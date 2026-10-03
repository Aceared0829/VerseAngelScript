#ifndef VAS_SAMPLE_SCRIPTBUILDER_H
#define VAS_SAMPLE_SCRIPTBUILDER_H

#include "../../add_on/scriptbuilder/scriptbuilder.h"
#include "vas_paths.h"
#include <limits>
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
	ScriptBuilder() : loadedCallback(0), loadedParam(0) {}
	void SetSectionLoadedCallback(SectionLoadedCallback callback, void *param)
	{
		loadedCallback = callback;
		loadedParam = param;
	}

	int AddSectionFromFile(const char *filename)
	{
		std::string section;
		if( !ScriptSectionPath(filename, section) )
		{
			GetEngine()->WriteMessage(filename, 0, 0, asMSGTYPE_ERROR, "Failed to resolve script path");
			return -1;
		}
		// Use the builder's own comparison rules and skip before reading again.
		// AddSectionFromMemory records the name before processing nested includes,
		// so repeated includes and cycles remain include-once.
		if( includedScripts.find(section) != includedScripts.end() ) return 0;

		FILE *file = OpenFile(section.c_str(), "rb");
		if( file == 0 ) return ReportFileError(section, "Failed to open script file '");
		// Classify the opened handle, not a path that may have been replaced.
		// Unknown or non-file streams retain load events but provide no digest.
#ifdef _WIN32
		HANDLE handle = reinterpret_cast<HANDLE>(_get_osfhandle(_fileno(file)));
		bool regularFile = GetFileType(handle) == FILE_TYPE_DISK;
#else
		struct stat info;
		bool regularFile = fstat(fileno(file), &info) == 0 && S_ISREG(info.st_mode);
#endif

		std::string code;
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

private:
	SectionLoadedCallback loadedCallback;
	void *loadedParam;
	int ReportFileError(const std::string &section, const char *prefix)
	{
		std::string message = prefix + section + "'";
		GetEngine()->WriteMessage(section.c_str(), 0, 0, asMSGTYPE_ERROR, message.c_str());
		return -1;
	}
};
}

#endif
