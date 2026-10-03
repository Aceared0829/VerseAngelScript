cmake_minimum_required(VERSION 3.21)

project(VerseAngelScript LANGUAGES C CXX)

include(CTest)

function(vas_enable_cxx23 target)
	if(MSVC)
		# Keep the C++23 selection explicit so VAS does not silently fall back
		# to /std:c++latest.
		target_compile_options(${target} PRIVATE
			"$<$<COMPILE_LANGUAGE:CXX>:/std:c++23preview>"
			"$<$<COMPILE_LANGUAGE:CXX>:/permissive->"
			"$<$<COMPILE_LANGUAGE:CXX>:/Zc:__cplusplus>"
			"$<$<COMPILE_LANGUAGE:CXX>:/utf-8>"
		)
	else()
		set_target_properties(${target} PROPERTIES
			CXX_STANDARD 23
			CXX_STANDARD_REQUIRED ON
			CXX_EXTENSIONS OFF
		)
	endif()
endfunction()

add_subdirectory(sdk/angelscript/projects/cmake sdk/angelscript-build)
vas_enable_cxx23(angelscript)

if(MSVC AND NOT AS_NO_EXCEPTIONS)
	target_compile_options(angelscript PRIVATE "$<$<COMPILE_LANGUAGE:CXX>:/EHsc>")
endif()

add_executable(vasbuild
	sdk/samples/asbuild/source/main.cpp
	sdk/samples/asbuild/source/vas_project.cpp
	sdk/add_on/scriptbuilder/scriptbuilder.cpp
	sdk/add_on/scripthelper/scripthelper.cpp
)

target_link_libraries(vasbuild PRIVATE angelscript)
vas_enable_cxx23(vasbuild)

if(MSVC)
	target_compile_options(vasbuild PRIVATE "$<$<COMPILE_LANGUAGE:CXX>:/EHsc>")
endif()

add_executable(vasrun
	sdk/samples/asrun/source/main.cpp
	sdk/add_on/contextmgr/contextmgr.cpp
	sdk/add_on/datetime/datetime.cpp
	sdk/add_on/debugger/debugger.cpp
	sdk/add_on/scriptarray/scriptarray.cpp
	sdk/add_on/scriptbuilder/scriptbuilder.cpp
	sdk/add_on/scriptdictionary/scriptdictionary.cpp
	sdk/add_on/scriptfile/scriptfile.cpp
	sdk/add_on/scriptfile/scriptfilesystem.cpp
	sdk/add_on/scripthelper/scripthelper.cpp
	sdk/add_on/scriptsocket/scriptsocket.cpp
	sdk/add_on/scriptstdstring/scriptstdstring.cpp
	sdk/add_on/scriptstdstring/scriptstdstring_utils.cpp
)

target_link_libraries(vasrun PRIVATE angelscript)
if(WIN32)
	# MinGW does not process the socket add-on's MSVC pragma comment(lib).
	target_link_libraries(vasrun PRIVATE ws2_32)
endif()

vas_enable_cxx23(vasrun)

if(MSVC)
	target_compile_options(vasrun PRIVATE "$<$<COMPILE_LANGUAGE:CXX>:/EHsc>")
endif()

if(MINGW)
	# The Windows CRT must dispatch to wmain rather than its ANSI main adapter.
	target_link_options(vasbuild PRIVATE -municode)
	target_link_options(vasrun PRIVATE -municode)
endif()

if(BUILD_TESTING)
	# An independent native argv receiver for Rider transport tests. The Windows
	# java.exe launcher narrows its arguments through the active ANSI code page;
	# wmain is required to verify the compiler's actual Unicode argument boundary.
	add_executable(vas_rider_argv_fixture EXCLUDE_FROM_ALL tests/vasbuild/rider_argv_fixture.cpp)
	vas_enable_cxx23(vas_rider_argv_fixture)
	if(MSVC)
		target_compile_options(vas_rider_argv_fixture PRIVATE "$<$<COMPILE_LANGUAGE:CXX>:/EHsc>")
	endif()
	if(MINGW)
		target_link_options(vas_rider_argv_fixture PRIVATE -municode)
	endif()

	foreach(case descriptor validation build safety includes)
		add_test(NAME vasbuild_project_${case}
			COMMAND ${CMAKE_COMMAND}
				"-DVASBUILD=$<TARGET_FILE:vasbuild>"
				"-DTEST_ROOT=${CMAKE_CURRENT_BINARY_DIR}/project-${case}"
				-P "${CMAKE_CURRENT_SOURCE_DIR}/tests/vasbuild/assert_project_${case}.cmake")
		set_tests_properties(vasbuild_project_${case} PROPERTIES TIMEOUT 60)
	endforeach()
	# Parse each physical JSONL record independently with CMake's JSON parser.
	# This keeps conformance tests native on MSVC, MinGW, Linux and macOS.
	set(VAS_JSONL_CASES arguments diagnostics includes paths io source_digest)
	if(UNIX)
		list(APPEND VAS_JSONL_CASES invalid_utf8)
	endif()
	foreach(case IN LISTS VAS_JSONL_CASES)
		add_test(
			NAME vasbuild_jsonl_${case}
			COMMAND ${CMAKE_COMMAND}
				"-DVASBUILD=$<TARGET_FILE:vasbuild>"
				"-DSOURCE_DIGEST_TEST=$<TARGET_FILE:vas_source_digest_test>"
				"-DTEST_ROOT=${CMAKE_CURRENT_BINARY_DIR}/jsonl-${case}"
				-P "${CMAKE_CURRENT_SOURCE_DIR}/tests/vasbuild/assert_jsonl_${case}.cmake"
		)
		set_tests_properties(vasbuild_jsonl_${case} PROPERTIES TIMEOUT 60)
	endforeach()

	add_executable(vas_source_digest_test
		tests/vasbuild/test_source_digest.cpp
		sdk/add_on/scriptbuilder/scriptbuilder.cpp)
	target_link_libraries(vas_source_digest_test PRIVATE angelscript)
	vas_enable_cxx23(vas_source_digest_test)
	add_test(NAME vas_source_digest
		COMMAND vas_source_digest_test source-digest-test.vas)
	# Keep test argv ASCII even when a Windows build directory contains Unicode;
	# the production loader resolves it using the native working directory.
	set_tests_properties(vas_source_digest PROPERTIES TIMEOUT 60
		WORKING_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}")

	if(WIN32)
		add_executable(vas_windows_arguments_test tests/vasbuild/test_windows_arguments.cpp)
		vas_enable_cxx23(vas_windows_arguments_test)
		if(MSVC)
			target_compile_options(vas_windows_arguments_test PRIVATE "$<$<COMPILE_LANGUAGE:CXX>:/EHsc>")
		endif()
		add_test(NAME vas_windows_arguments COMMAND vas_windows_arguments_test)
	endif()

	# The same tests run in the MSVC job and each native VS Code CI matrix job.
	foreach(mode absolute relative failures loader)
		add_test(
			NAME vas_tools_unicode_${mode}
			COMMAND ${CMAKE_COMMAND}
				"-DVASBUILD=$<TARGET_FILE:vasbuild>"
				"-DVASRUN=$<TARGET_FILE:vasrun>"
				"-DTEST_ROOT=${CMAKE_CURRENT_BINARY_DIR}/unicode-${mode}"
				"-DMODE=${mode}"
				-P "${CMAKE_CURRENT_SOURCE_DIR}/tests/vasbuild/assert_unicode_paths.cmake"
		)
	endforeach()

	set(VASBUILD_FIXTURE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/tests/vasbuild/fixtures")

	add_test(
		NAME vasbuild_accepts_vas
		COMMAND $<TARGET_FILE:vasbuild>
			"${VASBUILD_FIXTURE_DIR}/minimal-config.txt"
			"${VASBUILD_FIXTURE_DIR}/valid.vas"
			"${CMAKE_CURRENT_BINARY_DIR}/vasbuild-valid.vasbc"
	)

	add_test(
		NAME vasbuild_rejects_legacy_entry_extension
		COMMAND ${CMAKE_COMMAND}
			"-DVASBUILD=$<TARGET_FILE:vasbuild>"
			"-DCONFIG=${VASBUILD_FIXTURE_DIR}/minimal-config.txt"
			"-DSCRIPT=${VASBUILD_FIXTURE_DIR}/legacy.as"
			"-DOUTPUT=${CMAKE_CURRENT_BINARY_DIR}/vasbuild-legacy.vasbc"
			-P "${CMAKE_CURRENT_SOURCE_DIR}/tests/vasbuild/assert_rejected.cmake"
	)

	add_test(
		NAME vasbuild_rejects_legacy_include_extension
		COMMAND ${CMAKE_COMMAND}
			"-DVASBUILD=$<TARGET_FILE:vasbuild>"
			"-DCONFIG=${VASBUILD_FIXTURE_DIR}/minimal-config.txt"
			"-DSCRIPT=${VASBUILD_FIXTURE_DIR}/includes_legacy.vas"
			"-DOUTPUT=${CMAKE_CURRENT_BINARY_DIR}/vasbuild-legacy-include.vasbc"
			-P "${CMAKE_CURRENT_SOURCE_DIR}/tests/vasbuild/assert_rejected.cmake"
	)

	add_test(
		NAME vasrun_executes_vas
		COMMAND $<TARGET_FILE:vasrun> "${VASBUILD_FIXTURE_DIR}/runnable.vas"
	)
	add_test(
		NAME vasrun_rejects_legacy_entry_extension
		COMMAND ${CMAKE_COMMAND}
			"-DVASRUN=$<TARGET_FILE:vasrun>"
			"-DSCRIPT=${VASBUILD_FIXTURE_DIR}/legacy.as"
			-P "${CMAKE_CURRENT_SOURCE_DIR}/tests/vasbuild/assert_vasrun_rejected.cmake"
	)
	add_test(
		NAME vasrun_rejects_legacy_include_extension
		COMMAND ${CMAKE_COMMAND}
			"-DVASRUN=$<TARGET_FILE:vasrun>"
			"-DSCRIPT=${VASBUILD_FIXTURE_DIR}/runnable_includes_legacy.vas"
			-P "${CMAKE_CURRENT_SOURCE_DIR}/tests/vasbuild/assert_vasrun_rejected.cmake"
	)
	set_tests_properties(vasrun_executes_vas PROPERTIES
		PASS_REGULAR_EXPRESSION "VAS Rider project is running"
	)
endif()
