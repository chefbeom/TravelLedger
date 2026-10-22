import { strict as assert } from 'node:assert'
import { test } from 'node:test'
import { widgetFrame, resizedWidgetFrame, reorderItem } from '../../src/lib/calendarDirectEditing.js'

test('legacy widget appearance defaults remain visible and optional sizes stay automatic', () => {
  assert.deepEqual(widgetFrame({}), { cardWidth: 0, cardHeight: 0, showPeriod: true, showDetails: true })
  assert.deepEqual(widgetFrame({ cardWidth: 360, cardHeight: 240, showPeriod: false, showDetails: false }), { cardWidth: 360, cardHeight: 240, showPeriod: false, showDetails: false })
})

test('direct widget sizes are bounded and clearing a dimension restores automatic sizing', () => {
  assert.deepEqual(widgetFrame({ cardWidth: 9999, cardHeight: 1 }), { cardWidth: 1200, cardHeight: 160, showPeriod: true, showDetails: true })
  assert.equal(widgetFrame({ cardWidth: '', cardHeight: null }).cardWidth, 0)
  assert.equal(widgetFrame({ cardWidth: 'invalid' }).cardWidth, 0)
})

test('pointer resizing keeps a readable chart minimum and respects palette width and height limits', () => {
  assert.deepEqual(resizedWidgetFrame({ width: 480, height: 320 }, -999, -999, 1000, true), { cardWidth: 280, cardHeight: 160 })
  assert.deepEqual(resizedWidgetFrame({ width: 260, height: 200 }, 9999, 9999, 620), { cardWidth: 620, cardHeight: 800 })
})

test('direct reorder is immutable and moves only the selected item', () => {
  const items = [{ id: 'a' }, { id: 'b' }, { id: 'c' }]
  assert.deepEqual(reorderItem(items, 0, 2).map(item => item.id), ['b', 'c', 'a'])
  assert.deepEqual(items.map(item => item.id), ['a', 'b', 'c'])
  assert.deepEqual(reorderItem(items, -1, 9), items)
})
