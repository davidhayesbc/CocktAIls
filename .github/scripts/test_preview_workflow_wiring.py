from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
PREVIEW_WORKFLOW = ROOT / ".github/workflows/preview-release.yml"
ANDROID_WORKFLOW = ROOT / ".github/workflows/android-ci.yml"
ANDROID_SCREENSHOT_RUNNER = ROOT / ".github/scripts/run_android_instrumentation_with_screenshots.sh"


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

    def test_emulator_screenshots_are_collected_from_app_private_storage_before_uninstall(self):
        workflow = ANDROID_WORKFLOW.read_text()
        collector = ANDROID_SCREENSHOT_RUNNER.read_text()
        self.assertIn("bash .github/scripts/run_android_instrumentation_with_screenshots.sh", workflow)
        self.assertIn('shell run-as "$app_id" ls', collector)
        self.assertIn("exec-out run-as", collector)
        self.assertIn("connectedDebugAndroidTest", collector)
        self.assertIn("data.startswith(b\"\\x89PNG\\r\\n\\x1a\\n\")", collector)
        self.assertIn("readonly required_visual_screenshots=(", collector)
        self.assertIn("onboarding-integrated-setup.png", collector)
        self.assertIn("onboarding-barcode-scanner.png", collector)
        self.assertIn("onboarding-label-photo-preview.png", collector)
        self.assertIn("onboarding-label-crop-dialog.png", collector)
        self.assertIn("onboarding-review-real-route.png", collector)
        self.assertIn("onboarding-review-simulated.png", collector)
        self.assertIn("detailed-lookup-capture-preview.png", collector)
        self.assertIn("detailed-lookup-capture-preview-actions.png", collector)
        self.assertIn("detailed-lookup-capture-preview-large-font.png", collector)
        self.assertIn("detailed-lookup-debug-mode-consent.png", collector)
        self.assertIn("detailed-lookup-debug-mode-armed.png", collector)
        self.assertIn("detailed-lookup-debug-mode-captured.png", collector)
        required_visuals = collector.split("readonly required_visual_screenshots=(", 1)[1].split(")", 1)[0]
        self.assertIn("onboarding-label-crop-dialog.png", required_visuals)
        self.assertIn("onboarding-label-photo-preview.png", required_visuals)
        self.assertIn("onboarding-review-real-route.png", required_visuals)
        self.assertIn("detailed-lookup-capture-preview-actions.png", required_visuals)
        self.assertIn("detailed-lookup-capture-preview-large-font.png", required_visuals)
        self.assertIn("detailed-lookup-debug-mode-consent.png", required_visuals)
        self.assertIn("detailed-lookup-debug-mode-armed.png", required_visuals)
        self.assertIn("detailed-lookup-debug-mode-captured.png", required_visuals)
        self.assertIn("${{ runner.temp }}/onboarding-*.png", workflow)
        self.assertIn("${{ runner.temp }}/detailed-lookup-capture-*.png", workflow)
        self.assertIn("${{ runner.temp }}/detailed-lookup-debug-mode-*.png", workflow)
        self.assertIn("${{ runner.temp }}/detailed-lookup-debug-mode-*.png", workflow)
        self.assertIn('shell cmd uimode night yes', ANDROID_WORKFLOW.read_text())


if __name__ == "__main__":
    unittest.main()
