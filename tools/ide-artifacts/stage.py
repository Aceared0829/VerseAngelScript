"""Stage one existing, validated IDE package; never build or publish anything."""
import argparse
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
NS = {"v": "http://schemas.microsoft.com/developer/vsx-schema/2011"}
VS_CODE_MODES = ["current", "project", "alias-dirty", "alias-saved", "user-rerun-alias",
                 "user-rerun", "single-root", "legacy", "untrusted"]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def git(*args):
    return subprocess.check_output(["git", "-C", str(ROOT), *args], text=True).strip()


def tracked_under(directory):
    relative = directory.relative_to(ROOT).as_posix()
    paths = [ROOT / name for name in git("ls-files", "-z", "--", relative).split("\0") if name]
    require(paths, f"Missing tracked production inputs: {relative}")
    require(all(p.is_file() and not p.is_symlink() for p in paths), "Non-regular production input")
    return paths


def one_package(directory, pattern):
    candidates = list(directory.glob(pattern))
    require(len(candidates) == 1, f"Expected one package in {directory.name}, found {len(candidates)}")
    package = candidates[0]
    require(package.is_file() and not package.is_symlink(), "Package must be a regular file")
    require(package.resolve().is_relative_to(directory.resolve()), "Package escapes its build directory")
    require(re.fullmatch(r"[A-Za-z0-9_.-]+", package.name), "Unsafe package filename")
    return package


def checked_zip(data):
    archive = zipfile.ZipFile(io.BytesIO(data))
    seen = set()
    require(len(archive.infolist()) <= 10000, "Too many ZIP entries")
    require(sum(i.file_size for i in archive.infolist()) <= 256 * 1024 * 1024, "ZIP is too large")
    for entry in archive.infolist():
        name = entry.filename
        parts = name.rstrip("/").split("/")
        require(name and not name.startswith("/") and "\\" not in name and ":" not in name
                and all(p not in ("", ".", "..") for p in parts)
                and not any(ord(c) < 32 for c in name), "Unsafe ZIP entry")
        require(name.casefold() not in seen, "Duplicate ZIP entry")
        seen.add(name.casefold())
        kind = stat.S_IFMT(entry.external_attr >> 16)
        require(kind in (0, stat.S_IFREG, stat.S_IFDIR), "Non-regular ZIP entry")
        require(not entry.flag_bits & 1, "Encrypted ZIP entry")
    require(archive.testzip() is None, "Corrupt ZIP entry")
    return archive


def files(archive):
    return {i.filename for i in archive.infolist() if not i.is_dir()}


def parity(archive, expected):
    for name, source in expected.items():
        require(name in files(archive), f"Missing production member: {name}")
        require(archive.read(name) == source.read_bytes(), f"Source/package mismatch: {name}")


def vscode_package(data):
    root = ROOT / "tools/vscode-extension"
    expected = {f"extension/{name}": root / name for name in (
        "LICENSE.md", "package.json", "language-configuration.json",
        "syntaxes/vas.tmLanguage.json", "snippets/vas.code-snippets")}
    expected["extension/readme.md"] = root / "README.md"
    expected.update({f"extension/{p.relative_to(root).as_posix()}": p for p in tracked_under(root / "src")})
    with checked_zip(data) as archive:
        require(files(archive) == set(expected) | {"extension.vsixmanifest", "[Content_Types].xml"},
                "Unexpected or missing VS Code package payload")
        parity(archive, expected)
        package = json.loads(archive.read("extension/package.json"))
        require(package["name"] == "verseangelscript-vscode" and package["publisher"] == "VerseAngelScript",
                "Wrong VS Code package identity")
        manifest = ET.fromstring(archive.read("extension.vsixmanifest"))
        identity = manifest.find("v:Metadata/v:Identity", NS)
        require(identity is not None and identity.get("Version") == package["version"], "VSIX version mismatch")
    return package["version"], {"vscodeEngine": package["engines"]["vscode"]}


def rider_package(data):
    root = ROOT / "tools/rider-plugin"
    build = (root / "build.gradle.kts").read_text(encoding="utf-8")
    version = re.search(r'^version = "([^"]+)"$', build, re.M)[1]
    prefix = "verse-angelscript-rider/lib/"
    primary = f"{prefix}verse-angelscript-rider-{version}.jar"
    # Gson 2.13.2's published POM declares error_prone_annotations 2.41.0.
    # These are the only production dependencies; Rider SDK/test jars must not ship.
    dependencies = {prefix + "gson-2.13.2.jar": "com/google/gson/",
                    prefix + "error_prone_annotations-2.41.0.jar": "com/google/errorprone/annotations/"}
    require('implementation("com.google.code.gson:gson:2.13.2")' in build, "Review changed Rider dependencies")
    with checked_zip(data) as archive:
        require(files(archive) == {primary} | set(dependencies), "Unexpected or missing Rider distribution payload")
        for name, namespace in dependencies.items():
            with checked_zip(archive.read(name)) as dependency:
                for member in files(dependency):
                    require((member.startswith(namespace) and member.endswith(".class"))
                            or member in {"META-INF/MANIFEST.MF", "META-INF/LICENSE", "META-INF/NOTICE",
                                          "META-INF/versions/9/module-info.class"}
                            or re.fullmatch(r"META-INF/maven/(com.google.code.gson/gson|com.google.errorprone/error_prone_annotations)/pom\.(xml|properties)", member),
                            f"Unexpected Rider dependency member: {member}")
        with checked_zip(archive.read(primary)) as plugin:
            resources = {p.relative_to(root / "src/main/resources").as_posix(): p
                         for p in tracked_under(root / "src/main/resources")}
            resources.pop("META-INF/plugin.xml")  # Patched by buildPlugin.
            parity(plugin, resources)
            classes = {p.relative_to(root / "src/main/java").with_suffix("").as_posix()
                       for p in tracked_under(root / "src/main/java") if p.suffix == ".java"}
            for name in files(plugin) - set(resources) - {"META-INF/plugin.xml", "META-INF/MANIFEST.MF"}:
                require(name.endswith(".class") and name[:-6].split("$")[0] in classes,
                        f"Unexpected Rider plugin member: {name}")
            require(all(name + ".class" in files(plugin) for name in classes), "Missing production Rider classes")
            descriptor = ET.fromstring(plugin.read("META-INF/plugin.xml"))
            require(descriptor.findtext("id") == "com.verseangelscript.language", "Wrong Rider plugin identity")
            require(descriptor.findtext("version") == version, "Rider plugin version mismatch")
            compatibility = descriptor.find("idea-version")
            require(compatibility is not None, "Missing Rider installation range")
            since, until = compatibility.get("since-build"), compatibility.get("until-build")
            require(since == "262" and until == "262.*", "Review changed Rider installation range")
    return version, {"sinceBuild": since, "untilBuild": until,
                     "bundledTools": "Repository prebuilt Windows x64 tools; not rebuilt by this job"}


def load_checker(name):
    path = ROOT / "tools/visualstudio-extension/test" / f"{name}.py"
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def vs2026_package(package, data):
    with checked_zip(data) as archive:
        manifest = ET.fromstring(archive.read("extension.vsixmanifest"))
        identity = manifest.find("v:Metadata/v:Identity", NS)
        require(identity is not None and identity.get("Id") == "VerseAngelScript.VisualStudio", "Wrong VS2026 identity")
        targets = manifest.findall("v:Installation/v:InstallationTarget", NS)
        require(len(targets) == 1, "Ambiguous VS2026 installation range")
        target = targets[0]
        compatibility = {"targetId": target.get("Id"), "versionRange": target.get("Version"),
                         "architecture": target.findtext("v:ProductArchitecture", namespaces=NS)}
        require(compatibility == {"targetId": "Microsoft.VisualStudio.Community", "versionRange": "[18.0,19.0)",
                                  "architecture": "amd64"}, "Review changed VS2026 compatibility")
    results = ROOT / "tools/visualstudio-extension/TestResults"
    load_checker("check_package").verify_vsix(package)
    load_checker("check_results").verify(results / "VS2026.trx")
    require(read_json(results / "production-package.json")["vsixSha256"] == sha256(data),
            "VSIX differs from native tested production package")
    return identity.get("Version"), compatibility


def source_identity(environ):
    require(not git("status", "--porcelain", "--untracked-files=no"), "Tracked checkout changed after build")
    commit, tree = git("rev-parse", "HEAD"), git("rev-parse", "HEAD^{tree}")
    require(re.fullmatch(r"[a-f0-9]{40}", commit) and re.fullmatch(r"[a-f0-9]{40}", tree), "Invalid checkout identity")
    source = {"commit": commit, "tree": tree, "checkoutRef": environ.get("GITHUB_REF", ""),
              "event": environ.get("GITHUB_EVENT_NAME", ""), "pullRequestHeadCommit": None}
    if source["event"] == "pull_request":
        head = read_json(Path(environ["GITHUB_EVENT_PATH"]))["pull_request"]["head"]["sha"]
        require(re.fullmatch(r"[a-f0-9]{40}", head), "Invalid PR head identity")
        source["pullRequestHeadCommit"] = head
    return source


def runtime_identity(ide, requested, environ):
    if ide == "rider":
        build = (ROOT / "tools/rider-plugin/build.gradle.kts").read_text(encoding="utf-8")
        require(re.findall(r'rider\("([^"]+)"\)', build) == ["2026.2.0.2"], "Review changed Rider runtime")
        require(not environ.get("RIDER_HOME") and not environ.get("ORG_GRADLE_PROJECT_riderPath"), "Rider runtime override")
        return "2026.2.0.2", "Pinned Gradle rider() dependency used by this job"
    if ide == "vs2026":
        version = read_json(ROOT / "tools/visualstudio-extension/TestResults/host-instance.json")["installationVersion"]
        require(re.fullmatch(r"18\.\d+\.\d+\.\d+", version), "Invalid VS2026 host version")
        return version, "Native host-instance.json and loaded-package evidence gate"
    evidence = read_json(Path(environ["VAS_TEST_RUNTIME_EVIDENCE"]))
    require(evidence.get("passedModes") == VS_CODE_MODES and evidence.get("requestedVersion") == requested,
            "Missing or mismatched VS Code native runtime evidence")
    platform = {"Linux": "linux", "Windows": "win32", "macOS": "darwin"}[environ["RUNNER_OS"]]
    arch = {"X64": "x64", "ARM64": "arm64"}[environ["RUNNER_ARCH"]]
    require(evidence.get("platform") == platform and evidence.get("arch") == arch, "VS Code runtime platform mismatch")
    version = evidence.get("version", "")
    require(re.fullmatch(r"\d+\.\d+\.\d+", version), "Invalid VS Code runtime version")
    require(requested == "stable" or requested == version, "VS Code requested/runtime mismatch")
    return version, "vscode.version from every successful native Extension Host mode"


def artifact_name(ide, source, runner, version, compatibility, requested, attempt):
    if ide == "rider":
        compat = "build-262-to-262.x"
    elif ide == "vs2026":
        compat = "ge18.0-lt19.0-amd64"
    else:
        require(compatibility["vscodeEngine"] == "^1.96.0", "Review changed VS Code installation range")
        compat = "caret1.96.0"
    parts = ["vas", ide, "src", source["commit"], runner["os"], runner["arch"], "tested", version]
    if ide == "vscode":
        parts.extend(["requested", requested])
    parts.extend(["compat", compat, "attempt", attempt])
    name = "-".join(parts)
    require(re.fullmatch(r"[A-Za-z0-9_.-]+", name), "Unsafe artifact name")
    return name


def stage(ide, output, requested, environ):
    source = source_identity(environ)
    runner = {"os": environ["RUNNER_OS"], "arch": environ["RUNNER_ARCH"]}
    require(runner["os"] in {"Windows", "Linux", "macOS"} and runner["arch"] in {"X64", "ARM64"}, "Unknown runner")
    if ide in {"rider", "vs2026"}:
        require(runner == {"os": "Windows", "arch": "X64"}, "Unvalidated native runner")
    directory, pattern = {
        "rider": ("tools/rider-plugin/build/distributions", "*.zip"),
        "vscode": ("tools/vscode-extension", "*.vsix"),
        "vs2026": ("tools/visualstudio-extension/bin/Release", "**/*.vsix"),
    }[ide]
    package = one_package(ROOT / directory, pattern)
    data = package.read_bytes()
    if ide == "vs2026":
        package_version, compatibility = vs2026_package(package, data)
    else:
        package_version, compatibility = {"rider": rider_package, "vscode": vscode_package}[ide](data)
    tested_version, version_evidence = runtime_identity(ide, requested, environ)
    attempt = environ["GITHUB_RUN_ATTEMPT"]
    require(re.fullmatch(r"[1-9][0-9]*", attempt), "Invalid workflow attempt")
    name = artifact_name(ide, source, runner, tested_version, compatibility, requested, attempt)
    scope = {
        "rider": ["Gradle test and buildPlugin", "Required native trust and project result gates"],
        "vscode": ["Native compiler conformance", "Unit/TextMate/native project tests", "Native Extension Host modes"],
        "vs2026": ["Production package boundary and hash", "Native compiler conformance", "Core tests",
                   "Native VS18 editor/project evidence and loaded production assembly"],
    }[ide]
    manifest = {"schemaVersion": 1, "artifactName": name, "source": source, "runner": runner,
                "ide": {"name": ide, "testedVersion": tested_version, "versionEvidence": version_evidence},
                "installationCompatibility": compatibility,
                "workflow": {"runId": environ["GITHUB_RUN_ID"], "attempt": attempt, "job": environ["GITHUB_JOB"]},
                "validation": {"boundary": "This job only; require complete successful workflow before installation", "scope": scope, "platformScope": "This runner only",
                               "packageInspection": "Passed before staging", "marketplaceRelease": False},
                "package": {"file": package.name, "version": package_version, "sha256": sha256(data)}}
    if ide == "rider":
        manifest["validation"]["packageRelation"] = "Gradle tests use the build sandbox; buildPlugin output is checked separately"
    if ide == "vscode":
        manifest["ide"]["requestedVersion"] = requested
        manifest["validation"]["packageRelation"] = "Native host tests use source; packaged production files match that source byte-for-byte"
    if ide == "vs2026":
        manifest["validation"]["nativeMotwPromptCancellation"] = "unverified"
    # Refuse any pre-existing directory so stale logs/private files cannot hitchhike.
    output.mkdir(parents=True, exist_ok=False)
    try:
        (output / package.name).write_bytes(data)
        encoded = (json.dumps(manifest, indent=2) + "\n").encode("utf-8")
        (output / "manifest.json").write_bytes(encoded)
        (output / "SHA256SUMS").write_text(f"{sha256(data)}  {package.name}\n{sha256(encoded)}  manifest.json\n", encoding="utf-8")
        if environ.get("GITHUB_OUTPUT"):
            with open(environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as stream:
                stream.write(f"name={name}\npath={output.as_posix()}\n")
    except Exception:
        shutil.rmtree(output)
        raise
    print(f"Staged checked {ide} package: {package.name}; SHA256 {sha256(data)}")
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ide", choices=["rider", "vscode", "vs2026"], required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--requested-version", default="")
    args = parser.parse_args()
    stage(args.ide, args.output, args.requested_version, os.environ)
