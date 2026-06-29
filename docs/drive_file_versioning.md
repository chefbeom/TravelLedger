# CalenDrive File Versioning Baseline

This slice introduces the first backend foundation for CalenDrive file versions.

## What is implemented

- `drive_item_versions` stores owner-scoped file version metadata.
- `DriveService.completeUpload` records version `1` when a new uploaded file becomes a `DriveItem`.
- `GET /api/file/{fileId}/versions` returns the current user's versions for an owned file.
- `PATCH /api/file/{fileId}/versions/{versionId}/restore` restores a selected version after owner and lock checks, then records a new `RESTORE` version row.
- Version rows store display filename, extension, storage object key, content type, file size, source, and creation time.
- Version listing is owner-scoped through the existing `getOwnedFile` guard before querying version rows.
- Permanent deletion removes version rows before deleting drive items so the version ledger does not block cleanup.

## Current limits

- Restoring a previous version is not implemented yet.
- Uploading a replacement over an existing logical file is not implemented yet; current behavior still treats each new object key as a new file item.
- Version rows retain object keys for backend restore/download work, so user-facing export manifests still exclude storage paths.

## API contract

```http
GET /api/file/{fileId}/versions
```

Response item fields:

| Field | Meaning |
| --- | --- |
| `id` | Version row id. |
| `fileId` | Owning drive item id. |
| `versionNumber` | Per-file version number, newest sorted first by API. |
| `fileOriginName` | Display filename captured for that version. |
| `fileFormat` | File extension captured for that version. |
| `fileSize` | Object size in bytes. |
| `contentType` | Resolved content type used for download/restore work. |
| `source` | Version source, currently `UPLOAD`. |
| `createdAt` | Version row creation time. |

## Next steps

| Priority | Work |
| --- | --- |
| P0 | Add frontend version drawer in CalenDrive with restore affordance wired to the restore endpoint. |
| P1 | Add replacement upload flow that records version `N+1` instead of creating a separate file when the user chooses overwrite. |
| P1 | Add frontend version drawer in CalenDrive with download/restore affordances. |
| P2 | Add retention controls for max versions per file or per-user storage pressure. |