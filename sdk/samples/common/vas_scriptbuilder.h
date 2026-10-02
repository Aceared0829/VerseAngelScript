#ifndef VAS_SAMPLE_SCRIPTBUILDER_H
#define VAS_SAMPLE_SCRIPTBUILDER_H

#include "../../add_on/scriptbuilder/scriptbuilder.h"
#include "vas_paths.h"
#include <limits>

namespace vas
{
// Keep VAS file I/O at the native boundary on every toolchain. In particular,
// the upstream file loader uses narrow fopen on MinGW, despite UTF-8 sections.
class ScriptBuilder : public CScriptBuilder
{
public:
	int AddSectionFromFile(const char *filename)
	{
		std::string section;
		if( !AbsolutePath(filename, section) )
		{
			GetEngine()->WriteMessage(filename, 0, 0, asMSGTYPE_ERROR, "Failed to resolve script path");
			return -1;
		}

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

		// Use the builder's own comparison rules and skip before reading again.
		// AddSectionFromMemory records the name before processing nested includes,
		// so repeated includes and cycles remain include-once.
		if( includedScripts.find(section) != includedScripts.end() ) return 0;

		FILE *file = OpenFile(section.c_str(), "rb");
		if( file == 0 ) return ReportFileError(section, "Failed to open script file '");

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

		return AddSectionFromMemory(section.c_str(), code.c_str(), static_cast<unsigned int>(code.size()));
	}

private:
	int ReportFileError(const std::string &section, const char *prefix)
	{
		std::string message = prefix + section + "'";
		GetEngine()->WriteMessage(section.c_str(), 0, 0, asMSGTYPE_ERROR, message.c_str());
		return -1;
	}
};
}

#endif
