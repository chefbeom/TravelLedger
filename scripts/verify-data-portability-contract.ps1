$ErrorActionPreference = 'Stop'

function Read-RequiredFile([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Required file missing: $Path"
    }
    return Get-Content -Raw -LiteralPath $Path
}

function Assert-ContainsAll([string]$Name, [string]$Content, [string[]]$Snippets) {
    foreach ($snippet in $Snippets) {
        if (-not $Content.Contains($snippet)) {
            throw "$Name is missing required snippet: $snippet"
        }
    }
}

$contract = Read-RequiredFile 'docs/data_portability.md'
$securityChecklist = Read-RequiredFile 'docs/security_baseline_checklist.md'
$roadmap = Read-RequiredFile 'docs/project_improvement_roadmap.md'
$ci = Read-RequiredFile '.github/workflows/ci.yml'
$controller = Read-RequiredFile 'backend/src/main/java/com/playdata/calen/account/web/PrivacyController.java'
$service = Read-RequiredFile 'backend/src/main/java/com/playdata/calen/account/service/DataPortabilityExportService.java'
$serviceTest = Read-RequiredFile 'backend/src/test/java/com/playdata/calen/account/service/DataPortabilityExportServiceTest.java'
$integrationTest = Read-RequiredFile 'backend/src/test/java/com/playdata/calen/account/PrivacyControllerIntegrationTest.java'
$profileWorkspace = Read-RequiredFile 'frontend/src/components/ProfileWorkspace.vue'

Assert-ContainsAll 'docs/data_portability.md' $contract @(
    '# Data Portability Contract',
    '## Implemented API',
    '## Export flow',
    '## Portability product tiers',
    '## Full archive release requirements',
    '## CSV/Excel standardization contract',
    '## Archive contents',
    '## Non-negotiable safety rules',
    '## Current implementation anchors',
    '## Release gate',
    '## CI contract',
    'secondary PIN',
    'password-protected',
    'ledger CSV',
    'manifest/drive-items.json',
    'manifest/travel-media.json',
    'manifest/family-media.json',
    'PRIVACY_EXPORT_DONE',
    'Data export ready',
    'dateRangeLabel',
    'ledger_csv_and_safe_manifests',
    'object storage paths',
    'presigned URLs',
    'raw latitude/longitude',
    'async job',
    'standard CSV/Excel',
    'Frontend privacy action',
    'ProfileWorkspace.vue',
    'privacy-data-export-card',
    'privacy-export-secondary-pin',
    'manifest-only archive explanation',
    'PRIVACY_EXPORT_DONE',
    'Export notification',
    'personal data platform',
    'full data export',
    'Photo/file archive',
    'Standard ledger export',
    'Standard ledger import',
    'queued job with progress API',
    'per-file, total-byte, item-count',
    'preview-first validation',
    'duplicate detection',
    'explicit confirm-save'
)

Assert-ContainsAll 'PrivacyController' $controller @(
    '@PostMapping("/data-export")',
    '@AuthenticationPrincipal AppUserPrincipal currentUser',
    'secondaryPinSessionSupport.getVerifiedSecondaryPin',
    'exportUserDataArchive(',
    'HttpHeaders.CONTENT_DISPOSITION',
    'MediaType.parseMediaType(archive.contentType())'
)

Assert-ContainsAll 'DataPortabilityExportService' $service @(
    'public UserDataArchive exportUserDataArchive',
    'ensureSecondaryPinMatches',
    'ledgerEntryService.exportEntriesCsv',
    'findAllByOwner_IdOrderByLastModifiedAtDesc',
    'findAllByPlanOwnerIdOrderByUploadedAtDescIdDesc',
    'findAllByOwnerIdOrderByUploadedAtDescIdDesc',
    'metadata/export-metadata.json',
    'manifest/drive-items.json',
    'manifest/travel-media.json',
    'manifest/family-media.json',
    'PRIVACY_EXPORT_DONE',
    'Data export ready',
    'dateRangeLabel',
    'ledger_csv_and_safe_manifests',
    'excludedFields',
    'storagePath',
    'publicLink',
    'temporaryDownloadLink',
    'hasGpsMetadata',
    'createPasswordProtectedZip',
    'setEncryptFiles(true)',
    'private final UserNotificationService userNotificationService',
    'notifyPrivacyExportCompleted(userId, from, to)',
    'userNotificationService.createSystemNotification(',
    '"PRIVACY_EXPORT_DONE"',
    'privacyExportMetadata(from, to)',
    'dateRangeLabel(from, to)',
    'ledger_csv_and_safe_manifests'
)

Assert-ContainsAll 'DataPortabilityExportServiceTest' $serviceTest @(
    'exportUserDataArchiveBuildsEncryptedArchiveWithoutOperationalSecrets',
    'exportUserDataArchiveVerifiesSecondaryPinBeforeExportingLedgerData',
    'SECONDARY_PIN',
    'zipFile.isEncrypted()',
    'manifest/drive-items.json',
    'manifest/travel-media.json',
    'manifest/family-media.json',
    'PRIVACY_EXPORT_DONE',
    'Data export ready',
    'dateRangeLabel',
    'ledger_csv_and_safe_manifests'
)

Assert-ContainsAll 'PrivacyControllerIntegrationTest' $integrationTest @(
    'dataExportRequiresAuthenticationCsrfAndVerifiedSecondaryPin',
    'post("/api/privacy/data-export")',
    'with(csrf())',
    'application/zip'
)

Assert-ContainsAll 'ProfileWorkspace.vue' $profileWorkspace @(
    'profile-privacy-panel',
    'downloadPrivacyDataExport',
    'verifyProfileSecondaryPin(privacy.secondaryPin)',
    'data-testid="privacy-data-export-card"',
    'data-testid="privacy-export-from"',
    'data-testid="privacy-export-to"',
    'data-testid="privacy-export-open"',
    'data-testid="privacy-export-dialog"',
    'data-testid="privacy-export-secondary-pin"',
    'privacy-action-result',
    'Current archive includes ledger CSV and safe manifests only',
    'binary photos/files require a future async export job',
    'aria-live="assertive"'
)

Assert-ContainsAll 'docs/security_baseline_checklist.md' $securityChecklist @(
    'PORTABILITY-01',
    'docs/data_portability.md',
    'data-portability-contract',
    'scripts/verify-data-portability-contract.ps1'
)

Assert-ContainsAll 'docs/project_improvement_roadmap.md' $roadmap @(
    'Data portability',
    'docs/data_portability.md',
    'scripts/verify-data-portability-contract.ps1',
    'ProfileWorkspace.vue',
    'data-portability-contract'
)

Assert-ContainsAll '.github/workflows/ci.yml' $ci @(
    'data-portability-contract:',
    './scripts/verify-data-portability-contract.ps1',
    '- data-portability-contract',
    '[data-portability-contract]="${{ needs[''data-portability-contract''].result }}"'
)

Write-Host 'data portability contract verified'

