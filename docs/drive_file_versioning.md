# CalenDrive File Versioning Contract

Updated: 2026-06-30

This contract records the CalenDrive file versioning baseline. The goal is to make family-drive files safer by preserving upload/restore metadata, keeping version operations owner-scoped, and exposing a clear frontend restore affordance without leaking object-storage internals.

## Implemented surfaces

| Surface | Status | Safety boundary |
| --- | --- | --- |
| Version storage | `drive_item_versions` stores owner-scoped file version metadata. | Version rows retain object keys for backend restore/download work only. User-facing export manifests still exclude storage paths. |
| Initial upload version | `DriveService.completeUpload` records version `1` with source `UPLOAD` when a new uploaded file becomes a `DriveItem`. | Upload object keys must stay inside the current user's drive scope before storage lookup. |
| Version list API | `GET /api/file/{fileId}/versions` returns newest-first versions for a file owned by the current user. | Listing goes through `getOwnedFile` and `findAllByItem_IdAndOwner_IdOrderByVersionNumberDescIdDesc`. |
| Restore API | `PATCH /api/file/{fileId}/versions/{versionId}/restore` restores a selected version after owner and lock checks, then records a new source `RESTORE` row. | Restore uses `findByIdAndItem_IdAndOwner_Id`; locked files fail before metadata changes. |
| Frontend drawer | `CalenDriveWorkspace.vue` exposes a selected-file version launcher, version drawer, list, restore buttons, and live success/error messages. | Drawer is shown only for owned file contexts that can use the owner download path, not trash/shared/folder contexts. |
| Frontend API wrapper | `frontend/src/lib/api.js` exports `fetchDriveFileVersions` and `restoreDriveFileVersion`. | Restore uses `PATCH`, so existing CSRF handling applies. |

## API contract

```http
GET /api/file/{fileId}/versions
PATCH /api/file/{fileId}/versions/{versionId}/restore
```

Version response fields:

| Field | Meaning |
| --- | --- |
| `id` | Version row id. |
| `fileId` | Owning drive item id. |
| `versionNumber` | Per-file version number, newest sorted first by API. |
| `fileOriginName` | Display filename captured for that version. |
| `fileFormat` | File extension captured for that version. |
| `fileSize` | Object size in bytes. |
| `contentType` | Resolved content type used for download/restore work. |
| `source` | Version source, currently `UPLOAD` or `RESTORE`. |
| `createdAt` | Version row creation time. |

## Frontend contract

| Anchor | Required behavior |
| --- | --- |
| `drive-version-launcher` | Appears when exactly one owned file is selected in the Drive or Recent context. |
| `drive-version-open` | Opens the drawer and calls `fetchDriveFileVersions`. |
| `drive-version-drawer` | Explains that backend ownership checks remain authoritative and restore records a new `RESTORE` entry. |
| `drive-version-list` | Displays version number, filename, size, source, content type, and created time. |
| `drive-version-restore-{id}` | Calls `restoreDriveFileVersion(fileId, versionId)` after explicit confirmation. |
| `drive-version-success` / `drive-version-error` | Uses live status regions so restore outcomes are visible and testable. |

## Non-negotiable rules

- Users must not list or restore another user's file versions.
- Folders, shared-received files, trash items, and locked files must not expose unsafe restore affordances.
- Restoring a version must record a new `RESTORE` row instead of mutating history in place.
- Version UI must not show storage object keys, presigned URLs, public tokens, access-log internals, or backend storage paths.
- Data portability manifests must continue to exclude version object keys until binary archive export has async size limits, encryption, expiration, and restore rehearsal evidence.
- Permanent drive deletion must remove version rows before deleting drive items so cleanup remains deterministic.

## Evidence anchors

| Area | Evidence |
| --- | --- |
| Migration | `backend/src/main/resources/db/migration/V20260629_005__drive_item_versions.sql`. |
| Controller | `DriveFileController.listFileVersions` and `DriveFileController.restoreFileVersion`. |
| Service | `DriveService.listFileVersions`, `DriveService.restoreFileVersion`, and `recordFileVersion`. |
| Repository | `DriveItemVersionRepository.findAllByItem_IdAndOwner_IdOrderByVersionNumberDescIdDesc` and `findByIdAndItem_IdAndOwner_Id`. |
| Backend tests | `DriveServiceTest.completeUploadRecordsInitialFileVersionMetadata` and `DriveServiceTest.restoreFileVersionSwapsActiveMetadataAndRecordsRestoreVersion`. |
| Frontend | `CalenDriveWorkspace.vue`, `fetchDriveFileVersions`, `restoreDriveFileVersion`, and the `drive-version-*` test anchors. |
| Contract gate | `scripts/verify-drive-file-versioning-contract.ps1` and the `drive-file-versioning-contract` CI job. |

## Current limits

| Priority | Work |
| --- | --- |
| P0 | Add browser E2E coverage for selecting a file, opening the version drawer, and exercising mocked restore success/failure paths. |
| P1 | Add replacement upload flow that records version `N+1` instead of creating a separate file when the user chooses overwrite. |
| P1 | Add per-version download affordance if product policy allows old-version downloads. |
| P2 | Add retention controls for max versions per file or per-user storage pressure. |