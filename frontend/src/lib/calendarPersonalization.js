export const PERSONALIZATION_SECTIONS = [
  { id: 'dashboard', label: '대시보드 구성', description: '표시·순서·배치와 크기' },
  { id: 'calendar', label: '달력 표시', description: '기본 보기와 거래 시트' },
  { id: 'widgets', label: '집계 위젯', description: '집계·목표·그래프·표시' },
  { id: 'input', label: '거래 입력', description: '빠른 입력과 이미지 분석' },
  { id: 'defaults', label: '기본값 관리', description: '적용 범위를 나누어 복원' },
]
export const clonePersonalization = value => JSON.parse(JSON.stringify(value))
export const personalizationChanged = (left, right) => JSON.stringify(left) !== JSON.stringify(right)

// Desktop coordinates remain the source for the initial mobile ordering.
// An explicit mobile reorder records ranks separately and never rewrites x/y.
export function orderedMobilePanels(panels) {
  const desktop = [...panels].sort((a, b) => (Number(a.y) || 0) - (Number(b.y) || 0) || (Number(a.x) || 0) - (Number(b.x) || 0))
  const fallback = new Map(desktop.map((panel, index) => [panel.id, index]))
  return desktop.sort((a, b) => {
    const rank = panel => Number.isInteger(panel.mobileOrder) && panel.mobileOrder >= 0 ? panel.mobileOrder : fallback.get(panel.id)
    return rank(a) - rank(b) || fallback.get(a.id) - fallback.get(b.id)
  })
}

export function reorderMobilePanels(panels, visibleIds) {
  const ordered = orderedMobilePanels(panels)
  const visibleSet = new Set(visibleIds)
  const existingVisible = ordered.filter(panel => visibleSet.has(panel.id)).map(panel => panel.id)
  if (visibleSet.size !== visibleIds.length || existingVisible.length !== visibleIds.length) return panels.map(panel => ({ ...panel }))
  if (existingVisible.every((id, index) => id === visibleIds[index])) return panels.map(panel => ({ ...panel }))
  let index = 0
  const ids = ordered.map(panel => visibleSet.has(panel.id) ? visibleIds[index++] : panel.id)
  const positions = new Map(ids.map((id, order) => [id, order]))
  return panels.map(panel => ({ ...panel, mobileOrder: positions.get(panel.id) }))
}

export const MOBILE_WIDGET_COUNT = 6
export function normalizeMobileWidgetOrder(value) {
  if (!Array.isArray(value) || value.length !== MOBILE_WIDGET_COUNT || new Set(value).size !== MOBILE_WIDGET_COUNT
      || value.some(index => !Number.isInteger(index) || index < 0 || index >= MOBILE_WIDGET_COUNT)) return null
  return [...value]
}

export function normalizeMobileWidgetRows(value) {
  if (!Array.isArray(value) || value.length !== MOBILE_WIDGET_COUNT
      || value.some(rows => !Number.isInteger(rows) || rows < 0 || rows > 12)) return null
  return [...value]
}

export function reorderMobileWidgets(order, visibleIndices) {
  const current = normalizeMobileWidgetOrder(order) || Array.from({ length: MOBILE_WIDGET_COUNT }, (_, index) => index)
  const visible = new Set(visibleIndices)
  const oldVisible = current.filter(index => visible.has(index))
  if (visible.size !== visibleIndices.length || oldVisible.length !== visibleIndices.length || oldVisible.every((value, index) => value === visibleIndices[index])) return current
  let next = 0
  return current.map(index => visible.has(index) ? visibleIndices[next++] : index)
}

// Widgets are stored as an ordered array. When desktop order changes, carry
// mobile overrides with the same widget rather than with its old array index.
export function remapMobileWidgets(beforeIds, afterIds, order, rows) {
  if (beforeIds.length !== MOBILE_WIDGET_COUNT || afterIds.length !== MOBILE_WIDGET_COUNT
      || new Set(beforeIds).size !== MOBILE_WIDGET_COUNT || new Set(afterIds).size !== MOBILE_WIDGET_COUNT) return null
  const newIndex = new Map(afterIds.map((id, index) => [id, index]))
  if (beforeIds.some(id => !newIndex.has(id))) return null
  const remap = index => newIndex.get(beforeIds[index])
  const savedOrder = normalizeMobileWidgetOrder(order)
  const savedRows = normalizeMobileWidgetRows(rows)
  const nextRows = savedRows ? Array(MOBILE_WIDGET_COUNT).fill(0) : null
  if (nextRows) savedRows.forEach((height, index) => { nextRows[remap(index)] = height })
  return { order: savedOrder ? savedOrder.map(remap) : null, rows: nextRows }
}

// Pure collision resolver: explicit edits persist its result; responsive display
// projections use a copy and never rewrite the stored layout on read.
export function packDashboardPanels(panels, changedId, { maxPanelRows = 12 } = {}) {
  const occupied = new Set(), result = new Map()
  const ordered = [...panels].sort((a, b) => (a.id === changedId ? -1 : b.id === changedId ? 1 : a.y - b.y || a.x - b.x))
  const free = (x, y, w, h) => {
    for (let row = y; row < y + h; row++) for (let column = x; column < x + w; column++) if (occupied.has(`${column}:${row}`)) return false
    return true
  }
  for (const panel of ordered) {
    const w = Math.max(1, Math.min(9, Math.round(Number(panel.w) || 1)))
    const h = Math.max(1, Math.min(Math.max(12, maxPanelRows), Math.round(Number(panel.h) || 1)))
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
