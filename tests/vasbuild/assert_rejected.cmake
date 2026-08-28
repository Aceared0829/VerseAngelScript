foreach(required_variable VASBUILD CONFIG SCRIPT OUTPUT)
	if(NOT DEFINED ${required_variable})
		message(FATAL_ERROR "Missing -D${required_variable}=... for the vasbuild rejection test")
	endif()
endforeach()

execute_process(
	COMMAND "${VASBUILD}" "${CONFIG}" "${SCRIPT}" "${OUTPUT}"
	RESULT_VARIABLE vasbuild_result
	OUTPUT_VARIABLE vasbuild_stdout
	ERROR_VARIABLE vasbuild_stderr
)

set(vasbuild_log "${vasbuild_stdout}${vasbuild_stderr}")
if(vasbuild_result EQUAL 0)
	message(FATAL_ERROR "vasbuild accepted '${SCRIPT}', but the test expects rejection. Output:\n${vasbuild_log}")
endif()

if(NOT vasbuild_log MATCHES "VAS source files must use the '.vas' extension")
	message(FATAL_ERROR "vasbuild rejected '${SCRIPT}' for the wrong reason. Output:\n${vasbuild_log}")
endif()
