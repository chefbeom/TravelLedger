// Before the default was enlarged, an unopened account cached this exact
// five-palette geometry locally. Compare all coordinates and dimensions so a
// user-customized layout is never mistaken for the automatic default.
const LEGACY_DEFAULT = [
  { id: 'aggregate', x: 0, y: 0, w: 9, h: 2 },
  { id: 'calendar', x: 0, y: 3, w: 6, h: 6 },
  { id: 'quick-entry', x: 6, y: 3, w: 3, h: 6 },
  { id: 'sheet', x: 0, y: 9, w: 9, h: 3 },
  { id: 'calendar-query', x: 0, y: 2, w: 9, h: 1 },
]

export function isLegacyDefaultCalendarLayout(layout) {
  if (!Array.isArray(layout) || layout.length !== LEGACY_DEFAULT.length) return false
  const byId = new Map(layout.map(panel => [panel?.id, panel]))
  if (byId.size !== LEGACY_DEFAULT.length) return false
  return LEGACY_DEFAULT.every(expected => {
    const actual = byId.get(expected.id)
    return Boolean(actual && ['x', 'y', 'w', 'h'].every(key => actual[key] === expected[key]))
  })
}
