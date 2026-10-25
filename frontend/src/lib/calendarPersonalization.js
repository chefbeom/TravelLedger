export const PERSONALIZATION_SECTIONS = [
  { id: 'dashboard', label: '대시보드 구성', description: '표시·순서·배치와 크기' },
  { id: 'calendar', label: '달력 표시', description: '기본 보기와 거래 시트' },
  { id: 'widgets', label: '집계 위젯', description: '집계·목표·그래프·표시' },
  { id: 'input', label: '거래 입력', description: '빠른 입력과 이미지 분석' },
  { id: 'defaults', label: '기본값 관리', description: '적용 범위를 나누어 복원' },
]
export const clonePersonalization = value => JSON.parse(JSON.stringify(value))
export const personalizationChanged = (left, right) => JSON.stringify(left) !== JSON.stringify(right)

// Pure collision resolver: explicit edits persist its result; responsive display
// projections use a copy and never rewrite the stored layout on read.
export function packDashboardPanels(panels, changedId) {
  const occupied = new Set(), result = new Map()
  const ordered = [...panels].sort((a, b) => (a.id === changedId ? -1 : b.id === changedId ? 1 : a.y - b.y || a.x - b.x))
  const free = (x, y, w, h) => {
    for (let row = y; row < y + h; row++) for (let column = x; column < x + w; column++) if (occupied.has(`${column}:${row}`)) return false
    return true
  }
  for (const panel of ordered) {
    const w = Math.max(1, Math.min(9, Math.round(Number(panel.w) || 1)))
    const h = Math.max(1, Math.min(12, Math.round(Number(panel.h) || 1)))
    const x = Math.max(0, Math.min(9 - w, Math.round(Number(panel.x) || 0)))
    let y = Math.max(0, Math.min(200, Math.round(Number(panel.y) || 0)))
    while (y <= 200 && !free(x, y, w, h)) y++
    // An explicit move at the last allowed row must not push a neighbouring panel out of bounds.
    if (y > 200) {
      y = 0
      while (!free(x, y, w, h)) y++
    }
    for (let row = y; row < y + h; row++) for (let column = x; column < x + w; column++) occupied.add(`${column}:${row}`)
    result.set(panel.id, { ...panel, x, y, w, h })
  }
  return panels.map(panel => result.get(panel.id))
}

export function isPreferenceNavigationAllowed() {
  if (typeof window === 'undefined') return true
  return window.dispatchEvent(new CustomEvent('calen:before-preference-navigation', { cancelable: true }))
}
