// Remember-me tokens rotate on use. Do not send startup requests concurrently
// with restoration, and serialize restoration across tabs when Web Locks exist.
export async function restoreAuthentication(restore, locks = globalThis.navigator?.locks) {
  if (!locks?.request) return restore()
  let started = false
  try {
    return await locks.request('calen-authentication-restore', () => {
      started = true
      return restore()
    })
  } catch (error) {
    if (!started) return restore()
    throw error
  }
}

export async function bootstrapAuthentication(restore, loadOptions, locks) {
  await restoreAuthentication(restore, locks)
  await loadOptions()
}
