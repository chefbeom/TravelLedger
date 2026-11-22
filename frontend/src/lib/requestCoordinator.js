// Coalesce only simultaneous plain GETs. Completed responses are never cached.
export function createRequestCoordinator(performRequest) {
  const pending = new Map()
  let generation = 0
  let mutations = 0

  function invalidate() {
    generation += 1
    pending.clear()
  }

  return async function request(path, options = {}) {
    const method = String(options.method || 'GET').toUpperCase()
    const writes = !['GET', 'HEAD', 'OPTIONS', 'TRACE'].includes(method)
    if (writes) {
      mutations += 1
      invalidate()
      try {
        return await performRequest(path, options)
      } finally {
        mutations -= 1
        invalidate()
      }
    }
    const plainGet = method === 'GET' && mutations === 0
      && Object.keys(options).every((key) => key === 'method')
    if (!plainGet) return performRequest(path, options)

    const key = `${generation}:${path}`
    let entry = pending.get(key)
    if (!entry) {
      entry = { promise: Promise.resolve().then(() => performRequest(path, options)), waiters: 0 }
      pending.set(key, entry)
    }
    entry.waiters += 1
    try {
      const response = await entry.promise
      // A response is only shared while it is in flight; later calls start a new request.
      if (pending.get(key) === entry) pending.delete(key)
      entry.waiters -= 1
      if (response == null) return response
      // Widgets may modify their DTOs; sharing the network request must not share mutable state.
      // The last caller to resume receives the original, so a single caller never pays for a clone.
      return entry.waiters === 0 ? response : structuredClone(response)
    } catch (error) {
      entry.waiters -= 1
      if (pending.get(key) === entry) pending.delete(key)
      throw error
    }
  }
}
