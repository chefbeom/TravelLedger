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
import { buildPaletteStorageKey, loadPaletteState, savePaletteState } from '../features/palette/utils/paletteStorage'

function clonePresets(value) {
  return JSON.parse(JSON.stringify(value))
}

function createPaletteId(templateId) {
  return `${templateId}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
}

export const useDashboardPaletteStore = defineStore('dashboardPalette', () => {
  const currentPresetId = ref(3)
  const presets = ref(createDefaultPalettePresets())
  const isEditMode = ref(false)
  const hydrated = ref(false)
  const storageKey = ref('')

  const currentPreset = computed(() =>
    presets.value.find((preset) => Number(preset.id) === Number(currentPresetId.value))
      ?? presets.value.find((preset) => Number(preset.id) === 3)
      ?? presets.value[0],
  )
  const visiblePalettes = computed(() => (currentPreset.value?.palettes ?? []).filter((palette) => palette.visible !== false))
  const hiddenPalettes = computed(() => (currentPreset.value?.palettes ?? []).filter((palette) => palette.visible === false))
  const hiddenPaletteIds = computed(() => hiddenPalettes.value.map((palette) => palette.id))
  const availableTemplates = computed(() => paletteTemplates)

  function persist() {
    savePaletteState(storageKey.value, {
      currentPresetId: currentPresetId.value,
      presets: presets.value,
    })
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
    if (hydrated.value && storageKey.value === nextStorageKey) {
      return
    }

    storageKey.value = nextStorageKey
    const restored = loadPaletteState(storageKey.value)
    if (restored) {
      currentPresetId.value = [1, 2, 3].includes(Number(restored.currentPresetId))
        ? Number(restored.currentPresetId)
        : 3
      presets.value = restored.presets.map((preset) => ({
        ...preset,
        palettes: normalizePaletteList(preset.palettes ?? []),
      }))
    } else {
      currentPresetId.value = 3
      presets.value = createDefaultPalettePresets()
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
    isEditMode.value = !isEditMode.value
    persist()
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
      const definition = getPaletteDefinition(palette.type)
      const normalizedSize = normalizePaletteSize(size, definition.defaultSize)
      const allowedSize = definition.supportedSizes.includes(normalizedSize)
        ? normalizedSize
        : definition.defaultSize
      return {
        ...palette,
        size: allowedSize,
        position: clampPosition(palette.position, allowedSize),
      }
    })
    replacePresetPalettes(currentPresetId.value, nextPalettes)
    persist()
  }

  function applyLayoutPatches(patches) {
    replacePresetPalettes(
      currentPresetId.value,
      applyLayoutPatchesToPalettes(currentPreset.value?.palettes ?? [], patches),
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
    const size = normalizePaletteSize(template.defaultSize, definition.defaultSize)
    const allowedSize = definition.supportedSizes.includes(size) ? size : definition.defaultSize
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
    setPreset,
    toggleEditMode,
    movePalette,
    resizePalette,
    applyLayoutPatches,
    hidePalette,
    restorePalette,
    removePalette,
    addPalette,
    resetPreset,
  }
})
