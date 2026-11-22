# 가계부 거래 시트: 원거래 + 취소·환불 처리 기록 묶음 표시 설계 및 작업 지시서

> 대상 독자: 이 문서만 보고 구현할 에이전트. 문서의 행번호는 작성 시점(main, `e1eabee2` + 작업 디렉터리 미커밋 변경) 기준이며, 구현 전에 반드시 해당 파일을 다시 열어 위치를 확인한다.

## 0. 꼭 지킬 규칙 (먼저 읽을 것)

1. **합계·데이터 정합성은 바꾸지 않는다.** `financiallyExcluded` 규칙, 일·월 합계 계산(`calendarLedgerDay`, `calendarLedgerMonth`, 백엔드 집계 SQL)은 그대로 둔다. 이번 작업은 "표시 묶음 + 관리 창 통합"이다.
2. **DB 스키마 변경·마이그레이션 금지.** 백엔드는 5.3절의 **읽기 전용 조회 API 1개 추가**만 허용한다. 기존 API의 동작·응답 형식은 바꾸지 않는다.
3. **새 npm 의존성 금지.**
4. 작업 디렉터리에는 이번 작업과 무관한 미커밋 변경이 있다(`frontend/src/style.css`, `frontend/src/lib/api.js`, 백엔드 일부, `docs/` 일부 등). **되돌리거나 정리하지 않는다.** `frontend/src/lib/api.js`는 기존 미커밋 변경(관리자 탈퇴 모니터 함수 추가)을 보존한 채, 5.3절 함수 1개만 기존 `fetchLedgerAdjustments` 근처에 추가한다. 커밋은 하지 않는다(요청받은 경우에만, 작업 파일만 스테이징).
5. `frontend/src/style.css`는 수정하지 않는다. 스타일은 컴포넌트 scoped 스타일에 둔다. 전역 CSS가 버튼 배경·테두리를 `!important`로 강제하고 다크 테마(`:root[data-theme='toss']`) 규칙의 특이도가 매우 높다. 버튼 시각 상태를 배경에 의존하지 말고, 꼭 필요하면 루트 클래스 접두 + `!important`를 쓴다(참고: `docs/integrated_calendar_ux_workplan.md` 3.10절, `docs/record_map_ux_workplan.md` 3.6절). `border-radius`는 전역에서 0으로 강제된다.
6. 모바일(`:root[data-layout-mode='mobile']`)에서 거래 시트 표는 카드형으로 바뀌며, 각 `td`의 라벨이 `style.css`의 `td:nth-child(2)`, `td:nth-child(3)`, `td:nth-child(6)` 및 클래스(`sheet-table__title`, `sheet-table__category`, `sheet-table__amount`, `sheet-table__memo`, `sheet-table__actions`)로 붙는다(`style.css` 16293~16381, 16535~16559행 부근). **행의 `td` 개수·순서(체크박스, 시간, 구분, 제목, 카테고리, 결제수단, 금액, [메모], 작업)와 클래스를 바꾸지 않는다.** 정보는 기존 `td` 안에 추가한다.
7. App.vue는 모바일에서 `[role="dialog"][aria-modal="true"]`가 있으면 스크롤을 잠그고, Esc는 마지막 aria-modal 다이얼로그의 `data-modal-close` 버튼을 누른다. 관리 창은 기존처럼 오버레이에 `role="dialog" aria-modal="true"`, 닫기 버튼에 `data-modal-close`를 유지한다.
8. node:test로 실행되는 `frontend/src/lib/*.js`끼리의 import는 **반드시 `.js` 확장자**를 붙인다(예: `import { ... } from './ledgerSheetGroups.js'`). Vue 컴포넌트에서는 기존처럼 확장자 없이 import해도 된다.
9. e2e가 의존하는 접근성 이름은 유지한다: 다이얼로그 `거래 시트`, `거래 관리`, `분할 결제 상세`, `환불 기록`, `결제 취소 기록`; 행 버튼 `수정`/`관리`/`공유`/`분할 결제 보기`; 관리 창 버튼 이름이 `거래 내용 수정`, `환불`, `결제 취소`, `삭제`로 시작하는 것, `닫기`.

---

## 1. 현행 구조

### 1.1 데이터 모델 (백엔드)

`backend/src/main/java/com/playdata/calen/ledger/domain/LedgerEntry.java`

| 필드 (행) | 의미 |
|---|---|
| `financiallyExcluded` (104) | true면 모든 합계·통계에서 제외 |
| `cancelledAt` (107) | 원거래에만 설정. 활성 결제 취소가 있으면 값이 있음 |
| `refundedPrincipal` (110) | 원거래에만 의미. 활성 환불들의 기준금액 합 |
| `hasAdjustmentHistory` (113) | 원거래에 처리 기록이 한 번이라도 생기면 true. **되돌려도 다시 false가 되지 않는다** |
| `adjustmentType` (117) | 처리 기록 행에만 설정: `PAYMENT_CANCELLATION` / `REFUND` (`LedgerAdjustmentType.java`) |
| `adjustmentOriginId` (121) | 처리 기록 행 → 원거래 id |
| `adjustmentPrincipal/Deduction/Extra` (127~133) | 처리 금액 정보 |
| `adjustmentVoidedAt` (136) | 처리 기록이 **되돌려진(void)** 시각 |
| `splitPaymentGroupId/Index/Count` (146~152) | 분할 결제 묶음. 처리 기록 행에는 설정되지 않는다 |

조회 DTO `dto/LedgerEntryResponse.java` 35~48행에 위 필드가 모두 노출된다(`LedgerEntryService.toResponse` 1471~1505행).

### 1.2 처리 기록 생성·되돌림 흐름

`ledger/service/LedgerAdjustmentService.java`

- `create`(44~143행): 원거래는 **그대로 두고 별도 행을 하나 더 만든다.**
  - 결제 취소(94~113행): 원거래 `financiallyExcluded=true`, `cancelledAt=처리일시`. 새 행은 `entryType=EXPENSE`, 제목 `"결제 취소: " + 원제목`, `amount = 원거래 금액`, 카테고리·결제수단·외화 정보 복사, `financiallyExcluded=true`.
  - 환불(114~140행): 원거래 `refundedPrincipal += principal`. 새 행은 `entryType=INCOME`, 제목 `"환불: " + 원제목`, `amount = principal - deduction + extra`, 수입 카테고리·실제 수령 수단. **이 행은 합계에 포함된다**(수입).
  - 공통: 원거래 `hasAdjustmentHistory=true`(141행). 처리일은 원거래 날짜 이후만 허용(78행) — 프론트 기본값은 `max(오늘, 원거래 날짜)`(`LedgerAdjustmentDialog.vue` 19~20행)라서 **처리 기록은 원거래와 다른 날짜에 생기는 경우가 흔하다.**
  - 취소된 원거래(`cancelledAt != null`)나 처리 기록 행은 다시 처리할 수 없다(74~77행). 환불 이력이 있으면 결제 취소 불가(95행).
- `revert`(146~173행): 처리 기록을 지우지 않는다. 결제 취소 되돌림은 원거래 `financiallyExcluded=false`, `cancelledAt=null`; 환불 되돌림은 `refundedPrincipal -= principal`. 처리 기록 행은 `financiallyExcluded=true`, `adjustmentVoidedAt=now`가 된다. 원거래의 `hasAdjustmentHistory`는 true로 남는다.
- 처리 이력이 있는 원거래와 처리 기록 행은 일반 수정·삭제 불가(`LedgerEntryService.requireOrdinaryEditableEntry` 1508~1512행). 공유도 처리 기록 행·합계 제외 행은 불가(`getEntryForSharing` 647~654행).
- 조회 API(`web/LedgerEntryController.java`): `POST /api/entries/{id}/adjustments`(234행), `GET /api/entries/{id}/adjustments`(242행, **원거래 id만** 허용 — 처리 기록 id면 400, `LedgerEntryService.getAdjustmentHistory` 656~663행), `POST /api/entries/{id}/adjustments/revert`(249행). 원거래 한 건을 id로 조회하는 GET API는 없다.

### 1.3 시트 데이터 흐름과 건수

- `HouseholdWorkspace.vue`가 달력 범위의 거래를 `fetchEntries(from, to)`로 받아 `monthEntries`(2409~2420행) → `sortedMonthEntries` → `CalendarWorkspace`의 `entries` prop으로 넘긴다.
- `CalendarWorkspace.vue`
  - `selectedDateEntries`(1042~1051행): 선택 날짜 + `거래 구분` 필터 → 시간 정렬(`entryTime || '99:99'`, id 보조키, ASC/DESC).
  - `normalizedSelectedDateEntries`(1125~1130행): `visibleMemo` 추가.
  - `groupedSelectedDateEntries = groupLedgerSheetEntries(...)`(1131행), 페이지 분할(1132~1138행, 페이지 크기 1000).
  - 포커스 이동 watch(1140~1146행)가 `row.parts`에서 `focusEntryId`를 찾는다.
  - 일괄 선택 대상 `selectableDayEntries`(1147~1149행): `row.parts` 중 `!hasAdjustmentHistory && !adjustmentType`.
  - 건수 라벨 `selectedDateCountLabel`(1172~1175행): 행 수와 원본 건수가 같으면 `N건`, 다르면 `N건 · 세부 거래 M건`. 처리 기록 행도 1건으로 센다 → 사용자 화면의 `3건`.
  - 시트는 패널(5313~5346행)과 모달(5360~5400행) 두 곳에서 같은 props로 렌더링된다. `adjust-entry`, `revert-adjustment`, `show-adjustments`는 그대로 상위로 emit된다.
- `frontend/src/lib/ledgerSheetGroups.js` `groupLedgerSheetEntries`(3~21행): `splitPaymentGroupId`가 있고 `adjustmentType`이 없는 행만 분할 묶음. 주석(1~2행)대로 **환불·취소 기록은 의도적으로 원거래와 묶지 않는다.** `splitDisplayedAmount`(23~25행)는 센트 단위 합산, `splitExpectedCount`(27~29행).

### 1.4 시트 행 구성과 배지 라벨 (`frontend/src/components/HouseholdTransactionSheet.vue`)

- 분할 묶음 행(248~268행): 상태 배지는 일부 수단에 상태가 있으면 무조건 `처리 이력 있음`(114, 256행).
- 일반 행(269~320행): `v-for="entry in row.parts"`로 1행. 체크박스는 `hasAdjustmentHistory || adjustmentType`이면 비활성(274행). 제목 칸에 `statusLabel(entry)` 배지(287행), 처리 기록이면 `원거래 #id`(289~292행). 작업 칸: 처리 이력/처리 기록이면 `관리`, 아니면 `수정`(316행); 공유는 `!financiallyExcluded && !adjustmentType`일 때만(317행).
- `statusLabel`(150~159행) — `StatisticsWorkspace.vue` `adjustmentLabel`(227~234행), `integratedCalendarView.js` `ledgerStatus`(23~30행)에 **같은 로직이 3벌 복사**되어 있다:
  1. `adjustmentVoidedAt` → `처리 되돌림` (처리 기록 행이 되돌려진 경우. 유형 정보가 사라진다)
  2. `PAYMENT_CANCELLATION` → `결제 취소 기록`
  3. `REFUND` → `환불 입금`
  4. 원거래 `cancelledAt` → `결제 취소됨`
  5. `refundedPrincipal > 0` → `환불 완료`/`부분 환불`
  6. `hasAdjustmentHistory` → `처리 이력 있음` (활성 처리가 없는 원거래 = **모든 처리가 되돌려진 원거래**)
- 금액 칸은 상위의 `formatAmountMain/Sub`(CalendarWorkspace 4373~4389행; 원화/외화 모드)로 표시. 합계 제외 여부가 금액 칸에 표시되지 않는다.

### 1.5 관리 창 구조

- 시트 내부 `거래 관리` 오버레이(377~413행). `managedEntry`(단일 행) 또는 `managedSplit`(분할 묶음)을 받는다.
  - 원거래(처리 이력 있음): `환불`/`결제 취소`(가능할 때), `처리 내역`(405행 → `show-adjustments` emit).
  - 처리 기록 행: `되돌리기`(406행, 활성일 때만). **이미 되돌려진 처리 기록 행은 버튼이 하나도 없는 빈 창**이 된다(캡처로 확인).
  - 일반 거래: `거래 내용 수정`, `환불`, `결제 취소`, `삭제`.
- `처리 내역`은 시트 밖 `HouseholdWorkspace.vue`의 별도 다이얼로그 `LedgerAdjustmentHistoryDialog.vue`(제목 `취소·환불 처리 내역`)에서 `GET /{originId}/adjustments`로 불러온다(HouseholdWorkspace 113~122, 5711~5713행). 그 안에서 `이 처리 되돌리기`.
- 환불/취소 입력은 `LedgerAdjustmentDialog.vue`(HouseholdWorkspace 124~146, 5708~5710행), 되돌림은 `undoLedgerAdjustment`(148~163행; `window.confirm` 후 `revertLedgerAdjustment` → `refreshLedgerViews`).
- 즉, 거래 하나를 다루는 데 **시트 행 2개 + 관리 창 2종 + 처리 내역 창 1개**가 필요하다.

### 1.6 합계와 건수

- 프론트 일 합계 `calendarLedgerDay`(`integratedCalendarCore.js` 39~56행)와 월 합계(58~68행)는 행 묶음과 무관하게 **원본 행 단위**로 `financiallyExcluded`가 아닌 것만 더한다. 이번 변경으로 영향 없음.
- 백엔드 `CalendarSummaryService.ledger`(34~53행)의 `transactionCount`는 분할 묶음을 1건, 처리 기록 행을 각각 1건으로 센다. 프론트에서는 `integratedCalendarView.js` 108행에서 객체에 담기만 하고 **화면에 표시하지 않는다**(grep 결과 `.vue`에서 사용처 없음). → 이번 작업에서 백엔드 건수 SQL은 바꾸지 않는다(7절 비범위).

### 1.7 같은 그룹 함수를 쓰는 곳

- 통합 달력: `calendarLedgerDay`가 `groupLedgerSheetEntries(daily)`를 쓴다(42~47행). 일별 원본은 `/api/integrated-calendar/day/ledger`(선택한 하루치만). `buildDayTimeline`(`integratedCalendarView.js` 190행~, 가계부 행 205~227행)이 `ledgerStatus(first)` + `· 원거래 id` 배지를 만들고, `DayTimelineRow.vue` 36~40행의 `거래 상세` details가 `item.data.parts`를 나열한다.
- 통계 검색 결과(`StatisticsWorkspace.vue` 2571행)는 서버 페이지 단위 목록이며 그룹 함수를 쓰지 않는다.

---

## 2. 문제점

### 2.1 사용자 사례 재현 (확인됨)

사용자 화면의 3행을 같은 필드로 목 데이터를 만들어 현재 코드로 띄우면 화면이 그대로 재현된다(작업자 로컬 임시 폴더의 `current-sheet-*`, `current-manage-*`, `current-history-*` 캡처, 데스크톱 1440x900·모바일 390x844, 라이트/다크).

- 2행 `구독 서비스 A : 정기결제` = 원거래 #4101: `hasAdjustmentHistory=true`, `cancelledAt=null`, `refundedPrincipal=0`, `financiallyExcluded=false`.
- 3행 `결제 취소: 구독 서비스 A : 정기결제` = 결제 취소 기록: `adjustmentType=PAYMENT_CANCELLATION`, `adjustmentOriginId=4101`, **`adjustmentVoidedAt` 값 있음**, `financiallyExcluded=true`.

원인:
1. `groupLedgerSheetEntries`가 처리 기록을 일부러 원거래와 묶지 않는다(1.3절). 그래서 원거래와 처리 기록이 별도 행·별도 `관리` 창으로 나뉘고 건수도 `3건`이 된다.
2. 원거래와 처리 기록 행 각각에 상태 라벨을 따로 붙인다. 하나의 사건(결제 취소 → 되돌림)이 두 라벨로 쪼개진다.

### 2.2 '처리 되돌림'의 의미 (코드로 확정)

- `statusLabel`의 첫 분기(`HouseholdTransactionSheet.vue` 151행)는 **`adjustmentVoidedAt`이 있을 때만** `처리 되돌림`을 반환한다. `adjustmentVoidedAt`은 `LedgerAdjustmentService.revert`(172행)에서만 설정된다. 따라서 3행의 결제 취소는 **실제로 되돌려진(void) 상태**다. 라벨 로직 버그가 아니다.
- 이와 맞게 원거래는 `cancelledAt=null`, `financiallyExcluded=false`로 돌아와 있고 `hasAdjustmentHistory`만 남아 있어 6번 분기 `처리 이력 있음`이 붙는다.
- **결과: 구독 서비스 A ₩29,000은 지금 지출 합계에 포함되어 있다.** 결제 취소가 반영된 상태가 아니다.
- 되돌려진 경위 추정: 3행의 `관리` 창에는 `되돌리기` 버튼만 있다(406행). 중복처럼 보이는 행을 정리하려고 이 버튼을 눌렀을 가능성이 높다(확인 대화상자는 뜨지만 문구가 "되돌리시겠습니까"뿐이다). 지금 다시 결제 취소하면 취소 기록이 하나 더 생기며, 새 설계에서는 같은 행 하나에 "되돌린 취소 1건 + 적용 중인 취소 1건"으로 보인다.

### 2.3 그 밖의 문제

- P1. 라벨이 사건을 설명하지 못한다: `처리 이력 있음`은 실제로는 "모든 처리가 되돌려짐"이고, `처리 되돌림`은 무엇이 되돌려졌는지(취소인지 환불인지) 알 수 없다.
- P2. 되돌려진 처리 기록 행의 `관리` 창은 버튼이 없는 빈 창이다(캡처 `current-manage-adjustment-*`).
- P3. 처리 이력을 보려면 `관리` → `처리 내역` → 또 다른 창으로 이동한다. 창을 닫으면 시트로 돌아간다.
- P4. 금액 칸에 합계 제외 여부가 없다. 원거래가 취소되어 합계에서 빠져도 `₩29,000`이 그대로 보인다.
- P5. 관리 창 요약의 시간 줄이 `2026-10-22 - · 지출`처럼 보인다(`formatTime(null)`이 `-`).
- P6. 원거래는 모든 처리를 되돌린 뒤에도 일반 수정·삭제가 영원히 막힌다(`hasAdjustmentHistory` 유지 + `requireOrdinaryEditableEntry`). 백엔드 정책이므로 이번 범위 밖(7절).
- P7. 같은 라벨 로직이 3곳에 복사되어 있다(1.4절).

### 2.4 `record-sharing.spec.js` 기준선 실패 2건 (확인됨, 이번 변경과 무관)

`transaction sheet keeps edit, share and delete reachable beside long memos`(347~377행)가 desktop·mobile 두 프로젝트 모두 실패한다. 실제로 실행해 보니 `locator.scrollIntoViewIfNeeded` 시간 초과이며, `.sheet-table__actions-inner` 안에서 `삭제` 버튼을 찾지 못한다. 거래 시트 행의 `삭제` 버튼은 이미 `거래 관리` 창 안으로 옮겨졌고(행에는 `수정`·`공유`만, 314~319행), 테스트만 예전 구조를 기대하고 있다. **이번 묶음 변경이 원인이 아니며, 이번 변경 후에도 같은 이유로 실패한다.** 5.7단계에서 테스트를 현재 UX(행에는 `수정`·`공유`, 삭제는 관리 창)에 맞게 고친다.

---

## 3. 목표 설계

### 3.1 원칙

- **하나의 거래 = 시트의 한 행 = 하나의 관리 창.** 같은 화면 범위(같은 날짜·같은 필터)에 원거래와 그 처리 기록이 함께 있으면 원거래 행 하나로 묶는다.
- 원거래 행이 대표다. 시간·구분·카테고리·결제수단·메모는 원거래 값.
- 상태 요약은 **원거래 필드(`cancelledAt`, `refundedPrincipal`, `hasAdjustmentHistory`)를 기준**으로 만든다. 처리 기록이 다른 날짜에 있어 화면에 없더라도 상태는 정확하다.
- 금액 칸은 "합계에 반영되는 방식"을 그대로 보여준다. **순액(원거래 − 환불)은 표시하지 않는다.** 합계에서 제외되는 기록은 취소선 + `합계 제외`.
- 처리 기록이 원거래와 다른 날짜에 있으면 그 날짜의 시트에는 처리 기록이 **독립 "연결 처리" 행**으로 남는다(환불 입금은 그날 수입 합계에 포함되므로 숨기면 안 된다). 이 행의 `관리`도 같은 통합 관리 창(원거래 + 전체 이력)을 연다.

### 3.2 묶음 규칙

입력: 이미 날짜·필터·정렬이 적용된 하루치 목록(현행과 동일한 입력).

| 규칙 | 내용 |
|---|---|
| 원거래 판단 | `adjustmentType`이 없는 행 |
| 처리 기록 판단 | `adjustmentType`이 있는 행 |
| 묶음 키 | 처리 기록의 `adjustmentOriginId`가 같은 입력 안의 원거래 id와 같으면 그 원거래가 속한 행(일반 행 `entry:{id}` 또는 분할 묶음 행 `split:{groupId}`)의 `adjustments`에 붙는다 |
| 연결 처리 행 | 원거래가 입력 안에 없으면 `kind: 'adjustment'`, key `adjustment:{id}`, `parts: [처리 기록]`, `adjustments: []`인 독립 행 |
| 행 위치 | 입력 순서에서 대표(원거래 또는 분할 첫 수단, 연결 처리 행은 자기 자신)의 첫 등장 위치. 처리 기록의 시간은 행 위치에 영향을 주지 않는다(정렬 DESC에서 처리 기록이 원거래보다 먼저 와도 원거래 위치에 묶인다) |
| `adjustments` 내부 순서 | 항상 시간순 오름차순: `entryDate`, `entryTime || ''`, `id` |
| `parts` | 원거래(또는 분할 수단)만. 처리 기록은 `parts`에 넣지 않는다(연결 처리 행 제외). 그래서 `splitDisplayedAmount(row.parts)`, 일괄 선택, 분할 수단 수 계산이 바뀌지 않는다 |
| 분할 결제 | 분할 수단 하나에 대한 처리 기록은 분할 묶음 행의 `adjustments`에 붙는다. 분할 상세 창에서는 각 수단 카드 아래에 그 수단의 처리 기록만 보인다 |
| 입력 불변 | 입력 배열과 원소를 변경하지 않는다 |

### 3.3 상태 요약 라벨

원거래 `o`와 같은 행에 묶인 처리 기록 목록 `items`로 계산한다.

| 우선순위 | 조건 | status | 라벨 |
|---|---|---|---|
| 1 | `o.cancelledAt` | `CANCELLED` | `결제 취소됨` |
| 2 | `refundedPrincipal >= amount` (> 0) | `REFUNDED` | `환불 완료` |
| 3 | `refundedPrincipal > 0` | `PARTIALLY_REFUNDED` | `부분 환불` |
| 4 | `o.hasAdjustmentHistory` 또는 `items`가 있음 (활성 효과 없음 = 모두 되돌려짐) | `REVERTED` | `items`가 모두 결제 취소면 `취소 되돌림`, 모두 환불이면 `환불 되돌림`, 섞였거나 `items`가 비었으면 `처리 되돌림` |
| 5 | 그 외 | `NONE` | `''` |

근거: 결제 취소가 활성이면 `cancelledAt`이 반드시 있고, 환불이 활성이면 `principal > 0`(서비스 `money(principal, false)`)이라 `refundedPrincipal > 0`이다. 따라서 4번은 "모든 처리가 되돌려짐"과 정확히 같다.

처리 기록 단건 라벨(이력 목록·연결 처리 행·통계 검색·분할 상세에 사용):

| 기록 | 활성 | 되돌림 |
|---|---|---|
| 결제 취소 | `결제 취소` (행 배지로 쓸 때는 `결제 취소 기록`) | `결제 취소 되돌림` |
| 환불 | `환불 입금` | `환불 되돌림` |

단일 행 라벨 함수 `ledgerEntryStatusLabel(entry)`(문맥 없이 한 행만 볼 때): 처리 기록이면 `결제 취소 기록` / `환불 입금`, 되돌려졌으면 `결제 취소 기록 · 되돌림` / `환불 입금 · 되돌림`. 원거래면 3.3 표의 라벨(`items` 없음).

분할 묶음 행 배지 `splitAdjustmentLabel(row)`: 각 수단에 대해 `summarizeLedgerAdjustments(part, 그 수단의 adjustments).label`을 구하고, 라벨이 있는 수단이 n개이며 라벨이 모두 같으면 `n개 수단 {라벨}`(예: `1개 수단 결제 취소됨`), 서로 다르면 `n개 수단 처리 있음`, 없으면 배지 없음.

### 3.4 금액·건수 표시 규칙

| 경우 | 금액 칸 주 표시 | 보조 줄 |
|---|---|---|
| 일반 원거래(처리 없음/되돌림) | 기존 `formatAmountMain(o)` | 기존 `formatAmountSub(o)`(외화) |
| 원거래 `financiallyExcluded`(결제 취소됨) | 같은 값에 **취소선** | `결제 취소 · 합계 제외` + 외화 보조값(있으면, 역시 취소선) |
| 부분 환불 / 환불 완료 | 원거래 금액 그대로(지출 합계에 그대로 반영되기 때문) | `환불 기준 ₩{refundedPrincipal}`; 같은 행에 활성 환불 기록이 묶여 있으면 `환불 입금 +₩{합}`(이 날 수입 합계에 포함) |
| 연결 처리 행: 활성 환불 | 기존 금액(수입) | `환불 입금 · 원거래 다른 날짜` |
| 연결 처리 행: 결제 취소 기록(항상 합계 제외), 되돌린 환불 | 취소선 | `합계 제외` |

- 환불 관련 금액은 항상 원화(`money()`)다. 환불 기록에는 외화 정보가 없다. 결제 취소 기록은 원거래 외화 정보를 복사하므로 `formatAmountMain/Sub`를 그대로 써도 된다.
- 취소선은 `text-decoration: line-through`를 가진 span 클래스로 주고, 의미는 보조 줄 텍스트(`합계 제외`)로 전달한다(색만으로 전달하지 않는다).
- 건수 라벨: `{행 수}건`. 원본 기록 수와 다르면 `{행 수}건 · 세부 기록 {원본 수}건`(현행 `세부 거래` → `세부 기록`). 사용자 사례: `2건 · 세부 기록 3건`.
- 금액 숨김(통합 달력 `hideAmounts`): 배지·상세에 넣는 금액은 반드시 전달받은 `formatCurrency`로 만든다(숨김일 때 `금액 숨김`이 된다). 외화 원문 표기는 숨김일 때 넣지 않는다(현행 규칙 유지).

### 3.5 시트 행 와이어프레임

데스크톱 (사용자 사례, 되돌린 취소 1건):
```
□ | 시간  | 구분 | 제목                                          | 카테고리  | 결제수단        | 금액     | 메모            | 작업
□ | 21:31 | 지출 | 구독 서비스 B : 팀 요금제              | 취미/구독 | 테스트 카드 | ₩72,000  | -               | [수정][공유]
  |       |      |                                               |           |                 | US$50.00 |                 |
▣ | -     | 지출 | 구독 서비스 A : 정기결제 [취소 되돌림]          | 취미/구독 | 테스트 카드 | ₩29,000  | 구독 서비스 A 이용료 | [관리][공유]
  (비활성)            처리 이력 1건 · 결제 취소 되돌림
헤더 배지: 2건 · 세부 기록 3건
```

같은 거래를 다시 결제 취소한 뒤:
```
▣ | -  | 지출 | 구독 서비스 A : 정기결제 [결제 취소됨]                 | ... | ~~₩29,000~~          | ... | [관리]
                처리 이력 2건 · 결제 취소 되돌림 · 결제 취소 10월 22일      결제 취소 · 합계 제외
```

부분 환불(같은 날 환불 입금):
```
▣ | 12:30 | 지출 | 검증용 결제 [부분 환불]                 | ... | ₩100,000              | ... | [관리][공유]
                   처리 이력 1건 · 환불 입금                       환불 기준 ₩30,000
                                                                  환불 입금 +₩25,000
```

다른 날짜(원거래 9/10, 이 날은 10/22) 연결 처리 행:
```
▣ | 14:00 | 수입 | 환불: 검증용 결제 [환불 입금]          | 기타 수입 | 현금 | ₩25,000         | ... | [관리]
                   원거래 #7 · 다른 날짜의 거래                            환불 입금 · 원거래 다른 날짜
```

- 제목 칸 두 번째 줄 `adjustment-detail`: 묶인 처리가 있으면 `처리 이력 {n}건 · {각 기록 단건 라벨(+원거래와 날짜가 다르면 ' M월 D일')}`를 `·`로 연결. 묶인 처리는 없는데 `hasAdjustmentHistory`면 `다른 날짜에 처리 기록이 있습니다`.
- 기존 `원거래 #id`(289~292행)는 연결 처리 행에서만 표시. 기존 환불 기준 상세(`기준 · 차감 · 추가`)는 관리 창 이력으로 옮긴다(연결 처리 환불 행에는 유지해도 된다).
- 체크박스: 원거래가 처리 이력이 있으면 기존처럼 비활성. 연결 처리 행도 비활성. 접근성 이름 `aria-label="{제목} 선택"`(처리 이력 있는 행은 `title` 속성으로 "처리 이력이 있어 일괄 작업에서 제외됩니다").
- 작업 칸: `관리`(처리 이력/묶인 처리/연결 처리 행) 또는 `수정`(그 외). `공유`는 대표 원거래가 `!financiallyExcluded && !adjustmentType`일 때만(현행과 같음, 연결 처리 행은 없음). 버튼 칸 grid(446~447행 2열)는 그대로 둔다. 버튼이 1개면 1열에 놓여도 된다.

모바일(카드형): 동일한 정보가 `td` 단위 라벨(시간/구분/제목/…/작업)로 쌓인다. 제목 칸 안에서 배지와 상세 줄이 오른쪽 정렬(444~445행 규칙)로 이어진다. 금액 칸에 취소선 주 금액과 보조 줄이 세로로 쌓인다. 새로운 `td`는 만들지 않는다.

### 3.6 통합 관리 창 와이어프레임 (`거래 관리`)

```
┌ 거래 관리 ─────────────────────────────────────── [닫기] ┐
│ 원거래와 취소·환불 처리 이력을 함께 확인하고 처리합니다.     │
│ ┌ 요약 ───────────────────────────────────────────────┐ │
│ │ 구독 서비스 A : 정기결제                [취소 되돌림] │ │
│ │ 2026-11-21 · 시간 미지정 · 지출                        │ │
│ │ 취미 / 구독 · 테스트 카드                         │ │
│ │ ₩29,000  (취소됨이면 취소선 + '합계 제외')              │ │
│ │ 메모: 구독 서비스 A 이용료                                 │ │
│ └──────────────────────────────────────────────────────┘ │
│ 처리 이력 (h3)                                            │
│ ┌──────────────────────────────────────────────────────┐ │
│ │ 결제 취소 되돌림 · 2026-11-21                           │ │
│ │ 결제 취소 금액 ₩29,000 · 정기결제 취소                  │ │
│ │ 되돌림 2026-11-21 10:00                                │ │
│ └──────────────────────────────────────────────────────┘ │
│ ┌ (활성 기록 예) ───────────────────────────────────────┐ │
│ │ 환불 입금 · 2026-10-25 14:00                            │ │
│ │ 기준 ₩30,000 − 차감 ₩5,000 + 추가 ₩0 = 수령 ₩25,000 · 현금│ │
│ │ 적용 중                              [이 처리 되돌리기]  │ │
│ └──────────────────────────────────────────────────────┘ │
│ [환불 …] [결제 취소 …] [거래 내용 수정 …] [삭제]  (가능한 것만) │
│ 처리 이력이 있는 거래는 기록 보존을 위해 일반 수정·삭제할 수 없습니다. │
└──────────────────────────────────────────────────────────┘
```

- 시간 줄: `entryTime`이 없으면 `시간 미지정`(P5 해결). 날짜·시간·구분을 `·`로 연결.
- `처리 이력` 섹션은 이력이 있거나 `hasAdjustmentHistory`일 때만 보인다. `<section aria-labelledby="ledger-manage-history-title">` + `<h3 id="ledger-manage-history-title">처리 이력</h3>` + `<ol>`.
  - 불러오는 중: `<p role="status">처리 이력을 불러오는 중입니다…</p>`(먼저 로컬로 알고 있는 이력은 그대로 보여준다).
  - 실패: `<p role="alert">처리 이력을 모두 불러오지 못했습니다. 이 날짜의 기록만 표시합니다.</p>` + `다시 시도` 버튼.
  - 항목: 단건 라벨(3.3) + 날짜·시간; 환불은 `기준 − 차감 + 추가 = 수령 · 수령 수단`, 결제 취소는 `결제 취소 금액 ₩x`; 메모; `적용 중` 또는 `되돌림 {adjustmentVoidedAt 날짜 시간}`; 활성 항목만 `이 처리 되돌리기` 버튼.
- 작업 버튼(대상은 표시 중인 원거래 `o`, 현행 조건 재사용):
  - `거래 내용 수정`, `삭제`: `!o.hasAdjustmentHistory && !o.adjustmentType`.
  - `환불`: `canRefund(o)`, `결제 취소`: `canCancel(o)`(현행 163~170행).
  - 현행 `처리 내역`(405행), 단독 `되돌리기`(406행) 버튼은 **삭제**한다(이력과 되돌리기가 창 안에 있다).
- 연결 처리 행에서 열었을 때: 원거래를 서버에서 받아오기 전에는 요약 카드에 처리 기록 정보와 `원거래 정보를 불러오는 중입니다…`를 보여 주고, 원거래 대상 작업 버튼(환불·결제 취소)은 원거래를 받은 뒤에만 표시한다. 받아온 원거래의 날짜를 요약에 표시한다. 불러오기 실패 시에도 처리 기록 자체의 `이 처리 되돌리기`는 동작한다.
- 버튼 동작은 현행 `managerAction` 패턴(창을 닫고 emit)을 유지한다. 되돌리기 후 상위가 목록을 새로 고치면 행의 상태 요약이 바뀐다. 창을 다시 열면 최신 이력이 보인다.
- 분할 묶음 행의 `수정`은 현행 분할 관리 흐름(396~400행, `chooseSplitAction`)을 그대로 둔다. 분할 상세 창에서 수단의 `관리`/`수정·처리`는 이 통합 관리 창을 해당 수단 기준으로 연다.

### 3.7 엣지 케이스 표

| # | 상황 | 처리 |
|---|---|---|
| E1 | 원거래 + 같은 날 결제 취소(활성) | 1행, `결제 취소됨`, 금액 취소선 + `결제 취소 · 합계 제외`, 공유 없음 |
| E2 | 원거래 + 같은 날 결제 취소(되돌림) — 사용자 사례 | 1행, `취소 되돌림`, 금액 정상, 공유 가능, 체크박스 비활성 |
| E3 | 같은 원거래에 처리 여러 건(되돌린 취소 + 재취소, 환불 여러 건, 되돌린 환불 + 결제 취소) | 1행, 상태는 원거래 필드 기준 1개, 상세 줄·이력에 모두 시간순 |
| E4 | 처리 기록이 다른 날짜, 원거래 날짜의 시트 | 원거래 행만. 상태 정확. 상세 줄 `다른 날짜에 처리 기록이 있습니다`. 관리 창이 서버에서 전체 이력 로드 |
| E5 | 처리 기록이 다른 날짜, 처리 날짜의 시트 | 연결 처리 행(`kind: 'adjustment'`). `관리` → 서버에서 원거래 + 전체 이력 로드 |
| E6 | 원거래가 달력 로드 범위 밖(이전 달) | E5와 동일. `fetchEntries` 범위와 무관하게 조회 API가 원거래를 준다 |
| E7 | `거래 구분` 필터 `지출`: 환불(수입) 기록이 빠짐 | 원거래 행만, 상태는 원거래 필드로 정확(`부분 환불`) |
| E8 | 필터 `수입`: 원거래(지출)가 빠짐 | 환불 기록이 연결 처리 행으로 표시 |
| E9 | 분할 수단 하나 결제 취소 | 분할 묶음 행 1개, 배지 `1개 수단 결제 취소됨`, 금액은 현행대로 표시된 수단 합계(`환불·취소를 차감한 금액이 아닙니다` 안내 유지), 분할 상세의 해당 수단 카드에 그 처리 기록 표시 |
| E10 | 외화 원거래 결제 취소 | 원화/외화 토글은 원거래 기준. 취소선은 주·보조 금액 모두 |
| E11 | 시간 정렬 DESC에서 처리 기록이 원거래보다 늦은 시간 | 행 위치는 원거래 위치. 이력은 항상 오름차순 |
| E12 | 일괄 선택 | `parts` 기준 현행 규칙 그대로(처리 이력 있는 원거래·처리 기록은 제외). 전체 선택 체크박스 동작 불변 |
| E13 | 통합 달력에서 처리 기록 id로 원본 열기(`focusEntryId` = 처리 기록 id) | 그 기록이 묶인 원거래 행으로 페이지 이동·포커스(`rowContainsEntryId`) |
| E14 | 원거래가 삭제(휴지통)되어 조회 불가 | 조회 API 404 → 창은 로컬 정보 + 오류 문구, 되돌리기는 서버가 판단(현행 400 메시지 노출) |
| E15 | 조회 응답 형식이 이상함(테스트 목이 `[]`를 줌 등) | 실패로 처리(로컬 정보 유지 + `role="alert"`), 예외를 던지지 않는다 |
| E16 | 금액 숨김(통합 달력) | 배지·상세의 금액이 `금액 숨김`으로 표시 |
| E17 | 처리 기록 행에 `splitPaymentGroupId`가 있는 이상 데이터 | 처리 기록 규칙이 우선(분할 묶음에 넣지 않음). 원거래가 없으면 연결 처리 행 |

---

## 4. 데이터/로직 설계

### 4.1 `frontend/src/lib/ledgerSheetGroups.js` (확장)

기존 export(`groupLedgerSheetEntries`, `splitDisplayedAmount`, `splitExpectedCount`)의 이름은 유지하고 다음을 추가·변경한다. 파일 맨 위 주석(1~2행)은 새 규칙으로 고친다.

```js
/**
 * @returns {Array<{
 *   key: string,                    // 'entry:{id}' | 'split:{groupId}' | 'adjustment:{id}'
 *   kind: 'entry' | 'split' | 'adjustment',
 *   isSplit: boolean,               // kind === 'split' (기존 코드 호환)
 *   groupId?: string,               // split만
 *   parts: object[],                // 원거래들. adjustment 행은 [처리 기록]
 *   adjustments: object[],          // 이 행 원거래들에 묶인 처리 기록, 시간순
 * }>}
 */
export function groupLedgerSheetEntries(entries = [])

export function rowContainsEntryId(row, id)          // parts 또는 adjustments에 id(문자열 비교)가 있으면 true
export function adjustmentsForEntry(row, entryId)     // row.adjustments 중 adjustmentOriginId === entryId
export function compareLedgerChronologically(a, b)    // entryDate, entryTime||'', id 오름차순 비교 함수

export function adjustmentItemLabel(item)             // '결제 취소' | '결제 취소 되돌림' | '환불 입금' | '환불 되돌림'
export function ledgerEntryStatusLabel(entry)         // 3.3절 단일 행 라벨
export function summarizeLedgerAdjustments(origin, adjustments = [])
// → {
//   status: 'NONE'|'CANCELLED'|'REFUNDED'|'PARTIALLY_REFUNDED'|'REVERTED',
//   label: string,
//   excluded: boolean,                 // Boolean(origin.financiallyExcluded)
//   refundedPrincipal: number,
//   activeRefundReceived: number,      // 활성 REFUND 기록 amount 합(센트 단위 합산 후 /100)
//   items: Array<{ id, type, voided, label, amount, principal, deduction, extra,
//                  entryDate, entryTime, memo, paymentMethodName, voidedAt, sameDateAsOrigin, source }>,
//   hiddenHistory: boolean,            // origin.hasAdjustmentHistory && items.length === 0
// }
export function splitAdjustmentLabel(row)             // 3.3절 분할 배지
```

구현 지침(`groupLedgerSheetEntries`):
1. 1차 순회: `adjustmentType`이 없는 행의 id를 `Set`에 넣는다.
2. 2차 순회(입력 순서): 
   - 처리 기록이고 `adjustmentOriginId`가 Set에 있으면 `pending[originId]`에 모은다(행을 만들지 않음).
   - 처리 기록이고 원거래가 없으면 `{ key: 'adjustment:{id}', kind: 'adjustment', isSplit: false, parts: [entry], adjustments: [] }` 추가.
   - 원거래이고 `splitPaymentGroupId`가 있으면 기존처럼 분할 묶음(`kind: 'split'`, `adjustments: []`).
   - 그 외 `{ key: 'entry:{id}', kind: 'entry', isSplit: false, parts: [entry], adjustments: [] }`. 원거래 id → 행 Map에 저장.
3. 마지막에 `pending`을 원거래가 속한 행의 `adjustments`에 넣고, 각 행 `adjustments`를 `compareLedgerChronologically`로 정렬(새 배열).
4. 숫자 합은 기존 `splitDisplayedAmount`처럼 센트 정수로 더한다.

입출력 예(단위 테스트로 그대로 쓸 것):

```js
const card = { categoryGroupName: '취미', categoryDetailName: '구독', paymentMethodName: '테스트 카드' }
const input = [
  { ...card, id: 4100, entryDate: '2026-11-21', entryTime: '21:31', entryType: 'EXPENSE', amount: 72000, title: '구독 서비스 B : 팀 요금제', foreignCurrencyCode: 'USD', foreignAmount: 50 },
  { ...card, id: 4101, entryDate: '2026-11-21', entryTime: null, entryType: 'EXPENSE', amount: 29000, title: '구독 서비스 A : 정기결제', memo: '구독 서비스 A 이용료', hasAdjustmentHistory: true, refundedPrincipal: 0, financiallyExcluded: false, cancelledAt: null },
  { ...card, id: 4102, entryDate: '2026-11-21', entryTime: null, entryType: 'EXPENSE', amount: 29000, title: '결제 취소: 구독 서비스 A : 정기결제', memo: '정기결제 취소', financiallyExcluded: true, adjustmentType: 'PAYMENT_CANCELLATION', adjustmentOriginId: 4101, adjustmentPrincipal: 29000, adjustmentVoidedAt: '2026-11-21T10:00:00' },
]
const rows = groupLedgerSheetEntries(input)
// rows.map(r => r.key)                      → ['entry:4100', 'entry:4101']
// rows[1].adjustments.map(a => a.id)        → [4102]
// summarizeLedgerAdjustments(rows[1].parts[0], rows[1].adjustments)
//   → { status: 'REVERTED', label: '취소 되돌림', excluded: false, activeRefundReceived: 0,
//       items: [{ id: 4102, type: 'PAYMENT_CANCELLATION', voided: true, label: '결제 취소 되돌림', ... }], hiddenHistory: false }
// ledgerEntryStatusLabel(input[2])          → '결제 취소 기록 · 되돌림'
// rowContainsEntryId(rows[1], 4102)         → true
```

### 4.2 기존 `groupLedgerSheetEntries` 사용처 영향

| 사용처 | 영향 / 조치 |
|---|---|
| `CalendarWorkspace.vue` 1131행 | 그대로 호출. 건수 라벨(1172~1175행) 문구만 변경. 포커스 watch(1140~1146행)를 `rowContainsEntryId`로 |
| `CalendarWorkspace.vue` 1147~1149행 일괄 선택 | `parts` 기준이라 변경 불필요(연결 처리 행은 `adjustmentType`으로 이미 제외) |
| `HouseholdTransactionSheet.vue` 49, 248, 269행 포커스 | `rowContainsEntryId`로 |
| `integratedCalendarCore.js` 42~47행 | 그대로 호출(행에 `adjustments`가 생김). `displayedAmount`는 `parts`만 → 불변. 합계 불변 |
| `integratedCalendarView.js` `buildDayTimeline` | 4.3절 |

### 4.3 통합 달력 반영 (일관되게 반영한다)

같은 그룹 함수를 쓰므로 묶음은 자동 적용된다. 표시만 맞춘다.
- `integratedCalendarView.js`:
  - `ledgerStatus`는 `ledgerSheetGroups.js`의 `ledgerEntryStatusLabel`을 그대로 쓰도록 바꾼다(`export function ledgerStatus(entry) { return ledgerEntryStatusLabel(entry) }` — `DayTimelineRow.vue`가 이 이름을 import하므로 export 이름 유지).
  - 가계부 행(205~227행): `kind === 'adjustment'` 행은 배지 `${ledgerEntryStatusLabel(first)} · 원거래 #${first.adjustmentOriginId}`. 그 외 행은 일반 행이면 `summarizeLedgerAdjustments(first, row.adjustments).label`, 분할이면 `splitAdjustmentLabel(row)`를 배지로, `원거래` 접미사는 붙이지 않는다. `activeRefundReceived > 0`이면 배지 `환불 입금 +${formatCurrency(값)}` 추가. 기존 `합계 제외`/`일부 수단 합계 제외` 배지 로직은 그대로.
  - `amount`에 `struck: row.parts.every(part => part.financiallyExcluded)`를 추가(기존 `amountOf` 결과에 속성 추가).
- `DayTimelineRow.vue`:
  - `item.amount.struck`이면 금액 span에 `data-struck="true"`, scoped CSS `[data-struck='true'] { text-decoration: line-through; }`.
  - `거래 상세` details(36~40행): parts 목록 뒤에 `item.data.adjustments`를 같은 형식으로 나열: `시간 · {adjustmentItemLabel} · {currency(amount)}` + 환불이면 `· 기준 · 차감 · 추가`(기존 문구), 합계 제외면 `· 합계 제외`. `detailSummary()`에 처리 기록이 있으면 ` · 처리 {n}건`을 붙인다(예: `거래 상세 · 처리 2건`).
- 일 합계(`dailyLedger.income/expense`), 월 합계, 백엔드 `transactionCount`는 바꾸지 않는다.

### 4.4 통계 검색 결과

`StatisticsWorkspace.vue`의 `adjustmentLabel`(227~234행)을 `ledgerEntryStatusLabel` import로 교체(템플릿 2571행의 `· 원거래 #id`는 유지). 검색 결과는 서버 페이지 단위라 묶음은 하지 않는다(7절 비범위).

---

## 5. 단계별 작업 지시

공통 검증 명령(모두 `frontend` 디렉터리에서):
- 빌드: `npm run build`
- 단위: `npm run test:unit`
- e2e: dev 서버를 별도 포트로 띄우고(`npx vite --host 127.0.0.1 --port 5181 --strictPort`, 백그라운드), `E2E_BASE_URL=http://127.0.0.1:5181 E2E_START_LOCAL_SERVER=0 npx playwright test <spec...> --config playwright.config.js --reporter=line`. 끝나면 vite를 종료한다.
- 백엔드(`backend` 디렉터리, Gradle wrapper 있음): Git Bash `./gradlew test --tests 'com.playdata.calen.ledger.service.LedgerAdjustmentIntegrationTest'`, PowerShell은 `.\gradlew.bat test --tests "com.playdata.calen.ledger.service.LedgerAdjustmentIntegrationTest"`. 테스트는 H2를 쓴다.

### Step 0. 기준선 확인

- 목적: 이번 변경 전 상태를 기록.
- 실행: `npm run test:unit`, `npm run build`, e2e `e2e/ledger-adjustments.spec.js e2e/ledger-split-sheet-groups.spec.js e2e/ledger-manage-sheet.spec.js e2e/integrated-calendar.spec.js e2e/record-sharing.spec.js`.
- 완료 기준: `record-sharing.spec.js`의 `transaction sheet keeps edit, share and delete reachable beside long memos` 2건(desktop/mobile)만 실패하고 원인이 `삭제` 버튼 미존재임을 확인(2.4절). 다른 실패가 있으면 목록을 남기고 이번 작업과 분리해서 보고한다.

### Step 1. 순수 함수 + 단위 테스트

- 목적: 묶음·상태 요약 규칙을 화면과 분리해 확정.
- 파일: `frontend/src/lib/ledgerSheetGroups.js`, `frontend/tests/unit/ledgerSheetGroups.test.js`, `frontend/tests/unit/integratedCalendarCore.test.js`.
- 변경: 4.1절 함수 구현. 기존 단위 테스트 갱신:
  - `ledgerSheetGroups.test.js` 첫 테스트: id 7(원거래 id 없는 `REFUND`)은 이제 연결 처리 행 → 기대 key `['split:a', 'entry:9', 'adjustment:7', 'split:b']`, `rows[2].kind === 'adjustment'`, `rows[2].isSplit === false`.
  - `integratedCalendarCore.test.js` 17~34행: 환불 id 5(원거래 id 1 = 분할 수단)는 분할 행에 묶임 → `day.rows.length === 2`, `day.rows[0].adjustments[0].adjustmentDeduction === 5000`, `day.rows[0].displayedAmount === 45000` 유지, 월 합계 기대값 유지.
  - 6.1절 신규 테스트 추가.
- 완료 기준: `npm run test:unit` 통과.

### Step 2. 라벨 단일화 (통계·통합 달력)

- 파일: `frontend/src/lib/integratedCalendarView.js`(23~30행, 205~227행), `frontend/src/components/integrated-calendar/DayTimelineRow.vue`(15, 30, 38~39행), `frontend/src/components/StatisticsWorkspace.vue`(227~234행).
- 변경: 4.3, 4.4절. `integratedCalendarView.js`는 `import { ledgerEntryStatusLabel, summarizeLedgerAdjustments, splitAdjustmentLabel, adjustmentItemLabel } from './ledgerSheetGroups.js'`(확장자 필수). 순환 import가 생기지 않는지 확인(`ledgerSheetGroups.js`는 다른 lib를 import하지 않는다).
- 단위 테스트: `tests/unit/integratedCalendarView.test.js`에 6.1절 타임라인 케이스 추가.
- 완료 기준: `npm run test:unit`, `npm run build` 통과.

### Step 3. 백엔드 읽기 전용 조회 API

- 목적: 연결 처리 행(E5, E6)과 다른 날짜 이력(E4)을 관리 창 하나에서 보여 주려면 "아무 구성원 id → 원거래 + 전체 처리 기록"이 필요하다. 현재 API로는 처리 기록 id로 원거래를 얻을 수 없고(1.2절), 원거래 단건 조회 API도 없다. 스키마 변경 없이 조회만 추가한다.
- 파일:
  - 새 DTO `backend/src/main/java/com/playdata/calen/ledger/dto/LedgerAdjustmentGroupResponse.java`: `public record LedgerAdjustmentGroupResponse(LedgerEntryResponse origin, List<LedgerEntryResponse> adjustments) {}`
  - `ledger/service/LedgerEntryService.java`: `getAdjustmentHistory`(656~663행) 아래에 추가(클래스가 이미 `@Transactional(readOnly = true)`):
    ```java
    public LedgerAdjustmentGroupResponse getAdjustmentGroup(Long userId, Long entryId) {
        appUserService.getRequiredUser(userId);
        LedgerEntry entry = ledgerEntryRepository.findByIdAndOwnerIdAndDeletedAtIsNull(entryId, userId)
                .orElseThrow(() -> new NotFoundException("거래를 찾을 수 없습니다."));
        LedgerEntry origin = entry.getAdjustmentType() == null ? entry
                : ledgerEntryRepository.findByIdAndOwnerIdAndDeletedAtIsNull(entry.getAdjustmentOriginId(), userId)
                    .orElseThrow(() -> new NotFoundException("원거래를 찾을 수 없습니다."));
        var items = ledgerEntryRepository
                .findAllByOwnerIdAndAdjustmentOriginIdAndDeletedAtIsNullOrderByEntryDateAscIdAsc(userId, origin.getId())
                .stream().map(this::toResponse).toList();
        return new LedgerAdjustmentGroupResponse(toResponse(origin), items);
    }
    ```
  - `ledger/web/LedgerEntryController.java`: `getAdjustments`(242~247행) 아래에 `@GetMapping("/{id}/adjustment-group")` 핸들러 추가, `ledgerEntryService.getAdjustmentGroup(currentUser.userId(), id)` 반환.
  - 기존 `GET /{id}/adjustments`, 집계 SQL, 엔티티, 마이그레이션은 건드리지 않는다.
- 테스트: `backend/src/test/java/com/playdata/calen/ledger/service/LedgerAdjustmentIntegrationTest.java`에 6.3절 테스트 추가.
- 완료 기준: 위 백엔드 테스트 명령 통과 + `LedgerSplitPaymentIntegrationTest`, `com.playdata.calen.calendar.CalendarEventIntegrationTest`도 통과(`--tests` 여러 개 지정 가능).

### Step 4. 프론트 API 함수

- 파일: `frontend/src/lib/api.js` — **기존 미커밋 변경을 보존한 채** `fetchLedgerAdjustments`(767~769행) 바로 아래에 1개만 추가:
  ```js
  export function fetchLedgerAdjustmentGroup(entryId) {
    return request(`/entries/${encodeURIComponent(entryId)}/adjustment-group`)
  }
  ```
- 완료 기준: `git diff frontend/src/lib/api.js`에 기존 `fetchAdminWithdrawalEmbeddingStatus` 추가분과 이 함수만 보인다.

### Step 5. 거래 시트 행 묶음 표시 + 건수

- 파일: `frontend/src/components/HouseholdTransactionSheet.vue`, `frontend/src/components/CalendarWorkspace.vue`.
- `CalendarWorkspace.vue`:
  - 1172~1175행 `세부 거래` → `세부 기록`.
  - 1140~1146행 포커스 watch: `row.parts.some(...)` → `rowContainsEntryId(row, id)`(import 추가).
- `HouseholdTransactionSheet.vue`:
  - import에 `rowContainsEntryId, summarizeLedgerAdjustments, ledgerEntryStatusLabel, splitAdjustmentLabel, adjustmentsForEntry, adjustmentItemLabel` 추가. 로컬 `statusLabel`(150~159행)은 `ledgerEntryStatusLabel`로 대체(분할 상세 357행, 관리 요약 389행 사용처 포함).
  - 49행 포커스 watch, 248행·269행 `data-calendar-focused-entry`를 `rowContainsEntryId(row, focusEntryId)` 기준으로.
  - 분할 행 256행: `처리 이력 있음` 고정 배지 → `splitAdjustmentLabel(row)`(값이 있을 때만). `splitHasAdjustments`(114행)는 삭제 또는 이 함수로 대체.
  - 일반 행(269~320행): `row.kind === 'adjustment'`와 `'entry'`를 구분해 3.4~3.5절대로 제목 배지·상세 줄·금액 칸(취소선 + 보조 줄)·작업 버튼을 렌더링. `td` 개수·순서·클래스는 그대로(0절 6번). 행별 요약은 템플릿에서 반복 계산하지 말고 `computed`로 `rowViews = props.entries.map(row => ({ row, summary: ... }))`처럼 한 번 만든다(또는 작은 함수 캐시).
  - 금액 취소선 scoped CSS 예: `.sheet-table__amount-main.is-struck, .sheet-table__amount-sub.is-struck { text-decoration: line-through; }`. 다크 테마에서 색이 덮이지 않는지 계산된 스타일로 확인하고, 덮이면 `:global(:root[data-theme='toss']) .household-sheet-panel ...` 접두로 특이도를 올린다.
  - 처리 이력 있는 원거래/연결 처리 행 체크박스는 비활성 유지 + `aria-label`.
- 완료 기준: `npm run build` 통과. 6.2절의 사용자 사례 e2e에서 행 2개, `2건 · 세부 기록 3건`, `취소 되돌림` 확인.

### Step 6. 통합 관리 창

- 파일: `frontend/src/components/HouseholdTransactionSheet.vue`(관리 오버레이 377~413행과 스크립트 44~148행).
- 상태:
  - `managedEntry` → `managedRow`(행 객체)로 바꾸고, `managedOrigin` computed = 받아온 그룹의 `origin` ?? (`kind !== 'adjustment'`이면 `row.parts[0]`, 아니면 `null`).
  - `manageGroup = reactive({ loading: false, error: '', origin: null, adjustments: null, seq: 0 })`.
  - `openManager(row, event)`: 행 단위로 연다. 분할 상세 창의 수단 버튼(370행)은 `openManager({ key: 'entry:' + part.id, kind: 'entry', isSplit: false, parts: [part], adjustments: adjustmentsForEntry(activeSplit, part.id) }, $event)`.
  - 불러오기 조건: `row.kind === 'adjustment' || row.adjustments.length > 0 || row.parts[0]?.hasAdjustmentHistory`. 대상 id는 `row.parts[0].id`(연결 처리 행이면 처리 기록 id, 서버가 원거래로 해석). 요청마다 `seq`를 올리고 응답 시 `seq`가 같고 창이 열려 있을 때만 반영(빠르게 다른 행을 연 경우 무시).
  - 응답 검증: `json && typeof json === 'object' && !Array.isArray(json) && json.origin && Array.isArray(json.adjustments)`. 아니면 실패(E15).
  - 표시 이력 = 받아온 `adjustments` ?? `row.adjustments`(연결 처리 행이면 `row.parts`), `summarizeLedgerAdjustments(origin ?? {}, 이력)`로 요약.
  - `closeManager`는 `manageGroup`을 초기화(`seq`는 증가시켜 늦은 응답을 버림).
- 템플릿: 3.6절. 제목 `거래 관리`, 닫기 버튼 `ref="managerCloseButton"` + `data-modal-close` 유지. 기존 `처리 내역`, 단독 `되돌리기` 버튼 삭제. 이력 항목의 `이 처리 되돌리기` → `managerAction('revert-adjustment', item)`(item은 원본 처리 기록 객체 — `undoLedgerAdjustment`가 `id`, `adjustmentType`을 쓴다). 환불·결제 취소 → `managerAction('adjust-entry', { entry: managedOrigin, mode })`. 수정·삭제 → 기존과 동일.
- `show-adjustments` emit은 시트에서 더 이상 보내지 않는다. `defineEmits` 목록과 상위 연결(CalendarWorkspace 5344/5395행, HouseholdWorkspace 5903행, `LedgerAdjustmentHistoryDialog.vue`)은 **그대로 둔다**(제거는 비범위, 동작에 영향 없음).
- 스타일: 이력 목록은 `.ledger-manage-history` 등 새 클래스로 scoped 정의, 테두리 `1px solid var(--border)`, 이력 버튼은 전역 `.button .button--ghost` 사용, 최소 높이 2.75rem. 창 폭 `min(100%, 36rem)` → `min(100%, 40rem)`까지 허용. 모바일 390px에서 가로 넘침이 없어야 한다.
- 완료 기준: `npm run build`; 6.2절 e2e 통과; Esc로 관리 창만 닫히고 시트가 남으며 트리거 버튼에 포커스가 돌아간다(기존 `ledger-manage-sheet.spec.js` 검증 유지).

### Step 7. 기존 e2e 갱신 + 신규 e2e

- 파일: `frontend/e2e/ledger-adjustments.spec.js`, `ledger-manage-sheet.spec.js`, `ledger-split-sheet-groups.spec.js`, `integrated-calendar.spec.js`, `record-sharing.spec.js`, 신규 `frontend/e2e/ledger-adjustment-grouping.spec.js`.
- 변경 내용은 6.2절. 
- 완료 기준: 위 6개 spec이 desktop·mobile 모두 통과(`record-sharing.spec.js`의 기준선 실패 2건 포함 해소).

### Step 8. 시각 확인

- 6.2절 신규 spec 안에서 `testInfo.outputPath(...)`로 시트·관리 창을 데스크톱 1440x900 / 모바일 390x844, 라이트(기본) / 다크(`localStorage['calen-theme-mode'] = 'toss'`를 `page.addInitScript`로 설정) 4조합 캡처하고 직접 열어 확인한다.
- 확인 항목: 취소선이 다크에서도 보임, 배지 줄바꿈이 버튼과 겹치지 않음, 모바일 카드 라벨(시간/구분/…)이 기존과 같은 위치, 관리 창 가로 넘침 없음, 버튼 대비.
- 완료 기준: 캡처 확인 후 최종 `npm run build`, `npm run test:unit`, 관련 e2e 전체 재실행 통과.

---

## 6. 테스트 계획

### 6.1 단위 테스트 (`npm run test:unit`)

`tests/unit/ledgerSheetGroups.test.js`에 추가:
1. 사용자 사례(4.1절 입력 그대로): 행 2개, `entry:4101`에 4102 묶임, 라벨 `취소 되돌림`, `status === 'REVERTED'`, `excluded === false`.
2. 활성 결제 취소: 원거래 `cancelledAt`·`financiallyExcluded: true` + 활성 취소 기록 → `결제 취소됨`, `excluded === true`.
3. 되돌린 취소 + 재취소(두 번째 기록 id가 더 크고 같은 날짜, 입력은 역순) → 라벨 `결제 취소됨`, `items.map(i => i.label)` = `['결제 취소 되돌림', '결제 취소']`(시간순).
4. 부분 환불: `amount: 10000, refundedPrincipal: 3000`, 활성 환불 `amount: '2500.10'` + 되돌린 환불 `amount: 1000` → `부분 환불`, `activeRefundReceived === 2500.1`.
5. `refundedPrincipal === amount` → `환불 완료`.
6. 연결 처리 행: 원거래 없는 환불 → `kind: 'adjustment'`, `parts[0]`이 그 기록, 입력 순서 위치 유지.
7. DESC 입력(처리 기록이 원거래보다 앞) → 원거래 위치에 묶임, 행 수 감소.
8. 분할: 수단 id 1에 대한 환불 → `split:a` 행의 `adjustments`에 있음, `parts`·`splitDisplayedAmount` 불변, `adjustmentsForEntry(row, 1)` 길이 1, `splitAdjustmentLabel` = `1개 수단 부분 환불`(수단 `refundedPrincipal` 설정 시).
9. `hasAdjustmentHistory`만 있고 묶인 기록 없음 → `처리 되돌림`, `hiddenHistory === true`.
10. 섞인 되돌림(되돌린 취소 + 되돌린 환불) → `처리 되돌림`.
11. `ledgerEntryStatusLabel`: 되돌린 환불 → `환불 입금 · 되돌림`, 활성 취소 기록 → `결제 취소 기록`, 일반 거래 → `''`.
12. 입력 배열·원소 불변(`structuredClone`으로 비교).
13. 기존 페이지 경계 테스트 유지.

`tests/unit/integratedCalendarCore.test.js`: Step 1 갱신.

`tests/unit/integratedCalendarView.test.js`에 추가:
- 취소된 원거래(id 4) + 활성 환불(id 5, 25000) + 되돌린 환불(id 6) 같은 날 → `buildDayTimeline` 가계부 행 1개, 배지에 `결제 취소됨`, `합계 제외`, `환불 입금 +₩25,000` 포함, `amount.struck === true`, `data.adjustments.length === 2`.
- 같은 입력에 `formatCurrency: () => '금액 숨김'` → 배지 `환불 입금 +금액 숨김`.
- 연결 처리 행(원거래 없음) → 배지 `환불 입금 · 원거래 #4`.

### 6.2 e2e

공통: 각 spec은 `mockPersonalization`(`e2e/fixtures/calendarPersonalization.js`)을 쓰고, 이 픽스처는 모르는 `/api/**`에 `[]`를 돌려준다. 관리 창이 그룹 API를 부르는 테스트는 `page.route('**/api/entries/{id}/adjustment-group', ...)`를 **`mockPersonalization` 호출 뒤**에 등록하고(나중에 등록한 route가 우선), 필요하면 `page.reload()`한다. 시계는 `page.clock.setFixedTime(new Date('2026-10-22T12:00:00'))`(픽스처 날짜와 일치).

**신규 `e2e/ledger-adjustment-grouping.spec.js`**
1. `voided cancellation stays in one row with one management window` — 목 데이터(사용자 사례를 픽스처 날짜로 옮김):
   ```js
   const card = { categoryGroupId: 101, categoryGroupName: '취미', categoryDetailId: 102, categoryDetailName: '구독', paymentMethodId: 103, paymentMethodName: '테스트 카드' }
   const entries = [
     { ...card, id: 4100, entryDate: '2026-10-22', entryTime: '21:31', entryType: 'EXPENSE', amount: 72000, title: '구독 서비스 B : 팀 요금제', foreignCurrencyCode: 'USD', foreignAmount: 50 },
     { ...card, id: 4101, entryDate: '2026-10-22', entryTime: null, entryType: 'EXPENSE', amount: 29000, title: '구독 서비스 A : 정기결제', memo: '구독 서비스 A 이용료', hasAdjustmentHistory: true, refundedPrincipal: 0, financiallyExcluded: false, cancelledAt: null },
     { ...card, id: 4102, entryDate: '2026-10-22', entryTime: null, entryType: 'EXPENSE', amount: 29000, title: '결제 취소: 구독 서비스 A : 정기결제', memo: '정기결제 취소', financiallyExcluded: true, adjustmentType: 'PAYMENT_CANCELLATION', adjustmentOriginId: 4101, adjustmentPrincipal: 29000, adjustmentVoidedAt: '2026-10-22T10:00:00' },
   ]
   // GET /api/entries/4101/adjustment-group 와 /api/entries/4102/adjustment-group → { origin: entries[1], adjustments: [entries[2]] }
   ```
   기대: 시트(`dialog '거래 시트'`)에 `toContainText('2건 · 세부 기록 3건')`; `구독 서비스 A` 행이 1개(`getByRole('row').filter({ hasText: '구독 서비스 A' })` count 1)이고 `결제 취소:`로 시작하는 별도 행 없음; 그 행에 `취소 되돌림`, `관리`, `공유` 버튼; `처리 이력 있음`·`처리 되돌림` 텍스트 없음. `관리` 클릭 → `dialog '거래 관리'`에 `처리 이력` 제목, `결제 취소 되돌림`, `시간 미지정`, `결제 취소` 버튼(`/^결제 취소/`)과 `환불` 버튼 표시, `이 처리 되돌리기` 버튼 없음(이미 되돌림), `처리 내역` 버튼 없음. Esc → 관리 창 닫힘, 시트 유지. 4조합 캡처(Step 8).
2. `active cancellation strikes the amount and offers reverting inside the same window` — 원거래 `cancelledAt`, `financiallyExcluded: true` + 활성 취소 기록. 기대: 행 1개, `결제 취소됨`, `결제 취소 · 합계 제외`, 금액 요소의 계산된 `text-decoration-line`이 `line-through`, `공유` 없음. 관리 창에서 `이 처리 되돌리기` 클릭 → `page.once('dialog', d => d.accept())` → `POST /api/entries/{취소기록id}/adjustments/revert`가 호출됨(route로 기록).
3. `an adjustment on another date opens the original and full history` — 원거래는 `2026-09-10`(달력 로드 범위 밖), 10/22에는 환불 기록만(`adjustmentOriginId: 7`). 그룹 API는 원거래(`entryDate: '2026-09-10'`) + 환불 기록. 기대: 10/22 시트에 연결 처리 행 1개, `환불 입금`, `원거래 #7`, 버튼 `관리`만. 관리 창에 `2026-09-10`, `환불 입금`, `이 처리 되돌리기`, 원거래가 환불 가능하면 `환불` 버튼.
4. `type filter keeps the original row status` — 원거래(지출, `refundedPrincipal: 3000`) + 같은 날 환불(수입). `거래 구분`=`EXPENSE` → 원거래 행에 `부분 환불`, 환불 행 없음; `INCOME` → 연결 처리 행 1개.

**`e2e/ledger-adjustments.spec.js`** 갱신
- 18~38행 목에 `GET **/api/entries/7/adjustment-group`(id 8, 9로 들어와도 원거래 7 기준) 추가: `{ origin: original, adjustments: records.filter(r => r.adjustmentOriginId === 7) }`.
- 환불 저장 뒤(77~79행): `originalRow`에 `환불 완료`, `환불 입금 +₩93,000`; 시트에 별도 `환불:` 행 없음; `1건 · 세부 기록 2건`.
- 84~91행: `관리` → 관리 창 `처리 이력`에 `수령 ₩93,000` → `이 처리 되돌리기`(confirm accept) → 관리 창 닫힘 → 다시 `관리` → `환불 되돌림`이 이력에 보임. (`취소·환불 처리 내역` 다이얼로그를 기대하던 부분 삭제.)
- 이후 결제 취소 흐름(92~97행) 유지: `결제 취소됨`.

**`e2e/ledger-manage-sheet.spec.js`** 갱신(88~94행)
- `부분 환불 지출`(id 44) 그룹 API 목 추가: `{ origin: entries[3], adjustments: [] }`.
- `처리 내역` 버튼 기대(91행) → 관리 창에 `처리 이력` 제목 + `다른 날짜에 처리 기록이 있습니다` 또는 빈 이력 안내 확인으로 교체. 나머지 기대(환불 가능, 수정·삭제·결제 취소 없음, 안내 문구) 유지.

**`e2e/ledger-split-sheet-groups.spec.js`** 갱신
- 49행 `3건 · 세부 거래 5건` → `3건 · 세부 기록 5건`.
- 52행 `처리 이력 있음` → `1개 수단 결제 취소됨`.
- 88행 분할 상세의 `결제 취소됨` 유지(수단 단건 라벨).

**`e2e/integrated-calendar.spec.js`** 갱신(259~272행)
- `취소된 구매` 행 1개에 `결제 취소됨`, `합계 제외`; 별도 `환불 입금`·`되돌린 환불` 행 없음(`entries(page)` 개수 1). `거래 상세`를 펼치면 `환불 입금`, `₩25,000`, `기준 ₩30,000`, `차감 ₩5,000`, `환불 되돌림` 포함.

**`e2e/record-sharing.spec.js`** 갱신(347~377행, 기준선 실패 해소)
- 행 버튼 루프 `['수정', '공유', '삭제']` → `['수정', '공유']`. 삭제 접근성은 `수정` 클릭 → `dialog '거래 관리'`에 `/^삭제/` 버튼이 보이고 화면 안에 있는지 확인한 뒤 `닫기`로 닫는 검증으로 대체. 나머지(공유 다이얼로그) 유지.

### 6.3 백엔드 테스트

`LedgerAdjustmentIntegrationTest`에 추가(기존 헬퍼 `fixture()`, `purchase(f)`, `cancel(UUID)`, `refund(...)`, `mvc` 사용):

```java
@Test void adjustmentGroupResolvesFromOriginOrAdjustmentAndRequiresTheOwner() throws Exception {
    Fixture f = fixture(); Fixture other = fixture(); Long original = purchase(f);
    Long first = adjustments.create(f.ownerId(), original, cancel(UUID.randomUUID()));
    adjustments.revert(f.ownerId(), first);
    Long second = adjustments.create(f.ownerId(), original, cancel(UUID.randomUUID()));
    var byOrigin = entryService.getAdjustmentGroup(f.ownerId(), original);
    assertThat(byOrigin.origin().id()).isEqualTo(original);
    assertThat(byOrigin.origin().cancelledAt()).isNotNull();
    assertThat(byOrigin.adjustments()).extracting(LedgerEntryResponse::id).containsExactly(first, second);
    assertThat(byOrigin.adjustments().get(0).adjustmentVoidedAt()).isNotNull();
    assertThat(entryService.getAdjustmentGroup(f.ownerId(), second).origin().id()).isEqualTo(original);
    assertThat(entryService.getAdjustmentGroup(f.ownerId(), purchase(f)).adjustments()).isEmpty();
    mvc.perform(get("/api/entries/{id}/adjustment-group", second).with(user(AppUserPrincipal.from(f.owner()))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.origin.id").value(original))
            .andExpect(jsonPath("$.adjustments.length()").value(2));
    mvc.perform(get("/api/entries/{id}/adjustment-group", second).with(user(AppUserPrincipal.from(other.owner()))))
            .andExpect(status().isNotFound());
}
```
(`LedgerEntryResponse` import 필요. 같은 날짜 두 기록의 정렬은 저장소 메서드의 `EntryDateAscIdAsc`로 id 순이 보장된다.)

회귀: `LedgerSplitPaymentIntegrationTest`, `CalendarEventIntegrationTest`(167행 `transactionCount` 기대 2 — 이번에 SQL을 바꾸지 않으므로 그대로 통과해야 한다).

---

## 7. 리스크와 비범위

### 리스크
- R1. **행 객체 형태 변경**(`kind`, `adjustments` 추가, 처리 기록이 `parts`에서 빠짐): `row.parts`로 id를 찾는 코드가 더 있는지 `grep -rn "\.parts" frontend/src`로 전부 확인하고, 포커스·선택 관련은 `rowContainsEntryId`로 바꾼다.
- R2. **다른 날짜 기록**: 그날 시트만 보면 처리 기록 금액이 보이지 않을 수 있다. 원거래 상태 라벨과 `다른 날짜에 처리 기록이 있습니다` 문구, 관리 창의 서버 이력으로 보완한다. 합계는 원본 행 기준이라 틀어지지 않는다.
- R3. **같은 날 환불 입금이 원거래 행에 묶이면** 그날 수입 합계에 포함된 금액이 별도 행으로 보이지 않는다 → 금액 칸 보조 줄 `환불 입금 +₩x`를 반드시 표시한다.
- R4. 그룹 API 실패·지연 시 창이 비지 않도록 로컬 정보 우선 렌더링 + `seq`로 늦은 응답 무시.
- R5. 다크 테마 전역 CSS가 취소선 색·배지 테두리를 덮을 수 있다 → Step 8 계산된 스타일 확인.
- R6. 백엔드 `transactionCount`(처리 기록을 1건씩 셈)와 시트 건수가 달라진다. 현재 화면에 표시되지 않으므로 사용자 영향은 없다. 나중에 표시한다면 별도 작업으로 맞춘다.
- R7. 되돌리기 후 관리 창이 닫힌다(현행 패턴). 창 안에서 즉시 갱신하는 동작은 이번 범위가 아니다.

### 비범위
- N1. 모든 처리를 되돌린 원거래의 일반 수정·삭제 허용(`hasAdjustmentHistory` 해제, `requireOrdinaryEditableEntry` 완화) — 백엔드 정책 변경이므로 별도 결정 필요(P6).
- N2. 통계 검색 결과·CSV 내보내기에서의 묶음 표시(서버 페이지 단위, 4.4절은 라벨만 맞춘다).
- N3. 백엔드 `CalendarSummaryService` 건수 SQL 변경.
- N4. 사용하지 않게 되는 `show-adjustments` 이벤트 연결과 `LedgerAdjustmentHistoryDialog.vue` 제거.
- N5. 시트에서 원거래 날짜로 바로 이동하는 버튼(새 emit·날짜 이동 계약이 필요).
- N6. DB 스키마·마이그레이션, 처리 기록 생성 규칙(별도 행 생성 방식) 변경.
- N7. 사용자 데이터 정정: 사용자 사례의 구독 서비스 A 결제는 현재 **취소가 되돌려진 상태로 지출 합계에 포함**되어 있다. 실제로 취소하려면 새 관리 창에서 결제 취소를 다시 기록해야 한다(코드 변경으로 자동 처리하지 않는다).
