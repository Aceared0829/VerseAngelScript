"""Exact production VSIX boundary; real VS18 behavior is a separate mandatory gate."""
import argparse
import codecs
import json
import hashlib
import pathlib
import unittest
import zipfile
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
CANONICAL = ROOT.parent / "vscode-extension"
NS = {"v": "http://schemas.microsoft.com/developer/vsx-schema/2011"}
BINARY_ALLOWLIST = {"VerseAngelScript.dll", "Newtonsoft.Json.dll"}
PACKAGE_GUID = "d3a6e112-5f40-4df1-8bb7-0b79f0e74226"


class PackageContract(unittest.TestCase):
    def test_scoped_registration(self):
        registration = (ROOT / "VAS.pkgdef").read_text(encoding="utf-8")
        self.assertIn('[$RootKey$\\TextMate\\Repositories]', registration)
        self.assertIn('"source.vas"="$PackageFolder$\\language-configuration.json"', registration)
        self.assertNotIn("ContentTypeMapping", registration)
        self.assertNotIn("Cpp", registration)
        grammar = json.loads((CANONICAL / "syntaxes/vas.tmLanguage.json").read_text(encoding="utf-8"))
        self.assertEqual(grammar["fileTypes"], ["vas"])
        self.assertEqual(grammar["scopeName"], "source.vas")

    def test_production_package_manifest(self):
        manifest = ET.parse(ROOT / "source.extension.vsixmanifest").getroot()
        assets = manifest.findall("v:Assets/v:Asset", NS)
        self.assertEqual(len(assets), 3)
        mef = [a for a in assets if a.attrib["Type"] == "Microsoft.VisualStudio.MefComponent"]
        packages = [a for a in assets if a.attrib["Type"] == "Microsoft.VisualStudio.VsPackage"]
        self.assertEqual(len(mef), 1)
        self.assertEqual(mef[0].attrib["Path"], "|%CurrentProject%|")
        self.assertEqual([a.attrib["Path"] for a in packages], ["VAS.pkgdef", "|%CurrentProject%;PkgdefProjectOutputGroup|"])
        target = manifest.find("v:Installation/v:InstallationTarget", NS)
        self.assertEqual(target.attrib["Version"], "[18.0,19.0)")
        self.assertEqual(target.find("v:ProductArchitecture", NS).text, "amd64")

    def test_linked_assets_and_explicit_dependency(self):
        project = ET.parse(ROOT / "VerseAngelScript.VisualStudio.csproj").getroot()
        content = {item.attrib["Include"]: item for item in project.findall("ItemGroup/Content")}
        self.assertIn(r"..\vscode-extension\syntaxes\vas.tmLanguage.json", content)
        self.assertIn(r"..\vscode-extension\language-configuration.json", content)
        self.assertEqual(project.findtext("PropertyGroup/IncludeAssemblyInVSIXContainer"), "true")
        self.assertEqual(project.findtext("PropertyGroup/GeneratePkgDefFile"), "true")
        self.assertEqual(project.findtext("PropertyGroup/IncludeCopyLocalReferencesInVSIXContainer"), "false")
        packages = {p.get("Include"): p for p in project.findall("ItemGroup/PackageReference")}
        self.assertEqual(packages["Newtonsoft.Json"].get("Version"), "13.0.3")
        for name in ("Microsoft.VisualStudio.SDK", "Microsoft.VisualStudio.Interop", "Microsoft.VisualStudio.Workspace", "Microsoft.VisualStudio.Workspace.VSIntegration"):
            self.assertEqual(packages[name].get("ExcludeAssets"), "runtime")


def decode_generated_registration(data):
    # Pinned CreatePkgDef uses Encoding.Unicode: UTF-16LE with its FF FE BOM.
    # This is separate from the byte-identical UTF-8 VAS.pkgdef source asset.
    if not data.startswith(codecs.BOM_UTF16_LE) or data.startswith(codecs.BOM_UTF32_LE):
        raise ValueError("Generated package registration must have a UTF-16LE BOM")
    registration = data[len(codecs.BOM_UTF16_LE):].decode("utf-16-le", errors="strict")
    if "\0" in registration or "\ufeff" in registration:
        raise ValueError("Generated package registration contains an embedded NUL or BOM")
    return registration


def verify_vsix(path, evidence_path=None, pkgdef_evidence_path=None):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        assert len(names) == len(set(names)), "Duplicate VSIX entries"
        expected = {
            "Grammars/vas.tmLanguage.json": CANONICAL / "syntaxes/vas.tmLanguage.json",
            "language-configuration.json": CANONICAL / "language-configuration.json",
            "VAS.pkgdef": ROOT / "VAS.pkgdef",
        }
        for name, source in expected.items():
            assert names.count(name) == 1, f"Expected exactly one {name}; got {names}"
            assert archive.read(name) == source.read_bytes(), f"Packaged bytes differ: {name}"
        binaries = {n for n in names if n.lower().endswith((".dll", ".exe", ".pdb"))}
        assert binaries == BINARY_ALLOWLIST, f"Unexpected production binaries: {binaries}"
        for name in BINARY_ALLOWLIST:
            assert archive.read(name).startswith(b"MZ"), f"Not a managed PE assembly: {name}"
        assert not any("test" in n.lower() for n in names), names
        assert {n for n in names if n.lower().endswith(".pkgdef")} == {"VAS.pkgdef", "VerseAngelScript.pkgdef"}, names
        registration_bytes = archive.read("VerseAngelScript.pkgdef")
        if pkgdef_evidence_path is not None:
            pathlib.Path(pkgdef_evidence_path).write_bytes(registration_bytes)
        registration = decode_generated_registration(registration_bytes).lower()
        for required in (PACKAGE_GUID, "verseangelscript.visualstudio.vaspackage", "verseangelscript.dll", "menus"):
            assert required in registration, f"Missing production registration: {required}"
        assert "ilanguageclient" not in registration and "autoloadpackages" not in registration
        manifest = ET.fromstring(archive.read("extension.vsixmanifest"))
        assets = manifest.findall("v:Assets/v:Asset", NS)
        assert [(a.attrib["Type"], a.attrib["Path"]) for a in assets] == [
            ("Microsoft.VisualStudio.MefComponent", "VerseAngelScript.dll"),
            ("Microsoft.VisualStudio.VsPackage", "VAS.pkgdef"),
            ("Microsoft.VisualStudio.VsPackage", "VerseAngelScript.pkgdef")]
        # Only container bookkeeping may accompany the intentional payload.
        allowed = set(expected) | BINARY_ALLOWLIST | {"VerseAngelScript.pkgdef", "extension.vsixmanifest", "[Content_Types].xml", "manifest.json", "catalog.json"}
        assert set(names) <= allowed, f"Unexpected VSIX payload: {set(names) - allowed}"
        evidence = {
            "packageAssemblySha256": hashlib.sha256(archive.read("VerseAngelScript.dll")).hexdigest(),
            "newtonsoftAssemblySha256": hashlib.sha256(archive.read("Newtonsoft.Json.dll")).hexdigest(),
            "vsixSha256": hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest(),
        }
    if evidence_path is not None:
        pathlib.Path(evidence_path).write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    print(f"Production VSIX exact assembly boundary and canonical byte parity passed: {path}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--vsix", type=pathlib.Path)
    parser.add_argument("--write-evidence", type=pathlib.Path)
    parser.add_argument("--write-pkgdef-evidence", type=pathlib.Path)
    args = parser.parse_args()
    result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(PackageContract))
    if not result.wasSuccessful():
        raise SystemExit(1)
    if args.vsix:
        verify_vsix(args.vsix, args.write_evidence, args.write_pkgdef_evidence)
