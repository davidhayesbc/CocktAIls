"""Expose failing test names and bounded intake route diagnostics in CI annotations."""

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
            # This fixture's route diagnostics contain only UI routes/validation messages.
            first_line = (failure.get("message") or failure.text or "").splitlines()
            if first_line and first_line[0].startswith("java.lang.AssertionError: Intake route "):
                message += "; " + first_line[0][:1500]
            yield "::error::" + escape(message)


if __name__ == "__main__":
    for annotation in annotations(sys.argv[1]):
        print(annotation)
