Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$planPath = 'docs/service_decomposition_plan.md'
if (-not (Test-Path -LiteralPath $planPath)) {
    throw "Service decomposition plan not found: $planPath"
}

$content = Get-Content -LiteralPath $planPath -Raw
$findings = [System.Collections.Generic.List[string]]::new()

$trackedServices = @(
    @{
        Name = 'LedgerAiAnalysisService'
        Path = 'backend/src/main/java/com/playdata/calen/ledger/ai/LedgerAiAnalysisService.java'
        MaxLines = 1255
        RequiredSections = @('## Ledger AI Extraction Queue', '### Ledger AI Exit Criteria')
    },
    @{
        Name = 'TravelService'
        Path = 'backend/src/main/java/com/playdata/calen/travel/service/TravelService.java'
        MaxLines = 3000
        RequiredSections = @('## Travel Service Extraction Queue', '### Travel Exit Criteria')
    }
)

foreach ($service in $trackedServices) {
    $name = $service.Name
    $path = $service.Path
    if (-not (Test-Path -LiteralPath $path)) {
        $findings.Add("Tracked service file is missing: $path") | Out-Null
        continue
    }

    $actualLines = (Get-Content -LiteralPath $path).Count
    $maxLines = [int]$service.MaxLines
    if ($actualLines -gt $maxLines) {
        $findings.Add("Tracked service exceeds decomposition line budget: $name actual=$actualLines max=$maxLines") | Out-Null
    }

    $escapedName = [regex]::Escape($name)
    $pattern = [regex]("\| ``" + $escapedName + "`` \| (?<lines>\d+) lines \|")
    $match = $pattern.Match($content)
    if (-not $match.Success) {
        $findings.Add("Service decomposition plan is missing Current Baseline row for $name.") | Out-Null
    } else {
        $documentedLines = [int]$match.Groups['lines'].Value
        if ($documentedLines -ne $actualLines) {
            $findings.Add("Service decomposition plan line count for $name is stale: documented=$documentedLines actual=$actualLines") | Out-Null
        }
    }

    $budgetPattern = [regex]("\| ``" + $escapedName + "`` \| (?<baseline>\d+) lines \| (?<budget>\d+) lines \|")
    $budgetMatch = $budgetPattern.Match($content)
    if (-not $budgetMatch.Success) {
        $findings.Add("Service decomposition plan is missing CI Line Budget row for $name.") | Out-Null
    } else {
        $documentedBaseline = [int]$budgetMatch.Groups['baseline'].Value
        $documentedBudget = [int]$budgetMatch.Groups['budget'].Value
        if ($documentedBaseline -ne $actualLines) {
            $findings.Add("Service decomposition CI budget baseline for $name is stale: documented=$documentedBaseline actual=$actualLines") | Out-Null
        }
        if ($documentedBudget -ne $maxLines) {
            $findings.Add("Service decomposition CI budget max for $name is stale: documented=$documentedBudget expected=$maxLines") | Out-Null
        }
    }

    foreach ($section in $service.RequiredSections) {
        if (-not $content.Contains($section)) {
            $findings.Add("Service decomposition plan missing section for $name: $section") | Out-Null
        }
    }
}

$requiredGuardrails = @(
    'API stability',
    'Security first',
    'Small slices',
    'Pure before side effects',
    'Test before move',
    'Transaction clarity'
)
foreach ($guardrail in $requiredGuardrails) {
    if (-not $content.Contains($guardrail)) {
        $findings.Add("Service decomposition guardrail missing: $guardrail") | Out-Null
    }
}

$requiredReviewItems = @(
    'Identify the exact methods moved',
    'owner scope, token checks, or provider safety',
    'DTO contracts stable',
    'logs API keys, signed URLs, raw prompts, secondary PINs, or raw public tokens',
    'fewer responsibilities after the change'
)
foreach ($item in $requiredReviewItems) {
    if (-not $content.Contains($item)) {
        $findings.Add("Service decomposition review checklist missing item containing: $item") | Out-Null
    }
}

if ($findings.Count -gt 0) {
    Write-Host 'Service decomposition plan check failed.'
    $findings | Sort-Object -Unique | ForEach-Object { Write-Host " - $_" }
    exit 1
}

Write-Host 'Service decomposition plan check passed.'
