Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$manifestPath = 'frontend/public/manifest.webmanifest'
$serviceWorkerPath = 'frontend/public/sw.js'
$indexPath = 'frontend/index.html'
$registrationPath = 'frontend/src/registerServiceWorker.js'
$pwaDocPath = 'docs/pwa_mobile_capture.md'
$accessibilityChecklistPath = 'docs/accessibility_mobile_checklist.md'
$roadmapPath = 'docs/project_improvement_roadmap.md'
$ciPath = '.github/workflows/ci.yml'
$calendarWorkspacePath = 'frontend/src/components/CalendarWorkspace.vue'
$familyAlbumWorkspacePath = 'frontend/src/components/FamilyAlbumWorkspace.vue'
$travelMemoryPanelPath = 'frontend/src/components/TravelMemoryPanel.vue'
$driveProfileModalPath = 'frontend/src/components/CalenDriveProfileModal.vue'
$findings = [System.Collections.Generic.List[string]]::new()

function Add-Finding([string]$Message) {
    $findings.Add($Message) | Out-Null
}

function Read-TextIfExists([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) {
        Add-Finding "Missing PWA/mobile baseline file: $Path"
        return ''
    }
    return Get-Content -LiteralPath $Path -Raw
}

function Require-Snippets([string]$Name, [string]$Content, [string[]]$Snippets) {
    foreach ($snippet in $Snippets) {
        if (-not $Content.Contains($snippet)) {
            Add-Finding "$Name missing required snippet: $snippet"
        }
    }
}

foreach ($path in @(
    $manifestPath,
    $serviceWorkerPath,
    $indexPath,
    $registrationPath,
    $pwaDocPath,
    $accessibilityChecklistPath,
    $roadmapPath,
    $ciPath,
    $calendarWorkspacePath,
    $familyAlbumWorkspacePath,
    $travelMemoryPanelPath,
    $driveProfileModalPath
)) {
    if (-not (Test-Path -LiteralPath $path)) {
        Add-Finding "Missing PWA/mobile baseline file: $path"
    }
}

if (Test-Path -LiteralPath $manifestPath) {
    try {
        $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
        if ([string]::IsNullOrWhiteSpace($manifest.name)) { Add-Finding 'Manifest is missing name.' }
        if ([string]::IsNullOrWhiteSpace($manifest.short_name)) { Add-Finding 'Manifest is missing short_name.' }
        if ($manifest.start_url -ne '/') { Add-Finding 'Manifest start_url must stay /.' }
        if ($manifest.scope -ne '/') { Add-Finding 'Manifest scope must stay /.' }
        if ($manifest.display -ne 'standalone') { Add-Finding 'Manifest display must be standalone.' }
        if ([string]::IsNullOrWhiteSpace($manifest.theme_color)) { Add-Finding 'Manifest is missing theme_color.' }
        if ($null -eq $manifest.icons -or $manifest.icons.Count -lt 1) { Add-Finding 'Manifest must include at least one icon.' }
        if ($null -eq $manifest.shortcuts -or $manifest.shortcuts.Count -lt 1) { Add-Finding 'Manifest should keep mobile shortcuts for core capture flows.' }
    } catch {
        Add-Finding "Manifest is not valid JSON: $($_.Exception.Message)"
    }
}

$serviceWorker = Read-TextIfExists $serviceWorkerPath
Require-Snippets 'Service worker' $serviceWorker @(
    'const APP_SHELL',
    'caches.open(CACHE_NAME)',
    "request.method !== 'GET'",
    "url.origin !== self.location.origin",
    "url.pathname.startsWith('/api/')",
    "networkFirst(request, '/')",
    'cacheFirst(request)'
)

$index = Read-TextIfExists $indexPath
Require-Snippets 'Index metadata' $index @(
    '<link rel="manifest" href="/manifest.webmanifest"',
    '<meta name="theme-color"',
    '<meta name="apple-mobile-web-app-capable" content="yes"',
    '<meta name="viewport" content="width=device-width, initial-scale=1.0"'
)

$registration = Read-TextIfExists $registrationPath
Require-Snippets 'Service worker registration' $registration @(
    "'serviceWorker' in navigator",
    'import.meta.env.PROD',
    "navigator.serviceWorker.register('/sw.js')"
)

$accessibility = Read-TextIfExists $accessibilityChecklistPath
Require-Snippets 'Accessibility/mobile checklist' $accessibility @(
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

$pwaDoc = Read-TextIfExists $pwaDocPath
Require-Snippets 'PWA/mobile capture doc' $pwaDoc @(
    '# PWA and Mobile Capture Baseline',
    '## Installed app shell',
    '## Mobile camera upload hints',
    'Calendar/ledger image capture flows in `CalendarWorkspace.vue`',
    'Family album media uploads in `FamilyAlbumWorkspace.vue`',
    'Travel memory photo uploads in `TravelMemoryPanel.vue`',
    'CalenDrive profile image upload in `CalenDriveProfileModal.vue`',
    'backend MIME, extension, size, OCR, thumbnail, and privacy checks remain the authority',
    'Offline temporary upload queue',
    'E2E coverage'
)

$calendarWorkspace = Read-TextIfExists $calendarWorkspacePath
Require-Snippets 'CalendarWorkspace mobile capture' $calendarWorkspace @(
    'receiptFileInputRef',
    'handleReceiptFileChange',
    'accept="image/*" capture="environment"'
)

$familyAlbumWorkspace = Read-TextIfExists $familyAlbumWorkspacePath
Require-Snippets 'FamilyAlbumWorkspace mobile capture' $familyAlbumWorkspace @(
    'FAMILY_MEDIA_ACCEPT',
    'handlePickFiles',
    'capture="environment"'
)

$travelMemoryPanel = Read-TextIfExists $travelMemoryPanelPath
Require-Snippets 'TravelMemoryPanel mobile capture' $travelMemoryPanel @(
    'handlePhotoSelection',
    'extractPhotoMetadata',
    'accept="image/*" capture="environment" multiple type="file"'
)

$driveProfileModal = Read-TextIfExists $driveProfileModalPath
Require-Snippets 'CalenDriveProfileModal mobile capture' $driveProfileModal @(
    'handleProfileImageChange',
    'uploadDriveProfileImage',
    'accept="image/*" capture="environment"'
)

$roadmap = Read-TextIfExists $roadmapPath
Require-Snippets 'Project roadmap' $roadmap @(
    'PWA/mobile capture',
    'docs/pwa_mobile_capture.md',
    'scripts/verify-pwa-mobile-baseline.ps1',
    'pwa-mobile-baseline'
)

$ci = Read-TextIfExists $ciPath
Require-Snippets 'CI workflow' $ci @(
    'pwa-mobile-baseline:',
    './scripts/verify-pwa-mobile-baseline.ps1',
    '- pwa-mobile-baseline',
    '[pwa-mobile-baseline]="${{ needs[''pwa-mobile-baseline''].result }}"'
)

if ($findings.Count -gt 0) {
    Write-Host 'PWA/mobile baseline check failed.'
    $findings | Sort-Object -Unique | ForEach-Object { Write-Host " - $_" }
    exit 1
}

Write-Host 'PWA/mobile baseline check passed.'