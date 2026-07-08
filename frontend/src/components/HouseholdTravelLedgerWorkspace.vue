<script setup>
import { computed, nextTick, ref } from 'vue'

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
  travelPlans: {
    type: Array,
    default: () => [],
  },
  selectedTravelPlanId: {
    type: String,
    default: '',
  },
  travelPlanForm: {
    type: Object,
    default: () => ({}),
  },
  travelPlanLoading: {
    type: Boolean,
    default: false,
  },
  travelPlanSubmitting: {
    type: Boolean,
    default: false,
  },
  travelPlanError: {
    type: String,
    default: '',
  },
  linkingTravelEntryId: {
    type: String,
    default: '',
  },
})

const emit = defineEmits([
  'select-travel-plan',
  'create-travel-plan',
  'reset-travel-plan-form',
  'start-travel-entry',
  'open-travel-search',
  'view-travel-entry-date',
  'edit-travel-entry',
  'link-travel-entry',
  'open-travel-record-location',
])
const travelKeywordFilter = ref('')
const activeTravelType = ref('all')
const selectedTravelDate = ref('')
const selectedTravelSheetRef = ref(null)

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

const travelCalendarWeekdays = ['일', '월', '화', '수', '목', '금', '토']

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

function isTravelLinkedEntry(entry) {
  return Boolean(entry?.travelPlanId || entry?.travelRecordId)
}

function canLinkTravelRecord(entry) {
  return Boolean(selectedTravelPlan.value)
    && entry?.entryType === 'EXPENSE'
    && !entry?.travelRecordId
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

function parseLocalIsoDate(value) {
  const [year, month, day] = String(value || '').split('-').map((item) => Number(item))
  if (!year || !month || !day) {
    return null
  }
  return new Date(year, month - 1, day)
}

function toLocalIsoDate(value) {
  const year = value.getFullYear()
  const month = String(value.getMonth() + 1).padStart(2, '0')
  const day = String(value.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function formatTravelPlanDayLabel(date) {
  const parsed = parseLocalIsoDate(date)
  if (!parsed) {
    return date
  }
  return `${parsed.getMonth() + 1}/${parsed.getDate()}`
}

function buildTravelPlanDays(startDate, endDate, countsByDate, expenseByDate) {
  const start = parseLocalIsoDate(startDate)
  const end = parseLocalIsoDate(endDate)
  if (!start || !end || start > end) {
    return []
  }

  const days = []
  const cursor = new Date(start)
  while (cursor <= end && days.length < 400) {
    const date = toLocalIsoDate(cursor)
    days.push({
      date,
      dayLabel: `${days.length + 1}일차`,
      dateLabel: formatTravelPlanDayLabel(date),
      count: countsByDate.get(date) || 0,
      expense: expenseByDate.get(date) || 0,
    })
    cursor.setDate(cursor.getDate() + 1)
  }
  return days
}

function buildTravelPlanCalendarWeeks(startDate, endDate, countsByDate, expenseByDate) {
  const start = parseLocalIsoDate(startDate)
  const end = parseLocalIsoDate(endDate)
  if (!start || !end || start > end) {
    return []
  }

  const calendarStart = new Date(start)
  calendarStart.setDate(calendarStart.getDate() - calendarStart.getDay())
  const calendarEnd = new Date(end)
  calendarEnd.setDate(calendarEnd.getDate() + (6 - calendarEnd.getDay()))

  const weeks = []
  let currentWeek = []
  let travelDayIndex = 0
  const cursor = new Date(calendarStart)

  while (cursor <= calendarEnd && weeks.length < 60) {
    const date = toLocalIsoDate(cursor)
    const inTravelRange = cursor >= start && cursor <= end
    if (inTravelRange) {
      travelDayIndex += 1
    }
    currentWeek.push({
      date,
      dayNumber: cursor.getDate(),
      monthNumber: cursor.getMonth() + 1,
      inTravelRange,
      dayLabel: inTravelRange ? `${travelDayIndex}일차` : '',
      dateLabel: formatTravelPlanDayLabel(date),
      count: countsByDate.get(date) || 0,
      expense: expenseByDate.get(date) || 0,
    })

    if (currentWeek.length === 7) {
      weeks.push(currentWeek)
      currentWeek = []
    }

    cursor.setDate(cursor.getDate() + 1)
  }

  return weeks
}

const baseTravelEntries = computed(() =>
  props.entries
    .filter((entry) => {
      if (entry.entryType === 'INCOME') {
        return false
      }
      const searchableText = getEntrySearchText(entry)
      const matchesTravelKeyword = matchesKeywordSet(searchableText, travelKeywords)
      const filterText = normalizeSearchText(travelKeywordFilter.value)
      return (matchesTravelKeyword || isTravelLinkedEntry(entry))
        && (!filterText || searchableText.includes(filterText))
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

const travelEntryCountByDate = computed(() => {
  const counts = new Map()
  travelEntries.value.forEach((entry) => {
    const date = String(entry.entryDate || '').slice(0, 10)
    if (!date) {
      return
    }
    counts.set(date, (counts.get(date) || 0) + 1)
  })
  return counts
})

const travelExpenseByDate = computed(() => {
  const totals = new Map()
  travelEntries.value.forEach((entry) => {
    const date = String(entry.entryDate || '').slice(0, 10)
    if (!date) {
      return
    }
    totals.set(date, (totals.get(date) || 0) + Number(entry.amount || 0))
  })
  return totals
})

const travelTypeStats = computed(() => {
  const stats = new Map(travelTypeOptions.map((option) => [option.key, { ...option, count: 0, expense: 0 }]))
  const source = baseTravelEntries.value

  source.forEach((entry) => {
    const amount = Number(entry.amount || 0)
    const typeKey = getTravelTypeForEntry(entry)
    const typeStat = stats.get(typeKey) || stats.get('other')
    typeStat.count += 1
    typeStat.expense += amount

    const allStat = stats.get('all')
    allStat.count += 1
    allStat.expense += amount
  })
  return travelTypeOptions.map((option) => stats.get(option.key))
})

const activeTravelTypeOption = computed(() =>
  travelTypeOptions.find((option) => option.key === activeTravelType.value) || travelTypeOptions[0],
)
const selectedTravelPlan = computed(() =>
  props.travelPlans.find((plan) => String(plan.id) === String(props.selectedTravelPlanId)) || null,
)
const selectedTravelPlanRangeLabel = computed(() => {
  const plan = selectedTravelPlan.value
  if (!plan?.startDate || !plan?.endDate) {
    return '여행 기간 미설정'
  }
  return `${plan.startDate} - ${plan.endDate}`
})
const selectedTravelPlanDays = computed(() => {
  const plan = selectedTravelPlan.value
  if (!plan?.startDate || !plan?.endDate) {
    return []
  }
  return buildTravelPlanDays(plan.startDate, plan.endDate, travelEntryCountByDate.value, travelExpenseByDate.value)
})

const selectedTravelPlanCalendarWeeks = computed(() => {
  const plan = selectedTravelPlan.value
  if (!plan?.startDate || !plan?.endDate) {
    return []
  }
  return buildTravelPlanCalendarWeeks(plan.startDate, plan.endDate, travelEntryCountByDate.value, travelExpenseByDate.value)
})

const travelSearchKeyword = computed(() =>
  String(travelKeywordFilter.value || activeTravelTypeOption.value.keywords?.[0] || '여행').trim(),
)

const totalExpense = computed(() =>
  travelEntries.value
    .reduce((sum, entry) => sum + Number(entry.amount || 0), 0),
)

const expenseCount = computed(() => travelEntries.value.length)

const averageAmount = computed(() =>
  travelEntries.value.length ? Math.round(totalExpense.value / travelEntries.value.length) : 0,
)

const topCategoryRows = computed(() => {
  const buckets = new Map()
  travelEntries.value.forEach((entry) => {
    const label = getEntryCategoryLabel(entry)
    const current = buckets.get(label) || { label, expense: 0, count: 0 }
    const amount = Number(entry.amount || 0)
    current.expense += amount
    current.count += 1
    buckets.set(label, current)
  })
  return [...buckets.values()]
    .sort((left, right) => right.expense - left.expense)
    .slice(0, 5)
})

const recentEntries = computed(() => travelEntries.value.slice(0, 12))

const selectedTravelDateEntries = computed(() => {
  if (!selectedTravelDate.value) {
    return []
  }
  return travelEntries.value
    .filter((entry) => String(entry.entryDate || '').slice(0, 10) === selectedTravelDate.value)
    .sort((left, right) => `${left.entryDate} ${left.entryTime || ''}`.localeCompare(`${right.entryDate} ${right.entryTime || ''}`))
})

const selectedTravelDateTotal = computed(() =>
  selectedTravelDateEntries.value.reduce((sum, entry) => sum + Number(entry.amount || 0), 0),
)

const selectedTravelDateTitle = computed(() => {
  if (!selectedTravelDate.value) {
    return '선택 날짜 여행 거래'
  }
  const parsed = parseLocalIsoDate(selectedTravelDate.value)
  if (!parsed) {
    return `${selectedTravelDate.value} 여행 거래`
  }
  return `${parsed.getMonth() + 1}월 ${parsed.getDate()}일 여행 거래`
})

const monthFlowRows = computed(() => {
  const buckets = new Map()
  travelEntries.value.forEach((entry) => {
    const monthKey = String(entry.entryDate || '').slice(0, 7) || 'unknown'
    const current = buckets.get(monthKey) || { monthKey, label: formatMonthLabel(monthKey), expense: 0, count: 0 }
    const amount = Number(entry.amount || 0)
    current.expense += amount
    current.count += 1
    buckets.set(monthKey, current)
  })
  const rows = [...buckets.values()].sort((left, right) => right.monthKey.localeCompare(left.monthKey)).slice(0, 6)
  const maxAmount = Math.max(...rows.map((row) => row.expense), 1)
  return rows.map((row) => ({
    ...row,
    amount: row.expense,
    percent: Math.max(6, Math.round((row.expense / maxAmount) * 100)),
  }))
})

function openTravelSearch() {
  emit('open-travel-search', {
    keyword: travelSearchKeyword.value,
    entryType: 'EXPENSE',
  })
}

async function handleTravelCalendarDayClick(day) {
  if (!day?.inTravelRange) {
    return
  }
  selectedTravelDate.value = day.date
  await nextTick()
  selectedTravelSheetRef.value?.scrollIntoView?.({ behavior: 'smooth', block: 'start' })
}

function startTravelEntryFromSelectedDate() {
  emit('start-travel-entry', { entryType: 'EXPENSE', entryDate: selectedTravelDate.value || '' })
}
</script>

<template>
  <section class="panel household-travel-ledger">
    <div class="panel__header household-travel-ledger__header">
      <div>
        <span class="panel__eyebrow">TRAVEL LEDGER</span>
        <h2>여행 가계부</h2>
        <p>여행 기간의 지출을 일반 가계부 데이터 안에서 따로 모아 보고, 새 여행 지출도 같은 입력 흐름으로 기록합니다.</p>
      </div>
      <span class="panel__badge">{{ statsRangeLabel }}</span>
    </div>

    <section class="household-travel-ledger__connection">
      <div class="household-travel-ledger__connection-main">
        <label class="field">
          <span class="field__label">여행 연결</span>
          <select
            :value="selectedTravelPlanId"
            :disabled="travelPlanLoading"
            @change="emit('select-travel-plan', $event.target.value)"
          >
            <option value="">연결하지 않고 보기</option>
            <option v-for="plan in travelPlans" :key="plan.id" :value="String(plan.id)">
              {{ plan.name }} / {{ plan.destination || '목적지 미정' }} / {{ plan.startDate }} - {{ plan.endDate }}
            </option>
          </select>
        </label>
        <div class="household-travel-ledger__connection-copy">
          <strong>{{ selectedTravelPlan ? selectedTravelPlan.name : '여행 미연결' }}</strong>
          <span>
            {{
              selectedTravelPlan
                ? `${selectedTravelPlanRangeLabel} 기간으로 조회하고 새 거래에는 여행 연결값을 함께 저장합니다.`
                : '기존 가계부 데이터를 유지하면서 여행 키워드와 연결 거래를 모아 봅니다.'
            }}
          </span>
        </div>
      </div>

      <div class="household-travel-ledger__plan-form">
        <label class="field">
          <span class="field__label">새 여행 이름</span>
          <input v-model="travelPlanForm.name" type="text" placeholder="가을 일본 여행" />
        </label>
        <label class="field">
          <span class="field__label">목적지</span>
          <input v-model="travelPlanForm.destination" type="text" placeholder="오사카, 교토" />
        </label>
        <label class="field">
          <span class="field__label">시작일</span>
          <input v-model="travelPlanForm.startDate" type="date" />
        </label>
        <label class="field">
          <span class="field__label">종료일</span>
          <input v-model="travelPlanForm.endDate" type="date" />
        </label>
        <label class="field">
          <span class="field__label">통화</span>
          <input v-model="travelPlanForm.homeCurrency" type="text" maxlength="3" />
        </label>
        <label class="field">
          <span class="field__label">인원</span>
          <input v-model="travelPlanForm.headCount" type="number" min="1" step="1" />
        </label>
        <div class="entry-editor__actions household-travel-ledger__plan-actions">
          <button class="button button--ghost" type="button" @click="emit('reset-travel-plan-form')">초기화</button>
          <button class="button button--secondary" type="button" :disabled="travelPlanSubmitting" @click="emit('create-travel-plan')">
            {{ travelPlanSubmitting ? '여행 생성 중...' : '가계부에서 여행 생성' }}
          </button>
        </div>
      </div>
      <p v-if="travelPlanError" class="feedback feedback--error household-travel-ledger__connection-error">{{ travelPlanError }}</p>
    </section>

    <section v-if="selectedTravelPlanCalendarWeeks.length" class="household-travel-ledger__date-board">
      <div class="household-travel-ledger__date-board-header">
        <div>
          <strong>여행 기간 달력</strong>
          <span>{{ selectedTravelPlanRangeLabel }} 기준으로 여행 날짜만 선택할 수 있습니다.</span>
        </div>
        <small>{{ selectedTravelPlanDays.length }}일</small>
      </div>
      <div class="household-travel-ledger__calendar">
        <div class="household-travel-ledger__weekdays" aria-hidden="true">
          <span v-for="weekday in travelCalendarWeekdays" :key="weekday">{{ weekday }}</span>
        </div>
        <div class="household-travel-ledger__weeks">
          <div v-for="(week, weekIndex) in selectedTravelPlanCalendarWeeks" :key="weekIndex" class="household-travel-ledger__week">
            <button
              v-for="day in week"
              :key="day.date"
              class="household-travel-ledger__date-card"
              :class="{
                'household-travel-ledger__date-card--muted': !day.inTravelRange,
                'household-travel-ledger__date-card--active': selectedTravelDate === day.date,
              }"
              type="button"
              :disabled="!day.inTravelRange"
              @click="handleTravelCalendarDayClick(day)"
            >
              <span class="household-travel-ledger__date-card-head">
                <span>{{ day.monthNumber }}월 {{ day.dayNumber }}</span>
                <small>{{ day.count }}건</small>
              </span>
              <strong>{{ day.inTravelRange ? day.dayLabel : '-' }}</strong>
              <span class="household-travel-ledger__date-card-amount">{{ formatCurrency(day.expense) }}</span>
            </button>
          </div>
        </div>
      </div>
    </section>

    <section
      v-if="selectedTravelDate"
      ref="selectedTravelSheetRef"
      class="panel panel--compact household-travel-ledger__panel household-travel-ledger__panel--wide household-travel-ledger__date-sheet"
    >
      <div class="panel__header">
        <div>
          <h3>{{ selectedTravelDateTitle }}</h3>
        </div>
        <div class="household-travel-ledger__date-sheet-summary">
          <span>{{ selectedTravelDateEntries.length }}건</span>
          <strong class="is-expense">{{ formatCurrency(selectedTravelDateTotal) }}</strong>
        </div>
      </div>
      <div v-if="selectedTravelDateEntries.length" class="sheet-table-wrap household-travel-ledger__date-sheet-table">
        <table class="sheet-table">
          <thead>
            <tr>
              <th>시간</th>
              <th>제목</th>
              <th>분류</th>
              <th>결제수단</th>
              <th>금액</th>
              <th>작업</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="entry in selectedTravelDateEntries" :key="entry.id">
              <td>{{ formatTime(entry.entryTime) }}</td>
              <td>{{ entry.title }}</td>
              <td>{{ getEntryCategoryLabel(entry) }}</td>
              <td>{{ entry.paymentMethodName || '-' }}</td>
              <td class="is-expense">{{ formatCurrency(entry.amount) }}</td>
              <td>
                <div class="household-travel-ledger__entry-actions">
                  <button
                    v-if="canLinkTravelRecord(entry)"
                    class="button button--primary"
                    type="button"
                    :disabled="String(linkingTravelEntryId) === String(entry.id)"
                    @click="emit('link-travel-entry', entry)"
                  >
                    {{ String(linkingTravelEntryId) === String(entry.id) ? '연결 중' : '여행 기록 연결' }}
                  </button>
                  <button
                    v-if="entry.travelRecordId"
                    class="button button--secondary"
                    type="button"
                    @click="emit('open-travel-record-location', entry)"
                  >
                    위치 설정
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
      <p v-else class="panel__empty">선택한 날짜에 표시할 여행 지출 내역이 없습니다.</p>
    </section>

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

    <div class="household-travel-ledger__types" aria-label="여행 거래 유형 필터">
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
        <small>{{ formatCurrency(type.expense) }}</small>
      </button>
    </div>

    <div class="household-travel-ledger__summary">
      <article class="household-travel-ledger__summary-card">
        <span>여행 지출 합계</span>
        <strong class="is-expense">{{ formatCurrency(totalExpense) }}</strong>
        <small>{{ travelEntries.length }}건</small>
      </article>
      <article class="household-travel-ledger__summary-card">
        <span>지출 건수</span>
        <strong>{{ expenseCount }}건</strong>
        <small>여행 지출 기준</small>
      </article>
      <article class="household-travel-ledger__summary-card">
        <span>여행 일수</span>
        <strong>{{ selectedTravelPlanDays.length }}일</strong>
        <small>{{ expenseCount }}건</small>
      </article>
      <article class="household-travel-ledger__summary-card">
        <span>평균 지출</span>
        <strong>{{ formatCurrency(averageAmount) }}</strong>
        <small>{{ activeTravelType === 'all' ? '전체 여행 거래 기준' : '선택한 유형 기준' }}</small>
      </article>
    </div>

    <div class="entry-editor__actions household-travel-ledger__actions">
      <button class="button button--primary" type="button" @click="startTravelEntryFromSelectedDate">여행 지출 입력</button>
      <button class="button button--ghost" type="button" @click="openTravelSearch">검색에서 자세히 보기</button>
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
              <small>{{ row.count }}건 · 지출 {{ formatCurrency(row.expense) }}</small>
            </div>
            <span class="is-expense">{{ formatCurrency(row.expense) }}</span>
          </div>
        </div>
        <p v-else class="panel__empty">여행 관련 거래가 아직 없습니다.</p>
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
              <small>{{ row.count }}건 · 지출 {{ formatCurrency(row.expense) }}</small>
            </div>
            <span class="household-travel-ledger__flow-track">
              <i :style="{ width: `${row.percent}%` }"></i>
            </span>
          </div>
        </div>
        <p v-else class="panel__empty">선택한 조건에 해당하는 월별 거래가 없습니다.</p>
      </section>

      <section class="panel panel--compact household-travel-ledger__panel household-travel-ledger__panel--wide">
        <div class="panel__header">
          <div>
            <h3>최근 여행 거래</h3>
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
                    <button
                      v-if="canLinkTravelRecord(entry)"
                      class="button button--primary"
                      type="button"
                      :disabled="String(linkingTravelEntryId) === String(entry.id)"
                      @click="emit('link-travel-entry', entry)"
                    >
                      {{ String(linkingTravelEntryId) === String(entry.id) ? '연결 중' : '여행 기록 연결' }}
                    </button>
                    <span v-else-if="entry.travelRecordId" class="household-travel-ledger__link-badge">
                      여행 기록 연결됨
                    </span>
                    <button
                      v-if="entry.travelRecordId"
                      class="button button--secondary"
                      type="button"
                      @click="emit('open-travel-record-location', entry)"
                    >
                      위치 설정
                    </button>
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
        <p v-else class="panel__empty">여행 거래를 입력하면 여기에 모입니다.</p>
      </section>
    </div>
  </section>
</template>
