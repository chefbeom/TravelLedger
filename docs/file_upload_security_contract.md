# File Upload Security Contract

Updated: 2026-06-30

This contract records the shared upload security baseline for TravelLedger. It complements `docs/media_processing_queue_contract.md`: that document owns expensive media processing and queue separation, while this document owns input validation before files reach remote OCR, object storage, durable media records, or user-visible thumbnails.

## Scope

| Upload family | Entry point | Current protection anchor |
| --- | --- | --- |
| OCR receipt upload | `LedgerOcrService.analyze` | Rejects empty, oversized, MIME/extension mismatch, and fake image-signature uploads before calling the remote OCR client. |
| CalenDrive upload | `DriveStorageService.initUpload` | Normalizes content type, validates known extension/content-type mismatches before presigned URL generation, and builds owner-prefixed object keys. |
| Travel media upload | `TravelMediaStorageService.store`, `preparePresignedUploads`, `completePresignedUpload` | Validates upload candidates, binary signatures for direct upload, prepared-thumbnail candidates, and owner/plan/record-scoped object keys before MinIO stat. |
| Travel GPX upload | `TravelMediaStorageService.storeRouteGpx` | Accepts only `.gpx`/XML route files and keeps route files out of image thumbnail generation. |
| Family album upload | `FamilyMediaStorageService.store` | Requires file name, MIME/extension match, binary signature validation, owner/category object key scoping, and fail-closed thumbnail preparation. |
| Support inquiry attachment | `SupportInquiryStorageService.store` | Limits attachments to 5MB, allows only image extensions/content types, verifies binary signatures, and stores under a normalized user directory. |
| Thumbnail serving | `TravelMediaHttpResponses`, `TravelController`, `FamilyAlbumController`, `ImageThumbnailService` | Fail-closed is the target policy. Family/login-preview paths enforce it; the generic travel renderer still has the legacy original fallback and public max-age on thumbnail success. See the known gap below. |

## Validation pipeline

```mermaid
flowchart TD
    A["Upload request"] --> B["Auth, CSRF, and owner scope at controller/service"]
    B --> C["Size and empty-file check"]
    C --> D["Filename and extension normalization"]
    D --> E["MIME/content-type allowlist"]
    E --> F["Binary signature check when bytes are available"]
    F --> G["Owner-scoped object key or storage path"]
    G --> H["Storage, presign, OCR, or thumbnail processing"]
    H --> I["Record safe metadata only"]
```

## Required invariants

| Invariant | Required behavior |
| --- | --- |
| Validate before external calls | OCR upload validation must finish before `LedgerImageExtractionPort.analyze`; invalid uploads must not call remote OCR. |
| Validate before presign | Drive and travel upload preparation must reject known extension/content-type mismatches before generating presigned upload URLs. |
| Validate before object stat | Travel presigned completion must validate owner/plan/record object-key scope and reject ambiguous path segments before MinIO `statObject`. |
| Validate direct upload bytes | OCR, support attachments, family album, and direct travel uploads must inspect binary signatures for supported image types when backend receives bytes. |
| Size limits stay explicit | OCR and support already have explicit limits; drive, travel, and family media must define per-feature limits before large-upload release. |
| Storage path confinement | Local storage paths must normalize under feature roots; object keys must include owner or owner-derived scope and avoid raw path traversal segments. |
| Image processing fails closed | Thumbnail decode/persist failures must not create a trusted thumbnail record, leak stack traces, or return original private bytes as a thumbnail fallback. |
| Metadata is safe | Queue payloads, logs, manifests, and audit output must not include raw presigned URLs, public tokens, API keys, secondary PINs, full filesystem paths, raw EXIF/GPS payloads, or OCR provider credentials. |
| User action remains explicit | Upload/OCR preview output can suggest ledger data, but must not mutate ledger entries until the user confirms the resulting transaction. |

## Implementation anchors

| Area | Evidence to preserve |
| --- | --- |
| OCR invalid upload guard | `LedgerOcrService.validateFile`, `normalizeContentType`, `resolveImageExtension`, `isAllowedImageContentType`, `hasAllowedImageSignature`, and `readImageHeader`. |
| OCR no remote call tests | `LedgerOcrServiceTest.analyzeRejectsEmptyFileBeforeRemoteCall`, `analyzeRejectsOversizedFileBeforeRemoteCall`, `analyzeRejectsImageExtensionWithNonImageMimeBeforeRemoteCall`, `analyzeRejectsImageMimeWithNonImageExtensionBeforeRemoteCall`, `analyzeRejectsMismatchedImageMimeAndExtensionBeforeRemoteCall`, `analyzeRejectsFakeImageBytesBeforeRemoteCall`, and `analyzeRecordsInvalidFileMetricWhenUploadValidationFails`. |
| Drive presign guard | `DriveStorageService.prepareUploadRequest`, `normalizeContentType`, `validateContentTypeMatchesExtension`, `EXTENSION_CONTENT_TYPES`, and `GENERIC_CONTENT_TYPES`. |
| Drive tests | `DriveStorageServiceTest.initUploadRejectsKnownExtensionContentTypeMismatchBeforeStorageAccess`, `initUploadRejectsNullRequestBeforeStorageAccess`, and `initUploadAllowsGenericOctetStreamForKnownExtension`. |
| Travel presign completion guard | `TravelMediaStorageService.validateUploadCandidates`, `validateUploadCandidate`, `validateObjectKey`, `validatePreparedThumbnailCandidates`, `validateCompletedPreparedThumbnailCandidates`, and `verifyUploadedObject`. |
| Travel tests | `TravelMediaStorageServiceTest.completePresignedUploadRejectsObjectKeyOutsideOwnerRecordScopeBeforeMinioStat` and `completePresignedUploadRejectsAmbiguousObjectKeyBeforeMinioStat`. |
| Family upload guard | `FamilyMediaStorageService.validateFile`, `detectUpload`, `validateBinarySignature`, `matchesSignature`, `buildMinioObjectKey`, and `prepareDerivedThumbnailsQuietly`. |
| Support attachment guard | `SupportInquiryStorageService.MAX_ATTACHMENT_SIZE_BYTES`, `ALLOWED_CONTENT_TYPES_BY_EXTENSION`, `resolveAllowedContentType`, `validateBinarySignature`, and `matchesSignature`. |
| Fail-closed thumbnails | `FamilyAlbumControllerTest.shouldNotFallbackToOriginalImageWhenThumbnailIsUnavailable`, `TravelControllerTest`, and `ImageThumbnailServiceTest.shouldCreatePreparedThumbnailsForConfiguredWidths`. |

## Release gate

Owned travel media refactoring (2026-10-16): `ManageOwnedTravelMediaUseCase` separates prepare/complete/delete/download from persistence, object storage, owner/id metadata cache and HTTP rendering. The existing storage validation body and owner/plan/record key checks are unchanged. Integration tests cover all six existing HTTP paths, auth/CSRF, owner/record-kind rejection, batch/link ordering, verified storage size, memory PHOTO coercion, caption/GPS rules, database rollback and immediate/after-commit cache/cluster timing. Pure-core dependency checks reject JPA/HTTP/SDK coupling. No operational object store or database is used by these tests.

Known pre-existing gap, not fixed by this structural refactor: the generic travel thumbnail renderer falls back to the original when thumbnail loading fails and retains `public, max-age=86400` on success. Login-preview rendering instead returns 404/no-store and never original bytes; record-share rendering overrides cache to private/no-store. `TravelMediaHttpResponsesTest` freezes these differences rather than claiming all travel paths are fail-closed. The release-gate target below is not evidence that this gap is resolved. Owner/id metadata cache parent-delete and in-flight republish behavior, and atomic DB/object-store cleanup, also remain follow-ups.

The GPX upload lifecycle now uses `ManageTravelRouteFilesUseCase` and `TravelRouteUploadPort`. The legacy storage validator still checks route-scoped object keys before stat, and metadata before reading MultipartFile bytes. Port bridges keep getBytes lazy and preserve IOException translation. Runtime tests cover foreign owner/plan/route keys, ambiguous path segments and sequential abort behavior. The core does not read bytes or perform network/file I/O; its Upload declaration alone may name IOException.

Refactoring boundary (2026-10-16): OCR validation still runs in `LedgerOcrService` before `LedgerImageExtractionPort` is called through the multipart bridge. Provider HTTP is implemented by `ImageLedgerExtractionHttpAdapter` with NOT_SUPPORTED propagation; the rejected-upload tests still verify no extraction-port interaction. Route GPX parsing is now in `TravelRoutePayloadAdapter`, preserving secure processing, disabled DOCTYPE/external entities/XInclude and snapshot fallback. The static gate checks these new anchors and requires their runtime tests to remain selected in CI. This is not malware scanning or proof of production storage safety.

The `file-upload-security-contract` CI job must pass before promoting changes that affect OCR upload, Drive upload, travel media upload, family album upload, support inquiry attachments, presigned object keys, upload status UI, thumbnail serving, or large-upload queue work.

A release is not ready if any of these are true:

| Failure | Why it blocks release |
| --- | --- |
| Invalid OCR image reaches the remote OCR client. | Can leak user data to providers and waste external capacity. |
| Presigned upload URL is issued before extension/content-type validation. | Allows storage abuse and confusing file metadata. |
| Presigned completion stats an object key before owner/record scope validation. | Lets one user probe or complete another user's object. |
| Image signature spoofing is accepted by support, family, travel, or OCR flows. | Allows malicious or malformed bytes to be treated as trusted images. |
| Thumbnail failure falls back to original private bytes. | Breaks least-privilege preview behavior. |
| Upload logs or queue payloads store raw presigned URLs, public tokens, API keys, full filesystem paths, raw EXIF/GPS, or provider credentials. | Expands blast radius of operational logs and background queues. |

## CI contract

`scripts/verify-file-upload-security-contract.ps1` keeps this document synchronized with upload services, upload/security tests, `docs/security_baseline_checklist.md`, `docs/project_improvement_roadmap.md`, and the GitHub Actions `file-upload-security-contract` release-gate job.

## Next slices

| Slice | Notes |
| --- | --- |
| Per-feature max-size matrix | Define explicit max size for Drive, travel media, family album, GPX, support, OCR, and future video uploads. |
| Shared upload policy helper | Centralize extension/content-type/signature rules without weakening feature-specific allowances. |
| Travel/family malformed image tests | Add direct malformed-image upload tests that prove no trusted media row or thumbnail is created. |
| Upload status records | Persist upload intent, completion, failure reason, cleanup, and retry status before large-upload queue release. |
| Malware scanning hook | Add an optional async scan status before public sharing or data export includes uploaded binary files. |

## Test backlog

- Drive rejects known extension/content-type mismatches before MinIO presign.
- Drive completion cannot finalize object keys outside the current owner scope.
- Travel presigned completion rejects wrong-owner, wrong-plan, wrong-record, path traversal, and ambiguous object keys before MinIO stat.
- Travel direct upload rejects malformed image bytes before creating trusted media records.
- Family album rejects MIME/extension mismatch and fake image bytes before storage.
- Support inquiry rejects oversized, unsupported, MIME/extension mismatch, and fake image bytes.
- OCR rejects invalid uploads before remote OCR and records only bounded invalid-file metrics.
- Thumbnail endpoints never fall back to original private bytes when thumbnail generation/loading fails.
