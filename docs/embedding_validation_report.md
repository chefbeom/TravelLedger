# 임베딩 연동 기반 검증 결과

2026-10-06 (Asia/Seoul). 중단 전 확인한 빌드·health 결과와, 재개 후 실제 실행한 계약 테스트를 구분해 기록한다.

이 보고서는 **자동 동기화 구현 전 기반 단계의 스냅샷**이다. 아래 69개 계약 테스트와 당시 CRUD 자동 동기화가 없었다는 기록은 현재 worker/새 repository fragment의 런타임 검증 결과가 아니다. 이후 자동 동기화는 구현하고 Java 컴파일만 확인했으며 새 테스트 작성·테스트 실행·운영 적용은 하지 않았다. 최신 구현, 활성화 조건과 미검증 범위는 [자동 동기화 운영 문서](embedding_automatic_sync.md)를 따른다.

## 결과

| 항목 | 실제 결과 / 범위 |
|---|---|
| 백엔드 컴파일·패키징 | 전 단계의 `gradlew.bat bootWar --console=plain` 성공. 재개 후 `jar tf`로 WAR 내부 신규 임베딩 클래스 포함도 확인. 본 구현 코드는 변경하지 않았다. |
| 계약 테스트 | 작성해 둔 5개 클래스, 69개 테스트 통과. 실패·오류·스킵 0개. |
| 설정 바인딩 | 기본 비활성, URL·키·시간·배치 검증과 Spring 설정 바인딩/실패를 실제 테스트로 실행. |
| HTTP 계약 | Spring MockRestServiceServer로 정확한 POST 경로, X-API-Key, JSON 필드, 배치 상한, 응답/예외 처리 실행. 실제 OCI 인증 요청은 아님. |
| 원본 DB 범위 | H2의 실제 JPA 저장소로 소유자 범위·삭제 거래 제외·정기 규칙·승인 전 OCR 후보 제외를 실행. MariaDB 운영 데이터는 사용하지 않음. |
| 문서·UUID·해시 | 원문 유지, 공란 메모 포함, 구조화 메타데이터, 수정 시 고정 ID, Python 표준 uuid5 및 SHA-256 기준값 비교 실행. |
| OCI health | 전 단계에서 Tailscale Running/Online, 대상 peer 존재 및 운영 8000·테스트 8001 `/health` 비인증 GET HTTP 200 확인. 재개 시 같은 요청을 다시 실행하지 않았다. |
| OCI 인증 embed/upsert/delete | 미실행. 재개 환경에도 `APP_EMBEDDING_TEST_API_KEY`가 없다. 비밀 파일 탐색·키 추측을 하지 않았다. |
| 수동 확인 예제 | Python `ast.parse` 문법 검사 통과. 예제 자체 및 인증 요청은 실행하지 않았다. |
| 전체 기존 테스트 / 프런트 테스트 | 이번 범위에서 미실행. 69개 임베딩 테스트만 실행했다. |
| 배포·커밋·푸시·운영 적재 | 미실행. 운영 컬렉션·실제 가계부 데이터를 변경하지 않았다. |

빌드 도구 또는 의존성 제한으로 막힌 항목은 없었다. 비인증 health 성공만으로 TEI 추론·Qdrant 쓰기·올바른 컬렉션 연결까지 검증됐다고 볼 수는 없다.

## 실행한 주요 명령

`C:\project\calen\backend`에서 Java 17을 사용했다.

```powershell
$env:JAVA_HOME = 'C:\jdk-17'
.\gradlew.bat bootWar --console=plain
.\gradlew.bat test --tests 'com.playdata.calen.common.embedding.*' --tests 'com.playdata.calen.ledger.embedding.*' --console=plain
```

bootWar는 중단 전 실행하여 성공했고, 테스트 명령은 재개 후 두 번 실행했다. 첫 실행은 69개 중 4개가 실패했다. 3개는 요청 실행 후 모의 응답을 추가한 테스트 정의 순서 문제였고, 1개는 fixture와 관계없는 문자열을 요구한 테스트 작성 오류였다. 해당 테스트만 보완하고 두 번째 실행에서 69개가 모두 통과했다.

| 클래스 | 실행 개수 | 최종 실패 |
|---|---:|---:|
| `EmbeddingPropertiesTest` | 25 | 0 |
| `EmbeddingApiClientTest` | 26 | 0 |
| `LedgerEmbeddingDocumentFactoryTest` | 9 | 0 |
| `LedgerEmbeddingDocumentServiceTest` | 6 | 0 |
| `LedgerEmbeddingPointIdsTest` | 3 | 0 |

테스트 5개 클래스 중 수정한 파일은 `EmbeddingApiClientTest.java`, `LedgerEmbeddingDocumentServiceTest.java`다. 기존 기반 구현 코드·설정은 손대지 않았다. 이 보고서 외에 신규 기능 파일은 만들지 않았다.

## 실제 실행으로 확인한 내용

- 비활성 시 정상 생성자의 HTTP 클라이언트가 null이며, 모든 기능 호출은 외부 클라이언트에 접근하지 않고 비활성 오류를 반환한다.
- 임베딩/upsert 4개 허용·5개 거부, 설정 배치 2개 적용, 삭제 100개 허용·101개 거부, 삭제 중복 ID 제거를 확인했다.
- 문서 원문(공백·줄바꿈 포함)은 그대로 전송되고 질문에만 고정 `Instruct`/`Query` 형식을 붙인다.
- 임베딩 응답 1024차원과 개수, null/NaN/Infinity·누락·해석 실패, 잘못된 upsert/delete ID와 개수, 3xx/4xx/5xx·IO 오류를 처리한다.
- 원격 오류 메시지·키·문서 원문을 예외 내용이나 원인 예외에 담지 않는지 확인했다. 사용한 키·문서는 명백한 로컬 fixture다.
- null·빈 메모에도 `메모: ` 줄이 있으며, 원문 공백 유지 및 CRLF/CR → LF 규약이 해시와 일치한다.
- 날짜·금액·결제수단·정기 주기·여행 연결 ID는 메타데이터에만 있다. 여행 이름·방문 메모는 만들지 않는다.
- 다른 소유자의 원본과 삭제된 거래는 실제 H2 DB 조회에서 제외된다. 비활성 정기 규칙은 독립 문서이며 `active=false`를 보존한다.
- UUIDv5는 소유자·기록 유형·원본 ID별로 구분되고, 텍스트/메타데이터 수정에도 같은 기록 ID를 유지한다. Python 표준 라이브러리로 독립 계산한 UUID 3개와 공란 메모 SHA-256 기준값을 비교했다.

소스로만 확인한 사항: 운영 Compose 환경변수 전달, 실제 JDK HTTP 클라이언트의 5s/180s 타임아웃·리다이렉트 금지 설정, 기존 CRUD에 자동 동기화 호출이 없는 점이다. 실제 느린 서버의 시간 측정, OCI 모델 품질·Qdrant 데이터 조회, 동시 요청/순서 제어는 이번 테스트 결과에 포함되지 않는다.

이번 검증 범위에서 본 구현의 수정을 요구하는 실패는 발견하지 못했다. 실서버 인증 계약 확인은 아래 절차로 남아 있다.

## OCI에서 남은 인증 검증을 직접 실행하는 방법

현재 OCI에서 **`api-test`가 포함된 실제 Compose 프로젝트 디렉터리**에서 실행한다. 기본 Compose 파일이 아니라면, 아래 `docker compose`에 현재 운영 중인 파일을 `-f`로 지정한다. 저장소의 기존 TEI 전용 Compose 파일에 `api-test`가 있다고 가정하지 않는다.

컨테이너 내부 API 포트가 8000일 때의 명령이다. 다른 내부 포트를 사용한다면 아래 `base`의 포트만 실제 `api-test` 내부 포트로 바꾼다. 운영 `api` 컨테이너에서는 실행하지 않는다.

이 명령은 **아직 실행하지 않은 수동 절차**다. 컨테이너의 기존 `CALEN_API_KEY`를 메모리에서만 사용하고 출력하지 않는다. `QDRANT_COLLECTION`이 정확히 `calen-embedding-test`가 아니면 아무 요청도 하지 않고 중단한다. 실행마다 UUID 하나와 합성 문서 하나를 새로 만들어 저장을 시도하고, `finally`에서 해당 UUID만 삭제한다.

```bash
docker compose exec -T api-test python - <<'PY'
import json, math, os, sys, uuid
from urllib.request import Request, build_opener, ProxyHandler, HTTPRedirectHandler
from urllib.error import HTTPError

if os.environ.get('QDRANT_COLLECTION') != 'calen-embedding-test':
    sys.exit('STOP: api-test collection must be calen-embedding-test')
key = os.environ.get('CALEN_API_KEY')
if not key:
    sys.exit('STOP: api-test CALEN_API_KEY is missing')

class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

# Loopback inside api-test: use its own API and existing environment, not production API.
base = 'http://127.0.0.1:8000'
opener = build_opener(ProxyHandler({}), NoRedirect())
point_id = str(uuid.uuid4())
text = 'SYNTHETIC API CONTRACT CHECK ONLY. Not a real ledger, person, or payment.'
attempted_upsert = False
failed = False

def post(path, payload):
    request = Request(base + path, data=json.dumps(payload).encode('utf-8'),
                      headers={'Content-Type': 'application/json', 'X-API-Key': key},
                      method='POST')
    with opener.open(request, timeout=180) as response:
        return json.load(response)

try:
    result = post('/v1/embed', {'texts': [text]})
    vectors = result.get('vectors')
    assert result.get('dimensions') == 1024 and len(vectors) == 1
    assert len(vectors[0]) == 1024 and all(math.isfinite(v) for v in vectors[0])
    print('embed: OK (1 vector, 1024 dimensions)')
    attempted_upsert = True  # Also clean up when upsert may succeed but its response is lost.
    result = post('/v1/documents/upsert', {'documents': [{
        'id': point_id, 'text': text,
        'metadata': {'domain': 'ledger', 'record_type': 'ledger_entry',
                     'owner_id': 9999999999, 'source_id': 9999999999,
                     'validation_synthetic': True}}]})
    assert result.get('status') == 'ok' and result.get('upserted') == 1
    assert result.get('ids') == [point_id]
    print('upsert: OK (one new synthetic point)')
except Exception as error:
    failed = True
    print('verification: FAILED', type(error).__name__,
          'HTTP ' + str(error.code) if isinstance(error, HTTPError) else '')
finally:
    if attempted_upsert:
        try:
            result = post('/v1/documents/delete', {'ids': [point_id]})
            assert result.get('status') == 'ok' and result.get('requested') == 1
            assert result.get('ids') == [point_id]
            print('delete: OK (only this run ID requested)')
        except Exception as error:
            failed = True
            print('cleanup: FAILED', type(error).__name__, 'this_run_id=' + point_id)
sys.exit(1 if failed else 0)
PY
```

`requested=1`은 삭제 API에 보낸 서로 다른 ID가 1개라는 확인이며, 기존 포인트의 실제 삭제 개수가 아니다. 위 절차는 API 응답을 확인하며, Qdrant 검색·조회로 저장 내용을 확인하거나 삭제 후 포인트가 없음을 확인하는 단계는 포함하지 않는다.

키 부족·컬렉션 불일치 시 환경 값을 출력하거나 공유하지 말고 운영자가 `api-test`의 기존 설정을 확인한다. cleanup 실패 시에도 전체 삭제나 기존 포인트 삭제를 하지 않고 출력된 이번 UUID만 대상으로 재확인한다.
