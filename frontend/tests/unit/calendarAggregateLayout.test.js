import { test } from 'node:test'
import assert from 'node:assert/strict'
import { aggregateRequiredPanelRows, aggregateWidgetCellHeight } from '../../src/lib/calendarAggregateLayout.js'

test('two-row palettes reserve header space within the existing outer grid height', () => {
  assert.equal(aggregateWidgetCellHeight(140, 56), 111)
  assert.equal(aggregateRequiredPanelRows(140, 111, 56, 2), 2)
  assert.equal(aggregateRequiredPanelRows(140, 111, 56, 3), 3)
})

test('changing a palette height leaves card pitch unchanged and mobile keeps the normal cell size', () => {
  assert.equal(aggregateWidgetCellHeight(140, 56), 111)
  assert.equal(aggregateWidgetCellHeight(112, 56, true), 112)
  assert.equal(aggregateRequiredPanelRows(112, 112, 56, 7), 8)
})

test('missing or zero measurements never produce invalid sizes', () => {
  assert.ok(aggregateWidgetCellHeight(0, 100) >= 1)
  assert.ok(aggregateRequiredPanelRows(0, 0, 0, 0) >= 2)
})
