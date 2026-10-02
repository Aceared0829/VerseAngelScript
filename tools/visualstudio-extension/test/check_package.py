"""Verify the production package contract; native editor tests are a separate gate."""
import argparse
import pathlib
import unittest
import zipfile
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
CANONICAL = ROOT.parent / "vscode-extension"
NS = {"v": "http://schemas.microsoft.com/developer/vsx-schema/2011"}


class PackageContract(unittest.TestCase):
    def test_scoped_registration(self):
        registration = (ROOT / "VAS.pkgdef").read_text(encoding="utf-8")
        self.assertIn('[\u0024RootKey$\\TextMate\\Repositories]', registration)
        self.assertIn('"source.vas"="$PackageFolder$\\language-configuration.json"', registration)
        self.assertNotIn("ContentTypeMapping", registration)
        self.assertNotIn("Cpp", registration)
        import json
        grammar = json.loads((CANONICAL / "syntaxes/vas.tmLanguage.json").read_text(encoding="utf-8"))
        self.assertEqual(grammar["fileTypes"], ["vas"])
        self.assertEqual(grammar["scopeName"], "source.vas")

    def test_content_only_manifest(self):
        manifest = ET.parse(ROOT / "source.extension.vsixmanifest").getroot()
        assets = manifest.findall("v:Assets/v:Asset", NS)
        self.assertEqual([(a.attrib["Type"], a.attrib["Path"]) for a in assets],
                         [("Microsoft.VisualStudio.VsPackage", "VAS.pkgdef")])
        target = manifest.find("v:Installation/v:InstallationTarget", NS)
        self.assertEqual(target.attrib["Version"], "[18.0,19.0)")
        self.assertEqual(target.find("v:ProductArchitecture", NS).text, "amd64")

    def test_linked_assets(self):
        project = ET.parse(ROOT / "VerseAngelScript.VisualStudio.csproj").getroot()
        content = {item.attrib["Include"]: item for item in project.findall("ItemGroup/Content")}
        self.assertIn(r"..\vscode-extension\syntaxes\vas.tmLanguage.json", content)
        self.assertIn(r"..\vscode-extension\language-configuration.json", content)
        self.assertEqual(project.findtext("PropertyGroup/IncludeAssemblyInVSIXContainer"), "false")
        self.assertEqual(project.findtext("PropertyGroup/GeneratePkgDefFile"), "false")


def verify_vsix(path):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        expected = {
            "Grammars/vas.tmLanguage.json": CANONICAL / "syntaxes/vas.tmLanguage.json",
            "language-configuration.json": CANONICAL / "language-configuration.json",
            "VAS.pkgdef": ROOT / "VAS.pkgdef",
        }
        for name, source in expected.items():
            assert names.count(name) == 1, f"Expected exactly one {name}; got {names}"
            assert archive.read(name) == source.read_bytes(), f"Packaged bytes differ: {name}"
        assert not any(n.lower().endswith((".dll", ".exe", ".pdb")) for n in names), names
        assert not any("test" in n.lower() for n in names), names
        manifest = ET.fromstring(archive.read("extension.vsixmanifest"))
        assets = manifest.findall("v:Assets/v:Asset", NS)
        assert [(a.attrib["Type"], a.attrib["Path"]) for a in assets] == [
            ("Microsoft.VisualStudio.VsPackage", "VAS.pkgdef")]
    print(f"Production VSIX byte parity and content-only contract passed: {path}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--vsix", type=pathlib.Path)
    args = parser.parse_args()
    result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(PackageContract))
    if not result.wasSuccessful():
        raise SystemExit(1)
    if args.vsix:
        verify_vsix(args.vsix)
