// Group only the visible date/filter slice. Refund and cancellation entries are
// separate accounting records and must not be folded into the original payment.
export function groupLedgerSheetEntries(entries = []) {
  const rows = []
  const groups = new Map()
  for (const entry of entries) {
    const groupId = entry.splitPaymentGroupId && !entry.adjustmentType ? String(entry.splitPaymentGroupId) : ''
    if (!groupId) {
      rows.push({ key: `entry:${entry.id}`, isSplit: false, parts: [entry] })
      continue
    }
    let row = groups.get(groupId)
    if (!row) {
      row = { key: `split:${groupId}`, isSplit: true, groupId, parts: [] }
      groups.set(groupId, row)
      rows.push(row)
    }
    row.parts.push(entry)
  }
  return rows
}

export function splitDisplayedAmount(parts) {
  return parts.reduce((sum, part) => sum + Math.round((Number(part.amount) || 0) * 100), 0) / 100
}

export function splitExpectedCount(parts) {
  return Math.max(parts.length, ...parts.map(part => Number(part.splitPaymentCount) || 0))
}
