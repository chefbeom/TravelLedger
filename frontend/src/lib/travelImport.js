// Pure helpers for the travel import dialog (photos, GPX tracks, places).
// No browser APIs here: File objects are opaque values (only name/type/size are read)
// so everything can be verified with node:test.

export const IMPORT_LIMITS = Object.freeze({
  maxPhotoBytes: 15 * 1024 * 1024, // same as api.js MAX_TRAVEL_MEDIA_FILE_SIZE
  maxGpxBytes: 10 * 1024 * 1024, // server MAX_GPX_FILE_SIZE
  maxFilesPerImport: 200,
  maxPhotosPerGroup: 50,
  groupDistanceMeters: 250, // same default as recordMapPhotoLocation.hasDistantPhotoLocations
  groupGapMinutes: 120,
  attachGapMinutes: 30,
  maxSavedRoutePoints: 900,
  maxPreviewRoutePoints: 2000,
  minRouteDistanceKm: 0.01,
  titleMax: 120,
  memoMax: 500,
})

// Same strings as TravelMemoryPanel pinPresetOptions categories.
export const IMPORT_CATEGORIES = ['장소', '숙소', '음식점', '카페', '박물관', '관광지', '쇼핑', '이동']

const DEFAULT_PLAN_COLOR = '#3182F6'
const KIND_ORDER = { place: 0, photos: 1, gpx: 2 }
const PHOTO_EXTENSION = /\.(jpe?g|png|webp|gif|bmp)$/i
const PHOTO_TYPE = /^image\/(jpeg|png|webp|gif|bmp)$/i
const HEIC_EXTENSION = /\.(heic|heif)$/i
const HEIC_TYPE = /^image\/(heic|heif)/i

function isFiniteNumber(value) {
  return value !== null && value !== '' && value !== undefined && Number.isFinite(Number(value))
}

function pad(value) {
  return String(value).padStart(2, '0')
}

// ---------------------------------------------------------------- files

export function classifyImportFile(file) {
  const name = String(file?.name || '')
  const type = String(file?.type || '')
  const size = Number(file?.size || 0)

  if (HEIC_EXTENSION.test(name) || HEIC_TYPE.test(type)) {
    return { kind: 'rejected', reason: 'HEIC 사진은 아직 지원하지 않습니다. JPG로 저장한 뒤 다시 올려 주세요.' }
  }
  if (PHOTO_EXTENSION.test(name) || PHOTO_TYPE.test(type)) {
    if (size > IMPORT_LIMITS.maxPhotoBytes) {
      return { kind: 'rejected', reason: '사진은 한 장당 15MB 이하만 올릴 수 있습니다.' }
    }
    return { kind: 'photo' }
  }
  if (/\.gpx$/i.test(name) || /gpx/i.test(type)) {
    if (size > IMPORT_LIMITS.maxGpxBytes) {
      return { kind: 'rejected', reason: 'GPX 파일은 10MB 이하만 올릴 수 있습니다.' }
    }
    return { kind: 'gpx' }
  }
  return { kind: 'rejected', reason: '사진(JPG·PNG·WEBP·GIF·BMP)이나 GPX 파일만 추가할 수 있습니다.' }
}

// ---------------------------------------------------------------- geometry / GPX

export function haversineKm(a, b) {
  const radians = (degrees) => (degrees * Math.PI) / 180
  const deltaLat = radians(Number(b.latitude) - Number(a.latitude))
  const deltaLon = radians(Number(b.longitude) - Number(a.longitude))
  const h = Math.sin(deltaLat / 2) ** 2
    + Math.cos(radians(Number(a.latitude))) * Math.cos(radians(Number(b.latitude))) * Math.sin(deltaLon / 2) ** 2
  return 2 * 6371 * Math.asin(Math.sqrt(Math.min(1, h)))
}

// Copy of the legacy TravelRouteWorkspace algorithm (the last point is always kept).
export function thinRoutePoints(points, maxPoints = 900) {
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

function readAttribute(attributes, name) {
  const match = new RegExp(`(?:^|\\s)${name}\\s*=\\s*(?:"([^"]*)"|'([^']*)')`, 'i').exec(attributes)
  if (!match) return NaN
  const raw = (match[1] ?? match[2] ?? '').trim()
  return raw === '' ? NaN : Number(raw)
}

export function parseGpxText(text) {
  const source = String(text ?? '')
  if (!/<gpx[\s>]/i.test(source)) {
    return { ok: false, reason: 'GPX 형식이 아닙니다.' }
  }

  const lower = source.toLowerCase()
  const points = []
  const pattern = /<(trkpt|rtept)\b([^>]*)>/gi
  let match
  while ((match = pattern.exec(source)) !== null) {
    const tag = match[1].toLowerCase()
    const attributes = match[2]
    const selfClosing = /\/\s*$/.test(attributes)
    let time = null
    if (!selfClosing) {
      const closeIndex = lower.indexOf(`</${tag}>`, pattern.lastIndex)
      const body = closeIndex < 0 ? '' : source.slice(pattern.lastIndex, closeIndex)
      const timeMatch = /<time[^>]*>\s*([^<]+?)\s*<\/time>/i.exec(body)
      if (timeMatch) {
        const parsed = new Date(timeMatch[1])
        time = Number.isNaN(parsed.getTime()) ? null : parsed
      }
    }
    const latitude = readAttribute(attributes, 'lat')
    const longitude = readAttribute(attributes, 'lon')
    if (!Number.isFinite(latitude) || !Number.isFinite(longitude)
      || Math.abs(latitude) > 90 || Math.abs(longitude) > 180) {
      continue
    }
    const previous = points[points.length - 1]
    if (previous && previous.latitude === latitude && previous.longitude === longitude) {
      continue
    }
    points.push({ latitude, longitude, time })
  }

  if (points.length < 2) {
    return { ok: false, reason: '경로 점(trkpt)이 2개 이상인 GPX만 불러올 수 있습니다.' }
  }

  const times = points.map((point) => point.time).filter(Boolean)
  const startTime = times[0] ?? null
  const endTime = times[times.length - 1] ?? null
  const durationMinutes = times.length >= 2 ? Math.max(0, Math.round((endTime - startTime) / 60000)) : 0
  let distanceKm = 0
  for (let index = 1; index < points.length; index += 1) {
    distanceKm += haversineKm(points[index - 1], points[index])
  }

  return { ok: true, points, startTime, endTime, distanceKm, durationMinutes }
}

// ---------------------------------------------------------------- dates / titles

export function localDateTime(date) {
  return {
    date: `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`,
    time: `${pad(date.getHours())}:${pad(date.getMinutes())}`,
  }
}

export function stripExtension(name) {
  return String(name ?? '').replace(/\.[^./\\]+$/, '')
}

export function suggestTitle({ placeName, region, fileName } = {}) {
  const title = String(placeName || '').trim()
    || String(region || '').trim()
    || stripExtension(fileName).trim()
    || '사진 기록'
  return title.slice(0, IMPORT_LIMITS.titleMax)
}

function minutesOf(time) {
  const match = /^(\d{1,2}):(\d{2})/.exec(String(time || ''))
  return match ? Number(match[1]) * 60 + Number(match[2]) : null
}

function hasCoordinates(item) {
  return isFiniteNumber(item?.latitude) && isFiniteNumber(item?.longitude)
}

// ---------------------------------------------------------------- photo grouping

export function groupPhotos(photos, limits = IMPORT_LIMITS) {
  const sorted = (photos || [])
    .map((photo, index) => ({ photo, index }))
    .sort((left, right) => {
      const dateCompare = String(left.photo.date || '9999-99-99').localeCompare(String(right.photo.date || '9999-99-99'))
      if (dateCompare) return dateCompare
      const timeCompare = String(left.photo.time || '99:99').localeCompare(String(right.photo.time || '99:99'))
      return timeCompare || left.index - right.index
    })
    .map((entry) => entry.photo)

  const groups = []
  const maxPerGroup = limits.maxPhotosPerGroup ?? IMPORT_LIMITS.maxPhotosPerGroup

  function createGroup(photo) {
    const group = {
      id: `photos:${photo.id}`,
      photoIds: [],
      date: photo.date || '',
      time: photo.time || '',
      latitude: null,
      longitude: null,
      hasLocation: false,
      dateMissing: !photo.date,
      _gps: [],
      _times: [],
      _lastTime: null,
    }
    groups.push(group)
    return group
  }

  function addToGroup(group, photo) {
    group.photoIds.push(photo.id)
    const minutes = minutesOf(photo.time)
    if (minutes !== null) {
      group._times.push(minutes)
      group._lastTime = minutes
    }
    if (hasCoordinates(photo)) {
      group._gps.push({ latitude: Number(photo.latitude), longitude: Number(photo.longitude) })
      const count = group._gps.length
      group.latitude = group._gps.reduce((sum, point) => sum + point.latitude, 0) / count
      group.longitude = group._gps.reduce((sum, point) => sum + point.longitude, 0) / count
      group.hasLocation = true
    }
    return group
  }

  const open = (group) => group.photoIds.length < maxPerGroup

  for (const photo of sorted) {
    const date = photo.date || ''
    const minutes = minutesOf(photo.time)

    if (hasCoordinates(photo)) {
      let target = null
      for (let index = groups.length - 1; index >= 0; index -= 1) {
        if (groups[index].date === date && groups[index].hasLocation) {
          target = groups[index]
          break
        }
      }
      if (target && open(target)) {
        const distanceMeters = haversineKm(
          { latitude: target.latitude, longitude: target.longitude },
          { latitude: Number(photo.latitude), longitude: Number(photo.longitude) },
        ) * 1000
        const gapOk = minutes === null || target._lastTime === null
          || Math.abs(minutes - target._lastTime) <= limits.groupGapMinutes
        if (distanceMeters <= limits.groupDistanceMeters && gapOk) {
          addToGroup(target, photo)
          continue
        }
      }
      addToGroup(createGroup(photo), photo)
      continue
    }

    if (!date) {
      let target = null
      for (let index = groups.length - 1; index >= 0; index -= 1) {
        if (!groups[index].date && !groups[index].hasLocation) {
          target = groups[index]
          break
        }
      }
      addToGroup(target && open(target) ? target : createGroup(photo), photo)
      continue
    }

    // No GPS but dated.
    let best = null
    if (minutes !== null) {
      for (const group of groups) {
        if (group.date !== date || !open(group)) continue
        const diff = group._times.length
          ? Math.min(...group._times.map((value) => Math.abs(value - minutes)))
          : Infinity
        if (diff <= limits.attachGapMinutes && (!best || diff < best.diff)) {
          best = { group, diff }
        }
      }
    }
    if (best) {
      addToGroup(best.group, photo)
      continue
    }
    let target = null
    for (let index = groups.length - 1; index >= 0; index -= 1) {
      if (groups[index].date === date && !groups[index].hasLocation) {
        target = groups[index]
        break
      }
    }
    if (target && open(target)) {
      const gapOk = minutes === null || target._lastTime === null
        || Math.abs(minutes - target._lastTime) <= limits.groupGapMinutes
      if (gapOk) {
        addToGroup(target, photo)
        continue
      }
    }
    addToGroup(createGroup(photo), photo)
  }

  return groups.map(({ _gps, _times, _lastTime, ...group }) => group)
}

// ---------------------------------------------------------------- drafts

function lookupPhoto(photosById, id) {
  if (photosById instanceof Map) return photosById.get(id)
  return photosById?.[id]
}

function baseDraft(overrides) {
  return {
    include: true,
    title: '',
    titleEdited: false,
    time: '',
    flagDate: true,
    latitude: null,
    longitude: null,
    country: '',
    region: '',
    placeName: '',
    locationSource: 'none',
    geocoding: false,
    category: '장소',
    memo: '',
    photos: [],
    track: null,
    progress: { memoryId: null, routeId: null, photosUploaded: false, gpxUploaded: false },
    status: 'draft',
    error: '',
    ...overrides,
  }
}

export function createPhotoDraft(group, photosById, { fallbackDate = '' } = {}) {
  const photos = group.photoIds.map((id) => lookupPhoto(photosById, id)).filter(Boolean)
  const first = photos[0]
  return baseDraft({
    id: group.id,
    kind: 'photos',
    title: suggestTitle({ fileName: first?.fileName }),
    date: group.date || fallbackDate,
    time: group.time || '',
    dateSource: group.date ? 'exif' : 'fallback',
    latitude: group.hasLocation ? Number(Number(group.latitude).toFixed(7)) : null,
    longitude: group.hasLocation ? Number(Number(group.longitude).toFixed(7)) : null,
    locationSource: group.hasLocation ? 'exif' : 'none',
    photos,
  })
}

export function createTrackDraft({ id, file, fileName }, parsed, { fallbackDate = '', planColorHex } = {}) {
  const start = parsed.startTime ? localDateTime(parsed.startTime) : null
  return baseDraft({
    id,
    kind: 'gpx',
    title: stripExtension(fileName).slice(0, IMPORT_LIMITS.titleMax),
    date: start ? start.date : fallbackDate,
    time: start ? start.time : '',
    dateSource: start ? 'gpx' : 'fallback',
    colorHex: planColorHex || DEFAULT_PLAN_COLOR,
    track: {
      file,
      fileName,
      points: parsed.points,
      previewPoints: thinRoutePoints(parsed.points, IMPORT_LIMITS.maxPreviewRoutePoints),
      distanceKm: parsed.distanceKm,
      durationMinutes: parsed.durationMinutes,
      transportMode: 'WALK',
    },
  })
}

export function createPlaceDraft({ id, latitude, longitude, name, country, region }, { fallbackDate = '', source = 'map' } = {}) {
  return baseDraft({
    id,
    kind: 'place',
    title: String(name || '').slice(0, IMPORT_LIMITS.titleMax),
    date: fallbackDate,
    dateSource: 'fallback',
    flagDate: false,
    latitude: Number(latitude),
    longitude: Number(longitude),
    country: country || '',
    region: region || '',
    placeName: name || '',
    locationSource: source,
  })
}

export function sortDrafts(drafts) {
  return [...(drafts || [])].sort((left, right) => {
    const dateCompare = String(left.date || '').localeCompare(String(right.date || ''))
    if (dateCompare) return dateCompare
    const timeCompare = String(left.time || '99:99').localeCompare(String(right.time || '99:99'))
    if (timeCompare) return timeCompare
    const kindCompare = (KIND_ORDER[left.kind] ?? 9) - (KIND_ORDER[right.kind] ?? 9)
    if (kindCompare) return kindCompare
    return String(left.id).localeCompare(String(right.id))
  })
}

function dayNumber(date, startDate) {
  const parse = (value) => {
    const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(value || ''))
    return match ? Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3])) : NaN
  }
  const diff = (parse(date) - parse(startDate)) / 86400000
  return Number.isFinite(diff) && diff >= 0 ? Math.round(diff) + 1 : 0
}

export function needsDateReview(draft) {
  return draft.dateSource === 'fallback' && draft.flagDate !== false
}

export function groupDraftsByDate(drafts, planStartDate = '') {
  const sorted = sortDrafts(drafts)
  const groups = []
  const byKey = new Map()
  const review = { key: 'review', label: '날짜 확인 필요', drafts: [] }
  for (const draft of sorted) {
    if (needsDateReview(draft)) {
      review.drafts.push(draft)
      continue
    }
    let group = byKey.get(draft.date)
    if (!group) {
      const day = planStartDate ? dayNumber(draft.date, planStartDate) : 0
      group = { key: draft.date, label: day ? `${draft.date} · ${day}일차` : String(draft.date), drafts: [] }
      byKey.set(draft.date, group)
      groups.push(group)
    }
    group.drafts.push(draft)
  }
  if (review.drafts.length) groups.push(review)
  return groups
}

export function inferPeriod(drafts) {
  const dates = (drafts || [])
    .filter((draft) => draft.include !== false && ['exif', 'gpx', 'manual'].includes(draft.dateSource) && draft.date)
    .map((draft) => draft.date)
    .sort()
  if (!dates.length) return null
  return { startDate: dates[0], endDate: dates[dates.length - 1] }
}

export function suggestPlanId(plans, period) {
  if (!period) return ''
  const candidates = (plans || []).filter((plan) => plan?.startDate && plan?.endDate
    && plan.startDate <= period.startDate && plan.endDate >= period.endDate)
  if (!candidates.length) return ''
  candidates.sort((left, right) => String(right.startDate).localeCompare(String(left.startDate)))
  return String(candidates[0].id)
}

export function validateDraft(draft) {
  if (!draft || draft.include === false) return []
  const problems = []
  const title = String(draft.title || '').trim()
  if (!title) problems.push('제목을 입력해 주세요.')
  else if (title.length > IMPORT_LIMITS.titleMax) problems.push('제목은 120자 이하로 입력해 주세요.')
  if (String(draft.memo || '').trim().length > IMPORT_LIMITS.memoMax) problems.push('메모는 500자 이하로 입력해 주세요.')
  if (!draft.date) problems.push('날짜를 입력해 주세요.')
  if (draft.kind === 'gpx' && Number(draft.track?.distanceKm || 0) < IMPORT_LIMITS.minRouteDistanceKm) {
    problems.push('경로가 너무 짧습니다(10m 미만).')
  }
  return problems
}

export function summarizeDrafts(drafts) {
  const summary = {
    records: 0, photos: 0, routes: 0, excluded: 0, needsLocation: 0, needsReview: 0,
    saved: 0, failed: 0, pending: 0, savedRecords: 0, savedPhotos: 0, savedRoutes: 0,
  }
  for (const draft of drafts || []) {
    const included = draft.include !== false
    if (!included) {
      summary.excluded += 1
      continue
    }
    const photoCount = draft.kind === 'photos' ? (draft.photos?.length || 0) : 0
    if (draft.kind === 'gpx') summary.routes += 1
    else {
      summary.records += 1
      summary.photos += photoCount
      if (!hasCoordinates(draft)) summary.needsLocation += 1
    }
    if (validateDraft(draft).length) summary.needsReview += 1
    if (draft.status === 'saved') {
      summary.saved += 1
      if (draft.kind === 'gpx') summary.savedRoutes += 1
      else {
        summary.savedRecords += 1
        summary.savedPhotos += photoCount
      }
    } else {
      summary.pending += 1
      if (draft.status === 'failed' || draft.status === 'warning') summary.failed += 1
    }
  }
  return summary
}

// ---------------------------------------------------------------- payloads

function nullableNumber(value) {
  return isFiniteNumber(value) ? Number(value) : null
}

export function buildMemoryPayload(draft) {
  const memo = String(draft.memo || '').trim()
  return {
    memoryDate: draft.date,
    memoryTime: draft.time || null,
    category: String(draft.category || '장소').trim() || '장소',
    title: String(draft.title || '').trim().slice(0, IMPORT_LIMITS.titleMax),
    country: draft.country || null,
    region: draft.region || null,
    placeName: draft.placeName || null,
    latitude: nullableNumber(draft.latitude),
    longitude: nullableNumber(draft.longitude),
    sharedWithCommunity: false,
    memo: memo ? memo.slice(0, IMPORT_LIMITS.memoMax) : null,
  }
}

export function buildRoutePayload(draft, planColorHex) {
  const track = draft.track || {}
  const transportMode = track.transportMode || 'WALK'
  const distanceKm = Math.max(IMPORT_LIMITS.minRouteDistanceKm, Number(Number(track.distanceKm || 0).toFixed(3)))
  const memo = String(draft.memo || '').trim()
  return {
    routeDate: draft.date,
    title: String(draft.title || '').trim().slice(0, IMPORT_LIMITS.titleMax),
    transportMode,
    distanceKm,
    durationMinutes: Math.max(0, Math.round(Number(track.durationMinutes || 0))),
    stepCount: transportMode === 'WALK' ? Math.round(distanceKm * 1400) : 0,
    sourceType: 'GPX',
    startPlaceName: null,
    endPlaceName: null,
    lineColorHex: planColorHex || draft.colorHex || DEFAULT_PLAN_COLOR,
    lineStyle: 'SOLID',
    memo: memo ? memo.slice(0, IMPORT_LIMITS.memoMax) : null,
    points: thinRoutePoints(track.points || [], IMPORT_LIMITS.maxSavedRoutePoints).map((point) => ({
      latitude: Number(Number(point.latitude).toFixed(7)),
      longitude: Number(Number(point.longitude).toFixed(7)),
      pointType: 'ROUTE',
      linkedMemoryId: null,
      label: null,
    })),
  }
}

export function buildNewPlanPayload(target, today) {
  const noPeriod = Boolean(target.noPeriod)
  const endDate = noPeriod ? null : (target.endDate || null)
  return {
    name: String(target.name || '').trim(),
    destination: null,
    startDate: noPeriod ? null : (target.startDate || null),
    endDate,
    noPeriod,
    homeCurrency: 'KRW',
    headCount: 1,
    status: !noPeriod && endDate && endDate < today ? 'COMPLETED' : 'PLANNED',
    colorHex: DEFAULT_PLAN_COLOR,
    memo: null,
  }
}

// ---------------------------------------------------------------- messages

export function toImportErrorMessage(error) {
  const message = String(error?.message || '')
  if (error?.code === 'travel-media-too-large') return '사진이 너무 큽니다(한 장 15MB 이하).'
  if (/decode|could not be/i.test(message) || error?.name === 'EncodingError' || error?.name === 'DOMException') {
    return '사진 파일을 읽을 수 없습니다. 손상되었거나 지원하지 않는 형식입니다.'
  }
  if (/presigned/i.test(message)) return '파일 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.'
  if (/File upload failed/i.test(message)) return '파일을 올리지 못했습니다. 네트워크를 확인한 뒤 다시 시도해 주세요.'
  if (error?.status === 400) return '입력값을 확인해 주세요(제목 120자·메모 500자 이하, 날짜 필수).'
  if (error?.status === 401 || error?.status === 403) return '로그인이 필요하거나 권한이 없습니다.'
  if (error instanceof TypeError || error?.name === 'TypeError') return '네트워크 오류로 저장하지 못했습니다.'
  if (/[가-힣]/.test(message)) return message
  return '저장하지 못했습니다.'
}

function withParticle(text) {
  const last = text.charAt(text.length - 1)
  const code = last.charCodeAt(0)
  const hasFinal = code >= 0xac00 && code <= 0xd7a3 && (code - 0xac00) % 28 !== 0
  return `${text}${hasFinal ? '을' : '를'}`
}

export function formatImportNotice({ records = 0, photos = 0, routes = 0, planName = '', createdPlan = false } = {}) {
  const parts = []
  if (records > 0) parts.push(`기록 ${records}건`)
  if (photos > 0) parts.push(`사진 ${photos}장`)
  if (routes > 0) parts.push(`경로 ${routes}개`)
  if (!parts.length) return createdPlan && planName ? `‘${planName}’ 여행은 만들어졌습니다.` : '저장한 항목이 없습니다.'
  const target = planName ? ` ‘${planName}’에` : ''
  return `${withParticle(parts.join(' · '))}${target} 저장했습니다.`
}

// ---------------------------------------------------------------- save orchestration

export async function runImportSave({ drafts, target, api, today, onPlan, onChange }) {
  const pending = sortDrafts((drafts || []).filter((draft) => draft.include !== false && draft.status !== 'saved'))
  let createdPlanId = target.createdPlanId || ''

  if (target.mode === 'new' && !createdPlanId) {
    try {
      const created = await api.createPlan(buildNewPlanPayload(target, today))
      createdPlanId = String(created?.id ?? '')
      if (!createdPlanId) throw new Error('저장하지 못했습니다.')
      onPlan?.(createdPlanId)
    } catch (error) {
      const message = toImportErrorMessage(error)
      pending.forEach((draft) => onChange(draft.id, { status: 'failed', error: message }))
      return { planId: '', ok: false }
    }
  }

  const planId = createdPlanId || target.planId
  let allSaved = true

  for (const draft of pending) {
    let progress = { memoryId: null, routeId: null, photosUploaded: false, gpxUploaded: false, ...(draft.progress || {}) }
    const update = (patch) => onChange(draft.id, patch)
    update({ status: 'saving', error: '' })

    if (draft.kind === 'gpx') {
      if (!progress.routeId) {
        try {
          const route = await api.createRoute(planId, buildRoutePayload(draft, target.planColorHex))
          progress = { ...progress, routeId: route?.id }
          update({ progress })
        } catch (error) {
          update({ status: 'failed', error: toImportErrorMessage(error) })
          allSaved = false
          continue
        }
      }
      if (!progress.gpxUploaded) {
        try {
          await api.uploadRouteGpx(progress.routeId, [draft.track.file])
          progress = { ...progress, gpxUploaded: true }
          update({ progress })
        } catch {
          update({ status: 'warning', error: '경로는 저장됨 · 원본 GPX 보관 실패' })
          allSaved = false
          continue
        }
      }
      update({ status: 'saved', error: '' })
      continue
    }

    if (!progress.memoryId) {
      try {
        const memory = await api.createMemory(planId, buildMemoryPayload(draft))
        progress = { ...progress, memoryId: memory?.id }
        update({ progress })
      } catch (error) {
        update({ status: 'failed', error: toImportErrorMessage(error) })
        allSaved = false
        continue
      }
    }
    const photos = draft.photos || []
    if (photos.length && !progress.photosUploaded) {
      try {
        await api.uploadMemoryPhotos(progress.memoryId, photos.map((photo) => photo.file))
        progress = { ...progress, photosUploaded: true }
        update({ progress })
      } catch (error) {
        update({
          status: 'failed',
          error: `${toImportErrorMessage(error)} 기록은 저장되었습니다. 다시 시도하면 사진만 올립니다.`,
        })
        allSaved = false
        continue
      }
    }
    update({ status: 'saved', error: '' })
  }

  return { planId: String(planId || ''), ok: allSaved }
}
