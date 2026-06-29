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

$requiredMigrationSnippets = @{
    'V20260630_008__travel_route_segment_fields.sql' = @(
        'ALTER TABLE travel_route_segments',
        'MODIFY COLUMN route_path_json LONGTEXT NOT NULL',
        'ADD COLUMN IF NOT EXISTS line_color_hex VARCHAR(7) NULL',
        'ADD COLUMN IF NOT EXISTS line_style VARCHAR(20) NULL',
        'ADD COLUMN IF NOT EXISTS gpx_files_json LONGTEXT NULL'
    )
    'V20260630_009__ledger_entry_change_history_fields.sql' = @(
        'ALTER TABLE ledger_entry_change_histories',
        'MODIFY COLUMN summary VARCHAR(500) NOT NULL',
        'MODIFY COLUMN before_snapshot_json LONGTEXT NOT NULL',
        'MODIFY COLUMN after_snapshot_json LONGTEXT NOT NULL',
        'ADD COLUMN IF NOT EXISTS changes_json LONGTEXT NULL',
        'MODIFY COLUMN changes_json LONGTEXT NULL'
    )
    'V20260630_010__travel_media_asset_metadata_fields.sql' = @(
        'ALTER TABLE travel_media_assets',
        'ADD COLUMN IF NOT EXISTS gps_latitude DECIMAL(10,7) NULL',
        'ADD COLUMN IF NOT EXISTS gps_longitude DECIMAL(10,7) NULL',
        'ADD COLUMN IF NOT EXISTS representative_override BOOLEAN NOT NULL DEFAULT FALSE',
        'ADD COLUMN IF NOT EXISTS gps_extracted_at DATETIME NULL',
        'ADD INDEX IF NOT EXISTS idx_travel_media_assets_gps (gps_latitude, gps_longitude)',
        'ADD INDEX IF NOT EXISTS idx_travel_media_assets_rep_override (representative_override)'
    )
    'V20260630_011__travel_photo_cluster_tables.sql' = @(
        'CREATE TABLE IF NOT EXISTS travel_photo_clusters',
        'representative_media_id BIGINT NOT NULL',
        'representative_record_id BIGINT NOT NULL',
        'max_distance_meters DECIMAL(10,2) NOT NULL',
        'ADD INDEX IF NOT EXISTS idx_travel_photo_clusters_owner (owner_id)',
        'ADD INDEX IF NOT EXISTS idx_travel_photo_clusters_rep_media (representative_media_id)',
        'CREATE TABLE IF NOT EXISTS travel_photo_cluster_members',
        'ADD INDEX IF NOT EXISTS idx_travel_photo_cluster_members_owner (owner_id)',
        'ADD INDEX IF NOT EXISTS idx_travel_photo_cluster_members_cluster (cluster_id)',
        'ADD UNIQUE INDEX IF NOT EXISTS uq_travel_photo_cluster_members_cluster_media (cluster_id, media_id)'
    )
}
foreach ($entry in $requiredMigrationSnippets.GetEnumerator()) {
    $migrationPath = Join-Path $migrationRoot $entry.Key
    if (-not (Test-Path -LiteralPath $migrationPath)) {
        $findings.Add("Missing required migration content check target: $($entry.Key)") | Out-Null
        continue
    }

    $migrationContent = Get-Content -LiteralPath $migrationPath -Raw
    foreach ($snippet in $entry.Value) {
        if (-not $migrationContent.Contains($snippet)) {
            $findings.Add("$($entry.Key): missing required migration snippet: $snippet") | Out-Null
        }
    }
}

$strategyPath = 'docs/db_migration_strategy.md'
if (-not (Test-Path -LiteralPath $strategyPath)) {
    $findings.Add("Missing DB migration strategy document: $strategyPath") | Out-Null
} else {
    $strategyContent = Get-Content -LiteralPath $strategyPath -Raw
    $inventoryMatch = [regex]::Match($strategyContent, '(?s)## Current Migration Inventory(?<section>.*?)## Legacy Schema Updater Inventory')
    $evidenceMatch = [regex]::Match($strategyContent, '(?s)## Migration Operational Evidence(?<section>.*?)## Operating Rules')

    if (-not $inventoryMatch.Success) {
        $findings.Add('DB migration strategy is missing the Current Migration Inventory section before Legacy Schema Updater Inventory.') | Out-Null
    }
    if (-not $evidenceMatch.Success) {
        $findings.Add('DB migration strategy is missing the Migration Operational Evidence section before Operating Rules.') | Out-Null
    }

    foreach ($snippet in @(
        '## Startup DDL Freeze',
        'No new `ApplicationRunner` or `CommandLineRunner` may execute `CREATE TABLE`, `ALTER TABLE`, `DROP TABLE`, `CREATE INDEX`, or `ALTER INDEX`.',
        'New schema changes must be Flyway migrations only.',
        'Retire one legacy updater at a time'
    )) {
        if (-not $strategyContent.Contains($snippet)) {
            $findings.Add("DB migration strategy missing startup DDL freeze snippet: $snippet") | Out-Null
        }
    }

    $tick = [char]96
    foreach ($path in $trackedMigrations) {
        $fileName = [System.IO.Path]::GetFileName($path)
        $token = "$tick$fileName$tick"
        if ($inventoryMatch.Success -and -not $inventoryMatch.Groups['section'].Value.Contains($token)) {
            $findings.Add("DB migration strategy inventory missing tracked migration: $fileName") | Out-Null
        }
        if ($evidenceMatch.Success -and -not $evidenceMatch.Groups['section'].Value.Contains("| $token |")) {
            $findings.Add("DB migration strategy operational evidence missing tracked migration: $fileName") | Out-Null
        }
    }
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

$startupDdlPattern = [regex]'(?is)\b(ApplicationRunner|CommandLineRunner)\b.*\b(CREATE\s+TABLE|ALTER\s+TABLE|DROP\s+TABLE|CREATE\s+INDEX|ALTER\s+INDEX)\b'
if (Test-Path -LiteralPath $sourceRoot) {
    Get-ChildItem -LiteralPath $sourceRoot -Recurse -File -Filter '*.java' | ForEach-Object {
        $relativePath = [System.IO.Path]::GetRelativePath($repoRoot, $_.FullName) -replace '\\', '/'
        $source = Get-Content -LiteralPath $_.FullName -Raw
        if ($startupDdlPattern.IsMatch($source) -and -not $expectedLegacySchemaUpdaterSet.ContainsKey($relativePath)) {
            $findings.Add("Unexpected startup DDL runner found; add a Flyway migration instead of ApplicationRunner/CommandLineRunner schema mutation: $relativePath") | Out-Null
        }
    }
}

if ($findings.Count -gt 0) {
    Write-Host 'DB migration discipline check failed.'
    $findings | Sort-Object -Unique | ForEach-Object { Write-Host " - $_" }
    exit 1
}

Write-Host 'DB migration discipline check passed.'
