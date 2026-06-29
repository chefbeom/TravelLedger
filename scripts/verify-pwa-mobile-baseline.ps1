Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$manifestPath = 'frontend/public/manifest.webmanifest'
$serviceWorkerPath = 'frontend/public/sw.js'
$indexPath = 'frontend/index.html'
$registrationPath = 'frontend/src/registerServiceWorker.js'
$accessibilityChecklistPath = 'docs/accessibility_mobile_checklist.md'
 = 'docs/accessibility_mobile_checklist.md'
$findings = [System.Collections.Generic.List[string]]::new()

foreach ($path in @($manifestPath, $serviceWorkerPath, $indexPath, $registrationPath, $accessibilityChecklistPath)) {
    if (-not (Test-Path -LiteralPath $path)) {
        $findings.Add("Missing PWA/mobile/accessibility baseline file: $path") | Out-Null
    }
}

if (Test-Path -LiteralPath $manifestPath) {
    try {
        $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
        if ([string]::IsNullOrWhiteSpace($manifest.name)) { $findings.Add('Manifest is missing name.') | Out-Null }
        if ([string]::IsNullOrWhiteSpace($manifest.short_name)) { $findings.Add('Manifest is missing short_name.') | Out-Null }
        if ($manifest.start_url -ne '/') { $findings.Add('Manifest start_url must stay /.') | Out-Null }
        if ($manifest.scope -ne '/') { $findings.Add('Manifest scope must stay /.') | Out-Null }
        if ($manifest.display -ne 'standalone') { $findings.Add('Manifest display must be standalone.') | Out-Null }
        if ([string]::IsNullOrWhiteSpace($manifest.theme_color)) { $findings.Add('Manifest is missing theme_color.') | Out-Null }
        if ($null -eq $manifest.icons -or $manifest.icons.Count -lt 1) { $findings.Add('Manifest must include at least one icon.') | Out-Null }
        if ($null -eq $manifest.shortcuts -or $manifest.shortcuts.Count -lt 1) { $findings.Add('Manifest should keep mobile shortcuts for core capture flows.') | Out-Null }
    } catch {
        $findings.Add("Manifest is not valid JSON: $($_.Exception.Message)") | Out-Null
    }
}

if (Test-Path -LiteralPath $serviceWorkerPath) {
    $sw = Get-Content -LiteralPath $serviceWorkerPath -Raw
    $requiredSnippets = @(
        'const APP_SHELL',
        'caches.open(CACHE_NAME)',
        "request.method !== 'GET'",
        "url.origin !== self.location.origin",
        "url.pathname.startsWith('/api/')",
        'networkFirst(request, ''/'')',
        'cacheFirst(request)'
    )
    foreach ($snippet in $requiredSnippets) {
        if (-not $sw.Contains($snippet)) {
            $findings.Add("Service worker missing required cache/privacy snippet: $snippet") | Out-Null
        }
    }
}

if (Test-Path -LiteralPath $indexPath) {
    $index = Get-Content -LiteralPath $indexPath -Raw
    $requiredIndexSnippets = @(
        '<link rel="manifest" href="/manifest.webmanifest"',
        '<meta name="theme-color"',
        '<meta name="apple-mobile-web-app-capable" content="yes"',
        '<meta name="viewport" content="width=device-width, initial-scale=1.0"'
    )
    foreach ($snippet in $requiredIndexSnippets) {
        if (-not $index.Contains($snippet)) {
            $findings.Add("Index metadata missing required PWA/mobile snippet: $snippet") | Out-Null
        }
    }
}

if (Test-Path -LiteralPath $registrationPath) {
    $registration = Get-Content -LiteralPath $registrationPath -Raw
    if (-not $registration.Contains("'serviceWorker' in navigator")) {
        $findings.Add('Service worker registration must feature-detect navigator.serviceWorker.') | Out-Null
    }
    if (-not $registration.Contains('import.meta.env.PROD')) {
        $findings.Add('Service worker registration must stay production-only.') | Out-Null
    }
    if (-not $registration.Contains("navigator.serviceWorker.register('/sw.js')")) {
        $findings.Add('Service worker registration must register /sw.js.') | Out-Null
    }
}
if (Test-Path -LiteralPath $accessibilityChecklistPath) {
    $accessibility = Get-Content -LiteralPath $accessibilityChecklistPath -Raw
    $requiredAccessibilitySnippets = @(
        'WCAG 2.2 Recommendation',
        '## WCAG 2.2 Traceability',
        '2.4.11 Focus Not Obscured',
        '2.5.7 Dragging Movements',
        '2.5.8 Target Size (Minimum)',
        '3.3.8 Accessible Authentication',
        '4.1.3 Status Messages',
        '## Accessibility Risk Register',
        'Login, PIN, session expiry',
        'Admin dialogs and destructive actions',
        'Dashboard drag widgets',
        'Drive/share/file upload',
        'Maps and travel media',
        'AI/OCR result review',
        'Focus returns to the trigger',
        '360x640',
        '44x44 CSS px'
    )
    foreach ($snippet in $requiredAccessibilitySnippets) {
        if (-not $accessibility.Contains($snippet)) {
            $findings.Add("Accessibility/mobile checklist missing required WCAG 2.2 baseline snippet: $snippet") | Out-Null
        }
    }
}


if ($findings.Count -gt 0) {
    Write-Host 'PWA/mobile baseline check failed.'
    $findings | Sort-Object -Unique | ForEach-Object { Write-Host " - $_" }
    exit 1
}

Write-Host 'PWA/mobile baseline check passed.'

