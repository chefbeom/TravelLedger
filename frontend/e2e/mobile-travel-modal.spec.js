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
test('public shared map photo modal stays stable while the full image loads', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'chromium-mobile', 'Runs only against the mobile Playwright project.')

  const imageBytes = Buffer.from(
    'iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAFUlEQVR42mNkYGD4z8DAwMDAxAADAAANHQEDasKb6QAAAABJRU5ErkJggg==',
    'base64',
  )
  const photos = [
    {
      id: 101,
      planId: 1,
      planName: '모바일 여행',
      originalFileName: 'photo-1.png',
      contentType: 'image/png',
      contentUrl: '/test-photo-1.png',
      expenseDate: '2026-07-12',
      expenseTime: '14:41:00',
      title: 'Sengen-dori Street',
      country: '일본',
      region: '도쿄',
      placeName: 'Sengen-dori Street',
    },
    {
      id: 102,
      planId: 1,
      planName: '모바일 여행',
      originalFileName: 'photo-2.png',
      contentType: 'image/png',
      contentUrl: '/test-photo-2.png',
      expenseDate: '2026-07-12',
      expenseTime: '14:42:00',
      title: 'Second photo',
      country: '일본',
      region: '도쿄',
      placeName: 'Second place',
    },    {
      id: 201,
      planId: 1,
      planName: '모바일 여행',
      originalFileName: 'photo-3.png',
      contentType: 'image/png',
      contentUrl: '/test-photo-3.png',
      expenseDate: '2026-07-13',
      expenseTime: '09:10:00',
      title: 'Third photo',
      country: '일본',
      region: '요코하마',
      placeName: 'Third place',
    },
  ]

  await page.route('**/api/auth/me', (route) => route.fulfill({ status: 401, contentType: 'application/json', body: '{}' }))
  await page.route('**/api/travel/public/map-shares/mobile-photo-stability**', async (route) => {
    const url = new URL(route.request().url())
    if (url.pathname.includes('/photo-clusters/')) {
      const clusterId = Number(url.pathname.split('/').at(-1))
      const clusterPhotos = clusterId === 10 ? photos.slice(0, 2) : photos.slice(2)
      await new Promise((resolve) => setTimeout(resolve, 120))
      await route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify({
          id: clusterId,
          representativeMediaId: clusterPhotos[0].id,
          representativePhoto: clusterPhotos[0],
          photos: clusterPhotos,
          page: 0,
          size: 36,
          totalPhotoCount: clusterPhotos.length,
          hasNext: false,
        }),
      })
      return
    }

    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        token: 'mobile-photo-stability',
        title: '모바일 공유 지도',
        ownerDisplayName: '테스터',
        createdAt: '2026-07-12T14:40:00',
        overview: {
          includedPlanCount: 1,
          markers: [],
          photoPins: [
            { mediaId: 101, clusterId: 10, latitude: 35.6812, longitude: 139.7671, photoUrl: '/test-photo-1.png' },
            { mediaId: 102, clusterId: 10, latitude: 35.68121, longitude: 139.76711, photoUrl: '/test-photo-2.png' },
            { mediaId: 201, clusterId: 20, latitude: 35.6912, longitude: 139.7771, photoUrl: '/test-photo-3.png' },
          ],
          routes: [],
          photoClusters: [
            {
              id: 10,
              representativeMediaId: 101,
              planId: 1,
              planName: '모바일 여행',
              planColorHex: '#16A34A',
              memoryDate: '2026-07-12',
              memoryTime: '14:41:00',
              title: 'Sengen-dori Street',
              country: '일본',
              region: '도쿄',
              placeName: 'Sengen-dori Street',
              latitude: 35.6812,
              longitude: 139.7671,
              photoCount: 2,
              memoryCount: 1,
              representativePhotoUrl: '/test-photo-1.png',
            },
            {
              id: 20,
              representativeMediaId: 201,
              planId: 1,
              planName: '모바일 여행',
              planColorHex: '#16A34A',
              memoryDate: '2026-07-13',
              memoryTime: '09:10:00',
              title: 'Third photo',
              country: '일본',
              region: '요코하마',
              placeName: 'Third place',
              latitude: 35.6912,
              longitude: 139.7771,
              photoCount: 1,
              memoryCount: 1,
              representativePhotoUrl: '/test-photo-3.png',
            },
          ],
        },
      }),
    })
  })
  await page.route('**/test-photo-*.png*', async (route) => {
    const url = new URL(route.request().url())
    if (!url.searchParams.has('thumbnail')) {
      await new Promise((resolve) => setTimeout(resolve, 700))
    }
    await route.fulfill({ status: 200, contentType: 'image/png', body: imageBytes })
  })

  const pageErrors = []
  page.on('pageerror', (error) => pageErrors.push(error.message))
  await page.goto('/#travel-share/mobile-photo-stability')

  const fullscreenButton = page.locator('.travel-map__toolbar-group:last-child .travel-map__toolbar-button').last()
  await expect(fullscreenButton).toBeVisible()
  await fullscreenButton.tap()
  await expect.poll(() => page.evaluate(() => Boolean(document.fullscreenElement))).toBeTruthy()

  const cluster = page.locator('.travel-cluster-pin').first()
  await expect(cluster).toBeVisible()
  await cluster.tap()

  const dialog = page.locator('.public-map-share-photo-modal')
  const panel = page.locator('.public-map-share-photo-modal__panel')
  await expect(dialog).toBeVisible()
  await expect(panel).toBeVisible()
  await page.evaluate(() => document.documentElement.removeAttribute('data-theme'))
  const lightThemeColors = await panel.evaluate((element) => {
    const title = element.querySelector('h2')
    const meta = element.querySelector('.public-map-share-photo-modal__header p')
    const closeButton = element.querySelector('.public-map-share-photo-modal__header-actions .button')
    return {
      panelBackground: getComputedStyle(element).backgroundColor,
      title: getComputedStyle(title).color,
      meta: getComputedStyle(meta).color,
      closeButton: getComputedStyle(closeButton).color,
    }
  })
  expect(lightThemeColors).toEqual({
    panelBackground: 'rgb(255, 255, 255)',
    title: 'rgb(25, 31, 40)',
    meta: 'rgb(78, 89, 104)',
    closeButton: 'rgb(25, 31, 40)',
  })
  const loadingHeight = await panel.evaluate((element) => element.getBoundingClientRect().height)
  await expect(page.locator('.public-map-share-photo-modal__image-state--loading')).toBeVisible()
  await expect(page.locator('.public-map-share-photo-modal__media > img')).toBeVisible()
  const loadedHeight = await panel.evaluate((element) => element.getBoundingClientRect().height)

  expect(Math.abs(loadedHeight - loadingHeight)).toBeLessThanOrEqual(1)

  const nextPhotoButton = page.locator('.public-map-share-photo-modal__nav--next')
  await nextPhotoButton.tap()
  await expect(page.locator('.public-map-share-photo-modal__header h2')).toHaveText('Second place')
  await nextPhotoButton.tap()
  await expect(page.locator('.public-map-share-photo-modal__header h2')).toHaveText('Third place')
  await expect(page.locator('.public-map-share-photo-modal__header p')).toContainText('2026-07-13')
  await expect(page.locator('.public-map-share-photo-modal__media > img')).toHaveAttribute('src', '/test-photo-3.png')
  expect(pageErrors).toEqual([])
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
