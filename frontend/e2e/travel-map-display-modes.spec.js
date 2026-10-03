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
  const popup = page.locator('.travel-map__preview .travel-map-preview__open')
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
  const popup = page.locator('.travel-map__preview .travel-map-preview__open')
  await expect(popup.locator('strong')).toHaveText('Photo 2')
  await expect(page.locator('.travel-cluster-map__zoom')).toHaveText('15')

  const retained = await page.evaluate(async () => {
    const card = document.querySelector('.travel-map__preview .travel-map-preview__open')
    let removed = false
    const observer = new MutationObserver(() => { if (!card.isConnected) removed = true })
    observer.observe(document.querySelector('.travel-map__preview'), { childList: true, subtree: true })
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
  await page.locator('.travel-map-preview__header button').focus()
  await page.locator('.travel-map-preview__header button').press('Enter')
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
    const speed = controls.getByRole('slider', { name: '여정 재생 간격', exact: true })
    await speed.focus()
    await speed.press('Home')
    for (let step = 1; step < 4; step++) await speed.press('ArrowRight')
    await expect(speed).toHaveValue('4')
    await controls.getByRole('button', { name: '재생', exact: true }).click()
    const popup = page.locator('.travel-map__preview .travel-map-preview__open')
    await expect(popup.locator('strong')).toHaveText('Photo 1')
    await expect(page.locator('.travel-cluster-map__zoom')).toHaveText('15')
    const retained = await page.evaluate(async () => {
      const card = document.querySelector('.travel-map__preview .travel-map-preview__open')
      let removed = false
      const observer = new MutationObserver(() => { if (!card.isConnected) removed = true })
      observer.observe(document.querySelector('.travel-map__preview'), { childList: true, subtree: true })
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

async function dragJourneySpeed(page, slider, seconds) {
  await slider.scrollIntoViewIfNeeded()
  const box = await slider.boundingBox()
  const current = Number(await slider.inputValue())
  const thumbRadius = 8.5
  const position = (value) => box.x + thumbRadius + (box.width - thumbRadius * 2) * (value - 1) / 4
  await page.mouse.move(position(current), box.y + box.height / 2)
  await page.mouse.down()
  await page.mouse.move(position(seconds), box.y + box.height / 2, { steps: 8 })
  await page.mouse.up()
  await expect(slider).toHaveValue(String(seconds))
  await expect(slider).toHaveAttribute('aria-valuetext', `${seconds}초`)
}

async function expectPreviewMapLayout(page, isMobile) {
  const layout = await page.evaluate(() => {
    const stage = document.querySelector('#map-display-test .travel-map__stage').getBoundingClientRect()
    const preview = document.querySelector('.travel-map__preview').getBoundingClientRect()
    const canvas = document.querySelector('#map-display-test .travel-map__canvas').getBoundingClientRect()
    return { stage: { width: stage.width }, preview: { x: preview.x, width: preview.width, y: preview.y }, canvas: { x: canvas.x, width: canvas.width, bottom: canvas.bottom } }
  })
  if (isMobile) {
    expect(Math.abs(layout.canvas.width - layout.stage.width)).toBeLessThan(2)
    expect(layout.preview.y).toBeGreaterThanOrEqual(layout.canvas.bottom - 1)
  } else {
    expect(Math.abs(layout.preview.width - layout.stage.width / 4)).toBeLessThan(2)
    expect(Math.abs(layout.canvas.width - layout.stage.width * 3 / 4)).toBeLessThan(2)
    expect(Math.abs(layout.canvas.x - layout.preview.x - layout.preview.width)).toBeLessThan(2)
  }
  await expect.poll(() => page.evaluate(() => {
    const canvas = document.querySelector('#map-display-test .travel-map__canvas').getBoundingClientRect()
    const marker = document.querySelector('.leaflet-marker-icon[title^="Photo 1"]')
    if (!marker) return 9999
    const pin = marker.getBoundingClientRect()
    const style = getComputedStyle(marker)
    const x = pin.x - parseFloat(style.marginLeft)
    const y = pin.y - parseFloat(style.marginTop)
    return Math.max(Math.abs(x - canvas.x - canvas.width / 2), Math.abs(y - canvas.y - canvas.height / 2))
  })).toBeLessThan(4)
  await expect.poll(() => page.locator('.leaflet-marker-icon[title^="Photo 1"]').evaluate((marker) => {
    const pin = marker.getBoundingClientRect()
    const hit = document.elementFromPoint(pin.x + pin.width / 2, pin.y + pin.height / 2)
    return Boolean(hit && marker.contains(hit))
  })).toBe(true)
}

for (const kind of ['private', 'public']) {
  test(`${kind} pin and cluster open a larger preview beside a centered map`, async ({ page, isMobile }, testInfo) => {
    const fixture = await mountMap(page, kind)
    for (const mode of ['cluster', 'pin']) {
      if (mode === 'pin') await page.getByRole('button', { name: kind === 'private' ? '핀 보기' : '핀', exact: true }).click()
      await expect(page.locator('.leaflet-marker-icon[title^="Photo 1"]')).toBeVisible()
      await page.locator('.leaflet-marker-icon[title^="Photo 1"]').click()
      const preview = page.getByRole('region', { name: '선택한 여행 사진 미리보기', exact: true })
      await expect(preview).toBeVisible()
      await expect(preview.locator('strong')).toHaveText('Photo 1')
      await expect(preview.locator('img')).toHaveAttribute('src', /thumbnail=true.*w=480/)
      await expect(page.locator('[data-map-photo-detail="true"]')).toHaveCount(0)
      await expectPreviewMapLayout(page, isMobile)
      if (!isMobile) {
        await page.setViewportSize({ width: 1920, height: 1080 })
        await expectPreviewMapLayout(page, false)
        expect((await preview.locator('img').boundingBox()).width).toBeGreaterThan(240)
      }
      await page.getByRole('button', { name: '전체 화면', exact: true }).click()
      await expectPreviewMapLayout(page, isMobile)
      await expect(page.locator('.travel-map-inspector--fullscreen')).toHaveCount(0)
      for (const theme of ['default', 'toss']) {
        await page.evaluate((value) => document.documentElement.dataset.theme = value, theme)
        await expect(preview.locator('strong')).toHaveCSS('-webkit-text-fill-color', await preview.locator('strong').evaluate((element) => getComputedStyle(element).color))
      }
      await page.locator('#map-display-test .travel-map').screenshot({ path: testInfo.outputPath(`${mode}-split-preview.png`) })
      await preview.getByRole('button', { name: '사진 크게 보기', exact: true }).click()
      await expect(page.locator('[data-map-photo-detail="true"]')).toBeVisible()
      await page.locator('[data-map-photo-detail="true"] [data-modal-close]').click()
      await preview.getByRole('button', { name: '사진 미리보기 닫기', exact: true }).click()
      await expect(preview).toHaveCount(0)
      await expect.poll(() => page.evaluate(() => {
        const canvas = document.querySelector('#map-display-test .travel-map__canvas').getBoundingClientRect()
        const stage = document.querySelector('#map-display-test .travel-map__stage').getBoundingClientRect()
        return Math.abs(canvas.width - stage.width)
      })).toBeLessThan(2)
      await page.getByRole('button', { name: '전체 화면 종료', exact: true }).click()
    }
    expect(fixture.errors).toEqual([])
  })
}

test('preview falls back safely when a thumbnail fails and resets for the next photo', async ({ page }) => {
  const fixture = await mountMap(page, 'panel', true)
  await page.route('**/map-group-photo-2.png*', (route) => {
    return new URL(route.request().url()).searchParams.has('thumbnail')
      ? route.fulfill({ status: 404, body: '' })
      : route.fulfill({ contentType: 'image/png', body: image })
  })
  await page.route('**/map-group-photo-3.png*', (route) => route.fulfill({ status: 404, body: '' }))
  await page.evaluate(() => {
    window.mapDisplayFixture.journeyPlaybackActive = true
    window.mapDisplayFixture.journeyPhotoId = 2
  })
  const preview = page.getByRole('region', { name: '선택한 여행 사진 미리보기', exact: true })
  await expect(preview.locator('img')).toHaveAttribute('src', '/map-group-photo-2.png')
  await expect.poll(() => preview.locator('img').evaluate((img) => img.complete && img.naturalWidth > 0)).toBe(true)
  await page.evaluate(() => { window.mapDisplayFixture.journeyPhotoId = 3 })
  await expect(preview).toContainText('사진 미리보기를 불러올 수 없습니다.')
  await expect(preview.locator('strong')).toHaveText('Photo 3')
  await page.evaluate(() => { window.mapDisplayFixture.journeyPhotoId = 1 })
  await expect(preview.locator('img')).toHaveAttribute('src', /map-group-photo-1.*thumbnail=true.*w=480/)
  await expect(preview.locator('strong')).toHaveText('Photo 1')
  expect(fixture.errors).toEqual([])
})

for (const kind of ['private', 'public']) {
  test(`${kind} journey speed slider selects each second and retimes active playback`, async ({ page }, testInfo) => {
    const fixture = await mountMap(page, kind)
    await expect(page.locator(markerSelector)).toHaveCount(2)
    await page.getByRole('button', { name: '전체 화면', exact: true }).click()
    const controls = page.locator('.travel-journey-controls')
    const slider = controls.getByRole('slider', { name: '여정 재생 간격', exact: true })
    await expect(slider).toHaveAttribute('min', '1')
    await expect(slider).toHaveAttribute('max', '5')
    await expect(slider).toHaveAttribute('step', '1')
    await expect(controls.locator('.travel-journey-controls__speed-ticks')).toHaveText('12345')
    for (const seconds of [1, 2, 3, 4, 5]) {
      await dragJourneySpeed(page, slider, seconds)
      await expect(controls.locator('output')).toHaveText(`${seconds}초`)
    }
    const days = controls.locator('.travel-journey-controls__day-field select')
    await days.selectOption(await days.locator('option').nth(1).getAttribute('value'))
    await controls.getByRole('button', { name: '재생', exact: true }).click()
    const popup = page.locator('.travel-map__preview .travel-map-preview__open')
    await expect(popup.locator('strong')).toHaveText('Photo 1')
    await dragJourneySpeed(page, slider, 1)
    await expect(controls.locator('.travel-journey-controls__counter')).not.toHaveText('1 / 4장', { timeout: 4000 })
    await controls.getByRole('button', { name: '일시정지', exact: true }).click()
    await slider.focus()
    await slider.press('End')
    await slider.press('ArrowRight')
    await expect(slider).toHaveValue('5')
    await slider.press('Home')
    await slider.press('ArrowLeft')
    await expect(slider).toHaveValue('1')
    expect(await controls.evaluate((element) => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
    for (const theme of ['default', 'toss']) {
      await page.evaluate((value) => document.documentElement.dataset.theme = value, theme)
      await expect.poll(() => slider.evaluate((element) => getComputedStyle(element).backgroundColor)).toBe('rgba(0, 0, 0, 0)')
      const sliderStyle = await slider.evaluate((element) => {
        const style = getComputedStyle(element)
        return { background: style.backgroundColor, shadow: style.boxShadow, height: element.getBoundingClientRect().height }
      })
      expect(sliderStyle.background).toBe('rgba(0, 0, 0, 0)')
      expect(sliderStyle.shadow).toBe('none')
      expect(sliderStyle.height).toBeLessThanOrEqual(32)
      await expect(controls.locator('output')).toHaveCSS('color', 'rgb(248, 250, 252)')
      await expect(controls.locator('.travel-journey-controls__speed-ticks .is-selected')).toHaveCSS('color', 'rgb(134, 239, 172)')
      await dragJourneySpeed(page, slider, 3)
      await expect(controls.locator('output')).toHaveText('3초')
      await controls.screenshot({ path: testInfo.outputPath(`journey-speed-slider-${theme}.png`) })
    }
    expect(fixture.errors).toEqual([])
  })
}
