import test from 'node:test'
import assert from 'node:assert/strict'
import { createReceiptBatchRequestIds } from '../../src/lib/receiptHistoryGroups.js'
import { formatReceiptHistoryRequestTime, normalizeReceiptHistoryView, receiptHistoryRequestTimestamp } from '../../src/lib/receiptHistoryView.js'

test('history defaults to the current list and accepts only the supported block sizes', () => {
  assert.deepEqual(normalizeReceiptHistoryView(), { mode: 'list', size: 'medium' })
  assert.deepEqual(normalizeReceiptHistoryView({ mode: 'blocks', size: 'large' }), { mode: 'blocks', size: 'large' })
  assert.equal(normalizeReceiptHistoryView({ mode: 'blocks', size: 'small' }).size, 'small')
  assert.deepEqual(normalizeReceiptHistoryView({ mode: 'invalid', size: 'huge' }), { mode: 'list', size: 'medium' })
  assert.deepEqual(normalizeReceiptHistoryView(null), { mode: 'list', size: 'medium' })
})

test('list time uses the file request time, with batch metadata only as a fallback', () => {
  const timestamp = Date.parse('2026-10-20T11:00:00+09:00')
  const [clientRequestId] = createReceiptBatchRequestIds(6, { timestamp, token: 'time-test' })
  const createdAt = '2026-10-20T11:03:42+09:00'
  assert.equal(receiptHistoryRequestTimestamp({ clientRequestId, createdAt }), Date.parse(createdAt))
  assert.equal(receiptHistoryRequestTimestamp({ clientRequestId }), timestamp)
  assert.equal(receiptHistoryRequestTimestamp({ requestedAt: createdAt }), Date.parse(createdAt))
  assert.equal(receiptHistoryRequestTimestamp({ result: { clientRequestId } }), timestamp)
  assert.equal(receiptHistoryRequestTimestamp({ createdAt: 'invalid', clientRequestId }), timestamp)
  assert.equal(receiptHistoryRequestTimestamp({ createdAt: '2026-10-20', completedAt: createdAt, updatedAt: createdAt }), null)
  assert.equal(formatReceiptHistoryRequestTime({ createdAt: 'invalid' }), '시간 정보 없음')
  assert.equal(formatReceiptHistoryRequestTime({ createdAt }), new Intl.DateTimeFormat('ko-KR', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).format(new Date(createdAt)))
})
