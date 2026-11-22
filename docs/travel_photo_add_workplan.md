# 이미 만든 여행에 사진 추가하기 — 분석 및 작업 지시서

- 대상: 상단 메뉴 `기록·지도` > `여행 · 일정 · 경로`에서, **이미 만든 여행에 사진(GPS가 있는 사진 포함)을 한 장 또는 여러 장 추가하는 흐름**
- 선행 작업: 입력 흐름 재설계(`docs/record_map_input_flow_workplan.md`, 커밋 e2477e88). 이 문서는 그 결과물(가져오기 대화상자 `TravelImportDialog`, 기록 수정 대화상자 `TravelRecordEditDialog`, `lib/travelImport.js`, `lib/useTravelImport.js`)을 **재사용**해서 "사진 추가" 진입점과 기존 기록에 붙이는 규칙을 더한다.
- 작성 기준: 2026-11-22 작업 트리(미커밋 변경 포함). 코드 정독 + 임시 Playwright 스펙(목 API)으로 데스크톱 1440x900·모바일 390x844, 라이트·다크 캡처와 측정을 했다. 임시 스펙은 삭제했다.
- 사용자 요구(요지): "만든 여행에 사진을 추가하려는데 사진 추가 버튼이 따로 없어서 지도를 누르고 그 상태로 사진을 추가하게 된다. 사진 추가 버튼을 따로 만들어라. 사진은 여러 장, 한 장 모두 되어야 한다."
- 구현 담당자는 **이 문서만 보고** 작업한다. 5장 단계를 순서대로 진행하고, 단계마다 빌드·단위·e2e를 통과시킨 뒤 다음 단계로 넘어간다.

---

## 0. 꼭 지킬 규칙 (먼저 읽을 것)

1. **백엔드·DB 변경 없음.** 이번 설계는 기존 API만 쓴다(4.4절). 여러 장 업로드는 이미 지원된다(`POST /api/travel/memories/{id}/media/presign|complete`가 `files` 배열을 받음, 서버 한 장 15MB·JPG/PNG/WEBP/GIF/BMP만 허용 — `TravelMediaStorageService` 40·842·1004행). `backend/`는 열람만 한다.
2. **새 npm/Gradle 의존성 금지.** EXIF는 기존 `exifr`(`lib/photoMetadata.js`의 `extractPhotoMetadata`). `package.json`, `package-lock.json` 수정 금지.
3. **수정 금지 파일.**
   - 전역: `frontend/src/style.css`, `frontend/src/App.vue`, `frontend/src/lib/api.js`(기존 export만 import. **`api.js`에는 무관한 미커밋 변경 `fetchAdminWithdrawalEmbeddingStatus`가 있으니 파일을 열어 저장하지도 말 것**)
   - 공통 지도: `TravelMapPanel.vue`, `TravelMyMapClusterPanel.vue`(props/이벤트만 사용)
   - 가계부(`Household*.vue`, `Ledger*.vue`, `StatisticsWorkspace.vue`, `CalendarWorkspace.vue`, `lib/ledger*.js`, 가계부 e2e)와 통합 달력(`IntegratedCalendarWorkspace.vue`, `components/integrated-calendar/*`, `lib/integratedCalendar*.js`, `e2e/integrated-calendar.spec.js`) 전부
   - `e2e/record-sharing.spec.js`, `e2e/integrated-calendar.spec.js`는 실행만 한다.
4. **수정 대상 파일(이 목록 밖은 건드리지 않는다).** `lib/travelImport.js`, `lib/useTravelImport.js`, `components/travel-import/TravelImportDialog.vue`, `components/travel-import/TravelImportDraftItem.vue`, `components/travel-import/TravelRecordEditDialog.vue`, `components/TravelWorkspace.vue`, `components/TravelMyMapWorkspace.vue`, `components/TravelPhotoLightbox.vue`(prop 1개·emit 1개 추가만), `components/TravelHubWorkspace.vue`(prop·emit 연결만), `components/TravelMemoryPanel.vue`, `tests/unit/travelImport.test.js`, `e2e/travel-import.spec.js`(목 코드를 공용 파일로 옮기는 것만), 신규 `e2e/fixtures/travelMock.js`, 신규 `e2e/travel-photo-add.spec.js`.
5. **작업 트리의 무관한 미커밋 변경을 건드리지 않는다**(`api.js`, `style.css`, 백엔드 AI·탈퇴, docker-compose, `.env*`, 미추적 로그·캡처 파일 등). `git checkout/restore/stash/add -A/clean` 금지. 커밋은 사용자 지시가 있을 때만, 이 작업 파일만 지정해서 한다.
6. **계약 유지.**
   - `RecordMapWorkspace.vue` props(`token`, `publicView`, `placeFocusRequest`, `calendarGrantId`)·emits(`open-travel`, `place-focus-consumed`) — 이번 작업에서 이 파일은 수정하지 않는다.
   - Hub(`RecordMapHubWorkspace.vue`)의 hash 모드 동기화(`#travel`↔`#my-map`) — 수정하지 않는다.
   - **통합 달력 기록 딥링크**: `TravelWorkspace.vue`의 `recordFocusRequest` 처리(sourceType `memory` → `openPhotoEditor` → 설정 2단계 편집기, `route`, `plan`, ledger)와 `openPhotoEditor`는 **의미를 바꾸지 않는다**.
   - 가져오기 대화상자의 기존 동작과 `e2e/travel-import.spec.js` 시나리오(I1~I11, L1~L4, E1~E3, R1) 전부 통과. 기존 셀렉터·접근성 이름(4.6절 "유지" 표)을 바꾸지 않는다.
7. **모달·스크롤.** App.vue는 모바일에서 `[role="dialog"][aria-modal="true"]`가 있으면 배경 스크롤을 잠그고, Esc는 캡처 단계에서 **마지막 aria-modal 다이얼로그의 `data-modal-close` 버튼**을 누른다. 새 다이얼로그를 만들지 않고 기존 `RecordMapDialog` 기반 대화상자를 재사용한다. 지도 위 안내 띠·끌어놓기 표시에는 `role="dialog"`/`aria-modal`을 붙이지 않는다.
8. **전역 CSS 대응.** 전역 CSS가 버튼 배경·테두리를 `!important`로 강제하고 다크 테마 특이도가 높다. `docs/record_map_ux_workplan.md` 3.6절 규칙(루트 접두사 + `!important`, 선택 상태는 배경 대신 테두리·inset 그림자, `border-radius` 전제 금지)과 `components/record-map/recordMapTokens.css`의 `--rm-*` 토큰을 쓴다. **전역 클래스 이름(`topbar__actions` 등)을 컴포넌트 안 레이아웃용으로 빌려 쓰지 않는다** — 이번 버그의 원인이다(1.3절).
9. **파일 선택 창은 사용자 클릭 처리기 안에서 동기로 연다.** `<input type="file">.click()`은 클릭 이벤트 처리 중에만 확실히 동작한다(iOS Safari). 비동기 컴포넌트가 열린 뒤 `click()`하지 않는다(4.3절).
10. **문서·테스트에 실제 사용자 데이터·로컬 경로를 쓰지 않는다.** 픽스처는 테스트 안에서 버퍼로 만든다(6.3절).

---

## 1. 현행 흐름과 "버튼이 없다"는 인식의 원인

### 1.1 화면 트리 (관련 부분만)

```
RecordMapHubWorkspace (.record-map-hub)  [내 장소][여행 · 일정 · 경로][공유받은 여행]
└─ TravelWorkspace.vue  primaryTab: map | setup | photos | finance
    ├─ map   → TravelMyMapWorkspace.vue
    │           헤더(1530~1545행): 여행 [새 여행 만들기][여행 정보 수정] | 기록 [＋ 사진·GPX·장소 추가][기록 목록·편집] | 공유 [지도 공유]
    │           사진 지도 패널(1620행~): 선택 바 `.travel-map-selected-record`(1650행, 사진 없는 기록 핀에만) + TravelMyMapClusterPanel
    │           TravelPhotoLightbox ×2(1726행 전체 화면용, 1923행 일반) — `수정` → handleEditPhoto(472~485행) → 기록 수정 대화상자
    ├─ setup → TravelHubWorkspace.vue "TRAVEL SETUP" 4단계(2182행~)
    │           2단계 "사진 · 장소 · GPS 등록"(2270~2295행) → TravelMemoryPanel.vue(기록 지도 + 기록 표 + 레거시 편집기 모달)
    ├─ TravelImportDialog (여행에 추가)         ← TravelWorkspace 맨 끝에 마운트
    └─ TravelRecordEditDialog (기록 수정)        ← TravelWorkspace 맨 끝에 마운트(primaryTab과 무관하게 열 수 있음)
```

설정 2단계로 들어가는 길: 지도 헤더 `기록 목록·편집`, `여행 정보 수정` → 단계 탭 `사진 · GPS`, 4단계 `사진 · GPS 더 추가`, **통합 달력 기록 딥링크**(sourceType `memory` → `openPhotoEditor` → 2단계 + 편집기 모달), 새 여행을 만든 뒤 `기록 목록·편집`.

### 1.2 사용자가 사진을 추가하려 할 때 실제로 거치는 경로 (측정)

임시 스펙 조건: 여행 `가을 교토 여행`(2026-10-01~03)에 사진 있는 기록 `기요미즈데라`(10-01 10:00)와 사진 없는 기록 `점심`(10-02)이 있음. 지도에서 이 여행을 선택한 상태에서 시작.

| 경로 | 사용자 행동 | 측정 결과 |
|---|---|---|
| **P-A 지도 헤더 통합 버튼** | `＋ 사진·GPX·장소 추가` → 파일 선택 → 저장 (3) | 동작은 한다. 그러나 화면에 **`사진 추가`라는 이름의 버튼은 0개**(데스크톱·모바일 모두 `getByRole('button', { name: '사진 추가' })` = 0). 이름에 사진이 들어간 버튼은 이 통합 버튼 하나뿐. 대화상자 첫 화면은 지도(왼쪽/모바일 위)와 `지도에서 위치 찍기`·`현재 위치`·`장소 검색`이 크게 보이고, 사진 입력은 "파일을 끌어 놓거나 파일 선택" 점선 영역이다(모바일에서는 지도 아래 y=517px, 캡처 `C9-import-mobile-first-view-m`). **기존 기록 바로 옆(같은 날 10m, 20분 뒤)에서 찍은 사진도 새 기록 `기요미즈데라`를 하나 더 만든다**(측정: POST memories 제목 `기요미즈데라`, `후시미이나리`, `screenshot` — 기존 기록에 붙이는 선택지가 없다, `C6b-import-review-d`). |
| **P-B 기록 목록·편집(사용자가 말한 경로)** | `기록 목록·편집` → 설정 2단계. 단계 제목 문구는 "사진을 올리면 EXIF의 날짜·시간·GPS를 읽어 기록에 채웁니다."인데 **사진 버튼이 없다.** 이 단계의 버튼은 `경로 관리`, `새 기록`, 일차 탭, 지도 도구, 표의 `수정`/`삭제`, `여행 정보`, `지도에서 확인`뿐(측정 목록). 지도 패널 안내문은 "지도를 누르면 먼저 임시 핀이 생기고, 그 핀을 한 번 더 눌렀을 때만 기록 입력 모달이 열립니다."(TravelMemoryPanel 854행), 지도 힌트도 같은 내용(898행). → 지도 클릭 → 임시 핀 클릭 → 편집기 모달 → **스크롤**(사진 칸 `사진 업로드`가 모달 안 y=888px/뷰포트 900px, 모바일 y=1,211px/844px, 모달 내용 높이 1,576px/1,945px) → 파일 선택 → 제목 입력 → `기록 저장` (7 + 스크롤) | **라이트 테마에서 `새 기록` 버튼이 보이지 않는다**(측정: `color: rgb(99,118,109)`, `-webkit-text-fill-color: #fff`, `background: transparent` → 흰 글자가 밝은 배경 위, `C8-legacy-new-record-d`). 그래서 화면에서 보이는 유일한 "추가" 수단이 지도 클릭이다. 편집기에서 여러 장을 고르면 **모두 한 기록**에 붙고 첫 사진 EXIF만 쓴다. 파일 입력에 `capture="environment"`(1108행)가 있어 Android 등에서는 **갤러리 대신 카메라가 바로 열려** 이미 찍은 GPS 사진을 고르기 어렵다. |
| P-C 지도에서 기존 기록에 덧붙이기 | 핀 → `사진 크게 보기` → 라이트박스 `수정` → 기록 수정 대화상자 → **스크롤**(`사진 추가` 파일 칸이 y=908px/900px, 모바일 1,079px/758px) → 파일 선택 → `저장` (6 + 스크롤) | 동작은 한다(측정: PUT 1 + presign 1 + complete 1, 사진 GPS도 저장). 그러나 기록을 바꾸지 않았는데도 **PUT을 매번 보낸다**, 업로드 진행 표시 없음, 맨 아래 브라우저 기본 파일 칸이라 "사진 추가 버튼"으로 보이지 않음, EXIF·형식 검사 없음(이미지가 아니면 조용히 버림, 하나라도 15MB를 넘으면 전체 거부). |
| P-D 사진 없는 기록 | 핀 → 선택 바 `이 기록 수정` → P-C와 같음 (5 + 스크롤) | |
| P-E 목록 표의 기존 기록 | `기록 목록·편집` → 표 `수정` → 레거시 편집기 → 스크롤 → 파일 → `기록 수정` (5 + 스크롤) | 같은 `capture` 문제 |
| P-F 통합 달력 딥링크 | 달력에서 여행 기록 열기 → 설정 2단계 + 레거시 편집기 | 사진은 편집기의 `사진 업로드`로만 |
| P-G 여행 정보 수정 / 4단계 | `여행 정보 수정` → 탭 `사진 · GPS` 또는 4단계 `사진 · GPS 더 추가` → P-B와 같은 화면 | 탭 이름이 "사진"이라 사용자를 P-B로 이끈다 |
| P-H 사진 지도(`TravelMyPhotosWorkspace`) | 통합 모드에서는 보기 전용(업로드 진입점 없음, 2819행 안내문) | 이번 범위 밖 |

### 1.3 결론 — "사진 추가 버튼이 없다"의 원인

1. **사용자가 사진을 넣으러 간 곳(설정 2단계 `장소·사진 목록`, 탭 이름 `사진 · GPS`)에는 정말로 사진 버튼이 없다.** 화면 문구는 "사진을 올리면…"이라고 하면서, 유일하게 보이는 시작 방법은 "지도를 누르면 임시 핀 → 한 번 더 누르면 모달"이다. 사용자 표현("지도를 누르고 그 상태로 사진을 추가하도록 유도된다")과 정확히 일치한다.
2. **그 화면의 `새 기록` 버튼은 라이트 테마에서 글자가 보이지 않는다.** TravelMemoryPanel 856행이 패널 머리 버튼 묶음에 전역 클래스 `topbar__actions`를 빌려 썼고, 전역 규칙 `html body .app-shell .main-shell--standalone .topbar__actions .button { background: transparent !important; color: var(--dash-muted) !important }`(style.css 34194행)가 `button--primary`의 흰 `-webkit-text-fill-color`와 겹쳐 흰 글자·투명 배경이 된다. 다크 테마에서는 보인다.
3. **지도 헤더의 통합 버튼은 '사진 추가'로 인식되지 않는다.** 이름이 `＋ 사진·GPX·장소 추가`이고, 눌러도 첫 화면은 지도·위치 찍기·장소 검색 중심이라 "여행에 사진을 추가하는 곳"이라기보다 "여러 종류를 가져오는 곳"으로 보인다. 버튼 자체는 데스크톱(y=200)·모바일(y=379, 전체 폭) 모두 첫 화면에 보이므로 **숨김 문제는 아니다**.
4. **기존 기록에 사진을 붙이는 경로가 숨어 있다.** 지도에서는 `사진 크게 보기 → 수정 → 스크롤`해야 하고, 통합 가져오기는 기존 기록에 붙일 수 없어 같은 장소 기록을 중복으로 만든다.

캡처(작업자 로컬 임시 폴더 `photo/`, 저장소 미포함): `C1-map-header-{d,m}`(+`-dark`), `C2-legacy-list-{d,m}`, `C8-legacy-new-record-{d,m}`(+`-dark`), `C8b-legacy-pending-pin-{d,m}`, `C2c/C2d-legacy-editor-*`, `C3-legacy-edit-existing-*`, `C4a-lightbox-*`, `C4b/C4c/C4d-record-edit-*`, `C6a-import-empty-*`(+`-dark`), `C6b-import-review-*`, `C7a-trip-info-*`, `C7b-summary-*`, `C9-import-mobile-first-view-m`.

### 1.4 관련 코드 위치 (작성 시점 행번호)

| 파일 | 위치 | 내용 |
|---|---|---|
| `TravelMyMapWorkspace.vue` | 41 | emits `open-import`, `open-record-editor` 등 |
| | 472~485 | `handleEditPhoto` — MEMORY 사진이면 `open-record-editor` |
| | 983~991 | `selectedSinglePlanId`, `choosePlan` |
| | 1530~1545 | 헤더 버튼 그룹 |
| | 1620~1650 | 사진 지도 패널, 선택 바 |
| | 1726, 1923 | `TravelPhotoLightbox` 두 개(`show-edit-action`, `@edit-photo`) |
| | 1943~1958 | 헤더 CSS(모바일 2열 grid, `.travel-my-map-actions__main`이 2칸) |
| `TravelWorkspace.vue` | 39~43 | `importDialog`, `recordEditor`, `mapReloadKey`, `hubRefreshKey`, `importNotice` |
| | 301~345 | `openImport`, `handleImportSaved`, `openRecordEditor`, `handleRecordEdited` |
| | 543~583 | `TravelHubWorkspace`·`TravelImportDialog`·`TravelRecordEditDialog` 마운트 |
| `TravelHubWorkspace.vue` | 109·112~120 | `externalRefreshKey` prop, emits(`request-open-import` 포함) |
| | 887~890 | `setFeedback` / 2178~2179 피드백 표시 |
| | 2278~2290, 2802, 2821 | `TravelMemoryPanel` 3곳(`@open-import`) |
| `TravelMemoryPanel.vue` | 55 | emits |
| | 851~860 | 기록 지도 머리(856 `topbar__actions`, 858 `새 기록`) |
| | 885~899 | 지도(`enable-pick-location`, 힌트 문구) |
| | 1007~1008 | 표 행 `수정`/`삭제` |
| | 1102~1109 | 편집기 `여러 장 한 번에 가져오기`, `사진 업로드`(`capture="environment"`) |
| | 끝 `<style scoped>` | 일차 탭 활성 스타일 |
| `TravelImportDialog.vue` | 12~17 | props `open`, `plans`, `planId` / emits |
| | 43~45 | 열릴 때 `imp.reset` |
| | 276~314 | 드롭 영역·도구·검색 |
| | 353~360 | 푸터 |
| `useTravelImport.js` | 22~28 | `liveApi`(`uploadMemoryPhotos(id, files, onProgress)` 이미 3번째 인자 지원) |
| | 161~191 | `reset` |
| | 220~350 | `addFiles`(이번 배치만 묶음) |
| | 477~493 | `setTargetPlan` |
| `travelImport.js` | 40~63 | `classifyImportFile` |
| | 188~ | `groupPhotos` |
| | 471~484 | `validateDraft` |
| | 485~520 | `summarizeDrafts` |
| | 611~620 | `formatImportNotice` |
| | 623~705 | `runImportSave` |
| `TravelRecordEditDialog.vue` | 159~170 | `onPhotosPicked` |
| | 210~239 | `save`(항상 PUT) |
| | 322~340 | 사진 구역(맨 아래) |
| `api.js`(열람만) | 1375~, 1544~1590 | 업로드: 준비(썸네일·GPS) → presign → PUT(동시 5) → complete. 15MB 초과 1장이라도 있으면 전체 거부(`travel-media-too-large`). `options.onProgress({ phase: 'preparing'|'uploading'|'finalizing', current, total })` |

---

## 2. 문제점 (우선순위)

### P0 — 사용자 지적의 핵심
1. 사진을 넣으려는 화면(설정 2단계)에 사진 버튼이 없고, 안내가 지도 클릭을 유도한다.
2. 같은 화면의 `새 기록` 버튼이 라이트 테마에서 보이지 않는다(전역 클래스 충돌).
3. 지도 화면에 `사진 추가`라는 명시적 버튼이 없다. 통합 버튼은 사진 전용 흐름으로 인식되지 않는다.
4. 여행 단위로 사진을 넣으면 기존 기록 근처 사진도 **새 기록으로 중복 생성**된다(기존 기록에 붙이기 불가).
5. 기존 기록에 사진을 붙이는 입력이 대화상자 맨 아래 브라우저 기본 파일 칸이라 찾기 어렵다(데스크톱·모바일 모두 첫 화면 밖).

### P1 — 품질
6. 레거시 편집기 `capture="environment"` → 모바일에서 갤러리 선택이 막힐 수 있다.
7. 기록 수정 대화상자: 바꾸지 않은 기록도 PUT, 업로드 진행 표시 없음, 파일 형식·크기 안내가 거칠다(하나라도 크면 전체 중단, 비이미지는 조용히 무시), 손상 이미지 사전 검사 없음, 기록에 위치가 없을 때 사진 GPS를 쓰지 않음.
8. 가져오기 대화상자 저장 중 사진 업로드 진행률이 항목에 보이지 않는다(`runImportSave`가 `onProgress`를 넘기지 않음).
9. 설정 화면에서 가져오기·기록 수정을 저장하면 알림이 지도 화면(`importNotice`)에만 가서 설정 화면에서는 결과 문구가 보이지 않는다.
10. 설정 2단계 일차 탭의 활성 탭(`전체 일정`) 글자가 라이트 테마에서 어두운 배경 위 어두운 글자로 거의 보이지 않는다(`C8-legacy-new-record-d`).

---

## 3. 목표 설계

### 3.1 원칙

1. **`사진 추가`는 누르면 바로 사진 고르는 창이 뜬다.** 버튼 → (갤러리/카메라/파일) → 고른 사진을 확인하는 대화상자 → `저장`. 지도를 먼저 누를 필요가 없다.
2. **어디서 눌렀는지가 대상이다.** 여행 단위에서 누르면 그 여행에(자동 묶음 + 가까운 기존 기록에 붙이기 제안), 특정 기록에서 누르면 그 기록에(묶지 않고 전부 첨부).
3. **기존 컴포넌트 재사용.** 여행 단위 = `TravelImportDialog`의 `photos` 모드, 기록 단위 = `TravelRecordEditDialog`의 사진 구역. 새 다이얼로그 컴포넌트는 만들지 않는다(근거 3.6절).
4. **한 장/여러 장 같은 흐름.** 모든 입력은 `<input type="file" accept="image/*" multiple>`(`capture` 없음), 데스크톱은 끌어놓기도 된다.
5. **실패해도 중복 없이 이어서.** 기존 `runImportSave`의 진행 기록(`progress`)을 그대로 쓰고, 기존 기록에 붙이는 항목은 기록을 만들지 않는다.

### 3.2 진입점 위치 (결정)

| 위치 | 버튼(접근성 이름) | 대상 | 여는 것 |
|---|---|---|---|
| 여행 지도 헤더 `기록` 그룹 맨 앞 | **`사진 추가`**(주 버튼, `travel-my-map-actions__main` 클래스를 이 버튼으로 옮김) | 지도에서 선택한 여행(`selectedSinglePlanId`). 여행을 고르지 않았으면 대화상자에서 날짜로 추천/선택 | 파일 선택 창 → `TravelImportDialog` mode `photos`(제목 `사진 추가`) |
| 여행 지도 `사진 지도` 패널에 사진 파일을 끌어 놓기(데스크톱) | — (놓는 동안 `사진을 놓으면 ‘{여행}’에 추가합니다.` 표시) | 같음 | `TravelImportDialog` mode `photos`(GPX가 섞이면 mode `all`) |
| 기존 헤더 버튼 | `＋ 사진·GPX·장소 추가`(이름 유지, **보조 버튼**으로 강등) | 같음 | `TravelImportDialog` mode `all`(현행 그대로) |
| 선택 바(사진 없는 기록 핀) | 기존 `이 기록 수정` 옆 **`이 기록에 사진 추가`** | 그 기록 | 파일 선택 창 → `TravelRecordEditDialog`(새 사진이 미리 담긴 상태) |
| 사진 라이트박스(MEMORY 사진) | 기존 `수정` 옆 **`이 기록에 사진 추가`** | 그 사진의 기록 | 같음 |
| 기록 수정 대화상자 | 사진 구역 머리의 **`사진 추가`** 버튼(사진 구역을 제목·날짜 바로 아래로 이동) | 그 기록 | 파일 선택 창(같은 대화상자 안에 추가) |
| 설정 2단계 기록 지도 머리 | **`사진 추가`**(주) + `새 기록`(보조, 보이게 수정) | 작업할 여행 | 파일 선택 창 → `TravelImportDialog` mode `photos` |
| 설정 2단계 기록 표 행 | `사진 추가`(접근성 이름 `{제목}에 사진 추가`) | 그 기록 | 파일 선택 창 → `TravelRecordEditDialog` |
| 레거시 편집기(새 기록·수정·달력 딥링크) | 기존 `사진 업로드` 유지(`capture` 제거 + 안내 한 줄) | 그 편집 중인 기록 | 현행 |

### 3.3 사진 처리 규칙 표

용어: **여행 단위** = 헤더/설정 화면 `사진 추가`·끌어놓기(가져오기 `photos` 모드), **기록 단위** = 특정 기록에서 시작(기록 수정 대화상자). "묶음" = `groupPhotos` 규칙(같은 날짜 + 묶음 중심 250m 이내 + 직전 사진과 120분 이내, GPS 없는 사진은 같은 날 30분 이내 묶음에 합류 — 기존 그대로).

| # | GPS | 날짜(EXIF) | 대상 | 한 장 | 여러 장 |
|---|---|---|---|---|---|
| R1 | 있음 | 있음 | 여행 단위 | 기존 기록 매칭(같은 날짜 + 기록 좌표와 250m 이내 + 기록·사진 시각이 모두 있으면 120분 이내) → **그 기록에 붙이기를 기본 선택**(문구로 표시, `새 기록으로 저장`으로 바꿀 수 있음). 매칭 없으면 새 기록 1건: 날짜·시간·좌표 = EXIF, 제목 = 역지오코딩 장소명 → 지역 → 파일명 | 먼저 묶음을 만든 뒤 **묶음마다** 한 장과 같은 매칭/새 기록. 묶음 하나에 사진 최대 50장(초과 시 분할, 기존) |
| R2 | 없음 | 날짜·시각 있음 | 여행 단위 | 기존 기록 매칭(같은 날짜 + 기록에 시각이 있고 30분 이내) → 붙이기 기본 선택. 아니면 새 기록: 날짜 EXIF, **위치 없음** 칩 + `지도에서 위치 지정` 버튼. 위치 없이 저장 허용(문구 `위치가 없으면 지도에는 표시되지 않습니다.`) | 같은 배치의 GPS 묶음과 같은 날 30분 이내면 그 묶음에 합류(좌표 상속). 아니면 한 장과 같음 |
| R3 | 없음 | 없음(스크린샷 등) | 여행 단위 | 새 기록: 날짜 = 대상 여행 시작일(없으면 오늘) + **날짜 추정** 칩, `날짜 확인 필요` 그룹, 위치 없음 칩. 매칭하지 않음. 저장 허용(현행) | 날짜·GPS 없는 사진끼리 하나의 묶음(현행) |
| R4 | 있음 | 상관없음 | 기록 단위 | **묶지 않고 그 기록에 첨부.** 사진 GPS는 사진에 저장된다(서버 `gpsLatitude/Longitude`, 지도 사진 핀은 사진 위치에 표시). 기록에 위치가 없으면 `첫 사진 위치를 기록 위치로 사용` 체크(기본 켬 → 지도에 핀 표시, 저장 시 PUT). 기록 위치와 250m 넘게 떨어진 사진은 `기록 위치와 약 {거리} 떨어져 있습니다. 지도에는 사진 위치에 표시됩니다.`(저장 허용) | 같음(모두 한 기록에 첨부, 한 번에 최대 50장) |
| R5 | 없음 | 상관없음 | 기록 단위 | 첨부. 지도에는 기록 위치에 표시(서버가 기록 좌표로 대체). 기록 위치도 없으면 `위치가 없어 지도에는 표시되지 않습니다.` | 같음 |
| R6 | — | 사진 날짜 ≠ 기록 날짜 | 기록 단위 | 기록 날짜는 바꾸지 않는다. 안내만: `촬영일 {MM-DD} · 기록 날짜 {MM-DD}` | 같음 |
| R7 | — | — | 여행 단위, 새 여행 선택 | 기존 기록이 없으므로 매칭 없음(현행 새 여행 흐름) | 같음 |

공통 형식 규칙(`classifyImportFile(file, { photosOnly: true })`):
- 허용: 확장자 `jpg/jpeg/png/webp/gif/bmp` 또는 MIME `image/(jpeg|png|webp|gif|bmp)`. 한 장 15MB 이하.
- HEIC/HEIF: `HEIC 사진은 아직 지원하지 않습니다. JPG로 저장한 뒤 다시 올려 주세요.`(현행 문구). 참고: iOS 사진 앱에서 `accept="image/*"`로 고르면 보통 JPEG로 변환되어 들어온다. 이때 iOS 설정에 따라 **위치가 빠질 수 있으므로** R2/R3 규칙이 그대로 적용된다.
- 15MB 초과: `사진은 한 장당 15MB 이하만 올릴 수 있습니다.` — **그 파일만 거부**하고 나머지는 진행.
- 사진 모드에서 GPX·기타: `사진(JPG·PNG·WEBP·GIF·BMP)만 추가할 수 있습니다. GPX나 장소는 ‘장소·GPX도 추가’에서 넣어 주세요.`
- 디코딩 실패(손상): `사진 파일을 읽을 수 없습니다. 손상되었거나 지원하지 않는 형식입니다.`(입력 단계에서 거부 — 기록을 만들기 전에 걸러 고아 기록 방지, 현행 `canDecodeImage`).
- 한 번에 최대 200개 파일(가져오기, 현행), 기록 단위는 한 번 저장에 새 사진 50장.

### 3.4 와이어프레임

**여행 지도 헤더 — 데스크톱 1440x900**
```
TRAVEL ATLAS                 여행 [새 여행 만들기][여행 정보 수정] | 기록 [사진 추가(주)][＋ 사진·GPX·장소 추가][기록 목록·편집] | 공유 [지도 공유]
내 여행 지도                  (폭이 모자라면 그룹 단위로 줄바꿈 — 겹침 금지)
여행 선택 [가을 교토 여행 ▾]
(알림, role=status) 기록 1건 · 사진 3장을 ‘가을 교토 여행’에 저장했습니다. 그중 사진 1장은 기존 기록 1건에 더했습니다. [알림 닫기]
┌ 사진 지도 ─────────────────────────────────────────── [클러스터 보기][핀 보기] ┐
│ (사진 파일을 끌어 오는 동안만) ┌───────────────────────────────┐            │
│                               │ 사진을 놓으면 ‘가을 교토 여행’에 추가합니다. │  ← pointer-events:none, role=status
│                               └───────────────────────────────┘            │
│ (사진 없는 핀 선택 시) 선택: 점심 · 2026-10-02 [이 기록 수정][이 기록에 사진 추가]   │
```

**여행 지도 헤더 — 모바일 390x844 (기존 2열 grid 규칙 유지)**
```
[새 여행 만들기][여행 정보 수정]
[사진 추가 ─────── 2칸, 주 버튼 ──]
[＋ 사진·GPX·장소 추가][기록 목록·편집]
[지도 공유 ─────── 2칸 ──────────]   ← 홀수 개가 되므로 마지막 버튼을 2칸으로
여행 선택 [가을 교토 여행 ▾]
```
`사진 추가` 버튼 아래끝이 첫 화면(844px) 안에 있어야 한다(현재 통합 버튼 y=379~423).

**사진 추가 대화상자(가져오기 photos 모드) — 데스크톱**
```
┌ 사진 추가 ─────────────────────────────────────────────── [닫기] ┐
│ 저장할 여행 [가을 교토 여행 ▾]                                            │
├──────────────────────────────┬──────────────────────────────────┤
│ 지도(사진 핀 끌어서 위치 수정)   │ ┌ 사진을 끌어 놓거나 [사진 선택] ─────────┐ │
│                              │ │ JPG·PNG·WEBP·GIF·BMP, 한 장 15MB 이하 · 여러 장 가능 │ │
│                              │ └───────────────────────────────┘ │
│                              │ [장소·GPX도 추가]  (모드 전환, ghost)          │
│                              │ 사진 정보 읽는 중 2/3 (role=status)            │
│                              │ ── 2026-10-01 · 1일차 ──                     │
│                              │ ☑ [▣] 저장 위치 [기존 기록: 기요미즈데라 · 10-01 ▾]   │
│                              │     기존 기록 ‘기요미즈데라’에 사진 1장을 더합니다.   │
│                              │     기록의 제목·날짜·위치는 바뀌지 않습니다.        │
│                              │     같은 날 약 10m 거리에서 찍어 자동으로 골랐습니다. │
│                              │ ── 2026-10-02 · 2일차 ──                     │
│                              │ ☑ [▣▣] 저장 위치 [새 기록으로 저장 ▾]            │
│                              │     제목 [후시미이나리] 날짜 [10-02] 시간 [10:05] 사진 2장 │
│                              │     📍 일본 · 교토 · 후시미이나리 (사진 위치) [지도에서 보기] ▸고급 │
│                              │ ── 날짜 확인 필요 ──                          │
│                              │ ☑ [▣] screenshot ⚠날짜 추정 ⚠위치 없음 [지도에서 위치 지정] │
│                              │ 추가하지 못한 파일: a.heic — HEIC 사진은… [지우기]  │
├──────────────────────────────┴──────────────────────────────────┤
│ 기록 2 · 사진 4 · 경로 0 · 기존 기록에 추가 1               [취소][저장] │
└───────────────────────────────────────────────────────────────┘
저장 중 항목 상태: `사진 준비 중 1/2` → `사진 올리는 중 2/2` → `마무리 중` → `저장됨` / `실패: …`
```
- photos 모드에서 숨기는 것: `지도에서 위치 찍기`, `현재 위치`, 장소 검색(입력·결과), `경로 직접 그리기`. 지도는 남긴다(위치 지정·핀 드래그·검토).
- `장소·GPX도 추가`를 누르면 같은 대화상자가 mode `all`로 바뀌고(초안 유지) 제목이 `여행에 추가`가 된다.

**모바일(≤760px, 기존 전체 화면 규칙)**
```
┌ 사진 추가                 [닫기] ┐
│ 저장할 여행 [가을 교토 여행 ▾]      │
│ ┌ 사진을 끌어 놓거나 [사진 선택] ┐  │ ← photos 모드: 목록 열(.travel-import-panel)이 먼저(order:-1)
│ └─────────────────────────┘  │
│ ── 10/01 · 1일차 ──              │
│ ☑ [▣] 저장 위치 [기존: 기요미즈… ▾]│
│ …                               │
│ ┌ 지도 (30vh, 최소 220px) ─────┐  │ ← 지도는 목록 아래. `지도에서 위치 지정/보기`가 스크롤해 줌
│ └─────────────────────────┘  │
├────────────────────────────────┤
│ 기록 2·사진 4·…      [취소][저장] │ ← 푸터 sticky(현행)
└────────────────────────────────┘
```

**기록 수정 대화상자(사진 구역 이동) — 데스크톱/모바일 공통 순서**
```
┌ 기록 수정 ───────────────────────── [닫기] ┐
│ 제목 [기요미즈데라__________________]         │
│ 날짜 [2026-10-01] 시간·선택 [10:00] 분류 [관광지] │
│ ┌ 사진 3장 ───────────────── [사진 추가] ┐  │ ← 이동(제목·날짜 바로 아래). 첫 화면 안
│ │ [▣ ×][▣ ×][▣ ×(새, 점선)]                │  │
│ │ IMG_0101.jpg · 📍 사진 위치 있음(기록과 약 40m) │  │
│ │ IMG_0102.jpg · 위치 없음 → 기록 위치에 표시     │  │
│ │ ☑ 첫 사진 위치를 기록 위치로 사용 (기록에 위치가 없을 때만) │
│ │ 추가하지 못한 파일: big.jpg — 사진은 한 장당 15MB… │  │
│ │ 사진 올리는 중 1/2 (role=status)             │  │
│ └────────────────────────────────────┘  │
│ 위치: 기요미즈데라 · 일본 · 교토 …              │
│ [지도 260px]                                │
│ ▸ 국가·지역·장소명 직접 수정                   │
│ 메모 [________] 5/500                       │
├──────────────────────────────────────────┤
│ [기록 삭제]                     [취소][저장]  │
└──────────────────────────────────────────┘
```

**설정 2단계 기록 지도 머리**
```
기록 지도                                       [2개 핀] [사진 추가(주)] [새 기록]
사진은 ‘사진 추가’로 올리세요(여러 장 가능). 사진 없이 장소만 남기려면 ‘새 기록’을 누르거나 지도를 눌러 임시 핀을 만든 뒤 한 번 더 누르세요.
표 행: … | 사진 | [수정][사진 추가][삭제]
```

### 3.5 과업 단계 수 비교 (클릭·입력 수 = 파일 선택 창에서 고르기 1회 포함)

| 과업 | 현재 | 목표 |
|---|---|---|
| T1 선택한 여행에 GPS 사진 3장 추가(그중 1장은 기존 기록 근처) — 지도에서 | 통합 버튼 3단계지만 **사진 추가로 인식 안 됨** + 기존 기록 중복 1건 / 사용자 경로(P-B): 7 + 스크롤, 기록 1건에 3장 모두(첫 사진 EXIF만), 모바일은 카메라가 열릴 수 있음 | **3**: `사진 추가` → 사진 고르기 → `저장`. 결과: 새 기록(묶음) + 기존 기록에 1장 첨부, 중복 0 |
| T2 같은 과업을 `기록 목록·편집` 화면에서 | 7 + 스크롤(위와 같음) | **4**: `기록 목록·편집` → `사진 추가` → 고르기 → `저장` |
| T3 지도에서 사진 있는 기존 기록에 2장 덧붙이기 | 6 + 스크롤, 불필요한 PUT 1 | **5**: 핀 → `사진 크게 보기` → `이 기록에 사진 추가` → 고르기 → `저장`(PUT 0) |
| T4 사진 없는 기록에 사진 추가 | 5 + 스크롤 | **4**: 핀 → `이 기록에 사진 추가` → 고르기 → `저장` |
| T5 목록 표의 기존 기록에 사진 추가 | 5 + 스크롤 | **4**: `기록 목록·편집` → 행 `사진 추가` → 고르기 → `저장` |
| T6 사진 1장 | 3(통합 버튼) / 7(P-B) | **3** |
| T7 부분 실패 후 재시도 | 가져오기: 중복 0(현행 유지) / 기록 수정: 다시 저장 시 PUT 재전송 | 둘 다 **중복 0, 실패한 업로드만 재시도**, 바뀌지 않은 기록은 PUT 0 |

### 3.6 재사용 판단 근거

- **새 "사진 추가" 컴포넌트를 만들지 않는다.** `TravelImportDialog`에 이미 EXIF 읽기·묶음·역지오코딩·핀 드래그·날짜별 검토·부분 실패 재시도·이탈 가드·blob 해제·모바일 전체 화면이 있다. 사진 전용 화면을 따로 만들면 이 로직이 두 벌이 된다. 필요한 차이(도구 숨김, 문구, 기존 기록에 붙이기)는 `mode` prop과 초안 필드 2개로 충분하다.
- **기록 단위는 `TravelRecordEditDialog`.** 기록 하나에 붙이는 것은 "그 기록을 편집하는 행위"이고, 이미 사진 삭제·업로드·실패 후 재시도 문구가 있다. 가져오기 대화상자로 보내면 저장할 여행·묶음 같은 불필요한 개념이 끼어든다.
- **파일 선택 창을 먼저 여는 이유.** 대화상자를 먼저 열고 그 안에서 파일 창을 자동으로 띄우면 비동기 컴포넌트 로딩 뒤라 iOS에서 막힌다(사용자 활성화 만료). 그래서 각 화면이 숨은 `<input type="file">`을 하나 갖고 버튼 클릭 처리기에서 동기로 `click()`한 뒤, 고른 파일을 대화상자에 `initialFiles`로 넘긴다. 사용자가 창을 취소하면 아무 일도 일어나지 않는다. 끌어놓기를 원하는 데스크톱 사용자는 지도 패널에 바로 놓거나, 대화상자 안 드롭 영역을 쓴다.
- **레거시 편집기의 `사진 업로드`는 유지.** 통합 달력 딥링크·L4 테스트 계약이고, 장소 기록 작성 중 사진을 붙이는 용도로 여전히 쓰인다. `capture`만 제거한다.

---

## 4. 상태/데이터 설계

### 4.1 순수 함수 — `frontend/src/lib/travelImport.js` (추가·수정)

브라우저 API 금지(node:test로 검증). 기존 함수의 **기본 동작은 그대로** 두고 옵션·필드만 더한다.

| 함수 | 변경 | 규칙·예 |
|---|---|---|
| `classifyImportFile(file, { photosOnly = false } = {})` | 옵션 추가 | `photosOnly`일 때 GPX와 기타 형식은 `{ kind:'rejected', reason: PHOTO_ONLY_REJECT }`. HEIC·15MB 문구는 동일. 옵션 없으면 현행과 동일 결과 |
| `export const PHOTO_ONLY_REJECT` | 신규 | `'사진(JPG·PNG·WEBP·GIF·BMP)만 추가할 수 있습니다. GPX나 장소는 ‘장소·GPX도 추가’에서 넣어 주세요.'` |
| `baseDraft`(내부) | 필드 추가 | 모든 초안에 `attachRecordId: ''`, `attachSource: ''`(`'suggested'|'manual'|''`), `attachTouched: false`, `attachReason: ''`, `upload: null`(`{ phase, current, total }`) |
| `normalizeClock(value)` | 신규 | `'10:00:00'`→`'10:00'`, `'9:5'`→`'09:05'`, 빈값/잘못된 값→`''` |
| `minutesApart(a, b)` | 신규 | `'HH:mm'` 둘 → 절댓값 분, 하나라도 비면 `null` |
| `suggestAttachRecord(draft, records, limits = IMPORT_LIMITS)` | 신규 | 입력 `draft`(kind `photos`, `date`, `latitude/longitude`, `photos[].time`), `records`=`[{ id, title, memoryDate, memoryTime, latitude, longitude }]`. 출력 `{ recordId, distanceMeters, minutes, reason }` 또는 `null`. ① `draft.kind!=='photos'`이거나 `!draft.date`면 null. ② 후보 = `memoryDate === draft.date`. ③ 초안에 좌표가 있으면: 후보도 좌표가 있어야 하고 `haversineKm*1000 ≤ groupDistanceMeters(250)`; 기록 시각과 초안 사진 시각들이 모두 있으면 **사진 시각 중 가장 가까운 것**과 기록 시각 차 ≤ `groupGapMinutes(120)`(어느 쪽이든 시각이 없으면 시각 조건 생략). `reason:'nearby'`. ④ 초안에 좌표가 없으면: 기록 시각과 사진 시각이 둘 다 있어야 하고 차 ≤ `attachGapMinutes(30)`, `reason:'time'`. ⑤ 여러 후보면 거리(없으면 0) → 시각 차 → id 순으로 첫 번째 |
| `describeAttachReason(suggestion)` | 신규 | `nearby` → `같은 날 약 {거리} 거리에서 찍어 자동으로 골랐습니다.`(거리 < 1000m는 `{n}m`, 이상은 `{x.x}km`), `time` → `같은 날 {분}분 차이로 찍어 자동으로 골랐습니다.` |
| `buildAttachOptions(draft, records)` | 신규 | `[{ value:'', label:'새 기록으로 저장' }, ...]`. 순서: 제안 기록(있으면) → 같은 날짜 기록(시각순) → 나머지(날짜 내림차순). label = `기존 기록: {title||'제목 없음'} · {MM-DD}`(제목 40자 넘으면 `…`) |
| `validateDraft(draft)` | 분기 추가 | `kind==='photos' && attachRecordId`이면 `[]`(제목·날짜·메모 검사 생략 — 기록을 만들지 않음) |
| `summarizeDrafts(drafts)` | 필드 추가 | 포함된 photos 초안 중 `attachRecordId`가 있는 것은 `records`·`needsLocation`에 세지 않고 `attached += 1`, `attachedPhotos += 사진 수`. 저장 완료분은 `savedAttached`(서로 다른 recordId 수), `savedAttachedPhotos`. `photos`·`savedPhotos`는 **붙이는 사진도 포함**(총 사진 수). 붙이는 초안이 없으면 기존 결과와 완전히 같아야 한다(기존 단위 테스트 통과) |
| `formatImportNotice({ records, photos, routes, planName, createdPlan, attachedRecords = 0, attachedPhotos = 0 })` | 인자 추가 | 붙인 것이 없으면 현행 문구 그대로. `records===0 && routes===0 && attachedPhotos===photos && attachedRecords>0` → `사진 {photos}장을 ‘{planName}’의 기존 기록 {attachedRecords}건에 더했습니다.` 그 외에는 현행 문구 뒤에 ` 그중 사진 {attachedPhotos}장은 기존 기록 {attachedRecords}건에 더했습니다.` 예: `기록 1건 · 사진 3장을 ‘가을 교토 여행’에 저장했습니다. 그중 사진 1장은 기존 기록 1건에 더했습니다.` |
| `formatRecordPhotoNotice({ addedPhotos, title, recordChanged })` | 신규 | `recordChanged && addedPhotos` → `기록을 수정했습니다. 사진 {n}장을 추가했습니다.`; `recordChanged`만 → `기록을 수정했습니다.`(E1 계약); `addedPhotos`만 → `사진 {n}장을 ‘{title}’ 기록에 추가했습니다.`; 둘 다 아님 → `기록을 수정했습니다.` |
| `describePhotoAgainstRecord(photo, record, limits)` | 신규 | `photo={ latitude, longitude, date }`, `record={ latitude, longitude, memoryDate }` → `{ hasGps, distanceMeters (둘 다 좌표 있을 때만, 아니면 null), far: distanceMeters > 250, dateDiffers: Boolean(photo.date && record.memoryDate && photo.date !== record.memoryDate) }` |
| `runImportSave(...)` | 분기 추가 | 4.4절 |

### 4.2 composable — `frontend/src/lib/useTravelImport.js` (추가·수정)

새 import: `fetchTravelPlan`(api.js 기존 export). `canDecodeImage`를 **export**로 바꿔 기록 수정 대화상자도 쓴다.

```js
const mode = ref('all')                       // 'all' | 'photos'
const existingRecords = ref([])               // 대상 여행의 기록 [{ id:String, title, memoryDate, memoryTime:'HH:mm'|'', latitude, longitude, photoCount }]
const recordsState = ref('idle')              // 'idle' | 'loading' | 'ready' | 'error'
let recordsToken = 0
```

| 함수 | 동작 |
|---|---|
| `reset({ planId, plans, mode: nextMode = 'all', initialFiles = [] })` | 기존 초기화 + `mode.value = nextMode === 'photos' ? 'photos' : 'all'`, `existingRecords=[]`, `recordsState='idle'`, `recordsToken++`. `target.planId`가 있으면 `loadRecords(target.planId)`. `initialFiles.length`면 `return addFiles(initialFiles)`(Promise 반환) |
| `loadRecords(planId)`(내부) | `const token = ++recordsToken; recordsState='loading'` → `fetchTravelPlan(planId)` → `token !== recordsToken`이면 버림 → `existingRecords = memoryRecords.map(...)`(photoCount는 `mediaItems`에서 `recordType==='MEMORY' && recordId` 일치 수, memoryTime은 `normalizeClock`) → `recordsState='ready'` → `applyAttachSuggestions()`. 실패 → `recordsState='error'`, 초안은 모두 새 기록(대화상자에 `기존 기록을 불러오지 못해 모두 새 기록으로 저장합니다.`) |
| `applyAttachSuggestions()`(내부) | `kind==='photos' && status!=='saved' && !attachTouched && !progress?.memoryId`인 초안마다 `s = core.suggestAttachRecord(draft, existingRecords.value)` → `attachRecordId = s ? String(s.recordId) : ''`, `attachSource = s ? 'suggested' : ''`, `attachReason = s ? core.describeAttachReason(s) : ''` |
| `addFiles(fileList)` | `classifyImportFile(file, { photosOnly: mode.value === 'photos' })`. 사진 초안을 만든 직후(역지오코딩 전) `applyAttachSuggestions()`. 역지오코딩 루프에서 `live.attachRecordId`가 있는 초안은 건너뛴다(요청 절약) |
| `setAttachRecord(id, recordId)` | 초안 없거나 `status==='saved'`거나 `progress?.memoryId`가 있으면 무시(새 기록이 이미 만들어진 뒤 바꾸면 고아 기록이 됨). `attachTouched=true`, `attachRecordId=String(recordId||'')`, `attachSource = recordId ? 'manual' : ''`, `attachReason=''`. 새 기록으로 바꿨고 좌표가 있고 `!placeName && !titleEdited`면 `geocodeInto(draft)` |
| `attachOptionsFor(draft)` | `core.buildAttachOptions(draft, existingRecords.value)` (기존 기록이 없으면 빈 배열 → 항목에서 select 숨김) |
| `setMode(next)` | `mode.value = next`(초안 유지) |
| `setTargetPlan(value)` | 기존 동작 + 모든 photos 초안의 `attachRecordId=''`, `attachTouched=false`, `attachSource=''`, `attachReason=''`(다른 여행의 기록 id는 무효) → 기존 여행이면 `loadRecords(value)`, 새 여행이면 `existingRecords=[]` |
| `save()` | 반환 `counts`에 `attachedRecords: summary.savedAttached`, `attachedPhotos: summary.savedAttachedPhotos` 추가 |
| 반환 객체 | 기존 + `mode, existingRecords, recordsState, setAttachRecord, attachOptionsFor, setMode` |

### 4.3 컴포넌트 변경점

**TravelImportDialog.vue**
- props 추가: `mode: { type: String, default: 'all' }`, `initialFiles: { type: Array, default: () => [] }`.
- `watch(() => props.open, v => v && imp.reset({ planId: props.planId, plans: props.plans, mode: props.mode, initialFiles: props.initialFiles }), { immediate: true })`.
- `const photoMode = computed(() => imp.mode === 'photos')`, 제목 `photoMode ? '사진 추가' : '여행에 추가'`.
- photos 모드 템플릿: 드롭 영역 문구 `사진을 끌어 놓거나 <span>사진 선택</span>` / `JPG·PNG·WEBP·GIF·BMP, 한 장 15MB 이하 · 여러 장 가능 · 사진의 날짜·위치로 일정과 장소를 채웁니다.`; input `accept="image/*"`, `aria-label="사진 파일 선택"`(all 모드는 현행 `accept`·`aria-label="사진·GPX 파일 선택"` 그대로). `.travel-import-tools`, 검색 form·결과, 푸터 `경로 직접 그리기`는 `v-if="!photoMode"`. 드롭 영역 아래 `<button type="button" class="button button--ghost" @click="imp.setMode('all')">장소·GPX도 추가</button>`.
- `recordsState==='error'`면 `<p class="travel-import-hint" role="status">기존 기록을 불러오지 못해 모두 새 기록으로 저장합니다.</p>`.
- 그리드에 `:class="{ 'is-photos': photoMode }"`, 모바일 CSS: `.travel-import-grid.is-photos .travel-import-panel { order: -1; } .travel-import-grid.is-photos .travel-import-mapbox { height: 30vh; min-height: 220px; }`.
- 요약 문구: 현행 `summaryText` 뒤에 `s.attached`가 있으면 ` · 기존 기록에 추가 ${s.attached}`.
- `emitSaved`에 `attachedRecords`, `attachedPhotos` 추가(결과 counts 또는 summary 저장분).
- DraftItem에 `:attach-options="imp.attachOptionsFor(draft)"`, `@attach="imp.setAttachRecord(draft.id, $event)"`.

**TravelImportDraftItem.vue**
- props `attachOptions: { type: Array, default: () => [] }`, emit `attach`.
- `draft.kind==='photos' && attachOptions.length`일 때 제목 위에 `<label class="travel-import-item__field"><span>저장 위치</span><select aria-label="저장 위치" :value="draft.attachRecordId" :disabled="locked || Boolean(draft.progress?.memoryId)" @change="emit('attach', $event.target.value)">`.
- `draft.attachRecordId`가 있으면 제목·날짜·시간 행, 경고 칩, 위치 줄, `고급`을 **렌더하지 않고** 대신 `<p class="travel-import-item__attach">기존 기록 ‘{기록 제목}’에 사진 {n}장을 더합니다. 기록의 제목·날짜·위치는 바뀌지 않습니다.</p>` + `attachReason`이 있으면 `<small>{attachReason}</small>`. 기록 제목은 `attachOptions`에서 value로 찾는다(label에서 `기존 기록: ` 접두사·날짜 제거하지 말고, 옵션에 `title` 필드를 함께 넣어 두고 그것을 쓴다).
- `statusText`: `status==='saving' && draft.upload`면 phase별 `사진 준비 중 {c}/{t}` / `사진 올리는 중 {c}/{t}` / `마무리 중`.

**TravelRecordEditDialog.vue**
- prop `initialFiles: { type: Array, default: () => [] }`. emit `saved` payload를 `{ planId, addedPhotos, title, recordChanged }`로 확장(기존 `planId` 유지).
- 사진 구역을 날짜 행(`.travel-edit-row`) 바로 뒤로 옮긴다. 머리: `<div class="travel-edit-photos__head"><h3>사진 {{ n }}장</h3><button type="button" class="button button--secondary" :disabled="busy" @click="openPicker">사진 추가</button></div>`, 숨은 input(`ref="pickerInput"`, `class="travel-edit-picker"`(시각적으로만 숨김 — `.travel-import-drop__input`과 같은 CSS), `type="file" accept="image/*" multiple tabindex="-1" aria-label="사진 파일 선택"`, `@change="onPhotosPicked"`). 기존 `<label><span>사진 추가</span><input type="file"…>`는 삭제.
- `stagePhotos(files)`(기존 `onPhotosPicked` 대체, `stageSequence`로 닫힘·재시작 시 중단): ① 새 사진 합계 50장 초과분 거부 `한 번에 최대 50장까지 추가할 수 있습니다.` ② `classifyImportFile(file, { photosOnly: true })` 거부는 `rejected` 목록(파일명 + 이유) ③ `canDecodeImage` 실패 → 거부 ④ `extractPhotoMetadata(file, { reverseGeocode: false })`(실패 null) ⑤ `createLocalImagePreview(file, { maxWidth: 160, maxHeight: 160 })` → `newPhotos.push({ id, file, previewUrl, latitude, longitude, date })`. 중단되면 만든 미리보기 revoke.
- `onPhotosPicked(event)`: `const files = Array.from(event.target.files || []); event.target.value = ''; stagePhotos(files)` (**Array.from을 value 초기화보다 먼저**).
- `load()` 성공 직후 `if (props.initialFiles.length && !initialStaged) { initialStaged = true; stagePhotos(props.initialFiles) }`.
- 새 사진마다 한 줄: `describePhotoAgainstRecord`로 `📍 사진 위치 있음` (+`기록과 약 {거리}`, `far`면 `기록 위치와 약 {거리} 떨어져 있습니다. 지도에는 사진 위치에 표시됩니다.`) / `위치 없음 → 기록 위치에 표시`(기록 위치도 없으면 `위치가 없어 지도에는 표시되지 않습니다.`) / `dateDiffers`면 `촬영일 {MM-DD} · 기록 날짜 {MM-DD}`.
- 기록에 위치가 없고 GPS 있는 새 사진이 생기면: `useFirstPhotoLocation = ref(true)` 체크박스 `첫 사진 위치를 기록 위치로 사용`을 보이고, 켜짐 → `applyPoint(첫 GPS 사진)`(지도에 핀, 지명 역지오코딩), 꺼짐 → 좌표·지명을 적용 전 값으로 복원.
- 업로드 진행: `uploadTravelMemoryMedia(id, files, '', { onProgress: p => Object.assign(uploadState, p) })`, `<p v-if="uploadState.phase" class="travel-edit-note" role="status">` phase별 문구(4.3 DraftItem과 같은 문구).
- `save()`: `if (formDirty.value && !recordSaved.value) { PUT; recordSaved = true; ... }` — **바뀌지 않았으면 PUT 하지 않는다.** 업로드 실패 문구는 현행 유지(`… 기록은 저장되었습니다. 다시 저장하면 사진만 올립니다.`; PUT을 안 했으면 `… 다시 저장하면 사진만 올립니다.`). 성공 → `emit('saved', { planId, addedPhotos: 올린 수, title: form.title.trim(), recordChanged: PUT 여부 })`.
- `requestClose`: 현행 + emit payload에 `addedPhotos: 0, recordChanged: recordSaved.value`. 닫힐 때(언마운트) 미리보기 전부 revoke(현행 `revokeNewPhotos` 유지).
- `canSave`에 `!staging`(EXIF 읽는 중) 조건 추가.

**TravelMyMapWorkspace.vue**
- emits 이름 그대로, payload 확장: `open-import` `{ planId, mode: 'photos', files }`, `open-record-editor` `{ planId, recordId, files }`.
- 헤더 `기록` 그룹: 맨 앞에 `<button class="button button--primary travel-my-map-actions__main" type="button" @click="pickPhotos({ kind: 'trip' }, $event)">사진 추가</button>`, 기존 `＋ 사진·GPX·장소 추가`는 `class="button button--secondary"`(`__main` 제거, 문구·동작 그대로).
- 루트 `.workspace-stack` 안에 숨은 input 하나: `<input ref="photoPickerInput" class="travel-photo-picker" type="file" accept="image/*" multiple tabindex="-1" aria-hidden="true" data-photo-picker="map" @change="onPhotosPicked">`(시각적으로만 숨김, `display:none` 금지).
- 스크립트:
  ```js
  const photoPickerInput = ref(null)
  let photoPickTarget = null
  function pickPhotos(target, event) {
    photoPickTarget = target
    event?.currentTarget?.focus?.()          // Safari는 클릭으로 버튼에 포커스를 주지 않는다 → 닫은 뒤 포커스 복귀용
    photoPickerInput.value?.click()           // 반드시 클릭 처리기 안에서 동기로
  }
  function onPhotosPicked(event) {
    const files = Array.from(event.target.files || [])
    event.target.value = ''
    const target = photoPickTarget
    photoPickTarget = null
    if (!files.length || !target) return
    if (target.kind === 'record') {
      pauseJourneyPlayback(); pauseJourneyRoutePlayback(); lightboxPhoto.value = null
      emit('open-record-editor', { planId: target.planId, recordId: target.recordId, files })
      return
    }
    emit('open-import', { planId: selectedSinglePlanId.value, mode: 'photos', files })
  }
  ```
- 선택 바(1650행): `이 기록 수정` 뒤에 `<button class="button button--secondary" type="button" @click="pickPhotos({ kind: 'record', planId: selectedMarkerRecord.planId, recordId: selectedMarkerRecord.id }, $event)">이 기록에 사진 추가</button>`.
- 라이트박스 두 곳: `:show-add-photo-action="true"` `@add-photos="photo => pickPhotos({ kind: 'record', planId: photo.planId, recordId: photo.recordId })"`.
- 끌어놓기: `.travel-overview-map-panel` section에 `@dragover="onMapDragOver" @dragleave="onMapDragLeave" @drop="onMapDrop"`. `dataTransfer.types`에 `'Files'`가 있을 때만 `preventDefault`(지도 내부 드래그·텍스트 드래그 방해 금지). 놓으면 `files`에 `.gpx`가 있으면 mode `all`, 아니면 `photos`로 `emit('open-import', …)`. 표시: `<p v-if="photoDropActive" class="travel-map-drop-hint" role="status">사진을 놓으면 ‘{{ dropPlanName }}’에 추가합니다.</p>`(`const dropPlanName = computed(() => props.plans.find(p => String(p.id) === selectedSinglePlanId.value)?.name || '여행')`, 절대 위치, `pointer-events:none`, `z-index`는 지도 도구 위). `dragleave`는 `event.currentTarget.contains(event.relatedTarget)`이면 무시.
- 모바일 CSS(1947~1958행 패턴 그대로, 두 규칙 세트 모두): 버튼이 5개(홀수)가 되므로 그룹 `공유`의 버튼을 `grid-column: 1 / -1`. 선택자 예: `.workspace-stack .travel-my-map-actions .travel-my-map-actions__group[aria-label="공유"] .button { grid-column: 1 / -1; }`와 `:global(:root[data-layout-mode='mobile'] …)` 판.

**TravelPhotoLightbox.vue**(최소)
- prop `showAddPhotoAction: { type: Boolean, default: false }`, emits에 `'add-photos'`.
- `수정` 버튼 옆: `<button v-if="showAddPhotoAction && activePhoto?.recordId && String(activePhoto.recordType || '').toUpperCase() === 'MEMORY'" class="button button--secondary" type="button" @click="emit('add-photos', activePhoto)">이 기록에 사진 추가</button>`. 처리기는 동기 emit만 한다(부모가 같은 클릭 안에서 파일 창을 연다). `TravelPublicTripsWorkspace`(죽은 코드)는 prop 기본값 false라 영향 없음.

**TravelWorkspace.vue**
```js
import { markRaw } from 'vue'
const importDialog = reactive({ open: false, planId: '', mode: 'all', files: [] })
const recordEditor = reactive({ open: false, planId: '', recordId: '', files: [] })
const hubNotice = ref(null)                         // { token, message } — 설정 화면용
function showNotice(message) {
  importNotice.value = message
  if (primaryTab.value === 'setup') hubNotice.value = { token: Date.now(), message }
}
function openImport(payload = {}) {
  importDialog.planId = String(payload?.planId || '')
  importDialog.mode = payload?.mode === 'photos' ? 'photos' : 'all'
  importDialog.files = markRaw(Array.from(payload?.files || []))
  importDialog.open = true
}
function openRecordEditor({ planId, recordId, files } = {}) {
  if (!planId || !recordId) return
  recordEditor.planId = String(planId); recordEditor.recordId = String(recordId)
  recordEditor.files = markRaw(Array.from(files || []))
  recordEditor.open = true
}
```
- `handleImportSaved`: `showNotice(formatImportNotice(result))`(나머지 현행). `handleRecordEdited(kind, payload)`: 삭제면 `기록을 삭제했습니다.`, 아니면 `formatRecordPhotoNotice(payload || {})`. 닫을 때 `files = []`.
- 템플릿: `TravelImportDialog`에 `:mode="importDialog.mode" :initial-files="importDialog.files"`, `@close`에서 `importDialog.open = false; importDialog.files = []`. `TravelRecordEditDialog`에 `:initial-files="recordEditor.files"`. `TravelHubWorkspace`에 `:external-notice="hubNotice"` `@request-open-record-editor="openRecordEditor"`.
- `openPhotoEditor`·`recordFocusRequest` watch는 **수정하지 않는다**.

**TravelHubWorkspace.vue**(연결만)
- prop `externalNotice: { type: Object, default: null }` → `watch(() => props.externalNotice?.token, token => { if (token) setFeedback(props.externalNotice.message) })`.
- emits에 `'request-open-record-editor'` 추가.
- `TravelMemoryPanel` 3곳(2278~2290, 2802, 2821) 모두:
  `@open-import="payload => emit('request-open-import', { ...(payload || {}), planId: selectedPlanId })"`
  `@open-record-photos="payload => emit('request-open-record-editor', { planId: selectedPlanId, recordId: payload.recordId, files: payload.files })"`
- 피드백 div(2178행)에 `role="status"` 추가.

**TravelMemoryPanel.vue**
- emits에 `'open-record-photos'` 추가. `open-import`는 이제 payload `{ mode: 'photos', files }`를 실을 수 있다(편집기의 `여러 장 한 번에 가져오기`는 payload 없이 현행).
- 856행 `class="topbar__actions"` → `class="travel-memory-map-panel__actions"`(scoped: `display:flex; flex-wrap:wrap; align-items:center; justify-content:flex-end; gap:8px;` 모바일 버튼 `min-height:44px`). 그 안: 핀 배지, `<button class="button button--primary" type="button" @click="pickPhotos(null, $event)">사진 추가</button>`, `<button class="button button--secondary" type="button" @click="openBlankEditor()">새 기록</button>`.
- 패널 루트에 숨은 input(`data-photo-picker="list"`, 나머지는 지도와 동일). `pickPhotos(recordId, event)` / `onPhotosPicked`: 대상이 기록이면 `emit('open-record-photos', { recordId, files })`, 아니면 `emit('open-import', { mode: 'photos', files })`.
- 표 행(1007~1008): `수정`과 `삭제` 사이 `<button class="button button--ghost" type="button" :aria-label="`${memory.title || '기록'}에 사진 추가`" @click="pickPhotos(memory.id, $event)">사진 추가</button>`.
- 문구: 854행 → `사진은 ‘사진 추가’로 올리세요(여러 장 가능). 사진 없이 장소만 남기려면 ‘새 기록’을 누르거나 지도를 눌러 임시 핀을 만든 뒤 한 번 더 누르세요.` 지도 `hint-text`(898행) → `지도를 누르면 임시 핀이 생기고, 그 핀을 다시 누르면 장소 기록을 쓸 수 있습니다. 사진은 위의 ‘사진 추가’를 쓰세요.`
- 편집기 파일 입력(1108행): `capture="environment"` 삭제. 아래에 `<small class="field__hint">여기서 고른 사진은 모두 이 기록 하나에 붙습니다. 장소별로 나누려면 ‘여러 장 한 번에 가져오기’를 쓰세요.</small>`(새 기록일 때만). label `사진 업로드`는 유지(L4 계약).
- 일차 탭 활성 글자(파일 끝 `<style scoped>`의 두 번째 `:global` 규칙): 브라우저에서 `.travel-day-tabs__button.is-active`의 계산된 배경을 라이트·다크 각각 확인하고, 배경이 어두우면 글자를 `var(--rm-accent-contrast)`, 밝으면 `var(--rm-text)`로 — 라이트·다크 모두 대비 4.5:1 이상(테마별 선택자 `:root[data-theme='toss']`로 분기 가능). 배경을 바꾸는 방식은 쓰지 않는다(전역 강제와 싸움).

### 4.4 API 사용 순서

**여행 단위(`runImportSave`)** — 대상 = `include && status!=='saved'`(정렬 순)
```
0. (대화상자 열 때) GET /api/travel/plans/{planId}  → 기존 기록 목록(기존 여행 선택 시, 시퀀스로 오래된 응답 폐기)
1. 새 여행이면 POST /api/travel/plans(현행)
2. 항목마다 순차:
   kind photos + attachRecordId:
     memoryId = attachRecordId (POST memories 없음, progress.memoryId에 쓰지 않음)
     !progress.photosUploaded → api.uploadMemoryPhotos(memoryId, files, onProgress)
        = POST /memories/{id}/media/presign → PUT ×(원본+썸네일 4) → POST /memories/{id}/media/complete
        onProgress(p) → onChange(id, { upload: { phase: p.phase, current: p.current, total: p.total } })
     성공 → progress.photosUploaded = true, status 'saved', upload null
     실패 → status 'failed', error = toImportErrorMessage(e) + ' 다시 시도하면 사진만 올립니다.'
   kind photos/place(새 기록): 현행(POST memories → 업로드), 업로드에 onProgress만 추가
   kind gpx: 현행
3. 반환 { planId, ok }
```
재시도는 같은 함수 재호출. 업로드는 presign~complete가 한 묶음이라 실패 시 complete가 호출되지 않아 **사진 중복 없음**(이미 올라간 객체는 서버에 고아로 남을 수 있음 — 기존과 같은 한계, 7장).

**기록 단위(`TravelRecordEditDialog.save`)**
```
1. formDirty && !recordSaved → PUT /api/travel/memories/{id}   (사진만 추가면 생략)
2. newPhotos → uploadTravelMemoryMedia(id, files, '', { onProgress })   (presign → PUT → complete)
3. 실패 → 새 사진 목록 유지, 다시 저장하면 2만(1은 recordSaved로 생략)
```

### 4.5 오류·부분 실패

| 상황 | 처리 |
|---|---|
| 형식·크기·HEIC·손상 | 해당 파일만 거부 목록(파일명 + 이유). 나머지는 진행 |
| 파일 창 취소 | 아무 일도 없음(대화상자 안 열림) |
| 기존 기록 목록 로딩 실패 | 안내 문구 + 모두 새 기록(저장 가능) |
| 붙일 기록이 그 사이 삭제됨 | presign이 404/403 → `toImportErrorMessage`(`로그인이 필요하거나 권한이 없습니다.` 또는 `저장하지 못했습니다.`) + 항목에서 `새 기록으로 저장`으로 바꿔 다시 저장 가능 |
| 저장 중 닫기 | 불가(`busy`, 현행) |
| 저장하지 않은 항목이 있는데 닫기 | 현행 확인창(`저장하지 않은 항목 {n}건이 있습니다. 닫을까요?`) |
| 이탈 | 현행 `calen-route-leave-guard` + `beforeunload`(가져오기·기록 수정 모두) |
| 기록 수정에서 업로드 실패 | 한국어 문구 + `저장` 다시 누르면 업로드만 |

### 4.6 셀렉터·접근성 이름 계약

**유지(바꾸지 말 것):** `docs/record_map_input_flow_workplan.md` 4.6절 전부. 특히 버튼 `＋ 사진·GPX·장소 추가`, `기록 목록·편집`, `새 기록`, `이 기록 수정`, `수정`(라이트박스), dialog `여행에 추가`(all 모드), `기록 수정`, 파일 input label `사진·GPX 파일 선택`(all 모드), `사진 업로드`(레거시 편집기), `.travel-import-*` 클래스, `.travel-map-import-notice`, `.travel-map-selected-record`, heading `장소·사진 목록`.

**변경:** 기록 수정 대화상자의 파일 input label `사진 추가` → 버튼 `사진 추가` + 숨은 input label `사진 파일 선택`(기존 e2e는 이 label을 쓰지 않음).

**신규:**
| 대상 | 값 |
|---|---|
| 지도 헤더 | 버튼 `사진 추가`(정확히, `.travel-my-map-actions__main`), 숨은 input `input[data-photo-picker="map"]` |
| 지도 끌어놓기 | `.travel-map-drop-hint`(role=status) |
| 선택 바·라이트박스 | 버튼 `이 기록에 사진 추가` |
| 사진 모드 대화상자 | dialog 이름 `사진 추가`, input label `사진 파일 선택`, 버튼 `장소·GPX도 추가`, 항목 select `저장 위치`(option `새 기록으로 저장`, `기존 기록: {제목} · {MM-DD}`), 문구 `.travel-import-item__attach`, 요약에 `기존 기록에 추가 {n}` |
| 기록 수정 | 버튼 `사진 추가`, input label `사진 파일 선택`, 체크박스 `첫 사진 위치를 기록 위치로 사용`, 진행 `role=status` |
| 설정 2단계 | 버튼 `사진 추가`(정확히), 행 버튼 이름 `{제목}에 사진 추가`, 숨은 input `input[data-photo-picker="list"]`, 컨테이너 `.travel-memory-map-panel__actions` |

---

## 5. 단계별 작업 지시

### 공통: 실행 환경

- 다른 작업자가 5173 포트를 쓸 수 있다. 별도 포트와 저장소 밖 출력 폴더를 쓴다.
  ```powershell
  # 터미널 1 (백그라운드, 끝나면 반드시 종료)
  cd C:\project\calen\frontend; npx vite --host 127.0.0.1 --port 5188 --strictPort
  # 터미널 2
  cd C:\project\calen\frontend
  $env:E2E_BASE_URL='http://127.0.0.1:5188'; $env:E2E_START_LOCAL_SERVER='0'
  npx playwright test e2e/travel-import.spec.js --config playwright.config.js --reporter=line --output="$env:TEMP\pw-photo-add"
  ```
- 빌드 `npm run build`, 단위 `npm run test:unit`(node:test, `tests/unit/*.test.js`).
- **기록·지도 회귀**(이 문서에서 이렇게 부름):
  `npx playwright test e2e/record-map.spec.js e2e/travel-map-display-modes.spec.js e2e/travel-map-pin.spec.js e2e/mobile-travel-modal.spec.js e2e/record-sharing.spec.js e2e/integrated-calendar.spec.js e2e/travel-import.spec.js e2e/travel-photo-add.spec.js --config playwright.config.js --reporter=line --output="$env:TEMP\pw-photo-add"`
  (`travel-photo-add.spec.js`는 Step 7에서 생긴다. 그 전에는 빼고 실행. `record-sharing`·`integrated-calendar`가 다른 작업 때문에 실패하면 이번 변경과 무관함만 기록)

### Step 0. 기준선

- `git status --short`로 무관한 미커밋 파일 메모. `npm run test:unit`(작성 시점 **188 통과**), `npm run build`, `travel-import.spec.js`(작성 시점 **36 통과·2 건너뜀**), 기록·지도 회귀 실행.
- 완료 기준: 결과 메모, 실패가 있으면 무관함 확인.

### Step 1. 순수 함수 + 단위 테스트 (UI 변경 없음)

- 파일: `frontend/src/lib/travelImport.js`, `frontend/tests/unit/travelImport.test.js`
- 변경: 4.1절 표 전부(`runImportSave` 포함 — 4.4절. `api.uploadMemoryPhotos(id, files, onProgress)` 세 번째 인자로 진행 콜백을 넘긴다).
- 테스트(최소 16개 추가, 6.1절).
- 완료 기준: 기존 테스트 수정 없이 전부 통과(붙이는 초안이 없을 때 결과 불변).
- 검증: `cd C:\project\calen\frontend; node --test tests/unit/travelImport.test.js; npm run test:unit`

### Step 2. composable (UI 변경 없음)

- 파일: `frontend/src/lib/useTravelImport.js`
- 변경: 4.2절 표 전부, `canDecodeImage` export.
- 주의: `onChange`/`Object.assign`로만 상태 변경, `progress`는 객체 통째 교체(현행 규칙). `loadRecords`는 시퀀스 토큰 필수. 사진 초안 생성 후 **역지오코딩 전에** `applyAttachSuggestions()`.
- 검증: `npm run build`, `npm run test:unit`, `travel-import.spec.js` 전체(동작 회귀 없음 — 목의 기존 기록이 없는 시나리오라 결과가 같아야 함).

### Step 3. 가져오기 대화상자 photos 모드 + 항목 붙이기 UI

- 파일: `TravelImportDialog.vue`, `TravelImportDraftItem.vue`
- 변경: 4.3절 해당 부분.
- 완료 기준: mode 기본값 `all`에서 화면·동작이 현행과 같다(I1~I11 통과). photos 모드는 아직 진입점이 없다(Step 5에서 연결) — 임시 진입점을 만들지 않는다.
- 검증: `npm run build`, `npm run test:unit`, `travel-import.spec.js` 전체.

### Step 4. 기록 수정 대화상자 사진 구역

- 파일: `TravelRecordEditDialog.vue`, `TravelWorkspace.vue`(`recordEditor.files`, `:initial-files`, `handleRecordEdited` 문구만)
- 변경: 4.3절 해당 부분.
- 완료 기준: 지도 라이트박스 `수정` → 대화상자 첫 화면(1440x900)에 `사진 추가` 버튼이 보인다(스크롤 없이, `boundingBox().y + height ≤ 900`). 제목만 고치면 PUT 1회·업로드 0, 사진만 추가하면 PUT 0·presign 1·complete 1. E1~E3 통과(`기록을 수정했습니다` 문구 유지).
- 검증: `npm run build`, `npm run test:unit`, `travel-import.spec.js` 전체.

### Step 5. 지도 진입점(헤더·선택 바·라이트박스·끌어놓기) + 연결

- 파일: `TravelMyMapWorkspace.vue`, `TravelPhotoLightbox.vue`, `TravelWorkspace.vue`
- 변경: 4.3절 해당 부분(TravelWorkspace는 `openImport`·`showNotice`·템플릿 props).
- 확인: 데스크톱 헤더 버튼이 겹치지 않음(줄바꿈 허용), 모바일 2열 grid에서 `사진 추가`가 2칸·첫 화면 안, `지도 공유` 2칸, 가로 넘침 0(`document.documentElement.scrollWidth <= 390`). 라이트·다크 계산된 스타일로 `사진 추가` 글자 대비 확인(흰 `-webkit-text-fill-color` + 투명 배경 조합 금지).
- 완료 기준: `사진 추가` → 파일 창 → 사진 고르면 `사진 추가` 대화상자가 그 여행으로 열리고 항목이 이미 들어 있다. 취소하면 아무 일도 없다. 저장 후 지도 갱신·알림(현행 흐름).
- 검증: 빌드·단위, 기록·지도 회귀(record-map 'travel creation…'·'periodless travel…' 포함).

### Step 6. 설정 2단계(목록·편집) 진입점·버그

- 파일: `TravelMemoryPanel.vue`, `TravelHubWorkspace.vue`, `TravelWorkspace.vue`(`hubNotice` 연결)
- 변경: 4.3절 해당 부분(클래스 이름 교체, `사진 추가`·행 버튼, 문구, `capture` 제거, 활성 탭 대비, Hub 연결·피드백).
- 완료 기준: 라이트 테마에서 `새 기록` 글자가 보인다(계산된 `-webkit-text-fill-color`와 `background-color`가 다르고 대비 4.5:1 이상). `사진 추가` → 대화상자 `사진 추가`, `저장할 여행` = 작업할 여행. 저장 후 설정 화면 유지, 표 갱신, 상단 피드백에 결과 문구. 행 `사진 추가` → `기록 수정` 대화상자(새 사진 담김) → 저장 → 표의 사진 수 갱신. 통합 달력 딥링크 시나리오(integrated-calendar.spec) 통과.
- 검증: 빌드·단위, 기록·지도 회귀(L1~L4, record-map의 `새 기록` 사용 테스트 포함).

### Step 7. e2e

- 파일: 신규 `frontend/e2e/fixtures/travelMock.js`, 신규 `frontend/e2e/travel-photo-add.spec.js`, `frontend/e2e/travel-import.spec.js`(목 이동만)
- 내용: 6.3절대로 `travel-import.spec.js` 1~257행의 도우미(`exifSegment`, `withExif`, `canvasImage`, `gpxFile`, `createState`, `planOf`, `planDetail`, `photoPin`, `myMapOverview`, `reverseLookup`, `mock`, `OPEN`, `openMap`, `openImport`, `fileInput`, `requestsTo`, `photoSet`)와 아래쪽 `seedPhotoRecord`, `openLegacyList`를 `fixtures/travelMock.js`로 옮겨 export하고 두 스펙이 import한다. **테스트 본문은 바꾸지 않는다.** 새 스펙은 6.2절 시나리오.
- 완료 기준: 두 스펙 데스크톱·모바일 통과, 모든 테스트 끝 `expect(state.errors).toEqual([])`.
- 검증: `npx playwright test e2e/travel-import.spec.js e2e/travel-photo-add.spec.js …`, 기록·지도 회귀 전체.

### Step 8. 마무리

- 4.6절 계약 재점검, 6.4절 캡처 비교(라이트/다크 × 데스크톱/모바일), 임시 캡처 스펙 삭제, 띄운 vite 종료(포트 5188 LISTEN 없음 확인).
- `git diff --stat`으로 0장 3번 금지 파일 변경 없음 확인(`api.js`, `style.css`는 원래 미커밋 변경이 있으므로 **diff 내용이 작업 전과 같은지** 확인 — 작업 전에 `git diff --stat frontend/src/lib/api.js frontend/src/style.css` 결과를 메모해 둔다).
- 검증: `npm run build`, `npm run test:unit`, 기록·지도 회귀 전체.

---

## 6. 테스트 계획

### 6.1 단위 — `tests/unit/travelImport.test.js` 추가

1. `classifyImportFile`: `photosOnly`에서 `.gpx` 거부(`PHOTO_ONLY_REJECT`), `.txt` 거부(같은 문구), jpg 허용, heic 문구 동일, 16MB 거부. 옵션 없으면 gpx 허용(현행).
2. `normalizeClock`, `minutesApart`(빈값 null).
3. `suggestAttachRecord`: ① 같은 날 10m·20분 → 그 기록(`reason:'nearby'`) ② 300m → null ③ 같은 장소 121분 → null ④ 기록 시각 없음 → 시각 조건 생략하고 매칭 ⑤ 다른 날짜 → null ⑥ 초안 좌표 없음 + 30분 이내 → `time` ⑦ 초안 좌표 없음 + 기록 시각 없음 → null ⑧ 후보 2개면 가까운 쪽 ⑨ 초안 날짜 없음 → null ⑩ kind `gpx`/`place` → null.
4. `describeAttachReason`: m/km 표기.
5. `buildAttachOptions`: 첫 옵션 `새 기록으로 저장`, 제안 기록 다음 같은 날짜, label 형식, 제목 40자 말줄임.
6. `validateDraft`: 붙이는 초안은 제목 비어도 `[]`.
7. `summarizeDrafts`: 붙이는 초안은 `records`에서 빠지고 `attached`·`attachedPhotos`·`photos`에 반영, 저장분 `savedAttached`는 서로 다른 기록 수.
8. `formatImportNotice`: 붙인 것 없음(현행 문자열 그대로), 섞임, 붙이기만.
9. `formatRecordPhotoNotice` 4분기.
10. `describePhotoAgainstRecord`: 좌표 없음, 40m(far=false), 1.2km(far=true), 날짜 다름.
11. `runImportSave`(가짜 api): ① 붙이는 초안은 `createMemory` 0회, `uploadMemoryPhotos(attachId, files, fn)` 1회 ② 업로드 실패 후 재호출 → `createMemory` 0회 유지, 업로드만 1회 더 ③ `onProgress` 호출이 `onChange(id, { upload })`로 전달 ④ 새 기록 초안과 섞여도 순서·결과 정상 ⑤ 기존 시나리오(여행·GPX·경고) 회귀 없음.

### 6.2 e2e — `e2e/travel-photo-add.spec.js` (데스크톱·모바일)

공통: `fixtures/travelMock.js`의 상태형 목. 파일 창은 **버튼을 눌러 연다**:
```js
const [chooser] = await Promise.all([page.waitForEvent('filechooser'), page.getByRole('button', { name: '사진 추가', exact: true }).click()])
await chooser.setFiles([p.a, p.b])
```

| ID | 시나리오 | 핵심 단언 |
|---|---|---|
| P1 GPS 사진 1장 | `/#my-map` 여행 90 선택 → `사진 추가` → 파일 창(취소 없음) → `p.c` 1장 | dialog `사진 추가`, `저장할 여행` 값 `90`, 항목 1개(`data-kind="photos"`), `지도에서 위치 찍기`·`장소 검색`·`경로 직접 그리기` 0개, `저장` → POST `/plans/90/memories` 1회(payload `memoryDate:'2026-10-03'`, 좌표 있음), presign 1회(files 1) → 닫힘 → `.travel-map-import-notice`에 `기록 1건 · 사진 1장` |
| P2 여러 장 + 기존 기록 붙이기 | `seedPhotoRecord`(기록 501 `기요미즈데라` 10-01 10:00, 34.9949/135.785) → `사진 추가` → `p.a`,`p.b`(10-02, 50m) + 근처 사진(34.99495/135.78505, `2026:10:01 10:20:00`) | GET `/api/travel/plans/90` 1회 이상, 항목 2개, 근처 사진 항목의 `저장 위치` 값 `501`이고 그 항목에 textbox `기록 제목` 없음, `.travel-import-item__attach`에 `기요미즈데라`, 요약에 `기존 기록에 추가 1` → `저장` → POST memories **1회**(제목 `후시미이나리`), POST `/memories/501/media/presign` 1회(files 1), 새 기록 presign 1회(files 2) → 알림에 `그중 사진 1장은 기존 기록 1건에 더했습니다` |
| P2b 붙이기 해제 | P2와 같되 `저장 위치`를 `새 기록으로 저장`으로 변경 | 그 항목에 `기록 제목` 다시 보임, 저장 시 POST memories 2회, `/memories/501/media/presign` 0회 |
| P3 GPS 없는 사진 | 캔버스 PNG(`p.plain`) 1장 | 항목에 `날짜 추정`·`위치 없음` 칩, `날짜` 값 `2026-10-01`(여행 시작일), `날짜 확인 필요` 그룹, `저장` → memory payload `latitude:null`. (별도 테스트) `지도에서 위치 지정` → 지도 클릭 → `위치 없음` 사라지고 payload 좌표 있음 |
| P4 형식 거부 | `a.heic`(type `image/heic`), `route.gpx`, 16MB JPEG(버퍼 `Buffer.alloc(16*1024*1024)`를 jpg 이름으로) + 정상 `p.a` | `.travel-import-rejected li` 3개(HEIC 문구, `사진(JPG·PNG·WEBP·GIF·BMP)만`, `15MB`), 항목 1개, 저장 가능. `장소·GPX도 추가` → dialog 이름 `여행에 추가`, `지도에서 위치 찍기` 보임, 항목 유지 |
| P5 부분 실패·재시도 | P2 구성 + `state.failMemoryPresigns = 1` | 첫 실패 항목 상태 `실패: …`(한국어, `Presigned`/`decode` 같은 영어 없음), 버튼 `남은 항목 다시 저장` → POST memories 총 1회 유지(중복 0), presign은 실패 항목만 재호출, 닫힘 |
| P6 라이트박스에서 기존 기록에 2장 | `seedPhotoRecord` → 핀 → `사진 크게 보기` → `이 기록에 사진 추가`(파일 창) → `p.a`,`p.b` | dialog `기록 수정`, `.travel-setup-workflow` 0개, 새 사진 2장(점선 타일 2), `사진 위치 있음` 문구, `사진 추가` 버튼 boundingBox 아래끝 ≤ 뷰포트 높이(데스크톱) → `저장` → PUT `/memories/501` **0회**, presign 1회(files 2), complete 1회 → 알림 `사진 2장을 ‘기요미즈데라’ 기록에 추가했습니다.` |
| P7 위치 없는 기록 + GPS 사진 | `state.memories`에 좌표 없는 기록 추가(지도 핀이 없으므로 `기록 목록·편집` → 행 `{제목}에 사진 추가`) → `p.a` | `첫 사진 위치를 기록 위치로 사용` 체크됨 → `저장` → PUT 1회(body 좌표 = 34.9671/135.7727 근사) + presign 1회 → 설정 화면 유지, 상단 피드백(role=status)에 결과 문구 |
| P8 기록 수정 업로드 실패 재시도 | P6 + `failMemoryPresigns = 1` | 오류 문구 `다시 저장하면 사진만 올립니다`, 대화상자 유지·새 사진 유지 → `저장` → presign 2회째 성공, complete 1회, PUT 0회 |
| P9 목록 화면 `사진 추가` | `기록 목록·편집` → `새 기록` 버튼이 보임(계산된 스타일: `-webkit-text-fill-color` ≠ 배경, 배경이 `rgba(0, 0, 0, 0)`이면 글자색이 흰색이 아님) → `사진 추가`(파일 창) → `p.a` | dialog `사진 추가`, `저장할 여행` `90` → 저장 → heading `장소·사진 목록` 그대로, 표에 새 행, 피드백 문구 |
| P10 끌어놓기(데스크톱만) | `page.evaluateHandle`로 `DataTransfer`에 캔버스 JPEG `File` 추가 → `.travel-overview-map-panel`에 `dragover` → `.travel-map-drop-hint` 보임 → `drop` | dialog `사진 추가` + 항목 1개 |
| P11 Esc·포커스·blob 해제 | `addInitScript`로 `URL.revokeObjectURL` 호출 수를 `window.__revoked`에 셈 → `사진 추가`로 2장 → Esc(확인창 accept) | 닫힘, 포커스가 `사진 추가` 버튼(`document.activeElement` 텍스트), `__revoked ≥ 2`, POST 0회 |
| P12 모바일 레이아웃(모바일만) | 헤더 확인 후 `사진 추가`로 3장 | 헤더 `사진 추가` 아래끝 ≤ 844, 대화상자 = 뷰포트, photos 모드에서 첫 항목이 지도보다 위(`li.travel-import-item` y < `.travel-import-mapbox` y), `저장` 버튼 뷰포트 안, `scrollWidth <= 390`, dialog `scrollWidth <= clientWidth` |
| P13 취소 | `filechooser`에서 `setFiles([])` | 다이얼로그 0개, 요청 0건 |

### 6.3 목·픽스처

- `fixtures/travelMock.js`에 6.2절 도우미를 옮긴다. 목은 이미 `GET /api/travel/plans/{id}`(memoryRecords·mediaItems), presign/complete(`failMemoryPresigns`·`skipMemoryPresigns`), PUT/DELETE를 지원한다. 추가로 필요한 것 없음.
- EXIF JPEG·PNG는 `travel-import.spec.js`의 `exifSegment`/`canvasImage` 그대로(테스트 안에서 버퍼 생성). 1x1 base64 PNG는 정상 사진 픽스처로 쓰지 않는다(디코딩 실패).
- HEIC는 이름·MIME만 맞춘 아무 버퍼면 된다(분류 단계에서 거부). 16MB 파일은 `Buffer.alloc`.

### 6.4 캡처 확인

임시 스펙(예: `e2e/zz-photo-add-capture.tmp.spec.js`, 끝나면 삭제)으로 다크는 `page.addInitScript(() => localStorage.setItem('calen-theme-mode', 'toss'))`. 출력은 저장소 밖 임시 폴더. 대상: ① 지도 헤더(데스크톱·모바일, 라이트·다크) ② 사진 모드 검토(붙이기 항목 포함) ③ 저장 중 진행 문구 ④ 부분 실패 ⑤ 기록 수정 대화상자 첫 화면(사진 구역) ⑥ 설정 2단계 머리(`사진 추가`·`새 기록` 라이트) ⑦ 일차 탭 활성(라이트·다크) ⑧ 끌어놓기 표시.

---

## 7. 리스크와 비범위

### 7.1 리스크와 대응

| 리스크 | 대응 |
|---|---|
| 자동 붙이기가 엉뚱한 기록을 고름 | 같은 날짜 + 250m + 120분(GPS 없으면 30분)으로 좁게 제한, 항목에 이유 문구와 `저장 위치` select를 항상 보여 줌, 붙이는 항목은 기록 내용을 바꾸지 않음(사진만 추가 — 나중에 사진 삭제로 되돌릴 수 있음) |
| 파일 창이 안 열리는 브라우저 | 숨은 input을 `display:none` 대신 시각적 숨김으로, 클릭 처리기 안에서 동기 `click()`. 대화상자 안 드롭 영역 label(키보드 접근)도 그대로 둔다 |
| iOS가 사진 위치를 빼고 줌 | R2/R3 규칙(위치 없음 칩·지도에서 위치 지정·기록 단위면 기록 위치에 표시) |
| 업로드 도중 실패 시 서버에 고아 객체 | complete 전이라 DB에는 사진이 생기지 않음(중복 없음). 저장소 정리는 서버 기존 정책에 맡긴다(이번 범위 밖) |
| 헤더 버튼 증가로 데스크톱 줄바꿈 | 그룹 단위 줄바꿈 허용, 겹침만 금지. 모바일은 2열 grid 규칙 유지 |
| 모바일 버튼 수 홀수 | `지도 공유` 2칸 규칙 |
| 큰 여행에서 `GET /plans/{id}` 비용 | 대화상자 열 때 1회(여행 바꿀 때만 재요청), 시퀀스로 오래된 응답 폐기. 기록 수정 대화상자도 같은 API를 이미 쓴다 |
| 전역 CSS가 새 버튼 색을 덮음 | 단계마다 라이트·다크 계산된 스타일 확인, 전역 클래스 이름 차용 금지 |
| `File` 객체를 reactive 상태에 넣음 | `markRaw(Array.from(files))` |
| 설정 화면에서 저장 후 결과가 안 보임 | `hubNotice` → Hub `setFeedback` |

### 7.2 비범위 (이번에 하지 않음)

- HEIC 변환(새 의존성 필요), 서버 측 형식 확장.
- 가져오기 지도에 기존 기록 핀을 흐리게 함께 표시(검토 편의 — P2).
- 이전 배치 초안과 새로 추가한 사진을 다시 묶기(현행도 배치별 묶음).
- 사진을 다른 기록으로 옮기기, 사진 순서 바꾸기, 대표 사진 지정 흐름 변경.
- `TravelMyMapClusterPanel`의 클러스터 미리보기 안 버튼(파일 수정 금지 — 라이트박스·선택 바로 대체).
- 사진 지도(`TravelMyPhotosWorkspace`) 업로드 진입점.
- 가져오기 대화상자 all 모드의 `장소 찾기` 버튼 대비 문제(캡처 `C6a-import-empty-d`에서 글자가 흐림) — photos 모드에서는 숨겨지므로 별도 작업으로 남긴다.
- 레거시 편집기에서 여러 장을 고르면 한 기록에 붙는 동작 자체(안내 문구만 추가).
- 지도 공유 링크(제외 목록 방식)에 새 사진이 자동 노출되는 문제(선행 문서 P2와 동일).

---

## 부록 A. 단계별 체크리스트

- [ ] Step 0 기준선 메모(단위 188, travel-import 36+2 건너뜀, `api.js`/`style.css` diff stat 메모)
- [ ] Step 1 순수 함수·단위 테스트(기존 테스트 무수정 통과)
- [ ] Step 2 composable(기존 e2e 통과)
- [ ] Step 3 photos 모드·붙이기 UI(all 모드 불변)
- [ ] Step 4 기록 수정 사진 구역(사진만 추가 시 PUT 0, 첫 화면에 `사진 추가`)
- [ ] Step 5 지도 진입점(헤더·선택 바·라이트박스·끌어놓기, 모바일 grid)
- [ ] Step 6 설정 2단계(`새 기록` 보임, `사진 추가`·행 버튼, `capture` 제거, 탭 대비, 피드백)
- [ ] Step 7 e2e(공용 목 이동, P1~P13)
- [ ] Step 8 회귀·캡처·임시 파일 삭제·vite 종료·금지 파일 확인
