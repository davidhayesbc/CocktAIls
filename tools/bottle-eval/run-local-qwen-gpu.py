#!/usr/bin/env python3
"""Run the production extraction pipeline against a caller-provided local llama.cpp CUDA server.

No model downloads, package installs, external service calls, or raw prompt/output files are made.
The server is bound to loopback and stopped when the evaluation ends.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
CONFIG_PATH = ROOT / "tools/bottle-eval/qwen3-0.6b-q8-gpu.json"
CASES_PATH = ROOT / "apps/androidApp/src/test/resources/bottle-evaluation/v1/cases.json"
DEFAULT_REPORT = ROOT / "build/reports/bottle-evaluation/local-qwen3-gpu.json"
LOCAL_HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def read_config() -> dict:
    config = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
    if config.get("format") != "com.davidhayesbc.cocktails.bottle-evaluation-local-runtime" or config.get("schemaVersion") != 1:
        raise ValueError("Unsupported local runtime configuration")
    return config


def eligible_case_ids(cases_path: Path, requested: list[str] | None) -> list[str]:
    dataset = json.loads(cases_path.read_text(encoding="utf-8"))
    if dataset.get("format") != "com.davidhayesbc.cocktails.bottle-evaluation-cases":
        raise ValueError("Invalid bottle evaluation case manifest")
    eligible_cases = {
        case["id"]: case for case in dataset.get("cases", [])
        if case.get("kind") == "reviewed_product"
        and case.get("sourceCoverage") == "complete"
        and bool(case.get("pages"))
        and any(value.get("status") in {"SUPPORTED", "ABSENT", "CONFLICTING"}
                for value in case.get("sourceFacts", {}).values())
    }
    eligible = set(eligible_cases)
    selected = sorted(eligible if requested is None else set(requested))
    if not set(selected).issubset(eligible):
        invalid = sorted(set(selected) - eligible)
        raise ValueError("Local model evaluation only accepts complete reviewed-product source cases; rejected case IDs: " + ", ".join(invalid))
    if requested is not None and any(eligible_cases[case_id].get("split") != "development" for case_id in selected):
        raise ValueError("A selected subset may include development cases only; evaluate the full frozen manifest to score holdout.")
    return selected


def check_model(model_path: Path, config: dict) -> None:
    if not model_path.is_file():
        raise ValueError("The caller-provided Qwen model file does not exist; downloads are not performed by this runner.")
    expected = config["model"]["sha256"]
    digest = hashlib.sha256()
    with model_path.open("rb") as stream:
        for block in iter(lambda: stream.read(4 * 1024 * 1024), b""):
            digest.update(block)
    if digest.hexdigest() != expected:
        raise ValueError("Model SHA-256 does not match the pinned official Qwen artifact.")
    if model_path.stat().st_size != config["model"]["bytes"]:
        raise ValueError("Model file size does not match the pinned official Qwen artifact.")


def command_output(binary: Path, *args: str) -> str:
    result = subprocess.run([str(binary), *args], cwd=ROOT, text=True, capture_output=True, check=False)
    output = (result.stdout or "") + "\n" + (result.stderr or "")
    if result.returncode != 0:
        raise ValueError(f"llama.cpp preflight command failed ({args[0]}).")
    return output


def verify_server_build(binary: Path, config: dict) -> tuple[str, str]:
    submodule = subprocess.run(
        ["git", "-C", str(ROOT / config["llamaCpp"]["submodulePath"]), "rev-parse", "HEAD"],
        cwd=ROOT, text=True, capture_output=True, check=False,
    )
    if submodule.returncode != 0 or submodule.stdout.strip() != config["llamaCpp"]["commit"]:
        raise ValueError("Repository llama.cpp submodule does not match the configured pinned commit.")
    version = command_output(binary, "--version")
    expected = config["llamaCpp"]["serverVersionMustContain"]
    if expected not in version:
        raise ValueError("llama-server version does not identify the repository's pinned llama.cpp commit.")
    devices = command_output(binary, "--list-devices")
    if not re.search(r"(?i)CUDA\s*\d*", devices):
        raise ValueError("This llama-server build does not report a CUDA device; no inference was started.")
    clean_version = " ".join(version.split())[:300]
    clean_devices = " ".join(devices.split())[:500]
    return clean_version, clean_devices


def wait_for_health(process: subprocess.Popen, base_url: str, timeout_seconds: int = 240) -> None:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise ValueError("llama-server exited before becoming ready; inspect its local startup output.")
        try:
            with LOCAL_HTTP.open(base_url + "/health", timeout=2) as response:
                if response.status == 200:
                    return
        except (urllib.error.URLError, TimeoutError, OSError):
            pass
        time.sleep(1)
    raise ValueError("llama-server did not become healthy before the startup timeout.")


def start_private_log_reader(process: subprocess.Popen) -> tuple[threading.Event, dict[str, str]]:
    """Consume runtime logs without retaining raw lines or allowing pipe backpressure."""
    confirmed = threading.Event()
    result: dict[str, str] = {}
    pattern = re.compile(r"offloaded\s+(\d+)/(\d+)\s+layers\s+to\s+GPU", re.IGNORECASE)

    def read_lines() -> None:
        if process.stdout is None:
            return
        for line in process.stdout:
            match = pattern.search(line)
            if match and int(match.group(1)) > 0:
                result["offloadedLayers"] = f"{match.group(1)}/{match.group(2)}"
                confirmed.set()

    threading.Thread(target=read_lines, name="llama-server-safe-log-reader", daemon=True).start()
    return confirmed, result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", type=Path, help="Existing official Qwen3-0.6B-Q8_0.gguf file; never downloaded here")
    parser.add_argument("--llama-server", type=Path, help="Existing llama-server built from the pinned submodule")
    parser.add_argument("--cases", type=Path, default=CASES_PATH)
    parser.add_argument("--case-id", action="append", dest="case_ids", help="Run a selected complete development case only; repeatable")
    parser.add_argument("--report", type=Path, default=DEFAULT_REPORT)
    parser.add_argument("--port", type=int, default=9931)
    parser.add_argument("--preflight", action="store_true", help="Validate repository config/source coverage without starting a model")
    args = parser.parse_args()

    try:
        config = read_config()
        if not 1024 <= args.port <= 65535:
            raise ValueError("Port must be between 1024 and 65535.")
        selected_ids = eligible_case_ids(args.cases, args.case_ids)
        print(f"Evaluation config: {CONFIG_PATH.relative_to(ROOT)}")
        print(f"Eligible reviewed source cases: {len(selected_ids)} ({', '.join(selected_ids)})")
        if args.preflight:
            if not selected_ids:
                print("No complete, labeled frozen source cases are ready. No model or server was used.")
            else:
                print("Preflight only; no model or server was used.")
            return 0
        if not selected_ids:
            raise ValueError("No complete, labeled reviewed-product source cases are available; no model request was made.")
        if args.model is None or args.llama_server is None:
            raise ValueError("Provide --model and --llama-server. The runner does not download or build either artifact.")

        model_path = args.model.expanduser().resolve()
        binary = args.llama_server.expanduser().resolve()
        check_model(model_path, config)
        version, devices = verify_server_build(binary, config)
        host = config["hostRuntime"]
        base_url = f"http://127.0.0.1:{args.port}"
        server_args = [
            "--model", str(model_path), "--host", "127.0.0.1", "--port", str(args.port),
            "--ctx-size", str(config["androidBaseline"]["contextTokens"]),
            "--batch-size", str(config["androidBaseline"]["batchTokens"]),
            "--ubatch-size", str(config["androidBaseline"]["batchTokens"]),
            "--threads", str(host["threads"]), "--threads-batch", str(host["threads"]),
            "--parallel", "1", "--no-jinja", "--no-webui",
            "--device", host["device"], "--n-gpu-layers", str(host["gpuLayers"]),
            "--samplers", "penalties;dry;top_n_sigma;top_k;typ_p;top_p;min_p;xtc;temperature",
            "--temp", str(config["androidBaseline"]["temperature"]),
            "--seed", str(config["androidBaseline"]["seed"]),
            "--top-k", "40", "--top-p", "0.95", "--min-p", "0.05",
            "--top-n-sigma", "-1", "--typical-p", "1", "--xtc-probability", "0",
            "--xtc-threshold", "0.1", "--repeat-last-n", "64", "--repeat-penalty", "1",
            "--frequency-penalty", "0", "--presence-penalty", "0", "--dry-multiplier", "0",
            "--dry-base", "1.75", "--dry-allowed-length", "2", "--dry-penalty-last-n", "64",
        ]
        print("Starting a loopback-only llama.cpp server; requests stay on this machine.")
        process = subprocess.Popen(
            [str(binary), *server_args], cwd=ROOT, stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace", bufsize=1,
        )
        gpu_confirmed, log_summary = start_private_log_reader(process)
        try:
            wait_for_health(process, base_url)
            if not gpu_confirmed.wait(timeout=5):
                raise ValueError("llama-server became ready but did not confirm any layers offloaded to GPU; evaluation stopped.")
            gradle = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
            gpu_driver = "unavailable"
            try:
                driver_result = subprocess.run(
                    ["nvidia-smi", "--query-gpu=name,driver_version", "--format=csv,noheader"],
                    cwd=ROOT, text=True, capture_output=True, check=False, timeout=10,
                )
                if driver_result.returncode == 0:
                    gpu_driver = "; ".join(" ".join(line.split()) for line in driver_result.stdout.splitlines()[:2])
            except (OSError, subprocess.TimeoutExpired):
                pass
            command = [
                str(gradle), ":apps:androidApp:testDebugUnitTest",
                "--tests", "*BottleLookupEvaluationHarnessTest",
                f"-PbottleEvalCases={args.cases.resolve()}",
                f"-PbottleEvalReport={args.report.resolve()}",
                f"-PbottleEvalLocalServer={base_url}",
                f"-PbottleEvalLocalRuntime={version}",
                f"-PbottleEvalLocalBackend=CUDA; visible devices: {devices}; offloaded layers: {log_summary['offloadedLayers']}; driver: {gpu_driver}; host threads: {host['threads']}",
                f"-PbottleEvalModelSha256={config['model']['sha256']}",
                f"-PbottleEvalLlamaCppCommit={config['llamaCpp']['commit']}",
            ]
            if args.case_ids is not None:
                command.append(f"-PbottleEvalLocalCaseIds={','.join(selected_ids)}")
            if os.name == "nt":
                command = [os.environ.get("COMSPEC", "cmd.exe"), "/d", "/c", subprocess.list2cmdline(command)]
            completed = subprocess.run(command, cwd=ROOT, check=False)
            if completed.returncode != 0:
                return completed.returncode
        finally:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        print(f"Evaluation report written to {args.report.resolve()}")
        print("The report contains metrics and timings only. Host GPU timings are not Pixel CPU timings.")
        return 0
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as error:
        print(f"Local Qwen evaluation stopped: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
