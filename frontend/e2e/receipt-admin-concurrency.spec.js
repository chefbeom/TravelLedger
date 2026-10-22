import { expect, test } from '@playwright/test'

test('verified administrator can change image concurrency and reopen the persisted setting', async ({ page }, testInfo) => {
  const policy = { maxConcurrent: 3 }, saves = [], errors = []
  page.on('pageerror', error => errors.push(error.message))
  await page.route(/^https?:\/\/(?!127\.0\.0\.1|localhost)/, route => route.abort())
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    let json = []
    if (path === '/api/auth/me') json = { id: 1, loginId: 'fixture-admin', displayName: '검증용 관리자', admin: true, active: true }
    else if (path === '/api/auth/csrf') json = { token: 'fixture-csrf' }
    else if (path === '/api/admin/access/status') json = { verified: true }
    else if (path === '/api/admin/dashboard') json = { summary: {}, users: [], blockedIps: [], recentInvites: [] }
    else if (path === '/api/admin/login-audit-logs') json = { content: [], page: 0, totalPages: 0 }
    else if (path === '/api/admin/registration-policy') json = { publicRegistrationEnabled: false }
    else if (path === '/api/admin/login-map-preview') json = { enabled: false }
    else if (path === '/api/admin/ops-control') json = { ai: { candidateServers: [], featureConnections: {} }, dataServer: {} }
    else if (path === '/api/admin/ops-control/image-analysis') {
      if (route.request().method() === 'PATCH') {
        const body = route.request().postDataJSON()
        saves.push(body)
        policy.maxConcurrent = body.maxConcurrent
      }
      json = { ...policy, minAllowed: 1, maxAllowed: 20 }
    }
    return route.fulfill({ json })
  })
  await page.goto('/#admin')
  await page.getByRole('button', { name: '관리자', exact: true }).click()
  await expect(page.getByRole('heading', { name: '관리자 페이지', exact: true })).toBeVisible()
  await page.locator('.admin-command-card', { hasText: 'AI 및 서버 제어판' }).getByRole('button', { name: '열기', exact: true }).click()
  const modal = page.locator('.admin-ops-modal')
  await modal.getByRole('button', { name: '이미지 분석 동시 수 설정', exact: true }).click()
  const panel = modal.getByRole('region', { name: '이미지 분석 동시 수', exact: true })
  const input = panel.getByRole('spinbutton', { name: '최대 동시 이미지 분석 수', exact: true })
  await expect(input).toHaveValue('3')
  await input.fill('5')
  await panel.getByRole('button', { name: '동시 분석 수 저장', exact: true }).click()
  await expect(panel.getByRole('status')).toContainText('5건으로 저장')
  expect(saves).toEqual([{ maxConcurrent: 5 }])
  await input.fill('3.5')
  await panel.getByRole('button', { name: '동시 분석 수 저장', exact: true }).click()
  expect(saves).toHaveLength(1)
  await input.fill('5')
  for (const theme of ['default', 'toss']) {
    await page.evaluate(theme => document.documentElement.dataset.theme = theme, theme)
    expect(await panel.evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
    await modal.screenshot({ path: testInfo.outputPath(`${theme}-admin-image-limit.png`) })
  }
  await modal.getByRole('button', { name: '닫기', exact: true }).click()
  await page.locator('.admin-command-card', { hasText: 'AI 및 서버 제어판' }).getByRole('button', { name: '열기', exact: true }).click()
  await modal.getByRole('button', { name: '이미지 분석 동시 수 설정', exact: true }).click()
  await expect(input).toHaveValue('5')
  expect(errors).toEqual([])
})
