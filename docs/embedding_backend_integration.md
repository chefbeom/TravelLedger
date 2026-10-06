# 임베딩 백엔드 연동 기반

작성일: 2026-10-05 (Asia/Seoul)

## 이번 단계의 범위

설정 클래스, OCI 수신 API 클라이언트와 요청·응답 모델, 가계부 문서 생성기, 환경변수 전달 설정을 추가했다. 이 문서는 구현 규약과 운영자가 준비할 내용을 설명한다. 테스트·빌드·운영 호출로 검증한 결과를 의미하지 않는다.

2026-10-06 후속 구현에서 새 거래·정기 규칙의 생성·수정·삭제·복구를 영속 outbox와 별도 작업자에 연결했다. **마이그레이션 032를 먼저 적용한 후** `APP_EMBEDDING_ENABLED=true`이면 새 변경을 자동 동기화한다. 기존 자료 전체의 일괄 적재, Qdrant 검색, 챗봇 UI, LLM 응답 생성은 구현하지 않았다. 기존 AI 가계부·이미지 분석 연결은 변경하지 않았다. 설정·재시도·적용 순서·제한은 [자동 동기화 운영 문서](embedding_automatic_sync.md)를 따른다. 이번 후속 단계에서는 테스트를 추가하거나 실행하지 않고 Java 컴파일만 확인했다.

기존 사용자 작업물인 `deploy/oci/embedding/`는 변경하지 않았다. 확인한 체크아웃에는 `app.embedding`이 없어 기존 `app` 아래에 한 번만 추가했다. 실제 API 키 또는 운영 `.env`는 읽거나 작성하지 않았다.

## 코드 구성

경로는 저장소 루트 기준이다.

| 위치 | 역할 |
|---|---|
| `backend/src/main/java/com/playdata/calen/common/embedding/EmbeddingProperties.java` | `app.embedding` 바인딩 및 활성화 설정 검증 |
| 같은 디렉터리의 `EmbeddingApiClient.java` | 명시적으로 호출하는 임베딩·저장·삭제 API 클라이언트 |
| `EmbeddingApiModels.java`, `EmbeddingDocument.java` | JSON 전송 모델 및 문서 값 객체 |
| `EmbeddingApiException.java` | 원격 상태 코드와 후속 재시도 판단 정보. 민감한 응답 본문은 보관하지 않음 |
| `backend/src/main/java/com/playdata/calen/ledger/embedding/LedgerEmbeddingDocumentService.java` | 인증된 소유자 범위에서 저장 완료된 원본을 읽어 문서 생성 |
| 같은 디렉터리의 `LedgerEmbeddingDocumentFactory.java` | 고정 텍스트·메타데이터·텍스트 해시 구성 |
| `LedgerEmbeddingRecordType.java`, `LedgerEmbeddingPointIds.java` | 기록 유형 및 결정적 UUIDv5 규약 |

추가·수정한 전달 설정은 `backend/src/main/resources/application.yml`, `docker-compose.yml`, `docker-compose.oci.yml`, `docker-compose.oci.app.yml`, `.env.example`, `.env.oci.app.example`이다. OCI 데이터 서버·모니터링 Compose에는 백엔드가 없으므로 변경하지 않았다.

## 연결 설정

기본값은 비활성이다. 활성화할 때 운영자가 **백엔드 실행 환경**에 아래 값을 넣는다. 아래 키 표기는 자리표시자이며 실제 키가 아니다.

```dotenv
APP_EMBEDDING_ENABLED=true
APP_EMBEDDING_BASE_URL=http://100.75.225.16:8000
APP_EMBEDDING_API_KEY=<운영자가_입력할_OCI_CALEN_API_KEY와_동일한_값>
APP_EMBEDDING_CONNECT_TIMEOUT=5s
APP_EMBEDDING_READ_TIMEOUT=180s
APP_EMBEDDING_BATCH_SIZE=4
APP_EMBEDDING_CAPTURE_ENABLED=true
```

- 위 예시의 `<...>` 문자열을 그대로 키로 사용하지 않는다. 실제 값은 운영 비밀 설정에만 넣고 Git·문서·브라우저로 전달하지 않는다.
- `APP_EMBEDDING_API_KEY`는 OCI 수신 API의 `CALEN_API_KEY`와 같아야 한다. Qdrant 접근 토큰과는 별개의 값이다.
- URL은 HTTP(S)와 호스트를 가진 원점 주소만 허용한다. 사용자 정보, 쿼리, 프래그먼트, 경로 접두사는 허용하지 않으며 끝의 `/` 하나는 허용한다. 요청 목적지를 사용자 입력으로 받지 않는다.
- 활성화 시 키가 비어 있거나 공백·제어문자를 포함하면 설정 검증이 실패한다. 연결·응답 시간은 양수여야 하고 배치 크기는 1~4다.
- 비활성 상태에서는 HTTP 클라이언트를 만들거나 서버 요청을 시작하지 않는다. 기능 메서드를 명시적으로 호출하면 비활성 오류를 반환한다.
- HTTP 리다이렉트를 따라가지 않는다. API 키가 다른 서버로 넘어가는 것을 막는다. 원격 오류 본문·키·문서·질문은 추가 로그에 남기지 않는다.
- Compose의 `.env`에 값을 넣는 것만으로 컨테이너 환경변수에 자동 전달되는 것은 아니다. 이번에 세 백엔드 Compose의 `environment`에 위 여섯 항목을 추가했다. 환경변수 변경의 실제 반영은 별도 배포 절차에서 컨테이너를 다시 생성하거나 실행 환경을 다시 구성해야 한다.
- WAR를 외부 Tomcat에서 실행한다면 Docker Compose가 아니라 해당 프로세스 실행 환경에 같은 변수를 전달한다.
- 백엔드 프로세스 또는 컨테이너에서 Tailscale 주소 `100.75.225.16:8000`에 도달할 수 있어야 한다. 공인 포트 노출을 추가하지 않는다.

운영 연결 구조는 백엔드 → OCI 수신 API → TEI/Qdrant다. Qdrant는 데이터 서버 `100.81.241.98:6333`, 컬렉션은 `calen-personal-vectors`, named vector는 `dense`, 차원은 1024라는 합의를 기준으로 작성했다. 백엔드는 Qdrant에 직접 접속하거나 컬렉션·인덱스를 생성하지 않는다. 실제 컬렉션 선택과 범위 토큰은 OCI 수신 API 설정에서 관리한다. 이 정보는 전달받은 운영 계약이며 이번 작업에서 서버에 접속해 확인하지 않았다.

OCI 수신 API의 TEI·Qdrant 요청 대기 시간이 각각 60초이므로 백엔드 응답 대기는 180초를 기본값으로 둔다. 이는 응답을 기다리는 상한이지, 작업 완료 보장 또는 전체 요청 지연의 측정값이 아니다.

## API 계약과 호출 방법

모든 요청에 `X-API-Key` 헤더를 사용한다. 컬렉션 이름은 요청에 넣지 않는다.

| 메서드 | 수신 API | 제한 및 응답 |
|---|---|---|
| `embedDocuments(texts)` | `POST /v1/embed`, `{"texts":["..."]}` | 문서 원문 그대로. 설정된 배치 크기 이하, 최대 4개. `vectors`, `dimensions=1024` |
| `embedQueries(queries)` / `embedQuery(query)` | 같은 `/v1/embed` | 질문에만 아래 지시문을 붙임. 질문 벡터만 만들고 검색·저장은 하지 않음 |
| `upsertDocuments(documents)` | `POST /v1/documents/upsert`, `{"documents":[{"id":"UUID","text":"...","metadata":{}}]}` | 설정된 배치 크기 이하, 최대 4개. `status=ok`, `upserted=N`, `ids` |
| `deleteDocuments(ids)` | `POST /v1/documents/delete`, `{"ids":["UUID", "..."]}` | 한 호출의 입력은 최대 100개. 중복 ID를 제거해 전송. `status=ok`, `requested=N`, `ids` |

빈 입력·빈 텍스트·null은 거부한다. 한 upsert 배치 안에서 중복 포인트 ID도 거부한다. 응답의 벡터 개수·차원·유한 숫자 여부, 저장·삭제 응답의 개수와 ID 집합을 계약과 비교한다. 삭제의 `requested`는 서로 다른 **요청 ID 수**이며 실제 삭제한 기존 포인트 수가 아니다.

각 클라이언트 메서드는 한 배치만 처리한다. 자동 동기화 작업자가 `properties.getBatchSize()` 이하로 선점하고 영속 재시도를 관리한다. 저장 API가 이미 임베딩을 수행하므로 저장 전에 `embedDocuments`를 별도로 호출해 이중 추론하지 않는다.

후속 서비스에서 사용할 형태는 다음과 같다. 이 코드는 사용 예시이며 신규 HTTP 엔드포인트나 현재 실행되는 동기화 코드가 아니다.

```java
// userId는 @AuthenticationPrincipal 또는 신뢰된 백엔드 작업의 소유자 정보다.
EmbeddingDocument document = documentService.prepareEntry(userId, entryId);
// 문서 생성의 읽기 트랜잭션을 빠져나온 후 원격 요청을 수행한다.
var result = embeddingApiClient.upsertDocuments(List.of(document));

EmbeddingDocument ruleDocument = documentService.prepareRule(userId, ruleId);
var ruleResult = embeddingApiClient.upsertDocuments(List.of(ruleDocument));

List<Double> queryVector = embeddingApiClient.embedQuery("점심 식비를 쓴 기록을 찾아줘");
// queryVector만으로는 소유자 격리나 Qdrant 검색이 수행되지 않는다.
```

원격 HTTP 408·429·5xx 또는 연결·대기 오류는 `EmbeddingApiException.isRetryable()`에 재시도 판단 정보를 남긴다. 인증·입력 오류, 해석 불가능한 응답이나 계약 불일치는 재시도 가능으로 표시하지 않는다. `EmbeddingApiClient` 자체는 재시도하지 않고 별도 `LedgerEmbeddingSyncWorker`가 영속 재시도를 수행한다. 재시도 정보만으로 순서·최신성·원격 반영 여부가 보장되지는 않는다.

타임아웃이나 응답 해석 실패가 나도 원격 저장이 이미 끝났을 수 있다. 자동 동기화는 DB 최신 상태와 작업 revision을 확인한 뒤 같은 ID로 재시도한다. 원격의 조건부 쓰기/fencing 부재와 늦은 요청의 제한은 `embedding_automatic_sync.md`에 설명했다.

## 포함하는 원본

- `LedgerEntry`: 소유자 범위와 `deleted_at IS NULL`을 함께 적용해 DB에서 조회한 저장 완료 거래만 포함한다. 수입·지출 모두 대상이다.
- `RecurringLedgerRule`: 소유자 범위로 조회한 등록 규칙이 독립적인 대상이다. 실제 거래가 아직 없어도 문서를 만든다. 활성·비활성 모두 문서 생성이 가능하고 `active` 메타데이터로 구분한다.
- 승인 전 AI/OCR 후보, 가져오기 미리보기, 삭제된 거래는 문서 생성 대상이 아니다. 이런 DTO를 받는 공개 API를 추가하지 않았다.
- `owner_id`는 조회한 원본의 DB 소유자로 정한다. 문서 서비스에 넘기는 사용자 ID도 인증 principal 또는 신뢰된 작업에서 얻어야 한다. 브라우저가 보낸 소유자나 메타데이터를 그대로 저장하는 경로는 없다.
- 관련 엔티티 이름은 문서 생성 서비스의 읽기 트랜잭션 안에서 조회한다. 완성한 DTO를 반환하며 원격 API 호출은 이 서비스 안에서 하지 않는다.
- 여행 연계 경비도 원본 거래 ID로 처리한다. 연결 ID만 메타데이터에 포함하고 여행 이름·방문 메모를 텍스트에 합치지 않는다.

현재 검색 API는 없다. 후속 검색에서 `metadata.domain=ledger`, 인증된 `metadata.owner_id`, 허용된 `metadata.record_type`을 서버가 강제해야 한다. 비활성 규칙 제외 여부는 `recurring_rule` 분기에만 `metadata.active=true`를 적용하는 식으로 선택한다. 모든 기록에 `active=true`를 일괄 적용하면 해당 필드가 없는 일반 거래가 빠질 수 있다.

## 텍스트 형식 v1

기록 하나당 텍스트 하나, 벡터 하나다. 원문에서 읽은 값으로 아래 여섯 항목을 순서대로 구성한다.

```text
기록 유형: 가계부 거래
입출금 구분: 지출
제목: 점심 식사
대분류: 식비
소분류: 외식
메모: 동료와 점심
```

정기 규칙의 기록 유형은 `정기 입출금 규칙`, 입출금 구분은 원본 enum에 따라 `수입` 또는 `지출`이다. 실제 저장된 분류 이름을 사용하며 누락 값은 빈 문자열로 둔다. 메모가 null 또는 비어 있어도 `메모: ` 줄을 유지한다. 제목·메모를 AI로 보완하지 않고 trim·요약·대소문자 변경도 하지 않는다.

문자열은 UTF-8, 항목 구분은 LF다. 원문 내부의 CRLF·CR도 LF로만 정규화하고 내부 줄바꿈은 보존한다. 각 라벨 뒤에는 ASCII 공백 하나가 있으며 마지막 메모 값 뒤에 별도의 줄바꿈은 추가하지 않는다. 단, 원본 메모 자체의 끝 줄바꿈은 그대로 보존한다. 메모가 없는 경우 마지막 `메모: `의 공백도 해시 대상이다.

날짜·시간·금액·결제수단·정기 주기는 임베딩 텍스트에 넣지 않는다. 구조화 조회와 메타데이터에 사용하며 합계·정확한 금액 계산은 MariaDB를 기준으로 수행한다. 정기 규칙의 금액은 설정 금액이지 실제 지출·수입 내역이 아니므로 거래 합계에 더하지 않는다.

## 메타데이터 형식

아래 값은 OCI 수신 API가 Qdrant payload의 `metadata` 아래에 저장한다. `text`는 별도 payload 필드다.

| 필드 | 형식 / 의미 |
|---|---|
| `domain` | 문자열 `ledger` |
| `record_type` | 문자열 `ledger_entry` 또는 `recurring_rule` |
| `owner_id`, `source_id` | 원본 DB의 양수 정수. UUID·문자열로 바꾸지 않음 |
| `text_format_version` | 정수 `1` |
| `text_hash` | 실제 전송 텍스트 UTF-8 바이트의 SHA-256, 소문자 64자리 hex |
| `entry_type`, `title` | 원본 `INCOME`/`EXPENSE`, 제목 |
| `category_group_id/name`, `category_detail_id/name` | 실제 키는 `category_group_id`, `category_group_name`, `category_detail_id`, `category_detail_name` |
| `payment_method_id/name/kind` | 실제 키는 `payment_method_id`, `payment_method_name`, `payment_method_kind` |
| `amount`, `currency` | 원본 KRW amount의 십진 문자열, `KRW`. 부동소수점 반올림을 피함 |
| 거래의 `entry_date`, `entry_time` | ISO 날짜·시간 문자열. 시간 없으면 생략 |
| 거래의 `entry_date_epoch_day` | 같은 거래 날짜의 Unix epoch 기준 일수 정수. 후속 범위 필터에 사용 가능 |
| 거래의 외화·환율 필드 | `foreign_currency_code`, `foreign_amount`, `exchange_rate_to_krw`, `exchange_rate_date`, `exchange_rate_provider`. 금액·환율은 십진 문자열 |
| 거래의 여행 연결 필드 | `travel_plan_id`, `travel_record_id`. 존재할 때만 포함 |
| 규칙의 `active` | JSON boolean |
| 규칙의 주기 | `schedule_type`, `month_interval`, `day_of_month`, `interval_days` |
| 규칙의 기간·방식 | `start_date`, `end_date`, `mode` |
| 규칙의 시간 | `created_at`, `updated_at`. 원본 LocalDateTime의 ISO 문자열; UTC나 순서 번호로 바꾸지 않음 |

선택 필드가 null이면 생략하고 임의의 값은 채우지 않는다. 분류가 비활성화되어도 기존 기록의 실제 이름을 사용한다. 규칙의 `updated_at`은 참고 정보일 뿐 서버의 버전 비교 기능이 아니다. 거래 엔티티에 없는 변경 버전이나 수정 시간은 만들어 넣지 않는다.

`text_hash`는 텍스트 변경 판단에만 사용할 수 있다. 금액·결제수단·날짜·활성 상태만 바뀌면 텍스트 해시가 같아도 메타데이터 저장은 필요하다. 이 단계에서는 해시 기반 스킵·벡터 재사용·메타데이터 전용 갱신을 구현하지 않았다.

## UUID 규약

포인트 ID는 RFC 방식의 **UUIDv5(SHA-1)** 로 만든다. SHA-1은 여기서 이름 기반 UUID를 생성할 목적으로만 사용하며 인증·보안 해시로 사용하지 않는다.

- 고정 Calen namespace: `2d71c792-fd86-49da-81c7-a6c354eaf123`
- canonical name: `ledger|<owner_id>|<record_type>|<source_id>`
- ID는 양수 십진수, 부호·앞자리 0·공백 없음. 유형은 소문자 `ledger_entry`/`recurring_rule`.
- 예: owner 7, 거래 123 → `ledger|7|ledger_entry|123`
- 정기 규칙 123 → `ledger|7|recurring_rule|123`. 거래와 원본 번호가 같아도 다른 포인트다.
- UTF-8 name 바이트 앞에 namespace UUID의 네트워크 순서 16바이트를 붙여 SHA-1을 계산한다. 첫 16바이트에 version=5, RFC variant 비트를 적용한다.

텍스트, 해시, 형식 버전, 금액, 활성 상태를 UUID 입력에 넣지 않는다. 수정·재시도·정기 규칙 활성 변경에도 같은 소유자/유형/원본이면 같은 ID를 사용한다. 기록을 다른 소유자에게 복사했다면 새 소유자와 새 거래 ID로 별도 포인트가 된다.

다른 클라이언트에서도 Python 표준 라이브러리 등으로 같은 규약을 재현할 수 있다. 아래는 구현 예시이며 이번 작업에서 실행한 검증 코드는 아니다.

```python
import uuid

namespace = uuid.UUID("2d71c792-fd86-49da-81c7-a6c354eaf123")
point_id = uuid.uuid5(namespace, "ledger|7|ledger_entry|123")
```

namespace나 canonical name을 운영 적재 후 바꾸면 같은 기록이 다른 포인트로 생긴다. 변경이 필요하면 기존 포인트 정리·재적재 절차부터 설계한다.

삭제 작업은 삭제 전에 DB에서 확인한 소유자·유형·원본 ID를 보관하고 `LedgerEmbeddingPointIds.forRecord(...)`로 같은 UUID를 생성해 삭제 API에 보낼 수 있다. 삭제 후에는 원본 조회가 불가능할 수 있으므로 브라우저가 보내는 임의의 UUID에 삭제 권한을 주면 안 된다. 현재 CRUD에 이 흐름을 연결한 것은 아니다.

## 질문 지시문

문서 전송과 질문 전송을 구분한다. `embedDocuments` 및 문서 upsert의 텍스트에는 지시문을 붙이지 않는다. `embedQueries` / `embedQuery`는 다음 고정 형식으로 질문을 전송한다.

```text
Instruct: Given a personal finance question, retrieve relevant ledger transactions and recurring income or expense rules.
Query:<원본 질문>
```

질문 쪽 지시문과 문서 쪽 무지시문 구성은 [Qwen 공식 모델 카드](https://huggingface.co/Qwen/Qwen3-Embedding-0.6B)의 query/document 사용 예시를 참고했다. 지시문은 검색 임베딩용이며 챗봇 시스템 프롬프트나 소유자 권한 검사를 대체하지 않는다. 다른 클라이언트는 지시문을 중복 추가하지 않도록 같은 규약을 지켜야 한다.

## 후속 구현과 남은 단계

1. 기반 계약의 이전 검증은 `embedding_validation_report.md`를 확인한다. 2026-10-06 자동 동기화 후속 단계에서는 Java 컴파일만 확인했고, 새 테스트 추가·테스트 실행·운영 서버 접속·데이터 적재는 하지 않았다. 작업자와 MariaDB 런타임 검증은 별도 승인이 필요하다.
2. 운영 환경변수, 수신 API 인증키 일치, 백엔드의 Tailscale 도달 가능성을 배포 단계에서 확인한다. Qdrant 컬렉션과 named vector 설정도 수신 API 측에서 확인한다.
3. 거래·정기 규칙의 생성·수정·삭제·복구 outbox 연결은 2026-10-06에 구현했다. 런타임 검증·운영 반영은 미실행이다. 원격 fencing 제한과 활성화 조건은 `embedding_automatic_sync.md`를 따른다.
4. 분류·결제수단 JPA 이름 변경 fan-out과 메타데이터 전용 변경의 동기화를 구현했다. text_hash 기반 갱신 생략은 하지 않는다.
5. 기존 자료는 소유자·기록 유형별 커서 페이지 조회로 배치 최대 4건씩 적재한다. 삭제된 거래는 제외하고 진행 지점·실패·재시도·재시작 처리를 설계한다. 아직 구현되지 않았다.
6. Qdrant 검색에는 인증된 owner/domain/record_type 필터를 강제하고 비활성 정기 규칙 포함 여부를 명시적으로 선택한다. 검색 결과의 원본을 DB에서 다시 권한·삭제 상태로 확인한다.
7. 합계·정확한 날짜/금액 조건은 MariaDB로 처리하고, 의미 기반 검색으로 후보를 좁힌 뒤 LLM에 제공할 문맥과 가드레일을 별도로 설계한다. 승인 전 후보·여행 방문 메모는 포함하지 않는다.

최초 기반 단계의 검증 결과는 `embedding_validation_report.md`, 후속 자동 동기화의 구현 및 미검증 범위는 `embedding_automatic_sync.md`에 구분했다. 실제 운영 설정 변경, 배포, 커밋·푸시는 수행하지 않았다.
