// Display metadata travels in the existing, unique per-image clientRequestId.
// No batch API, new database column, or shared AI inference request is needed.
export function createReceiptBatchRequestIds(count, { timestamp = Date.now(), token } = {}) {
  const batchToken = token || globalThis.crypto?.randomUUID?.() || Math.random().toString(36).slice(2)
  return Array.from({ length: count }, (_, index) => `image-analysis-batch-${timestamp}-${batchToken}--${index + 1}-${count}`)
}

export function parseReceiptBatchRequestId(value) {
  const match = /^image-analysis-batch-(\d{10,16})-([a-z0-9-]+)--(\d+)-(\d+)$/.exec(String(value || ''))
  if (!match) return null
  const timestamp = Number(match[1]), position = Number(match[3]), totalCount = Number(match[4])
  if (!Number.isFinite(new Date(timestamp).getTime()) || !Number.isSafeInteger(totalCount) || totalCount < 1
    || !Number.isSafeInteger(position) || position < 1 || position > totalCount) return null
  return { key: `batch-${match[1]}-${match[2]}`, timestamp, position, totalCount }
}

export function groupReceiptHistoryRequests(histories, { getDateKey, getDateLabel }) {
  const days = new Map()
  for (const [index, history] of histories.entries()) {
    const batch = parseReceiptBatchRequestId(history.clientRequestId || history.result?.clientRequestId)
    const dateKey = getDateKey(history, batch)
    if (!days.has(dateKey)) days.set(dateKey, { key: dateKey, label: getDateLabel(dateKey), items: [], batches: new Map() })
    const day = days.get(dateKey)
    day.items.push(history)
    // Never guess old batches from adjacent timestamps, filenames, or IDs.
    const key = batch?.key || `individual-${history.id ?? index}`
    if (!day.batches.has(key)) day.batches.set(key, {
      key, timestamp: batch?.timestamp || Date.parse(history.createdAt || history.requestedAt || ''),
      totalCount: batch?.totalCount || 1, isLegacy: !batch, items: [],
    })
    const group = day.batches.get(key)
    group.items.push(history)
    group.totalCount = Math.max(group.totalCount, batch?.totalCount || 1, group.items.length)
  }
  return [...days.values()].map(day => ({ ...day, batches: [...day.batches.values()]
    .sort((a, b) => (b.timestamp || 0) - (a.timestamp || 0))
    .map(batch => ({ ...batch, items: batch.items.slice().sort((a, b) => {
      const left = parseReceiptBatchRequestId(a.clientRequestId || a.result?.clientRequestId)
      const right = parseReceiptBatchRequestId(b.clientRequestId || b.result?.clientRequestId)
      return (left?.position || 0) - (right?.position || 0)
    }) })) }))
    .sort((a, b) => a.key === b.key ? 0 : a.key === 'unknown' ? 1 : b.key === 'unknown' ? -1 : b.key.localeCompare(a.key))
}
