#!/usr/bin/env python3
"""Run exported production prompts through an already-installed local llama.cpp server.

This Windows-friendly stage does not invoke Gradle, download artifacts, or send data off-device.
Inputs and raw model outputs contain public-source text; keep both files private.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

CONFIG_PATH = Path(__file__).resolve().with_name("qwen3-0.6b-q8-gpu.json")
NO_PROXY = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def read_config() -> dict:
    config = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
    if config.get("format") != "com.davidhayesbc.cocktails.bottle-evaluation-local-runtime" or config.get("schemaVersion") != 1:
        raise ValueError("Unsupported local runtime configuration")
    return config


def load_jobs(path: Path, config: dict) -> tuple[dict, str]:
    if path.stat().st_size > 8 * 1024 * 1024:
        raise ValueError("Prompt-job file exceeds the 8 MiB safety limit")
    raw = path.read_bytes()
    job_sha = sha256(raw)
    jobs = json.loads(raw)
    if jobs.get("format") != "com.davidhayesbc.cocktails.bottle-evaluation-prompt-jobs" or jobs.get("schemaVersion") != 1:
        raise ValueError("Unsupported prompt-job file")
    if set(jobs) != {"format", "schemaVersion", "datasetVersion", "splitPlan", "config", "configCanonicalJson", "configSha256", "caseIds", "eligibleCaseIds", "noInferenceCaseIds", "noInferenceReasons", "tasks", "answerKeyIncluded"}:
        raise ValueError("Prompt-job file contains unsupported fields; expected a minimal no-answer-key payload")
    if jobs.get("answerKeyIncluded") is not False:
        raise ValueError("Prompt-job file must explicitly exclude reference answers")
    canonical = jobs.get("configCanonicalJson")
    if not isinstance(canonical, str) or sha256(canonical.encode("utf-8")) != jobs.get("configSha256"):
        raise ValueError("Prompt-job configuration hash is invalid")
    exported_config = json.loads(canonical)
    if exported_config != config:
        raise ValueError("Prompt-job config does not match the pinned runtime configuration")
    tasks = jobs.get("tasks")
    if not isinstance(tasks, list) or len(tasks) > 100:
        raise ValueError("Prompt jobs must contain at most 100 tasks")
    ids = set()
    for task in tasks:
        if set(task) != {"caseId", "sourceId", "sourceInputSha256", "taskSha256", "systemPrompt", "userPrompt"}:
            raise ValueError("Prompt task contains unsupported fields")
        case_id = task.get("caseId")
        if not isinstance(case_id, str) or not case_id or case_id in ids:
            raise ValueError("Prompt jobs contain a missing or duplicate case ID")
        ids.add(case_id)
        system = task.get("systemPrompt")
        user = task.get("userPrompt")
        if not isinstance(system, str) or not isinstance(user, str) or len(system) > 16_000 or len(user) > 24_000:
            raise ValueError("Prompt job is malformed or exceeds bounded prompt size")
        expected = sha256((system + "\0" + user).encode("utf-8"))
        if task.get("taskSha256") != expected:
            raise ValueError("Prompt task hash mismatch")
        if not re.fullmatch(r"[a-f0-9]{64}", task.get("sourceInputSha256", "")):
            raise ValueError("Prompt job is missing its frozen-source hash")
    if jobs.get("caseIds") != sorted(ids):
        raise ValueError("Prompt-job case index does not match tasks")
    eligible = jobs.get("eligibleCaseIds")
    no_inference = jobs.get("noInferenceCaseIds")
    no_inference_reasons = jobs.get("noInferenceReasons")
    if not isinstance(eligible, list) or not eligible or len(eligible) > 100 or eligible != sorted(set(eligible)):
        raise ValueError("Prompt-job eligible-case index must contain 1–100 sorted unique case IDs")
    if not isinstance(no_inference, list) or len(no_inference) > 100 or no_inference != sorted(set(no_inference)):
        raise ValueError("Prompt-job no-inference index must contain at most 100 sorted unique case IDs")
    allowed_no_inference_reasons = {"DETERMINISTIC_SOURCE", "RETRIEVAL_ABSTENTION", "PRE_INFERENCE_REJECTION"}
    if not isinstance(no_inference_reasons, dict) or set(no_inference_reasons) != set(no_inference) or any(
        reason not in allowed_no_inference_reasons for reason in no_inference_reasons.values()
    ):
        raise ValueError("Prompt-job no-inference reasons are missing, extra, or unsupported")
    if sorted(ids | set(no_inference)) != eligible or ids.intersection(no_inference):
        raise ValueError("Prompt tasks and no-inference cases must partition the eligible cases")
    return jobs, job_sha


def command_output(binary: Path, *args: str) -> str:
    result = subprocess.run([str(binary), *args], text=True, capture_output=True, check=False, timeout=30)
    if result.returncode != 0:
        raise ValueError(f"llama.cpp preflight command failed ({args[0]})")
    return (result.stdout or "") + "\n" + (result.stderr or "")


def check_model(path: Path, config: dict) -> None:
    model = config["model"]
    if not path.is_file() or path.stat().st_size != model["bytes"]:
        raise ValueError("Model file is missing or has an unexpected size; no download is attempted")
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(4 * 1024 * 1024), b""):
            digest.update(block)
    if digest.hexdigest() != model["sha256"]:
        raise ValueError("Model SHA-256 does not match the pinned official Qwen artifact")


def verify_server(binary: Path, config: dict) -> tuple[str, str]:
    version = command_output(binary, "--version")
    expected = config["llamaCpp"]["serverVersionMustContain"]
    if expected not in version:
        raise ValueError("llama-server version does not match the pinned llama.cpp build")
    devices = command_output(binary, "--list-devices")
    if not re.search(r"(?i)CUDA\s*\d*", devices):
        raise ValueError("This llama-server build reports no CUDA device")
    return " ".join(version.split())[:300], " ".join(devices.split())[:500]


def post_json(url: str, body: dict, timeout: int) -> dict:
    request = urllib.request.Request(
        url,
        data=json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with NO_PROXY.open(request, timeout=timeout) as response:
        data = response.read(1_048_577)
        if len(data) > 1_048_576:
            raise ValueError("Local llama.cpp response exceeded 1 MiB")
        return json.loads(data)


def wait_for_health(process: subprocess.Popen, base: str, timeout: int = 240) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise ValueError("llama-server exited before becoming ready")
        try:
            with NO_PROXY.open(base + "/health", timeout=2) as response:
                if response.status == 200:
                    return
        except (urllib.error.URLError, TimeoutError, OSError):
            pass
        time.sleep(1)
    raise ValueError("llama-server startup timed out")


def safe_log_reader(process: subprocess.Popen) -> tuple[threading.Event, dict[str, str]]:
    gpu = threading.Event()
    summary: dict[str, str] = {}
    pattern = re.compile(r"offloaded\s+(\d+)/(\d+)\s+layers\s+to\s+GPU", re.IGNORECASE)

    def consume() -> None:
        if process.stdout is None:
            return
        for line in process.stdout:
            match = pattern.search(line)
            if match and int(match.group(1)) > 0:
                summary["gpuOffloadedLayers"] = f"{match.group(1)}/{match.group(2)}"
                gpu.set()

    threading.Thread(target=consume, name="llama-safe-log-reader", daemon=True).start()
    return gpu, summary


def run_task(base: str, task: dict, config: dict) -> dict:
    templated = post_json(base + "/apply-template", {"messages": [
        {"role": "system", "content": task["systemPrompt"]},
        {"role": "user", "content": task["userPrompt"]},
    ], "add_generation_prompt": True}, timeout=30)
    rendered = templated.get("prompt")
    if not isinstance(rendered, str) or not rendered:
        raise ValueError("Pinned llama.cpp template endpoint omitted rendered prompt")

    android = config["androidBaseline"]
    sampler = android["samplerParameters"]
    body = {
        "prompt": rendered,
        "n_predict": android["maxOutputTokens"],
        "seed": android["seed"],
        "temperature": android["temperature"],
        "top_k": sampler["top_k"], "top_p": sampler["top_p"], "min_p": sampler["min_p"],
        "top_n_sigma": sampler["top_n_sigma"], "typical_p": sampler["typical_p"],
        "xtc_probability": sampler["xtc_probability"], "xtc_threshold": sampler["xtc_threshold"],
        "repeat_last_n": sampler["repeat_last_n"], "repeat_penalty": sampler["repeat_penalty"],
        "frequency_penalty": sampler["frequency_penalty"], "presence_penalty": sampler["presence_penalty"],
        "dry_multiplier": sampler["dry_multiplier"], "dry_base": sampler["dry_base"],
        "dry_allowed_length": sampler["dry_allowed_length"], "dry_penalty_last_n": sampler["dry_penalty_last_n"],
        "dry_sequence_breakers": sampler["dry_sequence_breakers"],
        "samplers": android["samplers"],
        "grammar": android["grammar"],
        "cache_prompt": False,
        "timings_per_token": True,
    }
    started = time.monotonic_ns()
    completion = post_json(base + "/completion", body, timeout=600)
    request_ms = max(0, (time.monotonic_ns() - started) // 1_000_000)
    timings = completion.get("timings") or {}
    stop = {"eos": "END_OF_SEQUENCE", "limit": "TOKEN_LIMIT", "word": "STOP_WORD"}.get(completion.get("stop_type"), "UNKNOWN")
    output = completion.get("content")
    if not isinstance(output, str):
        raise ValueError("Pinned llama.cpp completion endpoint omitted model output")
    return {
        "caseId": task["caseId"], "taskSha256": task["taskSha256"],
        "sourceInputSha256": task["sourceInputSha256"],
        "modelOutput": output,
        "finishReason": stop,
        "generatedTokens": timings.get("predicted_n", completion.get("tokens_predicted")),
        "generationMillis": round(timings["predicted_ms"]) if isinstance(timings.get("predicted_ms"), (int, float)) else None,
        "promptEvaluationMillis": round(timings["prompt_ms"]) if isinstance(timings.get("prompt_ms"), (int, float)) else None,
        "promptTokens": timings.get("prompt_n"),
        "requestRoundTripMillis": request_ms,
    }


def build_server_command(binary: Path, model: Path, port: int, config: dict) -> list[str]:
    baseline = config["androidBaseline"]
    host = config["hostRuntime"]
    return [
        str(binary), "--model", str(model), "--host", "127.0.0.1", "--port", str(port),
        "--ctx-size", str(baseline["contextTokens"]), "--batch-size", str(baseline["batchTokens"]),
        "--ubatch-size", str(baseline["batchTokens"]), "--threads", str(host["threads"]),
        "--threads-batch", str(host["threads"]), "--parallel", "1", "--no-jinja", "--no-webui",
        "--device", host["device"], "--n-gpu-layers", str(host["gpuLayers"]),
    ]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jobs", type=Path, required=True, help="Prompt jobs exported by the Android JVM harness")
    parser.add_argument("--model", type=Path, required=True, help="Existing pinned Qwen GGUF; never downloaded here")
    parser.add_argument("--llama-server", type=Path, required=True, help="Existing pinned CUDA llama-server executable")
    parser.add_argument("--results", type=Path, required=True, help="Private output path for raw model results")
    parser.add_argument("--port", type=int, default=9931)
    args = parser.parse_args()
    process = None
    try:
        config = read_config()
        if not 1024 <= args.port <= 65535:
            raise ValueError("Port must be between 1024 and 65535")
        jobs, job_sha = load_jobs(args.jobs.expanduser().resolve(), config)
        if not jobs["tasks"]:
            print("No model prompts were produced by the production extractor; no GPU inference was needed.")
            return 0
        model = args.model.expanduser().resolve()
        binary = args.llama_server.expanduser().resolve()
        check_model(model, config)
        version, devices = verify_server(binary, config)
        baseline = config["androidBaseline"]
        base = f"http://127.0.0.1:{args.port}"
        command = build_server_command(binary, model, args.port, config)
        print("Starting pinned llama.cpp on loopback; prompts and raw results stay on this desktop.")
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace", bufsize=1)
        gpu_ready, log_summary = safe_log_reader(process)
        wait_for_health(process, base)
        if not gpu_ready.wait(timeout=5):
            raise ValueError("Runtime did not confirm CUDA layer offload; evaluation stopped")

        outputs = []
        for index, task in enumerate(jobs["tasks"], start=1):
            print(f"Running local inference {index}/{len(jobs['tasks'])}: {task['caseId']}")
            outputs.append(run_task(base, task, config))

        result = {
            "format": "com.davidhayesbc.cocktails.bottle-evaluation-host-results",
            "schemaVersion": 1,
            "jobFileSha256": job_sha,
            "configSha256": jobs["configSha256"],
            "modelSha256": config["model"]["sha256"],
            "llamaCppCommit": config["llamaCpp"]["commit"],
            "runtimeVersion": version,
            "runtimeBackend": "CUDA",
            "devices": devices,
            "gpuOffloadedLayers": log_summary.get("gpuOffloadedLayers", "unavailable"),
            "inferenceSettings": {
                "contextTokens": baseline["contextTokens"],
                "batchTokens": baseline["batchTokens"],
                "maxOutputTokens": baseline["maxOutputTokens"],
                "temperature": baseline["temperature"],
                "grammarSha256": sha256(baseline["grammar"].encode("utf-8")),
                "samplers": baseline["samplers"],
            },
            "hostRuntime": "Windows/desktop CUDA; not comparable to Android CPU timing",
            "responses": outputs,
        }
        destination = args.results.expanduser().resolve()
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"Wrote {len(outputs)} result records to {destination}")
        print("The results include raw model output; review and transfer privately to the cloud JVM importer.")
        return 0
    except (OSError, ValueError, KeyError, json.JSONDecodeError, urllib.error.URLError, TimeoutError, subprocess.TimeoutExpired) as error:
        print(f"Local Qwen evaluation stopped: {error}", file=sys.stderr)
        return 2
    finally:
        if process is not None:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()


if __name__ == "__main__":
    raise SystemExit(main())
