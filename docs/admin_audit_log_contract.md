# Admin Audit Log Contract

Updated: 2026-06-30

This contract makes high-risk administrator operations reviewable and testable. It complements `docs/security_baseline_checklist.md` and uses the existing `LoginAuditLog` / `LoginAuditLogService.recordAdminAction` path with `LoginAuditStatus.ADMIN_ACTION`.

## Scope

| Surface | Risk | Audit requirement |
| --- | --- | --- |
| `/api/admin/data-management/**` | Backup creation, downloadable backup generation, database restore, and uploaded restore can expose or overwrite production data. | Every successful mutation/download action records an `ADMIN_ACTION` event with actor, client IP, user agent, bounded action code, and safe detail. |
| `/api/admin/users/{userId}/active` | Admin can disable or re-enable accounts and invalidate another user's access path. | Every successful activation change records target user ID and active state. |
| `/api/admin/blocked-ips` | Admin can clear login throttling state. | Every successful clear records a bounded IP detail, never request headers or tokens. |
| `/api/administrator/users/{userId}/status` | Drive admin can disable or re-enable CalenDrive users. | Every successful status change records target user ID and active state. |
| `/api/administrator/storage-capacity` | Drive admin can alter global storage capacity. | Every successful capacity update records the numeric capacity only. |

## Required audited actions

| Action code | Source | Safe detail shape |
| --- | --- | --- |
| `DATA_BACKUP_CREATE` | `AdminController` | Backup base file name only, with directory segments stripped. |
| `MINIO_BACKUP_CREATE` | `AdminController` | MinIO backup base file name only, with directory segments stripped. |
| `DATA_BACKUP_DOWNLOAD` | `AdminController` | Prepared backup base file name only, with directory segments stripped. |
| `DATA_RESTORE` | `AdminController` | Requested backup base file name only, with directory segments stripped. |
| `DATA_RESTORE_UPLOAD` | `AdminController` | Uploaded backup base file name only, with directory segments stripped. |
| `USER_ACTIVE_UPDATE` | `AdminController` | `userId=<id>,active=<boolean>`. |
| `BLOCKED_IP_CLEAR` | `AdminController` | Bounded IP string only. |
| `DRIVE_USER_STATUS_UPDATE` | `DriveAdminController` | `userId=<id>,active=<boolean>`. |
| `DRIVE_STORAGE_CAPACITY_UPDATE` | `DriveAdminController` | `providerCapacityBytes=<number>`. |

## Safe detail policy

| Rule | Reason |
| --- | --- |
| Detail must be bounded to 255 characters through `LoginAuditLogService`. | Prevents oversized request data from becoming audit payload. |
| Detail may include action code, target ID, boolean status, capacity number, or backup base file name after stripping `/` and `\\` directory segments. | Keeps forensic value without storing operational secrets. |
| Detail must not include password, secondary PIN, API key, secret, token, signed URL, presigned URL, `rclone.conf`, DB password, OAuth credential, raw backup contents, raw OCR image data, raw AI prompt, provider response body, or full filesystem path. | Audit logs are broadly visible to admins and can be exported during incidents. |
| Failed authorization attempts are covered by auth/admin security tests; successful high-risk admin mutations must also produce `ADMIN_ACTION`. | Access denial alone does not prove who performed a destructive action. |
| New admin-like mutation routes must either reuse this audit pattern or document why the operation is lower risk. | Prevents quiet expansion of unaudited admin power. |

## Current evidence

| Evidence | Coverage |
| --- | --- |
| `LoginAuditLogService.recordAdminAction` | Stores actor login ID, client IP, user agent, `ADMIN_ACTION`, safe detail, and linked admin user when available. |
| `AdminController` | Records backup create, MinIO backup create, backup download, restore, uploaded restore, user activation, and blocked-IP clear actions; backup audit details use `safeBackupFileName(...)` to avoid storing full paths. |
| `DriveAdminController` | Records drive user status and provider storage-capacity mutations. |
| `LoginAuditLogServiceTest` | Proves `recordAdminAction` stores `ADMIN_ACTION`, actor, IP, user agent, detail, success flag, and admin user link. |
| `AdminControllerAuditDetailTest` | Proves restore and uploaded-restore audit details strip Windows and Unix directory segments before calling `recordAdminAction`. |
| `DriveAdminSecurityIntegrationTest` | Proves drive storage capacity mutation requires verified admin plus CSRF and records safe audit detail without `password`, `token`, or `key`. |
| `docs/security_baseline_checklist.md` | Tracks `AUDIT-01` as the security baseline item for high-risk admin actions. |

## Test and CI gate

`scripts/verify-admin-audit-contract.ps1` checks that this contract exists, every required action code is documented, the current controllers still contain those action codes and call `recordAdminAction`, the security baseline references this contract, existing tests cover the audit service and drive-admin safe detail assertion, and the GitHub Actions `admin-audit-contract` job is part of the release gate.

## Release gate

A release that adds or changes admin, backup, restore, storage-management, user-management, or throttling-reset behavior should include:

- A new or updated `ADMIN_ACTION` detail code for every successful high-risk admin action.
- A safe detail review proving no password, secondary PIN, API key, token, signed URL, presigned URL, backup credential, raw prompt, provider response body, raw OCR image data, or full filesystem path is stored.
- A focused test or verifier update proving the route cannot silently bypass `recordAdminAction`.
- A security baseline update if the action changes authorization, secondary verification, CSRF, backup, restore, or drive-admin behavior.
