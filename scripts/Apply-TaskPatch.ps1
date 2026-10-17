param(
    [Parameter(Mandatory = $true)]
    [string]$TaskId,
    [Parameter(Mandatory = $true)]
    [string]$AttemptId,
    [Parameter(Mandatory = $true)]
    [string]$RepositoryPath,
    [string]$ApiBaseUrl = "http://localhost:8081",
    [string]$PatchPath = (Join-Path ([System.IO.Path]::GetTempPath()) "$TaskId.patch"),
    [switch]$Apply
)

$ErrorActionPreference = "Stop"
$parsedTaskId = [guid]::Empty
$parsedAttemptId = [guid]::Empty
if (-not [guid]::TryParse($TaskId, [ref]$parsedTaskId) -or
    -not [guid]::TryParse($AttemptId, [ref]$parsedAttemptId)) {
    throw "TaskId and AttemptId must be UUIDs."
}
$TaskId = $parsedTaskId.ToString()
$AttemptId = $parsedAttemptId.ToString()
$repository = (Resolve-Path $RepositoryPath).Path
$rootOutput = & git -C $repository rev-parse --show-toplevel
if ($LASTEXITCODE -ne 0) {
    throw "RepositoryPath must point inside a Git working tree."
}
$root = (Resolve-Path $rootOutput.Trim()).Path
$status = & git -C $root status --porcelain --untracked-files=all
if ($LASTEXITCODE -ne 0) {
    throw "Could not inspect target repository status."
}
if ($status) {
    throw "Target repository must have a clean working tree; commit, stash, or remove local changes before applying a task patch."
}

# The API owns the "safe to export" rules (task and attempt COMPLETED, every validation check passed,
# and exactly one passing, evidenced acceptance check per criterion). A refusal is returned as 409
# with the reason in the body, so this script does not duplicate the policy.
$patchUri = "$ApiBaseUrl/api/tasks/$TaskId/attempts/$AttemptId/patch"
try {
    $diff = [string](Invoke-RestMethod -Method Get -Uri $patchUri -Headers @{ Accept = "text/x-patch" })
} catch {
    $detail = $null
    if ($_.ErrorDetails -and $_.ErrorDetails.Message) {
        $detail = $_.ErrorDetails.Message
    } elseif ($_.Exception.Response) {
        try {
            $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
            $detail = $reader.ReadToEnd()
        } catch {
            $detail = $null
        }
    }
    if ([string]::IsNullOrWhiteSpace($detail)) { $detail = $_.Exception.Message }
    throw "The server refused to export the patch: $detail"
}
if ([string]::IsNullOrWhiteSpace($diff)) {
    throw "The server returned an empty patch."
}

$resolvedPatchPath = [System.IO.Path]::GetFullPath($PatchPath)
$patchDirectory = Split-Path -Parent $resolvedPatchPath
if ($patchDirectory -and -not (Test-Path $patchDirectory)) {
    New-Item -ItemType Directory -Path $patchDirectory -Force | Out-Null
}
[System.IO.File]::WriteAllText($resolvedPatchPath, $diff, [System.Text.UTF8Encoding]::new($false))

& git -C $root apply --check $resolvedPatchPath
if ($LASTEXITCODE -ne 0) {
    throw "Git rejected the patch against the target repository. The patch was exported to '$resolvedPatchPath' but was not applied."
}

if ($Apply) {
    & git -C $root apply $resolvedPatchPath
    if ($LASTEXITCODE -ne 0) {
        throw "Git could not apply the patch. The exported patch remains at '$resolvedPatchPath'."
    }
    Write-Output "Patch applied to '$root'. Review the changes and run the repository's tests."
} else {
    Write-Output "Patch exported and checked against '$root': '$resolvedPatchPath'. No repository files were changed. Re-run with -Apply to apply it."
}
