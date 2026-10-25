import unittest

from preview_ci_gate import evaluate_runs


SHA = "abc123"
URL = "https://github.com/example/repo/actions/runs/1"


def run(status, conclusion=None, sha=SHA, event="push", attempt=1):
    return {
        "head_sha": sha,
        "event": event,
        "status": status,
        "conclusion": conclusion,
        "run_attempt": attempt,
        "created_at": "2026-10-25T00:00:00Z",
        "html_url": URL,
    }


class PreviewCiGateTest(unittest.TestCase):
    def test_absent_run_waits(self):
        self.assertEqual({"state": "missing", "url": ""}, evaluate_runs({"workflow_runs": []}, SHA))

    def test_queued_run_with_null_conclusion_waits(self):
        self.assertEqual("pending", evaluate_runs({"workflow_runs": [run("queued")]}, SHA)["state"])

    def test_in_progress_run_with_null_conclusion_waits(self):
        self.assertEqual("pending", evaluate_runs({"workflow_runs": [run("in_progress")]}, SHA)["state"])

    def test_completed_failure_fails_closed(self):
        self.assertEqual("failure", evaluate_runs({"workflow_runs": [run("completed", "failure")]}, SHA)["state"])

    def test_completed_success_passes(self):
        result = evaluate_runs({"workflow_runs": [run("completed", "success")]}, SHA)
        self.assertEqual({"state": "success", "url": URL}, result)

    def test_ignores_other_commits_and_events(self):
        result = evaluate_runs({"workflow_runs": [run("completed", "success", sha="other"), run("completed", "success", event="pull_request")]}, SHA)
        self.assertEqual("missing", result["state"])

    def test_latest_retry_attempt_controls_result(self):
        result = evaluate_runs(
            {"workflow_runs": [run("completed", "failure", attempt=1), run("in_progress", None, attempt=2)]},
            SHA,
        )
        self.assertEqual("pending", result["state"])


if __name__ == "__main__":
    unittest.main()
