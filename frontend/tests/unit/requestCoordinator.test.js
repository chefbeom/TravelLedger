import test from 'node:test'
import assert from 'node:assert/strict'
import { createRequestCoordinator } from '../../src/lib/requestCoordinator.js'

function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

test('simultaneous GETs share one request but receive independent objects', async () => {
  const gate = deferred()
  let calls = 0
  const request = createRequestCoordinator(() => { calls++; return gate.promise })
  const first = request('/dashboard')
  const second = request('/dashboard')
  await Promise.resolve()
  assert.equal(calls, 1)
  gate.resolve({ rows: [{ amount: 1 }] })
  const [a, b] = await Promise.all([first, second])
  a.rows[0].amount = 9
  assert.equal(b.rows[0].amount, 1)
  await request('/dashboard')
  assert.equal(calls, 2)
})

test('writes invalidate outstanding reads before and after completion', async () => {
  const gates = []
  const request = createRequestCoordinator(() => {
    const gate = deferred(); gates.push(gate); return gate.promise
  })
  const old = request('/dashboard')
  await Promise.resolve()
  const write = request('/entries', { method: 'POST' })
  const during = request('/dashboard')
  gates[1].resolve(null)
  await write
  const fresh = request('/dashboard')
  await Promise.resolve()
  assert.equal(gates.length, 4)
  gates[0].resolve({ version: 0 })
  gates[2].resolve({ version: 1 })
  gates[3].resolve({ version: 2 })
  assert.equal((await fresh).version, 2)
  await Promise.all([old, during])
})

test('failure is removed and cancellable requests are independent', async () => {
  let calls = 0
  const request = createRequestCoordinator(async () => {
    calls++
    if (calls === 1) throw new Error('offline')
    return null
  })
  await assert.rejects(request('/dashboard'), /offline/)
  await request('/dashboard')
  const signal = new AbortController().signal
  await Promise.all([request('/dashboard', { signal }), request('/dashboard', { signal })])
  assert.equal(calls, 4)
})
