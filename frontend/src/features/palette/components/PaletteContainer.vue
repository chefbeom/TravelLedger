<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import DragDropGrid from './DragDropGrid.vue'
import { useDashboardPaletteStore } from '../../../stores/useDashboardPaletteStore'

const props = defineProps({
  currentUser: {
    type: Object,
    default: null,
  },
  dashboard: {
    type: Object,
    default: () => ({}),
  },
  calendarWeeks: {
    type: Array,
    default: () => [],
  },
  monthLabel: {
    type: String,
    default: '',
  },
  anchorDate: {
    type: String,
    default: '',
  },
  entries: {
    type: Array,
    default: () => [],
  },
  isLoading: {
    type: Boolean,
    default: false,
  },
})

const store = useDashboardPaletteStore()
const toolsOpen = ref(false)
const selectedTemplateId = ref('kpi-month')
const toolsPanelRef = ref(null)
const toolsButtonRef = ref(null)
const recentFlowLimits = [5, 6, 7, 8, 9, 10]
const recentFlowPalettes = computed(() => store.visiblePalettes.filter((palette) => (
  palette.type === 'kpi' && palette.options?.variant === 'recentFlow'
)))

const userStorageId = computed(() => props.currentUser?.id || props.currentUser?.loginId || 'anonymous')
const paletteContext = computed(() => ({
  dashboard: props.dashboard,
  calendarWeeks: props.calendarWeeks,
  monthLabel: props.monthLabel,
  anchorDate: props.anchorDate,
  entries: props.entries,
}))

watch(
  userStorageId,
  (value) => {
    store.hydrate({ userId: value, scope: 'household' })
  },
  { immediate: true },
)

function closeToolsOnOutsidePointer(event) {
  if (!toolsOpen.value) {
    return
  }
  if (toolsPanelRef.value?.contains(event.target) || toolsButtonRef.value?.contains(event.target)) {
    return
  }
  toolsOpen.value = false
}

function startEditMode() {
  if (!store.isEditMode) {
    store.toggleEditMode()
  }
}

function finishEditMode() {
  if (store.isEditMode) {
    store.toggleEditMode()
  }
  toolsOpen.value = false
}

function handleAddPalette() {
  store.addPalette(selectedTemplateId.value)
}

function handleResetPreset() {
  const confirmed = window.confirm('현재 프리셋의 팔레트 배치를 초기화할까요?')
  if (confirmed) {
    store.resetPreset(store.currentPresetId)
  }
}

function updateRecentFlowPalette(palette, patch) {
  store.updatePaletteOptions(palette.id, patch)
}

function hiddenPaletteTitle(palette) {
  const template = store.availableTemplates.find((item) => item.type === palette.type && item.options?.variant === palette.options?.variant)
  return template?.label || palette.id
}

onMounted(() => {
  document.addEventListener('pointerdown', closeToolsOnOutsidePointer)
})

onBeforeUnmount(() => {
  store.flushRemotePersist()
  document.removeEventListener('pointerdown', closeToolsOnOutsidePointer)
})
</script>

<template>
  <section class="palette-dashboard">
    <div v-if="isLoading" class="palette-dashboard__loading">대시보드 데이터를 불러오는 중입니다.</div>

    <DragDropGrid
      :palettes="store.visiblePalettes"
      :context="paletteContext"
      :edit-mode="store.isEditMode"
      @apply-layout-patches="store.applyLayoutPatches"
      @resize-palette="store.resizePalette"
      @hide-palette="store.hidePalette"
      @remove-palette="store.removePalette"
    />

    <button
      ref="toolsButtonRef"
      class="palette-dashboard__floating-button"
      type="button"
      :class="{ 'is-active': toolsOpen || store.isEditMode }"
      @click="toolsOpen = !toolsOpen"
    >
      설정
    </button>

    <aside v-if="toolsOpen" ref="toolsPanelRef" class="palette-dashboard__tools" data-no-drag="true">
      <div class="palette-dashboard__tools-head">
        <strong>대시보드</strong>
        <button type="button" @click="toolsOpen = false">닫기</button>
      </div>

      <div class="palette-dashboard__tools-body">
        <label class="palette-dashboard__field">
          <span>프리셋</span>
          <select :value="store.currentPresetId" @change="store.setPreset(Number($event.target.value))">
            <option v-for="preset in store.presets" :key="preset.id" :value="preset.id">
              {{ preset.name }}
            </option>
          </select>
        </label>

        <div class="palette-dashboard__preset-info">
          <span>{{ store.currentPreset?.name }}</span>
          <strong>{{ store.visiblePalettes.length }}개 표시</strong>
        </div>

        <template v-if="store.isEditMode">
          <label class="palette-dashboard__field">
            <span>팔레트 추가</span>
            <select v-model="selectedTemplateId">
              <option v-for="template in store.availableTemplates" :key="template.id" :value="template.id">
                {{ template.label }}
              </option>
            </select>
          </label>
          <button class="palette-dashboard__secondary" type="button" @click="handleAddPalette">
            추가
          </button>
          <div v-if="recentFlowPalettes.length" class="palette-dashboard__option-panel">
            <span>최근 흐름 설정</span>
            <div v-for="palette in recentFlowPalettes" :key="palette.id" class="palette-dashboard__option-card">
              <small>{{ hiddenPaletteTitle(palette) }}</small>
              <label class="palette-dashboard__field">
                <span>표시 기준</span>
                <select
                  :value="palette.options?.entryType || 'EXPENSE'"
                  @change="updateRecentFlowPalette(palette, { entryType: $event.target.value })"
                >
                  <option value="EXPENSE">지출</option>
                  <option value="INCOME">수입</option>
                </select>
              </label>
              <label class="palette-dashboard__field">
                <span>표시 개수</span>
                <select
                  :value="palette.options?.limit || 8"
                  @change="updateRecentFlowPalette(palette, { limit: Number($event.target.value) })"
                >
                  <option v-for="limit in recentFlowLimits" :key="limit" :value="limit">
                    {{ limit }}개
                  </option>
                </select>
              </label>
            </div>
          </div>

          <div class="palette-dashboard__hidden">
            <span>숨긴 팔레트</span>
            <button
              v-for="palette in store.hiddenPalettes"
              :key="palette.id"
              type="button"
              @click="store.restorePalette(palette.id)"
            >
              {{ hiddenPaletteTitle(palette) }}
            </button>
            <small v-if="!store.hiddenPalettes.length">숨긴 팔레트가 없습니다.</small>
          </div>
        </template>
      </div>

      <div class="palette-dashboard__tools-footer">
        <button v-if="!store.isEditMode" class="palette-dashboard__primary" type="button" @click="startEditMode">
          편집 시작
        </button>
        <template v-else>
          <button class="palette-dashboard__secondary" type="button" @click="handleResetPreset">
            초기화
          </button>
          <button class="palette-dashboard__primary" type="button" @click="finishEditMode">
            저장 및 완료
          </button>
        </template>
      </div>
    </aside>
  </section>
</template>

<style scoped>
.palette-dashboard {
  --household-dash-bg: #eef2ef;
  --household-dash-card: #ffffff;
  --household-dash-ink: #10201f;
  --household-dash-muted: #667775;
  --household-dash-line: rgba(0, 105, 96, 0.14);
  --household-dash-strong-line: rgba(0, 105, 96, 0.22);
  --household-dash-panel: rgba(255, 255, 255, 0.96);
  --household-dash-control: #f4f8f6;
  --household-dash-control-hover: #d7ff35;
  --household-dash-tile: #f6faf8;
  --household-dash-track: rgba(0, 105, 96, 0.1);
  --household-dash-teal: #006960;
  --household-dash-teal-strong: #00534d;
  --household-dash-teal-soft: #d7f3ed;
  --household-dash-mint: #67ded1;
  --household-dash-mint-soft: #dff8f4;
  --household-dash-lime: #d7ff35;
  --household-dash-lime-soft: #f1ffbe;
  --household-dash-coral: #ff765f;
  --household-dash-coral-soft: #ffe2dc;
  --household-dash-positive: #006960;
  --household-dash-negative: #c7513f;
  --household-dash-shadow: 0 18px 46px rgba(0, 83, 77, 0.08);
  --calendar-board-bg: var(--household-dash-bg);
  --calendar-panel-bg: var(--household-dash-card);
  --calendar-panel-border: var(--household-dash-line);
  --calendar-section-border: var(--household-dash-line);
  --calendar-day-bg: var(--household-dash-tile);
  --calendar-day-border: var(--household-dash-line);
  --calendar-track-expense-bg: var(--household-dash-track);
  --field-bg: var(--household-dash-control);
  --field-bg-muted: var(--household-dash-tile);
  --field-border: var(--household-dash-line);
  --control-bg: var(--household-dash-control);
  --control-bg-hover: var(--household-dash-control-hover);
  --text: var(--household-dash-ink);
  --text-soft: var(--household-dash-muted);
  --text-muted: #8a9996;
  --grid-guide-bg: rgba(103, 222, 209, 0.16);
  --grid-guide-border: rgba(0, 105, 96, 0.2);
  background: var(--household-dash-bg);
  border: 0;
  border-radius: 28px;
  min-width: 0;
  overflow-x: hidden;
  padding: 18px;
  position: relative;
}

.palette-dashboard__loading {
  background: var(--household-dash-panel);
  border: 1px solid var(--household-dash-line);
  border-radius: 16px;
  color: var(--household-dash-muted);
  font-size: 0.86rem;
  margin-bottom: 14px;
  padding: 10px 12px;
}

.palette-dashboard__floating-button {
  background: var(--household-dash-teal);
  border: 0;
  border-radius: 10px;
  box-shadow: 0 14px 28px rgba(0, 83, 77, 0.22);
  color: #ffffff;
  font-size: 0.78rem;
  font-weight: 800;
  min-height: 36px;
  padding: 0 12px;
  position: fixed;
  right: 18px;
  top: 50%;
  transform: translateY(-50%);
  z-index: 40;
}

.palette-dashboard__floating-button.is-active,
.palette-dashboard__floating-button:hover {
  background: var(--household-dash-lime);
  color: var(--household-dash-ink);
}

.palette-dashboard__tools {
  background: var(--household-dash-panel);
  border: 1px solid var(--household-dash-line);
  border-radius: 16px;
  box-shadow: 0 18px 40px rgba(0, 83, 77, 0.14);
  display: grid;
  gap: 12px;
  grid-template-rows: auto minmax(0, 1fr) auto;
  max-height: calc(100vh - 36px);
  max-height: calc(100dvh - 36px);
  max-width: calc(100vw - 36px);
  overflow: hidden;
  padding: 14px;
  position: fixed;
  right: 18px;
  top: 50%;
  transform: translateY(-50%);
  width: 280px;
  z-index: 41;
}

.palette-dashboard__tools-body {
  display: grid;
  gap: 12px;
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding-right: 4px;
  scrollbar-gutter: stable;
}

.palette-dashboard__tools-footer {
  align-items: center;
  border-top: 1px solid var(--household-dash-line);
  display: flex;
  gap: 8px;
  padding-top: 12px;
}

.palette-dashboard__tools-footer > button {
  flex: 1 1 0;
}

.palette-dashboard__tools-head {
  align-items: center;
  display: flex;
  justify-content: space-between;
}

.palette-dashboard__tools-head strong {
  color: var(--household-dash-ink);
  font-size: 0.95rem;
}

.palette-dashboard__tools button,
.palette-dashboard__field select {
  border: 1px solid var(--household-dash-line);
  font-size: 0.8rem;
  min-height: 32px;
  padding: 0 9px;
}

.palette-dashboard__tools-head button {
  background: var(--household-dash-control);
  color: var(--household-dash-ink);
}

.palette-dashboard__field {
  display: grid;
  gap: 6px;
}

.palette-dashboard__field span,
.palette-dashboard__hidden > span {
  color: var(--household-dash-teal);
  font-size: 0.72rem;
  font-weight: 800;
}

.palette-dashboard__field select {
  background: var(--household-dash-control);
  color: var(--household-dash-ink);
  width: 100%;
}

.palette-dashboard__preset-info {
  background: var(--household-dash-tile);
  border: 1px solid var(--household-dash-line);
  border-radius: 12px;
  display: grid;
  gap: 4px;
  padding: 10px;
}

.palette-dashboard__preset-info span {
  color: var(--household-dash-muted);
  font-size: 0.76rem;
}

.palette-dashboard__preset-info strong {
  color: var(--household-dash-ink);
  font-size: 0.92rem;
}

.palette-dashboard__primary {
  background: var(--household-dash-teal);
  border-color: transparent;
  color: #ffffff;
  font-weight: 800;
}

.palette-dashboard__secondary {
  background: var(--household-dash-control);
  color: var(--household-dash-ink);
}

.palette-dashboard__option-panel {
  border: 1px solid var(--household-dash-line);
  border-radius: 14px;
  display: grid;
  gap: 10px;
  padding: 10px;
}

.palette-dashboard__option-panel > span,
.palette-dashboard__option-card small {
  color: var(--household-dash-teal);
  font-size: 0.72rem;
  font-weight: 800;
}

.palette-dashboard__option-card {
  background: var(--household-dash-tile);
  border: 1px solid var(--household-dash-line);
  border-radius: 12px;
  display: grid;
  gap: 8px;
  padding: 10px;
}
.palette-dashboard__hidden {
  display: grid;
  gap: 6px;
}

.palette-dashboard__hidden button {
  background: var(--household-dash-control);
  color: var(--household-dash-ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.palette-dashboard__hidden small {
  color: var(--household-dash-muted);
  font-size: 0.76rem;
}

:global(html[data-theme='toss'] .palette-dashboard) {
  --household-dash-bg: linear-gradient(180deg, rgba(18, 24, 33, 0.94), rgba(14, 19, 27, 0.96));
  --household-dash-card: linear-gradient(180deg, rgba(31, 38, 50, 0.97), rgba(24, 31, 42, 0.97));
  --household-dash-ink: #edf3f8;
  --household-dash-muted: #a3b0bf;
  --household-dash-line: rgba(94, 109, 132, 0.32);
  --household-dash-strong-line: rgba(110, 126, 150, 0.44);
  --household-dash-panel: linear-gradient(180deg, rgba(30, 37, 49, 0.98), rgba(23, 30, 41, 0.98));
  --household-dash-control: rgba(33, 41, 54, 0.94);
  --household-dash-control-hover: rgba(43, 52, 67, 0.96);
  --household-dash-tile: rgba(34, 42, 55, 0.8);
  --household-dash-track: rgba(96, 112, 136, 0.24);
  --household-dash-teal: #78c9c0;
  --household-dash-teal-strong: #4da69d;
  --household-dash-teal-soft: rgba(86, 154, 147, 0.16);
  --household-dash-mint: #9ed8d2;
  --household-dash-mint-soft: rgba(105, 176, 168, 0.14);
  --household-dash-lime: #c1d887;
  --household-dash-lime-soft: rgba(193, 216, 135, 0.13);
  --household-dash-coral: #df8f86;
  --household-dash-coral-soft: rgba(223, 143, 134, 0.13);
  --household-dash-positive: #b5d98a;
  --household-dash-negative: #e39a91;
  --household-dash-shadow: 0 18px 34px rgba(0, 0, 0, 0.24);
  --grid-guide-bg: rgba(120, 201, 192, 0.08);
  --grid-guide-border: rgba(120, 201, 192, 0.18);
  background: var(--household-dash-bg);
  border: 1px solid rgba(91, 107, 129, 0.36);
  color: var(--household-dash-ink);
}

:global(html[data-theme='toss'] .palette-dashboard__loading),
:global(html[data-theme='toss'] .palette-dashboard__tools) {
  background: var(--household-dash-panel);
  border-color: var(--household-dash-line);
  box-shadow: 0 18px 40px rgba(0, 0, 0, 0.3);
  color: var(--household-dash-muted);
}

:global(html[data-theme='toss'] .palette-dashboard__tools-head strong),
:global(html[data-theme='toss'] .palette-dashboard__preset-info strong) {
  color: var(--household-dash-ink);
}

:global(html[data-theme='toss'] .palette-dashboard__field span),
:global(html[data-theme='toss'] .palette-dashboard__hidden > span) {
  color: var(--household-dash-teal);
}

:global(html[data-theme='toss'] .palette-dashboard__field select),
:global(html[data-theme='toss'] .palette-dashboard__secondary),
:global(html[data-theme='toss'] .palette-dashboard__hidden button),
:global(html[data-theme='toss'] .palette-dashboard__tools-head button),
:global(html[data-theme='toss'] .palette-dashboard__preset-info) {
  background: var(--household-dash-control);
  border-color: var(--household-dash-line);
  color: var(--household-dash-ink);
}

:global(html[data-theme='toss'] .palette-dashboard__preset-info span),
:global(html[data-theme='toss'] .palette-dashboard__hidden small) {
  color: var(--household-dash-muted);
}

:global(html[data-theme='toss'] .palette-dashboard__primary),
:global(html[data-theme='toss'] .palette-dashboard__floating-button) {
  background: linear-gradient(180deg, var(--household-dash-teal-strong), #367d77);
  border-color: rgba(120, 201, 192, 0.2);
  color: #ffffff;
}

:global(html[data-theme='toss'] .palette-dashboard__floating-button.is-active),
:global(html[data-theme='toss'] .palette-dashboard__floating-button:hover) {
  background: linear-gradient(180deg, #93c9c2, #5fa9a1);
  color: #101820;
}

@media (max-width: 720px) {
  .palette-dashboard {
    padding: 8px;
  }

  .palette-dashboard__floating-button {
    right: 12px;
  }

  .palette-dashboard__tools {
    max-height: calc(100vh - 24px);
    max-height: calc(100dvh - 24px);
    right: 12px;
    width: min(280px, calc(100vw - 24px));
  }
}
</style>
