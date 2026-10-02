"""Fail closed: a build, zero-test run or skipped IDE facts cannot pass the gate."""
import pathlib
import sys
import xml.etree.ElementTree as ET

EXPECTED = {
    "RunsInsideVisualStudio2026ExperimentalHost",
    "ClassifiesUnicodeSpacePathAndReopens",
    "TripleStringsAndNonNestingCommentsUseCanonicalGrammar",
    "CommentAndUncommentAreRealUndoableCommands",
    "BracePairingAndTypingAreUndoable",
    "NewlineIndentsAndUndoRestoresText",
    "CppAndAsAreNotClaimedByVasGrammar",
}


def verify(path):
    root = ET.parse(path).getroot()  # A missing or invalid TRX is a failure.
    ns = {"t": "http://microsoft.com/schemas/VisualStudio/TeamTest/2010"}
    results = root.findall("t:Results/t:UnitTestResult", ns)
    if not results:
        raise ValueError("TRX contains no native host results")
    failed = [r.attrib for r in results if r.get("outcome") != "Passed"]
    if failed:
        raise ValueError(f"Non-passing tests: {failed}")
    for name in EXPECTED:
        matches = [r for r in results if name in r.get("testName", "")]
        if not matches:
            raise ValueError(f"Missing required native host test: {name}")
    print(f"All {len(EXPECTED)} required native Visual Studio 2026 test cases passed")


if __name__ == "__main__":
    verify(pathlib.Path(sys.argv[1]))
