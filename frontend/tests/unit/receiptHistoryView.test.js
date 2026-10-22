import test from 'node:test'
import assert from 'node:assert/strict'
import { createReceiptBatchRequestIds } from '../../src/lib/receiptHistoryGroups.js'
import { formatReceiptHistoryRequestTime, normalizeReceiptHistoryView, receiptHistoryRequestTimestamp, receiptHistoryTileSummary } from '../../src/lib/receiptHistoryView.js'

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

test('square album captions show analysis state or a short result instead of filenames', () => {
  assert.equal(receiptHistoryTileSummary({ status: 'PROCESSING', summary: 'not finished' }), '분석 중')
  assert.equal(receiptHistoryTileSummary({ status: 'FAILED' }), '분석 실패')
  assert.equal(receiptHistoryTileSummary({ status: 'CANCELLED' }), '취소됨')
  assert.equal(receiptHistoryTileSummary({}), '상태 확인 필요')
  assert.equal(receiptHistoryTileSummary({ status: 'COMPLETED' }), '분석 완료')
  assert.equal(receiptHistoryTileSummary({ status: 'COMPLETED', summary: '짧은 요약' }), '짧은 요약')
  assert.equal(receiptHistoryTileSummary({ status: 'COMPLETED', summary: 'long', result: { suggestedEntries: [{ title: '편의점\n 결제' }, {}] } }), '편의점 결제 외 1건')
  assert.equal(receiptHistoryTileSummary({ status: 'COMPLETED', result: { suggestedEntry: { title: '은행 입금' } } }), '은행 입금')
  assert.equal(receiptHistoryTileSummary({ status: 'COMPLETED', summary: '🧾'.repeat(40) }), `${'🧾'.repeat(28)}…`)
})
