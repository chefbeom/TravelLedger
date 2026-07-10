<script setup>
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import {
  activateCategoryDetail,
  activateCategoryGroup,
  activatePaymentMethod,
  analyzeLedgerReceipt,
  buildLedgerImageAnalysisImageUrl,
  cancelLedgerImageAnalysisHistory,
  cancelLedgerImageAnalysisClientRequest,
  analyzeLedgerSpending,
  bulkUpdateEntries,
  createCategoryDetail,
  createCategoryGroup,
  createEntry,
  createPaymentMethod,
  createTravelPlan,
  deleteCategoryDetailPermanently,
  deleteCategoryGroupPermanently,
  deletePaymentMethodPermanently,
  deactivateCategoryDetail,
  deactivateCategoryGroup,
  deactivatePaymentMethod,
  deleteEntry,
  deleteLedgerImageAnalysisHistory,
  downloadLedgerCsv,
  emptyDeletedEntries,
  fetchCategories,
  fetchCategoryBreakdown,
  fetchCategoryDetailUsage,
  fetchCategoryGroupUsage,
  fetchCompare,
  fetchDashboard,
  fetchDeletedEntryPage,
  fetchEntryDateRange,
  fetchLedgerEntryHistories,
  fetchLedgerEntryHistory,
  fetchLedgerExchangeRate,
  fetchLedgerAiAnalysisHistory,
  fetchLedgerAiAnalysisHistories,
  fetchLedgerImageAnalysisHistories,
  fetchLedgerImageAnalysisHistory,
  markLedgerImageAnalysisEntryApproved,
  fetchLedgerAiAnalysisStatus,
  fetchLatestLedgerAiAnalysis,
  deleteLedgerAiAnalysisHistory,
  fetchEntrySearchPage,
  fetchEntries,
  fetchHouseholdAggregatePreferences,
  fetchTravelPlans,
  fetchOverview,
  fetchPaymentBreakdown,
  fetchPaymentMethodUsage,
  rerunLedgerAiAnalysis,
  rerunLedgerImageAnalysisHistory,
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
import PaletteContainer from '../features/palette/components/PaletteContainer.vue'
import CalendarWorkspace from './CalendarWorkspace.vue'

const HouseholdTravelLedgerWorkspace = defineAsyncComponent(() => import('./HouseholdTravelLedgerWorkspace.vue'))
const LedgerImportWorkspace = defineAsyncComponent(() => import('./LedgerImportWorkspace.vue'))
const ManagementWorkspace = defineAsyncComponent(() => import('./ManagementWorkspace.vue'))
const StatisticsWorkspace = defineAsyncComponent(() => import('./StatisticsWorkspace.vue'))

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

const emit = defineEmits(['open-travel-record-location'])

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
const FOREIGN_EXCHANGE_DEBOUNCE_MS = 180
const SEARCH_PAGE_SIZE = 100
const SEARCH_OTHER_FILTER_VALUE = '__OTHER__'
const AI_HISTORY_PAGE_SIZE = 8
const RECEIPT_OCR_PROMPT_RULES_KEY = 'calen-household-receipt-ocr-prompt-rules:v1'
const RECEIPT_OCR_REQUEST_PROMPT_LAST_KEY = 'calen-household-receipt-ocr-request-prompt-last:v1'
const RECEIPT_OCR_REQUEST_PROMPT_HISTORY_KEY = 'calen-household-receipt-ocr-request-prompt-history:v1'
const RECEIPT_OCR_APPLIED_ENTRY_MARKERS_KEY = 'calen-household-receipt-ocr-applied-entry-markers:v1'
const RECEIPT_OCR_REQUEST_PROMPT_HISTORY_LIMIT = 5
const csvExportOptions = [
  { value: 'ALL', label: '전체 데이터' },
  { value: 'LAST_6_MONTHS', label: '최근 6개월' },
  { value: 'LAST_1_YEAR', label: '최근 1년' },
  { value: 'LAST_3_YEARS', label: '최근 3년' },
  { value: 'CURRENT_VIEW', label: '현재 조회 범위' },
  { value: 'CUSTOM', label: '직접 선택' },
]
const householdAnalysisRouteKeys = ['stats-overview', 'stats-insights', 'stats-compare', 'stats-ai']
const householdDirectTabKeys = [
  'dashboard',
  'calendar',
  'travel-ledger',
  'ledger-analysis',
  'stats-search',
  'stats-trash',
  'import',
  'management',
]
const householdAnalysisTabs = [
  { key: 'stats-overview', label: '통계 요약' },
  { key: 'stats-insights', label: '인사이트' },
  { key: 'stats-compare', label: '비교' },
  { key: 'stats-ai', label: 'AI 분석' },
]
function loadReceiptOcrPromptRules() {
  if (typeof window === 'undefined') {
    return ''
  }
  try {
    return window.localStorage.getItem(RECEIPT_OCR_PROMPT_RULES_KEY) || ''
  } catch (error) {
    console.warn('Failed to load receipt OCR prompt rules', error)
    return ''
  }
}

function saveReceiptOcrPromptRules(value) {
  if (typeof window === 'undefined') {
    return
  }
  try {
    window.localStorage.setItem(RECEIPT_OCR_PROMPT_RULES_KEY, value || '')
  } catch (error) {
    console.warn('Failed to save receipt OCR prompt rules', error)
  }
}

function loadReceiptOcrRequestPromptLast() {
  if (typeof window === 'undefined') {
    return ''
  }
  try {
    return window.localStorage.getItem(RECEIPT_OCR_REQUEST_PROMPT_LAST_KEY) || ''
  } catch (error) {
    console.warn('Failed to load receipt OCR request prompt', error)
    return ''
  }
}

function saveReceiptOcrRequestPromptLast(value) {
  if (typeof window === 'undefined') {
    return
  }
  try {
    window.localStorage.setItem(RECEIPT_OCR_REQUEST_PROMPT_LAST_KEY, value || '')
  } catch (error) {
    console.warn('Failed to save receipt OCR request prompt', error)
  }
}

function loadReceiptOcrRequestPromptHistory() {
  if (typeof window === 'undefined') {
    return []
  }
  try {
    const parsed = JSON.parse(window.localStorage.getItem(RECEIPT_OCR_REQUEST_PROMPT_HISTORY_KEY) || '[]')
    return Array.isArray(parsed)
      ? parsed.map((item) => normalizeReceiptPrompt(item)).filter(Boolean).slice(0, RECEIPT_OCR_REQUEST_PROMPT_HISTORY_LIMIT)
      : []
  } catch (error) {
    console.warn('Failed to load receipt OCR request prompt history', error)
    return []
  }
}

function saveReceiptOcrRequestPromptHistory(items) {
  if (typeof window === 'undefined') {
    return
  }
  try {
    window.localStorage.setItem(RECEIPT_OCR_REQUEST_PROMPT_HISTORY_KEY, JSON.stringify(items || []))
  } catch (error) {
    console.warn('Failed to save receipt OCR request prompt history', error)
  }
}
function normalizeReceiptPrompt(value, maxLength = 1800) {
  const normalized = String(value || '').replace(/\u0000/g, ' ').trim()
  return normalized.length > maxLength ? normalized.slice(0, maxLength) : normalized
}

function normalizeReceiptOcrAppliedEntryMarkers(items) {
  if (!Array.isArray(items)) {
    return []
  }
  const seen = new Set()
  return items
    .map((item) => ({
      analysisId: String(item?.analysisId || '').trim(),
      entryIndex: Number(item?.entryIndex),
      appliedAt: String(item?.appliedAt || '').trim(),
    }))
    .filter((item) => item.analysisId && Number.isInteger(item.entryIndex) && item.entryIndex >= 0)
    .filter((item) => {
      const key = `${item.analysisId}:${item.entryIndex}`
      if (seen.has(key)) {
        return false
      }
      seen.add(key)
      return true
    })
}

function loadReceiptOcrAppliedEntryMarkers() {
  if (typeof window === 'undefined') {
    return []
  }
  try {
    return normalizeReceiptOcrAppliedEntryMarkers(JSON.parse(window.localStorage.getItem(RECEIPT_OCR_APPLIED_ENTRY_MARKERS_KEY) || '[]'))
  } catch (error) {
    console.warn('Failed to load receipt OCR applied entry markers', error)
    return []
  }
}

function saveReceiptOcrAppliedEntryMarkers(items) {
  if (typeof window === 'undefined') {
    return
  }
  try {
    window.localStorage.setItem(RECEIPT_OCR_APPLIED_ENTRY_MARKERS_KEY, JSON.stringify(normalizeReceiptOcrAppliedEntryMarkers(items).slice(0, 500)))
  } catch (error) {
    console.warn('Failed to save receipt OCR applied entry markers', error)
  }
}
const isLoading = ref(false)
const isSubmitting = ref(false)
const activeSubmit = ref('')
const feedback = ref('')
const errorMessage = ref('')
const undoableEntryAction = ref(null)
const householdTab = ref('dashboard')
const householdAnalysisRoute = ref('stats-overview')
const householdAnchorDate = ref(today)
const calendarAnchorDate = householdAnchorDate
const calendarReady = ref(false)
const statsReady = ref(false)
const aiAnalysisControls = reactive({
  mode: 'PERIOD',
  periodType: 'MONTH',
  comparisonPreset: 'CURRENT_MONTH_VS_PREVIOUS_MONTH',
  anchorDate: today,
  from: today,
  to: today,
  compareFrom: '',
  compareTo: '',
  focusEnabled: false,
  focusPreset: 'SPENDING_PATTERN',
  focusCustomText: '',
})
const aiAnalysisHistoryFilters = reactive({
  mode: '',
  periodType: '',
  createdFrom: '',
  createdTo: '',
  comparisonOnly: '',
})
const aiAnalysisStatus = ref(null)
const aiAnalysis = ref(null)
const aiAnalysisModalRequestKey = ref(0)
const aiAnalysisLoading = ref(false)
const aiAnalysisError = ref('')
const aiAnalysisStale = ref(false)
const aiAnalysisHistoryPage = ref({
  content: [],
  page: 0,
  size: AI_HISTORY_PAGE_SIZE,
  totalElements: 0,
  totalPages: 0,
})
const aiAnalysisHistoryLoading = ref(false)
const aiAnalysisHistoryError = ref('')
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
const calendarAggregateEntries = ref([])
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
const managementCategories = ref([])
const managementPaymentMethods = ref([])
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
  activeView: '',
  documentType: 'AUTO',
  requestPromptEnabled: false,
  requestPrompt: loadReceiptOcrRequestPromptLast(),
  requestPromptHistory: loadReceiptOcrRequestPromptHistory(),
  useExistingEntryStyle: false,
  rerunPromptEnabled: false,
  rerunPrompt: '',
  promptRulesEnabled: true,
  promptRules: loadReceiptOcrPromptRules(),
  isAnalyzing: false,
  pendingCount: 0,
  batchTotalCount: 0,
  batchCompletedCount: 0,
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
  historyItems: [],
  historyPage: 0,
  historySize: 8,
  historyTotalPages: 0,
  historyTotalElements: 0,
  isHistoryLoading: false,
  historyError: '',
  historyDetailAnalysisId: '',
  isHistoryDetailLoading: false,
  lastAppliedAnalysisId: null,
  lastAppliedReviewItemId: null,
  lastAppliedReviewEntryIndex: null,
  lastAppliedMode: '',
  lastAppliedSnapshot: null,
  appliedEntryMarkers: loadReceiptOcrAppliedEntryMarkers(),
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
  basisDateTime: '',
})
let feedbackTimerId = null
let searchRequestTimerId = null
let titleSuggestionSearchTimerId = null
let titleSuggestionSearchRequestId = 0
let receiptOcrItemSequence = 0
let receiptOcrHistoryRefreshTimerId = null
const RECEIPT_OCR_HISTORY_POLL_INTERVAL_MS = 2500
const RECEIPT_OCR_HISTORY_POLL_ATTEMPTS = 240
const RECEIPT_OCR_HISTORY_AUTO_REFRESH_INTERVAL_MS = 5000
let foreignExchangeTimerId = null
let foreignExchangeRequestId = 0
let foreignExchangeLoadedKey = ''
let foreignExchangePendingKey = ''

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
  keywordSpaceAnd: true,
  entryType: '',
  paymentMethodId: '',
  categoryGroupId: '',
  categoryDetailId: '',
  minAmount: '',
  maxAmount: '',
  sortBy: 'DATE_DESC',
})
const searchKeywordDraft = ref('')

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

const classificationDeleteModal = reactive({
  isOpen: false,
  isLoading: false,
  isSubmitting: false,
  type: '',
  target: null,
  usage: null,
  error: '',
  replacementCategoryGroupId: '',
  replacementCategoryDetailId: '',
  replacementPaymentMethodId: '',
})

const presetOptions = getPresetOptions()
const weekdayLabels = getWeekdayLabels()

const availableGroups = computed(() => categories.value.filter((group) => group.entryType === entryForm.entryType))
const availableDetails = computed(() => {
  const group = categories.value.find((item) => String(item.id) === String(entryForm.categoryGroupId))
  return group?.details ?? []
})
const classificationDeleteTypeLabel = computed(() => {
  const labels = {
    group: '대분류',
    detail: '소분류',
    payment: '결제수단',
  }
  return labels[classificationDeleteModal.type] || '분류'
})
const classificationDeleteTargetLabel = computed(() => {
  const target = classificationDeleteModal.target
  if (!target) {
    return ''
  }
  if (classificationDeleteModal.type === 'detail') {
    return `${target.groupName || '-'} / ${target.name}`
  }
  if (classificationDeleteModal.type === 'group') {
    return `${target.entryType === 'INCOME' ? '수입' : '지출'} / ${target.name}`
  }
  return target.name || ''
})
const classificationDeleteReplacementGroups = computed(() => {
  const target = classificationDeleteModal.target
  if (!target || !['group'].includes(classificationDeleteModal.type)) {
    return []
  }
  return categories.value.filter((group) =>
    group.entryType === target.entryType && String(group.id) !== String(target.id),
  )
})
const classificationDeleteReplacementDetails = computed(() => {
  const target = classificationDeleteModal.target
  if (!target) {
    return []
  }
  if (classificationDeleteModal.type === 'group') {
    const group = categories.value.find((item) =>
      String(item.id) === String(classificationDeleteModal.replacementCategoryGroupId),
    )
    return group?.details ?? []
  }
  if (classificationDeleteModal.type === 'detail') {
    const group = categories.value.find((item) => String(item.id) === String(target.groupId))
    return (group?.details ?? []).filter((detail) => String(detail.id) !== String(target.id))
  }
  return []
})
const classificationDeleteReplacementPayments = computed(() => {
  if (classificationDeleteModal.type !== 'payment') {
    return []
  }
  const targetId = classificationDeleteModal.target?.id
  return paymentMethods.value.filter((payment) => String(payment.id) !== String(targetId))
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
function isHouseholdAnalysisRoute(tab) {
  return householdAnalysisRouteKeys.includes(tab)
}

function resolveHouseholdStatisticsRoute(tab = householdTab.value) {
  return tab === 'ledger-analysis' ? householdAnalysisRoute.value : tab
}

function setHouseholdTab(tab) {
  const nextTab = String(tab || '').trim()
  if (isHouseholdAnalysisRoute(nextTab)) {
    householdAnalysisRoute.value = nextTab
    householdTab.value = 'ledger-analysis'
    return
  }
  if (nextTab === 'ledger-analysis') {
    householdTab.value = 'ledger-analysis'
    return
  }
  householdTab.value = householdDirectTabKeys.includes(nextTab) ? nextTab : 'dashboard'
}

function setHouseholdAnalysisRoute(tab) {
  if (!isHouseholdAnalysisRoute(tab)) {
    return
  }
  householdAnalysisRoute.value = tab
  householdTab.value = 'ledger-analysis'
  if (tab === 'stats-ai') {
    aiAnalysisModalRequestKey.value += 1
  }
}
const currentViewCsvRange = computed(() => (
  householdTab.value === 'ledger-analysis' || householdTab.value.startsWith('stats-')
    ? statsRange.value
    : getMonthRange(calendarAnchorDate.value)
))
const statisticsWorkspaceRoute = computed(() => resolveHouseholdStatisticsRoute())
const isStatisticsWorkspaceVisible = computed(() => householdTab.value === 'ledger-analysis' || householdTab.value === 'stats-search' || householdTab.value === 'stats-trash')
const comparisonAnchorDate = computed(() => {
  if (statsControls.preset === 'ALL') {
    return entryDateRange.value.latestDate || statsControls.anchorDate
  }
  return statsRange.value.to || statsControls.anchorDate
})
const csvExportRange = computed(() => resolveCsvExportRange(csvExportControls.preset))
const csvExportLabel = computed(() => csvExportRange.value.label)

function shouldLoadStatisticsForTab(tab = householdTab.value) {
  const route = resolveHouseholdStatisticsRoute(tab)
  return tab === 'travel-ledger'
    || route === 'stats-overview'
    || route === 'stats-insights'
    || route === 'stats-compare'
}

function shouldLoadOverviewForTab(tab = householdTab.value) {
  return resolveHouseholdStatisticsRoute(tab) === 'stats-overview'
}

function shouldLoadBreakdownsForTab(tab = householdTab.value) {
  return resolveHouseholdStatisticsRoute(tab) === 'stats-overview'
}

function shouldLoadComparisonRowsForTab(tab = householdTab.value) {
  return resolveHouseholdStatisticsRoute(tab) === 'stats-overview'
}

function shouldLoadStatisticsEntriesForTab(tab = householdTab.value) {
  const route = resolveHouseholdStatisticsRoute(tab)
  return route === 'stats-insights' || tab === 'travel-ledger'
}

function shouldLoadPastComparisonsForTab(tab = householdTab.value) {
  return resolveHouseholdStatisticsRoute(tab) === 'stats-compare'
}

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

function resolveDefaultDetailId(groupId, latestEntry, entryType = entryForm.entryType) {
  const details = getDetailsForGroupId(groupId, entryType)
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

function normalizeCategoryLookupName(value) {
  return String(value || '')
    .trim()
    .replace(/[\s/_:：·.,()\[\]-]+/g, '')
    .toLowerCase()
}

function findCategoryGroupForSuggestion(entryType, groupId, groupName) {
  const groups = getGroupsForType(entryType)
  const id = groupId != null && groupId !== '' ? String(groupId) : ''
  if (id) {
    const byId = groups.find((group) => String(group.id) === id)
    if (byId) return byId
  }
  const normalizedName = normalizeCategoryLookupName(groupName)
  if (normalizedName) {
    return groups.find((group) => normalizeCategoryLookupName(group.name) === normalizedName) || null
  }
  return null
}

function findCategoryDetailForSuggestion(groupId, entryType, detailId, detailName) {
  const details = getDetailsForGroupId(groupId, entryType)
  const id = detailId != null && detailId !== '' ? String(detailId) : ''
  if (id) {
    const byId = details.find((detail) => String(detail.id) === id)
    if (byId) return byId
  }
  const normalizedName = normalizeCategoryLookupName(detailName)
  if (normalizedName) {
    return details.find((detail) => normalizeCategoryLookupName(detail.name) === normalizedName) || null
  }
  return null
}

function isUncategorizedCategoryName(value) {
  return normalizeCategoryLookupName(value) === normalizeCategoryLookupName('\uBBF8\uBD84\uB958')
}

function findCategoryGroupByCandidateNames(entryType, names = []) {
  const normalizedNames = names.map(normalizeCategoryLookupName).filter(Boolean)
  return getGroupsForType(entryType)
    .filter((group) => !isUncategorizedCategoryName(group.name))
    .find((group) => normalizedNames.includes(normalizeCategoryLookupName(group.name))) || null
}

function findCategoryDetailByCandidateNames(groupId, entryType, names = []) {
  const normalizedNames = names.map(normalizeCategoryLookupName).filter(Boolean)
  return getDetailsForGroupId(groupId, entryType)
    .find((detail) => normalizedNames.includes(normalizeCategoryLookupName(detail.name))) || null
}

function findCategoryByDetailCandidateNames(entryType, names = []) {
  const groups = getGroupsForType(entryType).filter((group) => !isUncategorizedCategoryName(group.name))
  for (const group of groups) {
    const detail = findCategoryDetailByCandidateNames(group.id, entryType, names)
    if (detail) {
      return { group, detail }
    }
  }
  return { group: null, detail: null }
}

function resolveReceiptCategoryHint(entryType, suggestion = {}, context = {}) {
  const text = collectReceiptOcrTextCandidates(suggestion, context).join(' ').toLowerCase()
  const rules = [
    {
      keywords: ['\uC815\uAE30\uAD6C\uB3C5', '\uAD6C\uB3C5', '\uBA64\uBC84\uC2ED', 'subscription'],
      groupNames: ['\uAD6C\uB3C5', '\uCDE8\uBBF8', '\uBB38\uD654\uC0DD\uD65C'],
      detailNames: ['\uAD6C\uB3C5', '\uC815\uAE30\uAD6C\uB3C5', '\uC815\uAE30\uACB0\uC81C', '\uBA64\uBC84\uC2ED', '\uCF58\uD150\uCE20'],
    },
    {
      keywords: ['\uC6F9\uD230', '\uC2DC\uB9AC\uC988', '\uCFE0\uD0A4'],
      groupNames: ['\uCDE8\uBBF8', '\uBB38\uD654\uC0DD\uD65C'],
      detailNames: ['\uCF58\uD150\uCE20', '\uC6F9\uD230', '\uCDE8\uBBF8'],
    },
    {
      keywords: ['\uAC8C\uC784', 'game'],
      groupNames: ['\uAC8C\uC784', '\uCDE8\uBBF8'],
      detailNames: ['\uAC8C\uC784', '\uCF58\uD150\uCE20', '\uCDE8\uBBF8'],
    },
  ]
  for (const rule of rules) {
    if (!rule.keywords.some((keyword) => text.includes(keyword.toLowerCase()))) {
      continue
    }
    const group = findCategoryGroupByCandidateNames(entryType, rule.groupNames)
    if (group) {
      return {
        group,
        detail: findCategoryDetailByCandidateNames(group.id, entryType, rule.detailNames),
      }
    }
    const byDetail = findCategoryByDetailCandidateNames(entryType, rule.detailNames)
    if (byDetail.group) {
      return byDetail
    }
  }
  return { group: null, detail: null }
}
function resolveReceiptSuggestionCategory(suggestion = {}, entryType = 'EXPENSE', context = {}) {
  const latestEntry = getLatestEntryForType(entryType)
  const suggestedGroup = findCategoryGroupForSuggestion(entryType, suggestion.categoryGroupId, suggestion.categoryGroupName)
  const hintedCategory = resolveReceiptCategoryHint(entryType, suggestion, context)
  const matchedGroup = suggestedGroup && !isUncategorizedCategoryName(suggestedGroup.name)
    ? suggestedGroup
    : hintedCategory.group || suggestedGroup
  const categoryGroupId = matchedGroup
    ? String(matchedGroup.id)
    : resolveDefaultGroupId(entryType, latestEntry)
  const suggestedDetail = categoryGroupId
    ? findCategoryDetailForSuggestion(categoryGroupId, entryType, suggestion.categoryDetailId, suggestion.categoryDetailName)
    : null
  const hintedDetail = hintedCategory.group && String(hintedCategory.group.id) === String(categoryGroupId)
    ? hintedCategory.detail
    : null
  const matchedDetail = suggestedDetail || hintedDetail
  const categoryDetailId = matchedDetail
    ? String(matchedDetail.id)
    : resolveDefaultDetailId(categoryGroupId, latestEntry, entryType)
  const group = categoryGroupId
    ? getGroupsForType(entryType).find((item) => String(item.id) === String(categoryGroupId))
    : null
  const detail = categoryDetailId
    ? getDetailsForGroupId(categoryGroupId, entryType).find((item) => String(item.id) === String(categoryDetailId))
    : null
  return {
    categoryGroupId,
    categoryGroupName: group?.name || suggestion.categoryGroupName || '',
    categoryDetailId,
    categoryDetailName: detail?.name || suggestion.categoryDetailName || '',
  }
}function resolveDefaultPaymentMethodId(latestEntry) {
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
  () => classificationDeleteModal.replacementCategoryGroupId,
  () => {
    classificationDeleteModal.replacementCategoryDetailId = ''
  },
)

watch(
  () => [entryForm.currencyMode, entryForm.foreignCurrencyCode, entryForm.entryDate, entryForm.entryTime, isEntryTimeEnabled.value],
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
    if (statsReady.value && shouldLoadStatisticsForTab()) {
      await loadStatisticsData()
    }
  },
)

watch(
  householdTab,
  async (value, previousValue) => {
    if (isHouseholdAnalysisRoute(value)) {
      setHouseholdTab(value)
      return
    }
    if (!statsReady.value || value === previousValue) {
      return
    }

    if (value === 'ledger-analysis') {
      await loadHouseholdAnalysisRouteData()
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
  householdAnalysisRoute,
  async (value, previousValue) => {
    if (!statsReady.value || householdTab.value !== 'ledger-analysis' || value === previousValue) {
      return
    }
    await loadHouseholdAnalysisRouteData(value)
  },
)

watch(
  () => props.initialTab,
  (value) => {
    if (value) {
      setHouseholdTab(value)
    }
  },
  { immediate: true },
)

watch(
  () => [
    householdTab.value,
    statsRange.value.from,
    statsRange.value.to,
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
    await loadAggregatePreferences()
    await loadCalendarData()
    if (shouldLoadStatisticsForTab()) {
      await loadStatisticsData()
    }
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
  receiptOcr.items.forEach((item) => {
    item.abortController?.abort()
    revokeReceiptOcrItemPreview(item)
  })
  if (feedbackTimerId) {
    window.clearTimeout(feedbackTimerId)
  }
  if (searchRequestTimerId) {
    window.clearTimeout(searchRequestTimerId)
  }
  if (titleSuggestionSearchTimerId) {
    window.clearTimeout(titleSuggestionSearchTimerId)
  }
  if (foreignExchangeTimerId) {
    window.clearTimeout(foreignExchangeTimerId)
  }
  clearReceiptOcrHistoryAutoRefresh()
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
  if (foreignExchangeTimerId) {
    window.clearTimeout(foreignExchangeTimerId)
    foreignExchangeTimerId = null
  }
  foreignExchangePendingKey = ''
  foreignExchangeLoadedKey = ''
  foreignExchangeRequestId += 1
  foreignExchangeState.isLoading = false
  foreignExchangeState.error = ''
  foreignExchangeState.rateToKrw = null
  foreignExchangeState.rateDate = ''
  foreignExchangeState.provider = ''
  foreignExchangeState.basisDateTime = ''
  entryForm.foreignAmount = ''
  entryForm.exchangeRateToKrw = ''
  entryForm.exchangeRateDate = ''
  entryForm.exchangeRateProvider = ''
}

function buildForeignExchangeDateTime() {
  const entryDate = String(entryForm.entryDate || '').trim()
  if (!/^\d{4}-\d{2}-\d{2}$/.test(entryDate)) {
    return ''
  }
  const entryTime = isEntryTimeEnabled.value
    ? normalizeEntryTimePayload(entryForm.entryTime)
    : '00:00'
  return `${entryDate}T${entryTime || '00:00'}`
}

function buildForeignExchangeRequestKey(currencyCode, entryDateTime) {
  return `${normalizeForeignCurrencyCode(currencyCode)}|${entryDateTime || buildForeignExchangeDateTime()}`
}

function hasLoadedForeignExchangeQuote(requestKey) {
  return foreignExchangeLoadedKey === requestKey
    && Number.isFinite(Number(foreignExchangeState.rateToKrw))
    && Number(foreignExchangeState.rateToKrw) > 0
}

function applyExchangeQuote(quote) {
  const rate = Number(quote?.rateToKrw || 0)
  if (!quote?.available || !Number.isFinite(rate) || rate <= 0) {
    foreignExchangeState.rateToKrw = null
    foreignExchangeState.rateDate = ''
    foreignExchangeState.provider = ''
    foreignExchangeState.basisDateTime = ''
    entryForm.exchangeRateToKrw = ''
    entryForm.exchangeRateDate = ''
    entryForm.exchangeRateProvider = ''
    foreignExchangeState.error = '환율 정보를 불러오지 못했습니다.'
    return
  }

  foreignExchangeState.rateToKrw = rate
  foreignExchangeState.rateDate = quote.rateDate || entryForm.entryDate
  foreignExchangeState.provider = quote.provider || ''
  foreignExchangeState.basisDateTime = buildForeignExchangeDateTime()
  entryForm.exchangeRateToKrw = String(rate)
  entryForm.exchangeRateDate = foreignExchangeState.rateDate
  entryForm.exchangeRateProvider = foreignExchangeState.provider
  foreignExchangeState.error = ''
  syncForeignKrwAmount()
}

async function loadForeignExchangeRate({ force = false } = {}) {
  const currencyCode = normalizeForeignCurrencyCode(entryForm.foreignCurrencyCode)
  const entryDateTime = buildForeignExchangeDateTime()
  const requestKey = buildForeignExchangeRequestKey(currencyCode, entryDateTime)
  const requestId = ++foreignExchangeRequestId
  if (!currencyCode || currencyCode === 'KRW') {
    clearForeignExchangeFields()
    return
  }
  if (!entryDateTime) {
    foreignExchangeState.error = ''
    foreignExchangeState.basisDateTime = ''
    return
  }
  if (!force && hasLoadedForeignExchangeQuote(requestKey)) {
    foreignExchangeState.basisDateTime = entryDateTime
    return
  }

  foreignExchangeState.isLoading = true
  foreignExchangeState.error = ''
  foreignExchangeState.basisDateTime = entryDateTime
  try {
    const quote = await fetchLedgerExchangeRate(currencyCode, entryForm.entryDate, entryDateTime)
    if (requestId !== foreignExchangeRequestId || entryForm.currencyMode !== 'FOREIGN') {
      return
    }
    applyExchangeQuote(quote)
    foreignExchangeLoadedKey = Number(foreignExchangeState.rateToKrw) > 0 ? requestKey : ''
  } catch (error) {
    if (requestId !== foreignExchangeRequestId) {
      return
    }
    foreignExchangeLoadedKey = ''
    foreignExchangeState.error = error.message || '환율 정보를 불러오지 못했습니다.'
    entryForm.exchangeRateToKrw = ''
    entryForm.exchangeRateDate = ''
    entryForm.exchangeRateProvider = ''
    foreignExchangeState.basisDateTime = ''
  } finally {
    if (requestId === foreignExchangeRequestId) {
      foreignExchangeState.isLoading = false
    }
  }
}

function queueForeignExchangeRateLoad({ immediate = false } = {}) {
  const currencyCode = normalizeForeignCurrencyCode(entryForm.foreignCurrencyCode)
  const entryDateTime = buildForeignExchangeDateTime()
  const requestKey = buildForeignExchangeRequestKey(currencyCode, entryDateTime)

  if (!currencyCode || currencyCode === 'KRW') {
    clearForeignExchangeFields()
    return
  }

  if (!entryDateTime) {
    return
  }

  if (hasLoadedForeignExchangeQuote(requestKey)) {
    foreignExchangeState.basisDateTime = entryDateTime
    return
  }

  if (foreignExchangeTimerId) {
    window.clearTimeout(foreignExchangeTimerId)
    foreignExchangeTimerId = null
  }

  foreignExchangePendingKey = requestKey
  foreignExchangeState.error = ''
  foreignExchangeState.basisDateTime = entryDateTime

  if (immediate) {
    return loadForeignExchangeRate()
  }

  foreignExchangeTimerId = window.setTimeout(() => {
    foreignExchangeTimerId = null
    if (foreignExchangePendingKey !== requestKey) {
      return
    }
    loadForeignExchangeRate().catch((error) => {
      foreignExchangeState.error = error.message || '환율 정보를 불러오지 못했습니다.'
    })
  }, FOREIGN_EXCHANGE_DEBOUNCE_MS)
}

async function ensureForeignExchangeRateLoaded() {
  if (foreignExchangeTimerId) {
    window.clearTimeout(foreignExchangeTimerId)
    foreignExchangeTimerId = null
  }
  foreignExchangePendingKey = ''
  await loadForeignExchangeRate()
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
    const hydratedDateTime = entry.entryDate
      ? `${entry.entryDate}T${normalizeEntryTimePayload(entry.entryTime)}`
      : ''
    foreignExchangeState.basisDateTime = hydratedDateTime
    foreignExchangeLoadedKey = Number(entry.exchangeRateToKrw || 0) > 0 && hydratedDateTime
      ? buildForeignExchangeRequestKey(currencyCode, hydratedDateTime)
      : ''
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

function buildReceiptIsoDateText(year, month, day) {
  const parsedYear = Number(year)
  const parsedMonth = Number(month)
  const parsedDay = Number(day)
  if (!Number.isInteger(parsedYear) || !Number.isInteger(parsedMonth) || !Number.isInteger(parsedDay)) {
    return ''
  }
  const date = new Date(parsedYear, parsedMonth - 1, parsedDay)
  if (date.getFullYear() !== parsedYear || date.getMonth() !== parsedMonth - 1 || date.getDate() !== parsedDay) {
    return ''
  }
  return `${String(parsedYear).padStart(4, '0')}-${String(parsedMonth).padStart(2, '0')}-${String(parsedDay).padStart(2, '0')}`
}

function parseReceiptOcrDateText(value) {
  const text = String(value ?? '').trim()
  if (!text) return ''
  const fullDate = text.match(/(^|[^0-9])((?:19|20)\d{2})\s*[-./]\s*(\d{1,2})\s*[-./]\s*(\d{1,2})(?=$|[^0-9])/)
  if (fullDate) {
    return buildReceiptIsoDateText(fullDate[2], fullDate[3], fullDate[4])
  }
  const monthDay = text.match(/(^|[^0-9])(\d{1,2})\s*[-./]\s*(\d{1,2})(?:\s*[-./])?(?=$|[^0-9])/)
  if (monthDay) {
    return buildReceiptIsoDateText(new Date().getFullYear(), monthDay[2], monthDay[3])
  }
  return ''
}

function parseReceiptOcrTimeText(value) {
  const text = String(value ?? '').trim()
  if (!text) return ''
  const time = text.match(/(^|[^0-9])([01]?\d|2[0-3])\s*:\s*([0-5]\d)(?=$|[^0-9])/)
  if (!time) return ''
  return normalizeEntryTimePayload(`${time[2]}:${time[3]}`)
}
function collectReceiptOcrTextCandidates(suggestion = {}, context = {}) {
  return [
    suggestion.entryDate,
    suggestion.entryTime,
    suggestion.title,
    suggestion.memo,
    suggestion.categoryGroupName,
    suggestion.categoryDetailName,
    suggestion.categoryText,
    suggestion.rawText,
    context.rawText,
  ]
    .map((value) => String(value ?? '').trim())
    .filter(Boolean)
}

const RECEIPT_INCOME_ENTRY_HINTS = [
  '\uAE09\uC5EC', '\uC6D4\uAE09', '\uC785\uAE08 \uBC1B\uC74C', '\uC785\uAE08\uB418\uC5C8', '\uD658\uBD88 \uC644\uB8CC', '\uD658\uAE08',
  '\uBC30\uB2F9\uAE08', '\uC774\uC790 \uC218\uC775', 'salary', 'income', 'deposit received', 'credit received', 'refund', 'cashback',
]

const RECEIPT_EXPENSE_ENTRY_HINTS = [
  '\uAD6C\uB9E4', '\uC8FC\uBB38', '\uACB0\uC81C', '\uC0C1\uD488\uAE08\uC561', '\uBC30\uC1A1\uBE44', '\uC601\uC218\uC99D', '\uCE74\uB4DC \uC2B9\uC778',
  '\uCCAD\uAD6C', '\uB0A9\uBD80', '\uCD9C\uAE08', 'payment', 'purchase', 'order', 'paid', 'sales slip', 'invoice', 'charged',
]

const RECEIPT_EXPENSE_DOCUMENT_HINTS = [
  '\uC8FC\uBB38 \uC815\uBCF4', '\uC8FC\uBB38\uC77C\uC790', '\uC8FC\uBB38 \uC0C1\uD488', '\uC0C1\uD488\uAE08\uC561', '\uAD6C\uB9E4\uD655\uC815',
  '\uACB0\uC81C\uC644\uB8CC', '\uACB0\uC81C \uAE08\uC561', 'order info', 'order date', 'product amount', 'purchase confirmed',
  'payment completed', 'sales slip',
]

function normalizeReceiptEntryTypeEvidence(value) {
  return String(value ?? '').toLowerCase().replace(/\s+/g, ' ').trim()
}

function hasReceiptEntryTypeHint(evidence, hints) {
  return Boolean(evidence) && hints.some((hint) => evidence.includes(hint.toLowerCase()))
}

function resolveReceiptSuggestionEntryType(suggestion = {}, context = {}) {
  const entryEvidence = normalizeReceiptEntryTypeEvidence([
    suggestion.title,
    suggestion.memo,
    suggestion.categoryGroupName,
    suggestion.categoryDetailName,
    suggestion.categoryText,
  ].filter(Boolean).join(' '))
  if (hasReceiptEntryTypeHint(entryEvidence, RECEIPT_INCOME_ENTRY_HINTS)) {
    return 'INCOME'
  }
  if (hasReceiptEntryTypeHint(entryEvidence, RECEIPT_EXPENSE_ENTRY_HINTS)) {
    return 'EXPENSE'
  }

  const documentEvidence = normalizeReceiptEntryTypeEvidence(context.rawText)
  const documentType = normalizeOcrDocumentType(context.documentType || suggestion.documentType)
  if (
    hasReceiptEntryTypeHint(documentEvidence, RECEIPT_EXPENSE_DOCUMENT_HINTS)
    || documentType === 'RECEIPT'
    || documentType === 'PAYMENT_CAPTURE'
  ) {
    return 'EXPENSE'
  }
  return suggestion.entryType === 'INCOME' ? 'INCOME' : 'EXPENSE'
}
function resolveReceiptSuggestionDate(suggestion = {}, context = {}) {
  const direct = parseReceiptOcrDateText(suggestion.entryDate)
  if (direct) return direct
  for (const text of collectReceiptOcrTextCandidates(suggestion, context)) {
    const parsed = parseReceiptOcrDateText(text)
    if (parsed) return parsed
  }
  return ''
}

function resolveReceiptSuggestionTime(suggestion = {}, context = {}) {
  const direct = parseReceiptOcrTimeText(suggestion.entryTime)
  if (direct) return direct
  for (const text of collectReceiptOcrTextCandidates(suggestion, context)) {
    const parsed = parseReceiptOcrTimeText(text)
    if (parsed) return parsed
  }
  return ''
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
  const [groupItems, paymentItems, managementGroupItems, managementPaymentItems] = await Promise.all([
    fetchCategories(),
    fetchPaymentMethods(),
    fetchCategories(undefined, { includeInactive: true }),
    fetchPaymentMethods({ includeInactive: true }),
  ])
  categories.value = groupItems
  paymentMethods.value = paymentItems
  managementCategories.value = managementGroupItems
  managementPaymentMethods.value = managementPaymentItems
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
    setFeedback('여행 가계부가 생성되었습니다.')
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
    setFeedback('', '선택한 여행 기간 밖의 거래는 여행 기록으로 연결할 수 없습니다.')
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

function openTravelRecordLocation(entry) {
  const travelPlanId = entry?.travelPlanId || selectedHouseholdTravelPlan.value?.id
  const travelRecordId = entry?.travelRecordId
  if (!travelPlanId || !travelRecordId) {
    setFeedback('', '먼저 여행 기록에 연결할 지출을 선택해주세요.')
    return
  }
  emit('open-travel-record-location', {
    travelPlanId,
    travelRecordId,
  })
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
    throw new Error(`여행 가계부 거래는 ${plan.startDate} - ${plan.endDate} 기간 안에만 입력할 수 있습니다.`)
  }
}

async function loadAggregatePreferences() {
  const response = await fetchHouseholdAggregatePreferences()
  aggregateWidgetConfigs.value = Array.isArray(response?.widgets) ? response.widgets : []
  aggregateSettingsReady.value = true
  if (calendarReady.value) {
    await loadCalendarAggregateEntries()
  }
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

function normalizeAggregateDataPeriod(value) {
  return ['DAY', 'WEEK', 'MONTH', 'QUARTER', 'YEAR'].includes(value) ? value : 'MONTH'
}

function resolveAggregateWidgetDataRange(widget) {
  const kind = String(widget?.kind || '')
  const period = kind === 'MONTHLY_GOAL' ? 'MONTH' : normalizeAggregateDataPeriod(widget?.period)
  if (period === 'DAY' || period === 'WEEK') {
    return buildCalendarEntryRange(calendarAnchorDate.value)
  }
  return resolveRange(calendarAnchorDate.value, period, calendarAnchorDate.value, calendarAnchorDate.value)
}

function buildCalendarAggregateEntryRange(configs = aggregateWidgetConfigs.value) {
  const ranges = []
  const widgets = Array.isArray(configs) ? configs : []
  widgets.forEach((widget) => {
    const kind = String(widget?.kind || '')
    if (!kind || kind === 'NONE') return

    const period = kind === 'MONTHLY_GOAL' ? 'MONTH' : normalizeAggregateDataPeriod(widget?.period)
    const currentRange = resolveAggregateWidgetDataRange(widget)
    ranges.push(currentRange)

    if (kind === 'MONTHLY_CUMULATIVE_CHART' && widget?.comparePreviousPeriod) {
      ranges.push(shiftRange(calendarAnchorDate.value, period, calendarAnchorDate.value, calendarAnchorDate.value, 1))
    }
  })

  if (!ranges.length) {
    ranges.push(buildCalendarEntryRange(calendarAnchorDate.value))
  }

  return ranges.reduce((merged, range) => ({
    from: !merged.from || range.from < merged.from ? range.from : merged.from,
    to: !merged.to || range.to > merged.to ? range.to : merged.to,
  }), { from: '', to: '' })
}

async function loadCalendarAggregateEntries() {
  const range = buildCalendarAggregateEntryRange()
  calendarAggregateEntries.value = await fetchEntries(range.from, range.to)
}

async function loadCalendarData() {
  const range = buildCalendarEntryRange(calendarAnchorDate.value)
  const aggregateRange = buildCalendarAggregateEntryRange()
  const entryItemsPromise = fetchEntries(range.from, range.to)
  const aggregateItemsPromise = aggregateRange.from === range.from && aggregateRange.to === range.to
    ? entryItemsPromise
    : fetchEntries(aggregateRange.from, aggregateRange.to)
  const [dashboardResponse, entryItems, aggregateItems] = await Promise.all([
    fetchDashboard(calendarAnchorDate.value),
    entryItemsPromise,
    aggregateItemsPromise,
  ])
  dashboard.value = dashboardResponse
  monthEntries.value = entryItems
  calendarAggregateEntries.value = aggregateItems
}

async function loadStatisticsData({ route = resolveHouseholdStatisticsRoute() } = {}) {
  if (!shouldLoadStatisticsForTab(route)) {
    return
  }

  const range = statsRange.value
  const shouldLoadOverview = shouldLoadOverviewForTab(route)
  const shouldLoadBreakdowns = shouldLoadBreakdownsForTab(route)
  const shouldLoadComparisonRows = shouldLoadComparisonRowsForTab(route)
  const shouldLoadInsightEntries = shouldLoadStatisticsEntriesForTab(route)
  const [overview, categoryItems, paymentItems, compareItems, entryItems] = await Promise.all([
    shouldLoadOverview ? fetchOverview(range.from, range.to) : Promise.resolve(null),
    shouldLoadBreakdowns ? fetchCategoryBreakdown(range.from, range.to, 'EXPENSE') : Promise.resolve(null),
    shouldLoadBreakdowns ? fetchPaymentBreakdown(range.from, range.to) : Promise.resolve(null),
    shouldLoadComparisonRows ? fetchCompare(comparisonAnchorDate.value, statsControls.compareUnit, statsControls.comparePeriods) : Promise.resolve(null),
    shouldLoadInsightEntries ? fetchEntries(range.from, range.to) : Promise.resolve([]),
  ])

  if (shouldLoadOverview) {
    statsOverview.value = overview
  }
  if (shouldLoadBreakdowns) {
    expenseBreakdown.value = categoryItems
    paymentBreakdown.value = paymentItems
  }
  if (shouldLoadComparisonRows) {
    comparisonRows.value = compareItems
  }
  if (shouldLoadInsightEntries) {
    statsEntries.value = entryItems
  }
  if (shouldLoadPastComparisonsForTab(route)) {
    await loadPastComparisons()
  }
}

function normalizeAiOptionalDate(value) {
  const text = String(value ?? '').trim()
  return text || null
}
function buildAiAnalysisFocusPrompt() {
  if (!aiAnalysisControls.focusEnabled) {
    return ''
  }
  const customText = String(aiAnalysisControls.focusCustomText || '').trim()
  const prompts = {
    SPENDING_PATTERN: '지출 패턴 분석을 중점으로, 지출이 많이 발생한 영역과 반복되는 소비 흐름을 우선 분석해 주세요.',
    FIXED_COST: '고정 지출 분석을 중점으로, 정기적으로 반복되는 지출과 줄일 수 있는 고정비 후보를 우선 분석해 주세요.',
    RECURRING_IRREGULAR: '비정기 지출 및 정기 지출 종합 분석을 중점으로, 반복 지출과 갑작스러운 지출을 구분해 설명해 주세요.',
    DETAILED_CONSUMPTION: '소비 패턴 정밀 분석을 중점으로, 카테고리, 결제수단, 금액대, 시점별 소비 특징을 구체적으로 분석해 주세요.',
    COMPREHENSIVE: '종합 분석을 중점으로, 지출 패턴, 고정비, 비정기 지출, 개선 방향을 균형 있게 분석해 주세요.',
    CUSTOM: customText,
  }
  return String(prompts[aiAnalysisControls.focusPreset] || '').trim().slice(0, 500)
}
function buildAiAnalysisPayload() {
  const mode = aiAnalysisControls.mode || 'PERIOD'
  const focusPrompt = buildAiAnalysisFocusPrompt()
  const payload = {
    mode,
    anchorDate: aiAnalysisControls.anchorDate || statsControls.anchorDate || today,
  }
  if (focusPrompt) {
    payload.focusPrompt = focusPrompt
  }

  if (mode === 'COMPARISON') {
    payload.comparisonPreset = aiAnalysisControls.comparisonPreset || 'CURRENT_MONTH_VS_PREVIOUS_MONTH'
    if (payload.comparisonPreset === 'CUSTOM') {
      payload.periodType = aiAnalysisControls.periodType || 'CUSTOM'
      payload.from = normalizeAiOptionalDate(aiAnalysisControls.from)
      payload.to = normalizeAiOptionalDate(aiAnalysisControls.to)
      payload.compareFrom = normalizeAiOptionalDate(aiAnalysisControls.compareFrom)
      payload.compareTo = normalizeAiOptionalDate(aiAnalysisControls.compareTo)
    }
    return payload
  }

  payload.periodType = aiAnalysisControls.periodType || 'MONTH'
  if (payload.periodType === 'CUSTOM') {
    payload.from = normalizeAiOptionalDate(aiAnalysisControls.from)
    payload.to = normalizeAiOptionalDate(aiAnalysisControls.to)
  }
  return payload
}

function syncAiAnalysisControls(history) {
  if (!history) {
    return
  }
  aiAnalysisControls.mode = history.mode || aiAnalysisControls.mode
  aiAnalysisControls.periodType = history.periodType || aiAnalysisControls.periodType
  aiAnalysisControls.comparisonPreset = history.comparisonPreset || aiAnalysisControls.comparisonPreset
  aiAnalysisControls.anchorDate = history.to || aiAnalysisControls.anchorDate
  aiAnalysisControls.from = history.from || aiAnalysisControls.from
  aiAnalysisControls.to = history.to || aiAnalysisControls.to
  aiAnalysisControls.compareFrom = history.compareFrom || ''
  aiAnalysisControls.compareTo = history.compareTo || ''
}

function buildAiAnalysisHistoryParams(page = 0) {
  const params = {
    page,
    size: AI_HISTORY_PAGE_SIZE,
  }
  if (aiAnalysisHistoryFilters.mode) {
    params.mode = aiAnalysisHistoryFilters.mode
  }
  if (aiAnalysisHistoryFilters.periodType) {
    params.periodType = aiAnalysisHistoryFilters.periodType
  }
  if (aiAnalysisHistoryFilters.createdFrom) {
    params.createdFrom = aiAnalysisHistoryFilters.createdFrom
  }
  if (aiAnalysisHistoryFilters.createdTo) {
    params.createdTo = aiAnalysisHistoryFilters.createdTo
  }
  if (aiAnalysisHistoryFilters.comparisonOnly !== '') {
    params.comparisonOnly = aiAnalysisHistoryFilters.comparisonOnly === 'true'
  }
  return params
}

async function loadAiAnalysisStatus() {
  try {
    aiAnalysisStatus.value = await fetchLedgerAiAnalysisStatus()
  } catch (error) {
    aiAnalysisStatus.value = {
      enabled: false,
      configured: false,
      workflowConfigured: false,
      apiKeyConfigured: false,
      model: 'gemma4:e12b',
      message: error.message || 'AI 분석 설정 상태를 불러오지 못했습니다.',
    }
  }
}

async function loadAiAnalysisHistory(page = 0) {
  aiAnalysisHistoryLoading.value = true
  aiAnalysisHistoryError.value = ''
  try {
    aiAnalysisHistoryPage.value = await fetchLedgerAiAnalysisHistories(buildAiAnalysisHistoryParams(page))
  } catch (error) {
    aiAnalysisHistoryError.value = error.message || 'AI 분석 기록을 불러오지 못했습니다.'
  } finally {
    aiAnalysisHistoryLoading.value = false
  }
}

async function loadLatestAiAnalysis() {
  aiAnalysisLoading.value = true
  aiAnalysisError.value = ''
  setFeedback()
  try {
    if (!aiAnalysisStatus.value) {
      await loadAiAnalysisStatus()
    }
    const detail = await fetchLatestLedgerAiAnalysis(buildAiAnalysisPayload())
    if (detail?.result) {
      aiAnalysis.value = detail.result
      aiAnalysisStale.value = false
      syncAiAnalysisControls(detail.history)
    } else {
      aiAnalysisError.value = '같은 조건으로 저장된 AI 분석 기록이 없습니다.'
    }
  } catch (error) {
    aiAnalysisError.value = error.message || '기존 AI 분석 결과를 불러오지 못했습니다.'
  } finally {
    aiAnalysisLoading.value = false
  }
}

async function openAiAnalysisHistory(historyId) {
  if (!historyId) {
    return
  }
  aiAnalysisLoading.value = true
  aiAnalysisError.value = ''
  try {
    const detail = await fetchLedgerAiAnalysisHistory(historyId)
    aiAnalysis.value = detail?.result ?? null
    aiAnalysisStale.value = false
    syncAiAnalysisControls(detail?.history)
  } catch (error) {
    aiAnalysisError.value = error.message || 'AI 분석 기록을 열지 못했습니다.'
  } finally {
    aiAnalysisLoading.value = false
  }
}

async function requestAiAnalysis() {
  const hadPreviousResult = Boolean(aiAnalysis.value)
  aiAnalysisLoading.value = true
  aiAnalysisError.value = ''
  aiAnalysisStale.value = hadPreviousResult
  setFeedback()
  try {
    if (!aiAnalysisStatus.value) {
      await loadAiAnalysisStatus()
    }
    const payload = buildAiAnalysisPayload()
    aiAnalysis.value = await analyzeLedgerSpending(payload)
    aiAnalysisStale.value = false
    await loadAiAnalysisHistory()
  } catch (error) {
    const message = error.message || 'AI 분석 요청을 처리하지 못했습니다.'
    if (aiAnalysis.value) {
      aiAnalysisStale.value = true
      aiAnalysisError.value = `새 분석 요청에 실패했습니다. 아래는 이전 저장/표시 결과입니다. (${message})`
    } else {
      aiAnalysisStale.value = false
      aiAnalysisError.value = message
    }
  } finally {
    aiAnalysisLoading.value = false
  }
}


async function deleteAiAnalysisHistory(history) {
  const historyId = history?.id ?? history
  if (!historyId) {
    return
  }
  const title = history?.title || 'AI 분석 기록'
  if (!window.confirm(`'${title}' 기록을 삭제할까요?\n\n삭제하면 저장된 AI 분석 결과를 다시 불러올 수 없습니다.`)) {
    return
  }
  try {
    await deleteLedgerAiAnalysisHistory(historyId)
    if (String(aiAnalysis.value?.historyId || '') === String(historyId)) {
      aiAnalysis.value = null
      aiAnalysisStale.value = false
    }
    setFeedback('AI 분석 기록을 삭제했습니다.')
    await loadAiAnalysisHistory(aiAnalysisHistoryPage.value?.page ?? 0)
  } catch (error) {
    setFeedback('', error.message || 'AI 분석 기록을 삭제하지 못했습니다.')
  }
}
async function rerunAiAnalysis(historyId) {
  if (!historyId) {
    return
  }
  const hadPreviousResult = Boolean(aiAnalysis.value)
  aiAnalysisLoading.value = true
  aiAnalysisError.value = ''
  aiAnalysisStale.value = hadPreviousResult
  setFeedback()
  try {
    aiAnalysis.value = await rerunLedgerAiAnalysis(historyId)
    aiAnalysisStale.value = false
    await loadAiAnalysisHistory(aiAnalysisHistoryPage.value?.page ?? 0)
  } catch (error) {
    const message = error.message || 'AI 분석 재요청을 처리하지 못했습니다.'
    if (aiAnalysis.value) {
      aiAnalysisStale.value = true
      aiAnalysisError.value = `AI 재분석 요청에 실패했습니다. 아래는 이전 저장/표시 결과입니다. (${message})`
    } else {
      aiAnalysisStale.value = false
      aiAnalysisError.value = message
    }
  } finally {
    aiAnalysisLoading.value = false
  }
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

function updateSearchKeywordDraft(value) {
  searchKeywordDraft.value = String(value ?? '')
}

async function submitSearch() {
  if (searchRequestTimerId) {
    window.clearTimeout(searchRequestTimerId)
    searchRequestTimerId = null
  }
  const normalizedKeyword = searchKeywordDraft.value.trim()
  searchKeywordDraft.value = normalizedKeyword
  searchForm.keyword = normalizedKeyword
  await loadSearchResults(0)
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
    keywordSpaceAnd: false,
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
    keywordSpaceAnd: searchForm.keywordSpaceAnd ? true : false,
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
  if (!window.confirm('선택한 변경 이력의 변경 전 상태로 거래를 복구할까요? 현재 상태는 복구 이력으로 저장됩니다.')) {
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
    ? findCategoryGroupByKeywords('INCOME', ['여행', '정산', '환급', '수입', '환전'])
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
  const normalizedKeyword = String(keyword || '여행').trim() || '여행'
  searchKeywordDraft.value = normalizedKeyword
  searchForm.keyword = normalizedKeyword
  searchForm.entryType = entryType === 'INCOME' || entryType === 'EXPENSE' ? entryType : ''
  searchForm.paymentMethodId = ''
  searchForm.categoryGroupId = ''
  searchForm.categoryDetailId = ''
  searchForm.minAmount = ''
  searchForm.maxAmount = ''
  searchForm.sortBy = 'DATE_DESC'
  searchForm.keywordSpaceAnd = true
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

function setReceiptOcrView(view) {
  receiptOcr.activeView = ['analyze', 'history', 'rules'].includes(view) ? view : ''
  receiptOcr.historyDetailAnalysisId = ''
  if (receiptOcr.activeView !== 'history') {
    clearReceiptOcrHistoryAutoRefresh()
    return
  }
  if (!receiptOcr.historyItems.length && !receiptOcr.isHistoryLoading) {
    loadReceiptOcrHistories(0)
    return
  }
  scheduleReceiptOcrHistoryAutoRefresh()
}

function setReceiptRequestPromptEnabled(value) {
  receiptOcr.requestPromptEnabled = Boolean(value)
}

function setReceiptRequestPrompt(value) {
  const normalized = normalizeReceiptPrompt(value)
  receiptOcr.requestPrompt = normalized
  saveReceiptOcrRequestPromptLast(normalized)
}

function rememberReceiptOcrRequestPrompt(value) {
  const normalized = normalizeReceiptPrompt(value)
  if (!normalized) {
    return
  }
  const nextHistory = [normalized, ...receiptOcr.requestPromptHistory.filter((item) => item !== normalized)]
    .slice(0, RECEIPT_OCR_REQUEST_PROMPT_HISTORY_LIMIT)
  receiptOcr.requestPromptHistory = nextHistory
  saveReceiptOcrRequestPromptHistory(nextHistory)
  saveReceiptOcrRequestPromptLast(normalized)
}

function setReceiptExistingEntryStyleEnabled(value) {
  receiptOcr.useExistingEntryStyle = Boolean(value)
}

function setReceiptRerunPromptEnabled(value) {
  receiptOcr.rerunPromptEnabled = Boolean(value)
}

function setReceiptRerunPrompt(value) {
  receiptOcr.rerunPrompt = normalizeReceiptPrompt(value)
}

function setReceiptPromptRulesEnabled(value) {
  receiptOcr.promptRulesEnabled = Boolean(value)
}

function setReceiptPromptRules(value) {
  const normalized = normalizeReceiptPrompt(value, 3000)
  receiptOcr.promptRules = normalized
  saveReceiptOcrPromptRules(normalized)
}

function buildReceiptOcrPrompt(requestPrompt = '') {
  const parts = []
  if (receiptOcr.promptRulesEnabled && receiptOcr.promptRules) {
    parts.push(`[나만의 프롬프트 규칙]\n${receiptOcr.promptRules}`)
  }
  const normalizedRequestPrompt = normalizeReceiptPrompt(requestPrompt)
  if (normalizedRequestPrompt) {
    parts.push(`[이번 요청사항]\n${normalizedRequestPrompt}`)
  }
  return parts.join('\n\n')
}
function revokeReceiptOcrItemPreview(item) {
  if (item?.previewUrl) {
    URL.revokeObjectURL(item.previewUrl)
    item.previewUrl = ''
  }
}

function clearReceiptOcr() {
  receiptOcr.items.forEach((item) => {
    item.abortController?.abort()
    revokeReceiptOcrItemPreview(item)
  })
  receiptOcr.isAnalyzing = false
  receiptOcr.pendingCount = 0
  receiptOcr.batchTotalCount = 0
  receiptOcr.batchCompletedCount = 0
  receiptOcr.error = ''
  receiptOcr.activeView = 'analyze'
  receiptOcr.historyDetailAnalysisId = ''
  receiptOcr.isHistoryDetailLoading = false
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
  clearReceiptOcrAppliedMarker()
}

function openReceiptOcrModal() {
  receiptOcr.isOpen = true
  receiptOcr.activeView = ''
  receiptOcr.historyDetailAnalysisId = ''
  clearReceiptOcrHistoryAutoRefresh()
}

function closeReceiptOcrModal() {
  receiptOcr.isOpen = false
  clearReceiptOcrHistoryAutoRefresh()
}

function normalizeOcrDocumentType(documentType) {
  const normalized = String(documentType || 'AUTO').trim().toUpperCase().replace('-', '_')
  return ['AUTO', 'RECEIPT', 'PAYMENT_CAPTURE'].includes(normalized) ? normalized : 'AUTO'
}

function setReceiptOcrDocumentType(documentType) {
  receiptOcr.documentType = normalizeOcrDocumentType(documentType)
}

function normalizeOcrSuggestion(suggestion = {}, context = {}) {
  const entryType = resolveReceiptSuggestionEntryType(suggestion, context)
  const category = resolveReceiptSuggestionCategory(suggestion, entryType, context)
  return {
    entryDate: resolveReceiptSuggestionDate(suggestion, context),
    entryTime: resolveReceiptSuggestionTime(suggestion, context),
    title: suggestion.title || '',
    memo: suggestion.memo || '',
    amount: suggestion.amount !== null && suggestion.amount !== undefined && suggestion.amount !== ''
      ? String(Number(suggestion.amount || 0))
      : '',
    entryType,
    categoryGroupId: category.categoryGroupId,
    categoryGroupName: category.categoryGroupName,
    categoryDetailId: category.categoryDetailId,
    categoryDetailName: category.categoryDetailName,
    paymentMethodId: suggestion.paymentMethodId != null ? String(suggestion.paymentMethodId) : '',
    paymentMethodName: suggestion.paymentMethodName || '',
  }
}
function attachReceiptOcrSuggestionMeta(suggestion, item, entryIndex) {
  return {
    ...normalizeOcrSuggestion(suggestion, {
      rawText: item.rawText,
      documentType: item.documentType,
    }),
    analysisId: item.analysisId || null,
    clientRequestId: item.clientRequestId || null,
    analysisStatus: item.analysisStatus || null,
    reviewItemId: item.id,
    reviewEntryIndex: entryIndex,
  }
}
function createReceiptOcrItem(file, documentType) {
  receiptOcrItemSequence += 1
  const clientRequestId = `image-analysis-${Date.now()}-${receiptOcrItemSequence}`
  return {
    id: clientRequestId,
    clientRequestId,
    fileName: file.name || `transaction-image-${receiptOcrItemSequence}`,
    previewUrl: URL.createObjectURL(file),
    documentType,
    status: 'queued',
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
    analysisId: null,
    analysisStatus: 'PROCESSING',
    fromHistory: false,
    cancelled: false,
    abortController: null,
    sourceFile: file,
    storedImageAvailable: false,
  }
}
function createReceiptOcrStoredImageItem(sourceItem, documentType) {
  receiptOcrItemSequence += 1
  const clientRequestId = `image-analysis-rerun-${Date.now()}-${receiptOcrItemSequence}`
  const sourceAnalysisId = sourceItem?.analysisId || null
  return {
    id: clientRequestId,
    clientRequestId,
    fileName: sourceItem?.fileName || `transaction-image-${receiptOcrItemSequence}`,
    previewUrl: sourceItem?.previewUrl || (sourceAnalysisId ? buildLedgerImageAnalysisImageUrl(sourceAnalysisId) : ''),
    documentType,
    status: 'queued',
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
    analysisId: null,
    analysisStatus: 'PROCESSING',
    fromHistory: false,
    cancelled: false,
    abortController: null,
    sourceFile: null,
    storedImageAvailable: Boolean(sourceItem?.storedImageAvailable || sourceItem?.previewUrl),
    sourceAnalysisId,
  }
}
function syncReceiptOcrBusyState() {
  receiptOcr.pendingCount = receiptOcr.items.filter((item) => item.status === 'queued' || item.status === 'analyzing').length
  receiptOcr.isAnalyzing = receiptOcr.pendingCount > 0
}

async function removeReceiptOcrItem(itemId) {
  const item = receiptOcr.items.find((candidate) => candidate.id === itemId)
  const shouldCancelServerRequest = item?.status === 'analyzing'
  if (item) {
    item.abortController?.abort()
    item.abortController = null
    item.cancelled = true
    item.status = 'cancelled'
    item.analysisStatus = 'CANCELLED'
  }
  revokeReceiptOcrItemPreview(item)
  receiptOcr.items = receiptOcr.items.filter((candidate) => candidate.id !== itemId)
  if (shouldCancelServerRequest) {
    try {
      if (item.analysisId) {
        await cancelLedgerImageAnalysisHistory(item.analysisId)
      } else if (item.clientRequestId) {
        await cancelLedgerImageAnalysisClientRequest(item.clientRequestId)
      }
    } catch (cancelError) {
      console.warn('Failed to cancel image analysis request', cancelError)
    }
  }
  if (item?.analysisId && String(receiptOcr.historyDetailAnalysisId) === String(item.analysisId)) {
    receiptOcr.historyDetailAnalysisId = ''
  }
  if (item?.analysisId && String(receiptOcr.lastAppliedAnalysisId) === String(item.analysisId)) {
    clearReceiptOcrAppliedMarker()
  }
  syncReceiptOcrBusyState()
}
function isReceiptOcrItemActive(item) {
  return !!item && !item.cancelled && receiptOcr.items.some((candidate) => candidate.id === item.id)
}
function updateReceiptOcrItemPrompt({ itemId, enabled, prompt }) {
  const item = receiptOcr.items.find((candidate) => candidate.id === itemId)
  if (!item || item.status !== 'selected' || item.fromHistory) {
    return
  }
  if (enabled !== undefined) {
    item.requestPromptEnabled = Boolean(enabled)
  }
  if (prompt !== undefined) {
    item.requestPrompt = normalizeReceiptPrompt(prompt)
    saveReceiptOcrRequestPromptLast(item.requestPrompt)
  }
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

function normalizeImageAnalysisHistoryStatus(status) {
  const normalized = String(status || '').trim().toUpperCase()
  return ['PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED'].includes(normalized) ? normalized : 'COMPLETED'
}

function hasProcessingReceiptOcrHistoryItems(items = receiptOcr.historyItems) {
  return Array.isArray(items) && items.some((history) => (
    normalizeImageAnalysisHistoryStatus(history?.status || history?.result?.analysisStatus) === 'PROCESSING'
  ))
}

function clearReceiptOcrHistoryAutoRefresh() {
  if (!receiptOcrHistoryRefreshTimerId || typeof window === 'undefined') {
    receiptOcrHistoryRefreshTimerId = null
    return
  }
  window.clearTimeout(receiptOcrHistoryRefreshTimerId)
  receiptOcrHistoryRefreshTimerId = null
}

function scheduleReceiptOcrHistoryAutoRefresh() {
  clearReceiptOcrHistoryAutoRefresh()
  if (typeof window === 'undefined') {
    return
  }
  if (!receiptOcr.isOpen || receiptOcr.activeView !== 'history' || !hasProcessingReceiptOcrHistoryItems()) {
    return
  }
  receiptOcrHistoryRefreshTimerId = window.setTimeout(async () => {
    receiptOcrHistoryRefreshTimerId = null
    if (!receiptOcr.isOpen || receiptOcr.activeView !== 'history') {
      return
    }
    await loadReceiptOcrHistories(receiptOcr.historyPage || 0, { silent: true })
  }, RECEIPT_OCR_HISTORY_AUTO_REFRESH_INTERVAL_MS)
}

function normalizeReceiptApprovedEntryIndexes(values = []) {
  if (!Array.isArray(values)) {
    return []
  }
  const seen = new Set()
  return values
    .map((value) => Number(value))
    .filter((value) => Number.isInteger(value) && value >= 0)
    .filter((value) => {
      if (seen.has(value)) {
        return false
      }
      seen.add(value)
      return true
    })
}

function normalizeReceiptApprovedEntryIds(values = []) {
  if (!Array.isArray(values)) {
    return []
  }
  const seen = new Set()
  return values
    .map((value) => Number(value))
    .filter((value) => Number.isFinite(value) && value > 0)
    .filter((value) => {
      if (seen.has(value)) {
        return false
      }
      seen.add(value)
      return true
    })
}
function mapImageAnalysisHistoryToReviewItem(history = {}) {
  const result = history.result || {}
  const status = normalizeImageAnalysisHistoryStatus(history.status || result.analysisStatus)
  const suggestions = Array.isArray(result.suggestedEntries) && result.suggestedEntries.length
    ? result.suggestedEntries
    : [result.suggestedEntry].filter(Boolean)
  const approvedEntryIndexes = normalizeReceiptApprovedEntryIndexes(result.approvedEntryIndexes)
  const approvedEntryIds = normalizeReceiptApprovedEntryIds(result.approvedEntryIds)
  const itemId = `history-${history.id || Date.now()}`
  const analysisId = history.id || result.analysisId || null
  const clientRequestId = history.clientRequestId || result.clientRequestId || null
  const analysisStatus = status
  const storedImageAvailable = Boolean(history.imageAvailable || history.imageUrl)
  const previewUrl = history.imageUrl || (storedImageAvailable && analysisId ? buildLedgerImageAnalysisImageUrl(analysisId) : '')
  return {
    id: itemId,
    clientRequestId,
    fileName: history.fileName || `저장된 분석 #${history.id || ''}`.trim(),
    previewUrl,
    imageUrl: previewUrl,
    storedImageAvailable,
    documentType: normalizeOcrDocumentType(history.documentType || result.documentType),
    status: status === 'COMPLETED' ? 'done' : status === 'PROCESSING' ? 'analyzing' : status === 'CANCELLED' ? 'cancelled' : 'error',
    error: history.errorMessage || '',
    rawText: history.rawText || result.rawText || '',
    suggestedEntries: suggestions.map((suggestion, entryIndex) => attachReceiptOcrSuggestionMeta(suggestion, { id: itemId, analysisId, clientRequestId, analysisStatus, rawText: history.rawText || result.rawText || '' }, entryIndex)),
    approvedEntryIndexes,
    approvedEntryIds,
    lineItems: Array.isArray(result.lineItems) ? result.lineItems : [],
    warnings: Array.isArray(result.warnings) ? result.warnings : [],
    confidence: result.confidence ?? null,
    vendor: result.vendor || '',
    paymentMethodText: result.paymentMethodText || '',
    categoryText: result.categoryText || '',
    timing: result.timing || null,
    analysisId: history.id || result.analysisId || null,
    clientRequestId,
    analysisStatus: status,
    fromHistory: true,
    sourceFile: null,
  }
}
function sleepReceiptOcrPoll(ms, signal) {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(new DOMException('Aborted', 'AbortError'))
      return
    }
    const timeoutId = window.setTimeout(resolve, ms)
    const abortHandler = () => {
      window.clearTimeout(timeoutId)
      reject(new DOMException('Aborted', 'AbortError'))
    }
    signal?.addEventListener('abort', abortHandler, { once: true })
  })
}

function applyReceiptOcrHistoryToItem(item, history = {}) {
  if (!item) {
    return null
  }
  const mapped = mapImageAnalysisHistoryToReviewItem(history)
  const localId = item.id
  const sourceFile = item.sourceFile || null
  const localPreviewUrl = item.previewUrl || ''
  const fromHistory = item.fromHistory
  Object.assign(item, mapped, {
    id: localId,
    fromHistory,
    sourceFile,
    previewUrl: localPreviewUrl || mapped.previewUrl,
    imageUrl: mapped.imageUrl || item.imageUrl || '',
    storedImageAvailable: Boolean(mapped.storedImageAvailable || item.storedImageAvailable),
    cancelled: item.cancelled || mapped.status === 'cancelled',
  })
  item.suggestedEntries = (Array.isArray(mapped.suggestedEntries) ? mapped.suggestedEntries : [])
    .map((suggestion, entryIndex) => attachReceiptOcrSuggestionMeta(suggestion, item, entryIndex))
  return item
}

async function waitForReceiptOcrHistoryResult(item, initialResult = {}) {
  const analysisId = initialResult?.analysisId || item?.analysisId
  if (!analysisId) {
    return initialResult
  }
  item.analysisId = analysisId
  item.clientRequestId = initialResult?.clientRequestId || item.clientRequestId
  item.analysisStatus = 'PROCESSING'
  item.status = 'analyzing'

  for (let attempt = 0; attempt < RECEIPT_OCR_HISTORY_POLL_ATTEMPTS; attempt += 1) {
    if (!isReceiptOcrItemActive(item)) {
      return null
    }
    const history = await fetchLedgerImageAnalysisHistory(analysisId)
    if (!isReceiptOcrItemActive(item)) {
      return null
    }
    const status = normalizeImageAnalysisHistoryStatus(history?.status || history?.result?.analysisStatus)
    if (status === 'COMPLETED') {
      applyReceiptOcrHistoryToItem(item, history)
      return {
        ...(history.result || {}),
        analysisId: history.id || analysisId,
        clientRequestId: history.clientRequestId || item.clientRequestId,
        analysisStatus: 'COMPLETED',
      }
    }
    if (status === 'FAILED') {
      throw new Error(history?.errorMessage || 'AI image analysis failed.')
    }
    if (status === 'CANCELLED') {
      return {
        analysisId: history?.id || analysisId,
        clientRequestId: history?.clientRequestId || item.clientRequestId,
        analysisStatus: 'CANCELLED',
      }
    }
    item.analysisStatus = 'PROCESSING'
    item.status = 'analyzing'
    await sleepReceiptOcrPoll(RECEIPT_OCR_HISTORY_POLL_INTERVAL_MS, item.abortController?.signal)
  }
  throw new Error('AI image analysis is still running. Please check the analysis history again later.')
}

async function loadReceiptOcrHistories(page = receiptOcr.historyPage || 0, options = {}) {
  const silent = Boolean(options?.silent)
  clearReceiptOcrHistoryAutoRefresh()
  if (!silent) {
    receiptOcr.isHistoryLoading = true
  }
  receiptOcr.historyError = ''
  try {
    const response = await fetchLedgerImageAnalysisHistories({ page, size: receiptOcr.historySize })
    receiptOcr.historyItems = Array.isArray(response?.content) ? response.content : []
    receiptOcr.historyPage = Number(response?.number ?? page)
    receiptOcr.historyTotalPages = Number(response?.totalPages ?? 0)
    receiptOcr.historyTotalElements = Number(response?.totalElements ?? receiptOcr.historyItems.length)
  } catch (error) {
    receiptOcr.historyError = error.message || '이미지 분석 기록을 불러오지 못했습니다.'
  } finally {
    if (!silent) {
      receiptOcr.isHistoryLoading = false
    }
    scheduleReceiptOcrHistoryAutoRefresh()
  }
}

async function reuseReceiptOcrHistory(history) {
  if (!history?.id) {
    return null
  }
  receiptOcr.isOpen = true
  receiptOcr.historyError = ''
  try {
    const existing = receiptOcr.items.find((candidate) => String(candidate.analysisId) === String(history.id))
    if (existing) {
      receiptOcr.items = [existing, ...receiptOcr.items.filter((candidate) => candidate.id !== existing.id)]
      updateLegacyReceiptOcrFields({
        rawText: existing.rawText,
        lineItems: existing.lineItems,
        warnings: existing.warnings,
        confidence: existing.confidence,
        vendor: existing.vendor,
        paymentMethodText: existing.paymentMethodText,
        categoryText: existing.categoryText,
        timing: existing.timing,
      }, existing.suggestedEntries?.[0] || null, existing.fileName)
      return existing
    }

    const detail = history.result ? history : await fetchLedgerImageAnalysisHistory(history.id)
    const item = mapImageAnalysisHistoryToReviewItem(detail)
    receiptOcr.items = receiptOcr.items.filter((candidate) => String(candidate.analysisId) !== String(item.analysisId))
    receiptOcr.items.unshift(item)
    if (item.analysisStatus !== 'COMPLETED') {
      receiptOcr.historyError = item.analysisStatus === 'CANCELLED'
        ? '취소된 분석 기록입니다. 내용은 확인할 수 있지만 입력칸 적용은 막혀 있습니다.'
        : item.analysisStatus === 'PROCESSING'
          ? '아직 처리 중인 분석 기록입니다. 완료 후 다시 검수할 수 있습니다.'
          : '실패한 분석 기록입니다. 내용은 확인할 수 있지만 입력칸 적용은 막혀 있습니다.'
      return item
    }
    updateLegacyReceiptOcrFields(detail.result || {}, item.suggestedEntries[0] || null, item.fileName)
    return item
  } catch (error) {
    receiptOcr.historyError = error.message || '이미지 분석 기록을 다시 불러오지 못했습니다.'
    return null
  }
}

async function openReceiptOcrHistoryDetail(history) {
  if (!history?.id) {
    return
  }
  receiptOcr.isOpen = true
  receiptOcr.activeView = 'history'
  receiptOcr.historyDetailAnalysisId = String(history.id)
  receiptOcr.isHistoryDetailLoading = true
  try {
    await reuseReceiptOcrHistory(history)
  } finally {
    receiptOcr.isHistoryDetailLoading = false
  }
}

function closeReceiptOcrHistoryDetail() {
  receiptOcr.historyDetailAnalysisId = ''
}

async function cancelReceiptOcrHistory(historyId) {
  if (!historyId) {
    return
  }
  receiptOcr.historyError = ''
  try {
    const cancelled = await cancelLedgerImageAnalysisHistory(historyId)
    receiptOcr.historyItems = receiptOcr.historyItems.map((item) => String(item.id) === String(historyId) ? cancelled : item)
    receiptOcr.items = receiptOcr.items.map((item) => {
      if (String(item.analysisId) !== String(historyId)) {
        return item
      }
      return {
        ...item,
        status: 'cancelled',
        analysisStatus: 'CANCELLED',
        cancelled: true,
        error: '사용자가 취소한 분석 요청입니다.',
      }
    })
    if (String(receiptOcr.historyDetailAnalysisId) === String(historyId)) {
      receiptOcr.historyDetailAnalysisId = ''
    }
    if (String(receiptOcr.lastAppliedAnalysisId) === String(historyId)) {
      if (receiptOcr.lastAppliedMode === 'form' && isReceiptOcrAppliedSnapshotCurrent()) {
        editingEntryId.value = null
        amountInput.value = ''
        entryForm.amount = ''
        entryForm.title = ''
        entryForm.memo = ''
        entryForm.categoryGroupId = ''
        entryForm.categoryDetailId = ''
        entryForm.paymentMethodId = ''
        setFeedback('취소한 분석 결과를 입력칸에서 제거했습니다.')
      } else {
        setFeedback('입력칸이 수동으로 수정된 상태라 분석 취소로 지우지 않았습니다.')
      }
      clearReceiptOcrAppliedMarker()
    }
  } catch (error) {
    receiptOcr.historyError = error.message || '이미지 분석 기록을 취소하지 못했습니다.'
  }
}
async function deleteReceiptOcrHistory(history) {
  const historyId = typeof history === 'object' && history !== null ? history.id : history
  if (!historyId) {
    return
  }
  const fileName = typeof history === 'object' && history !== null ? (history.fileName || `분석 기록 #${historyId}`) : `분석 기록 #${historyId}`
  if (!window.confirm(`'${fileName}' 분석 기록을 삭제할까요?\n\n삭제하면 저장된 원본 이미지와 분석 결과를 다시 불러올 수 없습니다.`)) {
    return
  }
  receiptOcr.historyError = ''
  try {
    await deleteLedgerImageAnalysisHistory(historyId)
    receiptOcr.historyItems = receiptOcr.historyItems.filter((item) => String(item.id) !== String(historyId))
    receiptOcr.items = receiptOcr.items.filter((item) => String(item.analysisId) !== String(historyId))
    receiptOcr.historyTotalElements = Math.max(0, Number(receiptOcr.historyTotalElements || 0) - 1)
    if (String(receiptOcr.historyDetailAnalysisId) === String(historyId)) {
      receiptOcr.historyDetailAnalysisId = ''
    }
    if (String(receiptOcr.lastAppliedAnalysisId) === String(historyId)) {
      clearReceiptOcrAppliedMarker()
    }
    if (!receiptOcr.historyItems.length && receiptOcr.historyPage > 0) {
      await loadReceiptOcrHistories(receiptOcr.historyPage - 1)
    }
    setFeedback('이미지 분석 기록을 삭제했습니다.')
  } catch (error) {
    receiptOcr.historyError = error.message || '이미지 분석 기록을 삭제하지 못했습니다.'
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

async function analyzeReceiptFile(file, documentType, existingItem = null, prompt = '', useExistingEntryStyle = false) {
  const item = existingItem || createReceiptOcrItem(file, documentType)
  if (!existingItem) {
    receiptOcr.items.unshift(item)
  }
  item.status = 'analyzing'
  item.analysisStatus = 'PROCESSING'
  item.abortController = new AbortController()
  syncReceiptOcrBusyState()

  try {
    let result = await analyzeLedgerReceipt(file, { documentType, clientRequestId: item.clientRequestId, prompt, useExistingEntryStyle, signal: item.abortController.signal })
    if (!isReceiptOcrItemActive(item)) {
      if (result?.analysisId) {
        try {
          await cancelLedgerImageAnalysisHistory(result.analysisId)
        } catch (cancelError) {
          console.warn('Failed to cancel removed image analysis history', cancelError)
        }
      }
      return
    }
    if (String(result?.analysisStatus || '').toUpperCase() === 'PROCESSING') {
      result = await waitForReceiptOcrHistoryResult(item, result)
      if (!result || !isReceiptOcrItemActive(item)) {
        return
      }
    }
    if (String(result?.analysisStatus || '').toUpperCase() === 'CANCELLED') {
      item.status = 'cancelled'
      item.analysisId = result?.analysisId || item.analysisId
      item.clientRequestId = result?.clientRequestId || item.clientRequestId
      item.analysisStatus = 'CANCELLED'
      item.cancelled = true
      item.error = '취소된 분석 요청입니다.'
      return
    }
    const suggestions = Array.isArray(result?.suggestedEntries) && result.suggestedEntries.length
      ? result.suggestedEntries
      : [result?.suggestedEntry].filter(Boolean)
    item.status = 'done'
    item.documentType = normalizeOcrDocumentType(result?.documentType || documentType)
    item.analysisId = result?.analysisId || null
    item.clientRequestId = result?.clientRequestId || item.clientRequestId
    item.analysisStatus = result?.analysisStatus || 'COMPLETED'
    item.rawText = result?.rawText || ''
    item.suggestedEntries = suggestions.map((suggestion, entryIndex) => attachReceiptOcrSuggestionMeta(suggestion, item, entryIndex))
    item.lineItems = Array.isArray(result?.lineItems) ? result.lineItems : []
    item.warnings = Array.isArray(result?.warnings) ? result.warnings : []
    item.confidence = result?.confidence ?? null
    item.vendor = result?.vendor || ''
    item.paymentMethodText = result?.paymentMethodText || ''
    item.categoryText = result?.categoryText || ''
    item.timing = result?.timing || null
    updateLegacyReceiptOcrFields(result, item.suggestedEntries[0] || null, item.fileName)
    setFeedback('거래 이미지 분석이 완료되었습니다. 결과를 확인한 뒤 입력칸에 적용해 주세요.')
  } catch (error) {
    if (!isReceiptOcrItemActive(item)) {
      return
    }
    if (error?.name === 'AbortError') {
      item.status = 'cancelled'
      item.analysisStatus = 'CANCELLED'
      item.cancelled = true
      item.error = '사용자가 취소한 분석 요청입니다.'
      return
    }
    item.status = 'error'
    item.analysisStatus = 'FAILED'
    item.error = error.message
    receiptOcr.error = error.message
    setFeedback('', error.message)
  } finally {
    item.abortController = null
    syncReceiptOcrBusyState()
  }
}

function selectedReceiptOcrItems() {
  return receiptOcr.items.filter((item) => item.status === 'selected' && !item.cancelled && !item.fromHistory && typeof File !== 'undefined' && item.sourceFile instanceof File)
}

function selectReceiptOcrFiles(payload) {
  const files = Array.isArray(payload?.files)
    ? payload.files
    : typeof File !== 'undefined' && payload instanceof File
      ? [payload]
      : []
  if (!files.length) {
    return
  }

  const requestedDocumentType = normalizeOcrDocumentType(payload?.documentType || receiptOcr.documentType)
  const documentType = files.length > 1 ? 'AUTO' : requestedDocumentType
  receiptOcr.documentType = documentType
  receiptOcr.isOpen = true
  receiptOcr.activeView = 'analyze'
  receiptOcr.error = ''

  const previousDrafts = receiptOcr.items.filter((item) => item.status === 'selected' && !item.fromHistory)
  previousDrafts.forEach((item) => revokeReceiptOcrItemPreview(item))
  const retainedItems = receiptOcr.items.filter((item) => item.status !== 'selected' || item.fromHistory)
  const draftItems = files.map((file) => {
    const item = createReceiptOcrItem(file, documentType)
    item.status = 'selected'
    item.analysisStatus = 'DRAFT'
    return item
  })
  receiptOcr.items = [...draftItems, ...retainedItems]
  syncReceiptOcrBusyState()
}

async function startSelectedReceiptOcrAnalysis(payload = {}) {
  const items = selectedReceiptOcrItems()
  if (!items.length) {
    return
  }
  const fallbackPrompt = payload?.prompt || ''
  const useExistingEntryStyle = Boolean(payload?.useExistingEntryStyle ?? receiptOcr.useExistingEntryStyle)
  receiptOcr.isOpen = true
  receiptOcr.activeView = 'analyze'
  receiptOcr.error = ''
  receiptOcr.batchTotalCount = items.length
  receiptOcr.batchCompletedCount = 0

  items.forEach((item) => {
    item.status = 'queued'
    item.analysisStatus = 'PROCESSING'
    item.error = ''
  })
  syncReceiptOcrBusyState()

  for (const item of items) {
    if (!isReceiptOcrItemActive(item) || item.status !== 'queued') {
      receiptOcr.batchCompletedCount = Math.min(receiptOcr.batchCompletedCount + 1, receiptOcr.batchTotalCount)
      syncReceiptOcrBusyState()
      continue
    }
    const itemPrompt = item.requestPromptEnabled ? item.requestPrompt : fallbackPrompt
    if (normalizeReceiptPrompt(itemPrompt)) {
      rememberReceiptOcrRequestPrompt(itemPrompt)
    }
    const prompt = buildReceiptOcrPrompt(itemPrompt)
    await analyzeReceiptFile(item.sourceFile, normalizeOcrDocumentType(item.documentType || receiptOcr.documentType), item, prompt, useExistingEntryStyle)
    receiptOcr.batchCompletedCount = Math.min(receiptOcr.batchCompletedCount + 1, receiptOcr.batchTotalCount)
    syncReceiptOcrBusyState()
  }
  if (!receiptOcr.pendingCount) {
    receiptOcr.batchTotalCount = 0
    receiptOcr.batchCompletedCount = 0
  }
  if (items.some((item) => item.status === 'done')) {
    receiptOcr.activeView = 'history'
    await loadReceiptOcrHistories(0)
  }
}
async function analyzeReceiptImage(payload) {
  const files = Array.isArray(payload?.files)
    ? payload.files
    : typeof File !== 'undefined' && payload instanceof File
      ? [payload]
      : []
  if (!files.length) {
    return
  }

  const requestedDocumentType = normalizeOcrDocumentType(payload?.documentType || receiptOcr.documentType)
  const documentType = files.length > 1 ? 'AUTO' : requestedDocumentType
  const prompt = buildReceiptOcrPrompt(payload?.prompt)
  const useExistingEntryStyle = Boolean(payload?.useExistingEntryStyle ?? receiptOcr.useExistingEntryStyle)
  receiptOcr.documentType = documentType
  receiptOcr.isOpen = true
  receiptOcr.activeView = 'analyze'
  receiptOcr.error = ''
  setFeedback(files.length > 1 ? '여러 이미지를 한 번에 올려 자동 분석 요청으로 처리합니다.' : '')
  if (!receiptOcr.historyItems.length && !receiptOcr.isHistoryLoading) {
    loadReceiptOcrHistories(0)
  }
  const queue = files.map((file) => ({
    file,
    item: createReceiptOcrItem(file, documentType),
  }))
  receiptOcr.items.unshift(...queue.map(({ item }) => item))
  syncReceiptOcrBusyState()
  for (const { file, item } of queue) {
    if (isReceiptOcrItemActive(item)) {
      await analyzeReceiptFile(file, documentType, item, prompt, useExistingEntryStyle)
    }
  }
  if (queue.some(({ item }) => item.status === 'done')) {
    receiptOcr.activeView = 'history'
    await loadReceiptOcrHistories(0)
  }
}

async function rerunReceiptOcrItem(payload = {}) {
  const item = payload.item || payload
  const sourceFile = item?.sourceFile
  const canUseSourceFile = typeof File !== 'undefined' && sourceFile instanceof File
  const canUseStoredImage = Boolean(item?.analysisId && item?.storedImageAvailable)
  if (!canUseSourceFile && !canUseStoredImage) {
    setFeedback('', '저장된 원본 이미지가 없어 재요청할 수 없습니다. 이미지를 다시 선택해 주세요.')
    return
  }
  const requestPrompt = payload.prompt || ''
  if (normalizeReceiptPrompt(requestPrompt)) {
    rememberReceiptOcrRequestPrompt(requestPrompt)
  }
  const useExistingEntryStyle = Boolean(payload?.useExistingEntryStyle ?? receiptOcr.useExistingEntryStyle)
  const documentType = normalizeOcrDocumentType(item.documentType || receiptOcr.documentType)
  const prompt = buildReceiptOcrPrompt(requestPrompt)

  if (canUseSourceFile) {
    const nextItem = createReceiptOcrItem(sourceFile, documentType)
    nextItem.fileName = item.fileName || nextItem.fileName
    receiptOcr.items.unshift(nextItem)
    receiptOcr.activeView = 'analyze'
    receiptOcr.historyDetailAnalysisId = ''
    syncReceiptOcrBusyState()
    await analyzeReceiptFile(sourceFile, documentType, nextItem, prompt, useExistingEntryStyle)
    if (nextItem.status === 'done') {
      receiptOcr.activeView = 'history'
      receiptOcr.historyDetailAnalysisId = nextItem.analysisId ? String(nextItem.analysisId) : ''
      await loadReceiptOcrHistories(0)
    }
    return
  }

  const nextItem = createReceiptOcrStoredImageItem(item, documentType)
  receiptOcr.items.unshift(nextItem)
  receiptOcr.activeView = 'analyze'
  receiptOcr.historyDetailAnalysisId = ''
  nextItem.status = 'analyzing'
  nextItem.analysisStatus = 'PROCESSING'
  nextItem.abortController = new AbortController()
  syncReceiptOcrBusyState()

  try {
    let result = await rerunLedgerImageAnalysisHistory(item.analysisId, {
      documentType,
      prompt,
      useExistingEntryStyle,
      signal: nextItem.abortController.signal,
    })
    if (!isReceiptOcrItemActive(nextItem)) {
      if (result?.analysisId) {
        try {
          await cancelLedgerImageAnalysisHistory(result.analysisId)
        } catch (cancelError) {
          console.warn('Failed to cancel removed image analysis history', cancelError)
        }
      }
      return
    }
    if (String(result?.analysisStatus || '').toUpperCase() === 'PROCESSING') {
      result = await waitForReceiptOcrHistoryResult(nextItem, result)
      if (!result || !isReceiptOcrItemActive(nextItem)) {
        return
      }
    }
    if (String(result?.analysisStatus || '').toUpperCase() === 'CANCELLED') {
      nextItem.status = 'cancelled'
      nextItem.analysisId = result?.analysisId || nextItem.analysisId
      nextItem.clientRequestId = result?.clientRequestId || nextItem.clientRequestId
      nextItem.analysisStatus = 'CANCELLED'
      nextItem.cancelled = true
      nextItem.error = '취소된 이미지 분석 요청입니다.'
      return
    }
    const suggestions = Array.isArray(result?.suggestedEntries) && result.suggestedEntries.length
      ? result.suggestedEntries
      : [result?.suggestedEntry].filter(Boolean)
    nextItem.status = 'done'
    nextItem.documentType = normalizeOcrDocumentType(result?.documentType || documentType)
    nextItem.analysisId = result?.analysisId || null
    nextItem.clientRequestId = result?.clientRequestId || nextItem.clientRequestId
    nextItem.analysisStatus = result?.analysisStatus || 'COMPLETED'
    nextItem.rawText = result?.rawText || ''
    nextItem.suggestedEntries = suggestions.map((suggestion, entryIndex) => attachReceiptOcrSuggestionMeta(suggestion, nextItem, entryIndex))
    nextItem.lineItems = Array.isArray(result?.lineItems) ? result.lineItems : []
    nextItem.warnings = Array.isArray(result?.warnings) ? result.warnings : []
    nextItem.confidence = result?.confidence ?? null
    nextItem.vendor = result?.vendor || ''
    nextItem.paymentMethodText = result?.paymentMethodText || ''
    nextItem.categoryText = result?.categoryText || ''
    nextItem.timing = result?.timing || null
    nextItem.storedImageAvailable = true
    updateLegacyReceiptOcrFields(result, nextItem.suggestedEntries[0] || null, nextItem.fileName)
    setFeedback('저장된 원본 이미지로 다시 검수했습니다. 결과를 확인한 뒤 입력칸에 적용해 주세요.')
  } catch (error) {
    if (!isReceiptOcrItemActive(nextItem)) {
      return
    }
    if (error?.name === 'AbortError') {
      nextItem.status = 'cancelled'
      nextItem.analysisStatus = 'CANCELLED'
      nextItem.cancelled = true
      nextItem.error = '사용자가 취소한 이미지 분석 요청입니다.'
      return
    }
    nextItem.status = 'error'
    nextItem.analysisStatus = 'FAILED'
    nextItem.error = error.message
    receiptOcr.error = error.message
    setFeedback('', error.message)
  } finally {
    nextItem.abortController = null
    syncReceiptOcrBusyState()
  }

  if (nextItem.status === 'done') {
    receiptOcr.activeView = 'history'
    receiptOcr.historyDetailAnalysisId = nextItem.analysisId ? String(nextItem.analysisId) : ''
    await loadReceiptOcrHistories(0)
  }
}
function buildReceiptOcrAppliedSnapshot(suggestion = {}) {
  const normalizedSuggestion = normalizeOcrSuggestion(suggestion)
  const amount = normalizedSuggestion.amount !== ''
    ? String(Number(normalizedSuggestion.amount || 0))
    : entryForm.amount
  return {
    entryDate: normalizedSuggestion.entryDate || entryForm.entryDate,
    entryTime: normalizedSuggestion.entryTime ? normalizeEntryTimePayload(normalizedSuggestion.entryTime) : '',
    title: normalizedSuggestion.title || entryForm.title,
    memo: normalizedSuggestion.memo || entryForm.memo,
    amount,
    entryType: normalizedSuggestion.entryType || 'EXPENSE',
    categoryGroupId: normalizedSuggestion.categoryGroupId || entryForm.categoryGroupId,
    categoryDetailId: normalizedSuggestion.categoryDetailId || entryForm.categoryDetailId,
    paymentMethodId: normalizedSuggestion.paymentMethodId || entryForm.paymentMethodId,
  }
}

function isReceiptOcrAppliedSnapshotCurrent(snapshot = receiptOcr.lastAppliedSnapshot) {
  if (!snapshot) return false
  const currentEntryTime = isEntryTimeEnabled.value ? normalizeEntryTimePayload(entryForm.entryTime) : ''
  return String(entryForm.entryDate || '') === String(snapshot.entryDate || '')
    && String(currentEntryTime || '') === String(snapshot.entryTime || '')
    && String(entryForm.title || '') === String(snapshot.title || '')
    && String(entryForm.memo || '') === String(snapshot.memo || '')
    && String(entryForm.amount || '') === String(snapshot.amount || '')
    && String(entryForm.entryType || '') === String(snapshot.entryType || '')
    && String(entryForm.categoryGroupId || '') === String(snapshot.categoryGroupId || '')
    && String(entryForm.categoryDetailId || '') === String(snapshot.categoryDetailId || '')
    && String(entryForm.paymentMethodId || '') === String(snapshot.paymentMethodId || '')
}

function recordReceiptOcrAppliedEntryMarker(analysisId, entryIndex) {
  const normalizedAnalysisId = String(analysisId || '').trim()
  const normalizedEntryIndex = Number(entryIndex)
  if (!normalizedAnalysisId || !Number.isInteger(normalizedEntryIndex) || normalizedEntryIndex < 0) {
    return
  }
  const nextMarkers = normalizeReceiptOcrAppliedEntryMarkers([
    {
      analysisId: normalizedAnalysisId,
      entryIndex: normalizedEntryIndex,
      appliedAt: new Date().toISOString(),
    },
    ...receiptOcr.appliedEntryMarkers,
  ])
  receiptOcr.appliedEntryMarkers = nextMarkers
  saveReceiptOcrAppliedEntryMarkers(nextMarkers)
}

function removeReceiptOcrAppliedEntryMarker(analysisId, entryIndex) {
  const normalizedAnalysisId = String(analysisId || '').trim()
  const normalizedEntryIndex = Number(entryIndex)
  if (!normalizedAnalysisId || !Number.isInteger(normalizedEntryIndex) || normalizedEntryIndex < 0) {
    return
  }
  const nextMarkers = receiptOcr.appliedEntryMarkers.filter((item) => (
    String(item.analysisId) !== normalizedAnalysisId || Number(item.entryIndex) !== normalizedEntryIndex
  ))
  if (nextMarkers.length === receiptOcr.appliedEntryMarkers.length) {
    return
  }
  receiptOcr.appliedEntryMarkers = nextMarkers
  saveReceiptOcrAppliedEntryMarkers(nextMarkers)
}

function clearReceiptOcrAppliedMarker() {
  removeReceiptOcrAppliedEntryMarker(receiptOcr.lastAppliedAnalysisId, receiptOcr.lastAppliedReviewEntryIndex)
  receiptOcr.lastAppliedAnalysisId = null
  receiptOcr.lastAppliedReviewItemId = null
  receiptOcr.lastAppliedReviewEntryIndex = null
  receiptOcr.lastAppliedMode = ''
  receiptOcr.lastAppliedSnapshot = null
}

function cancelReceiptOcrAppliedSuggestion() {
  if (!receiptOcr.lastAppliedAnalysisId || receiptOcr.lastAppliedMode !== 'form') {
    return
  }
  if (isReceiptOcrAppliedSnapshotCurrent()) {
    editingEntryId.value = null
    amountInput.value = ''
    entryForm.amount = ''
    entryForm.title = ''
    entryForm.memo = ''
    entryForm.categoryGroupId = ''
    entryForm.categoryDetailId = ''
    entryForm.paymentMethodId = ''
    setFeedback('AI 분석 결과 적용을 취소하고 입력칸을 비웠습니다.')
  } else {
    setFeedback('입력칸이 수동으로 수정된 상태라 AI 적용 취소로 지우지 않았습니다.')
  }
  clearReceiptOcrAppliedMarker()
}

function buildReceiptOcrDirectEntryPayload(suggestion = {}) {
  const normalizedSuggestion = normalizeOcrSuggestion(suggestion)
  const entryType = normalizedSuggestion.entryType === 'INCOME' ? 'INCOME' : 'EXPENSE'
  const amount = Number(normalizedSuggestion.amount || 0)
  const categoryGroupId = normalizedSuggestion.categoryGroupId
  if (!String(normalizedSuggestion.title || '').trim()) {
    throw new Error('제목을 확인해 주세요.')
  }
  if (!Number.isFinite(amount) || amount <= 0) {
    throw new Error('금액을 확인해 주세요.')
  }
  if (!categoryGroupId) {
    throw new Error('대분류를 선택해 주세요.')
  }
  const paymentMethodId = entryType === 'INCOME'
    ? null
    : normalizedSuggestion.paymentMethodId
  if (entryType === 'EXPENSE' && !paymentMethodId) {
    throw new Error('지출 거래의 결제수단을 선택해 주세요.')
  }
  return {
    entryDate: normalizedSuggestion.entryDate || entryForm.entryDate,
    entryTime: normalizedSuggestion.entryTime ? normalizeEntryTimePayload(normalizedSuggestion.entryTime) : '00:00',
    title: String(normalizedSuggestion.title || '').trim(),
    memo: String(normalizedSuggestion.memo || '').trim() || null,
    amount,
    foreignCurrencyCode: null,
    foreignAmount: null,
    exchangeRateToKrw: null,
    entryType,
    categoryGroupId: Number(categoryGroupId),
    categoryDetailId: normalizedSuggestion.categoryDetailId ? Number(normalizedSuggestion.categoryDetailId) : null,
    paymentMethodId: entryType === 'INCOME' ? null : Number(paymentMethodId),
    travelPlanId: null,
    travelRecordId: null,
  }
}

function buildReceiptOcrSubmittedSnapshot(payload) {
  return {
    entryDate: payload.entryDate,
    entryTime: normalizeEntryTimePayload(payload.entryTime),
    title: payload.title || '',
    memo: payload.memo || '',
    amount: String(Number(payload.amount || 0)),
    amountInput: String(Number(payload.amount || 0)),
    currencyMode: 'KRW',
    foreignCurrencyCode: 'USD',
    foreignAmount: '',
    exchangeRateToKrw: '',
    exchangeRateDate: '',
    exchangeRateProvider: '',
    entryType: payload.entryType || 'EXPENSE',
    categoryGroupId: payload.categoryGroupId != null ? String(payload.categoryGroupId) : '',
    categoryDetailId: payload.categoryDetailId != null ? String(payload.categoryDetailId) : '',
    paymentMethodId: payload.paymentMethodId != null ? String(payload.paymentMethodId) : '',
    travelPlanId: '',
    travelRecordId: '',
    isTimeEnabled: hasEntryTimeValue(payload.entryTime),
  }
}

function markReceiptOcrReviewEntryApprovedLocally(analysisId, reviewItemId, entryIndex, entryId) {
  const normalizedIndex = Number(entryIndex)
  if (!analysisId || !Number.isInteger(normalizedIndex) || normalizedIndex < 0) {
    return
  }
  const normalizedEntryId = Number(entryId)
  const updateItem = (item) => {
    const matchesAnalysis = String(item.analysisId || '') === String(analysisId)
    const matchesReviewItem = reviewItemId && String(item.id || '') === String(reviewItemId)
    if (!matchesAnalysis && !matchesReviewItem) {
      return item
    }
    return {
      ...item,
      approvedEntryIndexes: normalizeReceiptApprovedEntryIndexes([...(item.approvedEntryIndexes || []), normalizedIndex]),
      approvedEntryIds: normalizeReceiptApprovedEntryIds([
        ...(item.approvedEntryIds || []),
        Number.isFinite(normalizedEntryId) && normalizedEntryId > 0 ? normalizedEntryId : null,
      ]),
    }
  }
  receiptOcr.items = receiptOcr.items.map(updateItem)
  receiptOcr.historyItems = receiptOcr.historyItems.map((history) => {
    if (String(history.id || '') !== String(analysisId)) {
      return history
    }
    const result = history.result || {}
    return {
      ...history,
      result: {
        ...result,
        approvedEntryIndexes: normalizeReceiptApprovedEntryIndexes([...(result.approvedEntryIndexes || []), normalizedIndex]),
        approvedEntryIds: normalizeReceiptApprovedEntryIds([
          ...(result.approvedEntryIds || []),
          Number.isFinite(normalizedEntryId) && normalizedEntryId > 0 ? normalizedEntryId : null,
        ]),
      },
    }
  })
}

function mergeReceiptOcrApprovedHistory(history) {
  if (!history?.id) {
    return
  }
  const updatedItem = mapImageAnalysisHistoryToReviewItem(history)
  receiptOcr.historyItems = receiptOcr.historyItems.map((candidate) => String(candidate.id || '') === String(history.id) ? history : candidate)
  receiptOcr.items = receiptOcr.items.map((candidate) => {
    if (String(candidate.analysisId || '') !== String(updatedItem.analysisId || '')) {
      return candidate
    }
    return {
      ...candidate,
      approvedEntryIndexes: updatedItem.approvedEntryIndexes,
      approvedEntryIds: updatedItem.approvedEntryIds,
      suggestedEntries: updatedItem.suggestedEntries,
      warnings: updatedItem.warnings,
      rawText: updatedItem.rawText,
      lineItems: updatedItem.lineItems,
      confidence: updatedItem.confidence,
      vendor: updatedItem.vendor,
      paymentMethodText: updatedItem.paymentMethodText,
      categoryText: updatedItem.categoryText,
      timing: updatedItem.timing,
    }
  })
}

async function persistReceiptOcrApprovedEntry(suggestion, entryId) {
  const analysisId = suggestion?.analysisId
  const entryIndex = Number(suggestion?.reviewEntryIndex)
  if (!analysisId || !Number.isInteger(entryIndex) || entryIndex < 0) {
    return false
  }
  try {
    const history = await markLedgerImageAnalysisEntryApproved(analysisId, entryIndex, entryId)
    markReceiptOcrReviewEntryApprovedLocally(analysisId, suggestion?.reviewItemId, entryIndex, entryId)
    mergeReceiptOcrApprovedHistory(history)
    return true
  } catch (error) {
    console.warn('Failed to persist approved image analysis entry marker', error)
    return false
  }
}
async function approveReceiptOcrSuggestion(suggestion = receiptOcr.suggestedEntry) {
  if (!suggestion) {
    return
  }
  const suggestionStatus = String(suggestion.analysisStatus || '').toUpperCase()
  if (suggestionStatus && suggestionStatus !== 'COMPLETED') {
    setFeedback('', '완료된 AI 이미지 분석 결과만 승인 후 기입할 수 있습니다.')
    return
  }
  isSubmitting.value = true
  activeSubmit.value = 'receipt-approval'
  setFeedback()
  try {
    const payload = buildReceiptOcrDirectEntryPayload(suggestion)
    const createdEntry = await createEntry(payload)
    undoableEntryAction.value = {
      type: 'create',
      entryId: createdEntry?.id ?? null,
      submittedSnapshot: buildReceiptOcrSubmittedSnapshot(payload),
    }
    receiptOcr.lastAppliedAnalysisId = suggestion.analysisId || null
    receiptOcr.lastAppliedReviewItemId = suggestion.reviewItemId || null
    receiptOcr.lastAppliedReviewEntryIndex = Number.isFinite(Number(suggestion.reviewEntryIndex)) ? Number(suggestion.reviewEntryIndex) : null
    receiptOcr.lastAppliedMode = 'entry'
    receiptOcr.lastAppliedSnapshot = null
    const markerPersisted = await persistReceiptOcrApprovedEntry(suggestion, createdEntry?.id)
    await refreshLedgerViews()
    await refreshOpenLedgerChangeHistory()
    setFeedback(markerPersisted ? 'AI 분석 결과를 승인하고 거래 내역에 기입했습니다.' : '거래는 기입됐지만 분석 기록의 승인 표시를 저장하지 못했습니다.')
  } catch (error) {
    setFeedback('', error.message)
  } finally {
    isSubmitting.value = false
    activeSubmit.value = ''
  }
}
async function applyReceiptOcrSuggestion(suggestion = receiptOcr.suggestedEntry) {
  if (!suggestion) {
    return
  }

  const normalizedSuggestion = normalizeOcrSuggestion(suggestion)
  const suggestionStatus = String(suggestion.analysisStatus || '').toUpperCase()
  if (suggestionStatus && suggestionStatus !== 'COMPLETED') {
    setFeedback('', '완료된 AI 이미지 분석 결과만 입력칸에 적용할 수 있습니다.')
    return
  }
  const suggestedAmount = Number(normalizedSuggestion.amount)
  if (!String(normalizedSuggestion.title || '').trim() || !Number.isFinite(suggestedAmount) || suggestedAmount <= 0) {
    setFeedback('', '제목과 금액을 확인해야 입력칸에 적용할 수 있습니다.')
    return
  }
  receiptOcr.lastAppliedAnalysisId = suggestion.analysisId || null
  receiptOcr.lastAppliedReviewItemId = suggestion.reviewItemId || null
  receiptOcr.lastAppliedReviewEntryIndex = Number.isFinite(Number(suggestion.reviewEntryIndex)) ? Number(suggestion.reviewEntryIndex) : null
  receiptOcr.lastAppliedMode = 'form'
  receiptOcr.lastAppliedSnapshot = buildReceiptOcrAppliedSnapshot(normalizedSuggestion)
  recordReceiptOcrAppliedEntryMarker(receiptOcr.lastAppliedAnalysisId, receiptOcr.lastAppliedReviewEntryIndex)

  editingEntryId.value = null
  entryForm.entryDate = normalizedSuggestion.entryDate || entryForm.entryDate
  if (normalizedSuggestion.entryTime) {
    entryForm.entryTime = normalizeEntryTimePayload(normalizedSuggestion.entryTime)
  }
  entryForm.title = normalizedSuggestion.title || entryForm.title
  entryForm.memo = normalizedSuggestion.memo || entryForm.memo
  entryForm.entryType = normalizedSuggestion.entryType || 'EXPENSE'
  entryForm.currencyMode = 'KRW'
  clearForeignExchangeFields()

  if (normalizedSuggestion.amount !== '') {
    const nextAmount = String(Number(normalizedSuggestion.amount || 0))
    amountInput.value = nextAmount
    entryForm.amount = nextAmount
  }

  entryForm.categoryGroupId = normalizedSuggestion.categoryGroupId || entryForm.categoryGroupId
  entryForm.categoryDetailId = normalizedSuggestion.categoryDetailId || entryForm.categoryDetailId
  if (normalizedSuggestion.paymentMethodId) {
    entryForm.paymentMethodId = String(normalizedSuggestion.paymentMethodId)
  }

  isEntryTimeEnabled.value = hasEntryTimeValue(normalizedSuggestion.entryTime)
  syncEntryDefaults({ preferLatest: false, force: false })

  receiptOcr.isOpen = false
  receiptOcr.activeView = ''
  await nextTick()
  calendarWorkspaceRef.value?.scrollToEntryEditor?.()
  setFeedback('영수증 분석 결과를 빠른 거래 입력칸에 적용했습니다. 일자와 시간, 분류를 확인해 주세요.')
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
      label: `최근 6개월 - ${formatDateRange(from, today)}`,
      isAll: false,
    }
  }

  if (preset === 'LAST_1_YEAR') {
    const from = shiftIsoDate(today, { years: 1 })
    return {
      from,
      to: today,
      label: `최근 1년 - ${formatDateRange(from, today)}`,
      isAll: false,
    }
  }

  if (preset === 'LAST_3_YEARS') {
    const from = shiftIsoDate(today, { years: 3 })
    return {
      from,
      to: today,
      label: `최근 3년 - ${formatDateRange(from, today)}`,
      isAll: false,
    }
  }

  if (preset === 'CUSTOM') {
    return {
      from: csvExportControls.customFrom,
      to: csvExportControls.customTo,
      label: `직접 선택 - ${formatDateRange(csvExportControls.customFrom, csvExportControls.customTo)}`,
      isAll: false,
    }
  }

  return {
    from: currentViewCsvRange.value.from,
    to: currentViewCsvRange.value.to,
    label: `현재 조회 범위 - ${formatDateRange(currentViewCsvRange.value.from, currentViewCsvRange.value.to)}`,
    isAll: false,
  }
}
async function refreshLedgerViews({ forceStatistics = false } = {}) {
  await loadEntryDateRange()
  const tasks = [loadCalendarData()]
  if (forceStatistics || shouldLoadStatisticsForTab()) {
    tasks.push(loadStatisticsData())
  }
  await Promise.all(tasks)
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
      throw new Error('CSV 조회 범위를 먼저 확인해 주세요.')
    }
    await downloadLedgerCsv(csvExportRange.value.from, csvExportRange.value.to)
    setFeedback(`현재 로그인에 사용하는 2차 비밀번호로 보호한 CSV 압축 파일을 생성했습니다. (${csvExportLabel.value})`)
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
    await loadCalendarAggregateEntries()
    setFeedback('사용자 지정 집계를 저장했습니다.')
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
      await ensureForeignExchangeRateLoaded()
      syncForeignKrwAmount()
      if (!entryForm.foreignAmount || Number(entryForm.foreignAmount) <= 0) {
        throw new Error('외화 금액을 입력해 주세요.')
      }
      if (!entryForm.exchangeRateToKrw || Number(entryForm.exchangeRateToKrw) <= 0) {
        throw new Error('환율 정보를 불러온 뒤 다시 시도해 주세요.')
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
    setFeedback('', '변경할 분류나 결제수단을 선택해 주세요.')
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
  if (!window.confirm('휴지통을 비우면 다시 복구할 수 없습니다. 정말 비우시겠습니까?')) {
    return
  }

  isSubmitting.value = true
  activeSubmit.value = 'entry-empty-trash'
  setFeedback()
  try {
    await emptyDeletedEntries()
    await refreshLedgerViews()
    setFeedback('휴지통을 비웠습니다. 휴지통 가계부 내역은 다시 복구할 수 없습니다.')
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
  const source = managementCategories.value.length ? managementCategories.value : categories.value
  return source.some((group) =>
    group.entryType === entryType && normalizeManagementName(group.name) === normalizedName,
  )
}

function hasDuplicateDetailName(name, groupId) {
  const source = managementCategories.value.length ? managementCategories.value : categories.value
  const group = source.find((item) => String(item.id) === String(groupId))
  const normalizedName = normalizeManagementName(name)
  return Boolean(group?.details?.some((detail) => normalizeManagementName(detail.name) === normalizedName))
}

function hasDuplicatePaymentMethodName(name) {
  const normalizedName = normalizeManagementName(name)
  const source = managementPaymentMethods.value.length ? managementPaymentMethods.value : paymentMethods.value
  return source.some((payment) => normalizeManagementName(payment.name) === normalizedName)
}

function resetClassificationDeleteModal() {
  classificationDeleteModal.isOpen = false
  classificationDeleteModal.isLoading = false
  classificationDeleteModal.isSubmitting = false
  classificationDeleteModal.type = ''
  classificationDeleteModal.target = null
  classificationDeleteModal.usage = null
  classificationDeleteModal.error = ''
  classificationDeleteModal.replacementCategoryGroupId = ''
  classificationDeleteModal.replacementCategoryDetailId = ''
  classificationDeleteModal.replacementPaymentMethodId = ''
}

async function openClassificationDeleteModal(type, target) {
  resetClassificationDeleteModal()
  classificationDeleteModal.isOpen = true
  classificationDeleteModal.isLoading = true
  classificationDeleteModal.type = type
  classificationDeleteModal.target = target

  try {
    if (type === 'group') {
      classificationDeleteModal.usage = await fetchCategoryGroupUsage(target.id)
    } else if (type === 'detail') {
      classificationDeleteModal.usage = await fetchCategoryDetailUsage(target.id)
    } else if (type === 'payment') {
      classificationDeleteModal.usage = await fetchPaymentMethodUsage(target.id)
    }
  } catch (error) {
    classificationDeleteModal.error = error.message || '연결 데이터를 불러오지 못했습니다.'
  } finally {
    classificationDeleteModal.isLoading = false
  }
}

function openGroupDelete(group) {
  openClassificationDeleteModal('group', {
    id: group.id,
    name: group.name,
    entryType: group.entryType,
  })
}

function openDetailDelete(payload) {
  const detail = payload?.detail
  const group = payload?.group
  if (!detail || !group) {
    return
  }
  openClassificationDeleteModal('detail', {
    id: detail.id,
    name: detail.name,
    groupId: group.id,
    groupName: group.name,
    entryType: group.entryType,
  })
}

function openPaymentDelete(payment) {
  openClassificationDeleteModal('payment', {
    id: payment.id,
    name: payment.name,
  })
}

function buildClassificationDeletePayload() {
  if (classificationDeleteModal.type === 'group') {
    return {
      replacementCategoryGroupId: toOptionalNumber(classificationDeleteModal.replacementCategoryGroupId),
      replacementCategoryDetailId: toOptionalNumber(classificationDeleteModal.replacementCategoryDetailId),
    }
  }
  if (classificationDeleteModal.type === 'detail') {
    return {
      replacementCategoryDetailId: toOptionalNumber(classificationDeleteModal.replacementCategoryDetailId),
    }
  }
  if (classificationDeleteModal.type === 'payment') {
    return {
      replacementPaymentMethodId: toOptionalNumber(classificationDeleteModal.replacementPaymentMethodId),
    }
  }
  return {}
}

async function confirmClassificationDelete() {
  if (!classificationDeleteModal.target || classificationDeleteModal.isSubmitting) {
    return
  }

  classificationDeleteModal.isSubmitting = true
  classificationDeleteModal.error = ''
  setFeedback()
  try {
    const payload = buildClassificationDeletePayload()
    if (classificationDeleteModal.type === 'group') {
      await deleteCategoryGroupPermanently(classificationDeleteModal.target.id, payload)
    } else if (classificationDeleteModal.type === 'detail') {
      await deleteCategoryDetailPermanently(classificationDeleteModal.target.id, payload)
    } else if (classificationDeleteModal.type === 'payment') {
      await deletePaymentMethodPermanently(classificationDeleteModal.target.id, payload)
    }
    resetClassificationDeleteModal()
    await Promise.all([loadMetadata(), refreshLedgerViews(), loadAggregatePreferences()])
    setFeedback('분류 태그를 삭제하고 연결된 거래를 정리했습니다.')
  } catch (error) {
    classificationDeleteModal.error = error.message || '분류 태그를 삭제하지 못했습니다.'
  } finally {
    classificationDeleteModal.isSubmitting = false
  }
}

async function createGroup() {
  const name = groupForm.name.trim()
  if (!name) return
  if (hasDuplicateGroupName(name, groupForm.entryType)) {
    setFeedback('', '숨김 또는 비활성 분류입니다. 숨겨진 분류는 분류 수정하기에서 복구하세요.')
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
    setFeedback('', '숨김 또는 비활성 분류입니다. 숨겨진 분류는 분류 수정하기에서 복구하세요.')
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
    setFeedback('소분류 카테고리를 추가했습니다.')
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
    setFeedback('', '숨김 또는 비활성 결제수단입니다. 숨겨진 결제수단은 분류 수정하기에서 복구하세요.')
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

async function activateGroup(groupId) {
  isSubmitting.value = true
  activeSubmit.value = 'group'
  setFeedback()
  try {
    await activateCategoryGroup(groupId)
    await Promise.all([loadMetadata(), refreshLedgerViews()])
    setFeedback('카테고리 그룹을 복구했습니다.')
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
    setFeedback('소분류 카테고리를 비활성화했습니다.')
  } catch (error) {
    setFeedback('', error.message)
  } finally {
    isSubmitting.value = false
    activeSubmit.value = ''
  }
}

async function activateDetail(detailId) {
  isSubmitting.value = true
  activeSubmit.value = 'detail'
  setFeedback()
  try {
    await activateCategoryDetail(detailId)
    await Promise.all([loadMetadata(), refreshLedgerViews()])
    setFeedback('소분류 카테고리를 복구했습니다.')
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

async function activatePayment(paymentId) {
  isSubmitting.value = true
  activeSubmit.value = 'payment'
  setFeedback()
  try {
    await activatePaymentMethod(paymentId)
    await Promise.all([loadMetadata(), refreshLedgerViews(), loadAggregatePreferences()])
    setFeedback('결제수단을 복구했습니다.')
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
        </div>
        <span class="panel__badge">{{ isLoading ? '불러오는 중' : '준비됨' }}</span>
      </div>

      <div class="household-anchor-toolbar">
        <div class="household-anchor-toolbar__meta">
          <strong>기준 날짜</strong>
        </div>
        <div class="household-anchor-toolbar__actions">
          <label class="field">
            <span class="field__label">조회 기준일</span>
            <input :value="householdAnchorDate" type="date" @input="handleChangeHouseholdAnchorDate($event.target.value)" />
          </label>
          <button class="button button--secondary" @click="handleChangeHouseholdAnchorDate(today)">오늘</button>
        </div>
      </div>

      <div class="scope-toggle scope-toggle--wrap">
        <button class="button" :class="{ 'button--primary': householdTab === 'dashboard' }" @click="setHouseholdTab('dashboard')">대시보드</button>
        <button class="button" :class="{ 'button--primary': householdTab === 'calendar' }" @click="setHouseholdTab('calendar')">달력 가계부</button>
        <button class="button" :class="{ 'button--primary': householdTab === 'travel-ledger' }" @click="setHouseholdTab('travel-ledger')">여행 가계부</button>
        <button class="button" :class="{ 'button--primary': householdTab === 'ledger-analysis' }" @click="setHouseholdTab('ledger-analysis')">가계부 분석</button>
        <button class="button" :class="{ 'button--primary': householdTab === 'stats-search' }" @click="setHouseholdTab('stats-search')">검색</button>
        <button class="button" :class="{ 'button--primary': householdTab === 'stats-trash' }" @click="setHouseholdTab('stats-trash')">휴지통</button>
        <div ref="dataActionMenuRef" class="household-data-actions">
          <button
            class="button"
            :class="{ 'button--primary': householdTab === 'import' || dataActionMenuOpen }"
            @click.stop="toggleDataActionMenu"
          >
            Data
          </button>
          <div v-if="dataActionMenuOpen" class="household-data-actions__menu" @click.stop>
            <button type="button" class="button button--ghost household-data-actions__menu-button" @click="openImportWorkspace">
              CSV 가져오기
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
              {{ isSubmitting && activeSubmit === 'export-csv' ? 'CSV 내보내는 중...' : `CSV 내보내기 (${csvExportLabel})` }}
            </button>
          </div>
        </div>
        <button class="button" :class="{ 'button--primary': householdTab === 'management' }" @click="setHouseholdTab('management')">분류 관리</button>
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
      :aggregate-entries="calendarAggregateEntries"
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
      @set-receipt-ocr-view="setReceiptOcrView"
      @set-receipt-request-prompt-enabled="setReceiptRequestPromptEnabled"
      @set-receipt-request-prompt="setReceiptRequestPrompt"
      @set-receipt-existing-entry-style-enabled="setReceiptExistingEntryStyleEnabled"
      @set-receipt-rerun-prompt-enabled="setReceiptRerunPromptEnabled"
      @set-receipt-rerun-prompt="setReceiptRerunPrompt"
      @set-receipt-prompt-rules-enabled="setReceiptPromptRulesEnabled"
      @set-receipt-prompt-rules="setReceiptPromptRules"
      @update:amount-input="handleAmountInput"
      @update:time-enabled="updateTimeEnabled"
      @fill-amount="fillAmount"
      @add-amount="addAmount"
      @select-receipt-files="selectReceiptOcrFiles"
      @start-receipt-analysis="startSelectedReceiptOcrAnalysis"
      @analyze-receipt="analyzeReceiptImage"
      @update-receipt-item-prompt="updateReceiptOcrItemPrompt"
      @update-receipt-review-entry="updateReceiptOcrReviewEntry"
      @remove-receipt-analysis="removeReceiptOcrItem"
      @apply-receipt-suggestion="applyReceiptOcrSuggestion"
      @approve-receipt-suggestion="approveReceiptOcrSuggestion"
      @rerun-receipt-analysis="rerunReceiptOcrItem"
      @clear-receipt-analysis="clearReceiptOcr"
      @cancel-receipt-applied="cancelReceiptOcrAppliedSuggestion"
      @load-receipt-history="loadReceiptOcrHistories"
      @reuse-receipt-history="reuseReceiptOcrHistory"
      @open-receipt-history-detail="openReceiptOcrHistoryDetail"
      @close-receipt-history-detail="closeReceiptOcrHistoryDetail"
      @cancel-receipt-history="cancelReceiptOcrHistory"
      @delete-receipt-history="deleteReceiptOcrHistory"
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
      @open-travel-record-location="openTravelRecordLocation"
    />

    <template v-else-if="isStatisticsWorkspaceVisible">
      <section v-if="householdTab === 'ledger-analysis'" class="panel panel--compact household-analysis-nav">
        <div class="panel__header">
          <div class="household-analysis-nav__copy">
            <h2>가계부 분석</h2>
          </div>
        </div>
        <div class="scope-toggle scope-toggle--wrap household-analysis-nav__tabs" aria-label="가계부 분석 보기">
          <button
            v-for="tab in householdAnalysisTabs"
            :key="tab.key"
            class="button"
            :class="{ 'button--primary': householdAnalysisRoute === tab.key }"
            type="button"
            @click="setHouseholdAnalysisRoute(tab.key)"
          >
            {{ tab.label }}
          </button>
        </div>
      </section>

      <StatisticsWorkspace
        :route="statisticsWorkspaceRoute"
      :stats-controls="statsControls"
      :search-form="searchForm"
      :search-keyword-draft="searchKeywordDraft"
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
      :ai-analysis-controls="aiAnalysisControls"
      :ai-analysis-history-filters="aiAnalysisHistoryFilters"
      :ai-analysis-status="aiAnalysisStatus"
      :ai-analysis="aiAnalysis"
      :ai-analysis-loading="aiAnalysisLoading"
      :ai-analysis-error="aiAnalysisError"
      :ai-analysis-stale="aiAnalysisStale"
      :ai-analysis-history-page="aiAnalysisHistoryPage"
      :ai-analysis-history-loading="aiAnalysisHistoryLoading"
      :ai-analysis-history-error="aiAnalysisHistoryError"
      :ai-analysis-modal-request-key="aiAnalysisModalRequestKey"
      @update-search-keyword-draft="updateSearchKeywordDraft"
      @submit-search="submitSearch"
      @change-search-page="loadSearchResults"
      @change-trash-page="loadTrashResults"
      @move-search-entry="moveEntryFromSearch"
      @save-search-entry="saveEntryFromSearch"
      @bulk-update-search-entries="bulkUpdateSearchEntries"
      @delete-search-entry="deleteEntryFromSearch"
      @restore-trash-entry="restoreEntryFromTrash"
      @empty-trash="emptyTrash"
      @request-ai-analysis="requestAiAnalysis"
      @load-latest-ai-analysis="loadLatestAiAnalysis"
      @load-ai-analysis-history="loadAiAnalysisHistory"
      @open-ai-analysis-history="openAiAnalysisHistory"
      @rerun-ai-analysis="rerunAiAnalysis"
      @delete-ai-analysis-history="deleteAiAnalysisHistory"
      />
    </template>

    <LedgerImportWorkspace
      v-else-if="householdTab === 'import'"
      @imported="handleImported"
    />

    <ManagementWorkspace
      v-else
      :categories="categories"
      :payment-methods="paymentMethods"
      :management-categories="managementCategories"
      :management-payment-methods="managementPaymentMethods"
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
      @activate-group="activateGroup"
      @activate-detail="activateDetail"
      @activate-payment="activatePayment"
      @delete-group="openGroupDelete"
      @delete-detail="openDetailDelete"
      @delete-payment="openPaymentDelete"
    />

    <div
      v-if="classificationDeleteModal.isOpen"
      class="travel-modal classification-delete-modal"
      @click.self="resetClassificationDeleteModal"
    >
      <section class="travel-modal__dialog classification-delete-modal__dialog" role="dialog" aria-modal="true" aria-labelledby="classification-delete-title">
        <div class="travel-modal__header">
          <div>
            <h2 id="classification-delete-title">{{ classificationDeleteTypeLabel }} 삭제</h2>
            <p>{{ classificationDeleteTargetLabel }} 태그를 삭제하기 전에 연결된 거래를 확인합니다.</p>
          </div>
          <button class="button button--ghost" type="button" :disabled="classificationDeleteModal.isSubmitting" @click="resetClassificationDeleteModal">
            닫기
          </button>
        </div>

        <div v-if="classificationDeleteModal.error" class="feedback feedback--error">{{ classificationDeleteModal.error }}</div>

        <div class="travel-modal__body">
          <div v-if="classificationDeleteModal.isLoading" class="classification-delete-modal__empty">
            연결 데이터를 불러오는 중입니다.
          </div>

          <template v-else>
            <div class="classification-delete-modal__summary">
              <strong>{{ classificationDeleteModal.usage?.totalCount ?? 0 }}건 연결</strong>
              <span>
                {{ classificationDeleteModal.usage?.totalCount ? '삭제하면 아래 거래가 미분류로 이동합니다.' : '연결된 거래가 없어 바로 삭제할 수 있습니다.' }}
              </span>
            </div>

            <div v-if="classificationDeleteModal.usage?.entries?.length" class="classification-delete-modal__list">
              <article
                v-for="entry in classificationDeleteModal.usage.entries"
                :key="entry.id"
                class="classification-delete-modal__entry"
              >
                <div>
                  <strong>{{ entry.title }}</strong>
                  <span>
                    {{ formatShortDate(entry.entryDate) }}
                    <template v-if="entry.entryTime"> {{ entry.entryTime }}</template>
                    - {{ entry.entryType === 'INCOME' ? '수입' : '지출' }}
                    <template v-if="entry.deleted"> - 삭제됨</template>
                  </span>
                </div>
                <div>
                  <strong>{{ formatCurrency(entry.amount) }}</strong>
                  <span>{{ entry.categoryGroupName }} / {{ entry.categoryDetailName || '미분류' }} - {{ entry.paymentMethodName }}</span>
                </div>
              </article>
              <p v-if="classificationDeleteModal.usage?.hasMore" class="classification-delete-modal__hint">
                최근 거래 {{ classificationDeleteModal.usage.entries.length }}건만 표시합니다.
              </p>
            </div>

            <div class="classification-delete-modal__replacement">
              <template v-if="classificationDeleteModal.type === 'group'">
                <label class="field">
                  <span class="field__label">대체 대분류</span>
                  <select v-model="classificationDeleteModal.replacementCategoryGroupId">
                    <option value="">미분류로 처리</option>
                    <option v-for="group in classificationDeleteReplacementGroups" :key="group.id" :value="String(group.id)">
                      {{ group.entryType === 'INCOME' ? '수입' : '지출' }} / {{ group.name }}
                    </option>
                  </select>
                </label>
                <label class="field">
                  <span class="field__label">대체 소분류</span>
                  <select v-model="classificationDeleteModal.replacementCategoryDetailId" :disabled="!classificationDeleteModal.replacementCategoryGroupId">
                    <option value="">소분류 없음</option>
                    <option v-for="detail in classificationDeleteReplacementDetails" :key="detail.id" :value="String(detail.id)">
                      {{ detail.name }}
                    </option>
                  </select>
                </label>
              </template>

              <template v-else-if="classificationDeleteModal.type === 'detail'">
                <label class="field">
                  <span class="field__label">대체 소분류</span>
                  <select v-model="classificationDeleteModal.replacementCategoryDetailId">
                    <option value="">소분류 없음</option>
                    <option v-for="detail in classificationDeleteReplacementDetails" :key="detail.id" :value="String(detail.id)">
                      {{ detail.name }}
                    </option>
                  </select>
                </label>
              </template>

              <template v-else-if="classificationDeleteModal.type === 'payment'">
                <label class="field">
                  <span class="field__label">대체 결제수단</span>
                  <select v-model="classificationDeleteModal.replacementPaymentMethodId">
                    <option value="">미분류로 처리</option>
                    <option v-for="payment in classificationDeleteReplacementPayments" :key="payment.id" :value="String(payment.id)">
                      {{ payment.name }}
                    </option>
                  </select>
                </label>
              </template>
            </div>
          </template>
        </div>

        <div class="travel-modal__footer">
          <button class="button button--ghost" type="button" :disabled="classificationDeleteModal.isSubmitting" @click="resetClassificationDeleteModal">
            취소
          </button>
          <button
            class="button button--danger"
            type="button"
            :disabled="classificationDeleteModal.isLoading || classificationDeleteModal.isSubmitting"
            @click="confirmClassificationDelete"
          >
            {{ classificationDeleteModal.isSubmitting ? '삭제 중...' : '그래도 삭제' }}
          </button>
        </div>
      </section>
    </div>

    <div class="household-floating-tools" aria-label="빠른 이동">
      <button class="household-floating-tools__button" type="button" title="위로 이동" @click="scrollHouseholdToTop">
        위
      </button>
      <button class="household-floating-tools__button" type="button" title="아래로 이동" @click="scrollHouseholdToBottom">
        아래
      </button>
      <button class="household-floating-tools__button household-floating-tools__button--accent" type="button" title="변경 이력 열기" @click="openLedgerChangeHistoryModal">
        이력
      </button>
    </div>

    <div v-if="ledgerChangeHistory.isOpen" class="travel-modal ledger-history-modal" @click.self="closeLedgerChangeHistoryModal">
      <section class="travel-modal__dialog ledger-history-modal__dialog" role="dialog" aria-modal="true" aria-labelledby="ledger-history-title">
        <div class="travel-modal__header ledger-history-modal__header">
          <div>
            <span class="ledger-history-modal__eyebrow">CHANGE HISTORY</span>
            <h2 id="ledger-history-title">수정 이력 확인</h2>
            <p>검색 화면과 일괄 변경에서 수정한 거래를 확인하고 변경 전 상태로 복구할 수 있습니다.</p>
          </div>
          <button class="button button--ghost" type="button" @click="closeLedgerChangeHistoryModal">닫기</button>
        </div>

        <div v-if="ledgerChangeHistory.error" class="feedback feedback--error">{{ ledgerChangeHistory.error }}</div>

        <div class="ledger-history-modal__body">
          <aside class="ledger-history-list" aria-label="수정 이력 목록">
            <div class="ledger-history-list__toolbar">
              <strong>{{ ledgerChangeHistory.totalElements }} items</strong>
              <span>{{ ledgerChangeHistory.page + 1 }} / {{ ledgerChangeHistoryPageLabel }}</span>
            </div>

            <div v-if="ledgerChangeHistory.isLoading" class="ledger-history-empty">변경 이력을 불러오는 중입니다.</div>
            <div v-else-if="!ledgerChangeHistory.content.length" class="ledger-history-empty">저장된 변경 이력이 없습니다.</div>
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
                Previous
              </button>
              <button class="button button--ghost" type="button" :disabled="ledgerChangeHistory.page + 1 >= ledgerChangeHistoryPageLabel || ledgerChangeHistory.isLoading" @click="loadLedgerChangeHistories(ledgerChangeHistory.page + 1)">
                Next
              </button>
            </div>
          </aside>

          <section class="ledger-history-detail" aria-label="변경 이력 상세">
            <div v-if="ledgerChangeHistory.isDetailLoading" class="ledger-history-empty">변경 상세를 불러오는 중입니다.</div>
            <div v-else-if="!ledgerChangeHistory.selected" class="ledger-history-empty">확인할 변경 이력을 선택하세요.</div>
            <template v-else>
              <div class="ledger-history-detail__header">
                <div>
                  <strong>{{ ledgerChangeHistory.selected.summary }}</strong>
                  <span>{{ formatLedgerChangeDate(ledgerChangeHistory.selected.createdAt) }} - {{ ledgerChangeHistory.selected.entryCount }}건</span>
                </div>
                <button class="button button--primary" type="button" :disabled="ledgerChangeHistory.isRestoring" @click="restoreLedgerChangeHistoryPoint(ledgerChangeHistory.selected)">
                  {{ ledgerChangeHistory.isRestoring ? '복구 중...' : '이 시점으로 복구' }}
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
                        <strong>-&gt;</strong>
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
