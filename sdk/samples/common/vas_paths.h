#ifndef VAS_SAMPLE_PATHS_H
#define VAS_SAMPLE_PATHS_H

// VAS tooling keeps script names and diagnostics in UTF-8. Only the native
// command-line and filesystem boundaries use UTF-16 on Windows.
#include <cstdio>
#include <string>
#include <vector>

#ifdef _WIN32
#ifndef NOMINMAX
#define NOMINMAX
#endif
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#else
#include <cerrno>
#include <unistd.h>
#endif

namespace vas
{
#ifdef _WIN32
inline bool ToUtf8(const wchar_t *value, std::string &result)
{
	int size = WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, value, -1, 0, 0, 0, 0);
	if( size == 0 ) return false;
	std::vector<char> buffer(size);
	if( WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, value, -1, buffer.data(), size, 0, 0) == 0 )
		return false;
	result.assign(buffer.data(), size - 1);
	return true;
}

inline bool ToWide(const char *value, std::wstring &result)
{
	int size = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, value, -1, 0, 0);
	if( size == 0 ) return false;
	std::vector<wchar_t> buffer(size);
	if( MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, value, -1, buffer.data(), size) == 0 )
		return false;
	result.assign(buffer.data(), size - 1);
	return true;
}

inline int RunWithUtf8Arguments(int argc, wchar_t **argv, int (*run)(int, char **))
{
	std::vector<std::string> values(argc);
	std::vector<char *> arguments(argc + 1, nullptr);
	for( int i = 0; i < argc; ++i )
	{
		if( !ToUtf8(argv[i], values[i]) )
		{
			std::fprintf(stderr, "Failed to decode command line argument %d as Unicode\n", i);
			return -1;
		}
		arguments[i] = &values[i][0];
	}
	return run(argc, arguments.data());
}
#endif

inline bool ReadCurrentDirectory(std::string &result)
{
#ifdef _WIN32
	DWORD size = GetCurrentDirectoryW(0, nullptr);
	while( size != 0 )
	{
		std::vector<wchar_t> buffer(size);
		DWORD length = GetCurrentDirectoryW(size, buffer.data());
		if( length == 0 ) return false;
		if( length < size ) return ToUtf8(buffer.data(), result);
		size = length;
	}
	return false;
#else
	std::vector<char> buffer(256);
	while( !getcwd(buffer.data(), buffer.size()) )
	{
		if( errno != ERANGE ) return false;
		buffer.resize(buffer.size() * 2);
	}
	result = buffer.data();
	return true;
#endif
}

inline std::string CurrentDirectory()
{
	std::string result;
	return ReadCurrentDirectory(result) ? result : "<unavailable>";
}

inline bool AbsolutePath(const char *filename, std::string &result)
{
	if( filename[0] == '\0' ) return false;
#ifdef _WIN32
	std::wstring native;
	if( !ToWide(filename, native) ) return false;
	DWORD size = GetFullPathNameW(native.c_str(), 0, nullptr, nullptr);
	while( size != 0 )
	{
		std::vector<wchar_t> buffer(size);
		DWORD length = GetFullPathNameW(native.c_str(), size, buffer.data(), nullptr);
		if( length == 0 ) return false;
		if( length < size ) return ToUtf8(buffer.data(), result);
		size = length;
	}
	return false;
#else
	if( filename[0] == '/' )
		result = filename;
	else
	{
		if( !ReadCurrentDirectory(result) ) return false;
		result += std::string("/") + filename;
	}
	return true;
#endif
}

inline FILE *OpenFile(const char *filename, const char *mode)
{
#ifdef _WIN32
	std::wstring native, nativeMode;
	if( !ToWide(filename, native) || !ToWide(mode, nativeMode) ) return nullptr;
#ifdef _MSC_VER
	FILE *file = nullptr;
	_wfopen_s(&file, native.c_str(), nativeMode.c_str());
	return file;
#else
	return _wfopen(native.c_str(), nativeMode.c_str());
#endif
#else
	return std::fopen(filename, mode);
#endif
}
}

#endif
