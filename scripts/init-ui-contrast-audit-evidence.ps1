param(
    [string]$OutputPath = "docs/ui_contrast_audit_evidence_run.md",
    [string]$Auditor = "",
    [string]$FrontendUrl = "",
    [string]$Browser = "Chrome"
)

$ErrorActionPreference = "Stop"

$now = Get-Date -Format "yyyy-MM-dd HH:mm:ss K"
$commit = "unknown"
try {
    $commit = (git rev-parse --short HEAD 2>$null).Trim()
    if (-not $commit) { $commit = "unknown" }
} catch {
    $commit = "unknown"
}

$rows = @(
    @("인증", "로그인"),
    @("인증", "회원가입/초대 링크"),
    @("인증", "관리자 추가 인증"),
    @("메인", "런처/대시보드"),
    @("가계부", "월 달력"),
    @("가계부", "거래 시트"),
    @("가계부", "금액 입력 패널"),
    @("가계부", "집계/통계 카드"),
    @("통계/AI", "통계 차트"),
    @("통계/AI", "AI 분석 패널"),
    @("통계/AI", "AI 결과 모달"),
    @("통계/AI", "PDF/인쇄 화면"),
    @("여행", "여행 허브"),
    @("여행", "여행 지도"),
    @("여행", "사진/썸네일 오버레이"),
    @("여행", "여행 공유"),
    @("드라이브", "파일 목록"),
    @("드라이브", "상세 패널"),
    @("드라이브", "컨텍스트 메뉴/드로어"),
    @("관리자", "관리 요약 대시보드"),
    @("관리자", "AI 및 서버 제어판"),
    @("관리자", "문의 관리"),
    @("관리자", "접근/사용자 관리"),
    @("알림", "알림 센터"),
    @("공통", "모달/토스트/팝업"),
    @("공통", "로딩/스켈레톤"),
    @("공통", "접근성 상태")
)

$table = New-Object System.Text.StringBuilder
[void]$table.AppendLine("| 영역 | 화면 | 데스크톱 기본 | 데스크톱 다크 | 모바일 기본 | 모바일 다크 | 증거/메모 |")
[void]$table.AppendLine("| --- | --- | --- | --- | --- | --- | --- |")
foreach ($row in $rows) {
    [void]$table.AppendLine("| $($row[0]) | $($row[1]) | 미확인 | 미확인 | 미확인 | 미확인 |  |")
}

$content = @"
# UI 대비 검수 실행 증거

## 메타 정보

- 검수 일시: $now
- 검수자: $Auditor
- 브랜치/커밋: $commit
- 브라우저: $Browser
- 프론트엔드 URL: $FrontendUrl
- 데스크톱 viewport: 1440 x 900
- 모바일 viewport: 390 x 844
- 기준 문서: docs/ui_contrast_page_inventory.md
- 검수 절차: docs/ui_contrast_manual_audit_runbook.md

## 결과 요약

| 상태 | 개수 |
| --- | ---: |
| 통과 | 0 |
| 수정 필요 | 0 |
| 확인 불가 | 0 |
| 미확인 | $($rows.Count * 4) |

## 화면별 검수 결과

$($table.ToString())

## 문제 기록

문제가 발견되면 아래 형식을 복사해 추가한다.

````text
문제 ID:
화면:
조건:
위치:
문제 설명:
기대 결과:
증거 파일:
수정 파일:
재검수 결과:
````

## 완료 판정

- [ ] 프론트 빌드 통과
- [ ] 기본 테마 데스크톱 전체 통과
- [ ] 다크 테마 데스크톱 전체 통과
- [ ] 기본 테마 모바일 전체 통과
- [ ] 다크 테마 모바일 전체 통과
- [ ] 수정 필요 항목 없음
- [ ] 미확인 항목 없음
"@

$fullPath = Join-Path (Resolve-Path ".") $OutputPath
$parent = Split-Path -Parent $fullPath
if ($parent -and -not (Test-Path $parent)) {
    New-Item -ItemType Directory -Path $parent | Out-Null
}
[System.IO.File]::WriteAllText($fullPath, $content, [System.Text.UTF8Encoding]::new($false))
Write-Host "Created UI contrast audit evidence file: $fullPath"