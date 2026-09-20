<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  fetchLatestWeeklySpendingDigest,
  fetchLedgerAiAnalysisHistory,
  requestWeeklySpendingDigest,
} from '../../../lib/api'

const props = defineProps({
  config: { type: Object, required: true },
  data: { type: Object, default: () => ({}) },
})

const detail = ref(null)
const loading = ref(true)
const requesting = ref(false)
const errorMessage = ref('')
let pollTimer = 0

const history = computed(() => detail.value?.history ?? null)
const result = computed(() => detail.value?.result ?? null)
const isProcessing = computed(() => history.value?.status === 'PROCESSING')
const isBusy = computed(() => loading.value || requesting.value || isProcessing.value)
const automaticEnabled = computed(() => props.data?.autoEnabled === true)
const statusClass = computed(() => 'is-' + (history.value?.status?.toLowerCase() || 'empty'))
const summary = computed(() => (
  result.value?.summary
  || result.value?.report?.keySummary
  || history.value?.summary
  || ''
))
const insights = computed(() => {
  const source = [
    ...(result.value?.highlights ?? []),
    ...(result.value?.categoryInsights ?? []),
    ...(result.value?.trendInsights ?? []),
    ...(result.value?.recommendations ?? []),
  ]
  return [...new Set(source.filter((item) => typeof item === 'string' && item.trim()))].slice(0, 4)
})
const currentExpense = computed(() => Number(result.value?.totalExpense ?? 0))
const previousExpense = computed(() => Number(result.value?.compareTotalExpense ?? 0))
const expenseDelta = computed(() => currentExpense.value - previousExpense.value)
const comparisonText = computed(() => {
  if (!result.value) return ''
  if (previousExpense.value <= 0) {
    return currentExpense.value > 0 ? '전주 지출 없음' : '전주와 동일'
  }
  const percent = (expenseDelta.value / previousExpense.value) * 100
  const percentText = (percent > 0 ? '+' : '') + percent.toFixed(1) + '%'
  return percentText + ' · ' + (expenseDelta.value > 0 ? '+' : '') + formatCurrency(expenseDelta.value)
})
const comparisonTone = computed(() => (
  expenseDelta.value > 0 ? 'negative' : expenseDelta.value < 0 ? 'positive' : 'neutral'
))
const periodLabel = computed(() => {
  const from = history.value?.from
  const to = history.value?.to
  return from && to ? from + ' ~ ' + to : '지난 주'
})
const statusLabel = computed(() => {
  if (isProcessing.value) return '정리 중'
  if (history.value?.status === 'COMPLETED') return '완료'
  if (history.value?.status === 'FAILED') return '실패'
  return '미생성'
})
const buttonLabel = computed(() => {
  if (isProcessing.value || requesting.value) return '요약 생성 중'
  if (history.value?.status === 'FAILED') return '다시 요약'
  return result.value ? '다시 요약' : '지금 요약'
})

function formatCurrency(value) {
  return new Intl.NumberFormat('ko-KR', {
    style: 'currency',
    currency: 'KRW',
    maximumFractionDigits: 0,
  }).format(Number(value ?? 0))
}

function clearPollTimer() {
  if (pollTimer) {
    window.clearTimeout(pollTimer)
    pollTimer = 0
  }
}

function schedulePoll() {
  clearPollTimer()
  const historyId = history.value?.id
  if (!isProcessing.value || !historyId) return
  pollTimer = window.setTimeout(async () => {
    try {
      detail.value = await fetchLedgerAiAnalysisHistory(historyId)
      errorMessage.value = ''
    } catch {
      // Keep the last known status and retry without exposing transport details.
    }
    schedulePoll()
  }, 5000)
}

async function loadLatest() {
  loading.value = true
  errorMessage.value = ''
  try {
    detail.value = await fetchLatestWeeklySpendingDigest()
    schedulePoll()
  } catch (error) {
    errorMessage.value = error?.message || '주간 브리핑을 불러오지 못했습니다.'
  } finally {
    loading.value = false
  }
}

async function runNow() {
  if (isBusy.value) return
  requesting.value = true
  errorMessage.value = ''
  clearPollTimer()
  try {
    detail.value = await requestWeeklySpendingDigest()
    schedulePoll()
  } catch (error) {
    errorMessage.value = error?.message || '주간 브리핑을 생성하지 못했습니다.'
  } finally {
    requesting.value = false
  }
}

onMounted(loadLatest)
onBeforeUnmount(clearPollTimer)
</script>

<template>
  <section class="weekly-digest" aria-live="polite">
    <header class="weekly-digest__head">
      <div>
        <span>지난주 지출 브리핑</span>
        <small>{{ periodLabel }}</small>
      </div>
      <span class="weekly-digest__status" :class="statusClass">
        {{ isProcessing ? '처리 중' : statusLabel }}
      </span>
    </header>

    <div class="weekly-digest__metrics">
      <div class="weekly-digest__metric">
        <span>지난주 지출</span>
        <strong>{{ formatCurrency(result?.totalExpense ?? 0) }}</strong>
        <small>{{ result?.expenseEntryCount ?? 0 }}건</small>
      </div>
      <div class="weekly-digest__metric">
        <span>전주 대비</span>
        <strong :class="'is-' + comparisonTone">
          {{ result ? comparisonText : '비교 대기' }}
        </strong>
        <small v-if="result">비교 지출 {{ formatCurrency(previousExpense) }}</small>
      </div>
    </div>

    <div class="weekly-digest__body">
      <p v-if="summary" class="weekly-digest__summary">{{ summary }}</p>
      <ul v-if="insights.length" class="weekly-digest__insights">
        <li v-for="(insight, index) in insights" :key="index + '-' + insight">{{ insight }}</li>
      </ul>
      <p v-else-if="loading" class="weekly-digest__empty">최근 브리핑을 확인하고 있습니다.</p>
      <p v-else-if="isProcessing" class="weekly-digest__empty">지난주 지출 내역을 정리하고 있습니다.</p>
      <p v-else-if="history?.status === 'FAILED'" class="weekly-digest__empty">
        {{ history.errorMessage || '요약 생성에 실패했습니다. 다시 요청해 주세요.' }}
      </p>
      <p v-else-if="!history" class="weekly-digest__empty">
        주간 브리핑이 아직 없습니다. 지난주 기록이 있으면 바로 생성할 수 있습니다.
      </p>
    </div>

    <footer class="weekly-digest__footer">
      <span :class="{ 'is-enabled': automaticEnabled }">
        {{ automaticEnabled ? '매주 월요일 자동 생성' : '자동 생성 꺼짐' }}
      </span>
      <button type="button" :disabled="isBusy" @click="runNow">
        {{ buttonLabel }}
      </button>
    </footer>
    <p v-if="errorMessage" class="weekly-digest__error" role="alert">{{ errorMessage }}</p>
  </section>
</template>

<style scoped>
.weekly-digest {
  color: var(--household-dash-ink, #10201f);
  display: grid;
  gap: 10px;
  grid-template-rows: auto auto minmax(0, 1fr) auto auto;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.weekly-digest__head,
.weekly-digest__footer {
  align-items: center;
  display: flex;
  gap: 10px;
  justify-content: space-between;
  min-width: 0;
}

.weekly-digest__head > div {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.weekly-digest__head span:first-child {
  color: var(--household-dash-teal, #006960);
  font-size: 0.76rem;
  font-weight: 800;
}

.weekly-digest__head small,
.weekly-digest__metric small {
  color: var(--household-dash-muted, #667775);
  font-size: 0.7rem;
}

.weekly-digest__status {
  background: var(--household-dash-tile, #f6faf8);
  border: 1px solid var(--household-dash-line, rgba(0, 105, 96, 0.14));
  border-radius: 999px;
  flex: 0 0 auto;
  font-size: 0.68rem;
  font-weight: 800;
  padding: 4px 8px;
}

.weekly-digest__status.is-completed {
  color: var(--household-dash-positive, #006960);
}

.weekly-digest__status.is-failed {
  color: var(--household-dash-negative, #c7513f);
}

.weekly-digest__metrics {
  display: grid;
  gap: 8px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.weekly-digest__metric {
  background: var(--household-dash-tile, #f6faf8);
  border: 1px solid var(--household-dash-line, rgba(0, 105, 96, 0.14));
  border-radius: 12px;
  display: grid;
  gap: 3px;
  min-width: 0;
  padding: 8px 10px;
}

.weekly-digest__metric > span {
  color: var(--household-dash-muted, #667775);
  font-size: 0.7rem;
  font-weight: 700;
}

.weekly-digest__metric strong {
  font-size: clamp(0.88rem, 1.4vw, 1.2rem);
  font-variant-numeric: tabular-nums;
  line-height: 1.2;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.weekly-digest__metric strong.is-positive {
  color: var(--household-dash-positive, #006960);
}

.weekly-digest__metric strong.is-negative {
  color: var(--household-dash-negative, #c7513f);
}

.weekly-digest__metric strong.is-neutral {
  color: var(--household-dash-ink, #10201f);
}

.weekly-digest__body {
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
  padding-right: 3px;
}

.weekly-digest__summary {
  color: var(--household-dash-ink, #10201f);
  font-size: 0.8rem;
  line-height: 1.45;
  margin: 0 0 8px;
  overflow-wrap: anywhere;
}

.weekly-digest__insights {
  color: var(--household-dash-muted, #52615f);
  display: grid;
  font-size: 0.74rem;
  gap: 5px;
  line-height: 1.35;
  margin: 0;
  padding-left: 17px;
}

.weekly-digest__empty,
.weekly-digest__error {
  color: var(--household-dash-muted, #667775);
  font-size: 0.75rem;
  line-height: 1.4;
  margin: 0;
}

.weekly-digest__error {
  color: var(--household-dash-negative, #c7513f);
}

.weekly-digest__footer {
  border-top: 1px solid var(--household-dash-line, rgba(0, 105, 96, 0.14));
  padding-top: 8px;
}

.weekly-digest__footer > span {
  color: var(--household-dash-muted, #667775);
  font-size: 0.68rem;
  min-width: 0;
}

.weekly-digest__footer > span.is-enabled {
  color: var(--household-dash-positive, #006960);
}

.weekly-digest__footer button {
  background: var(--household-dash-teal, #006960);
  border: 1px solid transparent;
  border-radius: 8px;
  color: #ffffff;
  flex: 0 0 auto;
  font-size: 0.72rem;
  font-weight: 800;
  min-height: 32px;
  padding: 0 10px;
}

.weekly-digest__footer button:disabled {
  cursor: wait;
  opacity: 0.62;
}

@media (max-width: 520px) {
  .weekly-digest__metrics {
    grid-template-columns: 1fr;
  }

  .weekly-digest__footer {
    align-items: flex-start;
    flex-direction: column;
  }

  .weekly-digest__footer button {
    width: 100%;
  }
}
</style>
