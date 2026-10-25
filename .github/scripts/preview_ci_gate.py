#!/usr/bin/env python3
"""Classify exact-source-commit Android CI workflow runs for preview publication."""

import json
import sys
from typing import Any


def evaluate_runs(payload: dict[str, Any], expected_sha: str) -> dict[str, str]:
    runs = [
        run
        for run in payload.get("workflow_runs", [])
        if run.get("head_sha") == expected_sha and run.get("event") == "push"
    ]
    if not runs:
        return {"state": "missing", "url": ""}

    runs.sort(key=lambda run: (run.get("created_at", ""), run.get("run_attempt", 0)))
    latest = runs[-1]
    status = latest.get("status")
    conclusion = latest.get("conclusion")
    if status != "completed" or conclusion is None:
        return {"state": "pending", "url": str(latest.get("html_url", ""))}
    if conclusion == "success":
        return {"state": "success", "url": str(latest.get("html_url", ""))}
    return {"state": "failure", "url": str(latest.get("html_url", ""))}


if __name__ == "__main__":
    print(json.dumps(evaluate_runs(json.load(sys.stdin), sys.argv[1])))
