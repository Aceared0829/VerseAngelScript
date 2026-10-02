cmake_minimum_required(VERSION 3.21)

foreach(required_variable VASBUILD VASRUN TEST_ROOT MODE)
	if(NOT DEFINED ${required_variable})
		message(FATAL_ERROR "Missing -D${required_variable}=... for the Unicode path test")
	endif()
endforeach()

# Deliberately use both BMP and supplementary-plane characters. These paths
# must survive Windows UTF-16 argv, UTF-8 includes and redirected diagnostics.
set(workspace "${TEST_ROOT}/工程 文😀 with spaces")
set(config "config 設定😀/host interface 文.txt")
set(entry "source scripts/入口 😀.vas")
set(runner "source scripts/実行 😀.vas")
set(include "source scripts/include 文😀/shared 函数😀.vas")
set(output "output 字節😀/compiled 文😀.vasbc")
file(MAKE_DIRECTORY "${workspace}/config 設定😀"
	"${workspace}/source scripts/include 文😀" "${workspace}/output 字節😀")
string(REPEAT "// Padding across the native read buffer\r\n" 150 config_padding)
set(config_contents "${config_padding}// Host interface 文😀\r\ntypedef ConfiguredInt \"int\"\r\n")
file(WRITE "${workspace}/${config}" "${config_contents}")
file(WRITE "${workspace}/${include}" "int answer() { return 42; }\n")
file(WRITE "${workspace}/${entry}" "#include \"include 文😀/shared 函数😀.vas\"\nvoid main() { ConfiguredInt value = answer(); }\n")
file(WRITE "${workspace}/cwd-marker.txt" "caller cwd preserved")
file(WRITE "${workspace}/${runner}" [=[
#include "include 文😀/shared 函数😀.vas"
int main() {
  array<string>@ args = getCommandLineArgs();
  if (args.length() != 1 || args[0] != "argument 文😀 with spaces") return 21;
  file marker;
  if (marker.open("cwd-marker.txt", "r") < 0) return 22;
  if (marker.readLine() != "caller cwd preserved") return 23;
  if (answer() != 42) return 24;
  print("Unicode path runner succeeded\n");
  return 0;
}
]=])

function(expect_success label)
	execute_process(COMMAND ${ARGN} WORKING_DIRECTORY "${workspace}"
		RESULT_VARIABLE result OUTPUT_VARIABLE out ERROR_VARIABLE err ENCODING UTF-8 TIMEOUT 20)
	if(NOT "${result}" STREQUAL "0")
		message(FATAL_ERROR "${label} failed (${result})\nstdout:\n${out}\nstderr:\n${err}")
	endif()
	set(last_stdout "${out}" PARENT_SCOPE)
endfunction()

function(expect_failure label expected_path expected_message)
	execute_process(COMMAND ${ARGN} WORKING_DIRECTORY "${workspace}"
		RESULT_VARIABLE result OUTPUT_VARIABLE out ERROR_VARIABLE err ENCODING UTF-8 TIMEOUT 20)
	if("${result}" STREQUAL "0")
		message(FATAL_ERROR "${label} unexpectedly succeeded\n${out}\n${err}")
	endif()
	string(REPLACE "\\" "/" log "${err}")
	string(REPLACE "\\" "/" expected_path "${expected_path}")
	string(FIND "${log}" "${expected_path} (" location)
	string(FIND "${log}" "${expected_message}" message_location)
	if(location LESS 0 OR message_location LESS 0)
		message(FATAL_ERROR "${label} lost its UTF-8 path or failed for the wrong reason (${result})\nstdout:\n${out}\nstderr:\n${err}\nExpected: ${expected_path}, ${expected_message}")
	endif()
	set(last_stderr "${log}" PARENT_SCOPE)
endfunction()

file(REMOVE "${workspace}/${output}")
if(MODE STREQUAL "absolute" OR MODE STREQUAL "relative")
	set(prefix "")
	if(MODE STREQUAL "absolute")
		set(prefix "${workspace}/")
	endif()
	expect_success("${MODE} Unicode build"
		"${VASBUILD}" "${prefix}${config}" "${prefix}${entry}" "${prefix}${output}")
	if(NOT EXISTS "${workspace}/${output}")
		message(FATAL_ERROR "Missing bytecode at the exact Unicode output path")
	endif()
	file(SIZE "${workspace}/${output}" bytecode_size)
	if(bytecode_size EQUAL 0)
		message(FATAL_ERROR "Unicode bytecode output is empty")
	endif()
	expect_success("${MODE} Unicode run"
		"${VASRUN}" "${prefix}${runner}" "argument 文😀 with spaces")
	if(NOT last_stdout MATCHES "Unicode path runner succeeded")
		message(FATAL_ERROR "Runner did not execute the script: ${last_stdout}")
	endif()
elseif(MODE STREQUAL "failures")
	file(WRITE "${workspace}/${config}" "typedef Broken \"unknown type\"\n")
	expect_failure("invalid Unicode config" "${config}" "Failed to register typedef"
		"${VASBUILD}" "${config}" "${entry}" "${output}")
	file(WRITE "${workspace}/${config}" "${config_contents}")

	set(missing_config "config 設定😀/missing 設定😀.txt")
	expect_failure("missing config" "${missing_config}" "Failed to open config file"
		"${VASBUILD}" "${missing_config}" "${entry}" "${output}")
	string(FIND "${last_stderr}" "${workspace}" cwd_location)
	if(cwd_location LESS 0)
		message(FATAL_ERROR "Config failure lost the Unicode working directory: ${last_stderr}")
	endif()

	# Exceed the old 256-byte diagnostic buffer without requiring a Windows
	# long-path policy: these BMP characters use three UTF-8 bytes each.
	string(REPEAT "文" 80 long_component)
	set(original_workspace "${workspace}")
	set(workspace "${workspace}/${long_component}")
	file(MAKE_DIRECTORY "${workspace}")
	expect_failure("long Unicode working directory" "${missing_config}" "Failed to open config file"
		"${VASBUILD}" "${missing_config}" "${entry}" "${output}")
	string(FIND "${last_stderr}" "${workspace}" cwd_location)
	if(cwd_location LESS 0)
		message(FATAL_ERROR "Long config failure truncated the working directory: ${last_stderr}")
	endif()
	set(workspace "${original_workspace}")

	set(missing_entry "source scripts/missing 入口😀.vas")
	foreach(tool "${VASBUILD}" "${VASRUN}")
		set(arguments "${missing_entry}")
		if(tool STREQUAL VASBUILD)
			set(arguments "${config}" "${missing_entry}" "${output}")
		endif()
		expect_failure("missing entry" "${workspace}/${missing_entry}" "Failed to open script file"
			"${tool}" ${arguments})
	endforeach()

	set(missing_output "missing 出力😀/compiled 文😀.vasbc")
	expect_failure("missing output directory" "${missing_output}" "Failed to open output file for writing"
		"${VASBUILD}" "${config}" "${entry}" "${missing_output}")

	# A real compiler error in a Unicode include must stay on that include's
	# section, not turn into a misleading file-not-found diagnostic.
	file(WRITE "${workspace}/${include}" "int answer() { return ; }\n")
	expect_failure("build include error" "${workspace}/${include}" "Must return a value"
		"${VASBUILD}" "${config}" "${entry}" "${output}")
	expect_failure("run include error" "${workspace}/${include}" "Must return a value"
		"${VASRUN}" "${runner}")

	file(REMOVE "${workspace}/${include}")
	expect_failure("missing include" "${workspace}/${include}" "Failed to open script file"
		"${VASBUILD}" "${config}" "${entry}" "${output}")

	set(legacy "source scripts/legacy 旧😀.as")
	file(WRITE "${workspace}/${legacy}" "void main() {}\n")
	expect_failure("legacy Unicode entry" "${legacy}" "VAS source files must use the '.vas' extension"
		"${VASBUILD}" "${config}" "${legacy}" "${output}")
	expect_failure("legacy Unicode runner entry" "${legacy}" "VAS source files must use the '.vas' extension"
		"${VASRUN}" "${legacy}")

	if(EXISTS "${workspace}/${output}" OR EXISTS "${workspace}/${missing_output}")
		message(FATAL_ERROR "Failed compilations unexpectedly wrote bytecode")
	endif()
else()
	message(FATAL_ERROR "Unknown Unicode path test mode: ${MODE}")
endif()
