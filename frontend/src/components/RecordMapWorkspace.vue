<script setup>
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { recordMapApi as api } from '../lib/recordMapApi'
import { buildRecordMapData, buildRecordMapJourney } from '../lib/recordMap'
import { extractPhotoMetadata } from '../lib/photoMetadata'
import { buildThumbnailUrl } from '../lib/mediaPreview'
import { todayIso } from '../lib/uiFormat'
import TravelMyMapClusterPanel from './TravelMyMapClusterPanel.vue'
import TravelMapPanel from './TravelMapPanel.vue'
import RecordMapDialog from './RecordMapDialog.vue'
import RecordMapPlaybackControls from './RecordMapPlaybackControls.vue'

const props = defineProps({ token: { type: String, default: '' }, publicView: Boolean })
const emit = defineEmits(['open-travel'])
const readOnly = computed(() => props.publicView || Boolean(props.token))
const snapshot = ref({ places: [], collections: [], visits: [], totalPlaces: 0, totalPages: 0 })
const filters = reactive({ collectionId: '', state: 'ALL', from: '', to: '', page: 0 })
const loading = ref(false), busy = ref(false), error = ref(''), feedback = ref('')
const view = ref('map'), displayMode = ref('cluster'), pickMode = ref(false)
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
const playbackDay = ref(''), playing = ref(false), speed = ref(4), playIndex = ref(-1), countdown = ref(0), nextDate = ref('')
let requestSequence = 0, detailSequence = 0, playbackSequence = 0, timer = null, releaseWait = null, focusSequence = 0, metadataSequence = 0

const selectedPlace = computed(() => snapshot.value.places.find(p => p.id === selectedPlaceId.value) || null)
const data = computed(() => buildRecordMapData(snapshot.value))
const journey = computed(() => buildRecordMapJourney(snapshot.value, playbackDay.value))
const days = computed(() => [...new Set(snapshot.value.visits.map(v => v.date).filter(Boolean))].sort())
const activeStep = computed(() => journey.value[playIndex.value] || null)
const photoSequence = computed(() => (detailVisits.value.some(v => v.placeId === selectedPlaceId.value) ? detailVisits.value.filter(v => v.placeId === selectedPlaceId.value) : snapshot.value.visits.filter(v => v.placeId === selectedPlaceId.value))
  .flatMap(v => (v.photos || []).map(p => ({ ...p, visit: v }))))
const activePhotoIndex = computed(() => photoSequence.value.findIndex(p => p.id === selectedPhoto.value?.id))
const dialogTitle = computed(() => ({ place: placeForm.id ? '장소 수정' : '장소 저장', visit: visitForm.id ? '방문 기록 수정' : '방문 기록 남기기',
  collection: '내 장소 모음', travel: '여행 장소를 모음에 추가', share: '모음 지도 공유', detail: selectedPlace.value?.name || '장소 상세', photo: selectedPhoto.value?.name || '사진 보기' }[modal.value] || '기록 지도'))

async function load() {
  const sequence = ++requestSequence
  stopPlayback(); playIndex.value = -1
  loading.value = true
  error.value = ''
  try {
    if (readOnly.value && !props.token) throw new Error('공유 주소가 올바르지 않습니다.')
    const result = await api.overview({ ...filters, collectionId: readOnly.value ? null : filters.collectionId }, props.token)
    if (sequence !== requestSequence) return
    snapshot.value = result
    if (!result.places.some(p => p.id === selectedPlaceId.value)) selectedPlaceId.value = null
    fitKey.value++
  } catch (e) {
    if (sequence !== requestSequence) return
    error.value = e.message
    if (readOnly.value && [401, 403, 404].includes(e.status)) {
      snapshot.value = { places: [], collections: [], visits: [], totalPlaces: 0, totalPages: 0 }
      selectedPlaceId.value = null; selectedPhotoId.value = null; selectedMarkerId.value = null
      modal.value = ''; selectedPhoto.value = null; detailVisits.value = []; detailSequence++
    }
  }
  finally { if (sequence === requestSequence) loading.value = false }
}
watch(() => props.token, () => {
  snapshot.value = { places: [], collections: [], visits: [], totalPlaces: 0, totalPages: 0 }
  selectedPlaceId.value = null; selectedPhotoId.value = null; selectedMarkerId.value = null
  modal.value = ''; selectedPhoto.value = null; detailVisits.value = []; detailSequence++
  filters.page = 0; load()
}, { immediate: true })
watch(() => [filters.collectionId, filters.state, filters.from, filters.to], () => { filters.page = 0; playIndex.value = -1; load() })
watch(() => filters.page, () => { playIndex.value = -1; load() })
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
  stopPlayback(); metadataSequence++; metadataLoading.value = false; gpsLoading.value = false; dirty.value = false; error.value = ''
  Object.assign(placeForm, { id: null, name: '', memo: '', country: '', region: '', latitude: point?.latitude ?? '', longitude: point?.longitude ?? '',
    visited: false, collectionIds: defaultCollections(), recordNow, sourceTravelRecordId: null })
  resetVisit(); modal.value = 'place'; pickMode.value = false
}
function editPlace(place) {
  stopPlayback(); metadataSequence++; metadataLoading.value = false; gpsLoading.value = false; dirty.value = false
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
    selectedPlaceId.value = result.id; feedback.value = '장소와 기록을 저장했습니다.'
  })
}
function beginVisit(visit = null) {
  stopPlayback(); error.value = ''; dirty.value = false
  if (!snapshot.value.places.length) { beginPlace(null, true); return }
  resetVisit(visit?.placeId || selectedPlaceId.value || snapshot.value.places[0].id)
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
function beginCollections() { stopPlayback(); dirty.value = false; Object.assign(collectionForm, { id: null, name: '', memo: '', colorHex: '#009E8C' }); modal.value = 'collection' }
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
  stopPlayback()
  const id = item?.recordMapPlaceId ?? item?.placeId ?? Number(String(item?.id || item?.clusterId || '').split(':').at(-1))
  if (!snapshot.value.places.some(p => p.id === Number(id))) return
  if (selectedPlaceId.value !== Number(id)) { detailVisits.value = []; detailSequence++ }
  selectedPlaceId.value = Number(id)
  selectedPhotoId.value = item.mediaId || item.representativeMediaId || null
  selectedMarkerId.value = selectedPhotoId.value ? null : 'place:' + id
}
function focusPlace(place) {
  stopPlayback(); selectedPlaceId.value = place.id; selectedMarkerId.value = 'place:' + place.id
  const pin = data.value.photoPins.find(p => p.recordMapPlaceId === place.id)
  selectedPhotoId.value = pin?.mediaId || null
  focusTarget.value = { requestId: ++focusSequence, latitude: place.latitude, longitude: place.longitude, zoom: 15, duration: .8 }
  view.value = 'map'
}
async function openDetail(place) {
  if (!place) return
  stopPlayback(); dirty.value = false; detailVisits.value = []; selectedPlaceId.value = place.id; modal.value = 'detail'; await loadDetail(0)
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
  stopPlayback()
  if (visit) selectedPlaceId.value = visit.placeId
  selectedPhoto.value = { ...photo, visit }; modal.value = 'photo'
}
function mapPreviewOpen(photo) {
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
  stopPlayback(); dirty.value = false; candidateCollections.value = defaultCollections(); modal.value = 'travel'; await loadCandidates(0)
}
async function loadCandidates(page) {
  try { candidates.value = await api.candidates(page) } catch (e) { error.value = e.message }
}
function importTravel(item) {
  act(async () => { const result = await api.importTravel(item.id, candidateCollections.value); modal.value = ''; await load(); selectedPlaceId.value = result.id; feedback.value = '여행 원본을 유지하며 장소를 연결했습니다.' })
}
async function beginShare() {
  stopPlayback(); dirty.value = false; shareUrl.value = ''; includeVisits.value = false
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

function wait(ms) {
  return new Promise(resolve => {
    releaseWait = resolve
    timer = window.setTimeout(() => { timer = null; releaseWait = null; resolve() }, ms)
  })
}
function stopPlayback() {
  playing.value = false; playbackSequence++; countdown.value = 0
  if (timer) window.clearTimeout(timer)
  timer = null; releaseWait?.(); releaseWait = null; mapRef.value?.cancelJourneyLeg?.()
}
async function play() {
  if (playing.value) { stopPlayback(); return }
  if (!journey.value.length) return
  view.value = 'map'; playing.value = true
  const sequence = ++playbackSequence
  let index = playIndex.value < 0 || playIndex.value >= journey.value.length - 1 ? 0 : playIndex.value
  const steps = [...journey.value]
  for (; index < steps.length && playing.value && sequence === playbackSequence; index++) {
    const step = steps[index]
    if (index > 0 && steps[index - 1].date !== step.date) {
      nextDate.value = step.date
      for (let n = 3; n > 0 && sequence === playbackSequence; n--) { countdown.value = n; await wait(1000) }
      countdown.value = 0
    }
    if (sequence !== playbackSequence) return
    if (index > 0) await mapRef.value?.prepareJourneyLeg?.(steps[index - 1], step, { focusCurrent: false })
    if (sequence !== playbackSequence) return
    playIndex.value = index; selectedPlaceId.value = step.placeId; selectedPhotoId.value = step.mediaId; selectedMarkerId.value = step.markerId
    await nextTick()
    if (!step.mediaId) {
      const place = selectedPlace.value
      mapRef.value?.previewRecordMarker?.({ ...place, id: step.markerId, recordMap: true, recordMapPlaceId: step.placeId,
        title: step.title, memoryDate: step.date, memoryTime: step.time, memoryCount: 1, photoUrl: '' })
    }
    await mapRef.value?.focusJourneyRecord?.(step, 16)
    if (sequence !== playbackSequence) return
    await wait(speed.value * 1000)
  }
  if (sequence === playbackSequence) { playing.value = false; feedback.value = '현재 지도에 표시된 기록 재생이 끝났습니다.' }
}
function setDay(day) { stopPlayback(); playbackDay.value = day; playIndex.value = -1 }
function setSpeed(seconds) { const resume = playing.value; stopPlayback(); speed.value = seconds; if (resume) play() }
function leaveGuard(event) { if (dirty.value) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', leaveGuard)
onBeforeUnmount(() => {
  requestSequence++; detailSequence++; metadataSequence++; stopPlayback()
  window.removeEventListener('beforeunload', leaveGuard)
  window.dispatchEvent(new CustomEvent('calen-route-leave-guard', { detail: { active: false } }))
})
</script>

<template>
  <div class="workspace-stack record-map-workspace">
    <header class="panel record-map-header">
      <div><span class="panel__eyebrow">RECORD MAP</span><h2>{{ snapshot.title || '기록 지도' }}</h2><p>{{ readOnly ? '공유가 허용된 장소와 기록을 읽기 전용으로 살펴보세요.' : '가고 싶은 곳을 저장하고, 다녀온 날의 사진과 이야기를 남겨보세요.' }}</p></div>
      <div v-if="!readOnly" class="record-map-actions">
        <button class="button button--primary" @click="beginPlace()" :disabled="busy">장소 저장</button>
        <button class="button" @click="photoInput.click()" :disabled="busy">사진으로 기록</button>
        <button class="button" @click="beginVisit()" :disabled="busy">방문 기록 남기기</button>
        <button class="button" @click="beginCollections">모음 관리</button>
        <button class="button" @click="beginTravelImport">여행 장소 가져오기</button>
        <button class="button" :disabled="!snapshot.collections.length" @click="beginShare">모음 공유</button>
        <input ref="photoInput" type="file" accept="image/*" multiple hidden @change="photoPicked" />
      </div>
    </header>
    <p v-if="error" class="feedback feedback--error" role="alert">{{ error }}</p>
    <p v-if="feedback" class="feedback feedback--success" role="status">{{ feedback }}</p>
    <section class="panel record-map-filters" aria-label="기록 지도 조회 조건">
      <label>장소 모음<select v-model="filters.collectionId" :disabled="readOnly || busy"><option value="">전체 장소</option><option v-for="c in snapshot.collections" :key="c.id" :value="String(c.id)">{{ c.name }}</option></select></label>
      <label>방문 상태<select v-model="filters.state" :disabled="busy"><option value="ALL">전체</option><option value="WANTED">가고 싶은 곳</option><option value="VISITED">방문한 곳</option></select></label>
      <label v-if="!readOnly || snapshot.visitsIncluded">기록 시작일<input v-model="filters.from" type="date" :disabled="busy" /></label>
      <label v-if="!readOnly || snapshot.visitsIncluded">기록 종료일<input v-model="filters.to" type="date" :disabled="busy" /></label>
      <div class="record-map-actions"><button class="button" @click="view = 'map'">지도</button><button class="button" @click="view = 'places'">장소 목록</button><button class="button" @click="view = 'diary'">일상 기록</button><button class="button" @click="load" :disabled="loading">새로고침</button></div>
    </section>
    <p v-if="loading" role="status">기록을 불러오는 중입니다.</p>
    <p v-if="snapshot.visitsLimited" class="record-map-note">지도 재생에는 최근 방문 기록 200건과 연결된 여행 사진 최대 1,000장이 표시됩니다. 날짜 범위를 좁히거나 장소 상세에서 이전 방문 기록을 확인하세요.</p>

    <TravelMyMapClusterPanel v-show="view === 'map'" ref="mapRef" :active="view === 'map'" show-embedded-controls include-record-markers :markers="data.markers" :photo-pins="data.photoPins" :photo-clusters="data.photoClusters"
      :display-mode="displayMode" :fit-request-key="fitKey" :focus-target="focusTarget" :selected-marker-id="selectedMarkerId" :selected-photo-id="selectedPhotoId"
      :selected-cluster-id="selectedPlaceId ? 'place:' + selectedPlaceId : null" :journey-photo-id="activeStep?.mediaId" :journey-playback-active="playing"
      :enable-pick-location="pickMode && !readOnly" @pick-location="pickPoint" @select-marker="selectPlace" @select-cluster="selectPlace" @select-photo-pin="selectPlace" @preview-cluster="mapPreviewOpen" @clear-selection="selectedPlaceId = null; selectedPhotoId = null; selectedMarkerId = null">
      <template #fullscreen-controls>
        <div class="record-map-floating-actions"><button class="button" @click="displayMode = displayMode === 'cluster' ? 'pin' : 'cluster'">{{ displayMode === 'cluster' ? '핀 보기' : '썸네일 보기' }}</button><button v-if="!readOnly" class="button" :aria-pressed="pickMode" @click="pickMode = !pickMode">{{ pickMode ? '핀 위치를 눌러 주세요' : '지도에 핀 찍기' }}</button><button class="button" v-if="selectedPlace" @click="openDetail(selectedPlace)">선택 장소 자세히</button></div>
        <RecordMapPlaybackControls :days="days" :day="playbackDay" :playing="playing" :speed="speed" :index="playIndex" :total="journey.length" :busy="loading || busy" :countdown="countdown" :next-date="nextDate" @day="setDay" @toggle="play" @speed="setSpeed" />
      </template>
    </TravelMyMapClusterPanel>
        <RecordMapDialog v-if="modal" :open="Boolean(modal)" :title="dialogTitle" :busy="busy" @close="closeDialog">
          <p v-if="error" class="feedback feedback--error" role="alert">{{ error }}</p>
          <p v-if="feedback" class="feedback feedback--success" role="status">{{ feedback }}</p>
          <form v-if="modal === 'place'" class="record-map-form" @submit.prevent="savePlace" @input="dirty = true">
            <p v-if="metadataLoading" role="status">사진의 촬영 날짜·위치를 읽는 중입니다. 직접 입력한 값은 유지됩니다.</p>
            <label class="field">장소 이름<input v-model="placeForm.name" required maxlength="160" placeholder="예: 성수동 A식당" /></label>
            <div class="record-map-form-grid"><label class="field">국가<input v-model="placeForm.country" maxlength="80" /></label><label class="field">지역<input v-model="placeForm.region" maxlength="120" /></label></div>
            <p class="record-map-note">지도를 클릭해 장소를 선택하거나 좌표를 입력하세요.</p>
            <TravelMapPanel :markers="[]" :zoom-animation="false" :selected-point="placeForm.latitude !== '' && placeForm.longitude !== '' ? { latitude: Number(placeForm.latitude), longitude: Number(placeForm.longitude) } : null" initial-map-size="compact" @pick-location="pickPoint" />
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
            <label class="field">방문 장소<select v-model="visitForm.placeId" :disabled="Boolean(visitForm.id)" required><option v-for="p in snapshot.places" :key="p.id" :value="p.id">{{ p.name }}</option></select></label>
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

    <section v-if="view === 'places'" class="panel record-map-place-grid">
      <article v-for="place in snapshot.places" :key="place.id"><span class="record-map-state" :class="{ 'is-visited': place.visited }">{{ place.visited ? '방문한 곳' : '가고 싶은 곳' }}</span><h3>{{ place.name }}</h3><p>{{ [place.country, place.region].filter(Boolean).join(' · ') }}</p><p>{{ place.visitCount }}번 기록 · {{ place.collectionIds.map(id => snapshot.collections.find(c => c.id === id)?.name).filter(Boolean).join(', ') || '모음 미지정' }}</p><div class="record-map-actions"><button class="button" @click="focusPlace(place)">지도에서 보기</button><button class="button" @click="openDetail(place)">방문 기록 보기</button></div></article>
    </section>
    <section v-if="view === 'diary'" class="panel record-map-diary">
      <article v-for="visit in [...snapshot.visits].sort((a,b) => (b.date + (b.time || '')).localeCompare(a.date + (a.time || '')))" :key="visit.id"><header><strong>{{ visit.title }}</strong><span>{{ visit.date }} {{ visit.time?.slice(0,5) }} · {{ snapshot.places.find(p => p.id === visit.placeId)?.name }}</span></header><p class="record-map-memo">{{ visit.memo }}</p><div class="record-map-photo-grid"><button v-for="photo in visit.photos" :key="photo.id" type="button" @click="selectedPlaceId = visit.placeId; openPhoto(photo, visit)"><img :src="buildThumbnailUrl(photo.photoUrl, 'mini')" :alt="photo.name" loading="lazy" /></button></div><button class="button" @click="openDetail(snapshot.places.find(p => p.id === visit.placeId))">장소·방문 기록 자세히</button></article>
      <p v-if="!snapshot.visits.length">날짜가 있는 방문 기록이 없습니다. 장소에 첫 방문을 남겨보세요.</p>
    </section>
    <p v-if="!snapshot.places.length && !loading" class="panel record-map-empty">{{ readOnly ? '공유된 모음에 현재 조건과 일치하는 장소가 없습니다.' : '아직 이 조건의 장소가 없습니다. 장소를 저장하거나 사진으로 첫 기록을 남겨보세요.' }}</p>
    <footer class="record-map-pagination"><span>장소 {{ snapshot.totalPlaces }}개 · {{ filters.page + 1 }} / {{ Math.max(1, snapshot.totalPages) }}페이지</span><button class="button" :disabled="filters.page <= 0 || loading" @click="filters.page--">이전</button><button class="button" :disabled="filters.page + 1 >= snapshot.totalPages || loading" @click="filters.page++">다음</button></footer>
  </div>
</template>

<style scoped>
:global(body:has(.record-map-workspace .travel-map--fullscreen) .pet-companion) { display: none !important; }
.record-map-header { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 18px; }
.record-map-header h2 { margin: 5px 0; } .record-map-header p { margin: 0; }
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
