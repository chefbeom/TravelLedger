import { computed } from 'vue'
import { getPaletteDefinition } from '../registry/paletteRegistry'

export function usePaletteRenderers(palettes, context) {
  return computed(() => (palettes.value ?? []).map((config) => {
    const definition = getPaletteDefinition(config.type)
    return {
      config,
      definition,
      data: definition.getPaletteData(config, context.value ?? {}),
      title: definition.getTitle(config),
    }
  }))
}
