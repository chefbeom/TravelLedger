import { strict as assert } from 'node:assert'
import { test } from 'node:test'
import { calendarQueryRow, withCalendarQueryRow, withoutCalendarQueryRow, ensureCalendarQueryPalette } from '../../src/lib/calendarQueryLayout.js'

test('a common query row separates aggregates from both calendar and quick entry without changing saved coordinates', () => {
  const panels = [{ id: 'aggregate', y: 0, h: 2 }, { id: 'calendar', y: 2, h: 6 }, { id: 'quick-entry', y: 2, h: 6 }]
  const original = structuredClone(panels), row = calendarQueryRow(panels)
  assert.equal(row, 2)
  assert.deepEqual(panels.map(panel => withCalendarQueryRow(panel, row).y), [0, 3, 3])
  assert.deepEqual(panels.map(panel => withoutCalendarQueryRow(withCalendarQueryRow(panel, row), row)), panels)
  assert.deepEqual(panels, original)
})

test('legacy layouts gain one movable palette and existing query positions are never derived again', () => {
  const legacy = [{ id: 'aggregate', x: 0, y: 0, w: 9, h: 2 }, { id: 'calendar', x: 0, y: 2, w: 6, h: 6 }]
  const original = structuredClone(legacy), upgraded = ensureCalendarQueryPalette(legacy)
  assert.deepEqual(upgraded.map(panel => [panel.id, panel.y]), [['aggregate', 0], ['calendar', 3], ['calendar-query', 2]])
  upgraded[2].y = 0
  assert.deepEqual(ensureCalendarQueryPalette(upgraded), upgraded)
  assert.deepEqual(legacy, original)
})

test('custom layouts place the strip above both primary palettes and never split another palette', () => {
  assert.equal(calendarQueryRow([{ id: 'calendar', y: 0, h: 6 }, { id: 'quick-entry', y: 8, h: 4 }]), 0)
  assert.equal(calendarQueryRow([{ id: 'calendar', y: 4, h: 6 }, { id: 'quick-entry', y: 4, h: 6 }, { id: 'aggregate', y: 1, h: 4 }]), 1)
  assert.equal(calendarQueryRow([{ id: 'calendar', y: 5, h: 6 }, { id: 'quick-entry', y: 5, h: 6 }, { id: 'aggregate', y: 3, h: 4 }, { id: 'sheet', y: 0, h: 4 }]), 0)
})
