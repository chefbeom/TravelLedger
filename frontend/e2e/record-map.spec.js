import { expect, test } from '@playwright/test'

const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6x1sAAAAASUVORK5CYII=', 'base64')
const token = 'a'.repeat(43)

function fixture(withVisits = false) {
  return {
    collections: [{ id: 10, name: '주말 맛집', memo: '비공개 모음 메모', colorHex: '#009E8C' }],
    places: withVisits ? [
      { id: 1, name: '서울 카페', latitude: 37.55, longitude: 126.97, visited: true, collectionIds: [10] },
      { id: 2, name: '도쿄 산책', latitude: 35.6812, longitude: 139.7671, visited: true, collectionIds: [10] },
      { id: 3, name: '가고 싶은 서점', latitude: 35.70, longitude: 139.79, visited: false, collectionIds: [] },
    ] : [],
    visits: withVisits ? [
      { id: 'visit:1', placeId: 1, title: '첫날 사진', date: '2026-10-12', time: '09:00:00', memo: '첫날 기록', photos: [{ id: 'photo:1', name: 'first.png', photoUrl: '/api/record-map/photos/1/content' }] },
      { id: 'visit:2', placeId: 2, title: '다음날 사진', date: '2026-10-13', time: '12:00:00', memo: '둘째 날', photos: [{ id: 'photo:2', name: 'second.png', photoUrl: '/api/record-map/photos/2/content' }] },
      { id: 'visit:3', placeId: 2, title: '사진 없는 산책', date: '2026-10-13', time: '15:00:00', memo: '산책 기록', photos: [] },
    ] : [],
    shares: [], requests: [], errors: [],
  }
}

function overview(state, url, shared = false) {
  const link = state.shares.find(s => s.active)
  const collectionId = shared ? link?.collectionId : Number(url.searchParams.get('collectionId'))
  const status = url.searchParams.get('state') || 'ALL'
  const from = url.searchParams.get('from'), to = url.searchParams.get('to')
  const places = state.places.map(p => ({ ...p, visited: p.visited || state.visits.some(v => v.placeId === p.id),
    visitCount: state.visits.filter(v => v.placeId === p.id).length }))
    .filter(p => (!collectionId || p.collectionIds.includes(collectionId))
      && (status === 'ALL' || (status === 'VISITED') === p.visited)
      && ((!from && !to) || state.visits.some(v => v.placeId === p.id && (!from || v.date >= from) && (!to || v.date <= to))))
  const visits = shared && !link?.includeVisits ? [] : state.visits.filter(v => places.some(p => p.id === v.placeId)
    && (!from || v.date >= from) && (!to || v.date <= to))
  return { title: shared ? '주말 맛집' : '기록 지도', readOnly: shared, visitsIncluded: !shared || Boolean(link?.includeVisits),
    collections: shared ? state.collections.filter(c => c.id === collectionId).map(c => ({ ...c, memo: null })) : state.collections,
    places: places.map(p => shared ? { ...p, memo: link?.includeVisits ? p.memo : null, collectionIds: [collectionId] } : p),
    visits, totalPlaces: places.length, page: 0, totalPages: places.length ? 1 : 0, visitsLimited: false }
}

async function mock(page, state, authenticated = true) {
  page.on('pageerror', error => state.errors.push(error.message))
  await page.route(/^https?:\/\/(?!127\.0\.0\.1|localhost)/, route => route.abort())
  await page.route('**/api/**', route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname, method = request.method()
    const body = request.headers()['content-type']?.includes('application/json') && request.postData() ? request.postDataJSON() : null
    state.requests.push({ path, method, body, csrf: request.headers()['x-xsrf-token'] })
    if (path === '/api/auth/me') return route.fulfill(authenticated ? { json: { id: 7, loginId: 'test-only-owner', displayName: '기록 지도 검증', active: true, admin: false } } : { status: 401, json: {} })
    if (path === '/api/auth/csrf') return route.fulfill({ json: { token: 'test-only-csrf' } })
    if (path === '/api/auth/registration-options') return route.fulfill({ json: { publicRegistrationEnabled: false, socialLoginProviders: [] } })
    if (path === '/api/record-map/overview') return route.fulfill({ json: overview(state, url) })
    if (path.includes('/content')) return route.fulfill({ contentType: 'image/png', body: png })
    if (path === '/api/record-map/places' && method === 'POST') {
      const id = Math.max(0, ...state.places.map(p => p.id)) + 1
      state.places.push({ id, ...body }); return route.fulfill({ json: { id } })
    }
    if (/\/places\/\d+$/.test(path) && method === 'PUT') {
      const id = Number(path.split('/').at(-1)); Object.assign(state.places.find(p => p.id === id), body)
      return route.fulfill({ json: { id } })
    }
    if (/\/places\/\d+\/visits$/.test(path)) {
      const placeId = Number(path.split('/').at(-2))
      if (method === 'GET') return route.fulfill({ json: state.visits.filter(v => v.placeId === placeId && v.id.startsWith('visit:')) })
      const id = state.visits.length + 1
      state.visits.push({ id: 'visit:' + id, placeId, ...body, photos: [] })
      return route.fulfill({ json: { id } })
    }
    if (/\/visits\/\d+\/photos$/.test(path) && method === 'POST') {
      const visit = state.visits.find(v => v.id === 'visit:' + path.split('/').at(-2)), id = 'photo:' + (state.visits.flatMap(v => v.photos).length + 1)
      const photo = { id, name: 'uploaded.png', photoUrl: '/api/record-map/photos/' + id.split(':')[1] + '/content' }
      visit.photos.push(photo); return route.fulfill({ json: photo })
    }
    if (path === '/api/record-map/collections' && method === 'POST') {
      const id = state.collections.length + 10; state.collections.push({ id, ...body }); return route.fulfill({ json: { id, ...body } })
    }
    if (/\/collections\/\d+$/.test(path) && method === 'DELETE') {
      const id = Number(path.split('/').at(-1))
      state.collections = state.collections.filter(c => c.id !== id)
      state.places.forEach(p => { p.collectionIds = p.collectionIds.filter(c => c !== id) })
      state.shares.filter(s => s.collectionId === id).forEach(s => { s.active = false })
      return route.fulfill({ status: 204 })
    }
    if (path === '/api/record-map/travel-candidates') return route.fulfill({ json: { number: 0, totalPages: 1,
      content: [{ id: 900, planId: 90, planName: '기존 여행', title: '여행에서 찾은 장소', date: '2026-10-13', latitude: 37.55, longitude: 126.97 }] } })
    if (path === '/api/record-map/travel-imports' && method === 'POST') {
      const id = state.places.length + 1
      state.places.push({ id, name: '여행에서 찾은 장소', latitude: 37.55, longitude: 126.97, collectionIds: body.collectionIds, sourceTravelRecordId: 900, visited: true })
      state.visits.push({ id: 'travel:900', placeId: id, title: '원본 여행 기록', date: '2026-10-13', time: '12:00:00', memo: '원본 그대로', travelPlanId: 90, travelPlanName: '기존 여행', photos: [] })
      return route.fulfill({ json: { id } })
    }
    if (/\/collections\/\d+\/shares$/.test(path)) {
      const collectionId = Number(path.split('/').at(-2))
      if (method === 'GET') return route.fulfill({ json: state.shares.filter(s => s.collectionId === collectionId).map(s => ({ ...s, token: null })) })
      const link = { id: state.shares.length + 1, collectionId, ...body, active: true, expiresAt: '2026-10-20T00:00:00' }
      state.shares.push(link); return route.fulfill({ json: { ...link, token } })
    }
    if (/\/shares\/\d+$/.test(path) && method === 'DELETE') {
      state.shares.find(s => s.id === Number(path.split('/').at(-1))).active = false
      return route.fulfill({ status: 204 })
    }
    if (path === '/api/record-map/public/' + token) {
      return state.shares.some(s => s.active) ? route.fulfill({ json: overview(state, url, true) })
        : route.fulfill({ status: 404, json: { message: '공유 자료를 찾을 수 없습니다.' } })
    }
    if (path === '/api/travel/my-map') return route.fulfill({ json: { includedPlanCount: 0, markers: [], routes: [], photoPins: [], photoClusters: [] } })
    if (path === '/api/record-shares/counts') return route.fulfill({ json: { ledger: 0, travel: 0 } })
    if (/preferences|pet|statistics|recent|groups|plans|photos/.test(path)) return route.fulfill({ json: [] })
    return route.fulfill({ json: {} })
  })
  await page.route(/^https:\/\/nominatim\.openstreetmap\.org\/search/, route => route.fulfill({ json: [
    { place_id: 500, name: '서울역', display_name: '서울역, 서울, 대한민국', lat: '37.556', lon: '126.971', address: { country: '대한민국', city: '서울' } },
  ] }))
}

test('a date-free bookmark is saved without a trip and rendered as a transparent map pin', async ({ page }, testInfo) => {
  const state = fixture()
  await mock(page, state)
  await page.goto('/#travel')
  await page.getByRole('button', { name: '장소 저장', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '장소 저장', exact: true })
  await dialog.getByLabel('장소 이름', { exact: true }).fill('아직 안 간 도쿄 서점')
  await dialog.getByLabel('위도', { exact: true }).fill('35.6812')
  await dialog.getByLabel('경도', { exact: true }).fill('139.7671')
  await dialog.getByLabel('주말 맛집', { exact: true }).check()
  await dialog.getByRole('button', { name: '장소 저장', exact: true }).click()
  await expect(dialog).not.toBeVisible()
  const marker = page.locator('.leaflet-marker-pane .leaflet-marker-icon')
  await expect(marker).toHaveCount(1)
  for (const theme of ['default', 'toss']) {
    await page.evaluate(theme => document.documentElement.dataset.theme = theme, theme)
    await expect.poll(() => marker.evaluate(el => getComputedStyle(el).backgroundColor)).toBe('rgba(0, 0, 0, 0)')
  }
  await marker.click()
  const preview = page.locator('.travel-map__preview')
  await expect(preview).toContainText('아직 안 간 도쿄 서점')
  await expect(preview).toContainText('기록 0건')
  await preview.getByRole('button', { name: '사진 크게 보기', exact: true }).click()
  await expect(page.getByRole('dialog', { name: '아직 안 간 도쿄 서점', exact: true })).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).not.toBeVisible()
  await expect(preview).toBeVisible()
  expect(state.requests.filter(r => r.path === '/api/record-map/places' && r.method === 'POST')[0].csrf).toBe('test-only-csrf')
  expect(state.requests.some(r => r.path === '/api/travel/plans' && r.method === 'POST')).toBe(false)
  await page.locator('.record-map-workspace').screenshot({ path: testInfo.outputPath('bookmark-map.png') })
  expect(state.errors).toEqual([])
})

test('a dated visit and photo can be added to an existing bookmark', async ({ page }) => {
  const state = fixture()
  state.places.push({ id: 1, name: '동네 카페', latitude: 37.55, longitude: 126.97, visited: false, collectionIds: [10] })
  await mock(page, state)
  await page.goto('/#travel')
  await page.getByRole('button', { name: '방문 기록 남기기', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '방문 기록 남기기', exact: true })
  await dialog.getByLabel('제목', { exact: true }).fill('첫 방문')
  await dialog.getByLabel('날짜', { exact: true }).fill('2026-10-13')
  await dialog.getByLabel('메모', { exact: true }).fill('좋았던 점을 기록')
  await dialog.getByLabel('첨부 사진', { exact: true }).setInputFiles({ name: 'test-only.png', mimeType: 'image/png', buffer: png })
  await dialog.getByRole('button', { name: '방문 기록 저장', exact: true }).click()
  await expect(dialog).not.toBeVisible()
  await expect(page.locator('.travel-map-photo-cluster')).toHaveCount(1)
  await page.getByRole('button', { name: '일상 기록', exact: true }).click()
  await expect(page.locator('.record-map-diary')).toContainText('첫 방문')
  await expect(page.locator('.record-map-diary img')).toHaveCount(1)
  await page.getByRole('combobox', { name: '방문 상태', exact: true }).selectOption('WANTED')
  await expect(page.locator('.record-map-empty')).toBeVisible()
  await page.getByRole('combobox', { name: '방문 상태', exact: true }).selectOption('VISITED')
  await expect(page.locator('.record-map-diary')).toContainText('첫 방문')
  expect(state.errors).toEqual([])
})

test('dated playback pauses, resumes, changes days and includes a no-photo visit', async ({ page }, testInfo) => {
  const state = fixture(true)
  await mock(page, state)
  await page.goto('/#travel')
  await page.getByLabel('기록 재생 간격', { exact: true }).focus()
  await page.getByLabel('기록 재생 간격', { exact: true }).press('Home')
  await page.getByLabel('기록 재생 간격', { exact: true }).press('Tab')
  await page.getByRole('button', { name: '재생', exact: true }).click()
  const preview = page.locator('.travel-map__preview')
  await expect(preview.locator('strong')).toHaveText('첫날 사진')
  await page.getByRole('button', { name: '일시정지', exact: true }).click()
  await expect(preview.locator('strong')).toHaveText('첫날 사진')
  await expect(page.getByRole('button', { name: '다시 재생', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '다시 재생', exact: true }).click()
  await expect(page.locator('.record-map-playback')).toContainText('2026-10-13 이어보기', { timeout: 12_000 })
  await expect(preview.locator('strong')).toHaveText('다음날 사진', { timeout: 18_000 })
  await expect(page.locator('.travel-cluster-map__zoom')).toHaveText('16')
  await expect(preview.locator('strong')).toHaveText('사진 없는 산책', { timeout: 10_000 })
  await expect(page.getByText('현재 지도에 표시된 기록 재생이 끝났습니다.', { exact: true })).toBeVisible()
  expect(state.requests.some(r => r.method !== 'GET' && r.path.startsWith('/api/record-map'))).toBe(false)
  await page.locator('.travel-map').screenshot({ path: testInfo.outputPath('record-playback.png') })
  expect(state.errors).toEqual([])
})

test('fullscreen detail closes one layer and the mobile form stays inside the viewport', async ({ page }, testInfo) => {
  const state = fixture(true)
  await mock(page, state)
  await page.goto('/#travel')
  await page.getByRole('button', { name: '전체 화면', exact: true }).click()
  await expect(page.locator('.travel-map')).toHaveClass(/travel-map--fullscreen/)
  await expect(page.locator('.pet-companion')).not.toBeVisible()
  const marker = page.locator('.leaflet-marker-pane .leaflet-marker-icon').first()
  await marker.click()
  const preview = page.locator('.travel-map__preview')
  await preview.getByRole('button', { name: '사진 크게 보기', exact: true }).click()
  const detail = page.locator('dialog.record-map-dialog')
  await expect(detail).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(detail).toBeVisible() // photo -> place details
  await expect(detail.locator('h2')).toHaveText('서울 카페')
  await page.keyboard.press('Escape')
  await expect(detail).not.toBeVisible()
  await expect(page.locator('.travel-map')).toHaveClass(/travel-map--fullscreen/)
  await page.getByRole('button', { name: '전체 화면 종료', exact: true }).click()
  await page.getByRole('button', { name: '장소 저장', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '장소 저장', exact: true })
  const box = await dialog.boundingBox(), viewport = page.viewportSize()
  expect(box.x).toBeGreaterThanOrEqual(0); expect(box.x + box.width).toBeLessThanOrEqual(viewport.width + 1)
  expect(box.y).toBeGreaterThanOrEqual(0); expect(box.y + box.height).toBeLessThanOrEqual(viewport.height + 1)
  const controlBounds = await dialog.locator('input, select, textarea, .travel-map__toolbar-button').evaluateAll(nodes => nodes.map(node => {
    const rect = node.getBoundingClientRect(); return { left: rect.left, right: rect.right }
  }))
  for (const control of controlBounds) {
    expect(control.left).toBeGreaterThanOrEqual(box.x)
    expect(control.right).toBeLessThanOrEqual(box.x + box.width)
  }
  const searchButton = dialog.getByRole('button', { name: '검색', exact: true })
  const searchColors = await searchButton.evaluate(node => {
    const style = getComputedStyle(node); return { foreground: style.webkitTextFillColor || style.color, background: style.backgroundColor, textToken: style.getPropertyValue('--text'), tossToken: style.getPropertyValue('--toss-text') }
  })
  const luminance = color => {
    const rgb = color.match(/[\d.]+/g).slice(0, 3).map(Number).map(v => v / 255).map(v => v <= .04045 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4)
    return rgb[0] * .2126 + rgb[1] * .7152 + rgb[2] * .0722
  }
  const foreground = luminance(searchColors.foreground), background = luminance(searchColors.background)
  expect((Math.max(foreground, background) + .05) / (Math.min(foreground, background) + .05), JSON.stringify(searchColors)).toBeGreaterThanOrEqual(4.5)
  await dialog.getByLabel('장소 이름', { exact: true }).fill('검색만 하는 장소')
  await dialog.getByLabel('위도', { exact: true }).fill('37.55')
  await dialog.getByLabel('경도', { exact: true }).fill('126.97')
  await dialog.getByRole('searchbox').fill('서울역')
  await dialog.getByRole('searchbox').press('Enter')
  await expect(searchButton).not.toBeDisabled()
  await dialog.locator('.travel-map__search-result').first().click()
  await expect(dialog.getByLabel('위도', { exact: true })).toHaveValue('37.556')
  await expect(dialog.getByLabel('경도', { exact: true })).toHaveValue('126.971')
  await expect(dialog).toBeVisible()
  expect(state.requests.filter(r => r.path === '/api/record-map/places' && r.method === 'POST')).toHaveLength(0)
  await dialog.screenshot({ path: testInfo.outputPath('place-form.png') })
  page.once('dialog', confirmation => confirmation.accept())
  await dialog.getByRole('button', { name: '창 닫기', exact: true }).click()
  await expect.poll(() => page.evaluate(() => document.body.style.position)).not.toBe('fixed')
  expect(state.errors).toEqual([])
})

test('a collection share is private by default, read-only, and revocable', async ({ page, context }) => {
  const state = fixture(true)
  await mock(page, state)
  await page.goto('/#travel')
  await page.getByRole('button', { name: '모음 공유', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '모음 지도 공유', exact: true })
  await expect(dialog.getByRole('checkbox')).not.toBeChecked()
  await dialog.getByRole('button', { name: '공개 공유 링크 만들기', exact: true }).click()
  await expect(dialog.getByLabel('생성된 공유 주소', { exact: true })).toHaveValue(new RegExp('#record-map-share/' + token))
  const visitor = await context.newPage()
  await mock(visitor, state, false)
  await visitor.goto('/#record-map-share/' + token)
  await expect(visitor.locator('.record-map-workspace h2')).toHaveText('주말 맛집')
  for (const name of ['장소 저장', '방문 기록 남기기', '모음 관리', '모음 공유']) await expect(visitor.getByRole('button', { name, exact: true })).toHaveCount(0)
  await expect(visitor.locator('.travel-map-photo-cluster')).toHaveCount(0)
  await expect(visitor.getByLabel('기록 시작일', { exact: true })).toHaveCount(0)
  await expect(visitor.getByText('비공개 모음 메모', { exact: true })).toHaveCount(0)
  page.once('dialog', dialog => dialog.accept())
  await dialog.getByRole('button', { name: '공유 종료', exact: true }).click()
  await visitor.getByRole('button', { name: '새로고침', exact: true }).click()
  await expect(visitor.getByRole('alert')).toContainText('공유 자료를 찾을 수 없습니다.')
  await expect(visitor.locator('.leaflet-marker-pane .leaflet-marker-icon')).toHaveCount(0)
  await expect(visitor.locator('dialog.record-map-dialog')).toHaveCount(0)
  expect(state.requests.filter(r => r.path === '/api/record-map/collections/10/shares' && r.method === 'POST')[0].body.includeVisits).toBe(false)
  expect(state.errors).toEqual([])
  await visitor.close()
})

test('a place can belong to several collections and collection deletion keeps its visits', async ({ page }) => {
  const state = fixture(true)
  await mock(page, state)
  await page.goto('/#travel')
  await page.getByRole('button', { name: '모음 관리', exact: true }).click()
  const collections = page.getByRole('dialog', { name: '내 장소 모음', exact: true })
  await collections.getByLabel('이름', { exact: true }).fill('산책 기록 모음')
  await collections.getByRole('button', { name: '모음 저장', exact: true }).click()
  await expect(collections.locator('article').filter({ hasText: '산책 기록 모음' })).toBeVisible()
  await collections.getByRole('button', { name: '창 닫기', exact: true }).click()
  await page.getByRole('button', { name: '장소 목록', exact: true }).click()
  await page.locator('.record-map-place-grid article').filter({ hasText: '서울 카페' }).getByRole('button', { name: '방문 기록 보기', exact: true }).click()
  await page.getByRole('button', { name: '장소·모음 수정', exact: true }).click()
  const place = page.getByRole('dialog', { name: '장소 수정', exact: true })
  await place.getByLabel('산책 기록 모음', { exact: true }).check()
  await place.getByRole('button', { name: '장소 저장', exact: true }).click()
  await expect(place).not.toBeVisible()
  expect(state.places[0].collectionIds).toEqual([10, 11])
  await page.getByRole('button', { name: '모음 관리', exact: true }).click()
  page.once('dialog', dialog => dialog.accept())
  await collections.locator('article').filter({ hasText: '주말 맛집' }).getByRole('button', { name: '모음 삭제', exact: true }).click()
  await expect(collections.locator('article').filter({ hasText: '주말 맛집' })).toHaveCount(0)
  await collections.getByRole('button', { name: '창 닫기', exact: true }).click()
  await page.getByRole('combobox', { name: '장소 모음', exact: true }).selectOption('11')
  await expect(page.locator('.record-map-place-grid')).toContainText('서울 카페')
  expect(state.places[0].collectionIds).toEqual([11])
  expect(state.visits[0].photos).toHaveLength(1)
  expect(state.errors).toEqual([])
})

test('an opted-in public map includes only the selected collection photos and dates', async ({ page }) => {
  const state = fixture(true)
  state.shares.push({ id: 1, collectionId: 10, active: true, includeVisits: true })
  await mock(page, state, false)
  await page.goto('/#record-map-share/' + token)
  await expect(page.locator('.travel-map-photo-cluster')).toHaveCount(2)
  await expect(page.getByRole('textbox', { name: '기록 시작일', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '장소 목록', exact: true }).click()
  await expect(page.locator('.record-map-place-grid')).not.toContainText('가고 싶은 서점')
  await page.getByRole('button', { name: '일상 기록', exact: true }).click()
  await expect(page.locator('.record-map-diary')).toContainText('첫날 기록')
  await expect(page.locator('.record-map-diary img')).toHaveCount(2)
  expect(state.requests.some(r => r.method !== 'GET')).toBe(false)
  expect(state.errors).toEqual([])
})

test('owned travel places are linked into collections without a write to the original travel API', async ({ page }) => {
  const state = fixture()
  await mock(page, state)
  await page.goto('/#travel')
  await page.getByRole('button', { name: '여행 장소 가져오기', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '여행 장소를 모음에 추가', exact: true })
  await dialog.getByLabel('주말 맛집', { exact: true }).check()
  await dialog.getByRole('button', { name: '장소 가져오기', exact: true }).click()
  await expect(dialog).not.toBeVisible()
  await page.getByRole('button', { name: '일상 기록', exact: true }).click()
  await expect(page.locator('.record-map-diary')).toContainText('원본 여행 기록')
  await expect(page.locator('.record-map-diary')).toContainText('원본 그대로')
  expect(state.requests.find(r => r.path === '/api/record-map/travel-imports').body).toEqual({ recordId: 900, collectionIds: [10] })
  expect(state.requests.some(r => r.path.startsWith('/api/travel/') && r.method !== 'GET')).toBe(false)
  expect(state.errors).toEqual([])
})
