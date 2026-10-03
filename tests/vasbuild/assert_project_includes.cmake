include("${CMAKE_CURRENT_LIST_DIR}/project_helpers.cmake")
file(MAKE_DIRECTORY "${project_root}/src/sub")
file(WRITE "${project_root}/host/default.txt" "// Empty host API\n")
file(WRITE "${project_root}/src/main.vas" [=[
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
file(WRITE "${project_root}/src/sub/first.vas" "#include \"second.vas\"\nvoid first() {}\n")
file(WRITE "${project_root}/src/sub/second.vas" "#include \"../main.vas\"\n")
file(WRITE "${project_root}/src/empty.vas" "")
run_report(ON output ON --project "${project_file}" --unit main)
expect_count(section_loaded 4)
expect_count(include_attempt 5)
expect_count(include_result 5)
set(project_report "${report_text}")
set(project_count "${report_count}")
foreach(i RANGE 1 ${report_count})
	set(project_record_${i} "${record_${i}}")
endforeach()
run_report(ON output ON "${project_root}/host/default.txt" "${project_root}/src/main.vas" "${project_root}/build/positional.vasbc")
expect_equal("${report_count}" "${project_count}" "project/positional native event count")
# Compare only observed loader records and their sequence/correlation fields.
# This catches a project implementation that scans source text speculatively.
foreach(i RANGE 1 ${report_count})
	string(JSON type GET "${record_${i}}" type)
	if(type MATCHES "^(section_loaded|include_attempt|include_result)$")
		expect_equal("${record_${i}}" "${project_record_${i}}" "native loader parity event ${i}")
	endif()
endforeach()

# Include traversal retains the real loader's existing relative-path behavior.
# Only manifest entry/config/output paths are constrained to portable relatives.
file(WRITE "${workspace}/outside.vas" "void outside() {}\n")
file(WRITE "${project_root}/src/main.vas" "#include \"../../outside.vas\"\nvoid main() { outside(); }\n")
run_report(ON output ON --project "${project_file}" --unit main)
find_record(a include_attempt requested ../../outside.vas)
expect_json("${record_${a}}" "${workspace}/outside.vas" resolved)
find_record(s section_loaded section "${workspace}/outside.vas")

# A failed descendant keeps partial discovery and never creates output parents.
write_project("${project_file}" src/main.vas host/default.txt new/deep/failure.vasbc)
file(WRITE "${project_root}/src/main.vas" "#include \"sub/first.vas\"\n#include \"not-visited.vas\"\nvoid main() {}\n")
file(WRITE "${project_root}/src/sub/first.vas" "#include \"missing.vas\"\n")
run_report(OFF load OFF --project "${project_file}" --unit main)
expect_count(section_loaded 2)
expect_count(include_attempt 2)
find_record(a include_attempt requested sub/first.vas)
find_record(r include_result attemptSeq "${a}")
expect_json("${record_${r}}" failed status)
expect_no_path("${project_root}/new")

file(WRITE "${project_root}/src/main.vas" "#include \"legacy.as\"\nvoid main() {}\n")
file(WRITE "${project_root}/src/legacy.as" "")
run_report(OFF load OFF --project "${project_file}" --unit main)
find_record(a include_attempt requested legacy.as)
find_record(r include_result attemptSeq "${a}")
expect_json("${record_${r}}" rejected status)
expect_no_path("${project_root}/new")
