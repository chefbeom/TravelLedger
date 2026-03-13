<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { extractPhotoMetadata, reverseGeocode } from '../lib/photoMetadata'
import { formatDateTime, toNullableNumber, todayIso } from '../lib/uiFormat'
import TravelMapPanel from './TravelMapPanel.vue'

const pinPresetOptions = [
  { key: 'general', label: '📍 기본 핀', category: '장소', iconText: '📍' },
  { key: 'lodging', label: '🏠 숙소', category: '숙소', iconText: '🏠' },
  { key: 'food', label: '🍽 음식점', category: '음식점', iconText: '🍽' },
  { key: 'cafe', label: '☕ 카페', category: '카페', iconText: '☕' },
  { key: 'museum', label: '🏛 박물관', category: '박물관', iconText: '🏛' },
  { key: 'sightseeing', label: '📸 관광지', category: '관광지', iconText: '📸' },
  { key: 'shopping', label: '🛍 쇼핑', category: '쇼핑', iconText: '🛍' },
  { key: 'transit', label: '🚌 교통', category: '이동', iconText: '🚌' },
]

const props = defineProps({
  travelPlan: {
    type: Object,
    default: null,
  },
  categoryOptions: {
    type: Array,
    default: () => [],
  },
  isSubmitting: {
    type: Boolean,
    default: false,
  },
  activeSubmit: {
    type: String,
    default: '',
  },
  refreshKey: {
    type: Number,
    default: 0,
  },
  focusRequest: {
    type: Object,
    default: null,
  },
})

const emit = defineEmits(['save-memory', 'delete-memory', 'delete-media'])

function createDefaultForm() {
  return {
    memoryDate: todayIso(),
    memoryTime: '',
    category: '장소',
    title: '',
    country: '',
    region: '',
    placeName: '',
    latitude: '',
    longitude: '',
    sharedWithCommunity: false,
    memo: '',
  }
}

function resolvePinPreset(category) {
  const text = String(category || '').trim().toLowerCase()

  if (!text) return pinPresetOptions[0]
  if (text.includes('숙소') || text.includes('호텔') || text.includes('hotel') || text.includes('hostel')) return pinPresetOptions.find((item) => item.key === 'lodging')
  if (text.includes('음식') || text.includes('식당') || text.includes('맛집') || text.includes('food') || text.includes('restaurant')) return pinPresetOptions.find((item) => item.key === 'food')
  if (text.includes('카페') || text.includes('coffee') || text.includes('cafe')) return pinPresetOptions.find((item) => item.key === 'cafe')
  if (text.includes('박물관') || text.includes('전시') || text.includes('museum')) return pinPresetOptions.find((item) => item.key === 'museum')
  if (text.includes('관광') || text.includes('명소') || text.includes('landmark') || text.includes('sight')) return pinPresetOptions.find((item) => item.key === 'sightseeing')
  if (text.includes('쇼핑') || text.includes('shopping') || text.includes('mall')) return pinPresetOptions.find((item) => item.key === 'shopping')
  if (text.includes('이동') || text.includes('교통') || text.includes('transit') || text.includes('버스') || text.includes('지하철') || text.includes('택시')) return pinPresetOptions.find((item) => item.key === 'transit')
  return pinPresetOptions[0]
}

const form = reactive(createDefaultForm())
const editingMemoryId = ref(null)
const isEditorOpen = ref(false)
const photoFiles = ref([])
const photoCaption = ref('')
const queuedCategory = ref('장소')
const pendingPoint = ref(null)
const locationFilter = reactive({
  country: '',
  region: '',
})
const pendingLocation = reactive({
  status: 'idle',
  country: '',
  region: '',
  placeName: '',
  latitude: '',
  longitude: '',
  message: '',
})
const autofillState = reactive({
  status: 'idle',
  message: '사진을 고르면 EXIF 정보가 있을 때 날짜, 시간, GPS 좌표를 자동으로 채워줍니다.',
  fileName: '',
})

let pendingLookupToken = 0

const memoryRecords = computed(() => props.travelPlan?.memoryRecords ?? [])
const memoryMediaItems = computed(() =>
  (props.travelPlan?.mediaItems ?? []).filter((item) => item.recordType === 'MEMORY' && item.mediaType === 'PHOTO'),
)

const memoryMediaMap = computed(() => {
  const bucket = new Map()
  memoryMediaItems.value.forEach((media) => {
    const key = String(media.recordId)
    const current = bucket.get(key) ?? []
    current.push(media)
    bucket.set(key, current)
  })
  return bucket
})

const countryOptions = computed(() => {
  const values = new Set((props.travelPlan?.memoryRecords ?? []).map((item) => item.country).filter(Boolean))
  return [...values].sort((left, right) => left.localeCompare(right))
})

const regionOptions = computed(() => {
  const selectedCountry = String(locationFilter.country || '').trim()
  const values = new Set(
    memoryRecords.value
      .filter((item) => !selectedCountry || item.country === selectedCountry)
      .map((item) => item.region)
      .filter(Boolean),
  )
  return [...values].sort((left, right) => left.localeCompare(right))
})

const filteredMemoryRecords = computed(() =>
  memoryRecords.value
    .filter((item) => !locationFilter.country || item.country === locationFilter.country)
    .filter((item) => !locationFilter.region || item.region === locationFilter.region)
    .map((item) => ({
      ...item,
      photoCount: memoryMediaMap.value.get(String(item.id))?.length || 0,
    }))
    .slice()
    .sort((left, right) => {
      const leftKey = `${left.memoryDate || ''} ${left.memoryTime || '99:99'} ${String(left.id).padStart(12, '0')}`
      const rightKey = `${right.memoryDate || ''} ${right.memoryTime || '99:99'} ${String(right.id).padStart(12, '0')}`
      return rightKey.localeCompare(leftKey)
    }),
)

const photoBackedMemories = computed(() =>
  memoryRecords.value
    .map((item) => {
      const photos = (memoryMediaMap.value.get(String(item.id)) ?? [])
        .slice()
        .sort((left, right) => String(right.uploadedAt || '').localeCompare(String(left.uploadedAt || '')))

      if (!photos.length) {
        return null
      }

      return {
        ...item,
        photoCount: photos.length,
        photos,
        heroPhoto: photos[0],
        locationLabel: [item.country, item.region, item.placeName].filter(Boolean).join(' / ') || '위치 미설정',
        sortKey: `${item.memoryDate || ''} ${item.memoryTime || '99:99'} ${photos[0]?.uploadedAt || ''} ${String(item.id).padStart(12, '0')}`,
      }
    })
    .filter(Boolean)
    .sort((left, right) => right.sortKey.localeCompare(left.sortKey)),
)

const activePinPreset = computed(() => resolvePinPreset(isEditorOpen.value ? form.category : queuedCategory.value))
const editorTitle = computed(() => (editingMemoryId.value ? '여행 기록 수정' : '새 여행 기록 작성'))

const selectedPoint = computed(() => {
  if (isEditorOpen.value) {
    const latitude = Number(form.latitude)
    const longitude = Number(form.longitude)
    if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) {
      return null
    }

    return {
      latitude,
      longitude,
      iconKey: activePinPreset.value.key,
      iconText: activePinPreset.value.iconText,
      colorHex: props.travelPlan?.colorHex || '#111827',
    }
  }

  if (!pendingPoint.value) {
    return null
  }

  const preset = resolvePinPreset(queuedCategory.value)
  return {
    latitude: pendingPoint.value.latitude,
    longitude: pendingPoint.value.longitude,
    iconKey: preset.key,
    iconText: preset.iconText,
    colorHex: props.travelPlan?.colorHex || '#111827',
  }
})

const mapMarkers = computed(() =>
  memoryRecords.value
    .filter((item) => item.latitude !== null && item.latitude !== undefined && item.longitude !== null && item.longitude !== undefined)
    .map((item) => {
      const preset = resolvePinPreset(item.category)
      return {
        id: item.id,
        planId: item.planId,
        planName: item.planName,
        colorHex: item.planColorHex || props.travelPlan?.colorHex || '#3182F6',
        latitude: Number(item.latitude),
        longitude: Number(item.longitude),
        country: item.country,
        region: item.region,
        placeName: item.placeName,
        title: item.title,
        visitedDate: item.memoryDate,
        visitedTime: item.memoryTime,
        photoCount: memoryMediaMap.value.get(String(item.id))?.length || 0,
        receiptCount: 0,
        mediaItems: memoryMediaMap.value.get(String(item.id)) || [],
        iconKey: preset.key,
        iconText: preset.iconText,
      }
    }),
)

const editingPhotos = computed(() => memoryMediaMap.value.get(String(editingMemoryId.value)) ?? [])

const pendingLocationLabel = computed(() => {
  return [pendingLocation.country, pendingLocation.region, pendingLocation.placeName].filter(Boolean).join(' / ') || '좌표만 선택됨'
})

watch(
  () => props.travelPlan?.id,
  () => {
    resetForm()
    clearPendingPoint()
    isEditorOpen.value = false
    queuedCategory.value = '장소'
    locationFilter.country = ''
    locationFilter.region = ''
  },
  { immediate: true },
)

watch(
  () => props.refreshKey,
  () => {
    resetForm()
    clearPendingPoint()
    isEditorOpen.value = false
  },
)

watch(
  () => props.focusRequest?.token,
  () => {
    const targetId = props.focusRequest?.id
    if (!targetId) {
      return
    }

    const targetMemory = memoryRecords.value.find((item) => String(item.id) === String(targetId))
    if (targetMemory) {
      openMemoryForEdit(targetMemory)
    }
  },
  { immediate: true },
)

watch(
  () => locationFilter.country,
  () => {
    if (!regionOptions.value.includes(locationFilter.region)) {
      locationFilter.region = ''
    }
  },
)

function resetPendingFiles() {
  photoFiles.value = []
  photoCaption.value = ''
  autofillState.status = 'idle'
  autofillState.fileName = ''
  autofillState.message = '사진을 고르면 EXIF 정보가 있을 때 날짜, 시간, GPS 좌표를 자동으로 채워줍니다.'
}

function resetForm() {
  Object.assign(form, createDefaultForm())
  editingMemoryId.value = null
  resetPendingFiles()
}

function clearPendingPoint() {
  pendingLookupToken += 1
  pendingPoint.value = null
  pendingLocation.status = 'idle'
  pendingLocation.country = ''
  pendingLocation.region = ''
  pendingLocation.placeName = ''
  pendingLocation.latitude = ''
  pendingLocation.longitude = ''
  pendingLocation.message = ''
}

function fillForm(memory) {
  editingMemoryId.value = memory.id
  form.memoryDate = memory.memoryDate || todayIso()
  form.memoryTime = memory.memoryTime || ''
  form.category = memory.category || '장소'
  form.title = memory.title || ''
  form.country = memory.country || ''
  form.region = memory.region || ''
  form.placeName = memory.placeName || ''
  form.latitude = memory.latitude != null ? String(memory.latitude) : ''
  form.longitude = memory.longitude != null ? String(memory.longitude) : ''
  form.sharedWithCommunity = Boolean(memory.sharedWithCommunity)
  form.memo = memory.memo || ''
  queuedCategory.value = form.category
  resetPendingFiles()
}

function buildPayload() {
  return {
    memoryDate: form.memoryDate,
    memoryTime: form.memoryTime || null,
    category: form.category.trim(),
    title: form.title.trim(),
    country: form.country.trim() || null,
    region: form.region.trim() || null,
    placeName: form.placeName.trim() || null,
    latitude: toNullableNumber(form.latitude),
    longitude: toNullableNumber(form.longitude),
    sharedWithCommunity: Boolean(form.sharedWithCommunity),
    memo: form.memo.trim() || null,
  }
}

function applyPendingLocationToForm() {
  if (!pendingPoint.value) {
    return
  }

  form.latitude = String(pendingPoint.value.latitude)
  form.longitude = String(pendingPoint.value.longitude)
  form.country = pendingLocation.country || ''
  form.region = pendingLocation.region || ''
  form.placeName = pendingLocation.placeName || ''

  if (!form.title && pendingLocation.placeName) {
    form.title = pendingLocation.placeName
  }
}

function openBlankEditor() {
  const nextCategory = queuedCategory.value || '장소'
  resetForm()
  form.category = nextCategory
  applyPendingLocationToForm()
  isEditorOpen.value = true
}

function closeEditor() {
  resetForm()
  isEditorOpen.value = false
}

function openMemoryForEdit(memory) {
  clearPendingPoint()
  fillForm(memory)
  isEditorOpen.value = true
}

function applyPinPreset(preset) {
  queuedCategory.value = preset.category
  if (isEditorOpen.value) {
    form.category = preset.category
  }
}

async function populatePendingLocation(point) {
  pendingLocation.status = 'loading'
  pendingLocation.country = ''
  pendingLocation.region = ''
  pendingLocation.placeName = ''
  pendingLocation.latitude = String(point.latitude)
  pendingLocation.longitude = String(point.longitude)
  pendingLocation.message = '선택한 좌표의 나라와 도시 정보를 불러오는 중입니다.'

  const currentToken = ++pendingLookupToken

  try {
    const location = await reverseGeocode(point.latitude, point.longitude)
    if (currentToken !== pendingLookupToken) {
      return
    }

    pendingLocation.status = 'ready'
    pendingLocation.country = location.country || ''
    pendingLocation.region = location.region || ''
    pendingLocation.placeName = location.placeName || ''
    pendingLocation.message =
      pendingLocation.country || pendingLocation.region || pendingLocation.placeName
        ? '지도의 핀을 한 번 더 누르면 이 위치로 기록 입력 모달이 열립니다.'
        : '좌표는 선택되었습니다. 핀을 한 번 더 누르면 기록 입력 모달이 열립니다.'
  } catch {
    if (currentToken !== pendingLookupToken) {
      return
    }

    pendingLocation.status = 'error'
    pendingLocation.message = '위치 이름을 가져오지 못했습니다. 좌표는 유지되며 핀을 한 번 더 눌러 기록을 작성할 수 있습니다.'
  }
}

async function handlePhotoSelection(event) {
  const files = [...(event.target.files ?? [])]
  photoFiles.value = files

  if (!files.length) {
    resetPendingFiles()
    return
  }

  const fieldLabels = {
    date: '날짜',
    time: '시간',
    latitude: '위도',
    longitude: '경도',
    country: '국가',
    region: '지역',
    place: '장소',
  }

  try {
    const metadata = await extractPhotoMetadata(files[0])
    autofillState.fileName = files[0].name

    if (!metadata) {
      autofillState.status = 'manual'
      autofillState.message = '읽을 수 있는 EXIF 정보가 없어 위치와 날짜를 직접 입력해야 합니다.'
      return
    }

    const applied = []
    if (metadata.date) {
      form.memoryDate = metadata.date
      applied.push(fieldLabels.date)
    }
    if (metadata.time) {
      form.memoryTime = metadata.time
      applied.push(fieldLabels.time)
    }
    if (metadata.latitude !== null && metadata.latitude !== undefined) {
      form.latitude = String(metadata.latitude)
      applied.push(fieldLabels.latitude)
    }
    if (metadata.longitude !== null && metadata.longitude !== undefined) {
      form.longitude = String(metadata.longitude)
      applied.push(fieldLabels.longitude)
    }
    if (metadata.country && !form.country.trim()) {
      form.country = metadata.country
      applied.push(fieldLabels.country)
    }
    if (metadata.region && !form.region.trim()) {
      form.region = metadata.region
      applied.push(fieldLabels.region)
    }
    if (metadata.placeName && !form.placeName.trim()) {
      form.placeName = metadata.placeName
      applied.push(fieldLabels.place)
    }

    autofillState.status = applied.length ? 'filled' : 'manual'
    autofillState.message = applied.length
      ? `자동 입력된 항목: ${applied.join(', ')}`
      : '사진 메타데이터에 위치 정보가 충분하지 않아 일부 항목은 직접 입력해야 합니다.'
  } catch (error) {
    autofillState.status = 'manual'
    autofillState.fileName = files[0].name
    autofillState.message = error?.message || '사진 메타데이터를 읽지 못했습니다.'
  }
}

function handlePickLocation(point) {
  if (!point) {
    return
  }

  isEditorOpen.value = false
  resetForm()
  pendingPoint.value = {
    latitude: point.latitude,
    longitude: point.longitude,
  }
  populatePendingLocation(point)
}

function handleSelectSelectedPoint() {
  if (!pendingPoint.value || isEditorOpen.value) {
    return
  }

  openBlankEditor()
}

function handleMoveSelectedPoint(point) {
  if (!point) {
    return
  }

  if (isEditorOpen.value) {
    form.latitude = String(point.latitude)
    form.longitude = String(point.longitude)
    return
  }

  pendingPoint.value = {
    latitude: point.latitude,
    longitude: point.longitude,
  }
  populatePendingLocation(point)
}

function handleMoveMarker(payload) {
  const targetMemory = memoryRecords.value.find((item) => String(item.id) === String(payload?.marker?.id))
  if (!targetMemory || !payload?.point) {
    return
  }

  clearPendingPoint()
  fillForm(targetMemory)
  form.latitude = String(payload.point.latitude)
  form.longitude = String(payload.point.longitude)
  isEditorOpen.value = true
}

function handleSelectMarker(marker) {
  const targetMemory = memoryRecords.value.find((item) => String(item.id) === String(marker?.id))
  if (targetMemory) {
    openMemoryForEdit(targetMemory)
  }
}

function handleWindowKeydown(event) {
  if (event.key === 'Escape' && isEditorOpen.value) {
    closeEditor()
  }
}

onMounted(() => {
  window.addEventListener('keydown', handleWindowKeydown)
})

onBeforeUnmount(() => {
  pendingLookupToken += 1
  window.removeEventListener('keydown', handleWindowKeydown)
})

function submitMemory() {
  if (!props.travelPlan) {
    return
  }

  queuedCategory.value = form.category.trim() || queuedCategory.value
  emit('save-memory', {
    id: editingMemoryId.value,
    payload: buildPayload(),
    files: photoFiles.value,
    caption: photoCaption.value.trim(),
  })
}
</script>

<template>
  <div v-if="travelPlan" class="workspace-stack">
    <section v-if="photoBackedMemories.length" class="panel">
      <div class="panel__header">
        <div>
          <h2>사진 재사용 빠른 열기</h2>
          <p>이미 사진이 달린 기록을 다시 열어 기존 사진은 유지한 채 메모와 새 사진만 이어서 추가할 수 있습니다.</p>
        </div>
        <span class="panel__badge">{{ photoBackedMemories.length }}개 기록</span>
      </div>

      <div class="travel-media-grid travel-media-grid--gallery">
        <article v-for="memory in photoBackedMemories" :key="`photo-memory-${memory.id}`" class="travel-media-card">
          <img v-if="memory.heroPhoto?.contentUrl" :src="memory.heroPhoto.contentUrl" :alt="memory.heroPhoto.originalFileName" class="travel-media-thumb" />
          <div v-else class="travel-media-thumb travel-media-thumb--receipt">사진 없음</div>
          <div class="travel-media-copy">
            <div class="travel-media-tags">
              <span class="chip chip--neutral">{{ memory.category || '기록' }}</span>
              <span class="chip chip--neutral">사진 {{ memory.photoCount }}장</span>
            </div>
            <strong>{{ memory.title || memory.placeName || '제목 없는 기록' }}</strong>
            <small>{{ formatDateTime(memory.memoryDate, memory.memoryTime) }}</small>
            <small>{{ memory.locationLabel }}</small>
            <small>{{ memory.memo || '기존 사진은 유지하고 새 사진과 메모만 이어서 추가할 수 있습니다.' }}</small>
          </div>
          <div class="travel-media-actions">
            <button class="button button--primary" @click="openMemoryForEdit(memory)">기록 편집</button>
            <a v-if="memory.heroPhoto?.contentUrl" class="button button--ghost" :href="memory.heroPhoto.contentUrl" target="_blank" rel="noreferrer">대표 사진 보기</a>
          </div>
        </article>
      </div>
    </section>

    <section class="panel panel--map-fill travel-memory-map-panel">
      <div class="panel__header">
        <div>
          <h2>기록 지도</h2>
          <p>지도를 누르면 먼저 임시 핀이 생기고, 그 핀을 한 번 더 눌렀을 때만 기록 입력 모달이 열립니다.</p>
        </div>
        <div class="topbar__actions">
          <span class="panel__badge">{{ mapMarkers.length }}개 핀</span>
          <button class="button button--primary" type="button" @click="openBlankEditor()">새 기록</button>
        </div>
      </div>

      <TravelMapPanel
        :markers="mapMarkers"
        :selected-point="selectedPoint"
        :enable-pick-location="true"
        :enable-draw-route="false"
        :draggable-markers="true"
        :draggable-selected-point="true"
        :view-key="travelPlan.id || 'memory-map'"
        initial-map-size="expanded"
        :auto-fit="false"
        hint-title="지도 클릭으로 핀 먼저 만들기"
        hint-text="지도 클릭은 임시 핀만 만들고, 임시 핀을 다시 눌렀을 때 기록 입력 모달이 열립니다. 기존 저장 핀 클릭이나 드래그는 바로 수정으로 이어집니다."
        @pick-location="handlePickLocation"
        @select-selected-point="handleSelectSelectedPoint"
        @move-selected-point="handleMoveSelectedPoint"
        @move-marker="handleMoveMarker"
        @select-marker="handleSelectMarker"
      >
        <template #toolbar>
          <div class="travel-map__toolbar-group travel-map__toolbar-group--wrap">
            <span class="travel-map__toolbar-label">핀 종류</span>
            <button
              v-for="preset in pinPresetOptions"
              :key="preset.key"
              class="travel-map__toolbar-button"
              :class="{ 'is-active': activePinPreset.key === preset.key }"
              type="button"
              @click="applyPinPreset(preset)"
            >
              {{ preset.label }}
            </button>
          </div>
        </template>
      </TravelMapPanel>

      <div v-if="pendingPoint" class="travel-memory-pending">
        <div class="travel-memory-pending__header">
          <div>
            <strong>{{ pendingLocation.placeName || '새 핀 준비됨' }}</strong>
            <p>{{ pendingLocation.message }}</p>
          </div>
          <span class="chip chip--neutral">{{ queuedCategory }}</span>
        </div>

        <div class="travel-memory-pending__grid">
          <div>
            <span>국가</span>
            <strong>{{ pendingLocation.country || '-' }}</strong>
          </div>
          <div>
            <span>도시 / 지역</span>
            <strong>{{ pendingLocation.region || '-' }}</strong>
          </div>
          <div>
            <span>장소명</span>
            <strong>{{ pendingLocation.placeName || '-' }}</strong>
          </div>
          <div>
            <span>좌표</span>
            <strong>{{ pendingLocation.latitude || '-' }}, {{ pendingLocation.longitude || '-' }}</strong>
          </div>
        </div>

        <p class="travel-memory-pending__caption">현재 선택 위치: {{ pendingLocationLabel }}</p>
      </div>
    </section>

    <section class="panel">
      <div class="panel__header">
        <div>
          <h2>저장한 여행 기록</h2>
          <p>국가와 지역으로 걸러보고, 저장된 기록을 다시 열어 메모와 사진, 공유 여부, 좌표까지 이어서 수정할 수 있습니다.</p>
        </div>
        <span class="panel__badge">{{ filteredMemoryRecords.length }}건</span>
      </div>

      <div class="travel-location-filter">
        <label class="field">
          <span class="field__label">국가 필터</span>
          <select v-model="locationFilter.country">
            <option value="">전체 국가</option>
            <option v-for="option in countryOptions" :key="option" :value="option">{{ option }}</option>
          </select>
        </label>
        <label class="field">
          <span class="field__label">지역 필터</span>
          <select v-model="locationFilter.region">
            <option value="">전체 지역</option>
            <option v-for="option in regionOptions" :key="option" :value="option">{{ option }}</option>
          </select>
        </label>
      </div>

      <div class="sheet-table-wrap">
        <table class="sheet-table">
          <thead>
            <tr>
              <th>날짜</th>
              <th>분류</th>
              <th>제목</th>
              <th>위치</th>
              <th>사진</th>
              <th>공유</th>
              <th>작업</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="memory in filteredMemoryRecords" :key="memory.id">
              <td>{{ formatDateTime(memory.memoryDate, memory.memoryTime) }}</td>
              <td>{{ memory.category }}</td>
              <td>{{ memory.title }}</td>
              <td>{{ [memory.country, memory.region, memory.placeName].filter(Boolean).join(' / ') || '-' }}</td>
              <td>{{ memory.photoCount }}</td>
              <td>{{ memory.sharedWithCommunity ? '공유' : '비공개' }}</td>
              <td class="sheet-table__actions">
                <button class="button button--ghost" @click="openMemoryForEdit(memory)">수정</button>
                <button class="button button--danger" @click="emit('delete-memory', memory)">삭제</button>
              </td>
            </tr>
            <tr v-if="!filteredMemoryRecords.length">
              <td colspan="7" class="sheet-table__empty">이 여행에 등록된 기록이 아직 없습니다.</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <div v-if="isEditorOpen" class="travel-modal" @click.self="closeEditor">
      <div class="travel-modal__dialog">
        <div class="travel-modal__header">
          <div>
            <h2>{{ editorTitle }}</h2>
            <p>지도의 핀 좌표를 기준으로 장소 정보와 메모, 사진을 입력합니다. ESC를 누르면 저장하지 않고 닫을 수 있습니다.</p>
          </div>
          <button class="button button--ghost" type="button" @click="closeEditor">닫기</button>
        </div>

        <div class="travel-modal__body">
          <div class="travel-form-grid">
            <label class="field">
              <span class="field__label">날짜</span>
              <input v-model="form.memoryDate" type="date" />
            </label>
            <label class="field">
              <span class="field__label">시간</span>
              <input v-model="form.memoryTime" type="time" />
            </label>
            <label class="field">
              <span class="field__label">분류</span>
              <input v-model="form.category" list="memory-category-options" type="text" placeholder="숙소, 음식점, 박물관, 관광지" />
            </label>
            <label class="field field--full">
              <span class="field__label">제목</span>
              <input v-model="form.title" type="text" placeholder="이 장소에서 무엇을 했는지 적어보세요." />
            </label>
            <label class="field">
              <span class="field__label">국가</span>
              <input v-model="form.country" list="memory-country-options" type="text" placeholder="일본" />
            </label>
            <label class="field">
              <span class="field__label">지역 / 도시</span>
              <input v-model="form.region" list="memory-region-options" type="text" placeholder="오사카" />
            </label>
            <label class="field field--full">
              <span class="field__label">장소명</span>
              <input v-model="form.placeName" type="text" placeholder="우메다 스카이빌딩, 교토역, 서울역" />
            </label>
            <label class="field">
              <span class="field__label">위도</span>
              <input v-model="form.latitude" type="number" step="0.0000001" placeholder="37.5547" />
            </label>
            <label class="field">
              <span class="field__label">경도</span>
              <input v-model="form.longitude" type="number" step="0.0000001" placeholder="126.9706" />
            </label>
            <label class="checkbox-row field--full">
              <input v-model="form.sharedWithCommunity" type="checkbox" />
              <span>이 기록을 커뮤니티 지도 월드에 공유하기</span>
            </label>
            <label class="field field--full">
              <span class="field__label">메모</span>
              <textarea v-model="form.memo" rows="4" placeholder="1~2줄 정도로 장소, 이동, 인상 등을 적어보세요." />
            </label>
          </div>

          <datalist id="memory-category-options">
            <option v-for="option in categoryOptions" :key="option" :value="option" />
          </datalist>
          <datalist id="memory-country-options">
            <option v-for="option in countryOptions" :key="option" :value="option" />
          </datalist>
          <datalist id="memory-region-options">
            <option v-for="option in regionOptions" :key="option" :value="option" />
          </datalist>

          <div class="travel-form-grid travel-form-grid--compact">
            <label class="field field--full">
              <span class="field__label">사진 업로드</span>
              <input accept="image/*" multiple type="file" @change="handlePhotoSelection" />
            </label>
            <label class="field field--full">
              <span class="field__label">사진 설명</span>
              <input v-model="photoCaption" type="text" placeholder="업로드할 사진에 붙일 짧은 설명" />
            </label>
          </div>

          <p
            class="travel-autofill-note"
            :class="{
              'travel-autofill-note--filled': autofillState.status === 'filled',
              'travel-autofill-note--manual': autofillState.status === 'manual',
            }"
          >
            <strong v-if="autofillState.fileName">{{ autofillState.fileName }}</strong>
            <span>{{ autofillState.message }}</span>
          </p>

          <div v-if="editingPhotos.length" class="travel-media-grid">
            <article v-for="media in editingPhotos" :key="media.id" class="travel-media-card">
              <img :src="media.contentUrl" :alt="media.originalFileName" class="travel-media-thumb" />
              <div class="travel-media-copy">
                <strong>{{ media.caption || media.originalFileName }}</strong>
                <small>{{ [media.country, media.region, media.placeName].filter(Boolean).join(' / ') || '-' }}</small>
              </div>
              <div class="travel-media-actions">
                <a class="button button--ghost" :href="media.contentUrl" target="_blank" rel="noreferrer">열기</a>
                <button class="button button--danger" @click="emit('delete-media', media)">삭제</button>
              </div>
            </article>
          </div>
        </div>

        <div class="travel-modal__footer">
          <button class="button button--ghost" type="button" @click="closeEditor">취소</button>
          <button class="button button--primary" :disabled="isSubmitting" @click="submitMemory">
            {{ isSubmitting && activeSubmit === 'memory' ? '저장 중...' : editingMemoryId ? '기록 수정' : '기록 저장' }}
          </button>
        </div>
      </div>
    </div>
  </div>

  <section v-else class="panel">
    <p class="panel__empty">먼저 여행을 만들거나 선택해 주세요.</p>
  </section>
</template>