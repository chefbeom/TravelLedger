import { computed } from 'vue'
import { getSpanBySize } from '../utils/paletteLayout'

export function usePaletteGrid(palettes) {
  const gridItems = computed(() => (palettes.value ?? []).map((palette) => {
    const span = getSpanBySize(palette.size)
    return {
      ...palette,
      grid: {
        x: palette.position?.x ?? 0,
        y: palette.position?.y ?? 0,
        w: span.w,
        h: span.h,
      },
    }
  }))

  return {
    gridItems,
  }
}
