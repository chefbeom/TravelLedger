import { expect, test } from '@playwright/test'

test('session restoration finishes before public startup requests begin', async ({ page }) => {
  let release
  const gate = new Promise(resolve => { release = resolve })
  const calls = []
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    calls.push(path)
    if (path === '/api/auth/me') {
      await gate
      return route.fulfill({ status: 401, json: { message: '로그인이 필요합니다.' } })
    }
    if (path === '/api/auth/registration-options') return route.fulfill({ json: { publicRegistrationEnabled: false, socialLoginProviders: [] } })
    return route.fulfill({ json: {} })
  })
  await page.goto('/')
  await expect.poll(() => calls.includes('/api/auth/me')).toBe(true)
  await page.waitForTimeout(200)
  expect(calls).not.toContain('/api/auth/registration-options')
  release()
  await expect.poll(() => calls.includes('/api/auth/registration-options')).toBe(true)
})
