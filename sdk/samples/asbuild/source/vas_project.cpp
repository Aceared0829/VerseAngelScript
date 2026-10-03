#include "vas_project.h"
#include "../../common/vas_build_report.h"
#include "../../common/vas_scriptbuilder.h"
// The pinned reader requires a complete C++11 implementation; v110 and older
// project files must be retargeted (see docs/vas-project.md).
#if defined(_MSC_VER) && _MSC_VER < 1900
#error "Project compilation requires MSVC 2015 (14.0.25123.0) or newer; use root CMake or retarget the legacy project."
#endif
// Pinned, unchanged upstream header; never exposed to the engine/add-ons.
#include "../vendor/nlohmann/json.hpp"
#include <algorithm>
#include <cerrno>
#include <cstdlib>
#include <set>
#include <stdexcept>
#ifndef _WIN32
#include <sys/stat.h>
#include <unistd.h>
#endif

namespace vas
{
namespace
{
using nlohmann::json;
const size_t MaxManifestBytes = 1024 * 1024;
const size_t MaxUnits = 256;
const int MaxDepth = 16;

struct PolicyError : std::runtime_error
{
	std::string code, field;
	PolicyError(const char *code, const std::string &field, const std::string &message)
		: std::runtime_error(message), code(code), field(field) {}
};

bool Fail(ProjectError &error, const char *code, const std::string &field,
	const std::string &section, const std::string &message)
{
	error = ProjectError();
	error.code = code; error.field = field; error.section = section; error.message = message;
	return false;
}

void Require(bool condition, const char *code, const std::string &field, const std::string &message)
{
	if( !condition ) throw PolicyError(code, field, message);
}

bool SafeText(const std::string &value)
{
	if( !IsValidUtf8(value) ) return false;
	for( size_t i = 0; i < value.size(); ++i )
	{
		unsigned char ch = static_cast<unsigned char>(value[i]);
		if( ch < 0x20 || ch == 0x7f ) return false;
		if( ch == 0xc2 && i + 1 < value.size() &&
			static_cast<unsigned char>(value[i + 1]) >= 0x80 &&
			static_cast<unsigned char>(value[i + 1]) <= 0x9f ) return false;
	}
	return true;
}

std::string Parent(const std::string &path)
{
	size_t slash = path.find_last_of('/');
	if( slash == std::string::npos ) return "";
	if( slash == 0 ) return "/";
	// Preserve the slash on Windows drive roots.
	if( slash == 2 && path[1] == ':' ) return path.substr(0, 3);
	return path.substr(0, slash);
}

std::string Join(const std::string &root, const std::string &relative)
{
	return root + (root.empty() || root.back() != '/' ? "/" : "") + relative;
}

bool AbsoluteIdentity(const char *filename, std::string &result)
{
	if( !filename || !SafeText(filename) || !AbsolutePath(filename, result) || !SafeText(result) ) return false;
#ifndef _WIN32
	// The compiler treats backslashes as separators on every host. Do not
	// describe a different POSIX file from the one it would actually load.
	if( result.find('\\') != std::string::npos ) return false;
#endif
	std::replace(result.begin(), result.end(), '\\', '/');
	std::string prefix;
	size_t begin = 0, protectedParts = 0;
	#ifdef _WIN32
	if( result.compare(0, 2, "//") == 0 ) { prefix = "//"; begin = 2; protectedParts = 2; }
	else if( result.size() >= 3 && result[1] == ':' && result[2] == '/' ) { prefix = result.substr(0, 3); begin = 3; }
	else
#endif
	if( result[0] == '/' ) { prefix = "/"; begin = 1; }
	else return false;
	std::vector<std::string> parts;
	while( begin <= result.size() )
	{
		size_t end = result.find('/', begin);
		if( end == std::string::npos ) end = result.size();
		std::string part = result.substr(begin, end - begin);
		if( part == ".." ) { if( parts.size() > protectedParts ) parts.pop_back(); }
		else if( !part.empty() && part != "." ) parts.push_back(part);
		begin = end + 1;
	}
	result = prefix;
	for( size_t i = 0; i < parts.size(); ++i ) result += (i ? "/" : "") + parts[i];
	return true;
}

std::string PointerPart(const std::string &key)
{
	std::string escaped;
	for( char ch : key ) escaped += ch == '~' ? "~0" : ch == '/' ? "~1" : std::string(1, ch);
	return escaped;
}

void Properties(const json &object, std::initializer_list<const char *> allowed, const std::string &field)
{
	Require(object.is_object(), "project_schema", field, "Expected an object at " + field);
	for( json::const_iterator it = object.begin(); it != object.end(); ++it )
	{
		bool known = false;
		for( const char *key : allowed ) if( it.key() == key ) known = true;
		Require(known, "project_schema", field + "/" + PointerPart(it.key()), "Unknown property at " + field + "/" + PointerPart(it.key()));
	}
}

std::string String(const json &object, const char *key, const std::string &field, size_t limit, bool nonempty = true)
{
	std::string pointer = field + "/" + key;
	Require(object.count(key) && object[key].is_string(), "project_schema", pointer, "Expected a string at " + pointer);
	std::string value = object[key].get<std::string>();
	Require((!nonempty || !value.empty()) && value.size() <= limit, "project_schema", pointer,
		"String length outside allowed range at " + pointer);
	return value;
}

bool ReservedComponent(const std::string &component)
{
	std::string base = component.substr(0, component.find('.'));
	while( !base.empty() && base.back() == ' ' ) base.pop_back();
	for( char &ch : base ) if( ch >= 'a' && ch <= 'z' ) ch -= 'a' - 'A';
	if( base == "CON" || base == "CONIN$" || base == "CONOUT$" || base == "PRN" ||
		base == "AUX" || base == "NUL" || base == "CLOCK$" ) return true;
	if( base.compare(0, 3, "COM") == 0 || base.compare(0, 3, "LPT") == 0 )
	{
		std::string digit = base.substr(3);
		if( (digit.size() == 1 && digit[0] >= '1' && digit[0] <= '9') ||
			digit == "\xc2\xb9" || digit == "\xc2\xb2" || digit == "\xc2\xb3" ) return true;
	}
	return false;
}

std::string PortablePath(const json &object, const char *key, const std::string &field)
{
	std::string path = String(object, key, field, 4096);
	std::string pointer = field + "/" + key;
	Require(path.find_first_of("\\:<>\"|?*") == std::string::npos && path[0] != '/', "project_path", pointer,
		"Expected a portable manifest-relative forward-slash path at " + pointer);
	size_t begin = 0;
	while( begin <= path.size() )
	{
		size_t end = path.find('/', begin);
		if( end == std::string::npos ) end = path.size();
		std::string part = path.substr(begin, end - begin);
		Require(!part.empty() && part != "." && part != ".." && part.back() != '.' && part.back() != ' ' && !ReservedComponent(part),
			"project_path", pointer, "Invalid or reserved path component at " + pointer);
		begin = end + 1;
	}
	return path;
}

void ValidateEntry(const std::string &entry, const std::string &field)
{
	Require(entry.size() >= 4 && entry.compare(entry.size() - 4, 4, ".vas") == 0,
		"project_path", field, "Entry scripts must use the lowercase .vas extension at " + field);
}

void ValidateId(const std::string &id, const std::string &field)
{
	for( size_t i = 0; i < id.size(); ++i )
	{
		char c = id[i];
		Require((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
			c == '_' || c == '-' || (i != 0 && c == '.'), "project_schema", field,
			"Unit ids must match [A-Za-z0-9_-][A-Za-z0-9_.-]{0,63}");
	}
}

struct FileInfo
{
	bool exists, directory, regular, link;
	unsigned long long volume, index, links;
	FileInfo() : exists(false), directory(false), regular(false), link(false), volume(0), index(0), links(0) {}
};

#ifdef _WIN32
bool HandleInfo(HANDLE handle, FileInfo &info)
{
	BY_HANDLE_FILE_INFORMATION native = {};
	if( !GetFileInformationByHandle(handle, &native) ) return false;
	info.exists = true;
	info.directory = (native.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) != 0;
	info.link = (native.dwFileAttributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0;
	info.regular = !info.directory && GetFileType(handle) == FILE_TYPE_DISK;
	info.volume = native.dwVolumeSerialNumber;
	info.index = (static_cast<unsigned long long>(native.nFileIndexHigh) << 32) | native.nFileIndexLow;
	info.links = native.nNumberOfLinks;
	return true;
}
#endif

bool Probe(const std::string &path, FileInfo &info, bool follow = true)
{
	info = FileInfo();
#ifdef _WIN32
	std::wstring native;
	if( !ToWide(path.c_str(), native) ) return false;
	HANDLE handle = CreateFileW(native.c_str(), 0, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
		0, OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS | (follow ? 0 : FILE_FLAG_OPEN_REPARSE_POINT), 0);
	if( handle == INVALID_HANDLE_VALUE )
	{
		DWORD error = GetLastError();
		return error == ERROR_FILE_NOT_FOUND || error == ERROR_PATH_NOT_FOUND;
	}
	bool ok = HandleInfo(handle, info);
	CloseHandle(handle);
	return ok;
#else
	struct stat native;
	if( (follow ? stat(path.c_str(), &native) : lstat(path.c_str(), &native)) != 0 ) return errno == ENOENT || errno == ENOTDIR;
	info.exists = true; info.directory = S_ISDIR(native.st_mode); info.regular = S_ISREG(native.st_mode);
	info.link = S_ISLNK(native.st_mode); info.volume = native.st_dev; info.index = native.st_ino; info.links = native.st_nlink;
	return true;
#endif
}

bool RealPath(const std::string &path, std::string &real)
{
#ifdef _WIN32
	std::wstring native;
	if( !ToWide(path.c_str(), native) ) return false;
	HANDLE handle = CreateFileW(native.c_str(), 0, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
		0, OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS, 0);
	if( handle == INVALID_HANDLE_VALUE ) return false;
	std::vector<wchar_t> buffer(512);
	DWORD length = GetFinalPathNameByHandleW(handle, buffer.data(), static_cast<DWORD>(buffer.size()), FILE_NAME_NORMALIZED);
	if( length >= buffer.size() )
	{
		buffer.resize(static_cast<size_t>(length) + 1);
		length = GetFinalPathNameByHandleW(handle, buffer.data(), static_cast<DWORD>(buffer.size()), FILE_NAME_NORMALIZED);
	}
	CloseHandle(handle);
	if( !length || length >= buffer.size() || !ToUtf8(buffer.data(), real) ) return false;
	std::replace(real.begin(), real.end(), '\\', '/');
	return true;
#else
	char *native = realpath(path.c_str(), 0);
	if( !native ) return false;
	real = native; free(native);
	return true;
#endif
}

bool SamePath(const std::string &a, const std::string &b)
{
#ifdef _WIN32
	std::wstring wa, wb;
	if( !ToWide(a.c_str(), wa) || !ToWide(b.c_str(), wb) ) return false;
	return CompareStringOrdinal(wa.c_str(), -1, wb.c_str(), -1, TRUE) == CSTR_EQUAL;
#else
	return a == b;
#endif
}

bool Contained(const std::string &root, const std::string &path)
{
	if( SamePath(root, path) ) return true;
	std::string prefix = root + (root.back() == '/' ? "" : "/");
	return path.size() > prefix.size() && SamePath(prefix, path.substr(0, prefix.size()));
}

bool SameFile(const FileInfo &a, const FileInfo &b)
{
	return a.exists && b.exists && a.volume == b.volume && a.index == b.index;
}

std::vector<std::string> ProtectedInputs(const Project &project, const CompilationUnit &unit, const std::vector<std::string> &sections)
{
	std::vector<std::string> files = sections;
	files.push_back(project.path); files.push_back(unit.config); files.push_back(unit.entry);
	// A selected output must not corrupt inputs explicitly declared by another
	// unit either. Missing unselected files are fine: Probe treats absence as
	// no identity, while literal path comparisons still protect future inputs.
	for( const CompilationUnit &declared : project.units )
	{
		files.push_back(declared.config); files.push_back(declared.entry);
	}
	return files;
}

bool SafeDestination(const Project &project, const CompilationUnit &unit, const std::vector<std::string> &sections, ProjectError &error)
{
	FileInfo output;
	if( !Contained(project.root, unit.output) || InvalidReportOutput(unit.output.c_str()) ||
		!Probe(unit.output, output, false) || (output.exists && (output.link || !output.regular || output.links != 1)) )
		return Fail(error, "project_output", "/output", unit.output, "Project output must be a contained regular, non-linked file distinct from stdout");
	for( const CompilationUnit &declared : project.units )
	{
		if( declared.id == unit.id ) continue;
		FileInfo other;
		if( !Probe(declared.output, other) || SameFile(other, output) )
			return Fail(error, "project_output", "/output", unit.output, "Project output must not alias another unit's existing output");
	}
	for( const std::string &input : ProtectedInputs(project, unit, sections) )
	{
		FileInfo info;
		if( SamePath(input, unit.output) || !Probe(input, info) || SameFile(info, output) )
			return Fail(error, "project_output", "/output", unit.output, "Project output must not overwrite the manifest, configuration, entry or any loaded section");
	}
	return true;
}

bool ParentInside(const Project &project, const std::string &parent, ProjectError &error)
{
	std::string realRoot, realParent;
	FileInfo info;
	if( !Probe(parent, info) || !info.directory || !RealPath(project.root, realRoot) || !RealPath(parent, realParent) ||
		!Contained(realRoot, realParent) )
		return Fail(error, "project_output", "/output", parent, "Project output parent must resolve to a directory inside the manifest root");
	return true;
}
}

const char *LegacyProjectWarning()
{
	return "Legacy project adapted as unit 'main'; migrate to schemaVersion 1. The builder and runner fields are ignored and never executed.";
}

bool ReadProject(const char *filename, Project &project, ProjectError &error)
{
	project = Project(); error = ProjectError();
	if( !AbsoluteIdentity(filename, project.path) )
	{
		project.path.clear();
		return Fail(error, "project_encoding", "", "", "Manifest path must have an unambiguous absolute UTF-8 identity without control characters");
	}
	project.root = Parent(project.path);
	FileInfo info;
	if( !Probe(project.path, info) || !info.regular )
		return Fail(error, "project_io", "", project.path, "Manifest must be a readable regular file");
	FILE *file = OpenFile(project.path.c_str(), "rb");
	if( !file ) return Fail(error, "project_io", "", project.path, "Failed to open project manifest");
	std::string bytes;
	char buffer[4096];
	size_t count;
	while( (count = fread(buffer, 1, sizeof(buffer), file)) != 0 )
	{
		bytes.append(buffer, count);
		if( bytes.size() > MaxManifestBytes ) break;
	}
	bool readFailed = ferror(file) != 0;
	fclose(file);
	if( readFailed ) return Fail(error, "project_io", "", project.path, "Failed to read project manifest");
	if( bytes.size() > MaxManifestBytes ) return Fail(error, "project_limit", "", project.path, "Project manifest exceeds 1048576 bytes");
	if( !IsValidUtf8(bytes) || bytes.find('\0') != std::string::npos )
		return Fail(error, "project_encoding", "", project.path, "Project manifest must be valid UTF-8 without raw NUL bytes");
	try
	{
		std::vector<std::set<std::string> > objectKeys;
		int containers = 0;
		json document = json::parse(bytes, [&](int, json::parse_event_t event, json &value) {
			if( event == json::parse_event_t::object_start || event == json::parse_event_t::array_start )
				Require(++containers <= MaxDepth, "project_limit", "", "Project JSON container depth exceeds 16");
			if( event == json::parse_event_t::object_start ) objectKeys.push_back(std::set<std::string>());
			if( event == json::parse_event_t::key )
				Require(objectKeys.back().insert(value.get<std::string>()).second, "project_json", "", "Duplicate JSON object key: " + value.get<std::string>());
			if( (event == json::parse_event_t::key || event == json::parse_event_t::value) && value.is_string() )
				Require(SafeText(value.get<std::string>()), "project_encoding", "", "Project JSON strings must not contain NUL or control characters");
			if( event == json::parse_event_t::object_end ) objectKeys.pop_back();
			if( event == json::parse_event_t::object_end || event == json::parse_event_t::array_end ) --containers;
			return true;
		});
		Require(document.is_object(), "project_schema", "", "Project manifest must be an object");
		project.legacy = document.count("schemaVersion") == 0;
		if( project.legacy ) Properties(document, {"name", "entry", "builderConfig", "bytecodeOutput", "builder", "runner"}, "");
		else
		{
			Properties(document, {"schemaVersion", "name", "compilationUnits"}, "");
			Require(document["schemaVersion"].is_number_integer() && document["schemaVersion"] == 1,
				"project_schema", "/schemaVersion", "Only integer schemaVersion 1 is supported");
		}
		if( document.count("name") ) { project.name = String(document, "name", "", 256, false); project.hasName = true; }
		if( project.legacy )
		{
			for( const char *tool : {"builder", "runner"} ) if( document.count(tool) ) String(document, tool, "", 4096, false);
			CompilationUnit unit;
			unit.id = "main";
			unit.entry = PortablePath(document, "entry", ""); ValidateEntry(unit.entry, "/entry");
			unit.config = PortablePath(document, "builderConfig", "");
			unit.output = PortablePath(document, "bytecodeOutput", "");
			project.units.push_back(unit);
		}
		else
		{
			Require(document.count("compilationUnits") && document["compilationUnits"].is_array(),
				"project_schema", "/compilationUnits", "Expected a compilationUnits array");
			const json &units = document["compilationUnits"];
			Require(!units.empty() && units.size() <= MaxUnits, "project_limit", "/compilationUnits", "Expected 1 through 256 compilation units");
			std::set<std::string> ids, outputs;
			for( size_t i = 0; i < units.size(); ++i )
			{
				std::string field = "/compilationUnits/" + std::to_string(i);
				Properties(units[i], {"id", "entry", "hostApi", "output"}, field);
				CompilationUnit unit;
				unit.id = String(units[i], "id", field, 64); ValidateId(unit.id, field + "/id");
				Require(ids.insert(unit.id).second, "project_schema", field + "/id", "Duplicate compilation unit id: " + unit.id);
				unit.entry = PortablePath(units[i], "entry", field); ValidateEntry(unit.entry, field + "/entry");
				Require(units[i].count("hostApi"), "project_schema", field + "/hostApi", "Missing hostApi object");
				Properties(units[i]["hostApi"], {"config"}, field + "/hostApi");
				unit.config = PortablePath(units[i]["hostApi"], "config", field + "/hostApi");
				unit.output = PortablePath(units[i], "output", field);
				for( const std::string &output : outputs ) Require(!SamePath(output, unit.output), "project_path", field + "/output", "Compilation units must have distinct output paths");
				outputs.insert(unit.output);
				project.units.push_back(unit);
			}
		}
		for( CompilationUnit &unit : project.units )
		{
			unit.entry = Join(project.root, unit.entry);
			unit.config = Join(project.root, unit.config);
			unit.output = Join(project.root, unit.output);
		}
		return true;
	}
	catch( const PolicyError &e ) { project.units.clear(); return Fail(error, e.code.c_str(), e.field, project.path, e.what()); }
	catch( const json::parse_error &e )
	{
		project.units.clear(); Fail(error, "project_json", "", project.path, e.what());
		if( e.byte >= 1 && e.byte <= bytes.size() + 1 )
		{
			error.byteOffset = e.byte; error.row = 1; error.column = 1;
			for( size_t i = 0; i < e.byte - 1; ++i )
				if( bytes[i] == '\n' ) { ++error.row; error.column = 1; } else ++error.column;
		}
		return false;
	}
	catch( const json::exception &e ) { project.units.clear(); return Fail(error, "project_json", "", project.path, e.what()); }
}

int DescribeProject(int argc, char **argv)
{
	Project project; ProjectError error;
	bool success = false;
	if( argc == 3 ) success = ReadProject(argv[2], project, error);
	else Fail(error, "project_arguments", "", "", "Usage: vasbuild --describe-project=json <manifest>");
	json result;
	result["protocol"] = "vas-project"; result["version"] = 1; result["success"] = success;
	result["project"] = project.path.empty() ? json(nullptr) : json(project.path);
	result["projectRoot"] = project.root.empty() ? json(nullptr) : json(project.root);
	result["projectSchemaVersion"] = success && !project.legacy ? json(1) : json(nullptr);
	result["legacyProject"] = project.legacy;
	result["name"] = project.hasName ? json(project.name) : json(nullptr);
	result["compilationUnits"] = json::array(); result["warnings"] = json::array(); result["errors"] = json::array();
	if( success )
	{
		for( const CompilationUnit &unit : project.units )
			result["compilationUnits"].push_back({{"id", unit.id}, {"entry", unit.entry}, {"hostApi", {{"config", unit.config}}}, {"output", unit.output}});
		if( project.legacy ) result["warnings"].push_back({{"code", "legacy_project"}, {"message", LegacyProjectWarning()}});
	}
	else result["errors"].push_back({{"code", error.code}, {"field", error.field}, {"message", error.message},
		{"section", error.section}, {"row", error.row}, {"column", error.column}, {"byteOffset", error.byteOffset ? json(error.byteOffset) : json(nullptr)}});
	std::string text = result.dump(-1, ' ', false, json::error_handler_t::replace) + '\n';
#ifdef _WIN32
	if( _setmode(_fileno(stdout), _O_BINARY) == -1 ) return -1;
#endif
	if( fwrite(text.data(), 1, text.size(), stdout) != text.size() || fflush(stdout) != 0 ) return -1;
	return success ? 0 : -1;
}

bool ValidateProjectInputs(const Project &project, const CompilationUnit &unit, ProjectError &error)
{
	std::string realRoot;
	if( !RealPath(project.root, realRoot) ) return Fail(error, "project_input", "", project.root, "Failed to resolve project root");
	const std::string inputs[] = {unit.config, unit.entry};
	for( size_t i = 0; i < 2; ++i )
	{
		FileInfo info; std::string real;
		if( !Probe(inputs[i], info) || !info.regular || !RealPath(inputs[i], real) || !Contained(realRoot, real) )
			return Fail(error, "project_input", i == 0 ? "/hostApi/config" : "/entry", inputs[i],
				"Selected project input must be a readable regular file resolving inside the manifest root");
	}
	return true;
}

bool PrepareProjectOutput(const Project &project, const CompilationUnit &unit,
	const std::vector<std::string> &sections, ProjectError &error)
{
	if( !SafeDestination(project, unit, sections, error) ) return false;
	std::string parent = Parent(unit.output), nearest = parent;
	std::vector<std::string> missing;
	while( true )
	{
		FileInfo info;
		if( !Probe(nearest, info, false) ) return Fail(error, "project_output", "/output", nearest, "Cannot inspect project output parent");
		if( info.exists ) break;
		if( SamePath(nearest, project.root) || nearest.empty() ) return Fail(error, "project_output", "/output", nearest, "Project output root is unavailable");
		missing.push_back(nearest); nearest = Parent(nearest);
	}
	if( !ParentInside(project, nearest, error) ) return false;
	for( std::vector<std::string>::reverse_iterator it = missing.rbegin(); it != missing.rend(); ++it )
	{
#ifdef _WIN32
		std::wstring native;
		if( !ToWide(it->c_str(), native) || (!CreateDirectoryW(native.c_str(), 0) && GetLastError() != ERROR_ALREADY_EXISTS) )
#else
		if( mkdir(it->c_str(), 0777) != 0 && errno != EEXIST )
#endif
			return Fail(error, "project_output", "/output", *it, "Failed to create project output directory");
		if( !ParentInside(project, *it, error) ) return false;
	}
	return ParentInside(project, parent, error) && SafeDestination(project, unit, sections, error);
}

bool ValidateProjectOutputHandle(FILE *file, const Project &project,
	const CompilationUnit &unit, const std::vector<std::string> &sections)
{
	FileInfo output;
#ifdef _WIN32
	if( !HandleInfo(reinterpret_cast<HANDLE>(_get_osfhandle(_fileno(file))), output) ) return false;
#else
	struct stat info;
	if( fstat(fileno(file), &info) != 0 ) return false;
	output.exists = true; output.regular = S_ISREG(info.st_mode);
	output.volume = info.st_dev; output.index = info.st_ino; output.links = info.st_nlink;
#endif
	if( !output.regular || output.links != 1 || output.link ) return false;
	ProjectError error;
	if( !ParentInside(project, Parent(unit.output), error) || !SafeDestination(project, unit, sections, error) ) return false;
	FileInfo named;
	if( !Probe(unit.output, named, false) || !SameFile(output, named) ) return false;
	for( const std::string &input : ProtectedInputs(project, unit, sections) )
	{
		FileInfo info;
		if( !Probe(input, info) || SameFile(info, output) ) return false;
	}
	return true;
}
}
