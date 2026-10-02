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
#if defined(_MSC_VER)
#include <crtdbg.h>
#endif

using namespace std;

// Function prototypes
int ConfigureEngine(asIScriptEngine *engine, const char *configFile);
int CompileScript(asIScriptEngine *engine, const char *scriptFile);
int SaveBytecode(asIScriptEngine *engine, const char *outputFile);
static bool IsVasScriptFile(const char *filename);
static int ReportInvalidVasScriptExtension(asIScriptEngine *engine, const char *filename, const char *role);
static string ResolveIncludePath(const char *include, const char *from);
static int VasIncludeCallback(const char *include, const char *from, CScriptBuilder *builder, void *userParam);

void MessageCallback(const asSMessageInfo *msg, void *param)
{
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

	int r;

	if( argc < 4 )
	{
		cout << "Usage: " << endl;
		cout << "vasbuild <config file> <script.vas> <output>" << endl;
		cout << " <config file>  is the file with the application interface" << endl;
		cout << " <script.vas>  is the VAS script file that should be compiled" << endl;
		cout << " <output>       is the name that the compiled script will be saved as" << endl;
		return -1;
	}

	// Create the script engine
	asIScriptEngine *engine = asCreateScriptEngine();
	if( engine == 0 )
	{
		cout << "Failed to create script engine." << endl;
		return -1;
	}

	// The script compiler will send any compiler messages to the callback
	engine->SetMessageCallback(asFUNCTION(MessageCallback), 0, asCALL_CDECL);

	// Reject a legacy extension before attempting to parse the configuration
	// file, so callers always receive the VAS migration diagnostic first.
	if( !IsVasScriptFile(argv[2]) )
	{
		ReportInvalidVasScriptExtension(engine, argv[2], "entry script");
		engine->ShutDownAndRelease();
		return -1;
	}

	// Configure the script engine with all the functions, 
	// and variables that the script should be able to use.
	r = ConfigureEngine(engine, argv[1]);
	if( r < 0 ) return -1;
	
	// Compile the script code
	r = CompileScript(engine, argv[2]);
	if( r < 0 ) return -1;

	// Save the bytecode
	r = SaveBytecode(engine, argv[3]);
	if( r < 0 ) return -1;

	// Shut down the engine
	engine->ShutDownAndRelease();

	return 0;
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
	r = ConfigEngineFromStream(engine, strm, configFile, &stringFactory);
	if( r < 0 )
	{
		engine->WriteMessage(configFile, 0, 0, asMSGTYPE_ERROR, "Configuration failed");
		return -1;
	}


	engine->WriteMessage(configFile, 0, 0, asMSGTYPE_INFORMATION, "Configuration successfully registered");
	
	return 0;
}

int CompileScript(asIScriptEngine *engine, const char *scriptFile)
{
	int r;
	if( !IsVasScriptFile(scriptFile) )
		return ReportInvalidVasScriptExtension(engine, scriptFile, "entry script");

	vas::ScriptBuilder builder;
	r = builder.StartNewModule(engine, "build");
	if( r < 0 ) return -1;
	builder.SetIncludeCallback(VasIncludeCallback, engine);

	r = builder.AddSectionFromFile(scriptFile);
	if( r < 0 ) return -1;

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

static string ResolveIncludePath(const char *include, const char *from)
{
	string includePath = include ? include : "";
	if( includePath.find_first_of("/\\") != 0 && includePath.find_first_of(":") == string::npos )
	{
		string sourcePath = from ? from : "";
		string::size_type slash = sourcePath.find_last_of("/\\");
		if( slash != string::npos )
			sourcePath.resize(slash + 1);
		else
			sourcePath = "";

		return sourcePath + includePath;
	}

	return includePath;
}

static int VasIncludeCallback(const char *include, const char *from, CScriptBuilder *builder, void *userParam)
{
	asIScriptEngine *engine = reinterpret_cast<asIScriptEngine *>(userParam);
	string resolvedInclude = ResolveIncludePath(include, from);
	if( !IsVasScriptFile(resolvedInclude.c_str()) )
		return ReportInvalidVasScriptExtension(engine, resolvedInclude.c_str(), "included script");

	// This callback is only installed on the tool's native-file builder.
	return static_cast<vas::ScriptBuilder *>(builder)->AddSectionFromFile(resolvedInclude.c_str());
}

class CBytecodeStream : public asIBinaryStream
{
public:
	CBytecodeStream() : f(0), failed(false) {}
	~CBytecodeStream() { if( f ) fclose(f); }

	int Open(const char *filename)
	{
		if( f ) return -1;
		f = vas::OpenFile(filename, "wb");
		if( f == 0 ) return -1;
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

int SaveBytecode(asIScriptEngine *engine, const char *outputFile)
{
	CBytecodeStream stream;
	int r = stream.Open(outputFile);
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
