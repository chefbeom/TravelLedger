import { createTravelUploadScheduler } from '../../src/lib/api.js'
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  IMPORT_LIMITS,
  PHOTO_ONLY_REJECT,
  buildAttachOptions,
  describeAttachReason,
  describePhotoAgainstRecord,
  formatRecordPhotoNotice,
  minutesApart,
  normalizeClock,
  suggestAttachRecord,
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
  IMPORT_PHOTO_GROUP_CONCURRENCY,
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

// ---------------------------------------------------------------- photo add / attach

const REC = { id: 501, title: '기요미즈데라', memoryDate: '2026-10-01', memoryTime: '10:00', latitude: 34.9949, longitude: 135.785 }
function photoDraft(overrides = {}) {
  return { kind: 'photos', date: '2026-10-01', latitude: 34.99491, longitude: 135.78501, photos: [{ time: '10:20' }], ...overrides }
}

test('classifyImportFile photosOnly rejects GPX and other files but keeps the default behavior', () => {
  assert.equal(classifyImportFile({ name: 'a.gpx', size: 10 }, { photosOnly: true }).reason, PHOTO_ONLY_REJECT)
  assert.equal(classifyImportFile({ name: 'a.txt', size: 10 }, { photosOnly: true }).reason, PHOTO_ONLY_REJECT)
  assert.equal(classifyImportFile({ name: 'a.jpg', size: 10 }, { photosOnly: true }).kind, 'photo')
  assert.match(classifyImportFile({ name: 'a.heic', size: 10 }, { photosOnly: true }).reason, /HEIC/)
  assert.match(classifyImportFile({ name: 'a.jpg', size: 16 * MB }, { photosOnly: true }).reason, /15MB/)
  assert.equal(classifyImportFile({ name: 'a.gpx', size: 10 }).kind, 'gpx')
})

test('normalizeClock and minutesApart', () => {
  assert.equal(normalizeClock('10:00:00'), '10:00')
  assert.equal(normalizeClock('9:5'), '09:05')
  assert.equal(normalizeClock(''), '')
  assert.equal(normalizeClock('abc'), '')
  assert.equal(minutesApart('10:00', '10:45'), 45)
  assert.equal(minutesApart('', '10:45'), null)
})

test('suggestAttachRecord matches nearby same-day records', () => {
  const s = suggestAttachRecord(photoDraft(), [REC])
  assert.equal(s.recordId, 501)
  assert.equal(s.reason, 'nearby')
  assert.ok(s.distanceMeters < 50)
})

test('suggestAttachRecord rejects far, late, other-day, undated and non-photo drafts', () => {
  assert.equal(suggestAttachRecord(photoDraft({ latitude: 34.9979, longitude: 135.785 }), [REC]), null)
  assert.equal(suggestAttachRecord(photoDraft({ photos: [{ time: '12:01' }] }), [REC]), null)
  assert.equal(suggestAttachRecord(photoDraft({ date: '2026-10-02' }), [REC]), null)
  assert.equal(suggestAttachRecord(photoDraft({ date: '' }), [REC]), null)
  assert.equal(suggestAttachRecord(photoDraft({ kind: 'gpx' }), [REC]), null)
  assert.equal(suggestAttachRecord(photoDraft({ kind: 'place' }), [REC]), null)
})

test('suggestAttachRecord skips the time condition when the record has no time', () => {
  const s = suggestAttachRecord(photoDraft({ photos: [{ time: '23:00' }] }), [{ ...REC, memoryTime: '' }])
  assert.equal(s.recordId, 501)
})

test('suggestAttachRecord handles photos without GPS by time only', () => {
  const noGps = photoDraft({ latitude: null, longitude: null })
  assert.equal(suggestAttachRecord(noGps, [REC]).reason, 'time')
  assert.equal(suggestAttachRecord(noGps, [{ ...REC, memoryTime: '' }]), null)
  assert.equal(suggestAttachRecord(photoDraft({ latitude: null, longitude: null, photos: [{ time: '11:00' }] }), [REC]), null)
})

test('suggestAttachRecord prefers the closest candidate', () => {
  const far = { ...REC, id: 502, latitude: 34.9958, longitude: 135.785 }
  assert.equal(suggestAttachRecord(photoDraft(), [far, REC]).recordId, 501)
})

test('describeAttachReason formats meters, kilometers and minutes', () => {
  assert.equal(describeAttachReason({ reason: 'nearby', distanceMeters: 10.4 }), '같은 날 약 10m 거리에서 찍어 자동으로 골랐습니다.')
  assert.equal(describeAttachReason({ reason: 'nearby', distanceMeters: 1500 }), '같은 날 약 1.5km 거리에서 찍어 자동으로 골랐습니다.')
  assert.equal(describeAttachReason({ reason: 'time', minutes: 12 }), '같은 날 12분 차이로 찍어 자동으로 골랐습니다.')
})

test('buildAttachOptions lists the suggestion first, then the same day, then the rest', () => {
  const other = { id: 502, title: '점심', memoryDate: '2026-10-02', memoryTime: '', latitude: null, longitude: null }
  const sameDay = { id: 503, title: 'x'.repeat(50), memoryDate: '2026-10-01', memoryTime: '09:00', latitude: 35, longitude: 136 }
  const options = buildAttachOptions(photoDraft(), [other, sameDay, REC])
  assert.equal(options[0].label, '새 기록으로 저장')
  assert.equal(options[1].value, '501')
  assert.equal(options[1].label, '기존 기록: 기요미즈데라 · 10-01')
  assert.equal(options[2].value, '503')
  assert.ok(options[2].label.includes('…'))
  assert.equal(options[3].value, '502')
  assert.equal(buildAttachOptions(photoDraft(), []).length, 1)
})

test('validateDraft skips checks for drafts that attach to an existing record', () => {
  assert.deepEqual(validateDraft({ kind: 'photos', include: true, title: '', date: '', attachRecordId: '501' }), [])
  assert.ok(validateDraft({ kind: 'photos', include: true, title: '', date: '2026-10-01' }).length)
})

test('summarizeDrafts counts attached drafts separately', () => {
  const drafts = [
    { kind: 'photos', include: true, title: 'A', date: 'd', photos: [1, 2], latitude: 1, longitude: 2, status: 'saved' },
    { kind: 'photos', include: true, date: 'd', photos: [1], attachRecordId: '501', status: 'saved' },
    { kind: 'photos', include: true, date: 'd', photos: [1, 2, 3], attachRecordId: '501', status: 'draft' },
    { kind: 'photos', include: true, date: 'd', photos: [1], attachRecordId: '502', status: 'saved' },
  ]
  const s = summarizeDrafts(drafts)
  assert.equal(s.records, 1)
  assert.equal(s.attached, 3)
  assert.equal(s.attachedPhotos, 5)
  assert.equal(s.photos, 7)
  assert.equal(s.savedAttached, 2)
  assert.equal(s.savedAttachedPhotos, 2)
  assert.equal(s.savedPhotos, 4)
  assert.equal(s.needsLocation, 0)
})

test('formatImportNotice mentions attached photos and keeps the legacy text otherwise', () => {
  assert.equal(formatImportNotice({ records: 1, photos: 3, planName: '가을 교토 여행' }), '기록 1건 · 사진 3장을 ‘가을 교토 여행’에 저장했습니다.')
  assert.equal(
    formatImportNotice({ records: 1, photos: 3, planName: '가을 교토 여행', attachedRecords: 1, attachedPhotos: 1 }),
    '기록 1건 · 사진 3장을 ‘가을 교토 여행’에 저장했습니다. 그중 사진 1장은 기존 기록 1건에 더했습니다.',
  )
  assert.equal(
    formatImportNotice({ records: 0, photos: 2, planName: 'A', attachedRecords: 1, attachedPhotos: 2 }),
    '사진 2장을 ‘A’에 기존 기록 1건에 더했습니다.',
  )
})

test('formatRecordPhotoNotice covers the four branches', () => {
  assert.equal(formatRecordPhotoNotice({ addedPhotos: 2, title: 'A', recordChanged: true }), '기록을 수정했습니다. 사진 2장을 추가했습니다.')
  assert.equal(formatRecordPhotoNotice({ recordChanged: true }), '기록을 수정했습니다.')
  assert.equal(formatRecordPhotoNotice({ addedPhotos: 2, title: 'A' }), '사진 2장을 ‘A’ 기록에 추가했습니다.')
  assert.equal(formatRecordPhotoNotice({}), '기록을 수정했습니다.')
})

test('describePhotoAgainstRecord reports GPS, distance and date differences', () => {
  assert.deepEqual(describePhotoAgainstRecord({ date: '2026-10-01' }, REC), { hasGps: false, distanceMeters: null, far: false, dateDiffers: false })
  const near = describePhotoAgainstRecord({ latitude: 34.9953, longitude: 135.785, date: '2026-10-01' }, REC)
  assert.equal(near.far, false)
  assert.ok(near.distanceMeters > 30 && near.distanceMeters < 50)
  const far = describePhotoAgainstRecord({ latitude: 35.0057, longitude: 135.785 }, REC)
  assert.equal(far.far, true)
  assert.equal(describePhotoAgainstRecord({ date: '2026-10-03' }, REC).dateDiffers, true)
})

function attachHarness({ failPresign = 0 } = {}) {
  const calls = []
  let left = failPresign
  let memoryId = 600
  const api = {
    async createMemory(planId, payload) { calls.push(['memory', payload.title]); return { id: ++memoryId } },
    async uploadMemoryPhotos(id, files, onProgress) {
      calls.push(['photos', String(id), files.length, typeof onProgress])
      onProgress?.({ phase: 'uploading', current: 1, total: 2 })
      if (left > 0) { left -= 1; throw new Error('Presigned travel media upload is unavailable.') }
    },
  }
  return { api, calls }
}

function attachDrafts() {
  const progress = () => ({ memoryId: null, routeId: null, photosUploaded: false, gpxUploaded: false })
  return [
    { id: 'photos:a', kind: 'photos', include: true, title: 'A', date: '2026-10-02', time: '10:00', category: '장소', memo: '', latitude: 1, longitude: 2, photos: [{ file: 'f1' }, { file: 'f2' }], progress: progress(), status: 'draft', attachRecordId: '' },
    { id: 'photos:b', kind: 'photos', include: true, title: '', date: '2026-10-01', time: '10:20', photos: [{ file: 'f3' }], progress: progress(), status: 'draft', attachRecordId: '501' },
  ]
}

test('runImportSave uploads to the existing record without creating a memory', async () => {
  const { api, calls } = attachHarness()
  const drafts = attachDrafts()
  const changes = []
  const result = await runImportSave({
    drafts, target: { mode: 'existing', planId: '90' }, api, today: '2026-11-22',
    onChange: (id, patch) => { changes.push([id, patch]); Object.assign(drafts.find(d => d.id === id), patch) },
  })
  assert.equal(result.ok, true)
  assert.equal(calls.filter(c => c[0] === 'memory').length, 1)
  assert.equal(calls.filter(c => c[0] === 'photos').length, 2)
  const attachedCall = calls.find(c => c[0] === 'photos' && c[1] === '501')
  assert.equal(attachedCall[2], 1)
  assert.equal(attachedCall[3], 'function')
  assert.equal(drafts[1].progress.memoryId, null)
  assert.ok(changes.some(([id, patch]) => id === 'photos:b' && patch.upload?.phase === 'uploading' && patch.upload.total === 2))
  assert.deepEqual(drafts.map(d => d.status), ['saved', 'saved'])
})

test('runImportSave retries only the upload for an attached draft', async () => {
  const { api, calls } = attachHarness({ failPresign: 2 })
  const drafts = attachDrafts()
  const onChange = (id, patch) => Object.assign(drafts.find(d => d.id === id), patch)
  const first = await runImportSave({ drafts, target: { mode: 'existing', planId: '90' }, api, today: '2026-11-22', onChange })
  assert.equal(first.ok, false)
  assert.equal(drafts[1].status, 'failed')
  assert.match(drafts[1].error, /다시 시도하면 사진만 올립니다/)
  assert.doesNotMatch(drafts[1].error, /Presigned/)
  const second = await runImportSave({ drafts, target: { mode: 'existing', planId: '90' }, api, today: '2026-11-22', onChange })
  assert.equal(second.ok, true)
  assert.equal(calls.filter(c => c[0] === 'memory').length, 1)
  assert.equal(calls.filter(c => c[0] === 'photos' && c[1] === '501').length, 2)
})

// ---------------------------------------------------------------- concurrent photo save

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))

function manyPhotoDrafts(count, photosPer = 2) {
  return Array.from({ length: count }, (_, i) => ({
    id: `photos:${i}`, kind: 'photos', include: true, title: `T${i}`, date: '2026-10-02',
    time: `${String(8 + Math.floor(i / 6)).padStart(2, '0')}:${String((i % 6) * 10).padStart(2, '0')}`,
    category: '장소', memo: '', latitude: 1, longitude: 2,
    photos: Array.from({ length: photosPer }, (_, k) => ({ file: `f${i}-${k}` })),
    progress: { memoryId: null, routeId: null, photosUploaded: false, gpxUploaded: false }, status: 'draft',
  }))
}

// Fake api modelled on the real upload: prepare-all, 3 round trips, parallel PUTs.
function timedApi({ withScheduler, failFor = null, d = { create: 5, prep: 10, trip: 8, put: 20 } } = {}) {
  const log = []
  let memoryId = 0, inFlightCalls = 0, maxCalls = 0, inFlightPuts = 0, maxPuts = 0
  const api = {
    async createPlan() { return { id: 91 } },
    async createMemory(_planId, payload) { await sleep(d.create); log.push(['memory', payload.title]); return { id: ++memoryId } },
    async uploadMemoryPhotos(id, files, _onProgress, options = {}) {
      const sched = options.scheduler
      log.push(['upload-start', id])
      inFlightCalls += 1; maxCalls = Math.max(maxCalls, inFlightCalls)
      try {
        if (failFor && files.includes(failFor)) { await sleep(d.trip); throw new Error('Presigned travel media upload is unavailable.') }
        const run = (kind, fn) => (sched ? sched[kind](fn) : fn())
        const mapAll = async (items, limit, fn) => {
          if (sched) return Promise.all(items.map(item => fn(item)))
          let next = 0
          await Promise.all(Array.from({ length: Math.min(limit, items.length) }, async () => { while (next < items.length) await fn(items[next++]) }))
        }
        await mapAll(files, 2, () => run('prepare', () => sleep(d.prep)))
        await sleep(d.trip)
        await mapAll(files, 5, () => run('upload', async () => {
          inFlightPuts += 1; maxPuts = Math.max(maxPuts, inFlightPuts)
          await sleep(d.put)
          inFlightPuts -= 1
        }))
        await sleep(d.trip)
      } finally { inFlightCalls -= 1 }
    },
  }
  if (withScheduler) api.createUploadScheduler = () => createTravelUploadScheduler()
  return { api, log, stats: () => ({ maxCalls, maxPuts }) }
}

async function timedRun(drafts, opts) {
  const h = timedApi(opts)
  const started = Date.now()
  const result = await runImportSave({ drafts, target: { mode: 'existing', planId: '90' }, api: h.api, today: '2026-11-22', onChange: applier(drafts) })
  return { ...h, result, elapsed: Date.now() - started }
}

test('photo drafts: records are created in order before any upload starts and uploads overlap', async () => {
  const drafts = manyPhotoDrafts(10)
  const { log, stats, result } = await timedRun(drafts, { withScheduler: true })
  assert.equal(result.ok, true)
  const firstUpload = log.findIndex(e => e[0] === 'upload-start')
  assert.deepEqual(log.slice(0, firstUpload).map(e => e[1]), drafts.map((_, i) => `T${i}`))
  assert.equal(log.filter(e => e[0] === 'memory').length, 10)
  assert.ok(stats().maxCalls > 1)
  assert.ok(stats().maxCalls <= IMPORT_PHOTO_GROUP_CONCURRENCY)
  assert.ok(stats().maxPuts <= 5)
  assert.ok(drafts.every(d => d.status === 'saved' && d.progress.photosUploaded))
})

test('createTravelUploadScheduler caps prepare and upload concurrency separately', async () => {
  const scheduler = createTravelUploadScheduler({ prepareConcurrency: 2, uploadConcurrency: 3 })
  const track = async kind => {
    let active = 0, max = 0
    await Promise.all(Array.from({ length: 12 }, () => scheduler[kind](async () => { active += 1; max = Math.max(max, active); await sleep(3); active -= 1 })))
    return max
  }
  assert.equal(await track('prepare'), 2)
  assert.equal(await track('upload'), 3)
  await assert.rejects(scheduler.upload(async () => { throw new Error('boom') }), /boom/)
  assert.equal(await scheduler.upload(async () => 'ok'), 'ok')
})

test('photo drafts: one failing draft leaves the others saved with the usual error text', async () => {
  const drafts = manyPhotoDrafts(6)
  const { result } = await timedRun(drafts, { withScheduler: true, failFor: 'f2-0' })
  assert.equal(result.ok, false)
  assert.equal(drafts.filter(d => d.status === 'saved').length, 5)
  const failed = drafts.find(d => d.id === 'photos:2')
  assert.equal(failed.status, 'failed')
  assert.match(failed.error, /기록은 저장되었습니다\. 다시 시도하면 사진만 올립니다\.$/)
  assert.ok(failed.progress.memoryId)
  const seq = manyPhotoDrafts(6)
  await timedRun(seq, { withScheduler: false, failFor: 'f2-0' })
  assert.equal(seq.find(d => d.id === 'photos:2').error, failed.error)
})

test('photo drafts: resume skips finished drafts and never recreates records', async () => {
  const drafts = manyPhotoDrafts(6)
  const first = await timedRun(drafts, { withScheduler: true, failFor: 'f3-1' })
  assert.equal(first.result.ok, false)
  const second = await timedRun(drafts, { withScheduler: true })
  assert.equal(second.result.ok, true)
  assert.equal(second.log.filter(e => e[0] === 'memory').length, 0)
  assert.equal(second.log.filter(e => e[0] === 'upload-start').length, 1)
  assert.ok(drafts.every(d => d.status === 'saved'))
})

test('photo drafts: attach drafts upload to the existing record through the scheduler too', async () => {
  const drafts = manyPhotoDrafts(3)
  drafts[1].attachRecordId = 777
  const { log, result } = await timedRun(drafts, { withScheduler: true })
  assert.equal(result.ok, true)
  assert.equal(log.filter(e => e[0] === 'memory').length, 2)
  assert.ok(log.some(e => e[0] === 'upload-start' && e[1] === '777'))
})

test('simulated save time: concurrent groups beat the sequential flow (30 drafts x 2 photos)', async () => {
  const oldRun = await timedRun(manyPhotoDrafts(30), { withScheduler: false })
  const newRun = await timedRun(manyPhotoDrafts(30), { withScheduler: true })
  console.log(`simulated save, 30 drafts x 2 photos: old sequential ${oldRun.elapsed} ms, new concurrent ${newRun.elapsed} ms`)
  assert.ok(newRun.stats().maxPuts <= 5)
  assert.ok(newRun.elapsed < oldRun.elapsed * 0.6)
})
