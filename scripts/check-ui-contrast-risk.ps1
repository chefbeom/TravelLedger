param(
    [string]$Path = "frontend/src",
    [switch]$FailOnRisk
)

$ErrorActionPreference = "Stop"

$root = Resolve-Path $Path
$patterns = @(
    [pscustomobject]@{ Name = "Transparent text"; Pattern = "color\s*:\s*transparent|-webkit-text-fill-color\s*:\s*transparent"; Severity = "high"; Reason = "Actual text can disappear unless this is intentional masking." },
    [pscustomobject]@{ Name = "Very low opacity"; Pattern = "opacity\s*:\s*0\.[0-4][0-9]?"; Severity = "high"; Reason = "Text below roughly 50% opacity is often unreadable on themed backgrounds." },
    [pscustomobject]@{ Name = "Hidden visibility"; Pattern = "visibility\s*:\s*hidden|display\s*:\s*none"; Severity = "medium"; Reason = "Confirm hidden text is decorative or accessibility-only, not user-facing content." },
    [pscustomobject]@{ Name = "Light gray text"; Pattern = "color\s*:\s*#(?:d[0-9a-f]{2}|e[0-9a-f]{2}|f[0-9a-f]{2}|[a-f]{3})\b"; Severity = "medium"; Reason = "Light text can disappear on light theme backgrounds." },
    [pscustomobject]@{ Name = "Text shadow masking"; Pattern = "text-shadow\s*:\s*0\s+0\s+0\s+transparent"; Severity = "medium"; Reason = "Text-shadow masking can make real form values invisible." },
    [pscustomobject]@{ Name = "Webkit text security"; Pattern = "-webkit-text-security"; Severity = "medium"; Reason = "Confirm only secret fields use text security." },
    [pscustomobject]@{ Name = "Backdrop blur"; Pattern = "backdrop-filter|-webkit-backdrop-filter"; Severity = "low"; Reason = "Blurred transparent panels need explicit text/background contrast." },
    [pscustomobject]@{ Name = "Image overlay text"; Pattern = "background\s*:\s*transparent|position\s*:\s*absolute"; Severity = "low"; Reason = "Text placed over images/maps should have a contrast layer." }
)

$files = Get-ChildItem -Path $root -Recurse -File -Include *.css,*.vue,*.scss,*.sass -ErrorAction SilentlyContinue
$findings = New-Object System.Collections.Generic.List[object]

foreach ($file in $files) {
    $lines = Get-Content -LiteralPath $file.FullName
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]
        foreach ($rule in $patterns) {
            if ($line -match $rule.Pattern) {
                $findings.Add([pscustomobject]@{
                    Severity = $rule.Severity
                    Rule = $rule.Name
                    File = $file.FullName
                    Line = $i + 1
                    Text = $line.Trim()
                    Reason = $rule.Reason
                }) | Out-Null
            }
        }
    }
}

if ($findings.Count -eq 0) {
    Write-Host "No contrast-risk CSS patterns found."
    exit 0
}

$ordered = $findings | Sort-Object @{ Expression = { @{ high = 0; medium = 1; low = 2 }[$_.Severity] } }, File, Line
$ordered | Format-Table Severity, Rule, File, Line, Text -AutoSize

Write-Host ""
Write-Host "Summary: $($findings.Count) potential contrast-risk pattern(s) found."
Write-Host "Use this as a review aid only. Actual completion still requires browser rendering checks in basic/dark and mobile/desktop modes."

if ($FailOnRisk -and ($findings | Where-Object { $_.Severity -in @("high", "medium") })) {
    exit 1
}