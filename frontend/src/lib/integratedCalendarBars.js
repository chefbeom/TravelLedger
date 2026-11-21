import { validCalendarDate } from './integratedCalendarCore.js'

const LAYER_ORDER = { travel: 0, events: 2 }

function layerRank(span) {
  if (span.layer === 'travel') return span.shared ? 1 : 0
  return LAYER_ORDER[span.layer] ?? 3
}

/**
 * Place multi-day items of one week (7 consecutive ISO dates) into
 * non-overlapping lanes.
 */
export function layoutWeekBars(weekDates, spans, maxLanes) {
  const weekStart = weekDates[0], weekEnd = weekDates[6]
  const limit = Math.max(0, Math.floor(Number(maxLanes) || 0))
  const hiddenByCol = [0, 0, 0, 0, 0, 0, 0]
  const candidates = []
  for (const span of spans || []) {
    const start = span.startDate, end = span.endDate
    if (!validCalendarDate(start) || !validCalendarDate(end) || start > end) continue
    const clipStart = start > weekStart ? start : weekStart
    const clipEnd = end < weekEnd ? end : weekEnd
    if (clipStart > clipEnd) continue
    const startCol = weekDates.indexOf(clipStart), endCol = weekDates.indexOf(clipEnd)
    candidates.push({ span, startCol, endCol, continuesBefore: start < weekStart, continuesAfter: end > weekEnd })
  }
  candidates.sort((a, b) => a.span.startDate.localeCompare(b.span.startDate)
    || b.span.endDate.localeCompare(a.span.endDate)
    || layerRank(a.span) - layerRank(b.span)
    || String(a.span.key).localeCompare(String(b.span.key)))
  const laneEnd = []
  const bars = []
  for (const item of candidates) {
    let lane = 0
    while (lane < laneEnd.length && laneEnd[lane] >= item.startCol) lane += 1
    if (lane < limit) {
      laneEnd[lane] = item.endCol
      bars.push({
        key: item.span.key, layer: item.span.layer, title: item.span.title, shared: Boolean(item.span.shared), lane,
        startCol: item.startCol, span: item.endCol - item.startCol + 1,
        continuesBefore: item.continuesBefore, continuesAfter: item.continuesAfter,
      })
    } else {
      for (let col = item.startCol; col <= item.endCol; col += 1) hiddenByCol[col] += 1
    }
  }
  return { laneCount: laneEnd.length, bars, hiddenByCol }
}
