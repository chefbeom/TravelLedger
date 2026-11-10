import test from 'node:test'
import assert from 'node:assert/strict'
import { buildInsights, summarizeEntries } from '../../src/lib/analytics.js'

test('cancelled and reverted records stay visible but do not affect totals or insights', () => {
  const entries = [
    { id: 1, entryDate: '2026-01-10', entryType: 'EXPENSE', amount: 100, financiallyExcluded: true },
    { id: 2, entryDate: '2026-03-15', entryType: 'EXPENSE', amount: 100, financiallyExcluded: true },
    { id: 3, entryDate: '2026-03-15', entryType: 'INCOME', amount: 93 },
  ]
  assert.deepEqual(summarizeEntries(entries), { income: 93, expense: 0, balance: 93, count: 3 })
  assert.equal(buildInsights(entries).peakExpenseDay.total, 0)
})
