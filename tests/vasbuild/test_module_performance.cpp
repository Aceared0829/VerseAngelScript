#include "../../sdk/samples/common/vas_scriptbuilder.h"
#include <chrono>
#include <cstdlib>
#include <iostream>

static void check(bool value, const char *message) {
    if (!value) { std::cerr << message << '\n'; std::exit(1); }
}
static void message(const asSMessageInfo *info, void *) {
    if (info->type == asMSGTYPE_ERROR) std::cerr << info->section << ':' << info->row << ':' << info->col << ' ' << info->message << '\n';
}
static void write(const std::string &path, const std::string &text) {
    FILE *file = vas::OpenFile(path.c_str(), "wb"); check(file != nullptr, "Cannot create benchmark fixture");
    check(std::fwrite(text.data(), 1, text.size(), file) == text.size(), "Cannot write benchmark fixture");
    check(std::fclose(file) == 0, "Cannot close benchmark fixture");
}
static int oldInclude(const char *name, const char *from, CScriptBuilder *builder, void *) {
    std::string parent(from); parent.resize(parent.find_last_of("/\\") + 1);
    return builder->AddSectionFromFile((parent + name).c_str());
}
static int run(asIScriptEngine *engine, asIScriptModule *module) {
    auto *context = engine->CreateContext();
    check(context->Prepare(module->GetFunctionByDecl("int main()")) >= 0, "Cannot prepare benchmark");
    check(context->Execute() == asEXECUTION_FINISHED, "Cannot execute benchmark");
    int result = int(context->GetReturnDWord()); context->Release(); return result;
}
static void benchmark(const char *label, const std::string &file, bool modern, int expected, uint64_t reads) {
    std::vector<int64_t> loads, compiles;
    uint64_t tokens = 0, expansions = 0, fastSections = 0;
    // One warm-up and three runs: OS cache is warm for both builders. No fragile
    // timing threshold in CI; semantic, read-count and reset checks are gates.
    for (int iteration = 0; iteration < 4; ++iteration) {
        auto *engine = asCreateScriptEngine();
        engine->SetMessageCallback(asFUNCTION(message), nullptr, asCALL_CDECL);
        auto begin = std::chrono::steady_clock::now();
        if (modern) {
            vas::ScriptBuilder builder;
            check(builder.StartNewModule(engine, "bench") >= 0, "Cannot start modern builder");
            builder.SetMappedMessageCallback(message, nullptr);
            check(builder.AddSectionFromFile(file.c_str()) > 0, "Modern preprocessing failed");
            auto loaded = std::chrono::steady_clock::now();
            check(builder.BuildModule() >= 0, "Modern compilation failed");
            auto compiled = std::chrono::steady_clock::now();
            check(run(engine, builder.GetModule()) == expected, "Modern output changed");
            check(builder.GetFileReadCount() == reads, "Repeated import reread a source file");
            tokens = builder.GetPreprocessingStatistics().tokens;
            expansions = builder.GetPreprocessingStatistics().expansions;
            fastSections = builder.GetPreprocessingStatistics().fastSections;
            if (iteration) {
                loads.push_back(std::chrono::duration_cast<std::chrono::microseconds>(loaded - begin).count());
                compiles.push_back(std::chrono::duration_cast<std::chrono::microseconds>(compiled - loaded).count());
            }
        } else {
            CScriptBuilder builder;
            check(builder.StartNewModule(engine, "bench") >= 0, "Cannot start reference builder");
            builder.SetIncludeCallback(oldInclude, nullptr);
            check(builder.AddSectionFromFile(file.c_str()) > 0, "Reference preprocessing failed");
            auto loaded = std::chrono::steady_clock::now();
            check(builder.BuildModule() >= 0, "Reference compilation failed");
            auto compiled = std::chrono::steady_clock::now();
            check(run(engine, builder.GetModule()) == expected, "Reference output changed");
            if (iteration) {
                loads.push_back(std::chrono::duration_cast<std::chrono::microseconds>(loaded - begin).count());
                compiles.push_back(std::chrono::duration_cast<std::chrono::microseconds>(compiled - loaded).count());
            }
        }
        engine->ShutDownAndRelease();
    }
    std::sort(loads.begin(), loads.end()); std::sort(compiles.begin(), compiles.end());
    std::cout << label << ": load/preprocess median=" << loads[1] << " us, compile median=" << compiles[1] << " us";
    if (modern) std::cout << ", actual file reads=" << reads << ", lexed tokens=" << tokens
                          << ", expansions=" << expansions << ", fast sections=" << fastSections;
    std::cout << '\n';
}
int main(int argc, char **argv) {
    check(argc == 2, "Expected benchmark directory");
    std::string root(argv[1]);
    const std::string file = root + "/bench.vas", dependency = root + "/math.vas";
    std::string plain;
    for (int i = 0; i < 2000; ++i) plain += "int value" + std::to_string(i) + "(){return " + std::to_string(i) + ";}\n";
    plain += "int main(){return value42();}\n";
    write(file, plain);
    benchmark("reference plain source (2000 functions)", file, false, 42, 1);
    benchmark("modern plain source (fast path)", file, true, 42, 1);
    write(dependency, "int add(int a,int b){return a+b;}\n");
    std::string quoted, mixed;
    for (int i = 0; i < 10000; ++i) {
        quoted += "#include \"math.vas\"\n";
        mixed += i % 3 == 0 ? "import math;\n" : i % 3 == 1 ? "#include \"math.vas\"\n" : "#include <math.vas>\n";
    }
    const std::string entry = "int main(){return add(20,22);}\n";
    write(file, quoted + entry);
    benchmark("reference repeated includes (10000)", file, false, 42, 2);
    benchmark("modern repeated includes (10000)", file, true, 42, 2);
    write(file, mixed + entry);
    benchmark("modern mixed imports/includes (10000)", file, true, 42, 2);
    std::string expanded = "int main(){int sum=0;\n", macro = "#define SUM(a,b) ((a)+(b))\nint main(){int sum=0;\n";
    for (int i = 0; i < 10000; ++i) { expanded += "sum += ((1)+(2));\n"; macro += "sum += SUM(1,2);\n"; }
    expanded += "return sum;}\n"; macro += "return sum;}\n";
    write(file, expanded);
    benchmark("modern already expanded source (10000 expressions)", file, true, 30000, 1);
    write(file, macro);
    benchmark("modern function macros (10000 calls)", file, true, 30000, 1);
    // Reusing a builder must clear the macro and positive resolution caches.
    auto *engine = asCreateScriptEngine();
    engine->SetMessageCallback(asFUNCTION(message), nullptr, asCALL_CDECL);
    {
        vas::ScriptBuilder builder;
        write(dependency, "#define VALUE 7\n"); write(file, "import math;\nint main(){return VALUE;}\n");
        for (int expected : {7, 9}) {
            write(dependency, "#define VALUE " + std::to_string(expected) + "\n");
            check(builder.StartNewModule(engine, "reset") >= 0, "Cannot reset builder");
            builder.SetMappedMessageCallback(message, nullptr);
            check(builder.AddSectionFromFile(file.c_str()) > 0 && builder.BuildModule() >= 0, "Rebuild failed");
            check(run(engine, builder.GetModule()) == expected, "Rebuild used a stale macro/source cache");
            check(builder.GetFileReadCount() == 2, "Rebuild did not reread both inputs");
        }
        std::vector<asDWORD> referenceCode;
        for (bool useMacro : {false, true}) {
            check(builder.StartNewModule(engine, "bytecode") >= 0, "Cannot start bytecode check");
            std::string source = useMacro ? "#define SUM(a,b) ((a)+(b))\nint main(){int x=7;return SUM(x,3);}" :
                "int main(){int x=7;return ((x)+(3));}";
            check(builder.AddSectionFromMemory("memory", source.c_str()) >= 0 && builder.BuildModule() >= 0, "Bytecode check compilation failed");
            auto *function = builder.GetModule()->GetFunctionByDecl("int main()");
            asUINT count = 0; auto *code = function->GetByteCode(&count);
            std::vector<asDWORD> instructions(code, code + count);
            if (useMacro) check(instructions == referenceCode, "Macro expansion changed runtime bytecode");
            else referenceCode = std::move(instructions);
            check(run(engine, builder.GetModule()) == 10, "Bytecode check output changed");
        }
        builder.StartNewModule(engine, "native-import");
        check(builder.AddSectionFromMemory("memory", "import int host(int) from \"other\"; int main(){return 42;}") >= 0 && builder.BuildModule() >= 0,
              "Legacy function import grammar changed");
        check(builder.GetModule()->GetImportedFunctionCount() == 1, "Native function import was consumed as a module import");
        builder.StartNewModule(engine, "define-word"); builder.DefineWord("HOST_FLAG");
        check(builder.AddSectionFromMemory("memory", "#if HOST_FLAG\nint main(){return 42;}\n#endif\n") >= 0 && builder.BuildModule() >= 0,
              "Host DefineWord compatibility failed");
        check(run(engine, builder.GetModule()) == 42, "Host define value changed");
    }
    engine->ShutDownAndRelease();
    std::cout << "Rebuild cache invalidation, identical runtime bytecode, native function import and host DefineWord checks passed.\n";
}
