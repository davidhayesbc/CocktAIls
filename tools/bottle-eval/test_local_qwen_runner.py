import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("run-local-qwen-gpu.py")
SPEC = importlib.util.spec_from_file_location("local_qwen_runner", SCRIPT)
RUNNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNNER)


class PromptJobValidationTests(unittest.TestCase):
    def setUp(self):
        self.config = RUNNER.read_config()
        canonical = json.dumps(self.config, ensure_ascii=False, separators=(",", ":"))
        # The Android exporter records its exact compact JSON bytes for the config hash.
        self.canonical = canonical
        system, user = "system text", "source prompt"
        self.task = {
            "caseId": "case-1",
            "sourceId": "source-1",
            "sourceInputSha256": "a" * 64,
            "taskSha256": hashlib.sha256((system + "\0" + user).encode()).hexdigest(),
            "systemPrompt": system,
            "userPrompt": user,
        }
        self.jobs = {
            "format": "com.davidhayesbc.cocktails.bottle-evaluation-prompt-jobs",
            "schemaVersion": 1,
            "datasetVersion": "test-v1",
            "splitPlan": "split-v1",
            "config": self.config,
            "configCanonicalJson": canonical,
            "configSha256": hashlib.sha256(canonical.encode()).hexdigest(),
            "caseIds": ["case-1"],
            "tasks": [self.task],
            "answerKeyIncluded": False,
        }

    def read_jobs(self, jobs):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "jobs.json"
            path.write_text(json.dumps(jobs, ensure_ascii=False), encoding="utf-8")
            return RUNNER.load_jobs(path, self.config)

    def test_valid_job_accepts_exact_task_and_config_hashes(self):
        jobs, digest = self.read_jobs(self.jobs)
        self.assertEqual(["case-1"], jobs["caseIds"])
        self.assertEqual(hashlib.sha256(json.dumps(self.jobs, ensure_ascii=False).encode()).hexdigest(), digest)

    def test_job_with_reference_answers_is_rejected(self):
        jobs = dict(self.jobs)
        jobs["sourceFacts"] = {"product_name": {"value": "gold label"}}
        with self.assertRaisesRegex(ValueError, "unsupported fields"):
            self.read_jobs(jobs)

    def test_task_tampering_is_rejected(self):
        jobs = json.loads(json.dumps(self.jobs))
        jobs["tasks"][0]["userPrompt"] = "changed source prompt"
        with self.assertRaisesRegex(ValueError, "task hash mismatch"):
            self.read_jobs(jobs)

    def test_missing_or_duplicate_task_index_is_rejected(self):
        jobs = json.loads(json.dumps(self.jobs))
        jobs["tasks"].append(dict(jobs["tasks"][0]))
        with self.assertRaisesRegex(ValueError, "duplicate case ID"):
            self.read_jobs(jobs)

    def test_host_command_uses_android_context_batch_and_legacy_template_mode(self):
        command = RUNNER.build_server_command(Path("llama-server.exe"), Path("model.gguf"), 9931, self.config)
        pairs = list(zip(command, command[1:]))
        values = {key: value for key, value in pairs if key.startswith("--")}
        baseline = self.config["androidBaseline"]
        self.assertEqual(str(baseline["contextTokens"]), values["--ctx-size"])
        self.assertEqual(str(baseline["batchTokens"]), values["--batch-size"])
        self.assertEqual(str(baseline["batchTokens"]), values["--ubatch-size"])
        self.assertIn("--no-jinja", command)
        self.assertIn("127.0.0.1", command)

    def test_inference_request_carries_production_grammar_and_decoding_settings(self):
        requests = []
        original = RUNNER.post_json

        def fake_post(url, body, timeout):
            requests.append((url, body))
            if url.endswith("/apply-template"):
                self.assertEqual("system text", body["messages"][0]["content"])
                self.assertEqual("source prompt", body["messages"][1]["content"])
                self.assertTrue(body["add_generation_prompt"])
                return {"prompt": "legacy-rendered-prompt"}
            return {"content": "{\"facts\":[]}", "stop_type": "limit", "timings": {"predicted_n": 160, "predicted_ms": 123, "prompt_ms": 45, "prompt_n": 20}}

        RUNNER.post_json = fake_post
        try:
            result = RUNNER.run_task("http://127.0.0.1:9931", self.task, self.config)
        finally:
            RUNNER.post_json = original
        completion = requests[1][1]
        baseline = self.config["androidBaseline"]
        self.assertEqual("legacy-rendered-prompt", completion["prompt"])
        self.assertEqual(baseline["grammar"], completion["grammar"])
        self.assertEqual(baseline["maxOutputTokens"], completion["n_predict"])
        self.assertEqual(baseline["samplers"], completion["samplers"])
        self.assertEqual("TOKEN_LIMIT", result["finishReason"])
        self.assertEqual(123, result["generationMillis"])


if __name__ == "__main__":
    unittest.main()
