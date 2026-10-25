import unittest

from preview_release_gate import classify_release


SHA = "abc123"
URL = "https://github.com/example/repo/releases/tag/preview"
ASSETS = [
    {"name": "CocktAIls-preview.apk"},
    {"name": "SHA256SUMS.txt"},
    {"name": "RELEASE_NOTES.md"},
]


class PreviewReleaseGateTest(unittest.TestCase):
    def test_absent_release_is_creation_path(self):
        self.assertEqual("missing", classify_release(404, None, SHA)["state"])

    def test_matching_existing_release_is_retry_safe(self):
        release = {"prerelease": True, "body": f"Source commit: {SHA}", "assets": ASSETS, "html_url": URL}
        self.assertEqual({"state": "existing", "url": URL}, classify_release(200, release, SHA))

    def test_wrong_commit_or_release_type_is_a_mismatch(self):
        wrong_commit = {"prerelease": True, "body": "Source commit: other", "assets": ASSETS, "html_url": URL}
        stable_release = {"prerelease": False, "body": f"Source commit: {SHA}", "assets": ASSETS, "html_url": URL}
        self.assertEqual("mismatch", classify_release(200, wrong_commit, SHA)["state"])
        self.assertEqual("mismatch", classify_release(200, stable_release, SHA)["state"])

    def test_missing_assets_or_api_errors_fail_closed(self):
        incomplete = {"prerelease": True, "body": f"Source commit: {SHA}", "assets": [], "html_url": URL}
        self.assertEqual("mismatch", classify_release(200, incomplete, SHA)["state"])
        self.assertEqual("error", classify_release(403, None, SHA)["state"])


if __name__ == "__main__":
    unittest.main()
