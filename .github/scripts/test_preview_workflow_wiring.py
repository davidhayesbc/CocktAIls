from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
PREVIEW_WORKFLOW = ROOT / ".github/workflows/preview-release.yml"
ANDROID_WORKFLOW = ROOT / ".github/workflows/android-ci.yml"


def section(source: str, name: str, next_name: str) -> str:
    start = source.index(f"  {name}:")
    end = source.index(f"  {next_name}:", start + 1)
    return source[start:end]


class PreviewWorkflowWiringTest(unittest.TestCase):
    def test_gate_checks_ref_before_checkout_and_checks_out_exact_sha(self):
        source = PREVIEW_WORKFLOW.read_text()
        job = section(source, "validate-dispatch", "prepare-preview")
        self.assertLess(job.index("Reject non-main refs"), job.index("actions/checkout@"))
        self.assertIn("ref: ${{ github.sha }}", job)
        self.assertIn("persist-credentials: false", job)
        self.assertIn("preview_ci_gate.py", job)

    def test_publish_job_checks_out_exact_sha_before_running_release_helpers(self):
        source = PREVIEW_WORKFLOW.read_text()
        job = source[source.index("  publish-preview:"):]
        self.assertIn("needs: prepare-preview", job)
        self.assertIn("ref: ${{ github.sha }}", job)
        self.assertIn("persist-credentials: false", job)
        self.assertIn("preview_release_gate.py", job)

    def test_unprivileged_android_ci_runs_gate_fixtures_without_secrets(self):
        source = ANDROID_WORKFLOW.read_text()
        self.assertIn("python3 -m unittest discover -s .github/scripts -p 'test_preview_*.py'", source)
        self.assertIn("permissions:\n  contents: read", source)


if __name__ == "__main__":
    unittest.main()
