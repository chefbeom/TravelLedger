<script setup>
import { computed } from 'vue'

const props = defineProps({
  entries: {
    type: Array,
    default: () => [],
  },
  statsControls: {
    type: Object,
    required: true,
  },
  presetOptions: {
    type: Array,
    default: () => [],
  },
  statsRangeLabel: {
    type: String,
    required: true,
  },
  formatCurrency: {
    type: Function,
    required: true,
  },
  formatShortDate: {
    type: Function,
    required: true,
  },
  formatTime: {
    type: Function,
    required: true,
  },
})

const emit = defineEmits(['start-travel-entry', 'open-travel-search'])

const travelKeywords = [
  '여행',
  '항공',
  '비행',
  '공항',
  '숙소',
  '호텔',
  '게스트하우스',
  '리조트',
  '교통',
  '기차',
  '버스',
  '택시',
  '렌터카',
  '입장권',
  '관광',
  '투어',
  '기념품',
  '환전',
  '해외',
  '일본',
  '오사카',
  '도쿄',
  '후쿠오카',
  '대만',
  '태국',
  '베트남',
]

const travelEntries = computed(() =>
  props.entries
    .filter((entry) => entry.entryType === 'EXPENSE')
    .filter((entry) => {
      const searchableText = [
        entry.title,
        entry.memo,
        entry.categoryGroupName,
        entry.categoryDetailName,
        entry.paymentMethodName,
      ].join(' ').toLowerCase()
      return travelKeywords.some((keyword) => searchableText.includes(keyword.toLowerCase()))
    })
    .sort((left, right) => `${right.entryDate} ${right.entryTime || ''}`.localeCompare(`${left.entryDate} ${left.entryTime || ''}`)),
)

const totalExpense = computed(() =>
  travelEntries.value.reduce((sum, entry) => sum + Number(entry.amount || 0), 0),
)

const topCategoryRows = computed(() => {
  const buckets = new Map()
  travelEntries.value.forEach((entry) => {
    const label = entry.categoryDetailName
      ? `${entry.categoryGroupName} / ${entry.categoryDetailName}`
      : entry.categoryGroupName || '미분류'
    const current = buckets.get(label) || { label, amount: 0, count: 0 }
    current.amount += Number(entry.amount || 0)
    current.count += 1
    buckets.set(label, current)
  })
  return [...buckets.values()].sort((left, right) => right.amount - left.amount).slice(0, 5)
})

const topPaymentRows = computed(() => {
  const buckets = new Map()
  travelEntries.value.forEach((entry) => {
    const label = entry.paymentMethodName || '결제수단 없음'
    const current = buckets.get(label) || { label, amount: 0, count: 0 }
    current.amount += Number(entry.amount || 0)
    current.count += 1
    buckets.set(label, current)
  })
  return [...buckets.values()].sort((left, right) => right.amount - left.amount).slice(0, 5)
})

const recentEntries = computed(() => travelEntries.value.slice(0, 12))
</script>

<template>
  <section class="panel household-travel-ledger">
    <div class="panel__header household-travel-ledger__header">
      <div>
        <span class="panel__eyebrow">TRAVEL LEDGER</span>
        <h2>여행 가계부</h2>
        <p>여행 관련 지출을 일반 가계부 데이터 안에서 따로 모아 보고, 새 여행 지출도 같은 거래 입력 흐름으로 기록합니다.</p>
      </div>
      <span class="panel__badge">{{ statsRangeLabel }}</span>
    </div>

    <div class="household-travel-ledger__toolbar">
      <div class="scope-toggle scope-toggle--wrap">
        <button
          v-for="option in presetOptions"
          :key="option.value"
          class="button"
          :class="{ 'button--primary': statsControls.preset === option.value }"
          type="button"
          @click="statsControls.preset = option.value"
        >
          {{ option.label }}
        </button>
      </div>
      <label class="field household-travel-ledger__date">
        <span class="field__label">조회 기준</span>
        <input v-model="statsControls.anchorDate" type="date" />
      </label>
    </div>

    <div class="household-travel-ledger__summary">
      <article class="household-travel-ledger__summary-card">
        <span>여행 지출</span>
        <strong>{{ formatCurrency(totalExpense) }}</strong>
        <small>{{ travelEntries.length }}건</small>
      </article>
      <article class="household-travel-ledger__summary-card">
        <span>주요 분류</span>
        <strong>{{ topCategoryRows[0]?.label || '데이터 없음' }}</strong>
        <small>{{ topCategoryRows[0] ? formatCurrency(topCategoryRows[0].amount) : '여행 키워드가 포함된 거래가 없습니다.' }}</small>
      </article>
      <article class="household-travel-ledger__summary-card">
        <span>주요 결제수단</span>
        <strong>{{ topPaymentRows[0]?.label || '데이터 없음' }}</strong>
        <small>{{ topPaymentRows[0] ? formatCurrency(topPaymentRows[0].amount) : '여행 지출을 입력해 보세요.' }}</small>
      </article>
    </div>

    <div class="entry-editor__actions household-travel-ledger__actions">
      <button class="button button--primary" type="button" @click="emit('start-travel-entry')">여행 지출 입력</button>
      <button class="button button--ghost" type="button" @click="emit('open-travel-search')">검색에서 자세히 보기</button>
    </div>

    <div class="household-travel-ledger__content">
      <section class="panel panel--compact household-travel-ledger__panel">
        <div class="panel__header">
          <div>
            <h3>분류별 여행 지출</h3>
          </div>
        </div>
        <div v-if="topCategoryRows.length" class="household-travel-ledger__rows">
          <div v-for="row in topCategoryRows" :key="row.label" class="household-travel-ledger__row">
            <div>
              <strong>{{ row.label }}</strong>
              <small>{{ row.count }}건</small>
            </div>
            <span>{{ formatCurrency(row.amount) }}</span>
          </div>
        </div>
        <p v-else class="panel__empty">여행 관련 지출이 아직 없습니다.</p>
      </section>

      <section class="panel panel--compact household-travel-ledger__panel">
        <div class="panel__header">
          <div>
            <h3>최근 여행 지출</h3>
          </div>
        </div>
        <div v-if="recentEntries.length" class="sheet-table-wrap">
          <table class="sheet-table">
            <thead>
              <tr>
                <th>날짜</th>
                <th>제목</th>
                <th>분류</th>
                <th>금액</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="entry in recentEntries" :key="entry.id">
                <td>{{ formatShortDate(entry.entryDate) }} {{ formatTime(entry.entryTime) }}</td>
                <td>{{ entry.title }}</td>
                <td>{{ entry.categoryDetailName ? `${entry.categoryGroupName} / ${entry.categoryDetailName}` : entry.categoryGroupName }}</td>
                <td class="is-expense">{{ formatCurrency(entry.amount) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-else class="panel__empty">여행 지출을 입력하면 여기에 모입니다.</p>
      </section>
    </div>
  </section>
</template>
