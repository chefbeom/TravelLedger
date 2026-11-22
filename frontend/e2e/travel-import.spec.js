import { expect, test } from '@playwright/test'

// Fixtures are built inside the tests (no files in the repository): EXIF JPEGs are canvas JPEGs with a
// hand-written APP1 segment, GPX files are plain strings.

function exifSegment({ lat, lon, dateTime }) { // dateTime 'YYYY:MM:DD HH:mm:ss', north latitude / east longitude only
  const t = Buffer.alloc(178)
  const entry = (at, tag, type, count, value) => { t.writeUInt16LE(tag, at); t.writeUInt16LE(type, at + 2); t.writeUInt32LE(count, at + 4); t.writeUInt32LE(value, at + 8) }
  const ref = (at, tag, text) => { t.writeUInt16LE(tag, at); t.writeUInt16LE(2, at + 2); t.writeUInt32LE(2, at + 4); t.write(text + '\0', at + 8, 'ascii') }
  const dms = (at, v) => {
    const d = Math.floor(v), mf = (v - d) * 60, m = Math.floor(mf), s = Math.round((mf - m) * 60000)
    ;[[d, 1], [m, 1], [s, 1000]].forEach(([n, den], i) => { t.writeUInt32LE(n, at + i * 8); t.writeUInt32LE(den, at + i * 8 + 4) })
  }
  t.write('II', 0, 'ascii'); t.writeUInt16LE(42, 2); t.writeUInt32LE(8, 4)
  t.writeUInt16LE(2, 8); entry(10, 0x8769, 4, 1, 38); entry(22, 0x8825, 4, 1, 76); t.writeUInt32LE(0, 34)
  t.writeUInt16LE(1, 38); entry(40, 0x9003, 2, 20, 56); t.writeUInt32LE(0, 52); t.write(dateTime + '\0', 56, 'ascii')
  t.writeUInt16LE(4, 76); ref(78, 0x0001, 'N'); entry(90, 0x0002, 5, 3, 130); ref(102, 0x0003, 'E'); entry(114, 0x0004, 5, 3, 154); t.writeUInt32LE(0, 126)
  dms(130, lat); dms(154, lon)
  const payload = Buffer.concat([Buffer.from('Exif\0\0', 'binary'), t])
  const head = Buffer.alloc(4); head.writeUInt16BE(0xFFE1, 0); head.writeUInt16BE(payload.length + 2, 2)
  return Buffer.concat([head, payload])
}
const withExif = (jpeg, meta) => Buffer.concat([jpeg.subarray(0, 2), exifSegment(meta), jpeg.subarray(2)])

async function canvasImage(page, type, seed = 0) {
  const base64 = await page.evaluate(({ type, seed }) => {
    const canvas = document.createElement('canvas')
    canvas.width = 96; canvas.height = 64
    const context = canvas.getContext('2d')
    context.fillStyle = `hsl(${seed * 70 % 360} 60% 55%)`
    context.fillRect(0, 0, 96, 64)
    context.fillStyle = '#fff'
    context.fillRect(10 + seed * 4, 10, 30, 20)
    return canvas.toDataURL(type, 0.8).split(',')[1]
  }, { type, seed })
  return Buffer.from(base64, 'base64')
}

function gpxFile(name, { start, points = 60, stepSeconds = 20, lat = 34.967, lon = 135.772, latStep = 0.00005 }) {
  const first = new Date(start).getTime()
  const rows = Array.from({ length: points }, (_, i) => {
    const time = new Date(first + i * stepSeconds * 1000).toISOString()
    return `<trkpt lat="${(lat + i * latStep).toFixed(6)}" lon="${(lon + i * 0.00002).toFixed(6)}"><time>${time}</time></trkpt>`
  })
  return { name, mimeType: 'application/gpx+xml', buffer: Buffer.from(`<?xml version="1.0"?><gpx version="1.1"><trk><name>${name}</name><trkseg>${rows.join('')}</trkseg></trk></gpx>`) }
}

function createState(overrides = {}) {
  return {
    errors: [],
    requests: [],
    plans: [{ id: 90, name: '가을 교토 여행', startDate: '2026-10-01', endDate: '2026-10-03', homeCurrency: 'KRW', headCount: 1, status: 'PLANNED', colorHex: '#3182F6', noPeriod: false }],
    memories: [],
    media: [],
    routes: [],
    nextPlanId: 91,
    nextMemoryId: 501,
    nextMediaId: 800,
    nextRouteId: 700,
    failMemoryPresigns: 0, // number of upcoming memory presign calls (after `skipMemoryPresigns`) that answer "not presigned"
    skipMemoryPresigns: 0,
    failGpxPresigns: 0,
    places: { 서울역: { name: '서울역', displayName: '서울역, 서울, 대한민국', latitude: 37.556, longitude: 126.971, country: '대한민국', region: '서울' } },
    ...overrides,
  }
}

const planOf = (state, id) => state.plans.find((plan) => String(plan.id) === String(id))

function planDetail(state, plan) {
  const memoryRecords = state.memories.filter((memory) => memory.planId === plan.id)
  const mediaItems = state.media.filter((media) => memoryRecords.some((memory) => memory.id === media.recordId))
  const routeSegments = state.routes.filter((route) => route.planId === plan.id)
  return {
    ...plan, budgetItems: [], records: [], memoryRecords, mediaItems, routeSegments,
    memoryRecordCount: memoryRecords.length, mediaItemCount: mediaItems.length, routeSegmentCount: routeSegments.length,
  }
}

function photoPin(state, memory, media) {
  const plan = planOf(state, memory.planId)
  return {
    id: media.id, mediaId: media.id, clusterId: memory.id, recordId: memory.id,
    planId: plan.id, planName: plan.name, planColorHex: plan.colorHex,
    latitude: media.gpsLatitude ?? memory.latitude, longitude: media.gpsLongitude ?? memory.longitude,
    title: memory.title, placeName: memory.placeName, country: memory.country, region: memory.region,
    memoryDate: memory.memoryDate, memoryTime: memory.memoryTime, expenseDate: memory.memoryDate, expenseTime: memory.memoryTime,
    photoUrl: `/__mock-photo/${media.id}.png`, contentUrl: `/__mock-photo/${media.id}.png`,
    originalFileName: media.originalFileName, mediaType: 'PHOTO', recordType: 'MEMORY',
  }
}

function myMapOverview(state) {
  const photoPins = []
  const photoClusters = []
  const markers = []
  for (const memory of state.memories) {
    if (memory.latitude == null || memory.longitude == null) continue
    const plan = planOf(state, memory.planId)
    const media = state.media.filter((item) => item.recordId === memory.id)
    if (media.length) {
      const pins = media.map((item) => photoPin(state, memory, item))
      photoPins.push(...pins)
      photoClusters.push({ ...pins[0], id: memory.id, representativeMediaId: pins[0].mediaId, representativePhotoUrl: pins[0].photoUrl, photoCount: pins.length, memoryCount: 1 })
    } else {
      markers.push({
        id: memory.id, planId: plan.id, planName: plan.name, colorHex: plan.colorHex, latitude: memory.latitude, longitude: memory.longitude,
        country: memory.country, region: memory.region, placeName: memory.placeName, title: memory.title,
        memoryDate: memory.memoryDate, memoryTime: memory.memoryTime, visitedDate: memory.memoryDate, visitedTime: memory.memoryTime,
        photoCount: 0, receiptCount: 0, iconKey: 'general', iconText: '📍',
      })
    }
  }
  const routes = state.routes.map((route) => {
    const plan = planOf(state, route.planId)
    return { ...route, planName: plan.name, planColorHex: plan.colorHex }
  })
  return { includedPlanCount: state.plans.length, markers, routes, photoPins, photoClusters }
}

function reverseLookup(lat) {
  if (lat < 34.99) return { country: '일본', region: '교토', placeName: '후시미이나리' }
  if (lat < 35.5) return { country: '일본', region: '교토', placeName: '기요미즈데라' }
  return { country: '대한민국', region: '서울', placeName: '서울 장소' }
}

async function mock(page, state) {
  page.on('pageerror', (error) => state.errors.push(error.message))
  await page.route(/^https?:\/\/(?!127\.0\.0\.1|localhost)/, (route) => route.abort())
  await page.route('**/__mock-upload/**', (route) => route.fulfill({ status: 200, body: '' }))
  await page.route('**/__mock-photo/**', async (route) => route.fulfill({ contentType: 'image/png', body: state.photoBuffer || Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6x1sAAAAASUVORK5CYII=', 'base64') }))
  await page.route('**/api/**', (route) => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname, method = request.method()
    const body = request.headers()['content-type']?.includes('application/json') && request.postData() ? request.postDataJSON() : null
    state.requests.push({ path, method, body, params: Object.fromEntries(url.searchParams) })
    const json = (value, status = 200) => route.fulfill({ status, json: value })
    if (path === '/api/auth/me') return json({ id: 7, loginId: 'test-only-owner', displayName: '가져오기 검증', active: true, admin: false })
    if (path === '/api/auth/csrf') return json({ token: 'test-only-csrf' })
    if (path === '/api/auth/registration-options') return json({ publicRegistrationEnabled: false, socialLoginProviders: [] })
    if (path === '/api/record-shares/counts') return json({ ledger: 0, travel: 0 })
    if (path === '/api/record-map/overview' || path === '/api/record-map/map') {
      return json({ title: '기록 지도', readOnly: false, visitsIncluded: true, collections: [], places: [], visits: [], totalPlaces: 0, page: 0, totalPages: 0, visitsLimited: false, placesLimited: false })
    }
    if (path === '/api/record-map/locations/search') {
      const found = state.places[url.searchParams.get('q')]
      return json(found ? [found] : [])
    }
    if (path === '/api/record-map/places' && method === 'POST') return json({ id: 31 })
    if (path.startsWith('/api/record-map/places/') && path.endsWith('/visits') && method === 'POST') return json({ id: 41 })
    if (path.startsWith('/api/record-map/visits/') && path.endsWith('/photos') && method === 'POST') return json({ id: 'photo:1', name: 'p.jpg', photoUrl: '/__mock-photo/1.png' })
    if (path === '/api/travel/geocode/reverse') return json(reverseLookup(Number(url.searchParams.get('lat'))))
    if (path === '/api/travel/portfolio') {
      return json({ includedPlanCount: state.plans.length, plans: state.plans, memoryRecords: [], routeSegments: [], mediaItems: [] })
    }
    if (path === '/api/travel/my-map') return json(myMapOverview(state))
    const cluster = path.match(/^\/api\/travel\/my-map\/photo-clusters\/(\d+)$/)
    if (cluster) {
      const memory = state.memories.find((item) => item.id === Number(cluster[1]))
      const pins = state.media.filter((item) => item.recordId === memory?.id).map((item) => photoPin(state, memory, item))
      return json({ id: memory?.id, representativeMediaId: pins[0]?.mediaId, representativePhoto: pins[0], photos: pins, page: 0, size: 12, totalPhotoCount: pins.length, hasNext: false })
    }
    if (path === '/api/travel/plans' && method === 'POST') {
      const created = { id: state.nextPlanId++, ...body }
      state.plans.push(created)
      return json(planDetail(state, created))
    }
    if (path === '/api/travel/plans') return json(state.plans)
    const plan = path.match(/^\/api\/travel\/plans\/(\d+)$/)
    if (plan && method === 'GET') return json(planDetail(state, planOf(state, plan[1])))
    const memoryCreate = path.match(/^\/api\/travel\/plans\/(\d+)\/memories$/)
    if (memoryCreate && method === 'POST') {
      const created = { id: state.nextMemoryId++, planId: Number(memoryCreate[1]), ...body }
      state.memories.push(created)
      return json(created)
    }
    const memoryItem = path.match(/^\/api\/travel\/memories\/(\d+)$/)
    if (memoryItem && method === 'PUT') {
      Object.assign(state.memories.find((item) => item.id === Number(memoryItem[1])), body)
      return json(state.memories.find((item) => item.id === Number(memoryItem[1])))
    }
    if (memoryItem && method === 'DELETE') {
      state.memories = state.memories.filter((item) => item.id !== Number(memoryItem[1]))
      state.media = state.media.filter((item) => item.recordId !== Number(memoryItem[1]))
      return route.fulfill({ status: 204 })
    }
    const presign = path.match(/^\/api\/travel\/memories\/(\d+)\/media\/presign$/)
    if (presign) {
      if (state.skipMemoryPresigns > 0) state.skipMemoryPresigns -= 1
      else if (state.failMemoryPresigns > 0) { state.failMemoryPresigns -= 1; return json({ uploadMode: 'LOCAL', uploads: [] }) }
      return json({
        uploadMode: 'PRESIGNED',
        uploads: body.files.map((file, index) => ({
          uploadUrl: `/__mock-upload/${presign[1]}-${index}`, method: 'PUT', objectKey: `m/${presign[1]}/${index}`,
          originalFileName: file.originalFileName, contentType: file.contentType, fileSize: file.fileSize,
          thumbnails: (file.thumbnails || []).map((thumbnail) => ({ variant: thumbnail.variant, uploadUrl: `/__mock-upload/${presign[1]}-${index}-${thumbnail.variant}`, method: 'PUT', objectKey: `m/${presign[1]}/${index}/${thumbnail.variant}`, contentType: thumbnail.contentType })),
        })),
      })
    }
    const complete = path.match(/^\/api\/travel\/memories\/(\d+)\/media\/complete$/)
    if (complete) {
      const created = body.files.map((file) => ({
        id: state.nextMediaId++, recordType: 'MEMORY', recordId: Number(complete[1]), mediaType: 'PHOTO',
        originalFileName: file.originalFileName, gpsLatitude: file.gpsLatitude ?? null, gpsLongitude: file.gpsLongitude ?? null,
      }))
      state.media.push(...created)
      return json(created)
    }
    const mediaItem = path.match(/^\/api\/travel\/media\/(\d+)$/)
    if (mediaItem && method === 'DELETE') {
      state.media = state.media.filter((item) => item.id !== Number(mediaItem[1]))
      return route.fulfill({ status: 204 })
    }
    const routeCreate = path.match(/^\/api\/travel\/plans\/(\d+)\/routes$/)
    if (routeCreate && method === 'POST') {
      const created = { id: state.nextRouteId++, planId: Number(routeCreate[1]), ...body }
      state.routes.push(created)
      return json(created)
    }
    const gpxPresign = path.match(/^\/api\/travel\/routes\/(\d+)\/gpx-files\/presign$/)
    if (gpxPresign) {
      if (state.failGpxPresigns > 0) { state.failGpxPresigns -= 1; return json({ uploadMode: 'LOCAL', uploads: [] }) }
      return json({ uploadMode: 'PRESIGNED', uploads: body.files.map((file, index) => ({ uploadUrl: `/__mock-upload/gpx-${gpxPresign[1]}-${index}`, method: 'PUT', objectKey: `g/${gpxPresign[1]}/${index}`, originalFileName: file.originalFileName, contentType: file.contentType, fileSize: file.fileSize })) })
    }
    if (/\/gpx-files\/complete$/.test(path)) return json([])
    if (/\/gpx-files\/abort$/.test(path)) return route.fulfill({ status: 204 })
    if (/\/api\/travel\/(categories|exchange-rates|share-groups|plans)/.test(path) && method === 'GET') return json(path.includes('categories') ? {} : [])
    return json({})
  })
}

const OPEN = '＋ 사진·GPX·장소 추가'

async function openMap(page, { plan = '' } = {}) {
  await page.goto('/#my-map')
  await expect(page.getByRole('heading', { name: '내 여행 지도' })).toBeVisible()
  if (plan) await page.locator('.travel-map-trip-select select').selectOption(String(plan))
}

async function openImport(page) {
  await page.getByRole('button', { name: OPEN, exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '여행에 추가' })
  await expect(dialog).toBeVisible()
  return dialog
}

const fileInput = (dialog) => dialog.getByLabel('사진·GPX 파일 선택')
const requestsTo = (state, method, pattern) => state.requests.filter((item) => item.method === method && pattern.test(item.path))

async function photoSet(page) {
  const jpeg = await canvasImage(page, 'image/jpeg', 1)
  return {
    a: { name: 'IMG_0101.jpg', mimeType: 'image/jpeg', buffer: withExif(jpeg, { lat: 34.96710, lon: 135.77270, dateTime: '2026:10:02 10:05:00' }) },
    b: { name: 'IMG_0102.jpg', mimeType: 'image/jpeg', buffer: withExif(await canvasImage(page, 'image/jpeg', 2), { lat: 34.96750, lon: 135.77300, dateTime: '2026:10:02 10:40:00' }) },
    c: { name: 'IMG_0103.jpg', mimeType: 'image/jpeg', buffer: withExif(await canvasImage(page, 'image/jpeg', 3), { lat: 35.01160, lon: 135.76810, dateTime: '2026:10:03 15:40:00' }) },
    plain: { name: 'screenshot.png', mimeType: 'image/png', buffer: await canvasImage(page, 'image/png', 4) },
  }
}

test('I1 a GPX file keeps its own date, is saved as one route and the map refreshes in place', async ({ page }) => {
  const state = createState()
  await mock(page, state)
  await openMap(page, { plan: 90 })
  const dialog = await openImport(page)
  await expect(dialog.getByLabel('저장할 여행')).toHaveValue('90')
  await fileInput(dialog).setInputFiles(gpxFile('fushimi-walk.gpx', { start: '2026-10-02T03:00:00Z', points: 1200 }))
  const item = dialog.locator('li.travel-import-item[data-kind="gpx"]')
  await expect(item).toHaveCount(1)
  await expect(item.getByLabel('날짜')).toHaveValue('2026-10-02')
  await expect(item.getByLabel('경로 제목')).toHaveValue('fushimi-walk')
  const before = requestsTo(state, 'GET', /^\/api\/travel\/my-map$/).length
  await dialog.getByRole('button', { name: '저장', exact: true }).click()
  await expect(dialog).toBeHidden()
  const routes = requestsTo(state, 'POST', /^\/api\/travel\/plans\/90\/routes$/)
  expect(routes).toHaveLength(1)
  expect(routes[0].body).toMatchObject({ routeDate: '2026-10-02', sourceType: 'GPX', transportMode: 'WALK', title: 'fushimi-walk' })
  expect(routes[0].body.points.length).toBeLessThanOrEqual(900)
  expect(requestsTo(state, 'POST', /\/gpx-files\/presign$/)).toHaveLength(1)
  expect(requestsTo(state, 'POST', /\/gpx-files\/complete$/)).toHaveLength(1)
  await expect.poll(() => requestsTo(state, 'GET', /^\/api\/travel\/my-map$/).length).toBeGreaterThan(before)
  await expect(page.locator('.travel-map-import-notice')).toContainText('경로 1개')
  expect(state.errors).toEqual([])
})

test('I2 three GPX files from three days become three routes with their own dates', async ({ page }) => {
  const state = createState()
  await mock(page, state)
  await openMap(page, { plan: 90 })
  const dialog = await openImport(page)
  await fileInput(dialog).setInputFiles([
    gpxFile('day1.gpx', { start: '2026-10-01T03:00:00Z' }),
    gpxFile('day2.gpx', { start: '2026-10-02T03:00:00Z' }),
    gpxFile('day3.gpx', { start: '2026-10-03T03:00:00Z' }),
  ])
  await expect(dialog.locator('li.travel-import-item[data-kind="gpx"]')).toHaveCount(3)
  await expect(dialog.locator('h3.travel-import-day')).toHaveCount(3)
  await dialog.getByRole('button', { name: '저장', exact: true }).click()
  await expect(dialog).toBeHidden()
  const routes = requestsTo(state, 'POST', /^\/api\/travel\/plans\/90\/routes$/)
  expect(routes.map((item) => item.body.routeDate)).toEqual(['2026-10-01', '2026-10-02', '2026-10-03'])
  expect(state.errors).toEqual([])
})

test('I3 unusable files are listed with a reason and nothing can be saved', async ({ page }) => {
  const state = createState()
  await mock(page, state)
  await openMap(page, { plan: 90 })
  const dialog = await openImport(page)
  await fileInput(dialog).setInputFiles([
    { name: 'broken.gpx', mimeType: 'application/gpx+xml', buffer: Buffer.from('<gpx><trk><trkseg></trkseg></trk></gpx>') },
    { name: 'note.txt', mimeType: 'text/plain', buffer: Buffer.from('hello') },
    { name: 'bad.jpg', mimeType: 'image/jpeg', buffer: Buffer.from('this is not an image at all, only text') },
  ])
  const rejected = dialog.locator('.travel-import-rejected li')
  await expect(rejected).toHaveCount(3)
  await expect(rejected.filter({ hasText: 'broken.gpx' })).toContainText('경로 점')
  await expect(rejected.filter({ hasText: 'note.txt' })).toContainText('GPX 파일만')
  await expect(rejected.filter({ hasText: 'bad.jpg' })).toContainText('읽을 수 없습니다')
  await expect(dialog.locator('li.travel-import-item')).toHaveCount(0)
  await expect(dialog.getByRole('button', { name: '저장', exact: true })).toBeDisabled()
  await dialog.getByRole('button', { name: '지우기', exact: true }).click()
  await expect(rejected).toHaveCount(0)
  expect(state.errors).toEqual([])
})

async function reviewPhotoSet(page, state) {
  await mock(page, state)
  await openMap(page, { plan: 90 })
  const dialog = await openImport(page)
  const photos = await photoSet(page)
  await fileInput(dialog).setInputFiles([photos.a, photos.b, photos.c, photos.plain])
  const items = dialog.locator('li.travel-import-item[data-kind="photos"]')
  await expect(items).toHaveCount(3)
  await expect(dialog.locator('.travel-import-progress')).toHaveCount(0)
  return { dialog, items }
}

test('I4 photos are grouped, reviewed on the map and saved as one record per group', async ({ page }) => {
  const state = createState()
  const { dialog, items } = await reviewPhotoSet(page, state)
  const first = items.filter({ hasText: '사진 2장' })
  await expect(first).toHaveCount(1)
  await expect(first.getByLabel('기록 제목')).toHaveValue('후시미이나리')
  await expect(first.getByLabel('날짜')).toHaveValue('2026-10-02')
  const noLocation = items.filter({ hasText: '위치 없음' })
  await expect(noLocation).toHaveCount(1)
  await expect(noLocation.locator('.travel-import-flag')).toHaveText(['날짜 추정', '위치 없음'])
  await expect(noLocation.getByLabel('기록 제목')).toHaveValue('screenshot')
  await noLocation.getByRole('button', { name: '지도에서 위치 지정', exact: true }).click()
  const canvas = dialog.locator('.travel-map__canvas')
  const box = await canvas.boundingBox()
  await canvas.click({ position: { x: box.width * .4, y: box.height * .6 } })
  await expect(noLocation.locator('.travel-import-flag', { hasText: '위치 없음' })).toHaveCount(0)
  await expect(dialog.locator('.travel-import-summary')).toContainText('기록 3 · 사진 4')
  await dialog.getByRole('button', { name: '저장', exact: true }).click()
  await expect(dialog).toBeHidden()
  const memories = requestsTo(state, 'POST', /\/plans\/90\/memories$/)
  expect(memories).toHaveLength(3)
  const grouped = memories.find((item) => item.body.memoryDate === '2026-10-02')
  expect(grouped.body).toMatchObject({ title: '후시미이나리', memoryTime: '10:05' })
  expect(grouped.body.latitude).toBeCloseTo(34.9673, 3)
  expect(memories.every((item) => item.body.latitude !== null && item.body.longitude !== null)).toBe(true)
  expect(memories.filter((item) => item.body.memoryDate === '2026-10-01')).toHaveLength(1)
  const presigns = requestsTo(state, 'POST', /\/media\/presign$/)
  expect(presigns).toHaveLength(3)
  expect(presigns.map((item) => item.body.files.length).sort()).toEqual([1, 1, 2])
  await expect(page.locator('.travel-map-import-notice')).toContainText('기록 3건 · 사진 4장')
  expect(state.errors).toEqual([])
})

test('I5 a failed upload is retried without creating records twice', async ({ page }) => {
  const state = createState({ skipMemoryPresigns: 1, failMemoryPresigns: 1 })
  const { dialog, items } = await reviewPhotoSet(page, state)
  // The plain screenshot has no location; give it one so every record can be saved.
  await items.filter({ hasText: '위치 없음' }).getByRole('button', { name: '지도에서 위치 지정', exact: true }).click()
  const canvas = dialog.locator('.travel-map__canvas')
  const box = await canvas.boundingBox()
  await canvas.click({ position: { x: box.width * .4, y: box.height * .6 } })
  await dialog.getByRole('button', { name: '저장', exact: true }).click()
  const failed = dialog.locator('li.travel-import-item[data-status="failed"]')
  await expect(failed).toHaveCount(1)
  await expect(failed.locator('.travel-import-item__status')).toContainText('파일 저장소')
  await expect(failed.locator('.travel-import-item__status')).not.toContainText('Presigned')
  await expect(dialog.locator('li.travel-import-item[data-status="saved"]')).toHaveCount(2)
  expect(requestsTo(state, 'POST', /\/plans\/90\/memories$/)).toHaveLength(3)
  await dialog.getByRole('button', { name: '남은 항목 다시 저장', exact: true }).click()
  await expect(dialog).toBeHidden()
  expect(requestsTo(state, 'POST', /\/plans\/90\/memories$/)).toHaveLength(3)
  expect(requestsTo(state, 'POST', /\/media\/presign$/)).toHaveLength(4)
  expect(state.media).toHaveLength(4)
  expect(state.errors).toEqual([])
})

test('I6 a place found through the server search is added and saved with its coordinates', async ({ page }) => {
  const state = createState()
  await mock(page, state)
  await openMap(page, { plan: 90 })
  const dialog = await openImport(page)
  await dialog.getByRole('searchbox', { name: '장소 검색' }).fill('서울역')
  await dialog.getByRole('button', { name: '장소 찾기', exact: true }).click()
  await dialog.getByRole('button', { name: '서울역', exact: true }).click()
  const item = dialog.locator('li.travel-import-item[data-kind="place"]')
  await expect(item).toHaveCount(1)
  await expect(item.getByLabel('기록 제목')).toHaveValue('서울역')
  await dialog.getByRole('button', { name: '저장', exact: true }).click()
  await expect(dialog).toBeHidden()
  const memories = requestsTo(state, 'POST', /\/plans\/90\/memories$/)
  expect(memories).toHaveLength(1)
  expect(memories[0].body.latitude).toBeCloseTo(37.556, 3)
  expect(memories[0].body.longitude).toBeCloseTo(126.971, 3)
  expect(state.requests.some((item) => item.path === '/api/record-map/locations/search')).toBe(true)
  expect(state.errors).toEqual([])
})

test('I7 photos outside every trip need an explicit choice and can start a new trip', async ({ page }) => {
  const state = createState()
  await mock(page, state)
  await openMap(page)
  const dialog = await openImport(page)
  const jpeg = await canvasImage(page, 'image/jpeg', 5)
  await fileInput(dialog).setInputFiles({ name: 'IMG_9001.jpg', mimeType: 'image/jpeg', buffer: withExif(jpeg, { lat: 34.9671, lon: 135.7727, dateTime: '2026:11:14 09:30:00' }) })
  await expect(dialog.locator('li.travel-import-item[data-kind="photos"]')).toHaveCount(1)
  await expect(dialog.getByLabel('저장할 여행')).toHaveValue('')
  await expect(dialog.getByRole('button', { name: '저장', exact: true })).toBeDisabled()
  await dialog.getByLabel('저장할 여행').selectOption('__new__')
  await expect(dialog.getByLabel('새 여행 시작일')).toHaveValue('2026-11-14')
  await expect(dialog.getByLabel('새 여행 종료일')).toHaveValue('2026-11-14')
  await dialog.getByLabel('새 여행 이름').fill('가을 단풍 여행')
  await dialog.getByRole('button', { name: '저장', exact: true }).click()
  await expect(dialog).toBeHidden()
  const plans = requestsTo(state, 'POST', /^\/api\/travel\/plans$/)
  expect(plans).toHaveLength(1)
  expect(plans[0].body).toMatchObject({ name: '가을 단풍 여행', startDate: '2026-11-14', endDate: '2026-11-14', homeCurrency: 'KRW', headCount: 1, noPeriod: false })
  expect(requestsTo(state, 'POST', /\/plans\/91\/memories$/)).toHaveLength(1)
  await expect(page.locator('.travel-map-trip-select select')).toHaveValue('91')
  expect(state.errors).toEqual([])
})

test('I8 mobile layout keeps the save action reachable without sideways scrolling', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'chromium-mobile', 'mobile only')
  const state = createState()
  await mock(page, state)
  await openMap(page, { plan: 90 })
  const dialog = await openImport(page)
  const photos = await photoSet(page)
  await fileInput(dialog).setInputFiles([photos.a, photos.b, photos.c])
  await expect(dialog.locator('li.travel-import-item')).toHaveCount(2)
  const viewport = page.viewportSize()
  const box = await dialog.boundingBox()
  expect(Math.round(box.x)).toBe(0)
  expect(Math.round(box.width)).toBe(viewport.width)
  expect(Math.round(box.height)).toBe(viewport.height)
  const save = await dialog.getByRole('button', { name: '저장', exact: true }).boundingBox()
  expect(save.y + save.height).toBeLessThanOrEqual(viewport.height)
  expect(save.height).toBeGreaterThanOrEqual(40)
  const metrics = await page.evaluate(() => {
    const element = document.querySelector('.travel-import-dialog')
    return { page: document.documentElement.scrollWidth, dialogScroll: element.scrollWidth, dialogClient: element.clientWidth }
  })
  expect(metrics.page).toBeLessThanOrEqual(viewport.width)
  expect(metrics.dialogScroll).toBeLessThanOrEqual(metrics.dialogClient)
  expect(state.errors).toEqual([])
})

test('I9 closing with unsaved items asks first and never saves them', async ({ page }) => {
  const state = createState()
  await mock(page, state)
  await openMap(page, { plan: 90 })
  const dialog = await openImport(page)
  await fileInput(dialog).setInputFiles(gpxFile('keep-me.gpx', { start: '2026-10-02T03:00:00Z' }))
  await expect(dialog.locator('li.travel-import-item')).toHaveCount(1)
  page.once('dialog', (confirmation) => confirmation.dismiss())
  await dialog.getByRole('button', { name: '창 닫기', exact: true }).click()
  await expect(dialog).toBeVisible()
  await expect(dialog.locator('li.travel-import-item')).toHaveCount(1)
  page.once('dialog', (confirmation) => confirmation.accept())
  await dialog.getByRole('button', { name: '창 닫기', exact: true }).click()
  await expect(dialog).toBeHidden()
  expect(requestsTo(state, 'POST', /\/api\/travel\/plans\/\d+\/(memories|routes)$/)).toHaveLength(0)
  expect(state.errors).toEqual([])
})

test('I10 Escape closes an empty dialog and returns focus to the add button', async ({ page }) => {
  const state = createState()
  await mock(page, state)
  await openMap(page, { plan: 90 })
  const dialog = await openImport(page)
  await page.keyboard.press('Escape')
  await expect(dialog).toBeHidden()
  await expect(page.getByRole('button', { name: OPEN, exact: true })).toBeFocused()
  expect(state.errors).toEqual([])
})

test('I11 the current position becomes a place item', async ({ page, context }) => {
  const state = createState()
  await context.grantPermissions(['geolocation'])
  await context.setGeolocation({ latitude: 35.0, longitude: 135.77 })
  await mock(page, state)
  await openMap(page, { plan: 90 })
  const dialog = await openImport(page)
  await dialog.getByRole('button', { name: '현재 위치', exact: true }).click()
  const item = dialog.locator('li.travel-import-item[data-kind="place"]')
  await expect(item).toHaveCount(1)
  await dialog.getByRole('button', { name: '저장', exact: true }).click()
  await expect(dialog).toBeHidden()
  const memories = requestsTo(state, 'POST', /\/plans\/90\/memories$/)
  expect(memories).toHaveLength(1)
  expect(memories[0].body.latitude).toBeCloseTo(35.0, 4)
  expect(memories[0].body.longitude).toBeCloseTo(135.77, 4)
  expect(state.errors).toEqual([])
})

async function openLegacyList(page) {
  await page.getByRole('button', { name: '기록 목록·편집', exact: true }).click()
  await expect(page.getByRole('heading', { name: '장소·사진 목록' })).toBeVisible()
}

test('L1 the legacy route screen keeps the GPX date and explains unusable files', async ({ page }) => {
  const state = createState()
  await mock(page, state)
  await openMap(page, { plan: 90 })
  await openLegacyList(page)
  await page.getByRole('button', { name: '경로 관리', exact: true }).click()
  const input = page.locator('input[type="file"][accept=".gpx"]')
  await input.setInputFiles(gpxFile('legacy-walk.gpx', { start: '2026-10-02T03:00:00Z', points: 40 }))
  await expect(page.locator('.travel-route-form').getByLabel('날짜', { exact: true })).toHaveValue('2026-10-02')
  await input.setInputFiles({ name: 'empty.gpx', mimeType: 'application/gpx+xml', buffer: Buffer.from('<gpx><trk><trkseg></trkseg></trk></gpx>') })
  await expect(page.getByRole('alert').filter({ hasText: '경로 점을 찾지 못한 파일' })).toContainText('empty.gpx')
  expect(state.errors).toEqual([])
})

test('L2 deleting a legacy record asks for confirmation first', async ({ page }) => {
  const state = createState()
  state.memories.push({ id: 501, planId: 90, memoryDate: '2026-10-02', memoryTime: null, category: '장소', title: '지울 기록', latitude: 34.97, longitude: 135.77, country: '일본', region: '교토', placeName: '교토', sharedWithCommunity: false, memo: null })
  state.nextMemoryId = 502
  await mock(page, state)
  await openMap(page, { plan: 90 })
  await openLegacyList(page)
  const row = page.locator('tr').filter({ hasText: '지울 기록' })
  page.once('dialog', (confirmation) => confirmation.dismiss())
  await row.getByRole('button', { name: '삭제', exact: true }).click()
  await page.waitForTimeout(300)
  expect(requestsTo(state, 'DELETE', /\/api\/travel\/memories\/\d+$/)).toHaveLength(0)
  page.once('dialog', (confirmation) => confirmation.accept())
  await row.getByRole('button', { name: '삭제', exact: true }).click()
  await expect.poll(() => requestsTo(state, 'DELETE', /\/api\/travel\/memories\/501$/).length).toBe(1)
  expect(state.errors).toEqual([])
})

test('L3 the legacy place list fits a phone without sideways overflow', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'chromium-mobile', 'mobile only')
  const state = createState()
  await mock(page, state)
  await openMap(page, { plan: 90 })
  await openLegacyList(page)
  const panel = page.locator('.travel-memory-map-panel')
  await expect(panel).toBeVisible()
  const box = await panel.boundingBox()
  expect(box.width).toBeLessThanOrEqual(page.viewportSize().width)
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(page.viewportSize().width)
  expect(state.errors).toEqual([])
})

test('L4 a single photo is saved from the legacy editor and bulk import opens the new dialog', async ({ page }) => {
  const state = createState()
  await mock(page, state)
  await openMap(page, { plan: 90 })
  await openLegacyList(page)
  await page.getByRole('button', { name: '새 기록', exact: true }).click()
  const editor = page.locator('.travel-modal__dialog')
  await expect(editor).toBeVisible()
  await expect(editor.getByText('여러 사진 받기')).toHaveCount(0)
  await expect(editor.getByText('커뮤니티')).toHaveCount(0)
  const jpeg = await canvasImage(page, 'image/jpeg', 6)
  await editor.getByLabel('사진 업로드').setInputFiles({ name: 'IMG_7001.jpg', mimeType: 'image/jpeg', buffer: withExif(jpeg, { lat: 34.9671, lon: 135.7727, dateTime: '2026:10:02 11:15:00' }) })
  await expect(editor.getByLabel('날짜', { exact: true })).toHaveValue('2026-10-02')
  await editor.getByLabel('제목', { exact: true }).fill('단일 사진 기록')
  await editor.getByRole('button', { name: '기록 저장', exact: true }).click()
  await expect(editor).toBeHidden()
  expect(requestsTo(state, 'POST', /\/plans\/90\/memories$/)).toHaveLength(1)
  expect(requestsTo(state, 'POST', /\/media\/presign$/)).toHaveLength(1)
  await page.getByRole('button', { name: '새 기록', exact: true }).click()
  await page.locator('.travel-modal__dialog').getByRole('button', { name: '여러 장 한 번에 가져오기', exact: true }).click()
  await expect(page.getByRole('dialog', { name: '여행에 추가' })).toBeVisible()
  expect(state.errors).toEqual([])
})

function seedPhotoRecord(state) {
  state.memories.push({ id: 501, planId: 90, memoryDate: '2026-10-01', memoryTime: '10:00:00', category: '관광지', title: '기요미즈데라', latitude: 34.9949, longitude: 135.785, country: '일본', region: '교토', placeName: '기요미즈데라', sharedWithCommunity: false, memo: '첫날 방문' })
  state.media.push({ id: 801, recordType: 'MEMORY', recordId: 501, mediaType: 'PHOTO', originalFileName: 'kiyomizu.png', gpsLatitude: null, gpsLongitude: null })
  state.nextMemoryId = 502
  state.nextMediaId = 802
}

async function openRecordEditorFromMap(page) {
  await page.locator('.travel-map__canvas .leaflet-marker-icon').first().click()
  await page.getByRole('button', { name: /사진 크게 보기/ }).first().click()
  await page.getByRole('button', { name: '수정', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '기록 수정' })
  await expect(dialog).toBeVisible()
  await expect(page.locator('.travel-setup-workflow')).toHaveCount(0)
  return dialog
}

test('E1 a record is edited on top of the map without leaving it', async ({ page }) => {
  const state = createState()
  seedPhotoRecord(state)
  await mock(page, state)
  await openMap(page, { plan: 90 })
  const dialog = await openRecordEditorFromMap(page)
  await expect(dialog.getByLabel('제목', { exact: true })).toHaveValue('기요미즈데라')
  await dialog.getByLabel('제목', { exact: true }).fill('청수사 산책')
  await dialog.getByLabel('날짜', { exact: true }).fill('2026-10-02')
  const before = requestsTo(state, 'GET', /^\/api\/travel\/my-map$/).length
  await dialog.getByRole('button', { name: '저장', exact: true }).click()
  await expect(dialog).toBeHidden()
  const updates = requestsTo(state, 'PUT', /^\/api\/travel\/memories\/501$/)
  expect(updates).toHaveLength(1)
  expect(updates[0].body).toMatchObject({ title: '청수사 산책', memoryDate: '2026-10-02', category: '관광지', sharedWithCommunity: false })
  await expect.poll(() => requestsTo(state, 'GET', /^\/api\/travel\/my-map$/).length).toBeGreaterThan(before)
  await expect(page.locator('.travel-map-import-notice')).toContainText('기록을 수정했습니다')
  await expect(page.getByRole('heading', { name: '내 여행 지도' })).toBeVisible()
  expect(state.errors).toEqual([])
})

test('E2 deleting a photo or a record needs a confirmation', async ({ page }) => {
  const state = createState()
  seedPhotoRecord(state)
  await mock(page, state)
  await openMap(page, { plan: 90 })
  const dialog = await openRecordEditorFromMap(page)
  const removePhoto = dialog.getByRole('button', { name: 'kiyomizu.png 삭제' })
  page.once('dialog', (confirmation) => confirmation.dismiss())
  await removePhoto.click()
  await page.waitForTimeout(300)
  expect(requestsTo(state, 'DELETE', /\/api\/travel\/media\/\d+$/)).toHaveLength(0)
  page.once('dialog', (confirmation) => confirmation.accept())
  await removePhoto.click()
  await expect.poll(() => requestsTo(state, 'DELETE', /\/api\/travel\/media\/801$/).length).toBe(1)
  page.once('dialog', (confirmation) => confirmation.dismiss())
  await dialog.getByRole('button', { name: '기록 삭제', exact: true }).click()
  await page.waitForTimeout(300)
  expect(requestsTo(state, 'DELETE', /\/api\/travel\/memories\/\d+$/)).toHaveLength(0)
  page.once('dialog', (confirmation) => confirmation.accept())
  await dialog.getByRole('button', { name: '기록 삭제', exact: true }).click()
  await expect(dialog).toBeHidden()
  expect(requestsTo(state, 'DELETE', /\/api\/travel\/memories\/501$/)).toHaveLength(1)
  await expect(page.locator('.travel-map-import-notice')).toContainText('기록을 삭제했습니다')
  expect(state.errors).toEqual([])
})

test('E3 a photo-less pin opens the same editor from the selection bar', async ({ page }) => {
  const state = createState()
  seedPhotoRecord(state)
  state.media = []
  await mock(page, state)
  await openMap(page, { plan: 90 })
  await page.locator('.travel-map__canvas .leaflet-marker-icon').first().click()
  const bar = page.locator('.travel-map-selected-record')
  await expect(bar).toContainText('기요미즈데라')
  await bar.getByRole('button', { name: '이 기록 수정', exact: true }).click()
  await expect(page.getByRole('dialog', { name: '기록 수정' })).toBeVisible()
  expect(state.errors).toEqual([])
})

test('R1 a photo with a capture date fills the visit date for my places', async ({ page }) => {
  const state = createState()
  await mock(page, state)
  await page.goto('/#travel')
  const jpeg = await canvasImage(page, 'image/jpeg', 7)
  await page.locator('input[type="file"][hidden]').setInputFiles({ name: 'IMG_8101.jpg', mimeType: 'image/jpeg', buffer: withExif(jpeg, { lat: 34.9949, lon: 135.785, dateTime: '2026:10:05 14:20:00' }) })
  const dialog = page.getByRole('dialog', { name: /장소/ })
  await expect(dialog).toBeVisible()
  await expect(dialog.getByRole('checkbox', { name: '방문 날짜와 이야기도 기록' })).toBeChecked()
  await expect(dialog.getByRole('textbox', { name: '방문 날짜' })).toHaveValue('2026-10-05')
  await expect(dialog.getByRole('status').filter({ hasText: '사진 촬영일(2026-10-05)' })).toBeVisible()
  await expect(dialog.getByRole('button', { name: 'IMG_8101.jpg 빼기' })).toBeVisible()
  await dialog.getByRole('button', { name: '장소 저장', exact: true }).click()
  await expect(dialog).toBeHidden()
  const visits = requestsTo(state, 'POST', /\/api\/record-map\/places\/31\/visits$/)
  expect(visits).toHaveLength(1)
  expect(visits[0].body.date).toBe('2026-10-05')
  expect(state.errors).toEqual([])
})
