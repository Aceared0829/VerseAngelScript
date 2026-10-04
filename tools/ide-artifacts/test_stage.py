"""Delivery boundaries and provenance, using synthetic ZIPs, never IDE builds."""
import contextlib
import io
import json
from pathlib import Path
import stat
import subprocess
import tempfile
import unittest
from unittest.mock import patch, Mock
import warnings
import zipfile

import stage


def zipped(entries):
    result = io.BytesIO()
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", UserWarning)
        with zipfile.ZipFile(result, "w") as archive:
            for name, value in entries:
                archive.writestr(name, value)
    return result.getvalue()


def vscode_entries():
    root = stage.ROOT / "tools/vscode-extension"
    entries = [(f"extension/{name}", (root / name).read_bytes()) for name in (
        "LICENSE.md", "package.json", "language-configuration.json", "syntaxes/vas.tmLanguage.json", "snippets/vas.code-snippets")]
    entries += [("extension/readme.md", (root / "README.md").read_bytes())]
    entries += [(f"extension/src/{p.name}", p.read_bytes()) for p in (root / "src").glob("*.js")]
    entries += [("[Content_Types].xml", b"<Types/>"), ("extension.vsixmanifest", b'<PackageManifest xmlns="http://schemas.microsoft.com/developer/vsx-schema/2011"><Metadata><Identity Version="0.1.0"/></Metadata></PackageManifest>')]
    return entries


def rider_entries():
    root = stage.ROOT / "tools/rider-plugin"
    entries = [(p.relative_to(root / "src/main/resources").as_posix(), p.read_bytes())
               for p in (root / "src/main/resources").rglob("*") if p.is_file() and p.name != "plugin.xml"]
    entries += [(p.relative_to(root / "src/main/java").with_suffix(".class").as_posix(), b"synthetic-class")
                for p in (root / "src/main/java").rglob("*.java")]
    entries += [("META-INF/plugin.xml", b'<idea-plugin><id>com.verseangelscript.language</id><version>0.5.6</version><idea-version since-build="262" until-build="262.*"/></idea-plugin>')]
    prefix = "verse-angelscript-rider/lib/"
    return [(prefix + "verse-angelscript-rider-0.5.6.jar", zipped(entries)),
            # The official Gson 2.13.2 JAR also ships this consumer ProGuard file.
            (prefix + "gson-2.13.2.jar", zipped([("com/google/gson/Gson.class", b"synthetic-class"),
                                                ("META-INF/proguard/gson.pro", b"-keepattributes Signature\n")])),
            (prefix + "error_prone_annotations-2.41.0.jar", zipped([("com/google/errorprone/annotations/CanIgnoreReturnValue.class", b"synthetic-class")]))]


class StageTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.env = {"RUNNER_OS": "Linux", "RUNNER_ARCH": "X64", "GITHUB_RUN_ID": "123",
                    "GITHUB_RUN_ATTEMPT": "2", "GITHUB_JOB": "extension"}

    def test_zip_rejects_bad_duplicates_traversal_and_symlinks(self):
        for name in ("../private", "/absolute", "C:/private", r"a\private", "a//b", "a/./b", "a/../b", "a\nb"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                stage.checked_zip(zipped([(name, b"bad")]))
        for entries in ([('a', b'x'), ('a', b'y')], [('a', b'x'), ('A', b'y')]):
            with self.assertRaisesRegex(ValueError, "Duplicate"):
                stage.checked_zip(zipped(entries))
        symlink = zipfile.ZipInfo("link")
        symlink.create_system = 3
        symlink.external_attr = (stat.S_IFLNK | 0o777) << 16
        with self.assertRaisesRegex(ValueError, "Non-regular"):
            stage.checked_zip(zipped([(symlink, b"../private")]))
        with self.assertRaises(zipfile.BadZipFile):
            stage.checked_zip(b"not a zip")

    def test_package_selection_fails_closed(self):
        with self.assertRaisesRegex(ValueError, "found 0"):
            stage.one_package(self.root, "*.vsix")
        (self.root / "one.vsix").write_bytes(b"one")
        self.assertEqual(stage.one_package(self.root, "*.vsix").name, "one.vsix")
        (self.root / "two.vsix").write_bytes(b"two")
        with self.assertRaisesRegex(ValueError, "found 2"):
            stage.one_package(self.root, "*.vsix")

    def test_untracked_production_tree_files_are_never_allowlisted(self):
        subprocess.run(["git", "init", "--quiet", str(self.root)], check=True)
        source = self.root / "src"
        source.mkdir()
        (source / "production.js").write_text("production")
        subprocess.run(["git", "-C", str(self.root), "add", "src/production.js"], check=True)
        (source / "private.js").write_text("private")
        with patch.object(stage, "ROOT", self.root):
            self.assertEqual(stage.tracked_under(source), [source / "production.js"])

    def test_vscode_only_current_production_bytes(self):
        entries = vscode_entries()
        self.assertEqual(stage.vscode_package(zipped(entries)), ("0.1.0", {"vscodeEngine": "^1.96.0"}))
        for member in ("extension/test/private.js", "extension/.env", "extension/node_modules/sdk/index.js", "private.key"):
            with self.subTest(member=member), self.assertRaisesRegex(ValueError, "payload"):
                stage.vscode_package(zipped(entries + [(member, b"private")]))
        with self.assertRaisesRegex(ValueError, "payload"):
            stage.vscode_package(zipped(entries[1:]))
        bad = [(name, b"stale" if name == "extension/src/extension.js" else data) for name, data in entries]
        with self.assertRaisesRegex(ValueError, "mismatch"):
            stage.vscode_package(zipped(bad))

    def test_rider_rejects_sdk_and_nested_test_or_private_payload(self):
        entries = rider_entries()
        self.assertEqual(stage.rider_package(zipped(entries))[1]["untilBuild"], "262.*")
        with self.assertRaisesRegex(ValueError, "distribution payload"):
            stage.rider_package(zipped(entries + [("verse-angelscript-rider/lib/rider-test-framework.jar", b"sdk")]))
        for member in ("testData/private.txt", "com/verseangelscript/rider/AccidentalTest.class", ".env"):
            with zipfile.ZipFile(io.BytesIO(entries[0][1])) as archive:
                members = [(n, archive.read(n)) for n in archive.namelist()]
            changed = [(entries[0][0], zipped(members + [(member, b"private")]))] + entries[1:]
            with self.subTest(member=member), self.assertRaisesRegex(ValueError, "Unexpected Rider plugin member"):
                stage.rider_package(zipped(changed))
        with self.assertRaisesRegex(ValueError, "distribution payload"):
            stage.rider_package(zipped(entries[:-1]))

    def test_gson_proguard_entry_is_allowed_only_in_gson(self):
        entries = rider_entries()
        self.assertEqual(stage.rider_package(zipped(entries))[0], "0.5.6")
        # Do not broaden this exception to unrelated ProGuard data or another JAR.
        for index, member in ((1, "META-INF/proguard/private.pro"),
                              (1, "META-INF/private.txt"),
                              (2, "META-INF/proguard/gson.pro")):
            with zipfile.ZipFile(io.BytesIO(entries[index][1])) as dependency:
                members = [(n, dependency.read(n)) for n in dependency.namelist()]
            changed = list(entries)
            changed[index] = (entries[index][0], zipped(members + [(member, b"unrelated")]))
            with self.subTest(index=index, member=member), self.assertRaisesRegex(ValueError, "Unexpected Rider dependency member"):
                stage.rider_package(zipped(changed))

    def test_checkout_commit_and_tree_are_not_pr_head(self):
        event = self.root / "event.json"
        event.write_text(json.dumps({"pull_request": {"head": {"sha": "b" * 40}, "title": "private text"}}))
        env = {"GITHUB_REF": "refs/pull/42/merge", "GITHUB_EVENT_NAME": "pull_request", "GITHUB_EVENT_PATH": str(event)}
        with patch.object(stage, "git", side_effect=["", "a" * 40, "c" * 40]):
            source = stage.source_identity(env)
        self.assertEqual(source["commit"], "a" * 40)
        self.assertEqual(source["tree"], "c" * 40)
        self.assertEqual(source["pullRequestHeadCommit"], "b" * 40)
        self.assertNotIn("private", json.dumps(source))
        with patch.object(stage, "git", return_value=" M tracked.file"), self.assertRaisesRegex(ValueError, "changed"):
            stage.source_identity(env)

    def runtime(self, **changes):
        data = {"version": "1.110.2", "requestedVersion": "stable", "passedModes": stage.VS_CODE_MODES,
                "platform": "linux", "arch": "x64", **changes}
        evidence = self.root / "runtime.json"
        evidence.write_text(json.dumps(data))
        self.env["VAS_TEST_RUNTIME_EVIDENCE"] = str(evidence)

    def test_runtime_requires_all_native_modes_platform_and_resolved_version(self):
        self.runtime()
        self.assertEqual(stage.runtime_identity("vscode", "stable", self.env)[0], "1.110.2")
        for change in ({"version": "stable"}, {"platform": "win32"}, {"arch": "arm64"},
                       {"passedModes": stage.VS_CODE_MODES[:-1]}, {"requestedVersion": "1.96.4"}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.runtime(**change)
                stage.runtime_identity("vscode", "stable", self.env)

    def test_stage_contains_only_verified_bytes_and_hash_bound_manifest(self):
        self.runtime()
        package = self.root / "original.vsix"
        data = zipped(vscode_entries())
        package.write_bytes(data)
        source = {"commit": "a" * 40, "tree": "c" * 40, "pullRequestHeadCommit": "b" * 40}
        output = self.root / "delivery"
        with patch.object(stage, "source_identity", return_value=source), patch.object(stage, "one_package", return_value=package), contextlib.redirect_stdout(io.StringIO()):
            manifest = stage.stage("vscode", output, "stable", self.env)
            with self.assertRaises(FileExistsError):
                stage.stage("vscode", output, "stable", self.env)
        self.assertEqual({p.name for p in output.iterdir()}, {"original.vsix", "manifest.json", "SHA256SUMS"})
        self.assertEqual((output / "original.vsix").read_bytes(), data)
        self.assertEqual(manifest["package"]["sha256"], stage.sha256(data))
        self.assertIn("Linux-X64-tested-1.110.2-requested-stable-compat-caret1.96.0-attempt-2", manifest["artifactName"])
        for line in (output / "SHA256SUMS").read_text().splitlines():
            digest, name = line.split("  ")
            self.assertEqual(digest, stage.sha256((output / name).read_bytes()))
        other_name = stage.artifact_name("vscode", source, {"os": "Windows", "arch": "X64"}, "1.110.2", {"vscodeEngine": "^1.96.0"}, "stable", "2")
        self.assertNotEqual(manifest["artifactName"], other_name)
        self.assertIn("This job only", manifest["validation"]["boundary"])

    def test_vs2026_upload_hash_must_match_native_evidence(self):
        xml = b'<PackageManifest xmlns="http://schemas.microsoft.com/developer/vsx-schema/2011"><Metadata><Identity Id="VerseAngelScript.VisualStudio" Version="0.1.0"/></Metadata><Installation><InstallationTarget Id="Microsoft.VisualStudio.Community" Version="[18.0,19.0)"><ProductArchitecture>amd64</ProductArchitecture></InstallationTarget></Installation></PackageManifest>'
        data = zipped([("extension.vsixmanifest", xml)])
        checker = Mock()
        with patch.object(stage, "load_checker", return_value=checker), patch.object(stage, "read_json", return_value={"vsixSha256": "0" * 64}), self.assertRaisesRegex(ValueError, "differs"):
            stage.vs2026_package(self.root / "fake.vsix", data)
        self.assertEqual(checker.verify_vsix.call_count, 1)
        self.assertEqual(checker.verify.call_count, 1)
        with patch.object(stage, "load_checker", return_value=checker), patch.object(stage, "read_json", return_value={"vsixSha256": stage.sha256(data)}):
            self.assertEqual(stage.vs2026_package(self.root / "fake.vsix", data)[1]["architecture"], "amd64")


if __name__ == "__main__":
    unittest.main()
