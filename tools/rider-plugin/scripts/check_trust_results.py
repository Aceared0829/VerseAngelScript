"""Verify the native trust case and expose only its controlled audit records.

Usage: python scripts/check_trust_results.py <JUnit XML for VasRiderTrustIntegrationTest>
This is an evidence check, not a replacement for Gradle's process exit status.
"""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


CLASS = "com.verseangelscript.rider.diagnostics.VasRiderTrustIntegrationTest"
EXPECTED = {"guardsRegisteredBackgroundDiagnosticsAcrossTrustChanges"}
PREFIX = "VAS_RIDER_TRUST_AUDIT_V1 "
BOOLEAN = r"(true|false)"
CLASS_NAME = r"((?:[A-Za-z_$][A-Za-z0-9_$]*\.)+[A-Za-z_$][A-Za-z0-9_$]*)"
TRUST = re.compile(re.escape(PREFIX) + "TRUST trusted=" + BOOLEAN)
FILTERS = re.compile(re.escape(PREFIX) + "FILTERS trusted=" + BOOLEAN + r" count=(0|[1-9][0-9]{0,5})")
FILTER = re.compile(re.escape(PREFIX) + "FILTER trusted=" + BOOLEAN + " class=" + CLASS_NAME + " prohibited=" + BOOLEAN)


class EvidenceError(ValueError):
    """A fixed diagnostic which must never include report contents or paths."""


def controlled_audit(stdout):
    """Reconstruct allowlisted fields only, after checking complete enumeration."""
    lines = []
    trust_states = set()
    counts = {}
    decisions = {"false": [], "true": []}
    for line in stdout.splitlines():
        if not line.startswith(PREFIX):
            continue
        if match := TRUST.fullmatch(line):
            trusted = match.group(1)
            trust_states.add(trusted)
            lines.append(f"{PREFIX}TRUST trusted={trusted}")
        elif match := FILTERS.fullmatch(line):
            trusted, count = match.groups()
            if trusted in counts:
                raise EvidenceError("Duplicate filter enumeration")
            counts[trusted] = int(count)
            lines.append(f"{PREFIX}FILTERS trusted={trusted} count={int(count)}")
        elif match := FILTER.fullmatch(line):
            trusted, class_name, prohibited = match.groups()
            if trusted not in counts:
                raise EvidenceError("Invalid filter enumeration")
            decisions[trusted].append((class_name, prohibited))
            lines.append(f"{PREFIX}FILTER trusted={trusted} class={class_name} prohibited={prohibited}")
        else:
            raise EvidenceError("Malformed controlled audit record")
    if trust_states != {"false", "true"} or set(counts) != {"false", "true"}:
        raise EvidenceError("Missing trust or filter evidence")
    if any(counts[state] != len(decisions[state]) for state in counts):
        raise EvidenceError("Incomplete filter enumeration")
    return lines


def verify(path):
    """Return safe output only after the exact native class/case has passed."""
    try:
        root = ET.parse(path).getroot()
    except (OSError, ET.ParseError, ValueError, LookupError):
        raise EvidenceError("Missing or invalid JUnit report") from None
    suites = [suite for suite in root.iter("testsuite") if suite.get("name") == CLASS]
    if len(suites) != 1:
        raise EvidenceError("Missing or duplicate native trust suite")
    suite = suites[0]
    for counter in ("failures", "errors", "skipped", "disabled"):
        if suite.get(counter, "0") != "0":
            raise EvidenceError("Native trust suite did not pass")
    if any(suite.find(f".//{tag}") is not None for tag in ("failure", "error", "skipped")):
        raise EvidenceError("Native trust suite did not pass")
    cases = suite.findall("testcase")
    if "tests" in suite.attrib and suite.get("tests") != str(len(cases)):
        raise EvidenceError("Inconsistent native trust case count")
    selected = []
    for name in sorted(EXPECTED):
        matches = [case for case in cases if case.get("classname") == CLASS
                   and case.get("name") in (name, name + "()")]
        if len(matches) != 1:
            raise EvidenceError("Missing or duplicate required native trust case")
        case = matches[0]
        if case.get("status", "run") not in ("run", "passed") or case.get("result", "completed") not in ("completed", "passed"):
            raise EvidenceError("Required native trust case did not pass")
        selected.append(case)
    # Gradle uses suite-level stdout. Also accept JUnit's case-level stdout,
    # but never read other cases, other suites, stderr, properties, or failures.
    streams = list(suite.findall("system-out"))
    for case in selected:
        streams.extend(case.findall("system-out"))
    return controlled_audit("\n".join(stream.text or "" for stream in streams))


def main(argv=None):
    args = sys.argv[1:] if argv is None else argv
    if len(args) != 1:
        print("Usage: check_trust_results.py <native trust JUnit XML>", file=sys.stderr)
        return 1
    try:
        lines = verify(Path(args[0]))
    except EvidenceError as error:
        print("Rider trust evidence rejected: " + str(error), file=sys.stderr)
        return 1
    for line in lines:
        print(line)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
