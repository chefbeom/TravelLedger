// The common query strip is a presentation row, not an extra user-owned palette.
// Keep saved palette coordinates unchanged and use the same projection in view/edit.
export const CALENDAR_QUERY_ITEM_ID = 'calendar-query'

export function calendarQueryRow(panels) {
  const primary = panels.filter(panel => ['calendar', 'quick-entry'].includes(panel.id))
  let row = primary.length ? Math.min(...primary.map(panel => Number(panel.y) || 0)) : 0
  // A custom side-by-side palette must not be split by the full-width strip.
  while (true) {
    const crossing = panels.filter(panel => Number(panel.y) < row && Number(panel.y) + Number(panel.h) > row)
    if (!crossing.length) return row
    row = Math.min(...crossing.map(panel => Number(panel.y)))
  }
}

export function withCalendarQueryRow(panel, row, rows = 1) {
  return { ...panel, y: panel.y >= row ? panel.y + rows : panel.y }
}

export function withoutCalendarQueryRow(panel, row, rows = 1) {
  return { ...panel, y: panel.y >= row + rows ? panel.y - rows : panel.y }
}
