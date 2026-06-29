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

Assert-ContainsAll 'docs/data_portability.md' $contract @(
    '# Data Portability Contract',
    '## Implemented API',
    '## Export flow',
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
    'object storage paths',
    'presigned URLs',
    'raw latitude/longitude',
    'async job',
    'standard CSV/Excel'
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
    'excludedFields',
    'storagePath',
    'publicLink',
    'temporaryDownloadLink',
    'hasGpsMetadata',
    'createPasswordProtectedZip',
    'setEncryptFiles(true)'
)

Assert-ContainsAll 'DataPortabilityExportServiceTest' $serviceTest @(
    'exportUserDataArchiveBuildsEncryptedArchiveWithoutOperationalSecrets',
    'exportUserDataArchiveVerifiesSecondaryPinBeforeExportingLedgerData',
    'SECONDARY_PIN',
    'zipFile.isEncrypted()',
    'manifest/drive-items.json',
    'manifest/travel-media.json',
    'manifest/family-media.json'
)

Assert-ContainsAll 'PrivacyControllerIntegrationTest' $integrationTest @(
    'dataExportRequiresAuthenticationCsrfAndVerifiedSecondaryPin',
    'post("/api/privacy/data-export")',
    'with(csrf())',
    'application/zip'
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
    'data-portability-contract'
)

Assert-ContainsAll '.github/workflows/ci.yml' $ci @(
    'data-portability-contract:',
    './scripts/verify-data-portability-contract.ps1',
    '- data-portability-contract',
    '[data-portability-contract]="${{ needs[''data-portability-contract''].result }}"'
)

Write-Host 'data portability contract verified'