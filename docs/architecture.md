# 아키텍처

## 백엔드 내부 구조 리팩토링 — 2026-10-17

목표별 적용 범위·최종 회귀 결과와 남겨 둔 기존 경계는 [최종 감사](backend_refactoring_acceptance.md)에 정리했습니다. 점진적 구조 개선 완료이며 전 백엔드의 완전 전환·운영 배포 완료를 뜻하지 않습니다.

기능별 패키지와 단일 배포를 유지하면서 MVC의 HTTP 책임과 application/domain/adapter 경계를 점진적으로 분리하고 있습니다. 공유 요청 생성, 가계부 입력 준비, 기록 지도 쓰기·조회, 여행 계획/예산/지출·추억 기록 쓰기를 분리하고 여행 요약 캐시·계획/금액/좌표 정책을 공통으로 정리했습니다. 여행 클러스터의 순수 계산·차등 투영 저장은 전용 유스케이스로 옮겨 비동기 coordinator의 대형 서비스 역참조를 제거했습니다. 계획·기록 삭제의 공통 첨부 파일 정리는 커밋 성공 후에 수행하도록 보강했습니다. 나머지 기존 업무가 모두 전환된 것은 아닙니다. 의존 규칙·보존 조건·단계별 코드 및 테스트 근거는 [백엔드 리팩토링 진행 문서](backend_refactoring_progress.md)를 기준으로 합니다. 아래 배포·저장소 구성과는 구분합니다.

소유 클러스터 상세 조회·대표 사진 변경은 `ManageTravelPhotoClustersUseCase`로 분리했습니다. HTTP는 전용 Controller, 트랜잭션은 입력 어댑터, 소유 행·멤버·사진 조회/변경은 JPA 출력 어댑터가 담당합니다. 조회는 갱신 대기를 트랜잭션 밖에서 수행한 뒤 짧은 읽기 트랜잭션을 엽니다. 대표 변경은 기존 READ_COMMITTED 안에서 소유자 확인·갱신 잠금·소속 검증·대표 플래그 변경·요약 무효화·동기 투영·커밋 후 재갱신을 같은 순서로 수행합니다. 공유/공개 지도도 같은 순수 정렬·페이징 정책을 사용하며 HTTP URL을 코어 값에 넣지 않습니다. 기존 서비스 진입 메서드는 호환 delegate로 남습니다. 지도 개요·공유·미디어·경로도 별도 유스케이스를 사용하며 기존 캐시/파일 정책의 추가 개선은 최종 감사의 후속 범위로 구분합니다.

AI/OCR/Excel의 종료 저장은 소유자·처리 상태를 확인하는 공통 유스케이스/저장 포트를 사용하며 Excel은 추론 시작 버전도 확인합니다. 동기 분석은 결과 직렬화 후 완료 상태를 원자적으로 노출하고 외부 호출 진입에서 트랜잭션을 중단합니다. 재시작 복구 순회도 `RecoverLedgerAiJobsUseCase`로 분리했습니다. 다섯 배치/100건 상한·분석/이미지 ID 커서·Excel 생성 시각+ID 커서·입력 복구 조건·owner 한도와 실행기 포화의 다른 처리 순서는 순수 코어가 담당합니다. `LedgerAiRecoverySources`가 기존 Repository/리스와 지연 작업 포트를 연결하며 기존 커서·작업 목록을 복제하지 않습니다. 짧은 lease/CAS 종료 저장과 호출자 트랜잭션은 유지하고 동기 추론·큐 수명 관리 및 OCR/Excel 접수의 나머지 orchestration은 기존 경계에 남습니다.

Excel 분석은 워크북 입력 준비와 외부 모델 통신을 분리했습니다. 제공자 독립 추출 포트가 반환한 후보에 순수 정책을 적용해 원본 행 연결·중복·등록 선택지를 검증하고 기존 미리보기 DTO로 반환합니다. 저장된 작업 입력 JSON과 기존 제공자 프로토콜은 유지합니다.

일반 가계부 분석도 `LedgerSpendingAnalysisPort`의 순수 요청/결과·보고서 모델을 사용합니다. 제공자 선택 및 LM Studio/OpenAI/Ollama·n8n 전송은 출력 어댑터, JSON 별칭/envelope·기존 평문 fallback은 응답 어댑터가 소유합니다. 출력 안전 검증은 같은 규칙·순서의 순수 정책으로 분리했습니다. 기존 v2 준비/저장 입력은 `LedgerAnalysisPreparedData`에서 보존하고, 날짜·금액 scale·필드 순서·null·열거 코드의 JSON 호환성을 검증합니다. 준비 빌더는 더 이상 대형 분석 서비스의 중첩 타입을 참조하지 않습니다. fallback 보고서와 기간 계산은 순수 정책, 전체 통계/지출 조회 조정은 `PrepareLedgerAnalysisDatasetUseCase`, 기존 통계/Repository 접근과 DTO 변환은 출력 어댑터가 담당합니다. 조회 전체에 새 트랜잭션을 씌우지 않고 각 소스의 기존 트랜잭션·호출 순서·주간 top 조회 생략을 유지합니다. provider의 제한된 입력과 로컬 전체 자료는 구분하며 공유 payload/response 조립은 `LedgerAnalysisFormats`에 둡니다.

분석 이력 목록/상세/삭제와 completed/processing 매칭·저장 결과 판단은 `ManageLedgerAnalysisHistoryUseCase`로 분리했습니다. 코어는 소유자 확인 순서·검색 날짜/페이지·조회용 fallback과 재사용용 null 처리의 차이를, JPA/JSON/HTTP 어댑터는 기존 쿼리·역직렬화·29필드 응답 계약을 담당합니다. 제공자/모델/기간·비교 날짜·createdAfter/Limit(1)은 유지하고 비교 프리셋 조건을 새로 넣지 않습니다. CRUD는 기존 readonly/쓰기 REQUIRED이고 내부 matching/decoding에는 새 트랜잭션을 추가하지 않습니다. 손상된 결과는 금액 0과 기존 안내문으로 노출하되 재사용하지 않으며 PROCESSING 결과는 읽지 않습니다. 5분 재사용·SingleFlight·종료 CAS 규칙은 그대로입니다.

비동기 분석 접수는 `StartLedgerAnalysisUseCase`가 사용자/설정 확인→선택적 분산 접수 lease→진행 중/완료 이력 재사용→새 PROCESSING 생성/제출/실패를 조정합니다. `LedgerAnalysisAdmissions`는 요청별 operation에 검증된 owner와 새 행을 보관하고 기존 key/TTL/작업 map/finalizer를 연결합니다. 새 공유 registry나 트랜잭션은 없습니다. 기존 record 문자열 기반 MD5 접수 identity를 유지하며, lease가 찼을 때는 processing만 재사용하고 custom focus는 1초 retry의 기존 거부 응답을 유지합니다. 요약은 제출 이후에 읽습니다. 동기/비동기의 초기 이력 필드·제목 생성은 공통 draft adapter와 순수 정책으로 옮겼고 동기 추론·큐 수명 관리는 아직 기존 경계에 남습니다.

백그라운드 분석은 `ExecuteLedgerAnalysisUseCase`가 담당합니다. 소유자 범위 상태 조회→전체 자료 준비→기존 v2 직렬화/조건부 저장→timer 시작→provider 요청 변환/추론→lease 확인→결과 직렬화→종료 CAS 순서를 보존합니다. `LedgerAnalysisFormats`는 동기/비동기 payload·응답 조립과 안전한 실패 문구를 공유하고 provider 200/120건 제한을 로컬 전체 보고서에 적용하지 않습니다. `LedgerAnalysisWorkTelemetry`는 기존 logger category·bounded 메시지와 null timer의 기존 동작을 유지합니다. lease 상실은 결과/실패/metric을 반영하지 않고 terminal CAS 패배만 discarded로 기록합니다. 입력 어댑터 직접 호출도 NOT_SUPPORTED이며 저장 어댑터에 새 트랜잭션을 추가하지 않습니다. 기존 FutureTask·예약·리스 해제·큐 cleanup은 그대로이고, 순수 코어에는 HTTP/JPA/DTO나 작업 registry가 없습니다.

OCR 이미지 분석은 `LedgerImageExtractionPort`로 외부 추출을 요청하고 프레임워크 없는 `LedgerImageData` 결과를 받습니다. HTTP 전송/프롬프트/모델 선택은 `ImageLedgerExtractionHttpAdapter`, JSON 별칭·행별 날짜/시간·수입/지출 보정은 `LedgerImageResponseCodec`이 담당합니다. 이미지 바이트는 기존처럼 필요 시 읽고 전송 포트 직접 호출 시에도 트랜잭션을 중단합니다. 동기/비동기 미리보기 조립을 공통화했지만 소유권·접수/복구·분류·제안 금액 정책은 아직 레거시 OCR 흐름에 남습니다. 추출 결과만으로 가계부를 자동 저장하지 않습니다.

이미지 원본 저장은 `LedgerImageStoragePort`의 MinIO 어댑터로 분리했습니다. `StoreLedgerImageInputUseCase`는 저장소 사용 여부 → 스트림 업로드 → 짧은 소유자 범위 참조 갱신 → 확실한 갱신 0건일 때 새 객체 정리를 조정합니다. 취소된 작업의 원본 연결·버전 증가·상태 보존과 저장소 미설정 시 임시 스풀 경로를 유지합니다. 업로드 전용 포트에만 JDK `InputStream`/`IOException` 선언을 허용하며 업무 코어의 직접 I/O·포트 default 메서드는 금지합니다. 이력 삭제의 기존 저장소→DB 순서 및 모호한 참조 갱신 실패 시 무조건 삭제하지 않는 동작도 유지합니다. DB와 객체 저장소의 원자적 커밋이나 durable cleanup outbox를 도입한 것은 아닙니다.

이 문서는 OCI 분리 배포, Redis 도입, 여행 미디어 처리 개선 이후의 현재 Calen 구조를 요약합니다.

임베딩 자동 동기화의 한 배치도 순수 `SynchronizeLedgerEmbeddingsUseCase`와 jobs/index 출력 포트로 분리했습니다. worker는 기존 스케줄·공유 리스·busy·안전 로그와 전송 전 무트랜잭션 검사를 유지합니다. 코어는 최신 claim만 전송하고, 기존 저장 어댑터의 owner/revision/token/lease 조건으로 완료·실패를 반영합니다. HTTP 어댑터 직접 진입도 NOT_SUPPORTED로 DB 트랜잭션을 중단합니다. 기존 outbox SQL·문서 텍스트/해시·결정적 ID·재시도·활성화 기본값은 바꾸지 않았고 RAG나 기존 자료 전체 적재를 추가하지 않았습니다. 문서 생성기의 JPA 접근/정책 및 기동 capture의 추가 정리는 남습니다.

여행 경로 생성·수정·삭제는 `WriteTravelRouteUseCase`와 MANDATORY JPA 저장 어댑터로 분리했습니다. 순수 정책은 좌표·연속 중복·점 개수·색 규칙을, payload 어댑터는 기존 JSON/POLYLINE6·GPX 보안 파서·원본 실패 시 snapshot 대체·스키마 컬럼 크기 cache를 담당합니다. 소유권 확인 → 저장/응답 조립 → 요약 무효화 순서와 기존 URI/JSON을 유지합니다. 지도 snapshot과 전체 상세의 원본 GPX 열기 여부·nullable 시간·색/파일명 기본값 차이도 유지합니다. 개별 경로 삭제는 기존 즉시 링크/파일 정리 순서를 보존하므로 DB rollback 시 물리 파일 복원을 보장하지 않습니다. 계획 전체 삭제의 커밋 후 cleanup과 구분하며 durable 보상은 아직 별도 범위입니다.

GPX 파일의 업로드·presign·complete·abort도 `ManageTravelRouteFilesUseCase`와 전용 Controller/저장소 포트로 분리했습니다. 기존 저장소 구현이 owner/plan/route key scope·파일 검증·SDK를 담당하고, bytes는 원래 getBytes 경로로 지연 전달합니다. GPX Upload 선언에만 IOException을 허용하며 코어는 직접 I/O를 수행하지 않습니다. prepare/abort readonly와 upload/complete 쓰기 트랜잭션, 두 교체 방식의 서로 다른 파일 정리 순서 및 기존 부분 실패/물리 복구 한계를 보존합니다. API·DB·배포는 변경하지 않았습니다.

소유 여행 미디어의 prepare·complete·삭제·다운로드는 `ManageOwnedTravelMediaUseCase`로 분리했습니다. 입력 Controller는 기존 여섯 URI·principal·검증·DTO를 유지하고, transaction adapter가 readonly/쓰기 경계를 설정합니다. JPA 어댑터는 소유자·기록 종류·활성 사용자·일괄 저장 후 링크와 detached 응답 값을, storage adapter는 기존 MinIO 검증기/SDK를 연결합니다. MIME·GPS 7자리 반올림·caption 정책은 순수 코어에 둡니다. 300초/4096개 owner+media 메타데이터 캐시는 독립 어댑터로 옮겼지만 상위 삭제 연계와 in-flight 재발행 경합을 새로 해결한 것은 아닙니다.

`TravelMediaHttpResponses`는 권한 검사가 끝난 metadata만 받아 썸네일·원본 HTTP 응답을 만듭니다. 공유·공개 권한 판단은 기존 진입점에 남습니다. 일반 썸네일의 원본 fallback·public max-age 헤더와 로그인 전용 썸네일의 404/no-store, 기록 공유 응답의 private/no-store 차이를 보존합니다. 일반 fallback이 fail-closed 보안 목표를 만족한다는 의미가 아니며 별도 정책 변경이 필요합니다. 저장 도중 실패/DB rollback의 기존 물리 파일 보상 한계도 유지합니다.

여행 공유 관리의 8개 API는 `ManageTravelSharingUseCase`로 분리했습니다. 수신자 검색·소유 그룹 조회/저장/삭제·완성 여행 공유/목록/취소·공개 전환의 업무 순서는 순수 코어가, 활성 계정·소유 계획·공유 행·그룹 멤버 저장은 MANDATORY JPA 어댑터가 담당합니다. 기존 그룹은 소유자/이름의 대소문자 무시 조회 후 멤버 전체 검증→삭제/flush→교체를 유지하며, 계정의 canonical login으로 중복을 제거합니다. 기존 unique constraint와 기본 격리 수준을 유지하고 새 잠금/재시도는 도입하지 않습니다. 요약 cache의 public 무효화는 기존 공통 출력 포트로 노출했으며 실제 bean/registry와 즉시·커밋 후 시점은 동일합니다. 수신 전시·공유 사진·공개 지도 조회 권한은 별도 읽기 유스케이스에 연결하며 관리 유스케이스에서 판단하지 않습니다.

공유 조회·미디어 권한은 `ReadTravelExhibitsUseCase`로 분리했습니다. 수신자·완료 계획·동일 media.plan 확인과 HMAC token-first 공개 범위 검사는 순수 코어가, scoped DB 조회와 detached 값 생성은 MANDATORY JPA adapter가 맡습니다. 승인된 기록 공유 bridge는 기존 RecordSharingService의 현재 승인·그룹 권한 검사 뒤에서만 호출하며 브라우저 owner/plan 입력에 직접 노출하지 않습니다. 소유자용 metadata cache를 공유 권한 cache로 쓰지 않습니다. 서버에 도착한 요청은 현재 공개/공유 상태를 확인하지만 기존 generic HTTP thumbnail cache/fallback 정책을 변경한 것은 아닙니다.

계획 상세는 공통 `TravelPlanDetailData`/`TravelPlanDetailMapper`를 사용하고 URL은 기존 소유/수신/기록 공유별 주소로 HTTP mapper에서 구분합니다. 기존 27필드와 nested DTO·정렬·합산 후 반올림·null·full GPX 읽기를 유지합니다. 고정한 이전 DTO 조립 코드와 생성 데이터 150개에서 전체 JSON을 비교하며, H2에서 승인 전/취소 후/현재 그룹 제외 후 접근과 private/no-store 헤더를 확인합니다. 초기 자동 승인 검토 한도는 재시도 가능 시각 후 동일 승인 경로로 해소했고, 이번 코드의 전체 백엔드 회귀와 단일 WAR 검증이 통과했습니다. 운영 DB/스토리지·외부 CI/배포를 검증한 것은 아니며 구체적인 결과·남은 범위는 작업 기록에서 구분합니다.

소유 지도 조회 세 API는 `ReadOwnedTravelMapUseCase`로 분리했습니다. `TransactionalOwnedTravelMap`은 기존 공통 `TravelSummaryCache`의 SingleFlight를 재사용하고 클러스터 generation 대기를 DB 트랜잭션 밖에 둡니다. 개요의 누락 투영 저장을 위한 쓰기 가능 트랜잭션과 핀 묶음·사진 액자의 읽기 전용 트랜잭션을 구분합니다. `JpaOwnedTravelMapReadAdapter`는 활성 소유자·MEMORY·좌표 범위와 기존 SQL 후보 제한을, 순수 코어는 구면 거리·반경 확장·정렬·집계를 담당합니다. 기존 Redis key/generation envelope/TTL/무효화 시점·캐시 URL·null·JSON을 유지하며 경로 개요에서는 원본 GPX를 열지 않습니다. bearer map-share와 인증 atlas/community는 각각 별도 유스케이스에 연결했고, 가계부 양방향 반영은 별도 유스케이스로 분리했으며 소유 집계도 전용 읽기 유스케이스로 분리했습니다.

여행↔가계부 반영은 `ReflectTravelLedgerUseCase`와 순수 정책·값으로 분리했습니다. 두 POST는 전용 HTTP Controller로 옮기고 writable 입력 트랜잭션 안에서 MANDATORY 저장·기존 가계부 writer·부수효과 어댑터를 호출합니다. 소유 범위/조회 순서, 기본 분류·결제수단의 생성·재활성화, 순차 재반영/휴지통 제외, 외화 반올림과 텍스트 제한, 역방향 기존 위치·공유 상태를 보존합니다. 역방향에만 같은 TravelSummaryCache의 즉시/커밋 후 무효화와 owner별 dirty/dispatch 묶음을 적용하며 응답 변환 실패도 SQL·링크·이력과 함께 롤백됩니다. 새 잠금/동시성 유일성 제약·DB 스키마는 도입하지 않았습니다. 동작 비교 oracle과 H2 회귀의 근거는 진행 문서에 기록합니다.

소유 계획 목록·상세·포트폴리오는 `ReadOwnedTravelPlansUseCase`로 분리했습니다. 활성 소유자 확인은 cache hit보다 먼저이며, MANDATORY JPA 어댑터가 소유 범위·집계 쿼리·정렬·detached 사실을 읽고 순수 정책이 clamp/합산 후 반올림·계획별 묶음을 담당합니다. 목록의 SQL 집계와 상세/portfolio의 null-kind 처리 차이도 기존처럼 유지합니다. 목록/portfolio는 같은 TravelSummaryCache에서 flight 진입 전에 외부 트랜잭션을 중단한 뒤 짧은 readonly 트랜잭션을 열며, 상세는 기존 readonly REQUIRED 경계를 유지합니다. cache 어댑터는 기존 key/60초 TTL/DTO JSON과 null·과거 표시 값·URL을 보존하고 현재 값으로 재정규화하지 않습니다. 새 flight registry나 cache 권한 판단을 추가하지 않았고, full GPX 상세 읽기와 기존 원본 실패 시 snapshot fallback을 유지합니다.

여행 분류·환율 조회도 `ReadTravelReferenceUseCase`로 분리했습니다. 소유자 검증은 분류 읽기·통화 검증·제공자보다 먼저이고, 소유 범위 사실은 MANDATORY JPA 어댑터가 읽습니다. 기본/사용자 분류별 정렬과 case-sensitive 첫 값 유지, null-kind와 통화/locale/빈 항목 규칙은 순수 정책이 보존합니다. 환율 어댑터는 기존 ExchangeRateService 하나를 재사용해 provider cache/날짜/fallback을 바꾸지 않으며 unavailable은 기존 JSON으로 반환합니다. 기존 readonly REQUIRED를 유지하고 AI의 트랜잭션 중단 정책을 여기에 임의 적용하지 않았습니다. TravelService는 업무·저장소 의존 없이 입력 port에 위임하는 호환 facade이며 compiled 의존 검사가 이 조건을 고정합니다.

AI 분석의 로컬 fallback 보고서·반복 지출·결제수단 계산은 `LedgerLocalReportPolicy`와 `LedgerLocalReportData`로 분리했습니다. 입력은 전체 caller dataset의 순수 값이고 capped provider Request와 구분합니다. `LedgerLocalReportMapper`가 기존 통계/준비 DTO와 응답을 변환하며 저장 v2 schema·제공자 전송 제한·원격 merge 우선순위·작업 종료/복구는 바꾸지 않았습니다. 기존 한국어 문구·순서·key/중복/상위 10개·일수/반올림/금액 포맷을 그대로 검증합니다. 한국 숫자 포맷의 순수 JDK NumberFormat만 해당 정책에서 허용하며 다른 java.text/I/O 의존은 계속 차단합니다. 새 I/O 포트나 Spring 빈을 만들지 않았고 기존 async/orchestration 분리는 별도 후속 범위입니다.

## 1. 제품 구조

인증 사용자의 공개 atlas·클러스터·커뮤니티 feed 조회는 `ReadTravelAtlasUseCase`로 분리했습니다. 공개 완료 계획 우선/직접 수신 완료 계획의 중복 제거와 서로 다른 community 노출 규칙은 순수 정책/코어가, 현재 DB 범위·페이지·detached 값은 MANDATORY 어댑터가 담당합니다. 활성 viewer 검증 뒤 매번 읽으며 atlas에 새 캐시를 도입하지 않았습니다. 공개 사진은 기존 HMAC 발급 서비스에 위임하고 비공개 수신 사진은 공유 행 ID를 반환하며 URL은 HTTP mapper가 조립합니다. 기존 full GPX 읽기·대표 좌표·집계·게시자 이름·페이지 총계/상한을 유지하고 스키마와 HMAC 구현은 변경하지 않았습니다. 소유 계획 목록/상세/portfolio도 별도 읽기 유스케이스에 연결했습니다.

공개 지도 bearer 링크 생성·지도/클러스터/사진 조회·로그인 축소 미리보기는 `TravelMapLinksUseCase`와 `TransactionalTravelMapLinks`로 분리했습니다. MANDATORY JPA 어댑터는 소유 범위·저장 JSON·현재 링크/계획과 detached 값만 담당하고, 코어는 선택/제외 검증·현재 범위·클러스터·샘플링을 조정합니다. 토큰은 기존 24바이트 SecureRandom·중복 확인·URL-safe 형식으로 외부 어댑터에서 생성합니다. 생성은 writable, 읽기는 readonly이며 소유자용 캐시를 공유 권한으로 사용하지 않습니다. 링크에는 새 만료/완료/공개 상태 규칙을 추가하지 않았고 기존 scope JSON의 fail-soft 동작도 유지했습니다. 로그인 설정 서비스는 더 이상 TravelService를 참조하지 않으며 기존 thumbnail-only/no-store와 GPX 원본 해석 차이를 유지합니다. 인증 atlas/community는 별도 읽기 유스케이스에, 가계부 연동은 별도 쓰기 유스케이스에 연결했습니다.

Calen은 크게 아래 네 도메인으로 구성됩니다.

- 가계부
- 여행
- 가족 앨범
- 계정 / 관리자

각 도메인은 화면과 업무 흐름이 다르지만, 아래 자원을 공유합니다.

- 공통 인증/세션
- 하나의 백엔드 애플리케이션
- 하나의 MariaDB
- 하나의 오브젝트 스토리지 계층

## 2. 배포 구조

### 로컬 개발

로컬에서는 아래를 한 번에 띄울 수 있습니다.

- frontend
- backend
- MariaDB
- MinIO

기준 파일:

- [`docker-compose.yml`](/C:/Users/kjs99/Desktop/calen/docker-compose.yml)

### OCI 운영

운영은 책임별로 나눈 구조를 기준으로 합니다.

#### 앱 서버

- Spring Boot backend
- Vue frontend
- public nginx

관련 파일:

- [`docker-compose.oci.app.yml`](/C:/Users/kjs99/Desktop/calen/docker-compose.oci.app.yml)
- [`.env.oci.app.example`](/C:/Users/kjs99/Desktop/calen/.env.oci.app.example)

#### 데이터 서버

- MariaDB
- MinIO

관련 파일:

- [`docker-compose.oci.data.yml`](/C:/Users/kjs99/Desktop/calen/docker-compose.oci.data.yml)
- [`.env.oci.data.example`](/C:/Users/kjs99/Desktop/calen/.env.oci.data.example)

#### Redis A: 캐시 서버

- 역지오코딩 캐시
- 여행 목록 요약 캐시
- 여행 포트폴리오 캐시
- 내 지도 개요 캐시

#### Redis B: 상태/락 서버

- 로그인 시도 횟수/차단 상태
- 관리자 백업/복구 작업 락

관련 파일:

- [`deploy/oci/redis/docker-compose.redis.cache.yml`](/C:/Users/kjs99/Desktop/calen/deploy/oci/redis/docker-compose.redis.cache.yml)
- [`deploy/oci/redis/docker-compose.redis.state.yml`](/C:/Users/kjs99/Desktop/calen/deploy/oci/redis/docker-compose.redis.state.yml)

## 3. 여행 미디어 흐름

최근 구조 변경 중 가장 큰 축은 여행 이미지 업로드/제공 방식입니다.

### 업로드 흐름

1. 프런트가 EXIF / 날짜 / GPS 메타데이터를 읽음
2. 프런트가 백엔드에 presigned 업로드 대상을 요청
3. 브라우저가 원본 파일을 MinIO로 직접 업로드
4. 백엔드가 업로드 완료를 검증
5. 백엔드가 DB에 메타데이터를 저장
6. 백엔드가 준비된 썸네일을 생성

### 저장 책임

- DB는 미디어 메타데이터를 저장
- MinIO는 원본 이미지를 저장
- MinIO는 준비된 썸네일도 함께 저장

이미지 바이너리는 DB에 저장하지 않습니다.

### 썸네일 전략

준비된 썸네일 폭:

- `320`
- `480`
- `960`

현재 동작:

- 썸네일 요청 시 준비된 썸네일을 먼저 확인
- 준비된 썸네일이 없으면 기존 즉석 생성 fallback 가능
- 원본은 크게 보기 / 다운로드에 사용

## 4. Redis 구조

### Redis A

Redis A는 캐시 전용 역할입니다.

현재 사용처:

- 역지오코딩 캐시
- 여행 목록 요약
- 여행 포트폴리오 요약
- 내 지도 개요 데이터

설계 포인트:

- fail-open
- Redis A 장애가 앱 기동 실패로 이어지지 않음
- 재연결은 backoff 기반

### Redis B

Redis B는 임시 상태와 작업 제어에 사용합니다.

현재 사용처:

- 로그인 시도 상태
- 백업/복구 작업 락

이 분리는 캐시 부하가 락/상태값 처리에 영향을 주지 않게 하려는 목적입니다.

## 5. 여행 지도 구조

여행 지도 관련 기능은 한 번에 모든 상세를 내려주는 방식보다 선택적 로딩 쪽으로 이동했습니다.

### 내 지도

- 개요 데이터 우선 로드
- 선택된 핀 상세는 lazy loading
- 주변 핀은 제한된 범위로 함께 프리패치

### 여행 보기 / 여행 로그 지도

- 팝업 이미지 로딩은 더 선택적으로 처리
- 선택 핀 변경 시 불필요한 전체 재렌더를 줄이는 방향으로 조정

## 6. 가계부 달력 구조

가계부 달력은 사용자 크기 조절 기능을 포함합니다.

최근 개선 포인트:

- 리사이즈 중 셀 잘림 감소
- 수직/수평 조절 시 비율 깨짐 완화
- 월 달력 전체가 함께 줄어드는 방향으로 보정

## 7. 운영 포인트

### MinIO

- 주요 오브젝트 스토리지
- 여행 미디어 presigned 업로드 지원
- public 업로드 접근은 백엔드와 reverse proxy 설정을 통해 제어

### 백업

- DB / MinIO 백업과 복구는 관리자 기능에서 처리
- 중복 실행 방지는 Redis B 락 사용

### 캐시 무효화

여행 관련 쓰기 작업 이후 아래 캐시는 무효화됩니다.

- 여행 목록 요약
- 포트폴리오 요약
- 내 지도 개요

## 8. 다음에 읽을 문서

운영과 배포 기준으로는 아래 문서를 이어서 보는 것을 권장합니다.

스키마는 아직 Flyway 단독 관리가 아닙니다. 9개 레거시 보정과 스위치 밖의 5개 기동 DDL, 자동 검증의 범위 및 운영 전환 공백은 [스키마 관리 감사](backend_schema_governance_audit.md)를 참고합니다. 이번 리팩토링은 기존 SQL·운영 기본값을 변경하지 않습니다.

1. [OCI DB + MinIO 분리 배포 가이드](/C:/Users/kjs99/Desktop/calen/docs/OCI_DB_MinIO_분리_배포가이드.md)
2. [OCI MinIO presigned URL 설정 가이드](/C:/Users/kjs99/Desktop/calen/docs/OCI_MinIO_presignedURL_설정가이드.md)
3. [OCI Redis 2서버 설정 가이드](/C:/Users/kjs99/Desktop/calen/docs/OCI_Redis_2Server_설정가이드.md)
