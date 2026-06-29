param(
    [string]$ApplicationConfigPath = "backend/src/main/resources/application.yml",
    [string[]]$EnvExamplePath = @(".env.example", ".env.oci.app.example")
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$appPath = Join-Path $repoRoot $ApplicationConfigPath

if (-not (Test-Path $appPath)) {
    throw "Application config not found: $ApplicationConfigPath"
}

$appText = Get-Content -Raw -Path $appPath
$requiredVars = [regex]::Matches($appText, '\$\{([A-Z][A-Z0-9_]*)') |
    ForEach-Object { $_.Groups[1].Value } |
    Sort-Object -Unique

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

function Test-AllowedExtra([string]$name) {
    foreach ($pattern in $allowedExtraPatterns) {
        if ($name -match $pattern) {
            return $true
        }
    }
    return $false
}

$totalEnvVars = 0
$hasFailure = $false

foreach ($relativeEnvPath in $EnvExamplePath) {
    $envPath = Join-Path $repoRoot $relativeEnvPath
    if (-not (Test-Path $envPath)) {
        throw "Environment example not found: $relativeEnvPath"
    }

    $envLines = Get-Content -Path $envPath
    $envVars = [ordered]@{}
    $duplicates = New-Object System.Collections.Generic.List[string]
    $malformed = New-Object System.Collections.Generic.List[string]
    $invalidBooleans = New-Object System.Collections.Generic.List[string]

    for ($i = 0; $i -lt $envLines.Count; $i++) {
        $lineNumber = $i + 1
        $line = $envLines[$i]
        if ($line -match '^\s*$' -or $line -match '^\s*#') {
            continue
        }
        if ($line -notmatch '^\s*([A-Z][A-Z0-9_]*)=(.*)$') {
            $malformed.Add("line ${lineNumber}: $line")
            continue
        }

        $name = $Matches[1]
        $value = $Matches[2]
        if ($envVars.Contains($name)) {
            $duplicates.Add($name)
        } else {
            $envVars[$name] = $value
        }

        if ($name -match '(_ENABLED|_SSL|_SHOW_SQL|_FORMAT_SQL|_BASELINE_ON_MIGRATE|_VALIDATE_ON_MIGRATE)$' -and $value -notmatch '^(true|false)$') {
            $invalidBooleans.Add("${name}=${value}")
        }
    }

    $missing = $requiredVars | Where-Object { -not $envVars.Contains($_) }
    $undocumented = $envVars.Keys |
        Where-Object { $requiredVars -notcontains $_ -and -not (Test-AllowedExtra $_) } |
        Sort-Object -Unique

    if ($malformed.Count -gt 0) {
        $hasFailure = $true
        Write-Host "Malformed $relativeEnvPath lines:"
        $malformed | ForEach-Object { Write-Host "  $_" }
    }
    if ($duplicates.Count -gt 0) {
        $hasFailure = $true
        Write-Host "Duplicate $relativeEnvPath variables:"
        $duplicates | Sort-Object -Unique | ForEach-Object { Write-Host "  $_" }
    }
    if ($missing.Count -gt 0) {
        $hasFailure = $true
        Write-Host "Variables referenced by application.yml but missing from ${relativeEnvPath}:"
        $missing | ForEach-Object { Write-Host "  $_" }
    }
    if ($undocumented.Count -gt 0) {
        $hasFailure = $true
        Write-Host "Variables in $relativeEnvPath that are not referenced by application.yml or allowlisted as compose-only:"
        $undocumented | ForEach-Object { Write-Host "  $_" }
    }
    if ($invalidBooleans.Count -gt 0) {
        $hasFailure = $true
        Write-Host "Boolean-like variables in $relativeEnvPath must be true or false:"
        $invalidBooleans | ForEach-Object { Write-Host "  $_" }
    }

    $totalEnvVars += $envVars.Count
    Write-Host "Checked ${relativeEnvPath}: $($requiredVars.Count) application variables documented, $($envVars.Count) example variables parsed."
}

if ($hasFailure) {
    Write-Host "Environment configuration drift detected. Update env examples, application.yml, or the allowlist in scripts/verify-env-sync.ps1."
    exit 1
}

Write-Host "Environment configuration sync OK: $($requiredVars.Count) application variables documented across $($EnvExamplePath.Count) env example file(s), $totalEnvVars example entries checked."