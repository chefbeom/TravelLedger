const formatterCache = new Map()

const shortDateFormatter = new Intl.DateTimeFormat('ko-KR', {
  month: 'short',
  day: 'numeric',
})

const fullDateFormatter = new Intl.DateTimeFormat('ko-KR', {
  year: 'numeric',
  month: 'long',
  day: 'numeric',
})

const monthFormatter = new Intl.DateTimeFormat('ko-KR', {
  year: 'numeric',
  month: 'long',
})

const weekdayLabels = ['일', '월', '화', '수', '목', '금', '토']
const weekdayFullLabels = ['일요일', '월요일', '화요일', '수요일', '목요일', '금요일', '토요일']

function getCurrencyFormatter(currency = 'KRW', maximumFractionDigits = currency === 'KRW' ? 0 : 2) {
  const key = `${currency}:${maximumFractionDigits}`
  if (!formatterCache.has(key)) {
    formatterCache.set(
      key,
      new Intl.NumberFormat('ko-KR', {
        style: 'currency',
        currency,
        maximumFractionDigits,
      }),
    )
  }

  return formatterCache.get(key)
}

export function formatCurrency(value) {
  return getCurrencyFormatter('KRW', 0).format(Number(value ?? 0))
}

export function formatCurrencyByCode(value, currencyCode = 'KRW', maximumFractionDigits) {
  const code = String(currencyCode || 'KRW').toUpperCase()
  const digits = maximumFractionDigits ?? (code === 'KRW' ? 0 : 2)
  return getCurrencyFormatter(code, digits).format(Number(value ?? 0))
}

export function formatNumber(value, maximumFractionDigits = 2) {
  return new Intl.NumberFormat('ko-KR', {
    maximumFractionDigits,
  }).format(Number(value ?? 0))
}

export function formatShortDate(value) {
  return shortDateFormatter.format(parseIsoDate(value))
}

export function formatFullDate(value) {
  return fullDateFormatter.format(parseIsoDate(value))
}

export function formatCompactNumber(value) {
  return new Intl.NumberFormat('ko-KR', {
    maximumFractionDigits: 0,
  }).format(Number(value ?? 0))
}

export function formatMonthLabel(value) {
  return monthFormatter.format(parseIsoDate(value))
}

export function formatTime(value) {
  if (!value) {
    return '-'
  }

  return String(value).slice(0, 5)
}

export function formatDateRange(from, to) {
  if (!from || !to) {
    return ''
  }

  if (from === to) {
    return formatFullDate(from)
  }

  return `${formatFullDate(from)} - ${formatFullDate(to)}`
}

export function parseIsoDate(value) {
  const [year, month, day] = String(value).split('-').map(Number)
  return new Date(year, month - 1, day)
}

export function toIsoDate(date) {
  const year = date.getFullYear()
  const month = `${date.getMonth() + 1}`.padStart(2, '0')
  const day = `${date.getDate()}`.padStart(2, '0')
  return `${year}-${month}-${day}`
}

export function getMonthRange(anchorValue) {
  const anchor = parseIsoDate(anchorValue)
  const from = new Date(anchor.getFullYear(), anchor.getMonth(), 1)
  const to = new Date(anchor.getFullYear(), anchor.getMonth() + 1, 0)
  return {
    from: toIsoDate(from),
    to: toIsoDate(to),
  }
}

export function buildCalendarWeeks(summaryItems, anchorValue) {
  const anchor = parseIsoDate(anchorValue)
  const targetMonth = anchor.getMonth()
  const firstDay = new Date(anchor.getFullYear(), targetMonth, 1)
  const lastDay = new Date(anchor.getFullYear(), targetMonth + 1, 0)
  const start = new Date(firstDay)
  start.setDate(start.getDate() - start.getDay())

  const end = new Date(lastDay)
  end.setDate(end.getDate() + (6 - end.getDay()))

  const summaryMap = new Map(summaryItems.map((item) => [item.date, item]))
  const weeks = []
  const cursor = new Date(start)
  const startIso = toIsoDate(start)

  while (cursor <= end) {
    const week = []
    for (let index = 0; index < 7; index += 1) {
      const iso = toIsoDate(cursor)
      week.push({
        date: iso,
        dayNumber: cursor.getDate(),
        monthNumber: cursor.getMonth() + 1,
        inCurrentMonth: cursor.getMonth() === targetMonth,
        showMonthTag: cursor.getDate() === 1 || iso === startIso,
        summary: summaryMap.get(iso) ?? {
          date: iso,
          income: 0,
          expense: 0,
          balance: 0,
          entryCount: 0,
        },
      })
      cursor.setDate(cursor.getDate() + 1)
    }
    weeks.push(week)
  }

  return weeks
}

export function getWeekdayLabels() {
  return weekdayLabels
}

export function getWeekdayFullLabel(index) {
  return weekdayFullLabels[index] ?? ''
}
