#ifndef VAS_SAMPLE_SCRIPTBUILDER_H
#define VAS_SAMPLE_SCRIPTBUILDER_H

#include "../../add_on/scriptbuilder/scriptbuilder.h"
#include "vas_paths.h"
#include "vas_preprocessor.h"
#include <limits>
#include <unordered_map>
#include <unordered_set>
#ifdef _WIN32
#include <io.h>
#else
#include <sys/stat.h>
#endif

namespace vas
{
// This is the existing builder identity policy, shared with report paths.
inline bool ScriptSectionPath(const char *filename, std::string &section)
{
	if( !AbsolutePath(filename, section) ) return false;
	// Match CScriptBuilder's section identity: absolute UTF-8 with forward
	// slashes and lexical dot segments removed (not symlink resolution).
	for( size_t i = 0; i < section.size(); ++i )
		if( section[i] == '\\' ) section[i] = '/';
	size_t pos;
	while( (pos = section.find("/./")) != std::string::npos )
		section.erase(pos + 1, 2);
	while( (pos = section.find("/../")) != std::string::npos )
	{
		size_t parent = section.rfind('/', pos == 0 ? 0 : pos - 1);
		if( parent == std::string::npos || parent == pos ) break;
		section.erase(parent, pos + 3 - parent);
	}

	return true;
}

// Keep VAS file I/O at the native boundary on every toolchain. In particular,
// the upstream file loader uses narrow fopen on MinGW, despite UTF-8 sections.
class ScriptBuilder : public CScriptBuilder
{
public:
	typedef void (*SectionLoadedCallback)(const std::string &section, const std::string &code, bool regularFile, void *param);
	ScriptBuilder() : loadedCallback(0), loadedParam(0), inspectFileType(false) {}
	~ScriptBuilder()
	{
		if (diagnosticCallback && GetEngine())
			GetEngine()->SetMessageCallback(asFUNCTION(diagnosticCallback), diagnosticParam, asCALL_CDECL);
	}
	int StartNewModule(asIScriptEngine *value, const char *name)
	{
		int result = CScriptBuilder::StartNewModule(value, name);
		preprocessor.reset(value);
		for (const auto &word : definedWords) preprocessor.defineWord(word);
		seen.clear(); normalized.clear(); resolutions.clear(); searchPaths.clear();
		rootsInitialized = false; fileReads = 0; loadDepth = 0; dependencyKind = Preprocessor::Quoted;
		return result;
	}
	void DefineWord(const char *word)
	{
		CScriptBuilder::DefineWord(word);
		if (GetEngine()) preprocessor.defineWord(word);
	}
	void SetMappedMessageCallback(void (*callback)(const asSMessageInfo *, void *), void *param)
	{
		diagnosticCallback = callback; diagnosticParam = param;
		GetEngine()->SetMessageCallback(asMETHOD(ScriptBuilder, MappedMessage), this, asCALL_THISCALL);
	}
	uint64_t GetFileReadCount() const { return fileReads; }
	const Preprocessor::Statistics &GetPreprocessingStatistics() const { return preprocessor.stats; }
	void AddSearchPath(const std::string &path) { configuredPaths.push_back(path); resolutions.clear(); rootsInitialized = false; }
	Preprocessor::DependencyKind GetDependencyKind() const { return dependencyKind; }
	const std::string &GetResolutionError() const { return resolutionError; }
	std::string ResolveDependency(const char *requested, const char *from)
	{
		std::string path = requested ? requested : "", parent = from ? from : "";
		parent.resize(parent.find_last_of("/\\") == std::string::npos ? 0 : parent.find_last_of("/\\") + 1);
		std::string key = std::to_string(int(dependencyKind)) + "|" + parent + "|" + path;
		resolutionError.clear();
		auto cached = resolutions.find(key);
		if (cached != resolutions.end()) return cached->second;
		if (path.empty()) return path;
		if (path[0] == '/' || path[0] == '\\' || path.find(':') != std::string::npos) return Normalize(path);
		std::string local = Normalize(parent + path);
		if (dependencyKind != Preprocessor::System && (Exists(local) || path.size() < 4 || path.substr(path.size() - 4) != ".vas"))
			return resolutions.emplace(key, local).first->second;
		std::string result;
		for (const auto &root : searchPaths)
		{
			std::string candidate = Normalize(root + "/" + path);
			if (!Exists(candidate)) continue;
			if (!result.empty() && Identity(result) != Identity(candidate))
			{
				resolutionError = "Ambiguous module/include '" + path + "': '" + result + "' and '" + candidate + "'";
				return "";
			}
			result = candidate;
		}
		if (result.empty()) result = dependencyKind == Preprocessor::System && !searchPaths.empty() ? Normalize(searchPaths[0] + "/" + path) : local;
		return resolutions.emplace(key, result).first->second;
	}
	void SetSectionLoadedCallback(SectionLoadedCallback callback, void *param, bool inspectType = false)
	{
		loadedCallback = callback;
		loadedParam = param;
		inspectFileType = inspectType;
	}

	int AddSectionFromFile(const char *filename)
	{
		std::string section;
		section = Normalize(filename ? filename : "");
		if( section.empty() )
		{
			GetEngine()->WriteMessage(filename, 0, 0, asMSGTYPE_ERROR, "Failed to resolve script path");
			return -1;
		}
		// Use the builder's own comparison rules and skip before reading again.
		// AddSectionFromMemory records the name before processing nested includes,
		// so repeated includes and cycles remain include-once.
		if (seen.count(Identity(section))) return 0;
		if (!rootsInitialized) InitializeRoots(section);

		FILE *file = OpenFile(section.c_str(), "rb");
		if( file == 0 ) return ReportFileError(section, "Failed to open script file '");
		++fileReads;
		// Classify the opened handle, not a path that may have been replaced.
		// Unknown or non-file streams retain load events but provide no digest.
		// Only report mode requests this probe; ordinary tools keep the original
		// file I/O path without extra metadata calls.
		bool regularFile = false;
		if( inspectFileType )
		{
#ifdef _WIN32
			HANDLE handle = reinterpret_cast<HANDLE>(_get_osfhandle(_fileno(file)));
			regularFile = GetFileType(handle) == FILE_TYPE_DISK;
#else
			struct stat info;
			regularFile = fstat(fileno(file), &info) == 0 && S_ISREG(info.st_mode);
#endif
		}

		std::string code;
		// Reserve from the opened handle; keep chunked reads and EOF/error checks
		// so a concurrent size change cannot truncate the actual loaded bytes.
#ifdef _WIN32
		LARGE_INTEGER sizeHint;
		HANDLE sizeHandle = reinterpret_cast<HANDLE>(_get_osfhandle(_fileno(file)));
		if (GetFileSizeEx(sizeHandle, &sizeHint) && sizeHint.QuadPart > 0)
			code.reserve(static_cast<size_t>((std::min)(sizeHint.QuadPart, LONGLONG(64 * 1024 * 1024))));
#else
		struct stat sizeHint;
		if (fstat(fileno(file), &sizeHint) == 0 && sizeHint.st_size > 0)
			code.reserve(static_cast<size_t>((std::min)(sizeHint.st_size, off_t(64 * 1024 * 1024))));
#endif
		char buffer[4096];
		size_t count;
		bool tooLarge = false;
		while( (count = std::fread(buffer, 1, sizeof(buffer), file)) != 0 )
		{
			if( code.size() > static_cast<size_t>((std::numeric_limits<int>::max)()) - count )
			{
				tooLarge = true;
				break;
			}
			code.append(buffer, count);
		}
		bool readFailed = std::ferror(file) != 0;
		std::fclose(file);
		if( tooLarge ) return ReportFileError(section, "Script file is too large '");
		if( readFailed ) return ReportFileError(section, "Failed to load script file '");

		// Report a successful byte read, not acceptance by preprocessing or the
		// compiler. This remains observable even if a nested include aborts.
		if( loadedCallback ) loadedCallback(section, code, regularFile, loadedParam);
		return AddSectionFromMemory(section.c_str(), code.c_str(), static_cast<unsigned int>(code.size()));
	}
	int AddSectionFromMemory(const char *sectionName, const char *code, unsigned length = 0, int lineOffset = 0)
	{
		const std::string name(sectionName);
		if (!seen.insert(Identity(name)).second) return 0;
		IncludeIfNotAlreadyIncluded(sectionName);
		if (++loadDepth > 128) { --loadDepth; return ReportFileError(name, "Import/include nesting exceeds 128 in '"); }
		size_t size = length ? length : std::strlen(code);
		if (preprocessor.canSkip(std::string_view(code, size)))
		{
			++preprocessor.stats.fastSections; preprocessor.stats.outputBytes += size; --loadDepth;
			return ProcessScriptSection(code, static_cast<unsigned>(size), sectionName, lineOffset) < 0 ? -1 : 1;
		}
		bool accepted = preprocessor.process(name, std::string(code, size), lineOffset,
			[this](const std::string &path, Preprocessor::DependencyKind kind, const Preprocessor::Origin &origin)
			{
				auto previous = dependencyKind; dependencyKind = kind;
				int result;
				if (includeCallback) result = includeCallback(path.c_str(), origin.section->name.c_str(), this, includeParam);
				else {
					std::string resolved = ResolveDependency(path.c_str(), origin.section->name.c_str());
					if (resolved.empty() && !resolutionError.empty()) {
						GetEngine()->WriteMessage(origin.section->name.c_str(), 0, 0, asMSGTYPE_ERROR, resolutionError.c_str());
						result = -1;
					} else result = AddSectionFromFile(resolved.c_str());
				}
				dependencyKind = previous;
				return result;
			}, [this](const std::string &text) { return pragmaCallback ? pragmaCallback(text, *this, pragmaParam) : -1; });
		--loadDepth;
		if (!accepted) return -1;
		const auto &output = preprocessor.output(name);
		return ProcessScriptSection(output.c_str(), static_cast<unsigned>(output.size()), sectionName, lineOffset) < 0 ? -1 : 1;
	}

private:
	Preprocessor preprocessor;
	std::unordered_set<std::string> seen;
	std::unordered_map<std::string, std::string> normalized, resolutions;
	std::vector<std::string> configuredPaths, searchPaths;
	std::string resolutionError;
	bool rootsInitialized = false;
	unsigned loadDepth = 0;
	uint64_t fileReads = 0;
	Preprocessor::DependencyKind dependencyKind = Preprocessor::Quoted;
	void (*diagnosticCallback)(const asSMessageInfo *, void *) = nullptr;
	void *diagnosticParam = nullptr;
	void MappedMessage(const asSMessageInfo *message)
	{
		asSMessageInfo mapped = *message;
		preprocessor.remap(mapped);
		diagnosticCallback(&mapped, diagnosticParam);
	}
	static std::string Identity(std::string path)
	{
#ifdef _WIN32
		for (char &c : path) if (c >= 'A' && c <= 'Z') c += 'a' - 'A';
#endif
		return path;
	}
	std::string Normalize(const std::string &path)
	{
		auto found = normalized.find(path);
		if (found != normalized.end()) return found->second;
		std::string result;
		if (!ScriptSectionPath(path.c_str(), result)) return "";
		return normalized.emplace(path, result).first->second;
	}
	static bool Exists(const std::string &path)
	{
#ifdef _WIN32
		std::wstring native;
		if (!ToWide(path.c_str(), native)) return false;
		DWORD attributes = GetFileAttributesW(native.c_str());
		return attributes != INVALID_FILE_ATTRIBUTES && !(attributes & FILE_ATTRIBUTE_DIRECTORY);
#else
		struct stat info;
		return stat(path.c_str(), &info) == 0 && !S_ISDIR(info.st_mode);
#endif
	}
	void InitializeRoots(const std::string &entry)
	{
		std::string directory = entry.substr(0, entry.find_last_of('/'));
		searchPaths = {directory};
		for (const auto &path : configuredPaths) searchPaths.push_back(Normalize(path));
		std::string environment;
#ifdef _WIN32
		DWORD length = GetEnvironmentVariableW(L"VAS_INCLUDE_PATH", nullptr, 0);
		if (length)
		{
			std::vector<wchar_t> buffer(length);
			DWORD count = GetEnvironmentVariableW(L"VAS_INCLUDE_PATH", buffer.data(), length);
			if (count && count < length) ToUtf8(buffer.data(), environment);
		}
		const char separator = ';';
#else
		if (const char *value = std::getenv("VAS_INCLUDE_PATH")) environment = value;
		const char separator = ':';
#endif
		for (size_t begin = 0; begin < environment.size();)
		{
			size_t end = environment.find(separator, begin);
			std::string path = environment.substr(begin, end - begin);
			if (!path.empty())
			{
				bool absolute = path[0] == '/' || path[0] == '\\' || path.find(':') != std::string::npos;
				searchPaths.push_back(Normalize(absolute ? path : directory + "/" + path));
			}
			if (end == std::string::npos) break; begin = end + 1;
		}
		rootsInitialized = true;
	}
	SectionLoadedCallback loadedCallback;
	void *loadedParam;
	bool inspectFileType;
	int ReportFileError(const std::string &section, const char *prefix)
	{
		std::string message = prefix + section + "'";
		GetEngine()->WriteMessage(section.c_str(), 0, 0, asMSGTYPE_ERROR, message.c_str());
		return -1;
	}
};
}

#endif
