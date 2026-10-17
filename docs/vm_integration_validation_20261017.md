# 격리 VM 배포·실연동 검증 — 2026-10-17

> 2026-10-18 후속: 아래에서 발견한 모바일 집계 제목 결함은 개발 코드에서 수정하고 로컬 브라우저 회귀 검증을 완료했다. 이 문서의 VM 결과는 당시 배포본의 기록이며, 수정본을 VM/운영에 재배포한 것은 아니다. 상세 내용은 마지막 후속 수정 항목을 참조한다.

## 결론

현재 작업물 WAR와 프런트 빌드를 `192.168.35.123` 테스트 VM에 배포했다. 실제 MariaDB·Redis·MinIO·SMTP를 사용한 **통합 시나리오 15건 통과**, 실제 Chromium의 **데스크톱·모바일 smoke 8건 통과**를 확인했다. 검증한 정상 요청에서 신규 백엔드 회귀는 발견하지 못했다.

다만 **모바일 집계 제목의 화면 결함 1건**이 남아 있다. AI 실호출은 사용자 요청으로 제외했다. 이 결과는 운영 서버 배포·운영 데이터 검증·모든 장애/입력의 무결함 보증이 아니다. 이번에는 앱 기능 코드를 수정하거나 커밋·푸시하지 않았다.

## 배포 대상과 격리

| 항목 | 실제 구성 |
| --- | --- |
| VM | Ubuntu 22.04.4 LTS / x86_64 / 2 CPU / 약 4GB RAM |
| 앱 주소 | `https://192.168.35.123:18443` |
| HTTP | `192.168.35.123:18080` → 위 HTTPS로 이동 |
| 오브젝트 공개 엔드포인트 | `https://192.168.35.123:19000` — 서명 URL PUT/GET 검증 |
| 프로젝트/디렉터리 | `calen-vm-integration` / `/home/test/calen-vm-integration-20261017` |
| DB | MariaDB 11.4.13 / `calen_vm` / REPEATABLE-READ / 테이블 56개·외래키 68개 |
| 캐시·상태 | Redis 캐시와 상태 컨테이너 분리, 실제 키 생성·PING·재시작 세션 복구 확인 |
| 파일 | VM 전용 MinIO, `calen-vm-cloud`·`calen-vm-workspace` 버킷 |
| 메일 | VM 내부 Mailpit SMTP, 관리 API는 VM loopback `127.0.0.1:18025`만 노출 |
| TLS | IP SAN을 포함한 30일 테스트 인증서. OS 전역 신뢰 설정은 변경하지 않음 |

운영 DB·Redis·MinIO·LLM 및 운영 환경변수를 복사/사용하지 않았다. DB·Redis·MinIO 콘솔 포트는 호스트에 공개하지 않았다. VM IP의 앱/서명 URL 포트와 loopback Mailpit만 구성했다. 애플리케이션용 임의 비밀값은 VM 디렉터리 700/환경 파일 600으로 보관하며 SSH 비밀번호는 파일로 저장하지 않았다.

공개 가입은 세 테스트 계정의 실제 이메일 인증 후 관리자 API로 다시 닫았다. 최종 계정 권한은 ADMIN 1명 / USER 2명이다. 드라이브 검증을 위한 수신자 임시 ADMIN 전환은 테스트 종료 시 USER로 복원했다.

## 검증한 실제 기능

1. SMTP 인증 메일 발송·토큰 확인·가입 활성화·독립 사용자 3명 생성.
2. 로그인, Secure 세션/remember cookie, CSRF 없는 변경 요청 거절, 잘못된 인증 거절.
3. 관리자 2차 인증·권한 분리·공개 가입 종료.
4. 거래 등록/수정, 소유자 격리, 명시 환율 외화, 검색, 수입·지출·잔액 및 달력/대시보드 집계.
5. 거래 소프트 삭제·휴지통·복구·검증용 기록 영구 삭제.
6. 선택 수신자 공유, 공유 메모·알림 수, 승인 후 독립 복사, 수정 공유 스냅샷과 원본 불변.
7. 여행 계획/예산/지출/방문 기록/수동 경로와 합계·소유권.
8. 원본과 pin/mini/preview/detail **4종 썸네일**의 실제 HTTPS 서명 PUT, CORS preflight, 완료 검증, 사진/썸네일 다운로드.
9. 실제 GPX multipart 업로드·경로 파일 저장.
10. 여행 승인 전/후 권한, 읽기 전용 공유, 공개 지도·사진, 공유 취소 후 거절.
11. 기록 지도 모음/장소/방문/사진/여행 가져오기, 검색, 방문 비공개/공개 분리, 링크 폐기.
12. 실제 MariaDB 데이터 및 Redis 캐시·상태 사용 확인.
13. **백엔드 재시작 후** Redis 세션 복구·DB 거래·MinIO 원본 유지, 세션 쿠키 없는 remember 복구 및 로그아웃.
14. 드라이브 실제 S3 업로드/완료/다운로드, 기존 ADMIN 전용 공유 정책, 수신·취소, 휴지통/복구/영구 삭제.
15. 저장 경로가 로컬 fallback이 아닌 MinIO object key인지 SQL 확인, 불완전 썸네일/잘못된 좌표/타인 삭제·드라이브 접근 거절.

브라우저는 실제 VM 주소·실제 API를 사용했다. API route mocking은 사용하지 않았다. 데스크톱 1920×1080 / 모바일 390×844에서 로그인 폼·PIN 키패드·새로고침 유지, 달력, 기록 지도, 여행 지도·사진 표시를 검사했다. 검사한 화면에서 문서 가로 넘침과 JS 예외·API 5xx는 없었다. 모든 버튼·모든 모달·실제 모바일 OS를 전수 검사한 것은 아니다.

최종 VM 상태 수집 시 백엔드 health는 healthy, OOM/restart-loop가 없었고, backend ERROR 행과 nginx 5xx는 각각 0이었다. 유휴 시점 백엔드 메모리는 약 602MiB였다. 이는 부하/성능 시험 결과가 아니다.

## 발견·해결한 배포/검증 준비 사항

- `minio/minio:latest` 및 기존 공식 Quay 이미지 조회가 실패했다. 검증 VM에서는 공식 소스 `RELEASE.2025-10-15T17-29-55Z`를 별도 이미지로 빌드했다. 커뮤니티 소스 배포 및 해당 보안 수정 릴리스의 근거는 [공식 README](https://github.com/minio/minio#source-only-distribution), [공식 릴리스](https://github.com/minio/minio/releases/tag/RELEASE.2025-10-15T17-29-55Z)다. 이 설정을 운영 이미지 변경 또는 보안 유지보수 완료로 간주하지 않는다.
- 빈 MinIO에 필요한 버킷이 없어 서명 URL 생성이 실패했다. VM 전용 버킷 2개를 생성하고 `TRAVEL_PRESIGNED_UPLOAD_ENABLED=true`를 적용해 해결했다. 앱 소스 변경은 없다.
- 실제 클라이언트와 같이 원본+4종 썸네일을 보내도록 검증 요청을 맞췄다. 기존 API는 불완전 썸네일을 400으로 거절한다.
- 승인 완료된 가계부를 다른 내용으로 재공유하는 요청은 기존 안전 정책상 거절된다. 별도의 미공유 원본으로 수정 공유를 검증했다. 드라이브 공유는 기존 ADMIN-only 계약에 맞춰 검증했다.
- 개별 `permanent=true` 삭제는 활성 거래 기준이며, 이미 휴지통인 거래의 개별 영구 삭제 계약이 아니다. 복구 후 해당 검증 기록만 영구 삭제하는 절차로 맞췄다.

첫 실행의 준비/절차 오류도 결과 JSON의 `previousAttempt`에 보존했다. 애플리케이션의 거절 조건을 완화해 테스트를 통과시킨 것이 아니다.

## 남은 화면 결함

**[P2] 모바일 달력의 ‘사용자 설정 집계’ 제목이 폭 약 20px로 압축되어 글자 단위로 세로 줄바꿈된다.** 같은 행의 수정/배치 버튼과 제목 배치를 후속 수정해야 한다. 백엔드 리팩토링 회귀로 판정한 것은 아니며, 배포·검증 요청 범위를 넘어 프런트 소스를 변경하지 않았다.

재현: 390×844 → 로그인 → 가계부 → 달력 가계부 → 집계 영역으로 스크롤. 증거: `artifacts/vm-integration-20261017/mobile-aggregate-header.png`, `browser-results.json`의 WARN.

## 미검증/제한

- AI·OCR·임베딩 실제 제공자 호출은 **사용자 요청으로 제외**. Kakao도 별도 제공자 설정/실호출은 검증하지 않았다.
- SMTP는 실제 로컬 SMTP/Mailpit 연동이며 외부 수신함 도착률/실제 메일 사업자 인증을 검증한 것이 아니다.
- 새 VM DB에서 기존 기본 경로(Hibernate update + legacy updater)로 기동했다. `DB_MIGRATION_ENABLED=false`다. **운영 DB 복제본 upgrade, Flyway-only 전체 체인·복원, 운영 최소 권한 DB 계정은 미검증**이다.
- 운영 규모 데이터·성능·동시 사용자 부하·대용량/네트워크 장애·모든 308개 API 조합은 전수 검사하지 않았다.
- 기존 원격 파일 삭제/DB 롤백의 비원자성 등 이전 감사의 기술 부채는 그대로다.
- 이 VM은 테스트용 자체 서명 인증서·컨테이너 구성이며 운영 보안 패치/하드닝 완료를 뜻하지 않는다. 서비스와 합성 데이터는 추가 확인을 위해 유지한다.

## 실행 증거와 재실행

- 소스 기준 HEAD `4aeb6d7d` / 브랜치 `main`의 **현재 미커밋 작업물을 포함한 빌드**다. 원격 main만 새로 clone한 배포가 아니다.
- WAR SHA-256(로컬과 실행 컨테이너 일치): `9E1CC0DC6B935AD31A1BD16CCAA55ECA403B877955B32CD6DD9DF71E915C0DC8`.
- 앞서 강제 재실행한 로컬 백엔드 회귀 213 suites / 1417 tests, 프런트 unit 27건·build·모의 공유 E2E 34건과 별개의 실연동 증거다.
- `artifacts/vm-integration-20261017/results.json`: VM 15개 통합 시나리오 및 재실행 이력.
- `artifacts/vm-integration-20261017/deployment-evidence.json`: 비밀값을 제외한 DB/컨테이너/WAR 해시·오류 집계.
- `artifacts/vm-integration-20261017/browser-results.json`, `*-login.png`, `*-ledger.png`, `*-record-map.png`, `*-travel-map.png`: 브라우저 결과·화면.
- 같은 디렉터리의 `compose.yaml`, `bootstrap_vm.py`, `provision_storage.py`, `integration_test.py`, `extra_checks.py`, `browser_test.mjs`, `snapshot_vm.py`가 배포/검증 절차다.
- 테스트 계정은 VM의 보호된 `test_accounts.json`에만 별도 보관한다. 브라우저 검증 중 개발 PC 임시 폴더로 가져온 복사본은 완료 후 삭제한다. 비밀번호/인증 토큰/서명 URL·세션 쿠키는 저장소·보고서에 포함하지 않는다.

기존 VM 데이터가 있는 상태에서 기본 `integration_test.py`를 반복 실행하면 신규 가입/초기 데이터 전제가 맞지 않는다. 재검증에는 기존 합성 계정을 쓰는 `--resume --storage`와 `--after-restart`, `extra_checks.py`를 사용한다. 어느 스크립트도 운영 서버를 대상으로 사용하지 않는다.

## 후속 수정 — 2026-10-18 모바일 집계 헤더

- 수정 전 390×844에서 제목 폭 **19.875px**로 문제를 재현했다. 모바일 버튼 영역의 `width: 100%`와 기존 헤더의 강제 가로 flex 배치가 충돌하는 것이 원인이었다.
- `frontend/src/styles/mobileUsability.css`에서 모바일 집계 헤더만 제목/버튼을 별도 행으로 배치하고, 버튼은 남은 폭에 맞춰 줄바꿈하도록 수정했다. 데스크톱 가로 배치와 거래·집계 로직은 유지했다.
- 로컬 Chromium에서 320×640, 360×740, 390×844, 412×915, 844×390, 390×440의 라이트/다크·기본/수정 모드, 버튼 경계·클릭·취소 및 기존 거래 버튼·화면 회전과 데스크톱 배치 **14개 테스트 통과**. 프런트 unit **27건 통과**, production build 성공.
- 재실행: `node node_modules/@playwright/test/cli.js test --config playwright.aggregate-header.config.js --grep "aggregate header|ledger controls|automatic mode"` (`frontend`에서 실행). API와 외부 요청은 모의 처리한 로컬 UI 회귀다.
- 증거: `frontend/artifacts/aggregate-header/report/index.html`, `frontend/artifacts/aggregate-header/results/`의 390×844 라이트/다크 헤더·수정 모드 캡처.
- 수정본의 커밋·푸시는 이후 별도 사용자 요청에 따라 진행한다. VM/운영 재배포는 포함하지 않는다.
- 2026-10-18 합의한 작업 기준: 로컬에서 코드 수정·정적 검사·빌드·단위 테스트를 수행하고, 테스트 배포와 실제 기능 검사는 요청받을 때 VM에서 수행한다. 커밋·푸시와 배포는 별도 단계로 취급한다.
