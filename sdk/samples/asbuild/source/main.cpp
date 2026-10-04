#include <iostream>  // cout
#include <assert.h>  // assert()
#include <string.h>  // strstr()
#include <angelscript.h>
#include "../../../add_on/scriptbuilder/scriptbuilder.h"
#include "../../../add_on/scripthelper/scripthelper.h"
#include <stdio.h>
#include "../../common/vas_console.h"
#include <stdlib.h>
#include <sstream>
#include "../../common/vas_scriptbuilder.h"
#include "../../common/vas_source_digest.h"
#include "../../common/vas_build_report.h"
#include "vas_project.h"
#include "../../common/vas_format.h"
#if defined(_MSC_VER)
#include <crtdbg.h>
#endif

using namespace std;

// Function prototypes
int ConfigureEngine(asIScriptEngine *engine, const char *configFile);
int CompileScript(asIScriptEngine *engine, const char *scriptFile, vas::BuildReport *report, vector<string> &sections);
int SaveBytecode(asIScriptEngine *engine, const char *outputFile, bool reportMode,
	const vas::Project *project, const vas::CompilationUnit *unit, const vector<string> &sections);
static bool IsVasScriptFile(const char *filename);
static int ReportInvalidVasScriptExtension(asIScriptEngine *engine, const char *filename, const char *role);
static int VasIncludeCallback(const char *include, const char *from, CScriptBuilder *builder, void *userParam);

void MessageCallback(const asSMessageInfo *msg, void *param)
{
	vas::BuildReport *report = static_cast<vas::BuildReport *>(param);
	if( report )
	{
		const char *severity = msg->type == asMSGTYPE_WARNING ? "warning" :
			msg->type == asMSGTYPE_INFORMATION ? "information" : "error";
		report->Diagnostic(severity, msg->section, msg->row, msg->col, msg->message);
		return;
	}
	const char *type = "ERR ";
	if( msg->type == asMSGTYPE_WARNING ) 
		type = "WARN";
	else if( msg->type == asMSGTYPE_INFORMATION ) 
		type = "INFO";

	FILE *stream = msg->type == asMSGTYPE_INFORMATION ? stdout : stderr;
	vas::WriteDiagnostic(stream, msg->section, msg->row, msg->col, type, msg->message);
}

static int Run(int argc, char **argv);

#ifdef _WIN32
int wmain(int argc, wchar_t **argv)
{
	return vas::RunWithUtf8Arguments(argc, argv, Run);
}
#else
int main(int argc, char **argv)
{
	return Run(argc, argv);
}
#endif

static int Run(int argc, char **argv)
{
#if defined(_MSC_VER)
	// Turn on memory leak detection (use _CrtSetBreakAlloc to break at specific allocation)
	_CrtSetDbgFlag(_CRTDBG_LEAK_CHECK_DF|_CRTDBG_ALLOC_MEM_DF);
	_CrtSetReportMode(_CRT_ASSERT,_CRTDBG_MODE_FILE);
	_CrtSetReportFile(_CRT_ASSERT,_CRTDBG_FILE_STDERR);

	//_CrtSetBreakAlloc(6150);
#endif

	if( argc > 1 && string(argv[1]) == "--describe-project=json" )
		return vas::DescribeProject(argc, argv);

	const bool reportMode = argc > 1 && string(argv[1]) == "--report=jsonl";
	const bool projectMode = reportMode && argc > 2 &&
		(string(argv[2]) == "--project" || string(argv[2]) == "--unit");
	vas::Project project;
	vas::ProjectError projectError;
	const vas::CompilationUnit *unit = 0;
	string requestedUnit;
	bool projectValid = false;
	string config, entry, output;
	vas::BuildReport reportStorage;
	vas::BuildReport *report = reportMode ? &reportStorage : 0;
	if( report )
	{
		if( !report->Open() ) return -1;
		if( projectMode )
		{
			if( argc == 6 && string(argv[2]) == "--project" && string(argv[4]) == "--unit" && argv[5][0] )
			{
				requestedUnit = argv[5];
				projectValid = vas::ReadProject(argv[3], project, projectError);
				if( projectValid )
				{
					for( const vas::CompilationUnit &candidate : project.units )
						if( candidate.id == requestedUnit ) unit = &candidate;
					if( !unit )
					{
						projectError.code = "project_unit";
						projectError.message = "Unknown compilation unit: " + requestedUnit;
						projectError.section = project.path;
					}
					else { config = unit->config; entry = unit->entry; output = unit->output; }
				}
			}
			else projectError.message = "Usage: vasbuild --report=jsonl --project <manifest> --unit <id> (explicit unit required)";
		}
		vas::BuildReportRecord start = report->Record("start");
		start.Text("compiler", "vasbuild");
		start.Text("compilerVersion", asGetLibraryVersion());
		start.Text("positionEncoding", "utf-8-bytes");
		start.Number("positionBase", 1);
		string cwd;
		if( vas::ReadCurrentDirectory(cwd) ) start.Text("cwd", cwd);
		else start.Null("cwd");
		const char *names[] = {"config", "entry", "output"};
		const string selected[] = {config, entry, output};
		for( int i = 0; i < 3; ++i )
		{
			string path;
			const char *argument = projectMode ? selected[i].c_str() : argc > i + 2 ? argv[i + 2] : "";
			bool resolved;
			if( projectMode ) { path = selected[i]; resolved = !path.empty(); }
			else resolved = i == 1 ? vas::ScriptSectionPath(argument, path) : vas::AbsolutePath(argument, path);
			if( resolved ) start.Text(names[i], path);
			else start.Null(names[i]);
		}
		if( projectMode )
		{
			if( project.path.empty() ) start.Null("project"); else start.Text("project", project.path);
			if( projectValid && !project.legacy ) start.Number("projectSchemaVersion", 1); else start.Null("projectSchemaVersion");
			if( requestedUnit.empty() ) start.Null("unit"); else start.Text("unit", requestedUnit);
			start.Boolean("legacyProject", project.legacy);
		}
		if( !report->Write(start) ) return -1;
		if( projectMode && (!projectValid || !unit) )
		{
			report->Diagnostic("error", projectError.section.c_str(), projectError.row, projectError.column, projectError.message.c_str());
			report->Result(false);
			return -1;
		}
		if( !projectMode && argc != 5 )
		{
			report->Diagnostic("error", "", 0, 0,
				"Usage: vasbuild --report=jsonl <config file> <script.vas> <output>");
			report->Result(false);
			return -1;
		}
		if( projectMode && project.legacy ) report->Diagnostic("warning", project.path.c_str(), 0, 0, vas::LegacyProjectWarning());
		if( !projectMode ) { --argc; ++argv; }
	}

	if( !projectMode )
	{
		if( argc < 4 )
		{
			cout << "Usage: " << endl;
			cout << "vasbuild <config file> <script.vas> <output>" << endl;
			cout << "vasbuild --describe-project=json <manifest>" << endl;
			cout << "vasbuild --report=jsonl --project <manifest> --unit <id>" << endl;
			cout << " <config file>  is the file with the application interface" << endl;
			cout << " <script.vas>  is the VAS script file that should be compiled" << endl;
			cout << " <output>       is the name that the compiled script will be saved as" << endl;
			return -1;
		}
		config = argv[1]; entry = argv[2]; output = argv[3];
	}
	vector<string> sections;

	if( report ) report->phase = "engine";
	asIScriptEngine *engine = asCreateScriptEngine();
	if( engine == 0 )
	{
		if( report )
		{
			report->Diagnostic("error", "", 0, 0, "Failed to create script engine.");
			report->Result(false);
		}
		else cout << "Failed to create script engine." << endl;
		return -1;
	}
	engine->SetMessageCallback(asFUNCTION(MessageCallback), report, asCALL_CDECL);

	bool success = false;
	do
	{
		// Preserve the legacy extension diagnostic before reading config.
		if( report ) report->phase = "arguments";
		if( !IsVasScriptFile(entry.c_str()) )
		{
			ReportInvalidVasScriptExtension(engine, entry.c_str(), "entry script");
			break;
		}
		if( report ) report->phase = "config";
		if( projectMode && !vas::ValidateProjectInputs(project, *unit, projectError) )
		{
			if( projectError.field == "/entry" ) report->phase = "load";
			report->Diagnostic("error", projectError.section.c_str(), 0, 0, projectError.message.c_str());
			break;
		}
		if( ConfigureEngine(engine, config.c_str()) < 0 ) break;
		if( report && report->Failed() ) break;
		if( CompileScript(engine, entry.c_str(), report, sections) < 0 ) break;
		if( report && report->Failed() ) break;
		if( report ) report->phase = "output";
		if( projectMode && !vas::PrepareProjectOutput(project, *unit, sections, projectError) )
		{
			report->Diagnostic("error", projectError.section.c_str(), 0, 0, projectError.message.c_str());
			break;
		}
		if( report && vas::InvalidReportOutput(output.c_str()) )
		{
			engine->WriteMessage(output.c_str(), 0, 0, asMSGTYPE_ERROR, "Report bytecode output must be a regular file distinct from stdout");
			break;
		}
		if( SaveBytecode(engine, output.c_str(), report != 0, projectMode ? &project : 0, unit, sections) < 0 ) break;
		success = true;
	} while( false );

	// Release on all ordinary paths, before the terminal record.
	engine->ShutDownAndRelease();
	if( report && !report->Result(success) ) return -1;
	return success ? 0 : -1;
}

#ifdef AS_CAN_USE_CPP11
// The string factory doesn't need to keep a specific order in the
// cache, so the unordered_map is faster than the ordinary map
#include <unordered_map>  // std::unordered_map
BEGIN_AS_NAMESPACE
typedef unordered_map<string, int> map_t;
END_AS_NAMESPACE
#else
#include <map>      // std::map
BEGIN_AS_NAMESPACE
typedef map<string, int> map_t;
END_AS_NAMESPACE
#endif

// Default string factory. Removes duplicate string constants
// This same implementation is provided in the scriptstdstring add-on
class CStdStringFactory : public asIStringFactory
{
public:
	CStdStringFactory() {}
	~CStdStringFactory()
	{
		// The script engine must release each string 
		// constant that it has requested
		assert(stringCache.size() == 0);
	}

	const void *GetStringConstant(const char *data, asUINT length)
	{
		string str(data, length);
		map_t::iterator it = stringCache.find(str);
		if (it != stringCache.end())
			it->second++;
		else
			it = stringCache.insert(map_t::value_type(str, 1)).first;

		return reinterpret_cast<const void*>(&it->first);
	}

	int  ReleaseStringConstant(const void *str)
	{
		if (str == 0)
			return asERROR;

		map_t::iterator it = stringCache.find(*reinterpret_cast<const string*>(str));
		if (it == stringCache.end())
			return asERROR;

		it->second--;
		if (it->second == 0)
			stringCache.erase(it);
		return asSUCCESS;
	}

	int  GetRawStringData(const void *str, char *data, asUINT *length) const
	{
		if (str == 0)
			return asERROR;

		if (length)
			*length = (asUINT)reinterpret_cast<const string*>(str)->length();

		if (data)
			memcpy(data, reinterpret_cast<const string*>(str)->c_str(), reinterpret_cast<const string*>(str)->length());

		return asSUCCESS;
	}

	// TODO: Make sure the access to the string cache is thread safe
	map_t stringCache;
};

CStdStringFactory stringFactory;

// This function will register the application interface, 
// based on information read from a configuration file. 
int ConfigureEngine(asIScriptEngine *engine, const char *configFile)
{
	int r;

	FILE *config = vas::OpenFile(configFile, "r");
	if( config == 0 )
	{
		// Write a message to the engine's message callback
		string msg = "Failed to open config file in path: '" + vas::CurrentDirectory() + "'";
		engine->WriteMessage(configFile, 0, 0, asMSGTYPE_ERROR, msg.c_str());
		return -1;
	}

	// Preserve text-mode configuration parsing while opening the native path
	// through the wide Windows CRT. The parser consumes a standard C++ stream.
	stringstream strm;
	char buffer[4096];
	size_t count;
	while( (count = fread(buffer, 1, sizeof(buffer), config)) != 0 )
		strm.write(buffer, static_cast<streamsize>(count));
	bool readFailed = ferror(config) != 0;
	fclose(config);
	if( readFailed )
	{
		engine->WriteMessage(configFile, 0, 0, asMSGTYPE_ERROR, "Failed to read config file");
		return -1;
	}

	// Configure the engine with the information from the file
	r = ConfigEngineFromStream(engine, strm, configFile, &stringFactory, vas::ValidateFormat, engine);
	if( r < 0 )
	{
		engine->WriteMessage(configFile, 0, 0, asMSGTYPE_ERROR, "Configuration failed");
		return -1;
	}


	engine->WriteMessage(configFile, 0, 0, asMSGTYPE_INFORMATION, "Configuration successfully registered");
	
	return 0;
}

struct IncludeContext
{
	asIScriptEngine *engine;
	vas::BuildReport *report;
	vector<string> *sections;
};

static void SectionLoaded(const string &section, const string &code, bool regularFile, void *param)
{
	IncludeContext *context = static_cast<IncludeContext *>(param);
	context->sections->push_back(section);
	vas::BuildReport *report = context->report;
	if( !report ) return;
	vas::BuildReportRecord record = report->Record("section_loaded");
	record.Text("section", section);
	record.Boolean("utf8Valid", vas::IsValidUtf8(code));
	if( regularFile )
	{
		record.Number("sourceDigestVersion", 1);
		record.Text("sourceDigestAlgorithm", "sha256");
		record.Number("sourceByteLength", static_cast<std::int64_t>(code.size()));
		record.Text("sourceDigest", vas::SourceDigestSha256(code));
	}
	report->Write(record);
}

int CompileScript(asIScriptEngine *engine, const char *scriptFile, vas::BuildReport *report, vector<string> &sections)
{
	int r;
	if( !IsVasScriptFile(scriptFile) )
		return ReportInvalidVasScriptExtension(engine, scriptFile, "entry script");

	if( report ) report->phase = "load";
	vas::ScriptBuilder builder;
	IncludeContext context = {engine, report, &sections};
	builder.SetSectionLoadedCallback(SectionLoaded, &context, report != 0);
	r = builder.StartNewModule(engine, "build");
	if( r < 0 ) return -1;
	builder.SetIncludeCallback(VasIncludeCallback, &context);
	builder.SetMappedMessageCallback(MessageCallback, report);

	r = builder.AddSectionFromFile(scriptFile);
	if( r < 0 ) return -1;

	if( report )
	{
		report->dependenciesComplete = true;
		report->phase = "compile";
	}
	r = builder.BuildModule();
	if( r < 0 )
	{
		engine->WriteMessage(scriptFile, 0, 0, asMSGTYPE_ERROR, "Script failed to build");
		return -1;
	}

	engine->WriteMessage(scriptFile, 0, 0, asMSGTYPE_INFORMATION, "Script successfully built");

	return 0;
}

// VAS source files use a lower-case .vas extension on every supported platform.
// Keep this policy in the VAS builder rather than CScriptBuilder: embedders may
// compile in-memory sections or use their own virtual file system naming scheme.
static bool IsVasScriptFile(const char *filename)
{
	if( filename == 0 )
		return false;

	string path(filename);
	string::size_type slash = path.find_last_of("/\\");
	string::size_type dot = path.find_last_of('.');
	return dot != string::npos &&
		(slash == string::npos || dot > slash) &&
		path.compare(dot, 4, ".vas") == 0;
}

static int ReportInvalidVasScriptExtension(asIScriptEngine *engine, const char *filename, const char *role)
{
	string path = filename ? filename : "";
	string message = "VAS source files must use the '.vas' extension";
	if( role && role[0] )
		message += string(" for the ") + role;
	message += ". Received '" + path + "'.";
	engine->WriteMessage(path.c_str(), 0, 0, asMSGTYPE_ERROR, message.c_str());
	return asERROR;
}

static int VasIncludeCallback(const char *include, const char *from, CScriptBuilder *builder, void *userParam)
{
	IncludeContext *context = static_cast<IncludeContext *>(userParam);
	vas::BuildReport *report = context->report;
	string resolvedInclude = static_cast<vas::ScriptBuilder *>(builder)->ResolveDependency(include, from);
	std::uint64_t attempt = 0;
	if( report )
	{
		vas::BuildReportRecord record = report->Record("include_attempt");
		attempt = report->Sequence();
		record.Text("from", from ? from : "");
		record.Text("requested", include ? include : "");
		string section;
		if( vas::ScriptSectionPath(resolvedInclude.c_str(), section) ) record.Text("resolved", section);
		else record.Null("resolved");
		report->Write(record);
	}

	bool resolved = !resolvedInclude.empty();
	if (!resolved) context->engine->WriteMessage(from, 0, 0, asMSGTYPE_ERROR, static_cast<vas::ScriptBuilder *>(builder)->GetResolutionError().c_str());
	bool validExtension = resolved && IsVasScriptFile(resolvedInclude.c_str());
	int result = !resolved ? -1 : validExtension ?
		static_cast<vas::ScriptBuilder *>(builder)->AddSectionFromFile(resolvedInclude.c_str()) :
		ReportInvalidVasScriptExtension(context->engine, resolvedInclude.c_str(), "included script");
	if( report )
	{
		vas::BuildReportRecord record = report->Record("include_result");
		record.Number("attemptSeq", attempt);
		record.Text("status", !resolved ? "failed" : !validExtension ? "rejected" : result < 0 ? "failed" : result == 0 ? "skipped" : "loaded");
		report->Write(record);
	}
	return result;
}

class CBytecodeStream : public asIBinaryStream
{
public:
	CBytecodeStream() : f(0), failed(false) {}
	~CBytecodeStream() { if( f ) fclose(f); }

	int Open(const char *filename, bool reportMode, const vas::Project *project,
		const vas::CompilationUnit *unit, const vector<string> &sections)
	{
		if( f ) return -1;
		f = vas::OpenFile(filename, reportMode ? "ab" : "wb");
		if( f == 0 ) return -1;
		if( (project && !vas::ValidateProjectOutputHandle(f, *project, *unit, sections)) ||
			(reportMode && !vas::PrepareReportOutput(f)) )
		{
			fclose(f);
			f = 0;
			return -1;
		}
		return 0;
	}
	int Write(const void *ptr, asUINT size) 
	{
		if( f == 0 || failed ) return -1;
		if( size && fwrite(ptr, 1, size, f) != size ) failed = true;
		return failed ? -1 : 0;
	}
	int Close()
	{
		if( !f ) return -1;
		// Buffered writes may not fail until fclose flushes the stream. Retain
		// earlier errors too: the engine need not propagate each Write result.
		if( fclose(f) != 0 ) failed = true;
		f = 0;
		return failed ? -1 : 0;
	}
	int Read(void *, asUINT) { return -1; }

protected:
	FILE *f;
	bool failed;
};

int SaveBytecode(asIScriptEngine *engine, const char *outputFile, bool reportMode,
	const vas::Project *project, const vas::CompilationUnit *unit, const vector<string> &sections)
{
	CBytecodeStream stream;
	int r = stream.Open(outputFile, reportMode, project, unit, sections);
	if( r < 0 )
	{
		engine->WriteMessage(outputFile, 0, 0, asMSGTYPE_ERROR, "Failed to open output file for writing");
		return -1;
	}

	asIScriptModule *mod = engine->GetModule("build");
	if( mod == 0 )
	{
		engine->WriteMessage(outputFile, 0, 0, asMSGTYPE_ERROR, "Failed to retrieve the compiled bytecode");
		return -1;
	}

	r = mod->SaveByteCode(&stream);
	int closeResult = stream.Close();
	if( r < 0 || closeResult < 0 )
	{
		engine->WriteMessage(outputFile, 0, 0, asMSGTYPE_ERROR, "Failed to write the bytecode");
		return -1;
	}

	engine->WriteMessage(outputFile, 0, 0, asMSGTYPE_INFORMATION, "Bytecode successfully saved");

	return 0;
}
