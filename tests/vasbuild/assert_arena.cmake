cmake_minimum_required(VERSION 3.21)
file(MAKE_DIRECTORY "${TEST_ROOT}")
set(entry "${SOURCE_ROOT}/examples/arena/main.vas")
set(config "${SOURCE_ROOT}/sdk/samples/asbuild/bin/config.txt")
# This uses arrays, strings and script classes: the same source must compile
# against each shipped API config AND run against the actual registered host.
foreach(host IN ITEMS "sdk/samples/asbuild/bin/config.txt"
    "App1/.vas/vasbuild.config.txt" "templates/rider/vas-starter/.vas/vasbuild.config.txt"
    "tools/rider-plugin/src/main/resources/runtime/windows-x64/vasbuild.config.txt")
    execute_process(COMMAND "${VASBUILD}" "${SOURCE_ROOT}/${host}" "${entry}" "${TEST_ROOT}/arena.vasbc"
        RESULT_VARIABLE status OUTPUT_VARIABLE out ERROR_VARIABLE err ENCODING UTF-8 TIMEOUT 20)
    if(NOT status EQUAL 0)
        message(FATAL_ERROR "Arena failed to compile against ${host}\n${out}\n${err}")
    endif()
endforeach()
function(run expected_status expected)
    execute_process(COMMAND "${VASRUN}" "${entry}" ${ARGN}
        RESULT_VARIABLE status OUTPUT_VARIABLE out ERROR_VARIABLE err ENCODING UTF-8 TIMEOUT 20)
    string(REPLACE "\r\n" "\n" out "${out}")
    if(NOT status EQUAL expected_status OR NOT out STREQUAL expected)
        message(FATAL_ERROR "Arena runtime mismatch for ${ARGN} (${status})\nExpected:\n${expected}\nActual:\n${out}\n${err}")
    endif()
endfunction()
file(READ "${SOURCE_ROOT}/examples/arena/expected-quiet.txt" quiet)
string(REPLACE "\r\n" "\n" quiet "${quiet}")
run(0 "${quiet}" --quiet)
run(0 "SELF_TEST passed=23 failed=0\n" --self-test)
set(batch "BATCH count=2000 wins=654 losses=1346 draws=0 checksum=9933226\n")
run(0 "${batch}" --batch 2000)
run(0 "BATCH count=1 wins=0 losses=1 draws=0 checksum=16\n" --batch 1)
set(usage "用法：vasrun main.vas [--quiet | --self-test | --batch 1..100000]\n")
foreach(count IN ITEMS 0 -1 100001 2oops "")
    run(2 "${usage}" --batch "${count}")
endforeach()
run(2 "${usage}" --unknown)
run(2 "${usage}" --batch)

# All imported source files must remain in the native dependency report.
execute_process(COMMAND "${VASBUILD}" --report=jsonl "${config}" "${entry}" "${TEST_ROOT}/arena.vasbc"
    RESULT_VARIABLE status OUTPUT_VARIABLE report ERROR_VARIABLE err ENCODING UTF-8 TIMEOUT 20)
string(REGEX MATCHALL "\"type\":\"section_loaded\"" sections "${report}")
list(LENGTH sections count)
if(NOT status EQUAL 0 OR NOT count EQUAL 9 OR NOT report MATCHES "\"dependenciesComplete\":true")
    message(FATAL_ERROR "Arena dependency closure changed\n${report}\n${err}")
endif()

set(ENV{VAS_INCLUDE_PATH} "${SOURCE_ROOT}/examples/arena")
# Compile-time logging elimination must not change simulation state or RNG use.
file(WRITE "${TEST_ROOT}/silent.vas" "#define VAS_ARENA_VERBOSE 0\n#include \"${entry}\"\n")
execute_process(COMMAND "${VASBUILD}" "${config}" "${TEST_ROOT}/silent.vas" "${TEST_ROOT}/silent.vasbc"
    RESULT_VARIABLE status OUTPUT_VARIABLE out ERROR_VARIABLE err ENCODING UTF-8 TIMEOUT 20)
if(NOT status EQUAL 0)
    message(FATAL_ERROR "Logging-off variant compilation failed\n${out}\n${err}")
endif()
execute_process(COMMAND "${VASRUN}" "${TEST_ROOT}/silent.vas" --batch 2000
    RESULT_VARIABLE status OUTPUT_VARIABLE out ERROR_VARIABLE err ENCODING UTF-8 TIMEOUT 20)
string(REPLACE "\r\n" "\n" out "${out}")
if(NOT status EQUAL 0 OR NOT out STREQUAL batch)
    message(FATAL_ERROR "Logging-off variant changed combat results\n${out}\n${err}")
endif()

# Deliberately change configuration without editing the executable example.
foreach(override IN ITEMS "VAS_ARENA_MAX_ROUNDS 0" "VAS_ARENA_VERBOSE 2")
    file(WRITE "${TEST_ROOT}/invalid.vas" "#define ${override}\n#include \"${entry}\"\n")
    execute_process(COMMAND "${VASBUILD}" "${config}" "${TEST_ROOT}/invalid.vas" "${TEST_ROOT}/invalid.vasbc"
        RESULT_VARIABLE status OUTPUT_VARIABLE out ERROR_VARIABLE err ENCODING UTF-8 TIMEOUT 20)
    if(status EQUAL 0 OR NOT "${out}${err}" MATCHES "#error")
        message(FATAL_ERROR "Invalid arena configuration was not rejected\n${out}\n${err}")
    endif()
endforeach()
