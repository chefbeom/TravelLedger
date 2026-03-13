import {
  formatCurrency,
  formatDateRange,
  getWeekdayFullLabel,
  parseIsoDate,
  toIsoDate,
} from './format'

const presetLabels = {
  DAY: '일간',
  WEEK: '주간',
  MONTH: '월간',
  QUARTER: '분기',
  YEAR: '연간',
  CUSTOM: '원하는 기간',
}

export function getDefaultTimeValue() {
  const now = new Date()
  return `${`${now.getHours()}`.padStart(2, '0')}:${`${now.getMinutes()}`.padStart(2, '0')}`
}

export function getPresetOptions() {
  return Object.entries(presetLabels).map(([value, label]) => ({ value, label }))
}

export function resolveRange(anchorDate, preset, customFrom, customTo) {
  const anchor = parseIsoDate(anchorDate)

  if (preset === 'CUSTOM') {
    const from = customFrom || anchorDate
    const to = customTo || anchorDate
    return {
      from,
      to,
      label: formatDateRange(from, to),
      presetLabel: presetLabels[preset],
    }
  }

  switch (preset) {
    case 'DAY':
      return {
        from: anchorDate,
        to: anchorDate,
        label: formatDateRange(anchorDate, anchorDate),
        presetLabel: presetLabels[preset],
      }
    case 'WEEK': {
      const start = new Date(anchor)
      const weekday = start.getDay() === 0 ? 7 : start.getDay()
      start.setDate(start.getDate() - (weekday - 1))
      const end = new Date(start)
      end.setDate(end.getDate() + 6)
      return {
        from: toIsoDate(start),
        to: toIsoDate(end),
        label: formatDateRange(toIsoDate(start), toIsoDate(end)),
        presetLabel: presetLabels[preset],
      }
    }
    case 'MONTH': {
      const start = new Date(anchor.getFullYear(), anchor.getMonth(), 1)
      const end = new Date(anchor.getFullYear(), anchor.getMonth() + 1, 0)
      return {
        from: toIsoDate(start),
        to: toIsoDate(end),
        label: formatDateRange(toIsoDate(start), toIsoDate(end)),
        presetLabel: presetLabels[preset],
      }
    }
    case 'QUARTER': {
      const quarterStartMonth = Math.floor(anchor.getMonth() / 3) * 3
      const start = new Date(anchor.getFullYear(), quarterStartMonth, 1)
      const end = new Date(anchor.getFullYear(), quarterStartMonth + 3, 0)
      return {
        from: toIsoDate(start),
        to: toIsoDate(end),
        label: formatDateRange(toIsoDate(start), toIsoDate(end)),
        presetLabel: presetLabels[preset],
      }
    }
    case 'YEAR': {
      const start = new Date(anchor.getFullYear(), 0, 1)
      const end = new Date(anchor.getFullYear(), 11, 31)
      return {
        from: toIsoDate(start),
        to: toIsoDate(end),
        label: formatDateRange(toIsoDate(start), toIsoDate(end)),
        presetLabel: presetLabels[preset],
      }
    }
    default:
      return {
        from: anchorDate,
        to: anchorDate,
        label: formatDateRange(anchorDate, anchorDate),
        presetLabel: presetLabels.DAY,
      }
  }
}

export function buildPastComparisonRanges() {
  return [
    { key: 'yesterday', label: '어제', preset: 'DAY' },
    { key: 'last-week', label: '지난주', preset: 'WEEK' },
    { key: 'last-month', label: '1개월 전', preset: 'MONTH' },
    { key: 'last-quarter', label: '1분기 전', preset: 'QUARTER' },
    { key: 'last-year', label: '1년 전', preset: 'YEAR' },
  ]
}

export function shiftRange(anchorDate, preset, customFrom, customTo, step = 1) {
  const current = resolveRange(anchorDate, preset, customFrom, customTo)
  const from = parseIsoDate(current.from)
  const to = parseIsoDate(current.to)

  if (preset === 'CUSTOM') {
    const length = Math.round((to - from) / 86400000) + 1
    const shiftedTo = new Date(from)
    shiftedTo.setDate(shiftedTo.getDate() - 1)
    const shiftedFrom = new Date(shiftedTo)
    shiftedFrom.setDate(shiftedFrom.getDate() - (length - 1))
    return {
      from: toIsoDate(shiftedFrom),
      to: toIsoDate(shiftedTo),
    }
  }

  const shiftedAnchor = new Date(parseIsoDate(anchorDate))
  switch (preset) {
    case 'DAY':
      shiftedAnchor.setDate(shiftedAnchor.getDate() - step)
      break
    case 'WEEK':
      shiftedAnchor.setDate(shiftedAnchor.getDate() - (7 * step))
      break
    case 'MONTH':
      shiftedAnchor.setMonth(shiftedAnchor.getMonth() - step)
      break
    case 'QUARTER':
      shiftedAnchor.setMonth(shiftedAnchor.getMonth() - (3 * step))
      break
    case 'YEAR':
      shiftedAnchor.setFullYear(shiftedAnchor.getFullYear() - step)
      break
    default:
      break
  }

  return resolveRange(toIsoDate(shiftedAnchor), preset, customFrom, customTo)
}

export function filterEntries(entries, filters) {
  const keyword = filters.keyword.trim().toLowerCase()
  const minAmount = Number(filters.minAmount || 0)
  const maxAmount = Number(filters.maxAmount || 0)

  return entries
    .filter((entry) => {
      if (keyword) {
        const target = [entry.title, entry.memo, entry.categoryGroupName, entry.categoryDetailName, entry.paymentMethodName]
          .filter(Boolean)
          .join(' ')
          .toLowerCase()
        if (!target.includes(keyword)) {
          return false
        }
      }

      if (filters.entryType && entry.entryType !== filters.entryType) {
        return false
      }

      if (filters.paymentMethodId && String(entry.paymentMethodId) !== String(filters.paymentMethodId)) {
        return false
      }

      if (filters.categoryGroupId && String(entry.categoryGroupId) !== String(filters.categoryGroupId)) {
        return false
      }

      if (filters.minAmount && Number(entry.amount) < minAmount) {
        return false
      }

      if (filters.maxAmount && Number(entry.amount) > maxAmount) {
        return false
      }

      return true
    })
    .sort((a, b) => {
      switch (filters.sortBy) {
        case 'AMOUNT_DESC':
          return Number(b.amount) - Number(a.amount)
        case 'AMOUNT_ASC':
          return Number(a.amount) - Number(b.amount)
        case 'DATE_ASC':
          return `${a.entryDate}${a.entryTime ?? ''}`.localeCompare(`${b.entryDate}${b.entryTime ?? ''}`)
        case 'DATE_DESC':
        default:
          return `${b.entryDate}${b.entryTime ?? ''}`.localeCompare(`${a.entryDate}${a.entryTime ?? ''}`)
      }
    })
}

export function summarizeEntries(entries) {
  return entries.reduce(
    (summary, entry) => {
      const amount = Number(entry.amount ?? 0)
      if (entry.entryType === 'INCOME') {
        summary.income += amount
      } else {
        summary.expense += amount
      }
      summary.count += 1
      summary.balance = summary.income - summary.expense
      return summary
    },
    { income: 0, expense: 0, balance: 0, count: 0 },
  )
}

function buildExpenseBuckets(entries, resolver, labelResolver) {
  const buckets = new Map()

  entries
    .filter((entry) => entry.entryType === 'EXPENSE')
    .forEach((entry) => {
      const key = resolver(entry)
      if (key === null || key === undefined) {
        return
      }
      const current = buckets.get(key) ?? { key, total: 0, count: 0 }
      current.total += Number(entry.amount ?? 0)
      current.count += 1
      buckets.set(key, current)
    })

  if (!buckets.size) {
    return null
  }

  const top = [...buckets.values()].sort((a, b) => b.total - a.total)[0]
  return {
    label: labelResolver(top.key),
    total: top.total,
    count: top.count,
  }
}

function buildSeries(entries, keys, resolver, labelResolver) {
  const totals = new Map(keys.map((key) => [key, 0]))

  entries
    .filter((entry) => entry.entryType === 'EXPENSE')
    .forEach((entry) => {
      const key = resolver(entry)
      if (key === null || key === undefined || !totals.has(key)) {
        return
      }
      totals.set(key, totals.get(key) + Number(entry.amount ?? 0))
    })

  return keys.map((key) => ({
    key,
    label: labelResolver(key),
    value: totals.get(key) ?? 0,
  }))
}

export function buildInsights(entries) {
  const strongestHour = buildExpenseBuckets(
    entries.filter((entry) => entry.entryTime),
    (entry) => Number(String(entry.entryTime).slice(0, 2)),
    (hour) => `${hour}시`,
  )

  const strongestWeekday = buildExpenseBuckets(
    entries,
    (entry) => parseIsoDate(entry.entryDate).getDay(),
    (dayIndex) => getWeekdayFullLabel(dayIndex),
  )

  const strongestWeekOfMonth = buildExpenseBuckets(
    entries,
    (entry) => Math.floor((parseIsoDate(entry.entryDate).getDate() - 1) / 7) + 1,
    (week) => `${week}주차`,
  )

  const strongestMonthOfYear = buildExpenseBuckets(
    entries,
    (entry) => parseIsoDate(entry.entryDate).getMonth() + 1,
    (month) => `${month}월`,
  )

  const peakExpenseDay = entries
    .filter((entry) => entry.entryType === 'EXPENSE')
    .reduce((map, entry) => {
      const key = entry.entryDate
      map.set(key, (map.get(key) ?? 0) + Number(entry.amount ?? 0))
      return map
    }, new Map())

  const peakExpenseDayEntry = [...peakExpenseDay.entries()]
    .map(([date, total]) => ({ date, total }))
    .sort((a, b) => b.total - a.total)[0] ?? null

  const hourlySeries = buildSeries(
    entries.filter((entry) => entry.entryTime),
    Array.from({ length: 24 }, (_, index) => index),
    (entry) => Number(String(entry.entryTime).slice(0, 2)),
    (hour) => `${hour}시`,
  )

  const weekdaySeries = buildSeries(
    entries,
    [1, 2, 3, 4, 5, 6, 0],
    (entry) => parseIsoDate(entry.entryDate).getDay(),
    (dayIndex) => getWeekdayFullLabel(dayIndex),
  )

  const weekOfMonthSeries = buildSeries(
    entries,
    [1, 2, 3, 4, 5],
    (entry) => Math.floor((parseIsoDate(entry.entryDate).getDate() - 1) / 7) + 1,
    (week) => `${week}주차`,
  )

  const monthOfYearSeries = buildSeries(
    entries,
    [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12],
    (entry) => parseIsoDate(entry.entryDate).getMonth() + 1,
    (month) => `${month}월`,
  )

  return {
    strongestHour: strongestHour
      ? { ...strongestHour, caption: `${formatCurrency(strongestHour.total)} / ${strongestHour.count}건` }
      : { label: '시간 기록 없음', total: 0, count: 0, caption: '거래 시간을 입력하면 분석됩니다.' },
    strongestWeekday: strongestWeekday
      ? { ...strongestWeekday, caption: `${formatCurrency(strongestWeekday.total)} / ${strongestWeekday.count}건` }
      : { label: '데이터 없음', total: 0, count: 0, caption: '선택한 기간의 지출이 없습니다.' },
    strongestWeekOfMonth: strongestWeekOfMonth
      ? { ...strongestWeekOfMonth, caption: `${formatCurrency(strongestWeekOfMonth.total)} / ${strongestWeekOfMonth.count}건` }
      : { label: '데이터 없음', total: 0, count: 0, caption: '선택한 기간의 지출이 없습니다.' },
    strongestMonthOfYear: strongestMonthOfYear
      ? { ...strongestMonthOfYear, caption: `${formatCurrency(strongestMonthOfYear.total)} / ${strongestMonthOfYear.count}건` }
      : { label: '데이터 없음', total: 0, count: 0, caption: '선택한 기간의 지출이 없습니다.' },
    peakExpenseDay: peakExpenseDayEntry
      ? {
          label: peakExpenseDayEntry.date,
          total: peakExpenseDayEntry.total,
          caption: `${formatDateRange(peakExpenseDayEntry.date, peakExpenseDayEntry.date)} / ${formatCurrency(peakExpenseDayEntry.total)}`,
        }
      : { label: '데이터 없음', total: 0, caption: '선택한 기간의 지출이 없습니다.' },
    hourlySeries,
    weekdaySeries,
    weekOfMonthSeries,
    monthOfYearSeries,
  }
}
