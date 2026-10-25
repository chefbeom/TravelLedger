import { strict as assert } from 'node:assert'
import { test } from 'node:test'
import { widgetGridSize, widgetGridStyle, resizedWidgetGrid } from '../../src/lib/calendarWidgetGrid.js'

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
