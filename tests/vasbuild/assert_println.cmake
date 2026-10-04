cmake_minimum_required(VERSION 3.21)
file(MAKE_DIRECTORY "${TEST_ROOT}")
set(script "${SOURCE_ROOT}/tests/vasbuild/fixtures/println.vas")
foreach(config IN ITEMS
	"sdk/samples/asbuild/bin/config.txt"
	"tools/rider-plugin/src/main/resources/runtime/windows-x64/vasbuild.config.txt"
	"templates/rider/vas-starter/.vas/vasbuild.config.txt"
	"App1/.vas/vasbuild.config.txt")
	execute_process(COMMAND "${VASBUILD}" "${SOURCE_ROOT}/${config}" "${script}" "${TEST_ROOT}/println.vasbc"
		RESULT_VARIABLE result OUTPUT_VARIABLE out ERROR_VARIABLE err ENCODING UTF-8 TIMEOUT 20)
	if(NOT "${result}" STREQUAL "0")
		message(FATAL_ERROR "println build failed for ${config} (${result})\n${out}\n${err}")
	endif()
endforeach()

# Compare hex bytes to retain embedded NULs and verify exactly one added newline.
execute_process(COMMAND "${VASRUN}" "${script}" RESULT_VARIABLE result
	OUTPUT_FILE "${TEST_ROOT}/output.txt" ERROR_VARIABLE err TIMEOUT 20)
set(expected "72\nresult = 72\nABC + 72 ASDA\nABC + 72 ASDA\nhex=0048, pi=3.14\nworld hello world\n{} {data}\n|    x|y    |\n0048 3.142\n漢字😀 文字😀\nline\n\n\n\n")
string(HEX "${expected}" expected_hex)
string(APPEND expected_hex "6100620a")
set(tail "0 1 2 3 4 5 6 7 8 9 10 11\n-128\n255\n-32768\n65535\n-2147483648\n4294967295\n-9223372036854775808\n18446744073709551615\n1.25\n-2.5\ntrue\nfalse\n-128|255|-32768|65535|-2147483648|4294967295|-9223372036854775808|18446744073709551615|1.25|-2.5|true\nprint still has no newline|ABC + 72 ASDA|ABC + 72 ASDA|hex=0048, pi=3.14|{} {data}|漢字😀 文字😀")
string(HEX "${tail}" tail_hex)
string(APPEND expected_hex "${tail_hex}")
file(READ "${TEST_ROOT}/output.txt" actual_hex HEX)
# Text-mode stdout translates logical LF to CRLF on Windows.
if(WIN32)
	string(REPLACE "0d0a" "0a" actual_hex "${actual_hex}")
endif()
if(NOT "${result}" STREQUAL "0" OR NOT "${actual_hex}" STREQUAL "${expected_hex}")
	message(FATAL_ERROR "println output mismatch (${result})\nExpected hex: ${expected_hex}\nActual hex: ${actual_hex}\n${err}")
endif()

# Literal syntax, argument indices and type errors must fail during compilation,
# including code that never executes. Test both the runner and exported configs.
set(error_calls
	"FUNCTION(\"partial {\")"
	"FUNCTION(\"partial }\")"
	"FUNCTION(\"partial {}\")"
	"FUNCTION(\"partial {1}\", 72)"
	"FUNCTION(\"partial {} {0}\", 72)"
	"FUNCTION(\"partial {:d}\", \"text\")"
	"FUNCTION(\"partial {:q}\", 72)"
	"FUNCTION(\"partial {:{}d}\", 72)"
	"FUNCTION(\"partial {:{}d}\", 72, true)"
	"FUNCTION(\"partial {0:.{1}f}\", 1.0, \"bad\")"
	"FUNCTION(\"partial {name}\", 72)"
	"FUNCTION(\"partial {}\", array<int>())"
	"FUNCTION((\"partial \" \"{}\"))")
foreach(call IN LISTS error_calls)
	foreach(function IN ITEMS print println)
		string(REPLACE "FUNCTION" "${function}" actual_call "${call}")
		file(WRITE "${TEST_ROOT}/invalid.vas" "void main() { if(false) { ${actual_call}; } }\n")
		execute_process(COMMAND "${VASBUILD}" "${SOURCE_ROOT}/sdk/samples/asbuild/bin/config.txt"
			"${TEST_ROOT}/invalid.vas" "${TEST_ROOT}/invalid.vasbc"
			RESULT_VARIABLE result OUTPUT_VARIABLE out ERROR_VARIABLE err ENCODING UTF-8 TIMEOUT 20)
		if("${result}" STREQUAL "0" OR NOT "${out}${err}" MATCHES "${function}:")
			message(FATAL_ERROR "Invalid literal compiled: ${actual_call} (${result})\n${out}\n${err}")
		endif()
		execute_process(COMMAND "${VASRUN}" "${TEST_ROOT}/invalid.vas"
			RESULT_VARIABLE result OUTPUT_VARIABLE out ERROR_VARIABLE err ENCODING UTF-8 TIMEOUT 20)
		if("${result}" STREQUAL "0" OR NOT "${out}${err}" MATCHES "ERR.*${function}:" OR out MATCHES "script exception")
			message(FATAL_ERROR "Runner did not reject literal at build time: ${actual_call}\n${out}\n${err}")
		endif()
	endforeach()
endforeach()

# Dynamic text is checked at runtime. Invalid values (e.g. negative width) also
# remain runtime errors; neither path may emit a partially formatted message.
set(runtime_calls
	"string f = \"partial {\"\; FUNCTION(f)"
	"string f = \"partial {}\"\; FUNCTION(f)"
	"string f = \"partial {:d}\"\; FUNCTION(f, \"bad\")"
	"FUNCTION(\"partial {:{}d}\", 72, -1)")
foreach(call IN LISTS runtime_calls)
	foreach(function IN ITEMS print println)
		string(REPLACE "FUNCTION" "${function}" actual_call "${call}")
		file(WRITE "${TEST_ROOT}/dynamic.vas" "void main() { ${actual_call}; print(\"unreachable marker\"); }\n")
		execute_process(COMMAND "${VASBUILD}" "${SOURCE_ROOT}/sdk/samples/asbuild/bin/config.txt"
			"${TEST_ROOT}/dynamic.vas" "${TEST_ROOT}/dynamic.vasbc"
			RESULT_VARIABLE result OUTPUT_VARIABLE out ERROR_VARIABLE err ENCODING UTF-8 TIMEOUT 20)
		if(NOT "${result}" STREQUAL "0")
			message(FATAL_ERROR "Dynamic/value error incorrectly rejected at compilation\n${out}\n${err}")
		endif()
		execute_process(COMMAND "${VASRUN}" "${TEST_ROOT}/dynamic.vas"
			RESULT_VARIABLE result OUTPUT_VARIABLE out ERROR_VARIABLE err ENCODING UTF-8 TIMEOUT 20)
		if("${result}" STREQUAL "0" OR NOT out MATCHES "${function}:" OR out MATCHES "partial |unreachable marker")
			message(FATAL_ERROR "Dynamic format did not fail cleanly\n${out}\n${err}")
		endif()
	endforeach()
endforeach()

# Valid literal forms, dynamic values, extra unused arguments, and fallback sizes.
file(WRITE "${TEST_ROOT}/valid.vas" "void main() {
"
	"println(\"no placeholders\", 123);
"
	"println(\"{0} {0}\", 7);
"
	"println(\"{:{}.{}f}\", 1.25, 8, 2);
"
	"println(format: \"ok\");
"
	"string f = \"{}\"; println(f, 7);
"
	"string s; s.resize(600); for(uint i=0; i<600; ++i) s[i]=120; println(\"{}\", s);
"
	"println(\"{16}\", 0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16);
"
	"}\n")
execute_process(COMMAND "${VASBUILD}" "${SOURCE_ROOT}/sdk/samples/asbuild/bin/config.txt"
	"${TEST_ROOT}/valid.vas" "${TEST_ROOT}/valid.vasbc"
	RESULT_VARIABLE result OUTPUT_VARIABLE out ERROR_VARIABLE err ENCODING UTF-8 TIMEOUT 20)
if(NOT "${result}" STREQUAL "0")
	message(FATAL_ERROR "Valid format rejected\n${out}\n${err}")
endif()
execute_process(COMMAND "${VASRUN}" "${TEST_ROOT}/valid.vas"
	RESULT_VARIABLE result OUTPUT_VARIABLE out ERROR_VARIABLE err ENCODING UTF-8 TIMEOUT 20)
string(REPEAT "x" 600 long_text)
string(REPLACE "\r\n" "\n" out "${out}")
if(NOT "${result}" STREQUAL "0" OR NOT out STREQUAL "no placeholders\n7 7\n    1.25\nok\n7\n${long_text}\n16\n")
	message(FATAL_ERROR "Valid/dynamic/large format mismatch\n${out}\n${err}")
endif()
