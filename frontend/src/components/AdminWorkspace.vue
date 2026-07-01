<script setup>
import { computed, onMounted, reactive } from 'vue'
import InviteAccessPanel from './InviteAccessPanel.vue'
import { buildThumbnailUrl } from '../lib/mediaPreview'
import {
  archiveAdminSupportInquiry,
  createAdminDataBackup,
  createAdminMinioBackup,
  createInvite,
  downloadAdminDataBackup,
  deleteAdminSupportInquiry,
  fetchAdminAccessStatus,
  fetchAdminDataManagement,
  fetchAdminDashboard,
  fetchAdminLoginAuditLogs,
  fetchAdminOpsControl,
  fetchAdminSupportInquiries,
  replyAdminSupportInquiry,
  restoreAdminDataBackup,
  restoreAdminUploadedBackup,
  unlockBlockedIp,
  updateAdminAiControl,
  updateAdminDataStorageControl,
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
  opsControl: null,
  opsControlError: '',
  opsControlMessage: '',
  opsControlCheckedAt: '',
  loadingOpsControl: false,
  savingAiControl: false,
  savingDataControl: false,
  aiControlForm: {
    enabled: false,
    provider: 'lmstudio',
    model: '',
    workflowUrl: '',
    apiKeyHeader: 'X-TravelLedger-AI-Key',
    apiKey: '',
    clearApiKey: false,
    lmStudioBaseUrl: '',
    lmStudioChatPath: '/v1/chat/completions',
    lmStudioModelsPath: '/v1/models',
    lmStudioApiKey: '',
    clearLmStudioApiKey: false,
    apiKeyConfigured: false,
    lmStudioApiKeyConfigured: false,
    temperature: 0.2,
    maxTokens: 4096,
    connectTimeoutSeconds: 3,
    readTimeoutSeconds: 120,
    enforceProviderUrlAllowlist: true,
    allowedProviderHosts: '',
  },
  dataControlForm: {
    minioStorageCapacityGb: 0,
  },
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
  creatingInvite: false,
  inviteManager: {
    expiresInHours: 72,
    generatedLink: '',
    generatedExpiresAt: '',
    feedbackMessage: '',
    errorMessage: '',
  },
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

function clearInviteManagerFeedback() {
  state.inviteManager.feedbackMessage = ''
  state.inviteManager.errorMessage = ''
}

function buildInviteUrl(token) {
  const path = window.location.pathname || '/'
  return `${window.location.origin}${path}#invite/${encodeURIComponent(token)}`
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
  state.loadingOpsControl = false
  state.savingAiControl = false
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
    await loadOpsControl()
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

async function handleCreateInvite() {
  state.creatingInvite = true
  clearInviteManagerFeedback()

  try {
    const response = await createInvite({
      expiresInHours: state.inviteManager.expiresInHours,
    })

    state.inviteManager.generatedLink = buildInviteUrl(response.token)
    state.inviteManager.generatedExpiresAt = response.expiresAt
    state.inviteManager.feedbackMessage = '1회용 초대 링크를 만들었습니다. 복사해서 전달해주세요.'
    await loadDashboard()
  } catch (error) {
    if (!handleAdminAccessRequired(error)) {
      state.inviteManager.errorMessage = error.message
    }
  } finally {
    state.creatingInvite = false
  }
}

async function copyInviteLink() {
  if (!state.inviteManager.generatedLink) {
    return
  }

  clearInviteManagerFeedback()

  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(state.inviteManager.generatedLink)
    } else {
      const element = document.createElement('textarea')
      element.value = state.inviteManager.generatedLink
      element.setAttribute('readonly', 'readonly')
      element.style.position = 'absolute'
      element.style.left = '-9999px'
      document.body.appendChild(element)
      element.select()
      document.execCommand('copy')
      element.remove()
    }

    state.inviteManager.feedbackMessage = '초대 링크를 클립보드에 복사했습니다.'
  } catch {
    state.inviteManager.errorMessage = '브라우저에서 자동 복사를 지원하지 않아 직접 복사해야 합니다.'
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

function syncAiControlForm(control = state.opsControl) {
  const ai = control?.ai
  if (!ai) {
    return
  }
  state.aiControlForm = {
    enabled: Boolean(ai.enabled),
    provider: ai.provider || 'lmstudio',
    model: ai.model || '',
    workflowUrl: ai.workflowUrl || '',
    apiKeyHeader: ai.apiKeyHeader || 'X-TravelLedger-AI-Key',
    apiKey: '',
    clearApiKey: false,
    lmStudioBaseUrl: ai.lmStudioBaseUrl || '',
    lmStudioChatPath: ai.lmStudioChatPath || '/v1/chat/completions',
    lmStudioModelsPath: ai.lmStudioModelsPath || '/v1/models',
    lmStudioApiKey: '',
    clearLmStudioApiKey: false,
    apiKeyConfigured: Boolean(ai.apiKeyConfigured),
    lmStudioApiKeyConfigured: Boolean(ai.lmStudioApiKeyConfigured),
    temperature: Number(ai.temperature ?? 0.2),
    maxTokens: Number(ai.maxTokens ?? 4096),
    connectTimeoutSeconds: Number(ai.connectTimeoutSeconds ?? 3),
    readTimeoutSeconds: Number(ai.readTimeoutSeconds ?? 120),
    enforceProviderUrlAllowlist: Boolean(ai.enforceProviderUrlAllowlist),
    allowedProviderHosts: ai.allowedProviderHosts || '',
  }
}

function syncDataControlForm(control = state.opsControl) {
  const capacityBytes = Number(control?.dataServer?.minioStorage?.capacityBytes ?? 0)
  state.dataControlForm = {
    minioStorageCapacityGb: bytesToGb(capacityBytes),
  }
}
function markOpsControlChecked() {
  state.opsControlCheckedAt = new Date().toISOString()
}

function formatOpsControlCheckedAt(value) {
  if (!value) {
    return '-'
  }
  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value))
}
async function loadOpsControl() {
  state.loadingOpsControl = true
  state.opsControlError = ''
  state.opsControlMessage = ''

  try {
    state.opsControl = await fetchAdminOpsControl()
    syncAiControlForm()
    syncDataControlForm()
    markOpsControlChecked()
  } catch (error) {
    if (!handleAdminAccessRequired(error)) {
      state.opsControlError = error.message || 'AI 및 서버 제어판 정보를 불러오지 못했습니다.'
    }
  } finally {
    state.loadingOpsControl = false
  }
}

async function handleSaveAiControl() {
  state.savingAiControl = true
  state.opsControlError = ''
  state.opsControlMessage = ''

  try {
    const payload = {
      enabled: Boolean(state.aiControlForm.enabled),
      provider: state.aiControlForm.provider,
      model: state.aiControlForm.model,
      workflowUrl: state.aiControlForm.workflowUrl,
      apiKeyHeader: state.aiControlForm.apiKeyHeader,
      lmStudioBaseUrl: state.aiControlForm.lmStudioBaseUrl,
      lmStudioChatPath: state.aiControlForm.lmStudioChatPath,
      lmStudioModelsPath: state.aiControlForm.lmStudioModelsPath,
      temperature: Number(state.aiControlForm.temperature),
      maxTokens: Number(state.aiControlForm.maxTokens),
      connectTimeoutSeconds: Number(state.aiControlForm.connectTimeoutSeconds),
      readTimeoutSeconds: Number(state.aiControlForm.readTimeoutSeconds),
      enforceProviderUrlAllowlist: Boolean(state.aiControlForm.enforceProviderUrlAllowlist),
      allowedProviderHosts: state.aiControlForm.allowedProviderHosts,
    }
    if (state.aiControlForm.clearApiKey) {
      payload.clearApiKey = true
    } else if (state.aiControlForm.apiKey?.trim()) {
      payload.apiKey = state.aiControlForm.apiKey.trim()
    }
    if (state.aiControlForm.clearLmStudioApiKey) {
      payload.clearLmStudioApiKey = true
    } else if (state.aiControlForm.lmStudioApiKey?.trim()) {
      payload.lmStudioApiKey = state.aiControlForm.lmStudioApiKey.trim()
    }
    state.opsControl = await updateAdminAiControl(payload)
    syncAiControlForm()
    syncDataControlForm()
    markOpsControlChecked()
    state.opsControlMessage = 'AI 설정이 적용되었습니다. 설정 저장소 상태를 확인하세요.'
  } catch (error) {
    if (!handleAdminAccessRequired(error)) {
      state.opsControlError = error.message || 'AI 설정을 저장하지 못했습니다.'
    }
  } finally {
    state.savingAiControl = false
  }
}

async function handleSaveDataControl() {
  state.savingDataControl = true
  state.opsControlError = ''
  state.opsControlMessage = ''

  try {
    state.opsControl = await updateAdminDataStorageControl({
      minioStorageCapacityBytes: gbToBytes(state.dataControlForm.minioStorageCapacityGb),
    })
    syncAiControlForm()
    syncDataControlForm()
    markOpsControlChecked()
    state.opsControlMessage = '데이터 서버 설정이 적용되었습니다. 설정 저장소 상태를 확인하세요.'
  } catch (error) {
    if (!handleAdminAccessRequired(error)) {
      state.opsControlError = error.message || '데이터 서버 설정을 저장하지 못했습니다.'
    }
  } finally {
    state.savingDataControl = false
  }
}

function bytesToGb(bytes) {
  const value = Number(bytes)
  if (!Number.isFinite(value) || value <= 0) {
    return 0
  }
  return Number((value / 1024 / 1024 / 1024).toFixed(2))
}

function gbToBytes(gb) {
  const value = Number(gb)
  if (!Number.isFinite(value) || value <= 0) {
    return 0
  }
  return Math.round(value * 1024 * 1024 * 1024)
}
function formatPercent(value) {
  const numberValue = Number(value)
  if (!Number.isFinite(numberValue)) {
    return '0%'
  }
  return `${numberValue.toFixed(numberValue >= 10 ? 0 : 1)}%`
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
            <h2>AI 및 서버 제어판</h2>
            <p>AI 분석 기능을 켜고 끄며 LM Studio 연결 정보, AI 서버 상태, 데이터 서버 상태를 한곳에서 확인하고 조절합니다.</p>
          </div>
          <div class="admin-toolbar">
            <button class="button button--ghost" type="button" :disabled="state.loadingOpsControl" @click="loadOpsControl">
              {{ state.loadingOpsControl ? '점검 중...' : '상태 새로고침' }}
            </button>
          </div>
        </div>

        <div v-if="state.opsControlError" class="feedback feedback--error" role="alert">{{ state.opsControlError }}</div>
        <div v-if="state.opsControlMessage" class="feedback feedback--success" role="status" aria-live="polite">{{ state.opsControlMessage }}</div>
        <p v-if="state.opsControlCheckedAt" class="field__hint">최근 점검: {{ formatOpsControlCheckedAt(state.opsControlCheckedAt) }}</p>
        <p v-if="state.opsControl?.persistenceMessage" class="field__hint">설정 저장소: {{ state.opsControl.persistenceMessage }}</p>

        <div class="summary-grid">
          <article class="summary-card">
            <span>AI 기능</span>
            <strong>{{ state.opsControl?.ai?.enabled ? '켜짐' : '꺼짐' }}</strong>
            <small>{{ state.opsControl?.ai?.statusMessage || '-' }}</small>
          </article>
          <article class="summary-card">
            <span>AI 서버</span>
            <strong>{{ state.opsControl?.aiServer?.reachable ? '정상' : '확인 필요' }}</strong>
            <small>{{ state.opsControl?.aiServer?.message || '-' }}</small>
          </article>
          <article class="summary-card">
            <span>DB 서버</span>
            <strong>{{ state.opsControl?.dataServer?.databaseReachable ? '정상' : '확인 필요' }}</strong>
            <small>{{ state.opsControl?.dataServer?.databaseHost || '-' }}</small>
          </article>
          <article class="summary-card">
            <span>데이터 스토리지 잔여</span>
            <strong>{{ formatFileSize(state.opsControl?.dataServer?.minioStorage?.remainingBytes || 0) }}</strong>
            <small>
              {{ formatFileSize(state.opsControl?.dataServer?.minioStorage?.totalSizeBytes || 0) }} / {{ formatFileSize(state.opsControl?.dataServer?.minioStorage?.capacityBytes || 0) }} 사용
            </small>
          </article>
        </div>

        <form class="support-detail-grid" @submit.prevent="handleSaveAiControl">
          <div class="support-inquiry-card">
            <div class="support-inquiry-reply__header">
              <strong>AI 기능 제어</strong>
              <small>일반 설정은 저장되어 재시작 후에도 복원됩니다. API key는 보안상 현재 실행 중인 서버에만 반영됩니다.</small>
            </div>
            <label class="field">
              <span class="field__label">AI 분석 사용</span>
              <input v-model="state.aiControlForm.enabled" type="checkbox" />
            </label>
            <label class="field">
              <span class="field__label">Provider</span>
              <select v-model="state.aiControlForm.provider">
                <option value="lmstudio">LM Studio</option>
                <option value="n8n">n8n</option>
              </select>
            </label>
            <label class="field">
              <span class="field__label">모델</span>
              <input v-model="state.aiControlForm.model" placeholder="google/gemma-4-e2b 또는 auto" />
            </label>
            <label class="field">
              <span class="field__label">n8n webhook URL</span>
              <input v-model="state.aiControlForm.workflowUrl" placeholder="https://n8n.example.com/webhook/..." />
            </label>
            <label class="field">
              <span class="field__label">n8n API key header</span>
              <input v-model="state.aiControlForm.apiKeyHeader" placeholder="X-TravelLedger-AI-Key" />
            </label>
            <label class="field">
              <span class="field__label">n8n API key 변경</span>
              <input v-model="state.aiControlForm.apiKey" type="password" autocomplete="new-password" :disabled="state.aiControlForm.clearApiKey" :placeholder="state.aiControlForm.apiKeyConfigured ? '설정됨 - 변경할 때만 입력' : '미설정'" />
            </label>
            <label class="field field--inline">
              <input v-model="state.aiControlForm.clearApiKey" type="checkbox" />
              <span class="field__label">n8n API key 삭제</span>
            </label>
            <label class="field">
              <span class="field__label">LM Studio URL</span>
              <input v-model="state.aiControlForm.lmStudioBaseUrl" placeholder="http://100.92.170.22:1234" />
            </label>
            <label class="field">
              <span class="field__label">LM Studio API key 변경</span>
              <input v-model="state.aiControlForm.lmStudioApiKey" type="password" autocomplete="new-password" :disabled="state.aiControlForm.clearLmStudioApiKey" :placeholder="state.aiControlForm.lmStudioApiKeyConfigured ? '설정됨 - 변경할 때만 입력' : '미설정'" />
            </label>
            <label class="field field--inline">
              <input v-model="state.aiControlForm.clearLmStudioApiKey" type="checkbox" />
              <span class="field__label">LM Studio API key 삭제</span>
            </label>
            <label class="field">
              <span class="field__label">Chat path</span>
              <input v-model="state.aiControlForm.lmStudioChatPath" placeholder="/v1/chat/completions" />
            </label>
            <label class="field">
              <span class="field__label">Models path</span>
              <input v-model="state.aiControlForm.lmStudioModelsPath" placeholder="/v1/models" />
            </label>
          </div>

          <div class="support-inquiry-card">
            <div class="support-inquiry-reply__header">
              <strong>응답/보안 조절</strong>
              <small>응답 품질과 안전한 provider host 범위를 조절합니다.</small>
            </div>
            <label class="field">
              <span class="field__label">Temperature</span>
              <input v-model.number="state.aiControlForm.temperature" type="number" min="0" max="2" step="0.1" />
            </label>
            <label class="field">
              <span class="field__label">Max tokens</span>
              <input v-model.number="state.aiControlForm.maxTokens" type="number" min="128" max="8192" step="128" />
            </label>
            <label class="field">
              <span class="field__label">연결 제한 시간(초)</span>
              <input v-model.number="state.aiControlForm.connectTimeoutSeconds" type="number" min="1" max="600" />
            </label>
            <label class="field">
              <span class="field__label">응답 제한 시간(초)</span>
              <input v-model.number="state.aiControlForm.readTimeoutSeconds" type="number" min="1" max="600" />
            </label>
            <label class="field">
              <span class="field__label">Provider 허용 목록 강제</span>
              <input v-model="state.aiControlForm.enforceProviderUrlAllowlist" type="checkbox" />
            </label>
            <label class="field">
              <span class="field__label">허용 호스트</span>
              <input v-model="state.aiControlForm.allowedProviderHosts" placeholder="100.92.170.22,127.0.0.1,localhost" />
            </label>
            <div class="panel__actions">
              <button class="button button--primary" type="submit" :disabled="state.savingAiControl">
                {{ state.savingAiControl ? '저장 중...' : 'AI 설정 저장' }}
              </button>
            </div>
          </div>
        </form>
        <form class="support-detail-grid" @submit.prevent="handleSaveDataControl">
          <div class="support-inquiry-card">
            <div class="support-inquiry-reply__header">
              <strong>데이터 서버 제어</strong>
              <small>MinIO 잔여 용량 계산에 사용할 운영 기준 용량을 조절합니다. 설정은 저장되어 재시작 후에도 복원됩니다.</small>
            </div>
            <label class="field">
              <span class="field__label">스토리지 기준 용량(GB)</span>
              <input v-model.number="state.dataControlForm.minioStorageCapacityGb" type="number" min="0" max="1048576" step="1" />
            </label>
            <p class="form-hint">
              현재 사용량 {{ formatFileSize(state.opsControl?.dataServer?.minioStorage?.totalSizeBytes || 0) }}, 남은 용량 {{ formatFileSize(state.opsControl?.dataServer?.minioStorage?.remainingBytes || 0) }}입니다.
            </p>
            <div class="panel__actions">
              <button class="button button--primary" type="submit" :disabled="state.savingDataControl">
                {{ state.savingDataControl ? '저장 중...' : '스토리지 기준 저장' }}
              </button>
            </div>
          </div>

          <div class="support-inquiry-card">
            <div class="support-inquiry-reply__header">
              <strong>운영 참고</strong>
              <small>0GB는 용량 기준 미설정입니다. 최대 1PB까지 입력할 수 있으며, 실제 디스크 여유 공간이 아니라 MinIO 객체 사용량과 설정한 기준 용량으로 남은 용량을 계산합니다.</small>
            </div>
            <p class="form-hint">
              백업, 여행 미디어, 드라이브 파일이 같은 버킷을 사용하면 이 기준값을 실제 서버 디스크/오브젝트 스토리지 한도에 맞춰 관리하세요.
            </p>
          </div>
        </form>
        <div class="sheet-table-wrap">
          <table class="sheet-table">
            <thead>
              <tr>
                <th>대상</th>
                <th>상태</th>
                <th>상세</th>
                <th>측정값</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td>AI 서버</td>
                <td>{{ state.opsControl?.aiServer?.reachable ? '정상' : '확인 필요' }}</td>
                <td>{{ state.opsControl?.aiServer?.baseUrl || '-' }} {{ state.opsControl?.aiServer?.modelsPath || '' }}</td>
                <td>{{ state.opsControl?.aiServer?.latencyMillis || 0 }} ms / {{ state.opsControl?.aiServer?.models?.join(', ') || '모델 없음' }}</td>
              </tr>
              <tr>
                <td>DB 서버</td>
                <td>{{ state.opsControl?.dataServer?.databaseReachable ? '정상' : '확인 필요' }}</td>
                <td>{{ state.opsControl?.dataServer?.databaseProduct || '-' }} @ {{ state.opsControl?.dataServer?.databaseHost || '-' }}</td>
                <td>{{ state.opsControl?.dataServer?.databaseMessage || '-' }}</td>
              </tr>
              <tr>
                <td>MinIO 스토리지</td>
                <td>{{ state.opsControl?.dataServer?.minioStorage?.available ? '정상' : '확인 필요' }}</td>
                <td>버킷 {{ state.opsControl?.dataServer?.minioStorage?.bucketName || '-' }}, 객체 {{ state.opsControl?.dataServer?.minioStorage?.objectCount || 0 }}개</td>
                <td>{{ formatPercent(state.opsControl?.dataServer?.minioStorage?.usedPercent || 0) }} 사용, {{ formatFileSize(state.opsControl?.dataServer?.minioStorage?.remainingBytes || 0) }} 남음</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
      <InviteAccessPanel
        :expires-in-hours="state.inviteManager.expiresInHours"
        :generated-link="state.inviteManager.generatedLink"
        :generated-expires-at="state.inviteManager.generatedExpiresAt"
        :is-creating="state.creatingInvite"
        :feedback-message="state.inviteManager.feedbackMessage"
        :error-message="state.inviteManager.errorMessage"
        @change-expiry="state.inviteManager.expiresInHours = $event"
        @create-invite="handleCreateInvite"
        @copy-invite="copyInviteLink"
      />

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
