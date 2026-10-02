#include "../../sdk/samples/common/vas_paths.h"

#include <cstring>

static bool called = false;

static int CheckArguments(int argc, char **argv)
{
	called = true;
	if( argc != 3 || argv[3] != nullptr ) return 1;
	if( std::strcmp(argv[0], "vasrun") != 0 ) return 2;
	if( std::strcmp(argv[1], "\xe6\x96\x87\xf0\x9f\x98\x80 with spaces") != 0 ) return 3;
	if( std::strcmp(argv[2], "") != 0 ) return 4;
	return 0;
}

int main()
{
	wchar_t program[] = L"vasrun";
	wchar_t unicode[] = L"\u6587\U0001F600 with spaces";
	wchar_t empty[] = L"";
	wchar_t *valid[] = {program, unicode, empty};
	if( vas::RunWithUtf8Arguments(3, valid, CheckArguments) != 0 || !called ) return 1;

	// Windows filenames/argv can contain unpaired UTF-16 surrogates. Reject
	// them before executing the tool rather than silently replacing characters.
	wchar_t high[] = {0xD800, 0};
	wchar_t low[] = {0xDC00, 0};
	wchar_t *invalid[] = {program, high, empty};
	called = false;
	if( vas::RunWithUtf8Arguments(3, invalid, CheckArguments) != -1 || called ) return 2;
	invalid[1] = low;
	if( vas::RunWithUtf8Arguments(3, invalid, CheckArguments) != -1 || called ) return 3;
	return 0;
}
