# 기록·지도 UI/UX 개편 설계 분석 및 작업 지시서

- 대상: 상단 메뉴 `기록·지도`(route `travel`, `my-map`, `travel-money`, `travel-log`, `photo-album`, `public-trips`)와 공유 화면 `record-map-share`
- 작성 기준: 2026-11-21 작업 트리(미커밋 변경 포함), 실제 화면 캡처(데스크톱 1440x900, 모바일 390x844, 라이트/다크)
- 범위: **이번 구현은 P0 + P1만** 한다. P2는 7장 '비범위'에만 적었다.
- 구현 담당자는 이 문서만 보고 작업한다. **5장 단계를 순서대로** 진행하고, 단계마다 빌드·테스트를 통과시킨 뒤 다음 단계로 넘어간다.

---

## 0. 꼭 지킬 규칙 (먼저 읽을 것)

1. **백엔드 변경 금지.** `backend/` 아래는 열람만 한다. API 경로·파라미터·응답 형식은 그대로 쓴다.
2. **새 npm 의존성 금지.** `package.json`, `package-lock.json`을 수정하지 않는다.
3. **통합 달력 파일 수정 금지.** 동시에 다른 작업자가 구현 중이다.
   - 금지: `frontend/src/components/IntegratedCalendarWorkspace.vue`, `frontend/src/components/integrated-calendar/*`, `frontend/src/lib/integratedCalendar*.js`, `frontend/src/lib/useIntegratedCalendarSources.js`, `frontend/src/lib/useMediaQuery.js`(달력 작업이 만들 수 있음), `frontend/e2e/integrated-calendar.spec.js`, `frontend/tests/unit/integratedCalendar*.test.js`
4. **`RecordMapWorkspace.vue`의 임베드 계약 유지.** 통합 달력이 `IntegratedCalendarWorkspace.vue` 292행에서 다음처럼 쓴다.
   ```vue
   <RecordMapWorkspace public-view :calendar-grant-id="mapSource.grantId" :place-focus-request="mapSource.focus" />
   ```
   - props 이름·타입(`token: String`, `publicView: Boolean`, `placeFocusRequest: Object`, `calendarGrantId: [String, Number]`)과 emits(`open-travel`, `place-focus-consumed`)를 바꾸지 않는다. 새 prop을 추가할 때는 반드시 기본값이 있어야 하고, 기본값일 때 현재 동작과 같아야 한다.
   - `calendarGrantId`가 있으면 `fetchCalendarMapView` / `fetchCalendarSharedPlace`를 쓰는 분기(RecordMapWorkspace.vue 78~80, 109, 411~413행)를 그대로 둔다.
5. **App.vue 계약 유지.** `App.vue`는 이번 작업에서 **수정하지 않는다.** 다음 경로가 모두 그대로 동작해야 한다.
   - `navigateCalendarPlaceSource` (App.vue 654~658행): `calendarPlaceFocusRequest` 설정 후 `navigate('travel')` → Hub가 `내 장소` 모드로 열리고 `RecordMapWorkspace`가 장소 상세를 연다.
   - `navigateCalendarTravelSource` (648~652행), `navigateTravelRecordLocation` (635~646행): `travelRecordFocusRequest` 설정 후 `navigate('travel-money')` → Hub가 `여행 · 일정 · 경로` 모드로 열리고 `TravelWorkspace`가 sourceType별 화면을 연다.
   - Hub(`TravelWorkspace` 이름으로 import된 `RecordMapHubWorkspace.vue`, App.vue 27행, 1397~1404행)의 props `route`, `recordFocusRequest`, `placeFocusRequest`와 emits `open-household-travel-ledger`, `record-focus-consumed`, `place-focus-consumed`를 유지한다.
   - 헤더 `ALBUM` 버튼 → route `photo-album` → Hub 여행 모드 → 사진첩. 유지.
   - `#record-map-share/{token}` → `<RecordMapWorkspace :token public-view />`(App.vue 1142~1144행), `#travel-share/{token}` → `TravelPublicMapShareWorkspace`. 유지.
6. **데이터 정합성·권한 동작 유지.** 공유 화면은 읽기 전용(쓰기 요청 0건), 여행 원본은 ID 참조만 하고 쓰지 않음, 날짜 없는 사진 묶음은 방문 수에 포함하지 않음, 위치 검색은 버튼/Enter로만 실행, 검색 후보는 저장 확인 전 DB에 저장하지 않음, 오래된 요청 응답은 시퀀스 번호로 버림(`requestSequence`, `listSequence`, `locationSequence`, `detailSequence`, `metadataSequence`). 이 로직은 **한 줄도 의미를 바꾸지 않는다.** 순수 함수로 옮길 때는 원래 식을 그대로 옮기고 단위 테스트로 고정한다.
7. **`frontend/src/style.css`는 수정하지 않는다.** 토큰은 3.5절의 새 CSS 파일(네임스페이스 한정)과 컴포넌트 scoped 스타일에 둔다. 전역 CSS를 이겨야 하면 3.6절 규칙(루트 접두사 + `!important`)을 쓴다.
8. **작업 트리의 무관한 미커밋 변경을 건드리지 않는다**(`style.css`, `api.js`, `integratedCalendarCore.js`, `.env*`, 백엔드 등). `git checkout/restore/stash/add -A` 금지. 커밋은 사용자 지시가 있을 때만, 이 작업 파일만 지정해서 한다.
9. **e2e 셀렉터 계약(3.7절)을 지킨다.** 셀렉터를 바꾸면 같은 단계에서 테스트도 고치고, 기존 테스트가 검증하던 시나리오는 하나도 빠지면 안 된다.
10. **접근성:** 메뉴·다이얼로그는 키보드로 열고 닫을 수 있어야 한다(Esc, 닫은 뒤 원래 버튼으로 포커스 복귀). 아이콘만 있는 버튼은 `aria-label`. 모바일 터치 대상은 최소 44px. 라이트/다크 모두 본문 대비 4.5:1 이상.
11. **스크롤 잠금 주의:** App.vue는 `[role="dialog"][aria-modal="true"]`가 DOM에 있으면 모바일에서 배경 스크롤을 잠근다(App.vue 115~131행). 지도 위 패널·바텀시트에는 `role="dialog"`/`aria-modal`을 **붙이지 않는다.**

---

## 1. 현행 구조 요약

### 1.1 화면 트리 (실제로 렌더되는 것)

```
App.vue
├─ (인증) activeRoute ∈ travelRouteKeys {travel, travel-money, travel-log, photo-album, my-map, public-trips}  (App.vue 269, 1397)
│   └─ RecordMapHubWorkspace.vue (34행)  ← App에서는 'TravelWorkspace'라는 이름
│       ├─ nav.panel.record-map-mode-switch : [내 장소] [여행 · 일정 · 경로]   (24~27행)
│       ├─ mode==='records' → RecordMapWorkspace.vue (708행)
│       │    ├─ header.panel.record-map-header : h2 '내 장소' (+ Teleport로 추가 버튼을 지도 도구/사이드바로 이동)  (493~503)
│       │    ├─ div.record-map-frame → TravelMyMapClusterPanel(workspace-layout)   (506~526)
│       │    │    ├─ #navigation 슬롯: nav.record-map-tools (확대/축소, 전체 보기, 전체 화면, 표시 설정)  (511~518)
│       │    │    └─ #workspace-overlays 슬롯: RecordMapSidebar.vue (136행)  (519~524)
│       │    └─ RecordMapDialog.vue (<dialog>) : place | visit | collection | travel | share | detail | photo  (527~607)
│       └─ mode==='travel' → TravelWorkspace.vue (509행)
│            ├─ primaryTab 'map'   → TravelMyMapWorkspace.vue (1,934행) → TravelMyMapClusterPanel(일반 레이아웃)
│            │                        + TravelJourneyPlaybackControls, TravelMyMapInspectorPanels, TravelPhotoLightbox, 공유 모달
│            ├─ primaryTab 'setup' → TravelHubWorkspace.vue (2,897행) 여행 등록 워크플로(1 정보 / 2 사진·장소 / 3 GPX / 4 요약)
│            │                        → TravelMemoryPanel(1,505), TravelRouteWorkspace(1,535), TravelMapPanel(1,200)
│            ├─ primaryTab 'photos' → TravelHubWorkspace route 'photo-album' → TravelMyPhotosWorkspace 등
│            ├─ primaryTab 'finance' → 가계부 연계 안내 패널 / (legacy) TravelHubWorkspace route 'travel-money'
│            └─ primaryTab 'record-shares' → RecordSharingWorkspace kind=TRAVEL
├─ #record-map-share/{token} → RecordMapWorkspace :token public-view   (App.vue 1142)
├─ #travel-share/{token}     → TravelPublicMapShareWorkspace          (App.vue 1146)
└─ (통합 달력 내부) IntegratedCalendarWorkspace 292행 → RecordMapWorkspace public-view :calendar-grant-id
```

### 1.2 컴포넌트 역할과 사용 여부

| 파일 | 행 | 역할 | 사용처 / 상태 |
|---|---|---|---|
| `RecordMapHubWorkspace.vue` | 34 | 모드 전환(내 장소 / 여행) | App.vue. 모드는 내부 ref, URL에 반영 안 됨 |
| `RecordMapWorkspace.vue` | 708 | 내 장소 지도: 조회·검색·등록·상세·공유 전부 | Hub, App(공유 route), 통합 달력 |
| `RecordMapSidebar.vue` | 136 | 지도 위 검색/목록 패널 | RecordMapWorkspace |
| `RecordMapDialog.vue` | 40 | 네이티브 `<dialog>` 래퍼(포커스 복귀, Esc) | RecordMapWorkspace |
| `RecordMapPlaybackControls.vue` | 21 | 기록 재생 UI | **죽은 코드**(import 0건. 재생은 문서상 제거됨) |
| `TravelWorkspace.vue` | 509 | 여행 모드 라우팅(primaryTab) | Hub |
| `TravelMyMapWorkspace.vue` | 1,934 | 여행 사진 지도 | TravelWorkspace |
| `TravelMyMapClusterPanel.vue` | 1,789 | 공통 Leaflet 지도(클러스터/핀/미리보기/전체화면) | 4곳 |
| `TravelHubWorkspace.vue` | 2,897 | 여행 등록 워크플로, 사진첩, 여행 돈 장부(legacy) | TravelWorkspace |
| `TravelMapPanel.vue` | 1,200 | 편집용 지도(검색, 시야/크기 토글) | 장소 등록 폼 등 6곳 |
| `TravelPublicTripsWorkspace.vue` | 1,435 | 공개 여행 | **죽은 코드**(import 0건. route `public-trips`는 지도 열기로 처리, TravelWorkspace 150~152행) |
| `TravelCommunityWorkspace.vue` | 177 | 커뮤니티 피드 | `LEGACY_TRAVEL_SHARING_ENABLED = false`(TravelHubWorkspace 268행)로 **렌더되지 않음** |
| `TravelSharedExhibitWorkspace.vue` | 239 | 공유 전시 | TravelHub 안에서는 플래그로 숨김. RecordSharingWorkspace·통합 달력에서는 사용 중 |
| `RecordSharingWorkspace.vue` | 262 | 공유받은 여행/가계부 | TravelWorkspace(여행 모드 일부 화면에서만 보임), Household |
| `RecordShareDialog.vue` | 145 | 그룹 공유 요청 | TravelHub, Household |

`buildRecordMapJourney`(lib/recordMap.js 31행)는 화면에서 쓰지 않지만 단위 테스트(`tests/unit/recordMap.test.js`)가 있으므로 이번에는 남긴다.

### 1.3 RecordMapWorkspace 상태 (RecordMapWorkspace.vue 16~43행)

| 상태 | 의미 |
|---|---|
| `snapshot` | 지도용 응답(`/map`, 최대 장소 500·방문 200·여행 사진 1,000) |
| `listSnapshot` | 목록용 응답(`/overview`, 30개씩 페이지) |
| `filters` {collectionId, state, from, to, q, page} | 서버 조회 조건 |
| `view` 'places'/'collections'/'diary' | 사이드바 탭 |
| `displayMode` 'cluster'/'pin', `pickMode` | 지도 표시/핀 찍기 |
| `compact`, `sidebarOpen` | `matchMedia('(max-width: 760px)')` 결과, 패널 펼침 |
| `searchMode` 'saved'/'location', `searchText`, `locationResults`, `foundLocation`, `locationHistory`, `lastLocationQuery` | 저장 장소 검색 / 새 위치 검색 |
| `frameHeight` | `innerHeight - 프레임 문서 top - 12` (refreshFrame 171~179행) |
| `selectedPlaceId`, `selectedPhotoId`, `selectedMarkerId`, `focusTarget`, `fitKey` | 지도 선택·카메라 |
| `modal` '', 'place', 'visit', 'collection', 'travel', 'share', 'detail', 'photo' | 다이얼로그 |
| `placeForm`, `visitForm`, `collectionForm`, `photosPending`, `dirty` | 폼 |
| `detailVisits`, `detailPage`, `detailHasMore` | 장소 상세 방문 목록(20개 페이지) |

### 1.4 데이터 흐름과 API 호출 시점

| 시점 | 호출 | 코드 |
|---|---|---|
| 마운트 / token·grant 변경 | `load()` → `/map` + `/overview` 병렬 (grant면 `fetchCalendarMapView` 2회) | 194~201, 70~94 |
| 필터(collectionId/state/from/to/q) 변경 | `load()` (page 0으로) | 202 |
| 페이지 변경 | `loadList()`만 (`/overview`) — 지도 재조회 없음 | 203, 104~116 |
| 저장 장소 검색 입력 | 300ms 디바운스 후 `filters.q` → `load()` | 121~125 |
| 새 위치 검색 | 버튼/Enter에서만 `/locations/search` (AbortController + 시퀀스) | 136~155 |
| 장소 상세 열기 | `/places/{id}/visits?page` (읽기 전용이면 스냅샷에서) | 391~405 |
| 저장(장소/방문/모음/가져오기/공유) | 각 API 후 `load()` 전체 재조회 | 289~479 |
| `placeFocusRequest.token` (달력에서 진입) | `fetchCalendarPlaceSource` 또는 `fetchCalendarSharedPlace` → `openDetail` → `place-focus-consumed` | 406~422 |
| 이탈 가드 | `dirty` 변경 시 `calen-route-leave-guard` 이벤트, `beforeunload` | 204, 481~488 |

Hub 모드 전환은 `v-if`라서 `내 장소 ↔ 여행` 전환마다 반대쪽 컴포넌트가 언마운트되고, 돌아오면 지도 초기화와 API 재조회(`/map`+`/overview` 또는 `/travel/portfolio`+`/travel/my-map`+여행 목록)가 다시 일어난다.

### 1.5 Hub 모드 결정 규칙 (RecordMapHubWorkspace.vue 7~21행)

- 초기: `route === 'travel' ? 'records' : 'travel'`
- `route` 변경 시 같은 규칙으로 재설정
- `recordFocusRequest.token` 생기면 `'travel'`, `placeFocusRequest.token` 생기면 `'records'`(immediate)
- 버튼 전환 시 `calen-route-leave-guard` 상태가 active면 `window.confirm`
- 여행 모드에 넘기는 route: `route === 'travel' ? 'my-map' : route`

---

## 2. 문제점 (캡처·코드로 확인)

캡처 위치: 작업자 로컬 임시 폴더(`%TEMP%\recordmap-shots\` 등, 저장소에 포함하지 않음. 파일명은 6.5절)

### A. 정보 위계·흐름

- **A1. 지도 위에 의미 없는 띠 2개.** 데스크톱에서 모드 전환 패널(y 103~175)과 제목만 있는 `내 장소` 헤더 패널(y 193~251)이 약 150px을 차지해 지도가 y 261부터 시작한다(01-records-d-*). 헤더 패널의 버튼은 Teleport로 지도 안으로 옮겨져 있어 헤더에는 h2만 남는다(RecordMapWorkspace 493~503행).
- **A2. 주요 행동(＋ 장소 추가)이 지도 오른쪽 도구 막대 맨 위에 92px 폭으로 끼어 있다.** 확대/전체 보기 같은 지도 조작과 같은 위계로 보인다. 모바일에서는 목록을 펼칠 때만 사이드바 헤더로 옮겨진다(30행 `actionsTarget`).
- **A3. 같은 조건을 세 군데서 고른다.** 모음(카테고리) 필터가 칩 줄(Sidebar 35~39행), `모음` 탭(52~59행), `필터 > 장소 모음` select(46행) 세 곳에 있다.
- **A4. 공유받은 여행 진입점이 사실상 숨겨져 있다.** `공유받은 여행` 버튼(TravelWorkspace 396행)은 `primaryTab`이 map/setup이 아닐 때만 보이는 switcher 안에 있다. 기록·지도 기본 화면(내 장소, 여행 지도)에서는 보이지 않고 `ALBUM` 화면에 들어가야 나온다. 승인 대기 배지도 그곳에만 있다. (e2e는 컴포넌트를 직접 마운트해서 검증하므로 이 문제를 잡지 못한다.)
- **A5. 모드가 URL에 남지 않는다.** `여행 · 일정 · 경로`로 바꿔도 hash는 `#travel` 그대로라 새로고침·뒤로 가기를 하면 항상 `내 장소`로 돌아간다. 이미 App에 `#my-map` route가 있고 Hub가 `my-map`을 여행 모드로 해석하므로 App 수정 없이 해결할 수 있다(4.3절).
- **A6. 여행 모드는 완전히 다른 화면 문법이다.** 내 장소는 '지도 가득 + 떠 있는 패널', 여행 지도는 '세로로 쌓인 패널 + 페이지 스크롤 + 지도 위 가운데 정렬 도구줄'이다(06b-travel-full-*). `장소·사진 추가`를 누르면 별도 'TRAVEL SETUP' 전체 화면으로 넘어가고 모달이 바로 뜬다(07-*).

### B. 지도와 패널

- **B1. (버그) 카테고리 칩 줄이 3px로 눌려 보이지 않는다.** `.record-map-sidebar__body`가 `display:flex; flex-direction:column`(Sidebar 93행)이고 칩 줄 `.record-map-categories`가 `overflow-x:auto`(94행)라서 `min-height:auto`가 0으로 계산되어 수축한다. 01-records-d-light.png의 y≈432에서 칩 테두리만 보인다. 사용자는 카테고리를 고를 수 없다.
- **B2. 목록 카드가 키가 크다(약 140px).** 카드 안에 또 테두리 카드처럼 보이는 main 버튼(전역 버튼 배경 강제)과, 두 번째 줄에 따로 놓인 `방문 기록 보기` 버튼 때문이다. 1440x900에서 장소 2개만 보인다.
- **B3. 정의되지 않은 토큰과 청록색 대체값.** `--accent-soft`, `--input-bg`는 style.css에 정의가 없다(검색 결과 0건). 그래서 선택 상태 배경이 대체값 `#009e8c22`(청록)로 칠해져 올리브 테마와 어긋난다(Sidebar 85, 88, 96, 106, 109행). 검색 후보 번호 배지 `#357ccb`+흰 글자는 4.3:1로 작은 글씨 기준 미달이다(Sidebar 89행).
- **B4. 읽기 전용 공유 화면에서도 `모음` 탭이 보인다.** 공유 범위는 모음 1개뿐이라 의미가 없고, `필터 > 장소 모음` select는 disabled로만 표시된다(09-public-share-d.png).
- **B5. 지도 다시 그리기 범위가 넓다.** `data` computed(51~58행)가 위치 후보·포커스가 바뀔 때마다 `buildRecordMapData` 전체를 다시 만들어 새 배열을 내려보내고, `TravelMyMapClusterPanel`의 watch(1575~1580행)는 배열 identity로 `renderMap()`을 다시 실행한다. `thumbnail(id)`(117~120행)는 장소마다 방문 전체를 훑는다(O(장소 x 방문)).

### C. 다이얼로그(장소 등록·상세)

- **C1. 장소 등록 폼 안의 지도 도구가 과하다.** `TravelMapPanel`의 시야(고정/따라가기)·크기(작게/보통/크게) 토글이 작은 위치 선택 지도에도 그대로 나와, 모바일에서는 이름 칸 아래 도구만 화면 하나를 차지한다(04-place-dialog-m-dark.png).
- **C2. 저장 버튼이 폼 맨 아래에만 있다.** 위치·카테고리·사진·방문·고급까지 내려가야 `장소 저장`이 나온다. 선택한 좌표를 보여주는 곳도 없다(좌표는 접힌 '직접 입력' 안에만 있음).
- **C3. 장소 상세의 표시 오류.** 국가·지역이 없으면 `· 방문한 곳`처럼 앞에 가운뎃점이 붙는다(587행). 메모가 없어도 빈 문단이 공간을 차지한다(588행). 기록이 1페이지뿐이어도 `이전 기록 / 1페이지 / 다음 기록`이 항상 보인다(598행). 장소가 속한 모음이 표시되지 않는다(05-detail-dialog-d-light.png).
- **C4. `더보기` 메뉴(`<details>`)는 Esc나 바깥 클릭으로 닫히지 않는다.** 메뉴 안 버튼을 눌렀을 때만 닫힌다(495행).

### D. 모바일

- **D1. 목록을 펼쳐도 목록이 보이지 않는다.** 390x844에서 펼친 패널은 헤더(추가/더보기 버튼이 세로 grid로 쌓여 약 100px), 검색 모드, 검색, 눌린 칩, 탭, 방문 상태, 필터로 꽉 차서 장소 카드가 한 장도 보이지 않는다(02-sidebar-open-m-light.png). 원인: `.record-map-add-actions { display:grid }`(621행), B1, 컨트롤 줄마다 큰 여백.
- **D2. 지도 위 공간 손실.** 상단 테마 스위처 띠 + 앱 헤더 + 모드 전환 + 빈 `내 장소` 헤더로 지도가 y≈282(CSS px)부터 시작한다(01-records-m-light.png).
- **D3. 여행 지도 헤더 버튼 6개가 한 줄에 하나씩 세로로 쌓인다**(06b-travel-full-m-light.png, 약 330px). 전역 CSS(style.css 7016~7023, 15483~15500, 30045행)가 폭을 강제한다.
- **D4. 전역 레이아웃 누수.** 모바일 헤더 압축 규칙이 `body:has(.record-map-workspace)`로 걸려 있어(RecordMapWorkspace 643~658행) 통합 달력이 공유 지도 원본을 임베드하는 순간에도 앱 헤더가 압축된다. 반대로 여행 모드에서는 압축되지 않아 모드 사이 헤더 높이가 달라진다. 모드 전환 바 스타일(657~658행)도 Hub가 아닌 Workspace에 있다.

### E. 일관성·접근성·기타

- **E1.** 검색 모드·탭·칩·카드 선택 상태가 각자 다른 색과 굵기로 표시된다(aria-pressed 스타일이 컴포넌트마다 다름).
- **E2.** 지도 도구 버튼 높이 36px(638행)로 모바일 터치 기준 44px 미달.
- **E3.** 이미지에 `width/height`, `decoding="async"`가 없어 목록 로딩 중 레이아웃이 밀린다(Sidebar 61, 67행; Workspace 594행).
- **E4.** 죽은 CSS: `.record-map-filters`, `.record-map-place-grid`, `.record-map-state`, `.record-map-floating-actions`, Workspace scoped의 `.record-map-diary`/`.record-map-empty`(자식 컴포넌트 요소라 scoped로 적용되지 않음) (Workspace 668~706행).
- **E5.** (전역, 이번 범위 밖) 데스크톱에서 떠 있는 테마 스위처가 앱 헤더의 프로필/로그아웃과 여행 모달의 `닫기` 버튼을 가린다(07-travel-setup-step2-d-light.png). 7장에 기록.

---

## 3. 목표 정보 구조와 화면 설계

### 3.1 원칙

1. **지도가 주인공.** 기록·지도에 들어오면 앱 헤더 아래 얇은 모드 바(데스크톱 ≤ 52px, 모바일 ≤ 48px) 하나만 두고 나머지 높이는 지도에 준다.
2. **사용자가 오는 이유 3가지를 모드로:** `내 장소`(일상 장소·방문·사진), `여행 · 일정 · 경로`(여행 단위 사진·GPX), `공유받은 여행`(승인 대기 배지). 모드는 URL에 남긴다(`#travel` / `#my-map`).
3. **주요 행동은 목록 패널 머리에.** `＋ 장소 추가`는 패널이 열려 있으면 패널 헤더, 접혀 있으면 지도 도구 막대에 둔다(데스크톱·모바일 공통 규칙).
4. **패널은 '고정 컨트롤 + 스크롤 목록'.** 컨트롤 줄은 절대 수축하지 않는다(`flex-shrink:0`). 목록 카드는 76px 안팎.
5. **기존 기능·이름은 유지.** 버튼 이름, 다이얼로그 제목, 라벨은 3.7절 계약 그대로 둔다(e2e와 사용자 학습 비용).

### 3.2 데스크톱 와이어프레임 (1440x900)

```
┌ 앱 topbar (변경 없음) ──────────────────────────────────────────────────────┐
├ .record-map-hub > nav.record-map-mode-switch  (높이 ≤52px, 패널 테두리 1줄) ──────┤
│ [내 장소] [여행 · 일정 · 경로] [공유받은 여행 ②]                                  │
├ .record-map-frame (남은 높이 전부, 기존 refreshFrame 계산 유지) ────────────────────┤
│┌ .record-map-sidebar (360px) ───────────────┐                    ┌ .record-map-tools ┐│
││ 내 장소 · 3곳        [＋ 장소 추가][더보기][접기] │                    │ [−] 15 [＋]       ││
││ [내 장소 검색|새 위치 찾기]  (32px 세그먼트)     │                    │ [전체 보기]       ││
││ [장소명·지역·기록 검색 ............][검색]       │                    │ [전체 화면]       ││
││ [전체][주말 맛집][산책][+ 관리]  → 가로 스크롤   │        지도         │ 표시 설정 ▸       ││
││ [장소 목록][모음][일상 기록]                   │                    └──────────────────┘│
││ [방문 상태: 전체 ▾      ] [필터 · 적용 중 ▸]    │                                      │
││ ─────────────────────────────── (아래만 스크롤) │                                      │
││ ┌48┐ 서울 카페                 방문 기록 보기   │                                      │
││ └──┘ 대한민국 · 서울 · 방문한 곳 · 1번 기록      │                                      │
││ ┌48┐ 도쿄 산책 ...                            │                                      │
││ 장소 3개 · 1 / 1                  [이전][다음] │                                      │
│└────────────────────────────────────────────┘                                      │
└──────────────────────────────────────────────────────────────────────────────┘
```

- 소유자 화면에서는 `header.record-map-header`를 렌더하지 않는다(A1). 읽기 전용(공유/임베드) 화면은 h2(공유 제목)와 `읽기 전용` 배지를 가진 얇은 헤더(≤ 48px)를 유지한다(e2e가 `.record-map-workspace h2`를 검사).
- 오른쪽 도구 막대는 지도 조작만 남아 72~80px 폭으로 줄어든다(패널이 열려 있는 동안 추가 버튼은 패널 헤더에 있음).

### 3.3 모바일 와이어프레임 (390x844, 폭 760px 이하 = compact)

```
접힘(기본)                                  펼침(목록 버튼 / 검색 후)
┌ 테마 스위처 · topbar(압축, Hub에서) ┐     ┌ topbar(압축) ─────────────────┐
├ 모드 바: [내 장소][여행·일정·경로][공유②]┤  ├ 모드 바 ───────────────────────┤
├ 지도 ───────────────────────────┤     ├ 지도(위 30%) ───────────────────┤
│[＋ 장소 추가][더보기][−]15[＋][전체보기]│     │                               │
│[전체 화면][표시 설정 ▸]  (도구 4열)   │     ├ 시트(지도 높이의 72%) ───────────┤
│                                   │     │ 내 장소 · 3곳 [＋ 장소 추가][더보기][접기]│ ← 한 줄
│               지도                 │     │ [내 장소 검색|새 위치 찾기]           │
│                                   │     │ [검색어 ...........][검색]          │
│┌ 검색 줄 ─────────────────────────┐│     │ [전체][주말 맛집][+관리] →          │
││[장소명·지역·기록 검색 ][검색] [목록]││     │ [장소 목록][모음][일상 기록]          │
│└─────────────────────────────────┘│     │ [방문 상태 ▾][필터 ▸]               │
└───────────────────────────────────┘     │ 카드 76px x 2~3장 보임 (본문 스크롤)  │
                                          └──────────────────────────────────┘
```

- 펼침 상태에서 도구 막대 숨김은 현재 규칙 유지(`.travel-map--sidebar-expanded > .travel-map__toolbar { display:none }`, Workspace 660행). e2e가 의존한다.
- 시트 높이: 펼침 시 `height: 72%`(지도 프레임 기준)로 고정해 예측 가능하게 한다. 접힘 시 기존처럼 검색 줄만.
- 시트 헤더의 추가 버튼은 **가로 한 줄**(flex row, 각 버튼 min-height 40px, 폰트 12px).

### 3.4 여행 모드(TravelMyMapWorkspace) 화면 정리 — P1 범위

구조(패널 쌓기)는 유지하고 첫 패널의 버튼 무리만 정리한다.

```
┌ panel: TRAVEL ATLAS / 내 여행 지도 ──────────────────────────────────────────────┐
│ [여행 선택 ▾ 전체 여행]                                                          │
│ 여행  : [새 여행 만들기] [여행 정보 수정]                                           │
│ 기록  : [장소·사진 추가(주)] [지도에 장소 찍기] [경로 추가]                           │
│ 공유  : [지도 공유]                                                              │
│ ▸ 지도 필터와 요약                                                               │
└────────────────────────────────────────────────────────────────────────────┘
모바일: 그룹 라벨은 숨기고 버튼을 2열 grid로. `장소·사진 추가`만 2칸 차지. 높이 ≈ 4줄(약 200px → 현재 330px).
```

### 3.5 디자인 토큰 (새 파일, 네임스페이스 한정)

새 파일 `frontend/src/components/record-map/recordMapTokens.css`를 만들고 `RecordMapHubWorkspace.vue`와 `RecordMapWorkspace.vue`의 `<script setup>`에서 `import './record-map/recordMapTokens.css'`(Workspace는 `'./record-map/recordMapTokens.css'`, 경로는 두 파일 모두 `components/` 기준)로 불러온다. 선택자는 `.record-map-hub, .record-map-workspace`로만 한정해 다른 화면에 영향이 없게 한다. CSS 변수는 DOM으로 상속되므로 Sidebar와 `<dialog>` 안에서도 쓸 수 있다. (통합 달력 계획서 3.5절과 같은 팔레트·같은 명명 방식(`--ic-*` ↔ `--rm-*`)을 쓴다.)

```css
.record-map-hub, .record-map-workspace { /* 라이트(기본) */ }
:root[data-theme='toss'] :is(.record-map-hub, .record-map-workspace) { /* 다크 */ }
```

| 토큰 | 용도 | 라이트 | 다크 (`data-theme='toss'`) |
|---|---|---|---|
| `--rm-surface` | 카드·입력 배경 | `var(--surface-elevated, #faf8f2)` | `var(--dark-card, #20292c)` |
| `--rm-panel` | 패널·도구 막대·시트·다이얼로그 | `var(--panel-bg, #f4f1e8)` | `var(--panel-bg, #1d2427)` |
| `--rm-line` | 테두리 | `var(--line, #a3b18a)` | `var(--line, #4a595d)` |
| `--rm-text` / `--rm-text-soft` | 본문 / 보조 | `var(--text)` / `var(--text-soft)` | 동일 |
| `--rm-accent` | 선택 테두리·활성 표시 | `var(--brand-strong, #3a5a40)` | `#9bd3a3` |
| `--rm-accent-contrast` | accent 위 글자 | `#ffffff` | `#0f1517` |
| `--rm-accent-soft` | 선택 배경(B3의 청록 대체) | `rgba(88, 129, 87, 0.16)` | `rgba(155, 211, 163, 0.16)` |
| `--rm-visited` / `-soft` | '방문한 곳' 글자/배경 | `#2e6b3f` / `#dde4d9` | `#86c79b` / `#364c44` |
| `--rm-wanted` / `-soft` | '가고 싶은 곳' 글자/배경 | `#8a5a12` / `#efe5d2` | `#e0b25a` / `#4a4736` |
| `--rm-search-badge` | 검색 후보 번호 배지 배경(글자 흰색) | `#2f6aa8` (5.6:1) | `#2f6aa8` |
| `--rm-danger-text` | 경고 글자(버튼 아님) | `#a3402c` | `#e88776` |
| `--rm-focus` | focus-visible 외곽선 | `var(--focus-ring, #588157)` | `#9bd3a3` |
| `--rm-shadow` | 떠 있는 패널 그림자 | `0 6px 22px rgba(0, 0, 0, 0.2)` | `0 6px 22px rgba(0, 0, 0, 0.45)` |

대비(WCAG): `--rm-visited` #2e6b3f on #faf8f2 6.0:1, `--rm-wanted` #8a5a12 on #faf8f2 5.6:1, 다크 `#e0b25a` on #20292c 7.5:1, `#86c79b` on #20292c 7.5:1 이상. **주의:** 올리브 활성 테마에서 `--income/--expense/--danger`가 모두 녹색 계열이다(style.css 33332~33335행). 상태 색에 이 전역 변수를 쓰지 말고 위 `--rm-*`를 쓴다. 삭제 버튼은 전역 `.button--danger`(실제 빨강으로 렌더됨, style.css 33580·33778행)를 그대로 쓴다.

지도 핀·클러스터 색은 `TravelMyMapClusterPanel`이 정하므로 **바꾸지 않는다**(위치 후보 핀 `planColorHex: '#357CCB'`, Workspace 56행 포함). 바꾸는 것은 목록의 번호 배지뿐이다.

### 3.6 전역 CSS 대응 규칙 (구현 시 필수)

1. 전역 CSS가 `button`/`.button`의 배경·테두리를 `!important`로 덮는다. 목록 카드 main 버튼·상세 버튼처럼 배경이 없어야 하는 버튼은 **루트 접두사 + `!important`**로 덮는다. 예:
   ```css
   .record-map-sidebar .record-map-list-card .record-map-list-card__main,
   .record-map-sidebar .record-map-list-card .record-map-list-card__detail {
     background: transparent !important; border-width: 0 !important; box-shadow: none !important;
   }
   ```
   (scoped 컴파일 후 `[data-v]`가 붙어 0,3,1 이상이 되어 전역 0,2,x를 이긴다. 그래도 지면 `:global(html body .app-shell .record-map-workspace ...)` 형태를 쓴다 — Workspace 624~626행이 이미 쓰는 방식.)
2. 선택 상태(aria-pressed="true")는 **배경 대신 테두리·굵기·왼쪽 3px 띠**로 표시해 전역 배경 강제와 싸우지 않는다. 띠는 `box-shadow: inset 3px 0 0 var(--rm-accent) !important`.
3. `border-radius`는 전역에서 0으로 강제된다. 둥근 모양을 전제로 하지 않는다.
4. 다이얼로그 안 버튼은 전역 `.button`, `.button--primary`, `.button--ghost`, `.button--danger` 클래스를 그대로 쓴다.
5. 여행 모드 헤더 버튼 2열 grid는 전역 규칙(style.css 7016~7023, 15483~15500, 15554~15570, 30045~30046행)을 이겨야 한다. scoped 선택자 앞에 `.workspace-stack .travel-my-map-header`를 붙이고 `display`, `grid-template-columns`, `width`, `flex`에 `!important`를 쓴다. 적용 후 브라우저에서 계산된 스타일을 반드시 확인한다(6.5절).
6. 라이트·다크 두 테마의 계산된 스타일을 각 단계에서 직접 확인한다.

### 3.7 e2e가 의존하는 셀렉터·이름 계약 (바꾸지 말 것)

`frontend/e2e/record-map.spec.js`(780행, 데스크톱+모바일 각 26개 내외)가 다음에 의존한다.

| 종류 | 값 |
|---|---|
| Hub 버튼 | `여행 · 일정 · 경로`(정확히), `내 장소`(exact) |
| 클래스 | `.record-map-workspace`, `.record-map-frame`, `.record-map-sidebar`, `.record-map-sidebar__header`, `.record-map-sidebar__places`, `.record-map-sidebar__pagination`, `.record-map-list-card`, `.record-map-list-card__main`, `.record-map-list-filter` (+ `summary`), `.record-map-diary`, `.record-map-empty`, `.record-map-tools` (+ `summary`, `.travel-cluster-map__zoom`), `.record-map-add > summary`, `dialog.record-map-dialog`, `[data-place-id]` + `is-selected`, `.record-map-playback`(0개여야 함) |
| 버튼 이름 | `＋ 장소 추가`, `사진으로 기록`, `방문 기록 남기기`, `카테고리·모음 관리`, `여행 장소 가져오기`, `모음 공유`, `목록 열기`/`목록 접기`(aria-label), `장소 목록`, `모음`, `일상 기록`, `내 장소 검색`, `새 위치 찾기`, `지도 검색`(aria-label), `이 위치 저장`, `{장소명} 지도에서 보기`(aria-label), `방문 기록 보기`, `전체 화면`/`전체 화면 종료`, `새로고침`, `창 닫기`(aria-label), `장소 저장`, `방문 기록 저장`, `모음 저장`, `모음 삭제`, `장소·모음 수정`, `공개 공유 링크 만들기`, `공유 종료`, `장소 가져오기`, `추가`, `검색`(다이얼로그 지도 검색) |
| 라벨 | `장소 이름`, `위도`, `경도`, `새 카테고리 이름`, `사진 · 선택`, `국가·지역 및 좌표 직접 입력`(텍스트), `제목`, `날짜`, `메모`, `첨부 사진`, `이름`, `생성된 공유 주소`, combobox `방문 상태`, `장소 모음`, textbox `기록 시작일`, searchbox `저장 장소 검색`, `주소·지역 검색` |
| 다이얼로그 이름 | `장소 저장`, `장소 수정`, `방문 기록 남기기`, `내 장소 모음`, `여행 장소를 모음에 추가`, `모음 지도 공유`, 장소명(상세) |
| 동작 | 상세에서 Esc 1회 → 사진→상세, 2회 → 닫힘(전체 화면 유지). 모바일 폭 ≤760에서 패널 펼침 시 `.record-map-tools` 숨김. 패널·도구가 지도 캔버스 안에 들어와야 함. 위치 목록 선택 시 확대 15. |
| 여행 모드 | heading `내 여행 지도`, 버튼 `새 여행 만들기`, `여행 정보 수정`, `장소·사진 추가`, `지도에 장소 찍기`, `지도에서 확인`, `만들고 지도 열기`, `저장하고 지도 열기`, `.travel-setup-workflow`, `.travel-map-trip-select select`, `.travel-map-workbench`, `핀 보기`/`클러스터 보기`, `.travel-unified-shell` |

`frontend/e2e/travel-map-display-modes.spec.js`, `travel-map-pin.spec.js`는 `TravelMyMapWorkspace`/`TravelMyMapClusterPanel`/`TravelPublicMapShareWorkspace`를 직접 마운트한다. `TravelMyMapClusterPanel.vue`, `TravelMapPanel.vue`는 **이번에 수정하지 않는다**(공통 컴포넌트, 회귀 범위가 큼).

### 3.8 목표 컴포넌트 트리

```
RecordMapHubWorkspace.vue            (수정) 루트 .record-map-hub, 모드 3개 + hash 동기화 + 모바일 헤더 압축 CSS 이전
├─ RecordMapWorkspace.vue            (수정) 소유자 헤더 제거, 루트 수식 클래스, 다이얼로그 구조 개선, lib 사용
│   ├─ RecordMapSidebar.vue          (수정) 컨트롤 고정·카드 압축·읽기 전용 탭 정리·토큰
│   └─ RecordMapDialog.vue           (수정 소량) 스티키 푸터를 위한 스타일만
├─ TravelWorkspace.vue               (수정 소량) 중복 '공유받은 여행' 버튼/분기 제거
│   └─ TravelMyMapWorkspace.vue      (수정 소량) 헤더 버튼 그룹화·모바일 2열
└─ RecordSharingWorkspace.vue        (변경 없음) Hub의 '공유받은 여행' 모드에서 kind="TRAVEL"로 사용
새 파일:
  frontend/src/components/record-map/recordMapTokens.css
  frontend/src/lib/recordMapView.js
  frontend/tests/unit/recordMapView.test.js
삭제:
  frontend/src/components/RecordMapPlaybackControls.vue   (import 0건 재확인 후)
```

---

## 4. 상태/데이터 설계

### 4.1 순수 함수 — `frontend/src/lib/recordMapView.js` (새 파일)

모두 부수효과 없는 함수로 만들고 `tests/unit/recordMapView.test.js`로 고정한다. **원래 식을 그대로 옮긴다.**

```js
// RecordMapWorkspace 45행: 뒤쪽(list)이 같은 id를 덮어씀. 순서 = Map 삽입 순서.
export function mergePlacesById(mapPlaces = [], listPlaces = []) {
  return [...new Map([...mapPlaces, ...listPlaces].map(p => [p.id, p])).values()]
}

// RecordMapWorkspace 117~120행과 동일한 결과를 O(V)로: 장소별 '첫 번째로 사진이 있는 방문'의 첫 사진 photoUrl.
// 탐색 순서: listVisits 전체 → mapVisits 전체 (원래 [...list, ...map].find 순서)
export function buildFirstPhotoIndex(listVisits = [], mapVisits = []) // → Map<placeId, photoUrl>
//   구현: for (const v of [...listVisits, ...mapVisits]) if (v.photos?.length && !index.has(v.placeId)) index.set(v.placeId, v.photos[0].photoUrl)
//   호출부: thumbnail(id) = index.get(id) ? buildThumbnailUrl(index.get(id), 'mini') : ''

// RecordMapWorkspace 53~56행: 위치 후보 → 지도 마커
export function buildLocationMarkers(locationResults = [], focusedId = null) {
  return locationResults.map((location, index) => ({ ...location,
    title: location.name, placeName: location.name, recordMap: true, recordMapLocation: true, searchResultNumber: index + 1,
    searchResultFocused: focusedId === location.id,
    planName: '검색 후보 · 저장 전', planColorHex: '#357CCB' }))
}

// RecordMapWorkspace 60행
export function sortDiaryVisits(visits = []) {
  return visits.filter(v => v.date).sort((a, b) => (b.date + (b.time || '')).localeCompare(a.date + (a.time || '')))
}

// RecordMapWorkspace 369행
export function placeIdFromMapItem(item) {
  return Number(item?.recordMapPlaceId ?? item?.placeId ?? Number(String(item?.id || item?.clusterId || '').split(':').at(-1)))
}

// C3 수정: '대한민국 · 서울 · 방문한 곳' / 국가·지역 없으면 '방문한 곳'
export function formatPlaceMeta(place = {}) {
  return [place.country, place.region, place.visited ? '방문한 곳' : '가고 싶은 곳'].filter(Boolean).join(' · ')
}

// 필터 요약 라벨용: collectionId, from, to 중 값이 있는 개수(state는 별도 select라 제외 — Sidebar 45행 기존 '적용 중' 조건과 동일 대상)
export function countActiveFilters(filters = {}) {
  return ['collectionId', 'from', 'to'].filter(key => Boolean(filters[key])).length
}

// Hub
export function hubModeForRoute(route) { return route === 'travel' ? 'records' : 'travel' }   // Hub 7, 18행과 동일
export function hubHashForMode(mode) { return mode === 'records' ? 'travel' : mode === 'travel' ? 'my-map' : '' }
```

주의: `buildFirstPhotoIndex`를 쓰면 `listPlaces` computed(59행)는 index computed 하나 + map 한 번으로 바뀐다. 결과 문자열이 원래 `thumbnail()`과 같아야 한다(단위 테스트로 비교).

### 4.2 RecordMapWorkspace computed 재구성 (동작 동일)

```js
const allPlaces = computed(() => mergePlacesById(snapshot.value.places, listSnapshot.value.places))
const baseMapData = computed(() => buildRecordMapData(mapSnapshot.value))
const locationMarkers = computed(() => searchMode.value === 'location' ? buildLocationMarkers(locationResults.value, foundLocation.value?.id ?? null) : [])
const data = computed(() => locationMarkers.value.length
  ? { ...baseMapData.value, markers: [...baseMapData.value.markers, ...locationMarkers.value] }
  : baseMapData.value)
const firstPhotoIndex = computed(() => buildFirstPhotoIndex(listSnapshot.value.visits, snapshot.value.visits))
const listPlaces = computed(() => listSnapshot.value.places.map(p => ({ ...p, thumbnail: thumbnailFor(p.id) })))
const diaryVisits = computed(() => sortDiaryVisits(listSnapshot.value.visits))
```

- 원래 `data`는 `buildRecordMapData` 결과의 `markers`에 **push로 변이**했다. 새 코드는 변이하지 않는다. `baseMapData.value.markers`를 절대 push하지 말 것(캐시된 computed 오염).
- `foundLocation`의 비교 기준은 원래 `foundLocation.value?.id === location.id`이다. `focusedId`에 `null`을 넘겼을 때 id가 `undefined`인 후보가 true가 되지 않도록 `focusedId != null && focusedId === location.id`로 구현해도 원래 동작과 같다(후보 id는 `normalizeLocationResults`가 항상 `location:{n}`으로 채움 — `recordMapLocationSearch.js` 12행). 둘 중 하나를 택하고 테스트에 적는다.
- 효과: 위치 후보 변경 시 사진 핀/클러스터 배열 identity가 유지된다(지도 컴포넌트의 watch 자체는 markers 변화로 1회 실행되는 것이 정상).

### 4.3 Hub 모드와 URL 동기화

상태: `mode` = `'records' | 'travel' | 'shares'`.

```js
import { hubModeForRoute, hubHashForMode } from '../lib/recordMapView'
const mode = ref(hubModeForRoute(props.route))
watch(() => props.route, route => { mode.value = hubModeForRoute(route) })          // 기존과 동일
// recordFocusRequest / placeFocusRequest watch는 기존 그대로 유지

function changeMode(value) {
  if (value === mode.value) return
  const targetHash = hubHashForMode(value)                 // 'travel' | 'my-map' | ''
  const currentHash = window.location.hash.replace(/^#/, '')
  if (targetHash && targetHash !== currentHash && hubModeForRoute(targetHash) !== hubModeForRoute(props.route)) {
    // route가 바뀌는 경우: App의 hashchange 처리(handleHashChange)가 이탈 가드 confirm을 하고,
    // 취소하면 App이 hash를 되돌린다. mode는 route watch가 바꾼다. 여기서 mode를 직접 바꾸지 않는다.
    window.location.hash = targetHash
    return
  }
  // route가 그대로인 경우(예: shares ↔ 같은 route의 모드, travel-money 상태에서 '여행' 재선택 등): 기존 confirm 후 직접 전환
  if (leaveGuard.active && !window.confirm(leaveGuard.message || '작성 중인 내용을 저장하지 않고 화면을 바꿀까요?')) return
  mode.value = value
}
```

동작 표 (구현 후 수동·e2e로 확인):

| 현재 route / mode | 누른 버튼 | 결과 |
|---|---|---|
| `travel` / records | 여행 · 일정 · 경로 | hash `#my-map` → route `my-map` → mode travel (TravelWorkspace route `my-map` → 지도) |
| `my-map` / travel | 내 장소 | hash `#travel` → mode records |
| `travel-money`(달력·가계부 진입) / travel | 내 장소 | hash `#travel` → records |
| `photo-album`(ALBUM) / travel | 여행 · 일정 · 경로 | 이미 travel → 아무 일 없음 (기존과 동일) |
| `travel` / records | 공유받은 여행 | route 그대로, confirm 후 mode shares |
| `travel` / shares | 내 장소 | targetHash `travel` == currentHash → 직접 mode records |
| `my-map` / shares | 여행 · 일정 · 경로 | 직접 mode travel |
| 새로고침 `#my-map` | — | 여행 모드로 열림 (A5 해결) |

- App은 `my-map`을 이미 알고 있다: `routeMeta['my-map']`(App.vue 175행), `travelRouteKeys`(269행), 헤더 활성 `travelHeaderRouteKeys`(270행), 제목은 `travel`로 매핑(272행). **App.vue 수정 불필요.**
- `navigate()`가 아닌 hash 변경이므로 펫 안내·`calendarLedgerFocusRequest` 초기화 같은 부수효과가 없다(App.vue 415~429행 `applyHashRoute`).
- `shares` 모드는 URL에 남기지 않는다(새 route 키가 필요해 App 수정이 생김 → P2).

### 4.4 루트 수식 클래스 (RecordMapWorkspace)

```js
const rootClass = computed(() => ({
  'record-map-workspace--readonly': readOnly.value,
  'record-map-workspace--embedded': Boolean(props.calendarGrantId),          // 통합 달력 임베드
  'record-map-workspace--shared': Boolean(props.token) && !props.calendarGrantId, // #record-map-share
}))
```

- 모바일 헤더 압축 CSS는 Hub(`body:has(.record-map-hub)`)로 옮긴다(D4). 공유 route에 필요한 `.app-shell` padding/`.theme-switcher` 정적 배치만 `body:has(.record-map-workspace--shared)`로 Workspace에 남긴다. `--embedded`일 때는 어떤 전역 규칙도 걸리지 않아야 한다.
- 전체 화면 시 펫/테마 스위처 숨김(613~614행)은 그대로 둔다(임베드에서도 전체 화면이면 숨기는 것이 맞음).

### 4.5 추가 버튼 위치 규칙

```js
const actionsTarget = computed(() => sidebarOpen.value
  ? (sidebarActionsAnchor.value || mapActionsAnchor.value)
  : mapActionsAnchor.value)
```

(기존 30행은 `compact.value && sidebarOpen.value`였다. 데스크톱도 같은 규칙으로 통일한다.) Teleport 원본(`<Teleport ...><div v-if="!readOnly" class="record-map-add-actions">…`)은 헤더 밖, 템플릿 루트 바로 아래로 옮긴다. `photoInput` ref가 Teleport 안에 있으므로 함께 옮겨지고 동작은 같다.

---

## 5. 단계별 작업 지시

### 공통: 실행 환경

- 다른 작업자가 5173 포트 dev 서버로 Playwright를 돌릴 수 있다. **충돌을 피하려면 별도 포트를 쓴다.**
  ```powershell
  # 터미널 1 (백그라운드로 유지)
  cd C:\project\calen\frontend; npx vite --host 127.0.0.1 --port 5180 --strictPort
  # 터미널 2
  cd C:\project\calen\frontend
  $env:E2E_BASE_URL='http://127.0.0.1:5180'; $env:E2E_START_LOCAL_SERVER='0'
  npx playwright test e2e/record-map.spec.js --config playwright.config.js --output=..\..\pw-out-recordmap
  ```
  `--output`을 저장소 밖으로 주어 다른 작업자의 `test-results/`를 지우지 않는다(Playwright는 실행 시 출력 폴더를 비운다). 작업이 끝나면 띄운 vite를 종료한다.
- 단위 테스트: `cd frontend; npm run test:unit` (node:test, vitest 아님). 빌드: `cd frontend; npm run build`.
- 회귀 묶음(이 문서에서 "기록·지도 회귀"라고 부름):
  `npx playwright test e2e/record-map.spec.js e2e/travel-map-display-modes.spec.js e2e/travel-map-pin.spec.js e2e/mobile-travel-modal.spec.js e2e/record-sharing.spec.js --config playwright.config.js --output=..\..\pw-out-recordmap`
- 빌드가 통합 달력 파일 때문에 일시적으로 깨지면 그 파일을 고치지 말고 잠시 후 재시도한다.

### Step 0. 기준선 확인

- 목적: 시작 전 상태 기록.
- 할 일: `git status --short`로 무관한 미커밋 파일 목록을 메모. `npm run test:unit`(작성 시점 118개 통과), `npm run build`, 기록·지도 회귀 실행(작성 시점 record-map/travel-map-display-modes/travel-map-pin/mobile-travel-modal 4개 spec: 90 통과, 8 skip — mobile-travel-modal의 실계정 테스트는 환경변수 없으면 skip이 정상).
- 완료 기준: 결과를 작업 메모에 남김. 실패가 있으면 이번 변경과 무관함을 확인해 둔다.

### Step 1. 순수 함수 lib + 단위 테스트 (UI 변경 없음, P0 기반)

- 수정/생성: 생성 `frontend/src/lib/recordMapView.js`, `frontend/tests/unit/recordMapView.test.js`
- 내용: 4.1절 함수 전부 구현. 테스트(최소 12개):
  1. `mergePlacesById` 중복 id는 list 쪽 값, 순서는 첫 등장 순서.
  2. `buildFirstPhotoIndex`가 원래 `thumbnail()` 식(`[...list, ...map].find(v => v.placeId === id && v.photos?.length)?.photos[0]`)과 같은 photoUrl을 돌려줌 — 원래 식을 테스트 안에 그대로 써서 여러 장소에 대해 비교. 사진 없는 방문, 빈 배열, 같은 장소가 list/map 양쪽에 있는 경우 포함.
  3. `buildLocationMarkers` 번호 1부터, focused 표시, `planColorHex '#357CCB'`, 원본 필드 유지.
  4. `sortDiaryVisits` 날짜 없는 방문 제외, 같은 날짜는 시간 내림차순, 시간 없는 항목 처리.
  5. `placeIdFromMapItem`: `recordMapPlaceId`, `placeId`, `'place:12'`, `clusterId`, 잘못된 값 → NaN.
  6. `formatPlaceMeta`: 국가·지역 모두/일부/없음, visited true/false. 앞 가운뎃점 없음.
  7. `countActiveFilters`.
  8. `hubModeForRoute`: `travel`→records, `my-map`/`travel-money`/`photo-album`/`travel-log`/`public-trips`→travel. `hubHashForMode`: records→'travel', travel→'my-map', shares→''.
- 완료 기준/검증: `cd frontend; node --test tests/unit/recordMapView.test.js` 통과, `npm run test:unit` 전체 통과.

### Step 2. 토큰 파일 + 죽은 코드 정리 (P0)

- 수정/생성: 생성 `frontend/src/components/record-map/recordMapTokens.css`. 수정 `RecordMapWorkspace.vue`, `RecordMapSidebar.vue`, `RecordMapHubWorkspace.vue`. 삭제 `RecordMapPlaybackControls.vue`.
- 내용:
  1. 3.5절 표대로 토큰 파일 작성. Hub와 Workspace `<script setup>`에서 import.
  2. Hub 루트 `<div class="workspace-stack">` → `<div class="workspace-stack record-map-hub">`.
  3. Sidebar의 `var(--accent-soft, …)`, `var(--accent, #009E8C)`, `var(--input-bg, var(--panel-bg))`, `#009e8c18/16/22`, `#357ccb`, `var(--panel-bg)`, `var(--line)`, `var(--text)`를 `--rm-*`로 교체(`--rm-accent-soft`, `--rm-accent`, `--rm-surface`, `--rm-search-badge`, `--rm-panel`, `--rm-line`, `--rm-text`). focus-visible 외곽선은 `--rm-focus`.
  4. Workspace scoped의 `#53646877` 테두리 → `var(--rm-line)`, 도구 막대/메뉴 배경 → `var(--rm-panel)`, 그림자 → `var(--rm-shadow)`. `#365b45`(624~625행)는 `var(--rm-accent)`와 `var(--rm-accent-contrast)`로.
  5. 죽은 CSS 삭제(E4): Workspace의 `.record-map-filters*`, `.record-map-place-grid*`, `.record-map-state*`, `.record-map-floating-actions`, `.record-map-diary*`, `.record-map-empty`, `.record-map-place-grid h3`. 삭제 전 각 클래스가 Workspace 템플릿에 없는지 grep으로 확인(`.record-map-diary`, `.record-map-empty`는 Sidebar 템플릿에만 있고 Sidebar scoped에 자체 스타일이 있음).
  6. `RecordMapPlaybackControls.vue` 삭제: `grep -r "RecordMapPlaybackControls" frontend/src frontend/e2e frontend/tests`가 0건인지 확인 후 삭제.
- 완료 기준: 화면이 시각적으로 크게 바뀌지 않음(선택 배경만 청록 → 올리브). 라이트/다크 캡처로 확인.
- 검증: `npm run build`, `npm run test:unit`, `npx playwright test e2e/record-map.spec.js ...`(공통 명령).

### Step 3. 사이드바 P0 수정 — 칩 버그, 카드 압축, 모바일 시트 (P0)

- 수정: `RecordMapSidebar.vue` (필요 시 `RecordMapWorkspace.vue` 스타일 일부)
- 내용:
  1. **B1 수정:** `.record-map-categories`, `.record-map-sidebar__tabs`, `.record-map-list-filter`, `.record-map-suggestions`, `.record-map-sidebar__pagination`, `.record-map-sidebar__hint`에 `flex-shrink: 0`. 칩 줄 `min-height: 40px`, 칩 버튼 `min-height: 36px`(모바일 40px).
  2. 헤더: `<strong>`을 `{{ readOnly ? '공유 장소와 기록' : '내 장소' }}<small> · {{ snapshot.totalPlaces || 0 }}곳</small>`로. (`.record-map-sidebar__header` 클래스 유지.)
  3. 검색 모드 버튼: 높이 32px, 글자 .8rem의 2분할 세그먼트. 이름 `내 장소 검색`/`새 위치 찾기` 유지.
  4. **필터 한 줄화:** `.record-map-list-filter`를 `display:flex; flex-wrap:wrap; gap:6px; align-items:end`. 방문 상태 label `flex: 1 1 140px`, `details` `flex: 0 0 auto`, `details[open] { flex-basis: 100% }`. 요약 텍스트는 `필터` + (적용 수가 있으면 ` · ${countActiveFilters(filters)}`). Sidebar에서 lib 함수를 import해서 쓴다. combobox 이름 `방문 상태`, `장소 모음`, textbox `기록 시작일`은 그대로.
  5. **읽기 전용 탭 정리(B4):** `모음` 탭 버튼에 `v-if="!readOnly"`. 읽기 전용에서 `tab === 'collections'`가 될 수 없지만 방어로 `section v-if="tab === 'collections' && !readOnly"`. `필터 > 장소 모음` label도 `v-if="!readOnly"`(현재 disabled select).
  6. **카드 압축(B2):** `.record-map-list-card`를 `grid-template-columns: minmax(0, 1fr) auto; align-items: center; padding: 8px; gap: 6px`. 썸네일/플레이스홀더 48x48. 카드 두 번째 줄 `{{ place.visited ? … }}`은 `formatPlaceMeta`가 아닌 기존 문구 유지(방문 수 포함). `방문 기록 보기` 버튼은 오른쪽 열, `font-size:.75rem`, `min-height:36px`(모바일 44px), 밑줄 텍스트 버튼. 3.6-1 규칙으로 main/detail 버튼 배경 제거. 선택 카드는 `box-shadow: inset 3px 0 0 var(--rm-accent)` + `border-color: var(--rm-accent)`.
     - '지도에서 선택한 장소' 카드(Sidebar 65행)와 위치 후보 카드(28~31행), 일상 기록 카드(61행)도 같은 그리드 규칙을 쓰되 위치 후보의 `이 위치 저장` 버튼은 오른쪽 열에 둔다.
  7. 이미지(E3): `<img … width="48" height="48" decoding="async" loading="lazy">`, 일상 기록 사진 `width="64" height="64"`.
  8. 선택 상태 스타일 통일(E1): 검색 모드/탭/칩의 `[aria-pressed="true"]`는 `border-color: var(--rm-accent); box-shadow: inset 0 -3px 0 var(--rm-accent); font-weight: 700`(배경은 `--rm-accent-soft`, 전역에 덮이면 무시해도 됨).
  9. **모바일 시트(D1)** (`@media (max-width:760px)`):
     - `.record-map-sidebar.is-open { top:auto; height:72%; max-height:none; }` (지도 프레임 기준). 접힘은 기존 유지.
     - 펼침 시 헤더는 한 줄: `.record-map-sidebar__header { flex-wrap: nowrap }`, 제목 `flex:1; min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap`.
     - 헤더 안에 Teleport된 추가 버튼(`.record-map-add-actions`)을 가로 배치: Workspace scoped에 `:global(.record-map-sidebar__header .record-map-add-actions) { display:flex !important; flex-direction:row; gap:4px }` (Workspace 621행의 `display:grid`는 지도 도구 막대 안에서만 적용되도록 `.record-map-tools .record-map-add-actions`로 범위 축소).
     - 컨트롤 간격 `gap:5px`, 패딩 8px. 카드 2~3장이 보이는지 390x844, 360x640 캡처로 확인.
- 완료 기준: 01/02 캡처 기준으로 데스크톱 첫 화면에 카드 4장 이상, 모바일 펼침에 카드 2장 이상 보임. 칩 줄이 보이고 클릭 가능.
- 검증: 공통 빌드/단위 + `record-map.spec.js`(특히 'map overlays fit…', 'a dated visit and photo…', 'an opted-in public map…', 'location lookup…').

### Step 4. Hub 셸 개편 — 모드 바, 공유받은 여행, URL 동기화, 소유자 헤더 제거 (P1)

- 수정: `RecordMapHubWorkspace.vue`, `RecordMapWorkspace.vue`, `TravelWorkspace.vue`
- 내용:
  1. Hub 모드 3개: `내 장소`, `여행 · 일정 · 경로`, `공유받은 여행`. 마지막 버튼에 `useRecordShareNotifications()`의 `shareCounts.travel` 배지(TravelWorkspace 396행과 같은 마크업·`aria-label`: `` `승인 대기 ${n}건` ``, 클래스 `record-share-badge`). `aria-pressed` 유지.
  2. `mode === 'shares'`이면 `<RecordSharingWorkspace kind="TRAVEL" />`(defineAsyncComponent). `RecordMapWorkspace`/`TravelWorkspace`는 렌더하지 않는다(v-if/v-else-if/v-else).
  3. `changeMode`를 4.3절 코드로 교체. `watch(() => props.route …)`는 `hubModeForRoute` 사용. focus 요청 watch 2개는 그대로.
  4. 모드 바 스타일: `nav.panel.record-map-mode-switch`(클래스 유지) `display:flex; align-items:center; gap:4px; padding:6px 8px; min-height:0`. 버튼 `min-height:40px`(모바일 44px), 모바일에서는 3개가 한 줄에 들어가도록 `flex:1 1 0; font-size:12px; padding:4px 6px; white-space:nowrap`. 활성 버튼은 기존처럼 `button--primary` 클래스.
  5. **모바일 헤더 압축 CSS 이전(D4):** RecordMapWorkspace 644~658행의 `:global(body:has(.record-map-workspace) …)` 규칙 중 `.topbar*`, `.record-map-mode-switch*` 규칙을 Hub `<style scoped>`로 옮기고 선택자를 `body:has(.record-map-hub)`로 바꾼다. `.app-shell` padding-top(644행)과 `.theme-switcher` 규칙(645~646행)은 Hub로 옮긴 사본(`body:has(.record-map-hub)`)과, Workspace에 남기는 사본(`body:has(.record-map-workspace--shared)`) 두 벌로 둔다. Workspace에는 `body:has(.record-map-workspace)` 형태의 전역 규칙이 **전체 화면 규칙(613~614행) 외에는 남지 않아야** 한다.
  6. Workspace 루트에 4.4절 `rootClass` 바인딩.
  7. **소유자 헤더 제거(A1):** `<header class="panel record-map-header">`에 `v-if="readOnly"`. Teleport 블록은 header 밖(루트 div 첫 자식)으로 이동. 읽기 전용 헤더는 `padding:6px 12px`, h2 `font-size:1rem; margin:0`, `record-map-note`는 같은 줄 배지로.
  8. 4.5절 `actionsTarget` 규칙 적용. 지도 도구 막대 폭: 추가 버튼이 없을 때 `width:80px`. 기존 `.record-map-add-primary` 스타일은 유지하되 사이드바 헤더 안에서는 `font-size:12px; padding:6px 8px`.
  9. TravelWorkspace: 396행 `공유받은 여행` 버튼, 437행 `<RecordSharingWorkspace v-if="primaryTab === 'record-shares'" …/>`, 5행 import, 4·6행 `useRecordShareNotifications`를 제거한다(Hub로 일원화). `isHubVisible`의 `'record-shares'` 조건(329행)은 남겨도 무해하지만 함께 정리한다. 다른 곳에서 `primaryTab = 'record-shares'`를 설정하지 않는지 grep으로 확인.
- 완료 기준: 데스크톱에서 지도 상단 y가 현재보다 약 60px 이상 올라감. `#my-map` 새로고침 시 여행 모드. 뒤로 가기로 모드 복귀. 통합 달력에서 공유 지도 원본을 열어도 모바일 앱 헤더가 압축되지 않음(수동 확인: 통합 달력은 수정 금지이므로 e2e 추가 대신 6.5절 캡처로).
- 검증: 공통 + 기록·지도 회귀 전체. 'travel creation…', 'record maps never expose travel playback…'(모드 왕복), 'a collection share is private…'(읽기 전용 h2) 통과 필수. 6.4절 신규 테스트 1·2 추가.

### Step 5. 장소 등록·상세 다이얼로그 개선 (P1)

- 수정: `RecordMapWorkspace.vue`, `RecordMapDialog.vue`, `frontend/e2e/record-map.spec.js`(한 곳)
- 내용:
  1. **장소 폼 구획(C2):** 기존 필드를 다음 순서의 `<section class="record-map-form__section">`(각 `<h3>` .85rem)으로 묶는다. 라벨 텍스트는 그대로.
     - `기본 정보`: 장소 이름, 장소 설명 · 선택
     - `위치`: 안내문, `TravelMapPanel`, `현재 위치 사용`, 그리고 새 상태 줄 `<p class="record-map-note" role="status">`: 좌표가 있으면 `선택한 위치 · {lat.toFixed(5)}, {lng.toFixed(5)}`, 없으면 `아직 위치를 선택하지 않았습니다.` (숫자 변환은 `Number(placeForm.latitude)`; 빈 문자열이면 '없음' 분기)
     - `카테고리`: 기존 fieldset 그대로
     - `사진과 방문 기록`: 이미 방문한 곳 표시, 사진 · 선택(+ 안내), 방문 날짜와 이야기도 기록(+ 하위 폼)
     - `details.record-map-advanced` 국가·지역 및 좌표 직접 입력, 여행 원본 안내
  2. **스티키 푸터:** 제출 버튼을 `<div class="record-map-form__footer">`로 감싼다(폼 안 유지 — submit 동작 보존). CSS `position:sticky; bottom:-18px; margin:0 -18px -18px; padding:12px 18px; background:var(--rm-panel); border-top:1px solid var(--rm-line); z-index:1`(모바일 600px 이하는 12px 기준). `RecordMapDialog`의 스크롤 컨테이너는 `<dialog>` 자신(overflow:auto)이고 `fieldset.record-map-dialog__body`는 스크롤 컨테이너가 아니어야 sticky가 동작한다 — fieldset에 overflow를 주지 말 것. 방문 기록 폼도 같은 푸터를 쓴다.
  3. **위치 선택 지도 도구 축소(C1):** Workspace scoped에 
     ```css
     .record-map-form :deep(.travel-map__toolbar-group:has(> .travel-map__toolbar-label)) { display: none; }
     ```
     (시야·크기 그룹만 숨김. 검색 폼과 `전체 보기/전체 화면` 그룹은 유지.) `TravelMapPanel.vue`는 수정하지 않는다. 숨겨진 버튼은 getBoundingClientRect가 0이 되므로 **같은 단계에서** `record-map.spec.js` 'fullscreen detail closes one layer…' 테스트의 경계 검사(현재 432~439행)를 보이는 요소만 검사하도록 고친다:
     ```js
     const controlBounds = await dialog.locator('input, select, textarea, .travel-map__toolbar-button').evaluateAll(nodes => nodes
       .map(node => node.getBoundingClientRect()).filter(rect => rect.width > 0 && rect.height > 0)
       .map(rect => ({ left: rect.left, right: rect.right })))
     expect(controlBounds.length).toBeGreaterThan(3)
     ```
     (원래 검증 의도 — 보이는 컨트롤이 다이얼로그 가로 경계 안에 있음 — 는 그대로.)
  4. **상세(C3):** 587행을 `<p>{{ formatPlaceMeta(selectedPlace) }}</p>`로. 장소가 속한 모음 칩 줄 추가: `snapshot.collections.filter(c => selectedPlace.collectionIds?.includes(c.id))`를 색 사각형(`c.colorHex`)+이름 칩으로(없으면 렌더 안 함). 588행 메모 `v-if="selectedPlace.memo"`. 593행 방문 메모 `v-if="visit.memo"`. 598행 페이지 버튼 `v-if="!readOnly && (detailPage > 0 || detailHasMore)"`. 방문 카드 사진 썸네일 `width/height` 지정, `decoding="async"`.
  5. **더보기 메뉴(C4):** `<details class="record-map-add">`에 `@keydown.esc.stop="closeAddMenu($event)"`; `closeAddMenu`는 `details.open = false` 후 `summary.focus()`. 메뉴가 열려 있을 때 문서 `pointerdown`이 details 밖이면 닫는 리스너를 `toggle` 이벤트에서 등록/해제(언마운트 시 해제). 기존 '메뉴 안 버튼 클릭 시 닫힘'은 유지.
  6. 상태 칩: 상세 메타 옆 `방문한 곳/가고 싶은 곳`을 `--rm-visited`/`--rm-wanted` 글자색 칩으로(선택, 배경은 soft 토큰).
- 완료 기준: 04/05 캡처에서 저장 버튼이 항상 화면 하단에 보이고, 모바일 위치 지도 위 도구가 검색줄 + 전체 보기/화면만 남음. 상세에 앞 가운뎃점 없음.
- 검증: 공통 + `record-map.spec.js` 전체(다이얼로그 이름/라벨 계약 3.7절), 6.4절 신규 테스트 3.

### Step 6. RecordMapWorkspace 성능 정리 (P1)

- 수정: `RecordMapWorkspace.vue`
- 내용: 4.2절대로 `allPlaces`, `baseMapData`, `locationMarkers`, `data`, `firstPhotoIndex`, `listPlaces`, `diaryVisits`를 lib 함수로 교체. `selectPlace`(369행)의 id 계산을 `placeIdFromMapItem(item)`으로. `thumbnail(id)` 함수는 index 조회로 바꾸되 이름을 `thumbnailFor`로 바꾸고 다른 참조가 없는지 확인.
- 완료 기준: 동작 동일. 위치 검색 → 후보 선택 시 `buildRecordMapData`가 다시 호출되지 않음(임시 `console.count`로 확인 후 제거).
- 검증: 공통 + `record-map.spec.js`의 위치 검색 4개 테스트('location lookup…', 'numbered search pins…', 'related keyword…', 'a superseded location search…'), 'saved search covers other pages…'.

### Step 7. 여행 모드 헤더 정리 (P1)

- 수정: `TravelMyMapWorkspace.vue` (템플릿 1512~1527행 부근, scoped 스타일)
- 내용:
  1. `.travel-my-map-header__actions` 안의 버튼 6개를 그룹 3개로 감싼다: `<div class="travel-my-map-actions__group" role="group" aria-label="여행">`(새 여행 만들기, 여행 정보 수정), `aria-label="기록"`(장소·사진 추가, 지도에 장소 찍기, 경로 추가), `aria-label="공유"`(지도 공유). 각 그룹 앞에 시각 라벨 `<span class="travel-my-map-actions__label" aria-hidden="true">여행</span>`. 버튼 이름·클래스·핸들러·`:disabled`·`:aria-pressed`는 그대로.
  2. 데스크톱: 그룹 사이 구분선(왼쪽 1px `var(--line)`), 그룹 내부 gap 6px.
  3. 모바일(≤720px, 그리고 `:root[data-layout-mode='mobile']`): 라벨 숨김, `.travel-my-map-header__actions`와 그룹을 `display:contents`가 아닌 2열 grid로: 컨테이너 `display:grid !important; grid-template-columns:repeat(2, minmax(0,1fr)) !important; gap:6px`, 그룹 `display:contents`, 버튼 `width:auto !important; flex:none !important; min-height:44px`. `장소·사진 추가`는 `grid-column: 1 / -1`. 3.6-5 규칙으로 전역 규칙을 이기는지 계산된 스타일 확인.
  4. `.travel-map-trip-select`의 margin을 `.5rem 0`로. 클래스와 select 구조 유지.
- 완료 기준: 06b 모바일 캡처에서 헤더 버튼 영역 높이가 약 200px 이하. 데스크톱에서 그룹이 한 줄 또는 두 줄로 정돈.
- 검증: 공통 + `travel-map-display-modes.spec.js`('travel photo map uses the full workbench…'), `record-map.spec.js`의 여행 3개 테스트('travel creation…', 'periodless travel…', 'converting a dated trip…').

### Step 8. 신규 테스트, 접근성 점검, 시각 확인 마무리

- 수정: `frontend/e2e/record-map.spec.js`(신규 테스트 추가), 필요 시 각 컴포넌트 소폭
- 내용: 6.4절 신규 테스트 작성. 3.7절 계약 재점검. 모바일 터치 대상: `.record-map-tools .button` 모바일 `min-height:44px`(E2), 카드 상세 버튼 44px. 키보드: Tab으로 모드 바 → 패널 헤더 추가 버튼 → 검색 → 칩 → 목록 순서인지 확인. 6.5절 캡처로 라이트/다크 × 데스크톱/모바일 확인 후 임시 캡처 spec 삭제.
- 완료 기준: 부록 체크리스트 전부 체크.
- 검증: `npm run build`, `npm run test:unit`, 기록·지도 회귀 전체(데스크톱+모바일).

---

## 6. 테스트 계획

### 6.1 실행 명령

| 목적 | 명령 (PowerShell) |
|---|---|
| 빌드 | `cd C:\project\calen\frontend; npm run build` |
| 단위 전체 | `cd C:\project\calen\frontend; npm run test:unit` |
| 이번 단위만 | `node --test tests/unit/recordMapView.test.js tests/unit/recordMap.test.js tests/unit/recordMapLocationSearch.test.js tests/unit/recordMapPhotoLocation.test.js` |
| e2e 기록 지도 | (5장 공통처럼 5180 포트 서버 + 환경변수) `npx playwright test e2e/record-map.spec.js --config playwright.config.js --output=..\..\pw-out-recordmap` |
| 데스크톱만 | 위 명령 + `--project=chromium-desktop` |
| 특정 테스트 | 위 명령 + `-g "테스트 이름 일부"` |

### 6.2 단위 테스트

- 신규 `tests/unit/recordMapView.test.js` (Step 1 목록).
- 기존 `recordMap.test.js`, `recordMapLocationSearch.test.js`, `recordMapPhotoLocation.test.js`는 변경 없이 통과해야 한다.

### 6.3 기존 e2e 변경 매핑

| 테스트 | 변경 | 이유 |
|---|---|---|
| `record-map.spec.js` 'fullscreen detail closes one layer and the mobile form stays inside the viewport' | 컨트롤 경계 검사에서 크기 0인 요소 제외 + 보이는 컨트롤 수 > 3 단언 추가 (Step 5-3) | 시야·크기 그룹 숨김 |
| 그 외 전부 | 변경 없음 | 3.7절 계약 유지 |

`action()` 헬퍼(record-map.spec.js 128~132행)는 버튼이 보이면 바로 누르고 아니면 `.record-map-add > summary`를 연다 — 추가 버튼이 패널 헤더로 옮겨져도 그대로 동작한다. `tab()` 헬퍼는 `목록 열기`가 보이면 누른다 — 유지.

### 6.4 신규 e2e (`record-map.spec.js` 끝에 추가, 기존 `mock()` 재사용)

1. **'record map modes are deep-linkable and history restores them'**
   - `page.goto('/#my-map')` → heading `내 여행 지도` visible, 버튼 `여행 · 일정 · 경로`의 `aria-pressed="true"`.
   - `내 장소` 클릭 → `expect(page).toHaveURL(/#travel$/)`, `.record-map-frame .travel-map__canvas` visible.
   - `page.goBack()` → heading `내 여행 지도` 다시 visible.
   - `page.reload()` 후에도 여행 모드 유지.
   - `state.errors` 빈 배열.
2. **'shared travel entry is reachable from the record map hub with a pending badge'**
   - mock 추가: `/api/record-shares/counts` → `{ ledger: 0, travel: 2 }`, `/api/record-shares`(GET) → `{ items: [], page: 0, size: 10, totalElements: 0, totalPages: 0 }` (`page.route`를 `mock()` 뒤에 등록하면 먼저 매칭된다).
   - `/#travel` → 버튼 `공유받은 여행` 안에 `승인 대기 2건` 라벨 배지. 클릭 → heading `공유받은 여행` visible, `.record-map-frame` 0개. `내 장소` 클릭 → 지도 복귀, hash는 `#travel` 유지.
3. **'place form keeps the save action reachable and shows the chosen coordinates'**
   - `/#travel` → `action(page, '장소 저장')` → 다이얼로그 `장소 저장`.
   - `국가·지역 및 좌표 직접 입력` 펼쳐 위도 37.55/경도 126.97 입력 → 상태 문구 `선택한 위치 · 37.55000, 126.97000` 보임.
   - 저장 버튼(`장소 저장`)의 boundingBox가 viewport 안(스크롤 없이) — 모바일 프로젝트 포함.
   - 시야·크기 토글(`고정`, `따라가기`, `작게`) 버튼이 다이얼로그 안에서 보이지 않음(`toBeHidden`).
   - 상세: 국가·지역 없는 장소(fixture(true)의 '서울 카페')를 상세로 열어 메타가 `방문한 곳`으로 시작(앞 가운뎃점 없음), `이전 기록` 버튼 0개.
4. **'sidebar category chips are visible and the mobile sheet shows list cards'**
   - fixture(true), `/#travel`, `tab(page, '장소 목록')`.
   - 칩 버튼 `주말 맛집`의 boundingBox 높이 ≥ 32.
   - `.record-map-sidebar__places article` 첫 카드가 `.record-map-sidebar` 영역 안에 완전히 보임(모바일 포함).
   - 칩 `주말 맛집` 클릭 → overview 요청 params에 `collectionId: '10'`.

### 6.5 스크린샷 확인 방법

- 임시 spec `frontend/e2e/zz-recordmap-capture.tmp.spec.js`를 만들고(`record-map.spec.js` 1~138행의 fixture/mock/헬퍼를 복사), 테마는 `page.addInitScript(t => localStorage.setItem('calen-theme-mode', t), 'default' | 'toss')`로 지정. 촬영 대상:
  1. `/#travel` 첫 화면(데스크톱/모바일 × 라이트/다크), 모바일은 `목록 열기` 후 한 장 더
  2. `더보기` 메뉴 열림
  3. `장소 저장` 다이얼로그(모바일은 스크롤 없이 저장 버튼 보이는지)
  4. 장소 상세 다이얼로그
  5. `여행 · 일정 · 경로` 첫 화면 full page(모바일 헤더 버튼 2열)
  6. `#record-map-share/{token}` (활성 공유가 있는 state)
  7. `공유받은 여행` 모드
- 출력은 저장소 밖(예: `%TEMP%\recordmap-shots\`)으로. **끝나면 임시 spec을 삭제**한다.
- 기준 캡처(개편 전): 작업자 로컬 임시 폴더(저장소에 포함하지 않음)
  - `01-records-{d|m}-{light|dark}.png`(첫 화면), `01b-…full…`(전체 페이지), `02-sidebar-open-m-*`(모바일 펼침), `03-more-menu-*`, `04-place-dialog-*`, `05-detail-dialog-*`, `06-travel-*`/`06b-travel-full-*`(여행 모드), `07-travel-setup-step2-*`, `07b-…after-close-*`, `08-travel-route-step-*`, `09-public-share-{d|m}.png`
  - 같은 구도로 찍어 나란히 비교한다. (임시 폴더라 정리될 수 있음 — 없으면 개편 전 비교는 생략)

---

## 7. 리스크와 비범위

### 7.1 리스크와 대응

| 리스크 | 대응 |
|---|---|
| hash 동기화로 이탈 가드 confirm이 두 번 뜨거나 빠짐 | route가 바뀌는 전환은 App의 `handleHashChange`만 confirm(4.3절), 같은 route 전환만 Hub가 confirm. 작성 중인 장소 폼(dirty)에서 모드 전환 → confirm 1회, 취소 시 모드·hash 원상태를 수동 확인 |
| `#my-map` 진입 시 TravelWorkspace가 `applyRouteState('my-map')`로 지도 열기 — 기존 `travel`→`my-map` 매핑과 같음 | Hub의 `legacyRoute` 계산(20행)은 그대로 둔다 |
| 모바일 헤더 압축 CSS를 Hub로 옮기면서 공유 route(`#record-map-share`)에서 테마 스위처가 지도 위에 떠서 겹침 | `--shared` 수식 클래스용 사본 유지(Step 4-5). 모바일 공유 화면 캡처로 확인 |
| 추가 버튼 Teleport 대상이 데스크톱에서도 사이드바로 바뀌면서 메뉴가 잘림 | `.record-map-add__menu`는 사이드바 헤더 기준 `position:absolute; right:0` — 사이드바(`.record-map-sidebar`)에 `overflow:hidden`을 주지 않는다. 메뉴 `z-index`는 기존 1100 유지 |
| 스티키 푸터가 `fieldset`/`dialog` 구조에서 동작하지 않음 | `<dialog>`가 스크롤 컨테이너, 그 아래 요소에 overflow 금지. 동작하지 않으면 대안: 푸터를 `RecordMapDialog`의 새 `footer` 슬롯으로 올리되 버튼에 `form="record-map-place-form"` 속성으로 폼 제출 연결(폼에 id 부여) |
| 전역 CSS가 새 스타일을 덮음 | 3.6절. 계산된 스타일 확인 |
| `:has()` 선택자 | Chromium/Safari/Firefox 최신 지원. 미지원 시에도 도구가 보일 뿐 기능 손실 없음 |
| 통합 달력 작업과 충돌 | 통합 달력 파일 미수정, 5180 포트 사용, Playwright `--output` 저장소 밖 |

### 7.2 비범위 (P2 — 이번에 구현하지 않음)

1. **모드 전환 시 상태 보존**(KeepAlive): `RecordMapWorkspace`가 setup에서 window 리스너를 등록하므로 `onActivated/onDeactivated`로 옮기는 리팩터링이 선행돼야 함. 전환마다 재조회·지도 재초기화(1.4절)는 이번에 남는다.
2. **여행 모드를 '지도 가득 + 떠 있는 패널' 문법으로 통일**(A6): `TravelMyMapClusterPanel`의 `workspace-layout`을 여행 지도에도 적용하고, TRAVEL SETUP 워크플로를 지도 위 패널/시트로 흡수. `TravelHubWorkspace.vue`(2,897행) 분해와 함께 진행.
3. **장소 상세를 데스크톱 오른쪽 패널로**(모달 대신): e2e의 다이얼로그 이름·Esc 계층 계약을 바꿔야 함.
4. **모음 필터 UI 일원화**(A3): 칩만 남기고 `모음` 탭·`필터 > 장소 모음` 제거. e2e가 `장소 모음` combobox에 의존하므로 테스트 재작성 필요.
5. **`공유받은 여행` 모드의 URL화**: 새 route 키 → App.vue 수정 필요.
6. **죽은 코드 제거**: `TravelPublicTripsWorkspace.vue`(1,435행, import 0건), `TravelHubWorkspace`의 `LEGACY_TRAVEL_SHARING_ENABLED` 분기와 `TravelCommunityWorkspace.vue`, `buildRecordMapJourney`(테스트만 존재). 제품 결정 후 삭제.
7. **레이아웃 모드 선호 반영**: `compact`가 `matchMedia('(max-width: 760px)')`만 보고 앱의 `data-layout-mode`(자동/모바일/데스크톱 선택)는 무시한다. 일관화 필요.
8. **전역 테마 스위처 겹침**(E5): 데스크톱에서 떠 있는 테마 스위처가 헤더 버튼·모달 닫기 버튼을 가림 → `style.css`/App 수정 필요.
9. **장소 폼·상세를 별도 컴포넌트로 분리**(`record-map/RecordMapPlaceForm.vue`, `RecordMapPlaceDetail.vue`)와 `useRecordMapData` composable 추출. 이번 lib 분리 이후 단계.
10. **지도 범위 기반 조회**(500개 상한 대신 bbox) — 백엔드 변경 필요.
11. **바텀시트 드래그 스냅**(접힘/절반/전체) 제스처.

---

## 부록 A. 단계별 체크리스트

**Step 0**
- [ ] 무관한 미커밋 파일 목록 메모
- [ ] 단위·빌드·기록·지도 회귀 기준선 기록

**Step 1**
- [ ] `lib/recordMapView.js` 함수 9개
- [ ] `tests/unit/recordMapView.test.js` 12개 이상, 원래 식과 비교하는 테스트 포함
- [ ] `npm run test:unit` 통과

**Step 2**
- [ ] `components/record-map/recordMapTokens.css` (라이트/다크)
- [ ] Hub·Workspace import, Hub 루트 `.record-map-hub`
- [ ] `--accent-soft`/`--input-bg`/청록·`#357ccb` 대체값 제거
- [ ] 죽은 CSS 삭제, `RecordMapPlaybackControls.vue` 삭제(grep 0건 확인)
- [ ] 빌드·단위·record-map e2e 통과

**Step 3**
- [ ] 칩 줄 보임(flex-shrink 0), 클릭 가능
- [ ] 헤더 개수 표시, 필터 한 줄, 읽기 전용 `모음` 탭·장소 모음 select 숨김
- [ ] 카드 76px 안팎, 배경 버튼 제거, 이미지 width/height/decoding
- [ ] 모바일 펼침 시트 72%, 헤더 한 줄, 카드 2장 이상 보임(390x844, 360x640)
- [ ] record-map e2e 통과

**Step 4**
- [ ] 모드 3개 + 배지, `공유받은 여행` 모드 렌더
- [ ] hash 동기화(4.3절 표 전부 수동 확인), 이탈 가드 confirm 1회
- [ ] 모바일 압축 CSS Hub로 이전, Workspace에는 `--shared`·전체화면 규칙만
- [ ] 소유자 헤더 제거, Teleport 이동, `actionsTarget` 규칙
- [ ] TravelWorkspace 중복 공유 버튼·분기 제거
- [ ] 신규 e2e 1·2, 기록·지도 회귀 통과

**Step 5**
- [ ] 장소 폼 구획, 좌표 상태 문구, 스티키 푸터(장소·방문 폼)
- [ ] 위치 지도 시야/크기 그룹 숨김 + 기존 테스트 경계 검사 수정
- [ ] 상세 메타·메모·페이지 버튼·모음 칩
- [ ] 더보기 메뉴 Esc/바깥 클릭 닫기, 포커스 복귀
- [ ] 신규 e2e 3, record-map e2e 통과

**Step 6**
- [ ] 4.2절 computed 교체, `markers` 변이 없음
- [ ] 위치 검색 4개·페이지 테스트 통과

**Step 7**
- [ ] 여행 헤더 버튼 그룹 3개, 이름·핸들러 불변
- [ ] 모바일 2열(계산된 스타일 확인), 데스크톱 정돈
- [ ] travel-map-display-modes + 여행 3개 테스트 통과

**Step 8**
- [ ] 신규 e2e 4, 3.7절 계약 재점검
- [ ] 모바일 터치 44px, 키보드 순서, 라이트/다크 대비
- [ ] 6.5절 캡처 비교 후 임시 spec 삭제, 띄운 vite 종료
- [ ] 빌드·단위·기록·지도 회귀 전체 통과
- [ ] 통합 달력 파일·`style.css`·`App.vue`·`api.js`·백엔드 diff 없음(`git diff --stat`으로 확인)
