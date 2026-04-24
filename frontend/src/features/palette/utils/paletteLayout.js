import { DASHBOARD_GRID_COLUMNS, PALETTE_SIZES } from '../types'

export const SIZE_SPANS = {
  '1x1': { w: 1, h: 1 },
  '1x2': { w: 1, h: 2 },
  '2x2': { w: 2, h: 2 },
  '3x2': { w: 3, h: 2 },
  '3x3': { w: 3, h: 3 },
  '3x4': { w: 3, h: 4 },
}

const OLD_SIZE_MIGRATIONS = {
  small: '1x1',
  medium: '2x2',
  large: '3x3',
}

export function normalizePaletteSize(size, fallback = '2x2') {
  const migrated = OLD_SIZE_MIGRATIONS[String(size || '').trim()] || size
  return PALETTE_SIZES.includes(migrated) ? migrated : fallback
}

export function getSpanBySize(size) {
  return SIZE_SPANS[normalizePaletteSize(size)] || SIZE_SPANS['2x2']
}

export function clampPosition(position, size, columns = DASHBOARD_GRID_COLUMNS) {
  const span = getSpanBySize(size)
  const rawX = Number(position?.x ?? 0)
  const rawY = Number(position?.y ?? 0)
  return {
    x: Math.min(Math.max(Number.isFinite(rawX) ? Math.floor(rawX) : 0, 0), Math.max(columns - span.w, 0)),
    y: Math.max(Number.isFinite(rawY) ? Math.floor(rawY) : 0, 0),
  }
}

export function clonePaletteConfig(palette) {
  return {
    id: String(palette.id),
    type: String(palette.type || 'kpi'),
    size: normalizePaletteSize(palette.size),
    position: clampPosition(palette.position, palette.size),
    visible: palette.visible !== false,
    options: { ...(palette.options ?? {}) },
  }
}

function overlaps(left, right) {
  const leftSpan = getSpanBySize(left.size)
  const rightSpan = getSpanBySize(right.size)
  return !(
    left.position.x + leftSpan.w <= right.position.x
    || right.position.x + rightSpan.w <= left.position.x
    || left.position.y + leftSpan.h <= right.position.y
    || right.position.y + rightSpan.h <= left.position.y
  )
}

export function isAreaFree(palettes, position, size, excludeId = '') {
  const candidate = {
    id: excludeId || '__candidate__',
    size,
    position: clampPosition(position, size),
  }
  return palettes
    .filter((palette) => palette.visible !== false && String(palette.id) !== String(excludeId))
    .every((palette) => !overlaps(candidate, palette))
}

export function findFirstAvailablePosition(palettes, size, columns = DASHBOARD_GRID_COLUMNS) {
  const span = getSpanBySize(size)
  for (let y = 0; y < 200; y += 1) {
    for (let x = 0; x <= Math.max(columns - span.w, 0); x += 1) {
      const position = { x, y }
      if (isAreaFree(palettes, position, size)) {
        return position
      }
    }
  }
  return { x: 0, y: 0 }
}

export function normalizePaletteList(palettes, columns = DASHBOARD_GRID_COLUMNS) {
  const normalized = (palettes ?? []).map(clonePaletteConfig)
  const visible = []

  return normalized.map((palette) => {
    const next = {
      ...palette,
      position: clampPosition(palette.position, palette.size, columns),
    }

    if (next.visible !== false) {
      if (!isAreaFree(visible, next.position, next.size, next.id)) {
        next.position = findFirstAvailablePosition(visible, next.size, columns)
      }
      visible.push(next)
    }

    return next
  })
}

export function applyLayoutPatchesToPalettes(palettes, patches, columns = DASHBOARD_GRID_COLUMNS) {
  const patchMap = new Map((patches ?? []).map((patch) => [String(patch.id), patch]))
  return normalizePaletteList(
    (palettes ?? []).map((palette) => {
      const patch = patchMap.get(String(palette.id))
      if (!patch) {
        return palette
      }
      const size = normalizePaletteSize(patch.size ?? palette.size, palette.size)
      return {
        ...palette,
        size,
        position: clampPosition(patch.position ?? palette.position, size, columns),
      }
    }),
    columns,
  )
}

export function placePaletteInFirstAvailableSlot(palettes, palette, columns = DASHBOARD_GRID_COLUMNS) {
  const visiblePalettes = (palettes ?? []).filter((item) => item.visible !== false)
  const next = clonePaletteConfig({
    ...palette,
    visible: true,
    position: findFirstAvailablePosition(visiblePalettes, palette.size, columns),
  })
  return normalizePaletteList([...(palettes ?? []), next], columns)
}
