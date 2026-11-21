import { groupLedgerSheetEntries, splitDisplayedAmount, splitExpectedCount } from './ledgerSheetGroups.js'

// Date-only values must not be parsed through the browser's local time zone.
function calendarDate(year, month, day) {
  const date = new Date(0)
  date.setUTCHours(0, 0, 0, 0)
  date.setUTCFullYear(year, month - 1, day)
  return date
}

function isoDate(date) {
  return `${String(date.getUTCFullYear()).padStart(4, '0')}-${String(date.getUTCMonth() + 1).padStart(2, '0')}-${String(date.getUTCDate()).padStart(2, '0')}`
}

export function validCalendarDate(value) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const [year, month, day] = value.split('-').map(Number)
  if (year < 1 || month < 1 || month > 12 || day < 1 || day > 31) return false
  return isoDate(calendarDate(year, month, day)) === value
}

export function calendarMonthGrid(year, month) {
  if (!Number.isInteger(year) || year < 1 || year > 9999 || !Number.isInteger(month) || month < 1 || month > 12) throw new RangeError('Invalid calendar month')
  const start = calendarDate(year, month, 1)
  const currentMonthStart = isoDate(start)
  const currentMonthEnd = isoDate(calendarDate(year, month + 1, 0))
  start.setUTCDate(start.getUTCDate() - start.getUTCDay())
  const days = Array.from({ length: 42 }, (_, index) => {
    const date = new Date(start)
    date.setUTCDate(date.getUTCDate() + index)
    const value = isoDate(date)
    return { date: value, inMonth: value >= currentMonthStart && value <= currentMonthEnd }
  })
  return { monthStart: currentMonthStart, monthEnd: currentMonthEnd, visibleStart: days[0].date, visibleEnd: days.at(-1).date, days }
}

// Group only records on the selected day. A group can contain payments on other
// dates, which must not be included in this day's amount or transaction count.
export function calendarLedgerDay(entries, date) {
  if (!validCalendarDate(date)) throw new RangeError('Invalid calendar date')
  const daily = entries.filter(entry => entry.entryDate === date)
  const rows = groupLedgerSheetEntries(daily).map(row => ({
    ...row,
    displayedAmount: splitDisplayedAmount(row.parts),
    paymentMethodCount: row.parts.length,
    expectedPaymentMethodCount: row.isSplit ? splitExpectedCount(row.parts) : 1,
  }))
  let incomeCents = 0, expenseCents = 0
  for (const entry of daily) {
    if (entry.financiallyExcluded) continue
    const cents = Math.round((Number(entry.amount) || 0) * 100)
    if (entry.entryType === 'INCOME') incomeCents += cents
    else if (entry.entryType === 'EXPENSE') expenseCents += cents
  }
  return { rows, income: incomeCents / 100, expense: expenseCents / 100, balance: (incomeCents - expenseCents) / 100 }
}

export function calendarLedgerMonth(entries, year, month) {
  const { monthStart, monthEnd } = calendarMonthGrid(year, month)
  let incomeCents = 0, expenseCents = 0
  for (const entry of entries) {
    if (!validCalendarDate(entry.entryDate) || entry.entryDate < monthStart || entry.entryDate > monthEnd || entry.financiallyExcluded) continue
    const cents = Math.round((Number(entry.amount) || 0) * 100)
    if (entry.entryType === 'INCOME') incomeCents += cents
    else if (entry.entryType === 'EXPENSE') expenseCents += cents
  }
  return { income: incomeCents / 100, expense: expenseCents / 100, balance: (incomeCents - expenseCents) / 100 }
}

export function overlapsCalendarDate(startDate, endDate, date) {
  return validCalendarDate(startDate) && validCalendarDate(endDate) && validCalendarDate(date)
    && startDate <= endDate && startDate <= date && date <= endDate
}

export function calendarWeekday(value) {
  if (!validCalendarDate(value)) throw new RangeError('Invalid calendar date')
  const [year, month, day] = value.split('-').map(Number)
  return calendarDate(year, month, day).getUTCDay()
}

export function shiftCalendarDate(value, days) {
  if (!validCalendarDate(value)) throw new RangeError('Invalid calendar date')
  const [year, month, day] = value.split('-').map(Number)
  const date = calendarDate(year, month, day)
  date.setUTCDate(date.getUTCDate() + days)
  return isoDate(date)
}

// The selected day after moving to another month: today when it belongs to that
// month, otherwise the first day.
export function monthTargetDate(year, month, todayIso) {
  const prefix = `${String(year).padStart(4, '0')}-${String(month).padStart(2, '0')}`
  return typeof todayIso === 'string' && todayIso.startsWith(`${prefix}-`) ? todayIso : `${prefix}-01`
}

export function splitCalendarWeeks(days) {
  const weeks = []
  for (let index = 0; index < days.length; index += 7) weeks.push(days.slice(index, index + 7))
  return weeks
}
