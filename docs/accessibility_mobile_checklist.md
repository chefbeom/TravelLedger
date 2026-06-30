# Accessibility and Mobile UX Checklist

Updated: 2026-06-30

This checklist turns the accessibility/mobile UX improvement track into reviewable release criteria. Use it for dashboard drag widgets, maps, CalenDrive, dialogs, PIN/auth screens, upload flows, OCR, and AI report screens. It maps the project baseline to the W3C WCAG 2.2 Recommendation so reviews can connect product risk to specific accessibility criteria.

## Priority Screens

| Priority | Area | Main risk | First acceptable slice |
| --- | --- | --- | --- |
| P0 | Login, PIN, session expiry | Users can be locked out or unable to recover from errors. | Keyboard-only login/PIN flow, visible focus, clear error text, touch-safe controls, mobile numeric PIN entry hints, and screen-reader PIN keypad progress. |
| P0 | Admin dialogs and destructive actions | Focus loss or unclear confirmation can cause unsafe actions. | Focus trap, Escape/Cancel path, action-specific confirmation copy, and return-focus evidence. |
| P1 | Dashboard drag widgets | Drag-only layout blocks keyboard and touch assistive users. | Non-drag reorder controls, reset-layout action, visible focus, and mobile-safe control spacing. |
| P1 | Drive/share/file upload | File pickers and share dialogs often miss labels and status updates. | Labeled controls, upload progress text, link expiry/revoke state announced, and touch-safe share actions. |
| P1 | Maps and travel media | Map-only information is hard for keyboard/screen-reader users. | Provide list/table alternative for routes, markers, clusters, and photos. |
| P2 | AI/OCR result review | Long generated output and failed OCR states can be hard to scan. | Section headings, status summary, retry state, and non-color-only confidence indicators. |

## WCAG 2.2 Traceability

| WCAG 2.2 criterion | Project interpretation | Required evidence |
| --- | --- | --- |
| 2.1.1 Keyboard | Login, PIN, admin dialogs, dashboard layout, drive share, uploads, maps, OCR, and AI review must complete their primary action without pointer input. | Keyboard-only trace for each touched P0/P1 screen. |
| 2.4.7 Focus Visible | Focus must be visible on buttons, links, inputs, file controls, map alternatives, drag alternatives, and destructive actions. | Screenshot or manual note for desktop, modal, and mobile states. |
| 2.4.11 Focus Not Obscured | Sticky headers, bottom sheets, modals, toasts, and virtual-keyboard layouts must not cover the active control. | Focus trace at 360x640 and 200% zoom. |
| 2.5.7 Dragging Movements | Drag widgets, map gestures, route/photo movement, and drive item movement need a non-drag path. | Button/menu/list alternative can perform the same operation. |
| 2.5.8 Target Size (Minimum) | WCAG minimum is 24 CSS px; this project targets 44x44 CSS px for primary mobile actions, PIN keys, upload buttons, share controls, and destructive admin actions. | Mobile viewport review with no cramped primary actions. |
| 3.3.1 Error Identification | Auth, upload, OCR, AI, backup, and share errors identify the failed field/action in text. | Error-state screenshot or component story. |
| 3.3.3 Error Suggestion | Recoverable failures explain the next action: retry, revoke, re-upload, contact admin, or restore backup. | Manual failure-path note. |
| 3.3.8 Accessible Authentication | Login and PIN flows must not require memorization puzzles, hidden gestures, or transcription-only steps; password managers, paste, autocomplete, and numeric keyboards should work where safe. | Auth/PIN smoke pass on desktop and mobile. |
| 4.1.3 Status Messages | Upload progress, OCR/AI completion, share revoke, backup result, and async failure states are available as text status, not spinner/color only. | DOM/live-region check or visible status text. |

## Global accessibility primitives

| Primitive | Implementation | Release expectation |
| --- | --- | --- |
| Visible focus | `frontend/src/style.css` defines `--focus-ring` and a global `:focus-visible` treatment for links, buttons, inputs, selects, textareas, role buttons, and tabbable controls. | Any new interactive control must keep or intentionally replace the visible focus indicator. |
| Mobile touch target | `frontend/src/style.css` defines `--touch-target-min: 44px` and applies it to buttons, role buttons, text inputs, selects, and textareas. | Primary actions must not shrink below the 44x44 CSS px project target on mobile. |
| Reduced motion | `frontend/src/style.css` honors `prefers-reduced-motion: reduce` by minimizing animation, transition, and smooth-scroll duration. | Motion-heavy UI must be meaningful, optional, and safe for reduced-motion users. |
| Screen-reader utility | `.sr-only` and `.visually-hidden` are available for non-visual labels/status copy. | Icon-only buttons and visual-only status text need accessible names or hidden text. |

## Accessibility Risk Register

| Area | Required non-pointer path | Required focus behavior | Mobile/touch baseline | Current evidence |
| --- | --- | --- | --- | --- |
| Login, PIN, session expiry | Submit, clear, delete, recovery, and retry work from keyboard. | Focus starts on the first useful field, errors are announced, and focus returns to the trigger after session-expiry dialogs. | Numeric keyboard hints and 44x44 CSS px PIN/action targets. | `PinPadInput.vue` has labelled keypad controls and non-secret progress text. |
| Admin dialogs and destructive actions | Confirm/cancel works without mouse and never depends on color alone. | Focus is trapped in the dialog, Escape/Cancel is available, and focus returns to the trigger. | Destructive and cancel actions remain separated and touch-safe at 360x640. | Manual checklist required before admin release. |
| Dashboard drag widgets | Reorder/reset layout is available through buttons, menus, or forms. | Focus order follows visual order after reorder and reset. | Drag handles do not become the only touch target. | `DragDropGrid.vue` exposes keyboard move controls in edit mode for up/left/down/right movement. |
| Drive/share/file upload | Select file, upload, revoke link, change permission, and copy link are reachable from keyboard. | Upload/share status is text-visible and focus moves to actionable recovery on failure. | Primary share/upload controls target 44x44 CSS px and fit at 360x640. | Security PIN forms expose mobile input hints; `InviteAccessPanel.vue` has labelled expiry controls, button types, generated-link live status, and alert/status feedback. |
| Maps and travel media | Route, marker, cluster, and photo data has list/table alternatives. | Focus can leave map controls and return to the selected list item. | Map gestures are optional for core read/review flows. | Implementation queued. |
| AI/OCR result review | Retry, delete history, inspect source entries, and accept/reject recommendations are keyboard reachable. | Long AI/OCR output has headings and status summaries before detail sections. | Generated reports do not require horizontal scrolling at 360x640. | Manual checklist required before AI/OCR release. |

## WCAG 2.2 Checklist

| Check | Target behavior | Evidence |
| --- | --- | --- |
| Keyboard operation | Every interactive control is reachable and usable without a mouse. | Tab/Shift+Tab smoke pass for each priority screen. |
| Focus visibility | Focus indicator is clearly visible and not hidden behind sticky headers or modals. | Screenshot or manual note for default, modal, and mobile viewport states. |
| Focus order | Navigation order follows visual order and returns to the trigger after dialog close. | Manual keyboard trace. |
| Dialog behavior | Modal dialogs use one active focus region, close predictably, and preserve context. | Dialog checklist entry per modal family. |
| Drag alternatives | Drag/drop widgets also provide buttons, menus, or forms for the same operation. | Dashboard and file movement flows work without pointer drag. |
| Target size | Primary touch targets meet WCAG 2.2 minimum target size and have practical mobile spacing. | 360px-wide viewport review. |
| Accessible names | Icon-only buttons, inputs, upload controls, and map controls have meaningful labels. | DOM review or automated accessibility scan. |
| Error recovery | Auth, upload, OCR, AI, and backup errors explain what failed and what the user can do next. | Error state screenshot or component story. |
| Status updates | Upload progress, AI/OCR completion, and async failures are announced in text, not only spinners. | Screen-reader or DOM live-region smoke check. |
| Color and contrast | Text, icons, focus rings, error states, and chart labels do not rely on color alone. | Contrast check on light/dark or current theme states. |
| Reduced motion | Motion-heavy UI respects reduced-motion preferences. | Browser reduced-motion setting smoke check. |
| Mobile viewport | Core flows work at 360x640 without horizontal scrolling or clipped actions. | Mobile viewport smoke pass. |

## Manual Test Recipe

1. Open each priority screen at desktop width and at a 360px mobile viewport.
2. Complete the primary flow using only keyboard controls.
3. Repeat the same flow with the browser zoomed to 200%.
4. Trigger one validation error and one async failure state.
5. Confirm focus lands on useful content after navigation, modal close, upload completion, failed AI/OCR actions, and dashboard widget reorder.
6. Record gaps in the roadmap or issue tracker with the affected screen and checklist row.

## Current Evidence

| Area | Evidence |
| --- | --- |
| Global focus/touch/reduced-motion baseline | `frontend/src/style.css` provides global `:focus-visible`, 44px target sizing for common controls, `prefers-reduced-motion: reduce`, and `.sr-only`/`.visually-hidden` utilities. |
| Login and invite PIN keypad | `PinPadInput.vue` exposes the keypad as a labelled group, gives digit/clear/delete buttons accessible names, and announces PIN entry progress without exposing digits. |
| Dashboard drag widgets | `DragDropGrid.vue` exposes an edit-mode keyboard control panel with labelled up/left/down/right buttons and bounded movement. |
| Profile and CalenDrive security PIN forms | PIN inputs use numeric input mode, numeric pattern hints, max length, and autocomplete hints so mobile keyboards and password managers behave predictably. |
| Invite access panel | `InviteAccessPanel.vue` exposes a labelled expiry select, explicit button types, generated-link live status, copy action label, and separate success/error feedback semantics. |

## Release Evidence Template

Copy this block into the release note, pull request, or issue when a priority screen changes.

```text
Commit SHA:
Screen or flow:
Priority: P0 | P1 | P2
Desktop browser/version:
Mobile viewport/device: 360x640 | other
Keyboard-only result:
Focus visible/not-obscured result:
Drag alternative result:
Touch target review:
Error/status message review:
Reduced-motion review:
Screen-reader or DOM status evidence:
Known gaps and follow-up issue:
Release decision: pass | conditional | fail
```

## Implementation Queue

| Order | Change | Reason |
| --- | --- | --- |
| 1 | Add reusable focus-trap and return-focus handling for modal families. | Reduces repeated dialog accessibility bugs. |
| 2 | Add route/marker list alternatives for map screens. | Makes travel information available without map gestures. |
| 3 | Add accessible upload status components for Drive/OCR/media flows. | Makes long-running operations understandable. |
| 4 | Add automated accessibility smoke checks for login, dashboard, drive share, and OCR review. | Prevents regressions after the manual baseline is fixed. |
| 5 | Add per-component stories or fixtures for modal, map, drive upload, and AI/OCR review states. | Makes manual evidence easier to capture. |

## CI Gate

`scripts/verify-accessibility-mobile-checklist.ps1` keeps this checklist, the known frontend evidence anchors, the project roadmap, and the GitHub Actions release gate aligned. The verifier is intentionally text-based so it can run without a browser while the team is still building automated accessibility smoke tests.

The CI gate fails when:

- WCAG 2.2 traceability rows or priority-screen risk rows disappear.
- The release evidence template loses keyboard, focus, touch target, drag alternative, status/error, reduced-motion, or mobile viewport fields.
- `frontend/src/style.css` loses global focus-visible, touch-target, reduced-motion, or screen-reader utility anchors.
- `DragDropGrid.vue` loses the keyboard dashboard layout controls or bounded movement function.
- `PinPadInput.vue` loses labelled keypad, live progress, clear/delete labels, or button semantics.
- Security PIN forms lose numeric mobile input hints.
- CI release gate wiring no longer includes the accessibility/mobile checklist job.

## Release Gate

A release touching priority screens should include:

- A checklist note for keyboard, focus, touch target, and error-state behavior.
- Updated WCAG 2.2 Traceability and Accessibility Risk Register rows for any touched P0/P1 screen.
- A mobile viewport smoke pass at 360px width, with 360x640 used for auth, PIN, upload, and share flows.
- A confirmation that destructive admin/share actions still have clear text, cancel paths, and focus returns to the trigger after close.
- A ticket for any remaining P0 or P1 accessibility defect that is intentionally deferred.