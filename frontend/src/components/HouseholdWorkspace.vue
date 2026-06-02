<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import {
  analyzeLedgerReceipt,
  bulkUpdateEntries,
  createCategoryDetail,
  createCategoryGroup,
  createEntry,
  createPaymentMethod,
  createTravelPlan,
  deactivateCategoryDetail,
  deactivateCategoryGroup,
  deactivatePaymentMethod,
  deleteEntry,
  downloadLedgerCsv,
  emptyDeletedEntries,
  fetchCategories,
  fetchCategoryBreakdown,
  fetchCompare,
  fetchDashboard,
  fetchDeletedEntryPage,
  fetchEntryDateRange,
  fetchLedgerEntryHistories,
  fetchLedgerEntryHistory,
  fetchLedgerExchangeRate,
  fetchEntrySearchPage,
  fetchEntries,
  fetchHouseholdAggregatePreferences,
  fetchTravelPlans,
  fetchOverview,
  fetchPaymentBreakdown,
  linkLedgerEntryToTravelRecord,
  fetchPaymentMethods,
  restoreEntry,
  restoreLedgerEntryHistory,
  saveHouseholdAggregatePreferences,
  updateEntry,
} from '../lib/api'
import {
  buildCalendarWeeks,
  formatCurrency,
  formatCurrencyByCode,
  formatFullDate,
  formatDateRange,
  formatMonthLabel,
  formatShortDate,
  formatTime,
  getMonthRange,
  parseIsoDate,
  getWeekdayLabels,
  toIsoDate,
} from '../lib/format'
import {
  buildInsights,
  buildPastComparisonRanges,
  getDefaultTimeValue,
  getPresetOptions,
  resolveRange,
  shiftRange,
} from '../lib/analytics'
import CalendarWorkspace from './CalendarWorkspace.vue'
import HouseholdTravelLedgerWorkspace from './HouseholdTravelLedgerWorkspace.vue'
import LedgerImportWorkspace from './LedgerImportWorkspace.vue'
import ManagementWorkspace from './ManagementWorkspace.vue'
import StatisticsWorkspace from './StatisticsWorkspace.vue'
import PaletteContainer from '../features/palette/components/PaletteContainer.vue'

const props = defineProps({
  currentUser: {
    type: Object,
    default: null,
  },
  initialTab: {
    type: String,
    default: '',
  },
})

const compareUnitLabels = {
  DAY: '일간',
  WEEK: '주간',
  MONTH: '월간',
  QUARTER: '분기',
  YEAR: '연간',
}

const today = toIsoDate(new Date())
const quickAmountButtons = [10000, 30000, 50000, 100000]
const foreignCurrencyOptions = ['USD', 'JPY', 'EUR', 'CNY', 'GBP', 'AUD', 'CAD', 'HKD', 'SGD', 'THB', 'PHP', 'VND', 'TWD']
const SEARCH_PAGE_SIZE = 100
const SEARCH_OTHER_FILTER_VALUE = '__OTHER__'
const csvExportOptions = [
  { value: 'ALL', label: '전체 데이터' },
  { value: 'LAST_6_MONTHS', label: '최근 6개월' },
  { value: 'LAST_1_YEAR', label: '최근 1년' },
  { value: 'LAST_3_YEARS', label: '최근 3년' },
  { value: 'CURRENT_VIEW', label: '현재 조회 범위' },
  { value: 'CUSTOM', label: '직접 선택' },
]

const isLoading = ref(false)
const isSubmitting = ref(false)
const activeSubmit = ref('')
const feedback = ref('')
const errorMessage = ref('')
const undoableEntryAction = ref(null)
const householdTab = ref('dashboard')
const householdAnchorDate = ref(today)
const calendarAnchorDate = householdAnchorDate
const calendarReady = ref(false)
const statsReady = ref(false)

const dashboard = ref({
  anchorDate: today,
  quickStats: [],
  calendar: [],
  expenseBreakdown: [],
  paymentBreakdown: [],
  monthlyComparison: [],
  recentEntries: [],
})
const monthEntries = ref([])
const statsEntries = ref([])
const statsOverview = ref({
  from: today,
  to: today,
  income: 0,
  expense: 0,
  balance: 0,
  entryCount: 0,
})
const comparisonRows = ref([])
const expenseBreakdown = ref([])
const paymentBreakdown = ref([])
const pastComparisons = ref([])
const searchPageState = ref({
  content: [],
  page: 0,
  size: SEARCH_PAGE_SIZE,
  totalElements: 0,
  totalPages: 0,
  summary: {
    income: 0,
    expense: 0,
    balance: 0,
    count: 0,
  },
})
const trashPageState = ref({
  content: [],
  page: 0,
  size: SEARCH_PAGE_SIZE,
  totalElements: 0,
  totalPages: 0,
})
const categories = ref([])
const paymentMethods = ref([])
const entryDateRange = ref({
  earliestDate: null,
  latestDate: null,
})
const aggregateWidgetConfigs = ref([])
const aggregateSettingsReady = ref(false)
const editingEntryId = ref(null)
const amountInput = ref('')
const isEntryTimeEnabled = ref(false)
const calendarWorkspaceRef = ref(null)
const titleSuggestionSearchResults = ref([])
const householdTravelPlans = ref([])
const selectedHouseholdTravelPlanId = ref('')
const householdTravelPlanError = ref('')
const isHouseholdTravelPlanLoading = ref(false)
const isHouseholdTravelPlanSubmitting = ref(false)
const linkingTravelEntryId = ref('')
const receiptOcr = reactive({
  isOpen: false,
  documentType: 'AUTO',
  isAnalyzing: false,
  pendingCount: 0,
  error: '',
  items: [],
  fileName: '',
  rawText: '',
  suggestedEntry: null,
  lineItems: [],
  warnings: [],
  confidence: null,
  vendor: '',
  paymentMethodText: '',
  categoryText: '',
  timing: null,
})
const ledgerChangeHistory = reactive({
  isOpen: false,
  isLoading: false,
  isDetailLoading: false,
  isRestoring: false,
  error: '',
  content: [],
  page: 0,
  size: 20,
  totalElements: 0,
  totalPages: 0,
  selected: null,
})
const foreignExchangeState = reactive({
  isLoading: false,
  error: '',
  rateToKrw: null,
  rateDate: '',
  provider: '',
})
let feedbackTimerId = null
let searchRequestTimerId = null
let titleSuggestionSearchTimerId = null
let titleSuggestionSearchRequestId = 0
let receiptOcrItemSequence = 0
let foreignExchangeRequestId = 0

const entryForm = reactive({
  entryDate: today,
  entryTime: getDefaultTimeValue(),
  title: '',
  memo: '',
  amount: '',
  currencyMode: 'KRW',
  foreignCurrencyCode: 'USD',
  foreignAmount: '',
  exchangeRateToKrw: '',
  exchangeRateDate: '',
  exchangeRateProvider: '',
  entryType: 'EXPENSE',
  categoryGroupId: '',
  categoryDetailId: '',
  paymentMethodId: '',
  travelPlanId: '',
  travelRecordId: '',
})

const householdTravelPlanForm = reactive({
  name: '',
  destination: '',
  startDate: today,
  endDate: today,
  homeCurrency: 'KRW',
  headCount: 1,
  status: 'PLANNED',
  colorHex: '#3182F6',
  memo: '',
})

const statsControls = reactive({
  anchorDate: householdAnchorDate.value,
  preset: 'MONTH',
  customFrom: today,
  customTo: today,
  compareUnit: 'MONTH',
  comparePeriods: 12,
})

const searchForm = reactive({
  keyword: '',
  entryType: '',
  paymentMethodId: '',
  categoryGroupId: '',
  categoryDetailId: '',
  minAmount: '',
  maxAmount: '',
  sortBy: 'DATE_DESC',
})

const csvExportControls = reactive({
  preset: 'ALL',
  customFrom: today,
  customTo: today,
})

const groupForm = reactive({
  entryType: 'EXPENSE',
  name: '',
  displayOrder: 0,
})

const detailForm = reactive({
  groupId: '',
  name: '',
  displayOrder: 0,
})

const paymentForm = reactive({
  kind: 'CARD',
  name: '',
  displayOrder: 0,
})

const presetOptions = getPresetOptions()
const weekdayLabels = getWeekdayLabels()

const availableGroups = computed(() => categories.value.filter((group) => group.entryType === entryForm.entryType))
const availableDetails = computed(() => {
  const group = categories.value.find((item) => String(item.id) === String(entryForm.categoryGroupId))
  return group?.details ?? []
})
const selectedHouseholdTravelPlan = computed(() =>
  householdTravelPlans.value.find((plan) => String(plan.id) === String(selectedHouseholdTravelPlanId.value)) || null,
)
const entryTravelPlanDateLimit = computed(() => {
  if (!entryForm.travelPlanId) {
    return { min: '', max: '' }
  }
  const plan = householdTravelPlans.value.find((item) => String(item.id) === String(entryForm.travelPlanId))
  if (!plan?.startDate || !plan?.endDate) {
    return { min: '', max: '' }
  }
  return {
    min: plan.startDate,
    max: plan.endDate,
  }
})
const amountPreview = computed(() => Number(entryForm.amount || 0))
const isForeignCurrencyMode = computed(() => entryForm.currencyMode === 'FOREIGN')
const canUndoLastEntryAction = computed(() => Boolean(undoableEntryAction.value?.entryId))
const undoEntryActionLabel = computed(() => (
  undoableEntryAction.value?.type === 'update'
    ? '수정 취소'
    : '등록 취소'
))
const quickStats = computed(() => dashboard.value.quickStats ?? [])
const monthLabel = computed(() => formatMonthLabel(calendarAnchorDate.value))
const calendarWeeks = computed(() => buildCalendarWeeks(dashboard.value.calendar ?? [], calendarAnchorDate.value))
const statsRange = computed(() => resolveRange(
  statsControls.anchorDate,
  statsControls.preset,
  statsControls.customFrom,
  statsControls.customTo,
  entryDateRange.value.earliestDate,
  entryDateRange.value.latestDate,
))
const statsRangeLabel = computed(() => statsRange.value.label)
const statsCards = computed(() => [
  { key: 'selected', label: '선택 범위', overview: statsOverview.value },
  ...quickStats.value.slice(0, 3),
])
const comparisonBadge = computed(() => `${compareUnitLabels[statsControls.compareUnit] || statsControls.compareUnit} / ${statsControls.comparePeriods}개 구간`)
const currentViewCsvRange = computed(() => (
  householdTab.value.startsWith('stats-')
    ? statsRange.value
    : getMonthRange(calendarAnchorDate.value)
))
const comparisonAnchorDate = computed(() => {
  if (statsControls.preset === 'ALL') {
    return entryDateRange.value.latestDate || statsControls.anchorDate
  }
  return statsRange.value.to || statsControls.anchorDate
})
const csvExportRange = computed(() => resolveCsvExportRange(csvExportControls.preset))
const csvExportLabel = computed(() => csvExportRange.value.label)
const sortedMonthEntries = computed(() =>
  monthEntries.value
    .slice()
    .sort((left, right) => `${right.entryDate}${right.entryTime || '99:99'}${String(right.id).padStart(10, '0')}`.localeCompare(`${left.entryDate}${left.entryTime || '99:99'}${String(left.id).padStart(10, '0')}`)),
)
const searchResults = computed(() => searchPageState.value.content ?? [])
const searchSummary = computed(() => searchPageState.value.summary ?? {
  income: 0,
  expense: 0,
  balance: 0,
  count: 0,
})
const trashResults = computed(() => trashPageState.value.content ?? [])
const insights = computed(() => buildInsights(statsEntries.value))
const searchPageInfo = computed(() => searchPageState.value)
const trashPageInfo = computed(() => trashPageState.value)
const isEditingEntry = computed(() => editingEntryId.value !== null)
const dataActionMenuRef = ref(null)
const dataActionMenuOpen = ref(false)
const ledgerChangeHistoryPageLabel = computed(() => Math.max(ledgerChangeHistory.totalPages || 0, 1))

function normalizeTitleSuggestionText(value) {
  return String(value || '')
    .toLowerCase()
    .replace(/\s*[:：]\s*/g, ':')
    .replace(/\s+/g, ' ')
    .trim()
}

function getTitleSuggestionSearchKeyword(value) {
  const text = String(value || '').trim()
  if (!text) {
    return ''
  }

  const colonIndex = text.search(/[:：]/)
  if (colonIndex > 0) {
    return text.slice(0, colonIndex).trim()
  }
  return text
}

const entrySuggestions = computed(() => {
  const keyword = normalizeTitleSuggestionText(entryForm.title)
  if (!keyword) {
    return []
  }
  const baseEntries = [
    ...titleSuggestionSearchResults.value,
    ...(dashboard.value.recentEntries ?? []),
    ...monthEntries.value,
    ...statsEntries.value,
  ]
  const suggestions = []
  const seen = new Set()

  baseEntries
    .filter((entry) => entry.entryType === entryForm.entryType)
    .sort((left, right) => `${right.entryDate}${right.entryTime || ''}${String(right.id).padStart(10, '0')}`.localeCompare(`${left.entryDate}${left.entryTime || ''}${String(left.id).padStart(10, '0')}`))
    .forEach((entry) => {
      const title = String(entry.title || '').trim()
      const normalizedTitle = normalizeTitleSuggestionText(title)

      if (!normalizedTitle || !normalizedTitle.includes(keyword)) {
        return
      }

      const signature = `${entry.entryType}:${normalizedTitle}`

      if (seen.has(signature)) {
        return
      }

      seen.add(signature)
      suggestions.push({
        id: entry.id,
        entryDate: entry.entryDate,
        entryTime: normalizeEntryTimePayload(entry.entryTime),
        title,
        memo: entry.memo || '',
        amount: Number(entry.amount || 0),
        entryType: entry.entryType,
        categoryGroupId: entry.categoryGroupId != null ? String(entry.categoryGroupId) : '',
        categoryDetailId: entry.categoryDetailId != null ? String(entry.categoryDetailId) : '',
        paymentMethodId: entry.paymentMethodId != null ? String(entry.paymentMethodId) : '',
        categoryLabel: [entry.categoryGroupName, entry.categoryDetailName].filter(Boolean).join(' / ') || '-',
        paymentMethodName: entry.paymentMethodName || '-',
      })
    })

  return suggestions.slice(0, 10)
})

function sortEntriesByRecent(left, right) {
  const leftKey = `${left.entryDate || ''}${left.entryTime || ''}${String(left.id ?? '').padStart(10, '0')}`
  const rightKey = `${right.entryDate || ''}${right.entryTime || ''}${String(right.id ?? '').padStart(10, '0')}`
  return rightKey.localeCompare(leftKey)
}

function getRecentEntryCandidates() {
  const seen = new Set()
  return [
    ...(dashboard.value.recentEntries ?? []),
    ...monthEntries.value,
    ...statsEntries.value,
  ]
    .filter((entry) => entry?.entryDate)
    .filter((entry) => {
      const key = entry.id != null
        ? `id:${entry.id}`
        : `${entry.entryType}:${entry.entryDate}:${entry.entryTime}:${entry.title}:${entry.amount}`
      if (seen.has(key)) {
        return false
      }
      seen.add(key)
      return true
    })
    .sort(sortEntriesByRecent)
}

function getLatestEntryForType(entryType) {
  return getRecentEntryCandidates().find((entry) => entry.entryType === entryType) ?? null
}

function getGroupsForType(entryType) {
  return categories.value.filter((group) => group.entryType === entryType)
}

function getDetailsForGroupId(groupId, entryType = entryForm.entryType) {
  const group = getGroupsForType(entryType).find((item) => String(item.id) === String(groupId))
  return group?.details ?? []
}

function resolveDefaultGroupId(entryType, latestEntry) {
  const groups = getGroupsForType(entryType)
  const latestGroupId = latestEntry?.categoryGroupId != null ? String(latestEntry.categoryGroupId) : ''
  if (latestGroupId && groups.some((item) => String(item.id) === latestGroupId)) {
    return latestGroupId
  }
  return groups[0] ? String(groups[0].id) : ''
}

function resolveDefaultDetailId(groupId, latestEntry) {
  const details = getDetailsForGroupId(groupId)
  const latestMatchesGroup = latestEntry && String(latestEntry.categoryGroupId ?? '') === String(groupId)
  const latestDetailId = latestMatchesGroup && latestEntry.categoryDetailId != null
    ? String(latestEntry.categoryDetailId)
    : ''

  if (latestMatchesGroup) {
    return latestDetailId && details.some((item) => String(item.id) === latestDetailId)
      ? latestDetailId
      : ''
  }

  return details[0] ? String(details[0].id) : ''
}

function resolveDefaultPaymentMethodId(latestEntry) {
  const latestPaymentMethodId = latestEntry?.paymentMethodId != null ? String(latestEntry.paymentMethodId) : ''
  if (latestPaymentMethodId && paymentMethods.value.some((item) => String(item.id) === latestPaymentMethodId)) {
    return latestPaymentMethodId
  }
  return paymentMethods.value[0] ? String(paymentMethods.value[0].id) : ''
}

function isEntryFormEmptyForDefaults() {
  return !entryForm.title.trim()
    && !entryForm.memo.trim()
    && !entryForm.amount
    && !amountInput.value
}

watch(
  () => entryForm.entryType,
  () => {
    syncEntryDefaults({
      preferLatest: true,
      force: !isEditingEntry.value && isEntryFormEmptyForDefaults(),
    })
  },
)

watch(
  () => entryForm.categoryGroupId,
  () => {
    syncEntryDefaults({ preferLatest: true, force: false })
  },
)

watch(
  () => [entryForm.currencyMode, entryForm.foreignCurrencyCode, entryForm.entryDate],
  () => {
    if (entryForm.currencyMode !== 'FOREIGN') {
      clearForeignExchangeFields()
      return
    }
    queueForeignExchangeRateLoad()
  },
)

watch(
  () => [entryForm.currencyMode, entryForm.foreignAmount, entryForm.exchangeRateToKrw],
  () => {
    syncForeignKrwAmount()
  },
)

watch(
  () => [entryForm.title, entryForm.entryType, entryDateRange.value.earliestDate, entryDateRange.value.latestDate],
  () => {
    queueTitleSuggestionSearch()
  },
)

watch(calendarAnchorDate, async () => {
  if (calendarReady.value) {
    await loadCalendarData()
  }
  if (!isEditingEntry.value) {
    entryForm.entryDate = calendarAnchorDate.value
  }
  if (statsControls.anchorDate !== calendarAnchorDate.value) {
    statsControls.anchorDate = calendarAnchorDate.value
  }
})

watch(
  () => statsControls.anchorDate,
  (value) => {
    if (value && value !== calendarAnchorDate.value) {
      calendarAnchorDate.value = value
    }
  },
)

watch(
  () => [statsControls.anchorDate, statsControls.preset, statsControls.customFrom, statsControls.customTo, statsControls.compareUnit, statsControls.comparePeriods],
  async () => {
    if (statsReady.value) {
      await loadStatisticsData()
    }
  },
)

watch(
  householdTab,
  async (value, previousValue) => {
    if (!statsReady.value || value === previousValue) {
      return
    }

    if ((value.startsWith('stats-') && value !== 'stats-trash') || value === 'travel-ledger') {
      await loadStatisticsData()
    }
    if (value === 'travel-ledger' && !householdTravelPlans.value.length && !isHouseholdTravelPlanLoading.value) {
      await loadHouseholdTravelPlans()
    }
    if (value === 'stats-search') {
      await loadSearchResults(0)
    } else if (value === 'stats-trash') {
      await loadTrashResults(0)
    }
  },
)

watch(
  () => props.initialTab,
  (value) => {
    if (value === 'travel-ledger') {
      householdTab.value = 'travel-ledger'
    }
  },
  { immediate: true },
)

watch(
  () => [
    householdTab.value,
    statsRange.value.from,
    statsRange.value.to,
    searchForm.keyword,
    searchForm.entryType,
    searchForm.paymentMethodId,
    searchForm.categoryGroupId,
    searchForm.categoryDetailId,
    searchForm.minAmount,
    searchForm.maxAmount,
    searchForm.sortBy,
  ],
  () => {
    if (!statsReady.value || householdTab.value !== 'stats-search') {
      return
    }
    queueSearchResultsReload()
  },
)

onMounted(async () => {
  window.addEventListener('pointerdown', handleGlobalPointerDown)
  isLoading.value = true
  try {
    await loadMetadata()
    await loadEntryDateRange()
    await Promise.all([loadCalendarData(), loadStatisticsData(), loadAggregatePreferences()])
    loadHouseholdTravelPlans()
    resetEntryForm()
    resetHouseholdTravelPlanForm()
    calendarReady.value = true
    statsReady.value = true
  } catch (error) {
    undoableEntryAction.value = null
    setFeedback('', error.message)
  } finally {
    isLoading.value = false
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('pointerdown', handleGlobalPointerDown)
  receiptOcr.items.forEach(revokeReceiptOcrItemPreview)
  if (feedbackTimerId) {
    window.clearTimeout(feedbackTimerId)
  }
  if (searchRequestTimerId) {
    window.clearTimeout(searchRequestTimerId)
  }
  if (titleSuggestionSearchTimerId) {
    window.clearTimeout(titleSuggestionSearchTimerId)
  }
})

function setFeedback(message = '', error = '') {
  feedback.value = message
  errorMessage.value = error

  if (feedbackTimerId) {
    window.clearTimeout(feedbackTimerId)
    feedbackTimerId = null
  }

  if (message || error) {
    feedbackTimerId = window.setTimeout(() => {
      feedback.value = ''
      errorMessage.value = ''
      feedbackTimerId = null
    }, 5000)
  }
}

function normalizeForeignCurrencyCode(value) {
  return String(value || '').trim().toUpperCase()
}

function normalizeDecimalInput(value) {
  const text = String(value ?? '').replace(/[^\d.]/g, '')
  const parts = text.split('.')
  if (parts.length <= 1) {
    return parts[0] || ''
  }
  return `${parts[0]}.${parts.slice(1).join('').slice(0, 4)}`
}

function clearForeignExchangeFields() {
  foreignExchangeState.isLoading = false
  foreignExchangeState.error = ''
  foreignExchangeState.rateToKrw = null
  foreignExchangeState.rateDate = ''
  foreignExchangeState.provider = ''
  entryForm.foreignAmount = ''
  entryForm.exchangeRateToKrw = ''
  entryForm.exchangeRateDate = ''
  entryForm.exchangeRateProvider = ''
}

function applyExchangeQuote(quote) {
  const rate = Number(quote?.rateToKrw || 0)
  if (!quote?.available || !Number.isFinite(rate) || rate <= 0) {
    foreignExchangeState.rateToKrw = null
    foreignExchangeState.rateDate = ''
    foreignExchangeState.provider = ''
    entryForm.exchangeRateToKrw = ''
    entryForm.exchangeRateDate = ''
    entryForm.exchangeRateProvider = ''
    foreignExchangeState.error = '환율 정보를 불러오지 못했습니다.'
    return
  }

  foreignExchangeState.rateToKrw = rate
  foreignExchangeState.rateDate = quote.rateDate || entryForm.entryDate
  foreignExchangeState.provider = quote.provider || ''
  entryForm.exchangeRateToKrw = String(rate)
  entryForm.exchangeRateDate = foreignExchangeState.rateDate
  entryForm.exchangeRateProvider = foreignExchangeState.provider
  foreignExchangeState.error = ''
  syncForeignKrwAmount()
}

async function queueForeignExchangeRateLoad() {
  const currencyCode = normalizeForeignCurrencyCode(entryForm.foreignCurrencyCode)
  const requestId = ++foreignExchangeRequestId
  if (!currencyCode || currencyCode === 'KRW') {
    clearForeignExchangeFields()
    return
  }

  foreignExchangeState.isLoading = true
  foreignExchangeState.error = ''
  try {
    const quote = await fetchLedgerExchangeRate(currencyCode, entryForm.entryDate)
    if (requestId !== foreignExchangeRequestId || entryForm.currencyMode !== 'FOREIGN') {
      return
    }
    applyExchangeQuote(quote)
  } catch (error) {
    if (requestId !== foreignExchangeRequestId) {
      return
    }
    foreignExchangeState.error = error.message || '환율 정보를 불러오지 못했습니다.'
    entryForm.exchangeRateToKrw = ''
    entryForm.exchangeRateDate = ''
    entryForm.exchangeRateProvider = ''
  } finally {
    if (requestId === foreignExchangeRequestId) {
      foreignExchangeState.isLoading = false
    }
  }
}

function syncForeignKrwAmount() {
  if (entryForm.currencyMode !== 'FOREIGN') {
    return
  }
  const foreignAmount = Number(entryForm.foreignAmount || 0)
  const rate = Number(entryForm.exchangeRateToKrw || 0)
  if (!Number.isFinite(foreignAmount) || foreignAmount <= 0 || !Number.isFinite(rate) || rate <= 0) {
    entryForm.amount = ''
    amountInput.value = ''
    return
  }

  const nextAmount = String(Math.round(foreignAmount * rate))
  entryForm.amount = nextAmount
  amountInput.value = nextAmount
}

function hydrateForeignFieldsFromEntry(entry) {
  const currencyCode = normalizeForeignCurrencyCode(entry?.foreignCurrencyCode)
  if (currencyCode && currencyCode !== 'KRW') {
    entryForm.currencyMode = 'FOREIGN'
    entryForm.foreignCurrencyCode = currencyCode
    entryForm.foreignAmount = entry.foreignAmount != null ? String(Number(entry.foreignAmount)) : ''
    entryForm.exchangeRateToKrw = entry.exchangeRateToKrw != null ? String(Number(entry.exchangeRateToKrw)) : ''
    entryForm.exchangeRateDate = entry.exchangeRateDate || ''
    entryForm.exchangeRateProvider = entry.exchangeRateProvider || ''
    foreignExchangeState.rateToKrw = Number(entry.exchangeRateToKrw || 0) || null
    foreignExchangeState.rateDate = entry.exchangeRateDate || ''
    foreignExchangeState.provider = entry.exchangeRateProvider || ''
    foreignExchangeState.error = ''
    return
  }

  entryForm.currencyMode = 'KRW'
  entryForm.foreignCurrencyCode = 'USD'
  clearForeignExchangeFields()
}

function buildEntryFormSnapshot() {
  return {
    entryDate: entryForm.entryDate,
    entryTime: normalizeEntryTimePayload(entryForm.entryTime),
    title: entryForm.title,
    memo: entryForm.memo,
    amount: entryForm.amount,
    currencyMode: entryForm.currencyMode,
    foreignCurrencyCode: entryForm.foreignCurrencyCode,
    foreignAmount: entryForm.foreignAmount,
    exchangeRateToKrw: entryForm.exchangeRateToKrw,
    exchangeRateDate: entryForm.exchangeRateDate,
    exchangeRateProvider: entryForm.exchangeRateProvider,
    entryType: entryForm.entryType,
    categoryGroupId: entryForm.categoryGroupId,
    categoryDetailId: entryForm.categoryDetailId,
    paymentMethodId: entryForm.paymentMethodId,
    travelPlanId: entryForm.travelPlanId,
    travelRecordId: entryForm.travelRecordId,
    amountInput: amountInput.value,
    isTimeEnabled: isEntryTimeEnabled.value,
  }
}

function buildEntryFormSnapshotFromEntry(entry) {
  if (!entry) {
    return null
  }

  return {
    entryDate: entry.entryDate,
    entryTime: normalizeEntryTimePayload(entry.entryTime),
    title: entry.title || '',
    memo: entry.memo || '',
    amount: String(Number(entry.amount || 0)),
    amountInput: String(Number(entry.amount || 0)),
    currencyMode: entry.foreignCurrencyCode ? 'FOREIGN' : 'KRW',
    foreignCurrencyCode: entry.foreignCurrencyCode || 'USD',
    foreignAmount: entry.foreignAmount != null ? String(Number(entry.foreignAmount)) : '',
    exchangeRateToKrw: entry.exchangeRateToKrw != null ? String(Number(entry.exchangeRateToKrw)) : '',
    exchangeRateDate: entry.exchangeRateDate || '',
    exchangeRateProvider: entry.exchangeRateProvider || '',
    entryType: entry.entryType || 'EXPENSE',
    categoryGroupId: entry.categoryGroupId != null ? String(entry.categoryGroupId) : '',
    categoryDetailId: entry.categoryDetailId != null ? String(entry.categoryDetailId) : '',
    paymentMethodId: entry.paymentMethodId != null ? String(entry.paymentMethodId) : '',
    travelPlanId: entry.travelPlanId != null ? String(entry.travelPlanId) : '',
    travelRecordId: entry.travelRecordId != null ? String(entry.travelRecordId) : '',
    isTimeEnabled: hasEntryTimeValue(entry.entryTime),
  }
}

function restoreEntryFormSnapshot(snapshot) {
  if (!snapshot) {
    return
  }

  editingEntryId.value = null
  entryForm.entryDate = snapshot.entryDate || calendarAnchorDate.value
  entryForm.entryTime = snapshot.entryTime || '00:00'
  entryForm.title = snapshot.title || ''
  entryForm.memo = snapshot.memo || ''
  entryForm.amount = snapshot.amount || ''
  entryForm.currencyMode = snapshot.currencyMode || 'KRW'
  entryForm.foreignCurrencyCode = snapshot.foreignCurrencyCode || 'USD'
  entryForm.foreignAmount = snapshot.foreignAmount || ''
  entryForm.exchangeRateToKrw = snapshot.exchangeRateToKrw || ''
  entryForm.exchangeRateDate = snapshot.exchangeRateDate || ''
  entryForm.exchangeRateProvider = snapshot.exchangeRateProvider || ''
  entryForm.entryType = snapshot.entryType || 'EXPENSE'
  entryForm.categoryGroupId = snapshot.categoryGroupId || ''
  entryForm.categoryDetailId = snapshot.categoryDetailId || ''
  entryForm.paymentMethodId = snapshot.paymentMethodId || ''
  entryForm.travelPlanId = snapshot.travelPlanId || ''
  entryForm.travelRecordId = snapshot.travelRecordId || ''
  amountInput.value = snapshot.amountInput || ''
  isEntryTimeEnabled.value = Boolean(snapshot.isTimeEnabled)
  syncEntryDefaults()
}

function restoreSubmittedEntryAction(action) {
  if (action.type === 'update' && action.rollbackSnapshot && action.entryId) {
    restoreEntryFormSnapshot(action.rollbackSnapshot)
    editingEntryId.value = action.entryId
    return
  }

  if (!action?.submittedSnapshot) {
    return
  }

  restoreEntryFormSnapshot(action.submittedSnapshot)

  editingEntryId.value = null
}

function buildEntryPayloadFromEntry(entry) {
  if (!entry) {
    return null
  }

  return {
    entryDate: entry.entryDate,
    entryTime: normalizeEntryTimePayload(entry.entryTime),
    title: entry.title || '',
    memo: entry.memo || null,
    amount: Number(entry.amount || 0),
    foreignCurrencyCode: entry.foreignCurrencyCode || null,
    foreignAmount: entry.foreignAmount != null ? Number(entry.foreignAmount) : null,
    exchangeRateToKrw: entry.exchangeRateToKrw != null ? Number(entry.exchangeRateToKrw) : null,
    entryType: entry.entryType,
    categoryGroupId: Number(entry.categoryGroupId),
    categoryDetailId: entry.categoryDetailId != null ? Number(entry.categoryDetailId) : null,
    paymentMethodId: resolveEntryPaymentMethodPayload(entry.entryType, entry.paymentMethodId),
    travelPlanId: entry.travelPlanId != null ? Number(entry.travelPlanId) : null,
    travelRecordId: entry.travelRecordId != null ? Number(entry.travelRecordId) : null,
  }
}

function toOptionalNumber(value) {
  const text = String(value ?? '').trim()
  return text ? Number(text) : null
}

function normalizeEntryTimePayload(value) {
  const text = String(value ?? '').trim()
  const match = text.match(/^(\d{1,2}):(\d{1,2})/)
  if (!match) {
    return '00:00'
  }
  const hour = Math.min(Math.max(Number(match[1]), 0), 23)
  const minute = Math.min(Math.max(Number(match[2]), 0), 59)
  return `${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`
}

function hasEntryTimeValue(value) {
  const normalized = normalizeEntryTimePayload(value)
  return Boolean(normalized && normalized !== '00:00')
}

function resolveEntryPaymentMethodPayload(entryType, paymentMethodId) {
  return entryType === 'INCOME' ? null : toOptionalNumber(paymentMethodId)
}

function sanitizeAmountInput(value) {
  return String(value || '').replace(/[^0-9]/g, '')
}

function syncEntryDefaults({ preferLatest = true, force = false } = {}) {
  const entryType = entryForm.entryType || 'EXPENSE'
  const latestEntry = preferLatest ? getLatestEntryForType(entryType) : null
  const groups = getGroupsForType(entryType)
  const currentGroupValid = groups.some((item) => String(item.id) === String(entryForm.categoryGroupId))

  if (force || !currentGroupValid) {
    entryForm.categoryGroupId = resolveDefaultGroupId(entryType, latestEntry)
  }

  const details = getDetailsForGroupId(entryForm.categoryGroupId, entryType)
  const currentDetailValid = entryForm.categoryDetailId
    ? details.some((item) => String(item.id) === String(entryForm.categoryDetailId))
    : details.length === 0

  if (force || !currentDetailValid) {
    entryForm.categoryDetailId = resolveDefaultDetailId(entryForm.categoryGroupId, latestEntry)
  }

  if (entryType === 'INCOME') {
    entryForm.paymentMethodId = ''
  } else {
    const currentPaymentMethodValid = paymentMethods.value.some((item) => String(item.id) === String(entryForm.paymentMethodId))
    if (force || !currentPaymentMethodValid) {
      entryForm.paymentMethodId = resolveDefaultPaymentMethodId(latestEntry)
    }
  }

  if (!detailForm.groupId && categories.value[0]) {
    detailForm.groupId = String(categories.value[0].id)
  }
}

async function loadMetadata() {
  const [groupItems, paymentItems] = await Promise.all([fetchCategories(), fetchPaymentMethods()])
  categories.value = groupItems
  paymentMethods.value = paymentItems
  syncEntryDefaults()
}

async function loadHouseholdTravelPlans() {
  isHouseholdTravelPlanLoading.value = true
  householdTravelPlanError.value = ''
  try {
    householdTravelPlans.value = await fetchTravelPlans()
    if (
      selectedHouseholdTravelPlanId.value
      && !householdTravelPlans.value.some((plan) => String(plan.id) === String(selectedHouseholdTravelPlanId.value))
    ) {
      selectedHouseholdTravelPlanId.value = ''
    }
  } catch (error) {
    householdTravelPlanError.value = error.message || '여행 목록을 불러오지 못했습니다.'
  } finally {
    isHouseholdTravelPlanLoading.value = false
  }
}

function resetHouseholdTravelPlanForm() {
  householdTravelPlanForm.name = ''
  householdTravelPlanForm.destination = ''
  householdTravelPlanForm.startDate = statsControls.anchorDate || householdAnchorDate.value || today
  householdTravelPlanForm.endDate = householdTravelPlanForm.startDate
  householdTravelPlanForm.homeCurrency = 'KRW'
  householdTravelPlanForm.headCount = 1
  householdTravelPlanForm.status = 'PLANNED'
  householdTravelPlanForm.colorHex = '#3182F6'
  householdTravelPlanForm.memo = ''
}

function isDateInSelectedTravelPlan(dateValue) {
  const plan = selectedHouseholdTravelPlan.value
  if (!plan?.startDate || !plan?.endDate || !dateValue) {
    return false
  }
  return String(dateValue) >= String(plan.startDate) && String(dateValue) <= String(plan.endDate)
}

function resolveSelectedTravelEntryDate() {
  const plan = selectedHouseholdTravelPlan.value
  if (!plan) {
    return statsControls.anchorDate || householdAnchorDate.value || today
  }
  const anchor = statsControls.anchorDate || householdAnchorDate.value || plan.startDate
  return isDateInSelectedTravelPlan(anchor) ? anchor : plan.startDate
}

async function selectHouseholdTravelPlan(planId) {
  selectedHouseholdTravelPlanId.value = String(planId || '')
  const plan = selectedHouseholdTravelPlan.value
  if (!plan?.startDate || !plan?.endDate) {
    return
  }
  statsControls.preset = 'CUSTOM'
  statsControls.customFrom = plan.startDate
  statsControls.customTo = plan.endDate
  statsControls.anchorDate = plan.startDate
  householdAnchorDate.value = plan.startDate
  if (statsReady.value && householdTab.value === 'travel-ledger') {
    await loadStatisticsData()
  }
}

async function createHouseholdTravelPlan() {
  const name = String(householdTravelPlanForm.name || '').trim()
  const startDate = householdTravelPlanForm.startDate
  const endDate = householdTravelPlanForm.endDate
  if (!name) {
    householdTravelPlanError.value = '여행 이름을 입력해 주세요.'
    return
  }
  if (!startDate || !endDate || startDate > endDate) {
    householdTravelPlanError.value = '여행 시작일과 종료일을 올바르게 입력해 주세요.'
    return
  }

  isHouseholdTravelPlanSubmitting.value = true
  householdTravelPlanError.value = ''
  try {
    const created = await createTravelPlan({
      name,
      destination: String(householdTravelPlanForm.destination || '').trim(),
      startDate,
      endDate,
      homeCurrency: String(householdTravelPlanForm.homeCurrency || 'KRW').trim().toUpperCase() || 'KRW',
      headCount: Number(householdTravelPlanForm.headCount || 1),
      status: householdTravelPlanForm.status || 'PLANNED',
      colorHex: householdTravelPlanForm.colorHex || '#3182F6',
      memo: String(householdTravelPlanForm.memo || '').trim(),
    })
    await loadHouseholdTravelPlans()
    resetHouseholdTravelPlanForm()
    await selectHouseholdTravelPlan(created?.id)
    setFeedback('여행을 만들고 여행 가계부 조회 기간에 연결했습니다.')
  } catch (error) {
    householdTravelPlanError.value = error.message || '여행을 만들지 못했습니다.'
  } finally {
    isHouseholdTravelPlanSubmitting.value = false
  }
}

function patchEntryTravelLink(entryId, planId, recordId) {
  const patchEntry = (entry) => {
    if (!entry || String(entry.id) !== String(entryId)) {
      return entry
    }
    return {
      ...entry,
      travelPlanId: planId,
      travelRecordId: recordId,
    }
  }

  monthEntries.value = monthEntries.value.map(patchEntry)
  statsEntries.value = statsEntries.value.map(patchEntry)
  dashboard.value = {
    ...dashboard.value,
    recentEntries: (dashboard.value.recentEntries ?? []).map(patchEntry),
  }
  searchPageState.value = {
    ...searchPageState.value,
    content: (searchPageState.value.content ?? []).map(patchEntry),
  }
}

async function linkTravelLedgerEntry(entry) {
  const selectedPlan = selectedHouseholdTravelPlan.value
  const planId = selectedPlan?.id || entry?.travelPlanId
  if (!entry?.id || !planId) {
    setFeedback('', '연결할 여행과 거래를 선택해주세요.')
    return
  }
  if (entry.entryType !== 'EXPENSE') {
    setFeedback('', '여행 기록에는 지출 거래만 연결할 수 있습니다.')
    return
  }
  if (selectedPlan?.startDate && selectedPlan?.endDate && !isDateInSelectedTravelPlan(entry.entryDate)) {
    setFeedback('', '선택한 여행 기간 안의 거래만 여행 기록으로 연결할 수 있습니다.')
    return
  }

  linkingTravelEntryId.value = String(entry.id)
  setFeedback()
  try {
    const record = await linkLedgerEntryToTravelRecord(planId, entry.id)
    patchEntryTravelLink(entry.id, record.planId, record.id)
    setFeedback('가계부 거래를 여행 기록에 연결했습니다. 여행 기능에서 위치와 사진을 이어서 설정할 수 있습니다.')
  } catch (error) {
    setFeedback('', error.message)
  } finally {
    linkingTravelEntryId.value = ''
  }
}

function validateEntryTravelDate() {
  if (!entryForm.travelPlanId) {
    return
  }
  const plan = householdTravelPlans.value.find((item) => String(item.id) === String(entryForm.travelPlanId))
  if (!plan?.startDate || !plan?.endDate) {
    return
  }
  if (String(entryForm.entryDate) < String(plan.startDate) || String(entryForm.entryDate) > String(plan.endDate)) {
    throw new Error(`여행 가계부 거래는 ${plan.startDate} - ${plan.endDate} 기간 안에서만 입력할 수 있습니다.`)
  }
}

async function loadAggregatePreferences() {
  const response = await fetchHouseholdAggregatePreferences()
  aggregateWidgetConfigs.value = Array.isArray(response?.widgets) ? response.widgets : []
  aggregateSettingsReady.value = true
}

async function loadEntryDateRange() {
  const response = await fetchEntryDateRange()
  entryDateRange.value = {
    earliestDate: response?.earliestDate || null,
    latestDate: response?.latestDate || null,
  }

  if (!calendarReady.value && statsControls.preset === 'ALL' && entryDateRange.value.latestDate) {
    statsControls.anchorDate = entryDateRange.value.latestDate
  }
}

function handleChangeCalendarMonth(value) {
  calendarAnchorDate.value = value
}

function handleChangeHouseholdAnchorDate(value) {
  if (!value) {
    return
  }
  householdAnchorDate.value = value
}

function buildCalendarEntryRange(anchorDate) {
  const range = getMonthRange(anchorDate)
  const from = parseIsoDate(range.from)
  const to = parseIsoDate(range.to)
  from.setDate(from.getDate() - 7)
  to.setDate(to.getDate() + 7)
  return {
    from: toIsoDate(from),
    to: toIsoDate(to),
  }
}

async function loadCalendarData() {
  const range = buildCalendarEntryRange(calendarAnchorDate.value)
  const [dashboardResponse, entryItems] = await Promise.all([
    fetchDashboard(calendarAnchorDate.value),
    fetchEntries(range.from, range.to),
  ])
  dashboard.value = dashboardResponse
  monthEntries.value = entryItems
}

async function loadStatisticsData() {
  const range = statsRange.value
  const shouldLoadInsightEntries = householdTab.value === 'stats-insights' || householdTab.value === 'travel-ledger'
  const [overview, categoryItems, paymentItems, compareItems, entryItems] = await Promise.all([
    fetchOverview(range.from, range.to),
    fetchCategoryBreakdown(range.from, range.to, 'EXPENSE'),
    fetchPaymentBreakdown(range.from, range.to),
    fetchCompare(comparisonAnchorDate.value, statsControls.compareUnit, statsControls.comparePeriods),
    shouldLoadInsightEntries ? fetchEntries(range.from, range.to) : Promise.resolve([]),
  ])

  statsOverview.value = overview
  expenseBreakdown.value = categoryItems
  paymentBreakdown.value = paymentItems
  comparisonRows.value = compareItems
  statsEntries.value = entryItems
  await loadPastComparisons()
}

function queueSearchResultsReload() {
  if (searchRequestTimerId) {
    window.clearTimeout(searchRequestTimerId)
  }
  searchRequestTimerId = window.setTimeout(() => {
    if (householdTab.value !== 'stats-search') {
      searchRequestTimerId = null
      return
    }
    loadSearchResults(0).catch((error) => {
      setFeedback('', error.message)
    })
    searchRequestTimerId = null
  }, 200)
}

function clearTitleSuggestionSearch() {
  titleSuggestionSearchRequestId += 1
  titleSuggestionSearchResults.value = []
  if (titleSuggestionSearchTimerId) {
    window.clearTimeout(titleSuggestionSearchTimerId)
    titleSuggestionSearchTimerId = null
  }
}

function queueTitleSuggestionSearch() {
  if (titleSuggestionSearchTimerId) {
    window.clearTimeout(titleSuggestionSearchTimerId)
  }

  const keyword = normalizeTitleSuggestionText(entryForm.title)
  if (keyword.length < 2 || !entryDateRange.value.earliestDate || !entryDateRange.value.latestDate) {
    clearTitleSuggestionSearch()
    return
  }

  titleSuggestionSearchTimerId = window.setTimeout(() => {
    titleSuggestionSearchTimerId = null
    loadTitleSuggestionSearch().catch(() => {
      titleSuggestionSearchResults.value = []
    })
  }, 250)
}

async function loadTitleSuggestionSearch() {
  const requestId = titleSuggestionSearchRequestId + 1
  titleSuggestionSearchRequestId = requestId
  const response = await fetchEntrySearchPage({
    from: entryDateRange.value.earliestDate,
    to: entryDateRange.value.latestDate,
    keyword: getTitleSuggestionSearchKeyword(entryForm.title),
    entryType: entryForm.entryType,
    sortBy: 'DATE_DESC',
    page: 0,
    size: 20,
  })

  if (requestId !== titleSuggestionSearchRequestId) {
    return
  }
  titleSuggestionSearchResults.value = Array.isArray(response?.content) ? response.content : []
}

async function loadSearchResults(page = 0) {
  const range = statsRange.value
  const paymentMethodOther = searchForm.paymentMethodId === SEARCH_OTHER_FILTER_VALUE
  const categoryGroupOther = searchForm.categoryGroupId === SEARCH_OTHER_FILTER_VALUE
  const categoryDetailOther = searchForm.categoryDetailId === SEARCH_OTHER_FILTER_VALUE
  const response = await fetchEntrySearchPage({
    from: range.from,
    to: range.to,
    keyword: searchForm.keyword,
    entryType: searchForm.entryType,
    paymentMethodId: paymentMethodOther ? '' : searchForm.paymentMethodId,
    categoryGroupId: categoryGroupOther ? '' : searchForm.categoryGroupId,
    categoryDetailId: categoryDetailOther ? '' : searchForm.categoryDetailId,
    paymentMethodOther: paymentMethodOther ? true : '',
    categoryGroupOther: categoryGroupOther ? true : '',
    categoryDetailOther: categoryDetailOther ? true : '',
    minAmount: searchForm.minAmount,
    maxAmount: searchForm.maxAmount,
    sortBy: searchForm.sortBy,
    page,
    size: SEARCH_PAGE_SIZE,
  })

  if (response.totalPages > 0 && page >= response.totalPages) {
    await loadSearchResults(response.totalPages - 1)
    return
  }

  searchPageState.value = {
    content: response.content ?? [],
    page: response.page ?? 0,
    size: response.size ?? SEARCH_PAGE_SIZE,
    totalElements: response.totalElements ?? 0,
    totalPages: response.totalPages ?? 0,
    summary: response.summary ?? {
      income: 0,
      expense: 0,
      balance: 0,
      count: 0,
    },
  }
}

async function loadTrashResults(page = 0) {
  const response = await fetchDeletedEntryPage({
    page,
    size: SEARCH_PAGE_SIZE,
  })

  if (response.totalPages > 0 && page >= response.totalPages) {
    await loadTrashResults(response.totalPages - 1)
    return
  }

  trashPageState.value = {
    content: response.content ?? [],
    page: response.page ?? 0,
    size: response.size ?? SEARCH_PAGE_SIZE,
    totalElements: response.totalElements ?? 0,
    totalPages: response.totalPages ?? 0,
  }
}

function scrollHouseholdToTop() {
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

function scrollHouseholdToBottom() {
  const target = Math.max(document.documentElement.scrollHeight, document.body.scrollHeight)
  window.scrollTo({ top: target, behavior: 'smooth' })
}

function formatLedgerChangeDate(value) {
  if (!value) {
    return '-'
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }
  return new Intl.DateTimeFormat('ko-KR', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date)
}

function formatLedgerChangeFieldValue(value) {
  const text = String(value ?? '').trim()
  return text || '-'
}

async function loadLedgerChangeHistories(page = 0) {
  ledgerChangeHistory.isLoading = true
  ledgerChangeHistory.error = ''
  try {
    const response = await fetchLedgerEntryHistories({
      page,
      size: ledgerChangeHistory.size,
    })
    ledgerChangeHistory.content = response?.content ?? []
    ledgerChangeHistory.page = response?.page ?? 0
    ledgerChangeHistory.size = response?.size ?? ledgerChangeHistory.size
    ledgerChangeHistory.totalElements = response?.totalElements ?? 0
    ledgerChangeHistory.totalPages = response?.totalPages ?? 0

    const selectedId = ledgerChangeHistory.selected?.id
    const stillSelected = selectedId
      ? ledgerChangeHistory.content.some((item) => item.id === selectedId)
      : false
    if (!stillSelected) {
      ledgerChangeHistory.selected = null
      if (ledgerChangeHistory.content[0]) {
        await selectLedgerChangeHistory(ledgerChangeHistory.content[0])
      }
    }
  } catch (error) {
    ledgerChangeHistory.error = error.message
  } finally {
    ledgerChangeHistory.isLoading = false
  }
}

async function refreshOpenLedgerChangeHistory() {
  if (!ledgerChangeHistory.isOpen) {
    return
  }
  await loadLedgerChangeHistories(0)
}

async function openLedgerChangeHistoryModal() {
  ledgerChangeHistory.isOpen = true
  await loadLedgerChangeHistories(0)
}

function closeLedgerChangeHistoryModal() {
  ledgerChangeHistory.isOpen = false
}

async function selectLedgerChangeHistory(history) {
  if (!history?.id) {
    ledgerChangeHistory.selected = null
    return
  }
  ledgerChangeHistory.isDetailLoading = true
  ledgerChangeHistory.error = ''
  try {
    ledgerChangeHistory.selected = await fetchLedgerEntryHistory(history.id)
  } catch (error) {
    ledgerChangeHistory.error = error.message
  } finally {
    ledgerChangeHistory.isDetailLoading = false
  }
}

async function restoreLedgerChangeHistoryPoint(history) {
  const historyId = history?.id ?? ledgerChangeHistory.selected?.id
  if (!historyId) {
    return
  }
  if (!window.confirm('선택한 변경 이력의 변경 전 상태로 거래를 복구할까요? 현재 상태도 새 복구 이력으로 저장됩니다.')) {
    return
  }

  ledgerChangeHistory.isRestoring = true
  ledgerChangeHistory.error = ''
  try {
    const restoredHistory = await restoreLedgerEntryHistory(historyId)
    await refreshLedgerViews()
    await loadLedgerChangeHistories(0)
    if (restoredHistory?.id) {
      await selectLedgerChangeHistory(restoredHistory)
    }
    setFeedback('선택한 변경 이력 기준으로 거래를 복구했습니다.')
  } catch (error) {
    ledgerChangeHistory.error = error.message
    setFeedback('', error.message)
  } finally {
    ledgerChangeHistory.isRestoring = false
  }
}

async function loadPastComparisons() {
  const configs = buildPastComparisonRanges()
  pastComparisons.value = await Promise.all(
    configs.map(async (config) => {
      const currentRange = resolveRange(
        comparisonAnchorDate.value,
        config.preset,
        statsControls.customFrom,
        statsControls.customTo,
        entryDateRange.value.earliestDate,
        entryDateRange.value.latestDate,
      )
      const previousRange = shiftRange(
        comparisonAnchorDate.value,
        config.preset,
        statsControls.customFrom,
        statsControls.customTo,
        1,
        entryDateRange.value.earliestDate,
        entryDateRange.value.latestDate,
      )
      const [currentOverview, previousOverview] = await Promise.all([
        fetchOverview(currentRange.from, currentRange.to),
        fetchOverview(previousRange.from, previousRange.to),
      ])

      return {
        key: config.key,
        label: config.label,
        from: previousRange.from,
        to: previousRange.to,
        overview: previousOverview,
        deltaExpense: Number(previousOverview.expense || 0) - Number(currentOverview.expense || 0),
      }
    }),
  )
}

function resetEntryForm({ entryDate = calendarAnchorDate.value } = {}) {
  editingEntryId.value = null
  entryForm.entryDate = entryDate || calendarAnchorDate.value
  entryForm.entryTime = '00:00'
  entryForm.title = ''
  entryForm.memo = ''
  entryForm.amount = ''
  entryForm.currencyMode = 'KRW'
  entryForm.foreignCurrencyCode = 'USD'
  entryForm.foreignAmount = ''
  entryForm.exchangeRateToKrw = ''
  entryForm.exchangeRateDate = ''
  entryForm.exchangeRateProvider = ''
  entryForm.entryType = 'EXPENSE'
  entryForm.categoryGroupId = ''
  entryForm.categoryDetailId = ''
  entryForm.paymentMethodId = ''
  entryForm.travelPlanId = ''
  entryForm.travelRecordId = ''
  amountInput.value = ''
  foreignExchangeState.isLoading = false
  foreignExchangeState.error = ''
  foreignExchangeState.rateToKrw = null
  foreignExchangeState.rateDate = ''
  foreignExchangeState.provider = ''
  isEntryTimeEnabled.value = false
  syncEntryDefaults({ preferLatest: true, force: true })
}

function fillEntryForm(entry) {
  editingEntryId.value = entry.id
  entryForm.entryDate = entry.entryDate
  entryForm.entryTime = normalizeEntryTimePayload(entry.entryTime)
  entryForm.title = entry.title || ''
  entryForm.memo = entry.memo || ''
  entryForm.amount = String(Number(entry.amount || 0))
  hydrateForeignFieldsFromEntry(entry)
  entryForm.entryType = entry.entryType
  entryForm.categoryGroupId = String(entry.categoryGroupId)
  entryForm.categoryDetailId = entry.categoryDetailId != null ? String(entry.categoryDetailId) : ''
  entryForm.paymentMethodId = entry.entryType === 'INCOME' ? '' : String(entry.paymentMethodId)
  entryForm.travelPlanId = entry.travelPlanId != null ? String(entry.travelPlanId) : ''
  entryForm.travelRecordId = entry.travelRecordId != null ? String(entry.travelRecordId) : ''
  amountInput.value = String(Number(entry.amount || 0))
  isEntryTimeEnabled.value = hasEntryTimeValue(entry.entryTime)
}

async function fillEntryFormAndScroll(entry) {
  fillEntryForm(entry)
  await nextTick()
  calendarWorkspaceRef.value?.scrollToEntryEditor?.()
}

function normalizeCategorySearchText(value) {
  return String(value || '').replace(/\s+/g, '').toLowerCase()
}

function findCategoryGroupByKeywords(entryType, keywords) {
  const groups = categories.value.filter((group) => group.entryType === entryType)
  return groups.find((group) => {
    const name = normalizeCategorySearchText(group.name)
    return keywords.some((keyword) => name.includes(normalizeCategorySearchText(keyword)))
  }) || groups[0] || null
}

function findCategoryDetailByKeywords(groupId, keywords) {
  const group = categories.value.find((item) => String(item.id) === String(groupId))
  if (!group?.details?.length) {
    return null
  }
  return group.details.find((detail) => {
    const name = normalizeCategorySearchText(detail.name)
    return keywords.some((keyword) => name.includes(normalizeCategorySearchText(keyword)))
  }) || group.details[0] || null
}

async function startTravelLedgerEntry(payload = 'EXPENSE') {
  const requestedEntryType = typeof payload === 'object' && payload !== null ? payload.entryType : payload
  const requestedEntryDate = typeof payload === 'object' && payload !== null ? payload.entryDate : ''
  const normalizedEntryType = requestedEntryType === 'INCOME' ? 'INCOME' : 'EXPENSE'
  const selectedPlan = selectedHouseholdTravelPlan.value
  const entryDate = selectedPlan
    ? (isDateInSelectedTravelPlan(requestedEntryDate) ? requestedEntryDate : resolveSelectedTravelEntryDate())
    : (requestedEntryDate || resolveSelectedTravelEntryDate())
  householdTab.value = 'calendar'
  resetEntryForm({ entryDate })
  entryForm.entryType = normalizedEntryType
  entryForm.title = normalizedEntryType === 'INCOME' ? '여행 수입 : ' : '여행 : '
  entryForm.memo = selectedPlan?.name ? `여행 가계부 · ${selectedPlan.name}` : '여행 가계부'
  entryForm.travelPlanId = selectedPlan?.id ? String(selectedPlan.id) : ''
  entryForm.travelRecordId = ''
  calendarAnchorDate.value = entryDate

  const travelGroup = normalizedEntryType === 'INCOME'
    ? findCategoryGroupByKeywords('INCOME', ['여행', '정산', '환급', '수입', '외화'])
    : findCategoryGroupByKeywords('EXPENSE', ['여행', '교통', '숙박', '문화', '생활'])
  if (travelGroup?.id) {
    entryForm.categoryGroupId = String(travelGroup.id)
    const travelDetailKeywords = normalizedEntryType === 'INCOME'
      ? ['여행', '정산', '환급', '수입', '기타']
      : ['여행', '숙소', '교통', '항공', '식비', '입장', '기타']
    const travelDetail = findCategoryDetailByKeywords(travelGroup.id, travelDetailKeywords)
    entryForm.categoryDetailId = travelDetail?.id ? String(travelDetail.id) : ''
  }

  syncEntryDefaults({ preferLatest: false, force: false })
  await nextTick()
  calendarWorkspaceRef.value?.scrollToEntryEditor?.()
}

async function openTravelLedgerSearch(payload = '여행') {
  const keyword = typeof payload === 'object' && payload !== null ? payload.keyword : payload
  const entryType = typeof payload === 'object' && payload !== null ? payload.entryType : ''
  searchForm.keyword = String(keyword || '여행').trim() || '여행'
  searchForm.entryType = entryType === 'INCOME' || entryType === 'EXPENSE' ? entryType : ''
  searchForm.paymentMethodId = ''
  searchForm.categoryGroupId = ''
  searchForm.categoryDetailId = ''
  searchForm.minAmount = ''
  searchForm.maxAmount = ''
  searchForm.sortBy = 'DATE_DESC'
  householdTab.value = 'stats-search'
  await nextTick()
  if (statsReady.value) {
    await loadSearchResults(0)
  }
}

async function viewTravelLedgerEntryDate(entry) {
  householdTab.value = 'calendar'
  calendarAnchorDate.value = entry.entryDate
  await nextTick()
  calendarWorkspaceRef.value?.setSelectedDate?.(entry.entryDate)
  await calendarWorkspaceRef.value?.scrollToLedgerSheet?.()
}

async function editTravelLedgerEntry(entry) {
  householdTab.value = 'calendar'
  calendarAnchorDate.value = entry.entryDate
  await nextTick()
  calendarWorkspaceRef.value?.setSelectedDate?.(entry.entryDate)
  await fillEntryFormAndScroll(entry)
}

function applyEntrySuggestion(suggestion) {
  editingEntryId.value = null
  entryForm.title = suggestion.title || ''
  entryForm.memo = suggestion.memo || ''
  entryForm.amount = String(Number(suggestion.amount || 0))
  entryForm.currencyMode = 'KRW'
  clearForeignExchangeFields()
  entryForm.entryType = suggestion.entryType || entryForm.entryType
  amountInput.value = String(Number(suggestion.amount || 0))
  entryForm.categoryGroupId = suggestion.categoryGroupId || ''
  entryForm.categoryDetailId = suggestion.categoryDetailId || ''
  entryForm.paymentMethodId = suggestion.paymentMethodId || ''

  if (isEntryTimeEnabled.value) {
    entryForm.entryTime = normalizeEntryTimePayload(suggestion.entryTime)
  }

  syncEntryDefaults()
}

function applyEntryTitleSuggestion(suggestion) {
  if (!suggestion) {
    return
  }

  if (suggestion.title) {
    entryForm.title = suggestion.title
  }
  if (suggestion.memo) {
    entryForm.memo = suggestion.memo
  }

  if (suggestion.categoryGroupId) {
    entryForm.categoryGroupId = String(suggestion.categoryGroupId)
  }
  if (suggestion.categoryDetailId) {
    entryForm.categoryDetailId = String(suggestion.categoryDetailId)
  } else if (suggestion.categoryGroupId) {
    entryForm.categoryDetailId = ''
  }
  if (suggestion.paymentMethodId) {
    entryForm.paymentMethodId = String(suggestion.paymentMethodId)
  }

  syncEntryDefaults({ preferLatest: false, force: false })
}

function revokeReceiptOcrItemPreview(item) {
  if (item?.previewUrl) {
    URL.revokeObjectURL(item.previewUrl)
    item.previewUrl = ''
  }
}

function clearReceiptOcr() {
  receiptOcr.items.forEach(revokeReceiptOcrItemPreview)
  receiptOcr.isAnalyzing = false
  receiptOcr.pendingCount = 0
  receiptOcr.error = ''
  receiptOcr.items = []
  receiptOcr.fileName = ''
  receiptOcr.rawText = ''
  receiptOcr.suggestedEntry = null
  receiptOcr.lineItems = []
  receiptOcr.warnings = []
  receiptOcr.confidence = null
  receiptOcr.vendor = ''
  receiptOcr.paymentMethodText = ''
  receiptOcr.categoryText = ''
  receiptOcr.timing = null
}

function openReceiptOcrModal() {
  receiptOcr.isOpen = true
}

function closeReceiptOcrModal() {
  receiptOcr.isOpen = false
}

function normalizeOcrDocumentType(documentType) {
  const normalized = String(documentType || 'AUTO').trim().toUpperCase().replace('-', '_')
  return ['AUTO', 'RECEIPT', 'PAYMENT_CAPTURE'].includes(normalized) ? normalized : 'AUTO'
}

function setReceiptOcrDocumentType(documentType) {
  receiptOcr.documentType = normalizeOcrDocumentType(documentType)
}

function normalizeOcrSuggestion(suggestion = {}) {
  const entryType = suggestion.entryType === 'INCOME' ? 'INCOME' : 'EXPENSE'
  return {
    entryDate: suggestion.entryDate || calendarAnchorDate.value,
    entryTime: normalizeEntryTimePayload(suggestion.entryTime),
    title: suggestion.title || '',
    memo: suggestion.memo || '',
    amount: suggestion.amount !== null && suggestion.amount !== undefined && suggestion.amount !== ''
      ? String(Number(suggestion.amount || 0))
      : '',
    entryType,
    categoryGroupId: suggestion.categoryGroupId != null ? String(suggestion.categoryGroupId) : '',
    categoryGroupName: suggestion.categoryGroupName || '',
    categoryDetailId: suggestion.categoryDetailId != null ? String(suggestion.categoryDetailId) : '',
    categoryDetailName: suggestion.categoryDetailName || '',
    paymentMethodId: suggestion.paymentMethodId != null ? String(suggestion.paymentMethodId) : '',
    paymentMethodName: suggestion.paymentMethodName || '',
  }
}

function createReceiptOcrItem(file, documentType) {
  receiptOcrItemSequence += 1
  return {
    id: `ocr-${Date.now()}-${receiptOcrItemSequence}`,
    fileName: file.name || `transaction-image-${receiptOcrItemSequence}`,
    previewUrl: URL.createObjectURL(file),
    documentType,
    status: 'analyzing',
    error: '',
    rawText: '',
    suggestedEntries: [],
    lineItems: [],
    warnings: [],
    confidence: null,
    vendor: '',
    paymentMethodText: '',
    categoryText: '',
    timing: null,
  }
}

function syncReceiptOcrBusyState() {
  receiptOcr.pendingCount = receiptOcr.items.filter((item) => item.status === 'analyzing').length
  receiptOcr.isAnalyzing = receiptOcr.pendingCount > 0
}

function removeReceiptOcrItem(itemId) {
  const item = receiptOcr.items.find((candidate) => candidate.id === itemId)
  revokeReceiptOcrItemPreview(item)
  receiptOcr.items = receiptOcr.items.filter((candidate) => candidate.id !== itemId)
  syncReceiptOcrBusyState()
}

function updateReceiptOcrReviewEntry({ itemId, entryIndex, field, value }) {
  const item = receiptOcr.items.find((candidate) => candidate.id === itemId)
  const entry = item?.suggestedEntries?.[entryIndex]
  if (!entry || !field) {
    return
  }

  entry[field] = value

  if (field === 'entryType') {
    entry.entryType = value === 'INCOME' ? 'INCOME' : 'EXPENSE'
    const groups = getGroupsForType(entry.entryType)
    if (!groups.some((group) => String(group.id) === String(entry.categoryGroupId))) {
      entry.categoryGroupId = groups[0] ? String(groups[0].id) : ''
    }
    const details = getDetailsForGroupId(entry.categoryGroupId, entry.entryType)
    entry.categoryDetailId = details[0] ? String(details[0].id) : ''
    if (entry.entryType === 'INCOME') {
      entry.paymentMethodId = ''
    } else if (!paymentMethods.value.some((method) => String(method.id) === String(entry.paymentMethodId))) {
      entry.paymentMethodId = paymentMethods.value[0] ? String(paymentMethods.value[0].id) : ''
    }
  }

  if (field === 'categoryGroupId') {
    const details = getDetailsForGroupId(value, entry.entryType)
    entry.categoryDetailId = details[0] ? String(details[0].id) : ''
  }
}

function updateLegacyReceiptOcrFields(result, firstSuggestion, fileName) {
  receiptOcr.error = ''
  receiptOcr.fileName = fileName || ''
  receiptOcr.rawText = result?.rawText || ''
  receiptOcr.suggestedEntry = firstSuggestion || null
  receiptOcr.lineItems = Array.isArray(result?.lineItems) ? result.lineItems : []
  receiptOcr.warnings = Array.isArray(result?.warnings) ? result.warnings : []
  receiptOcr.confidence = result?.confidence ?? null
  receiptOcr.vendor = result?.vendor || ''
  receiptOcr.paymentMethodText = result?.paymentMethodText || ''
  receiptOcr.categoryText = result?.categoryText || ''
  receiptOcr.timing = result?.timing || null
}

async function analyzeReceiptFile(file, documentType) {
  const item = createReceiptOcrItem(file, documentType)
  receiptOcr.items.unshift(item)
  syncReceiptOcrBusyState()

  try {
    const result = await analyzeLedgerReceipt(file, { documentType })
    const suggestions = Array.isArray(result?.suggestedEntries) && result.suggestedEntries.length
      ? result.suggestedEntries
      : [result?.suggestedEntry].filter(Boolean)
    item.status = 'done'
    item.rawText = result?.rawText || ''
    item.suggestedEntries = suggestions.map(normalizeOcrSuggestion)
    item.lineItems = Array.isArray(result?.lineItems) ? result.lineItems : []
    item.warnings = Array.isArray(result?.warnings) ? result.warnings : []
    item.confidence = result?.confidence ?? null
    item.vendor = result?.vendor || ''
    item.paymentMethodText = result?.paymentMethodText || ''
    item.categoryText = result?.categoryText || ''
    item.timing = result?.timing || null
    updateLegacyReceiptOcrFields(result, item.suggestedEntries[0] || null, item.fileName)
    setFeedback('거래 이미지 분석이 완료됐습니다. 완료된 항목을 검토한 뒤 입력칸에 적용해 주세요.')
  } catch (error) {
    item.status = 'error'
    item.error = error.message
    receiptOcr.error = error.message
    setFeedback('', error.message)
  } finally {
    syncReceiptOcrBusyState()
  }
}

async function analyzeReceiptImage(payload) {
  const files = Array.isArray(payload?.files)
    ? payload.files
    : payload instanceof File
      ? [payload]
      : []
  if (!files.length) {
    return
  }

  const documentType = normalizeOcrDocumentType(payload?.documentType || receiptOcr.documentType)
  receiptOcr.documentType = documentType
  receiptOcr.isOpen = true
  receiptOcr.error = ''
  setFeedback()
  files.forEach((file) => {
    analyzeReceiptFile(file, documentType)
  })
  return

  receiptOcr.isAnalyzing = true
  receiptOcr.error = ''
  receiptOcr.fileName = file.name || 'receipt-image'
  receiptOcr.rawText = ''
  receiptOcr.suggestedEntry = null
  receiptOcr.lineItems = []
  receiptOcr.warnings = []
  receiptOcr.confidence = null
  receiptOcr.vendor = ''
  receiptOcr.paymentMethodText = ''
  receiptOcr.categoryText = ''
  receiptOcr.timing = null
  setFeedback()

  try {
    const result = await analyzeLedgerReceipt(file)
    receiptOcr.rawText = result?.rawText || ''
    receiptOcr.suggestedEntry = result?.suggestedEntry || null
    receiptOcr.lineItems = Array.isArray(result?.lineItems) ? result.lineItems : []
    receiptOcr.warnings = Array.isArray(result?.warnings) ? result.warnings : []
    receiptOcr.confidence = result?.confidence ?? null
    receiptOcr.vendor = result?.vendor || ''
    receiptOcr.paymentMethodText = result?.paymentMethodText || ''
    receiptOcr.categoryText = result?.categoryText || ''
    receiptOcr.timing = result?.timing || null
    setFeedback('영수증 분석이 완료됐습니다. 결과를 확인한 뒤 입력칸에 적용해 주세요.')
  } catch (error) {
    receiptOcr.error = error.message
    setFeedback('', error.message)
  } finally {
    receiptOcr.isAnalyzing = false
  }
}

async function applyReceiptOcrSuggestion(suggestion = receiptOcr.suggestedEntry) {
  if (!suggestion) {
    return
  }

  editingEntryId.value = null
  entryForm.entryDate = suggestion.entryDate || entryForm.entryDate
  entryForm.entryTime = normalizeEntryTimePayload(suggestion.entryTime)
  entryForm.title = suggestion.title || entryForm.title
  entryForm.memo = suggestion.memo || entryForm.memo
  entryForm.entryType = suggestion.entryType || 'EXPENSE'
  entryForm.currencyMode = 'KRW'
  clearForeignExchangeFields()

  if (suggestion.amount !== null && suggestion.amount !== undefined && suggestion.amount !== '') {
    const nextAmount = String(Number(suggestion.amount || 0))
    amountInput.value = nextAmount
    entryForm.amount = nextAmount
  }

  if (suggestion.categoryGroupId) {
    entryForm.categoryGroupId = String(suggestion.categoryGroupId)
  }
  if (suggestion.categoryDetailId) {
    entryForm.categoryDetailId = String(suggestion.categoryDetailId)
  }
  if (suggestion.paymentMethodId) {
    entryForm.paymentMethodId = String(suggestion.paymentMethodId)
  }
  isEntryTimeEnabled.value = hasEntryTimeValue(suggestion.entryTime)
  syncEntryDefaults({ preferLatest: true, force: false })

  await nextTick()
  calendarWorkspaceRef.value?.scrollToEntryEditor?.()
  setFeedback('영수증 분석 결과를 빠른 거래 입력칸에 적용했습니다. 저장 전 금액과 분류를 확인해 주세요.')
}

function buildEntryPayload() {
  const payload = {
    entryDate: entryForm.entryDate,
    entryTime: isEntryTimeEnabled.value ? normalizeEntryTimePayload(entryForm.entryTime) : '00:00',
    title: entryForm.title.trim(),
    memo: entryForm.memo.trim() || null,
    amount: Number(entryForm.amount || 0),
    entryType: entryForm.entryType,
    categoryGroupId: Number(entryForm.categoryGroupId),
    categoryDetailId: entryForm.categoryDetailId ? Number(entryForm.categoryDetailId) : null,
    paymentMethodId: resolveEntryPaymentMethodPayload(entryForm.entryType, entryForm.paymentMethodId),
    travelPlanId: entryForm.travelPlanId ? Number(entryForm.travelPlanId) : null,
    travelRecordId: entryForm.travelRecordId ? Number(entryForm.travelRecordId) : null,
  }
  if (entryForm.currencyMode === 'FOREIGN') {
    payload.foreignCurrencyCode = normalizeForeignCurrencyCode(entryForm.foreignCurrencyCode)
    payload.foreignAmount = Number(entryForm.foreignAmount || 0)
    payload.exchangeRateToKrw = Number(entryForm.exchangeRateToKrw || 0)
  } else {
    payload.foreignCurrencyCode = null
    payload.foreignAmount = null
    payload.exchangeRateToKrw = null
  }
  return payload
}

function handleAmountInput(value) {
  const digits = sanitizeAmountInput(value)
  amountInput.value = digits
  entryForm.amount = digits ? String(Number(digits)) : ''
  if (entryForm.currencyMode === 'FOREIGN') {
    entryForm.currencyMode = 'KRW'
    clearForeignExchangeFields()
  }
}

function fillAmount(value) {
  entryForm.currencyMode = 'KRW'
  clearForeignExchangeFields()
  amountInput.value = String(Number(value || 0))
  entryForm.amount = amountInput.value
}

function addAmount(value) {
  entryForm.currencyMode = 'KRW'
  clearForeignExchangeFields()
  const nextValue = amountPreview.value + Number(value || 0)
  amountInput.value = String(nextValue)
  entryForm.amount = String(nextValue)
}

function updateTimeEnabled(value) {
  isEntryTimeEnabled.value = Boolean(value)

  if (!isEntryTimeEnabled.value) {
    entryForm.entryTime = '00:00'
    return
  }

  if (!entryForm.entryTime || entryForm.entryTime === '00:00') {
    entryForm.entryTime = getDefaultTimeValue()
  }
}

function formatAmountShortcut(value) {
  const amount = Number(value || 0)
  if (amount >= 10000) {
    return `${(amount / 10000).toLocaleString('ko-KR')}만`
  }
  return amount.toLocaleString('ko-KR')
}

function handleGlobalPointerDown(event) {
  if (!dataActionMenuOpen.value) {
    return
  }

  if (dataActionMenuRef.value?.contains(event.target)) {
    return
  }

  dataActionMenuOpen.value = false
}

function toggleDataActionMenu() {
  dataActionMenuOpen.value = !dataActionMenuOpen.value
}

function openImportWorkspace() {
  householdTab.value = 'import'
  dataActionMenuOpen.value = false
}

function shiftIsoDate(value, { months = 0, years = 0 } = {}) {
  const date = parseIsoDate(value)
  if (years) {
    date.setFullYear(date.getFullYear() - years)
  }
  if (months) {
    date.setMonth(date.getMonth() - months)
  }
  return toIsoDate(date)
}

function resolveCsvExportRange(preset) {
  if (preset === 'ALL') {
    return {
      from: null,
      to: null,
      label: '전체 데이터',
      isAll: true,
    }
  }

  if (preset === 'LAST_6_MONTHS') {
    const from = shiftIsoDate(today, { months: 6 })
    return {
      from,
      to: today,
      label: `최근 6개월 · ${formatDateRange(from, today)}`,
      isAll: false,
    }
  }

  if (preset === 'LAST_1_YEAR') {
    const from = shiftIsoDate(today, { years: 1 })
    return {
      from,
      to: today,
      label: `최근 1년 · ${formatDateRange(from, today)}`,
      isAll: false,
    }
  }

  if (preset === 'LAST_3_YEARS') {
    const from = shiftIsoDate(today, { years: 3 })
    return {
      from,
      to: today,
      label: `최근 3년 · ${formatDateRange(from, today)}`,
      isAll: false,
    }
  }

  if (preset === 'CUSTOM') {
    return {
      from: csvExportControls.customFrom,
      to: csvExportControls.customTo,
      label: `직접 선택 · ${formatDateRange(csvExportControls.customFrom, csvExportControls.customTo)}`,
      isAll: false,
    }
  }

  return {
    from: currentViewCsvRange.value.from,
    to: currentViewCsvRange.value.to,
    label: `현재 조회 범위 · ${formatDateRange(currentViewCsvRange.value.from, currentViewCsvRange.value.to)}`,
    isAll: false,
  }
}

async function refreshLedgerViews() {
  await loadEntryDateRange()
  await Promise.all([loadCalendarData(), loadStatisticsData()])
  if (householdTab.value === 'stats-search') {
    await loadSearchResults(searchPageState.value.page ?? 0)
  } else if (householdTab.value === 'stats-trash') {
    await loadTrashResults(trashPageState.value.page ?? 0)
  }
}

async function handleImported(result) {
  await Promise.all([loadMetadata(), refreshLedgerViews(), loadAggregatePreferences()])
  const details = []
  if (result.createdCategoryGroups?.length) {
    details.push('category groups ' + result.createdCategoryGroups.length)
  }
  if (result.createdCategoryDetails?.length) {
    details.push('category details ' + result.createdCategoryDetails.length)
  }
  if (result.createdPaymentMethods?.length) {
    details.push('payment methods ' + result.createdPaymentMethods.length)
  }
  setFeedback(
    details.length
      ? 'Excel import completed. Created ' + details.join(', ') + '.'
      : 'Excel import completed.',
  )
}

async function exportEntriesToCsv() {
  isSubmitting.value = true
  activeSubmit.value = 'export-csv'
  dataActionMenuOpen.value = false
  setFeedback()
  try {
    if (!csvExportRange.value.isAll && (!csvExportRange.value.from || !csvExportRange.value.to)) {
      throw new Error('CSV 저장 범위를 먼저 확인해 주세요.')
    }
    await downloadLedgerCsv(csvExportRange.value.from, csvExportRange.value.to)
    setFeedback(`현재 로그인에 사용한 2차 비밀번호로 보호된 CSV 압축 파일을 저장했습니다. (${csvExportLabel.value})`)
  } catch (error) {
    setFeedback('', error.message)
  } finally {
    isSubmitting.value = false
    activeSubmit.value = ''
  }
}

async function updateAggregatePreferences(widgets) {
  isSubmitting.value = true
  activeSubmit.value = 'aggregate-settings'
  setFeedback()
  try {
    const response = await saveHouseholdAggregatePreferences(widgets)
    aggregateWidgetConfigs.value = Array.isArray(response?.widgets) ? response.widgets : []
    setFeedback('사용자 설정 집계를 저장했습니다.')
  } catch (error) {
    setFeedback('', error.message)
  } finally {
    isSubmitting.value = false
    activeSubmit.value = ''
  }
}

async function submitEntry() {
  isSubmitting.value = true
  activeSubmit.value = 'entry'
  setFeedback()
  try {
    validateEntryTravelDate()
    if (entryForm.currencyMode === 'FOREIGN') {
      syncForeignKrwAmount()
      if (!entryForm.foreignAmount || Number(entryForm.foreignAmount) <= 0) {
        throw new Error('외화 금액을 입력해 주세요.')
      }
      if (!entryForm.exchangeRateToKrw || Number(entryForm.exchangeRateToKrw) <= 0) {
        throw new Error('환율 정보를 불러온 뒤 저장해 주세요.')
      }
    }
    const submittedSnapshot = buildEntryFormSnapshot()
    const submittedPayload = buildEntryPayload()
    if (editingEntryId.value) {
      const rollbackEntry = monthEntries.value.find((entry) => entry.id === editingEntryId.value)
        ?? statsEntries.value.find((entry) => entry.id === editingEntryId.value)
        ?? dashboard.value.recentEntries?.find((entry) => entry.id === editingEntryId.value)
      const rollbackPayload = buildEntryPayloadFromEntry(rollbackEntry)
      const rollbackSnapshot = buildEntryFormSnapshotFromEntry(rollbackEntry)
      await updateEntry(editingEntryId.value, submittedPayload)
      undoableEntryAction.value = rollbackPayload
        ? {
            type: 'update',
            entryId: editingEntryId.value,
            rollbackPayload,
            rollbackSnapshot,
          }
        : null
      setFeedback('가계부 내역을 수정했습니다.')
    } else {
      const createdEntry = await createEntry(submittedPayload)
      undoableEntryAction.value = {
        type: 'create',
        entryId: createdEntry?.id ?? null,
        submittedSnapshot,
      }
      setFeedback('가계부 내역을 등록했습니다.')
    }
    await refreshLedgerViews()
    await refreshOpenLedgerChangeHistory()
    resetEntryForm({ entryDate: submittedSnapshot.entryDate })
  } catch (error) {
    setFeedback('', error.message)
  } finally {
    isSubmitting.value = false
    activeSubmit.value = ''
  }
}

async function undoLastEntryAction() {
  if (!undoableEntryAction.value?.entryId) {
    return
  }

  isSubmitting.value = true
  activeSubmit.value = 'entry-undo'
  setFeedback()

  try {
    const action = undoableEntryAction.value
    if (action.type === 'update' && action.rollbackPayload) {
      await updateEntry(action.entryId, action.rollbackPayload)
    } else {
      await deleteEntry(action.entryId, { permanent: true })
    }
    await refreshLedgerViews()
    await refreshOpenLedgerChangeHistory()
    restoreSubmittedEntryAction(action)
    undoableEntryAction.value = null
    setFeedback(action.type === 'update'
      ? '방금 수정한 내역을 취소하고 수정 전 값으로 복구했습니다.'
      : '방금 등록한 내역을 취소하고 입력값을 빠른 거래 입력에 복구했습니다.')
    await nextTick()
    calendarWorkspaceRef.value?.scrollToEntryEditor?.()
  } catch (error) {
    setFeedback('', error.message)
  } finally {
    isSubmitting.value = false
    activeSubmit.value = ''
  }
}

async function moveEntryFromSearch(entry) {
  householdTab.value = 'calendar'
  calendarAnchorDate.value = entry.entryDate
  await nextTick()
  calendarWorkspaceRef.value?.setSelectedDate?.(entry.entryDate)
  await fillEntryFormAndScroll(entry)
}

async function saveEntryFromSearch({ entry, payload }) {
  if (!entry?.id || !payload) {
    return
  }

  isSubmitting.value = true
  activeSubmit.value = 'search-entry-update'
  setFeedback()
  try {
    const rollbackPayload = buildEntryPayloadFromEntry(entry)
    const rollbackSnapshot = buildEntryFormSnapshotFromEntry(entry)
    await updateEntry(entry.id, payload)
    undoableEntryAction.value = rollbackPayload
      ? {
          type: 'update',
          entryId: entry.id,
          rollbackPayload,
          rollbackSnapshot,
        }
      : null
    await refreshLedgerViews()
    await refreshOpenLedgerChangeHistory()
    setFeedback('검색 결과에서 가계부 내역을 수정했습니다.')
  } catch (error) {
    setFeedback('', error.message)
  } finally {
    isSubmitting.value = false
    activeSubmit.value = ''
  }
}

async function bulkUpdateSearchEntries(payload) {
  const entryIds = Array.isArray(payload?.entryIds) ? payload.entryIds : []
  if (!entryIds.length) {
    setFeedback('', '일괄 변경할 거래를 선택해 주세요.')
    return
  }
  if (!payload.categoryGroupId && !payload.paymentMethodId) {
    setFeedback('', '변경할 대분류나 결제수단을 선택해 주세요.')
    return
  }
  if (!window.confirm(`선택한 ${entryIds.length}건의 거래를 일괄 변경할까요?`)) {
    return
  }

  isSubmitting.value = true
  activeSubmit.value = 'search-bulk-update'
  setFeedback()
  try {
    const response = await bulkUpdateEntries(payload)
    await refreshLedgerViews()
    await refreshOpenLedgerChangeHistory()
    setFeedback(`선택한 ${response.updatedCount ?? entryIds.length}건의 거래를 일괄 변경했습니다.`)
  } catch (error) {
    setFeedback('', error.message)
  } finally {
    isSubmitting.value = false
    activeSubmit.value = ''
  }
}

async function deleteEntryFromSearch(entry) {
  if (!window.confirm(`'${entry.title}' 내역을 휴지통으로 이동할까요?`)) {
    return
  }
  await removeEntry(entry)
}

async function restoreEntryFromTrash(entry) {
  isSubmitting.value = true
  activeSubmit.value = 'entry-restore'
  setFeedback()
  try {
    await restoreEntry(entry.id)
    await refreshLedgerViews()
    setFeedback('휴지통의 가계부 내역을 복구했습니다.')
  } catch (error) {
    setFeedback('', error.message)
  } finally {
    isSubmitting.value = false
    activeSubmit.value = ''
  }
}

async function emptyTrash() {
  if (!trashResults.value.length && !(trashPageInfo.value.totalElements > 0)) {
    return
  }
  if (!window.confirm('휴지통을 비우면 더이상 복구할 수 없습니다. 정말 비우시겠습니까?')) {
    return
  }

  isSubmitting.value = true
  activeSubmit.value = 'entry-empty-trash'
  setFeedback()
  try {
    await emptyDeletedEntries()
    await refreshLedgerViews()
    setFeedback('휴지통을 비웠습니다. 삭제된 가계부 내역은 더이상 복구할 수 없습니다.')
  } catch (error) {
    setFeedback('', error.message)
  } finally {
    isSubmitting.value = false
    activeSubmit.value = ''
  }
}

async function removeEntry(entry) {
  isSubmitting.value = true
  activeSubmit.value = 'entry-delete'
  setFeedback()
  try {
    await deleteEntry(entry.id)
    monthEntries.value = monthEntries.value.filter((item) => item.id !== entry.id)
    statsEntries.value = statsEntries.value.filter((item) => item.id !== entry.id)
    dashboard.value = {
      ...dashboard.value,
      recentEntries: (dashboard.value.recentEntries ?? []).filter((item) => item.id !== entry.id),
      calendar: (dashboard.value.calendar ?? []).map((day) => {
        if (day.date !== entry.entryDate) {
          return day
        }

        return {
          ...day,
          summary: {
            ...day.summary,
            entryCount: Math.max(0, Number(day.summary?.entryCount ?? 0) - 1),
            income: entry.entryType === 'INCOME'
              ? Math.max(0, Number(day.summary?.income ?? 0) - Number(entry.amount ?? 0))
              : Number(day.summary?.income ?? 0),
            expense: entry.entryType === 'EXPENSE'
              ? Math.max(0, Number(day.summary?.expense ?? 0) - Number(entry.amount ?? 0))
              : Number(day.summary?.expense ?? 0),
          },
        }
      }),
    }

    if (editingEntryId.value === entry.id) {
      resetEntryForm()
    }
    setFeedback('가계부 내역을 삭제했습니다.')
    refreshLedgerViews().catch(() => {})
  } catch (error) {
    setFeedback('', error.message)
  } finally {
    isSubmitting.value = false
    activeSubmit.value = ''
  }
}

function normalizeManagementName(value) {
  return String(value || '').trim().toLowerCase()
}

function hasDuplicateGroupName(name, entryType) {
  const normalizedName = normalizeManagementName(name)
  return categories.value.some((group) =>
    group.entryType === entryType && normalizeManagementName(group.name) === normalizedName,
  )
}

function hasDuplicateDetailName(name, groupId) {
  const group = categories.value.find((item) => String(item.id) === String(groupId))
  const normalizedName = normalizeManagementName(name)
  return Boolean(group?.details?.some((detail) => normalizeManagementName(detail.name) === normalizedName))
}

function hasDuplicatePaymentMethodName(name) {
  const normalizedName = normalizeManagementName(name)
  return paymentMethods.value.some((payment) => normalizeManagementName(payment.name) === normalizedName)
}

async function createGroup() {
  const name = groupForm.name.trim()
  if (!name) return
  if (hasDuplicateGroupName(name, groupForm.entryType)) {
    setFeedback('', '이미 있는 분류입니다.')
    return
  }
  isSubmitting.value = true
  activeSubmit.value = 'group'
  setFeedback()
  try {
    await createCategoryGroup({
      entryType: groupForm.entryType,
      name,
      displayOrder: Number(groupForm.displayOrder || 0),
    })
    groupForm.name = ''
    groupForm.displayOrder = 0
    await Promise.all([loadMetadata(), refreshLedgerViews()])
    setFeedback('카테고리 그룹을 추가했습니다.')
  } catch (error) {
    setFeedback('', error.message)
  } finally {
    isSubmitting.value = false
    activeSubmit.value = ''
  }
}

async function createDetail() {
  const name = detailForm.name.trim()
  if (!detailForm.groupId || !name) return
  if (hasDuplicateDetailName(name, detailForm.groupId)) {
    setFeedback('', '이미 있는 분류입니다.')
    return
  }
  isSubmitting.value = true
  activeSubmit.value = 'detail'
  setFeedback()
  try {
    await createCategoryDetail({
      groupId: Number(detailForm.groupId),
      name,
      displayOrder: Number(detailForm.displayOrder || 0),
    })
    detailForm.name = ''
    detailForm.displayOrder = 0
    await Promise.all([loadMetadata(), refreshLedgerViews()])
    setFeedback('세부 카테고리를 추가했습니다.')
  } catch (error) {
    setFeedback('', error.message)
  } finally {
    isSubmitting.value = false
    activeSubmit.value = ''
  }
}

async function createPayment() {
  const name = paymentForm.name.trim()
  if (!name) return
  if (hasDuplicatePaymentMethodName(name)) {
    setFeedback('', '이미 있는 결제수단입니다.')
    return
  }
  isSubmitting.value = true
  activeSubmit.value = 'payment'
  setFeedback()
  try {
    await createPaymentMethod({
      kind: paymentForm.kind,
      name,
      displayOrder: Number(paymentForm.displayOrder || 0),
    })
    paymentForm.name = ''
    paymentForm.displayOrder = 0
    await Promise.all([loadMetadata(), refreshLedgerViews(), loadAggregatePreferences()])
    setFeedback('결제수단을 추가했습니다.')
  } catch (error) {
    setFeedback('', error.message)
  } finally {
    isSubmitting.value = false
    activeSubmit.value = ''
  }
}

async function deactivateGroup(groupId) {
  isSubmitting.value = true
  activeSubmit.value = 'group'
  setFeedback()
  try {
    await deactivateCategoryGroup(groupId)
    await Promise.all([loadMetadata(), refreshLedgerViews()])
    setFeedback('카테고리 그룹을 비활성화했습니다.')
  } catch (error) {
    setFeedback('', error.message)
  } finally {
    isSubmitting.value = false
    activeSubmit.value = ''
  }
}

async function deactivateDetail(detailId) {
  isSubmitting.value = true
  activeSubmit.value = 'detail'
  setFeedback()
  try {
    await deactivateCategoryDetail(detailId)
    await Promise.all([loadMetadata(), refreshLedgerViews()])
    setFeedback('세부 카테고리를 비활성화했습니다.')
  } catch (error) {
    setFeedback('', error.message)
  } finally {
    isSubmitting.value = false
    activeSubmit.value = ''
  }
}

async function deactivatePayment(paymentId) {
  isSubmitting.value = true
  activeSubmit.value = 'payment'
  setFeedback()
  try {
    await deactivatePaymentMethod(paymentId)
    await Promise.all([loadMetadata(), refreshLedgerViews(), loadAggregatePreferences()])
    setFeedback('결제수단을 비활성화했습니다.')
  } catch (error) {
    setFeedback('', error.message)
  } finally {
    isSubmitting.value = false
    activeSubmit.value = ''
  }
}
</script>

<template>
  <div class="workspace-stack">
    <div v-if="feedback" class="feedback feedback--success feedback--actionable">
      <span>{{ feedback }}</span>
    </div>
    <div v-if="errorMessage" class="feedback feedback--error">{{ errorMessage }}</div>

    <section class="panel">
      <div class="panel__header">
        <div>
          <h2>가계부 전체 기능</h2>
          <p>예전처럼 달력형 입력, 통계, 검색, 인사이트, 분류 관리 기능을 한 페이지 안에서 다시 사용할 수 있습니다.</p>
        </div>
        <span class="panel__badge">{{ isLoading ? '불러오는 중' : '준비됨' }}</span>
      </div>

      <div class="household-anchor-toolbar">
        <div class="household-anchor-toolbar__meta">
          <strong>기준 날짜</strong>
          <span>탭을 바꿔도 같은 날짜 기준으로 달력과 통계가 유지됩니다.</span>
        </div>
        <div class="household-anchor-toolbar__actions">
          <label class="field">
            <span class="field__label">조회 기준</span>
            <input :value="householdAnchorDate" type="date" @input="handleChangeHouseholdAnchorDate($event.target.value)" />
          </label>
          <button class="button button--secondary" @click="handleChangeHouseholdAnchorDate(today)">오늘</button>
        </div>
      </div>

      <div class="scope-toggle scope-toggle--wrap">
        <button class="button" :class="{ 'button--primary': householdTab === 'dashboard' }" @click="householdTab = 'dashboard'">대시보드</button>
        <button class="button" :class="{ 'button--primary': householdTab === 'calendar' }" @click="householdTab = 'calendar'">달력 가계부</button>
        <button class="button" :class="{ 'button--primary': householdTab === 'travel-ledger' }" @click="householdTab = 'travel-ledger'">여행 가계부</button>
        <button class="button" :class="{ 'button--primary': householdTab === 'stats-overview' }" @click="householdTab = 'stats-overview'">통계 요약</button>
        <button class="button" :class="{ 'button--primary': householdTab === 'stats-search' }" @click="householdTab = 'stats-search'">검색</button>
        <button class="button" :class="{ 'button--primary': householdTab === 'stats-trash' }" @click="householdTab = 'stats-trash'">휴지통</button>
        <button class="button" :class="{ 'button--primary': householdTab === 'stats-insights' }" @click="householdTab = 'stats-insights'">인사이트</button>
        <button class="button" :class="{ 'button--primary': householdTab === 'stats-compare' }" @click="householdTab = 'stats-compare'">비교</button>
        <div ref="dataActionMenuRef" class="household-data-actions">
          <button
            class="button"
            :class="{ 'button--primary': householdTab === 'import' || dataActionMenuOpen }"
            @click.stop="toggleDataActionMenu"
          >
            데이터 입/출
          </button>
          <div v-if="dataActionMenuOpen" class="household-data-actions__menu" @click.stop>
            <button type="button" class="button button--ghost household-data-actions__menu-button" @click="openImportWorkspace">
              엑셀 가져오기
            </button>
            <div class="household-data-actions__divider"></div>
            <label class="field">
              <span class="field__label">CSV 범위</span>
              <select v-model="csvExportControls.preset">
                <option v-for="option in csvExportOptions" :key="option.value" :value="option.value">
                  {{ option.label }}
                </option>
              </select>
            </label>
            <label v-if="csvExportControls.preset === 'CUSTOM'" class="field">
              <span class="field__label">시작일</span>
              <input v-model="csvExportControls.customFrom" type="date" />
            </label>
            <label v-if="csvExportControls.preset === 'CUSTOM'" class="field">
              <span class="field__label">종료일</span>
              <input v-model="csvExportControls.customTo" type="date" />
            </label>
            <button class="button button--secondary household-data-actions__menu-button" :disabled="isSubmitting" @click="exportEntriesToCsv">
              {{ isSubmitting && activeSubmit === 'export-csv' ? 'CSV 저장 중...' : `CSV로 저장하기 (${csvExportLabel})` }}
            </button>
          </div>
        </div>
        <button class="button" :class="{ 'button--primary': householdTab === 'management' }" @click="householdTab = 'management'">분류 관리</button>
      </div>
    </section>

    <PaletteContainer
      v-if="householdTab === 'dashboard'"
      :current-user="props.currentUser"
      :dashboard="dashboard"
      :calendar-weeks="calendarWeeks"
      :month-label="monthLabel"
      :anchor-date="calendarAnchorDate"
      :entries="sortedMonthEntries"
      :is-loading="isLoading"
    />

    <CalendarWorkspace
      ref="calendarWorkspaceRef"
      v-else-if="householdTab === 'calendar'"
      :current-user="props.currentUser"
      :quick-stats="quickStats"
      :month-label="monthLabel"
      :anchor-date="calendarAnchorDate"
      :weekday-labels="weekdayLabels"
      :calendar-weeks="calendarWeeks"
      :entries="sortedMonthEntries"
      :entry-form="entryForm"
      :entry-date-limit="entryTravelPlanDateLimit"
      :is-editing-entry="isEditingEntry"
      :is-submitting="isSubmitting"
      :active-submit="activeSubmit"
      :available-groups="availableGroups"
      :category-groups="categories"
      :available-details="availableDetails"
      :payment-methods="paymentMethods"
      :entry-suggestions="entrySuggestions"
      :aggregate-widget-configs="aggregateWidgetConfigs"
      :aggregate-settings-ready="aggregateSettingsReady"
      :aggregate-settings-saving="isSubmitting && activeSubmit === 'aggregate-settings'"
      :amount-input="amountInput"
      :amount-preview="amountPreview"
      :is-time-enabled="isEntryTimeEnabled"
      :quick-amount-buttons="quickAmountButtons"
      :foreign-currency-options="foreignCurrencyOptions"
      :foreign-exchange-state="foreignExchangeState"
      :receipt-ocr="receiptOcr"
      :format-amount-shortcut="formatAmountShortcut"
      :format-currency="formatCurrency"
      :format-currency-by-code="formatCurrencyByCode"
      :format-short-date="formatShortDate"
      :format-time="formatTime"
      :can-undo-last-entry-action="canUndoLastEntryAction"
      :undo-entry-action-label="undoEntryActionLabel"
      @open-receipt-ocr="openReceiptOcrModal"
      @close-receipt-ocr="closeReceiptOcrModal"
      @set-receipt-document-type="setReceiptOcrDocumentType"
      @update:amount-input="handleAmountInput"
      @update:time-enabled="updateTimeEnabled"
      @fill-amount="fillAmount"
      @add-amount="addAmount"
      @analyze-receipt="analyzeReceiptImage"
      @update-receipt-review-entry="updateReceiptOcrReviewEntry"
      @remove-receipt-analysis="removeReceiptOcrItem"
      @apply-receipt-suggestion="applyReceiptOcrSuggestion"
      @clear-receipt-analysis="clearReceiptOcr"
      @submit-entry="submitEntry"
      @undo-entry-action="undoLastEntryAction"
      @edit-entry="fillEntryForm"
      @delete-entry="removeEntry"
      @apply-entry-suggestion="applyEntrySuggestion"
      @apply-title-suggestion="applyEntryTitleSuggestion"
      @change-anchor-month="handleChangeCalendarMonth"
      @save-aggregate-widget-configs="updateAggregatePreferences"
    />

    <HouseholdTravelLedgerWorkspace
      v-else-if="householdTab === 'travel-ledger'"
      :entries="statsEntries"
      :stats-controls="statsControls"
      :preset-options="presetOptions"
      :stats-range-label="statsRangeLabel"
      :format-currency="formatCurrency"
      :format-short-date="formatShortDate"
      :format-time="formatTime"
      :travel-plans="householdTravelPlans"
      :selected-travel-plan-id="selectedHouseholdTravelPlanId"
      :travel-plan-form="householdTravelPlanForm"
      :travel-plan-loading="isHouseholdTravelPlanLoading"
      :travel-plan-submitting="isHouseholdTravelPlanSubmitting"
      :travel-plan-error="householdTravelPlanError"
      :linking-travel-entry-id="linkingTravelEntryId"
      @select-travel-plan="selectHouseholdTravelPlan"
      @create-travel-plan="createHouseholdTravelPlan"
      @reset-travel-plan-form="resetHouseholdTravelPlanForm"
      @start-travel-entry="startTravelLedgerEntry"
      @open-travel-search="openTravelLedgerSearch"
      @view-travel-entry-date="viewTravelLedgerEntryDate"
      @edit-travel-entry="editTravelLedgerEntry"
      @link-travel-entry="linkTravelLedgerEntry"
    />

    <StatisticsWorkspace
      v-else-if="householdTab.startsWith('stats-')"
      :route="householdTab"
      :stats-controls="statsControls"
      :search-form="searchForm"
      :preset-options="presetOptions"
      :stats-cards="statsCards"
      :stats-range-label="statsRangeLabel"
      :comparison-rows="comparisonRows"
      :comparison-badge="comparisonBadge"
      :search-results="searchResults"
      :search-page-info="searchPageInfo"
      :search-summary="searchSummary"
      :trash-results="trashResults"
      :trash-page-info="trashPageInfo"
      :insights="insights"
      :past-comparisons="pastComparisons"
      :expense-breakdown="expenseBreakdown"
      :payment-breakdown="paymentBreakdown"
      :payment-methods="paymentMethods"
      :categories="categories"
      :format-currency="formatCurrency"
      :format-short-date="formatShortDate"
      :format-full-date="formatFullDate"
      :format-date-range="formatDateRange"
      :format-time="formatTime"
      @change-search-page="loadSearchResults"
      @change-trash-page="loadTrashResults"
      @move-search-entry="moveEntryFromSearch"
      @save-search-entry="saveEntryFromSearch"
      @bulk-update-search-entries="bulkUpdateSearchEntries"
      @delete-search-entry="deleteEntryFromSearch"
      @restore-trash-entry="restoreEntryFromTrash"
      @empty-trash="emptyTrash"
    />

    <LedgerImportWorkspace
      v-else-if="householdTab === 'import'"
      @imported="handleImported"
    />

    <ManagementWorkspace
      v-else
      :categories="categories"
      :payment-methods="paymentMethods"
      :group-form="groupForm"
      :detail-form="detailForm"
      :payment-form="paymentForm"
      :is-submitting="isSubmitting"
      :active-submit="activeSubmit"
      @create-group="createGroup"
      @create-detail="createDetail"
      @create-payment="createPayment"
      @deactivate-group="deactivateGroup"
      @deactivate-detail="deactivateDetail"
      @deactivate-payment="deactivatePayment"
    />

    <div class="household-floating-tools" aria-label="가계부 빠른 이동">
      <button class="household-floating-tools__button" type="button" title="맨 위로 가기" @click="scrollHouseholdToTop">
        위
      </button>
      <button class="household-floating-tools__button" type="button" title="맨 아래로 가기" @click="scrollHouseholdToBottom">
        아래
      </button>
      <button class="household-floating-tools__button household-floating-tools__button--accent" type="button" title="수정 이력 확인하기" @click="openLedgerChangeHistoryModal">
        이력
      </button>
    </div>

    <div v-if="ledgerChangeHistory.isOpen" class="travel-modal ledger-history-modal" @click.self="closeLedgerChangeHistoryModal">
      <section class="travel-modal__dialog ledger-history-modal__dialog" role="dialog" aria-modal="true" aria-labelledby="ledger-history-title">
        <div class="travel-modal__header ledger-history-modal__header">
          <div>
            <span class="ledger-history-modal__eyebrow">CHANGE HISTORY</span>
            <h2 id="ledger-history-title">수정 이력 확인</h2>
            <p>검색 화면과 일괄 변경에서 수정된 거래를 확인하고 변경 전 상태로 복구할 수 있습니다.</p>
          </div>
          <button class="button button--ghost" type="button" @click="closeLedgerChangeHistoryModal">닫기</button>
        </div>

        <div v-if="ledgerChangeHistory.error" class="feedback feedback--error">{{ ledgerChangeHistory.error }}</div>

        <div class="ledger-history-modal__body">
          <aside class="ledger-history-list" aria-label="수정 이력 목록">
            <div class="ledger-history-list__toolbar">
              <strong>{{ ledgerChangeHistory.totalElements }}건</strong>
              <span>{{ ledgerChangeHistory.page + 1 }} / {{ ledgerChangeHistoryPageLabel }}</span>
            </div>

            <div v-if="ledgerChangeHistory.isLoading" class="ledger-history-empty">수정 이력을 불러오는 중입니다.</div>
            <div v-else-if="!ledgerChangeHistory.content.length" class="ledger-history-empty">아직 저장된 수정 이력이 없습니다.</div>
            <template v-else>
              <button
                v-for="history in ledgerChangeHistory.content"
                :key="history.id"
                :class="['ledger-history-list__item', { 'is-active': ledgerChangeHistory.selected?.id === history.id }]"
                type="button"
                @click="selectLedgerChangeHistory(history)"
              >
                <span class="ledger-history-list__meta">
                  <strong>{{ history.actionLabel }}</strong>
                  <small>{{ formatLedgerChangeDate(history.createdAt) }}</small>
                </span>
                <span class="ledger-history-list__summary">{{ history.summary }}</span>
                <span class="ledger-history-list__count">{{ history.entryCount }}건</span>
              </button>
            </template>

            <div class="ledger-history-list__pager">
              <button class="button button--ghost" type="button" :disabled="ledgerChangeHistory.page <= 0 || ledgerChangeHistory.isLoading" @click="loadLedgerChangeHistories(ledgerChangeHistory.page - 1)">
                이전
              </button>
              <button class="button button--ghost" type="button" :disabled="ledgerChangeHistory.page + 1 >= ledgerChangeHistoryPageLabel || ledgerChangeHistory.isLoading" @click="loadLedgerChangeHistories(ledgerChangeHistory.page + 1)">
                다음
              </button>
            </div>
          </aside>

          <section class="ledger-history-detail" aria-label="수정 이력 상세">
            <div v-if="ledgerChangeHistory.isDetailLoading" class="ledger-history-empty">상세 변경 내용을 불러오는 중입니다.</div>
            <div v-else-if="!ledgerChangeHistory.selected" class="ledger-history-empty">왼쪽에서 확인할 이력을 선택하세요.</div>
            <template v-else>
              <div class="ledger-history-detail__header">
                <div>
                  <strong>{{ ledgerChangeHistory.selected.summary }}</strong>
                  <span>{{ formatLedgerChangeDate(ledgerChangeHistory.selected.createdAt) }} · {{ ledgerChangeHistory.selected.entryCount }}건</span>
                </div>
                <button class="button button--primary" type="button" :disabled="ledgerChangeHistory.isRestoring" @click="restoreLedgerChangeHistoryPoint(ledgerChangeHistory.selected)">
                  {{ ledgerChangeHistory.isRestoring ? '복구 중...' : '이 변경 전으로 돌아가기' }}
                </button>
              </div>

              <div class="ledger-history-detail__changes">
                <article v-for="change in ledgerChangeHistory.selected.changes" :key="change.entryId" class="ledger-history-change-card">
                  <div class="ledger-history-change-card__title">
                    <strong>{{ change.afterTitle || change.beforeTitle }}</strong>
                    <small>#{{ change.entryId }}</small>
                  </div>
                  <dl>
                    <div v-for="field in change.fields" :key="`${change.entryId}-${field.field}`">
                      <dt>{{ field.field }}</dt>
                      <dd>
                        <span>{{ formatLedgerChangeFieldValue(field.beforeValue) }}</span>
                        <strong>→</strong>
                        <span>{{ formatLedgerChangeFieldValue(field.afterValue) }}</span>
                      </dd>
                    </div>
                  </dl>
                </article>
              </div>
            </template>
          </section>
        </div>
      </section>
    </div>
  </div>
</template>
