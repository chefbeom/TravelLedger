Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$tokenPatterns = @(
    @{ Name = 'AWS access key'; Regex = 'AKIA[0-9A-Z]{16}' },
    @{ Name = 'Private key block'; Regex = '-----BEGIN (RSA|OPENSSH|EC|DSA|PRIVATE) KEY-----' },
    @{ Name = 'GitHub classic token'; Regex = 'ghp_[A-Za-z0-9_]{36,}' },
    @{ Name = 'GitHub fine-grained token'; Regex = 'github_pat_[A-Za-z0-9_]{80,}' },
    @{ Name = 'OpenAI-style API key'; Regex = 'sk-[A-Za-z0-9]{20,}' },
    @{ Name = 'Google API key'; Regex = 'AIza[0-9A-Za-z_-]{35}' },
    @{ Name = 'Stripe live secret key'; Regex = 'sk_live_[0-9A-Za-z]{16,}' },
    @{ Name = 'Slack token'; Regex = 'xox[baprs]-[A-Za-z0-9-]{10,}' },
    @{ Name = 'JWT token'; Regex = 'eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}' }
)

$sensitiveAssignmentPattern = [regex]'(?im)^\s*(?:export\s+)?[A-Z0-9_]*(?:API_KEY|SECRET|TOKEN|PASSWORD|PRIVATE_KEY|JWT_KEY)[A-Z0-9_]*\s*[:=]\s*["'']?([^"''\s#]+)'
$placeholderPattern = [regex]'(?i)(^$|example|placeholder|change-?me|replace-?me|your-|dummy|test|local|dev|^\$\{|^\$\(|^%|^<.*>$)'
$excludedPathPatterns = @(
    '^\.git/',
    '^\.gradle/',
    '^\.osmu-run/',
    '^backend/build/',
    '^frontend/dist/',
    '^frontend/node_modules/'
)

function Convert-ToRepoPath([string] $Path) {
    return ($Path -replace '\\', '/')
}

function Test-ExcludedPath([string] $Path) {
    $repoPath = Convert-ToRepoPath $Path
    foreach ($pattern in $excludedPathPatterns) {
        if ($repoPath -match $pattern) {
            return $true
        }
    }
    return $false
}

function Get-LineNumber([string] $Content, [int] $Index) {
    if ($Index -le 0) {
        return 1
    }
    return (($Content.Substring(0, $Index) -split "`r?`n").Count)
}

function Add-Finding([System.Collections.Generic.List[string]] $Findings, [string] $File, [int] $Line, [string] $Kind) {
    $Findings.Add("${File}:${Line} $Kind") | Out-Null
}

$trackedFiles = & git ls-files
if ($LASTEXITCODE -ne 0) {
    throw 'git ls-files failed. Run this script inside a Git working tree.'
}

$findings = [System.Collections.Generic.List[string]]::new()
foreach ($file in $trackedFiles) {
    if ([string]::IsNullOrWhiteSpace($file) -or (Test-ExcludedPath $file)) {
        continue
    }

    try {
        $content = Get-Content -LiteralPath $file -Raw -ErrorAction Stop
    } catch {
        continue
    }

    if ([string]::IsNullOrEmpty($content) -or $content.Contains([char]0)) {
        continue
    }

    foreach ($pattern in $tokenPatterns) {
        $regex = [regex]::new($pattern.Regex, [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
        foreach ($match in $regex.Matches($content)) {
            Add-Finding $findings $file (Get-LineNumber $content $match.Index) $pattern.Name
        }
    }

    foreach ($match in $sensitiveAssignmentPattern.Matches($content)) {
        $value = $match.Groups[1].Value.Trim().Trim('"', "'")
        if (-not $placeholderPattern.IsMatch($value)) {
            Add-Finding $findings $file (Get-LineNumber $content $match.Index) 'Sensitive environment assignment'
        }
    }
}

if ($findings.Count -gt 0) {
    Write-Host 'Potential secret-like values were found. Rotate real secrets and replace them with environment variables.'
    $findings | Sort-Object -Unique | ForEach-Object { Write-Host " - $_" }
    exit 1
}

Write-Host 'Secret scan passed.'