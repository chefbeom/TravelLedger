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

function buildRecentFlowKpi(context) {
  const recentEntries = context.dashboard?.recentEntries ?? []
  const totalExpense = recentEntries
    .filter((entry) => entry.entryType === 'EXPENSE')
    .reduce((total, entry) => total + Number(entry.amount ?? 0), 0)
  const totalIncome = recentEntries
    .filter((entry) => entry.entryType === 'INCOME')
    .reduce((total, entry) => total + Number(entry.amount ?? 0), 0)
  const maxValue = Math.max(totalIncome, totalExpense, 1)

  return {
    title: '최근 흐름',
    eyebrow: '최근 입력',
    value: `${formatNumber(recentEntries.length)}건`,
    meta: recentEntries[0]?.title || '최근 거래 없음',
    tone: 'neutral',
    rows: recentEntries.slice(0, 3).map((entry) => ({
      label: entry.title || '-',
      value: formatCurrency(entry.amount),
      tone: entry.entryType === 'INCOME' ? 'positive' : 'negative',
    })),
    bars: [
      { label: '수입', value: totalIncome, percent: Math.round((totalIncome / maxValue) * 100), tone: 'positive' },
      { label: '지출', value: totalExpense, percent: Math.round((totalExpense / maxValue) * 100), tone: 'negative' },
    ],
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
    supportedSizes: ['1x1', '1x2', '2x2', '3x2', '3x3'],
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
      if (variant === 'recentFlow') return buildRecentFlowKpi(context)
      return buildOverviewKpi(context, 'month', '이번 달')
    },
  },
  calendar: {
    type: 'calendar',
    label: '월 달력',
    supportedSizes: ['1x2', '2x2', '3x2', '3x3'],
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
