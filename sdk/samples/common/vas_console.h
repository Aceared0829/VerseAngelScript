#ifndef VAS_SAMPLE_CONSOLE_H
#define VAS_SAMPLE_CONSOLE_H

#include <cstdio>
#include <sstream>
#include <string>
#include <vector>

#ifdef _WIN32
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#endif

namespace vas
{
// Windows console handles (including ConPTY) consume UTF-16 via WriteConsoleW.
// Redirected streams retain UTF-8 bytes for build tools and diagnostic parsers.
inline void WriteUtf8(FILE *stream, const std::string &text)
{
#ifdef _WIN32
	HANDLE handle = GetStdHandle(stream == stderr ? STD_ERROR_HANDLE : STD_OUTPUT_HANDLE);
	DWORD mode;
	if( handle != INVALID_HANDLE_VALUE && GetConsoleMode(handle, &mode) )
	{
		int size = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, text.c_str(), -1, 0, 0);
		if( size > 0 )
		{
			std::vector<wchar_t> buffer(size);
			if( MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, text.c_str(), -1, buffer.data(), size) > 0 )
			{
				size_t offset = 0;
				const size_t length = size - 1;
				while( offset < length )
				{
					DWORD count = static_cast<DWORD>(length - offset > 16384 ? 16384 : length - offset);
					// Do not divide a supplementary-plane character between writes.
					wchar_t last = buffer[offset + count - 1];
					if( last >= 0xD800 && last <= 0xDBFF ) --count;
					DWORD written = 0;
					if( !WriteConsoleW(handle, buffer.data() + offset, count, &written, 0) || written == 0 )
						return;
					offset += written;
				}
				return;
			}
		}
	}
#endif
	std::fwrite(text.data(), 1, text.size(), stream);
	std::fflush(stream);
}

inline void WriteDiagnostic(FILE *stream, const char *section, int row, int column,
	const char *type, const char *message)
{
	std::ostringstream text;
	text << section << " (" << row << ", " << column << ") : " << type << " : " << message << '\n';
	WriteUtf8(stream, text.str());
}
}

#endif
