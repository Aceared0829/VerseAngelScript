cmake_minimum_required(VERSION 3.21)
file(MAKE_DIRECTORY "${TEST_ROOT}/pkg" "${TEST_ROOT}/search-a" "${TEST_ROOT}/search-b")
file(WRITE "${TEST_ROOT}/constants.vas" "#define BASE 10\n#define LABEL \"math\"\n")
file(WRITE "${TEST_ROOT}/math.vas" "import constants;\n#define SUM(a,b) ((a)+(b))\n#define ALIAS SUM\nint add(int a,int b){ return a+b; }\n")
file(WRITE "${TEST_ROOT}/pkg/tool.vas" "#define TOOL_VERSION 2\nint tool(){return 9;}\n")
set(config "${SOURCE_ROOT}/sdk/samples/asbuild/bin/config.txt")
function(check name code expected)
    file(WRITE "${TEST_ROOT}/main.vas" "${code}\n")
    execute_process(COMMAND "${VASBUILD}" "${config}" "${TEST_ROOT}/main.vas" "${TEST_ROOT}/main.vasbc"
        RESULT_VARIABLE result OUTPUT_VARIABLE out ERROR_VARIABLE err TIMEOUT 20 ENCODING UTF-8)
    if(NOT result EQUAL 0)
        message(FATAL_ERROR "${name} compilation failed (${result})\n${out}\n${err}")
    endif()
    execute_process(COMMAND "${VASRUN}" "${TEST_ROOT}/main.vas"
        RESULT_VARIABLE result OUTPUT_VARIABLE out ERROR_VARIABLE err TIMEOUT 20 ENCODING UTF-8)
    string(REPLACE "\r\n" "\n" out "${out}")
    if(NOT result EQUAL 0 OR NOT out STREQUAL expected)
        message(FATAL_ERROR "${name} runtime mismatch (${result})\n${out}\n${err}")
    endif()
endfunction()
function(reject name code expected)
    file(WRITE "${TEST_ROOT}/main.vas" "${code}\n")
    foreach(tool IN ITEMS "${VASBUILD}" "${VASRUN}")
        if(tool STREQUAL VASBUILD)
            set(args "${config}" "${TEST_ROOT}/main.vas" "${TEST_ROOT}/bad.vasbc")
        else()
            set(args "${TEST_ROOT}/main.vas")
        endif()
        execute_process(COMMAND "${tool}" ${args} RESULT_VARIABLE result
            OUTPUT_VARIABLE out ERROR_VARIABLE err TIMEOUT 20 ENCODING UTF-8)
        if(result EQUAL 0 OR NOT "${out}${err}" MATCHES "${expected}")
            message(FATAL_ERROR "${name} was not rejected cleanly by ${tool} (${result})\n${out}\n${err}")
        endif()
    endforeach()
endfunction()

foreach(form IN ITEMS "import math;" "#include \"math.vas\"" "#include <math.vas>")
    check("equivalent-${form}" "${form}\nvoid main(){println(\"{} {} {}\", add(20,22), SUM(50,22), LABEL);}" "42 72 math\n")
endforeach()
check(dedup-and-transitive [=[
import math;
#include "./math.vas"
#include <math.vas>
import constants;
import pkg.tool;
#if defined(BASE) && SUM(1,2) == 3 && TOOL_VERSION >= 2
void main(){println("{} {} {}", BASE, ALIAS(2,3), tool());}
#else
#error dependency macros not visible
#endif
]=] "10 5 9\n")
check(nested-arguments [=[
import math;
#define INC(x) ((x)+1)
#define VALUE 7
#define MULTILINE(x) ((x) + \
  VALUE)
void main(){println("{} {} {} {}", INC(INC(1)), SUM(add(1,2), SUM(3,4)), MULTILINE(2), ALIAS(ALIAS(1,2),3));}
]=] "3 10 9 6\n")
check(vas-integer-radices [=[
#if 0b1010 == 10 && 0o12 == 10 && 0d10 == 10 && 0xA == 10 && 010 == 10 && 1'000 == 1000
void main(){println(42);}
#else
#error radix mismatch
#endif
]=] "42\n")
check(conditional-and-undef [=[
#define EMPTY
#define FLAG 3
#if 1 || (1 / 0)
#if 0 && (1 / 0)
#error short circuit failed
#elif FLAG * 2 == 6 && (0x10 >> 1) == 8
#define SELECT 42
#else
#error wrong branch
#endif
#endif
#ifdef EMPTY
#undef EMPTY
#endif
#ifndef EMPTY
void main(){println(SELECT);}
#endif
]=] "42\n")
# The module name uses underscores so that the file mapping is unambiguous.
file(WRITE "${TEST_ROOT}/cycle_a.vas" "#define CYCLE_BEFORE 7\nimport cycle_b;\n#define CYCLE_AFTER 9\n")
file(WRITE "${TEST_ROOT}/cycle_b.vas" "import cycle_a;\n#if defined(CYCLE_AFTER)\n#error forward macro became visible\n#endif\n#define CYCLE_OTHER CYCLE_BEFORE\n")
check(cycles "import cycle_a;\nvoid main(){println(\"{} {} {}\", CYCLE_BEFORE, CYCLE_AFTER, CYCLE_OTHER);}" "7 9 7\n")
check(trivia-and-literals [=[
// import never_loaded;
/* #include <never_loaded.vas> */
#if 0
import missing;
#include <missing.vas>
#define POISON 1
#endif
#define TOKEN 7
void main(){println("TOKEN import missing;", 1); println(TOKEN);}
]=] "TOKEN import missing;\n7\n")
check(identical-redefinition "#define A(x) ((x)+1)\n#define A(x) (( x ) + 1)\nvoid main(){println(A(1));}" "2\n")
check(recursion-stops "#define SELF SELF\n#define FIRST SECOND\n#define SECOND FIRST\nint SELF=3; int FIRST=7;\nvoid main(){println(\"{} {}\", SELF, FIRST);}" "3 7\n")
file(WRITE "${TEST_ROOT}/conflict.vas" "#define FLAG 2\n")
reject(conflict "#define FLAG 1\nimport conflict;\nvoid main(){}" "Conflicting macro.*previous definition at.*main.vas:1")
reject(arity "import math;\nvoid main(){println(SUM(1));}" "Wrong number of arguments")
reject(unclosed-call "import math;\nvoid main(){println(SUM(1,2;}" "Unclosed function macro")
reject(unclosed-if "#if 1\nvoid main(){}" "Unclosed conditional")
reject(unmatched-else "#else\nvoid main(){}" "Unmatched")
reject(duplicate-else "#if 0\n#else\n#else\n#endif\nvoid main(){}" "Unmatched or duplicate")
reject(empty-define "#define\nvoid main(){}" "Expected a macro name")
reject(empty-if "#if\n#endif\nvoid main(){}" "Missing operand")
reject(division-by-zero "#if 1/0\n#endif\nvoid main(){}" "Division by zero")
reject(invalid-shift "#if 1 << 64\n#endif\nvoid main(){}" "Invalid shift")
reject(function-import "void main(){ import math; }" "Module imports must be at file scope")
reject(missing-module "import missing;\nvoid main(){}" "Failed to open script file")
reject(pasting "#define JOIN(a,b) a ## b\nvoid main(){}" "not supported")
reject(variadic "#define LOG(...) 1\nvoid main(){}" "Expected a macro parameter")
string(REPEAT "!" 140 deep_expression)
reject(expression-limit "#if ${deep_expression}1\n#endif\nvoid main(){}" "expression nesting exceeds")

# Engine diagnostics after expansion must point to the original byte column,
# including Unicode before the macro and before the later offending token.
file(WRITE "${TEST_ROOT}/main.vas" "#define LONG_NAME 1\nvoid main(){ int value = LONG_NAME; /*漢字*/ missing(); }\n")
execute_process(COMMAND "${VASBUILD}" --report=jsonl "${config}" "${TEST_ROOT}/main.vas" "${TEST_ROOT}/bad.vasbc"
    RESULT_VARIABLE result OUTPUT_VARIABLE report ERROR_VARIABLE err TIMEOUT 20 ENCODING UTF-8)
string(HEX "void main(){ int value = LONG_NAME; /*漢字*/ " prefix_hex)
string(LENGTH "${prefix_hex}" prefix_length)
math(EXPR expected_column "${prefix_length} / 2 + 1")
if(result EQUAL 0 OR NOT report MATCHES "\"row\":2,\"column\":${expected_column}")
    message(FATAL_ERROR "Original column mapping lost after macro expansion\n${report}\n${err}")
endif()

# Macro-generated invalid literals also use the invocation's original location.
reject(format-macro "#define BAD println(\"{}\")\nvoid main(){ BAD; }" "println: argument not found")

file(WRITE "${TEST_ROOT}/search-a/external.vas" "#define EXTERNAL 17\n")
file(WRITE "${TEST_ROOT}/search-b/external.vas" "#define EXTERNAL 19\n")
set(ENV{VAS_INCLUDE_PATH} "search-a")
check(search-root "#include <external.vas>\nvoid main(){println(EXTERNAL);}" "17\n")
if(WIN32)
    set(ENV{VAS_INCLUDE_PATH} "search-a;search-b")
else()
    set(ENV{VAS_INCLUDE_PATH} "search-a:search-b")
endif()
reject(ambiguity "import external;\nvoid main(){}" "Ambiguous module/include")
unset(ENV{VAS_INCLUDE_PATH})
