Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$migrationRoot = 'backend/src/main/resources/db/migration'
if (-not (Test-Path -LiteralPath $migrationRoot)) {
    throw "Migration directory not found: $migrationRoot"
}

$trackedMigrations = & git ls-files "$migrationRoot/*.sql"
if ($LASTEXITCODE -ne 0) {
    throw 'git ls-files failed. Run this script inside a Git working tree.'
}

$versionedPattern = [regex]'^(?<version>V\d{8}_\d{3})__(?<description>[a-z0-9][a-z0-9_]*?)\.sql$'
$repeatablePattern = [regex]'^R__(?<description>[a-z0-9][a-z0-9_]*?)\.sql$'
$versions = @{}
$findings = [System.Collections.Generic.List[string]]::new()

foreach ($path in $trackedMigrations) {
    $fileName = [System.IO.Path]::GetFileName($path)
    $versionedMatch = $versionedPattern.Match($fileName)
    $repeatableMatch = $repeatablePattern.Match($fileName)

    if (-not $versionedMatch.Success -and -not $repeatableMatch.Success) {
        $findings.Add("${path}: migration filename must match VYYYYMMDD_NNN__description.sql or R__description.sql") | Out-Null
        continue
    }

    if ($versionedMatch.Success) {
        $version = $versionedMatch.Groups['version'].Value
        if ($versions.ContainsKey($version)) {
            $findings.Add("${path}: duplicate migration version also used by $($versions[$version])") | Out-Null
        } else {
            $versions[$version] = $path
        }
    }
}

if ($trackedMigrations.Count -eq 0) {
    $findings.Add('No migration scripts are tracked.') | Out-Null
}

$baseline = Join-Path $migrationRoot 'V20260629_000__baseline_marker.sql'
if (-not (Test-Path -LiteralPath $baseline)) {
    $findings.Add("Missing baseline marker migration: $baseline") | Out-Null
}

if ($findings.Count -gt 0) {
    Write-Host 'DB migration discipline check failed.'
    $findings | Sort-Object -Unique | ForEach-Object { Write-Host " - $_" }
    exit 1
}

Write-Host 'DB migration discipline check passed.'