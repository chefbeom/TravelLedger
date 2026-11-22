<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import RecordMapDialog from '../RecordMapDialog.vue'
import TravelMapPanel from '../TravelMapPanel.vue'
import {
  deleteTravelMedia,
  deleteTravelMemory,
  fetchTravelPlan,
  updateTravelMemory,
  uploadTravelMemoryMedia,
} from '../../lib/api'
import { extractPhotoMetadata, reverseGeocode } from '../../lib/photoMetadata'
import { createLocalImagePreview } from '../../lib/mediaPreview'
import {
  IMPORT_CATEGORIES,
  IMPORT_LIMITS,
  classifyImportFile,
  describePhotoAgainstRecord,
  toImportErrorMessage,
} from '../../lib/travelImport'
import { searchTravelLocations } from '../../lib/useTravelImport'

const MAX_NEW_PHOTOS = 50
const DECODE_FAILURE = '사진 파일을 읽을 수 없습니다. 손상되었거나 지원하지 않는 형식입니다.'

const LEAVE_GUARD_EVENT = 'calen-route-leave-guard'

const props = defineProps({
  open: { type: Boolean, default: false },
  planId: { type: [String, Number], default: '' },
  recordId: { type: [String, Number], default: '' },
  initialFiles: { type: Array, default: () => [] },
})
const emit = defineEmits(['close', 'saved', 'deleted'])

const loading = ref(true)
const busy = ref(false)
const error = ref('')
const notice = ref('')
const record = ref(null)
const plan = ref(null)
const photos = ref([])
const newPhotos = ref([])
const form = reactive({ title: '', memoryDate: '', memoryTime: '', category: '장소', country: '', region: '', placeName: '', memo: '', latitude: null, longitude: null })
const namesEdited = ref(false)
const geocoding = ref(false)
const formDirty = ref(false)
const mediaChanged = ref(false)
const recordSaved = ref(false)
const recordWasPut = ref(false)
const mapReady = ref(false)
const staging = ref(false)
const rejectedFiles = ref([])
const pickerInput = ref(null)
const uploadState = reactive({ phase: '', current: 0, total: 0 })
const useFirstPhotoLocation = ref(true)
let appliedPhotoId = ''
let originalNames = { country: '', region: '', placeName: '' }
let initialStaged = false
let stageSequence = 0
let loadSequence = 0
let snapshot = ''
let geocodeSequence = 0
let previewCounter = 0

const hasLocation = computed(() => Number.isFinite(form.latitude) && Number.isFinite(form.longitude)
  && form.latitude !== null && form.longitude !== null)
const selectedPoint = computed(() => (hasLocation.value
  ? { latitude: form.latitude, longitude: form.longitude, colorHex: plan.value?.colorHex || '#3182F6', iconKey: 'general', iconText: '📍' }
  : null))
const locationText = computed(() => {
  const names = [form.placeName, form.country, form.region].filter(Boolean)
  return names.length ? names.join(' · ') : hasLocation.value ? `좌표 ${form.latitude.toFixed(5)}, ${form.longitude.toFixed(5)}` : '위치 없음'
})
const categoryOptions = computed(() => [...new Set([...IMPORT_CATEGORIES, form.category].filter(Boolean))])
const titleProblem = computed(() => {
  const title = form.title.trim()
  if (!title) return '제목을 입력해 주세요.'
  if (title.length > IMPORT_LIMITS.titleMax) return '제목은 120자 이하로 입력해 주세요.'
  return ''
})
const canSave = computed(() => !busy.value && !loading.value && !staging.value && Boolean(record.value) && !titleProblem.value && Boolean(form.memoryDate))
const dirty = computed(() => formDirty.value || newPhotos.value.length > 0)
const recordHasLocation = computed(() => Number.isFinite(Number(record.value?.latitude)) && Number.isFinite(Number(record.value?.longitude))
  && record.value?.latitude !== null && record.value?.longitude !== null && record.value?.latitude !== ''
  && record.value?.longitude !== '')
const firstGpsPhoto = computed(() => newPhotos.value.find((item) => Number.isFinite(item.latitude) && Number.isFinite(item.longitude)) || null)
const showLocationOption = computed(() => Boolean(record.value) && !recordHasLocation.value && Boolean(firstGpsPhoto.value))
const uploadText = computed(() => {
  if (!uploadState.phase) return ''
  if (uploadState.phase === 'preparing') return `사진 준비 중 ${uploadState.current}/${uploadState.total}`
  if (uploadState.phase === 'uploading') return `사진 올리는 중 ${uploadState.current}/${uploadState.total}`
  return '마무리 중'
})

function formatDistance(meters) {
  const value = Math.round(meters)
  return value < 1000 ? `${value}m` : `${(value / 1000).toFixed(1)}km`
}

const photoNotes = computed(() => newPhotos.value.map((item) => {
  const info = describePhotoAgainstRecord(
    { latitude: item.latitude, longitude: item.longitude, date: item.date },
    { latitude: record.value?.latitude, longitude: record.value?.longitude, memoryDate: form.memoryDate },
  )
  const parts = []
  if (info.hasGps) {
    parts.push(info.distanceMeters !== null ? `📍 사진 위치 있음 (기록과 약 ${formatDistance(info.distanceMeters)})` : '📍 사진 위치 있음')
    if (info.far) parts.push(`기록 위치와 약 ${formatDistance(info.distanceMeters)} 떨어져 있습니다. 지도에는 사진 위치에 표시됩니다.`)
  } else if (recordHasLocation.value) {
    parts.push('위치 없음 → 기록 위치에 표시')
  } else {
    parts.push('위치가 없어 지도에는 표시되지 않습니다.')
  }
  if (info.dateDiffers) parts.push(`촬영일 ${item.date.slice(5)} · 기록 날짜 ${form.memoryDate.slice(5)}`)
  return { id: item.id, name: item.file.name, text: parts.join(' · ') }
}))

function revokeNewPhotos() {
  newPhotos.value.forEach((item) => URL.revokeObjectURL(item.previewUrl))
}

async function load() {
  const sequence = ++loadSequence
  loading.value = true
  error.value = ''
  notice.value = ''
  record.value = null
  try {
    const detail = await fetchTravelPlan(props.planId)
    if (sequence !== loadSequence) return
    plan.value = detail
    const found = (detail?.memoryRecords || []).find((item) => String(item.id) === String(props.recordId))
    if (!found) {
      error.value = '기록을 찾지 못했습니다. 이미 삭제되었을 수 있습니다.'
      return
    }
    record.value = found
    photos.value = (detail.mediaItems || []).filter((item) => String(item.recordId) === String(props.recordId)
      && String(item.recordType).toUpperCase() === 'MEMORY' && String(item.mediaType || 'PHOTO').toUpperCase() === 'PHOTO')
    Object.assign(form, {
      title: found.title || '',
      memoryDate: found.memoryDate || '',
      memoryTime: found.memoryTime ? String(found.memoryTime).slice(0, 5) : '',
      category: found.category || '장소',
      country: found.country || '',
      region: found.region || '',
      placeName: found.placeName || '',
      memo: found.memo || '',
      latitude: found.latitude === null || found.latitude === undefined || found.latitude === '' ? null : Number(found.latitude),
      longitude: found.longitude === null || found.longitude === undefined || found.longitude === '' ? null : Number(found.longitude),
    })
    namesEdited.value = false
    originalNames = { country: form.country, region: form.region, placeName: form.placeName }
    snapshot = JSON.stringify(form)
    formDirty.value = false
    recordSaved.value = false
    if (props.initialFiles.length && !initialStaged) {
      initialStaged = true
      stagePhotos(props.initialFiles)
    }
  } catch (loadError) {
    if (sequence === loadSequence) error.value = toImportErrorMessage(loadError)
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}

watch(() => [props.open, props.planId, props.recordId], ([open]) => {
  if (open) {
    initialStaged = false
    load()
  } else {
    stageSequence += 1
  }
}, { immediate: true })

watch(() => JSON.stringify(form), (value) => {
  if (!record.value) return
  if (value === snapshot) {
    formDirty.value = false
    return
  }
  formDirty.value = true
  recordSaved.value = false
})

watch(dirty, (active) => {
  window.dispatchEvent(new CustomEvent(LEAVE_GUARD_EVENT, { detail: { active, message: '저장하지 않은 기록 수정 내용이 있습니다. 나갈까요?' } }))
})

requestAnimationFrame(() => requestAnimationFrame(() => { mapReady.value = true }))

onBeforeUnmount(() => {
  stageSequence += 1
  loadSequence += 1
  geocodeSequence += 1
  revokeNewPhotos()
  window.dispatchEvent(new CustomEvent(LEAVE_GUARD_EVENT, { detail: { active: false } }))
})

async function applyPoint(point) {
  form.latitude = Number(Number(point.latitude).toFixed(7))
  form.longitude = Number(Number(point.longitude).toFixed(7))
  if (namesEdited.value) return
  const sequence = ++geocodeSequence
  if (point.country || point.region || point.placeName) {
    form.country = point.country || form.country
    form.region = point.region || form.region
    form.placeName = point.placeName || form.placeName
    return
  }
  geocoding.value = true
  try {
    const info = await reverseGeocode(form.latitude, form.longitude)
    if (sequence !== geocodeSequence || namesEdited.value) return
    if (info.country || info.region || info.placeName) {
      form.country = info.country || ''
      form.region = info.region || ''
      form.placeName = info.placeName || ''
    }
  } catch {
    // keep the old names; the coordinates are what matters
  } finally {
    if (sequence === geocodeSequence) geocoding.value = false
  }
}

function openPicker() {
  pickerInput.value?.click()
}

async function stagePhotos(fileList) {
  const files = Array.from(fileList || [])
  if (!files.length) return
  const sequence = ++stageSequence
  staging.value = true
  const nextRejected = []
  const added = []
  try {
    let room = Math.max(0, MAX_NEW_PHOTOS - newPhotos.value.length)
    for (const file of files) {
      if (sequence !== stageSequence) break
      if (room <= 0) {
        nextRejected.push({ id: `rej-${++previewCounter}`, name: file.name, reason: `한 번에 최대 ${MAX_NEW_PHOTOS}장까지 추가할 수 있습니다.` })
        continue
      }
      const kind = classifyImportFile(file, { photosOnly: true })
      if (kind.kind === 'rejected') {
        nextRejected.push({ id: `rej-${++previewCounter}`, name: file.name, reason: kind.reason })
        continue
      }
      let meta = null
      try {
        meta = await extractPhotoMetadata(file, { reverseGeocode: false })
      } catch {
        meta = null
      }
      let previewUrl = ''
      try {
        previewUrl = await createLocalImagePreview(file, { maxWidth: 160, maxHeight: 160, rejectOnDecodeError: true })
      } catch {
        nextRejected.push({ id: `rej-${++previewCounter}`, name: file.name, reason: DECODE_FAILURE })
        continue
      }
      if (sequence !== stageSequence) {
        if (previewUrl.startsWith('blob:')) URL.revokeObjectURL(previewUrl)
        break
      }
      room -= 1
      added.push({
        id: `new-${++previewCounter}`,
        file,
        previewUrl,
        latitude: Number.isFinite(Number(meta?.latitude)) && meta?.latitude !== null ? Number(meta.latitude) : null,
        longitude: Number.isFinite(Number(meta?.longitude)) && meta?.longitude !== null ? Number(meta.longitude) : null,
        date: meta?.date || '',
      })
    }
    if (sequence !== stageSequence) return
    if (added.length) newPhotos.value = [...newPhotos.value, ...added]
    if (nextRejected.length) rejectedFiles.value = [...rejectedFiles.value, ...nextRejected]
  } finally {
    if (sequence === stageSequence) staging.value = false
  }
}

function onPhotosPicked(event) {
  const files = Array.from(event.target.files || [])
  event.target.value = ''
  stagePhotos(files)
}

function syncPhotoLocation() {
  const first = firstGpsPhoto.value
  if (showLocationOption.value && useFirstPhotoLocation.value && first) {
    if (appliedPhotoId !== first.id) {
      appliedPhotoId = first.id
      applyPoint({ latitude: first.latitude, longitude: first.longitude })
    }
    return
  }
  if (appliedPhotoId) {
    appliedPhotoId = ''
    geocodeSequence += 1
    form.latitude = null
    form.longitude = null
    if (!namesEdited.value) Object.assign(form, originalNames)
  }
}

watch([showLocationOption, useFirstPhotoLocation, () => firstGpsPhoto.value?.id], syncPhotoLocation)

function onMapPoint(point) {
  appliedPhotoId = ''
  useFirstPhotoLocation.value = false
  applyPoint(point)
}

function removeNewPhoto(id) {
  const target = newPhotos.value.find((item) => item.id === id)
  if (target) URL.revokeObjectURL(target.previewUrl)
  newPhotos.value = newPhotos.value.filter((item) => item.id !== id)
}

async function deletePhoto(media) {
  if (busy.value || !window.confirm('이 사진을 삭제할까요? 되돌릴 수 없습니다.')) return
  busy.value = true
  error.value = ''
  try {
    await deleteTravelMedia(media.id)
    photos.value = photos.value.filter((item) => item.id !== media.id)
    mediaChanged.value = true
  } catch (deleteError) {
    error.value = toImportErrorMessage(deleteError)
  } finally {
    busy.value = false
  }
}

function buildPayload() {
  const memo = form.memo.trim()
  return {
    memoryDate: form.memoryDate,
    memoryTime: form.memoryTime || null,
    category: form.category.trim() || '장소',
    title: form.title.trim().slice(0, IMPORT_LIMITS.titleMax),
    country: form.country.trim() || null,
    region: form.region.trim() || null,
    placeName: form.placeName.trim() || null,
    latitude: hasLocation.value ? form.latitude : null,
    longitude: hasLocation.value ? form.longitude : null,
    sharedWithCommunity: Boolean(record.value?.sharedWithCommunity),
    memo: memo ? memo.slice(0, IMPORT_LIMITS.memoMax) : null,
  }
}

async function save() {
  if (!canSave.value) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    if (formDirty.value && !recordSaved.value) {
      await updateTravelMemory(record.value.id, buildPayload())
      recordSaved.value = true
      recordWasPut.value = true
      snapshot = JSON.stringify(form)
      formDirty.value = false
    }
    let addedPhotos = 0
    if (newPhotos.value.length) {
      addedPhotos = newPhotos.value.length
      Object.assign(uploadState, { phase: 'preparing', current: 0, total: addedPhotos })
      try {
        await uploadTravelMemoryMedia(record.value.id, newPhotos.value.map((item) => item.file), '', {
          onProgress: (progress) => Object.assign(uploadState, {
            phase: progress?.phase || '', current: progress?.current ?? 0, total: progress?.total ?? 0,
          }),
        })
      } catch (uploadError) {
        mediaChanged.value = true
        const suffix = recordWasPut.value ? '기록은 저장되었습니다. 다시 저장하면 사진만 올립니다.' : '다시 저장하면 사진만 올립니다.'
        error.value = `${toImportErrorMessage(uploadError)} ${suffix}`
        return
      } finally {
        Object.assign(uploadState, { phase: '', current: 0, total: 0 })
      }
      revokeNewPhotos()
      newPhotos.value = []
    }
    emit('saved', { planId: props.planId, addedPhotos, title: form.title.trim(), recordChanged: recordWasPut.value })
  } catch (saveError) {
    error.value = toImportErrorMessage(saveError)
  } finally {
    busy.value = false
  }
}

async function removeRecord() {
  if (busy.value || !record.value) return
  if (!window.confirm(`“${record.value.title || '이 기록'}” 기록과 첨부 사진을 삭제할까요? 되돌릴 수 없습니다.`)) return
  busy.value = true
  error.value = ''
  try {
    await deleteTravelMemory(record.value.id)
    emit('deleted', { planId: props.planId })
  } catch (deleteError) {
    error.value = toImportErrorMessage(deleteError)
  } finally {
    busy.value = false
  }
}

function requestClose() {
  if (busy.value) return
  if (dirty.value && !window.confirm('변경한 내용을 저장하지 않고 닫을까요?')) return
  if (mediaChanged.value || recordSaved.value) emit('saved', { planId: props.planId, addedPhotos: 0, title: form.title.trim(), recordChanged: recordWasPut.value })
  else emit('close')
}
</script>

<template>
  <RecordMapDialog :open="open" title="기록 수정" :busy="busy" class="travel-record-edit-dialog" @close="requestClose">
    <p v-if="loading" class="travel-edit-note" role="status">기록을 불러오는 중입니다.</p>
    <p v-if="error" class="feedback feedback--error" role="alert">{{ error }}</p>
    <form v-if="record" class="travel-edit-form" @submit.prevent="save">
      <label class="travel-edit-field">
        <span>제목</span>
        <input v-model="form.title" type="text" maxlength="120" :aria-invalid="titleProblem ? 'true' : undefined" />
      </label>
      <p v-if="titleProblem" class="travel-edit-problem">{{ titleProblem }}</p>
      <div class="travel-edit-row">
        <label class="travel-edit-field">
          <span>날짜</span>
          <input v-model="form.memoryDate" type="date" />
        </label>
        <label class="travel-edit-field">
          <span>시간 · 선택</span>
          <input v-model="form.memoryTime" type="time" />
        </label>
        <label class="travel-edit-field">
          <span>분류</span>
          <input v-model="form.category" type="text" maxlength="80" list="travel-edit-categories" />
          <datalist id="travel-edit-categories"><option v-for="item in categoryOptions" :key="item" :value="item" /></datalist>
        </label>
      </div>
      <section class="travel-edit-photos" aria-label="사진">
        <div class="travel-edit-photos__head">
          <h3>사진 {{ photos.length + newPhotos.length }}장</h3>
          <button type="button" class="button button--secondary" :disabled="busy" @click="openPicker">사진 추가</button>
          <input
            ref="pickerInput"
            class="travel-edit-picker"
            type="file"
            multiple
            accept="image/*"
            tabindex="-1"
            aria-label="사진 파일 선택"
            @change="onPhotosPicked"
          />
        </div>
        <ul class="travel-edit-photo-list">
          <li v-for="media in photos" :key="media.id" class="travel-edit-photo">
            <img :src="media.contentUrl" :alt="media.originalFileName || '사진'" width="72" height="72" decoding="async" loading="lazy" />
            <button type="button" class="travel-edit-photo__remove" :aria-label="`${media.originalFileName || '사진'} 삭제`" :disabled="busy" @click="deletePhoto(media)">×</button>
          </li>
          <li v-for="item in newPhotos" :key="item.id" class="travel-edit-photo is-new">
            <img :src="item.previewUrl" :alt="item.file.name" width="72" height="72" decoding="async" />
            <button type="button" class="travel-edit-photo__remove" :aria-label="`${item.file.name} 빼기`" :disabled="busy" @click="removeNewPhoto(item.id)">×</button>
          </li>
        </ul>
        <p v-if="staging" class="travel-edit-note" role="status">사진 정보 읽는 중…</p>
        <ul v-if="photoNotes.length" class="travel-edit-photo-notes">
          <li v-for="note in photoNotes" :key="note.id"><b>{{ note.name }}</b> · {{ note.text }}</li>
        </ul>
        <label v-if="showLocationOption" class="travel-edit-check">
          <input v-model="useFirstPhotoLocation" type="checkbox" :disabled="busy" />
          <span>첫 사진 위치를 기록 위치로 사용</span>
        </label>
        <section v-if="rejectedFiles.length" class="travel-edit-rejected" aria-label="추가하지 못한 파일">
          <div class="travel-edit-rejected__head">
            <strong>추가하지 못한 파일</strong>
            <button type="button" class="button button--ghost" @click="rejectedFiles = []">지우기</button>
          </div>
          <ul>
            <li v-for="item in rejectedFiles" :key="item.id"><b>{{ item.name }}</b> — {{ item.reason }}</li>
          </ul>
        </section>
        <p v-if="uploadText" class="travel-edit-note" role="status">{{ uploadText }}</p>
      </section>
      <p class="travel-edit-location">
        <strong>위치:</strong> {{ locationText }}
        <small v-if="geocoding">위치 이름 확인 중…</small>
        <small v-else>지도를 눌러 바꾸거나 핀을 끌어 옮기세요.</small>
      </p>
      <div class="travel-edit-map">
        <TravelMapPanel
          v-if="mapReady"
          class="travel-edit-map-panel"
          :markers="[]"
          :selected-point="selectedPoint"
          :draggable-selected-point="!busy"
          :enable-pick-location="!busy"
          :location-search="searchTravelLocations"
          :view-key="`${recordId}-${loading ? 'loading' : 'ready'}`"
          :auto-fit="true"
          :zoom-animation="false"
          initial-map-size="compact"
          hint-title="위치 수정"
          hint-text="지도를 누르면 위치가 바뀝니다."
          @pick-location="onMapPoint"
          @move-selected-point="onMapPoint"
        />
      </div>
      <details class="travel-edit-advanced">
        <summary>국가·지역·장소명 직접 수정</summary>
        <div class="travel-edit-row">
          <label class="travel-edit-field"><span>국가</span><input v-model="form.country" type="text" maxlength="80" @input="namesEdited = true" /></label>
          <label class="travel-edit-field"><span>지역 / 도시</span><input v-model="form.region" type="text" maxlength="120" @input="namesEdited = true" /></label>
          <label class="travel-edit-field"><span>장소명</span><input v-model="form.placeName" type="text" maxlength="160" @input="namesEdited = true" /></label>
        </div>
      </details>
      <label class="travel-edit-field">
        <span>메모 <small>{{ form.memo.length }}/500</small></span>
        <textarea v-model="form.memo" rows="3" maxlength="500" />
      </label>
      <footer class="travel-edit-footer">
        <button type="button" class="button button--danger" :disabled="busy" @click="removeRecord">기록 삭제</button>
        <span class="travel-edit-footer__spacer" />
        <button type="button" class="button button--secondary" :disabled="busy" @click="requestClose">취소</button>
        <button type="submit" class="button button--primary" :disabled="!canSave">저장</button>
      </footer>
    </form>
  </RecordMapDialog>
</template>

<style scoped>
.travel-edit-form { display: grid; gap: 12px; min-width: 0; }
.travel-edit-field { display: grid; gap: 4px; min-width: 0; font-size: .8rem; color: var(--rm-text-soft, inherit); }
.travel-edit-field input, .travel-edit-field textarea { width: 100%; min-width: 0; box-sizing: border-box; min-height: 44px; font-size: .95rem; }
.travel-edit-row { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; }
.travel-edit-location { margin: 0; display: flex; flex-wrap: wrap; gap: 4px 10px; align-items: baseline; overflow-wrap: anywhere; }
.travel-edit-location small, .travel-edit-note { color: var(--rm-text-soft, inherit); margin: 0; }
.travel-edit-problem { margin: 0; color: var(--rm-danger-text); font-size: .8rem; }
.travel-edit-map { height: 260px; min-width: 0; }
.travel-edit-map .travel-edit-map-panel { height: 100%; padding: 0; margin: 0; gap: 6px; overflow: hidden; grid-template-rows: auto minmax(0, 1fr) !important; }
.travel-record-edit-dialog .travel-edit-map-panel :deep(.travel-map__canvas) { min-height: 0 !important; height: 100% !important; }
.travel-record-edit-dialog .travel-edit-map-panel :deep(.travel-map__stage) { min-height: 0 !important; height: 100% !important; }
.travel-record-edit-dialog .travel-edit-map-panel :deep(.travel-map__toolbar-group:has(> .travel-map__toolbar-label)) { display: none !important; }
.travel-record-edit-dialog .travel-edit-map-panel :deep(.travel-map__hint) { display: none !important; }
:global(html body .app-shell .travel-record-edit-dialog .travel-map .travel-map__toolbar .travel-map__toolbar-button:not(.is-active)) { color: var(--text) !important; -webkit-text-fill-color: var(--text) !important; background: var(--panel-bg) !important; text-shadow: none; }
.travel-edit-advanced summary { cursor: pointer; min-height: 32px; display: flex; align-items: center; font-size: .85rem; }
.travel-edit-photos { display: grid; gap: 8px; min-width: 0; }
.travel-edit-photos__head { position: relative; display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.travel-edit-photos__head h3 { margin: 0; font-size: .95rem; }
.travel-edit-photos__head .button { min-height: 44px; }
.travel-edit-picker { position: absolute; width: 1px; height: 1px; margin: -1px; padding: 0; border: 0; overflow: hidden; clip: rect(0 0 0 0); clip-path: inset(50%); white-space: nowrap; }
.travel-edit-photo-notes { display: grid; gap: 2px; margin: 0; padding: 0; list-style: none; font-size: .78rem; color: var(--rm-text-soft, inherit); }
.travel-edit-photo-notes li { list-style: none; overflow-wrap: anywhere; }
.travel-edit-check { display: inline-flex; align-items: center; gap: 8px; min-height: 44px; font-size: .85rem; cursor: pointer; }
.travel-edit-check input { width: 20px; height: 20px; margin: 0; }
.travel-edit-rejected { display: grid; gap: 6px; padding: 10px; border: 1px solid var(--rm-wanted); font-size: .82rem; min-width: 0; }
.travel-edit-rejected__head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.travel-edit-rejected ul { margin: 0; padding: 0; list-style: none; display: grid; gap: 4px; }
.travel-edit-rejected li { overflow-wrap: anywhere; list-style: none; }
.travel-edit-photo-list { display: flex; flex-wrap: wrap; gap: 10px; margin: 8px 0; padding: 0; list-style: none; }
.travel-edit-photo { position: relative; width: 72px; height: 72px; list-style: none; }
.travel-edit-photo img { width: 72px; height: 72px; object-fit: cover; border: 1px solid var(--rm-line); }
.travel-edit-photo.is-new img { border-style: dashed; border-color: var(--rm-accent); }
.travel-edit-photo__remove { position: absolute; top: -8px; right: -8px; width: 26px; height: 26px; min-height: 26px !important; min-width: 26px !important; padding: 0 !important; border-radius: 50%; border: 1px solid var(--rm-line) !important; background: var(--rm-panel) !important; color: var(--rm-text) !important; -webkit-text-fill-color: var(--rm-text) !important; cursor: pointer; }
.travel-edit-photo__remove::after { content: ''; position: absolute; inset: -9px; }
.travel-edit-footer { position: sticky; bottom: 0; z-index: 1; display: flex; flex-wrap: wrap; gap: 8px; align-items: center; margin: 0 -18px -18px; padding: 12px 18px; background: var(--rm-panel); border-top: 1px solid var(--rm-line); }
.travel-edit-footer__spacer { flex: 1 1 auto; }
.travel-edit-footer .button { min-height: 44px; }
@media (max-width: 600px) {
  .travel-edit-row { grid-template-columns: minmax(0, 1fr); }
  .travel-edit-footer { margin: 0 -12px -12px; padding: 10px 12px; }
  .travel-edit-footer .button { flex: 1 1 auto; }
}
</style>
