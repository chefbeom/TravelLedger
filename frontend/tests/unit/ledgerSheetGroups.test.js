import test from 'node:test'
import assert from 'node:assert/strict'
import { groupLedgerSheetEntries, splitDisplayedAmount, splitExpectedCount } from '../../src/lib/ledgerSheetGroups.js'

test('split entries form one stable row without folding unrelated adjustment records', () => {
  const source = [
    { id: 1, splitPaymentGroupId: 'a', splitPaymentCount: 3, amount: '0.10' },
    { id: 9, title: '일반 거래' },
    { id: 2, splitPaymentGroupId: 'a', splitPaymentCount: 3, amount: '0.20' },
    { id: 7, splitPaymentGroupId: 'a', adjustmentType: 'REFUND', amount: '0.10' },
    { id: 3, splitPaymentGroupId: 'b', splitPaymentCount: 2, amount: 10 },
  ]
  const rows = groupLedgerSheetEntries(source)
  assert.deepEqual(rows.map(row => row.key), ['split:a', 'entry:9', 'entry:7', 'split:b'])
  assert.deepEqual(rows[0].parts.map(part => part.id), [1, 2])
  assert.equal(splitDisplayedAmount(rows[0].parts), 0.3)
  assert.equal(splitExpectedCount(rows[0].parts), 3)
  assert.equal(rows[2].isSplit, false)
  assert.deepEqual(source.map(entry => entry.id), [1, 9, 2, 7, 3])
})

test('groups stay intact if their parts straddle the old pagination boundary', () => {
  const source = Array.from({ length: 999 }, (_, index) => ({ id: index + 1 }))
  source.push({ id: 1000, splitPaymentGroupId: 'boundary', splitPaymentCount: 2 })
  source.push({ id: 1001 })
  source.push({ id: 1002, splitPaymentGroupId: 'boundary', splitPaymentCount: 2 })
  const rows = groupLedgerSheetEntries(source)
  assert.equal(rows.length, 1001)
  assert.deepEqual(rows.slice(0, 1000).at(-1).parts.map(part => part.id), [1000, 1002])
  assert.equal(rows.slice(1000)[0].parts[0].id, 1001)
})
