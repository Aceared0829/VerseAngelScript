"""Regression tests for the fail-closed package and native-result gates."""
import contextlib
import io
import pathlib
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile

from check_package import CANONICAL, ROOT, verify_vsix
from check_results import EXPECTED, verify


class NativeResultGateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = pathlib.Path(self.temp.name) / "native.trx"

    def write_results(self, missing=None, outcome="Passed"):
        ns = "{http://microsoft.com/schemas/VisualStudio/TeamTest/2010}"
        root = ET.Element(ns + "TestRun")
        results = ET.SubElement(root, ns + "Results")
        for name in EXPECTED - ({missing} if missing else set()):
            ET.SubElement(results, ns + "UnitTestResult", testName="VerseAngelScript.VisualStudio.Tests.EditorTests." + name + " (VS18)", outcome=outcome)
        ET.ElementTree(root).write(self.path)

    def test_complete_native_results_pass(self):
        self.write_results()
        with contextlib.redirect_stdout(io.StringIO()):
            verify(self.path)

    def test_absent_results_fail(self):
        with self.assertRaises(FileNotFoundError):
            verify(self.path)

    def test_missing_native_case_fails(self):
        self.write_results(missing="BracePairingAndTypingAreUndoable")
        with self.assertRaisesRegex(ValueError, "Missing required"):
            verify(self.path)

    def test_skipped_or_failed_native_cases_fail(self):
        for outcome in ("NotExecuted", "Failed", "Inconclusive"):
            with self.subTest(outcome=outcome):
                self.write_results(outcome=outcome)
                with self.assertRaisesRegex(ValueError, "Non-passing"):
                    verify(self.path)

    def test_empty_results_fail(self):
        self.path.write_text('<TestRun xmlns="http://microsoft.com/schemas/VisualStudio/TeamTest/2010"><Results /></TestRun>')
        with self.assertRaisesRegex(ValueError, "no native host results"):
            verify(self.path)


class VsixGateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = pathlib.Path(self.temp.name) / "test.vsix"

    def write_package(self, altered=False, assembly=False, missing=False):
        with zipfile.ZipFile(self.path, "w") as archive:
            archive.writestr("extension.vsixmanifest", (ROOT / "source.extension.vsixmanifest").read_bytes())
            archive.writestr("VAS.pkgdef", (ROOT / "VAS.pkgdef").read_bytes())
            archive.writestr("language-configuration.json", (CANONICAL / "language-configuration.json").read_bytes())
            if not missing:
                grammar = (CANONICAL / "syntaxes/vas.tmLanguage.json").read_bytes()
                archive.writestr("Grammars/vas.tmLanguage.json", grammar + b" " if altered else grammar)
            if assembly:
                archive.writestr("HostTests.dll", b"must not ship")

    def test_byte_identical_content_only_package_passes(self):
        self.write_package()
        with contextlib.redirect_stdout(io.StringIO()):
            verify_vsix(self.path)

    def test_changed_canonical_bytes_fail(self):
        self.write_package(altered=True)
        with self.assertRaisesRegex(AssertionError, "Packaged bytes differ"):
            verify_vsix(self.path)

    def test_missing_grammar_fails(self):
        self.write_package(missing=True)
        with self.assertRaisesRegex(AssertionError, "Expected exactly one"):
            verify_vsix(self.path)

    def test_test_or_runtime_assembly_cannot_ship(self):
        self.write_package(assembly=True)
        with self.assertRaises(AssertionError):
            verify_vsix(self.path)


if __name__ == "__main__":
    unittest.main(verbosity=2)
