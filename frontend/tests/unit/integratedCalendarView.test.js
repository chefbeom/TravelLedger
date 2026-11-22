import test from 'node:test'
import assert from 'node:assert/strict'
import { calendarLedgerDay, calendarMonthGrid } from '../../src/lib/integratedCalendarCore.js'
import { buildDaySummaries, buildDayTimeline, buildMonthSummary, collectMonthSpans, formatCellAmount, formatDayHeading, formatMonthDay, isLayerVisible, visibleSharedMapEntries } from '../../src/lib/integratedCalendarView.js'

const grid = calendarMonthGrid(2024, 2)
const allOn = () => true
const won = value => `₩${Number(value).toLocaleString('en-US')}`
const month = {
  ledger: [{ date: '2024-02-15', income: 0, expense: 50000, transactionCount: 1 }, { date: '2024-02-15', income: 1, expense: 1, transactionCount: 1 }, { date: '2024-03-01', income: 0, expense: 100000, transactionCount: 1 }],
  places: [{ date: '2024-02-15', count: 3 }],
  travel: { periods: [{ id: 10, title: '여행', startDate: '2024-02-14', endDate: '2024-02-16', status: 'PLANNED' }], routes: [{ date: '2024-02-15', count: 2 }], records: [{ date: '2024-02-15', type: 'MEMORY', count: 4, importedCount: 1 }] },
  events: [{ id: 9, title: '일정', startDate: '2024-02-15', endDate: '2024-02-17' }, { id: 8, title: '하루', startDate: '2024-02-15', endDate: '2024-02-15' }],
}
const sharedSummaries = [{ type: 'exhibit', shareId: 42, title: '친구 여행', data: { periods: [{ id: 20, title: '친구 여행', startDate: '2024-02-15', endDate: '2024-02-16' }, { id: 21, title: '두번째', startDate: '2024-02-15', endDate: '2024-02-15' }], records: [{ date: '2024-02-15', type: 'MEMORY', count: 2 }], routes: [{ date: '2024-02-15', count: 1 }] } }]
const mapSummaries = [{ id: 21, title: '친구 장소 모음', days: [{ date: '2024-02-15', count: 1 }] }]
const summaries = (overrides = {}) => buildDaySummaries({ days: grid.days, month, sharedSummaries, mapSummaries, isVisible: allOn, includeShared: true, formatCurrency: won, ...overrides })

test('day summaries cover all 42 cells but the month summary excludes adjacent months', () => {
  const map = summaries()
  assert.equal(map.size, 42)
  assert.equal(map.get('2024-03-01').ledger.expense, 100000)
  const summary = buildMonthSummary({ grid, month, isVisible: allOn, includeShared: true, shared: sharedSummaries })
  assert.equal(summary.expense, 50001)
  assert.equal(summary.income, 1)
  assert.equal(summary.visits, 3)
  assert.equal(summary.tripCount, 1)
  assert.equal(summary.sharedCount, 1)
})

test('day summary texts keep the original order and wording', () => {
  const texts = summaries().get('2024-02-15').items.map(item => item.text)
  assert.deepEqual(texts, [
    '수입 ₩0 · 지출 ₩50,000', '장소 기록 3건', '공유 장소 1건', '여행 기간 · 여행', '여행 기록 3건', '경로 2건',
    '일정 시작 · 일정', '일정 · 하루', '공유 여행 시작 · 친구 여행', '공유 여행 · 두번째', '공유 기록 2건', '공유 경로 1건',
  ])
  const other = summaries().get('2024-02-16').items.map(item => item.text)
  assert.ok(other.includes('여행 종료 · 여행') && other.includes('일정 기간 · 일정') && other.includes('공유 여행 종료 · 친구 여행'))
  assert.ok(summaries().get('2024-02-17').items.map(item => item.text).includes('일정 종료 · 일정'))
})

test('imported travel records are subtracted only while the places layer is shown', () => {
  assert.ok(summaries().get('2024-02-15').items.some(item => item.text === '여행 기록 3건'))
  const placesOff = summaries({ isVisible: source => source !== 'places' }).get('2024-02-15').items.map(item => item.text)
  assert.ok(placesOff.includes('여행 기록 4건'))
  const noPlaceData = summaries({ month: { ...month, places: null } }).get('2024-02-15').items.map(item => item.text)
  assert.ok(noPlaceData.includes('여행 기록 4건'))
  const zero = summaries({ month: { ...month, travel: { ...month.travel, records: [{ date: '2024-02-15', type: 'MEMORY', count: 1, importedCount: 1 }] } } })
  assert.ok(!zero.get('2024-02-15').items.some(item => item.key === 'record-MEMORY'))
})

test('shared items disappear with includeShared false and layers disappear when hidden', () => {
  assert.ok(!summaries({ includeShared: false }).get('2024-02-15').items.some(item => item.shared))
  const noLedger = summaries({ isVisible: source => source !== 'ledger' }).get('2024-02-15')
  assert.equal(noLedger.ledger, null)
  assert.ok(!noLedger.items.some(item => item.text.startsWith('수입')))
})

test('a share with two periods gets distinct span keys and the first ledger row per date is used', () => {
  const day = summaries().get('2024-02-15')
  const keys = day.spans.filter(item => item.shared).map(item => item.key)
  assert.equal(new Set(keys).size, 2)
  assert.equal(day.ledger.expense, 50000)
})

test('month spans follow layer visibility', () => {
  const spans = collectMonthSpans({ month, sharedSummaries, isVisible: allOn, includeShared: true })
  assert.deepEqual(spans.map(item => item.key), ['travel-10', 'event-9', 'event-8', 'shared-exhibit-42-20', 'shared-exhibit-42-21'])
  assert.deepEqual(collectMonthSpans({ month, sharedSummaries, isVisible: source => source === 'events', includeShared: true }).map(item => item.key), ['event-9', 'event-8'])
})

test('layer visibility maps derived sources', () => {
  const layers = { ledger: true, places: false, travel: true, routes: false, events: true }
  assert.equal(isLayerVisible(layers, 'travel-records'), true)
  assert.equal(isLayerVisible(layers, 'ledger-categories'), true)
  assert.equal(isLayerVisible(layers, 'places'), false)
})

const ledgerEntries = [
  { id: 1, title: '분할 거래', entryDate: '2024-02-15', entryTime: '00:00', entryType: 'EXPENSE', amount: 30000, paymentMethodName: '카드', splitPaymentGroupId: 'g', splitPaymentCount: 2 },
  { id: 2, title: '분할 거래', entryDate: '2024-02-15', entryTime: '00:00', entryType: 'EXPENSE', amount: 20000, paymentMethodName: '현금', splitPaymentGroupId: 'g', splitPaymentCount: 2 },
]
const timeline = (overrides = {}) => buildDayTimeline({
  date: '2024-02-15', isVisible: allOn, includeShared: true, ledgerRows: calendarLedgerDay(ledgerEntries, '2024-02-15').rows,
  daySources: { places: [], 'travel-records': [], routes: [], events: [] }, periods: [], sharedSummaries: [], sharedDays: [], mapDays: [], formatCurrency: won, ...overrides,
})

test('a split payment is one row that opens the first entry', () => {
  const rows = timeline()
  assert.equal(rows.length, 1)
  assert.deepEqual(rows[0].primary.action, { type: 'ledger', payload: { date: '2024-02-15', entryId: 1 } })
  assert.equal(rows[0].amount.sign, '−')
  assert.match(rows[0].timeNote, /^가계부의 00:00은/)
  assert.equal(timeline({ ledgerRows: calendarLedgerDay([{ ...ledgerEntries[0], entryTime: '10:00', splitPaymentGroupId: null, splitPaymentCount: null }], '2024-02-15').rows })[0].timeNote, '')
})

test('an imported place groups with its original record only', () => {
  const daySources = {
    places: [
      { id: 17, placeId: 4, placeName: '서울 장소', title: '여행 원본', categories: [], photoCount: 0, sourceKind: 'TRAVEL_IMPORT', sourceTravelRecordId: 17 },
      { id: 18, placeId: 4, placeName: '서울 장소', title: '별도 방문', categories: [], photoCount: 0, sourceKind: 'VISIT', sourceTravelRecordId: 17 },
    ],
    'travel-records': [{ id: 17, planId: 10, planName: '여행', title: '여행 원본', type: 'MEMORY', photoCount: 0 }], routes: [], events: [],
  }
  const rows = timeline({ ledgerRows: [], daySources })
  assert.deepEqual(rows.map(row => row.kind), ['place', 'place'])
  const imported = rows.find(row => row.key === 'place-TRAVEL_IMPORT-17')
  assert.deepEqual(imported.secondary.map(item => item.label), ['여행 기록 원본 보기'])
  assert.deepEqual(imported.secondary[0].action.payload, { sourceType: 'memory', planId: 10, recordId: 17 })
  assert.equal(rows.find(row => row.key === 'place-VISIT-18').secondary.length, 0)
  const placesOff = timeline({ ledgerRows: [], daySources, isVisible: source => source !== 'places' })
  assert.deepEqual(placesOff.map(row => row.key), ['travel-17'])
})

test('multi-day events show an end time on the last day and continue in between', () => {
  const night = { id: 9, title: '야간', startDate: '2024-02-14', endDate: '2024-02-15', allDay: false, startTime: '21:00', endTime: '08:30' }
  const ending = timeline({ ledgerRows: [], daySources: { places: [], 'travel-records': [], routes: [], events: [night] } })[0]
  assert.equal(ending.timeLabel, '종료 08:30')
  assert.equal(ending.group, 'timed')
  const long = { id: 10, title: '긴', startDate: '2024-02-14', endDate: '2024-02-16', allDay: false, startTime: '09:00', endTime: '10:00' }
  const middle = timeline({ ledgerRows: [], daySources: { places: [], 'travel-records': [], routes: [], events: [long] } })[0]
  assert.equal(middle.timeLabel, '계속')
  assert.equal(middle.group, 'allday')
})

test('shared map entries duplicating a visible shared trip record are hidden', () => {
  const share = { id: 21, title: '지도', entries: [{ id: 5, sourceKind: 'TRAVEL_IMPORT', placeName: 'a', title: 'b' }, { id: 6, sourceKind: 'VISIT', placeName: 'c', title: 'd' }] }
  const sharedDays = [{ type: 'exhibit', shareId: 42, title: '친구', records: [{ id: 5, title: 'x' }], routes: [] }]
  assert.deepEqual(visibleSharedMapEntries(share, { travelVisible: true, sharedDays }).map(item => item.id), [6])
  assert.deepEqual(visibleSharedMapEntries(share, { travelVisible: false, sharedDays }).map(item => item.id), [5, 6])
  const rows = timeline({ ledgerRows: [], sharedDays, mapDays: [share] })
  assert.ok(rows.some(row => row.kind === 'shared-map' && row.data.id === 6))
  assert.ok(!rows.some(row => row.kind === 'shared-map' && row.data.id === 5))
})

test('timeline order is all-day, then timed by time, then untimed, ties by key', () => {
  const daySources = {
    places: [{ id: 1, placeId: 1, placeName: 'p', title: 'b', categories: [], photoCount: 0, sourceKind: 'VISIT', time: '09:00' }, { id: 2, placeId: 1, placeName: 'p', title: 'a', categories: [], photoCount: 0, sourceKind: 'VISIT' }],
    'travel-records': [], routes: [{ id: 3, planId: 1, title: 'route' }],
    events: [{ id: 4, title: 'all', startDate: '2024-02-15', endDate: '2024-02-15', allDay: true }, { id: 5, title: 'early', startDate: '2024-02-15', endDate: '2024-02-15', allDay: false, startTime: '08:00' }],
  }
  const rows = timeline({ ledgerRows: [], daySources })
  assert.deepEqual(rows.map(row => row.key), ['event-4', 'event-5', 'place-VISIT-1', 'place-VISIT-2', 'route-3'])
  assert.deepEqual(rows.map(row => row.group), ['allday', 'timed', 'timed', 'untimed', 'untimed'])
})

test('shared travel expenses are neutral and flagged as excluded from totals', () => {
  const sharedDays = [{ type: 'exhibit', shareId: 42, title: '친구', records: [{ id: 1, title: '식사', kind: 'LEDGER', amountKrw: 12000 }, { id: 2, title: '메모', kind: 'MEMORY' }], routes: [] }]
  const rows = timeline({ ledgerRows: [], sharedDays })
  const expense = rows.find(row => row.key === 'shared-exhibit-42-1')
  assert.equal(expense.amount.tone, 'neutral')
  assert.ok(expense.badges.includes('합계 제외'))
  assert.equal(rows.find(row => row.key === 'shared-exhibit-42-2').amount, null)
})

test('formatters', () => {
  assert.equal(formatDayHeading('2024-02-15', '2024-02-15'), '2월 15일 목요일')
  assert.equal(formatDayHeading('2023-12-31', '2024-02-15'), '2023년 12월 31일 일요일')
  assert.equal(formatMonthDay('2024-02-15'), '2월 15일(목)')
  assert.equal(formatCellAmount(-50000, { compact: true }), '−5만')
  assert.equal(formatCellAmount(50000, { hidden: true }), '•••')
  assert.equal(formatCellAmount(12000), '+12,000')
  assert.equal(formatCellAmount(-1200000), '−120만')
})

const adjustedEntries = [
  { id: 4, title: '취소된 구매', entryDate: '2024-02-15', entryTime: '09:00', entryType: 'EXPENSE', amount: 30000, paymentMethodName: '카드', financiallyExcluded: true, cancelledAt: '2024-02-15T10:00:00', hasAdjustmentHistory: true, refundedPrincipal: 0 },
  { id: 5, title: '환불: 취소된 구매', entryDate: '2024-02-15', entryTime: '11:00', entryType: 'INCOME', amount: 25000, paymentMethodName: '현금', adjustmentType: 'REFUND', adjustmentOriginId: 4, adjustmentPrincipal: 30000, adjustmentDeduction: 5000, adjustmentExtra: 0 },
  { id: 6, title: '환불: 취소된 구매', entryDate: '2024-02-15', entryTime: '12:00', entryType: 'INCOME', amount: 1000, paymentMethodName: '현금', financiallyExcluded: true, adjustmentType: 'REFUND', adjustmentOriginId: 4, adjustmentVoidedAt: '2024-02-15T13:00:00' },
]

test('adjustments fold into the original timeline row with status, refund and strike markers', () => {
  const rows = timeline({ ledgerRows: calendarLedgerDay(adjustedEntries, '2024-02-15').rows })
  assert.equal(rows.length, 1)
  assert.ok(rows[0].badges.includes('결제 취소됨'))
  assert.ok(rows[0].badges.includes('합계 제외'))
  assert.ok(rows[0].badges.includes('환불 입금 +₩25,000'))
  assert.equal(rows[0].amount.struck, true)
  assert.equal(rows[0].data.adjustments.length, 2)
  assert.ok(timeline({ ledgerRows: calendarLedgerDay(adjustedEntries, '2024-02-15').rows, formatCurrency: () => '금액 숨김' })[0].badges.includes('환불 입금 +금액 숨김'))
})

test('an adjustment whose original is missing keeps a linked badge', () => {
  const rows = timeline({ ledgerRows: calendarLedgerDay([adjustedEntries[1]], '2024-02-15').rows })
  assert.equal(rows.length, 1)
  assert.ok(rows[0].badges.includes('환불 입금 · 원거래 #4'))
  assert.equal(rows[0].amount.struck, false)
})
