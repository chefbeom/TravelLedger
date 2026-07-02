import CalendarPalette from '../palettes/CalendarPalette.vue'
import KpiPalette from '../palettes/KpiPalette.vue'
import { getSpanBySize } from '../utils/paletteLayout'

function formatCurrency(value) {
  return new Intl.NumberFormat('ko-KR', {
    style: 'currency',
    currency: 'KRW',
    maximumFractionDigits: 0,
  }).format(Number(value ?? 0))
}

function formatNumber(value) {
  return new Intl.NumberFormat('ko-KR').format(Number(value ?? 0))
}

const RECENT_FLOW_DEFAULT_LIMIT = 8
const RECENT_FLOW_MIN_LIMIT = 5
const RECENT_FLOW_MAX_LIMIT = 10

function clampRecentFlowLimit(value) {
  const parsed = Number(value ?? RECENT_FLOW_DEFAULT_LIMIT)
  if (!Number.isFinite(parsed)) return RECENT_FLOW_DEFAULT_LIMIT
  return Math.min(RECENT_FLOW_MAX_LIMIT, Math.max(RECENT_FLOW_MIN_LIMIT, Math.round(parsed)))
}

function resolveRecentFlowType(value) {
  return value === 'INCOME' ? 'INCOME' : 'EXPENSE'
}

function entryTimestamp(entry) {
  const date = entry.entryDate || entry.date || entry.transactionDate || entry.localDate || ''
  const time = entry.entryTime || entry.time || ''
  const dateTime = date ? Date.parse(`${date}T${time || '00:00:00'}`) : Number.NaN
  if (Number.isFinite(dateTime)) return dateTime
  const createdTime = Date.parse(entry.createdAt || entry.updatedAt || '')
  if (Number.isFinite(createdTime)) return createdTime
  return Number(entry.id ?? 0)
}

function entryTitle(entry) {
  return entry.title || entry.categoryDetailName || entry.categoryName || entry.paymentMethodName || '거래'
}

function quickStat(context, key) {
  return (context.dashboard?.quickStats ?? []).find((item) => item.key === key)?.overview ?? null
}

function buildOverviewKpi(context, key, title) {
  const overview = quickStat(context, key) ?? {}
  const balance = Number(overview.balance ?? 0)
  const income = Number(overview.income ?? 0)
  const expense = Number(overview.expense ?? 0)
  const maxValue = Math.max(income, expense, Math.abs(balance), 1)

  return {
    title,
    eyebrow: '가계부',
    value: formatCurrency(balance),
    tone: balance >= 0 ? 'positive' : 'negative',
    meta: `${formatNumber(overview.entryCount ?? 0)}건`,
    rows: [
      { label: '수입', value: formatCurrency(income), tone: 'positive' },
      { label: '지출', value: formatCurrency(expense), tone: 'negative' },
    ],
    bars: [
      { label: '수입', value: income, percent: Math.round((income / maxValue) * 100), tone: 'positive' },
      { label: '지출', value: expense, percent: Math.round((expense / maxValue) * 100), tone: 'negative' },
    ],
  }
}

function buildIncomeExpenseKpi(context) {
  const month = quickStat(context, 'month') ?? {}
  const income = Number(month.income ?? 0)
  const expense = Number(month.expense ?? 0)
  const total = Math.max(income + expense, 1)
  const balance = Number(month.balance ?? 0)

  return {
    title: '수입/지출 균형',
    eyebrow: context.monthLabel || '이번 달',
    value: `${Math.round((expense / total) * 100)}%`,
    meta: '지출 비중',
    tone: expense <= income ? 'positive' : 'negative',
    rows: [
      { label: '수입', value: formatCurrency(income), tone: 'positive' },
      { label: '지출', value: formatCurrency(expense), tone: 'negative' },
      { label: '순액', value: formatCurrency(balance), tone: balance >= 0 ? 'positive' : 'negative' },
    ],
    bars: [
      { label: '수입', value: income, percent: Math.round((income / total) * 100), tone: 'positive' },
      { label: '지출', value: expense, percent: Math.round((expense / total) * 100), tone: 'negative' },
    ],
  }
}

function buildRecentFlowKpi(context, options = {}) {
  const entryType = resolveRecentFlowType(options.entryType)
  const limit = clampRecentFlowLimit(options.limit)
  const sourceEntries = Array.isArray(context.entries) && context.entries.length
    ? context.entries
    : (context.dashboard?.recentEntries ?? [])
  const recentEntries = sourceEntries
    .filter((entry) => entry.entryType === entryType)
    .slice()
    .sort((left, right) => entryTimestamp(right) - entryTimestamp(left))
    .slice(0, limit)
  const latestEntry = recentEntries[0]
  const flowLabel = entryType === 'INCOME' ? '최근 수입' : '최근 지출'

  return {
    title: '최근 흐름',
    eyebrow: flowLabel,
    value: latestEntry ? formatCurrency(latestEntry.amount) : '-',
    meta: latestEntry ? '최신순 거래' : `${flowLabel} 없음`,
    tone: entryType === 'INCOME' ? 'positive' : 'negative',
    rows: recentEntries.map((entry) => ({
      label: entryTitle(entry),
      value: formatCurrency(entry.amount),
      tone: entry.entryType === 'INCOME' ? 'positive' : 'negative',
    })),
    bars: [],
  }
}

const kpiVariantTitles = {
  today: '오늘',
  week: '이번 주',
  month: '이번 달',
  year: '올해',
  incomeExpense: '수입/지출 균형',
  recentFlow: '최근 흐름',
}

export const paletteRegistry = {
  kpi: {
    type: 'kpi',
    label: 'KPI',
    supportedSizes: ['1x1', '2x1', '1x2', '2x2', '2x3', '3x1', '3x2', '3x3', '4x2', '4x3'],
    defaultSize: '2x2',
    spanBySize: getSpanBySize,
    component: KpiPalette,
    getTitle(config) {
      return kpiVariantTitles[config.options?.variant] || 'KPI'
    },
    getPaletteData(config, context) {
      const variant = config.options?.variant
      if (variant === 'today') return buildOverviewKpi(context, 'day', '오늘')
      if (variant === 'week') return buildOverviewKpi(context, 'week', '이번 주')
      if (variant === 'month') return buildOverviewKpi(context, 'month', '이번 달')
      if (variant === 'year') return buildOverviewKpi(context, 'year', '올해')
      if (variant === 'incomeExpense') return buildIncomeExpenseKpi(context)
      if (variant === 'recentFlow') return buildRecentFlowKpi(context, config.options)
      return buildOverviewKpi(context, 'month', '이번 달')
    },
  },
  calendar: {
    type: 'calendar',
    label: '월 달력',
    supportedSizes: ['2x2', '3x2', '3x3', '3x4', '4x3', '4x4'],
    defaultSize: '3x3',
    spanBySize: getSpanBySize,
    component: CalendarPalette,
    getTitle() {
      return '월 달력'
    },
    getPaletteData(config, context) {
      return {
        monthLabel: context.monthLabel,
        anchorDate: context.anchorDate,
        weeks: context.calendarWeeks ?? [],
      }
    },
  },
}

export function getPaletteDefinition(type) {
  return paletteRegistry[type] || paletteRegistry.kpi
}
