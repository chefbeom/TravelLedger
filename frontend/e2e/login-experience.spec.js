import { expect, test } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import path from 'node:path'

const screenshotDir = path.resolve('artifacts/login-redesign-20261029')
const mockTile = `<svg xmlns="http://www.w3.org/2000/svg" width="256" height="256" viewBox="0 0 256 256">
  <rect width="256" height="256" fill="#d8ded2"/><path d="M-20 75 L280 175 M-30 220 L270 30 M30 -10 L105 270 M220 -20 L165 275" fill="none" stroke="#f7f4e9" stroke-width="13"/>
  <path d="M-20 75 L280 175 M-30 220 L270 30 M30 -10 L105 270 M220 -20 L165 275" fill="none" stroke="#9baea1" stroke-width="2"/>
  <path d="M0 35 H256 M0 135 H256 M0 235 H256 M50 0 V256 M150 0 V256 M250 0 V256" fill="none" stroke="#bbc7b9" stroke-width="1"/>
</svg>`

async function mockLogin(page, { theme = 'default', kakao = false, previewEnabled = true, previewError = false, loginSucceeds = false } = {}) {
  const state = { payloads: [], errors: [] }
  page.on('pageerror', error => state.errors.push(error.message))
  await page.addInitScript(value => localStorage.setItem('calen-theme-mode', value), theme)
  await page.route(/^https?:\/\/(?!127\.0\.0\.1|localhost)/, route => route.abort())
  await page.route(/^https:\/\/[abc]\.tile\.openstreetmap\.org\//, route => route.fulfill({ contentType: 'image/svg+xml', body: mockTile }))
  await page.route('**/api/**', async route => {
    const request = route.request()
    const pathname = new URL(request.url()).pathname
    if (pathname === '/api/auth/me') return route.fulfill({ status: 401, json: { message: '로그인이 필요합니다.' } })
    if (pathname === '/api/auth/csrf') return route.fulfill({ json: { token: 'mock-csrf' } })
    if (pathname === '/api/auth/login') {
      state.payloads.push(request.postDataJSON())
      if (loginSucceeds) return route.fulfill({ json: { id: 1, loginId: 'design-test', displayName: '디자인 테스트', active: true, admin: false } })
      return route.fulfill({ status: 401, json: { message: '로그인 정보를 확인해 주세요.' } })
    }
    if (pathname === '/api/auth/registration-options') {
      return route.fulfill({ json: { publicRegistrationEnabled: false, socialLoginProviders: kakao ? ['KAKAO'] : [] } })
    }
    if (pathname === '/api/travel/public/login-map-preview') {
      if (previewError) return route.fulfill({ status: 503, json: { message: '테스트 오류' } })
      if (!previewEnabled) return route.fulfill({ json: { enabled: false } })
      return route.fulfill({ json: {
        enabled: true, markerCount: 4, routeCount: 1,
        markers: [
          { number: 1, latitude: 37.58, longitude: 126.92 },
          { number: 2, latitude: 37.55, longitude: 126.97 },
          { number: 3, latitude: 37.52, longitude: 127.02 },
          { number: 4, latitude: 37.49, longitude: 127.08 },
        ],
        routes: [{ points: [
          { latitude: 37.58, longitude: 126.92 },
          { latitude: 37.55, longitude: 126.97 },
          { latitude: 37.52, longitude: 127.02 },
          { latitude: 37.49, longitude: 127.08 },
        ] }],
      } })
    }
    return route.fulfill({ json: [] })
  })
  await page.goto('/#launcher')
  await expect(page.getByRole('heading', { name: '로그인', exact: true })).toBeVisible()
  if (previewEnabled && !previewError) await expect(page.getByText('장소 4곳')).toBeVisible()
  return state
}

async function noHorizontalOverflow(page) {
  const dimensions = await page.evaluate(() => ({ width: document.documentElement.clientWidth, scroll: document.documentElement.scrollWidth }))
  expect(dimensions.scroll).toBeLessThanOrEqual(dimensions.width + 1)
}

test('four viewports keep the split layout, a usable form, and capture comparison screenshots', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'chromium-desktop', 'The matrix already includes the mobile viewport.')
  test.setTimeout(120_000)
  const state = await mockLogin(page)
  await mkdir(screenshotDir, { recursive: true })
  for (const digit of '123') await page.getByRole('button', { name: `2차 비밀번호 ${digit}`, exact: true }).click()
  await expect(page.locator('.login-map-preview__heading h2')).toHaveCSS('-webkit-text-fill-color', 'rgb(244, 241, 232)')
  await expect(page.locator('.login-map-preview__heading p')).toHaveCSS('-webkit-text-fill-color', 'rgb(244, 241, 232)')
  await expect(page.locator('.pin-pad__slot--filled').first()).toHaveCSS('background-color', 'rgb(47, 107, 79)')
  await expect(page.locator('.pin-pad__slot:not(.pin-pad__slot--filled)').first()).toHaveCSS('background-color', 'rgb(200, 207, 196)')
  const viewports = [
    { name: 'qhd-2560x1440', width: 2560, height: 1440 },
    { name: 'fhd-plus-1920x1200', width: 1920, height: 1200 },
    { name: 'laptop-1366x768', width: 1366, height: 768 },
    { name: 'mobile-390x844', width: 390, height: 844 },
  ]
  for (const viewport of viewports) {
    await page.setViewportSize({ width: viewport.width, height: viewport.height })
    await expect(page.locator('.login-map-preview__map svg path.leaflet-interactive').first()).toBeAttached()
    await expect.poll(() => page.locator('.login-map-preview .leaflet-tile:not(.leaflet-tile-loaded)').count()).toBe(0)
    await noHorizontalOverflow(page)
    for (const selector of ['.login-map-preview__attribution a', '.login-map-preview .leaflet-control-zoom-in', '.pin-pad__key', '.stack-form--login > .button--primary']) {
      const rect = await page.locator(selector).first().boundingBox()
      expect(rect.width, selector).toBeGreaterThanOrEqual(44)
      expect(rect.height, selector).toBeGreaterThanOrEqual(44)
    }
    const dimensions = await page.evaluate(() => {
      const rect = selector => {
        const { x, y, width, height, bottom } = document.querySelector(selector).getBoundingClientRect()
        return { x, y, width, height, bottom }
      }
      return { shell: rect('.auth-shell--travel-preview'), map: rect('.login-map-preview'), panel: rect('.auth-travel-panel'), brand: rect('.auth-travel-brand'), note: rect('.auth-signup-note') }
    })
    expect(dimensions.shell.x).toBe(0)
    expect(dimensions.shell.width).toBe(viewport.width)
    if (viewport.width > 900) {
      expect(dimensions.map.width / dimensions.panel.width).toBeCloseTo(1.15, 1)
      expect(dimensions.panel.x).toBeCloseTo(dimensions.map.width, 0)
      expect(Math.abs((dimensions.brand.y + dimensions.note.bottom) / 2 - (dimensions.panel.y + dimensions.panel.height / 2))).toBeLessThan(3)
      if (viewport.name === 'laptop-1366x768') expect(dimensions.shell.height).toBeLessThanOrEqual(viewport.height)
    } else {
      expect(dimensions.map.width).toBe(viewport.width)
      expect(dimensions.map.height).toBe(260)
      expect(dimensions.panel.y).toBe(dimensions.map.bottom)
    }
    await page.mouse.move(0, 0)
    await page.screenshot({ path: path.join(screenshotDir, `${viewport.name}.png`), animations: 'disabled' })
    if (dimensions.shell.height > viewport.height) {
      await page.screenshot({ path: path.join(screenshotDir, `${viewport.name}-full.png`), fullPage: true, animations: 'disabled' })
    }
  }
  expect(state.errors).toEqual([])
})

test('PIN is masked, Korean controls preserve input behavior and login sends the original payload', async ({ page }) => {
  const state = await mockLogin(page)
  await page.getByLabel('아이디', { exact: true }).fill('design-test')
  await page.getByLabel('비밀번호', { exact: true }).fill('not-a-real-password')
  expect(await page.getByLabel('비밀번호', { exact: true }).getAttribute('type')).toBe('password')
  for (const digit of '123') await page.getByRole('button', { name: `2차 비밀번호 ${digit}`, exact: true }).click()
  await expect(page.locator('.pin-pad__progress')).toHaveText('3 / 8')
  await expect(page.locator('.pin-pad__slot--filled')).toHaveCount(3)
  await expect(page.locator('.pin-pad__display')).toHaveAttribute('aria-label', '2차 비밀번호 8자리 중 3자리 입력됨')
  expect(await page.locator('.pin-pad__display').innerText()).not.toMatch(/[123]/)
  await page.getByRole('button', { name: '2차 비밀번호 한 자리 지우기' }).click()
  await expect(page.locator('.pin-pad__progress')).toHaveText('2 / 8')
  await page.getByRole('button', { name: '2차 비밀번호 모두 지우기' }).click()
  await expect(page.locator('.pin-pad__progress')).toHaveText('0 / 8')
  for (const digit of '12345678') await page.getByRole('button', { name: `2차 비밀번호 ${digit}`, exact: true }).click()
  await expect(page.getByRole('button', { name: '2차 비밀번호 9', exact: true })).toBeDisabled()
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('로그인 정보를 확인해 주세요')
  expect(state.payloads).toEqual([{ loginId: 'design-test', password: 'not-a-real-password', secondaryPin: '12345678', rememberDevice: true }])
  expect(state.errors).toEqual([])
})

test('view and theme controls remain available after sign-in', async ({ page }) => {
  await mockLogin(page, { loginSucceeds: true })
  await expect(page.locator('.theme-switcher')).toBeHidden()
  await page.getByLabel('아이디', { exact: true }).fill('design-test')
  await page.getByLabel('비밀번호', { exact: true }).fill('not-a-real-password')
  for (const digit of '12345678') await page.getByRole('button', { name: `2차 비밀번호 ${digit}`, exact: true }).click()
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page.locator('.theme-switcher')).toBeVisible()
  await expect(page.getByRole('button', { name: '다크 테마', exact: true })).toBeVisible()
})

test('short and zoom-equivalent viewports scroll without clipping, including Kakao and dark theme', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'chromium-desktop', 'This exercises both desktop and small layouts.')
  const state = await mockLogin(page, { theme: 'toss', kakao: true })
  // Chrome zoom changes the CSS viewport. These sizes match 1366×768 at 125% and 150%.
  for (const viewport of [{ width: 1093, height: 614 }, { width: 910, height: 512 }, { width: 900, height: 512 }, { width: 320, height: 568 }]) {
    await page.setViewportSize(viewport)
    await noHorizontalOverflow(page)
    await page.getByRole('button', { name: '카카오로 계속' }).scrollIntoViewIfNeeded()
    await expect(page.getByRole('button', { name: '카카오로 계속' })).toBeInViewport()
    const fields = page.locator('.login-field input')
    await expect(fields.first()).toHaveCSS('height', '48px')
    await expect(page.locator('.pin-pad__key').first()).toHaveCSS('min-height', '48px')
  }
  expect(state.errors).toEqual([])
})

function contrast(first, second) {
  const luminance = color => {
    const channels = color.match(/[\d.]+/g).slice(0, 3).map(value => Number(value) / 255)
    const linear = channels.map(value => value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4)
    return linear[0] * 0.2126 + linear[1] * 0.7152 + linear[2] * 0.0722
  }
  const values = [luminance(first), luminance(second)].sort((a, b) => b - a)
  return (values[0] + 0.05) / (values[1] + 0.05)
}

for (const theme of ['default', 'toss']) {
  test(`${theme} login text and controls meet WCAG AA contrast`, async ({ page }, testInfo) => {
    test.skip(testInfo.project.name !== 'chromium-desktop', 'Colors do not depend on pointer mode.')
    await mockLogin(page, { theme })
    const pairs = [
      ['.auth-travel-brand h1', '.auth-travel-panel'],
      ['.auth-travel-brand p', '.auth-travel-panel'],
      ['.auth-travel-login__heading h2', '.auth-travel-panel'],
      ['.login-field span', '.auth-travel-panel'],
      ['.pin-pad__progress', '.auth-travel-panel'],
      ['.pin-pad__hint', '.auth-travel-panel'],
      ['.checkbox-row span', '.auth-travel-panel'],
      ['.auth-signup-note span', '.auth-travel-panel'],
      ['.login-field input', '.login-field input'],
      ['.pin-pad__key', '.pin-pad__key'],
      ['.stack-form--login > .button--primary', '.stack-form--login > .button--primary'],
    ]
    for (const [foreground, background] of pairs) {
      const colors = await page.evaluate(([fg, bg]) => {
        const textStyle = getComputedStyle(document.querySelector(fg))
        return [textStyle.webkitTextFillColor, getComputedStyle(document.querySelector(bg)).backgroundColor]
      }, [foreground, background])
      expect(contrast(...colors), `${foreground}: ${colors.join(' on ')}`).toBeGreaterThanOrEqual(4.5)
    }
    await expect(page.locator('.login-map-preview__heading h2')).toHaveCSS('-webkit-text-fill-color', 'rgb(244, 241, 232)')
    await expect(page.locator('.stack-form--login > .button--primary')).toHaveCSS('background-color', 'rgb(47, 107, 79)')
    const placeholderColors = await page.locator('.login-field input').first().evaluate(input => [getComputedStyle(input, '::placeholder').webkitTextFillColor, getComputedStyle(input).backgroundColor])
    expect(contrast(...placeholderColors), `placeholder: ${placeholderColors.join(' on ')}`).toBeGreaterThanOrEqual(4.5)
    await page.getByRole('button', { name: '2차 비밀번호 1', exact: true }).click()
    await expect(page.locator('.pin-pad__slot--filled').first()).toHaveCSS('background-color', theme === 'toss' ? 'rgb(132, 215, 169)' : 'rgb(47, 107, 79)')
    if (theme === 'toss') {
      await mkdir(screenshotDir, { recursive: true })
      await page.screenshot({ path: path.join(screenshotDir, 'dark-theme.png'), fullPage: true, animations: 'disabled' })
    }
  })
}

for (const previewError of [false, true]) {
  test(`${previewError ? 'unavailable' : 'unconfigured'} preview remains legible and never covers the login form`, async ({ page }) => {
    await mockLogin(page, { previewEnabled: false, previewError })
    const empty = page.locator('.login-map-preview__empty strong')
    await expect(empty).toHaveText(previewError ? '지도를 불러오지 못했습니다.' : '여행 기록을 지도 위에서 다시 만나보세요.')
    await expect(empty).toHaveCSS('-webkit-text-fill-color', 'rgb(244, 241, 232)')
    await expect(page.getByLabel('아이디', { exact: true })).toBeVisible()
    await noHorizontalOverflow(page)
  })
}

test('map zoom and pin popup remain interactive and keyboard focus stays visible', async ({ page }) => {
  const state = await mockLogin(page)
  await page.locator('.login-map-preview .leaflet-interactive').nth(1).click()
  await expect(page.locator('.login-map-preview .leaflet-popup')).toBeVisible()
  await page.locator('.login-map-preview .leaflet-control-zoom-in').click()
  await page.getByLabel('아이디', { exact: true }).focus()
  await page.keyboard.press('Tab')
  await expect(page.getByLabel('비밀번호', { exact: true })).toBeFocused()
  const focus = await page.getByLabel('비밀번호', { exact: true }).evaluate(element => ({ style: getComputedStyle(element).outlineStyle, width: getComputedStyle(element).outlineWidth }))
  expect(focus.style).toBe('solid')
  expect(parseFloat(focus.width)).toBeGreaterThanOrEqual(2)
  expect(state.errors).toEqual([])
})
