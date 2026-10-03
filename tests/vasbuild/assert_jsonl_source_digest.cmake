include("${CMAKE_CURRENT_LIST_DIR}/jsonl_helpers.cmake")

# Compare the actual compiler report with CMake's independent binary-file
# SHA-256 and size implementations. Fixtures remain unchanged during each run.
function(expect_digest section)
	find_record(i section_loaded section "${section}")
	file(SHA256 "${section}" digest)
	file(SIZE "${section}" size)
	expect_json("${record_${i}}" 1 sourceDigestVersion)
	expect_json("${record_${i}}" sha256 sourceDigestAlgorithm)
	expect_json("${record_${i}}" "${size}" sourceByteLength)
	expect_json("${record_${i}}" "${digest}" sourceDigest)
endfunction()

file(WRITE "${workspace}/empty.vas" "")
run_report(OFF compile ON config.txt empty.vas out.vasbc)
expect_digest("${workspace}/empty.vas")
find_record(i section_loaded section "${workspace}/empty.vas")
expect_json("${record_${i}}" e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855 sourceDigest)

# A known vector need not be valid source: successful read precedes compilation.
file(WRITE "${workspace}/abc.vas" "abc")
run_report(OFF compile ON config.txt abc.vas out.vasbc)
expect_digest("${workspace}/abc.vas")
find_record(i section_loaded section "${workspace}/abc.vas")
expect_json("${record_${i}}" ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad sourceDigest)

# Exercise padding boundaries, complete SHA blocks and the loader's read size.
foreach(length 55 56 63 64 65 119 120 127 128 129 4095 4096 4097)
	math(EXPR padding "${length} - 3")
	string(REPEAT a ${padding} comment)
	file(WRITE "${workspace}/main.vas" "//${comment}\n")
	run_report(OFF compile ON config.txt main.vas out.vasbc)
	expect_digest("${workspace}/main.vas")
endforeach()

string(ASCII 239 187 191 bom)
string(ASCII 128 192 175 237 160 128 244 144 128 128 194 invalid)
foreach(content "// 中文😀\nvoid main() {}\n" "${bom}void main() {}\r\n" "/* ${invalid} */\r\nvoid main() {}\r\n")
	file(WRITE "${workspace}/main.vas" "${content}")
	run_report(ON output ON config.txt main.vas out.vasbc)
	expect_digest("${workspace}/main.vas")
endforeach()

# CMake cannot represent a NUL in a string. Generate a raw binary fixture using
# the native test utility, then independently hash the entire file with CMake.
# Use an ASCII relative path so the utility's narrow test argv is portable.
execute_process(COMMAND "${SOURCE_DIGEST_TEST}" --nul-fixture nul.vas
	WORKING_DIRECTORY "${workspace}" RESULT_VARIABLE generated)
expect_equal("${generated}" 0 "embedded NUL fixture generation")
run_report(ON output ON config.txt nul.vas out.vasbc)
expect_digest("${workspace}/nul.vas")

# The preprocessor blanks directives/inactive code and resolves nested includes.
# Original raw bytes, lexical identity, include-once and cycles all survive.
file(MAKE_DIRECTORY "${workspace}/sub")
file(WRITE "${workspace}/main.vas" "#if NEVER_DEFINED\r\ninvalid inactive source\r\n#endif\r\n#include \"sub/a.vas\"\r\n#include \"sub/./a.vas\"\r\nvoid main() { child(); }\r\n")
file(WRITE "${workspace}/sub/a.vas" "#include \"../empty.vas\"\n#include \"../main.vas\"\nvoid child() {}\n")
run_report(ON output ON config.txt ./main.vas out.vasbc)
expect_count(section_loaded 3)
foreach(file main.vas sub/a.vas empty.vas)
	expect_digest("${workspace}/${file}")
endforeach()

if(UNIX)
	file(CREATE_LINK "${workspace}/empty.vas" "${workspace}/alias.vas" SYMBOLIC)
	file(APPEND "${workspace}/main.vas" "#include \"alias.vas\"\n")
	run_report(ON output ON config.txt main.vas out.vasbc)
	expect_count(section_loaded 4)
	expect_digest("${workspace}/empty.vas")
	expect_digest("${workspace}/alias.vas")
endif()

# A partial graph includes digests only for fully-read sections. Missing/rejected
# descendants and never-visited siblings must not acquire fabricated evidence.
file(WRITE "${workspace}/sub/a.vas" "#include \"missing.vas\"\n")
run_report(OFF load OFF config.txt main.vas out.vasbc)
expect_count(section_loaded 2)
expect_digest("${workspace}/main.vas")
expect_digest("${workspace}/sub/a.vas")
file(WRITE "${workspace}/main.vas" "#include \"legacy.as\"\n")
file(WRITE "${workspace}/legacy.as" "void legacy() {}\n")
run_report(OFF load OFF config.txt main.vas out.vasbc)
expect_count(section_loaded 1)
expect_digest("${workspace}/main.vas")
run_report(OFF load OFF config.txt missing.vas out.vasbc)
expect_count(section_loaded 0)
file(WRITE "${workspace}/main.vas" "#pragma unsupported\n")
run_report(OFF load OFF config.txt main.vas out.vasbc)
expect_digest("${workspace}/main.vas")

if(UNIX)
	# A failed native fread (directory) is not a successfully loaded section.
	file(MAKE_DIRECTORY "${workspace}/directory.vas")
	run_report(OFF load OFF config.txt directory.vas out.vasbc)
	expect_count(section_loaded 0)
	# Keep existing non-file source behavior, while omitting all digest fields.
	file(CREATE_LINK /dev/null "${workspace}/device.vas" SYMBOLIC)
	run_report(OFF compile ON config.txt device.vas out.vasbc)
	find_record(i section_loaded section "${workspace}/device.vas")
	foreach(field sourceDigestVersion sourceDigestAlgorithm sourceByteLength sourceDigest)
		string(JSON unused ERROR_VARIABLE error GET "${record_${i}}" "${field}")
		if(NOT error)
			message(FATAL_ERROR "Non-file input claimed ${field}")
		endif()
	endforeach()
endif()
