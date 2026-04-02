<script setup>
import { computed, onMounted, reactive } from 'vue'
import { buildThumbnailUrl } from '../lib/mediaPreview'
import {
  archiveAdminSupportInquiry,
  createAdminDataBackup,
  createAdminMinioBackup,
  downloadAdminDataBackup,
  deleteAdminSupportInquiry,
  fetchAdminAccessStatus,
  fetchAdminDataManagement,
  fetchAdminDashboard,
  fetchAdminLoginAuditLogs,
  fetchAdminSupportInquiries,
  replyAdminSupportInquiry,
  restoreAdminDataBackup,
  restoreAdminUploadedBackup,
  unlockBlockedIp,
  updateAdminUserActive,
  verifyAdminAccess,
} from '../lib/api'

const props = defineProps({
  currentUser: {
    type: Object,
    required: true,
  },
})

const PAGE_SIZE = 10

const state = reactive({
  loading: true,
  loadingLoginLogs: false,
  mutatingUserId: null,
  unlockingIp: '',
  savingReply: false,
  mutatingInquiryId: null,
  errorMessage: '',
  adminAccessReady: false,
  adminAccessVerified: false,
  verifyingAdminAccess: false,
  adminAccessCode: '',
  adminAccessError: '',
  summary: null,
  dataManagementOpen: false,
  loadingDataManagement: false,
  creatingDataBackup: false,
  creatingMinioBackup: false,
  downloadingDataBackup: false,
  restoringBackupFile: '',
  restoringUploadedBackup: false,
  restoreUploadFile: null,
  dataManagementError: '',
  dataManagement: null,
  recentLoginLogs: [],
  loginLogPage: { content: [], page: 0, size: 10, totalElements: 0, totalPages: 0 },
  blockedIps: [],
  blockedIpPage: 0,
  users: [],
  userPage: 0,
  recentInvites: [],
  invitePage: 0,
  supportInquiries: [],
  supportTab: 'inbox',
  supportPages: { inbox: 0, archive: 0 },
  selectedSupportInquiryId: null,
  supportReplyContent: '',
})

const summaryCards = [
  { key: 'totalUsers', label: '전체 사용자' },
  { key: 'activeUsers', label: '활성 사용자' },
  { key: 'adminUsers', label: '관리자 계정' },
  { key: 'blockedIpCount', label: '차단 IP' },
  { key: 'pendingInvites', label: '미사용 초대' },
  { key: 'recentFailureCount', label: '24시간 실패' },
]

const loginStatusLabel = {
  SUCCESS: '성공',
  BLOCKED: '차단',
  FAILED: '인증 실패',
}

const inviteStatusLabel = {
  PENDING: '사용 가능',
  USED: '사용 완료',
  EXPIRED: '만료',
}

const inquiryStatusLabel = {
  PENDING: '답변 대기',
  ANSWERED: '답변 완료',
}

function formatDateTime(value) {
  if (!value) {
    return '-'
  }

  const normalized = new Date(value)
  if (Number.isNaN(normalized.getTime())) {
    return value
  }

  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(normalized)
}

function clampPage(page, totalItems) {
  const totalPages = Math.max(1, Math.ceil(totalItems / PAGE_SIZE))
  return Math.min(Math.max(page, 0), totalPages - 1)
}

function paginate(items, page) {
  const start = page * PAGE_SIZE
  return items.slice(start, start + PAGE_SIZE)
}

const inboxSupportInquiries = computed(() => (
  state.supportInquiries.filter((inquiry) => !inquiry.archived)
))

const archivedSupportInquiries = computed(() => (
  state.supportInquiries.filter((inquiry) => inquiry.archived)
))

const currentSupportSource = computed(() => (
  state.supportTab === 'archive' ? archivedSupportInquiries.value : inboxSupportInquiries.value
))

const currentSupportPage = computed(() => state.supportPages[state.supportTab])

const pagedSupportInquiries = computed(() => (
  paginate(currentSupportSource.value, currentSupportPage.value)
))

const supportPageCount = computed(() => Math.max(1, Math.ceil(currentSupportSource.value.length / PAGE_SIZE)))

const selectedSupportInquiry = computed(() => (
  currentSupportSource.value.find((inquiry) => inquiry.id === state.selectedSupportInquiryId) ?? null
))

const pagedBlockedIps = computed(() => paginate(state.blockedIps, state.blockedIpPage))
const blockedIpPageCount = computed(() => Math.max(1, Math.ceil(state.blockedIps.length / PAGE_SIZE)))

const pagedUsers = computed(() => paginate(state.users, state.userPage))
const userPageCount = computed(() => Math.max(1, Math.ceil(state.users.length / PAGE_SIZE)))

const pagedInvites = computed(() => paginate(state.recentInvites, state.invitePage))
const invitePageCount = computed(() => Math.max(1, Math.ceil(state.recentInvites.length / PAGE_SIZE)))

function syncSelection(preferredId = state.selectedSupportInquiryId) {
  state.supportPages.inbox = clampPage(state.supportPages.inbox, inboxSupportInquiries.value.length)
  state.supportPages.archive = clampPage(state.supportPages.archive, archivedSupportInquiries.value.length)

  const visibleList = currentSupportSource.value
  const preferred = visibleList.find((item) => item.id === preferredId)
  const fallback = pagedSupportInquiries.value[0] ?? visibleList[0] ?? null
  const nextInquiry = preferred ?? fallback
  state.selectedSupportInquiryId = nextInquiry?.id ?? null
  state.supportReplyContent = nextInquiry?.replyContent ?? ''
}

function setSupportTab(tab) {
  state.supportTab = tab
  syncSelection()
}

function selectSupportInquiry(inquiry) {
  state.selectedSupportInquiryId = inquiry?.id ?? null
  state.supportReplyContent = inquiry?.replyContent ?? ''
}

function handleAdminAccessRequired(error) {
  if (error?.status !== 403) {
    return false
  }
  state.adminAccessReady = true
  state.adminAccessVerified = false
  state.adminAccessError = '관리자 페이지 접근을 위해 3차 비밀번호를 입력해 주세요.'
  state.loading = false
  state.loadingLoginLogs = false
  return true
}

async function initializeAdminWorkspace() {
  state.loading = true
  state.errorMessage = ''
  state.adminAccessError = ''

  try {
    const status = await fetchAdminAccessStatus()
    state.adminAccessReady = true
    state.adminAccessVerified = Boolean(status?.verified)
    if (state.adminAccessVerified) {
      await loadDashboard()
      return
    }
  } catch (error) {
    if (!handleAdminAccessRequired(error)) {
      state.errorMessage = error.message
    }
  } finally {
    state.loading = false
  }
}

async function handleVerifyAdminAccess() {
  state.verifyingAdminAccess = true
  state.adminAccessError = ''

  try {
    await verifyAdminAccess(state.adminAccessCode)
    state.adminAccessVerified = true
    state.adminAccessCode = ''
    await loadDashboard()
  } catch (error) {
    state.adminAccessError = error.message
  } finally {
    state.verifyingAdminAccess = false
  }
}

async function loadDashboard() {
  state.loading = true
  state.errorMessage = ''

  try {
    const [dashboard, supportInquiries, loginLogPage] = await Promise.all([
      fetchAdminDashboard(),
      fetchAdminSupportInquiries(),
      fetchAdminLoginAuditLogs(0),
    ])

    state.summary = dashboard.summary
    state.loginLogPage = loginLogPage
    state.recentLoginLogs = loginLogPage.content ?? []
    state.blockedIps = dashboard.blockedIps ?? []
    state.users = dashboard.users ?? []
    state.recentInvites = dashboard.recentInvites ?? []
    state.supportInquiries = supportInquiries ?? []
    state.blockedIpPage = clampPage(state.blockedIpPage, state.blockedIps.length)
    state.userPage = clampPage(state.userPage, state.users.length)
    state.invitePage = clampPage(state.invitePage, state.recentInvites.length)
    syncSelection()
  } catch (error) {
    if (!handleAdminAccessRequired(error)) {
      state.errorMessage = error.message
    }
  } finally {
    state.loading = false
  }
}

async function loadLoginAuditLogs(page = 0) {
  state.loadingLoginLogs = true
  state.errorMessage = ''

  try {
    state.loginLogPage = await fetchAdminLoginAuditLogs(page)
    state.recentLoginLogs = state.loginLogPage.content ?? []
  } catch (error) {
    if (!handleAdminAccessRequired(error)) {
      state.errorMessage = error.message
    }
  } finally {
    state.loadingLoginLogs = false
  }
}

async function toggleUserActive(user) {
  state.mutatingUserId = user.id
  state.errorMessage = ''

  try {
    const updatedUser = await updateAdminUserActive(user.id, !user.active)
    state.users = state.users.map((item) => (item.id === updatedUser.id ? updatedUser : item))
  } catch (error) {
    if (!handleAdminAccessRequired(error)) {
      state.errorMessage = error.message
    }
  } finally {
    state.mutatingUserId = null
  }
}

async function handleUnlockIp(ip) {
  state.unlockingIp = ip
  state.errorMessage = ''

  try {
    await unlockBlockedIp(ip)
    state.blockedIps = state.blockedIps.filter((item) => item.clientIp !== ip)
    state.blockedIpPage = clampPage(state.blockedIpPage, state.blockedIps.length)
    if (state.summary) {
      state.summary = {
        ...state.summary,
        blockedIpCount: Math.max(0, (state.summary.blockedIpCount ?? 0) - 1),
      }
    }
  } catch (error) {
    if (!handleAdminAccessRequired(error)) {
      state.errorMessage = error.message
    }
  } finally {
    state.unlockingIp = ''
  }
}

async function handleReplySupportInquiry() {
  if (!selectedSupportInquiry.value) {
    return
  }

  state.savingReply = true
  state.errorMessage = ''

  try {
    const updatedInquiry = await replyAdminSupportInquiry(
      selectedSupportInquiry.value.id,
      state.supportReplyContent,
    )
    state.supportInquiries = state.supportInquiries.map((item) => (
      item.id === updatedInquiry.id ? updatedInquiry : item
    ))
    state.supportTab = 'archive'
    syncSelection(updatedInquiry.id)
  } catch (error) {
    if (!handleAdminAccessRequired(error)) {
      state.errorMessage = error.message
    }
  } finally {
    state.savingReply = false
  }
}

async function handleArchiveToggle(inquiry, archived) {
  state.mutatingInquiryId = inquiry.id
  state.errorMessage = ''

  try {
    const updatedInquiry = await archiveAdminSupportInquiry(inquiry.id, archived)
    state.supportInquiries = state.supportInquiries.map((item) => (
      item.id === updatedInquiry.id ? updatedInquiry : item
    ))
    state.supportTab = archived ? 'archive' : 'inbox'
    syncSelection(updatedInquiry.id)
  } catch (error) {
    if (!handleAdminAccessRequired(error)) {
      state.errorMessage = error.message
    }
  } finally {
    state.mutatingInquiryId = null
  }
}

async function handleDeleteSupportInquiry(inquiry) {
  state.mutatingInquiryId = inquiry.id
  state.errorMessage = ''

  try {
    await deleteAdminSupportInquiry(inquiry.id)
    state.supportInquiries = state.supportInquiries.filter((item) => item.id !== inquiry.id)
    syncSelection()
  } catch (error) {
    if (!handleAdminAccessRequired(error)) {
      state.errorMessage = error.message
    }
  } finally {
    state.mutatingInquiryId = null
  }
}

function formatFileSize(bytes) {
  if (!Number.isFinite(bytes) || bytes <= 0) {
    return '0 B'
  }

  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let value = bytes
  let unitIndex = 0

  while (value >= 1024 && unitIndex < units.length - 1) {
    value /= 1024
    unitIndex += 1
  }

  return `${value >= 10 || unitIndex === 0 ? value.toFixed(0) : value.toFixed(1)} ${units[unitIndex]}`
}

async function loadDataManagement() {
  state.loadingDataManagement = true
  state.dataManagementError = ''

  try {
    state.dataManagement = await fetchAdminDataManagement()
  } catch (error) {
    if (!handleAdminAccessRequired(error)) {
      state.dataManagementError = error.message
    }
  } finally {
    state.loadingDataManagement = false
  }
}

async function openDataManagement() {
  state.dataManagementOpen = true
  await loadDataManagement()
}

function closeDataManagement() {
  state.dataManagementOpen = false
  state.dataManagementError = ''
  state.restoreUploadFile = null
}

async function handleCreateDataBackup() {
  state.creatingDataBackup = true
  state.dataManagementError = ''

  try {
    await createAdminDataBackup()
    await loadDataManagement()
  } catch (error) {
    if (!handleAdminAccessRequired(error)) {
      state.dataManagementError = error.message
    }
  } finally {
    state.creatingDataBackup = false
  }
}

async function handleCreateMinioBackup() {
  state.creatingMinioBackup = true
  state.dataManagementError = ''

  try {
    await createAdminMinioBackup()
    await loadDataManagement()
  } catch (error) {
    if (!handleAdminAccessRequired(error)) {
      state.dataManagementError = error.message
    }
  } finally {
    state.creatingMinioBackup = false
  }
}

async function handleDownloadDataBackup() {
  state.downloadingDataBackup = true
  state.dataManagementError = ''

  try {
    const { blob, fileName } = await downloadAdminDataBackup()
    const url = window.URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = fileName
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
    window.URL.revokeObjectURL(url)
  } catch (error) {
    if (!handleAdminAccessRequired(error)) {
      state.dataManagementError = error.message
    }
  } finally {
    state.downloadingDataBackup = false
  }
}

function handleRestoreUploadSelection(event) {
  state.restoreUploadFile = event.target.files?.[0] ?? null
}

async function handleRestoreDataBackup(backup) {
  if (!backup?.fileName) {
    return
  }

  const confirmed = window.confirm(
    `선택한 백업(${backup.fileName})으로 현재 데이터를 복구합니다. 현재 데이터는 덮어써지며, 복구 후에는 즉시 되돌릴 수 없습니다. 계속할까요?`,
  )
  if (!confirmed) {
    return
  }

  state.restoringBackupFile = backup.fileName
  state.dataManagementError = ''

  try {
    await restoreAdminDataBackup(backup.fileName)
    await loadDataManagement()
    await loadDashboard()
  } catch (error) {
    if (!handleAdminAccessRequired(error)) {
      state.dataManagementError = error.message
    }
  } finally {
    state.restoringBackupFile = ''
  }
}

async function handleRestoreUploadedBackup() {
  if (!state.restoreUploadFile) {
    return
  }

  const confirmed = window.confirm(
    `업로드한 백업(${state.restoreUploadFile.name})으로 현재 데이터를 복구합니다. 현재 데이터는 덮어써지며, 복구 후에는 즉시 되돌릴 수 없습니다. 계속할까요?`,
  )
  if (!confirmed) {
    return
  }

  state.restoringUploadedBackup = true
  state.dataManagementError = ''

  try {
    await restoreAdminUploadedBackup(state.restoreUploadFile)
    state.restoreUploadFile = null
    await loadDataManagement()
    await loadDashboard()
  } catch (error) {
    if (!handleAdminAccessRequired(error)) {
      state.dataManagementError = error.message
    }
  } finally {
    state.restoringUploadedBackup = false
  }
}

onMounted(initializeAdminWorkspace)
</script>

<template>
  <section class="workspace-stack admin-workspace">
    <div v-if="state.adminAccessReady && !state.adminAccessVerified" class="travel-modal">
      <div class="travel-modal__dialog profile-security-modal admin-access-modal">
        <div class="travel-modal__header">
          <div>
            <h2>관리자 추가 인증</h2>
            <p>관리자 페이지를 열려면 8자리 3차 비밀번호를 입력해야 합니다.</p>
          </div>
        </div>
        <form class="travel-modal__body" @submit.prevent="handleVerifyAdminAccess">
          <div v-if="state.adminAccessError" class="feedback feedback--error">{{ state.adminAccessError }}</div>
          <label class="field">
            <span class="field__label">3차 비밀번호</span>
            <input
              v-model="state.adminAccessCode"
              type="password"
              inputmode="numeric"
              maxlength="8"
              autocomplete="one-time-code"
              placeholder="숫자 8자리"
              :disabled="state.verifyingAdminAccess"
            />
          </label>
        </form>
        <div class="travel-modal__footer">
          <button
            class="button button--primary"
            type="button"
            :disabled="state.verifyingAdminAccess || state.adminAccessCode.length !== 8"
            @click="handleVerifyAdminAccess"
          >
            {{ state.verifyingAdminAccess ? '확인 중...' : '확인' }}
          </button>
        </div>
      </div>
    </div>

    <div v-if="state.dataManagementOpen" class="travel-modal">
      <div class="travel-modal__dialog admin-data-modal">
        <div class="travel-modal__header">
          <div>
            <h2>데이터 백업/복구</h2>
            <p>현재 저장된 데이터 통계, 수동 백업, Google Drive 백업 목록 조회와 복구를 한 화면에서 관리합니다.</p>
          </div>
          <button class="button button--ghost" type="button" @click="closeDataManagement">
            닫기
          </button>
        </div>
        <div class="travel-modal__body">
          <div v-if="state.dataManagementError" class="feedback feedback--error">{{ state.dataManagementError }}</div>

          <section class="panel panel--compact">
            <div class="panel__header">
              <div>
                <h2>현재 데이터 통계</h2>
                <p>관리자 기준으로 저장된 전체 데이터의 개수와 합계를 요약합니다.</p>
              </div>
              <button class="button button--ghost" type="button" :disabled="state.loadingDataManagement" @click="loadDataManagement">
                {{ state.loadingDataManagement ? '불러오는 중...' : '통계 새로고침' }}
              </button>
            </div>
            <div v-if="state.dataManagement?.stats?.sections?.length" class="admin-data-stats">
              <section
                v-for="section in state.dataManagement.stats.sections"
                :key="section.key"
                class="admin-data-stats__section"
              >
                <h3>{{ section.title }}</h3>
                <div class="sheet-table-wrap">
                  <table class="sheet-table">
                    <tbody>
                      <tr v-for="item in section.items" :key="`${section.key}-${item.label}`">
                        <th>{{ item.label }}</th>
                        <td>{{ item.value }}</td>
                      </tr>
                    </tbody>
                  </table>
                </div>
              </section>
            </div>
            <p v-else class="panel__empty">
              {{ state.loadingDataManagement ? '현재 데이터 통계를 불러오는 중입니다.' : '표시할 데이터 통계가 없습니다.' }}
            </p>
          </section>

          <section class="panel panel--compact">
            <div class="panel__header">
              <div>
                <h2>파일 백업/복구</h2>
                <p>현재 데이터를 Google Drive 백업 형식인 `.sql.gz` 파일로 내려받고, 준비한 `.sql` 파일을 업로드해 현재 데이터에 복구 적용할 수 있습니다.</p>
              </div>
            </div>
            <div class="admin-data-upload-row">
              <button
                class="button button--secondary"
                type="button"
                :disabled="state.downloadingDataBackup || state.dataManagement?.busy"
                @click="handleDownloadDataBackup"
              >
                {{ state.downloadingDataBackup ? '백업 파일 준비 중...' : '현재 데이터 파일로 다운로드' }}
              </button>
              <label class="field admin-data-upload-field">
                <span class="field__label">복구할 SQL 파일</span>
                <input
                  type="file"
                  accept=".sql"
                  :disabled="state.restoringUploadedBackup || state.dataManagement?.busy"
                  @change="handleRestoreUploadSelection"
                />
              </label>
              <button
                class="button button--ghost"
                type="button"
                :disabled="!state.restoreUploadFile || state.restoringUploadedBackup || state.dataManagement?.busy"
                @click="handleRestoreUploadedBackup"
              >
                {{ state.restoringUploadedBackup ? '업로드 복구 중...' : '업로드 파일로 복구' }}
              </button>
            </div>
            <p v-if="state.restoreUploadFile" class="field__hint">
              선택한 파일: {{ state.restoreUploadFile.name }}
            </p>
          </section>

          <section class="panel panel--compact">
            <div class="panel__header">
              <div>
                <h2>수동 백업</h2>
                <p>현재 상태를 즉시 Google Drive 백업 목록에 추가합니다. 자동 새벽 백업과 별개로 원하는 시점 백업을 만들 수 있습니다.</p>
              </div>
              <div class="admin-data-status">
                <span v-if="state.dataManagement?.busy" class="entry-type-pill entry-type-pill--expense">
                  {{ state.dataManagement?.runningOperation === 'restore' ? '복구 진행 중' : '백업 진행 중' }}
                </span>
                <button
                  class="button button--primary"
                  type="button"
                  :disabled="state.creatingDataBackup || state.loadingDataManagement || state.dataManagement?.busy"
                  @click="handleCreateDataBackup"
                >
                  {{ state.creatingDataBackup ? '백업 생성 중...' : '지금 백업하기' }}
                </button>
              </div>
            </div>
          </section>

          <section class="panel panel--compact">
            <div class="panel__header">
              <div>
                <h2>백업 목록</h2>
                <p>Google Drive에 저장된 백업 파일 목록입니다. 복구할 시점을 선택해 즉시 현재 데이터에 적용할 수 있습니다.</p>
              </div>
            </div>
            <div v-if="state.dataManagement?.backupsError" class="feedback feedback--error">
              {{ state.dataManagement.backupsError }}
            </div>
            <div class="sheet-table-wrap">
              <table class="sheet-table">
                <thead>
                  <tr>
                    <th>파일명</th>
                    <th>수정 시각</th>
                    <th>크기</th>
                    <th>관리</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-if="!(state.dataManagement?.backups?.length)">
                    <td colspan="4" class="sheet-table__empty">
                      {{ state.loadingDataManagement ? '백업 목록을 불러오는 중입니다.' : '표시할 백업 파일이 없습니다.' }}
                    </td>
                  </tr>
                  <tr v-for="backup in state.dataManagement?.backups ?? []" :key="backup.fileName">
                    <td>{{ backup.fileName }}</td>
                    <td>{{ formatDateTime(backup.modifiedAt) }}</td>
                    <td>{{ formatFileSize(backup.sizeBytes) }}</td>
                    <td>
                      <button
                        class="button button--ghost"
                        type="button"
                        :disabled="state.restoringBackupFile === backup.fileName || state.dataManagement?.busy"
                        @click="handleRestoreDataBackup(backup)"
                      >
                        {{ state.restoringBackupFile === backup.fileName ? '복구 중...' : '이 시점으로 복구' }}
                      </button>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </section>

          <section class="panel panel--compact">
            <div class="panel__header">
              <div>
                <h2>MinIO 파일 백업</h2>
                <p>여행 사진, GPX, 가족 앨범, 문의 첨부처럼 MinIO 버킷에 저장된 파일을 묶어서 Google Drive에 백업합니다.</p>
              </div>
              <div class="admin-data-status">
                <span v-if="state.dataManagement?.minioStorage" class="field__hint">
                  버킷 {{ state.dataManagement.minioStorage.bucketName || '-' }} · 파일 {{ state.dataManagement.minioStorage.objectCount ?? 0 }}개 · {{ formatFileSize(state.dataManagement.minioStorage.totalSizeBytes ?? 0) }}
                </span>
                <button
                  class="button button--primary"
                  type="button"
                  :disabled="state.creatingMinioBackup || state.loadingDataManagement || state.dataManagement?.busy"
                  @click="handleCreateMinioBackup"
                >
                  {{ state.creatingMinioBackup ? 'MinIO 백업 생성 중...' : '지금 MinIO 백업하기' }}
                </button>
              </div>
            </div>
            <div v-if="state.dataManagement?.minioStorage?.errorMessage" class="feedback feedback--error">
              {{ state.dataManagement.minioStorage.errorMessage }}
            </div>
            <div v-if="state.dataManagement?.minioBackupsError" class="feedback feedback--error">
              {{ state.dataManagement.minioBackupsError }}
            </div>
            <div class="sheet-table-wrap">
              <table class="sheet-table">
                <thead>
                  <tr>
                    <th>파일명</th>
                    <th>수정 시각</th>
                    <th>크기</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-if="!(state.dataManagement?.minioBackups?.length)">
                    <td colspan="3" class="sheet-table__empty">
                      {{ state.loadingDataManagement ? 'MinIO 백업 목록을 불러오는 중입니다.' : '표시할 MinIO 백업 파일이 없습니다.' }}
                    </td>
                  </tr>
                  <tr v-for="backup in state.dataManagement?.minioBackups ?? []" :key="`minio-${backup.fileName}`">
                    <td>{{ backup.fileName }}</td>
                    <td>{{ formatDateTime(backup.modifiedAt) }}</td>
                    <td>{{ formatFileSize(backup.sizeBytes) }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </section>
        </div>
      </div>
    </div>

    <template v-if="state.adminAccessVerified">
      <div v-if="state.errorMessage" class="feedback feedback--error">{{ state.errorMessage }}</div>

      <section class="panel">
        <div class="panel__header">
          <div>
            <h2>관리자 페이지</h2>
            <p>{{ currentUser.displayName }} 계정으로 로그인한 상태에서 사용자 상태, 로그인 기록, 문의 메일함을 관리합니다.</p>
          </div>
          <div class="admin-toolbar">
            <button class="button button--ghost" type="button" :disabled="state.loading" @click="openDataManagement">
              데이터 백업/복구
            </button>
            <button class="button button--ghost" type="button" :disabled="state.loading" @click="loadDashboard">
              {{ state.loading ? '불러오는 중...' : '새로고침' }}
            </button>
          </div>
        </div>
      </section>

      <section class="summary-grid">
        <article v-for="card in summaryCards" :key="card.key" class="summary-card">
          <span>{{ card.label }}</span>
          <strong>{{ state.summary?.[card.key] ?? 0 }}</strong>
        </article>
      </section>

      <section class="panel">
        <div class="panel__header">
          <div>
            <h2>문의 메일함</h2>
            <p>답변 완료된 문의는 자동으로 보관함으로 이동하고, 보관함에서는 꺼내기·삭제·재답변이 가능합니다.</p>
          </div>
          <div class="scope-toggle scope-toggle--wrap">
            <button
              class="button button--ghost"
              type="button"
              :class="{ 'is-active': state.supportTab === 'inbox' }"
              @click="setSupportTab('inbox')"
            >
              진행 중
            </button>
            <button
              class="button button--ghost"
              type="button"
              :class="{ 'is-active': state.supportTab === 'archive' }"
              @click="setSupportTab('archive')"
            >
              보관함
            </button>
          </div>
        </div>
        <div class="sheet-table-wrap">
          <table class="sheet-table">
            <thead>
              <tr>
                <th>보낸 시각</th>
                <th>상태</th>
                <th>보낸 사람</th>
                <th>제목</th>
                <th>첨부</th>
                <th>관리</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="!pagedSupportInquiries.length">
                <td colspan="6" class="sheet-table__empty">
                  {{ state.supportTab === 'archive' ? '보관된 문의가 없습니다.' : '진행 중인 문의가 없습니다.' }}
                </td>
              </tr>
              <tr
                v-for="inquiry in pagedSupportInquiries"
                :key="inquiry.id"
                :class="{ 'support-inquiry-row--selected': inquiry.id === state.selectedSupportInquiryId }"
              >
                <td>{{ formatDateTime(inquiry.createdAt) }}</td>
                <td>
                  <span :class="['entry-type-pill', inquiry.status === 'ANSWERED' ? 'entry-type-pill--income' : 'entry-type-pill--expense']">
                    {{ inquiryStatusLabel[inquiry.status] ?? inquiry.status }}
                  </span>
                </td>
                <td>{{ inquiry.senderDisplayName }} ({{ inquiry.senderLoginId }})</td>
                <td>{{ inquiry.title }}</td>
                <td>{{ inquiry.attachmentFileName || '-' }}</td>
                <td>
                  <button class="button button--ghost" type="button" @click="selectSupportInquiry(inquiry)">
                    {{ inquiry.id === state.selectedSupportInquiryId ? '선택됨' : '열기' }}
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="panel__actions">
          <button
            class="button button--ghost"
            type="button"
            :disabled="currentSupportPage <= 0"
            @click="state.supportPages[state.supportTab] = currentSupportPage - 1"
          >
            이전
          </button>
          <span>{{ currentSupportPage + 1 }} / {{ supportPageCount }}</span>
          <button
            class="button button--ghost"
            type="button"
            :disabled="currentSupportPage + 1 >= supportPageCount"
            @click="state.supportPages[state.supportTab] = currentSupportPage + 1"
          >
            다음
          </button>
        </div>
      </section>

      <section class="panel">
        <div class="panel__header">
          <div>
            <h2>문의 상세 / 답변</h2>
            <p>선택한 문의 내용을 확인하고 답변을 저장하면 사용자 프로필에서 답변을 확인할 수 있습니다.</p>
          </div>
        </div>

        <div v-if="selectedSupportInquiry" class="support-detail-grid">
          <div class="support-inquiry-card">
            <div class="support-inquiry-meta">
              <strong>{{ selectedSupportInquiry.title }}</strong>
              <span :class="['entry-type-pill', selectedSupportInquiry.status === 'ANSWERED' ? 'entry-type-pill--income' : 'entry-type-pill--expense']">
                {{ inquiryStatusLabel[selectedSupportInquiry.status] ?? selectedSupportInquiry.status }}
              </span>
            </div>
            <small>{{ selectedSupportInquiry.senderDisplayName }} ({{ selectedSupportInquiry.senderLoginId }}) · {{ formatDateTime(selectedSupportInquiry.createdAt) }}</small>
            <p class="support-inquiry-content">{{ selectedSupportInquiry.content }}</p>

            <div v-if="selectedSupportInquiry.attachmentUrl" class="support-inquiry-attachment">
              <a class="button button--ghost" :href="selectedSupportInquiry.attachmentUrl" target="_blank" rel="noreferrer">
                첨부 이미지 열기
              </a>
              <img
                v-if="selectedSupportInquiry.attachmentContentType?.startsWith('image/')"
                :src="buildThumbnailUrl(selectedSupportInquiry.attachmentUrl)"
                :alt="selectedSupportInquiry.attachmentFileName || selectedSupportInquiry.title"
                loading="eager"
                fetchpriority="high"
                decoding="async"
                class="support-inquiry-preview"
              />
            </div>
          </div>

          <div class="support-inquiry-card">
            <div class="support-inquiry-reply__header">
              <strong>{{ state.supportTab === 'archive' ? '보관 문의 관리' : '답변하기' }}</strong>
              <small>
                {{ selectedSupportInquiry.replyContent ? '기존 답변을 수정할 수 있습니다.' : '아직 답변하지 않은 문의입니다.' }}
              </small>
            </div>
            <textarea
              v-model="state.supportReplyContent"
              rows="8"
              placeholder="사용자에게 전달할 답변을 입력해 주세요."
              :disabled="state.savingReply || state.mutatingInquiryId === selectedSupportInquiry.id"
            />
            <div class="support-inquiry-actions">
              <button
                class="button button--ghost"
                type="button"
                :disabled="state.mutatingInquiryId === selectedSupportInquiry.id"
                @click="handleArchiveToggle(selectedSupportInquiry, !selectedSupportInquiry.archived)"
              >
                {{ selectedSupportInquiry.archived ? '꺼내기' : '보관함으로' }}
              </button>
              <button
                v-if="selectedSupportInquiry.archived"
                class="button button--ghost"
                type="button"
                :disabled="state.mutatingInquiryId === selectedSupportInquiry.id"
                @click="handleDeleteSupportInquiry(selectedSupportInquiry)"
              >
                삭제
              </button>
              <button
                class="button button--primary"
                type="button"
                :disabled="state.savingReply"
                @click="handleReplySupportInquiry"
              >
                {{ state.savingReply ? '저장 중...' : selectedSupportInquiry.replyContent ? '답변 저장' : '답변 등록' }}
              </button>
            </div>
          </div>
        </div>
        <p v-else class="panel__empty">왼쪽 목록에서 확인할 문의를 선택해 주세요.</p>
      </section>

      <section class="panel">
        <div class="panel__header">
          <div>
            <h2>현재 차단된 IP</h2>
            <p>5회 이상 실패로 24시간 차단된 IP를 확인하고 즉시 해제할 수 있습니다.</p>
          </div>
        </div>
        <div class="sheet-table-wrap">
          <table class="sheet-table">
            <thead>
              <tr>
                <th>IP</th>
                <th>실패 횟수</th>
                <th>차단 해제 예정</th>
                <th>관리</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="!pagedBlockedIps.length">
                <td colspan="4" class="sheet-table__empty">현재 차단된 IP가 없습니다.</td>
              </tr>
              <tr v-for="blockedIp in pagedBlockedIps" :key="blockedIp.clientIp">
                <td>{{ blockedIp.clientIp }}</td>
                <td>{{ blockedIp.failureCount }}회</td>
                <td>{{ formatDateTime(blockedIp.lockedUntil) }}</td>
                <td>
                  <button
                    class="button button--ghost"
                    type="button"
                    :disabled="state.unlockingIp === blockedIp.clientIp"
                    @click="handleUnlockIp(blockedIp.clientIp)"
                  >
                    {{ state.unlockingIp === blockedIp.clientIp ? '해제 중...' : '차단 해제' }}
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="panel__actions">
          <button class="button button--ghost" type="button" :disabled="state.blockedIpPage <= 0" @click="state.blockedIpPage -= 1">이전</button>
          <span>{{ state.blockedIpPage + 1 }} / {{ blockedIpPageCount }}</span>
          <button class="button button--ghost" type="button" :disabled="state.blockedIpPage + 1 >= blockedIpPageCount" @click="state.blockedIpPage += 1">다음</button>
        </div>
      </section>

      <section class="panel">
        <div class="panel__header">
          <div>
            <h2>최근 로그인 기록</h2>
            <p>최근 10개만 먼저 보여주고, 이후 기록은 페이지를 넘겨 확인할 수 있습니다.</p>
          </div>
        </div>
        <div class="sheet-table-wrap">
          <table class="sheet-table">
            <thead>
              <tr>
                <th>시각</th>
                <th>상태</th>
                <th>로그인 ID</th>
                <th>표시 이름</th>
                <th>IP</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="!state.recentLoginLogs.length">
                <td colspan="5" class="sheet-table__empty">아직 기록된 로그인 로그가 없습니다.</td>
              </tr>
              <tr v-for="log in state.recentLoginLogs" :key="log.id">
                <td>{{ formatDateTime(log.attemptedAt) }}</td>
                <td>
                  <span :class="['entry-type-pill', log.success ? 'entry-type-pill--income' : 'entry-type-pill--expense']">
                    {{ loginStatusLabel[log.status] ?? log.status }}
                  </span>
                </td>
                <td>{{ log.loginId }}</td>
                <td>{{ log.displayName || '-' }}</td>
                <td>{{ log.clientIp }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="panel__actions">
          <button
            class="button button--ghost"
            type="button"
            :disabled="state.loadingLoginLogs || state.loginLogPage.page <= 0"
            @click="loadLoginAuditLogs(state.loginLogPage.page - 1)"
          >
            이전
          </button>
          <span>{{ state.loginLogPage.page + 1 }} / {{ Math.max(state.loginLogPage.totalPages, 1) }}</span>
          <button
            class="button button--ghost"
            type="button"
            :disabled="state.loadingLoginLogs || state.loginLogPage.page + 1 >= Math.max(state.loginLogPage.totalPages, 1)"
            @click="loadLoginAuditLogs(state.loginLogPage.page + 1)"
          >
            다음
          </button>
        </div>
      </section>

      <section class="panel">
        <div class="panel__header">
          <div>
            <h2>사용자 상태</h2>
            <p>관리자 여부와 활성 상태를 확인하고, 일반 사용자만 활성/비활성 처리할 수 있습니다.</p>
          </div>
        </div>
        <div class="sheet-table-wrap">
          <table class="sheet-table">
            <thead>
              <tr>
                <th>로그인 ID</th>
                <th>이름</th>
                <th>권한</th>
                <th>상태</th>
                <th>관리</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="!pagedUsers.length">
                <td colspan="5" class="sheet-table__empty">사용자 정보가 없습니다.</td>
              </tr>
              <tr v-for="user in pagedUsers" :key="user.id">
                <td>{{ user.loginId }}</td>
                <td>{{ user.displayName }}</td>
                <td>
                  <span :class="['entry-type-pill', user.admin ? 'entry-type-pill--income' : 'entry-type-pill--expense']">
                    {{ user.admin ? '관리자' : '일반 사용자' }}
                  </span>
                </td>
                <td>{{ user.active ? '활성' : '비활성' }}</td>
                <td>
                  <template v-if="user.admin">-</template>
                  <button
                    v-else
                    class="button button--ghost"
                    type="button"
                    :disabled="state.mutatingUserId === user.id"
                    @click="toggleUserActive(user)"
                  >
                    {{ state.mutatingUserId === user.id ? '처리 중...' : user.active ? '비활성화' : '활성화' }}
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="panel__actions">
          <button class="button button--ghost" type="button" :disabled="state.userPage <= 0" @click="state.userPage -= 1">이전</button>
          <span>{{ state.userPage + 1 }} / {{ userPageCount }}</span>
          <button class="button button--ghost" type="button" :disabled="state.userPage + 1 >= userPageCount" @click="state.userPage += 1">다음</button>
        </div>
      </section>

      <section class="panel">
        <div class="panel__header">
          <div>
            <h2>최근 초대 링크</h2>
            <p>최근에 발급된 초대 링크가 사용됐는지, 아직 남아 있는지 확인할 수 있습니다.</p>
          </div>
        </div>
        <div class="sheet-table-wrap">
          <table class="sheet-table">
            <thead>
              <tr>
                <th>생성 시각</th>
                <th>생성자</th>
                <th>만료 시각</th>
                <th>상태</th>
                <th>사용자</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="!pagedInvites.length">
                <td colspan="5" class="sheet-table__empty">초대 링크 기록이 없습니다.</td>
              </tr>
              <tr v-for="invite in pagedInvites" :key="invite.id">
                <td>{{ formatDateTime(invite.createdAt) }}</td>
                <td>{{ invite.createdByDisplayName }} ({{ invite.createdByLoginId }})</td>
                <td>{{ formatDateTime(invite.expiresAt) }}</td>
                <td>{{ inviteStatusLabel[invite.status] ?? invite.status }}</td>
                <td>
                  <template v-if="invite.usedByLoginId">
                    {{ invite.usedByDisplayName }} ({{ invite.usedByLoginId }})
                  </template>
                  <template v-else>-</template>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="panel__actions">
          <button class="button button--ghost" type="button" :disabled="state.invitePage <= 0" @click="state.invitePage -= 1">이전</button>
          <span>{{ state.invitePage + 1 }} / {{ invitePageCount }}</span>
          <button class="button button--ghost" type="button" :disabled="state.invitePage + 1 >= invitePageCount" @click="state.invitePage += 1">다음</button>
        </div>
      </section>
    </template>
  </section>
</template>
