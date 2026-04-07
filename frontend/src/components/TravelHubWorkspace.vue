<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import {
  createTravelBudgetItem,
  createTravelMemory,
  createTravelPlan,
  createTravelRecord,
  createTravelRoute,
  deleteTravelBudgetItem,
  deleteTravelMedia,
  deleteTravelMemory,
  deleteTravelPlan,
  deleteTravelRecord,
  deleteTravelRoute,
  fetchTravelCategories,
  fetchTravelCommunityFeed,
  fetchTravelExchangeRates,
  fetchTravelPlan,
  fetchTravelPlans,
  fetchTravelPortfolio,
  fetchTravelSharedExhibit,
  fetchTravelSharedExhibits,
  updateTravelBudgetItem,
  updateTravelMemory,
  updateTravelPlan,
  updateTravelRecord,
  updateTravelRoute,
  shareTravelPlan,
  uploadTravelMemoryMedia,
  uploadTravelRouteGpxFiles,
} from '../lib/api'
import {
  formatCurrency,
  formatCurrencyByCode,
  formatDate,
  formatDateTime,
  safeNumber,
  toNullableNumber,
  todayIso,
} from '../lib/uiFormat'
import { buildThumbnailUrl } from '../lib/mediaPreview'
import TravelCommunityWorkspace from './TravelCommunityWorkspace.vue'
import TravelMapPanel from './TravelMapPanel.vue'
import TravelMemoryPanel from './TravelMemoryPanel.vue'
import TravelOverviewWorkspace from './TravelOverviewWorkspace.vue'
import TravelRouteWorkspace from './TravelRouteWorkspace.vue'
import TravelSharedExhibitWorkspace from './TravelSharedExhibitWorkspace.vue'

const props = defineProps({
  route: {
    type: String,
    required: true,
  },
  integratedMode: {
    type: Boolean,
    default: false,
  },
  integratedPhotoMode: {
    type: Boolean,
    default: false,
  },
})

const emit = defineEmits(['request-open-finance', 'request-open-log'])

const fallbackCategories = {
  planStatuses: ['PLANNED', 'COMPLETED', 'SAMPLE'],
  budgetCategories: ['교통', '숙소', '식비', '쇼핑', '체험', '기타'],
  expenseCategories: ['식비', '카페', '교통', '쇼핑', '입장권', '뽑기', '기타'],
  memoryCategories: ['장소', '식사', '카페', '이동', '숙소', '사진'],
  countries: [],
  regions: [],
  places: [],
}

const planStatusLabels = {
  PLANNED: '예정 여행',
  COMPLETED: '다녀온 여행',
  SAMPLE: '테스트 여행',
}

const workspaceMeta = computed(() => {
  switch (props.route) {
    case 'travel-money':
      return {
        title: '여행 돈 장부',
        description: '여행 생성, 예산안, 지출 기록, 장소별 소비 통계를 한곳에서 관리합니다.',
      }
    case 'travel-log':
      return {
        title: '여행 로그',
        description: '날짜별 메모와 GPX 경로를 기록하고, 전체 또는 일차별 여행 보기를 함께 확인합니다.',
      }
    default:
      return {
        title: '사진첩',
        description: '여행 로그에서 올린 사진을 다시 쓰고, 지도 갤러리와 커뮤니티 피드를 함께 확인합니다.',
      }
  }
})

const isLoading = ref(false)
const isSubmitting = ref(false)
const activeSubmit = ref('')
const feedback = ref('')
const errorMessage = ref('')

const travelPlans = ref([])
const selectedPlanId = ref('')
const travelPlan = ref(null)
const travelPortfolio = ref(null)
const travelRates = ref([])
const travelCategories = ref({ ...fallbackCategories })
const communityFeed = ref([])

const moneyTab = ref('planner')
const logTab = ref('overview')
const albumTab = ref('upload')
const planFormMode = ref('create')
const editingBudgetItemId = ref(null)
const editingRecordId = ref(null)
const memoryRefreshKey = ref(0)
const routeRefreshKey = ref(0)
const memoryFocusRequest = ref(null)
const memoryUploadProgress = reactive({
  active: false,
  title: '사진을 업로드하는 중입니다.',
  description: '선택한 사진을 저장소 서버로 업로드하고 있습니다.',
  current: 0,
  total: 0,
})
const sharedExhibitSummaries = ref([])
const selectedSharedExhibitId = ref('')
const selectedSharedExhibit = ref(null)
const shareForm = reactive({
  recipientLoginId: '',
})

const planForm = reactive({
  name: '',
  destination: '',
  startDate: todayIso(),
  endDate: todayIso(),
  homeCurrency: 'KRW',
  headCount: 1,
  status: 'PLANNED',
  colorHex: '#3182F6',
  memo: '',
})

const budgetForm = reactive({
  category: '교통',
  title: '',
  amount: '',
  currencyCode: 'KRW',
  memo: '',
})
const budgetAmountInput = ref('')

const recordForm = reactive({
  expenseDate: todayIso(),
  expenseTime: '',
  category: '식비',
  title: '',
  amount: '',
  currencyCode: 'JPY',
  country: '',
  region: '',
  placeName: '',
  latitude: '',
  longitude: '',
  memo: '',
})
const recordAmountInput = ref('')

const recordGpsEnabled = ref(true)
const recordGpsLoading = ref(false)
const recordGpsRequestToken = ref(0)
const recordAutofillMessage = ref('현재 위치(GPS) 자동 기록이 켜져 있습니다. 새 지출 기록에서 현재 위치를 위도/경도에 채워줍니다.')
const recordAutofillTone = ref('default')
const communityFeedPage = ref(0)
const communityFeedPageCount = ref(1)
const communityFeedTotal = ref(0)
const sharedExhibitPage = ref(0)
const sharedExhibitPageCount = ref(1)
const sharedExhibitTotal = ref(0)
const selectedRecordDate = ref('')
const photoAlbumGroupMode = ref('all')

const planStatusOptions = computed(() => travelCategories.value.planStatuses?.length ? travelCategories.value.planStatuses : fallbackCategories.planStatuses)
const budgetCategoryOptions = computed(() => travelCategories.value.budgetCategories?.length ? travelCategories.value.budgetCategories : fallbackCategories.budgetCategories)
const expenseCategoryOptions = computed(() => travelCategories.value.expenseCategories?.length ? travelCategories.value.expenseCategories : fallbackCategories.expenseCategories)
const memoryCategoryOptions = computed(() => travelCategories.value.memoryCategories?.length ? travelCategories.value.memoryCategories : fallbackCategories.memoryCategories)
const requiresExplicitPlanSelection = computed(() => props.route === 'travel-log' || props.route === 'photo-album')
const isSharedExhibitTab = computed(() => props.route === 'photo-album' && albumTab.value === 'shared')
const hasSharedExhibits = computed(() => sharedExhibitSummaries.value.length > 0)
const showAlbumUploadTab = computed(() => !props.integratedPhotoMode)
const photoAlbumTabChoices = computed(() => (
  props.integratedPhotoMode
    ? [
        { key: 'gallery', label: '사진첩' },
        { key: 'shared', label: '공유 전시' },
        { key: 'community', label: '커뮤니티' },
      ]
    : [
        { key: 'upload', label: '업로드와 기록' },
        { key: 'gallery', label: '지도 갤러리' },
        { key: 'shared', label: '공유 전시' },
        { key: 'community', label: '커뮤니티' },
      ]
))
const showPlanGate = computed(() =>
  requiresExplicitPlanSelection.value
  && !travelPlan.value
  && !isSharedExhibitTab.value
  && !(props.route === 'photo-album' && hasSharedExhibits.value)
)
const canShareTravelPlan = computed(() => Boolean(travelPlan.value) && travelPlan.value.status === 'COMPLETED')
const emptyTravelPlanMessage = computed(() => {
  if (isSharedExhibitTab.value) {
    return '공유 전시 탭에서는 여행 선택 없이도 공유받은 전시를 볼 수 있습니다.'
  }
  if (props.route === 'photo-album' && hasSharedExhibits.value) {
    return '공유받은 전시가 있어 여행 선택 없이도 공유 전시를 바로 볼 수 있습니다.'
  }
  if (showPlanGate.value) {
    return '계속하려면 먼저 여행 하나를 선택해주세요.'
  }
  return '먼저 여행을 만들어야 예산, 이동 경로, 사진첩 기능을 이어서 사용할 수 있습니다.'
})

function parseIsoDate(value) {
  const [year, month, day] = String(value || '').split('-').map((item) => Number(item))
  if (!year || !month || !day) {
    return null
  }

  return new Date(year, month - 1, day)
}

function formatTravelDayBadge(value, index) {
  const parsed = parseIsoDate(value)
  if (!parsed) {
    return `${index + 1}일차`
  }

  return `${index + 1}일차 · ${parsed.getMonth() + 1}/${parsed.getDate()}`
}

function buildTravelDateRange(startDate, endDate) {
  const start = parseIsoDate(startDate)
  const end = parseIsoDate(endDate)
  if (!start || !end || start > end) {
    return []
  }

  const dates = []
  const cursor = new Date(start)
  while (cursor <= end && dates.length < 400) {
    const year = cursor.getFullYear()
    const month = String(cursor.getMonth() + 1).padStart(2, '0')
    const day = String(cursor.getDate()).padStart(2, '0')
    dates.push(`${year}-${month}-${day}`)
    cursor.setDate(cursor.getDate() + 1)
  }
  return dates
}

function normalizeTravelRecordTime(value) {
  const normalized = String(value || '').trim()
  return normalized || '00:00'
}

function sanitizeTravelAmountInput(value, { allowDecimal = false } = {}) {
  const source = String(value || '').replace(/,/g, '')
  if (!allowDecimal) {
    return source.replace(/[^0-9]/g, '')
  }

  const cleaned = source.replace(/[^0-9.]/g, '')
  const dotIndex = cleaned.indexOf('.')
  if (dotIndex < 0) {
    return cleaned
  }

  const integerPart = cleaned.slice(0, dotIndex).replace(/\./g, '')
  const decimalPart = cleaned.slice(dotIndex + 1).replace(/\./g, '')
  return `${integerPart}.${decimalPart}`
}

function formatTravelAmountInput(value, { allowDecimal = false } = {}) {
  const sanitized = sanitizeTravelAmountInput(value, { allowDecimal })
  if (!sanitized) {
    return ''
  }

  if (!allowDecimal) {
    return Number(sanitized).toLocaleString('ko-KR')
  }

  const hasDot = sanitized.includes('.')
  const [rawIntegerPart = '', rawDecimalPart = ''] = sanitized.split('.', 2)
  const normalizedInteger = rawIntegerPart.replace(/^0+(?=\d)/, '') || '0'
  const formattedInteger = Number(normalizedInteger).toLocaleString('ko-KR')

  if (!hasDot) {
    return formattedInteger
  }

  return `${formattedInteger}.${rawDecimalPart}`
}

function handleBudgetAmountInput(value) {
  const sanitized = sanitizeTravelAmountInput(value)
  budgetForm.amount = sanitized ? String(Number(sanitized)) : ''
  budgetAmountInput.value = formatTravelAmountInput(sanitized)
}

function handleRecordAmountInput(value) {
  const sanitized = sanitizeTravelAmountInput(value, { allowDecimal: true })
  const normalized = sanitized === '.' ? '0.' : sanitized
  recordForm.amount = normalized
  recordAmountInput.value = formatTravelAmountInput(normalized, { allowDecimal: true })
}

function formatTravelLocationLabel(record) {
  const locationLabel = [record?.country, record?.region, record?.placeName]
    .map((value) => String(value || '').trim())
    .filter(Boolean)
    .join(' / ')
  if (locationLabel) {
    return locationLabel
  }

  const latitude = Number(record?.latitude)
  const longitude = Number(record?.longitude)
  if (Number.isFinite(latitude) && Number.isFinite(longitude)) {
    return `${latitude.toFixed(5)}, ${longitude.toFixed(5)}`
  }

  return '-'
}

const actualByCategory = computed(() => {
  const bucket = new Map()
  ;(travelPlan.value?.records ?? []).forEach((record) => {
    const key = record.category || '기타'
    const current = bucket.get(key) ?? { label: key, totalKrw: 0, count: 0 }
    current.totalKrw += safeNumber(record.amountKrw)
    current.count += 1
    bucket.set(key, current)
  })
  return [...bucket.values()].sort((left, right) => right.totalKrw - left.totalKrw)
})

const budgetByCategory = computed(() => {
  const bucket = new Map()
  ;(travelPlan.value?.budgetItems ?? []).forEach((item) => {
    const key = item.category || '기타'
    const current = bucket.get(key) ?? { label: key, totalKrw: 0, count: 0 }
    current.totalKrw += safeNumber(item.amountKrw)
    current.count += 1
    bucket.set(key, current)
  })
  return [...bucket.values()].sort((left, right) => right.totalKrw - left.totalKrw)
})

const spendingLocations = computed(() => {
  const bucket = new Map()
  ;(travelPlan.value?.records ?? []).forEach((record) => {
    const label = formatTravelLocationLabel(record) === '-' ? '위치 미입력' : formatTravelLocationLabel(record)
    const key = label
    const current = bucket.get(key) ?? {
      key,
      label,
      totalKrw: 0,
      count: 0,
    }
    current.totalKrw += safeNumber(record.amountKrw)
    current.count += 1
    bucket.set(key, current)
  })
  return [...bucket.values()].sort((left, right) => right.totalKrw - left.totalKrw)
})

const recordPhotoSummaryMap = computed(() => {
  const bucket = new Map()
  ;(travelPlan.value?.mediaItems ?? []).forEach((item) => {
    if (item.recordType === 'MEMORY' || item.mediaType !== 'PHOTO') {
      return
    }
    const key = String(item.recordId)
    const current = bucket.get(key) ?? { photos: 0, heroPhotoUrl: '' }
    current.photos += 1
    if (!current.heroPhotoUrl) {
      current.heroPhotoUrl = item.contentUrl || ''
    }
    bucket.set(key, current)
  })
  return bucket
})

const tripDateRange = computed(() => buildTravelDateRange(travelPlan.value?.startDate, travelPlan.value?.endDate))
const recordSummaryByDate = computed(() => {
  const bucket = new Map()
  ;(travelPlan.value?.records ?? []).forEach((record) => {
    const dateKey = String(record.expenseDate || '').trim()
    if (!dateKey) {
      return
    }

    const current = bucket.get(dateKey) ?? {
      totalKrw: 0,
      count: 0,
      photoCount: 0,
      currencies: new Set(),
    }
    current.totalKrw += safeNumber(record.amountKrw)
    current.count += 1
    current.photoCount += recordPhotoSummaryMap.value.get(String(record.id))?.photos || 0
    if (record.currencyCode) {
      current.currencies.add(String(record.currencyCode).toUpperCase())
    }
    bucket.set(dateKey, current)
  })
  return bucket
})

const tripRecordDays = computed(() =>
  tripDateRange.value.map((date, index) => {
    const summary = recordSummaryByDate.value.get(date)
    return {
      date,
      dayLabel: formatTravelDayBadge(date, index),
      totalKrw: summary?.totalKrw || 0,
      count: summary?.count || 0,
      photoCount: summary?.photoCount || 0,
      currencyLabel: [...(summary?.currencies ?? [])].join(', '),
    }
  }),
)

const filteredTravelRecords = computed(() => {
  const records = travelPlan.value?.records ?? []
  if (!selectedRecordDate.value) {
    return records
  }
  return records.filter((record) => String(record.expenseDate || '') === selectedRecordDate.value)
})

const recordMarkers = computed(() =>
  filteredTravelRecords.value
    .filter((record) => record.latitude !== null && record.latitude !== undefined && record.longitude !== null && record.longitude !== undefined)
    .map((record) => {
      const photoSummary = recordPhotoSummaryMap.value.get(String(record.id))
      return {
      id: record.id,
      planId: record.planId,
      planName: record.planName,
      colorHex: record.planColorHex || travelPlan.value?.colorHex || '#3182F6',
      latitude: Number(record.latitude),
      longitude: Number(record.longitude),
      country: record.country,
      region: record.region,
      placeName: record.placeName,
      title: record.title,
      amount: record.amount,
      currencyCode: record.currencyCode,
      amountKrw: record.amountKrw,
      visitedDate: record.expenseDate,
      visitedTime: record.expenseTime,
      photoCount: photoSummary?.photos || 0,
      receiptCount: 0,
      photoUrl: photoSummary?.heroPhotoUrl || '',
      }
    }),
)

const memoryById = computed(() => new Map((travelPlan.value?.memoryRecords ?? []).map((item) => [String(item.id), item])))
const memoryPhotoSummaryMap = computed(() => {
  const bucket = new Map()
  ;(travelPlan.value?.mediaItems ?? []).forEach((item) => {
    if (item.recordType !== 'MEMORY' || item.mediaType !== 'PHOTO') {
      return
    }
    const key = String(item.recordId)
    const current = bucket.get(key) ?? {
      count: 0,
      heroPhoto: null,
      heroPhotoUrl: '',
      caption: '',
      uploadedAt: '',
    }
    current.count += 1
    if (!current.heroPhoto) {
      current.heroPhoto = item
      current.heroPhotoUrl = item.contentUrl || ''
      current.caption = item.caption || item.originalFileName || ''
      current.uploadedAt = item.uploadedAt || ''
    }
    bucket.set(key, current)
  })
  return bucket
})
const photoAlbumPhotoCount = computed(() => [...memoryPhotoSummaryMap.value.values()].reduce((total, item) => total + item.count, 0))
const PHOTO_ALBUM_PAGE_SIZE = 10
const photoAlbumPage = ref(0)
const photoAlbumCards = computed(() =>
  [...memoryPhotoSummaryMap.value.entries()]
    .map(([recordId, photoSummary]) => {
      const memory = memoryById.value.get(recordId)
      if (!memory) return null
      const heroPhoto = photoSummary.heroPhoto
      return {
        id: `album-${recordId}`,
        memoryId: memory.id,
        planName: memory.planName || travelPlan.value?.name || '여행',
        title: memory.title || memory.placeName || '제목 없는 기록',
        memoryDate: memory.memoryDate,
        memoryTime: memory.memoryTime,
        country: memory.country,
        region: memory.region,
        placeName: memory.placeName,
        latitude: memory.latitude,
        longitude: memory.longitude,
        memo: memory.memo,
        colorHex: memory.planColorHex || travelPlan.value?.colorHex || '#3182F6',
        photoCount: photoSummary.count,
        heroPhoto,
        heroPhotoUrl: photoSummary.heroPhotoUrl,
        caption: photoSummary.caption,
        locationLabel: [memory.country, memory.region, memory.placeName].filter(Boolean).join(' / ') || '위치 미설정',
        sortKey: `${photoSummary.uploadedAt || ''} ${memory.memoryDate || ''} ${memory.memoryTime || '99:99'} ${String(memory.id).padStart(12, '0')}`,
      }
    })
    .filter(Boolean)
    .sort((left, right) => right.sortKey.localeCompare(left.sortKey)),
)
const photoAlbumPageCount = computed(() => Math.max(Math.ceil(photoAlbumCards.value.length / PHOTO_ALBUM_PAGE_SIZE), 1))
const pagedPhotoAlbumCards = computed(() => {
  const start = photoAlbumPage.value * PHOTO_ALBUM_PAGE_SIZE
  return photoAlbumCards.value.slice(start, start + PHOTO_ALBUM_PAGE_SIZE)
})
const recordAutofillMessageClass = computed(() => ({
  'travel-autofill-note--filled': recordAutofillTone.value === 'filled',
  'travel-autofill-note--manual': recordAutofillTone.value === 'manual',
}))
const photoAlbumMarkers = computed(() =>
  photoAlbumCards.value
    .filter((item) => item.latitude !== null && item.latitude !== undefined && item.longitude !== null && item.longitude !== undefined)
    .map((item) => ({
      id: item.id,
      planId: travelPlan.value?.id,
      planName: item.planName,
      colorHex: item.colorHex,
      latitude: Number(item.latitude),
      longitude: Number(item.longitude),
      country: item.country,
      region: item.region,
      placeName: item.placeName,
      title: item.title,
      visitedDate: item.memoryDate,
      visitedTime: item.memoryTime,
      photoCount: item.photoCount,
      receiptCount: 0,
      photoUrl: item.heroPhotoUrl,
  })),
)

const photoAlbumGroups = computed(() => {
  const bucket = new Map()

  photoAlbumCards.value.forEach((item) => {
    let groupKey = 'all'
    let label = '전체 보기'

    switch (photoAlbumGroupMode.value) {
      case 'time':
        groupKey = item.memoryDate || 'unknown-date'
        label = item.memoryDate ? `${formatDate(item.memoryDate)} 일정` : '날짜 미지정'
        break
      case 'country':
        groupKey = item.country || 'unknown-country'
        label = item.country || '국가 미지정'
        break
      case 'region':
        groupKey = `${item.country || ''}__${item.region || 'unknown-region'}`
        label = [item.country, item.region].filter(Boolean).join(' / ') || '지역 미지정'
        break
      case 'travel':
        groupKey = item.planName || 'unknown-plan'
        label = item.planName || '여행 미지정'
        break
      default:
        break
    }

    const current = bucket.get(groupKey) ?? { key: groupKey, label, items: [] }
    current.items.push(item)
    bucket.set(groupKey, current)
  })

  return [...bucket.values()]
})

watch(
  () => photoAlbumCards.value.length,
  () => {
    if (photoAlbumPage.value >= photoAlbumPageCount.value) {
      photoAlbumPage.value = Math.max(photoAlbumPageCount.value - 1, 0)
    }
  },
)

watch(albumTab, () => {
  photoAlbumPage.value = 0
})

watch(
  () => [travelPlan.value?.id, travelPlan.value?.startDate, travelPlan.value?.endDate],
  () => {
    const availableDates = tripRecordDays.value.map((item) => item.date)
    if (!availableDates.length) {
      selectedRecordDate.value = ''
      return
    }

    if (availableDates.includes(selectedRecordDate.value)) {
      return
    }

    const latestRecordedDate = [...recordSummaryByDate.value.keys()]
      .filter((date) => availableDates.includes(date))
      .sort((left, right) => left.localeCompare(right))
      .at(-1)

    selectedRecordDate.value = latestRecordedDate || availableDates[0]
  },
  { immediate: true },
)

watch(
  () => [props.route, props.integratedPhotoMode],
  ([route, integratedPhotoMode]) => {
    if (route === 'photo-album' && integratedPhotoMode && albumTab.value === 'upload') {
      albumTab.value = 'gallery'
    }
    if (route !== 'photo-album' && integratedPhotoMode && photoAlbumGroupMode.value !== 'all') {
      photoAlbumGroupMode.value = 'all'
    }
  },
  { immediate: true },
)

function handleSelectRecordDate(date) {
  selectedRecordDate.value = String(date || '')
  if (!editingRecordId.value && selectedRecordDate.value) {
    recordForm.expenseDate = selectedRecordDate.value
  }
}

function setFeedback(message = '', error = '') {
  feedback.value = message
  errorMessage.value = error
}

function resetMemoryUploadProgress() {
  memoryUploadProgress.active = false
  memoryUploadProgress.title = '사진을 업로드하는 중입니다.'
  memoryUploadProgress.description = '선택한 사진을 저장소 서버로 업로드하고 있습니다.'
  memoryUploadProgress.current = 0
  memoryUploadProgress.total = 0
}

function startMemoryUploadProgress(total, description = '선택한 사진을 저장소 서버로 업로드하고 있습니다.') {
  memoryUploadProgress.active = total > 0
  memoryUploadProgress.title = '사진을 업로드하는 중입니다.'
  memoryUploadProgress.description = description
  memoryUploadProgress.current = 0
  memoryUploadProgress.total = total
}

function updateMemoryUploadProgress({ current, total, description }) {
  if (Number.isFinite(total)) {
    memoryUploadProgress.total = total
  }
  if (Number.isFinite(current)) {
    memoryUploadProgress.current = current
  }
  if (description) {
    memoryUploadProgress.description = description
  }
}

function planStatusLabel(status) {
  return planStatusLabels[status] || status || '미정'
}

function collectCurrencyCodes() {
  const codes = new Set(['KRW', 'USD', 'JPY', 'EUR'])
  if (travelPlan.value?.homeCurrency) codes.add(String(travelPlan.value.homeCurrency).toUpperCase())
  ;(travelPlan.value?.budgetItems ?? []).forEach((item) => codes.add(String(item.currencyCode || 'KRW').toUpperCase()))
  ;(travelPlan.value?.records ?? []).forEach((item) => codes.add(String(item.currencyCode || 'KRW').toUpperCase()))
  ;(travelPortfolio.value?.plans ?? []).forEach((item) => codes.add(String(item.homeCurrency || 'KRW').toUpperCase()))
  return [...codes]
}

function resetPlanForm() {
  planFormMode.value = 'create'
  planForm.name = ''
  planForm.destination = ''
  planForm.startDate = todayIso()
  planForm.endDate = todayIso()
  planForm.homeCurrency = 'KRW'
  planForm.headCount = 1
  planForm.status = 'PLANNED'
  planForm.colorHex = '#3182F6'
  planForm.memo = ''
}

function fillPlanForm(plan) {
  if (!plan) return
  planFormMode.value = 'edit'
  planForm.name = plan.name || ''
  planForm.destination = plan.destination || ''
  planForm.startDate = plan.startDate || todayIso()
  planForm.endDate = plan.endDate || todayIso()
  planForm.homeCurrency = plan.homeCurrency || 'KRW'
  planForm.headCount = Number(plan.headCount || 1)
  planForm.status = plan.status || 'PLANNED'
  planForm.colorHex = plan.colorHex || '#3182F6'
  planForm.memo = plan.memo || ''
}

function resetBudgetForm() {
  editingBudgetItemId.value = null
  budgetForm.category = budgetCategoryOptions.value[0] || '교통'
  budgetForm.title = ''
  budgetForm.amount = ''
  budgetAmountInput.value = ''
  budgetForm.currencyCode = travelPlan.value?.homeCurrency || 'KRW'
  budgetForm.memo = ''
}

function fillBudgetForm(item) {
  editingBudgetItemId.value = item.id
  budgetForm.category = item.category || budgetCategoryOptions.value[0] || '교통'
  budgetForm.title = item.title || ''
  budgetForm.amount = String(item.amount || '')
  budgetAmountInput.value = formatTravelAmountInput(budgetForm.amount)
  budgetForm.currencyCode = item.currencyCode || travelPlan.value?.homeCurrency || 'KRW'
  budgetForm.memo = item.memo || ''
}

function resetRecordForm() {
  editingRecordId.value = null
  recordForm.expenseDate = selectedRecordDate.value || travelPlan.value?.startDate || todayIso()
  recordForm.expenseTime = ''
  recordForm.category = expenseCategoryOptions.value[0] || '식비'
  recordForm.title = ''
  recordForm.amount = ''
  recordAmountInput.value = ''
  recordForm.currencyCode = travelPlan.value?.homeCurrency === 'KRW' ? 'JPY' : travelPlan.value?.homeCurrency || 'JPY'
  recordForm.country = ''
  recordForm.region = ''
  recordForm.placeName = ''
  recordForm.latitude = ''
  recordForm.longitude = ''
  recordForm.memo = ''
  refreshRecordGpsAutofill({ overwrite: true })
}

function fillRecordForm(record) {
  editingRecordId.value = record.id
  recordForm.expenseDate = record.expenseDate || travelPlan.value?.startDate || todayIso()
  recordForm.expenseTime = record.expenseTime || '00:00'
  recordForm.category = record.category || expenseCategoryOptions.value[0] || '식비'
  recordForm.title = record.title || ''
  recordForm.amount = String(record.amount || '')
  recordAmountInput.value = formatTravelAmountInput(recordForm.amount, { allowDecimal: true })
  recordForm.currencyCode = record.currencyCode || 'KRW'
  recordForm.country = record.country || ''
  recordForm.region = record.region || ''
  recordForm.placeName = record.placeName || ''
  recordForm.latitude = record.latitude != null ? String(record.latitude) : ''
  recordForm.longitude = record.longitude != null ? String(record.longitude) : ''
  recordForm.memo = record.memo || ''
  cancelRecordGpsRequest()
  if (recordGpsEnabled.value) {
    recordAutofillMessage.value = '현재 위치(GPS) 자동 기록은 켜져 있지만, 수정 중에는 저장된 좌표를 그대로 유지합니다.'
    recordAutofillTone.value = 'manual'
  } else {
    recordAutofillMessage.value = '현재 위치(GPS) 자동 기록이 꺼져 있습니다. 저장된 좌표를 직접 수정하거나 지도에서 다시 찍을 수 있습니다.'
    recordAutofillTone.value = 'manual'
  }
}

function buildPlanPayload() {
  return {
    name: planForm.name.trim(),
    destination: planForm.destination.trim() || null,
    startDate: planForm.startDate,
    endDate: planForm.endDate,
    homeCurrency: String(planForm.homeCurrency || 'KRW').trim().toUpperCase(),
    headCount: Number(planForm.headCount || 1),
    status: planForm.status,
    colorHex: planForm.colorHex,
    memo: planForm.memo.trim() || null,
  }
}

function buildBudgetPayload() {
  return {
    category: budgetForm.category.trim(),
    title: budgetForm.title.trim(),
    amount: Number(budgetForm.amount),
    currencyCode: String(budgetForm.currencyCode || 'KRW').trim().toUpperCase(),
    memo: budgetForm.memo.trim() || null,
  }
}

function buildRecordPayload() {
  return {
    expenseDate: recordForm.expenseDate,
    expenseTime: normalizeTravelRecordTime(recordForm.expenseTime),
    category: recordForm.category.trim(),
    title: recordForm.title.trim(),
    amount: Number(recordForm.amount),
    currencyCode: String(recordForm.currencyCode || 'KRW').trim().toUpperCase(),
    country: recordForm.country.trim() || null,
    region: recordForm.region.trim() || null,
    placeName: recordForm.placeName.trim() || null,
    latitude: toNullableNumber(recordForm.latitude),
    longitude: toNullableNumber(recordForm.longitude),
    memo: recordForm.memo.trim() || null,
  }
}

function applyRecordPickedLocation(point) {
  if (!point) {
    return
  }

  cancelRecordGpsRequest()
  recordForm.latitude = String(point.latitude)
  recordForm.longitude = String(point.longitude)

  const country = String(point.country || '').trim()
  const region = String(point.region || '').trim()
  const placeName = String(point.placeName || point.title || point.displayName || '').trim()

  if (country) {
    recordForm.country = country
  }
  if (region) {
    recordForm.region = region
  }
  if (placeName) {
    recordForm.placeName = placeName
  }

  recordAutofillMessage.value = '지도에서 선택한 위치로 위도와 경도를 입력했습니다.'
  recordAutofillTone.value = 'manual'
}

function handleMoveSelectedRecordPoint(point) {
  if (!point) {
    return
  }

  cancelRecordGpsRequest()
  recordForm.latitude = String(point.latitude)
  recordForm.longitude = String(point.longitude)
  recordAutofillMessage.value = '지도에서 현재 선택 핀을 옮겨 위치를 조정했습니다.'
  recordAutofillTone.value = 'manual'
}

function cancelRecordGpsRequest() {
  recordGpsRequestToken.value += 1
  recordGpsLoading.value = false
}

function refreshRecordGpsAutofill({ overwrite = false, force = false } = {}) {
  if (!recordGpsEnabled.value) {
    cancelRecordGpsRequest()
    recordAutofillMessage.value = '현재 위치(GPS) 자동 기록이 꺼져 있습니다. 지도나 검색으로 위치를 직접 지정할 수 있습니다.'
    recordAutofillTone.value = 'manual'
    return
  }

  if (!selectedPlanId.value || editingRecordId.value) {
    cancelRecordGpsRequest()
    recordAutofillMessage.value = '현재 위치(GPS) 자동 기록이 켜져 있습니다. 새 지출 기록에서 현재 위치를 자동으로 채워줍니다.'
    recordAutofillTone.value = 'default'
    return
  }

  if (!force && !overwrite && String(recordForm.latitude || '').trim() && String(recordForm.longitude || '').trim()) {
    return
  }

  if (typeof window === 'undefined' || !window.navigator?.geolocation) {
    recordAutofillMessage.value = '이 브라우저에서는 현재 위치(GPS)를 사용할 수 없습니다. 지도나 검색으로 위치를 직접 지정해 주세요.'
    recordAutofillTone.value = 'manual'
    return
  }

  const requestToken = recordGpsRequestToken.value + 1
  recordGpsRequestToken.value = requestToken
  recordGpsLoading.value = true
  recordAutofillMessage.value = '현재 위치(GPS)를 확인하고 있습니다.'
  recordAutofillTone.value = 'default'

  window.navigator.geolocation.getCurrentPosition(
    (position) => {
      if (recordGpsRequestToken.value !== requestToken) {
        return
      }
      recordGpsLoading.value = false
      recordForm.latitude = String(position.coords.latitude)
      recordForm.longitude = String(position.coords.longitude)
      recordAutofillMessage.value = '현재 위치(GPS)를 위도와 경도에 자동 입력했습니다. 필요하면 지도에서 다시 조정할 수 있습니다.'
      recordAutofillTone.value = 'filled'
    },
    (error) => {
      if (recordGpsRequestToken.value !== requestToken) {
        return
      }
      recordGpsLoading.value = false
      if (error?.code === error.PERMISSION_DENIED) {
        recordAutofillMessage.value = '현재 위치(GPS) 권한이 없어 자동 입력하지 못했습니다. 위치를 직접 입력하거나 지도에서 선택해 주세요.'
      } else if (error?.code === error.TIMEOUT) {
        recordAutofillMessage.value = '현재 위치(GPS)를 가져오는 시간이 오래 걸렸습니다. 잠시 후 다시 시도하거나 지도에서 직접 지정해 주세요.'
      } else {
        recordAutofillMessage.value = '현재 위치(GPS)를 읽지 못했습니다. 위치를 직접 입력하거나 지도에서 선택해 주세요.'
      }
      recordAutofillTone.value = 'manual'
    },
    {
      enableHighAccuracy: true,
      timeout: 10000,
      maximumAge: 60000,
    },
  )
}

function setRecordGpsEnabled(enabled) {
  const normalized = Boolean(enabled)
  if (recordGpsEnabled.value === normalized) {
    if (normalized) {
      refreshRecordGpsAutofill({ overwrite: true, force: true })
    }
    return
  }

  recordGpsEnabled.value = normalized
  if (normalized) {
    refreshRecordGpsAutofill({ overwrite: true, force: true })
  } else {
    cancelRecordGpsRequest()
    recordAutofillMessage.value = '현재 위치(GPS) 자동 기록이 꺼져 있습니다. 지도나 검색으로 위치를 직접 지정할 수 있습니다.'
    recordAutofillTone.value = 'manual'
  }
}

async function loadTravelCategoriesSafe() {
  try {
    travelCategories.value = await fetchTravelCategories()
  } catch {
    travelCategories.value = { ...fallbackCategories }
  }
}

async function loadTravelCommunityFeed(page = communityFeedPage.value) {
  try {
    const response = await fetchTravelCommunityFeed(page, 10)
    communityFeed.value = response.items ?? []
    communityFeedPage.value = response.page ?? page ?? 0
    communityFeedPageCount.value = Math.max(response.totalPages ?? 1, 1)
    communityFeedTotal.value = response.totalElements ?? communityFeed.value.length
  } catch (error) {
    communityFeed.value = []
    communityFeedPage.value = 0
    communityFeedPageCount.value = 1
    communityFeedTotal.value = 0
    setFeedback('', error.message)
  }
}

async function loadSharedExhibits(preferredShareId = selectedSharedExhibitId.value, page = sharedExhibitPage.value) {
  try {
    const response = await fetchTravelSharedExhibits(page, 5)
    const exhibits = response.items ?? []
    sharedExhibitSummaries.value = exhibits
    sharedExhibitPage.value = response.page ?? page ?? 0
    sharedExhibitPageCount.value = Math.max(response.totalPages ?? 1, 1)
    sharedExhibitTotal.value = response.totalElements ?? exhibits.length

    const requestedShareId = String(preferredShareId || '').trim()
    const nextShareId = exhibits.some((item) => String(item.id) === requestedShareId)
      ? requestedShareId
      : (exhibits[0] ? String(exhibits[0].id) : '')
    selectedSharedExhibitId.value = nextShareId
    selectedSharedExhibit.value = nextShareId ? await fetchTravelSharedExhibit(nextShareId) : null
    if (props.route === 'photo-album' && !travelPlan.value && exhibits.length) {
      albumTab.value = 'shared'
    }
  } catch (error) {
    sharedExhibitSummaries.value = []
    sharedExhibitPage.value = 0
    sharedExhibitPageCount.value = 1
    sharedExhibitTotal.value = 0
    selectedSharedExhibitId.value = ''
    selectedSharedExhibit.value = null
    setFeedback('', error.message)
  }
}

async function handleChangeCommunityFeedPage(page) {
  await loadTravelCommunityFeed(page)
}

async function handleChangeSharedExhibitPage(page) {
  await loadSharedExhibits('', page)
}

async function loadTravelRates() {
  try {
    travelRates.value = await fetchTravelExchangeRates(collectCurrencyCodes())
  } catch {
    travelRates.value = []
  }
}

async function refreshTravelData(preferredPlanId = selectedPlanId.value, includeCommunity = props.route === 'photo-album') {
  isLoading.value = true
  try {
    await loadTravelCategoriesSafe()
    const plans = await fetchTravelPlans()
    travelPlans.value = plans
    const requestedPlanId = String(preferredPlanId || '').trim()
    const currentPlanId = String(selectedPlanId.value || '').trim()
    let nextPlanId = ''

    if (plans.some((item) => String(item.id) === requestedPlanId)) {
      nextPlanId = requestedPlanId
    } else if (!requiresExplicitPlanSelection.value && plans.some((item) => String(item.id) === currentPlanId)) {
      nextPlanId = currentPlanId
    } else if (!requiresExplicitPlanSelection.value && plans[0]) {
      nextPlanId = String(plans[0].id)
    }

    selectedPlanId.value = nextPlanId
    travelPlan.value = selectedPlanId.value ? await fetchTravelPlan(selectedPlanId.value) : null
    travelPortfolio.value = await fetchTravelPortfolio()
    await loadTravelRates()
    if (includeCommunity) await loadTravelCommunityFeed()
    if (props.route === 'photo-album') await loadSharedExhibits(selectedSharedExhibitId.value)
    if (!travelPlan.value) {
      resetPlanForm()
      resetBudgetForm()
      resetRecordForm()
      memoryFocusRequest.value = null
    }
  } catch (error) {
    setFeedback('', error.message)
  } finally {
    isLoading.value = false
  }
}

async function handleSelectSharedExhibit(shareId) {
  const normalizedShareId = String(shareId || '').trim()
  selectedSharedExhibitId.value = normalizedShareId
  if (!normalizedShareId) {
    selectedSharedExhibit.value = null
    return
  }

  isLoading.value = true
  try {
    selectedSharedExhibit.value = await fetchTravelSharedExhibit(normalizedShareId)
  } catch (error) {
    selectedSharedExhibitId.value = ''
    selectedSharedExhibit.value = null
    setFeedback('', error.message)
  } finally {
    isLoading.value = false
  }
}

watch(
  () => props.route,
  async (route, previousRoute) => {
    if (!props.integratedMode && route !== previousRoute && (route === 'travel-log' || route === 'photo-album')) {
      selectedPlanId.value = ''
      travelPlan.value = null
      memoryFocusRequest.value = null
    }
    const preferredPlanId = (
      route === 'travel-money' || props.integratedMode
        ? selectedPlanId.value
        : ''
    )
    await refreshTravelData(preferredPlanId, route === 'photo-album')
  },
)

watch(
  () => [moneyTab.value, selectedPlanId.value, recordGpsEnabled.value, editingRecordId.value],
  ([tab, planId, gpsEnabled, editingRecordId]) => {
    if (tab !== 'records' || !planId || !gpsEnabled || editingRecordId) {
      return
    }
    refreshRecordGpsAutofill()
  },
)

onMounted(async () => {
  await refreshTravelData()
})

async function handleSelectPlan(planId) {
  selectedPlanId.value = String(planId || '')
  memoryFocusRequest.value = null
  await refreshTravelData(selectedPlanId.value, props.route === 'photo-album')
  resetBudgetForm()
  resetRecordForm()
}

async function handleShareTravelPlan() {
  if (!selectedPlanId.value || !shareForm.recipientLoginId.trim()) return
  isSubmitting.value = true
  activeSubmit.value = 'share-plan'
  setFeedback()
  try {
    const response = await shareTravelPlan(selectedPlanId.value, {
      loginId: shareForm.recipientLoginId.trim(),
    })
    shareForm.recipientLoginId = ''
    setFeedback(`${response.recipientDisplayName} (${response.recipientLoginId}) 님에게 여행 전시를 공유했습니다.`)
  } catch (error) {
    setFeedback('', error.message)
  } finally {
    isSubmitting.value = false
    activeSubmit.value = ''
  }
}

function handleOpenTravelPlanner() {
  resetPlanForm()
  moneyTab.value = 'planner'
  if (props.integratedMode) {
    emit('request-open-finance')
    return
  }
  if (!props.integratedMode) {
    window.location.hash = 'travel-money'
  }
}

async function handleSubmitPlan() {
  isSubmitting.value = true
  activeSubmit.value = 'plan'
  setFeedback()
  try {
    let preferredPlanId = selectedPlanId.value
    if (planFormMode.value === 'edit' && selectedPlanId.value) {
      await updateTravelPlan(selectedPlanId.value, buildPlanPayload())
      preferredPlanId = selectedPlanId.value
      setFeedback('여행 정보를 수정했습니다.')
    } else {
      const created = await createTravelPlan(buildPlanPayload())
      preferredPlanId = String(created.id)
      setFeedback('새 여행을 만들었습니다.')
    }
    await refreshTravelData(preferredPlanId, props.route === 'photo-album')
    resetPlanForm()
    resetBudgetForm()
    resetRecordForm()
  } catch (error) {
    setFeedback('', error.message)
  } finally {
    isSubmitting.value = false
    activeSubmit.value = ''
  }
}

async function handleDeletePlan() {
  if (!selectedPlanId.value) return
  isSubmitting.value = true
  activeSubmit.value = 'delete-plan'
  setFeedback()
  try {
    await deleteTravelPlan(selectedPlanId.value)
    await refreshTravelData('', props.route === 'photo-album')
    resetPlanForm()
    resetBudgetForm()
    resetRecordForm()
    memoryFocusRequest.value = null
    setFeedback('여행을 삭제했습니다.')
  } catch (error) {
    setFeedback('', error.message)
  } finally {
    isSubmitting.value = false
    activeSubmit.value = ''
  }
}

async function handleSubmitBudgetItem() {
  if (!selectedPlanId.value) return
  isSubmitting.value = true
  activeSubmit.value = 'budget'
  setFeedback()
  try {
    if (editingBudgetItemId.value) {
      await updateTravelBudgetItem(editingBudgetItemId.value, buildBudgetPayload())
      setFeedback('예산 항목을 수정했습니다.')
    } else {
      await createTravelBudgetItem(selectedPlanId.value, buildBudgetPayload())
      setFeedback('예산 항목을 추가했습니다.')
    }
    await refreshTravelData(selectedPlanId.value, props.route === 'photo-album')
    resetBudgetForm()
  } catch (error) {
    setFeedback('', error.message)
  } finally {
    isSubmitting.value = false
    activeSubmit.value = ''
  }
}

async function handleDeleteBudgetItem(item) {
  isSubmitting.value = true
  activeSubmit.value = 'budget-delete'
  setFeedback()
  try {
    await deleteTravelBudgetItem(item.id)
    await refreshTravelData(selectedPlanId.value, props.route === 'photo-album')
    if (editingBudgetItemId.value === item.id) resetBudgetForm()
    setFeedback('예산 항목을 삭제했습니다.')
  } catch (error) {
    setFeedback('', error.message)
  } finally {
    isSubmitting.value = false
    activeSubmit.value = ''
  }
}

async function handleSubmitRecord() {
  if (!selectedPlanId.value) return
  isSubmitting.value = true
  activeSubmit.value = 'record'
  setFeedback()
  try {
    if (editingRecordId.value) {
      await updateTravelRecord(editingRecordId.value, buildRecordPayload())
      setFeedback('지출 장부를 수정했습니다.')
    } else {
      await createTravelRecord(selectedPlanId.value, buildRecordPayload())
      setFeedback('지출 장부를 추가했습니다.')
    }
    await refreshTravelData(selectedPlanId.value, props.route === 'photo-album')
    resetRecordForm()
  } catch (error) {
    setFeedback('', error.message)
  } finally {
    isSubmitting.value = false
    activeSubmit.value = ''
  }
}async function handleDeleteRecord(record) {
  isSubmitting.value = true
  activeSubmit.value = 'record-delete'
  setFeedback()
  try {
    await deleteTravelRecord(record.id)
    await refreshTravelData(selectedPlanId.value, props.route === 'photo-album')
    if (editingRecordId.value === record.id) resetRecordForm()
    setFeedback('지출 장부를 삭제했습니다.')
  } catch (error) {
    setFeedback('', error.message)
  } finally {
    isSubmitting.value = false
    activeSubmit.value = ''
  }
}

async function handleDeleteMedia(media) {
  if (!media?.id) return
  isSubmitting.value = true
  activeSubmit.value = 'media-delete'
  setFeedback()
  try {
    await deleteTravelMedia(media.id)
    await refreshTravelData(selectedPlanId.value, props.route === 'photo-album')
    setFeedback('첨부 파일을 삭제했습니다.')
  } catch (error) {
    setFeedback('', error.message)
  } finally {
    isSubmitting.value = false
    activeSubmit.value = ''
  }
}

async function handleSaveMemory(submission) {
  if (!selectedPlanId.value) return
  isSubmitting.value = true
  activeSubmit.value = 'memory'
  setFeedback()
  try {
    if (submission.batchItems?.length) {
      startMemoryUploadProgress(submission.batchItems.length, '선택한 사진을 순서대로 업로드하고 있습니다.')
      for (const [index, item] of submission.batchItems.entries()) {
        updateMemoryUploadProgress({
          current: index + 1,
          total: submission.batchItems.length,
          description: `${index + 1} / ${submission.batchItems.length}번째 사진을 업로드하고 있습니다.`,
        })
        const savedBatchMemory = await createTravelMemory(selectedPlanId.value, item.payload)
        await uploadTravelMemoryMedia(savedBatchMemory.id, [item.file], '', {
          onProgress: ({ phase }) => {
            if (phase === 'finalizing') {
              updateMemoryUploadProgress({
                current: index + 1,
                total: submission.batchItems.length,
                description: `${index + 1} / ${submission.batchItems.length}번째 사진을 기록에 연결하고 있습니다.`,
              })
            }
          },
        })
      }
      await refreshTravelData(selectedPlanId.value, true)
      memoryRefreshKey.value += 1
      setFeedback(`여행 기록 ${submission.batchItems.length}건을 저장했습니다.`)
      return
    }

    let saved
    if (submission.id) {
      saved = await updateTravelMemory(submission.id, submission.payload)
      setFeedback('여행 기록을 수정했습니다.')
    } else {
      saved = await createTravelMemory(selectedPlanId.value, submission.payload)
      setFeedback('여행 기록을 저장했습니다.')
    }
    if (submission.files?.length) {
      startMemoryUploadProgress(submission.files.length)
      await uploadTravelMemoryMedia(saved.id, submission.files, submission.caption || '', {
        onProgress: ({ phase, current, total, fileName }) => {
          if (phase === 'finalizing') {
            updateMemoryUploadProgress({
              current,
              total,
              description: '업로드한 사진을 기록에 연결하고 있습니다.',
            })
            return
          }

          updateMemoryUploadProgress({
            current,
            total,
            description: fileName
              ? `${fileName} 업로드를 진행하고 있습니다.`
              : '선택한 사진을 저장소 서버로 업로드하고 있습니다.',
          })
        },
      })
    }
    await refreshTravelData(selectedPlanId.value, true)
    memoryRefreshKey.value += 1
  } catch (error) {
    setFeedback('', error.message)
  } finally {
    resetMemoryUploadProgress()
    isSubmitting.value = false
    activeSubmit.value = ''
  }
}

async function handleDeleteMemory(memory) {
  isSubmitting.value = true
  activeSubmit.value = 'memory-delete'
  setFeedback()
  try {
    await deleteTravelMemory(memory.id)
    await refreshTravelData(selectedPlanId.value, true)
    if (String(memoryFocusRequest.value?.id || '') === String(memory.id)) memoryFocusRequest.value = null
    memoryRefreshKey.value += 1
    setFeedback('여행 기록을 삭제했습니다.')
  } catch (error) {
    setFeedback('', error.message)
  } finally {
    isSubmitting.value = false
    activeSubmit.value = ''
  }
}

async function handleSaveRoute(payload) {
  if (!selectedPlanId.value) return
  isSubmitting.value = true
  activeSubmit.value = 'route'
  setFeedback()
  try {
    const { id, gpxFiles = [], ...routePayload } = payload || {}
    const savedRoute = id
      ? await updateTravelRoute(id, routePayload)
      : await createTravelRoute(selectedPlanId.value, routePayload)
    if (gpxFiles.length) {
      await uploadTravelRouteGpxFiles(savedRoute.id, gpxFiles)
    }
    await refreshTravelData(selectedPlanId.value, props.route === 'photo-album')
    routeRefreshKey.value += 1
    setFeedback('이동 경로를 저장했습니다.')
  } catch (error) {
    setFeedback('', error.message)
  } finally {
    isSubmitting.value = false
    activeSubmit.value = ''
  }
}

async function handleDeleteRoute(route) {
  isSubmitting.value = true
  activeSubmit.value = 'route-delete'
  setFeedback()
  try {
    await deleteTravelRoute(route.id)
    await refreshTravelData(selectedPlanId.value, props.route === 'photo-album')
    routeRefreshKey.value += 1
    setFeedback('이동 경로를 삭제했습니다.')
  } catch (error) {
    setFeedback('', error.message)
  } finally {
    isSubmitting.value = false
    activeSubmit.value = ''
  }
}

function openMemoryEditor(memoryId) {
  if (!memoryId) return
  logTab.value = 'memories'
  memoryFocusRequest.value = {
    id: String(memoryId),
    token: Date.now(),
  }
  if (props.integratedPhotoMode) {
    emit('request-open-log')
    return
  }
  albumTab.value = 'upload'
}
</script>

<template>
  <div class="workspace-stack">
    <div v-if="feedback" class="feedback feedback--success">{{ feedback }}</div>
    <div v-if="errorMessage" class="feedback feedback--error">{{ errorMessage }}</div>

    <section class="panel">
      <div class="panel__header">
        <div>
          <h2>{{ workspaceMeta.title }}</h2>
          <p>{{ workspaceMeta.description }}</p>
        </div>
        <span v-if="travelPlan" class="panel__badge">{{ travelPlan.name }}</span>
      </div>

      <div class="travel-toolbar">
        <label class="field travel-toolbar__select">
          <span class="field__label">선택한 여행</span>
          <select :value="selectedPlanId" @change="handleSelectPlan($event.target.value)">
            <option value="">여행 선택</option>
            <option v-for="plan in travelPlans" :key="plan.id" :value="String(plan.id)">
              {{ plan.name }} / {{ plan.destination || '목적지 미정' }} / {{ planStatusLabel(plan.status) }}
            </option>
          </select>
        </label>

        <div class="travel-toolbar__actions">
          <button class="button button--ghost" :disabled="isLoading" @click="refreshTravelData(selectedPlanId, route === 'photo-album')">새로고침</button>
          <button class="button button--secondary" @click="route === 'travel-money' ? resetPlanForm() : handleOpenTravelPlanner()">{{ route === 'travel-money' ? '새 여행' : '여행 만들기' }}</button>
          <button class="button button--ghost" :disabled="!travelPlan" @click="fillPlanForm(travelPlan)">여행 수정</button>
        </div>
      </div>

      <div v-if="travelPlan" class="travel-plan-banner" :style="{ '--travel-plan-color': travelPlan.colorHex || '#3182F6' }">
        <strong>{{ travelPlan.destination || '목적지 미정' }}</strong>
        <span>{{ travelPlan.startDate }} - {{ travelPlan.endDate }}</span>
        <small>{{ planStatusLabel(travelPlan.status) }} / {{ travelPlan.headCount }}명 / {{ travelPlan.homeCurrency }}</small>
      </div>
      <p v-else class="panel__empty">{{ emptyTravelPlanMessage }}</p>
    </section>

    <template v-if="showPlanGate">
      <section v-if="travelPlans.length" class="panel">
        <div class="panel__header">
          <div>
            <h2>여행 선택</h2>
            <p>여행 로그를 열기 전에 이번에 볼 여행을 먼저 골라주세요.</p>
          </div>
          <span class="panel__badge">{{ travelPlans.length }}개 여행</span>
        </div>
        <div class="travel-plan-picker-grid">
          <button
            v-for="plan in travelPlans"
            :key="plan.id"
            class="travel-plan-picker-card"
            type="button"
            @click="handleSelectPlan(plan.id)"
          >
            <strong>{{ plan.name }}</strong>
            <span>{{ plan.destination || '목적지 미정' }}</span>
            <small>{{ formatDate(plan.startDate) }} - {{ formatDate(plan.endDate) }}</small>
            <small>{{ planStatusLabel(plan.status) }} / {{ formatCurrency(plan.actualTotalKrw) }}</small>
          </button>
        </div>
      </section>

      <section v-else class="panel">
        <div class="panel__header">
          <div>
            <h2>여행이 아직 없습니다</h2>
            <p>먼저 여행을 하나 만든 뒤 여행 로그, 경로, 사진 화면을 사용할 수 있습니다.</p>
          </div>
        </div>
        <div class="travel-plan-picker-empty">
          <p class="panel__empty">등록된 여행이 없어서 선택할 수 있는 항목이 없습니다.</p>
          <button class="button button--primary" type="button" @click="handleOpenTravelPlanner">여행 만들러 가기</button>
        </div>
      </section>
    </template>

    <template v-else>
    <section v-if="travelPlan" class="travel-summary-grid">
      <article class="travel-stat-card"><span>예산안</span><strong>{{ formatCurrency(travelPlan.plannedTotalKrw) }}</strong><small>{{ travelPlan.budgetItemCount }}개 항목</small></article>
      <article class="travel-stat-card"><span>실사용 금액</span><strong>{{ formatCurrency(travelPlan.actualTotalKrw) }}</strong><small>{{ travelPlan.recordCount }}개 지출 기록</small></article>
      <article class="travel-stat-card"><span>기록 + 미디어</span><strong>{{ travelPlan.memoryRecordCount }} / {{ travelPlan.mediaItemCount }}</strong><small>여행 기록 / 업로드 파일</small></article>
      <article class="travel-stat-card"><span>총 이동량</span><strong>{{ Number(travelPlan.totalDistanceKm || 0).toFixed(2) }} km</strong><small>{{ travelPlan.totalDurationMinutes || 0 }}분 / {{ Number(travelPlan.totalStepCount || 0).toLocaleString('ko-KR') }}걸음</small></article>
    </section>

    <template v-if="route === 'travel-money'">
      <section class="panel">
        <div class="scope-toggle">
          <button class="button" :class="{ 'button--primary': moneyTab === 'planner' }" @click="moneyTab = 'planner'">여행 설정</button>
          <button class="button" :class="{ 'button--primary': moneyTab === 'budget' }" @click="moneyTab = 'budget'">예약·예산</button>
          <button class="button" :class="{ 'button--primary': moneyTab === 'records' }" @click="moneyTab = 'records'">여행 가계부</button>
          <button class="button" :class="{ 'button--primary': moneyTab === 'stats' }" @click="moneyTab = 'stats'">통계</button>
        </div>
      </section>

      <div v-if="moneyTab === 'planner'" class="content-grid content-grid--travel">
        <section class="panel">
          <div class="panel__header"><div><h2>{{ planFormMode === 'edit' ? '여행 수정' : '여행 만들기' }}</h2><p>예정 여행, 다녀온 여행, 테스트 여행으로 상태를 나눠 관리할 수 있습니다.</p></div></div>
          <div class="travel-form-grid">
            <label class="field field--full"><span class="field__label">여행 이름</span><input v-model="planForm.name" type="text" placeholder="봄 일본 여행" /></label>
            <label class="field field--full"><span class="field__label">목적지</span><input v-model="planForm.destination" type="text" placeholder="오사카, 교토, 도쿄" /></label>
            <label class="field"><span class="field__label">시작일</span><input v-model="planForm.startDate" type="date" /></label>
            <label class="field"><span class="field__label">종료일</span><input v-model="planForm.endDate" type="date" /></label>
            <label class="field"><span class="field__label">기준 통화</span><input v-model="planForm.homeCurrency" type="text" maxlength="3" placeholder="KRW" /></label>
            <label class="field"><span class="field__label">인원 수</span><input v-model="planForm.headCount" type="number" min="1" step="1" /></label>
            <label class="field"><span class="field__label">상태</span><select v-model="planForm.status"><option v-for="option in planStatusOptions" :key="option" :value="option">{{ planStatusLabel(option) }}</option></select></label>
            <label class="field"><span class="field__label">색상</span><input v-model="planForm.colorHex" type="color" /></label>
            <label class="field field--full"><span class="field__label">메모</span><textarea v-model="planForm.memo" rows="3" placeholder="동행자, 여행 테마, 준비 메모를 적어두세요." /></label>
          </div>
          <div class="entry-editor__actions">
            <button class="button button--ghost" @click="resetPlanForm">초기화</button>
            <button class="button button--primary" :disabled="isSubmitting" @click="handleSubmitPlan">{{ isSubmitting && activeSubmit === 'plan' ? '저장 중...' : planFormMode === 'edit' ? '여행 수정 저장' : '여행 만들기' }}</button>
            <button class="button button--danger" :disabled="!travelPlan || isSubmitting" @click="handleDeletePlan">선택한 여행 삭제</button>
          </div>
        </section>

        <section class="panel">
          <div class="panel__header"><div><h2>여행 목록</h2><p>여행별로 목적지, 상태, 예산, 실제 사용 금액을 묶어서 비교할 수 있습니다.</p></div></div>
          <div class="sheet-table-wrap">
            <table class="sheet-table">
              <thead><tr><th>이름</th><th>목적지</th><th>상태</th><th>예산안</th><th>실사용</th><th>여행 기간</th></tr></thead>
              <tbody>
                <tr v-for="plan in travelPlans" :key="plan.id" @click="handleSelectPlan(plan.id)">
                  <td>{{ plan.name }}</td><td>{{ plan.destination || '-' }}</td><td>{{ planStatusLabel(plan.status) }}</td><td>{{ formatCurrency(plan.plannedTotalKrw) }}</td><td>{{ formatCurrency(plan.actualTotalKrw) }}</td><td>{{ formatDate(plan.startDate) }} - {{ formatDate(plan.endDate) }}</td>
                </tr>
                <tr v-if="!travelPlans.length"><td colspan="6" class="sheet-table__empty">등록된 여행이 아직 없습니다.</td></tr>
              </tbody>
            </table>
          </div>
        </section>
      </div>

      <div v-else-if="moneyTab === 'budget'" class="content-grid content-grid--travel">
        <section class="panel">
          <div class="panel__header"><div><h2>{{ editingBudgetItemId ? '예산 항목 수정' : '예산 항목 추가' }}</h2><p>교통, 숙소, 식비, 쇼핑 등 예상 비용을 항목별로 먼저 잡아둘 수 있습니다.</p></div></div>
          <div class="travel-form-grid">
            <label class="field"><span class="field__label">분류</span><input v-model="budgetForm.category" list="budget-category-options" type="text" /></label>
            <label class="field"><span class="field__label">통화</span><input v-model="budgetForm.currencyCode" type="text" maxlength="3" /></label>
            <label class="field field--full"><span class="field__label">항목명</span><input v-model="budgetForm.title" type="text" placeholder="호텔, JR 패스, 현지 유심" /></label>
            <label class="field"><span class="field__label">금액</span><input :value="budgetAmountInput" type="text" inputmode="numeric" placeholder="0" @input="handleBudgetAmountInput($event.target.value)" /></label>
            <label class="field field--full"><span class="field__label">메모</span><textarea v-model="budgetForm.memo" rows="3" placeholder="예산 항목에 대한 간단한 설명을 남겨두세요." /></label>
          </div>
          <datalist id="budget-category-options"><option v-for="option in budgetCategoryOptions" :key="option" :value="option" /></datalist>
          <div class="entry-editor__actions">
            <button class="button button--ghost" @click="resetBudgetForm">초기화</button>
            <button class="button button--primary" :disabled="isSubmitting || !travelPlan" @click="handleSubmitBudgetItem">{{ isSubmitting && activeSubmit === 'budget' ? '저장 중...' : editingBudgetItemId ? '예산 수정' : '예산 추가' }}</button>
          </div>
        </section>

        <section class="panel">
          <div class="panel__header"><div><h2>예산안 표</h2><p>각 예산 항목은 원래 통화와 KRW 환산 금액을 함께 보관해서 비교하기 쉽습니다.</p></div></div>
          <div class="sheet-table-wrap">
            <table class="sheet-table">
              <thead><tr><th>분류</th><th>항목명</th><th>원통화</th><th>KRW</th><th>메모</th><th>작업</th></tr></thead>
              <tbody>
                <tr v-for="item in travelPlan?.budgetItems ?? []" :key="item.id"><td>{{ item.category }}</td><td>{{ item.title }}</td><td>{{ formatCurrencyByCode(item.amount, item.currencyCode) }}</td><td>{{ formatCurrency(item.amountKrw) }}</td><td>{{ item.memo || '-' }}</td><td class="sheet-table__actions"><button class="button button--ghost" @click="fillBudgetForm(item)">수정</button><button class="button button--danger" @click="handleDeleteBudgetItem(item)">삭제</button></td></tr>
                <tr v-if="!(travelPlan?.budgetItems ?? []).length"><td colspan="6" class="sheet-table__empty">예산 항목이 아직 없습니다.</td></tr>
              </tbody>
            </table>
          </div>
        </section>
      </div>      <div v-else-if="moneyTab === 'records'" class="workspace-stack travel-money-records">
        <section class="panel travel-money-records__summary">
          <div class="panel__header">
            <div>
              <h2>여행 날짜 가계부</h2>
              <p>월 달력 대신 선택한 여행 기간만큼 날짜를 펼쳐 두고, 이동 중에는 하루 단위로 바로 지출을 적을 수 있게 구성했습니다.</p>
            </div>
            <span class="panel__badge">{{ tripRecordDays.length }}일</span>
          </div>
          <div class="scope-toggle scope-toggle--wrap">
            <button class="button" :class="{ 'button--primary': !selectedRecordDate }" @click="handleSelectRecordDate('')">전체 보기</button>
            <button
              v-for="day in tripRecordDays"
              :key="day.date"
              class="button"
              :class="{ 'button--primary': selectedRecordDate === day.date }"
              @click="handleSelectRecordDate(day.date)"
            >
              {{ day.dayLabel }}
            </button>
          </div>
          <div v-if="tripRecordDays.length" class="travel-ledger-grid">
            <button
              v-for="day in tripRecordDays"
              :key="`ledger-${day.date}`"
              type="button"
              class="travel-ledger-card"
              :class="{ 'travel-ledger-card--active': selectedRecordDate === day.date }"
              @click="handleSelectRecordDate(day.date)"
            >
              <small>{{ day.dayLabel }}</small>
              <strong>{{ formatCurrency(day.totalKrw) }}</strong>
              <span>지출 {{ day.count }}건</span>
              <span>{{ day.currencyLabel || 'KRW 환산 기준' }}</span>
            </button>
          </div>
          <p v-else class="panel__empty">선택한 여행의 기간 정보가 없어서 날짜형 장부를 만들 수 없습니다.</p>
        </section>
        <div class="content-grid content-grid--travel content-grid--travel-records travel-money-records__entry">
          <section class="panel travel-money-records__form">
            <div class="panel__header"><div><h2>{{ editingRecordId ? '지출 기록 수정' : '지출 기록 추가' }}</h2><p>언제 어디서 얼마를 썼는지와 위치를 함께 기록해 여행 지출 장부를 정리합니다.</p></div></div>
            <div class="travel-form-grid">
              <label class="field"><span class="field__label">날짜</span><input v-model="recordForm.expenseDate" type="date" /></label>
              <label class="field"><span class="field__label">시간</span><input v-model="recordForm.expenseTime" type="time" /><small class="field__hint">비워두면 00:00으로 저장됩니다.</small></label>
              <label class="field"><span class="field__label">분류</span><input v-model="recordForm.category" list="expense-category-options" type="text" /></label>
              <label class="field"><span class="field__label">통화</span><input v-model="recordForm.currencyCode" type="text" maxlength="3" /></label>
              <label class="field field--full"><span class="field__label">항목명</span><input v-model="recordForm.title" type="text" placeholder="도톤보리 점심, 지하철 충전, 기념품 구매" /></label>
              <label class="field"><span class="field__label">금액</span><input :value="recordAmountInput" type="text" inputmode="decimal" placeholder="0" @input="handleRecordAmountInput($event.target.value)" /></label>
              <label class="field field--full"><span class="field__label">장소명</span><input v-model="recordForm.placeName" list="travel-place-options" type="text" placeholder="도톤보리" /></label>
              <label class="field"><span class="field__label">위도</span><input v-model="recordForm.latitude" type="number" step="0.0000001" /></label>
              <label class="field"><span class="field__label">경도</span><input v-model="recordForm.longitude" type="number" step="0.0000001" /></label>
              <div class="field field--full">
                <span class="field__label">현재 위치(GPS)</span>
                <div class="scope-toggle">
                  <button type="button" class="button" :class="{ 'button--primary': recordGpsEnabled }" @click="setRecordGpsEnabled(true)">
                    {{ recordGpsLoading && recordGpsEnabled ? '위치 확인 중...' : '위치 켜짐' }}
                  </button>
                  <button type="button" class="button" :class="{ 'button--primary': !recordGpsEnabled }" @click="setRecordGpsEnabled(false)">위치 꺼짐</button>
                </div>
                <small class="field__hint">기본값은 켜짐이며, 새 지출 기록에서 현재 위치를 위도와 경도에 자동 입력합니다.</small>
              </div>
              <label class="field field--full"><span class="field__label">메모</span><textarea v-model="recordForm.memo" rows="3" placeholder="쿠폰 사용 여부, 결제 메모, 지출 이유를 남겨두세요." /></label>
            </div>
            <datalist id="expense-category-options"><option v-for="option in expenseCategoryOptions" :key="option" :value="option" /></datalist>
            <datalist id="travel-place-options"><option v-for="option in travelCategories.places" :key="option" :value="option" /></datalist>
            <p class="travel-autofill-note" :class="recordAutofillMessageClass">{{ recordAutofillMessage }}</p>
            <div class="entry-editor__actions">
              <button class="button button--ghost" @click="resetRecordForm">초기화</button>
              <button class="button button--primary" :disabled="isSubmitting || !travelPlan" @click="handleSubmitRecord">{{ isSubmitting && activeSubmit === 'record' ? '저장 중...' : editingRecordId ? '지출 기록 수정' : '지출 기록 추가' }}</button>
            </div>
          </section>

          <section class="panel panel--map-fill travel-money-records__map">
            <div class="panel__header"><div><h2>지출 지도</h2><p>지출이 발생한 도시와 장소를 핀으로 남겨 어디에서 돈을 많이 썼는지 빠르게 볼 수 있습니다.</p></div></div>
            <TravelMapPanel :markers="recordMarkers" :selected-point="recordForm.latitude && recordForm.longitude ? { latitude: Number(recordForm.latitude), longitude: Number(recordForm.longitude) } : null" :enable-pick-location="true" :enable-draw-route="false" :draggable-selected-point="true" :view-key="travelPlan?.id || 'travel-record-map'" hint-title="지출 위치 찍기" hint-text="지도를 눌러 지출 위치를 저장하거나, 현재 선택 핀을 드래그해 세부 위치를 맞출 수 있습니다." @pick-location="applyRecordPickedLocation" @move-selected-point="handleMoveSelectedRecordPoint" />
          </section>
        </div>

        <section class="panel travel-money-records__table">
          <div class="panel__header"><div><h2>지출 장부 표</h2><p>금액, 분류, 장소를 함께 정리해 이후 통계 화면에서 바로 집계할 수 있습니다.</p></div></div>
          <div class="sheet-table-wrap">
            <table class="sheet-table">
              <thead><tr><th>날짜</th><th>분류</th><th>항목명</th><th>장소</th><th>원통화</th><th>KRW</th><th>작업</th></tr></thead>
              <tbody>
                <tr v-for="record in filteredTravelRecords" :key="record.id"><td>{{ formatDateTime(record.expenseDate, record.expenseTime) }}</td><td>{{ record.category }}</td><td>{{ record.title }}</td><td>{{ formatTravelLocationLabel(record) }}</td><td>{{ formatCurrencyByCode(record.amount, record.currencyCode) }}</td><td>{{ formatCurrency(record.amountKrw) }}</td><td class="sheet-table__actions"><button class="button button--ghost" @click="fillRecordForm(record)">수정</button><button class="button button--danger" @click="handleDeleteRecord(record)">삭제</button></td></tr>
                <tr v-if="!filteredTravelRecords.length"><td colspan="7" class="sheet-table__empty">{{ selectedRecordDate ? '선택한 날짜에는 지출 기록이 없습니다.' : '지출 기록이 아직 없습니다.' }}</td></tr>
              </tbody>
            </table>
          </div>
        </section>
      </div>

      <div v-else class="workspace-stack">
        <div class="content-grid content-grid--travel">
          <section class="panel"><div class="panel__header"><div><h2>카테고리별 예산안과 실제 사용 금액</h2><p>같은 여행 안에서 예산 항목과 실제 지출 항목을 묶어 초과 지출 여부를 바로 확인할 수 있습니다.</p></div></div><div class="sheet-table-wrap"><table class="sheet-table"><thead><tr><th>카테고리</th><th>예산안</th><th>실사용</th></tr></thead><tbody><tr v-for="row in budgetByCategory" :key="row.label"><td>{{ row.label }}</td><td>{{ formatCurrency(row.totalKrw) }}</td><td>{{ formatCurrency(actualByCategory.find((item) => item.label === row.label)?.totalKrw || 0) }}</td></tr><tr v-if="!budgetByCategory.length && !actualByCategory.length"><td colspan="3" class="sheet-table__empty">카테고리별 데이터가 아직 없습니다.</td></tr></tbody></table></div></section>
          <section class="panel"><div class="panel__header"><div><h2>장소별 지출</h2><p>입력한 장소명이나 좌표를 기준으로 어느 곳에서 얼마나 썼는지 집계합니다.</p></div></div><div class="sheet-table-wrap"><table class="sheet-table"><thead><tr><th>장소</th><th>KRW 합계</th><th>건수</th></tr></thead><tbody><tr v-for="row in spendingLocations" :key="row.key"><td>{{ row.label }}</td><td>{{ formatCurrency(row.totalKrw) }}</td><td>{{ row.count }}</td></tr><tr v-if="!spendingLocations.length"><td colspan="3" class="sheet-table__empty">장소별 지출 데이터가 아직 없습니다.</td></tr></tbody></table></div></section>
        </div>
        <section class="panel"><div class="panel__header"><div><h2>여행 돈 장부 환율 정보</h2><p>예산안과 지출 장부에 사용된 통화는 백엔드에서 KRW 환산 환율을 계산해 함께 보여줍니다.</p></div></div><div class="travel-rate-grid"><article v-for="rate in travelRates" :key="rate.currencyCode" class="travel-rate-card"><strong>{{ rate.currencyCode }}</strong><span>{{ rate.available ? '사용 가능' : '없음' }}</span><small>{{ rate.rateToKrw ? `${formatCurrency(rate.rateToKrw)} / 1단위` : '-' }}</small></article></div></section>
      </div>
    </template>

    <template v-else-if="route === 'travel-log'">
      <section class="panel"><div class="scope-toggle"><button class="button" :class="{ 'button--primary': logTab === 'overview' }" @click="logTab = 'overview'">여행 보기</button><button class="button" :class="{ 'button--primary': logTab === 'memories' }" @click="logTab = 'memories'">여행 기록</button><button class="button" :class="{ 'button--primary': logTab === 'routes' }" @click="logTab = 'routes'">이동 경로</button></div></section>
      <section class="panel">
        <div class="panel__header">
          <div>
            <h2>여행 전시 공유</h2>
            <p>완성된 여행 로그만 다른 사용자에게 아이디 기준으로 공유할 수 있습니다. 공유받은 사람은 여행 사진 탭에서 읽기 전용 전시 페이지로 확인합니다.</p>
          </div>
          <span class="panel__badge">{{ canShareTravelPlan ? '공유 가능' : '완성 후 공유' }}</span>
        </div>
        <div class="travel-toolbar">
          <label class="field travel-toolbar__select">
            <span class="field__label">공유 받을 아이디</span>
            <input v-model="shareForm.recipientLoginId" type="text" placeholder="예: minsu" />
          </label>
          <div class="travel-toolbar__actions">
            <button class="button button--primary" :disabled="isSubmitting || !canShareTravelPlan || !shareForm.recipientLoginId.trim()" @click="handleShareTravelPlan">
              {{ isSubmitting && activeSubmit === 'share-plan' ? '공유 중...' : '전시 공유' }}
            </button>
          </div>
        </div>
        <small class="field__hint">{{ canShareTravelPlan ? '공유된 전시는 상대방 계정의 여행 사진 > 공유 전시에서 수정 없이 감상됩니다.' : '여행 상태가 완료(COMPLETED)일 때만 공유할 수 있습니다.' }}</small>
      </section>
      <TravelOverviewWorkspace v-if="logTab === 'overview'" :travel-plan="travelPlan" />
      <TravelMemoryPanel v-else-if="logTab === 'memories'" :travel-plan="travelPlan" :category-options="memoryCategoryOptions" :is-submitting="isSubmitting" :active-submit="activeSubmit" :refresh-key="memoryRefreshKey" :focus-request="memoryFocusRequest" :upload-progress="memoryUploadProgress" @save-memory="handleSaveMemory" @delete-memory="handleDeleteMemory" @delete-media="handleDeleteMedia" />
      <TravelRouteWorkspace v-else :travel-plan="travelPlan" :is-submitting="isSubmitting" :active-submit="activeSubmit" :refresh-key="routeRefreshKey" @save-route="handleSaveRoute" @delete-route="handleDeleteRoute" />
    </template>

    <template v-else-if="route === 'photo-album'">
      <section class="panel">
        <div class="scope-toggle scope-toggle--wrap">
          <button
            v-for="item in photoAlbumTabChoices"
            :key="item.key"
            class="button"
            :class="{ 'button--primary': albumTab === item.key }"
            @click="albumTab = item.key"
          >
            {{ item.label }}
          </button>
        </div>
        <small v-if="integratedPhotoMode" class="field__hint">사진 업로드와 기록 편집은 여행 로그에서 하고, 여기서는 지도와 사진첩 중심으로 다시 모아 봅니다.</small>
      </section>
      <TravelMemoryPanel v-if="showAlbumUploadTab && albumTab === 'upload'" :travel-plan="travelPlan" :category-options="memoryCategoryOptions" :is-submitting="isSubmitting" :active-submit="activeSubmit" :refresh-key="memoryRefreshKey" :focus-request="memoryFocusRequest" :upload-progress="memoryUploadProgress" @save-memory="handleSaveMemory" @delete-memory="handleDeleteMemory" @delete-media="handleDeleteMedia" />
      <div v-else-if="albumTab === 'gallery'" class="workspace-stack">
        <section class="panel"><div class="panel__header"><div><h2>사진 재사용 흐름</h2><p>갤러리 카드에서 바로 기록 편집을 누르면 업로드 화면이 열리고, 기존 사진은 그대로 유지된 채 새 사진과 메모만 이어서 추가할 수 있습니다.</p></div><span class="panel__badge">{{ photoAlbumCards.length }}개 기록</span></div></section>
        <section class="panel panel--map-fill"><div class="panel__header"><div><h2>사진첩 지도</h2><p>선택한 여행의 사진 기록이 위치별로 묶여 큰 지도에 표시됩니다.</p></div><span class="panel__badge">{{ photoAlbumPhotoCount }}장</span></div><TravelMapPanel :markers="photoAlbumMarkers" :selected-point="null" :enable-pick-location="false" :enable-draw-route="false" :view-key="travelPlan?.id || 'photo-album-map'" hint-title="사진 핀 보기" hint-text="여행 기록에 연결된 사진을 위치별로 묶어 보여줍니다." /></section>
        <section class="panel">
          <div class="panel__header">
            <div>
              <h2>사진첩 카드</h2>
              <p>여행 로그에서 올린 사진을 여행, 시간, 국가, 지역 기준으로 다시 묶어 보고 필요할 때만 여행 로그 편집으로 이동합니다.</p>
            </div>
          </div>
          <div class="scope-toggle scope-toggle--wrap">
            <button class="button" :class="{ 'button--primary': photoAlbumGroupMode === 'all' }" @click="photoAlbumGroupMode = 'all'">전체 보기</button>
            <button class="button" :class="{ 'button--primary': photoAlbumGroupMode === 'travel' }" @click="photoAlbumGroupMode = 'travel'">여행별</button>
            <button class="button" :class="{ 'button--primary': photoAlbumGroupMode === 'time' }" @click="photoAlbumGroupMode = 'time'">시간순</button>
            <button class="button" :class="{ 'button--primary': photoAlbumGroupMode === 'country' }" @click="photoAlbumGroupMode = 'country'">국가별</button>
            <button class="button" :class="{ 'button--primary': photoAlbumGroupMode === 'region' }" @click="photoAlbumGroupMode = 'region'">지역별</button>
          </div>
          <div v-if="photoAlbumCards.length" class="travel-photo-groups">
            <section v-for="group in photoAlbumGroups" :key="group.key" class="travel-photo-group">
              <div class="travel-photo-group__header">
                <strong>{{ group.label }}</strong>
                <small>{{ group.items.length }}개 기록 / 사진 {{ group.items.reduce((total, item) => total + item.photoCount, 0) }}장</small>
              </div>
              <div class="travel-media-grid travel-media-grid--gallery">
                <article v-for="(item, index) in group.items" :key="item.id" class="travel-media-card">
                  <img v-if="item.heroPhotoUrl" :src="buildThumbnailUrl(item.heroPhotoUrl)" :alt="item.caption || item.title" :loading="index < 2 ? 'eager' : 'lazy'" :fetchpriority="index < 2 ? 'high' : 'auto'" decoding="async" class="travel-media-thumb" />
                  <div v-else class="travel-media-thumb travel-media-thumb--receipt">사진 없음</div>
                  <div class="travel-media-copy">
                    <div class="travel-media-tags">
                      <span class="chip chip--neutral">{{ item.planName || '여행' }}</span>
                      <span class="chip chip--neutral">사진 {{ item.photoCount }}장</span>
                    </div>
                    <strong>{{ item.title }}</strong>
                    <small>{{ formatDateTime(item.memoryDate, item.memoryTime) }}</small>
                    <small>{{ item.locationLabel }}</small>
                    <small>{{ item.memo || '이 기록을 다시 열면 기존 사진이 남아 있는 상태에서 이어서 편집할 수 있습니다.' }}</small>
                  </div>
                  <div class="travel-media-actions">
                    <button class="button button--primary" @click="openMemoryEditor(item.memoryId)">기록 편집</button>
                    <a v-if="item.heroPhotoUrl" class="button button--ghost" :href="item.heroPhotoUrl" target="_blank" rel="noreferrer">대표 사진 열기</a>
                    <button class="button button--danger" :disabled="!item.heroPhoto" @click="handleDeleteMedia(item.heroPhoto)">대표 사진 삭제</button>
                  </div>
                </article>
              </div>
            </section>
          </div>
          <p v-else class="panel__empty">사진첩에 표시할 사진이 아직 없습니다.</p>
        </section>
      </div>
      <TravelSharedExhibitWorkspace v-else-if="albumTab === 'shared'" :exhibits="sharedExhibitSummaries" :exhibit-page="sharedExhibitPage" :exhibit-page-count="sharedExhibitPageCount" :exhibit-total="sharedExhibitTotal" :selected-exhibit-id="selectedSharedExhibitId" :selected-exhibit="selectedSharedExhibit" :is-loading="isLoading" @select-exhibit="handleSelectSharedExhibit" @change-exhibit-page="handleChangeSharedExhibitPage" />
      <TravelCommunityWorkspace v-else :travel-plan="travelPlan" :community-feed="communityFeed" :community-page="communityFeedPage" :community-page-count="communityFeedPageCount" :community-total="communityFeedTotal" @change-community-page="handleChangeCommunityFeedPage" />
    </template>
    </template>
  </div>
</template>
