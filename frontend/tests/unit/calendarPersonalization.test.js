import test from 'node:test'
import assert from 'node:assert/strict'
import { clonePersonalization, personalizationChanged, packDashboardPanels, orderedMobilePanels, reorderMobilePanels, reorderMobileWidgets, remapMobileWidgets } from '../../src/lib/calendarPersonalization.js'

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
test('mobile order is independent of desktop geometry and keeps hidden panel slots', () => {
  const original = structuredClone(panels)
  const moved = reorderMobilePanels(panels, ['calendar', 'aggregate', 'quick-entry'])
  assert.deepEqual(orderedMobilePanels(moved).map(panel => panel.id), ['calendar', 'aggregate', 'quick-entry', 'sheet'])
  assert.deepEqual(moved.map(({ id, x, y, w, h }) => ({ id, x, y, w, h })), original)
  assert.deepEqual(panels, original)
  assert.deepEqual(reorderMobilePanels(panels, ['aggregate', 'calendar', 'quick-entry']), original)
  assert.deepEqual(reorderMobilePanels(panels, ['aggregate', 'aggregate']), original)
})
test('mobile widget order keeps hidden slots and follows widgets across a desktop reorder', () => {
  assert.deepEqual(reorderMobileWidgets(null, [2, 0, 3]), [2, 1, 0, 3, 4, 5])
  const before = ['a', 'b', 'c', 'd', 'e', 'f'], after = ['b', 'a', 'c', 'd', 'e', 'f']
  assert.deepEqual(remapMobileWidgets(before, after, [2, 1, 0, 3, 4, 5], [2, 4, 0, 0, 0, 0]), {
    order: [2, 0, 1, 3, 4, 5], rows: [4, 2, 0, 0, 0, 0],
  })
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
test('responsive presentation can exceed the saved 12-row limit without mutating it', () => {
  const projected = panels.map(panel => panel.id === 'aggregate' ? { ...panel, h: 15 } : panel)
  const packed = packDashboardPanels(projected, 'aggregate', { maxPanelRows: 15 })
  assert.equal(packed.find(panel => panel.id === 'aggregate').h, 15)
  assert.ok(packed.find(panel => panel.id === 'calendar').y >= 15)
  assert.equal(panels.find(panel => panel.id === 'aggregate').h, 2)
  assert.equal(packDashboardPanels(projected, 'aggregate').find(panel => panel.id === 'aggregate').h, 12)
})
test('editing at the final row never pushes another dashboard panel beyond the saved coordinate range', () => {
  const crowded = panels.map(panel => ({ ...panel, x: 0, y: 200, w: 9 }))
  const packed = packDashboardPanels(crowded, 'calendar')
  assert.equal(packed.find(panel => panel.id === 'calendar').y, 200)
  assert.ok(packed.every(panel => panel.y >= 0 && panel.y <= 200))
})
