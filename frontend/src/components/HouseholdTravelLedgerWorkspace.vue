<script setup>
import { computed, ref } from 'vue'

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

const emit = defineEmits(['start-travel-entry', 'open-travel-search', 'view-travel-entry-date', 'edit-travel-entry'])
const travelKeywordFilter = ref('')
const activeTravelType = ref('all')

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

const travelTypeOptions = [
  { key: 'all', label: '전체', keywords: [] },
  { key: 'transport', label: '이동', keywords: ['항공', '비행', '공항', '교통', '기차', '버스', '택시', '렌터카', '주유', '톨게이트'] },
  { key: 'lodging', label: '숙소', keywords: ['숙소', '호텔', '게스트하우스', '리조트', '민박', '에어비앤비'] },
  { key: 'food', label: '식비', keywords: ['식비', '식사', '맛집', '카페', '음식', '밥', '편의점'] },
  { key: 'activity', label: '관광', keywords: ['입장권', '관광', '투어', '체험', '박물관', '전시', '공연'] },
  { key: 'shopping', label: '쇼핑', keywords: ['쇼핑', '기념품', '선물', '면세', '마트'] },
  { key: 'cash', label: '환전', keywords: ['환전', '현금', '외화', 'atm'] },
  { key: 'other', label: '기타', keywords: [] },
]

function normalizeSearchText(value) {
  return String(value || '').toLowerCase()
}

function getEntrySearchText(entry) {
  return normalizeSearchText([
    entry.title,
    entry.memo,
    entry.categoryGroupName,
    entry.categoryDetailName,
    entry.paymentMethodName,
  ].join(' '))
}

function matchesKeywordSet(searchableText, keywords) {
  return keywords.some((keyword) => searchableText.includes(normalizeSearchText(keyword)))
}

function getEntryCategoryLabel(entry) {
  return entry.categoryDetailName
    ? `${entry.categoryGroupName} / ${entry.categoryDetailName}`
    : entry.categoryGroupName || '미분류'
}

function getTravelTypeForEntry(entry) {
  const searchableText = getEntrySearchText(entry)
  const matchedType = travelTypeOptions.find((option) =>
    option.key !== 'all'
    && option.key !== 'other'
    && matchesKeywordSet(searchableText, option.keywords),
  )
  return matchedType?.key || 'other'
}

function formatMonthLabel(monthKey) {
  const [year, month] = String(monthKey || '').split('-')
  if (!year || !month) {
    return '날짜 없음'
  }
  return `${Number(year)}년 ${Number(month)}월`
}

const baseTravelEntries = computed(() =>
  props.entries
    .filter((entry) => entry.entryType === 'EXPENSE')
    .filter((entry) => {
      const searchableText = getEntrySearchText(entry)
      const matchesTravelKeyword = matchesKeywordSet(searchableText, travelKeywords)
      const filterText = normalizeSearchText(travelKeywordFilter.value)
      return matchesTravelKeyword && (!filterText || searchableText.includes(filterText))
    })
    .sort((left, right) => `${right.entryDate} ${right.entryTime || ''}`.localeCompare(`${left.entryDate} ${left.entryTime || ''}`)),
)

const travelEntries = computed(() => {
  if (activeTravelType.value === 'all') {
    return baseTravelEntries.value
  }
  if (activeTravelType.value === 'other') {
    return baseTravelEntries.value.filter((entry) => getTravelTypeForEntry(entry) === 'other')
  }
  return baseTravelEntries.value.filter((entry) => getTravelTypeForEntry(entry) === activeTravelType.value)
})

const travelTypeStats = computed(() => {
  const stats = new Map(travelTypeOptions.map((option) => [option.key, { ...option, count: 0, amount: 0 }]))
  baseTravelEntries.value.forEach((entry) => {
    const amount = Number(entry.amount || 0)
    const typeKey = getTravelTypeForEntry(entry)
    const typeStat = stats.get(typeKey) || stats.get('other')
    typeStat.count += 1
    typeStat.amount += amount
    const allStat = stats.get('all')
    allStat.count += 1
    allStat.amount += amount
  })
  return travelTypeOptions.map((option) => stats.get(option.key))
})

const activeTravelTypeOption = computed(() =>
  travelTypeOptions.find((option) => option.key === activeTravelType.value) || travelTypeOptions[0],
)

const travelSearchKeyword = computed(() =>
  String(travelKeywordFilter.value || activeTravelTypeOption.value.keywords?.[0] || '여행').trim(),
)

const totalExpense = computed(() =>
  travelEntries.value.reduce((sum, entry) => sum + Number(entry.amount || 0), 0),
)

const averageExpense = computed(() =>
  travelEntries.value.length ? Math.round(totalExpense.value / travelEntries.value.length) : 0,
)

const topCategoryRows = computed(() => {
  const buckets = new Map()
  travelEntries.value.forEach((entry) => {
    const label = getEntryCategoryLabel(entry)
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

const monthFlowRows = computed(() => {
  const buckets = new Map()
  travelEntries.value.forEach((entry) => {
    const monthKey = String(entry.entryDate || '').slice(0, 7) || 'unknown'
    const current = buckets.get(monthKey) || { monthKey, label: formatMonthLabel(monthKey), amount: 0, count: 0 }
    current.amount += Number(entry.amount || 0)
    current.count += 1
    buckets.set(monthKey, current)
  })
  const rows = [...buckets.values()].sort((left, right) => right.monthKey.localeCompare(left.monthKey)).slice(0, 6)
  const maxAmount = Math.max(...rows.map((row) => row.amount), 1)
  return rows.map((row) => ({
    ...row,
    percent: Math.max(6, Math.round((row.amount / maxAmount) * 100)),
  }))
})
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
      <label class="field household-travel-ledger__date">
        <span class="field__label">여행 검색어</span>
        <input v-model="travelKeywordFilter" type="search" placeholder="여행지, 항공, 숙소" />
      </label>
    </div>

    <div class="household-travel-ledger__types" aria-label="여행 지출 유형 필터">
      <button
        v-for="type in travelTypeStats"
        :key="type.key"
        class="household-travel-ledger__type"
        :class="{ 'household-travel-ledger__type--active': activeTravelType === type.key }"
        type="button"
        @click="activeTravelType = type.key"
      >
        <span>{{ type.label }}</span>
        <strong>{{ type.count }}건</strong>
        <small>{{ formatCurrency(type.amount) }}</small>
      </button>
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
      <article class="household-travel-ledger__summary-card">
        <span>평균 지출</span>
        <strong>{{ formatCurrency(averageExpense) }}</strong>
        <small>{{ activeTravelType === 'all' ? '전체 여행 지출 기준' : '선택한 유형 기준' }}</small>
      </article>
    </div>

    <div class="entry-editor__actions household-travel-ledger__actions">
      <button class="button button--primary" type="button" @click="emit('start-travel-entry')">여행 지출 입력</button>
      <button class="button button--ghost" type="button" @click="emit('open-travel-search', travelSearchKeyword)">검색에서 자세히 보기</button>
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
            <h3>월별 여행 지출 흐름</h3>
          </div>
        </div>
        <div v-if="monthFlowRows.length" class="household-travel-ledger__rows">
          <div v-for="row in monthFlowRows" :key="row.monthKey" class="household-travel-ledger__flow-row">
            <div>
              <strong>{{ row.label }}</strong>
              <small>{{ row.count }}건 · {{ formatCurrency(row.amount) }}</small>
            </div>
            <span class="household-travel-ledger__flow-track">
              <i :style="{ width: `${row.percent}%` }"></i>
            </span>
          </div>
        </div>
        <p v-else class="panel__empty">선택한 조건에 해당하는 월별 지출이 없습니다.</p>
      </section>

      <section class="panel panel--compact household-travel-ledger__panel household-travel-ledger__panel--wide">
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
                <th>작업</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="entry in recentEntries" :key="entry.id">
                <td>{{ formatShortDate(entry.entryDate) }} {{ formatTime(entry.entryTime) }}</td>
                <td>{{ entry.title }}</td>
                <td>{{ getEntryCategoryLabel(entry) }}</td>
                <td class="is-expense">{{ formatCurrency(entry.amount) }}</td>
                <td>
                  <div class="household-travel-ledger__entry-actions">
                    <button class="button button--ghost" type="button" @click="emit('view-travel-entry-date', entry)">
                      이동
                    </button>
                    <button class="button button--secondary" type="button" @click="emit('edit-travel-entry', entry)">
                      수정
                    </button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-else class="panel__empty">여행 지출을 입력하면 여기에 모입니다.</p>
      </section>
    </div>
  </section>
</template>
