param(
    [string]$ApplicationConfigPath = "backend/src/main/resources/application.yml",
    [string[]]$EnvExamplePath = @(".env.example", ".env.oci.app.example")
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent $PSScriptRoot
$appPath = Join-Path $repoRoot $ApplicationConfigPath
$contractPath = Join-Path $repoRoot 'docs/env_configuration_contract.md'

if (-not (Test-Path -LiteralPath $appPath)) {
    throw "Application config not found: $ApplicationConfigPath"
}
if (-not (Test-Path -LiteralPath $contractPath)) {
    throw "Environment configuration contract not found: docs/env_configuration_contract.md"
}

$appText = Get-Content -Raw -LiteralPath $appPath
$contractText = Get-Content -Raw -LiteralPath $contractPath
$requiredVars = [regex]::Matches($appText, '\$\{([A-Z][A-Z0-9_]*)') |
    ForEach-Object { $_.Groups[1].Value } |
    Sort-Object -Unique

$requiredGroups = [ordered]@{
    'database-and-migrations' = @(
        'DB_URL', 'DB_DRIVER', 'DB_SERVER', 'DB_ID', 'DB_PASS',
        'JPA_SHOW_SQL', 'JPA_FORMAT_SQL', 'H2_CONSOLE_ENABLED',
        'DB_MIGRATION_ENABLED', 'DB_MIGRATION_BASELINE_ON_MIGRATE',
        'DB_MIGRATION_BASELINE_VERSION', 'DB_MIGRATION_VALIDATE_ON_MIGRATE'
    )
    'object-storage-and-presigned-url' = @(
        'MINIO_API', 'MINIO_PUBLIC_API', 'MINIO_NAME', 'MINIO_SECRET',
        'MINIO_CLOUD_BUCKET', 'MINIO_WORKSPACE_BUCKET',
        'MINIO_PRESIGNED_URL_EXPIRY_SECONDS', 'MINIO_STORAGE_CAPACITY_BYTES'
    )
    'auth-session-and-seed' = @('JWT_KEY', 'JWT_EXPIRE', 'APP_SEED_ENABLED')
    'travel-integrations-and-media' = @(
        'TRAVEL_EXCHANGE_RATE_BASE_URL', 'TRAVEL_EXCHANGE_RATE_CACHE_MINUTES',
        'TRAVEL_REVERSE_GEOCODE_BASE_URL', 'TRAVEL_REVERSE_GEOCODE_USER_AGENT',
        'TRAVEL_REVERSE_GEOCODE_REQUEST_MIN_INTERVAL_MS', 'TRAVEL_REVERSE_GEOCODE_CACHE_TTL_HOURS',
        'TRAVEL_SUMMARY_CACHE_TTL_SECONDS', 'TRAVEL_MEDIA_DOWNLOAD_CACHE_TTL_SECONDS',
        'TRAVEL_THUMBNAIL_BACKFILL_ENABLED', 'TRAVEL_THUMBNAIL_BACKFILL_FIXED_DELAY_MS',
        'TRAVEL_THUMBNAIL_BACKFILL_INITIAL_DELAY_MS', 'TRAVEL_THUMBNAIL_BACKFILL_PAGE_SIZE',
        'TRAVEL_THUMBNAIL_BACKFILL_MAX_ITEMS_PER_RUN', 'TRAVEL_MEDIA_STORAGE_PATH',
        'TRAVEL_MEDIA_OBJECT_PREFIX', 'TRAVEL_PRESIGNED_UPLOAD_ENABLED'
    )
    'ledger-ocr' = @(
        'LEDGER_OCR_ENABLED', 'LEDGER_OCR_BASE_URL', 'LEDGER_OCR_WORKFLOW_URL',
        'LEDGER_OCR_API_KEY', 'LEDGER_OCR_CONNECT_TIMEOUT', 'LEDGER_OCR_READ_TIMEOUT',
        'LEDGER_OCR_MAX_FILE_SIZE'
    )
    'ledger-ai-provider' = @(
        'APP_LEDGER_AI_ENABLED', 'APP_LEDGER_AI_PROVIDER', 'APP_LEDGER_AI_WORKFLOW_URL',
        'APP_LEDGER_AI_API_KEY', 'APP_LEDGER_AI_API_KEY_HEADER', 'APP_LEDGER_AI_MODEL',
        'APP_LEDGER_AI_LMSTUDIO_BASE_URL', 'APP_LEDGER_AI_LMSTUDIO_CHAT_PATH',
        'APP_LEDGER_AI_LMSTUDIO_MODELS_PATH', 'APP_LEDGER_AI_LMSTUDIO_API_KEY',
        'APP_LEDGER_AI_TEMPERATURE', 'APP_LEDGER_AI_MAX_TOKENS',
        'APP_LEDGER_AI_CONNECT_TIMEOUT', 'APP_LEDGER_AI_READ_TIMEOUT',
        'APP_LEDGER_AI_ENFORCE_PROVIDER_URL_ALLOWLIST', 'APP_LEDGER_AI_ALLOWED_PROVIDER_HOSTS',
        'APP_LEDGER_AI_HISTORY_RETENTION_ENABLED', 'APP_LEDGER_AI_HISTORY_RETENTION_DAYS',
        'APP_LEDGER_AI_HISTORY_RETENTION_CRON', 'APP_LEDGER_AI_HISTORY_RETENTION_ZONE'
    )
    'redis-state-and-cache' = @(
        'REDIS_CACHE_HOST', 'REDIS_CACHE_PORT', 'REDIS_CACHE_PASSWORD',
        'REDIS_CACHE_DATABASE', 'REDIS_CACHE_SSL', 'REDIS_STATE_HOST',
        'REDIS_STATE_PORT', 'REDIS_STATE_PASSWORD', 'REDIS_STATE_DATABASE', 'REDIS_STATE_SSL'
    )
    'family-support-and-data-ops' = @(
        'FAMILY_MEDIA_STORAGE_PATH', 'FAMILY_MEDIA_OBJECT_PREFIX',
        'SUPPORT_ATTACHMENT_STORAGE_PATH', 'DATA_OPS_BACKUP_WORKDIR',
        'DATA_OPS_BACKUP_REMOTE_NAME', 'DATA_OPS_BACKUP_REMOTE_DIR',
        'DATA_OPS_MINIO_BACKUP_REMOTE_DIR', 'DATA_OPS_RCLONE_CONFIG_PATH',
        'DATA_OPS_BACKUP_ZONE', 'DATA_OPS_DB_BACKUP_ENABLED', 'DATA_OPS_DB_BACKUP_CRON',
        'DATA_OPS_MINIO_BACKUP_ENABLED', 'DATA_OPS_MINIO_BACKUP_CRON'
    )
}

$allowedExtraPatterns = @(
    '^COMPOSE_PROJECT_NAME$',
    '^TZ$',
    '^OCI_',
    '^APP_EXTERNAL_',
    '^BACKEND_INTERNAL_',
    '^DB_NAME$',
    '^DB_USER$',
    '^DB_PASSWORD$',
    '^DB_ROOT_PASSWORD$',
    '^DB_INTERNAL_',
    '^DATA_DB_',
    '^DATA_MINIO_',
    '^MINIO_ROOT_',
    '^MINIO_API_INTERNAL_URL$',
    '^MINIO_API_EXTERNAL_PORT$',
    '^MINIO_CONSOLE_EXTERNAL_PORT$',
    '^MINIO_CONSOLE_EXTERNAL_URL$',
    '^DATA_OPS_BACKUP_HOST_PATH$',
    '^DATA_OPS_RCLONE_CONFIG_HOST_PATH$'
)

$valueRules = @(
    @{ Name = 'APP_LEDGER_AI_LMSTUDIO_BASE_URL'; Type = 'equals'; Value = 'http://172.18.240.1:1234' },
    @{ Name = 'APP_LEDGER_AI_LMSTUDIO_CHAT_PATH'; Type = 'equals'; Value = '/api/v1/chat' },
    @{ Name = 'APP_LEDGER_AI_LMSTUDIO_MODELS_PATH'; Type = 'equals'; Value = '/api/v1/models' },
    @{ Name = 'APP_LEDGER_AI_API_KEY_HEADER'; Type = 'equals'; Value = 'X-TravelLedger-AI-Key' },
    @{ Name = 'APP_LEDGER_AI_ALLOWED_PROVIDER_HOSTS'; Type = 'contains'; Value = '172.18.240.1' },
    @{ Name = 'MINIO_PRESIGNED_URL_EXPIRY_SECONDS'; Type = 'integer-max'; Value = 604800 }
)

$ociAppRequiredValues = @{
    'APP_SEED_ENABLED' = 'false'
    'APP_LEDGER_AI_ENFORCE_PROVIDER_URL_ALLOWLIST' = 'true'
}

$allowedSecretExampleValues = @(
    '',
    'calen1234',
    'change-me-db-user-password',
    'change-me-db-root-password',
    'change-me-minio-password',
    'change-me-remember-me-key',
    'change-me-ocr-api-key',
    'change-me-ledger-ai-api-key',
    'change-me-cache-password',
    'change-me-state-password'
)

function Test-AllowedExtra([string]$name) {
    foreach ($pattern in $allowedExtraPatterns) {
        if ($name -match $pattern) {
            return $true
        }
    }
    return $false
}

function Add-Finding {
    param(
        [System.Collections.Generic.List[string]]$Findings,
        [string]$Message
    )
    $Findings.Add($Message) | Out-Null
}

$findings = New-Object System.Collections.Generic.List[string]

foreach ($groupName in $requiredGroups.Keys) {
    foreach ($name in $requiredGroups[$groupName]) {
        if ($requiredVars -notcontains $name) {
            Add-Finding $findings "Required $groupName variable is not referenced by application.yml: $name"
        }
        if ($contractText -notlike "*$name*") {
            Add-Finding $findings "Environment contract is missing required $groupName variable: $name"
        }
    }
}

$totalEnvVars = 0

foreach ($relativeEnvPath in $EnvExamplePath) {
    $envPath = Join-Path $repoRoot $relativeEnvPath
    if (-not (Test-Path -LiteralPath $envPath)) {
        throw "Environment example not found: $relativeEnvPath"
    }

    $envLines = Get-Content -LiteralPath $envPath
    $envVars = [ordered]@{}
    $duplicates = New-Object System.Collections.Generic.List[string]
    $malformed = New-Object System.Collections.Generic.List[string]

    for ($i = 0; $i -lt $envLines.Count; $i++) {
        $lineNumber = $i + 1
        $line = $envLines[$i]
        if ($line -match '^\s*$' -or $line -match '^\s*#') {
            continue
        }
        if ($line -notmatch '^\s*([A-Z][A-Z0-9_]*)=(.*)$') {
            $malformed.Add("line ${lineNumber}: $line") | Out-Null
            continue
        }

        $name = $Matches[1]
        $value = $Matches[2]
        if ($envVars.Contains($name)) {
            $duplicates.Add($name) | Out-Null
        } else {
            $envVars[$name] = $value
        }
    }

    $missing = $requiredVars | Where-Object { -not $envVars.Contains($_) }
    $undocumented = $envVars.Keys |
        Where-Object { $requiredVars -notcontains $_ -and -not (Test-AllowedExtra $_) } |
        Sort-Object -Unique

    foreach ($item in $malformed) {
        Add-Finding $findings "Malformed $relativeEnvPath line: $item"
    }
    foreach ($item in ($duplicates | Sort-Object -Unique)) {
        Add-Finding $findings "Duplicate $relativeEnvPath variable: $item"
    }
    foreach ($item in $missing) {
        Add-Finding $findings "Variable referenced by application.yml but missing from ${relativeEnvPath}: $item"
    }
    foreach ($item in $undocumented) {
        Add-Finding $findings "Variable in $relativeEnvPath is not referenced by application.yml or allowlisted as compose-only: $item"
    }

    foreach ($groupName in $requiredGroups.Keys) {
        foreach ($name in $requiredGroups[$groupName]) {
            if (-not $envVars.Contains($name)) {
                Add-Finding $findings "Required $groupName variable is missing from ${relativeEnvPath}: $name"
            }
        }
    }

    foreach ($name in $envVars.Keys) {
        $value = [string]$envVars[$name]
        if ($name -match '(_ENABLED|_SSL|_SHOW_SQL|_FORMAT_SQL|_BASELINE_ON_MIGRATE|_VALIDATE_ON_MIGRATE)$' -and $value -notmatch '^(true|false)$') {
            Add-Finding $findings "Boolean-like variable in $relativeEnvPath must be true or false: ${name}=${value}"
        }
        if ($name -match '(PASSWORD|PASS|SECRET|API_KEY|JWT_KEY)$' -and $allowedSecretExampleValues -notcontains $value) {
            Add-Finding $findings "Secret-like variable in $relativeEnvPath must be blank or use an approved placeholder: ${name}=${value}"
        }
    }

    foreach ($rule in $valueRules) {
        $name = [string]$rule.Name
        if (-not $envVars.Contains($name)) {
            Add-Finding $findings "Value rule target is missing from ${relativeEnvPath}: $name"
            continue
        }
        $actual = [string]$envVars[$name]
        $expected = $rule.Value
        switch ([string]$rule.Type) {
            'equals' {
                if ($actual -ne [string]$expected) {
                    Add-Finding $findings "${relativeEnvPath} must set ${name}=$expected but found $actual"
                }
            }
            'contains' {
                if ($actual -notlike "*$expected*") {
                    Add-Finding $findings "${relativeEnvPath} $name must contain $expected but found $actual"
                }
            }
            'integer-max' {
                $parsed = 0
                if (-not [int]::TryParse($actual, [ref]$parsed) -or $parsed -gt [int]$expected) {
                    Add-Finding $findings "${relativeEnvPath} $name must be an integer <= $expected but found $actual"
                }
            }
            default {
                Add-Finding $findings "Unknown value rule type for ${name}: $($rule.Type)"
            }
        }
    }

    if ($relativeEnvPath -eq '.env.oci.app.example') {
        foreach ($name in $ociAppRequiredValues.Keys) {
            if (-not $envVars.Contains($name)) {
                Add-Finding $findings "OCI app env example is missing required production-safety variable: $name"
                continue
            }
            $expected = [string]$ociAppRequiredValues[$name]
            $actual = [string]$envVars[$name]
            if ($actual -ne $expected) {
                Add-Finding $findings "OCI app env example must set ${name}=$expected but found $actual"
            }
        }
    }

    $totalEnvVars += $envVars.Count
    Write-Host "Checked ${relativeEnvPath}: $($requiredVars.Count) application variables documented, $($envVars.Count) example variables parsed."
}

if ($findings.Count -gt 0) {
    Write-Host "Environment configuration drift detected:"
    $findings | ForEach-Object { Write-Host "  - $_" }
    Write-Host "Update env examples, application.yml, docs/env_configuration_contract.md, or the compose-only allowlist in scripts/verify-env-sync.ps1."
    exit 1
}

Write-Host "Environment configuration sync OK: $($requiredVars.Count) application variables documented across $($EnvExamplePath.Count) env example file(s), $totalEnvVars example entries checked."
