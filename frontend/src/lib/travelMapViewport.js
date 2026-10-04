// Pick the largest unobscured rectangle rather than centering behind overlays.
export function getTravelMapUsableRect(width, height, overlays = []) {
  let candidates = [{ left: 0, top: 0, right: width, bottom: height }]
  for (const overlay of overlays) {
    const obstacle = { left: Math.max(0, overlay.left), top: Math.max(0, overlay.top), right: Math.min(width, overlay.right), bottom: Math.min(height, overlay.bottom) }
    if (obstacle.left >= obstacle.right || obstacle.top >= obstacle.bottom) continue
    candidates = candidates.flatMap(rect => {
      if (obstacle.right <= rect.left || obstacle.left >= rect.right || obstacle.bottom <= rect.top || obstacle.top >= rect.bottom) return [rect]
      return [
        { ...rect, right: Math.min(rect.right, obstacle.left) },
        { ...rect, left: Math.max(rect.left, obstacle.right) },
        { ...rect, bottom: Math.min(rect.bottom, obstacle.top) },
        { ...rect, top: Math.max(rect.top, obstacle.bottom) },
      ].filter(item => item.right > item.left && item.bottom > item.top)
    })
  }
  return candidates.sort((a, b) => (b.right - b.left) * (b.bottom - b.top) - (a.right - a.left) * (a.bottom - a.top))[0]
    ?? { left: 0, top: 0, right: width, bottom: height }
}
