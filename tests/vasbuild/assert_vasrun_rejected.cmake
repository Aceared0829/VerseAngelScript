foreach(required_variable VASRUN SCRIPT)
	if(NOT DEFINED ${required_variable})
		message(FATAL_ERROR "Missing -D${required_variable}=... for the vasrun rejection test")
	endif()
endforeach()

execute_process(
	COMMAND "${VASRUN}" "${SCRIPT}"
	RESULT_VARIABLE vasrun_result
	OUTPUT_VARIABLE vasrun_stdout
	ERROR_VARIABLE vasrun_stderr
)

set(vasrun_log "${vasrun_stdout}${vasrun_stderr}")
if(vasrun_result EQUAL 0)
	message(FATAL_ERROR "vasrun accepted '${SCRIPT}', but the test expects rejection. Output:\n${vasrun_log}")
endif()

if(NOT vasrun_log MATCHES "VAS source files must use the '.vas' extension")
	message(FATAL_ERROR "vasrun rejected '${SCRIPT}' for the wrong reason. Output:\n${vasrun_log}")
endif()
