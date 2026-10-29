import { test } from 'node:test'
import assert from 'node:assert/strict'
import { isLegacyDefaultCalendarLayout } from '../../src/lib/calendarDefaultLayout.js'

const original = [
  { id: 'aggregate', x: 0, y: 0, w: 9, h: 2 },
  { id: 'calendar', x: 0, y: 3, w: 6, h: 6 },
  { id: 'quick-entry', x: 6, y: 3, w: 3, h: 6 },
  { id: 'sheet', x: 0, y: 9, w: 9, h: 3 },
  { id: 'calendar-query', x: 0, y: 2, w: 9, h: 1 },
]

test('only the exact old automatic palette geometry is recognized', () => {
  assert.equal(isLegacyDefaultCalendarLayout(original), true)
  assert.equal(isLegacyDefaultCalendarLayout([...original].reverse()), true)
  assert.equal(isLegacyDefaultCalendarLayout(original.map(item => item.id === 'calendar' ? { ...item, h: 7 } : item)), false)
  assert.equal(isLegacyDefaultCalendarLayout(original.map(item => item.id === 'quick-entry' ? { ...item, x: 5 } : item)), false)
  assert.equal(isLegacyDefaultCalendarLayout([...original, original[0]]), false)
  assert.equal(isLegacyDefaultCalendarLayout(original.slice(1)), false)
  assert.equal(isLegacyDefaultCalendarLayout(null), false)
})
