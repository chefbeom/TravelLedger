import { expect, test } from '@playwright/test'
import { calendarSettings } from './fixtures/calendarPersonalization.js'

const dayCell = (page, date) => page.locator(`.integrated-calendar__cell[data-date="${date}"]`)
const entries = page => page.locator('.integrated-calendar__entry')
const isCompact = page => page.evaluate(() => window.matchMedia('(max-width: 60rem)').matches)
async function closeOverlays(page) {
  for (const selector of ['dialog.integrated-calendar__drawer', 'dialog.integrated-calendar__sheet']) {
    const dialog = page.locator(selector)
    if (await dialog.count() && await dialog.isVisible()) { await page.keyboard.press('Escape'); await expect(dialog).toHaveCount(0) }
  }
}
async function openDayPanel(page) {
  if (!await isCompact(page)) return // desktop: the panel is always visible
  if (await page.locator('dialog.integrated-calendar__sheet').count()) return
  const peek = page.locator('.integrated-calendar__peek')
  await expect(peek).toBeVisible()
  await peek.click()
  await expect(page.locator('dialog.integrated-calendar__sheet')).toBeVisible()
}
async function reload(page) { await closeOverlays(page); await page.getByRole('button', { name: '다시 불러오기', exact: true }).click() }
const openRowMenu = entry => entry.getByRole('button', { name: /^추가 작업/ }).click()

async function openSettings(page) {
  await closeOverlays(page)
  await page.getByRole('button', { name: '필터와 공유 설정' }).click()
  const drawer = page.getByRole('dialog', { name: '필터와 공유 설정' })
  await expect(drawer).toBeVisible()
  return drawer
}

async function mockCalendar(page, { theme = 'default' } = {}) {
  const state = { requests: [], delayMs: 0, conflict: false, events: [], errors: [], writes: [], selected: false, sharedAvailable: true, failPlaces: false, failRecordOptions: false, failCategories: false, importedPlace: false, mapAdded: false, mapSelected: false, mapAvailable: true, routeMode: false, adjustments: false }
  page.on('pageerror', error => state.errors.push(error.message))
  if (theme === 'toss') await page.addInitScript(() => localStorage.setItem('calen-theme-mode', 'toss'))
  await page.route(/^https?:\/\/(?!127\.0\.0\.1|localhost)/, route => route.abort())
  await page.route('**/api/**', async route => {
    const req = route.request(), url = new URL(req.url()), path = url.pathname
    let json = []
    state.requests.push(`${req.method()} ${path}`)
    if (state.delayMs && path.startsWith('/api/integrated-calendar/summary/')) await new Promise(resolve => setTimeout(resolve, state.delayMs))
    if (path === '/api/auth/me') json = { id: 2, loginId: 'fixture', displayName: '달력 검증', active: true, admin: false }
    else if (path === '/api/auth/csrf') json = { token: 'test' }
    else if (path === '/api/record-map/overview' || path === '/api/record-map/map') json = { collections: [], places: [], visits: [], totalPlaces: 0, totalPages: 0 }
    else if (path === '/api/record-map/places/4/visits') json = []
    else if (path === '/api/record-shares/counts') json = { ledger: 0, travel: 0 }
    else if (path.startsWith('/api/categories')) {
      if (state.failCategories) return route.fulfill({ status: 503, json: { message: '분류 조회 실패' } })
      json = [{ id: 101, name: '식비', entryType: 'EXPENSE', active: true, details: [] }]
    }
    else if (path.startsWith('/api/payment-methods')) json = [{ id: 103, name: '카드', active: true }]
    else if (path === '/api/account/preferences/household-personalization') json = calendarSettings()
    else if (path === '/api/account/preferences/household-aggregates') json = { widgets: calendarSettings().widgets }
    else if (path === '/api/entries/date-range') json = { minDate: '2024-01-01', maxDate: '2024-12-31' }
    else if (path === '/api/entries') json = [{ id: 1, title: '분할 거래', entryDate: '2024-02-15', entryTime: '00:00', entryType: 'EXPENSE', amount: 30000, categoryGroupId: 101, categoryGroupName: '식비', paymentMethodId: 103, paymentMethodName: '카드', splitPaymentGroupId: 'g', splitPaymentCount: 2 }, { id: 2, title: '분할 거래', entryDate: '2024-02-15', entryTime: '00:00', entryType: 'EXPENSE', amount: 20000, categoryGroupId: 101, categoryGroupName: '식비', paymentMethodId: 103, paymentMethodName: '현금', splitPaymentGroupId: 'g', splitPaymentCount: 2 }]
    else if (path === '/api/dashboard') json = { anchorDate: '2024-02-15', quickStats: [], calendar: [], expenseBreakdown: [], paymentBreakdown: [], monthlyComparison: [], recentEntries: [] }
    else if (path === '/api/travel/portfolio' && state.routeMode) json = { plans: [{ id: 10, name: '여행' }], planCount: 1, memoryRecords: [], mediaItems: [], routeSegments: [] }
    else if (path === '/api/travel/plans' && state.routeMode) json = [{ id: 10, name: '여행', status: 'PLANNED', startDate: '2024-02-14', endDate: '2024-02-16' }]
    else if (path === '/api/travel/plans/10' && state.routeMode) json = { id: 10, name: '여행', status: 'PLANNED', startDate: '2024-02-14', endDate: '2024-02-16', records: [], memoryRecords: [{ id: 17, title: '여행 원본', memoryDate: '2024-02-15', category: '장소' }], mediaItems: [], budgetItems: [], routeSegments: [{ id: 33, title: '원본 산책 경로', routeDate: '2024-02-15', transportMode: 'WALK', points: [] }] }
    else if (path === '/api/integrated-calendar/summary/ledger') json = [{ date: '2024-02-15', income: 0, expense: 50000, rowCount: 2, transactionCount: 1 }, { date: '2024-03-01', income: 0, expense: 100000, rowCount: 1, transactionCount: 1 }]
    else if (path === '/api/integrated-calendar/summary/places') {
      if (state.failPlaces) return route.fulfill({ status: 503, json: { message: '일시적인 오류' } })
      json = [{ date: '2024-02-15', count: 3 }]
    }
    else if (path === '/api/integrated-calendar/summary/travel') json = { periods: [{ id: 10, title: '여행', startDate: '2024-02-14', endDate: '2024-02-16', status: 'PLANNED' }], routes: [], records: [] }
    else if (path === '/api/integrated-calendar/summary/events') json = state.events
    else if (path === '/api/integrated-calendar/undated') json = { places: { items: [{ id: 3, title: '날짜 없는 장소' }], page: 0, size: 30, total: 1 }, trips: { items: [{ id: 4, title: '기간 없는 여행' }], page: 0, size: 30, total: 1 } }
    else if (path === '/api/integrated-calendar/shared/options/exhibit') json = { page: 0, totalPages: 1, items: state.sharedAvailable ? [{ id: 42, title: '친구 여행', owner: '친구', selected: state.selected }] : [] }
    else if (path === '/api/integrated-calendar/shared/options/record') {
      if (state.failRecordOptions) return route.fulfill({ status: 503, json: { message: '공유 조회 실패' } })
      json = { page: 0, totalPages: 0, items: [] }
    }
    else if (path === '/api/integrated-calendar/shared/selections/exhibit/42') { state.selected = req.postDataJSON().selected; return route.fulfill({ status: 204 }) }
    else if (path === '/api/integrated-calendar/shared/summary/exhibit/42') json = { type: 'exhibit', shareId: 42, title: '친구 여행', data: { periods: [{ id: 20, title: '친구 여행', startDate: '2024-02-15', endDate: '2024-02-16', status: 'COMPLETED' }], records: [], routes: [] } }
    else if (path === '/api/integrated-calendar/shared/day/exhibit/42') json = { type: 'exhibit', shareId: 42, title: '친구 여행', records: [], routes: [] }
    else if (path === '/api/travel/shared-exhibits/42') json = { sharedByDisplayName: '친구', sharedByLoginId: 'friend', travelPlan: { id: 20, name: '친구 여행 원본', status: 'COMPLETED', memoryRecords: [], routeSegments: [], mediaItems: [] } }
    else if (path === '/api/integrated-calendar/shared-map/options') json = { page: 0, totalPages: 1, items: state.mapAdded && state.mapAvailable ? [{ id: 21, title: '친구 장소 모음', selected: state.mapSelected, includesVisits: true }] : [] }
    else if (path === '/api/integrated-calendar/shared-map/links') { state.mapAdded = true; state.mapSelected = true; return route.fulfill({ status: 204 }) }
    else if (path === '/api/integrated-calendar/shared-map/selections/21') { state.mapSelected = req.postDataJSON().selected; return route.fulfill({ status: 204 }) }
    else if (path === '/api/integrated-calendar/shared-map/21/summary') json = { id: 21, title: '친구 장소 모음', days: [{ date: '2024-02-15', count: 1 }] }
    else if (path === '/api/integrated-calendar/shared-map/21/day') json = { id: 21, title: '친구 장소 모음', entries: [{ id: 7, placeId: 55, sourceKind: 'VISIT', placeName: '친구 장소', title: '친구 방문', photoCount: 0 }] }
    else if (path === '/api/integrated-calendar/shared-map/21/map' || path === '/api/integrated-calendar/shared-map/21/overview') json = { collections: [], places: [], visits: [], totalPlaces: 0, totalPages: 0, readOnly: true, visitsIncluded: true, title: '친구 장소 모음' }
    else if (path === '/api/integrated-calendar/shared-map/21/place-source/55') json = { place: { id: 55, name: '친구 장소', latitude: 37, longitude: 127, visited: true, visitCount: 1, collectionIds: [] }, visit: { id: 'visit:7', placeId: 55, title: '친구 방문', date: '2024-02-15', photos: [] } }
    else if (path === '/api/integrated-calendar/day/ledger' && state.adjustments) json = [
      { id: 4, title: '취소된 구매', entryDate: '2024-02-15', entryTime: '10:00', entryType: 'EXPENSE', amount: 30000, paymentMethodName: '카드', cancelledAt: '2024-02-15T10:00:00', financiallyExcluded: true },
      { id: 5, title: '환불 입금', entryDate: '2024-02-15', entryTime: '11:00', entryType: 'INCOME', amount: 25000, paymentMethodName: '현금', adjustmentType: 'REFUND', adjustmentOriginId: 4, adjustmentPrincipal: 30000, adjustmentDeduction: 5000, adjustmentExtra: 0 },
      { id: 6, title: '되돌린 환불', entryDate: '2024-02-15', entryTime: '12:00', entryType: 'INCOME', amount: 10000, paymentMethodName: '현금', adjustmentType: 'REFUND', adjustmentOriginId: 4, adjustmentVoidedAt: '2024-02-15T13:00:00', financiallyExcluded: true },
    ]
    else if (path === '/api/integrated-calendar/day/ledger') json = url.searchParams.get('date') === '2024-02-15' ? [
      { id: 1, title: '분할 거래', entryDate: '2024-02-15', entryTime: '00:00', entryType: 'EXPENSE', amount: 30000, paymentMethodName: '카드', splitPaymentGroupId: 'g', splitPaymentCount: 2, installmentMonths: 3 },
      { id: 2, title: '분할 거래', entryDate: '2024-02-15', entryTime: '00:00', entryType: 'EXPENSE', amount: 20000, paymentMethodName: '현금', splitPaymentGroupId: 'g', splitPaymentCount: 2 },
    ] : []
    else if (path === '/api/integrated-calendar/day/places' && state.importedPlace) json = [
      { id: 17, placeId: 4, placeName: '서울 장소', title: '여행 원본', categories: [], photoCount: 0, sourceKind: 'TRAVEL_IMPORT', sourceTravelRecordId: 17 },
      { id: 18, placeId: 4, placeName: '서울 장소', title: '별도 방문', categories: [], photoCount: 0, sourceKind: 'VISIT', sourceTravelRecordId: 17 },
    ]
    else if (path === '/api/integrated-calendar/day/travel-records' && state.importedPlace) json = [{ id: 17, planId: 10, planName: '여행', title: '여행 원본', type: 'MEMORY', photoCount: 0 }]
    else if (path === '/api/integrated-calendar/day/routes' && state.routeMode) json = [{ id: 33, planId: 10, planName: '여행', title: '원본 산책 경로', transportMode: 'WALK', distanceKm: 3 }]
    else if (path === '/api/integrated-calendar/day/place-source/4') json = { place: { id: 4, name: '서울 장소', latitude: 37, longitude: 127, visited: true, visitCount: 1, collectionIds: [] }, visit: { id: 'visit:18', placeId: 4, title: '별도 방문', date: '2024-02-15', photos: [] } }
    else if (path === '/api/integrated-calendar/events' && req.method() === 'GET') json = state.events.filter(event => event.startDate <= url.searchParams.get('to') && event.endDate >= url.searchParams.get('from'))
    else if (path === '/api/integrated-calendar/events' && req.method() === 'POST') {
      const body = req.postDataJSON(); state.writes.push(body); json = { ...body, id: 9, version: 0 }; state.events.push(json)
    } else if (path === '/api/integrated-calendar/events/9' && req.method() === 'PUT') {
      if (state.conflict) return route.fulfill({ status: 409, json: { message: 'conflict' } })
      const body = req.postDataJSON(); json = { ...body, id: 9, version: body.version + 1 }; state.events = [json]
    } else if (path === '/api/integrated-calendar/events/9' && req.method() === 'DELETE') {
      state.events = []; return route.fulfill({ status: 204 })
    }
    return route.fulfill({ json })
  })
  await page.clock.install({ time: new Date('2024-02-15T12:00:00+09:00') })
  await page.goto('/#integrated-calendar')
  await expect(dayCell(page, '2024-02-15')).toHaveAttribute('aria-pressed', 'true')
  await expect(dayCell(page, '2024-02-15')).toHaveAttribute('aria-label', /지출/)
  return state
}

test('mobile prioritizes the calendar while filters remain keyboard accessible', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await mockCalendar(page)
  const top = await page.evaluate(() => document.querySelector('.integrated-calendar__month').getBoundingClientRect().top - document.querySelector('.integrated-calendar').getBoundingClientRect().top)
  expect(top).toBeLessThanOrEqual(260)
  await expect(page.locator('dialog.integrated-calendar__drawer')).toHaveCount(0)
  const opener = page.getByRole('button', { name: '필터와 공유 설정' })
  await opener.focus()
  await page.keyboard.press('Enter')
  const drawer = page.getByRole('dialog', { name: '필터와 공유 설정' })
  await expect(drawer).toBeVisible()
  await expect(drawer.getByLabel('달력 필터')).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(drawer).toHaveCount(0)
  await expect(opener).toBeFocused()
})

test('a multi-day event shows its end time on the final day', async ({ page }) => {
  const state = await mockCalendar(page)
  state.events = [{ id: 9, version: 0, title: '야간 일정', startDate: '2024-02-14', endDate: '2024-02-15', allDay: false, startTime: '21:00', endTime: '08:30' }]
  await reload(page)
  await openDayPanel(page)
  await expect(entries(page).filter({ hasText: '야간 일정' })).toContainText('종료 08:30')
})

test('monthly totals exclude adjacent months, split payments remain one row, and personal events survive reload', async ({ page }) => {
  const state = await mockCalendar(page)
  const summary = page.getByLabel('이번 달 요약')
  await expect(summary).toContainText('50,000')
  await expect(summary).not.toContainText('100,000')
  await openDayPanel(page)
  const detail = entries(page).filter({ hasText: '분할 거래' })
  await expect(detail).toHaveCount(1)
  await detail.getByText('거래 1건 · 결제수단 2개 상세').click()
  await expect(detail).toContainText('카드')
  await expect(detail).toContainText('현금')
  const undatedDrawer = await openSettings(page)
  await undatedDrawer.getByText('날짜 미지정 자료', { exact: true }).click()
  await expect(undatedDrawer.getByRole('listitem').filter({ hasText: '날짜 없는 장소' })).toBeVisible()
  await expect(undatedDrawer.getByRole('listitem').filter({ hasText: '기간 없는 여행' })).toBeVisible()
  await closeOverlays(page)
  await openDayPanel(page)
  await page.getByRole('button', { name: '일정 추가' }).click()
  const form = page.locator('.integrated-calendar__form')
  await form.getByLabel('제목').fill('개인 일정')
  await form.getByLabel('종료 날짜 (선택)').fill('2024-02-17')
  await form.getByRole('button', { name: '저장' }).click()
  await expect(entries(page).filter({ hasText: '개인 일정' })).toBeVisible()
  await expect(dayCell(page, '2024-02-15')).toHaveAttribute('aria-label', /일정 시작 · 개인 일정/)
  await expect(dayCell(page, '2024-02-16')).toHaveAttribute('aria-label', /일정 기간 · 개인 일정/)
  await expect(dayCell(page, '2024-02-17')).toHaveAttribute('aria-label', /일정 종료 · 개인 일정/)
  await expect(page.locator('.integrated-calendar__bar[data-key="event-9"]')).toHaveCount(1)
  expect(state.writes).toHaveLength(1)
  await page.reload()
  await openDayPanel(page)
  await expect(entries(page).filter({ hasText: '개인 일정' })).toBeVisible()
  await entries(page).filter({ hasText: '개인 일정' }).getByRole('button', { name: /^일정 수정/ }).click()
  await page.locator('.integrated-calendar__form').getByLabel('메모').fill('다른 기기에서도 확인')
  await page.locator('.integrated-calendar__form').getByRole('button', { name: '저장' }).click()
  await expect(entries(page).filter({ hasText: '개인 일정' })).toContainText('다른 기기에서도 확인')
  await openRowMenu(entries(page).filter({ hasText: '개인 일정' }))
  await page.getByRole('menuitem', { name: '삭제' }).click()
  const confirm = page.getByRole('alertdialog', { name: '일정을 삭제할까요?' })
  await expect(confirm).toContainText('다른 원본 기록은 삭제되지 않습니다')
  await confirm.getByRole('button', { name: '삭제' }).click()
  await expect(entries(page).filter({ hasText: '개인 일정' })).toHaveCount(0)
  expect(state.errors).toEqual([])
})

test('shared trips require an explicit selection, stay separate from ledger totals, and disappear after permission is removed', async ({ page }) => {
  const state = await mockCalendar(page)
  const drawer = await openSettings(page)
  const selected = drawer.getByRole('checkbox', { name: /친구 여행/ })
  await expect(selected).not.toBeChecked()
  await expect(page.locator('.integrated-calendar__entry[data-shared="true"]')).toHaveCount(0)
  await selected.check()
  await expect(selected).toBeChecked()
  await closeOverlays(page)
  await expect(page.locator('.integrated-calendar__bar[data-shared="true"]')).toHaveCount(1)
  await openDayPanel(page)
  await expect(entries(page).filter({ hasText: '친구 여행' })).toBeVisible()
  await expect(entries(page).filter({ hasText: '친구 여행' })).toContainText('공유')
  await expect(page.getByLabel('이번 달 요약')).toContainText(/지출\s*₩50,000/)
  await page.getByRole('button', { name: /^공유 여행 원본 보기/ }).click()
  await expect(page.getByRole('dialog', { name: '공유 여행 원본' }).getByRole('heading', { name: '친구 여행 원본' })).toBeVisible()
  await page.getByRole('button', { name: '공유 여행 원본 닫기' }).click()
  await page.reload()
  await expect(page.locator('.integrated-calendar__bar[data-shared="true"]')).toHaveCount(1)
  state.sharedAvailable = false
  await reload(page)
  await expect(page.locator('.integrated-calendar__bar[data-shared="true"]')).toHaveCount(0)
  await openDayPanel(page)
  await expect(entries(page).filter({ hasText: '친구 여행' })).toHaveCount(0)
  expect(state.errors).toEqual([])
})

test('a shared map is added by link and disappears on deselection or revocation', async ({ page }) => {
  const state = await mockCalendar(page)
  const drawer = await openSettings(page)
  await drawer.getByLabel('기록 지도 공유 주소').fill(`https://example.com/#record-map-share/${'a'.repeat(43)}`)
  await drawer.getByRole('button', { name: '공유 지도 추가' }).click()
  const option = drawer.getByRole('checkbox', { name: /친구 장소 모음/ })
  await expect(option).toBeChecked()
  await closeOverlays(page)
  await expect(dayCell(page, '2024-02-15')).toHaveAttribute('aria-label', /공유 장소 1건/)
  await openDayPanel(page)
  await expect(entries(page).filter({ hasText: '친구 방문' })).toBeVisible()
  await page.getByRole('button', { name: /^공유 장소 원본 보기/ }).click()
  await expect(page.locator('.record-map-dialog').getByText('친구 방문', { exact: true })).toBeVisible()
  await page.locator('.record-map-dialog').getByRole('button', { name: '창 닫기' }).click()
  await page.getByRole('dialog', { name: '공유 지도 원본' }).getByRole('button', { name: '공유 지도 원본 닫기' }).click()
  await expect(page.getByRole('dialog', { name: '공유 지도 원본' })).toHaveCount(0)
  const drawer2 = await openSettings(page)
  await drawer2.getByRole('checkbox', { name: /친구 장소 모음/ }).uncheck()
  await closeOverlays(page)
  await expect(dayCell(page, '2024-02-15')).not.toHaveAttribute('aria-label', /공유 장소/)
  const drawer3 = await openSettings(page)
  await drawer3.getByRole('checkbox', { name: /친구 장소 모음/ }).check()
  await closeOverlays(page)
  await expect(dayCell(page, '2024-02-15')).toHaveAttribute('aria-label', /공유 장소 1건/)
  state.mapAvailable = false
  await page.evaluate(() => window.dispatchEvent(new Event('focus')))
  await expect(dayCell(page, '2024-02-15')).not.toHaveAttribute('aria-label', /공유 장소/)
  await openDayPanel(page)
  await expect(entries(page).filter({ hasText: '친구 방문' })).toHaveCount(0)
  expect(state.errors).toEqual([])
})

test('a failed source is reported without hiding the successful ledger', async ({ page }) => {
  const state = await mockCalendar(page)
  state.failPlaces = true
  await reload(page)
  await expect(page.getByRole('alert').filter({ hasText: '장소·기록 월 정보' })).toBeVisible()
  await expect(page.getByLabel('이번 달 요약')).toContainText('조회 실패')
  await expect(page.getByLabel('이번 달 요약')).toContainText('50,000')
  state.failPlaces = false
  await page.locator('.integrated-calendar__issues').getByRole('button', { name: '재시도' }).click()
  await expect(page.getByRole('alert').filter({ hasText: '장소·기록 월 정보' })).toHaveCount(0)
})

test('refund, cancellation and reversed records retain their original status and amounts', async ({ page }) => {
  const state = await mockCalendar(page)
  state.adjustments = true
  await reload(page)
  await openDayPanel(page)
  await expect(page.locator('.integrated-calendar__entry[data-layer="ledger"]')).toHaveCount(1)
  const cancelled = entries(page).filter({ hasText: '취소된 구매' })
  await expect(cancelled).toContainText('결제 취소됨')
  await expect(cancelled).toContainText('합계 제외')
  await expect(cancelled).toContainText('처리 2건')
  await cancelled.getByText('거래 상세').click()
  await expect(cancelled).toContainText('환불 입금')
  await expect(cancelled).toContainText('₩25,000')
  await expect(cancelled).toContainText('기준 ₩30,000')
  await expect(cancelled).toContainText('차감 ₩5,000')
  await expect(cancelled).toContainText('환불 되돌림')
})

test('a failed share source and filter metadata do not hide independently successful data', async ({ page }) => {
  const state = await mockCalendar(page)
  state.selected = true; state.failRecordOptions = true; state.failCategories = true
  await page.reload()
  await expect(page.getByRole('alert').filter({ hasText: '공유 여행 기록 목록' })).toBeVisible()
  await expect(page.getByRole('alert').filter({ hasText: '가계부 분류 필터 목록' })).toBeVisible()
  await expect(page.locator('.integrated-calendar__bar[data-shared="true"]')).toHaveCount(1)
  await expect(page.getByLabel('이번 달 요약')).toContainText('50,000')
  state.failCategories = false
  await page.getByRole('alert').filter({ hasText: '가계부 분류 필터 목록' }).getByRole('button', { name: '재시도' }).click()
  await expect(page.getByRole('alert').filter({ hasText: '가계부 분류 필터 목록' })).toHaveCount(0)
  await openDayPanel(page)
  await expect(entries(page).filter({ hasText: '친구 여행' })).toBeVisible()
  expect(state.errors).toEqual([])
})

test('an imported place is grouped only with its original travel record', async ({ page }) => {
  const state = await mockCalendar(page)
  state.importedPlace = true
  await reload(page)
  await openDayPanel(page)
  await expect(entries(page).filter({ has: page.locator('.integrated-calendar__entry-title', { hasText: '여행 원본' }) })).toHaveCount(1)
  await expect(entries(page).filter({ hasText: '별도 방문' })).toHaveCount(1)
  await closeOverlays(page)
  const placesChip = page.getByRole('group', { name: '표시할 자료' }).getByRole('button', { name: '장소·기록', exact: true })
  await placesChip.click()
  await expect(placesChip).toHaveAttribute('aria-pressed', 'false')
  await openDayPanel(page)
  await expect(entries(page).filter({ has: page.locator('.integrated-calendar__entry-title', { hasText: '여행 원본' }) })).toHaveCount(1)
  await expect(entries(page).filter({ hasText: '별도 방문' })).toHaveCount(0)
})

test('opening a visit reaches its original place even when it is outside the map list', async ({ page }) => {
  const state = await mockCalendar(page)
  state.importedPlace = true
  await reload(page)
  await openDayPanel(page)
  await entries(page).filter({ hasText: '별도 방문' }).getByRole('button', { name: /^장소 원본 보기/ }).click()
  await expect(page.getByRole('dialog')).toContainText('서울 장소')
  await expect(page.getByRole('dialog')).toContainText('별도 방문')
  expect(state.errors).toEqual([])
})

test('opening a grouped transaction reaches and focuses its original date and row', async ({ page }) => {
  const state = await mockCalendar(page)
  await openDayPanel(page)
  await entries(page).filter({ hasText: '분할 거래' }).getByRole('button', { name: /^가계부 원본 보기/ }).click()
  const sheet = page.getByRole('dialog', { name: '거래 시트' })
  await expect(sheet.getByRole('heading', { name: '2월 15일 거래 시트' })).toBeVisible()
  const row = sheet.getByRole('row').filter({ hasText: '분할 거래' })
  await expect(row).toBeVisible()
  await expect(row).toBeFocused()
  expect(state.errors).toEqual([])
})

test('opening a route reaches the original route after its plan finishes loading', async ({ page }) => {
  const state = await mockCalendar(page)
  state.routeMode = true
  await reload(page)
  await openDayPanel(page)
  await entries(page).filter({ hasText: '원본 산책 경로' }).getByRole('button', { name: /^경로 원본 보기/ }).click()
  const row = page.getByRole('row').filter({ hasText: '원본 산책 경로' })
  await expect(row).toBeVisible()
  await expect(row).toHaveClass(/sheet-table__row--focused/)
  expect(state.errors).toEqual([])
})

test('opening an imported travel memory reaches its original editor', async ({ page }) => {
  const state = await mockCalendar(page)
  state.routeMode = true; state.importedPlace = true
  await reload(page)
  await openDayPanel(page)
  const imported = entries(page).filter({ hasText: '여행에서 가져온 기록' })
  await openRowMenu(imported)
  await page.getByRole('menuitem', { name: '여행 기록 원본 보기' }).click()
  await expect(page.getByRole('heading', { name: '여행 기록 수정' })).toBeVisible()
  expect(state.errors).toEqual([])
})

test('calendar and details remain within the viewport at phone and desktop sizes', async ({ page }, info) => {
  test.skip(info.project.name !== 'chromium-desktop', 'This case exercises the viewport matrix itself.')
  const state = await mockCalendar(page)
  for (const [width, height] of [[320, 700], [390, 844], [768, 1024], [1366, 768], [1920, 1200], [2560, 1440]]) {
    await page.setViewportSize({ width, height })
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth - innerWidth), { message: `${width}px horizontal overflow` }).toBeLessThanOrEqual(1)
    expect(await page.locator('.integrated-calendar__cell').evaluateAll(nodes => nodes.every(el => el.scrollWidth <= el.clientWidth + 1))).toBe(true)
    expect(await page.locator('.integrated-calendar__layer-chip').evaluateAll(nodes => nodes.every(el => el.scrollWidth <= el.clientWidth + 1))).toBe(true)
    if (width === 390) {
      const text = dayCell(page, '2024-02-15').locator('.integrated-calendar__cell-money').first()
      await expect(text).toContainText('−5만')
      expect(await text.evaluate(el => { const r = el.getBoundingClientRect(), cell = el.closest('button').getBoundingClientRect(); return r.height > 5 && r.bottom <= cell.bottom && parseFloat(getComputedStyle(el).fontSize) >= 11 })).toBe(true)
    }
    if ([390, 1920].includes(width)) await page.screenshot({ path: info.outputPath(`integrated-calendar-${width}.png`), fullPage: true })
    if (width === 390) { await openSettings(page); await page.screenshot({ path: info.outputPath('integrated-calendar-390-drawer.png') }); await closeOverlays(page) }
  }
  await page.setViewportSize({ width: 320, height: 700 })
  const narrow = await openSettings(page)
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(1)
  await expect(narrow.getByLabel('기록 지도 공유 주소')).toBeVisible()
  await closeOverlays(page)
  await openDayPanel(page)
  await page.getByRole('button', { name: '일정 추가' }).click()
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(1)
  await expect(page.locator('.integrated-calendar__form').getByLabel('시작 날짜')).toBeVisible()
  await page.locator('.integrated-calendar__form').getByRole('button', { name: '취소' }).click()
  await closeOverlays(page)
  // CSS zoom exercises reflow at the effective 125% and 150% desktop widths;
  // physical-browser zoom is still a separate manual verification.
  await page.setViewportSize({ width: 1920, height: 1200 })
  for (const zoom of [1.25, 1.5]) {
    await page.evaluate(value => { document.documentElement.style.zoom = String(value) }, zoom)
    await expect.poll(() => page.evaluate(() => document.documentElement.getBoundingClientRect().right - innerWidth), { message: `${zoom * 100}% zoom overflow` }).toBeLessThanOrEqual(1)
    await expect(page.getByRole('heading', { name: /2월 15일/ })).toBeVisible()
  }
  await page.evaluate(() => { document.documentElement.style.zoom = '' })
  const tomorrow = dayCell(page, '2024-02-16')
  await tomorrow.focus()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('heading', { name: /2월 16일/ })).toBeVisible()
  expect(state.errors).toEqual([])
})

for (const theme of ['default', 'toss']) {
  test(`visual review screenshots (${theme})`, async ({ page }, info) => {
    test.skip(info.project.name !== 'chromium-desktop', 'Screenshots are reviewed manually once per theme.')
    const state = await mockCalendar(page, { theme })
    Object.assign(state, { importedPlace: true, routeMode: true, selected: true })
    state.events = [{ id: 9, version: 0, title: '야간 일정', startDate: '2024-02-14', endDate: '2024-02-20', allDay: false, startTime: '21:00', endTime: '08:30' }]
    await reload(page)
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.screenshot({ path: info.outputPath(`ic-1440-${theme}.png`), fullPage: true })
    await page.setViewportSize({ width: 390, height: 844 })
    await page.screenshot({ path: info.outputPath(`ic-390-${theme}.png`), fullPage: true })
    await openDayPanel(page)
    await page.screenshot({ path: info.outputPath(`ic-390-sheet-${theme}.png`) })
    await openSettings(page)
    await page.screenshot({ path: info.outputPath(`ic-390-drawer-${theme}.png`) })
    expect(state.errors).toEqual([])
  })
}

test('a multi-day trip is drawn as one continuous bar across its days', async ({ page }) => {
  const state = await mockCalendar(page)
  const bar = page.locator('.integrated-calendar__bar[data-key="travel-10"]')
  await expect(bar).toHaveCount(1)
  const [barBox, first, last] = await Promise.all([bar.boundingBox(), dayCell(page, '2024-02-14').boundingBox(), dayCell(page, '2024-02-16').boundingBox()])
  expect(Math.abs(barBox.x - first.x)).toBeLessThanOrEqual(2)
  expect(Math.abs(barBox.x + barBox.width - (last.x + last.width))).toBeLessThanOrEqual(2)
  expect(state.errors).toEqual([])
})

test('the event dialog is keyboard accessible and validates input', async ({ page }) => {
  const state = await mockCalendar(page)
  await openDayPanel(page)
  const add = page.getByRole('button', { name: '일정 추가' })
  await add.click()
  const form = page.locator('.integrated-calendar__form')
  await expect(form.getByLabel('제목')).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(form).toHaveCount(0)
  await expect(add).toBeFocused()
  await add.click()
  await page.locator('.integrated-calendar__form').getByRole('button', { name: '저장' }).click()
  await expect(page.locator('.integrated-calendar__form').getByRole('alert')).toContainText('제목과 시작·종료 날짜 및 시간을 확인해 주세요.')
  expect(state.writes).toHaveLength(0)
  expect(state.errors).toEqual([])
})

test('a version conflict keeps the event dialog open with a clear message', async ({ page }) => {
  const state = await mockCalendar(page)
  state.events = [{ id: 9, version: 0, title: '충돌 일정', startDate: '2024-02-15', endDate: '2024-02-15', allDay: true }]
  await reload(page)
  await openDayPanel(page)
  state.conflict = true
  await entries(page).filter({ hasText: '충돌 일정' }).getByRole('button', { name: /^일정 수정/ }).click()
  await page.locator('.integrated-calendar__form').getByLabel('메모').fill('수정')
  await page.locator('.integrated-calendar__form').getByRole('button', { name: '저장' }).click()
  await expect(page.locator('.integrated-calendar__form').getByRole('alert')).toContainText('다른 화면에서 일정이 변경되었습니다. 다시 불러온 뒤 수정해 주세요.')
  expect(state.errors).toEqual([])
})

test('deleting an event asks for confirmation with the cancel button focused', async ({ page }) => {
  const state = await mockCalendar(page)
  state.events = [{ id: 9, version: 0, title: '지울 일정', startDate: '2024-02-15', endDate: '2024-02-15', allDay: true }]
  await reload(page)
  await openDayPanel(page)
  const row = entries(page).filter({ hasText: '지울 일정' })
  await openRowMenu(row)
  await page.getByRole('menuitem', { name: '삭제' }).click()
  const confirm = page.getByRole('alertdialog', { name: '일정을 삭제할까요?' })
  await expect(confirm.getByRole('button', { name: '취소' })).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(confirm).toHaveCount(0)
  await expect(row).toHaveCount(1)
  expect(state.errors).toEqual([])
})

test('on a phone a tapped date opens a bottom sheet that closes back to the tapped cell', async ({ page }, info) => {
  test.skip(info.project.name !== 'chromium-mobile', 'The bottom sheet only exists in the compact layout.')
  const state = await mockCalendar(page)
  await expect(page.locator('.integrated-calendar__peek')).toContainText('상세 보기')
  await dayCell(page, '2024-02-16').tap()
  const sheet = page.locator('dialog.integrated-calendar__sheet')
  await expect(sheet).toBeVisible()
  await expect(sheet.getByRole('heading', { name: /2월 16일/ })).toBeVisible()
  await sheet.getByRole('button', { name: '다음 날' }).click()
  await expect(sheet.getByRole('heading', { name: /2월 17일/ })).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(sheet).toHaveCount(0)
  await expect(dayCell(page, '2024-02-16')).toBeFocused()
  await page.evaluate(() => window.scrollTo(0, 200))
  expect(await page.evaluate(() => window.scrollY)).toBeGreaterThan(0)
  expect(state.errors).toEqual([])
})

test('an open shared original survives a refresh and closes only when its permission is removed', async ({ page }) => {
  const state = await mockCalendar(page)
  const drawer = await openSettings(page)
  await drawer.getByRole('checkbox', { name: /친구 여행/ }).check()
  await closeOverlays(page)
  await openDayPanel(page)
  await page.getByRole('button', { name: /^공유 여행 원본 보기/ }).click()
  const overlay = page.getByRole('dialog', { name: '공유 여행 원본' })
  await expect(overlay).toBeVisible()
  await page.clock.fastForward(2000)
  await page.evaluate(() => window.dispatchEvent(new Event('focus')))
  await expect(overlay).toBeVisible()
  state.sharedAvailable = false
  await page.clock.fastForward(2000)
  await page.evaluate(() => window.dispatchEvent(new Event('focus')))
  await expect(overlay).toHaveCount(0)
  await expect(page.locator('.integrated-calendar__issues')).toContainText('공유 권한이 변경되어 원본을 닫았습니다.')
  expect(state.errors).toEqual([])
})

test('moving months selects today only in the current month, and the month picker works from the keyboard', async ({ page }) => {
  const state = await mockCalendar(page)
  await page.getByRole('button', { name: '다음 달' }).click()
  await expect(dayCell(page, '2024-03-01')).toHaveAttribute('aria-pressed', 'true')
  await page.getByRole('button', { name: '이전 달' }).click()
  await expect(dayCell(page, '2024-02-15')).toHaveAttribute('aria-pressed', 'true')
  const trigger = page.locator('.integrated-calendar__month-trigger')
  await trigger.click()
  await page.getByRole('dialog', { name: '연월 선택' }).getByRole('button', { name: '5월' }).click()
  await expect(dayCell(page, '2024-05-01')).toHaveAttribute('aria-pressed', 'true')
  await expect(trigger).toContainText('2024년 5월')
  await trigger.click()
  await expect(page.getByRole('dialog', { name: '연월 선택' })).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog', { name: '연월 선택' })).toHaveCount(0)
  await expect(trigger).toBeFocused()
  expect(state.errors).toEqual([])
})

test('a refresh keeps the calendar content on screen while it is revalidating', async ({ page }) => {
  const state = await mockCalendar(page)
  state.delayMs = 1500
  await reload(page)
  await expect(dayCell(page, '2024-02-15')).toHaveAttribute('aria-label', /지출/)
  await expect(page.locator('.integrated-calendar__month')).toHaveAttribute('aria-busy', 'true')
  await expect(page.locator('.integrated-calendar__month')).toHaveAttribute('aria-busy', 'false')
  expect(state.errors).toEqual([])
})

test('changing a ledger filter only requests ledger data, not shared or other month sources', async ({ page }) => {
  const state = await mockCalendar(page)
  await page.waitForTimeout(300)
  const before = state.requests.length
  const drawer = await openSettings(page)
  await drawer.getByLabel('거래 구분').selectOption('INCOME')
  await expect.poll(() => state.requests.slice(before).filter(request => request.includes('/summary/ledger')).length).toBeGreaterThan(0)
  await page.waitForTimeout(300)
  const added = state.requests.slice(before)
  expect(added.filter(request => /\/shared\/options\/|\/shared-map\/options|\/summary\/(places|travel|events)/.test(request))).toEqual([])
  expect(state.errors).toEqual([])
})

test('an event crossing a week boundary is drawn as two connected bars', async ({ page }) => {
  const state = await mockCalendar(page)
  state.events = [{ id: 9, version: 0, title: '긴 일정', startDate: '2024-02-15', endDate: '2024-02-20', allDay: true }]
  await reload(page)
  const bars = page.locator('.integrated-calendar__bar[data-key="event-9"]')
  await expect(bars).toHaveCount(2)
  await expect(bars.first()).toHaveAttribute('data-continues-after', 'true')
  await expect(bars.last()).toHaveAttribute('data-continues-before', 'true')
  await expect(dayCell(page, '2024-02-18')).toHaveAttribute('aria-label', /일정 기간 · 긴 일정/)
  expect(state.errors).toEqual([])
})

test('turning a layer chip off hides its data and its sub-filters', async ({ page }) => {
  const state = await mockCalendar(page)
  const ledgerChip = page.getByRole('group', { name: '표시할 자료' }).getByRole('button', { name: '가계부', exact: true })
  await ledgerChip.click()
  await expect(ledgerChip).toHaveAttribute('aria-pressed', 'false')
  await expect(dayCell(page, '2024-02-15')).not.toHaveAttribute('aria-label', /수입 ₩/)
  let drawer = await openSettings(page)
  await expect(drawer.getByText('거래 구분')).toHaveCount(0)
  await closeOverlays(page)
  await ledgerChip.click()
  await expect(dayCell(page, '2024-02-15')).toHaveAttribute('aria-label', /수입 ₩/)
  drawer = await openSettings(page)
  await expect(drawer.getByText('거래 구분')).toBeVisible()
  expect(state.errors).toEqual([])
})

test('the calendar grid starts near the top of the page', async ({ page }) => {
  await mockCalendar(page)
  const top = await page.evaluate(() => document.querySelector('.integrated-calendar__month').getBoundingClientRect().top - document.querySelector('.integrated-calendar').getBoundingClientRect().top)
  expect(top).toBeLessThanOrEqual(await isCompact(page) && page.viewportSize().width <= 560 ? 260 : 220)
  const clipped = await page.evaluate(() => { const strip = document.querySelector('.integrated-calendar__summary').getBoundingClientRect(); return [...document.querySelectorAll('.integrated-calendar__kpi-value')].filter(el => el.offsetParent !== null).filter(el => el.scrollWidth > el.clientWidth + 1 || el.getBoundingClientRect().right > strip.right + 1 || el.getBoundingClientRect().left < strip.left - 1).map(el => el.textContent) })
  expect(clipped).toEqual([])
  const kpiText = await page.locator('.integrated-calendar__summary').innerText()
  expect(kpiText).toMatch(/50,000/)
})
