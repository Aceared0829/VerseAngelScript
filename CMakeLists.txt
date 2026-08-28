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

if(MSVC)
	target_compile_options(vasrun PRIVATE
		"$<$<COMPILE_LANGUAGE:CXX>:/std:c++23preview>"
		"$<$<COMPILE_LANGUAGE:CXX>:/permissive->"
		"$<$<COMPILE_LANGUAGE:CXX>:/Zc:__cplusplus>"
		"$<$<COMPILE_LANGUAGE:CXX>:/EHsc>"
	)
else()
	set_target_properties(vasrun PROPERTIES
		CXX_STANDARD 23
		CXX_STANDARD_REQUIRED ON
		CXX_EXTENSIONS OFF
	)
endif()

if(BUILD_TESTING)
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
	set_tests_properties(vasrun_executes_vas PROPERTIES
		PASS_REGULAR_EXPRESSION "VAS Rider project is running"
	)
endif()
