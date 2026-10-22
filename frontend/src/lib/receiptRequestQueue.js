export const RECEIPT_MAX_CONCURRENCY = 20
export const RECEIPT_DEFAULT_CONCURRENCY = 3
export const RECEIPT_MAX_REQUEST_BATCH_SIZE = 5

export function splitReceiptRequestBatches(items) {
  const batches = []
  for (let offset = 0; offset < items.length; offset += RECEIPT_MAX_REQUEST_BATCH_SIZE) {
    batches.push(items.slice(offset, offset + RECEIPT_MAX_REQUEST_BATCH_SIZE))
  }
  return batches
}

// Finish all five (including async polling) before admitting the next batch.
// The administrator limit may be refreshed between batches; retries keep their existing IDs.
export async function runReceiptRequestBatches(items, concurrency, worker, { onBatchStart = () => {}, shouldContinue = () => true } = {}) {
  const batches = splitReceiptRequestBatches(items)
  const results = []
  for (let index = 0; index < batches.length; index++) {
    if (!shouldContinue()) break
    const limit = typeof concurrency === 'function' ? await concurrency() : concurrency
    if (!shouldContinue()) break
    const batch = batches[index]
    onBatchStart({ currentBatch: index + 1, totalBatches: batches.length, batchSize: batch.length })
    results.push(...await runReceiptRequestQueue(batch, limit,
      (item, position) => worker(item, index * RECEIPT_MAX_REQUEST_BATCH_SIZE + position)))
  }
  return results
}

export function normalizeReceiptConcurrency(value) {
  const number = Number(value)
  return Number.isFinite(number) && number >= 1
    ? Math.min(RECEIPT_MAX_CONCURRENCY, Math.floor(number)) : RECEIPT_DEFAULT_CONCURRENCY
}

// A slot covers upload AND asynchronous result polling, not merely the POST ack.
// Jobs and results retain input order even when they finish out of order.
export async function runReceiptRequestQueue(items, concurrency, worker) {
  const queue = [...items]
  const results = new Array(queue.length)
  let next = 0
  async function consume() {
    while (next < queue.length) {
      const index = next++
      try {
        results[index] = { status: 'fulfilled', value: await worker(queue[index], index) }
      } catch (reason) {
        results[index] = { status: 'rejected', reason }
      }
    }
  }
  await Promise.all(Array.from({ length: Math.min(queue.length, normalizeReceiptConcurrency(concurrency)) }, consume))
  return results
}
