include("${CMAKE_CURRENT_LIST_DIR}/jsonl_helpers.cmake")
file(MAKE_DIRECTORY "${workspace}/sub")
file(WRITE "${workspace}/main.vas" [=[
// #include "commented.vas"
/* #include "block-commented.vas" */
#if NEVER_DEFINED
#include "inactive.vas"
#endif
#include 'sub/first.vas'
#include "sub/./first.vas"
#include "sub/../empty.vas"
void main() { first(); }
]=])
file(WRITE "${workspace}/sub/first.vas" "#include \"second.vas\"\nvoid first() {}\n")
file(WRITE "${workspace}/sub/second.vas" "#include \"../main.vas\"\n")
file(WRITE "${workspace}/empty.vas" "")
run_report(ON output ON config.txt main.vas out.vasbc)
expect_count(section_loaded 4)
expect_count(include_attempt 5)
expect_count(include_result 5)
set(loaded 0)
set(skipped 0)
foreach(i RANGE 1 ${report_count})
	string(JSON type GET "${record_${i}}" type)
	if(type STREQUAL "include_result")
		string(JSON status GET "${record_${i}}" status)
		math(EXPR ${status} "${${status}} + 1")
	endif()
endforeach()
expect_equal("${loaded}" 3 "loaded include count")
expect_equal("${skipped}" 2 "skipped repeated/cyclic include count")
find_record(a include_attempt requested sub/first.vas)
expect_json("${record_${a}}" "${workspace}/main.vas" from)
expect_json("${record_${a}}" "${workspace}/sub/first.vas" resolved)
find_record(s section_loaded section "${workspace}/sub/first.vas")
find_record(n include_attempt requested second.vas)
find_record(r include_result attemptSeq "${a}")
if(NOT a LESS s OR NOT s LESS n OR NOT n LESS r)
	message(FATAL_ERROR "Include loaded/result ordering lost nested preprocessing semantics")
endif()

# A failed nested dependency retains already-read sections but stops discovery
# before later siblings. The enclosing read does not imply include success.
file(WRITE "${workspace}/sub/first.vas" "#include \"missing.vas\"\n")
file(WRITE "${workspace}/main.vas" "#include \"sub/first.vas\"\n#include \"never-visited.vas\"\nvoid main() {}\n")
run_report(OFF load OFF config.txt main.vas failed.vasbc)
expect_count(section_loaded 2)
expect_count(include_attempt 2)
expect_count(include_result 2)
find_record(a include_attempt requested sub/first.vas)
find_record(s section_loaded section "${workspace}/sub/first.vas")
find_record(r include_result attemptSeq "${a}")
expect_json("${record_${r}}" failed status)
if(NOT a LESS s OR NOT s LESS r)
	message(FATAL_ERROR "section_loaded must survive nested preprocessing failure")
endif()
find_record(a include_attempt requested missing.vas)
expect_json("${record_${a}}" "${workspace}/sub/first.vas" from)
expect_json("${record_${a}}" "${workspace}/sub/missing.vas" resolved)
find_record(r include_result attemptSeq "${a}")
expect_json("${record_${r}}" failed status)

file(WRITE "${workspace}/main.vas" "#include \"legacy.as\"\nvoid main() {}\n")
file(WRITE "${workspace}/legacy.as" "void legacy() {}\n")
run_report(OFF load OFF config.txt main.vas failed.vasbc)
expect_count(section_loaded 1)
find_record(a include_attempt requested legacy.as)
find_record(r include_result attemptSeq "${a}")
expect_json("${record_${r}}" rejected status)

# An unrecognized include syntax is left for compilation: completed discovery
# does not imply that syntactically invalid directives were accepted.
file(WRITE "${workspace}/main.vas" "#include bad\nvoid main() {}\n")
run_report(OFF compile ON config.txt main.vas failed.vasbc)
expect_count(section_loaded 1)
expect_count(include_attempt 0)

# A rejected pragma fails preprocessing after its bytes have been observed.
file(WRITE "${workspace}/main.vas" "#pragma unsupported\nvoid main() {}\n")
run_report(OFF load OFF config.txt main.vas failed.vasbc)
expect_count(section_loaded 1)
expect_count(include_attempt 0)
