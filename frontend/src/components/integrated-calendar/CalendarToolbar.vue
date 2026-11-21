<script setup>
import { LAYERS, LAYER_LABELS } from '../../lib/integratedCalendarView.js'
import CalendarIcon from './CalendarIcon.vue'
import MonthPicker from './MonthPicker.vue'

defineProps({
  year: { type: Number, required: true },
  month: { type: Number, required: true },
  todayIso: { type: String, required: true },
  revalidating: { type: Boolean, default: false },
  activeFilterCount: { type: Number, default: 0 },
  layers: { type: Object, default: null },
  includeShared: { type: Boolean, default: true },
})
const emit = defineEmits(['move', 'pick-month', 'today', 'refresh', 'open-settings', 'toggle-layer', 'toggle-shared'])
</script>

<template>
  <div class="integrated-calendar__toolbar">
    <div class="integrated-calendar__toolbar-row">
      <h1 class="integrated-calendar__title">통합 달력</h1>
      <div class="integrated-calendar__nav">
        <button type="button" class="integrated-calendar__icon-button" aria-label="이전 달" @click="emit('move', -1)"><CalendarIcon name="chevron-left" :size="18" /></button>
        <MonthPicker :year="year" :month="month" @pick="emit('pick-month', $event)" />
        <button type="button" class="integrated-calendar__icon-button" aria-label="다음 달" @click="emit('move', 1)"><CalendarIcon name="chevron-right" :size="18" /></button>
        <button type="button" class="integrated-calendar__today" @click="emit('today')">오늘</button>
      </div>
      <div class="integrated-calendar__tools">
        <button type="button" class="integrated-calendar__icon-button" :class="{ 'integrated-calendar__icon-button--spin': revalidating }" aria-label="다시 불러오기" :aria-busy="revalidating" @click="emit('refresh')"><CalendarIcon name="refresh" :size="18" /></button>
        <button type="button" class="integrated-calendar__filter-button" aria-label="필터와 공유 설정" aria-haspopup="dialog" @click="emit('open-settings')">
          <CalendarIcon name="filter" :size="16" /><span>필터</span><span v-if="activeFilterCount" class="integrated-calendar__filter-count" aria-hidden="true">{{ activeFilterCount }}</span>
        </button>
      </div>
    </div>
    <div v-if="layers" class="integrated-calendar__layers" role="group" aria-label="표시할 자료">
      <button v-for="layer in LAYERS" :key="layer" type="button" class="integrated-calendar__layer-chip" :data-layer="layer" :aria-pressed="layers[layer]" @click="emit('toggle-layer', layer)">
        <span class="integrated-calendar__chip-face"><span class="integrated-calendar__chip-box" aria-hidden="true"><CalendarIcon v-if="layers[layer]" name="check" :size="12" /></span><CalendarIcon :name="layer" :size="14" />{{ LAYER_LABELS[layer] }}</span>
      </button>
      <button type="button" class="integrated-calendar__layer-chip integrated-calendar__layer-chip--shared" data-layer="shared" :aria-pressed="includeShared" @click="emit('toggle-shared')">
        <span class="integrated-calendar__chip-face"><span class="integrated-calendar__chip-box" aria-hidden="true"><CalendarIcon v-if="includeShared" name="check" :size="12" /></span><CalendarIcon name="shared" :size="14" />공유 자료</span>
      </button>
    </div>
  </div>
</template>

<style scoped>
.integrated-calendar__toolbar { display: grid; gap: .5rem; min-width: 0; }
.integrated-calendar__toolbar-row { display: flex; align-items: center; flex-wrap: wrap; gap: .5rem .75rem; }
.integrated-calendar .integrated-calendar__title { font-size: 1.375rem; margin-right: auto; white-space: nowrap; }
.integrated-calendar__nav, .integrated-calendar__tools { display: flex; align-items: center; gap: .375rem; }
.integrated-calendar .integrated-calendar__icon-button { display: inline-flex; align-items: center; justify-content: center; min-width: 2.75rem; min-height: 2.75rem; padding: 0; }
.integrated-calendar .integrated-calendar__today, .integrated-calendar .integrated-calendar__filter-button { display: inline-flex; align-items: center; justify-content: center; gap: .375rem; min-height: 2.75rem; padding: .25rem .75rem; white-space: nowrap; }
.integrated-calendar .integrated-calendar__filter-count { display: inline-flex; align-items: center; justify-content: center; min-width: 1.25rem; height: 1.25rem; padding: 0 .25rem; background: var(--ic-accent); color: var(--ic-accent-contrast); -webkit-text-fill-color: var(--ic-accent-contrast); font-size: .75rem; font-weight: 700; }
.integrated-calendar__icon-button--spin :deep(svg) { animation: ic-spin 1s linear infinite; }
@media (prefers-reduced-motion: reduce) { .integrated-calendar__icon-button--spin :deep(svg) { animation: none; } }
@keyframes ic-spin { to { transform: rotate(360deg); } }
.integrated-calendar__layers { display: flex; flex-wrap: wrap; gap: .375rem; min-width: 0; }
.integrated-calendar .integrated-calendar__layer-chip { padding: 0; border: 0 !important; background: transparent !important; min-height: 2.75rem; }
.integrated-calendar .integrated-calendar__chip-face { display: inline-flex; align-items: center; gap: .375rem; box-sizing: border-box; min-height: 2.25rem; padding: .25rem .75rem; border: 1px solid var(--ic-line); background: transparent; color: var(--ic-text-soft); -webkit-text-fill-color: currentColor; font-size: .875rem; white-space: nowrap; }
.integrated-calendar .integrated-calendar__chip-box { display: inline-flex; align-items: center; justify-content: center; width: .875rem; height: .875rem; border: 1px solid currentColor; }
.integrated-calendar .integrated-calendar__layer-chip[aria-pressed='true'] .integrated-calendar__chip-face { background: var(--ic-layer-soft, var(--ic-surface-muted)); border-color: var(--ic-layer, var(--ic-line)); color: var(--ic-text); }
.integrated-calendar .integrated-calendar__layer-chip[aria-pressed='true'] .integrated-calendar__chip-box { background: var(--ic-layer, var(--ic-accent)); border-color: var(--ic-layer, var(--ic-accent)); color: var(--ic-surface); }
.integrated-calendar .integrated-calendar__layer-chip--shared[aria-pressed='true'] .integrated-calendar__chip-face { border-style: dashed; }
@media (max-width: 35rem) {
  .integrated-calendar__nav { order: 3; flex: 1 1 100%; justify-content: space-between; }
  .integrated-calendar__layers { flex-wrap: nowrap; overflow-x: auto; scrollbar-width: none; }
  .integrated-calendar__layers::-webkit-scrollbar { display: none; }
  .integrated-calendar .integrated-calendar__title { font-size: 1.125rem; }
}
</style>
