<script setup>
import { computed, reactive, ref, watch } from 'vue'
import BarChartCard from './BarChartCard.vue'
import BreakdownList from './BreakdownList.vue'
import ComparisonTable from './ComparisonTable.vue'
import DonutChartCard from './DonutChartCard.vue'
import SummaryCard from './SummaryCard.vue'
import { useTableSelection } from '../lib/tableSelection'

const chartPalette = ['#3182f6', '#12b886', '#f59f00', '#ff6b6b', '#7c5cff', '#00b8d9', '#fd7e14', '#5c7cfa']
const SEARCH_OTHER_FILTER_VALUE = '__OTHER__'
const emit = defineEmits([
  'change-search-page',
  'change-trash-page',
  'move-search-entry',
  'save-search-entry',
  'bulk-update-search-entries',
  'delete-search-entry',
  'restore-trash-entry',
  'empty-trash',
  'update-search-keyword-draft',
  'submit-search',
  'request-ai-analysis',
  'load-latest-ai-analysis',
  'load-ai-analysis-history',
  'open-ai-analysis-history',
  'rerun-ai-analysis',
])

const props = defineProps({
  route: {
    type: String,
    required: true,
  },
  statsControls: {
    type: Object,
    required: true,
  },
  searchForm: {
    type: Object,
    required: true,
  },
  searchKeywordDraft: {
    type: String,
    default: '',
  },
  presetOptions: {
    type: Array,
    default: () => [],
  },
  statsCards: {
    type: Array,
    default: () => [],
  },
  statsRangeLabel: {
    type: String,
    required: true,
  },
  comparisonRows: {
    type: Array,
    default: () => [],
  },
  comparisonBadge: {
    type: String,
    required: true,
  },
  searchResults: {
    type: Array,
    default: () => [],
  },
  searchPageInfo: {
    type: Object,
    required: true,
  },
  searchSummary: {
    type: Object,
    required: true,
  },
  trashResults: {
    type: Array,
    default: () => [],
  },
  trashPageInfo: {
    type: Object,
    required: true,
  },
  insights: {
    type: Object,
    required: true,
  },
  pastComparisons: {
    type: Array,
    default: () => [],
  },
  expenseBreakdown: {
    type: Array,
    default: () => [],
  },
  paymentBreakdown: {
    type: Array,
    default: () => [],
  },
  paymentMethods: {
    type: Array,
    default: () => [],
  },
  categories: {
    type: Array,
    default: () => [],
  },
  formatCurrency: {
    type: Function,
    required: true,
  },
  formatShortDate: {
    type: Function,
    required: true,
  },
  formatFullDate: {
    type: Function,
    required: true,
  },
  formatDateRange: {
    type: Function,
    required: true,
  },
  formatTime: {
    type: Function,
    required: true,
  },
  aiAnalysisControls: {
    type: Object,
    default: () => ({}),
  },
  aiAnalysisHistoryFilters: {
    type: Object,
    default: () => ({}),
  },
  aiAnalysisStatus: {
    type: Object,
    default: null,
  },
  aiAnalysis: {
    type: Object,
    default: null,
  },
  aiAnalysisLoading: {
    type: Boolean,
    default: false,
  },
  aiAnalysisError: {
    type: String,
    default: '',
  },
  aiAnalysisHistoryPage: {
    type: Object,
    default: () => ({ content: [], page: 0, size: 8, totalElements: 0, totalPages: 0 }),
  },
  aiAnalysisHistoryLoading: {
    type: Boolean,
    default: false,
  },
  aiAnalysisHistoryError: {
    type: String,
    default: '',
  },
})

const comparisonChartItems = computed(() =>
  props.comparisonRows.slice(-8).map((row, index) => ({
    label: row.label,
    value: Number(row.expense ?? 0),
    caption: `수입 ${props.formatCurrency(row.income)}`,
    color: chartPalette[index % chartPalette.length],
  })),
)
const searchResultSelection = useTableSelection(computed(() => props.searchResults))
const trashResultSelection = useTableSelection(computed(() => props.trashResults))
const editingSearchEntryId = ref(null)
const isSearchBulkToolbarVisible = ref(false)
const searchEditDraft = reactive({
  entryDate: '',
  entryTime: '00:00',
  title: '',
  memo: '',
  amount: '',
  foreignCurrencyCode: null,
  foreignAmount: null,
  exchangeRateToKrw: null,
  entryType: 'EXPENSE',
  categoryGroupId: '',
  categoryDetailId: '',
  paymentMethodId: '',
})
const searchBulkDraft = reactive({
  categoryGroupId: '',
  categoryDetailId: '',
  paymentMethodId: '',
})

const expenseDonutItems = computed(() =>
  props.expenseBreakdown.slice(0, 6).map((item, index) => ({
    label: item.detailName ? `${item.groupName} / ${item.detailName}` : item.groupName,
    value: Number(item.totalAmount ?? 0),
    caption: `${item.entryCount}건`,
    color: chartPalette[index % chartPalette.length],
  })),
)

const paymentDonutItems = computed(() =>
  props.paymentBreakdown.slice(0, 6).map((item, index) => ({
    label: `${item.paymentMethodName} (${item.kind})`,
    value: Number(item.totalAmount ?? 0),
    caption: `${item.entryCount}건`,
    color: chartPalette[index % chartPalette.length],
  })),
)

const hourlyChartItems = computed(() =>
  (props.insights.hourlySeries ?? []).map((item, index) => ({
    label: item.label,
    value: Number(item.value ?? 0),
    color: chartPalette[index % chartPalette.length],
  })),
)

const weekdayChartItems = computed(() =>
  (props.insights.weekdaySeries ?? []).map((item, index) => ({
    label: item.label.replace('요일', ''),
    value: Number(item.value ?? 0),
    color: chartPalette[index % chartPalette.length],
  })),
)

const weekOfMonthChartItems = computed(() =>
  (props.insights.weekOfMonthSeries ?? []).map((item, index) => ({
    label: item.label,
    value: Number(item.value ?? 0),
    color: chartPalette[index % chartPalette.length],
  })),
)

const monthOfYearChartItems = computed(() =>
  (props.insights.monthOfYearSeries ?? []).map((item, index) => ({
    label: item.label,
    value: Number(item.value ?? 0),
    color: chartPalette[index % chartPalette.length],
  })),
)


const aiPeriodOptions = [
  { value: 'WEEK', label: '1주' },
  { value: 'MONTH', label: '1달' },
  { value: 'QUARTER', label: '1~3달' },
  { value: 'HALF_YEAR', label: '6개월' },
  { value: 'YEAR', label: '1년' },
  { value: 'CUSTOM', label: '사용자 지정' },
]
const aiComparisonPresetOptions = [
  { value: 'PREVIOUS_WEEK', label: '지난주 vs 지지난주' },
  { value: 'CURRENT_MONTH_VS_PREVIOUS_MONTH', label: '이번 달 vs 지난달' },
  { value: 'MONTH_VS_PREVIOUS_3_MONTHS', label: '선택 월 vs 이전 3개월' },
  { value: 'YEAR_VS_PREVIOUS_YEAR', label: '올해 vs 작년' },
  { value: 'CUSTOM', label: '직접 비교' },
]
const aiModeOptions = [
  { value: '', label: '전체' },
  { value: 'PERIOD', label: '기간 분석' },
  { value: 'COMPARISON', label: '비교 분석' },
]
const aiComparisonOnlyOptions = [
  { value: '', label: '전체' },
  { value: 'true', label: '비교만' },
  { value: 'false', label: '단일 기간' },
]
const aiAnalysisHistoryItems = computed(() => props.aiAnalysisHistoryPage?.content ?? [])
const aiHistoryTotalPages = computed(() => Math.max(props.aiAnalysisHistoryPage?.totalPages ?? 0, 1))
const isAiPeriodCustom = computed(() => props.aiAnalysisControls?.mode !== 'COMPARISON' && props.aiAnalysisControls?.periodType === 'CUSTOM')
const isAiComparisonCustom = computed(() => props.aiAnalysisControls?.mode === 'COMPARISON' && props.aiAnalysisControls?.comparisonPreset === 'CUSTOM')
const aiHasResult = computed(() => Boolean(props.aiAnalysis))
const aiCompareDelta = computed(() => Number(props.aiAnalysis?.totalExpense ?? 0) - Number(props.aiAnalysis?.compareTotalExpense ?? 0))
const aiResultCards = computed(() => {
  if (!props.aiAnalysis) {
    return []
  }
  return [
    { label: '총 지출', value: props.formatCurrency(props.aiAnalysis.totalExpense), meta: `${props.aiAnalysis.expenseEntryCount ?? 0}건 내역` },
    { label: '일 평균', value: props.formatCurrency(props.aiAnalysis.averageDailyExpense), meta: formatAiRange(props.aiAnalysis.from, props.aiAnalysis.to) },
    { label: '비교 지출', value: props.formatCurrency(props.aiAnalysis.compareTotalExpense), meta: props.aiAnalysis.compareFrom ? formatAiRange(props.aiAnalysis.compareFrom, props.aiAnalysis.compareTo) : '비교 없음' },
    { label: '증감', value: `${aiCompareDelta.value > 0 ? '+' : ''}${props.formatCurrency(aiCompareDelta.value)}`, meta: props.aiAnalysis.mode === 'COMPARISON' ? '비교 기간 대비' : '최근 흐름 기준' },
  ]
})
const aiReport = computed(() => props.aiAnalysis?.report ?? {})
const aiReportKeySummary = computed(() => aiReport.value.keySummary || props.aiAnalysis?.summary || '')
const aiReportFullReport = computed(() => aiReport.value.fullReport || '')
const aiIsComparisonResult = computed(() => props.aiAnalysis?.mode === 'COMPARISON')
const aiFixedReportItems = computed(() => [
  ...safeAiReportList('regularSpending', props.aiAnalysis?.fixedCostInsights),
  ...safeAiReportList('fixedExpenses'),
  ...safeAiReportList('subscriptions'),
])
const aiAbnormalReportItems = computed(() => safeAiReportList('abnormalSpending', [
  ...safeAiList(props.aiAnalysis?.warnings),
  ...safeAiList(props.aiAnalysis?.unusualSpendingInsights),
]))
const aiComparisonReportItems = computed(() => safeAiReportList('comparisonFocus', props.aiAnalysis?.trendInsights))
function formatAiMode(mode) {
  return mode === 'COMPARISON' ? '비교 분석' : '기간 분석'
}

function formatAiPeriod(periodType) {
  return aiPeriodOptions.find((option) => option.value === periodType)?.label ?? periodType ?? '-'
}

function formatAiStatus(status) {
  if (status === 'COMPLETED') {
    return '완료'
  }
  if (status === 'FAILED') {
    return '실패'
  }
  return status ?? '-'
}

function formatAiRange(from, to) {
  if (!from || !to) {
    return '-'
  }
  return `${props.formatShortDate(from)} ~ ${props.formatShortDate(to)}`
}

function formatAiCreatedAt(value) {
  if (!value) {
    return '-'
  }
  return String(value).replace('T', ' ').slice(0, 16)
}

function safeAiReportList(fieldName, fallbackItems = []) {
  const reportItems = safeAiList(aiReport.value?.[fieldName])
  return reportItems.length ? reportItems : safeAiList(fallbackItems)
}
function safeAiList(items) {
  return Array.isArray(items) ? items.filter(Boolean) : []
}

function loadAiHistoryPage(page) {
  const safePage = Math.max(0, Math.min(page, aiHistoryTotalPages.value - 1))
  emit('load-ai-analysis-history', safePage)
}
const searchPageLabel = computed(() => Math.max(props.searchPageInfo.totalPages ?? 0, 1))
const trashPageLabel = computed(() => Math.max(props.trashPageInfo.totalPages ?? 0, 1))
const searchCategoryGroupOptions = computed(() =>
  props.categories.filter((group) => !props.searchForm.entryType || group.entryType === props.searchForm.entryType),
)
const searchCategoryDetailOptions = computed(() => {
  if (props.searchForm.categoryGroupId && props.searchForm.categoryGroupId !== SEARCH_OTHER_FILTER_VALUE) {
    const selectedGroup = props.categories.find((group) => String(group.id) === String(props.searchForm.categoryGroupId))
    return selectedGroup?.details ?? []
  }

  return searchCategoryGroupOptions.value.flatMap((group) => group.details ?? [])
})
const searchEditGroupOptions = computed(() =>
  props.categories.filter((group) => group.entryType === searchEditDraft.entryType),
)
const searchEditDetailOptions = computed(() => {
  const group = searchEditGroupOptions.value.find((item) => String(item.id) === String(searchEditDraft.categoryGroupId))
  return group?.details ?? []
})
const searchEditPaymentDisabled = computed(() => searchEditDraft.entryType === 'INCOME')
const searchEditErrors = computed(() => validateSearchEditDraft())
const selectedSearchCount = computed(() => searchResultSelection.selectedIds.value.length)
const searchBulkGroupOptions = computed(() => props.categories)
const selectedSearchBulkGroup = computed(() =>
  searchBulkGroupOptions.value.find((group) => String(group.id) === String(searchBulkDraft.categoryGroupId)) ?? null,
)
const searchBulkDetailOptions = computed(() => selectedSearchBulkGroup.value?.details ?? [])
const searchBulkPaymentDisabled = computed(() => selectedSearchBulkGroup.value?.entryType === 'INCOME')
const canSubmitSearchBulkUpdate = computed(() =>
  selectedSearchCount.value > 0
  && Boolean(searchBulkDraft.categoryGroupId || searchBulkDraft.paymentMethodId),
)

function normalizeAmountInput(value) {
  const normalized = String(value ?? '').replace(/,/g, '').trim()
  if (!normalized) {
    return ''
  }
  const amount = Number(normalized)
  return Number.isFinite(amount) ? String(amount) : normalized
}

function toOptionalNumber(value) {
  const text = String(value ?? '').trim()
  return text ? Number(text) : null
}

function normalizeSearchEditTimePayload(value) {
  const text = String(value ?? '').trim()
  const match = text.match(/^(\d{1,2}):(\d{1,2})/)
  if (!match) {
    return '00:00'
  }
  const hour = Math.min(Math.max(Number(match[1]), 0), 23)
  const minute = Math.min(Math.max(Number(match[2]), 0), 59)
  return `${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`
}

function resolveSearchEditPaymentMethodPayload() {
  return searchEditDraft.entryType === 'INCOME'
    ? null
    : toOptionalNumber(searchEditDraft.paymentMethodId)
}

function selectFirstSearchEditPaymentMethod() {
  searchEditDraft.paymentMethodId = props.paymentMethods[0] ? String(props.paymentMethods[0].id) : ''
}

function resetSearchDetailFilterIfInvalid() {
  const detailId = props.searchForm.categoryDetailId
  if (!detailId || detailId === SEARCH_OTHER_FILTER_VALUE) {
    return
  }

  const isValid = searchCategoryDetailOptions.value.some((detail) => String(detail.id) === String(detailId))
  if (!isValid) {
    props.searchForm.categoryDetailId = ''
  }
}

function formatSearchDetailFilterLabel(detail) {
  const group = props.categories.find((item) => String(item.id) === String(detail.groupId))
  return group ? `${group.name} / ${detail.name}` : detail.name
}

function handleSearchEntryTypeFilterChange() {
  const groupId = props.searchForm.categoryGroupId
  if (
    groupId
    && groupId !== SEARCH_OTHER_FILTER_VALUE
    && !searchCategoryGroupOptions.value.some((group) => String(group.id) === String(groupId))
  ) {
    props.searchForm.categoryGroupId = ''
  }
  resetSearchDetailFilterIfInvalid()
}

function handleSearchCategoryGroupFilterChange() {
  resetSearchDetailFilterIfInvalid()
}

function handleSearchBulkGroupChange() {
  const detailId = searchBulkDraft.categoryDetailId
  if (detailId && !searchBulkDetailOptions.value.some((detail) => String(detail.id) === String(detailId))) {
    searchBulkDraft.categoryDetailId = ''
  }
  if (searchBulkPaymentDisabled.value) {
    searchBulkDraft.paymentMethodId = ''
  }
}

function submitSearchBulkUpdate() {
  if (!canSubmitSearchBulkUpdate.value) {
    return
  }

  emit('bulk-update-search-entries', {
    entryIds: searchResultSelection.selectedIds.value.map((id) => Number(id)),
    categoryGroupId: searchBulkDraft.categoryGroupId ? Number(searchBulkDraft.categoryGroupId) : null,
    categoryDetailId: searchBulkDraft.categoryGroupId && searchBulkDraft.categoryDetailId
      ? Number(searchBulkDraft.categoryDetailId)
      : null,
    paymentMethodId: searchBulkDraft.paymentMethodId ? Number(searchBulkDraft.paymentMethodId) : null,
  })
  searchResultSelection.clearSelection()
  isSearchBulkToolbarVisible.value = false
}

function toggleSearchBulkToolbar() {
  isSearchBulkToolbarVisible.value = !isSearchBulkToolbarVisible.value
  if (!isSearchBulkToolbarVisible.value) {
    searchResultSelection.clearSelection()
  }
}

function selectFirstSearchEditDetail() {
  const firstDetail = searchEditDetailOptions.value[0]
  searchEditDraft.categoryDetailId = firstDetail ? String(firstDetail.id) : ''
}

function selectFirstSearchEditGroup() {
  const firstGroup = searchEditGroupOptions.value[0]
  searchEditDraft.categoryGroupId = firstGroup ? String(firstGroup.id) : ''
  selectFirstSearchEditDetail()
}

function validateSearchEditDraft() {
  const errors = []
  const amount = Number(normalizeAmountInput(searchEditDraft.amount))

  if (!searchEditDraft.entryDate) {
    errors.push('날짜')
  }
  if (!String(searchEditDraft.title || '').trim()) {
    errors.push('제목')
  }
  if (!Number.isFinite(amount) || amount <= 0) {
    errors.push('금액')
  }
  if (!searchEditDraft.categoryGroupId) {
    errors.push('대분류')
  }
  if (!searchEditPaymentDisabled.value && !searchEditDraft.paymentMethodId) {
    errors.push('결제수단')
  }

  return errors
}

function startSearchEntryEdit(entry) {
  editingSearchEntryId.value = entry.id
  searchEditDraft.entryDate = entry.entryDate || ''
  searchEditDraft.entryTime = normalizeSearchEditTimePayload(entry.entryTime)
  searchEditDraft.title = entry.title || ''
  searchEditDraft.memo = entry.memo || ''
  searchEditDraft.amount = normalizeAmountInput(entry.amount)
  searchEditDraft.foreignCurrencyCode = entry.foreignCurrencyCode || null
  searchEditDraft.foreignAmount = entry.foreignAmount ?? null
  searchEditDraft.exchangeRateToKrw = entry.exchangeRateToKrw ?? null
  searchEditDraft.entryType = entry.entryType === 'INCOME' ? 'INCOME' : 'EXPENSE'
  searchEditDraft.categoryGroupId = entry.categoryGroupId != null ? String(entry.categoryGroupId) : ''
  searchEditDraft.categoryDetailId = entry.categoryDetailId != null ? String(entry.categoryDetailId) : ''
  searchEditDraft.paymentMethodId = searchEditDraft.entryType === 'INCOME'
    ? ''
    : (entry.paymentMethodId != null ? String(entry.paymentMethodId) : '')

  if (!searchEditGroupOptions.value.some((group) => String(group.id) === String(searchEditDraft.categoryGroupId))) {
    selectFirstSearchEditGroup()
  } else if (
    searchEditDraft.categoryDetailId
    && !searchEditDetailOptions.value.some((detail) => String(detail.id) === String(searchEditDraft.categoryDetailId))
  ) {
    searchEditDraft.categoryDetailId = ''
  }
}

function cancelSearchEntryEdit() {
  editingSearchEntryId.value = null
}

function handleSearchEditEntryTypeChange() {
  selectFirstSearchEditGroup()
  if (searchEditPaymentDisabled.value) {
    searchEditDraft.paymentMethodId = ''
  } else {
    selectFirstSearchEditPaymentMethod()
  }
}

function handleSearchEditGroupChange() {
  selectFirstSearchEditDetail()
}

function submitSearchEntryEdit(entry) {
  searchEditDraft.amount = normalizeAmountInput(searchEditDraft.amount)
  if (searchEditErrors.value.length) {
    return
  }

  emit('save-search-entry', {
    entry,
    payload: {
      entryDate: searchEditDraft.entryDate,
      entryTime: normalizeSearchEditTimePayload(searchEditDraft.entryTime),
      title: searchEditDraft.title.trim(),
      memo: searchEditDraft.memo.trim() || null,
      amount: Number(searchEditDraft.amount || 0),
      foreignCurrencyCode: searchEditDraft.foreignCurrencyCode,
      foreignAmount: searchEditDraft.foreignAmount,
      exchangeRateToKrw: searchEditDraft.exchangeRateToKrw,
      entryType: searchEditDraft.entryType,
      categoryGroupId: Number(searchEditDraft.categoryGroupId),
      categoryDetailId: searchEditDraft.categoryDetailId ? Number(searchEditDraft.categoryDetailId) : null,
      paymentMethodId: resolveSearchEditPaymentMethodPayload(),
    },
  })
  cancelSearchEntryEdit()
}

watch(
  () => props.route,
  () => {
    cancelSearchEntryEdit()
    isSearchBulkToolbarVisible.value = false
    searchResultSelection.clearSelection()
  },
)

watch(
  () => props.searchResults.map((entry) => entry.id).join(','),
  () => {
    if (editingSearchEntryId.value && !props.searchResults.some((entry) => entry.id === editingSearchEntryId.value)) {
      cancelSearchEntryEdit()
    }
  },
)
</script>

<template>
  <div class="workspace-stack">
    <section class="panel toss-control-panel">
      <div class="panel__header">
        <div>
          <h2>통계 루트</h2>
          <p>일, 주, 월, 분기, 년, 사용자 지정 기간을 한 화면에서 비교합니다.</p>
        </div>
        <span class="panel__badge">{{ statsRangeLabel }}</span>
      </div>

      <div class="stats-toolbar">
        <div class="preset-chips">
          <button
            v-for="option in presetOptions"
            :key="option.value"
            :class="['preset-chip', { 'preset-chip--active': statsControls.preset === option.value }]"
            @click="statsControls.preset = option.value"
          >
            {{ option.label }}
          </button>
        </div>

        <div class="stats-toolbar__fields">
          <label v-if="statsControls.preset === 'CUSTOM'" class="field">
            <span class="field__label">조회 시작일</span>
            <input v-model="statsControls.customFrom" type="date" />
          </label>

          <label v-if="statsControls.preset === 'CUSTOM'" class="field">
            <span class="field__label">조회 종료일</span>
            <input v-model="statsControls.customTo" type="date" />
          </label>

          <label class="field">
            <span class="field__label">비교 단위</span>
            <select v-model="statsControls.compareUnit">
              <option value="DAY">일</option>
              <option value="WEEK">주</option>
              <option value="MONTH">월</option>
              <option value="QUARTER">분기</option>
              <option value="YEAR">년</option>
            </select>
          </label>

          <label class="field">
            <span class="field__label">비교 구간 수</span>
            <select v-model.number="statsControls.comparePeriods">
              <option :value="6">6</option>
              <option :value="8">8</option>
              <option :value="12">12</option>
              <option :value="16">16</option>
            </select>
          </label>
        </div>
      </div>
    </section>

    <template v-if="route === 'stats-overview'">
      <section class="summary-grid">
        <SummaryCard v-for="card in statsCards" :key="card.key" :card="card" />
      </section>

      <section class="chart-grid chart-grid--overview">
        <BarChartCard
          title="구간별 지출 추이"
          subtitle="최근 비교 구간 기준으로 지출 흐름을 막대 그래프로 보여줍니다."
          :items="comparisonChartItems"
          :format-value="formatCurrency"
          empty-text="비교할 지출 데이터가 없습니다."
        />
        <DonutChartCard
          title="지출 카테고리 비중"
          subtitle="가장 많이 쓴 카테고리를 색상으로 구분합니다."
          :items="expenseDonutItems"
          :format-value="formatCurrency"
          empty-text="카테고리 지출 데이터가 없습니다."
        />
        <DonutChartCard
          title="결제수단 비중"
          subtitle="카드, 현금, 포인트 사용 비중을 확인합니다."
          :items="paymentDonutItems"
          :format-value="formatCurrency"
          empty-text="결제수단 데이터가 없습니다."
        />
      </section>

      <div class="content-grid content-grid--stats">
        <section class="panel panel--wide">
          <div class="panel__header">
            <div>
              <h2>기간 비교 표</h2>
              <p>선택한 단위 기준으로 수입, 지출, 증감률 흐름을 표로 비교합니다.</p>
            </div>
            <span class="panel__badge">{{ comparisonBadge }}</span>
          </div>
          <ComparisonTable :rows="comparisonRows" />
        </section>

        <section class="panel">
          <BreakdownList title="지출 카테고리 상세" :items="expenseBreakdown" />
        </section>

        <section class="panel">
          <BreakdownList title="결제수단 상세" :items="paymentBreakdown" />
        </section>
      </div>
    </template>

    <template v-else-if="route === 'stats-search'">
      <section class="panel">
        <div class="panel__header">
          <div>
            <h2>검색</h2>
            <p>제목, 금액, 결제방법, 대분류 조건을 조합해서 거래를 찾습니다.</p>
          </div>
          <div class="search-header-actions">
            <button
              class="button button--ghost search-bulk-toggle"
              type="button"
              :class="{ 'search-bulk-toggle--active': isSearchBulkToolbarVisible }"
              @click="toggleSearchBulkToolbar"
            >
              {{ isSearchBulkToolbarVisible ? '일괄 수정 닫기' : '일괄 수정하기' }}
            </button>
            <span class="panel__badge">{{ searchPageInfo.totalElements }}건</span>
          </div>
        </div>

        <div class="search-grid">
          <div class="field field--full search-keyword-field">
            <label class="field__label" for="stats-search-keyword">키워드</label>
            <div class="search-keyword-field__control">
              <input
                id="stats-search-keyword"
                :value="searchKeywordDraft"
                type="text"
                placeholder="제목, 메모, 카테고리, 결제수단 검색"
                @input="emit('update-search-keyword-draft', $event.target.value)"
                @keydown.enter.prevent="emit('submit-search')"
              />
              <button class="button button--primary search-keyword-field__button" type="button" @click="emit('submit-search')">
                검색
              </button>
            </div>
          </div>

          <label class="field">
            <span class="field__label">구분</span>
            <select v-model="searchForm.entryType" @change="handleSearchEntryTypeFilterChange">
              <option value="">전체</option>
              <option value="EXPENSE">지출</option>
              <option value="INCOME">수입</option>
            </select>
          </label>

          <label class="field">
            <span class="field__label">결제수단</span>
            <select v-model="searchForm.paymentMethodId">
              <option value="">전체</option>
              <option :value="SEARCH_OTHER_FILTER_VALUE">기타(현재 목록 제외)</option>
              <option v-for="payment in paymentMethods" :key="payment.id" :value="String(payment.id)">
                {{ payment.name }}
              </option>
            </select>
          </label>

          <label class="field">
            <span class="field__label">대분류</span>
            <select v-model="searchForm.categoryGroupId" @change="handleSearchCategoryGroupFilterChange">
              <option value="">전체</option>
              <option :value="SEARCH_OTHER_FILTER_VALUE">기타(현재 목록 제외)</option>
              <option v-for="group in searchCategoryGroupOptions" :key="group.id" :value="String(group.id)">
                {{ group.entryType === 'INCOME' ? '수입' : '지출' }} / {{ group.name }}
              </option>
            </select>
          </label>

          <label class="field">
            <span class="field__label">소분류</span>
            <select v-model="searchForm.categoryDetailId">
              <option value="">전체</option>
              <option :value="SEARCH_OTHER_FILTER_VALUE">기타(현재 목록 제외)</option>
              <option v-for="detail in searchCategoryDetailOptions" :key="detail.id" :value="String(detail.id)">
                {{ formatSearchDetailFilterLabel(detail) }}
              </option>
            </select>
          </label>

          <label class="field">
            <span class="field__label">최소 금액</span>
            <input
              v-model="searchForm.minAmount"
              type="number"
              min="0"
              placeholder="0"
            />
          </label>

          <label class="field">
            <span class="field__label">최대 금액</span>
            <input v-model="searchForm.maxAmount" type="number" min="0" placeholder="제한 없음" />
          </label>

          <label class="field">
            <span class="field__label">정렬</span>
            <select v-model="searchForm.sortBy">
              <option value="DATE_DESC">최신순</option>
              <option value="DATE_ASC">오래된순</option>
              <option value="AMOUNT_DESC">금액 큰순</option>
              <option value="AMOUNT_ASC">금액 작은순</option>
            </select>
          </label>
        </div>

        <div class="search-summary">
          <div>
            <strong>{{ searchSummary.count }}건</strong>
            <span>검색 결과</span>
          </div>
          <div>
            <strong class="is-income">{{ formatCurrency(searchSummary.income) }}</strong>
            <span>수입 합계</span>
          </div>
          <div>
            <strong class="is-expense">{{ formatCurrency(searchSummary.expense) }}</strong>
            <span>지출 합계</span>
          </div>
        </div>

        <div v-if="isSearchBulkToolbarVisible" class="search-bulk-toolbar">
          <div class="search-bulk-toolbar__status">
            <strong>{{ selectedSearchCount }}건 선택</strong>
            <span>일괄 변경</span>
          </div>
          <label class="field search-bulk-toolbar__field">
            <span class="field__label">대분류</span>
            <select v-model="searchBulkDraft.categoryGroupId" @change="handleSearchBulkGroupChange">
              <option value="">유지</option>
              <option v-for="group in searchBulkGroupOptions" :key="group.id" :value="String(group.id)">
                {{ group.entryType === 'INCOME' ? '수입' : '지출' }} / {{ group.name }}
              </option>
            </select>
          </label>
          <label class="field search-bulk-toolbar__field">
            <span class="field__label">소분류</span>
            <select v-model="searchBulkDraft.categoryDetailId" :disabled="!searchBulkDraft.categoryGroupId">
              <option value="">없음</option>
              <option v-for="detail in searchBulkDetailOptions" :key="detail.id" :value="String(detail.id)">
                {{ detail.name }}
              </option>
            </select>
          </label>
          <label class="field search-bulk-toolbar__field">
            <span class="field__label">결제수단</span>
            <select v-model="searchBulkDraft.paymentMethodId" :disabled="searchBulkPaymentDisabled">
              <option value="">{{ searchBulkPaymentDisabled ? '수입은 자동 -' : '유지' }}</option>
              <option v-for="payment in paymentMethods" :key="payment.id" :value="String(payment.id)">
                {{ payment.name }}
              </option>
            </select>
          </label>
          <div class="search-bulk-toolbar__actions">
            <button
              class="button button--primary"
              type="button"
              :disabled="!canSubmitSearchBulkUpdate"
              @click="submitSearchBulkUpdate"
            >
              적용
            </button>
            <button
              class="button button--ghost"
              type="button"
              :disabled="!selectedSearchCount"
              @click="searchResultSelection.clearSelection()"
            >
              선택 해제
            </button>
          </div>
        </div>

        <div class="sheet-table-wrap">
          <table class="sheet-table stats-search-table">
            <thead>
              <tr>
                <th v-if="isSearchBulkToolbarVisible" class="sheet-table__select">
                  <input
                    class="sheet-table__checkbox"
                    type="checkbox"
                    :checked="searchResultSelection.allVisibleSelected"
                    :indeterminate.prop="searchResultSelection.someVisibleSelected"
                    @change="searchResultSelection.toggleAllVisible()"
                  />
                </th>
                <th>날짜</th>
                <th>시각</th>
                <th>제목</th>
                <th>카테고리</th>
                <th>결제수단</th>
                <th>금액</th>
                <th>관리</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="entry in searchResults" :key="entry.id" :class="{ 'sheet-table__row--editing': editingSearchEntryId === entry.id }">
                <td v-if="isSearchBulkToolbarVisible" class="sheet-table__select">
                  <input
                    class="sheet-table__checkbox"
                    type="checkbox"
                    :checked="searchResultSelection.isSelected(entry)"
                    @change="searchResultSelection.toggleItem(entry)"
                  />
                </td>
                <template v-if="editingSearchEntryId === entry.id">
                  <td>
                    <input v-model="searchEditDraft.entryDate" class="sheet-table__input stats-search-edit__date" type="date" />
                  </td>
                  <td>
                    <input v-model="searchEditDraft.entryTime" class="sheet-table__input stats-search-edit__time" type="time" />
                  </td>
                  <td class="sheet-table__title">
                    <input v-model="searchEditDraft.title" class="sheet-table__input stats-search-edit__title" type="text" />
                    <input v-model="searchEditDraft.memo" class="sheet-table__input stats-search-edit__memo" type="text" placeholder="메모" />
                  </td>
                  <td class="sheet-table__category">
                    <div class="stats-search-edit__stack">
                      <select v-model="searchEditDraft.entryType" class="sheet-table__select-input" @change="handleSearchEditEntryTypeChange">
                        <option value="EXPENSE">지출</option>
                        <option value="INCOME">수입</option>
                      </select>
                      <select v-model="searchEditDraft.categoryGroupId" class="sheet-table__select-input" @change="handleSearchEditGroupChange">
                        <option value="">대분류 선택</option>
                        <option v-for="group in searchEditGroupOptions" :key="group.id" :value="String(group.id)">
                          {{ group.name }}
                        </option>
                      </select>
                      <select v-model="searchEditDraft.categoryDetailId" class="sheet-table__select-input">
                        <option value="">소분류 없음</option>
                        <option v-for="detail in searchEditDetailOptions" :key="detail.id" :value="String(detail.id)">
                          {{ detail.name }}
                        </option>
                      </select>
                    </div>
                  </td>
                  <td class="sheet-table__textwrap">
                    <select v-model="searchEditDraft.paymentMethodId" class="sheet-table__select-input stats-search-edit__payment" :disabled="searchEditPaymentDisabled">
                      <option value="">{{ searchEditPaymentDisabled ? '수입은 자동 -' : '결제수단 선택' }}</option>
                      <option v-for="payment in paymentMethods" :key="payment.id" :value="String(payment.id)">
                        {{ payment.name }}
                      </option>
                    </select>
                  </td>
                  <td>
                    <input
                      v-model="searchEditDraft.amount"
                      class="sheet-table__input stats-search-edit__amount"
                      inputmode="decimal"
                      @change="searchEditDraft.amount = normalizeAmountInput(searchEditDraft.amount)"
                    />
                  </td>
                </template>
                <template v-else>
                  <td>{{ formatFullDate(entry.entryDate) }}</td>
                  <td>{{ formatTime(entry.entryTime) }}</td>
                  <td class="sheet-table__title">
                    <div class="stats-search-title-cell">
                      <strong>{{ entry.title }}</strong>
                      <span v-if="entry.memo" class="stats-search-title-cell__memo">{{ entry.memo }}</span>
                    </div>
                  </td>
                  <td class="sheet-table__category">{{ entry.categoryGroupName }}<template v-if="entry.categoryDetailName"> / {{ entry.categoryDetailName }}</template></td>
                  <td class="sheet-table__textwrap">{{ entry.paymentMethodName }}</td>
                  <td :class="entry.entryType === 'INCOME' ? 'is-income' : 'is-expense'">
                    {{ formatCurrency(entry.amount) }}
                  </td>
                </template>
                <td>
                  <div class="sheet-table__actions">
                    <template v-if="editingSearchEntryId === entry.id">
                      <button
                        type="button"
                        class="button button--primary"
                        :disabled="searchEditErrors.length > 0"
                        @click="submitSearchEntryEdit(entry)"
                      >
                        저장
                      </button>
                      <button type="button" class="button button--ghost" @click="cancelSearchEntryEdit">취소</button>
                    </template>
                    <template v-else>
                      <button type="button" class="button button--ghost" @click="startSearchEntryEdit(entry)">수정</button>
                      <button type="button" class="button button--ghost" @click="emit('move-search-entry', entry)">이동</button>
                    </template>
                    <button type="button" class="button button--ghost" @click="emit('delete-search-entry', entry)">삭제</button>
                  </div>
                  <p v-if="editingSearchEntryId === entry.id && searchEditErrors.length" class="stats-search-edit__hint">
                    {{ searchEditErrors.join(', ') }} 값을 확인해 주세요.
                  </p>
                </td>
              </tr>
              <tr v-if="!searchResults.length">
                <td :colspan="isSearchBulkToolbarVisible ? 8 : 7" class="sheet-table__empty">조건에 맞는 거래가 없습니다.</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="panel__actions">
          <button
            class="button button--ghost"
            type="button"
            :disabled="searchPageInfo.page <= 0"
            @click="emit('change-search-page', searchPageInfo.page - 1)"
          >
            이전
          </button>
          <span>{{ searchPageInfo.page + 1 }} / {{ searchPageLabel }}</span>
          <button
            class="button button--ghost"
            type="button"
            :disabled="searchPageInfo.page + 1 >= searchPageLabel"
            @click="emit('change-search-page', searchPageInfo.page + 1)"
          >
            다음
          </button>
        </div>
      </section>
    </template>

    <template v-else-if="route === 'stats-trash'">
      <section class="panel">
        <div class="panel__header">
          <div>
            <h2>휴지통</h2>
            <p>삭제한 가계부 내역을 보관하고, 필요하면 다시 복구할 수 있습니다.</p>
          </div>
          <span class="panel__badge">{{ trashPageInfo.totalElements }}건</span>
        </div>

        <div class="panel__actions panel__actions--end">
          <button
            type="button"
            class="button button--ghost"
            :disabled="!trashPageInfo.totalElements"
            @click="emit('empty-trash')"
          >
            휴지통 비우기
          </button>
        </div>

        <div class="sheet-table-wrap">
          <table class="sheet-table stats-search-table">
            <thead>
              <tr>
                <th class="sheet-table__select">
                  <input
                    class="sheet-table__checkbox"
                    type="checkbox"
                    :checked="trashResultSelection.allVisibleSelected"
                    :indeterminate.prop="trashResultSelection.someVisibleSelected"
                    @change="trashResultSelection.toggleAllVisible()"
                  />
                </th>
                <th>날짜</th>
                <th>시각</th>
                <th>제목</th>
                <th>카테고리</th>
                <th>결제수단</th>
                <th>금액</th>
                <th>관리</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="entry in trashResults" :key="entry.id">
                <td class="sheet-table__select">
                  <input
                    class="sheet-table__checkbox"
                    type="checkbox"
                    :checked="trashResultSelection.isSelected(entry)"
                    @change="trashResultSelection.toggleItem(entry)"
                  />
                </td>
                <td>{{ formatFullDate(entry.entryDate) }}</td>
                <td>{{ formatTime(entry.entryTime) }}</td>
                <td class="sheet-table__title">{{ entry.title }}</td>
                <td class="sheet-table__category">{{ entry.categoryGroupName }}<template v-if="entry.categoryDetailName"> / {{ entry.categoryDetailName }}</template></td>
                <td class="sheet-table__textwrap">{{ entry.paymentMethodName }}</td>
                <td :class="entry.entryType === 'INCOME' ? 'is-income' : 'is-expense'">
                  {{ formatCurrency(entry.amount) }}
                </td>
                <td>
                  <div class="sheet-table__actions">
                    <button type="button" class="button button--ghost" @click="emit('restore-trash-entry', entry)">복구</button>
                  </div>
                </td>
              </tr>
              <tr v-if="!trashResults.length">
                <td colspan="8" class="sheet-table__empty">휴지통에 보관된 거래가 없습니다.</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="panel__actions">
          <button
            class="button button--ghost"
            type="button"
            :disabled="trashPageInfo.page <= 0"
            @click="emit('change-trash-page', trashPageInfo.page - 1)"
          >
            이전
          </button>
          <span>{{ trashPageInfo.page + 1 }} / {{ trashPageLabel }}</span>
          <button
            class="button button--ghost"
            type="button"
            :disabled="trashPageInfo.page + 1 >= trashPageLabel"
            @click="emit('change-trash-page', trashPageInfo.page + 1)"
          >
            다음
          </button>
        </div>
      </section>
    </template>

    <template v-else-if="route === 'stats-insights'">
      <section class="insight-grid">
        <article class="panel insight-card">
          <p class="insight-card__label">하루 중 지출이 가장 많은 시간대</p>
          <strong>{{ insights.strongestHour.label }}</strong>
          <span>{{ insights.strongestHour.caption }}</span>
        </article>
        <article class="panel insight-card">
          <p class="insight-card__label">7일 중 지출이 가장 많은 요일</p>
          <strong>{{ insights.strongestWeekday.label }}</strong>
          <span>{{ insights.strongestWeekday.caption }}</span>
        </article>
        <article class="panel insight-card">
          <p class="insight-card__label">한 달 중 지출이 가장 큰 주차</p>
          <strong>{{ insights.strongestWeekOfMonth.label }}</strong>
          <span>{{ insights.strongestWeekOfMonth.caption }}</span>
        </article>
        <article class="panel insight-card">
          <p class="insight-card__label">1년 중 지출이 가장 큰 월</p>
          <strong>{{ insights.strongestMonthOfYear.label }}</strong>
          <span>{{ insights.strongestMonthOfYear.caption }}</span>
        </article>
      </section>

      <section class="panel">
        <div class="panel__header">
          <div>
            <h2>복합 조회 하이라이트</h2>
            <p>선택한 기간에서 가장 지출이 컸던 날짜를 바로 보여줍니다.</p>
          </div>
        </div>
        <div class="insight-highlight">
          <strong>최대 지출일</strong>
          <span>{{ insights.peakExpenseDay.caption }}</span>
        </div>
      </section>

      <section class="chart-grid chart-grid--insights">
        <BarChartCard
          title="시간대별 지출"
          subtitle="하루 24시간 중 어느 시간대에 지출이 몰렸는지 보여줍니다."
          :items="hourlyChartItems"
          :format-value="formatCurrency"
          empty-text="시간 정보가 있는 지출이 없습니다."
        />
        <BarChartCard
          title="요일별 지출"
          subtitle="한 주 중 어느 요일의 소비가 큰지 보여줍니다."
          :items="weekdayChartItems"
          :format-value="formatCurrency"
          empty-text="요일별 지출 데이터가 없습니다."
        />
        <BarChartCard
          title="주차별 지출"
          subtitle="한 달 안에서 어느 주차에 지출이 몰렸는지 확인합니다."
          :items="weekOfMonthChartItems"
          :format-value="formatCurrency"
          empty-text="주차별 지출 데이터가 없습니다."
        />
        <BarChartCard
          title="월별 지출"
          subtitle="1년 중 소비가 컸던 달을 색상으로 구분합니다."
          :items="monthOfYearChartItems"
          :format-value="formatCurrency"
          empty-text="월별 지출 데이터가 없습니다."
        />
      </section>
    </template>

    <template v-else-if="route === 'stats-ai'">
      <section class="panel ai-analysis-panel">
        <div class="panel__header">
          <div>
            <h2>AI 소비 분석</h2>
            <p>선택한 가계부 기간을 LM Studio/n8n provider로 보내고, 반환된 분석 결과를 저장합니다.</p>
          </div>
          <span class="panel__badge">{{ aiAnalysisStatus?.provider || 'AI' }} · {{ aiAnalysisStatus?.model || 'auto' }}</span>
        </div>

        <div class="ai-analysis-status" :class="{ 'ai-analysis-status--ready': aiAnalysisStatus?.configured }">
          <strong>{{ aiAnalysisStatus?.configured ? '준비됨' : '설정 필요' }}</strong>
          <span>{{ aiAnalysisStatus?.message || 'AI 분석 실행 전 provider 설정을 확인해주세요.' }}</span>
        </div>

        <div class="ai-analysis-layout">
          <div class="ai-analysis-controls">
            <div class="scope-toggle scope-toggle--wrap">
              <button class="button" :class="{ 'button--primary': aiAnalysisControls.mode === 'PERIOD' }" type="button" @click="aiAnalysisControls.mode = 'PERIOD'">기간 분석</button>
              <button class="button" :class="{ 'button--primary': aiAnalysisControls.mode === 'COMPARISON' }" type="button" @click="aiAnalysisControls.mode = 'COMPARISON'">비교 분석</button>
            </div>

            <div class="field-grid field-grid--four">
              <label class="field">
                <span class="field__label">기준일</span>
                <input v-model="aiAnalysisControls.anchorDate" type="date" />
              </label>
              <label class="field">
                <span class="field__label">분석 기간</span>
                <select v-model="aiAnalysisControls.periodType">
                  <option v-for="option in aiPeriodOptions" :key="option.value" :value="option.value">{{ option.label }}</option>
                </select>
              </label>
              <label v-if="aiAnalysisControls.mode === 'COMPARISON'" class="field field--wide">
                <span class="field__label">비교 조건</span>
                <select v-model="aiAnalysisControls.comparisonPreset">
                  <option v-for="option in aiComparisonPresetOptions" :key="option.value" :value="option.value">{{ option.label }}</option>
                </select>
              </label>
            </div>

            <div v-if="isAiPeriodCustom || isAiComparisonCustom" class="field-grid field-grid--four">
              <label class="field">
                <span class="field__label">시작일</span>
                <input v-model="aiAnalysisControls.from" type="date" />
              </label>
              <label class="field">
                <span class="field__label">종료일</span>
                <input v-model="aiAnalysisControls.to" type="date" />
              </label>
              <label v-if="isAiComparisonCustom" class="field">
                <span class="field__label">비교 시작일</span>
                <input v-model="aiAnalysisControls.compareFrom" type="date" />
              </label>
              <label v-if="isAiComparisonCustom" class="field">
                <span class="field__label">비교 종료일</span>
                <input v-model="aiAnalysisControls.compareTo" type="date" />
              </label>
            </div>

            <div class="ai-analysis-actions">
              <button class="button" type="button" :disabled="aiAnalysisLoading" @click="emit('load-latest-ai-analysis')">기존 결과 불러오기</button>
              <button class="button button--primary" type="button" :disabled="aiAnalysisLoading || aiAnalysisStatus?.configured === false" @click="emit('request-ai-analysis')">
                {{ aiAnalysisLoading ? '분석 중...' : '분석 요청하기' }}
              </button>
            </div>
            <div v-if="aiAnalysisError" class="feedback feedback--error">{{ aiAnalysisError }}</div>
          </div>

          <div class="ai-analysis-result">
            <template v-if="aiHasResult">
              <aside class="ai-analysis-advisory" role="note" aria-label="AI analysis advisory notice">
                <strong>AI 분석 결과는 참고용 조언입니다.</strong>
                <span>이 화면은 거래를 자동으로 생성, 수정, 삭제, 분류하지 않습니다. AI 추천을 실제 가계부에 반영하려면 사용자가 별도의 확인 액션을 직접 수행해야 합니다.</span>
              </aside>
              <div class="ai-result-card-grid">
                <article v-for="card in aiResultCards" :key="card.label" class="ai-result-card">
                  <span>{{ card.label }}</span>
                  <strong>{{ card.value }}</strong>
                  <small>{{ card.meta }}</small>
                </article>
              </div>

              <section class="ai-result-section ai-result-section--summary">
                <h3>핵심 요약</h3>
                <p>{{ aiReportKeySummary || '반환된 핵심 요약이 없습니다.' }}</p>
              </section>

              <section class="ai-result-section ai-result-section--report">
                <h3>보고서</h3>
                <p>{{ aiReportFullReport || '반환된 종합 보고서가 없습니다.' }}</p>
              </section>

              <div class="ai-result-section-grid">
                <section class="ai-result-section">
                  <h3>평균 금액</h3>
                  <p>{{ aiReport.averageAmountInsight || `일 평균 지출은 ${formatCurrency(aiAnalysis.averageDailyExpense)}입니다.` }}</p>
                </section>
                <section class="ai-result-section">
                  <h3>눈에 띄는 소비</h3>
                  <ul>
                    <li v-for="item in safeAiReportList('notableSpending', aiAnalysis.highlights)" :key="item">{{ item }}</li>
                    <li v-if="!safeAiReportList('notableSpending', aiAnalysis.highlights).length">눈에 띄는 소비 항목이 없습니다.</li>
                  </ul>
                </section>
                <section class="ai-result-section">
                  <h3>고정/구독 지출</h3>
                  <ul>
                    <li v-for="item in aiFixedReportItems" :key="item">{{ item }}</li>
                    <li v-if="!aiFixedReportItems.length">고정 지출 또는 구독 후보가 없습니다.</li>
                  </ul>
                </section>
                <section class="ai-result-section">
                  <h3>비정상 지출</h3>
                  <ul>
                    <li v-for="item in aiAbnormalReportItems" :key="item">{{ item }}</li>
                    <li v-if="!aiAbnormalReportItems.length">확인이 필요한 비정상 지출 후보가 없습니다.</li>
                  </ul>
                </section>
                <section class="ai-result-section">
                  <h3>결제 방법</h3>
                  <p>{{ aiReport.topPaymentMethod || safeAiList(aiAnalysis.paymentInsights)[0] || '결제수단 분석 결과가 없습니다.' }}</p>
                </section>
                <section class="ai-result-section">
                  <h3>개선 사항</h3>
                  <ul>
                    <li v-for="item in safeAiReportList('improvementActions', aiAnalysis.recommendations)" :key="item">{{ item }}</li>
                    <li v-if="!safeAiReportList('improvementActions', aiAnalysis.recommendations).length">반환된 개선 사항이 없습니다.</li>
                  </ul>
                </section>
                <section v-if="aiIsComparisonResult" class="ai-result-section ai-result-section--wide">
                  <h3>비교 핵심</h3>
                  <ul>
                    <li v-for="item in aiComparisonReportItems" :key="item">{{ item }}</li>
                    <li v-if="!aiComparisonReportItems.length">비교 분석 결과가 없습니다.</li>
                  </ul>
                </section>
              </div>
              <div class="ai-breakdown-grid">
                <section class="ai-result-section">
                  <h3>상위 카테고리</h3>
                  <div v-for="item in (aiAnalysis.categoryBreakdown ?? []).slice(0, 6)" :key="`${item.groupName}-${item.detailName}`" class="ai-breakdown-row">
                    <span>{{ item.detailName ? `${item.groupName} / ${item.detailName}` : item.groupName }}</span>
                    <strong>{{ formatCurrency(item.totalAmount) }}</strong>
                  </div>
                </section>
                <section class="ai-result-section">
                  <h3>결제수단</h3>
                  <div v-for="item in (aiAnalysis.paymentBreakdown ?? []).slice(0, 6)" :key="`${item.paymentMethodName}-${item.kind}`" class="ai-breakdown-row">
                    <span>{{ item.paymentMethodName }} · {{ item.kind }}</span>
                    <strong>{{ formatCurrency(item.totalAmount) }}</strong>
                  </div>
                </section>
              </div>
            </template>
            <div v-else class="empty-state ai-analysis-empty">
              <strong>아직 AI 분석 결과가 없습니다.</strong>
              <span>조건을 선택해 분석을 요청하거나 저장된 결과를 불러오세요.</span>
            </div>
          </div>
        </div>
      </section>

      <section class="panel ai-history-panel">
        <div class="panel__header">
          <div>
            <h2>분석 기록</h2>
            <p>분석 유형, 기간, 생성일, 비교 여부로 이전 분석을 조회합니다.</p>
          </div>
          <span class="panel__badge">{{ aiAnalysisHistoryPage?.totalElements ?? 0 }}건</span>
        </div>

        <div class="field-grid field-grid--five ai-history-filter-grid">
          <label class="field">
            <span class="field__label">유형</span>
            <select v-model="aiAnalysisHistoryFilters.mode">
              <option v-for="option in aiModeOptions" :key="option.value" :value="option.value">{{ option.label }}</option>
            </select>
          </label>
          <label class="field">
            <span class="field__label">분석 기간</span>
            <select v-model="aiAnalysisHistoryFilters.periodType">
              <option value="">전체</option>
              <option v-for="option in aiPeriodOptions" :key="option.value" :value="option.value">{{ option.label }}</option>
            </select>
          </label>
          <label class="field">
            <span class="field__label">생성 시작일</span>
            <input v-model="aiAnalysisHistoryFilters.createdFrom" type="date" />
          </label>
          <label class="field">
            <span class="field__label">생성 종료일</span>
            <input v-model="aiAnalysisHistoryFilters.createdTo" type="date" />
          </label>
          <label class="field">
            <span class="field__label">Comparison</span>
            <select v-model="aiAnalysisHistoryFilters.comparisonOnly">
              <option v-for="option in aiComparisonOnlyOptions" :key="option.value" :value="option.value">{{ option.label }}</option>
            </select>
          </label>
        </div>

        <div class="ai-analysis-actions">
          <button class="button" type="button" :disabled="aiAnalysisHistoryLoading" @click="loadAiHistoryPage(0)">기록 검색</button>
        </div>
        <div v-if="aiAnalysisHistoryError" class="feedback feedback--error">{{ aiAnalysisHistoryError }}</div>

        <div class="ai-history-list">
          <article v-for="history in aiAnalysisHistoryItems" :key="history.id" class="ai-history-item">
            <div>
              <strong>{{ history.title }}</strong>
              <span>{{ formatAiMode(history.mode) }} · {{ formatAiPeriod(history.periodType) }} · {{ formatAiStatus(history.status) }}</span>
              <small>{{ formatAiRange(history.from, history.to) }}<template v-if="history.compareFrom"> vs {{ formatAiRange(history.compareFrom, history.compareTo) }}</template></small>
              <p>{{ history.summary || history.errorMessage || '저장된 요약이 없습니다.' }}</p>
              <small>{{ formatAiCreatedAt(history.createdAt) }}</small>
            </div>
            <div class="ai-history-item__actions">
              <button class="button" type="button" @click="emit('open-ai-analysis-history', history.id)">열기</button>
              <button class="button button--secondary" type="button" :disabled="aiAnalysisLoading || aiAnalysisStatus?.configured === false" @click="emit('rerun-ai-analysis', history.id)">재분석</button>
            </div>
          </article>
          <div v-if="!aiAnalysisHistoryLoading && !aiAnalysisHistoryItems.length" class="empty-state ai-analysis-empty">
            <strong>저장된 AI 분석 기록이 없습니다.</strong>
            <span>먼저 분석을 요청하면 이곳에 기록됩니다.</span>
          </div>
        </div>

        <div class="pagination-row">
          <button class="button" type="button" :disabled="(aiAnalysisHistoryPage?.page ?? 0) <= 0" @click="loadAiHistoryPage((aiAnalysisHistoryPage?.page ?? 0) - 1)">이전</button>
          <span>{{ (aiAnalysisHistoryPage?.page ?? 0) + 1 }} / {{ aiHistoryTotalPages }}</span>
          <button class="button" type="button" :disabled="(aiAnalysisHistoryPage?.page ?? 0) >= aiHistoryTotalPages - 1" @click="loadAiHistoryPage((aiAnalysisHistoryPage?.page ?? 0) + 1)">다음</button>
        </div>
      </section>
    </template>
    <template v-else-if="route === 'stats-compare'">
      <section class="panel">
        <div class="panel__header">
          <div>
            <h2>지난 기록 비교</h2>
            <p>어제, 지난주, 1개월 전, 1분기 전, 1년 전과 현재 기간을 비교합니다.</p>
          </div>
        </div>

        <div class="compare-cards">
          <article v-for="row in pastComparisons" :key="row.key" class="compare-card">
            <div class="compare-card__head">
              <strong>{{ row.label }}</strong>
              <span>{{ formatDateRange(row.from, row.to) }}</span>
            </div>
            <div class="compare-card__body">
              <div>
                <span>지출</span>
                <strong class="is-expense">{{ formatCurrency(row.overview.expense) }}</strong>
              </div>
              <div>
                <span>변화</span>
                <strong :class="row.deltaExpense > 0 ? 'is-expense' : 'is-income'">
                  {{ row.deltaExpense > 0 ? '+' : '' }}{{ formatCurrency(row.deltaExpense) }}
                </strong>
              </div>
            </div>
          </article>
        </div>
      </section>
    </template>
  </div>
</template>
