function finiteDimension(value, fallback) {
  return Number.isFinite(Number(value)) ? Number(value) : fallback
}

export function clampTravelMapPreviewSize(size, { width, height, compact = false }) {
  const gap = compact ? 8 : 16
  const availableWidth = Math.max(1, finiteDimension(width, 1) - gap * 2)
  const maxWidth = Math.max(1, Math.floor(Math.min(960, availableWidth * (compact ? 1 : 0.6))))
  const maxHeight = Math.max(1, Math.floor(finiteDimension(height, 1) - gap * 2))
  const minWidth = Math.min(240, maxWidth)
  const minHeight = Math.min(compact ? 180 : 220, maxHeight)
  return {
    width: Math.round(Math.min(maxWidth, Math.max(minWidth, finiteDimension(size?.width, minWidth)))),
    height: Math.round(Math.min(maxHeight, Math.max(minHeight, finiteDimension(size?.height, minHeight)))),
  }
}
