cmake_minimum_required(VERSION 3.21)

file(MAKE_DIRECTORY "${TEST_ROOT}")
set(script "${SOURCE_ROOT}/tests/vasbuild/fixtures/print_primitives.vas")
# Every shipped host configuration must accept the same primitive calls as vasrun.
foreach(config IN ITEMS
	"sdk/samples/asbuild/bin/config.txt"
	"tools/rider-plugin/src/main/resources/runtime/windows-x64/vasbuild.config.txt"
	"templates/rider/vas-starter/.vas/vasbuild.config.txt"
	"App1/.vas/vasbuild.config.txt")
	execute_process(COMMAND "${VASBUILD}" "${SOURCE_ROOT}/${config}" "${script}" "${TEST_ROOT}/primitives.vasbc"
		RESULT_VARIABLE result OUTPUT_VARIABLE out ERROR_VARIABLE err ENCODING UTF-8 TIMEOUT 20)
	if(NOT "${result}" STREQUAL "0")
		message(FATAL_ERROR "Primitive print build failed for ${config} (${result})\n${out}\n${err}")
	endif()
endforeach()

execute_process(COMMAND "${VASRUN}" "${script}"
	RESULT_VARIABLE result OUTPUT_VARIABLE out ERROR_VARIABLE err ENCODING UTF-8 TIMEOUT 20)
set(expected "72|72|-2147483648|4294967295|-9223372036854775808|18446744073709551615|1.25|-2.5|true|false|-128|255|-32768|65535|72|string\n")
if(NOT "${result}" STREQUAL "0" OR NOT "${out}" STREQUAL "${expected}")
	message(FATAL_ERROR "Primitive print run failed (${result})\nExpected: ${expected}\nActual: ${out}\n${err}")
endif()
