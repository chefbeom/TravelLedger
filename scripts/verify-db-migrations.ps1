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

$expectedLegacySchemaUpdaters = @(
    'backend/src/main/java/com/playdata/calen/ledger/config/LedgerAiAnalysisSchemaUpdater.java',
    'backend/src/main/java/com/playdata/calen/ledger/config/LedgerEntrySchemaUpdater.java',
    'backend/src/main/java/com/playdata/calen/ledger/config/LedgerEntryChangeHistorySchemaUpdater.java',
    'backend/src/main/java/com/playdata/calen/travel/config/TravelMediaAssetSchemaUpdater.java',
    'backend/src/main/java/com/playdata/calen/travel/config/TravelPhotoClusterSchemaUpdater.java',
    'backend/src/main/java/com/playdata/calen/travel/config/TravelRouteSchemaUpdater.java'
)
$repoRoot = (Get-Location).Path
$sourceRoot = 'backend/src/main/java'
$currentLegacySchemaUpdaters = @()
if (Test-Path -LiteralPath $sourceRoot) {
    $currentLegacySchemaUpdaters = @(Get-ChildItem -LiteralPath $sourceRoot -Recurse -File -Filter '*SchemaUpdater.java' | ForEach-Object {
            [System.IO.Path]::GetRelativePath($repoRoot, $_.FullName) -replace '\\', '/'
        })
}

$expectedLegacySchemaUpdaterSet = @{}
foreach ($path in $expectedLegacySchemaUpdaters) {
    $expectedLegacySchemaUpdaterSet[$path] = $true
    if (-not (Test-Path -LiteralPath $path)) {
        $findings.Add("Documented legacy SchemaUpdater is missing; update docs/db_migration_strategy.md and this verifier when retiring it: $path") | Out-Null
    }
}

foreach ($path in $currentLegacySchemaUpdaters) {
    if (-not $expectedLegacySchemaUpdaterSet.ContainsKey($path)) {
        $findings.Add("Unexpected legacy SchemaUpdater found; add a Flyway migration plan instead of startup schema mutation, or document the temporary exception: $path") | Out-Null
    }
}

if ($findings.Count -gt 0) {
    Write-Host 'DB migration discipline check failed.'
    $findings | Sort-Object -Unique | ForEach-Object { Write-Host " - $_" }
    exit 1
}

Write-Host 'DB migration discipline check passed.'