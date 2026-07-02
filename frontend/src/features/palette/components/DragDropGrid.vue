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
const keyboardMoveItems = computed(() => renderItems.value.map((item) => {
  const span = getSpanBySize(item.config.size)
  const position = item.config.position ?? { x: 0, y: 0 }
  const maxX = Math.max(0, DASHBOARD_GRID_COLUMNS - span.w)
  const x = Math.min(maxX, Math.max(0, Number(position.x ?? 0)))
  const y = Math.max(0, Number(position.y ?? 0))

  return {
    id: item.config.id,
    title: item.title || item.definition?.label || item.config.id,
    x,
    y,
    canMoveUp: y > 0,
    canMoveLeft: x > 0,
    canMoveRight: x < maxX,
  }
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

function supportedSizeSpans(definition) {
  const sizes = Array.isArray(definition?.supportedSizes) && definition.supportedSizes.length
    ? definition.supportedSizes
    : ['2x2']
  return sizes.map((size) => ({ size, span: getSpanBySize(size) }))
}

function resizeAttrs(item) {
  const spans = supportedSizeSpans(item?.definition)
  return {
    minW: Math.min(...spans.map((itemSpan) => itemSpan.span.w)),
    minH: Math.min(...spans.map((itemSpan) => itemSpan.span.h)),
    maxW: Math.min(DASHBOARD_GRID_COLUMNS, Math.max(...spans.map((itemSpan) => itemSpan.span.w))),
    maxH: Math.max(...spans.map((itemSpan) => itemSpan.span.h)),
  }
}

function closestSupportedSize(item, width, height) {
  const spans = supportedSizeSpans(item?.definition)
  const exact = spans.find((itemSpan) => itemSpan.span.w === width && itemSpan.span.h === height)
  if (exact) {
    return exact.size
  }

  return spans.slice().sort((left, right) => {
    const leftScore = Math.abs(left.span.w - width) * 10
      + Math.abs(left.span.h - height) * 10
      + Math.abs((left.span.w * left.span.h) - (width * height))
    const rightScore = Math.abs(right.span.w - width) * 10
      + Math.abs(right.span.h - height) * 10
      + Math.abs((right.span.w * right.span.h) - (width * height))
    return leftScore - rightScore
  })[0]?.size ?? item?.config?.size ?? '2x2'
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
  return (grid.engine?.nodes ?? []).map((node) => {
    const id = node.el?.getAttribute('gs-id') || node.el?.getAttribute('data-palette-id')
    const item = renderItems.value.find((candidate) => String(candidate.config.id) === String(id))
    return {
      id,
      position: { x: node.x, y: node.y },
      size: closestSupportedSize(item, node.w, node.h),
    }
  }).filter((patch) => patch.id)
}

function movePaletteByKeyboard(id, deltaX, deltaY) {
  const palette = props.palettes.find((candidate) => String(candidate.id) === String(id))
  if (!palette) {
    return
  }

  const span = getSpanBySize(palette.size)
  const currentPosition = palette.position ?? { x: 0, y: 0 }
  const maxX = Math.max(0, DASHBOARD_GRID_COLUMNS - span.w)
  const nextPosition = {
    x: Math.min(maxX, Math.max(0, Number(currentPosition.x ?? 0) + deltaX)),
    y: Math.max(0, Number(currentPosition.y ?? 0) + deltaY),
  }

  if (nextPosition.x === currentPosition.x && nextPosition.y === currentPosition.y) {
    return
  }

  emit('apply-layout-patches', [{
    id: palette.id,
    position: nextPosition,
    size: palette.size,
  }])
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

function handleResizeStart(event, element) {
  resizeStartLayout = new Map(props.palettes.map((palette) => [String(palette.id), {
    id: String(palette.id),
    size: palette.size,
    position: { ...(palette.position ?? { x: 0, y: 0 }) },
  }]))
  element?.classList.add('is-palette-resizing')
}

function handleResizeStop(event, element) {
  element?.classList.remove('is-palette-resizing')
  emit('apply-layout-patches', readSnapshot())
  resizeStartLayout = new Map()
}

function destroyGrid() {
  if (!grid) {
    return
  }
  grid.off('dragstart')
  grid.off('dragstop')
  grid.off('resizestart')
  grid.off('resizestop')
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
    disableResize: !props.editMode,
    float: false,
    animate: true,
    draggable: {
      appendTo: 'body',
      cancel: 'button,a,input,select,textarea,[data-no-drag="true"]',
      handle: '.palette-item',
      scroll: false,
    },
    resizable: {
      handles: 'e, se, s',
    },
  }, gridElement.value)
  grid.enableMove(props.editMode)
  grid.enableResize(props.editMode)
  grid.on('dragstart', handleDragStart)
  grid.on('dragstop', handleDragStop)
  grid.on('resizestart', handleResizeStart)
  grid.on('resizestop', handleResizeStop)
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
    grid.enableResize(props.editMode)
  }
})

watch(layoutKey, () => {
  queueGridRebuild()
})
</script>

<template>
  <div class="palette-grid-shell" :class="{ 'palette-grid-shell--editing': editMode }" :style="gridShellStyle">
    <section
      v-if="editMode"
      class="palette-grid-keyboard-controls"
      aria-label="Keyboard dashboard layout controls"
      data-no-drag="true"
    >
      <p class="palette-grid-keyboard-controls__hint">
        Keyboard alternative for dashboard layout: move each widget one grid step without pointer drag.
      </p>
      <div
        v-for="item in keyboardMoveItems"
        :key="item.id"
        class="palette-grid-keyboard-controls__row"
      >
        <span class="palette-grid-keyboard-controls__title">{{ item.title }}</span>
        <div class="palette-grid-keyboard-controls__actions">
          <button type="button" :disabled="!item.canMoveUp" :aria-label="`Move ${item.title} up`" @click="movePaletteByKeyboard(item.id, 0, -1)">Up</button>
          <button type="button" :disabled="!item.canMoveLeft" :aria-label="`Move ${item.title} left`" @click="movePaletteByKeyboard(item.id, -1, 0)">Left</button>
          <button type="button" :aria-label="`Move ${item.title} down`" @click="movePaletteByKeyboard(item.id, 0, 1)">Down</button>
          <button type="button" :disabled="!item.canMoveRight" :aria-label="`Move ${item.title} right`" @click="movePaletteByKeyboard(item.id, 1, 0)">Right</button>
        </div>
      </div>
    </section>

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
        :gs-min-w="resizeAttrs(item).minW"
        :gs-min-h="resizeAttrs(item).minH"
        :gs-max-w="resizeAttrs(item).maxW"
        :gs-max-h="resizeAttrs(item).maxH"
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

.palette-grid-keyboard-controls {
  display: grid;
  gap: 10px;
  margin-bottom: 12px;
  padding: 12px;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: var(--surface-soft);
}

.palette-grid-keyboard-controls__hint {
  margin: 0;
  color: var(--text-soft);
  font-size: 0.88rem;
  font-weight: 700;
}

.palette-grid-keyboard-controls__row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 10px;
  align-items: center;
}

.palette-grid-keyboard-controls__title {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 800;
}

.palette-grid-keyboard-controls__actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 6px;
}

.palette-grid-keyboard-controls__actions button {
  min-height: 36px;
  padding: 0 10px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--surface-panel);
  color: var(--text);
  font: inherit;
  font-size: 0.78rem;
  font-weight: 800;
}

.palette-grid-keyboard-controls__actions button:disabled {
  cursor: not-allowed;
  opacity: 0.42;
}

@media (max-width: 640px) {
  .palette-grid-keyboard-controls__row {
    grid-template-columns: minmax(0, 1fr);
  }

  .palette-grid-keyboard-controls__actions {
    justify-content: stretch;
  }

  .palette-grid-keyboard-controls__actions button {
    flex: 1 1 72px;
  }
}

:deep(.grid-stack-item) {
  z-index: 1;
}

:deep(.grid-stack-item-content) {
  inset: var(--palette-grid-margin, 4px);
  overflow: hidden;
}

:deep(.grid-stack-item.is-palette-dragging .palette-item),
:deep(.grid-stack-item.is-palette-resizing .palette-item) {
  cursor: grabbing;
  opacity: 0.92;
}

:deep(.grid-stack-placeholder > .placeholder-content) {
  background: rgba(103, 222, 209, 0.16);
  border: 1px dashed rgba(0, 105, 96, 0.28);
  inset: var(--palette-grid-margin, 4px);
}

:global(html[data-theme='toss'] .palette-grid-guide span) {
  background: var(--grid-guide-bg);
  border-color: var(--grid-guide-border);
}

</style>
