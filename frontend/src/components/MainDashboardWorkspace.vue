<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { GridStack } from 'gridstack'
import 'gridstack/dist/gridstack.min.css'
import {
  createEntry,
  fetchCategories,
  fetchCompare,
  fetchDashboard,
  fetchDriveFolderDestinations,
  fetchDriveHomeSummary,
  fetchDrivePhotos,
  fetchDriveRecentFiles,
  fetchLayoutSetting,
  fetchPaymentMethods,
  fetchTravelPhotoFrameMedia,
  fetchTravelPlans,
  saveLayoutSetting,
} from '../lib/api'
import { DASHBOARD_GRID_COLUMNS } from '../features/palette/types'
import { buildThumbnailUrl, THUMBNAIL_VARIANTS } from '../lib/mediaPreview'
import TravelMiniLocationMap from './TravelMiniLocationMap.vue'
import { formatDateTime } from '../lib/uiFormat'

const props = defineProps({
  currentUser: {
    type: Object,
    required: true,
  },
  items: {
    type: Array,
    default: () => [],
  },
})

const emit = defineEmits(['navigate'])

const MAIN_DASHBOARD_STORAGE_VERSION = 'v6'
const MAIN_DASHBOARD_SCOPE = 'main'
const MAIN_DASHBOARD_LAYOUT_SCOPE = 'main-dashboard'
const MAIN_DASHBOARD_LAYOUT_VERSION = 6
const PAYMENT_SELECTION_STORAGE_VERSION = 'v1'
const SUMMARY_CACHE_STORAGE_VERSION = 'v2'
const MAIN_DASHBOARD_GRID_MARGIN = 4
const MAIN_DASHBOARD_GRID_GAP = MAIN_DASHBOARD_GRID_MARGIN * 2
const REMOTE_LAYOUT_SAVE_DELAY_MS = 800
const PHOTO_FRAME_FETCH_SIZE = 5000
const PHOTO_FRAME_RANDOM_INTERVAL_OPTIONS = [
  { value: 'refresh', label: '새로고침 시 변경' },
  { value: '5000', label: '5초' },
  { value: '10000', label: '10초' },
  { value: '30000', label: '30초' },
  { value: '60000', label: '1분' },
]

const paletteTemplates = [
  { id: 'household-summary', type: 'household-summary', label: '가계부 종합', options: {} },
  { id: 'household-week-expense', type: 'household-metric', label: '이번 주 사용금액', options: { metric: 'weekExpense' } },
  { id: 'household-week-income', type: 'household-metric', label: '이번 주 수입', options: { metric: 'weekIncome' } },
  { id: 'household-month-expense', type: 'household-metric', label: '이번 달 사용금액', options: { metric: 'monthExpense' } },
  { id: 'household-month-income', type: 'household-metric', label: '이번 달 수입', options: { metric: 'monthIncome' } },
  { id: 'household-payment', type: 'household-payment', label: '결제수단 사용금액', options: {} },
  { id: 'household-week-compare', type: 'household-compare', label: '이번 주/저번 주 비교', options: { period: 'week' } },
  { id: 'household-month-compare', type: 'household-compare', label: '이번 달/저번 달 비교', options: { period: 'month' } },
  { id: 'quick-entry', type: 'quick-entry', label: '빠른 금액 입력', options: {} },
  { id: 'travel-summary', type: 'travel-summary', label: '여행 요약', options: {} },
  { id: 'drive-summary', type: 'drive-summary', label: '드라이브 요약', options: {} },
  { id: 'photo-frame', type: 'photo-frame', label: '사진 액자', options: {} },
  { id: 'drive-capacity', type: 'drive-capacity', label: '드라이브 용량', options: {} },
  { id: 'drive-recent-files', type: 'drive-recent-files', label: '최근 저장 파일', options: {} },
  { id: 'quick-actions', type: 'quick-actions', label: '빠른 단축 기능', options: {} },
  { id: 'feature-links', type: 'feature-links', label: '기능 바로가기', options: {} },
]

const defaultSizeByType = {
  'household-summary': '3x2',
  'household-metric': '2x2',
  'household-payment': '3x2',
  'household-compare': '3x2',
  'quick-entry': '3x3',
  'travel-summary': '3x2',
  'drive-summary': '3x2',
  'photo-frame': '3x3',
  'drive-capacity': '3x2',
  'drive-recent-files': '3x2',
  'quick-actions': '3x2',
  'feature-links': '3x2',
}

const minSpanByType = {
  'household-summary': { w: 3, h: 2 },
  'household-metric': { w: 2, h: 1 },
  'household-payment': { w: 3, h: 2 },
  'household-compare': { w: 3, h: 2 },
  'quick-entry': { w: 3, h: 3 },
  'travel-summary': { w: 3, h: 2 },
  'drive-summary': { w: 3, h: 2 },
  'photo-frame': { w: 3, h: 2 },
  'drive-capacity': { w: 3, h: 2 },
  'drive-recent-files': { w: 3, h: 2 },
  'quick-actions': { w: 2, h: 2 },
  'feature-links': { w: 2, h: 2 },
}

const maxSpanByType = {
  'household-summary': { w: 6, h: 4 },
  'household-metric': { w: 4, h: 3 },
  'household-payment': { w: 5, h: 4 },
  'household-compare': { w: 5, h: 4 },
  'quick-entry': { w: 5, h: 5 },
  'travel-summary': { w: 5, h: 4 },
  'drive-summary': { w: 5, h: 4 },
  'photo-frame': { w: 6, h: 5 },
  'drive-capacity': { w: 5, h: 4 },
  'drive-recent-files': { w: 5, h: 4 },
  'quick-actions': { w: 4, h: 4 },
  'feature-links': { w: 4, h: 4 },
}

const fallbackMinSpan = { w: 2, h: 2 }
const fallbackMaxSpan = { w: 6, h: 5 }

const defaultPalettes = [
  { id: 'main-household-summary', type: 'household-summary', size: '3x2', position: { x: 0, y: 0 }, visible: true, options: {} },
  { id: 'main-month-expense', type: 'household-metric', size: '2x2', position: { x: 3, y: 0 }, visible: true, options: { metric: 'monthExpense' } },
  { id: 'main-month-income', type: 'household-metric', size: '2x2', position: { x: 5, y: 0 }, visible: true, options: { metric: 'monthIncome' } },
  { id: 'main-week-expense', type: 'household-metric', size: '2x2', position: { x: 7, y: 0 }, visible: true, options: { metric: 'weekExpense' } },
  { id: 'main-week-income', type: 'household-metric', size: '2x2', position: { x: 0, y: 2 }, visible: true, options: { metric: 'weekIncome' } },
  { id: 'main-quick-entry', type: 'quick-entry', size: '3x3', position: { x: 2, y: 2 }, visible: true, options: {} },
  { id: 'main-payment', type: 'household-payment', size: '3x2', position: { x: 5, y: 2 }, visible: true, options: {} },
  { id: 'main-week-compare', type: 'household-compare', size: '3x2', position: { x: 0, y: 5 }, visible: true, options: { period: 'week' } },
  { id: 'main-month-compare', type: 'household-compare', size: '3x2', position: { x: 3, y: 5 }, visible: true, options: { period: 'month' } },
  { id: 'main-travel-summary', type: 'travel-summary', size: '3x2', position: { x: 6, y: 5 }, visible: true, options: {} },
  { id: 'main-drive-summary', type: 'drive-summary', size: '3x2', position: { x: 0, y: 7 }, visible: true, options: {} },
  { id: 'main-drive-recent-files', type: 'drive-recent-files', size: '3x2', position: { x: 3, y: 7 }, visible: true, options: {} },
  { id: 'main-drive-capacity', type: 'drive-capacity', size: '3x2', position: { x: 6, y: 7 }, visible: true, options: {} },
  { id: 'main-photo-frame', type: 'photo-frame', size: '3x3', position: { x: 0, y: 9 }, visible: true, options: {} },
  { id: 'main-feature-links', type: 'feature-links', size: '3x2', position: { x: 3, y: 9 }, visible: true, options: {} },
  { id: 'main-quick-actions', type: 'quick-actions', size: '3x2', position: { x: 6, y: 9 }, visible: true, options: {} },
]

const metricDefinitions = {
  weekExpense: { statKey: 'week', field: 'expense', label: '이번 주 사용금액', tone: 'negative' },
  weekIncome: { statKey: 'week', field: 'income', label: '이번 주 수입', tone: 'positive' },
  monthExpense: { statKey: 'month', field: 'expense', label: '이번 달 사용금액', tone: 'negative' },
  monthIncome: { statKey: 'month', field: 'income', label: '이번 달 수입', tone: 'positive' },
  monthBalance: { statKey: 'month', field: 'balance', label: '이번 달 순액', tone: 'balance' },
}

const featureCopy = {
  household: {
    title: '가계부',
    badge: 'Finance',
  },
  travel: {
    title: '여행',
    badge: 'Travel',
  },
  drive: {
    title: '드라이브',
    badge: 'Cloud',
  },
  admin: {
    title: '관리자',
    badge: 'Admin',
  },
}

const loading = ref(false)
const errorMessage = ref('')
const feedbackMessage = ref('')
const householdDashboard = ref(null)
const travelPortfolio = ref(null)
const driveSummary = ref(null)
const driveRecentFileItems = ref([])
const drivePhotoFileItems = ref([])
const driveFolderItems = ref([])
const photoFrameFolderPhotoItems = ref({})
const weekCompareRows = ref([])
const monthCompareRows = ref([])
const categories = ref([])
const paymentMethods = ref([])
const palettes = ref([])
const isEditMode = ref(false)
const toolsOpen = ref(false)
const selectedTemplateId = ref('household-month-expense')
const selectedPaymentMethodId = ref('')
const quickSubmitting = ref(false)
const gridElement = ref(null)
const toolsPanelRef = ref(null)
const toolsButtonRef = ref(null)
const cellHeight = ref(92)

const quickEntry = reactive({
  entryDate: todayIso(),
  entryType: 'EXPENSE',
  title: '',
  amount: '',
  categoryGroupId: '',
  categoryDetailId: '',
  paymentMethodId: '',
})

let grid = null
let resizeObserver = null
let dragStartLayout = new Map()
let rebuildTimer = 0
let summaryLoadSequence = 0
let paletteRemoteHydrationSequence = 0
let paletteRemoteSaveTimer = 0
let pendingPaletteRemotePayload = null
let paletteChangedDuringRemoteHydration = false
let photoFrameClockTimer = 0
const photoFrameClock = ref(Date.now())
const photoFrameRefreshSeed = Date.now()
const photoFrameSettings = reactive({
  open: false,
  paletteId: '',
  source: 'all',
  mode: 'latest',
  fixedPhotoId: '',
  driveFolderId: '',
  travelPlanId: '',
  sort: 'recent',
  randomInterval: 'refresh',
  loading: false,
  error: '',
})
const photoFrameDetail = reactive({
  open: false,
  photo: null,
})

const userStorageId = computed(() => props.currentUser?.id || props.currentUser?.loginId || 'anonymous')
const storageKey = computed(() => `calen-main-dashboard-palettes:${MAIN_DASHBOARD_STORAGE_VERSION}:${userStorageId.value}:${MAIN_DASHBOARD_SCOPE}`)
const paymentSelectionStorageKey = computed(() => `calen-main-dashboard-payment:${PAYMENT_SELECTION_STORAGE_VERSION}:${userStorageId.value}`)
const summaryCacheStorageKey = computed(() => `calen-main-dashboard-summary:${SUMMARY_CACHE_STORAGE_VERSION}:${userStorageId.value}`)
const visiblePalettes = computed(() => palettes.value.filter((palette) => palette.visible !== false))
const hiddenPalettes = computed(() => palettes.value.filter((palette) => palette.visible === false))
const layoutKey = computed(() =>
  visiblePalettes.value
    .map((palette) => `${palette.id}:${palette.position?.x ?? 0}:${palette.position?.y ?? 0}:${palette.size}:${palette.visible}`)
    .join('|'),
)
const guideRowCount = computed(() => Math.max(
  1,
  ...visiblePalettes.value.map((palette) => {
    const span = mainSpanForPalette(palette)
    return (palette.position?.y ?? 0) + span.h
  }),
))
const guideCellCount = computed(() => DASHBOARD_GRID_COLUMNS * guideRowCount.value)
const mainDashboardGridStyle = computed(() => ({
  '--main-dashboard-cell-height': `${cellHeight.value}px`,
  '--main-dashboard-grid-gap': `${MAIN_DASHBOARD_GRID_GAP}px`,
  '--main-dashboard-grid-margin': `${MAIN_DASHBOARD_GRID_MARGIN}px`,
}))
const featureItems = computed(() => props.items.map((item) => ({
  ...item,
  ...(featureCopy[item.key] ?? {}),
})))
const quickGroups = computed(() => categories.value.filter((group) => group.entryType === quickEntry.entryType))
const quickDetails = computed(() => {
  const group = categories.value.find((item) => String(item.id) === String(quickEntry.categoryGroupId))
  return group?.details ?? []
})
const paymentOptions = computed(() => {
  const fromBreakdown = (householdDashboard.value?.paymentBreakdown ?? [])
    .map((item) => ({
      id: item.paymentMethodId,
      name: item.paymentMethodName,
      kind: item.kind,
    }))
    .filter((item) => item.id != null)
  const fromMethods = paymentMethods.value
    .map((item) => ({
      id: item.id,
      name: item.name,
      kind: item.kind,
    }))
    .filter((item) => item.id != null)
  const merged = new Map()
  ;[...fromBreakdown, ...fromMethods].forEach((item) => {
    merged.set(String(item.id), item)
  })
  return Array.from(merged.values())
})
const selectedPaymentSummary = computed(() => {
  const breakdown = householdDashboard.value?.paymentBreakdown ?? []
  if (!breakdown.length) {
    return null
  }
  const selected = selectedPaymentMethodId.value
    ? breakdown.find((item) => String(item.paymentMethodId) === String(selectedPaymentMethodId.value))
    : null
  return selected ?? breakdown.slice().sort((left, right) => Number(right.totalAmount ?? 0) - Number(left.totalAmount ?? 0))[0]
})
const travelPlans = computed(() => travelPortfolio.value?.plans ?? [])
const travelSummary = computed(() => {
  const plans = travelPlans.value
  return {
    planCount: plans.length,
    plannedTotal: plans.reduce((sum, plan) => sum + Number(plan.plannedTotalKrw ?? plan.totalBudgetKrw ?? 0), 0),
    actualTotal: plans.reduce((sum, plan) => sum + Number(plan.actualTotalKrw ?? plan.totalExpenseKrw ?? 0), 0),
    recordCount: plans.reduce((sum, plan) => sum + Number(plan.recordCount ?? plan.memoryRecordCount ?? 0), 0),
    mediaCount: plans.reduce((sum, plan) => sum + Number(plan.mediaItemCount ?? 0), 0),
  }
})
const recentTravelPlans = computed(() => travelPlans.value.slice(0, 3))
const allRecentDriveFiles = computed(() => (
  driveRecentFileItems.value.length
    ? driveRecentFileItems.value
    : (driveSummary.value?.recentFiles ?? [])
))
const recentDriveFiles = computed(() => allRecentDriveFiles.value.slice(0, 5))
const driveCapacity = computed(() => {
  const usedBytes = Number(driveSummary.value?.usedBytes ?? driveSummary.value?.driveUsedBytes ?? 0)
  const totalBytes = Number(
    driveSummary.value?.capacityBytes
      ?? driveSummary.value?.totalCapacityBytes
      ?? driveSummary.value?.storageCapacityBytes
      ?? driveSummary.value?.providerCapacityBytes
      ?? 0,
  )
  return {
    usedBytes,
    totalBytes,
    percent: totalBytes > 0 ? Math.min(100, Math.round((usedBytes / totalBytes) * 100)) : 0,
  }
})
const allDrivePhotoItems = computed(() => collectDrivePhotoItems(
  drivePhotoFileItems.value.length ? drivePhotoFileItems.value : allRecentDriveFiles.value,
))
const allTravelPhotoItems = computed(() => collectTravelPhotoItems())
const photoFrameItems = computed(() => [
  ...allDrivePhotoItems.value,
  ...allTravelPhotoItems.value,
])
const photoFrameSettingsPhotos = computed(() => photoFramePhotosForOptions(photoFrameSettings))
const quickActionItems = computed(() => [
  { key: 'household', label: '가계부 입력', meta: '달력/대시보드', route: 'household' },
  { key: 'travel', label: '여행 기록', meta: '예산/사진/지도', route: 'travel' },
  { key: 'drive', label: '파일 저장', meta: '드라이브 열기', route: 'drive' },
  { key: 'launcher', label: '메인으로', meta: '종합 보기', route: 'launcher' },
])
const dashboardOverviewCards = computed(() => {
  const month = quickStat('month')
  const week = quickStat('week')
  const monthBalance = Number(month.balance ?? 0)
  const travel = travelSummary.value
  const drive = driveCapacity.value
  return [
    {
      key: 'month-balance',
      eyebrow: 'HOUSEHOLD',
      label: '이번 달 현금흐름',
      value: formatCurrency(monthBalance),
      meta: `수입 ${formatCurrency(month.income)} · 지출 ${formatCurrency(month.expense)}`,
      tone: monthBalance >= 0 ? 'positive' : 'negative',
    },
    {
      key: 'week-expense',
      eyebrow: 'WEEK',
      label: '이번 주 지출',
      value: formatCurrency(week.expense),
      meta: `${formatNumber(week.entryCount)}건 기록`,
      tone: 'negative',
    },
    {
      key: 'travel-actual',
      eyebrow: 'TRAVEL',
      label: '여행 사용',
      value: formatCurrency(travel.actualTotal),
      meta: `여행 ${formatNumber(travel.planCount)}개 · 기록 ${formatNumber(travel.recordCount)}건`,
      tone: 'teal',
    },
    {
      key: 'drive-capacity',
      eyebrow: 'DRIVE',
      label: '드라이브 저장',
      value: formatBytes(drive.usedBytes),
      meta: drive.totalBytes
        ? `${drive.percent}% 사용 · 최근 파일 ${formatNumber(recentDriveFiles.value.length)}개`
        : `최근 파일 ${formatNumber(recentDriveFiles.value.length)}개`,
      tone: 'mint',
    },
  ]
})

function todayIso() {
  const now = new Date()
  const offset = now.getTimezoneOffset() * 60000
  return new Date(now.getTime() - offset).toISOString().slice(0, 10)
}

function clone(value) {
  return JSON.parse(JSON.stringify(value))
}

function formatCurrency(value) {
  return new Intl.NumberFormat('ko-KR', {
    style: 'currency',
    currency: 'KRW',
    maximumFractionDigits: 0,
  }).format(Number(value ?? 0))
}

function formatNumber(value) {
  return new Intl.NumberFormat('ko-KR').format(Number(value ?? 0))
}

function formatBytes(bytes) {
  const value = Number(bytes || 0)
  if (!value) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const index = Math.min(Math.floor(Math.log(value) / Math.log(1024)), units.length - 1)
  return `${(value / (1024 ** index)).toFixed(index === 0 ? 0 : 1)} ${units[index]}`
}

function fileName(item) {
  return item?.fileOriginName || item?.originalFileName || item?.name || item?.title || '파일'
}

function fileExtension(item) {
  const explicit = String(item?.fileFormat || item?.extension || '').toLowerCase()
  if (explicit) return explicit
  const name = fileName(item)
  return name.includes('.') ? name.split('.').pop().toLowerCase() : ''
}

function isImageFile(item) {
  const extension = fileExtension(item)
  const contentType = String(item?.contentType || item?.mimeType || '').toLowerCase()
  return contentType.startsWith('image/') || ['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp', 'svg'].includes(extension)
}

function buildDriveThumbnailPath(item) {
  if (!item) return ''
  if (item.thumbnailUrl) return item.thumbnailUrl
  if (item.contentUrl) return item.contentUrl
  if (item.id == null) return ''
  return `/api/file/${item.id}/thumbnail`
}

function buildDriveOpenPath(item) {
  if (!item) return ''
  if (item.downloadUrl) return item.downloadUrl
  if (item.contentUrl) return item.contentUrl
  if (item.id == null) return ''
  return `/api/file/${item.id}/download`
}

function collectDrivePhotoItems(items) {
  return (items ?? [])
    .filter((item) => isImageFile(item))
    .map((item) => {
      const thumbnailUrl = buildDriveThumbnailPath(item)
      const originalUrl = buildDriveOpenPath(item) || thumbnailUrl
      return {
        id: `drive-${item.id ?? fileName(item)}`,
        rawId: item.id == null ? '' : String(item.id),
        sourceType: 'drive',
        title: fileName(item),
        source: '드라이브',
        imageUrl: originalUrl,
        thumbnailUrl,
        openUrl: originalUrl,
        parentId: item.parentId == null ? '' : String(item.parentId),
        date: item.lastModifyDate || item.uploadDate || '',
        time: '',
        travelType: '드라이브 사진',
        location: [item.country, item.region, item.placeName].filter(Boolean).join(' · '),
        latitude: item.latitude ?? null,
        longitude: item.longitude ?? null,
        gpsLatitude: item.gpsLatitude ?? null,
        gpsLongitude: item.gpsLongitude ?? null,
        uploadedAt: item.uploadDate || item.lastModifyDate || '',
        updatedAt: item.lastModifyDate || item.uploadDate || '',
      }
    })
    .filter((item) => item.imageUrl || item.thumbnailUrl)
}

function collectTravelPhotoItems() {
  const photos = []
  const plansById = new Map((travelPortfolio.value?.plans ?? []).map((plan) => [String(plan.id), plan]))
  ;(travelPortfolio.value?.mediaItems ?? []).forEach((item) => {
    const contentUrl = item.contentUrl || item.mediaUrl || item.imageUrl || item.thumbnailUrl || ''
    const mediaType = String(item.mediaType || '').toUpperCase()
    if (mediaType && mediaType !== 'PHOTO') return
    if (!contentUrl) return
    const thumbnailUrl = buildThumbnailUrl(contentUrl, THUMBNAIL_VARIANTS.preview) || item.thumbnailUrl || contentUrl
    const plan = plansById.get(String(item.planId || '')) || {}
    const location = [item.country, item.region, item.placeName]
      .map((value) => String(value || '').trim())
      .filter(Boolean)
      .filter((value, index, array) => array.indexOf(value) === index)
      .join(' · ')
    photos.push({
      id: `travel-${item.id ?? imageUrl}`,
      rawId: item.id == null ? '' : String(item.id),
      sourceType: 'travel',
      planId: item.planId == null ? '' : String(item.planId),
      title: item.caption || item.originalFileName || item.title || item.planName || plan.name || '여행 사진',
      source: item.planName || plan.name || '여행',
      imageUrl: contentUrl,
      thumbnailUrl,
      openUrl: contentUrl,
      date: item.expenseDate || String(item.uploadedAt || '').slice(0, 10) || plan.startDate || '',
      time: item.expenseTime || '',
      travelType: item.recordType === 'MEMORY' ? '여행 기록' : '여행 사진',
      location,
      latitude: item.latitude ?? null,
      longitude: item.longitude ?? null,
      gpsLatitude: item.gpsLatitude ?? null,
      gpsLongitude: item.gpsLongitude ?? null,
      uploadedAt: item.uploadedAt || '',
      updatedAt: item.uploadedAt || item.expenseDate || plan.updatedAt || plan.startDate || '',
    })
  })
  return photos
}
function normalizePhotoFrameOptions(options = {}) {
  const source = ['all', 'drive', 'travel', 'drive-folder', 'travel-plan'].includes(options.source) ? options.source : 'all'
  const mode = ['latest', 'fixed', 'random', 'name'].includes(options.mode) ? options.mode : 'latest'
  const sort = ['recent', 'name'].includes(options.sort) ? options.sort : 'recent'
  const randomIntervalValue = String(options.randomInterval || 'refresh')
  const randomInterval = PHOTO_FRAME_RANDOM_INTERVAL_OPTIONS.some((item) => item.value === randomIntervalValue) ? randomIntervalValue : 'refresh'
  return {
    source,
    mode,
    sort,
    randomInterval,
    fixedPhotoId: String(options.fixedPhotoId || ''),
    driveFolderId: String(options.driveFolderId || ''),
    travelPlanId: String(options.travelPlanId || ''),
  }
}

function folderPhotosFor(folderId) {
  const key = String(folderId || '')
  return key ? collectDrivePhotoItems(photoFrameFolderPhotoItems.value[key] || []) : []
}

function photoFramePhotosForOptions(options = {}) {
  const normalized = normalizePhotoFrameOptions(options)
  if (normalized.source === 'drive') {
    return allDrivePhotoItems.value
  }
  if (normalized.source === 'travel') {
    return allTravelPhotoItems.value
  }
  if (normalized.source === 'drive-folder') {
    return folderPhotosFor(normalized.driveFolderId)
  }
  if (normalized.source === 'travel-plan') {
    return allTravelPhotoItems.value.filter((photo) => String(photo.planId || '') === normalized.travelPlanId)
  }
  return photoFrameItems.value
}

function sortPhotoFramePhotos(items, options = {}) {
  const normalized = normalizePhotoFrameOptions(options)
  const nextItems = [...(items || [])]
  if (normalized.mode === 'name' || normalized.sort === 'name') {
    return nextItems.sort((left, right) => String(left.title || '').localeCompare(String(right.title || ''), 'ko-KR'))
  }
  return nextItems.sort((left, right) => String(right.updatedAt || '').localeCompare(String(left.updatedAt || '')))
}

function photoFrameRandomIndex(length, seed) {
  if (!length) return -1
  let hash = 0
  const text = String(seed || '')
  for (let index = 0; index < text.length; index += 1) {
    hash = ((hash << 5) - hash) + text.charCodeAt(index)
    hash |= 0
  }
  return Math.abs(hash) % length
}

function configuredPhotoFrameIntervalMs() {
  const intervals = visiblePalettes.value
    .filter((palette) => palette.type === 'photo-frame')
    .map((palette) => normalizePhotoFrameOptions(palette.options))
    .filter((options) => options.mode === 'random' && options.randomInterval !== 'refresh')
    .map((options) => Number(options.randomInterval))
    .filter((value) => Number.isFinite(value) && value > 0)
  return intervals.length ? Math.min(...intervals) : 0
}

function stopPhotoFrameClock() {
  if (photoFrameClockTimer) {
    window.clearTimeout(photoFrameClockTimer)
    photoFrameClockTimer = 0
  }
}

function schedulePhotoFrameClock() {
  stopPhotoFrameClock()
  const intervalMs = configuredPhotoFrameIntervalMs()
  if (!intervalMs || document.visibilityState === 'hidden') {
    return
  }
  const delay = Math.max(50, intervalMs - (Date.now() % intervalMs) + 25)
  photoFrameClockTimer = window.setTimeout(() => {
    photoFrameClock.value = Date.now()
    schedulePhotoFrameClock()
  }, delay)
}

function handlePhotoFrameVisibilityChange() {
  if (document.visibilityState === 'hidden') {
    stopPhotoFrameClock()
  } else {
    photoFrameClock.value = Date.now()
    schedulePhotoFrameClock()
  }
}

function photoFrameRandomSeed(palette, options) {
  if (options.randomInterval === 'refresh') {
    return `${palette?.id || ''}:${photoFrameRefreshSeed}`
  }
  const intervalMs = Number(options.randomInterval || 0)
  if (!Number.isFinite(intervalMs) || intervalMs <= 0) {
    return `${palette?.id || ''}:${photoFrameRefreshSeed}`
  }
  return `${palette?.id || ''}:${options.randomInterval}:${Math.floor(photoFrameClock.value / intervalMs)}`
}

function formatPhotoFrameDate(value) {
  const text = String(value || '').trim()
  if (!text) return ''
  const match = text.match(/\d{4}-\d{2}-\d{2}/)
  if (match) return match[0]
  return text.length >= 10 ? text.slice(0, 10) : text
}

function photoFrameInfoLine(photo) {
  if (!photo) return ''
  const parts = [
    formatPhotoFrameDate(photo.date || photo.updatedAt),
    photo.sourceType === 'travel' ? (photo.source || photo.travelType || '여행') : photo.source,
    photo.location,
  ]
    .map((value) => String(value || '').trim())
    .filter(Boolean)
  return parts.join(' · ')
}
function photoFrameDisplayItemsFor(palette) {
  const options = normalizePhotoFrameOptions(palette?.options)
  const items = sortPhotoFramePhotos(photoFramePhotosForOptions(options), options)
  if (!items.length) return []
  if (options.mode === 'fixed') {
    const fixed = items.find((photo) => String(photo.id) === options.fixedPhotoId)
    return [fixed || items[0]].filter(Boolean)
  }
  if (options.mode === 'random') {
    const index = photoFrameRandomIndex(items.length, photoFrameRandomSeed(palette, options))
    const selected = items[index]
    return [selected || items[0]].filter(Boolean)
  }
  return [items[0]].filter(Boolean)
}

function photoFrameHeroFor(palette) {
  return photoFrameDisplayItemsFor(palette)[0] || null
}

function photoFrameDashboardImageUrl(photo) {
  return photo?.imageUrl || photo?.openUrl || photo?.thumbnailUrl || ''
}

function photoFramePreviewImageUrl(photo) {
  return photo?.thumbnailUrl || photo?.imageUrl || photo?.openUrl || ''
}

function photoFrameModeLabel(palette) {
  const options = normalizePhotoFrameOptions(palette?.options)
  const sourceLabel = {
    all: '전체 사진',
    drive: '드라이브',
    travel: '여행',
    'drive-folder': '드라이브 폴더',
    'travel-plan': '특정 여행',
  }[options.source] || '전체 사진'
  const modeLabel = {
    latest: '최신',
    fixed: '고정',
    random: '랜덤',
    name: '이름순',
  }[options.mode] || '최신'
  return `${sourceLabel} · ${modeLabel}`
}

async function loadPhotoFrameFolderPhotos(folderId, sort = 'recent') {
  const key = String(folderId || '')
  if (!key) return []
  photoFrameSettings.loading = true
  photoFrameSettings.error = ''
  try {
    const items = await fetchDrivePhotos({ parentId: key, sortOption: sort, size: PHOTO_FRAME_FETCH_SIZE })
    photoFrameFolderPhotoItems.value = {
      ...photoFrameFolderPhotoItems.value,
      [key]: items || [],
    }
    return items || []
  } catch (error) {
    photoFrameSettings.error = error.message || '폴더 사진을 불러오지 못했습니다.'
    return []
  } finally {
    photoFrameSettings.loading = false
  }
}

async function refreshConfiguredPhotoFrameFolders() {
  const folderIds = Array.from(new Set(palettes.value
    .filter((palette) => palette.type === 'photo-frame')
    .map((palette) => normalizePhotoFrameOptions(palette.options))
    .filter((options) => options.source === 'drive-folder' && options.driveFolderId)
    .map((options) => options.driveFolderId)))
  for (const folderId of folderIds) {
    if (!photoFrameFolderPhotoItems.value[folderId]) {
      await loadPhotoFrameFolderPhotos(folderId)
    }
  }
}

function photoFrameSourceLabel(photo) {
  return photo ? `${photo.source} · ${photo.title}` : '사진 선택'
}

function photoFrameDetailRecordedAt(photo) {
  if (!photo?.date) {
    return ''
  }
  return formatDateTime(photo.date, photo.time) || photo.date
}

function photoFrameDetailUploadedAt(photo) {
  const value = String(photo?.uploadedAt || photo?.updatedAt || '')
  if (!value) {
    return ''
  }
  const [date, time = ''] = value.split('T')
  return formatDateTime(date, time)
}

function photoFrameDetailLocation(photo) {
  return String(photo?.location || '').trim() || '위치 정보 없음'
}

function photoFrameDetailLatitude(photo) {
  return photo?.gpsLatitude ?? photo?.latitude ?? null
}

function photoFrameDetailLongitude(photo) {
  return photo?.gpsLongitude ?? photo?.longitude ?? null
}

function openPhotoFrameDetail(photo) {
  if (!photo) {
    return
  }
  photoFrameSettings.open = false
  photoFrameDetail.photo = photo
  photoFrameDetail.open = true
}

function closePhotoFrameDetail() {
  photoFrameDetail.open = false
  photoFrameDetail.photo = null
}

function openPhotoFrameTravelMap() {
  closePhotoFrameDetail()
  emit('navigate', 'travel')
}
async function openPhotoFrameSettings(palette) {
  photoFrameDetail.open = false
  photoFrameDetail.photo = null
  const options = normalizePhotoFrameOptions(palette?.options)
  photoFrameSettings.open = true
  photoFrameSettings.paletteId = String(palette?.id || '')
  photoFrameSettings.source = options.source
  photoFrameSettings.mode = options.mode
  photoFrameSettings.fixedPhotoId = options.fixedPhotoId
  photoFrameSettings.driveFolderId = options.driveFolderId
  photoFrameSettings.travelPlanId = options.travelPlanId
  photoFrameSettings.sort = options.sort
  photoFrameSettings.randomInterval = options.randomInterval
  photoFrameSettings.error = ''
  if (!driveFolderItems.value.length) {
    try {
      driveFolderItems.value = await fetchDriveFolderDestinations()
    } catch (error) {
      photoFrameSettings.error = error.message || '드라이브 폴더를 불러오지 못했습니다.'
    }
  }
  if (photoFrameSettings.source === 'drive-folder' && photoFrameSettings.driveFolderId) {
    await loadPhotoFrameFolderPhotos(photoFrameSettings.driveFolderId, photoFrameSettings.sort)
  }
}

function closePhotoFrameSettings() {
  photoFrameSettings.open = false
  photoFrameSettings.paletteId = ''
  photoFrameSettings.error = ''
}

async function handlePhotoFrameFolderChange() {
  photoFrameSettings.fixedPhotoId = ''
  if (photoFrameSettings.driveFolderId) {
    await loadPhotoFrameFolderPhotos(photoFrameSettings.driveFolderId, photoFrameSettings.sort)
  }
}

function handlePhotoFrameSourceChange() {
  photoFrameSettings.fixedPhotoId = ''
  if (photoFrameSettings.source !== 'drive-folder') {
    photoFrameSettings.driveFolderId = ''
  }
  if (photoFrameSettings.source !== 'travel-plan') {
    photoFrameSettings.travelPlanId = ''
  }
}

function savePhotoFrameSettings() {
  const target = palettes.value.find((palette) => String(palette.id) === String(photoFrameSettings.paletteId))
  if (!target) return
  const nextOptions = {
    ...(target.options || {}),
    source: photoFrameSettings.source,
    mode: photoFrameSettings.mode,
    fixedPhotoId: photoFrameSettings.fixedPhotoId,
    driveFolderId: photoFrameSettings.source === 'drive-folder' ? photoFrameSettings.driveFolderId : '',
    travelPlanId: photoFrameSettings.source === 'travel-plan' ? photoFrameSettings.travelPlanId : '',
    sort: photoFrameSettings.sort,
    randomInterval: photoFrameSettings.randomInterval,
  }
  palettes.value = normalizeMainPalettes(palettes.value.map((palette) => (
    String(palette.id) === String(target.id) ? { ...palette, options: nextOptions } : palette
  )))
  persistPalettes()
  closePhotoFrameSettings()
}
function createPaletteId(templateId) {
  return `${templateId}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
}

function defaultSizeFor(palette) {
  return defaultSizeByType[palette?.type] ?? '3x2'
}

function parseSizeSpan(size, fallback = '3x2') {
  const match = /^(\d+)x(\d+)$/.exec(String(size || '').trim())
  if (match) {
    return {
      w: Number(match[1]),
      h: Number(match[2]),
    }
  }

  const fallbackMatch = /^(\d+)x(\d+)$/.exec(String(fallback || '3x2').trim())
  return {
    w: Number(fallbackMatch?.[1] ?? 3),
    h: Number(fallbackMatch?.[2] ?? 2),
  }
}

function spanToSize(span) {
  return `${span.w}x${span.h}`
}

function spanBoundsForType(type) {
  const min = minSpanByType[type] ?? fallbackMinSpan
  const max = maxSpanByType[type] ?? fallbackMaxSpan
  return {
    min,
    max: {
      w: Math.min(DASHBOARD_GRID_COLUMNS, Math.max(min.w, max.w)),
      h: Math.max(min.h, max.h),
    },
  }
}

function clampSpanForType(span, type) {
  const bounds = spanBoundsForType(type)
  const rawW = Number(span?.w)
  const rawH = Number(span?.h)
  return {
    w: Math.min(Math.max(Number.isFinite(rawW) ? Math.round(rawW) : bounds.min.w, bounds.min.w), bounds.max.w),
    h: Math.min(Math.max(Number.isFinite(rawH) ? Math.round(rawH) : bounds.min.h, bounds.min.h), bounds.max.h),
  }
}

function mainSpanForPalette(palette) {
  return clampSpanForType(parseSizeSpan(palette?.size, defaultSizeFor(palette)), palette?.type)
}

function clampMainPosition(position, size, type) {
  const span = clampSpanForType(parseSizeSpan(size, defaultSizeByType[type] ?? '3x2'), type)
  const rawX = Number(position?.x ?? 0)
  const rawY = Number(position?.y ?? 0)
  return {
    x: Math.min(Math.max(Number.isFinite(rawX) ? Math.floor(rawX) : 0, 0), Math.max(DASHBOARD_GRID_COLUMNS - span.w, 0)),
    y: Math.max(Number.isFinite(rawY) ? Math.floor(rawY) : 0, 0),
  }
}

function cloneMainPaletteConfig(palette) {
  const type = String(palette?.type || 'household-summary')
  const span = clampSpanForType(parseSizeSpan(palette?.size, defaultSizeByType[type] ?? '3x2'), type)
  const size = spanToSize(span)
  return {
    id: String(palette?.id),
    type,
    size,
    position: clampMainPosition(palette?.position, size, type),
    visible: palette?.visible !== false,
    options: { ...(palette?.options ?? {}) },
  }
}

function mainPalettesOverlap(left, right) {
  const leftSpan = mainSpanForPalette(left)
  const rightSpan = mainSpanForPalette(right)
  return !(
    left.position.x + leftSpan.w <= right.position.x
    || right.position.x + rightSpan.w <= left.position.x
    || left.position.y + leftSpan.h <= right.position.y
    || right.position.y + rightSpan.h <= left.position.y
  )
}

function isMainAreaFree(palettesToCheck, position, size, type, excludeId = '') {
  const candidate = {
    id: excludeId || '__candidate__',
    type,
    size,
    position: clampMainPosition(position, size, type),
  }
  return palettesToCheck
    .filter((palette) => palette.visible !== false && String(palette.id) !== String(excludeId))
    .every((palette) => !mainPalettesOverlap(candidate, palette))
}

function findFirstAvailableMainPosition(palettesToCheck, size, type) {
  const span = clampSpanForType(parseSizeSpan(size, defaultSizeByType[type] ?? '3x2'), type)
  const normalizedSize = spanToSize(span)
  for (let y = 0; y < 200; y += 1) {
    for (let x = 0; x <= Math.max(DASHBOARD_GRID_COLUMNS - span.w, 0); x += 1) {
      const position = { x, y }
      if (isMainAreaFree(palettesToCheck, position, normalizedSize, type)) {
        return position
      }
    }
  }
  return { x: 0, y: 0 }
}

function normalizeMainPalettes(value) {
  const visible = []
  return (value ?? []).map((palette) => {
    const next = cloneMainPaletteConfig(palette)
    if (next.visible !== false) {
      next.position = clampMainPosition(next.position, next.size, next.type)
      if (!isMainAreaFree(visible, next.position, next.size, next.type, next.id)) {
        next.position = findFirstAvailableMainPosition(visible, next.size, next.type)
      }
      visible.push(next)
    }
    return next
  })
}

function applyMainLayoutPatches(paletteList, patches) {
  const patchMap = new Map((patches ?? []).map((patch) => [String(patch.id), patch]))
  return normalizeMainPalettes((paletteList ?? []).map((palette) => {
    const patch = patchMap.get(String(palette.id))
    if (!patch) return palette
    const span = clampSpanForType(parseSizeSpan(patch.size ?? palette.size, defaultSizeFor(palette)), palette.type)
    const size = spanToSize(span)
    return {
      ...palette,
      size,
      position: clampMainPosition(patch.position ?? palette.position, size, palette.type),
    }
  }))
}

function applyPalettePayload(payload) {
  if (!payload || !Array.isArray(payload.palettes)) {
    return false
  }

  palettes.value = normalizeMainPalettes(payload.palettes)
  return true
}

function palettePayload() {
  return { palettes: clone(palettes.value) }
}

function hydratePalettes() {
  paletteRemoteHydrationSequence += 1
  paletteChangedDuringRemoteHydration = false
  if (typeof window === 'undefined') {
    palettes.value = normalizeMainPalettes(clone(defaultPalettes))
    return
  }

  try {
    const raw = window.localStorage.getItem(storageKey.value)
    if (!raw) {
      palettes.value = normalizeMainPalettes(clone(defaultPalettes))
      hydrateRemotePalettes(paletteRemoteHydrationSequence, null)
      return
    }
    const parsed = JSON.parse(raw)
    palettes.value = normalizeMainPalettes(Array.isArray(parsed?.palettes) ? parsed.palettes : defaultPalettes)
    hydrateRemotePalettes(paletteRemoteHydrationSequence, palettePayload())
  } catch {
    palettes.value = normalizeMainPalettes(clone(defaultPalettes))
    hydrateRemotePalettes(paletteRemoteHydrationSequence, null)
  }
}

function persistPalettesLocal() {
  if (typeof window === 'undefined') return
  window.localStorage.setItem(storageKey.value, JSON.stringify({ palettes: palettes.value }))
}

function schedulePaletteRemotePersist(payload = palettePayload()) {
  if (typeof window === 'undefined') return
  if (paletteRemoteSaveTimer) {
    window.clearTimeout(paletteRemoteSaveTimer)
  }

  pendingPaletteRemotePayload = clone(payload)
  paletteRemoteSaveTimer = window.setTimeout(() => {
    savePaletteRemoteNow()
  }, REMOTE_LAYOUT_SAVE_DELAY_MS)
}

function savePaletteRemoteNow(payload = pendingPaletteRemotePayload) {
  if (typeof window === 'undefined' || !payload) {
    return Promise.resolve()
  }

  if (paletteRemoteSaveTimer) {
    window.clearTimeout(paletteRemoteSaveTimer)
    paletteRemoteSaveTimer = 0
  }

  const nextPayload = clone(payload)
  pendingPaletteRemotePayload = null
  return saveLayoutSetting(MAIN_DASHBOARD_LAYOUT_SCOPE, nextPayload, MAIN_DASHBOARD_LAYOUT_VERSION).catch(() => {
    // Local cache keeps the user's layout if the backend is temporarily unavailable.
  })
}

function persistPalettes() {
  paletteChangedDuringRemoteHydration = true
  const payload = palettePayload()
  persistPalettesLocal()
  schedulePaletteRemotePersist(payload)
}

async function hydrateRemotePalettes(sequence, fallbackPayload) {
  try {
    const response = await fetchLayoutSetting(MAIN_DASHBOARD_LAYOUT_SCOPE)
    if (sequence !== paletteRemoteHydrationSequence || paletteChangedDuringRemoteHydration) {
      return
    }

    if (applyPalettePayload(response?.payload)) {
      persistPalettesLocal()
      return
    }

    if (fallbackPayload) {
      await saveLayoutSetting(MAIN_DASHBOARD_LAYOUT_SCOPE, fallbackPayload, MAIN_DASHBOARD_LAYOUT_VERSION)
    }
  } catch {
    // Remote layout sync is progressive; the cached layout is still usable.
  }
}

function hydratePaymentSelection() {
  if (typeof window === 'undefined') return
  selectedPaymentMethodId.value = window.localStorage.getItem(paymentSelectionStorageKey.value) || ''
}

function persistPaymentSelection() {
  if (typeof window === 'undefined') return
  window.localStorage.setItem(paymentSelectionStorageKey.value, selectedPaymentMethodId.value)
}

function hydrateSummaryCache() {
  if (typeof window === 'undefined') return
  try {
    const raw = window.sessionStorage.getItem(summaryCacheStorageKey.value)
    if (!raw) return
    const cache = JSON.parse(raw)
    householdDashboard.value = cache.householdDashboard ?? null
    travelPortfolio.value = cache.travelPortfolio ?? null
    driveSummary.value = cache.driveSummary ?? null
    driveRecentFileItems.value = cache.driveRecentFileItems ?? []
    drivePhotoFileItems.value = cache.drivePhotoFileItems ?? []
    driveFolderItems.value = cache.driveFolderItems ?? []
    weekCompareRows.value = cache.weekCompareRows ?? []
    monthCompareRows.value = cache.monthCompareRows ?? []
    categories.value = cache.categories ?? []
    paymentMethods.value = cache.paymentMethods ?? []
    syncQuickEntryDefaults()
  } catch {
    // Cache is an optional paint-speed hint; failed reads fall back to fresh API data.
  }
}

function persistSummaryCache() {
  if (typeof window === 'undefined') return
  try {
    window.sessionStorage.setItem(summaryCacheStorageKey.value, JSON.stringify({
      savedAt: Date.now(),
      householdDashboard: householdDashboard.value,
      travelPortfolio: travelPortfolio.value,
      driveSummary: driveSummary.value,
      driveRecentFileItems: driveRecentFileItems.value,
      drivePhotoFileItems: drivePhotoFileItems.value,
      driveFolderItems: driveFolderItems.value,
      weekCompareRows: weekCompareRows.value,
      monthCompareRows: monthCompareRows.value,
      categories: categories.value,
      paymentMethods: paymentMethods.value,
    }))
  } catch {
    // Fresh API data still renders when session storage is unavailable.
  }
}

function paletteTitle(palette) {
  const template = paletteTemplates.find((item) =>
    item.type === palette.type
    && JSON.stringify(item.options ?? {}) === JSON.stringify(palette.options ?? {}),
  )
  if (template) return template.label
  if (palette.type === 'household-metric') return metricDefinitions[palette.options?.metric]?.label || '가계부 지표'
  if (palette.type === 'household-compare') return palette.options?.period === 'month' ? '이번 달/저번 달 비교' : '이번 주/저번 주 비교'
  return paletteTemplates.find((item) => item.type === palette.type)?.label || '팔레트'
}

function gridAttrs(palette) {
  const span = mainSpanForPalette(palette)
  return {
    x: palette.position?.x ?? 0,
    y: palette.position?.y ?? 0,
    w: span.w,
    h: span.h,
  }
}

function resizeAttrs(palette) {
  const bounds = spanBoundsForType(palette?.type)
  return {
    minW: bounds.min.w,
    minH: bounds.min.h,
    maxW: bounds.max.w,
    maxH: bounds.max.h,
  }
}

function paletteSizeClasses(palette) {
  const span = mainSpanForPalette(palette)
  return [
    `main-palette--w-${span.w}`,
    `main-palette--h-${span.h}`,
    {
      'main-palette--compact': span.w <= 2 || span.h <= 2,
      'main-palette--wide': span.w >= 4,
      'main-palette--tall': span.h >= 4,
      'main-palette--roomy': span.w >= 4 && span.h >= 3,
    },
  ]
}

function paletteListLimit(palette) {
  const span = mainSpanForPalette(palette)
  return Math.max(1, Math.min(6, span.h + (span.w >= 4 ? 1 : 0) - 1))
}

function recentTravelPlansFor(palette) {
  return recentTravelPlans.value.slice(0, paletteListLimit(palette))
}

function recentDriveFilesFor(palette) {
  return recentDriveFiles.value.slice(0, Math.min(5, paletteListLimit(palette) + 1))
}

function compareRowsVisibleFor(palette) {
  return compareRowsFor(palette).slice(-Math.min(4, paletteListLimit(palette)))
}


function quickStat(key) {
  return (householdDashboard.value?.quickStats ?? []).find((item) => item.key === key)?.overview ?? {}
}

function householdMetric(palette) {
  const definition = metricDefinitions[palette.options?.metric] ?? metricDefinitions.monthExpense
  const overview = quickStat(definition.statKey)
  const rawValue = Number(overview[definition.field] ?? 0)
  const tone = definition.tone === 'balance'
    ? (rawValue >= 0 ? 'positive' : 'negative')
    : definition.tone
  return {
    label: definition.label,
    value: formatCurrency(rawValue),
    meta: `${formatNumber(overview.entryCount ?? 0)}건`,
    tone,
  }
}

function monthOverviewRows() {
  const overview = quickStat('month')
  const balance = Number(overview.balance ?? 0)
  return [
    { label: '수입', value: formatCurrency(overview.income), tone: 'positive' },
    { label: '지출', value: formatCurrency(overview.expense), tone: 'negative' },
    { label: '순액', value: formatCurrency(balance), tone: balance >= 0 ? 'positive' : 'negative' },
    { label: '거래', value: `${formatNumber(overview.entryCount)}건`, tone: 'neutral' },
  ]
}

function compareRowsFor(palette) {
  return palette.options?.period === 'month' ? monthCompareRows.value : weekCompareRows.value
}

function compareMax(rows) {
  return Math.max(
    ...rows.map((row) => Number(row.expense ?? 0)),
    ...rows.map((row) => Number(row.income ?? 0)),
    1,
  )
}

function updateCellHeight() {
  const width = gridElement.value?.clientWidth || gridElement.value?.parentElement?.clientWidth || 0
  if (!width || !grid) return
  const rawCellWidth = (
    width
    - (MAIN_DASHBOARD_GRID_MARGIN * 2)
    - ((DASHBOARD_GRID_COLUMNS - 1) * MAIN_DASHBOARD_GRID_GAP)
  ) / DASHBOARD_GRID_COLUMNS
  const nextHeight = Math.round(Math.max(112, Math.min(168, rawCellWidth * 0.96)))
  cellHeight.value = nextHeight
  grid.cellHeight(nextHeight)
}

function readGridSnapshot() {
  if (!grid) return []
  return (grid.engine?.nodes ?? []).map((node) => ({
    id: node.el?.getAttribute('gs-id') || node.el?.getAttribute('data-palette-id'),
    position: { x: node.x, y: node.y },
    size: `${node.w}x${node.h}`,
  })).filter((patch) => patch.id)
}

function handleDragStart(event, element) {
  dragStartLayout = new Map(visiblePalettes.value.map((palette) => [String(palette.id), {
    id: String(palette.id),
    size: palette.size,
    position: { ...(palette.position ?? { x: 0, y: 0 }) },
  }]))
  element?.classList.add('is-main-palette-dragging')
}

function handleDragStop(event, element) {
  element?.classList.remove('is-main-palette-dragging')
  const movedId = element?.getAttribute('gs-id') || element?.getAttribute('data-palette-id')
  const node = element?.gridstackNode
  const before = dragStartLayout.get(String(movedId))

  if (movedId && node && before) {
    const swapTarget = [...dragStartLayout.values()].find((item) =>
      item.id !== String(movedId)
      && item.size === before.size
      && item.position.x === node.x
      && item.position.y === node.y,
    )

    if (swapTarget) {
      applyLayoutPatches([
        { id: movedId, position: swapTarget.position, size: before.size },
        { id: swapTarget.id, position: before.position, size: swapTarget.size },
      ])
      return
    }
  }

  applyLayoutPatches(readGridSnapshot())
}

function handleResizeStart(event, element) {
  element?.classList.add('is-main-palette-resizing')
}

function handleResizeStop(event, element) {
  element?.classList.remove('is-main-palette-resizing')
  applyLayoutPatches(readGridSnapshot())
}

function destroyGrid() {
  if (!grid) return
  grid.off('dragstart')
  grid.off('dragstop')
  grid.off('resizestart')
  grid.off('resizestop')
  grid.destroy(false)
  grid = null
}

function initGrid() {
  if (!gridElement.value) return
  destroyGrid()
  grid = GridStack.init({
    column: DASHBOARD_GRID_COLUMNS,
    margin: MAIN_DASHBOARD_GRID_MARGIN,
    cellHeight: cellHeight.value,
    disableResize: !isEditMode.value,
    float: false,
    animate: true,
    draggable: {
      appendTo: 'body',
      cancel: 'button,a,input,select,textarea,[data-no-drag="true"]',
      handle: '.main-palette',
      scroll: false,
    },
    resizable: {
      autoHide: false,
      handles: 'se',
    },
  }, gridElement.value)
  grid.enableMove(isEditMode.value)
  grid.enableResize(isEditMode.value)
  grid.on('dragstart', handleDragStart)
  grid.on('dragstop', handleDragStop)
  grid.on('resizestart', handleResizeStart)
  grid.on('resizestop', handleResizeStop)
  updateCellHeight()
}

function queueGridRebuild() {
  if (rebuildTimer) {
    window.clearTimeout(rebuildTimer)
  }
  rebuildTimer = window.setTimeout(async () => {
    await nextTick()
    initGrid()
    rebuildTimer = 0
  }, 0)
}

function applyLayoutPatches(patches) {
  palettes.value = applyMainLayoutPatches(palettes.value, patches)
  persistPalettes()
}

function hidePalette(id) {
  palettes.value = palettes.value.map((palette) => (
    String(palette.id) === String(id) ? { ...palette, visible: false } : palette
  ))
  persistPalettes()
}

function restorePalette(id) {
  const visible = visiblePalettes.value
  palettes.value = normalizeMainPalettes(palettes.value.map((palette) => {
    if (String(palette.id) !== String(id)) return palette
    return {
      ...palette,
      visible: true,
      position: findFirstAvailableMainPosition(visible, palette.size, palette.type),
    }
  }))
  persistPalettes()
}

function removePalette(id) {
  palettes.value = palettes.value.filter((palette) => String(palette.id) !== String(id))
  persistPalettes()
}

function addPalette() {
  const template = paletteTemplates.find((item) => item.id === selectedTemplateId.value) ?? paletteTemplates[0]
  const size = defaultSizeFor(template)
  const nextPalette = {
    id: createPaletteId(template.id),
    type: template.type,
    size,
    position: findFirstAvailableMainPosition(visiblePalettes.value, size, template.type),
    visible: true,
    options: { ...(template.options ?? {}) },
  }
  palettes.value = normalizeMainPalettes([...palettes.value, nextPalette])
  persistPalettes()
}

function resetPalettes() {
  if (!window.confirm('메인 대시보드 팔레트 배치를 초기화할까요?')) return
  palettes.value = normalizeMainPalettes(clone(defaultPalettes))
  persistPalettes()
}

function toggleEditMode() {
  const isFinishingEdit = isEditMode.value
  isEditMode.value = !isEditMode.value
  if (isFinishingEdit) {
    savePaletteRemoteNow()
  }
  if (grid) {
    grid.enableMove(isEditMode.value)
    grid.enableResize(isEditMode.value)
  }
}

function closeToolsOnOutsidePointer(event) {
  if (!toolsOpen.value) return
  if (toolsPanelRef.value?.contains(event.target) || toolsButtonRef.value?.contains(event.target)) return
  toolsOpen.value = false
}

function syncQuickEntryDefaults() {
  if (!quickGroups.value.some((group) => String(group.id) === String(quickEntry.categoryGroupId))) {
    quickEntry.categoryGroupId = quickGroups.value[0] ? String(quickGroups.value[0].id) : ''
  }
  if (!quickDetails.value.some((detail) => String(detail.id) === String(quickEntry.categoryDetailId))) {
    quickEntry.categoryDetailId = quickDetails.value[0] ? String(quickDetails.value[0].id) : ''
  }
  if (!paymentMethods.value.some((method) => String(method.id) === String(quickEntry.paymentMethodId))) {
    quickEntry.paymentMethodId = paymentMethods.value[0] ? String(paymentMethods.value[0].id) : ''
  }
}

function fillQuickAmount(value) {
  quickEntry.amount = String(Number(value || 0))
}

function addQuickAmount(value) {
  quickEntry.amount = String(Number(quickEntry.amount || 0) + Number(value || 0))
}

async function submitQuickEntry() {
  const amount = Number(quickEntry.amount || 0)
  if (!amount || amount <= 0) {
    feedbackMessage.value = ''
    errorMessage.value = '금액을 입력해주세요.'
    return
  }
  syncQuickEntryDefaults()
  if (!quickEntry.categoryGroupId || !quickEntry.paymentMethodId) {
    feedbackMessage.value = ''
    errorMessage.value = '카테고리와 결제수단 정보가 필요합니다.'
    return
  }

  quickSubmitting.value = true
  feedbackMessage.value = ''
  errorMessage.value = ''
  try {
    await createEntry({
      entryDate: quickEntry.entryDate || todayIso(),
      entryTime: '00:00',
      title: quickEntry.title.trim() || '빠른 입력',
      memo: null,
      amount,
      entryType: quickEntry.entryType,
      categoryGroupId: Number(quickEntry.categoryGroupId),
      categoryDetailId: quickEntry.categoryDetailId ? Number(quickEntry.categoryDetailId) : null,
      paymentMethodId: Number(quickEntry.paymentMethodId),
    })
    quickEntry.title = ''
    quickEntry.amount = ''
    feedbackMessage.value = '빠른 금액 입력을 저장했습니다.'
    await loadSummaries()
  } catch (error) {
    errorMessage.value = error.message
  } finally {
    quickSubmitting.value = false
  }
}

async function settleSummaryRequest(loadId, requestPromise, applyValue) {
  try {
    const value = await requestPromise
    if (loadId === summaryLoadSequence) {
      applyValue(value)
      persistSummaryCache()
    }
    return { status: 'fulfilled', value }
  } catch (error) {
    return { status: 'rejected', reason: error }
  }
}

function visiblePhotoFrameSources() {
  return new Set(visiblePalettes.value
    .filter((palette) => palette.type === 'photo-frame')
    .map((palette) => normalizePhotoFrameOptions(palette.options).source))
}

async function loadPhotoFrameSources(loadId) {
  const sources = visiblePhotoFrameSources()
  if (!sources.size) {
    return
  }

  const requests = []
  if (sources.has('all') || sources.has('drive')) {
    requests.push(settleSummaryRequest(
      loadId,
      fetchDrivePhotos({ sortOption: 'recent', size: PHOTO_FRAME_FETCH_SIZE }),
      (value) => {
        drivePhotoFileItems.value = value ?? []
      },
    ))
  }
  if (sources.has('all') || sources.has('travel') || sources.has('travel-plan')) {
    requests.push(settleSummaryRequest(loadId, fetchTravelPhotoFrameMedia(), (value) => {
      travelPortfolio.value = {
        ...(travelPortfolio.value ?? {}),
        mediaItems: value ?? [],
      }
    }))
  }

  await Promise.all(requests)
  if (loadId === summaryLoadSequence && sources.has('drive-folder')) {
    await refreshConfiguredPhotoFrameFolders()
  }
}

async function loadSummaries() {
  const loadId = summaryLoadSequence + 1
  summaryLoadSequence = loadId
  loading.value = true
  errorMessage.value = ''
  const anchorDate = todayIso()
  const results = await Promise.all([
    settleSummaryRequest(loadId, fetchDashboard(anchorDate), (value) => {
      householdDashboard.value = value
    }),
    settleSummaryRequest(loadId, fetchTravelPlans(), (value) => {
      travelPortfolio.value = {
        ...(travelPortfolio.value ?? {}),
        plans: value ?? [],
      }
    }),

    settleSummaryRequest(loadId, fetchDriveHomeSummary(), (value) => {
      driveSummary.value = value
    }),
    settleSummaryRequest(loadId, fetchDriveRecentFiles(), (value) => {
      driveRecentFileItems.value = value ?? []
    }),

    settleSummaryRequest(loadId, fetchCompare(anchorDate, 'WEEK', 2), (value) => {
      weekCompareRows.value = value ?? []
    }),
    settleSummaryRequest(loadId, fetchCompare(anchorDate, 'MONTH', 2), (value) => {
      monthCompareRows.value = value ?? []
    }),
    settleSummaryRequest(loadId, fetchCategories(), (value) => {
      categories.value = value ?? []
      syncQuickEntryDefaults()
    }),
    settleSummaryRequest(loadId, fetchPaymentMethods(), (value) => {
      paymentMethods.value = value ?? []
      syncQuickEntryDefaults()
    }),
  ])

  if (loadId === summaryLoadSequence) {
    syncQuickEntryDefaults()

    const failed = results.filter((result) => result.status === 'rejected')
    if (failed.length) {
      errorMessage.value = '일부 요약 정보를 불러오지 못했습니다. 백엔드 연결 후 자동으로 채워집니다.'
    }
    loading.value = false
    loadPhotoFrameSources(loadId)
  }
  return results
}

watch(
  userStorageId,
  () => {
    hydratePalettes()
    hydratePaymentSelection()
    hydrateSummaryCache()
  },
  { immediate: true },
)

watch(selectedPaymentMethodId, persistPaymentSelection)
watch(() => quickEntry.entryType, syncQuickEntryDefaults)
watch(() => quickEntry.categoryGroupId, syncQuickEntryDefaults)
watch(layoutKey, queueGridRebuild)
watch(
  () => visiblePalettes.value
    .filter((palette) => palette.type === 'photo-frame')
    .map((palette) => [palette.id, palette.options?.mode, palette.options?.randomInterval].join(':'))
    .join('|'),
  schedulePhotoFrameClock,
)
watch(isEditMode, (value) => {
  if (grid) {
    grid.enableMove(value)
    grid.enableResize(value)
  }
})

onMounted(async () => {
  document.addEventListener('pointerdown', closeToolsOnOutsidePointer)
  await nextTick()
  initGrid()
  resizeObserver = new ResizeObserver(updateCellHeight)
  if (gridElement.value?.parentElement) {
    resizeObserver.observe(gridElement.value.parentElement)
  }
  loadSummaries()
  document.addEventListener('visibilitychange', handlePhotoFrameVisibilityChange)
  schedulePhotoFrameClock()
})

onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', closeToolsOnOutsidePointer)
  summaryLoadSequence += 1
  if (rebuildTimer) {
    window.clearTimeout(rebuildTimer)
  }
  document.removeEventListener('visibilitychange', handlePhotoFrameVisibilityChange)
  stopPhotoFrameClock()
  savePaletteRemoteNow()
  resizeObserver?.disconnect()
  destroyGrid()
})
</script>

<template>
  <div class="main-dashboard">
    <section class="main-dashboard__overview" aria-label="메인 종합 현황">
      <div class="main-dashboard__overview-cards">
        <article
          v-for="card in dashboardOverviewCards"
          :key="card.key"
          class="main-dashboard__overview-card"
          :class="`is-${card.tone}`"
        >
          <span>{{ card.eyebrow }}</span>
          <strong>{{ card.value }}</strong>
          <small>{{ card.label }}</small>
          <p>{{ card.meta }}</p>
        </article>
      </div>
    </section>

    <div v-if="feedbackMessage" class="feedback feedback--success">{{ feedbackMessage }}</div>
    <div v-if="errorMessage" class="feedback feedback--error">{{ errorMessage }}</div>

    <section
      class="main-dashboard__palette-zone"
      :class="{ 'is-editing': isEditMode }"
      :style="mainDashboardGridStyle"
    >
      <div v-if="isEditMode" class="main-dashboard__grid-guide" aria-hidden="true">
        <span v-for="index in guideCellCount" :key="index"></span>
      </div>

      <div ref="gridElement" class="grid-stack main-dashboard__grid">
        <div
          v-for="palette in visiblePalettes"
          :key="palette.id"
          class="grid-stack-item"
          :gs-id="palette.id"
          :data-palette-id="palette.id"
          :gs-x="gridAttrs(palette).x"
          :gs-y="gridAttrs(palette).y"
          :gs-w="gridAttrs(palette).w"
          :gs-h="gridAttrs(palette).h"
          :gs-min-w="resizeAttrs(palette).minW"
          :gs-min-h="resizeAttrs(palette).minH"
          :gs-max-w="resizeAttrs(palette).maxW"
          :gs-max-h="resizeAttrs(palette).maxH"
        >
          <div class="grid-stack-item-content">
            <article
              class="main-palette"
              :class="[
                { 'main-palette--editing': isEditMode },
                `main-palette--${palette.type}`,
                palette.options?.metric ? `main-palette--${palette.options.metric}` : '',
                paletteSizeClasses(palette),
              ]"
            >
                            <header class="main-palette__head">
                <strong>{{ paletteTitle(palette) }}</strong>
                <div class="main-palette__head-actions" data-no-drag="true">
                  <button
                    v-if="palette.type === 'photo-frame'"
                    class="main-palette__settings-button"
                    type="button"
                    @click="openPhotoFrameSettings(palette)"
                  >설정</button>
                  <div v-if="isEditMode" class="main-palette__actions">
                    <button type="button" @click="hidePalette(palette.id)">숨김</button>
                    <button type="button" @click="removePalette(palette.id)">삭제</button>
                  </div>
                </div>
              </header>

              <div
                class="main-palette__body"
                :class="{
                  'main-palette__body--has-list': (
                    (palette.type === 'travel-summary' && recentTravelPlansFor(palette).length)
                    || (palette.type === 'drive-summary' && recentDriveFilesFor(palette).length)
                  ),
                }"
              >
                <template v-if="palette.type === 'household-summary'">
                  <div class="main-palette__metric-grid">
                    <div v-for="row in monthOverviewRows()" :key="row.label" class="main-palette__metric" :class="`is-${row.tone}`">
                      <span>{{ row.label }}</span>
                      <strong>{{ row.value }}</strong>
                    </div>
                  </div>
                </template>

                <template v-else-if="palette.type === 'household-metric'">
                  <div class="main-palette__single-metric" :class="`is-${householdMetric(palette).tone}`">
                    <span>{{ householdMetric(palette).label }}</span>
                    <strong>{{ householdMetric(palette).value }}</strong>
                    <small>{{ householdMetric(palette).meta }}</small>
                  </div>
                </template>

                <template v-else-if="palette.type === 'household-payment'">
                  <div class="main-palette__payment" data-no-drag="true">
                    <select v-model="selectedPaymentMethodId" aria-label="결제수단 선택">
                      <option value="">가장 많이 사용한 결제수단</option>
                      <option v-for="method in paymentOptions" :key="method.id" :value="String(method.id)">
                        {{ method.name }}{{ method.kind ? ` (${method.kind})` : '' }}
                      </option>
                    </select>
                    <div class="main-palette__single-metric is-negative">
                      <span>{{ selectedPaymentSummary?.paymentMethodName || '결제수단' }}</span>
                      <strong>{{ formatCurrency(selectedPaymentSummary?.totalAmount) }}</strong>
                      <small>{{ formatNumber(selectedPaymentSummary?.entryCount) }}건</small>
                    </div>
                  </div>
                </template>

                <template v-else-if="palette.type === 'household-compare'">
                  <div class="main-palette__compare">
                    <div
                      v-for="row in compareRowsVisibleFor(palette)"
                      :key="row.label"
                      class="main-palette__compare-row"
                    >
                      <div class="main-palette__compare-label">
                        <span>{{ row.label }}</span>
                        <strong>{{ formatCurrency(row.expense) }}</strong>
                      </div>
                      <div class="main-palette__bar">
                        <span class="is-negative" :style="{ width: `${Math.max(4, Math.round((Number(row.expense || 0) / compareMax(compareRowsFor(palette))) * 100))}%` }"></span>
                      </div>
                      <small>수입 {{ formatCurrency(row.income) }}</small>
                    </div>
                    <p v-if="!compareRowsFor(palette).length" class="main-palette__empty">비교 데이터가 없습니다.</p>
                  </div>
                </template>

                <template v-else-if="palette.type === 'quick-entry'">
                  <form class="main-palette__quick-form" data-no-drag="true" @submit.prevent="submitQuickEntry">
                    <div class="main-palette__quick-row">
                      <select v-model="quickEntry.entryType" aria-label="거래 유형">
                        <option value="EXPENSE">지출</option>
                        <option value="INCOME">수입</option>
                      </select>
                      <input v-model="quickEntry.entryDate" type="date" aria-label="거래일" />
                    </div>
                    <input v-model="quickEntry.title" type="text" placeholder="내용" />
                    <input v-model="quickEntry.amount" type="number" min="0" step="100" placeholder="금액" />
                    <div class="main-palette__quick-buttons">
                      <button type="button" @click="fillQuickAmount(10000)">1만</button>
                      <button type="button" @click="addQuickAmount(10000)">+1만</button>
                      <button type="button" @click="addQuickAmount(50000)">+5만</button>
                    </div>
                    <div class="main-palette__quick-row">
                      <select v-model="quickEntry.categoryGroupId" aria-label="카테고리 그룹">
                        <option v-for="group in quickGroups" :key="group.id" :value="String(group.id)">
                          {{ group.name }}
                        </option>
                      </select>
                      <select v-model="quickEntry.categoryDetailId" aria-label="상세 카테고리">
                        <option value="">상세 없음</option>
                        <option v-for="detail in quickDetails" :key="detail.id" :value="String(detail.id)">
                          {{ detail.name }}
                        </option>
                      </select>
                    </div>
                    <select v-model="quickEntry.paymentMethodId" aria-label="결제수단">
                      <option v-for="method in paymentMethods" :key="method.id" :value="String(method.id)">
                        {{ method.name }}
                      </option>
                    </select>
                    <button class="button button--primary" type="submit" :disabled="quickSubmitting">
                      {{ quickSubmitting ? '저장 중' : '저장' }}
                    </button>
                  </form>
                </template>

                <template v-else-if="palette.type === 'travel-summary'">
                  <div class="main-palette__metric-grid">
                    <div class="main-palette__metric"><span>여행</span><strong>{{ formatNumber(travelSummary.planCount) }}</strong></div>
                    <div class="main-palette__metric"><span>기록</span><strong>{{ formatNumber(travelSummary.recordCount) }}</strong></div>
                    <div class="main-palette__metric"><span>미디어</span><strong>{{ formatNumber(travelSummary.mediaCount) }}</strong></div>
                    <div class="main-palette__metric is-negative"><span>사용</span><strong>{{ formatCurrency(travelSummary.actualTotal) }}</strong></div>
                  </div>
                  <div v-if="recentTravelPlansFor(palette).length" class="main-palette__list">
                    <div v-for="plan in recentTravelPlansFor(palette)" :key="plan.id" class="main-palette__list-row">
                      <span>{{ plan.name || plan.destination || '-' }}</span>
                      <strong>{{ plan.status || 'PLANNED' }}</strong>
                    </div>
                  </div>
                </template>

                <template v-else-if="palette.type === 'drive-summary'">
                  <div class="main-palette__metric-grid">
                    <div class="main-palette__metric"><span>전체 항목</span><strong>{{ formatNumber(driveSummary?.driveItemCount) }}</strong></div>
                    <div class="main-palette__metric"><span>파일</span><strong>{{ formatNumber(driveSummary?.fileCount) }}</strong></div>
                    <div class="main-palette__metric"><span>공유</span><strong>{{ formatNumber(driveSummary?.sharedCount) }}</strong></div>
                    <div class="main-palette__metric"><span>용량</span><strong>{{ formatBytes(driveSummary?.usedBytes) }}</strong></div>
                  </div>
                  <div v-if="recentDriveFilesFor(palette).length" class="main-palette__list">
                    <div v-for="file in recentDriveFilesFor(palette)" :key="file.id" class="main-palette__list-row">
                      <span>{{ file.fileOriginName || file.name || '-' }}</span>
                      <strong>{{ formatBytes(file.fileSize) }}</strong>
                    </div>
                  </div>
                </template>

                                <template v-else-if="palette.type === 'photo-frame'">
                  <div class="main-palette__photo-frame">
                    <button
                      v-if="photoFrameHeroFor(palette)"
                      class="main-palette__photo-hero"
                      type="button"
                      data-no-drag="true"
                      @click="openPhotoFrameDetail(photoFrameHeroFor(palette))"
                    >
                      <img :src="photoFrameDashboardImageUrl(photoFrameHeroFor(palette))" :alt="photoFrameHeroFor(palette).title" loading="lazy" decoding="async" />
                      <div class="main-palette__photo-caption">
                        <span>{{ photoFrameModeLabel(palette) }}</span>
                        <strong>{{ photoFrameHeroFor(palette).title }}</strong>
                        <small v-if="photoFrameInfoLine(photoFrameHeroFor(palette))">{{ photoFrameInfoLine(photoFrameHeroFor(palette)) }}</small>
                      </div>
                    </button>
                    <button v-else class="main-palette__photo-empty" type="button" data-no-drag="true" @click="openPhotoFrameSettings(palette)">
                      <strong>표시할 사진이 없습니다.</strong>
                      <span>드라이브 또는 여행 사진을 선택해 액자를 설정하세요.</span>
                    </button>
                  </div>
                </template>

                <template v-else-if="palette.type === 'drive-capacity'">
                  <div class="main-palette__capacity">
                    <div class="main-palette__single-metric">
                      <span>사용 중인 용량</span>
                      <strong>{{ formatBytes(driveCapacity.usedBytes) }}</strong>
                      <small v-if="driveCapacity.totalBytes">{{ formatBytes(driveCapacity.totalBytes) }} 중 {{ driveCapacity.percent }}%</small>
                    </div>
                    <div class="main-palette__capacity-track">
                      <span :style="{ width: `${driveCapacity.totalBytes ? driveCapacity.percent : 18}%` }"></span>
                    </div>
                    <button class="main-palette__inline-action" type="button" data-no-drag="true" @click="emit('navigate', 'drive')">
                      드라이브 열기
                    </button>
                  </div>
                </template>

                <template v-else-if="palette.type === 'drive-recent-files'">
                  <div class="main-palette__recent-files">
                    <a
                      v-for="file in recentDriveFilesFor(palette)"
                      :key="file.id"
                      class="main-palette__recent-file"
                      :href="buildDriveOpenPath(file)"
                      target="_blank"
                      rel="noreferrer"
                      data-no-drag="true"
                    >
                      <img v-if="isImageFile(file)" :src="buildDriveThumbnailPath(file)" :alt="fileName(file)" loading="lazy" decoding="async" />
                      <span v-else class="main-palette__file-icon">{{ fileExtension(file).toUpperCase() || 'FILE' }}</span>
                      <strong>{{ fileName(file) }}</strong>
                      <small>{{ formatBytes(file.fileSize) }}</small>
                    </a>
                    <p v-if="!recentDriveFilesFor(palette).length" class="main-palette__empty">최근 저장한 파일이 없습니다.</p>
                  </div>
                </template>

                <template v-else-if="palette.type === 'quick-actions'">
                  <div class="main-palette__quick-actions">
                    <button
                      v-for="action in quickActionItems"
                      :key="action.key"
                      type="button"
                      class="main-palette__quick-action"
                      data-no-drag="true"
                      @click="emit('navigate', action.route)"
                    >
                      <strong>{{ action.label }}</strong>
                      <span>{{ action.meta }}</span>
                    </button>
                  </div>
                </template>

                <template v-else-if="palette.type === 'feature-links'">
                  <div class="main-palette__feature-links">
                    <button
                      v-for="item in featureItems"
                      :key="item.key"
                      type="button"
                      class="main-palette__feature-link"
                      data-no-drag="true"
                      @click="emit('navigate', item.key)"
                    >
                      <span>{{ item.badge }}</span>
                      <strong>{{ item.title }}</strong>
                    </button>
                  </div>
                </template>
              </div>
            </article>
          </div>
        </div>
      </div>
    </section>

    <button
      ref="toolsButtonRef"
      class="main-dashboard__floating-button"
      type="button"
      :class="{ 'is-active': toolsOpen || isEditMode }"
      @click="toolsOpen = !toolsOpen"
    >
      설정
    </button>

    <aside v-if="toolsOpen" ref="toolsPanelRef" class="main-dashboard__tools" data-no-drag="true">
      <div class="main-dashboard__tools-head">
        <strong>메인 팔레트</strong>
        <button type="button" @click="toolsOpen = false">닫기</button>
      </div>

      <button v-if="!isEditMode" class="main-dashboard__primary" type="button" @click="toggleEditMode">
        편집 시작
      </button>

      <template v-else>
        <label class="main-dashboard__field">
          <span>팔레트 추가</span>
          <select v-model="selectedTemplateId">
            <option v-for="template in paletteTemplates" :key="template.id" :value="template.id">
              {{ template.label }}
            </option>
          </select>
        </label>
        <button class="main-dashboard__secondary" type="button" @click="addPalette">추가</button>

        <div class="main-dashboard__hidden">
          <span>숨긴 팔레트</span>
          <button
            v-for="palette in hiddenPalettes"
            :key="palette.id"
            type="button"
            @click="restorePalette(palette.id)"
          >
            {{ paletteTitle(palette) }}
          </button>
          <small v-if="!hiddenPalettes.length">숨긴 팔레트가 없습니다.</small>
        </div>

        <button class="main-dashboard__secondary" type="button" @click="resetPalettes">초기화</button>
        <button class="main-dashboard__primary" type="button" @click="toggleEditMode">편집 완료</button>
      </template>
    </aside>
    <div v-if="photoFrameDetail.open && photoFrameDetail.photo" class="main-photo-frame-modal main-photo-frame-detail-modal" data-no-drag="true" @click.self="closePhotoFrameDetail">
      <section class="main-photo-frame-modal__dialog main-photo-frame-detail-modal__dialog" role="dialog" aria-modal="true" aria-labelledby="main-photo-frame-detail-title">
        <header class="main-photo-frame-modal__header">
          <div>
            <span>사진 액자</span>
            <h2 id="main-photo-frame-detail-title">{{ photoFrameDetail.photo.title || '사진 상세' }}</h2>
          </div>
          <button type="button" @click="closePhotoFrameDetail">닫기</button>
        </header>

        <div class="main-photo-frame-detail-modal__body">
          <figure class="main-photo-frame-detail-modal__figure">
            <img :src="photoFrameDashboardImageUrl(photoFrameDetail.photo)" :alt="photoFrameDetail.photo.title || '사진'" decoding="async" />
            <figcaption>{{ photoFrameDetail.photo.title || '사진' }}</figcaption>
          </figure>

          <aside class="main-photo-frame-detail-modal__info">
            <dl class="main-photo-frame-detail-modal__metadata">
              <div>
                <dt>사진</dt>
                <dd>{{ photoFrameDetail.photo.title || '-' }}</dd>
              </div>
              <div>
                <dt>출처</dt>
                <dd>{{ photoFrameDetail.photo.source || '드라이브' }}</dd>
              </div>
              <div>
                <dt>여행</dt>
                <dd>{{ photoFrameDetail.photo.sourceType === 'travel' ? (photoFrameDetail.photo.source || '여행') : '여행 연결 없음' }}</dd>
              </div>
              <div>
                <dt>기록 시간</dt>
                <dd>{{ photoFrameDetailRecordedAt(photoFrameDetail.photo) || '기록 시간 없음' }}</dd>
              </div>
              <div>
                <dt>업로드 시간</dt>
                <dd>{{ photoFrameDetailUploadedAt(photoFrameDetail.photo) || '업로드 시간 없음' }}</dd>
              </div>
              <div>
                <dt>위치</dt>
                <dd>{{ photoFrameDetailLocation(photoFrameDetail.photo) }}</dd>
              </div>
            </dl>
            <TravelMiniLocationMap
              :latitude="photoFrameDetailLatitude(photoFrameDetail.photo)"
              :longitude="photoFrameDetailLongitude(photoFrameDetail.photo)"
              :title="photoFrameDetailLocation(photoFrameDetail.photo)"
            />
            <div class="main-photo-frame-detail-modal__actions">
              <button v-if="photoFrameDetail.photo.sourceType === 'travel'" type="button" @click="openPhotoFrameTravelMap">여행 지도 열기</button>
              <button type="button" @click="closePhotoFrameDetail">닫기</button>
            </div>
          </aside>
        </div>
      </section>
    </div>
    <div v-if="photoFrameSettings.open" class="main-photo-frame-modal" data-no-drag="true" @click.self="closePhotoFrameSettings">
      <section class="main-photo-frame-modal__dialog" role="dialog" aria-modal="true" aria-labelledby="main-photo-frame-title">
        <header class="main-photo-frame-modal__header">
          <div>
            <span>사진 액자</span>
            <h2 id="main-photo-frame-title">사진 액자 설정</h2>
          </div>
          <button type="button" @click="closePhotoFrameSettings">닫기</button>
        </header>

        <div class="main-photo-frame-modal__body">
          <div class="main-photo-frame-modal__form">
            <label>
              <span>사진 가져오기</span>
              <select v-model="photoFrameSettings.source" @change="handlePhotoFrameSourceChange">
                <option value="all">드라이브와 여행 전체</option>
                <option value="drive">드라이브 전체 사진</option>
                <option value="travel">여행 전체 사진</option>
                <option value="drive-folder">드라이브 특정 폴더</option>
                <option value="travel-plan">여행 특정 항목</option>
              </select>
            </label>

            <label v-if="photoFrameSettings.source === 'drive-folder'">
              <span>드라이브 폴더</span>
              <select v-model="photoFrameSettings.driveFolderId" @change="handlePhotoFrameFolderChange">
                <option value="">폴더 선택</option>
                <option v-for="folder in driveFolderItems" :key="folder.id" :value="String(folder.id)">
                  {{ folder.name || folder.folderName || folder.path || '폴더' }}
                </option>
              </select>
            </label>

            <label v-if="photoFrameSettings.source === 'travel-plan'">
              <span>여행 선택</span>
              <select v-model="photoFrameSettings.travelPlanId" @change="photoFrameSettings.fixedPhotoId = ''">
                <option value="">여행 선택</option>
                <option v-for="plan in travelPlans" :key="plan.id" :value="String(plan.id)">
                  {{ plan.name || plan.destination || '여행' }}
                </option>
              </select>
            </label>

            <label>
              <span>표시 방식</span>
              <select v-model="photoFrameSettings.mode">
                <option value="latest">가장 최근 사진</option>
                <option value="fixed">선택한 사진 고정</option>
                <option value="random">사진 목록에서 랜덤</option>
                <option value="name">이름순으로 표시</option>
              </select>
            </label>

            <label v-if="photoFrameSettings.mode === 'random'">
              <span>변경 주기</span>
              <select v-model="photoFrameSettings.randomInterval">
                <option v-for="option in PHOTO_FRAME_RANDOM_INTERVAL_OPTIONS" :key="option.value" :value="option.value">
                  {{ option.label }}
                </option>
              </select>
            </label>

            <label>
              <span>정렬 기준</span>
              <select v-model="photoFrameSettings.sort">
                <option value="recent">최근 추가순</option>
                <option value="name">이름순</option>
              </select>
            </label>

            <label v-if="photoFrameSettings.mode === 'fixed'">
              <span>고정할 사진</span>
              <select v-model="photoFrameSettings.fixedPhotoId">
                <option value="">사진 선택</option>
                <option v-for="photo in photoFrameSettingsPhotos" :key="photo.id" :value="String(photo.id)">
                  {{ photoFrameSourceLabel(photo) }}
                </option>
              </select>
            </label>

            <p v-if="photoFrameSettings.loading" class="main-photo-frame-modal__state">사진을 불러오는 중입니다.</p>
            <p v-if="photoFrameSettings.error" class="main-photo-frame-modal__error">{{ photoFrameSettings.error }}</p>
          </div>

          <div class="main-photo-frame-modal__preview">
            <div class="main-photo-frame-modal__preview-head">
              <strong>미리보기</strong>
              <span>{{ photoFrameSettingsPhotos.length }}장</span>
            </div>
            <div v-if="photoFrameSettingsPhotos.length" class="main-photo-frame-modal__photo-grid">
              <button
                v-for="photo in photoFrameSettingsPhotos.slice(0, 24)"
                :key="photo.id"
                type="button"
                :class="{ 'is-selected': String(photo.id) === String(photoFrameSettings.fixedPhotoId) }"
                @click="photoFrameSettings.mode = 'fixed'; photoFrameSettings.fixedPhotoId = String(photo.id)"
              >
                <img :src="photoFramePreviewImageUrl(photo)" :alt="photo.title" loading="lazy" decoding="async" />
                <span>{{ photo.title }}</span>
                <small v-if="photoFrameInfoLine(photo)">{{ photoFrameInfoLine(photo) }}</small>
              </button>
            </div>
            <div v-else class="main-photo-frame-modal__empty">
              <strong>표시할 사진이 없습니다.</strong>
            </div>
          </div>
        </div>

        <footer class="main-photo-frame-modal__footer">
          <button type="button" @click="closePhotoFrameSettings">취소</button>
          <button class="main-dashboard__primary" type="button" @click="savePhotoFrameSettings">저장</button>
        </footer>
      </section>
    </div>
  </div>
</template>

<style scoped>
.main-dashboard {
  display: grid;
  gap: 14px;
  min-width: 0;
}

.main-dashboard__overview {
  display: grid;
  gap: 14px;
  min-width: 0;
}

.main-dashboard__overview-card {
  min-width: 0;
}

.main-dashboard__overview-cards {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  min-width: 0;
}

.main-dashboard__overview-card {
  display: grid;
  gap: 7px;
  min-height: 134px;
  padding: 18px;
}

.main-dashboard__overview-card span,
.main-dashboard__overview-card strong,
.main-dashboard__overview-card small,
.main-dashboard__overview-card p {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.main-dashboard__overview-card span {
  font-size: 0.72rem;
  font-weight: 900;
}

.main-dashboard__overview-card strong {
  font-size: clamp(1.28rem, 2.4vw, 2rem);
  letter-spacing: 0;
  line-height: 1.08;
}

.main-dashboard__overview-card small {
  font-size: 0.84rem;
  font-weight: 800;
}

.main-dashboard__overview-card p {
  margin: 0;
  font-size: 0.78rem;
  font-weight: 700;
}

.main-dashboard__header {
  align-items: center;
  background: #ffffff;
  border: 1px solid #d9dde5;
  display: flex;
  gap: 16px;
  justify-content: space-between;
  min-width: 0;
  padding: 18px;
}

.main-dashboard__eyebrow,
.main-palette__head strong,
.main-dashboard__field span,
.main-dashboard__hidden > span {
  color: #6f42c1;
  font-size: 0.8rem;
  font-weight: 800;
}

.main-dashboard__header h2 {
  color: #111827;
  font-size: 1.35rem;
  line-height: 1.2;
  margin: 4px 0 6px;
}

.main-dashboard__header p {
  color: #6b7280;
  font-size: 0.86rem;
  margin: 0;
}

.main-dashboard__palette-zone {
  background: #f4f5f7;
  border: 1px solid #d9dde5;
  min-width: 0;
  overflow-x: hidden;
  padding: 12px;
  position: relative;
}

.main-dashboard__grid {
  min-width: 100%;
  overflow: visible;
  width: 100%;
}

.main-dashboard__grid-guide {
  display: grid;
  gap: var(--main-dashboard-grid-gap, 8px);
  grid-auto-rows: calc(var(--main-dashboard-cell-height, 112px) - var(--main-dashboard-grid-gap, 8px));
  grid-template-columns: repeat(9, minmax(0, 1fr));
  left: var(--main-dashboard-grid-margin, 4px);
  pointer-events: none;
  position: absolute;
  right: var(--main-dashboard-grid-margin, 4px);
  top: var(--main-dashboard-grid-margin, 4px);
  z-index: 0;
}

.main-dashboard__grid-guide span {
  background: var(--grid-guide-bg);
  border: 1px dashed var(--grid-guide-border);
  box-sizing: border-box;
  min-width: 0;
}

:deep(.grid-stack-item) {
  z-index: 1;
}

:deep(.grid-stack-item-content) {
  inset: var(--main-dashboard-grid-margin, 4px);
  overflow: hidden;
}

.main-palette {
  background: #ffffff;
  border: 1px solid #d9dde5;
  box-shadow: 0 4px 12px rgba(15, 23, 42, 0.05);
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.main-palette--editing {
  cursor: grab;
  outline: 1px dashed rgba(111, 66, 193, 0.35);
}

:deep(.grid-stack-item.is-main-palette-dragging .main-palette) {
  cursor: grabbing;
  opacity: 0.92;
}

:deep(.grid-stack-item.is-main-palette-resizing .main-palette) {
  cursor: nwse-resize;
  opacity: 0.96;
  outline: 1px solid rgba(109, 114, 186, 0.5);
}

.main-dashboard__palette-zone:not(.is-editing) :deep(.ui-resizable-handle) {
  display: none !important;
}

.main-dashboard__palette-zone.is-editing :deep(.ui-resizable-se) {
  background: rgba(109, 114, 186, 0.86);
  border: 2px solid #ffffff;
  bottom: 8px;
  box-shadow: 0 2px 8px rgba(16, 17, 31, 0.18);
  cursor: nwse-resize;
  height: 14px;
  right: 8px;
  width: 14px;
  z-index: 4;
}

.main-palette__head {
  align-items: center;
  border-bottom: 1px solid #edf0f4;
  display: flex;
  gap: 8px;
  justify-content: space-between;
  min-height: 32px;
  min-width: 0;
  padding: 6px 8px;
}

.main-palette__head strong {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}


.main-palette__head-actions {
  align-items: center;
  display: flex;
  flex: 0 0 auto;
  gap: 6px;
  min-width: 0;
}

.main-palette__settings-button {
  background: #f8fafc;
  border: 1px solid #d1d5db;
  color: #374151;
  cursor: pointer;
  font-size: 0.76rem;
  font-weight: 900;
  min-height: 28px;
  padding: 0 9px;
}
.main-palette__actions {
  align-items: center;
  display: flex;
  flex: 0 0 auto;
  gap: 4px;
}

.main-palette__actions button,
.main-palette__payment select,
.main-palette__quick-form input,
.main-palette__quick-form select,
.main-palette__quick-buttons button {
  background: #f8fafc;
  border: 1px solid #d1d5db;
  color: #374151;
  font-size: 0.84rem;
  min-height: 36px;
  padding: 0 10px;
}

.main-palette__actions button {
  max-width: 46px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.main-palette__body {
  min-height: 0;
  overflow: hidden;
  padding: 12px;
}

.main-palette__metric-grid {
  display: grid;
  gap: 8px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  height: 100%;
}

.main-palette__metric,
.main-palette__single-metric {
  background: #f8fafc;
  border: 1px solid #e5e7eb;
  display: grid;
  gap: 5px;
  min-width: 0;
  padding: 10px;
}

.main-palette__single-metric {
  align-content: center;
  height: 100%;
}

.main-palette__metric span,
.main-palette__single-metric span,
.main-palette__compare-label span,
.main-palette__compare-row small {
  color: #6b7280;
  font-size: 0.78rem;
}

.main-palette__metric strong,
.main-palette__single-metric strong {
  color: #111827;
  display: block;
  font-variant-numeric: tabular-nums;
  font-size: 1.08rem;
  letter-spacing: 0;
  line-height: 1.18;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.main-palette__single-metric strong {
  font-size: clamp(1.16rem, 1.9vw, 1.68rem);
}

.main-palette__metric.is-positive strong,
.main-palette__single-metric.is-positive strong,
.is-positive {
  color: #047857;
}

.main-palette__metric.is-negative strong,
.main-palette__single-metric.is-negative strong,
.is-negative {
  color: #b91c1c;
}

.main-palette__payment,
.main-palette__compare,
.main-palette__quick-form,
.main-palette__list,
.main-palette__feature-links {
  display: grid;
  gap: 8px;
  min-height: 0;
}

.main-palette__payment select,
.main-palette__quick-form input,
.main-palette__quick-form select {
  width: 100%;
}

.main-palette__compare-row {
  display: grid;
  gap: 5px;
  min-width: 0;
}

.main-palette__compare-label,
.main-palette__list-row {
  align-items: center;
  display: flex;
  gap: 8px;
  justify-content: space-between;
  min-width: 0;
}

.main-palette__bar {
  background: #edf0f4;
  height: 7px;
  overflow: hidden;
}

.main-palette__bar span {
  background: #dc2626;
  display: block;
  height: 100%;
}

.main-palette__quick-row,
.main-palette__quick-buttons {
  display: grid;
  gap: 8px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.main-palette__quick-buttons {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.main-palette__list-row {
  border-top: 1px solid #edf0f4;
  padding-top: 7px;
}

.main-palette__list-row span {
  color: #374151;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.main-palette__list-row strong {
  color: #111827;
  flex: 0 0 auto;
  font-size: 0.78rem;
}

.main-palette__feature-links {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.main-palette__feature-link {
  background: #f8fafc;
  border: 1px solid #e5e7eb;
  display: grid;
  gap: 4px;
  min-width: 0;
  padding: 10px;
  text-align: left;
}

.main-palette__feature-link span {
  color: #6f42c1;
  font-size: 0.68rem;
  font-weight: 800;
}

.main-palette__feature-link strong {
  color: #111827;
  font-size: 0.86rem;
}

.main-palette__photo-frame {
  display: grid;
  gap: 8px;
  grid-template-rows: minmax(0, 1fr);
  height: 100%;
  min-height: 0;
}

.main-palette__photo-hero {
  background: #111827;
  border: 0;
  color: #ffffff;
  cursor: pointer;
  display: block;
  height: 100%;
  min-height: 0;
  overflow: hidden;
  padding: 0;
  position: relative;
  text-align: left;
  text-decoration: none;
  width: 100%;
}

.main-palette__photo-hero img {
  display: block;
  height: 100%;
  object-fit: cover;
  width: 100%;
}

.main-palette__photo-caption {
  bottom: 8px;
  display: grid;
  gap: 4px;
  left: 8px;
  max-width: calc(100% - 16px);
  position: absolute;
  right: 8px;
  z-index: 2;
}

.main-palette__photo-caption span,
.main-palette__photo-caption strong,
.main-palette__photo-caption small {
  background: rgba(17, 24, 39, 0.76);
  border-radius: 999px;
  color: #ffffff;
  display: block;
  left: auto !important;
  max-width: 100%;
  overflow: hidden;
  padding: 3px 7px;
  position: static !important;
  text-overflow: ellipsis;
  white-space: nowrap;
  width: fit-content;
}

.main-palette__photo-caption span {
  color: #bbf7d0;
  font-size: 0.68rem;
  font-weight: 800;
}

.main-palette__photo-caption strong {
  font-size: 0.78rem;
}

.main-palette__photo-caption small {
  color: #dbeafe;
  font-size: 0.68rem;
  font-weight: 800;
}


.main-palette__photo-empty {
  align-content: center;
  background: #f8fafc;
  border: 1px dashed #d1d5db;
  color: #6b7280;
  display: grid;
  gap: 6px;
  height: 100%;
  padding: 12px;
  text-align: center;
}

.main-palette__photo-empty strong {
  color: #111827;
  font-size: 0.9rem;
}


.main-photo-frame-modal {
  align-items: center;
  background: rgba(2, 6, 23, 0.68);
  display: flex;
  inset: 0;
  justify-content: center;
  padding: 24px;
  position: fixed;
  z-index: 1000;
}

.main-photo-frame-modal__dialog {
  background: #111b2a;
  border: 1px solid #334155;
  box-shadow: 0 24px 70px rgba(0, 0, 0, 0.36);
  color: #e5edf7;
  display: grid;
  gap: 16px;
  max-height: calc(100vh - 48px);
  max-width: 1040px;
  overflow: auto;
  padding: 18px;
  width: min(1040px, 100%);
}

.main-photo-frame-modal__header,
.main-photo-frame-modal__footer,
.main-photo-frame-modal__preview-head {
  align-items: center;
  display: flex;
  gap: 12px;
  justify-content: space-between;
  min-width: 0;
}

.main-photo-frame-modal__header span {
  color: #8fdcc6;
  font-size: 0.78rem;
  font-weight: 900;
}

.main-photo-frame-modal__header h2 {
  color: #f8fafc;
  font-size: 1.2rem;
  margin: 4px 0 0;
}

.main-photo-frame-modal__header button,
.main-photo-frame-modal__footer button {
  background: #0b1220;
  border: 1px solid #334155;
  color: #f8fafc;
  cursor: pointer;
  font-weight: 900;
  min-height: 40px;
  padding: 0 16px;
}

.main-photo-frame-modal__body {
  display: grid;
  gap: 16px;
  grid-template-columns: minmax(240px, 320px) minmax(0, 1fr);
  min-width: 0;
}

.main-photo-frame-modal__form,
.main-photo-frame-modal__preview {
  background: #0b1220;
  border: 1px solid #334155;
  display: grid;
  gap: 12px;
  min-width: 0;
  padding: 14px;
}

.main-photo-frame-modal__form label {
  display: grid;
  gap: 6px;
  min-width: 0;
}

.main-photo-frame-modal__form label span,
.main-photo-frame-modal__preview-head strong {
  color: #cbd5e1;
  font-size: 0.82rem;
  font-weight: 900;
}

.main-photo-frame-modal__form select {
  background: #111827;
  border: 1px solid #475569;
  color: #f8fafc;
  min-height: 40px;
  min-width: 0;
  padding: 0 10px;
}

.main-photo-frame-modal__photo-grid {
  display: grid;
  gap: 10px;
  grid-template-columns: repeat(auto-fill, minmax(128px, 1fr));
  max-height: 460px;
  min-width: 0;
  overflow: auto;
}

.main-photo-frame-modal__photo-grid button {
  background: #111827;
  border: 1px solid #334155;
  color: #e5edf7;
  cursor: pointer;
  display: grid;
  gap: 7px;
  min-width: 0;
  padding: 8px;
  text-align: left;
}

.main-photo-frame-modal__photo-grid button.is-selected {
  border-color: #34d399;
  box-shadow: 0 0 0 1px rgba(52, 211, 153, 0.55);
}

.main-photo-frame-modal__photo-grid img {
  aspect-ratio: 4 / 3;
  background: #020617;
  object-fit: cover;
  width: 100%;
}

.main-photo-frame-modal__photo-grid span,
.main-photo-frame-modal__photo-grid small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.main-photo-frame-modal__photo-grid small {
  color: #93c5fd;
  font-size: 0.72rem;
  font-weight: 800;
}

.main-photo-frame-modal__empty,
.main-photo-frame-modal__state,
.main-photo-frame-modal__error {
  color: #cbd5e1;
  margin: 0;
}

.main-photo-frame-modal__empty {
  align-content: center;
  border: 1px dashed #475569;
  display: grid;
  gap: 6px;
  min-height: 220px;
  padding: 18px;
  text-align: center;
}

.main-photo-frame-modal__error {
  color: #fecaca;
}

.main-photo-frame-detail-modal__dialog {
  width: min(1100px, 100%);
}

.main-photo-frame-detail-modal__body {
  display: grid;
  gap: 16px;
  grid-template-columns: minmax(0, 1.2fr) minmax(280px, 0.8fr);
  min-width: 0;
}

.main-photo-frame-detail-modal__figure {
  background: #020617;
  display: grid;
  gap: 10px;
  margin: 0;
  min-width: 0;
  padding: 12px;
}

.main-photo-frame-detail-modal__figure img {
  display: block;
  max-height: min(68vh, 720px);
  min-height: 0;
  object-fit: contain;
  width: 100%;
}

.main-photo-frame-detail-modal__figure figcaption {
  color: #dbeafe;
  font-size: 0.82rem;
  font-weight: 800;
  overflow-wrap: anywhere;
}

.main-photo-frame-detail-modal__info {
  display: grid;
  gap: 12px;
  min-width: 0;
}

.main-photo-frame-detail-modal__metadata {
  display: grid;
  gap: 8px;
  margin: 0;
}

.main-photo-frame-detail-modal__metadata > div {
  background: #0b1220;
  border: 1px solid #334155;
  display: grid;
  gap: 4px;
  min-width: 0;
  padding: 10px 12px;
}

.main-photo-frame-detail-modal__metadata dt {
  color: #8fdcc6;
  font-size: 0.72rem;
  font-weight: 900;
}

.main-photo-frame-detail-modal__metadata dd {
  color: #f8fafc;
  font-size: 0.88rem;
  font-weight: 800;
  margin: 0;
  overflow-wrap: anywhere;
}

.main-photo-frame-detail-modal__info .travel-mini-location-map {
  min-height: 190px;
}

.main-photo-frame-detail-modal__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.main-photo-frame-detail-modal__actions button {
  background: #0b1220;
  border: 1px solid #334155;
  color: #f8fafc;
  cursor: pointer;
  font-weight: 900;
  min-height: 40px;
  padding: 0 16px;
}

.main-photo-frame-detail-modal__actions button:first-child {
  background: #047857;
  border-color: #34d399;
}
@media (max-width: 860px) {
  .main-photo-frame-modal {
    padding: 12px;
  }

  .main-photo-frame-modal__body,
  .main-photo-frame-detail-modal__body {
    grid-template-columns: 1fr;
  }

  .main-photo-frame-detail-modal__figure img {
    max-height: 46vh;
  }

  .main-photo-frame-modal__dialog {
    max-height: calc(100vh - 24px);
  }
}
.main-palette__capacity {
  display: grid;
  gap: 10px;
}

.main-palette__capacity-track {
  background: #edf0f4;
  height: 9px;
  overflow: hidden;
}

.main-palette__capacity-track span {
  background: #6f42c1;
  display: block;
  height: 100%;
}

.main-palette__inline-action {
  background: #f8fafc;
  border: 1px solid #d1d5db;
  color: #374151;
  font-size: 0.78rem;
  font-weight: 800;
  min-height: 32px;
}

.main-palette__recent-files {
  display: grid;
  gap: 7px;
  min-height: 0;
}

.main-palette__recent-file {
  align-items: center;
  border-top: 1px solid #edf0f4;
  color: #374151;
  display: grid;
  gap: 8px;
  grid-template-columns: 36px minmax(0, 1fr) auto;
  min-width: 0;
  padding-top: 7px;
  text-decoration: none;
}

.main-palette__recent-file img,
.main-palette__file-icon {
  aspect-ratio: 1;
  background: #f3f4f6;
  border: 1px solid #e5e7eb;
  display: grid;
  font-size: 0.58rem;
  font-weight: 900;
  object-fit: cover;
  place-items: center;
  width: 36px;
}

.main-palette__recent-file strong {
  color: #111827;
  font-size: 0.78rem;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.main-palette__recent-file small {
  color: #6b7280;
  font-size: 0.72rem;
}

.main-palette__quick-actions {
  display: grid;
  gap: 8px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.main-palette__quick-action {
  background: #f8fafc;
  border: 1px solid #e5e7eb;
  display: grid;
  gap: 4px;
  min-width: 0;
  padding: 10px;
  text-align: left;
}

.main-palette__quick-action strong {
  color: #111827;
  font-size: 0.84rem;
}

.main-palette__quick-action span {
  color: #6b7280;
  font-size: 0.7rem;
}

.main-palette__empty {
  color: #9ca3af;
  font-size: 0.78rem;
  margin: 0;
}

.main-dashboard__floating-button {
  background: #3f2a78;
  border: 1px solid #3f2a78;
  box-shadow: 0 8px 18px rgba(31, 41, 55, 0.16);
  color: #ffffff;
  font-size: 0.78rem;
  font-weight: 800;
  min-height: 36px;
  padding: 0 12px;
  position: fixed;
  right: 18px;
  top: 50%;
  transform: translateY(-50%);
  z-index: 40;
}

.main-dashboard__floating-button.is-active,
.main-dashboard__floating-button:hover {
  background: #5f3dc4;
}

.main-dashboard__tools {
  background: #ffffff;
  border: 1px solid #cfd5df;
  box-shadow: 0 14px 32px rgba(15, 23, 42, 0.18);
  display: grid;
  gap: 12px;
  max-width: calc(100vw - 36px);
  padding: 14px;
  position: fixed;
  right: 18px;
  top: calc(50% + 46px);
  width: 290px;
  z-index: 41;
}

.main-dashboard__tools-head {
  align-items: center;
  display: flex;
  justify-content: space-between;
}

.main-dashboard__tools-head button,
.main-dashboard__tools button,
.main-dashboard__field select {
  border: 1px solid #d1d5db;
  font-size: 0.8rem;
  min-height: 32px;
  padding: 0 9px;
}

.main-dashboard__field,
.main-dashboard__hidden {
  display: grid;
  gap: 6px;
}

.main-dashboard__field select {
  background: #ffffff;
  width: 100%;
}

.main-dashboard__primary {
  background: #3f2a78;
  border-color: #3f2a78;
  color: #ffffff;
  font-weight: 800;
}

.main-dashboard__secondary {
  background: #f8fafc;
  color: #374151;
}

.main-dashboard__hidden button {
  background: #f8fafc;
  color: #374151;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.main-dashboard__hidden small {
  color: #9ca3af;
  font-size: 0.76rem;
}

/* Light dashboard reference skin: visual-only overrides. */
.main-dashboard {
  --dash-bg: #eef2ef;
  --dash-card: #ffffff;
  --dash-ink: #10201f;
  --dash-muted: #667775;
  --dash-line: rgba(0, 105, 96, 0.1);
  --dash-strong-line: rgba(0, 105, 96, 0.18);
  --dash-panel: rgba(255, 255, 255, 0.96);
  --dash-control-bg: #f4f8f6;
  --dash-control-hover: #d7ff35;
  --dash-tile-bg: #f6faf8;
  --dash-track-bg: rgba(0, 105, 96, 0.1);
  --dash-teal: #006960;
  --dash-teal-strong: #00534d;
  --dash-teal-soft: #d7f3ed;
  --dash-mint: #67ded1;
  --dash-mint-soft: #dff8f4;
  --dash-lime: #d7ff35;
  --dash-lime-soft: #f1ffbe;
  --dash-amber: #f7c956;
  --dash-amber-soft: #fff4c7;
  --dash-coral: #ff765f;
  --dash-coral-soft: #ffe2dc;
  --dash-positive: #006960;
  --dash-negative: #c7513f;
  --dash-popover-shadow: 0 18px 40px rgba(0, 83, 77, 0.14);
  --dash-card-radius: 18px;
  background: var(--dash-bg);
  border-radius: 28px;
  gap: 18px;
  padding: 18px;
}

.main-dashboard__overview-card {
  background: var(--dash-card);
  border: 0;
  border-radius: 24px;
  box-shadow: 0 18px 46px rgba(0, 83, 77, 0.08);
}

.main-dashboard__overview-card {
  color: var(--dash-ink);
}

.main-dashboard__overview-card span {
  color: var(--dash-teal);
}

.main-dashboard__overview-card p,
.main-dashboard__overview-card small {
  color: var(--dash-muted);
}

.main-dashboard__overview-card.is-teal {
  background: var(--dash-teal);
  color: #ffffff;
}

.main-dashboard__overview-card.is-mint {
  background: var(--dash-mint-soft);
}

.main-dashboard__overview-card.is-positive {
  background: var(--dash-lime-soft);
}

.main-dashboard__overview-card.is-negative {
  background: var(--dash-coral-soft);
}

.main-dashboard__overview-card.is-teal span,
.main-dashboard__overview-card.is-teal p,
.main-dashboard__overview-card.is-teal small {
  color: rgba(255, 255, 255, 0.76);
}

.main-dashboard__overview-card.is-teal strong {
  color: #ffffff;
}

.main-dashboard__header {
  background: var(--dash-panel);
  border: 0;
  border-radius: 16px;
  box-shadow: none;
  min-height: 72px;
  padding: 16px 20px;
}

.main-dashboard__eyebrow,
.main-palette__head strong,
.main-dashboard__field span,
.main-dashboard__hidden > span {
  color: var(--dash-teal);
}

.main-dashboard__header h2 {
  color: var(--dash-ink);
  font-size: 1.32rem;
  letter-spacing: 0;
  margin: 2px 0 4px;
}

.main-dashboard__header p {
  color: var(--dash-muted);
}

.main-dashboard__palette-zone {
  background: transparent;
  border: 0;
  padding: 0;
}

.main-dashboard__grid-guide span {
  background: rgba(103, 222, 209, 0.16);
  border-color: rgba(0, 105, 96, 0.2);
}

:deep(.grid-stack-item-content) {
  inset: var(--main-dashboard-grid-margin, 4px);
}

.main-palette {
  background: var(--dash-card);
  border: 0;
  border-radius: var(--dash-card-radius);
  box-shadow: none;
}

.main-palette--editing {
  outline: 1px dashed rgba(109, 114, 186, 0.38);
}

.main-palette__head {
  border-bottom: 0;
  min-height: 48px;
  padding: 14px 16px 6px;
}

.main-palette__body {
  padding: 12px 16px 18px;
}

.main-palette__actions button,
.main-palette__payment select,
.main-palette__quick-form input,
.main-palette__quick-form select,
.main-palette__quick-buttons button,
.main-dashboard__tools-head button,
.main-dashboard__tools button,
.main-dashboard__field select {
  background: var(--dash-control-bg);
  border: 0;
  border-radius: 8px;
  color: var(--dash-ink);
}

.main-palette__quick-buttons button:hover,
.main-palette__feature-link:hover,
.main-palette__quick-action:hover,
.main-palette__inline-action:hover {
  background: var(--dash-control-hover);
}

.main-palette__metric,
.main-palette__single-metric,
.main-palette__feature-link,
.main-palette__quick-action,
.main-palette__photo-empty {
  background: var(--dash-tile-bg);
  border: 0;
  border-radius: 10px;
}

.main-palette__metric,
.main-palette__single-metric {
  padding: 14px;
}

.main-palette--household-summary .main-palette__metric:first-child,
.main-palette--monthExpense .main-palette__single-metric,
.main-palette--weekExpense .main-palette__single-metric {
  background: var(--dash-lime-soft);
}

.main-palette--monthIncome .main-palette__single-metric,
.main-palette--weekIncome .main-palette__single-metric,
.main-palette--travel-summary .main-palette__metric:first-child,
.main-palette--drive-summary .main-palette__metric:first-child {
  background: var(--dash-mint-soft);
}

.main-palette--photo-frame,
.main-palette--drive-capacity {
  background: var(--dash-teal-soft);
}

.main-palette--photo-frame .main-palette__head strong,
.main-palette--drive-capacity .main-palette__head strong {
  color: var(--dash-ink);
}

.main-palette__metric span,
.main-palette__single-metric span,
.main-palette__compare-label span,
.main-palette__compare-row small,
.main-palette__quick-action span,
.main-palette__recent-file small {
  color: var(--dash-muted);
}

.main-palette__metric strong,
.main-palette__single-metric strong,
.main-palette__feature-link strong,
.main-palette__quick-action strong,
.main-palette__recent-file strong,
.main-palette__list-row strong {
  color: var(--dash-ink);
}

.main-palette__metric.is-positive strong,
.main-palette__single-metric.is-positive strong,
.is-positive,
.main-palette__metric.is-negative strong,
.main-palette__single-metric.is-negative strong,
.is-negative {
  color: var(--dash-ink);
}

.main-palette__list-row,
.main-palette__recent-file {
  border-top-color: var(--dash-line);
}

.main-palette__bar,
.main-palette__capacity-track {
  background: var(--dash-track-bg);
  border-radius: 999px;
}

.main-palette__bar span,
.main-palette__capacity-track span {
  background: var(--dash-teal);
  border-radius: inherit;
}

.main-palette__photo-hero,
.main-palette__recent-file img,
.main-palette__file-icon {
  border: 0;
  border-radius: 10px;
}


.main-palette__inline-action {
  background: var(--dash-control-bg);
  border: 0;
  border-radius: 8px;
}

.main-palette__feature-link span {
  color: var(--dash-teal);
}

.main-dashboard__floating-button {
  background: var(--dash-teal);
  border: 0;
  border-radius: 10px;
  bottom: 24px;
  box-shadow: 0 14px 28px rgba(0, 83, 77, 0.22);
  color: #ffffff;
  top: auto;
  transform: none;
}

.main-dashboard__floating-button.is-active,
.main-dashboard__floating-button:hover {
  background: var(--dash-lime);
  color: var(--dash-ink);
}

.main-dashboard__tools {
  background: var(--dash-panel);
  border: 0;
  border-radius: 16px;
  box-shadow: var(--dash-popover-shadow);
}

.main-dashboard__primary {
  background: var(--dash-teal);
  border-color: transparent;
  color: #ffffff;
}

.main-dashboard__secondary,
.main-dashboard__hidden button {
  background: var(--dash-control-bg);
}

:global(html[data-theme='toss'] .main-dashboard ){
  --dash-bg: linear-gradient(180deg, rgba(18, 24, 33, 0.94), rgba(14, 19, 27, 0.96));
  --dash-card: linear-gradient(180deg, rgba(31, 38, 50, 0.97), rgba(24, 31, 42, 0.97));
  --dash-ink: #edf3f8;
  --dash-muted: #a3b0bf;
  --dash-line: rgba(94, 109, 132, 0.32);
  --dash-strong-line: rgba(110, 126, 150, 0.44);
  --dash-panel: linear-gradient(180deg, rgba(30, 37, 49, 0.98), rgba(23, 30, 41, 0.98));
  --dash-control-bg: rgba(33, 41, 54, 0.94);
  --dash-control-hover: rgba(43, 52, 67, 0.96);
  --dash-tile-bg: rgba(34, 42, 55, 0.8);
  --dash-track-bg: rgba(96, 112, 136, 0.24);
  --dash-teal: #78c9c0;
  --dash-teal-strong: #4da69d;
  --dash-teal-soft: rgba(86, 154, 147, 0.16);
  --dash-mint: #9ed8d2;
  --dash-mint-soft: rgba(105, 176, 168, 0.14);
  --dash-lime: #c1d887;
  --dash-lime-soft: rgba(193, 216, 135, 0.13);
  --dash-lavender: rgba(137, 152, 204, 0.28);
  --dash-lavender-soft: rgba(137, 152, 204, 0.13);
  --dash-amber: #dfbd76;
  --dash-amber-soft: rgba(223, 189, 118, 0.13);
  --dash-coral: #df8f86;
  --dash-coral-soft: rgba(223, 143, 134, 0.13);
  --dash-positive: #b5d98a;
  --dash-negative: #e39a91;
  --dash-popover-shadow: 0 18px 40px rgba(0, 0, 0, 0.3);
  background: var(--dash-bg);
  border: 1px solid rgba(91, 107, 129, 0.36);
  color: var(--dash-ink);
}

:global(html[data-theme='toss'] .main-dashboard__header),
:global(html[data-theme='toss'] .main-dashboard__overview-card),
:global(html[data-theme='toss'] .main-dashboard__tools ){
  background: var(--dash-panel);
  border: 1px solid rgba(91, 107, 129, 0.38);
  box-shadow: var(--dash-popover-shadow);
}

:global(html[data-theme='toss'] .main-dashboard__overview-card.is-teal ){
  background: linear-gradient(180deg, rgba(42, 113, 107, 0.58), rgba(24, 31, 42, 0.98));
  border: 1px solid rgba(120, 201, 192, 0.24);
}

:global(html[data-theme='toss'] .main-dashboard__overview-card.is-mint),
:global(html[data-theme='toss'] .main-dashboard__overview-card.is-positive),
:global(html[data-theme='toss'] .main-dashboard__overview-card.is-negative ){
  background: var(--dash-tile-bg);
  border: 1px solid rgba(91, 107, 129, 0.32);
}

:global(html[data-theme='toss'] .main-dashboard__overview-card.is-mint ){
  border-color: rgba(158, 216, 210, 0.2);
}

:global(html[data-theme='toss'] .main-dashboard__overview-card.is-positive ){
  border-color: rgba(193, 216, 135, 0.2);
}

:global(html[data-theme='toss'] .main-dashboard__overview-card.is-negative ){
  border-color: rgba(223, 143, 134, 0.2);
}

:global(html[data-theme='toss'] .main-dashboard__palette-zone ){
  background: transparent;
}

:global(html[data-theme='toss'] .main-dashboard__eyebrow),
:global(html[data-theme='toss'] .main-dashboard__overview-card span),
:global(html[data-theme='toss'] .main-palette__head strong),
:global(html[data-theme='toss'] .main-dashboard__field span),
:global(html[data-theme='toss'] .main-dashboard__hidden > span),
:global(html[data-theme='toss'] .main-palette__feature-link span ){
  color: var(--dash-teal);
}

:global(html[data-theme='toss'] .main-dashboard__header h2),
:global(html[data-theme='toss'] .main-dashboard__overview-card strong),
:global(html[data-theme='toss'] .main-palette__metric strong),
:global(html[data-theme='toss'] .main-palette__single-metric strong),
:global(html[data-theme='toss'] .main-palette__feature-link strong),
:global(html[data-theme='toss'] .main-palette__quick-action strong),
:global(html[data-theme='toss'] .main-palette__recent-file strong),
:global(html[data-theme='toss'] .main-palette__list-row strong),
:global(html[data-theme='toss'] .main-palette__photo-empty strong),
:global(html[data-theme='toss'] .main-palette--photo-frame .main-palette__head strong),
:global(html[data-theme='toss'] .main-palette--drive-capacity .main-palette__head strong ){
  color: var(--dash-ink);
}

:global(html[data-theme='toss'] .main-dashboard__header p),
:global(html[data-theme='toss'] .main-dashboard__overview-card p),
:global(html[data-theme='toss'] .main-dashboard__overview-card small),
:global(html[data-theme='toss'] .main-palette__metric span),
:global(html[data-theme='toss'] .main-palette__single-metric span),
:global(html[data-theme='toss'] .main-palette__single-metric small),
:global(html[data-theme='toss'] .main-palette__compare-label span),
:global(html[data-theme='toss'] .main-palette__compare-row small),
:global(html[data-theme='toss'] .main-palette__quick-action span),
:global(html[data-theme='toss'] .main-palette__recent-file small),
:global(html[data-theme='toss'] .main-palette__list-row span),
:global(html[data-theme='toss'] .main-palette__photo-empty),
:global(html[data-theme='toss'] .main-palette__empty),
:global(html[data-theme='toss'] .main-dashboard__hidden small ){
  color: var(--dash-muted);
}

:global(html[data-theme='toss'] .main-dashboard__overview-card.is-teal span),
:global(html[data-theme='toss'] .main-dashboard__overview-card.is-teal p),
:global(html[data-theme='toss'] .main-dashboard__overview-card.is-teal small ){
  color: rgba(237, 243, 248, 0.74);
}

:global(html[data-theme='toss'] .main-dashboard__overview-card.is-teal strong ){
  color: #f6fbff;
}

:global(html[data-theme='toss'] .main-palette ){
  background: var(--dash-card);
  border: 1px solid rgba(91, 107, 129, 0.34);
  box-shadow: 0 18px 34px rgba(0, 0, 0, 0.24);
}

:global(html[data-theme='toss'] .main-palette__head),
:global(html[data-theme='toss'] .main-palette__list-row),
:global(html[data-theme='toss'] .main-palette__recent-file ){
  border-color: var(--dash-strong-line);
}

:global(html[data-theme='toss'] .main-palette__actions button),
:global(html[data-theme='toss'] .main-palette__payment select),
:global(html[data-theme='toss'] .main-palette__quick-form input),
:global(html[data-theme='toss'] .main-palette__quick-form select),
:global(html[data-theme='toss'] .main-palette__quick-buttons button),
:global(html[data-theme='toss'] .main-dashboard__tools-head button),
:global(html[data-theme='toss'] .main-dashboard__tools button),
:global(html[data-theme='toss'] .main-dashboard__field select),
:global(html[data-theme='toss'] .main-dashboard__secondary),
:global(html[data-theme='toss'] .main-dashboard__hidden button),
:global(html[data-theme='toss'] .main-palette__inline-action ){
  background: var(--dash-control-bg);
  border: 1px solid rgba(91, 107, 129, 0.36);
  color: var(--dash-ink);
}

:global(html[data-theme='toss'] .main-palette__quick-buttons button:hover),
:global(html[data-theme='toss'] .main-palette__feature-link:hover),
:global(html[data-theme='toss'] .main-palette__quick-action:hover),
:global(html[data-theme='toss'] .main-palette__inline-action:hover),
:global(html[data-theme='toss'] .main-dashboard__hidden button:hover ){
  background: var(--dash-control-hover);
  border-color: rgba(120, 201, 192, 0.26);
}

:global(html[data-theme='toss'] .main-palette__metric),
:global(html[data-theme='toss'] .main-palette__single-metric),
:global(html[data-theme='toss'] .main-palette__feature-link),
:global(html[data-theme='toss'] .main-palette__quick-action),
:global(html[data-theme='toss'] .main-palette__photo-empty ){
  background: var(--dash-tile-bg);
  border: 1px solid rgba(91, 107, 129, 0.3);
}

:global(html[data-theme='toss'] .main-palette--household-summary .main-palette__metric:first-child),
:global(html[data-theme='toss'] .main-palette--monthExpense .main-palette__single-metric),
:global(html[data-theme='toss'] .main-palette--weekExpense .main-palette__single-metric ){
  background: linear-gradient(180deg, var(--dash-coral-soft), rgba(31, 38, 50, 0.9));
  border-color: rgba(223, 143, 134, 0.2);
}

:global(html[data-theme='toss'] .main-palette--monthIncome .main-palette__single-metric),
:global(html[data-theme='toss'] .main-palette--weekIncome .main-palette__single-metric),
:global(html[data-theme='toss'] .main-palette--travel-summary .main-palette__metric:first-child),
:global(html[data-theme='toss'] .main-palette--drive-summary .main-palette__metric:first-child ){
  background: linear-gradient(180deg, var(--dash-mint-soft), rgba(31, 38, 50, 0.9));
  border-color: rgba(158, 216, 210, 0.2);
}

:global(html[data-theme='toss'] .main-palette--photo-frame),
:global(html[data-theme='toss'] .main-palette--drive-capacity ){
  background: linear-gradient(180deg, var(--dash-teal-soft), rgba(31, 38, 50, 0.94));
  border-color: rgba(120, 201, 192, 0.2);
}

:global(html[data-theme='toss'] .main-palette__bar),
:global(html[data-theme='toss'] .main-palette__capacity-track ){
  background: var(--dash-track-bg);
}

:global(html[data-theme='toss'] .main-palette__bar span),
:global(html[data-theme='toss'] .main-palette__capacity-track span ){
  background: var(--dash-teal-strong);
}

:global(html[data-theme='toss'] .main-palette__recent-file img),
:global(html[data-theme='toss'] .main-palette__file-icon ){
  background: rgba(33, 41, 54, 0.94);
  border: 1px solid rgba(91, 107, 129, 0.34);
  color: var(--dash-muted);
}

:global(html[data-theme='toss'] .main-palette__quick-form input::placeholder ){
  color: #7f899c;
}

:global(html[data-theme='toss'] .main-dashboard select option),
:global(html[data-theme='toss'] .main-dashboard input),
:global(html[data-theme='toss'] .main-dashboard select),
:global(html[data-theme='toss'] .main-dashboard button ){
  color-scheme: dark;
}

:global(html[data-theme='toss'] .main-palette__metric.is-positive strong),
:global(html[data-theme='toss'] .main-palette__single-metric.is-positive strong ){
  color: var(--dash-positive);
}

:global(html[data-theme='toss'] .main-palette__metric.is-negative strong),
:global(html[data-theme='toss'] .main-palette__single-metric.is-negative strong ){
  color: var(--dash-negative);
}

:global(html[data-theme='toss'] .main-dashboard__primary),
:global(html[data-theme='toss'] .main-dashboard__floating-button ){
  background: linear-gradient(180deg, var(--dash-teal-strong), #367d77);
  border-color: rgba(120, 201, 192, 0.2);
  color: #ffffff;
}

:global(html[data-theme='toss'] .main-dashboard__floating-button.is-active),
:global(html[data-theme='toss'] .main-dashboard__floating-button:hover ){
  background: linear-gradient(180deg, #93c9c2, #5fa9a1);
  color: #101820;
}

:global(html[data-theme='toss'] .main-dashboard__grid-guide span ){
  background: var(--grid-guide-bg);
  border-color: var(--grid-guide-border);
}

/* Data-density hardening for real dashboard payloads. */
.main-palette__body > * {
  min-width: 0;
}

.main-palette__metric-grid {
  grid-template-rows: repeat(2, minmax(0, 1fr));
  min-height: 0;
}

.main-palette__metric,
.main-palette__single-metric {
  align-content: center;
  min-height: 0;
  overflow: hidden;
}

.main-palette__metric span,
.main-palette__single-metric span,
.main-palette__single-metric small,
.main-palette__compare-label span,
.main-palette__compare-row small,
.main-palette__quick-action span {
  line-height: 1.25;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.main-palette__single-metric small {
  font-size: 0.82rem;
}

.main-palette__metric strong,
.main-palette__single-metric strong {
  display: block;
  font-variant-numeric: tabular-nums;
  letter-spacing: 0;
  line-height: 1.18;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.main-palette--travel-summary .main-palette__body,
.main-palette--drive-summary .main-palette__body {
  display: grid;
  gap: 8px;
  grid-template-rows: minmax(0, 1fr);
}

.main-palette--travel-summary .main-palette__body--has-list,
.main-palette--drive-summary .main-palette__body--has-list {
  grid-template-rows: minmax(0, 1fr) minmax(0, 72px);
}

.main-palette--travel-summary .main-palette__metric-grid,
.main-palette--drive-summary .main-palette__metric-grid {
  height: auto;
}

.main-palette__list {
  max-height: 100%;
  overflow: hidden;
}

.main-palette__payment {
  grid-template-rows: auto minmax(0, 1fr);
  height: 100%;
  overflow: hidden;
}

.main-palette__payment .main-palette__single-metric,
.main-palette__capacity .main-palette__single-metric {
  height: auto;
}


.main-palette__capacity {
  grid-template-rows: minmax(0, 1fr) auto auto;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.main-palette__quick-form,
.main-palette__recent-files,
.main-palette__quick-actions,
.main-palette__feature-links {
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.main-palette__quick-form input,
.main-palette__quick-form select,
.main-palette__payment select,
.main-palette__quick-buttons button {
  min-width: 0;
}

.main-palette__quick-buttons button {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.main-palette__recent-files {
  overflow-y: auto;
  padding-right: 2px;
}

.main-palette--quick-entry .main-palette__quick-form {
  align-content: start;
  overflow-y: auto;
  padding-right: 2px;
}

.main-palette__recent-file {
  min-height: 40px;
}

.main-palette__recent-file small {
  max-width: 64px;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.main-palette__quick-actions,
.main-palette__feature-links {
  grid-auto-rows: minmax(0, 1fr);
}

.main-palette__quick-action,
.main-palette__feature-link {
  overflow: hidden;
}

.main-palette__quick-action strong,
.main-palette__feature-link strong,
.main-palette__feature-link span,
.main-palette__photo-empty span,
.main-palette__photo-empty strong {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
}

.main-palette__quick-action strong,
.main-palette__feature-link strong,
.main-palette__feature-link span {
  white-space: nowrap;
}

.main-palette__photo-empty span {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.main-palette--compact .main-palette__head {
  min-height: 36px;
  padding: 8px 10px 4px;
}

.main-palette--compact .main-palette__body {
  padding: 8px 10px 10px;
}

.main-palette--compact .main-palette__metric-grid {
  gap: 6px;
  grid-template-columns: minmax(0, 1fr);
}

.main-palette--compact .main-palette__metric,
.main-palette--compact .main-palette__single-metric,
.main-palette--compact .main-palette__feature-link,
.main-palette--compact .main-palette__quick-action {
  gap: 3px;
  padding: 8px;
}

.main-palette--compact .main-palette__metric strong,
.main-palette--compact .main-palette__single-metric strong {
  font-size: 0.96rem;
}

.main-palette--h-1 .main-palette__head {
  min-height: 30px;
  padding: 6px 10px 2px;
}

.main-palette--h-1 .main-palette__body {
  padding: 6px 10px 8px;
}

.main-palette--w-2 .main-palette__quick-actions,
.main-palette--w-2 .main-palette__feature-links {
  grid-template-columns: minmax(0, 1fr);
}

.main-palette--wide .main-palette__metric-grid {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.main-palette--wide.main-palette--household-payment .main-palette__payment,
.main-palette--wide.main-palette--drive-capacity .main-palette__capacity {
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  grid-template-rows: auto minmax(0, 1fr);
}

.main-palette--wide.main-palette--household-payment .main-palette__payment select,
.main-palette--wide.main-palette--drive-capacity .main-palette__capacity-track,
.main-palette--wide.main-palette--drive-capacity .main-palette__inline-action {
  grid-column: 1 / -1;
}


.main-palette--roomy .main-palette__recent-files {
  gap: 8px;
}

.main-palette--roomy.main-palette--travel-summary .main-palette__body--has-list,
.main-palette--roomy.main-palette--drive-summary .main-palette__body--has-list {
  grid-template-rows: minmax(0, 1fr) minmax(0, 104px);
}

.main-palette--tall.main-palette--travel-summary .main-palette__body--has-list,
.main-palette--tall.main-palette--drive-summary .main-palette__body--has-list {
  grid-template-rows: minmax(0, 1fr) minmax(0, 128px);
}

@media (max-width: 720px) {
  .main-dashboard__overview {
    grid-template-columns: 1fr;
  }

  .main-dashboard__overview-cards {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .main-dashboard__header {
    align-items: stretch;
    display: grid;
  }

  .main-dashboard__palette-zone {
    padding: 8px;
  }

  .main-dashboard__grid-guide {
    left: calc(8px + var(--main-dashboard-grid-margin, 4px));
    right: calc(8px + var(--main-dashboard-grid-margin, 4px));
  }

  .main-palette__feature-links {
    grid-template-columns: minmax(0, 1fr);
  }

  .main-palette__metric-grid {
    gap: 6px;
  }

  .main-palette__metric,
  .main-palette__single-metric {
    padding: 8px;
  }

  .main-palette__metric strong,
  .main-palette__single-metric strong {
    font-size: 0.9rem;
  }

  .main-dashboard__floating-button {
    right: 12px;
  }

  .main-dashboard__tools {
    right: 12px;
    width: min(290px, calc(100vw - 24px));
  }
}

@media (max-width: 520px) {
  .main-dashboard__overview-cards {
    grid-template-columns: 1fr;
  }

  .main-dashboard__overview-card {
    min-height: 116px;
  }
}
</style>
