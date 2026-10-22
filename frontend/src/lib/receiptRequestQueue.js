export const RECEIPT_MAX_CONCURRENCY = 20
export const RECEIPT_DEFAULT_CONCURRENCY = 6

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
