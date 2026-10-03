import { expect, test } from '@playwright/test'

const snapshot = { id: 100, entryDate: '2026-10-03', title: '공유한 저녁 식사', memo: '가족 카드 사용', amount: 10000, entryType: 'EXPENSE', categoryGroupId: 10, categoryGroupName: '식비', categoryDetailId: 11, categoryDetailName: '외식', paymentMethodId: 12, paymentMethodName: '가족 카드', travelPlanId: 999, travelRecordId: 888 }
const group = { id: 5, name: '가족', ownerId: 1, members: [{ userId: 1, loginId: 'test-sender', displayName: '보낸 사람', self: false }, { userId: 2, loginId: 'test-receiver', displayName: '받는 사람', self: true }] }

async function fixture(page, kind = 'LEDGER', root = false) {
  const state = { status: 'PENDING', writes: [], reads: [] }
  await page.route(/^https?:\/\/(?!127\.0\.0\.1|localhost)/, (route) => route.abort())
  await page.route('**/api/**', async (route) => {
    const req = route.request()
    const url = new URL(req.url())
    const path = url.pathname
    if (req.method() === 'POST') {
      state.writes.push({ path, body: req.postDataJSON() })
      if (path.endsWith('accept-ledger') || path.endsWith('accept-travel')) state.status = 'ACCEPTED'
    } else state.reads.push(path)
    const item = { id: 9, groupId: 5, groupName: '가족', kind, sourceId: 100, senderId: 1, senderName: '보낸 사람', recipientId: 2, recipientName: '받는 사람', title: kind === 'LEDGER' ? snapshot.title : '함께한 여행', ledger: kind === 'LEDGER' ? snapshot : null, status: state.status, createdAt: '2026-10-03T09:00:00', importedLedgerEntryId: state.status === 'ACCEPTED' && kind === 'LEDGER' ? 300 : null }
    let body = {}
    if (path === '/api/auth/me') return route.fulfill(root ? { json: { id: 2, loginId: 'test-receiver', displayName: '받는 사람', active: true, admin: false } } : { status: 401, json: { message: 'test session' } })
    if (path === '/api/auth/csrf') body = { token: 'test-only-csrf' }
    else if (path === '/api/record-shares/groups') body = [group]
    else if (path === '/api/record-shares/counts') body = { ledger: 1, travel: 1 }
    else if (path === '/api/record-shares' && req.method() === 'GET') {
      const items = url.searchParams.get('status') && url.searchParams.get('status') !== state.status ? [] : [item]
      body = { items, page: 0, size: 10, totalElements: items.length, totalPages: items.length ? 1 : 0 }
    }
    else if (path.startsWith('/api/categories')) body = [{ id: 101, name: '식비', entryType: 'EXPENSE', active: true, details: [{ id: 102, name: '외식', active: true }] }]
    else if (path.startsWith('/api/payment-methods')) body = [{ id: 103, name: '가족 카드', active: true }]
    else if (path === '/api/entries' || path === '/api/travel/plans') body = []
    else if (path === '/api/entries/date-range') body = { minDate: '2026-10-01', maxDate: '2026-10-03' }
    else if (path === '/api/dashboard') body = { anchorDate: '2026-10-03', quickStats: [], calendar: [], expenseBreakdown: [], paymentBreakdown: [], monthlyComparison: [], recentEntries: [] }
    else if (path === '/api/account/preferences/household-aggregates') body = { widgets: [] }
    else if (path === '/api/record-shares/9/travel') body = { id: 9, sharedByLoginId: 'test-sender', sharedByDisplayName: '보낸 사람', sharedAt: '2026-10-03T09:00:00', travelPlan: { id: 100, name: '함께한 여행', status: 'PLANNED', startDate: '2026-10-01', endDate: '2026-10-03', memoryRecordCount: 0, routeSegmentCount: 0, mediaItemCount: 0, memoryRecords: [], routeSegments: [], mediaItems: [], budgetItems: [], expenseRecords: [] } }
    else if (req.method() === 'POST') body = item
    return route.fulfill({ json: body })
  })
  await page.goto(root ? '/#household' : '/')
  if (root) return state
  await page.evaluate(async (selectedKind) => {
    const { createApp } = await import('/node_modules/.vite/deps/vue.js')
    const { default: Component } = await import('/src/components/RecordSharingWorkspace.vue')
    document.querySelector('#app').style.display = 'none'
    const host = document.createElement('div')
    host.className = 'app-shell'
    host.id = 'record-sharing-test'
    document.body.appendChild(host)
    createApp(Component, { kind: selectedKind }).mount(host)
  }, kind)
  return state
}

test('ledger confirmation edits and imports only receiver-owned classification', async ({ page }, testInfo) => {
  const state = await fixture(page)
  await page.getByRole('button', { name: '확인 후 내 가계부에 기록', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog).toBeVisible()
  expect(state.writes).toHaveLength(0)
  await dialog.getByLabel('제목', { exact: true }).fill('수정한 저녁 식사')
  await dialog.getByLabel('금액 (원)', { exact: true }).fill('12000')
  await dialog.screenshot({ path: testInfo.outputPath('confirmation.png') })
  await dialog.getByRole('button', { name: '내 가계부에 등록', exact: true }).click()
  await expect(dialog).not.toBeVisible()
  await expect(page.getByRole('status')).toHaveText('내 가계부에 등록했습니다. 원본과 독립적으로 수정할 수 있습니다.')
  await page.getByLabel('처리 상태').selectOption('ACCEPTED')
  await expect(page.getByText('내 가계부에 등록된 독립 거래 #300')).toBeVisible()
  expect(state.writes).toHaveLength(1)
  expect(state.writes[0].body).toMatchObject({ title: '수정한 저녁 식사', amount: 12000, categoryGroupId: 101, categoryDetailId: 102, paymentMethodId: 103, travelPlanId: null, travelRecordId: null })
  await expect(page.getByRole('button', { name: '확인 후 내 가계부에 기록', exact: true })).toHaveCount(0)
})

test('household menu shows pending badge and opens record sharing workspace', async ({ page }) => {
  const errors = []
  page.on('pageerror', (error) => errors.push(error.message))
  await fixture(page, 'LEDGER', true)
  const tab = page.getByRole('button', { name: /기록 공유/ })
  await expect(tab.locator('.record-share-badge')).toHaveText('1')
  await tab.click()
  await expect(page.getByRole('heading', { name: '가계부 기록 공유', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '확인 후 내 가계부에 기록', exact: true })).toBeVisible()
  expect(errors).toEqual([])
})

test('travel is opened only after approval and stays read-only', async ({ page }) => {
  const state = await fixture(page, 'TRAVEL')
  await expect(page.getByRole('button', { name: '여행 수락', exact: true })).toBeVisible()
  expect(state.reads).not.toContain('/api/record-shares/9/travel')
  await page.getByRole('button', { name: '여행 수락', exact: true }).click()
  await expect(page.getByText(/님이 공유한 여행입니다. 이 화면은 읽기 전용입니다./)).toBeVisible()
  expect(state.writes).toHaveLength(1)
  expect(state.writes[0].path).toBe('/api/record-shares/9/accept-travel')
  expect(state.reads).toContain('/api/record-shares/9/travel')
  await expect(page.getByRole('button', { name: '선택 여행 수정', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '여행 열기', exact: true })).toBeVisible()
})

test('share dialog sends only selected members and excludes self', async ({ page }) => {
  const state = await fixture(page)
  await page.evaluate(async () => {
    const { createApp } = await import('/node_modules/.vite/deps/vue.js')
    const { default: Component } = await import('/src/components/RecordShareDialog.vue')
    const host = document.createElement('div'); document.body.appendChild(host)
    createApp(Component, { kind: 'LEDGER', source: { id: 100, title: '공유한 저녁 식사' } }).mount(host)
  })
  const dialog = page.getByRole('dialog')
  await expect(dialog.getByRole('checkbox')).toHaveCount(1)
  expect((await dialog.getByRole('checkbox').boundingBox()).width).toBeLessThanOrEqual(24)
  await dialog.getByRole('checkbox').check()
  await dialog.getByRole('button', { name: '1명에게 공유 요청', exact: true }).click()
  await expect.poll(() => state.writes.length).toBe(1)
  expect(state.writes[0].body).toEqual({ kind: 'LEDGER', sourceId: 100, groupId: 5, recipientIds: [1] })
})
