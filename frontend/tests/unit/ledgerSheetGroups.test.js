import test from 'node:test'
import assert from 'node:assert/strict'
import {
  adjustmentsForEntry, groupLedgerSheetEntries, ledgerEntryStatusLabel, rowContainsEntryId, splitAdjustmentLabel,
  splitDisplayedAmount, splitExpectedCount, summarizeLedgerAdjustments,
} from '../../src/lib/ledgerSheetGroups.js'

test('split entries form one stable row and unattached adjustment records become linked rows', () => {
  const source = [
    { id: 1, splitPaymentGroupId: 'a', splitPaymentCount: 3, amount: '0.10' },
    { id: 9, title: '일반 거래' },
    { id: 2, splitPaymentGroupId: 'a', splitPaymentCount: 3, amount: '0.20' },
    { id: 7, splitPaymentGroupId: 'a', adjustmentType: 'REFUND', amount: '0.10' },
    { id: 3, splitPaymentGroupId: 'b', splitPaymentCount: 2, amount: 10 },
  ]
  const rows = groupLedgerSheetEntries(source)
  assert.deepEqual(rows.map(row => row.key), ['split:a', 'entry:9', 'adjustment:7', 'split:b'])
  assert.deepEqual(rows[0].parts.map(part => part.id), [1, 2])
  assert.equal(splitDisplayedAmount(rows[0].parts), 0.3)
  assert.equal(splitExpectedCount(rows[0].parts), 3)
  assert.equal(rows[2].isSplit, false)
  assert.equal(rows[2].kind, 'adjustment')
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

const card = { categoryGroupName: '취미', categoryDetailName: '구독', paymentMethodName: '테스트 카드' }
const cancelRecord = (id, extra = {}) => ({ ...card, id, entryDate: '2026-11-21', entryTime: null, entryType: 'EXPENSE', amount: 29000, title: '결제 취소', financiallyExcluded: true, adjustmentType: 'PAYMENT_CANCELLATION', adjustmentOriginId: 4101, adjustmentPrincipal: 29000, ...extra })
const origin = (extra = {}) => ({ ...card, id: 4101, entryDate: '2026-11-21', entryTime: null, entryType: 'EXPENSE', amount: 29000, title: '구독 서비스 A', hasAdjustmentHistory: true, refundedPrincipal: 0, financiallyExcluded: false, cancelledAt: null, ...extra })

test('user case: voided cancellation folds into the original row', () => {
  const input = [
    { ...card, id: 4100, entryDate: '2026-11-21', entryTime: '21:31', entryType: 'EXPENSE', amount: 72000, title: '팀 요금제', foreignCurrencyCode: 'USD', foreignAmount: 50 },
    origin(),
    cancelRecord(4102, { adjustmentVoidedAt: '2026-11-21T10:00:00' }),
  ]
  const snapshot = structuredClone(input)
  const rows = groupLedgerSheetEntries(input)
  assert.deepEqual(rows.map(r => r.key), ['entry:4100', 'entry:4101'])
  assert.deepEqual(rows[1].adjustments.map(a => a.id), [4102])
  const summary = summarizeLedgerAdjustments(rows[1].parts[0], rows[1].adjustments)
  assert.equal(summary.status, 'REVERTED')
  assert.equal(summary.label, '취소 되돌림')
  assert.equal(summary.excluded, false)
  assert.equal(summary.activeRefundReceived, 0)
  assert.equal(summary.items[0].label, '결제 취소 되돌림')
  assert.equal(ledgerEntryStatusLabel(input[2]), '결제 취소 기록 · 되돌림')
  assert.equal(rowContainsEntryId(rows[1], 4102), true)
  assert.equal(rowContainsEntryId(rows[0], '4102'), false)
  assert.deepEqual(input, snapshot)
})

test('active cancellation marks the original cancelled and excluded', () => {
  const rows = groupLedgerSheetEntries([origin({ cancelledAt: '2026-11-21T09:00:00', financiallyExcluded: true }), cancelRecord(4102)])
  const summary = summarizeLedgerAdjustments(rows[0].parts[0], rows[0].adjustments)
  assert.equal(summary.label, '결제 취소됨')
  assert.equal(summary.excluded, true)
  assert.equal(ledgerEntryStatusLabel(rows[0].adjustments[0]), '결제 취소 기록')
})

test('reverted cancellation followed by a new cancellation lists history chronologically', () => {
  const rows = groupLedgerSheetEntries([
    cancelRecord(6750),
    origin({ cancelledAt: '2026-11-21T11:00:00', financiallyExcluded: true }),
    cancelRecord(4102, { adjustmentVoidedAt: '2026-11-21T10:00:00' }),
  ])
  assert.equal(rows.length, 1)
  const summary = summarizeLedgerAdjustments(rows[0].parts[0], rows[0].adjustments)
  assert.equal(summary.label, '결제 취소됨')
  assert.deepEqual(summary.items.map(i => i.label), ['결제 취소 되돌림', '결제 취소'])
})

test('partial refund counts only active refund receipts', () => {
  const refund = (id, amount, extra = {}) => ({ id, entryDate: '2026-11-21', entryType: 'INCOME', amount, adjustmentType: 'REFUND', adjustmentOriginId: 1, ...extra })
  const rows = groupLedgerSheetEntries([
    { id: 1, entryDate: '2026-11-21', amount: 10000, refundedPrincipal: 3000, hasAdjustmentHistory: true },
    refund(2, '2500.10'),
    refund(3, 1000, { adjustmentVoidedAt: '2026-11-21T10:00:00', financiallyExcluded: true }),
  ])
  const summary = summarizeLedgerAdjustments(rows[0].parts[0], rows[0].adjustments)
  assert.equal(summary.label, '부분 환불')
  assert.equal(summary.status, 'PARTIALLY_REFUNDED')
  assert.equal(summary.activeRefundReceived, 2500.1)
  const full = summarizeLedgerAdjustments({ amount: 10000, refundedPrincipal: 10000, hasAdjustmentHistory: true }, [])
  assert.equal(full.label, '환불 완료')
})

test('adjustment without an original in the slice stays as a linked row at its position', () => {
  const rows = groupLedgerSheetEntries([{ id: 1 }, { id: 8, adjustmentType: 'REFUND', adjustmentOriginId: 99 }, { id: 2 }])
  assert.deepEqual(rows.map(r => r.key), ['entry:1', 'adjustment:8', 'entry:2'])
  assert.equal(rows[1].kind, 'adjustment')
  assert.equal(rows[1].parts[0].id, 8)
  assert.deepEqual(rows[1].adjustments, [])
})

test('descending input still attaches the record to the original position', () => {
  const rows = groupLedgerSheetEntries([cancelRecord(4102), { id: 5 }, origin()])
  assert.deepEqual(rows.map(r => r.key), ['entry:5', 'entry:4101'])
  assert.equal(rows[1].adjustments.length, 1)
})

test('split payment adjustments attach to the split row without changing parts', () => {
  const rows = groupLedgerSheetEntries([
    { id: 1, splitPaymentGroupId: 'a', splitPaymentCount: 2, amount: 100, refundedPrincipal: 30, hasAdjustmentHistory: true },
    { id: 2, splitPaymentGroupId: 'a', splitPaymentCount: 2, amount: 50 },
    { id: 9, adjustmentType: 'REFUND', adjustmentOriginId: 1, amount: 25, splitPaymentGroupId: 'a' },
  ])
  assert.equal(rows.length, 1)
  assert.equal(rows[0].kind, 'split')
  assert.deepEqual(rows[0].parts.map(p => p.id), [1, 2])
  assert.equal(splitDisplayedAmount(rows[0].parts), 150)
  assert.equal(adjustmentsForEntry(rows[0], 1).length, 1)
  assert.equal(adjustmentsForEntry(rows[0], 2).length, 0)
  assert.equal(splitAdjustmentLabel(rows[0]), '1개 수단 부분 환불')
})

test('history flag without visible records and mixed reverts read as generic reverted', () => {
  const hidden = summarizeLedgerAdjustments({ hasAdjustmentHistory: true }, [])
  assert.equal(hidden.label, '처리 되돌림')
  assert.equal(hidden.hiddenHistory, true)
  const mixed = summarizeLedgerAdjustments({ hasAdjustmentHistory: true }, [
    { id: 1, adjustmentType: 'PAYMENT_CANCELLATION', adjustmentVoidedAt: 'x' },
    { id: 2, adjustmentType: 'REFUND', adjustmentVoidedAt: 'x' },
  ])
  assert.equal(mixed.label, '처리 되돌림')
  assert.equal(summarizeLedgerAdjustments({ hasAdjustmentHistory: true }, [{ id: 2, adjustmentType: 'REFUND', adjustmentVoidedAt: 'x' }]).label, '환불 되돌림')
})

test('single entry status labels', () => {
  assert.equal(ledgerEntryStatusLabel({ adjustmentType: 'REFUND', adjustmentVoidedAt: 'x' }), '환불 입금 · 되돌림')
  assert.equal(ledgerEntryStatusLabel({ adjustmentType: 'PAYMENT_CANCELLATION' }), '결제 취소 기록')
  assert.equal(ledgerEntryStatusLabel({ id: 1 }), '')
})
