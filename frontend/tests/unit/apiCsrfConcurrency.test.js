import test from 'node:test'
import assert from 'node:assert/strict'
import { createPaymentMethod } from '../../src/lib/api.js'

test('parallel writes share the initial CSRF fetch and respect a subsequently rotated cookie', async () => {
  const originalFetch = globalThis.fetch, originalDocument = globalThis.document
  let release, csrfReads = 0
  const gate = new Promise(resolve => { release = resolve }), headers = []
  globalThis.document = { cookie: '' }
  globalThis.fetch = async (url, options) => {
    if (url.endsWith('/auth/csrf')) {
      csrfReads++
      await gate
      document.cookie = 'XSRF-TOKEN=initial-token'
      return Response.json({ token: 'initial-token' })
    }
    headers.push(options.headers['X-XSRF-TOKEN'])
    return Response.json({ ok: true })
  }
  try {
    const writes = Array.from({ length: 6 }, () => createPaymentMethod({ name: 'test' }))
    assert.equal(csrfReads, 1)
    assert.equal(headers.length, 0)
    release()
    await Promise.all(writes)
    assert.deepEqual(headers, Array(6).fill('initial-token'))
    document.cookie = 'XSRF-TOKEN=rotated-token'
    await createPaymentMethod({ name: 'next' })
    assert.equal(headers.at(-1), 'rotated-token')
    assert.equal(csrfReads, 1)
  } finally {
    release()
    globalThis.fetch = originalFetch
    if (originalDocument === undefined) delete globalThis.document
    else globalThis.document = originalDocument
  }
})

test('a failed CSRF fetch rejects the current writes but does not poison the next attempt', async () => {
  const originalFetch = globalThis.fetch, originalDocument = globalThis.document
  let reads = 0
  globalThis.document = { cookie: '' }
  globalThis.fetch = async url => url.endsWith('/auth/csrf')
    ? ++reads === 1 ? new Response('', { status: 503 }) : Response.json({ token: 'retry-token' })
    : Response.json({ ok: true })
  try {
    const results = await Promise.allSettled(Array.from({ length: 6 }, () => createPaymentMethod({ name: 'test' })))
    assert.ok(results.every(result => result.status === 'rejected'))
    assert.equal(reads, 1)
    assert.deepEqual(await createPaymentMethod({ name: 'retry' }), { ok: true })
    assert.equal(reads, 2)
  } finally {
    globalThis.fetch = originalFetch
    if (originalDocument === undefined) delete globalThis.document
    else globalThis.document = originalDocument
  }
})
