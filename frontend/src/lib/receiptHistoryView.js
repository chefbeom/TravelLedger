import { parseReceiptBatchRequestId } from './receiptHistoryGroups.js'

export function normalizeReceiptHistoryView(value) {
  return {
    mode: value?.mode === 'blocks' ? 'blocks' : 'list',
    size: ['small', 'medium', 'large'].includes(value?.size) ? value.size : 'medium',
  }
}

// Use the actual file request time; only fall back to batch metadata if it is missing.
// Completion/update time is not a request time and must not be substituted.
export function receiptHistoryRequestTimestamp(history) {
  for (const value of [history?.createdAt, history?.requestedAt]) {
    if (!value || !/\d{2}:\d{2}/.test(String(value))) continue
    const timestamp = Date.parse(value)
    if (Number.isFinite(timestamp)) return timestamp
  }
  return parseReceiptBatchRequestId(history?.clientRequestId || history?.result?.clientRequestId)?.timestamp ?? null
}

const timeFormatter = new Intl.DateTimeFormat('ko-KR', {
  hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
})

export function formatReceiptHistoryRequestTime(history) {
  const timestamp = receiptHistoryRequestTimestamp(history)
  return timestamp == null ? '시간 정보 없음' : timeFormatter.format(new Date(timestamp))
}
