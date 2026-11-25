[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet('prepare', 'prepare-manual', 'check', 'functional', 'live', 'auto', 'resume', 'clean')]
    [string]$Mode,
    [string]$Dataset,
    [ValidatePattern('^\d{8}-\d{6}-\d{3}-[0-9a-f]{8}$')]
    [string]$RunId,
    [Alias('-live')]
    [switch]$Live,
    [ValidateRange(1, 100000)]
    [int]$MaxRequests = 250,
    [switch]$Quick,
    [ValidateRange(0, 100000)]
    [int]$Limit = 0,
    [ValidateRange(0, 100000)]
    [int]$StabilityCount = 10,
    [ValidateRange(2, 100)]
    [int]$Repeats = 3,
    [ValidateRange(1, 3600000)]
    [int]$DelayMs = 2000,
    [switch]$ReorderCharacteristics
)

$ErrorActionPreference = 'Stop'
if ($Mode -in @('live', 'auto', 'resume') -and -not $Live) {
    throw 'Real Gemini calls require --live.'
}
if (($RunId -and $Mode -notin @('live', 'resume')) -or ($Mode -eq 'resume' -and -not $RunId)) {
    throw 'Use -RunId with live to use a prepared snapshot, or with resume to continue it.'
}
if ($Mode -in @('live', 'auto')) {
    if ($Quick) {
        if (-not $PSBoundParameters.ContainsKey('Limit')) { $Limit = 3 }
        if (-not $PSBoundParameters.ContainsKey('StabilityCount')) { $StabilityCount = 0 }
    }
}

$projectRoot = Split-Path -Parent $PSScriptRoot
$resultsRoot = Join-Path $PSScriptRoot 'results'
$previousDataset = $env:AI_CATALOG_EVALUATION_DATASET
if ($Mode -eq 'prepare-manual') {
    if ([string]::IsNullOrWhiteSpace($Dataset)) {
        $Dataset = Join-Path $resultsRoot 'reviewed-dataset.csv'
    }
    $Dataset = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Dataset)
    $env:AI_CATALOG_EVALUATION_DATASET = $Dataset
}

Push-Location $projectRoot
try {
    $currentRunId = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
    if ($Mode -eq 'clean') {
        $root = $resultsRoot
        if (-not (Test-Path -LiteralPath $root -PathType Container)) { return }
        $resolvedRoot = Get-Item -LiteralPath $root
        if ($resolvedRoot.FullName -ne $root -or ($resolvedRoot.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw 'Unexpected evaluation results path.'
        }
        $runs = @(Get-ChildItem -LiteralPath $root -Directory | Where-Object {
            $_.Name -match '^\d{8}-\d{6}-\d{3}-[0-9a-f]{8}$' -and
            -not ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) -and
            (Test-Path -LiteralPath (Join-Path $_.FullName 'summary.json') -PathType Leaf) -and
            -not (Test-Path -LiteralPath (Join-Path $_.FullName 'run-manifest.json'))
        } | Where-Object {
            try {
                $report = Get-Content -LiteralPath (Join-Path $_.FullName 'summary.json') -Raw -Encoding UTF8 | ConvertFrom-Json
                (@($report.parameters.PSObject.Properties.Value) -contains 'live' -and $report.latency.attempts -eq 0) -or
                ($report.offline.functional.failed -gt 0 -and $report.latency.attempts -eq 0)
            } catch { $false }
        })
        if ($runs.Count -eq 0) { return }
        $backupRoot = Join-Path $root '_backup'
        if ((Test-Path -LiteralPath $backupRoot) -and
            ((Get-Item -LiteralPath $backupRoot).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw 'Unexpected backup path.'
        }
        $backup = New-Item -ItemType Directory -Path (Join-Path $backupRoot $currentRunId)
        foreach ($run in $runs) {
            $source = (Resolve-Path -LiteralPath $run.FullName).ProviderPath
            $destination = Join-Path $backup.FullName $run.Name
            if ([IO.Path]::GetDirectoryName($source) -ne $resolvedRoot.FullName -or
                [IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($destination)) -ne $backup.FullName) {
                throw 'Unexpected run path.'
            }
            Move-Item -LiteralPath $source -Destination $destination
        }
        Write-Host "Archived $($runs.Count) run directories to $($backup.FullName)"
        return
    }
    $existingRun = $PSBoundParameters.ContainsKey('RunId')
    if ($existingRun) { $currentRunId = $RunId }
    $output = "evaluation/results/$currentRunId"
    if ($existingRun) {
        $item = Get-Item -LiteralPath $output
        if (-not $item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw 'Expected a local evaluation run directory.'
        }
        if ($Mode -eq 'live' -and (Test-Path -LiteralPath "$output/run-manifest.json")) {
            throw 'The run has started. Use -Mode resume.'
        }
    }
    $junit = "evaluation/target/evaluation-junit/$currentRunId"
    $goal = 'org.codehaus.mojo:exec-maven-plugin:3.5.1:java'
    $main = '-Dexec.mainClass=com.nearapp.aicatalogbackend.evaluation.EvaluationCommand'
    $classpath = '-Dexec.classpathScope=test'

    if ($Mode -eq 'resume') {
        Write-Host "Results: $output"
        & .\mvnw.cmd -q test-compile $goal $main $classpath "-Dexec.args=resume $output --live"
        if ($LASTEXITCODE -ne 0) { throw 'Evaluation did not complete. See Maven output and any saved report.' }
        return
    }

    $launchLive = $Mode -in @('auto', 'live')
    if ($Mode -in @('prepare', 'prepare-manual', 'check') -or ($launchLive -and -not $existingRun)) {
        $operation = if ($launchLive) { 'prepare' } else { $Mode }
        & .\mvnw.cmd -q test-compile $goal $main $classpath "-Dexec.args=$operation $output"
        if ($LASTEXITCODE -ne 0) { throw 'Dataset preparation failed. See the error above.' }
        if ($Mode -eq 'check') { return }
        Write-Host "Results: $output"
        if (-not $launchLive) { return }
    }

    # Each Maven invocation has its own Surefire directory, even on a reused snapshot.
    $junit += '-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
    & .\mvnw.cmd test "-Devaluation.junit.directory=$junit"
    $testExit = $LASTEXITCODE
    $arguments = "functional $output $junit $testExit"
    if ($launchLive -and $testExit -eq 0) {
        $reorder = $ReorderCharacteristics.IsPresent.ToString().ToLowerInvariant()
        $arguments = "live $output --live $junit $testExit $Limit $StabilityCount $Repeats $reorder $DelayMs $MaxRequests"
    }
    & .\mvnw.cmd -q $goal $main $classpath "-Dexec.args=$arguments"
    $reportExit = $LASTEXITCODE
    Write-Host "Results: $output"
    Write-Host "JUnit: $junit"
    if ($reportExit -ne 0 -or $testExit -ne 0) { throw 'Evaluation did not complete. See Maven output and any saved report.' }
}
finally {
    $env:AI_CATALOG_EVALUATION_DATASET = $previousDataset
    Pop-Location
}
