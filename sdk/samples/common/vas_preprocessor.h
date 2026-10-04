#ifndef VAS_PREPROCESSOR_H
#define VAS_PREPROCESSOR_H

#include <angelscript.h>
#include <algorithm>
#include <cstdint>
#include <functional>
#include <map>
#include <stdexcept>
#include <string>
#include <string_view>
#include <unordered_map>
#include <vector>

namespace vas {

// Tool-layer preprocessing. The embeddable upstream builder remains unchanged.
class Preprocessor {
public:
    enum DependencyKind { Quoted, System, Module };
    struct Statistics { uint64_t tokens = 0, expansions = 0, outputBytes = 0, fastSections = 0; } stats;
    struct Span { size_t begin, end, original; bool linear; };
    struct Section {
        std::string name, raw, output;
        int lineOffset = 0;
        std::vector<size_t> rawLines, outputLines;
        std::vector<Span> spans;
    };
    struct Origin { Section *section = nullptr; size_t offset = 0; };
    typedef std::function<int(const std::string &, DependencyKind, const Origin &)> Loader;
    typedef std::function<int(const std::string &)> Pragma;
private:
    struct Token { std::string_view text; asETokenClass kind; Origin origin; bool linear = true; };
    struct Macro {
        bool function = false;
        std::vector<std::string_view> parameters;
        std::vector<Token> body;
        std::vector<int> slots;
        Origin definition;
    };
    struct Conditional { bool parent, active, taken, seenElse = false; Origin origin; };
    asIScriptEngine *engine = nullptr;
    std::map<std::string, Section> sections;
    std::unordered_map<std::string_view, Macro> macros;
    static constexpr size_t maxTokens = 1000000, maxOutput = 64 * 1024 * 1024;
    size_t expansionWork = 0;

    static bool trivia(const Token &token) { return token.kind == asTC_WHITESPACE || token.kind == asTC_COMMENT; }
    static size_t next(const std::vector<Token> &tokens, size_t i) {
        while (i < tokens.size() && trivia(tokens[i])) ++i;
        return i;
    }
    static std::vector<size_t> lines(const std::string &text) {
        std::vector<size_t> result(1, 0);
        for (size_t i = 0; i < text.size(); ++i) if (text[i] == '\n') result.push_back(i + 1);
        return result;
    }
    static void position(const Origin &origin, int &row, int &column) {
        const auto &starts = origin.section->rawLines;
        size_t line = size_t(std::upper_bound(starts.begin(), starts.end(), origin.offset) - starts.begin() - 1);
        row = int(line + 1) + origin.section->lineOffset;
        column = int(origin.offset - starts[line] + 1);
    }
    static std::string location(const Origin &origin) {
        int row, column; position(origin, row, column);
        return origin.section->name + ":" + std::to_string(row) + ":" + std::to_string(column);
    }
    [[noreturn]] void fail(const Origin &origin, const std::string &message) {
        int row, column; position(origin, row, column);
        engine->WriteMessage(origin.section->name.c_str(), row, column, asMSGTYPE_ERROR, message.c_str());
        throw Failure();
    }
    struct Failure {};
    std::vector<Token> lex(Section &section) {
        std::vector<Token> result;
        // Most script tokens average more than four bytes; reserve once without
        // a second lexer pass. Views borrow the immutable section buffer.
        result.reserve((std::min)(section.raw.size() / 4 + 1, size_t(65536)));
        for (size_t offset = 0; offset < section.raw.size();) {
            asUINT length = 0;
            auto kind = engine->ParseToken(section.raw.data() + offset, section.raw.size() - offset, &length);
            if (!length) fail({&section, offset}, "Preprocessor lexer made no progress");
            result.push_back({std::string_view(section.raw.data() + offset, length), kind, {&section, offset}});
            offset += length;
            ++stats.tokens;
            if (result.size() > maxTokens) fail({&section, offset}, "Section exceeds one million preprocessing tokens");
        }
        return result;
    }
    void append(Section &section, const Token &token) {
        if (token.text.size() > maxOutput - section.output.size()) fail(token.origin, "Preprocessed section exceeds 64 MiB");
        size_t begin = section.output.size();
        section.output.append(token.text.data(), token.text.size());
        // Unmodified ranges coalesce: ordinary files need one mapping span,
        // rather than a per-byte or per-token source map.
        if (!section.spans.empty()) {
            Span &last = section.spans.back();
            if (last.end == begin && ((!last.linear && !token.linear && last.original == token.origin.offset) ||
                (last.linear && token.linear && last.original + (last.end - last.begin) == token.origin.offset))) {
                last.end = section.output.size(); return;
            }
        }
        section.spans.push_back({begin, section.output.size(), token.origin.offset, token.linear});
    }
    void blank(Section &section, size_t begin, size_t end) {
        if (end - begin > maxOutput - section.output.size()) fail({&section, begin}, "Preprocessed section exceeds 64 MiB");
        size_t outputBegin = section.output.size();
        for (size_t i = begin; i < end; ++i) section.output.push_back(section.raw[i] == '\n' ? '\n' : ' ');
        section.spans.push_back({outputBegin, section.output.size(), begin, true});
    }
    static bool same(const Macro &a, const Macro &b) {
        if (a.function != b.function || a.parameters != b.parameters || a.body.size() != b.body.size()) return false;
        for (size_t i = 0; i < a.body.size(); ++i) if (a.body[i].text != b.body[i].text) return false;
        return true;
    }
    void define(const std::vector<Token> &tokens, size_t begin, size_t end) {
        begin = next(tokens, begin);
        if (begin >= end || tokens[begin].kind != asTC_IDENTIFIER) fail(tokens[begin - 1].origin, "Expected a macro name");
        const Token &name = tokens[begin++];
        Macro macro; macro.definition = name.origin;
        // Function macros require the opening parenthesis to touch the name.
        if (begin < end && tokens[begin].text == "(" && tokens[begin].origin.offset == name.origin.offset + name.text.size()) {
            macro.function = true; ++begin;
            begin = next(tokens, begin);
            if (begin < end && tokens[begin].text != ")") {
                for (;;) {
                    if (begin >= end || tokens[begin].kind != asTC_IDENTIFIER) fail(name.origin, "Expected a macro parameter name");
                    if (std::find(macro.parameters.begin(), macro.parameters.end(), tokens[begin].text) != macro.parameters.end())
                        fail(tokens[begin].origin, "Duplicate macro parameter");
                    macro.parameters.push_back(tokens[begin++].text);
                    begin = next(tokens, begin);
                    if (begin < end && tokens[begin].text == ")") break;
                    if (begin >= end || tokens[begin].text != ",") fail(name.origin, "Expected ',' or ')' in macro parameters");
                    begin = next(tokens, begin + 1);
                }
            }
            if (begin >= end || tokens[begin].text != ")") fail(name.origin, "Unclosed macro parameter list");
            ++begin;
        }
        for (; begin < end; ++begin) {
            if (trivia(tokens[begin]) || tokens[begin].text == "\\") continue;
            if (tokens[begin].text == "#" || tokens[begin].text == "##" || tokens[begin].text == "...")
                fail(tokens[begin].origin, "Stringizing, token pasting and variadic macros are not supported");
            macro.body.push_back(tokens[begin]);
            auto param = tokens[begin].kind == asTC_IDENTIFIER ? std::find(macro.parameters.begin(), macro.parameters.end(), tokens[begin].text) : macro.parameters.end();
            macro.slots.push_back(param == macro.parameters.end() ? -1 : int(param - macro.parameters.begin()));
        }
        auto existing = macros.find(name.text);
        if (existing != macros.end() && !same(existing->second, macro))
            fail(name.origin, "Conflicting macro '" + std::string(name.text) + "'; previous definition at " + location(existing->second.definition));
        if (existing == macros.end()) macros.emplace(name.text, std::move(macro));
    }
    std::vector<std::vector<Token>> arguments(const std::vector<Token> &tokens, size_t &i, const Origin &origin) {
        std::vector<std::vector<Token>> result(1);
        int depth = 1;
        ++i;
        for (; i < tokens.size(); ++i) {
            const Token &token = tokens[i];
            if (token.text == "(") ++depth;
            if (token.text == ")" && --depth == 0) { ++i; return result; }
            // Only a closing parenthesis decrements nesting.
            if (token.text == "," && depth == 1) result.emplace_back();
            else result.back().push_back(token);
        }
        fail(origin, "Unclosed function macro invocation");
    }
    std::vector<Token> expand(const std::vector<Token> &tokens, std::vector<std::string_view> &disabled, unsigned depth = 0) {
        std::vector<Token> result;
        result.reserve(tokens.size());
        for (size_t i = 0; i < tokens.size();) {
            const Token &token = tokens[i];
            if (token.kind != asTC_IDENTIFIER || !macros.count(token.text) || std::find(disabled.begin(), disabled.end(), token.text) != disabled.end()) {
                if (++expansionWork > maxTokens) fail(token.origin, "Macro expansion work limit exceeded");
                result.push_back(token); ++i; continue;
            }
            auto expanded = expandAt(tokens, i, disabled, depth);
            result.insert(result.end(), expanded.begin(), expanded.end());
        }
        return result;
    }
    std::vector<Token> expandAt(const std::vector<Token> &tokens, size_t &i, std::vector<std::string_view> &disabled, unsigned depth) {
        const Token &token = tokens[i++];
        if (++expansionWork > maxTokens) fail(token.origin, "Macro expansion work limit exceeded");
        auto found = token.kind == asTC_IDENTIFIER ? macros.find(token.text) : macros.end();
        if (found == macros.end() || std::find(disabled.begin(), disabled.end(), token.text) != disabled.end()) return {token};
        if (depth >= 64) fail(token.origin, "Macro expansion depth exceeds 64");
        const Macro &macro = found->second;
        std::vector<std::vector<Token>> args;
        if (macro.function) {
            size_t open = next(tokens, i);
            if (open >= tokens.size() || tokens[open].text != "(") return {token};
            i = open;
            args = arguments(tokens, i, token.origin);
            if (macro.parameters.empty() && args.size() == 1 && next(args[0], 0) == args[0].size()) args.clear();
            if (args.size() != macro.parameters.size()) fail(token.origin, "Wrong number of arguments for macro '" + std::string(token.text) + "'");
        }
        ++stats.expansions;
        std::vector<Token> replacement;
        replacement.reserve(macro.body.size());
        std::vector<bool> expandedArgs(args.size(), false);
        for (size_t part = 0; part < macro.body.size(); ++part) {
            Token body = macro.body[part];
            if (macro.slots[part] >= 0) {
                size_t index = size_t(macro.slots[part]);
                if (!expandedArgs[index]) {
                    bool containsMacro = std::any_of(args[index].begin(), args[index].end(), [this](const Token &item) { return item.kind == asTC_IDENTIFIER && macros.count(item.text); });
                    if (containsMacro) args[index] = expand(args[index], disabled, depth + 1);
                    expandedArgs[index] = true;
                }
                replacement.insert(replacement.end(), args[index].begin(), args[index].end());
            } else {
                body.origin = token.origin;
                body.linear = false;
                replacement.push_back(body);
            }
        }
        disabled.push_back(token.text);
        std::vector<Token> output = expand(replacement, disabled, depth + 1);
        // An object macro may alias a function macro. Its invocation arguments
        // belong to the surrounding stream, not to the replacement body.
        size_t last = output.size();
        while (last && trivia(output[last - 1])) --last;
        if (!macro.function && last && next(tokens, i) < tokens.size() && tokens[next(tokens, i)].text == "(") {
            auto alias = macros.find(output[last - 1].text);
            if (alias != macros.end() && alias->second.function && std::find(disabled.begin(), disabled.end(), output[last - 1].text) == disabled.end()) {
                size_t end = next(tokens, i);
                size_t after = end; arguments(tokens, after, token.origin);
                std::vector<Token> call(1, output[last - 1]);
                call.insert(call.end(), tokens.begin() + end, tokens.begin() + after);
                auto aliasDisabled = disabled; aliasDisabled.pop_back();
                auto value = expand(call, aliasDisabled, depth + 1);
                output.resize(last - 1); output.insert(output.end(), value.begin(), value.end()); i = after;
            }
        }
        disabled.pop_back();
        return output;
    }
    class Expression {
        const std::vector<Token> &tokens;
        size_t index = 0;
        unsigned nesting = 0;
        static int precedence(std::string_view op) {
            if (op == "||") return 1; if (op == "&&") return 2; if (op == "|") return 3;
            if (op == "^") return 4; if (op == "&") return 5; if (op == "==" || op == "!=") return 6;
            if (op == "<" || op == ">" || op == "<=" || op == ">=") return 7;
            if (op == "<<" || op == ">>") return 8; if (op == "+" || op == "-") return 9;
            if (op == "*" || op == "/" || op == "%") return 10; return 0;
        }
        bool take(std::string_view text) { if (index < tokens.size() && tokens[index].text == text) { ++index; return true; } return false; }
        int64_t primary(bool evaluate) {
            struct Guard {
                unsigned &depth;
                explicit Guard(unsigned &value) : depth(value) { if (++depth > 128) throw std::runtime_error("#if expression nesting exceeds 128"); }
                ~Guard() { --depth; }
            } guard(nesting);
            if (take("!")) return !primary(evaluate);
            if (take("~")) return ~primary(evaluate);
            if (take("+")) return primary(evaluate);
            if (take("-")) return int64_t(uint64_t(0) - uint64_t(primary(evaluate)));
            if (take("(")) { int64_t value = binary(1, evaluate); if (!take(")")) throw std::runtime_error("Expected ')' in #if"); return value; }
            if (index == tokens.size()) throw std::runtime_error("Missing operand in #if");
            const Token &token = tokens[index++];
            if (token.text == "true") return 1;
            if (token.text == "false" || token.kind == asTC_IDENTIFIER) return 0;
            std::string text(token.text); size_t end = 0;
            text.erase(std::remove(text.begin(), text.end(), '\''), text.end());
            // Match VAS radix prefixes and decimal leading zeros, rather than
            // inheriting C's implicit octal interpretation from strtoull(base=0).
            int radix = 10;
            if (text.size() > 2 && text[0] == '0') {
                char prefix = text[1];
                if (prefix == 'x' || prefix == 'X') radix = 16;
                else if (prefix == 'b' || prefix == 'B') radix = 2;
                else if (prefix == 'o' || prefix == 'O') radix = 8;
                else if (prefix == 'd' || prefix == 'D') radix = 10;
                if (prefix == 'x' || prefix == 'X' || prefix == 'b' || prefix == 'B' ||
                    prefix == 'o' || prefix == 'O' || prefix == 'd' || prefix == 'D') text.erase(0, 2);
            }
            uint64_t value;
            try { value = std::stoull(text, &end, radix); } catch (...) { throw std::runtime_error("Expected an integer in #if"); }
            while (end < text.size() && (text[end] == 'u' || text[end] == 'U' || text[end] == 'l' || text[end] == 'L')) ++end;
            if (end != text.size()) throw std::runtime_error("Expected an integer in #if");
            return int64_t(value);
        }
        int64_t binary(int minimum, bool evaluate) {
            int64_t lhs = primary(evaluate);
            while (index < tokens.size()) {
                auto op = tokens[index].text; int priority = precedence(op);
                if (priority < minimum) break;
                ++index;
                bool rightEval = evaluate && !(op == "&&" && !lhs) && !(op == "||" && lhs);
                int64_t rhs = binary(priority + 1, rightEval);
                if (!evaluate) { lhs = 0; continue; }
                if (op == "&&") lhs = lhs && rhs; else if (op == "||") lhs = lhs || rhs;
                else if (op == "+") lhs = int64_t(uint64_t(lhs) + uint64_t(rhs));
                else if (op == "-") lhs = int64_t(uint64_t(lhs) - uint64_t(rhs));
                else if (op == "*") lhs = int64_t(uint64_t(lhs) * uint64_t(rhs));
                else if (op == "/" || op == "%") {
                    if (!rhs) throw std::runtime_error("Division by zero in #if");
                    if (lhs == INT64_MIN && rhs == -1) throw std::runtime_error("Integer division overflow in #if");
                    lhs = op == "/" ? lhs / rhs : lhs % rhs;
                } else if (op == "<<" || op == ">>") {
                    if (rhs < 0 || rhs >= 64) throw std::runtime_error("Invalid shift in #if");
                    lhs = op == "<<" ? int64_t(uint64_t(lhs) << rhs) : lhs >> rhs;
                } else if (op == "==") lhs = lhs == rhs; else if (op == "!=") lhs = lhs != rhs;
                else if (op == "<") lhs = lhs < rhs; else if (op == ">") lhs = lhs > rhs;
                else if (op == "<=") lhs = lhs <= rhs; else if (op == ">=") lhs = lhs >= rhs;
                else if (op == "&") lhs &= rhs; else if (op == "|") lhs |= rhs; else if (op == "^") lhs ^= rhs;
            }
            return lhs;
        }
    public:
        explicit Expression(const std::vector<Token> &tokens) : tokens(tokens) {}
        bool value() { int64_t result = binary(1, true); if (index != tokens.size()) throw std::runtime_error("Unexpected token in #if"); return result != 0; }
    };
    bool condition(std::vector<Token> tokens, const Origin &origin) {
        try {
            std::vector<Token> defined;
            for (size_t i = 0; i < tokens.size(); ++i) {
                if (trivia(tokens[i])) continue;
                Token token = tokens[i];
                if (token.text == "defined") {
                    i = next(tokens, i + 1); bool parenthesis = i < tokens.size() && tokens[i].text == "(";
                    if (parenthesis) i = next(tokens, i + 1);
                    if (i >= tokens.size() || tokens[i].kind != asTC_IDENTIFIER) throw std::runtime_error("Expected a name after defined");
                    token.text = macros.count(tokens[i].text) ? "1" : "0"; token.kind = asTC_VALUE; token.linear = false;
                    if (parenthesis) { i = next(tokens, i + 1); if (i >= tokens.size() || tokens[i].text != ")") throw std::runtime_error("Expected ')' after defined"); }
                }
                defined.push_back(token);
            }
            std::vector<std::string_view> disabled;
            auto expanded = expand(defined, disabled);
            expanded.erase(std::remove_if(expanded.begin(), expanded.end(), trivia), expanded.end());
            return Expression(expanded).value();
        } catch (const std::exception &error) { fail(origin, error.what()); }
    }
    static size_t directiveEnd(const std::vector<Token> &tokens, size_t begin) {
        size_t end = begin;
        for (; end < tokens.size(); ++end) {
            if (tokens[end].kind != asTC_WHITESPACE || tokens[end].text.find('\n') == std::string_view::npos) continue;
            if (end > begin && tokens[end - 1].text == "\\") continue;
            break;
        }
        return end;
    }
public:
    bool canSkip(std::string_view code) const {
        return macros.empty() && code.find('#') == std::string_view::npos && code.find("import") == std::string_view::npos;
    }
    void reset(asIScriptEngine *value) { macros.clear(); sections.clear(); stats = {}; expansionWork = 0; engine = value; }
    void defineWord(const std::string &name) {
        auto &section = sections["<host define " + name + ">"]; section.name = "<host defines>"; section.raw = name; section.rawLines = {0};
        Macro macro; macro.definition = {&section, 0}; macro.body.push_back({"1", asTC_VALUE, macro.definition, false}); macro.slots.push_back(-1);
        macros.emplace(std::string_view(section.raw), std::move(macro));
    }
    bool process(const std::string &name, const std::string &raw, int lineOffset, const Loader &load, const Pragma &pragma) {
        auto &section = sections[name]; section.name = name; section.raw = raw; section.lineOffset = lineOffset; section.rawLines = lines(raw); section.output.reserve(raw.size());
        if (raw.size() > maxOutput) { engine->WriteMessage(name.c_str(), 0, 0, asMSGTYPE_ERROR, "Source section exceeds 64 MiB"); return false; }
        std::vector<Token> tokens;
        try { tokens = lex(section); } catch (const Failure &) { return false; }
        std::vector<Conditional> stack;
        int braces = 0;
        try {
            for (size_t i = 0; i < tokens.size();) {
                const Token &token = tokens[i];
                bool active = stack.empty() || stack.back().active;
                if (token.text == "#") {
                    size_t word = next(tokens, i + 1), end = directiveEnd(tokens, word);
                    if (word >= tokens.size()) { append(section, token); ++i; continue; }
                    auto directive = tokens[word].text;
                    size_t arg = next(tokens, word + 1);
                    if (directive != "include") arg = (std::min)(arg, end);
                    size_t finish = end < tokens.size() ? tokens[end].origin.offset : raw.size();
                    if (directive == "if" || directive == "ifdef" || directive == "ifndef") {
                        bool value = false;
                        if (directive != "if") {
                            if (arg >= end || tokens[arg].kind != asTC_IDENTIFIER || next(tokens, arg + 1) < end) fail(token.origin, "Expected one name in conditional directive");
                            value = macros.count(tokens[arg].text) != 0;
                            if (directive == "ifndef") value = !value;
                        } else if (active) value = condition(std::vector<Token>(tokens.begin() + arg, tokens.begin() + end), token.origin);
                        if (stack.size() >= 128) fail(token.origin, "Conditional nesting exceeds 128");
                        stack.push_back({active, active && value, active && value, false, token.origin});
                    } else if (directive == "else" || directive == "elif") {
                        if (stack.empty() || stack.back().seenElse) fail(token.origin, "Unmatched or duplicate #else/#elif");
                        auto &branch = stack.back();
                        bool value = directive == "else" || (branch.parent && !branch.taken && condition(std::vector<Token>(tokens.begin() + arg, tokens.begin() + end), token.origin));
                        branch.active = branch.parent && !branch.taken && value; branch.taken |= branch.active;
                        if (directive == "else") { branch.seenElse = true; if (arg < end) fail(token.origin, "Unexpected tokens after #else"); }
                    } else if (directive == "endif") {
                        if (stack.empty()) fail(token.origin, "Unmatched #endif");
                        if (arg < end) fail(token.origin, "Unexpected tokens after #endif"); stack.pop_back();
                    } else if (!active) {
                        // Inactive directives do not resolve files or define macros.
                    } else if (directive == "define") define(tokens, word + 1, end);
                    else if (directive == "undef") {
                        if (arg >= end || tokens[arg].kind != asTC_IDENTIFIER || next(tokens, arg + 1) < end) fail(token.origin, "Expected one name after #undef");
                        macros.erase(tokens[arg].text);
                    } else if (directive == "include") {
                        // Like the existing builder, include filenames may start
                        // on a subsequent line; tokens after the filename remain code.
                        if (arg < tokens.size() && tokens[arg].kind == asTC_VALUE && tokens[arg].text.size() > 2 && (tokens[arg].text[0] == '"' || tokens[arg].text[0] == '\'')) {
                            std::string path(tokens[arg].text.substr(1, tokens[arg].text.size() - 2));
                            if (path.find_first_of("\r\n") != std::string::npos) fail(token.origin, "Newline in include filename");
                            if (load(path, Quoted, token.origin) < 0) throw Failure();
                            finish = tokens[arg].origin.offset + tokens[arg].text.size(); end = arg + 1;
                        } else if (arg < tokens.size() && tokens[arg].text == "<") {
                            size_t close = arg + 1; std::string path;
                            for (; close < tokens.size() && tokens[close].text != ">"; ++close) {
                                if (trivia(tokens[close])) fail(token.origin, "Whitespace in angle include filename");
                                path.append(tokens[close].text);
                            }
                            if (close == tokens.size() || path.empty()) fail(token.origin, "Invalid angle include");
                            if (load(path, System, token.origin) < 0) throw Failure();
                            finish = tokens[close].origin.offset + 1; end = close + 1;
                        } else { append(section, token); ++i; continue; }
                    } else if (directive == "pragma") {
                        std::string text = arg < end ? std::string(raw.data() + tokens[arg].origin.offset, finish - tokens[arg].origin.offset) : "";
                        if (!pragma || pragma(text) < 0) fail(token.origin, "Invalid #pragma directive");
                    } else if (directive == "error") {
                        fail(token.origin, "#error " + std::string(raw.data() + tokens[word].origin.offset + directive.size(), finish - tokens[word].origin.offset - directive.size()));
                    } else { append(section, token); ++i; continue; }
                    blank(section, token.origin.offset, finish); i = end; continue;
                }
                if (!active) { blank(section, token.origin.offset, token.origin.offset + token.text.size()); ++i; }
                else if (token.text == "import") {
                    size_t part = next(tokens, i + 1), end = part; std::string path;
                    if (part < tokens.size() && tokens[part].kind == asTC_IDENTIFIER) {
                        path = std::string(tokens[part].text); end = next(tokens, part + 1);
                        while (end < tokens.size() && tokens[end].text == ".") {
                            size_t identifier = next(tokens, end + 1);
                            if (identifier == tokens.size() || tokens[identifier].kind != asTC_IDENTIFIER) break;
                            path += "/"; path.append(tokens[identifier].text); end = next(tokens, identifier + 1);
                        }
                    }
                    if (!path.empty() && end < tokens.size() && tokens[end].text == ";") {
                        if (braces) fail(token.origin, "Module imports must be at file scope");
                        if (load(path + ".vas", Module, token.origin) < 0) throw Failure();
                        blank(section, token.origin.offset, tokens[end].origin.offset + 1); i = end + 1;
                    } else { append(section, token); ++i; }
                } else {
                    auto found = token.kind == asTC_IDENTIFIER ? macros.find(token.text) : macros.end();
                    if (found == macros.end()) { append(section, token); ++i; if (token.text == "{") ++braces; if (token.text == "}") --braces; }
                    else {
                        std::vector<std::string_view> disabled;
                        auto expanded = expandAt(tokens, i, disabled, 0);
                        size_t endOffset = tokens[i - 1].origin.offset + tokens[i - 1].text.size();
                        size_t newlines = size_t(std::count(raw.begin() + token.origin.offset, raw.begin() + endOffset, '\n'));
                        // Separators prevent adjacent expansions from accidentally
                        // forming a different lexer token (e.g. '+' then '+').
                        Token space{" ", asTC_WHITESPACE, token.origin, false}; append(section, space);
                        for (auto value : expanded) {
                            if (trivia(value)) continue;
                            if (value.text.find('\n') != std::string_view::npos)
                                fail(token.origin, "Use escaped \\n instead of physical newlines in macro literals");
                            if (value.origin.section != &section) value.origin = token.origin;
                            value.linear = false; append(section, value); append(section, space);
                            if (value.text == "{") ++braces; if (value.text == "}") --braces;
                        }
                        Token newline{"\n", asTC_WHITESPACE, token.origin, false};
                        for (size_t line = 0; line < newlines; ++line) append(section, newline);
                    }
                }
            }
            if (!stack.empty()) fail(stack.back().origin, "Unclosed conditional directive");
            section.outputLines = lines(section.output); stats.outputBytes += section.output.size();
            return true;
        } catch (const Failure &) { return false; }
    }
    const std::string &output(const std::string &name) const { return sections.at(name).output; }
    void remap(asSMessageInfo &message) const {
        auto found = sections.find(message.section ? message.section : "");
        if (found == sections.end() || message.row <= found->second.lineOffset || message.col <= 0) return;
        const Section &section = found->second;
        if (section.outputLines.empty()) return; // preprocessing diagnostics are already original
        size_t line = size_t(message.row - section.lineOffset - 1);
        if (line >= section.outputLines.size()) return;
        size_t offset = section.outputLines[line] + size_t(message.col - 1);
        auto span = std::upper_bound(section.spans.begin(), section.spans.end(), offset,
            [](size_t value, const Span &item) { return value < item.begin; });
        if (span == section.spans.begin()) return; --span;
        if (offset > span->end) return;
        Origin origin{const_cast<Section *>(&section), span->original + (span->linear ? offset - span->begin : 0)};
        position(origin, message.row, message.col);
    }
};
}
#endif
