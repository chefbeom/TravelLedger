<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { GridStack } from 'gridstack'
import 'gridstack/dist/gridstack.min.css'
import {
  createEntry,
  fetchCategories,
  fetchCompare,
  fetchDashboard,
  fetchDriveHomeSummary,
  fetchDriveRecentFiles,
  fetchPaymentMethods,
  fetchTravelPortfolio,
} from '../lib/api'
import { DASHBOARD_GRID_COLUMNS } from '../features/palette/types'
import {
  applyLayoutPatchesToPalettes,
  findFirstAvailablePosition,
  getSpanBySize,
  normalizePaletteList,
} from '../features/palette/utils/paletteLayout'

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
const PAYMENT_SELECTION_STORAGE_VERSION = 'v1'

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

const fixedSizeByType = {
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
    description: '월별 흐름, 최근 거래, 통계 화면으로 이동합니다.',
    badge: 'Finance',
  },
  travel: {
    title: '여행',
    description: '여행 예산, 기록, 지도와 사진을 이어서 관리합니다.',
    badge: 'Travel',
  },
  drive: {
    title: '드라이브',
    description: '파일, 폴더, 공유, 휴지통 상태를 확인합니다.',
    badge: 'Cloud',
  },
  admin: {
    title: '관리자',
    description: '사용자, 초대, 접근 상태와 운영 도구를 확인합니다.',
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

const userStorageId = computed(() => props.currentUser?.id || props.currentUser?.loginId || 'anonymous')
const storageKey = computed(() => `calen-main-dashboard-palettes:${MAIN_DASHBOARD_STORAGE_VERSION}:${userStorageId.value}:${MAIN_DASHBOARD_SCOPE}`)
const paymentSelectionStorageKey = computed(() => `calen-main-dashboard-payment:${PAYMENT_SELECTION_STORAGE_VERSION}:${userStorageId.value}`)
const visiblePalettes = computed(() => palettes.value.filter((palette) => palette.visible !== false))
const hiddenPalettes = computed(() => palettes.value.filter((palette) => palette.visible === false))
const layoutKey = computed(() =>
  visiblePalettes.value
    .map((palette) => `${palette.id}:${palette.position?.x ?? 0}:${palette.position?.y ?? 0}:${palette.size}:${palette.visible}`)
    .join('|'),
)
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
const photoFrameItems = computed(() => [
  ...collectDrivePhotoItems(allRecentDriveFiles.value),
  ...collectTravelPhotoItems(),
].slice(0, 6))
const heroPhoto = computed(() => photoFrameItems.value[0] ?? null)
const quickActionItems = computed(() => [
  { key: 'household', label: '가계부 입력', meta: '달력/대시보드', route: 'household' },
  { key: 'travel', label: '여행 기록', meta: '예산/사진/지도', route: 'travel' },
  { key: 'drive', label: '파일 저장', meta: '드라이브 열기', route: 'drive' },
  { key: 'launcher', label: '메인으로', meta: '종합 보기', route: 'launcher' },
])

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
    .map((item) => ({
      id: `drive-${item.id ?? fileName(item)}`,
      title: fileName(item),
      source: '드라이브',
      imageUrl: buildDriveThumbnailPath(item),
      openUrl: buildDriveOpenPath(item),
    }))
    .filter((item) => item.imageUrl)
}

function collectTravelPhotoItems() {
  const photos = []
  ;(travelPortfolio.value?.plans ?? []).forEach((plan) => {
    ;(plan.mediaItems ?? []).forEach((item) => {
      const imageUrl = item.contentUrl || item.thumbnailUrl || item.mediaUrl || item.imageUrl || ''
      if (item.mediaType && item.mediaType !== 'PHOTO') return
      if (!imageUrl) return
      photos.push({
        id: `travel-${item.id ?? imageUrl}`,
        title: item.caption || item.originalFileName || plan.name || '여행 사진',
        source: plan.name || '여행',
        imageUrl,
        openUrl: imageUrl,
      })
    })
  })
  return photos
}

function createPaletteId(templateId) {
  return `${templateId}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
}

function fixedSizeFor(palette) {
  return fixedSizeByType[palette.type] ?? '3x2'
}

function normalizeMainPalettes(value) {
  const fixedSizePalettes = (value ?? []).map((palette) => ({
    ...palette,
    size: fixedSizeFor(palette),
  }))
  return normalizePaletteList(fixedSizePalettes).map((palette) => ({
    ...palette,
    size: fixedSizeFor(palette),
  }))
}

function hydratePalettes() {
  if (typeof window === 'undefined') {
    palettes.value = normalizeMainPalettes(clone(defaultPalettes))
    return
  }

  try {
    const raw = window.localStorage.getItem(storageKey.value)
    if (!raw) {
      palettes.value = normalizeMainPalettes(clone(defaultPalettes))
      return
    }
    const parsed = JSON.parse(raw)
    palettes.value = normalizeMainPalettes(Array.isArray(parsed?.palettes) ? parsed.palettes : defaultPalettes)
  } catch {
    palettes.value = normalizeMainPalettes(clone(defaultPalettes))
  }
}

function persistPalettes() {
  if (typeof window === 'undefined') return
  window.localStorage.setItem(storageKey.value, JSON.stringify({ palettes: palettes.value }))
}

function hydratePaymentSelection() {
  if (typeof window === 'undefined') return
  selectedPaymentMethodId.value = window.localStorage.getItem(paymentSelectionStorageKey.value) || ''
}

function persistPaymentSelection() {
  if (typeof window === 'undefined') return
  window.localStorage.setItem(paymentSelectionStorageKey.value, selectedPaymentMethodId.value)
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
  const span = getSpanBySize(palette.size)
  return {
    x: palette.position?.x ?? 0,
    y: palette.position?.y ?? 0,
    w: span.w,
    h: span.h,
  }
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
  const width = gridElement.value?.parentElement?.clientWidth || gridElement.value?.clientWidth || 0
  if (!width || !grid) return
  const rawCellWidth = (width - ((DASHBOARD_GRID_COLUMNS - 1) * 8)) / DASHBOARD_GRID_COLUMNS
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

function destroyGrid() {
  if (!grid) return
  grid.off('dragstart')
  grid.off('dragstop')
  grid.destroy(false)
  grid = null
}

function initGrid() {
  if (!gridElement.value) return
  destroyGrid()
  grid = GridStack.init({
    column: DASHBOARD_GRID_COLUMNS,
    margin: 4,
    cellHeight: cellHeight.value,
    disableResize: true,
    float: false,
    animate: true,
    draggable: {
      appendTo: 'body',
      cancel: 'button,a,input,select,textarea,[data-no-drag="true"]',
      handle: '.main-palette',
      scroll: false,
    },
  }, gridElement.value)
  grid.enableMove(isEditMode.value)
  grid.enableResize(false)
  grid.on('dragstart', handleDragStart)
  grid.on('dragstop', handleDragStop)
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
  palettes.value = normalizeMainPalettes(applyLayoutPatchesToPalettes(palettes.value, patches))
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
      position: findFirstAvailablePosition(visible, palette.size),
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
  const size = fixedSizeFor(template)
  const nextPalette = {
    id: createPaletteId(template.id),
    type: template.type,
    size,
    position: findFirstAvailablePosition(visiblePalettes.value, size),
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
  isEditMode.value = !isEditMode.value
  if (grid) {
    grid.enableMove(isEditMode.value)
    grid.enableResize(false)
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

async function loadSummaries() {
  loading.value = true
  errorMessage.value = ''
  const anchorDate = todayIso()
  const [
    householdResult,
    travelResult,
    driveResult,
    driveRecentResult,
    weekCompareResult,
    monthCompareResult,
    categoriesResult,
    paymentMethodsResult,
  ] = await Promise.allSettled([
    fetchDashboard(anchorDate),
    fetchTravelPortfolio(),
    fetchDriveHomeSummary(),
    fetchDriveRecentFiles(),
    fetchCompare(anchorDate, 'WEEK', 2),
    fetchCompare(anchorDate, 'MONTH', 2),
    fetchCategories(),
    fetchPaymentMethods(),
  ])

  if (householdResult.status === 'fulfilled') householdDashboard.value = householdResult.value
  if (travelResult.status === 'fulfilled') travelPortfolio.value = travelResult.value
  if (driveResult.status === 'fulfilled') driveSummary.value = driveResult.value
  if (driveRecentResult.status === 'fulfilled') driveRecentFileItems.value = driveRecentResult.value ?? []
  if (weekCompareResult.status === 'fulfilled') weekCompareRows.value = weekCompareResult.value ?? []
  if (monthCompareResult.status === 'fulfilled') monthCompareRows.value = monthCompareResult.value ?? []
  if (categoriesResult.status === 'fulfilled') categories.value = categoriesResult.value ?? []
  if (paymentMethodsResult.status === 'fulfilled') paymentMethods.value = paymentMethodsResult.value ?? []

  syncQuickEntryDefaults()

  const failed = [householdResult, travelResult, driveResult, driveRecentResult, weekCompareResult, monthCompareResult].filter((result) => result.status === 'rejected')
  if (failed.length) {
    errorMessage.value = '일부 요약 정보를 불러오지 못했습니다. 백엔드 연결 후 자동으로 채워집니다.'
  }
  loading.value = false
}

watch(
  userStorageId,
  () => {
    hydratePalettes()
    hydratePaymentSelection()
  },
  { immediate: true },
)

watch(selectedPaymentMethodId, persistPaymentSelection)
watch(() => quickEntry.entryType, syncQuickEntryDefaults)
watch(() => quickEntry.categoryGroupId, syncQuickEntryDefaults)
watch(layoutKey, queueGridRebuild)
watch(isEditMode, (value) => {
  if (grid) {
    grid.enableMove(value)
    grid.enableResize(false)
  }
})

onMounted(async () => {
  document.addEventListener('pointerdown', closeToolsOnOutsidePointer)
  await loadSummaries()
  await nextTick()
  initGrid()
  resizeObserver = new ResizeObserver(updateCellHeight)
  if (gridElement.value?.parentElement) {
    resizeObserver.observe(gridElement.value.parentElement)
  }
})

onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', closeToolsOnOutsidePointer)
  if (rebuildTimer) {
    window.clearTimeout(rebuildTimer)
  }
  resizeObserver?.disconnect()
  destroyGrid()
})
</script>

<template>
  <div class="main-dashboard">
    <section class="main-dashboard__header">
      <div>
        <span class="main-dashboard__eyebrow">메인 대시보드</span>
        <h2>{{ currentUser.displayName }}님의 종합 정보판</h2>
        <p>가계부, 여행, 드라이브 팔레트를 원하는 조합으로 배치합니다.</p>
      </div>
      <div class="main-dashboard__header-actions">
        <button class="button button--secondary" type="button" :disabled="loading" @click="loadSummaries">
          {{ loading ? '갱신 중' : '새로고침' }}
        </button>
      </div>
    </section>

    <div v-if="feedbackMessage" class="feedback feedback--success">{{ feedbackMessage }}</div>
    <div v-if="errorMessage" class="feedback feedback--error">{{ errorMessage }}</div>

    <section class="main-dashboard__palette-zone" :class="{ 'is-editing': isEditMode }">
      <div v-if="isEditMode" class="main-dashboard__grid-guide" aria-hidden="true">
        <span v-for="index in DASHBOARD_GRID_COLUMNS" :key="index"></span>
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
        >
          <div class="grid-stack-item-content">
            <article
              class="main-palette"
              :class="[
                { 'main-palette--editing': isEditMode },
                `main-palette--${palette.type}`,
                palette.options?.metric ? `main-palette--${palette.options.metric}` : '',
              ]"
            >
              <header class="main-palette__head">
                <strong>{{ paletteTitle(palette) }}</strong>
                <div v-if="isEditMode" class="main-palette__actions" data-no-drag="true">
                  <button type="button" @click="hidePalette(palette.id)">숨김</button>
                  <button type="button" @click="removePalette(palette.id)">삭제</button>
                </div>
              </header>

              <div
                class="main-palette__body"
                :class="{
                  'main-palette__body--has-list': (
                    (palette.type === 'travel-summary' && recentTravelPlans.length)
                    || (palette.type === 'drive-summary' && recentDriveFiles.length)
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
                      v-for="row in compareRowsFor(palette).slice(-2)"
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
                  <div v-if="recentTravelPlans.length" class="main-palette__list">
                    <div v-for="plan in recentTravelPlans" :key="plan.id" class="main-palette__list-row">
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
                  <div v-if="recentDriveFiles.length" class="main-palette__list">
                    <div v-for="file in recentDriveFiles" :key="file.id" class="main-palette__list-row">
                      <span>{{ file.fileOriginName || file.name || '-' }}</span>
                      <strong>{{ formatBytes(file.fileSize) }}</strong>
                    </div>
                  </div>
                </template>

                <template v-else-if="palette.type === 'photo-frame'">
                  <div class="main-palette__photo-frame">
                    <a
                      v-if="heroPhoto"
                      class="main-palette__photo-hero"
                      :href="heroPhoto.openUrl || heroPhoto.imageUrl"
                      target="_blank"
                      rel="noreferrer"
                      data-no-drag="true"
                    >
                      <img :src="heroPhoto.imageUrl" :alt="heroPhoto.title" loading="lazy" decoding="async" />
                      <span>{{ heroPhoto.source }}</span>
                      <strong>{{ heroPhoto.title }}</strong>
                    </a>
                    <div v-else class="main-palette__photo-empty">
                      <strong>표시할 사진이 없습니다.</strong>
                      <span>드라이브나 여행 기록에 사진을 업로드하면 액자처럼 표시됩니다.</span>
                    </div>
                    <div v-if="photoFrameItems.length > 1" class="main-palette__photo-strip">
                      <img
                        v-for="photo in photoFrameItems.slice(1, 5)"
                        :key="photo.id"
                        :src="photo.imageUrl"
                        :alt="photo.title"
                        loading="lazy"
                        decoding="async"
                      />
                    </div>
                  </div>
                </template>

                <template v-else-if="palette.type === 'drive-capacity'">
                  <div class="main-palette__capacity">
                    <div class="main-palette__single-metric">
                      <span>사용 중인 용량</span>
                      <strong>{{ formatBytes(driveCapacity.usedBytes) }}</strong>
                      <small v-if="driveCapacity.totalBytes">{{ formatBytes(driveCapacity.totalBytes) }} 중 {{ driveCapacity.percent }}%</small>
                      <small v-else>전체 용량 정보는 드라이브 설정에서 확인합니다.</small>
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
                      v-for="file in recentDriveFiles"
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
                    <p v-if="!recentDriveFiles.length" class="main-palette__empty">최근 저장한 파일이 없습니다.</p>
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
  </div>
</template>

<style scoped>
.main-dashboard {
  display: grid;
  gap: 14px;
  min-width: 0;
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
  bottom: 12px;
  display: grid;
  gap: 8px;
  grid-template-columns: repeat(9, minmax(0, 1fr));
  left: 12px;
  pointer-events: none;
  position: absolute;
  right: 12px;
  top: 12px;
  z-index: 0;
}

.main-dashboard__grid-guide span {
  background: rgba(111, 66, 193, 0.045);
  border: 1px dashed rgba(111, 66, 193, 0.18);
}

:deep(.grid-stack-item) {
  z-index: 1;
}

:deep(.grid-stack-item-content) {
  inset: 4px;
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
  grid-template-rows: minmax(0, 1fr) auto;
  height: 100%;
  min-height: 0;
}

.main-palette__photo-hero {
  background: #111827;
  color: #ffffff;
  display: block;
  min-height: 0;
  overflow: hidden;
  position: relative;
  text-decoration: none;
}

.main-palette__photo-hero img {
  display: block;
  height: 100%;
  object-fit: cover;
  width: 100%;
}

.main-palette__photo-hero span,
.main-palette__photo-hero strong {
  background: rgba(17, 24, 39, 0.72);
  left: 8px;
  max-width: calc(100% - 16px);
  overflow: hidden;
  padding: 3px 6px;
  position: absolute;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.main-palette__photo-hero span {
  bottom: 34px;
  font-size: 0.68rem;
}

.main-palette__photo-hero strong {
  bottom: 8px;
  font-size: 0.78rem;
}

.main-palette__photo-strip {
  display: grid;
  gap: 6px;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  min-height: 44px;
}

.main-palette__photo-strip img {
  aspect-ratio: 1;
  border: 1px solid #e5e7eb;
  object-fit: cover;
  width: 100%;
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
  --dash-bg: #eeeeef;
  --dash-card: #ffffff;
  --dash-ink: #10111f;
  --dash-muted: #727682;
  --dash-line: rgba(16, 17, 31, 0.08);
  --dash-lime: #d7ff43;
  --dash-lime-soft: #efffba;
  --dash-lavender: #c5ccff;
  --dash-lavender-soft: #eef0ff;
  --dash-card-radius: 14px;
  background: var(--dash-bg);
  border-radius: 24px;
  gap: 18px;
  padding: 18px;
}

.main-dashboard__header {
  background: rgba(255, 255, 255, 0.96);
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
  color: #6d72ba;
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

.main-dashboard__grid-guide {
  inset: 0;
}

.main-dashboard__grid-guide span {
  background: rgba(197, 204, 255, 0.16);
  border-color: rgba(109, 114, 186, 0.2);
}

:deep(.grid-stack-item-content) {
  inset: 5px;
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
  background: #f3f3f4;
  border: 0;
  border-radius: 8px;
  color: var(--dash-ink);
}

.main-palette__quick-buttons button:hover,
.main-palette__feature-link:hover,
.main-palette__quick-action:hover,
.main-palette__inline-action:hover {
  background: var(--dash-lime-soft);
}

.main-palette__metric,
.main-palette__single-metric,
.main-palette__feature-link,
.main-palette__quick-action,
.main-palette__photo-empty {
  background: #f5f5f6;
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
  background: var(--dash-lime);
}

.main-palette--monthIncome .main-palette__single-metric,
.main-palette--weekIncome .main-palette__single-metric,
.main-palette--travel-summary .main-palette__metric:first-child,
.main-palette--drive-summary .main-palette__metric:first-child {
  background: var(--dash-lavender);
}

.main-palette--photo-frame,
.main-palette--drive-capacity {
  background: var(--dash-lavender);
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
  border-top-color: rgba(16, 17, 31, 0.08);
}

.main-palette__bar,
.main-palette__capacity-track {
  background: rgba(16, 17, 31, 0.07);
  border-radius: 999px;
}

.main-palette__bar span,
.main-palette__capacity-track span {
  background: var(--dash-lime);
  border-radius: inherit;
}

.main-palette__photo-hero,
.main-palette__photo-strip img,
.main-palette__recent-file img,
.main-palette__file-icon {
  border: 0;
  border-radius: 10px;
}

.main-palette__photo-hero span,
.main-palette__photo-hero strong {
  background: rgba(16, 17, 31, 0.72);
  border-radius: 6px;
}

.main-palette__inline-action {
  background: rgba(255, 255, 255, 0.62);
  border: 0;
  border-radius: 8px;
}

.main-palette__feature-link span {
  color: #6d72ba;
}

.main-dashboard__floating-button {
  background: var(--dash-lime);
  border: 0;
  border-radius: 10px;
  bottom: 24px;
  box-shadow: 0 14px 28px rgba(16, 17, 31, 0.18);
  color: var(--dash-ink);
  top: auto;
  transform: none;
}

.main-dashboard__floating-button.is-active,
.main-dashboard__floating-button:hover {
  background: var(--dash-lavender);
  color: var(--dash-ink);
}

.main-dashboard__tools {
  background: rgba(255, 255, 255, 0.98);
  border: 0;
  border-radius: 16px;
  box-shadow: 0 18px 40px rgba(16, 17, 31, 0.16);
}

.main-dashboard__primary {
  background: var(--dash-lime);
  border-color: transparent;
  color: var(--dash-ink);
}

.main-dashboard__secondary,
.main-dashboard__hidden button {
  background: #f3f3f4;
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

@media (max-width: 720px) {
  .main-dashboard__header {
    align-items: stretch;
    display: grid;
  }

  .main-dashboard__palette-zone {
    padding: 8px;
  }

  .main-dashboard__grid-guide {
    left: 8px;
    right: 8px;
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
</style>
