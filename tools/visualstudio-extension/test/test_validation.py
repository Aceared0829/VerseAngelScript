"""Regression tests for the fail-closed package and native-result gates."""
import contextlib
import io
import json
import pathlib
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile

from check_package import CANONICAL, ROOT, verify_vsix
from check_results import EXPECTED, PROJECT_EVIDENCE, HOST_BOOTSTRAPS, verify


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
        for name in HOST_BOOTSTRAPS:
            ET.SubElement(results, ns + "UnitTestResult", testName=name, outcome=outcome)
        ET.ElementTree(root).write(self.path)
        (self.path.parent / "native-build.json").write_text(json.dumps({
            "generator": "Visual Studio 18 2026", "configuration": "Release", "conformancePassed": True,
            "compilerSha256": "a" * 64, "argvFixtureSha256": "b" * 64,
        }))
        evidence_root = self.path.parent / "project-build"
        evidence_root.mkdir(exist_ok=True)
        for name, checks in PROJECT_EVIDENCE.items():
            (evidence_root / (name + ".json")).write_text(json.dumps({
                "testName": name, "hostMajor": 18, "processName": "devenv", "rootSuffix": "VASProjectDisposal" if name == "PackageDisposalCancelsRunningBuildAndLateResults" else "VASIntegration",
                "compilerSha256": "a" * 64, "checks": dict.fromkeys(checks, True),
            }))

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


    def test_every_project_case_is_required(self):
        for name in PROJECT_EVIDENCE:
            with self.subTest(name=name):
                self.write_results(missing=name)
                with self.assertRaisesRegex(ValueError, "Missing required"):
                    verify(self.path)

    def test_project_evidence_is_required(self):
        self.write_results()
        name = next(iter(PROJECT_EVIDENCE))
        (self.path.parent / "project-build" / (name + ".json")).unlink()
        with self.assertRaises(FileNotFoundError):
            verify(self.path)

    def test_each_evidence_check_is_mandatory(self):
        for name, checks in PROJECT_EVIDENCE.items():
            for check in checks:
                with self.subTest(name=name, check=check):
                    self.write_results()
                    evidence = self.path.parent / "project-build" / (name + ".json")
                    data = json.loads(evidence.read_text())
                    data["checks"].pop(check)
                    evidence.write_text(json.dumps(data))
                    with self.assertRaisesRegex(ValueError, "Missing native evidence"):
                        verify(self.path)

    def test_foreign_host_and_compiler_evidence_fail(self):
        name = next(iter(PROJECT_EVIDENCE))
        for key, value in (("hostMajor", 17), ("processName", "testhost"), ("rootSuffix", "Exp"), ("compilerSha256", "c" * 64)):
            with self.subTest(key=key):
                self.write_results()
                evidence = self.path.parent / "project-build" / (name + ".json")
                data = json.loads(evidence.read_text())
                data[key] = value
                evidence.write_text(json.dumps(data))
                with self.assertRaisesRegex(ValueError, "evidence"):
                    verify(self.path)

    def test_duplicate_or_substring_test_names_fail(self):
        for replace in (False, True):
            self.write_results()
            tree = ET.parse(self.path)
            results = tree.getroot()[0]
            original = results[0]
            if replace:
                original.set("testName", "Fake" + original.get("testName").split(".")[-1])
            else:
                ET.SubElement(results, original.tag, **original.attrib)
            tree.write(self.path)
            with self.assertRaisesRegex(ValueError, "required native host test"):
                verify(self.path)

    def test_plain_unit_names_and_missing_bootstrap_fail(self):
        for plain_names in (True, False):
            self.write_results()
            tree = ET.parse(self.path)
            results = tree.getroot()[0]
            if plain_names:
                for result in results:
                    result.set("testName", result.get("testName").replace(" (VS18)", ""))
            else:
                results.remove(next(result for result in results if result.get("testName") in HOST_BOOTSTRAPS))
            tree.write(self.path)
            with self.assertRaisesRegex(ValueError, "required native host"):
                verify(self.path)

    def test_native_conformance_and_digest_evidence_required(self):
        for key, value in (("conformancePassed", False), ("generator", "Visual Studio 17 2022"), ("compilerSha256", "")):
            with self.subTest(key=key):
                self.write_results()
                evidence = self.path.parent / "native-build.json"
                data = json.loads(evidence.read_text())
                data[key] = value
                evidence.write_text(json.dumps(data))
                with self.assertRaises(ValueError):
                    verify(self.path)


class VsixGateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = pathlib.Path(self.temp.name) / "test.vsix"

    def write_package(self, altered=False, assembly=False, missing=False):
        with zipfile.ZipFile(self.path, "w") as archive:
            manifest = (ROOT / "source.extension.vsixmanifest").read_text().replace("|%CurrentProject%;PkgdefProjectOutputGroup|", "VerseAngelScript.pkgdef")
            archive.writestr("extension.vsixmanifest", manifest)
            archive.writestr("VerseAngelScript.dll", b"MZsynthetic managed payload")
            archive.writestr("Newtonsoft.Json.dll", b"MZsynthetic JSON dependency")
            archive.writestr("VerseAngelScript.pkgdef", """[$RootKey$\\Packages\\{d3a6e112-5f40-4df1-8bb7-0b79f0e74226}]
"Class"="VerseAngelScript.VisualStudio.VasPackage"
"CodeBase"="$PackageFolder$\\VerseAngelScript.dll"
[$RootKey$\\Menus]
"{d3a6e112-5f40-4df1-8bb7-0b79f0e74226}"=", 1, 1"
""")
            archive.writestr("VAS.pkgdef", (ROOT / "VAS.pkgdef").read_bytes())
            archive.writestr("language-configuration.json", (CANONICAL / "language-configuration.json").read_bytes())
            if not missing:
                grammar = (CANONICAL / "syntaxes/vas.tmLanguage.json").read_bytes()
                archive.writestr("Grammars/vas.tmLanguage.json", grammar + b" " if altered else grammar)
            if assembly:
                archive.writestr("HostTests.dll", b"must not ship")

    def test_byte_identical_package_with_exact_runtime_allowlist_passes(self):
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

    def test_host_test_assembly_cannot_ship(self):
        self.write_package(assembly=True)
        with self.assertRaises(AssertionError):
            verify_vsix(self.path)

    def test_sdk_assembly_and_unlisted_payload_cannot_ship(self):
        for name in ("Microsoft.VisualStudio.Interop.dll", "vasbuild.exe", "fixture.json", "Menus.ctmenu"):
            with self.subTest(name=name):
                self.write_package()
                with zipfile.ZipFile(self.path, "a") as archive:
                    archive.writestr(name, b"MZnot allowed")
                with self.assertRaises(AssertionError):
                    verify_vsix(self.path)

    def test_missing_production_registration_fails(self):
        self.write_package()
        replacement = self.path.with_suffix(".replacement")
        with zipfile.ZipFile(self.path) as source, zipfile.ZipFile(replacement, "w") as target:
            for name in source.namelist():
                if name != "VerseAngelScript.pkgdef":
                    target.writestr(name, source.read(name))
        replacement.replace(self.path)
        with self.assertRaises(AssertionError):
            verify_vsix(self.path)


if __name__ == "__main__":
    unittest.main(verbosity=2)
