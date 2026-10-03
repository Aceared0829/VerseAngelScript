#include "../../sdk/samples/common/vas_scriptbuilder.h"
#include "../../sdk/samples/common/vas_source_digest.h"
#include <cstdio>
#include <cstdlib>

static void Check(bool condition, const char *message)
{
	if( !condition )
	{
		std::fprintf(stderr, "%s\n", message);
		std::exit(1);
	}
}

static void Write(const std::string &path, const std::string &bytes)
{
	FILE *file = vas::OpenFile(path.c_str(), "wb");
	Check(file != 0, "Cannot open loader test source");
	Check(std::fwrite(bytes.data(), 1, bytes.size(), file) == bytes.size(), "Cannot write loader test source");
	Check(std::fclose(file) == 0, "Cannot close loader test source");
}

struct Observation
{
	std::string expected, observed, digest;
	unsigned int calls = 0;
};

static void Loaded(const std::string &section, const std::string &code, bool regularFile, void *param)
{
	Observation &observation = *static_cast<Observation *>(param);
	Check(regularFile, "Opened test source should be a regular file");
	++observation.calls;
	observation.observed = code;
	// Deterministically change the path at the existing load callback boundary,
	// before both digest calculation and preprocessing. A path reread would hash
	// and compile 99; the immutable loaded buffer must still hash/compile 7.
	Write(section, "int value() { return 99; }\n");
	observation.digest = vas::SourceDigestSha256(code);
}

int main(int argc, char **argv)
{
	if( argc == 3 && std::string(argv[1]) == "--nul-fixture" )
	{
		Write(argv[2], std::string("/* before\0after */\r\n", 20) + "void main() {}\n");
		return 0;
	}
	Check(argc == 2, "Expected a test source path");
	Check(vas::SourceDigestSha256("") == "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", "SHA-256 empty vector");
	Check(vas::SourceDigestSha256("abc") == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", "SHA-256 abc vector");
	Check(vas::SourceDigestSha256("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq") ==
		"248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1", "SHA-256 two-block vector");
	Check(vas::SourceDigestSha256(std::string(1000000, 'a')) ==
		"cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", "SHA-256 million-byte vector");
	Check(vas::SourceDigestSha256(std::string("a\0b", 3)) ==
		"59b271ae1bbcb1d31d41929817f4b16fb439eb4f31520b5ad1d5ce98920a7138", "SHA-256 embedded NUL vector");

	asIScriptEngine *engine = asCreateScriptEngine();
	Check(engine != 0, "Cannot create actual AngelScript engine");
	vas::ScriptBuilder builder;
	Observation observation;
	// Includes NUL in a comment and a real declaration after it. A C-string hash
	// or a length-losing handoff cannot pass both byte and execution assertions.
	observation.expected = std::string("/* before\0after */\r\n", 20) +
		"#if NEVER_DEFINED\ninvalid source removed by preprocessing\n#endif\nint value() { return 7; }\n";
	Write(argv[1], observation.expected);
	builder.SetSectionLoadedCallback(Loaded, &observation);
	Check(builder.StartNewModule(engine, "digest-test") >= 0, "Cannot start actual module");
	Check(builder.AddSectionFromFile(argv[1]) == 1, "Cannot load actual source");
	Check(observation.calls == 1 && observation.observed == observation.expected, "Callback lost original loaded bytes");
	// Independently calculated from this 111-byte fixture using Python hashlib.
	Check(observation.expected.size() == 111 && observation.digest ==
		"136a2fc3b3f676a3ebcefcc17672351d71c647c27faa9cffd0c3fc2785bdf428", "Digest changed after path overwrite");
	Check(builder.BuildModule() >= 0, "Actual source with embedded NUL/comment did not compile");
	asIScriptContext *context = engine->CreateContext();
	Check(context != 0, "Cannot create execution context");
	Check(context->Prepare(builder.GetModule()->GetFunctionByDecl("int value()")) >= 0, "Declaration after NUL was lost");
	Check(context->Execute() == asEXECUTION_FINISHED && context->GetReturnDWord() == 7,
		"Compiled bytes did not match the original callback buffer");
	context->Release();
	// Include-once identity must not reload the overwritten path.
	Check(builder.AddSectionFromFile(argv[1]) == 0 && observation.calls == 1, "Repeated identity was loaded again");
	// In-memory sections do not claim successful native-file loads.
	Check(builder.AddSectionFromMemory("memory", "void inMemory() {}") >= 0 && observation.calls == 1,
		"In-memory section incorrectly emitted a file load");
	engine->ShutDownAndRelease();
	return 0;
}
