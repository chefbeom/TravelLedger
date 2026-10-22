import { expect, test } from '@playwright/test'
import { calendarSettings } from './fixtures/calendarPersonalization.js'

const snapshot = { id: 100, entryDate: '2026-10-03', title: '공유한 저녁 식사', memo: '가족 카드 사용', amount: 10000, entryType: 'EXPENSE', categoryGroupId: 10, categoryGroupName: '식비', categoryDetailId: 11, categoryDetailName: '외식', paymentMethodId: 12, paymentMethodName: '가족 카드', travelPlanId: 999, travelRecordId: 888 }
const group = { id: 5, name: '가족', ownerId: 1, members: [{ userId: 1, loginId: 'test-sender', displayName: '보낸 사람', self: false }, { userId: 2, loginId: 'test-receiver', displayName: '받는 사람', self: true }] }

async function fixture(page, kind = 'LEDGER', root = false, options = {}) {
  const state = { status: 'PENDING', writes: [], reads: [], groups: options.groups ?? [group], layouts: {}, personalization: calendarSettings(), entries: options.entries ?? [], shareMemo: options.shareMemo ?? null,
    sharedSnapshot:{...snapshot}, categories:options.categories ?? [{ id: 101, name: '식비', entryType: 'EXPENSE', active: true, details: [{ id: 102, name: '외식', active: true }] }],
    payments:options.payments ?? [{ id: 103, name: '가족 카드', active: true }] }
  await page.route(/^https?:\/\/(?!127\.0\.0\.1|localhost)/, (route) => route.abort())
  await page.route('**/api/**', async (route) => {
    const req = route.request()
    const url = new URL(req.url())
    const path = url.pathname
    if (path === '/api/account/preferences/household-personalization') {
      if (req.method() === 'PUT') {
        state.personalization = { ...req.postDataJSON(), revision: 'saved-revision' }
        state.layouts['/api/account/preferences/layout-settings/household-calendar-view'] = { version: 3, payload: state.personalization.preferences }
        state.layouts['/api/account/preferences/layout-settings/household-calendar'] = { version: 3, payload: state.personalization.layout }
      }
      return route.fulfill({ json: state.personalization })
    }
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
        if (req.postDataJSON().ledgerOverride) {
          const edited=req.postDataJSON().ledgerOverride, category=state.categories.find(item=>item.id===edited.categoryGroupId)
          state.sharedSnapshot={...snapshot,...edited,categoryGroupName:category?.name,categoryDetailName:category?.details?.find(item=>item.id===edited.categoryDetailId)?.name,
            paymentMethodName:edited.entryType==='INCOME'?'-':state.payments.find(item=>item.id===edited.paymentMethodId)?.name}
        }
      }
    } else state.reads.push(path)
    const item = { id: 9, groupId: 5, groupName: '가족', kind, sourceId: 100, senderId: 1, senderName: '보낸 사람', recipientId: 2, recipientName: '받는 사람', title: kind === 'LEDGER' ? state.sharedSnapshot.title : '함께한 여행', ledger: kind === 'LEDGER' ? state.sharedSnapshot : null, status: state.status, createdAt: '2026-10-03T09:00:00', importedLedgerEntryId: state.status === 'ACCEPTED' && kind === 'LEDGER' ? 300 : null, shareMemo: state.shareMemo }
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
    else if (path.startsWith('/api/categories')) body = state.categories
    else if (path.startsWith('/api/payment-methods')) body = state.payments
    else if (path === '/api/entries' && req.method() === 'POST') {
      body = { ...req.postDataJSON(), id: 200 + state.entries.length, categoryGroupName: '식비', categoryDetailName: '외식', paymentMethodName: '가족 카드' }
      state.entries = [...state.entries, body]
    }
    else if (path === '/api/entries') body = state.entries
    else if (path === '/api/travel/plans') body = []
    else if (path === '/api/entries/date-range') body = { minDate: '2026-10-01', maxDate: '2026-10-03' }
    else if (path === '/api/dashboard') body = { anchorDate: '2026-10-03', quickStats: [], calendar: [], expenseBreakdown: [], paymentBreakdown: [], monthlyComparison: [], recentEntries: [] }
    else if (path === '/api/account/preferences/household-aggregates') body = { widgets: state.personalization.widgets }
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

async function openShareDialog(page, source = snapshot, kind = 'LEDGER') {
  await page.evaluate(async ({source,kind}) => {
    const { createApp } = await import('/node_modules/.vite/deps/vue.js')
    const { default: Component } = await import('/src/components/RecordShareDialog.vue')
    const host = document.createElement('div'); document.body.appendChild(host)
    window.senderShareSource=source
    const app=createApp(Component, { kind, source, onClose:()=>app.unmount() }); app.mount(host)
  }, {source,kind})
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

async function setCalendarSharingPreference(page, enabled) {
  await page.getByRole('button', { name: '개인화 설정', exact: true }).click()
  const settings = page.getByRole('dialog', { name: '개인화 설정', exact: true })
  await settings.getByRole('tab', { name: /거래 입력/ }).click()
  await settings.getByRole('switch', { name: /등록 후 공유 버튼 표시/ }).setChecked(enabled)
  await settings.getByRole('button', { name: '변경사항 저장', exact: true }).click()
  await expect(settings).toHaveCount(0)
}

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
  await setCalendarSharingPreference(page, true)
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
  await setCalendarSharingPreference(page, true)
  await expect(editor.getByRole('button', { name: '등록 후 공유', exact: true })).toBeVisible()
  await expect.poll(() => state.layouts['/api/account/preferences/layout-settings/household-calendar-view']?.payload.shareAfterSaveEnabled).toBe(true)
  await page.getByRole('button', { name: '대시보드', exact: true }).click()
  await page.getByRole('button', { name: '달력 가계부', exact: true }).click()
  await expect(editor.getByRole('button', { name: '등록 후 공유', exact: true })).toBeVisible()
  await page.reload()
  await page.getByRole('button', { name: '달력 가계부', exact: true }).click()
  await expect(editor.getByRole('button', { name: '등록 후 공유', exact: true })).toBeVisible()
  await setCalendarSharingPreference(page, false)
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

const senderCategories=[{id:10,name:'식비',entryType:'EXPENSE',active:true,details:[{id:11,name:'외식',active:true}]},
  {id:20,name:'수입',entryType:'INCOME',active:true,details:[{id:21,name:'정산',active:true}]}]
const senderPayments=[{id:12,name:'가족 카드',active:true},{id:13,name:'현금',active:true}]

test('sender can edit a share without editing their transaction and recipient confirms the edited copy', async ({page},testInfo)=>{
  const state=await fixture(page,'LEDGER',false,{categories:senderCategories,payments:senderPayments,entries:[{...snapshot}]})
  const dialog=await openShareDialog(page)
  await expect(dialog.getByRole('radio',{name:'원본 그대로 공유',exact:true})).toBeChecked()
  await dialog.getByRole('radio',{name:'내용을 수정해서 공유',exact:true}).check()
  const title=dialog.getByLabel('공유 제목',{exact:true})
  await expect(title).toHaveValue(snapshot.title)
  await expect(dialog.getByRole('combobox',{name:'공유 대분류',exact:true})).toBeEnabled()
  await title.fill('공유용 절반 식사')
  await dialog.getByLabel('공유 금액 (원)',{exact:true}).fill('5000')
  await dialog.getByLabel('공유 날짜',{exact:true}).fill('2026-10-14')
  await dialog.getByLabel('공유 시간',{exact:true}).fill('18:24:00')
  await dialog.getByLabel('공유 거래 메모',{exact:true}).fill('수정한 거래 메모')
  await dialog.getByRole('combobox',{name:'공유 결제수단',exact:true}).selectOption('13')
  await dialog.getByLabel('공유 메모 (선택)',{exact:true}).fill('함께 먹은 금액의 절반이에요.')
  await dialog.getByRole('checkbox').check()
  for (const theme of ['default', 'toss']) {
    await page.evaluate(value=>{document.documentElement.dataset.theme=value},theme)
    await dialog.evaluate(node=>{node.scrollTop=0})
    await dialog.screenshot({path:testInfo.outputPath(`edited-share-fields-${theme}.png`)})
    await dialog.getByRole('button',{name:'1명에게 공유 요청',exact:true}).scrollIntoViewIfNeeded()
    await expect(dialog.getByRole('button',{name:'1명에게 공유 요청',exact:true})).toBeInViewport()
    await dialog.screenshot({path:testInfo.outputPath(`edited-share-submit-${theme}.png`)})
  }
  const bounds=await dialog.boundingBox(), viewport=page.viewportSize()
  expect(bounds.x).toBeGreaterThanOrEqual(0); expect(bounds.x+bounds.width).toBeLessThanOrEqual(viewport.width+1)
  expect(await dialog.evaluate(node=>node.scrollWidth<=node.clientWidth+1)).toBe(true)
  await dialog.getByRole('button',{name:'1명에게 공유 요청',exact:true}).click()
  await expect.poll(()=>state.writes.length).toBe(1)
  const sent=state.writes[0]
  expect(sent.path).toBe('/api/record-shares')
  expect(sent.body.ledgerOverride).toMatchObject({title:'공유용 절반 식사',amount:5000,entryDate:'2026-10-14',entryTime:'18:24:00',memo:'수정한 거래 메모',paymentMethodId:13,categoryGroupId:10,travelPlanId:null,travelRecordId:null})
  expect(state.entries).toEqual([snapshot])
  expect(await page.evaluate(()=>window.senderShareSource)).toEqual(snapshot)
  await dialog.getByRole('button',{name:'닫기',exact:true}).click()
  await page.getByRole('button',{name:'새로고침',exact:true}).click()
  await expect(page.locator('.record-share-card')).toContainText('공유용 절반 식사')
  state.categories=[{id:101,name:'식비',entryType:'EXPENSE',active:true,details:[{id:102,name:'외식',active:true}]}]
  state.payments=[{id:103,name:'현금',active:true}]
  await page.getByRole('button',{name:'확인 후 내 가계부에 기록',exact:true}).click()
  const approval=page.getByRole('dialog',{name:'확인 후 내 가계부에 기록',exact:true})
  await expect(approval.getByLabel('제목',{exact:true})).toHaveValue('공유용 절반 식사')
  await expect(approval.getByLabel('금액 (원)',{exact:true})).toHaveValue('5000')
  await expect(approval.getByLabel('메모',{exact:true})).toHaveValue('수정한 거래 메모')
  await approval.getByRole('button',{name:'내 가계부에 등록',exact:true}).click()
  await expect.poll(()=>state.writes.length).toBe(2)
  expect(state.writes[1].body).toMatchObject({title:'공유용 절반 식사',amount:5000,categoryGroupId:101,paymentMethodId:103,travelPlanId:null})
})

test('switching back to original omits edited content and reset restores the source values', async ({page})=>{
  const state=await fixture(page,'LEDGER',false,{categories:senderCategories,payments:senderPayments})
  const dialog=await openShareDialog(page)
  await dialog.getByRole('radio',{name:'내용을 수정해서 공유',exact:true}).check()
  await dialog.getByLabel('공유 제목',{exact:true}).fill('전송하지 않을 제목')
  await dialog.getByRole('button',{name:'원본 내용으로 되돌리기',exact:true}).click()
  await expect(dialog.getByLabel('공유 제목',{exact:true})).toHaveValue(snapshot.title)
  await dialog.getByLabel('공유 제목',{exact:true}).fill('다시 수정한 제목')
  await dialog.getByRole('radio',{name:'원본 그대로 공유',exact:true}).check()
  await dialog.getByRole('checkbox').check()
  await dialog.getByRole('button',{name:'1명에게 공유 요청',exact:true}).click()
  await expect.poll(()=>state.writes.length).toBe(1)
  expect(state.writes[0].body).not.toHaveProperty('ledgerOverride')
})

test('edited foreign income shares preserve calculation and a failed draft can be retried', async ({page})=>{
  const state=await fixture(page,'LEDGER',false,{categories:senderCategories,payments:senderPayments,failShareOnce:true})
  const dialog=await openShareDialog(page)
  await dialog.getByRole('radio',{name:'내용을 수정해서 공유',exact:true}).check()
  await expect(dialog.getByRole('combobox',{name:'공유 대분류',exact:true})).toBeEnabled()
  await dialog.getByRole('combobox',{name:'공유 수입/지출',exact:true}).selectOption('INCOME')
  await dialog.getByRole('combobox',{name:'공유 통화',exact:true}).selectOption('USD')
  await dialog.getByLabel('공유 외화 금액',{exact:true}).fill('10')
  await dialog.getByLabel('공유 적용 환율',{exact:true}).fill('1300')
  await dialog.getByLabel('공유 제목',{exact:true}).fill('공유용 정산')
  await dialog.getByRole('checkbox').check()
  await dialog.getByRole('button',{name:'1명에게 공유 요청',exact:true}).click()
  await expect(dialog.getByRole('alert')).toContainText('일시적으로')
  await expect(dialog.getByLabel('공유 제목',{exact:true})).toHaveValue('공유용 정산')
  await expect(dialog.getByLabel('공유 외화 금액',{exact:true})).toHaveValue('10')
  await dialog.getByRole('button',{name:'1명에게 공유 요청',exact:true}).click()
  await expect.poll(()=>state.writes.length).toBe(2)
  expect(state.writes[1].body.ledgerOverride).toMatchObject({entryType:'INCOME',categoryGroupId:20,paymentMethodId:null,foreignCurrencyCode:'USD',foreignAmount:10,exchangeRateToKrw:1300,amount:13000})
})

test('travel sharing stays reference-only without ledger editing options',async ({page})=>{
  const state=await fixture(page,'TRAVEL')
  const dialog=await openShareDialog(page,{id:100,name:'함께한 여행'},'TRAVEL')
  await expect(dialog.getByRole('radiogroup',{name:'거래 공유 방식',exact:true})).toHaveCount(0)
  await dialog.getByRole('checkbox').check()
  await dialog.getByRole('button',{name:'1명에게 공유 요청',exact:true}).click()
  await expect.poll(()=>state.writes.length).toBe(1)
  expect(state.writes[0].body).not.toHaveProperty('ledgerOverride')
})
