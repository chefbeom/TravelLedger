import { expect } from '@playwright/test'

// Fixtures are built inside the tests (no files in the repository): EXIF JPEGs are canvas JPEGs with a
// hand-written APP1 segment, GPX files are plain strings.

export function exifSegment({ lat, lon, dateTime }) { // dateTime 'YYYY:MM:DD HH:mm:ss', north latitude / east longitude only
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
export const withExif = (jpeg, meta) => Buffer.concat([jpeg.subarray(0, 2), exifSegment(meta), jpeg.subarray(2)])

export async function canvasImage(page, type, seed = 0) {
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

export function gpxFile(name, { start, points = 60, stepSeconds = 20, lat = 34.967, lon = 135.772, latStep = 0.00005 }) {
  const first = new Date(start).getTime()
  const rows = Array.from({ length: points }, (_, i) => {
    const time = new Date(first + i * stepSeconds * 1000).toISOString()
    return `<trkpt lat="${(lat + i * latStep).toFixed(6)}" lon="${(lon + i * 0.00002).toFixed(6)}"><time>${time}</time></trkpt>`
  })
  return { name, mimeType: 'application/gpx+xml', buffer: Buffer.from(`<?xml version="1.0"?><gpx version="1.1"><trk><name>${name}</name><trkseg>${rows.join('')}</trkseg></trk></gpx>`) }
}

export function createState(overrides = {}) {
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

export const planOf = (state, id) => state.plans.find((plan) => String(plan.id) === String(id))

export function planDetail(state, plan) {
  const memoryRecords = state.memories.filter((memory) => memory.planId === plan.id)
  const mediaItems = state.media.filter((media) => memoryRecords.some((memory) => memory.id === media.recordId))
  const routeSegments = state.routes.filter((route) => route.planId === plan.id)
  return {
    ...plan, budgetItems: [], records: [], memoryRecords, mediaItems, routeSegments,
    memoryRecordCount: memoryRecords.length, mediaItemCount: mediaItems.length, routeSegmentCount: routeSegments.length,
  }
}

export function photoPin(state, memory, media) {
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

export function myMapOverview(state) {
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

export function reverseLookup(lat) {
  if (lat < 34.99) return { country: '일본', region: '교토', placeName: '후시미이나리' }
  if (lat < 35.5) return { country: '일본', region: '교토', placeName: '기요미즈데라' }
  return { country: '대한민국', region: '서울', placeName: '서울 장소' }
}

export async function mock(page, state) {
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

export const OPEN = '＋ 사진·GPX·장소 추가'

export async function openMap(page, { plan = '' } = {}) {
  await page.goto('/#my-map')
  await expect(page.getByRole('heading', { name: '내 여행 지도' })).toBeVisible()
  if (plan) await page.locator('.travel-map-trip-select select').selectOption(String(plan))
}

export async function openImport(page) {
  await page.getByRole('button', { name: OPEN, exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '여행에 추가' })
  await expect(dialog).toBeVisible()
  return dialog
}

export const fileInput = (dialog) => dialog.getByLabel('사진·GPX 파일 선택')
export const requestsTo = (state, method, pattern) => state.requests.filter((item) => item.method === method && pattern.test(item.path))

export async function photoSet(page) {
  const jpeg = await canvasImage(page, 'image/jpeg', 1)
  return {
    a: { name: 'IMG_0101.jpg', mimeType: 'image/jpeg', buffer: withExif(jpeg, { lat: 34.96710, lon: 135.77270, dateTime: '2026:10:02 10:05:00' }) },
    b: { name: 'IMG_0102.jpg', mimeType: 'image/jpeg', buffer: withExif(await canvasImage(page, 'image/jpeg', 2), { lat: 34.96750, lon: 135.77300, dateTime: '2026:10:02 10:40:00' }) },
    c: { name: 'IMG_0103.jpg', mimeType: 'image/jpeg', buffer: withExif(await canvasImage(page, 'image/jpeg', 3), { lat: 35.01160, lon: 135.76810, dateTime: '2026:10:03 15:40:00' }) },
    plain: { name: 'screenshot.png', mimeType: 'image/png', buffer: await canvasImage(page, 'image/png', 4) },
  }
}

export async function openLegacyList(page) {
  await page.getByRole('button', { name: '기록 목록·편집', exact: true }).click()
  await expect(page.getByRole('heading', { name: '장소·사진 목록' })).toBeVisible()
}

export function seedPhotoRecord(state) {
  state.memories.push({ id: 501, planId: 90, memoryDate: '2026-10-01', memoryTime: '10:00:00', category: '관광지', title: '기요미즈데라', latitude: 34.9949, longitude: 135.785, country: '일본', region: '교토', placeName: '기요미즈데라', sharedWithCommunity: false, memo: '첫날 방문' })
  state.media.push({ id: 801, recordType: 'MEMORY', recordId: 501, mediaType: 'PHOTO', originalFileName: 'kiyomizu.png', gpsLatitude: null, gpsLongitude: null })
  state.nextMemoryId = 502
  state.nextMediaId = 802
}
