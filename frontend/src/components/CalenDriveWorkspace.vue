<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import CalenDriveProfileModal from './CalenDriveProfileModal.vue'
import {
  abortDriveUpload,
  cancelAllDriveShares,
  cancelDriveShare,
  clearDriveTrash,
  completeDriveUpload,
  createDriveFolder,
  deleteDriveItem,
  fetchDriveAdminDashboard,
  fetchDriveHomeSummary,
  fetchDrivePage,
  fetchDriveProfileSettings,
  fetchDriveRecentFiles,
  fetchDriveShareInfo,
  fetchDriveSharedReceived,
  fetchDriveSharedSent,
  fetchDriveStorageAnalytics,
  fetchDriveTrashFiles,
  initializeDriveUpload,
  moveDriveItemToTrash,
  moveDriveItems,
  renameDriveItem,
  restoreDriveItem,
  restoreDriveItems,
  saveSharedDriveFile,
  searchDriveShareRecipients,
  shareDriveFiles,
  updateDriveStorageCapacity,
  updateDriveUserStatus,
  uploadDriveFileWithProgress,
} from '../lib/api'

const props = defineProps({
  currentUser: {
    type: Object,
    required: true,
  },
})

function createEmptyDrivePage() {
  return {
    fileList: [],
    breadcrumbs: [],
    availableExtensions: [],
    totalPage: 0,
    totalCount: 0,
    currentPage: 0,
    currentSize: 20,
  }
}

const sidebarCollapsed = ref(false)
const activeTab = ref('home')
const sharedTab = ref('received')
const viewMode = ref('icon')
const profileModalOpen = ref(false)
const topSearch = ref('')
const loading = ref(false)
const feedback = ref('')
const errorMessage = ref('')
const selectedIds = ref([])
const moveTargetId = ref('')

const homeSummary = ref(null)
const drivePage = ref(createEmptyDrivePage())
const recentFiles = ref([])
const trashFiles = ref([])
const sharedReceived = ref([])
const sharedSent = ref([])
const adminDashboard = ref(null)
const storageAnalytics = ref(null)
const profileSettings = ref(null)

const pageFilters = reactive({
  parentId: null,
  page: 0,
  size: 20,
  sortOption: 'recent',
  searchQuery: '',
  extensionFilter: '',
})

const uploadProgress = reactive({
  open: false,
  title: '파일을 업로드하는 중입니다.',
  description: '선택한 파일을 CalenDrive 저장소로 전송하고 있습니다.',
  current: 0,
  total: 0,
  percent: 0,
  fileName: '',
})

const shareDialog = reactive({
  open: false,
  targets: [],
  existingRecipients: [],
  searchQuery: '',
  searchResults: [],
  selectedRecipientLoginId: '',
  loading: false,
  saving: false,
})

const adminCapacityForm = reactive({
  providerCapacityGb: '100',
})

const navigationItems = computed(() => {
  const items = [
    { key: 'home', label: '홈', meta: '요약', icon: '홈' },
    { key: 'drive', label: '내 드라이브', meta: '파일', icon: '드' },
    { key: 'shared', label: '공유 파일', meta: '협업', icon: '공' },
    { key: 'recent', label: '최근 파일', meta: '최근', icon: '최' },
    { key: 'trash', label: '휴지통', meta: '보관', icon: '휴' },
  ]
  if (props.currentUser?.admin) {
    items.push({ key: 'admin', label: '관리자 페이지', meta: '운영', icon: '관' })
  }
  return items
})

const normalizedNavigationItems = computed(() => {
  const items = [
    { key: 'home', label: '홈', meta: '요약', icon: '홈' },
    { key: 'drive', label: '내 드라이브', meta: '파일', icon: '드' },
    { key: 'shared', label: '공유 파일', meta: '협업', icon: '공' },
    { key: 'recent', label: '최근 파일', meta: '최근', icon: '최' },
    { key: 'trash', label: '휴지통', meta: '보관', icon: '휴' },
  ]
  if (props.currentUser?.admin) {
    items.push({ key: 'admin', label: '관리자 페이지', meta: '운영', icon: '관' })
  }
  return items
})

const driveFiles = computed(() => drivePage.value?.fileList || [])
const breadcrumbs = computed(() => drivePage.value?.breadcrumbs || [])
const availableExtensions = computed(() => drivePage.value?.availableExtensions || [])
const homeRecentFiles = computed(() => homeSummary.value?.recentFiles || [])
const adminSummary = computed(() => adminDashboard.value?.summary || null)
const adminUsers = computed(() => adminDashboard.value?.users || [])
const currentProfileName = computed(() => profileSettings.value?.displayName || props.currentUser.displayName)
const currentProfileImage = computed(() => profileSettings.value?.profileImageUrl || '')
const currentProfileRole = computed(() => profileSettings.value?.role || (props.currentUser.admin ? 'ADMIN' : 'USER'))

const browserItems = computed(() => {
  if (activeTab.value === 'drive') {
    return driveFiles.value
  }
  if (activeTab.value === 'recent') {
    return recentFiles.value
  }
  if (activeTab.value === 'trash') {
    return trashFiles.value
  }
  if (activeTab.value === 'shared' && sharedTab.value === 'received') {
    return sharedReceived.value
  }
  return []
})

const selectableItems = computed(() => {
  if (activeTab.value === 'drive' || activeTab.value === 'recent' || activeTab.value === 'trash') {
    return browserItems.value
  }
  if (activeTab.value === 'shared' && sharedTab.value === 'received') {
    return sharedReceived.value
  }
  return []
})

const canShowBrowser = computed(() => {
  return activeTab.value === 'drive'
    || activeTab.value === 'recent'
    || activeTab.value === 'trash'
    || (activeTab.value === 'shared' && sharedTab.value === 'received')
})

const canShowSelectionBar = computed(() => selectableItems.value.length > 0)

const selectedItems = computed(() => {
  const currentMap = new Map(selectableItems.value.map((item) => [getSelectableId(item), item]))
  return selectedIds.value.map((id) => currentMap.get(id)).filter(Boolean)
})

const allCurrentSelected = computed(() => {
  return selectableItems.value.length > 0
    && selectableItems.value.every((item) => selectedIds.value.includes(getSelectableId(item)))
})

const selectedShareableItems = computed(() => selectedItems.value.filter((item) => !isFolder(item)))

const moveDestinationOptions = computed(() => {
  if (activeTab.value !== 'drive' && activeTab.value !== 'recent') {
    return []
  }

  const options = new Map()
  options.set('', { value: '', label: '홈' })

  for (const crumb of breadcrumbs.value) {
    options.set(String(crumb.id ?? ''), {
      value: String(crumb.id ?? ''),
      label: crumb.fileOriginName || '상위 폴더',
    })
  }

  for (const item of driveFiles.value) {
    if (!isFolder(item)) {
      continue
    }
    const itemId = getSelectableId(item)
    if (selectedIds.value.includes(itemId)) {
      continue
    }
    options.set(String(item.id), {
      value: String(item.id),
      label: item.fileOriginName,
    })
  }

  return Array.from(options.values())
})

const driveSummaryCards = computed(() => {
  const totalVisible = browserItems.value.length
  const totalBytes = browserItems.value.reduce((sum, item) => sum + Number(item.fileSize || 0), 0)

  return [
    {
      label: '총 항목',
      value: activeTab.value === 'home'
        ? Number(homeSummary.value?.driveItemCount || 0)
        : Number(drivePage.value?.totalCount || totalVisible),
      meta: activeTab.value === 'home'
        ? `파일 ${homeSummary.value?.fileCount || 0} · 폴더 ${homeSummary.value?.folderCount || 0}`
        : `현재 페이지 ${totalVisible}개`,
    },
    {
      label: '사용 용량',
      value: formatBytes(activeTab.value === 'home' ? homeSummary.value?.usedBytes || 0 : totalBytes),
      meta: activeTab.value === 'home'
        ? `휴지통 ${homeSummary.value?.trashCount || 0}개`
        : '현재 화면 기준 합계',
    },
    {
      label: '공유 상태',
      value: activeTab.value === 'shared'
        ? `${sharedReceived.value.length + sharedSent.value.length}건`
        : `${homeSummary.value?.sharedCount || 0}건`,
      meta: activeTab.value === 'shared'
        ? `받은 공유 ${sharedReceived.value.length} · 보낸 공유 ${sharedSent.value.length}`
        : '공유된 파일 수',
    },
  ]
})

const currentLocationLabel = computed(() => {
  if (activeTab.value !== 'drive') {
    if (activeTab.value === 'recent') {
      return '최근 파일'
    }
    if (activeTab.value === 'trash') {
      return '휴지통'
    }
    if (activeTab.value === 'shared') {
      return sharedTab.value === 'received' ? '받은 공유' : '보낸 공유'
    }
    if (activeTab.value === 'admin') {
      return '관리자 페이지'
    }
    return '홈'
  }

  if (!breadcrumbs.value.length) {
    return '홈'
  }
  return breadcrumbs.value.map((crumb) => crumb.fileOriginName).join(' / ')
})

const storageMeter = computed(() => {
  const usedBytes = Number(
    props.currentUser?.admin
      ? storageAnalytics.value?.providerUsedBytes || 0
      : profileSettings.value?.driveUsedBytes || homeSummary.value?.usedBytes || 0,
  )
  const totalBytes = Number(
    props.currentUser?.admin
      ? storageAnalytics.value?.providerCapacityBytes || 0
      : 0,
  )
  const percent = totalBytes > 0 ? Math.min(100, Math.round((usedBytes / totalBytes) * 100)) : 0
  return {
    usedBytes,
    totalBytes,
    percent,
  }
})

const shareDialogTitle = computed(() => {
  if (shareDialog.targets.length === 1) {
    return shareDialog.targets[0]?.fileOriginName || '선택한 파일'
  }
  return `${shareDialog.targets.length}개 항목`
})

const normalizedDriveSummaryCards = computed(() => {
  const totalVisible = browserItems.value.length
  const totalBytes = browserItems.value.reduce((sum, item) => sum + Number(item.fileSize || 0), 0)

  return [
    {
      label: '총 항목',
      value: activeTab.value === 'home'
        ? Number(homeSummary.value?.driveItemCount || 0)
        : Number(drivePage.value?.totalCount || totalVisible),
      meta: activeTab.value === 'home'
        ? `파일 ${homeSummary.value?.fileCount || 0}개 · 폴더 ${homeSummary.value?.folderCount || 0}개`
        : `현재 페이지 ${totalVisible}개`,
    },
    {
      label: '사용 용량',
      value: formatBytes(activeTab.value === 'home' ? homeSummary.value?.usedBytes || 0 : totalBytes),
      meta: activeTab.value === 'home'
        ? `휴지통 ${homeSummary.value?.trashCount || 0}개`
        : '현재 화면 기준 용량 합계',
    },
    {
      label: '공유 상태',
      value: activeTab.value === 'shared'
        ? `${sharedReceived.value.length + sharedSent.value.length}건`
        : `${homeSummary.value?.sharedCount || 0}건`,
      meta: activeTab.value === 'shared'
        ? `받은 공유 ${sharedReceived.value.length}건 · 보낸 공유 ${sharedSent.value.length}건`
        : '공유된 파일 수',
    },
  ]
})

const normalizedCurrentLocationLabel = computed(() => {
  if (activeTab.value !== 'drive') {
    if (activeTab.value === 'recent') {
      return '최근 파일'
    }
    if (activeTab.value === 'trash') {
      return '휴지통'
    }
    if (activeTab.value === 'shared') {
      return sharedTab.value === 'received' ? '받은 공유' : '보낸 공유'
    }
    if (activeTab.value === 'admin') {
      return '관리자 페이지'
    }
    return '홈'
  }

  if (!breadcrumbs.value.length) {
    return '홈'
  }

  return breadcrumbs.value.map((crumb) => crumb.fileOriginName).join(' / ')
})

const normalizedShareDialogTitle = computed(() => {
  if (shareDialog.targets.length === 1) {
    return shareDialog.targets[0]?.fileOriginName || '선택한 파일'
  }
  return `${shareDialog.targets.length}개 항목`
})

function setMessages(message = '', error = '') {
  feedback.value = message
  errorMessage.value = error
}

function formatBytes(bytes) {
  const value = Number(bytes || 0)
  if (!Number.isFinite(value) || value <= 0) {
    return '0 B'
  }

  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let amount = value
  let unitIndex = 0

  while (amount >= 1024 && unitIndex < units.length - 1) {
    amount /= 1024
    unitIndex += 1
  }

  return `${amount >= 10 || unitIndex === 0 ? amount.toFixed(0) : amount.toFixed(1)} ${units[unitIndex]}`
}

function formatTimestamp(value) {
  if (!value) {
    return '-'
  }

  const normalized = new Date(value)
  if (Number.isNaN(normalized.getTime())) {
    return String(value)
  }

  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(normalized)
}

function resolveNodeType(item) {
  return String(item?.nodeType || '').toUpperCase()
}

function isFolder(item) {
  return resolveNodeType(item) === 'FOLDER'
}

function isImageFile(item) {
  const extension = String(item?.fileFormat || '').toLowerCase()
  return ['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp', 'svg'].includes(extension)
}

function isVideoFile(item) {
  const extension = String(item?.fileFormat || '').toLowerCase()
  return ['mp4', 'mov', 'webm', 'avi', 'mkv'].includes(extension)
}

function buildOwnedDownloadPath(item) {
  return item?.downloadUrl || `/api/file/${item.id}/download`
}

function buildOwnedThumbnailPath(item) {
  return item?.thumbnailUrl || `/api/file/${item.id}/thumbnail`
}

function buildSharedDownloadPath(item) {
  return item?.downloadUrl || `/api/file/share/shared/${item.fileId}/download`
}

function buildSharedThumbnailPath(item) {
  return item?.thumbnailUrl || `/api/file/share/shared/${item.fileId}/thumbnail`
}

function getSelectableId(item) {
  if (activeTab.value === 'shared' && sharedTab.value === 'received') {
    return String(item.fileId)
  }
  return String(item.id)
}

function itemPreviewType(item) {
  if (isFolder(item)) {
    return 'folder'
  }
  if (isImageFile(item)) {
    return 'image'
  }
  if (isVideoFile(item)) {
    return 'video'
  }
  return 'file'
}

function itemTypeLabel(item) {
  if (isFolder(item)) {
    return '폴더'
  }
  return String(item.fileFormat || '파일').toUpperCase()
}

function clearSelection() {
  selectedIds.value = []
  moveTargetId.value = ''
}

function toggleSelection(item) {
  const id = getSelectableId(item)
  if (selectedIds.value.includes(id)) {
    selectedIds.value = selectedIds.value.filter((value) => value !== id)
    return
  }
  selectedIds.value = [...selectedIds.value, id]
}

function toggleSelectAllCurrent() {
  if (allCurrentSelected.value) {
    clearSelection()
    return
  }
  selectedIds.value = selectableItems.value.map((item) => getSelectableId(item))
}

function openProfileModal() {
  profileModalOpen.value = true
}

function toggleSidebar() {
  sidebarCollapsed.value = !sidebarCollapsed.value
}

function updateAdminCapacityForm() {
  const bytes = Number(storageAnalytics.value?.providerCapacityBytes || 0)
  adminCapacityForm.providerCapacityGb = bytes > 0 ? String(Math.round(bytes / 1024 / 1024 / 1024)) : '100'
}

async function runLoader(callback) {
  loading.value = true
  setMessages()
  try {
    await callback()
  } catch (error) {
    setMessages('', error.message)
  } finally {
    loading.value = false
  }
}

async function loadHomeSummary() {
  homeSummary.value = await fetchDriveHomeSummary()
}

async function loadDrivePage() {
  drivePage.value = await fetchDrivePage({
    parentId: pageFilters.parentId,
    page: pageFilters.page,
    size: pageFilters.size,
    sortOption: pageFilters.sortOption,
    searchQuery: pageFilters.searchQuery,
    extensionFilter: pageFilters.extensionFilter,
    statusFilter: 'all',
  })
}

async function loadRecentFiles() {
  recentFiles.value = await fetchDriveRecentFiles()
}

async function loadTrashFiles() {
  trashFiles.value = await fetchDriveTrashFiles()
}

async function loadSharedData() {
  const [received, sent] = await Promise.all([
    fetchDriveSharedReceived(),
    fetchDriveSharedSent(),
  ])
  sharedReceived.value = received
  sharedSent.value = sent
}

async function loadAdminData() {
  const [dashboard, analytics] = await Promise.all([
    fetchDriveAdminDashboard(),
    fetchDriveStorageAnalytics(),
  ])
  adminDashboard.value = dashboard
  storageAnalytics.value = analytics
  updateAdminCapacityForm()
}

async function loadProfileSettings() {
  profileSettings.value = await fetchDriveProfileSettings()
}

async function loadActiveTab() {
  await runLoader(async () => {
    if (!profileSettings.value) {
      await loadProfileSettings()
    }

    if (activeTab.value === 'home') {
      await loadHomeSummary()
      return
    }
    if (activeTab.value === 'drive') {
      await Promise.all([loadDrivePage(), loadHomeSummary()])
      return
    }
    if (activeTab.value === 'recent') {
      await loadRecentFiles()
      return
    }
    if (activeTab.value === 'shared') {
      await loadSharedData()
      return
    }
    if (activeTab.value === 'trash') {
      await loadTrashFiles()
      return
    }
    if (activeTab.value === 'admin' && props.currentUser?.admin) {
      await loadAdminData()
    }
  })
}

async function refreshVisibleData() {
  if (activeTab.value === 'home') {
    await loadHomeSummary()
    return
  }
  if (activeTab.value === 'drive') {
    await Promise.all([loadDrivePage(), loadHomeSummary()])
    return
  }
  if (activeTab.value === 'recent') {
    await loadRecentFiles()
    return
  }
  if (activeTab.value === 'shared') {
    await loadSharedData()
    return
  }
  if (activeTab.value === 'trash') {
    await loadTrashFiles()
    return
  }
  if (activeTab.value === 'admin' && props.currentUser?.admin) {
    await loadAdminData()
  }
}

function selectTab(tab) {
  activeTab.value = tab
}

function applySearch() {
  pageFilters.page = 0
  pageFilters.searchQuery = topSearch.value.trim()
  if (activeTab.value !== 'drive') {
    activeTab.value = 'drive'
    return
  }
  loadActiveTab()
}

function resetDriveFilters() {
  topSearch.value = ''
  pageFilters.searchQuery = ''
  pageFilters.extensionFilter = ''
  pageFilters.sortOption = 'recent'
  pageFilters.page = 0
  pageFilters.parentId = null
  if (activeTab.value !== 'drive') {
    activeTab.value = 'drive'
    return
  }
  loadActiveTab()
}

function openFolder(item) {
  if (!isFolder(item)) {
    return
  }
  pageFilters.parentId = item.id
  pageFilters.page = 0
  activeTab.value = 'drive'
}

function navigateToBreadcrumb(crumbId) {
  pageFilters.parentId = crumbId ?? null
  pageFilters.page = 0
  activeTab.value = 'drive'
}

function openOwnedItem(item) {
  if (isFolder(item)) {
    openFolder(item)
    return
  }
  window.open(buildOwnedDownloadPath(item), '_blank', 'noopener')
}

function openSharedItem(item) {
  window.open(buildSharedDownloadPath(item), '_blank', 'noopener')
}

async function promptCreateFolder() {
  const folderName = window.prompt('새 폴더 이름을 입력해주세요.', '새 폴더')
  if (!folderName) {
    return
  }

  try {
    await createDriveFolder({
      folderName,
      parentId: activeTab.value === 'drive' ? pageFilters.parentId : null,
    })
    setMessages('폴더를 만들었습니다.')
    activeTab.value = 'drive'
    await Promise.all([loadDrivePage(), loadHomeSummary()])
  } catch (error) {
    setMessages('', error.message)
  }
}

async function promptRename(item) {
  const nextName = window.prompt('새 이름을 입력해주세요.', item.fileOriginName)
  if (!nextName || nextName === item.fileOriginName) {
    return
  }

  try {
    await renameDriveItem(item.id, nextName)
    setMessages('이름을 변경했습니다.')
    await refreshVisibleData()
  } catch (error) {
    setMessages('', error.message)
  }
}

async function moveItemToTrash(item) {
  if (!window.confirm(`"${item.fileOriginName}" 항목을 휴지통으로 이동할까요?`)) {
    return
  }

  try {
    await moveDriveItemToTrash(item.id)
    setMessages('휴지통으로 이동했습니다.')
    await Promise.all([loadDrivePage(), loadHomeSummary(), loadRecentFiles(), loadTrashFiles()])
    clearSelection()
  } catch (error) {
    setMessages('', error.message)
  }
}

async function moveSelectedToTrash() {
  if (!selectedItems.value.length) {
    return
  }
  if (!window.confirm(`선택한 ${selectedItems.value.length}개 항목을 휴지통으로 이동할까요?`)) {
    return
  }

  try {
    await Promise.all(selectedItems.value.map((item) => moveDriveItemToTrash(item.id)))
    setMessages('선택한 항목을 휴지통으로 이동했습니다.')
    await Promise.all([loadDrivePage(), loadHomeSummary(), loadRecentFiles(), loadTrashFiles()])
    clearSelection()
  } catch (error) {
    setMessages('', error.message)
  }
}

async function restoreTrashItem(item) {
  try {
    await restoreDriveItem(item.id)
    setMessages('휴지통에서 복구했습니다.')
    await Promise.all([loadTrashFiles(), loadDrivePage(), loadHomeSummary()])
    clearSelection()
  } catch (error) {
    setMessages('', error.message)
  }
}

async function restoreSelectedFromTrash() {
  if (!selectedItems.value.length) {
    return
  }

  try {
    await restoreDriveItems(selectedItems.value.map((item) => item.id))
    setMessages('선택한 휴지통 항목을 복구했습니다.')
    await Promise.all([loadTrashFiles(), loadDrivePage(), loadHomeSummary()])
    clearSelection()
  } catch (error) {
    setMessages('', error.message)
  }
}

async function deleteItemPermanently(item) {
  if (!window.confirm(`"${item.fileOriginName}" 항목을 완전히 삭제할까요?`)) {
    return
  }

  try {
    await deleteDriveItem(item.id)
    setMessages('항목을 완전히 삭제했습니다.')
    await Promise.all([loadTrashFiles(), loadDrivePage(), loadHomeSummary()])
    clearSelection()
  } catch (error) {
    setMessages('', error.message)
  }
}

async function deleteSelectedPermanently() {
  if (!selectedItems.value.length) {
    return
  }
  if (!window.confirm(`선택한 ${selectedItems.value.length}개 항목을 완전히 삭제할까요?`)) {
    return
  }

  try {
    await Promise.all(selectedItems.value.map((item) => deleteDriveItem(item.id)))
    setMessages('선택한 항목을 완전히 삭제했습니다.')
    await Promise.all([loadTrashFiles(), loadDrivePage(), loadHomeSummary()])
    clearSelection()
  } catch (error) {
    setMessages('', error.message)
  }
}

async function handleClearTrash() {
  if (!window.confirm('휴지통의 모든 항목을 완전히 삭제할까요?')) {
    return
  }

  try {
    await clearDriveTrash()
    setMessages('휴지통을 비웠습니다.')
    await Promise.all([loadTrashFiles(), loadDrivePage(), loadHomeSummary()])
    clearSelection()
  } catch (error) {
    setMessages('', error.message)
  }
}

async function moveSelectedItemsToFolder() {
  if (!selectedItems.value.length) {
    setMessages('', '이동할 항목을 먼저 선택해주세요.')
    return
  }

  const targetParentId = moveTargetId.value === '' ? null : Number(moveTargetId.value)
  try {
    await moveDriveItems(selectedItems.value.map((item) => item.id), targetParentId)
    setMessages('선택한 항목을 이동했습니다.')
    await Promise.all([loadDrivePage(), loadHomeSummary()])
    clearSelection()
  } catch (error) {
    setMessages('', error.message)
  }
}

async function handleFilesSelected(event) {
  const files = Array.from(event.target.files || [])
  if (!files.length) {
    return
  }

  let activeUploadMeta = null
  uploadProgress.open = true
  uploadProgress.current = 0
  uploadProgress.total = files.length
  uploadProgress.percent = 0
  uploadProgress.fileName = ''
  setMessages()

  try {
    const prepared = await initializeDriveUpload(files, activeTab.value === 'drive' ? pageFilters.parentId : null)
    if (!Array.isArray(prepared) || prepared.length !== files.length) {
      throw new Error('업로드 준비 응답이 올바르지 않습니다.')
    }

    for (let index = 0; index < files.length; index += 1) {
      const file = files[index]
      const target = prepared[index]
      activeUploadMeta = target
      uploadProgress.current = index + 1
      uploadProgress.percent = 0
      uploadProgress.fileName = file.name

      await uploadDriveFileWithProgress(target, file, (progress) => {
        uploadProgress.percent = progress.percent ?? 0
      })

      await completeDriveUpload({
        fileOriginName: target.fileOriginName,
        fileFormat: target.fileFormat,
        fileSize: target.fileSize,
        finalObjectKey: target.finalObjectKey,
        chunkObjectKeys: [],
        parentId: target.parentId ?? pageFilters.parentId,
        relativePath: target.relativePath || file.webkitRelativePath || '',
        lastModified: target.lastModified ?? file.lastModified ?? null,
      })
    }

    setMessages(`${files.length}개 파일을 업로드했습니다.`)
    activeTab.value = 'drive'
    await Promise.all([loadDrivePage(), loadHomeSummary(), loadRecentFiles()])
  } catch (error) {
    if (activeUploadMeta?.finalObjectKey) {
      try {
        await abortDriveUpload({
          finalObjectKey: activeUploadMeta.finalObjectKey,
          chunkObjectKeys: activeUploadMeta.objectKey ? [activeUploadMeta.objectKey] : [],
        })
      } catch {
        // keep original upload error
      }
    }
    setMessages('', error.message)
  } finally {
    uploadProgress.open = false
    uploadProgress.percent = 0
    uploadProgress.fileName = ''
    event.target.value = ''
  }
}

async function openShareDialog(targets) {
  const normalizedTargets = (targets || []).filter((item) => item && !isFolder(item))
  if (!normalizedTargets.length) {
    setMessages('', '공유할 파일을 먼저 선택해주세요.')
    return
  }

  shareDialog.open = true
  shareDialog.targets = normalizedTargets
  shareDialog.existingRecipients = []
  shareDialog.searchQuery = ''
  shareDialog.searchResults = []
  shareDialog.selectedRecipientLoginId = ''
  shareDialog.loading = true
  shareDialog.saving = false
  setMessages()

  try {
    if (normalizedTargets.length === 1) {
      shareDialog.existingRecipients = await fetchDriveShareInfo(normalizedTargets[0].id)
    }
  } catch (error) {
    setMessages('', error.message)
  } finally {
    shareDialog.loading = false
  }
}

function closeShareDialog() {
  shareDialog.open = false
  shareDialog.targets = []
  shareDialog.existingRecipients = []
  shareDialog.searchQuery = ''
  shareDialog.searchResults = []
  shareDialog.selectedRecipientLoginId = ''
  shareDialog.loading = false
  shareDialog.saving = false
}

async function searchRecipients() {
  const query = shareDialog.searchQuery.trim()
  if (!query) {
    shareDialog.searchResults = []
    return
  }

  shareDialog.loading = true
  try {
    shareDialog.searchResults = await searchDriveShareRecipients(query)
  } catch (error) {
    setMessages('', error.message)
  } finally {
    shareDialog.loading = false
  }
}

async function submitShare() {
  const fileIds = shareDialog.targets.map((item) => item.id).filter(Boolean)
  if (!fileIds.length || !shareDialog.selectedRecipientLoginId) {
    setMessages('', '공유 대상 사용자와 파일을 모두 선택해주세요.')
    return
  }

  shareDialog.saving = true
  try {
    await shareDriveFiles(fileIds, shareDialog.selectedRecipientLoginId)
    setMessages('파일 공유를 완료했습니다.')
    if (shareDialog.targets.length === 1) {
      shareDialog.existingRecipients = await fetchDriveShareInfo(shareDialog.targets[0].id)
      shareDialog.searchQuery = ''
      shareDialog.searchResults = []
      shareDialog.selectedRecipientLoginId = ''
    } else {
      closeShareDialog()
    }
    await Promise.all([loadSharedData(), loadHomeSummary()])
  } catch (error) {
    setMessages('', error.message)
  } finally {
    shareDialog.saving = false
  }
}

async function cancelShareForRecipient(recipientLoginId) {
  if (!shareDialog.targets.length) {
    return
  }

  try {
    await cancelDriveShare([shareDialog.targets[0].id], recipientLoginId)
    shareDialog.existingRecipients = await fetchDriveShareInfo(shareDialog.targets[0].id)
    setMessages('공유를 취소했습니다.')
    await loadSharedData()
  } catch (error) {
    setMessages('', error.message)
  }
}

async function cancelAllSharesForFile(item) {
  const fileId = item.id || item.fileId
  const fileName = item.fileOriginName || '선택한 파일'
  if (!window.confirm(`"${fileName}" 파일의 모든 공유를 취소할까요?`)) {
    return
  }

  try {
    await cancelAllDriveShares([fileId])
    setMessages('모든 공유를 취소했습니다.')
    if (shareDialog.targets.length === 1 && shareDialog.targets[0]?.id === item.id) {
      shareDialog.existingRecipients = []
    }
    await loadSharedData()
    if (activeTab.value === 'drive') {
      await loadDrivePage()
    }
  } catch (error) {
    setMessages('', error.message)
  }
}

async function handleSaveSharedFile(item) {
  try {
    await saveSharedDriveFile(item.fileId, pageFilters.parentId)
    setMessages('공유 파일을 내 드라이브로 저장했습니다.')
    await Promise.all([loadSharedData(), loadDrivePage(), loadHomeSummary()])
    clearSelection()
  } catch (error) {
    setMessages('', error.message)
  }
}

async function saveSelectedSharedFiles() {
  if (!selectedItems.value.length) {
    return
  }

  try {
    await Promise.all(selectedItems.value.map((item) => saveSharedDriveFile(item.fileId, pageFilters.parentId)))
    setMessages('선택한 공유 파일을 내 드라이브로 저장했습니다.')
    await Promise.all([loadSharedData(), loadDrivePage(), loadHomeSummary()])
    clearSelection()
  } catch (error) {
    setMessages('', error.message)
  }
}

async function toggleUserActive(user) {
  try {
    await updateDriveUserStatus(user.id, !user.active)
    setMessages(`${user.displayName} 사용자의 상태를 변경했습니다.`)
    await loadAdminData()
  } catch (error) {
    setMessages('', error.message)
  }
}

async function saveProviderCapacity() {
  const capacityGb = Number(adminCapacityForm.providerCapacityGb)
  if (!Number.isFinite(capacityGb) || capacityGb <= 0) {
    setMessages('', '저장소 총 용량은 1GB 이상이어야 합니다.')
    return
  }

  try {
    await updateDriveStorageCapacity(capacityGb * 1024 * 1024 * 1024)
    setMessages('저장소 총 용량을 변경했습니다.')
    await loadAdminData()
  } catch (error) {
    setMessages('', error.message)
  }
}

function handleProfileUpdated(nextSettings) {
  if (nextSettings) {
    profileSettings.value = nextSettings
  }
  loadHomeSummary()
}

watch(
  () => activeTab.value,
  () => {
    clearSelection()
    loadActiveTab()
  },
)

watch(
  () => sharedTab.value,
  () => {
    clearSelection()
  },
)

watch(
  () => [
    pageFilters.parentId,
    pageFilters.page,
    pageFilters.size,
    pageFilters.sortOption,
    pageFilters.extensionFilter,
    pageFilters.searchQuery,
  ],
  () => {
    if (activeTab.value === 'drive') {
      clearSelection()
      loadActiveTab()
    }
  },
)

onMounted(() => {
  loadActiveTab()
})
</script>

<template>
  <div class="drive-layout">
    <aside class="panel drive-sidebar" :class="{ 'drive-sidebar--collapsed': sidebarCollapsed }">
      <div class="drive-sidebar__brand">
        <button class="drive-sidebar__toggle" type="button" @click="toggleSidebar">
          {{ sidebarCollapsed ? '›' : '‹' }}
        </button>
        <div v-if="!sidebarCollapsed" class="drive-sidebar__brand-copy">
          <span class="drive-sidebar__brand-icon">▣</span>
          <strong>CalenDrive</strong>
        </div>
      </div>

      <div v-if="!sidebarCollapsed" class="drive-sidebar__actions">
        <label class="button button--primary drive-upload-button">
          업로드
          <input type="file" multiple @change="handleFilesSelected" />
        </label>
        <button class="button button--ghost" type="button" @click="promptCreateFolder">폴더 만들기</button>
      </div>

      <nav class="drive-sidebar__nav">
        <button
          v-for="item in normalizedNavigationItems"
          :key="item.key"
          class="drive-sidebar__nav-item"
          :class="{ 'drive-sidebar__nav-item--active': activeTab === item.key }"
          type="button"
          @click="selectTab(item.key)"
        >
          <span class="drive-sidebar__nav-icon">{{ item.icon }}</span>
          <span v-if="!sidebarCollapsed" class="drive-sidebar__nav-copy">
            <strong>{{ item.label }}</strong>
            <small>{{ item.meta }}</small>
          </span>
        </button>
      </nav>

      <div v-if="!sidebarCollapsed" class="drive-sidebar__storage">
        <div class="drive-sidebar__storage-head">
          <strong>저장용량</strong>
          <span v-if="storageMeter.totalBytes">{{ storageMeter.percent }}%</span>
        </div>
        <div class="drive-sidebar__meter">
          <span :style="{ width: `${storageMeter.totalBytes ? storageMeter.percent : 18}%` }"></span>
        </div>
        <small v-if="storageMeter.totalBytes">
          {{ formatBytes(storageMeter.usedBytes) }} / {{ formatBytes(storageMeter.totalBytes) }}
        </small>
        <small v-else>{{ formatBytes(storageMeter.usedBytes) }} 사용 중</small>
      </div>
    </aside>

    <div class="workspace-stack drive-main">
      <section class="panel drive-topbar">
        <div class="drive-topbar__search">
          <input
            v-model="topSearch"
            class="drive-topbar__search-input"
            type="search"
            placeholder="파일명, 확장자, 공유자 이메일을 검색하세요"
            @keyup.enter="applySearch"
          />
          <button class="button button--ghost" type="button" @click="applySearch">검색</button>
        </div>

        <div class="drive-topbar__actions">
          <button class="button button--ghost" type="button" @click="loadActiveTab">새로고침</button>
          <div class="drive-shell__user">
            <img
              v-if="currentProfileImage"
              :src="currentProfileImage"
              :alt="currentProfileName"
              class="drive-shell__avatar"
            />
            <div v-else class="drive-shell__avatar drive-shell__avatar--placeholder">
              {{ currentProfileName.slice(0, 1) }}
            </div>
            <div>
              <strong>{{ currentProfileName }}</strong>
              <small>{{ currentProfileRole === 'ADMIN' ? '관리자' : '사용자' }}</small>
            </div>
          </div>
          <button class="button button--primary" type="button" @click="openProfileModal">프로필 설정</button>
        </div>
      </section>

      <div v-if="feedback" class="feedback feedback--success">{{ feedback }}</div>
      <div v-if="errorMessage" class="feedback feedback--error">{{ errorMessage }}</div>

      <section class="panel drive-dashboard">
        <div class="drive-dashboard__summary">
          <article v-for="card in normalizedDriveSummaryCards" :key="card.label" class="drive-dashboard__card">
            <span>{{ card.label }}</span>
            <strong>{{ card.value }}</strong>
            <small>{{ card.meta }}</small>
          </article>
          <article class="drive-dashboard__card">
            <span>현재 위치</span>
            <strong>{{ normalizedCurrentLocationLabel }}</strong>
            <small v-if="activeTab === 'drive'">현재 폴더 기준으로 업로드와 정리가 이루어집니다.</small>
            <small v-else>선택한 탭 기준으로 파일 상태를 빠르게 정리합니다.</small>
          </article>
        </div>

        <div class="drive-dashboard__controls">
          <label class="field">
            <span class="field__label">정렬</span>
            <select v-model="pageFilters.sortOption" :disabled="activeTab !== 'drive'">
              <option value="recent">최근 수정순</option>
              <option value="nameAsc">이름 오름차순</option>
              <option value="nameDesc">이름 내림차순</option>
              <option value="sizeDesc">용량 큰 순</option>
              <option value="sizeAsc">용량 작은 순</option>
            </select>
          </label>
          <label class="field">
            <span class="field__label">배치</span>
            <select v-model.number="pageFilters.size" :disabled="activeTab !== 'drive'">
              <option :value="12">12개</option>
              <option :value="20">20개</option>
              <option :value="40">40개</option>
              <option :value="80">80개</option>
            </select>
          </label>
          <label class="field">
            <span class="field__label">확장자</span>
            <select v-model="pageFilters.extensionFilter" :disabled="activeTab !== 'drive'">
              <option value="">전체</option>
              <option v-for="extension in availableExtensions" :key="extension" :value="extension">
                {{ extension.toUpperCase() }}
              </option>
            </select>
          </label>
          <div class="drive-view-toggle">
            <button class="button" :class="{ 'button--primary': viewMode === 'list' }" type="button" @click="viewMode = 'list'">리스트</button>
            <button class="button" :class="{ 'button--primary': viewMode === 'card' }" type="button" @click="viewMode = 'card'">카드</button>
            <button class="button" :class="{ 'button--primary': viewMode === 'icon' }" type="button" @click="viewMode = 'icon'">아이콘</button>
          </div>
        </div>
      </section>

      <section v-if="loading" class="panel">
        <p class="panel__empty">CalenDrive 데이터를 불러오는 중입니다.</p>
      </section>

      <template v-else>
        <section v-if="activeTab === 'home'" class="workspace-stack">
          <section class="panel">
            <div class="panel__header">
              <div>
                <h3>빠른 시작</h3>
                <p>업로드, 최근 작업, 공유 파일, 휴지통 정리를 홈에서 바로 이어서 진행할 수 있습니다.</p>
              </div>
              <div class="drive-toolbar__actions">
                <button class="button button--ghost" type="button" @click="selectTab('drive')">드라이브 열기</button>
                <button class="button button--ghost" type="button" @click="selectTab('shared')">공유 보기</button>
                <button class="button button--ghost" type="button" @click="selectTab('trash')">휴지통 보기</button>
              </div>
            </div>

            <div v-if="homeRecentFiles.length" class="drive-file-grid" :class="`drive-file-grid--${viewMode}`">
              <article
                v-for="item in homeRecentFiles"
                :key="item.id"
                class="drive-file-card"
                @click="openOwnedItem(item)"
              >
                <div class="drive-file-card__check-space"></div>
                <img
                  v-if="itemPreviewType(item) === 'image'"
                  class="drive-file-card__preview"
                  :src="buildOwnedThumbnailPath(item)"
                  :alt="item.fileOriginName"
                  loading="lazy"
                  decoding="async"
                />
                <div v-else class="drive-file-card__preview drive-file-card__preview--placeholder">
                  {{ itemPreviewType(item) === 'folder' ? '폴더' : itemTypeLabel(item) }}
                </div>
                <div class="drive-file-card__body">
                  <strong>{{ item.fileOriginName }}</strong>
                  <small>{{ formatBytes(item.fileSize) }}</small>
                  <small>{{ formatTimestamp(item.lastModifyDate || item.uploadDate) }}</small>
                </div>
              </article>
            </div>
            <p v-else class="panel__empty">최근 파일이 없습니다. 업로드 후 홈에서 최근 작업이 표시됩니다.</p>
          </section>
        </section>

        <section v-else-if="canShowBrowser" class="workspace-stack">
          <section class="panel">
            <div class="panel__header">
              <div>
                <h3 v-if="activeTab === 'drive'">내 드라이브</h3>
                <h3 v-else-if="activeTab === 'recent'">최근 파일</h3>
                <h3 v-else-if="activeTab === 'trash'">휴지통</h3>
                <h3 v-else>받은 공유 파일</h3>
                <p>보기 모드와 선택 도구를 이용해 파일을 빠르게 정리하고, 공유와 이동 작업을 한 화면에서 처리합니다.</p>
              </div>
              <div class="drive-toolbar__actions" v-if="activeTab === 'drive'">
                <div class="drive-breadcrumbs">
                  <button class="button button--ghost" type="button" @click="navigateToBreadcrumb(null)">홈</button>
                  <button
                    v-for="crumb in breadcrumbs"
                    :key="crumb.id"
                    class="button button--ghost"
                    type="button"
                    @click="navigateToBreadcrumb(crumb.id)"
                  >
                    {{ crumb.fileOriginName }}
                  </button>
                </div>
                <button class="button button--ghost" type="button" @click="resetDriveFilters">초기화</button>
              </div>
              <div class="scope-toggle scope-toggle--wrap" v-else-if="activeTab === 'shared'">
                <button class="button" :class="{ 'button--primary': sharedTab === 'received' }" type="button" @click="sharedTab = 'received'">받은 공유</button>
                <button class="button" :class="{ 'button--primary': sharedTab === 'sent' }" type="button" @click="sharedTab = 'sent'">보낸 공유</button>
              </div>
            </div>

            <div v-if="canShowSelectionBar" class="drive-selection-bar">
              <label class="checkbox-row drive-selection-bar__toggle">
                <input :checked="allCurrentSelected" type="checkbox" @change="toggleSelectAllCurrent" />
                <span>현재 화면 전체 선택</span>
              </label>
              <div class="drive-selection-bar__actions">
                <span class="drive-selection-bar__hint">선택 {{ selectedIds.length }}개</span>
                <template v-if="activeTab === 'drive' || activeTab === 'recent'">
                  <select v-model="moveTargetId" class="drive-selection-bar__select">
                    <option value="">홈으로 이동</option>
                    <option v-for="option in moveDestinationOptions" :key="`dest-${option.value}`" :value="option.value">
                      {{ option.label }}
                    </option>
                  </select>
                  <button class="button button--ghost" type="button" :disabled="!selectedIds.length" @click="moveSelectedItemsToFolder">이동</button>
                  <button class="button button--ghost" type="button" :disabled="!selectedShareableItems.length" @click="openShareDialog(selectedShareableItems)">공유</button>
                  <button class="button button--ghost" type="button" :disabled="!selectedIds.length" @click="moveSelectedToTrash">휴지통</button>
                </template>
                <template v-else-if="activeTab === 'trash'">
                  <button class="button button--ghost" type="button" :disabled="!selectedIds.length" @click="restoreSelectedFromTrash">복구</button>
                  <button class="button button--ghost" type="button" :disabled="!selectedIds.length" @click="deleteSelectedPermanently">완전 삭제</button>
                  <button class="button button--ghost" type="button" @click="handleClearTrash">휴지통 비우기</button>
                </template>
                <template v-else>
                  <button class="button button--ghost" type="button" :disabled="!selectedIds.length" @click="saveSelectedSharedFiles">내 드라이브에 저장</button>
                </template>
              </div>
            </div>

            <div v-if="viewMode === 'list'" class="sheet-table-wrap drive-table-wrap">
              <table class="sheet-table drive-table">
                <thead>
                  <tr>
                    <th></th>
                    <th>이름</th>
                    <th>유형</th>
                    <th>소유자</th>
                    <th>용량</th>
                    <th>수정 시각</th>
                    <th>작업</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="item in browserItems" :key="getSelectableId(item)">
                    <td>
                      <input :checked="selectedIds.includes(getSelectableId(item))" type="checkbox" @change="toggleSelection(item)" />
                    </td>
                    <td class="drive-table__name">
                      <button
                        class="drive-table__item"
                        type="button"
                        @click="activeTab === 'shared' ? openSharedItem(item) : openOwnedItem(item)"
                      >
                        <img
                          v-if="itemPreviewType(item) === 'image'"
                          class="drive-table__thumb"
                          :src="activeTab === 'shared' ? buildSharedThumbnailPath(item) : buildOwnedThumbnailPath(item)"
                          :alt="item.fileOriginName"
                          loading="lazy"
                          decoding="async"
                        />
                        <span v-else class="drive-table__thumb drive-table__thumb--placeholder">
                          {{ itemPreviewType(item) === 'folder' ? 'DIR' : itemTypeLabel(item) }}
                        </span>
                        <span>
                          <strong>{{ item.fileOriginName }}</strong>
                          <small>{{ item.ownerDisplayName || item.ownerLoginId || '내 드라이브' }}</small>
                        </span>
                      </button>
                    </td>
                    <td>{{ itemTypeLabel(item) }}</td>
                    <td>{{ item.ownerDisplayName || item.ownerLoginId || '-' }}</td>
                    <td>{{ isFolder(item) ? '-' : formatBytes(item.fileSize) }}</td>
                    <td>{{ formatTimestamp(item.deletedAt || item.lastModifyDate || item.uploadDate || item.sharedAt) }}</td>
                    <td>
                      <div class="sheet-table__actions">
                        <button class="button button--ghost" type="button" @click="activeTab === 'shared' ? openSharedItem(item) : openOwnedItem(item)">열기</button>
                        <button v-if="activeTab !== 'trash' && activeTab !== 'shared'" class="button button--ghost" type="button" @click="promptRename(item)">이름 변경</button>
                        <button v-if="activeTab !== 'trash' && activeTab !== 'shared' && !isFolder(item)" class="button button--ghost" type="button" @click="openShareDialog([item])">공유</button>
                        <button v-if="activeTab !== 'trash' && activeTab !== 'shared'" class="button button--ghost" type="button" @click="moveItemToTrash(item)">휴지통</button>
                        <button v-if="activeTab === 'trash'" class="button button--ghost" type="button" @click="restoreTrashItem(item)">복구</button>
                        <button v-if="activeTab === 'trash'" class="button button--ghost" type="button" @click="deleteItemPermanently(item)">완전 삭제</button>
                        <button v-if="activeTab === 'shared'" class="button button--ghost" type="button" @click="handleSaveSharedFile(item)">내 드라이브 저장</button>
                      </div>
                    </td>
                  </tr>
                  <tr v-if="!browserItems.length">
                    <td colspan="7" class="sheet-table__empty">표시할 항목이 없습니다.</td>
                  </tr>
                </tbody>
              </table>
            </div>

            <div v-else class="drive-file-grid" :class="`drive-file-grid--${viewMode}`">
              <article
                v-for="item in browserItems"
                :key="getSelectableId(item)"
                class="drive-file-card"
                @click="activeTab === 'shared' ? openSharedItem(item) : openOwnedItem(item)"
              >
                <label class="drive-file-card__checkbox" @click.stop>
                  <input :checked="selectedIds.includes(getSelectableId(item))" type="checkbox" @change="toggleSelection(item)" />
                </label>
                <img
                  v-if="itemPreviewType(item) === 'image'"
                  class="drive-file-card__preview"
                  :src="activeTab === 'shared' ? buildSharedThumbnailPath(item) : buildOwnedThumbnailPath(item)"
                  :alt="item.fileOriginName"
                  loading="lazy"
                  decoding="async"
                />
                <div v-else class="drive-file-card__preview drive-file-card__preview--placeholder">
                  {{ itemPreviewType(item) === 'folder' ? '폴더' : itemTypeLabel(item) }}
                </div>
                <div class="drive-file-card__body">
                  <strong>{{ item.fileOriginName }}</strong>
                  <small>{{ isFolder(item) ? '폴더' : formatBytes(item.fileSize) }}</small>
                  <small>{{ formatTimestamp(item.deletedAt || item.lastModifyDate || item.uploadDate || item.sharedAt) }}</small>
                </div>
                <div class="drive-file-card__actions" @click.stop>
                  <button class="button button--ghost" type="button" @click="activeTab === 'shared' ? openSharedItem(item) : openOwnedItem(item)">열기</button>
                  <button v-if="activeTab !== 'trash' && activeTab !== 'shared' && !isFolder(item)" class="button button--ghost" type="button" @click="openShareDialog([item])">공유</button>
                  <button v-if="activeTab !== 'trash' && activeTab !== 'shared'" class="button button--ghost" type="button" @click="moveItemToTrash(item)">휴지통</button>
                  <button v-if="activeTab === 'trash'" class="button button--ghost" type="button" @click="restoreTrashItem(item)">복구</button>
                  <button v-if="activeTab === 'trash'" class="button button--ghost" type="button" @click="deleteItemPermanently(item)">삭제</button>
                  <button v-if="activeTab === 'shared'" class="button button--ghost" type="button" @click="handleSaveSharedFile(item)">저장</button>
                </div>
              </article>
              <p v-if="!browserItems.length" class="panel__empty drive-file-grid__empty">표시할 항목이 없습니다.</p>
            </div>

            <div v-if="activeTab === 'drive' && drivePage.totalPage > 1" class="drive-pagination">
              <button class="button button--ghost" type="button" :disabled="pageFilters.page <= 0" @click="pageFilters.page -= 1">이전</button>
              <span>{{ drivePage.currentPage + 1 }} / {{ drivePage.totalPage }}</span>
              <button class="button button--ghost" type="button" :disabled="drivePage.currentPage >= drivePage.totalPage - 1" @click="pageFilters.page += 1">다음</button>
            </div>
          </section>
        </section>

        <section v-else-if="activeTab === 'shared' && sharedTab === 'sent'" class="workspace-stack">
          <section class="panel">
            <div class="panel__header">
              <div>
                <h3>보낸 공유 관리</h3>
                <p>내가 보낸 공유를 묶음별로 확인하고 개별 또는 전체 공유를 취소할 수 있습니다.</p>
              </div>
            </div>

            <div v-if="sharedSent.length" class="workspace-stack">
              <article v-for="group in sharedSent" :key="group.fileId" class="drive-share-group">
                <div class="drive-share-group__head">
                  <div>
                    <strong>{{ group.fileOriginName }}</strong>
                    <small>{{ formatBytes(group.fileSize) }} · {{ String(group.fileFormat || 'FILE').toUpperCase() }}</small>
                  </div>
                  <button class="button button--ghost" type="button" @click="cancelAllSharesForFile(group)">전체 공유 취소</button>
                </div>
                <div class="drive-share-group__recipients">
                  <span v-for="recipient in group.recipients" :key="recipient.shareId || recipient.recipientLoginId" class="chip chip--neutral">
                    {{ recipient.recipientDisplayName }} ({{ recipient.recipientLoginId }})
                  </span>
                </div>
              </article>
            </div>
            <p v-else class="panel__empty">보낸 공유가 없습니다.</p>
          </section>
        </section>

        <section v-else-if="activeTab === 'admin'" class="workspace-stack">
          <div class="summary-grid">
            <article class="summary-card">
              <span class="summary-card__label">전체 사용자</span>
              <strong class="summary-card__balance">{{ adminSummary?.totalUserCount || 0 }}</strong>
              <span class="summary-card__meta">활성 {{ adminSummary?.activeUserCount || 0 }}</span>
            </article>
            <article class="summary-card">
              <span class="summary-card__label">전체 파일</span>
              <strong class="summary-card__balance">{{ adminSummary?.totalFileCount || 0 }}</strong>
              <span class="summary-card__meta">폴더 {{ adminSummary?.totalFolderCount || 0 }}</span>
            </article>
            <article class="summary-card">
              <span class="summary-card__label">공유 파일</span>
              <strong class="summary-card__balance">{{ adminSummary?.totalSharedFileCount || 0 }}</strong>
              <span class="summary-card__meta">휴지통 {{ adminSummary?.totalTrashedCount || 0 }}</span>
            </article>
            <article class="summary-card">
              <span class="summary-card__label">총 사용량</span>
              <strong class="summary-card__balance">{{ formatBytes(adminSummary?.totalUsedBytes || 0) }}</strong>
              <span class="summary-card__meta">전체 저장소 기준</span>
            </article>
          </div>

          <section class="panel">
            <div class="panel__header">
              <div>
                <h3>스토리지 관리</h3>
                <p>전체 공급 용량, 현재 사용량, 남은 공간과 무결성 이슈를 한 번에 점검합니다.</p>
              </div>
            </div>
            <div class="travel-form-grid">
              <label class="field">
                <span class="field__label">총 공급 용량 (GB)</span>
                <input v-model="adminCapacityForm.providerCapacityGb" type="number" min="1" />
              </label>
              <div class="field">
                <span class="field__label">현재 사용량</span>
                <div class="drive-admin-stat">
                  <strong>{{ formatBytes(storageAnalytics?.providerUsedBytes || 0) }}</strong>
                  <small>{{ storageAnalytics?.providerUsagePercent?.toFixed?.(1) || '0.0' }}%</small>
                </div>
              </div>
              <div class="field">
                <span class="field__label">남은 용량</span>
                <div class="drive-admin-stat">
                  <strong>{{ formatBytes(storageAnalytics?.providerRemainingBytes || 0) }}</strong>
                </div>
              </div>
              <div class="field">
                <span class="field__label">무결성 이슈</span>
                <div class="drive-admin-stat">
                  <strong>{{ storageAnalytics?.issueCount || 0 }}</strong>
                </div>
              </div>
            </div>
            <div class="entry-editor__actions">
              <button class="button button--primary" type="button" @click="saveProviderCapacity">스토리지 총량 저장</button>
            </div>
            <ul class="drive-issue-list">
              <li v-for="issue in storageAnalytics?.issues || []" :key="issue">{{ issue }}</li>
              <li v-if="!(storageAnalytics?.issues || []).length">보고된 무결성 이슈가 없습니다.</li>
            </ul>
          </section>

          <section class="panel">
            <div class="panel__header">
              <div>
                <h3>사용자 현황</h3>
                <p>사용자별 파일 수, 공유 수, 사용량과 활성 상태를 관리합니다.</p>
              </div>
            </div>
            <div class="sheet-table-wrap drive-table-wrap">
              <table class="sheet-table drive-table">
                <thead>
                  <tr>
                    <th>사용자</th>
                    <th>역할</th>
                    <th>파일/폴더</th>
                    <th>공유/휴지통</th>
                    <th>사용량</th>
                    <th>상태</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="user in adminUsers" :key="user.id">
                    <td>
                      <strong>{{ user.displayName }}</strong>
                      <small>{{ user.loginId }}</small>
                    </td>
                    <td>{{ user.role }}</td>
                    <td>{{ user.fileCount }} / {{ user.folderCount }}</td>
                    <td>{{ user.sharedFileCount }} / {{ user.trashedCount }}</td>
                    <td>{{ formatBytes(user.usedBytes) }}</td>
                    <td>
                      <button class="button" :class="user.active ? 'button--ghost' : 'button--primary'" type="button" @click="toggleUserActive(user)">
                        {{ user.active ? '비활성 전환' : '활성 전환' }}
                      </button>
                    </td>
                  </tr>
                  <tr v-if="!adminUsers.length">
                    <td colspan="6" class="sheet-table__empty">아직 사용자 통계가 없습니다.</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </section>
        </section>

        <div v-if="uploadProgress.open" class="travel-modal" @click.self>
          <div class="travel-modal__dialog drive-progress-modal">
            <div class="travel-modal__header">
              <div>
                <h2>{{ uploadProgress.title }}</h2>
                <p>{{ uploadProgress.description }}</p>
              </div>
            </div>
            <div class="travel-modal__body drive-progress-modal__body">
              <strong>{{ uploadProgress.fileName || '업로드 준비 중' }}</strong>
              <span>{{ uploadProgress.current }}/{{ uploadProgress.total }} 파일</span>
              <div class="drive-progress-bar">
                <span :style="{ width: `${uploadProgress.percent}%` }"></span>
              </div>
              <small>{{ uploadProgress.percent }}%</small>
            </div>
          </div>
        </div>

        <div v-if="shareDialog.open" class="travel-modal" @click.self="closeShareDialog">
          <div class="travel-modal__dialog drive-share-modal">
            <div class="travel-modal__header">
              <div>
                <h2>파일 공유</h2>
                <p>{{ normalizedShareDialogTitle }}</p>
              </div>
              <button class="button button--ghost" type="button" @click="closeShareDialog">닫기</button>
            </div>
            <div class="travel-modal__body drive-share-modal__body">
              <section class="panel panel--compact">
                <div class="panel__header">
                  <div>
                    <h3>받는 사람 찾기</h3>
                    <p>loginId 또는 표시 이름으로 검색해 바로 공유합니다.</p>
                  </div>
                </div>
                <div class="drive-toolbar__filters">
                  <input v-model="shareDialog.searchQuery" class="drive-input" type="search" placeholder="사용자 검색" @keyup.enter="searchRecipients" />
                  <button class="button button--ghost" type="button" :disabled="shareDialog.loading" @click="searchRecipients">검색</button>
                </div>
                <div v-if="shareDialog.searchResults.length" class="drive-share-search-results">
                  <button
                    v-for="candidate in shareDialog.searchResults"
                    :key="candidate.recipientLoginId"
                    class="drive-share-search-result"
                    :class="{ 'drive-share-search-result--active': shareDialog.selectedRecipientLoginId === candidate.recipientLoginId }"
                    type="button"
                    @click="shareDialog.selectedRecipientLoginId = candidate.recipientLoginId"
                  >
                    <strong>{{ candidate.recipientDisplayName }}</strong>
                    <span>{{ candidate.recipientLoginId }}</span>
                  </button>
                </div>
                <p v-else class="panel__empty">검색 결과가 없으면 loginId 또는 표시 이름을 다시 입력해보세요.</p>
                <div class="entry-editor__actions">
                  <button class="button button--primary" type="button" :disabled="shareDialog.saving" @click="submitShare">
                    {{ shareDialog.saving ? '공유 중' : '선택한 사용자에게 공유' }}
                  </button>
                </div>
              </section>

              <section v-if="shareDialog.targets.length === 1" class="panel panel--compact">
                <div class="panel__header">
                  <div>
                    <h3>현재 공유 대상</h3>
                    <p>이미 공유된 사용자를 확인하고 개별 취소 또는 전체 취소를 할 수 있습니다.</p>
                  </div>
                </div>
                <div v-if="shareDialog.existingRecipients.length" class="workspace-stack">
                  <article v-for="recipient in shareDialog.existingRecipients" :key="recipient.shareId || recipient.recipientLoginId" class="drive-share-recipient">
                    <div>
                      <strong>{{ recipient.recipientDisplayName }}</strong>
                      <small>{{ recipient.recipientLoginId }}</small>
                    </div>
                    <button class="button button--ghost" type="button" @click="cancelShareForRecipient(recipient.recipientLoginId)">공유 취소</button>
                  </article>
                  <button class="button button--ghost" type="button" @click="cancelAllSharesForFile(shareDialog.targets[0])">전체 공유 취소</button>
                </div>
                <p v-else class="panel__empty">아직 공유된 사용자가 없습니다.</p>
              </section>
            </div>
          </div>
        </div>

        <CalenDriveProfileModal
          :open="profileModalOpen"
          :current-user="currentUser"
          @close="profileModalOpen = false"
          @updated="handleProfileUpdated"
        />
      </template>
    </div>
  </div>
</template>
