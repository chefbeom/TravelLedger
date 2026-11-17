import test from 'node:test'
import assert from 'node:assert/strict'
import { bootstrapAuthentication, restoreAuthentication } from '../../src/lib/sessionBootstrap.js'

test('startup options wait until remember-me restoration completes', async () => {
  const events = []
  let finish
  const pending = new Promise(resolve => { finish = resolve })
  const task = bootstrapAuthentication(async () => { events.push('restore'); await pending; events.push('restored') },
    async () => { events.push('options') }, null)
  await Promise.resolve()
  assert.deepEqual(events, ['restore'])
  finish()
  await task
  assert.deepEqual(events, ['restore', 'restored', 'options'])
})

test('restoration uses a shared browser lock and falls back if unavailable', async () => {
  const calls = []
  const locks = { request: async (name, callback) => { calls.push(name); return callback() } }
  assert.equal(await restoreAuthentication(() => 42, locks), 42)
  assert.deepEqual(calls, ['calen-authentication-restore'])
  assert.equal(await restoreAuthentication(() => 7, { request: async () => { throw new Error('unavailable') } }), 7)
  let callsToRestore = 0
  await assert.rejects(restoreAuthentication(() => { callsToRestore++; throw new Error('failed') }, locks), /failed/)
  assert.equal(callsToRestore, 1)
})
