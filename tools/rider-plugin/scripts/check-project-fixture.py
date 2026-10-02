#!/usr/bin/env python3
"""Validate the Rider host fixture before downloading/starting Rider (standard library only)."""

from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


NAMESPACE = {"m": "http://schemas.microsoft.com/developer/msbuild/2003"}
CPP_PROJECT_TYPE = "{BC8A1FFA-BEE3-4634-8014-F334798102B3}"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def check_fixture(directory: Path) -> None:
    project_path = directory / "VasNavigation.vcxproj"
    project = ET.parse(project_path).getroot()
    solution = (directory / "VasNavigation.sln").read_text(encoding="utf-8-sig")
    guid = project.findtext("m:PropertyGroup/m:ProjectGuid", namespaces=NAMESPACE)
    require(bool(guid), "The NMake project must declare ProjectGuid")
    solution_projects = re.findall(r'^Project\("([^"\n]+)"\) = "([^"\n]+)", "([^"\n]+)", "([^"\n]+)"$',
                                   solution, re.MULTILINE)
    require(solution_projects == [(CPP_PROJECT_TYPE, "VasNavigation", project_path.name, guid)],
            "The solution must reference exactly the matching NMake project and GUID")

    expected_imports = ["$(VCTargetsPath)\\Microsoft.Cpp.Default.props",
                        "$(VCTargetsPath)\\Microsoft.Cpp.props",
                        "$(VCTargetsPath)\\Microsoft.Cpp.targets"]
    require([item.get("Project") for item in project.findall("m:Import", NAMESPACE)] == expected_imports,
            "The fixture must retain the standard C++ imports in evaluation order")
    require(project.findtext("m:PropertyGroup/m:Keyword", namespaces=NAMESPACE) == "MakeFileProj",
            "The fixture must use the NMake project model")

    configurations = [item.get("Include") for item in project.findall("m:ItemGroup/m:ProjectConfiguration", NAMESPACE)]
    require(set(configurations) == {"Debug|x64", "Release|x64"} and len(configurations) == 2,
            "The fixture must have Debug and Release x64 configurations")
    groups = project.findall("m:PropertyGroup[@Label='Configuration']", NAMESPACE)
    for configuration in configurations:
        condition = f"'$(Configuration)|$(Platform)'=='{configuration}'"
        matching = [group for group in groups if group.get("Condition") == condition]
        require(len(matching) == 1, f"Missing or duplicate configuration properties: {configuration}")
        require(matching[0].findtext("m:ConfigurationType", namespaces=NAMESPACE) == "Makefile",
                f"Configuration is not NMake: {configuration}")
        require(matching[0].findtext("m:PlatformToolset", namespaces=NAMESPACE) == "v145",
                f"Configuration does not match the CI Visual Studio 2026 toolset: {configuration}")
        for mapping in (f"{configuration} = {configuration}",
                        f"{guid}.{configuration}.ActiveCfg = {configuration}",
                        f"{guid}.{configuration}.Build.0 = {configuration}"):
            require(mapping in solution, f"Missing solution configuration mapping: {mapping}")

    items = [item.get("Include", "").replace("\\", "/") for item in project.findall("m:ItemGroup/m:None", NAMESPACE)]
    require(len(items) == len(set(items)), "Duplicate explicit None items in the project")
    for item in items:
        require(item and not any(character in item for character in "*?;"),
                "Project items must be explicit paths, not wildcard or semicolon lists")
        target = (directory / item).resolve()
        require(target.is_relative_to(directory.resolve()) and target.is_file(),
                f"Missing or out-of-fixture project item: {item}")
    expected = {path.relative_to(directory).as_posix() for path in (directory / "src").rglob("*.vas")}
    included = {item for item in items if item.endswith(".vas")}
    require(included == expected,
            f"VAS item coverage differs: missing={sorted(expected - included)}, extra={sorted(included - expected)}")
    require("unicode-identifiers.config.txt" in items, "Missing manual-build compiler configuration item")
    print(f"Validated NMake fixture: {len(expected)} VAS files, {len(items)} None items, {len(configurations)} configurations")


if __name__ == "__main__":
    try:
        check_fixture(Path(__file__).resolve().parents[1] / "testData/solutions/vas-navigation")
    except (OSError, ET.ParseError, ValueError) as error:
        print(f"Rider project fixture check failed: {error}", file=sys.stderr)
        sys.exit(1)
