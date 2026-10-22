import test from 'node:test'
import assert from 'node:assert/strict'
import { clonePersonalization, personalizationChanged, packDashboardPanels } from '../../src/lib/calendarPersonalization.js'

const panels = [
  { id: 'aggregate', x: 0, y: 0, w: 9, h: 2 },
  { id: 'calendar', x: 0, y: 2, w: 6, h: 6 },
  { id: 'quick-entry', x: 6, y: 2, w: 3, h: 6 },
  { id: 'sheet', x: 0, y: 8, w: 9, h: 3 },
]
test('draft edits do not mutate the committed settings and detect a reverted change', () => {
  const saved = { preferences: { highlightMode: 'net' }, layout: panels, widgets: [{ title: '집계' }] }
  const draft = clonePersonalization(saved)
  assert.equal(personalizationChanged(saved, draft), false)
  draft.widgets[0].title = '다른 집계'
  assert.equal(saved.widgets[0].title, '집계')
  assert.equal(personalizationChanged(saved, draft), true)
  draft.widgets[0].title = '집계'
  assert.equal(personalizationChanged(saved, draft), false)
})
test('explicit resize packs collisions without changing the source or hiding panels', () => {
  const draft = panels.map(panel => panel.id === 'calendar' ? { ...panel, w: 9 } : panel)
  const packed = packDashboardPanels(draft, 'calendar')
  assert.equal(panels[1].w, 6)
  assert.deepEqual(packed.map(panel => panel.id), panels.map(panel => panel.id))
  const occupied = new Set()
  for (const { x, y, w, h } of packed) {
    assert.ok(x >= 0 && x + w <= 9)
    for (let row = y; row < y + h; row++) for (let col = x; col < x + w; col++) {
      const key = `${col}:${row}`
      assert.equal(occupied.has(key), false)
      occupied.add(key)
    }
  }
})
test('packing respects a valid custom placement and ordering', () => {
  const custom = panels.map(panel => ({ ...panel, y: panel.y + 15 }))
  assert.deepEqual(packDashboardPanels(custom, 'calendar'), custom)
})
test('editing at the final row never pushes another dashboard panel beyond the saved coordinate range', () => {
  const crowded = panels.map(panel => ({ ...panel, x: 0, y: 200, w: 9 }))
  const packed = packDashboardPanels(crowded, 'calendar')
  assert.equal(packed.find(panel => panel.id === 'calendar').y, 200)
  assert.ok(packed.every(panel => panel.y >= 0 && panel.y <= 200))
})
