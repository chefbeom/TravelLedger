import { expect, test } from '@playwright/test'
import { calendarSettings } from './fixtures/calendarPersonalization.js'

async function mockCalendar(page, { keepSettingsClosed = false } = {}) {
  const state = { events: [], errors: [], writes: [], selected: false, sharedAvailable: true, failPlaces: false, failRecordOptions: false, failCategories: false, importedPlace: false, mapAdded: false, mapSelected: false, mapAvailable: true, routeMode: false, adjustments: false }
  page.on('pageerror', error => state.errors.push(error.message))
  await page.route(/^https?:\/\/(?!127\.0\.0\.1|localhost)/, route => route.abort())
  await page.route('**/api/**', async route => {
    const req = route.request(), url = new URL(req.url()), path = url.pathname
    let json = []
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
      const body = req.postDataJSON(); json = { ...body, id: 9, version: body.version + 1 }; state.events = [json]
    } else if (path === '/api/integrated-calendar/events/9' && req.method() === 'DELETE') {
      state.events = []; return route.fulfill({ status: 204 })
    }
    return route.fulfill({ json })
  })
  await page.clock.install({ time: new Date('2024-02-15T12:00:00+09:00') })
  await page.goto('/#integrated-calendar')
  await expect(page.getByRole('heading', { name: '2024-02-15 상세' })).toBeVisible()
  if (!keepSettingsClosed && !await page.locator('.integrated-calendar__settings').evaluate(el => el.open)) await page.locator('.integrated-calendar__settings > summary').click()
  return state
}

test('mobile prioritizes the calendar while filters remain keyboard accessible', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await mockCalendar(page, { keepSettingsClosed: true })
  const settings = page.locator('.integrated-calendar__settings')
  await expect(settings).not.toHaveAttribute('open', '')
  const summary = settings.locator(':scope > summary')
  await summary.focus()
  await page.keyboard.press('Enter')
  await expect(settings).toHaveAttribute('open', '')
  await expect(page.getByLabel('달력 필터')).toBeVisible()
})

test('a multi-day event shows its end time on the final day', async ({ page }) => {
  const state = await mockCalendar(page)
  state.events = [{ id: 9, version: 0, title: '야간 일정', startDate: '2024-02-14', endDate: '2024-02-15', allDay: false, startTime: '21:00', endTime: '08:30' }]
  await page.getByRole('button', { name: '다시 불러오기', exact: true }).click()
  await expect(page.getByLabel('시간순 요약')).toContainText('08:30 개인 일정 종료 · 야간 일정')
})

test('monthly totals exclude adjacent months, split payments remain one row, and personal events survive reload', async ({ page }) => {
  const state = await mockCalendar(page)
  const summary = page.getByLabel('이번 달 요약')
  await expect(summary).toContainText('50,000')
  await expect(summary).not.toContainText('100,000')
  const detail = page.locator('.integrated-calendar__entry').filter({ hasText: '분할 거래' })
  await expect(detail).toHaveCount(1)
  await detail.getByText('거래 1건 · 결제수단 2개 상세').click()
  await expect(detail).toContainText('카드')
  await expect(detail).toContainText('현금')
  await page.getByText('날짜 미지정 자료', { exact: true }).click()
  await expect(page.getByRole('listitem').filter({ hasText: '날짜 없는 장소' })).toBeVisible()
  await expect(page.getByRole('listitem').filter({ hasText: '기간 없는 여행' })).toBeVisible()
  await page.getByRole('button', { name: '일정 추가' }).click()
  const form = page.locator('.integrated-calendar__form')
  await form.getByLabel('제목').fill('개인 일정')
  await form.getByLabel('종료 날짜 (선택)').fill('2024-02-17')
  await form.getByRole('button', { name: '저장' }).click()
  await expect(page.locator('.integrated-calendar__entry').filter({ hasText: '개인 일정' })).toBeVisible()
  await expect(page.getByRole('button', { name: /^2024-02-15 / })).toHaveAttribute('aria-label', /일정 시작 · 개인 일정/)
  await expect(page.getByRole('button', { name: /^2024-02-16 / })).toHaveAttribute('aria-label', /일정 기간 · 개인 일정/)
  await expect(page.getByRole('button', { name: /^2024-02-17 / })).toHaveAttribute('aria-label', /일정 종료 · 개인 일정/)
  expect(state.writes).toHaveLength(1)
  await page.reload()
  await expect(page.locator('.integrated-calendar__entry').filter({ hasText: '개인 일정' })).toBeVisible()
  await page.locator('.integrated-calendar__entry').filter({ hasText: '개인 일정' }).getByRole('button', { name: '수정' }).click()
  await page.locator('.integrated-calendar__form').getByLabel('메모').fill('다른 기기에서도 확인')
  await page.locator('.integrated-calendar__form').getByRole('button', { name: '저장' }).click()
  await expect(page.locator('.integrated-calendar__entry').filter({ hasText: '개인 일정' })).toContainText('다른 기기에서도 확인')
  page.once('dialog', dialog => dialog.accept())
  await page.locator('.integrated-calendar__entry').filter({ hasText: '개인 일정' }).getByRole('button', { name: '삭제' }).click()
  await expect(page.locator('.integrated-calendar__entry').filter({ hasText: '개인 일정' })).toHaveCount(0)
  expect(state.errors).toEqual([])
})

test('shared trips require an explicit selection, stay separate from ledger totals, and disappear after permission is removed', async ({ page }) => {
  const state = await mockCalendar(page)
  await page.getByText('공유받은 여행 선택', { exact: true }).click()
  const selected = page.getByRole('checkbox', { name: /친구 여행/ })
  await expect(selected).not.toBeChecked()
  await expect(page.locator('.integrated-calendar__entry').filter({ hasText: '공유 · 친구 여행' })).toHaveCount(0)
  await selected.check()
  await expect(page.locator('.integrated-calendar__entry').filter({ hasText: '공유 · 친구 여행' })).toBeVisible()
  await expect(page.getByLabel('이번 달 요약')).toContainText('지출 ₩50,000')
  await page.getByRole('button', { name: '공유 여행 원본 보기' }).click()
  await expect(page.getByRole('region', { name: '공유 여행 원본' }).getByRole('heading', { name: '친구 여행 원본' })).toBeVisible()
  await page.getByRole('button', { name: '공유 여행 원본 닫기' }).click()
  await page.reload()
  await expect(page.locator('.integrated-calendar__entry').filter({ hasText: '공유 · 친구 여행' })).toBeVisible()
  state.sharedAvailable = false
  await page.getByRole('button', { name: '다시 불러오기', exact: true }).click()
  await expect(page.locator('.integrated-calendar__entry').filter({ hasText: '공유 · 친구 여행' })).toHaveCount(0)
  expect(state.errors).toEqual([])
})

test('a shared map is added by link and disappears on deselection or revocation', async ({ page }) => {
  const state = await mockCalendar(page)
  await page.getByText('공유받은 기록 지도 선택', { exact: true }).click()
  await page.getByLabel('기록 지도 공유 주소').fill(`https://example.com/#record-map-share/${'a'.repeat(43)}`)
  await page.getByRole('button', { name: '공유 지도 추가' }).click()
  const option = page.getByRole('checkbox', { name: /친구 장소 모음/ })
  await expect(option).toBeChecked()
  await expect(page.locator('.integrated-calendar__entry').filter({ hasText: '친구 방문' })).toBeVisible()
  await page.getByRole('button', { name: '공유 장소 원본 보기' }).click()
  await expect(page.getByRole('dialog').getByText('친구 방문', { exact: true })).toBeVisible()
  await page.getByRole('dialog').getByRole('button', { name: '창 닫기' }).click()
  await page.getByRole('button', { name: '공유 지도 원본 닫기' }).click()
  await option.uncheck()
  await expect(page.locator('.integrated-calendar__entry').filter({ hasText: '친구 방문' })).toHaveCount(0)
  await option.check()
  await expect(page.locator('.integrated-calendar__entry').filter({ hasText: '친구 방문' })).toBeVisible()
  state.mapAvailable = false
  await page.evaluate(() => window.dispatchEvent(new Event('focus')))
  await expect(page.locator('.integrated-calendar__entry').filter({ hasText: '친구 방문' })).toHaveCount(0)
  expect(state.errors).toEqual([])
})

test('a failed source is reported without hiding the successful ledger', async ({ page }) => {
  const state = await mockCalendar(page)
  state.failPlaces = true
  await page.getByRole('button', { name: '다시 불러오기', exact: true }).click()
  await expect(page.getByRole('alert').filter({ hasText: '장소·기록 월 정보' })).toBeVisible()
  await expect(page.getByLabel('이번 달 요약')).toContainText('조회 실패')
  await expect(page.getByLabel('이번 달 요약')).toContainText('50,000')
  state.failPlaces = false
  await page.getByRole('button', { name: '다시 불러오기', exact: true }).click()
  await expect(page.getByRole('alert').filter({ hasText: '장소·기록 월 정보' })).toHaveCount(0)
})

test('refund, cancellation and reversed records retain their original status and amounts', async ({ page }) => {
  const state = await mockCalendar(page)
  state.adjustments = true
  await page.getByRole('button', { name: '다시 불러오기', exact: true }).click()
  const cancelled = page.locator('.integrated-calendar__entry').filter({ hasText: '취소된 구매' })
  await expect(cancelled).toContainText('결제 취소됨')
  await expect(cancelled).toContainText('합계 제외')
  const refund = page.locator('.integrated-calendar__entry').filter({ hasText: '환불 입금' })
  await expect(refund).toContainText('₩25,000')
  await refund.getByText('거래 상세').click()
  await expect(refund).toContainText('기준 ₩30,000')
  await expect(refund).toContainText('차감 ₩5,000')
  await expect(page.locator('.integrated-calendar__entry').filter({ hasText: '되돌린 환불' })).toContainText('처리 되돌림')
})

test('a failed share source and filter metadata do not hide independently successful data', async ({ page }) => {
  const state = await mockCalendar(page)
  state.selected = true; state.failRecordOptions = true; state.failCategories = true
  await page.reload()
  await expect(page.getByRole('alert').filter({ hasText: '공유 여행 기록 목록' })).toBeVisible()
  await expect(page.getByRole('alert').filter({ hasText: '가계부 분류 필터 목록' })).toBeVisible()
  await expect(page.locator('.integrated-calendar__entry').filter({ hasText: '공유 · 친구 여행' })).toBeVisible()
  await expect(page.getByLabel('이번 달 요약')).toContainText('50,000')
  state.failCategories = false
  await page.getByRole('alert').filter({ hasText: '가계부 분류 필터 목록' }).getByRole('button', { name: '재시도' }).click()
  await expect(page.getByRole('alert').filter({ hasText: '가계부 분류 필터 목록' })).toHaveCount(0)
  expect(state.errors).toEqual([])
})

test('an imported place is grouped only with its original travel record', async ({ page }) => {
  const state = await mockCalendar(page)
  state.importedPlace = true
  await page.getByRole('button', { name: '다시 불러오기', exact: true }).click()
  await expect(page.locator('.integrated-calendar__entry').filter({ has: page.locator('strong', { hasText: '여행 원본' }) })).toHaveCount(1)
  await expect(page.locator('.integrated-calendar__entry').filter({ hasText: '별도 방문' })).toHaveCount(1)
  await page.getByRole('combobox', { name: '종류', exact: true }).selectOption('travel')
  await expect(page.locator('.integrated-calendar__entry').filter({ has: page.locator('strong', { hasText: '여행 원본' }) })).toHaveCount(1)
})

test('opening a visit reaches its original place even when it is outside the map list', async ({ page }) => {
  const state = await mockCalendar(page)
  state.importedPlace = true
  await page.getByRole('button', { name: '다시 불러오기', exact: true }).click()
  await page.locator('.integrated-calendar__entry').filter({ hasText: '별도 방문' }).getByRole('button', { name: '장소 원본 보기' }).click()
  await expect(page.getByRole('dialog')).toContainText('서울 장소')
  await expect(page.getByRole('dialog')).toContainText('별도 방문')
  expect(state.errors).toEqual([])
})

test('opening a grouped transaction reaches and focuses its original date and row', async ({ page }) => {
  const state = await mockCalendar(page)
  await page.locator('.integrated-calendar__entry').filter({ hasText: '분할 거래' }).getByRole('button', { name: '가계부 원본 보기' }).click()
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
  await page.getByRole('button', { name: '다시 불러오기', exact: true }).click()
  await page.locator('.integrated-calendar__entry').filter({ hasText: '원본 산책 경로' }).getByRole('button', { name: '경로 원본 보기' }).click()
  const row = page.getByRole('row').filter({ hasText: '원본 산책 경로' })
  await expect(row).toBeVisible()
  await expect(row).toHaveClass(/sheet-table__row--focused/)
  expect(state.errors).toEqual([])
})

test('opening an imported travel memory reaches its original editor', async ({ page }) => {
  const state = await mockCalendar(page)
  state.routeMode = true; state.importedPlace = true
  await page.getByRole('button', { name: '다시 불러오기', exact: true }).click()
  await page.locator('.integrated-calendar__entry').filter({ hasText: '여행에서 가져온 기록' }).getByRole('button', { name: '여행 기록 원본 보기' }).click()
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
    expect(await page.locator('.integrated-calendar__filters label').evaluateAll(nodes => nodes.every(el => el.scrollWidth <= el.clientWidth + 1))).toBe(true)
    if (width === 390) {
      const cell = page.getByRole('button', { name: /^2024-02-15 / })
      const text = cell.locator('.integrated-calendar__compact-text').first()
      await expect(text).toContainText('−5만')
      expect(await text.evaluate(el => { const r = el.getBoundingClientRect(), cell = el.closest('button').getBoundingClientRect(); return r.height > 5 && r.bottom <= cell.bottom })).toBe(true)
    }
    if (width === 390) await page.locator('.integrated-calendar__settings > summary').click()
    if ([390, 1920].includes(width)) await page.screenshot({ path: info.outputPath(`integrated-calendar-${width}.png`), fullPage: true })
    if (width === 390) await page.locator('.integrated-calendar__settings > summary').click()
  }
  await page.setViewportSize({ width: 320, height: 700 })
  await page.getByText('공유받은 기록 지도 선택', { exact: true }).click()
  await page.getByRole('button', { name: '일정 추가' }).click()
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(1)
  await expect(page.getByLabel('기록 지도 공유 주소')).toBeVisible()
  await expect(page.locator('.integrated-calendar__form').getByLabel('시작 날짜')).toBeVisible()
  await page.locator('.integrated-calendar__form').getByRole('button', { name: '취소' }).click()
  // CSS zoom exercises reflow at the effective 125% and 150% desktop widths;
  // physical-browser zoom is still a separate manual verification.
  await page.setViewportSize({ width: 1920, height: 1200 })
  for (const zoom of [1.25, 1.5]) {
    await page.evaluate(value => { document.documentElement.style.zoom = String(value) }, zoom)
    await expect.poll(() => page.evaluate(() => document.documentElement.getBoundingClientRect().right - innerWidth), { message: `${zoom * 100}% zoom overflow` }).toBeLessThanOrEqual(1)
    await expect(page.getByRole('heading', { name: '2024-02-15 상세' })).toBeVisible()
  }
  await page.evaluate(() => { document.documentElement.style.zoom = '' })
  const tomorrow = page.getByRole('button', { name: /^2024-02-16 / })
  await tomorrow.focus()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('heading', { name: '2024-02-16 상세' })).toBeVisible()
  expect(state.errors).toEqual([])
})
