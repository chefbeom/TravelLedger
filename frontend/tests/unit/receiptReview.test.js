import test from 'node:test'
import assert from 'node:assert/strict'
import { countPendingReceiptReviews, pendingReceiptReviewIds, unreadReceiptReviewIds, receiptReviewPage, selectReceiptAnalysisItems, summarizeReceiptBatch } from '../../src/lib/receiptReview.js'

test('batch progress counts only settled requests and separates failure and cancellation', () => {
  const items = Array.from({ length: 10 }, () => ({ status: 'queued' }))
  assert.deepEqual(summarizeReceiptBatch(items), { total: 10, done: 0, failed: 0, cancelled: 0, settled: 0, remaining: 10, percent: 0 })
  items[0].status = 'done'
  items[1].status = 'analyzing'
  assert.deepEqual(summarizeReceiptBatch(items), { total: 10, done: 1, failed: 0, cancelled: 0, settled: 1, remaining: 9, percent: 10 })
  items[1].status = 'error'
  items[2].status = 'cancelled'
  for (const item of items.slice(3)) item.status = 'done'
  assert.deepEqual(summarizeReceiptBatch(items), { total: 10, done: 8, failed: 1, cancelled: 1, settled: 10, remaining: 0, percent: 100 })
  assert.equal(summarizeReceiptBatch([]).percent, 0)
})

test('pending badge counts requests across history pages and excludes fully used results', () => {
  const requests = [{ analysisId: 90, entryCount: 3, approvedEntryIndexes: [0] }, { analysisId: 91, entryCount: 1, approvedEntryIndexes: [] }]
  const markers = [{ analysisId: 90, entryIndex: 1 }, { analysisId: 90, entryIndex: 1 }, { analysisId: 91, entryIndex: 0 }]
  assert.equal(countPendingReceiptReviews(requests, [], [], markers), 1)
  assert.equal(countPendingReceiptReviews(requests, [], [], [...markers, { analysisId: 90, entryIndex: 2 }]), 0)
  const histories = [{ id: 92, status: 'COMPLETED', result: { suggestedEntries: [{}], approvedEntryIndexes: [0] } }, { id: 93, status: 'FAILED' }]
  assert.equal(countPendingReceiptReviews(requests, histories, [], markers), 1)
  assert.equal(countPendingReceiptReviews([], [], [{ analysisId: 1, status: 'done', suggestedEntries: [{}, {}] }]), 1)
  assert.equal(countPendingReceiptReviews([], [{ id: 2, status: 'COMPLETED', result: {} }]), 0)
  assert.equal(countPendingReceiptReviews([{ analysisId: 3, entryCount: 1, approvedEntryIndexes: null }], [{ id: 3, status: 'COMPLETED', result: null }], null, null), 1)
  assert.equal(countPendingReceiptReviews([], [{ id: 4, status: 'COMPLETED', result: { suggestedEntry: {}, approvedEntryIndexes: null } }]), 1)
})

test('all six selected images remain in order as their statuses change', () => {
  const items = Array.from({ length: 6 }, (_, index) => ({ id: `image-${index}`, status: index < 3 ? 'done' : 'queued', fileName: 'same-name.png' }))
  const ids = items.map(item => item.id)
  assert.deepEqual(selectReceiptAnalysisItems(items, ids), items)
  items[3].status = 'analyzing'
  items[4].status = 'error'
  items[5].status = 'cancelled'
  assert.equal(selectReceiptAnalysisItems(items, ids).length, 6)
  assert.deepEqual(selectReceiptAnalysisItems([...items, { id: 'old', status: 'done' }, { id: 'history', fromHistory: true }], ids), items)
  assert.equal(selectReceiptAnalysisItems(items.filter(item => item.id !== ids[2]), ids).length, 5)
})

test('read notifications are independent of approval and track new completions by request ID', () => {
  const requests = [{ analysisId: 90, entryCount: 1 }, { analysisId: 91, entryCount: 2 }]
  const ids = pendingReceiptReviewIds(requests)
  assert.deepEqual(ids, ['90', '91'])
  assert.deepEqual(unreadReceiptReviewIds(ids, []), ids)
  assert.deepEqual(unreadReceiptReviewIds(ids, ids), [])
  assert.equal(countPendingReceiptReviews(requests), 2)
  assert.deepEqual(unreadReceiptReviewIds([...ids, '92', '92'], ids), ['92'])
  assert.deepEqual(unreadReceiptReviewIds(ids, [90]), ['91'])
  assert.deepEqual(unreadReceiptReviewIds(ids, null), ids)
})

test('candidate pagination bounds forms but retains original indexes and editable references', () => {
  const entries = Array.from({ length: 83 }, (_, index) => ({ title: `entry-${index}` }))
  const first = receiptReviewPage(entries)
  assert.equal(first.entries.length, 10)
  assert.equal(first.totalPages, 9)
  first.entries[0].entry.title = 'edited'
  assert.equal(receiptReviewPage(entries, 0).entries[0].entry.title, 'edited')
  assert.equal(receiptReviewPage(entries, 1).entries[0].entryIndex, 10)
  const last = receiptReviewPage(entries, 999)
  assert.equal(last.page, 8)
  assert.deepEqual(last.entries.map(item => item.entryIndex), [80, 81, 82])
  assert.equal(receiptReviewPage(entries, -3).page, 0)
  assert.deepEqual(receiptReviewPage([]).entries, [])
})
