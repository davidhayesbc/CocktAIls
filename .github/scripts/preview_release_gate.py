"""Validate a prerelease API response before treating it as retry-safe."""

import json
import sys
from typing import Any


REQUIRED_ASSETS = {"CocktAIls-preview.apk", "SHA256SUMS.txt", "RELEASE_NOTES.md"}


def classify_release(http_status: int, payload: dict[str, Any] | None, expected_sha: str) -> dict[str, str]:
    if http_status == 404:
        return {"state": "missing", "url": ""}
    if http_status != 200 or payload is None:
        return {"state": "error", "url": ""}
    if not payload.get("prerelease") or f"Source commit: {expected_sha}" not in payload.get("body", ""):
        return {"state": "mismatch", "url": str(payload.get("html_url", ""))}
    names = {asset.get("name") for asset in payload.get("assets", [])}
    if not REQUIRED_ASSETS.issubset(names):
        return {"state": "mismatch", "url": str(payload.get("html_url", ""))}
    return {"state": "existing", "url": str(payload.get("html_url", ""))}


if __name__ == "__main__":
    status = int(sys.argv[1])
    expected_sha = sys.argv[2]
    payload = json.load(sys.stdin) if status == 200 else None
    print(json.dumps(classify_release(status, payload, expected_sha)))
