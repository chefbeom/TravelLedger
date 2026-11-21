import test from 'node:test'
import assert from 'node:assert/strict'
import { layoutWeekBars } from '../../src/lib/integratedCalendarBars.js'

const week1 = ['2024-02-11', '2024-02-12', '2024-02-13', '2024-02-14', '2024-02-15', '2024-02-16', '2024-02-17']
const week2 = ['2024-02-18', '2024-02-19', '2024-02-20', '2024-02-21', '2024-02-22', '2024-02-23', '2024-02-24']
const span = (key, startDate, endDate, extra = {}) => ({ key, layer: 'events', title: key, startDate, endDate, ...extra })

test('a multi-day item inside one week becomes a single bar', () => {
  const { bars, laneCount } = layoutWeekBars(week1, [span('a', '2024-02-14', '2024-02-16')], 3)
  assert.equal(laneCount, 1)
  assert.deepEqual(bars.map(({ startCol, span, lane, continuesBefore, continuesAfter }) => ({ startCol, span, lane, continuesBefore, continuesAfter })),
    [{ startCol: 3, span: 3, lane: 0, continuesBefore: false, continuesAfter: false }])
})

test('an item crossing a week boundary is clipped in both weeks', () => {
  const item = span('a', '2024-02-16', '2024-02-20')
  const first = layoutWeekBars(week1, [item], 3).bars[0]
  const second = layoutWeekBars(week2, [item], 3).bars[0]
  assert.deepEqual([first.startCol, first.span, first.continuesAfter, first.continuesBefore], [5, 2, true, false])
  assert.deepEqual([second.startCol, second.span, second.continuesBefore, second.continuesAfter], [0, 3, true, false])
})

test('overlapping items use different lanes and disjoint items reuse lane 0', () => {
  const overlap = layoutWeekBars(week1, [span('a', '2024-02-13', '2024-02-15'), span('b', '2024-02-14', '2024-02-16')], 3)
  assert.deepEqual(overlap.bars.map(bar => bar.lane), [0, 1])
  const disjoint = layoutWeekBars(week1, [span('a', '2024-02-11', '2024-02-12'), span('b', '2024-02-14', '2024-02-15')], 3)
  assert.deepEqual(disjoint.bars.map(bar => bar.lane), [0, 0])
  assert.equal(disjoint.laneCount, 1)
})

test('items beyond the lane limit are counted per column instead of drawn', () => {
  // Same start, so the longest item takes the lane first; the rest are hidden.
  const spans = [
    span('travel', '2024-02-14', '2024-02-16', { layer: 'travel' }),
    span('shared', '2024-02-15', '2024-02-16', { layer: 'travel', shared: true }),
    span('event', '2024-02-15', '2024-02-16'),
  ]
  const result = layoutWeekBars(week1, spans, 2)
  assert.equal(result.laneCount, 2)
  assert.deepEqual(result.bars.map(bar => bar.key), ['travel', 'shared'])
  assert.deepEqual(result.hiddenByCol, [0, 0, 0, 0, 1, 1, 0])
})

test('a longer item wins the lane when several start on the same day', () => {
  const spans = [span('short', '2024-02-15', '2024-02-16'), span('long', '2024-02-15', '2024-02-17')]
  const { bars, hiddenByCol } = layoutWeekBars(week1, spans, 1)
  assert.deepEqual(bars.map(bar => bar.key), ['long'])
  assert.deepEqual(hiddenByCol, [0, 0, 0, 0, 1, 1, 0])
})

test('zero lanes hide everything and still count the columns', () => {
  const { bars, laneCount, hiddenByCol } = layoutWeekBars(week1, [span('a', '2024-02-12', '2024-02-13')], 0)
  assert.equal(bars.length, 0)
  assert.equal(laneCount, 0)
  assert.deepEqual(hiddenByCol, [0, 1, 1, 0, 0, 0, 0])
})

test('invalid, reversed and out-of-week items are ignored', () => {
  const spans = [span('null', null, null), span('bad', '2023-02-29', '2024-02-12'), span('rev', '2024-02-15', '2024-02-13'), span('away', '2024-03-01', '2024-03-02')]
  const result = layoutWeekBars(week1, spans, 3)
  assert.equal(result.bars.length, 0)
  assert.deepEqual(result.hiddenByCol, [0, 0, 0, 0, 0, 0, 0])
})

test('the layout does not depend on the input order', () => {
  const spans = [span('c', '2024-02-13', '2024-02-17'), span('a', '2024-02-13', '2024-02-15'), span('b', '2024-02-12', '2024-02-14'), span('d', '2024-02-15', '2024-02-16', { layer: 'travel' })]
  const expected = JSON.stringify(layoutWeekBars(week1, spans, 3))
  assert.equal(JSON.stringify(layoutWeekBars(week1, [...spans].reverse(), 3)), expected)
  assert.equal(JSON.stringify(layoutWeekBars(week1, [spans[2], spans[0], spans[3], spans[1]], 3)), expected)
})
