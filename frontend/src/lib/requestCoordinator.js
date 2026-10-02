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
    let promise = pending.get(key)
    if (!promise) {
      promise = Promise.resolve().then(() => performRequest(path, options))
      pending.set(key, promise)
    }
    try {
      const response = await promise
      // Widgets may modify their DTOs; sharing the network request must not share mutable state.
      return response == null ? response : structuredClone(response)
    } finally {
      if (pending.get(key) === promise) pending.delete(key)
    }
  }
}
