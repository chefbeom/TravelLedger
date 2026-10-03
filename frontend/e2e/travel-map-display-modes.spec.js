import { expect, test } from '@playwright/test'

const image = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6x1sAAAAASUVORK5CYII=', 'base64')
const photos = [1, 2, 3, 4].map((id) => ({
  id, mediaId: id, clusterId: id === 4 ? 20 : 10, recordId: id,
  planId: 1, planName: '표시 방식 테스트', planColorHex: '#16A34A',
  latitude: id === 4 ? 35.7052 : 35.6812 + id * 0.000001,
  longitude: id === 4 ? 139.7911 : 139.7671 + id * 0.000001,
  title: `Photo ${id}`, placeName: `Place ${id}`, country: '일본', region: '도쿄',
  memoryDate: '2026-10-03', memoryTime: `10:0${id}:00`,
  expenseDate: '2026-10-03', expenseTime: `10:0${id}:00`,
  photoUrl: `/map-group-photo-${id}.png`, contentUrl: `/map-group-photo-${id}.png`,
  originalFileName: `photo-${id}.png`, mediaType: 'PHOTO', recordType: 'MEMORY',
}))
const clusters = [10, 20].map((id) => {
  const members = photos.filter((photo) => photo.clusterId === id)
  return { ...members[0], id, representativeMediaId: members[0].mediaId, representativePhotoUrl: members[0].photoUrl, photoCount: members.length, memoryCount: members.length }
})
const overview = { includedPlanCount: 1, markers: [], routes: [], photoPins: photos, photoClusters: clusters }

async function mockMap(page) {
  const requests = []
  const errors = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.route(/^https?:\/\/(?!127\.0\.0\.1|localhost)/, (route) => route.abort())
  await page.route('**/map-group-photo-*.png*', (route) => route.fulfill({ contentType: 'image/png', body: image }))
  await page.route('**/api/**', (route) => {
    const path = new URL(route.request().url()).pathname
    requests.push(path)
    if (path === '/api/auth/me') return route.fulfill({ status: 401, json: {} })
    if (path.includes('/photo-clusters/')) {
      const id = Number(path.split('/').at(-1))
      const members = photos.filter((photo) => photo.clusterId === id)
      return route.fulfill({ json: { id, representativeMediaId: members[0]?.mediaId, representativePhoto: members[0], photos: members, page: 0, size: 12, totalPhotoCount: members.length, hasNext: false } })
    }
    if (path === '/api/travel/my-map') return route.fulfill({ json: overview })
    if (path === '/api/travel/public/map-shares/grouped-photos') return route.fulfill({ json: { token: 'grouped-photos', title: '공유 지도 테스트', ownerDisplayName: '테스터', overview } })
    return route.fulfill({ json: {} })
  })
  await page.goto('/')
  return { requests, errors }
}

async function mountMap(page, kind = 'panel', day = false) {
  const fixture = await mockMap(page)
  await page.evaluate(async ({ kind, day, clusters, photos }) => {
    const { createApp, h, reactive } = await import('/node_modules/.vite/deps/vue.js')
    const file = kind === 'private' ? 'TravelMyMapWorkspace' : kind === 'public' ? 'TravelPublicMapShareWorkspace' : 'TravelMyMapClusterPanel'
    const { default: Component } = await import(`/src/components/${file}.vue`)
    document.querySelector('#app').style.display = 'none'
    const host = document.createElement('div')
    host.className = 'app-shell'
    host.id = 'map-display-test'
    document.body.appendChild(host)
    if (kind !== 'panel') {
      createApp(Component, kind === 'public' ? { token: 'grouped-photos' } : { active: true }).mount(host)
      return
    }
    const state = reactive({ photoClusters: day ? [] : clusters, photoPins: photos, displayMode: day ? 'pin' : 'cluster', selectedPhotoId: null, journeyPhotoId: null, journeyPlaybackActive: false, focusTarget: null })
    window.mapDisplayFixture = state
    window.mapDisplayPreviewEvents = []
    createApp({ render: () => h(Component, { ...state, onPreviewCluster: (item) => window.mapDisplayPreviewEvents.push(item.mediaId ?? item.id) }) }).mount(host)
  }, { kind, day, clusters, photos })
  return fixture
}

const markerSelector = '.leaflet-marker-pane .leaflet-marker-icon'

for (const kind of ['private', 'public']) {
  test(`${kind} travel map restores thumbnails and switches to grouped count pins`, async ({ page }, testInfo) => {
    const fixture = await mountMap(page, kind)
    const thumbnails = page.locator('.travel-map-photo-cluster')
    await expect(thumbnails).toHaveCount(2)
    await expect(thumbnails.first().locator('img')).toHaveAttribute('src', /thumbnail=true.*w=96/)
    await expect.poll(() => thumbnails.first().locator('img').evaluate((img) => img.complete && img.naturalWidth > 0)).toBeTruthy()
    await expect(thumbnails.locator('.travel-map-photo-cluster__count')).toHaveText(['3', '1'])
    const map = page.locator('#map-display-test .travel-map')
    await map.screenshot({ path: testInfo.outputPath('thumbnail-mode.png') })
    await page.getByRole('button', { name: kind === 'private' ? '핀 보기' : '핀', exact: true }).click()
    await expect(thumbnails).toHaveCount(0)
    await expect(page.locator(markerSelector)).toHaveCount(2)
    await expect(page.locator('.travel-map-pin-glyph__count')).toHaveText(['3', '1'])
    for (const theme of ['default', 'toss']) {
      await page.evaluate((theme) => { document.documentElement.dataset.theme = theme }, theme)
      const marker = page.locator(markerSelector).first()
      const backgrounds = await marker.evaluate((element) => [element, element.querySelector('svg')].map((node) => getComputedStyle(node).backgroundColor))
      expect(backgrounds).toEqual(['rgba(0, 0, 0, 0)', 'rgba(0, 0, 0, 0)'])
    }
    await map.screenshot({ path: testInfo.outputPath('pin-mode.png') })
    await page.getByRole('button', { name: kind === 'private' ? '클러스터 보기' : '클러스터', exact: true }).click()
    await expect(thumbnails).toHaveCount(2)
    expect(fixture.errors).toEqual([])
  })
}

test('zoom 20 retains a group instead of exposing every photo as an individual pin', async ({ page }) => {
  await mountMap(page)
  await expect(page.locator(markerSelector)).toHaveCount(2)
  await page.evaluate(() => {
    window.mapDisplayFixture.displayMode = 'pin'
    window.mapDisplayFixture.focusTarget = { requestId: 1, latitude: 35.6812, longitude: 139.7671, zoom: 20, duration: 0.1 }
  })
  await expect(page.locator('.travel-cluster-map__zoom')).toHaveText('20')
  await expect(page.locator(markerSelector)).toHaveCount(1)
  await expect(page.locator('.travel-map-pin-glyph__count')).toHaveText('3')
  await page.evaluate(() => { window.mapDisplayFixture.displayMode = 'cluster' })
  await expect(page.locator('.travel-map-photo-cluster__count')).toHaveText('3')
})

test('day-grouped journey popup follows the active photo within the group', async ({ page }) => {
  const fixture = await mountMap(page, 'panel', true)
  await page.evaluate(() => {
    window.mapDisplayFixture.journeyPlaybackActive = true
    window.mapDisplayFixture.journeyPhotoId = 2
  })
  const popup = page.locator('.leaflet-popup-content .travel-cluster-popup')
  await expect(popup.locator('strong')).toHaveText('Photo 2')
  await expect(popup.locator('img')).toHaveAttribute('src', /map-group-photo-2/)
  await expect(popup).toContainText('사진 3장')
  await page.evaluate(() => { window.mapDisplayFixture.journeyPhotoId = 3 })
  await expect(popup.locator('strong')).toHaveText(['Photo 3'])
  await expect(popup.locator('img')).toHaveAttribute('src', /map-group-photo-3/)
  await popup.click()
  expect(await page.evaluate(() => window.mapDisplayPreviewEvents)).toEqual([3])
  expect(fixture.errors).toEqual([])
})

test('journey card survives zoom, route-like pans and marker redraws until the next photo', async ({ page }) => {
  const fixture = await mountMap(page, 'panel', true)
  await page.evaluate(() => {
    window.mapDisplayFixture.journeyPlaybackActive = true
    window.mapDisplayFixture.journeyPhotoId = 2
    window.mapDisplayFixture.focusTarget = { requestId: 1, latitude: 35.6812, longitude: 139.7671, autoZoom: true, duration: 0.1 }
  })
  const popup = page.locator('.leaflet-popup-content .travel-cluster-popup')
  await expect(popup.locator('strong')).toHaveText('Photo 2')
  await expect(page.locator('.travel-cluster-map__zoom')).toHaveText('15')

  const retained = await page.evaluate(async () => {
    const card = document.querySelector('.leaflet-popup-content .travel-cluster-popup')
    let removed = false
    const observer = new MutationObserver(() => { if (!card.isConnected) removed = true })
    observer.observe(document.querySelector('.leaflet-popup-pane'), { childList: true, subtree: true })
    for (let i = 0; i < 8; i += 1) {
      window.mapDisplayFixture.focusTarget = { requestId: i + 2, latitude: 35.6812 + i * 0.00004, longitude: 139.7671, keepZoom: true, duration: 0.05 }
      if (i === 3) window.dispatchEvent(new Event('resize'))
      await new Promise((resolve) => setTimeout(resolve, 180))
      if (!card.isConnected) removed = true
    }
    await new Promise((resolve) => setTimeout(resolve, 500))
    observer.disconnect()
    return !removed && card.isConnected && card.querySelector('strong').textContent === 'Photo 2'
  })
  expect(retained).toBeTruthy()
  await page.evaluate(() => { window.mapDisplayFixture.journeyPhotoId = 3 })
  await expect(popup.locator('strong')).toHaveText(['Photo 3'])

  // Explicit dismissal must not be undone by a later moveend/redraw.
  await page.locator('.leaflet-popup-close-button').focus()
  await page.locator('.leaflet-popup-close-button').press('Enter')
  await page.evaluate(() => {
    window.mapDisplayFixture.focusTarget = { requestId: 20, latitude: 35.6813, longitude: 139.7672, keepZoom: true, duration: 0.1 }
  })
  await expect(popup).toHaveCount(0)
  await expect.poll(() => page.evaluate(() => document.querySelector('.travel-cluster-map__zoom')?.textContent)).toBe('15')
  await page.evaluate(() => { window.mapDisplayFixture.journeyPhotoId = 2 })
  await expect(popup.locator('strong')).toHaveText('Photo 2')
  expect(fixture.errors).toEqual([])
})

for (const kind of ['private', 'public']) {
  test(`${kind} fullscreen journey keeps its card for the configured interval and uses contextual zoom`, async ({ page }, testInfo) => {
    const fixture = await mountMap(page, kind)
    await expect(page.locator(markerSelector)).toHaveCount(2)
    await page.getByRole('button', { name: '전체 화면', exact: true }).click()
    const controls = page.locator('.travel-journey-controls')
    await expect(controls).toBeVisible()
    const days = controls.locator('.travel-journey-controls__day-field select')
    await days.selectOption(await days.locator('option').nth(1).getAttribute('value'))
    await controls.locator('.travel-journey-controls__speed select').selectOption('4')
    await controls.getByRole('button', { name: '재생', exact: true }).click()
    const popup = page.locator('.leaflet-popup-content .travel-cluster-popup')
    await expect(popup.locator('strong')).toHaveText('Photo 1')
    await expect(page.locator('.travel-cluster-map__zoom')).toHaveText('15')
    const retained = await page.evaluate(async () => {
      const card = document.querySelector('.leaflet-popup-content .travel-cluster-popup')
      let removed = false
      const observer = new MutationObserver(() => { if (!card.isConnected) removed = true })
      observer.observe(document.querySelector('.leaflet-popup-pane'), { childList: true, subtree: true })
      window.dispatchEvent(new Event('resize'))
      await new Promise((resolve) => setTimeout(resolve, 2400))
      observer.disconnect()
      return !removed && card.isConnected && card.querySelector('strong').textContent === 'Photo 1'
    })
    expect(retained).toBeTruthy()
    await expect(controls.locator('.travel-journey-controls__counter')).toHaveText('1 / 4장')
    await expect(popup.locator('strong')).toHaveText(['Photo 2'], { timeout: 6500 })
    await controls.getByRole('button', { name: '일시정지', exact: true }).click()
    await expect(popup.locator('strong')).toHaveText('Photo 2')
    await expect(page.locator('.travel-cluster-map__zoom')).toHaveText('15')
    const cardCanBeClicked = await popup.evaluate((card) => {
      const bounds = card.getBoundingClientRect()
      const hit = document.elementFromPoint(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2)
      return Boolean(hit && card.contains(hit))
    })
    expect(cardCanBeClicked).toBeTruthy()
    await page.locator('#map-display-test .travel-map').screenshot({ path: testInfo.outputPath('journey-card.png') })
    expect(fixture.errors).toEqual([])
  })
}
