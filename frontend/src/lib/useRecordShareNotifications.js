import { onBeforeUnmount, onMounted, ref } from 'vue'
import { fetchRecordShareCounts } from './api'

export function useRecordShareNotifications() {
  const counts = ref({ ledger: 0, travel: 0 })
  let timer
  let disposed = false
  async function refresh() {
    if (document.hidden || disposed) return
    try {
      const result = await fetchRecordShareCounts()
      if (!disposed) counts.value = result
    } catch { /* Background badge failures must not interrupt the workspace. */ }
  }
  onMounted(() => {
    refresh()
    timer = setInterval(refresh, 60000)
    window.addEventListener('focus', refresh)
    window.addEventListener('record-shares-changed', refresh)
    document.addEventListener('visibilitychange', refresh)
  })
  onBeforeUnmount(() => {
    disposed = true
    clearInterval(timer)
    window.removeEventListener('focus', refresh)
    window.removeEventListener('record-shares-changed', refresh)
    document.removeEventListener('visibilitychange', refresh)
  })
  return { shareCounts: counts, refreshShareCounts: refresh }
}
