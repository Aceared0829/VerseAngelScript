// Include the implementation to measure the actual registered concatenation
// callbacks against their former ostringstream implementation.
#include "../../sdk/add_on/scriptstdstring/scriptstdstring.cpp"
#include "../../sdk/add_on/scripthelper/scripthelper.h"
#include "../../sdk/samples/common/vas_format.h"
#include "../../sdk/samples/asrun/vendor/fmt/args.h"
#include <chrono>
#include <cstdlib>
#include <iostream>
#include <limits>
#include <new>

static bool counting = false;
static size_t allocations = 0;
void *operator new(size_t size) {
    if (void *p = std::malloc(size ? size : 1)) { if (counting) ++allocations; return p; }
    throw std::bad_alloc();
}
void *operator new[](size_t size) { return ::operator new(size); }
void operator delete(void *p) noexcept { std::free(p); }
void operator delete[](void *p) noexcept { std::free(p); }
void operator delete(void *p, size_t) noexcept { std::free(p); }
void operator delete[](void *p, size_t) noexcept { std::free(p); }

static void require(bool condition, const char *message) {
    if (!condition) { std::cerr << message << '\n'; std::exit(1); }
}
template<typename T> static std::string oldText(T value) {
    std::ostringstream stream; stream << value; return stream.str();
}
template<typename T> static void checkText(T value) {
    NumericText text(value);
    require(std::string(text.data(), text.length()) == oldText(value), "numeric output changed");
}
struct Grouped : std::numpunct<char> {
    char do_decimal_point() const override { return ','; }
    char do_thousands_sep() const override { return '_'; }
    std::string do_grouping() const override { return "\3"; }
};
struct LongNumber : std::num_put<char> {
    iter_type do_put(iter_type out, std::ios_base &, char_type, double) const override {
        for (int i = 0; i < 200; ++i) *out++ = 'x';
        return out;
    }
};
static void numericRegression() {
    checkText((std::numeric_limits<asINT64>::min)());
    checkText((std::numeric_limits<asINT64>::max)());
    checkText((std::numeric_limits<asQWORD>::max)());
    checkText(asINT64(0));
    const double values[] = {0, -0.0, 1.23456789, 1e-9, 1e20,
        (std::numeric_limits<double>::max)(), (std::numeric_limits<double>::min)(),
        std::numeric_limits<double>::denorm_min(), std::numeric_limits<double>::infinity(),
        -std::numeric_limits<double>::infinity(), std::numeric_limits<double>::quiet_NaN()};
    for (double value : values) { checkText(value); checkText(float(value)); }
    std::locale previous = std::locale::global(std::locale(std::locale::classic(), new Grouped));
    checkText(asINT64(-123456789)); checkText(asQWORD(123456789)); checkText(12345.6789);
    std::locale::global(std::locale(std::locale::classic(), new LongNumber));
    checkText(1.25); // A host facet exceeding the stack buffer must remain valid.
    std::locale::global(previous);
    require(AddStringBool("[", true) == "[true", "bool append changed");
    require(AddBoolString(false, "]") == "false]", "bool prepend changed");
    std::string value;
    AssignInt64ToString((std::numeric_limits<asINT64>::min)(), value);
    AddAssignUInt64ToString((std::numeric_limits<asQWORD>::max)(), value);
    require(value == "-922337203685477580818446744073709551615", "assignment/append changed");
}

static void dummy(asIScriptGeneric *) {}
static void compilerRegression() {
    asIScriptEngine *engine = asCreateScriptEngine();
    int invalid = engine->RegisterGlobalFunction("void invalid(int value, const ?&in ...)",
                                                asFUNCTION(dummy), asCALL_GENERIC);
    require(invalid >= 0, "primitive variadic registration failed");
    require(engine->GetFunctionById(invalid)->SetFormatStringValidator(vas::ValidateFormat, engine) == asINVALID_ARG,
            "missing string type accepted a primitive format parameter");
    RegisterStdString(engine);
    int id = engine->RegisterGlobalFunction("void checked(const string &in format, const ?&in ...)",
                                           asFUNCTION(dummy), asCALL_GENERIC);
    require(id >= 0, "registration failed");
    require(engine->GetFunctionById(id)->SetFormatStringValidator(vas::ValidateFormat, engine) == 0,
            "opt-in failed");
    int ordinary = engine->RegisterGlobalFunction("void ordinary(const string &in, const ?&in ...)",
                                                 asFUNCTION(dummy), asCALL_GENERIC);
    require(ordinary >= 0, "ordinary registration failed");
    auto build = [engine](const char *code) {
        auto *module = engine->GetModule("test", asGM_ALWAYS_CREATE);
        module->AddScriptSection("test", code);
        return module->Build();
    };
    require(build("void main(){ ordinary(\"{}\"); }") >= 0, "unmarked function was checked");
    require(build("namespace custom { void print(const string &in) {} } void main(){ custom::print(\"{\"); }") >= 0,
            "script function was checked by name");
    require(build("void main(){ checked(format: \"ok\"); }") >= 0, "named fixed parameter failed");
    require(build("void main(){ checked(format: \"{}\"); }") < 0, "named literal skipped validation");
    require(build("void main(){ checked(\"{}\", 7); }") >= 0, "valid primitive failed");
    std::stringstream config;
    require(WriteConfigToStream(engine, config) >= 0, "config export failed");
    require(config.str().find("formatfunc \"void checked") != std::string::npos, "metadata not exported");
    asIScriptEngine *consumer = asCreateScriptEngine();
    require(ConfigEngineFromStream(consumer, config, "test", GetStdStringFactorySingleton(),
                                    vas::ValidateFormat, consumer) >= 0, "metadata import failed");
    auto *module = consumer->GetModule("test", asGM_ALWAYS_CREATE);
    module->AddScriptSection("test", "void main(){ checked(\"{}\"); }");
    require(module->Build() < 0, "export/import silently lost checks");
    consumer->ShutDownAndRelease();
    config.clear(); config.seekg(0);
    consumer = asCreateScriptEngine();
    require(ConfigEngineFromStream(consumer, config, "test", GetStdStringFactorySingleton()) < 0,
            "missing validator silently accepted formatfunc");
    consumer->ShutDownAndRelease();
    engine->ShutDownAndRelease();
}

template<typename F> static size_t measure(const char *name, F operation) {
    const int iterations = 10000;
    volatile size_t checksum = 0;
    operation(); // warm up library state
    allocations = 0;
    auto begin = std::chrono::steady_clock::now();
    counting = true;
    for (int i = 0; i < iterations; ++i) checksum = checksum + operation();
    counting = false;
    auto elapsed = std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - begin).count();
    std::cout << name << ": " << allocations << " allocations / " << iterations
              << " calls, " << elapsed << " us, checksum=" << checksum << '\n';
    return allocations;
}

int main() {
    numericRegression();
    compilerRegression();
    const std::string prefix(80, 'x');
    const asINT64 number = (std::numeric_limits<asINT64>::min)();
    auto oldConcat = [&] { std::ostringstream stream; stream << number; return (prefix + stream.str()).size(); };
    auto newConcat = [&] { return AddStringInt64(prefix, number).size(); };
    require(AddStringInt64(prefix, number) == prefix + oldText(number), "concatenation changed");
    size_t oldCount = measure("old integer concatenation", oldConcat);
    size_t newCount = measure("new integer concatenation", newConcat);
    // libc++ can keep the old 20-byte conversion inside its larger string buffer.
    require(newCount <= oldCount, "integer allocation count regressed");
    auto oldFormat = [&] {
        fmt::dynamic_format_arg_store<fmt::format_context> args;
        args.push_back(prefix); args.push_back(number);
        return fmt::vformat("{} {}", args).size();
    };
    const std::string expected = fmt::vformat("{} {}", fmt::make_format_args(prefix, number));
    auto measuredFormat = [&] {
        vas::FormatArguments args(2);
        args.set(0, fmt::string_view(prefix.data(), prefix.size())); args.set(1, number);
        fmt::memory_buffer buffer; vas::FormatTo(buffer, "{} {}", args, false);
        require(fmt::string_view(buffer.data(), buffer.size()) == fmt::string_view(expected), "format changed");
        return buffer.size();
    };
    oldCount = measure("old format construction (excluding I/O)", oldFormat);
    newCount = measure("new format construction (excluding I/O)", measuredFormat);
    require(newCount == 0 && oldCount > newCount, "inline format path allocated");
    return 0;
}
