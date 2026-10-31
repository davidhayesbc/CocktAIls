import tempfile
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

from report_instrumentation_failures import annotations


class FailureReportingTest(unittest.TestCase):
    def report(self, xml):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "device" / "TEST-intake.xml"
            path.parent.mkdir()
            path.write_text(xml)
            return list(annotations(directory))

    def test_reports_route_without_stack_or_system_output(self):
        output = self.report('''<testsuite><testcase classname="Intake" name="restore">
          <failure message="java.lang.AssertionError: Intake route after opening; expected review but was queue">private stack</failure>
          <system-out>private output</system-out></testcase></testsuite>''')
        self.assertEqual(output, ["::error::Instrumentation failed: Intake.restore; java.lang.AssertionError: Intake route after opening; expected review but was queue"])

    def test_other_failures_report_identity_only(self):
        output = self.report('''<testsuite><testcase classname="Intake" name="other">
          <failure message="sensitive assertion value"/></testcase></testsuite>''')
        self.assertEqual(output, ["::error::Instrumentation failed: Intake.other"])

    def test_comparison_failure_xml_serializations(self):
        # JUnit String assertEquals emits ComparisonFailure, not AssertionError.
        detail = "Intake route after manual barcode submission; manual submission error=none expected:<[Scan the next bottle]> but was:<[Add a bottle manually]>"
        for prefix in ("org.junit.ComparisonFailure: ", ""):
            for location in ("message", "body"):
                with self.subTest(prefix=prefix, location=location):
                    root = ET.Element("testsuite")
                    case = ET.SubElement(root, "testcase", classname="Intake", name="restore")
                    failure = ET.SubElement(case, "failure", type="org.junit.ComparisonFailure")
                    diagnostic = prefix + detail
                    if location == "message":
                        failure.set("message", diagnostic)
                        failure.text = "private stack"
                    else:
                        failure.text = "\n" + diagnostic + "\nprivate stack"
                    output = self.report(ET.tostring(root, encoding="unicode"))
                    self.assertEqual(output, ["::error::Instrumentation failed: Intake.restore; " + diagnostic])

    def test_comparison_failure_without_route_prefix_remains_private(self):
        output = self.report('''<testsuite><testcase classname="Intake" name="other">
          <failure type="org.junit.ComparisonFailure" message="org.junit.ComparisonFailure: sensitive expected value"/>
          </testcase></testsuite>''')
        self.assertEqual(output, ["::error::Instrumentation failed: Intake.other"])

    def test_exit_lifecycle_flags_are_reported_without_stack(self):
        detail = "App Exit lifecycle timeout; isFinishing=true; isDestroyed=false expected:&lt;DESTROYED&gt; but was:&lt;STARTED&gt;"
        for prefix in ("java.lang.AssertionError: ", ""):
            with self.subTest(prefix=prefix):
                output = self.report(f'''<testsuite><testcase classname="Intake" name="exit">
                  <failure message="{prefix}{detail}">private stack</failure></testcase></testsuite>''')
                self.assertEqual(output, ["::error::Instrumentation failed: Intake.exit; " + prefix + detail.replace("&lt;", "<").replace("&gt;", ">")])

    def test_route_diagnostic_is_bounded_and_escaped(self):
        detail = "Intake route " + "%" * 2000
        root = ET.Element("testsuite")
        case = ET.SubElement(root, "testcase", classname="Intake", name="restore")
        ET.SubElement(case, "failure", message=detail)
        self.assertEqual(self.report(ET.tostring(root, encoding="unicode")), [
            "::error::Instrumentation failed: Intake.restore; " + detail[:1500].replace("%", "%25")
        ])

    def test_success_and_skips_are_not_failures(self):
        self.assertEqual(self.report('<testsuite><testcase name="pass"/><testcase name="skip"><skipped/></testcase></testsuite>'), [])

    def test_errors_and_workflow_command_escaping(self):
        output = self.report('<testsuite><testcase classname="Intake" name="bad%&#10;::notice::"><error/></testcase></testsuite>')
        self.assertEqual(output, ["::error::Instrumentation failed: Intake.bad%25%0A::notice::"])


if __name__ == "__main__":
    unittest.main()
