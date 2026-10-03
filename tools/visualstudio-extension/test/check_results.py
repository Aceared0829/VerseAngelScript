"""Fail closed on missing, skipped, duplicate or unevidenced native VS18 tests."""
import json
import ntpath
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

EDITOR_EXPECTED = {
    "RunsInsideVisualStudio2026ExperimentalHost",
    "ClassifiesUnicodeSpacePathAndReopens",
    "TripleStringsAndNonNestingCommentsUseCanonicalGrammar",
    "CommentAndUncommentAreRealUndoableCommands",
    "BracePairingAndTypingAreUndoable",
    "NewlineIndentsAndUndoRestoresText",
    "CppAndAsAreNotClaimedByVasGrammar",
}
PROJECT_EVIDENCE = {
    "SolutionCommandBuildsExplicitUnitWithNativeCompiler": {"solutionOpened", "registeredCommand", "explicitUnit", "nativeOutput", "sourceDigest"},
    "OpenFolderCommandBuildsExplicitUnitWithNativeCompiler": {"folderOpened", "registeredCommand", "explicitUnit", "nativeOutput"},
    "ReadUnitsRequiresExplicitSelectionAndCancelIsPassive": {"noDefaultUnit", "buildInitiallyDisabled", "zeroPassiveProcesses", "zeroCancelProcesses", "zeroOutputDirectories"},
    "LegacyManifestNeverSelectsEmbeddedTool": {"legacyWarning", "explicitUnit", "embeddedToolNotRun", "nativeOutput"},
    "InvalidDescriptorNeverEnablesBuild": {"failedDescriptor", "buildDisabled", "noOutput"},
    "CompilerArgumentsPreserveUnicodeAndMetacharacters": {"nativeWideArgv", "literalArguments", "stderrSeparate", "exitCodePreserved"},
    "NativeDiagnosticsNavigateUtf8ByteColumnsWithSourceDigest": {"nativeCompileFailure", "sourceDigest", "errorListTask", "navigateToCalled", "astralUtf16Position"},
    "ChangedOrDigestlessDiagnosticsDoNotNavigate": {"changedSourceRejected", "digestlessRejected", "actualTaskNavigateTo"},
    "DirtyInputsAndAliasesBlockNativeBuild": {"dirtyEntryBlocked", "dirtyConfigBlocked", "dirtyManifestBlocked", "dirtyHardLinkBlocked", "dirtyJunctionBlocked", "noBuildProcesses"},
    "RepeatedBuildCancelAndCloseIgnoreLateResults": {"repeatDisabled", "cancelCommand", "closeInvalidated", "lateResultIgnored", "processReleased"},
    "NativeIncludeObservationsRetainPartialDependencies": {"nativeIncludeDiscovery", "includeDigests", "partialDiscovery", "previousDependencyRetained", "retainedDependencyInvalidates"},
    "CanonicalMissingIncludesAndAncestorChangesInvalidate": {"nativeMissingInclude", "canonicalAliasCreate", "ancestorRename", "ancestorDelete"},
    "FirstTraversalDirtyIncludeAliasBlocksPublication": {"firstTraversalObserved", "dirtyTxtHardLinkRejected", "noStalePublication", "unrelatedDirtyAllowed"},
    "MismatchedProofAndTruncatedReportsNeverPublish": {"mismatchedDigestRejected", "truncatedTerminalRejected", "noDiagnosticPublication", "actualProcessCalls"},
    "BlockedBuildInputAndCompilerChangesInvalidate": {"sourceMutationCancels", "configMutationCancels", "manifestMutationCancels", "compilerSettingCancels", "lateOutputIgnored", "processesReaped", "concurrentWatcherCancelSafe"},
    "DescriptorCancellationReapsProcessBeforeRestart": {"descriptorBlocked", "cancelCommand", "cleanupRetainsOwnership", "repeatIgnoredDuringCleanup", "processReaped", "restartAfterCleanup"},
    "DirtyAliasChangedAfterReadBlocksImmediateBuild": {"descriptorRead", "rdtAliasChanged", "immediateBuildBlocked", "zeroBuildProcesses"},
    "PackageDisposalCancelsRunningBuildAndLateResults": {"nativePackageClose", "processCancelled", "lateResultIgnored", "isolatedHostSuffix"},
}
EXPECTED = EDITOR_EXPECTED | PROJECT_EVIDENCE.keys()
HOST_BOOTSTRAPS = {
    "Xunit.Instances.VisualStudio (VS18, VASIntegration)",
    "Xunit.Instances.VisualStudio (VS18, VASProjectDisposal)",
}


def _json(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def verify(path):
    path = pathlib.Path(path)
    root = ET.parse(path).getroot()
    ns = {"t": "http://microsoft.com/schemas/VisualStudio/TeamTest/2010"}
    results = root.findall("t:Results/t:UnitTestResult", ns)
    if not results:
        raise ValueError("TRX contains no native host results")
    failed = [r.attrib for r in results if r.get("outcome") != "Passed"]
    if failed:
        raise ValueError(f"Non-passing tests: {failed}")
    for name in sorted(EXPECTED):
        # A substring such as FakeRunsInside... cannot satisfy a required case.
        pattern = re.compile(r"(?:^|\.)" + re.escape(name) + r" \(VS18\)$")
        matches = [r for r in results if pattern.search(r.get("testName", ""))]
        if not matches:
            raise ValueError(f"Missing required native host test: {name}")
        if len(matches) != 1:
            raise ValueError(f"Duplicate required native host test: {name}")
    for bootstrap in sorted(HOST_BOOTSTRAPS):
        if sum(result.get("testName") == bootstrap for result in results) != 1:
            raise ValueError(f"Missing or duplicate required native host bootstrap: {bootstrap}")
    native = _json(path.parent / "native-build.json")
    if native.get("generator") != "Visual Studio 18 2026" or native.get("configuration") != "Release" or native.get("conformancePassed") is not True:
        raise ValueError("Native compiler build/conformance evidence is missing or invalid")
    for key in ("compilerSha256", "argvFixtureSha256"):
        if not re.fullmatch(r"[a-f0-9]{64}", str(native.get(key, ""))):
            raise ValueError(f"Missing native binary digest: {key}")
    production = _json(path.parent / "production-package.json")
    for key in ("packageAssemblySha256", "vsixSha256"):
        if not re.fullmatch(r"[a-f0-9]{64}", str(production.get(key, ""))):
            raise ValueError(f"Missing production package digest: {key}")
    host = _json(path.parent / "host-instance.json")
    installation = host.get("installationPath", "")
    if not isinstance(installation, str) or not ntpath.isabs(installation) or str(host.get("installationVersion", "")).split(".")[0] != "18":
        raise ValueError("Invalid selected native host installation evidence")
    expected_host = ntpath.normcase(ntpath.normpath(ntpath.join(installation, "Common7", "IDE", "devenv.exe")))
    for name, requirements in sorted(PROJECT_EVIDENCE.items()):
        evidence = _json(path.parent / "project-build" / (name + ".json"))
        expected_suffix = "VASProjectDisposal" if name == "PackageDisposalCancelsRunningBuildAndLateResults" else "VASIntegration"
        if (evidence.get("testName") != name or evidence.get("hostMajor") != 18
                or evidence.get("processName") != "devenv" or evidence.get("rootSuffix") != expected_suffix):
            raise ValueError(f"Invalid native VS18 evidence identity: {name}")
        host_exe = evidence.get("hostExe", "")
        if not isinstance(host_exe, str) or not ntpath.isabs(host_exe) or ntpath.normcase(ntpath.normpath(host_exe)) != expected_host:
            raise ValueError(f"Selected native host executable evidence mismatch: {name}")
        if evidence.get("packageAssemblySha256") != production["packageAssemblySha256"]:
            raise ValueError(f"Installed production package assembly evidence mismatch: {name}")
        if evidence.get("compilerSha256") != native["compilerSha256"]:
            raise ValueError(f"Native compiler evidence mismatch: {name}")
        for requirement in sorted(requirements):
            if evidence.get("checks", {}).get(requirement) is not True:
                raise ValueError(f"Missing native evidence {name}: {requirement}")
    print(f"All {len(EXPECTED)} required native Visual Studio 2026 test cases and project evidence passed")


if __name__ == "__main__":
    verify(pathlib.Path(sys.argv[1]))
