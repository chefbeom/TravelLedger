<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
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
  aiAnalysisStale: {
    type: Boolean,
    default: false,
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
  const cards = [
    { label: '총 지출', value: props.formatCurrency(props.aiAnalysis.totalExpense), meta: `${props.aiAnalysis.expenseEntryCount ?? 0}건 내역` },
    { label: '일 평균', value: props.formatCurrency(props.aiAnalysis.averageDailyExpense), meta: formatAiRange(props.aiAnalysis.from, props.aiAnalysis.to) },
  ]
  if (props.aiAnalysis.compareFrom) {
    cards.push(
      { label: '비교 지출', value: props.formatCurrency(props.aiAnalysis.compareTotalExpense), meta: formatAiRange(props.aiAnalysis.compareFrom, props.aiAnalysis.compareTo) },
      { label: '증감', value: `${aiCompareDelta.value > 0 ? '+' : ''}${props.formatCurrency(aiCompareDelta.value)}`, meta: '비교 기간 대비' },
    )
  } else {
    cards.push({ label: '비교', value: '비교 불가', meta: '비교 기간 없음' })
  }
  return cards
})
const rawAiReport = computed(() => props.aiAnalysis?.report ?? {})
const parsedAiPayload = computed(() => findParsedAiPayload(props.aiAnalysis, rawAiReport.value))
const aiReport = computed(() => buildNormalizedAiReport(rawAiReport.value, parsedAiPayload.value, props.aiAnalysis))
const aiReportKeySummary = computed(() => sanitizeAiText(aiReport.value.keySummary || props.aiAnalysis?.summary || ''))
const aiReportFullReport = computed(() => sanitizeAiText(aiReport.value.fullReport || ''))
const aiIsComparisonResult = computed(() => props.aiAnalysis?.mode === 'COMPARISON')
const aiFixedReportItems = computed(() => dedupeAiItems([
  ...safeAiReportList('regularSpending', props.aiAnalysis?.fixedCostInsights),
  ...safeAiReportList('subscriptions'),
  ...safeAiReportList('fixedExpenses'),
]))
const aiAbnormalReportItems = computed(() => dedupeAiItems([
  ...safeAiList(props.aiAnalysis?.warnings),
  ...safeAiList(props.aiAnalysis?.unusualSpendingInsights),
  ...safeAiReportList('abnormalSpending'),
]))
const aiComparisonReportItems = computed(() => safeAiReportList('comparisonFocus', props.aiAnalysis?.trendInsights))
const aiTopCategoryItems = computed(() => topAiBreakdownItems(props.aiAnalysis?.categoryBreakdown, formatAiCategoryBreakdownLabel))
const aiPaymentBreakdownItems = computed(() => normalizeAiPaymentBreakdown(props.aiAnalysis?.paymentBreakdown, props.aiAnalysis?.totalExpense))
const aiTopPaymentItems = computed(() => topAiBreakdownItems(aiPaymentBreakdownItems.value, formatAiPaymentBreakdownLabel, 3, props.aiAnalysis?.totalExpense))
const aiComputedTopPaymentInsight = computed(() => buildComputedTopPaymentInsight())
const aiImprovementDirectionItems = computed(() => buildAiImprovementDirectionItems())
const aiPrintableCards = computed(() => [
  ...aiResultCards.value,
  ...buildAiFocusMetricCards(),
])
const hasStaleAiResult = computed(() => Boolean(props.aiAnalysisStale && props.aiAnalysis))
const aiResultModalOpen = ref(false)
const pendingAiHistoryPrintId = ref(null)
const aiProgressStartedAt = ref(0)
const aiProgressTick = ref(Date.now())
let aiProgressTimerId = null

const aiProgressSteps = [
  { key: 'queued', label: '요청 접수', detail: '분석 요청을 등록하고 입력 조건을 확인합니다.' },
  { key: 'dataset', label: '데이터 정리', detail: '선택한 기간의 거래 데이터를 분석용으로 정리합니다.' },
  { key: 'provider', label: 'AI 응답 대기', detail: 'LM Studio 또는 n8n 응답을 기다립니다. 로컬 모델은 시간이 걸릴 수 있습니다.' },
  { key: 'save', label: '결과 저장', detail: '분석 결과를 저장하고 화면에 표시할 준비를 합니다.' },
]

const aiProgressElapsedSeconds = computed(() => {
  if (!props.aiAnalysisLoading || !aiProgressStartedAt.value) return 0
  return Math.max(0, Math.floor((aiProgressTick.value - aiProgressStartedAt.value) / 1000))
})

const aiProgressStepIndex = computed(() => {
  const seconds = aiProgressElapsedSeconds.value
  if (seconds < 4) return 0
  if (seconds < 10) return 1
  if (seconds < 55) return 2
  return 3
})

const aiProgressPercent = computed(() => {
  if (!props.aiAnalysisLoading) return 100
  const seconds = aiProgressElapsedSeconds.value
  if (seconds < 4) return Math.min(22, 8 + seconds * 4)
  if (seconds < 10) return Math.min(42, 24 + (seconds - 4) * 3)
  if (seconds < 55) return Math.min(88, 44 + Math.floor((seconds - 10) * 0.95))
  return Math.min(94, 89 + Math.floor((seconds - 55) * 0.2))
})

const aiProgressElapsedLabel = computed(() => {
  const seconds = aiProgressElapsedSeconds.value
  const minutes = Math.floor(seconds / 60).toString().padStart(2, '0')
  const remain = (seconds % 60).toString().padStart(2, '0')
  return minutes + ':' + remain
})

const aiProgressVisible = computed(() => Boolean(props.aiAnalysisLoading))
const aiProgressCurrentStep = computed(() => aiProgressSteps[aiProgressStepIndex.value] ?? aiProgressSteps[0])

const aiHistoryCompletedCount = computed(() => aiAnalysisHistoryItems.value.filter((history) => history.status === 'COMPLETED').length)
const aiHistoryFailedCount = computed(() => aiAnalysisHistoryItems.value.filter((history) => history.status === 'FAILED').length)
const aiPresentationSections = computed(() => buildAiPresentationSections())
const aiPrintableSections = computed(() => aiPresentationSections.value
  .filter((section) => !['evidence', 'actions'].includes(section.key))
  .map(compactAiPrintableSection))
const aiPrintableReport = computed(() => {
  const analysis = props.aiAnalysis
  return {
    title: 'TravelLedger AI 소비 분석 리포트',
    printTitle: buildAiPrintTitle(analysis),
    reportId: buildAiReportId(analysis),
    subtitle: '가계부 데이터 기반 소비 패턴 진단 및 실행 권고',
    generatedAt: new Date().toLocaleString('ko-KR'),
    analysisGeneratedAt: formatAiCreatedAt(analysis?.generatedAt),
    mode: formatAiMode(analysis?.mode),
    period: formatAiPeriod(analysis?.periodType),
    range: analysis ? formatAiRange(analysis.from, analysis.to) : '',
    comparisonRange: analysis?.compareFrom ? formatAiRange(analysis.compareFrom, analysis.compareTo) : '',
    model: analysis?.model || props.aiAnalysisStatus?.model || 'auto',
    stale: hasStaleAiResult.value,
    cards: aiPrintableCards.value,
    sections: aiPrintableSections.value,
    executiveSummary: buildAiExecutiveSummary(analysis),
    reportOutline: buildAiReportOutline(),
    dataEvidence: buildAiDataEvidence(analysis),
    actionPriorities: buildAiActionPriorities(analysis),
    conclusion: buildAiReportConclusion(analysis),
    reviewChecklist: buildAiReviewChecklist(analysis),
    qualityNotes: buildAiQualityNotes(analysis),
    methodology: buildAiMethodology(analysis),
    advisory: '본 리포트는 가계부 데이터와 AI 분석 결과를 바탕으로 작성된 참고용 분석 자료입니다. 거래 생성, 수정, 삭제, 분류 변경은 사용자의 확인 후 별도로 수행해야 합니다.',
  }
})
function buildAiPrintTitle(analysis) {
  const range = analysis ? formatAiRange(analysis.from, analysis.to).replace(/\s+/g, '') : 'preview'
  const id = analysis?.historyId ?? 'draft'
  return `TravelLedger_AI_Report_${id}_${range}`
}
function buildAiReportId(analysis) {
  const id = analysis?.historyId ?? 'draft'
  const generated = String(analysis?.generatedAt || new Date().toISOString()).replace(/[^0-9]/g, '').slice(0, 14)
  return `TL-AI-${id}-${generated || 'preview'}`
}
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

function aiNumericAmount(value) {
  const amount = Number(value ?? 0)
  return Number.isFinite(amount) ? amount : 0
}

function formatAiShare(amount, total) {
  const safeTotal = aiNumericAmount(total)
  if (safeTotal <= 0) {
    return '비중 산정 불가'
  }
  return `${((aiNumericAmount(amount) / safeTotal) * 100).toFixed(1)}%`
}

function formatAiCategoryBreakdownLabel(item) {
  if (!item) {
    return '미분류'
  }
  return item.detailName ? `${item.groupName || '미분류'} / ${item.detailName}` : (item.groupName || '미분류')
}

function formatAiPaymentBreakdownLabel(item) {
  if (!item) {
    return '결제수단 미확인'
  }
  return item.kind ? `${item.paymentMethodName || '결제수단 미확인'} (${item.kind})` : (item.paymentMethodName || '결제수단 미확인')
}

function normalizeAiPaymentBreakdown(items, totalExpense) {
  const source = Array.isArray(items) ? items : []
  const total = aiNumericAmount(totalExpense)
  return source
    .filter((item) => aiNumericAmount(item?.totalAmount) > 0)
    .filter((item) => !isIncomePlaceholderPayment(item))
    .filter((item) => total <= 0 || aiNumericAmount(item?.totalAmount) <= total + 1)
}

function isIncomePlaceholderPayment(item) {
  const name = String(item?.paymentMethodName ?? '').trim().toLowerCase()
  return name === '-' || name === 'income' || name === '수입'
}

function topAiBreakdownItems(items, labeler, limit = 3, totalOverride = null) {
  const source = Array.isArray(items) ? items : []
  const calculatedTotal = source.reduce((sum, item) => sum + aiNumericAmount(item?.totalAmount), 0)
  const total = totalOverride == null ? calculatedTotal : aiNumericAmount(totalOverride)
  return source
    .map((item) => ({
      label: labeler(item),
      amount: aiNumericAmount(item?.totalAmount),
      entryCount: Number(item?.entryCount ?? 0),
      share: formatAiShare(item?.totalAmount, total || calculatedTotal),
    }))
    .filter((item) => item.amount > 0)
    .sort((a, b) => b.amount - a.amount)
    .slice(0, limit)
    .map((item) => `${item.label}: ${props.formatCurrency(item.amount)} · ${item.entryCount}건 · ${item.share}`)
}

function buildComputedTopPaymentInsight() {
  const topPayment = splitAiTopBreakdownItem(aiTopPaymentItems.value[0])
  if (!topPayment) {
    return ''
  }
  return `지출 기준 결제수단 1위는 ${topPayment.label}이며 ${topPayment.meta}입니다. 결제수단 해석은 수입용 결제수단을 제외한 지출 집계만 기준으로 합니다.`
}

function buildAiImprovementDirectionItems() {
  const analysis = props.aiAnalysis
  if (!analysis) {
    return []
  }
  const source = safeAiReportList('improvementActions', analysis.recommendations)
  const explicit = source.filter((item) => /개선 방향|실행 방법|관리 수단|점검 수단|로드맵|상한|유지\/보류\/해지/.test(item))
  const generated = []
  if (aiTopCategoryItems.value[0]) {
    generated.push(`개선 방향: 가장 큰 지출 축인 ${aiTopCategoryItems.value[0]} 항목은 다음 기간 예산 한도를 먼저 정하고, 필수 지출과 선택 지출을 분리해 보세요.`)
  }
  if (aiAbnormalReportItems.value[0]) {
    generated.push(`실행 방법: ${aiAbnormalReportItems.value[0]} 항목은 비정기 지출로 따로 표시하고, 월 생활비 평가에서 분리해 원인을 확인하세요.`)
  }
  if (aiFixedReportItems.value[0]) {
    generated.push(`관리 수단: ${aiFixedReportItems.value[0]} 항목은 유지/보류/해지 후보로 나누고, 사용 빈도와 월 금액을 함께 기록하세요.`)
  }
  generated.push('점검 수단: 예산 알림, 자동 분류 규칙, 비정기 지출 태그, 월말 PDF 보고서를 함께 사용해 다음 기간 개선 여부를 확인하세요.')
  return dedupeAiItems([...explicit, ...generated])
}
function buildAiFocusMetricCards() {
  const focusCards = []
  const topCategory = splitAiTopBreakdownItem(aiTopCategoryItems.value[0])
  const topPayment = splitAiTopBreakdownItem(aiTopPaymentItems.value[0])
  if (topCategory) {
    focusCards.push({ label: '최대 지출 축', value: topCategory.label, meta: topCategory.meta || '상위 카테고리' })
  }
  if (topPayment) {
    focusCards.push({ label: '주요 결제수단', value: topPayment.label, meta: topPayment.meta || '상위 결제수단' })
  }
  return focusCards
}

function splitAiTopBreakdownItem(value) {
  const text = sanitizeAiText(value)
  if (!text) {
    return null
  }
  const separator = text.indexOf(': ')
  if (separator < 0) {
    return { label: text, meta: '' }
  }
  return {
    label: text.slice(0, separator),
    meta: text.slice(separator + 2),
  }
}

function buildAiDataEvidence(analysis) {
  if (!analysis) {
    return []
  }
  const items = [
    `분석 데이터: ${formatAiRange(analysis.from, analysis.to)} 기간의 지출 ${analysis.expenseEntryCount ?? 0}건`,
    `총 지출 ${props.formatCurrency(analysis.totalExpense)} / 일 평균 ${props.formatCurrency(analysis.averageDailyExpense)}`,
    analysis.compareFrom ? `비교 기준: ${formatAiRange(analysis.compareFrom, analysis.compareTo)} 기간 총 지출 ${props.formatCurrency(analysis.compareTotalExpense)}` : '비교 기준: 단일 기간 분석',
  ]
  if (aiTopCategoryItems.value.length) {
    items.push(`상위 지출 카테고리: ${aiTopCategoryItems.value.join(' / ')}`)
  }
  if (aiTopPaymentItems.value.length) {
    items.push(`주요 결제수단: ${aiTopPaymentItems.value.join(' / ')}`)
  }
  return dedupeAiItems(items)
}

function buildAiActionPriorities(analysis) {
  if (!analysis) {
    return []
  }
  const improvementItems = safeAiReportList('improvementActions', analysis.recommendations)
  const priorities = []
  if (aiAbnormalReportItems.value.length) {
    priorities.push(`1순위 점검: 이상/주의 지출 후보를 먼저 확인하세요. ${aiAbnormalReportItems.value[0]}`)
  }
  if (aiFixedReportItems.value.length) {
    priorities.push(`2순위 관리: 반복/구독성 지출을 예산 항목으로 분리하세요. ${aiFixedReportItems.value[0]}`)
  }
  if (aiTopCategoryItems.value.length) {
    priorities.push(`3순위 조정: 가장 큰 지출 축은 ${aiTopCategoryItems.value[0]}입니다. 해당 카테고리의 필요/충동 지출을 구분하세요.`)
  }
  improvementItems.slice(0, 3).forEach((item, index) => {
    priorities.push(`AI 권고 ${index + 1}: ${item}`)
  })
  return dedupeAiItems(priorities)
}

function buildAiReportConclusion(analysis) {
  if (!analysis) {
    return []
  }
  const totalExpense = aiNumericAmount(analysis.totalExpense)
  const compareExpense = aiNumericAmount(analysis.compareTotalExpense)
  const delta = totalExpense - compareExpense
  const deltaText = analysis.compareFrom
    ? `비교 기간 대비 ${props.formatCurrency(Math.abs(delta))} ${delta > 0 ? '증가' : delta < 0 ? '감소' : '변동 없음'}했습니다.`
    : '비교 기간이 없어 단일 기간 기준으로 해석합니다.'
  const concentrationText = aiTopCategoryItems.value[0]
    ? `소비 집중도는 ${aiTopCategoryItems.value[0]} 항목에서 가장 높게 나타납니다.`
    : '상위 카테고리 집중도는 산정할 수 없습니다.'
  const controlText = aiAbnormalReportItems.value.length || aiFixedReportItems.value.length
    ? '우선 관리는 이상 지출 후보와 반복/구독성 지출 점검에 두는 것이 적절합니다.'
    : '뚜렷한 이상 신호가 부족하므로 예산 대비 추세 확인을 우선합니다.'
  return dedupeAiItems([
    `종합 판단: ${formatAiRange(analysis.from, analysis.to)} 기간의 지출 규모는 ${props.formatCurrency(totalExpense)}이며, ${deltaText}`,
    concentrationText,
    controlText,
  ])
}

function buildAiReviewChecklist(analysis) {
  if (!analysis) {
    return []
  }
  return dedupeAiItems([
    '상위 지출 카테고리의 필수/선택 지출을 분리해 확인합니다.',
    '반복 결제와 구독성 지출은 다음 달 예산에 별도 항목으로 반영합니다.',
    '이상/주의 지출 후보는 중복 결제, 일회성 고액 지출, 결제수단 오류 여부를 확인합니다.',
    'AI 권고를 가계부에 반영하기 전 거래 원본과 영수증 또는 결제 내역을 대조합니다.',
  ])
}

function buildAiQualityNotes(analysis) {
  if (!analysis) {
    return []
  }
  const entryCount = Number(analysis.expenseEntryCount ?? 0)
  return dedupeAiItems([
    entryCount > 0 ? `분석 신뢰 기준: ${entryCount}건의 지출 데이터가 포함되었습니다.` : '분석 신뢰 기준: 지출 데이터가 부족해 해석 범위가 제한됩니다.',
    aiTopCategoryItems.value.length ? '카테고리 집중도는 저장된 카테고리 집계 금액을 기준으로 산출했습니다.' : '카테고리 집계가 부족해 지출 집중도 판단은 제한됩니다.',
    aiTopPaymentItems.value.length ? '결제수단 분석은 수입용 결제수단을 제외한 지출 기준 금액과 건수를 기준으로 산출했습니다.' : '결제수단 집계가 부족하거나 총 지출보다 큰 비정상 결제수단 항목이 제외되어 결제 패턴 판단은 제한됩니다.',
    analysis.compareFrom ? '비교 분석은 선택된 비교 기간의 총 지출과 현재 기간 총 지출을 단순 비교합니다.' : '비교 기간이 없어 증가/감소 평가는 제공하지 않습니다.',
    hasStaleAiResult.value ? '주의: 새 분석 실패로 이전 저장 결과가 표시되어 최신 거래와 차이가 있을 수 있습니다.' : '',
  ])
}

function buildAiReportOutline() {
  return [
    'Executive Summary: 분석 기간의 핵심 지표와 주요 판단을 빠르게 확인합니다.',
    '데이터 근거 및 우선 조치: 실제 집계값과 먼저 확인할 실행 항목을 확인합니다.',
    '종합 판단 및 체크리스트: 결론과 사용자가 직접 검토할 항목을 확인합니다.',
    '방법론 및 품질 한계: 계산 기준, 비교 기준, 해석 제한 사항을 확인합니다.',
    '상세 분석: 보고서, 결제방법, 소비 패턴, 개선 방향/방법/수단, 고정/이상 지출, 비교 분석을 세부적으로 검토합니다.',
  ]
}
function buildAiMethodology(analysis) {
  if (!analysis) {
    return []
  }
  return dedupeAiItems([
    '금액 지표는 저장된 거래 데이터의 지출 합계, 일 평균, 결제수단별 합계, 카테고리별 합계를 기준으로 계산했습니다.',
    '상위 지출 카테고리와 주요 결제수단은 금액 기준으로 내림차순 정렬한 뒤 상위 항목을 표시합니다.',
    analysis.compareFrom ? '증감 평가는 현재 분석 기간과 사용자가 선택한 비교 기간의 총 지출 차이를 기준으로 합니다.' : '비교 기간이 없는 경우 증가/감소 평가는 표시하지 않습니다.',
    'AI 문장은 계산된 집계값과 저장된 분석 결과를 해석한 설명이며, 원본 거래 데이터의 자동 변경에는 사용하지 않습니다.',
  ])
}
function safeAiReportList(fieldName, fallbackItems = []) {
  const reportItems = safeAiList(aiReport.value?.[fieldName])
  if (reportItems.length) {
    return reportItems
  }
  return safeAiList(fallbackItems)
}
function sanitizeAiText(value) {
  return String(value ?? '')
    .replace(/```(?:json)?/gi, '')
    .replace(/```/g, '')
    .replace(/\s+/g, ' ')
    .trim()
}

function isRawJsonLike(value) {
  const text = sanitizeAiText(value)
  return text.startsWith('{') && (text.includes('"ok"') || text.includes('"report"') || text.includes('"summary"'))
}

function extractJsonCandidate(value) {
  const text = sanitizeAiText(value)
  const start = text.indexOf('{')
  const end = text.lastIndexOf('}')
  if (start < 0 || end <= start) {
    return ''
  }
  return text.slice(start, end + 1)
}

function parseAiJsonCandidate(value) {
  const json = extractJsonCandidate(value)
  if (!json) {
    return null
  }
  try {
    return JSON.parse(json)
  } catch {
    try {
      return JSON.parse(json.replace(/,\s*([}\]])/g, '$1'))
    } catch {
      return null
    }
  }
}

function collectAiTextCandidates(analysis, report) {
  return [
    analysis?.summary,
    analysis?.nextPeriodForecast,
    analysis?.habitAssessment,
    report?.keySummary,
    report?.fullReport,
    report?.averageAmountInsight,
    report?.topPaymentMethod,
    ...(analysis?.highlights ?? []),
    ...(analysis?.warnings ?? []),
    ...(analysis?.recommendations ?? []),
    ...(analysis?.categoryInsights ?? []),
    ...(analysis?.paymentInsights ?? []),
    ...(analysis?.trendInsights ?? []),
    ...(analysis?.unusualSpendingInsights ?? []),
    ...(analysis?.fixedCostInsights ?? []),
    ...(report?.notableSpending ?? []),
    ...(report?.regularSpending ?? []),
    ...(report?.abnormalSpending ?? []),
    ...(report?.subscriptions ?? []),
    ...(report?.fixedExpenses ?? []),
    ...(report?.improvementActions ?? []),
    ...(report?.comparisonFocus ?? []),
  ].filter(Boolean)
}

function findParsedAiPayload(analysis, report) {
  for (const candidate of collectAiTextCandidates(analysis, report)) {
    const parsed = parseAiJsonCandidate(candidate)
    if (parsed && (parsed.report || parsed.summary || parsed.ok != null)) {
      return parsed
    }
  }
  return null
}

function firstNonRawText(...values) {
  return values.map(sanitizeAiText).find((value) => value && !isRawJsonLike(value)) || ''
}

function listFrom(...values) {
  return dedupeAiItems(values.flatMap((value) => Array.isArray(value) ? value : value ? [value] : []).map(sanitizeAiText).filter((item) => item && !isRawJsonLike(item)))
}

function buildNormalizedAiReport(report, parsed, analysis) {
  const parsedReport = parsed?.report ?? {}
  return {
    keySummary: firstNonRawText(report?.keySummary, parsedReport?.keySummary, parsed?.summary, analysis?.summary),
    fullReport: firstNonRawText(report?.fullReport, parsedReport?.fullReport),
    averageAmountInsight: firstNonRawText(report?.averageAmountInsight, parsedReport?.averageAmountInsight),
    notableSpending: listFrom(report?.notableSpending, parsedReport?.notableSpending, parsed?.highlights, analysis?.highlights),
    regularSpending: listFrom(report?.regularSpending, parsedReport?.regularSpending),
    abnormalSpending: listFrom(report?.abnormalSpending, parsedReport?.abnormalSpending, parsed?.warnings, parsed?.unusualSpendingInsights),
    topPaymentMethod: firstNonRawText(report?.topPaymentMethod, parsedReport?.topPaymentMethod),
    subscriptions: listFrom(report?.subscriptions, parsedReport?.subscriptions),
    fixedExpenses: listFrom(report?.fixedExpenses, parsedReport?.fixedExpenses),
    improvementActions: listFrom(report?.improvementActions, parsedReport?.improvementActions, parsed?.recommendations, analysis?.recommendations),
    comparisonFocus: listFrom(report?.comparisonFocus, parsedReport?.comparisonFocus, parsed?.trendInsights, analysis?.trendInsights),
  }
}

function dedupeAiItems(items) {
  const seen = new Set()
  return (items ?? []).filter((item) => {
    const value = sanitizeAiText(item)
    if (!value || seen.has(value)) {
      return false
    }
    seen.add(value)
    return true
  })
}

function splitAiParagraphs(text) {
  const value = sanitizeAiText(text)
  if (!value || isRawJsonLike(value)) {
    return []
  }
  return value
    .replace(/([.!?다요니다])\s+(?=[가-힣A-Z0-9])/g, '$1\n')
    .split(/\n+/)
    .map((item) => item.trim())
    .filter(Boolean)
}

function createAiSection(key, title, paragraphs = [], items = [], wide = false) {
  return {
    key,
    title,
    wide,
    paragraphs: dedupeAiItems(paragraphs.flatMap(splitAiParagraphs)),
    items: dedupeAiItems(items),
  }
}

const AI_PRINT_SECTION_LIMITS = {
  summary: { paragraphs: 2, items: 0, paragraphLength: 260, itemLength: 180 },
  overview: { paragraphs: 2, items: 3, paragraphLength: 240, itemLength: 180 },
  report: { paragraphs: 5, items: 0, paragraphLength: 420, itemLength: 220 },
  payment: { paragraphs: 1, items: 5, paragraphLength: 240, itemLength: 220 },
  notable: { paragraphs: 1, items: 6, paragraphLength: 220, itemLength: 220 },
  fixed: { paragraphs: 1, items: 6, paragraphLength: 220, itemLength: 220 },
  abnormal: { paragraphs: 1, items: 6, paragraphLength: 220, itemLength: 220 },
  comparison: { paragraphs: 1, items: 5, paragraphLength: 220, itemLength: 220 },
  improvementRoadmap: { paragraphs: 1, items: 12, paragraphLength: 260, itemLength: 260 },
}

function compactAiPrintableSection(section) {
  const limits = AI_PRINT_SECTION_LIMITS[section.key] ?? { paragraphs: 3, items: 5, paragraphLength: 260, itemLength: 220 }
  return {
    ...section,
    paragraphs: limitAiPrintEntries(section.paragraphs, limits.paragraphs, limits.paragraphLength),
    items: limitAiPrintEntries(section.items, limits.items, limits.itemLength),
  }
}

function limitAiPrintEntries(items, limit, maxLength) {
  if (limit === 0) {
    return []
  }
  return dedupeAiItems(items ?? []).map((item) => shortenAiPrintText(item, maxLength))
}

function shortenAiPrintText(text, maxLength) {
  return sanitizeAiText(text)
}
function buildAiPresentationSections() {
  if (!props.aiAnalysis) {
    return []
  }
  const analysis = props.aiAnalysis
  const report = aiReport.value
  const improvementItems = safeAiReportList('improvementActions', analysis.recommendations)
  const dataEvidence = buildAiDataEvidence(analysis)
  const actionPriorities = buildAiActionPriorities(analysis)
  return [
    createAiSection('summary', '핵심 요약', [aiReportKeySummary.value || `${formatAiRange(analysis.from, analysis.to)} 기간의 소비 분석입니다.`], [], true),
    createAiSection('overview', '지출 개요', [
      `분석 기간은 ${formatAiRange(analysis.from, analysis.to)}입니다.`,
      `총 지출은 ${props.formatCurrency(analysis.totalExpense)}이고 하루 평균 지출은 ${props.formatCurrency(analysis.averageDailyExpense)}입니다.`,
    ], [
      `${analysis.expenseEntryCount ?? 0}건의 지출 내역을 기준으로 계산했습니다.`,
      analysis.compareFrom ? `비교 기간은 ${formatAiRange(analysis.compareFrom, analysis.compareTo)}입니다.` : '비교 기간은 설정되지 않았습니다.',
    ]),
    createAiSection('evidence', '데이터 근거', ['아래 항목은 AI 문장이 아니라 저장된 가계부 집계값으로 산출한 핵심 근거입니다.'], dataEvidence, true),
    createAiSection('report', '보고서', [aiReportFullReport.value || 'AI 구조화 보고서가 부족해 핵심 지표 중심으로 표시합니다.'], [], true),
    createAiSection('payment', '결제방법', [aiComputedTopPaymentInsight.value || '결제수단 분석은 상위 결제수단과 지출 비중을 기준으로 확인하세요.'], aiTopPaymentItems.value.length ? aiTopPaymentItems.value : safeAiList(analysis.paymentInsights)),
    createAiSection('notable', '눈에 띄는 소비', [], safeAiReportList('notableSpending', analysis.highlights)),
    createAiSection('fixed', '고정비/구독/반복성 지출', [], aiFixedReportItems.value.length ? aiFixedReportItems.value : ['고정비, 구독비, 반복성 변동비 후보가 부족하거나 아직 확인되지 않았습니다.']),
    createAiSection('abnormal', '이상/주의 지출', [], aiAbnormalReportItems.value.length ? aiAbnormalReportItems.value : ['확인이 필요한 이상 지출 후보가 뚜렷하게 반환되지 않았습니다.']),
    createAiSection('actions', '우선 조치 권고', ['지출 규모, 반복성, 이상 신호를 기준으로 먼저 확인할 항목입니다.'], actionPriorities.length ? actionPriorities : (improvementItems.length ? improvementItems : ['예산 조정이나 분류 변경은 사용자가 직접 확인한 뒤 반영하세요.']), true),
    createAiSection('comparison', '비교 분석', [], aiComparisonReportItems.value.length ? aiComparisonReportItems.value : [aiIsComparisonResult.value ? '비교 분석 결과가 부족합니다.' : '비교 분석 모드가 아니므로 비교 항목은 생략됩니다.']),
  ]
}
function openAiResultModal() {
  if (aiHasResult.value) {
    aiResultModalOpen.value = true
  }
}

function closeAiResultModal() {
  aiResultModalOpen.value = false
}

function handleAiResultEscape(event) {
  if (event.key === 'Escape') {
    closeAiResultModal()
  }
}

function escapePrintHtml(value) {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

function buildAiExecutiveSummary(analysis) {
  if (!analysis) {
    return []
  }
  const delta = Number(aiCompareDelta.value || 0)
  const deltaDirection = delta > 0 ? '증가' : delta < 0 ? '감소' : '변동 없음'
  const keySummary = splitAiParagraphs(aiReportKeySummary.value)[0]
  return dedupeAiItems([
    `${formatAiRange(analysis.from, analysis.to)} 기간의 ${analysis.expenseEntryCount ?? 0}건 지출 내역을 기준으로 분석했습니다.`,
    `총 지출은 ${props.formatCurrency(analysis.totalExpense)}, 일 평균 지출은 ${props.formatCurrency(analysis.averageDailyExpense)}입니다.`,
    analysis.compareFrom ? `비교 기간(${formatAiRange(analysis.compareFrom, analysis.compareTo)}) 대비 지출 변화는 ${props.formatCurrency(Math.abs(delta))} ${deltaDirection}입니다.` : '',
    aiTopCategoryItems.value[0] ? `최대 지출 축: ${aiTopCategoryItems.value[0]}` : '',
    aiTopPaymentItems.value[0] ? `주요 결제수단: ${aiTopPaymentItems.value[0]}` : '',
    keySummary ? `핵심 판단: ${keySummary}` : '',
    'AI 권고는 자동 반영되지 않으며, 예산 조정이나 거래 분류 변경은 사용자가 직접 확인한 뒤 적용해야 합니다.',
  ])
}

function renderPrintParagraphs(paragraphs, emptyText = '') {
  const items = dedupeAiItems(paragraphs ?? [])
  if (!items.length) {
    return emptyText ? `<p class="muted">${escapePrintHtml(emptyText)}</p>` : ''
  }
  return items.map((paragraph) => `<p>${escapePrintHtml(paragraph)}</p>`).join('')
}

function renderPrintList(items, className = '') {
  const values = dedupeAiItems(items ?? [])
  if (!values.length) {
    return ''
  }
  const classAttribute = className ? ` class="${escapePrintHtml(className)}"` : ''
  return `<ul${classAttribute}>${values.map((item) => `<li>${escapePrintHtml(item)}</li>`).join('')}</ul>`
}

function renderPrintSection(section, index) {
  const items = renderPrintList(section.items)
  const paragraphs = renderPrintParagraphs(section.paragraphs, items ? '' : '반환된 분석 내용이 부족해 기본 지표 중심으로 확인해 주세요.')
  const wideClass = section.wide ? ' report-section--wide' : ''
  return `
    <section class="report-section${wideClass}">
      <div class="section-kicker">${String(index + 1).padStart(2, '0')}</div>
      <h2>${escapePrintHtml(section.title)}</h2>
      ${paragraphs}
      ${items}
    </section>
  `
}

function renderPrintMeta(report) {
  const rows = [
    ['리포트 ID', report.reportId],
    ['문서 구분', '개인 재무 AI 분석 참고 보고서'],
    ['주의 등급', report.stale ? '이전 결과 기준' : '정상 생성 결과'],
    ['분석 유형', report.mode],
    ['분석 단위', report.period],
    ['분석 기간', report.range],
    ['비교 기간', report.comparisonRange || '비교 없음'],
    ['AI 모델', report.model],
    ['결과 생성', report.analysisGeneratedAt || '-'],
    ['PDF 생성', report.generatedAt],
  ]
  return rows.map(([label, value]) => `
    <div class="meta-row">
      <span>${escapePrintHtml(label)}</span>
      <strong>${escapePrintHtml(value)}</strong>
    </div>
  `).join('')
}

function renderPrintCards(cards) {
  return cards.map((card) => `
    <article class="metric-card">
      <span>${escapePrintHtml(card.label)}</span>
      <strong>${escapePrintHtml(card.value)}</strong>
      <small>${escapePrintHtml(card.meta)}</small>
    </article>
  `).join('')
}

function buildAiPrintHtml(report) {
  const cards = renderPrintCards(report.cards)
  const sections = report.sections.map(renderPrintSection).join('')
  const executiveSummary = renderPrintList(report.executiveSummary, 'executive-list')
  const reportOutline = renderPrintList(report.reportOutline, 'outline-list')
  const dataEvidence = renderPrintList(report.dataEvidence, 'evidence-list')
  const actionPriorities = renderPrintList(report.actionPriorities, 'priority-list')
  const conclusion = renderPrintList(report.conclusion, 'conclusion-list')
  const reviewChecklist = renderPrintList(report.reviewChecklist, 'checklist')
  const qualityNotes = renderPrintList(report.qualityNotes, 'quality-list')
  const methodology = renderPrintList(report.methodology, 'methodology-list')
  const staleNotice = report.stale ? '<div class="notice notice--warning">새 분석 요청 실패로 이전 저장 결과를 기준으로 출력했습니다.</div>' : ''
  const styles = `
    @page { size: A4; margin: 16mm 14mm; }
    * { box-sizing: border-box; }
    body { margin: 0; color: #172033; font-family: 'Malgun Gothic', 'Apple SD Gothic Neo', 'Noto Sans KR', sans-serif; line-height: 1.68; background: #ffffff; }
    .cover { padding: 22px 0 18px; border-bottom: 3px solid #1d4ed8; }
    .eyebrow { color: #1d4ed8; font-size: 11px; font-weight: 900; letter-spacing: 0.18em; text-transform: uppercase; }
    h1 { margin: 8px 0 6px; font-size: 30px; letter-spacing: -0.04em; color: #0f172a; }
    .subtitle { margin: 0; color: #475569; font-size: 14px; }
    .report-id-badge { display: inline-flex; margin-top: 12px; padding: 5px 10px; border-radius: 999px; background: #e0f2fe; color: #075985; font-size: 11px; font-weight: 900; letter-spacing: 0.04em; }
    .report-meta { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px 18px; margin: 18px 0 0; padding: 16px; border: 1px solid #dbe4f0; border-radius: 16px; background: #f8fafc; }
    .meta-row { display: flex; justify-content: space-between; gap: 12px; font-size: 12px; border-bottom: 1px dashed #dbe4f0; padding-bottom: 6px; }
    .meta-row span { color: #64748b; }
    .meta-row strong { color: #0f172a; text-align: right; }
    .notice { margin: 16px 0 0; padding: 12px 14px; border-radius: 14px; font-size: 12px; font-weight: 800; }
    .notice--warning { color: #92400e; background: #fffbeb; border: 1px solid #f59e0b; }
    .notice--info { color: #1e3a8a; background: #eff6ff; border: 1px solid #bfdbfe; }
    .metric-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; margin: 18px 0; }
    .metric-card { min-height: 94px; padding: 14px; border: 1px solid #dbe4f0; border-radius: 16px; background: linear-gradient(180deg, #ffffff, #f8fafc); break-inside: avoid; }
    .metric-card span, .metric-card small { display: block; color: #64748b; font-size: 12px; }
    .metric-card strong { display: block; margin: 5px 0; color: #0f172a; font-size: 20px; letter-spacing: -0.03em; }
    .executive { margin: 18px 0; padding: 18px; border-radius: 18px; background: #102a56; color: #ffffff; break-inside: avoid; }
    .executive h2 { margin: 0 0 10px; color: #ffffff; border: 0; padding: 0; }
    .executive-list { margin: 0; padding-left: 20px; }
    .executive-list li { margin: 6px 0; }
    .outline-box { margin: 18px 0; padding: 16px 18px; border-radius: 18px; border: 1px solid #dbe4f0; background: #ffffff; break-inside: avoid; }
    .outline-box h2 { margin-top: 0; }
    .insight-strip { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; margin: 18px 0; }
    .insight-box { padding: 16px; border-radius: 18px; border: 1px solid #cbd5e1; background: #f8fafc; break-inside: avoid; }
    .insight-box h2 { margin-top: 0; }
    .conclusion-box { margin: 18px 0; padding: 18px; border-radius: 18px; border: 1px solid #bfdbfe; background: #eff6ff; break-inside: avoid; }
    .conclusion-box h2 { margin-top: 0; }
    .checklist-box { margin: 18px 0; padding: 18px; border-radius: 18px; border: 1px solid #d1d5db; background: #ffffff; break-inside: avoid; }
    .checklist-box h2 { margin-top: 0; }
    .quality-box { margin: 18px 0; padding: 18px; border-radius: 18px; border: 1px solid #fde68a; background: #fffbeb; break-inside: avoid; }
    .quality-box h2 { margin-top: 0; }
    .methodology-box { margin: 18px 0; padding: 18px; border-radius: 18px; border: 1px solid #c7d2fe; background: #eef2ff; break-inside: avoid; }
    .methodology-box h2 { margin-top: 0; }
    .detail-heading { margin: 18px 0 10px; padding-bottom: 6px; border-bottom: 2px solid #dbe4f0; font-size: 18px; }
    .section-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; margin-top: 12px; }
    .report-section { position: relative; padding: 18px; border: 1px solid #dbe4f0; border-radius: 18px; break-inside: avoid; background: #ffffff; }
    .report-section--wide { grid-column: 1 / -1; }
    .section-kicker { color: #2563eb; font-size: 11px; font-weight: 900; letter-spacing: 0.16em; }
    h2 { margin: 4px 0 10px; color: #0f172a; font-size: 17px; letter-spacing: -0.03em; }
    p { margin: 0 0 8px; font-size: 13px; color: #334155; }
    ul { margin: 8px 0 0; padding-left: 19px; }
    li { margin: 6px 0; font-size: 13px; color: #334155; }
    .muted { color: #94a3b8; }
    .appendix { margin-top: 18px; padding-top: 14px; border-top: 1px solid #dbe4f0; font-size: 11px; color: #64748b; }
    @media print { .metric-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } .insight-strip, .section-grid { grid-template-columns: 1fr; } .cover { padding-top: 0; } }
  `
  return `<!doctype html>
<html lang="ko">
<head>
  <meta charset="utf-8">
  <title>${escapePrintHtml(report.printTitle || report.title)}</title>
  <style>${styles}</style>
</head>
<body>
  <header class="cover">
    <div class="eyebrow">TravelLedger AI Analysis</div>
    <h1>${escapePrintHtml(report.title)}</h1>
    <p class="subtitle">${escapePrintHtml(report.subtitle)}</p>
    <div class="report-id-badge">Report ID: ${escapePrintHtml(report.reportId)}</div>
    <div class="report-meta">${renderPrintMeta(report)}</div>
    ${staleNotice}
  </header>
  <main>
    <section class="executive">
      <h2>Executive Summary</h2>
      ${executiveSummary || '<p>핵심 요약을 구성할 수 있는 분석 결과가 부족합니다.</p>'}
    </section>
    <section class="outline-box"><h2>리포트 구성</h2>${reportOutline || '<p class="muted">리포트 구성 정보를 표시할 수 없습니다.</p>'}</section>
    <section class="metric-grid" aria-label="핵심 지표">${cards}</section>
    <section class="insight-strip" aria-label="데이터 근거와 우선 조치">
      <article class="insight-box"><h2>데이터 근거</h2>${dataEvidence || '<p class="muted">집계 근거가 부족합니다.</p>'}</article>
      <article class="insight-box"><h2>우선 조치 권고</h2>${actionPriorities || '<p class="muted">우선 조치 항목이 부족합니다.</p>'}</article>
    </section>
    <section class="conclusion-box"><h2>종합 판단</h2>${conclusion || '<p class="muted">종합 판단을 구성할 수 있는 데이터가 부족합니다.</p>'}</section>
    <section class="checklist-box"><h2>검토 체크리스트</h2>${reviewChecklist || '<p class="muted">검토 항목이 부족합니다.</p>'}</section>
    <section class="methodology-box"><h2>분석 방법론</h2>${methodology || '<p class="muted">분석 방법론 정보를 구성할 수 없습니다.</p>'}</section>
    <section class="quality-box"><h2>데이터 품질 및 해석 한계</h2>${qualityNotes || '<p class="muted">데이터 품질 정보를 구성할 수 없습니다.</p>'}</section>
    <h2 class="detail-heading">상세 분석</h2>
    <div class="section-grid">${sections}</div>
    <div class="notice notice--info">${escapePrintHtml(report.advisory)}</div>
    <footer class="appendix">Report ID: ${escapePrintHtml(report.reportId)} · 본 문서는 브라우저 인쇄 기능으로 생성되었습니다. 금액과 건수는 저장된 가계부 데이터 기준이며, AI 문장은 참고용 분석입니다.</footer>
  </main>
</body>
</html>`
}

function printAiAnalysisReport() {
  if (!aiHasResult.value || typeof window === 'undefined') {
    return
  }
  const report = aiPrintableReport.value
  const printWindow = window.open('', '_blank', 'width=960,height=720')
  if (!printWindow) {
    return
  }
  printWindow.document.write(buildAiPrintHtml(report))
  printWindow.document.close()
  printWindow.focus()
  setTimeout(() => printWindow.print(), 250)
}
watch(() => props.aiAnalysis, (next, previous) => {
  if (next && next !== previous) {
    openAiResultModal()
  }
})

if (typeof window !== 'undefined') {
  window.addEventListener('keydown', handleAiResultEscape)
}

onBeforeUnmount(() => {
  if (typeof window !== 'undefined') {
    window.removeEventListener('keydown', handleAiResultEscape)
  }
})

function safeAiList(items) {
  const source = Array.isArray(items) ? items : []
  return dedupeAiItems(source.map(sanitizeAiText).filter((item) => item && !isRawJsonLike(item)))
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

<style scoped>

.ai-analysis-panel,
.ai-history-panel,
.ai-result-modal {
  --ai-mint: #a7f3b5;
  --ai-mint-strong: #58d47a;
  --ai-mint-deep: #1f8f58;
  --ai-mint-ink: #062615;
}

.ai-analysis-panel .button--primary,
.ai-history-panel .button--primary,
.ai-result-modal .button--primary {
  background: linear-gradient(135deg, var(--ai-mint), var(--ai-mint-strong));
  border-color: rgba(167, 243, 181, 0.72);
  color: var(--ai-mint-ink);
  box-shadow: 0 14px 32px rgba(88, 212, 122, 0.2);
}

.ai-analysis-panel .button--primary:hover,
.ai-history-panel .button--primary:hover,
.ai-result-modal .button--primary:hover {
  filter: brightness(1.04);
}

.ai-progress-card {
  margin-top: 1rem;
  padding: 1.1rem;
  border: 1px solid rgba(167, 243, 181, 0.38);
  border-radius: 22px;
  background:
    radial-gradient(circle at 12% 18%, rgba(167, 243, 181, 0.2), transparent 34%),
    linear-gradient(135deg, rgba(18, 34, 31, 0.94), rgba(21, 31, 45, 0.94));
  box-shadow: 0 18px 42px rgba(0, 0, 0, 0.24);
}

.ai-progress-card__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 1rem;
  margin-bottom: 0.9rem;
}

.ai-progress-card__header strong {
  display: block;
  color: #f6fff8;
  font-size: 1.02rem;
}

.ai-progress-card__header span {
  display: block;
  margin-top: 0.25rem;
  color: #b8c7c0;
  line-height: 1.55;
}

.ai-progress-card__header time {
  flex: 0 0 auto;
  padding: 0.35rem 0.7rem;
  border-radius: 999px;
  background: rgba(167, 243, 181, 0.14);
  color: var(--ai-mint);
  font-weight: 800;
}

.ai-progress-bar {
  height: 12px;
  overflow: hidden;
  border-radius: 999px;
  background: rgba(148, 163, 184, 0.18);
}

.ai-progress-bar span {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, var(--ai-mint), var(--ai-mint-strong), #34d399);
  transition: width 0.45s ease;
}

.ai-progress-steps {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 0.75rem;
  margin: 1rem 0 0;
  padding: 0;
  list-style: none;
}

.ai-progress-steps li {
  display: flex;
  gap: 0.6rem;
  min-width: 0;
  padding: 0.75rem;
  border: 1px solid rgba(148, 163, 184, 0.18);
  border-radius: 16px;
  background: rgba(15, 23, 42, 0.48);
  color: #94a3b8;
}

.ai-progress-steps li > span {
  display: grid;
  place-items: center;
  flex: 0 0 auto;
  width: 1.65rem;
  height: 1.65rem;
  border-radius: 999px;
  background: rgba(148, 163, 184, 0.14);
  color: inherit;
  font-weight: 900;
}

.ai-progress-steps strong {
  display: block;
  color: inherit;
  font-size: 0.9rem;
}

.ai-progress-steps small {
  display: block;
  margin-top: 0.2rem;
  color: #9aa8b8;
  line-height: 1.4;
}

.ai-progress-steps li.is-active,
.ai-progress-steps li.is-done {
  border-color: rgba(167, 243, 181, 0.44);
  color: #eaffef;
}

.ai-progress-steps li.is-active > span,
.ai-progress-steps li.is-done > span {
  background: var(--ai-mint);
  color: var(--ai-mint-ink);
}



.ai-history-item__titleline {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.55rem;
}

.ai-history-status {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 1.6rem;
  padding: 0.18rem 0.55rem;
  border: 1px solid rgba(148, 163, 184, 0.28);
  border-radius: 999px;
  background: rgba(148, 163, 184, 0.1);
  color: #cbd5e1;
  font-size: 0.78rem;
  font-weight: 900;
}
.ai-history-item--pending {
  border-color: rgba(167, 243, 181, 0.52);
  background:
    radial-gradient(circle at 8% 20%, rgba(167, 243, 181, 0.18), transparent 36%),
    rgba(18, 34, 31, 0.66);
}


.ai-history-status--completed {
  border-color: rgba(167, 243, 181, 0.45);
  background: rgba(167, 243, 181, 0.14);
  color: #bbf7d0;
}

.ai-history-status--failed {
  border-color: rgba(248, 113, 113, 0.45);
  background: rgba(248, 113, 113, 0.12);
  color: #fecaca;
}

.ai-history-status--pending {
  border-color: rgba(250, 204, 21, 0.42);
  background: rgba(250, 204, 21, 0.12);
  color: #fde68a;
}
.ai-history-status--running {
  border-color: rgba(167, 243, 181, 0.45);
  background: rgba(167, 243, 181, 0.16);
  color: #bbf7d0;
}
.ai-history-summary-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0.8rem;
  margin: 1rem 0;
}

.ai-history-summary-grid article {
  padding: 0.9rem 1rem;
  border: 1px solid rgba(167, 243, 181, 0.25);
  border-radius: 18px;
  background: linear-gradient(135deg, rgba(167, 243, 181, 0.13), rgba(15, 23, 42, 0.5));
}

.ai-history-summary-grid span {
  display: block;
  color: #a8b3c2;
  font-size: 0.86rem;
}

.ai-history-summary-grid strong {
  display: block;
  margin-top: 0.28rem;
  color: #f6fff8;
  font-size: 1.15rem;
}
.ai-analysis-stale-note {
  border: 1px solid rgba(245, 158, 11, 0.5);
  background: rgba(245, 158, 11, 0.12);
  color: #f8d28a;
  border-radius: 16px;
  padding: 12px 14px;
  font-weight: 800;
}

.ai-result-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  justify-content: flex-end;
  margin: 14px 0 18px;
}

.ai-presentation-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.ai-result-section--presentation p {
  margin: 0 0 10px;
  line-height: 1.75;
}

.ai-result-section--presentation ul {
  margin: 8px 0 0;
  padding-left: 20px;
}

.ai-result-section--presentation li {
  margin: 8px 0;
  line-height: 1.7;
}

.ai-result-modal {
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: rgba(6, 12, 24, 0.72);
  backdrop-filter: blur(10px);
}

.ai-result-modal__dialog {
  width: min(1120px, 100%);
  max-height: 80vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid rgba(148, 163, 184, 0.35);
  border-radius: 28px;
  background: linear-gradient(145deg, #1f2937, #111827);
  box-shadow: 0 28px 80px rgba(0, 0, 0, 0.45);
  color: #e5e7eb;
}

.ai-result-modal__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
  padding: 24px;
  border-bottom: 1px solid rgba(148, 163, 184, 0.22);
}

.ai-result-modal__header h2 {
  margin: 4px 0 6px;
}

.ai-result-modal__header p {
  margin: 0;
  color: #aeb8c8;
}

.ai-result-modal__eyebrow {
  color: #60a5fa;
  font-size: 0.78rem;
  font-weight: 900;
  letter-spacing: 0.14em;
}

.ai-result-modal__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  justify-content: flex-end;
}

.ai-result-modal__body {
  overflow: auto;
  padding: 24px;
}

.ai-analysis-advisory--modal,
.ai-result-card-grid--modal {
  margin-bottom: 18px;
}

@media (max-width: 760px) {
  .ai-presentation-grid {
    grid-template-columns: 1fr;
  }

  .ai-result-modal {
    align-items: stretch;
    padding: 10px;
  }

  .ai-result-modal__dialog {
    max-height: calc(100vh - 20px);
    border-radius: 22px;
  }

  .ai-result-modal__header {
    flex-direction: column;
    padding: 18px;
  }

  .ai-result-modal__actions {
    width: 100%;
    justify-content: stretch;
  }

  .ai-result-modal__actions .button {
    flex: 1 1 140px;
  }

  .ai-result-modal__body {
    padding: 18px;
  }
}

/* ai-mobile-mode-touch-upgrade */
@media (max-width: 760px) {
  :global(:root[data-layout-mode='mobile']) .ai-analysis-panel .button,
  :global(:root[data-layout-mode='mobile']) .ai-history-panel .button,
  :global(:root[data-layout-mode='mobile']) .ai-result-modal .button {
    min-height: 48px;
    padding: 0 14px;
    border-radius: 16px;
    font-size: 0.94rem;
    white-space: normal;
  }

  :global(:root[data-layout-mode='mobile']) .ai-analysis-actions,
  :global(:root[data-layout-mode='mobile']) .ai-result-toolbar,
  :global(:root[data-layout-mode='mobile']) .ai-history-item__actions,
  :global(:root[data-layout-mode='mobile']) .ai-result-modal__actions {
    display: grid;
    grid-template-columns: minmax(0, 1fr);
    gap: 10px;
    width: 100%;
  }

  :global(:root[data-layout-mode='mobile']) .ai-analysis-actions .button,
  :global(:root[data-layout-mode='mobile']) .ai-result-toolbar .button,
  :global(:root[data-layout-mode='mobile']) .ai-history-item__actions .button,
  :global(:root[data-layout-mode='mobile']) .ai-result-modal__actions .button {
    width: 100%;
  }

  :global(:root[data-layout-mode='mobile']) .scope-toggle--wrap {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 8px;
  }

  :global(:root[data-layout-mode='mobile']) .ai-progress-card {
    padding: 0.95rem;
    border-radius: 18px;
  }

  :global(:root[data-layout-mode='mobile']) .ai-progress-card__header {
    flex-direction: column;
  }

  :global(:root[data-layout-mode='mobile']) .ai-progress-steps,
  :global(:root[data-layout-mode='mobile']) .ai-history-summary-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  :global(:root[data-layout-mode='mobile']) .ai-history-item__titleline {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>

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
                  <td class="sheet-table__category">{{ entry.categoryGroupName }}<template v-if="entry.categoryDetailName"> / {{ entry.categoryDetailName }}
</template></td>
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
                <td class="sheet-table__category">{{ entry.categoryGroupName }}<template v-if="entry.categoryDetailName"> / {{ entry.categoryDetailName }}
</template></td>
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

            <div v-if="aiProgressVisible" class="ai-progress-card" role="status" aria-live="polite">
              <div class="ai-progress-card__header">
                <div>
                  <strong>AI 분석 진행 중</strong>
                  <span>{{ aiProgressCurrentStep.detail }}</span>
                </div>
                <time>{{ aiProgressElapsedLabel }}</time>
              </div>
              <div class="ai-progress-bar" role="progressbar" :aria-valuenow="aiProgressPercent" aria-valuemin="0" aria-valuemax="100" :aria-label="'AI 분석 진행률 ' + aiProgressPercent + '%'">
                <span :style="{ width: aiProgressPercent + '%' }"></span>
              </div>
              <ol class="ai-progress-steps">
                <li
                  v-for="(step, index) in aiProgressSteps"
                  :key="step.key"
                  :class="{ 'is-active': index === aiProgressStepIndex, 'is-done': index < aiProgressStepIndex }"
                >
                  <span>{{ index + 1 }}</span>
                  <div>
                    <strong>{{ step.label }}</strong>
                    <small>{{ step.detail }}</small>
                  </div>
                </li>
              </ol>
            </div>
          </div>

          <div class="ai-analysis-result">
            <template v-if="aiHasResult">
              <aside class="ai-analysis-advisory" role="note" aria-label="AI analysis advisory notice">
                <strong>AI 분석 결과는 참고용 조언입니다.</strong>
                <span>이 화면은 거래를 자동으로 생성, 수정, 삭제, 분류하지 않습니다. AI 추천을 실제 가계부에 반영하려면 사용자가 별도의 확인 액션을 직접 수행해야 합니다.</span>
              </aside>
              <div v-if="hasStaleAiResult" class="ai-analysis-stale-note">새 분석 요청에 실패해 이전 결과를 표시 중입니다.</div>
              <div class="ai-result-toolbar">
                <button class="button" type="button" @click="openAiResultModal">상세 보기</button>
                <button class="button button--secondary" type="button" @click="printAiAnalysisReport">PDF 저장/인쇄</button>
              </div>
              <div class="ai-result-card-grid">
                <article v-for="card in aiResultCards" :key="card.label" class="ai-result-card">
                  <span>{{ card.label }}</span>
                  <strong>{{ card.value }}</strong>
                  <small>{{ card.meta }}</small>
                </article>
              </div>
              <div class="ai-presentation-grid">
                <section
                  v-for="section in aiPresentationSections"
                  :key="section.key"
                  class="ai-result-section ai-result-section--presentation"
                  :class="{ 'ai-result-section--wide': section.wide }"
                >
                  <h3>{{ section.title }}</h3>
                  <p v-for="paragraph in section.paragraphs" :key="paragraph">{{ paragraph }}</p>
                  <ul v-if="section.items.length">
                    <li v-for="item in section.items" :key="item">{{ item }}</li>
                  </ul>
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

        <div class="ai-history-summary-grid">
          <article>
            <span>전체 기록</span>
            <strong>{{ aiAnalysisHistoryPage?.totalElements ?? 0 }}건</strong>
          </article>
          <article>
            <span>현재 페이지 완료</span>
            <strong>{{ aiHistoryCompletedCount }}건</strong>
          </article>
          <article>
            <span>현재 페이지 실패</span>
            <strong>{{ aiHistoryFailedCount }}건</strong>
          </article>
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
          <article v-if="aiAnalysisLoading" class="ai-history-item ai-history-item--pending">
            <div>
              <strong>현재 AI 분석 요청 처리 중</strong>
              <small>완료되면 결과 이력에 자동 저장됩니다. {{ aiProgressElapsedLabel }} 경과</small>
            </div>
            <span class="ai-history-status ai-history-status--running">분석 중</span>
          </article>
          <article v-for="history in aiAnalysisHistoryItems" :key="history.id" class="ai-history-item">
            <div>
              <div class="ai-history-item__titleline">
                <strong>{{ history.title }}</strong>
                <span :class="['ai-history-status', 'ai-history-status--' + String(history.status || '').toLowerCase()]">{{ formatAiStatus(history.status) }}</span>
              </div>
              <span>{{ formatAiMode(history.mode) }} · {{ formatAiPeriod(history.periodType) }}</span>
              <small>{{ formatAiRange(history.from, history.to) }}<template v-if="history.compareFrom"> vs {{ formatAiRange(history.compareFrom, history.compareTo) }}
</template></small>
              <p>{{ history.summary || history.errorMessage || '저장된 요약이 없습니다.' }}</p>
              <small>{{ formatAiCreatedAt(history.createdAt) }}</small>
            </div>
            <div class="ai-history-item__actions">
              <button class="button" type="button" @click="emit('open-ai-analysis-history', history.id)">열기</button>
              <button class="button button--secondary" type="button" :disabled="aiAnalysisLoading || history.status !== 'COMPLETED'" @click="printAiHistory(history.id)">PDF 저장</button>
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

  <Teleport to="body">
    <div v-if="aiResultModalOpen && aiHasResult" class="ai-result-modal" @click.self="closeAiResultModal">
      <section class="ai-result-modal__dialog" role="dialog" aria-modal="true" aria-labelledby="ai-result-modal-title">
        <header class="ai-result-modal__header">
          <div>
            <span class="ai-result-modal__eyebrow">AI ANALYSIS REPORT</span>
            <h2 id="ai-result-modal-title">AI 소비 분석 상세 결과</h2>
            <p>{{ aiPrintableReport.range }} · {{ aiAnalysisStatus?.provider || 'AI' }} · {{ aiAnalysisStatus?.model || 'auto' }}</p>
          </div>
          <div class="ai-result-modal__actions">
            <button class="button button--secondary" type="button" @click="printAiAnalysisReport">PDF 저장/인쇄</button>
            <button class="button" type="button" @click="closeAiResultModal">닫기</button>
          </div>
        </header>
        <div class="ai-result-modal__body">
          <aside class="ai-analysis-advisory ai-analysis-advisory--modal" role="note">
            <strong>AI 분석 결과는 참고용 조언입니다.</strong>
            <span>거래 생성, 수정, 삭제, 분류는 자동으로 수행되지 않습니다.</span>
          </aside>
          <div v-if="hasStaleAiResult" class="ai-analysis-stale-note">새 분석 요청에 실패해 이전 결과를 표시 중입니다.</div>
          <div class="ai-result-card-grid ai-result-card-grid--modal">
            <article v-for="card in aiResultCards" :key="`modal-${card.label}`" class="ai-result-card">
              <span>{{ card.label }}</span>
              <strong>{{ card.value }}</strong>
              <small>{{ card.meta }}</small>
            </article>
          </div>
          <div class="ai-presentation-grid ai-presentation-grid--modal">
            <section
              v-for="section in aiPresentationSections"
              :key="`modal-${section.key}`"
              class="ai-result-section ai-result-section--presentation"
              :class="{ 'ai-result-section--wide': section.wide }"
            >
              <h3>{{ section.title }}</h3>
              <p v-for="paragraph in section.paragraphs" :key="paragraph">{{ paragraph }}</p>
              <ul v-if="section.items.length">
                <li v-for="item in section.items" :key="item">{{ item }}</li>
              </ul>
            </section>
          </div>
        </div>
      </section>
    </div>
  </Teleport>
</template>












