// Main-dashboard snapshots in 1/3 columns are separate from the canonical
// 9-column account layout. Old payloads without a snapshot remain unchanged.
export function normalizeCompactLayouts(value) {
  const result = {}
  if (!value || typeof value !== 'object' || Array.isArray(value)) return result
  for (const columns of [1, 3]) {
    const input = value[String(columns)]
    if (!Array.isArray(input)) continue
    const ids = new Set()
    const entries = []
    for (const item of input) {
      const id = String(item?.id || '')
      const x = Number(item?.x), y = Number(item?.y), w = Number(item?.w), h = Number(item?.h)
      if (!id || ids.has(id) || ![x, y, w, h].every(Number.isInteger)
          || x < 0 || y < 0 || y > 1000 || w < 1 || w > columns || x + w > columns || h < 1 || h > 12) continue
      ids.add(id)
      entries.push({ id, x, y, w, h })
    }
    if (entries.length) result[String(columns)] = entries
  }
  return result
}
