# PWA and Mobile Capture Baseline

This slice makes the frontend more usable as an installable mobile workspace without changing server-side upload trust boundaries. `scripts/verify-pwa-mobile-baseline.ps1` now gates the installable app shell, API-cache exclusion, WCAG/mobile checklist anchors, and camera capture hints for the main image upload flows.

## Installed app shell

- `frontend/index.html` declares the manifest, theme color, Apple mobile app metadata, and a readable `TravelLedger` page title.
- `frontend/public/manifest.webmanifest` defines standalone display, icon metadata, shortcuts, and mobile-oriented categories.
- `frontend/src/registerServiceWorker.js` registers `/sw.js` only in production builds.
- `frontend/public/sw.js` caches only the app shell and static same-origin GET assets. It deliberately skips `/api/*` and non-GET requests so private ledger, drive, travel, OCR, and AI responses are not stored by the service worker.
- `frontend/public/offline.html` provides a safe navigation fallback when the app shell is installed but the network is unavailable.

## Mobile camera upload hints

The following image upload inputs now include `capture="environment"` so supported mobile browsers can open the rear camera directly:

- Calendar/ledger image capture flows in `CalendarWorkspace.vue`.
- Family album media uploads in `FamilyAlbumWorkspace.vue`.
- Travel memory photo uploads in `TravelMemoryPanel.vue`.
- CalenDrive profile image upload in `CalenDriveProfileModal.vue`.

These are client-side hints only. The backend MIME, extension, size, OCR, thumbnail, and privacy checks remain the authority. The verifier checks the component anchors so camera hints cannot silently disappear from OCR, travel, family album, or profile image flows.

## Mobile upload product slices

| Flow | Mobile action | Offline temporary state | Release guardrail |
| --- | --- | --- | --- |
| Receipt OCR | Take a receipt photo or choose an image from the library. | Draft keeps only the selected image, display filename, size, MIME type, createdAt, retryCount, and lastError until the user retries. | OCR analysis starts only after online retry and backend validation; no OCR text, AI result, presigned URL, or ledger mutation is stored offline. |
| Travel photo uploads | Capture trip photos in the field, preserve safe user-visible metadata, and attach them to a selected trip/memory. | Draft keeps the local media blob plus target plan/memory id visible to the current user. | Retry must call the normal prepare/complete upload flow again, re-run MIME/size/privacy checks, and avoid duplicate media rows with an idempotency key. |
| Family album uploads | Capture or choose family photos/videos for an album. | Draft keeps pending local media plus album/category target labels for review. | Drafts are private to the signed-in device, can be deleted before retry, and must never auto-share while offline. |

## Offline temporary upload contract

- Use IndexedDB for an explicit upload draft tray; do not use the service worker cache for private upload bodies, API responses, OCR responses, AI responses, or family/travel media metadata.
- Store the minimum client-side fields: draft id, flow type, local blob reference, display filename, MIME type, byte size, target label/id, createdAt, retryCount, lastError, and optional checksum/idempotency key.
- Never store auth tokens, CSRF tokens, presigned URLs, object keys, raw GPS/EXIF beyond the existing safe metadata policy, OCR extracted text, AI prompts/responses, or server-side analysis results in offline drafts.
- Require visible user controls for manual retry, delete, and clear-all; background retry is allowed only after the user is authenticated and the normal upload prepare/complete contract succeeds.
- Apply count, per-file size, total-byte, retry, and TTL caps; clear pending drafts on logout or account switch.
- Re-run backend MIME, extension, size, malware/image parsing, privacy, and ownership checks on every retry. Offline acceptance is only a convenience state, not a trust decision.
- Prefer WebCrypto encryption for stored blobs where browser support allows it; otherwise label the feature as local-device draft storage and provide a disable/clear option.
- Emit bounded client-side status messages so mobile users know whether a receipt, travel photo, or family album item is queued, retrying, uploaded, or discarded.
## Remaining backlog
| Area | Next step |
| --- | --- |
| Offline temporary upload queue | Implement an explicit IndexedDB draft tray for receipt OCR, travel photo uploads, and family album uploads with size/TTL caps, optional WebCrypto privacy hardening, manual retry/delete controls, logout cleanup, and backend revalidation on upload. |
| Install prompt UX | Add a small non-blocking install prompt that respects browser support, user dismissal, and explains offline draft limits honestly. |
| Mobile capture choice | Add separate `Take photo` and `Choose from library` buttons where `capture` is too aggressive for album workflows. |
| E2E coverage | Add mobile viewport smoke tests for install shell, OCR capture, travel photo upload, family album upload, and camera-hint fallback behavior. |
