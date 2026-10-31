import tempfile
from pathlib import Path
import unittest

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

    def test_success_and_skips_are_not_failures(self):
        self.assertEqual(self.report('<testsuite><testcase name="pass"/><testcase name="skip"><skipped/></testcase></testsuite>'), [])

    def test_errors_and_workflow_command_escaping(self):
        output = self.report('<testsuite><testcase classname="Intake" name="bad%&#10;::notice::"><error/></testcase></testsuite>')
        self.assertEqual(output, ["::error::Instrumentation failed: Intake.bad%25%0A::notice::"])


if __name__ == "__main__":
    unittest.main()
