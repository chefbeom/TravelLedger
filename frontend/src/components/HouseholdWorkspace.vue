<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import {
  createCategoryDetail,
  createCategoryGroup,
  createEntry,
  createPaymentMethod,
  deactivateCategoryDetail,
  deactivateCategoryGroup,
  deactivatePaymentMethod,
  deleteEntry,
  downloadLedgerCsv,
  fetchCategories,
  fetchCategoryBreakdown,
  fetchCompare,
  fetchDashboard,
  fetchEntryDateRange,
  fetchEntries,
  fetchHouseholdAggregatePreferences,
  fetchOverview,
  fetchPaymentBreakdown,
  fetchPaymentMethods,
  saveHouseholdAggregatePreferences,
  updateEntry,
} from '../lib/api'
import {
  buildCalendarWeeks,
  formatCurrency,
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
  filterEntries,
  getDefaultTimeValue,
  getPresetOptions,
  resolveRange,
  shiftRange,
  summarizeEntries,
} from '../lib/analytics'
import CalendarWorkspace from './CalendarWorkspace.vue'
import LedgerImportWorkspace from './LedgerImportWorkspace.vue'
import ManagementWorkspace from './ManagementWorkspace.vue'
import StatisticsWorkspace from './StatisticsWorkspace.vue'

const compareUnitLabels = {
  DAY: '일간',
  WEEK: '주간',
  MONTH: '월간',
  QUARTER: '분기',
  YEAR: '연간',
}

const today = toIsoDate(new Date())
const quickAmountButtons = [10000, 30000, 50000, 100000]
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
const householdTab = ref('calendar')
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
let feedbackTimerId = null

const entryForm = reactive({
  entryDate: today,
  entryTime: getDefaultTimeValue(),
  title: '',
  memo: '',
  amount: '',
  entryType: 'EXPENSE',
  categoryGroupId: '',
  categoryDetailId: '',
  paymentMethodId: '',
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
const amountPreview = computed(() => Number(entryForm.amount || 0))
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
const searchResults = computed(() => filterEntries(statsEntries.value, searchForm))
const searchSummary = computed(() => summarizeEntries(searchResults.value))
const insights = computed(() => buildInsights(statsEntries.value))
const isEditingEntry = computed(() => editingEntryId.value !== null)
const entrySuggestions = computed(() => {
  const keyword = entryForm.title.trim().toLowerCase()
  const baseEntries = [
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
      const target = [entry.title, entry.memo, entry.categoryGroupName, entry.categoryDetailName, entry.paymentMethodName]
        .filter(Boolean)
        .join(' ')
        .toLowerCase()

      if (keyword && !target.includes(keyword)) {
        return
      }

      const signature = [
        entry.entryType,
        entry.title || '',
        entry.memo || '',
        entry.amount || 0,
        entry.categoryGroupId || '',
        entry.categoryDetailId || '',
        entry.paymentMethodId || '',
      ].join('|')

      if (seen.has(signature)) {
        return
      }

      seen.add(signature)
      suggestions.push({
        id: entry.id,
        entryDate: entry.entryDate,
        entryTime: entry.entryTime || '00:00',
        title: entry.title || '',
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

  return suggestions.slice(0, keyword ? 6 : 4)
})

watch(
  () => entryForm.entryType,
  () => {
    if (!availableGroups.value.some((item) => String(item.id) === String(entryForm.categoryGroupId))) {
      entryForm.categoryGroupId = availableGroups.value[0] ? String(availableGroups.value[0].id) : ''
    }
    if (!availableDetails.value.some((item) => String(item.id) === String(entryForm.categoryDetailId))) {
      entryForm.categoryDetailId = availableDetails.value[0] ? String(availableDetails.value[0].id) : ''
    }
  },
)

watch(
  () => entryForm.categoryGroupId,
  () => {
    if (!availableDetails.value.some((item) => String(item.id) === String(entryForm.categoryDetailId))) {
      entryForm.categoryDetailId = availableDetails.value[0] ? String(availableDetails.value[0].id) : ''
    }
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

onMounted(async () => {
  isLoading.value = true
  try {
    await loadMetadata()
    await loadEntryDateRange()
    await Promise.all([loadCalendarData(), loadStatisticsData(), loadAggregatePreferences()])
    resetEntryForm()
    calendarReady.value = true
    statsReady.value = true
  } catch (error) {
    setFeedback('', error.message)
  } finally {
    isLoading.value = false
  }
})

onBeforeUnmount(() => {
  if (feedbackTimerId) {
    window.clearTimeout(feedbackTimerId)
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

function sanitizeAmountInput(value) {
  return String(value || '').replace(/[^0-9]/g, '')
}

function syncEntryDefaults() {
  if (!availableGroups.value.some((item) => String(item.id) === String(entryForm.categoryGroupId))) {
    entryForm.categoryGroupId = availableGroups.value[0] ? String(availableGroups.value[0].id) : ''
  }
  if (!availableDetails.value.some((item) => String(item.id) === String(entryForm.categoryDetailId))) {
    entryForm.categoryDetailId = availableDetails.value[0] ? String(availableDetails.value[0].id) : ''
  }
  if (!paymentMethods.value.some((item) => String(item.id) === String(entryForm.paymentMethodId))) {
    entryForm.paymentMethodId = paymentMethods.value[0] ? String(paymentMethods.value[0].id) : ''
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

  if (statsControls.preset === 'ALL' && entryDateRange.value.latestDate) {
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
  const [overview, categoryItems, paymentItems, compareItems, entryItems] = await Promise.all([
    fetchOverview(range.from, range.to),
    fetchCategoryBreakdown(range.from, range.to, 'EXPENSE'),
    fetchPaymentBreakdown(range.from, range.to),
    fetchCompare(comparisonAnchorDate.value, statsControls.compareUnit, statsControls.comparePeriods),
    fetchEntries(range.from, range.to),
  ])

  statsOverview.value = overview
  expenseBreakdown.value = categoryItems
  paymentBreakdown.value = paymentItems
  comparisonRows.value = compareItems
  statsEntries.value = entryItems
  await loadPastComparisons()
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

function resetEntryForm() {
  editingEntryId.value = null
  entryForm.entryDate = calendarAnchorDate.value
  entryForm.entryTime = '00:00'
  entryForm.title = ''
  entryForm.memo = ''
  entryForm.amount = ''
  entryForm.entryType = 'EXPENSE'
  amountInput.value = ''
  isEntryTimeEnabled.value = false
  syncEntryDefaults()
}

function fillEntryForm(entry) {
  editingEntryId.value = entry.id
  entryForm.entryDate = entry.entryDate
  entryForm.entryTime = entry.entryTime || '00:00'
  entryForm.title = entry.title || ''
  entryForm.memo = entry.memo || ''
  entryForm.amount = String(Number(entry.amount || 0))
  entryForm.entryType = entry.entryType
  entryForm.categoryGroupId = String(entry.categoryGroupId)
  entryForm.categoryDetailId = entry.categoryDetailId != null ? String(entry.categoryDetailId) : ''
  entryForm.paymentMethodId = String(entry.paymentMethodId)
  amountInput.value = String(Number(entry.amount || 0))
  isEntryTimeEnabled.value = Boolean(entry.entryTime && entry.entryTime !== '00:00')
}

async function fillEntryFormAndScroll(entry) {
  fillEntryForm(entry)
  await nextTick()
  calendarWorkspaceRef.value?.scrollToEntryEditor?.()
}

function applyEntrySuggestion(suggestion) {
  editingEntryId.value = null
  entryForm.title = suggestion.title || ''
  entryForm.memo = suggestion.memo || ''
  entryForm.amount = String(Number(suggestion.amount || 0))
  entryForm.entryType = suggestion.entryType || entryForm.entryType
  amountInput.value = String(Number(suggestion.amount || 0))
  entryForm.categoryGroupId = suggestion.categoryGroupId || ''
  entryForm.categoryDetailId = suggestion.categoryDetailId || ''
  entryForm.paymentMethodId = suggestion.paymentMethodId || ''

  if (isEntryTimeEnabled.value) {
    entryForm.entryTime = suggestion.entryTime || '00:00'
  }

  syncEntryDefaults()
}

function buildEntryPayload() {
  return {
    entryDate: entryForm.entryDate,
    entryTime: isEntryTimeEnabled.value ? (entryForm.entryTime || '00:00') : '00:00',
    title: entryForm.title.trim(),
    memo: entryForm.memo.trim() || null,
    amount: Number(entryForm.amount || 0),
    entryType: entryForm.entryType,
    categoryGroupId: Number(entryForm.categoryGroupId),
    categoryDetailId: entryForm.categoryDetailId ? Number(entryForm.categoryDetailId) : null,
    paymentMethodId: Number(entryForm.paymentMethodId),
  }
}

function handleAmountInput(value) {
  const digits = sanitizeAmountInput(value)
  amountInput.value = digits
  entryForm.amount = digits ? String(Number(digits)) : ''
}

function fillAmount(value) {
  amountInput.value = String(Number(value || 0))
  entryForm.amount = amountInput.value
}

function addAmount(value) {
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
  setFeedback()
  try {
    if (!csvExportRange.value.isAll && (!csvExportRange.value.from || !csvExportRange.value.to)) {
      throw new Error('CSV 저장 범위를 먼저 확인해 주세요.')
    }
    const secondaryPin = window.prompt('CSV를 내려받으려면 2차 비밀번호 8자리를 입력하세요.')
    if (secondaryPin === null) {
      return
    }
    if (!/^\d{8}$/.test(secondaryPin.trim())) {
      throw new Error('2차 비밀번호는 숫자 8자리여야 합니다.')
    }
    await downloadLedgerCsv(csvExportRange.value.from, csvExportRange.value.to, secondaryPin.trim())
    setFeedback(`2차 비밀번호로 보호된 CSV 압축 파일을 저장했습니다. (${csvExportLabel.value})`)
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
    if (editingEntryId.value) {
      await updateEntry(editingEntryId.value, buildEntryPayload())
      setFeedback('가계부 내역을 수정했습니다.')
    } else {
      await createEntry(buildEntryPayload())
      setFeedback('가계부 내역을 등록했습니다.')
    }
    await refreshLedgerViews()
    resetEntryForm()
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

async function createGroup() {
  if (!groupForm.name.trim()) return
  isSubmitting.value = true
  activeSubmit.value = 'group'
  setFeedback()
  try {
    await createCategoryGroup({
      entryType: groupForm.entryType,
      name: groupForm.name.trim(),
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
  if (!detailForm.groupId || !detailForm.name.trim()) return
  isSubmitting.value = true
  activeSubmit.value = 'detail'
  setFeedback()
  try {
    await createCategoryDetail({
      groupId: Number(detailForm.groupId),
      name: detailForm.name.trim(),
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
  if (!paymentForm.name.trim()) return
  isSubmitting.value = true
  activeSubmit.value = 'payment'
  setFeedback()
  try {
    await createPaymentMethod({
      kind: paymentForm.kind,
      name: paymentForm.name.trim(),
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
    <div v-if="feedback" class="feedback feedback--success">{{ feedback }}</div>
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
          <button class="button button--secondary" :disabled="isSubmitting" @click="exportEntriesToCsv">
            {{ isSubmitting && activeSubmit === 'export-csv' ? 'CSV 저장 중...' : `CSV 저장 (${csvExportLabel})` }}
          </button>
        </div>
      </div>

      <div class="scope-toggle scope-toggle--wrap">
        <button class="button" :class="{ 'button--primary': householdTab === 'calendar' }" @click="householdTab = 'calendar'">달력 가계부</button>
        <button class="button" :class="{ 'button--primary': householdTab === 'stats-overview' }" @click="householdTab = 'stats-overview'">통계 요약</button>
        <button class="button" :class="{ 'button--primary': householdTab === 'stats-search' }" @click="householdTab = 'stats-search'">검색</button>
        <button class="button" :class="{ 'button--primary': householdTab === 'stats-insights' }" @click="householdTab = 'stats-insights'">인사이트</button>
        <button class="button" :class="{ 'button--primary': householdTab === 'stats-compare' }" @click="householdTab = 'stats-compare'">비교</button>
        <button class="button" :class="{ 'button--primary': householdTab === 'import' }" @click="householdTab = 'import'">엑셀 가져오기</button>
        <button class="button" :class="{ 'button--primary': householdTab === 'management' }" @click="householdTab = 'management'">분류 관리</button>
      </div>
    </section>

    <CalendarWorkspace
      ref="calendarWorkspaceRef"
      v-if="householdTab === 'calendar'"
      :quick-stats="quickStats"
      :month-label="monthLabel"
      :anchor-date="calendarAnchorDate"
      :weekday-labels="weekdayLabels"
      :calendar-weeks="calendarWeeks"
      :entries="sortedMonthEntries"
      :entry-form="entryForm"
      :is-editing-entry="isEditingEntry"
      :is-submitting="isSubmitting"
      :active-submit="activeSubmit"
      :available-groups="availableGroups"
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
      :format-amount-shortcut="formatAmountShortcut"
      :format-currency="formatCurrency"
      :format-short-date="formatShortDate"
      :format-time="formatTime"
      @update:amount-input="handleAmountInput"
      @update:time-enabled="updateTimeEnabled"
      @fill-amount="fillAmount"
      @add-amount="addAmount"
      @submit-entry="submitEntry"
      @reset-entry="resetEntryForm"
      @edit-entry="fillEntryFormAndScroll"
      @delete-entry="removeEntry"
      @apply-entry-suggestion="applyEntrySuggestion"
      @change-anchor-month="handleChangeCalendarMonth"
      @save-aggregate-widget-configs="updateAggregatePreferences"
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
      :search-summary="searchSummary"
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
  </div>
</template>
