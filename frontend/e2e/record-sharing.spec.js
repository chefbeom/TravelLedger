import { expect, test } from '@playwright/test'

const snapshot = { id: 100, entryDate: '2026-10-03', title: '공유한 저녁 식사', memo: '가족 카드 사용', amount: 10000, entryType: 'EXPENSE', categoryGroupId: 10, categoryGroupName: '식비', categoryDetailId: 11, categoryDetailName: '외식', paymentMethodId: 12, paymentMethodName: '가족 카드', travelPlanId: 999, travelRecordId: 888 }
const group = { id: 5, name: '가족', ownerId: 1, members: [{ userId: 1, loginId: 'test-sender', displayName: '보낸 사람', self: false }, { userId: 2, loginId: 'test-receiver', displayName: '받는 사람', self: true }] }

async function fixture(page, kind = 'LEDGER', root = false, options = {}) {
  const state = { status: 'PENDING', writes: [], reads: [], groups: options.groups ?? [group], layouts: {}, entries: options.entries ?? [], shareMemo: options.shareMemo ?? null }
  await page.route(/^https?:\/\/(?!127\.0\.0\.1|localhost)/, (route) => route.abort())
  await page.route('**/api/**', async (route) => {
    const req = route.request()
    const url = new URL(req.url())
    const path = url.pathname
    if (path.startsWith('/api/account/preferences/layout-settings/')) {
      if (req.method() === 'PUT') state.layouts[path] = req.postDataJSON()
      return route.fulfill({ json: state.layouts[path] ?? {} })
    }
    if (req.method() === 'POST') {
      state.writes.push({ path, body: req.postDataJSON() })
      if (path.endsWith('accept-ledger') || path.endsWith('accept-travel')) state.status = 'ACCEPTED'
      if (path === '/api/record-shares') {
        if (options.failShareOnce && state.writes.filter((item) => item.path === path).length === 1) return route.fulfill({ status: 503, json: { message: '일시적으로 공유할 수 없습니다. 다시 시도해 주세요.' } })
        state.shareMemo = req.postDataJSON().shareMemo ?? null
      }
    } else state.reads.push(path)
    const item = { id: 9, groupId: 5, groupName: '가족', kind, sourceId: 100, senderId: 1, senderName: '보낸 사람', recipientId: 2, recipientName: '받는 사람', title: kind === 'LEDGER' ? snapshot.title : '함께한 여행', ledger: kind === 'LEDGER' ? snapshot : null, status: state.status, createdAt: '2026-10-03T09:00:00', importedLedgerEntryId: state.status === 'ACCEPTED' && kind === 'LEDGER' ? 300 : null, shareMemo: state.shareMemo }
    let body = {}
    if (path === '/api/auth/me') return route.fulfill(root ? { json: { id: 2, loginId: 'test-receiver', displayName: '받는 사람', active: true, admin: false } } : { status: 401, json: { message: 'test session' } })
    if (path === '/api/auth/csrf') body = { token: 'test-only-csrf' }
    else if (path === '/api/record-shares/groups') body = state.groups
    else if (path === '/api/travel/share-recipients') body = options.searchResults ?? []
    else if (path === '/api/travel/share-groups' && req.method() === 'POST') {
      const payload = req.postDataJSON()
      const members = (options.searchResults ?? []).filter((member) => payload.recipientLoginIds.includes(member.loginId))
      const saved = { id: 6, name: payload.name, ownerId: 2, members: [{ userId: 2, loginId: 'test-receiver', displayName: '받는 사람', self: true }, ...members] }
      state.groups = [...state.groups.filter((item) => item.name !== payload.name), saved]
      body = saved
    }
    else if (path === '/api/record-shares/counts') body = { ledger: 1, travel: 1 }
    else if (path === '/api/record-shares' && req.method() === 'GET') {
      const items = url.searchParams.get('status') && url.searchParams.get('status') !== state.status ? [] : [item]
      body = { items, page: 0, size: 10, totalElements: items.length, totalPages: items.length ? 1 : 0 }
    }
    else if (path.startsWith('/api/categories')) body = [{ id: 101, name: '식비', entryType: 'EXPENSE', active: true, details: [{ id: 102, name: '외식', active: true }] }]
    else if (path.startsWith('/api/payment-methods')) body = [{ id: 103, name: '가족 카드', active: true }]
    else if (path === '/api/entries' && req.method() === 'POST') {
      body = { ...req.postDataJSON(), id: 200 + state.entries.length, categoryGroupName: '식비', categoryDetailName: '외식', paymentMethodName: '가족 카드' }
      state.entries = [...state.entries, body]
    }
    else if (path === '/api/entries') body = state.entries
    else if (path === '/api/travel/plans') body = []
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
  expect(state.writes[0].body).toEqual({ kind: 'LEDGER', sourceId: 100, groupId: 5, recipientIds: [1], shareMemo: null })
})

async function openShareDialog(page) {
  await page.evaluate(async () => {
    const { createApp } = await import('/node_modules/.vite/deps/vue.js')
    const { default: Component } = await import('/src/components/RecordShareDialog.vue')
    const host = document.createElement('div'); document.body.appendChild(host)
    createApp(Component, { kind: 'LEDGER', source: { id: 100, title: '공유한 저녁 식사' } }).mount(host)
  })
  return page.getByRole('dialog', { name: '기록 공유', exact: true })
}

test('share memo sends with selected recipients, counts characters and preserves a failed draft', async ({ page }, testInfo) => {
  const state = await fixture(page, 'LEDGER', false, { groups: [ownedGroup], failShareOnce: true })
  const dialog = await openShareDialog(page)
  const input = dialog.getByLabel('공유 메모 (선택)', { exact: true })
  await expect(input).toHaveAttribute('maxlength', '500')
  await expect(dialog.getByText('0 / 500자', { exact: true })).toBeVisible()
  const memo = '  당신 카드로 결제했어요.\n식비로 등록해 주세요.  '
  await input.fill(memo)
  await dialog.getByLabel('친구 하나 (test-friend-a)에게 공유').check()
  await expect(dialog.getByText(`${memo.length} / 500자`, { exact: true })).toBeVisible()
  await dialog.getByRole('button', { name: '1명에게 공유 요청', exact: true }).click()
  await expect(dialog.getByRole('alert')).toContainText('다시 시도해 주세요.')
  await expect(input).toHaveValue(memo)
  await expect(dialog.getByLabel('친구 하나 (test-friend-a)에게 공유')).toBeChecked()
  await dialog.screenshot({ path: testInfo.outputPath('share-memo-draft.png') })
  await dialog.getByRole('button', { name: '1명에게 공유 요청', exact: true }).click()
  await expect.poll(() => state.writes.length).toBe(2)
  expect(state.writes[1].body).toEqual({ kind: 'LEDGER', sourceId: 100, groupId: 6, recipientIds: [3], shareMemo: memo.trim() })
  expect(state.writes[1].body).not.toHaveProperty('memo')
  await expect(dialog.getByRole('alert')).toHaveCount(0)
})

test('share memo is visible in received, sent and confirmation views without changing the transaction memo', async ({ page }, testInfo) => {
  const memo = '당신 카드로 결제했어요.\n<img src=x onerror=alert(1)>\n' + '긴 메모'.repeat(45)
  const state = await fixture(page, 'LEDGER', false, { shareMemo: memo })
  const card = page.locator('.record-share-card')
  await expect(card.getByRole('region', { name: '공유 메모', exact: true })).toBeVisible()
  await expect(card.locator('.record-share-note p')).toHaveText(memo)
  await expect(card.locator('.record-share-note img')).toHaveCount(0)
  await expect(card.locator('.record-share-note p')).toHaveCSS('white-space', 'pre-wrap')
  for (const theme of ['default', 'toss']) {
    await page.evaluate((value) => { document.documentElement.dataset.theme = value }, theme)
    await expect(card.getByRole('region', { name: '공유 메모', exact: true })).toBeVisible()
    expect(await card.evaluate((element) => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
    await card.screenshot({ path: testInfo.outputPath(`share-memo-card-${theme}.png`) })
  }
  await page.getByRole('button', { name: '보낸 기록', exact: true }).click()
  await expect(card.locator('.record-share-note p')).toHaveText(memo)
  await page.getByRole('button', { name: '받은 기록', exact: true }).click()
  await page.getByRole('button', { name: '확인 후 내 가계부에 기록', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '확인 후 내 가계부에 기록', exact: true })
  await expect(dialog.locator('.record-share-note p')).toHaveText(memo)
  await expect(dialog.getByLabel('메모', { exact: true })).toHaveValue(snapshot.memo)
  await dialog.screenshot({ path: testInfo.outputPath('share-memo-confirmation.png') })
  await dialog.getByRole('button', { name: '내 가계부에 등록', exact: true }).click()
  await expect(dialog).toHaveCount(0)
  expect(state.writes[0].body.memo).toBe(snapshot.memo)
  expect(state.writes[0].body).not.toHaveProperty('shareMemo')
  await page.getByLabel('처리 상태').selectOption('ACCEPTED')
  await expect(card.locator('.record-share-note p')).toHaveText(memo)
})

test('optional blank share memo sends null and a 500-character memo can be shared', async ({ page }) => {
  const state = await fixture(page)
  const dialog = await openShareDialog(page)
  const input = dialog.getByLabel('공유 메모 (선택)', { exact: true })
  await dialog.getByRole('checkbox').check()
  await input.fill(' \n ')
  await dialog.getByRole('button', { name: '1명에게 공유 요청', exact: true }).click()
  await expect.poll(() => state.writes.length).toBe(1)
  expect(state.writes[0].body.shareMemo).toBeNull()
  await input.fill('가'.repeat(500))
  await expect(dialog.getByText('500 / 500자', { exact: true })).toBeVisible()
  await dialog.getByRole('button', { name: '1명에게 공유 요청', exact: true }).click()
  await expect.poll(() => state.writes.length).toBe(2)
  expect(state.writes[1].body.shareMemo).toHaveLength(500)
  await expect(page.locator('.record-share-note')).toHaveCount(0)
})

const friendA = { userId: 3, loginId: 'test-friend-a', displayName: '친구 하나', self: false }
const friendB = { userId: 4, loginId: 'test-friend-b', displayName: '친구 둘', self: false }
const ownedGroup = { id: 6, name: '친구 모임', ownerId: 2, members: [{ userId: 2, loginId: 'test-receiver', displayName: '받는 사람', self: true }, friendA, friendB] }

test('regular registration only saves and opt-in sharing still requires recipient confirmation', async ({ page }) => {
  const state = await fixture(page, 'LEDGER', true, { groups: [ownedGroup] })
  await page.getByRole('button', { name: '달력 가계부', exact: true }).click()
  const editor = page.locator('.household-entry-panel')
  await editor.locator('.amount-input input').fill('10000')
  await editor.getByPlaceholder('예: 식사, 택시, 급여').fill('일반 등록 테스트')
  await editor.getByRole('button', { name: '거래 등록', exact: true }).click()
  await expect(page.getByText('가계부 내역을 등록했습니다.', { exact: true })).toBeVisible()
  await expect(page.getByRole('dialog', { name: '기록 공유', exact: true })).toHaveCount(0)
  expect(state.writes.map((item) => item.path)).toEqual(['/api/entries'])
  await editor.getByRole('button', { name: '입력 설정', exact: true }).click()
  await editor.getByLabel('등록 후 공유 버튼 표시', { exact: true }).check()
  await editor.locator('.amount-input input').fill('12000')
  await editor.getByPlaceholder('예: 식사, 택시, 급여').fill('공유 전 확인 테스트')
  await editor.getByRole('button', { name: '등록 후 공유', exact: true }).click()
  const share = page.getByRole('dialog', { name: '기록 공유', exact: true })
  await expect(share.getByText('0 / 2명 선택', { exact: true })).toBeVisible()
  await expect(share.getByRole('button', { name: '0명에게 공유 요청', exact: true })).toBeDisabled()
  expect(state.writes.map((item) => item.path)).toEqual(['/api/entries', '/api/entries'])
})

test('registration sharing is opt-in and its display preference survives navigation and reload', async ({ page }) => {
  const state = await fixture(page, 'LEDGER', true)
  await page.getByRole('button', { name: '달력 가계부', exact: true }).click()
  const editor = page.locator('.household-entry-panel')
  await expect(editor.getByRole('button', { name: '거래 등록', exact: true })).toBeVisible()
  await expect(editor.getByRole('button', { name: '등록 후 공유', exact: true })).toHaveCount(0)
  await editor.getByRole('button', { name: '입력 설정', exact: true }).click()
  await editor.getByLabel('등록 후 공유 버튼 표시', { exact: true }).check()
  await expect(editor.getByRole('button', { name: '등록 후 공유', exact: true })).toBeVisible()
  await expect.poll(() => state.layouts['/api/account/preferences/layout-settings/household-calendar-view']?.payload.shareAfterSaveEnabled).toBe(true)
  await page.getByRole('button', { name: '대시보드', exact: true }).click()
  await page.getByRole('button', { name: '달력 가계부', exact: true }).click()
  await expect(editor.getByRole('button', { name: '등록 후 공유', exact: true })).toBeVisible()
  await page.reload()
  await page.getByRole('button', { name: '달력 가계부', exact: true }).click()
  await expect(editor.getByRole('button', { name: '등록 후 공유', exact: true })).toBeVisible()
  await editor.getByRole('button', { name: '입력 설정', exact: true }).click()
  await editor.getByLabel('등록 후 공유 버튼 표시', { exact: true }).uncheck()
  await expect(editor.getByRole('button', { name: '등록 후 공유', exact: true })).toHaveCount(0)
  expect(state.writes).toHaveLength(0)
})

test('group editor separates search, draft members and saved groups without sending shares', async ({ page }, testInfo) => {
  const state = await fixture(page, 'LEDGER', false, { groups: [], searchResults: [friendA, friendB] })
  const manager = page.locator('.record-share-groups')
  const editor = manager.locator('form.record-share-group-form')
  await expect(manager.getByRole('heading', { name: '저장된 공유 그룹' })).toBeVisible()
  await expect(editor.getByText('아직 저장되지 않음', { exact: true })).toBeVisible()
  await editor.getByLabel('새 그룹 이름', { exact: true }).fill('친구 모임')
  await editor.getByLabel('사용자 찾기', { exact: true }).fill('test')
  await editor.getByRole('button', { name: '검색', exact: true }).click()
  await editor.getByRole('button', { name: '친구 하나 (test-friend-a) 추가', exact: true }).click()
  await expect(editor.getByRole('button', { name: '친구 하나 (test-friend-a) 추가', exact: true })).toBeDisabled()
  await expect(editor.getByRole('button', { name: '친구 하나 (test-friend-a) 제거', exact: true })).toBeVisible()
  await expect(editor.getByText('1명 추가', { exact: true })).toBeVisible()
  await editor.getByRole('button', { name: '그룹 저장', exact: true }).click()
  const saved = manager.getByRole('article', { name: '친구 모임 공유 그룹' })
  await expect(saved.getByRole('heading', { name: '친구 모임', exact: true })).toBeVisible()
  await expect(saved.getByText('내가 관리', { exact: true })).toBeVisible()
  await expect(saved.getByText('받는 사람 (나)', { exact: true })).toBeVisible()
  await expect(saved.getByText('친구 하나', { exact: true })).toBeVisible()
  await expect(saved.getByText('친구 둘', { exact: true })).toHaveCount(0)
  expect(state.writes.map((item) => item.path)).toEqual(['/api/travel/share-groups'])
  expect(state.writes[0].body).toEqual({ name: '친구 모임', recipientLoginIds: ['test-friend-a'] })
  await saved.getByRole('button', { name: '멤버 수정', exact: true }).click()
  await expect(editor.getByRole('heading', { name: '그룹 멤버 수정', exact: true })).toBeVisible()
  await expect(editor.getByLabel('수정 중인 그룹 이름')).toHaveAttribute('readonly', '')
  await editor.getByRole('button', { name: '수정 취소', exact: true }).click()
  await expect(editor.getByLabel('새 그룹 이름', { exact: true })).toHaveValue('')
  await manager.screenshot({ path: testInfo.outputPath('sharing-group-sections.png') })
  expect(await manager.evaluate((element) => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
})

test('saved group shows participation roles and protects non-owner editing', async ({ page }) => {
  await fixture(page)
  const saved = page.getByRole('article', { name: '가족 공유 그룹' })
  await expect(saved.getByText('참여 중', { exact: true })).toBeVisible()
  await expect(saved.getByText('관리자', { exact: true })).toBeVisible()
  await expect(saved.getByText('받는 사람 (나)', { exact: true })).toBeVisible()
  await expect(saved.getByRole('button', { name: '멤버 수정', exact: true })).toHaveCount(0)
  await expect(page.locator('.record-share-card__status').getByText('승인 대기', { exact: true })).toBeVisible()
})

test('recipient selection starts empty, resets across groups and shares only the chosen member', async ({ page }) => {
  const state = await fixture(page, 'LEDGER', false, { groups: [group, ownedGroup] })
  await page.evaluate(async () => {
    const { createApp } = await import('/node_modules/.vite/deps/vue.js')
    const { default: Component } = await import('/src/components/RecordShareDialog.vue')
    const host = document.createElement('div'); document.body.appendChild(host)
    createApp(Component, { kind: 'LEDGER', source: { id: 100, title: '공유할 기록' }, currentUserId: 2 }).mount(host)
  })
  const dialog = page.getByRole('dialog')
  await expect(dialog.getByRole('button', { name: '0명에게 공유 요청', exact: true })).toBeDisabled()
  await dialog.getByRole('checkbox').check()
  await dialog.getByLabel('공유 그룹', { exact: true }).selectOption('6')
  await expect(dialog.getByText('0 / 2명 선택', { exact: true })).toBeVisible()
  await dialog.getByRole('button', { name: '전체 선택', exact: true }).click()
  await expect(dialog.getByRole('checkbox', { checked: true })).toHaveCount(2)
  await dialog.getByRole('button', { name: '선택 해제', exact: true }).click()
  await expect(dialog.getByRole('checkbox', { checked: true })).toHaveCount(0)
  await dialog.getByRole('checkbox', { name: '친구 둘 (test-friend-b)에게 공유', exact: true }).check()
  await dialog.getByRole('button', { name: '1명에게 공유 요청', exact: true }).click()
  await expect.poll(() => state.writes.length).toBe(1)
  expect(state.writes[0].body).toEqual({ kind: 'LEDGER', sourceId: 100, groupId: 6, recipientIds: [4], shareMemo: null })
})

test('transaction sheet keeps edit, share and delete reachable beside long memos', async ({ page }, testInfo) => {
  const entries = Array.from({ length: 4 }, (_, index) => ({ ...snapshot, id: 100 + index, categoryGroupId: 101, categoryDetailId: 102, paymentMethodId: 103, entryTime: '13:07', memo: '긴 메모와 사용처 설명 '.repeat(20) }))
  const state = await fixture(page, 'LEDGER', true, { entries, groups: [ownedGroup] })
  await page.getByRole('button', { name: '달력 가계부', exact: true }).click()
  await page.getByLabel('조회 기준일', { exact: true }).fill('2026-10-03')
  await page.locator('.calendar__day--selected').press('Enter')
  const sheet = page.getByRole('dialog', { name: '거래 시트', exact: true })
  const actions = sheet.locator('.sheet-table__actions-inner').first()
  for (const theme of ['default', 'toss']) {
    await page.evaluate((value) => { document.documentElement.dataset.theme = value }, theme)
    for (const name of ['수정', '공유', '삭제']) {
      const button = actions.getByRole('button', { name, exact: true })
      await button.scrollIntoViewIfNeeded()
      await expect(button).toBeVisible()
      expect(await button.evaluate((element) => {
        const box = element.getBoundingClientRect()
        const wrap = element.closest('.household-sheet-table-wrap').getBoundingClientRect()
        const hit = document.elementFromPoint(box.x + box.width / 2, box.y + box.height / 2)
        return box.left >= wrap.left - 1 && box.right <= wrap.right + 1 && (element === hit || element.contains(hit))
      })).toBe(true)
    }
    expect(await sheet.evaluate((element) => element.getBoundingClientRect().height <= window.innerHeight)).toBe(true)
    await sheet.screenshot({ path: testInfo.outputPath(`transaction-actions-${theme}.png`) })
  }
  await actions.getByRole('button', { name: '공유', exact: true }).click()
  await expect(sheet).not.toBeVisible()
  const share = page.getByRole('dialog', { name: '기록 공유', exact: true })
  await expect(share.getByText('0 / 2명 선택', { exact: true })).toBeVisible()
  await expect(share.getByRole('button', { name: '0명에게 공유 요청', exact: true })).toBeDisabled()
  expect(state.writes).toHaveLength(0)
})
