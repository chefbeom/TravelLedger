# 기록·지도 데이터 입력·확인·수정 흐름 재설계 — 분석 및 작업 지시서

- 대상: 상단 메뉴 `기록·지도`(route `travel`, `my-map`, `travel-log`, `photo-album`, `travel-money`) 중 **여행 · 일정 · 경로** 모드의 데이터 입력(새 여행, 사진, GPX, 위치, 메모)·확인·수정 흐름, 그리고 **내 장소** 모드의 사진·장소 입력 일부
- 선행 작업: 화면 셸 개편(`docs/record_map_ux_workplan.md`, 커밋 e1eabee2). 이번 문서는 그 위에서 **입력 흐름과 옵션 정리**만 다룬다.
- 작성 기준: 2026-11-22 작업 트리(미커밋 변경 포함), 코드 정독 + 임시 Playwright 스펙으로 단계별 캡처(데스크톱 1440x900, 모바일 390x844, 라이트/다크)
- 범위: **이번 구현은 P0 + P1**(6장 Step 0~10). P2는 8.2절에만 적었다.
- 구현 담당자는 이 문서만 보고 작업한다. **6장 단계를 순서대로** 진행하고, 단계마다 빌드·단위·e2e를 통과시킨 뒤 다음 단계로 넘어간다.

---

## 0. 꼭 지킬 규칙 (먼저 읽을 것)

1. **백엔드 변경 금지, DB 스키마 변경·마이그레이션 금지.** 이번 설계는 기존 API만으로 구현한다(5.4절). `backend/`는 열람만 한다.
2. **새 npm/Gradle 의존성 금지.** EXIF는 이미 있는 `exifr`(`lib/photoMetadata.js`), GPX는 문자열 파싱(새 순수 함수, 5.1절)으로 처리한다. `package.json`, `package-lock.json` 수정 금지.
3. **수정 금지 파일(동시 작업 중).**
   - 가계부: `HouseholdTransactionSheet.vue`, `HouseholdWorkspace.vue`, `HouseholdTravelLedgerWorkspace.vue`, `StatisticsWorkspace.vue`, `CalendarWorkspace.vue`, `Ledger*.vue`, `frontend/src/lib/ledger*.js`, `backend/**/ledger/**`, 가계부 관련 e2e(`ledger-*.spec.js`, `transaction-sheet-*.spec.js`, `quick-entry-*.spec.js`)
   - 통합 달력: `IntegratedCalendarWorkspace.vue`, `components/integrated-calendar/*`, `lib/integratedCalendar*.js`, `lib/useIntegratedCalendarSources.js`, `e2e/integrated-calendar.spec.js`
   - 전역: `frontend/src/style.css`, `frontend/src/App.vue`, `frontend/src/lib/api.js`(이번 설계는 api.js를 고칠 필요가 없다. 기존 export만 import한다)
   - `TravelMyMapClusterPanel.vue`, `TravelMapPanel.vue`(공통 지도. 회귀 범위가 크다. **props/이벤트만 사용**하고 파일은 수정하지 않는다)
   - 다른 작업자가 바꾸고 있는 `e2e/record-sharing.spec.js`는 실행만 하고 수정하지 않는다.
4. **작업 트리의 무관한 미커밋 변경을 건드리지 않는다**(`api.js`의 `fetchAdminWithdrawalEmbeddingStatus`, `style.css`, 백엔드 AI·탈퇴, docker-compose, `.env*` 등). `git checkout/restore/stash/add -A` 금지. 커밋은 사용자 지시가 있을 때만, 이 작업 파일만 지정해서 한다.
5. **임베드·딥링크 계약 유지.**
   - `RecordMapWorkspace.vue` props(`token`, `publicView`, `placeFocusRequest`, `calendarGrantId`)·emits(`open-travel`, `place-focus-consumed`)와 동작 유지. 통합 달력이 `public-view`, `calendar-grant-id`, `place-focus-request`로 임베드한다.
   - App.vue의 `navigateCalendarTravelSource`/`navigateTravelRecordLocation`(travelRecordFocusRequest → route `travel-money`), `navigateCalendarPlaceSource`(calendarPlaceFocusRequest → route `travel`) 경로와 Hub의 hash 모드 동기화(`#travel`↔`#my-map`)는 **그대로** 동작해야 한다. `TravelWorkspace.vue` 350~379행의 `recordFocusRequest` 처리(sourceType `memory`/`route`/`plan`/ledger)는 의미를 바꾸지 않는다.
   - Hub(`RecordMapHubWorkspace.vue`) props `route`, `recordFocusRequest`, `placeFocusRequest`와 emits 3개 유지.
6. **데이터 정합성 규칙 유지.** 공유 화면 읽기 전용(쓰기 0건), 여행 원본은 ID 참조만(내 장소의 `travel-imports`는 원본을 쓰지 않음), 위치 검색은 버튼/Enter로만, 검색 후보는 저장 확인 전 DB에 저장 안 함, 오래된 응답은 시퀀스로 폐기. 새 비동기 코드도 같은 규칙(5.3절의 `intakeToken`, `overviewSequence`)을 따른다.
7. **모달·스크롤 규칙.** App.vue는 모바일에서 `[role="dialog"][aria-modal="true"]`(및 `.travel-modal`)가 있으면 배경 스크롤을 잠그고, Esc는 캡처 단계에서 마지막 모달의 `data-modal-close` 버튼을 누른다. 새 다이얼로그는 기존 `RecordMapDialog.vue`(네이티브 `<dialog>`, `data-modal-close`, 포커스 복귀)를 재사용한다. **지도 위 패널·바(선택 기록 바, 저장 알림)에는 `role="dialog"`/`aria-modal`을 붙이지 않는다.**
8. **전역 CSS 대응.** 전역 CSS가 버튼 배경·테두리를 `!important`로 강제하고 다크 테마 특이도가 매우 높다. `docs/record_map_ux_workplan.md` 3.6절 규칙(루트 접두사 + `!important`, 선택 상태는 배경 대신 테두리·inset 그림자, `border-radius` 전제 금지)과 `components/record-map/recordMapTokens.css`의 `--rm-*` 토큰을 재사용한다. 여행 모드 화면도 Hub 루트(`.record-map-hub`) 아래에 있으므로 `--rm-*`가 상속된다.
9. **e2e 셀렉터 계약(4.5절)을 지킨다.** 바꾸는 셀렉터는 같은 단계에서 테스트도 고치고, 기존 테스트가 검증하던 시나리오는 하나도 빠지면 안 된다.
10. **접근성.** 지도로만 할 수 있는 동작을 만들지 않는다(검색·현재 위치·항목 버튼으로 대체 가능해야 함). 아이콘만 있는 버튼은 `aria-label`. 모바일 터치 대상 44px 이상. 경고는 색만으로 표시하지 않는다(문구 동반).
11. **문서·테스트에 실제 사용자 데이터·로컬 경로를 쓰지 않는다.** 픽스처는 테스트 안에서 버퍼로 만든다(7.4절).

---

## 1. 현행 구조

### 1.1 화면 트리 (실제로 렌더되는 경로만)

```
App.vue (route ∈ travel, my-map, travel-log, photo-album, travel-money, public-trips)
└─ RecordMapHubWorkspace.vue (.record-map-hub)  모드: 내 장소 | 여행 · 일정 · 경로 | 공유받은 여행  (39~46행)
    ├─ records → RecordMapWorkspace.vue (746행)  지도 + RecordMapSidebar + RecordMapDialog(place|visit|collection|travel|share|detail|photo)
    ├─ shares  → RecordSharingWorkspace kind=TRAVEL
    └─ travel  → TravelWorkspace.vue (502행)  primaryTab: map | setup | photos | finance
         ├─ map   → TravelMyMapWorkspace.vue (1,959행)  헤더 버튼 6개(1517~1533) + 여행 선택(1535) + 필터(1537~1605)
         │          + TravelMyMapClusterPanel(1637~1732) + 여행별 구분(1740~1797) + 지도 공유 모달(1798~1909) + TravelPhotoLightbox
         ├─ setup → TravelHubWorkspace.vue (2,897행) "TRAVEL SETUP" 4단계 (2197~2353)
         │          1 여행 정보(2248~2277) / 2 사진·GPS → TravelMemoryPanel(1,505행)(2285~2309)
         │          3 GPX 경로 → TravelRouteWorkspace(1,535행)(2311~2332) / 4 완료 요약(2334~2351)
         ├─ photos → TravelHubWorkspace route=photo-album → TravelMyPhotosWorkspace / 사진첩 지도
         └─ finance → 가계부 연계 안내(447~478) / (legacy) TravelHubWorkspace route=travel-money (여행 설정·예산·지출·통계)
```

| 파일 | 상태 / 비고 |
|---|---|
| `TravelPublicTripsWorkspace.vue`(1,435행) | **죽은 코드**(import 0건) |
| `TravelCommunityWorkspace.vue`, Hub 안의 `TravelSharedExhibitWorkspace` | `LEGACY_TRAVEL_SHARING_ENABLED = false`(TravelHubWorkspace 268행)로 렌더 안 됨 |
| `TravelOverviewWorkspace.vue` | **숨은 채 렌더됨.** 설정 화면을 한 번 연 뒤 지도로 돌아오면 Hub가 `v-show`로 숨겨진 채 route `travel-log` 비워크플로 템플릿을 그려 `TravelOverviewWorkspace`(Leaflet 지도 포함, 463행)가 보이지 않는 상태로 마운트된다(TravelHubWorkspace 2815행, TravelWorkspace 480~500행). 통합 모드에서는 이 화면에 도달할 방법이 없다. |
| `TravelMemoryPanel`의 표 체크박스, `TravelRouteWorkspace`의 표 체크박스 | **죽은 옵션.** `useTableSelection`만 있고 선택으로 하는 동작이 없다(TravelMemoryPanel 260·1221~1247행, TravelRouteWorkspace 366·1477~1507행) |
| 기록 편집기의 `커뮤니티 지도 월드에 공유하기` | **죽은 옵션.** 커뮤니티 공유는 비활성(268행)인데 체크박스·`공유` 열이 남아 있다(TravelMemoryPanel 1323~1326, 1369~1372, 1254행) |

### 1.2 데이터 모델 요약 (백엔드 DTO 기준, 변경 없음)

| 엔티티 | 주요 필드(제약) | 생성/수정 API (`lib/api.js`) |
|---|---|---|
| 여행 `TravelPlan` | name(≤120, 필수), destination, startDate/endDate(둘 다 있거나 `noPeriod=true`면 둘 다 null), homeCurrency(3자, 필수), headCount(≥1), status(PLANNED/COMPLETED/SAMPLE), colorHex(#RRGGBB), memo(≤500) — `TravelPlanRequest` | `createTravelPlan`, `updateTravelPlan`, `deleteTravelPlan`, `fetchTravelPlan`(memoryRecords·mediaItems·routeSegments·각 count 포함) |
| 여행 기록 `TravelMemoryRecord` | **memoryDate(필수)**, memoryTime, category(필수, ≤80), **title(필수, ≤120)**, country/region/placeName, latitude/longitude(선택), sharedWithCommunity, memo(≤500) — `TravelMemoryRecordRequest` | `createTravelMemory(planId)`, `updateTravelMemory`, `deleteTravelMemory` |
| 사진 `TravelMediaAsset` | recordType MEMORY/EXPENSE, recordId, 원본+썸네일 4종(pin/mini/preview/detail), gpsLatitude/Longitude(클라이언트 EXIF) | `uploadTravelMemoryMedia(memoryId, files, caption, {onProgress})` = presign → PUT(원본+썸네일, 동시 5) → complete. 한 장 15MB(`api.js` 6행). `deleteTravelMedia` |
| 이동 경로 `TravelRouteSegment` | **routeDate(필수)**, title(필수, ≤120), transportMode(8종), **distanceKm(≥0.01)**, durationMinutes(≥0), stepCount, sourceType MANUAL/GPX, start/endPlaceName, lineColorHex, lineStyle(4종), memo(≤500), points(서로 다른 점 2개 이상, GPX는 서버도 900점으로 솎음 — `TravelRoutePolicy`) | `createTravelRoute(planId)`, `updateTravelRoute`, `deleteTravelRoute`, `uploadTravelRouteGpxFiles(routeId, files)`(원본 GPX 보관, 10MB — `TravelMediaStorageService` 41행) |
| 지도 조회 | `/api/travel/my-map`: photoPins(사진 GPS 우선, 없으면 기록 좌표 — `TravelPhotoClusterMemberRepository` 22~23행), photoClusters, markers(사진 없는 기록, id=기록 id), routes | `fetchTravelMyMapOverview`, `fetchTravelMyMapPhotoCluster` |
| 역지오코딩 | `/api/travel/geocode/reverse?lat&lon` → country/region/placeName. 서버가 요청 간 최소 간격으로 직렬화(`TravelReverseGeocodeService` 206~212행) | `reverseGeocode`(`photoMetadata.js`, 300개 LRU 캐시) |
| 위치 검색 | `/api/record-map/locations/search?q`(서버 경유) | `recordMapApi.searchLocations` + `normalizeLocationResults` |
| 내 장소 | place(name, lat/lng 필수, visited, collectionIds), visit(date 선택 — 날짜 없는 사진 묶음), photo(visit당 업로드 1장씩, 20장/15MB), collection, share | `recordMapApi.*` |
| 지도 공유 링크 | planIds + excluded(Record/Media/Route)Ids — **제외 목록 방식이라 나중에 추가한 기록도 기존 링크에 노출됨** | `createTravelMapShare` |

검증 실패 시 서버 응답은 필드 정보 없이 `요청 값을 확인해 주세요.`(GlobalExceptionHandler 50~58행)라서, 클라이언트가 먼저 검증하지 않으면 사용자는 무엇이 틀렸는지 알 수 없다.

### 1.3 과업별 흐름 지도

표기: ①②… 사용자 행동, → 화면 결과, [API], {저장 데이터}. 시작점은 상단 `기록·지도`를 이미 누른 상태(Hub 기본 = 내 장소)다.

#### A. 새 여행(지도) 만들기
1. ① `여행 · 일정 · 경로` → hash `#my-map` → TravelMyMapWorkspace [GET portfolio, my-map]
2. ② `새 여행 만들기`(1520행) → **화면 전환** TRAVEL SETUP 1단계(TravelWorkspace 190~193 → 160~169) [GET categories, plans, plan, exchange-rates, share-groups]
3. ③ 여행 이름 ④ 시작일 ⑤ 종료일(기본값이 오늘이라 대부분 변경) (선택: `기간 없음`, `상세 설정` 6개 필드)
4. ⑥ `만들고 지도 열기` [POST /travel/plans → refreshTravelData 4회 호출 → emit workflow-complete → GET portfolio] → **화면 전환** 지도, 새 여행 선택 상태
5. 이후 사진을 넣으려면 ⑦ `장소·사진 추가` → **화면 전환** 설정 2단계 + 모달
- {travel_plans: name, start/end, noPeriod, KRW, 1명, PLANNED, #3182F6}
- 캡처: `T02-setup-step1-*`, `T02b-setup-step1-advanced-*`, `T03-after-create-*`. 만들기 화면 머리에 **이전에 선택된 여행 배지**(예: "가을 교토 여행")가 그대로 보이고 툴바에 `기존 여행 수정`·`새로고침`이 함께 있어 "지금 무엇을 만드는지"가 흐리다(2206·2211~2230행).

#### B. GPX 파일로 경로 넣기
1. ① 여행 선택 select(1535행) — 선택하지 않으면 다음 화면의 대상 여행이 목록 첫 여행/이전 선택으로 정해진다
2. ② `경로 추가`(1527행) → **화면 전환** 설정 3단계(TravelRouteWorkspace) — 패널 5개, 문서 높이 4,207px(데스크톱)/5,779px(모바일)
3. 스크롤: `GPX 파일들` 입력은 지도 아래 `경로 정보 입력` 폼의 11번째 칸(1349~1352행). 문서 y=2,707px(데스크톱)/4,001px(모바일)
4. ③ (필수지만 숨은 단계) 일차 탭 선택 — **GPX 시간에서 날짜를 쓰지 않는다**(아래 버그)
5. ④ 파일 선택 → 브라우저 DOMParser로 파싱(806~836행), 여러 파일은 시작 시각 순으로 **하나의 경로로 합침**(1069행), 제목=파일명, 이동 수단=도보, 소요 시간·걸음 수 계산
6. 확인: `GPX 추출 정보` 카드(시작/종료 좌표), `경로 요약` 칩 8개. 지도는 새 GPX에 맞춰 다시 맞추지 않는다(`view-key`가 일차 기준, 1177행) → 선이 화면 밖에 있을 수 있음(`T11-gpx-loaded-d`)
7. ⑤ `경로 저장`(같은 버튼이 1285행과 1365행에 2개) [POST /plans/{id}/routes(점 ≤900) → presign → PUT → complete → refreshTravelData]
8. ⑥ `지도에서 확인` → **화면 전환** 지도(지도 컴포넌트 재생성, 1634~1636행)
- {route_segments + points(≤900) + GPX 원본}
- **버그(측정):** GPX 시각이 2026-10-02인데 저장된 routeDate는 2026-10-01(1일차, 기본 활성 일차). `handleGpxSelection` 1084~1094행에서 `draft.routeDate`가 항상 값이 있어 GPX 날짜 분기에 도달하지 못한다.
- **버그(측정):** 경로 점이 없는 GPX를 고르면 **아무 안내 없이** 무시된다(1053~1056행). 제목이 비어 있으면 저장 버튼이 그냥 비활성이다(1039~1045행).
- 타임라인 패널은 항목이 있어도 `선택한 날짜에는 아직 기록된 일정이 없습니다.`를 함께 보여 준다(`v-else`가 페이지 버튼에 붙음, 1449~1462행, `T11-gpx-loaded-d`).

#### C. 위치(장소) 추가 — 여행
| 경로 | 단계 | 비고 |
|---|---|---|
| C1 검색 | ① `장소·사진 추가` → 설정 2단계 + **빈 기록 모달**(지도·검색 없음) ② 모달 닫기 ③ 2단계 지도 툴바 검색 입력 ④ 검색 ⑤ 결과 선택 → 임시 핀 카드 ⑥ 임시 핀 다시 클릭 → 모달(좌표·국가·지역·장소명 채움) ⑦ 제목 ⑧ `기록 저장` ⑨ `지도에서 확인` | 지도 검색이 **브라우저에서 Nominatim으로 직접** 요청(TravelMapPanel 680~688행. `location-search` 미지정). 내 장소는 서버 경유 검색을 쓴다 |
| C2 메인 지도 클릭 | ① 여행 선택 ② `지도에 장소 찍기` ③ 지도 클릭 → **화면 전환** 설정 2단계 + 모달(좌표만, 역지오코딩 없음) ④ 제목 ⑤ `기록 저장` ⑥ `지도에서 확인` | 좌표가 소수 14자리로 채워지고, 국가·지역·장소명은 비어 있는데 placeholder(`일본`, `오사카`, `우메다 스카이빌딩…`)가 값처럼 보인다(`T21-map-pick-opens-setup-modal-d`) |
| C3 2단계 지도 클릭 | ① 지도 클릭 → 임시 핀 + 역지오코딩 카드 ② 임시 핀 **한 번 더** 클릭 → 모달 ③ `기록 저장` | "한 번 더 누르기" 규칙을 안내문 3곳에서 설명해야 할 만큼 비직관적(1063·1114~1115행) |
| C4 현재 위치 | **없음**(여행 기록 편집기에 GPS 버튼 없음. 레거시 지출 기록에만 있음) | 내 장소는 `현재 위치 사용` 있음 |
| C5 좌표 직접 | 모달의 위도·경도 칸 | |
| C6 사진 EXIF | D 참고 | |

#### D. 사진 추가 — 여행
| 경로 | 단계 | 저장 결과 |
|---|---|---|
| D1 1장 | ① `장소·사진 추가` → 설정 2단계 + 모달(필드·옵션 21개, 높이 1,157px/1,512px) ② `사진 업로드` 파일 선택 → EXIF로 날짜·시간·위경도·국가·지역·장소명 자동(`T05-single-photo-exif-*`) ③ **제목 직접 입력**(장소명이 있어도 제목은 안 채움, 서버 필수) ④ `기록 저장` [POST memory → presign → PUT×(1+4 썸네일) → complete] ⑤ `지도에서 확인` | 기록 1건 + 사진. 여러 장을 고르면 **첫 사진의 EXIF만** 쓰고 모두 한 기록에 붙음 |
| D2 여러 장 | ① `장소·사진 추가` ② `여러 사진 받기` 체크(모드 전환) ③ 파일 선택 → 화면을 막는 진행 모달 → 사진별 카드(제목만 입력 가능) ④ `기록 저장` [사진마다 POST memory + 업로드, 순차] ⑤ `지도에서 확인` | **사진 1장 = 기록 1건.** 같은 장소 사진도 각각 기록이 됨. 분류·메모는 전체 공통(579~593행). 사진별 날짜·위치는 저장 전 고칠 수 없음. EXIF 날짜가 없는 사진은 **폼에 남아 있던 날짜를 물려받는다**(615행) |
| D3 기존 기록에 추가 | 표 `수정` → 모달 → 파일 → `기록 수정` | |
| D4 지도에서 편집 | 클러스터 → 미리보기 `사진 크게 보기` → 라이트박스 `수정` → **화면 전환** 설정 2단계 + 모달(TravelWorkspace 218~243행) | |

**D2 부분 실패 재현(4장 중 1장이 손상된 이미지):** 기록 1~3은 사진과 함께 저장, 4번째는 **사진 없는 기록만** 생성, 화면에는 영어 원문 `The source image could not be decoded.`, 모달은 4장 초안을 그대로 연 채 남고(`T06b-batch-still-open-after-save-*`), 목록은 새로고침되지 않아 저장된 4건이 보이지 않는다(`T07-step2-after-batch-d`). 사용자가 다시 저장하면 **최대 4건이 중복 생성**된다(TravelHubWorkspace 2039~2064행: 실패 시 refresh·초안 정리 없음).

#### E. 기록(메모/지출) 추가
- 메모: 여행 기록 모달의 `메모`(서버 500자, UI에 maxlength 없음 → 넘기면 `요청 값을 확인해 주세요.`만 표시).
- 지출: 가계부의 여행 가계부가 주 경로(가계부 작업 범위, 이번 비범위). 레거시 `travel-money` 화면(`기존 여행 예산/지출 보기`)에도 12개 필드 폼이 남아 있다(TravelHubWorkspace 2438~2631행). 이번 설계는 건드리지 않는다.

#### F. 넣은 데이터 확인·수정
| 과업 | 현재 흐름 | 문제 |
|---|---|---|
| F1 저장 직후 확인 | 설정 화면의 작은 지도·표 → `지도에서 확인` → 지도 | 지도로 돌아올 때마다 `isLoading` 동안 지도 컴포넌트를 `v-else`로 내렸다가 새로 만든다(1634~1636행) → 줌·위치 초기화 |
| F2 지도에서 기록 수정 | 클러스터 → 크게 보기 → `수정` → 설정 2단계 + 모달 → 수정 → `기록 수정` → `지도에서 확인` | 6번 이상, 화면 전환 2회 |
| F3 위치 수정 | 2단계 지도에서 저장 핀 드래그 → 모달 자동 열림 → 저장(숨은 기능, 986~997행) / 위경도 숫자 입력 | 메인 지도에서는 불가 |
| F4 날짜 변경 | 기록마다 모달 열어 날짜 변경 | 일괄 변경 없음 |
| F5 순서 | 기록은 날짜·시간 정렬뿐. 수동 경로 제어점만 위/아래 이동 | |
| F6 삭제 | 표 `삭제` **한 번에 즉시 삭제**(확인 없음 — 기록 2108~2124, 경로 2150~2165, 사진 2016~2031행) | 되돌릴 수 없음 |
| F7 경로 확인 | 여행별 구분 표(읽기 전용) → `경로 관리` → 3단계 표 `수정` | 지도에서 경로를 눌러 고칠 수 없음 |

#### G. 내 장소(RecordMapWorkspace)
| 과업 | 단계 | 비고 |
|---|---|---|
| G1 장소 추가(검색) | `＋ 장소 추가` → 이름 → 다이얼로그 지도 검색(입력·검색·결과) → `장소 저장` = 6 | 지난 개편으로 정리됨 |
| G2 새 위치 찾기에서 | `새 위치 찾기` → 입력 → 검색 → `이 위치 저장` → `장소 저장` = 5 | |
| G3 사진으로 기록 | `더보기` → `사진으로 기록` → 파일 → (이름·좌표 자동) → `장소 저장` = 4, 날짜를 남기려면 `방문 날짜와 이야기도 기록` 체크 +1 | **EXIF 촬영일을 읽고도 버린다**(247~274행에서 visitForm.date를 채우지만 `recordNow`가 false라 312행에서 null 저장). 다이얼로그의 보이는 파일 칸은 `No file chosen`인데 문구는 `선택한 사진 3장`(`R04b-photo-first-place-bottom-m`). 사진 미리보기 없음. 거리가 먼 사진도 한 장소에 묶을지 확인창만 띄움 |
| G4 방문 기록 | 상세 → `방문 기록 남기기` → 장소 select·제목·날짜·메모·사진 → 저장 | 사진 입력이 장소 폼/방문 폼/사진으로 기록 3곳 |
| G5 지도에 핀 찍기 | `표시 설정` → `지도에 핀 찍기` → 지도 클릭 → 이름 → 저장 = 5 | |
| G6 여행 장소 가져오기 | `더보기` → `여행 장소 가져오기` → 모음 선택 → `장소 가져오기` | 원본 ID 연결 유지(변경 없음) |

#### H. 공유(입력과 연결되는 부분)
- 여행 `지도 공유`: 제목 → 여행 칩 → 제외 항목 → `공유 URL 생성` → 복사(5단계). 링크는 **여행 ID + 제외 목록**으로 저장되므로, 나중에 가져온 사진은 기존 공개 링크에 **자동 노출**된다(목록 API가 없어 입력 화면에서 경고할 수 없음 → 8.2 P2).
- 내 장소 `모음 공유`: 모음에 장소를 추가하면 공유 지도에도 반영된다고 UI에 이미 안내됨.

### 1.4 단계·클릭·옵션 수 요약 (현재)

클릭/입력 = 사용자 행동 수(파일 선택 대화상자 1회 = 1). 화면 전환 = 지도↔설정 화면처럼 본문 전체가 바뀌는 횟수(모달 제외). 노출 옵션 = 그 과업 동안 화면에 보이는 입력·토글·select·도구 버튼 수(목록 행 버튼 제외).

| 과업 | 클릭/입력 | 화면 전환 | 노출 옵션 | 주요 위험 |
|---|---|---|---|---|
| A 새 여행 만들기 | 5(+모드 1) | 2 | 13 | 만들기 화면에 이전 여행 배지 |
| A' 새 여행 만들고 사진 1장 저장해 지도에서 보기 | 10(만들기 5 + 장소·사진 추가·파일·제목·기록 저장·지도에서 확인 5) | 4 | 13 + 21 | |
| B GPX 1개 | 6~7 + 스크롤 2,700/4,000px | 2 | 25+ | 날짜 오저장, 실패 무안내 |
| B' GPX 3개(3일) | 일차별 3회 반복 18~21(아니면 하루짜리 1개 경로로 합쳐짐) | 2 | 25+ | |
| C1 위치 검색 | 9 | 2 | 21 + 지도 도구 7 | 브라우저 직접 외부 요청 |
| C2 메인 지도 클릭 | 6 | 2 | 21 | 지명 없음, placeholder 혼동 |
| C4 현재 위치 | 불가 | — | — | |
| D1 사진 1장 | 5 | 2 | 21 | 제목 수동 |
| D2 사진 N장 | 5 + 사후 수정 3N | 2 | 21 + 카드 N | 1장=1기록, 부분 실패 중복 |
| F1 저장 결과 확인 | 1 | 1 | — | 지도 재생성 |
| F2 지도에서 기록 수정 | 6+ | 2 | 21 | |
| F6 삭제 | 1 | 0 | — | 확인 없음 |
| G3 내 장소 사진으로 기록(날짜 포함) | 5 | 0 | 15 | EXIF 날짜 버림 |

### 1.5 화면별 옵션 목록과 판정

빈도: ●필수 ◐가끔 ○거의 안 씀. 판정: 유지 / 기본값 / 고급(접기) / 자동(EXIF·GPX·역지오코딩) / 제거.

**여행 지도 헤더·패널(TravelMyMapWorkspace)**

| 옵션 | 빈도 | 기본값 | 판정 |
|---|---|---|---|
| 새 여행 만들기 / 여행 정보 수정 | ◐ | — | 유지(e2e 계약) |
| 장소·사진 추가 | ● | — | **새 단일 진입점 `＋ 사진·GPX·장소 추가`로 교체**(목록 편집은 `기록 목록·편집`) |
| 지도에 장소 찍기 | ◐ | 여행 미선택 시 비활성 | 제거 → 가져오기 다이얼로그의 `지도에서 위치 찍기` |
| 경로 추가 | ◐ | — | 제거 → 다이얼로그(GPX) / 여행별 구분 `경로 관리`(직접 그리기) |
| 지도 공유 | ◐ | — | 유지 |
| 여행 선택 | ● | 전체 여행 | 유지 |
| 지도 필터와 요약(보기 기준 4, 칩/국가/지역, 통계 4) | ○ | 접힘 | 유지(이미 접힘) |
| 클러스터/핀 보기 | ◐ | 클러스터 | 유지 |

**설정 1단계 — 여행 정보**

| 옵션 | 빈도 | 기본값 | 판정 |
|---|---|---|---|
| 여행 이름 | ● | 빈칸 | 유지 |
| 기간 없음 / 시작일 / 종료일 | ● | 오늘~오늘 | 유지. 가져오기에서는 **사진·GPX 날짜로 추정** |
| 목적지·기준 통화·인원·상태·색상·메모 | ○ | KRW·1·예정·#3182F6 | 고급(이미 접힘) 유지. `상태`의 `테스트 여행(SAMPLE)` 노출은 P2 |
| 작업할 여행 / 기존 여행 수정 / 새로고침 / 단계 탭 4 / 종합 보기 | ◐ | — | 유지(레거시 편집 화면). 만들기 모드에서 이전 여행 배지 숨김(P1) |

**설정 2단계 — 기록 모달·지도(TravelMemoryPanel)**

| 옵션 | 빈도 | 기본값 | 판정 |
|---|---|---|---|
| 헤더 빠른 분류 5(장소/숙소/음식점/관광지/이동) | ○ | — | 제거(분류 입력과 중복) |
| 핀 종류 8(지도 툴바 + 모달) | ○ | 기본 핀 | **제거**: 아이콘은 이미 `분류` 문자열에서 `resolvePinPreset`으로 결정된다(81~93행) |
| 날짜 / 시간 | ● | 활성 일차 또는 여행 시작일 / 빈칸 | 유지, 가져오기에서 **EXIF 자동** |
| 분류(자유 입력 + 목록) | ◐ | 장소 | 유지(편집기), 가져오기에서는 `고급` |
| 제목 | ● | 빈칸(필수) | 유지, **장소명으로 자동 제안** |
| 국가·지역·장소명 | ◐ | 빈칸 | **역지오코딩 자동**, 직접 수정은 `고급` |
| 위도·경도 | ○ | 빈칸 | 지도·EXIF·검색이 채움. 숫자 입력은 레거시 편집기에만 |
| 커뮤니티 지도 월드 공유 | ✕ | false | **제거**(기능 비활성, 기존 값은 그대로 보존해서 전송) |
| 여러 사진 받기 | ◐ | 꺼짐 | **제거** → `여러 장 한 번에 가져오기`(새 다이얼로그) |
| 사진 업로드 / 사진 설명 | ● / ○ | — | 업로드 유지, 설명은 P2(편집기에만) |
| 국가/지역/장소 필터 3 | ○ | 전체 | 유지(레거시 목록) |
| 표 체크박스 | ✕ | — | **제거**(동작 없음) |
| 지도 시야(고정/따라가기)·크기(작게/보통/크게) | ○ | 고정·크게 | 레거시 유지(P2), 새 다이얼로그에서는 숨김 |
| GPX 경로 표시 | ○ | 숨김 | 유지 |

**설정 3단계 — 이동 경로(TravelRouteWorkspace)**

| 옵션 | 빈도 | 기본값 | 판정 |
|---|---|---|---|
| 날짜 | ● | 활성 일차 | **GPX 시각으로 자동**(버그 수정) |
| 경로 제목 | ● | 파일명 | 유지(자동) |
| 이동 수단 8 | ◐ | 도보 | 가져오기에서는 `고급` |
| 입력 방식(직접 그리기/GPX 불러오기) | ✕ | 직접 | **제거**(파일을 올리면 GPX, 아니면 직접 그리기로 자동) |
| 총 소요 시간 / 걸음 수 | ○ | GPX 시각·거리×1400 | 자동(가져오기에서는 표시만) |
| 경로 색상 / 선 스타일 4 | ○ | 여행 색 / 실선 | 기본값(가져오기에서 숨김), 레거시 유지 |
| 출발·도착 장소 | ○ | 빈칸 | 가져오기에서 숨김(P2: 역지오코딩 자동) |
| 메모 | ○ | 빈칸 | 가져오기 `고급` |
| GPX 파일들 | ● | — | 가져오기 다이얼로그로 이동, 레거시도 유지 |
| 경로 저장 버튼 2개 | ● | — | 레거시 유지(P2 정리) |
| 표 체크박스 | ✕ | — | **제거** |

**내 장소 장소 폼(RecordMapWorkspace)** — 지난 개편으로 구획·스티키 푸터 정리됨. 판정: `사진 · 선택`은 **추가(append) + 미리보기 목록**, EXIF 촬영일이 있으면 `방문 날짜와 이야기도 기록`을 **자동 체크**(해제 가능). 나머지 유지.

### 1.6 측정치 (임시 스펙, 목 API 기준)

| 항목 | 데스크톱 | 모바일 |
|---|---|---|
| 여행 지도 첫 화면 문서 높이 | 1,816px | 2,314px(첫 화면에 지도가 보이지 않음, `T01-travel-map-m`) |
| 설정 1단계(상세 펼침) | 1,428px | 2,018px |
| 설정 2단계(사진·장소) | 2,637px | 3,775px, **기록 지도 패널이 815px로 화면 밖까지 넘침**(`T22-after-close-left-in-setup-m`) |
| 설정 3단계(GPX) | 4,207px, GPX 입력 y=2,707 | 5,779px, GPX 입력 y=4,001 |
| 새 기록 모달 높이 | 1,157px | 1,512px |
| 내 장소 장소 다이얼로그 스크롤 | 1,776 / 808px | 2,093 / 758px |
| 사진 4장 EXIF 준비 | 241ms | 148ms (역지오코딩 즉시 응답 목. 실제는 서버 최소 간격 × 사진 수) |
| GPX 5,000점 파싱·표시 | 125ms | 151ms → 저장 835점 |

모바일 2단계 넘침 원인(계측): `TravelMemoryPanel` 루트 `.workspace-stack`이 grid인데 트랙 폭이 자식의 min-content(814.5px)로 계산되어 부모 폭(318px)을 넘는다. `.travel-setup-workflow__stage`·패널에 `min-width:0`만 줘서는 해결되지 않았다(실험). 트랙을 `minmax(0, 1fr)`로 고정해야 한다(Step 6).

추가 확인: Hub의 모바일 헤더 압축 규칙(`RecordMapHubWorkspace.vue` 64~67행) 선택자 `.app-shell .topbar__actions`가 앱 헤더뿐 아니라 `TravelMemoryPanel` 헤더(1065행 `class="topbar__actions"`)까지 `flex-wrap:nowrap`으로 강제한다(계산 스타일 확인). App 헤더의 실제 구조는 `header.topbar > div.topbar__actions`(App.vue 1345·1369행)다.

### 1.7 기준선 테스트 (작성 시점)

| 명령 | 결과 |
|---|---|
| `npm run test:unit` | 157 통과 |
| `npm run build` | 성공 |
| e2e 기록·지도 회귀(`record-map`, `travel-map-display-modes`, `travel-map-pin`, `mobile-travel-modal`, `record-sharing`, 데스크톱+모바일) | 132 통과, 8 건너뜀(실계정 필요 테스트), 1.4분 |

---

## 2. 문제점 (우선순위, 근거)

캡처 위치: 작업자 로컬 임시 폴더 `rm-input/`(저장소에 포함하지 않음, 파일 목록은 부록 B).

### P0 — 데이터가 틀리거나 사라질 수 있음

1. **여러 사진 저장의 부분 실패가 중복·고아 기록을 만든다.** 한 장이 실패하면 앞의 기록은 저장되고 실패한 사진의 기록은 사진 없이 남으며, 초안은 그대로 남고 목록은 갱신되지 않는다. 다시 저장하면 전부 다시 만든다. 오류는 브라우저 영어 원문. (TravelHubWorkspace 2039~2064, 2099~2105행; `T06b-*`, `T07-step2-after-batch-d`)
2. **GPX 날짜가 무시된다.** GPX 시각(10-02) 대신 기본 활성 일차(10-01)로 저장(TravelRouteWorkspace 1084~1094행, 측정값 `gpxSaved.routeDate=2026-10-01`). 여러 GPX를 고르면 날짜가 달라도 **한 경로로 합친다**(1069행).
3. **삭제에 확인이 없다.** 여행 기록·경로·사진 삭제가 한 번 클릭으로 즉시 실행(2016~2031, 2108~2124, 2150~2165행).
4. **EXIF가 없는 사진이 다른 날짜를 조용히 물려받는다.** 일괄 모드에서 폼의 날짜를 그대로 사용(TravelMemoryPanel 615행; 측정: PNG가 10-02로 저장). 표시는 되지만 "추정"이라는 표시가 없다.
5. **입력 실패가 안내되지 않는다.** 경로 점 없는 GPX 무시(1053~1056행), 제목 없으면 저장 버튼만 비활성, 서버 검증 실패는 `요청 값을 확인해 주세요.`만(필드 정보 없음).

### P0 — 길이·복잡도·직관성 (사용자 지적의 핵심)

6. **"어디서 넣는지"가 흩어져 있다.** 같은 여행 데이터를 넣는 입구가 헤더 버튼 3개(`장소·사진 추가`, `지도에 장소 찍기`, `경로 추가`) + 설정 화면 단계 탭 4개 + 2단계 헤더 빠른 분류 5개 + `새 기록` + 라이트박스 `수정`으로 나뉘고, 모두 **지도를 떠나 설정 화면으로 전환**한다(TravelWorkspace 195~257행). GPX는 별도 화면(3단계), 사진은 2단계 안의 모달, 지도 클릭은 2단계로 점프 후 모달.
7. **사진은 "장소 묶음"이 아니라 "사진 1장 = 기록 1건"이다.** 같은 장소 사진 2장이 기록 2건·핀 2개가 된다(측정: 후시미이나리 ×2). 묶음·제외·사진별 날짜·위치 수정을 저장 전에 할 수 없다.
8. **검토 단계가 없다.** EXIF·GPX로 채운 결과를 지도+목록에서 한 번에 보고 고친 뒤 저장하는 화면이 없다. 결과는 저장 후 다른 화면(`지도에서 확인`)에서 확인하고, 틀리면 다시 설정 화면으로 들어가야 한다.
9. **옵션 과다.** 기록 모달 하나에 21개(핀 종류 8, 필드 10, 커뮤니티 공유, 여러 사진 받기, 사진 설명), 경로 화면에 25개 이상(폼 12, 일차 탭, 지도 도구 7, 카드 버튼 4). 동작 없는 체크박스·비활성 기능 체크박스까지 노출(1.5절).
10. **위치 지정이 경로마다 다르게 동작한다.** 메인 지도 클릭은 지명 없음, 2단계 지도 클릭은 "임시 핀을 한 번 더 눌러야" 모달, 검색은 모달을 닫고 지도 툴바에서, 현재 위치는 없음. 지도 검색은 브라우저에서 외부(Nominatim)로 직접 요청(내 장소는 서버 경유).

### P1 — 확인·수정 루프, 오류, 모바일, 성능

11. **지도에서 고치기가 멀다.** 지도 사진 → 크게 보기 → `수정` → 설정 화면 전환 → 모달 → 저장 → `지도에서 확인`(6+행동, 2전환). 경로는 지도에서 고칠 수 없다.
12. **지도 복귀 시 재생성.** 오버뷰를 다시 부를 때 지도를 `v-else`로 내렸다 올려 줌·위치가 초기화되고, 응답 시퀀스 검사가 없다(TravelMyMapWorkspace 281~327, 1634~1636, 1436행).
13. **경합으로 편집기가 닫힌다(재현).** 새 여행을 만든 뒤 지도에서 다른 여행을 고르고 `장소·사진 추가`를 누르면 새 기록 모달이 열렸다가 약 0.2~0.4초 뒤 닫힌다(데스크톱·모바일 모두 재현, `T03b-race-editor-closed-*`). 계측: 편집기가 **이전 여행(1001)으로 마운트된 패널에서 먼저 열리고**, 이어서 여행이 90으로 바뀌는 순간 `TravelMemoryPanel` 406~419행 watch가 `isEditorOpen=false`로 닫는다. 원인 후보: `TravelHubWorkspace`의 `[workflowMode, initialPlanId]` watch(1574~1577)와 `applyExternalMemoryFocusRequest`(1646~1676)가 `refreshTravelData`를 경합시키고, 패널은 포커스 요청의 `planId`를 확인하지 않는다.
14. **남는 상태·문구.** 피드백(`새 여행을 만들었습니다`, 오류 원문)이 단계를 옮겨도 계속 남는다(TravelHubWorkspace `feedback`/`errorMessage`, `T03b-*`, `T11-*`). 만들기 화면에 이전 여행 배지.
15. **모바일.** 2단계 기록 지도 패널 가로 넘침(1.6절), 3단계 5,779px, 첫 화면에 지도 없음, 모달 높이 1,512px. 다크 테마에서 활성 모드 버튼·주 버튼이 구분되지 않고(`T02-setup-step1-d-dark`), 라이트 테마 활성 일차 탭 글자가 배경에 묻힌다(`T07-step2-after-batch-d`, `T11-*`의 `전체 일정`/`1일차`).
16. **숨은 렌더 비용.** 지도로 돌아온 뒤에도 숨은 `TravelOverviewWorkspace`(Leaflet 포함)가 마운트된 채 남는다(1.1절).
17. **성능.** EXIF 추출 때 사진마다 역지오코딩(`extractPhotoMetadata` 124~125행)을 해서 서버의 직렬 처리 간격만큼 사진 수에 비례해 느려진다. 큰 GPX를 원본 점 그대로 지도에 그린다.

### P1 — 내 장소

18. **사진으로 기록 시 EXIF 촬영일을 버린다**(RecordMapWorkspace 252·270·312행). 보이는 파일 칸은 비어 있는데 "선택한 사진 3장" 문구만 있고, 다시 고르면 기존 선택을 **교체**한다(275행). 미리보기 없음.

---

## 3. 목표 구조

### 3.1 원칙

1. **하나의 추가 진입점, 지도 위에서 끝낸다.** 여행 지도 헤더의 주 버튼 `＋ 사진·GPX·장소 추가` 하나로 사진·GPX·위치를 넣는다. 설정 화면으로 전환하지 않는다.
2. **파일을 먼저, 판단은 자동으로.** 사진과 GPX를 섞어 끌어 놓으면 자동 분류하고, EXIF(날짜·시간·GPS)·GPX(시각·거리)·역지오코딩(지명)으로 제목·날짜·위치를 채운다. 사용자는 **확인과 예외 처리만** 한다.
3. **저장 전에 지도+목록으로 한 번 검토한다.** 같은 화면에서 핀을 끌어 위치를 고치고, 날짜·제목을 바꾸고, 필요 없는 항목을 제외한다. "추정"·"위치 없음"은 문구로 표시한다.
4. **부분 실패는 이어서 저장한다.** 저장된 항목은 다시 보내지 않고, 실패한 항목만 다시 시도한다. 기록만 만들어지고 사진 업로드가 실패한 경우 기록을 다시 만들지 않는다.
5. **옵션은 기본값 + 고급 접기.** 항목마다 보이는 칸은 `제목`, `날짜(+시간)`, `위치 상태`, `포함` 4개. 분류·메모·이동 수단은 `고급`.
6. **저장 결과는 같은 지도에 즉시.** 다이얼로그가 닫히면 지도를 재생성 없이 갱신하고, 해당 여행으로 필터·맞춤 보기 + 알림 한 줄.
7. **지도에서 바로 고친다(P1).** 사진 라이트박스 `수정`과 선택한 장소 바의 `이 기록 수정`이 지도 위 `기록 수정` 다이얼로그를 연다(설정 화면 전환 없음).
8. **레거시 편집 화면은 "목록·정밀 편집"으로 남긴다.** 설정 2·3단계(표, 수동 경로 그리기, 제어점 편집)는 `기록 목록·편집`/`경로 관리`로 계속 접근하되, 중복 옵션과 버그를 정리한다.

### 3.2 핵심 흐름

```
[여행 지도] ──＋ 사진·GPX·장소 추가──▶ [여행에 추가 다이얼로그]
   ▲                                    │ 저장할 여행: 선택한 여행 / (없으면) 날짜로 추천 / 새 여행(이름만 입력, 기간 자동 추정)
   │                                    │ 입력: 파일 끌어놓기·선택(사진+GPX 혼합) | 장소 검색 | 지도에서 위치 찍기 | 현재 위치
   │                                    ▼
   │                          자동 처리: 분류 → EXIF/GPX 읽기 → 사진 묶기(같은 날 250m·2시간 이내)
   │                                    → 묶음별 역지오코딩 1회 → 제목·날짜·위치 제안
   │                                    ▼
   │                          검토(같은 다이얼로그): 지도(핀 드래그·GPX 선) + 날짜별 목록
   │                            항목: [썸네일] 제목 · 날짜/시간 · 위치(없으면 '지도에서 위치 지정') · 포함 · ▸고급
   │                                    ▼ 저장(순차, 항목별 상태)
   │                          (새 여행이면 createTravelPlan) → 묶음마다 createTravelMemory + uploadTravelMemoryMedia
   │                                                        → GPX마다 createTravelRoute + uploadTravelRouteGpxFiles
   │                            실패 항목만 '남은 항목 다시 저장'
   └──── 갱신(재생성 없이) + 해당 여행 맞춤 + "기록 3건 · 사진 7장 · 경로 1개를 저장했습니다." ◀──┘

[여행 지도] 사진 → 크게 보기 → 수정  ──▶ [기록 수정 다이얼로그](지도 위) 제목·날짜·분류·위치(핀 드래그/검색)·메모·사진 추가/삭제 → 저장 → 지도 갱신
[여행 지도] 사진 없는 장소 핀 선택 → "선택: 점심 · 10-01 [이 기록 수정]" ──▶ 같은 다이얼로그
[여행 지도] 기록 목록·편집 ──▶ (레거시) 설정 2단계 '장소·사진 목록' (표·필터·수동 편집)
[설정 2단계] 여러 장 한 번에 가져오기 ──▶ 같은 [여행에 추가 다이얼로그]
```

자동 규칙 요약(5.1절에서 정확히 정의):
- 사진 묶기: 촬영 시각 순으로, **같은 날짜 + 250m 이내 + 직전 사진과 120분 이내**면 같은 묶음. 250m는 내 장소의 "먼 사진" 경고 기준(`recordMapPhotoLocation.js`)과 같다. GPS 없는 사진은 같은 날 30분 이내의 묶음에 붙이고, 아니면 "위치 없음" 묶음. 날짜·GPS 둘 다 없으면 "날짜·위치 확인 필요" 묶음 하나.
- 날짜: EXIF → GPX 첫 시각(로컬 날짜) → (없으면) 여행 시작일 또는 오늘 + `날짜 추정` 표시.
- 제목: 역지오코딩 장소명 → 지역 → 파일명(확장자 제외) → `사진 기록`. 사용자가 고친 제목은 다시 덮지 않는다.
- GPX: **파일 1개 = 경로 1개**(날짜별 분리), 표시용 2,000점·저장용 900점으로 솎음, 거리·소요 시간·걸음 수(도보 km×1400) 자동.
- 새 여행 기간: 항목들의 EXIF/GPX/직접 입력 날짜 최소~최대(추정 날짜는 제외). 상태는 종료일이 오늘 이전이면 `COMPLETED`, 아니면 `PLANNED`.
- 여행 추천: 지도에서 여행을 고르지 않은 채 열었고 사용자가 select를 건드리지 않았다면, 추정 기간을 모두 포함하는 기존 여행이 있으면 그 여행을 제안한다.

### 3.3 옵션 정리표 (결정)

| 화면 | 유지 | 기본값(숨김) | 고급으로 | 자동화 | 제거 |
|---|---|---|---|---|---|
| 여행 지도 헤더 | 새 여행 만들기, 여행 정보 수정, 지도 공유, 여행 선택 | — | — | — | 장소·사진 추가(→ 새 진입점), 지도에 장소 찍기, 경로 추가, 위치 선택 안내문 |
| 가져오기 항목(사진 묶음·장소) | 제목, 날짜, 시간, 포함, 위치 지정 | 커뮤니티 공유=false(기존 값 보존), 사진 설명='' | 분류(기본 `장소`), 메모(≤500) | 날짜·시간·위경도(EXIF), 국가·지역·장소명(역지오코딩), 제목 제안 | 핀 종류, 여러 사진 받기 |
| 가져오기 항목(GPX) | 제목, 날짜, 포함 | 색상=여행 색, 선=실선, 출발·도착=빈칸 | 이동 수단(기본 도보), 메모 | 날짜(GPX 시각), 거리·소요·걸음, 점 솎기 | 입력 방식 |
| 가져오기 여행 | 저장할 여행 select | 통화 KRW·인원 1·색 #3182F6 | — | 새 여행 기간·상태, 기존 여행 추천 | — |
| 설정 2단계(레거시) | 날짜, 시간, 분류, 제목, 국가·지역·장소명, 위경도, 메모, 사진 업로드, 사진 설명, 필터 3 | — | — | 위치 검색은 서버 경유로 | 헤더 빠른 분류 5, 핀 종류 8(툴바·모달), 커뮤니티 공유 체크박스·열, 여러 사진 받기, 표 체크박스 |
| 설정 3단계(레거시) | 날짜, 제목, 이동 수단, 소요, 걸음, 색상, 선 스타일, 출발·도착, GPX 파일, 메모 | — | — | 날짜(GPX 시각) | 입력 방식 select, 표 체크박스 |
| 내 장소 장소 폼 | 전부 | — | — | 사진 촬영일 → 방문 날짜 자동 체크 | — |

### 3.4 개선 전·후 단계 수 비교 (목표 수치)

| 과업 | 현재 클릭·입력 / 전환 / 노출 옵션 | 목표 클릭·입력 / 전환 / 노출 옵션 | 목표 흐름 |
|---|---|---|---|
| A' 새 여행 만들고 사진 1장 저장해 지도에서 보기 | 10 / 4 / 34 | **5 / 0 / 5** | 추가 → `새 여행 만들기` 선택 → 이름 → 파일 → 저장(여행이 하나도 없으면 select 기본이 새 여행이라 4) |
| A 새 여행만 만들기(레거시) | 5 / 2 / 13 | 5 / 2 / 13 (+만든 뒤 지도에 `사진·GPX 추가` 안내) | 변경 최소(e2e 계약) |
| B GPX 1개 | 6~7+스크롤 / 2 / 25+ | **3 / 0 / 3** | 추가 → 파일 → 저장 |
| B' GPX 3개(3일) | 18~21 / 6 | **3 / 0** | 파일 3개 한 번에 → 날짜별 경로 3개 |
| C1 위치 검색 | 9 / 2 | **5 / 0** | 추가 → 검색어 → 찾기 → 결과 선택 → 저장 |
| C2 지도 클릭 | 6 / 2 | **4 / 0** | 추가 → 지도에서 위치 찍기 → 지도 클릭 → 저장(제목 자동) |
| C4 현재 위치 | 불가 | **3 / 0** | 추가 → 현재 위치 → 저장 |
| D1 사진 1장 | 5 / 2 / 21 | **3 / 0 / 5** | 추가 → 파일 → 저장 |
| D2 사진 N장(같은 장소 포함) | 5 + 사후 3N / 2, 기록 N건 | **3 / 0**, 기록 = 묶음 수 | 추가 → 파일 → (검토) → 저장 |
| F1 저장 결과 확인 | 1 / 1, 지도 재생성 | **0 / 0**, 지도 유지 | 저장 즉시 반영 |
| F2 지도에서 기록 수정 | 6+ / 2 | **5 / 0** | 사진 → 크게 보기 → 수정 → 변경 → 저장 |
| F6 삭제 | 1(확인 없음) | 2(확인 1회) | 의도적으로 +1 |
| G3 내 장소 사진으로 기록(날짜 포함) | 5 | **4** | 더보기 → 사진으로 기록 → 파일 → 장소 저장 |
| 부분 실패 후 재시도 | 중복 최대 N건 | **중복 0건** | `남은 항목 다시 저장` |

### 3.5 범위

- **P0(Step 1~5):** 순수 함수 lib, composable, 가져오기 다이얼로그(사진·GPX·검색·지도 찍기·현재 위치·새 여행), 여행 지도 헤더 진입점 교체와 갱신 연동, e2e.
- **P1(Step 6~9):** 레거시 화면 버그 수정(GPX 날짜·실패 안내·타임라인 문구·삭제 확인·남는 피드백·경합 가드·모바일 넘침·Hub 헤더 선택자·숨은 오버뷰·활성 탭 대비), 레거시 옵션 정리(일괄 모드 → 가져오기, 핀 종류·커뮤니티·죽은 체크박스·입력 방식 제거, 서버 경유 검색), 지도 위 `기록 수정` 다이얼로그, 내 장소 사진 촬영일·미리보기.
- **P2:** 8.2절.

---

## 4. 화면 설계

### 4.1 여행 지도 헤더 (TravelMyMapWorkspace 1512~1536행)

```
현재(데스크톱):  여행 [새 여행 만들기][여행 정보 수정] | 기록 [장소·사진 추가][지도에 장소 찍기][경로 추가] | 공유 [지도 공유]
                [여행 선택 ▾]  "지도에 장소를 찍으려면 먼저 여행 하나를 선택하세요."
목표(데스크톱):  여행 [새 여행 만들기][여행 정보 수정] | 기록 [＋ 사진·GPX·장소 추가(주)][기록 목록·편집] | 공유 [지도 공유]
                [여행 선택 ▾]
                (저장 알림, role=status) 기록 3건 · 사진 7장 · 경로 1개를 ‘가을 교토 여행’에 저장했습니다.   [알림 닫기]
목표(모바일, 2열 grid — 기존 규칙 유지):
                [새 여행 만들기][여행 정보 수정]
                [＋ 사진·GPX·장소 추가 ─ 2칸 ─]
                [기록 목록·편집][지도 공유]          ← 4줄 → 3줄
```

- 주 버튼은 기존 클래스 `button button--primary travel-my-map-actions__main` 유지(모바일 2칸 규칙이 이 클래스에 걸려 있음).
- `사진 지도` 패널 머리 아래, 사진 없는 장소 핀이 선택됐을 때만(P1): `<p class="travel-map-selected-record" role="status">선택: {제목} · {날짜} <button>이 기록 수정</button></p>`.
- 지도 재조회 중에는 지도를 유지하고 `<p class="travel-map-reloading" role="status">지도 갱신 중…</p>`만 표시(첫 로드는 기존 문구).

### 4.2 가져오기 다이얼로그 — 데스크톱 1440x900

`RecordMapDialog`(wide) 안. 다이얼로그 1200 x 최대 860px, 본문 grid `minmax(0, 1.1fr) minmax(360px, 1fr)`, 푸터 스티키.

```
┌ 여행에 추가 ───────────────────────────────────────────────────────────── [닫기] ┐
│ 저장할 여행 [가을 교토 여행 ▾]          (새 여행 선택 시) 새 여행 이름 [________]          │
│                                        기간 [2026-10-01]~[2026-10-03] □ 기간 없음 (사진·GPX에서 추정) │
├───────────────────────────────────────┬────────────────────────────────────────┤
│ .travel-import-map (TravelMapPanel)    │ ┌ .travel-import-drop ─────────────────┐ │
│  · 사진 묶음/장소 = 핀(끌어서 위치 수정)   │ │ 사진·GPX 파일을 끌어 놓거나 [파일 선택]    │ │
│  · GPX = 선                            │ │ JPG·PNG·WEBP·GIF·BMP 15MB, GPX 10MB 이하 │ │
│  · 위치 찍기 중이면 상단 안내 띠          │ └──────────────────────────────────────┘ │
│  [전체 보기][전체 화면]  (검색·시야·크기 숨김) │ [지도에서 위치 찍기][현재 위치]              │
│                                        │ [장소 검색 ____________________][찾기]   │
│                                        │ (검색 결과 최대 6개 버튼)                  │
│                                        │ 사진 정보 읽는 중 3/12 (role=status)       │
│                                        │ ── 2026-10-02 · 2일차 ───────────────── │
│                                        │ ☑ [▣▣▣+2] 제목 [후시미이나리_______]        │
│                                        │     날짜 [10-02] 시간 [10:05]  사진 5장    │
│                                        │     📍 일본 · 교토 (사진 위치)  ▸ 고급       │
│                                        │ ☑ [GPX] 제목 [fushimi-walk____]          │
│                                        │     날짜 [10-02] · 4.2km · 1시간 20분 · 점 5,000→900 │
│                                        │ ── 날짜 확인 필요 ───────────────────── │
│                                        │ ☑ [▣] 제목 [screenshot____] ⚠ 날짜 추정 ⚠ 위치 없음 │
│                                        │     [지도에서 위치 지정]                   │
│                                        │ 추가하지 못한 파일: broken.gpx — 경로 점(trkpt)이 2개 이상인 GPX만… [지우기] │
├───────────────────────────────────────┴────────────────────────────────────────┤
│ 기록 3 · 사진 7 · 경로 1 · 확인 필요 1          [경로 직접 그리기] [취소] [저장]           │
└──────────────────────────────────────────────────────────────────────────┘
```

- 파일이 하나도 없을 때 드롭 영역은 오른쪽 열을 크게 차지하고, 항목이 생기면 한 줄 높이로 줄어든다.
- 저장 중: 모든 입력 비활성(`RecordMapDialog`의 `fieldset :disabled=busy`), 항목마다 상태 문구 `저장 중`/`저장됨`/`실패: …`. 부분 실패 시 푸터 버튼이 `남은 항목 다시 저장`으로 바뀐다.
- `경로 직접 그리기`: 다이얼로그를 닫고(초안이 있으면 확인) 설정 3단계를 연다(emit `open-route-editor`).

### 4.3 가져오기 다이얼로그 — 모바일 390x844 (폭 ≤760px)

```
┌ 여행에 추가                 [닫기] ┐ ← 헤더 sticky(기존)
│ 저장할 여행 [가을 교토 여행 ▾]       │
│ ┌ 지도 (높이 34vh, 최소 220px) ───┐ │
│ └───────────────────────────────┘ │
│ [파일 선택][위치 찍기][현재 위치]    │ ← 3등분, 44px
│ [장소 검색 ______________][찾기]    │
│ ── 10/02 · 2일차 ──                │
│ ☑ [▣▣] 후시미이나리                 │
│   제목 [____________]              │
│   날짜 [10-02] 시간 [10:05]         │
│   📍 일본·교토  [지도에서 보기] ▸고급 │
│ …(목록은 다이얼로그 스크롤)           │
├───────────────────────────────────┤
│ 기록 3·사진 7·경로 1   [취소][저장]  │ ← 푸터 sticky 64px
└───────────────────────────────────┘
```

- 다이얼로그는 화면 전체(`width:100vw; height:100dvh; max-height:100dvh; margin:0`). 지도는 고정하지 않고 함께 스크롤된다. 항목의 `지도에서 보기`는 지도를 화면에 스크롤하고 그 핀을 선택한다.
- 가로 넘침 0(검증: `document.documentElement.scrollWidth <= 390`, 다이얼로그 `scrollWidth <= clientWidth`).

### 4.4 기록 수정 다이얼로그 (P1, 일반 폭 820px)

```
┌ 기록 수정 ───────────────────────── [닫기] ┐
│ 제목 [기요미즈데라_____________________]      │
│ 날짜 [2026-10-01] 시간 · 선택 [10:00] 분류 [관광지] (datalist) │
│ 위치: 기요미즈데라 · 일본 교토 (지도를 눌러 바꾸거나 핀을 끄세요) │
│ [지도 260px: 선택 핀 드래그 가능, 검색·시야·크기 숨김]     │
│ ▸ 국가·지역·장소명 직접 수정                    │
│ 메모 [__________________] 0/500              │
│ 사진 3장 [▣ ×][▣ ×][▣ ×]  [사진 추가]          │
├──────────────────────────────────────────┤
│ [기록 삭제]                    [취소][저장]   │ ← 스티키 푸터
└──────────────────────────────────────────┘
```

### 4.5 컴포넌트 트리

```
RecordMapHubWorkspace.vue            (수정 소량, P1) 모바일 헤더 규칙 선택자를 `.topbar > .topbar__actions`로 한정, 활성 모드 버튼 표시
└─ TravelWorkspace.vue               (수정) 가져오기·기록 수정 다이얼로그 마운트, 열기/저장 후 갱신(portfolio·mapReloadKey·hubRefreshKey·notice)
    ├─ TravelMyMapWorkspace.vue      (수정) 헤더 진입점, emits open-import / open-record-editor, props reloadKey·notice, 재조회 시퀀스·지도 유지, 찍기 모드 제거
    ├─ TravelHubWorkspace.vue        (수정 소량) prop externalRefreshKey, emit request-open-import, 2단계 제목 '장소·사진 목록', P1 버그 수정
    │   ├─ TravelMemoryPanel.vue     (수정, P1) 일괄 모드 → '여러 장 한 번에 가져오기', 옵션 제거, 포커스 가드, 서버 경유 검색
    │   └─ TravelRouteWorkspace.vue  (수정 소량, P1) GPX 날짜, 실패 안내, 타임라인 문구, 입력 방식·체크박스 제거
    ├─ travel-import/TravelImportDialog.vue     (신규) RecordMapDialog(wide) + TravelMapPanel + 항목 목록
    │   └─ travel-import/TravelImportDraftItem.vue (신규) 항목 카드(사진 묶음 / GPX / 장소)
    └─ travel-import/TravelRecordEditDialog.vue  (신규, P1) RecordMapDialog + TravelMapPanel(compact)
RecordMapDialog.vue                  (수정 소량) prop `wide`(기본 false), 인스턴스별 제목 id
RecordMapWorkspace.vue               (수정 소량, P1) 사진 촬영일 → 방문 날짜, 대기 사진 미리보기·추가 방식
lib/
  travelImport.js                    (신규) 순수 함수(5.1절)
  useTravelImport.js                 (신규) 상태·입력·저장 오케스트레이션(5.3절)
  photoMetadata.js                   (수정 소량) extractPhotoMetadata(file, { reverseGeocode = true })
tests/unit/travelImport.test.js      (신규)
e2e/travel-import.spec.js            (신규)
e2e/record-map.spec.js               (수정) 여행 테스트 2개를 새 진입점에 맞게 갱신
```

`TravelMyMapClusterPanel.vue`, `TravelMapPanel.vue`는 수정하지 않는다. `TravelMapPanel`은 이미 `markers`, `routes`, `draggable-markers`(`move-marker` → `{ marker, point }`, 571~578행), `selected-point`/`draggable-selected-point`(`move-selected-point`), `enable-pick-location`(`pick-location`), `selected-marker-id`, `view-key`(바뀌면 다시 맞춤, 1035~1044행), `location-search`, `hint-title/text`를 지원한다.

### 4.6 셀렉터·접근성 이름 계약

**유지(기존 e2e가 의존):** `docs/record_map_ux_workplan.md` 3.7절 전부 + 아래.

| 대상 | 값 |
|---|---|
| 여행 지도 | heading `내 여행 지도`, 버튼 `새 여행 만들기`, `여행 정보 수정`, `지도 공유`, `.travel-map-trip-select select`, `.travel-map-workbench`, `핀 보기`/`클러스터 보기`, `.travel-unified-shell` |
| 설정 화면 | `.travel-setup-workflow`, label `여행 이름`, `시작일`, `종료일`, `기간 없음`, `작업할 여행`, 버튼 `만들고 지도 열기`, `저장하고 지도 열기`, `지도에서 확인`, heading `새 여행 기록 작성`, 편집기 label `위도`/`경도`, 버튼 `닫기`, `.travel-modal__dialog`, `.travel-day-tabs` |
| 경로 화면 | 칩 문구 `선택 범위 경로 {n}개`(travel-map-display-modes가 직접 마운트해 검사) |
| 공유 모달 | dialog `여행 지도 공유`, 버튼 `닫기`(mobile-travel-modal) |

**변경(같은 단계에서 테스트 수정):**

| 이전 | 이후 | 영향 테스트 |
|---|---|---|
| 헤더 버튼 `장소·사진 추가` | `＋ 사진·GPX·장소 추가`(가져오기) + `기록 목록·편집`(레거시 목록) | record-map 'travel creation…'(242~245행), 'periodless travel…'(276~279행) |
| 헤더 버튼 `지도에 장소 찍기` | 다이얼로그 버튼 `지도에서 위치 찍기` | 'travel creation…'(225~234행) |
| 헤더 버튼 `경로 추가` | 제거(다이얼로그 `경로 직접 그리기`, 여행별 구분 `경로 관리`) | 없음 |
| 설정 2단계 heading `장소·사진 추가` | `장소·사진 목록` | 'travel creation…'(243행) |
| 편집기 `여러 사진 받기` 체크박스 | 버튼 `여러 장 한 번에 가져오기` | 없음 |

**신규(새 e2e가 사용):**

| 대상 | 값 |
|---|---|
| 가져오기 다이얼로그 | `dialog` 이름 `여행에 추가`, 루트 클래스 `.travel-import-dialog`(dialog 요소에 `record-map-dialog record-map-dialog--wide`도 붙음) |
| 여행 선택 | combobox `저장할 여행`(option `새 여행 만들기` value `__new__`), textbox `새 여행 이름`, checkbox `가져온 여행 기간 없음`, textbox `새 여행 시작일`/`새 여행 종료일` |
| 입력 | 파일 input label `사진·GPX 파일 선택`(`.travel-import-drop input[type=file]`), 버튼 `지도에서 위치 찍기`(aria-pressed), `현재 위치`, searchbox `장소 검색`, 버튼 `장소 찾기`, 검색 결과 버튼 이름 = 장소명 |
| 목록 | `ol.travel-import-list`, 항목 `li.travel-import-item[data-draft-id][data-kind="photos"|"gpx"|"place"]`, 날짜 머리 `h3.travel-import-day` |
| 항목 안 | textbox `기록 제목`(GPX는 `경로 제목`), `날짜`, `시간`, checkbox `포함`, 버튼 `지도에서 위치 지정`, `지도에서 보기`, summary `고급`, 고급 안 `분류`/`메모`/`이동 수단`, 사진 제외 버튼 aria-label `{파일명} 빼기`, 상태 `.travel-import-item__status`(`저장 중`/`저장됨`/`실패: …`/`경로는 저장됨 · 원본 GPX 보관 실패`), 경고 칩 `.travel-import-flag`(`날짜 추정`, `위치 없음`, `여행 기간 밖`) |
| 거부 파일 | `.travel-import-rejected li`(파일명 + 이유), 버튼 `지우기` |
| 진행 | `.travel-import-progress`(role=status) `사진 정보 읽는 중 {n}/{m}` |
| 푸터 | `.travel-import-summary`(role=status), 버튼 `저장`, `남은 항목 다시 저장`, `취소`, `경로 직접 그리기` |
| 여행 지도 | 버튼 `＋ 사진·GPX·장소 추가`, `기록 목록·편집`, 알림 `.travel-map-import-notice`(role=status), 선택 바 `.travel-map-selected-record` + 버튼 `이 기록 수정`(P1), 갱신 표시 `.travel-map-reloading` |
| 기록 수정(P1) | dialog `기록 수정`, label `제목`, `날짜`, `시간 · 선택`, `분류`, `메모`, 파일 input label `사진 추가`, 사진 삭제 aria-label `{파일명} 삭제`, 버튼 `기록 삭제`, `저장` |
| 레거시 | 버튼 `여러 장 한 번에 가져오기`(TravelMemoryPanel 편집기) |

### 4.7 스타일 규칙 (전역 CSS 대응)

- 새 컴포넌트는 `<style scoped>`만 쓰고 색은 `--rm-*` 토큰. 버튼은 전역 `.button`, `.button--primary`, `.button--ghost`, `.button--danger` 클래스를 그대로 쓴다.
- 지도 높이는 전역 `min-height:460px`(style.css 7709행)·모바일 420px 규칙을 이겨야 한다:
  ```css
  .travel-import-dialog .travel-import-map :deep(.travel-map__canvas) { min-height: 0 !important; height: 100% !important; }
  .travel-import-dialog .travel-import-map :deep(.travel-map__search),
  .travel-import-dialog .travel-import-map :deep(.travel-map__toolbar-group:has(> .travel-map__toolbar-label)) { display: none !important; }
  ```
  (`RecordMapWorkspace`가 장소 폼에서 이미 같은 `:has()` 방식으로 시야·크기 그룹을 숨긴다.) 적용 후 계산된 스타일을 브라우저에서 확인한다.
- 선택 상태(`aria-pressed="true"`)·포커스 항목은 배경 대신 `box-shadow: inset 3px 0 0 var(--rm-accent) !important; border-color: var(--rm-accent) !important`. 경고 칩은 `color: var(--rm-wanted)` + 문구.
- `RecordMapDialog`의 `wide` 스타일:
  ```css
  .record-map-dialog--wide { width: min(1200px, calc(100vw - 24px)); max-height: min(92dvh, 900px); }
  @media (max-width: 760px) { .record-map-dialog--wide { width: 100vw; max-width: 100vw; height: 100dvh; max-height: 100dvh; margin: 0; } }
  ```

---

## 5. 상태/데이터 설계

### 5.1 순수 함수 — `frontend/src/lib/travelImport.js` (신규)

**브라우저 API를 쓰지 않는다**(DOMParser·File 읽기·fetch 금지). node:test로 그대로 검증할 수 있어야 한다. File 객체는 불투명한 값으로만 들고 다닌다(`file.name`, `file.type`, `file.size`만 읽음).

```js
export const IMPORT_LIMITS = Object.freeze({
  maxPhotoBytes: 15 * 1024 * 1024,   // api.js MAX_TRAVEL_MEDIA_FILE_SIZE와 같게
  maxGpxBytes: 10 * 1024 * 1024,     // 서버 MAX_GPX_FILE_SIZE
  maxFilesPerImport: 200,
  maxPhotosPerGroup: 50,
  groupDistanceMeters: 250,          // recordMapPhotoLocation.hasDistantPhotoLocations 기본값과 같음
  groupGapMinutes: 120,
  attachGapMinutes: 30,
  maxSavedRoutePoints: 900,
  maxPreviewRoutePoints: 2000,
  minRouteDistanceKm: 0.01,
  titleMax: 120,
  memoMax: 500,
})
export const IMPORT_CATEGORIES = ['장소', '숙소', '음식점', '카페', '박물관', '관광지', '쇼핑', '이동'] // TravelMemoryPanel pinPresetOptions의 category와 같은 문자열
```

| 함수 | 입력 → 출력 | 규칙·예 |
|---|---|---|
| `classifyImportFile(file)` | `{name,type,size}` → `{ kind:'photo'|'gpx'|'rejected', reason? }` | 사진: 확장자 `jpe?g|png|webp|gif|bmp` 또는 type `image/(jpeg|png|webp|gif|bmp)`. `heic|heif` → 거부 `HEIC 사진은 아직 지원하지 않습니다. JPG로 저장한 뒤 다시 올려 주세요.` GPX: `.gpx` 또는 type에 `gpx`. 크기 초과 → `사진은 한 장당 15MB 이하만 올릴 수 있습니다.` / `GPX 파일은 10MB 이하만 올릴 수 있습니다.` 그 외 → `사진(JPG·PNG·WEBP·GIF·BMP)이나 GPX 파일만 추가할 수 있습니다.` 예: `{name:'a.JPG',type:'',size:10}` → `{kind:'photo'}` |
| `parseGpxText(text)` | 문자열 → `{ ok:true, points:[{latitude,longitude,time:Date|null}], startTime, endTime, distanceKm, durationMinutes }` 또는 `{ ok:false, reason }` | `<gpx`가 없으면 `GPX 형식이 아닙니다.` 정규식으로 `trkpt`/`rtept`(자기 닫힘 포함)의 `lat`/`lon`(작은/큰따옴표, 속성 순서 무관)과 `<time>` 추출, 범위 밖·NaN 제외, **연속 중복 좌표 제거**. 2점 미만 → `경로 점(trkpt)이 2개 이상인 GPX만 불러올 수 있습니다.` duration = 유효 시각 2개 이상일 때 `round((end-start)/60000)`, 아니면 0. distance = haversine 합 |
| `haversineKm(a, b)` | 두 점 → km | 지구 반지름 6371km. (37.5,127.0)→(37.51,127.0) ≈ 1.112 |
| `thinRoutePoints(points, maxPoints=900)` | 배열 → 배열 | **TravelRouteWorkspace 749~768행과 같은 알고리즘을 그대로 복사**(마지막 점 보존). 레거시 파일은 고치지 않는다 |
| `localDateTime(date)` | Date → `{ date:'YYYY-MM-DD', time:'HH:mm' }` | `getFullYear/getMonth/getDate/getHours/getMinutes`(로컬). `photoMetadata.js` `toDateParts`와 같은 형식 |
| `stripExtension(name)` | `'IMG_0101.jpg'` → `'IMG_0101'` | |
| `suggestTitle({ placeName, region, fileName })` | → 문자열(≤120) | `placeName || region || stripExtension(fileName) || '사진 기록'` |
| `groupPhotos(photos, limits=IMPORT_LIMITS)` | `[{ id, fileName, date, time, latitude, longitude }]`(date/time은 '' 가능, 좌표 null 가능) → `[{ id, photoIds, date, time, latitude, longitude, hasLocation, dateMissing }]` | 3.2절 규칙. ① 정렬 키 `(date||'9999-99-99', time||'99:99', 입력 순서)`. ② GPS 있는 사진: 마지막으로 만든 **같은 날짜·GPS 있는** 묶음의 중심이 250m 이내이고 그 묶음 마지막 시각과 120분 이내(어느 쪽이든 시각이 없으면 시각 조건 생략)면 합류, 아니면 새 묶음. 중심 = 구성 사진 좌표 평균. ③ GPS 없고 날짜·시각 있는 사진: 같은 날짜 묶음 중 시각 차가 30분 이내로 가장 가까운 묶음에 합류, 없으면 새 "위치 없음" 묶음(같은 날 위치 없는 사진끼리는 120분 규칙으로 묶음). ④ 날짜가 없는 사진은 GPS가 있으면 ②를 날짜 '' 기준으로 적용, 없으면 하나의 묶음. ⑤ 묶음당 50장 초과 시 분할. 묶음 `id = 'photos:' + 첫 사진 id`, `date/time` = 첫 사진 값, `dateMissing = !date` |
| `createPhotoDraft(group, photosById, { fallbackDate })` | → draft(5.2절) | `dateSource = group.date ? 'exif' : 'fallback'`, `date = group.date || fallbackDate`, `locationSource = hasLocation ? 'exif' : 'none'`, title = `suggestTitle({ fileName: 첫 사진 })`(역지오코딩 뒤 갱신) |
| `createTrackDraft({ id, file, fileName }, parsed, { fallbackDate, planColorHex })` | → draft | `date = startTime ? localDateTime(startTime).date : fallbackDate`, `dateSource = startTime ? 'gpx' : 'fallback'`, `time = startTime ? localDateTime(startTime).time : ''`, `title = stripExtension(fileName)`, `track.previewPoints = thinRoutePoints(points, 2000)` |
| `createPlaceDraft({ id, latitude, longitude, name, country, region }, { fallbackDate, source })` | → draft | `kind:'place'`, `title = name || ''`(없으면 composable이 역지오코딩으로 채움), `date = fallbackDate`, `dateSource:'fallback'`, `flagDate:false`(장소에는 원래 날짜 정보가 없으므로 `날짜 추정` 칩을 띄우지 않고 `날짜 확인 필요` 그룹에도 넣지 않는다. 기간 추정에서는 제외), `locationSource = source` |
| `sortDrafts(drafts)` | → 새 배열 | `(date, time||'99:99', kind 순서 place<photos<gpx, id)` |
| `groupDraftsByDate(drafts, planStartDate)` | → `[{ key, label, drafts }]` | label 예 `2026-10-02 · 2일차`(여행 시작일이 있을 때만 일차), 확인 필요 항목(`dateSource==='fallback' && flagDate`)은 맨 끝 `날짜 확인 필요` 그룹 |
| `inferPeriod(drafts)` | → `{ startDate, endDate } | null` | 포함된 항목 중 `dateSource ∈ {exif,gpx,manual}`의 날짜 최소~최대 |
| `suggestPlanId(plans, period)` | → `'' | id 문자열` | `startDate<=period.startDate && endDate>=period.endDate`인 여행 중 시작일이 가장 늦은 것. 기간 없는 여행 제외 |
| `validateDraft(draft)` | → `string[]` | 포함된 항목만: 제목 공백 → `제목을 입력해 주세요.`, 120자 초과 → `제목은 120자 이하로 입력해 주세요.`, 메모 500자 초과 → `메모는 500자 이하로 입력해 주세요.`, 날짜 없음 → `날짜를 입력해 주세요.`, GPX 거리 < 0.01km → `경로가 너무 짧습니다(10m 미만).` |
| `summarizeDrafts(drafts)` | → `{ records, photos, routes, excluded, needsLocation, needsReview, saved, failed, pending }` | records = 포함된 photos+place 항목 수, photos = 포함 묶음의 사진 수 합, needsReview = validateDraft 문제가 있는 항목 수 |
| `buildMemoryPayload(draft)` | → `TravelMemoryRecordRequest` 형태 | `{ memoryDate: date, memoryTime: time || null, category: (category||'장소').trim(), title: title.trim().slice(0,120), country: country||null, region: region||null, placeName: placeName||null, latitude: 숫자|null, longitude: 숫자|null, sharedWithCommunity: false, memo: memo.trim() ? memo.trim().slice(0,500) : null }` |
| `buildRoutePayload(draft, planColorHex)` | → `TravelRouteSegmentRequest` 형태 | `{ routeDate: date, title, transportMode: track.transportMode||'WALK', distanceKm: Math.max(0.01, Number(distanceKm.toFixed(3))), durationMinutes, stepCount: transportMode==='WALK' ? Math.round(distanceKm*1400) : 0, sourceType:'GPX', startPlaceName:null, endPlaceName:null, lineColorHex: planColorHex||'#3182F6', lineStyle:'SOLID', memo:null 또는 메모, points: thinRoutePoints(points,900).map(p => ({ latitude:Number(p.latitude.toFixed(7)), longitude:Number(p.longitude.toFixed(7)), pointType:'ROUTE', linkedMemoryId:null, label:null })) }` |
| `buildNewPlanPayload(target, today)` | → `TravelPlanRequest` 형태 | `{ name: target.name.trim(), destination:null, startDate: noPeriod?null:target.startDate, endDate: noPeriod?null:target.endDate, noPeriod: Boolean(target.noPeriod), homeCurrency:'KRW', headCount:1, status: (!noPeriod && endDate < today) ? 'COMPLETED' : 'PLANNED', colorHex:'#3182F6', memo:null }` |
| `toImportErrorMessage(error)` | → 한국어 문자열 | `error.code==='travel-media-too-large'` → `사진이 너무 큽니다(한 장 15MB 이하).`; 메시지에 `decode`/`could not be` 또는 `DOMException`/`EncodingError` → `사진 파일을 읽을 수 없습니다. 손상되었거나 지원하지 않는 형식입니다.`; `Presigned`/`presigned object storage` → `파일 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.`; `File upload failed.` → `파일을 올리지 못했습니다. 네트워크를 확인한 뒤 다시 시도해 주세요.`; `status===400` → `입력값을 확인해 주세요(제목 120자·메모 500자 이하, 날짜 필수).`; `status===401|403` → `로그인이 필요하거나 권한이 없습니다.`; `TypeError`(fetch 실패) → `네트워크 오류로 저장하지 못했습니다.`; 그 외 `error.message`가 한국어면 그대로, 아니면 `저장하지 못했습니다.` |
| `formatImportNotice({ records, photos, routes, planName })` | → 문자열 | `기록 3건 · 사진 7장 · 경로 1개를 ‘가을 교토 여행’에 저장했습니다.`(0인 항목은 생략) |
| `runImportSave({ drafts, target, api, today, onPlan, onChange })` | async → `{ planId, ok }` | 5.4절. `api`는 주입(테스트에서 가짜). `onChange(draftId, patch)`로만 상태를 바꾼다(직접 변이 금지) |

입출력 예 — `groupPhotos`:
```js
groupPhotos([
  { id: 'a', fileName: 'IMG_0101.jpg', date: '2026-10-02', time: '10:05', latitude: 34.96710, longitude: 135.77270 },
  { id: 'b', fileName: 'IMG_0102.jpg', date: '2026-10-02', time: '10:40', latitude: 34.96750, longitude: 135.77300 }, // ≈50m
  { id: 'c', fileName: 'IMG_0103.jpg', date: '2026-10-03', time: '15:40', latitude: 35.01160, longitude: 135.76810 },
  { id: 'd', fileName: 'screenshot.png', date: '', time: '', latitude: null, longitude: null },
])
// → [ { id:'photos:a', photoIds:['a','b'], date:'2026-10-02', time:'10:05', hasLocation:true, ... },
//     { id:'photos:c', photoIds:['c'], date:'2026-10-03', ... },
//     { id:'photos:d', photoIds:['d'], date:'', hasLocation:false, dateMissing:true } ]
```

입출력 예 — `parseGpxText`:
```js
parseGpxText(`<gpx><trk><trkseg>
  <trkpt lat="37.5" lon="127.0"><time>2026-10-02T03:00:00Z</time></trkpt>
  <trkpt lon='127.0' lat='37.51'><time>2026-10-02T03:10:00Z</time></trkpt>
</trkseg></trk></gpx>`)
// → { ok:true, points:[2개], distanceKm≈1.112, durationMinutes:10, startTime: Date(2026-10-02T03:00Z) }
// 단위 테스트에서 로컬 날짜를 검사할 때는 UTC 03:00처럼 KST·UTC 모두 같은 날짜가 되는 시각을 쓴다(TZ 독립).
```

### 5.2 초안(draft) 구조

```js
{
  id: 'photos:IMG_0101.jpg-1730000000000-0' | 'gpx:fushimi.gpx-…' | 'place:3',
  kind: 'photos' | 'gpx' | 'place',
  include: true,
  title: '후시미이나리', titleEdited: false,
  date: '2026-10-02', time: '10:05' | '',
  dateSource: 'exif' | 'gpx' | 'fallback' | 'manual', flagDate: true,   // 사용자가 날짜를 바꾸면 'manual'
  latitude: 34.9671 | null, longitude: 135.7727 | null,
  country: '일본', region: '교토', placeName: '후시미이나리',
  locationSource: 'exif' | 'search' | 'map' | 'gps' | 'none', geocoding: false,
  category: '장소', memo: '',
  photos: [{ id, file, fileName, previewUrl, date, time, latitude, longitude }],   // kind==='photos'
  track: { file, fileName, points, previewPoints, distanceKm, durationMinutes, transportMode: 'WALK' }, // kind==='gpx'
  progress: { memoryId: null, routeId: null, photosUploaded: false, gpxUploaded: false },
  status: 'draft' | 'saving' | 'saved' | 'failed' | 'warning',
  error: '',
}
```

### 5.3 composable — `frontend/src/lib/useTravelImport.js` (신규)

브라우저·API 의존 코드는 여기에만 둔다. Vue `reactive/ref/computed`를 쓴다.

```js
import { extractPhotoMetadata, reverseGeocode } from './photoMetadata'
import { createLocalImagePreview } from './mediaPreview'
import { recordMapApi } from './recordMapApi'
import { normalizeLocationResults } from './recordMapLocationSearch'
import { createTravelPlan, createTravelMemory, uploadTravelMemoryMedia, createTravelRoute, uploadTravelRouteGpxFiles } from './api'
import { todayIso } from './uiFormat'
import * as core from './travelImport'

export function useTravelImport() {
  // 상태
  const drafts = ref([]), rejected = ref([]), searchResults = ref([]), searchMessage = ref('')
  const intake = reactive({ active: false, done: 0, total: 0 })
  const target = reactive({ mode: 'existing', planId: '', name: '', startDate: '', endDate: '', noPeriod: false, touched: false, createdPlanId: '' })
  const saveState = ref('idle')            // 'idle' | 'saving' | 'done' | 'partial'
  const pickTargetId = ref(''), pickArmed = ref(false), focusedId = ref(''), fitKey = ref(0), gpsLoading = ref(false), error = ref('')
  let intakeToken = 0, searchToken = 0, searchAbort = null
  let plans = []                            // reset 때 받은 여행 목록(id, name, startDate, endDate, colorHex)

  // 파생값
  const summary = computed(() => core.summarizeDrafts(drafts.value))
  const problems = computed(() => new Map(drafts.value.map(d => [d.id, core.validateDraft(d)])))
  const hasUnsaved = computed(() => drafts.value.some(d => d.include && d.status !== 'saved'))
  const canSave = computed(() => saveState.value !== 'saving' && summary.value.pending > 0
    && summary.value.needsReview === 0 && (target.mode === 'existing' ? Boolean(target.planId || target.createdPlanId) : Boolean(target.name.trim())))
  const mapMarkers = computed(...)   // 위치 있는 포함 항목 → TravelMapPanel marker 형태(5.6절)
  const mapRoutes = computed(...)    // GPX 항목 → { id, points: previewPoints, lineColorHex, lineStyle: 'SOLID' }

  return { drafts, rejected, intake, target, saveState, summary, problems, hasUnsaved, canSave,
    pickTargetId, pickArmed, focusedId, fitKey, gpsLoading, error, searchResults, searchMessage, mapMarkers, mapRoutes,
    reset, addFiles, addPlace, searchPlaces, useCurrentLocation, updateDraft, setDraftLocation, removePhoto, removeDraft,
    removeRejected, setTargetPlan, save, dispose }
}
```

| 함수 | 동작 |
|---|---|
| `reset({ planId, plans })` | `intakeToken++`, 미리보기 URL 전부 `URL.revokeObjectURL`, 상태 초기화. `planId`가 있으면 `target.mode='existing'`, 없으면 여행이 하나도 없을 때 `'new'`, 있으면 `'existing'` + `planId=''`(추천 대기) |
| `addFiles(fileList)` | ① 개수 제한(현재 사진+GPX+새 파일 ≤200, 초과분 거부). ② `classifyImportFile`로 분류, 거부는 `rejected`에 `{ id, fileName, reason }`. ③ GPX: `await file.text()` → `parseGpxText` → 실패는 거부 목록, 성공은 `createTrackDraft`. ④ 사진: `intake.active=true; total+=n`, 동시 2개씩: `extractPhotoMetadata(file, { reverseGeocode:false })`(실패 시 null) → `canDecodeImage(file)`(아래) 실패면 거부 `사진 파일을 읽을 수 없습니다…` → `createLocalImagePreview(file, { maxWidth:160, maxHeight:120 })`. 매 await 뒤 `token !== intakeToken`이면 중단(새로 만든 미리보기는 revoke). ⑤ 이번 묶음만 `groupPhotos` → `createPhotoDraft`(fallbackDate = 대상 여행 시작일 → 다른 초안 추정 시작일 → `todayIso()`). 이전 초안과 합치지 않는다(P2). ⑥ `drafts = sortDrafts([...drafts, ...new])`, `fitKey++`. ⑦ 위치 있는 새 사진 묶음마다 **순차로** `reverseGeocode(lat,lng)` → country/region/placeName, `!titleEdited`면 title=`suggestTitle`. ⑧ 여행 추천: `target.mode==='existing' && !target.planId && !target.touched`면 `suggestPlanId(plans, inferPeriod(drafts))` 적용, 새 여행 모드면 `target.startDate/endDate`를 `inferPeriod`로 채움(사용자가 기간을 바꿨으면 유지) |
| `canDecodeImage(file)`(모듈 내부) | `createImageBitmap(file).then(b => { b.close?.(); return true }).catch(() => false)`. `createImageBitmap`이 없으면 true |
| `addPlace({ latitude, longitude, name, country, region }, source)` | `createPlaceDraft` 추가 + 포커스. 이름이 없으면 역지오코딩으로 title·지명 채움 |
| `searchPlaces(query)` | 공백이면 결과 비움. `searchToken++`, 이전 요청 abort, `recordMapApi.searchLocations(q, { signal })` → `normalizeLocationResults` → `searchResults`(최대 6). 0건 `검색 결과가 없습니다. 상세 주소나 주변 지역을 함께 검색해 주세요.` 실패 `장소를 검색하지 못했습니다.` 오래된 응답 폐기. **버튼/Enter에서만 호출** |
| `useCurrentLocation()` | `navigator.geolocation.getCurrentPosition`(timeout 10s) → `addPlace(..., 'gps')`. 미지원 `현재 위치를 지원하지 않는 브라우저입니다.`, 거부 `현재 위치를 가져오지 못했습니다. 위치 권한을 확인하거나 지도에서 선택해 주세요.`(RecordMapWorkspace 276~289행과 같은 문구) |
| `updateDraft(id, patch)` | 저장된 항목(`status==='saved'`)은 무시. `title`이 오면 `titleEdited=true`, `date`가 오면 `dateSource='manual'`. 날짜·시간이 바뀌면 `sortDrafts` |
| `setDraftLocation(id, point, source='map')` | 좌표·`locationSource` 갱신 → 역지오코딩으로 지명 갱신(`!titleEdited`면 제목도). 사진 묶음이면 GPS 없는 사진만 있던 묶음도 이 좌표로 저장된다 |
| `removePhoto(draftId, photoId)` | 묶음에서 사진 제거(미리보기 revoke), 0장이면 항목 제거 |
| `setTargetPlan(value)` | `'__new__'`면 새 여행 모드(이름 빈칸, 기간 = `inferPeriod` 또는 오늘~오늘), 아니면 기존 여행. `touched=true` |
| `save()` | `saveState='saving'` → `core.runImportSave({ drafts: drafts.value, target, api: liveApi, today: todayIso(), onPlan: id => target.createdPlanId = id, onChange: (id, patch) => Object.assign(find(id), patch) })` → 결과에 따라 `'done'`/`'partial'`. 반환 `{ planId, ok, counts }` |
| `dispose()` | `intakeToken++`, 검색 abort, 미리보기 전부 revoke |

`liveApi = { createPlan: createTravelPlan, createMemory: createTravelMemory, uploadMemoryPhotos: (id, files, onProgress) => uploadTravelMemoryMedia(id, files, '', { onProgress }), createRoute: createTravelRoute, uploadRouteGpx: uploadTravelRouteGpxFiles }`

`photoMetadata.js` 수정(기본 동작 불변): `export async function extractPhotoMetadata(file, options = {})` 안에서 `const shouldGeocode = options.reverseGeocode !== false` 일 때만 `reverseGeocode` 호출(현재 124~125행). 기존 호출부 3곳(RecordMapWorkspace 260행, TravelMemoryPanel 603·890행)은 그대로 둔다.

### 5.4 API 사용 순서 — `runImportSave`

```
대상 항목 = drafts.filter(d => d.include && d.status !== 'saved')  (sortDrafts 순서)
1. planId 결정
   target.mode==='new' && !target.createdPlanId → api.createPlan(buildNewPlanPayload(target, today)) → onPlan(created.id)
     실패 → 모든 대상 항목 onChange(id, { status:'failed', error:toImportErrorMessage(e) }) 후 { ok:false } 반환(아무것도 저장 안 됨)
   planId = target.createdPlanId || target.planId
2. 항목마다 순차(동시 저장 금지 — 진행 상황 예측 가능, 업로드는 api 내부에서 이미 동시 5)
   onChange(id, { status:'saving', error:'' })
   kind photos/place:
     progress.memoryId 없으면 → api.createMemory(planId, buildMemoryPayload(d)) → onChange(id, { progress:{...progress, memoryId} })
     photos가 있고 !progress.photosUploaded → api.uploadMemoryPhotos(memoryId, photos.map(p => p.file)) → photosUploaded=true
     → status 'saved'
     업로드 실패 → status 'failed', error = toImportErrorMessage(e) + ' 기록은 저장되었습니다. 다시 시도하면 사진만 올립니다.'
   kind gpx:
     progress.routeId 없으면 → api.createRoute(planId, buildRoutePayload(d, planColor)) → routeId 기록
     !progress.gpxUploaded → api.uploadRouteGpx(routeId, [track.file]) → gpxUploaded=true → status 'saved'
     GPX 원본 보관 실패 → status 'warning', error '경로는 저장됨 · 원본 GPX 보관 실패'(경로는 지도에 보임, 다시 시도하면 원본만 올림)
3. 반환 { planId, ok: 대상 항목 모두 saved }   ('warning'은 ok=false로 보고 '남은 항목 다시 저장' 대상에 포함)
```

다시 시도는 같은 `runImportSave`를 다시 호출한다. `progress`에 남은 id 덕분에 **기록·경로·여행을 다시 만들지 않는다.** 실패 항목을 `포함` 해제하면 대상에서 빠진다.

### 5.5 오류·부분 실패 처리

| 상황 | 처리 |
|---|---|
| 형식·크기 거부 | `.travel-import-rejected`에 파일명 + 이유, `지우기`. 저장과 무관 |
| GPX 파싱 실패 | 거부 목록(`GPX 형식이 아닙니다.` / `경로 점(trkpt)이 2개 이상인…`) |
| 손상 이미지 | 입력 단계에서 거부(기록을 만들기 전에 걸러 고아 기록 방지) |
| EXIF 없음 | 날짜 = 대체값 + `날짜 추정` 칩, 위치 없음 + `위치 없음` 칩과 `지도에서 위치 지정`. 저장은 허용(위치 없는 기록은 지도에 안 보인다는 안내 `위치가 없으면 지도에는 표시되지 않습니다.`) |
| 역지오코딩 실패·지연 | 제목은 파일명 기반 유지, 지명 빈칸. 오류 표시 안 함(`geocoding` 동안 `위치 이름 확인 중…`) |
| 검색 실패/0건, 현재 위치 거부 | 5.3절 문구, role=status |
| 검증 실패 | 항목에 `aria-invalid` + 문구, 푸터 `확인 필요 n건`, `저장` 비활성 |
| 여행 생성 실패 | 다이얼로그 상단 `role=alert` + 아무것도 저장 안 됨 |
| 기록 저장 후 업로드 실패 | 5.4절. 메시지는 한국어, `남은 항목 다시 저장` |
| 저장 중 닫기 | `RecordMapDialog`가 busy 동안 닫기 비활성 |
| 초안이 있는데 닫기 | `window.confirm('저장하지 않은 항목 {n}건이 있습니다. 닫을까요?')`. 부분 실패 상태에서 닫으면 `저장된 항목은 유지되고, 저장하지 못한 {n}건은 버려집니다. 닫을까요?` 저장된 항목이 하나라도 있으면 닫을 때도 `saved` 이벤트를 보내 지도를 갱신한다 |
| 이탈 | `hasUnsaved`가 바뀔 때 `calen-route-leave-guard`({ active, message:'저장하지 않은 가져오기 항목이 있습니다. 나갈까요?' }) 발송, `beforeunload` 등록. 다이얼로그 닫힘·언마운트 시 `{ active:false }` |
| 새 여행 생성 뒤 전부 실패하고 닫음 | 빈 여행이 남는다. 닫을 때 `‘{이름}’ 여행은 만들어졌습니다.`를 알림에 포함(8.1 리스크) |

### 5.6 연동 상태

**TravelImportDialog.vue**
- `<script setup>`에서 `const imp = reactive(useTravelImport())`로 감싼다. `reactive`가 안의 ref를 풀어 주므로 템플릿·스크립트 모두 `imp.saveState`, `imp.drafts`처럼 `.value` 없이 쓴다(아래 예시도 이 형태).
- props: `open: Boolean`, `plans: { type: Array, default: () => [] }`, `planId: { type: [String, Number], default: '' }`
- emits: `close`, `saved`(payload `{ planId, records, photos, routes, planName, createdPlan }`), `open-route-editor`(payload `{ planId }`)
- `watch(() => props.open, v => v && imp.reset({ planId: props.planId, plans: props.plans }), { immediate: true })`, `onBeforeUnmount(imp.dispose)`
- 지도: `<TravelMapPanel class="travel-import-map" :markers="imp.mapMarkers" :routes="imp.mapRoutes" :enable-pick-location="imp.pickArmed || Boolean(imp.pickTargetId)" :draggable-markers="imp.saveState !== 'saving'" :selected-marker-id="imp.focusedId" :view-key="imp.fitKey" :zoom-animation="false" :auto-fit="true" initial-map-size="compact" hint-title="지도 사용법" hint-text="핀을 끌어 위치를 고치거나 ‘지도에서 위치 찍기’를 누른 뒤 지도를 눌러 장소를 추가하세요." @pick-location="onPick" @move-marker="({ marker, point }) => imp.setDraftLocation(marker.id, point)" @select-marker="m => focus(m.id)" />`
- marker 형태(TravelMemoryPanel 362~388행과 같은 필드): `{ id: draft.id, planId, planName, colorHex, latitude, longitude, country, region, placeName, title, visitedDate: date, visitedTime: time, photoCount, receiptCount: 0, photoUrl: 첫 미리보기, iconKey: 'general', iconText: kind === 'place' ? '📍' : '📷' }`
- `onPick(point)`: `pickTargetId`가 있으면 `setDraftLocation(pickTargetId, point)` 후 해제, 아니면 `pickArmed`일 때 `addPlace(point, 'map')` 후 `pickArmed=false`. 찍기 중에는 지도 위에 `지도를 눌러 위치를 정하세요. [취소]` 띠(role=status, aria-modal 없음).

**TravelWorkspace.vue**
```js
const importDialog = reactive({ open: false, planId: '' })
const recordEditor = reactive({ open: false, planId: '', recordId: '' })   // P1
const mapReloadKey = ref(0), hubRefreshKey = ref(0), importNotice = ref('')
function openImport(payload = {}) { importDialog.planId = String(payload?.planId || ''); importDialog.open = true }
async function refreshTravelPortfolio() { try { travelPortfolio.value = await fetchTravelPortfolio() } catch (e) { travelSummaryError.value = e.message } }
async function handleImportSaved(result) {
  importDialog.open = false
  if (result?.planId) currentPlanId.value = String(result.planId)
  importNotice.value = formatImportNotice(result)
  await refreshTravelPortfolio()          // loadTravelSummary()는 route==='travel-log'일 때 설정 화면을 다시 여므로 쓰지 않는다(115~129행)
  mapReloadKey.value += 1; hubRefreshKey.value += 1
}
function openRouteEditorFromImport({ planId }) { importDialog.open = false; openRoutes({ planId }) }
```
- 템플릿: `<TravelImportDialog v-if="importDialog.open" :open="importDialog.open" :plans="travelPortfolio?.plans || []" :plan-id="importDialog.planId" @close="importDialog.open = false" @saved="handleImportSaved" @open-route-editor="openRouteEditorFromImport" />`(`.travel-unified-shell` 안, 맨 끝)
- `TravelMyMapWorkspace`에 `:reload-key="mapReloadKey" :notice="importNotice" @open-import="openImport" @open-record-editor="openRecordEditor" @dismiss-notice="importNotice = ''"`
- `TravelHubWorkspace`에 `:external-refresh-key="hubRefreshKey" @request-open-import="openImport"`
- `openPhotoEditor`(218~243행)·`recordFocusRequest` 처리(350~379행)는 **그대로**(달력 딥링크 계약). 지도 라이트박스의 MEMORY 사진만 새 `open-record-editor`로 간다.

**TravelMyMapWorkspace.vue**
- props 추가: `reloadKey: { type: Number, default: 0 }`, `notice: { type: String, default: '' }`. emits 추가: `open-import`, `open-record-editor`, `dismiss-notice`(기존 emits 유지).
- `let overviewSequence = 0`; `loadOverview` 시작에 `const sequence = ++overviewSequence`, 각 await 뒤 `if (sequence !== overviewSequence) return`, finally에서 같을 때만 `isLoading=false`.
- `watch(() => props.reloadKey, () => loadOverview({ autoSelect: false, reloadDetail: true }).then(() => { mapFitRequestKey.value += 1 }))`
- 템플릿 1634~1636행: `<p v-else-if="isLoading && !overview" …>` 로 바꿔 첫 로드만 지도 대신 문구, 재조회 중에는 지도 유지 + `.travel-map-reloading`.
- `handleEditPhoto`(462~471행): `String(photo.recordType || '').toUpperCase() === 'MEMORY'`면 `emit('open-record-editor', { planId: photo.planId, recordId: photo.recordId })`, 아니면 기존 `emit('open-photo-editor', photo)`.

**TravelHubWorkspace.vue**
- prop `externalRefreshKey: { type: Number, default: 0 }` → `watch(() => props.externalRefreshKey, () => refreshTravelData(props.initialPlanId || selectedPlanId.value, props.route === 'photo-album'))`
- emits에 `request-open-import` 추가, `TravelMemoryPanel`의 `@open-import="emit('request-open-import', { planId: selectedPlanId })"`(P1)
- `workflowTitle`(303~304행)의 2단계 문구 `'장소·사진 추가'` → `'장소·사진 목록'`

---

## 6. 단계별 작업 지시

### 공통: 실행 환경

- 다른 작업자가 5173 포트를 쓸 수 있다. **별도 포트**와 **저장소 밖 출력 폴더**를 쓴다(Playwright는 출력 폴더를 비운다).
  ```powershell
  # 터미널 1 (백그라운드 유지, 끝나면 종료)
  cd C:\project\calen\frontend; npx vite --host 127.0.0.1 --port 5185 --strictPort
  # 터미널 2
  cd C:\project\calen\frontend
  $env:E2E_BASE_URL='http://127.0.0.1:5185'; $env:E2E_START_LOCAL_SERVER='0'
  npx playwright test e2e/record-map.spec.js --config playwright.config.js --reporter=line --output="$env:TEMP\pw-rm-input"
  ```
- 단위: `cd C:\project\calen\frontend; npm run test:unit`(node:test). 빌드: `npm run build`.
- **기록·지도 회귀**(이 문서에서 이렇게 부름):
  `npx playwright test e2e/record-map.spec.js e2e/travel-map-display-modes.spec.js e2e/travel-map-pin.spec.js e2e/mobile-travel-modal.spec.js e2e/record-sharing.spec.js e2e/travel-import.spec.js --config playwright.config.js --reporter=line --output="$env:TEMP\pw-rm-input"`
  (`travel-import.spec.js`는 Step 5에서 생긴다. 그 전에는 빼고 실행)
- 다른 작업자 파일 때문에 빌드가 잠시 깨지면 그 파일을 고치지 말고 잠시 후 재시도한다. `record-sharing.spec.js`가 다른 작업 때문에 실패하면 이번 변경과 무관함을 기록만 한다.

### Step 0. 기준선 확인

- 할 일: `git status --short`로 무관한 미커밋 파일 목록 메모. `npm run test:unit`(작성 시점 157 통과), `npm run build`, 기록·지도 회귀(작성 시점 132 통과·8 건너뜀).
- 완료 기준: 결과 메모. 실패가 있으면 이번 변경과 무관함을 확인.

### Step 1. 순수 함수 lib + 단위 테스트 (P0, UI 변경 없음)

- 생성: `frontend/src/lib/travelImport.js`, `frontend/tests/unit/travelImport.test.js`
- 내용: 5.1절 상수·함수 전부. import는 상대 경로 `.js` 확장자 포함(node ESM). 다른 lib를 import하지 않는다(필요한 날짜 형식은 내부 구현).
- 테스트(최소 24개): 7.2절 목록.
- 검증: `cd C:\project\calen\frontend; node --test tests/unit/travelImport.test.js` 통과, `npm run test:unit` 전체 통과.

### Step 2. EXIF 옵션 + composable (P0, UI 변경 없음)

- 수정: `frontend/src/lib/photoMetadata.js`(5.3절 마지막 문단, 기본 동작 불변). 생성: `frontend/src/lib/useTravelImport.js`(5.3절).
- 주의: `runImportSave`에 넘기는 draft는 reactive 프록시다. `onChange`에서만 상태를 바꾼다. `progress`는 객체 통째로 교체(`{ ...d.progress, memoryId }`).
- 미리보기 URL은 `createLocalImagePreview`가 돌려준 `blob:` URL이다. 항목·사진 제거, `reset`, `dispose` 때 revoke한다.
- 검증: `npm run build`, `npm run test:unit`(기존 photoMetadata 사용처 회귀 없음 — 기록·지도 회귀 중 record-map 'a place can have a new category and photo…', 'location lookup…' 통과).

### Step 3. 다이얼로그 컴포넌트 (P0, 아직 진입점 없음)

- 수정: `frontend/src/components/RecordMapDialog.vue`
  1. prop `wide: { type: Boolean, default: false }` → `<dialog :class="['record-map-dialog', { 'record-map-dialog--wide': wide }]" …>` + 4.7절 CSS.
  2. 제목 id를 인스턴스별로: 모듈 스코프 `let dialogCount = 0` → `const titleId = 'record-map-dialog-title-' + (++dialogCount)`, `aria-labelledby`·`h2 id`에 사용. (이름으로 찾는 기존 e2e는 영향 없음)
  3. 그 외 동작(showModal, Esc, 바깥 클릭, 포커스 복귀, `data-modal-close`, `aria-label="창 닫기"`)은 그대로.
- 생성: `frontend/src/components/travel-import/TravelImportDialog.vue`, `frontend/src/components/travel-import/TravelImportDraftItem.vue`
  - Dialog: 4.2/4.3절 배치, 5.6절 연동, 4.6절 접근성 이름. `<RecordMapDialog :open="open" wide title="여행에 추가" :busy="imp.saveState === 'saving'" class="travel-import-dialog" @close="requestClose">`(`imp = reactive(useTravelImport())`).
  - 드롭 영역: `<label class="travel-import-drop" @dragover.prevent="dragging = true" @dragleave="dragging = false" @drop.prevent="onDrop">` 안에 **시각적으로만 숨긴** `<input type="file" multiple accept="image/jpeg,image/png,image/webp,image/gif,image/bmp,.jpg,.jpeg,.png,.webp,.gif,.bmp,.gpx,application/gpx+xml" aria-label="사진·GPX 파일 선택">`(display:none 금지 — 키보드 접근), `:focus-within` 외곽선. `change` 후 `event.target.value = ''`. 다이얼로그 본문 래퍼에도 `@dragover.prevent @drop.prevent`(바깥에 떨어뜨려 페이지가 파일을 여는 것 방지).
  - 검색: `<form @submit.prevent="imp.searchPlaces(query)"><input type="search" aria-label="장소 검색" maxlength="100"><button type="submit">장소 찾기</button></form>`, 결과 버튼 클릭 → `imp.addPlace({ latitude, longitude, name, country, region }, 'search')` 후 결과 비움.
  - 여행 select: `<select aria-label="저장할 여행">` 옵션 = `<option value="" disabled>여행을 고르세요</option>` + plans(`{name}{기간 없으면 ' · 기간 없음'}`) + `<option value="__new__">새 여행 만들기</option>`. 기존 여행 모드에서 `planId`가 비어 있으면 `저장` 비활성 + `저장할 여행을 고르세요.` 안내. 새 여행이면 이름·기간 입력 표시.
  - 푸터: `.travel-import-summary`(role=status, `aria-live="polite"`) 문구 예 `기록 3 · 사진 7 · 경로 1 · 제외 1 · 확인 필요 1`, 버튼 `저장`(`:disabled="!imp.canSave"`) / `saveState==='partial'`이면 `남은 항목 다시 저장`. 저장 결과 ok → `emit('saved', …)`.
  - 스티키 푸터는 `RecordMapDialog`의 스크롤 컨테이너가 `<dialog>` 자신이므로 `position: sticky; bottom: -18px; margin: 0 -18px -18px;`(record-map 장소 폼 푸터와 같은 방식). 모바일(≤600px)은 12px 기준.
  - DraftItem props: `draft`, `problems: string[]`, `disabled: Boolean`, `planStartDate`, `index`. emits: `update`(patch), `locate`, `show-on-map`, `remove-photo`(photoId), `focus`. 사진 썸네일 최대 4개 + `+n`, `<img :src width="56" height="56" decoding="async" loading="lazy" alt="파일명">`. 상태·경고 문구는 4.6절.
- 완료 기준: 빌드 통과. 임시로 `TravelWorkspace`에 `?importTest` 같은 진입점을 만들지 말고, Step 4에서 실제 진입점으로 확인한다.
- 검증: `npm run build`, `npm run test:unit`, record-map e2e 전체(RecordMapDialog 회귀 — 'fullscreen detail closes one layer…', 'place form keeps the save action…').

### Step 4. 진입점·갱신 연동 + 기존 e2e 갱신 (P0)

- 수정: `TravelWorkspace.vue`, `TravelMyMapWorkspace.vue`, `TravelHubWorkspace.vue`, `frontend/e2e/record-map.spec.js`
- 내용:
  1. TravelWorkspace: 5.6절 그대로(가져오기만. 기록 수정 다이얼로그는 Step 8).
  2. TravelMyMapWorkspace 헤더(1523~1528행): `장소·사진 추가`, `지도에 장소 찍기`, `경로 추가` 버튼을 지우고 `<button class="button button--primary travel-my-map-actions__main" type="button" @click="emit('open-import', { planId: selectedSinglePlanId })">＋ 사진·GPX·장소 추가</button>` + `<button class="button button--secondary" type="button" @click="emit('open-memories', { planId: selectedSinglePlanId })">기록 목록·편집</button>`. 그룹 라벨 `기록`·`aria-label="기록"` 유지.
  3. 찍기 모드 제거: `travelPickMode` ref(61행), `pickTravelLocation`(979~983행), `choosePlan`의 `travelPickMode.value = false`, `.travel-map-pick-hint` p(1536행), `:enable-pick-location="travelPickMode"`(1644행 → 속성 삭제, 기본 false 확인), `@pick-location` 바인딩 삭제. grep으로 `travelPickMode` 0건 확인.
  4. 알림: 헤더 아래(여행 선택 다음)에 `<p v-if="notice" class="feedback feedback--success travel-map-import-notice" role="status">{{ notice }} <button class="button button--ghost" type="button" @click="emit('dismiss-notice')">알림 닫기</button></p>`. 이 p에는 role=dialog/aria-modal을 붙이지 않는다(규칙 7). 버튼 이름을 `닫기`로 하지 않는다(다른 e2e의 `닫기` 버튼 검색과 섞이지 않게).
  5. `reloadKey` watch, `overviewSequence`, 지도 유지(5.6절).
  6. `handleEditPhoto`는 이 단계에서 **바꾸지 않는다**(Step 8).
  7. TravelHubWorkspace: `externalRefreshKey` prop·watch, emits `request-open-import`, 2단계 제목 `장소·사진 목록`.
  8. 새 여행을 레거시 화면에서 만든 직후 알림 `‘{이름}’ 여행을 만들었습니다. ‘＋ 사진·GPX·장소 추가’로 채워 보세요.`를 `importNotice`로 표시한다. `handleWorkflowComplete`는 만들기·수정·`지도에서 확인` 모두에서 불리므로, 호출 시작 시점의 `travelPortfolio.plans` id 집합을 기억했다가 `loadTravelSummary()` 뒤 `payload.planId`가 **새로 생긴 id일 때만** 알림을 띄운다(이름은 갱신된 portfolio에서 찾기). 모달을 자동으로 열지 않는다(e2e가 곧바로 지도를 조작함).
  9. record-map.spec.js 갱신:
     - 'travel creation starts from the map…' 225~234행: `＋ 사진·GPX·장소 추가` 클릭 → dialog `여행에 추가`에서 combobox `저장할 여행` 값이 `91` → `지도에서 위치 찍기` 클릭 → dialog 안 `.travel-map__canvas` 클릭 → `li.travel-import-item[data-kind="place"]` 1개 → `창 닫기` 클릭(초안 확인창은 `page.once('dialog', d => d.accept())`) → dialog 닫힘. 235~241행(지도에서 확인·스크롤 잠금 해제)은 의미를 유지해 "다이얼로그를 닫은 뒤 `document.body.style.position === ''`"로 검사. 242~245행: `기록 목록·편집` 클릭 → heading `장소·사진 목록`, `작업할 여행` 값 `91`, `새 기록` 클릭 → heading `새 여행 기록 작성` 보임.
     - 'periodless travel…' 276~279행: `기록 목록·편집` → `새 기록` → 편집기 → `닫기` → `.travel-day-tabs` 0개(기존 단언 유지).
- 완료 기준: 지도 헤더에서 다이얼로그가 열리고, 사진·GPX·장소를 넣고 저장하면 다이얼로그가 닫히며 지도가 재생성 없이 갱신되고 알림이 뜬다(수동 확인 + Step 5 e2e).
- 검증: 빌드·단위, 기록·지도 회귀 전체.

### Step 5. 가져오기 e2e (P0)

- 생성: `frontend/e2e/travel-import.spec.js`(7.3·7.4절).
- 완료 기준: 데스크톱·모바일 모두 통과, `state.errors` 빈 배열.
- 검증: `npx playwright test e2e/travel-import.spec.js …`, 기록·지도 회귀 전체.

### Step 6. 레거시 화면 버그 수정 (P1)

- 수정: `TravelRouteWorkspace.vue`, `TravelHubWorkspace.vue`, `TravelMemoryPanel.vue`, `RecordMapHubWorkspace.vue`
- 내용:
  1. **GPX 날짜**(TravelRouteWorkspace 1084~1094행): GPX 첫 시각이 있으면 `const detected = toIsoDate(firstTrackDate)`로 `draft.routeDate = detected`, `tripDays`에 있으면 `activeDayDate = detected`(activeDayDate watch가 같은 값을 다시 넣어도 무해). 시각이 없을 때만 기존 `selectedRouteDate` 사용.
  2. **GPX 실패 안내**: `const gpxMessage = ref('')`. 파싱 결과가 0개면 `gpxMessage = '경로 점을 찾지 못한 파일: ' + 파일명들` + `event.target.value = ''` 후 return. 일부만 실패하면 성공분은 반영하고 실패 파일명 안내. 표시: GPX 입력 아래 `<p class="travel-map-note" role="alert" v-if="gpxMessage">`. 성공·초기화 시 비움.
  3. **저장 버튼 이유 안내**: 1285·1365행 버튼 옆에 `draftPoints.length < 2`면 `경로 점이 2개 이상 필요합니다.`, 제목 없으면 `경로 제목을 입력해 주세요.`(`<small role="status">`).
  4. **타임라인 빈 문구**(1449~1462행): `<p v-else class="panel__empty">`를 `<p v-if="!activeDayTimeline.length" class="panel__empty">`로.
  5. **삭제 확인**(TravelHubWorkspace): `handleDeleteMemory` 시작에 `if (!window.confirm('“' + (memory.title || '이 기록') + '” 기록과 첨부 사진을 삭제할까요? 되돌릴 수 없습니다.')) return`, `handleDeleteRoute`에 `'“' + (route.title || '이 경로') + '” 경로를 삭제할까요? 되돌릴 수 없습니다.'`, `handleDeleteMedia`에 `'이 사진을 삭제할까요? 되돌릴 수 없습니다.'`.
  6. **남는 피드백**: `selectWorkflowStep` 시작과 `[workflowMode, workflowStartStep]` watch(1547행)에서 `setFeedback()` 호출. 저장 성공 직후 문구는 유지(같은 단계 안).
  7. **만들기 화면 배지**(2206행): `v-if="travelPlan && planFormMode !== 'create'"`.
  8. **포커스 가드**(TravelMemoryPanel 462~491행): watch 소스를 `() => [props.focusRequest?.token, props.travelPlan?.id]`로 바꾸고, 지역 변수 `appliedFocusToken`으로 한 번만 적용. `request.planId`가 있고 `String(request.planId) !== String(props.travelPlan?.id)`면 아직 적용하지 않고 return(여행이 바뀐 뒤 다시 실행됨). 이 watch는 **반드시 406행 여행 watch보다 뒤에 선언된 상태 유지**(같은 flush에서 초기화 뒤 적용).
  9. **모바일 2단계 넘침**(TravelHubWorkspace scoped): `.travel-setup-workflow .travel-setup-workflow__stage > .workspace-stack { grid-template-columns: minmax(0, 1fr) !important; min-width: 0; }` (자식 컴포넌트 루트에도 부모 scope id가 붙으므로 :deep 불필요). 390x844에서 `.travel-memory-map-panel` 폭 ≤ 390 확인.
  10. **Hub 헤더 선택자**(RecordMapHubWorkspace 64~67행): `.topbar__actions`를 `.topbar > .topbar__actions`로 바꾼다(4줄 모두). 앱 헤더 압축이 그대로인지 모바일 캡처로 확인.
  11. **활성 표시**: Hub 모드 버튼 `:global(html body .app-shell .record-map-hub .record-map-mode-switch .button[aria-pressed="true"]) { box-shadow: inset 0 -3px 0 var(--rm-accent) !important; font-weight: 700; }`. 일차 탭 활성 글자: TravelMemoryPanel·TravelRouteWorkspace scoped에 `.travel-day-tabs .travel-day-tabs__button.is-active :is(strong, small) { color: var(--rm-accent-contrast) !important; }` — 적용 전후 라이트/다크 캡처로 대비 확인(배경이 `--rm-accent`류가 아니면 대신 `.is-active`에 `box-shadow: inset 0 -3px 0 var(--rm-accent) !important`만 쓰고 글자색은 건드리지 않는다).
  12. **숨은 오버뷰**(TravelHubWorkspace 2815행): `<TravelOverviewWorkspace v-if="logTab === 'overview' && !integratedMode" …>`(통합 모드에서는 도달 불가 화면, 1.1절).
- 완료 기준: GPX 10-02 파일 → routeDate 10-02. 빈 GPX → 안내 문구. 삭제 시 확인창. 모바일 2단계 가로 넘침 0. 경합 재현 절차(2장 13번)에서 편집기가 열린 채 유지(`기록 목록·편집` → `새 기록` 경로와, `TravelWorkspace.openMemories({ planId, create: true })`가 남아 있는 경우 모두).
- 검증: 빌드·단위, 기록·지도 회귀(특히 travel-map-display-modes 'periodless route and overview…'), 7.3절 레거시 시나리오 L1~L3.

### Step 7. 레거시 옵션 정리 (P1)

- 수정: `TravelMemoryPanel.vue`, `TravelRouteWorkspace.vue`, `TravelHubWorkspace.vue`
- 내용:
  1. TravelMemoryPanel 일괄 모드 제거: 템플릿 `여러 사진 받기` label(1391~1394행) → `<button v-if="!editingMemoryId" type="button" class="button button--ghost" @click="emit('open-import')">여러 장 한 번에 가져오기</button>`(emits에 `open-import` 추가). 일괄 관련 템플릿(1314~1331 `hasMultiPhotoDrafts` 분기 → 일반 분기만 남김, 1405~1448 스켈레톤·카드)과 스크립트(`multiPhotoUploadEnabled`, `multiPhotoDrafts`, `multiPhotoLoading`, `multiPhotoLoadingCount`, `multiPhotoPreparationProgress`와 관련 함수, `isMultiPhotoMode`, `hasMultiPhotoDrafts`, `buildMultiPhotoDrafts`, `buildMultiPhotoPayload`, `resolveMultiPhotoTitle`, `extractDraftTitleSeed`, `handleMultiPhotoModeChange`, `revokeMultiPhotoPreviews`, `resetMultiPhotoDrafts`, `handlePhotoSelection`·`submitMemory`의 일괄 분기)를 삭제. `activeOperationProgress`는 `props.uploadProgress`만 보도록 단순화(진행 모달·이탈 가드 유지). 삭제 후 `grep -n "multiPhoto\|MultiPhoto\|batch" TravelMemoryPanel.vue`가 0건(클래스명 `travel-batch-*` CSS도 함께 정리).
  2. TravelHubWorkspace `handleSaveMemory`의 `submission.batchItems` 분기(2039~2064행) 삭제. 실패 메시지는 `setFeedback('', toImportErrorMessage(error))`(travelImport.js에서 import).
  3. 핀 종류 제거: 헤더 빠른 분류 버튼(1067~1075행), 지도 툴바 `핀 종류` 그룹(1123~1135행), 모달 `핀 종류` label(1299~1313행). `applyPinPreset`/`startMemoryWithPreset`/`quickMemoryPresets`는 사용처가 없어지면 삭제. `resolvePinPreset`·`pinPresetOptions`(아이콘 결정)는 유지. 분류 입력의 datalist에 `IMPORT_CATEGORIES`를 합쳐 제안(`[...new Set([...categoryOptions, ...IMPORT_CATEGORIES])]`).
  4. 커뮤니티 공유 체크박스(1369~1372행, 일괄 분기 것은 1번에서 삭제됨)와 표 `공유` 열(1235·1254행) 제거. `form.sharedWithCommunity`는 `fillForm`에서 기존 값을 읽어 `buildPayload`로 그대로 보낸다(기존 데이터 보존).
  5. 표 체크박스 제거: TravelMemoryPanel 1221~1228·1241~1247행과 `memoryRecordSelection`, TravelRouteWorkspace 1477~1484·1500~1507행과 `routeTableSelection`, 각 파일의 `useTableSelection` import(다른 사용처 없을 때). `colspan` 값을 열 수에 맞춰 줄인다(8→6, 7→6).
  6. placeholder: 국가 `예: 일본`, 지역 `예: 오사카`, 장소명 `예: 우메다 스카이빌딩`. 제목 `maxlength="120"`, 메모 `maxlength="500"`.
  7. 서버 경유 검색: TravelMemoryPanel의 `TravelMapPanel`에 `:location-search="searchTravelLocations"`. `useTravelImport.js`에 `export async function searchTravelLocations(q, options = {}) { const rows = normalizeLocationResults(await recordMapApi.searchLocations(q, options)); return rows.map(r => ({ ...r, title: r.name, placeName: r.name })) }`를 추가해 import한다.
  8. TravelRouteWorkspace `입력 방식` select(1316~1322행) 제거. GPX 안내문 1356~1358행(`draft.sourceType === 'GPX'` 분기)은 의미가 없어지므로 삭제. `draft.sourceType`은 GPX 로드 시 'GPX', `resetDraft`·`addDraftPoint`에서 'MANUAL'(현재 로직 유지).
- 완료 기준: 2단계 모달 옵션 21 → 입력 12개(날짜, 시간, 분류, 제목, 국가, 지역, 장소명, 위도, 경도, 메모, 사진 업로드, 사진 설명) + `여러 장 한 번에 가져오기` 버튼 1개, 3단계 폼 12 → 11, 동작 없는 체크박스 0.
- 검증: 빌드·단위, 기록·지도 회귀, 7.3절 L4(레거시 단일 사진 저장).

### Step 8. 지도 위 기록 수정 다이얼로그 (P1)

- 생성: `frontend/src/components/travel-import/TravelRecordEditDialog.vue`. 수정: `TravelWorkspace.vue`, `TravelMyMapWorkspace.vue`
- 내용:
  1. props `open`, `planId`, `recordId`. emits `close`, `saved`(`{ planId }`), `deleted`(`{ planId }`).
  2. 열릴 때 `fetchTravelPlan(planId)`(요청 시퀀스로 오래된 응답 폐기) → `memoryRecords`에서 기록, `mediaItems.filter(m => String(m.recordId) === String(recordId) && m.recordType === 'MEMORY' && m.mediaType === 'PHOTO')`. 못 찾으면 `기록을 찾지 못했습니다. 이미 삭제되었을 수 있습니다.`
  3. 폼: 4.4절. 지도는 `TravelMapPanel`(`initial-map-size="compact"`, `:selected-point`, `draggable-selected-point`, `enable-pick-location`, `:location-search="searchTravelLocations"`, 4.7절 숨김 규칙). 위치가 바뀌면 `reverseGeocode`로 국가·지역·장소명 갱신(사용자가 고급에서 직접 고친 값은 덮지 않음).
  4. 저장: `updateTravelMemory(id, payload)`(payload는 `buildMemoryPayload`와 같은 규칙, 단 `sharedWithCommunity`는 기존 값, `category`는 입력값) → 새 사진이 있으면 `uploadTravelMemoryMedia(id, files)` → `saved`. 업로드 실패 시 "기록은 저장됨, 사진만 다시 올리기" 상태(새 사진 목록 유지, 다시 저장하면 업로드만).
  5. 사진 삭제: 확인창 후 즉시 `deleteTravelMedia` → 목록에서 제거 + `dirty`. 기록 삭제: 확인창 후 `deleteTravelMemory` → `deleted`.
  6. 닫기: 변경이 있으면 확인. 이탈 가드 이벤트는 가져오기와 같은 방식.
  7. TravelWorkspace: `openRecordEditor({ planId, recordId })`, 저장·삭제 시 닫고 `mapReloadKey++`, `hubRefreshKey++`, 알림 `기록을 수정했습니다.` / `기록을 삭제했습니다.`
  8. TravelMyMapWorkspace: `handleEditPhoto`를 5.6절대로 분기. 선택 바: `const selectedMarkerRecord = computed(() => markers.value.find(m => String(m.id) === String(selectedMarkerId.value)) || null)` → 사진 지도 패널 머리 아래 `.travel-map-selected-record`(4.1절), 버튼 `이 기록 수정` → `emit('open-record-editor', { planId: m.planId, recordId: m.id })`.
- 완료 기준: 지도에서 라이트박스 `수정` → 설정 화면 전환 없이 다이얼로그, 저장 후 지도 유지·갱신. 달력 딥링크(sourceType `memory`)는 기존처럼 설정 2단계 편집기를 연다.
- 검증: 빌드·단위, 기록·지도 회귀, 7.3절 E1~E2.

### Step 9. 내 장소 사진 입력 보완 (P1)

- 수정: `RecordMapWorkspace.vue`
- 내용:
  1. `photoPicked`(247~274행): 메타데이터 적용 뒤 `visitForm.date`가 채워졌고 `sequence === metadataSequence`이며 사용자가 `방문 날짜와 이야기도 기록`을 직접 건드리지 않았다면 `placeForm.recordNow = true`, `visitDateFromPhoto.value = visitForm.date`. 장소 폼 `사진과 방문 기록` 구획에 `<p v-if="visitDateFromPhoto && placeForm.recordNow" class="record-map-note" role="status">사진 촬영일({{ visitDateFromPhoto }})로 방문 날짜를 채웠습니다. 날짜 없이 저장하려면 ‘방문 날짜와 이야기도 기록’을 해제하세요.</p>`. 체크박스에 `@change="recordNowTouched = true"`. `beginPlace`·`editPlace`에서 두 값 초기화.
  2. `visitFilesPicked`(275행): 교체 대신 추가 `photosPending.value = [...photosPending.value, ...files]`, `event.target.value = ''`. 개수 제한은 기존 `validateFiles`가 저장 때 검사(20장).
  3. 대기 사진 목록: 장소 폼·방문 폼의 사진 칸 아래 `<ul class="record-map-pending-photos">`에 미리보기(`URL.createObjectURL`, 56x56, `decoding="async"`)와 파일명, `<button type="button" :aria-label="file.name + ' 빼기'">×</button>`. 미리보기 URL은 `watch(photosPending)`에서 이전 URL revoke, 언마운트 시 revoke. 기존 문구 `선택한 사진 {n}장…`은 유지.
  4. label `사진 · 선택`, `첨부 사진`, 버튼 이름·다이얼로그 이름은 그대로(3.7절 계약).
- 완료 기준: EXIF 촬영일 있는 사진으로 기록 → 방문 날짜가 저장된다. EXIF 없는 PNG는 기존처럼 날짜 null(기존 e2e 'a place can have a new category and photo without inventing a visit date' 통과).
- 검증: 빌드·단위, record-map e2e 전체, 7.3절 R1.

### Step 10. 마무리 — 회귀·캡처·정리

- 내용: 4.6절 계약 재점검. 7.5절 캡처(라이트/다크 × 데스크톱/모바일)로 비교. 임시 캡처 스펙 삭제, 띄운 vite 종료.
- 완료 기준: 부록 A 체크리스트 전부.
- 검증: `npm run build`, `npm run test:unit`, 기록·지도 회귀 전체(데스크톱+모바일), `git diff --stat`으로 금지 파일(0장 3번) 변경 없음 확인.

---

## 7. 테스트 계획

### 7.1 실행 명령

| 목적 | 명령(PowerShell, `C:\project\calen\frontend`) |
|---|---|
| 빌드 | `npm run build` |
| 단위 전체 | `npm run test:unit` |
| 이번 단위만 | `node --test tests/unit/travelImport.test.js tests/unit/recordMapPhotoLocation.test.js tests/unit/recordMapView.test.js` |
| 가져오기 e2e | (6장 공통 환경변수) `npx playwright test e2e/travel-import.spec.js --config playwright.config.js --reporter=line --output="$env:TEMP\pw-rm-input"` |
| 데스크톱만 / 특정 테스트 | `--project=chromium-desktop` / `-g "이름 일부"` |

### 7.2 단위 테스트 — `tests/unit/travelImport.test.js`

1. `classifyImportFile`: jpg(대문자 확장자·빈 type), png, webp, heic 거부 문구, 16MB 사진 거부, gpx(확장자/MIME), 11MB gpx 거부, txt 거부.
2. `parseGpxText`: trkpt 정상(거리·시간), rtept, 자기 닫힘 `<trkpt lat=".." lon=".."/>`, 작은따옴표·속성 순서 반대, 시각 없음 → duration 0·startTime null, 점 1개 → 실패 문구, `<gpx` 없음 → 실패 문구, 연속 중복 좌표 제거, 범위 밖 좌표 제외.
3. `haversineKm` 근사값(오차 1% 이내).
4. `thinRoutePoints`: 원본 함수(TravelRouteWorkspace 749~768행)를 테스트 안에 그대로 복사해 5, 900, 901, 5,000점에서 결과 비교, 마지막 점 보존.
5. `localDateTime`: UTC 03:00 시각의 날짜(TZ 독립 검사), 시·분 0 채움.
6. `groupPhotos`: 5.1절 예, 250m 초과 분리, 같은 장소라도 121분 차이 분리, 다른 날짜 분리, GPS 없는 사진의 30분 이내 합류/초과 시 위치 없음 묶음, 날짜·GPS 없는 사진 하나의 묶음, 51장 분할.
7. `createPhotoDraft`/`createTrackDraft`/`createPlaceDraft`: dateSource·fallback, 트랙 previewPoints ≤2000.
8. `sortDrafts`, `groupDraftsByDate`(일차 라벨, `날짜 확인 필요` 그룹이 마지막).
9. `inferPeriod`(fallback 제외), `suggestPlanId`(포함하는 여행 중 시작일 최신, 기간 없는 여행 제외, 없으면 '').
10. `validateDraft` 각 문구, 제외 항목은 검사하지 않음.
11. `summarizeDrafts` 집계.
12. `buildMemoryPayload`: 공백 trim, 121자 → 120자, 메모 빈칸 → null, 좌표 null 유지, `sharedWithCommunity:false`.
13. `buildRoutePayload`: 거리 0.004 → 0.01, WALK 걸음 수, BUS 0, 점 ≤900·pointType 'ROUTE', 소수 7자리.
14. `buildNewPlanPayload`: 기간 없음, 과거 종료일 → COMPLETED, 미래 → PLANNED, KRW·1·#3182F6.
15. `toImportErrorMessage`: 각 분기.
16. `formatImportNotice`: 0인 항목 생략.
17. `runImportSave`(가짜 api): ① 성공 순서(여행→기록→사진→경로→GPX) ② 사진 업로드 실패 후 재호출 시 `createMemory` 추가 호출 0회·업로드만 1회 ③ 여행 생성 실패 시 기록 호출 0회 ④ 새 여행은 재시도에서 다시 만들지 않음 ⑤ GPX 원본 실패 → 'warning', 재시도 시 경로 생성 0회 ⑥ 제외(include=false)·saved 항목 건너뜀.

### 7.3 e2e 시나리오 — `e2e/travel-import.spec.js`

공통: 7.4절 상태형 목. 모든 테스트 끝에 `expect(state.errors).toEqual([])`. 데스크톱·모바일 프로젝트 모두 실행.

| ID | 시나리오 | 핵심 단언 |
|---|---|---|
| I1 GPX 가져오기 | `/#my-map` → 여행 select `90` → `＋ 사진·GPX·장소 추가` → GPX(2026-10-02 시각, 1,200점) 파일 선택 | 항목 `data-kind="gpx"` 1개, `날짜` 값 `2026-10-02`, `저장` → POST `/api/travel/plans/90/routes` body `routeDate:'2026-10-02'`, `sourceType:'GPX'`, `points.length ≤ 900`, gpx presign·complete 각 1회 → dialog 닫힘 → `/api/travel/my-map` 재요청 1회 이상 → `.travel-map-import-notice`에 `경로 1개` |
| I2 GPX 3개·날짜별 | 10-01/10-02/10-03 GPX 3개 한 번에 | 경로 항목 3개·날짜 3종, 저장 시 POST routes 3회 |
| I3 깨진 파일 | `broken.gpx`(점 없음), `note.txt`, `bad.jpg`(이미지 아닌 바이트) | `.travel-import-rejected li` 3개 + 각 이유, 항목 0개, `저장` 비활성 |
| I4 사진 묶음 검토 | EXIF JPEG 3장(2장 50m 이내 같은 날, 1장 다른 날) + EXIF 없는 PNG(캔버스로 만든 정상 PNG) | 항목 3개(사진 2장 묶음 1, 1장 묶음 1, 위치 없음 1), 묶음 제목이 역지오코딩 장소명, PNG 항목에 `날짜 추정`·`위치 없음` 칩 → `지도에서 위치 지정` → 지도 클릭 → `위치 없음` 사라짐 → 저장 → POST memories 3회(첫 묶음 payload `memoryDate:'2026-10-02'`, 좌표 있음), presign 3회(첫 묶음 files 2개) |
| I5 부분 실패·재시도 | I4와 같되 두 번째 presign을 1회만 503 | 항목 상태 `실패: 파일 저장소…` 또는 한국어 문구(영어 원문 없음), 버튼 `남은 항목 다시 저장` → POST memories 총 3회 유지(중복 0), 실패 항목 presign만 재시도 → 닫힘 |
| I6 장소 검색 | `장소 검색`에 `서울역` → `장소 찾기` → 결과 `서울역` 클릭 | 항목 `data-kind="place"` 제목 `서울역`, 저장 → memory payload 좌표 37.556/126.971. 검색 요청은 `/api/record-map/locations/search`뿐(외부 호스트 요청 0 — 목이 외부를 abort하므로 pageerror 없음 확인) |
| I7 새 여행으로 가져오기 | 지도 여행 select가 `전체 여행`인 상태에서 열기 → 기존 여행 기간 밖 날짜(예: 2026-11-14)의 EXIF 사진 → `저장할 여행`이 빈 값(추천 없음)이고 `저장` 비활성인지 확인 → `새 여행 만들기` 선택 → `새 여행 이름` 입력 | `새 여행 시작일/종료일`이 사진 날짜 최소/최대, 저장 → POST plans(body 날짜·`homeCurrency:'KRW'`) 다음 POST `/plans/{새 id}/memories` → 지도 여행 select 값이 새 id |
| I8 모바일 레이아웃 | 모바일 프로젝트만: 다이얼로그 열고 사진 3장 | 다이얼로그 `boundingBox` = 뷰포트, `저장` 버튼이 스크롤 없이 뷰포트 안, `document.documentElement.scrollWidth <= 390`, dialog `scrollWidth <= clientWidth` |
| I9 닫기 확인·이탈 가드 | 항목 있는 상태에서 `창 닫기` → confirm dismiss | 다이얼로그 유지, accept → 닫힘, `/api/travel/plans/*/memories` POST 0회 |
| I10 Esc | 항목 없는 상태에서 Esc | 닫힘, 포커스가 `＋ 사진·GPX·장소 추가` 버튼으로 복귀 |
| I11 현재 위치 | `context.grantPermissions(['geolocation'])` + `setGeolocation({ latitude: 35.0, longitude: 135.77 })` → `현재 위치` | 장소 항목 1개, 좌표 일치 |
| E1 기록 수정(P1) | 지도 클러스터 → `사진 크게 보기` → `수정` | dialog `기록 수정`(설정 화면 `.travel-setup-workflow` 0개), 제목·날짜 변경 → `저장` → PUT `/api/travel/memories/501` body 변경값 → 닫힘, my-map 재요청 |
| E2 사진·기록 삭제 확인(P1) | 수정 다이얼로그에서 사진 삭제 → confirm dismiss → DELETE 0회, accept → DELETE 1회 | |
| L1 레거시 GPX 날짜(P1) | 설정 3단계에서 GPX(10-02) 선택 | `날짜` 값 `2026-10-02`, 깨진 GPX → `role=alert` 안내 |
| L2 레거시 삭제 확인(P1) | 2단계 표 `삭제` → dismiss | DELETE 요청 0회 |
| L3 모바일 2단계 넘침(P1) | 모바일, `기록 목록·편집` | `.travel-memory-map-panel` 폭 ≤ 뷰포트 폭 |
| L4 레거시 단일 사진(P1) | `기록 목록·편집` → `새 기록` → EXIF JPEG 1장 → 제목 → `기록 저장` | POST memories 1회 + presign 1회, 편집기 닫힘, `여러 장 한 번에 가져오기` 클릭 → dialog `여행에 추가` |
| R1 내 장소 사진 촬영일(P1) | `/#travel` → `더보기` → `사진으로 기록` 숨은 input에 EXIF JPEG 1장 | `방문 날짜와 이야기도 기록` 체크됨, `방문 날짜` 값 = EXIF 날짜, 저장 → visit body `date` = EXIF 날짜 |

### 7.4 목·픽스처 작성법 (저장소에 파일을 두지 않는다)

- 상태형 목: `record-map.spec.js` 46~125행의 `mock()` 구조를 복사하고 여행 엔드포인트를 상태로 처리한다: `GET /api/travel/portfolio`, `GET /api/travel/my-map`(상태의 기록·사진·경로로 계산), `GET /api/travel/my-map/photo-clusters/{id}`, `GET/POST /api/travel/plans`, `GET/PUT /api/travel/plans/{id}`(detail에는 `memoryRecordCount`·`mediaItemCount`·`routeSegmentCount`도 채운다), `POST /api/travel/plans/{id}/memories`, `PUT /api/travel/memories/{id}`, `POST /api/travel/memories/{id}/media/presign`(요청 files마다 `{ uploadUrl:'/__mock-upload/…', method:'PUT', objectKey, originalFileName, contentType, fileSize, thumbnails: 요청 thumbnails 각각에 uploadUrl·objectKey }`, `uploadMode:'PRESIGNED'`), `…/media/complete`(상태에 mediaItems 추가), `POST /api/travel/plans/{id}/routes`, `POST /api/travel/routes/{id}/gpx-files/presign|complete`, `GET /api/travel/geocode/reverse`(좌표로 장소명 결정), `GET /api/record-map/locations/search`, `**/__mock-upload/**` → 200. 외부 호스트는 `route.abort()`.
- EXIF JPEG: 테스트 안에서 캔버스 JPEG을 만들고 APP1(EXIF)을 SOI 바로 뒤에 끼운다.
  ```js
  function exifSegment({ lat, lon, dateTime }) { // dateTime 'YYYY:MM:DD HH:mm:ss', 북위·동경만
    const t = Buffer.alloc(178)
    const entry = (at, tag, type, count, value) => { t.writeUInt16LE(tag, at); t.writeUInt16LE(type, at + 2); t.writeUInt32LE(count, at + 4); t.writeUInt32LE(value, at + 8) }
    const ref = (at, tag, text) => { t.writeUInt16LE(tag, at); t.writeUInt16LE(2, at + 2); t.writeUInt32LE(2, at + 4); t.write(text + '\0', at + 8, 'ascii') }
    const dms = (at, v) => { const d = Math.floor(v), mf = (v - d) * 60, m = Math.floor(mf), s = Math.round((mf - m) * 60000)
      ;[[d, 1], [m, 1], [s, 1000]].forEach(([n, den], i) => { t.writeUInt32LE(n, at + i * 8); t.writeUInt32LE(den, at + i * 8 + 4) }) }
    t.write('II', 0, 'ascii'); t.writeUInt16LE(42, 2); t.writeUInt32LE(8, 4)
    t.writeUInt16LE(2, 8); entry(10, 0x8769, 4, 1, 38); entry(22, 0x8825, 4, 1, 76); t.writeUInt32LE(0, 34)          // IFD0 → Exif, GPS
    t.writeUInt16LE(1, 38); entry(40, 0x9003, 2, 20, 56); t.writeUInt32LE(0, 52); t.write(dateTime + '\0', 56, 'ascii') // DateTimeOriginal
    t.writeUInt16LE(4, 76); ref(78, 0x0001, 'N'); entry(90, 0x0002, 5, 3, 130); ref(102, 0x0003, 'E'); entry(114, 0x0004, 5, 3, 154); t.writeUInt32LE(0, 126)
    dms(130, lat); dms(154, lon)
    const payload = Buffer.concat([Buffer.from('Exif\0\0', 'binary'), t])
    const head = Buffer.alloc(4); head.writeUInt16BE(0xFFE1, 0); head.writeUInt16BE(payload.length + 2, 2)
    return Buffer.concat([head, payload])
  }
  const withExif = (jpeg, meta) => Buffer.concat([jpeg.subarray(0, 2), exifSegment(meta), jpeg.subarray(2)])
  // jpeg: page.evaluate(() => { const c = document.createElement('canvas'); c.width = 96; c.height = 64; … return c.toDataURL('image/jpeg', .8).split(',')[1] })를 Buffer로
  ```
  이 방식으로 만든 JPEG은 `extractPhotoMetadata`가 날짜·시간·위경도를 읽는다(작성 시 확인). 정상 PNG도 캔버스 `toDataURL('image/png')`로 만든다. **1x1 base64 PNG(`record-map.spec.js` 3행)는 클라이언트 썸네일 준비에서 디코딩 실패하므로 정상 사진 픽스처로 쓰지 않는다**(작성 시 `The source image could not be decoded.` 확인).
- GPX: `<gpx><trk><trkseg>` + `trkpt`(20초 간격 `<time>`)를 문자열로 만들어 `{ name, mimeType: 'application/gpx+xml', buffer }`로 `setInputFiles`.
- 파일 넣기: `dialog.getByLabel('사진·GPX 파일 선택').setInputFiles([...])`(시각적으로 숨긴 input이어도 가능).

### 7.5 스크린샷 확인 방법

- 임시 스펙 `frontend/e2e/zz-rm-input-capture.tmp.spec.js`(7.4절 목 재사용)를 만들고 테마는 `page.addInitScript(() => localStorage.setItem('calen-theme-mode', 'toss'))`(다크) / 기본(라이트). 출력은 저장소 밖 임시 폴더. **끝나면 삭제.**
- 촬영 대상: ① `#my-map` 헤더(데스크톱·모바일) ② 가져오기 빈 상태 ③ 사진 4장 + GPX 1개 검토 상태(데스크톱·모바일, 라이트·다크) ④ 위치 찍기 중 ⑤ 부분 실패 상태 ⑥ 저장 후 지도 + 알림 ⑦ 기록 수정 다이얼로그 ⑧ 레거시 2단계 모바일(넘침 없음) ⑨ 레거시 3단계 GPX 로드 후 ⑩ 내 장소 사진으로 기록(촬영일 문구).
- 개편 전 기준 캡처(부록 B)와 같은 구도로 비교한다(임시 폴더라 없을 수 있음 — 없으면 생략).

---

## 8. 리스크와 비범위

### 8.1 리스크와 대응

| 리스크 | 대응 |
|---|---|
| 네이티브 `<dialog>`(top layer) 안의 Leaflet 지도 크기 계산 | 다이얼로그가 열린 다음 프레임에 지도가 마운트되도록 `RecordMapDialog`의 `open` 이후 렌더(기본 동작). 크기가 0이면 `view-key` 증가로 다시 맞춤. 장소 폼이 이미 같은 구조로 동작 중(record-map 'fullscreen detail…' 테스트) |
| `:has()` 미지원 브라우저 | 시야·크기 버튼이 보일 뿐 기능 손실 없음 |
| 역지오코딩 서버 직렬 처리로 지명 표시가 늦음 | 묶음당 1회, 순차, 제목은 파일명으로 먼저 표시. 저장은 지명 없이도 가능 |
| 사진 대량(100장+) 메모리 | 미리보기 160x120, 동시 2개, 200개 제한, revoke 철저 |
| `createImageBitmap` 디코딩 검사 비용 | 동시 2개. 미지원이면 검사 생략(업로드 단계 오류를 한국어로 안내) |
| 묶기 규칙이 사용자의 기대와 다름 | 묶음에서 사진 빼기(×) 제공, 규칙 수치는 상수로 노출. 묶음 나누기·합치기는 P2 |
| 새 여행 생성 후 전부 실패 → 빈 여행 남음 | 알림에 생성 사실 표시. 자동 삭제하지 않는다(사용자 데이터 삭제 금지) |
| 기존 공개 지도 링크에 새 사진이 자동 노출 | 링크 목록 API가 없어 경고 불가 → P2(백엔드 조회 API 필요). 문서에 기록 |
| 레거시 일괄 모드 삭제로 인한 단일 사진 경로 회귀 | Step 7 grep 확인 + L4 e2e |
| 헤더 진입점 변경으로 사용자 학습 비용 | 주 버튼 문구에 `사진·GPX·장소`를 모두 적고, 레거시 목록은 `기록 목록·편집`으로 명확히 분리 |
| 다른 작업자의 api.js·style.css 변경 | 두 파일 수정 금지, import만 사용 |
| e2e 두 개 수정 시 시나리오 손실 | 4.6절 매핑대로, 스크롤 잠금 해제·작업할 여행 값·기간 없음 탭 숨김 단언을 그대로 유지 |

### 8.2 비범위 (P2 — 이번에 구현하지 않음)

1. 묶음 나누기·합치기, 드래그로 순서 바꾸기, 여러 항목 날짜 일괄 변경.
2. 이미 있는 기록과의 중복 감지(같은 파일명·촬영 시각) 및 기존 기록에 합치기.
3. 경로를 지도에서 눌러 이름·날짜·이동 수단 바로 수정(`기록 수정` 다이얼로그의 경로판), 레거시 3단계 화면 축소(패널 5개 → 2개, 저장 버튼 1개, GPX 입력을 지도 옆으로).
4. GPX 출발·도착 장소 자동(시작·끝 좌표 역지오코딩), 경로를 기록 핀과 자동 연결.
5. HEIC 지원(디코딩 라이브러리 필요 → 의존성 금지 규칙과 충돌).
6. 공개 지도 링크 목록 API와 "이 여행은 공개 중" 경고(백엔드).
7. 지출(여행 가계부) 입력과 가져오기 연결 — 가계부 작업 범위.
8. 내 장소에서 거리가 먼 사진을 장소별로 나눠 저장(가져오기의 묶기 규칙 재사용), 내 장소에도 단일 가져오기 진입점.
9. `TravelHubWorkspace`(2,897행) 분해, 레거시 `travel-money` 화면 정리, 여행 상태 `테스트 여행(SAMPLE)` 숨김, 저장 버튼 중복 제거.
10. 지도 시야·크기 토글을 레거시 편집 화면에서도 숨기거나 기본값으로.
11. 다이얼로그를 데스크톱 오른쪽 패널(지도 가득 + 떠 있는 패널) 문법으로 통일(`docs/record_map_ux_workplan.md` 7.2-2와 함께).
12. 백그라운드 업로드(다이얼로그를 닫아도 계속), 업로드 일시 정지.
13. 죽은 코드 삭제: `TravelPublicTripsWorkspace.vue`, `LEGACY_TRAVEL_SHARING_ENABLED` 분기와 `TravelCommunityWorkspace.vue`.
14. `thinRoutePoints` 중복 정리(레거시 파일이 lib 함수를 쓰도록).

---

## 부록 A. 단계별 체크리스트

**Step 0**
- [ ] 무관한 미커밋 파일 목록 메모
- [ ] 단위(157)·빌드·기록·지도 회귀(132/8) 기준선 기록

**Step 1**
- [ ] `lib/travelImport.js` 상수·함수(5.1절) — 브라우저 API 0건
- [ ] `tests/unit/travelImport.test.js` 24개 이상, `thinRoutePoints` 원본 비교·`runImportSave` 가짜 api 포함
- [ ] `npm run test:unit` 통과

**Step 2**
- [ ] `extractPhotoMetadata(file, { reverseGeocode })` 기본 동작 불변
- [ ] `lib/useTravelImport.js` — intakeToken·searchToken 폐기 규칙, revoke, 이탈 가드
- [ ] 빌드·record-map 회귀 통과

**Step 3**
- [ ] `RecordMapDialog` `wide`·인스턴스별 제목 id
- [ ] `TravelImportDialog`·`TravelImportDraftItem` — 4.6절 이름, 키보드로 파일 선택 가능, 스티키 푸터
- [ ] 지도 검색·시야·크기 숨김(계산 스타일 확인), 지도 높이 정상

**Step 4**
- [ ] 헤더: `＋ 사진·GPX·장소 추가`, `기록 목록·편집`, 찍기 모드 코드 0건
- [ ] 저장 후 portfolio·지도·Hub 갱신, 지도 재생성 없음, 알림
- [ ] `overviewSequence`로 오래된 응답 폐기
- [ ] record-map.spec.js 2개 테스트 갱신(시나리오 유지)

**Step 5**
- [ ] `e2e/travel-import.spec.js` I1~I11 데스크톱·모바일 통과

**Step 6**
- [ ] GPX 날짜·실패 안내·저장 버튼 이유·타임라인 문구
- [ ] 삭제 확인 3곳, 남는 피드백 정리, 만들기 배지
- [ ] 포커스 가드, 모바일 넘침 0, Hub 헤더 선택자, 활성 표시, 숨은 오버뷰
- [ ] L1~L3 통과

**Step 7**
- [ ] 일괄 모드 제거(grep 0건) → `여러 장 한 번에 가져오기`
- [ ] 핀 종류·커뮤니티·표 체크박스·입력 방식 제거, placeholder·maxlength
- [ ] 서버 경유 위치 검색
- [ ] L4 통과

**Step 8**
- [ ] `TravelRecordEditDialog` — 위치 드래그·검색, 사진 추가/삭제(확인), 기록 삭제(확인)
- [ ] 라이트박스 `수정`(MEMORY)·선택 바 `이 기록 수정` → 다이얼로그, 달력 딥링크는 기존 경로
- [ ] E1~E2 통과

**Step 9**
- [ ] 사진 촬영일 → 방문 날짜 자동 체크·안내, 사진 추가(append)·미리보기·빼기
- [ ] R1 + 기존 record-map 사진 테스트 통과

**Step 10**
- [ ] 4.6절 계약 재점검, 7.5절 캡처 비교 후 임시 스펙 삭제, vite 종료
- [ ] 빌드·단위·기록·지도 회귀 전체 통과
- [ ] `style.css`·`App.vue`·`api.js`·`TravelMapPanel.vue`·`TravelMyMapClusterPanel.vue`·가계부·통합 달력·백엔드 diff 없음(`git diff --stat`)

## 부록 B. 분석 캡처 목록 (작업자 로컬 임시 폴더 `rm-input/`, 저장소 미포함)

접미사 `-d` 데스크톱 1440x900, `-m` 모바일 390x844, `-dark` 다크 테마.

| 파일 | 내용 |
|---|---|
| `T01-travel-map-*`, `T01b-travel-map-full-*` | 여행 지도 첫 화면(헤더 버튼 6개, 모바일 첫 화면에 지도 없음) |
| `T02-setup-step1-*`, `T02b-setup-step1-advanced-*` | 새 여행 만들기(이전 여행 배지, 다크에서 주 버튼 구분 안 됨) |
| `T03-after-create-*`, `T03b-race-editor-closed-*` | 만든 뒤 지도, 여행 전환 후 편집기 닫힘 경합 |
| `T04-step2-new-record-modal-*`, `T04b-…-bottom-*` | 새 기록 모달(옵션 21개, 모바일 1,512px) |
| `T05-single-photo-exif-*` | 1장 EXIF 자동 입력(제목은 빈칸) |
| `T06-batch-drafts-*`, `T06b-batch-still-open-after-save-*` | 여러 사진 카드, 부분 실패 후 초안이 남은 상태 |
| `T07-step2-after-batch-*` | 영어 오류 원문, 저장된 기록이 목록에 안 보임, 모바일 넘침 |
| `T08-step2-pending-pin-*` | 2단계 지도 클릭 임시 핀 카드 |
| `T09-step3-route-*`, `T10-gpx-broken-no-feedback-*`, `T11-gpx-loaded-*`, `T12-gpx-saved-*` | 경로 화면(4,207/5,779px), 깨진 GPX 무안내, GPX 로드 후(날짜 1일차, 타임라인 빈 문구 버그) |
| `T13-step4-summary-*`, `T14-map-after-input-*` | 요약 단계(목 응답에 count 필드가 없어 0으로 보임 — 실제 버그 아님), 입력 후 지도 |
| `T20-map-pick-mode-*`, `T21-map-pick-opens-setup-modal-*`, `T22-after-close-left-in-setup-*`, `T23-map-select-photo-*` | 메인 지도 찍기 → 설정 화면 모달(지명 없음, placeholder 혼동), 닫은 뒤 설정 화면에 남음 |
| `R01-records-*`, `R02-place-dialog-*`, `R02b-…-bottom-*`, `R03-more-menu-*`, `R04-photo-first-place-*`, `R04b-…-bottom-*`, `R05-after-photo-save-*`, `R06-visit-dialog-*`, `R07-travel-import-*` | 내 장소 입력(사진으로 기록 시 파일 칸 비어 보임·촬영일 버림 등) |
| `metrics-chromium-desktop.json`, `metrics-chromium-mobile.json` | 1.6절 측정값 원본 |
