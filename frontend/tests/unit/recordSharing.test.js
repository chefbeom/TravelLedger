import test from 'node:test'
import assert from 'node:assert/strict'
import { buildSharedLedgerDraft, buildSharedLedgerPayload } from '../../src/lib/recordSharing.js'

const snapshot = { entryDate: '2026-10-03', title: '식사', memo: '상세 내역', amount: 10000, entryType: 'EXPENSE', categoryGroupId: 1, categoryGroupName: '식비', categoryDetailId: 2, categoryDetailName: '외식', paymentMethodId: 3, paymentMethodName: '카드', travelPlanId: 400, travelRecordId: 500 }
test('shared ledger uses receiver taxonomy by matching names, never sender IDs', () => {
  const draft = buildSharedLedgerDraft(snapshot, [{ id: 101, name: '식비', entryType: 'EXPENSE', details: [{ id: 102, name: '외식' }] }], [{ id: 103, name: '카드' }])
  assert.equal(draft.categoryGroupId, 101)
  assert.equal(draft.categoryDetailId, 102)
  assert.equal(draft.paymentMethodId, 103)
  draft.title = '  저녁 식사  '
  const payload = buildSharedLedgerPayload(draft)
  assert.equal(payload.title, '저녁 식사')
  assert.equal(payload.travelPlanId, null)
  assert.equal(payload.travelRecordId, null)
  assert.equal(snapshot.title, '식사')
})
test('missing recipient catalogs require explicit selection rather than importing foreign IDs', () => {
  const draft = buildSharedLedgerDraft(snapshot, [], [])
  assert.equal(draft.categoryGroupId, '')
  assert.equal(draft.paymentMethodId, '')
})
test('income has no expense payment method and foreign exchange values survive confirmation', () => {
  const draft = buildSharedLedgerDraft({ ...snapshot, entryType: 'INCOME', foreignCurrencyCode: 'USD', foreignAmount: 10, exchangeRateToKrw: 1300 }, [{ id: 201, name: '수입', entryType: 'INCOME' }], [{ id: 3, name: '카드' }])
  const payload = buildSharedLedgerPayload(draft)
  assert.equal(payload.paymentMethodId, null)
  assert.equal(payload.foreignCurrencyCode, 'USD')
  assert.equal(payload.foreignAmount, 10)
  assert.equal(payload.exchangeRateToKrw, 1300)
})
