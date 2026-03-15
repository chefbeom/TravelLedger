const dateFormatter = new Intl.DateTimeFormat('en-CA', {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
})

export function todayIso() {
  return toIsoDate(new Date())
}

export function nowTimeIso() {
  return new Date().toTimeString().slice(0, 5)
}

export function monthBounds(anchorDate = todayIso()) {
  const base = new Date(`${anchorDate}T00:00:00`)
  const from = new Date(base.getFullYear(), base.getMonth(), 1)
  const to = new Date(base.getFullYear(), base.getMonth() + 1, 0)
  return {
    from: toIsoDate(from),
    to: toIsoDate(to),
  }
}

export function toIsoDate(value) {
  const date = value instanceof Date ? value : new Date(value)
  if (Number.isNaN(date.getTime())) {
    return ''
  }

  const year = date.getFullYear()
  const month = `${date.getMonth() + 1}`.padStart(2, '0')
  const day = `${date.getDate()}`.padStart(2, '0')
  return `${year}-${month}-${day}`
}

export function formatDate(value) {
  if (!value) {
    return '-'
  }

  const normalized = typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value)
    ? new Date(`${value}T00:00:00`)
    : new Date(value)

  if (Number.isNaN(normalized.getTime())) {
    return String(value)
  }

  return dateFormatter.format(normalized)
}

export function formatTime(value) {
  if (!value) {
    return '-'
  }

  const text = String(value)
  return text.length >= 5 ? text.slice(0, 5) : text
}

export function formatDateTime(date, time) {
  return [formatDate(date), time ? formatTime(time) : '']
    .filter((item) => item && item !== '-')
    .join(' ')
}

export function formatCurrency(value) {
  return new Intl.NumberFormat('ko-KR', {
    style: 'currency',
    currency: 'KRW',
    maximumFractionDigits: 0,
  }).format(Number(value ?? 0))
}

export function formatCurrencyByCode(value, currencyCode = 'KRW') {
  const code = String(currencyCode || 'KRW').toUpperCase()
  const maximumFractionDigits = code === 'KRW' ? 0 : 2

  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: code,
    maximumFractionDigits,
  }).format(Number(value ?? 0))
}

export function safeNumber(value, fallback = 0) {
  const numeric = Number(value)
  return Number.isFinite(numeric) ? numeric : fallback
}

export function toNullableNumber(value) {
  if (value === '' || value === null || value === undefined) {
    return null
  }

  const numeric = Number(value)
  return Number.isFinite(numeric) ? numeric : null
}

export function sumBy(items, selector) {
  return (items ?? []).reduce((total, item) => total + safeNumber(selector(item), 0), 0)
}

export function groupBy(items, selector) {
  return (items ?? []).reduce((bucket, item) => {
    const key = selector(item)
    const current = bucket.get(key) ?? []
    current.push(item)
    bucket.set(key, current)
    return bucket
  }, new Map())
}

export function compareByDateTimeDesc(leftDate, leftTime, rightDate, rightTime) {
  const left = `${leftDate || ''} ${leftTime || '99:99'}`
  const right = `${rightDate || ''} ${rightTime || '99:99'}`
  return right.localeCompare(left)
}
