// Old four-panel settings omitted the query strip. Upgrade once to an editable
// fifth palette; never project an extra row into an already upgraded layout.
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

export function ensureCalendarQueryPalette(panels) {
  if (panels.some(panel => panel.id === CALENDAR_QUERY_ITEM_ID)) return panels.map(panel => ({ ...panel }))
  const row = calendarQueryRow(panels)
  return [...panels.map(panel => withCalendarQueryRow({ ...panel, y: Number(panel.y) || 0 }, row)),
    { id: CALENDAR_QUERY_ITEM_ID, x: 0, y: row, w: 9, h: 1 }]
}
