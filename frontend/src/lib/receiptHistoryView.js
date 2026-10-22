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

// Gallery cards favor a short result label over long upload filenames.
export function receiptHistoryTileSummary(history) {
  const status = String(history?.status || '').trim().toUpperCase()
  if (status === 'PROCESSING') return '분석 중'
  if (status === 'FAILED') return '분석 실패'
  if (status === 'CANCELLED') return '취소됨'
  if (status !== 'COMPLETED') return '상태 확인 필요'
  const entries = history.result?.suggestedEntries || []
  const text = String(entries[0]?.title || history.result?.suggestedEntry?.title || history.summary || '분석 완료').replace(/\s+/g, ' ').trim()
  const characters = Array.from(text)
  return `${characters.slice(0, 28).join('')}${characters.length > 28 ? '…' : ''}${entries.length > 1 ? ` 외 ${entries.length - 1}건` : ''}`
}
