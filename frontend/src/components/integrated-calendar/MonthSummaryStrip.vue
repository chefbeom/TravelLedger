<script setup>
import { formatMonthDay } from '../../lib/integratedCalendarView.js'
import CalendarIcon from './CalendarIcon.vue'
import InfoTip from './InfoTip.vue'

const props = defineProps({
  summary: { type: Object, required: true },
  monthSources: { type: Object, required: true },
  monthErrors: { type: Object, required: true },
  layers: { type: Object, required: true },
  includeShared: { type: Boolean, default: true },
  sharedLoading: { type: Boolean, default: false },
  sharedFailed: { type: Boolean, default: false },
  hideAmounts: { type: Boolean, default: false },
  currency: { type: Function, required: true },
})
const emit = defineEmits(['toggle-hide'])

const state = source => props.monthSources[source] ? 'ready' : props.monthErrors[source] ? '조회 실패' : '불러오는 중'
const hasInsight = () => props.layers.ledger && (props.summary.busiestExpenseDay || props.summary.topCategory) || props.layers.places && props.summary.busiestVisitDay
const TIP = '현재 표시 자료와 필터 기준입니다. 이웃 달 날짜와 공유 자료의 금액은 합계에 포함하지 않습니다.'
</script>

<template>
  <div class="integrated-calendar__summary" aria-label="이번 달 요약">
    <div class="integrated-calendar__kpis">
      <template v-if="layers.ledger">
        <template v-if="state('ledger') === 'ready'">
          <span class="integrated-calendar__kpi"><span class="integrated-calendar__kpi-label">수입</span> <strong class="integrated-calendar__kpi-value integrated-calendar__kpi-value--income">{{ currency(summary.income) }}</strong></span>
          <span class="integrated-calendar__kpi"><span class="integrated-calendar__kpi-label">지출</span> <strong class="integrated-calendar__kpi-value integrated-calendar__kpi-value--expense">{{ currency(summary.expense) }}</strong></span>
          <span class="integrated-calendar__kpi"><span class="integrated-calendar__kpi-label">수지</span> <strong class="integrated-calendar__kpi-value">{{ currency(summary.balance) }}</strong></span>
        </template>
        <span v-else class="integrated-calendar__kpi"><span class="integrated-calendar__kpi-label">가계부 합계</span> <strong class="integrated-calendar__kpi-value">{{ state('ledger') }}</strong></span>
      </template>
      <span v-if="layers.places" class="integrated-calendar__kpi" :class="{ 'integrated-calendar__kpi--minor': state('places') === 'ready' }"><span class="integrated-calendar__kpi-label">장소 기록</span> <strong class="integrated-calendar__kpi-value">{{ state('places') === 'ready' ? `${summary.visits}건` : state('places') }}</strong></span>
      <span v-if="layers.travel" class="integrated-calendar__kpi" :class="{ 'integrated-calendar__kpi--minor': state('travel') === 'ready' }"><span class="integrated-calendar__kpi-label">여행</span> <strong class="integrated-calendar__kpi-value">{{ state('travel') === 'ready' ? `${summary.tripCount}개` : state('travel') }}</strong></span>
      <span v-if="includeShared && layers.travel" class="integrated-calendar__kpi" :class="{ 'integrated-calendar__kpi--minor': !sharedLoading && !sharedFailed }"><span class="integrated-calendar__kpi-label">공유 여행</span> <strong class="integrated-calendar__kpi-value">{{ sharedLoading ? '조회 중' : sharedFailed ? '일부 조회 실패' : `${summary.sharedCount}개` }}</strong><span class="integrated-calendar__kpi-note"> · 합계 제외</span></span>
    </div>
    <div class="integrated-calendar__summary-tools">
      <button type="button" class="integrated-calendar__icon-button" aria-label="금액 숨기기" :aria-pressed="hideAmounts" @click="emit('toggle-hide')"><CalendarIcon :name="hideAmounts ? 'eye-off' : 'eye'" :size="18" /></button>
      <InfoTip :text="TIP" />
    </div>
    <details v-if="hasInsight()" class="integrated-calendar__insights">
      <summary>요약 더보기</summary>
      <ul>
        <li v-if="layers.ledger && summary.busiestExpenseDay">지출이 가장 많았던 날 {{ formatMonthDay(summary.busiestExpenseDay.date) }} · {{ currency(summary.busiestExpenseDay.expense) }}</li>
        <li v-if="layers.ledger && summary.topCategory">지출이 가장 많았던 분류 {{ summary.topCategory.name }} · {{ currency(summary.topCategory.amount) }}</li>
        <li v-if="layers.places && summary.busiestVisitDay">장소 기록이 가장 많았던 날 {{ formatMonthDay(summary.busiestVisitDay.date) }} · {{ summary.busiestVisitDay.count }}건</li>
      </ul>
    </details>
  </div>
</template>

<style scoped>
.integrated-calendar__summary { display: flex; flex-wrap: wrap; align-items: center; gap: .25rem .75rem; padding: .25rem .75rem; border: 1px solid var(--ic-line); background: var(--ic-panel); min-width: 0; }
.integrated-calendar__kpis { display: flex; flex: 1 1 auto; flex-wrap: wrap; align-items: center; gap: .25rem 1rem; min-width: 0; padding: .25rem 0; }
.integrated-calendar__kpi { white-space: nowrap; flex: none; }
.integrated-calendar .integrated-calendar__kpi-label { color: var(--ic-text-soft); font-size: .875rem; }
.integrated-calendar .integrated-calendar__kpi-note { color: var(--ic-text-soft); font-size: .8125rem; }
.integrated-calendar .integrated-calendar__kpi-value { font-size: 1rem; }
.integrated-calendar .integrated-calendar__kpi-value--income { color: var(--ic-income); }
.integrated-calendar .integrated-calendar__kpi-value--expense { color: var(--ic-expense); }
.integrated-calendar__summary-tools { display: flex; align-items: center; gap: 1rem; padding-right: .875rem; }
.integrated-calendar .integrated-calendar__icon-button { display: inline-flex; align-items: center; justify-content: center; min-width: 2.75rem; min-height: 2.75rem; padding: 0; }
.integrated-calendar .integrated-calendar__icon-button[aria-pressed='true'] { background: var(--ic-accent-soft) !important; border-color: var(--ic-accent) !important; }
.integrated-calendar__insights { position: relative; flex: 0 0 auto; }
.integrated-calendar__insights summary { cursor: pointer; min-height: 2.75rem; display: flex; align-items: center; font-size: .875rem; color: var(--ic-text-soft); }
.integrated-calendar .integrated-calendar__insights ul { position: absolute; z-index: 4; right: 0; top: 100%; width: max-content; max-width: min(26rem, 80vw); margin: 0; padding: .625rem .75rem .625rem 1.75rem; border: 1px solid var(--ic-line); background: var(--ic-surface); box-shadow: 0 .25rem 1rem rgba(0, 0, 0, .18); display: grid; gap: .25rem; font-size: .875rem; }
@media (max-width: 35rem) {
  .integrated-calendar__summary { padding: 0 .5rem; gap: 0 .5rem; }
  .integrated-calendar__kpis { gap: 0 .75rem; padding: 0; line-height: 1.25; }
  .integrated-calendar__summary-tools { gap: .5rem; padding-right: .875rem; }
  /* Counts that loaded fine are visible in the grid itself; amounts and failures always stay. */
  .integrated-calendar__kpi--minor { display: none; }
  .integrated-calendar__summary-tools { flex: none; }
  .integrated-calendar__insights { display: none; }
}
</style>
