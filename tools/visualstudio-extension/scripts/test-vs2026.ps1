[CmdletBinding()]
param([string]$ResultsDirectory = (Join-Path $PSScriptRoot '../TestResults'))
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$extensionRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$ResultsDirectory = [IO.Path]::GetFullPath($ResultsDirectory)
New-Item -ItemType Directory -Force $ResultsDirectory | Out-Null

$vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio/Installer/vswhere.exe'
if (!(Test-Path $vswhere)) { throw 'vswhere.exe is required.' }
$instances = @(& $vswhere -products '*' -version '[18.0,19.0)' -requires Microsoft.VisualStudio.Component.VSSDK -format json | ConvertFrom-Json)
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

    & $msbuild 'test/HostTests/HostTests.csproj' /restore /t:Build /p:Configuration=Release /nologo /verbosity:minimal
    if ($LASTEXITCODE -ne 0) { throw "Host test restore/build failed ($LASTEXITCODE)." }
    $testOutput = Join-Path $extensionRoot 'test/HostTests/bin/Release/net472'
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
