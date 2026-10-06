# 가계부 임베딩 자동 동기화

구현일: 2026-10-06. 상태: 소스 구현 및 Java 컴파일 확인. 운영 미적용.

## 범위와 사용 전 조건

기존 문서 형식 v1, UUIDv5, 소유자 범위, OCI `/v1/documents/upsert`·`delete` 계약을 유지한다. 실제 저장한 거래와 등록된 정기 규칙만 대상이며 비활성 규칙도 `active=false`로 포함한다. OCR 미승인 결과·가져오기 미리보기는 대상이 아니다. AI 분석 연결·UI·검색·챗봇·기존 데이터 백필은 변경하지 않았다.

새 `ledger_embedding_sync_jobs`는 **Flyway `V20261006_032__ledger_embedding_sync_jobs.sql`로만** 생성한다. Hibernate 관리 엔티티나 startup DDL runner를 추가하지 않았다. 기본 `DB_MIGRATION_ENABLED=false`, `ddl-auto=update`만으로 이 JDBC-managed 테이블이 생성되지 않는다. 캡처 또는 API 활성화 전에 마이그레이션이 반드시 필요하다. 운영 DB/서버에는 적용하지 않았다.

| API enabled | capture-enabled | 동작 |
|---|---|---|
| false | false | 기본값. 원본 저장 동작 그대로, 작업 기록·원격 요청 없음 |
| false | true | 변경과 같은 DB 트랜잭션에 작업만 기록. 원격 요청 없음 |
| true | 어느 값이든 | 작업 기록 + 별도 작업자의 API 전송 |

원격 장애나 계획된 중단 때는 `CAPTURE_ENABLED=true`를 유지한 채 API만 비활성화해야 대기열을 유지한다. 둘 다 false였던 기간에 새로 만든 자료는 나중에 활성화해도 자동 백필하지 않는다. 이미 outbox에 있는 기록의 재조정을 비활성 기간 전체의 복구로 간주해서는 안 된다.

## 원자적 기록과 변경 경로

Hibernate flush의 post insert/update/delete listener가 원본을 쓴 **동일 JDBC 연결**로 outbox DML을 실행한다. 원본과 작업이 함께 커밋/롤백된다. listener는 원격 요청·다른 엔티티 조회·별도 트랜잭션을 만들지 않으며 JPA dirty checking 수정도 포함한다.

| 확인한 경로 | 포착 방식 |
|---|---|
| `LedgerEntryService.create/update/bulkUpdate` | 거래 insert/update 이벤트 |
| CSV 등록·`LedgerExcelImportService` 직접 repository 저장 | 거래 insert 이벤트 |
| soft/permanent delete, 휴지통 복구, 변경 이력 복원 | 거래 update/delete 이벤트. 삭제는 최신 원본 조회 결과로 판단 |
| `LedgerEntryService.emptyTrash` JPQL bulk delete | repository fragment: flush → 소유자 범위 tombstone INSERT SELECT → bulk delete, 같은 트랜잭션 |
| `RecurringLedgerService` 생성·수정·active 변경·삭제 | 규칙 insert/update/delete 이벤트 |
| 정기 규칙 자동 생성·승인 후 생성한 실제 거래 | 기존 거래 생성 경로 |
| `CategoryService`/`PaymentMethodService` 거래 재할당 | 거래 update 이벤트 |
| 분류명·결제수단 이름/종류의 JPA 변경 | 관련 거래와 규칙 INSERT SELECT. 표시 순서/숨김은 문서 필드가 아니므로 fan-out 안 함 |
| `LedgerTravelBridgeService`/`TravelService` 가계부 생성·수정·여행 링크 변경 | 기존 `LedgerEntryService` 경유 거래 이벤트 |
| `RecordSharingService` 수락 후 내 거래 생성 | 기존 생성 이벤트. 미수락 공유 요청 제외 |
| `DataInitializer` 실제 신규 데모 거래 저장 | 캡처 활성 시 saveAll insert 이벤트. 기존 자료 전체 적재 없음 |

owner/id는 신뢰된 DB 엔티티에서 결정한다. 분류/수단 삭제 서비스의 기존 정책은 거래만 재할당한다. 정기 규칙 참조 때문에 원본 삭제가 FK 오류로 롤백되면 작업도 롤백된다. 정기 규칙 자체의 분류/수단 변경은 기존 규칙 수정에서 포착한다. 현재 없는 이름 수정 API나 새로운 참조 재할당 정책은 추가하지 않았다.

향후 JPQL/native bulk UPDATE/DELETE, DB 직접 수정·덤프 복구는 Hibernate 이벤트를 통과하지 않는다. 새 bulk 경로는 같은 트랜잭션에서 명시적 캡처해야 한다. 전체 DB 백업 복구와 Qdrant 재정합은 별도 승인·운영 작업이며 이번 변경 이력 복원과 다르다.

## 순서·재시작·다중 인스턴스

- PK `(owner_id, record_type, source_id)`: 기록별 한 행으로 합친다. 본문은 저장하지 않는다. LedgerEntry의 존재하지 않는 updatedAt/version 대신 **outbox revision**을 원자적으로 증가시킨다.
- PROCESSING 중 새 수정은 revision만 증가시키고 기존 claim token/claimed revision을 유지한다. 이전 전송으로 새 변경을 완료 처리하거나 누락시킬 수 없다.
- 짧은 READ COMMITTED 트랜잭션에서 조건부 선점한다. 준비 시 해당 작업 행만 잠그고 최신 소유자 범위 원본을 읽어 DTO를 만든다. source 행은 잠그지 않는다.
- 준비 전에 revision이 바뀌면 **같은 열린 트랜잭션**에서 재대기시킨다. 같은 행 잠금을 잡고 다시 REQUIRES_NEW로 해제하지 않는다. API 직전에도 token/revision/expiry를 확인한다.
- 원본이 존재하고 거래가 삭제되지 않았다면 UPSERT, 없거나 soft-deleted 거래라면 기존 UUID에 DELETE. 삭제 후 복구도 같은 UUID의 최신 상태를 전송한다. inactive rule은 삭제하지 않는다.
- HTTP는 원본 및 선점·준비·완료 DB 트랜잭션 **밖에서** 실행한다. 최대 4개 선점 후 upsert/delete로 나눠 순차 전송한다. 서버가 임베딩하므로 별도 선행 embed 요청을 하지 않는다.
- 공유 `WorkLeaseService`가 여러 백엔드의 이 작업자 API 실행을 1개로 제한한다. HTTP는 별도 단일 thread executor에서 실행하므로 scheduler의 lease heartbeat를 막지 않는다. 로컬 poll 중복도 방지한다.
- 성공/실패는 유효한 token/claimed revision/lease 조건으로만 갱신한다. source revision이 바뀌면 PENDING이며 구버전 실패는 신버전 재시도 횟수를 소모하지 않는다.
- 재시작 시 PROCESSING은 기본 15분 lease 만료 후 회수하며 DB 시간을 사용한다. 1분 recovery delay 뒤 최신 상태를 다시 전송한다. 원격 성공 직후 앱이 죽어도 같은 UUID 재전송은 멱등하다.
- 작업/삭제 tombstone에 FK가 없으므로 물리 삭제 후에도 재시도가 가능하다. 완료 행을 자동 제거하지 않는다.
- text_hash가 같아도 금액·날짜·결제수단·active 등 메타데이터 변경을 처리한다. 해시만으로 갱신을 생략하지 않는다.

### 원격 fencing 제한과 보정

OCI API에는 조건부 revision 쓰기/삭제가 없다. 로컬 token은 **로컬 DB 완료만 보호하며 원격 저장을 fence하지 않는다**. lease를 잃은 프로세스의 오래된 UPSERT가 새 DELETE 뒤에 원격에서 완료되면 삭제 자료가 일시적으로 다시 생길 수 있다. HTTP timeout은 서버 작업 취소 보장이 아니다. 강한 일관성/exactly-once를 보장하지 않는다.

긴 lease, 인스턴스 간 직렬 실행, 최신 원본 재조회·새 revision 재대기로 충돌을 줄인다. 추가로 **이미 outbox에 등록된 SUCCEEDED 기록/삭제 tombstone만** 기본 24시간마다 최신 상태를 재전송한다. 기존 전체 데이터 백필이나 원격 조회/비교가 아니며, 그대로인 본문도 upsert 시 추론 비용이 생긴다. 원격 지연이 유한하고 작업자가 계속 성공하면 재조정 가능하다. FAILED나 기능 중단 상태에서는 정합을 보장하지 않는다. 완전한 오래된 요청 차단에는 향후 원격 API revision/fencing 계약이 필요하다.

## 재시도·보안

기본 최대 8회. 일시 오류는 10 → 20 → 40 → 80 → 160 → 320 → 640초 지연으로 재시도하며 설정 지연 상한은 15분이다. timeout/IO, HTTP 408·429·5xx는 기존 클라이언트 판정을 따른다. 계약 위반·일반 4xx·잘못된 문서는 영구 실패로 처리한다. 동일 revision이 상한을 소모하거나 lease 회수를 반복하면 FAILED로 남긴다. 이후 원본 새 변경은 revision 증가와 함께 횟수를 초기화하고 다시 PENDING이 된다.

outbox에는 식별자·revision·상태·시각·횟수·선점 token·고정 오류 코드만 저장한다. 본문/메모/질문/키/원격 응답/exception 원문은 저장하거나 새 로그에 출력하지 않는다. 키는 기존 비밀 환경변수만 사용하며 예시 파일은 빈값이다. Qdrant 관리자 키나 직접 쓰기는 추가하지 않았다.

원격 장애는 이미 커밋한 거래를 실패시키지 않는다. **캡처 자체의 DB 장애나 미적용 스키마**는 원자성을 지키기 위해 원본도 롤백한다. outbox 실패를 숨기고 원본만 커밋하는 fail-open은 아니다.

## 설정·활성화 — 실행하지 않은 운영 절차

기존 API 설정에 다음을 추가하고 backend Compose 세 파일로 전달했다.

```dotenv
APP_EMBEDDING_ENABLED=false
APP_EMBEDDING_CAPTURE_ENABLED=false
APP_EMBEDDING_SYNC_POLL_INTERVAL=5s
APP_EMBEDDING_SYNC_LEASE_DURATION=15m
APP_EMBEDDING_SYNC_MAX_ATTEMPTS=8
APP_EMBEDDING_SYNC_RETRY_BASE_DELAY=10s
APP_EMBEDDING_SYNC_RETRY_MAX_DELAY=15m
APP_EMBEDDING_SYNC_RECOVERY_DELAY=1m
APP_EMBEDDING_SYNC_RECONCILE_INTERVAL=24h
```

1. 별도 승인한 staging에서 DB 백업 후 기존 Flyway adoption 지침을 따라 032까지 적용한다. migration-discipline 점검과 실제 MariaDB 버전 리허설이 필요하며 이번에는 실행하지 않았다.
2. 기본 off 상태에서 실행 환경을 확인한다. 새 DDL runner/자동 백필은 없다.
3. 스키마 확인 후 `CAPTURE_ENABLED=true`, `ENABLED=false`로 전환해 기록/롤백을 검증한다. 운영 실자료 대신 별도 승인한 합성 자료로 확인한다.
4. Tailscale 도달성, 기존 OCI 키·컬렉션 계약 확인 후 `ENABLED=true`로 전환한다. URL은 기존 `http://100.75.225.16:8000`; API 키는 OCI CALEN_API_KEY와 같은 비밀값이다.
5. lease는 혼합 upsert/delete 두 API 시간, 배치 source-read DB 예산, 60초 여유보다 길어야 한다. 짧으면 startup 설정 검증이 거부한다. 실제 시간 측정/상한 보장이 아니므로 DB/서버가 장기간 지연되면 회수될 수 있다.
6. 오래된 PENDING/PROCESSING·FAILED를 점검한다. shutdown 선점은 즉시 해제하지 않고 만료/회수한다. timeout된 원격 작업과 다음 전송의 즉각적인 경합을 줄인다.

읽기 전용 점검 예시(미실행):

```sql
SELECT state, COUNT(*) AS jobs, MIN(next_attempt_at) AS earliest_due
FROM ledger_embedding_sync_jobs GROUP BY state;

SELECT owner_id, record_type, source_id, revision, applied_revision,
       attempts, last_error_code, updated_at
FROM ledger_embedding_sync_jobs WHERE state = 'FAILED'
ORDER BY updated_at DESC LIMIT 100;

SELECT owner_id, record_type, source_id, revision, lease_until
FROM ledger_embedding_sync_jobs
WHERE state = 'PROCESSING' AND lease_until <= CURRENT_TIMESTAMP(6);
```

원인을 해결한 뒤 운영자의 별도 승인으로 **특정 FAILED 기록 하나만** 재대기시키는 예시다. PROCESSING token을 강제로 지우거나 전체 큐를 초기화하지 않는다.

```sql
UPDATE ledger_embedding_sync_jobs
SET state = 'PENDING', attempts = 0, next_attempt_at = CURRENT_TIMESTAMP(6),
    last_error_code = NULL, updated_at = CURRENT_TIMESTAMP(6)
WHERE owner_id = :owner_id AND record_type = :record_type
  AND source_id = :source_id AND state = 'FAILED';
```

Rollback: API와 capture를 모두 끄고 작업자를 중단한 후 이전 앱으로 되돌리는 절차를 별도 계획한다. outbox를 삭제하지 말고 DB 백업과 보존한다. 앱 롤백은 이미 생성한 Qdrant 포인트를 되돌리지 않으므로 별도 원격 재정합이 필요할 수 있다. legacy SchemaUpdater는 변경/추가/삭제하지 않았다.

## 검증 상태·남은 일

- `compileJava --console=plain` 성공. 테스트 태스크는 실행하지 않았다.
- 소스에서 동일 연결·dirty checking·bulk delete, 최신 상태, token/revision, HTTP/DB 트랜잭션 분리, 민감정보 로그 제한을 점검했다. 런타임 검증은 아니다.
- 새 테스트 작성·기존 테스트 실행·MariaDB migration 적용·동시성/롤백/재시작/오류 주입 시험·OCI upsert/delete는 **미수행**이다. 이전 69개 기반 계약 테스트를 이번 worker 검증으로 표시하지 않는다.
- repository fragment는 ObjectProvider로 캡처를 지연 조회해 JPA-only 슬라이스에서 일반 Component 부재를 허용한다. 전체 앱에서는 component가 등록되므로 bulk 삭제 캡처가 수행된다. 실제 Spring context/listener/fragment 등록도 별도 runtime 검증 대상이다.
- 운영 DB 반영·백필·서버 변경/재시작·배포·커밋/푸시는 수행하지 않았다.

## 주요 변경 파일

- `ledger/embedding/sync/`: 설정, outbox SQL/캡처, Hibernate listener/등록, 영속 선점/완료 저장소, 별도 worker.
- `ledger/repository/LedgerEmbeddingTrashOperations*`, `LedgerEntryRepository`: bulk 삭제의 원자적 캡처.
- `LedgerEmbeddingDocumentService.findCurrentForSync`: 최신 소유자 범위 Optional 조회. 없음을 DELETE로 판단할 때 참여 트랜잭션을 예외로 rollback-only 처리하지 않는다.
- Flyway 032, `application.yml`, 환경 예시 2개, backend Compose 3개, 관련 운영 문서.
