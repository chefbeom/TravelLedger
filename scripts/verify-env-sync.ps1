param(
    [string]$ApplicationConfigPath = "backend/src/main/resources/application.yml",
    [string]$EnvExamplePath = ".env.example"
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$appPath = Join-Path $repoRoot $ApplicationConfigPath
$envPath = Join-Path $repoRoot $EnvExamplePath

if (-not (Test-Path $appPath)) {
    throw "Application config not found: $ApplicationConfigPath"
}
if (-not (Test-Path $envPath)) {
    throw "Environment example not found: $EnvExamplePath"
}

$appText = Get-Content -Raw -Path $appPath
$requiredVars = [regex]::Matches($appText, '\$\{([A-Z][A-Z0-9_]*)') |
    ForEach-Object { $_.Groups[1].Value } |
    Sort-Object -Unique

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

$missing = $requiredVars | Where-Object { -not $envVars.Contains($_) }
$undocumented = $envVars.Keys |
    Where-Object { $requiredVars -notcontains $_ -and -not (Test-AllowedExtra $_) } |
    Sort-Object -Unique

$hasFailure = $false

if ($malformed.Count -gt 0) {
    $hasFailure = $true
    Write-Host "Malformed .env.example lines:"
    $malformed | ForEach-Object { Write-Host "  $_" }
}
if ($duplicates.Count -gt 0) {
    $hasFailure = $true
    Write-Host "Duplicate .env.example variables:"
    $duplicates | Sort-Object -Unique | ForEach-Object { Write-Host "  $_" }
}
if ($missing.Count -gt 0) {
    $hasFailure = $true
    Write-Host "Variables referenced by application.yml but missing from .env.example:"
    $missing | ForEach-Object { Write-Host "  $_" }
}
if ($undocumented.Count -gt 0) {
    $hasFailure = $true
    Write-Host "Variables in .env.example that are not referenced by application.yml or allowlisted as compose-only:"
    $undocumented | ForEach-Object { Write-Host "  $_" }
}
if ($invalidBooleans.Count -gt 0) {
    $hasFailure = $true
    Write-Host "Boolean-like variables must be true or false:"
    $invalidBooleans | ForEach-Object { Write-Host "  $_" }
}

if ($hasFailure) {
    Write-Host "Environment configuration drift detected. Update .env.example, application.yml, or the allowlist in scripts/verify-env-sync.ps1."
    exit 1
}

Write-Host "Environment configuration sync OK: $($requiredVars.Count) application variables documented, $($envVars.Count) example variables checked."