# CalenDrive Share Permissions

CalenDrive now stores an explicit permission level per direct file share. Existing shares keep the previous behavior through the `DOWNLOAD` default.

## Permission model

| Permission | List/preview | Download URL | Save to my drive | Intended use |
| --- | --- | --- | --- | --- |
| `VIEW` | Allowed | Blocked | Blocked | Recipient can inspect metadata and thumbnails without receiving a file copy. |
| `DOWNLOAD` | Allowed | Allowed | Allowed | Current default behavior for normal file sharing. |
| `EDIT` | Allowed | Allowed | Allowed | Reserved for future collaborative edit flows while preserving download capability. |

## API contract

```http
POST /api/file/share
Content-Type: application/json

{
  "fileIds": [10, 11],
  "recipientLoginId": "family-user",
  "permission": "VIEW"
}
```

If `permission` is omitted, the backend uses `DOWNLOAD` for backward compatibility.

The share list responses include `permission` so clients can hide download/save actions for view-only shares.

## Enforcement baseline

- Owners can create or update a recipient's share permission.
- Locked files still reject share creation, cancellation, and permission changes.
- Trashed source files remain unavailable even when a share row exists.
- `VIEW` shares cannot call shared download URL generation or save the shared file into the recipient drive.
- Direct shared download URL requests write an owner-scoped audit log with status such as `shared_success`, `shared_permission_denied`, `shared_unavailable`, or `shared_not_found`.
- Unknown permission values fail fast with `400 Bad Request`.

## Next hardening steps

- Add frontend affordances for selecting `VIEW`, `DOWNLOAD`, and `EDIT`.
- Surface direct share access logs in the CalenDrive frontend for file owners.
- Decide whether `EDIT` should allow rename/version-restore on the owner's original file or create a collaborative copy workflow.
## Owner audit endpoint

```http
GET /api/file/share/{fileId}/access-logs
```

Owners can inspect the latest direct shared-download events for a file. The response reuses the download-link access log shape and intentionally omits raw tokens or synthetic share fingerprints.