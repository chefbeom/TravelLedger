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

$contract = Read-RequiredFile 'docs/drive_file_versioning.md'
$securityChecklist = Read-RequiredFile 'docs/security_baseline_checklist.md'
$roadmap = Read-RequiredFile 'docs/project_improvement_roadmap.md'
$ci = Read-RequiredFile '.github/workflows/ci.yml'
$api = Read-RequiredFile 'frontend/src/lib/api.js'
$workspace = Read-RequiredFile 'frontend/src/components/CalenDriveWorkspace.vue'
$style = Read-RequiredFile 'frontend/src/style.css'
$controller = Read-RequiredFile 'backend/src/main/java/com/playdata/calen/drive/web/DriveFileController.java'
$service = Read-RequiredFile 'backend/src/main/java/com/playdata/calen/drive/service/DriveService.java'
$repository = Read-RequiredFile 'backend/src/main/java/com/playdata/calen/drive/repository/DriveItemVersionRepository.java'
$serviceTest = Read-RequiredFile 'backend/src/test/java/com/playdata/calen/drive/service/DriveServiceTest.java'
$migration = Read-RequiredFile 'backend/src/main/resources/db/migration/V20260629_005__drive_item_versions.sql'

Assert-ContainsAll 'docs/drive_file_versioning.md' $contract @(
    '# CalenDrive File Versioning Contract',
    '## Implemented surfaces',
    '## API contract',
    '## Frontend contract',
    '## Non-negotiable rules',
    '## Evidence anchors',
    'GET /api/file/{fileId}/versions',
    'PATCH /api/file/{fileId}/versions/{versionId}/restore',
    'drive-version-launcher',
    'drive-version-drawer',
    'restoreDriveFileVersion',
    'RESTORE',
    'must not show storage object keys'
)

Assert-ContainsAll 'frontend/src/lib/api.js' $api @(
    'export function fetchDriveFileVersions(fileId)',
    'return request(`/file/${fileId}/versions`)',
    'export function restoreDriveFileVersion(fileId, versionId)',
    'method: ''PATCH'''
)

Assert-ContainsAll 'CalenDriveWorkspace.vue' $workspace @(
    'fetchDriveFileVersions',
    'restoreDriveFileVersion',
    'versionDrawer',
    'selectedVersionableItem',
    'canOpenVersionDrawer',
    'openDriveVersionDrawer',
    'loadDriveVersions',
    'restoreDriveVersion',
    'data-testid="drive-version-launcher"',
    'data-testid="drive-version-open"',
    'data-testid="drive-version-drawer"',
    'data-testid="drive-version-list"',
    'drive-version-success',
    'drive-version-error',
    'A RESTORE version entry was recorded'
)

Assert-ContainsAll 'frontend/src/style.css' $style @(
    '.drive-version-launcher',
    '.drive-version-overlay',
    '.drive-version-drawer',
    '.drive-version-card'
)

Assert-ContainsAll 'DriveFileController' $controller @(
    '@GetMapping("/{fileId}/versions")',
    'listFileVersions',
    '@PatchMapping("/{fileId}/versions/{versionId}/restore")',
    'restoreFileVersion',
    'driveService.restoreFileVersion(currentUser.userId(), fileId, versionId)'
)

Assert-ContainsAll 'DriveService' $service @(
    'public List<DriveDtos.FileVersionResponse> listFileVersions',
    'getOwnedFile(userId, fileId)',
    'findAllByItem_IdAndOwner_IdOrderByVersionNumberDescIdDesc',
    'public DriveDtos.FileItemResponse restoreFileVersion',
    'ensureUnlocked(item)',
    'findByIdAndItem_IdAndOwner_Id',
    'recordFileVersion(item, "RESTORE")',
    'recordFileVersion(savedItem, "UPLOAD")',
    'deleteFileVersions'
)

Assert-ContainsAll 'DriveItemVersionRepository' $repository @(
    'countByItem_IdAndOwner_Id',
    'findAllByItem_IdAndOwner_IdOrderByVersionNumberDescIdDesc',
    'findByIdAndItem_IdAndOwner_Id',
    'deleteAllByItem_IdIn'
)

Assert-ContainsAll 'DriveServiceTest' $serviceTest @(
    'completeUploadRecordsInitialFileVersionMetadata',
    'restoreFileVersionSwapsActiveMetadataAndRecordsRestoreVersion',
    'assertThat(version.getSource()).isEqualTo("UPLOAD")',
    'assertThat(restoreVersion.getSource()).isEqualTo("RESTORE")'
)

Assert-ContainsAll 'drive_item_versions migration' $migration @(
    'drive_item_versions',
    'owner_id',
    'version_number',
    'storage_path',
    'source'
)

Assert-ContainsAll 'docs/security_baseline_checklist.md' $securityChecklist @(
    'DRIVE-01',
    'docs/drive_file_versioning.md',
    'drive-file-versioning-contract',
    'scripts/verify-drive-file-versioning-contract.ps1'
)

Assert-ContainsAll 'docs/project_improvement_roadmap.md' $roadmap @(
    'Drive file versioning',
    'docs/drive_file_versioning.md',
    'scripts/verify-drive-file-versioning-contract.ps1',
    'drive-file-versioning-contract'
)

Assert-ContainsAll '.github/workflows/ci.yml' $ci @(
    'drive-file-versioning-contract:',
    './scripts/verify-drive-file-versioning-contract.ps1',
    '- drive-file-versioning-contract',
    '[drive-file-versioning-contract]="${{ needs[''drive-file-versioning-contract''].result }}"'
)

Write-Host 'drive file versioning contract verified'