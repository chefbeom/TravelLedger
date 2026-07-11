<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import CalenDriveProfileModal from './CalenDriveProfileModal.vue'
import {
  abortDriveUpload,
  cancelAllDriveShares,
  cancelDriveShare,
  clearDriveTrash,
  completeDriveUpload,
  createDriveDownloadLink,
  createDriveFolder,
  deleteDriveItem,
  downloadDriveItems,
  fetchDriveAdminDashboard,
  fetchDriveFolderDestinations,
  fetchDriveHomeSummary,
  fetchDriveDownloadLinks,
  fetchDrivePage,
  fetchDriveFileVersions,
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
  restoreDriveFileVersion,
  restoreDriveItems,
  revokeDriveDownloadLink,
  saveSharedDriveFile,
  searchDriveShareRecipients,
  shareDriveFiles,
  updateDriveStorageCapacity,
  updateDriveItemLock,
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
const moveDestinationFolders = ref([])
const moveDestinationLoading = ref(false)
const moveDestinationsHydrated = ref(false)
const folderTreeExpandedIds = ref([])
const detailsPanelOpen = ref(true)
const dragDepth = ref(0)
const driveDraggedItems = ref([])
const driveMoveDropTargetId = ref('')
const DRIVE_ROOT_DROP_TARGET_ID = '__drive-root__'

const previewDialog = reactive({
  open: false,
  item: null,
})

const contextMenu = reactive({
  open: false,
  x: 0,
  y: 0,
  item: null,
})

const moveDialog = reactive({
  open: false,
  targets: [],
  folders: [],
  targetParentId: '',
  loading: false,
  saving: false,
})

const sharedSaveDialog = reactive({
  open: false,
  targets: [],
  folders: [],
  targetParentId: '',
  loading: false,
  saving: false,
})

const folderDialog = reactive({
  open: false,
  title: '새 폴더 만들기',
  folderName: '',
  folders: [],
  targetParentId: '',
  loading: false,
  saving: false,
  successMessage: '폴더를 만들었습니다.',
})

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
  downloadLinks: [],
  searchQuery: '',
  searchResults: [],
  selectedRecipientLoginId: '',
  loading: false,
  saving: false,
  linkSaving: false,
  linkExpiresInMinutes: 60,
  linkMaxDownloads: 10,
  generatedDownloadLink: null,
  revokingLinkId: null,
})

const versionDrawer = reactive({
  open: false,
  item: null,
  versions: [],
  loading: false,
  restoringId: null,
  errorMessage: '',
  successMessage: '',
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

const selectedShareableItems = computed(() => selectedItems.value.filter((item) => canShareItem(item)))

const canMoveSelectedItems = computed(() => selectedItems.value.length > 0 && selectedItems.value.every((item) => canModifyOwnedItem(item)))

const canDownloadSelectedItems = computed(() => selectedItems.value.length > 0 && selectedItems.value.every((item) => canDownloadOwnedItem(item)))

const hasSelectedLockedItems = computed(() => selectedItems.value.some((item) => isLockedItem(item)))

const primarySelectedItem = computed(() => selectedItems.value.length === 1 ? selectedItems.value[0] : null)

const selectedVersionableItem = computed(() => canOpenVersionDrawer(primarySelectedItem.value) ? primarySelectedItem.value : null)

const selectedTotalBytes = computed(() => {
  return selectedItems.value.reduce((sum, item) => sum + Number(item.fileSize || 0), 0)
})

const canUploadInCurrentLocation = computed(() => activeTab.value === 'drive')

const isDriveItemDragging = computed(() => driveDraggedItems.value.length > 0)

const isDriveDropActive = computed(() => canUploadInCurrentLocation.value && dragDepth.value > 0 && !isDriveItemDragging.value)

const detailRows = computed(() => {
  if (selectedItems.value.length > 1) {
    return [
      { label: '선택 항목', value: `${selectedItems.value.length}개` },
      { label: '총 용량', value: formatBytes(selectedTotalBytes.value) },
      { label: '현재 위치', value: normalizedCurrentLocationLabel.value },
    ]
  }

  const item = primarySelectedItem.value
  if (!item) {
    return []
  }

  return [
    { label: '유형', value: itemTypeLabel(item) },
    { label: '용량', value: isFolder(item) ? '-' : formatBytes(item.fileSize) },
    { label: '소유자', value: itemOwnerLabel(item) },
    { label: '수정', value: formatTimestamp(item.deletedAt || item.lastModifyDate || item.uploadDate || item.sharedAt) },
    { label: '위치', value: item.folderPath || normalizedCurrentLocationLabel.value },
  ]
})

function buildBlockedFolderIds(targets, folders) {
  const blocked = new Set(
    (targets || [])
      .filter((item) => isFolder(item))
      .map((item) => String(item.id)),
  )
  let changed = true
  while (changed) {
    changed = false
    for (const folder of folders || []) {
      const folderId = String(folder.id)
      const parentId = folder.parentId == null ? '' : String(folder.parentId)
      if (!blocked.has(folderId) && parentId && blocked.has(parentId)) {
        blocked.add(folderId)
        changed = true
      }
    }
  }
  return blocked
}

const moveDestinationBlockedFolderIds = computed(() =>
  buildBlockedFolderIds(selectedItems.value, moveDestinationFolders.value),
)

const moveDestinationOptions = computed(() => {
  if (activeTab.value !== 'drive' && activeTab.value !== 'recent') {
    return []
  }

  return [
    { value: '', label: '내 드라이브', path: '최상위 위치', disabled: false },
    ...moveDestinationFolders.value.map((folder) => ({
      value: String(folder.id),
      label: folder.path || folder.fileOriginName,
      path: folder.path || folder.fileOriginName,
      disabled: moveDestinationBlockedFolderIds.value.has(String(folder.id)) || Boolean(folder.lockedFile || folder.systemManaged),
    })),
  ]
})

const folderTreeChildrenByParent = computed(() => {
  const childrenByParent = new Map()
  for (const folder of moveDestinationFolders.value) {
    const parentKey = folder.parentId == null ? '' : String(folder.parentId)
    const current = childrenByParent.get(parentKey) || []
    current.push(folder)
    childrenByParent.set(parentKey, current)
  }

  for (const children of childrenByParent.values()) {
    children.sort((left, right) =>
      String(left.fileOriginName || '').localeCompare(String(right.fileOriginName || ''), 'ko-KR'),
    )
  }

  return childrenByParent
})

const folderTreeVisibleNodes = computed(() => {
  const expandedIds = new Set(folderTreeExpandedIds.value)
  const visited = new Set()

  function visit(parentId = '', depth = 0) {
    const children = folderTreeChildrenByParent.value.get(parentId) || []
    const nodes = []

    for (const folder of children) {
      const folderId = String(folder.id)
      if (visited.has(folderId)) {
        continue
      }
      visited.add(folderId)

      const childFolders = folderTreeChildrenByParent.value.get(folderId) || []
      const expanded = expandedIds.has(folderId)
      nodes.push({
        ...folder,
        id: folderId,
        depth,
        expanded,
        hasChildren: childFolders.length > 0,
        active: activeTab.value === 'drive' && String(pageFilters.parentId ?? '') === folderId,
      })

      if (expanded) {
        nodes.push(...visit(folderId, depth + 1))
      }
    }

    return nodes
  }

  return visit('', 0)
})

const isDriveRootActive = computed(() => activeTab.value === 'drive' && pageFilters.parentId == null)

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

const moveDialogTitle = computed(() => {
  if (moveDialog.targets.length === 1) {
    return moveDialog.targets[0]?.fileOriginName || '선택한 항목'
  }
  return `${moveDialog.targets.length}개 항목`
})

const sharedSaveDialogTitle = computed(() => {
  if (sharedSaveDialog.targets.length === 1) {
    return sharedSaveDialog.targets[0]?.fileOriginName || '공유 파일'
  }
  return `${sharedSaveDialog.targets.length}개 공유 파일`
})

const moveDialogBlockedFolderIds = computed(() => buildBlockedFolderIds(moveDialog.targets, moveDialog.folders))

const moveDialogDestinationOptions = computed(() => [
  {
    value: '',
    label: '내 드라이브',
    path: '최상위 위치',
    disabled: false,
  },
  ...moveDialog.folders.map((folder) => ({
    value: String(folder.id),
    label: folder.fileOriginName,
    path: folder.path || folder.fileOriginName,
    disabled: moveDialogBlockedFolderIds.value.has(String(folder.id)) || Boolean(folder.lockedFile || folder.systemManaged),
  })),
])

const selectedMoveDialogDestination = computed(() =>
  moveDialogDestinationOptions.value.find((option) => option.value === moveDialog.targetParentId),
)

const canConfirmMoveDialog = computed(() =>
  Boolean(selectedMoveDialogDestination.value)
    && !selectedMoveDialogDestination.value.disabled
    && !moveDialog.loading
    && !moveDialog.saving,
)

const sharedSaveDestinationOptions = computed(() => [
  {
    value: '',
    label: '내 드라이브',
    path: '최상위 위치',
    disabled: false,
  },
  ...sharedSaveDialog.folders.map((folder) => ({
    value: String(folder.id),
    label: folder.fileOriginName,
    path: folder.path || folder.fileOriginName,
    disabled: Boolean(folder.lockedFile || folder.systemManaged),
  })),
])

const selectedSharedSaveDestination = computed(() =>
  sharedSaveDestinationOptions.value.find((option) => option.value === sharedSaveDialog.targetParentId),
)

const canConfirmSharedSaveDialog = computed(() =>
  Boolean(sharedSaveDialog.targets.length)
    && Boolean(selectedSharedSaveDestination.value)
    && !selectedSharedSaveDestination.value.disabled
    && !sharedSaveDialog.loading
    && !sharedSaveDialog.saving,
)

const folderDialogDestinationOptions = computed(() => [
  {
    value: '',
    label: '내 드라이브',
    path: '최상위 위치',
    disabled: false,
  },
  ...folderDialog.folders.map((folder) => ({
    value: String(folder.id),
    label: folder.fileOriginName,
    path: folder.path || folder.fileOriginName,
    disabled: Boolean(folder.lockedFile || folder.systemManaged),
  })),
])

const selectedFolderDialogDestination = computed(() =>
  folderDialogDestinationOptions.value.find((option) => option.value === folderDialog.targetParentId),
)

const canConfirmFolderDialog = computed(() =>
  Boolean(folderDialog.folderName.trim())
    && Boolean(selectedFolderDialogDestination.value)
    && !selectedFolderDialogDestination.value.disabled
    && !folderDialog.loading
    && !folderDialog.saving,
)

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

function isSystemManagedItem(item) {
  return Boolean(item?.systemManaged)
}

function isLockedItem(item) {
  return Boolean(item?.lockedFile || item?.systemManaged)
}

function canModifyOwnedItem(item) {
  return activeTab.value !== 'trash' && activeTab.value !== 'shared' && !isLockedItem(item)
}

function canShareItem(item) {
  return canModifyOwnedItem(item) && !isFolder(item)
}

function canDownloadOwnedItem(item) {
  return (activeTab.value === 'drive' || activeTab.value === 'recent') && Boolean(item?.id)
}

function canOpenVersionDrawer(item) {
  return canDownloadOwnedItem(item) && !isFolder(item)
}

function canOpenItemLocation(item) {
  return activeTab.value === 'drive' && Boolean(pageFilters.searchQuery) && Boolean(item)
}

function canToggleLock(item) {
  return activeTab.value !== 'trash' && activeTab.value !== 'shared' && Boolean(item?.id) && !isSystemManagedItem(item)
}

function canDragDriveItem(item) {
  return (activeTab.value === 'drive' || activeTab.value === 'recent') && canModifyOwnedItem(item)
}

function isFolderDestinationNode(item) {
  return Boolean(item?.id) && (
    Object.prototype.hasOwnProperty.call(item, 'parentId')
    || Object.prototype.hasOwnProperty.call(item, 'hasChildren')
    || Object.prototype.hasOwnProperty.call(item, 'depth')
  )
}

function canUseMoveDropTargets() {
  return activeTab.value === 'drive' || activeTab.value === 'recent'
}

function canUseAsMoveDropTarget(item) {
  return canUseMoveDropTargets()
    && Boolean(item?.id)
    && (isFolder(item) || isFolderDestinationNode(item))
    && !isLockedItem(item)
}

function isImageFile(item) {
  const extension = String(item?.fileFormat || '').toLowerCase()
  return ['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp', 'svg'].includes(extension)
}

function isVideoFile(item) {
  const extension = String(item?.fileFormat || '').toLowerCase()
  return ['mp4', 'mov', 'webm', 'avi', 'mkv'].includes(extension)
}

function isPdfFile(item) {
  const extension = String(item?.fileFormat || '').toLowerCase()
  return extension === 'pdf'
}

function isTextFile(item) {
  const extension = String(item?.fileFormat || '').toLowerCase()
  return ['txt', 'text', 'md', 'markdown', 'csv', 'log', 'json', 'xml', 'yaml', 'yml'].includes(extension)
}

function buildOwnedDownloadPath(item) {
  return item?.id ? `/api/file/${item.id}/download` : ''
}

function buildOwnedThumbnailPath(item) {
  return item?.thumbnailUrl || `/api/file/${item.id}/thumbnail`
}

function buildSharedDownloadPath(item) {
  return item?.fileId ? `/api/file/share/shared/${item.fileId}/download` : ''
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
  if (isPdfFile(item)) {
    return 'pdf'
  }
  if (isTextFile(item)) {
    return 'text'
  }
  return 'file'
}
function itemTypeLabel(item) {
  if (isFolder(item)) {
    return '폴더'
  }
  return String(item.fileFormat || '파일').toUpperCase()
}

function isSharedBrowserItem(item) {
  return activeTab.value === 'shared' && sharedTab.value === 'received' && item?.fileId
}

function itemDownloadPath(item) {
  return isSharedBrowserItem(item) ? buildSharedDownloadPath(item) : buildOwnedDownloadPath(item)
}

function itemThumbnailPath(item) {
  return isSharedBrowserItem(item) ? buildSharedThumbnailPath(item) : buildOwnedThumbnailPath(item)
}

function itemOwnerLabel(item) {
  return item?.ownerDisplayName || item?.ownerLoginId || '내 드라이브'
}

function canPreviewItem(item) {
  return !isFolder(item) && (isImageFile(item) || isVideoFile(item) || isPdfFile(item) || isTextFile(item))
}

function openPreviewDialog(item) {
  if (!canPreviewItem(item)) {
    openBrowserItem(item)
    return
  }
  previewDialog.item = item
  previewDialog.open = true
}

function closePreviewDialog() {
  previewDialog.open = false
  previewDialog.item = null
}

function openBrowserItem(item) {
  if (!item) {
    return
  }
  if (isFolder(item)) {
    openFolder(item)
    return
  }
  if (canPreviewItem(item)) {
    openPreviewDialog(item)
    return
  }
  window.open(itemDownloadPath(item), '_blank', 'noopener')
}

function openItemInNewWindow(item) {
  if (!item || isFolder(item)) {
    return
  }
  window.open(itemDownloadPath(item), '_blank', 'noopener')
}

function selectOnlyItem(item) {
  selectedIds.value = [getSelectableId(item)]
}

function selectItemFromPointer(item, event) {
  if (event?.ctrlKey || event?.metaKey) {
    toggleSelection(item)
    return
  }
  selectOnlyItem(item)
}

function openContextMenu(item, event) {
  if (!item) {
    return
  }
  selectOnlyItem(item)
  const width = 236
  const height = 380
  contextMenu.x = Math.min(event?.clientX || 0, Math.max(12, window.innerWidth - width - 12))
  contextMenu.y = Math.min(event?.clientY || 0, Math.max(12, window.innerHeight - height - 12))
  contextMenu.item = item
  contextMenu.open = true
}

function closeContextMenu() {
  contextMenu.open = false
  contextMenu.item = null
}

async function runContextAction(action) {
  const item = contextMenu.item
  closeContextMenu()
  if (!item || typeof action !== 'function') {
    return
  }
  await action(item)
}

function toggleDetailsPanel() {
  detailsPanelOpen.value = !detailsPanelOpen.value
}

function handleDriveDragEnter() {
  if (isDriveItemDragging.value || !canUploadInCurrentLocation.value) {
    return
  }
  dragDepth.value += 1
}

function handleDriveDragLeave() {
  if (isDriveItemDragging.value || !canUploadInCurrentLocation.value) {
    return
  }
  dragDepth.value = Math.max(0, dragDepth.value - 1)
}

function normalizeDriveUploadRelativePath(value) {
  return String(value || '')
    .replace(/\\/g, '/')
    .replace(/^\/+/, '')
    .split('/')
    .filter(Boolean)
    .join('/')
}

function getDriveUploadRelativePath(file) {
  return normalizeDriveUploadRelativePath(file?.driveRelativePath || file?.webkitRelativePath || '')
}

function attachDriveUploadRelativePath(file, relativePath) {
  const normalizedPath = normalizeDriveUploadRelativePath(relativePath)
  if (!file || !normalizedPath || getDriveUploadRelativePath(file)) {
    return file
  }

  try {
    Object.defineProperty(file, 'driveRelativePath', {
      value: normalizedPath,
      configurable: true,
    })
  } catch {
    try {
      file.driveRelativePath = normalizedPath
    } catch {
      if (typeof File === 'function') {
        const clonedFile = new File([file], file.name, {
          type: file.type,
          lastModified: file.lastModified,
        })
        try {
          Object.defineProperty(clonedFile, 'driveRelativePath', {
            value: normalizedPath,
            configurable: true,
          })
        } catch {
          // The upload can still continue as a normal flat file if the path cannot be attached.
        }
        return clonedFile
      }
    }
  }

  return file
}

function readDroppedFileEntry(entry, relativePath) {
  return new Promise((resolve, reject) => {
    entry.file(
      (file) => resolve(attachDriveUploadRelativePath(file, relativePath || file.name)),
      reject,
    )
  })
}

function readDroppedDirectoryEntries(entry) {
  return new Promise((resolve, reject) => {
    const reader = entry.createReader()
    const entries = []

    function readBatch() {
      reader.readEntries(
        (batch) => {
          if (!batch.length) {
            resolve(entries)
            return
          }
          entries.push(...batch)
          readBatch()
        },
        reject,
      )
    }

    readBatch()
  })
}

async function collectDroppedEntryFiles(entry, parentPath = '') {
  const entryPath = normalizeDriveUploadRelativePath(`${parentPath}${entry?.name || ''}`)
  if (!entry || !entryPath) {
    return []
  }
  if (entry.isFile) {
    return [await readDroppedFileEntry(entry, entryPath)]
  }
  if (!entry.isDirectory) {
    return []
  }

  const children = await readDroppedDirectoryEntries(entry)
  const nestedFiles = await Promise.all(children.map((child) => collectDroppedEntryFiles(child, `${entryPath}/`)))
  return nestedFiles.flat()
}

async function collectDroppedDriveFiles(dataTransfer) {
  const items = Array.from(dataTransfer?.items || [])
  const entries = items
    .map((item) => (typeof item.webkitGetAsEntry === 'function' ? item.webkitGetAsEntry() : null))
    .filter(Boolean)

  if (!entries.length) {
    return Array.from(dataTransfer?.files || [])
  }

  const groups = await Promise.all(entries.map((entry) => collectDroppedEntryFiles(entry)))
  return groups.flat().filter(Boolean)
}

async function handleDriveDrop(event) {
  dragDepth.value = 0
  if (isDriveItemDragging.value) {
    return
  }
  if (!canUploadInCurrentLocation.value) {
    return
  }
  const files = await collectDroppedDriveFiles(event?.dataTransfer)
  if (!files.length) {
    return
  }
  await handleFilesSelected({ target: { files, value: '' } })
}

function resetDriveMoveDrag() {
  driveDraggedItems.value = []
  driveMoveDropTargetId.value = ''
}

function getDriveDragItemIds() {
  return driveDraggedItems.value.map((item) => String(item.id)).filter(Boolean)
}

function canDropDriveItemsToFolder(target) {
  if (!driveDraggedItems.value.length || !canUseAsMoveDropTarget(target)) {
    return false
  }

  const targetId = String(target.id)
  const draggedIds = getDriveDragItemIds()
  if (!targetId || draggedIds.includes(targetId)) {
    return false
  }

  const blockedFolderIds = buildBlockedFolderIds(driveDraggedItems.value, moveDestinationFolders.value)
  return !blockedFolderIds.has(targetId)
}

function isDriveMoveDropTarget(item) {
  return driveMoveDropTargetId.value === String(item?.id || '')
}

function canDropDriveItemsToRoot() {
  return driveDraggedItems.value.length > 0
    && canUseMoveDropTargets()
    && driveDraggedItems.value.every((item) => canDragDriveItem(item))
}

function isDriveRootMoveDropTarget() {
  return driveMoveDropTargetId.value === DRIVE_ROOT_DROP_TARGET_ID
}

function handleDriveItemDragStart(item, event) {
  if (!canDragDriveItem(item)) {
    event.preventDefault()
    return
  }

  const itemId = getSelectableId(item)
  if (!selectedIds.value.includes(itemId)) {
    selectedIds.value = [itemId]
  }

  const draggingItems = selectedIds.value.includes(itemId)
    ? selectedItems.value.filter((selectedItem) => canDragDriveItem(selectedItem))
    : [item]

  if (!draggingItems.length) {
    event.preventDefault()
    return
  }

  driveDraggedItems.value = draggingItems
  driveMoveDropTargetId.value = ''
  dragDepth.value = 0
  ensureMoveDestinationsLoaded()

  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move'
    event.dataTransfer.setData('application/x-calen-drive-items', getDriveDragItemIds().join(','))
    event.dataTransfer.setData('text/plain', draggingItems.map((dragItem) => dragItem.fileOriginName).join(', '))
  }
}

function handleDriveItemDragEnd() {
  resetDriveMoveDrag()
}

function handleDriveFolderDragOver(item, event) {
  if (!canDropDriveItemsToFolder(item)) {
    return
  }
  event.preventDefault()
  event.stopPropagation()
  if (event.dataTransfer) {
    event.dataTransfer.dropEffect = 'move'
  }
  driveMoveDropTargetId.value = String(item.id)
}

function handleDriveFolderDragLeave(item, event) {
  if (event.currentTarget?.contains?.(event.relatedTarget)) {
    return
  }
  if (isDriveMoveDropTarget(item)) {
    driveMoveDropTargetId.value = ''
  }
}

function handleDriveRootDragOver(event) {
  if (!canDropDriveItemsToRoot()) {
    return
  }
  event.preventDefault()
  event.stopPropagation()
  if (event.dataTransfer) {
    event.dataTransfer.dropEffect = 'move'
  }
  driveMoveDropTargetId.value = DRIVE_ROOT_DROP_TARGET_ID
}

function handleDriveRootDragLeave(event) {
  if (event.currentTarget?.contains?.(event.relatedTarget)) {
    return
  }
  if (isDriveRootMoveDropTarget()) {
    driveMoveDropTargetId.value = ''
  }
}

async function handleDriveRootDrop(event) {
  if (!canDropDriveItemsToRoot()) {
    return
  }

  event.preventDefault()
  event.stopPropagation()

  const movingIds = driveDraggedItems.value.map((movingItem) => movingItem.id).filter(Boolean)
  resetDriveMoveDrag()
  if (!movingIds.length) {
    return
  }

  try {
    await moveDriveItems(movingIds, null)
    setMessages(`${movingIds.length}개 항목을 내 드라이브로 이동했습니다.`)
    invalidateMoveDestinations()
    await Promise.all([loadDrivePage(), loadHomeSummary(), loadRecentFiles()])
    clearSelection()
  } catch (error) {
    setMessages('', error.message)
  }
}

async function handleDriveFolderDrop(item, event) {
  if (!canDropDriveItemsToFolder(item)) {
    return
  }

  event.preventDefault()
  event.stopPropagation()

  const movingItems = [...driveDraggedItems.value]
  const movingIds = movingItems.map((movingItem) => movingItem.id).filter(Boolean)
  resetDriveMoveDrag()
  if (!movingIds.length) {
    return
  }

  try {
    await moveDriveItems(movingIds, Number(item.id))
    setMessages(`${movingIds.length}개 항목을 "${item.fileOriginName}" 폴더로 이동했습니다.`)
    invalidateMoveDestinations()
    await Promise.all([loadDrivePage(), loadHomeSummary(), loadRecentFiles()])
    clearSelection()
  } catch (error) {
    setMessages('', error.message)
  }
}

async function handleDriveKeyboard(event) {
  if (folderDialog.open && event.key === 'Escape') {
    closeFolderDialog()
    return
  }
  if (sharedSaveDialog.open && event.key === 'Escape') {
    closeSharedSaveDialog()
    return
  }
  if (moveDialog.open && event.key === 'Escape') {
    closeMoveDialog()
    return
  }
  if (previewDialog.open && event.key === 'Escape') {
    closePreviewDialog()
    return
  }
  if (contextMenu.open && event.key === 'Escape') {
    closeContextMenu()
    return
  }
  if (!canShowBrowser.value || !selectedItems.value.length) {
    return
  }
  if (event.key === 'Escape') {
    clearSelection()
    return
  }
  if (event.key === 'Enter' && primarySelectedItem.value) {
    event.preventDefault()
    openBrowserItem(primarySelectedItem.value)
    return
  }
  if (event.key === 'Delete' || event.key === 'Backspace') {
    event.preventDefault()
    if (activeTab.value === 'trash') {
      await deleteSelectedPermanently()
      return
    }
    if (activeTab.value !== 'shared') {
      await moveSelectedToTrash()
    }
  }
}

function clearSelection() {
  selectedIds.value = []
  moveTargetId.value = ''
  closeContextMenu()
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

function invalidateMoveDestinations() {
  moveDestinationsHydrated.value = false
  reloadMoveDestinations()
}

async function reloadMoveDestinations() {
  if (moveDestinationLoading.value) {
    return
  }

  moveDestinationLoading.value = true
  try {
    moveDestinationFolders.value = await fetchDriveFolderDestinations()
    moveDestinationsHydrated.value = true
  } catch (error) {
    setMessages('', error.message)
  } finally {
    moveDestinationLoading.value = false
  }
}

async function ensureMoveDestinationsLoaded() {
  if (moveDestinationLoading.value || moveDestinationsHydrated.value) {
    return
  }

  await reloadMoveDestinations()
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
  pageFilters.parentId = null
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

function openFolderTreeRoot() {
  pageFilters.parentId = null
  pageFilters.page = 0
  activeTab.value = 'drive'
}

function openFolderTreeNode(node) {
  pageFilters.parentId = Number(node.id)
  pageFilters.page = 0
  activeTab.value = 'drive'
}

function openItemLocation(item) {
  if (!canOpenItemLocation(item)) {
    return
  }
  pageFilters.parentId = item.parentId ?? null
  pageFilters.page = 0
  pageFilters.searchQuery = ''
  topSearch.value = ''
  activeTab.value = 'drive'
  selectedIds.value = item?.id ? [String(item.id)] : []
  setMessages(`"${item.fileOriginName}" 위치로 이동했습니다.`)
}

function toggleFolderTreeNode(node) {
  if (!node?.hasChildren) {
    return
  }

  const folderId = String(node.id)
  if (folderTreeExpandedIds.value.includes(folderId)) {
    folderTreeExpandedIds.value = folderTreeExpandedIds.value.filter((id) => id !== folderId)
    return
  }

  folderTreeExpandedIds.value = [...folderTreeExpandedIds.value, folderId]
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
  await openFolderDialog(activeTab.value === 'drive' ? pageFilters.parentId : null)
}

async function promptCreateFolderInside(item) {
  if (!isFolder(item)) {
    return
  }
  if (!canModifyOwnedItem(item)) {
    setMessages('', '잠긴 폴더에는 새 폴더를 만들 수 없습니다. 먼저 잠금을 해제해 주세요.')
    return
  }
  await openFolderDialog(item.id, {
    title: `"${item.fileOriginName}" 안에 폴더 만들기`,
    successMessage: `"${item.fileOriginName}" 안에 폴더를 만들었습니다.`,
  })
}

async function promptCreateFolderFromTree(node) {
  if (!node?.id || node.lockedFile || node.systemManaged) {
    setMessages('', '잠긴 폴더에는 새 폴더를 만들 수 없습니다. 먼저 잠금을 해제해 주세요.')
    return
  }

  await openFolderDialog(Number(node.id), {
    title: `"${node.fileOriginName}" 안에 폴더 만들기`,
    successMessage: `"${node.fileOriginName}" 안에 폴더를 만들었습니다.`,
  })
}

async function openFolderDialog(parentId = null, options = {}) {
  folderDialog.open = true
  folderDialog.title = options.title || '새 폴더 만들기'
  folderDialog.folderName = ''
  folderDialog.folders = []
  folderDialog.targetParentId = parentId == null ? '' : String(parentId)
  folderDialog.loading = true
  folderDialog.saving = false
  folderDialog.successMessage = options.successMessage || '폴더를 만들었습니다.'
  setMessages()

  try {
    folderDialog.folders = await fetchDriveFolderDestinations()
    const selected = folderDialogDestinationOptions.value.find((option) => option.value === folderDialog.targetParentId)
    if (!selected || selected.disabled) {
      folderDialog.targetParentId = ''
    }
  } catch (error) {
    setMessages('', error.message)
    closeFolderDialog()
  } finally {
    folderDialog.loading = false
  }
}

function closeFolderDialog() {
  folderDialog.open = false
  folderDialog.title = '새 폴더 만들기'
  folderDialog.folderName = ''
  folderDialog.folders = []
  folderDialog.targetParentId = ''
  folderDialog.loading = false
  folderDialog.saving = false
  folderDialog.successMessage = '폴더를 만들었습니다.'
}

async function confirmFolderDialog() {
  if (!canConfirmFolderDialog.value) {
    setMessages('', selectedFolderDialogDestination.value?.disabled ? '잠긴 폴더에는 새 폴더를 만들 수 없습니다.' : '폴더 이름과 위치를 확인해 주세요.')
    return
  }

  const targetParentId = folderDialog.targetParentId === '' ? null : Number(folderDialog.targetParentId)
  const successMessage = folderDialog.successMessage
  folderDialog.saving = true

  try {
    await createDriveFolder({
      folderName: folderDialog.folderName.trim(),
      parentId: targetParentId,
    })
    closeFolderDialog()
    setMessages(successMessage)
    activeTab.value = 'drive'
    pageFilters.parentId = targetParentId
    pageFilters.page = 0
    invalidateMoveDestinations()
    await Promise.all([loadDrivePage(), loadHomeSummary(), loadRecentFiles()])
  } catch (error) {
    setMessages('', error.message)
  } finally {
    folderDialog.saving = false
  }
}

async function promptRename(item) {
  if (!canModifyOwnedItem(item)) {
    setMessages('', '잠긴 항목은 이름을 바꿀 수 없습니다. 먼저 잠금을 해제해 주세요.')
    return
  }
  const nextName = window.prompt('새 이름을 입력해주세요.', item.fileOriginName)
  if (!nextName || nextName === item.fileOriginName) {
    return
  }

  try {
    await renameDriveItem(item.id, nextName)
    setMessages('이름을 변경했습니다.')
    if (isFolder(item)) {
      invalidateMoveDestinations()
    }
    await refreshVisibleData()
  } catch (error) {
    setMessages('', error.message)
  }
}

async function toggleItemLock(item) {
  if (!canToggleLock(item)) {
    return
  }

  const nextLocked = !isLockedItem(item)
  try {
    await updateDriveItemLock(item.id, nextLocked)
    setMessages(nextLocked ? '항목을 잠갔습니다.' : '항목 잠금을 해제했습니다.')
    await refreshVisibleData()
    selectedIds.value = [String(item.id)]
  } catch (error) {
    setMessages('', error.message)
  }
}

async function moveItemToTrash(item) {
  if (!canModifyOwnedItem(item)) {
    setMessages('', '잠긴 항목은 휴지통으로 이동할 수 없습니다. 먼저 잠금을 해제해 주세요.')
    return
  }
  if (!window.confirm(`"${item.fileOriginName}" 항목을 휴지통으로 이동할까요?`)) {
    return
  }

  try {
    await moveDriveItemToTrash(item.id)
    setMessages('휴지통으로 이동했습니다.')
    if (isFolder(item)) {
      invalidateMoveDestinations()
    }
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
  if (!canMoveSelectedItems.value) {
    setMessages('', '선택 항목에 잠긴 항목이 있습니다. 잠금을 해제한 뒤 이동해 주세요.')
    return
  }
  if (!window.confirm(`선택한 ${selectedItems.value.length}개 항목을 휴지통으로 이동할까요?`)) {
    return
  }

  try {
    await Promise.all(selectedItems.value.map((item) => moveDriveItemToTrash(item.id)))
    setMessages('선택한 항목을 휴지통으로 이동했습니다.')
    if (selectedItems.value.some((item) => isFolder(item))) {
      invalidateMoveDestinations()
    }
    await Promise.all([loadDrivePage(), loadHomeSummary(), loadRecentFiles(), loadTrashFiles()])
    clearSelection()
  } catch (error) {
    setMessages('', error.message)
  }
}

async function downloadSelectedItems() {
  if (!canDownloadSelectedItems.value) {
    return
  }

  try {
    await downloadDriveItems(selectedItems.value.map((item) => item.id))
    setMessages('선택 항목 다운로드를 시작했습니다.')
  } catch (error) {
    setMessages('', error.message)
  }
}

async function restoreTrashItem(item) {
  try {
    await restoreDriveItem(item.id)
    setMessages('휴지통에서 복구했습니다.')
    if (isFolder(item)) {
      invalidateMoveDestinations()
    }
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
    if (selectedItems.value.some((item) => isFolder(item))) {
      invalidateMoveDestinations()
    }
    await Promise.all([loadTrashFiles(), loadDrivePage(), loadHomeSummary()])
    clearSelection()
  } catch (error) {
    setMessages('', error.message)
  }
}

async function deleteItemPermanently(item) {
  if (isLockedItem(item)) {
    setMessages('', '잠긴 항목은 영구 삭제할 수 없습니다. 먼저 잠금을 해제해 주세요.')
    return
  }
  if (!window.confirm(`"${item.fileOriginName}" 항목을 완전히 삭제할까요?`)) {
    return
  }

  try {
    await deleteDriveItem(item.id)
    setMessages('항목을 완전히 삭제했습니다.')
    if (isFolder(item)) {
      invalidateMoveDestinations()
    }
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
  if (hasSelectedLockedItems.value) {
    setMessages('', '선택 항목에 잠긴 항목이 있습니다. 잠금을 해제한 뒤 삭제해 주세요.')
    return
  }
  if (!window.confirm(`선택한 ${selectedItems.value.length}개 항목을 완전히 삭제할까요?`)) {
    return
  }

  try {
    await Promise.all(selectedItems.value.map((item) => deleteDriveItem(item.id)))
    setMessages('선택한 항목을 완전히 삭제했습니다.')
    if (selectedItems.value.some((item) => isFolder(item))) {
      invalidateMoveDestinations()
    }
    await Promise.all([loadTrashFiles(), loadDrivePage(), loadHomeSummary()])
    clearSelection()
  } catch (error) {
    setMessages('', error.message)
  }
}

async function handleClearTrash() {
  if (trashFiles.value.some((item) => isLockedItem(item))) {
    setMessages('', '휴지통에 잠긴 항목이 있습니다. 잠금을 해제한 뒤 비워 주세요.')
    return
  }
  if (!window.confirm('휴지통의 모든 항목을 완전히 삭제할까요?')) {
    return
  }

  try {
    await clearDriveTrash()
    setMessages('휴지통을 비웠습니다.')
    invalidateMoveDestinations()
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

  if (!canMoveSelectedItems.value) {
    setMessages('', '선택 항목에 잠긴 항목이 있습니다. 잠금을 해제한 뒤 이동해 주세요.')
    return
  }

  await ensureMoveDestinationsLoaded()
  const selectedOption = moveDestinationOptions.value.find((option) => option.value === moveTargetId.value)
  if (!selectedOption || selectedOption.disabled) {
    setMessages('', '이동할 수 없는 위치입니다.')
    return
  }

  const targetParentId = moveTargetId.value === '' ? null : Number(moveTargetId.value)
  try {
    await moveDriveItems(selectedItems.value.map((item) => item.id), targetParentId)
    setMessages('선택한 항목을 이동했습니다.')
    invalidateMoveDestinations()
    await Promise.all([loadDrivePage(), loadHomeSummary()])
    clearSelection()
  } catch (error) {
    setMessages('', error.message)
  }
}

async function openMoveDialog(targets = selectedItems.value) {
  const nextTargets = (targets || []).filter((item) => item && canModifyOwnedItem(item))
  if (!nextTargets.length) {
    setMessages('', '이동할 수 있는 항목을 먼저 선택해 주세요.')
    return
  }

  moveDialog.open = true
  moveDialog.targets = nextTargets
  moveDialog.targetParentId = ''
  moveDialog.loading = true
  moveDialog.saving = false
  setMessages()

  try {
    moveDialog.folders = await fetchDriveFolderDestinations()
  } catch (error) {
    setMessages('', error.message)
    closeMoveDialog()
  } finally {
    moveDialog.loading = false
  }
}

function closeMoveDialog() {
  moveDialog.open = false
  moveDialog.targets = []
  moveDialog.folders = []
  moveDialog.targetParentId = ''
  moveDialog.loading = false
  moveDialog.saving = false
}

async function confirmMoveDialog() {
  const selectedOption = moveDialogDestinationOptions.value.find((option) => option.value === moveDialog.targetParentId)
  if (!selectedOption || selectedOption.disabled) {
    setMessages('', '이동할 수 없는 위치입니다.')
    return
  }

  const fileIds = moveDialog.targets.map((item) => item.id).filter(Boolean)
  if (!fileIds.length) {
    setMessages('', '이동할 항목이 없습니다.')
    return
  }

  moveDialog.saving = true
  try {
    const movedCount = fileIds.length
    const targetParentId = moveDialog.targetParentId === '' ? null : Number(moveDialog.targetParentId)
    await moveDriveItems(fileIds, targetParentId)
    closeMoveDialog()
    clearSelection()
    setMessages(`${movedCount}개 항목을 이동했습니다.`)
    invalidateMoveDestinations()
    await Promise.all([loadDrivePage(), loadHomeSummary(), loadRecentFiles()])
  } catch (error) {
    setMessages('', error.message)
  } finally {
    moveDialog.saving = false
  }
}

async function handleFilesSelected(event) {
  const files = Array.from(event.target.files || [])
  if (!files.length) {
    return
  }

  const hasFolderPaths = files.some((file) => getDriveUploadRelativePath(file).includes('/'))
  let activeUploadMeta = null
  uploadProgress.open = true
  uploadProgress.title = hasFolderPaths ? '폴더를 업로드하는 중입니다.' : '파일을 업로드하는 중입니다.'
  uploadProgress.description = hasFolderPaths
    ? '폴더 구조를 유지하며 CalenDrive 저장소로 전송하고 있습니다.'
    : '선택한 파일을 CalenDrive 저장소로 전송하고 있습니다.'
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
      uploadProgress.fileName = getDriveUploadRelativePath(file) || file.name

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
        relativePath: target.relativePath || getDriveUploadRelativePath(file),
        lastModified: target.lastModified ?? file.lastModified ?? null,
      })
    }

    setMessages(hasFolderPaths ? `${files.length}개 파일을 폴더 구조와 함께 업로드했습니다.` : `${files.length}개 파일을 업로드했습니다.`)
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
    uploadProgress.title = '파일을 업로드하는 중입니다.'
    uploadProgress.description = '선택한 파일을 CalenDrive 저장소로 전송하고 있습니다.'
    uploadProgress.percent = 0
    uploadProgress.fileName = ''
    event.target.value = ''
  }
}

async function openShareDialog(targets) {
  const normalizedTargets = (targets || []).filter((item) => item && canShareItem(item))
  if (!normalizedTargets.length) {
    if ((targets || []).some((item) => isLockedItem(item))) {
      setMessages('', '잠긴 파일은 공유할 수 없습니다. 먼저 잠금을 해제해 주세요.')
      return
    }
    setMessages('', '공유할 파일을 먼저 선택해주세요.')
    return
  }

  shareDialog.open = true
  shareDialog.targets = normalizedTargets
  shareDialog.existingRecipients = []
  shareDialog.downloadLinks = []
  shareDialog.searchQuery = ''
  shareDialog.searchResults = []
  shareDialog.selectedRecipientLoginId = ''
  shareDialog.loading = true
  shareDialog.saving = false
  shareDialog.linkSaving = false
  shareDialog.linkExpiresInMinutes = 60
  shareDialog.linkMaxDownloads = 10
  shareDialog.generatedDownloadLink = null
  shareDialog.revokingLinkId = null
  setMessages()

  try {
    if (normalizedTargets.length === 1) {
      const [recipients, downloadLinks] = await Promise.all([
        fetchDriveShareInfo(normalizedTargets[0].id),
        fetchDriveDownloadLinks(normalizedTargets[0].id),
      ])
      shareDialog.existingRecipients = recipients
      shareDialog.downloadLinks = downloadLinks.map(normalizeDownloadLink)
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
  shareDialog.downloadLinks = []
  shareDialog.searchQuery = ''
  shareDialog.searchResults = []
  shareDialog.selectedRecipientLoginId = ''
  shareDialog.loading = false
  shareDialog.saving = false
  shareDialog.linkSaving = false
  shareDialog.linkExpiresInMinutes = 60
  shareDialog.linkMaxDownloads = 10
  shareDialog.generatedDownloadLink = null
  shareDialog.revokingLinkId = null
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

function resolvePublicDownloadUrl(path) {
  if (!path) {
    return ''
  }
  try {
    return new URL(path, window.location.origin).href
  } catch (error) {
    return path
  }
}

function normalizeDownloadLink(link) {
  return {
    ...link,
    downloadUrl: resolvePublicDownloadUrl(link?.downloadUrl),
  }
}

async function createPublicDownloadLink() {
  const target = shareDialog.targets[0]
  if (!target?.id) {
    setMessages('', '다운로드 링크를 만들 파일을 먼저 선택해 주세요.')
    return
  }

  shareDialog.linkSaving = true
  try {
    const response = await createDriveDownloadLink(target.id, {
      expiresInMinutes: Number(shareDialog.linkExpiresInMinutes || 60),
      maxDownloads: Number(shareDialog.linkMaxDownloads || 10),
    })
    const normalized = normalizeDownloadLink(response)
    shareDialog.generatedDownloadLink = normalized
    shareDialog.downloadLinks = [
      normalized,
      ...shareDialog.downloadLinks.filter((link) => link.id !== normalized.id),
    ]
    setMessages('다운로드 링크를 만들었습니다.')
  } catch (error) {
    setMessages('', error.message)
  } finally {
    shareDialog.linkSaving = false
  }
}

async function revokePublicDownloadLink(link) {
  if (!link?.id || shareDialog.revokingLinkId) {
    return
  }

  shareDialog.revokingLinkId = link.id
  try {
    const response = await revokeDriveDownloadLink(link.id)
    const normalized = normalizeDownloadLink(response)
    shareDialog.downloadLinks = shareDialog.downloadLinks.map((candidate) =>
      candidate.id === normalized.id ? normalized : candidate,
    )
    if (shareDialog.generatedDownloadLink?.id === normalized.id) {
      shareDialog.generatedDownloadLink = normalized
    }
    setMessages('다운로드 링크를 비활성화했습니다.')
  } catch (error) {
    setMessages('', error.message)
  } finally {
    shareDialog.revokingLinkId = null
  }
}

async function copyPublicDownloadLink(link = shareDialog.generatedDownloadLink) {
  const url = link?.downloadUrl
  if (!url) {
    return
  }

  try {
    await navigator.clipboard.writeText(url)
    setMessages('다운로드 링크를 복사했습니다.')
  } catch (error) {
    window.prompt('다운로드 링크를 복사해 주세요.', url)
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
  await openSharedSaveDialog(item ? [item] : [])
}

async function saveSelectedSharedFiles() {
  await openSharedSaveDialog(selectedItems.value)
}

async function openSharedSaveDialog(targets = []) {
  const nextTargets = (targets || []).filter((item) => item?.fileId)
  if (!nextTargets.length) {
    setMessages('', '내 드라이브에 저장할 공유 파일을 선택해 주세요.')
    return
  }

  sharedSaveDialog.open = true
  sharedSaveDialog.targets = nextTargets
  sharedSaveDialog.folders = []
  sharedSaveDialog.targetParentId = ''
  sharedSaveDialog.loading = true
  sharedSaveDialog.saving = false
  setMessages()

  try {
    sharedSaveDialog.folders = await fetchDriveFolderDestinations()
  } catch (error) {
    setMessages('', error.message)
    closeSharedSaveDialog()
  } finally {
    sharedSaveDialog.loading = false
  }
}

function closeSharedSaveDialog() {
  sharedSaveDialog.open = false
  sharedSaveDialog.targets = []
  sharedSaveDialog.folders = []
  sharedSaveDialog.targetParentId = ''
  sharedSaveDialog.loading = false
  sharedSaveDialog.saving = false
}

async function confirmSharedSaveDialog() {
  const selectedOption = sharedSaveDestinationOptions.value.find((option) => option.value === sharedSaveDialog.targetParentId)
  if (!selectedOption || selectedOption.disabled) {
    setMessages('', selectedOption?.disabled ? '잠긴 폴더에는 공유 파일을 저장할 수 없습니다.' : '저장 위치를 확인해 주세요.')
    return
  }

  const targetFileIds = sharedSaveDialog.targets.map((item) => item.fileId).filter(Boolean)
  if (!targetFileIds.length) {
    setMessages('', '저장할 공유 파일이 없습니다.')
    return
  }

  sharedSaveDialog.saving = true
  try {
    const saveCount = targetFileIds.length
    const targetParentId = sharedSaveDialog.targetParentId === '' ? null : Number(sharedSaveDialog.targetParentId)
    await Promise.all(targetFileIds.map((fileId) => saveSharedDriveFile(fileId, targetParentId)))
    closeSharedSaveDialog()
    activeTab.value = 'drive'
    pageFilters.parentId = targetParentId
    pageFilters.page = 0
    clearSelection()
    setMessages(saveCount === 1 ? '공유 파일을 내 드라이브로 저장했습니다.' : `${saveCount}개 공유 파일을 내 드라이브로 저장했습니다.`)
    await Promise.all([loadSharedData(), loadDrivePage(), loadHomeSummary(), loadRecentFiles()])
  } catch (error) {
    setMessages('', error.message)
  } finally {
    sharedSaveDialog.saving = false
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

function resetVersionDrawerMessages() {
  versionDrawer.errorMessage = ''
  versionDrawer.successMessage = ''
}

function closeDriveVersionDrawer() {
  if (versionDrawer.loading || versionDrawer.restoringId) {
    return
  }
  versionDrawer.open = false
  versionDrawer.item = null
  versionDrawer.versions = []
  resetVersionDrawerMessages()
}

async function openDriveVersionDrawer(item) {
  if (!canOpenVersionDrawer(item)) {
    return
  }
  versionDrawer.open = true
  versionDrawer.item = item
  versionDrawer.versions = []
  resetVersionDrawerMessages()
  await loadDriveVersions(item)
}

async function loadDriveVersions(item = versionDrawer.item) {
  if (!item?.id) {
    return
  }
  versionDrawer.loading = true
  versionDrawer.errorMessage = ''

  try {
    const response = await fetchDriveFileVersions(item.id)
    versionDrawer.versions = Array.isArray(response) ? response : []
  } catch (error) {
    versionDrawer.errorMessage = error.message
    versionDrawer.versions = []
  } finally {
    versionDrawer.loading = false
  }
}

async function restoreDriveVersion(version) {
  if (!versionDrawer.item?.id || !version?.id || versionDrawer.restoringId) {
    return
  }

  const confirmed = window.confirm(`Restore version ${version.versionNumber || version.id} for ${versionDrawer.item.fileOriginName || 'this file'}?`)
  if (!confirmed) {
    return
  }

  resetVersionDrawerMessages()
  versionDrawer.restoringId = version.id

  try {
    const restored = await restoreDriveFileVersion(versionDrawer.item.id, version.id)
    versionDrawer.item = restored || versionDrawer.item
    versionDrawer.successMessage = 'Selected file version restored. A RESTORE version entry was recorded.'
    setMessages('Selected file version restored.', '')
    await loadDriveVersions(versionDrawer.item)
  } catch (error) {
    versionDrawer.errorMessage = error.message
  } finally {
    versionDrawer.restoringId = null
  }
}

function formatVersionSource(source) {
  const normalized = String(source || '').toUpperCase()
  if (normalized === 'RESTORE') {
    return 'Restore'
  }
  if (normalized === 'UPLOAD') {
    return 'Upload'
  }
  return source || '-'
}
onMounted(() => {
  document.addEventListener('click', closeContextMenu)
  window.addEventListener('keydown', handleDriveKeyboard)
  loadActiveTab()
  ensureMoveDestinationsLoaded()
})

onBeforeUnmount(() => {
  document.removeEventListener('click', closeContextMenu)
  window.removeEventListener('keydown', handleDriveKeyboard)
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

      <section v-if="!sidebarCollapsed" class="drive-sidebar__folders">
        <div class="drive-sidebar__folders-head">
          <strong>폴더</strong>
          <button class="drive-sidebar__folders-refresh" type="button" :disabled="moveDestinationLoading" @click="reloadMoveDestinations">
            {{ moveDestinationLoading ? '갱신 중' : '새로고침' }}
          </button>
        </div>
        <div class="drive-folder-tree">
          <div
            class="drive-folder-tree__row"
            :class="{
              'drive-folder-tree__row--active': isDriveRootActive,
              'drive-folder-tree__row--drop-target': isDriveRootMoveDropTarget(),
            }"
            @dragover="handleDriveRootDragOver"
            @dragleave="handleDriveRootDragLeave"
            @drop="handleDriveRootDrop"
          >
            <span class="drive-folder-tree__spacer"></span>
            <button class="drive-folder-tree__name" type="button" @click="openFolderTreeRoot">
              <span class="drive-folder-tree__icon">드</span>
              <span>내 드라이브</span>
            </button>
            <button class="drive-folder-tree__action" type="button" title="루트에 폴더 만들기" @click="openFolderDialog(null)">
              +
            </button>
          </div>

          <p v-if="moveDestinationLoading && !folderTreeVisibleNodes.length" class="drive-folder-tree__empty">
            폴더를 불러오고 있습니다.
          </p>
          <p v-else-if="!folderTreeVisibleNodes.length" class="drive-folder-tree__empty">
            아직 만든 폴더가 없습니다.
          </p>

          <div
            v-for="node in folderTreeVisibleNodes"
            :key="`folder-tree-${node.id}`"
            class="drive-folder-tree__row"
            :class="{
              'drive-folder-tree__row--active': node.active,
              'drive-folder-tree__row--locked': node.lockedFile || node.systemManaged,
              'drive-folder-tree__row--drop-target': isDriveMoveDropTarget(node),
            }"
            :style="{ paddingLeft: `${Math.min(node.depth, 5) * 12}px` }"
            @dragover="handleDriveFolderDragOver(node, $event)"
            @dragleave="handleDriveFolderDragLeave(node, $event)"
            @drop="handleDriveFolderDrop(node, $event)"
          >
            <button
              class="drive-folder-tree__toggle"
              type="button"
              :disabled="!node.hasChildren"
              :aria-label="node.expanded ? '폴더 접기' : '폴더 펼치기'"
              @click="toggleFolderTreeNode(node)"
            >
              {{ node.hasChildren ? (node.expanded ? '⌄' : '›') : '' }}
            </button>
            <button class="drive-folder-tree__name" type="button" @click="openFolderTreeNode(node)">
              <span class="drive-folder-tree__icon">폴</span>
              <span>{{ node.fileOriginName }}</span>
            </button>
            <button
              class="drive-folder-tree__action"
              type="button"
              :disabled="node.lockedFile || node.systemManaged"
              title="하위 폴더 만들기"
              @click="promptCreateFolderFromTree(node)"
            >
              +
            </button>
          </div>
        </div>
      </section>

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
        <div class="drive-topbar__identity">
          <span>CALEN DRIVE</span>
          <strong>{{ normalizedCurrentLocationLabel }}</strong>
        </div>
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
          <label class="button button--primary drive-upload-button">
            파일 업로드
            <input type="file" multiple @change="handleFilesSelected" />
          </label>
          <label class="button button--ghost drive-upload-button">
            폴더 업로드
            <input type="file" multiple webkitdirectory directory @change="handleFilesSelected" />
          </label>
          <button class="button button--ghost" type="button" @click="promptCreateFolder">새 폴더</button>
          <button class="button button--ghost" type="button" @click="loadActiveTab">새로고침</button>
          <button class="drive-topbar__profile" type="button" @click="openProfileModal">
            <img
              v-if="currentProfileImage"
              :src="currentProfileImage"
              :alt="currentProfileName"
              class="drive-shell__avatar"
            />
            <span v-else class="drive-shell__avatar drive-shell__avatar--placeholder">
              {{ currentProfileName.slice(0, 1) }}
            </span>
            <span class="drive-topbar__profile-copy">
              <strong>{{ currentProfileName }}</strong>
              <small>{{ currentProfileRole === 'ADMIN' ? '관리자' : '사용자' }}</small>
            </span>
          </button>
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
                @click="openBrowserItem(item)"
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
                  <span v-if="isLockedItem(item)" class="drive-lock-badge">잠금</span>
                  <small>{{ formatBytes(item.fileSize) }}</small>
                  <small>{{ formatTimestamp(item.lastModifyDate || item.uploadDate) }}</small>
                </div>
              </article>
            </div>
            <p v-else class="panel__empty">최근 파일이 없습니다. 업로드 후 홈에서 최근 작업이 표시됩니다.</p>
          </section>
        </section>

        <section v-else-if="canShowBrowser" class="workspace-stack">
          <section
            class="panel drive-browser-panel"
            :class="{ 'drive-browser-panel--drop-active': isDriveDropActive }"
            @dragenter.prevent="handleDriveDragEnter"
            @dragover.prevent
            @dragleave.prevent="handleDriveDragLeave"
            @drop.prevent="handleDriveDrop"
          >
            <div class="panel__header">
              <div>
                <h3 v-if="activeTab === 'drive'">내 드라이브</h3>
                <h3 v-else-if="activeTab === 'recent'">최근 파일</h3>
                <h3 v-else-if="activeTab === 'trash'">휴지통</h3>
                <h3 v-else>받은 공유 파일</h3>
              </div>
              <div class="drive-toolbar__actions" v-if="activeTab === 'drive'">
                <div class="drive-breadcrumbs">
                  <button
                    class="button button--ghost"
                    :class="{ 'drive-breadcrumbs__item--drop-target': isDriveRootMoveDropTarget() }"
                    type="button"
                    @click="navigateToBreadcrumb(null)"
                    @dragover="handleDriveRootDragOver"
                    @dragleave="handleDriveRootDragLeave"
                    @drop="handleDriveRootDrop"
                  >
                    홈
                  </button>
                  <button
                    v-for="crumb in breadcrumbs"
                    :key="crumb.id"
                    class="button button--ghost"
                    :class="{ 'drive-breadcrumbs__item--drop-target': isDriveMoveDropTarget(crumb) }"
                    type="button"
                    @click="navigateToBreadcrumb(crumb.id)"
                    @dragover="handleDriveFolderDragOver(crumb, $event)"
                    @dragleave="handleDriveFolderDragLeave(crumb, $event)"
                    @drop="handleDriveFolderDrop(crumb, $event)"
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

            <div class="drive-browser-actions">
              <div>
                <strong>{{ selectedIds.length ? selectedIds.length + '개 선택' : normalizedCurrentLocationLabel }}</strong>
              </div>
              <button class="button button--ghost" type="button" @click="toggleDetailsPanel">
                {{ detailsPanelOpen ? '상세 닫기' : '상세 보기' }}
              </button>
            </div>

            <div v-if="isDriveDropActive" class="drive-drop-overlay">
              <strong>여기에 놓아 업로드</strong>
              <span>파일과 폴더 구조가 현재 위치에 저장됩니다.</span>
            </div>

            <div
              v-if="contextMenu.open && contextMenu.item"
              class="drive-context-menu"
              :style="{ left: `${contextMenu.x}px`, top: `${contextMenu.y}px` }"
              @click.stop
              @contextmenu.prevent
            >
              <button type="button" @click="runContextAction(openBrowserItem)">열기</button>
              <button v-if="canPreviewItem(contextMenu.item)" type="button" @click="runContextAction(openPreviewDialog)">미리보기</button>
              <button
                v-if="canShareItem(contextMenu.item)"
                type="button"
                @click="runContextAction((item) => openShareDialog([item]))"
              >
                공유
              </button>
              <button
                v-if="canToggleLock(contextMenu.item)"
                type="button"
                @click="runContextAction(toggleItemLock)"
              >
                {{ isLockedItem(contextMenu.item) ? '잠금 해제' : '잠금' }}
              </button>
              <button
                v-if="isFolder(contextMenu.item) && canModifyOwnedItem(contextMenu.item)"
                type="button"
                @click="runContextAction(promptCreateFolderInside)"
              >
                하위 폴더 만들기
              </button>
              <button
                v-if="canModifyOwnedItem(contextMenu.item)"
                type="button"
                @click="runContextAction(promptRename)"
              >
                이름 변경
              </button>
              <button
                v-if="canModifyOwnedItem(contextMenu.item)"
                type="button"
                @click="runContextAction((item) => openMoveDialog([item]))"
              >
                위치 이동
              </button>
              <button
                v-if="canModifyOwnedItem(contextMenu.item)"
                type="button"
                @click="runContextAction(moveItemToTrash)"
              >
                휴지통으로 이동
              </button>
              <button v-if="activeTab === 'trash'" type="button" @click="runContextAction(restoreTrashItem)">복구</button>
              <button v-if="activeTab === 'trash' && !isLockedItem(contextMenu.item)" type="button" @click="runContextAction(deleteItemPermanently)">완전 삭제</button>
              <button v-if="activeTab === 'shared'" type="button" @click="runContextAction(handleSaveSharedFile)">내 드라이브에 저장</button>
            </div>

            <div class="drive-browser-layout" :class="{ 'drive-browser-layout--details-closed': !detailsPanelOpen }">
              <div class="drive-browser-content">
            <div v-if="canShowSelectionBar" class="drive-selection-bar">
              <label class="checkbox-row drive-selection-bar__toggle">
                <input :checked="allCurrentSelected" type="checkbox" @change="toggleSelectAllCurrent" />
                <span>현재 화면 전체 선택</span>
              </label>
              <div class="drive-selection-bar__actions">
                <span class="drive-selection-bar__hint">선택 {{ selectedIds.length }}개</span>
                <span v-if="hasSelectedLockedItems" class="drive-lock-badge">잠긴 항목 포함</span>
                <template v-if="activeTab === 'drive' || activeTab === 'recent'">
                  <select
                    v-model="moveTargetId"
                    class="drive-selection-bar__select"
                    @click="ensureMoveDestinationsLoaded"
                    @focus="ensureMoveDestinationsLoaded"
                  >
                    <option v-if="moveDestinationLoading" disabled value="">폴더를 불러오는 중...</option>
                    <option
                      v-for="option in moveDestinationOptions"
                      :key="`dest-${option.value}`"
                      :disabled="option.disabled"
                      :value="option.value"
                    >
                      {{ option.label }}
                    </option>
                  </select>
                  <button class="button button--ghost" type="button" :disabled="!canDownloadSelectedItems" @click="downloadSelectedItems">다운로드</button>
                  <button class="button button--ghost" type="button" :disabled="!canMoveSelectedItems" @click="moveSelectedItemsToFolder">이동</button>
                  <button class="button button--ghost" type="button" :disabled="!canMoveSelectedItems" @click="openMoveDialog(selectedItems)">위치 선택</button>
                  <button class="button button--ghost" type="button" :disabled="!selectedShareableItems.length" @click="openShareDialog(selectedShareableItems)">공유</button>
                  <button class="button button--ghost" type="button" :disabled="!canMoveSelectedItems" @click="moveSelectedToTrash">휴지통</button>


</template>
                <template v-else-if="activeTab === 'trash'">
                  <button class="button button--ghost" type="button" :disabled="!selectedIds.length" @click="restoreSelectedFromTrash">복구</button>
                  <button class="button button--ghost" type="button" :disabled="!selectedIds.length || hasSelectedLockedItems" @click="deleteSelectedPermanently">완전 삭제</button>
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
                  <tr
                    v-for="item in browserItems"
                    :key="getSelectableId(item)"
                    class="drive-table__row"
                    :class="{
                      'drive-table__row--selected': selectedIds.includes(getSelectableId(item)),
                      'drive-table__row--draggable': canDragDriveItem(item),
                      'drive-table__row--drop-target': isDriveMoveDropTarget(item),
                    }"
                    :draggable="canDragDriveItem(item)"
                    @click="selectItemFromPointer(item, $event)"
                    @dblclick="openBrowserItem(item)"
                    @contextmenu.prevent="openContextMenu(item, $event)"
                    @dragstart="handleDriveItemDragStart(item, $event)"
                    @dragend="handleDriveItemDragEnd"
                    @dragover="handleDriveFolderDragOver(item, $event)"
                    @dragleave="handleDriveFolderDragLeave(item, $event)"
                    @drop="handleDriveFolderDrop(item, $event)"
                  >
                    <td>
                      <input :checked="selectedIds.includes(getSelectableId(item))" type="checkbox" @click.stop @change="toggleSelection(item)" />
                    </td>
                    <td class="drive-table__name">
                      <button
                        class="drive-table__item"
                        type="button"
                        @click.stop="openBrowserItem(item)"
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
                          <span v-if="isLockedItem(item)" class="drive-lock-badge">잠금</span>
                          <small>{{ item.ownerDisplayName || item.ownerLoginId || '내 드라이브' }}</small>
                          <small v-if="pageFilters.searchQuery && item.folderPath" class="drive-location-hint">위치: {{ item.folderPath }}</small>
                        </span>
                      </button>
                    </td>
                    <td>{{ itemTypeLabel(item) }}</td>
                    <td>{{ item.ownerDisplayName || item.ownerLoginId || '-' }}</td>
                    <td>{{ isFolder(item) ? '-' : formatBytes(item.fileSize) }}</td>
                    <td>{{ formatTimestamp(item.deletedAt || item.lastModifyDate || item.uploadDate || item.sharedAt) }}</td>
                    <td>
                      <div class="sheet-table__actions" @click.stop>
                        <button class="button button--ghost" type="button" @click="openBrowserItem(item)">열기</button>
                        <button v-if="canOpenItemLocation(item)" class="button button--ghost" type="button" @click="openItemLocation(item)">위치 열기</button>
                        <button v-if="canModifyOwnedItem(item)" class="button button--ghost" type="button" @click="promptRename(item)">이름 변경</button>
                        <button v-if="canToggleLock(item)" class="button button--ghost" type="button" @click="toggleItemLock(item)">{{ isLockedItem(item) ? '잠금 해제' : '잠금' }}</button>
                        <button v-if="canShareItem(item)" class="button button--ghost" type="button" @click="openShareDialog([item])">공유</button>
                        <button v-if="canModifyOwnedItem(item)" class="button button--ghost" type="button" @click="openMoveDialog([item])">이동</button>
                        <button v-if="canModifyOwnedItem(item)" class="button button--ghost" type="button" @click="moveItemToTrash(item)">휴지통</button>
                        <button v-if="activeTab === 'trash'" class="button button--ghost" type="button" @click="restoreTrashItem(item)">복구</button>
                        <button v-if="activeTab === 'trash' && !isLockedItem(item)" class="button button--ghost" type="button" @click="deleteItemPermanently(item)">완전 삭제</button>
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
                :class="{
                  'drive-file-card--selected': selectedIds.includes(getSelectableId(item)),
                  'drive-file-card--locked': isLockedItem(item),
                  'drive-file-card--draggable': canDragDriveItem(item),
                  'drive-file-card--drop-target': isDriveMoveDropTarget(item),
                }"
                :aria-selected="selectedIds.includes(getSelectableId(item))"
                :draggable="canDragDriveItem(item)"
                @click="selectItemFromPointer(item, $event)"
                @dblclick="openBrowserItem(item)"
                @contextmenu.prevent="openContextMenu(item, $event)"
                @dragstart="handleDriveItemDragStart(item, $event)"
                @dragend="handleDriveItemDragEnd"
                @dragover="handleDriveFolderDragOver(item, $event)"
                @dragleave="handleDriveFolderDragLeave(item, $event)"
                @drop="handleDriveFolderDrop(item, $event)"
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
                  <span v-if="isLockedItem(item)" class="drive-lock-badge">잠금</span>
                  <small>{{ isFolder(item) ? '폴더' : formatBytes(item.fileSize) }}</small>
                  <small v-if="pageFilters.searchQuery && item.folderPath" class="drive-location-hint">위치: {{ item.folderPath }}</small>
                  <small>{{ formatTimestamp(item.deletedAt || item.lastModifyDate || item.uploadDate || item.sharedAt) }}</small>
                </div>
                <div class="drive-file-card__actions" @click.stop>
                  <button class="button button--ghost" type="button" @click="openBrowserItem(item)">열기</button>
                  <button v-if="canOpenItemLocation(item)" class="button button--ghost" type="button" @click="openItemLocation(item)">위치 열기</button>
                  <button v-if="canShareItem(item)" class="button button--ghost" type="button" @click="openShareDialog([item])">공유</button>
                  <button v-if="canToggleLock(item)" class="button button--ghost" type="button" @click="toggleItemLock(item)">{{ isLockedItem(item) ? '잠금 해제' : '잠금' }}</button>
                  <button v-if="canModifyOwnedItem(item)" class="button button--ghost" type="button" @click="openMoveDialog([item])">이동</button>
                  <button v-if="canModifyOwnedItem(item)" class="button button--ghost" type="button" @click="moveItemToTrash(item)">휴지통</button>
                  <button v-if="activeTab === 'trash'" class="button button--ghost" type="button" @click="restoreTrashItem(item)">복구</button>
                  <button v-if="activeTab === 'trash' && !isLockedItem(item)" class="button button--ghost" type="button" @click="deleteItemPermanently(item)">삭제</button>
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
              </div>

              <aside v-if="detailsPanelOpen" class="drive-details-panel">
                <div class="drive-details-panel__head">
                  <div>
                    <span>상세</span>
                    <strong>{{ selectedItems.length > 1 ? selectedItems.length + '개 항목' : (primarySelectedItem?.fileOriginName || '항목 선택') }}</strong>
                  </div>
                  <button class="button button--ghost" type="button" @click="toggleDetailsPanel">닫기</button>
                </div>

                <template v-if="selectedItems.length > 1">
                  <div class="drive-details-panel__preview drive-details-panel__preview--stack">
                    {{ selectedItems.length }}
                  </div>
                  <dl class="drive-details-panel__meta">
                    <template v-for="row in detailRows" :key="row.label">
                      <dt>{{ row.label }}</dt>
                      <dd>{{ row.value }}</dd>


</template>
                  </dl>


</template>

                <template v-else-if="primarySelectedItem">
                  <img
                    v-if="itemPreviewType(primarySelectedItem) === 'image'"
                    class="drive-details-panel__preview"
                    :src="itemThumbnailPath(primarySelectedItem)"
                    :alt="primarySelectedItem.fileOriginName"
                    loading="lazy"
                    decoding="async"
                  />
                  <div v-else class="drive-details-panel__preview drive-details-panel__preview--placeholder">
                    {{ itemPreviewType(primarySelectedItem) === 'folder' ? '폴더' : itemTypeLabel(primarySelectedItem) }}
                  </div>

                  <dl class="drive-details-panel__meta">
                    <template v-for="row in detailRows" :key="row.label">
                      <dt>{{ row.label }}</dt>
                      <dd>{{ row.value }}</dd>


</template>
                  </dl>

                  <div class="drive-details-panel__actions">
                    <button class="button button--primary" type="button" @click="openBrowserItem(primarySelectedItem)">열기</button>
                    <button v-if="canOpenItemLocation(primarySelectedItem)" class="button button--ghost" type="button" @click="openItemLocation(primarySelectedItem)">위치 열기</button>
                    <button v-if="canPreviewItem(primarySelectedItem)" class="button button--ghost" type="button" @click="openPreviewDialog(primarySelectedItem)">미리보기</button>
                    <button v-if="canShareItem(primarySelectedItem)" class="button button--ghost" type="button" @click="openShareDialog([primarySelectedItem])">공유</button>
                    <button v-if="canToggleLock(primarySelectedItem)" class="button button--ghost" type="button" @click="toggleItemLock(primarySelectedItem)">{{ isLockedItem(primarySelectedItem) ? '잠금 해제' : '잠금' }}</button>
                    <button v-if="canModifyOwnedItem(primarySelectedItem)" class="button button--ghost" type="button" @click="promptRename(primarySelectedItem)">이름 변경</button>
                    <button v-if="canModifyOwnedItem(primarySelectedItem)" class="button button--ghost" type="button" @click="openMoveDialog([primarySelectedItem])">위치 이동</button>
                    <button v-if="canModifyOwnedItem(primarySelectedItem)" class="button button--ghost" type="button" @click="moveItemToTrash(primarySelectedItem)">휴지통</button>
                    <button v-if="activeTab === 'trash'" class="button button--ghost" type="button" @click="restoreTrashItem(primarySelectedItem)">복구</button>
                    <button v-if="activeTab === 'trash' && !isLockedItem(primarySelectedItem)" class="button button--ghost" type="button" @click="deleteItemPermanently(primarySelectedItem)">완전 삭제</button>
                    <button v-if="activeTab === 'shared'" class="button button--ghost" type="button" @click="handleSaveSharedFile(primarySelectedItem)">내 드라이브에 저장</button>
                  </div>


</template>

                <div v-else class="drive-details-panel__empty">
                  <strong>파일을 선택하세요</strong>
                  <span>목록에서 항목을 선택하면 미리보기, 위치, 용량과 빠른 작업이 표시됩니다.</span>
                </div>
              </aside>
            </div>
          </section>
        </section>

        <section v-else-if="activeTab === 'shared' && sharedTab === 'sent'" class="workspace-stack">
          <section class="panel">
            <div class="panel__header">
              <div>
                <h3>보낸 공유 관리</h3>
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

        <div v-if="previewDialog.open && previewDialog.item" class="travel-modal drive-preview-modal" @click.self="closePreviewDialog">
          <div class="travel-modal__dialog drive-preview-modal__dialog">
            <div class="travel-modal__header">
              <div>
                <h2>{{ previewDialog.item.fileOriginName }}</h2>
                <p>{{ itemTypeLabel(previewDialog.item) }} · {{ formatBytes(previewDialog.item.fileSize) }}</p>
              </div>
              <div class="drive-preview-modal__actions">
                <button class="button button--ghost" type="button" @click="openItemInNewWindow(previewDialog.item)">새 창</button>
                <button class="button button--ghost" type="button" @click="closePreviewDialog">닫기</button>
              </div>
            </div>
            <div class="travel-modal__body drive-preview-modal__body">
              <img
                v-if="itemPreviewType(previewDialog.item) === 'image'"
                :src="itemDownloadPath(previewDialog.item)"
                :alt="previewDialog.item.fileOriginName"
              />
              <video
                v-else-if="itemPreviewType(previewDialog.item) === 'video'"
                :src="itemDownloadPath(previewDialog.item)"
                controls
                playsinline
              ></video>
              <iframe
                v-else-if="itemPreviewType(previewDialog.item) === 'pdf'"
                class="drive-preview-modal__frame"
                :src="itemDownloadPath(previewDialog.item)"
                :title="previewDialog.item.fileOriginName"
              ></iframe>
              <iframe
                v-else-if="itemPreviewType(previewDialog.item) === 'text'"
                class="drive-preview-modal__frame drive-preview-modal__frame--text"
                :src="itemDownloadPath(previewDialog.item)"
                :title="previewDialog.item.fileOriginName"
              ></iframe>
            </div>
          </div>
        </div>

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

        <div v-if="folderDialog.open" class="travel-modal" @click.self="closeFolderDialog">
          <div class="travel-modal__dialog drive-folder-modal">
            <div class="travel-modal__header">
              <div>
                <h2>{{ folderDialog.title }}</h2>
              </div>
              <button class="button button--ghost" type="button" @click="closeFolderDialog">닫기</button>
            </div>
            <div class="travel-modal__body drive-folder-modal__body">
              <section class="panel panel--compact">
                <div class="panel__header">
                  <div>
                    <h3>폴더 정보</h3>
                  </div>
                </div>
                <label class="field">
                  <span class="field__label">폴더 이름</span>
                  <input
                    v-model="folderDialog.folderName"
                    class="drive-input"
                    type="text"
                    maxlength="255"
                    placeholder="예: 2026 일본 여행"
                    @keyup.enter="confirmFolderDialog"
                  />
                </label>
                <div class="drive-folder-modal__preview">
                  <span>생성 위치</span>
                  <strong>{{ selectedFolderDialogDestination?.path || '내 드라이브' }}</strong>
                </div>
              </section>

              <section class="panel panel--compact">
                <div class="panel__header">
                  <div>
                    <h3>위치 선택</h3>
                  </div>
                </div>
                <div v-if="folderDialog.loading" class="panel__empty">폴더 목록을 불러오는 중입니다.</div>
                <div v-else class="drive-move-destination-list">
                  <button
                    v-for="option in folderDialogDestinationOptions"
                    :key="`folder-dest-${option.value || 'root'}`"
                    class="drive-move-destination"
                    :class="{ 'drive-move-destination--active': folderDialog.targetParentId === option.value }"
                    type="button"
                    :disabled="option.disabled"
                    @click="folderDialog.targetParentId = option.value"
                  >
                    <span class="drive-move-destination__icon">{{ option.value ? 'DIR' : 'ROOT' }}</span>
                    <span class="drive-move-destination__copy">
                      <strong>{{ option.label }}</strong>
                      <small>{{ option.path }}{{ option.disabled ? ' · 잠김' : '' }}</small>
                    </span>
                  </button>
                </div>
                <div class="entry-editor__actions">
                  <button class="button button--ghost" type="button" @click="closeFolderDialog">취소</button>
                  <button class="button button--primary" type="button" :disabled="!canConfirmFolderDialog" @click="confirmFolderDialog">
                    {{ folderDialog.saving ? '생성 중' : '폴더 만들기' }}
                  </button>
                </div>
              </section>
            </div>
          </div>
        </div>

        <div v-if="moveDialog.open" class="travel-modal" @click.self="closeMoveDialog">
          <div class="travel-modal__dialog drive-move-modal">
            <div class="travel-modal__header">
              <div>
                <h2>위치 이동</h2>
              </div>
              <button class="button button--ghost" type="button" @click="closeMoveDialog">닫기</button>
            </div>
            <div class="travel-modal__body drive-move-modal__body">
              <section class="panel panel--compact">
                <div class="panel__header">
                  <div>
                    <h3>이동 대상</h3>
                  </div>
                </div>
                <div v-if="moveDialog.loading" class="panel__empty">폴더 목록을 불러오는 중입니다.</div>
                <div v-else class="drive-move-destination-list">
                  <button
                    v-for="option in moveDialogDestinationOptions"
                    :key="`move-dest-${option.value || 'root'}`"
                    class="drive-move-destination"
                    :class="{ 'drive-move-destination--active': moveDialog.targetParentId === option.value }"
                    type="button"
                    :disabled="option.disabled"
                    @click="moveDialog.targetParentId = option.value"
                  >
                    <span class="drive-move-destination__icon">{{ option.value ? 'DIR' : 'ROOT' }}</span>
                    <span class="drive-move-destination__copy">
                      <strong>{{ option.label }}</strong>
                      <small>{{ option.path }}{{ option.disabled ? ' · 이동 불가' : '' }}</small>
                    </span>
                  </button>
                </div>
              </section>

              <section class="panel panel--compact">
                <div class="panel__header">
                  <div>
                    <h3>선택 항목</h3>
                  </div>
                </div>
                <div class="drive-move-target-list">
                  <span v-for="item in moveDialog.targets" :key="item.id">
                    {{ item.fileOriginName }}
                  </span>
                </div>
                <div class="entry-editor__actions">
                  <button class="button button--ghost" type="button" @click="closeMoveDialog">취소</button>
                  <button class="button button--primary" type="button" :disabled="!canConfirmMoveDialog" @click="confirmMoveDialog">
                    {{ moveDialog.saving ? '이동 중' : '선택 위치로 이동' }}
                  </button>
                </div>
              </section>
            </div>
          </div>
        </div>

        <div v-if="sharedSaveDialog.open" class="travel-modal" @click.self="closeSharedSaveDialog">
          <div class="travel-modal__dialog drive-shared-save-modal">
            <div class="travel-modal__header">
              <div>
                <h2>내 드라이브에 저장</h2>
              </div>
              <button class="button button--ghost" type="button" @click="closeSharedSaveDialog">닫기</button>
            </div>
            <div class="travel-modal__body drive-shared-save-modal__body">
              <section class="panel panel--compact">
                <div class="panel__header">
                  <div>
                    <h3>저장 위치</h3>
                  </div>
                </div>
                <div v-if="sharedSaveDialog.loading" class="panel__empty">폴더 목록을 불러오는 중입니다.</div>
                <div v-else class="drive-move-destination-list">
                  <button
                    v-for="option in sharedSaveDestinationOptions"
                    :key="`shared-save-dest-${option.value || 'root'}`"
                    class="drive-move-destination"
                    :class="{ 'drive-move-destination--active': sharedSaveDialog.targetParentId === option.value }"
                    type="button"
                    :disabled="option.disabled"
                    @click="sharedSaveDialog.targetParentId = option.value"
                  >
                    <span class="drive-move-destination__icon">{{ option.value ? 'DIR' : 'ROOT' }}</span>
                    <span class="drive-move-destination__copy">
                      <strong>{{ option.label }}</strong>
                      <small>{{ option.path }}{{ option.disabled ? ' · 저장 불가' : '' }}</small>
                    </span>
                  </button>
                </div>
              </section>

              <section class="panel panel--compact">
                <div class="panel__header">
                  <div>
                    <h3>선택 파일</h3>
                  </div>
                </div>
                <div class="drive-shared-save-target-list">
                  <span v-for="item in sharedSaveDialog.targets" :key="item.id">
                    {{ item.fileOriginName }}
                  </span>
                </div>
                <div class="drive-folder-modal__preview">
                  <span>저장 위치</span>
                  <strong>{{ selectedSharedSaveDestination?.path || '내 드라이브' }}</strong>
                </div>
                <div class="entry-editor__actions">
                  <button class="button button--ghost" type="button" @click="closeSharedSaveDialog">취소</button>
                  <button class="button button--primary" type="button" :disabled="!canConfirmSharedSaveDialog" @click="confirmSharedSaveDialog">
                    {{ sharedSaveDialog.saving ? '저장 중' : '선택 위치에 저장' }}
                  </button>
                </div>
              </section>
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
                    <h3>다운로드 링크</h3>
                  </div>
                </div>
                <div class="drive-toolbar__filters">
                  <label class="field">
                    <span class="field__label">유효 시간</span>
                    <select v-model.number="shareDialog.linkExpiresInMinutes">
                      <option :value="30">30분</option>
                      <option :value="60">1시간</option>
                      <option :value="360">6시간</option>
                      <option :value="1440">1일</option>
                      <option :value="10080">7일</option>
                      <option :value="43200">30일</option>
                    </select>
                  </label>
                  <label class="field">
                    <span class="field__label">다운로드 횟수</span>
                    <input v-model.number="shareDialog.linkMaxDownloads" class="drive-input" type="number" min="1" max="1000" />
                  </label>
                </div>
                <div class="entry-editor__actions">
                  <button class="button button--primary" type="button" :disabled="shareDialog.linkSaving" @click="createPublicDownloadLink">
                    {{ shareDialog.linkSaving ? '링크 생성 중' : '링크 생성' }}
                  </button>
                  <button
                    v-if="shareDialog.generatedDownloadLink?.downloadUrl && shareDialog.generatedDownloadLink.available"
                    class="button button--ghost"
                    type="button"
                    @click="copyPublicDownloadLink"
                  >
                    링크 복사
                  </button>
                </div>
                <div v-if="shareDialog.generatedDownloadLink?.downloadUrl" class="drive-generated-link">
                  <input class="drive-input" type="text" readonly :value="shareDialog.generatedDownloadLink.downloadUrl" />
                  <small>
                    {{ shareDialog.generatedDownloadLink.available ? '사용 가능' : '사용 불가' }} ·
                    {{ shareDialog.generatedDownloadLink.maxDownloads }}회까지,
                    {{ formatTimestamp(shareDialog.generatedDownloadLink.expiresAt) }}까지 사용 가능합니다.
                  </small>
                </div>
                <div v-if="shareDialog.downloadLinks.length" class="drive-download-link-list">
                  <article v-for="link in shareDialog.downloadLinks" :key="link.id" class="drive-download-link-item">
                    <div>
                      <strong>{{ link.available ? '사용 가능' : '사용 불가' }}</strong>
                      <small>
                        {{ link.downloadCount }} / {{ link.maxDownloads }}회 ·
                        {{ formatTimestamp(link.expiresAt) }}까지
                      </small>
                    </div>
                    <div class="drive-download-link-item__actions">
                      <button
                        class="button button--ghost"
                        type="button"
                        :disabled="!link.available || !link.downloadUrl"
                        @click="copyPublicDownloadLink(link)"
                      >
                        링크 복사
                      </button>
                      <button
                        class="button button--ghost"
                        type="button"
                        :disabled="!link.available || shareDialog.revokingLinkId === link.id"
                        @click="revokePublicDownloadLink(link)"
                      >
                        {{ shareDialog.revokingLinkId === link.id ? '회수 중' : '링크 회수' }}
                      </button>
                    </div>
                  </article>
                </div>
              </section>

              <section v-if="shareDialog.targets.length === 1" class="panel panel--compact">
                <div class="panel__header">
                  <div>
                    <h3>현재 공유 대상</h3>
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



    <div v-if="selectedVersionableItem" class="drive-version-launcher" data-testid="drive-version-launcher">
          <div>
            <strong>File version history</strong>
          </div>
          <button class="button button--ghost" type="button" data-testid="drive-version-open" @click="openDriveVersionDrawer(selectedVersionableItem)">
            View versions
          </button>
        </div>

        <div v-if="versionDrawer.open" class="drive-version-overlay" data-testid="drive-version-overlay" @click.self="closeDriveVersionDrawer">
          <aside class="drive-version-drawer" data-testid="drive-version-drawer" role="dialog" aria-modal="true" aria-labelledby="drive-version-title">
            <header class="drive-version-drawer__header">
              <div>
                <p class="eyebrow">CalenDrive versions</p>
                <h2 id="drive-version-title">{{ versionDrawer.item?.fileOriginName || 'File versions' }}</h2>
              </div>
              <button class="button button--ghost" type="button" :disabled="versionDrawer.loading || Boolean(versionDrawer.restoringId)" @click="closeDriveVersionDrawer">
                Close
              </button>
            </header>

            <div v-if="versionDrawer.successMessage" class="feedback feedback--success" data-testid="drive-version-success" aria-live="polite">
              {{ versionDrawer.successMessage }}
            </div>
            <div v-if="versionDrawer.errorMessage" class="feedback feedback--error" data-testid="drive-version-error" aria-live="assertive">
              {{ versionDrawer.errorMessage }}
            </div>

            <div v-if="versionDrawer.loading" class="drive-version-empty" data-testid="drive-version-loading">Loading versions...</div>
            <div v-else-if="!versionDrawer.versions.length" class="drive-version-empty" data-testid="drive-version-empty">
              No version records are available for this file yet.
            </div>
            <div v-else class="drive-version-list" data-testid="drive-version-list">
              <article v-for="version in versionDrawer.versions" :key="version.id" class="drive-version-card" :data-testid="`drive-version-row-${version.id}`">
                <div>
                  <strong>Version {{ version.versionNumber }}</strong>
                  <p>{{ version.fileOriginName }} · {{ formatBytes(version.fileSize) }}</p>
                  <small>{{ formatVersionSource(version.source) }} · {{ version.contentType || '-' }} · {{ formatTimestamp(version.createdAt) }}</small>
                </div>
                <button
                  class="button button--primary"
                  type="button"
                  :disabled="Boolean(versionDrawer.restoringId)"
                  :data-testid="`drive-version-restore-${version.id}`"
                  @click="restoreDriveVersion(version)"
                >
                  {{ versionDrawer.restoringId === version.id ? 'Restoring...' : 'Restore' }}
                </button>
              </article>
            </div>
          </aside>
        </div>
</template>
