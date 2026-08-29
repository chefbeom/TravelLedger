# 정기 입출금

정기결제 화면은 반복되는 지출과 수입을 하나의 규칙으로 관리한다. 지출과 수입 모두 기존 가계부 거래 생성 흐름을 사용한다.

## 등록 방식

- `자동 등록`: 예정일의 스케줄러가 거래를 바로 생성한다.
- `확인 후 등록`: 예정일에 대기 내역만 만들고, 사용자가 가계부의 정기결제 화면에서 등록 또는 건너뛰기를 선택한다.

## 반복 주기

- `달력 기준`: `달 반복 주기(개월)`와 날짜를 기준으로 실행한다. 1개월은 매월, 2개월은 격월, 3개월은 분기, 12개월은 매년이며 18개월처럼 임의의 개월 수도 입력할 수 있다.
- `N일마다`: 시작일을 1회차 기준일로 삼아 4·23·45일 등 입력한 일수 간격으로 실행한다. 이 방식은 달력의 날짜가 아니라 실제 경과 일수를 계산한다.
- 달력 기준에서 29~31일처럼 해당 월에 날짜가 없으면 그 달의 마지막 날을 예정일로 사용한다.
- 달력 기준의 주기 월은 시작일이 속한 달을 기준으로 고정한다. 예를 들어 시작일이 2026-08-01이고 2개월·15일이면 8월 15일, 10월 15일, 12월 15일에 실행한다.
- `달 반복 주기`는 1~120개월, `N일마다`의 간격은 1~3650일로 입력할 수 있다.
- 동일 규칙과 동일 예정일은 occurrence 고유 제약으로 한 번만 처리한다.
- 앱이 예정일에 실행되지 않은 경우 과거 날짜를 임의로 소급 생성하지 않는다. 필요한 거래는 사용자가 일반 거래로 직접 등록해야 한다.

## 화면과 API

가계부의 `정기결제` 탭에서 규칙을 등록·수정·일시정지하고 확인 대기 내역을 처리한다.

- `GET /api/recurring-ledger/rules`
- `POST /api/recurring-ledger/rules`
- `PUT /api/recurring-ledger/rules/{ruleId}`
- `DELETE /api/recurring-ledger/rules/{ruleId}`: 규칙을 삭제하지 않고 일시정지한다.
- `GET /api/recurring-ledger/occurrences/pending`
- `POST /api/recurring-ledger/occurrences/{occurrenceId}/approve`
- `POST /api/recurring-ledger/occurrences/{occurrenceId}/skip`

모든 API는 로그인한 사용자의 규칙과 대기 내역만 조회·변경한다. `확인 후 등록` 대기 내역은 승인 시점에 기존 `LedgerEntryService`로 거래를 생성하므로 분류·결제수단 소유권 검증도 기존 흐름과 동일하다.

규칙 응답에는 `scheduleType`, `monthInterval`, `dayOfMonth`, `intervalDays`가 포함된다. 기존 요청에서 `scheduleType`과 `monthInterval`을 생략하면 `MONTHLY_DATE`와 1개월로 해석한다.
## 스케줄 설정

기본 스케줄은 `Asia/Seoul` 기준 매일 00:05이며, 환경변수로 조정할 수 있다.

```dotenv
APP_LEDGER_RECURRING_ENABLED=true
APP_LEDGER_RECURRING_CRON=0 5 0 * * *
APP_LEDGER_RECURRING_ZONE=Asia/Seoul
```

운영에서 기능을 잠시 멈추려면 `APP_LEDGER_RECURRING_ENABLED=false`로 설정한다. 이미 생성된 확인 대기 내역은 기능을 다시 켠 뒤에도 별도로 승인하거나 건너뛸 수 있다.
