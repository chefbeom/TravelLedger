import { strict as assert } from 'node:assert'
import { test } from 'node:test'
import { widgetGridSize, widgetGridStyle, resizedWidgetGrid, resolvedWidgetGrid, widgetGridRowCount, packWidgetGrid } from '../../src/lib/calendarWidgetGrid.js'

test('missing grid sizes preserve legacy pixel and automatic dimensions without inferring a new layout', () => {
  assert.deepEqual(widgetGridSize({ cardWidth: 310, cardHeight: 230 }), { cardColumns: 0, cardRows: 0 })
  assert.deepEqual(widgetGridStyle({ cardWidth: 310, cardHeight: 230 }, 128), {})
})

test('saved sizes use bounded whole columns and rows, with a two-column graph minimum', () => {
  assert.deepEqual(widgetGridSize({ cardColumns: 2.3, cardRows: 4.8 }), { cardColumns: 2, cardRows: 5 })
  assert.deepEqual(widgetGridSize({ kind: 'MONTHLY_CUMULATIVE_CHART', cardColumns: 1, cardRows: 100 }), { cardColumns: 2, cardRows: 12 })
  assert.deepEqual(widgetGridSize({ cardColumns: 100, cardRows: -1 }), { cardColumns: 9, cardRows: 0 })
})

test('grid style scales width with its palette and height with the same dashboard row pitch', () => {
  assert.deepEqual(widgetGridStyle({ cardColumns: 9, cardRows: 2 }, 128), {
    '--calendar-widget-width': 'calc(100% + 0px)', '--calendar-widget-min-width': '0px', '--calendar-widget-height': '248px', '--calendar-widget-min-height': '0px',
  })
  assert.equal(widgetGridStyle({ cardRows: 2 }, 112)['--calendar-widget-height'], '216px')
})

test('pointer resizing snaps at cell boundaries rather than saving pixel deltas', () => {
  assert.deepEqual(resizedWidgetGrid({ width: 312, height: 248 }, 160, 128, 1432, 128), { cardColumns: 3, cardRows: 3 })
  assert.deepEqual(resizedWidgetGrid({ width: 312, height: 248 }, -9999, -9999, 1432, 128, true), { cardColumns: 2, cardRows: 1 })
  assert.deepEqual(resizedWidgetGrid({ width: 312, height: 248 }, 99999, 99999, 1432, 128), { cardColumns: 9, cardRows: 12 })
})

test('legacy and explicit sizes resolve to the same cells without changing their saved pixels', () => {
  const legacy = { kind: 'TOTAL', cardWidth: 247, cardHeight: 190 }, original = structuredClone(legacy)
  assert.deepEqual(resolvedWidgetGrid(legacy, 1432, 128), { cardColumns: 2, cardRows: 2 })
  assert.deepEqual(resolvedWidgetGrid({ ...legacy, cardColumns: 3, cardRows: 1 }, 1432, 128), { cardColumns: 3, cardRows: 1 })
  assert.deepEqual(resolvedWidgetGrid({ ...legacy, kind: 'MONTHLY_CUMULATIVE_CHART', cardWidth: 200 }, 1432, 128), { cardColumns: 2, cardRows: 2 })
  assert.deepEqual(legacy, original)
  assert.deepEqual(resolvedWidgetGrid({ kind: 'TOTAL' }, 0, 0), { cardColumns: 2, cardRows: 2 })
})

test('guides cover whole wrapped rows, including different heights and full-width mobile cards', () => {
  const frames = [{ cardColumns: 3, cardRows: 2 }, { cardColumns: 2, cardRows: 1 }, { cardColumns: 4, cardRows: 3 }, { cardColumns: 2, cardRows: 2 }]
  assert.equal(widgetGridRowCount(frames), 3)
  assert.equal(widgetGridRowCount(frames, true), 8)
  assert.equal(widgetGridRowCount([]), 0)
})

test('a 2x2 card occupies only its own cells and does not push an entire following row down', () => {
  const frames = Array.from({ length: 5 }, (_, index) => ({ cardColumns: 2, cardRows: index === 3 ? 2 : 1 }))
  const original = structuredClone(frames), cells = packWidgetGrid(frames)
  assert.deepEqual(cells, [{ x: 0, y: 0, w: 2, h: 1 }, { x: 2, y: 0, w: 2, h: 1 }, { x: 4, y: 0, w: 2, h: 1 }, { x: 6, y: 0, w: 2, h: 2 }, { x: 0, y: 1, w: 2, h: 1 }])
  assert.equal(widgetGridRowCount(frames), 2)
  const occupied = new Set()
  for (const cell of cells) for (let y = cell.y; y < cell.y + cell.h; y++) for (let x = cell.x; x < cell.x + cell.w; x++) {
    assert.equal(occupied.has(`${x}:${y}`), false)
    occupied.add(`${x}:${y}`)
  }
  assert.deepEqual(frames, original)
})
