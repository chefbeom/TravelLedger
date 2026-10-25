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
