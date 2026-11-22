# 성능 개선 2차(백엔드 DB·Redis/캐시·프론트) 작업 지시서

작성: 2026-11-22 · 기준 커밋 `87d2eecc`(main) · 대상: Spring Boot 백엔드(`backend/`, Java 17) + Vue 3/Vite 프론트(`frontend/`)

> **먼저 읽을 것.** 1차 성능 작업(`docs/performance_improvement_progress.md`, 2026-10-03)에서 AI 큐, 드라이브 조회, 사진 클러스터 계산, 지도 조회, 역지오코딩 워커, 대시보드 집계, 캐시 single-flight와 커밋 후 무효화, 프론트 동시 GET 병합은 이미 처리했다. 여행 가져오기 대화상자의 사진 그룹 동시 업로드도 `87d2eecc`에서 끝났다. 이 문서는 그 뒤에 **남은 문제만** 다룬다. 이미 처리한 항목은 다시 제안하지 않는다(8.3절).
>
> 근거 표기:
> - **[측정]**: 이번 분석에서 실제로 실행해 얻은 숫자. 방법은 1.1절에 적었다.
> - **[코드]**: 코드를 읽어 확정한 사실. 실행하지는 않았다.
> - **[추정]**: 코드 구조에서 계산한 예상치. 운영 수치는 아니다.

---

## 0. 꼭 지킬 규칙 (구현자용)

1. **동작을 바꾸지 않는다.** API 응답의 모양(필드 이름·타입·순서·의미)도 바꾸지 않는다. 예외는 각 작업 지시에 "동작 변화"로 적은 부분뿐이다. 거기에 적힌 것 외에는 아무것도 바꾸지 않는다.
2. **보안·소유권 검사를 그대로 둔다.** `requireActiveOwner`, `owner.id = :owner` 조건, 공유 권한 재확인(`access(...)`, `selectedShare(...)`), `ensureAdmin` 같은 호출을 빼거나 순서를 바꾸지 않는다. 캐시나 병렬화를 추가하더라도 권한 검사보다 앞에서 데이터를 돌려주면 안 된다.
3. **운영 DB에는 아무것도 하지 않는다.** 운영 DB 접속, `ALTER`/`CREATE INDEX` 실행, Flyway 실행 모두 금지다. 운영 인덱스 확인(`SHOW INDEX`, `EXPLAIN`)은 사용자가 직접 한다(9장).
4. **인덱스는 두 곳에 함께 넣는다.**
   - 운영은 `DB_MIGRATION_ENABLED=false`(Flyway 꺼짐)와 `ddl-auto: update` 조합이다(`application.yml` 32·39행, `docker-compose.oci.app.yml` 56행).
   - 그래서 엔티티의 `@Table(indexes = @Index(...))`가 있어야 Hibernate가 운영에서 인덱스를 만든다.
   - 같은 이름·같은 컬럼 순서로 번호 붙은 Flyway 마이그레이션도 함께 만든다.
5. **마이그레이션은 여러 번 실행해도 안전해야 한다.**
   - 운영 DB는 MariaDB 11.4다(`docker-compose.oci.data.yml` 5행, JDBC `org.mariadb.jdbc.Driver`). `CREATE INDEX IF NOT EXISTS`를 쓴다. 테스트용 H2(MySQL 모드)도 이 문법을 지원한다.
   - 기존 예: `V20261105_056__withdrawal_recovery_request_limits.sql` 9행.
   - `ALTER TABLE ... ADD INDEX`처럼 다시 실행하면 실패하는 문법은 쓰지 않는다.
6. **Java 코드에 DDL 문자열을 넣지 않는다.** `StartupSchemaGovernanceTest`가 `CREATE INDEX`·`ALTER TABLE` 같은 문자열 상수를 가진 새 클래스를 찾아내 실패시킨다. 테스트 코드(`src/test`)는 이 검사 대상이 아니다.
7. **새 설정 키(`application.yml`, `@Value("${...}")`)를 추가하지 않는다.**
   - `application.yml`과 `.env.example`·`.env.oci.app.example`·docker-compose에는 사용자의 미커밋 변경이 있다.
   - 환경 변수 동기화 계약(`scripts/verify-env-sync.ps1`)도 있다.
   - 필요한 값은 클래스 안 상수로 둔다.
8. **수정 금지 파일**(사용자의 무관한 미커밋 변경이 있음):
   - 설정·배포: `backend/src/main/resources/application.yml`, `.env.example`, `.env.oci.app.example`, `docker-compose.yml`, `docker-compose.oci.app.yml`
   - 백엔드 AI·탈퇴 관련: `backend/.../ledger/adapter/out/ai/ImageLedgerExtractionHttpAdapter.java`와 그 테스트, `backend/.../ledger/ai/LedgerAiAnalysisProperties.java`, `backend/.../account/web/IntegrationWithdrawalMonitorController.java`
   - 프론트: `frontend/src/components/AdminWithdrawalMonitor.vue`, `frontend/src/style.css`, `frontend/e2e/kakao-registration.spec.js`
   - 문서: `docs/account_withdrawal_workplan.md`, `docs/record_map_place_trip_refresh.md`
   - `frontend/src/lib/api.js`에도 무관한 미커밋 변경(`fetchAdminWithdrawalEmbeddingStatus`)이 있다. 이번 작업은 `api.js`를 고치지 않는다.
9. **스테이징(`git add`)도 커밋도 하지 않는다.** 작업 트리에 변경만 남긴다. 확인용 임시 파일, 임시 테스트, 임시 설정은 끝나면 지운다.
10. **백엔드 테스트는 패키지(또는 클래스) 단위로 하나씩 돌린다.** 한 번에 여러 패키지를 돌리면 logback 교착이나 OOM이 난 적이 있다.
11. **문서·테스트에 실제 사용자 데이터나 로컬 사용자 경로를 쓰지 않는다.**

공통 검증 명령:
- 백엔드(Git Bash): `cd backend && JAVA_HOME=/c/jdk-17 ./gradlew test --tests '<패턴>'`
  - PowerShell이면: `$env:JAVA_HOME='C:\jdk-17'; .\gradlew.bat test --tests '<패턴>'`
- 프론트: `cd frontend && npm run build`, `cd frontend && npm run test:unit`
- e2e(목 API를 쓰는 기존 Playwright 스펙):
  1. 백그라운드에서 `cd frontend && npx vite --host 127.0.0.1 --port 5189 --strictPort`를 실행한다.
  2. `cd frontend && E2E_BASE_URL=http://127.0.0.1:5189 E2E_START_LOCAL_SERVER=0 npx playwright test <spec> --config playwright.config.js --reporter=line`을 실행한다.
  3. 끝나면 vite를 종료한다.
- 마이그레이션 규칙: `powershell -ExecutionPolicy Bypass -File scripts/verify-db-migrations.ps1`
  - 이 스크립트는 `git ls-files`로 추적된 마이그레이션만 검사한다. 새 파일은 커밋 전에는 검사되지 않으므로 파일 이름 규칙(`VYYYYMMDD_NNN__소문자_설명.sql`)과 7.1절의 문서 행 추가를 직접 확인한다.

---

## 1. 현황 측정과 근거

### 1.1 이번에 실제로 측정한 것

| # | 무엇 | 방법 | 결과 |
|---|---|---|---|
| M1 | 프론트 빌드 청크 크기 | 현재 작업 트리에서 `npm run build`(vite 7). 수치는 vite가 출력한 값 | 엔트리 CSS `index-*.css` **1,019.15 kB**(gzip 132.35) · `HouseholdWorkspace` **477.82 kB**(gzip 136.56) · `leaflet` 149.57 kB(gzip 43.30) + CSS 15.61 kB · `AdminWorkspace` 122.60 · 엔트리 JS `index-*.js` 121.20(gzip 45.38) · `CalenDriveWorkspace` 91.26 · `IntegratedCalendarWorkspace` 86.39 · `StatisticsWorkspace` 86.05 · gridstack `types-*` 84.37 · exifr 전체 빌드를 품은 `mediaPreview-*` **80.11 kB**(gzip 28.32) · `MainDashboardWorkspace` 51.81 · `TravelMiniLocationMap` 1.68 |
| M2 | 첫 화면(런처 → `MainDashboardWorkspace`)이 정적으로 끌어오는 청크 | 빌드 결과 `MainDashboardWorkspace-*.js`의 `from"./…"` 목록 | `TravelMiniLocationMap`(→ leaflet JS·CSS), `mediaPreview`(exifr 80 kB), `types`(gridstack), `api`, `uiFormat`, `index` |
| M3 | F-1 실험: 가계부 탭 전용 컴포넌트를 비동기로 바꾼 빌드 | 스크래치 폴더에 소스를 복사해 `CalendarWorkspace`·`RecurringLedgerWorkspace`·조정 대화상자 2개만 `defineAsyncComponent`로 바꾸고 빌드. 저장소는 바꾸지 않음 | `HouseholdWorkspace` **477.82 → 182.99 kB**(gzip 136.56 → **54.63**). `CalendarWorkspace`는 별도 청크 260.54 kB(gzip 74.01)로 분리. `PaletteContainer`까지 비동기로 하면 149.71 kB까지 줄지만 기본 탭(대시보드)이 바로 쓰므로 권장하지 않음 |
| M4 | F-2 실험: 첫 화면에서 leaflet·exifr 제거 | 같은 방식으로 `MainDashboardWorkspace`의 `TravelMiniLocationMap`을 비동기로, `mediaPreview.js`의 exifr를 동적 import로 바꾸고 빌드 | `MainDashboardWorkspace`의 정적 의존에서 leaflet·`TravelMiniLocationMap`이 빠지고 `import()`로 바뀜. `mediaPreview`는 80.11 → **6.07 kB**. exifr는 `full.esm-*` 75.29 kB 별도 청크로 분리. 첫 화면 전송량 감소 ≈ 149.57 + 15.61 + 1.68 + 74.04 ≈ **241 kB**(gzip 약 **76 kB**) |
| M5 | Hibernate가 엔티티만으로 만드는 인덱스 목록(= Flyway 없이 `ddl-auto`로 만든 운영 스키마와 같은 조건) | 임시 `@DataJpaTest`(H2, `ddl-auto=create-drop`, legacy updater 끔)로 `information_schema.index_columns`를 덤프하고, 마이그레이션 SQL에 선언된 인덱스 이름과 비교. 끝난 뒤 임시 테스트 삭제 | `record_map_visits`: **PK와 `place_id` FK 인덱스뿐**. `owner_id`·`visit_date` 인덱스 없음 · `record_map_photos`: PK와 `visit_id` FK뿐 · `record_map_places`: PK와 유니크 `(owner_id, source_travel_record_id)`뿐. `updated_at` 정렬 인덱스 없음 · `record_map_collections`: **PK뿐** · `record_map_shares`: PK, `token_hash` 유니크, `collection_id` FK뿐 · `ledger_entries`: `idx_ledger_adjustment_origin` 없음. 마이그레이션에만 있고 엔티티에는 없는 일반 인덱스는 1.3절 표 참조 |
| M6 | 드라이브 관리자 통계의 쿼리 수 | 같은 임시 테스트에서 Hibernate statistics(`generate_statistics=true`, 테스트 속성으로만 지정)로 `DriveAdminService.getStorageAnalytics`의 접근 패턴(`findAll` 1회 + 사용자 목록 + 사용자별 `findAllByOwner_Id`)을 재현. 사용자 20명 × 파일 5개 | **prepared statement 22개**(사용자 수 + 2). 수정 후 기대값은 2개 |
| M7 | 프론트 요청 병합기의 `structuredClone` 비용 | Node(V8)에서 5,000개 사진 DTO(JSON 1,428,347바이트)를 20회 반복 측정 | `JSON.parse` **2.72 ms/회**, `structuredClone` **6.05 ms/회**. 응답을 한 번 더 복제하는 비용이 파싱보다 크다 |
| M8 | 대시보드 포토 프레임 정렬 비용 | Node에서 5,000개 항목 정렬을 20회 반복 측정 | `updatedAt` `localeCompare` 정렬 **4.83 ms/회**, 제목 `localeCompare('ko-KR')` 정렬 **6.13 ms/회** |

M3·M4는 저장소 밖 스크래치 복사본에서만 실험했다. 저장소의 `frontend/dist`(git 무시 대상)는 M1 빌드로 다시 생성되었을 뿐 소스 변경은 없다.

### 1.2 백엔드 DB

**D1. 기록 지도 테이블의 인덱스가 운영 스키마에 없다 [측정 M5 + 코드]**
- 인덱스는 마이그레이션 `V20261013_033__record_map.sql`에서 `CREATE TABLE` 안의 인라인 `INDEX`로만 선언됐다. 대상은 `idx_record_map_collection_owner`, `idx_record_map_place_owner_updated`, `idx_record_map_visit_owner_place_date`, `idx_record_map_photo_owner_visit`, `idx_record_map_share_owner_collection`이다.
- 엔티티에는 `@Index`가 없다. 해당 줄: `PlaceVisit.java` 11행, `PlacePhoto.java` 9행, `RecordPlace.java` 13행, `PlaceCollection.java` 9행, `RecordMapShare.java` 9행.
- 운영은 Flyway가 꺼져 있으므로, 테이블을 Hibernate가 만들었다면 이 인덱스는 운영에 없다. 게다가 나중에 Flyway를 켜더라도 `CREATE TABLE IF NOT EXISTS`는 테이블이 이미 있으면 통째로 건너뛰므로 인라인 인덱스는 여전히 만들어지지 않는다.
- 이 인덱스가 필요한 쿼리(모두 매 요청 실행되는 경로):
  - 통합 달력 월 요약 `CalendarSummaryService.places`(61~67행): `v.ownerId = :owner and v.visitDate between :from and :to group by v.visitDate`. `record_map_visits`에 `owner_id` 인덱스가 아예 없어 **전체 스캔**이 된다 [코드]. 기존 마이그레이션 인덱스 `(owner_id, place_id, visit_date, id)`도 가운데 `place_id` 때문에 날짜 범위 조회에는 맞지 않는다.
  - 통합 달력 일 상세 `CalendarDayService.places`(66~70행): `v.ownerId = :owner and v.visitDate = :date order by v.visitTime, v.id`.
  - 기록 지도 목록 `RecordPlaceRepository.findVisible` + `JpaRecordMapQueryAdapter.places`(40~42행): `p.ownerId = :owner order by updatedAt desc, id desc` 페이지 조회. `owner_id`는 유니크 인덱스 앞부분으로 찾을 수 있지만 정렬은 filesort다.
  - 기록 지도 개요에서 매번 실행되는 `RecordMapShareRepository.findActiveCollectionIds`(13~14행, `s.ownerId = :owner`)와 `PlaceCollectionRepository.findAllByOwnerIdOrderByCreatedAtDescIdDesc`: `owner_id` 인덱스가 없다.
  - `PlaceVisitRepository.countVisits`·`findVisibleVisits`, `PlacePhotoRepository.findAllByOwnerIdAndVisitIdInOrderByIdAsc`: FK 인덱스(`place_id`, `visit_id`)로는 동작하므로 영향이 작다.
- 운영 테이블 크기와 실행 계획은 확인하지 못했다(9장). 행 수가 적은 사용자에게는 체감이 작고, 방문 기록이 쌓일수록 비용이 행 수에 비례해 커진다 [추정].

**D2. `ledger_entries`의 조정·여행 연결 조회 인덱스 [측정 M5 + 코드]**
- `idx_ledger_adjustment_origin (owner_id, adjustment_origin_id)`는 마이그레이션 `V20261030_033_1__ledger_payment_adjustments.sql` 13행에만 있다. 엔티티 `LedgerEntry.java` 26~33행에도, legacy updater `LedgerEntrySchemaUpdater.java` 44~67행에도 없다.
- 이 인덱스를 쓰는 조회: `LedgerEntryRepository`의 `existsByOwnerIdAndAdjustmentOriginId`(46행), `findAllByOwnerIdAndAdjustmentOriginIdAndDeletedAtIsNull...`(50행), `...AndDeletedAt`(82행). 거래 관리·조정 이력에서 쓴다.
- `travel_record_id`에는 인덱스가 전혀 없다. 이 컬럼을 쓰는 조회:
  - 통합 달력 일 상세 여행 기록 `CalendarDayService.travelRecords`(109행): `e.owner.id = :owner and e.deletedAt is null and e.travelRecordId in :ids`
  - `findByOwnerIdAndTravelRecordIdAndDeletedAtIsNull`(54행)
  - 두 조회 모두 소유자 인덱스로 그 사용자의 미삭제 가계부 전체를 훑는다 [코드].

**D3. 드라이브 관리자 통계의 N+1 [측정 M6]**
- `DriveAdminService.getStorageAnalytics`(98~105행)는 `driveItemRepository.findAll()`로 전체 항목을 이미 읽는다. 그런데도 사용자마다 `findAllByOwner_Id(user.getId())`를 다시 호출한다(103행). 그래서 쿼리 수는 사용자 수 + 2개다.
- 같은 클래스의 `getDashboard`(74~78행)는 이미 `groupingBy`로 한 번에 처리한다.
- 같은 메서드 114~119행은 파일마다 MinIO `objectExists`(HEAD)를 읽기 트랜잭션 안에서 호출한다. 이 부분은 이번 범위가 아니다(8.2절).
- `updateProviderCapacity`(174행 이후)도 같은 통계를 반환하므로 함께 좋아진다.

**D4. 기록 지도·달력 공유 사진 썸네일이 요청한 크기를 무시한다 [코드]**
- 프론트 `buildThumbnailUrl`(`frontend/src/lib/mediaPreview.js` 229~250행)은 폭을 `w`로 보낸다.
- 여행 컨트롤러는 `@RequestParam(name = "w")`로 받는다(`OwnedTravelMediaController.java` 58행, `TravelExhibitController.java` 29·34행).
- 그런데 아래 두 컨트롤러는 파라미터 이름이 `width`라서 `w`가 무시된다. 결과적으로 `PreparedThumbnailProfile.selectWidth(null)`(33~34행)이 고르는 기본 폭 **480 px**로 응답한다.
  - `RecordMapController`의 `/photos/{id}/content`(90~93행), `/public/{token}/photos/{id}/content`(122~125행), `/public/{token}/travel-photos/{id}/content`(127~130행)
  - `CalendarMapShareController`의 `/{id}/photos/{kind}/{photoId}`(52~56행)
- 기록 지도 목록과 상세(`RecordMapWorkspace.vue` 68·657행)는 `mini`(240 px)를 요청하므로 화소 수 기준 약 4배 큰 이미지를 받는다 [추정].
- 이 응답은 `Cache-Control: no-store`(`RecordMapController` 135행)라서 화면을 다시 열 때마다 다시 받는다.
- 이 불일치는 `docs/photo_orientation_fix_workplan.md` 2.4절 끝에 "비범위"로 적혀 있던 문제다.

**D5. 읽기만 하는 공유 달력 조회가 쓰기 트랜잭션으로 열린다 [코드]**
- `CalendarSharedService.summary`(82~83행)와 `day`(96~97행)는 `@Transactional`(readOnly 아님)이다. 하지만 하는 일은 조회뿐이다.
  - 호출 대상 `UserLayoutSettingService.getLayoutSetting`(31~37행)은 읽기만 한다.
  - `RecordSharingService.calendarTravelAccess`(164~167행)는 잠금 없는 경로(`requireRecipient(..., false)`)다.
- readOnly 트랜잭션이면 Hibernate가 flush와 변경 감지를 건너뛰고, 드라이버에 읽기 전용 힌트를 준다.
- 통합 달력은 선택한 공유마다 이 두 API를 월·일 단위로 호출한다.

**확인했지만 문제가 아니었던 것 [코드]** (재제안 금지):
- `default_batch_fetch_size: 50`(`application.yml` 37행)과 `open-in-view: false`(30행)가 이미 있다.
- 가계부 월 조회 `LedgerEntryService.getEntries`(142~148행)는 `toResponse`(1532~1567행)에서 분류·결제수단 프록시를 읽지만 batch fetch로 묶인다.
- 통합 달력 월 요약(`CalendarSummaryService`)과 일 상세(`CalendarDayService`)는 SQL `group by` 집계와 `in :ids` 일괄 조회를 쓴다. N+1이 없다.
- 기록 지도 개요(`ReadRecordMapService.overview` 40~73행)도 일괄 조회만 쓴다.
- 가계부 인덱스 `(owner_id, deleted_at, entry_date, id)` 등은 엔티티와 legacy updater 양쪽에 있어 운영에도 있다.

### 1.3 마이그레이션에만 있고 엔티티에 없는 인덱스 전체 [측정 M5]

| 테이블 | 인덱스 | 이번 처리 |
|---|---|---|
| `record_map_collections` | `idx_record_map_collection_owner (owner_id, created_at, id)` | DB-1에서 엔티티에 추가 |
| `record_map_places` | `idx_record_map_place_owner_updated (owner_id, updated_at, id)` | DB-1 |
| `record_map_visits` | `idx_record_map_visit_owner_place_date (owner_id, place_id, visit_date, id)` | DB-1 (+ 새 `idx_record_map_visit_owner_date`) |
| `record_map_photos` | `idx_record_map_photo_owner_visit (owner_id, visit_id, id)` | DB-1 |
| `record_map_shares` | `idx_record_map_share_owner_collection (owner_id, collection_id)` | DB-1 |
| `ledger_entries` | `idx_ledger_adjustment_origin (owner_id, adjustment_origin_id)` | DB-1 |
| `record_map_collection_places` | `idx_record_map_member_collection` | 보류: Hibernate FK 인덱스(`collection_id`)로 충분 |
| `calendar_map_grants` | `idx_calendar_map_grant_owner (share_owner_id)` | 보류: 철회·탈퇴 경로에서만 사용 |
| `drive_item_versions` | `idx_drive_item_versions_item_owner`, `idx_drive_item_versions_owner_created` | 보류: 버전 기능 트래픽이 낮음 |
| `persistent_logins` | `idx_persistent_logins_user_id` | 보류: 엔티티가 아님(`SecurityConfig` 179행 DDL). 로그아웃·탈퇴 때만 사용 |
| `ledger_embedding_sync_jobs` | `idx_ledger_embedding_ready`, `idx_ledger_embedding_expiry` | 보류: 엔티티가 아님. 임베딩 수집 기본값이 꺼짐(`APP_EMBEDDING_CAPTURE_ENABLED=false`) |
| `withdrawal_recovery_request_limits` | `idx_withdrawal_recovery_limit_expiry` | 보류: 엔티티가 아님. 트래픽이 낮음 |

참고(성능이 아니라 정합성): 유니크 인덱스 `uk_ledger_adjustment_request`, `uk_ledger_split_payment_part`, `uk_app_users_email`도 엔티티에 없다. 중복 데이터가 이미 있으면 유니크 추가가 실패할 수 있으므로 이번 범위에서 다루지 않고 별도로 검토한다(8.2절).

### 1.4 Redis·캐시·외부 호출

인벤토리 [코드]:

| 대상 | 저장소 | 키 | TTL | 무효화 |
|---|---|---|---|---|
| 여행 요약 | cache Redis, Jackson JSON | `travel:plans:{owner}`, `travel:portfolio:{owner}`, `travel:mymap:overview:{owner}` (`TravelSummaryCache.java` 21~23행) | 60초 고정(`application.yml` 172행) | 여행 쓰기 경로 전부가 `invalidateOwner`(31~35행)를 호출. 즉시 1회 + 커밋 후 1회 DEL |
| 사진 클러스터 스냅샷 | cache Redis | `travel:mymap:photo-clusters:{owner}` | 6시간 | 재계산 반영 시, 탈퇴 시 |
| 미디어 다운로드 메타 | 로컬 `BoundedExpiringCache(4096)` | `(userId, mediaId)` | 300초 | 삭제·이관·탈퇴 시 (로컬만) |
| 역지오코딩 | 로컬 4096 + cache Redis | `geo:reverse:%.5f:%.5f` | 24시간 | 없음(값 불변) |
| 장소 검색 | 로컬 256 | 질의 SHA-256 | 24시간 | 없음 |
| 로그인 시도·2차 PIN·데이터 작업 락 | state Redis(`RedisStateService`) | `auth:login-attempt:ip:*` 등 | 24시간 등 | — |
| 공유 세션 | state Redis(Spring Session) | — | 30분 | 선택 기능. 기본값 꺼짐(`application.yml` 249행) |
| 환율 | 로컬 `ConcurrentHashMap` | `{통화}:{날짜}` | 30분 | 없음 |

- Spring Cache(`@Cacheable`), `KEYS` 명령, 패턴 삭제는 쓰지 않는다. 모든 무효화는 키 단위 DEL이다.
- 가계부·통합 달력·기록 지도에는 서버 캐시가 없다. 그래서 낡은 데이터가 보일 위험은 없지만, 요청마다 다시 계산한다.

**C1. 환율 조회: 외부 HTTP에 제한 시간이 없고, 캐시가 무한히 커진다 [코드]**
- `ExchangeRateService`(`backend/.../travel/service/ExchangeRateService.java`)
  - 34행 `restClientBuilder.baseUrl(...).build()`: request factory를 지정하지 않는다. `application.yml`에 `spring.http.client.*` 설정도 없어 연결·읽기 제한 시간이 없다.
  - 같은 프로젝트의 `TravelReverseGeocodeService.initialize`(79~81행)는 `SimpleClientHttpRequestFactory`에 연결 3초·읽기 5초를 준다.
- 이 호출은 DB 쓰기 트랜잭션 안에서 일어난다.
  - 가계부 생성: `LedgerEntryService.create`(696~697행 `@Transactional`)
  - 외화 금액에 환율을 주지 않았을 때: `PrepareLedgerEntryService` 53~54행 → `TravelLedgerExchangeRateAdapter` → `getRateToKrw`
  - 여행 지출 생성: `TravelService.createExpenseRecord`(273~274행 `@Transactional`) → `WriteTravelRecordService` 72행
- 그래서 환율 제공자가 응답하지 않으면 요청 스레드와 **Hikari 연결 1개**가 무기한 묶인다. 풀 크기를 따로 설정하지 않았으므로 기본 최대 10개다.
- 캐시 `cache`(27행)는 만료된 항목을 지우지 않는다. 과거 날짜 키(`{통화}:{과거날짜}`)는 다시 조회되지 않으면 영원히 남는다(71~75·99행).
- 과거 날짜 환율은 바뀌지 않는데도 30분마다 다시 받는다.

**C2. state Redis가 끊기면 로그인 요청 스레드가 재연결을 기다린다 [코드]**
- `RedisStateService.ensureRedisCommands` → `tryConnectRedis`(207~243행)는 요청 스레드가 `synchronized (redisMonitor)`를 잡은 채 `redisClient.connect()`를 동기로 호출한다.
- 연결 대기는 `RedisConnectionSupport.buildUri`의 `withTimeout(3s)`(25행)까지다. 그동안 다른 모든 state Redis 호출(로그인 시도 집계, 2차 PIN)이 같은 모니터에서 기다린다.
- backoff는 30초~300초(29~30행)다. 즉 Redis 장애 중에는 backoff 창마다 로그인·PIN 요청이 최대 3초씩 멈춘다.
- 1차 작업은 `RedisCacheService`만 비동기 재연결로 바꿨다(182~227행: 단일 데몬 스레드, 모니터 밖에서 connect). 회귀 테스트는 `RedisCacheServiceTest.blockedReconnectDoesNotBlockCacheRequestsAndHasSingleAttempt`(41행)이다.

### 1.5 프론트

**F1. `HouseholdWorkspace` 청크 478 kB [측정 M1·M3]**
- `HouseholdWorkspace.vue`가 탭 하나에서만 그리는 컴포넌트를 정적으로 import한다(97~102행).
  - `CalendarWorkspace`(6,177줄): `householdTab === 'calendar'`일 때만 그림(5841행)
  - `RecurringLedgerWorkspace`: `recurring-ledger` 탭에서만(5831행)
  - `LedgerAdjustmentDialog`·`LedgerAdjustmentHistoryDialog`: `v-if`(5733·5736행)
- 기본 탭은 `dashboard`(482행)인데 이것들까지 모두 받는다. 비동기로 바꾸면 **477.82 → 182.99 kB**(gzip 136.56 → 54.63)다 [측정 M3].
- 주의: `calendarWorkspaceRef`를 탭 전환 직후 쓰는 함수가 있다(3176·3183·3240·3268~3269·3278~3280·4839행, 감시자 1469행). 비동기 청크가 아직 로드되지 않았으면 `await nextTick()` 뒤에도 ref가 `null`이다. 그래서 F-1에 로드 대기 도우미를 함께 넣는다.

**F2. 첫 화면이 leaflet과 exifr 전체 빌드를 받는다 [측정 M2·M4]**
- leaflet 경로: `MainDashboardWorkspace.vue` 24행이 `TravelMiniLocationMap`을 정적으로 import한다. 이 컴포넌트는 `leaflet`과 그 CSS를 import하고(`TravelMiniLocationMap.vue` 3~4행), 사진 상세 모달 안(2220행)에서만 쓰인다.
- exifr 경로: 23행이 문자열 도우미 `buildThumbnailUrl`을 쓰려고 `lib/mediaPreview.js`를 import한다. 그 파일 1행이 `import exifr from 'exifr'`(전체 빌드)다.
- exifr를 실제로 호출하는 곳은 세 파일 네 곳이고, 모두 이미 `async` 함수 안이다.
  - `mediaPreview.js` 95행 `orientation`, 103행 `gps`
  - `photoMetadata.js` 105행 `parse`
  - `recordMapPhotoLocation.js` 21행 `gps`

**F3. GET 병합기가 응답마다 `structuredClone`을 한다 [코드 + 측정 M7]**
- `frontend/src/lib/requestCoordinator.js` 35~41행은 기다리는 호출자가 하나뿐이어도 응답 전체를 복제한다.
- 5,000개 사진 목록이면 파싱(2.72 ms)보다 복제(6.05 ms)가 더 비싸다.
- 대상 응답: 포토 프레임 사진 목록, 포트폴리오, 월 가계부 등 큰 일반 GET.

**F4. 가계부 화면의 직렬 로딩 [코드]**
- 마운트(`HouseholdWorkspace.vue` 1497~1519행): `loadMetadata` → `loadEntryDateRange` → `loadAggregatePreferences` → `loadCalendarData` → (통계 탭이면) `loadStatisticsData`를 차례로 await한다. 첫 데이터가 그려지기 전에 왕복 4~5회가 필요하다.
  - 셋 중 앞의 세 함수는 서로 의존하지 않는다.
  - `loadEntryDateRange`(2326~2336행)는 `statsControls.anchorDate`만 바꾼다. 그것도 `!calendarReady`이고 통계 기간이 `ALL`일 때뿐이다. 즉 달력이 아니라 통계에만 영향을 준다.
  - `loadCalendarData`(2414~2432행)는 집계 선호(`buildCalendarAggregateEntryRange`)에 의존한다.
- 저장 후 `refreshLedgerViews`(5014~5026행)도 `await loadEntryDateRange()`를 끝낸 뒤에야 달력을 다시 읽는다. 저장할 때마다 왕복 1회가 더해진다.
- 이 함수의 호출처는 `submitEntry`, `undoLastEntryAction`, `saveEntryFromSearch`, `bulkUpdateSearchEntries`, `restoreEntryFromTrash`, `emptyTrash`, 조정·관리 핸들러다.

**F5. 대시보드 포토 프레임이 렌더마다 전체 목록을 정렬한다 [코드 + 측정 M8]**
- 템플릿(2027~2037행)이 한 팔레트에 `photoFrameHeroFor(palette)`를 6번 호출한다.
- 매 호출이 `photoFrameDisplayItemsFor`(714~726행) → `sortPhotoFramePhotos`(623~630행)로 전체 목록을 복사하고 `localeCompare` 정렬한다.
- 목록은 드라이브 사진 최대 5,000개(`PHOTO_FRAME_FETCH_SIZE` 50행, 1687행)와 여행 사진 전체다.
- 5,000개 기준 한 번 정렬이 약 5~6 ms이므로 렌더 1회에 팔레트 하나당 약 30~37 ms가 든다 [측정 M8 × 호출 수 6].

---

## 2. 우선순위 표

평가 기준: 영향(사용자 체감·자원) × 위험(동작 회귀 가능성) × 노력. 각 항목은 상(H)·중(M)·하(L)로 매겼다.

| ID | 항목 | 영향 | 위험 | 노력 | 근거 | 결정 |
|---|---|---|---|---|---|---|
| DB-1 | 기록 지도·가계부 인덱스를 엔티티에 맞추기 + 새 날짜 인덱스 + 마이그레이션 063 | H (통합 달력 월·일, 기록 지도 목록의 전체 스캔 제거) | L (인덱스 추가만) | M | M5, D1·D2 | **지금** |
| DB-2 | 드라이브 관리자 통계의 N+1 제거 | M (관리자 화면. 사용자 수에 비례하던 쿼리가 2개로) | L | L | M6, D3 | **지금** |
| DB-3 | 공유 달력 summary·day를 readOnly로 | L | L | L | D5 | **지금**(DB-2와 함께) |
| DB-4 | 기록 지도·달력 공유 썸네일의 `w` 파라미터 수용 | M (목록 썸네일 전송량 약 1/4) | L | L | D4 | **지금** |
| C-1 | 환율: HTTP 제한 시간 + 크기 제한 캐시 | H (장애 시 DB 연결 고갈 예방) | L | L | C1 | **지금** |
| C-2 | `RedisStateService` 비동기 재연결 | M (Redis 장애 시 로그인 지연 제거) | M (동시성 코드) | M | C2 | **지금** |
| F-1 | 가계부 탭 전용 컴포넌트 비동기 분리 | H (기본 탭 −295 kB, gzip −82 kB) | M (ref 타이밍) | M | M3, F1 | **지금** |
| F-2 | 첫 화면에서 leaflet·exifr 분리 | H (첫 화면 −241 kB, gzip 약 −76 kB) | L | L | M4, F2 | **지금** |
| F-3 | GET 병합기의 불필요한 복제 생략 | M (큰 GET마다 수 ms) | L | L | M7, F3 | **지금** |
| F-4 | 가계부 마운트·저장 후 로딩 병렬화 | M (마운트 왕복 −2회, 저장마다 −1회) | M | L | F4 | **지금** |
| F-5 | 포토 프레임 정렬 결과 재사용 | M (렌더당 수십 ms) | L | L | M8, F5 | **지금** |

진행 순서: 1단계(백엔드 DB) DB-1 → DB-2·DB-3 → DB-4, 2단계(Redis·캐시) C-1 → C-2, 3단계(프론트) F-2 → F-1 → F-3 → F-5 → F-4. 각 단계가 끝날 때마다 검증 명령을 통과시킨 뒤 다음 단계로 간다.

보류 항목과 이유는 8.2절에 있다.

---

## 3. 1단계 — 백엔드 DB

### DB-1. 기록 지도·가계부 인덱스를 엔티티에 맞추고 마이그레이션 추가 (완료 2026-11-22)

- 목적: Flyway 없이 `ddl-auto: update`로 운영하는 환경에서도 같은 인덱스가 생기게 한다. 통합 달력이 쓰는 방문 날짜 인덱스를 새로 만든다.
- 파일:
  - `backend/src/main/java/com/playdata/calen/recordmap/domain/PlaceVisit.java`
  - `backend/src/main/java/com/playdata/calen/recordmap/domain/PlacePhoto.java`
  - `backend/src/main/java/com/playdata/calen/recordmap/domain/RecordPlace.java`
  - `backend/src/main/java/com/playdata/calen/recordmap/domain/PlaceCollection.java`
  - `backend/src/main/java/com/playdata/calen/recordmap/domain/RecordMapShare.java`
  - `backend/src/main/java/com/playdata/calen/ledger/domain/LedgerEntry.java`
  - 신규 `backend/src/main/resources/db/migration/V20261122_063__performance_indexes.sql`
  - `docs/db_migration_strategy.md`(7.1절 행 추가)
  - 테스트: 신규 `backend/src/test/java/com/playdata/calen/common/schema/PerformanceIndexSchemaTest.java`, 신규 `backend/src/test/java/com/playdata/calen/common/schema/PerformanceIndexMigrationTest.java`
- 변경 — 엔티티(`@Table`의 `indexes`에 추가. 기존 `name`과 `uniqueConstraints`는 그대로 둔다):

  | 엔티티 | 추가할 `@Index` (이름 / columnList) |
  |---|---|
  | `PlaceVisit` | `idx_record_map_visit_owner_place_date` / `owner_id, place_id, visit_date, id`<br>`idx_record_map_visit_owner_date` / `owner_id, visit_date, visit_time, id` **(신규)** |
  | `PlacePhoto` | `idx_record_map_photo_owner_visit` / `owner_id, visit_id, id` |
  | `RecordPlace` | `idx_record_map_place_owner_updated` / `owner_id, updated_at, id` |
  | `PlaceCollection` | `idx_record_map_collection_owner` / `owner_id, created_at, id` |
  | `RecordMapShare` | `idx_record_map_share_owner_collection` / `owner_id, collection_id` |
  | `LedgerEntry` | `idx_ledger_adjustment_origin` / `owner_id, adjustment_origin_id`<br>`idx_ledger_entries_owner_travel_record` / `owner_id, travel_record_id` **(신규)** |

  - 이름과 컬럼 순서는 기존 마이그레이션(`V20261013_033__record_map.sql`, `V20261030_033_1__ledger_payment_adjustments.sql` 13행)과 **글자 하나까지 같게** 한다. Hibernate update는 이름으로 이미 있는 인덱스를 찾아 건너뛰므로, Flyway로 이미 만든 DB에서는 아무 일도 하지 않는다.
  - `jakarta.persistence.Index` import를 추가한다. 다른 필드, 매핑, `@Version`은 건드리지 않는다.
- 변경 — 마이그레이션 `V20261122_063__performance_indexes.sql`(주석은 영어 한두 줄, 기존 파일 형식을 따른다):
  ```sql
  -- Entity-declared indexes for ddl-auto environments and Flyway baselines that skipped inline table indexes.
  CREATE INDEX IF NOT EXISTS idx_record_map_collection_owner ON record_map_collections (owner_id, created_at, id);
  CREATE INDEX IF NOT EXISTS idx_record_map_place_owner_updated ON record_map_places (owner_id, updated_at, id);
  CREATE INDEX IF NOT EXISTS idx_record_map_visit_owner_place_date ON record_map_visits (owner_id, place_id, visit_date, id);
  CREATE INDEX IF NOT EXISTS idx_record_map_visit_owner_date ON record_map_visits (owner_id, visit_date, visit_time, id);
  CREATE INDEX IF NOT EXISTS idx_record_map_photo_owner_visit ON record_map_photos (owner_id, visit_id, id);
  CREATE INDEX IF NOT EXISTS idx_record_map_share_owner_collection ON record_map_shares (owner_id, collection_id);
  CREATE INDEX IF NOT EXISTS idx_ledger_adjustment_origin ON ledger_entries (owner_id, adjustment_origin_id);
  CREATE INDEX IF NOT EXISTS idx_ledger_entries_owner_travel_record ON ledger_entries (owner_id, travel_record_id);
  ```
  - 번호: 최신은 `V20261114_062`다(`057`은 원래 비어 있음). 다음 번호는 `063`이다. 작성 날짜가 다르면 `V{작성일}_063__performance_indexes.sql`로 하고, 이 문서와 `db_migration_strategy.md` 행도 같은 이름으로 맞춘다.
  - `ALTER TABLE`, `DROP`, 데이터 변경은 넣지 않는다.
- 제약:
  - 쿼리 문장(`CalendarSummaryService`, `CalendarDayService`, 각 Repository)은 바꾸지 않는다. 인덱스만 추가한다.
  - MariaDB InnoDB의 `CREATE INDEX`는 기본으로 온라인(INPLACE, LOCK=NONE)이지만 큰 테이블에서는 시간이 걸린다. 적용 시점은 사용자가 정한다(9장). 구현자는 운영 DB에 아무것도 하지 않는다.
- 테스트:
  1. `PerformanceIndexSchemaTest`
     - 설정: `@DataJpaTest(properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop", "app.schema.legacy-updaters.enabled=false"})`. `DriveItemRepositoryTest`와 같은 형식이다.
     - `JdbcTemplate`으로 `select index_name, column_name from information_schema.index_columns where table_name = ? order by index_name, ordinal_position`을 조회한다(H2는 대문자로 돌려주므로 비교 전에 소문자로 바꾼다).
     - 위 표의 인덱스 8개가 각각 **정확한 컬럼 순서**로 있는지 검사한다.
     - 테스트 이름 예: `entityIndexesMatchFlywayNamesSoDdlAutoEnvironmentsGetThem`.
  2. `PerformanceIndexMigrationTest`(`RecordMapMigrationTest`·`EmailSignupExpiryMigrationTest` 형식, H2 `MODE=MySQL`)
     - (a) 새 DB: `app_users`를 만든 뒤 `V20261013_033__record_map.sql`과 `V20261111_058__record_map_undated_photos.sql`을 적용한다. `ledger_entries`는 필요한 컬럼(`id`, `owner_id`, `adjustment_origin_id`, `travel_record_id`)만 가진 최소 테이블로 만든다. 그 다음 063을 **두 번** 실행해도 예외가 없어야 한다.
     - (b) Hibernate가 만든 것과 같은 DB: 인라인 인덱스 없이 `record_map_*` 최소 테이블을 직접 만든다(컬럼만). 그 위에서 063을 두 번 실행하고, `information_schema.index_columns`로 8개 인덱스가 모두 생겼는지 확인한다.
     - (c) 두 경우 모두 기존 행이 그대로인지(행 수와 값) 확인한다.
- 검증 명령:
  - `cd backend && JAVA_HOME=/c/jdk-17 ./gradlew test --tests 'com.playdata.calen.common.schema.*'`
    - 기존 `StartupSchemaGovernanceTest`·`LegacySchemaCompatibilityTest`도 이 패키지에 있으므로 함께 통과해야 한다.
  - `cd backend && JAVA_HOME=/c/jdk-17 ./gradlew test --tests 'com.playdata.calen.recordmap.*'`
  - `cd backend && JAVA_HOME=/c/jdk-17 ./gradlew test --tests 'com.playdata.calen.calendar.*'`
  - `cd backend && JAVA_HOME=/c/jdk-17 ./gradlew test --tests 'com.playdata.calen.ledger.service.*'`
  - `powershell -ExecutionPolicy Bypass -File scripts/verify-db-migrations.ps1`(7.1절 참고)
- 완료 기준: 위 테스트 모두 통과, `docs/db_migration_strategy.md`에 063 행 2개 추가, 쿼리·응답 변경 없음.

### DB-2. 드라이브 관리자 통계의 N+1 제거 (완료 2026-11-22)

- 파일: `backend/src/main/java/com/playdata/calen/drive/service/DriveAdminService.java`, 테스트 신규 `backend/src/test/java/com/playdata/calen/drive/service/DriveAdminServiceQueryCountTest.java`
- 변경: `getStorageAnalytics`(98행 이후)에서 이미 읽은 `items`를 `getDashboard`(76~78행)와 같은 방식으로 `Collectors.groupingBy(item -> item.getOwner().getId())`로 묶는다. 사용자별 응답은 `toAdminUserResponse(user, itemsByUser.getOrDefault(user.getId(), List.of()))`로 만든다.
  - 103행의 `driveItemRepository.findAllByOwner_Id(...)` 호출을 없앤다.
  - 정렬(`usedBytes` 내림차순), 합계, `issues` 계산, MinIO `objectExists` 루프(114~119행)는 **그대로** 둔다.
  - 171행의 단일 사용자 조회(`findAllByOwner_Id`)는 한 명만 다루므로 그대로 둔다.
- 제약: `ensureAdmin(admin)` 호출과 그 위치를 유지한다. 응답 DTO 필드 값은 이전과 같아야 한다.
- 테스트(`DriveAdminServiceQueryCountTest`):
  - `@DataJpaTest`(DB-1과 같은 속성)에 `spring.jpa.properties.hibernate.generate_statistics=true`를 **이 테스트 속성으로만** 추가한다. `application.yml`은 건드리지 않는다.
  - `DriveAdminService`는 생성자로 직접 만든다. 저장소 빈은 주입받고, `DriveStorageService`는 Mockito mock(`objectExists`가 `true`를 반환)으로, 나머지 의존성(withdrawal 관련 등)은 mock으로 넣는다.
  - 사용자 3명 × 파일 2개(한 명은 휴지통 파일 1개 포함)를 만든다. `entityManager.flush(); clear();` 뒤 `SessionFactory.getStatistics().clear()`를 호출하고, `getStorageAnalytics(true)`를 실행한다.
  - 단언 1: `getPrepareStatementCount()`가 **사용자 수와 무관**해야 한다. 사용자 3명과 10명일 때 같은 값인지 확인한다. 관리자 설정 조회·저장 1~2개가 더해질 수 있으므로 절대값이 아니라 "두 경우가 같다"로 단언한다.
  - 단언 2: 사용자별 `fileCount`·`trashedCount`·`usedBytes`가 기대값과 같다.
  - `getStorageAnalytics(false)`는 `AccessDeniedException`을 던져야 한다.
- 검증: `cd backend && JAVA_HOME=/c/jdk-17 ./gradlew test --tests 'com.playdata.calen.drive.service.*'`

### DB-3. 공유 달력 summary·day를 읽기 전용 트랜잭션으로 (완료 2026-11-22)

- 파일: `backend/src/main/java/com/playdata/calen/calendar/CalendarSharedService.java`
- 변경: 82행과 96행의 `@Transactional`을 `@Transactional(readOnly = true)`로 바꾼다. `setSelected`(68행)는 쓰기이므로 그대로 둔다.
- 제약: 메서드 본문, 권한 확인(`contains(selections(...))`, `access(...)`, `plans.findByIdAndOwnerId`)과 그 순서를 바꾸지 않는다.
- 테스트: 기존 `CalendarEventIntegrationTest`가 `/api/integrated-calendar/shared` 흐름(196·349행 이후)을 이미 검사하므로 그대로 통과해야 한다. 새 테스트는 필요 없다.
  - 선택: 같은 클래스에 "선택하지 않은 공유의 summary는 404, 선택한 공유는 200이고 `records[].importedCount == 0`" 단언이 없으면 하나 추가한다.
- 검증: `cd backend && JAVA_HOME=/c/jdk-17 ./gradlew test --tests 'com.playdata.calen.calendar.*'`

### DB-4. 기록 지도·달력 공유 사진 썸네일에서 `w` 파라미터 받기 (완료 2026-11-22)

- 파일:
  - `backend/src/main/java/com/playdata/calen/recordmap/adapter/in/web/RecordMapController.java`(90~93, 122~125, 127~130행)
  - `backend/src/main/java/com/playdata/calen/calendar/CalendarMapShareController.java`(52~56행)
  - 테스트: `backend/src/test/java/com/playdata/calen/recordmap/RecordMapIntegrationTest.java`에 메서드 추가
- 변경: 네 엔드포인트에 `@RequestParam(name = "w", required = false) Integer w`를 **추가**한다. 기존 `width` 파라미터는 지우지 않는다. 실제 폭은 `width != null ? width : w`로 정한다.
  - 같은 계산이 반복되므로 각 컨트롤러에 `private static Integer requestedWidth(Integer width, Integer w)` 도우미를 하나씩 둔다.
- 동작 변화(의도한 것): 프론트가 `w=240`을 보내면 240 버킷 썸네일이 나간다. 지금은 항상 480이다.
  - 응답 헤더(`no-store`, `nosniff`, `Referrer-Policy`, `Content-Disposition`)는 그대로 둔다.
  - 프론트는 고치지 않는다.
- 제약: 권한 경로(`media.getOwned`, `getShared`, `getSharedTravel`, `maps.photo`)를 그대로 쓴다.
- 테스트:
  - `RecordMapIntegrationTest`에 `ownedPhotoThumbnailHonorsShortWidthParameter`를 추가한다.
  - 이 클래스는 MinIO 없이 로컬 파일 저장소(`app.travel.media-storage-path=build/record-map-test-media`, 45행)와 실제 `TravelMediaStorageService` 빈(65행)을 쓰고, 403~404행처럼 진짜 PNG를 multipart로 올린다. 이 방식을 그대로 따른다.
  - 절차:
    1. 1000×500 PNG를 테스트 안에서 `BufferedImage`로 만들어 올린다. 서버는 원본보다 좁은 버킷만 만들므로 1000 px이어야 96·240·480·960이 모두 생긴다.
    2. `GET /api/record-map/photos/{id}/content?thumbnail=true&w=240` 응답 본문을 `ImageIO.read`로 읽으면 폭이 **240**이어야 한다.
    3. `?thumbnail=true`만 보내면 **480**(기존 기본값), `?thumbnail=true&width=96`이면 **96**이어야 한다(기존 파라미터 유지).
    4. 다른 사용자(`as(...)`)가 같은 URL을 요청하면 지금과 같은 상태 코드(404 또는 403)가 나와야 한다.
  - `CalendarMapShareController`는 같은 도우미를 쓰므로, `CalendarEventIntegrationTest`의 `shared-map` 흐름(278행 이후)에 사진이 있으면 `w=240` 단언을 하나 추가한다. 사진 준비가 번거로우면 도우미 메서드를 패키지 전용 정적 메서드로 두고 단위 테스트로 대신한다.
- 검증: `cd backend && JAVA_HOME=/c/jdk-17 ./gradlew test --tests 'com.playdata.calen.recordmap.*'`, `... --tests 'com.playdata.calen.calendar.*'`

---

## 4. 2단계 — Redis·캐시·외부 호출

### C-1. 환율 조회에 제한 시간과 크기 제한 캐시 적용 (완료 2026-11-22)

- 파일: `backend/src/main/java/com/playdata/calen/travel/service/ExchangeRateService.java`, 테스트 신규 `backend/src/test/java/com/playdata/calen/travel/service/ExchangeRateServiceTest.java`
- 변경:
  1. 생성자 34행: `SimpleClientHttpRequestFactory`를 만들고 연결 3초·읽기 5초를 설정한다(`TravelReverseGeocodeService` 79~81행과 같은 값·방식). `restClientBuilder.requestFactory(factory).baseUrl(...).build()`로 만든다. 상수 `CONNECT_TIMEOUT`·`READ_TIMEOUT`을 클래스 안에 둔다(새 설정 키 금지).
  2. 27행 `ConcurrentHashMap`을 `com.playdata.calen.common.cache.BoundedExpiringCache<String, CachedRate>`(최대 512개)로 바꾼다.
     - **낡은 값 폴백(120~125행 `fallback(..., cachedRate)`)을 지켜야 한다.** `BoundedExpiringCache.get`은 만료된 항목을 지워 버리므로 다음과 같이 한다.
       - 캐시 자체의 TTL은 길게 둔다(상수 `STALE_RETENTION = Duration.ofDays(7)`).
       - "신선한지"는 지금처럼 `CachedRate.expiresAt`으로 판단한다(73행 로직 유지).
       - 결과: 신선하면 그대로 반환하고, 신선하지 않으면 다시 조회한다. 조회에 실패하면 7일 안의 낡은 값을 반환한다(지금과 같다).
     - 만료 정리: `@Scheduled(fixedDelay = 600_000)` 메서드에서 `cache.evictExpired()`를 호출한다. 스케줄링은 이미 켜져 있다(`spring.task.scheduling.pool.size: 4`). 스케줄러를 쓰기 어렵다면 `put` 때 1% 확률로 정리해도 된다.
  3. 과거 날짜 환율의 신선도: `rateDate != null && resolvedRateDate.isBefore(LocalDate.now())`이면 `expiresAt`을 `now + 24시간`으로, 그 밖에는 지금처럼 `cacheDuration`(30분)으로 한다.
     - 동작 변화(의도한 것): 과거 날짜 환율을 30분이 아니라 24시간 동안 재사용한다. 과거 환율은 제공자 쪽에서 바뀌지 않으므로 값은 같다.
     - 이 변경이 부담되면 생략해도 된다. 1·2번만으로도 이 항목의 목적은 달성된다.
- 제약:
  - 메서드 시그니처, 반환 DTO, 예외 메시지(55행), `KRW` 처리(67~69행), 실패 시 `available=false` 응답은 그대로 둔다.
  - 트랜잭션 경계(`LedgerEntryService.create` 등)는 이번에 옮기지 않는다(8.2절).
- 테스트(`ExchangeRateServiceTest`, 외부 네트워크를 쓰지 않는다):
  - JDK 내장 `com.sun.net.httpserver.HttpServer`를 `127.0.0.1:0`에 띄운다. `/latest`는 `{"base":"USD","date":"2026-11-20","rates":{"KRW":1400.5}}`를 반환하고, `/slow`용 경로는 응답 전에 8초를 잔다.
  - `RestClient.builder()`로 서비스를 만든다. `baseUrl`은 테스트 서버 주소, `cacheMinutes`는 1이다.
  - 테스트 목록:
    1. `returnsRateAndCachesFreshValue`: 두 번 호출해도 서버 요청은 1회(서버에서 호출 수를 센다).
    2. `slowProviderTimesOutWithinReadTimeout`: 응답하지 않는 서버를 가리키게 하고 `getRateToKrw("USD", null)`을 호출한다. 결과는 `available=false`여야 하고, 경과 시간은 **7초 미만**이어야 한다(`assertTimeoutPreemptively(Duration.ofSeconds(10), ...)`로 감싸 무한 대기를 막는다).
    3. `staleValueIsReturnedWhenProviderFailsAfterExpiry`: 시계를 직접 바꿀 수 없으므로 패키지 전용 생성자로 `cacheDuration`을 1 ms로 주입한다. 첫 호출이 성공한 뒤 서버를 500으로 바꾸고 다시 호출하면 직전 값이 반환되어야 한다.
    4. `cacheIsBounded`: 서로 다른 과거 날짜로 600번 조회한 뒤, 패키지 전용 `int cacheSizeForTest()`가 512 이하여야 한다.
  - 테스트용 패키지 전용 생성자·메서드 이름은 `...ForTest`로 끝나게 한다(기존 `onDemandThumbnailPermitsForTest` 관례).
- 검증: `cd backend && JAVA_HOME=/c/jdk-17 ./gradlew test --tests 'com.playdata.calen.travel.service.ExchangeRateServiceTest'`, 이어서 `... --tests 'com.playdata.calen.ledger.service.*'`

### C-2. `RedisStateService` 재연결을 요청 스레드 밖으로 (완료 2026-11-22)

- 파일: `backend/src/main/java/com/playdata/calen/account/service/RedisStateService.java`, 테스트 신규 `backend/src/test/java/com/playdata/calen/account/service/RedisStateServiceReconnectTest.java`
- 변경: `RedisCacheService`(`common/cache/RedisCacheService.java` 36~41, 182~249행)의 비동기 재연결 구조를 그대로 옮긴다.
  - 단일 데몬 스레드 `ExecutorService`(스레드 이름 `redis-state-reconnect`)와 `AtomicBoolean reconnecting`을 둔다.
  - `ensureRedisCommands()`: 연결이 열려 있으면 그 명령 객체를 반환한다. 아니면 `requestReconnect()`만 호출하고 `null`을 반환한다. 호출한 쪽은 기존 메모리 폴백을 쓴다.
  - `requestReconnect()`: backoff 시각 전이거나 이미 재연결 중이면 아무것도 하지 않는다.
  - `connectInBackground()`: 모니터 **밖에서** `redisClient.connect()`를 호출하고, 성공하면 모니터 안에서 필드만 바꾼다.
  - `markRedisUnavailable`: 실패한 연결을 분리하고 backoff를 예약한다. 연결 닫기는 실행기에서 한다.
  - `@PreDestroy`에서 실행기를 종료한다. `RedisCacheService`의 `stopped` 처리를 참고한다.
  - 시작 시 연결(`@PostConstruct initialize()` 67행 이후)은 지금처럼 동기로 둔다. 기동 직후의 첫 로그인부터 Redis를 쓰게 하기 위해서다.
  - backoff 상수(29~30행)와 명령 타임아웃(3초)은 바꾸지 않는다.
- 제약: 공개 메서드(`get`, `set`, `setIfAbsent`, `compareAndDelete`, `increment`, `delete`, `scanKeys`)의 시그니처와 "Redis를 쓸 수 없으면 null·-1·빈 목록을 반환한다"는 의미를 그대로 둔다. `LoginAttemptService`·`SecondaryPinAttemptService`·`AdminDataManagementService`는 고치지 않는다.
- 테스트(`RedisCacheServiceTest` 41행 `blockedReconnectDoesNotBlockCacheRequestsAndHasSingleAttempt`와 같은 방식):
  1. `blockedReconnectDoesNotBlockStateRequestsAndHasSingleAttempt`: `RedisClient` mock의 `connect()`가 `CountDownLatch`에서 멈추게 한다. 그 상태에서 여러 스레드가 `get("k")`를 호출해도 각 호출은 **200 ms 안에** `null`을 반환해야 하고, `connect()` 호출은 1회여야 한다.
  2. `commandFailureFallsBackAndSchedulesBackoff`: 명령이 예외를 던지면 이후 호출이 즉시 폴백하고, backoff 시간 안에는 `connect()`를 다시 호출하지 않아야 한다.
  - mock 주입 방식은 `RedisCacheServiceTest`가 쓰는 방식(패키지 전용 필드 설정이나 리플렉션)을 그대로 따른다.
- 검증: `cd backend && JAVA_HOME=/c/jdk-17 ./gradlew test --tests 'com.playdata.calen.account.service.*'`, 이어서 `... --tests 'com.playdata.calen.common.cache.*'`

---

## 5. 3단계 — 프론트

공통 제약:
- 새 npm 의존성을 추가하지 않는다. `vite.config.js`에 `manualChunks`를 추가하지 않는다(이번 범위 밖).
- `frontend/src/style.css`와 `frontend/src/lib/api.js`는 수정하지 않는다.
- 화면 문구, 클래스 이름, `data-*` 속성은 바꾸지 않는다. e2e가 이것들을 쓴다.

### F-2. 첫 화면에서 leaflet과 exifr 떼어내기 (완료 2026-11-22)

- 파일: `frontend/src/components/MainDashboardWorkspace.vue`, `frontend/src/lib/mediaPreview.js`, `frontend/src/lib/photoMetadata.js`, `frontend/src/lib/recordMapPhotoLocation.js`
- 변경:
  1. `MainDashboardWorkspace.vue` 24행 `import TravelMiniLocationMap from './TravelMiniLocationMap.vue'`를 `const TravelMiniLocationMap = defineAsyncComponent(() => import('./TravelMiniLocationMap.vue'))`로 바꾼다. 2행 vue import에 `defineAsyncComponent`를 추가한다. 사용처(2220행)는 그대로 둔다.
  2. `mediaPreview.js` 1행의 정적 exifr import를 지우고, 모듈 안에 지연 로더를 둔다.
     ```js
     let exifrModule = null
     function loadExifr() {
       exifrModule ||= import('exifr').then((module) => module.default ?? module)
       return exifrModule
     }
     ```
     95행은 `const exifr = await loadExifr(); return normalizeExifOrientation(await exifr.orientation(file))`로 바꾼다. 103행도 같은 방식이다. 두 곳 모두 이미 `try { … } catch` 안에 있으므로 import 실패도 기존 폴백(방향 1, GPS null)으로 처리된다.
  3. `photoMetadata.js`(1·105행)와 `recordMapPhotoLocation.js`(1·21행)도 같은 방식으로 바꾼다. 각 파일에 같은 로더를 두거나, 새 파일 `frontend/src/lib/exifrLoader.js`에 `export function loadExifr()`를 하나 두고 세 파일이 함께 쓴다(권장).
  - export 이름과 함수 시그니처(`buildThumbnailUrl`, `THUMBNAIL_*`, `prepareClientImageUpload`, `createLocalImagePreview` 등)는 바꾸지 않는다.
- 제약:
  - `scripts/verify-media-processing-contract.ps1`가 검사하는 문자열(`docs/photo_orientation_fix_workplan.md` 0장 10번)을 지우지 않는다.
  - `exifr/dist/lite`로 바꾸지 않는다. `parse` 옵션 호환성을 확인하지 않았다.
- 테스트:
  - 기존 단위 테스트 `imageOrientation.test.js`, `recordMapPhotoLocation.test.js`, `travelImport.test.js`가 통과해야 한다.
  - `recordMapPhotoLocation.test.js`가 exifr를 모듈 단위로 바꿔 끼우고 있다면, 동적 import에서도 같은 방식으로 동작하는지 확인한다.
  - 빌드 확인(수동):
    - `npm run build` 뒤 `grep -o 'from"\./[^"]*"' dist/assets/MainDashboardWorkspace-*.js`의 결과에 `TravelMiniLocationMap`과 `leaflet`이 없어야 한다.
    - `ls -la dist/assets/mediaPreview-*.js` 크기가 10 kB 미만이어야 한다(측정 M4: 6.07 kB).
- 검증:
  - `cd frontend && npm run test:unit && npm run build`
  - e2e: `main-dashboard-responsive.spec.js`, `photo-orientation.spec.js`, `record-map.spec.js`. 여행 사진 업로드 스펙(`travel-photo-add.spec.js`, `travel-import.spec.js`)이 있으면 함께 돌린다(공통 명령).

### F-1. 가계부 탭 전용 컴포넌트를 비동기로 분리 (완료 2026-11-22)

- 파일: `frontend/src/components/HouseholdWorkspace.vue`
- 변경:
  1. 98·99·101·102행의 정적 import를 바꾼다.
     ```js
     const loadCalendarWorkspace = () => import('./CalendarWorkspace.vue')
     const CalendarWorkspace = defineAsyncComponent(loadCalendarWorkspace)
     const RecurringLedgerWorkspace = defineAsyncComponent(() => import('./RecurringLedgerWorkspace.vue'))
     const LedgerAdjustmentDialog = defineAsyncComponent(() => import('./LedgerAdjustmentDialog.vue'))
     const LedgerAdjustmentHistoryDialog = defineAsyncComponent(() => import('./LedgerAdjustmentHistoryDialog.vue'))
     ```
     **`PaletteContainer`(97행)는 정적으로 남긴다.** 기본 탭(대시보드)이 곧바로 쓰므로, 비동기로 바꾸면 왕복이 하나 더 생긴다.
  2. ref 타이밍 보완 도우미를 추가한다.
     ```js
     async function ensureCalendarWorkspaceMounted() {
       await loadCalendarWorkspace()
       for (let attempt = 0; attempt < 5 && !calendarWorkspaceRef.value; attempt += 1) await nextTick()
       return calendarWorkspaceRef.value
     }
     ```
     달력 탭으로 바꾼 직후 `calendarWorkspaceRef.value?.…`를 호출하는 곳의 `await nextTick()`을 `await ensureCalendarWorkspaceMounted()`로 바꾼다. 대상은 3175~3176, 3182~3183, 3240, 3267~3269, 3277~3280, 4839행 주변이다. 이 중 3176행처럼 바로 앞에 `await nextTick()`이 없는 곳은 호출 앞에 `await ensureCalendarWorkspaceMounted()`를 넣는다.
     - 1469행 감시자는 ref가 생기면 다시 실행되므로 고치지 않는다.
  3. 미리 받아 두기: `onMounted` 끝(1519행 직전, `finally` 뒤)에서 `requestIdleCallback`이 있으면 그것으로, 없으면 `setTimeout(…, 1500)`으로 `loadCalendarWorkspace()`를 한 번 호출한다. 실패는 무시한다(`.catch(() => {})`). 첫 화면을 막지 않으면서도 탭 전환이 즉시 되게 하기 위해서다.
- 제약:
  - props, emit, `v-if`/`v-else-if` 체인(5818~5848행)을 바꾸지 않는다.
  - `calendarReady`, `statsReady` 의미를 바꾸지 않는다.
  - 비동기 로드 중에 보여 줄 placeholder를 따로 만들지 않는다(기본 동작 유지).
- 테스트·검증:
  - `cd frontend && npm run build`: `HouseholdWorkspace-*.js` 크기가 **200 kB 미만**이어야 한다(측정 M3: 182.99 kB). `CalendarWorkspace-*.js`가 별도 청크로 생겨야 한다.
  - e2e(달력 가계부와 거래 관리 흐름, 공통 명령):
    - `ledger-transaction-manage-flow.spec.js`, `ledger-manage-sheet.spec.js`, `ledger-adjustments.spec.js`, `ledger-adjustment-grouping.spec.js`, `ledger-split-payments.spec.js`
    - `transaction-sheet-date-navigation.spec.js`, `calendar-quick-entry-layout.spec.js`, `calendar-wysiwyg.spec.js`
  - 수동: 여행 가계부 탭의 "날짜 보기"·"수정"(`viewTravelLedgerEntryDate`, `editTravelLedgerEntry`)을 **새로고침 직후 첫 클릭**으로 눌렀을 때 달력 탭으로 바뀌고 해당 날짜가 선택되며 스크롤되는지 확인한다. 통합 달력에서 가계부로 넘어오는 `initialCalendarFocus`도 확인한다.

### F-3. GET 병합기에서 불필요한 복제 생략 (완료 2026-11-22)

- 파일: `frontend/src/lib/requestCoordinator.js`, `frontend/tests/unit/requestCoordinator.test.js`
- 변경: 진행 중인 요청 항목을 `{ promise, waiters }`로 관리한다.
  ```js
  let entry = pending.get(key)
  if (!entry) { entry = { promise: Promise.resolve().then(() => performRequest(path, options)), waiters: 0 }; pending.set(key, entry) }
  entry.waiters += 1
  try {
    const response = await entry.promise
    if (pending.get(key) === entry) pending.delete(key) // 응답 후에는 새 합류를 막는다
    entry.waiters -= 1
    if (response == null) return response
    return entry.waiters === 0 ? response : structuredClone(response)
  } catch (error) {
    entry.waiters -= 1
    if (pending.get(key) === entry) pending.delete(key)
    throw error
  }
  ```
  - 마지막으로 재개되는 호출자 하나만 원본을 받고, 나머지는 복제본을 받는다. 호출자가 하나면 복제하지 않는다.
  - 쓰기 요청 처리(13~24행), `generation`, `plainGet` 판정(25~27행)은 그대로 둔다.
- 테스트(기존 3개는 그대로 통과해야 한다). 추가할 것:
  1. `single GET returns the performed response without cloning`: `performRequest`가 돌려준 객체와 결과가 같은 객체(`assert.equal(result, original)`)여야 한다.
  2. `three simultaneous GETs receive three independent objects`: 셋 중 하나를 바꿔도 나머지가 바뀌지 않아야 한다. 호출 수는 1이다.
  3. `a GET started after the shared response settled performs a new request`: 실패·성공 뒤에 새로 호출하면 새 요청을 보내야 한다.
- 검증: `cd frontend && npm run test:unit && npm run build`. e2e는 `session-bootstrap.spec.js`, `main-dashboard-responsive.spec.js`, `smoke.spec.js`를 돌린다.

### F-5. 포토 프레임 정렬 결과 재사용 (완료 2026-11-22)

- 파일: `frontend/src/components/MainDashboardWorkspace.vue`
- 변경:
  - 렌더마다 `photoFrameDisplayItemsFor(palette)`를 6번 다시 계산하지 않도록 계산 결과를 기억해 둔다.
  - 방법: `computed`로 `photoFrameHeroByPaletteKey`(`Map`)를 만든다. 키는 팔레트 식별자(기존 `palette.id`. 없으면 템플릿 `v-for`의 key와 같은 값)다. 값은 `photoFrameDisplayItemsFor(palette)[0] || null`이다.
  - 이 computed는 팔레트 목록, 사진 원본 목록(`photoFrameItems`·드라이브·여행), 각 팔레트의 `options`, 랜덤 시드에 의존한다.
  - 템플릿 2027~2037행의 `photoFrameHeroFor(palette)` 호출은 `photoFrameHeroFor`가 이 Map에서 읽도록 바꾼다(템플릿은 그대로).
  - 랜덤 모드의 시드·간격 동작(`photoFrameRandomSeed`, 시계 타이머 653~680행)이 바뀌면 computed가 다시 계산되도록 같은 반응형 값을 읽게 한다.
- 제약:
  - 어떤 사진이 고정·랜덤·최신 모드에서 선택되는지는 바뀌면 안 된다.
  - `PHOTO_FRAME_FETCH_SIZE`(50행)와 hero 이미지 URL(`photoFrameDashboardImageUrl` 732~733행, 원본 사용)은 이번에 바꾸지 않는다(8.2절).
- 테스트:
  - 선택 로직을 순수 함수로 떼어낼 수 있으면 `frontend/src/lib/photoFrameSelection.js`(신규)에 두고 `frontend/tests/unit/photoFrameSelection.test.js`(신규)에서 다음을 검사한다.
    - 고정 모드: 지정 id, 없으면 첫 항목
    - 최신순: `updatedAt` 내림차순 첫 항목
    - 이름순: `localeCompare('ko-KR')` 첫 항목
    - 랜덤: 같은 시드면 같은 항목
  - 떼어내지 않는다면 e2e `main-dashboard-responsive.spec.js`가 통과하는 것으로 확인한다.
- 검증: `cd frontend && npm run test:unit && npm run build`, e2e `main-dashboard-responsive.spec.js`

### F-4. 가계부 마운트·저장 후 로딩 병렬화 (완료 2026-11-22)

- 파일: `frontend/src/components/HouseholdWorkspace.vue`
- 변경:
  1. 마운트(1501~1507행) 순서를 바꾼다.
     ```js
     await Promise.all([loadMetadata(), loadEntryDateRange(), loadAggregatePreferences()])
     await loadCalendarData()
     if (shouldLoadStatisticsForTab()) await loadStatisticsData()
     ```
     나머지(`loadHouseholdTravelPlans()`, `resetEntryForm()`, `calendarReady.value = true` …)와 `try/catch/finally`는 그대로 둔다.
     - 근거: `loadEntryDateRange`는 마운트 중(`!calendarReady`) 통계 기준일만 바꾸고(2333행), 통계는 그 뒤에 읽는다. `loadCalendarData`는 집계 선호(`loadAggregatePreferences`) 뒤에 실행된다.
  2. `refreshLedgerViews`(5014~5026행)를 다음 형태로 바꾼다.
     ```js
     const dateRange = loadEntryDateRange()
     const tasks = [loadCalendarData()]
     if (forceStatistics || shouldLoadStatisticsForTab()) tasks.push(dateRange.then(() => loadStatisticsData()))
     else tasks.push(dateRange)
     await Promise.all(tasks)
     ```
     검색·휴지통 재조회(5020~5025행)는 그대로 그 뒤에 둔다.
- 제약:
  - 오류 처리 의미(하나가 실패하면 `catch`로 가서 피드백 표시)를 유지한다. `Promise.all`이 처음 실패한 오류를 던지므로 지금과 같다.
  - `removeEntry`의 낙관적 갱신과 그 뒤 백그라운드 `refreshLedgerViews()`(5360행)는 지우지 않는다. 서버 상태와 다시 맞추는 용도다.
  - `loadMetadata`의 4개 요청(2121~2127행)은 합치지 않는다(활성/비활성 목록의 의미 확인이 필요하다).
- 테스트·검증:
  - `cd frontend && npm run build`
  - e2e: `ledger-transaction-manage-flow.spec.js`, `ledger-split-payments.spec.js`, `ledger-adjustments.spec.js`, `calendar-first-account-defaults.spec.js`, `calendar-aggregate-interaction.spec.js`, `calendar-cross-device.spec.js`
  - 수동: 통계 탭에서 기간 `ALL`로 새로고침했을 때 기준일이 최신 거래일로 잡히는지(2333행 동작) 확인한다.

---

## 6. 단계별 완료 확인

| 단계 | 명령 | 통과 기준 |
|---|---|---|
| 1단계 끝 | `./gradlew test --tests 'com.playdata.calen.common.schema.*'` → `'...recordmap.*'` → `'...calendar.*'` → `'...drive.service.*'` → `'...ledger.service.*'`(하나씩) / `scripts/verify-db-migrations.ps1` | 전부 통과, `git status`에 지정 파일만 변경 |
| 2단계 끝 | `'...travel.service.ExchangeRateServiceTest'` → `'...account.service.*'` → `'...common.cache.*'` → `'...ledger.service.*'` | 전부 통과 |
| 3단계 끝 | `npm run test:unit`, `npm run build`, 5장의 e2e 목록 | 통과. 빌드 표에서 `HouseholdWorkspace` < 200 kB, `mediaPreview` < 10 kB, 대시보드 청크의 정적 의존에 leaflet 없음 |

마지막으로 `git status --short`와 `git diff --stat`으로 0장 8번의 수정 금지 파일이 바뀌지 않았는지 확인한다. 스테이징·커밋은 하지 않는다.

---

## 7. 문서 갱신

### 7.1 `docs/db_migration_strategy.md`

`scripts/verify-db-migrations.ps1`(246~258행)은 추적된 마이그레이션마다 두 섹션에 행이 있는지 검사한다. 새 파일이 커밋되면 행이 없을 때 실패하므로 지금 함께 추가한다.

- `## Current Migration Inventory` 표에 추가: `` | `V20261122_063__performance_indexes.sql` | Adds entity-matched record-map and ledger lookup indexes with IF NOT EXISTS for ddl-auto and baselined databases. | ``
- `## Migration Operational Evidence` 표에 추가: `` | `V20261122_063__performance_indexes.sql` | H2 migration and schema tests passed; MariaDB/Flyway rehearsal remains required. Online index creation on large tables should run in a quiet window. | Indexes only; drop the new indexes to roll back. No data change. | Adds indexes; no legacy updater retired. | ``
  - 열 개수는 바로 위 `V20261114_062` 행과 똑같이 맞춘다.

### 7.2 이 문서

구현이 끝나면 각 항목 제목 옆에 `(완료 YYYY-MM-DD)`만 덧붙인다. 측정 수치는 바꾸지 않는다.

---

## 8. 참고

### 8.1 측정 재현 방법(선택)

- M5 재현: `docs`에 넣지 말고 임시 `@DataJpaTest`를 만들어 `information_schema.index_columns`를 덤프한다. 마이그레이션 파일의 `CREATE INDEX`·인라인 `INDEX` 이름과 비교하고, 끝나면 그 테스트를 지운다.
- M3·M4 재현: `frontend`의 `src`, `public`, `index.html`, `package.json`, `vite.config.js`를 임시 폴더에 복사한다. `node_modules`는 링크(Windows는 `New-Item -ItemType Junction`)로 연결하고, 복사본만 고친 뒤 `npx vite build`를 실행한다.

### 8.2 보류한 항목과 이유

| 항목 | 근거 | 보류 이유 |
|---|---|---|
| 엔트리 CSS 1,019 kB(`style.css` 1.1 MB, 4,936개 `!important`) | M1 | `style.css`에 사용자의 미커밋 변경이 있다. 전역 CSS 특이도 규칙(다른 workplan의 `:global(html body …)` 관례)에 의존하는 화면이 많아 기능별로 쪼개면 회귀 위험이 크다. 별도 작업으로 다룬다 |
| 통합 달력의 창 포커스 재조회(`IntegratedCalendarWorkspace.vue` 179~191행, 1초 간격) | 포커스마다 월 5개 + 일 5개 + 공유·지도 선택 수에 비례하는 요청. 모두 `no-store`라 병합되지 않음 | 간격을 늘리면 데이터 신선도 정책이 바뀐다. 소유자 결정이 필요하다(예: 60초) |
| 여행 허브 `refreshTravelData` 3단 전체 재조회(20곳), `TravelWorkspace`의 3중 재조회, 기록 지도 `load()` 전체 재조회 | 프론트 감사 | 로컬 상태 패치로 바꾸려면 큰 리팩터링이 필요하고 동작 회귀 위험이 높다. 다음 차수 |
| 포토 프레임: 드라이브 5,000개 조회, hero 타일에 원본 이미지 | `MainDashboardWorkspace.vue` 50·732행 | 화질과 표시 범위에 대한 제품 결정이 필요하다. 대안 후보: 타일은 960 `detail` 변형 + 실패 시 원본 |
| `PetCompanion` 1 MB PNG 스프라이트, 숨은 탭에서도 도는 12fps 타이머 | `PetCompanion.vue` 4·221·523~529행 | 이미지 변환 도구가 필요하다. 영향은 로그인 사용자의 바이트와 CPU로 낮음~중간. 별도 작업 |
| 공개 여행(atlas) 개요·클러스터 페이지마다 전체 재클러스터, `travel:public-trips:overview` 키는 지우기만 하고 쓰지 않음 | `ReadTravelAtlasService.java` 36~73행, `TravelSummaryCache.java` 17·32·37~39행 | 응답이 열람자별(공개 + 내가 받은 공유)이라 전역 키 하나로 캐시할 수 없다. 캐시 설계가 먼저 필요하다 |
| 여행 요약 캐시 적중 경로의 DB 확인(`requireActiveOwner`)과 전역 `Semaphore(128)` | `ReadOwnedTravelPlansService.java` 19·28행, `SingleFlight.java` 16·24행 | 권한 확인은 유지해야 한다(규칙 2). 세마포어는 1차 작업에서 의도한 보호 장치다 |
| 공유 세션의 요청마다 Redis 왕복 3회 | `SharedSessionConfig.java` | 선택 기능이고 기본값이 꺼져 있다 |
| Hikari 풀 크기·누수 감지 명시 | `application.yml`에 설정 없음(기본 최대 10) | 실제 점유 시간 메트릭(`hikaricp_*`, Prometheus 노출 중)을 보고 정해야 한다. `application.yml`·env 계약 파일에 사용자의 미커밋 변경이 있다 |
| 기록 지도·달력 공유 사진의 `no-store` → `private, max-age` | `RecordMapController.java` 135행 | 철회·탈퇴 뒤 브라우저 캐시에 남는 문제라 개인정보 정책 결정이 필요하다 |
| 드라이브 관리자 통계의 파일별 MinIO HEAD(트랜잭션 안) | `DriveAdminService.java` 114~119행 | 무결성 검사를 비동기 작업으로 떼어내는 설계가 필요하다. 관리자 전용이다 |
| 환율 호출을 트랜잭션 밖으로 이동, single-flight, 실패 결과 캐시 | C1 | C-1의 제한 시간만으로 최악의 경우를 막는다. 트랜잭션 경계를 옮기면 가계부 쓰기 흐름이 바뀐다 |
| 역지오코딩 실패 결과 캐시, 워커 스레드의 동기 Redis 재연결 | `TravelReverseGeocodeService.java` 115·191~197·218~268행 | 워커 스레드에서만 일어나 HTTP 스레드를 막지 않는다. 영향이 낮다 |
| `RedisStateService`의 INCR→EXPIRE, GET→DEL 비원자성 | 156~159·138~142행 | 성능이 아니라 정합성 문제다 |
| 마이그레이션에만 있는 유니크 인덱스 3개 | 1.3절 | 정합성 문제다. 중복 데이터가 있으면 생성이 실패하므로 데이터 점검이 먼저다 |
| `travel_expense_records (plan_id, expense_date)` 인덱스 | 기존 `(plan_id, record_type, expense_date, id)`의 앞부분 `plan_id`로 동작 | 계획당 행 수가 적어 효과가 측정되지 않았다 |
| 1.3절 저트래픽 인덱스(`calendar_map_grants`, `drive_item_versions`, `persistent_logins`, 임베딩, 복구 제한) | M5 | 호출 빈도가 낮다. 필요하면 DB-1과 같은 방식으로 추가한다 |
| `Intl.NumberFormat` 매 호출 생성, 계산값 배열에 대한 `deep: true` 감시자, 큰 데이터의 `shallowRef` 전환 | 프론트 감사(`HouseholdTransactionSheet.vue` 201~203행 등) | 각각의 효과가 작다. 묶어서 다음 차수 |
| 대시보드와 별도로 하는 `fetchCompare`(주·월) 요청 | `MainDashboardWorkspace.vue` | 응답 모양 변경(API 합치기)이 필요하다 |

### 8.3 이미 처리되어 재제안하지 않는 것

- 1차 성능 작업(`docs/performance_improvement_progress.md` 표 전체): AI 큐, 엑셀 AI, 이미지 AI, ZIP 스트리밍, 드라이브 SQL 집계, 사진 클러스터 격자 계산과 워커, 지도 bbox·페이지, 역지오코딩 워커·병합·공유 간격, 대시보드 일별 집계 재사용, 캐시 single-flight와 커밋 후 무효화, 로컬 캐시 크기 제한, `RedisCacheService` 비동기 재연결, 프론트 동시 GET 병합.
- 여행 가져오기 대화상자 사진 그룹 동시 업로드(`87d2eecc`).
- 썸네일: 클라이언트 사전 생성, 서버 요청 시 생성의 `Semaphore(2)` 제한(`TravelMediaStorageService.java` 68·356행), 백필 작업, `.thumbs-v2`.
- 모든 최상위 화면의 `defineAsyncComponent`(`App.vue` 21~35행), leaflet 별도 청크, 서비스워커 지연 등록, nginx gzip과 `/assets` 1년 캐시.
- 통합 달력 프론트 저장소(키·시퀀스 가드, 이벤트 저장 시 이벤트만 재조회), 가계부·검색 입력 디바운스, 검색·휴지통 서버 페이지, 숨은 탭의 폴링 중지, 사진 그리드의 썸네일 변형과 `loading="lazy"`, 지도 클러스터의 화면 범위 처리와 rAF 디바운스.

---

## 9. 확인하지 못한 것 (사용자 확인 필요)

1. **운영 DB의 실제 인덱스.** 운영 Flyway 설정이 정말 꺼져 있는지, `record_map_*` 테이블을 Hibernate와 Flyway 중 무엇이 만들었는지 확인하지 않았다. 일부 테이블(`withdrawal_recovery_request_limits`, `ledger_embedding_sync_jobs`)은 엔티티 없이 마이그레이션으로만 생기므로 운영에서 마이그레이션을 따로 적용했을 가능성도 있다.
   - 사용자가 읽기 전용으로 확인: `SHOW INDEX FROM record_map_visits; SHOW INDEX FROM record_map_places; SHOW INDEX FROM ledger_entries;`
   - 그리고 `EXPLAIN SELECT visit_date, COUNT(id) FROM record_map_visits WHERE owner_id = ? AND visit_date BETWEEN ? AND ? GROUP BY visit_date;`
   - DB-1은 인덱스가 이미 있어도 아무 일도 하지 않으므로 안전하다.
2. 운영 테이블 행 수, 실제 쿼리 시간, Hikari 점유율, Redis 지연. 부하 테스트와 운영 메트릭은 보지 않았다.
3. DB-4의 바이트 감소율(240 vs 480 버킷)은 화소 수로 계산한 추정이다. 실제 JPEG 크기는 사진마다 다르다.
4. F-1·F-2의 크기 감소는 빌드 측정값이다. 실제 첫 화면 시간(LCP)은 측정하지 않았다.
5. M7·M8은 Node(V8)에서 합성 데이터로 잰 값이다. 브라우저와 실제 데이터의 수치는 다를 수 있다.
6. C-2의 Lettuce 연결 대기 시간은 `RedisURI.withTimeout(3s)`이 연결에도 적용된다는 Lettuce 동작을 근거로 한 것이다. Redis를 실제로 끊어 보지는 않았다.
