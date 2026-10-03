"""Native-evidence parser regressions; these never substitute for a Rider run."""
import contextlib
import io
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

from check_project_build_results import CLASS, EXPECTED, PREFIX, EvidenceError, main, verify

AUDIT = [PREFIX + "PASS case=" + name for name in sorted(EXPECTED)]


class ProjectBuildEvidenceTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.path = Path(temporary.name) / "project-build.xml"

    def report(self, case_stdout=False):
        suite = ET.Element("testsuite", name=CLASS, tests=str(len(EXPECTED)), failures="0", errors="0", skipped="0")
        cases = []
        for name in sorted(EXPECTED):
            case = ET.SubElement(suite, "testcase", classname=CLASS, name=name + "()")
            if case_stdout:
                ET.SubElement(case, "system-out").text = PREFIX + "PASS case=" + name
            cases.append(case)
        if not case_stdout:
            ET.SubElement(suite, "system-out").text = "\n".join(AUDIT)
        return suite, cases

    def write(self, root):
        ET.ElementTree(root).write(self.path, encoding="utf-8", xml_declaration=True)

    def rejected(self, root, message):
        self.write(root)
        with self.assertRaisesRegex(EvidenceError, message):
            verify(self.path)

    def test_passing_exact_native_cases_emit_only_fixed_audit_records(self):
        for case_stdout in (False, True):
            with self.subTest(case_stdout=case_stdout):
                root, cases = self.report(case_stdout)
                for case in cases:
                    case.set("name", case.get("name").removesuffix("()"))
                self.write(root)
                self.assertEqual(AUDIT, verify(self.path))
                stdout, stderr = io.StringIO(), io.StringIO()
                with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                    self.assertEqual(0, main([str(self.path)]))
                self.assertEqual("\n".join(AUDIT) + "\n", stdout.getvalue())
                self.assertEqual("", stderr.getvalue())

    def test_missing_and_malformed_reports_fail_without_echoing_contents_or_path(self):
        for contents in (None, '<private-project report="secret-value">'):
            with self.subTest(contents=contents):
                if contents is not None:
                    self.path.write_text(contents)
                stdout, stderr = io.StringIO(), io.StringIO()
                with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                    self.assertEqual(1, main([str(self.path)]))
                self.assertEqual("", stdout.getvalue())
                self.assertEqual("Rider project build evidence rejected: Missing or invalid JUnit report\n", stderr.getvalue())

    def test_missing_wrong_and_duplicate_suites_fail(self):
        suite, _ = self.report()
        suite.set("name", CLASS + "Lookalike")
        self.rejected(suite, "native project build suite")
        root = ET.Element("testsuites")
        root.extend([self.report()[0], self.report()[0]])
        self.rejected(root, "native project build suite")

    def test_every_named_case_is_required_with_exact_class_and_method(self):
        for index in range(len(EXPECTED)):
            for change in ("missing", "wrong-class", "suffix", "duplicate"):
                with self.subTest(index=index, change=change):
                    suite, cases = self.report()
                    case = cases[index]
                    if change == "missing":
                        suite.remove(case)
                    elif change == "wrong-class":
                        case.set("classname", CLASS + "Lookalike")
                    elif change == "suffix":
                        case.set("name", case.get("name") + "Lookalike")
                    else:
                        ET.SubElement(suite, "testcase", case.attrib)
                    suite.set("tests", str(len(suite.findall("testcase"))))
                    self.rejected(suite, "required native project build case")

    def test_skipped_disabled_failed_and_errored_cases_fail_without_leaking(self):
        for tag in ("skipped", "disabled", "failure", "error"):
            with self.subTest(tag=tag):
                suite, cases = self.report()
                ET.SubElement(cases[0], tag, message="private failure path").text = "private stack trace"
                self.write(suite)
                stdout, stderr = io.StringIO(), io.StringIO()
                with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                    self.assertEqual(1, main([str(self.path)]))
                self.assertEqual("", stdout.getvalue())
                self.assertEqual("Rider project build evidence rejected: Native project build suite did not pass\n", stderr.getvalue())

    def test_nonzero_or_invalid_suite_counters_fail(self):
        for counter in ("failures", "errors", "skipped", "disabled"):
            for value in ("1", "-1", "unknown", ""):
                with self.subTest(counter=counter, value=value):
                    suite, _ = self.report()
                    suite.set(counter, value)
                    self.rejected(suite, "did not pass")

    def test_nonpassing_case_status_and_result_fail(self):
        for attribute, value in (("status", "notrun"), ("status", "skipped"), ("result", "suppressed"), ("result", "")):
            with self.subTest(attribute=attribute, value=value):
                suite, cases = self.report()
                cases[0].set(attribute, value)
                self.rejected(suite, "case did not pass")

    def test_case_count_mismatch_fails(self):
        suite, _ = self.report()
        suite.set("tests", "0")
        self.rejected(suite, "case count")

    def test_every_audit_record_required_and_duplicates_rejected(self):
        for index in range(len(AUDIT)):
            suite, _ = self.report()
            suite.find("system-out").text = "\n".join(AUDIT[:index] + AUDIT[index + 1:])
            self.rejected(suite, "Missing required.*audit")
        suite, _ = self.report()
        suite.find("system-out").text += "\n" + AUDIT[0]
        self.rejected(suite, "Duplicate required.*audit")

    def test_audit_cannot_be_borrowed_from_another_case_or_suite(self):
        suite, cases = self.report(case_stdout=True)
        a, b = cases[0].find("system-out"), cases[1].find("system-out")
        a.text, b.text = b.text, a.text
        self.rejected(suite, "different case")
        for other_suite in (False, True):
            root = ET.Element("testsuites")
            suite, _ = self.report()
            suite.find("system-out").text = ""
            root.append(suite)
            if other_suite:
                other = ET.SubElement(root, "testsuite", name="com.example.Unrelated")
            else:
                other = ET.SubElement(suite, "testcase", classname=CLASS, name="unrelatedMethod()")
                suite.set("tests", str(len(EXPECTED) + 1))
            ET.SubElement(other, "system-out").text = "\n".join(AUDIT)
            self.rejected(root, "Missing required.*audit")

    def test_unrelated_output_and_sensitive_fields_are_suppressed(self):
        suite, _ = self.report()
        private = "HOME=/private/account\nprivate compiler stdout\nlog prefix " + AUDIT[0]
        suite.find("system-out").text = private + "\n" + "\n".join(AUDIT)
        ET.SubElement(suite, "system-err").text = private
        ET.SubElement(ET.SubElement(suite, "properties"), "property", name="secret", value="private")
        self.write(suite)
        self.assertEqual(AUDIT, verify(self.path))

    def test_malformed_unknown_and_payload_carrying_audit_fails_closed(self):
        for record in (PREFIX + "PASS case=unknown", AUDIT[0] + " path=/private", PREFIX + "UNKNOWN private", PREFIX + "PASS case=/private"):
            with self.subTest(record=record):
                suite, _ = self.report()
                suite.find("system-out").text += "\n" + record
                self.rejected(suite, "Malformed controlled")


if __name__ == "__main__":
    unittest.main(verbosity=2)
