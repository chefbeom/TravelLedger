import { widgetFrame } from './calendarDirectEditing.js'

export const WIDGET_GRID_COLUMNS = 9
export const WIDGET_GRID_MAX_ROWS = 12
export const WIDGET_GRID_GAP = 8

const dimension = (value, min, max) => Number.isFinite(Number(value)) && Number(value) > 0 ? Math.max(min, Math.min(max, Math.round(Number(value)))) : 0

// Zero means the optional grid override is absent: keep older pixel/automatic settings intact.
export function widgetGridSize(config = {}) {
  return {
    cardColumns: dimension(config.cardColumns, config.kind === 'MONTHLY_CUMULATIVE_CHART' ? 2 : 1, WIDGET_GRID_COLUMNS),
    cardRows: dimension(config.cardRows, 1, WIDGET_GRID_MAX_ROWS),
  }
}

export function widgetGridStyle(config, rowHeight) {
  const { cardColumns, cardRows } = widgetGridSize(config)
  return {
    ...(cardColumns ? { '--calendar-widget-width': `calc(${cardColumns * 100 / WIDGET_GRID_COLUMNS}% + ${WIDGET_GRID_GAP * cardColumns / WIDGET_GRID_COLUMNS - WIDGET_GRID_GAP}px)`, '--calendar-widget-min-width': '0px' } : {}),
    ...(cardRows ? { '--calendar-widget-height': `${cardRows * rowHeight - WIDGET_GRID_GAP}px`, '--calendar-widget-min-height': '0px' } : {}),
  }
}

export function resizedWidgetGrid(origin, dx, dy, availableWidth, rowHeight, chart = false) {
  const columnPitch = (Math.max(1, availableWidth) + WIDGET_GRID_GAP) / WIDGET_GRID_COLUMNS
  return widgetGridSize({
    kind: chart ? 'MONTHLY_CUMULATIVE_CHART' : 'TOTAL',
    cardColumns: Math.max(1, Math.round((origin.width + dx + WIDGET_GRID_GAP) / columnPitch)),
    cardRows: Math.max(1, Math.round((origin.height + dy + WIDGET_GRID_GAP) / Math.max(1, rowHeight))),
  })
}
// Legacy pixels remain stored, but their rendered frame must obey the very same
// cells as explicitly configured cards. Reading never migrates account data.
export function resolvedWidgetGrid(config, availableWidth, rowHeight) {
  const saved = widgetGridSize(config), legacy = widgetFrame(config)
  const width = legacy.cardWidth || (config.kind === 'MONTHLY_CUMULATIVE_CHART'
    ? 480 + Math.max(0, (Number(config.layoutW) || 2) - 2) * 120
    : { SMALL: 240, MEDIUM: 260, LARGE: 360 }[config.displaySize] || 260)
  const inferred = resizedWidgetGrid({ width, height: legacy.cardHeight || 160 }, 0, 0,
    Number(availableWidth) > 0 ? availableWidth : 1200, Number(rowHeight) > 0 ? rowHeight : 112,
    config.kind === 'MONTHLY_CUMULATIVE_CHART')
  return { cardColumns: saved.cardColumns || inferred.cardColumns, cardRows: saved.cardRows || inferred.cardRows }
}

export function widgetGridRowCount(frames, mobile = false) {
  let used = 0, rowHeight = 0, rows = 0
  for (const frame of frames) {
    const width = mobile ? WIDGET_GRID_COLUMNS : frame.cardColumns
    if (used + width > WIDGET_GRID_COLUMNS) { rows += rowHeight; used = 0; rowHeight = 0 }
    used += width
    rowHeight = Math.max(rowHeight, frame.cardRows)
  }
  return rows + rowHeight
}
