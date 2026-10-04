#ifndef VAS_FORMAT_H
#define VAS_FORMAT_H

#include <angelscript.h>
#include <algorithm>
#include <cstring>
#include <string>
#include <vector>
#ifndef FMT_HEADER_ONLY
#define FMT_HEADER_ONLY
#endif
#include "../asrun/vendor/fmt/format.h"

namespace vas {

// These details are pinned to the vendored fmt version. Parse syntax and types
// without rendering dummy values or allocating an output of user-supplied width.
class FormatChecker {
    fmt::detail::compile_parse_context<char> context;
    const fmt::detail::type *types;
    int count;

    void checkDynamic(fmt::arg_id_kind kind, const fmt::detail::arg_ref<char> &ref) {
        if (kind == fmt::arg_id_kind::none) return;
        if (kind == fmt::arg_id_kind::name) on_error("named arguments are not supported");
        if (ref.index < 0 || ref.index >= count) on_error("argument not found");
        const auto type = types[ref.index];
        if (type != fmt::detail::type::int_type && type != fmt::detail::type::uint_type &&
            type != fmt::detail::type::long_long_type && type != fmt::detail::type::ulong_long_type)
            on_error("width/precision is not integer");
    }
public:
    FormatChecker(fmt::string_view text, const fmt::detail::type *types, int count)
        : context(text, count, types), types(types), count(count) {}
    void on_text(const char *, const char *) {}
    int on_arg_id() { return context.next_arg_id(); }
    int on_arg_id(int id) { context.check_arg_id(id); return id; }
    int on_arg_id(fmt::string_view) { on_error("named arguments are not supported"); return 0; }
    void on_replacement_field(int, const char *) {}
    const char *on_format_specs(int id, const char *begin, const char *end) {
        context.advance_to(begin);
        fmt::detail::dynamic_format_specs<char> specs;
        const char *result = fmt::detail::parse_format_specs(begin, end, specs, context, types[id]);
        // At runtime fmt's base parse_context does not perform consteval checks.
        checkDynamic(specs.dynamic_width(), specs.width_ref);
        checkDynamic(specs.dynamic_precision(), specs.precision_ref);
        return result;
    }
    void on_error(const char *message) { throw fmt::format_error(message); }
};

inline int ValidateFormat(const char *text, asUINT length, const int *ids, asUINT count,
                          char *error, asUINT capacity, void *param) {
    try {
        const int stringType = static_cast<asIScriptEngine *>(param)->GetTypeIdByDecl("string");
        std::vector<fmt::detail::type> types;
        types.reserve(count);
        for (asUINT i = 0; i < count; ++i) {
            using fmt::detail::type;
            switch (ids[i]) {
            case asTYPEID_BOOL: types.push_back(type::bool_type); break;
            case asTYPEID_INT8: case asTYPEID_INT16: case asTYPEID_INT32: types.push_back(type::int_type); break;
            case asTYPEID_UINT8: case asTYPEID_UINT16: case asTYPEID_UINT32: types.push_back(type::uint_type); break;
            case asTYPEID_INT64: types.push_back(type::long_long_type); break;
            case asTYPEID_UINT64: types.push_back(type::ulong_long_type); break;
            case asTYPEID_FLOAT: types.push_back(type::float_type); break;
            case asTYPEID_DOUBLE: types.push_back(type::double_type); break;
            default:
                if (ids[i] != stringType) throw fmt::format_error("unsupported argument type");
                types.push_back(type::string_type);
            }
        }
        if (text) {
            fmt::string_view format(text, length);
            FormatChecker checker(format, types.data(), static_cast<int>(count));
            fmt::detail::parse_format_string(format, checker);
        }
        return asSUCCESS;
    } catch (const std::exception &exception) {
        if (capacity) {
            const size_t size = (std::min)(std::strlen(exception.what()), size_t(capacity - 1));
            std::memcpy(error, exception.what(), size);
            error[size] = 0;
        }
        return asERROR;
    }
}

// Primitive values live inside format_arg; string_view borrows the script's
// string only until the synchronous call completes. Large calls grow safely.
class FormatArguments {
    typedef fmt::basic_format_arg<fmt::format_context> Argument;
    Argument inlineArgs[16];
    std::vector<Argument> overflow;
    Argument *args;
    int count;
public:
    explicit FormatArguments(int count) : args(inlineArgs), count(count) {
        if (count > 16) { overflow.resize(count); args = overflow.data(); }
    }
    template<typename T> void set(int index, T value) { args[index] = Argument(value); }
    fmt::format_args view() const { return fmt::format_args(args, count); }
    FormatArguments(const FormatArguments &) = delete;
    FormatArguments &operator=(const FormatArguments &) = delete;
};

inline void FormatTo(fmt::memory_buffer &output, fmt::string_view format,
                     const FormatArguments &arguments, bool newline) {
    fmt::vformat_to(std::back_inserter(output), format, arguments.view());
    if (newline) output.push_back('\n');
}

} // namespace vas
#endif
