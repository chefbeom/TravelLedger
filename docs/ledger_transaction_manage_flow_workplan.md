# 가계부 '거래 관리' 흐름 재설계: 상태별 행동 · 수정/삭제 정책 · 통합 관리 창 설계 및 작업 지시서

> 대상 독자: 이 문서만 보고 구현할 에이전트. 행번호는 작성 시점(main `10eb4fef` + 작업 디렉터리 미커밋 변경) 기준이다. **구현 전에 반드시 해당 파일을 다시 열어 위치를 확인한다.**
> 선행 문서: `docs/ledger_adjustment_grouping_workplan.md`(원거래 + 처리 기록 묶음, 통합 관리 창 1차). 이번 문서는 그 문서의 비범위 N1(P6: 되돌린 뒤에도 수정·삭제 영구 차단)을 해결하고, 관리 창을 "상태별 주 행동" 중심으로 다시 짠다.

## 0. 꼭 지킬 규칙 (먼저 읽을 것)

1. **합계·데이터 의미는 바꾸지 않는다.** `financiallyExcluded` 규칙, 처리 기록(별도 행) 생성 방식, `cancelledAt`/`refundedPrincipal`/`hasAdjustmentHistory`/`adjustmentVoidedAt`의 의미, 집계 SQL, 일·월 합계 계산은 그대로다.
2. **DB 스키마 변경·마이그레이션 금지.** 백엔드 변경은 서비스 검증 로직, 저장소의 파생 쿼리 메서드(메서드 이름 기반, 컬럼 추가 없음), DTO 수준만 허용한다.
3. **새 npm/Gradle 의존성 금지.**
4. 작업 디렉터리의 무관한 미커밋 변경(`frontend/src/lib/api.js`의 `fetchAdminWithdrawalEmbeddingStatus` 추가분, `frontend/src/style.css`, 백엔드 AI·탈퇴 관련 파일, `docker-compose*.yml`, `.env*.example`, `docs/` 일부, 각종 로그·산출물)은 **되돌리거나 정리하지 않는다.** 이번 작업은 `api.js`를 **수정하지 않는다**(필요한 함수 `createLedgerAdjustment`, `revertLedgerAdjustment`, `updateEntry`, `deleteEntry`, `fetchLedgerAdjustmentGroup`가 이미 있다). 커밋하지 않는다(요청받은 경우에만, 작업 파일만 스테이징).
5. `frontend/src/style.css` 수정 금지. 스타일은 컴포넌트 scoped에 둔다. 전역 `.button`이 `min-height:46px; padding:0 18px; align-items:center; justify-content:center`를 주고, 다크 테마(`:root[data-theme="toss"] .button`, 특이도 0,3,0)가 배경·테두리를 덮는다. 일부 전역 규칙은 `!important`다. **버튼 상태를 배경색에 의존하지 말고**, 필요하면 `.ledger-manage-dialog .ledger-manage-actions .button`처럼 루트 클래스 접두로 특이도를 0,4,0 이상으로 올린다(참고: `docs/integrated_calendar_ux_workplan.md` 3.10절, `docs/record_map_ux_workplan.md` 3.6절). `border-radius`는 전역에서 0으로 강제된다.
6. App.vue(1035~1059행 `handleGlobalModalEscape`)는 **캡처 단계**에서 Esc를 가로채 z-index가 가장 높은 `[role="dialog"][aria-modal="true"]` 안의 **첫 번째 `[data-modal-close]`**(없으면 이름이 `닫기`/`취소`인 버튼)를 클릭하고 이벤트를 멈춘다. 그래서 컴포넌트의 `@keydown.esc`는 실행되지 않는다. 모바일에서는 aria-modal 다이얼로그가 있으면 배경 스크롤을 잠근다. → 관리 창의 단계 전환에서 Esc 동작은 `data-modal-close`의 **위치를 옮기는 방식**으로 구현한다(4.5절).
7. node:test로 실행되는 `frontend/src/lib/*.js`끼리의 import는 **반드시 `.js` 확장자**를 붙인다. Vue 컴포넌트에서는 확장자 없이 import해도 된다.
8. 모바일(`:root[data-layout-mode='mobile']`)에서 거래 시트 표는 카드형이며 `td` 순서·클래스에 라벨이 붙는다. **행의 `td` 개수·순서·클래스를 바꾸지 않는다.**
9. 문서·테스트·목 데이터에는 실제 거래명·카드명·금액·거래 번호·로컬 경로를 쓰지 않는다. 예시는 `구독 서비스 A`, `테스트 카드`, `검증용 결제`처럼 익명화한다.

---

## 1. 현행 구조와 문제점

### 1.1 관련 코드 지도

| 영역 | 파일 · 행 | 내용 |
|---|---|---|
| 처리 기록 생성 | `backend/.../ledger/service/LedgerAdjustmentService.java` `create` 36~136 | 원거래는 두고 별도 행 생성. 결제 취소: 원거래 `financiallyExcluded=true`, `cancelledAt`(92~93), 취소 행은 원거래 금액·외화·카테고리·결제수단을 **복사**(94~106). 환불: 원거래 `refundedPrincipal += principal`(124), 환불 행은 수입(합계 포함). 공통 `hasAdjustmentHistory=true`(134). 거부 조건: 지출 아님·처리 기록·합계 제외·이미 취소(67~70), 처리일 < 원거래 날짜(71~73), 환불 있으면 취소 불가(88), 여행 기록 연결이면 취소 불가(89~91) |
| 처리 되돌림 | 같은 파일 `revert` 139~166 | 기록은 지우지 않는다. 취소 되돌림: 원거래 `financiallyExcluded=false`, `cancelledAt=null`. 환불 되돌림: `refundedPrincipal -= principal`. 기록 행은 `financiallyExcluded=true`, `adjustmentVoidedAt=now`. 이미 되돌린 기록이면 아무것도 안 함(148, 재시도 안전) |
| 동시성 | 두 메서드 모두 `users.getRequiredUserForWrite`(=`AppUserRepository.findActiveForPreferenceUpdate`, `PESSIMISTIC_WRITE`) + 원거래 `lockActiveOwned` | **소유자 행 잠금으로 같은 사용자의 모든 가계부 쓰기가 직렬화**된다. 일반 `update`/`delete`/`bulkUpdate`도 같은 `getRequiredUserForWrite`를 먼저 호출한다(LedgerEntryService 846, 866, 951행). `LedgerEntry`에는 `@Version`이 없다 |
| 수정·삭제 차단 | `backend/.../ledger/service/LedgerEntryService.java` `requireOrdinaryEditableEntry` 1523~1527 | `adjustmentType != null \|\| hasAdjustmentHistory`면 400 "취소·환불 이력이 있는 거래는 기록 보존을 위해 일반 수정·삭제할 수 없습니다…" |
| 차단 호출처 | 같은 파일 `validateSplitEditable` 791, `update` 849, `bulkUpdate` 881, `delete` 954, `applySnapshot`(변경 이력 복구) 1108 | 5곳 모두 같은 함수 |
| 휴지통 | 같은 파일 `getDeletedEntries` 143~162, `restore` 963~969, `emptyTrash` 972~975 | 복구는 한 건만 `deletedAt=null` |
| 그룹 조회 | 같은 파일 `getAdjustmentGroup` 666~678, 컨트롤러 `LedgerEntryController` 250행 `GET /api/entries/{id}/adjustment-group` | 아무 구성원 id → `{ origin, adjustments }`(소유자 검증, 처리 기록은 `entryDate, id` 오름차순) |
| 금액 계산 | `ledger/application/service/PrepareLedgerEntryService.java` `exchange` 48~55, `ledger/domain/policy/LedgerMoneyPolicy.calculate` 23~28 | 외화면 `krwAmount = foreignAmount × rate`(정수 반올림)로 **원화 금액을 다시 계산**하고, 요청에 환율이 있으면 `exchangeRateDate=entryDate`, `provider="Provided"`로 바뀐다 |
| 시트 관리 창 | `frontend/src/components/HouseholdTransactionSheet.vue` 스크립트 148~244, 템플릿 461~523 | `managedRow`/`managedSplit`, `manageGroup`(그룹 API), 요약 → 처리 이력 → 버튼 순서 |
| 관리 창 버튼 조건 | 같은 파일 514~520 | `거래 내용 수정`/`삭제`: `!hasAdjustmentHistory && !adjustmentType`. `환불`: `canRefund`(237~240). `결제 취소`: `canCancel`(241~244). 하단 안내 520 |
| 이력 항목 되돌리기 | 같은 파일 503 | 활성 기록마다 작은 `이 처리 되돌리기`(→ `revert-adjustment` emit → HouseholdWorkspace `undoLedgerAdjustment`가 `window.confirm`) |
| 시트 행 버튼 | 같은 파일 397(일반 행 `수정`/`관리`), 337(분할 행 `수정`), 454(분할 상세 `수정·처리`/`관리`) | 진입 버튼 이름이 상태마다 다르다 |
| 일괄 선택 | `CalendarWorkspace.vue` 1146~1149, 시트 83·349~350, `StatisticsWorkspace.vue` 225~227·2505 | `!hasAdjustmentHistory && !adjustmentType` |
| 통계 검색 | `StatisticsWorkspace.vue` 2590(인라인 `수정`), 2591(`이동`), 2593(`삭제`) | 같은 차단 조건. `이동`은 `HouseholdWorkspace.moveEntryFromSearch`(5184~5190)로 **편집 폼을 바로 연다**(이력 있는 거래도) |
| 환불/취소 입력 | `LedgerAdjustmentDialog.vue`(113행), HouseholdWorkspace 124~146(`openLedgerAdjustment`/`submitLedgerAdjustment`), 5708~5710 | 관리 창을 **닫고** 별도 모달을 연다 |
| 일반 수정 | 관리 창 `거래 내용 수정` → `edit-entry` → `CalendarWorkspace.handleSheetEditEntry`(4434~4439, 시트 모달 닫고 스크롤) → `HouseholdWorkspace.handleSheetEdit`(3144~3168) → `fillEntryForm`(3117~3142) | 메인 편집 폼(외화·분할·할부·여행 연결 포함) |
| 삭제 | 관리 창 `삭제` → `delete-entry` → `HouseholdWorkspace.removeEntry`(5292~) | `window.confirm` 후 `deleteEntry` |
| 통합 달력 | `frontend/src/lib/integratedCalendarView.js` 201~229, `components/integrated-calendar/DayTimelineRow.vue` | 라벨만 표시하고 `가계부 원본 보기`로 이동. 수정·삭제 없음 |

### 1.2 화면 A 재현: "결제 취소를 되돌린 거래"에 수정·삭제가 없다

작업자 로컬에서 기존 e2e 목을 복사한 임시 spec으로 데스크톱 1440x900·모바일 390x844, 다크 테마를 캡처해 사용자 화면과 같은 결과를 확인했다(익명 데이터: 원거래 `hasAdjustmentHistory=true, cancelledAt=null, refundedPrincipal=0` + `adjustmentVoidedAt`이 있는 결제 취소 기록).

- 배지 `취소 되돌림`, 처리 이력 1건, 버튼은 `[환불][결제 취소]`뿐, 하단 "처리 이력이 있는 거래는 기록 보존을 위해 일반 수정·삭제할 수 없습니다."
- 원인: 프론트 514·517행 조건이 `hasAdjustmentHistory`만 보고, 백엔드도 `requireOrdinaryEditableEntry`가 같은 기준으로 막는다. `hasAdjustmentHistory`는 **한 번 true가 되면 되돌려도 false가 되지 않는다**(LedgerAdjustmentService 134행만 설정, revert는 건드리지 않음). 이 상태의 원거래는 활성 효과가 하나도 없는데도 영구히 수정·삭제가 막힌다.

### 1.3 화면 B와의 불일치

- 처리 이력이 없는 거래는 `[거래 내용 수정][환불][결제 취소][삭제]` 2x2.
- 이력이 있는 거래는 같은 창인데 버튼 구성이 완전히 달라지고, 행 버튼 이름도 `수정` ↔ `관리`로 바뀐다(397행).
- **결제 취소된 거래**(캡처 `current-cancelled-manage-*`)는 주 행동 버튼이 **하나도 없고**, 이력 카드 안의 작은 `이 처리 되돌리기`만 있다. 사용자가 원하는 "결제 취소 취소"가 주 행동으로 보이지 않는다.

### 1.4 버튼 설명 줄 잘림(화면 A) 원인 — 계산된 스타일로 확인

다크 테마 데스크톱에서 관리 창 버튼에 실제 적용된 규칙(위에서 아래로 덮어씀):

```
.button                                   => min-height:46px; padding:0px 18px; align-items:center; justify-content:center; ...
:root[data-theme="toss"] .button          => background / border-color
.ledger-manage-actions .button[data-v-…]  => min-height:3.25rem; align-items:flex-start; white-space:normal
```

- **세로 패딩이 0**(전역 `padding:0 18px`)이고, scoped 규칙은 `min-height: 3.25rem`(데스크톱 52px, 모바일 48px)만 준다.
- 내용 높이(굵은 제목 줄 line-height 24px + gap 0.2rem + 설명 줄)가 최소 높이와 거의 같아서, 측정값이 데스크톱 `button.bottom=683, small.bottom=681`(2px), 모바일 `669/663`이었다. 설명 줄의 아랫부분이 1px 테두리에 붙어 **잘린 것처럼 보인다**. 2열 배치에서 설명이 두 줄로 접히면 더 심해진다.
- 수정: 4.6절(패딩·line-height·height:auto 지정, 설명 줄 `display:block`).

### 1.5 그 밖의 문제

- P1. 처리 이력 항목의 날짜·시간이 붙어 보인다: `2026-10-2209:30`(템플릿 497행 `{{ item.entryDate }}<template v-if> {{ clock(...) }}</template>`에서 앞 공백이 컴파일 시 사라짐).
- P2. 환불·결제 취소 입력과 되돌림 확인이 **관리 창 밖**(별도 모달, `window.confirm`)에서 일어나고, 처리 후 관리 창이 닫힌다. 1→2→3 흐름(입력 → 취소 → 취소 취소 → 수정)을 이어서 할 수 없다.
- P3. 버튼 정보가 버튼 안 `<small>`에 있어 접근성 이름이 `환불 받은 금액과 차감·추가 금액 기록`처럼 길고, `결제 취소`와 새 `결제 취소 취소`, `환불`과 `환불 취소`가 접두로 겹친다(e2e `/^환불/` 등이 모호해진다).
- P4. 통계 검색 `이동`(`moveEntryFromSearch`)과 여행 가계부 `수정`(`editTravelLedgerEntry` 3262~3268)은 이력 있는 거래도 메인 편집 폼에 넣는다 → 저장 시 서버 400.
- P5. `hasAdjustmentHistory` 기반 조건이 시트 6곳, 통계 4곳, 달력 1곳, HouseholdWorkspace 1곳에 흩어져 있다.

### 1.6 기준선 e2e 실패(이번 변경과 무관, 확인만)

- `record-sharing.spec.js` 347~377행, `mobile-usability.spec.js` 215행 루프는 시트 행에 `삭제` 버튼이 있다고 기대한다. 삭제는 이미 관리 창으로 옮겨졌으므로 기준선에서 실패할 수 있다. 이번 작업의 e2e 갱신(6단계 F7) 때 함께 고친다.

---

## 2. 상태 모델과 상태별 행동표

### 2.1 원거래 상태 정의

원거래 `o`(`adjustmentType == null`)의 필드만으로 결정한다(처리 기록 목록이 없어도 결정 가능). 우선순위는 위에서부터.

| 상태 | 조건 | 배지(기존 라벨 유지) | 상태 줄(관리 창) | 활성 효과 |
|---|---|---|---|---|
| `CANCELLED` 결제 취소됨 | `o.cancelledAt != null` | `결제 취소됨` | `결제 취소 적용 중 · 이 거래는 지출 합계에서 제외됩니다.` | 있음 |
| `REFUNDED` 환불 완료 | `refundedPrincipal > 0 && refundedPrincipal >= amount` | `환불 완료` | `환불 완료 · 원거래 금액 전액이 환불 처리되었습니다.` | 있음 |
| `PARTIALLY_REFUNDED` 부분 환불 | `refundedPrincipal > 0` | `부분 환불` | `부분 환불 · 환불 기준 ₩{refundedPrincipal} · 남은 환불 가능 ₩{amount − refundedPrincipal}` | 있음 |
| `REVERTED` 처리 모두 취소됨 | `o.hasAdjustmentHistory`(위 셋이 아님) | `취소 되돌림`/`환불 되돌림`/`처리 되돌림`(기존 `summarizeLedgerAdjustments`) | `지난 처리를 모두 취소했습니다 · 처리 이력만 남아 있으며 일반 거래처럼 수정·삭제할 수 있습니다.` | 없음 |
| `NORMAL` 정상 | 위 어디에도 해당 없음 | (없음) | (표시 안 함) | 없음 |

- 처리 기록 행(`adjustmentType != null`)은 상태 `RECORD`. 자기 자신은 수정·삭제·공유 불가이고, 관리 창은 원거래 기준으로 연다(선행 작업과 동일).
- **활성 효과 판단(`hasActive`)**: `o.cancelledAt != null || refundedPrincipal > 0 || o.financiallyExcluded`. 처리 기록 목록을 알고 있으면 `adjustments.some(a => !a.adjustmentVoidedAt)`도 OR로 더한다(데이터 불일치 시 더 엄격한 쪽). 근거: 활성 결제 취소는 반드시 `cancelledAt`과 `financiallyExcluded`를 세우고(서비스 92~93행), 활성 환불은 `principal > 0`이라 `refundedPrincipal > 0`이다. 원거래의 `financiallyExcluded`를 세우는 곳은 결제 취소뿐이다(grep 확인).
- 결제 취소와 환불은 동시에 활성일 수 없다(취소 상태면 생성 거부 67~70행, 환불 있으면 취소 거부 88행). 활성 결제 취소는 최대 1건이다.

### 2.2 상태 전이(상태 머신)

```
            결제 취소(create PAYMENT_CANCELLATION)
 NORMAL ───────────────────────────────────────▶ CANCELLED
   │  ▲                                              │
   │  │ (이력이 없으므로 돌아오지 않음)                  │ 결제 취소 취소(revert 취소 기록)
   │  └───────────── REVERTED ◀──────────────────────┘
   │                  │  ▲  ▲
   │ 환불(create      │  │  └─────── 환불 취소(마지막 활성 환불 revert) ──┐
   │  REFUND)         │  │                                               │
   ▼                  │  └─ 결제 취소 ─▶ CANCELLED(위와 같음)              │
 PARTIALLY_REFUNDED ◀─┘ 환불                                             │
   │  ▲ 환불 취소(활성 환불이 남음)                                         │
   │  │                                                                 │
   └──┴── 추가 환불(합계 = amount) ──▶ REFUNDED ── 환불 취소 ─▶ PARTIALLY_REFUNDED 또는 REVERTED
```

- `REVERTED`에서 FULL 수정으로 금액을 바꾸거나 다시 처리할 수 있다. 다시 처리하면 `CANCELLED`/`PARTIALLY_REFUNDED`로 간다.
- 삭제는 `NORMAL`, `REVERTED`에서만 가능(3.3절). 삭제 → 휴지통 → 복구하면 같은 상태로 돌아온다.

### 2.3 행동 정의와 용어

| 행동 id | 버튼 이름(접근성 이름과 동일) | 내부 동작 | 이력 라벨(결과) |
|---|---|---|---|
| `uncancel` | `결제 취소 취소` | 활성 결제 취소 기록에 `POST /api/entries/{기록id}/adjustments/revert` | `결제 취소 되돌림`, 배지 `취소 되돌림` |
| `unrefund` | `환불 취소` | 선택한 활성 환불 기록에 revert | `환불 되돌림` |
| `edit` | `거래 내용 수정` | FULL: 메인 편집 폼(기존). LIMITED: 창 안 간이 폼 → `PUT /api/entries/{id}` | — |
| `refund` | `환불` | 창 안 환불 입력 → `POST /api/entries/{id}/adjustments` | `환불 입금` |
| `cancel` | `결제 취소` | 창 안 결제 취소 입력 → 같은 API | `결제 취소` |
| `delete` | `삭제` | 창 안 확인 → 기존 `removeEntry`(휴지통) | — |

- 용어: 사용자가 쓰는 말 "결제 취소를 취소"를 버튼 이름으로 쓰고, 결과 기록은 기존 라벨(`되돌림`)을 유지한다. 라벨 함수(`ledgerSheetGroups.js`)는 바꾸지 않는다(통합 달력·통계·선행 e2e와 일관).
- 이력 목록 안의 작은 `이 처리 되돌리기` 버튼은 **삭제**한다. 되돌리기는 주 행동(`결제 취소 취소`, `환불 취소`) 한 곳에서만 한다. 이력은 읽기 전용 타임라인이 된다.

### 2.4 상태별 행동표 (관리 창 주 행동, 표시 순서대로)

표기: ● 활성, ◐ 비활성으로 표시(`aria-disabled="true"`, 이유를 설명 줄에 표시), – 숨김.
설명 줄 문구와 비활성 사유 문구는 **그대로** 구현한다(`{}`는 치환).

| 행동 \ 상태 | NORMAL(지출) | NORMAL(수입) | CANCELLED | PARTIALLY_REFUNDED | REFUNDED | REVERTED |
|---|---|---|---|---|---|---|
| `결제 취소 취소` | – | – | ● 1순위 | – | – | – |
| `환불 취소` | – | – | – | ● 2순위 | ● 1순위 | – |
| `환불` | ● 2순위 | – | – | ● 1순위 | – | ● 2순위 |
| `거래 내용 수정` | ● 1순위 (FULL) | ● 1순위 (FULL) | ● 2순위 (LIMITED) | ● 3순위 (LIMITED) | ● 2순위 (LIMITED) | ● 1순위 (FULL) |
| `결제 취소` | ● 3순위 / 여행 연결이면 ◐ | – | – | ◐ | ◐ | ● 3순위 / 여행 연결이면 ◐ |
| `삭제` | ● 마지막 | ● 마지막 | ◐ | ◐ | ◐ | ● 마지막 |

설명 줄(활성일 때):

| 행동 | 설명 줄 |
|---|---|
| `결제 취소 취소` | `취소를 되돌려 이 거래를 지출 합계에 다시 포함합니다` |
| `환불 취소` | 활성 환불 1건: `환불 입금 ₩{amount}({날짜})을 되돌립니다` / 2건 이상: `적용 중인 환불 {n}건 중 하나를 골라 되돌립니다` |
| `환불` | NORMAL·REVERTED: `받은 금액과 차감·추가 금액을 기록합니다` / PARTIALLY_REFUNDED: `남은 ₩{amount − refundedPrincipal} 범위에서 추가 환불을 기록합니다` |
| `거래 내용 수정` FULL | NORMAL: `금액, 날짜, 분류 등 모든 항목을 변경합니다` / REVERTED: `금액, 날짜, 분류 등 모든 항목을 변경합니다(지난 처리 이력은 보존)` |
| `거래 내용 수정` LIMITED | `제목·메모·분류·날짜·시간만 변경합니다(금액 등은 처리 적용 중)` |
| `결제 취소` | `청구되지 않은 결제를 취소로 기록하고 합계에서 제외합니다` |
| `삭제` | NORMAL: `휴지통으로 이동합니다` / REVERTED: `지난 처리 이력과 함께 휴지통으로 이동합니다` |

비활성 사유:

| 행동 | 상태/조건 | 사유 문구 |
|---|---|---|
| `결제 취소` | `travelRecordId != null` | `여행 기록과 연결된 거래는 결제 취소할 수 없습니다.` |
| `결제 취소` | PARTIALLY_REFUNDED / REFUNDED | `환불 기록이 있어 결제 취소할 수 없습니다. 환불을 모두 취소한 뒤 가능합니다.` |
| `삭제` | CANCELLED | `결제 취소를 먼저 취소해야 삭제할 수 있습니다.` |
| `삭제` | PARTIALLY_REFUNDED / REFUNDED | `적용 중인 환불을 모두 취소해야 삭제할 수 있습니다.` |
| `결제 취소 취소` / `환불 취소` | 대상 기록을 아직 모름(그룹 로딩 중) | `처리 이력을 불러오는 중입니다.` |
| 〃 | 그룹 로딩 실패 + 로컬에도 대상 기록 없음 | `처리 이력을 불러오지 못했습니다. 아래 '다시 시도'를 눌러 주세요.` |
| `결제 취소 취소` | 이력은 받았는데 활성 취소 기록이 없음(데이터 불일치) | `적용 중인 취소 기록을 찾을 수 없습니다. 관리자에게 문의해 주세요.` |

하단 안내(`note`, 상태별 1줄, 없으면 표시 안 함):
- CANCELLED / PARTIALLY_REFUNDED / REFUNDED: `처리가 적용 중이라 금액·수입/지출 구분·결제수단·외화·할부·여행 연결은 바꿀 수 없습니다. 처리를 취소하면 모든 항목을 수정할 수 있습니다.`
- REVERTED: `지난 처리 이력의 금액은 처리 당시 금액으로 보존됩니다.`
- 그 외: 없음. (기존 520행 "처리 이력이 있는 거래는 기록 보존을 위해…" 문구는 **삭제**)
- 여행 연결 안내(521행)는 `결제 취소` 비활성 사유로 대체하므로 삭제한다.

### 2.5 분할 결제 조합

- **분할 묶음 행**(`kind: 'split'`)의 관리 창(현행 `managedSplit`, 508~513행)은 묶음 단위 메뉴를 유지한다.

| 행동 | 조건 | 설명/사유 |
|---|---|---|
| `거래 내용 수정` | 모든 수단이 `bulkSelectable`(이력 전혀 없음) | 설명 `분할 결제 전체 수정` / ◐ 사유 `처리 이력이 있는 수단이 있어 묶음 전체 수정은 할 수 없습니다. '분할 결제 보기'에서 수단별 '관리'를 이용하세요.` |
| `환불` | 하나 이상의 수단이 `refund` 활성 | `처리할 결제수단 선택` (→ 분할 상세, intent `refund`) |
| `결제 취소` | 하나 이상의 수단이 `cancel` 활성 | `처리할 결제수단 선택` |
| `결제 취소 취소` | 하나 이상의 수단이 CANCELLED | `되돌릴 결제수단 선택` (→ 분할 상세, intent `uncancel`) |
| `환불 취소` | 하나 이상의 수단이 활성 환불 보유 | `되돌릴 결제수단 선택` (intent `unrefund`) |
| `삭제` | 하나 이상의 수단이 `delete` 활성 | `삭제할 결제수단 선택` (danger) |

  조건을 만족하지 않는 행동은 숨긴다(묶음 메뉴는 숨김이 덜 혼란스럽다). 백엔드 묶음 수정(`validateSplitEditable`)은 **현행 유지**(어느 수단이든 이력이 있으면 거부).
- **분할 상세 창**의 수단별 버튼은 `관리` 하나로 통일한다(현행 `수정·처리`/`관리`). 누르면 그 수단 기준 통합 관리 창이 열리고, 상태별 행동표(2.4)를 그대로 따른다. intent(`refund`/`cancel`/`uncancel`/`unrefund`/`delete`)로 들어온 경우 해당 행동이 가능한 수단에만 `이 수단 {행동}` 버튼을 보여 주고, 누르면 그 수단의 관리 창을 **해당 단계로 바로** 연다(4.4절 `initialStep`).

---

## 3. 수정·삭제 정책 결정과 근거

### 3.1 결정 요약

| # | 결정 | 근거 |
|---|---|---|
| D1 | **활성 효과가 없는 원거래(NORMAL, REVERTED)는 전체 수정(FULL) 허용** — 수입/지출 구분 변경 포함 | 되돌린 기록은 `financiallyExcluded=true` + `adjustmentVoidedAt`으로 합계에 영향이 없고, `refundedPrincipal=0`, `cancelledAt=null`이라 원거래 금액을 바꿔도 깨지는 불변식이 없다. 새 처리는 `create`가 새 금액·구분으로 다시 검증한다 |
| D2 | **활성 효과가 있는 원거래(CANCELLED, PARTIALLY_REFUNDED, REFUNDED)는 제한 수정(LIMITED)**: 제목·메모·대분류(지출)·소분류·날짜·시간만. 금액·구분·결제수단·외화(통화·외화금액·환율)·할부·여행 계획/기록 연결은 잠금 | 취소 기록은 원거래 금액을 복사해 두었고(`adjustmentPrincipal = amount`), 환불은 `refundedPrincipal <= amount`에 의존한다. 금액·구분이 바뀌면 되돌림(`revert`의 `inconsistent()` 159행)과 환불 가능 잔액이 깨진다. 결제수단·여행 연결도 취소 규칙(여행 연결 금지)과 집계 의미를 바꾼다 |
| D3 | LIMITED에서 **날짜는 가장 이른 활성 처리 기록 날짜 이하**로만 변경 | `create`의 "처리일 ≥ 원거래 날짜" 불변식(71~73행)을 유지. 되돌린 기록 날짜는 제한하지 않는다(효과 없음, 사용자가 잘못 입력한 날짜를 고칠 수 있어야 함) |
| D4 | **삭제는 활성 효과가 없을 때만**(NORMAL, REVERTED). REVERTED 삭제 시 되돌린 처리 기록도 **같은 `deletedAt`(초 단위 절삭)으로 함께 휴지통**에 넣고, 원거래 복구 시 함께 복구, 영구 삭제 시 함께 삭제. 휴지통 목록에는 처리 기록을 표시하지 않는다 | 기록만 남기면 원거래가 없는 "연결 처리 행"이 시트에 남고 그룹 API가 404가 된다. 같은 시각으로 묶으면 스키마 변경 없이 복구 대상을 식별할 수 있다 |
| D5 | **처리 기록 행은 계속 수정·삭제·복구 불가**. 일괄 수정(`bulkUpdate`), 분할 묶음 수정(`validateSplitEditable`), 변경 이력 복구(`applySnapshot`)는 **현행 엄격 규칙 유지**(이력이 한 번이라도 있으면 거부, 문구만 갱신) | 다건 작업에서 상태별 잠금 필드를 설명하기 어렵고 범위가 커진다. 개별 수정은 관리 창으로 일원화 |
| D6 | **낙관적 락(버전 컬럼) 없이** 소유자 행 비관적 잠금 + **서버의 현재 상태 기준 재검증**으로 정합성 보장. 서버 capabilities 필드는 **추가하지 않는다** | `@Version`은 스키마 변경이 필요하다. 모든 쓰기가 이미 `getRequiredUserForWrite`로 직렬화되고, 검증은 잠금 뒤 다시 읽은 엔티티로 한다. 낡은 화면에서 보낸 금액 변경은 서버에서 400으로 거부된다. capabilities 계산 입력(origin 필드 + adjustments)은 그룹 응답에 이미 모두 있어, 서버 필드를 추가하면 같은 규칙이 두 군데서 다르게 진화할 위험만 늘어난다. 대신 **같은 사례표(7.1)로 프론트·백엔드 양쪽을 테스트**한다 |
| D7 | 이미 있는 이력은 **처리 당시 값 그대로 보존**(FULL로 금액·제목을 바꿔도 되돌린 취소 기록의 금액·제목은 갱신하지 않음) | 기록은 감사 흔적이다. 화면에서 "처리 당시 금액" 안내로 혼동을 줄인다 |

### 3.2 백엔드 검증 규칙(의사코드)

새 클래스 `backend/src/main/java/com/playdata/calen/ledger/service/LedgerEntryEditPolicy.java`(패키지 전용 `final class`, 정적 메서드, 스프링 빈 아님):

```java
enum EditMode { FULL, LIMITED }

static boolean hasActiveAdjustment(LedgerEntry origin, List<LedgerEntry> records) {
    BigDecimal refunded = origin.getRefundedPrincipal() == null ? BigDecimal.ZERO : origin.getRefundedPrincipal();
    return origin.getCancelledAt() != null || refunded.signum() > 0 || origin.isFinanciallyExcluded()
            || records.stream().anyMatch(r -> r.getAdjustmentVoidedAt() == null);
}

static Optional<LocalDate> earliestActiveRecordDate(List<LedgerEntry> records)   // voidedAt == null 중 entryDate 최솟값

/** 요청이 잠금 필드를 바꾸는지. 반환: 사용자에게 보일 필드 이름 목록(중복 없음, 표시 순서 고정) */
static List<String> lockedFieldChanges(LedgerEntry e, LedgerEntryRequest r) {
    list = []
    if (r.entryType() != e.getEntryType()) add("수입/지출 구분")
    if (e.getEntryType() == EXPENSE && !Objects.equals(r.paymentMethodId(), e.getPaymentMethod().getId())) add("결제수단")
    String rc = currency(r.foreignCurrencyCode()), ec = currency(e.getForeignCurrencyCode())   // null·공백·"KRW" → null, 대문자
    if (!Objects.equals(rc, ec)) add("외화 정보")
    else if (ec != null) {                                     // 외화 거래: 원화 금액은 재계산되므로 외화 금액·환율로 비교
        if (!sameAmount(r.foreignAmount(), e.getForeignAmount())
            || r.exchangeRateToKrw() == null || !sameAmount(r.exchangeRateToKrw(), e.getExchangeRateToKrw())) add("외화 정보")
    } else if (!sameAmount(r.amount(), e.getAmount())) add("금액")
    Integer im = r.installmentMonths();                        // null = 유지(applyRequest 규칙), 0 = 해제
    if (im != null && (im == 0 ? e.getInstallmentMonths() != null : !im.equals(e.getInstallmentMonths()))) add("할부")
    if (r.installmentInterestType() != null && !r.installmentInterestType().equals(e.getInstallmentInterestType())) add("할부")
    if (!Objects.equals(r.travelPlanId(), e.getTravelPlanId()) || !Objects.equals(r.travelRecordId(), e.getTravelRecordId())) add("여행 연결")
    return list
}
// sameAmount: 둘 다 null이면 true, 하나만 null이면 false, 아니면 compareTo == 0
```

`LedgerEntryService` 변경(행번호는 작성 시점):

```java
// update 845~862
@Transactional
public LedgerEntryResponse update(Long userId, Long entryId, LedgerEntryRequest request) {
    appUserService.getRequiredUserForWrite(userId);                 // 소유자 잠금(기존)
    LedgerEntry e = find...(entryId, userId)                          // 기존
    if (e.getAdjustmentType() != null) throw new BadRequestException(MSG_RECORD_IMMUTABLE);   // request가 null이어도 먼저 판정
    List<LedgerEntry> records = e.isHasAdjustmentHistory()
            ? ledgerEntryRepository.findAllByOwnerIdAndAdjustmentOriginIdAndDeletedAtIsNullOrderByEntryDateAscIdAsc(userId, e.getId())
            : List.of();
    boolean limited = LedgerEntryEditPolicy.hasActiveAdjustment(e, records);
    if (limited) {
        var changed = LedgerEntryEditPolicy.lockedFieldChanges(e, request);
        if (!changed.isEmpty()) throw new BadRequestException(MSG_LOCKED.formatted(String.join("·", changed)));
        var limit = LedgerEntryEditPolicy.earliestActiveRecordDate(records);
        if (limit.isPresent() && request.entryDate().isAfter(limit.get())) throw new BadRequestException(MSG_DATE.formatted(limit.get()));
    }
    LedgerEntrySnapshot before = toSnapshot(e);
    applyRequest(userId, e, request);                                 // 카테고리 유형·소분류 소속·여행 기간·텍스트 정리 검증 재사용
    if (limited) {                                                    // 외화 재계산·환율 메타데이터 변동을 원복(요청 값이 같음을 위에서 확인함)
        e.setAmount(before.amount()); e.setForeignCurrencyCode(before.foreignCurrencyCode());
        e.setForeignAmount(before.foreignAmount()); e.setExchangeRateToKrw(before.exchangeRateToKrw());
        e.setExchangeRateDate(before.exchangeRateDate()); e.setExchangeRateProvider(before.exchangeRateProvider());
    }
    LedgerEntrySnapshot after = toSnapshot(e);
    recordChangeHistory(e.getOwner(), UPDATE, List.of(before), List.of(after), buildUpdateSummary(before, after));  // 기존
    return toResponse(e);
}

// delete 949~960
public void delete(Long userId, Long entryId, boolean permanent) {
    appUserService.getRequiredUserForWrite(userId);
    LedgerEntry e = find...
    if (e.getAdjustmentType() != null) throw new BadRequestException(MSG_RECORD_IMMUTABLE);
    List<LedgerEntry> records = e.isHasAdjustmentHistory() ? findAllBy...OriginId...(userId, e.getId()) : List.of();
    if (LedgerEntryEditPolicy.hasActiveAdjustment(e, records)) throw new BadRequestException(MSG_DELETE_ACTIVE);
    if (permanent) { ledgerEntryRepository.deleteAll(records); ledgerEntryRepository.delete(e); return; }
    LocalDateTime now = LocalDateTime.now().withNano(0);              // DB 정밀도 차이를 없애기 위해 초 단위
    records.forEach(r -> r.setDeletedAt(now));
    e.setDeletedAt(now);
}

// restore 962~969
public LedgerEntryResponse restore(Long userId, Long entryId) {
    appUserService.getRequiredUserForWrite(userId);
    LedgerEntry e = findByIdAndOwnerIdAndDeletedAtIsNotNull(...)
    if (e.getAdjustmentType() != null) throw new BadRequestException(MSG_RECORD_RESTORE);
    if (e.isHasAdjustmentHistory()) {
        ledgerEntryRepository.findAllByOwnerIdAndAdjustmentOriginIdAndDeletedAt(userId, e.getId(), e.getDeletedAt())
                .forEach(r -> r.setDeletedAt(null));
    }
    e.setDeletedAt(null);
    return toResponse(e);
}

// getDeletedEntries 143~162: 저장소 호출만 교체
ledgerEntryRepository.findAllByOwnerIdAndDeletedAtIsNotNullAndAdjustmentTypeIsNullOrderByDeletedAtDescEntryDateDescIdDesc(userId, PageRequest.of(...))

// requireOrdinaryEditableEntry 1523~1527: 조건 그대로, 문구만 MSG_STRICT로. (validateSplitEditable, bulkUpdate, applySnapshot에서 계속 사용)
```

저장소 `LedgerEntryRepository`에 파생 메서드 2개 추가(쿼리 메서드 이름만, 스키마 변경 없음):

```java
List<LedgerEntry> findAllByOwnerIdAndAdjustmentOriginIdAndDeletedAt(Long ownerId, Long originId, LocalDateTime deletedAt);
Page<LedgerEntry> findAllByOwnerIdAndDeletedAtIsNotNullAndAdjustmentTypeIsNullOrderByDeletedAtDescEntryDateDescIdDesc(Long ownerId, Pageable pageable);
```

오류 문구(상수로 두고 그대로 사용):

| 상수 | 문구 |
|---|---|
| `MSG_RECORD_IMMUTABLE` | `취소·환불 처리 기록은 직접 수정하거나 삭제할 수 없습니다. 원거래의 '거래 관리'에서 처리해 주세요.` |
| `MSG_LOCKED` | `결제 취소·환불이 적용 중인 거래는 %s을(를) 바꿀 수 없습니다. 결제 취소 취소 또는 환불 취소 후 수정해 주세요.` |
| `MSG_DATE` | `결제 취소·환불 처리일(%s)보다 늦은 날짜로 옮길 수 없습니다.` |
| `MSG_DELETE_ACTIVE` | `결제 취소·환불이 적용 중인 거래는 삭제할 수 없습니다. 결제 취소 취소 또는 환불 취소 후 삭제해 주세요.` |
| `MSG_RECORD_RESTORE` | `취소·환불 처리 기록은 원거래를 복구하면 함께 복구됩니다.` |
| `MSG_STRICT` | `취소·환불 이력이 있는 거래는 일괄 수정·분할 묶음 수정·변경 이력 복구에서 제외됩니다. 거래 관리에서 개별로 수정해 주세요.` |

### 3.3 엣지 케이스

| # | 상황 | 처리 |
|---|---|---|
| E1 | REVERTED에서 FULL로 금액 29,000 → 35,000 | 허용. 되돌린 취소 기록 금액은 29,000 그대로(이력에 "처리 당시 금액" 안내). 이후 환불 가능 잔액은 35,000 기준. 지출 합계는 35,000 |
| E2 | REVERTED에서 수입으로 구분 변경 | 허용(D1). 배지 `취소 되돌림` 유지, 수입이라 `환불`/`결제 취소`는 숨김. 다시 지출로 바꾸면 처리 가능 |
| E3 | REVERTED에서 날짜를 되돌린 기록 날짜보다 늦게 | 허용(D3, 되돌린 기록은 제한 없음) |
| E4 | CANCELLED에서 제목·메모·소분류·시간 변경 | 허용. `cancelledAt`, `financiallyExcluded`, 취소 기록은 그대로. 취소 기록 제목(`결제 취소: 옛 제목`)·카테고리는 갱신하지 않음(D7). 취소 기록은 합계 제외라 통계 영향 없음 |
| E5 | CANCELLED에서 날짜를 취소일보다 뒤로 | 400 `MSG_DATE`. 앞으로 당기는 것은 허용 |
| E6 | PARTIALLY_REFUNDED에서 금액을 환불 기준보다 작게(또는 어떤 금액 변경이든) | 400 `MSG_LOCKED("금액")`. `refundedPrincipal`은 변하지 않음 |
| E7 | 외화 원거래(환율 저장됨)를 LIMITED로 날짜만 변경 | 요청이 저장된 외화금액·환율을 그대로 보내므로 잠금 검사 통과. `applyRequest`가 원화 금액을 재계산하고 환율 날짜·제공자를 바꾸더라도 직후 원복 → 금액·`exchangeRateDate`·`exchangeRateProvider` 불변 |
| E8 | 다른 탭에서 결제 취소한 직후, 이 탭(낡은 FULL 화면)에서 금액 수정 저장 | 소유자 잠금 뒤 다시 읽은 엔티티는 CANCELLED → LIMITED → 400 `MSG_LOCKED`. 프론트는 오류를 보여 주고 그룹을 다시 불러온다(4.4절) |
| E9 | 두 탭에서 동시에 같은 거래 LIMITED 수정 | 소유자 잠금으로 직렬화, 마지막 저장이 이김(기존 일반 수정과 같은 동작). 재무 필드는 어느 쪽도 바꿀 수 없으므로 합계 정합성 문제 없음 |
| E10 | 데이터 불일치: `cancelledAt=null`인데 활성 취소 기록 존재 | 서버는 기록을 함께 보므로 LIMITED·삭제 거부. 프론트는 그룹을 받은 뒤 `adjustments`로 다시 계산해 LIMITED로 표시. `결제 취소 취소`는 그 기록으로 revert 시도 → 서버 `inconsistent()` 400 문구 노출 |
| E11 | REVERTED 원거래 삭제(처리 기록이 다른 날짜) | 날짜와 무관하게 모든 기록을 같은 시각으로 휴지통 이동. 시트·통계·통합 달력에서 모두 사라짐 |
| E12 | 휴지통에서 원거래 복구 | 같은 `deletedAt`의 기록만 복구. 처리 기록 id로 복구 요청 시 400 `MSG_RECORD_RESTORE`(휴지통 목록에 보이지 않으므로 정상 UI로는 발생하지 않음) |
| E13 | 영구 삭제 / 휴지통 비우기 | 영구 삭제는 기록 먼저 `deleteAll` 후 원거래 삭제(FK 없음, 인덱스만 있음 — `V20261030_033_1__ledger_payment_adjustments.sql`). 휴지통 비우기는 기존 쿼리로 기록까지 삭제됨 |
| E14 | 기존에 이미 휴지통에 있는 처리 기록 | 기존 정책상 삭제가 불가능했으므로 없다. 있더라도 목록에서 숨겨질 뿐 데이터는 보존 |
| E15 | 분할 수단 하나만 CANCELLED | 그 수단 관리 창은 LIMITED. 묶음 전체 수정은 거부(현행). 다른 수단은 각자 상태 |
| E16 | 변경 이력 화면에서 "복구"를 FULL 수정된 REVERTED 거래에 실행 | 현행 엄격 규칙으로 400 `MSG_STRICT`(D5, 비범위 N3) |
| E17 | 메인 편집 폼에 거래를 열어 둔 채 관리 창에서 결제 취소 | 편집 폼 저장 시 재무 필드가 바뀌었으면 400. 바뀌지 않았으면 LIMITED로 통과(단, 시간 미입력 거래는 폼이 `00:00`을 보내므로 시간 변경으로 저장될 수 있음 — 기존 폼 동작, 변경하지 않음) |
| E18 | 처리 기록 행 id로 `PUT`/`DELETE` | 400 `MSG_RECORD_IMMUTABLE`(기존 테스트 `update(owner, event, null)`가 계속 400이어야 하므로 **request 접근 전에** 판정) |

---

## 4. 통합 거래 관리 창 설계

### 4.1 진입점 통일

- 시트의 **모든 행 버튼을 `관리`**로 통일한다: 일반 행(397행 `수정`/`관리` → `관리`), 분할 묶음 행(337행 `수정` → `관리`), 분할 상세 수단 버튼(454행 `수정·처리`/`관리` → `관리`). `공유`, `분할 결제 보기`는 그대로.
- 행 버튼에 `aria-haspopup="dialog"`를 추가한다(접근성 이름은 `관리` 그대로).
- 시트 머리말 문구(261행) `선택한 날짜의 거래를 바로 수정하거나 삭제할 수 있습니다.` → `거래의 '관리'에서 수정·결제 취소·환불·삭제를 할 수 있습니다.`

### 4.2 컴포넌트 구조

```
HouseholdTransactionSheet.vue
 ├─ (행, 분할 상세 창: 기존)
 └─ <LedgerManageDialog>  ← 새 파일. 기존 461~523행 관리 오버레이 + 스크립트 148~244행의 관리 로직을 옮김
       ├─ 메뉴 단계: 상태 줄 → 원거래 요약 → 주 행동 그리드 → 처리 이력 타임라인 → 안내
       ├─ 단계 cancel/refund: <LedgerAdjustmentForm embedded>  ← LedgerAdjustmentDialog.vue에서 폼 본문만 추출한 새 파일
       ├─ 단계 uncancel / unrefund / delete: 확인 화면
       └─ 단계 edit-limited: 간이 수정 폼
LedgerAdjustmentDialog.vue  ← <LedgerAdjustmentForm>을 감싸는 래퍼로 축소(동작·문구 동일)
```

- API 호출(`createLedgerAdjustment`, `revertLedgerAdjustment`, `updateEntry`, `fetchLedgerAdjustmentGroup`)은 `LedgerManageDialog`가 직접 한다(시트가 이미 `fetchLedgerAdjustmentGroup`을 직접 부르는 선례). 성공하면 그룹을 다시 불러와 **창을 연 채로** 새 상태를 보여 주고, 상위에 `ledger-changed`를 알려 목록·합계를 새로 고친다.
- FULL `거래 내용 수정`은 기존처럼 창을 닫고 `edit-entry` emit(메인 편집 폼). 삭제는 창 안 확인 후 `delete-entry`에 `{ entry, confirmed: true }`를 emit(기존 `removeEntry`가 휴지통 이동·로컬 반영·편집 폼 초기화를 담당).

### 4.3 레이아웃 와이어프레임

데스크톱(창 폭 `min(100%, 44rem)`), 상태 CANCELLED, 메뉴 단계:

```
┌ 거래 관리 ───────────────────────────────────────────────────── [닫기]* ┐
│ 거래 상태를 확인하고 필요한 처리를 선택합니다.                              │
│ [결제 취소됨]  결제 취소 적용 중 · 이 거래는 지출 합계에서 제외됩니다.        │ ← 상태 줄(.ledger-manage-state)
│ ┌ 원거래 ────────────────────────────────────────────────────────────┐ │
│ │ 구독 서비스 A                                                         │ │
│ │ 2026-10-22 · 09:00 · 지출 · 취미 / 구독 · 테스트 카드                     │ │
│ │ ~~₩29,000~~  결제 취소 · 합계 제외                                      │ │
│ │ 메모: 테스트 메모                                                       │ │
│ └──────────────────────────────────────────────────────────────────────┘ │
│ 할 수 있는 작업 (h3)                                                       │
│ ┌───────────────────────────────┐ ┌───────────────────────────────┐      │
│ │ 결제 취소 취소                    │ │ 거래 내용 수정                    │      │
│ │ 취소를 되돌려 이 거래를 지출 합계에  │ │ 제목·메모·분류·날짜·시간만 변경합니다 │      │
│ │ 다시 포함합니다                    │ │ (금액 등은 처리 적용 중)            │      │
│ └───────────────────────────────┘ └───────────────────────────────┘      │
│ ┌┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┐  ← aria-disabled: 점선 테두리 + 흐림        │
│ ┆ 삭제                            ┆                                         │
│ ┆ 결제 취소를 먼저 취소해야 삭제할 수  ┆                                         │
│ ┆ 있습니다.                        ┆                                         │
│ └┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┘                                         │
│ 처리 이력 (h3)                                                             │
│ │ 결제 취소 · 2026-10-22 09:30 · 적용 중                                    │
│ │ 결제 취소 금액 ₩29,000 · 테스트 취소 메모                                  │
│ 처리가 적용 중이라 금액·수입/지출 구분·결제수단·외화·할부·여행 연결은 바꿀 수       │
│ 없습니다. 처리를 취소하면 모든 항목을 수정할 수 있습니다.                      │
│ (role=status) 결제 취소를 저장했습니다.                                     │ ← 직전 처리 결과(있을 때만)
└──────────────────────────────────────────────────────────────────────────┘
 * 메뉴 단계에서만 data-modal-close
```

REVERTED(화면 A 개선): 상태 줄 `[취소 되돌림] 지난 처리를 모두 취소했습니다 · …`, 행동 `[거래 내용 수정(FULL)] [환불] [결제 취소] [삭제]` 2x2(화면 B와 같은 구성), 이력은 읽기 전용, 안내 `지난 처리 이력의 금액은 처리 당시 금액으로 보존됩니다.`

확인 단계(예: `결제 취소 취소`):

```
┌ 거래 관리 ─────────────────────────────────────────── [닫기] ┐   ← data-modal-close 없음
│ 결제 취소 취소 (h3, tabindex=-1, 진입 시 포커스)                   │
│ 구독 서비스 A · 2026-10-22 결제 취소(₩29,000)를 되돌립니다.         │
│ · 원거래가 다시 지출 합계에 포함됩니다.                             │
│ · 취소 기록은 '결제 취소 되돌림'으로 처리 이력에 남습니다.             │
│ (role=alert 오류 자리)                                          │
│                      [결제 취소 취소하기(primary)] [돌아가기]*    │   ← * data-modal-close
└────────────────────────────────────────────────────────────────┘
```

- `환불 취소` 단계: 활성 환불이 1건이면 위와 같은 확인(문구 `환불 입금 ₩{amount}({날짜})을 되돌립니다.` / `· 이 환불 입금이 수입 합계에서 빠집니다.` / `· 환불 기준 ₩{principal}만큼 다시 환불할 수 있게 됩니다.` / `· 기록은 '환불 되돌림'으로 남습니다.`). 2건 이상이면 `<fieldset><legend>되돌릴 환불 선택</legend>` + 라디오(`{날짜} {시간} · 수령 ₩{amount} · 기준 ₩{principal}`, 기본 선택 없음, 선택 전 실행 버튼 비활성) → 실행 버튼 `환불 취소하기`.
- `결제 취소`/`환불` 단계: h3 `결제 취소 기록`/`환불 기록` + `<LedgerAdjustmentForm embedded cancel-label="돌아가기">`(기존 안내 문구·필드·검증 그대로, 기존 버튼 `결제 취소 저장`/`환불 기록 저장`).
- `거래 내용 수정`(LIMITED) 단계:

```
│ 거래 내용 수정 (h3)                                                    │
│ 결제 취소가 적용 중이라 금액·구분·결제수단·외화·할부·여행 연결은 바꿀 수 없습니다. │
│ 고정 항목: ₩29,000 · 지출 · 테스트 카드                                  │
│ [날짜 (max=처리일)] [시간 (선택)]                                        │
│ [제목 *]                                                               │
│ [메모 (선택, 500자)]                                                    │
│ [대분류 (지출, 활성 + 현재값)] [소분류 (소분류 없음 + 목록)]                │
│ (role=status 검증 문구) (role=alert 서버 오류)                            │
│                                         [저장(primary)] [돌아가기]*     │
```

  첫 줄 안내의 "결제 취소가"는 상태에 따라 `결제 취소가`/`환불이`로 바꾼다.
- `삭제` 단계: h3 `거래 삭제`, `'{제목}' 거래를 휴지통으로 이동합니다.` + REVERTED이면 `되돌린 처리 이력 {n}건도 함께 이동하며, 휴지통에서 복구하면 함께 복구됩니다.` → `[삭제하기(danger)] [돌아가기]*`.

모바일(390px): 같은 순서로 세로 1열. 창은 `padding: 1rem` 오버레이 안에서 폭 100%, `max-height: calc(100dvh - 2rem)`, 내부 스크롤. 행동 그리드는 `minmax(min(100%, 13rem), 1fr)`라 자동 1열. 단계 하단 버튼 줄은 `flex-wrap: wrap`, 각 버튼 `min-height: 2.75rem`, 실행 버튼이 먼저(왼쪽/위).

```
┌ 거래 관리      [닫기] ┐
│ [결제 취소됨]          │
│ 결제 취소 적용 중 · …   │
│ ┌ 원거래 ───────────┐ │
│ │ …                 │ │
│ └──────────────────┘ │
│ 할 수 있는 작업        │
│ [결제 취소 취소     ]  │
│ [거래 내용 수정     ]  │
│ [삭제(점선)        ]  │
│ 처리 이력              │
│ │ 결제 취소 · …        │
└──────────────────────┘
```

### 4.4 단계 전환 상태(`LedgerManageDialog`)

```js
const step = ref('menu')   // 'menu' | 'cancel' | 'refund' | 'uncancel' | 'unrefund' | 'edit-limited' | 'delete'
const busy = ref(false)
const stepError = ref('')
const resultMessage = ref('')       // 메뉴 단계 role=status
let stepTrigger = null              // 단계로 들어올 때 누른 행동 버튼(돌아가기 시 포커스 복귀)
```

- 행동 클릭: `aria-disabled`면 아무것도 하지 않는다(설명 줄에 이미 사유가 있음). FULL `edit` → `emit('edit-entry', origin)` 후 닫기. 그 외 → `stepTrigger = event.currentTarget; step.value = id; stepError = ''` → `nextTick` 후 단계 h3(tabindex=-1)에 포커스(폼 단계는 `LedgerAdjustmentForm`이 날짜 입력에 포커스하므로 그대로 둔다) + 창 `scrollTop = 0`.
- `돌아가기`: `busy`면 비활성. `step = 'menu'` → `stepTrigger`가 연결돼 있으면 포커스, 아니면 닫기 버튼.
- 실행(`uncancel`/`unrefund`): `busy=true` → `await revertLedgerAdjustment(record.id)` → `await reloadGroup()` → `step='menu'`, `resultMessage` 설정 → `emit('ledger-changed', { kind, message })` → 결과 메시지 요소(`tabindex=-1`)에 포커스. 실패: `stepError = error.message || '처리하지 못했습니다.'`(role=alert), 단계 유지, 그룹 다시 불러오기 시도.
- 실행(`cancel`/`refund` 폼 submit payload): `await createLedgerAdjustment(origin.id, payload)` → 이후 동일. 오류는 폼 `error` prop으로.
- 실행(`edit-limited`): `await updateEntry(origin.id, buildLimitedEditPayload(origin, draft))` → 이후 동일. 400이면 `stepError`에 서버 문구 + `reloadGroup()`(E8).
- 실행(`delete`): `emit('delete-entry', { entry: origin, confirmed: true })` 후 창 닫기(포커스 복귀는 시트가 처리).
- 결과 메시지:

| kind | 메시지 |
|---|---|
| `cancel` | `결제 취소를 저장했습니다. 이 거래는 지출 합계에서 제외됩니다.` |
| `refund` | `환불 기록을 저장했습니다.` |
| `uncancel` | `결제 취소를 취소했습니다. 이 거래가 다시 지출 합계에 포함됩니다.` |
| `unrefund` | `환불을 취소했습니다. 처리 이력은 보존됩니다.` |
| `edit` | `거래 내용을 수정했습니다.` |

- `reloadGroup()`: 원거래 id로 `fetchLedgerAdjustmentGroup`. 응답 검증·`seq` 무시 규칙은 기존 `loadManageGroup`(176~193행) 그대로. **이력이 없던 거래도 처리 후에는 항상 불러온다.**
- 열기 옵션: `initialStep`(분할 상세 intent에서 사용). 열린 직후 그룹 로딩이 필요한 단계(`uncancel`/`unrefund`)면 로딩 완료 후 단계로 들어간다(로딩 중에는 메뉴에서 해당 버튼이 "불러오는 중" 사유로 비활성).

### 4.5 접근성

- 다이얼로그: 오버레이 `role="dialog" aria-modal="true" aria-labelledby="ledger-manage-title"`, 제목 `거래 관리`는 **모든 단계에서 유지**(App.vue Esc·e2e가 이 이름에 의존). 단계 영역은 `<section :aria-labelledby="stepTitleId">` + `<h3 :id tabindex="-1">`(Playwright에서 `getByRole('region', { name: '환불 기록' })`).
- 열 때 포커스: 닫기 버튼(현행, e2e가 Esc 후 트리거 포커스 복귀를 검증). 닫을 때: 트리거가 연결돼 있으면 트리거, 아니면 시트 제목 `h2`(tabindex=-1 추가).
- **Esc**: 메뉴 단계에서는 헤더 `닫기`에 `data-modal-close`(→ 창 닫기). 하위 단계에서는 헤더 `닫기`의 속성을 제거하고(`:data-modal-close="step === 'menu' ? '' : null"`) 단계의 `돌아가기` 버튼에 `data-modal-close`를 둔다(→ 메뉴로). `busy` 중에는 `돌아가기`가 `disabled`라 App.vue의 `click()`이 무시된다. 오버레이의 `@keydown.esc.stop`은 남겨도 되지만 의존하지 않는다.
- 행동 버튼: `<button type="button" :aria-label="action.label" :aria-describedby="descId" :aria-disabled="!action.enabled || null" :data-manage-action="action.id">` 안에 `<strong>{label}</strong><small :id="descId">{설명 또는 사유}</small>`. 접근성 이름이 정확히 `환불`, `환불 취소`, `결제 취소`, `결제 취소 취소`, `거래 내용 수정`, `삭제`가 되어 접두 충돌이 없다. 비활성은 `disabled` 대신 `aria-disabled`(포커스 가능, 사유가 읽힘).
- 상태 줄은 정적 텍스트. 처리 결과는 `<p ref="resultEl" role="status" tabindex="-1">`.
- 처리 이력: `<section aria-labelledby="ledger-manage-history-title"><h3>처리 이력</h3><ol>`(현행 유지). 로딩 `role="status"`, 실패 `role="alert"` + `다시 시도`(현행 유지).

### 4.6 스타일(모두 scoped, `style.css` 수정 금지)

```css
.ledger-manage-dialog { width: min(100%, 44rem); }
.ledger-manage-dialog .ledger-manage-actions .button {
  height: auto; min-height: 3.75rem; padding: .7rem .9rem; gap: .25rem;
  display: flex; flex-direction: column; align-items: flex-start; justify-content: flex-start;
  line-height: 1.35; text-align: left; white-space: normal;
}
.ledger-manage-dialog .ledger-manage-actions .button strong { line-height: 1.35; }
.ledger-manage-dialog .ledger-manage-actions .button small { display: block; line-height: 1.35; font-weight: 400; }
.ledger-manage-dialog .ledger-manage-actions .button[aria-disabled='true'] { opacity: .62; cursor: not-allowed; border-style: dashed !important; }
.ledger-manage-dialog .ledger-manage-actions .button--danger:not([aria-disabled='true']) { background: #8b2735 !important; border-color: #8b2735 !important; color: #fff !important; }   /* 기존 568~569행 규칙 이전 */
.ledger-manage-state { display: flex; flex-wrap: wrap; align-items: center; gap: .4rem; margin: .4rem 0 0; line-height: 1.45; }
.ledger-manage-history__item { border-left: 3px solid var(--border); }   /* 타임라인 느낌, 테두리 유지 */
.ledger-manage-step__actions { display: flex; flex-wrap: wrap; gap: .5rem; justify-content: flex-end; }
.ledger-manage-step__actions .button { width: auto; min-height: 2.75rem; }
```

- 위 선택자 특이도는 0,4,0(+scoped 속성)으로 전역 `.button`(0,1,0)·다크 `:root[data-theme="toss"] .button`(0,3,0)을 이긴다. 다크 테마에서 계산된 스타일로 `paddingTop > 0`, `button.bottom - small.bottom >= 8`을 확인한다(F8).
- aria-disabled danger 버튼은 빨간 배경을 쓰지 않는다(일반 버튼 + 점선).
- 이력 날짜·시간은 `{{ [item.entryDate, clock(item.entryTime)].filter(Boolean).join(' ') }}`로 출력(P1).

---

## 5. 데이터/로직 설계

### 5.1 새 순수 함수 `frontend/src/lib/ledgerEntryCapabilities.js`

`import { summarizeLedgerAdjustments } from './ledgerSheetGroups.js'`만 허용(확장자 필수, 순환 금지).

```js
export const LEDGER_ENTRY_STATES = ['NORMAL', 'CANCELLED', 'PARTIALLY_REFUNDED', 'REFUNDED', 'REVERTED', 'RECORD']

export function ledgerEntryState(entry)
// → RECORD(adjustmentType 있음) | CANCELLED | REFUNDED | PARTIALLY_REFUNDED | REVERTED | NORMAL  (2.1절 우선순위)

export function ledgerEntryCapabilities(entry, adjustments = null, { historyStatus = 'ready' } = {})
// entry: 원거래(LedgerEntryResponse 형태). adjustments: 알고 있는 처리 기록 배열(없으면 null)
// historyStatus: 'ready' | 'loading' | 'error'  (관리 창의 그룹 로딩 상태)
// → {
//   state, hasActive,
//   editMode: 'FULL' | 'LIMITED' | 'NONE',        // RECORD → NONE
//   maxEntryDate: string | null,                    // LIMITED이고 adjustments가 있으면 활성 기록 entryDate 최솟값
//   bulkSelectable: boolean,                        // !hasAdjustmentHistory && !adjustmentType  (현행 일괄 규칙 그대로)
//   canShare: boolean,                              // !financiallyExcluded && !adjustmentType   (현행 그대로)
//   canDelete: boolean,                             // state NORMAL|REVERTED
//   refundableAmount: number,                       // 센트 단위 계산 후 /100, max(0, amount - refundedPrincipal)
//   activeCancellation: object | null,              // adjustments 중 활성 PAYMENT_CANCELLATION(여럿이면 compareLedgerChronologically 마지막)
//   activeRefunds: object[],                        // 활성 REFUND, 시간순
//   actions: Array<{ id, label, description, enabled, reason }>,   // 2.4절 표의 표시 순서, 숨김 제외
//   stateLine: string,                              // 2.1절 상태 줄('' 가능)
//   note: string,                                   // 2.4절 하단 안내('' 가능)
// }

export function splitRowCapabilities(row)
// row.kind === 'split' → { canEditGroup, editGroupReason, intents: ['refund','cancel','uncancel','unrefund','delete'] 중 가능한 것 }
// 각 수단에 ledgerEntryCapabilities(part, adjustmentsForEntry(row, part.id))를 적용해 2.5절 표대로

export function buildLimitedEditPayload(origin, draft)
// draft: { entryDate, entryTime, title, memo, categoryGroupId, categoryDetailId } (폼 문자열)
// → PUT 본문. 재무 필드는 origin 값 그대로:
// { entryDate, entryTime: draft.entryTime || null, title: draft.title.trim(), memo: draft.memo.trim() || null,
//   amount: Number(origin.amount), foreignCurrencyCode: origin.foreignCurrencyCode || null,
//   foreignAmount: origin.foreignAmount != null ? Number(origin.foreignAmount) : null,
//   exchangeRateToKrw: origin.exchangeRateToKrw != null ? Number(origin.exchangeRateToKrw) : null,
//   entryType: origin.entryType, categoryGroupId: Number(draft.categoryGroupId),
//   categoryDetailId: draft.categoryDetailId ? Number(draft.categoryDetailId) : null,
//   paymentMethodId: origin.paymentMethodId ?? null, travelPlanId: origin.travelPlanId ?? null,
//   travelRecordId: origin.travelRecordId ?? null, installmentMonths: origin.installmentMonths ?? null,
//   installmentInterestType: origin.installmentInterestType ?? null }
// 주의: HouseholdWorkspace의 buildEntryPayloadFromEntry는 쓰지 않는다(시간 없음을 '00:00'으로, 할부 없음을 0으로 보냄).

export function validateLimitedEditDraft(draft, capabilities)
// → '' 또는 첫 오류 문구:
//   제목 비었음 '제목을 입력해 주세요.' / 120자 초과 '제목은 120자 이내로 입력해 주세요.'
//   메모 500자 초과 '메모는 500자 이내로 입력해 주세요.'
//   날짜 없음 '날짜를 입력해 주세요.'
//   maxEntryDate가 있고 날짜 > maxEntryDate → `처리일(${maxEntryDate})보다 늦은 날짜로 바꿀 수 없습니다.`
//   대분류 없음 '대분류를 선택해 주세요.'
```

입출력 예(단위 테스트로 그대로 사용):

```js
const base = { id: 1, entryType: 'EXPENSE', amount: 29000, entryDate: '2026-10-22', title: '구독 서비스 A', paymentMethodId: 103 }
ledgerEntryCapabilities(base).actions.map(a => a.id)                       // ['edit', 'refund', 'cancel', 'delete']
ledgerEntryCapabilities({ ...base, entryType: 'INCOME' }).actions.map(a => a.id)   // ['edit', 'delete']
ledgerEntryCapabilities({ ...base, travelRecordId: 5 }).actions.find(a => a.id === 'cancel')
//   → { enabled: false, reason: '여행 기록과 연결된 거래는 결제 취소할 수 없습니다.', ... }

const cancelled = { ...base, hasAdjustmentHistory: true, financiallyExcluded: true, cancelledAt: '2026-10-22T09:30:00' }
const record = { id: 2, adjustmentType: 'PAYMENT_CANCELLATION', adjustmentOriginId: 1, entryDate: '2026-10-23', amount: 29000 }
const c = ledgerEntryCapabilities(cancelled, [record])
// c.state 'CANCELLED', c.editMode 'LIMITED', c.maxEntryDate '2026-10-23', c.activeCancellation.id 2
// c.actions.map(a => [a.id, a.enabled]) → [['uncancel', true], ['edit', true], ['delete', false]]
ledgerEntryCapabilities(cancelled, null, { historyStatus: 'loading' }).actions[0]
//   → { id: 'uncancel', enabled: false, reason: '처리 이력을 불러오는 중입니다.' }

const reverted = { ...base, hasAdjustmentHistory: true }
const r = ledgerEntryCapabilities(reverted, [{ ...record, adjustmentVoidedAt: '2026-10-23T10:00:00' }])
// r.state 'REVERTED', r.editMode 'FULL', r.canDelete true, r.maxEntryDate null
// r.actions.map(a => a.id) → ['edit', 'refund', 'cancel', 'delete']

const partial = { ...base, amount: 10000, hasAdjustmentHistory: true, refundedPrincipal: 3000 }
const refunds = [{ id: 8, adjustmentType: 'REFUND', adjustmentOriginId: 1, entryDate: '2026-10-22', entryTime: '13:00', amount: 2500, adjustmentPrincipal: 3000 }]
const p = ledgerEntryCapabilities(partial, refunds)
// p.state 'PARTIALLY_REFUNDED', p.refundableAmount 7000, p.editMode 'LIMITED'
// p.actions.map(a => [a.id, a.enabled]) → [['refund', true], ['unrefund', true], ['edit', true], ['cancel', false], ['delete', false]]

// 불일치(E10): 필드는 정상인데 활성 기록이 있음 → 더 엄격하게.
// 규칙: ledgerEntryState가 NORMAL/REVERTED인데 활성 기록이 있으면, 활성 취소 기록이 있으면 CANCELLED, 아니면 PARTIALLY_REFUNDED로
//       state를 올려 그 상태의 행동표를 쓴다(서버도 같은 경우 LIMITED·삭제 거부).
ledgerEntryCapabilities(reverted, [record]).editMode                         // 'LIMITED'
ledgerEntryCapabilities({ id: 2, adjustmentType: 'REFUND' }).editMode         // 'NONE', actions []

buildLimitedEditPayload({ ...cancelled, foreignCurrencyCode: 'USD', foreignAmount: 20, exchangeRateToKrw: 1450, categoryGroupId: 101, installmentMonths: null },
  { entryDate: '2026-10-21', entryTime: '', title: ' 구독 서비스 A2 ', memo: '', categoryGroupId: '101', categoryDetailId: '' })
// → { entryDate: '2026-10-21', entryTime: null, title: '구독 서비스 A2', memo: null, amount: 29000, foreignCurrencyCode: 'USD',
//     foreignAmount: 20, exchangeRateToKrw: 1450, entryType: 'EXPENSE', categoryGroupId: 101, categoryDetailId: null,
//     paymentMethodId: 103, travelPlanId: null, travelRecordId: null, installmentMonths: null, installmentInterestType: null }
```

### 5.2 `hasAdjustmentHistory` 기반 조건 → 새 함수로 교체할 곳

| 파일 · 행(작성 시점) | 현행 | 교체 |
|---|---|---|
| `HouseholdTransactionSheet.vue` 83 `selectableParts` | `!hasAdjustmentHistory && !adjustmentType` | `ledgerEntryCapabilities(entry).bulkSelectable` |
| 같은 파일 349~350 체크박스 title/disabled | 같은 조건 | `!caps.bulkSelectable` |
| 같은 파일 397 행 버튼 | `수정`/`관리` 분기 | 항상 `관리` |
| 같은 파일 398, 338, 455 공유 | `!financiallyExcluded && !adjustmentType` | `caps.canShare`(동작 동일) |
| 같은 파일 451~455 분할 상세 intent 버튼 | `canRefund`/`canCancel`/삭제 조건 | `caps.actions`의 해당 id가 enabled일 때 `이 수단 {환불/결제 취소/결제 취소 취소/환불 취소/삭제}` → 그 수단 관리 창을 해당 단계로 |
| 같은 파일 508~521 관리 창 버튼·안내 | 개별 조건 | `LedgerManageDialog`가 `caps.actions`/`caps.note`/`splitRowCapabilities`로 렌더링 |
| `CalendarWorkspace.vue` 1146~1149 `selectableDayEntries` | 같은 조건 | `ledgerEntryCapabilities(entry).bulkSelectable` |
| `StatisticsWorkspace.vue` 225~227 `searchResultSelection`, 2505 체크박스 | 같은 조건 | `bulkSelectable` |
| 같은 파일 2590 인라인 `수정` | 같은 조건 | `caps.editMode === 'FULL'` |
| 같은 파일 2593 `삭제` | 같은 조건 | `caps.canDelete` |
| `HouseholdWorkspace.vue` 3149 분할 묶음 편집 차단 | 이력 있으면 오류 | **유지**(D5). 문구 그대로 |
| `HouseholdWorkspace.vue` `moveEntryFromSearch` 5184~5190, `editTravelLedgerEntry` 3262~3268 | 항상 편집 폼 | `ledgerEntryCapabilities(entry).editMode !== 'FULL'`이면 편집 폼 대신 해당 날짜로 이동 + `scrollToLedgerSheet`(viewTravelLedgerEntryDate 3254~3260과 같은 방식) + `setFeedback('결제 취소·환불이 적용 중인 거래입니다. 거래 시트의 \'관리\'에서 수정해 주세요.')` |

시트 행 템플릿에서 같은 행마다 함수를 여러 번 부르지 않도록 기존 `rowViews` computed(121~124행)에 `caps: row.kind === 'entry' ? ledgerEntryCapabilities(row.parts[0], row.adjustments) : null`을 추가한다.

### 5.3 컴포넌트 계약

`LedgerAdjustmentForm.vue`(새 파일, `LedgerAdjustmentDialog.vue` 14~56행 스크립트와 72~95행 폼을 옮김):

```js
props: { entry: Object(required), mode: String(required), categories: Array, paymentMethods: Array, busy: Boolean, error: String,
         cancelLabel: { type: String, default: '닫기' }, escTarget: { type: Boolean, default: false } }
emits: ['submit', 'cancel']
// 취소 버튼: <button type="button" :data-modal-close="escTarget ? '' : null" :disabled="busy" @click="emit('cancel')">{{ cancelLabel }}</button>
// 안내 문구(67~71행)도 폼 안으로 옮긴다. 제목(h2)과 오버레이는 래퍼에 남긴다.
```

`LedgerAdjustmentDialog.vue`는 오버레이·`role="dialog"`·제목 `환불 기록`/`결제 취소 기록`·헤더 `닫기`를 유지하고 본문에 `<LedgerAdjustmentForm @submit="emit('submit', $event)" @cancel="emit('close')">`만 둔다. 외부 동작·문구는 바뀌지 않는다.

`LedgerManageDialog.vue`(새 파일):

```js
props: {
  row: Object,            // 관리 대상 행(kind entry|adjustment) 또는 분할 묶음 행(split)
  split: Boolean,         // 분할 묶음 메뉴 모드(기존 managedSplit)
  initialStep: { type: String, default: 'menu' },
  categoryGroups: Array, paymentMethods: Array,
  formatTime: Function, formatPaymentMethod: Function, formatAmountMain: Function, formatAmountSub: Function,
}
emits: ['close', 'edit-entry', 'delete-entry', 'choose-split-action', 'ledger-changed']
// close(restoreFocus:boolean), choose-split-action(intent) — 시트가 분할 상세 창을 연다(기존 chooseSplitAction 213~220)
```

`HouseholdTransactionSheet.vue`:
- 새 props `categoryGroups`, `paymentMethods`(기본 `[]`), 새 emit `ledger-changed`.
- `openManager(row, event, initialStep = 'menu')`, `openSplitManager`, `openPartManager(part, event, initialStep)`은 유지하되 상태만 넘기고 렌더링은 `<LedgerManageDialog v-if="managedRow" ...>`.
- `adjust-entry`, `revert-adjustment`, `show-adjustments`는 더 이상 emit하지 않는다. `defineEmits` 선언과 상위 연결은 **그대로 둔다**(제거는 비범위).

`CalendarWorkspace.vue`: 두 시트 사용처(5313~5346, 5360~5396)에 `:category-groups="categoryGroups" :payment-methods="paymentMethods" @ledger-changed="emit('ledger-changed', $event)"` 추가, `defineEmits`(369행 부근)에 `'ledger-changed'` 추가.

`HouseholdWorkspace.vue`:
- `<CalendarWorkspace … @ledger-changed="handleLedgerManageChanged">`(5899행 부근).
- `async function handleLedgerManageChanged({ message } = {}) { await refreshLedgerViews({ forceStatistics: true }); setFeedback(message || '') }`(실패 시 `setFeedback('처리는 저장되었습니다.', \`화면 새로고침 실패: ${error.message}\`)`).
- `removeEntry(payload)`: 첫 줄에서 `const entry = payload?.entry ?? payload; const confirmed = payload?.confirmed === true`로 정규화하고, `confirmed`면 `window.confirm`을 건너뛴다. 나머지 로직은 `entry` 변수로 그대로. (통계 `delete-search-entry`는 기존처럼 entry를 넘기므로 확인창이 뜬다.)

### 5.4 백엔드 DTO/서비스 변경점 요약

| 파일 | 변경 |
|---|---|
| `ledger/service/LedgerEntryEditPolicy.java` | 새 파일(3.2절) |
| `ledger/service/LedgerEntryService.java` | `update`, `delete`, `restore`, `getDeletedEntries` 변경, 문구 상수 추가, `requireOrdinaryEditableEntry` 문구 변경 |
| `ledger/repository/LedgerEntryRepository.java` | 파생 메서드 2개 추가 |
| DTO | **변경 없음**(`LedgerAdjustmentGroupResponse`에 capabilities를 추가하지 않음 — D6) |
| 컨트롤러 | **변경 없음**(권한·소유자 검증은 기존 `findByIdAndOwnerId…`와 `currentUser.userId()`로 유지) |
| `LedgerAdjustmentService` | **변경 없음** |

---

## 6. 단계별 작업 지시

공통 검증 명령:
- 프론트(`frontend`): `npm run build`, `npm run test:unit`
- e2e: dev 서버를 별도 포트로 백그라운드 실행 `cd frontend && npx vite --host 127.0.0.1 --port 5183 --strictPort` → `cd frontend && E2E_BASE_URL=http://127.0.0.1:5183 E2E_START_LOCAL_SERVER=0 npx playwright test <spec...> --config playwright.config.js --reporter=line` → 끝나면 vite 프로세스를 종료한다(Windows에서는 `netstat -ano | grep :5183`으로 남은 node 프로세스를 확인).
- 백엔드(Git Bash): `cd backend && JAVA_HOME=/c/jdk-17 ./gradlew test --tests '<클래스>'` (여러 개는 `--tests`를 반복). 테스트는 H2.

### B0 / F0. 기준선

- 실행: `npm run test:unit`, `npm run build`; e2e `e2e/ledger-adjustments.spec.js e2e/ledger-adjustment-grouping.spec.js e2e/ledger-manage-sheet.spec.js e2e/ledger-split-sheet-groups.spec.js e2e/ledger-split-payments.spec.js e2e/integrated-calendar.spec.js e2e/record-sharing.spec.js e2e/quick-entry-responsive.spec.js e2e/transaction-sheet-date-navigation.spec.js e2e/mobile-usability.spec.js`; 백엔드 `LedgerAdjustmentIntegrationTest`, `LedgerSplitPaymentIntegrationTest`, `com.playdata.calen.calendar.CalendarEventIntegrationTest`.
- 완료 기준: 실패 목록을 기록한다. 1.6절의 `삭제` 버튼 기대 실패는 기준선 실패로 분리한다.

### B1. 백엔드 수정·삭제 정책

- 목적: 3장 정책을 서버에서 강제한다.
- 파일: `LedgerEntryEditPolicy.java`(신규), `LedgerEntryService.java`, `LedgerEntryRepository.java`, 테스트 `backend/src/test/java/com/playdata/calen/ledger/service/LedgerEntryEditPolicyIntegrationTest.java`(신규, `LedgerAdjustmentIntegrationTest`의 `@SpringBootTest(properties = …)` 설정·`fixture()`·`purchase()`·`cancel()`·`refund()` 헬퍼를 복사해 사용).
- 구체 변경: 3.2절 의사코드 그대로. `update`에서 처리 기록 판정을 `request` 접근보다 먼저 한다(기존 테스트 `entryService.update(owner, event, null)`가 400이어야 함).
- 완료 기준: 7.2절 테스트 전부 + 기존 `LedgerAdjustmentIntegrationTest`, `LedgerSplitPaymentIntegrationTest`, `CalendarEventIntegrationTest` 통과.
- 검증: `cd backend && JAVA_HOME=/c/jdk-17 ./gradlew test --tests 'com.playdata.calen.ledger.service.LedgerEntryEditPolicyIntegrationTest' --tests 'com.playdata.calen.ledger.service.LedgerAdjustmentIntegrationTest' --tests 'com.playdata.calen.ledger.service.LedgerSplitPaymentIntegrationTest' --tests 'com.playdata.calen.calendar.CalendarEventIntegrationTest'`

### F1. 정책 순수 함수 + 단위 테스트

- 파일: `frontend/src/lib/ledgerEntryCapabilities.js`(신규), `frontend/tests/unit/ledgerEntryCapabilities.test.js`(신규, `node:test` + `node:assert/strict`, 기존 `ledgerSheetGroups.test.js` 형식).
- 구체 변경: 5.1절. 문구는 2.4절 표와 글자 하나까지 같게.
- 완료 기준: 7.1절 사례 전부 통과. `npm run test:unit`.

### F2. 환불·결제 취소 폼 추출

- 파일: `frontend/src/components/LedgerAdjustmentForm.vue`(신규), `LedgerAdjustmentDialog.vue`.
- 구체 변경: 5.3절. 스타일(`.adjustment-form`, `.adjustment-grid`, `.adjustment-note`, `.adjustment-total`, `.adjustment-actions`)은 폼 파일로 옮기고, 오버레이·다이얼로그 스타일은 래퍼에 남긴다.
- 완료 기준: `npm run build`; 기존 `e2e/ledger-adjustments.spec.js`의 환불 모달 부분이 이 단계 시점에는 그대로 동작(아직 관리 창은 기존 흐름).

### F3. `LedgerManageDialog` 추출 + 메뉴 단계 재구성

- 파일: `frontend/src/components/LedgerManageDialog.vue`(신규), `HouseholdTransactionSheet.vue`.
- 구체 변경:
  - 시트 148~244행 중 관리 창 전용 로직(`manageGroup`, `loadManageGroup`, `retryManageGroup`, `managedOrigin`, `managedHistory`, `managedSummary`, `managedPending`, `managedShowHistory`, `managedBadge`, `canRefund`, `canCancel`)과 461~523행 템플릿, 558~581행 관리 창 스타일을 새 파일로 옮긴다. 시트에는 `managedRow`, `managedSplit`, 트리거 포커스 처리, `chooseSplitAction`만 남긴다.
  - 메뉴 순서를 4.3절대로: 설명 문구 `거래 상태를 확인하고 필요한 처리를 선택합니다.` → 상태 줄(배지 + `caps.stateLine`) → 원거래 요약 → `할 수 있는 작업`(h3) + 행동 그리드(`caps.actions`, 4.5절 마크업) → 처리 이력(읽기 전용, `이 처리 되돌리기` 삭제, P1 수정, 되돌린 항목에 REVERTED이면 `처리 당시 금액` 표기) → `caps.note`.
  - `caps`는 `ledgerEntryCapabilities(managedOrigin, managedHistory, { historyStatus })`(`historyStatus`는 `manageGroup.loading ? 'loading' : manageGroup.error ? 'error' : 'ready'`, 그룹이 필요 없는 NORMAL은 'ready').
  - 분할 묶음 메뉴는 `splitRowCapabilities`로 2.5절대로.
  - 스타일 4.6절(버튼 잘림 수정 포함).
  - 이 단계에서 단계 전환이 필요한 행동은 임시로 기존 동작에 연결해도 된다(F4에서 교체): `refund`/`cancel` → 기존 `adjust-entry` emit, `uncancel`/`unrefund` → 기존 `revert-adjustment` emit, `delete` → 기존 `delete-entry`.
- 완료 기준: `npm run build`; 화면 A 상태에서 `거래 내용 수정`·`삭제`가 보이고, CANCELLED에서 `결제 취소 취소`가 첫 버튼으로 보인다(임시 확인은 F7 spec으로).

### F4. 창 안 단계 전환 + 상위 연결

- 파일: `LedgerManageDialog.vue`, `HouseholdTransactionSheet.vue`, `CalendarWorkspace.vue`, `HouseholdWorkspace.vue`.
- 구체 변경: 4.4절 단계(`cancel`, `refund`, `uncancel`, `unrefund`, `edit-limited`, `delete`)와 4.5절 Esc/포커스, 5.3절 props/emit 연결, `removeEntry` 정규화, `handleLedgerManageChanged`.
  - `edit-limited` 폼: 대분류는 `categoryGroups.filter(g => g.entryType === 'EXPENSE' && (g.active !== false || String(g.id) === String(origin.categoryGroupId)))`, 소분류는 선택한 대분류의 `details`(활성 + 현재값). 날짜 input에 `max=caps.maxEntryDate`(있을 때). 검증은 `validateLimitedEditDraft`, 본문은 `buildLimitedEditPayload`.
  - 분할 상세 intent 버튼은 그 수단의 관리 창을 `initialStep`으로 연다(5.2절).
- 완료 기준: `npm run build`, `npm run test:unit`; 7.3절 신규 spec의 시나리오 1~5 통과.

### F5. 진입점 통일

- 파일: `HouseholdTransactionSheet.vue`.
- 구체 변경: 4.1절(행 버튼 `관리`, `aria-haspopup`, 머리말 문구), 5.2절 시트 행 교체(`rowViews.caps`).
- 완료 기준: `npm run build`; 일반 거래 행에 `관리`·`공유`, 이력 거래 행에도 `관리`(·`공유` 조건부).

### F6. 다른 화면 일관성

- 파일: `CalendarWorkspace.vue`(1146~1149), `StatisticsWorkspace.vue`(225~227, 2505, 2590, 2593), `HouseholdWorkspace.vue`(`moveEntryFromSearch`, `editTravelLedgerEntry`).
- 구체 변경: 5.2절 표. 통합 달력(`integratedCalendarView.js`, `integrated-calendar/*`)은 라벨이 이미 공용 함수이고 수정·삭제 UI가 없으므로 **변경하지 않는다**(확인만).
- 완료 기준: `npm run build`; 통계 검색에서 REVERTED 거래에 인라인 `수정`·`삭제`가 보이고, CANCELLED 거래에는 없으며 `이동`을 누르면 편집 폼이 아니라 해당 날짜 거래 시트로 이동하고 안내가 뜬다.

### F7. e2e 갱신 + 신규 spec

- 파일: 신규 `frontend/e2e/ledger-transaction-manage-flow.spec.js`; 갱신 `ledger-manage-sheet.spec.js`, `ledger-adjustments.spec.js`, `ledger-adjustment-grouping.spec.js`, `ledger-split-sheet-groups.spec.js`, `record-sharing.spec.js`, `quick-entry-responsive.spec.js`, `transaction-sheet-date-navigation.spec.js`, `mobile-usability.spec.js`.
- 구체 변경: 7.3절.
- 완료 기준: 위 spec 전부 desktop·mobile 통과(+ `integrated-calendar.spec.js`, `ledger-split-payments.spec.js` 회귀 통과).

### F8. 시각 확인

- 신규 spec 안에서 `testInfo.outputPath(...)`로 NORMAL / CANCELLED / PARTIALLY_REFUNDED / REVERTED 관리 창 메뉴, `uncancel` 확인 단계, `edit-limited` 단계를 데스크톱·모바일 × 라이트·다크(`localStorage['calen-theme-mode']='toss'` 후 reload)로 캡처해 직접 열어 본다.
- 확인 항목: 설명 줄 잘림 없음(계산값 검사 7.3절), 점선 비활성 버튼이 다크에서 구분됨, danger 버튼 대비, 모바일 가로 넘침 없음, 이력 날짜·시간 사이 공백.
- 완료 기준: 최종 `npm run build`, `npm run test:unit`, F7 e2e 전체, B1 백엔드 명령 재실행 통과.

---

## 7. 테스트 계획

### 7.1 프론트 단위(`tests/unit/ledgerEntryCapabilities.test.js`)

1. 5.1절 입출력 예 전부.
2. 상태 우선순위: `cancelledAt` + `refundedPrincipal>0`(불가능하지만) → `CANCELLED`.
3. `REFUNDED`: `refundedPrincipal === amount` → 행동 `['unrefund', 'edit', 'cancel'(false), 'delete'(false)]`, `refund` 없음, `refundableAmount === 0`.
4. 센트 계산: `amount: '10000.10', refundedPrincipal: '0.20'` → `refundableAmount === 9999.9`.
5. 활성 환불 2건 → `unrefund` 설명 `적용 중인 환불 2건 중 하나를 골라 되돌립니다`, `activeRefunds` 시간순.
6. 활성 환불 1건 → 설명 `환불 입금 ₩2,500(2026-10-22)을 되돌립니다`.
7. `historyStatus: 'error'` + adjustments null → `uncancel` 사유 `처리 이력을 불러오지 못했습니다. 아래 '다시 시도'를 눌러 주세요.`
8. CANCELLED인데 adjustments에 활성 취소 없음(전부 되돌림) + `historyStatus:'ready'` → `uncancel` 사유 `적용 중인 취소 기록을 찾을 수 없습니다. 관리자에게 문의해 주세요.`
9. `maxEntryDate`: 활성 기록 날짜 `['2026-10-25','2026-10-23']` + 되돌린 `'2026-10-21'` → `'2026-10-23'`.
10. `bulkSelectable`·`canShare`가 현행 조건과 같음(REVERTED: bulk false, share true / CANCELLED: share false).
11. `splitRowCapabilities`: 수단 A CANCELLED + 수단 B NORMAL → `canEditGroup false`(사유 2.5절), intents에 `uncancel`, `refund`, `cancel`, `delete` 포함.
12. `validateLimitedEditDraft`: 각 오류 문구, 정상은 `''`.
13. 입력 객체 불변(`structuredClone` 비교).

기존 `ledgerSheetGroups.test.js`, `integratedCalendarCore.test.js`, `integratedCalendarView.test.js`는 변경 없이 통과해야 한다.

### 7.2 백엔드 통합(`LedgerEntryEditPolicyIntegrationTest`)

| 테스트 | 절차 | 기대 |
|---|---|---|
| `revertedEntryAllowsFullEditAndKeepsVoidedHistory` | purchase(100) → cancel(SETTLEMENT) → revert → update(금액 150, 제목 변경, 날짜 SETTLEMENT+1일 — 되돌린 기록 날짜보다 늦게, E3) | 성공. 원거래 금액 150. `getAdjustmentGroup`의 기록 금액 100, `adjustmentVoidedAt` 유지. `statistics.getOverview(새 날짜)` 지출 150. 이어서 처리일 SETTLEMENT+1일로 직접 만든 `LedgerAdjustmentRequest`(REFUND, principal 151)는 400, principal 150은 성공(헬퍼 `refund()`는 날짜가 SETTLEMENT로 고정이라 원거래 날짜보다 빨라 쓰지 않는다) |
| `revertedEntryMayChangeType` | cancel → revert → update(entryType INCOME, 수입 대분류) | 성공. 이후 `adjustments.create` 환불 시 400 |
| `activeCancellationAllowsOnlyNonFinancialFields` | cancel(SETTLEMENT) → update(제목·메모·시간·날짜 PURCHASE−1일) | 성공. `cancelledAt` 유지, `financiallyExcluded` true, 금액 100 |
| 〃 잠금 필드 | 같은 상태에서 금액 120 / entryType INCOME / 다른 결제수단 / 할부 3개월 / travelPlanId | 각각 400, 메시지에 `금액`/`수입/지출 구분`/`결제수단`/`할부`/`여행 연결` 포함, 엔티티 불변 |
| 〃 날짜 상한 | 같은 상태에서 날짜 SETTLEMENT+1일 | 400, 메시지에 `2026-03-15` 포함 |
| `activeRefundLocksAmountAndKeepsRefundedPrincipal` | refund(30) → update(금액 20) | 400. `refundedPrincipal` 30 유지. 제목만 변경은 성공 |
| `foreignLimitedEditKeepsAmountAndRateMetadata` | `entryService.create`로 USD 10, 환율 1300 지출 → 저장된 `amount`, `exchangeRateDate`, `exchangeRateProvider` 기록 → refund(1000) → 같은 외화 필드 + 새 날짜(SETTLEMENT 이전)로 update | 성공, 세 값 불변 |
| `adjustmentRecordsAreImmutable` | cancel 기록 id로 update/delete | 둘 다 400 |
| `deleteBlockedWhileActive` | cancel → delete / (새 거래) refund → delete | 400 `MSG_DELETE_ACTIVE` |
| `deleteRevertedCascadesToTrashAndRestoresTogether` | cancel → revert → delete(false) | `getEntries` 범위에서 원거래·기록 모두 없음. `getDeletedEntries` 내용에 원거래만(기록 없음). `restore(기록 id)` 400. `restore(원거래)` 후 `getAdjustmentGroup` 기록 1건(voided) 복구 |
| `permanentDeleteRemovesHistory` | cancel → revert → delete(true) | `entries.findById(원거래)`, `entries.findById(기록)` 모두 empty |
| `bulkAndSplitStayStrict` | cancel → revert → `bulkUpdate([원거래])` | 400(문구 `MSG_STRICT`) |
| `httpUpdateRequiresOwner` | 다른 사용자로 `PUT /api/entries/{id}` (csrf) | 404 |

기존 `LedgerAdjustmentIntegrationTest.cancellationPreservesBothRecords…`(delete 400, 처리 기록 update 400)는 새 정책에서도 그대로 통과해야 한다.

### 7.3 e2e

공통: `mockPersonalization`(`e2e/fixtures/calendarPersonalization.js`)을 먼저 호출하고, 그 뒤에 `page.route`를 등록한 다음 `page.reload()`(나중 등록이 우선). 시계 `page.clock.setFixedTime(new Date('2026-10-22T12:00:00'))`. 카테고리·결제수단 목은 `ledger-adjustments.spec.js` 11~17행 형식(지출 `식비`(101, details 포함), 수입 `기타 수입`(102), 카드 103, 현금 104). **관리 창 버튼은 정확한 이름으로 찾는다**: `manage.getByRole('button', { name: '환불', exact: true })` 등. 비활성 확인은 `toHaveAttribute('aria-disabled', 'true')`.

**신규 `ledger-transaction-manage-flow.spec.js`** — 상태를 가진 목 서버 하나(원거래 id 7 `검증용 결제` ₩100,000, 지출, 카드 103)로 구현:
- `GET /api/entries…`: 픽스처가 `entries` 배열을 그대로 돌려준다(배열을 직접 변경).
- `POST **/api/entries/7/adjustments`: 기존 `ledger-adjustments.spec.js` 18~38행 로직(취소 기록 id 9, 환불 기록 id 8·10…).
- `POST **/api/entries/{id}/adjustments/revert`: 기록 `adjustmentVoidedAt`, `financiallyExcluded=true`; 취소면 원거래 `cancelledAt=null, financiallyExcluded=false`; 환불이면 `refundedPrincipal -= principal`. 요청을 배열에 기록.
- `GET /\/api\/entries\/(7|8|9|10)\/adjustment-group$/`: `{ origin, adjustments }` 현재 상태.
- `PUT **/api/entries/7`: 본문 기록. 활성 효과가 있는데 `amount`·`entryType`·`paymentMethodId`가 원거래와 다르면 `400 { message: '결제 취소·환불이 적용 중인 거래는 금액을(를) 바꿀 수 없습니다. …' }`, 아니면 원거래에 반영 후 200 원거래 JSON.
- `DELETE **/api/entries/7`: 기록 후 배열에서 제거, 204.

시나리오:
1. `input → payment cancellation → cancel the cancellation → full edit` (사용자 흐름 1→2→3):
   행 버튼 `관리`(이름 정확히) → 메뉴에 `거래 내용 수정`·`환불`·`결제 취소`·`삭제` → `결제 취소` 클릭 → `manage.getByRole('region', { name: '결제 취소 기록' })` → `결제 취소 저장` → 창 유지, 배지 `결제 취소됨`, `role=status`에 `결제 취소를 저장했습니다`, 첫 버튼 `결제 취소 취소`, `삭제` aria-disabled + 사유 문구, 시트 행 금액 취소선 → `결제 취소 취소` → region `결제 취소 취소` → `결제 취소 취소하기` → revert 요청 `POST …/9/adjustments/revert` 1회, 배지 `취소 되돌림`, `거래 내용 수정` 설명에 `모든 항목`, `삭제` 활성 → `거래 내용 수정` 클릭 → 시트 닫힘, `거래 수정 중` 표시, `거래 제목` 값 `검증용 결제`.
2. `cancelled entry allows limited edit inside the window`: CANCELLED 상태에서 `거래 내용 수정` → region `거래 내용 수정`에 고정 항목 문구, 날짜 input `max`가 취소일 → 제목 `검증용 결제 수정`, 메모 입력 → `저장` → PUT 본문 `amount === 100000`, `entryType === 'EXPENSE'`, `paymentMethodId === 103`, `title === '검증용 결제 수정'`, `entryTime === '12:30'` → 결과 메시지 `거래 내용을 수정했습니다` → 날짜를 취소일 다음 날로 바꾸면 `저장` 비활성 + 검증 문구.
3. `refunds can be added and one of several can be cancelled`: `환불` → region `환불 기록`(기존 라벨 `차감액 (원)` 등) → 30,000 저장 → `부분 환불`, 버튼 `환불`과 `환불 취소`가 모두 있고 각각 정확한 이름으로 1개씩, `결제 취소` aria-disabled(환불 사유) → 다시 20,000 환불 → `환불 취소` → fieldset `되돌릴 환불 선택` 라디오 2개, 선택 전 `환불 취소하기` 비활성 → 두 번째 선택 → 실행 → 해당 id revert 요청.
4. `reverted entry can be deleted with its history from the window`: REVERTED 상태 → `삭제` → region `거래 삭제`에 `되돌린 처리 이력 1건도 함께 이동` → `삭제하기` → `window.confirm` **없이** DELETE 1회(`page.on('dialog')`가 호출되지 않음을 확인), 창 닫힘, 행 사라짐.
5. `escape steps back before closing`: 메뉴 → `결제 취소` 단계 → Esc → 메뉴(region 없음, 창 유지, 포커스가 `결제 취소` 버튼) → Esc → 창 닫힘, 포커스가 행 `관리` 버튼.
6. `action descriptions are not clipped` (데스크톱·모바일 × 라이트·다크): 각 상태 메뉴에서 모든 `[data-manage-action]` 버튼에 대해 `paddingTop > 0`이고 `button.getBoundingClientRect().bottom - small.getBoundingClientRect().bottom >= 8`, 창 가로 넘침 없음. F8 캡처.

**기존 spec 갱신**

| spec | 변경 |
|---|---|
| `ledger-manage-sheet.spec.js` | 행 버튼 `수정` → `관리`(20, 37, 44, 70, 82, 87행 부근). 삭제는 `window.confirm` 대신 창 안 단계: `삭제` → region `거래 삭제` → `돌아가기`(삭제 0회) / `삭제하기`(삭제 1회). 두 번째 테스트: `/^환불/` → `{ name: '환불', exact: true }`, `/^결제 취소/` → exact, `/^삭제/` → exact. 여행 연결 지출: `결제 취소` 버튼이 aria-disabled이고 설명에 `여행 기록과 연결된 거래는 결제 취소할 수 없습니다.` 부분 환불 지출: `환불`·`환불 취소`·`거래 내용 수정`(설명 `제목·메모·분류·날짜·시간만`) 활성, `결제 취소`·`삭제` aria-disabled, 안내 `처리가 적용 중이라 금액·…`, 옛 문구 `기록 보존을 위해` 없음 |
| `ledger-adjustments.spec.js` | `수정` → `관리`. `manage.getByRole('button', { name: /환불/ })` → exact `환불`. 환불 입력은 `page.getByRole('dialog', { name: '환불 기록' })` → `manage.getByRole('region', { name: '환불 기록' })`(레이아웃 검사는 region 기준). 저장 후 `expect(refund).toHaveCount(0)` → 창 유지 + 결과 메시지. 이력의 `이 처리 되돌리기` → 주 행동 `환불 취소` → `환불 취소하기`(confirm 없음). 결제 취소: `결제 취소 기록` region → `결제 취소 저장` → `결제 취소됨` |
| `ledger-adjustment-grouping.spec.js` | 테스트 1: `결제 취소`/`환불` exact로, 추가로 `거래 내용 수정`·`삭제` 보임(화면 A 개선 확인). 테스트 2: `이 처리 되돌리기` + confirm → `결제 취소 취소` → `결제 취소 취소하기`. 테스트 3: `/^환불/` → exact `환불`, `이 처리 되돌리기` 기대 → `환불 취소` 버튼 기대 |
| `ledger-split-sheet-groups.spec.js` | 16, 53, 70행 `수정` → `관리`; 97, 110행 `수정·처리` → `관리` |
| `record-sharing.spec.js` | 357행 루프 `['수정','공유']` → `['관리','공유']`, 371행 `수정` → `관리`, 삭제 접근성은 관리 창의 `삭제` 버튼 확인으로 |
| `quick-entry-responsive.spec.js` | 162행 `수정` → `관리` |
| `transaction-sheet-date-navigation.spec.js` | 149행 `수정` → `관리` |
| `mobile-usability.spec.js` | 215행 루프 `['수정','공유','삭제']` → `['관리','공유']` |

---

## 8. 리스크와 비범위

### 리스크

- R1. **프론트 정책과 서버 정책의 불일치**: 같은 규칙이 JS와 Java에 있다. 7.1과 7.2의 사례를 같은 표(2.4절)에서 뽑아 맞춘다. 서버가 최종 판정이므로 불일치 시에도 데이터는 안전하고, 관리 창은 400 문구를 보여 주고 그룹을 다시 불러온다.
- R2. **LIMITED 수정의 외화 재계산**: 서버가 원화 금액·환율 메타데이터를 원복하므로, 잠금 검사(요청 외화 값 = 저장 값)를 빼먹으면 외화 금액 변경이 조용히 무시될 수 있다. 의사코드 순서(검사 → apply → 원복)를 지킨다.
- R3. **삭제 묶음 식별을 `deletedAt` 일치로 함**: 반드시 초 단위로 절삭한 같은 값을 원거래·기록에 넣는다. 다른 경로(휴지통 비우기 등)는 영향 없음.
- R4. **e2e 대량 갱신**: 행 버튼 이름 변경과 접근성 이름 정확화로 8개 spec을 고친다. 테스트 의미(무엇을 검증하는지)는 유지하고 선택자만 바꾼다.
- R5. **다크 테마 전역 CSS**: aria-disabled 표시를 배경에 의존하지 않는다(점선 + 흐림). danger 배경은 활성일 때만 `!important`.
- R6. **관리 창이 열린 채 목록이 새로 고쳐짐**: 창은 서버 그룹 데이터를 기준으로 렌더링하고 `seq`로 늦은 응답을 버린다. LIMITED 수정으로 날짜가 바뀌면 시트에서 행이 사라질 수 있으므로 닫을 때 포커스 대체 대상(시트 제목)을 둔다.
- R7. 메인 편집 폼이 시간 미입력 거래를 `00:00`으로 보내는 기존 동작(E17)은 FULL 수정에서 그대로 남는다.

### 비범위

- N1. DB 스키마·마이그레이션(`@Version` 낙관적 락 포함). 필요해지면 별도 작업으로 `version` 컬럼 + `If-Match` 계약을 설계한다.
- N2. 일괄 수정·분할 묶음 수정에서 REVERTED 거래 허용(현행 엄격 규칙 유지).
- N3. 변경 이력 복구(`applySnapshot`)를 새 정책으로 완화. 지금은 이력 있는 거래 복구를 400으로 거부한다.
- N4. 처리 기록의 제목·카테고리를 원거래 수정에 맞춰 갱신하는 동작(D7로 보존).
- N5. 그룹 응답에 서버 capabilities 필드 추가(D6에서 기각, 필요 시 재검토).
- N6. 사용하지 않게 되는 `adjust-entry`/`revert-adjustment`/`show-adjustments` 이벤트 연결, `LedgerAdjustmentHistoryDialog.vue`, HouseholdWorkspace `openLedgerAdjustment`/`undoLedgerAdjustment` 제거.
- N7. 통합 달력에서 바로 관리 창 열기(현재는 `가계부 원본 보기`로 이동만).
- N8. 관리 창 포커스 트랩(현행 다이얼로그들과 같은 수준 유지).
