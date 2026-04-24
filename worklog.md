# Worklog

이 문서는 사용자의 요청, 요청 분석, 실제 수행 내용, 구현 결과, 검증 기록을 남기는 작업 로그입니다.

## 기록 원칙

- 코드 작성 또는 파일 변경 작업을 수행할 때마다 새 항목을 추가합니다.
- 사용자의 원 요청 또는 요약을 먼저 기록합니다.
- 요청을 어떻게 해석했는지, 어떤 방식으로 실행했는지, 실제로 무엇이 구현되었는지 구분해서 기록합니다.
- 검증한 명령, 확인한 파일, 검증 결과를 남깁니다.
- 검증하지 못한 항목이 있으면 이유를 함께 남깁니다.

## 기록 양식

```md
### YYYY-MM-DD - 작업 제목

- 사용자 명령:
- 요청 분석:
- 실행 내용:
- 구현 내용:
- 검증 기록:
- 결과:
- 후속 메모:
```

## 작업 기록

### 2026-04-24 - Main dashboard reference design skin

- User request: Apply only the design style from the attached Figma reference image.
- Request analysis: Keep existing main dashboard palette data, API reads, GridStack layout, navigation, and user storage behavior unchanged, and apply only the visual language from the reference: light gray app canvas, white cards, lime/lavender accents, compact rounded panels, and a soft top navigation bar.
- Actions taken: Checked `codingconvention.md`, reviewed `MainDashboardWorkspace.vue` and `style.css`, and confirmed unrelated dirty files are outside this work.
- Implementation: Added palette type/metric CSS hooks to `MainDashboardWorkspace.vue` and layered visual-only CSS overrides for the reference-style dashboard surface, cards, metric panels, charts, floating settings button, and tools panel. Updated `main-shell--standalone` and top navigation styling in `style.css` to match the same light dashboard frame.
- Verification: Ran `cmd /c npm run build` in `frontend` successfully. Verified no TypeScript SFC/script or `.ts`/`.tsx` files with `rg -n 'lang="ts"|lang=''ts''' frontend/src` and `Get-ChildItem -Path frontend/src -Recurse -Include *.ts,*.tsx`. Ran `git diff --check -- frontend/src/components/MainDashboardWorkspace.vue frontend/src/style.css` with no whitespace errors.
- Result: The main dashboard keeps its current functionality while adopting the attached reference's light HR-dashboard visual tone.
- Follow-up note: A browser pass with real connected user data can tune exact visual balance if needed.

### 2026-04-24 - 메인 팔레트 확장 기능 추가

- 사용자 명령: 팔레트 기능은 더 확장적이어야 하며, 사진을 불러와 액자처럼 표시하거나 드라이브 용량 확인, 최근 저장 파일, 빠른 접근 단축기능처럼 동작할 수 있어야 한다는 요청.
- 요청 분석: 메인 대시보드 팔레트를 단순 요약 정보판이 아니라 기능 단위 위젯으로 확장하는 작업으로 해석했습니다. 백엔드 신규 API는 만들지 않고 기존 드라이브/여행/가계부 API 응답에서 가능한 범위로 구현했습니다.
- 실행 내용: `codingconvention.md`를 확인하고 드라이브 썸네일/다운로드 경로, 여행 사진 데이터 사용 방식, 기존 메인 팔레트 구조를 확인했습니다.
- 구현 내용: `사진 액자`, `드라이브 용량`, `최근 저장 파일`, `빠른 단축 기능` 팔레트를 추가했습니다. 드라이브 최근 파일 API를 추가로 읽어 이미지 파일은 사진 액자 후보로 사용하고, 여행 포트폴리오에 사진 URL이 포함될 경우 함께 액자 후보로 모으도록 했습니다. 드라이브 용량 팔레트는 사용량과 가능한 경우 전체 용량 대비 퍼센트를 표시하고, 최근 파일 팔레트는 썸네일/파일명/용량과 열기 링크를 제공합니다. 빠른 단축 기능 팔레트는 가계부, 여행, 드라이브, 메인 이동을 버튼으로 제공합니다.
- 검증 기록: `frontend`에서 `cmd /c npm run build`를 실행해 Vite 빌드 통과를 확인했습니다. `.ts`, `.tsx`, `lang="ts"`가 없는지 확인했고, `git diff --check -- frontend/src/components/MainDashboardWorkspace.vue`에서 공백 오류가 없음을 확인했습니다.
- 결과: 메인 팔레트가 사진 표시와 드라이브 운영 정보, 최근 파일 접근, 기능 단축 버튼까지 포함하는 확장형 대시보드 구조로 넓어졌습니다.
- 후속 메모: 백엔드 연결 후 실제 업로드 사진/최근 파일 응답 필드에 따라 썸네일 표시를 브라우저에서 추가 확인해야 합니다. Vite 번들 크기 경고는 기존과 동일하게 표시됩니다.

### 2026-04-24 - 메인 팔레트 크기 선택 제거 및 고정 크기 적용

- 사용자 명령: 각 팔레트마다 내용에 따라 필요한 크기가 다르므로 알맞게 조정하고, `2x2` 같은 크기 선택 방식은 필요 없으니 고정 크기로 가자는 요청.
- 요청 분석: 메인 대시보드 팔레트에서 사용자가 크기를 직접 선택하는 UI를 제거하고, 팔레트 종류별로 내용에 맞는 고정 그리드 크기를 강제하는 작업으로 해석했습니다.
- 실행 내용: `codingconvention.md`를 확인하고 `MainDashboardWorkspace.vue`의 팔레트 템플릿, 저장 키, 크기 변경 함수, 편집 컨트롤을 확인했습니다.
- 구현 내용: 메인 대시보드 저장 버전을 `v3`로 올려 기존 저장 크기를 새 고정 크기 체계로 보정하게 했습니다. 팔레트 타입별 고정 크기 매핑을 추가하고, 저장된 크기나 드래그 스냅샷의 크기 값도 로드/저장 시 고정 크기로 재정규화되도록 했습니다. 편집 모드의 크기 select를 제거해 숨김/삭제만 보이도록 했습니다.
- 검증 기록: `frontend`에서 `cmd /c npm run build`를 실행해 Vite 빌드 통과를 확인했습니다. `.ts`, `.tsx`, `lang="ts"`가 없는지 확인했고, `git diff --check -- frontend/src/components/MainDashboardWorkspace.vue`에서 공백 오류가 없음을 확인했습니다.
- 결과: 메인 대시보드 팔레트는 내용별 고정 크기로 배치되며, 사용자는 크기 단위를 직접 선택하지 않습니다.
- 후속 메모: Vite 번들 크기 경고는 기존과 동일하게 표시됩니다.

### 2026-04-24 - 헤더 주요 기능 네비게이션 추가

- 사용자 명령: 최초 페이지에서 다른 기능으로 접속할 수 있는 버튼들이 보이지 않으므로 헤더에 네비게이션을 추가해달라는 요청.
- 요청 분석: 메인 대시보드 팔레트 배치와 무관하게 가계부, 여행, 드라이브 등 주요 기능으로 이동할 수 있는 전역 진입점이 필요하다고 해석했습니다.
- 실행 내용: `codingconvention.md`를 확인하고 `frontend/src/App.vue`의 상단 헤더와 라우팅 함수, `frontend/src/style.css`의 topbar 스타일을 확인했습니다.
- 구현 내용: 헤더에 `메인`, `가계부`, `여행`, `드라이브`, 관리자 계정일 때 `관리자`가 표시되는 네비게이션을 추가했습니다. 기존 `navigate` 함수를 그대로 사용하고, 현재 활성 라우트는 강조 표시되도록 했습니다. 모바일 폭에서는 줄바꿈되는 형태로 유지했습니다.
- 검증 기록: `cmd /c npm run build`로 프론트 빌드 통과를 확인했습니다. `.ts`, `.tsx`, `lang="ts"`가 없는지 확인했고, `git diff --check`에서 공백 오류가 없음을 확인했습니다.
- 결과: 팔레트 상태와 상관없이 상단 헤더에서 주요 기능으로 바로 이동할 수 있습니다.
- 후속 메모: Vite 번들 크기 경고는 기존과 동일하게 표시됩니다.

### 2026-04-24 - 메인 대시보드 팔레트 구성 기능 추가

- 사용자 명령: 최초 페이지의 가계부, 여행, 드라이브 내용을 변경하고, 특히 가계부는 이번 주/이번 달 사용금액과 수입, 특정 결제수단 사용금액, 주간/월간 비교 그래프, 빠른 금액 입력 등을 사용자가 설정에서 여러 팔레트로 추가해 원하는 대로 구성할 수 있게 해달라는 요청.
- 요청 분석: 로그인 후 첫 화면인 메인 대시보드를 정적 요약 카드가 아니라 사용자별 커스터마이즈 가능한 팔레트 보드로 바꾸는 작업으로 해석했습니다. 기존 백엔드 API와 가계부 도메인 로직은 변경하지 않고, 기존 API를 읽거나 `createEntry`를 호출하는 프론트 진입 기능만 추가했습니다.
- 실행 내용: `codingconvention.md`를 확인하고 기존 `MainDashboardWorkspace.vue`, 가계부 통계/비교/입력 API 사용 방식을 확인했습니다.
- 구현 내용: `MainDashboardWorkspace.vue`를 메인 전용 GridStack 팔레트 대시보드로 개편했습니다. 사용자별 localStorage 저장 키를 사용해 팔레트 배치/숨김/복구/추가/삭제/크기 변경/초기화를 지원하고, 설정 플로팅 패널에서 여러 팔레트를 추가할 수 있게 했습니다. 기본 팔레트로 가계부 종합, 이번 주/이번 달 지출과 수입, 결제수단 사용금액 선택, 주간/월간 비교 그래프, 빠른 금액 입력, 여행 요약, 드라이브 요약, 기능 바로가기를 배치했습니다.
- 검증 기록: `frontend`에서 `cmd /c npm run build`를 실행해 Vite 빌드 통과를 확인했습니다. `rg`와 `Get-ChildItem`으로 `.ts`, `.tsx`, `lang="ts"`가 없는지 확인했습니다. `git diff --check -- frontend/src/components/MainDashboardWorkspace.vue`로 공백 오류가 없음을 확인했고, CRLF 변환 경고만 표시됐습니다.
- 결과: 최초 메인 페이지가 사용자 구성형 팔레트 대시보드로 동작하며, 가계부/여행/드라이브 요약과 빠른 금액 입력을 같은 화면에서 구성할 수 있습니다.
- 후속 메모: 백엔드 연결이 불가한 현재 상태에서는 요약/입력 API 호출이 실패할 수 있으나, 화면 구성과 빌드는 정상입니다. 실제 데이터 표시와 빠른 입력 저장은 백엔드 연결 후 브라우저에서 추가 확인이 필요합니다.

### 2026-04-24 - 백엔드 미연결 상태용 로그인 가드 임시 해제

- 사용자 명령: 현재 백엔드 연결이 불가해 로그인하지 않으면 화면에 접속할 수 없으므로 네비게이션 가드를 풀고, 나중에 복구 요청 시 다시 복구할 수 있게 해달라는 요청.
- 요청 분석: 인증 API가 실패해도 프론트 화면을 확인할 수 있도록 임시 로컬 사용자로 진입시키는 작업으로 해석했습니다. 실제 인증 로직을 삭제하지 않고 복구가 쉬운 플래그 방식으로 제한했습니다.
- 실행 내용: `codingconvention.md`를 확인하고 `frontend/src/App.vue`의 `restoreSession`, 로그인 분기, 로그아웃 흐름을 확인했습니다.
- 구현 내용: `AUTH_GUARD_BYPASS_ENABLED`와 로컬 미리보기 사용자 객체를 추가했습니다. `fetchCurrentUser` 실패 시 임시 사용자로 진입하게 했고, 임시 우회 상태에서 로그아웃 버튼을 눌러도 다시 로그인 화면에 갇히지 않도록 처리했습니다.
- 검증 기록: `frontend`에서 `cmd /c npm run build`로 프론트 빌드 통과 여부를 확인했습니다.
- 결과: 백엔드가 연결되지 않아도 프론트 주요 화면으로 진입할 수 있습니다. 복구 시 `AUTH_GUARD_BYPASS_ENABLED` 우회 코드를 제거하거나 `false`로 되돌리면 기존 로그인 흐름을 다시 사용할 수 있습니다.
- 후속 메모: 이 변경은 임시 개발용 우회이며 배포 전에 반드시 복구해야 합니다.

### 2026-04-24 - 가계부 팔레트 대시보드 및 통합 대시보드 개편

- 사용자 명령: 기존 가계부/여행/드라이브 기능 로직과 API를 변경하지 않고, 가계부 첫 화면을 팔레트 기반 대시보드로 개편하고 로그인 후 기능 선택 화면을 전체 종합 대시보드로 교체하라는 요청.
- 요청 분석: 백엔드와 기존 도메인 로직은 유지하고, 프론트 진입 화면과 디자인 계층만 추가해야 하는 작업으로 해석했습니다. TypeScript 도입 금지, 사용자별 localStorage 저장, GridStack 9열 팔레트 편집, 기존 가계부 탭 유지, 작업 후 검증 및 커밋 조건을 적용했습니다.
- 실행 내용: `codingconvention.md`와 기존 `HouseholdWorkspace`, `App.vue`, API 호출 구조를 확인했습니다. `pinia`, `gridstack` 의존성을 추가하고, Pinia를 앱에 연결했습니다.
- 구현 내용: `frontend/src/features/palette` 아래 팔레트 컨테이너, GridStack 그리드, 팔레트 공통 셸, KPI/월달력 팔레트, 레지스트리, 레이아웃/스토리지 유틸을 추가했습니다. `frontend/src/stores/useDashboardPaletteStore.js`에 프리셋, 편집 모드, 추가/숨김/복구/삭제/크기 변경/레이아웃 저장 액션을 구현했습니다. `HouseholdWorkspace` 기본 탭을 `dashboard`로 바꾸고 기존 달력/통계/검색/휴지통/인사이트/비교/입출력/분류관리 탭은 유지했습니다. `MainDashboardWorkspace`를 추가해 가계부/여행/드라이브 요약과 기존 기능 이동 동작을 읽기 전용으로 연결했습니다.
- 검증 기록: `frontend`에서 `cmd /c npm run build`를 실행해 Vite 프로덕션 빌드 통과를 확인했습니다. `rg`와 `Get-ChildItem`으로 신규 `.ts`, `.tsx`, `lang="ts"`가 없는지 확인했습니다. `git diff --check`는 공백 오류 없이 통과했고, CRLF 변환 경고만 표시됐습니다. Vite 빌드에서 기존 규모성 JS 청크 경고가 있었고, `npm install` 후 npm audit 기준 high 취약점 2건 경고가 표시됐습니다.
- 결과: 가계부 팔레트 대시보드와 전체 종합 대시보드가 프론트에 추가됐고, 기존 기능 접근 경로는 유지했습니다.
- 후속 메모: 브라우저에서 실제 드래그/스왑 상호작용은 로그인 세션과 API 데이터가 있는 환경에서 추가 확인하는 것이 좋습니다. 이번 커밋에는 기존에 있던 `deploy/oci/scripts/provision-project-tenant.sh` 변경과 미추적 외부 폴더는 포함하지 않습니다.

### 2026-04-24 - 프론트 로그인 화면 문구 제거 커밋 및 푸시

- 사용자 명령: 현재 프론트를 수정했으니 푸시해달라는 요청.
- 요청 분석: 사용자가 수정한 프론트 변경을 확인하고, 관련 파일만 커밋한 뒤 원격 `main` 브랜치로 푸시하는 작업으로 해석했습니다.
- 실행 내용: `codingconvention.md`를 확인하고, `git status`, `git fetch origin`, `git diff -- frontend/src/App.vue`로 변경 범위와 원격 상태를 확인했습니다. 기존에 남아 있던 `deploy/oci/scripts/provision-project-tenant.sh` 수정과 미추적 파일들은 이번 커밋 대상에서 제외합니다.
- 구현 내용: `frontend/src/App.vue`에서 비로그인 화면의 안내 카피 블록이 제거된 변경을 커밋 대상으로 확인했습니다.
- 검증 기록: `frontend` 디렉터리에서 `cmd /c npm run build`를 실행해 Vite 프로덕션 빌드가 성공하는 것을 확인했습니다. 빌드 중 JS 청크 크기 경고는 있었지만 빌드는 정상 완료되었습니다.
- 결과: 프론트 변경과 작업 로그를 커밋하고 원격 `main`으로 푸시합니다.
- 후속 메모: 이전 문서 규칙 커밋 `c2e73a8`도 아직 원격에 없으므로 이번 푸시 때 함께 올라갑니다.

### 2026-04-24 - Git 커밋 및 푸시 작업 규칙 추가

- 사용자 명령: 작업이 하나 완료되면 커밋하고, 기능이 하나 완성됐을 때 커밋하며, 푸시는 사용자가 지시할 때만 하라는 요청.
- 요청 분석: Git 작업 흐름에 대한 프로젝트 작업 규칙을 `codingconvention.md`에 추가하고, 이번 변경 내역을 `worklog.md`에 기록해야 하는 요청으로 해석했습니다.
- 실행 내용: `codingconvention.md`와 `worklog.md`를 먼저 확인한 뒤, `codingconvention.md`에 Git 작업 규칙 섹션을 추가하고 프로젝트 컨벤션 기록에 날짜별 항목을 남겼습니다.
- 구현 내용: 완료된 작업/기능 단위 커밋, 커밋 전 변경 파일 확인, 무관한 변경 제외, 사용자 명시 지시 전 푸시 금지 규칙을 문서화했습니다.
- 검증 기록: 파일 수정 후 `Select-String`으로 추가된 규칙 문구를 확인했고, `git status --short -- codingconvention.md worklog.md`로 이번 커밋 대상 파일이 두 문서뿐임을 확인했습니다.
- 결과: Git 커밋/푸시 작업 규칙이 문서에 추가되었습니다.
- 후속 메모: 이번 문서 작업 완료 후 `codingconvention.md`와 `worklog.md`만 커밋하고 푸시는 수행하지 않습니다.

### 2026-04-24 - 코딩 컨벤션 및 작업 로그 문서 추가

- 사용자 명령: 앞으로 코딩 컨벤션 추가 요청은 `codingconvention.md`에 기록하고, 코드 작성 전 해당 내용을 확인/검증하도록 해달라는 요청. 또한 사용자의 명령, 분석, 실행 내용, 구현 내용, 검증 기록을 남기는 `worklog.md` 파일을 추가해달라는 요청.
- 요청 분석: 프로젝트 루트에 기준 문서 두 개를 추가해 이후 작업 절차를 명문화하는 문서 작업으로 해석했습니다.
- 실행 내용: 기존 파일 목록과 Git 상태를 확인하고, 루트에 `codingconvention.md`와 `worklog.md`를 새로 추가했습니다.
- 구현 내용: `codingconvention.md`에는 컨벤션 누적 규칙과 코드 작성 전 필수 확인 절차를 작성했습니다. `worklog.md`에는 기록 원칙, 기록 양식, 이번 작업의 최초 기록을 작성했습니다.
- 검증 기록: `rg --files`로 기존 파일 목록을 확인했고, `git status --short`로 작업 트리 상태를 확인했습니다. 문서 추가 후 `Get-Content codingconvention.md`, `Get-Content worklog.md`로 생성 여부와 내용을 다시 확인했습니다.
- 결과: 문서 기반 작업 절차가 추가되었습니다.
- 후속 메모: 앞으로 코드 변경 작업 전 `codingconvention.md`를 확인하고, 작업 완료 후 `worklog.md`에 기록합니다.
