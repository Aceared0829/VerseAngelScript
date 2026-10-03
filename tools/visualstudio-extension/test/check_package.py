"""Exact production VSIX boundary; real VS18 behavior is a separate mandatory gate."""
import argparse
import json
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
        self.assertEqual(len(assets), 2)
        self.assertTrue(all(a.attrib["Type"] == "Microsoft.VisualStudio.VsPackage" for a in assets))
        self.assertEqual(assets[0].attrib["Path"], "VAS.pkgdef")
        self.assertEqual(assets[1].attrib["Path"], "|%CurrentProject%;PkgdefProjectOutputGroup|")
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


def verify_vsix(path):
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
        registration = archive.read("VerseAngelScript.pkgdef").decode("utf-8-sig").lower()
        for required in (PACKAGE_GUID, "verseangelscript.visualstudio.vaspackage", "verseangelscript.dll", "menus"):
            assert required in registration, f"Missing production registration: {required}"
        assert "ilanguageclient" not in registration and "autoloadpackages" not in registration
        manifest = ET.fromstring(archive.read("extension.vsixmanifest"))
        assets = manifest.findall("v:Assets/v:Asset", NS)
        assert [(a.attrib["Type"], a.attrib["Path"]) for a in assets] == [
            ("Microsoft.VisualStudio.VsPackage", "VAS.pkgdef"),
            ("Microsoft.VisualStudio.VsPackage", "VerseAngelScript.pkgdef")]
        # Only container bookkeeping may accompany the intentional payload.
        allowed = set(expected) | BINARY_ALLOWLIST | {"VerseAngelScript.pkgdef", "extension.vsixmanifest", "[Content_Types].xml", "manifest.json", "catalog.json"}
        assert set(names) <= allowed, f"Unexpected VSIX payload: {set(names) - allowed}"
    print(f"Production VSIX exact assembly boundary and canonical byte parity passed: {path}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--vsix", type=pathlib.Path)
    args = parser.parse_args()
    result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(PackageContract))
    if not result.wasSuccessful():
        raise SystemExit(1)
    if args.vsix:
        verify_vsix(args.vsix)
