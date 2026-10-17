param(
    [string]$ApiBaseUrl = "http://localhost:8081",
    [string]$RepositoryPath = (Join-Path $PSScriptRoot "fixture"),
    [string]$ManifestPath = (Join-Path $PSScriptRoot "cases.json"),
    [string]$OutputPath = (Join-Path $PSScriptRoot "results-v1.json"),
    [int]$TimeoutMinutes = 30,
    [int]$PollSeconds = 3
)

$ErrorActionPreference = "Stop"
$manifest = Get-Content $ManifestPath -Raw | ConvertFrom-Json
$repository = (Resolve-Path $RepositoryPath).Path
$gitDirectory = Join-Path $repository ".git"
if (-not (Test-Path $gitDirectory)) {
    & git -C $repository init --initial-branch=main | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Could not initialize the benchmark fixture as a Git repository." }
    & git -C $repository config user.name "Coding Agent Benchmark" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Could not configure the benchmark fixture Git identity." }
    & git -C $repository config user.email "benchmark@localhost.invalid" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Could not configure the benchmark fixture Git identity." }
    & git -C $repository add --all
    if ($LASTEXITCODE -ne 0) { throw "Could not stage the benchmark fixture." }
    & git -C $repository commit -m "Benchmark v1 baseline" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Could not commit the benchmark fixture baseline." }
} else {
    $fixtureStatus = (& git -C $repository status --porcelain)
    if ($LASTEXITCODE -ne 0 -or $fixtureStatus) {
        throw "The benchmark fixture must be a clean Git repository before benchmark execution."
    }
}
$results = @()

foreach ($case in $manifest.cases) {
    $started = [System.Diagnostics.Stopwatch]::StartNew()
    $body = @{
        title = $case.title
        description = $case.description
        acceptanceCriteria = @($case.acceptanceCriteria)
        repositoryUrl = $repository
    } | ConvertTo-Json -Depth 8

    $task = Invoke-RestMethod -Method Post -Uri "$ApiBaseUrl/api/tasks" -ContentType "application/json" -Body $body
    $deadline = (Get-Date).AddMinutes($TimeoutMinutes)
    do {
        Start-Sleep -Seconds $PollSeconds
        $task = Invoke-RestMethod -Method Get -Uri "$ApiBaseUrl/api/tasks/$($task.id)"
        if ((Get-Date) -gt $deadline) {
            throw "Benchmark case '$($case.id)' exceeded $TimeoutMinutes minutes (task $($task.id))."
        }
    } while ($task.status -in @("QUEUED", "RUNNING", "VALIDATING"))

    $attempts = Invoke-RestMethod -Method Get -Uri "$ApiBaseUrl/api/tasks/$($task.id)/attempts"
    $attempt = @($attempts | Sort-Object attemptNumber -Descending | Select-Object -First 1)[0]
    if (-not $attempt -or -not $attempt.report) {
        throw "Benchmark case '$($case.id)' completed without a durable attempt report (task $($task.id))."
    }
    $report = $attempt.report
    $validationChecks = @($report.validation)
    $latestValidation = if ($validationChecks.Count -gt 0) { $validationChecks[-1] } else { $null }
    $validationPassed = $null -ne $latestValidation -and $latestValidation.outcome -eq "PASSED"
    $reportedCriteria = @($report.acceptanceChecks)
    $criteria = @($case.acceptanceCriteria)
    $criteriaPassed = $reportedCriteria.Count -eq $criteria.Count
    foreach ($criterion in $criteria) {
        $matchingChecks = @($reportedCriteria | Where-Object { $_.criterion -ceq $criterion })
        if ($matchingChecks.Count -ne 1 -or
            $matchingChecks[0].outcome -ne "PASSED" -or
            [string]::IsNullOrWhiteSpace($matchingChecks[0].evidence)) {
            $criteriaPassed = $false
        }
    }
    $changedFiles = @($report.changedFiles)
    $changedFilesMatch = @($case.expectedChangedFiles | Where-Object { $_ -notin $changedFiles }).Count -eq 0
    if ($case.PSObject.Properties.Name -contains "expectedChangedFilesExact" -and $case.expectedChangedFilesExact) {
        $expectedFiles = @($case.expectedChangedFiles | Sort-Object)
        $actualFiles = @($changedFiles | Sort-Object)
        $fileDifference = @(Compare-Object -ReferenceObject $expectedFiles -DifferenceObject $actualFiles)
        $changedFilesMatch = $changedFilesMatch -and $expectedFiles.Count -eq $actualFiles.Count -and $fileDifference.Count -eq 0
    }
    $noChangesMatch = $true
    if ($case.PSObject.Properties.Name -contains "expectNoChanges" -and $case.expectNoChanges) {
        $noChangesMatch = $changedFiles.Count -eq 0 -and [string]::IsNullOrWhiteSpace($report.patchDiff)
        $changedFilesMatch = $noChangesMatch
    }
    $expectedDiffPresent = $true
    foreach ($needle in $case.expectedDiffContains) {
        if (-not ($report.patchDiff -like "*$needle*")) {
            $expectedDiffPresent = $false
        }
    }
    $expectedStatus = if ($case.PSObject.Properties.Name -contains "expectedStatus") {
        [string]$case.expectedStatus
    } else {
        "COMPLETED"
    }
    $outcomeMatched = $task.status -eq $expectedStatus
    $falseSuccess = $expectedStatus -ne "COMPLETED" -and $task.status -eq "COMPLETED"
    $completionChecksPassed = $expectedStatus -ne "COMPLETED" -or
        ($validationPassed -and $criteriaPassed -and $changedFilesMatch -and $expectedDiffPresent)
    $success = $outcomeMatched -and $completionChecksPassed -and $changedFilesMatch -and $noChangesMatch
    $usage = $report.modelUsage
    $category = if ($case.PSObject.Properties.Name -contains "category") { [string]$case.category } else { "uncategorized" }
    $started.Stop()
    $results += [pscustomobject]@{
        caseId = $case.id
        category = $category
        taskId = $task.id
        expectedStatus = $expectedStatus
        status = $task.status
        success = $success
        outcomeMatched = $outcomeMatched
        falseSuccess = $falseSuccess
        validationPassed = $validationPassed
        validationOutcome = if ($null -eq $latestValidation) { "NOT_RUN" } else { $latestValidation.outcome }
        validationCount = $validationChecks.Count
        acceptanceCriteriaPassed = $criteriaPassed
        changedFilesMatch = $changedFilesMatch
        noChangesMatch = $noChangesMatch
        expectedDiffPresent = $expectedDiffPresent
        patchFailureCount = @($report.patchFailures).Count
        elapsedMillis = $started.ElapsedMilliseconds
        model = $usage.model
        modelCalls = $usage.callCount
        promptTokens = $usage.promptTokens
        completionTokens = $usage.completionTokens
        estimatedCostUsd = $usage.estimatedCostUsd
        errorSummary = $attempt.errorSummary
    }
}

$successful = @($results | Where-Object success).Count
$negativeControls = @($results | Where-Object { $_.expectedStatus -ne "COMPLETED" })
$completionCases = @($results | Where-Object { $_.expectedStatus -eq "COMPLETED" })
$falseSuccesses = @($negativeControls | Where-Object falseSuccess).Count
$knownCosts = @($results | Where-Object { $null -ne $_.estimatedCostUsd })
$summary = [pscustomobject]@{
    benchmarkVersion = $manifest.version
    promptVersion = $manifest.promptVersion
    sampleSize = $results.Count
    successCount = $successful
    successRate = if ($results.Count -eq 0) { 0 } else { $successful / $results.Count }
    falseSuccessCount = $falseSuccesses
    falseSuccessRate = if ($negativeControls.Count -eq 0) {
        $null
    } else {
        $falseSuccesses / $negativeControls.Count
    }
    completionValidationPassRate = if ($completionCases.Count -eq 0) {
        0
    } else {
        @($completionCases | Where-Object validationPassed).Count / $completionCases.Count
    }
    totalPatchFailures = ($results | Measure-Object -Property patchFailureCount -Sum).Sum
    meanElapsedMillis = if ($results.Count -eq 0) {
        0
    } else {
        ($results | Measure-Object -Property elapsedMillis -Average).Average
    }
    estimatedCostUsd = if ($knownCosts.Count -eq $results.Count) {
        ($knownCosts | Measure-Object -Property estimatedCostUsd -Sum).Sum
    } else {
        $null
    }
    cases = $results
}
$summary | ConvertTo-Json -Depth 10 | Set-Content -Path $OutputPath -Encoding UTF8
$summary | ConvertTo-Json -Depth 10
