export function normalizeWidgetDimension(value, min, max) {
  const number = Number(value)
  return Number.isFinite(number) && number > 0 ? Math.round(Math.max(min, Math.min(max, number))) : 0
}

export function widgetFrame(config = {}) {
  return {
    cardWidth: normalizeWidgetDimension(config.cardWidth, 200, 1200),
    cardHeight: normalizeWidgetDimension(config.cardHeight, 160, 800),
    showPeriod: config.showPeriod !== false,
    showDetails: config.showDetails !== false,
  }
}

export function resizedWidgetFrame(origin, dx, dy, availableWidth, chart = false) {
  return {
    cardWidth: Math.round(Math.max(chart ? 280 : 200, Math.min(1200, availableWidth, origin.width + dx))),
    cardHeight: Math.round(Math.max(160, Math.min(800, origin.height + dy))),
  }
}

export function reorderItem(items, from, to) {
  if (!Number.isInteger(from) || !Number.isInteger(to) || from < 0 || to < 0 || from >= items.length || to >= items.length) return [...items]
  const reordered = [...items]
  reordered.splice(to, 0, reordered.splice(from, 1)[0])
  return reordered
}
