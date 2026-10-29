import { chromium, expect, test } from '@playwright/test'
import path from 'node:path'

test('actual browser zoom at 125% and 150% does not clip the login form', async ({ baseURL }, testInfo) => {
  test.skip(testInfo.project.name !== 'chromium-desktop', 'The browser zoom API is desktop-specific.')
  const extension = path.resolve('e2e/fixtures/login-zoom-extension')
  const context = await chromium.launchPersistentContext('', {
    channel: 'chromium',
    headless: true,
    viewport: { width: 1366, height: 768 },
    args: [`--disable-extensions-except=${extension}`, `--load-extension=${extension}`],
  })
  try {
    const worker = context.serviceWorkers()[0] || await context.waitForEvent('serviceworker')
    const page = context.pages()[0] || await context.newPage()
    await page.route(/^https?:\/\/(?!127\.0\.0\.1|localhost)/, route => route.abort())
    await page.route('**/api/**', async route => {
      const pathname = new URL(route.request().url()).pathname
      if (pathname === '/api/auth/me') return route.fulfill({ status: 401, json: { message: '로그인이 필요합니다.' } })
      if (pathname === '/api/auth/registration-options') return route.fulfill({ json: { publicRegistrationEnabled: false, socialLoginProviders: ['KAKAO'] } })
      if (pathname === '/api/travel/public/login-map-preview') return route.fulfill({ json: { enabled: false } })
      return route.fulfill({ json: [] })
    })
    await page.goto(`${baseURL}/#launcher`)
    await expect(page.getByLabel('아이디', { exact: true })).toBeVisible()
    const tabId = await worker.evaluate(async () => {
      const tabs = await chrome.tabs.query({ active: true, lastFocusedWindow: true })
      return tabs[0].id
    })
    for (const factor of [1.25, 1.5]) {
      const actual = await worker.evaluate(async ({ tabId, factor }) => {
        await chrome.tabs.setZoom(tabId, factor)
        return chrome.tabs.getZoom(tabId)
      }, { tabId, factor })
      expect(actual).toBeCloseTo(factor, 2)
      await expect.poll(async () => Math.abs(await page.evaluate(() => innerWidth) - 1366 / factor)).toBeLessThan(3)
      const dimensions = await page.evaluate(() => ({ clientWidth: document.documentElement.clientWidth, scrollWidth: document.documentElement.scrollWidth }))
      expect(dimensions.scrollWidth).toBeLessThanOrEqual(dimensions.clientWidth + 1)
      await page.getByRole('button', { name: '카카오로 계속' }).scrollIntoViewIfNeeded()
      await expect(page.getByRole('button', { name: '카카오로 계속' })).toBeInViewport()
      await expect(page.getByLabel('아이디', { exact: true })).toBeAttached()
    }
  } finally {
    await context.close()
  }
})
