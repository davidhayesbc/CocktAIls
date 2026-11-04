"""Expose failing test names and bounded intake diagnostics in CI annotations."""

from pathlib import Path
import sys
import re
import xml.etree.ElementTree as ET


def escape(message):
    return message.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def annotations(directory):
    for path in sorted(Path(directory).rglob("TEST-*.xml")):
        root = ET.parse(path).getroot()
        for case in root.iter("testcase"):
            failure = case.find("failure")
            if failure is None:
                failure = case.find("error")
            if failure is None:
                continue
            message = f"Instrumentation failed: {case.get('classname', '?')}.{case.get('name', '?')}"
            # Do not publish raw test output, trees, or arbitrary assertion values.
            # These fixtures report only UI routes/validation messages or lifecycle flags.
            first_line = (failure.get("message") or failure.text or "").strip().splitlines()
            # JUnit's String assertEquals throws ComparisonFailure. XML writers
            # may serialize Throwable.toString(), or just getMessage() with a
            # separate type attribute. Require an exact fixture diagnostic prefix.
            diagnostic_prefixes = (
                "org.junit.ComparisonFailure: Intake route ",
                "java.lang.AssertionError: Intake route ",
                "Intake route ",
                "java.lang.AssertionError: App Exit lifecycle timeout; ",
                "App Exit lifecycle timeout; ",
            )
            if first_line and first_line[0].startswith(diagnostic_prefixes):
                message += "; " + first_line[0][:1500]
            if first_line and case.get("classname") == "com.davidhayesbc.cocktails.android.BundledLabelOcrTest":
                stages = "IMAGE|IMAGE_VALIDATION|DIRECT_RECOGNITION|DIRECT_FACTS|DIRECT_QUANTITY|DIRECT_ABV|OPEN_REPOSITORY|CREATE_SESSION|CREATE_DRAFT|ENQUEUE|WAIT_WORKER|WORKER_STATE|RECOGNITION_STATE|TEXT_FOUND|RECOGNITION_TIME|AI_STATE|AI_REASON|SUPPORTED_FIELDS|PRIVACY|CATEGORY"
                states = "ABSENT|QUEUED|RUNNING|COMPLETED|SKIPPED|FAILED|CANCELLED|STALE|INTERRUPTED"
                pattern = rf"(?:java\.lang\.AssertionError: )?Synthetic OCR diagnostic: stage=({stages}); failure=(TIMEOUT|ASSERTION|ILLEGAL_STATE|ILLEGAL_ARGUMENT|OTHER); worker=({states})"
                if re.fullmatch(pattern, first_line[0]):
                    message += "; " + first_line[0]
            yield "::error::" + escape(message)


if __name__ == "__main__":
    for annotation in annotations(sys.argv[1]):
        print(annotation)
