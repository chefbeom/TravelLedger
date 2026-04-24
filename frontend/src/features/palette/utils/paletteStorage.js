import { normalizePaletteList } from './paletteLayout'

export function buildPaletteStorageKey(userId, scope = 'household') {
  return `calen-dashboard-palettes:v7:${userId || 'anonymous'}:${scope}`
}

export function buildPaletteLayoutScope(scope = 'household') {
  return scope === 'household' ? 'household-dashboard' : `${scope}-dashboard`
}

export function loadPaletteState(storageKey) {
  if (!storageKey || typeof window === 'undefined') {
    return null
  }

  try {
    const raw = window.localStorage.getItem(storageKey)
    if (!raw) {
      return null
    }
    const parsed = JSON.parse(raw)
    if (!parsed || !Array.isArray(parsed.presets)) {
      return null
    }
    return {
      currentPresetId: Number(parsed.currentPresetId) || 3,
      presets: parsed.presets.map((preset) => ({
        ...preset,
        id: Number(preset.id),
        palettes: normalizePaletteList(preset.palettes ?? []),
      })),
    }
  } catch {
    return null
  }
}

export function savePaletteState(storageKey, state) {
  if (!storageKey || typeof window === 'undefined') {
    return
  }

  window.localStorage.setItem(
    storageKey,
    JSON.stringify({
      currentPresetId: state.currentPresetId,
      presets: state.presets,
    }),
  )
}
