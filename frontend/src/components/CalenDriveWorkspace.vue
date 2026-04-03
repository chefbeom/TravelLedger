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
  renameDriveItem,
  restoreDriveItem,
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

const tabs = computed(() => (
  props.currentUser?.admin
    ? [
        { key: 'home', label: '홈' },
        { key: 'drive', label: '드라이브' },
        { key: 'recent', label: '최근 파일' },
        { key: 'shared', label: '공유' },
        { key: 'trash', label: '휴지통' },
        { key: 'admin', label: '관리자' },
      ]
    : [
        { key: 'home', label: '홈' },
        { key: 'drive', label: '드라이브' },
        { key: 'recent', label: '최근 파일' },
        { key: 'shared', label: '공유' },
        { key: 'trash', label: '휴지통' },
      ]
))

const activeTab = ref('home')
const sharedTab = ref('received')
const profileModalOpen = ref(false)
const loading = ref(false)
const feedback = ref('')
const errorMessage = ref('')

const homeSummary = ref(null)
const drivePage = ref({
  fileList: [],
  breadcrumbs: [],
  availableExtensions: [],
  totalPage: 0,
  totalCount: 0,
  currentPage: 0,
  currentSize: 30,
})
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
  size: 30,
  sortOption: 'recent',
  searchQuery: '',
  extensionFilter: '',
})

const uploadProgress = reactive({
  open: false,
  title: '파일 업로드 중입니다.',
  description: '선택한 파일을 CalenDrive에 저장하고 있습니다.',
  current: 0,
  total: 0,
  percent: 0,
  fileName: '',
})

const shareDialog = reactive({
  open: false,
  file: null,
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

function buildOwnedDownloadPath(item) {
  return `/api/file/${item.id}/download`
}

function buildOwnedThumbnailPath(item) {
  return `/api/file/${item.id}/thumbnail`
}

function buildSharedDownloadPath(item) {
  return `/api/file/share/shared/${item.fileId}/download`
}

function buildSharedThumbnailPath(item) {
  return `/api/file/share/shared/${item.fileId}/thumbnail`
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
    if (activeTab.value === 'home') {
      await Promise.all([loadHomeSummary(), loadProfileSettings()])
      return
    }
    if (activeTab.value === 'drive') {
      await Promise.all([loadDrivePage(), loadProfileSettings()])
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
    await Promise.all([loadRecentFiles(), loadHomeSummary()])
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

function openFolder(item) {
  if (!isFolder(item)) {
    return
  }
  pageFilters.parentId = item.id
  pageFilters.page = 0
  selectTab('drive')
}

function navigateToBreadcrumb(crumbId) {
  pageFilters.parentId = crumbId ?? null
  pageFilters.page = 0
  selectTab('drive')
}

function downloadOwnedItem(item) {
  window.open(buildOwnedDownloadPath(item), '_blank', 'noopener')
}

function downloadSharedItem(item) {
  window.open(buildSharedDownloadPath(item), '_blank', 'noopener')
}

async function promptCreateFolder() {
  const folderName = window.prompt('새 폴더 이름을 입력하세요.', '새 폴더')
  if (!folderName) {
    return
  }

  try {
    await createDriveFolder({
      folderName,
      parentId: pageFilters.parentId,
    })
    setMessages('폴더를 만들었습니다.')
    await Promise.all([loadDrivePage(), loadHomeSummary()])
  } catch (error) {
    setMessages('', error.message)
  }
}

async function promptRename(item) {
  const nextName = window.prompt('새 이름을 입력하세요.', item.fileOriginName)
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
    await Promise.all([loadDrivePage(), loadHomeSummary(), loadTrashFiles()])
  } catch (error) {
    setMessages('', error.message)
  }
}

async function restoreTrashItem(item) {
  try {
    await restoreDriveItem(item.id)
    setMessages('휴지통에서 복구했습니다.')
    await Promise.all([loadTrashFiles(), loadDrivePage(), loadHomeSummary()])
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
  uploadProgress.title = '파일 업로드 중입니다.'
  uploadProgress.description = '선택한 파일을 CalenDrive에 저장하고 있습니다.'
  setMessages()

  try {
    const prepared = await initializeDriveUpload(files, pageFilters.parentId)
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
    await Promise.all([loadDrivePage(), loadHomeSummary(), loadRecentFiles()])
  } catch (error) {
    if (activeUploadMeta?.finalObjectKey) {
      try {
        await abortDriveUpload({
          finalObjectKey: activeUploadMeta.finalObjectKey,
          chunkObjectKeys: activeUploadMeta.objectKey ? [activeUploadMeta.objectKey] : [],
        })
      } catch {
        // Ignore abort failures and preserve the original upload error.
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

async function openShareDialog(item) {
  shareDialog.open = true
  shareDialog.file = item
  shareDialog.existingRecipients = []
  shareDialog.searchQuery = ''
  shareDialog.searchResults = []
  shareDialog.selectedRecipientLoginId = ''
  shareDialog.loading = true
  shareDialog.saving = false
  setMessages()

  try {
    shareDialog.existingRecipients = await fetchDriveShareInfo(item.id)
  } catch (error) {
    setMessages('', error.message)
  } finally {
    shareDialog.loading = false
  }
}

function closeShareDialog() {
  shareDialog.open = false
  shareDialog.file = null
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
  if (!shareDialog.file || !shareDialog.selectedRecipientLoginId) {
    setMessages('', '공유할 사용자를 선택해주세요.')
    return
  }

  shareDialog.saving = true
  try {
    await shareDriveFiles([shareDialog.file.id], shareDialog.selectedRecipientLoginId)
    shareDialog.existingRecipients = await fetchDriveShareInfo(shareDialog.file.id)
    shareDialog.searchQuery = ''
    shareDialog.searchResults = []
    shareDialog.selectedRecipientLoginId = ''
    setMessages('파일을 공유했습니다.')
    await loadSharedData()
  } catch (error) {
    setMessages('', error.message)
  } finally {
    shareDialog.saving = false
  }
}

async function cancelShareForRecipient(recipientLoginId) {
  if (!shareDialog.file) {
    return
  }

  try {
    await cancelDriveShare([shareDialog.file.id], recipientLoginId)
    shareDialog.existingRecipients = await fetchDriveShareInfo(shareDialog.file.id)
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
    if (shareDialog.file?.id === item.id) {
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
    await saveSharedDriveFile(item.fileId)
    setMessages('공유 파일을 내 드라이브에 저장했습니다.')
    await Promise.all([loadSharedData(), loadDrivePage(), loadHomeSummary()])
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
  if (activeTab.value === 'home') {
    loadHomeSummary()
  }
}

const driveFiles = computed(() => drivePage.value?.fileList || [])
const breadcrumbs = computed(() => drivePage.value?.breadcrumbs || [])
const availableExtensions = computed(() => drivePage.value?.availableExtensions || [])
const homeRecentFiles = computed(() => homeSummary.value?.recentFiles || [])
const adminSummary = computed(() => adminDashboard.value?.summary || null)
const adminUsers = computed(() => adminDashboard.value?.users || [])
const currentProfileName = computed(() => profileSettings.value?.displayName || props.currentUser.displayName)
const currentProfileImage = computed(() => profileSettings.value?.profileImageUrl || '')

watch(
  () => activeTab.value,
  () => {
    loadActiveTab()
  },
)

watch(
  () => pageFilters.parentId,
  () => {
    if (activeTab.value === 'drive') {
      loadActiveTab()
    }
  },
)

onMounted(() => {
  loadActiveTab()
})
</script>

<template>
  <div class="workspace-stack drive-shell">
    <section class="panel drive-shell__header">
      <div class="panel__header">
        <div>
          <h2>CalenDrive</h2>
          <p>구글 드라이브 기반 구조를 Calen 안으로 옮겨, 업로드·공유·휴지통·관리 기능을 하나의 워크스페이스로 정리했습니다.</p>
        </div>
        <div class="drive-shell__hero">
          <button class="button button--ghost" type="button" @click="profileModalOpen = true">프로필 설정</button>
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
              <small>{{ currentUser.loginId }}</small>
            </div>
          </div>
        </div>
      </div>

      <div class="scope-toggle scope-toggle--wrap">
        <button
          v-for="tab in tabs"
          :key="tab.key"
          class="button"
          :class="{ 'button--primary': activeTab === tab.key }"
          type="button"
          @click="selectTab(tab.key)"
        >
          {{ tab.label }}
        </button>
      </div>
      <small class="field__hint">파일 드라이브, 공유, 휴지통, 관리자 기능을 전역 구조를 깨지 않게 4번 페이지 안으로만 통합했습니다.</small>
    </section>

    <div v-if="feedback" class="feedback feedback--success">{{ feedback }}</div>
    <div v-if="errorMessage" class="feedback feedback--error">{{ errorMessage }}</div>

    <section v-if="loading" class="panel">
      <p class="panel__empty">CalenDrive 데이터를 불러오는 중입니다.</p>
    </section>

    <template v-else>
      <section v-if="activeTab === 'home'" class="workspace-stack">
        <div class="summary-grid">
          <article class="summary-card">
            <span class="summary-card__label">전체 항목</span>
            <strong class="summary-card__balance">{{ homeSummary?.driveItemCount || 0 }}</strong>
            <span class="summary-card__meta">파일 {{ homeSummary?.fileCount || 0 }} / 폴더 {{ homeSummary?.folderCount || 0 }}</span>
          </article>
          <article class="summary-card">
            <span class="summary-card__label">사용 중인 저장소</span>
            <strong class="summary-card__balance">{{ formatBytes(homeSummary?.usedBytes || 0) }}</strong>
            <span class="summary-card__meta">공유 {{ homeSummary?.sharedCount || 0 }} / 휴지통 {{ homeSummary?.trashCount || 0 }}</span>
          </article>
          <article class="summary-card">
            <span class="summary-card__label">프로필 표시 이름</span>
            <strong class="summary-card__balance">{{ currentProfileName }}</strong>
            <span class="summary-card__meta">{{ currentUser.admin ? '관리자 권한' : '일반 사용자' }}</span>
          </article>
          <article class="summary-card">
            <span class="summary-card__label">최근 변경 흐름</span>
            <strong class="summary-card__balance">{{ homeRecentFiles.length }}</strong>
            <span class="summary-card__meta">최근 파일 카드</span>
          </article>
        </div>

        <section class="panel">
          <div class="panel__header">
            <div>
              <h3>최근 작업 파일</h3>
              <p>내 드라이브에서 최근에 바뀐 파일을 먼저 보여줍니다.</p>
            </div>
            <button class="button button--ghost" type="button" @click="selectTab('drive')">드라이브 열기</button>
          </div>
          <div v-if="homeRecentFiles.length" class="drive-card-grid">
            <article
              v-for="item in homeRecentFiles"
              :key="item.id"
              class="drive-card"
              @click="isFolder(item) ? openFolder(item) : downloadOwnedItem(item)"
            >
              <img
                v-if="isImageFile(item)"
                class="drive-card__thumb"
                :src="buildOwnedThumbnailPath(item)"
                :alt="item.fileOriginName"
                loading="lazy"
                decoding="async"
              />
              <div v-else class="drive-card__thumb drive-card__thumb--placeholder">
                {{ isFolder(item) ? 'FOLDER' : (item.fileFormat || 'FILE').toUpperCase() }}
              </div>
              <div class="drive-card__body">
                <strong>{{ item.fileOriginName }}</strong>
                <small>{{ isFolder(item) ? '폴더' : formatBytes(item.fileSize) }}</small>
                <small>{{ formatTimestamp(item.lastModifyDate || item.uploadDate) }}</small>
              </div>
            </article>
          </div>
          <p v-else class="panel__empty">아직 저장된 파일이 없습니다.</p>
        </section>
      </section>
      <section v-else-if="activeTab === 'drive'" class="workspace-stack">
        <section class="panel">
          <div class="panel__header">
            <div>
              <h3>파일 드라이브</h3>
              <p>업로드, 폴더 생성, 공유, 삭제, 휴지통 이동을 이 화면에서 바로 처리합니다.</p>
            </div>
            <div class="drive-toolbar__actions">
              <label class="button button--primary drive-upload-button">
                파일 업로드
                <input type="file" multiple hidden @change="handleFilesSelected" />
              </label>
              <button class="button button--ghost" type="button" @click="promptCreateFolder">폴더 만들기</button>
            </div>
          </div>

          <div class="drive-toolbar">
            <div class="drive-breadcrumbs">
              <button class="button button--ghost" type="button" @click="navigateToBreadcrumb(null)">루트</button>
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
            <div class="drive-toolbar__filters">
              <input
                v-model="pageFilters.searchQuery"
                class="drive-input"
                type="search"
                placeholder="파일 이름 검색"
                @keyup.enter="loadDrivePage"
              />
              <select v-model="pageFilters.extensionFilter" class="drive-input" @change="loadDrivePage">
                <option value="">모든 확장자</option>
                <option v-for="extension in availableExtensions" :key="extension" :value="extension">
                  {{ extension.toUpperCase() }}
                </option>
              </select>
              <select v-model="pageFilters.sortOption" class="drive-input" @change="loadDrivePage">
                <option value="recent">최근 수정순</option>
                <option value="name">이름순</option>
                <option value="size">용량순</option>
                <option value="oldest">오래된 순</option>
              </select>
            </div>
          </div>

          <div class="sheet-table-wrap drive-table-wrap">
            <table class="sheet-table drive-table">
              <thead>
                <tr>
                  <th>이름</th>
                  <th>종류</th>
                  <th>소유자</th>
                  <th>용량</th>
                  <th>최근 수정</th>
                  <th>작업</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="item in driveFiles" :key="item.id">
                  <td class="drive-table__name">
                    <button
                      class="drive-table__item"
                      type="button"
                      @click="isFolder(item) ? openFolder(item) : downloadOwnedItem(item)"
                    >
                      <img
                        v-if="isImageFile(item)"
                        class="drive-table__thumb"
                        :src="buildOwnedThumbnailPath(item)"
                        :alt="item.fileOriginName"
                        loading="lazy"
                        decoding="async"
                      />
                      <span v-else class="drive-table__thumb drive-table__thumb--placeholder">
                        {{ isFolder(item) ? 'DIR' : (item.fileFormat || 'FILE').toUpperCase() }}
                      </span>
                      <span>
                        <strong>{{ item.fileOriginName }}</strong>
                        <small v-if="item.sharedFile">공유됨</small>
                      </span>
                    </button>
                  </td>
                  <td>{{ isFolder(item) ? '폴더' : (item.fileFormat || '파일').toUpperCase() }}</td>
                  <td>{{ item.ownerDisplayName || item.ownerLoginId }}</td>
                  <td>{{ isFolder(item) ? '-' : formatBytes(item.fileSize) }}</td>
                  <td>{{ formatTimestamp(item.lastModifyDate || item.uploadDate) }}</td>
                  <td>
                    <div class="sheet-table__actions">
                      <button v-if="!isFolder(item)" class="button button--ghost" type="button" @click="downloadOwnedItem(item)">다운로드</button>
                      <button class="button button--ghost" type="button" @click="promptRename(item)">이름 변경</button>
                      <button v-if="!isFolder(item)" class="button button--ghost" type="button" @click="openShareDialog(item)">공유</button>
                      <button class="button button--ghost" type="button" @click="moveItemToTrash(item)">휴지통</button>
                    </div>
                  </td>
                </tr>
                <tr v-if="!driveFiles.length">
                  <td colspan="6" class="sheet-table__empty">현재 폴더에 파일이 없습니다.</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
      </section>

      <section v-else-if="activeTab === 'recent'" class="workspace-stack">
        <section class="panel">
          <div class="panel__header">
            <div>
              <h3>최근 파일</h3>
              <p>최근 수정한 파일을 시간순으로 빠르게 확인합니다.</p>
            </div>
          </div>
          <div v-if="recentFiles.length" class="drive-card-grid">
            <article
              v-for="item in recentFiles"
              :key="item.id"
              class="drive-card"
              @click="downloadOwnedItem(item)"
            >
              <img
                v-if="isImageFile(item)"
                class="drive-card__thumb"
                :src="buildOwnedThumbnailPath(item)"
                :alt="item.fileOriginName"
                loading="lazy"
                decoding="async"
              />
              <div v-else class="drive-card__thumb drive-card__thumb--placeholder">
                {{ (item.fileFormat || 'FILE').toUpperCase() }}
              </div>
              <div class="drive-card__body">
                <strong>{{ item.fileOriginName }}</strong>
                <small>{{ formatBytes(item.fileSize) }}</small>
                <small>{{ formatTimestamp(item.lastModifyDate || item.uploadDate) }}</small>
              </div>
            </article>
          </div>
          <p v-else class="panel__empty">최근 파일이 없습니다.</p>
        </section>
      </section>

      <section v-else-if="activeTab === 'shared'" class="workspace-stack">
        <section class="panel">
          <div class="scope-toggle scope-toggle--wrap">
            <button class="button" :class="{ 'button--primary': sharedTab === 'received' }" type="button" @click="sharedTab = 'received'">받은 파일</button>
            <button class="button" :class="{ 'button--primary': sharedTab === 'sent' }" type="button" @click="sharedTab = 'sent'">보낸 공유</button>
          </div>
        </section>

        <section v-if="sharedTab === 'received'" class="panel">
          <div class="panel__header">
            <div>
              <h3>받은 공유 파일</h3>
              <p>다른 사용자가 공유한 파일을 저장하거나 바로 내려받을 수 있습니다.</p>
            </div>
          </div>

          <div v-if="sharedReceived.length" class="drive-card-grid">
            <article v-for="item in sharedReceived" :key="`${item.fileId}-${item.id}`" class="drive-card">
              <img
                v-if="isImageFile(item)"
                class="drive-card__thumb"
                :src="buildSharedThumbnailPath(item)"
                :alt="item.fileOriginName"
                loading="lazy"
                decoding="async"
              />
              <div v-else class="drive-card__thumb drive-card__thumb--placeholder">
                {{ (item.fileFormat || 'FILE').toUpperCase() }}
              </div>
              <div class="drive-card__body">
                <strong>{{ item.fileOriginName }}</strong>
                <small>{{ item.ownerDisplayName }} ({{ item.ownerLoginId }})</small>
                <small>{{ formatTimestamp(item.sharedAt) }}</small>
                <div class="drive-card__actions">
                  <button class="button button--ghost" type="button" @click="downloadSharedItem(item)">다운로드</button>
                  <button class="button button--primary" type="button" @click="handleSaveSharedFile(item)">내 드라이브에 저장</button>
                </div>
              </div>
            </article>
          </div>
          <p v-else class="panel__empty">공유받은 파일이 없습니다.</p>
        </section>

        <section v-else class="panel">
          <div class="panel__header">
            <div>
              <h3>보낸 공유 관리</h3>
              <p>어떤 파일을 누구에게 보냈는지 확인하고 개별 공유나 전체 공유를 취소할 수 있습니다.</p>
            </div>
          </div>

          <div v-if="sharedSent.length" class="workspace-stack">
            <article v-for="group in sharedSent" :key="group.fileId" class="drive-share-group">
              <div>
                <strong>{{ group.fileOriginName }}</strong>
                <small>{{ formatBytes(group.fileSize) }} · {{ (group.fileFormat || 'FILE').toUpperCase() }}</small>
              </div>
              <div class="drive-share-group__recipients">
                <span v-for="recipient in group.recipients" :key="recipient.shareId || recipient.recipientLoginId" class="chip chip--neutral">
                  {{ recipient.recipientDisplayName }} ({{ recipient.recipientLoginId }})
                </span>
              </div>
              <div class="drive-card__actions">
                <button
                  v-for="recipient in group.recipients"
                  :key="`cancel-${group.fileId}-${recipient.recipientLoginId}`"
                  class="button button--ghost"
                  type="button"
                  @click="cancelDriveShare([group.fileId], recipient.recipientLoginId).then(() => { setMessages('공유를 취소했습니다.'); loadSharedData() }).catch((error) => setMessages('', error.message))"
                >
                  {{ recipient.recipientDisplayName }} 공유 취소
                </button>
                <button class="button button--ghost" type="button" @click="cancelAllSharesForFile(group)">전체 공유 취소</button>
              </div>
            </article>
          </div>
          <p v-else class="panel__empty">보낸 공유가 없습니다.</p>
        </section>
      </section>
      <section v-else-if="activeTab === 'trash'" class="workspace-stack">
        <section class="panel">
          <div class="panel__header">
            <div>
              <h3>휴지통</h3>
              <p>휴지통에 보낸 파일을 복구하거나 완전히 삭제할 수 있습니다.</p>
            </div>
            <button class="button button--ghost" type="button" @click="handleClearTrash">휴지통 비우기</button>
          </div>

          <div class="sheet-table-wrap drive-table-wrap">
            <table class="sheet-table drive-table">
              <thead>
                <tr>
                  <th>이름</th>
                  <th>삭제 시각</th>
                  <th>용량</th>
                  <th>작업</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="item in trashFiles" :key="item.id">
                  <td>{{ item.fileOriginName }}</td>
                  <td>{{ formatTimestamp(item.deletedAt) }}</td>
                  <td>{{ isFolder(item) ? '-' : formatBytes(item.fileSize) }}</td>
                  <td>
                    <div class="sheet-table__actions">
                      <button class="button button--ghost" type="button" @click="restoreTrashItem(item)">복구</button>
                      <button class="button button--ghost" type="button" @click="deleteItemPermanently(item)">완전 삭제</button>
                    </div>
                  </td>
                </tr>
                <tr v-if="!trashFiles.length">
                  <td colspan="4" class="sheet-table__empty">휴지통이 비어 있습니다.</td>
                </tr>
              </tbody>
            </table>
          </div>
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
            <span class="summary-card__meta">드라이브 전체 기준</span>
          </article>
        </div>

        <section class="panel">
          <div class="panel__header">
            <div>
              <h3>스토리지 관리</h3>
              <p>용량, 무결성 이슈, 사용자별 사용량을 한 번에 점검합니다.</p>
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
              <span class="field__label">무결성 검사 이슈</span>
              <div class="drive-admin-stat">
                <strong>{{ storageAnalytics?.issueCount || 0 }}</strong>
              </div>
            </div>
          </div>
          <div class="entry-editor__actions">
            <button class="button button--primary" type="button" @click="saveProviderCapacity">스토리지 용량 저장</button>
          </div>
          <ul class="drive-issue-list">
            <li v-for="issue in storageAnalytics?.issues || []" :key="issue">{{ issue }}</li>
          </ul>
        </section>

        <section class="panel">
          <div class="panel__header">
            <div>
              <h3>사용자 현황</h3>
              <p>사용자별 파일 수, 공유 수, 사용량, 활성 상태를 관리합니다.</p>
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
                      {{ user.active ? '활성 해제' : '활성 전환' }}
                    </button>
                  </td>
                </tr>
                <tr v-if="!adminUsers.length">
                  <td colspan="6" class="sheet-table__empty">아직 사용자 집계가 없습니다.</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
      </section>
    </template>

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
            <p>{{ shareDialog.file?.fileOriginName }}</p>
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
            <p v-else class="panel__empty">검색 결과가 없으면 loginId 일부를 다시 입력해보세요.</p>
            <div class="entry-editor__actions">
              <button class="button button--primary" type="button" :disabled="shareDialog.saving" @click="submitShare">
                {{ shareDialog.saving ? '공유 중..' : '선택한 사용자에게 공유' }}
              </button>
            </div>
          </section>

          <section class="panel panel--compact">
            <div class="panel__header">
              <div>
                <h3>현재 공유 대상</h3>
                <p>이미 공유한 사용자를 확인하고 개별 취소할 수 있습니다.</p>
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
              <button class="button button--ghost" type="button" @click="cancelAllSharesForFile(shareDialog.file)">전체 공유 취소</button>
            </div>
            <p v-else class="panel__empty">아직 공유한 사용자가 없습니다.</p>
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
  </div>
</template>
