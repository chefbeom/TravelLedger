import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { getPaletteDefinition } from '../features/palette/registry/paletteRegistry'
import { createDefaultPalettePresets, paletteTemplates } from '../features/palette/registry/paletteDefaults'
import {
  applyLayoutPatchesToPalettes,
  clampPosition,
  findFirstAvailablePosition,
  normalizePaletteList,
  normalizePaletteSize,
} from '../features/palette/utils/paletteLayout'
import {
  buildPaletteLayoutScope,
  buildPaletteStorageKey,
  loadPaletteState,
  savePaletteState,
} from '../features/palette/utils/paletteStorage'
import { fetchLayoutSetting, saveLayoutSetting } from '../lib/api'

const PALETTE_LAYOUT_VERSION = 7
const REMOTE_SAVE_DELAY_MS = 800

function clonePresets(value) {
  return JSON.parse(JSON.stringify(value))
}

function createPaletteId(templateId) {
  return `${templateId}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
}

function resolveAllowedPaletteSize(palette, requestedSize) {
  const definition = getPaletteDefinition(palette?.type)
  const currentSize = normalizePaletteSize(palette?.size, definition.defaultSize)
  const normalizedSize = normalizePaletteSize(requestedSize, currentSize)
  if (definition.supportedSizes.includes(normalizedSize)) {
    return normalizedSize
  }
  if (definition.supportedSizes.includes(currentSize)) {
    return currentSize
  }
  return definition.defaultSize
}

export const useDashboardPaletteStore = defineStore('dashboardPalette', () => {
  const currentPresetId = ref(3)
  const presets = ref(createDefaultPalettePresets())
  const isEditMode = ref(false)
  const hydrated = ref(false)
  const storageKey = ref('')
  const layoutScope = ref('')
  let remoteHydrationSequence = 0
  let remoteSaveTimer = 0
  let pendingRemoteState = null
  let pendingRemoteScope = ''
  let changedDuringRemoteHydration = false

  const currentPreset = computed(() =>
    presets.value.find((preset) => Number(preset.id) === Number(currentPresetId.value))
      ?? presets.value.find((preset) => Number(preset.id) === 3)
      ?? presets.value[0],
  )
  const visiblePalettes = computed(() => (currentPreset.value?.palettes ?? []).filter((palette) => palette.visible !== false))
  const hiddenPalettes = computed(() => (currentPreset.value?.palettes ?? []).filter((palette) => palette.visible === false))
  const hiddenPaletteIds = computed(() => hiddenPalettes.value.map((palette) => palette.id))
  const availableTemplates = computed(() => paletteTemplates)

  function snapshotState() {
    return {
      currentPresetId: currentPresetId.value,
      presets: clonePresets(presets.value),
    }
  }

  function applyState(state) {
    if (!state || !Array.isArray(state.presets)) {
      return false
    }

    currentPresetId.value = [1, 2, 3].includes(Number(state.currentPresetId))
      ? Number(state.currentPresetId)
      : 3
    presets.value = state.presets.map((preset) => ({
      ...preset,
      palettes: normalizePaletteList(preset.palettes ?? []),
    }))
    return true
  }

  function persistLocal(state = snapshotState()) {
    savePaletteState(storageKey.value, {
      currentPresetId: state.currentPresetId,
      presets: state.presets,
    })
  }

  function scheduleRemotePersist(state = snapshotState()) {
    if (!layoutScope.value || typeof window === 'undefined') {
      return
    }

    if (remoteSaveTimer) {
      window.clearTimeout(remoteSaveTimer)
    }

    pendingRemoteScope = layoutScope.value
    pendingRemoteState = clonePresets(state)
    remoteSaveTimer = window.setTimeout(() => {
      saveRemoteNow()
    }, REMOTE_SAVE_DELAY_MS)
  }

  function saveRemoteNow(state = pendingRemoteState, scope = pendingRemoteScope || layoutScope.value) {
    if (!scope || typeof window === 'undefined' || !state) {
      return Promise.resolve()
    }

    if (remoteSaveTimer) {
      window.clearTimeout(remoteSaveTimer)
      remoteSaveTimer = 0
    }

    const payload = clonePresets(state)
    pendingRemoteState = null
    pendingRemoteScope = ''
    return saveLayoutSetting(scope, payload, PALETTE_LAYOUT_VERSION).catch(() => {
      // Local cache remains the fallback if the backend is temporarily unavailable.
    })
  }

  function persist() {
    changedDuringRemoteHydration = true
    const state = snapshotState()
    persistLocal(state)
    scheduleRemotePersist(state)
  }

  async function hydrateRemote(sequence, fallbackState) {
    try {
      const response = await fetchLayoutSetting(layoutScope.value)
      if (sequence !== remoteHydrationSequence || changedDuringRemoteHydration) {
        return
      }

      if (applyState(response?.payload)) {
        persistLocal()
        return
      }

      if (fallbackState) {
        await saveLayoutSetting(layoutScope.value, fallbackState, PALETTE_LAYOUT_VERSION)
      }
    } catch {
      // A failed remote read should not block dashboard rendering.
    }
  }

  function replacePresetPalettes(presetId, nextPalettes) {
    presets.value = presets.value.map((preset) => (
      Number(preset.id) === Number(presetId)
        ? { ...preset, palettes: normalizePaletteList(nextPalettes) }
        : preset
    ))
  }

  function hydrate({ userId, scope = 'household' } = {}) {
    const nextStorageKey = buildPaletteStorageKey(userId, scope)
    const nextLayoutScope = buildPaletteLayoutScope(scope)
    if (hydrated.value && storageKey.value === nextStorageKey) {
      return
    }

    saveRemoteNow()
    storageKey.value = nextStorageKey
    layoutScope.value = nextLayoutScope
    remoteHydrationSequence += 1
    changedDuringRemoteHydration = false
    const restored = loadPaletteState(storageKey.value)
    if (applyState(restored)) {
      hydrateRemote(remoteHydrationSequence, snapshotState())
    } else {
      currentPresetId.value = 3
      presets.value = createDefaultPalettePresets()
      hydrateRemote(remoteHydrationSequence, null)
    }
    isEditMode.value = false
    hydrated.value = true
  }

  function setPreset(id) {
    const normalizedId = Number(id)
    if (![1, 2, 3].includes(normalizedId)) {
      return
    }
    currentPresetId.value = normalizedId
    persist()
  }

  function toggleEditMode() {
    const isFinishingEdit = isEditMode.value
    isEditMode.value = !isEditMode.value
    if (isFinishingEdit) {
      flushRemotePersist()
    }
  }

  function flushRemotePersist({ force = false } = {}) {
    if (!force && !pendingRemoteState) {
      return Promise.resolve()
    }

    const state = force ? snapshotState() : pendingRemoteState
    if (force) {
      persistLocal(state)
    }
    return saveRemoteNow(state, force ? layoutScope.value : undefined)
  }

  function movePalette(id, position) {
    const nextPalettes = (currentPreset.value?.palettes ?? []).map((palette) => {
      if (String(palette.id) !== String(id)) {
        return palette
      }
      return {
        ...palette,
        position: clampPosition(position, palette.size),
      }
    })
    replacePresetPalettes(currentPresetId.value, nextPalettes)
    persist()
  }

  function resizePalette(id, size) {
    const nextPalettes = (currentPreset.value?.palettes ?? []).map((palette) => {
      if (String(palette.id) !== String(id)) {
        return palette
      }
      const allowedSize = resolveAllowedPaletteSize(palette, size)
      return {
        ...palette,
        size: allowedSize,
        position: clampPosition(palette.position, allowedSize),
      }
    })
    replacePresetPalettes(currentPresetId.value, nextPalettes)
    persist()
  }

  function updatePaletteOptions(id, options = {}) {
    const nextPalettes = (currentPreset.value?.palettes ?? []).map((palette) => {
      if (String(palette.id) !== String(id)) {
        return palette
      }
      return {
        ...palette,
        options: {
          ...(palette.options ?? {}),
          ...(options ?? {}),
        },
      }
    })
    replacePresetPalettes(currentPresetId.value, nextPalettes)
    persist()
  }
  function applyLayoutPatches(patches) {
    const paletteById = new Map((currentPreset.value?.palettes ?? []).map((palette) => [String(palette.id), palette]))
    const normalizedPatches = (patches ?? []).map((patch) => {
      const palette = paletteById.get(String(patch.id))
      if (!palette) {
        return patch
      }
      const size = resolveAllowedPaletteSize(palette, patch.size ?? palette.size)
      return {
        ...patch,
        size,
        position: clampPosition(patch.position ?? palette.position, size),
      }
    })
    replacePresetPalettes(
      currentPresetId.value,
      applyLayoutPatchesToPalettes(currentPreset.value?.palettes ?? [], normalizedPatches),
    )
    persist()
  }

  function hidePalette(id) {
    const nextPalettes = (currentPreset.value?.palettes ?? []).map((palette) => (
      String(palette.id) === String(id)
        ? { ...palette, visible: false }
        : palette
    ))
    replacePresetPalettes(currentPresetId.value, nextPalettes)
    persist()
  }

  function restorePalette(id) {
    const visible = visiblePalettes.value
    const nextPalettes = (currentPreset.value?.palettes ?? []).map((palette) => {
      if (String(palette.id) !== String(id)) {
        return palette
      }
      return {
        ...palette,
        visible: true,
        position: findFirstAvailablePosition(visible, palette.size),
      }
    })
    replacePresetPalettes(currentPresetId.value, nextPalettes)
    persist()
  }

  function removePalette(id) {
    replacePresetPalettes(
      currentPresetId.value,
      (currentPreset.value?.palettes ?? []).filter((palette) => String(palette.id) !== String(id)),
    )
    persist()
  }

  function addPalette(templateId, options = {}) {
    const template = paletteTemplates.find((item) => item.id === templateId) ?? paletteTemplates[0]
    if (!template) {
      return
    }
    const definition = getPaletteDefinition(template.type)
    const allowedSize = resolveAllowedPaletteSize({ type: template.type, size: definition.defaultSize }, template.defaultSize)
    const nextPalette = {
      id: createPaletteId(template.id),
      type: template.type,
      size: allowedSize,
      position: findFirstAvailablePosition(visiblePalettes.value, allowedSize),
      visible: true,
      options: {
        ...(template.options ?? {}),
        ...(options ?? {}),
      },
    }
    replacePresetPalettes(currentPresetId.value, [...(currentPreset.value?.palettes ?? []), nextPalette])
    persist()
  }

  function resetPreset(id = currentPresetId.value) {
    const defaults = clonePresets(createDefaultPalettePresets())
    const target = defaults.find((preset) => Number(preset.id) === Number(id))
    if (!target) {
      return
    }
    presets.value = presets.value.map((preset) => (
      Number(preset.id) === Number(id)
        ? target
        : preset
    ))
    persist()
  }

  return {
    currentPresetId,
    presets,
    isEditMode,
    hydrated,
    currentPreset,
    visiblePalettes,
    hiddenPalettes,
    hiddenPaletteIds,
    availableTemplates,
    hydrate,
    persist,
    flushRemotePersist,
    setPreset,
    toggleEditMode,
    movePalette,
    resizePalette,
    applyLayoutPatches,
    updatePaletteOptions,
    hidePalette,
    restorePalette,
    removePalette,
    addPalette,
    resetPreset,
  }
})
