include("${CMAKE_CURRENT_LIST_DIR}/jsonl_helpers.cmake")

# Opt-in mode requires exactly the tuple, with the flag first.
run_report(OFF arguments OFF)
expect_count(diagnostic 1)
foreach(field config entry output)
	expect_type("${record_1}" NULL ${field})
endforeach()
run_report(OFF arguments OFF config.txt)
run_report(OFF arguments OFF config.txt main.vas)
run_report(OFF arguments OFF config.txt main.vas output.vasbc unexpected)
expect_count(section_loaded 0)

# Empty arguments must be passed directly: list expansion drops empty items.
execute_process(COMMAND "${VASBUILD}" --report=jsonl "" "" ""
	WORKING_DIRECTORY "${workspace}" RESULT_VARIABLE result
	OUTPUT_VARIABLE out ERROR_VARIABLE err ENCODING NONE TIMEOUT 20)
if("${result}" STREQUAL "0")
	message(FATAL_ERROR "Empty arguments unexpectedly succeeded")
endif()
expect_equal("${err}" "" "empty arguments stderr")
parse_report("${out}")
expect_json("${record_${report_count}}" OFF success)
expect_json("${record_${report_count}}" arguments phase)
foreach(field config entry output)
	expect_type("${record_1}" NULL ${field})
endforeach()

# Legacy callers keep text diagnostics, exit codes and ignored trailing args.
execute_process(COMMAND "${VASBUILD}" config.txt main.vas plain.vasbc ignored --report=jsonl
	WORKING_DIRECTORY "${workspace}" RESULT_VARIABLE result
	OUTPUT_VARIABLE out ERROR_VARIABLE err ENCODING UTF-8 TIMEOUT 20)
expect_equal("${result}" 0 "legacy build exit status")
expect_equal("${err}" "" "legacy success stderr")
if(NOT out MATCHES "INFO.*Configuration successfully registered" OR out MATCHES "\"protocol\"")
	message(FATAL_ERROR "Legacy stdout changed: ${out}")
endif()
run_report(ON output ON config.txt main.vas report.vasbc)
file(SHA256 "${workspace}/plain.vasbc" plain_hash)
file(SHA256 "${workspace}/report.vasbc" report_hash)
expect_equal("${plain_hash}" "${report_hash}" "legacy/report bytecode")
execute_process(COMMAND "${VASBUILD}" WORKING_DIRECTORY "${workspace}"
	RESULT_VARIABLE result OUTPUT_VARIABLE out ERROR_VARIABLE err ENCODING UTF-8 TIMEOUT 20)
if("${result}" STREQUAL "0" OR NOT out MATCHES "Usage:" OR out MATCHES "\"protocol\"")
	message(FATAL_ERROR "Legacy usage changed: ${result}\n${out}\n${err}")
endif()
file(WRITE "${workspace}/bad.vas" "void main() { missing(); }\n")
execute_process(COMMAND "${VASBUILD}" config.txt bad.vas failed.vasbc
	WORKING_DIRECTORY "${workspace}" RESULT_VARIABLE result
	OUTPUT_VARIABLE out ERROR_VARIABLE err ENCODING UTF-8 TIMEOUT 20)
if("${result}" STREQUAL "0" OR NOT err MATCHES "ERR.*No matching symbol" OR out MATCHES "\"protocol\"")
	message(FATAL_ERROR "Legacy failure diagnostics changed: ${result}\n${out}\n${err}")
endif()
