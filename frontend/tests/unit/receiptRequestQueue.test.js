import test from 'node:test'
import assert from 'node:assert/strict'
import { normalizeReceiptConcurrency, runReceiptRequestQueue, runReceiptRequestBatches, splitReceiptRequestBatches } from '../../src/lib/receiptRequestQueue.js'

const deferred = () => {
  let resolve
  const promise = new Promise(done => { resolve = done })
  return { promise, resolve }
}
const tick = () => new Promise(resolve => setImmediate(resolve))

test('receipt concurrency defaults to three and is bounded to the administrator safety range', () => {
  for (const value of [undefined, null, 0, -1, 'bad', Infinity]) assert.equal(normalizeReceiptConcurrency(value), 3)
  assert.equal(normalizeReceiptConcurrency('1'), 1)
  assert.equal(normalizeReceiptConcurrency(6.9), 6)
  assert.equal(normalizeReceiptConcurrency(100), 20)
})

test('queue refills a freed slot immediately and preserves input-result association despite failures', async () => {
  const gates = Array.from({ length: 8 }, deferred), started = [], completed = []
  let active = 0, peak = 0
  const execution = runReceiptRequestQueue(gates, 3, async (gate, index) => {
    started.push(index)
    peak = Math.max(peak, ++active)
    try {
      await gate.promise
      completed.push(index)
      if (index === 2) throw new Error('one image failed')
      return `image-${index}`
    } finally { active-- }
  })
  assert.deepEqual(started, [0, 1, 2])
  gates[2].resolve()
  await tick()
  assert.deepEqual(started, [0, 1, 2, 3])
  assert.deepEqual(completed, [2])
  for (const gate of gates) gate.resolve()
  const results = await execution
  assert.equal(peak, 3)
  assert.equal(results[2].status, 'rejected')
  assert.equal(results[2].reason.message, 'one image failed')
  assert.equal(results[7].value, 'image-7')
  assert.equal(results.filter(result => result.status === 'fulfilled').length, 7)
  assert.equal(active, 0)
})

test('queue never exceeds twenty and safely handles empty input and cancelled queued jobs', async () => {
  const gate = deferred(), started = []
  const execution = runReceiptRequestQueue(Array.from({ length: 25 }, (_, id) => ({ id })), 99, async item => {
    started.push(item.id)
    await gate.promise
  })
  assert.equal(started.length, 20)
  gate.resolve()
  await execution
  assert.equal(started.length, 25)
  assert.deepEqual(await runReceiptRequestQueue([], 6, () => assert.fail()), [])
  const items = [{ cancelled: false }, { cancelled: true }], calls = []
  await runReceiptRequestQueue(items, 1, async (item, index) => { if (!item.cancelled) calls.push(index) })
  assert.deepEqual(calls, [0])
})

test('twelve images form 5/5/2 batches, use three concurrent slots and wait for every result before the next batch', async () => {
  const gates = Array.from({ length: 12 }, deferred), started = [], batches = []
  let active = 0, peak = 0
  const execution = runReceiptRequestBatches(gates, 3, async (gate, index) => {
    started.push(index)
    peak = Math.max(peak, ++active)
    try { await gate.promise; if (index === 4) throw new Error('fixture failed'); return index }
    finally { active-- }
  }, { onBatchStart: batch => batches.push(batch) })
  assert.deepEqual(started, [0, 1, 2])
  gates[0].resolve(); gates[1].resolve()
  await tick()
  assert.deepEqual(started, [0, 1, 2, 3, 4])
  gates[3].resolve(); gates[4].resolve()
  await tick()
  assert.equal(started.length, 5, 'one slow first-batch image must block the second batch')
  gates[2].resolve()
  await tick()
  assert.deepEqual(started, [0, 1, 2, 3, 4, 5, 6, 7])
  for (const gate of gates) gate.resolve()
  const results = await execution
  assert.equal(peak, 3)
  assert.deepEqual(batches.map(batch => batch.batchSize), [5, 5, 2])
  assert.deepEqual(batches.map(batch => [batch.currentBatch, batch.totalBatches]), [[1, 3], [2, 3], [3, 3]])
  assert.equal(results[4].status, 'rejected')
  assert.equal(results[11].value, 11)
  assert.equal(results.length, 12)
})

test('batch policy is refreshed for each group and cannot run more than five even with an administrator limit of twenty', async () => {
  const gates = Array.from({ length: 7 }, deferred), started = [], limits = [20, 1]
  let reads = 0
  const execution = runReceiptRequestBatches(gates, async () => limits[reads++], async (gate, index) => {
    started.push(index); await gate.promise
  })
  await tick()
  assert.deepEqual(started, [0, 1, 2, 3, 4])
  for (const gate of gates.slice(0, 5)) gate.resolve()
  await tick()
  assert.deepEqual(started, [0, 1, 2, 3, 4, 5])
  assert.equal(reads, 2)
  gates[5].resolve()
  await tick()
  assert.equal(started.at(-1), 6)
  gates[6].resolve()
  await execution
})

test('a later policy failure or cancelled execution never dispatches the remaining batches', async () => {
  let reads = 0
  const started = []
  await assert.rejects(runReceiptRequestBatches(Array.from({ length: 6 }, (_, index) => index), async () => {
    if (++reads === 2) throw new Error('settings unavailable')
    return 3
  }, async item => started.push(item)), /settings unavailable/)
  assert.deepEqual(started, [0, 1, 2, 3, 4])
  let active = true
  const stopped = []
  await runReceiptRequestBatches(Array.from({ length: 12 }, (_, index) => index), 3, async item => {
    stopped.push(item); active = false
  }, { shouldContinue: () => active })
  assert.deepEqual(stopped, [0, 1, 2, 3, 4])
  assert.deepEqual(await runReceiptRequestBatches([], 3, () => assert.fail()), [])
  assert.deepEqual(splitReceiptRequestBatches([1, 2, 3, 4, 5, 6]), [[1, 2, 3, 4, 5], [6]])
})
