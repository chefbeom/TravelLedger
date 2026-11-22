// Group only the visible date/filter slice.
// - Split payments become one row.
// - An adjustment record (cancellation/refund) is attached to its original payment's row when the
//   original is in the same slice; otherwise it stays as a standalone "linked adjustment" row.
// Accounting is untouched: totals still iterate over the raw entries, never over these rows.

export function compareLedgerChronologically(a, b) {
  const date = String(a?.entryDate || '').localeCompare(String(b?.entryDate || ''))
  if (date) return date
  const time = String(a?.entryTime || '').localeCompare(String(b?.entryTime || ''))
  if (time) return time
  return (Number(a?.id) || 0) - (Number(b?.id) || 0)
}

export function groupLedgerSheetEntries(entries = []) {
  const originIds = new Set()
  for (const entry of entries) {
    if (!entry.adjustmentType) originIds.add(String(entry.id))
  }
  const rows = []
  const splitRows = new Map()
  const rowByOriginId = new Map()
  const pending = new Map()
  for (const entry of entries) {
    if (entry.adjustmentType) {
      const originId = String(entry.adjustmentOriginId ?? '')
      if (originIds.has(originId)) {
        if (!pending.has(originId)) pending.set(originId, [])
        pending.get(originId).push(entry)
      } else {
        rows.push({ key: `adjustment:${entry.id}`, kind: 'adjustment', isSplit: false, parts: [entry], adjustments: [] })
      }
      continue
    }
    const groupId = entry.splitPaymentGroupId ? String(entry.splitPaymentGroupId) : ''
    if (!groupId) {
      const row = { key: `entry:${entry.id}`, kind: 'entry', isSplit: false, parts: [entry], adjustments: [] }
      rows.push(row)
      rowByOriginId.set(String(entry.id), row)
      continue
    }
    let row = splitRows.get(groupId)
    if (!row) {
      row = { key: `split:${groupId}`, kind: 'split', isSplit: true, groupId, parts: [], adjustments: [] }
      splitRows.set(groupId, row)
      rows.push(row)
    }
    row.parts.push(entry)
    rowByOriginId.set(String(entry.id), row)
  }
  for (const [originId, items] of pending) {
    const row = rowByOriginId.get(originId)
    row.adjustments = row.adjustments.concat(items)
  }
  for (const row of rows) {
    if (row.adjustments.length > 1) row.adjustments = [...row.adjustments].sort(compareLedgerChronologically)
  }
  return rows
}

export function rowContainsEntryId(row, id) {
  if (!row || id === null || id === undefined || id === '') return false
  const target = String(id)
  return (row.parts || []).some(part => String(part.id) === target)
    || (row.adjustments || []).some(item => String(item.id) === target)
}

export function adjustmentsForEntry(row, entryId) {
  const target = String(entryId)
  return (row?.adjustments || []).filter(item => String(item.adjustmentOriginId) === target)
}

export function splitDisplayedAmount(parts) {
  return parts.reduce((sum, part) => sum + Math.round((Number(part.amount) || 0) * 100), 0) / 100
}

export function splitExpectedCount(parts) {
  return Math.max(parts.length, ...parts.map(part => Number(part.splitPaymentCount) || 0))
}

export function adjustmentItemLabel(item) {
  const voided = Boolean(item?.adjustmentVoidedAt ?? item?.voided)
  const type = item?.adjustmentType ?? item?.type
  if (type === 'PAYMENT_CANCELLATION') return voided ? '결제 취소 되돌림' : '결제 취소'
  if (type === 'REFUND') return voided ? '환불 되돌림' : '환불 입금'
  return voided ? '처리 되돌림' : '처리 기록'
}

export function summarizeLedgerAdjustments(origin, adjustments = []) {
  const o = origin || {}
  const items = (adjustments || []).map(item => ({
    id: item.id,
    type: item.adjustmentType,
    voided: Boolean(item.adjustmentVoidedAt),
    label: adjustmentItemLabel(item),
    amount: item.amount,
    principal: item.adjustmentPrincipal,
    deduction: item.adjustmentDeduction,
    extra: item.adjustmentExtra,
    entryDate: item.entryDate,
    entryTime: item.entryTime,
    memo: item.memo,
    paymentMethodName: item.paymentMethodName,
    voidedAt: item.adjustmentVoidedAt,
    sameDateAsOrigin: item.entryDate === o.entryDate,
    source: item,
  }))
  const refundedPrincipal = Number(o.refundedPrincipal) || 0
  const amount = Number(o.amount) || 0
  const activeRefundCents = items
    .filter(item => item.type === 'REFUND' && !item.voided)
    .reduce((sum, item) => sum + Math.round((Number(item.amount) || 0) * 100), 0)
  let status = 'NONE'
  let label = ''
  if (o.cancelledAt) {
    status = 'CANCELLED'
    label = '결제 취소됨'
  } else if (refundedPrincipal > 0 && refundedPrincipal >= amount) {
    status = 'REFUNDED'
    label = '환불 완료'
  } else if (refundedPrincipal > 0) {
    status = 'PARTIALLY_REFUNDED'
    label = '부분 환불'
  } else if (o.hasAdjustmentHistory || items.length) {
    status = 'REVERTED'
    if (items.length && items.every(item => item.type === 'PAYMENT_CANCELLATION')) label = '취소 되돌림'
    else if (items.length && items.every(item => item.type === 'REFUND')) label = '환불 되돌림'
    else label = '처리 되돌림'
  }
  return {
    status,
    label,
    excluded: Boolean(o.financiallyExcluded),
    refundedPrincipal,
    activeRefundReceived: activeRefundCents / 100,
    items,
    hiddenHistory: Boolean(o.hasAdjustmentHistory) && items.length === 0,
  }
}

export function ledgerEntryStatusLabel(entry) {
  if (!entry) return ''
  if (entry.adjustmentType === 'PAYMENT_CANCELLATION') return entry.adjustmentVoidedAt ? '결제 취소 기록 · 되돌림' : '결제 취소 기록'
  if (entry.adjustmentType === 'REFUND') return entry.adjustmentVoidedAt ? '환불 입금 · 되돌림' : '환불 입금'
  if (entry.adjustmentVoidedAt) return '처리 되돌림'
  return summarizeLedgerAdjustments(entry, []).label
}

export function splitAdjustmentLabel(row) {
  const labels = (row?.parts || [])
    .map(part => summarizeLedgerAdjustments(part, adjustmentsForEntry(row, part.id)).label)
    .filter(Boolean)
  if (!labels.length) return ''
  return labels.every(label => label === labels[0]) ? `${labels.length}개 수단 ${labels[0]}` : `${labels.length}개 수단 처리 있음`
}
