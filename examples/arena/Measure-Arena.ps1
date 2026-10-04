param(
	[string]$Runner = (Join-Path $PSScriptRoot '../../out/build/windows-msvc-v145-cxx23/Release/vasrun.exe'),
	[string]$Entry = (Join-Path $PSScriptRoot 'main.vas'),
	[int[]]$BattleCounts = @(2000, 20000),
	[ValidateRange(3, 15)]
	[int]$Samples = 3
)

$ErrorActionPreference = 'Stop'
$Runner = (Resolve-Path -LiteralPath $Runner).Path
$Entry = (Resolve-Path -LiteralPath $Entry).Path

foreach ($BattleCount in $BattleCounts)
{
	if ($BattleCount -lt 1 -or $BattleCount -gt 100000)
	{
		throw 'BattleCounts must be within 1..100000.'
	}

	$ExpectedOutput = (& $Runner $Entry --batch $BattleCount | Out-String).Trim()
	if ($LASTEXITCODE -ne 0)
	{
		throw "Arena warm-up failed: $ExpectedOutput"
	}

	$Timings = @()
	for ($Sample = 0; $Sample -lt $Samples; ++$Sample)
	{
		$Stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
		$Output = (& $Runner $Entry --batch $BattleCount | Out-String).Trim()
		$Stopwatch.Stop()
		if ($LASTEXITCODE -ne 0 -or $Output -cne $ExpectedOutput)
		{
			throw "Arena result changed during measurement: $Output"
		}
		$Timings += $Stopwatch.Elapsed.TotalMilliseconds
	}

	$SortedTimings = @($Timings | Sort-Object)
	$Middle = [int][Math]::Floor($Samples / 2)
	$Median = $SortedTimings[$Middle]
	if ($Samples % 2 -eq 0)
	{
		$Median = ($SortedTimings[$Middle - 1] + $Median) / 2
	}
	[PSCustomObject]@{
		BattleCount = $BattleCount
		Samples = $Samples
		MedianMilliseconds = [Math]::Round($Median, 3)
		MinMilliseconds = [Math]::Round($SortedTimings[0], 3)
		MaxMilliseconds = [Math]::Round($SortedTimings[-1], 3)
		Summary = $ExpectedOutput
	}
}
