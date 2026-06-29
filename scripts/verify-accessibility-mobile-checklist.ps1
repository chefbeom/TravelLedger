Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$checklistPath = 'docs/accessibility_mobile_checklist.md'
$ciPath = '.github/workflows/ci.yml'
$roadmapPath = 'docs/project_improvement_roadmap.md'
$securityChecklistPath = 'docs/security_baseline_checklist.md'
$pinPadPath = 'frontend/src/components/PinPadInput.vue'
$profileModalPath = 'frontend/src/components/CalenDriveProfileModal.vue'
$inviteAccessPanelPath = 'frontend/src/components/InviteAccessPanel.vue'
$findings = [System.Collections.Generic.List[string]]::new()

function Assert-FileExists {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        $findings.Add("Missing accessibility/mobile input: $Path") | Out-Null
    }
}

function Assert-Contains {
    param(
        [string]$Label,
        [string]$Content,
        [string]$Needle
    )
    if (-not $Content.Contains($Needle)) {
        $findings.Add("$Label missing required snippet: $Needle") | Out-Null
    }
}

foreach ($path in @($checklistPath, $ciPath, $roadmapPath, $securityChecklistPath, $pinPadPath, $profileModalPath, $inviteAccessPanelPath)) {
    Assert-FileExists -Path $path
}

if ($findings.Count -eq 0) {
    $checklist = Get-Content -LiteralPath $checklistPath -Raw
    $ci = Get-Content -LiteralPath $ciPath -Raw
    $roadmap = Get-Content -LiteralPath $roadmapPath -Raw
    $securityChecklist = Get-Content -LiteralPath $securityChecklistPath -Raw
    $pinPad = Get-Content -LiteralPath $pinPadPath -Raw
    $profileModal = Get-Content -LiteralPath $profileModalPath -Raw
    $inviteAccessPanel = Get-Content -LiteralPath $inviteAccessPanelPath -Raw

    $requiredSections = @(
        '# Accessibility and Mobile UX Checklist',
        '## Priority Screens',
        '## WCAG 2.2 Traceability',
        '## Accessibility Risk Register',
        '## WCAG 2.2 Checklist',
        '## Manual Test Recipe',
        '## Current Evidence',
        '## Release Evidence Template',
        '## Implementation Queue',
        '## CI Gate',
        '## Release Gate'
    )
    foreach ($section in $requiredSections) {
        Assert-Contains -Label 'Accessibility/mobile checklist' -Content $checklist -Needle $section
    }

    $requiredWcagRows = @(
        '2.1.1 Keyboard',
        '2.4.7 Focus Visible',
        '2.4.11 Focus Not Obscured',
        '2.5.7 Dragging Movements',
        '2.5.8 Target Size (Minimum)',
        '3.3.1 Error Identification',
        '3.3.3 Error Suggestion',
        '3.3.8 Accessible Authentication',
        '4.1.3 Status Messages'
    )
    foreach ($row in $requiredWcagRows) {
        Assert-Contains -Label 'WCAG traceability' -Content $checklist -Needle $row
    }

    $requiredPriorityAreas = @(
        'Login, PIN, session expiry',
        'Admin dialogs and destructive actions',
        'Dashboard drag widgets',
        'Drive/share/file upload',
        'Maps and travel media',
        'AI/OCR result review'
    )
    foreach ($area in $requiredPriorityAreas) {
        Assert-Contains -Label 'Accessibility risk register' -Content $checklist -Needle $area
    }

    $requiredEvidenceFields = @(
        'Commit SHA:',
        'Screen or flow:',
        'Priority: P0 | P1 | P2',
        'Desktop browser/version:',
        'Mobile viewport/device: 360x640 | other',
        'Keyboard-only result:',
        'Focus visible/not-obscured result:',
        'Drag alternative result:',
        'Touch target review:',
        'Error/status message review:',
        'Reduced-motion review:',
        'Screen-reader or DOM status evidence:',
        'Known gaps and follow-up issue:',
        'Release decision: pass | conditional | fail'
    )
    foreach ($field in $requiredEvidenceFields) {
        Assert-Contains -Label 'Accessibility evidence template' -Content $checklist -Needle $field
    }

    foreach ($snippet in @('role="group"', ':aria-label="label"', 'role="status"', 'aria-live="polite"', ':aria-label="progressLabel"', ':aria-label="`PIN digit ${digit}`"', 'aria-label="Clear PIN digits"', 'aria-label="Delete last PIN digit"', 'type="button"')) {
        Assert-Contains -Label 'PinPadInput.vue accessibility anchor' -Content $pinPad -Needle $snippet
    }

    foreach ($snippet in @('inputmode="numeric"', 'maxlength="8"', 'autocomplete="new-password"', 'pattern="[0-9]*"')) {
        Assert-Contains -Label 'CalenDriveProfileModal.vue mobile PIN anchor' -Content $profileModal -Needle $snippet
    }

    foreach ($snippet in @('<label class="field">', '<select', 'aria-label="Invite link expiry"', 'Generated link', 'Expires at:', 'Copy link', 'role="status"', 'aria-live="polite"', 'role="alert"')) {
        Assert-Contains -Label 'InviteAccessPanel.vue share accessibility anchor' -Content $inviteAccessPanel -Needle $snippet
    }

    foreach ($snippet in @('accessibility-mobile-checklist:', './scripts/verify-accessibility-mobile-checklist.ps1', '[accessibility-mobile-checklist]="${{ needs[''accessibility-mobile-checklist''].result }}"')) {
        Assert-Contains -Label 'CI workflow accessibility gate' -Content $ci -Needle $snippet
    }

    foreach ($snippet in @('Accessibility/mobile UX', 'docs/accessibility_mobile_checklist.md', 'scripts/verify-accessibility-mobile-checklist.ps1')) {
        Assert-Contains -Label 'Project roadmap accessibility coverage' -Content $roadmap -Needle $snippet
    }

    foreach ($snippet in @('UX-01', 'WCAG 2.2', 'scripts/verify-accessibility-mobile-checklist.ps1')) {
        Assert-Contains -Label 'Security baseline accessibility coverage' -Content $securityChecklist -Needle $snippet
    }
}

if ($findings.Count -gt 0) {
    Write-Host 'Accessibility/mobile checklist verification failed.'
    $findings | Sort-Object -Unique | ForEach-Object { Write-Host " - $_" }
    exit 1
}

Write-Host 'Accessibility/mobile checklist verification passed.'
