[CmdletBinding()]
param([string]$ResultsDirectory = (Join-Path $PSScriptRoot '../TestResults'))
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$extensionRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$ResultsDirectory = [IO.Path]::GetFullPath($ResultsDirectory)
New-Item -ItemType Directory -Force $ResultsDirectory | Out-Null

$vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio/Installer/vswhere.exe'
if (!(Test-Path $vswhere)) { throw 'vswhere.exe is required.' }
$instances = @(& $vswhere -products '*' -version '[18.0,19.0)' -requires Microsoft.VisualStudio.Component.VSSDK Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -format json | ConvertFrom-Json)
if ($LASTEXITCODE -ne 0 -or $instances.Count -eq 0) { throw 'A real VS2026 (18.x) instance with VSSDK is required; no fallback is allowed.' }
$instance = $instances | Sort-Object { [version]$_.installationVersion } -Descending | Select-Object -First 1
$install = $instance.installationPath
$msbuild = Join-Path $install 'MSBuild/Current/Bin/MSBuild.exe'
$devenv = Join-Path $install 'Common7/IDE/devenv.exe'
if (!(Test-Path $msbuild) -or !(Test-Path $devenv)) { throw 'VS2026 MSBuild and devenv must both exist.' }
if ((Get-Item $devenv).VersionInfo.FileMajorPart -ne 18) { throw 'Selected devenv is not major version 18.' }
$instance | Select-Object instanceId, installationVersion, installationPath | ConvertTo-Json | Tee-Object (Join-Path $ResultsDirectory 'host-instance.json')

# Pin the supported Microsoft test harness to the same instance. A native assertion
# verifies the process executable too, since the harness otherwise permits fallback.
$env:VSInstallDir = $install
$env:__UNITTESTEXPLORER_VSINSTALLPATH__ = Join-Path $install 'Common7/IDE'
$env:VSAPPIDDIR = $env:__UNITTESTEXPLORER_VSINSTALLPATH__
$env:VAS_EXPECTED_DEVENV = $devenv
$env:VAS_TEST_WORKSPACE = Join-Path $ResultsDirectory 'fixtures'
$env:VAS_TEST_RESULTS = $ResultsDirectory
$env:XUNIT_LOGS = Join-Path $ResultsDirectory 'harness'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $extensionRoot '../..'))
$nativeBuild = Join-Path $ResultsDirectory 'native-build'
$projectEvidence = Join-Path $ResultsDirectory 'project-build'
# Remove stale evidence before any run; a previous native pass cannot satisfy this gate.
if (Test-Path $projectEvidence) { Remove-Item $projectEvidence -Recurse -Force }
New-Item -ItemType Directory -Force $projectEvidence | Out-Null
# Do not convert absence of a native MOTW prompt into trust-state evidence.
[ordered]@{
    nativeMotwPromptCancellation = 'unverified'
    reason = 'This suite does not establish a native MOTW prompt on the selected host; no trust-state synchronization or prompt-cancellation pass is claimed.'
} | ConvertTo-Json | Set-Content (Join-Path $ResultsDirectory 'native-security-evidence.json') -Encoding utf8

# The extension tests consume the repository compiler, built with this exact VS18
# installation. A canned descriptor or framework fixture cannot replace it.
cmake -S $repositoryRoot -B $nativeBuild -G 'Visual Studio 18 2026' -A x64 "-DCMAKE_GENERATOR_INSTANCE=$install" -DBUILD_TESTING=ON
if ($LASTEXITCODE -ne 0) { throw 'Native VS2026 CMake configuration failed.' }
cmake --build $nativeBuild --config Release --target vasbuild vas_rider_argv_fixture vas_source_digest_test --parallel
if ($LASTEXITCODE -ne 0) { throw 'Native compiler/argument fixture build failed.' }
ctest --test-dir $nativeBuild -C Release --output-on-failure --no-tests=error -R '^(vasbuild_project_|vasbuild_jsonl_|vas_source_digest$)'
if ($LASTEXITCODE -ne 0) { throw 'Native compiler project/report conformance failed.' }
$env:VAS_NATIVE_COMPILER = Join-Path $nativeBuild 'Release/vasbuild.exe'
$env:VASBUILD_EXECUTABLE = $env:VAS_NATIVE_COMPILER
$env:VAS_NATIVE_ARGV_FIXTURE = Join-Path $nativeBuild 'Release/vas_rider_argv_fixture.exe'
foreach ($path in @($env:VAS_NATIVE_COMPILER, $env:VAS_NATIVE_ARGV_FIXTURE)) {
    if (!(Test-Path $path -PathType Leaf)) { throw "Missing native test binary: $path" }
}
[ordered]@{
    generator = 'Visual Studio 18 2026'
    configuration = 'Release'
    compiler = $env:VAS_NATIVE_COMPILER
    compilerSha256 = (Get-FileHash $env:VAS_NATIVE_COMPILER -Algorithm SHA256).Hash.ToLowerInvariant()
    argvFixture = $env:VAS_NATIVE_ARGV_FIXTURE
    argvFixtureSha256 = (Get-FileHash $env:VAS_NATIVE_ARGV_FIXTURE -Algorithm SHA256).Hash.ToLowerInvariant()
    conformancePassed = $true
} | ConvertTo-Json | Set-Content (Join-Path $ResultsDirectory 'native-build.json') -Encoding utf8

Push-Location $extensionRoot
try {
    python 'test/test_validation.py'
    if ($LASTEXITCODE -ne 0) { throw 'Validation gate regression tests failed.' }
    & $msbuild 'VerseAngelScript.VisualStudio.csproj' /restore /t:Build /p:Configuration=Release /p:DeployExtension=false /nologo /verbosity:minimal
    if ($LASTEXITCODE -ne 0) { throw "Production VSIX build failed ($LASTEXITCODE)." }
    $packages = @(Get-ChildItem 'bin/Release' -Filter '*.vsix' -Recurse)
    if ($packages.Count -ne 1) { throw "Expected one production VSIX, found $($packages.Count)." }
    python 'test/check_package.py' --vsix $packages[0].FullName
    if ($LASTEXITCODE -ne 0) { throw 'Production VSIX contract failed.' }

    & $msbuild 'test/CoreTests/CoreTests.csproj' /restore /t:Build /p:Configuration=Release /p:TargetFrameworks=net472 /p:TargetFramework=net472 /nologo /verbosity:minimal
    if ($LASTEXITCODE -ne 0) { throw "Native Windows core test build failed ($LASTEXITCODE)." }
    & 'test/CoreTests/bin/Release/net472/CoreTests.exe' 2>&1 | Tee-Object (Join-Path $ResultsDirectory 'core-tests.log')
    if ($LASTEXITCODE -ne 0) { throw "Native Windows core tests failed ($LASTEXITCODE)." }

    & $msbuild 'test/HostTests/HostTests.csproj' /restore /t:Build /p:Configuration=Release /nologo /verbosity:minimal
    if ($LASTEXITCODE -ne 0) { throw "Host test restore/build failed ($LASTEXITCODE)." }
    $testOutput = Join-Path $extensionRoot 'test/HostTests/bin/Release/net472'
    # The published IDE harness has runtime AssemblyRefs omitted from its nuspec.
    # Verify the actual output identities before launching, not just restore success.
    $runtimeDependencies = [ordered]@{
        'Microsoft.VisualStudio.Interop' = '18.0.0.0'
        'System.Memory' = '4.0.5.0'
        'System.Threading.Tasks.Extensions' = '4.2.4.0'
        'System.Collections.Immutable' = '10.0.0.10'
        'System.Runtime.CompilerServices.Unsafe' = '6.0.3.0'
    }
    $runtimeEvidence = foreach ($name in $runtimeDependencies.Keys) {
        $assembly = Join-Path $testOutput "$name.dll"
        if (!(Test-Path $assembly)) { throw "Missing harness runtime dependency: $name" }
        $version = [Reflection.AssemblyName]::GetAssemblyName($assembly).Version.ToString()
        if ($version -ne $runtimeDependencies[$name]) {
            throw "Harness runtime $name has version $version; expected $($runtimeDependencies[$name])."
        }
        [pscustomobject]@{ name = $name; version = $version }
    }
    $runtimeEvidence | ConvertTo-Json | Tee-Object (Join-Path $ResultsDirectory 'host-runtime-dependencies.json')
    Copy-Item $packages[0].FullName (Join-Path $testOutput 'VerseAngelScript.vsix') -Force
    $trx = Join-Path $ResultsDirectory 'VS2026.trx'
    if (Test-Path $trx) { Remove-Item $trx }
    # RequireExtension installs this VSIX into VASIntegration and starts actual devenv.
    dotnet test 'test/HostTests/HostTests.csproj' --no-restore --no-build --configuration Release --logger 'trx;LogFileName=VS2026.trx' --results-directory $ResultsDirectory -- RunConfiguration.TargetPlatform=x64
    $testExit = $LASTEXITCODE
    python 'test/check_results.py' (Join-Path $ResultsDirectory 'VS2026.trx')
    if ($LASTEXITCODE -ne 0) { throw 'Required native VS2026 results are missing, skipped or failed.' }
    if ($testExit -ne 0) { throw "Native VS2026 tests failed ($testExit)." }
} finally {
    Pop-Location
}
