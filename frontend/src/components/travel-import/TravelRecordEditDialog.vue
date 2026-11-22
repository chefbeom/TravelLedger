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
import { reverseGeocode } from '../../lib/photoMetadata'
import { IMPORT_CATEGORIES, IMPORT_LIMITS, toImportErrorMessage } from '../../lib/travelImport'
import { searchTravelLocations } from '../../lib/useTravelImport'

const LEAVE_GUARD_EVENT = 'calen-route-leave-guard'

const props = defineProps({
  open: { type: Boolean, default: false },
  planId: { type: [String, Number], default: '' },
  recordId: { type: [String, Number], default: '' },
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
const mapReady = ref(false)
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
const canSave = computed(() => !busy.value && !loading.value && Boolean(record.value) && !titleProblem.value && Boolean(form.memoryDate))
const dirty = computed(() => formDirty.value || newPhotos.value.length > 0)

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
    snapshot = JSON.stringify(form)
    formDirty.value = false
    recordSaved.value = false
  } catch (loadError) {
    if (sequence === loadSequence) error.value = toImportErrorMessage(loadError)
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}

watch(() => [props.open, props.planId, props.recordId], ([open]) => {
  if (open) load()
}, { immediate: true })

watch(() => JSON.stringify(form), (value) => {
  if (!record.value || value === snapshot) return
  formDirty.value = true
  recordSaved.value = false
})

watch(dirty, (active) => {
  window.dispatchEvent(new CustomEvent(LEAVE_GUARD_EVENT, { detail: { active, message: '저장하지 않은 기록 수정 내용이 있습니다. 나갈까요?' } }))
})

requestAnimationFrame(() => requestAnimationFrame(() => { mapReady.value = true }))

onBeforeUnmount(() => {
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

function onPhotosPicked(event) {
  const files = Array.from(event.target.files || [])
  event.target.value = ''
  const accepted = files.filter((file) => /^image\//i.test(file.type) || /\.(jpe?g|png|webp|gif|bmp)$/i.test(file.name))
  const tooBig = accepted.find((file) => file.size > IMPORT_LIMITS.maxPhotoBytes)
  if (tooBig) {
    error.value = `${tooBig.name}: 사진은 한 장당 15MB 이하만 올릴 수 있습니다.`
    return
  }
  error.value = files.length !== accepted.length ? '사진 파일만 추가할 수 있습니다.' : ''
  newPhotos.value = [...newPhotos.value, ...accepted.map((file) => ({ id: `new-${++previewCounter}`, file, previewUrl: URL.createObjectURL(file) }))]
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
    if (!recordSaved.value) {
      await updateTravelMemory(record.value.id, buildPayload())
      recordSaved.value = true
      snapshot = JSON.stringify(form)
      formDirty.value = false
    }
    if (newPhotos.value.length) {
      try {
        await uploadTravelMemoryMedia(record.value.id, newPhotos.value.map((item) => item.file))
      } catch (uploadError) {
        mediaChanged.value = true
        error.value = `${toImportErrorMessage(uploadError)} 기록은 저장되었습니다. 다시 저장하면 사진만 올립니다.`
        return
      }
      revokeNewPhotos()
      newPhotos.value = []
    }
    emit('saved', { planId: props.planId })
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
  if (mediaChanged.value || recordSaved.value) emit('saved', { planId: props.planId })
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
          @pick-location="applyPoint"
          @move-selected-point="applyPoint"
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
      <section class="travel-edit-photos" aria-label="사진">
        <p class="travel-edit-note">사진 {{ photos.length + newPhotos.length }}장</p>
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
        <label class="travel-edit-field">
          <span>사진 추가</span>
          <input type="file" multiple accept="image/jpeg,image/png,image/webp,image/gif,image/bmp" @change="onPhotosPicked" />
        </label>
      </section>
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
