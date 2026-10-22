export const RECEIPT_REVIEW_PAGE_SIZE = 10

// Failed and cancelled requests are settled work, not successful analyses.
export function summarizeReceiptBatch(items = []) {
  const counts = { total: items.length, done: 0, failed: 0, cancelled: 0 }
  for (const item of items) {
    if (item.status === 'done') counts.done++
    else if (item.status === 'error') counts.failed++
    else if (item.status === 'cancelled') counts.cancelled++
  }
  const settled = counts.done + counts.failed + counts.cancelled
  return { ...counts, settled, remaining: counts.total - settled,
    percent: counts.total ? Math.round(settled / counts.total * 100) : 0 }
}

// Count requests, not transactions. A partially used request remains pending.
export function countPendingReceiptReviews(requests = [], histories = [], items = [], appliedMarkers = []) {
  const list = value => Array.isArray(value) ? value : []
  const byId = new Map(list(requests).filter(request => request?.analysisId).map(request => [String(request.analysisId), request]))
  const merge = (id, total, approved = []) => {
    if (!id) return
    const key = String(id)
    const previous = byId.get(key)
    byId.set(key, { analysisId: id, entryCount: total,
      approvedEntryIndexes: [...list(previous?.approvedEntryIndexes), ...list(approved)] })
  }
  for (const history of list(histories)) {
    if (String(history?.status).toUpperCase() !== 'COMPLETED' || !history.result) continue
    const result = history.result
    merge(history.id, list(result.suggestedEntries).length || (result.suggestedEntry ? 1 : 0), result.approvedEntryIndexes)
  }
  for (const item of list(items)) {
    if (item?.status === 'done' && list(item.suggestedEntries).length) merge(item.analysisId, item.suggestedEntries.length, item.approvedEntryIndexes)
  }
  const appliedById = new Map()
  for (const marker of list(appliedMarkers)) {
    if (!marker?.analysisId) continue
    const key = String(marker.analysisId)
    if (!appliedById.has(key)) appliedById.set(key, [])
    appliedById.get(key).push(marker.entryIndex)
  }
  let pending = 0
  for (const request of byId.values()) {
    const total = Number(request.entryCount) || 0
    const used = new Set([...list(request.approvedEntryIndexes), ...list(appliedById.get(String(request.analysisId)))]
      .map(Number).filter(index => Number.isInteger(index) && index >= 0 && index < total))
    if (total > used.size) pending++
  }
  return pending
}

// Keep the current selection visible even after individual requests finish.
export function selectReceiptAnalysisItems(items = [], batchItemIds = []) {
  const batch = new Set(batchItemIds)
  return items.filter(item => !item.fromHistory && (
    batch.size ? batch.has(item.id) : item.status !== 'done'
  ))
}

export function receiptReviewPage(entries = [], requestedPage = 0) {
  const totalPages = Math.max(1, Math.ceil(entries.length / RECEIPT_REVIEW_PAGE_SIZE))
  const page = Math.max(0, Math.min(totalPages - 1, Math.trunc(Number(requestedPage) || 0)))
  const start = page * RECEIPT_REVIEW_PAGE_SIZE
  return {
    page, totalPages, total: entries.length, start,
    end: Math.min(start + RECEIPT_REVIEW_PAGE_SIZE, entries.length),
    entries: entries.slice(start, start + RECEIPT_REVIEW_PAGE_SIZE)
      .map((entry, index) => ({ entry, entryIndex: start + index })),
  }
}
