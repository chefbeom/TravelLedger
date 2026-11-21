<script setup>
import { computed } from 'vue'
import { formatDayHeading } from '../../lib/integratedCalendarView.js'
import CalendarIcon from './CalendarIcon.vue'
import DayTimelineRow from './DayTimelineRow.vue'

const props = defineProps({
  date: { type: String, required: true },
  todayIso: { type: String, required: true },
  timeline: { type: Array, required: true },
  dayTotals: { type: Object, default: null },
  initialLoading: { type: Boolean, default: false },
  revalidating: { type: Boolean, default: false },
  pendingSources: { type: Array, default: () => [] },
  hideAmounts: { type: Boolean, default: false },
  mode: { type: String, default: 'panel' }, // panel | sheet
  noLayers: { type: Boolean, default: false },
  sharedBusy: { type: Boolean, default: false },
  currency: { type: Function, required: true },
})
const emit = defineEmits(['open', 'add-event', 'prev-day', 'next-day', 'close'])
const GROUPS = [['allday', '종일·기간'], ['timed', '시간순'], ['untimed', '시간 미지정']]
const heading = computed(() => formatDayHeading(props.date, props.todayIso))
const groups = computed(() => GROUPS.map(([key, label]) => ({ key, label, items: props.timeline.filter(item => item.group === key) })).filter(group => group.items.length))
const totalsText = computed(() => {
  const totals = props.dayTotals
  if (!totals) return ''
  return [totals.income ? `수입 ${props.currency(totals.income)}` : '', totals.expense ? `지출 ${props.currency(totals.expense)}` : ''].filter(Boolean).join(' · ')
})
const showSkeleton = computed(() => props.initialLoading && !props.timeline.length)
const showEmpty = computed(() => !props.initialLoading && !props.pendingSources.length && !props.timeline.length)
</script>

<template>
  <div class="integrated-calendar__panel" :class="`integrated-calendar__panel--${mode}`">
    <div v-if="mode === 'sheet'" class="integrated-calendar__handle" aria-hidden="true" />
    <header class="integrated-calendar__panel-head">
      <div class="integrated-calendar__panel-title">
        <button type="button" class="integrated-calendar__icon-button" aria-label="이전 날" @click="emit('prev-day')"><CalendarIcon name="chevron-left" :size="18" /></button>
        <h2 id="integrated-calendar-day-title" tabindex="-1">{{ heading }}</h2>
        <span v-if="date === todayIso" class="integrated-calendar__today-label">오늘</span>
        <button type="button" class="integrated-calendar__icon-button" aria-label="다음 날" @click="emit('next-day')"><CalendarIcon name="chevron-right" :size="18" /></button>
      </div>
      <p v-if="totalsText" class="integrated-calendar__panel-totals">{{ totalsText }}</p>
      <div class="integrated-calendar__panel-actions">
        <button type="button" class="integrated-calendar__add" @click="emit('add-event')"><CalendarIcon name="plus" :size="16" />일정 추가</button>
        <button v-if="mode === 'sheet'" type="button" class="integrated-calendar__close" @click="emit('close')">닫기</button>
      </div>
    </header>
    <ol class="integrated-calendar__timeline" aria-label="이 날의 항목" :aria-busy="initialLoading || revalidating ? 'true' : 'false'">
      <template v-if="showSkeleton">
        <li v-for="n in 3" :key="n" class="integrated-calendar__skeleton-row" aria-hidden="true" />
      </template>
      <template v-for="group in groups" :key="group.key">
        <li role="presentation" class="integrated-calendar__timeline-group">{{ group.label }}</li>
        <DayTimelineRow v-for="item in group.items" :key="item.key" :item="item" :hide-amounts="hideAmounts" :busy="sharedBusy && item.primary.action.type === 'shared-travel'" :currency="currency" @open="emit('open', $event, item)" />
      </template>
    </ol>
    <p v-if="pendingSources.length && !showSkeleton" role="status" class="integrated-calendar__panel-note">나머지 자료를 불러오는 중…</p>
    <p v-if="showEmpty" class="integrated-calendar__panel-empty">{{ noLayers ? '표시할 자료를 하나 이상 켜 주세요.' : '이 날짜에 표시할 항목이 없습니다.' }}</p>
  </div>
</template>

<style scoped>
.integrated-calendar__panel { display: grid; gap: .75rem; align-content: start; min-width: 0; }
.integrated-calendar__handle { justify-self: center; width: 2.5rem; height: .25rem; background: var(--ic-line); }
.integrated-calendar__panel-head { display: grid; gap: .5rem; }
.integrated-calendar__panel-title { display: flex; align-items: center; gap: .25rem; min-width: 0; }
.integrated-calendar .integrated-calendar__panel-title h2 { margin: 0; flex: 0 1 auto; font-size: 1.125rem; line-height: 1.3; }
.integrated-calendar__panel-title h2:focus { outline: none; }
.integrated-calendar__panel-title h2:focus-visible { outline: 3px solid var(--focus-ring, currentColor); outline-offset: 2px; }
.integrated-calendar .integrated-calendar__today-label { padding: 0 .375rem; background: var(--ic-today); color: var(--ic-accent-contrast) !important; -webkit-text-fill-color: var(--ic-accent-contrast) !important; font-size: .75rem; font-weight: 700; line-height: 1.4; }
.integrated-calendar .integrated-calendar__panel-title .integrated-calendar__icon-button { display: inline-flex; align-items: center; justify-content: center; min-width: 2.75rem; min-height: 2.75rem; padding: 0; }
.integrated-calendar .integrated-calendar__panel-title .integrated-calendar__icon-button:first-child { margin-left: -.25rem; }
.integrated-calendar .integrated-calendar__panel-totals { margin: 0; font-weight: 700; }
.integrated-calendar__panel-actions { display: flex; gap: .5rem; flex-wrap: wrap; }
.integrated-calendar .integrated-calendar__add, .integrated-calendar .integrated-calendar__close { display: inline-flex; align-items: center; gap: .375rem; min-height: 2.75rem; padding: .25rem .875rem; }
.integrated-calendar__timeline { display: grid; gap: .375rem; margin: 0; padding: 0; list-style: none; }
.integrated-calendar .integrated-calendar__timeline-group { margin-top: .375rem; color: var(--ic-text-soft); font-size: .8125rem; font-weight: 700; }
.integrated-calendar__skeleton-row { height: 3.25rem; background: var(--ic-surface-muted); }
.integrated-calendar .integrated-calendar__panel-note, .integrated-calendar .integrated-calendar__panel-empty { margin: 0; color: var(--ic-text-soft); }
</style>
