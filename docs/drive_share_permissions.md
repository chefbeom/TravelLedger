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
- Unknown permission values fail fast with `400 Bad Request`.

## Next hardening steps

- Add frontend affordances for selecting `VIEW`, `DOWNLOAD`, and `EDIT`.
- Add owner-visible audit rows for direct shared download attempts, not only public download links.
- Decide whether `EDIT` should allow rename/version-restore on the owner's original file or create a collaborative copy workflow.