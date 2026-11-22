// Pure selection rules for dashboard photo-frame palettes. `options` must already be normalized.
export function sortPhotoFramePhotos(items, options = {}) {
  const nextItems = [...(items || [])]
  if (options.mode === 'name' || options.sort === 'name') {
    return nextItems.sort((left, right) => String(left.title || '').localeCompare(String(right.title || ''), 'ko-KR'))
  }
  return nextItems.sort((left, right) => String(right.updatedAt || '').localeCompare(String(left.updatedAt || '')))
}

export function photoFrameRandomIndex(length, seed) {
  if (!length) return -1
  let hash = 0
  const text = String(seed || '')
  for (let index = 0; index < text.length; index += 1) {
    hash = ((hash << 5) - hash) + text.charCodeAt(index)
    hash |= 0
  }
  return Math.abs(hash) % length
}

export function selectPhotoFrameDisplayItems(photos, options = {}, seed = '') {
  const items = sortPhotoFramePhotos(photos, options)
  if (!items.length) return []
  if (options.mode === 'fixed') {
    const fixed = items.find((photo) => String(photo.id) === options.fixedPhotoId)
    return [fixed || items[0]].filter(Boolean)
  }
  if (options.mode === 'random') {
    const selected = items[photoFrameRandomIndex(items.length, seed)]
    return [selected || items[0]].filter(Boolean)
  }
  return [items[0]].filter(Boolean)
}
