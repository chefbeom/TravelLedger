# 통합 달력 UI/UX 개편 설계 분석 및 작업 지시서

- 대상: `frontend/src/components/IntegratedCalendarWorkspace.vue`와 그 하위로 새로 만들 컴포넌트, `frontend/src/lib/integratedCalendarCore.js`와 새 lib 파일, `frontend/e2e/integrated-calendar.spec.js`, `frontend/tests/unit/*`
- 범위: 이번 작업은 P0 + P1 + 필요한 리팩터링이다. P2는 7장 '후속'에만 적는다.
- 구현 담당자는 이 문서만 보고 작업한다. **5장 단계를 순서대로** 진행하고, 단계마다 빌드와 테스트를 통과시킨 뒤 다음 단계로 넘어간다.

---

## 0. 꼭 지킬 규칙 (먼저 읽을 것)

1. **백엔드 변경 금지.** `backend/` 아래 파일은 열람만 한다. API 경로·파라미터·응답 형식은 그대로 쓴다.
2. **새 npm 의존성 추가 금지.** `package.json`, `package-lock.json`을 수정하지 않는다. 아이콘·팝오버·포커스 트랩은 직접 구현한다(아래 설계 참고).
3. **이 작업과 무관한 미커밋 변경을 건드리지 않는다.** 작업 트리에는 `frontend/src/style.css`, `frontend/src/lib/api.js`, `.env.example`, 백엔드 파일 등에 다른 작업의 미커밋 변경이 있다. 이것들을 되돌리거나(`git checkout`, `git restore`, `git stash`) 정리하거나 포맷하지 않는다.
   - `style.css`와 `api.js`는 **수정할 필요가 없다.** 디자인 토큰은 컴포넌트 scoped 스타일 안에 정의한다(3.5절).
   - `git add -A` 같은 일괄 스테이징을 하지 않는다. 커밋은 사용자 지시가 있을 때만 하며, 그때도 이 작업 파일만 지정해서 스테이징한다.
4. **수정 가능 파일:** 아래 목록과 5장에서 지시하는 새 파일만 수정/생성한다.
   - 수정: `frontend/src/components/IntegratedCalendarWorkspace.vue`, `frontend/src/lib/integratedCalendarCore.js`, `frontend/e2e/integrated-calendar.spec.js`, `frontend/tests/unit/integratedCalendarCore.test.js`
   - 생성: `frontend/src/components/integrated-calendar/*.vue`, `frontend/src/lib/integratedCalendarView.js`, `frontend/src/lib/integratedCalendarBars.js`, `frontend/src/lib/useIntegratedCalendarSources.js`, `frontend/src/lib/useMediaQuery.js`, `frontend/tests/unit/integratedCalendarView.test.js`, `frontend/tests/unit/integratedCalendarBars.test.js`
   - `frontend/src/App.vue`는 수정하지 않는다. emit 이름과 페이로드를 유지하면 App 쪽 변경은 필요 없다.
5. **데이터 정합성 동작 유지(1.4절 표 전체).** UI를 바꾸더라도 계산 규칙은 한 줄도 바꾸지 않는다. 순수 함수로 옮길 때는 원래 코드를 그대로 옮기고 단위 테스트로 고정한다.
6. **접근성:** drawer, 모달, 바텀시트, 메뉴, 팝오버는 키보드로 열고 닫을 수 있어야 한다(Esc, 포커스 가두기, 닫은 뒤 원래 버튼으로 포커스 복귀). 아이콘만 있는 버튼에는 `aria-label`을 단다. 터치 대상은 최소 `2.75rem`(44px)이다. 라이트/다크 테마 모두 본문 대비 4.5:1 이상이어야 한다.
7. e2e 셀렉터를 바꾸면 **같은 단계에서** 테스트도 고친다. 기존 테스트가 검증하던 시나리오는 하나도 빠지면 안 된다(6.3절 매핑표).

---

## 1. 현행 구조 요약

### 1.1 파일과 역할

| 파일 | 역할 |
|---|---|
| `frontend/src/components/IntegratedCalendarWorkspace.vue` (510행) | 상태 약 40개, 데이터 로딩, 계산, 템플릿(365–474행), scoped 스타일(476–510행)이 모두 한 파일에 있음 |
| `frontend/src/lib/integratedCalendarCore.js` (73행) | `validCalendarDate`, `calendarMonthGrid`(42칸, UTC 기준), `calendarLedgerDay`(같은 날짜의 분할 결제만 묶음), `calendarLedgerMonth`, `overlapsCalendarDate` |
| `frontend/src/lib/api.js` 600–649행 | `/integrated-calendar/*` 호출 함수. 모두 `cache: 'no-store'` |
| `frontend/src/App.vue` 1386행 | `<IntegratedCalendarWorkspace @navigate @open-travel-source @open-place-source @open-ledger-source>`. 핸들러는 648–664행(`planId`, `placeId`, `date`+`entryId`가 없으면 무시) |
| `frontend/e2e/integrated-calendar.spec.js` (313행) | `mockCalendar()`로 모든 API를 목 처리한 Playwright 테스트 14개. `chromium-desktop`(1440x900)과 `chromium-mobile`(390x844, 터치) 두 프로젝트에서 모두 실행됨(마지막 뷰포트 테스트만 데스크톱 전용) |
| `frontend/tests/unit/integratedCalendarCore.test.js` | `node:test` 기반 단위 테스트 3개 (**vitest 아님**) |

### 1.2 상태 목록 (IntegratedCalendarWorkspace.vue)

| 묶음 | 상태 (행) |
|---|---|
| 날짜 | `today`(11), `year`/`month`/`selectedDate`(12), `yearDraft`(13), `grid` computed(14) |
| 필터 | `filter`(종류 단일 선택), `entryType`, `categoryId`, `paymentMethodId`, `collectionId`, `planId`(15), `settingsOpen`(16, 최초 1회 matchMedia), `hideAmounts`(17), `includeShared`(26) |
| 필터 목록 | `categories`, `paymentMethods`, `collections`, `plans`(18), `metadataErrors`, `metadataLoading`(19) |
| 월 요약 | `monthSources`{ledger, ledger-categories, places, travel, events}(20), `monthErrors`(21), `loadingMonth`(24) |
| 날짜 상세 | `daySources`{ledger, places, travel-records, routes, events}(22), `dayErrors`(23), `loadingDay`(24) |
| 미지정 | `undated`, `undatedError`, `undatedBusy`, `undatedPlacePage`, `undatedTripPage`(25) |
| 공유 여행 | `sharedOptions`, `sharedSummaries`, `sharedDays`, `sharedError`, `sharedBusy`, `sharedLoading`(26), `sharedSource`, `sharedSourceRef`, `sharedSourceBusy`(28) |
| 공유 지도 | `mapOptions`, `mapSummaries`, `mapDays`, `mapError`, `mapLoading`, `mapBusy`, `mapLink`(27), `mapSource`, `mapSourceRef`(29) |
| 경쟁 방지 | `monthSequence`, `daySequence`, `sharedSequence`, `sharedDaySequence`(30), `mapSequence`, `mapDaySequence`(31) |
| 일정 편집 | `draft`(32), `isEditingEvent`(33), `eventBusy`, `errorMessage`(17) |

계산값: `visible()`(36), `dailyLedger`(39), `dayPeriods`(41), `importedRecordIds`(44), `travelRecordsById`(45), `displayedTravelRecords`(46), `linkedImportedRecord`(47), `visibleSharedMapEntries`(48), `dayTimeline`(60–76), `summary`(77), `monthVisits`(80), `busiestExpenseDay`(81), `busiestVisitDay`(82), `filteredForDay()`(91–128, 함수이며 computed 아님).

### 1.3 데이터 흐름과 API 호출 시점

| 함수 | 호출 API | 언제 호출되나 |
|---|---|---|
| `loadMonth`(130–147) | `fetchCalendarSummary(source)` × 5 (ledger, ledger-categories, places, travel, events) + 내부에서 `loadShared()`, `loadSharedMaps()` 호출(139–140) | 마운트(356), `watch([year, month, entryType, categoryId, paymentMethodId, collectionId, planId])`(331), focus/visibilitychange(340), '다시 불러오기'(373), 일정 저장/삭제 후(320, 327) |
| `loadDay`(149–161) | `fetchCalendarDay(source, date, {collectionId, planId})` × 4 + `fetchCalendarEvents(date)`, 끝나면 `loadSharedDay()`, `loadSharedMapDay()` | 마운트, `watch([selectedDate, collectionId, planId])`(332), focus, '다시 불러오기', 일정 저장/삭제 후 |
| `loadShared`(213–244) | `fetchCalendarSharedOptions(type, page)`를 exhibit/record 두 종류 모든 페이지 + 선택된 항목마다 `fetchCalendarSharedSummary` → `loadSharedDay` | `loadMonth`가 호출할 때마다(즉 가계부 필터 변경 시에도), `watch(includeShared)`(333), 선택 변경 후(273) |
| `loadSharedDay`(257–269) | 선택된 공유마다 `fetchCalendarSharedDay` | `loadDay` 끝, `loadShared` 끝 (한 번 새로고침에 두 번 호출됨) |
| `loadSharedMaps`(162–179) | `fetchCalendarMapOptions(page)` 모든 페이지 + `fetchCalendarMapSummary` → `loadSharedMapDay` | `loadMonth`가 호출할 때마다, `watch(includeShared)`, 링크 추가·선택 변경 후(203, 209) |
| `loadSharedMapDay`(185–197) | `fetchCalendarMapDay` | `loadDay` 끝, `loadSharedMaps` 끝 |
| `loadUndated`(277–282) | `fetchCalendarUndated({placePage, tripPage})` | '날짜 미지정 자료' details를 열 때마다(414), 페이지 이동 시 |
| `loadFilterOptions`(342–352) | `fetchCategories`, `fetchPaymentMethods`, `requestRecordMap('/overview?page=0&size=1')`, `fetchTravelPlans` | 마운트, 재시도 버튼 |
| `submitEvent`/`removeEvent`(310–330) | `saveCalendarEvent`, `deleteCalendarEvent` → `loadMonth()`+`loadDay()` 전체 | 사용자 조작 |
| `openSharedTravelSource`(245–256) | `fetchTravelSharedExhibit` 또는 `fetchRecordSharedTravel` | '공유 여행 원본 보기' |

월 요약 응답 형식(백엔드 `CalendarSummaryService` 기준): ledger `[{date, income, expense, rowCount, transactionCount}]`, places `[{date, count}]`, travel `{periods:[{id,title,startDate,endDate,status}], routes:[{date,count}], records:[{date,type,count,importedCount}]}`, events `[{id,title,startDate,endDate}]`(월 요약에는 시간·종일 정보가 없음).

### 1.4 반드시 유지할 정합성 동작 (코드 근거)

| 규칙 | 현재 위치 | 새 위치 |
|---|---|---|
| 월 합계는 표시 중인 달(`monthStart`~`monthEnd`)만 합산, 이웃 달 칸은 제외 | `summary` 77–79, `monthVisits` 80, `busiest*` 81–82 | `integratedCalendarView.js`의 `buildMonthSummary()` |
| 분할 결제는 같은 날짜의 수단끼리만 1행, 금액은 `splitDisplayedAmount` | core `calendarLedgerDay` 39–56, 사용 39행 | 그대로(core). 타임라인 행 1개 = `dailyLedger.rows` 1개 |
| 가계부 일 필터(거래 구분·분류·결제수단)는 상세 데이터에 클라이언트 측에서 적용 | 39행 | 워크스페이스 `dailyLedger` computed 그대로 |
| 여행에서 가져온 장소와 여행 기록은 **원본 ID**(`sourceTravelRecordId` ↔ 기록 `id`)가 같을 때만 같은 항목. 장소 레이어가 보일 때만 기록 쪽을 숨김 | `importedRecordIds` 44, `displayedTravelRecords` 46, `linkedImportedRecord` 47 | `buildDayTimeline()` 내부. 로직 동일 |
| 월 셀의 여행 기록 수 = `count - importedCount`(장소 레이어가 보이고 월 장소 데이터가 있을 때만 차감) | 107행 | `buildDaySummaries()` |
| 공유 지도 항목 중 `TRAVEL_IMPORT`이고 같은 ID의 공유 여행 기록이 보이면 지도 쪽을 숨김 | `visibleSharedMapEntries` 48 | `visibleSharedMapEntries()` 순수 함수 |
| 같은 실제 여행(`planId`)을 가리키는 공유 2개는 1개만 표시 | 239행 `new Map(... [planId, value])` | composable `loadSharedSummaries()` |
| 공유 금액은 개인 합계에 포함하지 않음(월 KPI·일 합계는 `monthSources.ledger`/`dailyLedger`만 사용) | 77, 412행 문구 | KPI와 일 합계 계산에 공유 데이터 사용 금지. 공유 행에 '합계 제외' 배지 |
| 권한 철회: 요약/상세 조회 실패한 공유는 목록에서 제거, 옵션에서 사라지면 표시 안 함 | 174–175, 192–195, 239–240, 264–267 | composable 동일 |
| 원본 열기 직전 권한·선택 재확인 | 250행 | composable `openSharedTravelSource()` 동일 + 공유 지도도 같은 확인 추가 |
| 시퀀스 번호로 늦게 도착한 응답 무시, 언마운트 시 모든 시퀀스 증가 | 30–31, 143, 155, 169, 229, 262, 359 | composable: 출처별 시퀀스 + `dispose()` |
| 일정 버전 충돌(409) 메시지 | 321행: `다른 화면에서 일정이 변경되었습니다. 다시 불러온 뒤 수정해 주세요.` / 328행: `다른 화면에서 일정이 변경되었습니다. 다시 불러와 주세요.` | EventDialog / 삭제 처리. 문구 그대로 |
| 일정 검증 규칙 | 313행 | EventDialog `validate()` 그대로 |
| 일정 저장 페이로드 | 318–319행 | 동일 (`title.trim()`, `allDay`이면 시간 null, `version` 포함) |
| 다일 일정의 마지막 날은 종료 시간 표시 | 70–71행 | `buildDayTimeline()` `timeLabel: '종료 HH:MM'` |
| 날짜 없는 장소/기간 없는 여행은 날짜를 임의로 만들지 않음 | 414–422 | Drawer의 '날짜 미지정 자료' |
| 가계부 00:00은 미입력과 구분 불가(안내) | 437, 450 | 행의 ⓘ 툴팁으로 이동(문구 유지) |

### 1.5 emit 계약 (변경 금지)

워크스페이스(최상위 컴포넌트)만 App으로 emit한다. 하위 컴포넌트는 `open` 이벤트로 워크스페이스에 알리고, 워크스페이스가 아래 페이로드로 변환한다.

| 동작 | emit | 페이로드 (현재 코드와 동일해야 함) |
|---|---|---|
| 가계부 행 | `open-ledger-source` | `{ date: selectedDate, entryId: row.parts[0].id }` (452) |
| 연결된 여행 지출 | `open-travel-source` | `{ sourceType: 'ledger', planId: row.parts[0].travelPlanId, recordId: row.parts[0].travelRecordId }` (452) |
| 장소 | `open-place-source` | `{ placeId: place.placeId, visitId: place.sourceKind === 'VISIT' ? place.id : null }` (456) |
| 여행 기록(가져온 장소의 연결 기록 포함) | `open-travel-source` | `{ sourceType: record.type === 'MEMORY' ? 'memory' : 'ledger', planId: record.planId, recordId: record.id }` (289) |
| 여행 기간 | `open-travel-source` | `{ sourceType: 'plan', planId: period.id }` (460) |
| 경로 | `open-travel-source` | `{ sourceType: 'route', planId: route.planId, routeId: route.id, routeDate: selectedDate }` (463) |
| 미지정 장소 / 여행 | `open-place-source` / `open-travel-source` | `{ placeId: item.id }` / `{ sourceType: 'plan', planId: item.id }` (419) |
| `navigate` | 선언만 되어 있고 현재 사용처 없음 | `defineEmits`에서 **지우지 않는다**(App이 리스너를 붙이고 있음) |

---

## 2. 문제점 목록

### A. 위계 (확인됨)
- A1. 달력 위에 헤더(368–374) → 필터 details(376, 데스크톱 기본 펼침 16행) → 그 안의 공유 선택 details 2개(389, 394) → 오류 알림 최대 4개(400–403) → 요약 줄(404–413) → 날짜 미지정 details(414)가 쌓인다. 달력 그리드는 그 아래(423)에서 시작한다(보고 기준 데스크톱 약 600px, 390px 모바일 약 870px).
- A2. 필터는 select 6개 + 체크박스 2개를 한 줄로 나열(378–387). 어떤 필터가 어떤 출처에 영향을 주는지 구분이 없다.
- A3. 요약(404–413)은 `<span>` 나열이라 강조가 없고, 날짜가 ISO(`2024-02-15`) 그대로 나온다(408, 410).
- A4. 상세 제목도 ISO(`2024-02-15 상세`, 436).

### B. 셀 (확인됨)
- B1. 출처별 색, 수입/지출 색, 주말 색이 없다. 오늘 표시는 `<small> 오늘</small>` 텍스트뿐(429)이고 모바일에서는 숨겨진다(509).
- B2. 다일 여행/일정이 연속 바가 아니라 칸마다 `일정 기간 · 제목` 텍스트를 반복한다(102–105, 112–115).
- B3. 셀에 2개만 표시하고(430) `+N 더보기`(431)는 날짜 선택 외 아무 동작이 없다.
- B4. 모바일은 `−5만`, `여행+`, `공유−` 같은 축약어와 범례 문단(425)에 의존하고 글자 크기가 `0.6rem`(약 9.6px, 509)이다.
- B5. 다른 달 칸은 `opacity: .55`(494)라 글자 대비가 떨어진다.

### C. 상세 패널 (확인됨)
- C1. '시간순 요약'(437)과 출처별 섹션(448–468)이 같은 항목을 두 번 보여준다.
- C2. 모든 카드에 전폭 '원본 보기' 버튼이 있고 카드당 최대 2개(452, 456).
- C3. 기술 안내문 노출: `가계부의 00:00은 저장된 값이며…`(437), `(저장된 시간 중 가장 빠름)`(450).
- C4. 일정 폼이 패널 중간에 인라인으로 끼어든다(439–447). 삭제는 `window.confirm`(325).
- C5. 모바일에서 상세가 달력 아래에 붙어 스크롤이 길다(507).
- C6. 공유 원본은 페이지 맨 아래에 embed되고 `scrollIntoView`로 이동한다(471–472, 183, 253).
- C7. 비어 있는 섹션도 제목과 '거래가 없습니다' 등 문구를 모두 보여준다(448, 455, 463, 464).

### D. 동작 (확인됨)
- D1. `loadMonth`/`loadDay`가 시작 시 데이터를 `null`로 비운다(142, 154). focus/visibilitychange 재조회(335–341)마다 달력과 상세가 깜빡인다.
- D2. `filteredForDay()`가 셀당 **최대 6회** 호출된다(428에 3회, 430에 1회, 431에 2회). 각 호출이 `find`/전체 순회를 한다.
- D3. 가계부 필터를 바꿔도 `loadMonth` 안에서 `loadShared`/`loadSharedMaps`가 공유 옵션 **전체 페이지**를 다시 조회한다(139–140, 331).
- D4. `moveMonth`와 월 select/연도 변경이 항상 1일을 선택한다(296, 303–304). 현재 달로 돌아와도 오늘이 선택되지 않는다.
- D5. 연도 number input + 월 select + 이전/다음 버튼이 중복이고, '다시 불러오기'가 큰 텍스트 버튼으로 노출된다(370–373).

### E. 일관성 (확인됨)
- E1. 하드코딩 색 `#159665`(480, 484, 494), `#bd3947`(499), `#9aaa91`(fallback 다수).
- E2. 오류 알림이 출처별로 여러 개 쌓이고(400–403, 390, 416, 448 등) 각자 재시도 버튼이 있다.

### F. 새로 발견한 문제
- F1. **전역 CSS 간섭.** `style.css`에는 다음 전역 규칙이 있어 컴포넌트 스타일이 무시되기 쉽다.
  - `.app-shell :where(button, .button, [role="button"])`(33559행): `background-color`, `border-color`를 `!important`로 강제(라이트에서는 투명, 다크에서는 `--dark-overlay`). hover/focus-visible(33567행)도 `!important`. **현재 scoped의 `button { background: var(--panel-bg) }`(479)는 실제로 적용되지 않는다.**
  - `.app-shell *, [role="dialog"] * { border-radius: 0 !important }`(33535행 부근): 앱 전체가 각진 디자인이다. 둥근 점·칩은 사각형이 된다.
  - 다크 테마 `html[data-theme='toss'] body .app-shell :is(..., [role='dialog']) :is(button, input, select, textarea)`(35920행 부근, 특이도 0,4,2 + `!important`): 다이얼로그 안 버튼 배경을 `--dark-modal-soft`로 강제한다.
  - 전역 클래스 이름 충돌: `.chip`, `.alert`, `.banner`, `.popover`, `.tooltip`, `.is-active`(primary 버튼 색으로 바뀜), `.is-selected`, `.modal`, `.dialog`. `[aria-selected='true']`에도 전역 색 규칙이 있다(19977행).
  - 전역 `:focus-visible` 링(`outline: 3px solid var(--focus-ring)`, 116행)이 이미 있다. 컴포넌트에서 따로 outline 색을 지정할 필요가 없다.
- F2. **라이트 테마 의미 토큰이 구분되지 않는다.** 최종 라이트 값(33293행 블록)은 `--income: #588157`, `--expense: #3a5a40`, `--danger: #3a5a40`로 모두 녹색 계열이다. 전역 토큰만으로는 수입/지출/오류를 색으로 구분할 수 없다. 다크 최종값(35483–35484, 36242행)은 `--income: #86c79b`, `--expense: #e88776`로 구분된다. → 달력 전용 의미 토큰이 필요하다(3.5절).
- F3. `--calendar-day-bg`(라이트 `#f8fafc` 차가운 흰색, 68행), `--calendar-panel-bg`, `--calendar-section-bg`, 다크 `--calendar-day-bg: rgba(54,65,84,.62)`(9083행, 슬레이트 블루)는 현재 팔레트(라이트 크림/포레스트 `#f4f1e8`, 다크 뉴트럴 `#1d2427`)와 어긋난다. CalendarWorkspace는 이 값을 `!important` 오버라이드로 덮어서 쓴다(36780행 이후). 통합 달력은 `--surface-elevated`, `--surface-soft`, `--panel-bg`, `--line`, `--text`, `--text-soft`, `--brand-strong`, `--focus-ring`, `--modal-overlay`를 기반으로 쓴다.
- F4. 라이트 `--text-muted`(#588157)는 `--surface-soft`(#ebe8dd) 위에서 대비 3.65:1이다. 작은 글자에 쓰지 말고 `--text-soft`를 쓴다.
- F5. `loadShared`/`loadSharedMaps`가 시작할 때 `sharedSource = null`, `mapSource = null`(215, 164)로 만든다. focus 재조회만 해도 열어 둔 공유 원본이 닫힌다.
- F6. 일정 저장/삭제 후 `loadMonth()`+`loadDay()` 전체를 다시 부른다(320, 327). 그 결과 공유 옵션 전 페이지까지 재조회한다.
- F7. 필터 하나만 바뀌어도 월 요약 5개 출처를 모두 다시 요청한다(131–145). 예: 가계부 분류만 바꿔도 장소·여행·일정 요약을 다시 받는다.
- F8. 공유 여행 기간 항목 key `shared-${type}-${shareId}`(119)는 한 공유에 기간이 여러 개면 중복된다.
- F9. `errorMessage` 하나를 일정 폼과 삭제가 함께 쓴다(17, 308, 326). 저장 뒤 포커스 복귀가 없다.
- F10. `settingsOpen` 초기값만 matchMedia로 정한다(16). 창 크기를 바꿔도 반영되지 않는다.
- F11. 셀 `aria-label`이 ISO 날짜로 시작하고 요일이 없다. 오늘 칸에 `aria-current="date"`가 없다(428).
- F12. 썸네일 `<img>`에 `width`/`height`가 없어 레이아웃이 밀린다(456, 461, 466).
- F13. 한 번 새로고침하면 `loadSharedDay`가 두 번 호출된다(`loadDay` 끝 159 + `loadShared` 끝 241). 지도도 같다.
- F14. 미지정 자료를 details를 열 때마다 다시 조회한다(414).
- F15. **App의 모바일 스크롤 잠금 규칙:** `App.vue` 115–131, 937–950행은 화면 폭 760px 이하(또는 터치)에서 DOM에 `[role="dialog"][aria-modal="true"]`가 하나라도 있으면 body 스크롤을 잠근다. 닫힌 다이얼로그 요소에 이 속성이 남아 있으면 모바일에서 페이지 스크롤이 영구히 잠긴다. → 모든 다이얼로그는 `v-if`로 열려 있을 때만 DOM에 둔다.

---

## 3. 목표 정보 구조와 화면 설계

### 3.1 원칙
1. **달력이 첫 화면의 주인공이다.** 그리드 상단은 워크스페이스 상단에서 데스크톱 220px, 모바일 260px 이내여야 한다(오류 배너가 없을 때).
2. 자주 쓰는 조작(월 이동, 레이어 켜고 끄기)은 툴바에 둔다. 가끔 쓰는 조작(세부 필터, 공유 선택, 미지정 자료)은 오른쪽 drawer로 보낸다.
3. 색은 정보다. 출처마다 색이 하나씩 있고, 같은 색을 칩·바·점·타임라인 줄무늬에 똑같이 쓴다. 공유 항목은 같은 출처 색에 점선 테두리(모바일은 빗금)와 '공유' 배지를 붙인다. 색만으로 구분하지 않도록 아이콘·텍스트·부호(+/−)를 함께 쓴다.
4. 재조회는 조용히 한다. 데이터가 있으면 그대로 보여주고, 툴바 새로고침 아이콘 회전과 `aria-busy`로만 진행 중임을 알린다.
5. 오류는 한 곳(툴바 아래 배너 1개)에 모으고 재시도 버튼도 1개만 둔다.

### 3.2 데스크톱 와이어프레임 (1440x900, 폭 60rem 초과)

```
┌ section.integrated-calendar ─────────────────────────────────────────────────────────────┐
│ ┌ CalendarToolbar ──────────────────────────────────────────────────────────────────────┐ │
│ │ 통합 달력   [‹] [ 2024년 2월 ▾ ] [›]  [오늘]                      [⟳] [⚲ 필터 (2)]     │ │ 1행
│ │ 표시할 자료: [■가계부✓] [■장소·기록✓] [■여행✓] [■경로✓] [■개인 일정✓] [◇공유 자료✓]    │ │ 2행
│ └───────────────────────────────────────────────────────────────────────────────────────┘ │
│ ┌ MonthSummaryStrip (aria-label="이번 달 요약") ─────────────────────────────────────────┐ │
│ │ 수입 ₩0 │ 지출 ₩50,000 │ 수지 −₩50,000 │ 장소 기록 3건 │ 여행 1개 │ 공유 여행 1개·합계 제외 │ [👁] [요약 더보기 ▾] ⓘ │
│ └───────────────────────────────────────────────────────────────────────────────────────┘ │
│ ┌ CalendarIssuesBanner (오류 있을 때만, role=alert) ─────────────────────────────────────┐ │
│ │ ⚠ 일부 자료를 불러오지 못했습니다. · 장소·기록 월 정보를 불러오지 못했습니다.  [재시도] │ │
│ └───────────────────────────────────────────────────────────────────────────────────────┘ │
│ ┌ MonthGrid (flex 1) ──────────────────────────────────┐ ┌ DayPanel (aside, 22–26rem) ────┐ │
│ │ 일(빨강) 월  화  수  목  금  토(파랑)                  │ │ [‹] 2월 15일 목요일 [오늘] [›] │ │
│ │ ┌──┬──┬──┬──┬──┬──┬──┐  ← 주(week) 행               │ │ 수입 ₩0 · 지출 ₩50,000         │ │
│ │ │11│12│13│14│15│16│17│  날짜 숫자 행                  │ │ [+ 일정 추가]                  │ │
│ │ │  │  │  │▌여행────────│  바 레인 0 (연속 바)           │ │ ─ 종일·기간 ─                  │ │
│ │ │  │  │  │  │▌친구 여행─│  바 레인 1 (공유: 점선)        │ │ 기간 ▌✈ 여행  2/14~2/16 · 계획 │ │
│ │ │  │  │  │  │−50,000   │  금액(지출색)                  │ │ ─ 시간순 ─                     │ │
│ │ │  │  │  │  │■3 ■1  +1 │  출처 개수 칩, +N               │ │ 00:00ⓘ ▌₩ 분할 거래  −₩50,000 ⋯│ │
│ │ └──┴──┴──┴──┴──┴──┴──┘                                │ │   [거래 1건 · 결제수단 2개 상세]│ │
│ │ ... 6주                                              │ │ ─ 시간 미지정 ─                 │ │
│ └──────────────────────────────────────────────────────┘ │ ▌📍 서울 장소 · 별도 방문     ⋯ │ │
│                                                          └────────────────────────────────┘ │
└────────────────────────────────────────────────────────────────────────────────────────────┘
```

- DayPanel은 `position: sticky; top: 1rem; max-height: calc(100dvh - 2rem); overflow: auto`.
- 레이아웃: `grid-template-columns: minmax(0, 1fr) minmax(20rem, 24rem); gap: 1rem`.
- 폭 60rem 이하에서는 DayPanel을 렌더링하지 않고 3.3절의 peek 바와 바텀시트를 쓴다.

### 3.3 모바일 와이어프레임 (390x844, 폭 60rem 이하는 "compact")

```
기본 화면                                바텀시트 펼침(모달)                  설정 drawer(모달, 전폭)
┌──────────────────────────────┐   ┌──────────────────────────────┐   ┌──────────────────────────────┐
│통합 달력        [⟳] [⚲ 필터2] │   │ (어두운 배경, 뒤 화면 inert)  │   │ 필터와 공유 설정        [닫기] │
│[‹] 2024년 2월 ▾ [›]   [오늘] │   │                              │   │ ─ 가계부 (가계부 켜짐일 때) ─ │
│[■가계부][■장소·기록][■여행]→ │←가로│┌────────────────────────────┐│   │ 거래 구분 [전체 ▾]           │
│ 수입 ₩0 · 지출 ₩5만 · 수지 … │ 스크롤│ ▔▔ (손잡이)          [닫기] ││   │ 분류 [전체 ▾] 결제수단 [전체▾]│
├──────────────────────────────┤   ││[‹] 2월 15일 목요일 오늘 [›]  ││   │ ─ 장소·여행 ─                 │
│일 월 화 수 목 금 토           │   ││지출 ₩50,000   [+ 일정 추가]  ││   │ 장소 분류 [▾] 여행 [▾]        │
│11 12 13 14 15 16 17          │   ││─ 종일·기간 ─                 ││   │ ─ 공유받은 여행 선택 ─         │
│         ▀▀▀▀▀▀▀▀▀▀ (6px 바)  │   ││기간 ▌여행 2/14~2/16        ›││   │ ☐ 친구 여행 · 친구 (공유 전시) │
│            ▀▀▀▀▀▀▀ (공유 빗금)│   ││─ 시간순 ─                    ││   │ ─ 공유받은 기록 지도 선택 ─    │
│            −5만 (11px)        │   ││00:00ⓘ ▌분할 거래 −₩50,000 ⋯ ││   │ [주소 입력      ] [공유 지도 추가]│
│            ■■ +1              │   ││...                           ││   │ ▸ 날짜 미지정 자료            │
│ ... 6주 (셀 높이 4.75rem)      │   │└────────────────────────────┘│   │ [필터 초기화]         [닫기]  │
├──────────────────────────────┤   └──────────────────────────────┘   └──────────────────────────────┘
│[2월 15일 목 · 항목 4개 · 지출 ₩5만 · 상세 보기 ▴]│ ← peek 바(sticky bottom, 버튼 1개)
└──────────────────────────────┘
```

- compact에서 **날짜 칸을 탭하면** 그 날짜를 선택하고 바텀시트를 연다. 시트 안의 `‹`/`›` 버튼으로 시트를 닫지 않고 이전/다음 날로 이동할 수 있다.
- peek 바(`button.integrated-calendar__peek`)는 선택한 날짜 요약을 한 줄로 보여주고, 누르면 시트를 연다. **peek 바에는 heading과 '일정 추가' 버튼을 넣지 않는다.** 시트가 열렸을 때 이름이 같은 요소가 두 개 생기지 않게 하기 위해서다.
- 시트: `max-height: 85dvh`, 내부 스크롤, 상단 손잡이(장식) + '닫기' 버튼. Esc, 배경 탭, 닫기 버튼으로 닫는다. 닫으면 시트를 연 요소(셀 또는 peek)로 포커스가 돌아간다.
- 폭이 60rem보다 넓어지면 열린 시트를 닫는다(`watch(compact)`).

### 3.4 컴포넌트 트리와 파일

```
IntegratedCalendarWorkspace.vue      ← 상태 소유자, emit 담당, 레이아웃(그리드 + 패널/시트)
├─ integrated-calendar/CalendarToolbar.vue        월 이동, 오늘, 새로고침, 필터 버튼, 레이어 칩
│   └─ integrated-calendar/MonthPicker.vue        연·월 선택 팝오버(비모달)
├─ integrated-calendar/MonthSummaryStrip.vue      KPI, 금액 숨기기, 요약 더보기(details), ⓘ
├─ integrated-calendar/CalendarIssuesBanner.vue   통합 오류 배너(role=alert, 재시도 1개)
├─ integrated-calendar/MonthGrid.vue              요일 헤더, 주 행, 셀, 연속 바 레이어
├─ integrated-calendar/DayPanel.vue               날짜 헤더, 일 합계, 통합 타임라인
│   ├─ integrated-calendar/DayTimelineRow.vue     행 1개(주 버튼, 상세 details, ⋯ 메뉴, ⓘ)
│   ├─ integrated-calendar/RowMenu.vue            ⋯ 메뉴(role=menu)
│   └─ integrated-calendar/InfoTip.vue            ⓘ 툴팁
├─ integrated-calendar/CalendarSettingsDrawer.vue 세부 필터, 공유 여행/지도 선택, 미지정 자료
├─ integrated-calendar/EventDialog.vue            일정 추가/수정 모달
├─ integrated-calendar/ConfirmDialog.vue          앱 내 삭제 확인(role=alertdialog)
├─ integrated-calendar/SharedSourceOverlay.vue    공유 여행/공유 지도 원본 전체화면 오버레이
├─ integrated-calendar/BaseDialog.vue             네이티브 <dialog> 래퍼(모든 모달 공통)
└─ integrated-calendar/CalendarIcon.vue           인라인 SVG 아이콘(name prop)

lib/
├─ integratedCalendarCore.js      (+ calendarWeekday, shiftCalendarDate, monthTargetDate, splitCalendarWeeks)
├─ integratedCalendarView.js      (신규) 레이어 가시성, 일별 요약 Map, 월 KPI, 스팬 수집, 타임라인, 포맷터
├─ integratedCalendarBars.js      (신규) 주 단위 연속 바 레이아웃
├─ useIntegratedCalendarSources.js (신규) 데이터 로딩 composable
└─ useMediaQuery.js               (신규) matchMedia 반응형 ref
```

클래스 접두사는 기존과 같은 `integrated-calendar__`를 쓴다(e2e 셀렉터 호환, 전역 클래스 충돌 회피). `.chip`, `.alert`, `.banner`, `.popover`, `.tooltip`, `.is-active`, `.is-selected`, `.modal`, `.dialog` 같은 **접두사 없는 이름은 쓰지 않는다**.

### 3.5 디자인 토큰과 레이어 색 매핑

`IntegratedCalendarWorkspace.vue`의 scoped 스타일 맨 위에 다음 변수를 정의한다. CSS 사용자 정의 속성은 DOM으로 상속되므로 하위 컴포넌트와 `<dialog>`(top layer에 떠도 DOM 위치는 그대로) 안에서도 쓸 수 있다. `:root[data-theme='toss'] .integrated-calendar`는 scoped 컴파일 후에도 정상 동작한다(마지막 선택자에만 data-v가 붙음). **`style.css`는 수정하지 않는다.**

| 토큰 | 용도 | 라이트 (`.integrated-calendar`) | 다크 (`:root[data-theme='toss'] .integrated-calendar`) |
|---|---|---|---|
| `--ic-surface` | 이번 달 셀, 행 배경 | `var(--surface-elevated, #faf8f2)` | `var(--dark-card, #20292c)` |
| `--ic-surface-muted` | 이웃 달 셀 | `var(--surface-soft, #ebe8dd)` | `var(--dark-surface, #1d2427)` |
| `--ic-panel` | 패널·툴바·KPI 띠 | `var(--panel-bg, #f4f1e8)` | `var(--dark-surface-raised, #1b2225)` |
| `--ic-line` | 테두리 | `var(--line, #a3b18a)` | `var(--line, #4a595d)` |
| `--ic-text` / `--ic-text-soft` | 본문 / 보조 글자 | `var(--text)` / `var(--text-soft)` | `var(--text)` / `var(--text-soft)` |
| `--ic-accent` | 선택 칸 테두리, 선택 날짜 배지 배경 | `var(--brand-strong, #3a5a40)` | `#9bd3a3` |
| `--ic-accent-contrast` | 선택 배지 글자 | `#ffffff` (7.7:1) | `#0f1517` (10.7:1) |
| `--ic-accent-soft` | 선택 칸 배경 | `rgba(88, 129, 87, 0.16)` | `rgba(155, 211, 163, 0.16)` |
| `--ic-today` | 오늘 테두리·'오늘' 배지 | `var(--brand-strong, #3a5a40)` | `#9bd3a3` |
| `--ic-income` / `-soft` | 수입 글자 / 배경 | `#2e6b3f` / `#dde4d9` | `var(--dark-income, #86c79b)` / `#364c44` |
| `--ic-expense` / `-soft` | 지출 글자 / 배경 | `#a3402c` / `#eeded6` | `var(--dark-expense, #e88776)` / `#4c3e3c` |
| `--ic-sunday` | 일요일 숫자·헤더 | `#b03a3a` | `#f09a9a` |
| `--ic-saturday` | 토요일 숫자·헤더 | `#2f5d8a` | `#93bde8` |
| `--ic-warning` / `-soft` | 오류 배너 아이콘·테두리 / 배경 | `#8a4b0f` / `#eae0d2` | `#e0b25a` / `#4a4736` |
| `--ic-backdrop` | 다이얼로그 배경 | `var(--modal-overlay, rgba(52,78,65,.52))` | `var(--modal-overlay, rgba(4,7,8,.78))` |

레이어 색 (칩 사각형, 바, 점, 타임라인 왼쪽 줄무늬, 아이콘):

| 레이어(`data-layer`) | 라벨 | 아이콘 | 라이트 fg / soft | 다크 fg / soft |
|---|---|---|---|---|
| `ledger` | 가계부 | 카드 | `#3d6b47` / `#e0e4da` | `#8fcf9f` / `#384e45` |
| `places` | 장소·기록 | 핀 | `#9a4a2a` / `#ede0d6` | `#e6a383` / `#4c443f` |
| `travel` | 여행 | 여행가방 | `#2c6275` / `#dde3e1` | `#86c3d6` / `#364b51` |
| `routes` | 경로 | 경로선 | `#7a5a10` / `#e8e2d2` | `#d9bb64` / `#494938` |
| `events` | 개인 일정 | 달력 | `#6b4a8e` / `#e6e0e4` | `#c4a8e8` / `#444555` |
| 공유(수식) | 공유 자료 | 사람 2명 | 출처 색 + `1px dashed` 테두리, 모바일 바는 빗금 `repeating-linear-gradient(135deg, fg 0 3px, transparent 3px 6px)` | 동일 |

변수 이름은 `--ic-layer-ledger`, `--ic-layer-ledger-soft` 형식으로 정의하고, 요소에서는 `[data-layer='ledger'] { --ic-layer: var(--ic-layer-ledger); --ic-layer-soft: var(--ic-layer-ledger-soft); }`처럼 지역 변수 `--ic-layer`/`--ic-layer-soft`에 연결해서 쓴다.

대비 검증값(WCAG 상대 휘도로 계산): 라이트 레이어 fg는 `#faf8f2` 위에서 5.8~6.6:1, `#ebe8dd` 위에서 5.0~5.7:1, soft 배경 위 본문색(#344e41)은 약 7.0:1이다. 다크 레이어 fg는 `#20292c` 위에서 7.0~8.2:1, soft 배경 위 본문색(#f1f4f3)은 8.1~8.6:1이다. 수입/지출은 라이트 6.0/5.9:1, 다크 7.5/5.8:1이다.
**주의:** 다크 지출색을 지출 soft 배경 위에 글자로 올리면 3.96:1로 기준 미달이다. 금액 글자는 항상 `--ic-surface`/`--ic-panel` 위에 둔다. 바 위의 글자는 레이어 fg가 아니라 `--ic-text`로 쓴다.

### 3.6 셀(MonthGrid) 규격

공통:
- 주 행 `div.integrated-calendar__week`는 `position: relative; display: grid; grid-template-columns: repeat(7, minmax(0, 1fr)); gap: .25rem;`. 셀 버튼 7개가 1행에 들어간다.
- 바 레이어 `div.integrated-calendar__bars`(`aria-hidden="true"`, `pointer-events: none`)는 주 행 위에 절대 위치로 겹친다: `position: absolute; inset: var(--ic-date-row-h) 0 auto 0; display: grid; grid-template-columns: repeat(7, minmax(0,1fr)); column-gap: .25rem; grid-auto-rows: var(--ic-bar-h); row-gap: var(--ic-bar-gap);`. 바는 `grid-column: (startCol + 1) / span (span)`, `grid-row: lane + 1`로 놓는다. **열 간격이 셀과 같아야 바가 정확히 정렬된다.**
- 셀 내부 순서: 날짜 행(숫자 + '오늘' 배지) → 레인 스페이서(`height: calc(var(--ic-lane-count) * (var(--ic-bar-h) + var(--ic-bar-gap)))`, `--ic-lane-count`는 주 행에 인라인 style로 지정) → 금액 줄 → 출처 개수 칩 줄 → `+N`.
- 셀 `button.integrated-calendar__cell`의 속성: `type="button"`, `data-date="YYYY-MM-DD"`, `:aria-pressed="date === selectedDate"`, 오늘이면 `aria-current="date"`, `aria-label`(아래). **`aria-selected`는 쓰지 않는다**(전역 색 규칙 충돌).
- `aria-label` 형식: `${formatDayHeading(date)}${오늘 ? ', 오늘' : ''}, ${n}개 항목${n ? ': ' + texts.join(', ') : ''}`. 예: `2월 15일 목요일, 오늘, 4개 항목: 수입 ₩0 · 지출 ₩50,000, 장소 기록 3건, 여행 기간 · 여행, 공유 여행 시작 · 친구 여행`. `texts`의 문구는 현재 `filteredForDay`의 `text`와 같아야 한다(4.4절). e2e가 `일정 시작 · 개인 일정` 같은 문구를 확인한다.
- 주말: 요일 헤더 '일'은 `--ic-sunday`, '토'는 `--ic-saturday`. 이번 달 날짜 숫자도 같은 색. 이웃 달 숫자는 `--ic-text-soft`.
- 이웃 달 칸: 배경 `--ic-surface-muted`, 글자 `--ic-text-soft`. **opacity를 쓰지 않는다.**
- 오늘(선택 안 됨): 날짜 숫자를 `2px solid var(--ic-today)` 사각 테두리로 감싸고, 데스크톱에서는 숫자 옆에 '오늘' 배지(0.6875rem 이상, `--ic-today` 배경, 대비색 글자)를 붙인다.
- 선택: 셀 배경 `--ic-accent-soft`, 테두리 `2px solid var(--ic-accent)`, 날짜 숫자는 `--ic-accent` 채운 사각 배지 + `--ic-accent-contrast` 글자(CalendarWorkspace의 선택 배지 패턴). 오늘이면서 선택이면 채운 배지 + '오늘' 배지.
- 금액 줄 `span.integrated-calendar__cell-money`: 지출은 `−` 접두(U+2212)와 `--ic-expense`, 수입은 `+` 접두와 `--ic-income`. 금액 숨김이면 `•••`.
- hover(포인터 기기만, `@media (hover: hover)`): 테두리 `--ic-accent`.

데스크톱(폭 35rem 초과):
- `--ic-date-row-h: 1.75rem; --ic-bar-h: 1.125rem; --ic-bar-gap: .125rem;` 최대 레인 3.
- 셀 최소 높이 `6.5rem`. 주 행 높이는 내용에 맞춘다(레인이 많은 주는 더 높아도 된다).
- 바: 글자 `.75rem/600`, `padding-inline: .375rem`, 배경 `--ic-layer-soft`, 글자 `--ic-text`, 한 줄 말줄임. 실제 시작인 조각은 왼쪽 `3px solid var(--ic-layer)` 테두리, 앞주에서 이어지는 조각(`continuesBefore`)은 왼쪽 테두리 없이 시작, 다음 주로 이어지는 조각(`continuesAfter`)은 `::after`로 `›` 표시. 공유는 `outline: 1px dashed var(--ic-layer); outline-offset: -1px` + 제목 앞 공유 아이콘.
- 금액: 전체 숫자(예: `−50,000`, `+12,000`). 절댓값 100만 이상이면 compact(`−120만`). 수입과 지출이 모두 있으면 두 줄.
- 개수 칩: `■ 3` 형태(레이어 색 사각형 0.5rem + 숫자 .75rem). 최대 3개, 나머지는 `+N`에 더한다.
- `+N` (`span.integrated-calendar__more`): 숨겨진 바 수 + 넘친 칩 수. `.75rem/700`.

모바일(폭 35rem 이하):
- `--ic-date-row-h: 1.25rem; --ic-bar-h: .375rem; --ic-bar-gap: .125rem;` 최대 레인 2. 셀 높이 `4.75rem`, `padding: .125rem`.
- 날짜 숫자 `.8125rem`. '오늘' 텍스트 배지는 숨기고 숫자 테두리만 남긴다.
- 바: 글자 없이 6px 높이 단색(`--ic-layer`), 공유는 빗금.
- 금액: compact(`−5만`, `+3만`), **글자 크기 최소 `.6875rem`(11px)**, 최대 2줄.
- 개수: 글자 대신 점(`.375rem` 사각형, 전역 규칙 때문에 사각형이 됨) 최대 4개 + `+N`(11px).
- 모바일 범례 문단(현재 425행)은 삭제한다. 레이어 칩이 범례 역할을 한다.
- 셀 버튼 자체는 높이 4.75rem, 폭 약 49px(390 기준)이다. 터치 대상 기준(2.75rem)을 높이로 충족한다.

`+N`/셀 클릭 동작:
- 데스크톱: 클릭하면 날짜를 선택한다. 클릭 지점이 `.integrated-calendar__more` 안이면(`event.target.closest('.integrated-calendar__more')`) 선택 후 DayPanel 제목(`h2[tabindex="-1"]`)으로 포커스를 옮긴다("패널 포커스" 방식, 팝오버는 P2).
- compact: 어느 지점을 탭해도 선택 + 바텀시트 열기.
- 다른 달 칸을 누르면 그 달로 이동하고 해당 날짜를 선택한다(현재 `chooseDate`, 298–301행과 동일).

### 3.7 상세 패널(DayPanel)과 통합 타임라인 규격

헤더:
- `[‹ 이전 날] h2#integrated-calendar-day-title(tabindex="-1") [오늘 배지] [› 다음 날]`. 제목 텍스트는 `formatDayHeading(date, today)` 결과(예: `2월 15일 목요일`, 연도가 오늘과 다르면 `2023년 12월 31일 일요일`). '오늘' 배지는 h2 밖, 옆에 둔다.
- 일 합계: 가계부 레이어가 켜져 있고 상세 가계부가 로드됐을 때만 `수입 ₩x · 지출 ₩y`(0인 항목은 생략, 둘 다 0이면 줄 생략). `dailyLedger.income/expense`(공유 제외)를 쓴다.
- `[+ 일정 추가]` 버튼(이름 '일정 추가'). compact 시트에서는 닫기 버튼도 있다(이름 '닫기').

본문 = 하나의 `<ol class="integrated-calendar__timeline" aria-label="이 날의 항목">`, 그룹 소제목은 `<li role="presentation" class="integrated-calendar__timeline-group">`로 넣는다:
1. **종일·기간**: 여행 기간, 공유 여행 기간, 종일 일정, 시간 있는 다일 일정의 중간 날
2. **시간순**: 시간이 있는 항목, 시간 오름차순
3. **시간 미지정**: 시간이 없는 항목(경로 포함)

빈 그룹은 소제목도 숨긴다. 전부 비면 "이 날짜에 표시할 항목이 없습니다." + '일정 추가' 버튼. 레이어가 하나도 켜져 있지 않으면 "표시할 자료를 하나 이상 켜 주세요."

로딩: 해당 날짜의 첫 로드(4.2절 키 변경)에서는 회색 스켈레톤 행 3개를 `aria-hidden`으로 보여주고 `ol`에 `aria-busy="true"`를 단다. 일부 출처만 끝났으면 끝난 행부터 보이고 하단에 "나머지 자료를 불러오는 중…"을 `role="status"`로 둔다. 같은 날짜 재조회 중에는 아무것도 비우지 않는다.

행 `li.integrated-calendar__entry` (클래스 이름 유지):
- 속성: `data-layer`, `data-shared="true|false"`, `data-key`.
- 구조:
  ```
  li.integrated-calendar__entry
  ├─ button.integrated-calendar__entry-main  (aria-label = `${primary.label}: ${title}`)
  │   ├─ span.integrated-calendar__entry-time    '09:30' | '종일' | '기간' | '종료 08:30' | '시간 미지정'
  │   ├─ span.integrated-calendar__entry-stripe  (레이어 색 3px 세로줄 + 아이콘, aria-hidden)
  │   ├─ span.integrated-calendar__entry-body
  │   │   ├─ span.integrated-calendar__entry-title   제목
  │   │   ├─ span.integrated-calendar__entry-sub     보조 정보
  │   │   └─ span.integrated-calendar__entry-badges  배지들('공유', '합계 제외', '결제 취소됨' …)
  │   └─ span.integrated-calendar__entry-amount  금액(±, 색)
  ├─ InfoTip (timeNote가 있을 때만, main 버튼 밖, 시간 옆에 배치)
  ├─ RowMenu (secondary 동작이 있을 때만, 이름 `추가 작업: ${title}`)
  └─ details.integrated-calendar__entry-more (가계부 행만: 분할/거래 상세)
  ```
  버튼 안에 다른 버튼이나 details를 넣지 않는다(중첩 인터랙티브 금지). InfoTip·RowMenu·details는 main 버튼의 형제다.
- 행 클릭 = 원본 열기(`primary`). 보조 동작은 ⋯ 메뉴(`secondary`).
- 메모는 2줄 말줄임(`-webkit-line-clamp: 2`)이지만 텍스트는 DOM에 그대로 둔다(e2e `toContainText` 대상).
- 썸네일은 `width="40" height="40"`, `loading="lazy"`, `alt`는 제목 포함(예: `서울 장소 사진`). 공유 지도 사진은 기존처럼 새 탭 링크(`<a target="_blank" rel="noopener noreferrer">`)를 유지하되 main 버튼 밖에 둔다.

출처별 행 내용과 동작 (`buildDayTimeline`이 만든다, 4.6절):

| kind | 그룹/시간 | 제목 | 보조/배지 | primary (라벨 → 동작) | secondary |
|---|---|---|---|---|---|
| `ledger` | `ledgerTime(row)` 있으면 시간순, 없으면 미지정 | `row.parts[0].title` | `수입`/`지출` · 결제수단(분할이면 `카드 외 1`), 배지: 합계 제외 문구(451행 그대로), `ledgerStatus` + `· 원거래 N`, `여행 지출과 연결된 가계부 거래`. 금액 `currency(row.displayedAmount)`에 수입 `+`, 지출 `−` | `가계부 원본 보기` → `open-ledger-source` | `parts[0].travelPlanId && travelRecordId`면 `연결된 여행 지출 보기` |
| `place` | 시간 있으면 시간순 | `${placeName} · ${title}` | 분류(없으면 '분류 없음'), 배지 `여행에서 가져온 기록`(+ planName), 연결 기록이 LEDGER면 `가계부 연결`/`여행 지출 · 가계부 미반영` + 금액, `사진 N장`(1 이상일 때) | `장소 원본 보기` → `open-place-source` | 연결 기록 있으면 `여행 기록 원본 보기` |
| `travel-period` | 종일·기간, `기간` | `period.title` | `2월 14일 ~ 2월 16일 · 계획/완료` | `여행 원본 보기` → plan | 없음 |
| `travel-record` | 시간 있으면 시간순 | `record.title` | planName · 유형 문구(461행), 장소명, 외화 | `여행 기록 원본 보기` | 없음 |
| `route` | 미지정 | `route.title` | planName · transportMode · km · 분 | `경로 원본 보기` | 없음 |
| `event` | 종일/중간 날 → 종일·기간(`종일` 또는 `계속`), 시작 날 → `startTime`, 마지막 날 → `종료 ${endTime}` | `event.title` | `2월 14일 ~ 2월 15일` · 시간 범위, 메모 | `일정 수정` → EventDialog | `수정`, `삭제` |
| `shared-period` | 종일·기간 | `period.title` | `공유 · ${share.title}`, 배지 `공유` | `공유 여행 원본 보기` → 오버레이 | 없음 |
| `shared-record` | 시간 있으면 시간순 | `record.title` | `공유 · ${share.title}` · `여행 지출`/`기록`, 금액(중립색), 배지 `공유`, `합계 제외`(LEDGER일 때) | `공유 여행 원본 보기` | 없음 |
| `shared-route` | 미지정 | `route.title` | transportMode · km, 배지 `공유` | `공유 여행 원본 보기` | 없음 |
| `shared-map` | 시간 있으면 시간순 | `${placeName} · ${title}` | `공유 · ${share.title}`, 메모, 사진 링크, 배지 `공유` | `공유 장소 원본 보기` → 지도 오버레이 | 없음 |

ⓘ 툴팁(`timeNote`):
- 가계부 행이고 시간이 `00:00`으로 시작하면: `가계부의 00:00은 저장된 값이며 미입력 여부를 구분할 수 없습니다.`
- 분할 행이면 덧붙임: `여러 결제수단 중 가장 이른 저장 시간입니다.`
- 월 KPI 띠: `현재 표시 자료와 필터 기준입니다. 이웃 달 날짜와 공유 자료의 금액은 합계에 포함하지 않습니다.`

### 3.8 오버레이 규격

모든 모달은 `BaseDialog.vue`(네이티브 `<dialog>` + `showModal()`)를 쓴다. `showModal()`은 바깥 콘텐츠를 inert로 만들어 포커스를 가두고, Esc를 `cancel` 이벤트로 보내며, top layer에 그려서 z-index 경쟁이 없다. 기존 `RecordMapDialog.vue`가 같은 패턴이다.

```vue
<!-- integrated-calendar/BaseDialog.vue 골격 -->
<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
const props = defineProps({
  labelledby: { type: String, default: null },
  label: { type: String, default: null },
  variant: { type: String, default: 'center' }, // center | drawer | sheet | fullscreen
  role: { type: String, default: 'dialog' },   // dialog | alertdialog
  busy: { type: Boolean, default: false },
  closeOnBackdrop: { type: Boolean, default: true },
  initialFocus: { type: String, default: null }, // CSS 선택자
})
const emit = defineEmits(['close'])
const el = ref(null)
let trigger = null
const FOCUSABLE = 'button:not(:disabled), [href], input:not(:disabled), select:not(:disabled), textarea:not(:disabled), summary, [tabindex]:not([tabindex="-1"])'
onMounted(async () => {
  trigger = document.activeElement
  el.value.showModal()
  await nextTick()
  const target = (props.initialFocus && el.value.querySelector(props.initialFocus)) || el.value.querySelector('[autofocus]') || el.value.querySelector(FOCUSABLE)
  target?.focus()
})
onBeforeUnmount(() => {
  if (el.value?.open) el.value.close()
  if (trigger?.isConnected) trigger.focus({ preventScroll: true })
})
function onCancel(event) { event.preventDefault(); if (!props.busy) emit('close') }
function onClick(event) {
  if (!props.closeOnBackdrop || props.busy || event.target !== el.value) return
  const r = el.value.getBoundingClientRect()
  if (event.clientX < r.left || event.clientX > r.right || event.clientY < r.top || event.clientY > r.bottom) emit('close')
}
</script>
<template>
  <dialog ref="el" :class="['integrated-calendar__dialog', `integrated-calendar__dialog--${variant}`]" :role="role" aria-modal="true"
    :aria-labelledby="labelledby" :aria-label="label" @cancel="onCancel" @click="onClick">
    <slot />
  </dialog>
</template>
```

- 부모는 `v-if`로 마운트하고, 닫을 때는 플래그를 false로 바꿔 언마운트한다(F15 스크롤 잠금 규칙). 열려 있는 동안만 `role="dialog" aria-modal="true"`가 DOM에 있으므로 App의 모바일 스크롤 잠금이 자동으로 걸리고 풀린다.
- 포커스 복귀: 언마운트 시 연 요소로 돌아간다. 연 요소가 사라졌으면(예: 삭제된 행) 부모가 `nextTick` 뒤 DayPanel 제목(`#integrated-calendar-day-title`)에 포커스를 준다.
- 내용은 dialog 전체를 채운다(`dialog { padding: 0 }`, 내부 래퍼에 padding). 그래야 배경 클릭 판정이 정확하다.
- `::backdrop { background: var(--ic-backdrop); }`, 애니메이션은 `@media (prefers-reduced-motion: no-preference)`에서만.
- variant CSS:
  - `center`: `width: min(32rem, calc(100vw - 2rem)); max-height: min(90dvh, 48rem);`
  - `drawer`: `margin: 0 0 0 auto; height: 100dvh; max-height: 100dvh; width: min(26rem, 100vw); max-width: 100vw;`
  - `sheet`: `margin: auto 0 0 0; width: 100vw; max-width: 100vw; max-height: 85dvh;`
  - `fullscreen`: `margin: 0; width: 100vw; height: 100dvh; max-width: 100vw; max-height: 100dvh;`

| 오버레이 | 이름(접근성) | 내용·동작 |
|---|---|---|
| CalendarSettingsDrawer | dialog `aria-labelledby` → h2 `필터와 공유 설정` | 4.3절 필터 그룹(`<div role="group" aria-label="달력 필터">`로 감쌈), '공유받은 여행 선택' 섹션(h3 + 설명 389행 문구 + 체크박스 목록), '공유받은 기록 지도 선택' 섹션(h3 + 설명 394행 문구 + 주소 폼 + 체크박스 목록), `<details>` '날짜 미지정 자료'(열 때 1회 로드, 이후 페이지 이동·새로고침 때만 재조회), 하단 '필터 초기화', '닫기'. 드로어에서 일어난 동작 오류(선택 저장 실패, 지도 추가 실패)는 해당 섹션 안에 `role="alert"` 문단으로 표시 |
| EventDialog | h2 `개인 일정 추가` / `일정 수정` | `form.integrated-calendar__form` 안에 라벨 `제목`, `시작 날짜`, `종료 날짜 (선택)`, `종일`(체크박스), `시작 시간`, `종료 시간`(종일 아닐 때), `메모`. 하단 `저장`(type=submit, `.button.button--primary`), `취소`(`.button.button--ghost`), 수정 모드에서만 왼쪽에 `삭제`(`.button.button--danger`). 오류는 폼 상단 `role="alert"`. 초기 포커스는 제목 입력 |
| ConfirmDialog | role `alertdialog`, h2 `일정을 삭제할까요?` | 본문 `‘{제목}’ 일정을 삭제합니다. 다른 원본 기록은 삭제되지 않습니다.` 버튼 `취소`(초기 포커스), `삭제`(danger). busy 동안 Esc/배경 닫기 비활성 |
| 바텀시트(DayPanel, compact) | dialog `aria-labelledby="integrated-calendar-day-title"` | 3.3절. class `integrated-calendar__sheet` |
| SharedSourceOverlay | dialog `aria-label="공유 여행 원본"` / `"공유 지도 원본"` | 상단 바: 제목 + 닫기 버튼(이름 `공유 여행 원본 닫기` / `공유 지도 원본 닫기`). 본문: `TravelSharedExhibitWorkspace embedded :selected-exhibit` / `RecordMapWorkspace public-view :calendar-grant-id :place-focus-request`(기존 471–472행 props 그대로, `defineAsyncComponent` 유지). 지도는 dialog가 열린 뒤(`nextTick`) 렌더링해서 Leaflet이 크기를 올바르게 잡게 한다 |
| MonthPicker(비모달 팝오버) | trigger `aria-haspopup="dialog" aria-expanded`, 패널 `role="dialog" aria-label="연월 선택"`(aria-modal 없음) | 연도 줄 `[‹ 이전 해] 2024년 [다음 해 ›]`, 월 버튼 12개(3×4, 현재 월 `aria-pressed="true"`). 열면 현재 월 버튼에 포커스, Esc/바깥 클릭/선택 시 닫고 trigger로 포커스 복귀. 연도 범위 1900~9998(현재 규칙 유지) |
| RowMenu(비모달 메뉴) | trigger 이름 `추가 작업: {제목}`, `aria-haspopup="menu" aria-expanded`; 패널 `role="menu"`, 항목 `role="menuitem"` 버튼 | 열면 첫 항목 포커스, ↑/↓ 순환, Home/End, Esc(→ `preventDefault()` + `stopPropagation()`으로 바깥 dialog의 cancel을 막음)·Tab·바깥 클릭으로 닫힘, 닫으면 trigger로 포커스. 행의 오른쪽 아래로 절대 위치 |
| InfoTip | 버튼 `aria-label="도움말: {문구}"` | 시각 아이콘 1rem, 버튼 hit area 2.75rem(음수 margin으로 레이아웃 영향 최소화). hover/focus-visible 시 `role="tooltip"` 말풍선 표시, 터치는 클릭 토글, Esc로 닫힘 |

### 3.9 접근성 이름·셀렉터 계약 (e2e가 의존)

| 요소 | 이름/셀렉터 |
|---|---|
| 루트 | `section.integrated-calendar[aria-label="통합 달력"]` |
| 이전/다음 달 | 버튼 `aria-label="이전 달"` / `"다음 달"` |
| 연월 선택 | `button.integrated-calendar__month-trigger`(텍스트 `2024년 2월`) |
| 오늘 | 버튼 텍스트 `오늘` (오늘 칸 aria-label에도 '오늘'이 있으므로 테스트는 `exact: true`) |
| 새로고침 | 아이콘 버튼 `aria-label="다시 불러오기"` (기존 이름 유지) |
| 필터 drawer 열기 | 버튼 `aria-label="필터와 공유 설정"`, `aria-haspopup="dialog"`, 활성 필터 수 배지 |
| 레이어 칩 | `div[role="group"][aria-label="표시할 자료"]` 안의 `button.integrated-calendar__layer-chip[data-layer][aria-pressed]`, 이름 `가계부`/`장소·기록`/`여행`/`경로`/`개인 일정`/`공유 자료` |
| 월 요약 | `.integrated-calendar__summary[aria-label="이번 달 요약"]` |
| 금액 숨기기 | 요약 띠의 아이콘 버튼 `aria-label="금액 숨기기"`, `aria-pressed` |
| 오류 배너 | `.integrated-calendar__issues[role="alert"]`, 안에 버튼 `재시도` 1개 |
| 달력 | `.integrated-calendar__month[aria-busy]`, 주 `.integrated-calendar__week`, 셀 `button.integrated-calendar__cell[data-date]`, 바 `.integrated-calendar__bar[data-key][data-layer][data-shared]`, 금액 `.integrated-calendar__cell-money`, `+N` `.integrated-calendar__more` |
| 상세 | 데스크톱 `aside.integrated-calendar__details`, compact `dialog.integrated-calendar__sheet`, 제목 `h2#integrated-calendar-day-title`, peek `button.integrated-calendar__peek`(이름에 `상세 보기` 포함) |
| 행 | `li.integrated-calendar__entry[data-layer][data-shared]`, main 버튼 `.integrated-calendar__entry-main`, 제목 `.integrated-calendar__entry-title`, 메뉴 버튼 이름 `추가 작업: …`, 메뉴 항목 `role="menuitem"` |
| 이전/다음 날 | DayPanel 헤더 아이콘 버튼 `aria-label="이전 날"` / `"다음 날"` |
| 시트 닫기 | 시트 헤더 버튼 `닫기` |
| 일정 추가 | 버튼 `일정 추가` (DayPanel 안에만 존재) |
| 일정 폼 | `form.integrated-calendar__form` (EventDialog 안) |
| drawer | dialog 이름 `필터와 공유 설정`, class `integrated-calendar__drawer` |

### 3.10 전역 CSS 대응 규칙 (구현 시 필수)

1. 버튼 요소의 배경·테두리색은 전역 `!important`에 덮인다. 버튼 시각 상태는 **내부 span(또는 `::before`)에 배경을 칠하는 방식**을 기본으로 한다. 예: 레이어 칩은 `button.integrated-calendar__layer-chip > span.integrated-calendar__chip-face`에 배경과 테두리를 준다.
2. 꼭 버튼 자체에 칠해야 하면(날짜 셀) `.integrated-calendar .integrated-calendar__cell { background-color: var(--ic-surface) !important; border-color: … !important; }`처럼 **루트 클래스를 앞에 붙여 특이도를 높이고 `!important`**를 쓴다(scoped data-v 포함 0,3,0 > 전역 0,2,0).
3. 다이얼로그 안 버튼은 다크 테마에서 배경이 `--dark-modal-soft`로 강제된다(특이도 0,4,2). 다이얼로그 안 주요 버튼은 전역 `.button`, `.button--primary`, `.button--ghost`, `.button--danger` 클래스를 써서 앱 공통 모양을 따른다. 타임라인 행 main 버튼은 `border-width: 0`으로 두고 배경을 의존하지 않는다.
4. `border-radius`는 전역에서 0으로 강제된다. 둥근 모양을 전제로 디자인하지 않는다.
5. 각 단계에서 라이트·다크 두 테마의 **계산된 스타일**을 브라우저에서 직접 확인한다(6.5절 스크린샷 테스트).

---

## 4. 상태/데이터 설계

### 4.1 composable 인터페이스 — `frontend/src/lib/useIntegratedCalendarSources.js`

Vue와 `api.js`만 import한다(단위 테스트 대상 아님). 순수 계산은 하지 않고 로딩·경쟁 방지·오류 상태만 맡는다.

```js
/**
 * @param {object} options
 * @param {import('vue').ComputedRef<{monthStart:string, monthEnd:string, visibleStart:string, visibleEnd:string}>} options.grid
 * @param {import('vue').Ref<string>} options.selectedDate
 * @param {{entryType:string, categoryId:string, paymentMethodId:string, collectionId:string, planId:string}} options.filters  reactive
 * @param {import('vue').Ref<boolean>} options.includeShared
 * @param {(source:string) => boolean} options.isVisible  오류 배너에서 숨긴 레이어의 상세 오류를 거르는 데 사용
 */
export function useIntegratedCalendarSources({ grid, selectedDate, filters, includeShared, isVisible }) {
  return {
    // 월 요약 (키: ledger, ledger-categories, places, travel, events)
    monthSources, monthErrors, monthPending,        // reactive 객체 3개
    monthInitialLoading,                            // computed: 데이터가 한 번도 없고 요청 중인 출처가 있음
    monthRevalidating,                              // computed: 데이터가 있는데 요청 중인 출처가 있음
    // 날짜 상세 (키: ledger, places, travel-records, routes, events)
    daySources, dayErrors, dayPending, dayInitialLoading, dayRevalidating,
    // 공유 여행
    sharedOptions, sharedSummaries, sharedDays,     // ref 배열
    sharedErrors,                                   // reactive { options:'', summaries:'', day:'', fatal:'', action:'' }
    sharedLoading, sharedBusy,
    // 공유 지도
    mapOptions, mapSummaries, mapDays, mapErrors /* 같은 형태 */, mapLoading, mapBusy,
    // 필터 목록
    categories, paymentMethods, collections, plans, metadataErrors, metadataLoading,
    // 미지정
    undated, undatedError, undatedBusy, undatedPlacePage, undatedTripPage,
    // 통합 오류
    issues,            // computed Array<{ key:string, message:string }>
    retrying,          // ref<boolean>
    actionError,       // ref<string> 일정 삭제·원본 열기 실패 등
    // 동작
    loadMonth,         // ({ force=false, only=null }={}) => Promise<void>
    loadDay,           // ({ force=false, only=null }={}) => Promise<void>
    refreshAll,        // () => Promise<void>   (월·일 force + 공유 옵션 + 지도 옵션)
    refreshEvents,     // () => Promise<void>   (월/일 events만 force)
    loadSharedOptions, loadSharedSummaries, loadSharedDay,
    toggleShared,      // (item, checked:boolean) => Promise<boolean>
    loadMapOptions, loadMapSummaries, loadMapDay,
    addSharedMap,      // (link:string) => Promise<boolean>
    toggleSharedMap,   // (item, checked:boolean) => Promise<boolean>
    openSharedTravelSource, // (share) => Promise<object|null>  권한 재확인 후 상세 반환
    canOpenSharedMap,  // (shareId) => boolean
    loadFilterOptions, loadUndated, changeUndatedPage,
    retryFailed,       // () => Promise<void>
    dispose,           // () => void  onBeforeUnmount에서 호출: 모든 시퀀스 증가
  }
}
```

- 워크스페이스는 `onMounted`에서 `refreshAll()`과 `loadFilterOptions()`를, `onBeforeUnmount`에서 `dispose()`를 부른다. focus/visibilitychange 리스너와 `today` 갱신은 워크스페이스에 남긴다(현재 334–341행 로직, 1초 스로틀 유지, 호출 대상은 `refreshAll()`).
- 가계부 일 필터(`entryType`, `categoryId`, `paymentMethodId`)는 상세 요청에 쓰이지 않고 클라이언트에서 적용된다(39행). 이 동작은 그대로 둔다.

### 4.2 stale-while-revalidate 규칙

출처마다 `{ data, key, error, pending, seq }`를 둔다. `key = JSON.stringify(query)`.

```js
// composable 내부 공통 헬퍼(설명용 골격)
async function revalidate(store, source, query, fetcher, { force, resetOnKeyChange, errorMessage }) {
  const key = JSON.stringify(query)
  if (!force && store.keys[source] === key && store.data[source] != null && !store.errors[source]) return  // 같은 키, 이미 최신
  if (!force && store.pending[source] && store.pendingKeys[source] === key) return                           // 같은 키로 이미 요청 중
  const seq = ++store.seq[source]
  if (store.keys[source] !== key && resetOnKeyChange) store.data[source] = null   // 날짜 상세만: 다른 날짜의 행을 남기지 않음
  store.pending[source] = true; store.pendingKeys[source] = key
  try {
    const data = await fetcher(query)
    if (seq !== store.seq[source]) return
    store.data[source] = data; store.keys[source] = key; store.errors[source] = ''
  } catch {
    if (seq !== store.seq[source]) return
    store.data[source] = null; store.keys[source] = key; store.errors[source] = errorMessage
  } finally {
    if (seq === store.seq[source]) store.pending[source] = false
  }
}
```

| 상황 | 월 요약 | 날짜 상세 | 공유/지도 |
|---|---|---|---|
| 같은 키 재조회(focus, 새로고침, 일정 저장 후) | 기존 데이터 유지, 성공 시 교체 | 기존 데이터 유지 | 기존 목록 유지 |
| 키 변경(월 이동, 필터 변경) | 기존 데이터 유지하되 그리드에 `aria-busy="true"`와 `.is-revalidating`(바·금액 opacity .6 아님, **상단 2px 진행 막대만** 표시) | **즉시 비우고 스켈레톤**(다른 날짜의 행을 잘못 누르지 않도록) | 요약은 유지 후 교체 |
| 요청 실패 | 데이터 `null` + 오류(필터가 바뀐 경우 이전 값이 틀린 정보가 되므로 남기지 않음) | 동일 | 실패한 공유만 제거(권한 철회 처리, 1.4절) |
| 오류 문구 | 시작할 때 지우지 않는다. 성공했을 때만 지운다(배너 깜빡임 방지) | 동일 | 동일 |

- `monthInitialLoading`: 보이는 출처 중 `data == null && pending && !error`가 하나라도 있으면 true. 첫 화면에서만 셀에 스켈레톤(얇은 회색 막대)을 보여준다.
- 시퀀스는 출처별로 둔다(`monthSeq[source]`, `daySeq[source]`, `sharedOptionsSeq`, `sharedSummariesSeq`, `sharedDaySeq`, 지도 3개). `dispose()`는 모두 증가시킨다.
- 공유 원본 오버레이는 재조회로 닫지 않는다(F5 수정). 대신 워크스페이스에서 `watch(sharedSummaries)`/`watch(mapSummaries)`로 열린 원본의 공유가 목록에서 사라졌는지 확인하고, 사라졌으면 오버레이를 닫고 `actionError`에 `공유 권한이 변경되어 원본을 닫았습니다.`를 넣는다. 이를 위해 오버레이 상태를 `{ kind: 'travel', share: { type, shareId }, detail }` / `{ kind: 'map', grantId, focus }`로 저장한다.

### 4.3 재조회 트리거 (composable 내부 watch)

| 트리거 | 호출 |
|---|---|
| 마운트 | `refreshAll()`, `loadFilterOptions()` |
| `grid.visibleStart/visibleEnd/monthStart` 변경(월 이동) | `loadMonth()`, `loadSharedSummaries()`, `loadMapSummaries()` |
| `filters.entryType/categoryId/paymentMethodId` 변경 | `loadMonth()` → 키가 바뀐 `ledger`, `ledger-categories`만 실제 요청됨 |
| `filters.collectionId/planId` 변경 | `loadMonth()`(→ `places`, `travel`만 요청), `loadDay()`(→ `places`, `travel-records`, `routes`만 요청) |
| `selectedDate` 변경 | `loadDay()`, `loadSharedDay()`, `loadMapDay()` |
| `includeShared` true | 옵션을 한 번도 못 받았으면 `loadSharedOptions()`/`loadMapOptions()`, 아니면 `loadSharedSummaries()`/`loadMapSummaries()` |
| `includeShared` false | 공유 요약·상세·지도 요약·상세를 즉시 비우고 해당 시퀀스 증가(진행 중 응답 무시) |
| 공유 선택 변경 성공 | `loadSharedOptions()`(→ 요약 → 상세) / `loadMapOptions()` |
| 지도 링크 추가 성공 | `loadMapOptions()` |
| 일정 저장·삭제 성공 | `refreshEvents()`만 |
| '다시 불러오기', focus/visibilitychange | `refreshAll()` (공유 옵션도 다시 받아 권한 철회를 반영) |
| 배너 '재시도' | `retryFailed()` |

월 요약 쿼리(현재 133–137행과 동일):
- `ledger`: `{ from: visibleStart, to: visibleEnd, type: entryType, categoryId, paymentMethodId }`
- `ledger-categories`: `{ from: monthStart, to: monthEnd, type: entryType, categoryId, paymentMethodId }`
- `places`: `{ from, to, collectionId, planId }`
- `travel`: `{ from, to, planId, collectionId }`
- `events`: `{ from, to }`

날짜 상세 쿼리(155행과 동일): `events`는 `fetchCalendarEvents(date)`, 나머지는 `fetchCalendarDay(source, date, { collectionId: source === 'places' ? collectionId : '', planId: ['places','travel-records','routes'].includes(source) ? planId : '' })`.

`loadSharedOptions()`는 현재 `loadShared()`의 옵션 부분(219–232행)을, `loadSharedSummaries()`는 233–241행을, `loadSharedDay()`는 257–269행을 옮긴다. 지도도 같은 방식(166–176행 / 172–176행 / 185–197행). 오류 문구는 그대로 쓴다:
- 옵션 일부 실패: `${failedTypes.join('·')} 목록을 불러오지 못했습니다. 다른 공유 자료는 계속 표시됩니다.` (`failedTypes`는 `공유 전시`/`공유 여행 기록`)
- 요약 일부 실패: `일부 공유 자료의 권한이 변경되었거나 조회에 실패했습니다. 다시 불러와 주세요.`
- 상세 일부 실패: `일부 공유 상세 정보의 권한이 변경되었거나 조회에 실패했습니다.`
- 전체 실패: `공유 자료를 불러오지 못했습니다.`
- 지도: `일부 공유 지도 권한이 변경되었거나 조회에 실패했습니다.` / `일부 공유 지도 권한이 변경되었거나 상세 조회에 실패했습니다.` / `공유 지도를 불러오지 못했습니다.`
- 동작 오류(drawer 안 표시): `공유 자료 선택을 저장하지 못했습니다. 권한을 확인하고 다시 시도해 주세요.`, `공유 지도 선택을 저장하지 못했습니다.`, `유효한 기록 지도 공유 주소를 입력해 주세요.`, `공유 지도를 추가하지 못했습니다. 링크가 만료되었거나 취소되었는지 확인해 주세요.`
- 원본 열기 실패(배너): `공유 여행 원본을 열 수 없습니다. 공유 권한을 확인해 주세요.`

`addSharedMap(link)`의 토큰 파싱 정규식(200행)은 그대로 쓴다.

### 4.4 날짜별 요약 Map — `buildDaySummaries()` (integratedCalendarView.js)

입력:
```js
buildDaySummaries({
  days,            // grid.days: [{ date, inMonth }]
  month,           // monthSources: { ledger, places, travel, events } (null 가능)
  sharedSummaries, // [{ type, shareId, title, data: { periods, records, routes } }]
  mapSummaries,    // [{ id, title, days: [{ date, count }] }]
  isVisible,       // (source) => boolean
  includeShared,   // boolean
  formatCurrency,  // (value) => string  (금액 숨김 처리 포함)
}) // => Map<string, DaySummary>
```

출력 `DaySummary`:
```js
{
  date: '2024-02-15',
  ledger: { income: 0, expense: 50000, transactionCount: 1 } | null,   // 가계부 레이어가 보이고 데이터가 있을 때만
  counts: [ { key, layer, shared, count, text } ],   // 장소, 공유 장소, 여행 기록, 경로, 공유 기록, 공유 경로
  spans:  [ { key, layer, shared, title, role, text } ], // role: 'single' | 'start' | 'middle' | 'end'
  items:  [ { key, kind: 'money'|'count'|'span', layer, shared, text, ...원본 필드 } ], // aria 문구 순서용(현재 filteredForDay 순서와 동일)
  total: items.length,
}
```

구현 규칙:
1. 먼저 날짜로 색인한다: `ledgerByDate`, `placesByDate`, `routesByDate`는 `Map(date → 첫 번째 항목)`(현재 `find` 동작과 같게 **첫 항목 유지**), `travel.records`는 `Map(date → 배열)`(하루에 type별로 여러 개), 지도 요약은 공유마다 `Map(date → count)`, 공유 요약은 공유마다 records `Map(date → 배열)`, routes `Map(date → 첫 항목)`.
2. 날짜마다 아래 순서로 항목을 넣는다. **현재 `filteredForDay`(91–128행)의 순서·조건·문구를 그대로 옮긴다.**
   1. 가계부 금액: `isVisible('ledger') && money` → `수입 ${fc(income)} · 지출 ${fc(expense)}` (key `ledger`)
   2. 장소: `isVisible('places') && visits?.count` → `장소 기록 ${n}건` (key `places`)
   3. 공유 장소: `includeShared && isVisible('places')`, 공유마다 count → `공유 장소 ${n}건` (key `shared-map-${share.id}`)
   4. 여행 기간: `isVisible('travel')`, `overlapsCalendarDate` → `${periodLabel('여행', s, e, date)} · ${title}` (key `travel-${id}`)
   5. 여행 기록: `isVisible('travel')`, 그 날짜의 기록마다 `count = item.count - (isVisible('places') && month.places ? (item.importedCount || 0) : 0)`, `count > 0`이면 `여행 기록 ${count}건` (key `record-${type}`)
   6. 경로: `isVisible('routes') && route?.count` → `경로 ${n}건` (key `routes`)
   7. 일정: `isVisible('events')`, 겹치면 `${periodLabel('일정', s, e, date)} · ${title}` (key `event-${id}`)
   8. 공유 여행: `includeShared && isVisible('travel')`, **공유마다** 기간(겹치면 `${periodLabel('공유 여행', s, e, date)} · ${title}`, key `shared-${type}-${shareId}-${period.id}` ← F8 수정) 다음 그 공유의 기록(`공유 기록 ${count}건`, key `shared-record-${type}-${shareId}-${record.type}`)
   9. 공유 경로: `includeShared && isVisible('routes')`, 공유마다 `공유 경로 ${n}건` (key `shared-route-${type}-${shareId}`)
3. `periodLabel(kind, start, end, date)`는 현재 83–86행을 그대로 export한다. `role`은 `start === end ? 'single' : date === start ? 'start' : date === end ? 'end' : 'middle'`.
4. 워크스페이스에서는 `const daySummaries = computed(() => buildDaySummaries({...}))` **한 번**만 계산하고, 셀은 `daySummaries.get(date)`만 읽는다. 템플릿에서 함수 호출로 다시 계산하지 않는다.

월 KPI `buildMonthSummary({ grid, month, isVisible, includeShared, shared })`도 같은 파일에 둔다. `summary`(77–79), `monthVisits`(80), `busiestExpenseDay`(81), `busiestVisitDay`(82), 기간이 겹치는 여행 수(411), 지출 최다 분류(409)를 그대로 옮기고 날짜는 `formatMonthDay()`(예: `2월 15일(목)`)로 표시한다.

### 4.5 다일 바 레이아웃 — `integratedCalendarBars.js`

```js
/**
 * 한 주(7일) 안에서 기간 항목을 겹치지 않는 레인에 배치한다.
 * @param {string[]} weekDates  일요일부터 토요일까지 연속된 ISO 날짜 7개
 * @param {Array<{key:string, layer:string, title:string, startDate:string, endDate:string, shared?:boolean}>} spans
 * @param {number} maxLanes     표시할 최대 레인 수(0 이상 정수)
 * @returns {{
 *   laneCount: number,          // 실제 사용한 레인 수(≤ maxLanes)
 *   bars: Array<{ key, layer, title, shared, lane, startCol, span, continuesBefore, continuesAfter }>,
 *   hiddenByCol: number[]       // 길이 7, 레인이 모자라 숨겨진 항목 수(열별)
 * }}
 */
export function layoutWeekBars(weekDates, spans, maxLanes) { /* … */ }
```

알고리즘:
1. `weekStart = weekDates[0]`, `weekEnd = weekDates[6]`.
2. 각 span에 대해 `validCalendarDate(start) && validCalendarDate(end) && start <= end`가 아니면 건너뛴다(core `overlapsCalendarDate`와 같은 조건).
3. `clipStart = max(start, weekStart)`, `clipEnd = min(end, weekEnd)`(ISO 문자열 비교). `clipStart > clipEnd`면 이 주와 겹치지 않으므로 건너뛴다.
4. `startCol = weekDates.indexOf(clipStart)`, `endCol = weekDates.indexOf(clipEnd)`, `span = endCol - startCol + 1`, `continuesBefore = start < weekStart`, `continuesAfter = end > weekEnd`. (날짜 계산 없이 indexOf로 충분하다. weekDates가 연속 7일이기 때문이다.)
5. 정렬(주가 바뀌어도 같은 항목이 같은 레인에 남도록 원래 기간 기준): ① 원래 `startDate` 오름차순 ② 원래 `endDate` 내림차순(긴 것 먼저) ③ 레이어 순서 `travel(공유 아님) → travel(공유) → events` ④ `key` 오름차순.
6. 탐욕 배치: `laneEnd = []`. 각 항목에 대해 `laneEnd[l] < startCol`(또는 undefined)인 가장 작은 `l`을 찾는다. `l < maxLanes`면 배치하고 `laneEnd[l] = endCol`. 아니면 숨김: `startCol..endCol` 열의 `hiddenByCol`을 1씩 증가(레인을 차지하지 않음).
7. `laneCount = laneEnd.length`.

예시: 주 `2024-02-11`~`2024-02-17`, spans = [여행 02-14~02-16, 공유 02-15~02-16, 일정 02-15~02-17], maxLanes = 2
→ 여행 lane0(col 3, span 3), 공유 lane1(col 4, span 2), 일정은 col 4–6에서 lane0(끝 5)·lane1(끝 5) 모두 충돌 → 숨김, `hiddenByCol = [0,0,0,0,1,1,1]`, `laneCount = 2`.

수집 함수 `collectMonthSpans({ month, sharedSummaries, isVisible, includeShared })`(integratedCalendarView.js):
- `isVisible('travel')`: `month.travel.periods` → `{ key: 'travel-' + id, layer: 'travel', shared: false }`
- `isVisible('events')`: `month.events` → `{ key: 'event-' + id, layer: 'events' }` (단일 날짜 일정도 1칸짜리 바로 표시)
- `includeShared && isVisible('travel')`: 공유마다 `data.periods` → `{ key: 'shared-' + type + '-' + shareId + '-' + period.id, layer: 'travel', shared: true }`

워크스페이스 결합:
```js
const maxLanes = computed(() => miniCells.value ? 2 : 3)  // miniCells = useMediaQuery('(max-width: 35rem)')
const weeks = computed(() => splitCalendarWeeks(grid.value.days).map(days => {
  const dates = days.map(day => day.date)
  const layout = layoutWeekBars(dates, monthSpans.value, maxLanes.value)
  return { key: dates[0], ...layout, days: days.map((day, col) => ({ ...day, summary: daySummaries.value.get(day.date), hiddenBars: layout.hiddenByCol[col] })) }
}))
```

### 4.6 통합 타임라인 — `buildDayTimeline()` (integratedCalendarView.js)

입력:
```js
buildDayTimeline({
  date, isVisible, includeShared,
  ledgerRows,       // dailyLedger.rows (가계부 레이어가 보이고 daySources.ledger가 있을 때)
  daySources,       // { places, 'travel-records', routes, events }
  periods,          // month.travel.periods 중 date와 겹치는 것(dayPeriods, 41행)
  sharedSummaries, sharedDays, mapDays,
  formatCurrency,
})
```

출력 항목:
```js
{
  key,            // 현재 dayTimeline 키 재사용: ledger row.key, `place-${sourceKind}-${id}`, `period-${id}`, `travel-${id}`, `route-${id}`, `event-${id}`,
                  // `shared-${type}-${shareId}-${id}`(기록), `shared-period-${type}-${shareId}-${period.id}`, `shared-route-${type}-${shareId}-${id}`, `shared-map-${share.id}-${sourceKind}-${id}`
  kind,           // 3.7절 표의 kind
  layer, shared,
  group,          // 'allday' | 'timed' | 'untimed'
  time,           // 정렬용 'HH:MM' 또는 ''
  timeLabel,      // '09:30' | '종일' | '기간' | '계속' | '종료 08:30' | '시간 미지정'
  timeNote,       // ⓘ 문구 또는 ''
  title, subtitle, badges: string[],
  amount,         // { value:number, sign:'+'|'−'|'', tone:'income'|'expense'|'neutral' } | null
  primary:   { label, action: { type, payload } },
  secondary: [ { label, action: { type, payload } } ],
  data,           // 원본 객체(가계부 row, place 등) — 상세 표시용
}
```

규칙(현재 코드에서 그대로 옮길 것):
- 가계부 시간: `row.parts.map(p => p.entryTime).filter(Boolean).sort()[0] || ''`(49–51행).
- 장소 레이어가 보이면 `importedRecordIds`에 있는 여행 기록은 제외(44, 46행). 장소 행의 연결 기록은 `travelRecordsById.get(place.sourceTravelRecordId)`(TRAVEL_IMPORT일 때만, 47행).
- 일정: `ending = item.startDate !== date && item.endDate === date`. 종일이면 `group:'allday', timeLabel:'종일'`. 종일이 아니면 시작 날 `startTime`, 마지막 날 `endTime`(`timeLabel: '종료 ' + endTime.slice(0,5)`), 중간 날 `group:'allday', timeLabel:'계속'`(70–71행).
- 공유 지도 항목은 `visibleSharedMapEntries(share, { travelVisible: isVisible('travel'), sharedDays })`로 거른다(48행 로직: `item.sourceKind !== 'TRAVEL_IMPORT' || !travelVisible || !sharedDays.some(trip => trip.records.some(r => r.id === item.id))`).
- 공유 기간은 `sharedSummaries`의 `data.periods` 중 date와 겹치는 것, 공유 기록·경로는 `sharedDays`의 `records`/`routes`(`isVisible('travel')`/`isVisible('routes')`).
- 정렬: 그룹 순서(allday → timed → untimed), 같은 그룹 안에서 `time` 오름차순, 그다음 `key` 오름차순(75행과 같은 안정 정렬).
- 각 primary/secondary의 `action.payload`는 1.5절 표와 정확히 같아야 한다. 워크스페이스의 `handleOpen(action)`이 `type`별로 emit하거나 오버레이를 연다.

### 4.7 통합 오류(issues) 모델

```js
issues = computed(() => [
  ...Object.entries(monthErrors).filter(([s, m]) => m && isVisibleForMonth(s)).map(([s, m]) => ({ key: `month-${s}`, message: m })),
  ...Object.entries(dayErrors).filter(([s, m]) => m && isVisible(s)).map(([s, m]) => ({ key: `day-${s}`, message: m })),
  ...['options', 'summaries', 'day', 'fatal'].filter(k => sharedErrors[k]).map(k => ({ key: `shared-${k}`, message: sharedErrors[k] })),
  ...['options', 'summaries', 'day', 'fatal'].filter(k => mapErrors[k]).map(k => ({ key: `map-${k}`, message: mapErrors[k] })),
  ...(metadataErrors.value.length ? [{ key: 'metadata', message: `${metadataErrors.value.join('·')} 필터 목록을 불러오지 못했습니다. 다른 자료는 계속 표시됩니다.` }] : []),
  ...(actionError.value ? [{ key: 'action', message: actionError.value }] : []),
])
```
- `isVisibleForMonth('ledger-categories')`는 `isVisible('ledger')`와 같다.
- 메시지 문구는 현재와 같다: 월 `${sourceLabels[s]} 월 정보를 불러오지 못했습니다.`, 일 `${sourceLabels[s]} 상세 정보를 불러오지 못했습니다.` (`sourceLabels`는 35행 그대로).
- `retryFailed()`: 실패한 월 출처만 `loadMonth({ force: true, only })`, 실패한 일 출처만 `loadDay({ force: true, only })`, 공유 오류가 있으면 `loadSharedOptions()`, 지도 오류가 있으면 `loadMapOptions()`, 메타데이터 오류가 있으면 `loadFilterOptions()`, `actionError`는 비운다. 모두 `Promise.all`로 실행하고 그동안 `retrying = true`.
- 배너(`CalendarIssuesBanner.vue`): `v-if="issues.length"`, `role="alert"`, 첫 줄 `일부 자료를 불러오지 못했습니다.`, 이어서 `<ul>`에 메시지(3개 넘으면 나머지는 `<details><summary>외 N건</summary>`), 오른쪽에 `재시도` 버튼 **1개**(`retrying` 동안 disabled). 배경 `--ic-warning-soft`, 왼쪽 테두리 `3px solid var(--ic-warning)`, 경고 아이콘.

---

## 5. 단계별 작업 지시

모든 명령은 저장소 루트 `C:\project\calen`에서 PowerShell 기준이다. 공통 검증 명령:

```powershell
cd frontend; npm run build
cd frontend; npm run test:unit
cd frontend; npx playwright test e2e/integrated-calendar.spec.js --config playwright.config.js --project=chromium-desktop
cd frontend; npx playwright test e2e/integrated-calendar.spec.js --config playwright.config.js
```
(세 번째는 빠른 확인용, 네 번째는 데스크톱+모바일 전체. 단계 끝에는 네 번째까지 통과시킨다.)

### Step 0. 기준선 확인
- 목적: 현재 상태가 통과하는지 확인하고 변경 전 스크린샷을 남긴다.
- 수정 파일: 없음.
- 할 일:
  1. `git status --short`로 미커밋 변경 목록을 기록해 둔다(건드리지 않을 파일 확인용).
  2. 위 공통 검증 명령 4개를 실행한다. Playwright 브라우저가 없으면 `cd frontend; npm run test:e2e:install`. e2e는 `playwright.config.js`의 webServer가 `npm run dev -- --host 127.0.0.1`(5173)을 자동으로 띄우며 API는 전부 목이라 백엔드가 필요 없다.
  3. 뷰포트 테스트가 만든 `frontend/test-results/**/integrated-calendar-390.png`, `integrated-calendar-1920.png`를 확인해 둔다.
- 완료 기준: 빌드 성공, 단위 테스트 전부 통과, e2e 전부 통과(기존에 실패하는 테스트가 있으면 이름과 원인을 기록하고 계속 진행한다. 그 테스트를 이번 작업으로 고치려 하지 않는다).

### Step 1. 순수 로직 lib 추가 + 단위 테스트
- 목적: 화면과 무관한 계산을 먼저 고정한다. 이 단계에서 UI는 바뀌지 않는다.
- 수정/생성:
  - `frontend/src/lib/integratedCalendarCore.js`에 export 추가(기존 함수는 그대로 둔다):
    - `calendarWeekday(iso)` → 0(일)~6(토). 내부 `calendarDate()`(UTC) 사용. 잘못된 날짜면 `RangeError`.
    - `shiftCalendarDate(iso, days)` → ISO. UTC 기준으로 더한다.
    - `monthTargetDate(year, month, todayIso)` → 오늘이 그 달이면 `todayIso`, 아니면 `YYYY-MM-01`.
    - `splitCalendarWeeks(days)` → 7개씩 나눈 배열(42칸 → 6주).
  - `frontend/src/lib/integratedCalendarView.js` (신규, Vue import 금지):
    - `LAYERS`(순서 배열: ledger, places, travel, routes, events), `LAYER_LABELS`, `SOURCE_LABELS`(35행 그대로)
    - `isLayerVisible(layers, source)` → `source === 'travel-records' ? layers.travel : source === 'ledger-categories' ? layers.ledger : Boolean(layers[source])`
    - `periodLabel`, `buildDaySummaries`(4.4), `buildMonthSummary`(4.4), `collectMonthSpans`(4.5), `visibleSharedMapEntries`, `buildDayTimeline`(4.6), `ledgerStatus`(52–59행 그대로 이동)
    - 포맷터: `formatDayHeading(iso, todayIso)`(`2월 15일 목요일`, 연도가 다르면 `2023년 …`), `formatMonthDay(iso)`(`2월 15일(목)`), `formatCellAmount(value, { compact, hidden })`(부호 포함, U+2212, 숨김이면 `•••`, compact는 `Intl.NumberFormat('ko-KR', { notation: 'compact', maximumFractionDigits: 1 })`)
  - `frontend/src/lib/integratedCalendarBars.js` (신규): `layoutWeekBars`(4.5)
  - `frontend/tests/unit/integratedCalendarCore.test.js`에 테스트 추가, `frontend/tests/unit/integratedCalendarView.test.js`, `frontend/tests/unit/integratedCalendarBars.test.js` 생성(목록은 6.2절). 형식은 기존 테스트처럼 `import test from 'node:test'`, `import assert from 'node:assert/strict'`.
- 완료 기준: `npm run test:unit` 통과, `npm run build` 통과(새 파일은 아직 import되지 않아도 됨).
- 검증: `cd frontend; node --test tests/unit/integratedCalendarCore.test.js tests/unit/integratedCalendarView.test.js tests/unit/integratedCalendarBars.test.js`, `cd frontend; npm run test:unit`.

### Step 2. 데이터 composable 추출 + SWR + 재조회 범위 축소 (P0)
- 목적: 깜빡임 제거(D1), 불필요한 공유 재조회 제거(D3, F6, F7, F13), 공유 원본 유지(F5). 템플릿은 거의 그대로 두어 **기존 e2e를 수정 없이 통과**시키는 것이 이 단계의 검증이다.
- 수정/생성: `frontend/src/lib/useIntegratedCalendarSources.js`(신규), `frontend/src/components/IntegratedCalendarWorkspace.vue`.
- 변경 내용:
  1. 4.1~4.3, 4.7절대로 composable을 만든다. 현재 130–282행, 342–352행의 로딩 함수와 관련 상태를 옮긴다.
  2. 워크스페이스는 composable을 호출하고 반환값을 템플릿에서 쓰던 이름에 맞춰 연결한다. 현재 템플릿이 `sharedError`/`mapError` 문자열을 쓰는 곳(390, 401, 402행)은 임시 computed `sharedErrorText = Object.values(sharedErrors).filter(Boolean).join(' ')` 등으로 연결한다. 재시도 버튼은 `loadSharedOptions`/`loadMapOptions`/`loadFilterOptions`로 바꾼다.
  3. `visible()`은 아직 `filter` select 기반 그대로 두되, composable에 `isVisible: visible`로 넘긴다.
  4. '다시 불러오기' 버튼과 focus/visibility 핸들러는 `refreshAll()`을 부른다. 일정 저장/삭제 뒤에는 `refreshEvents()`만 부른다.
  5. `openSharedTravelSource`는 composable 함수로 상세를 받고(권한 재확인 포함), 워크스페이스가 `sharedSource`에 `{ share: { type, shareId }, detail }`을 저장한다. 템플릿의 `:selected-exhibit="sharedSource.detail"`로 바꾼다. 4.2절 마지막 항목대로 권한이 사라지면 닫는다. 재조회로는 닫지 않는다.
  6. `onBeforeUnmount`에서 `dispose()`.
- 완료 기준:
  - 기존 e2e 14개가 **테스트 수정 없이** 데스크톱·모바일 모두 통과.
  - 수동 확인(dev 서버, 6.5절 방법): 브라우저 창 포커스를 옮겼다 돌아와도 달력 칸 내용이 사라졌다 나타나지 않는다. 가계부 분류 필터를 바꿀 때 네트워크 탭에 `/shared/options`, `/shared-map/options`, `/summary/places`, `/summary/travel`, `/summary/events` 요청이 생기지 않는다.
- 검증: 공통 검증 명령 4개.

### Step 3. 날짜별 요약 Map 적용 + 월 이동 규칙 (P0)
- 목적: D2(셀당 6회 계산) 제거, D4(월 이동 시 오늘 선택).
- 수정: `IntegratedCalendarWorkspace.vue`.
- 변경 내용:
  1. `filteredForDay()`(91–128행), `compactPeriod()`(87–89행)를 지우고 `daySummaries = computed(() => buildDaySummaries(...))`를 추가한다. 템플릿 셀 루프는 `cells = computed(() => grid.value.days.map(day => ({ ...day, summary: daySummaries.value.get(day.date) })))`를 돈다.
  2. 기존 템플릿의 `item.compact`(모바일 축약어, 430행)는 Step 7에서 사라진다. 그때까지 워크스페이스에 임시 함수 `legacyCompact(item)`을 둔다: money → 금액 숨김이면 `숨김`, 아니면 `[+compact(income), −compact(expense)].filter(Boolean).join('\n') || '거래' + transactionCount`; count → 레이어/공유에 따라 `장소`·`기록`·`경로`·`공유` + count; span → `여행`/`일정`/`공유` + (start `+`, end `−`). (기존 e2e의 `−5만` 확인용, Step 7에서 삭제)
  3. `summary`, `monthVisits`, `busiestExpenseDay`, `busiestVisitDay`를 `buildMonthSummary()` 결과로 바꾼다. 아직 템플릿 문구는 그대로 둔다(ISO 날짜도 이 단계에서는 유지 — e2e 영향 없음).
  4. `moveMonth(offset)`: 새 연·월 계산 후 `selectedDate = monthTargetDate(year, month, today)`. `selectMonthStart()`/`changeYear()`도 같은 함수를 쓴다. `goToday()`는 그대로.
  5. `dayTimeline`(60–76행)을 `buildDayTimeline()` 기반으로 바꾸되, 이 단계의 '시간순 요약' 출력은 기존 형식(`${time} ${label}`)을 유지한다(기존 e2e `08:30 개인 일정 종료 · 야간 일정` 확인). 필요하면 임시 매핑 함수를 둔다.
- 완료 기준: 기존 e2e 14개 수정 없이 통과. Vue devtools 또는 `console.count`로 확인했을 때 셀 렌더링마다 계산 함수가 다시 호출되지 않는다(확인 후 `console.count` 제거).
- 검증: 공통 검증 명령 4개.

### Step 4. 디자인 토큰 + 공통 UI 기반 컴포넌트
- 목적: E1 해소, 이후 단계에서 쓸 기반 준비.
- 수정/생성:
  - `IntegratedCalendarWorkspace.vue` scoped 스타일: 3.5절 토큰 전체 정의. `#159665`, `#bd3947`, `#9aaa91` fallback을 토큰으로 교체. 컴포넌트 자체의 `:focus-visible` outline 규칙(480, 484행)은 삭제(전역 링 사용). `.integrated-calendar__cell.is-other { opacity }`(494) → 배경/글자 토큰으로 교체. 날짜 셀에 주말 색·오늘 테두리·선택 배지(3.6절)를 현재 마크업 기준으로 먼저 적용(셀에 `data-weekday` 속성 추가해 CSS로 색 지정). 3.10절 규칙(루트 접두 + `!important`)을 지킨다.
  - `frontend/src/lib/useMediaQuery.js`: `export function useMediaQuery(query)` → `ref(boolean)`. `matchMedia(query)`의 `change` 이벤트 구독, `onBeforeUnmount`에서 해제. SSR 가드(`typeof window === 'undefined'`면 false).
  - `frontend/src/components/integrated-calendar/BaseDialog.vue`(3.8절 골격), `ConfirmDialog.vue`, `RowMenu.vue`, `InfoTip.vue`, `CalendarIcon.vue`.
  - `CalendarIcon.vue`: `props: { name: String, size: { default: 16 } }`, `viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true" focusable="false"`. 아이콘(직접 그린 단순 도형):
    - `ledger`: `<rect x="3" y="6" width="18" height="12"/><path d="M3 10h18"/>`
    - `places`: `<path d="M12 21s-6-5.6-6-11a6 6 0 0 1 12 0c0 5.4-6 11-6 11z"/><circle cx="12" cy="10" r="2"/>`
    - `travel`: `<rect x="4" y="7" width="16" height="13"/><path d="M9 7V4h6v3"/>`
    - `routes`: `<circle cx="6" cy="19" r="2"/><circle cx="18" cy="5" r="2"/><path d="M8 19h7a3 3 0 0 0 0-6H9a3 3 0 0 1 0-6h7"/>`
    - `events`: `<rect x="4" y="5" width="16" height="15"/><path d="M4 10h16M9 3v4M15 3v4"/>`
    - `shared`: `<circle cx="9" cy="8" r="3"/><path d="M3 20a6 6 0 0 1 12 0M16 5a3 3 0 0 1 0 6M21 20a6 6 0 0 0-4-5.6"/>`
    - `more`(⋯): `<circle cx="5" cy="12" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/>`
    - `info`: `<circle cx="12" cy="12" r="9"/><path d="M12 11v5M12 8h.01"/>`
    - `chevron-left`/`chevron-right`: `<path d="M15 6l-6 6 6 6"/>` / `<path d="M9 6l6 6-6 6"/>`
    - `refresh`: `<path d="M20 11a8 8 0 1 0-2.3 5.7M20 4v7h-7"/>`
    - `filter`: `<path d="M4 5h16l-6 7v6l-4 2v-8z"/>`
    - `eye` / `eye-off`: `<path d="M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12z"/><circle cx="12" cy="12" r="3"/>` / 같은 모양 + `<path d="M4 4l16 16"/>`
    - `close`: `<path d="M6 6l12 12M18 6L6 18"/>`
    - `warning`: `<path d="M12 3l10 18H2z"/><path d="M12 10v5M12 18h.01"/>`
  - 이 단계에서는 새 컴포넌트를 화면에 연결하지 않는다(연결은 Step 5~11). 빌드에서 미사용 경고는 문제없다.
- 완료 기준: 빌드·단위·e2e 통과. 라이트/다크 테마에서 오늘·선택·주말 표시가 보인다(6.5절 스크린샷).
- 검증: 공통 검증 명령 4개 + 6.5절 스크린샷 테스트(이 단계에서 6.5절 '시각 확인용 스크린샷' 테스트를 먼저 추가해 두면 이후 단계마다 재사용할 수 있다).

### Step 5. 상단 재편 A — 툴바, 월 요약 띠, 통합 오류 배너 (P0)
- 목적: A1(일부), A3, D5, E2 해소.
- 생성: `integrated-calendar/CalendarToolbar.vue`, `integrated-calendar/MonthPicker.vue`, `integrated-calendar/MonthSummaryStrip.vue`, `integrated-calendar/CalendarIssuesBanner.vue`.
- 수정: `IntegratedCalendarWorkspace.vue`, `frontend/e2e/integrated-calendar.spec.js`.
- 변경 내용:
  1. 기존 헤더(367–375행)를 `CalendarToolbar`로 교체. props: `year, month, todayIso, revalidating, activeFilterCount, layers, includeShared`. emits: `move(offset)`, `pick-month({ year, month })`, `today`, `refresh`, `open-settings`, `toggle-layer(source)`, `toggle-shared`. 이 단계에서는 레이어 칩 줄을 아직 렌더링하지 않고(Step 6), '필터와 공유 설정' 버튼은 기존 `<details>`를 열고 닫는 동작(`settingsOpen` 토글 + 스크롤)으로 임시 연결한다. 연도 input·월 select는 제거하고 `MonthPicker`로 대체. 새로고침은 아이콘 버튼(`aria-label="다시 불러오기"`, `revalidating` 동안 아이콘 회전 — `prefers-reduced-motion: reduce`에서는 회전 대신 `aria-busy`만).
  2. 기존 요약(404–413행)을 `MonthSummaryStrip`으로 교체(`aria-label="이번 달 요약"`, 클래스 `integrated-calendar__summary` 유지). KPI: `수입`, `지출`, `수지`(가계부 레이어), `장소 기록 N건`(장소), `여행 N개`(여행), `공유 여행 N개 · 합계 제외`(공유+여행). 각 KPI는 `<span class="…-label">지출</span> <strong>₩50,000</strong>`처럼 **라벨과 값 사이에 공백 문자**를 둔다. 실패하면 값 자리에 `조회 실패`, 첫 로드 중이면 `불러오는 중`. 금액 숨기기 토글(눈 아이콘, `aria-pressed`)을 여기에 두고 drawer의 '금액 숨기기' 체크박스는 Step 6에서 제거. 인사이트(지출 최다일, 지출 최다 분류, 장소 최다일)는 `<details><summary>요약 더보기</summary>`, 날짜는 `formatMonthDay`. ⓘ 문구는 3.7절.
  3. 오류 알림 4개(400–403행)와 상세 섹션 안 오류 문단(448, 455, 459, 463, 464행), 공유 details 안의 오류(390행)를 `CalendarIssuesBanner` 1개로 통합(4.7절). drawer(현재 details) 안의 동작 오류는 해당 섹션에 남긴다.
  4. e2e: 6.3절 헬퍼(`dayCell`, `entries`, `isCompact`, `openDayPanel`, `closeOverlays`, `reload`, `openSettings`)를 이 단계에서 spec 상단에 추가한다. `openDayPanel`/`closeOverlays`는 peek·시트·drawer가 없으면 아무것도 하지 않도록 작성한다(이후 단계에서 수정 불필요). `'다시 불러오기'` 클릭을 `reload(page)`로, 요약 문구 확인을 6.3절대로, 오류 재시도 확인을 배너 기준으로 바꾼다.
- 완료 기준: 공통 검증 통과. 데스크톱에서 필터 details가 닫혀 있을 때 그리드 상단 ≤ 220px(아직 details·미지정이 남아 있으면 Step 6에서 달성해도 됨).

### Step 6. 상단 재편 B — 설정 drawer + 레이어 칩 (P0 + P1)
- 목적: A1, A2 해소, '종류' 단일 select → 다중 레이어 토글.
- 생성: `integrated-calendar/CalendarSettingsDrawer.vue`. 수정: `CalendarToolbar.vue`, `IntegratedCalendarWorkspace.vue`, e2e.
- 변경 내용:
  1. 상태: `filter` ref 삭제 → `layers = reactive({ ledger: true, places: true, travel: true, routes: true, events: true })`. `visible = source => isLayerVisible(layers, source)`. `includeShared`는 '공유 자료' 칩으로 토글.
  2. 툴바 2행에 레이어 칩(3.9절). 칩 = `button[aria-pressed]` > `span.integrated-calendar__chip-face`(레이어 색 사각형 + 아이콘 + 라벨 + 켜짐일 때 체크 표시). 켜짐: soft 배경 + 레이어색 테두리. 꺼짐: 투명 배경 + `--ic-text-soft` + 빈 사각형. 모바일에서 칩 줄은 `overflow-x: auto; scrollbar-width: none;` 가로 스크롤(문서 폭은 늘리지 않음), 칩 높이 2.75rem.
  3. `<details class="integrated-calendar__settings">`(376–399행)와 '날짜 미지정 자료' details(414–422행)를 `CalendarSettingsDrawer`(BaseDialog `variant="drawer"`)로 이동. 열기는 툴바 '필터와 공유 설정' 버튼(`settingsOpen` ref + `v-if`). `settingsOpen` 초기값 matchMedia 로직(16행) 삭제. 기존 '금액 숨기기'·'선택한 공유 자료 표시' 체크박스(385–386행)는 drawer로 옮기지 않는다(각각 요약 띠의 눈 버튼, '공유 자료' 칩으로 이동).
  4. drawer 내용(위에서 아래로): 제목 `필터와 공유 설정` + 닫기 아이콘 버튼 → `<div role="group" aria-label="달력 필터">` 안에 **켜진 레이어의 하위 필터만**:
     - 가계부(`layers.ledger`): 거래 구분, 분류, 결제수단
     - 장소 분류: `layers.places || layers.travel`일 때
     - 여행: `layers.places || layers.travel || layers.routes`일 때, `planId`가 있으면 388행 안내문 표시
     - 켜진 레이어가 없으면 "켜진 자료가 없어 세부 필터가 없습니다."
     → '공유받은 여행 선택'(h3, 389행 설명, 체크박스: `{{ title }} · {{ owner }} ({{ 공유 전시 | 공유 기록 }})`) → '공유받은 기록 지도 선택'(h3, 394행 설명, 주소 폼, 체크박스) → `<details>` '날짜 미지정 자료'(열 때 `undated`가 없을 때만 로드) → 하단 고정 바 '필터 초기화'(하위 필터 5개를 ''로), '닫기'.
  5. 숨겨진 하위 필터 값은 유지하되(쿼리에 계속 쓰임), 툴바 배지 숫자 `activeFilterCount`는 "현재 drawer에 보이는 하위 필터 중 값이 있는 것의 수"로 계산한다. 레이어를 끄면 그 레이어 전용 필터 값은 `''`로 초기화한다(숨은 필터가 몰래 적용되는 혼란 방지). 단 `planId`/`collectionId`는 관련 레이어가 모두 꺼질 때만 초기화.
  6. 미지정 항목의 '원본 보기' 버튼은 1.5절 페이로드 그대로 emit하고 drawer를 닫는다.
  7. e2e: drawer 관련 테스트, '종류' select를 쓰던 테스트를 6.3절대로 수정.
- 완료 기준: 공통 검증 통과. 1440x900에서 그리드 상단 ≤ 220px, 390x844에서 ≤ 260px(6.3절 test 1 측정). drawer: Esc로 닫히고 '필터와 공유 설정' 버튼으로 포커스 복귀. 모바일에서 drawer가 열려 있는 동안 배경 스크롤이 잠기고 닫으면 풀린다.

### Step 7. MonthGrid — 주 단위 연속 바와 셀 재디자인 (P0 + P1)
- 목적: B1~B5 해소.
- 생성: `integrated-calendar/MonthGrid.vue`. 수정: `IntegratedCalendarWorkspace.vue`, e2e.
- 변경 내용:
  1. 워크스페이스에 `monthSpans = computed(() => collectMonthSpans(...))`, `miniCells = useMediaQuery('(max-width: 35rem)')`, `weeks` computed(4.5절 결합 코드)를 추가한다.
  2. `MonthGrid` props: `weeks, selectedDate, todayIso, hideAmounts, initialLoading, revalidating, mini`. emits: `select(date, { fromMore: boolean })`. 마크업은 3.6절. 요일 헤더는 `aria-hidden="true"`로 유지(셀 aria-label에 요일 포함).
  3. 셀 내용: 금액(`summary.ledger`, `formatCellAmount`), 개수 칩/점(`summary.counts`), `+N`(= `hiddenBars` + 넘친 칩 수). 바는 주 행의 바 레이어에 `layout.bars`로 그린다(`data-key`, `data-layer`, `data-shared`, `data-continues-before`, `data-continues-after`, 데스크톱에서만 제목 텍스트).
  4. `aria-label`은 3.6절 형식(`formatDayHeading` + `summary.items` 문구). `legacyCompact`, `.integrated-calendar__compact-text`, `.integrated-calendar__wide-text`, 모바일 범례(425행)를 삭제한다.
  5. `initialLoading`이면 셀마다 회색 막대 1~2개 스켈레톤(`aria-hidden`), `revalidating`이면 그리드 상단 2px 진행 막대(`role="progressbar"` 없이 장식, 그리드 `aria-busy="true"`).
  6. 셀 클릭 → `select(date, { fromMore: Boolean(event.target.closest('.integrated-calendar__more')) })`. 워크스페이스는 `chooseDate(date)` 후 `fromMore`이면 `nextTick` 뒤 상세 제목에 포커스(데스크톱, 이 단계에서는 기존 h2에 `id="integrated-calendar-day-title" tabindex="-1"`을 붙여 사용). compact 동작은 Step 10에서 추가.
  7. e2e: 셀 셀렉터를 `dayCell()`로, 모바일 축약어 확인을 `.integrated-calendar__cell-money`로 바꾼다. 새 테스트 '다일 바'(6.4절)를 추가한다.
- 완료 기준: 공통 검증 통과. 390px에서 셀 금액 글자 ≥ 11px이고 셀 안에 들어간다. 2월 14~16일 여행이 한 줄 연속 바로 보인다(스크린샷).

### Step 8. EventDialog + 앱 내 삭제 확인 (P1)
- 목적: C4, F9 해소. 아직 기존 상세 패널(출처별 섹션) 위에서 작업한다.
- 생성: `integrated-calendar/EventDialog.vue`(ConfirmDialog는 Step 4에서 생성). 수정: 워크스페이스, e2e.
- 변경 내용:
  1. 인라인 폼(439–447행)과 `isEditingEvent`, 공유 `errorMessage`를 삭제한다. 워크스페이스 상태: `eventDialog = ref(null)`(`{ mode: 'create'|'edit', event }`), `confirmDelete = ref(null)`(event). 기존 패널의 '일정 추가'(436행), 일정 카드의 '수정'·'삭제'(464행) 버튼이 각각 EventDialog/ConfirmDialog를 연다.
  2. `EventDialog` props: `initial`(draft 초기값), `busy`, `error`. emits: `submit(payload)`, `delete`, `close`. 초기값은 현재 `beginEvent`(305–309행)와 같다(새 일정은 시작·종료 = 선택 날짜, 종일). 검증은 313행 규칙 그대로, 실패 시 `제목과 시작·종료 날짜 및 시간을 확인해 주세요.`를 폼 상단 `role="alert"`에 표시하고 첫 오류 입력에 포커스. 종일 체크를 해제하면 시간 입력 2개가 나타난다.
  3. 저장(워크스페이스 `submitEvent(payload)`): 318–319행 페이로드로 `saveCalendarEvent(draft.id, …)`. 성공 → 다이얼로그 닫기 → `refreshEvents()`. 409 → 다이얼로그를 열어 둔 채 `다른 화면에서 일정이 변경되었습니다. 다시 불러온 뒤 수정해 주세요.` 표시. 그 밖의 오류 → `error.message`.
  4. 삭제: (이 단계) 일정 카드 '삭제' 또는 EventDialog '삭제' → `ConfirmDialog`. 확인 → `deleteCalendarEvent(id, version)` → 성공 시 확인창·일정창 닫기 → `refreshEvents()` → 포커스를 상세 제목 h2(`id="integrated-calendar-day-title" tabindex="-1"`을 이 단계에서 기존 h2에 붙인다)로. 409 → `actionError = '다른 화면에서 일정이 변경되었습니다. 다시 불러와 주세요.'`(배너). busy 동안 버튼 비활성.
  5. `window.confirm` 사용처가 남아 있지 않은지 `rg "window.confirm" frontend/src/components/IntegratedCalendarWorkspace.vue frontend/src/components/integrated-calendar`로 확인.
  6. e2e: 'monthly totals…' 테스트의 `page.once('dialog', …)`를 지우고, 카드의 '삭제' 클릭 뒤 `page.getByRole('alertdialog', { name: '일정을 삭제할까요?' })`의 '삭제'를 누르도록 바꾼다(Step 9에서 ⋯ 메뉴 경유로 다시 바뀜). EventDialog 키보드·409 테스트(6.4절 6, 7, 8)를 추가한다. mock에 `state.conflict`를 추가한다(6.3절).
- 완료 기준: 공통 검증 통과. Esc로 EventDialog가 닫히고 '일정 추가' 버튼으로 포커스가 돌아온다.

### Step 9. DayPanel — 통합 타임라인 (P1)
- 목적: C1, C2, C3, C7 해소.
- 생성: `integrated-calendar/DayPanel.vue`, `integrated-calendar/DayTimelineRow.vue`. 수정: `IntegratedCalendarWorkspace.vue`, e2e.
- 변경 내용:
  1. 워크스페이스에 `timeline = computed(() => buildDayTimeline(...))`. 기존 `<aside>`(435–469행) 안의 '시간순 요약'과 출처별 섹션을 모두 지우고 `<DayPanel>`으로 교체한다.
  2. `DayPanel` props: `date, todayIso, timeline, dayTotals, initialLoading, revalidating, pendingSources, hideAmounts, mode('panel'|'sheet'), noLayers`. emits: `open(action)`, `add-event`, `edit-event(event)`, `delete-event(event)`, `prev-day`, `next-day`, `close`. 제목 `h2#integrated-calendar-day-title`(tabindex -1).
  3. `DayTimelineRow`: 3.7절 구조. 가계부 행의 `<details class="integrated-calendar__entry-more">` 요약 문구는 `row.isSplit ? '거래 1건 · 결제수단 ' + row.paymentMethodCount + '개 상세' : '거래 상세'`, 내용은 452행의 결제수단별 줄(시간·수단·금액·상태·할부·환불 기준/차감/추가·외화·합계 제외) 그대로.
  4. 워크스페이스 `handleOpen(action)`: `type`별로 1.5절 emit 또는 오버레이 열기. `event-edit`/메뉴 '수정'은 EventDialog, 메뉴 '삭제'는 ConfirmDialog를 연다(Step 8에서 만든 흐름 재사용).
  5. 이전/다음 날: `chooseDate(shiftCalendarDate(selectedDate, ±1))`.
  6. e2e: 행 셀렉터·버튼 이름·⋯ 메뉴 사용으로 6.3절대로 수정(일정 삭제는 `openRowMenu` → `menuitem '삭제'` → alertdialog '삭제').
- 완료 기준: 공통 검증 통과. 같은 항목이 패널에 두 번 나오지 않는다. '원본 보기' 전폭 버튼이 사라졌다. 00:00 행에만 ⓘ가 있다.

### Step 10. 모바일 바텀시트 (P1)
- 목적: C5 해소.
- 수정: 워크스페이스, `DayPanel.vue`, e2e.
- 변경 내용:
  1. `compact = useMediaQuery('(max-width: 60rem)')`, `sheetOpen = ref(false)`. `watch(compact, value => { if (!value) sheetOpen.value = false })`.
  2. 템플릿: `<aside v-if="!compact" class="integrated-calendar__details"><DayPanel mode="panel" …/></aside>`, compact이면 그리드 아래에 `button.integrated-calendar__peek`(sticky bottom: `position: sticky; bottom: 0; z-index: 2;`, 높이 ≥ 2.75rem, 안전영역 `padding-bottom: env(safe-area-inset-bottom)`)와 `<BaseDialog v-if="compact && sheetOpen" variant="sheet" class="integrated-calendar__sheet" labelledby="integrated-calendar-day-title" @close="sheetOpen = false"><DayPanel mode="sheet" … @close="sheetOpen = false"/></BaseDialog>`. (BaseDialog 루트에 class가 붙도록 `inheritAttrs` 기본값 유지)
  3. peek 텍스트: `${formatMonthDay(date)} · 항목 ${count}개${지출 있으면 ' · 지출 ' + 금액} · 상세 보기`. heading/‘일정 추가’ 버튼을 넣지 않는다.
  4. compact에서 셀 선택(`select`) → `chooseDate(date)` + `sheetOpen = true`.
  5. 시트 안에서 EventDialog·ConfirmDialog·SharedSourceOverlay를 열면 시트 위에 중첩 모달로 뜬다(네이티브 top layer). RowMenu의 Esc는 `preventDefault()`로 시트의 cancel을 막는다.
  6. e2e: 모바일 프로젝트에서 상세를 다루는 모든 테스트에 `openDayPanel(page)`이 이미 들어가 있어야 한다(Step 5 헬퍼). 툴바·drawer를 쓰기 전에는 `closeOverlays(page)`(헬퍼들이 내부에서 호출).
- 완료 기준: 공통 검증(특히 `chromium-mobile`) 통과. 390x844에서 날짜를 탭하면 시트가 열리고, Esc/닫기/배경 탭으로 닫히며, 닫은 뒤 탭한 셀에 포커스가 있다. 시트가 열린 동안 페이지 스크롤이 잠기고 닫으면 원래 스크롤 위치로 돌아온다.

### Step 11. 공유 원본 오버레이 (P1)
- 목적: C6 해소.
- 생성: `integrated-calendar/SharedSourceOverlay.vue`. 수정: 워크스페이스, e2e.
- 변경 내용:
  1. 471–472행의 페이지 하단 섹션과 `sharedSourceRef`/`mapSourceRef`/`scrollIntoView`를 삭제한다.
  2. `SharedSourceOverlay` props: `source`(`{ kind: 'travel', detail } | { kind: 'map', grantId, focus }`). BaseDialog `variant="fullscreen"`, `label`은 `공유 여행 원본`/`공유 지도 원본`. 상단 바에 제목과 닫기 버튼(이름 `공유 여행 원본 닫기`/`공유 지도 원본 닫기`). `TravelSharedExhibitWorkspace`, `RecordMapWorkspace`는 이 파일에서 `defineAsyncComponent`로 import(워크스페이스에서는 제거).
  3. 공유 지도 열기: `openSharedMapSource(share, item)`은 `canOpenSharedMap(share.id)`로 현재 선택·권한을 확인한 뒤 `{ kind: 'map', grantId: share.id, focus: { token: Date.now(), placeId: item.placeId, recordId: item.id, sourceKind: item.sourceKind, date: selectedDate } }`(181행과 동일).
  4. 공유 여행 열기 중에는 해당 행 main 버튼을 `aria-busy`/disabled.
  5. e2e: 오버레이 테스트를 6.3절대로 수정. `RecordMapWorkspace` 안의 장소 다이얼로그는 `.record-map-dialog`로 찾는다(바깥 오버레이도 dialog라서 `getByRole('dialog')`만으로는 두 개가 잡힌다).
- 완료 기준: 공통 검증 통과. 지도 오버레이에서 지도가 빈 화면 없이 그려진다(스크린샷 확인). 포커스를 바꿔 refreshAll이 일어나도 오버레이가 닫히지 않는다.

### Step 12. 정리, 접근성 점검, 신규 테스트, 시각 확인
- 목적: 마무리.
- 할 일:
  1. 워크스페이스에 남은 죽은 코드(임시 함수, 쓰지 않는 상태·import, 쓰지 않는 CSS)를 삭제한다. 워크스페이스는 상태 연결과 레이아웃만 남아 대략 250행 이하가 목표다(강제 아님).
  2. 6.4절 신규 e2e를 모두 추가한다.
  3. 접근성 점검표:
     - 모든 아이콘 버튼에 `aria-label`이 있다(`rg "<button" frontend/src/components/integrated-calendar`로 훑어본다).
     - 키보드만으로: 툴바 → 칩 → 셀(Tab/Enter) → 패널 행 → ⋯ 메뉴(↑↓/Esc) → drawer/EventDialog/ConfirmDialog/시트/오버레이 열고 닫기, 닫은 뒤 포커스 복귀.
     - 터치 대상 2.75rem: 툴바 버튼, 칩, 행 main 버튼, ⋯, ⓘ, peek, 다이얼로그 버튼. (셀은 모바일 높이 4.75rem로 충족)
     - 라이트/다크 스크린샷에서 글자 대비 육안 확인(3.5절 값에서 벗어난 색을 쓰지 않았는지 코드 검색: `rg "#[0-9a-fA-F]{3,6}" frontend/src/components/integrated-calendar frontend/src/components/IntegratedCalendarWorkspace.vue` — 토큰 정의 블록 외 하드코딩 색이 없어야 한다).
     - `prefers-reduced-motion: reduce`에서 회전·슬라이드 애니메이션이 꺼진다.
  4. 6.5절 스크린샷 4장(라이트/다크 × 1440/390)과 시트·drawer 열린 상태를 확인한다.
- 완료 기준: 공통 검증 4개 통과, `git status --short`에서 이 작업과 관계없는 파일의 상태가 Step 0 기록과 같다.

---

## 6. 테스트 계획

### 6.1 실행 명령 (package.json·설정 확인 결과)

| 목적 | 명령 (PowerShell, 루트에서) |
|---|---|
| 빌드 | `cd frontend; npm run build` (`vite build`) |
| 단위 테스트 전체 | `cd frontend; npm run test:unit` (`node --test tests/unit/*.test.js`, Node 24) |
| 통합 달력 단위 테스트만 | `cd frontend; node --test tests/unit/integratedCalendarCore.test.js tests/unit/integratedCalendarView.test.js tests/unit/integratedCalendarBars.test.js` |
| e2e 데스크톱만 | `cd frontend; npx playwright test e2e/integrated-calendar.spec.js --config playwright.config.js --project=chromium-desktop` |
| e2e 전체(데스크톱+모바일) | `cd frontend; npx playwright test e2e/integrated-calendar.spec.js --config playwright.config.js` |
| 특정 테스트 | 위 명령 뒤에 `-g "테스트 이름 일부"` |
| 브라우저 설치(필요 시) | `cd frontend; npm run test:e2e:install` |

- **vitest는 설치되어 있지 않다.** `npx vitest`를 쓰지 않는다. 단위 테스트는 `node:test`다.
- e2e는 Vite dev 서버(127.0.0.1:5173)를 자동 실행하고, 이미 떠 있으면 재사용한다. 모든 API는 `mockCalendar()`가 목 처리하므로 백엔드가 필요 없다. 시계는 `page.clock.install({ time: 2024-02-15T12:00:00+09:00 })`로 고정된다.

### 6.2 단위 테스트 목록

`integratedCalendarCore.test.js`(추가):
- `calendarWeekday('2024-02-15') === 4`, `'2024-02-11' === 0`, 잘못된 날짜는 `RangeError`.
- `shiftCalendarDate('2024-02-29', 1) === '2024-03-01'`, `('2024-03-01', -1) === '2024-02-29'`, `('2023-12-31', 1) === '2024-01-01'`.
- `monthTargetDate(2024, 2, '2024-02-15') === '2024-02-15'`, `monthTargetDate(2024, 3, '2024-02-15') === '2024-03-01'`.
- `splitCalendarWeeks(calendarMonthGrid(2024, 2).days)` → 길이 6, 각 7, 첫 주 첫 날 `2024-01-28`.

`integratedCalendarBars.test.js`(신규):
- 한 주 안 다일 항목: 2/14~2/16 → `startCol 3, span 3, lane 0, continuesBefore false, continuesAfter false`.
- 주 경계를 넘는 항목: 2/16~2/20을 첫 주(2/11~17)와 둘째 주(2/18~24)로 각각 배치 → 첫 주 `startCol 5, span 2, continuesAfter true`, 둘째 주 `startCol 0, span 3, continuesBefore true`.
- 겹치면 다른 레인, 안 겹치면 레인 0 재사용(2/11~12, 2/14~15 → 둘 다 lane 0).
- 4.5절 예시(maxLanes 2) → `hiddenByCol = [0,0,0,0,1,1,1]`, `laneCount 2`.
- `maxLanes 0` → bars 없음, 겹친 열마다 hidden 증가.
- 잘못된 날짜(`null`, `2023-02-29`), `start > end`, 주와 겹치지 않는 항목은 무시.
- 정렬 결정성: 입력 순서를 섞어도 결과가 같다.
- 긴 항목 우선: 같은 시작일이면 끝이 늦은 항목이 lane 0.

`integratedCalendarView.test.js`(신규):
- `buildDaySummaries`:
  - 이웃 달 날짜도 Map에 들어가지만(42칸) 월 합계(`buildMonthSummary`)에는 `2024-03-01`의 100,000이 포함되지 않는다(e2e 목 데이터와 같은 값으로).
  - 문구 순서와 내용이 현재 `filteredForDay`와 같다: ledger → places → shared-map → travel 기간 → travel 기록 → routes → events → shared(기간, 기록) → shared routes. `periodLabel` 결과 `일정 시작 · X`, `일정 기간 · X`, `일정 종료 · X`, 단일 날짜는 `일정 · X`.
  - 여행 기록 수 차감: places 레이어 보임 + `month.places` 있음 → `count - importedCount`, places 꺼짐 → 차감 안 함, 결과 0 이하면 항목 없음.
  - `includeShared false`면 공유 항목 없음. 레이어를 끄면 해당 항목 없음.
  - 같은 공유에 기간 2개 → key가 서로 다름(F8).
  - 날짜당 ledger 항목이 중복이면 첫 항목 사용.
- `buildDayTimeline`:
  - 분할 결제 2건 → 행 1개, primary 페이로드 `{ type:'ledger', payload:{ date, entryId: 1 } }`.
  - 가져온 장소(TRAVEL_IMPORT, sourceTravelRecordId 17) + 여행 기록 17 + 별도 방문(VISIT, sourceTravelRecordId 17): places 보임 → 기록 17 행 없음, 장소 2행, 가져온 장소 행 secondary에 `여행 기록 원본 보기`(페이로드 `{ sourceType:'memory', planId:10, recordId:17 }`), 별도 방문 행에는 없음. places 꺼짐 → 기록 17 행 1개.
  - 다일 일정 21:00~08:30(2/14~2/15) → 2/15에서 `timeLabel '종료 08:30'`, `group 'timed'`. 3일 일정 중간 날 → `'계속'`, `'allday'`.
  - 공유 지도 TRAVEL_IMPORT 항목이 같은 ID의 공유 여행 기록과 겹치면 travel 보임일 때 숨김, travel 꺼짐이면 표시(`visibleSharedMapEntries`).
  - 정렬: allday → timed(시간순) → untimed, 동률은 key 순.
  - 가계부 00:00 → `timeNote`에 `가계부의 00:00은` 포함, 10:00 → `''`.
  - 공유 기록 금액은 `amount.tone === 'neutral'`이고 배지에 `합계 제외`.
- 포맷터: `formatDayHeading('2024-02-15', '2024-02-15') === '2월 15일 목요일'`, 연도 다르면 `'2023년 12월 31일 일요일'`; `formatMonthDay('2024-02-15') === '2월 15일(목)'`; `formatCellAmount(-50000, { compact: true })`가 `'−5만'`; `formatCellAmount(50000, { hidden: true }) === '•••'`.

### 6.3 e2e 헬퍼와 기존 테스트 변경 매핑

spec 상단에 추가(Step 5):
```js
const dayCell = (page, date) => page.locator(`.integrated-calendar__cell[data-date="${date}"]`)
const entries = page => page.locator('.integrated-calendar__entry')
const isCompact = page => page.evaluate(() => window.matchMedia('(max-width: 60rem)').matches)
async function closeOverlays(page) {
  for (const selector of ['dialog.integrated-calendar__drawer', 'dialog.integrated-calendar__sheet']) {
    const dialog = page.locator(selector)
    if (await dialog.count() && await dialog.isVisible()) { await page.keyboard.press('Escape'); await expect(dialog).toHaveCount(0) }
  }
}
async function openDayPanel(page) {
  const peek = page.locator('.integrated-calendar__peek')
  if (!await peek.count() || !await peek.isVisible()) return   // 데스크톱 또는 Step 10 이전
  if (await page.locator('dialog.integrated-calendar__sheet').count()) return
  await peek.click()
  await expect(page.locator('dialog.integrated-calendar__sheet')).toBeVisible()
}
async function reload(page) { await closeOverlays(page); await page.getByRole('button', { name: '다시 불러오기', exact: true }).click() }
async function openSettings(page) {
  await closeOverlays(page)
  await page.getByRole('button', { name: '필터와 공유 설정' }).click()
  const drawer = page.getByRole('dialog', { name: '필터와 공유 설정' })
  await expect(drawer).toBeVisible()
  return drawer
}
const openRowMenu = entry => entry.getByRole('button', { name: /^추가 작업/ }).click()
```
(Step 5 시점에는 drawer가 아직 details이므로 `openSettings`는 Step 6에서 위 형태로 완성한다. Step 5에서는 details summary를 여는 임시 구현을 두어도 된다.)

`mockCalendar` 변경:
- 옵션: `{ theme = 'default' } = {}`. `theme === 'toss'`이면 `page.goto` 전에 `await page.addInitScript(() => localStorage.setItem('calen-theme-mode', 'toss'))`.
- `state`에 `requests: []`, `delayMs: 0`, `conflict: false` 추가. `PUT /api/integrated-calendar/events/9` 처리 맨 앞에 `if (state.conflict) return route.fulfill({ status: 409, json: { message: 'conflict' } })`. 라우트 핸들러 첫 줄에서 `state.requests.push(\`${req.method()} ${path}\`)`, `/api/integrated-calendar/summary/` 경로는 `if (state.delayMs) await new Promise(r => setTimeout(r, state.delayMs))`(Node 쪽 타이머라 `page.clock`의 영향을 받지 않음).
- 마지막 대기·설정 열기(81–82행)를 다음으로 교체:
  ```js
  await expect(dayCell(page, '2024-02-15')).toHaveAttribute('aria-pressed', 'true')
  await expect(dayCell(page, '2024-02-15')).toHaveAttribute('aria-label', /지출/)
  ```
- `keepSettingsClosed` 옵션은 삭제한다(drawer는 기본으로 닫혀 있음).

기존 테스트별 변경(시나리오는 모두 유지):

| 기존 테스트 | 변경 |
|---|---|
| 1. mobile prioritizes the calendar… | 390x844로 `mockCalendar`. `.integrated-calendar__month`의 top − `.integrated-calendar`의 top ≤ 260 확인. drawer 없음(`toHaveCount(0)`) 확인 → '필터와 공유 설정' 버튼 `focus()` + `Enter` → drawer 보임, `drawer.getByLabel('달력 필터')` 보임 → `Escape` → drawer `toHaveCount(0)`, 버튼 `toBeFocused()` |
| 2. multi-day event end time | `state.events` 설정 → `reload(page)` → `openDayPanel(page)` → `entries(page).filter({ hasText: '야간 일정' })`가 `종료 08:30` 포함 |
| 3. monthly totals / split / undated / CRUD | 요약 `50,000` 포함·`100,000` 미포함 유지. `openDayPanel` 후 '분할 거래' 행 1개, `거래 1건 · 결제수단 2개 상세` 클릭, 카드·현금 포함. 미지정: `openSettings` → `drawer.getByText('날짜 미지정 자료', { exact: true }).click()` → `drawer.getByRole('listitem').filter({ hasText: '날짜 없는 장소' })`, `'기간 없는 여행'` 보임 → `closeOverlays`. 일정 추가: `openDayPanel` → '일정 추가' → `.integrated-calendar__form`에 제목·종료 날짜 입력 → '저장' → 폼 `toHaveCount(0)` → '개인 일정' 행 보임 → `dayCell` 2/15·2/16·2/17의 aria-label이 각각 `/일정 시작 · 개인 일정/`, `/일정 기간 · 개인 일정/`, `/일정 종료 · 개인 일정/` → `.integrated-calendar__bar[data-key="event-9"]` `toHaveCount(1)`(2/15~17은 한 주 안) → `state.writes` 1건. `page.reload()` → `openDayPanel` → 행 보임 → 행의 `getByRole('button', { name: /^일정 수정/ })` 클릭 → 메모 입력 → 저장 → 행에 메모 포함. 삭제: `openRowMenu(row)` → `page.getByRole('menuitem', { name: '삭제' })` → `page.getByRole('alertdialog', { name: '일정을 삭제할까요?' })`가 `다른 원본 기록은 삭제되지 않습니다` 포함 → 그 안의 '삭제' 클릭 → 행 `toHaveCount(0)`. `page.once('dialog', …)` 삭제. `state.errors` 빈 배열 유지 |
| 4. shared trips selection / separate / revoke | `entries`에서 `[data-shared="true"]` 행 0개 확인 → `openSettings` → `drawer.getByRole('checkbox', { name: /친구 여행/ })` 미체크 확인 → `check()` → 체크됨 → `closeOverlays` → `.integrated-calendar__bar[data-shared="true"]` `toHaveCount(1)` → `openDayPanel` → `page.locator('.integrated-calendar__entry[data-shared="true"]').filter({ hasText: '친구 여행' })` 보임·`공유` 포함 → 요약 `toContainText(/지출\s*₩50,000/)` → 행의 `/^공유 여행 원본 보기/` 버튼 클릭 → `page.getByRole('dialog', { name: '공유 여행 원본' }).getByRole('heading', { name: '친구 여행 원본' })` 보임 → '공유 여행 원본 닫기' → 오버레이 `toHaveCount(0)` → `page.reload()` → 공유 바 1개 → `state.sharedAvailable = false` → `reload(page)` → 공유 바 `toHaveCount(0)`, 공유 행 `toHaveCount(0)` |
| 5. shared map add / deselect / revoke | `openSettings` → `drawer.getByLabel('기록 지도 공유 주소')` 입력 → '공유 지도 추가' → 체크박스 `/친구 장소 모음/` 체크됨 → `closeOverlays` → `dayCell(2/15)` aria-label `/공유 장소 1건/` → `openDayPanel` → '친구 방문' 행 보임 → `/^공유 장소 원본 보기/` 클릭 → `page.locator('.record-map-dialog').getByText('친구 방문', { exact: true })` 보임 → `.record-map-dialog`의 '창 닫기' → `page.getByRole('dialog', { name: '공유 지도 원본' })`의 '공유 지도 원본 닫기' → `openSettings` → 체크 해제 → `closeOverlays` → aria-label에 `공유 장소` 없음 → 다시 `openSettings`·체크 → 있음 → `state.mapAvailable = false` → `page.evaluate(() => window.dispatchEvent(new Event('focus')))` → 없음, '친구 방문' 행 `toHaveCount(0)` |
| 6. failed source reported | `state.failPlaces = true` → `reload` → `getByRole('alert').filter({ hasText: '장소·기록 월 정보' })` 보임 → 요약 `조회 실패`, `50,000` 포함 → `state.failPlaces = false` → **배너의 '재시도' 버튼** 클릭 → 해당 alert `toHaveCount(0)` |
| 7. refund / cancellation / reversed | `state.adjustments = true` → `reload` → `openDayPanel` → 기존 단언 그대로(`entries(page)` 사용). '거래 상세'는 행 안 details 요약 |
| 8. failed share source + metadata | `page.reload()` → 두 alert 문구(`공유 여행 기록 목록`, `가계부 분류 필터 목록`) 보임 → 요약 `50,000` → 공유 바 1개 → `state.failCategories = false` → `getByRole('alert').filter({ hasText: '가계부 분류 필터 목록' }).getByRole('button', { name: '재시도' })` 클릭 → 그 필터 결과 `toHaveCount(0)`, `공유 여행 기록 목록` alert는 남음 → `openDayPanel` → 공유 행 보임 (**배너 조작은 시트를 열기 전에** 한다. 시트가 열리면 배경이 inert다) |
| 9. imported place grouped | `importedPlace = true` → `reload` → `openDayPanel` → `entries(page).filter({ has: page.locator('.integrated-calendar__entry-title', { hasText: '여행 원본' }) })` 1개, '별도 방문' 1개 → `closeOverlays` → `page.getByRole('group', { name: '표시할 자료' }).getByRole('button', { name: '장소·기록', exact: true })` 클릭 → `aria-pressed="false"` → `openDayPanel` → '여행 원본' 제목 행 1개(이번엔 여행 기록 행), '별도 방문' 0개 |
| 10. visit → original place | `reload` → `openDayPanel` → '별도 방문' 행의 `/^장소 원본 보기/` → `page.getByRole('dialog')`에 `서울 장소`, `별도 방문` (화면 이동으로 통합 달력이 언마운트되므로 dialog는 하나) |
| 11. grouped transaction → original row | `openDayPanel` → '분할 거래' 행의 `/^가계부 원본 보기/` → 기존 단언 그대로 |
| 12. route → original route | `routeMode` → `reload` → `openDayPanel` → `/^경로 원본 보기/` → 기존 단언 |
| 13. imported memory → editor | `routeMode`, `importedPlace` → `reload` → `openDayPanel` → `entries(page).filter({ hasText: '여행에서 가져온 기록' })` → `openRowMenu` → `menuitem '여행 기록 원본 보기'` → heading `여행 기록 수정` |
| 14. viewport matrix (데스크톱 전용) | 루프에서 각 크기마다 `closeOverlays`. 가로 넘침 검사 유지. `.integrated-calendar__cell` 넘침 검사 유지. `.integrated-calendar__filters label` 검사 → `.integrated-calendar__layer-chip` 각각 `scrollWidth <= clientWidth + 1`. 390일 때: `dayCell(2/15).locator('.integrated-calendar__cell-money').first()`가 `−5만` 포함, 높이 > 5, 셀 안에 들어감, `parseFloat(getComputedStyle(el).fontSize) >= 11`. 390·1920 스크린샷 유지, 390에서는 `openSettings` 후 drawer 스크린샷 추가 → `closeOverlays`. 이후 320x700: `openSettings` → `drawer.getByLabel('기록 지도 공유 주소')` 보임 + 가로 넘침 없음 → `closeOverlays` → `openDayPanel` → '일정 추가' → 넘침 없음 → 폼의 '시작 날짜' 보임 → '취소' → `closeOverlays`. 1920 줌 루프: `page.getByRole('heading', { name: /2월 15일/ })` 보임. 마지막: `dayCell(2/16)` focus + Enter → heading `/2월 16일/` 보임 |

### 6.4 신규 e2e

1. **월 이동 시 오늘 선택**: '다음 달' → `dayCell('2024-03-01')` `aria-pressed="true"` → '이전 달' → `dayCell('2024-02-15')` pressed. 연월 선택: `.integrated-calendar__month-trigger` 클릭 → `getByRole('dialog', { name: '연월 선택' })`에서 `5월` 클릭 → `2024-05-01` pressed, 트리거 텍스트 `2024년 5월` → 다시 열고 `Escape` → 팝오버 닫힘, 트리거 포커스.
2. **재조회 중 데이터 유지(SWR)**: `state.delayMs = 1500` → `reload(page)` → 즉시 `dayCell(2/15)` aria-label에 `지출` 포함 유지, `.integrated-calendar__month`에 `aria-busy="true"` → 응답 후 `aria-busy="false"`.
3. **필터 변경 시 공유 재조회 없음**: 로드 완료 후 `state.requests.length` 기억 → `openSettings` → '거래 구분'을 `수입`으로 선택 → `expect.poll`로 `/summary/ledger` 요청 1건 증가 확인 → 증가분 중 `/shared/options/`, `/shared-map/options`, `/summary/places`, `/summary/travel`, `/summary/events`가 0건.
4. **주 경계를 넘는 다일 바**: `state.events = [{ id: 9, version: 0, title: '긴 일정', startDate: '2024-02-15', endDate: '2024-02-20' }]` → `reload` → `.integrated-calendar__bar[data-key="event-9"]` 2개, 첫째 `data-continues-after="true"`, 둘째 `data-continues-before="true"` → `dayCell('2024-02-18')` aria-label `/일정 기간 · 긴 일정/`.
5. **레이어 칩 하위 필터 노출**: '가계부' 칩 끄기 → `openSettings` → drawer에 '거래 구분' 레이블 없음 → 닫고 다시 켜기 → 있음. 셀 aria-label에 `수입 ₩` 문구가 꺼진 동안 없음.
6. **EventDialog 키보드**: (데스크톱) '일정 추가' → 제목 입력에 포커스 → `Escape` → 폼 `toHaveCount(0)`, '일정 추가' 버튼 포커스. 빈 제목으로 '저장' → `role=alert`에 `제목과 시작·종료 날짜 및 시간을 확인해 주세요.`
7. **삭제 확인 초기 포커스**: 일정 생성 후 삭제 메뉴 → alertdialog의 '취소'가 포커스, `Escape`로 닫히고 일정 유지.
8. **409 충돌 문구**: 목에 `state.conflict = true`일 때 `PUT /events/9`가 409 → 저장 시 다이얼로그 안에 `다른 화면에서 일정이 변경되었습니다. 다시 불러온 뒤 수정해 주세요.`
9. **모바일 시트**(모바일 프로젝트 전용, `test.skip(info.project.name !== 'chromium-mobile')`): `dayCell('2024-02-16')` 탭 → `dialog.integrated-calendar__sheet` 보임, 제목 `/2월 16일/` → 시트의 '다음 날' → `/2월 17일/` → `Escape` → 시트 사라짐, `dayCell('2024-02-16')` 포커스(시트를 연 요소).
10. **공유 원본이 재조회로 닫히지 않음**: 공유 선택 후 공유 여행 원본 열기 → `window.dispatchEvent(new Event('focus'))` → 오버레이 계속 보임 → `state.sharedAvailable = false` → focus 이벤트 → 오버레이 닫힘, 배너에 `공유 권한이 변경되어 원본을 닫았습니다.`

### 6.5 스크린샷 확인 방법

spec에 데스크톱 프로젝트 전용 테스트를 추가한다(테마별 2개). `mockCalendar`를 재사용하고 풍부한 데이터를 켠다.

```js
for (const theme of ['default', 'toss']) {
  test(`visual review screenshots (${theme})`, async ({ page }, info) => {
    test.skip(info.project.name !== 'chromium-desktop', 'Screenshots are reviewed manually once per theme.')
    const state = await mockCalendar(page, { theme })
    Object.assign(state, { importedPlace: true, routeMode: true, selected: true })
    state.events = [{ id: 9, version: 0, title: '야간 일정', startDate: '2024-02-14', endDate: '2024-02-20', allDay: false, startTime: '21:00', endTime: '08:30' }]
    await reload(page)
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.screenshot({ path: info.outputPath(`ic-1440-${theme}.png`) })
    await page.setViewportSize({ width: 390, height: 844 })
    await page.screenshot({ path: info.outputPath(`ic-390-${theme}.png`) })
    await openDayPanel(page)
    await page.screenshot({ path: info.outputPath(`ic-390-sheet-${theme}.png`) })
    await openSettings(page)
    await page.screenshot({ path: info.outputPath(`ic-390-drawer-${theme}.png`) })
    expect(state.errors).toEqual([])
  })
}
```
- 실행: `cd frontend; npx playwright test e2e/integrated-calendar.spec.js --config playwright.config.js --project=chromium-desktop -g "visual review"`
- 결과: `frontend/test-results/<테스트 폴더>/ic-*.png`. 이미지 뷰어로 열어 확인한다.
- 확인 항목: 첫 화면에 달력 그리드가 보이는가(1440: 그리드 전체 또는 5주 이상, 390: 그리드 전체 + peek), 2/14~2/20 바가 두 주에 걸쳐 연속으로 그려졌는가, 오늘(15일) 배지와 선택 배지, 일/토 색, 지출 금액 색, 공유 바 점선/빗금, 다크에서 글자 대비, 시트·drawer 레이아웃이 잘리지 않는가.
- `test-results/`, `playwright-report/`는 산출물이며 커밋하지 않는다.
- (선택) dev 서버로 직접 보기: `cd frontend; npm run dev -- --host 127.0.0.1` 후 브라우저 `http://127.0.0.1:5173/#integrated-calendar`. 실제 데이터는 백엔드(8080)가 필요하므로 시각 확인은 위 목 테스트를 기본으로 한다.

---

## 7. 리스크와 비범위

### 7.1 리스크와 대응

| 리스크 | 대응 |
|---|---|
| 전역 CSS `!important`가 버튼·다이얼로그 스타일을 덮음(F1) | 3.10절 규칙. 단계마다 라이트/다크 스크린샷으로 계산된 스타일 확인 |
| 모바일 스크롤 잠금이 풀리지 않음(F15) | 다이얼로그는 `v-if`로만 존재. peek 바와 비모달 팝오버에는 `aria-modal`을 쓰지 않음. 테스트 9에서 시트 닫은 뒤 스크롤 가능 여부 확인(`window.scrollTo(0, 200)` 후 `scrollY > 0`) |
| 중첩 모달(시트 위 EventDialog/오버레이/RecordMapDialog)에서 Esc가 바깥까지 닫음 | 네이티브 `cancel`은 최상위 dialog에만 발생. RowMenu·MonthPicker의 Esc 처리에서 `preventDefault()` + `stopPropagation()` |
| 공유 지도 오버레이 안에서 Leaflet 지도가 빈 화면 | dialog `showModal()` 뒤 `nextTick`에 자식 렌더링, 오버레이 본문 높이를 명시(`height: calc(100dvh - 상단바)`) |
| 공유 여행 원본 컴포넌트가 dialog 안에서 스크롤/고정 요소 문제 | 오버레이 본문 `overflow: auto`. 문제 시 해당 컴포넌트 수정은 하지 말고 오버레이 쪽 레이아웃으로 해결하고 보고 |
| SWR로 필터 변경 직후 잠깐 이전 값 표시 | 진행 막대 + `aria-busy`. 실패 시 데이터 제거(1.4절 정합성 우선) |
| 출처별 시퀀스로 바꾸며 경쟁 조건 회귀 | Step 2 완료 기준에서 기존 e2e 무수정 통과 필수. 언마운트 `dispose()` |
| 셀 높이 증가로 1440x900에서 6주가 다 안 보임 | 데스크톱 셀 최소 6.5rem, 레인 많은 주만 높아짐. 스크린샷에서 5주 이상 보이면 허용 |
| e2e의 `getByRole` 이름 부분 일치로 strict 위반(예: '오늘', '여행', '수정') | 3.9절 이름 계약, 테스트는 `exact: true` 또는 정규식 `^…` 사용 |
| `page.clock` 하에서 JS 타이머 기반 지연 로직 | 스켈레톤 지연 등 타이머 로직을 두지 않는다 |
| 미커밋 무관 변경 훼손 | 0장 3항. Step 12에서 `git status --short`를 Step 0과 비교 |

### 7.2 비범위 (P2 — 이번에 구현하지 않음, 후속 과제)
- 주 보기·목록(어젠다) 보기 전환.
- 달력에서 드래그로 기간 선택해 일정 생성, 바 드래그로 일정 기간 변경.
- `role="grid"` + 화살표 키 셀 이동(roving tabindex), PageUp/PageDown 월 이동.
- `+N` 클릭 시 그날 미리보기 팝오버(이번에는 패널 포커스 방식).
- 레이어 켜짐 상태·금액 숨김 설정을 localStorage나 서버 계정 설정에 저장.
- 공휴일·기념일 표시(데이터 소스 없음).
- 자정 경과 시 `today` 자동 갱신(현재는 focus 때만 갱신).
- 시트 스와이프 제스처(드래그로 높이 조절·닫기).
- 공유 지도 사진 라이트박스(현재 새 탭 링크 유지).
- 월 요약 인사이트 확장(카테고리별 막대 등).
- 오프라인/네트워크 재시도 백오프.

---

## 부록 A. 단계별 체크리스트

- [ ] Step 0 기준선: 빌드·단위·e2e 결과와 `git status` 기록
- [ ] Step 1 순수 lib + 단위 테스트
- [ ] Step 2 composable + SWR + 재조회 범위 (기존 e2e 무수정 통과)
- [ ] Step 3 dayItems Map + 월 이동 오늘 선택 (기존 e2e 무수정 통과)
- [ ] Step 4 토큰 + BaseDialog/ConfirmDialog/RowMenu/InfoTip/CalendarIcon/useMediaQuery
- [ ] Step 5 툴바 + 월 요약 띠 + 통합 오류 배너 + e2e 헬퍼
- [ ] Step 6 설정 drawer + 레이어 칩
- [ ] Step 7 MonthGrid + 연속 바 + 셀 재디자인
- [ ] Step 8 EventDialog + 삭제 확인
- [ ] Step 9 DayPanel 통합 타임라인
- [ ] Step 10 모바일 바텀시트
- [ ] Step 11 공유 원본 오버레이
- [ ] Step 12 정리 + 접근성 + 신규 e2e + 스크린샷
