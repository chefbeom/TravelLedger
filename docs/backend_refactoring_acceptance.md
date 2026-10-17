# 백엔드 MVC·헥사고널 리팩토링 — 목표별 최종 감사

> 후속 검증(2026-10-17): 사용자 승인 테스트 VM에 현재 작업물을 배포해 MariaDB·Redis·MinIO·SMTP 실연동 15개 시나리오와 데스크톱/모바일 smoke 8건을 통과했다. 모바일 집계 제목 결함 1건은 남는다. AI 실호출·운영 DB upgrade는 제외했다. 아래는 기존 로컬 감사 시점의 기록이며, 최신 배포 범위·증거·제한은 [격리 VM 실연동 보고서](vm_integration_validation_20261017.md)를 참조한다.

## 판정 범위

2026-10-15에 설정한 목표는 **기존 기능을 유지하는 점진적 구조 개선**이다. 모든 기존 클래스를 새 폴더로 옮기거나, 모든 서비스에 인터페이스를 만들거나, 운영 스키마/배포를 전환하는 작업은 아니다. 기능별 패키지·단일 WAR·기존 API/JSON/DB 계약을 유지하며 실제 업무 단위의 경계와 자동 회귀를 만든다.

최종 전체 회귀·WAR·목표별 감사를 마쳐 **위 점진적 구조 개선 범위는 완료**로 판정한다. 전 백엔드의 완전한 헥사고널 전환이나 운영 배포 완료를 뜻하지 않는다. 단계별 변경·검증 근거는 [진행 기록](backend_refactoring_progress.md), 계층별 규칙은 [아키텍처](architecture.md), 운영 검증 한계는 [스키마 감사](backend_schema_governance_audit.md)에 기록한다.

## 설정한 여섯 목표와 구현 근거

| 목표 | 현재 구현·감사 결과 | 대표 자동 검증 |
| --- | --- | --- |
| 1. 기능별 패키지 유지, MVC/계층 책임·의존 규칙 | `sharing/ledger/recordmap/travel`의 HTTP/트랜잭션 진입을 `adapter/in`, 유스케이스·입출력 계약을 `application`, 순수 업무 값·정책을 `domain/model`·`domain/policy`, DB/JSON/외부 연동을 `adapter/out`으로 분리했다. 기존 JPA 엔티티·DTO는 호환 경계에 유지한다. 컴파일된 메서드 본문·제네릭·중첩 타입까지 의존성을 검사하며 새 코어의 Spring/JPA/HTTP/JSON·다른 기능 엔티티 의존을 거부한다. | `BackendArchitectureTest`; 금지 의존·I/O·검사 대상 누락 반례; 서비스 분해 gate |
| 2. 공유 요청 생성의 첫 수직 단위 | `CreateRecordShareUseCase`와 세 출력 포트, 순수 생성 정책, HTTP/트랜잭션/JPA 어댑터를 적용했다. 원본→수신자 행 잠금·동일 요청 재사용·수신자별 저장의 전체 롤백·수정 스냅샷 JSON·메모/소유권을 보존한다. 승인 등 다른 기존 업무를 생성 코어로 무리하게 합치지 않았다. | `CreateRecordShareServiceTest`, `RecordSharingServiceTest`, 공유 스냅샷 계약·H2 동시성/다중 수신자 롤백 테스트 |
| 3. 가계부·기록 지도 확장, 여행 대형 서비스 책임 분리 | 가계부 입력 준비·금액/텍스트 정책, 기록 지도 쓰기/조회/공개 권한을 분리했다. 여행 계획·예산·기록·경로/GPX·미디어·공유/전시/공개/소유 지도·클러스터·목록/상세/포트폴리오·양방향 가계부 반영·분류/환율을 전용 유스케이스로 옮겼다. `TravelService`는 입력 포트만 가진 387줄 호환 facade다. | 기록 지도 순수/H2/JSON 회귀, 여행 각 `*RefactoringIntegrationTest`, `OwnedTravelPlansIntegrationTest`, `TravelLedgerReflectionIntegrationTest`, 독립 legacy 차등 테스트 |
| 4. AI/OCR/Excel 상태·완료·취소·실패·복구 규칙 | `FinalizeLedgerAiJobUseCase`/순수 정책/조건부 저장 포트가 소유자+PROCESSING 조건을 강제하고 Excel은 추론 버전도 확인한다. 결과 직렬화 후에만 완료를 노출한다. 기존 이미지 취소와 늦은 결과 폐기, 삭제 후 재생성 방지, 리스/owner 한도/예약 해제는 유지한다. 세 복구 순회는 같은 bounded 유스케이스를 사용한다. 일반 분석은 이력 관리·접수/draft·백그라운드 실행도 분리했다. | `FinalizeLedgerAiJobServiceTest`, `LedgerAiJobPersistenceTest`, `RecoverLedgerAiJobsServiceTest`/192 profile oracle, 분석 접수/실행/이력 차등·트랜잭션 테스트 |
| 5. 외부 AI·저장소·임베딩 경계 | 일반 분석·이미지·워크북은 제공자 독립 순수 요청/결과 포트를 사용한다. provider HTTP·응답 codec·안전 정책·기존 v2·전체 로컬 보고서와 capped 입력을 구분한다. 이미지 저장/참조와 MinIO를 분리하고 실패 보상을 유지했다. 임베딩 한 배치/jobs/index·소유 문서 조회·리스/token/revision 및 짧은 저장과 무트랜잭션 전송을 분리했다. RAG 도입/전체 적재/운영 endpoint 전환은 하지 않는다. | 분석/OCR/Excel provider·codec·입력/출력 안전 테스트, `StoreLedgerImageInputServiceTest`, `SynchronizeLedgerEmbeddingsServiceTest`, `LedgerEmbeddingOutboxIntegrationTest` |
| 6. 스키마 정책/테스트 공백 조사, DB·동시성·의존 규칙 보강 | 9개 레거시 조건을 공통화하고 14개 Java DDL 소스의 정확한 목록·동결 범위·스위치 밖 5개를 감사했다. SQL·Flyway 체크섬·운영 기본값은 바꾸지 않았다. H2의 소유 범위·실제 롤백/행 잠금/version/CAS/리스/캐시 fencing/커밋 후 파일 정리와 CI 명시 회귀 선택을 추가했다. MariaDB/Flyway-only 성공으로 과장하지 않는다. | `LegacySchemaCompatibilityTest`, `StartupSchemaGovernanceTest`, 기능별 H2 통합·동시성 회귀, 마이그레이션/보안/E2E 문서 gate |

## 마지막 의존·동작 감사

- 전환 코어는 같은 기능의 순수 모델·정책·포트만 사용한다. 기존 enum과 프레임워크 독립 업무 예외, 두 binary 계약의 제한적 JDK I/O 선언만 명시적으로 허용하며 임의 I/O는 허용하지 않는다.
- 실제 코드 검색에서 남은 `TravelService` 운영 참조는 기존 공유 서비스의 승인/열람 bridge와 기존 HTTP의 `MediaDownload` 타입 호환이다. 새 여행 유스케이스/JPA/캐시/클러스터/경로 코어로 되돌아가는 대형 서비스 의존은 compiled guard가 금지한다. 이 호환 bridge를 제거하려면 별도 공유 승인/응답 계약 작업으로 다룬다.
- 소유 지도/계획 조회는 같은 `TravelSummaryCache`/SingleFlight와 generation fencing을 재사용한다. 공유/공개 읽기는 owner metadata cache로 권한을 우회하지 않는다. 원래의 무효화 시점·TTL·직렬화 shape를 보존한다.
- 새 분석 worker는 status 조회→입력 직렬화/조건부 저장→timer→request 변환/추론→lease 확인→응답 직렬화→종료 CAS 순서를 유지한다. 최초 조회 오류, null timer, 실패 metric/로그 순서와 기존 예외 동작을 차등 검증한다. 직접 입력 어댑터 호출도 트랜잭션을 중단한다.
- 임베딩 Outbox는 전송 중 원본 변경·토큰 교체·만료 리스·다른 owner·동시 claim·실패/재시도·바깥 rollback·직접 HTTP 진입을 H2/모의 네트워크로 검증했다. 문서 생성의 기존 읽기 서비스와 capture는 이 경계 밖의 호환 구현으로 유지한다.
- 파일 삭제는 성공한 DB 커밋 후 기존 보상 경로를 사용한다. DB와 MinIO의 원자적 커밋이나 durable cleanup outbox를 추가한 것은 아니다.

## 명시적으로 남긴 기존 경계와 후속 과제

다음은 위 여섯 목표를 달성하기 위해 무조건 모두 옮겨야 하는 범위가 아니라, 별도 기능/운영 변경 시 검토할 기존 경계다. 현재 동작과 오류 계약을 보존하기 위해 이번에 임의로 변경하지 않았다.

- 가계부 조회/이력·공유 승인/수락, OCR의 제안 금액/제목/분류/사용자 확인 정책은 기존 서비스에 남는다. 일반 분석의 동기 추론·기존 큐 수명 관리 및 OCR/Excel 접수도 점진적 후속 분리가 가능하다. 코어 검사가 미전환 코드까지 순수하다고 주장하지 않는다.
- 소유 미디어 metadata cache의 상위 삭제/in-flight 재발행 경합, generic 썸네일의 기존 원본 fallback/client cache, 공개 읽기의 기존 in-flight 정책은 이번에 변경하지 않았다. 기존 보안/권한 회귀와 동일 동작 보존은 이 정책 개선의 완료를 뜻하지 않는다.
- 임베딩 문서 생성/capture의 추가 순수 정책 분리와 강한 파일 정리 보장(outbox 등)은 후속 설계다. 네트워크 전송·소유 scope·revision/token 저장 경계는 이미 분리·검증했다.
- 실제 MariaDB 특화 SQL/격리 수준/실행계획, V000부터 전체 Flyway bootstrap·복제 DB upgrade/복원, 최소 권한 기동 전환은 **별도 승인된 격리 환경**이 필요하다. 이번 목표는 조사·공백 문서화이며 운영 스키마 전환을 포함하지 않는다.
- 실제 브라우저/모바일·운영 Redis/MinIO/외부 AI·스테이징 배포 smoke는 실행하지 않았다. H2/모의 연동 테스트나 WAR 빌드를 해당 검증의 대체 증거로 사용하지 않는다.

## 최종 실행 증거

- 격리 H2/모의 외부 연동의 전체 `gradlew test bootWar`: **213개 스위트, 1417건, 실패 0 / 오류 0 / 건너뜀 0**, `BUILD SUCCESSFUL` (10분 53초). 프로세스 종료 후 XML 집계: **2026-10-17 05:14:01 KST**. 진행 중 결과만 보고 성공을 판정하거나 대기 중 테스트를 중복 실행하지 않았다.
- 마지막 실행 경계 9개가 WAR에 포함됐고 test oracle 및 제거한 `LedgerAiAnalysisService$DateRange/$AnalysisPlan/$AnalysisDataset`는 없다. CI 명시 선택 **118개**가 모두 컴파일돼 있다. 기존 단일 WAR이며 새 라이브러리/마이그레이션 SQL 변경은 없다.
- 시크릿·AI 제공자 안전·코치·서비스 분해·E2E 체크리스트·마이그레이션·공유 권한·업로드 보안 **정적 gate 8종** 통과. 마지막 단계 관련 **28개 파일**의 strict UTF-8/BOM·후행 공백·diff 검사를 통과했다. gate의 oracle 독립성·NOT_SUPPORTED·지연 변환·CI 선택 조건을 메모리에서 각각 훼손하면 실패한다.
- 마지막 oracle의 13개 메서드/세 record는 변경 전 snapshot과 공개 진입 visibility를 제외하고 동일하다. 기존 Repository SQL·제공자 프롬프트·종료 CAS·큐 cleanup·프런트 기능을 마지막 단계에서 변경하지 않았다.
- HEAD는 `4aeb6d7d` 그대로다. 운영 DB/저장소 변경·배포·커밋·푸시는 수행하지 않았다. 미전환 기존 경계와 별도 운영/기능 과제는 위 목록에 남기며 이번 검증의 완료와 구분한다.

## 후속 커밋 전 검사 — 2026-10-18

- 사용자 커밋·푸시 요청에 따라 소스·회귀 테스트·CI 계약·문서를 커밋 대상으로 점검했다. 인증서·VM 자격증명·로그/화면/빌드 산출물·포트폴리오 및 별도 임베딩 인프라 초안은 제외한다. VM/운영 재배포는 요청받지 않아 수행하지 않는다.
- 시크릿 검사와 CI 정적/계약 검사 **25종 통과**, Git 추적 소스의 UTF-8 BOM 검사 및 staged diff 검사를 통과했다. 기존 전체 백엔드 결과는 **213 suites / 1417 tests, 실패·오류·skip 0**이며, 검증 WAR보다 나중에 수정된 백엔드 소스/테스트 파일은 없다.
- 앱 동작 변경 없이 추가 CI 오검출을 수정했다. 백업 문서의 Markdown checksum 표기, Alertmanager의 실제 환경변수 포트 매핑, LF/CRLF 경보 이름 파싱, PowerShell 자동 `$Matches`와 반복 변수의 충돌, 분리된 여행 컨트롤러의 경로·현재 사용자 ID 전달 검사를 정합화했다.
- Prometheus 규칙은 LF/CRLF 양쪽에서 동일한 **25개** 경보를 읽는다. 필수 경보 제거·여행 조회의 viewer scope 훼손·백업 checksum 표기 제거를 메모리에서 각각 주입한 세 음성 대조군은 모두 거절됐다. 실제 앱/설정 파일을 훼손하거나 안전 검사를 생략하는 방식은 사용하지 않았다.
