$ErrorActionPreference = "Stop"

$templatePath = Resolve-Path (Join-Path $PSScriptRoot "..\..\templates\rider\vas-starter")

dotnet new install $templatePath --force
if ($LASTEXITCODE -ne 0) {
    throw "Failed to install the Verse AngelScript project template."
}

dotnet new list vas
if ($LASTEXITCODE -ne 0) {
    throw "The Verse AngelScript project template was installed but could not be listed."
}
