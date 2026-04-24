<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { GridStack } from 'gridstack'
import 'gridstack/dist/gridstack.min.css'
import { formatCompactNumber } from '../lib/format'
import { resolveRange, summarizeEntries } from '../lib/analytics'
import { useTableSelection } from '../lib/tableSelection'
import { fetchLayoutSetting, saveLayoutSetting } from '../lib/api'

const CALENDAR_SCALE_KEY = 'calen-household-calendar-scale-preset'
const CALENDAR_COLLAPSE_KEY = 'calen-household-calendar-collapsed'
const CALENDAR_HIGHLIGHT_KEY = 'calen-household-calendar-highlight-mode'
const CALENDAR_CUSTOM_SIZE_KEY = 'calen-household-calendar-custom-size'
const CALENDAR_AGGREGATE_PANEL_ENABLED_KEY = 'calen-household-calendar-aggregate-panel-enabled'
const CALENDAR_PANEL_LAYOUT_STORAGE_KEY = 'calen-household-calendar-panel-layout:v1'
const CALENDAR_PANEL_LAYOUT_SCOPE = 'household-calendar'
const CALENDAR_PANEL_LAYOUT_VERSION = 1
const DEFAULT_CALENDAR_HIGHLIGHT_MODE = 'net'
const CALENDAR_CUSTOM_WIDTH_MIN = 780
const CALENDAR_CUSTOM_WIDTH_MAX = 1500
const CALENDAR_CUSTOM_HEIGHT_MIN = 800
const CALENDAR_CUSTOM_HEIGHT_MAX = 1500
const DEFAULT_CALENDAR_CUSTOM_WIDTH = 1200
const DEFAULT_CALENDAR_CUSTOM_HEIGHT = 1200
const CALENDAR_LAYOUT_GRID_COLUMNS = 9
const CALENDAR_LAYOUT_GRID_MARGIN = 4
const CALENDAR_LAYOUT_GRID_GAP = CALENDAR_LAYOUT_GRID_MARGIN * 2
const REMOTE_LAYOUT_SAVE_DELAY_MS = 800

const calendarScalePresets = [
  { key: 'compact', label: '좁게', value: 74 },
  { key: 'default', label: '기본', value: 112 },
  { key: 'expanded', label: '넓게', value: 150 },
]

const calendarDisplayModes = [
  { key: 'default', label: '지금처럼 보기' },
  { key: 'fit', label: '내 화면에 맞추기' },
]

const calendarWeekModes = [
  { key: 'month', label: '이번달 보기' },
  { key: 'current', label: '이번 주 보기' },
  { key: 'previous', label: '이전 주 보기' },
]

const calendarHighlightModes = [
  { key: 'net', label: '수입-지출 보기' },
  { key: 'expense', label: '지출만 보기' },
  { key: 'income', label: '수입만 보기' },
]

const SELECTED_DAY_ENTRY_PAGE_SIZE = 10

const aggregateWidgetKinds = [
  { value: 'NONE', label: '사용 안 함' },
  { value: 'TOTAL', label: '합계' },
  { value: 'PAYMENT_METHOD', label: '결제수단' },
]

const aggregateWidgetPeriods = [
  { value: 'MONTH', label: '이번 달' },
  { value: 'WEEK', label: '이번 주' },
  { value: 'DAY', label: '오늘' },
]

const aggregateWidgetAmountTypes = [
  { value: 'NET', label: '전체' },
  { value: 'INCOME', label: '수입' },
  { value: 'EXPENSE', label: '지출' },
]

const calendarPanelDefinitions = [
  {
    id: 'calendar',
    title: '달력',
    defaultLayout: { x: 0, y: 0, w: 6, h: 6 },
    minW: 4,
    minH: 4,
    maxW: 9,
    maxH: 10,
  },
  {
    id: 'quick-entry',
    title: '빠른 거래 입력',
    defaultLayout: { x: 6, y: 0, w: 3, h: 4 },
    minW: 3,
    minH: 3,
    maxW: 5,
    maxH: 8,
  },
  {
    id: 'aggregate',
    title: '사용자 설정 집계',
    defaultLayout: { x: 6, y: 4, w: 3, h: 3 },
    minW: 3,
    minH: 2,
    maxW: 5,
    maxH: 6,
  },
  {
    id: 'sheet',
    title: '거래 시트',
    defaultLayout: { x: 0, y: 7, w: 9, h: 4 },
    minW: 4,
    minH: 2,
    maxW: 9,
    maxH: 8,
  },
]

const props = defineProps({
  currentUser: {
    type: Object,
    default: null,
  },
  quickStats: {
    type: Array,
    default: () => [],
  },
  monthLabel: {
    type: String,
    required: true,
  },
  anchorDate: {
    type: String,
    required: true,
  },
  weekdayLabels: {
    type: Array,
    default: () => [],
  },
  calendarWeeks: {
    type: Array,
    default: () => [],
  },
  entries: {
    type: Array,
    default: () => [],
  },
  entryForm: {
    type: Object,
    required: true,
  },
  isEditingEntry: {
    type: Boolean,
    default: false,
  },
  isSubmitting: {
    type: Boolean,
    default: false,
  },
  canUndoLastEntryAction: {
    type: Boolean,
    default: false,
  },
  undoEntryActionLabel: {
    type: String,
    default: '등록 취소',
  },
  activeSubmit: {
    type: String,
    default: '',
  },
  availableGroups: {
    type: Array,
    default: () => [],
  },
  availableDetails: {
    type: Array,
    default: () => [],
  },
  paymentMethods: {
    type: Array,
    default: () => [],
  },
  entrySuggestions: {
    type: Array,
    default: () => [],
  },
  aggregateWidgetConfigs: {
    type: Array,
    default: () => [],
  },
  aggregateSettingsReady: {
    type: Boolean,
    default: false,
  },
  aggregateSettingsSaving: {
    type: Boolean,
    default: false,
  },
  amountInput: {
    type: String,
    default: '',
  },
  amountPreview: {
    type: Number,
    default: 0,
  },
  isTimeEnabled: {
    type: Boolean,
    default: false,
  },
  quickAmountButtons: {
    type: Array,
    default: () => [],
  },
  formatAmountShortcut: {
    type: Function,
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

const emit = defineEmits([
  'update:amountInput',
  'update:timeEnabled',
  'fill-amount',
  'add-amount',
  'submit-entry',
  'undo-entry-action',
  'edit-entry',
  'delete-entry',
  'apply-entry-suggestion',
  'change-anchor-month',
  'save-aggregate-widget-configs',
])

const selectedDate = ref(props.anchorDate)
const selectedDaySort = ref('ASC')
const selectedDayEntryFilter = ref('ALL')
const selectedDayEntryPage = ref(0)
const calendarScalePreset = ref('default')
const calendarWeekMode = ref('month')
const calendarPreviousWeekOffset = ref(1)
const calendarHighlightMode = ref(DEFAULT_CALENDAR_HIGHLIGHT_MODE)
const isCalendarCollapsed = ref(false)
const isAggregateEditMode = ref(false)
const isAggregatePanelEnabled = ref(true)
const isLayoutEditMode = ref(false)
const quickEntryPanelRef = ref(null)
const ledgerSheetRef = ref(null)
const calendarShellRef = ref(null)
const calendarContentRef = ref(null)
const layoutGridRef = ref(null)
const aggregateWidgetDraftConfigs = ref(createDefaultAggregateConfigs())
const calendarShellWidth = ref(0)
const calendarNaturalWidth = ref(860)
const calendarNaturalHeight = ref(1000)
const isCalendarResizePanelOpen = ref(false)
const isCalendarCustomSizeEnabled = ref(false)
const calendarCustomWidth = ref(DEFAULT_CALENDAR_CUSTOM_WIDTH)
const calendarCustomHeight = ref(DEFAULT_CALENDAR_CUSTOM_HEIGHT)
const layoutCellHeight = ref(112)
const calendarPanelLayout = ref(createDefaultCalendarPanelLayout())
let calendarResizeObserver = null
let layoutGrid = null
let layoutGridResizeObserver = null
let layoutGridRebuildTimer = 0
let layoutRemoteHydrationSequence = 0
let layoutRemoteSaveTimer = 0
let pendingLayoutRemotePayload = null
let layoutChangedDuringRemoteHydration = false

const maxDailyExpense = computed(() => {
  const expenses = props.calendarWeeks.flat().map((day) => Number(day.summary?.expense ?? 0))
  return Math.max(...expenses, 1)
})

const maxDailyIncome = computed(() => {
  const incomes = props.calendarWeeks.flat().map((day) => Number(day.summary?.income ?? 0))
  return Math.max(...incomes, 1)
})

const maxDailyNetDifference = computed(() => {
  const differences = props.calendarWeeks.flat().map((day) => Math.abs(Number(day.summary?.income ?? 0) - Number(day.summary?.expense ?? 0)))
  return Math.max(...differences, 1)
})

const selectedDateEntries = computed(() => {
  const filtered = props.entries
    .filter((entry) => entry.entryDate === selectedDate.value)
    .filter((entry) => selectedDayEntryFilter.value === 'ALL' || entry.entryType === selectedDayEntryFilter.value)
  return filtered.slice().sort((left, right) => {
    const leftKey = `${left.entryDate} ${left.entryTime || '99:99'} ${String(left.id).padStart(10, '0')}`
    const rightKey = `${right.entryDate} ${right.entryTime || '99:99'} ${String(right.id).padStart(10, '0')}`
    return selectedDaySort.value === 'ASC' ? leftKey.localeCompare(rightKey) : rightKey.localeCompare(leftKey)
  })
})

const calendarYear = computed(() => Number(props.anchorDate.slice(0, 4)))
const calendarMonth = computed(() => Number(props.anchorDate.slice(5, 7)))
const yearOptions = computed(() => {
  const baseYear = calendarYear.value
  return Array.from({ length: 13 }, (_, index) => baseYear - 6 + index)
})

const isAmountOnlyCalendar = computed(() => false)
const isFitCalendar = computed(() => calendarScalePreset.value === 'fit')
const todayIso = getLocalIsoDate(new Date())
const currentWeekReferenceDate = computed(() => (
  props.anchorDate.slice(0, 7) === todayIso.slice(0, 7) ? todayIso : props.anchorDate
))
const referenceWeekIndex = computed(() => {
  const byCurrentDate = props.calendarWeeks.findIndex((week) => week.some((day) => day.date === currentWeekReferenceDate.value))
  if (byCurrentDate >= 0) {
    return byCurrentDate
  }

  const byAnchorDate = props.calendarWeeks.findIndex((week) => week.some((day) => day.date === props.anchorDate))
  return byAnchorDate >= 0 ? byAnchorDate : 0
})
const displayedCalendarWeeks = computed(() => {
  if (calendarWeekMode.value === 'month') {
    return props.calendarWeeks
  }

  if (calendarWeekMode.value === 'current') {
    return props.calendarWeeks[referenceWeekIndex.value] ? [props.calendarWeeks[referenceWeekIndex.value]] : props.calendarWeeks
  }

  const startIndex = clamp(referenceWeekIndex.value - calendarPreviousWeekOffset.value, 0, Math.max(props.calendarWeeks.length - 1, 0))
  const endIndex = clamp(referenceWeekIndex.value, 0, Math.max(props.calendarWeeks.length - 1, 0))
  const weeks = props.calendarWeeks.slice(startIndex, endIndex + 1)
  return weeks.length ? weeks : props.calendarWeeks
})

const calendarViewStyle = computed(() => {
  const metrics = getCalendarDisplayMetrics(calendarScalePreset.value, calendarShellWidth.value)
  if (metrics.responsive) {
    return {
      '--calendar-min-width': metrics.minWidth,
      '--calendar-gap': metrics.gap,
      '--calendar-week-gap': metrics.weekGap,
      '--calendar-day-min-height': metrics.minHeight,
      '--calendar-day-padding': metrics.padding,
      '--calendar-expense-total-size': metrics.totalSize,
      '--calendar-metric-size': metrics.metricSize,
      '--calendar-toolbar-gap': metrics.toolbarGap,
      '--calendar-day-head-size': metrics.headSize,
    }
  }

  const zoom = metrics.zoom
  return {
    '--calendar-min-width': metrics.minWidth,
    '--calendar-gap': `${Math.round(10 * zoom)}px`,
    '--calendar-week-gap': `${Math.round(8 * zoom)}px`,
    '--calendar-day-min-height': `${Math.round(metrics.minHeight)}px`,
    '--calendar-day-padding': `${Math.round(12 * zoom)}px`,
    '--calendar-expense-total-size': `${Math.max(1, 1.18 * zoom).toFixed(2)}rem`,
    '--calendar-metric-size': `${Math.max(0.72, 0.8 * zoom).toFixed(2)}rem`,
    '--calendar-toolbar-gap': `${Math.round(18 * zoom)}px`,
    '--calendar-day-head-size': `${Math.max(0.78, 0.88 * zoom).toFixed(2)}rem`,
  }
})

const calendarLayoutStyle = computed(() => {
  const baseStyle = { ...calendarViewStyle.value }
  if (!isCalendarCustomSizeEnabled.value) {
    return baseStyle
  }

  const width = clamp(calendarCustomWidth.value, CALENDAR_CUSTOM_WIDTH_MIN, CALENDAR_CUSTOM_WIDTH_MAX)
  const height = clamp(calendarCustomHeight.value, CALENDAR_CUSTOM_HEIGHT_MIN, CALENDAR_CUSTOM_HEIGHT_MAX)
  const naturalWidth = Math.max(calendarNaturalWidth.value, 860)
  const naturalHeight = Math.max(calendarNaturalHeight.value, 1)
  const scale = clamp(Math.min(width / naturalWidth, height / naturalHeight), 0.45, 1.6)

  return {
    ...baseStyle,
    '--calendar-frame-width': `${Math.round(naturalWidth * scale)}px`,
    '--calendar-frame-height': `${Math.round(naturalHeight * scale)}px`,
    '--calendar-content-width': `${naturalWidth}px`,
    '--calendar-scale': scale.toFixed(4),
  }
})

const calendarCustomSizeLabel = computed(() => (
  isCalendarCustomSizeEnabled.value
    ? `${calendarCustomWidth.value}px × ${calendarCustomHeight.value}px`
    : '기본 크기 사용 중'
))

const normalizedSelectedDateEntries = computed(() =>
  selectedDateEntries.value.map((entry) => ({
    ...entry,
    visibleMemo: stripImportedMemo(entry.memo),
  })),
)
const selectedDayEntryPageCount = computed(() =>
  Math.max(Math.ceil(normalizedSelectedDateEntries.value.length / SELECTED_DAY_ENTRY_PAGE_SIZE), 1),
)
const pagedNormalizedSelectedDateEntries = computed(() => {
  const start = selectedDayEntryPage.value * SELECTED_DAY_ENTRY_PAGE_SIZE
  return normalizedSelectedDateEntries.value.slice(start, start + SELECTED_DAY_ENTRY_PAGE_SIZE)
})
const selectedDayEntrySelection = useTableSelection(pagedNormalizedSelectedDateEntries)
const userStorageId = computed(() => props.currentUser?.id || props.currentUser?.loginId || 'anonymous')
const calendarPanelLayoutStorageKey = computed(() => `${CALENDAR_PANEL_LAYOUT_STORAGE_KEY}:${userStorageId.value}`)

const hasSelectedMemoColumn = computed(() => normalizedSelectedDateEntries.value.some((entry) => entry.visibleMemo))
const selectedDateCountLabel = computed(() => `${normalizedSelectedDateEntries.value.length}건`)
const formattedAmountInput = computed(() => {
  if (!props.amountInput) {
    return ''
  }

  return formatCompactNumber(props.amountInput)
})

const aggregateCards = computed(() => {
  const sourceConfigs = isAggregateEditMode.value ? aggregateWidgetDraftConfigs.value : props.aggregateWidgetConfigs
  const cards = normalizeAggregateConfigs(sourceConfigs).slice(0, 4).map((config, index) => buildAggregateCard(config, index))
  return isAggregateEditMode.value ? cards : cards.filter((card) => card.config.kind !== 'NONE')
})
const calendarLayoutPanels = computed(() => (
  calendarPanelDefinitions.map((definition) => ({
    ...definition,
    ...calendarPanelLayout.value.find((item) => item.id === definition.id),
  }))
))
const calendarPanelLayoutKey = computed(() => (
  calendarLayoutPanels.value.map((panel) => `${panel.id}:${panel.x}:${panel.y}:${panel.w}:${panel.h}`).join('|')
))
const calendarLayoutGuideRowCount = computed(() => Math.max(
  1,
  ...calendarLayoutPanels.value.map((panel) => Number(panel.y ?? 0) + Number(panel.h ?? 1)),
))
const calendarLayoutGuideCellCount = computed(() => CALENDAR_LAYOUT_GRID_COLUMNS * calendarLayoutGuideRowCount.value)
const calendarLayoutGridStyle = computed(() => ({
  '--calendar-layout-cell-height': `${layoutCellHeight.value}px`,
  '--calendar-layout-grid-gap': `${CALENDAR_LAYOUT_GRID_GAP}px`,
  '--calendar-layout-grid-margin': `${CALENDAR_LAYOUT_GRID_MARGIN}px`,
}))

watch(
  () => props.anchorDate,
  (value) => {
    const currentMonthKey = value.slice(0, 7)
    if (!selectedDate.value || !selectedDate.value.startsWith(currentMonthKey)) {
      selectedDate.value = value
    }
  },
  { immediate: true },
)

watch(selectedDate, (value) => {
  if (!props.isEditingEntry) {
    props.entryForm.entryDate = value
  }
  selectedDayEntryPage.value = 0
})

watch(selectedDaySort, () => {
  selectedDayEntryPage.value = 0
})

watch(selectedDayEntryFilter, () => {
  selectedDayEntryPage.value = 0
})

watch(
  () => normalizedSelectedDateEntries.value.length,
  () => {
    if (selectedDayEntryPage.value >= selectedDayEntryPageCount.value) {
      selectedDayEntryPage.value = Math.max(selectedDayEntryPageCount.value - 1, 0)
    }
  },
)

watch(calendarScalePreset, (value) => {
  if (typeof window !== 'undefined') {
    window.localStorage.setItem(CALENDAR_SCALE_KEY, value)
  }
})

watch(calendarHighlightMode, (value) => {
  if (typeof window !== 'undefined') {
    window.localStorage.setItem(CALENDAR_HIGHLIGHT_KEY, value)
  }
})

watch(isAggregatePanelEnabled, (value) => {
  if (typeof window !== 'undefined') {
    window.localStorage.setItem(CALENDAR_AGGREGATE_PANEL_ENABLED_KEY, value ? 'true' : 'false')
  }
})

watch(calendarPanelLayoutKey, () => {
  queueLayoutGridRebuild()
})

watch(isLayoutEditMode, (value) => {
  if (!layoutGrid) {
    return
  }

  layoutGrid.enableMove(value)
  layoutGrid.enableResize(value)
})

watch([calendarCustomWidth, calendarCustomHeight, isCalendarCustomSizeEnabled], () => {
  if (typeof window === 'undefined') {
    return
  }

  window.localStorage.setItem(
    CALENDAR_CUSTOM_SIZE_KEY,
    JSON.stringify({
      enabled: isCalendarCustomSizeEnabled.value,
      width: calendarCustomWidth.value,
      height: calendarCustomHeight.value,
    }),
  )
})

watch([calendarWeekMode, displayedCalendarWeeks], () => {
  if (calendarWeekMode.value === 'month') {
    return
  }

  const visibleDates = displayedCalendarWeeks.value.flat().map((day) => day.date)
  if (visibleDates.includes(selectedDate.value)) {
    return
  }

  const nextDate = displayedCalendarWeeks.value
    .flat()
    .find((day) => day.date === currentWeekReferenceDate.value)?.date
    ?? displayedCalendarWeeks.value.flat().find((day) => day.inCurrentMonth)?.date
    ?? displayedCalendarWeeks.value.flat()[0]?.date

  if (nextDate) {
    selectedDate.value = nextDate
  }
}, { immediate: true })

watch(
  () => props.paymentMethods,
  () => {
    aggregateWidgetDraftConfigs.value = normalizeAggregateConfigs(aggregateWidgetDraftConfigs.value)
  },
  { deep: true },
)

watch(
  () => props.aggregateWidgetConfigs,
  () => {
    if (!isAggregateEditMode.value) {
      syncAggregateWidgetDraft()
    }
  },
  { deep: true, immediate: true },
)

watch(isCalendarCollapsed, (value) => {
  if (typeof window !== 'undefined') {
    window.localStorage.setItem(CALENDAR_COLLAPSE_KEY, value ? 'true' : 'false')
  }

  if (value) {
    if (calendarResizeObserver) {
      calendarResizeObserver.disconnect()
    }
    return
  }

  nextTick(() => {
    updateCalendarShellWidth()
    updateCalendarContentSize()

    if (typeof ResizeObserver !== 'undefined') {
      if (!calendarResizeObserver) {
        calendarResizeObserver = new ResizeObserver(() => {
          updateCalendarShellWidth()
          updateCalendarContentSize()
        })
      }
      if (calendarShellRef.value) {
        calendarResizeObserver.observe(calendarShellRef.value)
      }
      if (calendarContentRef.value) {
        calendarResizeObserver.observe(calendarContentRef.value)
      }
    }
  })
})

onMounted(() => {
  if (typeof window === 'undefined') {
    return
  }

  hydrateCalendarPanelLayout()

  const savedScale = window.localStorage.getItem(CALENDAR_SCALE_KEY)
  const savedCollapsed = window.localStorage.getItem(CALENDAR_COLLAPSE_KEY)
  const savedHighlight = window.localStorage.getItem(CALENDAR_HIGHLIGHT_KEY)
  const savedCustomSize = window.localStorage.getItem(CALENDAR_CUSTOM_SIZE_KEY)
  const savedAggregatePanelEnabled = window.localStorage.getItem(CALENDAR_AGGREGATE_PANEL_ENABLED_KEY)

  if (savedScale) {
    calendarScalePreset.value = normalizePresetKey(calendarDisplayModes, savedScale, 'default')
  }

  if (savedHighlight && calendarHighlightModes.some((item) => item.key === savedHighlight)) {
    calendarHighlightMode.value = savedHighlight
  } else {
    calendarHighlightMode.value = DEFAULT_CALENDAR_HIGHLIGHT_MODE
    window.localStorage.setItem(CALENDAR_HIGHLIGHT_KEY, DEFAULT_CALENDAR_HIGHLIGHT_MODE)
  }

  if (savedCollapsed) {
    isCalendarCollapsed.value = savedCollapsed === 'true'
  }

  if (savedAggregatePanelEnabled) {
    isAggregatePanelEnabled.value = savedAggregatePanelEnabled !== 'false'
  }

  if (savedCustomSize) {
    try {
      const parsed = JSON.parse(savedCustomSize)
      calendarCustomWidth.value = clamp(Number(parsed?.width) || DEFAULT_CALENDAR_CUSTOM_WIDTH, CALENDAR_CUSTOM_WIDTH_MIN, CALENDAR_CUSTOM_WIDTH_MAX)
      calendarCustomHeight.value = clamp(Number(parsed?.height) || DEFAULT_CALENDAR_CUSTOM_HEIGHT, CALENDAR_CUSTOM_HEIGHT_MIN, CALENDAR_CUSTOM_HEIGHT_MAX)
      isCalendarCustomSizeEnabled.value = Boolean(parsed?.enabled)
    } catch (_error) {
      calendarCustomWidth.value = DEFAULT_CALENDAR_CUSTOM_WIDTH
      calendarCustomHeight.value = DEFAULT_CALENDAR_CUSTOM_HEIGHT
      isCalendarCustomSizeEnabled.value = false
    }
  }

  nextTick(() => {
    initLayoutGrid()
    if (typeof ResizeObserver !== 'undefined') {
      layoutGridResizeObserver = new ResizeObserver(() => {
        updateLayoutCellHeight()
        refreshCalendarMeasurements()
      })
      if (layoutGridRef.value?.parentElement) {
        layoutGridResizeObserver.observe(layoutGridRef.value.parentElement)
      }
    }

    updateCalendarShellWidth()
    updateCalendarContentSize()

    if (typeof ResizeObserver !== 'undefined') {
      calendarResizeObserver = new ResizeObserver(() => {
        updateCalendarShellWidth()
        updateCalendarContentSize()
      })
      if (calendarShellRef.value) {
        calendarResizeObserver.observe(calendarShellRef.value)
      }
      if (calendarContentRef.value) {
        calendarResizeObserver.observe(calendarContentRef.value)
      }
    }
  })
})

onBeforeUnmount(() => {
  if (layoutGridRebuildTimer) {
    window.clearTimeout(layoutGridRebuildTimer)
  }
  saveCalendarPanelLayoutRemoteNow()
  layoutGridResizeObserver?.disconnect()
  destroyLayoutGrid()

  if (calendarResizeObserver) {
    calendarResizeObserver.disconnect()
    calendarResizeObserver = null
  }
})

function isEditableTarget(target) {
  if (!(target instanceof HTMLElement)) {
    return false
  }

  if (target.isContentEditable) {
    return true
  }

  return Boolean(target.closest('input, textarea, select, [contenteditable="true"]'))
}

function handleWorkspaceKeydown(event) {
  if (event.key !== 'Backspace') {
    return
  }

  if (isEditableTarget(event.target)) {
    return
  }

  event.preventDefault()
}

function getPresetValue(presets, key, fallbackKey) {
  return presets.find((item) => item.key === key)?.value ?? presets.find((item) => item.key === fallbackKey)?.value ?? presets[0].value
}

function normalizePresetKey(presets, value, fallbackKey) {
  if (value === 'compact' || value === 'amount-only') {
    return 'default'
  }

  if (value === 'expanded') {
    return 'fit'
  }

  const direct = presets.find((item) => item.key === value)
  if (direct) {
    return direct.key
  }

  const numeric = Number(value)
  if (Number.isFinite(numeric)) {
    if (numeric <= 82) {
      return 'amount-only'
    }
    if (numeric >= 135) {
      return 'fit'
    }
    return 'default'
  }

  return fallbackKey
}

function clamp(value, min, max) {
  return Math.min(max, Math.max(min, value))
}

function getLocalIsoDate(date) {
  const year = date.getFullYear()
  const month = `${date.getMonth() + 1}`.padStart(2, '0')
  const day = `${date.getDate()}`.padStart(2, '0')
  return `${year}-${month}-${day}`
}

function getCalendarDisplayMetrics(mode, width) {
  if (mode === 'fit') {
    const viewportWidth = typeof window !== 'undefined' ? window.innerWidth : 1280
    const safeWidth = Math.max(width || viewportWidth || 1280, 320)
    const fitScale = clamp(safeWidth / 1440, 0.74, 1.04)
    const estimatedDayWidth = clamp(((safeWidth - 32) / 7) * fitScale, 62, 156)
    const dayHeight = clamp(Math.round(estimatedDayWidth * 1.02), 100, 148)
    const dayPadding = clamp(Math.round(estimatedDayWidth * 0.08), 7, 12)
    const gap = clamp(Math.round(estimatedDayWidth * 0.065), 4, 10)
    const weekGap = clamp(Math.round(estimatedDayWidth * 0.05), 4, 8)
    const totalSize = clamp(estimatedDayWidth / 118, 0.86, 1.14)
    const metricSize = clamp(estimatedDayWidth / 176, 0.62, 0.82)
    const headSize = clamp(estimatedDayWidth / 164, 0.7, 0.9)

    return {
      responsive: true,
      minWidth: '100%',
      gap: `${gap}px`,
      weekGap: `${weekGap}px`,
      minHeight: `${dayHeight}px`,
      padding: `${dayPadding}px`,
      totalSize: `${totalSize.toFixed(2)}rem`,
      metricSize: `${metricSize.toFixed(2)}rem`,
      toolbarGap: `${clamp(Math.round(gap * 1.8), 12, 18)}px`,
      headSize: `${headSize.toFixed(2)}rem`,
    }
  }

  return {
    zoom: 1.12,
    minHeight: 146,
    minWidth: '860px',
  }
}

function updateCalendarShellWidth() {
  if (!calendarShellRef.value) {
    return
  }

  calendarShellWidth.value = calendarShellRef.value.clientWidth || 0
}

function updateCalendarContentSize() {
  if (!calendarContentRef.value) {
    return
  }

  calendarNaturalWidth.value = calendarContentRef.value.offsetWidth || calendarContentRef.value.scrollWidth || 860
  calendarNaturalHeight.value = calendarContentRef.value.offsetHeight || calendarContentRef.value.scrollHeight || 1000
}

function updateCalendarCustomWidth(value) {
  calendarCustomWidth.value = clamp(Number(value) || DEFAULT_CALENDAR_CUSTOM_WIDTH, CALENDAR_CUSTOM_WIDTH_MIN, CALENDAR_CUSTOM_WIDTH_MAX)
  isCalendarCustomSizeEnabled.value = true
}

function updateCalendarCustomHeight(value) {
  calendarCustomHeight.value = clamp(Number(value) || DEFAULT_CALENDAR_CUSTOM_HEIGHT, CALENDAR_CUSTOM_HEIGHT_MIN, CALENDAR_CUSTOM_HEIGHT_MAX)
  isCalendarCustomSizeEnabled.value = true
}

function resetCalendarCustomSize() {
  calendarCustomWidth.value = DEFAULT_CALENDAR_CUSTOM_WIDTH
  calendarCustomHeight.value = DEFAULT_CALENDAR_CUSTOM_HEIGHT
  isCalendarCustomSizeEnabled.value = false
}

function createDefaultCalendarPanelLayout() {
  return calendarPanelDefinitions.map((definition) => ({
    id: definition.id,
    ...definition.defaultLayout,
  }))
}

function clone(value) {
  return JSON.parse(JSON.stringify(value))
}

function normalizeCalendarPanelLayout(layouts) {
  const source = new Map((layouts ?? []).map((item) => [String(item.id), item]))

  return calendarPanelDefinitions.map((definition) => {
    const raw = source.get(definition.id) ?? definition.defaultLayout
    const width = clamp(Number(raw.w) || definition.defaultLayout.w, definition.minW, definition.maxW)
    const height = clamp(Number(raw.h) || definition.defaultLayout.h, definition.minH, definition.maxH)

    return {
      id: definition.id,
      x: clamp(Number(raw.x) || 0, 0, CALENDAR_LAYOUT_GRID_COLUMNS - width),
      y: Math.max(0, Number(raw.y) || 0),
      w: width,
      h: height,
    }
  })
}

function hydrateCalendarPanelLayout() {
  layoutRemoteHydrationSequence += 1
  layoutChangedDuringRemoteHydration = false
  if (typeof window === 'undefined') {
    calendarPanelLayout.value = createDefaultCalendarPanelLayout()
    return
  }

  const savedLayout = window.localStorage.getItem(calendarPanelLayoutStorageKey.value)
    || window.localStorage.getItem(CALENDAR_PANEL_LAYOUT_STORAGE_KEY)
  if (!savedLayout) {
    calendarPanelLayout.value = createDefaultCalendarPanelLayout()
    hydrateRemoteCalendarPanelLayout(layoutRemoteHydrationSequence, null)
    return
  }

  try {
    calendarPanelLayout.value = normalizeCalendarPanelLayout(JSON.parse(savedLayout))
    persistCalendarPanelLayoutLocal()
    hydrateRemoteCalendarPanelLayout(layoutRemoteHydrationSequence, clone(calendarPanelLayout.value))
  } catch (_error) {
    calendarPanelLayout.value = createDefaultCalendarPanelLayout()
    hydrateRemoteCalendarPanelLayout(layoutRemoteHydrationSequence, null)
  }
}

function persistCalendarPanelLayoutLocal() {
  if (typeof window !== 'undefined') {
    window.localStorage.setItem(calendarPanelLayoutStorageKey.value, JSON.stringify(calendarPanelLayout.value))
  }
}

function scheduleCalendarPanelLayoutRemotePersist(payload = clone(calendarPanelLayout.value)) {
  if (typeof window === 'undefined') {
    return
  }

  if (layoutRemoteSaveTimer) {
    window.clearTimeout(layoutRemoteSaveTimer)
  }

  pendingLayoutRemotePayload = clone(payload)
  layoutRemoteSaveTimer = window.setTimeout(() => {
    saveCalendarPanelLayoutRemoteNow()
  }, REMOTE_LAYOUT_SAVE_DELAY_MS)
}

function saveCalendarPanelLayoutRemoteNow(payload = pendingLayoutRemotePayload) {
  if (typeof window === 'undefined' || !payload) {
    return Promise.resolve()
  }

  if (layoutRemoteSaveTimer) {
    window.clearTimeout(layoutRemoteSaveTimer)
    layoutRemoteSaveTimer = 0
  }

  const nextPayload = clone(payload)
  pendingLayoutRemotePayload = null
  return saveLayoutSetting(CALENDAR_PANEL_LAYOUT_SCOPE, nextPayload, CALENDAR_PANEL_LAYOUT_VERSION).catch(() => {
    // Local cache keeps the user's layout if the backend is temporarily unavailable.
  })
}

function persistCalendarPanelLayout() {
  layoutChangedDuringRemoteHydration = true
  const payload = clone(calendarPanelLayout.value)
  persistCalendarPanelLayoutLocal()
  scheduleCalendarPanelLayoutRemotePersist(payload)
}

async function hydrateRemoteCalendarPanelLayout(sequence, fallbackLayout) {
  try {
    const response = await fetchLayoutSetting(CALENDAR_PANEL_LAYOUT_SCOPE)
    if (sequence !== layoutRemoteHydrationSequence || layoutChangedDuringRemoteHydration) {
      return
    }

    if (Array.isArray(response?.payload)) {
      calendarPanelLayout.value = normalizeCalendarPanelLayout(response.payload)
      persistCalendarPanelLayoutLocal()
      refreshCalendarMeasurements()
      return
    }

    if (fallbackLayout) {
      await saveLayoutSetting(CALENDAR_PANEL_LAYOUT_SCOPE, fallbackLayout, CALENDAR_PANEL_LAYOUT_VERSION)
    }
  } catch {
    // Remote layout sync should not block the calendar workspace.
  }
}

function resetCalendarPanelLayout() {
  calendarPanelLayout.value = createDefaultCalendarPanelLayout()
  persistCalendarPanelLayout()
  refreshCalendarMeasurements()
}

function calendarPanelAttrs(panel) {
  return {
    x: panel.x,
    y: panel.y,
    w: panel.w,
    h: panel.h,
    minW: panel.minW,
    minH: panel.minH,
    maxW: panel.maxW,
    maxH: panel.maxH,
  }
}

function updateLayoutCellHeight() {
  const width = layoutGridRef.value?.clientWidth || layoutGridRef.value?.parentElement?.clientWidth || 0
  if (!width || !layoutGrid) {
    return
  }

  const rawCellWidth = (
    width
    - (CALENDAR_LAYOUT_GRID_MARGIN * 2)
    - ((CALENDAR_LAYOUT_GRID_COLUMNS - 1) * CALENDAR_LAYOUT_GRID_GAP)
  ) / CALENDAR_LAYOUT_GRID_COLUMNS
  const nextHeight = Math.round(Math.max(108, Math.min(148, rawCellWidth * 0.88)))
  layoutCellHeight.value = nextHeight
  layoutGrid.cellHeight(nextHeight)
}

function readLayoutGridSnapshot() {
  if (!layoutGrid) {
    return []
  }

  return (layoutGrid.engine?.nodes ?? []).map((node) => ({
    id: node.el?.getAttribute('gs-id') || node.el?.getAttribute('data-calendar-panel-id'),
    x: node.x,
    y: node.y,
    w: node.w,
    h: node.h,
  })).filter((item) => item.id)
}

function applyCalendarPanelLayout(snapshot) {
  calendarPanelLayout.value = normalizeCalendarPanelLayout(snapshot)
  persistCalendarPanelLayout()
  refreshCalendarMeasurements()
}

function handleLayoutGridStop() {
  applyCalendarPanelLayout(readLayoutGridSnapshot())
}

function destroyLayoutGrid() {
  if (!layoutGrid) {
    return
  }

  layoutGrid.off('dragstop')
  layoutGrid.off('resizestop')
  layoutGrid.destroy(false)
  layoutGrid = null
}

function initLayoutGrid() {
  if (!layoutGridRef.value) {
    return
  }

  destroyLayoutGrid()
  layoutGrid = GridStack.init({
    column: CALENDAR_LAYOUT_GRID_COLUMNS,
    margin: CALENDAR_LAYOUT_GRID_MARGIN,
    cellHeight: layoutCellHeight.value,
    disableResize: false,
    float: false,
    animate: true,
    draggable: {
      appendTo: 'body',
      cancel: 'button,a,input,select,textarea,[data-no-drag="true"]',
      handle: '.panel__header, .household-calendar-panel-dragbar',
      scroll: false,
    },
    resizable: {
      handles: 'se',
    },
  }, layoutGridRef.value)
  layoutGrid.enableMove(isLayoutEditMode.value)
  layoutGrid.enableResize(isLayoutEditMode.value)
  layoutGrid.on('dragstop', handleLayoutGridStop)
  layoutGrid.on('resizestop', handleLayoutGridStop)
  updateLayoutCellHeight()
}

function queueLayoutGridRebuild() {
  if (typeof window === 'undefined') {
    return
  }

  if (layoutGridRebuildTimer) {
    window.clearTimeout(layoutGridRebuildTimer)
  }

  layoutGridRebuildTimer = window.setTimeout(async () => {
    await nextTick()
    initLayoutGrid()
    refreshCalendarMeasurements()
    layoutGridRebuildTimer = 0
  }, 0)
}

function toggleLayoutEditMode() {
  const isFinishingEdit = isLayoutEditMode.value
  isLayoutEditMode.value = !isLayoutEditMode.value
  if (isFinishingEdit) {
    saveCalendarPanelLayoutRemoteNow()
  }
}

function refreshCalendarMeasurements() {
  nextTick(() => {
    updateCalendarShellWidth()
    updateCalendarContentSize()
  })
}

function getExpenseAmount(day) {
  return Number(day.summary?.expense ?? 0)
}

function getIncomeAmount(day) {
  return Number(day.summary?.income ?? 0)
}

function getNetDifference(day) {
  return getIncomeAmount(day) - getExpenseAmount(day)
}

function getCalendarSummaryLabel() {
  if (calendarHighlightMode.value === 'expense') {
    return '지출'
  }

  if (calendarHighlightMode.value === 'income') {
    return '수입'
  }

  return '증감'
}

function getCalendarSummaryAmount(day) {
  if (calendarHighlightMode.value === 'expense') {
    return getExpenseAmount(day)
  }

  if (calendarHighlightMode.value === 'income') {
    return getIncomeAmount(day)
  }

  return Math.abs(getNetDifference(day))
}

function shouldShowExpenseMetric() {
  return calendarHighlightMode.value !== 'income'
}

function shouldShowIncomeMetric() {
  return calendarHighlightMode.value !== 'expense'
}

function isExpenseHighlighted(day) {
  const expense = getExpenseAmount(day)
  const income = getIncomeAmount(day)

  if (calendarHighlightMode.value === 'expense') {
    return expense > 0
  }

  if (calendarHighlightMode.value === 'income') {
    return false
  }

  return expense > income && expense > 0
}

function isIncomeHighlighted(day) {
  const expense = getExpenseAmount(day)
  const income = getIncomeAmount(day)

  if (calendarHighlightMode.value === 'income') {
    return income > 0
  }

  if (calendarHighlightMode.value === 'expense') {
    return false
  }

  return income > expense && income > 0
}

function getCalendarBarRatio(day) {
  const expense = getExpenseAmount(day)
  const income = getIncomeAmount(day)

  if (calendarHighlightMode.value === 'expense') {
    if (!expense) {
      return 0
    }
    return Math.max(12, Math.round((expense / maxDailyExpense.value) * 100))
  }

  if (calendarHighlightMode.value === 'income') {
    if (!income) {
      return 0
    }
    return Math.max(12, Math.round((income / maxDailyIncome.value) * 100))
  }

  const difference = Math.abs(getNetDifference(day))
  if (!difference) {
    return 0
  }

  return Math.max(12, Math.round((difference / maxDailyNetDifference.value) * 100))
}

function createDefaultAggregateConfigs() {
  const defaultPaymentMethodId = props.paymentMethods[0] ? String(props.paymentMethods[0].id) : ''
  return [
    { id: 'aggregate-1', kind: 'TOTAL', period: 'MONTH', paymentMethodId: '', amountType: 'NET' },
    { id: 'aggregate-2', kind: 'NONE', period: 'MONTH', paymentMethodId: '', amountType: 'NET' },
    { id: 'aggregate-3', kind: 'NONE', period: 'WEEK', paymentMethodId: '', amountType: 'NET' },
    { id: 'aggregate-4', kind: 'NONE', period: 'DAY', paymentMethodId: defaultPaymentMethodId, amountType: 'NET' },
  ]
}

function normalizeAggregateConfigs(configs) {
  const fallback = createDefaultAggregateConfigs()
  const validPaymentIds = new Set(props.paymentMethods.map((item) => String(item.id)))
  const firstPaymentMethodId = props.paymentMethods[0] ? String(props.paymentMethods[0].id) : ''

  return fallback.map((baseConfig, index) => {
    const current = configs?.[index] ?? {}
    const kind = aggregateWidgetKinds.some((item) => item.value === current.kind) ? current.kind : baseConfig.kind
    const period = aggregateWidgetPeriods.some((item) => item.value === current.period) ? current.period : baseConfig.period
    const amountType = aggregateWidgetAmountTypes.some((item) => item.value === current.amountType) ? current.amountType : baseConfig.amountType
    const paymentMethodId = kind === 'PAYMENT_METHOD'
      ? (validPaymentIds.has(String(current.paymentMethodId ?? '')) ? String(current.paymentMethodId) : (baseConfig.paymentMethodId || firstPaymentMethodId))
      : ''

    return {
      id: current.id || baseConfig.id,
      kind,
      period,
      paymentMethodId,
      amountType,
    }
  })
}

function updateAggregateWidget(index, field, value) {
  aggregateWidgetDraftConfigs.value = normalizeAggregateConfigs(
    aggregateWidgetDraftConfigs.value.map((config, configIndex) => {
      if (configIndex !== index) {
        return config
      }

      const nextConfig = {
        ...config,
        [field]: value,
      }

      if (field === 'kind' && value !== 'PAYMENT_METHOD') {
        nextConfig.paymentMethodId = ''
      }

      if (field === 'kind' && value === 'PAYMENT_METHOD' && !nextConfig.paymentMethodId) {
        nextConfig.paymentMethodId = props.paymentMethods[0] ? String(props.paymentMethods[0].id) : ''
      }

      return nextConfig
    }),
  )
}

function syncAggregateWidgetDraft() {
  aggregateWidgetDraftConfigs.value = normalizeAggregateConfigs(props.aggregateWidgetConfigs)
}

function startAggregateEdit() {
  syncAggregateWidgetDraft()
  isAggregateEditMode.value = true
}

function cancelAggregateEdit() {
  syncAggregateWidgetDraft()
  isAggregateEditMode.value = false
}

function saveAggregateWidgetConfigs() {
  emit(
    'save-aggregate-widget-configs',
    normalizeAggregateConfigs(aggregateWidgetDraftConfigs.value).map(({ kind, period, paymentMethodId, amountType }) => ({
      kind,
      period,
      paymentMethodId: paymentMethodId ? Number(paymentMethodId) : null,
      amountType,
    })),
  )
  isAggregateEditMode.value = false
}

function toggleAggregatePanelEnabled() {
  isAggregatePanelEnabled.value = !isAggregatePanelEnabled.value

  if (!isAggregatePanelEnabled.value && isAggregateEditMode.value) {
    cancelAggregateEdit()
  }
}

function getAggregateRange(period) {
  const anchorMonthPrefix = String(props.anchorDate || '').slice(0, 7)
  const selectedMonthPrefix = String(selectedDate.value || '').slice(0, 7)
  const baseDate = selectedMonthPrefix === anchorMonthPrefix
    ? (selectedDate.value || props.anchorDate)
    : props.anchorDate

  const range = resolveRange(baseDate, period, props.anchorDate, props.anchorDate)

  if (!anchorMonthPrefix) {
    return range
  }

  const monthStart = `${anchorMonthPrefix}-01`
  const monthEnd = resolveRange(props.anchorDate, 'MONTH', props.anchorDate, props.anchorDate).to

  if (period !== 'WEEK' && period !== 'DAY') {
    return range
  }

  const from = range.from < monthStart ? monthStart : range.from
  const to = range.to > monthEnd ? monthEnd : range.to

  return {
    ...range,
    from,
    to,
  }
}

function buildAggregateCard(config, index) {
  if (config.kind === 'NONE') {
    return {
      id: config.id || ('aggregate-' + (index + 1)),
      index,
      config,
      title: '사용 안 함',
      periodLabel: '',
      totalAmount: 0,
      overview: summarizeEntries([]),
    }
  }

  const range = getAggregateRange(config.period)
  const rangeEntries = props.entries.filter((entry) => entry.entryDate >= range.from && entry.entryDate <= range.to)
  const filteredEntries = config.kind === 'PAYMENT_METHOD' && config.paymentMethodId
    ? rangeEntries.filter((entry) => String(entry.paymentMethodId) === String(config.paymentMethodId))
    : rangeEntries
  const overview = summarizeEntries(filteredEntries)
  const totalAmount = config.amountType === 'INCOME'
    ? Number(overview.income)
    : config.amountType === 'EXPENSE'
      ? Number(overview.expense)
      : Number(overview.income) + Number(overview.expense)
  const paymentMethodName = props.paymentMethods.find((item) => String(item.id) === String(config.paymentMethodId))?.name || '결제수단'
  const periodLabel = aggregateWidgetPeriods.find((item) => item.value === config.period)?.label || '이번 달'
  const amountTypeLabel = aggregateWidgetAmountTypes.find((item) => item.value === config.amountType)?.label || '전체'
  const title = config.kind === 'PAYMENT_METHOD'
    ? (periodLabel + ' ' + paymentMethodName + ' ' + amountTypeLabel + ' 합계')
    : (config.amountType === 'NET'
        ? (periodLabel + ' 총 합계')
        : (periodLabel + ' 총 ' + amountTypeLabel + ' 합계'))

  return {
    id: config.id || ('aggregate-' + (index + 1)),
    index,
    config,
    title,
    periodLabel,
    totalAmount,
    overview,
  }
}

function stripImportedMemo(value) {
  if (!value) {
    return ''
  }

  return String(value)
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line && !line.startsWith('Imported from Excel'))
    .join('\n')
}

function getExpenseRatio(day) {
  const amount = Number(day.summary?.expense ?? 0)
  if (!amount) {
    return 0
  }

  return Math.max(12, Math.round((amount / maxDailyExpense.value) * 100))
}

function formatCompactCurrency(value) {
  const amount = Number(value ?? 0)
  if (!amount) {
    return '0원'
  }

  if (amount >= 10000) {
    return `${(amount / 10000).toFixed(amount >= 100000 ? 0 : 1).replace(/\.0$/, '')}만`
  }

  return `${amount.toLocaleString('ko-KR')}원`
}

function updateAnchorMonth(year, month) {
  const safeMonth = `${Math.min(12, Math.max(1, Number(month) || 1))}`.padStart(2, '0')
  emit('change-anchor-month', `${Number(year)}-${safeMonth}-01`)
}

function handleChangeYear(event) {
  updateAnchorMonth(event.target.value, calendarMonth.value)
}

function handleChangeMonth(event) {
  updateAnchorMonth(calendarYear.value, event.target.value)
}

function shiftAnchorYear(offset) {
  updateAnchorMonth(calendarYear.value + Number(offset || 0), calendarMonth.value)
}

function shiftAnchorMonth(offset) {
  const nextDate = new Date(calendarYear.value, calendarMonth.value - 1 + Number(offset || 0), 1)
  updateAnchorMonth(nextDate.getFullYear(), nextDate.getMonth() + 1)
}

async function handleSelectDay(day) {
  selectedDate.value = day.date

  if (!props.isEditingEntry) {
    props.entryForm.entryDate = day.date
  }

  if (!day.inCurrentMonth) {
    emit('change-anchor-month', day.date)
  }

  await nextTick()
  ledgerSheetRef.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

function formatPaymentMethodForSheet(entry) {
  return entry.entryType === 'INCOME' ? '-' : entry.paymentMethodName
}

function getMonthTag(day) {
  if (day.showMonthTag) {
    return `${day.monthNumber}월`
  }
  return ''
}

function toggleCalendarCollapsed() {
  isCalendarCollapsed.value = !isCalendarCollapsed.value
}

function scrollToEntryEditor() {
  quickEntryPanelRef.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

function setSelectedDate(value) {
  selectedDate.value = value
}

defineExpose({
  scrollToEntryEditor,
  setSelectedDate,
})
</script>

<template>
  <div class="workspace-stack household-calendar-workspace" @keydown.capture="handleWorkspaceKeydown">
    <section
      :class="[
        'panel household-calendar-control-panel household-calendar-layout',
        { 'household-calendar-layout--amount-only': isAmountOnlyCalendar },
        { 'household-calendar-layout--fit': isFitCalendar },
        { 'household-calendar-layout--custom-size': isCalendarCustomSizeEnabled },
      ]"
      :style="calendarLayoutStyle"
    >
      <div class="panel__header household-calendar-control-panel__header">
        <div>
          <h2>{{ monthLabel }}</h2>
          <p>달력을 넓게 보고 날짜를 누르면 바로 아래 거래 시트로 이동해 해당 날짜 거래를 바로 확인할 수 있습니다.</p>
        </div>
        <div class="household-calendar-header-actions">
          <span class="panel__badge">{{ anchorDate.slice(0, 7) }}</span>
          <button class="button button--secondary" type="button" @click="toggleCalendarCollapsed">
            {{ isCalendarCollapsed ? '달력 펼치기' : '달력 접기' }}
          </button>
          <div class="household-calendar-layout-toolbar household-calendar-layout-toolbar--inline" data-no-drag="true">
            <div class="household-calendar-layout-toolbar__status">
              <strong>달력 배치</strong>
              <span>{{ isLayoutEditMode ? '편집 중' : '고정됨' }}</span>
            </div>
            <div class="household-calendar-layout-toolbar__actions">
              <button type="button" class="button button--secondary" @click="resetCalendarPanelLayout">
                기본 배치
              </button>
              <button type="button" class="button button--primary" @click="toggleLayoutEditMode">
                {{ isLayoutEditMode ? '배치 완료' : '배치 편집' }}
              </button>
            </div>
          </div>
        </div>
      </div>

      <div v-if="!isCalendarCollapsed" class="calendar-toolbar">
        <div class="calendar-stepper">
          <span class="calendar-stepper__label">연도</span>
          <div class="calendar-stepper__controls">
            <button type="button" class="calendar-stepper__arrow" aria-label="이전 연도" @click="shiftAnchorYear(-1)">&lt;</button>
            <label class="field calendar-stepper__field">
              <select :value="calendarYear" @change="handleChangeYear">
                <option v-for="year in yearOptions" :key="year" :value="year">{{ year }}년</option>
              </select>
            </label>
            <button type="button" class="calendar-stepper__arrow" aria-label="다음 연도" @click="shiftAnchorYear(1)">&gt;</button>
          </div>
        </div>
        <div class="calendar-stepper">
          <span class="calendar-stepper__label">월</span>
          <div class="calendar-stepper__controls">
            <button type="button" class="calendar-stepper__arrow" aria-label="이전 월" @click="shiftAnchorMonth(-1)">&lt;</button>
            <label class="field calendar-stepper__field">
              <select :value="calendarMonth" @change="handleChangeMonth">
                <option v-for="month in 12" :key="month" :value="month">{{ month }}월</option>
              </select>
            </label>
            <button type="button" class="calendar-stepper__arrow" aria-label="다음 월" @click="shiftAnchorMonth(1)">&gt;</button>
          </div>
        </div>
      </div>

      <div v-if="!isCalendarCollapsed" class="calendar-size-toolbar">
        <div class="calendar-size-toolbar__block calendar-size-toolbar__block--resize">
          <button class="button button--ghost calendar-size-toolbar__control" type="button" @click="isCalendarResizePanelOpen = !isCalendarResizePanelOpen">
            크기 조절
          </button>
          <span class="calendar-size-toolbar__label calendar-size-toolbar__label--inline">{{ calendarCustomSizeLabel }}</span>
          <div v-if="isCalendarResizePanelOpen" class="calendar-resize-panel">
            <div class="calendar-resize-panel__header">
              <strong>달력 실제 크기</strong>
              <button class="button button--ghost" type="button" @click="resetCalendarCustomSize">기본값</button>
            </div>
            <label class="calendar-resize-panel__field">
              <span>가로 {{ calendarCustomWidth }}px</span>
              <input
                :value="calendarCustomWidth"
                type="range"
                :min="CALENDAR_CUSTOM_WIDTH_MIN"
                :max="CALENDAR_CUSTOM_WIDTH_MAX"
                step="10"
                @input="updateCalendarCustomWidth($event.target.value)"
              />
            </label>
            <label class="calendar-resize-panel__field">
              <span>세로 {{ calendarCustomHeight }}px</span>
              <input
                :value="calendarCustomHeight"
                type="range"
                :min="CALENDAR_CUSTOM_HEIGHT_MIN"
                :max="CALENDAR_CUSTOM_HEIGHT_MAX"
                step="10"
                @input="updateCalendarCustomHeight($event.target.value)"
              />
            </label>
          </div>
        </div>
        <div class="calendar-size-toolbar__block">
          <span class="calendar-size-toolbar__label">달력 크기</span>
          <div class="calendar-size-toggle">
            <button
              v-for="preset in calendarDisplayModes"
              :key="preset.key"
              type="button"
              class="calendar-size-toggle__button"
              :class="{ 'is-active': calendarScalePreset === preset.key }"
              @click="calendarScalePreset = preset.key"
            >
              {{ preset.label }}
            </button>
          </div>
        </div>
        <div class="calendar-size-toolbar__block">
          <span class="calendar-size-toolbar__label">주차 보기</span>
          <div class="calendar-size-toggle">
            <button
              v-for="mode in calendarWeekModes"
              :key="mode.key"
              type="button"
              class="calendar-size-toggle__button"
              :class="{ 'is-active': calendarWeekMode === mode.key }"
              @click="calendarWeekMode = mode.key"
            >
              {{ mode.label }}
            </button>
          </div>
          <div
            class="calendar-size-toggle"
            :class="{ 'calendar-size-toggle--inactive': calendarWeekMode !== 'previous' }"
            aria-hidden="true"
          >
            <button
              v-for="offset in [1, 2]"
              :key="offset"
              type="button"
              class="calendar-size-toggle__button"
              :class="{ 'is-active': calendarPreviousWeekOffset === offset }"
              :disabled="calendarWeekMode !== 'previous'"
              @click="calendarPreviousWeekOffset = offset"
            >
              {{ offset }}주 전
            </button>
          </div>
        </div>
        <div class="calendar-size-toolbar__block">
          <span class="calendar-size-toolbar__label">표시 기준</span>
          <div class="calendar-size-toggle">
            <button
              v-for="mode in calendarHighlightModes"
              :key="mode.key"
              type="button"
              class="calendar-size-toggle__button"
              :class="{ 'is-active': calendarHighlightMode === mode.key }"
              @click="calendarHighlightMode = mode.key"
            >
              {{ mode.label }}
            </button>
          </div>
        </div>
        <strong class="calendar-size-toolbar__hint">
          현재 {{ calendarDisplayModes.find((item) => item.key === calendarScalePreset)?.label }}
          <template v-if="calendarWeekMode !== 'month'">
            · {{ calendarWeekModes.find((item) => item.key === calendarWeekMode)?.label }}
            <template v-if="calendarWeekMode === 'previous'"> (이번 주 포함 {{ calendarPreviousWeekOffset + 1 }}주)</template>
          </template>
        </strong>
      </div>
    </section>

    <section
      class="household-calendar-layout-board"
      :class="{ 'household-calendar-layout-board--editing': isLayoutEditMode }"
      :style="calendarLayoutGridStyle"
    >
      <div v-if="isLayoutEditMode" class="household-calendar-layout-guide" aria-hidden="true">
        <span v-for="index in calendarLayoutGuideCellCount" :key="index"></span>
      </div>

      <div ref="layoutGridRef" class="grid-stack household-calendar-layout-grid">
        <div
          v-for="panel in calendarLayoutPanels"
          :key="panel.id"
          class="grid-stack-item"
          :class="`household-calendar-layout-item--${panel.id}`"
          :gs-id="panel.id"
          :data-calendar-panel-id="panel.id"
          :gs-x="calendarPanelAttrs(panel).x"
          :gs-y="calendarPanelAttrs(panel).y"
          :gs-w="calendarPanelAttrs(panel).w"
          :gs-h="calendarPanelAttrs(panel).h"
          :gs-min-w="calendarPanelAttrs(panel).minW"
          :gs-min-h="calendarPanelAttrs(panel).minH"
          :gs-max-w="calendarPanelAttrs(panel).maxW"
          :gs-max-h="calendarPanelAttrs(panel).maxH"
        >
          <div class="grid-stack-item-content">
            <div
              class="household-calendar-layout-panel-shell"
              :class="{ 'household-calendar-layout-panel-shell--editing': isLayoutEditMode }"
            >
              <template v-if="panel.id === 'quick-entry'">
                <section ref="quickEntryPanelRef" class="panel household-entry-panel">
        <div class="panel__header">
          <div>
            <h2>{{ isEditingEntry ? '거래 수정' : '빠른 거래 입력' }}</h2>
            <p>날짜를 고른 뒤 금액과 분류를 먼저 입력하고, 시간은 필요할 때만 켜서 빠르게 기록할 수 있습니다.</p>
          </div>
          <span class="panel__badge household-entry-panel__date-badge">{{ formatShortDate(entryForm.entryDate) }}</span>
        </div>

        <div class="entry-editor">
          <div class="entry-editor__amount">
            <div class="entry-type-toggle">
              <button
                type="button"
                :class="['toggle-chip', { 'toggle-chip--active': entryForm.entryType === 'EXPENSE' }]"
                @click="entryForm.entryType = 'EXPENSE'"
              >
                지출
              </button>
              <button
                type="button"
                :class="['toggle-chip', { 'toggle-chip--active': entryForm.entryType === 'INCOME' }]"
                @click="entryForm.entryType = 'INCOME'"
              >
                수입
              </button>
            </div>

            <label class="field field--amount">
              <span class="field__label">금액</span>
              <div class="amount-input">
                <span>₩</span>
                <input
                  :value="formattedAmountInput"
                  type="text"
                  inputmode="numeric"
                  placeholder="예: 48,000"
                  @input="emit('update:amountInput', $event.target.value)"
                />
              </div>
              <small class="field__hint">현재 입력 금액 {{ formatCurrency(amountPreview) }}</small>
            </label>

            <div class="amount-shortcuts">
              <button
                v-for="value in quickAmountButtons"
                :key="value"
                type="button"
                class="button button--secondary amount-shortcuts__button"
                @click="emit('fill-amount', value)"
              >
                {{ formatAmountShortcut(value) }}
              </button>
              <button type="button" class="button button--secondary amount-shortcuts__button" @click="emit('add-amount', 5000)">
                +5천
              </button>
              <button type="button" class="button button--secondary amount-shortcuts__button" @click="emit('add-amount', 10000)">
                +1만
              </button>
            </div>
          </div>

          <div class="entry-editor__fields">
            <label class="field">
              <span class="field__label">날짜</span>
              <input v-model="entryForm.entryDate" type="date" />
            </label>

            <label class="field household-time-field">
              <span class="field__label">시간</span>
              <label class="checkbox-row household-time-toggle">
                <input
                  :checked="isTimeEnabled"
                  type="checkbox"
                  @change="emit('update:timeEnabled', $event.target.checked)"
                />
                <span>시간 입력 사용</span>
              </label>
              <input v-model="entryForm.entryTime" type="time" :disabled="!isTimeEnabled" />
              <small class="field__hint">시간 입력을 끄면 자동으로 00:00으로 저장됩니다.</small>
            </label>

            <label class="field field--full">
              <span class="field__label">제목</span>
              <input v-model="entryForm.title" type="text" placeholder="예: 식사, 택시, 급여" />
            </label>

            <label v-if="entryForm.entryType === 'EXPENSE'" class="field">
  <span class="field__label">결제수단</span>
  <select v-model="entryForm.paymentMethodId">
    <option v-for="payment in paymentMethods" :key="payment.id" :value="String(payment.id)">
      {{ payment.name }}
    </option>
  </select>
</label>

<template v-if="entryForm.entryType === 'INCOME'">
  <div class="field field--full">
    <span class="field__label">분류</span>
    <div class="entry-editor__category-grid">
      <select v-model="entryForm.categoryGroupId">
        <option v-for="group in availableGroups" :key="group.id" :value="String(group.id)">
          {{ group.name }}
        </option>
      </select>
      <select v-model="entryForm.categoryDetailId">
        <option value="">소분류 없음</option>
        <option v-for="detail in availableDetails" :key="detail.id" :value="String(detail.id)">
          {{ detail.name }}
        </option>
      </select>
    </div>
  </div>
</template>
<template v-else>
  <label class="field">
    <span class="field__label">대분류</span>
    <select v-model="entryForm.categoryGroupId">
      <option v-for="group in availableGroups" :key="group.id" :value="String(group.id)">
        {{ group.name }}
      </option>
    </select>
  </label>

  <label class="field">
    <span class="field__label">분류</span>
    <select v-model="entryForm.categoryDetailId">
      <option value="">소분류 없음</option>
      <option v-for="detail in availableDetails" :key="detail.id" :value="String(detail.id)">
        {{ detail.name }}
      </option>
    </select>
  </label>
</template>

            <label class="field field--full">
              <span class="field__label">메모</span>
              <input v-model="entryForm.memo" type="text" placeholder="상세 메모를 남기고 싶다면 입력해 주세요." />
            </label>
          </div>
        </div>

        <div class="entry-editor__actions">
          <button type="button" class="button button--primary" :disabled="isSubmitting" @click="emit('submit-entry')">
            {{
              isSubmitting && activeSubmit === 'entry'
                ? '저장 중...'
                : isEditingEntry
                  ? '거래 수정'
                  : '거래 등록'
            }}
          </button>
          <button
            v-if="canUndoLastEntryAction"
            type="button"
            class="button button--secondary"
            :disabled="isSubmitting"
            @click="emit('undo-entry-action')"
          >
            {{ undoEntryActionLabel }}
          </button>
        </div>
      </section>

              </template>

              <template v-else-if="panel.id === 'aggregate'">
                <section class="panel household-quickstats-panel">
        <div class="panel__header household-aggregate-header">
          <div>
            <h2>사용자 설정 집계</h2>
            <p>한 줄에 하나씩 집계 결과만 배치하고, 필요할 때만 수정 버튼으로 표시 항목을 바꿀 수 있습니다.</p>
          </div>
          <div class="household-aggregate-header__actions">
            <button
              type="button"
              class="button button--ghost household-aggregate-toggle"
              :aria-pressed="isAggregatePanelEnabled"
              @click="toggleAggregatePanelEnabled"
            >
              {{ isAggregatePanelEnabled ? '집계 끄기' : '집계 켜기' }}
            </button>
            <span v-if="isAggregatePanelEnabled && aggregateSettingsReady" class="panel__badge">{{ aggregateCards.length }}칸</span>
            <template v-if="isAggregatePanelEnabled && isAggregateEditMode">
              <button
                type="button"
                class="button button--primary"
                :disabled="aggregateSettingsSaving"
                @click="saveAggregateWidgetConfigs"
              >
                {{ aggregateSettingsSaving ? '저장 중...' : '저장' }}
              </button>
              <button
                type="button"
                class="button button--secondary"
                :disabled="aggregateSettingsSaving"
                @click="cancelAggregateEdit"
              >
                취소
              </button>
            </template>
            <button
              v-else-if="isAggregatePanelEnabled"
              type="button"
              class="button button--secondary"
              :disabled="!aggregateSettingsReady"
              @click="startAggregateEdit"
            >
              수정
            </button>
          </div>
        </div>
        <div v-if="!isAggregatePanelEnabled" class="household-aggregate-empty household-aggregate-empty--off">
          <strong>사용자 설정 집계가 꺼져 있습니다.</strong>
          <span>저장된 집계 구성은 유지되며, 다시 켜면 같은 항목을 바로 볼 수 있습니다.</span>
          <button type="button" class="button button--secondary" @click="toggleAggregatePanelEnabled">집계 켜기</button>
        </div>
        <div v-else-if="!aggregateSettingsReady" class="household-aggregate-empty">
          <strong>집계 설정을 불러오는 중입니다.</strong>
          <span>저장된 카드 구성을 불러온 뒤 현재 달력 데이터로 집계를 보여줍니다.</span>
        </div>
        <div v-else-if="aggregateCards.length" class="household-aggregate-grid">
          <article v-for="card in aggregateCards" :key="card.id" class="household-aggregate-card">
            <div v-if="isAggregateEditMode" class="household-aggregate-card__controls">
              <label class="field household-aggregate-card__field">
                <span class="field__label">집계</span>
                <select :value="card.config.kind" @change="updateAggregateWidget(card.index, 'kind', $event.target.value)">
                  <option v-for="option in aggregateWidgetKinds" :key="option.value" :value="option.value">
                    {{ option.label }}
                  </option>
                </select>
              </label>
              <label class="field household-aggregate-card__field">
                <span class="field__label">기간</span>
                <select :value="card.config.period" @change="updateAggregateWidget(card.index, 'period', $event.target.value)">
                  <option v-for="option in aggregateWidgetPeriods" :key="option.value" :value="option.value">
                    {{ option.label }}
                  </option>
                </select>
              </label>
              <label v-if="card.config.kind !== 'NONE'" class="field household-aggregate-card__field">
                <span class="field__label">기준</span>
                <select :value="card.config.amountType" @change="updateAggregateWidget(card.index, 'amountType', $event.target.value)">
                  <option v-for="option in aggregateWidgetAmountTypes" :key="option.value" :value="option.value">
                    {{ option.label }}
                  </option>
                </select>
              </label>
            </div>


            <label v-if="isAggregateEditMode && card.config.kind === 'PAYMENT_METHOD'" class="field household-aggregate-card__field">
              <span class="field__label">결제수단</span>
              <select :value="card.config.paymentMethodId" @change="updateAggregateWidget(card.index, 'paymentMethodId', $event.target.value)">
                <option v-for="payment in paymentMethods" :key="payment.id" :value="String(payment.id)">
                  {{ payment.name }}
                </option>
              </select>
            </label>

            <div class="household-aggregate-card__copy">
              <span class="household-aggregate-card__eyebrow">{{ card.title }}</span>
              <template v-if="card.config.kind === 'NONE'">
                <strong>-</strong>
                <small>이 슬롯은 저장 후 화면에서 숨김 처리됩니다.</small>
              </template>
              <template v-else>
                <strong>{{ formatCurrency(card.totalAmount) }}</strong>
                <small>{{ card.periodLabel }} 기준 {{ card.overview.entryCount }}건</small>
              </template>
            </div>

            <div v-if="card.config.kind !== 'NONE'" class="household-aggregate-card__meta">
              <span>수입 {{ formatCurrency(card.overview.income) }}</span>
              <span>지출 {{ formatCurrency(card.overview.expense) }}</span>
            </div>
          </article>
        </div>
        <div v-else class="household-aggregate-empty">
          <strong>표시 중인 집계가 없습니다.</strong>
          <span>우측 상단 수정 버튼에서 필요한 집계만 골라 등록하면 이 영역에 바로 나타납니다.</span>
        </div>
      </section>

              </template>

              <template v-else-if="panel.id === 'calendar'">
                <section
      :class="[
        'panel household-calendar-panel household-calendar-layout household-calendar-panel--content-only',
        { 'household-calendar-layout--amount-only': isAmountOnlyCalendar },
        { 'household-calendar-layout--fit': isFitCalendar },
        { 'household-calendar-layout--custom-size': isCalendarCustomSizeEnabled },
      ]"
      :style="calendarLayoutStyle"
    >
      <div v-if="isLayoutEditMode" class="household-calendar-panel-dragbar">
        달력 영역
      </div>
      <div v-if="!isCalendarCollapsed" ref="calendarShellRef" class="calendar-shell">
        <div class="calendar-scale-frame">
          <div ref="calendarContentRef" class="calendar-scale-content">
            <div class="calendar">
              <div class="calendar__weekdays">
                <span v-for="weekday in weekdayLabels" :key="weekday">{{ weekday }}</span>
              </div>

              <div class="calendar__weeks">
                <div v-for="(week, weekIndex) in displayedCalendarWeeks" :key="`week-${weekIndex}`" class="calendar__week">
              <article
                v-for="day in week"
                :key="day.date"
                :class="[
                  'calendar__day',
                  {
                    'calendar__day--muted': !day.inCurrentMonth,
                    'calendar__day--active': isExpenseHighlighted(day),
                    'calendar__day--income': isIncomeHighlighted(day),
                    'calendar__day--selected': selectedDate === day.date,
                  },
                ]"
                role="button"
                tabindex="0"
                @click="handleSelectDay(day)"
                @keydown.enter.prevent="handleSelectDay(day)"
              >
                <div class="calendar__day-head">
                  <div class="calendar__day-stamp">
                    <span v-if="getMonthTag(day)" class="calendar__month-tag">{{ getMonthTag(day) }}</span>
                    <strong>{{ day.dayNumber }}</strong>
                  </div>
                  <span v-if="!isAmountOnlyCalendar">{{ day.summary.entryCount }}건</span>
                </div>
                <div class="calendar__expense-block">
                  <span v-if="!isAmountOnlyCalendar" class="calendar__label">{{ getCalendarSummaryLabel() }}</span>
                  <strong class="calendar__expense-total">
                    {{ formatCompactCurrency(getCalendarSummaryAmount(day)) }}
                  </strong>
                </div>
                <div v-if="!isAmountOnlyCalendar" class="calendar__metrics">
                  <div v-if="shouldShowExpenseMetric()" class="calendar__metric calendar__metric--expense">
                    <span>지출</span>
                    <strong class="is-expense">{{ formatCurrency(day.summary.expense) }}</strong>
                  </div>
                  <div v-if="shouldShowIncomeMetric()" class="calendar__metric calendar__metric--income">
                    <span>수입</span>
                    <strong class="is-income">{{ formatCurrency(day.summary.income) }}</strong>
                  </div>
                </div>
                <div
                  :class="[
                    'calendar__bar',
                    {
                      'calendar__bar--income': isIncomeHighlighted(day),
                    },
                  ]"
                >
                  <span :style="{ width: `${getCalendarBarRatio(day)}%` }" />
                </div>
              </article>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div v-else class="household-calendar-collapsed-note">
        <strong>{{ formatShortDate(selectedDate) }}</strong>
        <span>달력을 접어 두었습니다. 다시 펼치면 날짜별 흐름을 바로 확인할 수 있습니다.</span>
      </div>
    </section>

              </template>

              <template v-else-if="panel.id === 'sheet'">
                <section ref="ledgerSheetRef" class="panel household-sheet-panel">
      <div class="panel__header">
        <div>
          <h2>{{ formatShortDate(selectedDate) }} 거래 시트</h2>
          <p>달력에서 고른 날짜의 거래만 모아서 바로 수정하거나 삭제할 수 있습니다.</p>
        </div>
        <div class="household-sheet-header">
          <span class="panel__badge">{{ selectedDateCountLabel }}</span>
          <div class="scope-toggle">
            <button type="button" class="button" :class="{ 'button--primary': selectedDayEntryFilter === 'ALL' }" @click="selectedDayEntryFilter = 'ALL'">전체</button>
            <button type="button" class="button" :class="{ 'button--primary': selectedDayEntryFilter === 'INCOME' }" @click="selectedDayEntryFilter = 'INCOME'">수입</button>
            <button type="button" class="button" :class="{ 'button--primary': selectedDayEntryFilter === 'EXPENSE' }" @click="selectedDayEntryFilter = 'EXPENSE'">지출</button>
          </div>
          <div class="scope-toggle">
            <button type="button" class="button" :class="{ 'button--primary': selectedDaySort === 'ASC' }" @click="selectedDaySort = 'ASC'">시간 오름차순</button>
            <button type="button" class="button" :class="{ 'button--primary': selectedDaySort === 'DESC' }" @click="selectedDaySort = 'DESC'">시간 내림차순</button>
          </div>
        </div>
      </div>

      <div class="sheet-table-wrap household-sheet-table-wrap">
        <table class="sheet-table">
          <thead>
            <tr>
              <th class="sheet-table__select">
                <input
                  class="sheet-table__checkbox"
                  type="checkbox"
                  :checked="selectedDayEntrySelection.allVisibleSelected"
                  :indeterminate.prop="selectedDayEntrySelection.someVisibleSelected"
                  @change="selectedDayEntrySelection.toggleAllVisible()"
                />
              </th>
              <th>시간</th>
              <th>구분</th>
              <th>제목</th>
              <th>카테고리</th>
              <th>결제수단</th>
              <th>금액</th>
              <th v-if="hasSelectedMemoColumn">메모</th>
              <th>작업</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="entry in pagedNormalizedSelectedDateEntries" :key="entry.id">
              <td class="sheet-table__select">
                <input
                  class="sheet-table__checkbox"
                  type="checkbox"
                  :checked="selectedDayEntrySelection.isSelected(entry)"
                  @change="selectedDayEntrySelection.toggleItem(entry)"
                />
              </td>
              <td>{{ formatTime(entry.entryTime) }}</td>
              <td>
                <span :class="['chip', entry.entryType === 'INCOME' ? 'chip--income' : 'chip--expense']">
                  {{ entry.entryType === 'INCOME' ? '수입' : '지출' }}
                </span>
              </td>
              <td class="sheet-table__title">{{ entry.title }}</td>
              <td class="sheet-table__category">
                {{ entry.categoryGroupName }}
                <template v-if="entry.categoryDetailName"> / {{ entry.categoryDetailName }}</template>
              </td>
              <td>{{ formatPaymentMethodForSheet(entry) }}</td>
              <td :class="['sheet-table__amount', entry.entryType === 'INCOME' ? 'is-income' : 'is-expense']">
                {{ formatCurrency(entry.amount) }}
              </td>
              <td v-if="hasSelectedMemoColumn" class="sheet-table__memo">{{ entry.visibleMemo || '-' }}</td>
              <td class="sheet-table__actions">
                <button type="button" class="button button--ghost" @click="emit('edit-entry', entry)">수정</button>
                <button type="button" class="button button--danger" @click="emit('delete-entry', entry)">삭제</button>
              </td>
            </tr>
            <tr v-if="!normalizedSelectedDateEntries.length">
              <td :colspan="hasSelectedMemoColumn ? 9 : 8" class="sheet-table__empty">선택한 날짜에는 거래가 없습니다.</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="normalizedSelectedDateEntries.length > SELECTED_DAY_ENTRY_PAGE_SIZE" class="panel__actions">
        <button class="button button--ghost" type="button" :disabled="selectedDayEntryPage <= 0" @click="selectedDayEntryPage -= 1">이전</button>
        <span>{{ selectedDayEntryPage + 1 }} / {{ selectedDayEntryPageCount }}</span>
        <button class="button button--ghost" type="button" :disabled="selectedDayEntryPage + 1 >= selectedDayEntryPageCount" @click="selectedDayEntryPage += 1">다음</button>
      </div>
    </section>
              </template>
            </div>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>



