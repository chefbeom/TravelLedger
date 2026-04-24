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

      <button v-if="!store.isEditMode" class="palette-dashboard__primary" type="button" @click="startEditMode">
        편집 시작
      </button>

      <template v-else>
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

        <button class="palette-dashboard__secondary" type="button" @click="handleResetPreset">
          현재 프리셋 초기화
        </button>
        <button class="palette-dashboard__primary" type="button" @click="finishEditMode">
          편집 완료
        </button>
      </template>
    </aside>
  </section>
</template>

<style scoped>
.palette-dashboard {
  background: #f4f5f7;
  border: 1px solid #d9dde5;
  min-width: 0;
  overflow-x: hidden;
  padding: 12px;
  position: relative;
}

.palette-dashboard__loading {
  background: #ffffff;
  border: 1px solid #d9dde5;
  color: #6b7280;
  font-size: 0.86rem;
  margin-bottom: 10px;
  padding: 10px 12px;
}

.palette-dashboard__floating-button {
  background: #3f2a78;
  border: 1px solid #3f2a78;
  box-shadow: 0 8px 18px rgba(31, 41, 55, 0.16);
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
  background: #5f3dc4;
}

.palette-dashboard__tools {
  background: #ffffff;
  border: 1px solid #cfd5df;
  box-shadow: 0 14px 32px rgba(15, 23, 42, 0.18);
  display: grid;
  gap: 12px;
  max-width: calc(100vw - 36px);
  padding: 14px;
  position: fixed;
  right: 18px;
  top: calc(50% + 46px);
  width: 280px;
  z-index: 41;
}

.palette-dashboard__tools-head {
  align-items: center;
  display: flex;
  justify-content: space-between;
}

.palette-dashboard__tools-head strong {
  color: #111827;
  font-size: 0.95rem;
}

.palette-dashboard__tools button,
.palette-dashboard__field select {
  border: 1px solid #d1d5db;
  font-size: 0.8rem;
  min-height: 32px;
  padding: 0 9px;
}

.palette-dashboard__tools-head button {
  background: #f8fafc;
}

.palette-dashboard__field {
  display: grid;
  gap: 6px;
}

.palette-dashboard__field span,
.palette-dashboard__hidden > span {
  color: #6f42c1;
  font-size: 0.72rem;
  font-weight: 800;
}

.palette-dashboard__field select {
  background: #ffffff;
  width: 100%;
}

.palette-dashboard__preset-info {
  background: #f8fafc;
  border: 1px solid #e5e7eb;
  display: grid;
  gap: 4px;
  padding: 10px;
}

.palette-dashboard__preset-info span {
  color: #6b7280;
  font-size: 0.76rem;
}

.palette-dashboard__preset-info strong {
  color: #111827;
  font-size: 0.92rem;
}

.palette-dashboard__primary {
  background: #3f2a78;
  border-color: #3f2a78;
  color: #ffffff;
  font-weight: 800;
}

.palette-dashboard__secondary {
  background: #f8fafc;
  color: #374151;
}

.palette-dashboard__hidden {
  display: grid;
  gap: 6px;
}

.palette-dashboard__hidden button {
  background: #f8fafc;
  color: #374151;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.palette-dashboard__hidden small {
  color: #9ca3af;
  font-size: 0.76rem;
}

:global(:root[data-theme='toss']) .palette-dashboard {
  background: rgba(12, 16, 23, 0.88);
  border-color: rgba(78, 95, 125, 0.44);
}

:global(:root[data-theme='toss']) .palette-dashboard__loading,
:global(:root[data-theme='toss']) .palette-dashboard__tools {
  background: rgba(18, 23, 31, 0.98);
  border-color: rgba(78, 95, 125, 0.48);
  box-shadow: 0 18px 40px rgba(0, 0, 0, 0.34);
  color: var(--text-soft, #aeb8cb);
}

:global(:root[data-theme='toss']) .palette-dashboard__tools-head strong,
:global(:root[data-theme='toss']) .palette-dashboard__preset-info strong {
  color: var(--text, #f3f7ff);
}

:global(:root[data-theme='toss']) .palette-dashboard__field span,
:global(:root[data-theme='toss']) .palette-dashboard__hidden > span {
  color: #b9c5ff;
}

:global(:root[data-theme='toss']) .palette-dashboard__field select,
:global(:root[data-theme='toss']) .palette-dashboard__secondary,
:global(:root[data-theme='toss']) .palette-dashboard__hidden button,
:global(:root[data-theme='toss']) .palette-dashboard__tools-head button,
:global(:root[data-theme='toss']) .palette-dashboard__preset-info {
  background: rgba(27, 33, 44, 0.96);
  border-color: rgba(78, 95, 125, 0.46);
  color: var(--text, #f3f7ff);
}

:global(:root[data-theme='toss']) .palette-dashboard__preset-info span,
:global(:root[data-theme='toss']) .palette-dashboard__hidden small {
  color: var(--text-soft, #aeb8cb);
}

:global(:root[data-theme='toss']) .palette-dashboard__primary,
:global(:root[data-theme='toss']) .palette-dashboard__floating-button {
  background: #b9ef35;
  border-color: transparent;
  color: #10111f;
}

:global(:root[data-theme='toss']) .palette-dashboard__floating-button.is-active,
:global(:root[data-theme='toss']) .palette-dashboard__floating-button:hover {
  background: #b9c5ff;
  color: #10111f;
}

@media (max-width: 720px) {
  .palette-dashboard {
    padding: 8px;
  }

  .palette-dashboard__floating-button {
    right: 12px;
  }

  .palette-dashboard__tools {
    right: 12px;
    width: min(280px, calc(100vw - 24px));
  }
}
</style>
