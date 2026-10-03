#ifndef VAS_COMPILER_PROJECT_H
#define VAS_COMPILER_PROJECT_H

#include <cstdio>
#include <string>
#include <vector>

namespace vas
{
struct ProjectError
{
	std::string code, field, message, section;
	int row, column;
	size_t byteOffset;
	ProjectError() : row(0), column(0), byteOffset(0) {}
};
struct CompilationUnit
{
	std::string id, entry, config, output;
};
struct Project
{
	std::string path, root, name;
	bool legacy, hasName;
	std::vector<CompilationUnit> units;
	Project() : legacy(false), hasName(false) {}
};

// Read-only: does not inspect unit files, create an engine, or mutate the disk.
bool ReadProject(const char *filename, Project &project, ProjectError &error);
int DescribeProject(int argc, char **argv);
const char *LegacyProjectWarning();
bool ValidateProjectInputs(const Project &project, const CompilationUnit &unit, ProjectError &error);
// Called only after successful compilation. Records actually loaded sections,
// not guessed/scanned dependencies. Creates parents only after all probes pass.
bool PrepareProjectOutput(const Project &project, const CompilationUnit &unit,
	const std::vector<std::string> &sections, ProjectError &error);
// Validate the opened append handle before the ordinary report code truncates it.
bool ValidateProjectOutputHandle(FILE *file, const Project &project,
	const CompilationUnit &unit, const std::vector<std::string> &sections);
}
#endif
