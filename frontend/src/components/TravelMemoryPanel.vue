<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { buildThumbnailUrl, createLocalImagePreview } from '../lib/mediaPreview'
import { extractPhotoMetadata, reverseGeocode } from '../lib/photoMetadata'
import { formatDate, formatDateTime, toIsoDate, toNullableNumber, todayIso } from '../lib/uiFormat'
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

const activeDayDate = ref('')

function resolveDefaultMemoryDate() {
  return activeDayDate.value || props.travelPlan?.startDate || todayIso()
}

function createDefaultForm() {
  return {
    memoryDate: resolveDefaultMemoryDate(),
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
const multiPhotoUploadEnabled = ref(false)
const multiPhotoDrafts = ref([])
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

const defaultAutofillMessage = '사진을 고르면 EXIF 정보가 있을 때 날짜, 시간, GPS 좌표를 자동으로 채워줍니다.'
autofillState.message = defaultAutofillMessage

let pendingLookupToken = 0

const memoryRecords = computed(() => props.travelPlan?.memoryRecords ?? [])
const memoryMediaItems = computed(() =>
  (props.travelPlan?.mediaItems ?? []).filter((item) => item.recordType === 'MEMORY' && item.mediaType === 'PHOTO'),
)
const tripDays = computed(() => {
  const startText = props.travelPlan?.startDate || todayIso()
  const endText = props.travelPlan?.endDate || startText
  const start = new Date(`${startText}T00:00:00`)
  const end = new Date(`${endText}T00:00:00`)

  if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime()) || end < start) {
    return [{ index: 1, date: startText, label: '1일차' }]
  }

  const days = []
  const cursor = new Date(start)
  let index = 1

  while (cursor <= end) {
    days.push({
      index,
      date: toIsoDate(cursor),
      label: `${index}일차`,
    })
    cursor.setDate(cursor.getDate() + 1)
    index += 1
  }

  return days.length ? days : [{ index: 1, date: startText, label: '1일차' }]
})
const activeDay = computed(() => tripDays.value.find((day) => day.date === activeDayDate.value) || null)
const activeDayLabel = computed(() => activeDay.value?.label || '전체 일정')
const scopedMemoryRecords = computed(() =>
  memoryRecords.value.filter((item) => !activeDayDate.value || item.memoryDate === activeDayDate.value),
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
  const values = new Set(scopedMemoryRecords.value.map((item) => item.country).filter(Boolean))
  return [...values].sort((left, right) => left.localeCompare(right))
})

const regionOptions = computed(() => {
  const selectedCountry = String(locationFilter.country || '').trim()
  const values = new Set(
    scopedMemoryRecords.value
      .filter((item) => !selectedCountry || item.country === selectedCountry)
      .map((item) => item.region)
      .filter(Boolean),
  )
  return [...values].sort((left, right) => left.localeCompare(right))
})

const filteredMemoryRecords = computed(() =>
  scopedMemoryRecords.value
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

const MEMORY_RECORD_PAGE_SIZE = 10
const memoryRecordPage = ref(0)
const memoryRecordPageCount = computed(() =>
  Math.max(1, Math.ceil(filteredMemoryRecords.value.length / MEMORY_RECORD_PAGE_SIZE)),
)
const pagedFilteredMemoryRecords = computed(() => {
  const start = memoryRecordPage.value * MEMORY_RECORD_PAGE_SIZE
  return filteredMemoryRecords.value.slice(start, start + MEMORY_RECORD_PAGE_SIZE)
})

const multiPhotoLoading = ref(false)
const multiPhotoLoadingCount = ref(0)
const multiPhotoSkeletonItems = computed(() =>
  Array.from({ length: Math.max(1, multiPhotoLoadingCount.value) }, (_, index) => `multi-photo-skeleton-${index}`),
)

const photoBackedMemories = computed(() =>
  scopedMemoryRecords.value
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

const photoQuickOpenSort = ref('desc')
const photoQuickOpenPage = ref(0)
const PHOTO_QUICK_OPEN_PAGE_SIZE = 5
const sortedPhotoBackedMemories = computed(() => {
  const items = photoBackedMemories.value.slice()
  if (photoQuickOpenSort.value === 'asc') {
    items.reverse()
  }
  return items
})
const photoQuickOpenPageCount = computed(() =>
  Math.max(1, Math.ceil(sortedPhotoBackedMemories.value.length / PHOTO_QUICK_OPEN_PAGE_SIZE)),
)
const pagedPhotoBackedMemories = computed(() => {
  const start = photoQuickOpenPage.value * PHOTO_QUICK_OPEN_PAGE_SIZE
  return sortedPhotoBackedMemories.value.slice(start, start + PHOTO_QUICK_OPEN_PAGE_SIZE)
})

const isMultiPhotoMode = computed(() => multiPhotoUploadEnabled.value && !editingMemoryId.value)
const hasMultiPhotoDrafts = computed(() => isMultiPhotoMode.value && multiPhotoDrafts.value.length > 0)

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
  filteredMemoryRecords.value
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
    activeDayDate.value = ''
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
  () => [activeDayDate.value, locationFilter.country, locationFilter.region],
  () => {
    memoryRecordPage.value = 0
  },
)

watch(filteredMemoryRecords, (items) => {
  const maxPage = Math.max(0, Math.ceil(items.length / MEMORY_RECORD_PAGE_SIZE) - 1)
  if (memoryRecordPage.value > maxPage) {
    memoryRecordPage.value = maxPage
  }
})

watch(
  () => [photoQuickOpenSort.value, scopedMemoryRecords.value.length],
  () => {
    photoQuickOpenPage.value = 0
  },
)

watch(sortedPhotoBackedMemories, (items) => {
  const maxPage = Math.max(0, Math.ceil(items.length / PHOTO_QUICK_OPEN_PAGE_SIZE) - 1)
  if (photoQuickOpenPage.value > maxPage) {
    photoQuickOpenPage.value = maxPage
  }
})

watch(
  tripDays,
  (days) => {
    if (activeDayDate.value && !days.some((day) => day.date === activeDayDate.value)) {
      activeDayDate.value = ''
    }
  },
  { deep: true },
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

watch(
  countryOptions,
  (options) => {
    if (locationFilter.country && !options.includes(locationFilter.country)) {
      locationFilter.country = ''
      locationFilter.region = ''
    }
  },
  { deep: true },
)

function revokeMultiPhotoPreviews() {
  multiPhotoDrafts.value.forEach((item) => {
    if (item?.previewUrl && item.previewUrl.startsWith('blob:')) {
      URL.revokeObjectURL(item.previewUrl)
    }
  })
}

function resetMultiPhotoDrafts() {
  revokeMultiPhotoPreviews()
  multiPhotoDrafts.value = []
  multiPhotoLoading.value = false
  multiPhotoLoadingCount.value = 0
}

function resetAutofillMessage() {
  autofillState.status = 'idle'
  autofillState.fileName = ''
  autofillState.message = defaultAutofillMessage
  return
  autofillState.message = '?ъ쭊??怨좊Ⅴ硫?EXIF ?뺣낫媛 ?덉쓣 ???좎쭨, ?쒓컙, GPS 醫뚰몴瑜??먮룞?쇰줈 梨꾩썙以띾땲??'
}

function extractDraftTitleSeed(fileName) {
  const text = String(fileName || '').trim()
  if (!text) {
    return ''
  }
  return text.replace(/\.[^/.]+$/, '').trim()
}

function resolveMultiPhotoTitle(item) {
  const explicitTitle = String(item?.title || '').trim()
  if (explicitTitle) {
    return explicitTitle
  }

  const placeName = String(item?.placeName || '').trim()
  if (placeName) {
    return placeName
  }

  const suggestedTitle = String(item?.suggestedTitle || '').trim()
  if (suggestedTitle) {
    return suggestedTitle
  }

  return String(item?.fileName || '').trim() || '사진 기록'
}

function buildMultiPhotoPayload(item) {
  return {
    memoryDate: item.memoryDate || form.memoryDate,
    memoryTime: item.memoryTime || null,
    category: (form.category || queuedCategory.value || '?μ냼').trim(),
    title: resolveMultiPhotoTitle(item),
    country: item.country || null,
    region: item.region || null,
    placeName: item.placeName || null,
    latitude: toNullableNumber(item.latitude),
    longitude: toNullableNumber(item.longitude),
    sharedWithCommunity: Boolean(form.sharedWithCommunity),
    memo: form.memo.trim() || null,
  }
}

async function buildMultiPhotoDrafts(files) {
  const drafts = []

  for (const [index, file] of files.entries()) {
    let metadata = null
    try {
      metadata = await extractPhotoMetadata(file)
    } catch {
      metadata = null
    }

    drafts.push({
      id: `${file.name}-${file.lastModified}-${index}`,
      file,
      fileName: file.name,
      previewUrl: await createLocalImagePreview(file),
      title: '',
      suggestedTitle: extractDraftTitleSeed(file.name),
      memoryDate: metadata?.date || form.memoryDate || resolveDefaultMemoryDate(),
      memoryTime: metadata?.time || '',
      country: metadata?.country || '',
      region: metadata?.region || '',
      placeName: metadata?.placeName || '',
      latitude: metadata?.latitude != null ? String(metadata.latitude) : '',
      longitude: metadata?.longitude != null ? String(metadata.longitude) : '',
    })
  }

  return drafts
}

function handleMultiPhotoModeChange() {
  resetMultiPhotoDrafts()
  photoFiles.value = []
  photoCaption.value = ''
  resetAutofillMessage()
}

function resetPendingFiles() {
  resetMultiPhotoDrafts()
  photoFiles.value = []
  photoCaption.value = ''
  multiPhotoUploadEnabled.value = false
  autofillState.status = 'idle'
  autofillState.fileName = ''
  autofillState.message = '사진을 고르면 EXIF 정보가 있을 때 날짜, 시간, GPS 좌표를 자동으로 채워줍니다.'
}

function resetForm() {
  Object.assign(form, createDefaultForm())
  editingMemoryId.value = null
  resetPendingFiles()
  resetAutofillMessage()
}

function selectAllDays() {
  activeDayDate.value = ''
}

function selectTripDay(day) {
  activeDayDate.value = day.date
  if (!editingMemoryId.value) {
    form.memoryDate = day.date
  }
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
  resetAutofillMessage()
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
    multiPhotoLoading.value = false
    multiPhotoLoadingCount.value = 0
    return
  }

  if (isMultiPhotoMode.value) {
    resetMultiPhotoDrafts()
    photoCaption.value = ''
    multiPhotoLoading.value = true
    multiPhotoLoadingCount.value = files.length
    autofillState.status = 'loading'
    autofillState.fileName = ''
    autofillState.message = `${files.length}장의 사진 정보를 불러오는 중입니다.`

    try {
      multiPhotoDrafts.value = await buildMultiPhotoDrafts(files)
      const enrichedCount = multiPhotoDrafts.value.filter((item) =>
        Boolean(item.memoryDate || item.memoryTime || item.country || item.region || item.placeName || item.latitude || item.longitude),
      ).length
      autofillState.status = enrichedCount ? 'filled' : 'manual'
      autofillState.fileName = ''
      autofillState.message = enrichedCount
        ? `${multiPhotoDrafts.value.length}장의 사진 정보를 불러왔습니다. 각 사진 제목을 입력한 뒤 저장하세요.`
        : `${multiPhotoDrafts.value.length}장의 사진을 불러왔지만 메타데이터가 부족한 항목은 빈 값으로 저장됩니다.`
    } catch (error) {
      resetMultiPhotoDrafts()
      autofillState.status = 'manual'
      autofillState.fileName = files[0]?.name || ''
      autofillState.message = error?.message || '?ъ쭊 硫뷀??곗씠?곕? ?쎌? 紐삵뻽?듬땲??'
    }
    multiPhotoLoading.value = false
    multiPhotoLoadingCount.value = 0
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
  revokeMultiPhotoPreviews()
  window.removeEventListener('keydown', handleWindowKeydown)
})

function submitMemory() {
  if (!props.travelPlan) {
    return
  }

  queuedCategory.value = form.category.trim() || queuedCategory.value

  if (hasMultiPhotoDrafts.value) {
    const missingTitle = multiPhotoDrafts.value.find((item) => !resolveMultiPhotoTitle(item))
    if (missingTitle) {
      autofillState.status = 'manual'
      autofillState.fileName = missingTitle.fileName || ''
      autofillState.message = '복수 사진 기록에서는 모든 사진의 제목을 입력해야 합니다.'
      return
    }

    emit('save-memory', {
      batchItems: multiPhotoDrafts.value.map((item) => ({
        payload: buildMultiPhotoPayload(item),
        file: item.file,
      })),
    })
    return
  }

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

      <div class="travel-memory-quick-open__controls">
        <div class="scope-toggle">
          <button
            class="button button--ghost"
            :class="{ 'is-active': photoQuickOpenSort === 'desc' }"
            type="button"
            @click="photoQuickOpenSort = 'desc'"
          >
            최신순
          </button>
          <button
            class="button button--ghost"
            :class="{ 'is-active': photoQuickOpenSort === 'asc' }"
            type="button"
            @click="photoQuickOpenSort = 'asc'"
          >
            오래된순
          </button>
        </div>
        <div v-if="photoQuickOpenPageCount > 1" class="panel__actions">
          <button
            class="button button--ghost"
            type="button"
            :disabled="photoQuickOpenPage <= 0"
            @click="photoQuickOpenPage -= 1"
          >
            이전
          </button>
          <span>{{ photoQuickOpenPage + 1 }} / {{ photoQuickOpenPageCount }}</span>
          <button
            class="button button--ghost"
            type="button"
            :disabled="photoQuickOpenPage + 1 >= photoQuickOpenPageCount"
            @click="photoQuickOpenPage += 1"
          >
            다음
          </button>
        </div>
      </div>

      <div class="travel-media-grid travel-media-grid--gallery travel-media-grid--quick-open">
        <article
          v-for="(memory, index) in pagedPhotoBackedMemories"
          :key="`photo-memory-${memory.id}`"
          class="travel-media-card travel-media-card--compact"
        >
          <img
            v-if="memory.heroPhoto?.contentUrl"
            :src="buildThumbnailUrl(memory.heroPhoto.contentUrl)"
            :alt="memory.heroPhoto.originalFileName"
            :loading="index < 2 ? 'eager' : 'lazy'"
            :fetchpriority="index < 2 ? 'high' : 'auto'"
            decoding="async"
            class="travel-media-thumb"
          />
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

      <div class="travel-day-tabs">
        <button
          class="travel-day-tabs__button"
          :class="{ 'is-active': !activeDayDate }"
          type="button"
          @click="selectAllDays"
        >
          <strong>전체 일정</strong>
          <small>모든 날짜</small>
        </button>
        <button
          v-for="day in tripDays"
          :key="day.date"
          class="travel-day-tabs__button"
          :class="{ 'is-active': activeDayDate === day.date }"
          type="button"
          @click="selectTripDay(day)"
        >
          <strong>{{ day.label }}</strong>
          <small>{{ formatDate(day.date) }}</small>
        </button>
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
          <h2>{{ activeDayLabel }} 여행 기록</h2>
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
            <tr v-for="memory in pagedFilteredMemoryRecords" :key="memory.id">
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
      <div v-if="filteredMemoryRecords.length > MEMORY_RECORD_PAGE_SIZE" class="panel__actions">
        <button
          class="button button--ghost"
          type="button"
          :disabled="memoryRecordPage <= 0"
          @click="memoryRecordPage -= 1"
        >
          이전
        </button>
        <span>{{ memoryRecordPage + 1 }} / {{ memoryRecordPageCount }}</span>
        <button
          class="button button--ghost"
          type="button"
          :disabled="memoryRecordPage + 1 >= memoryRecordPageCount"
          @click="memoryRecordPage += 1"
        >
          다음
        </button>
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
            <label class="field field--full travel-memory-pin-picker">
              <span class="field__label">핀 종류</span>
              <div class="travel-map__toolbar-group travel-map__toolbar-group--wrap travel-memory-pin-picker__options">
                <button
                  v-for="preset in pinPresetOptions"
                  :key="`editor-pin-${preset.key}`"
                  class="travel-map__toolbar-button"
                  :class="{ 'is-active': activePinPreset.key === preset.key }"
                  type="button"
                  @click="applyPinPreset(preset)"
                >
                  {{ preset.label }}
                </button>
              </div>
            </label>
            <template v-if="hasMultiPhotoDrafts">
              <label class="field">
                <span class="field__label">분류</span>
                <input v-model="form.category" list="memory-category-options" type="text" placeholder="장소, 음식점, 박물관, 관광지" />
              </label>
              <div class="field travel-batch-memory-summary">
                <span class="field__label">일괄 기록 안내</span>
                <small class="field__hint">사진마다 날짜, 시간, 위치 정보는 자동으로 들어가고 제목만 개별 입력합니다.</small>
              </div>
              <label class="checkbox-row field--full">
                <input v-model="form.sharedWithCommunity" type="checkbox" />
                <span>이 기록을 커뮤니티 지도 월드에 공유하기</span>
              </label>
              <label class="field field--full">
                <span class="field__label">메모</span>
                <textarea v-model="form.memo" rows="4" placeholder="모든 사진 기록에 공통으로 남길 메모가 있다면 입력해 주세요." />
              </label>
            </template>
            <template v-else>
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
            </template>
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
            <label v-if="!editingMemoryId" class="checkbox-row field--full travel-batch-toggle">
              <input v-model="multiPhotoUploadEnabled" type="checkbox" @change="handleMultiPhotoModeChange" />
              <span>여러 사진 받기</span>
            </label>
            <label class="field field--full">
              <span class="field__label">사진 업로드</span>
              <input accept="image/*" multiple type="file" @change="handlePhotoSelection" />
            </label>
            <label v-if="!isMultiPhotoMode" class="field field--full">
              <span class="field__label">사진 설명</span>
              <input v-model="photoCaption" type="text" placeholder="업로드할 사진에 붙일 짧은 설명" />
            </label>
          </div>

          <div v-if="multiPhotoLoading" class="travel-batch-memory-list">
            <article
              v-for="item in multiPhotoSkeletonItems"
              :key="item"
              class="travel-batch-memory-card travel-batch-memory-card--skeleton"
            >
              <div class="travel-batch-memory-card__thumb travel-skeleton-block" aria-hidden="true" />
              <div class="travel-batch-memory-card__meta">
                <span class="travel-skeleton-line travel-skeleton-line--lg" aria-hidden="true" />
                <span class="travel-skeleton-line travel-skeleton-line--md" aria-hidden="true" />
                <span class="travel-skeleton-line travel-skeleton-line--sm" aria-hidden="true" />
              </div>
              <div class="travel-batch-memory-card__title travel-batch-memory-card__title--skeleton">
                <span class="travel-skeleton-line travel-skeleton-line--label" aria-hidden="true" />
                <span class="travel-skeleton-block travel-skeleton-block--input" aria-hidden="true" />
              </div>
            </article>
          </div>

          <div v-else-if="hasMultiPhotoDrafts" class="travel-batch-memory-list">
            <article v-for="(item, index) in multiPhotoDrafts" :key="item.id" class="travel-batch-memory-card">
              <img
                :src="item.previewUrl"
                :alt="item.fileName"
                :loading="index < 4 ? 'eager' : 'lazy'"
                :fetchpriority="index < 4 ? 'high' : 'auto'"
                decoding="async"
                class="travel-batch-memory-card__thumb"
              />
              <div class="travel-batch-memory-card__meta">
                <strong>{{ item.fileName }}</strong>
                <small>{{ formatDateTime(item.memoryDate, item.memoryTime) }}</small>
                <small>{{ [item.country, item.region, item.placeName].filter(Boolean).join(' / ') || '위치 정보 없음' }}</small>
              </div>
              <label class="field travel-batch-memory-card__title">
                <span class="field__label">제목</span>
                <input
                  v-model="item.title"
                  type="text"
                  :placeholder="item.suggestedTitle || '사진마다 제목을 입력해 주세요.'"
                />
              </label>
            </article>
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
            <article v-for="(media, index) in editingPhotos" :key="media.id" class="travel-media-card">
              <img
                :src="buildThumbnailUrl(media.contentUrl)"
                :alt="media.originalFileName"
                :loading="index < 2 ? 'eager' : 'lazy'"
                :fetchpriority="index < 2 ? 'high' : 'auto'"
                decoding="async"
                class="travel-media-thumb"
              />
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
