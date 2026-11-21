import test from 'node:test'
import assert from 'node:assert/strict'
import { calendarLedgerDay, calendarLedgerMonth, calendarMonthGrid, calendarWeekday, monthTargetDate, overlapsCalendarDate, shiftCalendarDate, splitCalendarWeeks, validCalendarDate } from '../../src/lib/integratedCalendarCore.js'

test('calendar uses date-only values across leap years and month boundaries', () => {
  const grid = calendarMonthGrid(2024, 2)
  assert.equal(grid.days.length, 42)
  assert.equal(grid.monthEnd, '2024-02-29')
  assert.equal(grid.visibleStart, '2024-01-28')
  assert.equal(grid.visibleEnd, '2024-03-09')
  assert.equal(grid.days.filter(day => day.inMonth).length, 29)
  assert.equal(validCalendarDate('2023-02-29'), false)
  assert.equal(validCalendarDate('2024-02-29'), true)
  assert.equal(validCalendarDate('2024-2-01'), false)
})

test('split payments are grouped only within their actual date, while excluded cancellations do not count', () => {
  const entries = [
    { id: 1, entryDate: '2024-02-01', entryType: 'EXPENSE', amount: 30000, splitPaymentGroupId: 'a', splitPaymentCount: 3 },
    { id: 2, entryDate: '2024-02-01', entryType: 'EXPENSE', amount: 15000, splitPaymentGroupId: 'a', splitPaymentCount: 3, installmentMonths: 5 },
    { id: 3, entryDate: '2024-02-02', entryType: 'EXPENSE', amount: 5000, splitPaymentGroupId: 'a', splitPaymentCount: 3 },
    { id: 4, entryDate: '2024-02-01', entryType: 'EXPENSE', amount: 9000, financiallyExcluded: true },
    { id: 5, entryDate: '2024-02-01', entryType: 'INCOME', amount: 25000, adjustmentType: 'REFUND', adjustmentOriginId: 1, adjustmentPrincipal: 30000, adjustmentDeduction: 5000 },
    { id: 6, entryDate: '2024-03-01', entryType: 'EXPENSE', amount: 100000 },
  ]
  const day = calendarLedgerDay(entries, '2024-02-01')
  assert.equal(day.rows.length, 3)
  assert.equal(day.rows[0].paymentMethodCount, 2)
  assert.equal(day.rows[0].expectedPaymentMethodCount, 3)
  assert.equal(day.rows[0].displayedAmount, 45000)
  assert.equal(day.rows[0].parts[1].installmentMonths, 5)
  assert.equal(day.rows[2].parts[0].adjustmentDeduction, 5000)
  assert.deepEqual(calendarLedgerMonth(entries, 2024, 2), { income: 25000, expense: 50000, balance: -25000 })
})

test('periods require actual valid dates, with inclusive multi-day boundaries', () => {
  assert.equal(overlapsCalendarDate('2024-02-28', '2024-03-02', '2024-02-29'), true)
  assert.equal(overlapsCalendarDate('2024-02-28', '2024-03-02', '2024-03-02'), true)
  assert.equal(overlapsCalendarDate(null, null, '2024-02-29'), false)
  assert.equal(overlapsCalendarDate('2024-03-02', '2024-02-28', '2024-02-29'), false)
})

test('weekday and date shifting use UTC date-only arithmetic', () => {
  assert.equal(calendarWeekday('2024-02-15'), 4)
  assert.equal(calendarWeekday('2024-02-11'), 0)
  assert.throws(() => calendarWeekday('2023-02-29'), RangeError)
  assert.equal(shiftCalendarDate('2024-02-29', 1), '2024-03-01')
  assert.equal(shiftCalendarDate('2024-03-01', -1), '2024-02-29')
  assert.equal(shiftCalendarDate('2023-12-31', 1), '2024-01-01')
})

test('moving months selects today only when it belongs to the target month', () => {
  assert.equal(monthTargetDate(2024, 2, '2024-02-15'), '2024-02-15')
  assert.equal(monthTargetDate(2024, 3, '2024-02-15'), '2024-03-01')
})

test('the 42 day grid splits into six weeks of seven days', () => {
  const weeks = splitCalendarWeeks(calendarMonthGrid(2024, 2).days)
  assert.equal(weeks.length, 6)
  assert.ok(weeks.every(week => week.length === 7))
  assert.equal(weeks[0][0].date, '2024-01-28')
})
