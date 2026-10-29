// Reserve space for the header inside two outer rows. Keep this pitch independent
// of the panel's selected height so resizing the panel never resizes its cards.
export function aggregateWidgetCellHeight(outerCellHeight, chromeHeight, mobile = false) {
  const outer = Math.max(1, Number(outerCellHeight) || 1)
  if (mobile) return outer
  const chrome = Math.max(0, Number(chromeHeight) || 0)
  return Math.max(1, outer - (chrome + 2) / 2)
}

// When cards need more rows than a palette can show, grow its presentation
// rather than covering controls or introducing a nested scroll area.
export function aggregateRequiredPanelRows(outerCellHeight, widgetCellHeight, chromeHeight, widgetRows) {
  const outer = Math.max(1, Number(outerCellHeight) || 1)
  const pitch = Math.max(1, Number(widgetCellHeight) || 1)
  const chrome = Math.max(0, Number(chromeHeight) || 0)
  const rows = Math.max(0, Number(widgetRows) || 0)
  return Math.max(2, Math.ceil((rows * pitch + chrome - 0.5) / outer))
}
