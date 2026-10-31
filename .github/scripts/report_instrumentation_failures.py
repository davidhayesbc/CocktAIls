"""Expose failing test names and bounded intake diagnostics in CI annotations."""

from pathlib import Path
import sys
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
            yield "::error::" + escape(message)


if __name__ == "__main__":
    for annotation in annotations(sys.argv[1]):
        print(annotation)
