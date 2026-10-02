include("${CMAKE_CURRENT_LIST_DIR}/jsonl_helpers.cmake")

run_report(OFF output ON config.txt main.vas missing-directory/out.vasbc)
find_record(d diagnostic message "Failed to open output file for writing")
expect_json("${record_${d}}" missing-directory/out.vasbc section)
file(MAKE_DIRECTORY "${workspace}/directory.vas")
run_report(OFF load OFF config.txt directory.vas out.vasbc)
expect_count(section_loaded 0)
file(MAKE_DIRECTORY "${workspace}/config-directory")
run_report(OFF config OFF config-directory main.vas out.vasbc)
expect_count(section_loaded 0)

# /dev/full forces a buffered bytecode flush failure instead of an open error.
# It is Linux-specific; portable open/read failures above run everywhere.
if(UNIX AND EXISTS "/dev/full")
	# A failed first report write must abort before compiling or producing output.
	file(REMOVE "${workspace}/broken-transport.vasbc")
	execute_process(COMMAND "${VASBUILD}" --report=jsonl config.txt main.vas broken-transport.vasbc
		WORKING_DIRECTORY "${workspace}" RESULT_VARIABLE result OUTPUT_FILE /dev/full
		ERROR_VARIABLE err ENCODING NONE TIMEOUT 20)
	if("${result}" STREQUAL "0" OR EXISTS "${workspace}/broken-transport.vasbc")
		message(FATAL_ERROR "Failed report transport did not stop the build: ${result}")
	endif()

	run_report(OFF output ON config.txt main.vas /dev/full)
	find_record(d diagnostic message "Failed to write the bytecode")
	expect_json("${record_${d}}" /dev/full section)

	# A larger module also forces an fwrite failure before fclose, exercising
	# retained write errors rather than only a final buffered flush failure.
	set(large_source "void main() {}\n")
	foreach(i RANGE 1 600)
		string(APPEND large_source "int value${i}() { return ${i}; }\n")
	endforeach()
	file(WRITE "${workspace}/large.vas" "${large_source}")
	run_report(ON output ON config.txt large.vas large.vasbc)
	file(SIZE "${workspace}/large.vasbc" size)
	if(size LESS_EQUAL 8192)
		message(FATAL_ERROR "Large-bytecode fixture does not exceed a CRT buffer")
	endif()
	run_report(OFF output ON config.txt large.vas /dev/full)
	find_record(d diagnostic message "Failed to write the bytecode")
endif()

# Bytecode must not corrupt the JSONL transport when paths alias stdout.
if(UNIX AND EXISTS "/dev/stdout")
	run_report(OFF output ON config.txt main.vas /dev/stdout)
endif()
if(UNIX AND EXISTS "/proc/self/fd/1")
	run_report(OFF output ON config.txt main.vas /proc/self/fd/1)
endif()
set(capture "${workspace}/same-output.jsonl")
execute_process(COMMAND "${VASBUILD}" --report=jsonl config.txt main.vas "${capture}"
	WORKING_DIRECTORY "${workspace}" RESULT_VARIABLE result OUTPUT_FILE "${capture}"
	ERROR_VARIABLE err ENCODING NONE TIMEOUT 20)
if("${result}" STREQUAL "0")
	message(FATAL_ERROR "Aliased report/bytecode output unexpectedly succeeded")
endif()
expect_equal("${err}" "" "aliased stdout stderr")
file(READ "${capture}" out)
parse_report("${out}")
expect_json("${record_${report_count}}" OFF success)
expect_json("${record_${report_count}}" output phase)
expect_json("${record_${report_count}}" ON dependenciesComplete)
