<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { recordMapApi as api } from '../lib/recordMapApi'
import { buildRecordMapData } from '../lib/recordMap'
import { locationSuggestions, normalizeLocationResults, relatedLocationQueries } from '../lib/recordMapLocationSearch'
import { extractPhotoMetadata } from '../lib/photoMetadata'
import { buildThumbnailUrl } from '../lib/mediaPreview'
import { todayIso } from '../lib/uiFormat'
import TravelMyMapClusterPanel from './TravelMyMapClusterPanel.vue'
import TravelMapPanel from './TravelMapPanel.vue'
import RecordMapDialog from './RecordMapDialog.vue'
import RecordMapSidebar from './RecordMapSidebar.vue'

const props = defineProps({ token: { type: String, default: '' }, publicView: Boolean })
const emit = defineEmits(['open-travel'])
const readOnly = computed(() => props.publicView || Boolean(props.token))
const snapshot = ref({ places: [], collections: [], visits: [], totalPlaces: 0, totalPages: 0 })
const listSnapshot = ref({ places: [], collections: [], visits: [], totalPlaces: 0, totalPages: 0 })
const filters = reactive({ collectionId: '', state: 'ALL', from: '', to: '', q: '', page: 0 })
const loading = ref(false), busy = ref(false), error = ref(''), feedback = ref('')
const view = ref('places'), displayMode = ref('cluster'), pickMode = ref(false)
const compact = ref(window.matchMedia('(max-width: 760px)').matches), sidebarOpen = ref(!compact.value)
const searchMode = ref('saved'), searchText = ref(''), locationResults = ref([]), locationLoading = ref(false), locationMessage = ref(''), foundLocation = ref(null)
const locationHistory = ref([]), lastLocationQuery = ref('')
const frameRef = ref(null), frameHeight = ref(620), listLoading = ref(false)
const mapActionsAnchor = ref(null)
const sidebarActionsAnchor = ref(null)
const actionsTarget = computed(() => compact.value && sidebarOpen.value ? sidebarActionsAnchor.value || mapActionsAnchor.value : mapActionsAnchor.value)
const selectedPlaceId = ref(null), selectedPhotoId = ref(null), selectedMarkerId = ref(null)
const mapRef = ref(null), focusTarget = ref(null), fitKey = ref(0)
const modal = ref(''), dirty = ref(false), photoInput = ref(null)
const photosPending = ref([]), selectedPhoto = ref(null), gpsLoading = ref(false), metadataLoading = ref(false)
const placeForm = reactive({ id: null, name: '', memo: '', country: '', region: '', latitude: '', longitude: '', visited: false, collectionIds: [], recordNow: false, sourceTravelRecordId: null })
const visitForm = reactive({ id: null, placeId: null, title: '', memo: '', date: todayIso(), time: '' })
const collectionForm = reactive({ id: null, name: '', memo: '', colorHex: '#009E8C' })
const detailVisits = ref([]), detailPage = ref(0), detailHasMore = ref(false), detailLoading = ref(false)
const candidates = ref({ content: [], number: 0, totalPages: 0 }), candidateCollections = ref([])
const shareCollectionId = ref(''), shareList = ref([]), includeVisits = ref(false), expiresDays = ref(7), shareUrl = ref(''), newestShareId = ref(null)
let requestSequence = 0, listSequence = 0, locationSequence = 0, detailSequence = 0, focusSequence = 0, metadataSequence = 0
let searchTimer = null, locationAbort = null, frameObserver = null

const allPlaces = computed(() => [...new Map([...snapshot.value.places, ...listSnapshot.value.places].map(p => [p.id, p])).values()])
const selectedPlace = computed(() => allPlaces.value.find(p => p.id === selectedPlaceId.value) || null)
const mapSnapshot = computed(() => {
  const extra = selectedPlace.value && !snapshot.value.places.some(p => p.id === selectedPlaceId.value)
  return extra ? { ...snapshot.value, places: [...snapshot.value.places, selectedPlace.value], visits: [...snapshot.value.visits, ...listSnapshot.value.visits.filter(v => v.placeId === selectedPlaceId.value)] } : snapshot.value
})
const data = computed(() => {
  const result = buildRecordMapData(mapSnapshot.value)
  if (searchMode.value === 'location') result.markers.push(...locationResults.value.map((location, index) => ({ ...location,
    title: location.name, placeName: location.name, recordMap: true, recordMapLocation: true, searchResultNumber: index + 1,
    searchResultFocused: foundLocation.value?.id === location.id,
    planName: '검색 후보 · 저장 전', planColorHex: '#357CCB' })))
  return result
})
const listPlaces = computed(() => listSnapshot.value.places.map(p => ({ ...p, thumbnail: thumbnail(p.id) })))
const diaryVisits = computed(() => [...listSnapshot.value.visits].sort((a,b) => (b.date+(b.time||'')).localeCompare(a.date+(a.time||''))))
const suggestions = computed(() => searchMode.value === 'location' && searchText.value.trim() !== lastLocationQuery.value
  ? locationSuggestions(searchText.value, locationHistory.value) : [])
const relatedQueries = computed(() => searchMode.value === 'location' ? relatedLocationQueries(searchText.value) : [])
const photoSequence = computed(() => (detailVisits.value.some(v => v.placeId === selectedPlaceId.value) ? detailVisits.value.filter(v => v.placeId === selectedPlaceId.value) : snapshot.value.visits.filter(v => v.placeId === selectedPlaceId.value))
  .flatMap(v => (v.photos || []).map(p => ({ ...p, visit: v }))))
const activePhotoIndex = computed(() => photoSequence.value.findIndex(p => p.id === selectedPhoto.value?.id))
const dialogTitle = computed(() => ({ place: placeForm.id ? '장소 수정' : '장소 저장', visit: visitForm.id ? '방문 기록 수정' : '방문 기록 남기기',
  collection: '내 장소 모음', travel: '여행 장소를 모음에 추가', share: '모음 지도 공유', detail: selectedPlace.value?.name || '장소 상세', photo: selectedPhoto.value?.name || '사진 보기' }[modal.value] || '기록 지도'))

async function load() {
  const sequence = ++requestSequence
  ++listSequence
  loading.value = true
  error.value = ''
  try {
    if (readOnly.value && !props.token) throw new Error('공유 주소가 올바르지 않습니다.')
    const params = { ...filters, size: 30, collectionId: readOnly.value ? null : filters.collectionId }
    const [result, list] = await Promise.all([api.map({ ...params, page: undefined }, props.token), api.overview(params, props.token)])
    if (sequence !== requestSequence) return
    snapshot.value = result
    listSnapshot.value = list
    if (!allPlaces.value.some(p => p.id === selectedPlaceId.value)) selectedPlaceId.value = null
    fitKey.value++
  } catch (e) {
    if (sequence !== requestSequence) return
    error.value = e.message
    if (readOnly.value && [401, 403, 404].includes(e.status)) {
      clearSharedData()
    }
  }
  finally { if (sequence === requestSequence) loading.value = false }
}
function clearSharedData() {
  requestSequence++; listSequence++
  locationAbort?.abort(); locationSequence++; locationLoading.value = false
  snapshot.value = { places: [], collections: [], visits: [], totalPlaces: 0, totalPages: 0 }
  listSnapshot.value = { ...snapshot.value }; clearLocationResults(); locationHistory.value = []; lastLocationQuery.value = ''
  selectedPlaceId.value = null; selectedPhotoId.value = null; selectedMarkerId.value = null
  modal.value = ''; selectedPhoto.value = null; detailVisits.value = []; detailSequence++
  loading.value = false; listLoading.value = false
}
async function loadList() {
  const sequence = ++listSequence, filterSequence = requestSequence
  listLoading.value = true
  try {
    const result = await api.overview({ ...filters, size: 30, collectionId: readOnly.value ? null : filters.collectionId }, props.token)
    if (sequence === listSequence && filterSequence === requestSequence) listSnapshot.value = result
  } catch (e) {
    if (sequence !== listSequence || filterSequence !== requestSequence) return
    error.value = e.message
    if (readOnly.value && [401,403,404].includes(e.status)) clearSharedData()
  } finally { if (sequence === listSequence) listLoading.value = false }
}
function thumbnail(id) {
  const photo = [...listSnapshot.value.visits, ...snapshot.value.visits].find(v => v.placeId === id && v.photos?.length)?.photos[0]
  return photo ? buildThumbnailUrl(photo.photoUrl, 'mini') : ''
}
function updateSearch(text) {
  searchText.value = text; clearTimeout(searchTimer)
  if (searchMode.value === 'saved') searchTimer = setTimeout(() => { filters.q = text.trim() }, 300)
  else { locationAbort?.abort(); locationSequence++; locationLoading.value = false; clearLocationResults() }
}
function clearLocationResults() {
  locationResults.value = []; locationMessage.value = ''; foundLocation.value = null
  if (String(selectedMarkerId.value || '').startsWith('location:')) selectedMarkerId.value = null
}
function changeSearchMode(mode) {
  clearTimeout(searchTimer); locationAbort?.abort(); locationSequence++
  searchMode.value = mode; searchText.value = mode === 'saved' ? filters.q : ''
  clearLocationResults(); locationLoading.value = false
  if (mode === 'saved') fitKey.value++
}
async function submitSearch() {
  clearTimeout(searchTimer)
  if (searchMode.value === 'saved') { filters.q = searchText.value.trim(); sidebarOpen.value = true; return }
  if (readOnly.value || locationLoading.value) return
  const sequence = ++locationSequence
  locationAbort?.abort(); locationAbort = new AbortController()
  const query = searchText.value.trim()
  locationLoading.value = true; clearLocationResults()
  try {
    const rows = normalizeLocationResults(await api.searchLocations(query, { signal: locationAbort.signal }))
    if (sequence !== locationSequence) return
    locationResults.value = rows; lastLocationQuery.value = query
    locationHistory.value = [...rows, ...locationHistory.value].slice(0, 40)
    locationMessage.value = rows.length ? `검색 후보 ${rows.length}개 · 지도 핀의 번호와 목록을 함께 확인하세요.` : '검색 결과가 없습니다. 출구·상호 정보가 없으면 상세 주소나 주변 지역을 함께 검색해 주세요.'
    sidebarOpen.value = true
    await nextTick()
    if (sequence === locationSequence && rows.length) focusLocation(rows[0], compact.value)
  } catch (e) { if (sequence === locationSequence && e.name !== 'AbortError') locationMessage.value = e.message }
  finally { if (sequence === locationSequence) locationLoading.value = false }
}
function focusLocation(location, collapse = true) {
  // Suggestions reuse already fetched coordinates without an upstream request.
  const existing = locationResults.value.find(row => row.name === location.name && row.latitude === location.latitude && row.longitude === location.longitude)
  if (!existing) locationResults.value = normalizeLocationResults([location, ...locationResults.value])
  const target = existing || locationResults.value[0]
  if (!target) return
  foundLocation.value = target; selectedPlaceId.value = null; selectedPhotoId.value = null; selectedMarkerId.value = target.id
  if (compact.value && collapse) sidebarOpen.value = false
  focusTarget.value = { requestId: ++focusSequence, ...target, zoom: 15, duration: .8, showPreview: true }
}
function searchRelated(query) { searchText.value = query; submitSearch() }
function saveLocation(location) { beginPlace(location); placeForm.name = location.name; placeForm.country = location.country; placeForm.region = location.region }
function selectCollection(id) { filters.collectionId = String(id || ''); view.value = 'places'; sidebarOpen.value = true }
function setFilter(key, value) { filters[key] = value }
function switchTab(tab) { view.value = tab; sidebarOpen.value = true }
function refreshFrame() {
  compact.value = window.matchMedia('(max-width: 760px)').matches
  if (frameRef.value) frameHeight.value = Math.max(280, window.innerHeight - frameRef.value.getBoundingClientRect().top - 12)
}
watch(() => sidebarOpen.value, async open => {
  // Automatic collapse on a place click must not replace its explicit zoom
  // with a keepZoom pan while that camera transition is still starting.
  if (!open) return
  await nextTick()
  if (!sidebarOpen.value) return
  const target = foundLocation.value || selectedPlace.value
  if (target) focusTarget.value = { requestId: ++focusSequence, ...target, keepZoom: true, duration: .35 }
})
onMounted(() => {
  refreshFrame(); requestAnimationFrame(refreshFrame); window.addEventListener('resize', refreshFrame); window.addEventListener('scroll', refreshFrame, { passive:true })
  frameObserver = new ResizeObserver(refreshFrame)
  if (frameRef.value) { frameObserver.observe(frameRef.value); frameObserver.observe(frameRef.value.parentElement) }
})
watch(() => props.token, () => {
  clearTimeout(searchTimer); locationAbort?.abort(); locationSequence++; locationLoading.value = false; searchText.value = ''
  snapshot.value = { places: [], collections: [], visits: [], totalPlaces: 0, totalPages: 0 }
  listSnapshot.value = { ...snapshot.value }; clearLocationResults(); locationHistory.value = []; lastLocationQuery.value = ''; searchMode.value = 'saved'
  selectedPlaceId.value = null; selectedPhotoId.value = null; selectedMarkerId.value = null
  modal.value = ''; selectedPhoto.value = null; detailVisits.value = []; detailSequence++
  filters.page = 0; load()
}, { immediate: true })
watch(() => [filters.collectionId, filters.state, filters.from, filters.to, filters.q], () => { filters.page = 0; foundLocation.value = null; load() })
watch(() => filters.page, () => { if (!loading.value) loadList() })
watch(dirty, value => window.dispatchEvent(new CustomEvent('calen-route-leave-guard', { detail: { active: value, message: '작성 중인 장소·방문 기록을 저장하지 않고 나갈까요?' } })))

async function act(action) {
  if (busy.value) return
  busy.value = true; error.value = ''; feedback.value = ''
  try { await action() } catch (e) { error.value = e.message || '요청을 처리하지 못했습니다.' }
  finally { busy.value = false }
}
function defaultCollections() { return filters.collectionId ? [Number(filters.collectionId)] : [] }
function resetVisit(placeId = null) {
  Object.assign(visitForm, { id: null, placeId, title: '', memo: '', date: todayIso(), time: '' })
  photosPending.value = []
}
function beginPlace(point = null, recordNow = false) {
  metadataSequence++; metadataLoading.value = false; gpsLoading.value = false; dirty.value = false; error.value = ''
  Object.assign(placeForm, { id: null, name: '', memo: '', country: '', region: '', latitude: point?.latitude ?? '', longitude: point?.longitude ?? '',
    visited: false, collectionIds: defaultCollections(), recordNow, sourceTravelRecordId: null })
  resetVisit(); modal.value = 'place'; pickMode.value = false
}
function editPlace(place) {
  metadataSequence++; metadataLoading.value = false; gpsLoading.value = false; dirty.value = false
  Object.assign(placeForm, { ...place, recordNow: false, collectionIds: [...place.collectionIds] })
  resetVisit(place.id); modal.value = 'place'
}
function pickPoint(point) {
  if (busy.value) return
  if (modal.value === 'place') { placeForm.latitude = point.latitude; placeForm.longitude = point.longitude; dirty.value = true }
  else beginPlace(point)
}
async function photoPicked(event) {
  const files = [...(event.target.files || [])]
  event.target.value = ''
  if (!files.length) return
  beginPlace(null, true)
  photosPending.value = files
  dirty.value = true
  placeForm.name = files[0].name.replace(/\.[^.]+$/, '')
  const originalName = placeForm.name, originalDate = visitForm.date, originalTime = visitForm.time
  const sequence = ++metadataSequence
  metadataLoading.value = true
  try {
    const metadata = await extractPhotoMetadata(files[0])
    if (sequence !== metadataSequence || modal.value !== 'place' || busy.value || placeForm.id || !metadata) return
    if (metadata.latitude != null && metadata.longitude != null) {
      if (placeForm.latitude === '' && placeForm.longitude === '') {
        placeForm.latitude = metadata.latitude; placeForm.longitude = metadata.longitude
        if (!placeForm.country) placeForm.country = metadata.country
        if (!placeForm.region) placeForm.region = metadata.region
        if (metadata.placeName && placeForm.name === originalName) placeForm.name = metadata.placeName
      }
    }
    if (metadata.date && visitForm.date === originalDate) visitForm.date = metadata.date
    if (metadata.time && visitForm.time === originalTime) visitForm.time = metadata.time
  } catch { if (sequence === metadataSequence) feedback.value = '사진의 위치를 읽을 수 없습니다. 지도에서 장소를 찍거나 좌표를 입력해 주세요.' }
  finally { if (sequence === metadataSequence) metadataLoading.value = false }
}
function visitFilesPicked(event) { photosPending.value = [...(event.target.files || [])]; dirty.value = true }
function useGps() {
  if (!navigator.geolocation) { error.value = '현재 위치를 지원하지 않는 브라우저입니다.'; return }
  gpsLoading.value = true
  metadataLoading.value = false
  const sequence = ++metadataSequence
  navigator.geolocation.getCurrentPosition(position => {
    gpsLoading.value = false
    if (sequence !== metadataSequence || modal.value !== 'place') return
    placeForm.latitude = position.coords.latitude; placeForm.longitude = position.coords.longitude; dirty.value = true
  }, () => {
    if (sequence !== metadataSequence || modal.value !== 'place') return
    gpsLoading.value = false; error.value = '현재 위치를 가져오지 못했습니다. 위치 권한을 확인하거나 지도에서 선택해 주세요.'
  }, { timeout: 10000 })
}
function validateFiles() {
  if (photosPending.value.length > 20 || photosPending.value.some(f => f.size > 15 * 1024 * 1024 || !/\.(jpe?g|png|webp|gif|bmp)$/i.test(f.name))) {
    throw new Error('사진은 한 방문당 20장, 한 장당 15MB 이하의 JPG·PNG·WEBP·GIF·BMP 파일을 선택해 주세요.')
  }
}
async function uploadPending(visitId) {
  while (photosPending.value.length) {
    await api.photo(visitId, photosPending.value[0])
    photosPending.value = photosPending.value.slice(1)
  }
}
function savePlace() {
  act(async () => {
    if (placeForm.latitude === '' || placeForm.longitude === '' || !Number.isFinite(Number(placeForm.latitude)) || !Number.isFinite(Number(placeForm.longitude))) throw new Error('지도에서 장소를 찍거나 위도·경도를 입력해 주세요.')
    validateFiles()
    const result = await api.place(placeForm.id, { name: placeForm.name, memo: placeForm.memo, country: placeForm.country, region: placeForm.region,
      latitude: Number(placeForm.latitude), longitude: Number(placeForm.longitude), visited: placeForm.visited, collectionIds: placeForm.collectionIds })
    placeForm.id = result.id
    if (placeForm.recordNow) {
      const visit = await api.visit(result.id, visitForm.id, { title: visitForm.title || placeForm.name, memo: visitForm.memo, date: visitForm.date, time: visitForm.time || null })
      visitForm.id = visit.id
      await uploadPending(visit.id)
    }
    dirty.value = false; modal.value = ''; await load()
    clearLocationResults(); selectedPlaceId.value = result.id
    feedback.value = allPlaces.value.some(p => p.id === result.id) ? '장소와 기록을 저장했습니다.' : '저장했습니다. 현재 검색·필터 조건을 바꾸면 저장한 장소를 확인할 수 있습니다.'
  })
}
function beginVisit(visit = null) {
  error.value = ''; dirty.value = false
  if (!allPlaces.value.length) { beginPlace(null, true); return }
  resetVisit(visit?.placeId || selectedPlaceId.value || allPlaces.value[0].id)
  if (visit) Object.assign(visitForm, { id: Number(visit.id.split(':')[1]), placeId: visit.placeId, title: visit.title, memo: visit.memo || '', date: visit.date, time: visit.time || '' })
  modal.value = 'visit'
}
function saveVisit() {
  act(async () => {
    validateFiles()
    const result = await api.visit(visitForm.placeId, visitForm.id, { title: visitForm.title || snapshot.value.places.find(p => p.id === visitForm.placeId)?.name || '방문 기록',
      memo: visitForm.memo, date: visitForm.date, time: visitForm.time || null })
    visitForm.id = result.id
    await uploadPending(result.id)
    dirty.value = false; modal.value = ''; await load(); feedback.value = '방문 기록을 저장했습니다.'
  })
}
function beginCollections() { dirty.value = false; Object.assign(collectionForm, { id: null, name: '', memo: '', colorHex: '#009E8C' }); modal.value = 'collection' }
function saveCollection() {
  act(async () => {
    await api.collection(collectionForm.id, { name: collectionForm.name, memo: collectionForm.memo, colorHex: collectionForm.colorHex })
    dirty.value = false; Object.assign(collectionForm, { id: null, name: '', memo: '', colorHex: '#009E8C' }); await load(); feedback.value = '모음을 저장했습니다.'
  })
}
function deleteCollection(collection) {
  if (!window.confirm('“' + collection.name + '” 모음을 삭제할까요? 장소·방문 기록·사진은 유지되고 이 모음의 공유 링크는 종료됩니다.')) return
  act(async () => { await api.deleteCollection(collection.id); if (String(filters.collectionId) === String(collection.id)) filters.collectionId = ''; await load(); feedback.value = '모음만 삭제했습니다.' })
}
function deletePlace(place) {
  if (!window.confirm('“' + place.name + '” 저장 장소를 삭제할까요? 여행 원본은 유지됩니다. 방문 기록이 있으면 먼저 정리해야 합니다.')) return
  act(async () => { await api.deletePlace(place.id); modal.value = ''; selectedPlaceId.value = null; await load() })
}
function deleteVisit(visit) {
  if (!window.confirm('이 방문 기록과 첨부 사진을 삭제할까요? 장소와 모음은 유지됩니다.')) return
  act(async () => { await api.deleteVisit(Number(visit.id.split(':')[1])); await load(); await loadDetail(0) })
}
function deletePhoto(photo) {
  if (!window.confirm('이 첨부 사진을 삭제할까요?')) return
  act(async () => { await api.deletePhoto(Number(photo.id.split(':')[1])); await load(); await loadDetail(detailPage.value); modal.value = 'detail' })
}
function selectPlace(item) {
  if (item?.recordMapLocation) { focusLocation(item); return }
  const id = item?.recordMapPlaceId ?? item?.placeId ?? Number(String(item?.id || item?.clusterId || '').split(':').at(-1))
  if (!allPlaces.value.some(p => p.id === Number(id))) return
  if (selectedPlaceId.value !== Number(id)) { detailVisits.value = []; detailSequence++ }
  selectedPlaceId.value = Number(id)
  selectedPhotoId.value = item.mediaId || item.representativeMediaId || null
  selectedMarkerId.value = selectedPhotoId.value ? null : 'place:' + id
  if (compact.value) sidebarOpen.value = false
  nextTick(() => {
    const card = frameRef.value?.querySelector(`[data-place-id="${Number(id)}"]`)
    const container = card?.closest('.record-map-sidebar__body')
    if (!card || !container) return
    const row = card.getBoundingClientRect(), bounds = container.getBoundingClientRect()
    if (row.top < bounds.top || row.bottom > bounds.bottom) container.scrollBy({ top: row.top - bounds.top, behavior: 'smooth' })
  })
}
function focusPlace(place) {
  selectedPlaceId.value = place.id; selectedMarkerId.value = 'place:' + place.id
  const pin = data.value.photoPins.find(p => p.recordMapPlaceId === place.id)
  selectedPhotoId.value = pin?.mediaId || null
  focusTarget.value = { requestId: ++focusSequence, latitude: place.latitude, longitude: place.longitude, zoom: 15, duration: .8, showPreview: true }
  if (compact.value) sidebarOpen.value = false
}
async function openDetail(place) {
  if (!place) return
  dirty.value = false; detailVisits.value = []; selectedPlaceId.value = place.id; modal.value = 'detail'; await loadDetail(0)
}
async function loadDetail(page) {
  const id = selectedPlaceId.value, sequence = ++detailSequence
  detailLoading.value = true
  try {
    const original = snapshot.value.visits.filter(v => v.placeId === id && v.id.startsWith('travel:'))
    const rows = readOnly.value ? snapshot.value.visits.filter(v => v.placeId === id && !v.id.startsWith('travel:')) : await api.visits(id, page)
    if (sequence !== detailSequence || id !== selectedPlaceId.value) return
    detailVisits.value = [...(page === 0 ? original : []), ...rows]
    detailPage.value = page; detailHasMore.value = !readOnly.value && rows.length === 20
  } catch (e) { error.value = e.message } finally { if (sequence === detailSequence) detailLoading.value = false }
}
function openPhoto(photo, visit = null) {
  if (visit) selectedPlaceId.value = visit.placeId
  selectedPhoto.value = { ...photo, visit }; modal.value = 'photo'
}
function mapPreviewOpen(photo) {
  if (photo.recordMapLocation) { saveLocation(photo); return }
  selectPlace(photo)
  const visit = snapshot.value.visits.find(v => v.photos?.some(p => p.id === (photo.mediaId ?? photo.representativeMediaId)))
  const picture = visit?.photos?.find(p => p.id === (photo.mediaId ?? photo.representativeMediaId))
  if (picture) openPhoto(picture, visit)
  else if (selectedPlace.value) openDetail(selectedPlace.value)
}
function changePhoto(delta) {
  const photo = photoSequence.value[activePhotoIndex.value + delta]
  if (photo) selectedPhoto.value = photo
}
function closeDialog() {
  if (busy.value) return
  if (dirty.value && !window.confirm('작성 중인 내용을 저장하지 않고 닫을까요?')) return
  dirty.value = false; metadataSequence++; gpsLoading.value = false; metadataLoading.value = false
  modal.value = modal.value === 'photo' && selectedPlace.value ? 'detail' : ''
  if (modal.value === 'detail') loadDetail(0)
}
async function beginTravelImport() {
  dirty.value = false; candidateCollections.value = defaultCollections(); modal.value = 'travel'; await loadCandidates(0)
}
async function loadCandidates(page) {
  try { candidates.value = await api.candidates(page) } catch (e) { error.value = e.message }
}
function importTravel(item) {
  act(async () => { const result = await api.importTravel(item.id, candidateCollections.value); modal.value = ''; await load(); selectedPlaceId.value = result.id; feedback.value = '여행 원본을 유지하며 장소를 연결했습니다.' })
}
async function beginShare() {
  dirty.value = false; shareUrl.value = ''; includeVisits.value = false
  shareCollectionId.value = filters.collectionId || String(snapshot.value.collections[0]?.id || '')
  modal.value = 'share'; await loadShares()
}
async function loadShares() {
  if (!shareCollectionId.value) { shareList.value = []; return }
  const id = shareCollectionId.value
  try { const list = await api.shares(id); if (id === shareCollectionId.value) shareList.value = list } catch (e) { error.value = e.message }
}
function createShare() {
  act(async () => {
    const result = await api.createShare(shareCollectionId.value, { includeVisits: includeVisits.value, expiresInDays: Number(expiresDays.value) })
    newestShareId.value = result.id
    shareUrl.value = window.location.origin + window.location.pathname + '#record-map-share/' + result.token
    await loadShares()
  })
}
function revokeShare(link) {
  if (!window.confirm('이 공유 링크를 종료할까요? 사진 주소도 접근할 수 없게 됩니다.')) return
  act(async () => { await api.revokeShare(link.id); if (newestShareId.value === link.id) shareUrl.value = ''; await loadShares() })
}
async function copyShare() {
  try { await navigator.clipboard.writeText(shareUrl.value); feedback.value = '공유 주소를 복사했습니다.' } catch { error.value = '주소를 선택해 직접 복사해 주세요.' }
}

function leaveGuard(event) { if (dirty.value) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', leaveGuard)
onBeforeUnmount(() => {
  requestSequence++; listSequence++; locationSequence++; detailSequence++; metadataSequence++
  clearTimeout(searchTimer); locationAbort?.abort(); frameObserver?.disconnect(); window.removeEventListener('resize', refreshFrame); window.removeEventListener('scroll', refreshFrame)
  window.removeEventListener('beforeunload', leaveGuard)
  window.dispatchEvent(new CustomEvent('calen-route-leave-guard', { detail: { active: false } }))
})
</script>

<template>
  <div class="workspace-stack record-map-workspace">
    <header class="panel record-map-header">
      <div><h2>{{ snapshot.title || '기록 지도' }}</h2><span v-if="readOnly" class="record-map-note">공유된 자료 · 읽기 전용</span></div>
      <Teleport :to="actionsTarget || 'body'" :disabled="!actionsTarget"><details v-if="!readOnly" class="record-map-add"><summary class="button button--primary">＋ 추가·관리</summary><div class="record-map-add__menu" @click="$event.target.closest('button') && ($event.currentTarget.closest('details').open = false)">
        <button class="button button--primary" @click="beginPlace()" :disabled="busy">장소 저장</button>
        <button class="button" @click="photoInput.click()" :disabled="busy">사진으로 기록</button>
        <button class="button" @click="beginVisit()" :disabled="busy">방문 기록 남기기</button>
        <button class="button" @click="beginCollections">모음 관리</button>
        <button class="button" @click="beginTravelImport">여행 장소 가져오기</button>
        <button class="button" :disabled="!snapshot.collections.length" @click="beginShare">모음 공유</button>
        <input ref="photoInput" type="file" accept="image/*" multiple hidden @change="photoPicked" />
      </div></details></Teleport>
    </header>
    <p v-if="error" class="feedback feedback--error" role="alert">{{ error }}</p>
    <p v-if="feedback" class="feedback feedback--success" role="status">{{ feedback }}</p>
    <div ref="frameRef" class="record-map-frame" :style="{ '--record-map-height': frameHeight + 'px' }">
    <TravelMyMapClusterPanel ref="mapRef" active workspace-layout include-record-markers :workspace-sidebar-open="sidebarOpen" :overlay-revision="String(sidebarOpen) + view + String(compact)" :markers="data.markers" :photo-pins="data.photoPins" :photo-clusters="data.photoClusters"
      :display-mode="displayMode" :fit-request-key="fitKey" :focus-target="focusTarget" :selected-marker-id="selectedMarkerId" :selected-photo-id="selectedPhotoId"
      :selected-cluster-id="selectedPlaceId ? 'place:' + selectedPlaceId : null"
      :enable-pick-location="pickMode && !readOnly" @pick-location="pickPoint" @select-marker="selectPlace" @select-cluster="selectPlace" @select-photo-pin="selectPlace" @preview-cluster="mapPreviewOpen" @clear-selection="selectedPlaceId = null; selectedPhotoId = null; selectedMarkerId = null">
      <template #navigation="controls">
        <nav class="record-map-tools" aria-label="지도 도구 모음">
          <div v-if="!readOnly" ref="mapActionsAnchor"></div>
          <div class="record-map-tools__zoom"><button class="button" type="button" aria-label="지도 축소" @click="controls.zoomOut">−</button><strong class="travel-cluster-map__zoom">{{ controls.zoom }}</strong><button class="button" type="button" aria-label="지도 확대" @click="controls.zoomIn">＋</button></div>
          <button class="button" type="button" @click="controls.fitAll">전체 보기</button><button class="button" type="button" @click="controls.toggleFullscreen">{{ controls.isFullscreen ? '전체 화면 종료' : '전체 화면' }}</button>
          <details><summary>표시 설정</summary><div class="record-map-tools__settings"><button class="button" @click="displayMode = displayMode === 'cluster' ? 'pin' : 'cluster'">{{ displayMode === 'cluster' ? '핀 보기' : '썸네일 보기' }}</button><button v-if="!readOnly" class="button" :aria-pressed="pickMode" @click="pickMode = !pickMode">{{ pickMode ? '핀 위치를 눌러 주세요' : '지도에 핀 찍기' }}</button><button class="button" @click="load" :disabled="loading">새로고침</button></div></details>
        </nav>
      </template>
      <template #workspace-overlays>
        <RecordMapSidebar :open="sidebarOpen" :tab="view" :read-only="readOnly" :busy="busy" :loading="loading || listLoading" :filters="filters" :snapshot="listSnapshot" :places="listPlaces" :visits="diaryVisits" :selected="selectedPlace"
          :search-mode="searchMode" :search-text="searchText" :locations="locationResults" :location-loading="locationLoading" :location-message="locationMessage"
          :selected-location-id="foundLocation?.id" :suggestions="suggestions" :related-queries="relatedQueries" :map-limits="snapshot"
          @toggle="sidebarOpen = !sidebarOpen" @tab="switchTab" @search-text="updateSearch" @search-mode="changeSearchMode" @search="submitSearch" @filter="setFilter" @collection="selectCollection" @select="focusPlace" @detail="openDetail" @photo="openPhoto" @location="focusLocation" @save-location="saveLocation" @related="searchRelated" @page="filters.page = $event" @collections="beginCollections" @share="beginShare"><template #actions><div v-if="!readOnly" ref="sidebarActionsAnchor"></div></template></RecordMapSidebar>
      </template>
    </TravelMyMapClusterPanel>
    </div>
        <RecordMapDialog v-if="modal" :open="Boolean(modal)" :title="dialogTitle" :busy="busy" @close="closeDialog">
          <p v-if="error" class="feedback feedback--error" role="alert">{{ error }}</p>
          <p v-if="feedback" class="feedback feedback--success" role="status">{{ feedback }}</p>
          <form v-if="modal === 'place'" class="record-map-form" @submit.prevent="savePlace" @input="dirty = true">
            <p v-if="metadataLoading" role="status">사진의 촬영 날짜·위치를 읽는 중입니다. 직접 입력한 값은 유지됩니다.</p>
            <label class="field">장소 이름<input v-model="placeForm.name" required maxlength="160" placeholder="예: 성수동 A식당" /></label>
            <div class="record-map-form-grid"><label class="field">국가<input v-model="placeForm.country" maxlength="80" /></label><label class="field">지역<input v-model="placeForm.region" maxlength="120" /></label></div>
            <p class="record-map-note">지도를 클릭해 장소를 선택하거나 좌표를 입력하세요.</p>
            <TravelMapPanel :markers="[]" :zoom-animation="false" :location-search="api.searchLocations" :selected-point="placeForm.latitude !== '' && placeForm.longitude !== '' ? { latitude: Number(placeForm.latitude), longitude: Number(placeForm.longitude) } : null" initial-map-size="compact" @pick-location="pickPoint" />
            <div class="record-map-form-grid"><label class="field">위도<input v-model="placeForm.latitude" required type="number" step="any" min="-90" max="90" /></label><label class="field">경도<input v-model="placeForm.longitude" required type="number" step="any" min="-180" max="180" /></label></div>
            <button type="button" class="button" :disabled="gpsLoading" @click="useGps">{{ gpsLoading ? '위치 확인 중' : '현재 위치 사용' }}</button>
            <label class="field">장소 메모<textarea v-model="placeForm.memo" maxlength="2000" rows="3" /></label>
            <fieldset><legend>넣을 모음 · 여러 개 선택 가능</legend><label v-for="c in snapshot.collections" :key="c.id" class="checkbox-row"><input v-model="placeForm.collectionIds" type="checkbox" :value="c.id" /><span>{{ c.name }}</span></label><p v-if="!snapshot.collections.length">모음 없이 저장한 후 모음 관리에서 목록을 만들 수 있습니다.</p></fieldset>
            <label class="checkbox-row"><input v-model="placeForm.visited" type="checkbox" /><span>이미 방문한 곳으로 표시</span></label>
            <label v-if="!placeForm.id" class="checkbox-row"><input v-model="placeForm.recordNow" type="checkbox" /><span>방문 날짜와 사진도 함께 기록</span></label>
            <div v-if="placeForm.recordNow" class="record-map-form">
              <div class="record-map-form-grid"><label class="field">방문 날짜<input v-model="visitForm.date" type="date" required /></label><label class="field">시간 · 선택<input v-model="visitForm.time" type="time" /></label></div>
              <label class="field">방문 제목<input v-model="visitForm.title" maxlength="160" :placeholder="placeForm.name || '방문 제목'" /></label>
              <label class="field">이날의 이야기<textarea v-model="visitForm.memo" maxlength="4000" rows="4" /></label>
              <label class="field">사진 · 같은 장소에 첨부<input type="file" accept="image/*" multiple @change="visitFilesPicked" /></label>
              <span>업로드할 사진 {{ photosPending.length }}장 · 1장당 15MB, 방문당 최대 20장</span>
            </div>
            <p v-if="placeForm.sourceTravelRecordId" class="record-map-note">연결된 여행 기록은 원본 여행에서 관리합니다. 여기서 수정하는 장소 정보는 원본 여행에 영향을 주지 않습니다.</p>
            <button class="button button--primary" :disabled="busy || gpsLoading">{{ busy ? '저장 중...' : '장소 저장' }}</button>
          </form>

          <form v-else-if="modal === 'visit'" class="record-map-form" @submit.prevent="saveVisit" @input="dirty = true">
            <label class="field">방문 장소<select v-model="visitForm.placeId" :disabled="Boolean(visitForm.id)" required><option v-for="p in allPlaces" :key="p.id" :value="p.id">{{ p.name }}</option></select></label>
            <label class="field">제목<input v-model="visitForm.title" maxlength="160" placeholder="이날의 방문 기록" /></label>
            <div class="record-map-form-grid"><label class="field">날짜<input v-model="visitForm.date" type="date" required /></label><label class="field">시간 · 선택<input v-model="visitForm.time" type="time" /></label></div>
            <label class="field">메모<textarea v-model="visitForm.memo" maxlength="4000" rows="5" /></label>
            <label class="field">첨부 사진<input type="file" accept="image/*" multiple @change="visitFilesPicked" /></label><span>업로드할 사진 {{ photosPending.length }}장 · 한 장당 15MB 이하</span>
            <button class="button button--primary" :disabled="busy">{{ busy ? '저장 중...' : '방문 기록 저장' }}</button>
          </form>

          <div v-else-if="modal === 'collection'" class="record-map-form">
            <div class="record-map-collection-list"><article v-for="c in snapshot.collections" :key="c.id"><strong><i :style="{ background: c.colorHex }"></i>{{ c.name }}</strong><div class="record-map-actions"><button class="button" @click="Object.assign(collectionForm, c); dirty = false">수정</button><button class="button button--danger" @click="deleteCollection(c)" :disabled="busy">모음 삭제</button></div></article></div>
            <form class="record-map-form" @submit.prevent="saveCollection" @input="dirty = true"><h3>{{ collectionForm.id ? '모음 수정' : '새 모음' }}</h3><label class="field">이름<input v-model="collectionForm.name" required maxlength="100" placeholder="한국 맛집, 산책 장소, 가고 싶은 곳" /></label><label class="field">설명<textarea v-model="collectionForm.memo" maxlength="1000" rows="2" /></label><label class="field">지도 색상<input v-model="collectionForm.colorHex" type="color" /></label><div class="record-map-actions"><button class="button button--primary" :disabled="busy">모음 저장</button><button class="button" type="button" @click="Object.assign(collectionForm, { id: null, name: '', memo: '', colorHex: '#009E8C' }); dirty = false">새로 작성</button></div></form>
            <p class="record-map-note">모음 삭제는 장소와 사진을 지우지 않습니다. 공유 중인 이 모음의 링크는 종료됩니다.</p>
          </div>

          <div v-else-if="modal === 'travel'" class="record-map-form">
            <p>소유한 여행 장소를 연결합니다. 사진과 방문 날짜는 여행 원본을 참조합니다.</p>
            <fieldset><legend>추가할 모음</legend><label v-for="c in snapshot.collections" :key="c.id" class="checkbox-row"><input v-model="candidateCollections" type="checkbox" :value="c.id" /><span>{{ c.name }}</span></label></fieldset>
            <div class="record-map-collection-list"><article v-for="item in candidates.content" :key="item.id"><div><strong>{{ item.title }}</strong><p>{{ item.planName }} · {{ item.date }} · {{ item.region }}</p></div><button class="button" :disabled="busy" @click="importTravel(item)">장소 가져오기</button></article></div>
            <p v-if="!candidates.content.length">위치가 등록된 여행 기록이 없습니다.</p>
            <div class="record-map-pagination"><button class="button" :disabled="!candidates.number || busy" @click="loadCandidates(candidates.number - 1)">이전</button><span>{{ candidates.number + 1 }} / {{ Math.max(1, candidates.totalPages) }}</span><button class="button" :disabled="candidates.number + 1 >= candidates.totalPages || busy" @click="loadCandidates(candidates.number + 1)">다음</button></div>
          </div>

          <div v-else-if="modal === 'share'" class="record-map-form">
            <label class="field">공유할 모음<select v-model="shareCollectionId" @change="shareUrl = ''; loadShares()" :disabled="busy"><option v-for="c in snapshot.collections" :key="c.id" :value="String(c.id)">{{ c.name }}</option></select></label>
            <p>주소를 가진 사람이 로그인 없이 선택한 모음을 읽기 전용으로 봅니다. 모음에 장소를 추가하거나 빼면 공유 지도에도 반영됩니다.</p>
            <p>기본 공개 내용: 모음 이름, 장소 이름·좌표, 방문 여부.</p>
            <label class="checkbox-row"><input v-model="includeVisits" type="checkbox" :disabled="busy" /><span>이 모음에 연결된 사진·방문 날짜·메모도 공유</span></label>
            <label class="field">공유 기간 · 일<input v-model.number="expiresDays" type="number" min="1" max="365" :disabled="busy" /></label>
            <button class="button button--primary" :disabled="busy || !shareCollectionId" @click="createShare">공개 공유 링크 만들기</button>
            <div v-if="shareUrl" class="record-map-form"><label class="field">생성된 공유 주소<input :value="shareUrl" readonly @focus="$event.target.select()" /></label><div class="record-map-actions"><button class="button" @click="copyShare">주소 복사</button><a class="button" :href="shareUrl" target="_blank" rel="noopener noreferrer">공유 지도 열기</a></div><small>주소는 이 화면에서만 제공됩니다. 다시 접속하면 새 링크를 만들 수 있습니다.</small></div>
            <div class="record-map-collection-list"><article v-for="link in shareList" :key="link.id"><span>{{ link.active ? '공유 중' : '종료' }} · {{ link.includeVisits ? '사진·방문 기록 포함' : '장소만' }} · {{ link.expiresAt?.slice(0, 16).replace('T', ' ') }}</span><button class="button" v-if="link.active" :disabled="busy" @click="revokeShare(link)">공유 종료</button></article></div>
          </div>

          <div v-else-if="modal === 'detail' && selectedPlace" class="record-map-form">
            <p>{{ [selectedPlace.country, selectedPlace.region].filter(Boolean).join(' · ') }} · {{ selectedPlace.visited ? '방문한 곳' : '가고 싶은 곳' }}</p>
            <p class="record-map-memo">{{ selectedPlace.memo }}</p>
            <div v-if="!readOnly" class="record-map-actions"><button class="button" @click="editPlace(selectedPlace)">장소·모음 수정</button><button class="button button--primary" @click="beginVisit()">방문 기록 남기기</button><button class="button button--danger" @click="deletePlace(selectedPlace)" :disabled="busy">장소 삭제</button></div>
            <h3>날짜별 방문 기록</h3><p v-if="detailLoading" role="status">방문 기록을 불러오는 중입니다.</p>
            <article v-for="visit in detailVisits" :key="visit.id" class="record-map-visit">
              <header><div><strong>{{ visit.title }}</strong><p>{{ visit.date }} {{ visit.time?.slice(0, 5) }}<span v-if="visit.travelPlanName"> · {{ visit.travelPlanName }}</span></p></div><div v-if="!readOnly && visit.id.startsWith('visit:')" class="record-map-actions"><button class="button" @click="beginVisit(visit)">수정</button><button class="button button--danger" @click="deleteVisit(visit)" :disabled="busy">삭제</button></div></header>
              <p class="record-map-memo">{{ visit.memo }}</p>
              <div class="record-map-photo-grid"><button v-for="photo in visit.photos" :key="photo.id" type="button" @click="openPhoto(photo, visit)"><img :src="buildThumbnailUrl(photo.photoUrl, 'mini')" :alt="photo.name" loading="lazy" /></button></div>
              <small v-if="visit.id.startsWith('travel:')">연결된 여행 원본 기록입니다. 여행 화면에서 수정할 수 있습니다.</small>
            </article>
            <p v-if="!detailVisits.length && !detailLoading">아직 방문 기록이 없습니다. 가고 싶은 장소로 보관하거나 첫 방문을 남겨보세요.</p>
            <div v-if="!readOnly" class="record-map-pagination"><button class="button" :disabled="!detailPage || detailLoading" @click="loadDetail(detailPage - 1)">이전 기록</button><span>{{ detailPage + 1 }}페이지</span><button class="button" :disabled="!detailHasMore || detailLoading" @click="loadDetail(detailPage + 1)">다음 기록</button></div>
          </div>

          <div v-else-if="modal === 'photo' && selectedPhoto" class="record-map-form">
            <img class="record-map-large-photo" :src="selectedPhoto.photoUrl" :alt="selectedPhoto.name" />
            <p>{{ selectedPhoto.visit?.title }} · {{ selectedPhoto.visit?.date }} {{ selectedPhoto.visit?.time?.slice(0,5) }}</p>
            <p class="record-map-memo">{{ selectedPhoto.visit?.memo }}</p>
            <div class="record-map-actions"><button class="button" :disabled="activePhotoIndex <= 0" @click="changePhoto(-1)">이전 사진</button><button class="button" :disabled="activePhotoIndex < 0 || activePhotoIndex + 1 >= photoSequence.length" @click="changePhoto(1)">다음 사진</button><button class="button button--danger" v-if="!readOnly && selectedPhoto.id.startsWith('photo:')" :disabled="busy" @click="deletePhoto(selectedPhoto)">사진 삭제</button></div>
          </div>
        </RecordMapDialog>

  </div>
</template>

<style scoped>
:global(body:has(.record-map-workspace .travel-map--fullscreen) .pet-companion) { display: none !important; }
:global(body:has(.record-map-workspace .travel-map--fullscreen) .theme-switcher) { display:none !important; }
.record-map-workspace > .feedback { margin:0; padding:6px 10px; font-size:.8rem; }
.record-map-workspace { gap:8px; }
.record-map-header { display: flex; flex-wrap: wrap; align-items:center; justify-content: space-between; gap: 8px; padding:10px 14px; }
.record-map-header h2 { margin: 5px 0; } .record-map-header p { margin: 0; }
.record-map-header h2 { font-size:1.1rem; }
.record-map-add { position:relative; z-index:1100; }
.record-map-add summary { cursor:pointer; list-style:none; font-size:.78rem; padding:7px 4px; }
:global(html body .app-shell .record-map-workspace .travel-map .record-map-tools .record-map-add summary.button--primary) { background:#365b45 !important; color:#fff !important; -webkit-text-fill-color:#fff !important; text-shadow:none !important; }
:global(html body .app-shell .record-map-workspace .travel-map .record-map-sidebar .record-map-sidebar__header .record-map-add summary.button--primary) { background:#365b45 !important; color:#fff !important; -webkit-text-fill-color:#fff !important; text-shadow:none !important; font-size:12px !important; padding:6px !important; }
:global(html body .app-shell .record-map-workspace .travel-map .record-map-sidebar .record-map-search button.button[type="submit"]) { background:var(--panel-bg) !important; color:var(--text) !important; -webkit-text-fill-color:var(--text) !important; text-shadow:none !important; }
.record-map-add__menu { position:absolute; top:calc(100% + 6px); right:0; width:200px; max-height:50dvh; overflow-y:auto; display:grid; gap:4px; padding:8px; border:1px solid var(--line); background:var(--panel-bg); box-shadow:0 6px 24px #0003; }
.record-map-add__menu .button { text-align:left; width:100%; }
.record-map-workspace { --map-tile-filter:var(--travel-map-tile-filter-light); }
:global(:root[data-theme='toss'] .record-map-workspace) { --map-tile-filter:var(--travel-map-tile-filter-dark); }
.record-map-frame { position:relative; width:100%; min-width:0; height:var(--record-map-height); }
.record-map-frame :deep(.travel-map--record-workspace) { position:relative; display:block !important; margin:0; padding:0; width:100%; height:100%; min-width:0; border:1px solid var(--line); overflow:hidden; box-sizing:border-box; }
.record-map-frame :deep(.travel-map--record-workspace.travel-map--fullscreen) { position:fixed; width:100vw; height:100dvh; }
.record-map-frame :deep(.travel-map--record-workspace > .travel-map__toolbar) { position:absolute !important; z-index:1002; top:12px !important; right:12px !important; bottom:auto !important; left:auto !important; width:auto !important; max-width:none !important; max-height:none !important; height:auto !important; margin:0; padding:0 !important; border:0 !important; background:transparent !important; transform:none !important; overflow:visible !important; }
.record-map-frame :deep(.travel-map--record-workspace .travel-map__stage) { position:relative; width:100%; height:100% !important; min-height:0 !important; padding:0; }
.record-map-frame :deep(.travel-map--record-workspace .travel-map__canvas) { height:100% !important; min-height:0 !important; width:100%; }
.record-map-tools { display:grid; gap:5px; width:92px; padding:8px; box-sizing:border-box; background:var(--panel-bg); color:var(--text); border:1px solid var(--line); box-shadow:0 4px 16px #0003; }
.record-map-tools .button { padding:7px 4px !important; min-height:36px !important; min-width:0 !important; max-width:100% !important; width:100%; box-sizing:border-box; font-size:.78rem !important; }
.record-map-tools__zoom { display:grid; grid-template-columns:1fr auto 1fr; align-items:center; gap:3px; }
.record-map-tools__zoom .travel-cluster-map__zoom { min-width:20px; font-size:.8rem; }
.record-map-tools summary { cursor:pointer; text-align:center; padding:8px 0; font-size:.78rem; }
.record-map-tools__settings { display:grid; gap:5px; }
@media(max-width:760px) {
  :global(body:has(.record-map-workspace) .app-shell) { padding-top:0 !important; }
  :global(body:has(.record-map-workspace) .theme-switcher) { position:static !important; padding:4px 8px !important; margin:0 !important; min-height:0 !important; justify-content:flex-end; }
  :global(body:has(.record-map-workspace) .theme-switcher button) { min-height:30px !important; padding:4px 7px !important; font-size:11px !important; }
  :global(body:has(.record-map-workspace) .topbar) { display:flex !important; flex-wrap:wrap; gap:4px !important; padding:6px 8px !important; min-height:0 !important; }
  :global(body:has(.record-map-workspace) .topbar__copy) { flex:1; min-width:0; }
  :global(body:has(.record-map-workspace) .topbar__copy :is(.topbar__eyebrow,.topbar__title,.topbar__description,.topbar__context)) { display:none !important; }
  :global(body:has(.record-map-workspace) .topbar__brand) { font-size:14px !important; }
  :global(body:has(.record-map-workspace) .topbar__actions) { width:auto !important; gap:4px !important; }
  :global(html body:has(.record-map-workspace) .app-shell .topbar__actions) { display:flex !important; flex-direction:row !important; flex-wrap:nowrap !important; }
  :global(body:has(.record-map-workspace) .topbar__actions .button) { min-height:30px !important; min-width:0 !important; padding:4px 7px !important; font-size:11px !important; }
  :global(html body:has(.record-map-workspace) .app-shell .topbar__actions .button) { width:auto !important; flex:0 0 auto !important; }
  :global(body:has(.record-map-workspace) .topbar__nav) { width:100% !important; gap:3px !important; padding:0 !important; }
  :global(body:has(.record-map-workspace) .topbar__nav-button) { min-height:34px !important; padding:5px 8px !important; font-size:12px !important; }
  :global(body:has(.record-map-workspace) .record-map-mode-switch) { gap:5px; padding:6px 8px !important; }
  :global(body:has(.record-map-workspace) .record-map-mode-switch .button) { min-height:34px !important; font-size:12px !important; padding:5px 8px !important; }
  .record-map-frame :deep(.travel-map--record-workspace > .travel-map__toolbar) { top:8px !important; right:8px !important; left:8px !important; }
  .record-map-frame :deep(.travel-map--record-workspace.travel-map--sidebar-expanded > .travel-map__toolbar) { display:none !important; }
  .record-map-tools { width:100%; padding:5px; grid-template-columns:repeat(4,minmax(0,1fr)); }
  .record-map-tools__zoom { grid-column:span 2; }
  .record-map-tools > button:nth-of-type(2), .record-map-tools > details { grid-column:span 2; }
  .record-map-tools .button { min-height:36px; }
  .record-map-header { padding:6px 10px !important; min-height:0 !important; }
  .record-map-header h2 { margin:0 !important; font-size:1rem !important; }
}
.record-map-actions, .record-map-floating-actions { display: flex; gap: 6px; flex-wrap: wrap; align-items: center; }
.record-map-actions > *, .record-map-floating-actions > * { flex-shrink: 0; }
.record-map-filters { display: flex; gap: 12px; flex-wrap: wrap; align-items: end; }
.record-map-filters > label { display: grid; gap: 6px; flex: 1 1 140px; min-width: 0; }
.record-map-filters input, .record-map-filters select { width: 100%; min-width: 0; }
.record-map-form { display: grid; gap: 14px; min-width: 0; }
.record-map-form-grid { display: grid; grid-template-columns: repeat(2, minmax(0,1fr)); gap: 12px; }
.record-map-form .field { min-width: 0; }
.record-map-form :deep(.travel-map) { min-width: 0; max-width: 100%; width: 100%; margin: 0; padding: 12px; box-sizing: border-box; }
.record-map-form :deep(.travel-map__toolbar), .record-map-form :deep(.travel-map__search), .record-map-form :deep(.travel-map__search-form), .record-map-form :deep(.travel-map__toolbar-group) { min-width: 0; max-width: 100%; box-sizing: border-box; }
.record-map-form :deep(.travel-map__search-input) { min-width: 0; max-width: 100%; box-sizing: border-box; }
:global(html body .app-shell .record-map-workspace .record-map-dialog .record-map-form .travel-map .travel-map__toolbar .travel-map__toolbar-button:not(.is-active)) { color: var(--text) !important; -webkit-text-fill-color: var(--text) !important; background: var(--panel-bg) !important; text-shadow: none; }
.record-map-form input, .record-map-form textarea, .record-map-form select { width: 100%; min-width: 0; }
.record-map-form input[type="checkbox"] { width: auto; }
.record-map-form input[type="color"] { width: 72px; height: 42px; padding: 4px; }
fieldset { display: flex; gap: 10px; flex-wrap: wrap; border: 1px solid #53646877; padding: 12px; }
.record-map-note { font-size: .85rem; line-height: 1.6; opacity: .85; margin: 0; }
.record-map-collection-list { display: grid; gap: 8px; }
.record-map-collection-list article { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 12px; padding: 12px; border: 1px solid #53646877; }
.record-map-collection-list strong { overflow-wrap: anywhere; }
.record-map-collection-list i { display: inline-block; width: 10px; height: 10px; margin-right: 8px; }
.record-map-place-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 260px),1fr)); gap: 12px; }
.record-map-place-grid article, .record-map-visit, .record-map-diary article { padding: 16px; border: 1px solid #53646877; min-width: 0; }
.record-map-state { display: inline-block; background: #ba812522; color: #c98c2f; border-left: 3px solid currentColor; padding: 5px 8px; font-size: .8rem; }
.record-map-state.is-visited { background: #009e8c22; color: #2aaf9e; }
.record-map-place-grid h3, .record-map-memo { overflow-wrap: anywhere; }
.record-map-memo { white-space: pre-wrap; }
.record-map-visit header, .record-map-diary header { display: flex; justify-content: space-between; flex-wrap: wrap; align-items: center; gap: 10px; }
.record-map-diary { display: grid; gap: 14px; }
.record-map-photo-grid { display: grid; grid-template-columns: repeat(auto-fill,minmax(100px,1fr)); gap: 8px; }
.record-map-photo-grid button { padding: 0; border: 1px solid #53646877; background: transparent; height: 110px; overflow: hidden; cursor: pointer; }
.record-map-photo-grid img { width: 100%; height: 100%; object-fit: cover; }
.record-map-large-photo { width: 100%; max-height: 64dvh; object-fit: contain; }
.record-map-pagination { display: flex; gap: 10px; flex-wrap: wrap; justify-content: center; align-items: center; padding: 14px 0; }
.record-map-empty { text-align: center; padding: 24px; }
@media(max-width:600px) { .record-map-form-grid { grid-template-columns: 1fr; } .record-map-header { padding: 14px; } .record-map-actions > .button { flex: 1 1 auto; } .record-map-pagination .button { min-height: 44px; } }
</style>
