#ifndef VAS_SAMPLE_BUILD_REPORT_H
#define VAS_SAMPLE_BUILD_REPORT_H

#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>
#include "vas_paths.h"
#ifdef _WIN32
#include <fcntl.h>
#include <io.h>
#else
#include <sys/stat.h>
#endif

namespace vas
{
// Report mode writes bytecode only to ordinary files distinct from stdout.
// Reject devices/FIFOs too: terminal aliases such as /dev/tty and CONOUT$ need
// not have the same filesystem identity as the stdout handle.
inline bool InvalidReportOutput(const char *filename)
{
#ifdef _WIN32
	std::wstring native;
	if( !ToWide(filename, native) ) return false;
	// Metadata-only opens of console devices may fail even though a subsequent
	// write-only CRT open succeeds. Reject DOS device names/namespaces before
	// probing, including aliases with extensions, trailing dots or spaces.
	std::wstring check = native;
	for( wchar_t &ch : check )
	{
		if( ch == L'/' ) ch = L'\\';
		if( ch >= L'a' && ch <= L'z' ) ch -= L'a' - L'A';
	}
	if( check.find(L"\\\\.\\") == 0 || check.find(L"\\\\?\\GLOBALROOT\\") == 0 || check.find(L"\\??\\") == 0 )
		return true;
	size_t slash = check.find_last_of(L'\\');
	std::wstring base = check.substr(slash == std::wstring::npos ? 0 : slash + 1);
	// A drive-relative spelling such as C:CON is also a device alias.
	if( base.size() >= 2 && base[1] == L':' ) base.erase(0, 2);
	size_t suffix = base.find_first_of(L".:");
	if( suffix != std::wstring::npos ) base.resize(suffix);
	while( !base.empty() && base.back() == L' ' ) base.pop_back();
	if( base == L"CON" || base == L"CONIN$" || base == L"CONOUT$" || base == L"NUL" ||
		base == L"PRN" || base == L"AUX" || base == L"CLOCK$" ) return true;
	if( base.size() == 4 && (base.compare(0, 3, L"COM") == 0 || base.compare(0, 3, L"LPT") == 0) &&
		((base[3] >= L'1' && base[3] <= L'9') || base[3] == 0xB9 || base[3] == 0xB2 || base[3] == 0xB3) ) return true;
	HANDLE output = CreateFileW(native.c_str(), 0,
		FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, 0, OPEN_EXISTING, 0, 0);
	if( output == INVALID_HANDLE_VALUE ) return false;
	HANDLE report = GetStdHandle(STD_OUTPUT_HANDLE);
	BY_HANDLE_FILE_INFORMATION outInfo = {}, reportInfo = {};
	bool invalid = GetFileType(output) != FILE_TYPE_DISK;
	bool same = GetFileInformationByHandle(output, &outInfo) && GetFileInformationByHandle(report, &reportInfo) &&
		outInfo.dwVolumeSerialNumber == reportInfo.dwVolumeSerialNumber &&
		outInfo.nFileIndexHigh == reportInfo.nFileIndexHigh && outInfo.nFileIndexLow == reportInfo.nFileIndexLow;
	CloseHandle(output);
	return invalid || same;
#else
	struct stat outInfo, reportInfo;
	if( stat(filename, &outInfo) != 0 ) return false; // Normal open reports missing/inaccessible parents.
	return !S_ISREG(outInfo.st_mode) || (fstat(fileno(stdout), &reportInfo) == 0 &&
		outInfo.st_dev == reportInfo.st_dev && outInfo.st_ino == reportInfo.st_ino);
#endif
}

// Validate the actual write handle before truncation, rather than trusting only
// the path probe. In particular a metadata-only Windows device open can fail
// while the write-only CRT open succeeds. The caller opened in append mode so a
// rejected stdout alias has not already truncated its own report.
inline bool PrepareReportOutput(FILE *file)
{
#ifdef _WIN32
	HANDLE output = reinterpret_cast<HANDLE>(_get_osfhandle(_fileno(file)));
	HANDLE report = GetStdHandle(STD_OUTPUT_HANDLE);
	if( GetFileType(output) != FILE_TYPE_DISK ) return false;
	BY_HANDLE_FILE_INFORMATION outInfo = {}, reportInfo = {};
	if( !GetFileInformationByHandle(output, &outInfo) ) return false;
	if( GetFileInformationByHandle(report, &reportInfo) &&
		outInfo.dwVolumeSerialNumber == reportInfo.dwVolumeSerialNumber &&
		outInfo.nFileIndexHigh == reportInfo.nFileIndexHigh && outInfo.nFileIndexLow == reportInfo.nFileIndexLow ) return false;
	if( _chsize_s(_fileno(file), 0) != 0 ) return false;
#else
	struct stat outInfo, reportInfo;
	if( fstat(fileno(file), &outInfo) != 0 || !S_ISREG(outInfo.st_mode) ) return false;
	if( fstat(fileno(stdout), &reportInfo) == 0 &&
		outInfo.st_dev == reportInfo.st_dev && outInfo.st_ino == reportInfo.st_ino ) return false;
	if( ftruncate(fileno(file), 0) != 0 ) return false;
#endif
	return std::fseek(file, 0, SEEK_SET) == 0;
}

// Return the length of a valid Unicode scalar encoded at offset, or zero.
// Reject overlong sequences, surrogates, truncation and values above U+10FFFF.
inline size_t Utf8ScalarLength(const std::string &text, size_t offset)
{
	const unsigned char first = static_cast<unsigned char>(text[offset]);
	if( first < 0x80 ) return 1;
	size_t size = first >= 0xC2 && first <= 0xDF ? 2 :
		first >= 0xE0 && first <= 0xEF ? 3 : first >= 0xF0 && first <= 0xF4 ? 4 : 0;
	if( !size || text.size() - offset < size ) return 0;
	for( size_t i = 1; i < size; ++i )
		if( (static_cast<unsigned char>(text[offset + i]) & 0xC0) != 0x80 ) return 0;
	const unsigned char second = static_cast<unsigned char>(text[offset + 1]);
	if( (first == 0xE0 && second < 0xA0) || (first == 0xED && second >= 0xA0) ||
		(first == 0xF0 && second < 0x90) || (first == 0xF4 && second >= 0x90) ) return 0;
	return size;
}

inline bool IsValidUtf8(const std::string &text)
{
	for( size_t i = 0; i < text.size(); )
	{
		size_t length = Utf8ScalarLength(text, i);
		if( !length ) return false;
		i += length;
	}
	return true;
}

// A tiny purpose-specific JSON writer. Text fields always contain valid UTF-8.
// Invalid input bytes are replaced for display and also retained in rawBytes.
class BuildReportRecord
{
public:
	BuildReportRecord(const char *type, std::uint64_t sequence)
		: json("{\"protocol\":\"vasbuild\",\"version\":1")
	{
		Text("type", type);
		Number("seq", sequence);
	}

	void Text(const char *name, const std::string &value)
	{
		bool valid = true;
		json += std::string(",\"") + name + "\":" + Quote(value, valid);
		if( !valid )
		{
			invalid.push_back(name);
			std::string hex;
			static const char digits[] = "0123456789abcdef";
			for( unsigned char byte : value )
			{
				hex += digits[byte >> 4];
				hex += digits[byte & 15];
			}
			raw.push_back(hex);
		}
	}
	void Number(const char *name, std::int64_t value)
	{
		json += std::string(",\"") + name + "\":" + std::to_string(value);
	}
	void Boolean(const char *name, bool value)
	{
		json += std::string(",\"") + name + "\":" + (value ? "true" : "false");
	}
	void Null(const char *name) { json += std::string(",\"") + name + "\":null"; }

	std::string Finish()
	{
		json += ",\"invalidUtf8Fields\":[";
		for( size_t i = 0; i < invalid.size(); ++i )
		{
			if( i ) json += ',';
			json += '"' + invalid[i] + '"';
		}
		json += "],\"rawBytes\":{";
		for( size_t i = 0; i < invalid.size(); ++i )
		{
			if( i ) json += ',';
			json += '"' + invalid[i] + "\":\"" + raw[i] + '"';
		}
		return json + "}}\n";
	}

private:
	static std::string Quote(const std::string &value, bool &valid)
	{
		static const char digits[] = "0123456789abcdef";
		std::string result = "\"";
		for( size_t i = 0; i < value.size(); )
		{
			unsigned char byte = static_cast<unsigned char>(value[i]);
			if( byte == '"' || byte == '\\' )
			{
				result += '\\'; result += value[i++];
			}
			else if( byte < 0x20 )
			{
				result += "\\u00"; result += digits[byte >> 4]; result += digits[byte & 15]; ++i;
			}
			else
			{
				size_t size = Utf8ScalarLength(value, i);
				if( size ) { result.append(value, i, size); i += size; }
				else { result += "\\ufffd"; valid = false; ++i; }
			}
		}
		return result + '"';
	}
	std::string json;
	std::vector<std::string> invalid, raw;
};

class BuildReport
{
public:
	BuildReport() : dependenciesComplete(false), phase("arguments"), sequence(0), failed(false), finished(false) {}
	bool Open()
	{
#ifdef _WIN32
		// Reports are bytes, even when stdout is a console. Avoid CRT CRLF or
		// UTF-16 translation; ordinary console diagnostics remain unchanged.
		if( _setmode(_fileno(stdout), _O_BINARY) == -1 ) failed = true;
#endif
		return !failed;
	}
	BuildReportRecord Record(const char *type) { return BuildReportRecord(type, ++sequence); }
	std::uint64_t Sequence() const { return sequence; }
	bool Write(BuildReportRecord record)
	{
		if( failed ) return false;
		std::string line = record.Finish();
		if( std::fwrite(line.data(), 1, line.size(), stdout) != line.size() || std::fflush(stdout) != 0 )
			failed = true;
		return !failed;
	}
	void Diagnostic(const char *severity, const char *section, int row, int column, const char *message)
	{
		BuildReportRecord record = Record("diagnostic");
		record.Text("severity", severity);
		record.Text("section", section ? section : "");
		record.Number("row", row);
		record.Number("column", column);
		record.Text("message", message ? message : "");
		Write(record);
	}
	bool Result(bool success)
	{
		if( finished ) return false;
		finished = true;
		BuildReportRecord record = Record("result");
		record.Boolean("success", success);
		record.Text("phase", phase);
		record.Boolean("dependenciesComplete", dependenciesComplete);
		return Write(record);
	}
	bool Failed() const { return failed; }
	bool dependenciesComplete;
	const char *phase;

private:
	std::uint64_t sequence;
	bool failed, finished;
};
}
#endif
