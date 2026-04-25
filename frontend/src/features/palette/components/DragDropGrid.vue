<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, toRef, watch } from 'vue'
import { GridStack } from 'gridstack'
import 'gridstack/dist/gridstack.min.css'
import PaletteItem from './PaletteItem.vue'
import { DASHBOARD_GRID_COLUMNS } from '../types'
import { getSpanBySize } from '../utils/paletteLayout'
import { usePaletteRenderers } from '../composables/usePaletteRenderers'

const props = defineProps({
  palettes: {
    type: Array,
    default: () => [],
  },
  context: {
    type: Object,
    default: () => ({}),
  },
  editMode: {
    type: Boolean,
    default: false,
  },
})

const emit = defineEmits(['apply-layout-patches', 'resize-palette', 'hide-palette', 'remove-palette'])

const PALETTE_GRID_MARGIN = 4
const PALETTE_GRID_GAP = PALETTE_GRID_MARGIN * 2

const gridElement = ref(null)
const cellHeight = ref(92)
const palettesRef = toRef(props, 'palettes')
const contextRef = toRef(props, 'context')
const renderItems = usePaletteRenderers(palettesRef, contextRef)
const layoutKey = computed(() =>
  props.palettes
    .map((palette) => `${palette.id}:${palette.position?.x ?? 0}:${palette.position?.y ?? 0}:${palette.size}:${palette.visible}`)
    .join('|'),
)
const guideRowCount = computed(() => Math.max(
  1,
  ...props.palettes.map((palette) => {
    const span = getSpanBySize(palette.size)
    return (palette.position?.y ?? 0) + span.h
  }),
))
const guideCellCount = computed(() => DASHBOARD_GRID_COLUMNS * guideRowCount.value)
const gridShellStyle = computed(() => ({
  '--palette-cell-height': `${cellHeight.value}px`,
  '--palette-grid-gap': `${PALETTE_GRID_GAP}px`,
  '--palette-grid-margin': `${PALETTE_GRID_MARGIN}px`,
}))

let grid = null
let resizeObserver = null
let dragStartLayout = new Map()
let rebuildTimer = 0

function gridAttrs(config) {
  const span = getSpanBySize(config.size)
  return {
    id: config.id,
    x: config.position?.x ?? 0,
    y: config.position?.y ?? 0,
    w: span.w,
    h: span.h,
  }
}

function updateCellHeight() {
  const width = gridElement.value?.clientWidth || gridElement.value?.parentElement?.clientWidth || 0
  if (!width || !grid) {
    return
  }
  const rawCellWidth = (
    width
    - (PALETTE_GRID_MARGIN * 2)
    - ((DASHBOARD_GRID_COLUMNS - 1) * PALETTE_GRID_GAP)
  ) / DASHBOARD_GRID_COLUMNS
  const nextHeight = Math.round(Math.max(96, Math.min(156, rawCellWidth * 0.92)))
  cellHeight.value = nextHeight
  grid.cellHeight(nextHeight)
}

function readSnapshot() {
  if (!grid) {
    return []
  }
  return (grid.engine?.nodes ?? []).map((node) => ({
    id: node.el?.getAttribute('gs-id') || node.el?.getAttribute('data-palette-id'),
    position: { x: node.x, y: node.y },
    size: `${node.w}x${node.h}`,
  })).filter((patch) => patch.id)
}

function handleDragStart(event, element) {
  dragStartLayout = new Map(props.palettes.map((palette) => [String(palette.id), {
    id: String(palette.id),
    size: palette.size,
    position: { ...(palette.position ?? { x: 0, y: 0 }) },
  }]))
  element?.classList.add('is-palette-dragging')
}

function handleDragStop(event, element) {
  element?.classList.remove('is-palette-dragging')
  const movedId = element?.getAttribute('gs-id') || element?.getAttribute('data-palette-id')
  const node = element?.gridstackNode
  const before = dragStartLayout.get(String(movedId))

  if (movedId && node && before) {
    const swapTarget = [...dragStartLayout.values()].find((item) =>
      item.id !== String(movedId)
      && item.size === before.size
      && item.position.x === node.x
      && item.position.y === node.y,
    )

    if (swapTarget) {
      emit('apply-layout-patches', [
        { id: movedId, position: swapTarget.position, size: before.size },
        { id: swapTarget.id, position: before.position, size: swapTarget.size },
      ])
      return
    }
  }

  emit('apply-layout-patches', readSnapshot())
}

function destroyGrid() {
  if (!grid) {
    return
  }
  grid.off('dragstart')
  grid.off('dragstop')
  grid.destroy(false)
  grid = null
}

function initGrid() {
  if (!gridElement.value) {
    return
  }

  destroyGrid()
  grid = GridStack.init({
    column: DASHBOARD_GRID_COLUMNS,
    margin: PALETTE_GRID_MARGIN,
    cellHeight: cellHeight.value,
    disableResize: true,
    float: false,
    animate: true,
    draggable: {
      appendTo: 'body',
      cancel: 'button,a,input,select,textarea,[data-no-drag="true"]',
      handle: '.palette-item',
      scroll: false,
    },
  }, gridElement.value)
  grid.enableMove(props.editMode)
  grid.enableResize(false)
  grid.on('dragstart', handleDragStart)
  grid.on('dragstop', handleDragStop)
  updateCellHeight()
}

function queueGridRebuild() {
  if (rebuildTimer) {
    window.clearTimeout(rebuildTimer)
  }
  rebuildTimer = window.setTimeout(async () => {
    await nextTick()
    initGrid()
    rebuildTimer = 0
  }, 0)
}

onMounted(async () => {
  await nextTick()
  initGrid()
  resizeObserver = new ResizeObserver(updateCellHeight)
  if (gridElement.value?.parentElement) {
    resizeObserver.observe(gridElement.value.parentElement)
  }
})

onBeforeUnmount(() => {
  if (rebuildTimer) {
    window.clearTimeout(rebuildTimer)
  }
  resizeObserver?.disconnect()
  destroyGrid()
})

watch(() => props.editMode, (value) => {
  if (grid) {
    grid.enableMove(value)
    grid.enableResize(false)
  }
})

watch(layoutKey, () => {
  queueGridRebuild()
})
</script>

<template>
  <div class="palette-grid-shell" :class="{ 'palette-grid-shell--editing': editMode }" :style="gridShellStyle">
    <div v-if="editMode" class="palette-grid-guide" aria-hidden="true">
      <span v-for="index in guideCellCount" :key="index"></span>
    </div>

    <div ref="gridElement" class="grid-stack palette-grid">
      <div
        v-for="item in renderItems"
        :key="item.config.id"
        class="grid-stack-item"
        :gs-id="item.config.id"
        :data-palette-id="item.config.id"
        :gs-x="gridAttrs(item.config).x"
        :gs-y="gridAttrs(item.config).y"
        :gs-w="gridAttrs(item.config).w"
        :gs-h="gridAttrs(item.config).h"
      >
        <div class="grid-stack-item-content">
          <PaletteItem
            :config="item.config"
            :definition="item.definition"
            :data="item.data"
            :title="item.title"
            :edit-mode="editMode"
            @resize="(id, size) => emit('resize-palette', id, size)"
            @hide="(id) => emit('hide-palette', id)"
            @remove="(id) => emit('remove-palette', id)"
          />
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.palette-grid-shell {
  min-width: 0;
  overflow-x: hidden;
  position: relative;
  width: 100%;
}

.palette-grid {
  min-width: 100%;
  overflow: visible;
  width: 100%;
}

.palette-grid-guide {
  display: grid;
  gap: var(--palette-grid-gap, 8px);
  grid-auto-rows: calc(var(--palette-cell-height, 96px) - var(--palette-grid-gap, 8px));
  grid-template-columns: repeat(9, minmax(0, 1fr));
  left: var(--palette-grid-margin, 4px);
  pointer-events: none;
  position: absolute;
  right: var(--palette-grid-margin, 4px);
  top: var(--palette-grid-margin, 4px);
  z-index: 0;
}

.palette-grid-guide span {
  background: var(--grid-guide-bg);
  border: 1px dashed var(--grid-guide-border);
  box-sizing: border-box;
  min-width: 0;
}

:deep(.grid-stack-item) {
  z-index: 1;
}

:deep(.grid-stack-item-content) {
  inset: var(--palette-grid-margin, 4px);
  overflow: hidden;
}

:deep(.grid-stack-item.is-palette-dragging .palette-item) {
  cursor: grabbing;
  opacity: 0.92;
}

:deep(.grid-stack-placeholder > .placeholder-content) {
  background: rgba(111, 66, 193, 0.08);
  border: 1px dashed rgba(111, 66, 193, 0.45);
  inset: var(--palette-grid-margin, 4px);
}

:global(:root[data-theme='toss']) .palette-grid-guide span {
  background: var(--grid-guide-bg);
  border-color: var(--grid-guide-border);
}

:global(:root[data-theme='toss']) :deep(.grid-stack-placeholder > .placeholder-content) {
  background: var(--grid-guide-bg);
  border-color: var(--grid-guide-border);
}
</style>
