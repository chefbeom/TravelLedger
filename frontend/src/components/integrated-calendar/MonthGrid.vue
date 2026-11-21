<script setup>
import { formatCellAmount, formatDayHeading } from '../../lib/integratedCalendarView.js'
import CalendarIcon from './CalendarIcon.vue'

const props = defineProps({
  weeks: { type: Array, required: true },
  selectedDate: { type: String, required: true },
  todayIso: { type: String, required: true },
  hideAmounts: { type: Boolean, default: false },
  initialLoading: { type: Boolean, default: false },
  revalidating: { type: Boolean, default: false },
  mini: { type: Boolean, default: false },
})
const emit = defineEmits(['select'])
const WEEKDAYS = ['일', '월', '화', '수', '목', '금', '토']
const MAX_CHIPS = 3, MAX_DOTS = 4

function ariaLabel(day) {
  const summary = day.summary
  const total = summary?.total || 0
  return `${formatDayHeading(day.date, props.todayIso)}${day.date === props.todayIso ? ', 오늘' : ''}, ${total}개 항목${total ? `: ${summary.items.map(item => item.text).join(', ')}` : ''}`
}
function moneyLines(day) {
  const ledger = day.summary?.ledger
  if (!ledger) return []
  if (props.hideAmounts) return [{ tone: 'neutral', text: '•••' }]
  const options = { compact: props.mini }
  const lines = []
  if (ledger.income) lines.push({ tone: 'income', text: formatCellAmount(ledger.income, options) })
  if (ledger.expense) lines.push({ tone: 'expense', text: formatCellAmount(-ledger.expense, options) })
  return lines
}
function shownCounts(day) {
  return (day.summary?.counts || []).slice(0, props.mini ? MAX_DOTS : MAX_CHIPS)
}
function moreCount(day) {
  const counts = day.summary?.counts || []
  return (day.hiddenBars || 0) + Math.max(0, counts.length - (props.mini ? MAX_DOTS : MAX_CHIPS))
}
function onClick(event, day) {
  emit('select', day.date, { fromMore: Boolean(event.target.closest?.('.integrated-calendar__more')) })
}
</script>

<template>
  <div class="integrated-calendar__month" :class="{ 'integrated-calendar__month--mini': mini }" :aria-busy="revalidating || initialLoading ? 'true' : 'false'">
    <div v-if="revalidating" class="integrated-calendar__progress" aria-hidden="true" />
    <div class="integrated-calendar__weekdays" aria-hidden="true">
      <span v-for="(name, index) in WEEKDAYS" :key="name" :data-weekday="index">{{ name }}</span>
    </div>
    <div class="integrated-calendar__weeks">
      <div v-for="week in weeks" :key="week.key" class="integrated-calendar__week" :style="{ '--ic-lane-count': week.laneCount }">
        <div class="integrated-calendar__bars" aria-hidden="true">
          <span v-for="bar in week.bars" :key="bar.key" class="integrated-calendar__bar" :data-key="bar.key" :data-layer="bar.layer" :data-shared="bar.shared ? 'true' : 'false'"
            :data-continues-before="bar.continuesBefore ? 'true' : 'false'" :data-continues-after="bar.continuesAfter ? 'true' : 'false'"
            :style="{ gridColumn: `${bar.startCol + 1} / span ${bar.span}`, gridRow: bar.lane + 1 }">
            <template v-if="!mini"><CalendarIcon v-if="bar.shared" name="shared" :size="12" /><span class="integrated-calendar__bar-title">{{ bar.title }}</span></template>
          </span>
        </div>
        <button v-for="day in week.days" :key="day.date" type="button" class="integrated-calendar__cell"
          :class="{ 'integrated-calendar__cell--other': !day.inMonth, 'integrated-calendar__cell--selected': day.date === selectedDate, 'integrated-calendar__cell--today': day.date === todayIso }"
          :data-date="day.date" :data-weekday="day.weekday" :aria-pressed="day.date === selectedDate" :aria-current="day.date === todayIso ? 'date' : undefined" :aria-label="ariaLabel(day)" @click="onClick($event, day)">
          <span class="integrated-calendar__cell-date">
            <strong class="integrated-calendar__cell-number">{{ Number(day.date.slice(-2)) }}</strong>
            <span v-if="day.date === todayIso && !mini" class="integrated-calendar__today-badge">오늘</span>
          </span>
          <span class="integrated-calendar__cell-lanes" aria-hidden="true" />
          <template v-if="initialLoading">
            <span class="integrated-calendar__skeleton" aria-hidden="true" /><span class="integrated-calendar__skeleton integrated-calendar__skeleton--short" aria-hidden="true" />
          </template>
          <template v-else>
            <span v-if="moneyLines(day).length" class="integrated-calendar__cell-moneys">
              <span v-for="line in moneyLines(day)" :key="line.tone" class="integrated-calendar__cell-money" :data-tone="line.tone">{{ line.text }}</span>
            </span>
            <span v-if="shownCounts(day).length" class="integrated-calendar__cell-counts" aria-hidden="true">
              <span v-for="count in shownCounts(day)" :key="count.key" class="integrated-calendar__count" :data-layer="count.layer" :data-shared="count.shared ? 'true' : 'false'"><span class="integrated-calendar__count-box" /><template v-if="!mini">{{ count.count }}</template></span>
            </span>
            <span v-if="moreCount(day)" class="integrated-calendar__more">+{{ moreCount(day) }}</span>
          </template>
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.integrated-calendar__month { --ic-date-row-h: 1.75rem; --ic-bar-h: 1.125rem; --ic-bar-gap: .125rem; position: relative; min-width: 0; display: grid; gap: .25rem; align-content: start; }
.integrated-calendar__progress { position: absolute; top: 0; left: 0; right: 0; height: 2px; background: var(--ic-accent); z-index: 2; }
@media (prefers-reduced-motion: no-preference) { .integrated-calendar__progress { animation: ic-progress 1s ease-in-out infinite alternate; transform-origin: left; } }
@keyframes ic-progress { from { transform: scaleX(.15); } to { transform: scaleX(1); } }
.integrated-calendar__weekdays, .integrated-calendar__week { display: grid; grid-template-columns: repeat(7, minmax(0, 1fr)); gap: .25rem; }
.integrated-calendar__weekdays span { padding: .25rem .5rem; font-weight: 700; }
.integrated-calendar__weekdays span[data-weekday='0'] { color: var(--ic-sunday); }
.integrated-calendar__weekdays span[data-weekday='6'] { color: var(--ic-saturday); }
.integrated-calendar__weeks { display: grid; gap: .25rem; }
.integrated-calendar__week { position: relative; }
.integrated-calendar__bars { position: absolute; z-index: 1; inset: var(--ic-date-row-h) 0 auto 0; display: grid; grid-template-columns: repeat(7, minmax(0, 1fr)); column-gap: .25rem; grid-auto-rows: var(--ic-bar-h); row-gap: var(--ic-bar-gap); pointer-events: none; }
.integrated-calendar__bar { display: flex; align-items: center; gap: .25rem; min-width: 0; box-sizing: border-box; padding-inline: .375rem; overflow: hidden; background: var(--ic-layer-soft); border-left: 3px solid var(--ic-layer); color: var(--ic-text); font-size: .75rem; font-weight: 600; line-height: 1; position: relative; }
.integrated-calendar__bar[data-continues-before='true'] { border-left: 0; }
.integrated-calendar__bar[data-continues-after='true']::after { content: '›'; position: absolute; right: .25rem; font-weight: 700; }
.integrated-calendar__bar[data-shared='true'] { outline: 1px dashed var(--ic-layer); outline-offset: -1px; }
.integrated-calendar__bar-title { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.integrated-calendar .integrated-calendar__cell { display: flex; flex-direction: column; align-items: stretch; gap: .125rem; box-sizing: border-box; min-width: 0; min-height: 6.5rem; padding: 0 .25rem .25rem; overflow: hidden; text-align: left; cursor: pointer; background-color: var(--ic-surface) !important; border: 1px solid var(--ic-line) !important; color: var(--ic-text) !important; -webkit-text-fill-color: var(--ic-text) !important; box-shadow: none !important; }
.integrated-calendar .integrated-calendar__cell--other { background-color: var(--ic-surface-muted) !important; color: var(--ic-text-soft) !important; -webkit-text-fill-color: var(--ic-text-soft) !important; }
.integrated-calendar .integrated-calendar__cell--selected { background-color: var(--ic-accent-soft) !important; border-color: var(--ic-accent) !important; box-shadow: inset 0 0 0 1px var(--ic-accent) !important; }
@media (hover: hover) { .integrated-calendar .integrated-calendar__cell:hover { border-color: var(--ic-accent) !important; } }
.integrated-calendar__cell-date { display: flex; align-items: center; gap: .25rem; height: calc(var(--ic-date-row-h) - 1px); flex: none; }
.integrated-calendar .integrated-calendar__cell-number { box-sizing: border-box; min-width: 1.5rem; padding: 0 .25rem; border: 2px solid transparent; text-align: center; font-size: .9375rem; line-height: 1.25rem; color: var(--ic-text) !important; -webkit-text-fill-color: var(--ic-text) !important; }
.integrated-calendar .integrated-calendar__cell[data-weekday='0'] .integrated-calendar__cell-number { color: var(--ic-sunday) !important; -webkit-text-fill-color: var(--ic-sunday) !important; }
.integrated-calendar .integrated-calendar__cell[data-weekday='6'] .integrated-calendar__cell-number { color: var(--ic-saturday) !important; -webkit-text-fill-color: var(--ic-saturday) !important; }
.integrated-calendar .integrated-calendar__cell--other[data-date] .integrated-calendar__cell-number { color: var(--ic-text-soft) !important; -webkit-text-fill-color: var(--ic-text-soft) !important; }
.integrated-calendar .integrated-calendar__cell--today .integrated-calendar__cell-number { border-color: var(--ic-today); }
.integrated-calendar .integrated-calendar__cell--selected[data-date] .integrated-calendar__cell-number { background: var(--ic-accent); border-color: var(--ic-accent); color: var(--ic-accent-contrast) !important; -webkit-text-fill-color: var(--ic-accent-contrast) !important; }
.integrated-calendar .integrated-calendar__today-badge { padding: 0 .375rem; background: var(--ic-today); color: var(--ic-accent-contrast) !important; -webkit-text-fill-color: var(--ic-accent-contrast) !important; font-size: .6875rem; line-height: 1rem; font-weight: 700; }
.integrated-calendar__cell-lanes { flex: none; height: calc(var(--ic-lane-count, 0) * (var(--ic-bar-h) + var(--ic-bar-gap))); }
.integrated-calendar__cell-moneys { display: grid; }
.integrated-calendar .integrated-calendar__cell-money { font-size: .75rem; font-weight: 700; line-height: 1.2; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.integrated-calendar .integrated-calendar__cell-money[data-tone='income'] { color: var(--ic-income) !important; -webkit-text-fill-color: var(--ic-income) !important; }
.integrated-calendar .integrated-calendar__cell-money[data-tone='expense'] { color: var(--ic-expense) !important; -webkit-text-fill-color: var(--ic-expense) !important; }
.integrated-calendar__cell-counts { display: flex; flex-wrap: wrap; gap: .125rem .5rem; }
.integrated-calendar__count { display: inline-flex; align-items: center; gap: .25rem; font-size: .75rem; line-height: 1.2; }
.integrated-calendar__count-box { width: .5rem; height: .5rem; background: var(--ic-layer); }
.integrated-calendar__count[data-shared='true'] .integrated-calendar__count-box { background: transparent; outline: 1px dashed var(--ic-layer); outline-offset: -1px; }
.integrated-calendar__more { font-size: .75rem; font-weight: 700; line-height: 1.2; }
.integrated-calendar__skeleton { display: block; height: .5rem; width: 70%; background: var(--ic-surface-muted); }
.integrated-calendar__skeleton--short { width: 40%; }

@media (max-width: 35rem) {
  .integrated-calendar__month { --ic-date-row-h: 1.25rem; --ic-bar-h: .375rem; --ic-bar-gap: .125rem; }
  .integrated-calendar__weekdays span { padding: .25rem 0; text-align: center; }
  .integrated-calendar .integrated-calendar__cell { height: 4.75rem; min-height: 0; padding: 0 .125rem .125rem; gap: 0; }
  .integrated-calendar .integrated-calendar__cell-number { min-width: 0; padding: 0 .125rem; font-size: .8125rem; line-height: 1rem; }
  .integrated-calendar__bar { padding: 0; background: var(--ic-layer); border-left: 0; }
  .integrated-calendar__bar[data-shared='true'] { outline: 0; background: repeating-linear-gradient(135deg, var(--ic-layer) 0 3px, transparent 3px 6px); }
  .integrated-calendar .integrated-calendar__cell-money { font-size: .6875rem; line-height: 1.1; }
  .integrated-calendar__cell-counts { gap: .125rem; }
  .integrated-calendar__count-box { width: .375rem; height: .375rem; }
  .integrated-calendar__more { font-size: .6875rem; }
}
</style>
