# 백엔드 스키마 관리 감사

기준: 2026-10-16 작업 트리의 소스. 운영 DB, 스테이징, Flyway 이력 또는 배포 로그를 조회한 보고서가 아니다.

## 결론과 변경 범위

현재 스키마 관리는 Flyway 단독이 아니다. Hibernate 자동 변경, 레거시 보정 9개, 그 밖의 기동 DDL 5곳이 공존한다. **레거시 스위치 OFF는 무-DDL 기동의 증거가 아니다.** 이번 작업은 실행 조건의 중복 제거, 기존 Java DDL 범위 동결, 테스트 공백 명시까지만 수행한다. 기존 SQL, DB 테이블, 마이그레이션 이력, 운영 기본값은 변경하지 않는다.

`LegacySchemaCompatibility`에 기존 `@ConditionalOnProperty`를 모았다. `app.schema.legacy-updaters.enabled`의 미설정/true 활성, false 및 true 이외 값 비활성 의미를 보존한다. 새 DDL을 추가하거나 보정 클래스를 삭제할 허가를 뜻하지 않는다.

## 현재 기본 설정

근거: `backend/src/main/resources/application.yml` 및 `backend/build.gradle`.

| 경로 | 현재 값 | 의미/한계 |
|---|---|---|
| `spring.jpa.hibernate.ddl-auto` | `update` | 엔티티 기준 변경이 가능하며 JDBC 전용 테이블의 준비를 보장하지 않는다. |
| `spring.flyway.enabled` | `${DB_MIGRATION_ENABLED:false}` | 라이브러리 의존성이 있어도 기본 실행은 꺼져 있다. |
| `spring.flyway.baseline-on-migrate` | `${DB_MIGRATION_BASELINE_ON_MIGRATE:true}` | 비어 있지 않은 기존 스키마 도입 정책이며 대상/기존 이력을 별도로 검증해야 한다. |
| `spring.flyway.baseline-version` | `${DB_MIGRATION_BASELINE_VERSION:0}` | baseline marker는 전체 기본 테이블 생성 스크립트가 아니다. |
| `spring.flyway.validate-on-migrate` | `${DB_MIGRATION_VALIDATE_ON_MIGRATE:true}` | 활성 시 이력/체크섬 검사이며 업무 smoke/복원 검증을 대체하지 않는다. |
| `app.schema.legacy-updaters.enabled` | `${APP_SCHEMA_LEGACY_UPDATERS_ENABLED:true}` | 아래 9개 보정 bean만 제어한다. Flyway 설정과 독립이다. |

환경변수·프로필 등 실제 배포의 override는 확인하지 않았다. `.env`나 시크릿 값은 수집하지 않는다. 이번 작업에서 위 기본값이나 배포 설정을 바꾸지 않는다.

## Java DDL 소스 목록: 9 + 5

소스 검색과 컴파일된 문자열 검사로 확인한 14곳이다. 아래 목록은 현존 부채의 동결 목록이며 새 예외를 허용하는 목록이 아니다. 런타임 조합 SQL·외부 SQL의 실행 도달성을 완전히 증명하는 정적 분석은 아니다.

### 레거시 스위치로 제어하는 9곳

모두 `ApplicationRunner`이며 `@LegacySchemaCompatibility`를 사용한다. 기존 MariaDB/MySQL 조건·메타데이터 검사·SQL·예외 처리 본문은 그대로다. 일부는 제품명 검사보다 테이블 존재 검사를 먼저 수행한다.

| 소스 | 주요 책임 | 이전/폐기 근거 |
|---|---|---|
| `backend/src/main/java/com/playdata/calen/ledger/config/LedgerAiAnalysisSchemaUpdater.java` | AI 이력 | V004/V013 등 중복 범위, 실제 기동/저장/조회 검증 대기 |
| `backend/src/main/java/com/playdata/calen/ledger/config/LedgerEntrySchemaUpdater.java` | 거래 열·인덱스 | V012 중복 범위, 실제 가계부/가져오기 검증 대기 |
| `backend/src/main/java/com/playdata/calen/ledger/config/LedgerEntryChangeHistorySchemaUpdater.java` | 거래 변경 이력 | V009 중복 범위, 실제 이력/복원 검증 대기 |
| `backend/src/main/java/com/playdata/calen/travel/config/TravelMediaAssetSchemaUpdater.java` | 여행 미디어 GPS·대표 정보 | V010 중복 범위, 실제 업로드/지도 검증 대기 |
| `backend/src/main/java/com/playdata/calen/travel/config/TravelPhotoClusterSchemaUpdater.java` | 사진 클러스터 | V011 중복 범위, 실제 클러스터 검증 대기 |
| `backend/src/main/java/com/playdata/calen/travel/config/TravelRouteSchemaUpdater.java` | 경로·GPX | V008 중복 범위, 실제 경로 검증 대기 |
| `backend/src/main/java/com/playdata/calen/account/config/HouseholdGoalSchemaUpdater.java` | 가계 목표 | V014 중복 범위, 실제 목표 CRUD 검증 대기 |
| `backend/src/main/java/com/playdata/calen/ledger/config/LedgerImageAnalysisRequestSchemaUpdater.java` | 이미지 요청 기본 테이블·복구/버전 열 | V024/V027은 후속 필드이며 기본 테이블 bootstrap 공백 |
| `backend/src/main/java/com/playdata/calen/travel/config/TravelMapShareLinkSchemaUpdater.java` | 여행 지도 링크 | 대응 Flyway 테이블 마이그레이션 공백 |

9개 모두 기존 [폐기 증거 장부](db_migration_retirement_evidence.md)의 Pending 상태를 유지한다. 공통 어노테이션 적용은 SQL 이전 또는 폐기 완료가 아니다.

### 해당 스위치로 제어되지 않는 기존 5곳

| 소스 | 실행 진입·DDL | 조용히 비활성화하지 않는 이유 |
|---|---|---|
| `backend/src/main/java/com/playdata/calen/account/security/SecurityConfig.java` | `@Bean persistentTokenRepository` → `persistent_logins` 생성 | 로그인 유지 토큰 저장에 사용한다. |
| `backend/src/main/java/com/playdata/calen/account/service/AdminOpsControlService.java` | `@PostConstruct` → `admin_ops_control_settings` 생성 후 설정 읽기 | 운영 설정 읽기/저장 및 장애 알림 동작과 결합돼 있다. |
| `backend/src/main/java/com/playdata/calen/account/service/RegistrationPolicyService.java` | `@PostConstruct` → `registration_policy_settings` 생성 후 읽기 | 회원가입 허용 정책의 기본 거부 동작을 보존해야 한다. |
| `backend/src/main/java/com/playdata/calen/travel/service/TravelLoginMapPreviewService.java` | `@PostConstruct` → `travel_login_map_preview_settings` 생성 후 읽기/초기화 | 로그인 지도 공개 설정과 실패 시 비공개 동작을 보존해야 한다. |
| `backend/src/main/java/com/playdata/calen/travel/service/TravelDriveLinkBackfillService.java` | `ApplicationReadyEvent` → `app_one_time_jobs` 생성 뒤 1회 작업 | V016과 겹치며 이어지는 백필 DML/완료 기록은 DDL과 별도 책임이다. |

첫 네 테이블은 현재 마이그레이션 디렉터리에서 대응 CREATE를 찾지 못했다. 마지막은 V016이 있지만 실제 기존 DB와 기동/백필을 대조한 폐기 증거는 없다. 다섯 소스의 SQL·설정/보안 동작은 이번에 변경하지 않았다.

## 마이그레이션 범위와 기동 순서의 공백

- 현재 V000~V033, 34개 버전 파일을 보존한다. V000은 marker이며 전체 스키마 bootstrap이 아니다.
- 예: V006은 기존 `drive_shares`에 ALTER한다. V024는 기존 `ledger_image_analysis_requests`에 ALTER/UPDATE JOIN을 수행한다. 뒤늦은 Hibernate `update`나 ApplicationRunner가 앞선 Flyway 실패를 복구해 줄 것이라고 가정해서는 안 된다. **소스 기준 도출한 신규 DB 기동 위험**이며 실제 MariaDB에서 실패를 재현한 보고가 아니다.
- 스키마가 이미 있는 DB의 baseline 도입과 빈 DB의 bootstrap은 다른 검증이다. baseline 버전/이력 존재 여부/미적용 ALTER의 전제 테이블을 각각 대조해야 한다. baseline 성공을 전체 기본 테이블 준비 완료로 간주하지 않는다.
- `ddl-auto=validate`도 위 5개 Java 기동 DDL을 차단하지 않는다. 무-DDL 기동을 목표로 한다면 별도 설계·마이그레이션·격리 환경 권한 검증까지 필요하다.
- 이번에는 새 SQL을 만들거나 기존 SQL/체크섬을 수정하지 않았다. 기존 스키마를 재구성하거나 백필을 실행하지도 않았다.

## 자동 검증과 그 한계

| 검사 | 이번에 확인하는 것 | 확인하지 못하는 것 |
|---|---|---|
| `LegacySchemaCompatibilityTest` | 9개 bean의 미설정/true/비true 및 Flyway flag 조합, 병합 조건 메타데이터, 모의 H2 제품명에서 DDL 미호출 | Flyway 실제 실행, 전체 Spring 운영 기동, MariaDB SQL 문법 |
| `StartupSchemaGovernanceTest` | main class의 DDL 문자열 소스 14곳 정확한 목록, runner 이름 없는 helper/문자열 결합/UNIQUE INDEX 탐지 반례 | 모든 동적 SQL의 생성/도달성, 외부 파일의 DDL |
| `scripts/verify-db-migrations.ps1` | 기존 파일명/버전/문서/폐기 장부 검사 유지, 공통 조건 확인, Java 전 소스 DDL 동결 | DB 접속/체크섬 실이력/복원·배포 성공. 주석까지 매칭할 수 있는 보수적 소스 검사다. |
| `RecordMapMigrationTest` | H2에 `app_users` 전제를 만든 뒤 V033의 소유 FK/유일성/인덱스 및 반복 적용 | V000부터 전체 체인, MariaDB 전용 문법·잠금 |
| `RecordShareMemoMigrationTest` | H2에 기존 `record_shares` 전제를 만든 뒤 V031의 기존 행 보존/선택 메모 | 신규 DB 전체 bootstrap, MariaDB 이력 |
| H2 JPA/트랜잭션·동시성 회귀 | 각 리팩토링 단위의 롤백/owner/version/캐시·파일 callback 계약 | 실제 MariaDB 격리·DDL 암묵 커밋·실행계획·성능, Redis/MinIO/AI 운영 통합 |

신규 스키마 조건/범위 회귀 16건은 모의 JDBC와 클래스 검사만 사용한다. H2 통합 테스트의 create-drop 및 Flyway 비활성 설정은 의도적인 격리 수단이며 Flyway-only 운영 검증 증거가 아니다. 전체 회귀의 최신 건수/결과는 [진행 기록](backend_refactoring_progress.md)에 별도 기록한다.

## 후속 운영 검증 게이트 — 이번에는 실행하지 않음

1. 별도 승인된 격리 MariaDB에서 실제 버전·문자셋·격리 수준, 기존 테이블/인덱스·열과 Flyway 이력을 메타데이터로만 비교한다. 계정·토큰·공유 링크·프롬프트·설정 값은 증거에 남기지 않는다.
2. 빈 DB bootstrap과 비어 있지 않은 복제 스키마의 baseline/upgrade를 나눠 테스트한다. 백업/복원 가능성, 신규/기존 데이터 보존을 함께 확인한다. 운영 DB를 테스트 대상으로 사용하지 않는다.
3. Hibernate update와 Java DDL의 도움을 받는지 구분한다. 9개 스위치 OFF만으로 무-DDL 또는 Flyway 단독 성공이라고 적지 않는다. 나머지 5개를 이전할 때는 로그인 유지·가입 정책·공개 미리보기·설정 영속·1회 백필을 개별 회귀한다.
4. 대응 마이그레이션과 staging 기동/Flyway 이력/업무 smoke/복원 증거가 모인 한 항목만 기존 폐기 장부에 기록한다. 증거 없는 Ready 전환, 보정 클래스 일괄 삭제 또는 운영 기본값 변경을 하지 않는다.
5. 이후 최소 권한 기동/DDL validate 전환은 별도 변경으로 승인·검증한다. 이번 목표의 코드 회귀와 실제 운영 스키마 전환 완료를 구분한다.
