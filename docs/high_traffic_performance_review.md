# 대규모 트래픽 성능 검토

검토일: 2026-10-03. 대상: 현재 작업 디렉터리의 Spring Boot 3.5.11 / Java 17 백엔드, Vue 프런트, MariaDB·MinIO·Redis·Nginx 배포 설정, 멀티모달 LLM 이미지 분석 경로. `PaddleOCR/`는 별도 서비스 코드이며 현재 백엔드 이미지 분석 경로에는 연결되어 있지 않다.

개선 전 소스와 설정을 읽은 정적 분석 기록이다. 이후 구현 변경과 검증 상태는 [성능 개선 진행 기록](performance_improvement_progress.md)을 확인한다. 아래 코드 줄 번호는 검토 당시 기준이다. 운영 환경 변수, 서버 사양, 실제 DB 인덱스·실행 계획, 운영 메트릭은 확인하지 않았다. 처리량과 메모리 예시는 코드 구조에서 계산한 예시이며 부하 테스트 결과가 아니다.

가장 먼저 개선할 부분은 **느린 AI 작업이 DB 연결을 점유하는 경로, OCR·ZIP의 큰 메모리 사용, 드라이브의 전체 조회 반복, 사진 클러스터의 전체 재계산**이다. 어떤 병목이 먼저 나타나는지는 요청 구성과 사용자별 누적 데이터량에 따라 달라진다.

| 우선순위 | 지점 | 부하가 커지는 조건 | 영향 |
|---|---|---|---|
| P1 | AI 엑셀 미리보기의 트랜잭션·제공자 락 | 여러 사용자가 같은 모델 서버로 요청 | DB 연결·HTTP 스레드 대기, 일반 API 지연 |
| P1 | OCR 작업 큐의 이미지 원본 보관 | 큰 이미지 분석 요청이 연속 접수 | JVM 힙 증가, GC·메모리 부족 |
| P1 | 드라이브 다운로드·ZIP 전체 버퍼링 | 큰 파일 또는 다수 동시 다운로드 | 힙·네트워크·DB 연결 점유 |
| P1 | 드라이브 전체 파일 조회와 폴더 순회 | 파일·폴더가 많은 사용자 | DB 전송량과 반복 조회 증가 |
| P1 | 사진 클러스터 중첩 비교·전체 재저장 | 사진이 많은 계정의 업로드·삭제·공개 지도 조회 | CPU, 쓰기 증폭, 긴 트랜잭션 |
| P1 | 역지오코딩 요청 스레드 대기 | 캐시에 없는 좌표가 동시에 유입 | HTTP 스레드 대기, 외부 제공자 처리량 제한 |
| P2 | 대시보드 반복 집계와 요청 증폭 | 로그인·새로고침·빠른 입력 집중 | MariaDB 읽기 부하 |
| P2 | 지도 전체 조회·큰 스냅샷·인접 핀 정렬 | 기록·사진·경로가 많은 사용자 | DB, JSON 처리, 응답 크기, 브라우저 처리 비용 |
| P2 | 캐시 미스 중복 계산·퇴거 없는 로컬 캐시 | 캐시 만료·Redis 장애·새 미디어 지속 조회 | 부하 급증·장기 메모리 증가 |
| 확장 전 필수 | 서버 로컬 세션·작업 큐·스케줄러 | 백엔드 인스턴스 증가·재시작 | 인증 유지와 작업 처리 제어의 제약 |

P1은 해당 기능의 트래픽이 늘었을 때 우선 제거할 구조적 병목, P2는 읽기 부하와 데이터 증가에 대한 다음 개선 대상이다.

## 1. AI 엑셀 미리보기: 모델 대기가 일반 API의 DB 연결을 막을 수 있다

근거: [LedgerAiExcelImportService.java:59](/C:/project/calen/backend/src/main/java/com/playdata/calen/ledger/service/LedgerAiExcelImportService.java:59), [preview():87](/C:/project/calen/backend/src/main/java/com/playdata/calen/ledger/service/LedgerAiExcelImportService.java:87), [제공자 큐 호출:307](/C:/project/calen/backend/src/main/java/com/playdata/calen/ledger/service/LedgerAiExcelImportService.java:307), [LedgerAiRequestQueue.java:18](/C:/project/calen/backend/src/main/java/com/playdata/calen/ledger/ai/LedgerAiRequestQueue.java:18).

`preview()`는 클래스 수준의 읽기 트랜잭션에서 사용자·분류 데이터를 읽고, 같은 호출 안에서 AI 응답을 기다린다. 제공자 큐는 `provider + baseUrl`별 `ReentrantLock`을 잡은 채 외부 호출을 수행한다. `lock.lock()` 대기에는 별도 제한 시간이 없다. 따라서 같은 제공자를 사용하는 엑셀 미리보기·분석 등의 작업이 서로 기다리며, HTTP 연결 및 DB 연결 점유 시간이 길어질 수 있다. 모델의 HTTP 읽기 제한 시간은 락 획득 전 대기 시간을 제한하지 않는다.

[application.yml:4](/C:/project/calen/backend/src/main/resources/application.yml:4)에는 Hikari 풀 크기의 명시적 설정이 없다. 별도 운영 설정이 없고 기본 Hikari 구성을 사용하면 최대 연결은 10개다. 따라서 이런 장기 요청이 풀을 점유하면 대시보드·가계부 등 다른 기능까지 연결을 기다릴 수 있다. 실제 풀 크기는 런타임 메트릭으로 확인해야 한다. [HikariCP 공식 설정](https://github.com/brettwooldridge/HikariCP#frequently-used).

개선:

- DB에서 필요한 정보를 짧은 트랜잭션으로 DTO에 담고, 외부 모델 호출은 트랜잭션 밖에서 수행한다.
- 엑셀 AI 미리보기도 작업 ID를 반환하고 상태를 조회하는 방식으로 변경한다.
- 제공자별 동시 작업 수, 최대 대기 작업 수, 대기 시간 제한을 함께 설정한다. 초과 접수는 429 또는 503과 재시도 정보를 반환한다.
- 같은 모델 서버로 향하는 기능들의 자원 배분을 정하고, 인스턴스가 늘어도 제공자 전체 동시성 제한을 유지한다.
- 풀 크기는 DB 처리 용량과 연결 점유 시간을 측정해 조정한다.

일반 가계부 AI 분석 API는 [LedgerAiAnalysisController.java:99](/C:/project/calen/backend/src/main/java/com/playdata/calen/ledger/web/LedgerAiAnalysisController.java:99)에서 `startAnalyze()`를 사용하며 이미 비동기다. 이 문제는 동기 엑셀 미리보기 등 남아 있는 경로를 대상으로 한다.

## 2. AI·OCR: 비동기지만 워커가 하나이고, OCR 큐가 이미지 원본을 유지한다

근거: [LedgerAiAsyncConfig.java:11](/C:/project/calen/backend/src/main/java/com/playdata/calen/ledger/ai/LedgerAiAsyncConfig.java:11), [LedgerOcrAsyncConfig.java:11](/C:/project/calen/backend/src/main/java/com/playdata/calen/ledger/ocr/LedgerOcrAsyncConfig.java:11), [OCR 이미지 복사:144](/C:/project/calen/backend/src/main/java/com/playdata/calen/ledger/ocr/LedgerOcrService.java:144), [OCR 작업 접수:294](/C:/project/calen/backend/src/main/java/com/playdata/calen/ledger/ocr/LedgerOcrService.java:294).

현재 OCR는 **이미지를 멀티모달 LLM에 직접 보내 문자 읽기와 거래 항목 추출을 한 번에 수행**한다. [LedgerOcrRemoteClient.analyze():76](/C:/project/calen/backend/src/main/java/com/playdata/calen/ledger/ocr/LedgerOcrRemoteClient.java:76)는 `IMAGE_ANALYSIS` 기능 설정으로 AI 서버를 선택하고, [이미지 요청 생성:204](/C:/project/calen/backend/src/main/java/com/playdata/calen/ledger/ocr/LedgerOcrRemoteClient.java:204)에서 이미지 바이트를 Base64로 담는다. Ollama에는 `images`, 그 외에는 `image_url` 데이터 URL로 전달한다. 이미지 분석 요청당 추론 POST는 1회이며, 모델이 `auto`일 때 모델 목록 GET이 추가된다. PaddleOCR 호출이나 실패 시 PaddleOCR로 전환하는 경로는 없다.

두 executor는 각각 워커 1개와 대기 큐 100개로 설정돼 있다. HTTP 응답을 빨리 반환해도 분석 완료 처리량은 워커와 모델 서버 처리 용량으로 제한된다. 작업당 60초가 걸린다는 가정에서는 워커 하나가 분당 1건을 처리하고, 100건이 앞에 있으면 뒤 작업의 대기는 약 100분이다.

OCR는 업로드를 `byte[]`로 읽고 이를 가진 `MultipartFile`을 대기 작업이 참조한다. 이미지 저장소에 원본을 저장한 뒤에도 작업 큐에 원본 바이트를 유지한다. 기본 10MB 크기의 이미지 100개가 큐에 쌓이면 원본 바이트만 약 1,000MiB를 차지할 수 있다. 파싱·Base64·HTTP 전송 등 추가 메모리는 별도다. 큐가 가득 차면 예외 처리로 거절하지만, 메모리 부담은 큐가 채워지는 동안 이미 발생한다.

개선:

- 큐에는 작업 ID와 저장된 이미지의 object key만 넣고, 워커가 실행 시 원본을 읽는다.
- 사용자별 접수 제한과 전체 큐 제한을 설정하고, 대기 시간·활성 워커·거절 수를 관측한다.
- 지속성 있는 작업 큐 또는 DB 작업 테이블을 사용해 재시작 후 재처리·취소·만료를 관리한다.
- 동일 요청은 원자적으로 중복 접수를 차단한다. 현재 분석 API의 이력 재사용 조회만으로는 동시 접수 경쟁을 완전히 막기 어렵다.
- 워커 수는 모델·GPU의 실측 동시 처리 용량에 맞춘다.
- 같은 `provider + baseUrl`의 추론 요청은 공통 `LedgerAiRequestQueue`에서 직렬화된다. 워커만 늘려도 이 제한이 유지되면 AI 서버로 동시에 전송되는 요청 수는 늘지 않는다. 제공자 제한과 워커 수를 함께 설계한다.
- 이미지 해상도와 전송 크기를 제한하고, 작은 글자의 인식 품질을 확인하면서 리사이즈한다. Base64 전송은 원본보다 약 1/3 커지며 원본 바이트 외에 문자열·JSON 버퍼도 필요하다.
- 이미지 분석에는 비전 지원 모델을 명시한다. [자동 모델 선택:1008](/C:/project/calen/backend/src/main/java/com/playdata/calen/ledger/ocr/LedgerOcrRemoteClient.java:1008)은 목록의 첫 모델을 선택하며 이미지 지원 여부를 검사하지 않는다. 자동 선택을 유지하면 모델 목록을 짧게 캐시해 요청마다 추가 GET을 수행하는 비용을 줄인다.

별도 [PaddleOCR 서비스:1693](/C:/project/calen/PaddleOCR/ocr_service.py:1693)는 이미지에서 PaddleOCR로 문자를 읽고, 설정된 경우 추출 텍스트를 LLM으로 구조화하는 다른 경로다. 이 서비스 내부의 락과 동기 처리 문제를 현재 애플리케이션 OCR의 병목으로 설명했던 부분은 정정한다. `LEDGER_OCR_BASE_URL`·`LEDGER_OCR_WORKFLOW_URL` 등 설정은 남아 있지만, 현재 [준비 상태 검사:1144](/C:/project/calen/backend/src/main/java/com/playdata/calen/ledger/ocr/LedgerOcrService.java:1144)는 AI 기능 설정을 사용하고 `LedgerOcrProperties`에서는 업로드 크기 제한만 읽는다.

로컬 `.env`의 기본값은 `APP_LEDGER_AI_PROVIDER=lmstudio`, `APP_LEDGER_AI_MODEL=auto`다. 다만 [관리자 저장 설정 복원:943](/C:/project/calen/backend/src/main/java/com/playdata/calen/account/service/AdminOpsControlService.java:943)이 기능별 설정을 덮어쓸 수 있다. 이번 확인에서 Docker 엔진은 연결되지 않았고 로컬 LM Studio 모델 목록 조회도 시간 초과되어, 운영 환경의 최종 제공자·모델명은 확정하지 않았다.

## 3. 파일·ZIP 다운로드: 바이너리 전체를 JVM 힙에 올린다

근거: [단일 다운로드:357](/C:/project/calen/backend/src/main/java/com/playdata/calen/drive/service/DriveService.java:357), [ZIP 요청 트랜잭션:364](/C:/project/calen/backend/src/main/java/com/playdata/calen/drive/service/DriveService.java:364), [ZIP 버퍼 생성:850](/C:/project/calen/backend/src/main/java/com/playdata/calen/drive/service/DriveService.java:850), [MinIO 전체 읽기:162](/C:/project/calen/backend/src/main/java/com/playdata/calen/drive/service/DriveStorageService.java:162).

단일 파일은 `readAllBytes()`로 읽는다. ZIP은 파일별 원본 바이트를 읽어 `ByteArrayOutputStream`에 쓴 다음, `toByteArray()`로 압축 결과를 복사한다. 따라서 압축 결과, 그 복사본, 처리 중 원본 파일 등이 동시에 힙을 사용한다. 500MiB의 압축 결과라면 결과 버퍼와 복사본만 약 1GiB 이상이 필요할 수 있다. 여러 다운로드가 동시에 실행되면 GC와 메모리 부족 위험이 커진다.

ZIP 생성은 DB 트랜잭션 안에서 파일 조회·오브젝트 읽기·압축을 수행해 DB 연결 점유 시간도 늘릴 수 있다. ZIP API는 관리자 권한으로 제한돼 있지만 같은 백엔드의 일반 요청과 힙·DB 풀을 공유한다. 프런트도 실제로 [downloadDriveItems()](/C:/project/calen/frontend/src/lib/api.js:1699)를 사용한다.

개선:

- 권한 확인 후 단일 파일은 기존 presigned 다운로드 기능을 활용한다. 유효 시간과 공유 권한 검증을 유지한다.
- ZIP은 제한된 버퍼로 스트리밍하거나 별도 작업으로 생성해 MinIO에 저장하고 완료 후 다운로드 URL을 반환한다.
- 파일 메타데이터·권한 조회 트랜잭션과 파일 전송·압축 실행을 분리한다.
- 요청별 총 파일 크기·파일 개수 및 동시 압축 작업 수를 제한한다.

여행 원본 응답은 [TravelController.java:592](/C:/project/calen/backend/src/main/java/com/playdata/calen/travel/web/TravelController.java:592)에서 `Resource`를 반환한다. 모든 여행 원본이 `readAllBytes()`로 응답된다고 보아서는 안 된다. 여기서 확인한 큰 버퍼링 문제는 드라이브 파일·ZIP 경로다.

## 4. 드라이브: 반환 개수가 작아도 DB에서는 전체 파일을 읽는다

근거: [홈 요약:178](/C:/project/calen/backend/src/main/java/com/playdata/calen/drive/service/DriveService.java:178), [최근 파일:192](/C:/project/calen/backend/src/main/java/com/playdata/calen/drive/service/DriveService.java:192), [사진 목록:205](/C:/project/calen/backend/src/main/java/com/playdata/calen/drive/service/DriveService.java:205), [childrenOf():922](/C:/project/calen/backend/src/main/java/com/playdata/calen/drive/service/DriveService.java:922).

홈 요약은 사용자 항목 전체를 읽어 여러 번 순회한다. 최근 파일은 전체 조회 후 Java에서 필터링하고 30개를 선택한다. 사진 목록도 전체 조회 후 폴더·이미지 필터와 정렬·개수 제한을 적용한다. 사용자 파일이 늘어날수록 작은 응답을 만들기 위해 DB에서 전송하고 처리하는 데이터가 커진다.

폴더 순회에 사용하는 `childrenOf()`는 각 호출마다 사용자 항목 전체를 다시 조회한다. 이를 휴지통 이동·복구·하위 항목 수집·ZIP에서 재귀 호출하므로, 사용자 전체 항목 수가 N이고 방문 노드가 K이면 조회·행 처리량이 O(K×N)으로 커질 수 있다.

개선:

- 홈 통계는 SQL `COUNT`·`SUM` 집계, 최근 파일은 조건과 정렬을 SQL로 옮긴 `LIMIT 30` 조회로 변경한다.
- 자식은 `owner_id + parent_id` 조건으로 조회하거나 필요한 트리를 한 번 가져와 부모별 맵으로 순회한다.
- 일반 목록의 기존 DB 페이징은 유지하고, 홈·최근·사진·휴지통 등 보조 API도 같은 수준으로 제한한다.
- `(owner_id, trashed, item_type, last_modified_at, id)` 및 `(owner_id, parent_id, trashed, last_modified_at, id)` 등을 쿼리별 복합 인덱스 후보로 검토한다. 실제 `SHOW INDEX`와 `EXPLAIN`으로 기존 인덱스·정렬 순서·선택도를 확인한 후 적용한다.
- [프런트의 반복 재조회:1638](/C:/project/calen/frontend/src/components/CalenDriveWorkspace.vue:1638)는 목록·홈·최근 파일을 함께 읽는다. 변경한 항목에 필요한 데이터만 갱신하도록 조정한다.

## 5. 사진 클러스터: O(N²) 계산을 쓰기 요청에서도 수행한다

근거: [TravelPhotoClusterService.java:48](/C:/project/calen/backend/src/main/java/com/playdata/calen/travel/service/TravelPhotoClusterService.java:48), [사진 업로드 완료:1705](/C:/project/calen/backend/src/main/java/com/playdata/calen/travel/service/TravelService.java:1705), [사진 삭제:1522](/C:/project/calen/backend/src/main/java/com/playdata/calen/travel/service/TravelService.java:1522), [전체 클러스터 저장:1861](/C:/project/calen/backend/src/main/java/com/playdata/calen/travel/service/TravelService.java:1861), [공개 지도:881](/C:/project/calen/backend/src/main/java/com/playdata/calen/travel/service/TravelService.java:881).

연결 요소 계산은 방문한 사진마다 전체 후보를 다시 순회한다. 서로 충분히 떨어진 좌표라면 사진 1만 장에서 약 5천만 번의 거리 비교가 발생할 수 있다. 이는 코드상 연산 수 예시이며 실행 시간 측정값은 아니다.

개인 지도에는 Redis·DB 스냅샷이 이미 있다. 그러나 사진 업로드·삭제는 쓰기 트랜잭션 안에서 전체 사진 클러스터를 재계산한다. 저장도 사용자 클러스터·멤버를 전부 삭제하고 다시 저장한다. 공개 여행 개요와 공개 클러스터 상세는 대상 사진 전체에 대한 클러스터 계산을 호출한다.

개선:

- 공간 격자·공간 인덱스로 가까운 후보만 비교한다. 셀 경계를 넘는 인접 사진과 기존 클러스터 의미를 보존해야 한다.
- 사진 변경 후 커밋된 이벤트로 재계산 작업을 접수하고, 연속 업로드는 합쳐서 처리한다.
- 변경 위치 주변 또는 영향받는 클러스터만 갱신하고, 저장도 변경분 위주로 처리한다. 삭제로 연결 요소가 나뉘는 경우를 포함한다.
- 공개 지도도 데이터 버전·조회 권한 범위에 맞는 클러스터 스냅샷을 재사용한다.
- 요청 시에는 저장된 요약·해당 클러스터의 페이지만 읽는다.

## 6. 역지오코딩: 캐시 미스가 HTTP 스레드 대기로 바뀐다

근거: [TravelReverseGeocodeService.java:100](/C:/project/calen/backend/src/main/java/com/playdata/calen/travel/service/TravelReverseGeocodeService.java:100), [제공자 슬롯 대기:189](/C:/project/calen/backend/src/main/java/com/playdata/calen/travel/service/TravelReverseGeocodeService.java:189), [기본 간격:117](/C:/project/calen/backend/src/main/resources/application.yml:117).

캐시에 없으면 요청 스레드에서 `synchronized` 메서드에 진입해 `Thread.sleep()`으로 최소 1,200ms 간격을 기다린다. 기본 설정에서 한 JVM의 외부 요청 시작 빈도는 약 0.83건/초다. 서로 다른 미캐시 좌표 100개가 함께 요청되면 마지막 요청의 슬롯 대기는 약 119초까지 늘어날 수 있으며 외부 응답 시간은 별도다. 같은 좌표가 동시에 들어와도 캐시 조회 이후의 중복 호출을 합치는 처리가 없다.

기본 제공자인 공용 Nominatim은 애플리케이션 전체에 최대 1요청/초 제한을 적용한다. 인스턴스를 늘려 개별 JVM의 대기만 유지하면 제공자 전체 제한을 초과할 수 있다. [Nominatim 공식 정책](https://operations.osmfoundation.org/policies/nominatim/).

개선:

- 좌표별 동시 요청을 하나로 합치고, 허용 가능한 정밀도의 캐시를 재사용한다.
- 외부 호출은 제한된 작업 큐에서 처리하고 사용자 요청 스레드에 긴 대기를 남기지 않는다.
- 제공자 제한을 모든 백엔드 인스턴스에서 공유한다.
- 대규모 신규 좌표 처리에는 처리 용량이 확보된 제공자 또는 자체 서비스를 사용한다.
- 외부 호출의 제한 시간·실패 시 대체 동작을 명시한다.

## 7. 대시보드: SQL 집계는 적용됐지만 같은 데이터를 반복 집계한다

근거: [StatisticsService.java:144](/C:/project/calen/backend/src/main/java/com/playdata/calen/ledger/service/StatisticsService.java:144), [프런트 요약 로딩:1656](/C:/project/calen/frontend/src/components/MainDashboardWorkspace.vue:1656).

`getDashboard()`는 일·주·월·연 4회 집계, 달력, 분류별, 결제수단별, 12개월 비교, 최근 항목을 순차 호출한다. 코드상 가계부 repository 호출은 9개이며 사용자·연관 조회는 별도다. 같은 트랜잭션의 1차 캐시가 반복 사용자 조회를 줄일 수 있어, 이를 사용자 조회까지 포함한 고정 SQL 개수로 해석해서는 안 된다.

프런트는 대시보드와 별도로 2주·2개월 비교, 분류, 결제수단, 여행 목록을 요청한다. 관리자에게는 드라이브 홈·최근 파일도 추가된다. 빠른 입력 저장 후에도 전체 요약을 다시 읽는다. 동시에 접속하는 사용자 수에 요청 수와 반복 집계 비용이 곱해진다.

개선:

- 일·주·월·연 합계를 조건부 집계 하나로 묶거나 제한된 기간의 일별 합계를 재사용한다. 연말·월말에 주간 범위가 다른 해·월을 넘는 경우를 포함한다.
- 필요한 주·월 비교를 대시보드 응답에 함께 내려 중복 요청을 줄인다.
- 사용자·조회 기준일·데이터 버전별 요약을 짧게 캐시하고, 가계부 변경 커밋 후 무효화한다.
- 데이터량이 커지면 사용자·날짜별 집계 테이블과 증분 갱신을 검토한다.

현재 통계는 SQL 집계를 사용하며, [LedgerEntry.java:26](/C:/project/calen/backend/src/main/java/com/playdata/calen/ledger/domain/LedgerEntry.java:26)에 소유자·삭제 여부·날짜 등의 복합 인덱스가 선언돼 있다. 전체 가계부를 Java에서 집계하는 구조로 단정할 수 없다.

## 8. 지도·포트폴리오: 응답 제한 이후에도 서버가 전체 데이터를 처리한다

근거: [인접 핀 조회:494](/C:/project/calen/backend/src/main/java/com/playdata/calen/travel/service/TravelService.java:494), [지도 개요:437](/C:/project/calen/backend/src/main/java/com/playdata/calen/travel/service/TravelService.java:437), [전체 포트폴리오:367](/C:/project/calen/backend/src/main/java/com/playdata/calen/travel/service/TravelService.java:367), [스냅샷 상세 구성:1804](/C:/project/calen/backend/src/main/java/com/playdata/calen/travel/service/TravelService.java:1804), [단일 클러스터 상세:797](/C:/project/calen/backend/src/main/java/com/playdata/calen/travel/service/TravelService.java:797).

인접 핀은 사용자 좌표 기록 전체를 읽고 Java에서 거리순으로 정렬한 뒤 일부만 반환한다. 비용은 전체 기록 조회와 O(N log N) 정렬이다. 개요는 모든 마커·사진 핀·경로를 응답에 포함하고, 포트폴리오는 예산·기록·미디어·경로 전체를 읽는다. Redis 캐시가 적중해도 큰 JSON 전송과 브라우저 렌더링 비용은 남는다.

사진 클러스터 상세는 해당 클러스터 하나를 반환하기 전에 사용자 전체 사진 상세가 담긴 스냅샷을 읽는다. Redis 스냅샷 하나를 통째로 역직렬화하는 비용도 사용자 전체 데이터량에 비례한다.

개선:

- 지도 화면 범위와 줌 수준에 맞춰 bounding box 및 클러스터 요약을 조회한다.
- 가까운 핀은 공간 인덱스·격자로 후보를 줄인 뒤 거리 정렬과 제한을 수행한다.
- 개요·경로 요약·사진 상세를 나누고, 상세는 클러스터별 키 또는 DB 커서 페이징으로 가져온다.
- 전체 포트폴리오에는 기간 제한이나 단계별 로딩을 적용한다.
- 개인 데이터 캐시 키에는 사용자·권한 범위를 포함한다.

## 9. 미디어와 캐시: 요청 시 생성 및 만료 순간의 부하가 남아 있다

근거: [썸네일 fallback:336](/C:/project/calen/backend/src/main/java/com/playdata/calen/travel/service/TravelMediaStorageService.java:336), [원본 전체 읽기·파생 이미지 생성:607](/C:/project/calen/backend/src/main/java/com/playdata/calen/travel/service/TravelMediaStorageService.java:607), [썸네일 실패 시 원본 반환:589](/C:/project/calen/backend/src/main/java/com/playdata/calen/travel/web/TravelController.java:589), [캐시 조회·계산:285](/C:/project/calen/backend/src/main/java/com/playdata/calen/travel/service/TravelService.java:285), [로컬 미디어 캐시:2301](/C:/project/calen/backend/src/main/java/com/playdata/calen/travel/service/TravelService.java:2301).

준비된 썸네일이 없으면 요청 시 원본을 읽어 파생 이미지를 생성하고 저장한다. 동시 요청이 같은 원본의 생성 작업을 중복 수행할 수 있다. 생성에 실패하면 작은 썸네일 요청에 원본을 반환해 전송량이 커진다. 준비된 썸네일, presigned 업로드, 백필 기능은 이미 있으므로 이 경로들을 중심으로 생성 부하를 줄일 수 있다.

여행 요약은 cache-aside 방식이다. TTL 만료나 무효화 뒤 동시 요청이 들어오면 같은 데이터를 여러 번 계산할 수 있고, Redis 장애 시에는 모두 DB 경로로 진행한다. 로컬 `ownedMediaDownloadCache`는 만료를 조회 시 제거하며 전체 크기 제한·주기적 퇴거가 없다. 다시 읽지 않는 키가 계속 쌓이면 만료한 메타데이터가 메모리에 남는다. 이 캐시는 바이너리를 담는 캐시가 아니다.

개선:

- 썸네일 생성은 백그라운드 작업으로 처리하고 object key별 중복 생성을 막는다. 누락 시 작은 대체 이미지나 명시적 준비 상태를 반환한다.
- 권한이 허용되는 미디어 전달에는 짧은 수명의 서명 URL과 CDN을 적용해 앱 서버의 데이터 전송을 줄인다.
- 무거운 요약 계산은 키별 동시 계산을 합치고, TTL에 분산을 주며 허용 가능한 데이터에는 만료 데이터 재사용을 검토한다.
- 로컬 캐시는 크기 제한과 시간 기반 퇴거가 있는 구현을 사용한다.
- 캐시 복구·재연결도 요청 지연 예산 안에서 동작하도록 확인한다. [RedisCacheService.java:166](/C:/project/calen/backend/src/main/java/com/playdata/calen/common/cache/RedisCacheService.java:166)는 요청 경로에서 모니터를 잡고 연결을 시도한다.

## 10. 인스턴스를 늘리기 전에 공유 상태와 작업 배치를 정리해야 한다

근거: [SecurityConfig.java:151](/C:/project/calen/backend/src/main/java/com/playdata/calen/account/security/SecurityConfig.java:151), [backend/build.gradle:28](/C:/project/calen/backend/build.gradle:28), [반복 가계부 전체 처리:124](/C:/project/calen/backend/src/main/java/com/playdata/calen/ledger/service/RecurringLedgerService.java:124), [배포 구성](/C:/project/calen/docker-compose.oci.app.yml), [데이터 서버 구성](/C:/project/calen/docker-compose.oci.data.yml).

인증은 `HttpSessionSecurityContextRepository`를 사용하며 소스·의존성에서 Spring Session을 통한 Redis 세션 공유는 확인되지 않는다. 별도 컨테이너 클러스터 설정이 없다면 세션은 인스턴스 로컬이다. 상태용 Redis가 연결돼 있다는 사실만으로 로그인 세션이 공유되지는 않는다. 서버가 바뀔 때 인증과 2차 PIN 상태가 유지되도록 세션 저장소 또는 세션 고정 정책을 결정해야 한다. Redis 세션 구현 방법은 [Spring Session 3.5 공식 문서](https://docs.spring.io/spring-session/reference/3.5/guides/boot-redis.html)에서 확인할 수 있다.

AI·OCR 작업 큐와 제공자 락도 JVM 로컬이다. 반복 가계부는 활성 규칙 전체를 하나의 `synchronized` 쓰기 트랜잭션으로 처리한다. 인스턴스가 늘면 같은 스케줄 작업을 각 인스턴스가 시작할 수 있고, 규칙 수가 늘면 긴 트랜잭션·경쟁 비용이 커진다. DB 유일 제약에 따른 중복 방어와 별개로 작업 소유권·중복 실행을 제어해야 한다.

개선:

- 공유 세션 저장소와 로드 밸런서를 준비하고, 재인증·세션 만료 동작을 확인한다.
- 작업은 lease·락·원자적 상태 전환으로 소유권을 정하고, 재시작 시 미완료 작업을 회수한다.
- 반복 가계부와 백필은 제한된 배치·짧은 트랜잭션으로 나누고 전용 스케줄러에서 실행한다.
- 앱의 모든 인스턴스가 사용하는 DB 연결 수 합계를 관리한다.
- 제공된 구성에서 MariaDB와 MinIO는 한 데이터 서버를 공유한다. 미디어·백업과 DB의 디스크·네트워크 경쟁은 실제 메트릭으로 확인하고 필요 시 분리한다.

## 추가 확인: 검색과 성능 관측

가계부 검색은 [LedgerEntryRepository.java:67](/C:/project/calen/backend/src/main/java/com/playdata/calen/ledger/repository/LedgerEntryRepository.java:67)에서 여러 필드의 `%keyword%` 조건을 사용한다. 소유자·날짜 인덱스로 검색 범위를 줄일 수 있지만, 제목·메모의 일반 인덱스만으로 포함 검색을 빠르게 처리하기는 어렵다. 기간 제한, 필터별 동적 쿼리, 검색 의미에 맞는 전문 검색을 검토한다. 전문 검색은 기존 부분 문자열 검색과 결과가 달라질 수 있다. [MariaDB LIKE 최적화 문서](https://mariadb.com/docs/server/reference/sql-functions/string-functions/like#optimizing-like).

Prometheus, Hikari 풀 포화·대기 경보, JVM·AI·OCR 메트릭은 이미 있다. 다만 [p95 경보:43](/C:/project/calen/deploy/oci/monitoring/prometheus/rules/calen-alerts.yml:43)는 `http_server_requests_seconds_bucket`을 사용하며, 저장소에서는 HTTP 히스토그램을 켜는 설정을 확인하지 못했다. 운영 override 여부를 확인하고 실제 `/actuator/prometheus`에서 bucket 시계열이 나오는지 검증해야 한다. 필요 시 `management.metrics.distribution.percentiles-histogram.http.server.requests=true` 또는 목표 지연별 SLO bucket을 설정한다. [Spring Boot 3.5 메트릭 설정](https://docs.spring.io/spring-boot/3.5/reference/actuator/metrics.html#actuator.metrics.customizing.per-meter-properties).

## 개선 순서와 검증 기준

1. AI 엑셀의 트랜잭션 범위를 줄이고, OCR 큐의 이미지 바이트 보관과 ZIP 전체 버퍼링을 제거한다.
2. 드라이브의 SQL 필터·집계·개수 제한과 부모 조건 조회를 적용한다.
3. 사진 클러스터 재계산을 비동기로 옮기고 공간 후보 제한·변경분 저장을 적용한다.
4. 역지오코딩 동시 요청 합치기와 제공자 전체 대기·처리량 제어를 적용한다.
5. 대시보드 집계·요청 중복, 지도 응답 범위, 캐시 만료 시 중복 계산을 개선한다.
6. 세션·작업 공유와 배치 분할을 준비한 뒤 인스턴스 확장을 검증한다.

별도 테스트 환경에서 일반 사용자·큰 데이터 사용자, 같은 사용자 집중 요청·여러 사용자 요청, 캐시 적중·미스 조건을 나눠 비교한다. 초기 데이터 예시는 가계부 1천/1만/10만 건, 드라이브 1천/1만 항목, GPS 사진 1천/5천/1만 장이다. 동시 요청은 10/50/100 단계로 올리고, 포화가 확인되면 해당 자원을 측정한다. 이 숫자는 검증 시작점이며 목표 처리량 보장은 아니다.

| 시나리오 | 확인할 결과 |
|---|---|
| AI 엑셀 요청이 몰린 상태의 일반 가계부 조회 | 외부 모델 대기가 일반 요청의 DB 풀 대기로 전파되는지 |
| 10MB OCR 이미지 다수 접수 | 대기 작업 수 대비 힙 증가, GC, 접수 거절과 대기 시간 |
| 큰 단일 파일·ZIP 동시 다운로드 | 바이너리 메모리, 전송 처리량, DB 연결 점유 시간 |
| 파일 1만 개 계정의 최근 파일·홈 요약 | SQL별 읽은 행 수와 쿼리 시간, 반환 행 수 |
| 큰 폴더 트리의 휴지통 이동·ZIP 수집 | 전체 사용자 파일 조회가 노드마다 반복되는지 |
| 사진 1만 장 계정의 추가 업로드·공개 지도 | 클러스터 계산 시간, 삭제·삽입 행 수, 트랜잭션 시간 |
| 미캐시 좌표 동시 요청 | 사용자 요청 대기 시간과 제공자 전체 호출 빈도 |
| 캐시 만료 직후 동시 대시보드·지도 요청 | 같은 캐시 키의 계산 횟수, DB 부하 급증 |
| 백엔드 두 인스턴스·재시작 | 세션 유지, 큐 복구, 작업 중복 실행, 제공자 동시성 제한 |

공통으로 URI별 p95/p99·성공 처리량·오류율, Hikari active/pending/acquire 시간, SQL 수·읽은 행 수, JVM 힙·GC 정지, 작업 큐 깊이·대기 시간, MinIO 지연·전송량을 수집한다. 제공자 추론 시간과 작업 큐 대기 시간은 따로 측정해야 사용자가 실제로 기다린 시간을 알 수 있다.

이미 적용된 SQL 통계 집계, 가계부 인덱스 선언, 일부 `EntityGraph`·배치 조회, 목록 페이징, 여행 캐시·클러스터 스냅샷, 준비된 썸네일·presigned 업로드, Vue 지연 로딩, 정적 자원 장기 캐시·gzip을 유지하면서 남은 경로를 개선하는 방향이다.
