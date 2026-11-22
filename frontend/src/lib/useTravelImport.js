import { computed, reactive, ref } from 'vue'
import { extractPhotoMetadata, reverseGeocode } from './photoMetadata'
import { createLocalImagePreview } from './mediaPreview'
import { recordMapApi } from './recordMapApi'
import { normalizeLocationResults } from './recordMapLocationSearch'
import {
  createTravelMemory,
  createTravelPlan,
  createTravelRoute,
  fetchTravelPlan,
  uploadTravelMemoryMedia,
  uploadTravelRouteGpxFiles,
} from './api'
import { todayIso } from './uiFormat'
import * as core from './travelImport'

// Search through our own server (same as "내 장소"), never straight to a third party.
export async function searchTravelLocations(q, options = {}) {
  const rows = normalizeLocationResults(await recordMapApi.searchLocations(q, options))
  return rows.map((row) => ({ ...row, title: row.name, placeName: row.name }))
}

const liveApi = {
  createPlan: createTravelPlan,
  createMemory: createTravelMemory,
  uploadMemoryPhotos: (id, files, onProgress) => uploadTravelMemoryMedia(id, files, '', { onProgress }),
  createRoute: createTravelRoute,
  uploadRouteGpx: uploadTravelRouteGpxFiles,
}

const DECODE_FAILURE = '사진 파일을 읽을 수 없습니다. 손상되었거나 지원하지 않는 형식입니다.'
const DEFAULT_COLOR = '#3182F6'

export async function canDecodeImage(file) {
  if (typeof createImageBitmap !== 'function') return true
  try {
    const bitmap = await createImageBitmap(file)
    bitmap.close?.()
    return true
  } catch {
    return false
  }
}

function roundPoint(point) {
  return {
    latitude: Number(Number(point.latitude).toFixed(7)),
    longitude: Number(Number(point.longitude).toFixed(7)),
  }
}

export function useTravelImport() {
  const drafts = ref([])
  const rejected = ref([])
  const searchResults = ref([])
  const searchMessage = ref('')
  const intake = reactive({ active: false, done: 0, total: 0 })
  const target = reactive({
    mode: 'existing', planId: '', name: '', startDate: '', endDate: '', noPeriod: false,
    touched: false, periodTouched: false, createdPlanId: '', planColorHex: '',
  })
  const mode = ref('all') // 'all' | 'photos'
  const existingRecords = ref([]) // [{ id, title, memoryDate, memoryTime, latitude, longitude, photoCount }]
  const recordsState = ref('idle') // 'idle' | 'loading' | 'ready' | 'error'
  const saveState = ref('idle') // 'idle' | 'saving' | 'done' | 'partial'
  const pickTargetId = ref('')
  const pickArmed = ref(false)
  const focusedId = ref('')
  const fitKey = ref(0)
  const gpsLoading = ref(false)
  const error = ref('')
  let intakeToken = 0
  let searchToken = 0
  let searchAbort = null
  let idCounter = 0
  let recordsToken = 0
  let plans = []

  const summary = computed(() => core.summarizeDrafts(drafts.value))
  const problems = computed(() => new Map(drafts.value.map((draft) => [draft.id, core.validateDraft(draft)])))
  const hasUnsaved = computed(() => drafts.value.some((draft) => draft.include && draft.status !== 'saved'))
  const canSave = computed(() => saveState.value !== 'saving'
    && summary.value.pending > 0
    && summary.value.needsReview === 0
    && (target.mode === 'existing'
      ? Boolean(target.planId || target.createdPlanId)
      : Boolean(target.createdPlanId || target.name.trim())))

  const targetPlan = computed(() => plans.find((plan) => String(plan.id) === String(target.planId)) || null)
  const planStartDate = computed(() => (target.mode === 'existing' ? targetPlan.value?.startDate || '' : target.noPeriod ? '' : target.startDate || ''))

  const planPeriod = computed(() => {
    if (target.mode === 'existing') {
      const plan = targetPlan.value
      return plan?.startDate && plan?.endDate ? { startDate: plan.startDate, endDate: plan.endDate } : null
    }
    return !target.noPeriod && target.startDate && target.endDate ? { startDate: target.startDate, endDate: target.endDate } : null
  })

  const mapMarkers = computed(() => drafts.value
    .filter((draft) => draft.include && draft.kind !== 'gpx'
      && Number.isFinite(Number(draft.latitude)) && draft.latitude !== null && draft.latitude !== ''
      && Number.isFinite(Number(draft.longitude)) && draft.longitude !== null && draft.longitude !== '')
    .map((draft) => ({
      id: draft.id,
      planId: target.planId,
      planName: '',
      colorHex: targetPlan.value?.colorHex || DEFAULT_COLOR,
      latitude: Number(draft.latitude),
      longitude: Number(draft.longitude),
      country: draft.country,
      region: draft.region,
      placeName: draft.placeName,
      title: draft.title,
      visitedDate: draft.date,
      visitedTime: draft.time,
      photoCount: draft.kind === 'photos' ? draft.photos.length : 0,
      receiptCount: 0,
      photoUrl: draft.photos?.[0]?.previewUrl || '',
      iconKey: 'general',
      iconText: draft.kind === 'place' ? '📍' : '📷',
    })))

  const mapRoutes = computed(() => drafts.value
    .filter((draft) => draft.include && draft.kind === 'gpx')
    .map((draft) => ({
      id: draft.id,
      title: draft.title,
      points: draft.track.previewPoints,
      lineColorHex: draft.colorHex || DEFAULT_COLOR,
      lineStyle: 'SOLID',
    })))

  function findDraft(id) {
    return drafts.value.find((draft) => draft.id === id)
  }

  function revokePreview(url) {
    if (url && String(url).startsWith('blob:')) URL.revokeObjectURL(url)
  }

  function revokeAllPreviews() {
    drafts.value.forEach((draft) => (draft.photos || []).forEach((photo) => revokePreview(photo.previewUrl)))
  }

  function nextId(prefix, name = '') {
    idCounter += 1
    return `${prefix}:${name}-${Date.now()}-${idCounter}`
  }

  function fallbackDate() {
    if (target.mode === 'existing' && targetPlan.value?.startDate) return targetPlan.value.startDate
    return core.inferPeriod(drafts.value)?.startDate || todayIso()
  }

  function refreshSuggestions() {
    const period = core.inferPeriod(drafts.value)
    if (target.mode === 'existing' && !target.planId && !target.touched) {
      target.planId = core.suggestPlanId(plans, period)
      if (target.planId) loadRecords(target.planId)
    } else if (target.mode === 'new' && !target.periodTouched && !target.noPeriod && period) {
      target.startDate = period.startDate
      target.endDate = period.endDate
    }
  }

  function applyAttachSuggestions() {
    for (const draft of drafts.value) {
      if (draft.kind !== 'photos' || draft.status === 'saved' || draft.attachTouched || draft.progress?.memoryId) continue
      const suggestion = core.suggestAttachRecord(draft, existingRecords.value)
      draft.attachRecordId = suggestion ? String(suggestion.recordId) : ''
      draft.attachSource = suggestion ? 'suggested' : ''
      draft.attachReason = suggestion ? core.describeAttachReason(suggestion) : ''
    }
  }

  async function loadRecords(planId) {
    recordsToken += 1
    const token = recordsToken
    recordsState.value = 'loading'
    existingRecords.value = []
    try {
      const detail = await fetchTravelPlan(planId)
      if (token !== recordsToken) return
      const media = Array.isArray(detail?.mediaItems) ? detail.mediaItems : []
      existingRecords.value = (Array.isArray(detail?.memoryRecords) ? detail.memoryRecords : []).map((record) => ({
        id: String(record.id),
        title: record.title || '',
        memoryDate: record.memoryDate || '',
        memoryTime: core.normalizeClock(record.memoryTime),
        latitude: record.latitude ?? null,
        longitude: record.longitude ?? null,
        photoCount: media.filter((item) => String(item.recordType || '').toUpperCase() === 'MEMORY'
          && String(item.recordId) === String(record.id)).length,
      }))
      recordsState.value = 'ready'
      applyAttachSuggestions()
    } catch {
      if (token !== recordsToken) return
      existingRecords.value = []
      recordsState.value = 'error'
    }
  }

  function reset({ planId = '', plans: nextPlans = [], mode: nextMode = 'all', initialFiles = [] } = {}) {
    recordsToken += 1
    existingRecords.value = []
    recordsState.value = 'idle'
    mode.value = nextMode === 'photos' ? 'photos' : 'all'
    intakeToken += 1
    searchToken += 1
    searchAbort?.abort()
    searchAbort = null
    revokeAllPreviews()
    drafts.value = []
    rejected.value = []
    searchResults.value = []
    searchMessage.value = ''
    intake.active = false
    intake.done = 0
    intake.total = 0
    saveState.value = 'idle'
    pickTargetId.value = ''
    pickArmed.value = false
    focusedId.value = ''
    gpsLoading.value = false
    error.value = ''
    plans = Array.isArray(nextPlans) ? nextPlans.map((plan) => ({ ...plan })) : []
    const today = todayIso()
    Object.assign(target, {
      mode: 'existing', planId: '', name: '', startDate: today, endDate: today, noPeriod: false,
      touched: false, periodTouched: false, createdPlanId: '', planColorHex: '',
    })
    if (planId) {
      target.planId = String(planId)
      loadRecords(target.planId)
    } else if (!plans.length) {
      target.mode = 'new'
    }
    if (initialFiles && initialFiles.length) return addFiles(initialFiles)
    return Promise.resolve()
  }

  async function geocodeInto(draft, { overrideNames = true } = {}) {
    const token = intakeToken
    const { latitude, longitude } = draft
    draft.geocoding = true
    let info = {}
    try {
      info = await reverseGeocode(latitude, longitude)
    } catch {
      info = {}
    }
    if (token !== intakeToken) return
    const current = findDraft(draft.id)
    if (!current || current.latitude !== latitude || current.longitude !== longitude) return
    current.geocoding = false
    if (info.country || info.region || info.placeName) {
      if (overrideNames || !current.country) current.country = info.country || current.country
      if (overrideNames || !current.region) current.region = info.region || current.region
      if (overrideNames || !current.placeName) current.placeName = info.placeName || current.placeName
    }
    if (!current.titleEdited) {
      const suggested = current.kind === 'photos'
        ? core.suggestTitle({ placeName: current.placeName, region: current.region, fileName: current.photos?.[0]?.fileName })
        : (current.placeName || current.region || current.title || '새 장소')
      current.title = suggested
    }
  }

  async function addFiles(fileList) {
    const files = Array.from(fileList || [])
    if (!files.length) return
    const token = intakeToken
    const existingCount = drafts.value.reduce((sum, draft) => sum + (draft.kind === 'photos' ? draft.photos.length : draft.kind === 'gpx' ? 1 : 0), 0)
    let room = Math.max(0, core.IMPORT_LIMITS.maxFilesPerImport - existingCount)
    const photoFiles = []
    const gpxFiles = []
    const nextRejected = []
    for (const file of files) {
      if (room <= 0) {
        nextRejected.push({ id: nextId('rejected', file.name), fileName: file.name, reason: `한 번에 최대 ${core.IMPORT_LIMITS.maxFilesPerImport}개까지 추가할 수 있습니다.` })
        continue
      }
      const result = core.classifyImportFile(file, { photosOnly: mode.value === 'photos' })
      if (result.kind === 'rejected') {
        nextRejected.push({ id: nextId('rejected', file.name), fileName: file.name, reason: result.reason })
        continue
      }
      room -= 1
      if (result.kind === 'gpx') gpxFiles.push(file)
      else photoFiles.push(file)
    }
    if (nextRejected.length) rejected.value = [...rejected.value, ...nextRejected]

    if (photoFiles.length) {
      intake.active = true
      intake.total += photoFiles.length
    }

    // GPX tracks first: they are quick and show up on the map immediately.
    const trackDrafts = []
    for (const file of gpxFiles) {
      let parsed
      try {
        parsed = core.parseGpxText(await file.text())
      } catch {
        parsed = { ok: false, reason: 'GPX 파일을 읽지 못했습니다.' }
      }
      if (token !== intakeToken) return
      if (!parsed.ok) {
        rejected.value = [...rejected.value, { id: nextId('rejected', file.name), fileName: file.name, reason: parsed.reason }]
        continue
      }
      trackDrafts.push(core.createTrackDraft(
        { id: nextId('gpx', file.name), file, fileName: file.name },
        parsed,
        { fallbackDate: fallbackDate(), planColorHex: targetPlan.value?.colorHex },
      ))
    }
    if (trackDrafts.length) {
      drafts.value = core.sortDrafts([...drafts.value, ...trackDrafts])
      fitKey.value += 1
      refreshSuggestions()
    }

    if (!photoFiles.length) return

    const photoEntries = []
    let cursor = 0
    async function worker() {
      while (cursor < photoFiles.length) {
        const file = photoFiles[cursor]
        cursor += 1
        let entry = null
        let failure = ''
        try {
          let meta = null
          try {
            meta = await extractPhotoMetadata(file, { reverseGeocode: false })
          } catch {
            meta = null
          }
          if (token !== intakeToken) return
          if (!(await canDecodeImage(file))) {
            failure = DECODE_FAILURE
          } else {
            const previewUrl = await createLocalImagePreview(file, { maxWidth: 160, maxHeight: 120 })
            if (token !== intakeToken) {
              revokePreview(previewUrl)
              return
            }
            entry = {
              id: nextId('photo', file.name),
              file,
              fileName: file.name,
              previewUrl,
              date: meta?.date || '',
              time: meta?.time || '',
              latitude: meta?.latitude ?? null,
              longitude: meta?.longitude ?? null,
            }
          }
        } catch {
          failure = DECODE_FAILURE
        }
        if (token !== intakeToken) return
        if (entry) photoEntries.push(entry)
        else rejected.value = [...rejected.value, { id: nextId('rejected', file.name), fileName: file.name, reason: failure || DECODE_FAILURE }]
        intake.done += 1
      }
    }
    await Promise.all([worker(), worker()])
    if (token !== intakeToken) return

    const finishIntake = () => {
      if (intake.done >= intake.total) {
        intake.active = false
        intake.done = 0
        intake.total = 0
      }
    }

    if (photoEntries.length) {
      const byId = new Map(photoEntries.map((entry) => [entry.id, entry]))
      const groups = core.groupPhotos(photoEntries)
      const date = fallbackDate()
      const photoDrafts = groups.map((group) => core.createPhotoDraft(group, byId, { fallbackDate: date }))
      drafts.value = core.sortDrafts([...drafts.value, ...photoDrafts])
      fitKey.value += 1
      refreshSuggestions()
      applyAttachSuggestions()
      finishIntake()
      for (const created of photoDrafts) {
        if (token !== intakeToken) return
        const live = findDraft(created.id)
        if (live && !live.attachRecordId && live.latitude !== null && live.longitude !== null) await geocodeInto(live)
      }
    } else {
      finishIntake()
    }
  }

  function addPlace({ latitude, longitude, name = '', country = '', region = '' }, source = 'map') {
    const draft = core.createPlaceDraft(
      { id: nextId('place'), latitude, longitude, name, country, region },
      { fallbackDate: fallbackDate(), source },
    )
    Object.assign(draft, roundPoint(draft))
    drafts.value = core.sortDrafts([...drafts.value, draft])
    focusedId.value = draft.id
    fitKey.value += 1
    refreshSuggestions()
    const live = findDraft(draft.id)
    if (live && (!name || !country)) return geocodeInto(live, { overrideNames: !name })
    return Promise.resolve()
  }

  async function searchPlaces(query) {
    const q = String(query || '').trim()
    searchToken += 1
    const token = searchToken
    searchAbort?.abort()
    searchAbort = null
    if (!q) {
      searchResults.value = []
      searchMessage.value = ''
      return
    }
    const controller = typeof AbortController === 'function' ? new AbortController() : null
    searchAbort = controller
    searchMessage.value = '장소를 찾는 중입니다.'
    try {
      const rows = normalizeLocationResults(await recordMapApi.searchLocations(q, controller ? { signal: controller.signal } : {}))
      if (token !== searchToken) return
      searchResults.value = rows.slice(0, 6)
      searchMessage.value = rows.length ? '' : '검색 결과가 없습니다. 상세 주소나 주변 지역을 함께 검색해 주세요.'
    } catch (searchError) {
      if (token !== searchToken || searchError?.name === 'AbortError') return
      searchResults.value = []
      searchMessage.value = '장소를 검색하지 못했습니다.'
    }
  }

  function useCurrentLocation() {
    if (!navigator.geolocation) {
      searchMessage.value = '현재 위치를 지원하지 않는 브라우저입니다.'
      return Promise.resolve()
    }
    gpsLoading.value = true
    const token = intakeToken
    return new Promise((resolve) => {
      navigator.geolocation.getCurrentPosition(
        async (position) => {
          gpsLoading.value = false
          if (token !== intakeToken) return resolve()
          searchMessage.value = ''
          await addPlace({ latitude: position.coords.latitude, longitude: position.coords.longitude }, 'gps')
          resolve()
        },
        () => {
          gpsLoading.value = false
          if (token === intakeToken) searchMessage.value = '현재 위치를 가져오지 못했습니다. 위치 권한을 확인하거나 지도에서 선택해 주세요.'
          resolve()
        },
        { enableHighAccuracy: true, timeout: 10000 },
      )
    })
  }

  function updateDraft(id, patch) {
    const draft = findDraft(id)
    if (!draft || draft.status === 'saved') return
    const { transportMode, ...rest } = patch
    if ('title' in rest) rest.titleEdited = true
    if ('date' in rest) rest.dateSource = 'manual'
    Object.assign(draft, rest)
    if (transportMode && draft.track) draft.track = { ...draft.track, transportMode }
    if ('date' in rest || 'time' in rest) drafts.value = core.sortDrafts(drafts.value)
    if ('date' in rest || 'include' in rest) refreshSuggestions()
  }

  function setDraftLocation(id, point, source = 'map') {
    const draft = findDraft(id)
    if (!draft || draft.status === 'saved') return Promise.resolve()
    Object.assign(draft, roundPoint(point), { locationSource: source })
    focusedId.value = id
    return geocodeInto(draft)
  }

  function removePhoto(draftId, photoId) {
    const draft = findDraft(draftId)
    if (!draft || draft.status === 'saved') return
    const photo = draft.photos.find((item) => item.id === photoId)
    if (!photo) return
    revokePreview(photo.previewUrl)
    draft.photos = draft.photos.filter((item) => item.id !== photoId)
    if (!draft.photos.length) {
      drafts.value = drafts.value.filter((item) => item.id !== draftId)
      refreshSuggestions()
      return
    }
    if (!draft.titleEdited && !draft.placeName) {
      draft.title = core.suggestTitle({ fileName: draft.photos[0].fileName })
    }
  }

  function removeDraft(id) {
    const draft = findDraft(id)
    if (!draft) return
    ;(draft.photos || []).forEach((photo) => revokePreview(photo.previewUrl))
    drafts.value = drafts.value.filter((item) => item.id !== id)
    if (pickTargetId.value === id) pickTargetId.value = ''
    if (focusedId.value === id) focusedId.value = ''
    refreshSuggestions()
  }

  function removeRejected(id) {
    rejected.value = id ? rejected.value.filter((item) => item.id !== id) : []
  }

  function updateTarget(patch) {
    Object.assign(target, patch)
    if ('startDate' in patch || 'endDate' in patch || 'noPeriod' in patch) {
      target.periodTouched = true
    }
  }

  function setAttachRecord(id, recordId) {
    const draft = findDraft(id)
    if (!draft || draft.kind !== 'photos' || draft.status === 'saved' || draft.progress?.memoryId) return
    draft.attachTouched = true
    draft.attachRecordId = String(recordId || '')
    draft.attachSource = recordId ? 'manual' : ''
    draft.attachReason = ''
    if (!recordId && draft.latitude !== null && draft.longitude !== null && !draft.placeName && !draft.titleEdited) {
      geocodeInto(draft)
    }
  }

  function attachOptionsFor(draft) {
    if (!existingRecords.value.length || draft?.kind !== 'photos') return []
    return core.buildAttachOptions(draft, existingRecords.value)
  }

  function setMode(next) {
    mode.value = next === 'photos' ? 'photos' : 'all'
  }

  function setTargetPlan(value) {
    target.touched = true
    for (const draft of drafts.value) {
      if (draft.kind !== 'photos' || draft.status === 'saved') continue
      draft.attachRecordId = ''
      draft.attachTouched = false
      draft.attachSource = ''
      draft.attachReason = ''
    }
    if (value === '__new__') {
      const period = core.inferPeriod(drafts.value)
      const today = todayIso()
      target.mode = 'new'
      target.planId = ''
      target.name = ''
      target.noPeriod = false
      target.periodTouched = false
      target.startDate = period?.startDate || today
      target.endDate = period?.endDate || today
      recordsToken += 1
      existingRecords.value = []
      recordsState.value = 'idle'
      return
    }
    target.mode = 'existing'
    target.planId = value ? String(value) : ''
    if (target.planId) loadRecords(target.planId)
    else {
      recordsToken += 1
      existingRecords.value = []
      recordsState.value = 'idle'
    }
  }

  async function save() {
    if (saveState.value === 'saving') return { planId: '', ok: false, counts: { records: 0, photos: 0, routes: 0 } }
    saveState.value = 'saving'
    error.value = ''
    target.planColorHex = target.mode === 'new' && !target.createdPlanId
      ? DEFAULT_COLOR
      : (targetPlan.value?.colorHex || DEFAULT_COLOR)
    const result = await core.runImportSave({
      drafts: drafts.value,
      target,
      api: liveApi,
      today: todayIso(),
      onPlan: (id) => { target.createdPlanId = String(id) },
      onChange: (id, patch) => {
        const draft = findDraft(id)
        if (draft) Object.assign(draft, patch)
      },
    })
    saveState.value = result.ok ? 'done' : 'partial'
    const counts = {
      records: summary.value.savedRecords,
      photos: summary.value.savedPhotos,
      routes: summary.value.savedRoutes,
      attachedRecords: summary.value.savedAttached,
      attachedPhotos: summary.value.savedAttachedPhotos,
    }
    if (!result.ok && !result.planId && target.mode === 'new') {
      error.value = drafts.value.find((draft) => draft.status === 'failed')?.error || '여행을 만들지 못했습니다.'
    }
    return { ...result, counts }
  }

  function dispose() {
    recordsToken += 1
    intakeToken += 1
    searchToken += 1
    searchAbort?.abort()
    searchAbort = null
    revokeAllPreviews()
  }

  return {
    drafts, rejected, intake, target, saveState, summary, problems, hasUnsaved, canSave,
    pickTargetId, pickArmed, focusedId, fitKey, gpsLoading, error, searchResults, searchMessage,
    mapMarkers, mapRoutes, planStartDate, planPeriod,
    mode, existingRecords, recordsState, setAttachRecord, attachOptionsFor, setMode,
    reset, addFiles, addPlace, searchPlaces, useCurrentLocation, updateDraft, setDraftLocation,
    removePhoto, removeDraft, removeRejected, setTargetPlan, updateTarget, save, dispose,
  }
}
