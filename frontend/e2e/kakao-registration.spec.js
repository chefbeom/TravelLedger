import { expect, test } from '@playwright/test'

async function mockKakao(page, overrides = {}) {
  const state = { configured: true, publicRegistrationEnabled: true, pending: true, user: null, failComplete: false,
    complete: [], cancelled: 0, starts: 0, callbacks: 0, linked: false, linkPending: false, linkPins: [], emailRequests: [], registrations: [], resends: [], verifiedTokens: [], verificationRequests: [], withdrawalPins: [], users: [], promotions: [], errors: [], email: 'verified@example.test', nickname: '카카오 닉네임', ...overrides }
  page.on('pageerror', error => state.errors.push(error.message))
  await page.route(/^https?:\/\/(?!127\.0\.0\.1|localhost)/, route => route.abort())
  await page.route('**/api/**', async route => {
    const request = route.request(), path = new URL(request.url()).pathname
    let json = []
    if (path === '/api/auth/me') {
      if (!state.user) return route.fulfill({ status: 401, json: { message: '로그인이 필요합니다.' } })
      json = state.user
    } else if (path === '/api/auth/csrf') json = { token: 'mock-csrf' }
    else if (path === '/api/auth/registration-options' || path === '/api/admin/registration-policy') {
      if (request.method() === 'PUT') state.publicRegistrationEnabled = request.postDataJSON().publicRegistrationEnabled
      json = { publicRegistrationEnabled: state.publicRegistrationEnabled, socialLoginProviders: state.configured ? ['KAKAO'] : [] }
    } else if (path === '/api/auth/profile/email-verification') {
      state.emailRequests.push(request.postDataJSON())
      json = { verificationRequired: true, message: '인증 메일을 보냈습니다.' }
    } else if (path === '/api/auth/register') {
      state.registrations.push(request.postDataJSON())
      json = { verificationRequired: true, message: '인증 메일을 보냈습니다.' }
    } else if (path === '/api/auth/email-verification/resend') {
      state.resends.push(request.postDataJSON())
      json = { accepted: true, message: '요청을 처리했습니다.' }
    } else if (path === '/api/auth/email-verification/verify-profile' || path === '/api/auth/email-verification/verify') {
      const payload = request.postDataJSON()
      state.verifiedTokens.push(payload.token)
      state.verificationRequests.push({ path, ...payload })
      json = { verified: true, message: '이메일 인증이 완료되었습니다.' }
    } else if (path === '/api/privacy/withdrawal/preview') {
      state.withdrawalPins.push(request.postDataJSON().secondaryPin)
      json = state.withdrawalPreview ?? { withdrawalAvailable: false, kakaoLinked: false, kakaoUnlinkConfigured: false,
        requiredFamilyTransfers: [{ categoryId: 1, categoryName: '가족 추억', eligibleMembers: [{ userId: 2, displayName: '수진', loginId: 'member' }] }],
        requiredShareGroupTransfers: [{ groupId: 2, groupName: '가족 공유', containsOthersShares: true,
          members: [{ userId: 2, displayName: '수진', eligible: true }, { userId: 3, displayName: '민수', eligible: false, unavailableReason: '같은 이름의 그룹이 있음' }] }],
        requiredTravelPlanTransfers: [
          { planId: 3, planName: '공개 여행', publiclyShared: true, members: [] },
          { planId: 4, planName: '함께한 여행', publiclyShared: false,
            members: [{ userId: 2, displayName: '수진', eligible: true }] },
        ], foreignTravelMedia: { planCount: 2, mediaCount: 3 } }
    } else if (path === '/api/auth/oauth/kakao/link') {
      json = { configured: state.configured, linked: state.linked }
    } else if (path === '/api/auth/oauth/kakao/link/start') {
      state.linkPins.push(request.postDataJSON().secondaryPin)
      state.linkPending = true
      return route.fulfill({ status: 204 })
    } else if (path === '/api/auth/oauth/kakao/link/authorize') {
      return route.fulfill({ contentType: 'text/html', body: '<!doctype html><script>location.replace("/api/auth/oauth/kakao/callback?code=link-code&state=link-state")</script>' })
    } else if (path === '/api/auth/oauth/kakao/start') {
      state.starts++
      // A tiny mock provider page starts a new navigation, so Playwright also
      // intercepts the callback instead of bypassing routes in a redirect chain.
      return route.fulfill({ contentType: 'text/html', body: '<!doctype html><title>Mock Kakao</title><script>location.replace("/api/auth/oauth/kakao/callback?code=mock-code&state=mock-state")</script>' })
    } else if (path === '/api/auth/oauth/kakao/callback') {
      state.callbacks++
      if (state.linkPending) {
        state.linkPending = false
        state.linked = true
        return route.fulfill({ status: 302, headers: { location: '/#profile?kakao-link=connected' } })
      }
      state.pending = true
      return route.fulfill({ status: 302, headers: { location: '/#oauth/kakao/complete' } })
    } else if (path === '/api/auth/oauth/kakao/registration') {
      if (!state.publicRegistrationEnabled) return route.fulfill({ status: 403, json: { message: '접근 권한이 없습니다.' } })
      if (!state.pending) return route.fulfill({ status: 400, json: { message: '카카오 가입 인증이 만료되었거나 취소되었습니다. 카카오로 다시 인증해 주세요.' } })
      json = { email: state.email, displayName: state.nickname, expiresAt: new Date(Date.now() + 600_000).toISOString() }
    } else if (path === '/api/auth/oauth/kakao/complete') {
      expect(request.headers()['x-xsrf-token']).toBe('mock-csrf')
      const payload = request.postDataJSON()
      state.complete.push(payload)
      if (!state.publicRegistrationEnabled) return route.fulfill({ status: 403, json: { message: '접근 권한이 없습니다.' } })
      if (state.failComplete) return route.fulfill({ status: 400, json: { message: '표시 이름을 확인해 주세요.' } })
      state.pending = false
      state.user = { id: 72, loginId: 'kakao_internal', displayName: payload.displayName, active: true, admin: false, localLoginEnabled: false }
      json = state.user
    } else if (path === '/api/auth/oauth/kakao/cancel') {
      expect(request.headers()['x-xsrf-token']).toBe('mock-csrf')
      state.cancelled++
      state.pending = false
      return route.fulfill({ status: 204 })
    } else if (path === '/api/travel/public/login-map-preview') json = { enabled: false }
    else if (path === '/api/admin/access/status') json = { verified: true }
    else if (path === '/api/admin/dashboard') json = { summary: { adminUsers: 1 }, users: state.users, blockedIps: [], recentInvites: [] }
    else if (/^\/api\/admin\/users\/\d+\/administrator$/.test(path)) {
      expect(request.headers()['x-xsrf-token']).toBe('mock-csrf')
      const payload = request.postDataJSON(), id = Number(path.split('/')[4])
      state.promotions.push({ id, ...payload })
      if (payload.secondaryPin !== '12345678') return route.fulfill({ status: 401, json: { message: '2차 비밀번호가 올바르지 않습니다.' } })
      state.users = state.users.map(user => user.id === id ? { ...user, admin: true, role: 'ADMIN' } : user)
      json = state.users.find(user => user.id === id)
    }
    else if (path === '/api/admin/login-audit-logs') json = { content: [], page: 0, totalPages: 0 }
    else if (path === '/api/admin/login-map-preview') json = { enabled: false }
    else if (path === '/api/dashboard') json = { quickStats: [], calendar: [], recentEntries: [], expenseBreakdown: [], paymentBreakdown: [], monthlyComparison: [] }
    else if (path.startsWith('/api/account/preferences/layout-settings/')) json = { payload: null }
    else if (path === '/api/record-shares/counts') json = { ledger: 0, travel: 0 }
    return route.fulfill({ json })
  })
  return state
}

async function fillAccount(page) {
  await page.getByRole('textbox', { name: '표시 이름', exact: true }).fill('카카오 사용자')
}

test('Kakao entry follows the callback, shows verified identity and keeps a failed signup editable for retry', async ({ page }) => {
  const state = await mockKakao(page, { failComplete: true })
  await page.goto('/#launcher')
  const start = page.getByRole('button', { name: '카카오로 계속', exact: true })
  await expect(start).toHaveCSS('background-color', 'rgb(254, 229, 0)')
  await start.click()
  await expect(page).toHaveURL(/#oauth\/kakao\/complete$/)
  await expect(page.getByLabel('인증된 카카오 정보')).toContainText(state.email)
  await expect(page.getByRole('textbox', { name: '표시 이름', exact: true })).toHaveValue(state.nickname)
  await fillAccount(page)
  const submit = page.getByRole('button', { name: '카카오 계정으로 가입 완료', exact: true })
  await submit.click()
  await expect(page.getByRole('alert')).toContainText('숫자 8자리')
  expect(state.complete).toEqual([])
  for (const digit of '12345678') await page.getByRole('button', { name: `PIN digit ${digit}`, exact: true }).click()
  await submit.click()
  await expect(page.locator('.auth-feedback.feedback--error')).toContainText('표시 이름을 확인')
  await expect(page.getByRole('textbox', { name: '표시 이름', exact: true })).toHaveValue('카카오 사용자')
  await expect(page.getByRole('textbox', { name: '로그인 ID', exact: true })).toHaveCount(0)
  await expect(page.getByLabel('비밀번호', { exact: true })).toHaveCount(0)
  state.failComplete = false
  await page.getByRole('textbox', { name: '표시 이름', exact: true }).fill('수정한 이름')
  await submit.click()
  await expect(page).toHaveURL(/#launcher$/)
  await expect(page.locator('.feedback--success')).toContainText('카카오 계정으로 가입하고 로그인했습니다.')
  expect(state.starts).toBe(1)
  expect(state.callbacks).toBe(1)
  expect(state.complete).toHaveLength(2)
  expect(state.complete[1]).toEqual({ displayName: '수정한 이름', secondaryPin: '12345678', rememberDevice: true })
  expect(state.errors).toEqual([])
})

test('existing user links Kakao with a PIN and keeps local login available', async ({ page }) => {
  const state = await mockKakao(page, { user: { id: 41, loginId: 'existing', displayName: '기존 사용자', active: true, admin: false, localLoginEnabled: true } })
  await page.goto('/#profile')
  await page.getByRole('button', { name: '카카오 계정 연결', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '카카오 계정 연결' })
  await expect(dialog).toContainText('기존 아이디·비밀번호 로그인도 계속')
  await dialog.getByLabel('2차 비밀번호').fill('12345678')
  await dialog.getByRole('button', { name: '카카오 인증 계속' }).click()
  await expect(page.getByText('카카오 계정이 연결되었습니다.', { exact: false })).toBeVisible()
  await expect(page.getByText('카카오 계정 연결됨', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '비밀번호 변경' })).toBeVisible()
  expect(state.linkPins).toEqual(['12345678'])
  expect(state.errors).toEqual([])
})

test('existing user requests email verification and can confirm the link', async ({ page }) => {
  const state = await mockKakao(page, { user: { id: 42, loginId: 'existing', displayName: '기존 사용자', active: true, admin: false, localLoginEnabled: true } })
  await page.goto('/#profile')
  await page.getByRole('button', { name: '이메일 등록·변경' }).click()
  const dialog = page.getByRole('dialog', { name: '이메일 등록·변경' })
  await dialog.getByLabel('이메일 주소').fill('verified@example.test')
  await dialog.getByLabel('2차 비밀번호').fill('12345678')
  await dialog.getByRole('button', { name: '인증 메일 보내기' }).click()
  await expect(page.getByText('인증 메일을 보냈습니다.', { exact: true })).toBeVisible()
  expect(state.emailRequests).toEqual([{ email: 'verified@example.test', secondaryPin: '12345678' }])
  await page.goto('/#verify-profile-email/test-token')
  await expect(page.getByText('직접 요청한 이메일 인증이라면 아래 버튼을 눌러 완료해 주세요.')).toBeVisible()
  expect(state.verifiedTokens).toEqual([])
  await page.getByRole('button', { name: '이메일 인증하기' }).click()
  await expect(page.getByText('이메일 인증이 완료되었습니다.', { exact: true })).toBeVisible()
  expect(state.verifiedTokens).toEqual(['test-token'])
  expect(state.errors).toEqual([])
})

test('signup email links require a deliberate confirmation instead of verifying on page load', async ({ page }) => {
  const state = await mockKakao(page)
  await page.goto('/#verify-email/signup-token')
  await expect(page.getByRole('button', { name: '이메일 인증하기' })).toBeVisible()
  await expect(page.getByLabel('가입 시 설정한 2차 비밀번호')).toHaveAttribute('type', 'password')
  expect(state.verifiedTokens).toEqual([])
  await page.getByLabel('가입 시 설정한 2차 비밀번호').fill('12345678')
  await page.getByRole('button', { name: '이메일 인증하기' }).click()
  await expect(page.getByText('이메일 인증이 완료되었습니다.', { exact: true })).toBeVisible()
  expect(state.verifiedTokens).toEqual(['signup-token'])
  expect(state.verificationRequests).toEqual([{ path: '/api/auth/email-verification/verify', token: 'signup-token', secondaryPin: '12345678' }])
})

test('an existing pending signup can request email again after reload even if signup is closed', async ({ page }) => {
  const state = await mockKakao(page, { publicRegistrationEnabled: false })
  await page.goto('/#signup')
  await page.reload()
  await page.getByText('이미 가입을 신청하셨나요? 인증 메일 다시 받기').click()
  await page.getByLabel('가입에 사용한 이메일').fill('pending@example.test')
  await page.getByRole('button', { name: '인증 메일 다시 보내기' }).click()
  await expect(page.locator('.auth-feedback.feedback--success')).toContainText('재전송 요청을 처리했습니다')
  expect(state.resends).toEqual([{ email: 'pending@example.test' }])
  expect(state.errors).toEqual([])
})

test('a newly requested signup prefills the email resend form', async ({ page }) => {
  const state = await mockKakao(page)
  await page.goto('/#signup')
  await page.getByPlaceholder('로그인 ID').fill('new-account')
  await page.getByPlaceholder('표시 이름').fill('새 사용자')
  await page.getByPlaceholder('이메일').fill('new@example.test')
  await page.getByPlaceholder('비밀번호(8자 이상)').fill('test-password')
  await page.getByPlaceholder('비밀번호 확인').fill('test-password')
  for (const digit of '12345678') await page.getByRole('button', { name: `PIN digit ${digit}`, exact: true }).click()
  await page.getByRole('button', { name: '인증 메일 보내기', exact: true }).click()
  await expect(page.getByLabel('가입에 사용한 이메일')).toHaveValue('new@example.test')
  await page.getByRole('button', { name: '인증 메일 다시 보내기' }).click()
  await expect(page.locator('.auth-feedback.feedback--success')).toContainText('재전송 요청을 처리했습니다')
  expect(state.registrations).toHaveLength(1)
  expect(state.resends).toEqual([{ email: 'new@example.test' }])
  expect(state.errors).toEqual([])
})

test('withdrawal preview requires a fresh PIN and clearly does not perform deletion', async ({ page }) => {
  const state = await mockKakao(page, { user: { id: 42, loginId: 'existing', displayName: '기존 사용자', active: true, admin: false, localLoginEnabled: true } })
  await page.goto('/#profile')
  await page.getByRole('tab', { name: '개인정보 관리' }).click()
  await page.getByRole('button', { name: '탈퇴 내용 확인' }).click()
  const dialog = page.getByRole('dialog', { name: '회원 탈퇴 사전 확인' })
  await expect(dialog).toContainText('현재는 탈퇴를 신청하거나 데이터를 삭제할 수 없습니다')
  await expect(dialog).toContainText('자동 폐기와 복원 시 탈퇴 반영은 아직 적용되지 않았습니다')
  await expect(dialog).toContainText('다른 사람의 여행에 올린 사진·영수증은 그 여행에 보존하고')
  await expect(dialog).toContainText('업로더 표시는 현재 여행 소유자로 변경할 예정입니다')
  await expect(dialog.getByRole('link', { name: '개인정보 보호법 제21조' })).toHaveAttribute('href', /law\.go\.kr/)
  await dialog.getByLabel('2차 비밀번호').fill('12345678')
  await dialog.getByRole('button', { name: '공유 항목 확인' }).click()
  await expect(dialog).toContainText('가족 추억 — 수진')
  await expect(dialog).toContainText('가족 공유 — 수진, 민수 (같은 이름의 그룹이 있음)')
  await expect(dialog).toContainText('공개 여행 (공개 중) — 먼저 이전받을 회원에게 이 여행을 공유해 주세요.')
  await expect(dialog).toContainText('함께한 여행 — 수진')
  await expect(dialog).toContainText('다른 회원의 여행 2개에 내가 올린 사진·영수증 3개가 있습니다.')
  await expect(dialog).toContainText('사전 확인과 대상 선택만으로는 소유권이 변경되거나 계정이 탈퇴되지 않습니다')
  await expect(dialog.getByRole('button', { name: '탈퇴하기' })).toHaveCount(0)
  expect(state.withdrawalPins).toEqual(['12345678'])
  expect(state.errors).toEqual([])
})

test('withdrawal preview tells administrators how to appoint another administrator', async ({ page }) => {
  const state = await mockKakao(page, { user: { id: 1, loginId: 'admin', displayName: '관리자', active: true, admin: true, localLoginEnabled: true } })
  await page.goto('/#profile')
  await page.getByRole('tab', { name: '개인정보 관리' }).click()
  await page.getByRole('button', { name: '탈퇴 내용 확인' }).click()
  const dialog = page.getByRole('dialog', { name: '회원 탈퇴 사전 확인' })
  await expect(dialog).toContainText('다른 활성 관리자가 있어야 합니다')
  await expect(dialog).toContainText('접근 및 사용자 관리에서 먼저 다른 사용자를 관리자로 지정')
  await expect(dialog).toContainText('현재는 탈퇴를 신청하거나 데이터를 삭제할 수 없습니다')
  expect(state.withdrawalPins).toEqual([])
  expect(state.errors).toEqual([])
})

test('withdrawal preview does not treat missing server fields as empty data', async ({ page }) => {
  const state = await mockKakao(page, {
    user: { id: 42, loginId: 'existing', displayName: '기존 사용자', active: true, admin: false, localLoginEnabled: true },
    withdrawalPreview: { withdrawalAvailable: false },
  })
  await page.goto('/#profile')
  await page.getByRole('tab', { name: '개인정보 관리' }).click()
  await page.getByRole('button', { name: '탈퇴 내용 확인' }).click()
  const dialog = page.getByRole('dialog', { name: '회원 탈퇴 사전 확인' })
  await dialog.getByLabel('2차 비밀번호').fill('12345678')
  await dialog.getByRole('button', { name: '공유 항목 확인' }).click()
  await expect(dialog).toContainText('가족 앨범 정보를 확인할 수 없습니다')
  await expect(dialog).toContainText('공유 그룹 정보를 확인할 수 없습니다')
  await expect(dialog).toContainText('공유 여행 정보를 확인할 수 없습니다')
  await expect(dialog).toContainText('다른 회원의 여행에 남을 자료 수를 확인할 수 없습니다')
  await expect(dialog).not.toContainText('이전이 필요한 가족 앨범이 없습니다')
  expect(state.errors).toEqual([])
})

test('withdrawal preview can display a verified empty foreign-media count', async ({ page }) => {
  const state = await mockKakao(page, {
    user: { id: 42, loginId: 'existing', displayName: '기존 사용자', active: true, admin: false, localLoginEnabled: true },
    withdrawalPreview: { withdrawalAvailable: false, requiredFamilyTransfers: [], requiredShareGroupTransfers: [],
      requiredTravelPlanTransfers: [], foreignTravelMedia: { planCount: 0, mediaCount: 0 } },
  })
  await page.goto('/#profile')
  await page.getByRole('tab', { name: '개인정보 관리' }).click()
  await page.getByRole('button', { name: '탈퇴 내용 확인' }).click()
  const dialog = page.getByRole('dialog', { name: '회원 탈퇴 사전 확인' })
  await dialog.getByLabel('2차 비밀번호').fill('12345678')
  await dialog.getByRole('button', { name: '공유 항목 확인' }).click()
  await expect(dialog).toContainText('다른 회원의 여행에 보존할 내 사진·영수증이 없습니다')
  await expect(dialog).not.toContainText('다른 회원의 여행에 남을 자료 수를 확인할 수 없습니다')
  expect(state.errors).toEqual([])
})

test('expired pending session has no signup form and allows a fresh Kakao authentication', async ({ page }) => {
  const state = await mockKakao(page, { pending: false })
  await page.goto('/#oauth/kakao/complete')
  await expect(page.getByRole('alert')).toContainText('만료되었거나 취소')
  await expect(page.getByRole('textbox', { name: '로그인 ID', exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: '카카오로 다시 인증', exact: true }).click()
  await expect(page.getByLabel('인증된 카카오 정보')).toContainText(state.email)
  expect(state.starts).toBe(1)
  expect(state.errors).toEqual([])
})

test('cancelling pending signup clears its server session and returns to login without creating an account', async ({ page }) => {
  const state = await mockKakao(page)
  await page.goto('/#oauth/kakao/complete')
  await page.getByRole('button', { name: '가입 취소하고 로그인으로', exact: true }).click()
  await expect(page).toHaveURL(/#launcher$/)
  expect(state.cancelled).toBe(1)
  expect(state.pending).toBe(false)
  expect(state.complete).toEqual([])
  await page.goto('/#oauth/kakao/complete')
  await expect(page.getByRole('alert')).toContainText('만료되었거나 취소')
  expect(state.errors).toEqual([])
})

test('closing public signup during onboarding gives an actionable message instead of a generic permission error', async ({ page }) => {
  const state = await mockKakao(page)
  await page.goto('/#oauth/kakao/complete')
  await expect(page.getByLabel('인증된 카카오 정보')).toBeVisible()
  await fillAccount(page)
  for (const digit of '12345678') await page.getByRole('button', { name: `PIN digit ${digit}`, exact: true }).click()
  state.publicRegistrationEnabled = false
  await page.getByRole('button', { name: '카카오 계정으로 가입 완료', exact: true }).click()
  await expect(page.locator('.auth-feedback.feedback--error')).toContainText('현재 신규 회원가입이 닫혀 있습니다')
  expect(state.user).toBeNull()
  await page.reload()
  await expect(page.getByRole('alert')).toContainText('현재 신규 회원가입이 닫혀 있습니다')
  await expect(page.getByRole('textbox', { name: '로그인 ID', exact: true })).toHaveCount(0)
  expect(state.errors).toEqual([])
})

test('callback errors remain on signup and show fixed actionable messages, never provider text', async ({ page }) => {
  const state = await mockKakao(page)
  for (const [code, expected] of [['existing-account', '계정을 자동으로 연결하지는 않습니다'], ['registration-closed', '현재 신규 회원가입이 닫혀'], ['email-required', '인증된 이메일 제공'], ['cancelled', '인증을 취소'], ['invalid-state', '만료되었거나 일치하지'], ['unavailable', '연결할 수 없습니다'], ['<script>provider-secret</script>', '카카오 인증을 완료하지 못했습니다']]) {
    await page.goto(`/#signup?kakao=${encodeURIComponent(code)}`)
    await expect(page.getByRole('heading', { name: 'TravelLedger 계정을 만드세요.', exact: true })).toBeVisible()
    await expect(page.locator('.auth-feedback.feedback--error')).toContainText(expected)
    await expect(page.locator('body')).not.toContainText('provider-secret')
  }
  expect(state.errors).toEqual([])
})

test('registration policy distinguishes new signup, existing social login and unconfigured providers', async ({ page }) => {
  const state = await mockKakao(page)
  await page.goto('/#signup')
  await expect(page.getByRole('button', { name: '카카오로 계속', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '인증 메일 보내기', exact: true })).toBeVisible()
  await expect(page.getByText('새 미인증 가입 신청은 마지막 인증 메일 요청 후 7일이 지나면 만료됩니다.', { exact: false })).toBeVisible()
  state.publicRegistrationEnabled = false
  await page.reload()
  await expect(page.getByText('신규 가입은 닫혀 있습니다.', { exact: false })).toBeVisible()
  await expect(page.getByRole('button', { name: '카카오로 계속', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '인증 메일 보내기', exact: true })).toHaveCount(0)
  state.configured = false
  await page.reload()
  await expect(page.getByRole('button', { name: '카카오로 계속', exact: true })).toHaveCount(0)
  await expect(page.getByText('현재 사용할 수 있는 소셜 로그인 제공자가 없습니다.', { exact: true })).toBeVisible()
  expect(state.errors).toEqual([])
})

test('Kakao onboarding fits desktop and small mobile screens in both themes without clipping identity or fields', async ({ page }, testInfo) => {
  const state = await mockKakao(page, { email: `${'long-email-'.repeat(12)}@example.test`, nickname: '카카오 긴 닉네임 '.repeat(9) })
  await page.goto('/#oauth/kakao/complete')
  await expect(page.getByLabel('인증된 카카오 정보')).toBeVisible()
  for (const width of [1440, 1024, 390, 320]) {
    await page.setViewportSize({ width, height: 900 })
    for (const theme of ['default', 'toss']) {
      if (((await page.locator('html').getAttribute('data-theme')) === 'toss') !== (theme === 'toss')) await page.getByRole('button', { name: theme === 'toss' ? '다크 테마' : '기본 테마', exact: true }).click()
      await expect(page.getByRole('button', { name: '카카오로 다시 인증', exact: true })).toHaveCSS('background-color', 'rgb(254, 229, 0)')
      await expect(page.getByRole('button', { name: '카카오로 다시 인증', exact: true })).toHaveCSS('color', 'rgb(25, 25, 25)')
      expect(await page.locator('.kakao-registration').evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
      for (const input of await page.locator('.kakao-registration input:not([type="checkbox"])').all()) {
        const r = await input.boundingBox()
        expect(r.x).toBeGreaterThanOrEqual(0)
        expect(r.x + r.width).toBeLessThanOrEqual(width + 1)
      }
      expect(await page.getByLabel('인증된 카카오 정보').evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
      if ([1440, 390].includes(width)) await page.screenshot({ path: testInfo.outputPath(`${width}-${theme}-kakao-signup.png`), fullPage: true })
    }
  }
  expect(state.errors).toEqual([])
})

for (const configured of [false, true]) test(`admin signup policy shows actual Kakao configuration (${configured}) without exposing credentials`, async ({ page }) => {
  const state = await mockKakao(page, { configured, user: { id: 1, loginId: 'mock-admin', displayName: '관리자', admin: true, active: true } })
  await page.goto('/#admin')
  await page.getByRole('button', { name: '관리자', exact: true }).click()
  await page.locator('.admin-command-card', { hasText: '접근 및 사용자 관리' }).getByRole('button', { name: '열기', exact: true }).click()
  const modal = page.locator('.admin-access-control-modal')
  await modal.getByRole('button', { name: '가입 정책', exact: false }).click()
  await expect(modal.getByRole('status')).toContainText(configured ? '카카오 로그인 설정이 감지되었습니다' : '비활성 상태이거나 연결 설정이 누락')
  await modal.getByRole('checkbox', { name: '공개 회원가입 허용', exact: true }).uncheck()
  await modal.getByRole('button', { name: '가입 정책 저장', exact: true }).click()
  await expect(modal).toContainText('공개 회원가입을 껐습니다')
  expect(state.publicRegistrationEnabled).toBe(false)
  expect(state.errors).toEqual([])
})

test('admin promotion asks for explicit scope confirmation and a fresh masked PIN', async ({ page }) => {
  const state = await mockKakao(page, {
    user: { id: 1, loginId: 'mock-admin', displayName: '관리자', admin: true, active: true },
    users: [
      { id: 1, loginId: 'mock-admin', displayName: '관리자', admin: true, active: true },
      { id: 7, loginId: 'member', displayName: '새 관리자', admin: false, active: true },
      { id: 8, loginId: 'disabled', displayName: '비활성 회원', admin: false, active: false },
    ],
  })
  await page.goto('/#admin')
  await page.getByRole('button', { name: '관리자', exact: true }).click()
  await page.locator('.admin-command-card', { hasText: '접근 및 사용자 관리' }).getByRole('button', { name: '열기', exact: true }).click()
  const modal = page.locator('.admin-access-control-modal')
  await modal.getByRole('button', { name: '사용자 상태', exact: false }).click()
  const targetRow = modal.getByRole('row', { name: /member/ })
  await expect(modal.getByRole('row', { name: /disabled/ }).getByRole('button', { name: '관리자 지정' })).toHaveCount(0)
  await targetRow.getByRole('button', { name: '관리자 지정' }).click()
  const form = modal.locator('form', { hasText: '새 관리자 (member)님을 관리자로 지정' })
  await expect(form).toContainText('백업·복원')
  await expect(form).toContainText('관리자 권한을 회수할 수 없습니다')
  const pin = form.getByLabel('현재 관리자의 2차 비밀번호')
  await expect(pin).toHaveAttribute('type', 'password')
  await form.getByRole('button', { name: '관리자로 지정', exact: true }).click()
  await expect(form.getByRole('alert')).toContainText('권한의 범위')
  await form.getByRole('checkbox', { name: '대상 사용자와 관리자 권한 범위를 확인했습니다.' }).check()
  await form.getByRole('button', { name: '관리자로 지정', exact: true }).click()
  await expect(form.getByRole('alert')).toContainText('숫자 8자리')
  expect(state.promotions).toEqual([])
  await pin.fill('87654321')
  await form.getByRole('button', { name: '관리자로 지정', exact: true }).click()
  await expect(form.getByRole('alert')).toContainText('올바르지 않습니다')
  await expect(pin).toHaveValue('')
  await pin.fill('12345678')
  await form.getByRole('button', { name: '관리자로 지정', exact: true }).click()
  await expect(modal.getByRole('status')).toContainText('다시 로그인하면 권한이 적용됩니다')
  await expect(targetRow).toContainText('관리자')
  await expect(targetRow.getByRole('button', { name: '관리자 지정' })).toHaveCount(0)
  expect(state.promotions).toEqual([{ id: 7, secondaryPin: '87654321' }, { id: 7, secondaryPin: '12345678' }])
  expect(state.errors).toEqual([])
})
