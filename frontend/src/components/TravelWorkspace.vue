<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { formatCurrency, formatCurrencyByCode, formatShortDate, formatTime } from '../lib/format'
import * as travelUtils from '../lib/travel'
import { extractPhotoMetadata } from '../lib/photoMetadata'
import BarChartCard from './BarChartCard.vue'
import DonutChartCard from './DonutChartCard.vue'
import TravelMapPanel from './TravelMapPanel.vue'
import TravelMemoryPanel from './TravelMemoryPanel.vue'

const chartPalette = ['#3182f6', '#12b886', '#f59f00', '#ff6b6b', '#7c5cff', '#00b8d9', '#fd7e14', '#5c7cfa']
const planColorPresets = ['#3182F6', '#12B886', '#F59F00', '#FF6B6B', '#7C5CFF', '#00B8D9', '#FD7E14', '#5C7CFA']
const {
  buildKrwGuideAmount,
  buildTravelAnalytics,
  buildTravelGalleryItems,
  convertFromKrw,
  convertToKrw,
  resolveTravelRate,
  travelBudgetCategoryOptions,
  travelCurrencyOptions,
} = travelUtils

const props = defineProps({
  route: { type: String, required: true },
  travelPlans: { type: Array, default: () => [] },
  selectedPlanId: { type: String, default: '' },
  travelPlan: { type: Object, default: null },
  travelPortfolio: { type: Object, default: null },
  travelRates: { type: Array, default: () => [] },
  travelStatsScope: { type: String, default: 'PLAN' },
  planForm: { type: Object, required: true },
  budgetForm: { type: Object, required: true },
  recordForm: { type: Object, required: true },
  recordFormVersion: { type: Number, default: 0 },
  memoryForm: { type: Object, required: true },
  memoryFormVersion: { type: Number, default: 0 },
  planEditorMode: { type: String, default: 'create' },
  editingBudgetItemId: { type: [Number, String, null], default: null },
  editingRecordId: { type: [Number, String, null], default: null },
  editingMemoryId: { type: [Number, String, null], default: null },
  isSubmitting: { type: Boolean, default: false },
  activeSubmit: { type: String, default: '' },
})

const emit = defineEmits([
  'select-plan',
  'start-plan-create',
  'submit-plan',
  'delete-plan',
  'refresh-rates',
  'submit-budget-item',
  'edit-budget-item',
  'reset-budget-item',
  'delete-budget-item',
  'submit-record',
  'edit-record',
  'reset-record',
  'delete-record',
  'submit-memory',
  'edit-memory',
  'reset-memory',
  'delete-memory',
  'change-stats-scope',
  'upload-media',
  'upload-memory-media',
  'delete-media',
])

const splitForm = reactive({
  extraAmount: '',
  peopleCount: 2,
})

const exchangeForm = reactive({
  amount: '1000',
  fromCurrency: 'KRW',
  toCurrency: 'USD',
})

const recordLocationFilter = reactive({
  country: '',
  region: '',
})

const galleryFilter = ref('ALL')
const mediaCaptions = reactive({
  PHOTO: '',
  RECEIPT: '',
})
const photoFiles = ref([])
const receiptFiles = ref([])
const photoAutofillState = reactive({
  status: 'idle',
  message: '?ъ쭊???좏깮?섎㈃ 珥ъ쁺 ?쒓컙怨??꾩튂 ?뺣낫媛 ?덉쑝硫??먮룞?쇰줈 梨꾩썙吏묐땲??',
  fileName: '',
})

const mediaLibraryRoutes = ['travel-gallery', 'travel-photos', 'travel-receipts']

const planAnalytics = computed(() => buildTravelAnalytics(props.travelPlan, props.travelRates))
const scopedTravelData = computed(() => (props.travelStatsScope === 'ALL' ? props.travelPortfolio : props.travelPlan))
const scopedAnalytics = computed(() => buildTravelAnalytics(scopedTravelData.value, props.travelRates))
const settingsCurrencyCode = computed(() => props.travelPlan?.homeCurrency || props.planForm.homeCurrency || 'USD')

const rateCards = computed(() =>
  props.travelRates
    .slice()
    .sort((a, b) => a.currencyCode.localeCompare(b.currencyCode))
    .map((item) => ({
      ...item,
      rate: item.rateToKrw ? Number(item.rateToKrw) : 0,
      krwGuideAmount: item.currencyCode === 'KRW' ? 1000 : buildKrwGuideAmount(item.rateToKrw, 1000),
    })),
)

const plannedCategoryChart = computed(() =>
  scopedAnalytics.value.plannedByCategory.map((item, index) => ({
    label: item.label,
    value: item.value,
    caption: `${item.count}媛???ぉ`,
    color: chartPalette[index % chartPalette.length],
  })),
)

const actualCategoryChart = computed(() =>
  scopedAnalytics.value.actualByCategory.map((item, index) => ({
    label: item.label,
    value: item.value,
    caption: `${item.count}嫄?湲곕줉`,
    color: chartPalette[index % chartPalette.length],
  })),
)

const dailyChart = computed(() =>
  scopedAnalytics.value.dailyTotals.map((item, index) => ({
    label: formatShortDate(item.label),
    value: item.value,
    caption: `${item.count}嫄?,
    color: chartPalette[index % chartPalette.length],
  })),
)

const currencyChart = computed(() =>
  scopedAnalytics.value.currencyBreakdown.map((item, index) => ({
    label: item.label,
    value: item.value,
    caption: `${formatCurrencyByCode(item.originalTotal, item.label)} / ${
      item.rateAvailable ? `1${item.label}=${formatCurrency(item.currentRateToKrw)}` : '?섏쑉 ?뺤씤 ?꾩슂'
    }`,
    color: chartPalette[index % chartPalette.length],
  })),
)

const selectedLocation = computed(() => {
  if (props.recordForm.latitude && props.recordForm.longitude) {
    return {
      latitude: Number(props.recordForm.latitude),
      longitude: Number(props.recordForm.longitude),
    }
  }
  return null
})

function normalizeLocationLabel(value) {
  return String(value || '').trim()
}

function normalizeColorHex(value, fallback = '#3182F6') {
  return /^#[0-9A-Fa-f]{6}$/.test(String(value || '').trim()) ? String(value).trim().toUpperCase() : fallback
}

const planLocationCategories = computed(() => {
  const countryMap = new Map()
  const regionMap = new Map()

  ;(props.travelPlan?.records ?? []).forEach((record) => {
    const country = normalizeLocationLabel(record.country)
    const region = normalizeLocationLabel(record.region)
    const amountKrw = Number(record.amountKrw ?? 0)

    if (country) {
      const currentCountry = countryMap.get(country) ?? { label: country, count: 0, totalKrw: 0 }
      currentCountry.count += 1
      currentCountry.totalKrw += amountKrw
      countryMap.set(country, currentCountry)
    }

    if (region) {
      const regionKey = `${country}::${region}`
      const currentRegion = regionMap.get(regionKey) ?? {
        country,
        label: region,
        count: 0,
        totalKrw: 0,
      }
      currentRegion.count += 1
      currentRegion.totalKrw += amountKrw
      regionMap.set(regionKey, currentRegion)
    }
  })

  const sorter = (left, right) => right.count - left.count || left.label.localeCompare(right.label)

  return {
    countries: [...countryMap.values()].sort(sorter),
    regions: [...regionMap.values()].sort(sorter),
  }
})

const recordCountryOptions = computed(() => planLocationCategories.value.countries)

const recordRegionOptions = computed(() => {
  const selectedCountry = normalizeLocationLabel(recordLocationFilter.country)
  return planLocationCategories.value.regions.filter((item) => !selectedCountry || item.country === selectedCountry)
})

const formCountryOptions = computed(() => {
  const labels = new Set(recordCountryOptions.value.map((item) => item.label))
  const currentCountry = normalizeLocationLabel(props.recordForm.country)
  if (currentCountry) {
    labels.add(currentCountry)
  }
  return [...labels]
})

const formRegionOptions = computed(() => {
  const selectedCountry = normalizeLocationLabel(props.recordForm.country)
  const labels = new Set(
    planLocationCategories.value.regions
      .filter((item) => !selectedCountry || item.country === selectedCountry)
      .map((item) => item.label),
  )
  const currentRegion = normalizeLocationLabel(props.recordForm.region)
  if (currentRegion) {
    labels.add(currentRegion)
  }
  return [...labels]
})

const filteredTravelRecords = computed(() =>
  (props.travelPlan?.records ?? []).filter((record) => {
    const matchesCountry =
      !recordLocationFilter.country || normalizeLocationLabel(record.country) === normalizeLocationLabel(recordLocationFilter.country)
    const matchesRegion =
      !recordLocationFilter.region || normalizeLocationLabel(record.region) === normalizeLocationLabel(recordLocationFilter.region)
    return matchesCountry && matchesRegion
  }),
)

const recordMediaCountMap = computed(() => {
  const bucket = new Map()
  ;(props.travelPlan?.mediaItems ?? []).forEach((item) => {
    const key = String(item.recordId)
    const current = bucket.get(key) ?? { total: 0, photos: 0, receipts: 0 }
    current.total += 1
    if (item.mediaType === 'RECEIPT') {
      current.receipts += 1
    } else {
      current.photos += 1
    }
    bucket.set(key, current)
  })
  return bucket
})

const editingRecordMedia = computed(() =>
  (props.travelPlan?.mediaItems ?? []).filter((item) => String(item.recordId) === String(props.editingRecordId)),
)

const editingRecordPhotos = computed(() => editingRecordMedia.value.filter((item) => item.mediaType === 'PHOTO'))
const editingRecordReceipts = computed(() => editingRecordMedia.value.filter((item) => item.mediaType === 'RECEIPT'))

const isMediaLibraryRoute = computed(() => mediaLibraryRoutes.includes(props.route))

const activeGalleryFilter = computed(() => {
  if (props.route === 'travel-photos') {
    return 'PHOTO'
  }
  if (props.route === 'travel-receipts') {
    return 'RECEIPT'
  }
  return galleryFilter.value
})

const allGalleryItems = computed(() => buildTravelGalleryItems(scopedTravelData.value))

const galleryItems = computed(() => {
  if (activeGalleryFilter.value === 'ALL') {
    return allGalleryItems.value
  }
  return allGalleryItems.value.filter((item) => item.mediaType === activeGalleryFilter.value)
})

const gallerySummary = computed(() => {
  const items = allGalleryItems.value
  return {
    total: items.length,
    photos: items.filter((item) => item.mediaType === 'PHOTO').length,
    receipts: items.filter((item) => item.mediaType === 'RECEIPT').length,
  }
})

const galleryPageMeta = computed(() => {
  if (props.route === 'travel-photos') {
    return {
      title: '?ы뻾 ?ъ쭊',
      description: '?ы뻾 以?李띿? ?ъ쭊留??곕줈 紐⑥븘 ?μ냼, ?쒓컙, ?낅줈??湲곗??쇰줈 愿由ы빀?덈떎.',
      count: gallerySummary.value.photos,
      emptyText: '?낅줈?쒗븳 ?ы뻾 ?ъ쭊???놁뒿?덈떎.',
    }
  }

  if (props.route === 'travel-receipts') {
    return {
      title: '?곸닔利?蹂닿???,
      description: '寃곗젣 ?곸닔利앷낵 利앸튃 ?대?吏留?遺꾨━?댁꽌 湲곕줉蹂꾨줈 ?뺤씤?섍퀬 愿由ы빀?덈떎.',
      count: gallerySummary.value.receipts,
      emptyText: '?낅줈?쒗븳 ?곸닔利앹씠 ?놁뒿?덈떎.',
    }
  }

  return {
    title: '?ы뻾 媛ㅻ윭由?,
    description: '?ы뻾蹂??먮뒗 ?꾩껜 ?ы뻾 湲곗??쇰줈 ?낅줈?쒗븳 ?ъ쭊怨??곸닔利앹쓣 ?④퍡 紐⑥븘 遊낅땲??',
    count: gallerySummary.value.total,
    emptyText: '?낅줈?쒗븳 ?ъ쭊?대굹 ?곸닔利앹씠 ?놁뒿?덈떎.',
  }
})

const showGalleryFilterChips = computed(() => props.route === 'travel-gallery')

const representativePlanCards = computed(() => {
  const sourcePlans = props.travelPortfolio?.plans?.length
    ? props.travelPortfolio.plans
    : props.travelPlan
      ? [props.travelPlan]
      : []
  const sourceMedia = props.travelPortfolio?.mediaItems?.length
    ? buildTravelGalleryItems(props.travelPortfolio)
    : allGalleryItems.value

  const mediaByPlan = new Map()
  sourceMedia.forEach((item) => {
    const key = String(item.planId)
    const current = mediaByPlan.get(key) ?? { photos: [], receipts: [], all: [] }
    current.all.push(item)
    if (item.mediaType === 'RECEIPT') {
      current.receipts.push(item)
    } else {
      current.photos.push(item)
    }
    mediaByPlan.set(key, current)
  })

  return sourcePlans.map((plan) => {
    const media = mediaByPlan.get(String(plan.id)) ?? { photos: [], receipts: [], all: [] }
    const hero = media.photos[0] ?? media.all[0] ?? null
    return {
      id: plan.id,
      name: plan.name,
      destination: plan.destination || '?ы뻾吏 誘몄젙',
      period: [plan.startDate, plan.endDate].filter(Boolean).join(' - '),
      coverUrl: hero?.contentUrl || '',
      coverLabel: hero?.caption || hero?.title || hero?.originalFileName || '',
      photoCount: media.photos.length,
      receiptCount: media.receipts.length,
      mediaCount: media.all.length,
      recordCount: Number(plan.recordCount ?? 0) + Number(plan.memoryRecordCount ?? 0),
      actualTotalKrw: Number(plan.actualTotalKrw ?? 0),
    }
  })
})

const visiblePlanCards = computed(() =>
  representativePlanCards.value.filter((planCard) => {
    if (props.route === 'travel-photos') {
      return planCard.photoCount > 0
    }
    if (props.route === 'travel-receipts') {
      return planCard.receiptCount > 0
    }
    return planCard.mediaCount > 0
  }),
)

const gallerySummaryCards = computed(() => [
  {
    key: 'photos',
    label: '?ы뻾 ?ъ쭊',
    value: gallerySummary.value.photos,
    caption: '?μ냼, ?뚯떇, ?띻꼍 ?ъ쭊',
  },
  {
    key: 'receipts',
    label: '?곸닔利?,
    value: gallerySummary.value.receipts,
    caption: '寃곗젣 利앸튃 ?대?吏? PDF',
  },
  {
    key: 'total',
    label: '?꾩껜 誘몃뵒??,
    value: gallerySummary.value.total,
    caption: props.travelStatsScope === 'ALL' ? '?꾩껜 ?ы뻾 湲곗?' : '?좏깮 ?ы뻾 湲곗?',
  },
])

const placeLegendPlans = computed(() => {
  const plans = props.travelStatsScope === 'ALL' ? props.travelPortfolio?.plans ?? [] : props.travelPlan ? [props.travelPlan] : []
  return plans.map((plan) => ({
    id: plan.id,
    name: plan.name,
    destination: plan.destination || '?ы뻾吏 誘몄젙',
    colorHex: plan.colorHex || '#3182F6',
    recordCount: Number(plan.recordCount ?? 0) + Number(plan.memoryRecordCount ?? 0),
  }))
})

const travelPlaceRows = computed(() => {
  const bucket = new Map()

  scopedAnalytics.value.mapMarkers.forEach((marker) => {
    const key = [
      marker.planId,
      marker.country || '',
      marker.region || '',
      marker.placeName || marker.title || '',
      marker.latitude,
      marker.longitude,
    ].join('::')

    const current = bucket.get(key) ?? {
      key,
      planId: marker.planId,
      planName: marker.planName || '?ы뻾 誘몄젙',
      colorHex: marker.colorHex || '#3182F6',
      country: marker.country || '',
      region: marker.region || '',
      placeName: marker.placeName || marker.title || '?꾩튂 ?대쫫 ?놁쓬',
      latitude: marker.latitude,
      longitude: marker.longitude,
      totalKrw: 0,
      count: 0,
      latestDate: marker.visitedDate || '',
    }

    current.totalKrw += Number(marker.amountKrw ?? 0)
    current.count += 1
    if ((marker.visitedDate || '') > current.latestDate) {
      current.latestDate = marker.visitedDate || ''
    }

    bucket.set(key, current)
  })

  return [...bucket.values()].sort((left, right) => right.totalKrw - left.totalKrw || right.count - left.count)
})

const pendingMediaCount = computed(() => photoFiles.value.length + receiptFiles.value.length)

const splitBaseTotal = computed(() => Math.ceil(Number(scopedAnalytics.value.summary.actualTotalKrw ?? 0)))
const splitExtraTotal = computed(() => Math.max(0, Number(splitForm.extraAmount || 0)))
const splitHeadCount = computed(() => Math.max(1, Number(splitForm.peopleCount || scopedAnalytics.value.summary.headCount || 1)))
const splitGrandTotal = computed(() => Math.ceil(splitBaseTotal.value + splitExtraTotal.value))
const splitPerPerson = computed(() => Math.ceil(splitGrandTotal.value / splitHeadCount.value))

const exchangeAmountNumber = computed(() => Number(exchangeForm.amount || 0))
const exchangeKrwValue = computed(() => convertToKrw(exchangeAmountNumber.value, exchangeForm.fromCurrency, props.travelRates))
const exchangeResultValue = computed(() => {
  if (exchangeForm.fromCurrency === exchangeForm.toCurrency) {
    return exchangeAmountNumber.value
  }
  if (exchangeForm.toCurrency === 'KRW') {
    return exchangeKrwValue.value
  }
  return convertFromKrw(exchangeKrwValue.value, exchangeForm.toCurrency, props.travelRates)
})

watch(
  () => props.travelPlan?.id,
  () => {
    splitForm.peopleCount = Number(props.travelPlan?.headCount ?? 2)
    recordLocationFilter.country = ''
    recordLocationFilter.region = ''
    if (props.travelPlan?.homeCurrency) {
      exchangeForm.toCurrency = props.travelPlan.homeCurrency
    }
  },
  { immediate: true },
)

watch(
  () => props.travelStatsScope,
  (scope) => {
    if (scope === 'ALL') {
      splitForm.peopleCount = 1
      return
    }
    splitForm.peopleCount = Number(props.travelPlan?.headCount ?? 2)
  },
  { immediate: true },
)

watch(
  () => props.recordFormVersion,
  () => {
    clearPendingMediaSelection()
  },
)

watch(
  () => recordLocationFilter.country,
  (country) => {
    const normalizedCountry = normalizeLocationLabel(country)
    const isValidRegion = recordRegionOptions.value.some((item) => item.label === normalizeLocationLabel(recordLocationFilter.region))
    if (!normalizedCountry || !isValidRegion) {
      recordLocationFilter.region = ''
    }
  },
)

function hasPlans() {
  return props.travelPlans.length > 0
}

function hasScopedTravelData() {
  return Boolean(scopedTravelData.value)
}

function isEditingBudgetItem() {
  return props.editingBudgetItemId !== null && props.editingBudgetItemId !== ''
}

function isEditingRecord() {
  return props.editingRecordId !== null && props.editingRecordId !== ''
}

function mediaCountForRecord(recordId) {
  return recordMediaCountMap.value.get(String(recordId)) ?? { total: 0, photos: 0, receipts: 0 }
}

function photoCountForRecord(recordId) {
  return mediaCountForRecord(recordId).photos
}

function receiptCountForRecord(recordId) {
  return mediaCountForRecord(recordId).receipts
}

function handlePickLocation(point) {
  props.recordForm.latitude = String(point.latitude)
  props.recordForm.longitude = String(point.longitude)
}

function clearPendingMediaSelection() {
  photoFiles.value = []
  receiptFiles.value = []
  mediaCaptions.PHOTO = ''
  mediaCaptions.RECEIPT = ''
  photoAutofillState.status = 'idle'
  photoAutofillState.fileName = ''
  photoAutofillState.message = '?ъ쭊???좏깮?섎㈃ 珥ъ쁺 ?쒓컙怨??꾩튂 ?뺣낫媛 ?덉쑝硫??먮룞?쇰줈 梨꾩썙吏묐땲??'
}

async function applyPhotoMetadata(file) {
  if (!file) {
    photoAutofillState.status = 'idle'
    photoAutofillState.fileName = ''
    photoAutofillState.message = '?ъ쭊???좏깮?섎㈃ 珥ъ쁺 ?쒓컙怨??꾩튂 ?뺣낫媛 ?덉쑝硫??먮룞?쇰줈 梨꾩썙吏묐땲??'
    return
  }

  try {
    const metadata = await extractPhotoMetadata(file)
    photoAutofillState.fileName = file.name

    if (!metadata) {
      photoAutofillState.status = 'manual'
      photoAutofillState.message = '?ъ쭊 硫뷀??곗씠?곌? ?놁뼱 吏곸젒 ?낅젰??二쇱꽭??'
      return
    }

    const appliedFields = []

    if (metadata.date) {
      props.recordForm.expenseDate = metadata.date
      appliedFields.push('?좎쭨')
    }
    if (metadata.time) {
      props.recordForm.expenseTime = metadata.time
      appliedFields.push('?쒓컙')
    }
    if (metadata.latitude !== null && metadata.latitude !== undefined) {
      props.recordForm.latitude = String(metadata.latitude)
      appliedFields.push('?꾨룄')
    }
    if (metadata.longitude !== null && metadata.longitude !== undefined) {
      props.recordForm.longitude = String(metadata.longitude)
      appliedFields.push('寃쎈룄')
    }
    if (metadata.country && !normalizeLocationLabel(props.recordForm.country)) {
      props.recordForm.country = metadata.country
      appliedFields.push('援??')
    }
    if (metadata.region && !normalizeLocationLabel(props.recordForm.region)) {
      props.recordForm.region = metadata.region
      appliedFields.push('吏??)
    }
    if (metadata.placeName && !normalizeLocationLabel(props.recordForm.placeName)) {
      props.recordForm.placeName = metadata.placeName
      appliedFields.push('?μ냼')
    }

    if (appliedFields.length) {
      photoAutofillState.status = 'filled'
      photoAutofillState.message = `${file.name}?먯꽌 ${appliedFields.join(', ')} ?뺣낫瑜??먮룞?쇰줈 梨꾩썱?듬땲??`
    } else {
      photoAutofillState.status = 'manual'
      photoAutofillState.message = '?ъ쭊 硫뷀??곗씠?곌? ?놁뼱 吏곸젒 ?낅젰??二쇱꽭??'
    }
  } catch (error) {
    photoAutofillState.status = 'manual'
    photoAutofillState.fileName = file.name
    photoAutofillState.message = '?ъ쭊 ?뺣낫瑜??쎌? 紐삵빐 吏곸젒 ?낅젰???꾩슂?⑸땲??'
    console.warn('photo metadata extraction failed', error)
  }
}

function clearPickedLocation() {
  props.recordForm.latitude = ''
  props.recordForm.longitude = ''
}

async function updateSelectedFiles(type, event) {
  const files = [...(event.target.files ?? [])]
  if (type === 'PHOTO') {
    photoFiles.value = files
    if (!files.length) {
      photoAutofillState.status = 'idle'
      photoAutofillState.fileName = ''
      photoAutofillState.message = '?ъ쭊???좏깮?섎㈃ 珥ъ쁺 ?쒓컙怨??꾩튂 ?뺣낫媛 ?덉쑝硫??먮룞?쇰줈 梨꾩썙吏묐땲??'
      return
    }
    await applyPhotoMetadata(files[0])
    return
  }
  receiptFiles.value = files
}

function resetRecordWithMedia() {
  clearPendingMediaSelection()
  emit('reset-record')
}

function handleUploadMedia(mediaType) {
  if (!props.editingRecordId) {
    return
  }

  const files = mediaType === 'PHOTO' ? photoFiles.value : receiptFiles.value
  if (!files.length) {
    return
  }

  emit('upload-media', {
    recordId: props.editingRecordId,
    mediaType,
    files,
    caption: mediaCaptions[mediaType].trim(),
  })
}

function handleEditRecord(record) {
  clearPendingMediaSelection()
  emit('edit-record', record)
}

function handleScopeChange(scope) {
  emit('change-stats-scope', scope)
}

function handleOpenPlanCard(planId) {
  emit('select-plan', String(planId))
  emit('change-stats-scope', 'PLAN')
}

function planCardCountLabel(planCard) {
  if (props.route === 'travel-photos') {
    return `?ъ쭊 ${planCard.photoCount}??
  }
  if (props.route === 'travel-receipts') {
    return `?곸닔利?${planCard.receiptCount}嫄?
  }
  return `?ъ쭊 ${planCard.photoCount}??쨌 ?곸닔利?${planCard.receiptCount}嫄?
}

function isImageMedia(item) {
  return String(item.contentType || '').startsWith('image/')
}

function mediaTypeLabel(mediaType) {
  return mediaType === 'RECEIPT' ? '?곸닔利? : '?ъ쭊'
}

function buildPendingMediaPayload() {
  return {
    mediaGroups: [
      {
        mediaType: 'PHOTO',
        files: photoFiles.value,
        caption: mediaCaptions.PHOTO.trim(),
      },
      {
        mediaType: 'RECEIPT',
        files: receiptFiles.value,
        caption: mediaCaptions.RECEIPT.trim(),
      },
    ].filter((item) => item.files.length),
  }
}

function formatRecordDateTime(record) {
  const dateLabel = record.expenseDate ? formatShortDate(record.expenseDate) : ''
  const timeLabel = record.expenseTime ? formatTime(record.expenseTime) : ''
  return [dateLabel, timeLabel].filter(Boolean).join(' ')
}
</script>

<template>
  <div class="workspace-stack">
    <section class="panel">
      <div class="panel__header">
        <div>
          <h2>?ы뻾 ?뚰겕?ㅽ럹?댁뒪</h2>
          <p>?ы뻾 怨꾪쉷, 湲곕줉, ?섏쑉, ?ъ쭊, ?듦퀎瑜????먮쫫?쇰줈 愿由ы빀?덈떎.</p>
        </div>
        <span v-if="travelPlan" class="panel__badge">{{ travelPlan.name }}</span>
      </div>

      <div class="travel-toolbar">
        <label class="field travel-toolbar__select">
          <span class="field__label">?ы뻾 ?좏깮</span>
          <select :value="selectedPlanId" @change="emit('select-plan', $event.target.value)">
            <option value="">?ы뻾???좏깮??二쇱꽭??/option>
            <option v-for="plan in travelPlans" :key="plan.id" :value="String(plan.id)">
              {{ plan.name }} / {{ plan.destination || '?ы뻾吏 誘몄젙' }}
            </option>
          </select>
        </label>

        <div class="travel-toolbar__actions">
          <button class="button button--secondary" @click="emit('start-plan-create')">???ы뻾</button>
          <button class="button button--ghost" :disabled="!hasPlans()" @click="emit('refresh-rates')">?섏쑉 ?덈줈怨좎묠</button>
        </div>
      </div>

      <div v-if="travelPlan" class="travel-plan-banner" :style="{ '--travel-plan-color': normalizeColorHex(travelPlan.colorHex) }">
        <strong>{{ travelPlan.destination || '?ы뻾吏 誘몄젙' }}</strong>
        <span>{{ travelPlan.startDate }} - {{ travelPlan.endDate }}</span>
        <small>湲곗? ?듯솕 {{ travelPlan.homeCurrency }} / ?몄썝 {{ travelPlan.headCount }}紐?/small>
      </div>

      <p v-else class="panel__empty">?꾩쭅 ?깅줉???ы뻾???놁뒿?덈떎. 癒쇱? ?ы뻾 怨꾪쉷??留뚮뱾??二쇱꽭??</p>
    </section>

    <section class="travel-summary-grid">
      <article class="travel-stat-card">
        <span>?좏깮 ?ы뻾 ?덉궛</span>
        <strong>{{ formatCurrency(planAnalytics.summary.plannedTotalKrw) }}</strong>
        <small>{{ planAnalytics.summary.budgetItemCount }}媛???ぉ</small>
      </article>
      <article class="travel-stat-card">
        <span>?좏깮 ?ы뻾 ?ъ슜??/span>
        <strong>{{ formatCurrency(planAnalytics.summary.actualTotalKrw) }}</strong>
        <small>{{ planAnalytics.summary.recordCount }}嫄?湲곕줉</small>
      </article>
      <article class="travel-stat-card">
        <span>?낅줈???뚯씪</span>
        <strong>{{ planAnalytics.summary.mediaItemCount }}媛?/strong>
        <small>?ъ쭊怨??곸닔利??ы븿</small>
      </article>
      <article class="travel-stat-card">
        <span>?섏쑉 怨꾩궛 湲곗?</span>
        <strong>{{ settingsCurrencyCode }}</strong>
        <small>?먯꽭??怨꾩궛? ?섏쑉 怨꾩궛湲곗뿉???뺤씤</small>
      </article>
    </section>

    <datalist id="travel-budget-categories">
      <option v-for="category in travelBudgetCategoryOptions" :key="category" :value="category" />
    </datalist>
    <datalist id="travel-country-options">
      <option v-for="country in formCountryOptions" :key="country" :value="country" />
    </datalist>
    <datalist id="travel-region-options">
      <option v-for="region in formRegionOptions" :key="region" :value="region" />
    </datalist>

    <template v-if="route === 'travel-planner'">
      <div class="content-grid content-grid--travel">
        <section class="panel">
          <div class="panel__header">
            <div>
              <h2>{{ planEditorMode === 'edit' ? '?ы뻾 ?뺣낫 ?섏젙' : '?ы뻾 怨꾪쉷 留뚮뱾湲? }}</h2>
              <p>?ы뻾 ?대쫫, ?쇱젙, 湲곗? ?듯솕, ?몄썝?섎? 癒쇱? ?깅줉?⑸땲??</p>
            </div>
          </div>

          <div class="travel-form-grid">
            <label class="field">
              <span class="field__label">?ы뻾紐?/span>
              <input v-model="planForm.name" type="text" placeholder="?? ?ㅼ궗移?3諛?4?? />
            </label>
            <label class="field">
              <span class="field__label">?ы뻾吏</span>
              <input v-model="planForm.destination" type="text" placeholder="?? Osaka" />
            </label>
            <label class="field">
              <span class="field__label">?쒖옉??/span>
              <input v-model="planForm.startDate" type="date" />
            </label>
            <label class="field">
              <span class="field__label">醫낅즺??/span>
              <input v-model="planForm.endDate" type="date" />
            </label>
            <label class="field">
              <span class="field__label">湲곗? ?듯솕</span>
              <select v-model="planForm.homeCurrency">
                <option v-for="currency in travelCurrencyOptions" :key="currency.code" :value="currency.code">
                  {{ currency.label }}
                </option>
              </select>
            </label>
            <label class="field">
              <span class="field__label">?몄썝??/span>
              <input v-model="planForm.headCount" type="number" min="1" step="1" placeholder="?? 4" />
            </label>
            <label class="field">
              <span class="field__label">여행 상태</span>
              <select v-model="planForm.status">
                <option value="PLANNED">예정 여행</option>
                <option value="COMPLETED">다녀온 여행</option>
                <option value="SAMPLE">테스트용 여행</option>
              </select>
            </label>
            <div class="field">
              <span class="field__label">?ы뻾 ?됱긽</span>
              <div class="travel-color-field">
                <input v-model="planForm.colorHex" class="travel-color-field__picker" type="color" />
                <input v-model="planForm.colorHex" class="travel-color-field__hex" type="text" placeholder="#3182F6" />
              </div>
            </div>
            <div class="field field--full">
              <span class="field__label">?됱긽 ?꾨━??/span>
              <div class="travel-color-palette">
                <button
                  v-for="color in planColorPresets"
                  :key="color"
                  type="button"
                  :title="color"
                  class="travel-color-chip"
                  :class="{ 'travel-color-chip--active': normalizeColorHex(planForm.colorHex) === color }"
                  :style="{ '--travel-plan-color': color }"
                  @click="planForm.colorHex = color"
                />
              </div>
            </div>
            <label class="field field--full">
              <span class="field__label">硫붾え</span>
              <textarea v-model="planForm.memo" rows="4" placeholder="???ы뻾?먯꽌 愿由ы븷 ?덉궛 踰붿쐞瑜??곸뼱?먯꽭??" />
            </label>
          </div>

          <div class="entry-editor__actions">
            <button class="button button--primary" :disabled="isSubmitting" @click="emit('submit-plan')">
              {{ isSubmitting && activeSubmit === 'travel-plan' ? '???以?..' : planEditorMode === 'edit' ? '?ы뻾 ?섏젙' : '?ы뻾 ?앹꽦' }}
            </button>
            <button v-if="travelPlan && planEditorMode === 'edit'" class="button button--danger" @click="emit('delete-plan')">
              ?ы뻾 ??젣
            </button>
          </div>
        </section>

        <section class="panel">
          <div class="panel__header">
            <div>
              <h2>{{ isEditingBudgetItem() ? '?덉궛 ??ぉ ?섏젙' : '?덉궛 ??ぉ 異붽?' }}</h2>
              <p>?섏쑉 怨꾩궛? 蹂꾨룄 ?섏씠吏濡?遺꾨━?덇퀬, ?ш린?쒕뒗 ?덉궛 ?낅젰?먮쭔 吏묒쨷?????덇쾶 ?뺣━?덉뒿?덈떎.</p>
            </div>
          </div>

          <div v-if="travelPlan" class="travel-form-grid">
            <label class="field">
              <span class="field__label">移댄뀒怨좊━</span>
              <input v-model="budgetForm.category" list="travel-budget-categories" type="text" placeholder="??났, ?숈냼, 蹂댄뿕..." />
            </label>
            <label class="field">
              <span class="field__label">??ぉ紐?/span>
              <input v-model="budgetForm.title" type="text" placeholder="?? ?몄쿇-?ㅼ궗移??뺣났 ??났" />
            </label>
            <label class="field">
              <span class="field__label">?덉긽 湲덉븸</span>
              <input v-model="budgetForm.amount" type="number" min="0" step="0.01" placeholder="0" />
            </label>
            <label class="field">
              <span class="field__label">?듯솕</span>
              <select v-model="budgetForm.currencyCode">
                <option v-for="currency in travelCurrencyOptions" :key="currency.code" :value="currency.code">
                  {{ currency.label }}
                </option>
              </select>
            </label>
            <label class="field">
              <span class="field__label">?뺣젹 ?쒖꽌</span>
              <input v-model="budgetForm.displayOrder" type="number" min="0" step="1" />
            </label>
            <label class="field field--full">
              <span class="field__label">硫붾え</span>
              <textarea v-model="budgetForm.memo" rows="3" placeholder="?덉빟 ?쒖젏?대굹 李멸퀬 硫붾え瑜??곸뼱?????덉뒿?덈떎." />
            </label>
          </div>

          <p v-else class="panel__empty">?덉궛 ??ぉ??異붽??섎젮硫?癒쇱? ?ы뻾 怨꾪쉷???좏깮??二쇱꽭??</p>

          <div v-if="travelPlan" class="entry-editor__actions">
            <button class="button button--primary" :disabled="isSubmitting" @click="emit('submit-budget-item')">
              {{ isSubmitting && activeSubmit === 'travel-budget' ? '???以?..' : isEditingBudgetItem() ? '?덉궛 ?섏젙' : '?덉궛 異붽?' }}
            </button>
            <button v-if="isEditingBudgetItem()" class="button button--secondary" @click="emit('reset-budget-item')">
              ?몄쭛 痍⑥냼
            </button>
          </div>
        </section>
      </div>

      <section class="panel">
        <div class="panel__header">
          <div>
            <h2>?ы뻾 ?덉궛 紐⑸줉</h2>
            <p>怨꾪쉷??鍮꾩슜????ぉ蹂꾨줈 ??ν븯怨??섏젙?????덉뒿?덈떎.</p>
          </div>
        </div>

        <div class="sheet-table-wrap">
          <table class="sheet-table">
            <thead>
              <tr>
                <th>移댄뀒怨좊━</th>
                <th>??ぉ紐?/th>
                <th>?먭툑??/th>
                <th>KRW ?섏궛</th>
                <th>硫붾え</th>
                <th>?묒뾽</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in travelPlan?.budgetItems ?? []" :key="item.id">
                <td>{{ item.category }}</td>
                <td>{{ item.title }}</td>
                <td>{{ formatCurrencyByCode(item.amount, item.currencyCode) }}</td>
                <td>{{ formatCurrency(item.amountKrw) }}</td>
                <td>{{ item.memo || '-' }}</td>
                <td class="sheet-table__actions">
                  <button class="button button--ghost" @click="emit('edit-budget-item', item)">?섏젙</button>
                  <button class="button button--danger" @click="emit('delete-budget-item', item)">??젣</button>
                </td>
              </tr>
              <tr v-if="!(travelPlan?.budgetItems ?? []).length">
                <td colspan="6" class="sheet-table__empty">?깅줉???ы뻾 ?덉궛 ??ぉ???놁뒿?덈떎.</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </template>

    <template v-else-if="route === 'travel-exchange'">
      <div class="content-grid content-grid--travel content-grid--travel-records">
        <section class="panel">
          <div class="panel__header">
            <div>
              <h2>?섏쑉 怨꾩궛湲?/h2>
              <p>?낅젰 ?붾㈃怨?遺꾨━?댁꽌, ?꾩슂???섏궛留?鍮좊Ⅴ寃?怨꾩궛?????덇쾶 留뚮뱾?덉뒿?덈떎.</p>
            </div>
          </div>

          <div class="travel-form-grid">
            <label class="field">
              <span class="field__label">湲곗? 湲덉븸</span>
              <input v-model="exchangeForm.amount" type="number" min="0" step="0.01" placeholder="1000" />
            </label>
            <label class="field">
              <span class="field__label">湲곗? ?듯솕</span>
              <select v-model="exchangeForm.fromCurrency">
                <option v-for="currency in travelCurrencyOptions" :key="currency.code" :value="currency.code">
                  {{ currency.label }}
                </option>
              </select>
            </label>
            <label class="field">
              <span class="field__label">?섏궛 ?듯솕</span>
              <select v-model="exchangeForm.toCurrency">
                <option v-for="currency in travelCurrencyOptions" :key="currency.code" :value="currency.code">
                  {{ currency.label }}
                </option>
              </select>
            </label>
            <label class="field">
              <span class="field__label">KRW 湲곗? ?섏궛</span>
              <input :value="formatCurrency(exchangeKrwValue)" type="text" readonly />
            </label>
          </div>

          <div class="travel-exchange-result">
            <article class="travel-exchange-card">
              <span>?섏궛 寃곌낵</span>
              <strong>{{ formatCurrencyByCode(exchangeResultValue, exchangeForm.toCurrency) }}</strong>
              <small>{{ exchangeForm.fromCurrency }} -> {{ exchangeForm.toCurrency }}</small>
            </article>
            <article class="travel-exchange-card">
              <span>1,000??湲곗?</span>
              <strong>{{ formatCurrencyByCode(exchangeForm.toCurrency === 'KRW' ? 1000 : buildKrwGuideAmount(resolveTravelRate(travelRates, exchangeForm.toCurrency), 1000), exchangeForm.toCurrency) }}</strong>
              <small>湲곕낯 媛?대뱶 ?섏궛</small>
            </article>
            <article class="travel-exchange-card">
              <span>?꾩옱 ?좏깮 ?ы뻾 ?듯솕</span>
              <strong>{{ settingsCurrencyCode }}</strong>
              <small>{{ travelPlan ? `${travelPlan.name} 湲곗? ?듯솕` : '?ы뻾 ?좏깮 ??湲곕낯媛믪쑝濡?諛섏쁺' }}</small>
            </article>
          </div>
        </section>

        <section class="panel panel--map-fill">
          <div class="panel__header">
            <div>
              <h2>?섏쑉 湲곗???/h2>
              <p>湲곕낯?곸쑝濡?1,000??湲곗? ?섏궛怨?1?듯솕???먰솕 媛믪쓣 ?④퍡 蹂댁뿬以띾땲??</p>
            </div>
          </div>

          <div class="travel-rate-grid">
            <article v-for="item in rateCards" :key="item.currencyCode" class="travel-rate-card">
              <strong>{{ item.currencyCode }}</strong>
              <span>{{ item.available ? `1,000??= ${formatCurrencyByCode(item.krwGuideAmount, item.currencyCode)}` : '?섏쑉 ?뺣낫瑜?遺덈윭?ㅼ? 紐삵뻽?듬땲??' }}</span>
              <small v-if="item.available">{{ item.currencyCode === 'KRW' ? '1??= 1?? : `1${item.currencyCode} = ${formatCurrency(item.rate)}` }}</small>
            </article>
          </div>
        </section>
      </div>
    </template>

    <template v-else-if="route === 'travel-memories'">
      <TravelMemoryPanel
        :travel-plan="travelPlan"
        :memory-form="memoryForm"
        :memory-form-version="memoryFormVersion"
        :editing-memory-id="editingMemoryId"
        :is-submitting="isSubmitting"
        :active-submit="activeSubmit"
        @submit-memory="emit('submit-memory', $event)"
        @edit-memory="emit('edit-memory', $event)"
        @reset-memory="emit('reset-memory')"
        @delete-memory="emit('delete-memory', $event)"
        @upload-memory-media="emit('upload-memory-media', $event)"
        @delete-media="emit('delete-media', $event)"
      />
    </template>

    <template v-else-if="route === 'travel-records'">
      <div class="content-grid content-grid--travel content-grid--travel-records">
        <section class="panel">
          <div class="panel__header">
            <div>
              <h2>{{ isEditingRecord() ? '?ы뻾 湲곕줉 ?섏젙' : '?ы뻾 湲곕줉 異붽?' }}</h2>
              <p>?ъ쭊??怨좊Ⅴ??利됱떆 硫뷀??곗씠?곕? ?쎌뼱 ?쒓컙怨??꾩튂瑜??먮룞?쇰줈 梨꾩슦怨? ??ν븯硫??뚯씪???④퍡 ?곌껐?⑸땲??</p>
            </div>
          </div>

          <div v-if="travelPlan" class="travel-form-grid">
            <label class="field">
              <span class="field__label">?좎쭨</span>
              <input v-model="recordForm.expenseDate" type="date" />
            </label>
            <label class="field">
              <span class="field__label">?쒓컙</span>
              <input v-model="recordForm.expenseTime" type="time" />
            </label>
            <label class="field">
              <span class="field__label">移댄뀒怨좊━</span>
              <input v-model="recordForm.category" list="travel-budget-categories" type="text" placeholder="?앸퉬, 援먰넻, ?쇳븨..." />
            </label>
            <label class="field field--full">
              <span class="field__label">?쒕ぉ</span>
              <input v-model="recordForm.title" type="text" placeholder="?? ?꾪넠蹂대━ ?肄붿빞?? />
            </label>
            <label class="field">
              <span class="field__label">?ъ슜 湲덉븸</span>
              <input v-model="recordForm.amount" type="number" min="0" step="0.01" placeholder="0" />
            </label>
            <label class="field">
              <span class="field__label">?듯솕</span>
              <select v-model="recordForm.currencyCode">
                <option v-for="currency in travelCurrencyOptions" :key="currency.code" :value="currency.code">
                  {{ currency.label }}
                </option>
              </select>
            </label>
            <label class="field">
              <span class="field__label">諛⑸Ц 援??</span>
              <input v-model="recordForm.country" list="travel-country-options" type="text" placeholder="?? Japan" />
            </label>
            <label class="field">
              <span class="field__label">?ы뻾 吏??/span>
              <input v-model="recordForm.region" list="travel-region-options" type="text" placeholder="?? Osaka" />
            </label>
            <label class="field field--full">
              <span class="field__label">諛⑸Ц ?μ냼</span>
              <input v-model="recordForm.placeName" type="text" placeholder="?? Dotonbori" />
            </label>
            <label class="field">
              <span class="field__label">?꾨룄</span>
              <input v-model="recordForm.latitude" type="text" readonly />
            </label>
            <label class="field">
              <span class="field__label">寃쎈룄</span>
              <input v-model="recordForm.longitude" type="text" readonly />
            </label>
            <label class="field field--full">
              <span class="field__label">硫붾え</span>
              <textarea v-model="recordForm.memo" rows="3" placeholder="?μ냼, 吏異?諛곌꼍, ?곸닔利?硫붾え瑜??④만 ???덉뒿?덈떎." />
            </label>
          </div>

          <p v-else class="panel__empty">?ы뻾 湲곕줉???④린?ㅻ㈃ 癒쇱? ?ы뻾 怨꾪쉷???좏깮??二쇱꽭??</p>

          <div v-if="travelPlan" class="entry-editor__actions">
            <button class="button button--ghost" @click="clearPickedLocation">?좏깮 ?꾩튂 珥덇린??/button>
            <button class="button button--primary" :disabled="isSubmitting" @click="emit('submit-record', buildPendingMediaPayload())">
              {{
                isSubmitting && activeSubmit === 'travel-record'
                  ? '???以?..'
                  : pendingMediaCount
                    ? isEditingRecord()
                      ? '湲곕줉 ???+ ?湲??뚯씪 諛섏쁺'
                      : '湲곕줉 異붽? + ?뚯씪 ?낅줈??
                    : isEditingRecord()
                      ? '湲곕줉 ?섏젙'
                      : '湲곕줉 異붽?'
              }}
            </button>
            <button v-if="isEditingRecord()" class="button button--secondary" @click="resetRecordWithMedia">
              ??湲곕줉 ?묒꽦
            </button>
          </div>
        </section>

        <section class="panel panel--map-fill">
          <div class="panel__header">
            <div>
              <h2>湲곕줉 ?꾩튂 ?쒓렇</h2>
              <p>吏?꾨? ?대┃?섎㈃ ?꾩옱 ?ы뻾 湲곕줉??醫뚰몴媛 ?ㅼ뼱媛怨? ?섏쨷????쇰줈 ?ㅼ떆 蹂????덉뒿?덈떎.</p>
            </div>
          </div>

          <TravelMapPanel
            :markers="planAnalytics.mapMarkers"
            :selected-point="selectedLocation"
            @pick-location="handlePickLocation"
          />
        </section>
      </div>

      <section class="panel">
        <div class="panel__header">
          <div>
            <h2>?ъ쭊 ?낅줈?쒖? ?곸닔利??낅줈??/h2>
            <p>?ы뻾 湲곕줉 ??κ낵 ?뚯씪 ?낅줈?쒕? 遺꾨━???먯뿀?듬땲?? ??湲곕줉? ??ν븷 ???④퍡 ?곌껐?섍퀬, ??λ맂 湲곕줉? ?꾨옒 踰꾪듉?쇰줈 諛붾줈 異붽? ?낅줈?쒗븷 ???덉뒿?덈떎.</p>
          </div>
        </div>

        <div class="travel-media-upload-grid">
          <article class="travel-inline-media">
            <div class="travel-inline-media__header">
              <span class="field__label">?ы뻾 ?ъ쭊 ?낅줈??/span>
              <small>?ъ쭊??怨좊Ⅴ??利됱떆 珥ъ쁺 ?쒓컙, GPS, ?꾩튂 ?뺣낫媛 ?덉쑝硫???湲곕줉 移몄뿉 ?먮룞 ?낅젰?⑸땲??</small>
            </div>
            <div class="travel-form-grid travel-form-grid--compact">
              <label class="field">
                <span class="field__label">?ы뻾 ?ъ쭊</span>
                <input accept="image/*" multiple type="file" @change="updateSelectedFiles('PHOTO', $event)" />
              </label>
              <label class="field">
                <span class="field__label">?ъ쭊 ?ㅻ챸</span>
                <input v-model="mediaCaptions.PHOTO" type="text" placeholder="?? ?꾪넠蹂대━ ?肄붿빞?? />
              </label>
            </div>
            <div class="travel-file-chip-row">
              <span class="chip chip--neutral">?ъ쭊 ?湲?{{ photoFiles.length }}??/span>
              <span v-if="photoFiles.length && !isEditingRecord()" class="chip chip--neutral">湲곕줉 ??????④퍡 ?낅줈??/span>
            </div>
            <button
              v-if="isEditingRecord()"
              class="button button--primary"
              :disabled="!photoFiles.length || isSubmitting"
              @click="handleUploadMedia('PHOTO')"
            >
              ?ъ쭊留?諛붾줈 ?낅줈??            </button>
          </article>

          <article class="travel-inline-media">
            <div class="travel-inline-media__header">
              <span class="field__label">?곸닔利??낅줈??/span>
              <small>?곸닔利??대?吏??臾쇰줎 PDF???щ┫ ???덇퀬, ??λ맂 湲곕줉?먮뒗 諛붾줈 異붽? 泥⑤??????덉뒿?덈떎.</small>
            </div>
            <div class="travel-form-grid travel-form-grid--compact">
              <label class="field">
                <span class="field__label">?곸닔利??뚯씪</span>
                <input accept="image/*,application/pdf" multiple type="file" @change="updateSelectedFiles('RECEIPT', $event)" />
              </label>
              <label class="field">
                <span class="field__label">?곸닔利??ㅻ챸</span>
                <input v-model="mediaCaptions.RECEIPT" type="text" placeholder="?? 移대뱶 ?곸닔利? />
              </label>
            </div>
            <div class="travel-file-chip-row">
              <span class="chip chip--neutral">?곸닔利??湲?{{ receiptFiles.length }}媛?/span>
              <span v-if="receiptFiles.length && !isEditingRecord()" class="chip chip--neutral">湲곕줉 ??????④퍡 ?낅줈??/span>
            </div>
            <button
              v-if="isEditingRecord()"
              class="button button--primary"
              :disabled="!receiptFiles.length || isSubmitting"
              @click="handleUploadMedia('RECEIPT')"
            >
              ?곸닔利앸쭔 諛붾줈 ?낅줈??            </button>
          </article>
        </div>

        <p
          class="travel-autofill-note"
          :class="{
            'travel-autofill-note--filled': photoAutofillState.status === 'filled',
            'travel-autofill-note--manual': photoAutofillState.status === 'manual',
          }"
        >
          {{ photoAutofillState.message }}
        </p>

        <div class="travel-file-chip-row">
          <span class="chip chip--neutral">?ъ쭊 ?湲?{{ photoFiles.length }}??/span>
          <span class="chip chip--neutral">?곸닔利??湲?{{ receiptFiles.length }}媛?/span>
          <span v-if="pendingMediaCount" class="chip chip--neutral">
            {{ isEditingRecord() ? '諛붾줈 ?낅줈???먮뒗 ?????諛섏쁺' : '湲곕줉 ??????④퍡 ?낅줈?? }}
          </span>
        </div>

        <div v-if="photoFiles.length || receiptFiles.length" class="travel-pending-grid">
          <article v-if="photoFiles.length" class="travel-pending-card">
            <strong>?좏깮???ъ쭊</strong>
            <small>{{ photoFiles.map((file) => file.name).join(', ') }}</small>
          </article>
          <article v-if="receiptFiles.length" class="travel-pending-card">
            <strong>?좏깮???곸닔利?/strong>
            <small>{{ receiptFiles.map((file) => file.name).join(', ') }}</small>
          </article>
        </div>
      </section>

      <section class="panel">
        <div class="panel__header">
          <div>
            <h2>?꾩옱 ?곌껐???ъ쭊怨??곸닔利?/h2>
            <p>諛⑷툑 ??ν븳 湲곕줉? 洹몃?濡??댁뼱?먭린 ?뚮Ц?? ???吏곹썑?먮룄 ?ㅼ젣 ?곌껐???뚯씪??諛붾줈 ?뺤씤?????덉뒿?덈떎.</p>
          </div>
        </div>

        <p v-if="!isEditingRecord()" class="panel__empty">湲곕줉 紐⑸줉?먯꽌 ?섏젙??湲곕줉???좏깮?섎㈃ 洹?湲곕줉???곌껐???ъ쭊怨??곸닔利앹쓣 ?ш린??蹂????덉뒿?덈떎.</p>

        <div v-else class="travel-media-stack">
          <section class="travel-media-section">
            <div class="travel-media-section__header">
              <strong>?ы뻾 ?ъ쭊</strong>
              <span class="chip chip--neutral">{{ editingRecordPhotos.length }}媛?/span>
            </div>
            <div v-if="editingRecordPhotos.length" class="travel-media-grid">
              <article v-for="media in editingRecordPhotos" :key="media.id" class="travel-media-card">
                <img v-if="isImageMedia(media)" :src="media.contentUrl" :alt="media.originalFileName" class="travel-media-thumb" />
                <div v-else class="travel-media-thumb travel-media-thumb--receipt">FILE</div>
                <div class="travel-media-copy">
                  <span class="chip chip--neutral">{{ mediaTypeLabel(media.mediaType) }}</span>
                  <strong>{{ media.originalFileName }}</strong>
                  <small>{{ media.uploadedBy }} 쨌 {{ formatShortDate(media.expenseDate) }}</small>
                  <small>{{ [media.country, media.region, media.placeName].filter(Boolean).join(' / ') || '?꾩튂 ?뺣낫 ?놁쓬' }}</small>
                </div>
                <div class="travel-media-actions">
                  <a class="button button--ghost" :href="media.contentUrl" target="_blank" rel="noreferrer">蹂닿린</a>
                  <button class="button button--danger" @click="emit('delete-media', media)">??젣</button>
                </div>
              </article>
            </div>
            <p v-else class="panel__empty">?깅줉???ы뻾 ?ъ쭊???놁뒿?덈떎.</p>
          </section>

          <section class="travel-media-section">
            <div class="travel-media-section__header">
              <strong>?곸닔利?/ 寃곗젣 利앸튃</strong>
              <span class="chip chip--neutral">{{ editingRecordReceipts.length }}媛?/span>
            </div>
            <div v-if="editingRecordReceipts.length" class="travel-media-grid">
              <article v-for="media in editingRecordReceipts" :key="media.id" class="travel-media-card">
                <img v-if="isImageMedia(media)" :src="media.contentUrl" :alt="media.originalFileName" class="travel-media-thumb" />
                <div v-else class="travel-media-thumb travel-media-thumb--receipt">PDF</div>
                <div class="travel-media-copy">
                  <span class="chip chip--neutral">{{ mediaTypeLabel(media.mediaType) }}</span>
                  <strong>{{ media.originalFileName }}</strong>
                  <small>{{ media.uploadedBy }} 쨌 {{ formatShortDate(media.expenseDate) }}</small>
                  <small>{{ [media.country, media.region, media.placeName].filter(Boolean).join(' / ') || '?꾩튂 ?뺣낫 ?놁쓬' }}</small>
                </div>
                <div class="travel-media-actions">
                  <a class="button button--ghost" :href="media.contentUrl" target="_blank" rel="noreferrer">蹂닿린</a>
                  <button class="button button--danger" @click="emit('delete-media', media)">??젣</button>
                </div>
              </article>
            </div>
            <p v-else class="panel__empty">?깅줉???곸닔利앹씠 ?놁뒿?덈떎.</p>
          </section>
        </div>
      </section>

      <section class="panel">
        <div class="panel__header">
          <div>
            <h2>?ы뻾 吏異?湲곕줉</h2>
            <p>?좎쭨, ?쒓컙, ?꾩튂, ?낅줈?쒕맂 ?ъ쭊 ?섍퉴吏 ?④퍡 ?뺤씤?????덉뒿?덈떎.</p>
          </div>
        </div>

        <div v-if="travelPlan" class="travel-location-filter">
          <label class="field">
            <span class="field__label">援?? 移댄뀒怨좊━</span>
            <select v-model="recordLocationFilter.country">
              <option value="">?꾩껜 援??</option>
              <option v-for="country in recordCountryOptions" :key="country.label" :value="country.label">
                {{ country.label }} 쨌 {{ country.count }}嫄?              </option>
            </select>
          </label>
          <label class="field">
            <span class="field__label">?꾩떆 / 吏??移댄뀒怨좊━</span>
            <select v-model="recordLocationFilter.region">
              <option value="">?꾩껜 ?꾩떆</option>
              <option v-for="region in recordRegionOptions" :key="`${region.country}-${region.label}`" :value="region.label">
                {{ region.label }}<template v-if="region.country"> ({{ region.country }})</template> 쨌 {{ region.count }}嫄?              </option>
            </select>
          </label>
        </div>

        <div v-if="travelPlan && recordCountryOptions.length" class="travel-location-chip-row">
          <button class="button button--ghost" :class="{ 'button--primary': !recordLocationFilter.country }" @click="recordLocationFilter.country = ''">
            ?꾩껜
          </button>
          <button
            v-for="country in recordCountryOptions.slice(0, 6)"
            :key="`country-chip-${country.label}`"
            class="button button--ghost"
            :class="{ 'button--primary': recordLocationFilter.country === country.label }"
            @click="
              recordLocationFilter.country = country.label;
              recordLocationFilter.region = '';
            "
          >
            {{ country.label }}
          </button>
        </div>

        <div class="sheet-table-wrap">
          <table class="sheet-table">
            <thead>
              <tr>
                <th>?쇱떆</th>
                <th>移댄뀒怨좊━</th>
                <th>?쒕ぉ</th>
                <th>諛⑸Ц ?꾩튂</th>
                <th>?먭툑??/th>
                <th>KRW ?섏궛</th>
                <th>誘몃뵒??/th>
                <th>?묒뾽</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="record in filteredTravelRecords" :key="record.id">
                <td>{{ formatRecordDateTime(record) || '-' }}</td>
                <td>{{ record.category }}</td>
                <td>{{ record.title }}</td>
                <td>{{ [record.country, record.region, record.placeName].filter(Boolean).join(' / ') || '-' }}</td>
                <td>{{ formatCurrencyByCode(record.amount, record.currencyCode) }}</td>
                <td>{{ formatCurrency(record.amountKrw) }}</td>
                <td class="travel-record-media-count">
                  <strong>?ъ쭊 {{ photoCountForRecord(record.id) }}??/strong>
                  <small>?곸닔利?{{ receiptCountForRecord(record.id) }}媛?/small>
                </td>
                <td class="sheet-table__actions">
                  <button class="button button--ghost" @click="handleEditRecord(record)">?섏젙</button>
                  <button class="button button--ghost" @click="handleEditRecord(record)">誘몃뵒??/button>
                  <button class="button button--danger" @click="emit('delete-record', record)">??젣</button>
                </td>
              </tr>
              <tr v-if="!filteredTravelRecords.length">
                <td colspan="8" class="sheet-table__empty">
                  {{ (travelPlan?.records ?? []).length ? '?좏깮??援??/?꾩떆 移댄뀒怨좊━??留욌뒗 湲곕줉???놁뒿?덈떎.' : '?깅줉???ы뻾 湲곕줉???놁뒿?덈떎.' }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </template>

    <template v-else-if="route === 'travel-places'">
      <section class="panel">
        <div class="panel__header">
          <div>
            <h2>?μ냼 ? 踰붿쐞</h2>
            <p>?좏깮 ?ы뻾留?蹂닿굅???꾩껜 ?ы뻾???⑹퀜?? 吏湲덇퉴吏 ?쒓렇??紐⑤뱺 ?μ냼瑜????섏씠吏?먯꽌 蹂????덉뒿?덈떎.</p>
          </div>
          <span class="panel__badge">{{ scopedAnalytics.summary.scopeLabel }}</span>
        </div>

        <div class="scope-toggle">
          <button class="button" :class="{ 'button--primary': travelStatsScope === 'PLAN' }" @click="handleScopeChange('PLAN')">?좏깮 ?ы뻾</button>
          <button class="button" :class="{ 'button--primary': travelStatsScope === 'ALL' }" @click="handleScopeChange('ALL')">?꾩껜 ?ы뻾</button>
        </div>

        <div v-if="placeLegendPlans.length" class="travel-place-legend">
          <article v-for="plan in placeLegendPlans" :key="plan.id" class="travel-place-legend__item">
            <span class="travel-place-legend__dot" :style="{ '--travel-plan-color': normalizeColorHex(plan.colorHex) }" />
            <div>
              <strong>{{ plan.name }}</strong>
              <small>{{ plan.destination }} 쨌 {{ plan.recordCount }}嫄?/small>
            </div>
          </article>
        </div>
      </section>

      <div v-if="hasScopedTravelData()" class="content-grid content-grid--travel content-grid--travel-records">
        <section class="panel panel--map-fill">
          <div class="panel__header">
            <div>
              <h2>?꾩껜 ?μ냼 吏??/h2>
              <p>?ы뻾留덈떎 ?ㅻⅨ ?됱긽 ??쇰줈 ?쒖떆?⑸땲?? ????꾨Ⅴ硫?寃곗젣 湲덉븸, ?쒓컙, ?ъ쭊源뚯? ?④퍡 蹂????덉뒿?덈떎.</p>
            </div>
            <span class="panel__badge">{{ scopedAnalytics.mapMarkers.length }}媛??</span>
          </div>

          <TravelMapPanel
            :markers="scopedAnalytics.mapMarkers"
            :selected-point="null"
            :enable-pick-location="false"
            :marker-radius="6"
            hint-title="?μ냼 ? 蹂닿린"
            hint-text="?ы뻾 ?됱긽蹂??묒? ??쇰줈 紐⑤뱺 ?꾩튂瑜??쒕늿??蹂닿퀬, ?앹뾽?먯꽌 ?ъ쭊怨?吏異??뺣낫瑜?諛붾줈 ?뺤씤?????덉뒿?덈떎."
          />
        </section>

        <section class="panel">
          <div class="panel__header">
            <div>
              <h2>?쒓렇???μ냼 紐⑸줉</h2>
              <p>媛숈? ?μ냼???ы뻾蹂꾨줈 臾띠뼱??諛⑸Ц ?잛닔? ?꾩쟻 ?ъ슜 湲덉븸???④퍡 蹂댁뿬以띾땲??</p>
            </div>
          </div>

          <div class="sheet-table-wrap">
            <table class="sheet-table">
              <thead>
                <tr>
                  <th>?ы뻾</th>
                  <th>?μ냼</th>
                  <th>援?? / 吏??/th>
                  <th>?꾩쟻 ?ъ슜??/th>
                  <th>諛⑸Ц ?잛닔</th>
                  <th>理쒓렐 諛⑸Ц??/th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="place in travelPlaceRows" :key="place.key">
                  <td>
                    <div class="travel-place-name">
                      <span class="travel-place-name__dot" :style="{ '--travel-plan-color': normalizeColorHex(place.colorHex) }" />
                      <span>{{ place.planName }}</span>
                    </div>
                  </td>
                  <td>{{ place.placeName }}</td>
                  <td>{{ [place.country, place.region].filter(Boolean).join(' / ') || '-' }}</td>
                  <td>{{ formatCurrency(place.totalKrw) }}</td>
                  <td>{{ place.count }}??/td>
                  <td>{{ place.latestDate ? formatShortDate(place.latestDate) : '-' }}</td>
                </tr>
                <tr v-if="!travelPlaceRows.length">
                  <td colspan="6" class="sheet-table__empty">?꾩쭅 吏?꾩뿉 ?쒖떆???꾩튂 ?쒓렇媛 ?놁뒿?덈떎.</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
      </div>

      <section v-else class="panel">
        <p class="panel__empty">癒쇱? ?ы뻾 怨꾪쉷怨?吏異?湲곕줉???깅줉??二쇱꽭??</p>
      </section>
    </template>

    <template v-else-if="route === 'travel-stats'">
      <section class="panel">
        <div class="panel__header">
          <div>
            <h2>?듦퀎 踰붿쐞</h2>
            <p>?좏깮???ы뻾留?蹂쇱?, ?꾩껜 ?ы뻾???⑹퀜 蹂쇱? 諛붾줈 諛붽? ???덉뒿?덈떎.</p>
          </div>
          <span class="panel__badge">{{ scopedAnalytics.summary.scopeLabel }}</span>
        </div>

        <div class="scope-toggle">
          <button class="button" :class="{ 'button--primary': travelStatsScope === 'PLAN' }" @click="handleScopeChange('PLAN')">?좏깮 ?ы뻾</button>
          <button class="button" :class="{ 'button--primary': travelStatsScope === 'ALL' }" @click="handleScopeChange('ALL')">?꾩껜 ?ы뻾</button>
        </div>

        <p class="travel-scope-note">
          {{ travelStatsScope === 'ALL' ? `?꾩껜 ?ы뻾 ${scopedAnalytics.summary.includedPlanCount}嫄댁쓣 ?⑹궛???듦퀎?낅땲??` : `${travelPlan?.name || '?좏깮???ы뻾'} 湲곗? ?듦퀎?낅땲??` }}
        </p>
      </section>

      <div v-if="hasScopedTravelData()" class="content-grid content-grid--travel">
        <section class="panel">
          <div class="panel__header">
            <div>
              <h2>?붿튂?섏씠 怨꾩궛湲?/h2>
              <p>湲곕줉??珥??ъ슜?≪뿉 異붽?湲덉쓣 ?뷀븯怨??몄썝?섏뿉 留욊쾶 1?몃떦 湲덉븸??怨꾩궛?⑸땲??</p>
            </div>
            <span class="panel__badge">{{ formatCurrency(splitPerPerson) }}</span>
          </div>

          <div class="travel-form-grid">
            <label class="field">
              <span class="field__label">湲곕줉??珥??ъ슜??/span>
              <input :value="formatCurrency(splitBaseTotal)" type="text" readonly />
            </label>
            <label class="field">
              <span class="field__label">異붽?湲?/span>
              <input v-model="splitForm.extraAmount" type="number" min="0" step="1" placeholder="?? 5000" />
            </label>
            <label class="field">
              <span class="field__label">?몄썝??/span>
              <input v-model="splitForm.peopleCount" type="number" min="1" step="1" placeholder="?? 4" />
            </label>
            <label class="field">
              <span class="field__label">?⑹궛 珥앹븸</span>
              <input :value="formatCurrency(splitGrandTotal)" type="text" readonly />
            </label>
          </div>

          <div class="travel-split-result">
            <div>
              <span>1?몃떦 湲덉븸</span>
              <strong>{{ formatCurrency(splitPerPerson) }}</strong>
              <small>湲곕낯 ?몄썝 {{ scopedAnalytics.summary.headCount }}紐?/ ?꾩옱 怨꾩궛 {{ splitHeadCount }}紐?/small>
            </div>
          </div>
        </section>

        <section class="panel">
          <div class="panel__header">
            <div>
              <h2>諛⑸Ц 援??? 吏??/h2>
              <p>吏異?湲곕줉???쒓렇??援??, 吏?? ?μ냼瑜??쒕늿??紐⑥븘 遊낅땲??</p>
            </div>
          </div>

          <div class="travel-visit-grid">
            <article class="travel-visit-card">
              <strong>援??</strong>
              <div class="travel-visit-chips">
                <span v-for="country in scopedAnalytics.visitedCountries" :key="country" class="chip chip--neutral">{{ country }}</span>
                <p v-if="!scopedAnalytics.visitedCountries.length" class="panel__empty">援?? ?쒓렇媛 ?놁뒿?덈떎.</p>
              </div>
            </article>
            <article class="travel-visit-card">
              <strong>吏??/strong>
              <div class="travel-visit-chips">
                <span v-for="region in scopedAnalytics.visitedRegions" :key="region" class="chip chip--neutral">{{ region }}</span>
                <p v-if="!scopedAnalytics.visitedRegions.length" class="panel__empty">吏???쒓렇媛 ?놁뒿?덈떎.</p>
              </div>
            </article>
            <article class="travel-visit-card">
              <strong>?μ냼</strong>
              <div class="travel-visit-chips">
                <span v-for="place in scopedAnalytics.visitedPlaces" :key="place" class="chip chip--neutral">{{ place }}</span>
                <p v-if="!scopedAnalytics.visitedPlaces.length" class="panel__empty">?μ냼 ?쒓렇媛 ?놁뒿?덈떎.</p>
              </div>
            </article>
          </div>
        </section>
      </div>

      <section v-if="hasScopedTravelData()" class="panel">
        <div class="panel__header">
          <div>
            <h2>吏異?吏??/h2>
            <p>????꾨Ⅴ硫??μ냼, ?ъ슜 湲덉븸, ?쒓컙, ?낅줈?쒕맂 ?ъ쭊???④퍡 ?뺤씤?????덉뒿?덈떎.</p>
          </div>
        </div>

        <TravelMapPanel
          :markers="scopedAnalytics.mapMarkers"
          :selected-point="null"
          :enable-pick-location="false"
          hint-title="吏異?? 蹂닿린"
          hint-text="????뚮윭 ?ъ슜 湲덉븸, 諛⑸Ц ?쒓컙, ?곌껐???ъ쭊???④퍡 ?뺤씤?????덉뒿?덈떎."
        />
      </section>

      <section v-if="hasScopedTravelData()" class="chart-grid chart-grid--travel">
        <DonutChartCard
          title="?덉궛 移댄뀒怨좊━ 鍮꾩쨷"
          subtitle="?ы뻾 ?덉궛???대뵒??吏묒쨷?섏뼱 ?덈뒗吏 ?됱긽?쇰줈 ?뺤씤?⑸땲??"
          :items="plannedCategoryChart"
          :format-value="formatCurrency"
          empty-text="?덉궛 ?곗씠?곌? ?놁뒿?덈떎."
        />
        <DonutChartCard
          title="?ㅼ궗??移댄뀒怨좊━ 鍮꾩쨷"
          subtitle="?ㅼ젣 ?ъ슜 湲덉븸???먮쫫??移댄뀒怨좊━蹂꾨줈 遊낅땲??"
          :items="actualCategoryChart"
          :format-value="formatCurrency"
          empty-text="湲곕줉 ?곗씠?곌? ?놁뒿?덈떎."
        />
        <BarChartCard
          title="?쇱옄蹂?吏異?
          subtitle="?ы뻾 ?좎쭨 湲곗??쇰줈 吏異??먮쫫??留됰?洹몃옒?꾨줈 遊낅땲??"
          :items="dailyChart"
          :format-value="formatCurrency"
          empty-text="?쇱옄蹂??곗씠?곌? ?놁뒿?덈떎."
        />
        <BarChartCard
          title="?듯솕蹂??ъ슜??
          subtitle="?명솕 ?ъ슜?≪쓣 KRW 湲곗??쇰줈 鍮꾧탳?⑸땲??"
          :items="currencyChart"
          :format-value="formatCurrency"
          empty-text="?듯솕蹂?湲곕줉???놁뒿?덈떎."
        />
      </section>
    </template>

    <template v-else-if="isMediaLibraryRoute">
      <section class="panel">
        <div class="panel__header">
          <div>
            <h2>{{ galleryPageMeta.title }}</h2>
            <p>{{ galleryPageMeta.description }}</p>
          </div>
          <span class="panel__badge">{{ galleryPageMeta.count }}媛?/span>
        </div>

        <div class="scope-toggle">
          <button class="button" :class="{ 'button--primary': travelStatsScope === 'PLAN' }" @click="handleScopeChange('PLAN')">?좏깮 ?ы뻾</button>
          <button class="button" :class="{ 'button--primary': travelStatsScope === 'ALL' }" @click="handleScopeChange('ALL')">?꾩껜 ?ы뻾</button>
        </div>

        <div class="travel-media-summary-grid">
          <article v-for="card in gallerySummaryCards" :key="card.key" class="travel-media-summary-card">
            <span>{{ card.label }}</span>
            <strong>{{ card.value }}媛?/strong>
            <small>{{ card.caption }}</small>
          </article>
        </div>

        <div v-if="visiblePlanCards.length" class="travel-plan-card-grid">
          <article v-for="planCard in visiblePlanCards" :key="planCard.id" class="travel-plan-card">
            <img v-if="planCard.coverUrl" :src="planCard.coverUrl" :alt="planCard.name" class="travel-plan-card__media" />
            <div v-else class="travel-plan-card__placeholder">????ъ쭊 ?놁쓬</div>
            <div class="travel-plan-card__copy">
              <strong>{{ planCard.name }}</strong>
              <small>{{ planCard.destination }}</small>
              <small>{{ planCard.period || '湲곌컙 誘몄젙' }}</small>
              <small>{{ planCardCountLabel(planCard) }} 쨌 湲곕줉 {{ planCard.recordCount }}嫄?/small>
              <small>?ъ슜??{{ formatCurrency(planCard.actualTotalKrw) }}</small>
            </div>
            <div class="travel-plan-card__actions">
              <button class="button button--ghost" @click="handleOpenPlanCard(planCard.id)">???ы뻾 蹂닿린</button>
            </div>
          </article>
        </div>

        <div v-if="showGalleryFilterChips" class="scope-toggle scope-toggle--filters">
          <button class="button" :class="{ 'button--primary': galleryFilter === 'ALL' }" @click="galleryFilter = 'ALL'">?꾩껜</button>
          <button class="button" :class="{ 'button--primary': galleryFilter === 'PHOTO' }" @click="galleryFilter = 'PHOTO'">?ъ쭊 {{ gallerySummary.photos }}</button>
          <button class="button" :class="{ 'button--primary': galleryFilter === 'RECEIPT' }" @click="galleryFilter = 'RECEIPT'">?곸닔利?{{ gallerySummary.receipts }}</button>
        </div>
      </section>

      <div v-if="galleryItems.length" class="travel-media-grid travel-media-grid--gallery">
        <article v-for="media in galleryItems" :key="media.id" class="travel-media-card">
          <img v-if="isImageMedia(media)" :src="media.contentUrl" :alt="media.originalFileName" class="travel-media-thumb" />
          <div v-else class="travel-media-thumb travel-media-thumb--receipt">PDF</div>
          <div class="travel-media-copy">
            <div class="travel-media-tags">
              <span class="chip chip--neutral">{{ mediaTypeLabel(media.mediaType) }}</span>
              <span class="chip chip--neutral">{{ media.planName }}</span>
            </div>
            <strong>{{ media.caption || media.title || media.originalFileName }}</strong>
            <small>{{ media.uploadedBy }} 쨌 {{ formatRecordDateTime(media) || '-' }}</small>
            <small>{{ [media.country, media.region, media.placeName].filter(Boolean).join(' / ') || '?꾩튂 ?뺣낫 ?놁쓬' }}</small>
            <small>{{ formatCurrencyByCode(media.amount, media.currencyCode) }} / {{ formatCurrency(media.amountKrw) }}</small>
          </div>
          <div class="travel-media-actions">
            <a class="button button--ghost" :href="media.contentUrl" target="_blank" rel="noreferrer">?닿린</a>
            <button class="button button--danger" @click="emit('delete-media', media)">??젣</button>
          </div>
        </article>
      </div>

      <section v-else class="panel">
        <p class="panel__empty">{{ galleryPageMeta.emptyText }}</p>
      </section>
    </template>
  </div>
</template>
