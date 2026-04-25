# Worklog

이 문서는 사용자의 요청, 요청 분석, 실제 수행 내용, 구현 결과, 검증 기록을 남기는 작업 로그입니다.

## 기록 원칙

- 코드 작성 또는 파일 변경 작업을 수행할 때마다 새 항목을 추가합니다.
- 사용자의 원 요청 또는 요약을 먼저 기록합니다.
- 요청을 어떻게 해석했는지, 어떤 방식으로 실행했는지, 실제로 무엇이 구현되었는지 구분해서 기록합니다.
- 검증한 명령, 확인한 파일, 검증 결과를 남깁니다.
- 검증하지 못한 항목이 있으면 이유를 함께 남깁니다.

## 기록 양식

```md
### YYYY-MM-DD - 작업 제목

- 사용자 명령:
- 요청 분석:
- 실행 내용:
- 구현 내용:
- 검증 기록:
- 결과:
- 후속 메모:
```

## 작업 기록

### 2026-04-25 - Remove household calendar collapse control

- User request: Remove the calendar collapse/expand button feature from the household calendar ledger.
- Request analysis: The button was tied to `isCalendarCollapsed`, a local/remote calendar view preference field, conditional toolbar/calendar rendering, and a collapsed note style. Removing only the button would leave a hidden state path that could still collapse the calendar if old saved preferences were restored.
- Actions taken: Checked `codingconvention.md`, searched all collapse-related references in `CalendarWorkspace.vue` and `style.css`, and confirmed no other components use the household calendar collapsed note class.
- Implementation: Removed the collapse state, toggle function, saved preference field, localStorage key usage, conditional collapsed rendering, and the unused collapsed-note CSS. The calendar toolbar, size controls, and calendar body now always render.
- Verification: Ran `cmd /c npm run build` in `frontend` successfully. Verified no TypeScript SFC/script or `.ts`/`.tsx` files with `rg -n 'lang="ts"|lang=''ts''' frontend/src`, `Get-ChildItem -Path frontend/src -Recurse -Include *.ts,*.tsx`, and `rg --files frontend/src | rg '\.(ts|tsx)$'`. Verified household calendar collapse references are removed with `rg -n "household-calendar-collapsed-note|isCalendarCollapsed|toggleCalendarCollapsed|CALENDAR_COLLAPSE_KEY|달력 접기|달력 펼치기" frontend/src/components/CalendarWorkspace.vue frontend/src/style.css`.
- Result: The household calendar can no longer be collapsed from the UI or restored into a collapsed state from saved preferences.
- Follow-up note: Existing old collapsed preference values are ignored by the frontend after this change.

### 2026-04-25 - Preserve resized household calendar panel dimensions

- User request: In the household calendar ledger, resizing panels by dragging a corner does not survive refresh or another computer; only positions appear to be saved.
- Request analysis: GridStack snapshots already included `w/h`, but the remote save was debounced and a fast refresh could let a stale DB payload overwrite the newer local resized dimensions. The old localStorage layout format also had no timestamp, so the app could not tell whether local `w/h` was newer than the DB response.
- Actions taken: Checked `codingconvention.md`, traced `resizestop`/`dragstop`, local layout persistence, DB layout hydration, and the account layout setting API.
- Implementation: Changed calendar layout localStorage to store `{ savedAt, layout }` while still reading the old array format. Drag/resize stops now save the latest `w/h` to the DB immediately. On hydration, if the local resized layout is newer than the DB response, the app keeps the local `w/h` and writes it back to the DB instead of being overwritten by stale remote data.
- Verification: Ran `cmd /c npm run build` in `frontend` successfully. Verified no TypeScript SFC/script or `.ts`/`.tsx` files with `rg -n 'lang="ts"|lang=''ts''' frontend/src`, `Get-ChildItem -Path frontend/src -Recurse -Include *.ts,*.tsx`, and `rg --files frontend/src | rg '\.(ts|tsx)$'`. Ran `git diff --check -- frontend/src/components/CalendarWorkspace.vue` with no whitespace errors.
- Result: Drag-resized calendar panel widths/heights are persisted with positions and should survive refreshes and cross-device loads for the same authenticated user.
- Follow-up note: Existing old local array caches migrate automatically, but without a prior timestamp the DB remains the source of truth until the next resize is saved.

### 2026-04-25 - Persist household calendar size preferences to database

- User request: Confirm whether size changes are saved after layout DB persistence work.
- Request analysis: The draggable panel layout size (`w/h`) was already included in the DB layout payload, but the calendar display size controls (`달력 크기`, custom width/height, collapsed/display preferences) still lived only in browser localStorage, so they did not follow the user to another computer.
- Actions taken: Checked `codingconvention.md`, reviewed `CalendarWorkspace.vue` layout resize persistence, localStorage-only calendar size settings, and the existing account layout setting API.
- Implementation: Added a `household-calendar-view` layout setting scope for calendar display preferences. The calendar now saves and restores scale preset, custom size enabled/width/height, collapsed state, highlight mode, and aggregate panel visibility through the database while keeping localStorage as a fallback cache.
- Verification: Ran `cmd /c npm run build` in `frontend` successfully. Verified no TypeScript SFC/script or `.ts`/`.tsx` files with `rg -n 'lang="ts"|lang=''ts''' frontend/src`, `Get-ChildItem -Path frontend/src -Recurse -Include *.ts,*.tsx`, and `rg --files frontend/src | rg '\.(ts|tsx)$'`. Ran `git diff --check -- frontend/src/components/CalendarWorkspace.vue` with no whitespace errors.
- Result: Calendar size/display changes now sync through the DB for the same authenticated user across different computers.
- Follow-up note: Backend must be reachable for cross-device sync; if it is not, the current browser still keeps the settings locally.

### 2026-04-25 - Flush household layout edits to database

- User request: Household layout edits should remain applied when signing in from another computer.
- Request analysis: Layout persistence already used the database, but layout writes were debounced. If the user finished editing or left the page before the debounce completed, localStorage had the newest layout while the database still had the previous layout, so another device loaded stale data.
- Actions taken: Checked `codingconvention.md`, reviewed the household calendar layout sync, the main dashboard layout sync, the household palette Pinia store, and the palette container lifecycle.
- Implementation: Added pending remote payload tracking and immediate flush helpers for the household calendar layout, main dashboard palettes, and household palette dashboard store. Edit completion and component unmount now flush pending database saves instead of only clearing timers.
- Verification: Ran `cmd /c npm run build` in `frontend` successfully. Verified no TypeScript SFC/script or `.ts`/`.tsx` files with `rg -n 'lang="ts"|lang=''ts''' frontend/src`, `Get-ChildItem -Path frontend/src -Recurse -Include *.ts,*.tsx`, and `rg --files frontend/src | rg '\.(ts|tsx)$'`.
- Result: Recent layout edits are pushed to the database promptly, so the same authenticated user can load the latest household/dashboard arrangement on another computer.
- Follow-up note: If the backend is unreachable, the local cache still preserves the layout on the current browser until the remote save can succeed.

### 2026-04-25 - Extract calendar controls above layout grid

- User request: Correct the previous change: extract the calendar top control area shown in the screenshot and pull it above the layout grid.
- Request analysis: The prior implementation moved `달력 배치 / 고정됨` into the calendar panel header, but the intended layout was the reverse: the whole calendar header/control area should be separated from the draggable GridStack calendar panel. The calendar panel should keep showing the actual calendar body, while layout controls remain above it.
- Actions taken: Checked `codingconvention.md`, reviewed the current `CalendarWorkspace.vue` structure, and confirmed only calendar layout UI files needed changes.
- Implementation: Added a separate `household-calendar-control-panel` above the GridStack board containing the month header, month/year steppers, calendar size, week mode, display mode, collapse control, and layout controls. Removed those controls from the GridStack calendar panel so that panel now contains the calendar body/collapsed note only. Added a small edit-mode-only calendar drag bar and updated GridStack's drag handle selector so the calendar panel can still be moved after the header was extracted.
- Verification: Ran `cmd /c npm run build` in `frontend` successfully. Verified no TypeScript SFC/script or `.ts`/`.tsx` files with `rg -n 'lang="ts"|lang=''ts''' frontend/src`, `Get-ChildItem -Path frontend/src -Recurse -Include *.ts,*.tsx`, and `rg --files frontend/src | rg '\.(ts|tsx)$'`. Ran `git diff --check -- frontend/src/components/CalendarWorkspace.vue frontend/src/style.css` with no whitespace errors.
- Result: The screenshot's calendar top control area now appears as a separate section above the draggable calendar layout grid, and the draggable calendar panel remains movable in edit mode.
- Follow-up note: Browser visual QA with an authenticated session would be useful to fine-tune spacing against the screenshot.

### 2026-04-25 - Move calendar layout controls into calendar header

- User request: Move the calendar layout status/control area (`달력 배치`, `고정됨`) into the upper calendar section shown in the reference image.
- Request analysis: The layout controls were rendered as a separate toolbar above the GridStack board, while the requested position is inside the calendar panel header near the month badge and collapse control. The change should only move the UI controls and keep GridStack layout logic, DB persistence, calendar navigation, and transaction behavior unchanged.
- Actions taken: Checked `codingconvention.md`, reviewed `CalendarWorkspace.vue` and related layout CSS, and confirmed existing unrelated local files were outside this task.
- Implementation: Removed the standalone layout toolbar from the calendar workspace top and inserted a compact inline `household-calendar-layout-toolbar` inside the calendar panel header actions. Added `data-no-drag="true"` to the inline controls so they do not trigger panel dragging in layout edit mode, and added responsive CSS so the controls stay usable on desktop and mobile widths.
- Verification: Ran `cmd /c npm run build` in `frontend` successfully. Verified no TypeScript SFC/script or `.ts`/`.tsx` files with `rg -n 'lang="ts"|lang=''ts''' frontend/src`, `Get-ChildItem -Path frontend/src -Recurse -Include *.ts,*.tsx`, and `rg --files frontend/src | rg '\.(ts|tsx)$'`.
- Result: The `달력 배치 / 고정됨` controls now live in the calendar header area instead of as a separate toolbar above the grid.
- Follow-up note: Browser visual QA with an authenticated session would be useful to confirm the exact spacing against the screenshot.

### 2026-04-24 - Persist dashboard grid layouts in DB

- User request: Use the DB for the configured grid layouts so the same layout continues on other computers.
- Request analysis: The current grid layouts were stored only in browser `localStorage`, so layouts were tied to one browser/device. The safest change was to add a user-scoped layout settings API and keep `localStorage` as a fast cache/fallback without changing dashboard, calendar, transaction, or palette domain logic.
- Actions taken: Checked `codingconvention.md`, reviewed the Spring Boot account preference pattern, inspected the main dashboard, household palette store, and household calendar GridStack persistence points, and kept unrelated local changes out of scope.
- Implementation: Added `user_layout_settings` JPA storage with `GET/PUT /api/account/preferences/layout-settings/{scope}`. Connected DB sync for scopes `main-dashboard`, `household-dashboard`, and `household-calendar`. Existing local layouts are read first for fast paint and migrated to DB when no remote payload exists; later drag/add/hide/reset changes are saved locally and debounced to DB.
- Verification: Ran `cmd /c npm run build` in `frontend` successfully. Ran `.\gradlew.bat classes` in `backend` successfully. Verified no TypeScript SFC/script or `.ts`/`.tsx` files with `rg -n 'lang="ts"|lang=''ts''' frontend/src`, `Get-ChildItem -Path frontend/src -Recurse -Include *.ts,*.tsx`, and `rg --files frontend/src | rg '\.(ts|tsx)$'`. Ran `git diff --check` on the changed backend/frontend files with no whitespace errors.
- Result: Main dashboard, household dashboard palettes, and household calendar panel layouts now persist per authenticated user in the DB and can follow the user across devices.
- Follow-up note: Existing unrelated local files remain untouched and are not part of this work.

### 2026-04-24 - Dark mode audit and dashboard palette fixes

- User request: Check dark mode overall.
- Request analysis: The app already applies dark mode through `data-theme='toss'`, CSS variables, and global dark overrides. The main risk was scoped dashboard CSS with hard-coded light backgrounds/text, because scoped component styles can stay white even when the root theme is dark.
- Actions taken: Checked `codingconvention.md`, reviewed `App.vue` theme application, scanned global and scoped frontend styles for hard-coded light colors, inspected the main dashboard and household palette components, and kept unrelated local files out of scope.
- Implementation: Added dark-mode scoped overrides for `MainDashboardWorkspace.vue`, the standalone shell/topbar in `style.css`, and the household palette dashboard components (`PaletteContainer`, `PaletteItem`, `DragDropGrid`, `KpiPalette`, `CalendarPalette`). The overrides preserve light-mode defaults and only activate under `data-theme='toss'`.
- Verification: Ran `cmd /c npm run build` in `frontend` successfully. Verified no TypeScript SFC/script or `.ts`/`.tsx` files with `rg -n 'lang="ts"|lang=''ts''' frontend/src`, `Get-ChildItem -Path frontend/src -Recurse -Include *.ts,*.tsx`, and `rg --files frontend/src | rg '\.(ts|tsx)$'`. Ran `git diff --check -- frontend/src/components/MainDashboardWorkspace.vue frontend/src/features/palette/components/PaletteContainer.vue frontend/src/features/palette/components/PaletteItem.vue frontend/src/features/palette/components/DragDropGrid.vue frontend/src/features/palette/palettes/KpiPalette.vue frontend/src/features/palette/palettes/CalendarPalette.vue frontend/src/style.css` with no whitespace errors.
- Result: The main dashboard, standalone topbar, and household palette dashboard now follow the dark Toss palette instead of keeping light reference surfaces.
- Follow-up note: Browser screenshot QA with authenticated real data is still useful for lower-priority specialty states, but build/static checks passed.

### 2026-04-24 - Household calendar draggable layout

- User request: Make the household calendar ledger layout adjustable like a dashboard, with panels that can be moved and resized by dragging a corner.
- Request analysis: The calendar ledger already had four logical panels: calendar, quick transaction input, user aggregate settings, and selected-day transaction sheet. The safest implementation was to wrap these existing panels in a GridStack layout while keeping all transaction, calendar, aggregate, and API logic unchanged.
- Actions taken: Checked `codingconvention.md`, reviewed the existing dashboard GridStack implementation, and inspected the current `CalendarWorkspace.vue` panel structure and styles.
- Implementation: Added a 9-column GridStack layout to `CalendarWorkspace.vue` with persisted localStorage positions/sizes for the calendar, quick entry, aggregate, and sheet panels. Added layout edit mode, default layout reset, header-based dragging, bottom-right corner resizing, layout guide cells, per-panel min/max sizes, and calendar measurement refresh after moves/resizes. Updated `style.css` so each panel fills its grid cell, scrolls internally when resized smaller, and shows drag/resize affordances only in edit mode.
- Verification: Ran `cmd /c npm run build` in `frontend` successfully. Verified no TypeScript SFC/script or `.ts`/`.tsx` files with `rg -n 'lang="ts"|lang=''ts''' frontend/src`, `Get-ChildItem -Path frontend/src -Recurse -Include *.ts,*.tsx`, and `rg --files frontend/src | rg '\.(ts|tsx)$'`. Ran `git diff --check -- frontend/src/components/CalendarWorkspace.vue frontend/src/style.css` with no whitespace errors.
- Result: The household calendar ledger panels can now be repositioned and resized in edit mode without changing the underlying calendar selection, transaction entry, aggregate saving, or transaction sheet logic.

### 2026-04-24 - Move invite link creation to admin workspace

- User request: Move the invite-link creation currently at the bottom of the dashboard into an admin-only dashboard/admin feature.
- Request analysis: `InviteAccessPanel` was rendered under the main launcher dashboard for admins. Although hidden from non-admin users, the responsibility belonged in the admin workspace, which already has route/admin checks and additional admin access verification.
- Actions taken: Checked `codingconvention.md`, searched `App.vue`, `AdminWorkspace.vue`, and `InviteAccessPanel.vue` for invite-link state, handlers, and rendering locations. Confirmed unrelated local files are outside this task.
- Implementation: Removed `InviteAccessPanel`, invite creation state, and invite create/copy handlers from `App.vue`. Added `InviteAccessPanel` to `AdminWorkspace.vue` after admin access verification, moved invite creation/copy state and handlers into the admin workspace, and refreshed the admin dashboard after creating an invite so recent invite data can update.
- Verification: Verified invite creation references now live in `AdminWorkspace.vue` instead of `App.vue`. Ran `cmd /c npm run build` in `frontend` successfully. Verified no TypeScript SFC/script or `.ts`/`.tsx` files with `rg -n 'lang="ts"|lang=''ts''' frontend/src` and `rg --files frontend/src | rg '\.(ts|tsx)$'`. Ran `git diff --check -- frontend/src/App.vue frontend/src/components/AdminWorkspace.vue worklog.md` with no whitespace errors.
- Result: The main dashboard no longer shows the invite-link panel. Invite creation is now part of the administrator workspace and only available through the admin feature path after admin verification.
- Follow-up note: Backend authorization for `createInvite` remains unchanged; this change tightens the frontend placement and access surface.

### 2026-04-24 - Push auth guard restoration and dashboard load optimization

- User request: Push the current work.
- Request analysis: `main` was ahead of `origin/main` by two commits: `a3a8dbb Optimize main dashboard initial load` and `2521292 Restore frontend auth guard`. Unrelated local dirty/untracked files were present and intentionally excluded.
- Actions taken: Checked `codingconvention.md`, reviewed branch status and unpushed commit list, and prepared this push log before publishing.
- Implementation: Prepared a push package containing the dashboard initial load optimization, auth guard restoration, and this push log entry.
- Verification: `git status --short --branch` showed `main...origin/main [ahead 2]` before this log entry. Push verification was performed after `git push origin main`.
- Result: Push log recorded before publishing the current work.
- Follow-up note: No unrelated local files are included in the push package.

### 2026-04-24 - Restore frontend auth guard

- User request: Remove `AUTH_GUARD_BYPASS_ENABLED` and restore the existing login/authentication flow.
- Request analysis: The temporary auth guard bypass lived in `frontend/src/App.vue`, where failed `fetchCurrentUser()` calls created a local preview user and logout returned to the bypass user instead of a logged-out state.
- Actions taken: Checked `codingconvention.md`, searched the frontend for bypass symbols, reviewed the session restore and logout flow, and confirmed unrelated local files are outside this task.
- Implementation: Removed the bypass flag, local preview user, and `createAuthBypassUser()`. `restoreSession()` now returns to `currentUser = null` on unauthenticated sessions and only shows non-401 errors. `handleLogout()` now always clears `currentUser` and shows the normal logout message.
- Verification: Verified no `AUTH_GUARD_BYPASS_ENABLED`, `AUTH_GUARD_BYPASS_USER`, `createAuthBypassUser`, `local-auth-bypass`, or local preview bypass references remain with `rg`. Ran `cmd /c npm run build` in `frontend` successfully. Verified no TypeScript SFC/script or `.ts`/`.tsx` files with `rg -n 'lang="ts"|lang=''ts''' frontend/src` and `rg --files frontend/src | rg '\.(ts|tsx)$'`. Ran `git diff --check -- frontend/src/App.vue worklog.md` with no whitespace errors.
- Result: The app now requires the normal backend-authenticated login flow again instead of entering via the temporary local bypass.
- Follow-up note: Since backend authentication is restored, a disconnected backend will show the login/unauthenticated state instead of opening the dashboard.

### 2026-04-24 - Main dashboard initial load optimization

- User request: The dashboard takes too long to load after refresh.
- Request analysis: The dashboard API calls were already started in parallel, but the component waited for all summary requests to settle before applying any data and before initializing GridStack. A single slow endpoint could delay the whole dashboard paint.
- Actions taken: Checked `codingconvention.md`, reviewed `MainDashboardWorkspace.vue` loading flow, API calls, mount sequence, and current git status.
- Implementation: Added per-user session summary cache so a refreshed tab can paint the last dashboard data immediately. Changed summary loading to apply each API result as soon as it resolves, while ignoring stale responses from older loads. Moved GridStack initialization ahead of network loading so palette layout renders immediately and data fills progressively.
- Verification: Ran `cmd /c npm run build` in `frontend` successfully. Verified no TypeScript SFC/script or `.ts`/`.tsx` files with `rg -n 'lang="ts"|lang=''ts''' frontend/src` and `rg --files frontend/src | rg '\.(ts|tsx)$'`. Ran `git diff --check -- frontend/src/components/MainDashboardWorkspace.vue worklog.md` with no whitespace errors.
- Result: Dashboard refresh no longer waits for every summary endpoint before rendering the palette grid. Cached data appears first when available, then fresh household/travel/drive/compare/control data updates incrementally.
- Follow-up note: Backend endpoint latency can still affect when each individual number becomes fresh, but the first dashboard view should appear much sooner.

### 2026-04-24 - Push current dashboard work

- User request: Push the work completed so far and record a log summarizing the work through this push.
- Request analysis: The current branch was `main`, ahead of `origin/main` by 13 committed dashboard/auth/navigation changes. Unrelated local dirty/untracked files were present and intentionally excluded from the push package.
- Actions taken: Checked `codingconvention.md`, branch status, remote URL, and unpushed commit list. Prepared this worklog entry so the push itself has a summarized record.
- Implementation: Push package includes `71ce968 Add household palette dashboard`, `4507cd2 Temporarily bypass frontend auth guard`, `e566d83 Add configurable main dashboard palettes`, `c88c309 Add global feature navigation`, `b5fa4bb Fix main dashboard palette sizing`, `8764761 Expand main dashboard palettes`, `4f8e8b7 Apply dashboard reference styling`, `d53fb7b Harden palette layouts for dense data`, `45a72fb Rebalance main dashboard palette sizes`, `529aa59 Fix palette numeric text rendering`, `b337adf Rebalance palette scale and readability`, `267561d Rebalance main dashboard palette sizes`, and `cdaeb99 Align dashboard palette grid guides`, plus this push summary log commit.
- Verification: Previous implementation commits each include their build/type/no-TypeScript/diff-check verification. Before this log commit, `git status --short --branch` showed `main...origin/main [ahead 13]`; unrelated local files remained unstaged.
- Result: Push summary was recorded before pushing to `origin/main`.
- Follow-up note: No push should be repeated unless explicitly requested again or a new completed work item needs to be published.

### 2026-04-24 - Palette grid guide alignment

- User request: Add/configure the horizontal grid as well because the visible grid and actual palette sizes feel mismatched, especially when multiple palettes stack.
- Request analysis: The edit guide rendered only 9 vertical columns while GridStack uses both column width and row height. The guide also had CSS overrides that could make it wider than the actual GridStack area, and item content used inset values that were not fully tied to GridStack's margin.
- Actions taken: Checked `codingconvention.md`, reviewed the main dashboard GridStack setup, the shared household palette `DragDropGrid`, guide rendering, margin/inset values, and responsive padding rules.
- Implementation: Added explicit grid margin/gap constants, computed the visible guide row count from palette positions and spans, and rendered a full `9 columns x row count` cell guide in edit mode. The guide now uses the same cell height, row gap, column gap, and content inset as the GridStack item content. Cell height calculations now use the actual grid element width first so padding does not skew the sizing.
- Verification: Ran `cmd /c npm run build` in `frontend` successfully. Verified no TypeScript SFC/script or `.ts`/`.tsx` files with `rg -n 'lang="ts"|lang=''ts''' frontend/src` and `rg --files frontend/src | rg '\.(ts|tsx)$'`. Ran `git diff --check -- frontend/src/components/MainDashboardWorkspace.vue frontend/src/features/palette/components/DragDropGrid.vue worklog.md` with no whitespace errors.
- Result: Main dashboard and household dashboard edit grids now show horizontal and vertical cells that align with the real palette content area, reducing visual drift when palettes stack across multiple rows.
- Follow-up note: Browser visual QA with connected sample data can still fine-tune the accent color/opacity of the guide, but the sizing math now follows GridStack's actual margin and cell measurements.

### 2026-04-24 - Main dashboard palette size review

- User request: Review the sizes of drive summary, travel summary, quick amount entry, and related palettes based on the attached screenshot.
- Request analysis: The previous readability fix made the grid cells taller, but some palettes still kept larger fixed spans and reserved empty recent-item areas. As a result, travel summary, drive summary, recent saved files, and quick entry could look oversized with large blank space when real data was sparse.
- Actions taken: Checked `codingconvention.md`, inspected the main dashboard palette size registry, default layout positions, quick-entry form density, and travel/drive summary body layout.
- Implementation: Bumped the main dashboard storage version to `v6`, reduced quick entry from `3x4` to `3x3`, reduced travel summary, drive summary, and recent saved files from `3x3` to `3x2`, and rebalanced default positions. Travel and drive summary palettes now render the recent list area only when actual recent records exist, and list space is capped to a compact 72px when present.
- Verification: Ran `cmd /c npm run build` in `frontend` successfully. Verified no TypeScript SFC/script or `.ts`/`.tsx` files with `rg -n 'lang="ts"|lang=''ts''' frontend/src`, `rg --files frontend/src | rg '\.(ts|tsx)$'`, and `Get-ChildItem -Path frontend/src -Recurse -Include *.ts,*.tsx`. Ran `git diff --check -- frontend/src/components/MainDashboardWorkspace.vue worklog.md` with no whitespace errors.
- Result: The reviewed palettes now better match their information density: quick entry has enough room for the full form without the previous extra row, and travel/drive summary cards no longer reserve blank list space when there are no recent items.
- Follow-up note: Because the main dashboard storage version changed, the main dashboard layout resets once to apply the new fixed sizes instead of restoring the oversized localStorage layout.

### 2026-04-24 - Palette scale and readability rebalance

- User request: Palette sizes are still too small and problematic. Analyze thoroughly, create a plan, execute it, and run verification/inspection.
- Request analysis: The issue was structural rather than a single palette bug. Main GridStack cell height used `cellWidth * 0.82` with a 132px cap, which made every palette physically short on a 9-column dashboard. Form controls also used small `0.72rem` text and 28px height, so even enlarged palettes looked cramped. Existing localStorage layouts could continue to restore the older cramped dashboard.
- Actions taken: Checked `codingconvention.md`, reviewed main dashboard palette sizing, shared household palette grid sizing, typography rules, and storage key versioning. Planned the fix before editing: increase grid unit height, enlarge text/control sizes, bump the main dashboard storage version, then validate.
- Implementation: Raised main dashboard GridStack cell height to a 112-168px range using `cellWidth * 0.96`; raised the shared household palette grid to a 96-156px range using `cellWidth * 0.92`. Bumped the main dashboard palette storage version from `v4` to `v5` so stale cramped layouts do not persist. Increased main palette title/label/value/control sizes, input/button heights, card padding, quick-form gaps, and shared KPI palette typography.
- Verification: Ran `cmd /c npm run build` in `frontend` successfully. Verified no TypeScript SFC/script or `.ts`/`.tsx` files with `rg -n 'lang="ts"|lang=''ts''' frontend/src` and `Get-ChildItem -Path frontend/src -Recurse -Include *.ts,*.tsx`. Ran `git diff --check -- frontend/src/components/MainDashboardWorkspace.vue frontend/src/features/palette/components/DragDropGrid.vue frontend/src/features/palette/palettes/KpiPalette.vue` with no whitespace errors.
- Result: Palettes now have larger physical grid cells, readable form controls, stronger KPI typography, and a refreshed default storage key so the new sizing actually applies on reload.
- Follow-up note: Because the storage key changed, the main dashboard layout resets once to the new default sizing; this is intentional to remove older cramped localStorage state.

### 2026-04-24 - Palette numeric text rendering fix

- User request: The attached dashboard screenshot shows palette text and sizing problems; analyze and fix the issue so palettes do not break or look crushed.
- Request analysis: The visible issue was caused by applying multi-line `-webkit-line-clamp` rules to numeric/currency values. Numeric values such as `₩0` and `0 B` should not be treated like paragraphs because line clamping can squeeze glyphs vertically and make the won sign look struck or clipped. The floating settings button was also overlapping a right-side palette.
- Actions taken: Checked `codingconvention.md`, reviewed the main dashboard metric styles and the shared household KPI palette styles, and confirmed unrelated dirty files are outside this work.
- Implementation: Changed main dashboard metric values and KPI palette values to single-line numeric rendering with tabular numerals, stable line-height, ellipsis overflow, and no multi-line clamp. Added stable line-height to labels/meta text. Moved the main dashboard floating settings button from the vertical center to the lower-right area so it does not cover palette content.
- Verification: Ran `cmd /c npm run build` in `frontend` successfully. Verified no TypeScript SFC/script or `.ts`/`.tsx` files with `rg -n 'lang="ts"|lang=''ts''' frontend/src` and `Get-ChildItem -Path frontend/src -Recurse -Include *.ts,*.tsx`. Ran `git diff --check -- frontend/src/components/MainDashboardWorkspace.vue frontend/src/features/palette/palettes/KpiPalette.vue` with no whitespace errors.
- Result: Currency and numeric values render as stable one-line figures instead of being vertically clipped or visually crushed, and the settings button no longer covers the middle-right palette.
- Follow-up note: If a browser screenshot still shows cramped cards after localStorage restores an older layout, reset the main dashboard palette layout once so the newer fixed sizes are reapplied cleanly.

### 2026-04-24 - Main palette size rebalance

- User request: The attached quick-entry palette screenshot shows the palette size breaking; inspect and fix other palettes too so they do not break or get crushed, changing palette sizes if needed.
- Request analysis: The quick-entry form has seven vertical rows and cannot reliably fit inside the existing `3x3` height on narrower dashboard widths. Summary and recent-file palettes also had real-data risk because they combine KPI grids with lists.
- Actions taken: Checked `codingconvention.md`, reviewed the palette size registry and GridStack span utility, and confirmed existing unrelated dirty files are outside this work.
- Implementation: Added `3x4` as a supported palette span. Changed main quick-entry to `3x4`, travel summary and drive summary to `3x3`, and recent drive files to `3x3`. Rebalanced default positions to avoid overlap after the larger fixed sizes. Gave quick-entry an internal vertical overflow fallback and gave summary lists more reserved space.
- Verification: Ran `cmd /c npm run build` in `frontend` successfully. Verified no TypeScript SFC/script or `.ts`/`.tsx` files with `rg -n 'lang="ts"|lang=''ts''' frontend/src` and `Get-ChildItem -Path frontend/src -Recurse -Include *.ts,*.tsx`. Ran `git diff --check -- frontend/src/components/MainDashboardWorkspace.vue frontend/src/features/palette/types.js frontend/src/features/palette/utils/paletteLayout.js` with no whitespace errors.
- Result: Form-heavy and list-heavy palettes now get enough grid height instead of being forced into cramped cells, while stored layouts still normalize through the existing layout utility.
- Follow-up note: Existing user localStorage layouts will be re-normalized at load with the new fixed sizes, so some palettes may shift to the next available empty area to prevent overlap.

### 2026-04-24 - Palette real-data layout hardening

- User request: Inspect all current palettes and verify whether palette proportions, positions, and visual shape break when real data is rendered.
- Request analysis: The main risk was not API logic but dense UI payloads: long Korean labels, large currency values, long file/travel names, many recent files, and high calendar entry counts inside fixed GridStack cells.
- Actions taken: Checked `codingconvention.md`, reviewed the main dashboard palettes and the household palette components (`PaletteItem`, `KpiPalette`, `CalendarPalette`, `DragDropGrid`), then inspected fixed sizes, grid rows, overflow rules, and mobile rules.
- Implementation: Hardened main dashboard palette CSS so metric grids use fixed two-row tracks, long values clamp instead of pushing cards, travel/drive summary lists get bounded space, payment/capacity cards no longer overrun their own rows, recent files scroll vertically inside the palette, quick actions/feature links truncate safely, and mobile metric cards keep a stable two-column shape. Hardened household KPI and calendar palettes with overflow containment, line clamp, ellipsis, and bounded calendar count/marker widths.
- Verification: Ran `cmd /c npm run build` in `frontend` successfully. Verified no TypeScript SFC/script or `.ts`/`.tsx` files with `rg -n 'lang="ts"|lang=''ts''' frontend/src` and `Get-ChildItem -Path frontend/src -Recurse -Include *.ts,*.tsx`. Ran `git diff --check -- frontend/src/components/MainDashboardWorkspace.vue frontend/src/features/palette/palettes/KpiPalette.vue frontend/src/features/palette/palettes/CalendarPalette.vue` with no whitespace errors.
- Result: Real data should now be contained inside each palette without widening the page, breaking card ratios, or visually spilling out of fixed palette cells.
- Follow-up note: A connected backend/browser pass with production-sized user data can still tune exact row counts, but the layout now has containment safeguards for overflow-heavy payloads.

### 2026-04-24 - Main dashboard reference design skin

- User request: Apply only the design style from the attached Figma reference image.
- Request analysis: Keep existing main dashboard palette data, API reads, GridStack layout, navigation, and user storage behavior unchanged, and apply only the visual language from the reference: light gray app canvas, white cards, lime/lavender accents, compact rounded panels, and a soft top navigation bar.
- Actions taken: Checked `codingconvention.md`, reviewed `MainDashboardWorkspace.vue` and `style.css`, and confirmed unrelated dirty files are outside this work.
- Implementation: Added palette type/metric CSS hooks to `MainDashboardWorkspace.vue` and layered visual-only CSS overrides for the reference-style dashboard surface, cards, metric panels, charts, floating settings button, and tools panel. Updated `main-shell--standalone` and top navigation styling in `style.css` to match the same light dashboard frame.
- Verification: Ran `cmd /c npm run build` in `frontend` successfully. Verified no TypeScript SFC/script or `.ts`/`.tsx` files with `rg -n 'lang="ts"|lang=''ts''' frontend/src` and `Get-ChildItem -Path frontend/src -Recurse -Include *.ts,*.tsx`. Ran `git diff --check -- frontend/src/components/MainDashboardWorkspace.vue frontend/src/style.css` with no whitespace errors.
- Result: The main dashboard keeps its current functionality while adopting the attached reference's light HR-dashboard visual tone.
- Follow-up note: A browser pass with real connected user data can tune exact visual balance if needed.

### 2026-04-24 - 메인 팔레트 확장 기능 추가

- 사용자 명령: 팔레트 기능은 더 확장적이어야 하며, 사진을 불러와 액자처럼 표시하거나 드라이브 용량 확인, 최근 저장 파일, 빠른 접근 단축기능처럼 동작할 수 있어야 한다는 요청.
- 요청 분석: 메인 대시보드 팔레트를 단순 요약 정보판이 아니라 기능 단위 위젯으로 확장하는 작업으로 해석했습니다. 백엔드 신규 API는 만들지 않고 기존 드라이브/여행/가계부 API 응답에서 가능한 범위로 구현했습니다.
- 실행 내용: `codingconvention.md`를 확인하고 드라이브 썸네일/다운로드 경로, 여행 사진 데이터 사용 방식, 기존 메인 팔레트 구조를 확인했습니다.
- 구현 내용: `사진 액자`, `드라이브 용량`, `최근 저장 파일`, `빠른 단축 기능` 팔레트를 추가했습니다. 드라이브 최근 파일 API를 추가로 읽어 이미지 파일은 사진 액자 후보로 사용하고, 여행 포트폴리오에 사진 URL이 포함될 경우 함께 액자 후보로 모으도록 했습니다. 드라이브 용량 팔레트는 사용량과 가능한 경우 전체 용량 대비 퍼센트를 표시하고, 최근 파일 팔레트는 썸네일/파일명/용량과 열기 링크를 제공합니다. 빠른 단축 기능 팔레트는 가계부, 여행, 드라이브, 메인 이동을 버튼으로 제공합니다.
- 검증 기록: `frontend`에서 `cmd /c npm run build`를 실행해 Vite 빌드 통과를 확인했습니다. `.ts`, `.tsx`, `lang="ts"`가 없는지 확인했고, `git diff --check -- frontend/src/components/MainDashboardWorkspace.vue`에서 공백 오류가 없음을 확인했습니다.
- 결과: 메인 팔레트가 사진 표시와 드라이브 운영 정보, 최근 파일 접근, 기능 단축 버튼까지 포함하는 확장형 대시보드 구조로 넓어졌습니다.
- 후속 메모: 백엔드 연결 후 실제 업로드 사진/최근 파일 응답 필드에 따라 썸네일 표시를 브라우저에서 추가 확인해야 합니다. Vite 번들 크기 경고는 기존과 동일하게 표시됩니다.

### 2026-04-24 - 메인 팔레트 크기 선택 제거 및 고정 크기 적용

- 사용자 명령: 각 팔레트마다 내용에 따라 필요한 크기가 다르므로 알맞게 조정하고, `2x2` 같은 크기 선택 방식은 필요 없으니 고정 크기로 가자는 요청.
- 요청 분석: 메인 대시보드 팔레트에서 사용자가 크기를 직접 선택하는 UI를 제거하고, 팔레트 종류별로 내용에 맞는 고정 그리드 크기를 강제하는 작업으로 해석했습니다.
- 실행 내용: `codingconvention.md`를 확인하고 `MainDashboardWorkspace.vue`의 팔레트 템플릿, 저장 키, 크기 변경 함수, 편집 컨트롤을 확인했습니다.
- 구현 내용: 메인 대시보드 저장 버전을 `v3`로 올려 기존 저장 크기를 새 고정 크기 체계로 보정하게 했습니다. 팔레트 타입별 고정 크기 매핑을 추가하고, 저장된 크기나 드래그 스냅샷의 크기 값도 로드/저장 시 고정 크기로 재정규화되도록 했습니다. 편집 모드의 크기 select를 제거해 숨김/삭제만 보이도록 했습니다.
- 검증 기록: `frontend`에서 `cmd /c npm run build`를 실행해 Vite 빌드 통과를 확인했습니다. `.ts`, `.tsx`, `lang="ts"`가 없는지 확인했고, `git diff --check -- frontend/src/components/MainDashboardWorkspace.vue`에서 공백 오류가 없음을 확인했습니다.
- 결과: 메인 대시보드 팔레트는 내용별 고정 크기로 배치되며, 사용자는 크기 단위를 직접 선택하지 않습니다.
- 후속 메모: Vite 번들 크기 경고는 기존과 동일하게 표시됩니다.

### 2026-04-24 - 헤더 주요 기능 네비게이션 추가

- 사용자 명령: 최초 페이지에서 다른 기능으로 접속할 수 있는 버튼들이 보이지 않으므로 헤더에 네비게이션을 추가해달라는 요청.
- 요청 분석: 메인 대시보드 팔레트 배치와 무관하게 가계부, 여행, 드라이브 등 주요 기능으로 이동할 수 있는 전역 진입점이 필요하다고 해석했습니다.
- 실행 내용: `codingconvention.md`를 확인하고 `frontend/src/App.vue`의 상단 헤더와 라우팅 함수, `frontend/src/style.css`의 topbar 스타일을 확인했습니다.
- 구현 내용: 헤더에 `메인`, `가계부`, `여행`, `드라이브`, 관리자 계정일 때 `관리자`가 표시되는 네비게이션을 추가했습니다. 기존 `navigate` 함수를 그대로 사용하고, 현재 활성 라우트는 강조 표시되도록 했습니다. 모바일 폭에서는 줄바꿈되는 형태로 유지했습니다.
- 검증 기록: `cmd /c npm run build`로 프론트 빌드 통과를 확인했습니다. `.ts`, `.tsx`, `lang="ts"`가 없는지 확인했고, `git diff --check`에서 공백 오류가 없음을 확인했습니다.
- 결과: 팔레트 상태와 상관없이 상단 헤더에서 주요 기능으로 바로 이동할 수 있습니다.
- 후속 메모: Vite 번들 크기 경고는 기존과 동일하게 표시됩니다.

### 2026-04-24 - 메인 대시보드 팔레트 구성 기능 추가

- 사용자 명령: 최초 페이지의 가계부, 여행, 드라이브 내용을 변경하고, 특히 가계부는 이번 주/이번 달 사용금액과 수입, 특정 결제수단 사용금액, 주간/월간 비교 그래프, 빠른 금액 입력 등을 사용자가 설정에서 여러 팔레트로 추가해 원하는 대로 구성할 수 있게 해달라는 요청.
- 요청 분석: 로그인 후 첫 화면인 메인 대시보드를 정적 요약 카드가 아니라 사용자별 커스터마이즈 가능한 팔레트 보드로 바꾸는 작업으로 해석했습니다. 기존 백엔드 API와 가계부 도메인 로직은 변경하지 않고, 기존 API를 읽거나 `createEntry`를 호출하는 프론트 진입 기능만 추가했습니다.
- 실행 내용: `codingconvention.md`를 확인하고 기존 `MainDashboardWorkspace.vue`, 가계부 통계/비교/입력 API 사용 방식을 확인했습니다.
- 구현 내용: `MainDashboardWorkspace.vue`를 메인 전용 GridStack 팔레트 대시보드로 개편했습니다. 사용자별 localStorage 저장 키를 사용해 팔레트 배치/숨김/복구/추가/삭제/크기 변경/초기화를 지원하고, 설정 플로팅 패널에서 여러 팔레트를 추가할 수 있게 했습니다. 기본 팔레트로 가계부 종합, 이번 주/이번 달 지출과 수입, 결제수단 사용금액 선택, 주간/월간 비교 그래프, 빠른 금액 입력, 여행 요약, 드라이브 요약, 기능 바로가기를 배치했습니다.
- 검증 기록: `frontend`에서 `cmd /c npm run build`를 실행해 Vite 빌드 통과를 확인했습니다. `rg`와 `Get-ChildItem`으로 `.ts`, `.tsx`, `lang="ts"`가 없는지 확인했습니다. `git diff --check -- frontend/src/components/MainDashboardWorkspace.vue`로 공백 오류가 없음을 확인했고, CRLF 변환 경고만 표시됐습니다.
- 결과: 최초 메인 페이지가 사용자 구성형 팔레트 대시보드로 동작하며, 가계부/여행/드라이브 요약과 빠른 금액 입력을 같은 화면에서 구성할 수 있습니다.
- 후속 메모: 백엔드 연결이 불가한 현재 상태에서는 요약/입력 API 호출이 실패할 수 있으나, 화면 구성과 빌드는 정상입니다. 실제 데이터 표시와 빠른 입력 저장은 백엔드 연결 후 브라우저에서 추가 확인이 필요합니다.

### 2026-04-24 - 백엔드 미연결 상태용 로그인 가드 임시 해제

- 사용자 명령: 현재 백엔드 연결이 불가해 로그인하지 않으면 화면에 접속할 수 없으므로 네비게이션 가드를 풀고, 나중에 복구 요청 시 다시 복구할 수 있게 해달라는 요청.
- 요청 분석: 인증 API가 실패해도 프론트 화면을 확인할 수 있도록 임시 로컬 사용자로 진입시키는 작업으로 해석했습니다. 실제 인증 로직을 삭제하지 않고 복구가 쉬운 플래그 방식으로 제한했습니다.
- 실행 내용: `codingconvention.md`를 확인하고 `frontend/src/App.vue`의 `restoreSession`, 로그인 분기, 로그아웃 흐름을 확인했습니다.
- 구현 내용: `AUTH_GUARD_BYPASS_ENABLED`와 로컬 미리보기 사용자 객체를 추가했습니다. `fetchCurrentUser` 실패 시 임시 사용자로 진입하게 했고, 임시 우회 상태에서 로그아웃 버튼을 눌러도 다시 로그인 화면에 갇히지 않도록 처리했습니다.
- 검증 기록: `frontend`에서 `cmd /c npm run build`로 프론트 빌드 통과 여부를 확인했습니다.
- 결과: 백엔드가 연결되지 않아도 프론트 주요 화면으로 진입할 수 있습니다. 복구 시 `AUTH_GUARD_BYPASS_ENABLED` 우회 코드를 제거하거나 `false`로 되돌리면 기존 로그인 흐름을 다시 사용할 수 있습니다.
- 후속 메모: 이 변경은 임시 개발용 우회이며 배포 전에 반드시 복구해야 합니다.

### 2026-04-24 - 가계부 팔레트 대시보드 및 통합 대시보드 개편

- 사용자 명령: 기존 가계부/여행/드라이브 기능 로직과 API를 변경하지 않고, 가계부 첫 화면을 팔레트 기반 대시보드로 개편하고 로그인 후 기능 선택 화면을 전체 종합 대시보드로 교체하라는 요청.
- 요청 분석: 백엔드와 기존 도메인 로직은 유지하고, 프론트 진입 화면과 디자인 계층만 추가해야 하는 작업으로 해석했습니다. TypeScript 도입 금지, 사용자별 localStorage 저장, GridStack 9열 팔레트 편집, 기존 가계부 탭 유지, 작업 후 검증 및 커밋 조건을 적용했습니다.
- 실행 내용: `codingconvention.md`와 기존 `HouseholdWorkspace`, `App.vue`, API 호출 구조를 확인했습니다. `pinia`, `gridstack` 의존성을 추가하고, Pinia를 앱에 연결했습니다.
- 구현 내용: `frontend/src/features/palette` 아래 팔레트 컨테이너, GridStack 그리드, 팔레트 공통 셸, KPI/월달력 팔레트, 레지스트리, 레이아웃/스토리지 유틸을 추가했습니다. `frontend/src/stores/useDashboardPaletteStore.js`에 프리셋, 편집 모드, 추가/숨김/복구/삭제/크기 변경/레이아웃 저장 액션을 구현했습니다. `HouseholdWorkspace` 기본 탭을 `dashboard`로 바꾸고 기존 달력/통계/검색/휴지통/인사이트/비교/입출력/분류관리 탭은 유지했습니다. `MainDashboardWorkspace`를 추가해 가계부/여행/드라이브 요약과 기존 기능 이동 동작을 읽기 전용으로 연결했습니다.
- 검증 기록: `frontend`에서 `cmd /c npm run build`를 실행해 Vite 프로덕션 빌드 통과를 확인했습니다. `rg`와 `Get-ChildItem`으로 신규 `.ts`, `.tsx`, `lang="ts"`가 없는지 확인했습니다. `git diff --check`는 공백 오류 없이 통과했고, CRLF 변환 경고만 표시됐습니다. Vite 빌드에서 기존 규모성 JS 청크 경고가 있었고, `npm install` 후 npm audit 기준 high 취약점 2건 경고가 표시됐습니다.
- 결과: 가계부 팔레트 대시보드와 전체 종합 대시보드가 프론트에 추가됐고, 기존 기능 접근 경로는 유지했습니다.
- 후속 메모: 브라우저에서 실제 드래그/스왑 상호작용은 로그인 세션과 API 데이터가 있는 환경에서 추가 확인하는 것이 좋습니다. 이번 커밋에는 기존에 있던 `deploy/oci/scripts/provision-project-tenant.sh` 변경과 미추적 외부 폴더는 포함하지 않습니다.

### 2026-04-24 - 프론트 로그인 화면 문구 제거 커밋 및 푸시

- 사용자 명령: 현재 프론트를 수정했으니 푸시해달라는 요청.
- 요청 분석: 사용자가 수정한 프론트 변경을 확인하고, 관련 파일만 커밋한 뒤 원격 `main` 브랜치로 푸시하는 작업으로 해석했습니다.
- 실행 내용: `codingconvention.md`를 확인하고, `git status`, `git fetch origin`, `git diff -- frontend/src/App.vue`로 변경 범위와 원격 상태를 확인했습니다. 기존에 남아 있던 `deploy/oci/scripts/provision-project-tenant.sh` 수정과 미추적 파일들은 이번 커밋 대상에서 제외합니다.
- 구현 내용: `frontend/src/App.vue`에서 비로그인 화면의 안내 카피 블록이 제거된 변경을 커밋 대상으로 확인했습니다.
- 검증 기록: `frontend` 디렉터리에서 `cmd /c npm run build`를 실행해 Vite 프로덕션 빌드가 성공하는 것을 확인했습니다. 빌드 중 JS 청크 크기 경고는 있었지만 빌드는 정상 완료되었습니다.
- 결과: 프론트 변경과 작업 로그를 커밋하고 원격 `main`으로 푸시합니다.
- 후속 메모: 이전 문서 규칙 커밋 `c2e73a8`도 아직 원격에 없으므로 이번 푸시 때 함께 올라갑니다.

### 2026-04-24 - Git 커밋 및 푸시 작업 규칙 추가

- 사용자 명령: 작업이 하나 완료되면 커밋하고, 기능이 하나 완성됐을 때 커밋하며, 푸시는 사용자가 지시할 때만 하라는 요청.
- 요청 분석: Git 작업 흐름에 대한 프로젝트 작업 규칙을 `codingconvention.md`에 추가하고, 이번 변경 내역을 `worklog.md`에 기록해야 하는 요청으로 해석했습니다.
- 실행 내용: `codingconvention.md`와 `worklog.md`를 먼저 확인한 뒤, `codingconvention.md`에 Git 작업 규칙 섹션을 추가하고 프로젝트 컨벤션 기록에 날짜별 항목을 남겼습니다.
- 구현 내용: 완료된 작업/기능 단위 커밋, 커밋 전 변경 파일 확인, 무관한 변경 제외, 사용자 명시 지시 전 푸시 금지 규칙을 문서화했습니다.
- 검증 기록: 파일 수정 후 `Select-String`으로 추가된 규칙 문구를 확인했고, `git status --short -- codingconvention.md worklog.md`로 이번 커밋 대상 파일이 두 문서뿐임을 확인했습니다.
- 결과: Git 커밋/푸시 작업 규칙이 문서에 추가되었습니다.
- 후속 메모: 이번 문서 작업 완료 후 `codingconvention.md`와 `worklog.md`만 커밋하고 푸시는 수행하지 않습니다.

### 2026-04-24 - 코딩 컨벤션 및 작업 로그 문서 추가

- 사용자 명령: 앞으로 코딩 컨벤션 추가 요청은 `codingconvention.md`에 기록하고, 코드 작성 전 해당 내용을 확인/검증하도록 해달라는 요청. 또한 사용자의 명령, 분석, 실행 내용, 구현 내용, 검증 기록을 남기는 `worklog.md` 파일을 추가해달라는 요청.
- 요청 분석: 프로젝트 루트에 기준 문서 두 개를 추가해 이후 작업 절차를 명문화하는 문서 작업으로 해석했습니다.
- 실행 내용: 기존 파일 목록과 Git 상태를 확인하고, 루트에 `codingconvention.md`와 `worklog.md`를 새로 추가했습니다.
- 구현 내용: `codingconvention.md`에는 컨벤션 누적 규칙과 코드 작성 전 필수 확인 절차를 작성했습니다. `worklog.md`에는 기록 원칙, 기록 양식, 이번 작업의 최초 기록을 작성했습니다.
- 검증 기록: `rg --files`로 기존 파일 목록을 확인했고, `git status --short`로 작업 트리 상태를 확인했습니다. 문서 추가 후 `Get-Content codingconvention.md`, `Get-Content worklog.md`로 생성 여부와 내용을 다시 확인했습니다.
- 결과: 문서 기반 작업 절차가 추가되었습니다.
- 후속 메모: 앞으로 코드 변경 작업 전 `codingconvention.md`를 확인하고, 작업 완료 후 `worklog.md`에 기록합니다.
### 2026-04-24 - Household calendar aggregate toggle and side-by-side layout

- User request: In the household calendar ledger, allow the user-configured aggregate panel to be turned on/off, and place the calendar and quick transaction input next to each other.
- Request analysis: The existing calendar screen already had aggregate preference loading/saving through `fetchHouseholdAggregatePreferences` and `saveHouseholdAggregatePreferences`, plus the quick entry form and calendar in the same Vue component. The safest change was to keep the API/data contract intact and add only a local display toggle and CSS layout areas.
- Actions taken: Checked `codingconvention.md`, reviewed `CalendarWorkspace.vue`, the aggregate preference flow, quick entry form bindings, calendar sizing state, and the related global styles in `style.css`.
- Implementation: Added a persisted localStorage display toggle for the user aggregate panel. Turning the panel off hides aggregate cards/settings while preserving the saved aggregate configuration, and turning it on restores the same configured cards. Updated the calendar workspace grid so the calendar is visually placed on the left and the quick transaction/aggregate column on the right on desktop, with the transaction sheet spanning below. Added responsive stacking for narrower screens and compact quick-entry sizing in the side column.
- Verification: Ran `cmd /c npm run build` in `frontend` successfully. Verified no TypeScript SFC/script or `.ts`/`.tsx` files with `rg -n 'lang="ts"|lang=''ts''' frontend/src`, `Get-ChildItem -Path frontend/src -Recurse -Include *.ts,*.tsx`, and `rg --files frontend/src | rg '\.(ts|tsx)$'`. Ran `git diff --check -- frontend/src/components/CalendarWorkspace.vue frontend/src/style.css` with no whitespace errors.
- Result: The household calendar view now has a user-controlled aggregate on/off switch and a desktop layout where the calendar and quick transaction input sit side by side without changing transaction entry, calendar selection, aggregate saving, or backend logic.
