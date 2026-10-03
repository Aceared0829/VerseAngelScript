// Test-only receiver for Rider's native-process argument transport. On Windows,
// wmain receives the actual UTF-16 argv, unlike the JDK 25 java.exe launcher,
// which converts GetCommandLineW through the active ANSI code page before main.
#include <cstdio>
#include <stdexcept>
#include <string>
#include <vector>

#ifdef _WIN32
#include <windows.h>
#include <fcntl.h>
#include <io.h>
#endif

namespace {

bool writeBytes(FILE *stream, const std::string &value)
{
	return std::fwrite(value.data(), 1, value.size(), stream) == value.size();
}

std::string base64(const std::string &value)
{
	static constexpr char alphabet[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
	std::string encoded;
	encoded.reserve((value.size() + 2) / 3 * 4);
	for (std::size_t offset = 0; offset < value.size(); offset += 3) {
		const auto first = static_cast<unsigned char>(value[offset]);
		const auto second = offset + 1 < value.size() ? static_cast<unsigned char>(value[offset + 1]) : 0u;
		const auto third = offset + 2 < value.size() ? static_cast<unsigned char>(value[offset + 2]) : 0u;
		encoded += alphabet[first >> 2];
		encoded += alphabet[((first & 3u) << 4) | (second >> 4)];
		encoded += offset + 1 < value.size() ? alphabet[((second & 15u) << 2) | (third >> 6)] : '=';
		encoded += offset + 2 < value.size() ? alphabet[third & 63u] : '=';
	}
	return encoded;
}

int run(const std::vector<std::string> &args)
{
	if (!args.empty() && args[0] == "arguments" && args.size() == 2) {
		// Fixed UTF-8 bytes keep this diagnostic independent of compiler/source and
		// console code pages. The stdout value always comes from the received argv.
		const std::string diagnostic = "diagnostic \xE6\xBC\xA2\xE5\xAD\x97\xF0\x9F\x98\x80\n";
		return writeBytes(stdout, args[1] + "\n") && writeBytes(stderr, diagnostic) ? 7 : 2;
	}
	if (!args.empty() && args[0] == "argument-list") {
		if (!writeBytes(stdout, std::to_string(args.size() - 1) + "\n")) {
			return 2;
		}
		for (std::size_t index = 1; index < args.size(); ++index) {
			if (!writeBytes(stdout, base64(args[index]) + "\n")) {
				return 2;
			}
		}
		return 0;
	}
	writeBytes(stderr, "Invalid native argv fixture mode or argument count\n");
	return 2;
}

#ifdef _WIN32
std::string utf8(const wchar_t *value)
{
	const int count = WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, value, -1, nullptr, 0, nullptr, nullptr);
	if (count == 0) {
		throw std::runtime_error("Invalid UTF-16 argument");
	}
	std::string converted(static_cast<std::size_t>(count), '\0');
	if (WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, value, -1,
		converted.data(), count, nullptr, nullptr) != count) {
		throw std::runtime_error("UTF-16 argument conversion failed");
	}
	converted.pop_back(); // Remove the conversion's terminating NUL only.
	return converted;
}
#endif

} // namespace

#ifdef _WIN32
int wmain(int argc, wchar_t **argv)
{
	// Match the LF-only Java fixture protocol, bypassing CRT CRLF translation.
	if (_setmode(_fileno(stdout), _O_BINARY) == -1 || _setmode(_fileno(stderr), _O_BINARY) == -1) {
		return 2;
	}
	try {
		std::vector<std::string> args;
		for (int index = 1; index < argc; ++index) {
			args.push_back(utf8(argv[index]));
		}
		return run(args);
	} catch (const std::exception &error) {
		writeBytes(stderr, std::string(error.what()) + "\n");
		return 2;
	}
}
#else
int main(int argc, char **argv)
{
	return run(std::vector<std::string>(argv + 1, argv + argc));
}
#endif
