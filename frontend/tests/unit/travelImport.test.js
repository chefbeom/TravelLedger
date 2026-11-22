import test from 'node:test'
import assert from 'node:assert/strict'
import {
  IMPORT_LIMITS,
  buildMemoryPayload,
  buildNewPlanPayload,
  buildRoutePayload,
  classifyImportFile,
  createPhotoDraft,
  createPlaceDraft,
  createTrackDraft,
  formatImportNotice,
  groupDraftsByDate,
  groupPhotos,
  haversineKm,
  inferPeriod,
  localDateTime,
  parseGpxText,
  runImportSave,
  sortDrafts,
  stripExtension,
  suggestPlanId,
  suggestTitle,
  summarizeDrafts,
  thinRoutePoints,
  toImportErrorMessage,
  validateDraft,
} from '../../src/lib/travelImport.js'

const MB = 1024 * 1024

// Verbatim copy of the legacy TravelRouteWorkspace.thinRoutePoints.
function legacyThinRoutePoints(points, maxPoints = 900) {
  if (!Array.isArray(points) || points.length <= maxPoints) {
    return points
  }
  const reduced = []
  const step = Math.max(1, Math.ceil((points.length - 1) / Math.max(1, maxPoints - 1)))
  for (let index = 0; index < points.length; index += step) {
    reduced.push(points[index])
  }
  const lastPoint = points[points.length - 1]
  const reducedLastPoint = reduced[reduced.length - 1]
  if (reducedLastPoint !== lastPoint) {
    reduced.push(lastPoint)
  }
  return reduced
}

function gpx(points) {
  return `<gpx><trk><trkseg>${points.map(([lat, lon, time]) =>
    `<trkpt lat="${lat}" lon="${lon}">${time ? `<time>${time}</time>` : ''}</trkpt>`).join('')}</trkseg></trk></gpx>`
}

test('classifyImportFile accepts photos by extension or MIME type', () => {
  assert.deepEqual(classifyImportFile({ name: 'a.JPG', type: '', size: 10 }), { kind: 'photo' })
  assert.deepEqual(classifyImportFile({ name: 'noext', type: 'image/webp', size: 10 }), { kind: 'photo' })
  assert.deepEqual(classifyImportFile({ name: 'a.png', type: 'image/png', size: 10 }), { kind: 'photo' })
})

test('classifyImportFile rejects HEIC, oversized and unknown files with a reason', () => {
  assert.match(classifyImportFile({ name: 'a.heic', type: '', size: 10 }).reason, /HEIC/)
  assert.equal(classifyImportFile({ name: 'a.jpg', type: '', size: 16 * MB }).kind, 'rejected')
  assert.match(classifyImportFile({ name: 'a.jpg', type: '', size: 16 * MB }).reason, /15MB/)
  assert.equal(classifyImportFile({ name: 'a.gpx', type: '', size: 11 * MB }).kind, 'rejected')
  assert.match(classifyImportFile({ name: 'a.gpx', type: '', size: 11 * MB }).reason, /10MB/)
  assert.equal(classifyImportFile({ name: 'a.txt', type: 'text/plain', size: 1 }).kind, 'rejected')
})

test('classifyImportFile accepts GPX by extension or MIME type', () => {
  assert.deepEqual(classifyImportFile({ name: 'walk.GPX', type: '', size: 10 }), { kind: 'gpx' })
  assert.deepEqual(classifyImportFile({ name: 'walk', type: 'application/gpx+xml', size: 10 }), { kind: 'gpx' })
})

test('parseGpxText reads trkpt distance and duration', () => {
  const result = parseGpxText(`<gpx><trk><trkseg>
    <trkpt lat="37.5" lon="127.0"><time>2026-10-02T03:00:00Z</time></trkpt>
    <trkpt lon='127.0' lat='37.51'><time>2026-10-02T03:10:00Z</time></trkpt>
  </trkseg></trk></gpx>`)
  assert.equal(result.ok, true)
  assert.equal(result.points.length, 2)
  assert.ok(Math.abs(result.distanceKm - 1.112) < 0.02)
  assert.equal(result.durationMinutes, 10)
  assert.equal(result.startTime.toISOString(), '2026-10-02T03:00:00.000Z')
})

test('parseGpxText supports rtept, self-closing points and either attribute order', () => {
  const result = parseGpxText(`<gpx><rte><rtept lat="1" lon="2"/><rtept lon='2.5' lat='1.5' /></rte></gpx>`)
  assert.equal(result.ok, true)
  assert.deepEqual(result.points.map(p => [p.latitude, p.longitude]), [[1, 2], [1.5, 2.5]])
  assert.equal(result.startTime, null)
  assert.equal(result.durationMinutes, 0)
})

test('parseGpxText reports unusable files', () => {
  assert.equal(parseGpxText('<html></html>').reason, 'GPX 형식이 아닙니다.')
  assert.match(parseGpxText(gpx([[1, 2]])).reason, /2개 이상/)
})

test('parseGpxText drops consecutive duplicates and out-of-range points', () => {
  const result = parseGpxText(gpx([[1, 2], [1, 2], [95, 2], [1.1, 2.1], [1.1, 200]]))
  assert.equal(result.ok, true)
  assert.equal(result.points.length, 2)
})

test('haversineKm is accurate to one percent', () => {
  const km = haversineKm({ latitude: 37.5, longitude: 127.0 }, { latitude: 37.51, longitude: 127.0 })
  assert.ok(Math.abs(km - 1.112) / 1.112 < 0.01)
})

test('thinRoutePoints matches the legacy algorithm and keeps the last point', () => {
  for (const count of [5, 900, 901, 5000]) {
    const points = Array.from({ length: count }, (_, i) => ({ latitude: i, longitude: i }))
    const next = thinRoutePoints(points, 900)
    assert.deepEqual(next, legacyThinRoutePoints(points, 900))
    assert.equal(next.at(-1), points.at(-1))
    assert.ok(next.length <= 901)
  }
})

test('localDateTime pads values and uses local calendar fields', () => {
  const instant = new Date('2026-10-02T03:00:00Z')
  const result = localDateTime(instant)
  assert.equal(result.date, `${instant.getFullYear()}-${String(instant.getMonth() + 1).padStart(2, '0')}-${String(instant.getDate()).padStart(2, '0')}`)
  assert.match(result.date, /^2026-10-0[23]$/)
  assert.deepEqual(localDateTime(new Date(2026, 0, 5, 7, 4)), { date: '2026-01-05', time: '07:04' })
})

test('stripExtension and suggestTitle fall back in order', () => {
  assert.equal(stripExtension('IMG_0101.jpg'), 'IMG_0101')
  assert.equal(suggestTitle({ placeName: '후시미', region: '교토', fileName: 'a.jpg' }), '후시미')
  assert.equal(suggestTitle({ region: '교토', fileName: 'a.jpg' }), '교토')
  assert.equal(suggestTitle({ fileName: 'a.jpg' }), 'a')
  assert.equal(suggestTitle({}), '사진 기록')
  assert.equal(suggestTitle({ placeName: 'x'.repeat(200) }).length, 120)
})

test('groupPhotos groups by day, distance and time gap', () => {
  const groups = groupPhotos([
    { id: 'a', fileName: 'IMG_0101.jpg', date: '2026-10-02', time: '10:05', latitude: 34.96710, longitude: 135.77270 },
    { id: 'b', fileName: 'IMG_0102.jpg', date: '2026-10-02', time: '10:40', latitude: 34.96750, longitude: 135.77300 },
    { id: 'c', fileName: 'IMG_0103.jpg', date: '2026-10-03', time: '15:40', latitude: 35.01160, longitude: 135.76810 },
    { id: 'd', fileName: 'screenshot.png', date: '', time: '', latitude: null, longitude: null },
  ])
  assert.equal(groups.length, 3)
  assert.deepEqual(groups[0].photoIds, ['a', 'b'])
  assert.equal(groups[0].id, 'photos:a')
  assert.equal(groups[0].hasLocation, true)
  assert.deepEqual(groups[1].photoIds, ['c'])
  assert.deepEqual(groups[2], { id: 'photos:d', photoIds: ['d'], date: '', time: '', latitude: null, longitude: null, hasLocation: false, dateMissing: true })
})

test('groupPhotos splits distant, late and other-day photos', () => {
  const far = groupPhotos([
    { id: 'a', date: '2026-10-02', time: '10:00', latitude: 35.0, longitude: 135.0 },
    { id: 'b', date: '2026-10-02', time: '10:10', latitude: 35.01, longitude: 135.0 },
  ])
  assert.equal(far.length, 2)
  const late = groupPhotos([
    { id: 'a', date: '2026-10-02', time: '10:00', latitude: 35.0, longitude: 135.0 },
    { id: 'b', date: '2026-10-02', time: '12:01', latitude: 35.0, longitude: 135.0 },
  ])
  assert.equal(late.length, 2)
  const otherDay = groupPhotos([
    { id: 'a', date: '2026-10-02', time: '10:00', latitude: 35.0, longitude: 135.0 },
    { id: 'b', date: '2026-10-03', time: '10:05', latitude: 35.0, longitude: 135.0 },
  ])
  assert.equal(otherDay.length, 2)
})

test('groupPhotos attaches GPS-less photos within 30 minutes, otherwise keeps them separate', () => {
  const attached = groupPhotos([
    { id: 'a', date: '2026-10-02', time: '10:00', latitude: 35.0, longitude: 135.0 },
    { id: 'n', date: '2026-10-02', time: '10:25', latitude: null, longitude: null },
  ])
  assert.equal(attached.length, 1)
  assert.deepEqual(attached[0].photoIds, ['a', 'n'])
  const separate = groupPhotos([
    { id: 'a', date: '2026-10-02', time: '10:00', latitude: 35.0, longitude: 135.0 },
    { id: 'n', date: '2026-10-02', time: '11:00', latitude: null, longitude: null },
  ])
  assert.equal(separate.length, 2)
  assert.equal(separate[1].hasLocation, false)
})

test('groupPhotos puts all undated, GPS-less photos in one group and splits 51 photos', () => {
  const undated = groupPhotos([
    { id: 'x', date: '', time: '', latitude: null, longitude: null },
    { id: 'y', date: '', time: '', latitude: null, longitude: null },
  ])
  assert.equal(undated.length, 1)
  assert.equal(undated[0].photoIds.length, 2)
  const many = groupPhotos(Array.from({ length: 51 }, (_, i) => ({
    id: `p${i}`, date: '2026-10-02', time: '10:00', latitude: 35.0, longitude: 135.0,
  })))
  assert.deepEqual(many.map(group => group.photoIds.length), [IMPORT_LIMITS.maxPhotosPerGroup, 1])
})

test('draft factories mark estimated dates and cap preview points', () => {
  const photo = { id: 'a', file: {}, fileName: 'IMG_1.jpg' }
  const group = { id: 'photos:a', photoIds: ['a'], date: '', time: '', latitude: null, longitude: null, hasLocation: false, dateMissing: true }
  const draft = createPhotoDraft(group, new Map([['a', photo]]), { fallbackDate: '2026-10-01' })
  assert.equal(draft.dateSource, 'fallback')
  assert.equal(draft.date, '2026-10-01')
  assert.equal(draft.locationSource, 'none')
  assert.equal(draft.title, 'IMG_1')
  const exif = createPhotoDraft({ ...group, date: '2026-10-02', time: '10:00', hasLocation: true, latitude: 1, longitude: 2 }, { a: photo }, { fallbackDate: 'x' })
  assert.equal(exif.dateSource, 'exif')
  assert.equal(exif.locationSource, 'exif')

  const points = Array.from({ length: 5000 }, (_, i) => ({ latitude: 35 + i * 1e-5, longitude: 135, time: null }))
  const track = createTrackDraft({ id: 'gpx:1', file: {}, fileName: 'walk.gpx' }, { points, startTime: new Date(2026, 9, 2, 9, 30), distanceKm: 5, durationMinutes: 10 }, { fallbackDate: '2026-10-01' })
  assert.equal(track.dateSource, 'gpx')
  assert.equal(track.date, '2026-10-02')
  assert.ok(track.track.previewPoints.length <= 2001)
  const noTime = createTrackDraft({ id: 'gpx:2', file: {}, fileName: 'a.gpx' }, { points, startTime: null, distanceKm: 5, durationMinutes: 0 }, { fallbackDate: '2026-10-01' })
  assert.equal(noTime.dateSource, 'fallback')
  const place = createPlaceDraft({ id: 'place:1', latitude: 1, longitude: 2, name: '서울역' }, { fallbackDate: '2026-10-01', source: 'search' })
  assert.equal(place.flagDate, false)
  assert.equal(place.title, '서울역')
})

test('sortDrafts and groupDraftsByDate order by date and put review items last', () => {
  const drafts = [
    { id: 'g', kind: 'gpx', date: '2026-10-02', time: '', dateSource: 'gpx', flagDate: true },
    { id: 'p', kind: 'photos', date: '2026-10-02', time: '10:05', dateSource: 'exif', flagDate: true },
    { id: 'r', kind: 'photos', date: '2026-10-01', time: '', dateSource: 'fallback', flagDate: true },
    { id: 'l', kind: 'place', date: '2026-10-01', time: '', dateSource: 'fallback', flagDate: false },
  ]
  assert.deepEqual(sortDrafts(drafts).map(d => d.id), ['l', 'r', 'p', 'g'])
  const groups = groupDraftsByDate(drafts, '2026-10-01')
  assert.deepEqual(groups.map(g => g.label), ['2026-10-01 · 1일차', '2026-10-02 · 2일차', '날짜 확인 필요'])
  assert.deepEqual(groups.at(-1).drafts.map(d => d.id), ['r'])
  assert.equal(groupDraftsByDate(drafts)[0].label, '2026-10-01')
})

test('inferPeriod ignores fallback and excluded drafts; suggestPlanId picks the latest containing trip', () => {
  const period = inferPeriod([
    { include: true, dateSource: 'exif', date: '2026-10-03' },
    { include: true, dateSource: 'gpx', date: '2026-10-01' },
    { include: true, dateSource: 'fallback', date: '2025-01-01' },
    { include: false, dateSource: 'exif', date: '2030-01-01' },
  ])
  assert.deepEqual(period, { startDate: '2026-10-01', endDate: '2026-10-03' })
  assert.equal(inferPeriod([{ include: true, dateSource: 'fallback', date: '2026-01-01' }]), null)
  const plans = [
    { id: 1, startDate: '2026-09-01', endDate: '2026-12-31' },
    { id: 2, startDate: '2026-10-01', endDate: '2026-10-05' },
    { id: 3, startDate: null, endDate: null },
    { id: 4, startDate: '2026-10-02', endDate: '2026-10-05' },
  ]
  assert.equal(suggestPlanId(plans, period), '2')
  assert.equal(suggestPlanId(plans, { startDate: '2027-01-01', endDate: '2027-01-02' }), '')
  assert.equal(suggestPlanId(plans, null), '')
})

test('validateDraft reports each problem and skips excluded drafts', () => {
  const base = { include: true, kind: 'photos', title: 'ok', memo: '', date: '2026-10-01' }
  assert.deepEqual(validateDraft(base), [])
  assert.deepEqual(validateDraft({ ...base, title: '  ' }), ['제목을 입력해 주세요.'])
  assert.deepEqual(validateDraft({ ...base, title: 'x'.repeat(121) }), ['제목은 120자 이하로 입력해 주세요.'])
  assert.deepEqual(validateDraft({ ...base, memo: 'x'.repeat(501) }), ['메모는 500자 이하로 입력해 주세요.'])
  assert.deepEqual(validateDraft({ ...base, date: '' }), ['날짜를 입력해 주세요.'])
  assert.deepEqual(validateDraft({ ...base, kind: 'gpx', track: { distanceKm: 0.004 } }), ['경로가 너무 짧습니다(10m 미만).'])
  assert.deepEqual(validateDraft({ ...base, include: false, title: '' }), [])
})

test('summarizeDrafts counts records, photos, routes and states', () => {
  const summary = summarizeDrafts([
    { include: true, kind: 'photos', title: 'a', date: '2026-10-01', photos: [{}, {}], latitude: 1, longitude: 2, status: 'draft' },
    { include: true, kind: 'photos', title: '', date: '2026-10-01', photos: [{}], latitude: null, longitude: null, status: 'failed' },
    { include: true, kind: 'gpx', title: 'g', date: '2026-10-01', track: { distanceKm: 1 }, status: 'saved' },
    { include: false, kind: 'place', title: 'x', date: '2026-10-01', status: 'draft' },
  ])
  assert.equal(summary.records, 2)
  assert.equal(summary.photos, 3)
  assert.equal(summary.routes, 1)
  assert.equal(summary.excluded, 1)
  assert.equal(summary.needsLocation, 1)
  assert.equal(summary.needsReview, 1)
  assert.equal(summary.saved, 1)
  assert.equal(summary.failed, 1)
  assert.equal(summary.pending, 2)
})

test('buildMemoryPayload trims, truncates and keeps null coordinates', () => {
  const payload = buildMemoryPayload({ date: '2026-10-02', time: '', category: ' 관광지 ', title: ` ${'가'.repeat(121)} `, country: '', region: '교토', placeName: '', latitude: null, longitude: null, memo: '  ' })
  assert.equal(payload.title.length, 120)
  assert.equal(payload.memoryTime, null)
  assert.equal(payload.category, '관광지')
  assert.equal(payload.country, null)
  assert.equal(payload.latitude, null)
  assert.equal(payload.memo, null)
  assert.equal(payload.sharedWithCommunity, false)
  assert.equal(buildMemoryPayload({ date: '2026-10-02', title: 't', latitude: 34.9, longitude: 135.7, memo: ' 메모 ' }).memo, '메모')
})

test('buildRoutePayload enforces the distance floor, step count and point limit', () => {
  const points = Array.from({ length: 2000 }, (_, i) => ({ latitude: 35 + i * 1e-5, longitude: 135.123456789 }))
  const draft = { date: '2026-10-02', title: 'walk', memo: '', track: { points, distanceKm: 0.004, durationMinutes: 12, transportMode: 'WALK' } }
  const payload = buildRoutePayload(draft, '#112233')
  assert.equal(payload.distanceKm, 0.01)
  assert.equal(payload.stepCount, 14)
  assert.equal(payload.sourceType, 'GPX')
  assert.equal(payload.lineColorHex, '#112233')
  assert.ok(payload.points.length <= 901)
  assert.equal(payload.points[0].pointType, 'ROUTE')
  assert.equal(payload.points[0].longitude, 135.1234568)
  assert.equal(buildRoutePayload({ ...draft, track: { ...draft.track, transportMode: 'BUS', distanceKm: 5 } }).stepCount, 0)
})

test('buildNewPlanPayload sets status from the end date and keeps defaults', () => {
  const past = buildNewPlanPayload({ name: ' 여행 ', startDate: '2020-01-01', endDate: '2020-01-02', noPeriod: false }, '2026-11-22')
  assert.equal(past.status, 'COMPLETED')
  assert.equal(past.name, '여행')
  assert.equal(past.homeCurrency, 'KRW')
  assert.equal(past.headCount, 1)
  assert.equal(past.colorHex, '#3182F6')
  assert.equal(buildNewPlanPayload({ name: 'a', startDate: '2030-01-01', endDate: '2030-01-02' }, '2026-11-22').status, 'PLANNED')
  const periodless = buildNewPlanPayload({ name: 'a', startDate: '2020-01-01', endDate: '2020-01-02', noPeriod: true }, '2026-11-22')
  assert.deepEqual([periodless.startDate, periodless.endDate, periodless.noPeriod, periodless.status], [null, null, true, 'PLANNED'])
})

test('toImportErrorMessage maps technical errors to Korean messages', () => {
  assert.match(toImportErrorMessage(Object.assign(new Error('File upload failed.'), { code: 'travel-media-too-large' })), /15MB/)
  assert.match(toImportErrorMessage(new Error('The source image could not be decoded.')), /읽을 수 없습니다/)
  assert.match(toImportErrorMessage(new Error('Presigned travel media upload is unavailable.')), /파일 저장소/)
  assert.match(toImportErrorMessage(new Error('File upload failed.')), /네트워크를 확인/)
  assert.match(toImportErrorMessage(Object.assign(new Error('요청 값을 확인해 주세요.'), { status: 400 })), /입력값을 확인/)
  assert.match(toImportErrorMessage(Object.assign(new Error('x'), { status: 403 })), /권한/)
  assert.match(toImportErrorMessage(new TypeError('Failed to fetch')), /네트워크 오류/)
  assert.equal(toImportErrorMessage(new Error('서버가 바쁩니다.')), '서버가 바쁩니다.')
  assert.equal(toImportErrorMessage(new Error('boom')), '저장하지 못했습니다.')
})

test('formatImportNotice omits zero counts', () => {
  assert.equal(formatImportNotice({ records: 3, photos: 7, routes: 1, planName: '가을 교토 여행' }), '기록 3건 · 사진 7장 · 경로 1개를 ‘가을 교토 여행’에 저장했습니다.')
  assert.equal(formatImportNotice({ records: 0, photos: 0, routes: 2, planName: 'A' }), '경로 2개를 ‘A’에 저장했습니다.')
})

function harness({ failPresign = 0, failGpx = false, failPlan = false } = {}) {
  const calls = []
  let remainingPresignFailures = failPresign
  let memoryId = 500
  const api = {
    async createPlan(payload) { calls.push(['plan']); if (failPlan) throw Object.assign(new Error('x'), { status: 400 }); return { id: 91, ...payload } },
    async createMemory(planId, payload) { calls.push(['memory', String(planId), payload.title]); return { id: ++memoryId } },
    async uploadMemoryPhotos(id, files) {
      calls.push(['photos', id, files.length])
      if (remainingPresignFailures > 0) { remainingPresignFailures -= 1; throw new Error('Presigned travel media upload is unavailable.') }
    },
    async createRoute(planId) { calls.push(['route', String(planId)]); return { id: 700 } },
    async uploadRouteGpx(id) { calls.push(['gpx', id]); if (failGpx) { failGpx = false; throw new Error('File upload failed.') } },
  }
  return { api, calls }
}

function makeDrafts() {
  return [
    { id: 'photos:a', kind: 'photos', include: true, title: 'A', date: '2026-10-02', time: '10:00', category: '장소', memo: '', latitude: 1, longitude: 2, photos: [{ file: 'f1' }, { file: 'f2' }], progress: { memoryId: null, routeId: null, photosUploaded: false, gpxUploaded: false }, status: 'draft' },
    { id: 'gpx:a', kind: 'gpx', include: true, title: 'G', date: '2026-10-02', time: '11:00', memo: '', track: { file: 'g1', points: [{ latitude: 1, longitude: 1 }, { latitude: 1.1, longitude: 1 }], distanceKm: 11, durationMinutes: 10, transportMode: 'WALK' }, progress: { memoryId: null, routeId: null, photosUploaded: false, gpxUploaded: false }, status: 'draft' },
  ]
}

function applier(drafts) {
  return (id, patch) => Object.assign(drafts.find(d => d.id === id), patch)
}

test('runImportSave creates the trip, memories, photos, routes and GPX in order', async () => {
  const { api, calls } = harness()
  const drafts = makeDrafts()
  const target = { mode: 'new', planId: '', name: '새 여행', startDate: '2026-10-02', endDate: '2026-10-02', noPeriod: false, createdPlanId: '' }
  const result = await runImportSave({ drafts, target, api, today: '2026-11-22', onPlan: id => { target.createdPlanId = id }, onChange: applier(drafts) })
  assert.deepEqual(result, { planId: '91', ok: true })
  assert.deepEqual(calls.map(c => c[0]), ['plan', 'memory', 'photos', 'route', 'gpx'])
  assert.deepEqual(drafts.map(d => d.status), ['saved', 'saved'])
})

test('runImportSave retries only the failed upload without creating the memory again', async () => {
  const { api, calls } = harness({ failPresign: 1 })
  const drafts = makeDrafts()
  const target = { mode: 'existing', planId: '90', createdPlanId: '' }
  const onChange = applier(drafts)
  const first = await runImportSave({ drafts, target, api, today: '2026-11-22', onChange })
  assert.equal(first.ok, false)
  assert.equal(drafts[0].status, 'failed')
  assert.match(drafts[0].error, /기록은 저장되었습니다/)
  assert.doesNotMatch(drafts[0].error, /Presigned/)
  assert.equal(drafts[0].progress.memoryId, 501)
  const second = await runImportSave({ drafts, target, api, today: '2026-11-22', onChange })
  assert.equal(second.ok, true)
  assert.equal(calls.filter(c => c[0] === 'memory').length, 1)
  assert.equal(calls.filter(c => c[0] === 'photos').length, 2)
  assert.equal(calls.filter(c => c[0] === 'route').length, 1)
})

test('runImportSave stops before saving anything when the new trip cannot be created', async () => {
  const { api, calls } = harness({ failPlan: true })
  const drafts = makeDrafts()
  const target = { mode: 'new', planId: '', name: 'x', createdPlanId: '' }
  const result = await runImportSave({ drafts, target, api, today: '2026-11-22', onPlan() {}, onChange: applier(drafts) })
  assert.equal(result.ok, false)
  assert.deepEqual(calls.map(c => c[0]), ['plan'])
  assert.deepEqual(drafts.map(d => d.status), ['failed', 'failed'])
})

test('runImportSave does not create the new trip twice on retry', async () => {
  const { api, calls } = harness({ failPresign: 1 })
  const drafts = makeDrafts()
  const target = { mode: 'new', planId: '', name: 'x', startDate: '2026-10-02', endDate: '2026-10-02', createdPlanId: '' }
  const options = { drafts, target, api, today: '2026-11-22', onPlan: id => { target.createdPlanId = id }, onChange: applier(drafts) }
  await runImportSave(options)
  await runImportSave(options)
  assert.equal(calls.filter(c => c[0] === 'plan').length, 1)
})

test('runImportSave marks a GPX archive failure as a warning and only re-uploads the original', async () => {
  const { api, calls } = harness({ failGpx: true })
  const drafts = makeDrafts()
  const target = { mode: 'existing', planId: '90' }
  const onChange = applier(drafts)
  const first = await runImportSave({ drafts, target, api, today: '2026-11-22', onChange })
  assert.equal(first.ok, false)
  assert.equal(drafts[1].status, 'warning')
  assert.equal(drafts[1].error, '경로는 저장됨 · 원본 GPX 보관 실패')
  const second = await runImportSave({ drafts, target, api, today: '2026-11-22', onChange })
  assert.equal(second.ok, true)
  assert.equal(calls.filter(c => c[0] === 'route').length, 1)
  assert.equal(calls.filter(c => c[0] === 'gpx').length, 2)
})

test('runImportSave skips excluded and already saved drafts', async () => {
  const { api, calls } = harness()
  const drafts = makeDrafts()
  drafts[0].include = false
  drafts[1].status = 'saved'
  const result = await runImportSave({ drafts, target: { mode: 'existing', planId: '90' }, api, today: '2026-11-22', onChange: applier(drafts) })
  assert.equal(result.ok, true)
  assert.deepEqual(calls, [])
})
