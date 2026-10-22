import test from 'node:test'
import assert from 'node:assert/strict'
import { createReceiptBatchRequestIds, createReceiptSelectionRequestIds, groupReceiptHistoryRequests, parseReceiptBatchRequestId } from '../../src/lib/receiptHistoryGroups.js'

const options = {
  getDateKey: (history, batch) => batch ? new Date(batch.timestamp).toISOString().slice(0, 10) : history.createdAt?.slice(0, 10) || 'unknown',
  getDateLabel: key => key,
}
function batch(count, timestamp, token, start = 1) {
  return createReceiptBatchRequestIds(count, { timestamp, token }).map((clientRequestId, index) => ({ id: start + index, clientRequestId, createdAt: '2026-10-20T12:00:00', status: 'COMPLETED' }))
}

test('one date displays distinct 5, 4, 1 request groups without changing source histories', () => {
  const time = Date.parse('2026-10-20T09:00:00Z')
  const rows = [...batch(5, time + 2000, 'five'), ...batch(4, time + 1000, 'four', 6), ...batch(1, time, 'one', 10)]
  const before = JSON.stringify(rows)
  const [day] = groupReceiptHistoryRequests(rows, options)
  assert.deepEqual(day.batches.map(group => group.totalCount), [5, 4, 1])
  assert.deepEqual(day.batches.map(group => group.items.length), [5, 4, 1])
  assert.equal(JSON.stringify(rows), before)
})

test('unique image ids preserve batch order, stay under 120 characters, and survive JSON reload', () => {
  const rows = batch(6, Date.parse('2026-10-20T09:00:00Z'), '12345678-1234-1234-1234-123456789abc')
  assert.equal(new Set(rows.map(row => row.clientRequestId)).size, 6)
  assert.ok(rows.every(row => row.clientRequestId.length <= 120))
  const [day] = groupReceiptHistoryRequests(JSON.parse(JSON.stringify(rows.reverse())), options)
  assert.deepEqual(day.batches[0].items.map(row => row.id), [1, 2, 3, 4, 5, 6])
})

test('pagination and midnight do not turn a partial batch into a smaller or new request', () => {
  const rows = batch(6, Date.parse('2026-10-20T23:59:00Z'), 'midnight')
  rows[5].createdAt = '2026-10-21T00:10:00'
  const [day] = groupReceiptHistoryRequests(rows.slice(4), options)
  assert.equal(day.key, '2026-10-20')
  assert.equal(day.batches[0].totalCount, 6)
  assert.equal(day.batches[0].items.length, 2)
})

test('adjacent legacy timestamps and the same filenames never fabricate a shared batch', () => {
  const rows = [1, 2].map(id => ({ id, createdAt: '2026-10-20T12:00:00', fileName: 'same.png', clientRequestId: `image-analysis-1790000000000-${id}` }))
  const [day] = groupReceiptHistoryRequests(rows, options)
  assert.equal(day.batches.length, 2)
  assert.ok(day.batches.every(group => group.isLegacy && group.totalCount === 1))
  assert.equal(parseReceiptBatchRequestId('image-analysis-batch-1790000000000-token--0-6'), null)
  assert.equal(parseReceiptBatchRequestId('image-analysis-batch-1790000000000-token--7-6'), null)
})

test('retries retain their original date, batch and positions when the same histories are updated later', () => {
  const rows = batch(3, Date.parse('2026-10-20T11:34:12Z'), 'retries')
  rows[1].status = 'FAILED'
  rows[2].status = 'FAILED'
  const before = groupReceiptHistoryRequests(rows, options)[0]
  for (const row of rows.slice(1)) Object.assign(row, { status: 'COMPLETED', updatedAt: '2026-10-22T12:00:00', summary: 'retried result' })
  const after = groupReceiptHistoryRequests(rows, options)[0]
  assert.equal(after.key, before.key)
  assert.equal(after.batches.length, 1)
  assert.equal(after.batches[0].key, before.batches[0].key)
  assert.equal(after.batches[0].totalCount, 3)
  assert.deepEqual(after.batches[0].items.map(row => row.id), [1, 2, 3])
})

test('new selections split their history IDs into unique groups of at most five without changing older batches', () => {
  const ids = createReceiptSelectionRequestIds(12, { timestamp: Date.parse('2026-10-20T09:00:00Z'), token: '12345678-1234-1234-1234-123456789abc' })
  const parsed = ids.map(parseReceiptBatchRequestId)
  assert.equal(new Set(ids).size, 12)
  assert.ok(ids.every(id => id.length <= 120))
  assert.deepEqual(parsed.map(batch => batch.totalCount), [5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 2, 2])
  assert.deepEqual(parsed.map(batch => batch.position), [1, 2, 3, 4, 5, 1, 2, 3, 4, 5, 1, 2])
  assert.equal(new Set(parsed.map(batch => batch.key)).size, 3)
  const [day] = groupReceiptHistoryRequests(ids.map((clientRequestId, index) => ({ id: index + 1, clientRequestId })), options)
  assert.deepEqual(day.batches.map(batch => batch.totalCount), [5, 5, 2])
  const old = createReceiptBatchRequestIds(12, { token: 'legacy' }).map(parseReceiptBatchRequestId)
  assert.equal(new Set(old.map(batch => batch.key)).size, 1)
  assert.ok(old.every(batch => batch.totalCount === 12))
  assert.deepEqual(createReceiptSelectionRequestIds(0), [])
})
