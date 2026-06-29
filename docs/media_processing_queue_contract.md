# Media Processing Queue Contract

Updated: 2026-06-30

This contract records how TravelLedger should handle large photo/video uploads and thumbnail reprocessing without coupling user-facing upload completion to expensive image work. It complements the current prepared-thumbnail implementation and sets the next queue boundary for travel media, family album media, and future large archive exports.

## Current baseline

| Area | Existing behavior | Evidence |
| --- | --- | --- |
| Travel presigned upload | Browser uploads originals and prepared thumbnails directly to object storage when presigned upload is enabled; backend validates candidate metadata and completed object keys. | `TravelMediaStorageService.preparePresignedUploads`, `completePresignedUpload`, `validateObjectKey`, and prepared-thumbnail candidate validation. |
| Prepared thumbnails | Image uploads generate fixed prepared thumbnail profiles, and media responses prefer prepared thumbnails instead of original files. | `ImageThumbnailService.createPreparedThumbnails`, `PreparedThumbnailProfile.defaultWidths`, `TravelMediaStorageService.loadPreparedThumbnail`. |
| Thumbnail backfill | Existing travel images can be repaired by a bounded startup/scheduled/manual backfill pass. | `TravelThumbnailBackfillService` with enabled flag, page size, max items per run, running guard, cursor, and status counts. |
| Family album thumbnail serving | Family album thumbnail responses fail closed instead of falling back to original bytes when a thumbnail is unavailable. | `FamilyAlbumControllerTest.shouldNotFallbackToOriginalImageWhenThumbnailIsUnavailable`. |

## Queue separation target

| Queue | Owns | Must not own | First implementation slice |
| --- | --- | --- | --- |
| Large media upload queue | Durable upload intent, object-key scope, owner/album/plan scope, upload completion status, size/content-type validation, retry visibility, and cleanup of abandoned uploads. | Thumbnail rendering, EXIF parsing, AI/OCR work, public sharing, or data export archive creation. | Persist upload intent and completion state before expensive processing; expose status to UI. |
| Thumbnail reprocess queue | Missing/corrupt prepared thumbnail detection, bounded reprocessing, retry count, failure reason, and safe backoff. | Original upload authorization, share permission changes, or user-visible mutation outside thumbnail status. | Convert travel thumbnail backfill into a queue-backed worker while preserving bounded page/max-item behavior. |
| Video preview queue | Optional poster frame or metadata extraction for video files. | Blocking upload completion or serving full video bytes as thumbnail fallback. | Record video preview status separately from original object availability. |
| Data export media queue | Large photo/file archive generation, progress, encryption, retry/resume, and restore rehearsal evidence. | Inline privacy export response for ledger CSV and manifests. | Keep binary archive generation async and encrypted; current `/api/privacy/data-export` remains ledger/manifests only. |

## Concrete lane ownership

| Lane | Trigger | Allowed work | Forbidden work | Bounded controls |
| --- | --- | --- | --- | --- |
| Original media upload lane | UI/API upload prepare and completion requests. | Validate file metadata, object-key owner/record scope, original object availability, durable media row/status, and abandoned upload cleanup. | Thumbnail retries, image decode loops, public-share/token mutation, AI/OCR work, and media export archive creation. | `TRAVEL_PRESIGNED_UPLOAD_ENABLED`, object prefix scope, per-feature upload limits, and request/auth rate limits. |
| Thumbnail backfill/reprocessing lane | Scheduled/manual repair of existing image media rows. | Detect missing prepared variants, call `ensurePreparedThumbnails`, count created/already-present/skipped/failed statuses, and persist bounded failure evidence. | Original upload authorization, presigned URL minting, upload-byte acceptance, public-share/token mutation, video transcoding, and export archive creation. | `TRAVEL_THUMBNAIL_BACKFILL_ENABLED`, `TRAVEL_THUMBNAIL_BACKFILL_FIXED_DELAY_MS`, `TRAVEL_THUMBNAIL_BACKFILL_INITIAL_DELAY_MS`, `TRAVEL_THUMBNAIL_BACKFILL_PAGE_SIZE`, and `TRAVEL_THUMBNAIL_BACKFILL_MAX_ITEMS_PER_RUN`. |
| Future video/transcode lane | Explicit video preview or transcode job enqueue. | Optional poster/metadata extraction, retry/backoff, status updates, and metrics for expensive video processing. | Synchronous request-thread video decoding, thumbnail backfill reuse, and direct serving of full video bytes as thumbnail fallback. | A separate queue/executor/metrics before broad video processing is enabled. |

- `TRAVEL_PRESIGNED_UPLOAD_ENABLED` controls browser direct original uploads; it is not a switch for thumbnail workers.
- The `TRAVEL_THUMBNAIL_BACKFILL_*` variables control only the thumbnail backfill/reprocessing lane.
- Operators must not process original video/photo uploads on the thumbnail backfill scheduler, mint presigned upload URLs, mutate sharing/public-token state, or run export archive work.
- Future video/transcode support must add a separate queue/executor/metrics before broad video processing is enabled and must not reuse the thumbnail backfill scheduler.

## Required invariants

| Invariant | Reason |
| --- | --- |
| Upload completion must validate owner-scoped object keys before reading object metadata. | Prevents user A from completing user B's presigned object. |
| Upload completion must record original availability separately from thumbnail readiness. | Large media should not block user confirmation while thumbnails are still processing. |
| Thumbnail workers must be bounded by enable flag, page size, max items per run, running guard, and cursor/progress state. | Prevents startup or scheduled repair from saturating CPU, memory, or object storage. |
| Thumbnail failure must fail closed and keep original/private access rules unchanged. | Broken image processing should not leak originals through preview endpoints. |
| Video preview generation must be optional and asynchronous. | Video decoding is expensive and should not block upload completion. |
| Queue payloads must not include raw presigned URLs, public tokens, API keys, secondary PINs, raw EXIF payloads, or full filesystem paths. | Queue rows/logs often live longer and are more broadly observable than request memory. |
| Reprocessing must be idempotent. | Retried workers should not create duplicate media rows or mutate share permissions. |
| User-visible status must distinguish uploaded, processing, ready, failed, and retryable states. | Users need recovery paths for large uploads and thumbnail failures. |

## Test evidence to keep current

| Evidence | Coverage |
| --- | --- |
| `TravelMediaStorageServiceTest.storesPreparedThumbnailsAlongsideOriginalImage` | Original image storage creates prepared thumbnail variants. |
| `TravelMediaStorageServiceTest.storesRouteGpxWithoutGeneratingPreparedThumbnails` | Non-image route uploads do not trigger thumbnail generation. |
| `TravelMediaStorageServiceTest.ensurePreparedThumbnailsReportsAlreadyPresentWhenPreparedSetExists` | Reprocessing is idempotent when all prepared variants exist. |
| `TravelMediaStorageServiceTest.ensurePreparedThumbnailsRecreatesMissingPreparedVariant` | Reprocessing repairs a missing prepared thumbnail variant. |
| `TravelThumbnailBackfillServiceTest.runBackfillPassNowProcessesConfiguredImageBatchAndCountsStatuses` | Backfill processes bounded batches and reports created/already-present statuses. |
| `TravelThumbnailBackfillServiceTest.runBackfillPassNowSkipsRepositoryWhenDisabled` | Backfill can be disabled before touching repositories. |
| `ImageThumbnailServiceTest.shouldCreatePreparedThumbnailsForConfiguredWidths` | Prepared thumbnail widths remain fixed and predictable. |
| `ImageThumbnailServiceTest.shouldReuseCachedThumbnailForRepeatedRequests` | Repeated thumbnail reads can use cache instead of reprocessing originals. |
| `FamilyAlbumControllerTest.shouldNotFallbackToOriginalImageWhenThumbnailIsUnavailable` | Thumbnail endpoints fail closed when thumbnail creation/loading fails. |

## Release gate

A release that changes travel media upload, family album upload, drive preview thumbnails, prepared thumbnail generation, video preview behavior, media export archives, or upload status UI should include:

- Updated queue ownership notes if expensive work moves between synchronous upload, upload queue, thumbnail reprocess queue, video preview queue, or export queue.
- A test or contract update proving owner-scoped object-key validation still happens before object metadata reads.
- A test or contract update proving thumbnail failures do not fall back to original private bytes.
- Bounded worker settings for any new processor: enable flag, batch/page size, max items per run, running guard, retry/backoff policy, and safe failure reason.
- Evidence that `TRAVEL_THUMBNAIL_BACKFILL_*` remains present in both env examples and mapped through `application.yml` whenever thumbnail worker behavior changes.
- Separate queue/executor/metrics evidence before enabling any video preview/transcode processor.
- A privacy review proving queue payloads and logs do not include raw presigned URLs, public tokens, API keys, secondary PINs, raw EXIF payloads, or full filesystem paths.

## CI contract

`scripts/verify-media-processing-contract.ps1` checks this document, the current travel thumbnail backfill service, prepared thumbnail storage/serving code, test evidence, security baseline row, and the GitHub Actions `media-processing-contract` release-gate job. The gate is intentionally structural; it does not run thumbnail workers or upload tests.