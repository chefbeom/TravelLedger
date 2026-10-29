import { expect, test } from '@playwright/test'

async function mockKakao(page, overrides = {}) {
  const state = { configured: true, publicRegistrationEnabled: true, pending: true, user: null, failComplete: false,
    complete: [], cancelled: 0, starts: 0, callbacks: 0, errors: [], email: 'verified@example.test', nickname: '카카오 닉네임', ...overrides }
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
    } else if (path === '/api/auth/oauth/kakao/start') {
      state.starts++
      // A tiny mock provider page starts a new navigation, so Playwright also
      // intercepts the callback instead of bypassing routes in a redirect chain.
      return route.fulfill({ contentType: 'text/html', body: '<!doctype html><title>Mock Kakao</title><script>location.replace("/api/auth/oauth/kakao/callback?code=mock-code&state=mock-state")</script>' })
    } else if (path === '/api/auth/oauth/kakao/callback') {
      state.callbacks++
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
      if (state.failComplete) return route.fulfill({ status: 400, json: { message: '사용할 수 없는 로그인 ID입니다.' } })
      state.pending = false
      state.user = { id: 72, loginId: payload.loginId, displayName: payload.displayName, active: true, admin: false }
      json = state.user
    } else if (path === '/api/auth/oauth/kakao/cancel') {
      expect(request.headers()['x-xsrf-token']).toBe('mock-csrf')
      state.cancelled++
      state.pending = false
      return route.fulfill({ status: 204 })
    } else if (path === '/api/travel/public/login-map-preview') json = { enabled: false }
    else if (path === '/api/admin/access/status') json = { verified: true }
    else if (path === '/api/admin/dashboard') json = { summary: {}, users: [], blockedIps: [], recentInvites: [] }
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
  await page.getByRole('textbox', { name: '로그인 ID', exact: true }).fill('new-kakao-user')
  await page.getByLabel('비밀번호', { exact: true }).fill('test-password')
  await page.getByLabel('비밀번호 확인', { exact: true }).fill('test-password')
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
  await expect(page.locator('.auth-feedback.feedback--error')).toContainText('사용할 수 없는 로그인 ID')
  await expect(page.getByRole('textbox', { name: '로그인 ID', exact: true })).toHaveValue('new-kakao-user')
  await expect(page.getByLabel('비밀번호', { exact: true })).toHaveValue('test-password')
  state.failComplete = false
  await page.getByRole('textbox', { name: '로그인 ID', exact: true }).fill('corrected-kakao-user')
  await submit.click()
  await expect(page).toHaveURL(/#launcher$/)
  await expect(page.locator('.feedback--success')).toContainText('카카오 계정으로 가입하고 로그인했습니다.')
  expect(state.starts).toBe(1)
  expect(state.callbacks).toBe(1)
  expect(state.complete).toHaveLength(2)
  expect(state.complete[1]).toEqual({ loginId: 'corrected-kakao-user', displayName: state.nickname, password: 'test-password', secondaryPin: '12345678', rememberDevice: true })
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
