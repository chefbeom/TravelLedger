import test from 'node:test'
import assert from 'node:assert/strict'
import { normalizeReceiptConcurrency, runReceiptRequestQueue } from '../../src/lib/receiptRequestQueue.js'

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
