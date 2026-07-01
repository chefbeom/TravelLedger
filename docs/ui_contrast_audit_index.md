# UI 대비 검수 인덱스

이 문서는 UI 대비 개선과 검수에 필요한 문서 및 스크립트의 진입점이다.
목표는 기본 테마, 다크 테마, 모바일, 데스크톱에서 글자와 배경이 겹쳐 보이지 않는지 확인하는 것이다.

## 산출물

| 파일 | 용도 |
| --- | --- |
| `docs/ui_contrast_readability_checklist.md` | 대비 회귀 방지 체크리스트 |
| `docs/ui_contrast_page_inventory.md` | 검수 대상 화면 인벤토리 |
| `docs/ui_contrast_audit_matrix.md` | 화면별 테마/viewport 점검 매트릭스 |
| `docs/ui_contrast_manual_audit_runbook.md` | 실제 브라우저 수동 검수 절차 |
| `docs/ui_contrast_audit_evidence_template.md` | 검수 증거 기록 템플릿 |
| `docs/ui_contrast_audit_evidence_current.md` | 현재 검수 상태 기록 |
| `scripts/check-ui-contrast-risk.ps1` | CSS 대비 위험 패턴 보조 점검 |
| `scripts/init-ui-contrast-audit-evidence.ps1` | 검수 결과 파일 초기화 |

## 권장 실행 순서

1. 프론트엔드 빌드를 실행한다.
2. `scripts/check-ui-contrast-risk.ps1`로 위험 CSS 패턴을 점검한다.
3. `scripts/init-ui-contrast-audit-evidence.ps1`로 검수 결과 파일을 생성한다.
4. `docs/ui_contrast_page_inventory.md`의 화면을 순서대로 연다.
5. 각 화면을 기본 테마/다크 테마, 데스크톱/모바일에서 확인한다.
6. 결과를 생성된 evidence 파일과 `docs/ui_contrast_audit_matrix.md`에 기록한다.
7. 문제가 있으면 스크린샷, 조건, 위치, 수정 파일을 남긴다.
8. 수정 후 같은 조건으로 재검수한다.

## 완료 조건

- 프론트엔드 빌드가 통과해야 한다.
- 주요 화면이 기본 테마와 다크 테마에서 모두 통과해야 한다.
- 주요 화면이 모바일과 데스크톱에서 모두 통과해야 한다.
- `수정 필요`와 `미확인` 항목이 남아 있으면 완료가 아니다.
- 실제 브라우저 렌더링 증거 없이 완료로 판단하지 않는다.

## PowerShell 예시

```powershell
# 위험 CSS 패턴 확인
.\scripts\check-ui-contrast-risk.ps1

# high/medium 위험을 실패로 처리
.\scripts\check-ui-contrast-risk.ps1 -FailOnRisk

# 검수 결과 파일 초기화
.\scripts\init-ui-contrast-audit-evidence.ps1 `
  -OutputPath "docs/ui_contrast_audit_evidence_run.md" `
  -Auditor "name" `
  -FrontendUrl "http://localhost:5173" `
  -Browser "Chrome"
```

## 주의

이 인덱스와 스크립트는 실제 렌더링 검증을 대체하지 않는다.
CSS 위험 패턴 점검은 보조 수단이며, 최종 판단은 브라우저에서 화면을 직접 확인한 증거로 한다.