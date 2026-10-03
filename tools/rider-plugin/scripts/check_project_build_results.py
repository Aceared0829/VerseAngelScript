"""Fail closed unless every required native Rider Build Project case passed.

This checks real host JUnit evidence, not source presence, JVM-only tests, or the
Gradle process status. It emits only fixed allowlisted case names, never report
stdout, paths, compiler output, exceptions, environment, or XML properties.
"""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


CLASS = "com.verseangelscript.rider.projectbuild.VasRiderProjectBuildIntegrationTest"
EXPECTED = frozenset({
    "buildsExplicitUnitsWithDistinctHostConfigurations",
    "navigatesNestedUtf8DiagnosticsIndependentlyOfActiveEditor",
    "preservesLegacyWarningsAndLiteralUnicodeMetacharacterPaths",
    "rejectsDirtyEntryManifestHostAndKnownInclude",
    "retainsDependenciesAfterPartialTraversal",
    "blocksUntrustedAndPassiveProjectExecution",
    "cancelsSelectionAndSuppressesSupersededResults",
    "suppressesResultsAfterInputsSettingsOrTrustChange",
    "rejectsMissingManifestWithoutActiveFileFallback",
})
PREFIX = "VAS_RIDER_PROJECT_BUILD_AUDIT_V1 "
RECORD = re.compile(re.escape(PREFIX) + r"PASS case=([A-Za-z][A-Za-z0-9]*)")


class EvidenceError(ValueError):
    """A fixed safe diagnostic; report contents must never be interpolated."""


def verify(path):
    try:
        root = ET.parse(path).getroot()
    except (OSError, ET.ParseError, ValueError, LookupError):
        raise EvidenceError("Missing or invalid JUnit report") from None
    suites = [suite for suite in root.iter("testsuite") if suite.get("name") == CLASS]
    if len(suites) != 1:
        raise EvidenceError("Missing or duplicate native project build suite")
    suite = suites[0]
    for counter in ("failures", "errors", "skipped", "disabled"):
        if suite.get(counter, "0") != "0":
            raise EvidenceError("Native project build suite did not pass")
    if any(suite.find(f".//{tag}") is not None for tag in ("failure", "error", "skipped", "disabled")):
        raise EvidenceError("Native project build suite did not pass")
    cases = suite.findall("testcase")
    if "tests" in suite.attrib and suite.get("tests") != str(len(cases)):
        raise EvidenceError("Inconsistent native project build case count")
    selected = []
    for name in sorted(EXPECTED):
        matches = [case for case in cases if case.get("classname") == CLASS
                   and case.get("name") in (name, name + "()")]
        if len(matches) != 1:
            raise EvidenceError("Missing or duplicate required native project build case")
        case = matches[0]
        if case.get("status", "run") not in ("run", "passed") or case.get("result", "completed") not in ("completed", "passed"):
            raise EvidenceError("Required native project build case did not pass")
        selected.append((name, case))
    # Gradle writes suite-level stdout. Some JUnit writers use case-level
    # stdout; in that form the record must agree with its enclosing case.
    seen = set()
    streams = [(None, stream) for stream in suite.findall("system-out")]
    for name, case in selected:
        streams.extend((name, stream) for stream in case.findall("system-out"))
    for owner, stream in streams:
        for line in (stream.text or "").splitlines():
            if not line.startswith(PREFIX):
                continue
            match = RECORD.fullmatch(line)
            if not match or match.group(1) not in EXPECTED:
                raise EvidenceError("Malformed controlled project build audit record")
            name = match.group(1)
            if owner is not None and name != owner:
                raise EvidenceError("Project build audit belongs to a different case")
            if name in seen:
                raise EvidenceError("Duplicate required project build audit record")
            seen.add(name)
    if seen != EXPECTED:
        raise EvidenceError("Missing required project build audit record")
    return [PREFIX + "PASS case=" + name for name in sorted(EXPECTED)]


def main(argv=None):
    args = sys.argv[1:] if argv is None else argv
    if len(args) != 1:
        print("Usage: check_project_build_results.py <native project build JUnit XML>", file=sys.stderr)
        return 1
    try:
        lines = verify(Path(args[0]))
    except EvidenceError as error:
        print("Rider project build evidence rejected: " + str(error), file=sys.stderr)
        return 1
    for line in lines:
        print(line)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
