"""Small fail-closed and stdout-allowlist tests; no IDE host required."""
import contextlib
import io
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

from check_trust_results import CLASS, EXPECTED, PREFIX, EvidenceError, main, verify


AUDIT = [
    PREFIX + "TRUST trusted=false",
    PREFIX + "FILTERS trusted=false count=1",
    PREFIX + "FILTER trusted=false class=com.example.SafeFilter$Nested prohibited=true",
    PREFIX + "TRUST trusted=true",
    PREFIX + "FILTERS trusted=true count=1",
    PREFIX + "FILTER trusted=true class=com.example.SafeFilter$Nested prohibited=false",
]


class TrustEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "native-trust.xml"

    def report(self, audit=None, case_stdout=False):
        suite = ET.Element("testsuite", name=CLASS, tests="1", failures="0", errors="0", skipped="0")
        case = ET.SubElement(suite, "testcase", classname=CLASS, name=next(iter(EXPECTED)) + "()")
        ET.SubElement(case if case_stdout else suite, "system-out").text = "\n".join(AUDIT if audit is None else audit)
        return suite, case

    def write(self, root):
        ET.ElementTree(root).write(self.path, encoding="utf-8", xml_declaration=True)

    def test_passing_native_case_exposes_only_controlled_records(self):
        suite, _ = self.report()
        self.write(suite)
        self.assertEqual(AUDIT, verify(self.path))
        stdout, stderr = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            self.assertEqual(0, main([str(self.path)]))
        self.assertEqual("\n".join(AUDIT) + "\n", stdout.getvalue())
        self.assertEqual("", stderr.getvalue())

    def test_empty_filter_enumerations_are_explicit_evidence(self):
        audit = [PREFIX + "TRUST trusted=false", PREFIX + "FILTERS trusted=false count=0",
                 PREFIX + "TRUST trusted=true", PREFIX + "FILTERS trusted=true count=0"]
        suite, _ = self.report(audit)
        self.write(suite)
        self.assertEqual(audit, verify(self.path))

    def test_case_stdout_and_bare_method_name_are_supported(self):
        suite, case = self.report(case_stdout=True)
        case.set("name", next(iter(EXPECTED)))
        self.write(suite)
        self.assertEqual(AUDIT, verify(self.path))

    def test_missing_report_and_malformed_xml_fail_without_echoing_input(self):
        for contents in (None, '<secret-report-path encoding="private-value">'):
            with self.subTest(contents=contents):
                if contents is not None:
                    self.path.write_text(contents)
                stdout, stderr = io.StringIO(), io.StringIO()
                with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                    self.assertEqual(1, main([str(self.path)]))
                self.assertEqual("", stdout.getvalue())
                self.assertEqual("Rider trust evidence rejected: Missing or invalid JUnit report\n", stderr.getvalue())

    def test_missing_native_suite_fails(self):
        suite, _ = self.report()
        suite.set("name", "com.example.UnrelatedTest")
        self.write(suite)
        with self.assertRaisesRegex(EvidenceError, "native trust suite"):
            verify(self.path)

    def test_missing_or_lookalike_native_case_fails(self):
        for change in ("missing", "wrong-class", "method-suffix"):
            with self.subTest(change=change):
                suite, case = self.report()
                if change == "missing":
                    suite.remove(case)
                    suite.set("tests", "0")
                elif change == "wrong-class":
                    case.set("classname", "com.example.UnrelatedTest")
                else:
                    case.set("name", next(iter(EXPECTED)) + "NotReally")
                self.write(suite)
                with self.assertRaisesRegex(EvidenceError, "required native trust case"):
                    verify(self.path)

    def test_skipped_failed_and_errored_native_cases_fail(self):
        for tag in ("skipped", "failure", "error"):
            with self.subTest(tag=tag):
                suite, case = self.report()
                ET.SubElement(case, tag, message="private failure text").text = "private stack trace"
                self.write(suite)
                stdout, stderr = io.StringIO(), io.StringIO()
                with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                    self.assertEqual(1, main([str(self.path)]))
                self.assertEqual("", stdout.getvalue())
                self.assertEqual("Rider trust evidence rejected: Native trust suite did not pass\n", stderr.getvalue())

    def test_nonpassing_suite_counters_fail_even_without_detail_tags(self):
        for counter in ("skipped", "failures", "errors", "disabled"):
            with self.subTest(counter=counter):
                suite, _ = self.report()
                suite.set(counter, "1")
                self.write(suite)
                with self.assertRaisesRegex(EvidenceError, "did not pass"):
                    verify(self.path)

    def test_duplicate_cases_and_suites_fail(self):
        suite, case = self.report()
        ET.SubElement(suite, "testcase", case.attrib)
        suite.set("tests", "2")
        self.write(suite)
        with self.assertRaisesRegex(EvidenceError, "duplicate required"):
            verify(self.path)
        root = ET.Element("testsuites")
        root.extend([self.report()[0], self.report()[0]])
        self.write(root)
        with self.assertRaisesRegex(EvidenceError, "duplicate native trust suite"):
            verify(self.path)

    def test_explicit_notrun_status_or_inconsistent_count_fails(self):
        suite, case = self.report()
        case.set("status", "notrun")
        self.write(suite)
        with self.assertRaisesRegex(EvidenceError, "did not pass"):
            verify(self.path)
        suite, _ = self.report()
        suite.set("tests", "0")
        self.write(suite)
        with self.assertRaisesRegex(EvidenceError, "case count"):
            verify(self.path)

    def test_missing_trust_or_filter_audit_fails(self):
        for audit in ([], AUDIT[:3], [AUDIT[0], AUDIT[3]]):
            with self.subTest(audit=audit):
                suite, _ = self.report(audit)
                self.write(suite)
                with self.assertRaisesRegex(EvidenceError, "Missing trust or filter evidence"):
                    verify(self.path)

    def test_filter_count_mismatch_or_duplicate_decision_fails(self):
        for audit in (AUDIT[:2] + AUDIT[3:], AUDIT[:3] + [AUDIT[2]] + AUDIT[3:]):
            with self.subTest(audit=audit):
                suite, _ = self.report(audit)
                self.write(suite)
                with self.assertRaises(EvidenceError):
                    verify(self.path)

    def test_unrelated_stdout_stderr_properties_and_other_tests_are_suppressed(self):
        raw = ["HOME=/private/environment", "/private/project/path", "arbitrary compiler output",
               "log prefix " + PREFIX + "TRUST trusted=false"]
        suite, _ = self.report(raw + AUDIT + ["after: private profile data"])
        ET.SubElement(suite, "system-err").text = "\n".join(raw)
        ET.SubElement(ET.SubElement(suite, "properties"), "property", name="private", value="private value")
        other_case = ET.SubElement(suite, "testcase", classname=CLASS, name="unrelatedMethod()")
        ET.SubElement(other_case, "system-out").text = PREFIX + "TRUST trusted=true"
        suite.set("tests", "2")
        root = ET.Element("testsuites")
        root.append(suite)
        other_suite = ET.SubElement(root, "testsuite", name="com.example.OtherTest")
        ET.SubElement(other_suite, "system-out").text = PREFIX + "TRUST trusted=true"
        self.write(root)
        self.assertEqual(AUDIT, verify(self.path))

    def test_arbitrary_fields_and_invalid_class_names_are_never_emitted(self):
        for record in (PREFIX + "TRUST trusted=true environment=private",
                       PREFIX + "FILTER trusted=true class=/private/path prohibited=false",
                       PREFIX + "FILTER trusted=true class=com.example.Filter prohibited=false trailing-private",
                       PREFIX + "UNKNOWN private payload"):
            with self.subTest(record=record):
                suite, _ = self.report(AUDIT + [record])
                self.write(suite)
                stdout, stderr = io.StringIO(), io.StringIO()
                with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                    self.assertEqual(1, main([str(self.path)]))
                self.assertEqual("", stdout.getvalue())
                self.assertEqual("Rider trust evidence rejected: Malformed controlled audit record\n", stderr.getvalue())


if __name__ == "__main__":
    unittest.main(verbosity=2)
