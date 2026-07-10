import { expect, test } from '@playwright/test'

const USER_ENV = ['E2E_USER_LOGIN_ID', 'E2E_USER_PASSWORD']
const MOBILE_TRAVEL_FLAG = 'E2E_TRAVEL_MOBILE_SMOKE_READY'

function env(name) {
  return (process.env[name] || '').trim()
}

function requireMobileTravelFixture(testInfo) {
  const missing = USER_ENV.filter((name) => !env(name))
  test.skip(testInfo.project.name !== 'chromium-mobile', 'Runs only against the mobile Playwright project.')
  test.skip(missing.length > 0, `Missing mobile travel credentials: ${missing.join(', ')}`)
  test.skip(env(MOBILE_TRAVEL_FLAG) !== '1', `${MOBILE_TRAVEL_FLAG}=1 is required for stable travel fixtures.`)
}

async function signIn(page) {
  const csrfResponse = await page.request.get('/api/auth/csrf')
  expect(csrfResponse.ok(), 'CSRF bootstrap should succeed before login.').toBeTruthy()

  const loginResponse = await page.request.post('/api/auth/login', {
    data: {
      loginId: env('E2E_USER_LOGIN_ID'),
      password: env('E2E_USER_PASSWORD'),
      secondaryPin: env('E2E_USER_SECONDARY_PIN') || undefined,
      rememberDevice: true,
    },
  })
  expect(loginResponse.ok(), 'Mobile travel fixture user should be able to log in.').toBeTruthy()
}

async function expectNoHorizontalOverflow(page, target = 'document') {
  const metrics = await page.evaluate((scope) => {
    const element = scope === 'document'
      ? document.documentElement
      : document.querySelector(scope)
    return element
      ? { clientWidth: element.clientWidth, scrollWidth: element.scrollWidth }
      : null
  }, target)
  expect(metrics, `${target} should exist.`).not.toBeNull()
  expect(metrics.scrollWidth, `${target} must not require horizontal scrolling.`).toBeLessThanOrEqual(metrics.clientWidth + 1)
}

async function expectMobileModalLock(page, dialog) {
  await expect(dialog).toBeVisible()
  await expectNoHorizontalOverflow(page, '.travel-modal__dialog')
  const state = await page.evaluate(() => ({
    rootOverflow: document.documentElement.style.overflow,
    bodyPosition: document.body.style.position,
    bodyTop: document.body.style.top,
  }))
  expect(state.rootOverflow).toBe('hidden')
  expect(state.bodyPosition).toBe('fixed')
  expect(state.bodyTop).toMatch(/^-\d+px$/)
}

test('mobile modal guard locks and restores background scroll in the app shell', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'chromium-mobile', 'Runs only against the mobile Playwright project.')
  await page.goto('/')
  await expect(page.locator('#app')).toBeVisible()

  await page.evaluate(() => {
    const dialog = document.createElement('section')
    dialog.id = 'mobile-modal-scroll-lock-fixture'
    dialog.setAttribute('role', 'dialog')
    dialog.setAttribute('aria-modal', 'true')
    document.body.append(dialog)
  })

  await expect.poll(() => page.evaluate(() => ({
    rootOverflow: document.documentElement.style.overflow,
    bodyPosition: document.body.style.position,
  }))).toEqual({ rootOverflow: 'hidden', bodyPosition: 'fixed' })

  await page.evaluate(() => document.querySelector('#mobile-modal-scroll-lock-fixture')?.remove())
  await expect.poll(() => page.evaluate(() => ({
    rootOverflow: document.documentElement.style.overflow,
    bodyPosition: document.body.style.position,
  }))).toEqual({ rootOverflow: '', bodyPosition: '' })
})
test.describe('mobile travel map and modal interactions', () => {
  test.beforeEach(async ({ page }, testInfo) => {
    requireMobileTravelFixture(testInfo)
    await signIn(page)
    await page.goto('/#/travel')
    await expect(page.getByRole('heading', { name: '내 여행 지도' })).toBeVisible()
  })

  test('map filters, tap and rotation keep the travel view responsive', async ({ page }, testInfo) => {
    requireMobileTravelFixture(testInfo)
    const pageErrors = []
    page.on('pageerror', (error) => pageErrors.push(error.message))

    await page.getByRole('button', { name: '여행별' }).click()
    await expect(page.getByLabel('여행 선택')).toBeVisible()

    const map = page.locator('.travel-map__canvas.leaflet-container').first()
    await expect(map).toBeVisible()
    const box = await map.boundingBox()
    expect(box, 'Travel map should have a visible tap target.').not.toBeNull()
    await page.touchscreen.tap(box.x + (box.width / 2), box.y + (box.height / 2))

    await expectNoHorizontalOverflow(page)
    await page.setViewportSize({ width: 844, height: 390 })
    await expect(map).toBeVisible()
    await expectNoHorizontalOverflow(page)
    await page.setViewportSize({ width: 390, height: 844 })
    await expect(map).toBeVisible()
    expect(pageErrors).toEqual([])
  })

  test('share modal locks the background, fits the viewport and restores it when closed', async ({ page }, testInfo) => {
    requireMobileTravelFixture(testInfo)
    await page.getByRole('button', { name: '지도 공유' }).click()
    const dialog = page.getByRole('dialog', { name: '여행 지도 공유' })
    await expectMobileModalLock(page, dialog)

    await dialog.evaluate((element) => {
      element.scrollTop = element.scrollHeight
    })
    await expectNoHorizontalOverflow(page, '.travel-map-share-modal__dialog')

    await page.setViewportSize({ width: 844, height: 390 })
    await expect(dialog).toBeVisible()
    await expectNoHorizontalOverflow(page, '.travel-map-share-modal__dialog')
    await page.setViewportSize({ width: 390, height: 844 })

    await dialog.getByRole('button', { name: '닫기' }).click()
    await expect(dialog).toBeHidden()
    const state = await page.evaluate(() => ({
      rootOverflow: document.documentElement.style.overflow,
      bodyPosition: document.body.style.position,
    }))
    expect(state.rootOverflow).toBe('')
    expect(state.bodyPosition).toBe('')
  })

  test('photo detail modal supports touch open, previous/next and close without overflow', async ({ page }, testInfo) => {
    requireMobileTravelFixture(testInfo)
    await page.getByRole('button', { name: /^내 사진:/ }).click()
    await expect(page.getByRole('heading', { name: '내 사진' })).toBeVisible()

    const firstPhoto = page.locator('.travel-my-photos__thumb-button').first()
    await expect(firstPhoto, 'The travel fixture must provide at least one photo.').toBeVisible()
    await firstPhoto.tap()

    const dialog = page.getByRole('dialog', { name: '사진 상세' })
    await expectMobileModalLock(page, dialog)
    await expectNoHorizontalOverflow(page, '.travel-my-photos__modal')

    const next = dialog.getByRole('button', { name: '다음 사진' })
    if (await next.count()) {
      await next.tap()
      await expect(dialog).toBeVisible()
    }

    await dialog.getByRole('button', { name: '닫기' }).tap()
    await expect(dialog).toBeHidden()
  })
})
