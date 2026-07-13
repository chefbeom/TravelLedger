<script setup>
import { computed, onBeforeUnmount, onMounted, reactive } from 'vue'
import InviteAccessPanel from './InviteAccessPanel.vue'
import { buildThumbnailUrl } from '../lib/mediaPreview'
import {
  archiveAdminSupportInquiry,
  createAdminDataBackup,
  createAdminMinioBackup,
  deleteAdminAiControlPresetSecret,
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
  updateAdminAiRouting,
  updateAdminDataStorageControl,
  updateAdminSupportInquiryStatus,
  updateAdminUserActive,
  verifyAdminAccess,
} from '../lib/api'

const props = defineProps({
  currentUser: {
    type: Object,
    required: true,
  },
})

const emit = defineEmits(['exit-admin-access'])

const PAGE_SIZE = 10
const AI_CONTROL_PRESETS_STORAGE_KEY = 'travelledger:admin-ai-control-presets:v1'
const AI_CONTROL_CANDIDATES_STORAGE_KEY = 'travelledger:admin-ai-control-candidates:v1'
const AI_CONTROL_CANDIDATE_LIMIT = 3

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
  opsControlModalOpen: false,
  opsControlModalView: 'menu',
  aiServerWizardStep: 1,
  aiServerDraftName: '',
  activeAdminPanel: '',
  aiControlPresets: [],
  aiControlPresetKey: '',
  aiCandidateServerKeys: [],
  aiCandidateServerPickerKey: '',
  aiFeatureConnections: { ledger: '', image: '', excel: '' },
  aiTargetFeature: 'ledger',
  aiControlPreserveCurrentSecret: false,
  aiServerStatusOpen: false,
  aiServerEditorOpen: false,
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
    openAiBaseUrl: 'https://api.openai.com',
    openAiChatPath: '/v1/chat/completions',
    openAiModelsPath: '/v1/models',
    openAiApiKey: '',
    clearOpenAiApiKey: false,
    ollamaBaseUrl: 'http://localhost:11434',
    ollamaChatPath: '/api/chat',
    ollamaModelsPath: '/api/tags',
    ollamaApiKey: '',
    clearOllamaApiKey: false,
    apiKeyConfigured: false,
    lmStudioApiKeyConfigured: false,
    openAiApiKeyConfigured: false,
    ollamaApiKeyConfigured: false,
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
  accessModalOpen: false,
  accessModalView: 'invite',
  recentInvites: [],
  invitePage: 0,
  supportInquiries: [],
  supportModalOpen: false,
  supportTab: 'pending',
  supportPages: { pending: 0, processing: 0, done: 0 },
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

const pendingSupportInquiries = computed(() => (
  state.supportInquiries.filter((inquiry) => !inquiry.archived && inquiry.status === 'PENDING')
))

const processingSupportInquiries = computed(() => (
  state.supportInquiries.filter((inquiry) => !inquiry.archived && inquiry.status === 'IN_PROGRESS')
))

const completedSupportInquiries = computed(() => (
  state.supportInquiries.filter((inquiry) => inquiry.archived || inquiry.status === 'ANSWERED')
))

const inboxSupportInquiries = computed(() => ([
  ...pendingSupportInquiries.value,
  ...processingSupportInquiries.value,
]))

const archivedSupportInquiries = completedSupportInquiries

const supportTabOptions = computed(() => [
  { key: 'pending', label: '미확인', count: pendingSupportInquiries.value.length },
  { key: 'processing', label: '진행중', count: processingSupportInquiries.value.length },
  { key: 'done', label: '완료됨', count: completedSupportInquiries.value.length },
])

const currentSupportSource = computed(() => {
  if (state.supportTab === 'processing') {
    return processingSupportInquiries.value
  }
  if (state.supportTab === 'done') {
    return completedSupportInquiries.value
  }
  return pendingSupportInquiries.value
})

const currentSupportPage = computed(() => state.supportPages[state.supportTab] ?? 0)

const pagedSupportInquiries = computed(() => (
  paginate(currentSupportSource.value, currentSupportPage.value)
))

const supportPageCount = computed(() => Math.max(1, Math.ceil(currentSupportSource.value.length / PAGE_SIZE)))

const selectedSupportInquiry = computed(() => (
  state.supportInquiries.find((inquiry) => inquiry.id === state.selectedSupportInquiryId) ?? null
))

const pagedBlockedIps = computed(() => paginate(state.blockedIps, state.blockedIpPage))
const blockedIpPageCount = computed(() => Math.max(1, Math.ceil(state.blockedIps.length / PAGE_SIZE)))

const pagedUsers = computed(() => paginate(state.users, state.userPage))
const userPageCount = computed(() => Math.max(1, Math.ceil(state.users.length / PAGE_SIZE)))

const pagedInvites = computed(() => paginate(state.recentInvites, state.invitePage))
const invitePageCount = computed(() => Math.max(1, Math.ceil(state.recentInvites.length / PAGE_SIZE)))

const accessModalOptions = computed(() => [
  {
    key: 'invite',
    label: '초대 링크 생성',
    count: state.recentInvites.length,
  },
  {
    key: 'blocked',
    label: '차단된 IP 조회',
    count: state.blockedIps.length,
  },
  {
    key: 'logs',
    label: '최근 로그인 기록',
    count: state.loginLogPage.totalElements ?? state.recentLoginLogs.length,
  },
  {
    key: 'users',
    label: '사용자 상태',
    count: state.users.length,
  },
])

const adminPanelCards = computed(() => [
  {
    key: 'ops',
    eyebrow: 'AI / Server',
    title: 'AI 및 서버 제어판',
    metric: state.opsControl?.aiServer?.reachable ? 'AI 정상' : 'AI 점검 필요',
    detail: state.opsControl?.ai?.model || state.aiControlForm.model || '모델 미설정',
  },
  {
    key: 'support',
    eyebrow: 'Support',
    title: '문의 관리',
    metric: `${inboxSupportInquiries.value.length}건 대기`,
    detail: `보관 ${archivedSupportInquiries.value.length}건`,
  },
  {
    key: 'access',
    eyebrow: 'Access / Users',
    title: '접근 및 사용자 관리',
    metric: `${state.blockedIps.length}개 차단 IP`,
    detail: `미사용 초대 ${state.summary?.pendingInvites ?? 0}건`,
  },
])

function openAdminPanel(panel) {
  if (panel === 'ops') {
    openOpsControlModal('menu')
    return
  }
  if (panel === 'support') {
    openSupportModal('pending')
    return
  }
  if (panel === 'access') {
    openAccessModal('invite')
    return
  }
  state.activeAdminPanel = state.activeAdminPanel === panel ? '' : panel
}

function closeAdminPanel() {
  state.activeAdminPanel = ''
}

function openOpsControlModal(view = 'menu') {
  state.activeAdminPanel = ''
  state.opsControlModalOpen = true
  state.opsControlModalView = view
  if (!state.opsControl && !state.loadingOpsControl) {
    loadOpsControl()
  }
}

function closeOpsControlModal() {
  state.opsControlModalOpen = false
  state.opsControlModalView = 'menu'
  state.aiServerWizardStep = 1
}

function selectOpsControlModalView(view) {
  state.opsControlModalView = view
  if (view === 'status') {
    handleCheckAiServerStatus()
    return
  }
  if (view === 'add') {
    state.aiServerWizardStep = 1
    state.aiServerDraftName = state.aiServerDraftName || buildCurrentAiServerName()
  }
}

function startAiServerAdd() {
  state.aiControlPreserveCurrentSecret = false
  state.aiServerDraftName = buildCurrentAiServerName()
  selectOpsControlModalView('add')
}

function buildCurrentAiServerName() {
  const model = state.aiControlForm.model || 'auto'
  return `${currentAiProviderLabel()} - ${model}`
}

function isOpenAiCompatibleProvider(provider = state.aiControlForm.provider) {
  return provider === 'lmstudio' || provider === 'openai'
}

function currentAiServerAddress(source = state.aiControlForm) {
  if (source.provider === 'n8n') return source.workflowUrl || '-'
  if (source.provider === 'openai') return source.openAiBaseUrl || '-'
  if (source.provider === 'ollama') return source.ollamaBaseUrl || '-'
  return source.lmStudioBaseUrl || '-'
}

function hasAiServerAddress(source = state.aiControlForm) {
  if (source.provider === 'n8n') return Boolean(source.workflowUrl?.trim())
  if (source.provider === 'openai') return Boolean(source.openAiBaseUrl?.trim())
  if (source.provider === 'ollama') return Boolean(source.ollamaBaseUrl?.trim())
  return Boolean(source.lmStudioBaseUrl?.trim())
}

function currentAiProviderLabel(provider = state.aiControlForm.provider) {
  if (provider === 'openai') return 'OpenAI API'
  if (provider === 'ollama') return 'Ollama'
  return provider === 'n8n' ? 'n8n' : 'LM Studio'
}

function addAllowedProviderHost(host) {
  const values = String(state.aiControlForm.allowedProviderHosts || '')
    .split(',')
    .map((value) => value.trim())
    .filter(Boolean)
  if (!values.some((value) => value.toLowerCase() === host.toLowerCase())) {
    values.push(host)
    state.aiControlForm.allowedProviderHosts = values.join(',')
  }
}

function handleAiProviderChange() {
  state.aiControlPreserveCurrentSecret = false
  if (state.aiControlForm.provider === 'ollama') {
    state.aiControlForm.ollamaBaseUrl ||= 'http://localhost:11434'
    state.aiControlForm.ollamaChatPath ||= '/api/chat'
    state.aiControlForm.ollamaModelsPath ||= '/api/tags'
    state.aiControlForm.model = state.aiControlForm.model || 'llama3.2-vision'
    addAllowedProviderHost('localhost')
    return
  }
  if (state.aiControlForm.provider !== 'openai') return
  state.aiControlForm.openAiBaseUrl ||= 'https://api.openai.com'
  state.aiControlForm.openAiChatPath ||= '/v1/chat/completions'
  state.aiControlForm.openAiModelsPath ||= '/v1/models'
  if (!state.aiControlForm.model || state.aiControlForm.model.trim().toLowerCase() === 'auto') {
    state.aiControlForm.model = 'gpt-4.1-mini'
  }
  addAllowedProviderHost('api.openai.com')
}

function goNextAiServerStep() {
  state.aiServerWizardStep = Math.min(3, state.aiServerWizardStep + 1)
}

function goPrevAiServerStep() {
  state.aiServerWizardStep = Math.max(1, state.aiServerWizardStep - 1)
}

function applyAiControlPresetByKey(key) {
  const preset = state.aiControlPresets.find((item) => item.key === key)
  if (!preset) {
    return
  }

  state.aiControlPresetKey = key
  state.aiServerDraftName = preset.title || preset.model || 'AI 서버'
  applyAiControlPreset()
  state.aiServerWizardStep = 1
  state.opsControlModalView = 'add'
  state.opsControlMessage = '선택한 서버 설정을 편집 화면으로 불러왔습니다. 필요한 내용을 확인한 뒤 저장하세요.'
}

async function deleteAiControlPreset(key) {
  const preset = state.aiControlPresets.find((item) => item.key === key)
  if (!preset) {
    return
  }

  const title = preset.title || preset.model || 'AI 서버'
  if (!window.confirm('"' + title + '" 서버 프리셋과 암호화된 API 키를 삭제할까요?\n현재 적용 중인 AI 서버 설정은 변경되지 않습니다.')) {
    return
  }

  state.opsControlError = ''
  try {
    await deleteAdminAiControlPresetSecret(key)
    state.aiControlPresets = state.aiControlPresets.filter((item) => item.key !== key)
    removeAiCandidateServer(key)
    if (state.aiControlPresetKey === key) {
      state.aiControlPresetKey = ''
    }
    persistAiControlPresets()
    state.opsControlMessage = '서버 프리셋과 서버에 암호화 저장된 API 키를 삭제했습니다.'
  } catch (error) {
    if (!handleAdminAccessRequired(error)) {
      state.opsControlError = error.message || '서버 프리셋의 암호화 API 키를 삭제하지 못했습니다.'
    }
  }
}
function syncSelection(preferredId = state.selectedSupportInquiryId) {
  state.supportPages.pending = clampPage(state.supportPages.pending, pendingSupportInquiries.value.length)
  state.supportPages.processing = clampPage(state.supportPages.processing, processingSupportInquiries.value.length)
  state.supportPages.done = clampPage(state.supportPages.done, completedSupportInquiries.value.length)

  const visibleList = currentSupportSource.value
  const preferred = visibleList.find((item) => item.id === preferredId)
  const fallback = pagedSupportInquiries.value[0] ?? visibleList[0] ?? null
  const nextInquiry = preferred ?? fallback
  state.selectedSupportInquiryId = nextInquiry?.id ?? null
  state.supportReplyContent = nextInquiry?.replyContent ?? ''
}

function openAccessModal(view = 'invite') {
  state.activeAdminPanel = ''
  state.accessModalOpen = true
  state.accessModalView = accessModalOptions.value.some((option) => option.key === view) ? view : 'invite'
}

function closeAccessModal() {
  state.accessModalOpen = false
}

function selectAccessModalView(view) {
  state.accessModalView = accessModalOptions.value.some((option) => option.key === view) ? view : 'invite'
}

function supportTabForInquiry(inquiry) {
  if (!inquiry) {
    return 'pending'
  }
  if (inquiry.archived || inquiry.status === 'ANSWERED') {
    return 'done'
  }
  if (inquiry.status === 'IN_PROGRESS') {
    return 'processing'
  }
  return 'pending'
}

function openSupportModal(tab = state.supportTab || 'pending') {
  state.activeAdminPanel = ''
  state.supportModalOpen = true
  setSupportTab(tab)
}

function closeSupportModal() {
  state.supportModalOpen = false
}

function setSupportTab(tab) {
  state.supportTab = state.supportPages[tab] === undefined ? 'pending' : tab
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
  state.adminAccessError = '관리자 페이지 접근을 위해 2차 비밀번호를 다시 입력해 주세요.'
  state.loading = false
  state.loadingLoginLogs = false
  state.loadingOpsControl = false
  state.savingAiControl = false
  return true
}

async function initializeAdminWorkspace() {
  loadAiControlPresets()
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

function exitAdminAccess() {
  if (state.verifyingAdminAccess) {
    return
  }
  emit('exit-admin-access')
}

function handleAdminAccessKeydown(event) {
  if (event.key !== 'Escape') {
    return
  }
  if (!state.adminAccessReady || state.adminAccessVerified) {
    return
  }
  event.preventDefault()
  exitAdminAccess()
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
    state.supportTab = 'done'
    syncSelection(updatedInquiry.id)
  } catch (error) {
    if (!handleAdminAccessRequired(error)) {
      state.errorMessage = error.message
    }
  } finally {
    state.savingReply = false
  }
}

async function handleUpdateSupportStatus(inquiry, status) {
  state.mutatingInquiryId = inquiry.id
  state.errorMessage = ''

  try {
    const updatedInquiry = await updateAdminSupportInquiryStatus(inquiry.id, status)
    state.supportInquiries = state.supportInquiries.map((item) => (
      item.id === updatedInquiry.id ? updatedInquiry : item
    ))
    state.supportTab = supportTabForInquiry(updatedInquiry)
    syncSelection(updatedInquiry.id)
  } catch (error) {
    if (!handleAdminAccessRequired(error)) {
      state.errorMessage = error.message
    }
  } finally {
    state.mutatingInquiryId = null
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
    state.supportTab = archived ? 'done' : supportTabForInquiry(updatedInquiry)
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

function aiControlPresetAddress(preset) {
  if (preset?.provider === 'openai') return preset.openAiBaseUrl || ''
  if (preset?.provider === 'ollama') return preset.ollamaBaseUrl || ''
  return preset?.lmStudioBaseUrl || preset?.workflowUrl || ''
}

function aiControlPresetKeyFor(preset) {
  return [
    preset.targetFeature || 'ledger',
    preset.provider,
    preset.model,
    preset.workflowUrl,
    preset.lmStudioBaseUrl,
    preset.lmStudioChatPath,
    preset.lmStudioModelsPath,
    preset.openAiBaseUrl,
    preset.openAiChatPath,
    preset.openAiModelsPath,
    preset.ollamaBaseUrl,
    preset.ollamaChatPath,
    preset.ollamaModelsPath,
  ].join('|')
}

function providerCredentialConfigured(source = state.aiControlForm) {
  if (source.provider === 'openai') return Boolean(source.openAiApiKeyConfigured)
  if (source.provider === 'ollama') return Boolean(source.ollamaApiKeyConfigured)
  if (source.provider === 'n8n') return Boolean(source.apiKeyConfigured)
  return Boolean(source.lmStudioApiKeyConfigured)
}

function buildAiControlPreset(source = state.aiControlForm) {
  if (!hasAiServerAddress(source)) return null
  const preset = {
    title: state.aiServerDraftName || source.title || buildCurrentAiServerName(),
    targetFeature: state.aiTargetFeature,
    provider: source.provider || 'lmstudio',
    model: source.model || 'auto',
    workflowUrl: source.workflowUrl || '',
    lmStudioBaseUrl: source.lmStudioBaseUrl || '',
    lmStudioChatPath: source.lmStudioChatPath || '/v1/chat/completions',
    lmStudioModelsPath: source.lmStudioModelsPath || '/v1/models',
    openAiBaseUrl: source.openAiBaseUrl || 'https://api.openai.com',
    openAiChatPath: source.openAiChatPath || '/v1/chat/completions',
    openAiModelsPath: source.openAiModelsPath || '/v1/models',
    ollamaBaseUrl: source.ollamaBaseUrl || 'http://localhost:11434',
    ollamaChatPath: source.ollamaChatPath || '/api/chat',
    ollamaModelsPath: source.ollamaModelsPath || '/api/tags',
    temperature: Number(source.temperature ?? 0.2),
    maxTokens: Number(source.maxTokens ?? 4096),
    connectTimeoutSeconds: Number(source.connectTimeoutSeconds ?? 3),
    readTimeoutSeconds: Number(source.readTimeoutSeconds ?? 120),
    enforceProviderUrlAllowlist: Boolean(source.enforceProviderUrlAllowlist),
    allowedProviderHosts: source.allowedProviderHosts || '',
    credentialConfigured: providerCredentialConfigured(source),
    savedAt: new Date().toISOString(),
  }
  preset.key = aiControlPresetKeyFor(preset)
  return preset
}
function readAiControlPresets() {
  try {
    const parsed = JSON.parse(localStorage.getItem(AI_CONTROL_PRESETS_STORAGE_KEY) || '[]')
    return Array.isArray(parsed)
      ? parsed
          .filter((item) => item && aiControlPresetAddress(item))
          .map((item) => ({
            ...item,
            key: String(item.key || "").trim() || aiControlPresetKeyFor(item),
            credentialConfigured: Boolean(item.credentialConfigured),
          }))
          .slice(0, 12)
      : []
  } catch {
    return []
  }
}

function persistAiControlPresets() {
  localStorage.setItem(AI_CONTROL_PRESETS_STORAGE_KEY, JSON.stringify(state.aiControlPresets.slice(0, 12)))
}

function loadAiControlPresets() {
  state.aiControlPresets = readAiControlPresets()
  loadAiCandidateServers()
}

function rememberAiControlPreset(source = state.aiControlForm) {
  const preset = buildAiControlPreset(source)
  if (!preset) {
    return
  }
  state.aiControlPresets = [preset, ...state.aiControlPresets.filter((item) => item.key !== preset.key)].slice(0, 12)
  state.aiControlPresetKey = preset.key
  persistAiControlPresets()
  loadAiCandidateServers()
}

function activeAiControlPresetKey() {
  return buildAiControlPreset(state.aiControlForm)?.key || ''
}

function isAiControlPresetActive(preset) {
  return Boolean(preset?.key && preset.key === activeAiControlPresetKey())
}

function activeAiControlPresetTitle() {
  const preset = state.aiControlPresets.find((item) => isAiControlPresetActive(item))
  return preset?.title || preset?.model || ''
}

function isPresetCredentialConfigured(preset) {
  return Boolean(preset?.credentialConfigured)
}
const candidateAiControlPresets = computed(() => state.aiCandidateServerKeys
  .map((key) => state.aiControlPresets.find((preset) => preset.key === key))
  .filter((preset) => isFeatureCompatibleAiPreset(preset)),
)

const availableAiControlPresets = computed(() => state.aiControlPresets
  .filter((preset) => isFeatureCompatibleAiPreset(preset))
  .filter((preset) => !state.aiCandidateServerKeys.includes(preset.key)),
)

function isFeatureCompatibleAiPreset(preset) {
  return ['lmstudio', 'openai', 'ollama'].includes(String(preset?.provider || '').toLowerCase())
}

function normalizeAiCandidateServerKeys(keys) {
  const availableKeys = new Set(state.aiControlPresets
    .filter((preset) => isFeatureCompatibleAiPreset(preset))
    .map((preset) => preset.key))
  const normalized = Array.isArray(keys) ? keys.map((key) => String(key || '').trim()) : []
  return [...new Set(normalized.filter((key) => availableKeys.has(key)))].slice(0, AI_CONTROL_CANDIDATE_LIMIT)
}

function readAiCandidateServerKeys() {
  try {
    return JSON.parse(localStorage.getItem(AI_CONTROL_CANDIDATES_STORAGE_KEY) || '[]')
  } catch {
    return []
  }
}

function persistAiCandidateServerKeys() {
  localStorage.setItem(AI_CONTROL_CANDIDATES_STORAGE_KEY, JSON.stringify(state.aiCandidateServerKeys))
}

function loadAiCandidateServers() {
  state.aiCandidateServerKeys = normalizeAiCandidateServerKeys(readAiCandidateServerKeys())
  if (!state.aiCandidateServerPickerKey || state.aiCandidateServerKeys.includes(state.aiCandidateServerPickerKey)) {
    state.aiCandidateServerPickerKey = availableAiControlPresets.value[0]?.key || ''
  }
}

function aiServerPresetSignature(source) {
  const provider = String(source?.provider || '').toLowerCase()
  const baseUrl = aiControlPresetAddress(source)
  const chatPath = provider === 'ollama'
    ? source?.ollamaChatPath
    : provider === 'openai'
      ? source?.openAiChatPath
      : source?.lmStudioChatPath
  const modelsPath = provider === 'ollama'
    ? source?.ollamaModelsPath
    : provider === 'openai'
      ? source?.openAiModelsPath
      : source?.lmStudioModelsPath
  return [provider, source?.model || '', baseUrl || '', chatPath || '', modelsPath || ''].join('|')
}

function aiFeatureConfigSignature(feature) {
  const config = aiFeatureConfigFor(feature)
  if (!config) {
    return ''
  }
  return [config.provider || '', config.model || '', config.baseUrl || '', config.chatPath || '', config.modelsPath || ''].join('|')
}

function syncAiFeatureConnections() {
  for (const feature of ['ledger', 'image', 'excel']) {
    const signature = aiFeatureConfigSignature(feature)
    const matched = candidateAiControlPresets.value.find((preset) => aiServerPresetSignature(preset) === signature)
    state.aiFeatureConnections[feature] = matched?.key || ''
  }
}

function serverRoutingCandidateToPreset(candidate) {
  const provider = String(candidate?.provider || 'lmstudio').toLowerCase()
  const baseUrl = candidate?.baseUrl || ''
  const chatPath = candidate?.chatPath || (provider === 'ollama' ? '/api/chat' : '/v1/chat/completions')
  const modelsPath = candidate?.modelsPath || (provider === 'ollama' ? '/api/tags' : '/v1/models')
  return {
    key: String(candidate?.presetKey || '').trim(),
    title: candidate?.title || candidate?.model || 'AI server',
    targetFeature: 'ledger',
    provider,
    model: candidate?.model || 'auto',
    workflowUrl: '',
    lmStudioBaseUrl: provider === 'lmstudio' ? baseUrl : '',
    lmStudioChatPath: provider === 'lmstudio' ? chatPath : '/v1/chat/completions',
    lmStudioModelsPath: provider === 'lmstudio' ? modelsPath : '/v1/models',
    openAiBaseUrl: provider === 'openai' ? baseUrl : '',
    openAiChatPath: provider === 'openai' ? chatPath : '/v1/chat/completions',
    openAiModelsPath: provider === 'openai' ? modelsPath : '/v1/models',
    ollamaBaseUrl: provider === 'ollama' ? baseUrl : '',
    ollamaChatPath: provider === 'ollama' ? chatPath : '/api/chat',
    ollamaModelsPath: provider === 'ollama' ? modelsPath : '/api/tags',
    temperature: Number(candidate?.temperature ?? 0.2),
    maxTokens: Number(candidate?.maxTokens ?? 4096),
    credentialConfigured: Boolean(candidate?.apiKeyConfigured),
    savedAt: new Date().toISOString(),
  }
}

function syncAiRoutingFromServer() {
  const routing = state.opsControl?.ai
  if (!routing?.routingConfigured) {
    syncAiFeatureConnections()
    return
  }

  const savedCandidates = Array.isArray(routing.candidateServers)
    ? routing.candidateServers.map(serverRoutingCandidateToPreset).filter((preset) => preset.key)
    : []
  const savedKeys = new Set(savedCandidates.map((preset) => preset.key))
  state.aiControlPresets = [
    ...savedCandidates,
    ...state.aiControlPresets.filter((preset) => !savedKeys.has(preset.key)),
  ].slice(0, 12)
  state.aiCandidateServerKeys = savedCandidates.map((preset) => preset.key)
  state.aiFeatureConnections = {
    ledger: '',
    image: '',
    excel: '',
    ...(routing.featureConnections || {}),
  }
  persistAiControlPresets()
  persistAiCandidateServerKeys()
  state.aiCandidateServerPickerKey = availableAiControlPresets.value[0]?.key || ''
}
function candidateAiPresetForFeature(feature) {
  const key = state.aiFeatureConnections[feature]
  return candidateAiControlPresets.value.find((preset) => preset.key === key) || null
}

function addAiCandidateServer() {
  const key = state.aiCandidateServerPickerKey
  if (!key) {
    state.opsControlError = '후보로 등록할 서버를 선택하세요.'
    return
  }
  if (state.aiCandidateServerKeys.includes(key)) {
    state.opsControlError = '이미 후보 서버에 등록된 서버입니다.'
    return
  }
  if (state.aiCandidateServerKeys.length >= AI_CONTROL_CANDIDATE_LIMIT) {
    state.opsControlError = `후보 서버는 최대 ${AI_CONTROL_CANDIDATE_LIMIT}개까지 등록할 수 있습니다.`
    return
  }
  state.opsControlError = ''
  state.aiCandidateServerKeys = [...state.aiCandidateServerKeys, key]
  persistAiCandidateServerKeys()
  state.aiCandidateServerPickerKey = availableAiControlPresets.value[0]?.key || ''
}

function removeAiCandidateServer(key) {
  state.aiCandidateServerKeys = state.aiCandidateServerKeys.filter((candidateKey) => candidateKey !== key)
  for (const feature of ['ledger', 'image', 'excel']) {
    if (state.aiFeatureConnections[feature] === key) {
      state.aiFeatureConnections[feature] = ''
    }
  }
  persistAiCandidateServerKeys()
  if (!state.aiCandidateServerPickerKey) {
    state.aiCandidateServerPickerKey = availableAiControlPresets.value[0]?.key || ''
  }
}

function connectAiFeatureToCandidate(feature, key) {
  if (!candidateAiControlPresets.value.some((preset) => preset.key === key)) {
    return
  }
  state.aiFeatureConnections[feature] = key
}

function clearAiFeatureConnection(feature) {
  state.aiFeatureConnections[feature] = ''
}

function buildAiRoutingCandidatePayload(preset) {
  const provider = String(preset.provider || 'lmstudio').toLowerCase()
  const chatPath = provider === 'ollama'
    ? preset.ollamaChatPath
    : provider === 'openai'
      ? preset.openAiChatPath
      : preset.lmStudioChatPath
  const modelsPath = provider === 'ollama'
    ? preset.ollamaModelsPath
    : provider === 'openai'
      ? preset.openAiModelsPath
      : preset.lmStudioModelsPath
  return {
    presetKey: preset.key,
    title: preset.title || preset.model || 'AI server',
    provider,
    model: preset.model || 'auto',
    baseUrl: aiControlPresetAddress(preset),
    chatPath: chatPath || (provider === 'ollama' ? '/api/chat' : '/v1/chat/completions'),
    modelsPath: modelsPath || (provider === 'ollama' ? '/api/tags' : '/v1/models'),
    temperature: Number(preset.temperature ?? state.aiControlForm.temperature ?? 0.2),
    maxTokens: Number(preset.maxTokens ?? state.aiControlForm.maxTokens ?? 4096),
  }
}

async function saveAiFeatureConnections() {
  state.savingAiControl = true
  state.opsControlError = ''
  state.opsControlMessage = ''
  const activeFeature = state.aiTargetFeature
  try {
    state.opsControl = await updateAdminAiRouting({
      candidates: candidateAiControlPresets.value.map(buildAiRoutingCandidatePayload),
      featureConnections: { ...state.aiFeatureConnections },
    })
    state.aiTargetFeature = activeFeature
    syncAiControlForm()
    syncAiRoutingFromServer()
    markOpsControlChecked()
    state.opsControlMessage = '후보 서버와 기능 연결을 저장했습니다.'
  } catch (error) {
    if (!handleAdminAccessRequired(error)) {
      state.opsControlError = error.message || '후보 서버와 기능 연결을 저장하지 못했습니다.'
    }
  } finally {
    state.savingAiControl = false
  }
}
function toggleAiServerStatusPanel() {
  state.aiServerStatusOpen = !state.aiServerStatusOpen
}

async function handleCheckAiServerStatus() {
  state.aiServerStatusOpen = true
  await loadOpsControl()
}

function toggleAiServerEditor() {
  state.aiServerEditorOpen = !state.aiServerEditorOpen
}

function openAiServerEditor() {
  state.aiServerEditorOpen = true
}

function formatAiServerStatusLabel() {
  if (!state.opsControl?.aiServer) {
    return '상태 미확인'
  }
  return state.opsControl.aiServer.reachable ? '정상 연결' : '확인 필요'
}

function formatAiServerModelList(models) {
  if (!Array.isArray(models) || !models.length) {
    return '조회된 모델 없음'
  }
  return models.join(', ')
}
function applyAiControlPreset() {
  const preset = state.aiControlPresets.find((item) => item.key === state.aiControlPresetKey)
  if (!preset) return
  const targetFeature = ['ledger', 'image', 'excel'].includes(preset.targetFeature) ? preset.targetFeature : 'ledger'
  const reuseCurrentSecret = isAiControlPresetActive(preset)
    && state.aiTargetFeature === targetFeature
    && providerCredentialConfigured(state.aiControlForm)
  const credentialConfigured = isPresetCredentialConfigured(preset) || reuseCurrentSecret
  state.aiControlPreserveCurrentSecret = reuseCurrentSecret
  state.aiTargetFeature = targetFeature
  state.aiControlForm.provider = preset.provider || 'lmstudio'
  state.aiControlForm.model = preset.model || 'auto'
  state.aiControlForm.workflowUrl = preset.workflowUrl || ''
  state.aiControlForm.lmStudioBaseUrl = preset.lmStudioBaseUrl || ''
  state.aiControlForm.lmStudioChatPath = preset.lmStudioChatPath || '/v1/chat/completions'
  state.aiControlForm.lmStudioModelsPath = preset.lmStudioModelsPath || '/v1/models'
  state.aiControlForm.openAiBaseUrl = preset.openAiBaseUrl || 'https://api.openai.com'
  state.aiControlForm.openAiChatPath = preset.openAiChatPath || '/v1/chat/completions'
  state.aiControlForm.openAiModelsPath = preset.openAiModelsPath || '/v1/models'
  state.aiControlForm.ollamaBaseUrl = preset.ollamaBaseUrl || 'http://localhost:11434'
  state.aiControlForm.ollamaChatPath = preset.ollamaChatPath || '/api/chat'
  state.aiControlForm.ollamaModelsPath = preset.ollamaModelsPath || '/api/tags'
  state.aiControlForm.apiKey = ''
  state.aiControlForm.lmStudioApiKey = ''
  state.aiControlForm.openAiApiKey = ''
  state.aiControlForm.ollamaApiKey = ''
  state.aiControlForm.apiKeyConfigured = preset.provider === 'n8n' && credentialConfigured
  state.aiControlForm.lmStudioApiKeyConfigured = preset.provider === 'lmstudio' && credentialConfigured
  state.aiControlForm.openAiApiKeyConfigured = preset.provider === 'openai' && credentialConfigured
  state.aiControlForm.ollamaApiKeyConfigured = preset.provider === 'ollama' && credentialConfigured
  state.aiControlForm.clearApiKey = false
  state.aiControlForm.clearLmStudioApiKey = false
  state.aiControlForm.clearOpenAiApiKey = false
  state.aiControlForm.clearOllamaApiKey = false
  state.aiServerEditorOpen = true
}
function formatAiControlPreset(preset) {
  const saved = preset.savedAt ? formatDateTime(preset.savedAt) : '저장 시각 없음'
  return `${preset.title || preset.model || 'AI 서버'} · ${preset.model || 'auto'} · ${aiControlPresetAddress(preset) || '-'} · ${saved}`
}
function aiFeatureConfigFor(feature, control = state.opsControl) {
  const ai = control?.ai
  if (!ai) {
    return null
  }
  if (feature === 'image') return ai.imageAnalysis
  if (feature === 'excel') return ai.excelImport
  return ai.ledgerAnalysis
}

function activeAiFeatureConfig(control = state.opsControl) {
  return aiFeatureConfigFor(state.aiTargetFeature, control)
}

function aiFeatureServerStatusFor(feature, control = state.opsControl) {
  if (feature === 'image') return control?.imageAiServer
  if (feature === 'excel') return control?.excelAiServer
  return control?.aiServer
}

function aiFeatureProviderLabel(feature) {
  const provider = aiFeatureConfigFor(feature)?.provider
  if (provider === 'openai') return 'OpenAI API'
  if (provider === 'ollama') return 'Ollama'
  if (provider === 'lmstudio') return 'LM Studio'
  return '-'
}

function aiFeatureServerStateLabel(feature) {
  const status = aiFeatureServerStatusFor(feature)
  if (!status) return '점검 전'
  return status.reachable ? '정상 연결' : '확인 필요'
}

function reachableAiFeatureCount() {
  return ['ledger', 'image', 'excel']
    .filter((feature) => aiFeatureServerStatusFor(feature)?.reachable)
    .length
}

function selectAiTargetFeature(feature) {
  state.aiTargetFeature = ['ledger', 'image', 'excel'].includes(feature) ? feature : 'ledger'
  state.aiControlPresetKey = ''
  state.aiControlPreserveCurrentSecret = false
  syncAiControlForm()
}

function aiFeatureLabel(feature = state.aiTargetFeature) {
  if (feature === 'image') return '이미지 분석 / OCR'
  if (feature === 'excel') return 'AI 엑셀 추출'
  return '가계부 AI 분석'
}

function syncAiControlForm(control = state.opsControl) {
  const ai = control?.ai
  if (!ai) {
    return
  }
  const feature = activeAiFeatureConfig(control)
  const provider = feature?.provider || ai.provider || 'lmstudio'
  const baseUrl = feature?.baseUrl || ''
  const chatPath = feature?.chatPath || (provider === 'ollama' ? '/api/chat' : '/v1/chat/completions')
  const modelsPath = feature?.modelsPath || (provider === 'ollama' ? '/api/tags' : '/v1/models')
  const keyConfigured = Boolean(feature?.apiKeyConfigured)
  state.aiControlForm = {
    enabled: Boolean(ai.enabled),
    provider,
    model: feature?.model || ai.model || '',
    workflowUrl: ai.workflowUrl || '',
    apiKeyHeader: ai.apiKeyHeader || 'X-TravelLedger-AI-Key',
    apiKey: '',
    clearApiKey: false,
    lmStudioBaseUrl: provider === 'lmstudio' ? baseUrl : '',
    lmStudioChatPath: provider === 'lmstudio' ? chatPath : '/v1/chat/completions',
    lmStudioModelsPath: provider === 'lmstudio' ? modelsPath : '/v1/models',
    lmStudioApiKey: '',
    clearLmStudioApiKey: false,
    openAiBaseUrl: provider === 'openai' ? baseUrl : 'https://api.openai.com',
    openAiChatPath: provider === 'openai' ? chatPath : '/v1/chat/completions',
    openAiModelsPath: provider === 'openai' ? modelsPath : '/v1/models',
    openAiApiKey: '',
    clearOpenAiApiKey: false,
    ollamaBaseUrl: provider === 'ollama' ? baseUrl : 'http://localhost:11434',
    ollamaChatPath: provider === 'ollama' ? chatPath : '/api/chat',
    ollamaModelsPath: provider === 'ollama' ? modelsPath : '/api/tags',
    ollamaApiKey: '',
    clearOllamaApiKey: false,
    apiKeyConfigured: Boolean(ai.apiKeyConfigured),
    lmStudioApiKeyConfigured: provider === 'lmstudio' && keyConfigured,
    openAiApiKeyConfigured: provider === 'openai' && keyConfigured,
    ollamaApiKeyConfigured: provider === 'ollama' && keyConfigured,
    temperature: Number(feature?.temperature ?? ai.temperature ?? 0.2),
    maxTokens: Number(feature?.maxTokens ?? ai.maxTokens ?? 4096),
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
    syncAiRoutingFromServer()
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
    const pendingPreset = buildAiControlPreset()
    const payload = {
      enabled: Boolean(state.aiControlForm.enabled),
      targetFeature: state.aiTargetFeature,
      provider: state.aiControlForm.provider,
      model: state.aiControlForm.model,
      workflowUrl: state.aiControlForm.workflowUrl,
      apiKeyHeader: state.aiControlForm.apiKeyHeader,
      lmStudioBaseUrl: state.aiControlForm.lmStudioBaseUrl,
      lmStudioChatPath: state.aiControlForm.lmStudioChatPath,
      lmStudioModelsPath: state.aiControlForm.lmStudioModelsPath,
      openAiBaseUrl: state.aiControlForm.openAiBaseUrl,
      openAiChatPath: state.aiControlForm.openAiChatPath,
      openAiModelsPath: state.aiControlForm.openAiModelsPath,
      ollamaBaseUrl: state.aiControlForm.ollamaBaseUrl,
      ollamaChatPath: state.aiControlForm.ollamaChatPath,
      ollamaModelsPath: state.aiControlForm.ollamaModelsPath,
      temperature: Number(state.aiControlForm.temperature),
      maxTokens: Number(state.aiControlForm.maxTokens),
      connectTimeoutSeconds: Number(state.aiControlForm.connectTimeoutSeconds),
      readTimeoutSeconds: Number(state.aiControlForm.readTimeoutSeconds),
      enforceProviderUrlAllowlist: Boolean(state.aiControlForm.enforceProviderUrlAllowlist),
      allowedProviderHosts: state.aiControlForm.allowedProviderHosts,
      presetKey: pendingPreset?.key || undefined,
      reuseExistingSecrets: Boolean(state.aiControlPreserveCurrentSecret),
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
    if (state.aiControlForm.clearOllamaApiKey) {
      payload.clearOllamaApiKey = true
    } else if (state.aiControlForm.ollamaApiKey?.trim()) {
      payload.ollamaApiKey = state.aiControlForm.ollamaApiKey.trim()
    }
    if (state.aiControlForm.clearOpenAiApiKey) {
      payload.clearOpenAiApiKey = true
    } else if (state.aiControlForm.openAiApiKey?.trim()) {
      payload.openAiApiKey = state.aiControlForm.openAiApiKey.trim()
    }
    state.opsControl = await updateAdminAiControl(payload)
    syncAiControlForm()
    syncAiRoutingFromServer()
    syncDataControlForm()
    markOpsControlChecked()
    state.aiControlPreserveCurrentSecret = false
    rememberAiControlPreset()
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

onMounted(() => {
  initializeAdminWorkspace()
  window.addEventListener('keydown', handleAdminAccessKeydown)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', handleAdminAccessKeydown)
})
</script>

<template>
  <section class="workspace-stack admin-workspace">
    <div v-if="state.adminAccessReady && !state.adminAccessVerified" class="travel-modal">
      <div class="travel-modal__dialog profile-security-modal admin-access-modal">
        <div class="travel-modal__header">
          <div>
            <h2>관리자 추가 인증</h2>
            <p>관리자 페이지를 열려면 로그인에 사용하는 8자리 2차 비밀번호를 다시 입력해야 합니다.</p>
          </div>
        </div>
        <form class="travel-modal__body" @submit.prevent="handleVerifyAdminAccess">
          <div v-if="state.adminAccessError" class="feedback feedback--error">{{ state.adminAccessError }}</div>
          <label class="field">
            <span class="field__label">2차 비밀번호 재확인</span>
            <input
              v-model="state.adminAccessCode"
              type="password"
              inputmode="numeric"
              maxlength="8"
              autocomplete="off"
              placeholder="숫자 8자리"
              :disabled="state.verifyingAdminAccess"
            />
          </label>
        </form>
        <div class="travel-modal__footer">
          <button
            class="button button--ghost"
            type="button"
            :disabled="state.verifyingAdminAccess"
            @click="exitAdminAccess"
          >
            메인으로 나가기 (Esc)
          </button>
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

      <section class="admin-command-center">
        <div class="admin-command-center__header">
          <div>
            <span class="admin-command-center__eyebrow">Admin Control Hub</span>
            <h2>관리 기능 요약 대시보드</h2>
          </div>
          <div class="admin-toolbar">
            <button class="button button--ghost" type="button" :disabled="state.loading" @click="loadDashboard">
              {{ state.loading ? '불러오는 중...' : '요약 새로고침' }}
            </button>
          </div>
        </div>
        <div class="admin-command-grid">
          <article
            v-for="panel in adminPanelCards"
            :key="panel.key"
            class="admin-command-card"
            :class="{ 'is-active': state.activeAdminPanel === panel.key }"
          >
            <span>{{ panel.eyebrow }}</span>
            <strong>{{ panel.title }}</strong>
            <div class="admin-command-card__meta">
              <b>{{ panel.metric }}</b>
            </div>
            <button class="button button--primary" type="button" @click="openAdminPanel(panel.key)">
              {{ state.activeAdminPanel === panel.key ? '닫기' : '열기' }}
            </button>
          </article>
        </div>
      </section>

      <div v-if="state.opsControlModalOpen" class="travel-modal admin-ops-modal" @keydown.esc="closeOpsControlModal">
        <div class="travel-modal__dialog admin-ops-modal__dialog">
          <div class="travel-modal__header admin-ops-modal__header">
            <div>
              <span class="admin-command-center__eyebrow">AI / Server</span>
              <h2>AI 및 서버 제어판</h2>
            </div>
            <button class="button button--ghost" type="button" @click="closeOpsControlModal">닫기</button>
          </div>

          <div class="travel-modal__body admin-ops-modal__body">
            <div v-if="state.opsControlError" class="feedback feedback--error" role="alert">{{ state.opsControlError }}</div>
            <div v-if="state.opsControlMessage" class="feedback feedback--success" role="status" aria-live="polite">{{ state.opsControlMessage }}</div>

            <div class="admin-ops-modal__nav" aria-label="AI 및 서버 제어판 기능 선택">
              <button
                type="button"
                :class="['admin-ops-action-card', { 'is-active': state.opsControlModalView === 'status' }]"
                @click="selectOpsControlModalView('status')"
              >
                <span>1</span>
                <strong>서버 상태 확인</strong>
              </button>
              <button
                type="button"
                :class="['admin-ops-action-card', { 'is-active': state.opsControlModalView === 'list' }]"
                @click="selectOpsControlModalView('list')"
              >
                <span>2</span>
                <strong>서버 리스트 조회 및 서버 변경</strong>
              </button>
              <button
                type="button"
                :class="['admin-ops-action-card', { 'is-active': state.opsControlModalView === 'add' }]"
                @click="startAiServerAdd"
              >
                <span>3</span>
                <strong>서버 추가</strong>
              </button>
            </div>

            <section v-if="state.opsControlModalView === 'menu'" class="admin-ops-modal__empty">
              <strong>작업할 기능을 선택하세요.</strong>
            </section>

            <section v-else-if="state.opsControlModalView === 'status'" class="admin-ops-panel">
              <div class="panel__header">
                <div>
                  <h3>서버 상태 확인</h3>
                </div>
                <button class="button button--primary" type="button" :disabled="state.loadingOpsControl" @click="handleCheckAiServerStatus">
                  {{ state.loadingOpsControl ? '테스트 중...' : '테스트 연결' }}
                </button>
              </div>
              <p v-if="state.opsControlCheckedAt" class="field__hint">최근 점검: {{ formatOpsControlCheckedAt(state.opsControlCheckedAt) }}</p>
              <p v-if="state.opsControl?.persistenceMessage" class="field__hint">설정 저장소: {{ state.opsControl.persistenceMessage }}</p>
              <div class="summary-grid admin-ops-status-grid">
                <article class="summary-card admin-ops-status-card">
                  <span class="admin-ops-status-card__label">AI 기능</span>
                  <strong class="admin-ops-status-card__value">{{ state.opsControl?.ai?.enabled ? '켜짐' : '꺼짐' }}</strong>
                  <small class="admin-ops-status-card__hint">가계부, 이미지/OCR, 엑셀 추출 기능별 서버를 별도 점검합니다.</small>
                </article>
                <article class="summary-card admin-ops-status-card">
                  <span class="admin-ops-status-card__label">AI 서버 연결</span>
                  <strong class="admin-ops-status-card__value">{{ reachableAiFeatureCount() }} / 3 정상</strong>
                  <small class="admin-ops-status-card__hint">가계부: {{ aiFeatureServerStateLabel('ledger') }}<br>이미지/OCR: {{ aiFeatureServerStateLabel('image') }}<br>엑셀: {{ aiFeatureServerStateLabel('excel') }}</small>
                </article>
                <article class="summary-card admin-ops-status-card">
                  <span class="admin-ops-status-card__label">DB 서버</span>
                  <strong class="admin-ops-status-card__value">{{ state.opsControl?.dataServer?.databaseReachable ? '정상' : '확인 필요' }}</strong>
                  <small class="admin-ops-status-card__hint">{{ state.opsControl?.dataServer?.databaseHost || '-' }}</small>
                </article>
                <article class="summary-card admin-ops-status-card">
                  <span class="admin-ops-status-card__label">MinIO 여유</span>
                  <strong class="admin-ops-status-card__value">{{ formatFileSize(state.opsControl?.dataServer?.minioStorage?.remainingBytes || 0) }}</strong>
                  <small class="admin-ops-status-card__hint">{{ formatPercent(state.opsControl?.dataServer?.minioStorage?.usedPercent || 0) }} 사용</small>
                </article>
              </div>
              <div class="admin-ai-feature-status-grid admin-ai-feature-status-grid--probe">
                <article v-for="feature in ['ledger', 'image', 'excel']" :key="feature" class="admin-ai-feature-status-card">
                  <span>{{ aiFeatureLabel(feature) }}</span>
                  <strong>{{ aiFeatureServerStateLabel(feature) }}</strong>
                  <small>{{ aiFeatureProviderLabel(feature) }} · {{ aiFeatureConfigFor(feature)?.model || '모델 미설정' }}</small>
                  <small>{{ aiFeatureServerStatusFor(feature)?.baseUrl || aiFeatureConfigFor(feature)?.baseUrl || '서버 주소 미설정' }}</small>
                  <small>{{ aiFeatureServerStatusFor(feature)?.message || '테스트 연결 전' }}</small>
                </article>
              </div>

              <div class="sheet-table-wrap">
                <table class="sheet-table">
                  <thead>
                    <tr>
                      <th>대상</th>
                      <th>상태</th>
                      <th>연결 정보</th>
                      <th>측정값</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="feature in ['ledger', 'image', 'excel']" :key="feature">
                      <td>{{ aiFeatureLabel(feature) }} 서버</td>
                      <td>{{ aiFeatureServerStateLabel(feature) }}</td>
                      <td>{{ aiFeatureServerStatusFor(feature)?.baseUrl || aiFeatureConfigFor(feature)?.baseUrl || '-' }} {{ aiFeatureServerStatusFor(feature)?.modelsPath || aiFeatureConfigFor(feature)?.modelsPath || '' }}</td>
                      <td>{{ aiFeatureServerStatusFor(feature)?.latencyMillis || 0 }} ms / {{ formatAiServerModelList(aiFeatureServerStatusFor(feature)?.models) }}</td>
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
                      <td>{{ formatFileSize(state.opsControl?.dataServer?.minioStorage?.totalSizeBytes || 0) }} 사용</td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </section>

            <section v-else-if="state.opsControlModalView === 'list'" class="admin-ops-panel admin-ai-routing">
              <div class="panel__header">
                <div>
                  <h3>후보 서버 연결</h3>
                  <p>후보 서버 등록은 바로가기만 관리합니다. 아래 기능 연결을 저장해야 실제 분석 서버가 바뀝니다.</p>
                </div>
                <button class="button button--ghost" type="button" :disabled="state.loadingOpsControl" @click="loadOpsControl">
                  {{ state.loadingOpsControl ? '조회 중...' : '현재 설정 조회' }}
                </button>
              </div>

              <section class="admin-ai-routing__candidate-add" aria-labelledby="candidate-server-title">
                <div>
                  <strong id="candidate-server-title">후보 서버</strong>
                  <small>자주 전환할 서버를 최대 3개까지 등록할 수 있습니다.</small>
                </div>
                <div class="admin-ai-routing__candidate-add-controls">
                  <select v-model="state.aiCandidateServerPickerKey" aria-label="후보로 등록할 서버">
                    <option value="">등록할 서버 선택</option>
                    <option v-for="preset in availableAiControlPresets" :key="preset.key" :value="preset.key">
                      {{ preset.title || preset.model || 'AI 서버' }} · {{ preset.provider }}
                    </option>
                  </select>
                  <button class="button button--secondary" type="button" :disabled="!state.aiCandidateServerPickerKey || state.aiCandidateServerKeys.length >= 3" @click="addAiCandidateServer">후보 추가</button>
                  <button class="button button--ghost" type="button" @click="startAiServerAdd">새 서버 등록</button>
                </div>
              </section>

              <section class="admin-ai-routing__candidates" aria-label="후보 서버 목록">
                <article v-if="!candidateAiControlPresets.length" class="admin-ai-routing__empty">
                  <strong>등록된 후보 서버가 없습니다.</strong>
                  <span>등록 서버 중 자주 사용할 서버를 후보로 추가하세요.</span>
                </article>
                <article v-for="preset in candidateAiControlPresets" :key="preset.key" class="admin-ai-routing__candidate-card">
                  <div>
                    <span class="admin-ai-routing__candidate-index">후보 {{ state.aiCandidateServerKeys.indexOf(preset.key) + 1 }}</span>
                    <strong>{{ preset.title || preset.model || 'AI 서버' }}</strong>
                    <small>{{ currentAiProviderLabel(preset.provider) }} · {{ preset.model || 'auto' }}</small>
                    <small>{{ aiControlPresetAddress(preset) || '서버 주소 미설정' }}</small>
                    <small>{{ isPresetCredentialConfigured(preset) ? 'API 키 암호화 저장됨' : 'API 키 미설정' }}</small>
                  </div>
                  <div class="admin-ai-routing__candidate-actions">
                    <button class="button button--ghost" type="button" @click="applyAiControlPresetByKey(preset.key)">편집</button>
                    <button class="button button--danger" type="button" @click="removeAiCandidateServer(preset.key)">후보 해제</button>
                  </div>
                </article>
              </section>

              <section class="admin-ai-routing__board" aria-labelledby="feature-routing-title">
                <div class="admin-ai-routing__board-header">
                  <div>
                    <h4 id="feature-routing-title">AI 기능별 서버 연결</h4>
                    <p>각 기능에서 사용할 후보 서버 하나를 선택하세요.</p>
                  </div>
                  <span>{{ candidateAiControlPresets.length }}/3 후보</span>
                </div>
                <article v-for="feature in ['ledger', 'image', 'excel']" :key="feature" class="admin-ai-routing__feature-row" :class="{ 'is-connected': candidateAiPresetForFeature(feature) }">
                  <div class="admin-ai-routing__feature-node">
                    <span>{{ feature === 'image' ? 'IMAGE / OCR' : feature === 'excel' ? 'EXCEL IMPORT' : 'LEDGER AI' }}</span>
                    <strong>{{ aiFeatureLabel(feature) }}</strong>
                    <small>{{ aiFeatureConfigFor(feature)?.model || '현재 연결 없음' }}</small>
                  </div>
                  <div class="admin-ai-routing__line" aria-hidden="true">
                    <i></i>
                    <b>{{ candidateAiPresetForFeature(feature) ? '연결됨' : '선택 필요' }}</b>
                  </div>
                  <div class="admin-ai-routing__server-options">
                    <button v-for="preset in candidateAiControlPresets" :key="preset.key" type="button" class="admin-ai-routing__server-node" :class="{ 'is-selected': state.aiFeatureConnections[feature] === preset.key }" @click="connectAiFeatureToCandidate(feature, preset.key)">
                      <strong>{{ preset.title || preset.model || 'AI 서버' }}</strong>
                      <span>{{ currentAiProviderLabel(preset.provider) }} · {{ preset.model || 'auto' }}</span>
                    </button>
                    <button v-if="state.aiFeatureConnections[feature]" type="button" class="button button--ghost admin-ai-routing__clear" @click="clearAiFeatureConnection(feature)">연결 해제</button>
                    <span v-else-if="!candidateAiControlPresets.length" class="admin-ai-routing__no-candidate">후보 서버를 먼저 추가하세요.</span>
                  </div>
                </article>
              </section>

              <details class="admin-ai-routing__registry">
                <summary>전체 등록 서버 관리 ({{ state.aiControlPresets.length }}개)</summary>
                <div class="admin-ops-server-list">
                  <article v-if="!state.aiControlPresets.length" class="admin-ops-server-row admin-ops-server-row--empty">
                    <strong>등록된 서버가 없습니다.</strong>
                  </article>
                  <article v-for="preset in state.aiControlPresets" :key="preset.key" class="admin-ops-server-row" :class="{ 'is-active': state.aiCandidateServerKeys.includes(preset.key) }">
                    <div>
                      <strong>{{ preset.title || preset.model || 'AI 서버' }}</strong>
                      <span>{{ currentAiProviderLabel(preset.provider) }} · {{ preset.model || 'auto' }}</span>
                      <small>{{ aiControlPresetAddress(preset) || '-' }}</small>
                    </div>
                    <div class="admin-ops-server-row__actions">
                      <button class="button button--ghost" type="button" @click="applyAiControlPresetByKey(preset.key)">편집</button>
                      <button class="button button--danger" type="button" @click="deleteAiControlPreset(preset.key)">삭제</button>
                    </div>
                  </article>
                </div>
              </details>

              <div class="panel__actions admin-ai-routing__save-actions">
                <span>후보를 추가하거나 변경해도 저장 전에는 실제 AI 서버 설정이 바뀌지 않습니다.</span>
                <button class="button button--primary" type="button" :disabled="state.savingAiControl" @click="saveAiFeatureConnections">
                  {{ state.savingAiControl ? '저장 중...' : '기능별 연결 저장' }}
                </button>
              </div>
            </section>
            <section v-else-if="state.opsControlModalView === 'add'" class="admin-ops-panel">
              <div class="panel__header">
                <div>
                  <h3>서버 추가</h3>
                </div>
                <div class="admin-ops-step-indicator" aria-label="서버 추가 단계">
                  <span :class="{ 'is-active': state.aiServerWizardStep === 1 }">1 기본</span>
                  <span :class="{ 'is-active': state.aiServerWizardStep === 2 }">2 연결</span>
                  <span :class="{ 'is-active': state.aiServerWizardStep === 3 }">3 응답/보안</span>
                </div>
              </div>

              <form class="admin-ops-wizard" @submit.prevent="handleSaveAiControl">
                <div class="admin-ai-feature-target" role="group" aria-label="AI 기능별 서버 선택">
                  <span>적용 기능</span>
                  <button type="button" :class="['button', { 'button--primary': state.aiTargetFeature === 'ledger' }]" @click="selectAiTargetFeature('ledger')">가계부 AI 분석</button>
                  <button type="button" :class="['button', { 'button--primary': state.aiTargetFeature === 'image' }]" @click="selectAiTargetFeature('image')">이미지 분석 / OCR</button>
                  <button type="button" :class="['button', { 'button--primary': state.aiTargetFeature === 'excel' }]" @click="selectAiTargetFeature('excel')">AI 엑셀 추출</button>
                  <small>{{ aiFeatureLabel() }} 전용 서버 설정을 저장합니다.</small>
                </div>
                <div v-if="state.aiServerWizardStep === 1" class="admin-ai-field-grid">
                  <label class="field admin-ai-field-grid__wide">
                    <span class="field__label">서버 이름</span>
                    <input v-model="state.aiServerDraftName" placeholder="예: 집 LM Studio Gemma 서버" />
                  </label>
                  <label class="field">
                    <span class="field__label">제공자</span>
                    <select v-model="state.aiControlForm.provider" @change="handleAiProviderChange">
                      <option value="lmstudio">LM Studio</option>
                      <option value="openai">OpenAI API</option>
                  <option value="ollama">Ollama</option>
                    </select>
                  </label>
                  <label class="field">
                    <span class="field__label">서버 모델 명</span>
                    <input v-model="state.aiControlForm.model" placeholder="google/gemma-4-e2b 또는 auto" />
                  </label>
                  <label v-if="state.aiControlForm.provider === 'lmstudio'" class="field admin-ai-field-grid__wide">
                    <span class="field__label">서버 주소</span>
                    <input v-model="state.aiControlForm.lmStudioBaseUrl" placeholder="http://100.x.x.x:1234" />
                  </label>
                  <label v-else-if="state.aiControlForm.provider === 'openai'" class="field admin-ai-field-grid__wide">
                    <span class="field__label">OpenAI API 주소</span>
                    <input v-model="state.aiControlForm.openAiBaseUrl" placeholder="https://api.openai.com" />
                  </label>
                                    <label v-if="state.aiControlForm.provider === 'ollama'" class="field admin-ai-field-grid__wide">
                    <span class="field__label">Ollama 주소</span>
                    <input v-model="state.aiControlForm.ollamaBaseUrl" placeholder="http://127.0.0.1:11434" />
                  </label>
                  <label v-else-if="state.aiControlForm.provider === 'n8n'" class="field admin-ai-field-grid__wide">
                    <span class="field__label">서버 주소 / n8n Webhook URL</span>
                    <input v-model="state.aiControlForm.workflowUrl" placeholder="https://n8n.example.com/webhook/..." />
                  </label>
                  <label class="field field--inline admin-ai-field-grid__wide admin-ai-enable-row">
                    <input v-model="state.aiControlForm.enabled" type="checkbox" />
                    <span class="field__label">저장 후 AI 분석 사용</span>
                  </label>
                </div>

                <div v-else-if="state.aiServerWizardStep === 2" class="admin-ai-field-grid">
                  <template v-if="state.aiControlForm.provider === 'lmstudio'">
                    <label class="field">
                      <span class="field__label">Chat path</span>
                      <input v-model="state.aiControlForm.lmStudioChatPath" placeholder="/v1/chat/completions" />
                    </label>
                    <label class="field">
                      <span class="field__label">Models path</span>
                      <input v-model="state.aiControlForm.lmStudioModelsPath" placeholder="/v1/models" />
                    </label>
                    <label class="field admin-ai-field-grid__wide">
                      <span class="field__label">LM Studio API key</span>
                      <input v-model="state.aiControlForm.lmStudioApiKey" type="password" autocomplete="new-password" :disabled="state.aiControlForm.clearLmStudioApiKey" :placeholder="state.aiControlForm.lmStudioApiKeyConfigured ? '설정됨 - 변경할 때만 입력' : '미설정'" />
                      <small v-if="state.aiControlForm.lmStudioApiKeyConfigured" class="admin-ai-secret-status">서버에 암호화 저장됨. 다시 입력하지 않아도 저장 시 재사용됩니다.</small>
                    </label>
                    <label class="field field--inline admin-ai-field-grid__wide">
                      <input v-model="state.aiControlForm.clearLmStudioApiKey" type="checkbox" />
                      <span class="field__label">LM Studio API key 삭제</span>
                    </label>
                  </template>
                  <template v-else-if="state.aiControlForm.provider === 'openai'">
                    <label class="field">
                      <span class="field__label">Chat Completions path</span>
                      <input v-model="state.aiControlForm.openAiChatPath" placeholder="/v1/chat/completions" />
                    </label>
                    <label class="field">
                      <span class="field__label">Models path</span>
                      <input v-model="state.aiControlForm.openAiModelsPath" placeholder="/v1/models" />
                    </label>
                    <label class="field admin-ai-field-grid__wide">
                      <span class="field__label">OpenAI API key</span>
                      <input v-model="state.aiControlForm.openAiApiKey" type="password" autocomplete="new-password" :disabled="state.aiControlForm.clearOpenAiApiKey" :placeholder="state.aiControlForm.openAiApiKeyConfigured ? '설정됨 - 변경할 때만 입력' : '필수 입력'" />
                      <small v-if="state.aiControlForm.openAiApiKeyConfigured" class="admin-ai-secret-status">서버에 암호화 저장됨. 다시 입력하지 않아도 저장 시 재사용됩니다.</small>
                    </label>
                    <label class="field field--inline admin-ai-field-grid__wide">
                      <input v-model="state.aiControlForm.clearOpenAiApiKey" type="checkbox" />
                      <span class="field__label">OpenAI API key 제거</span>
                    </label>
                  </template>
                                    <template v-else-if="state.aiControlForm.provider === 'ollama'">
                    <label class="field">
                      <span class="field__label">Ollama Chat path</span>
                      <input v-model="state.aiControlForm.ollamaChatPath" placeholder="/api/chat" />
                    </label>
                    <label class="field">
                      <span class="field__label">Ollama Models path</span>
                      <input v-model="state.aiControlForm.ollamaModelsPath" placeholder="/api/tags" />
                    </label>
                    <label class="field admin-ai-field-grid__wide">
                      <span class="field__label">Ollama API key (프록시 사용 시 선택)</span>
                      <input v-model="state.aiControlForm.ollamaApiKey" type="password" autocomplete="new-password" :disabled="state.aiControlForm.clearOllamaApiKey" :placeholder="state.aiControlForm.ollamaApiKeyConfigured ? '설정됨 - 변경할 때만 입력' : '일반 Ollama는 비워도 됩니다'" />
                      <small v-if="state.aiControlForm.ollamaApiKeyConfigured" class="admin-ai-secret-status">서버에 암호화 저장됨.</small>
                    </label>
                    <label class="field field--inline admin-ai-field-grid__wide">
                      <input v-model="state.aiControlForm.clearOllamaApiKey" type="checkbox" />
                      <span class="field__label">Ollama API key 삭제</span>
                    </label>
                  </template>                  <template v-else>
                    <label class="field admin-ai-field-grid__wide">
                      <span class="field__label">n8n Webhook URL</span>
                      <input v-model="state.aiControlForm.workflowUrl" placeholder="https://n8n.example.com/webhook/..." />
                    </label>
                    <label class="field">
                      <span class="field__label">n8n API key header</span>
                      <input v-model="state.aiControlForm.apiKeyHeader" placeholder="X-TravelLedger-AI-Key" />
                    </label>
                    <label class="field">
                      <span class="field__label">n8n API key</span>
                      <input v-model="state.aiControlForm.apiKey" type="password" autocomplete="new-password" :disabled="state.aiControlForm.clearApiKey" :placeholder="state.aiControlForm.apiKeyConfigured ? '설정됨 - 변경할 때만 입력' : '미설정'" />
                      <small v-if="state.aiControlForm.apiKeyConfigured" class="admin-ai-secret-status">서버에 암호화 저장됨. 다시 입력하지 않아도 저장 시 재사용됩니다.</small>
                    </label>
                    <label class="field field--inline admin-ai-field-grid__wide">
                      <input v-model="state.aiControlForm.clearApiKey" type="checkbox" />
                      <span class="field__label">n8n API key 삭제</span>
                    </label>
                  </template>
                </div>

                <div v-else class="admin-ai-field-grid admin-ai-field-grid--compact">
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
                  <label class="field field--inline">
                    <input v-model="state.aiControlForm.enforceProviderUrlAllowlist" type="checkbox" />
                    <span class="field__label">Provider 허용 목록 강제</span>
                  </label>
                  <label class="field admin-ai-field-grid__wide">
                    <span class="field__label">허용 호스트</span>
                    <input v-model="state.aiControlForm.allowedProviderHosts" placeholder="100.x.x.x,127.0.0.1,localhost" />
                  </label>
                </div>

                <div class="panel__actions admin-ops-wizard__actions">
                  <button class="button button--ghost" type="button" :disabled="state.aiServerWizardStep === 1" @click="goPrevAiServerStep">이전</button>
                  <button v-if="state.aiServerWizardStep < 3" class="button button--primary" type="button" @click="goNextAiServerStep">다음</button>
                  <button v-else class="button button--primary" type="submit" :disabled="state.savingAiControl">
                    {{ state.savingAiControl ? '저장 중...' : '서버 저장' }}
                  </button>
                </div>
              </form>
            </section>
          </div>
        </div>
      </div>

      <div v-if="state.supportModalOpen" class="travel-modal admin-support-modal" @keydown.esc="closeSupportModal">
        <div class="travel-modal__dialog admin-support-modal__dialog">
          <div class="travel-modal__header admin-support-modal__header">
            <div>
              <span class="admin-command-center__eyebrow">Support</span>
              <h2>문의 관리</h2>
            </div>
            <button class="button button--ghost" type="button" @click="closeSupportModal">닫기</button>
          </div>

          <div class="travel-modal__body admin-support-modal__body">
            <div v-if="state.errorMessage" class="feedback feedback--error">{{ state.errorMessage }}</div>
            <div class="admin-support-tabs" role="tablist" aria-label="문의 상태 필터">
              <button
                v-for="tab in supportTabOptions"
                :key="tab.key"
                type="button"
                :class="['admin-support-tab', { 'is-active': state.supportTab === tab.key }]"
                @click="setSupportTab(tab.key)"
              >
                <strong>{{ tab.label }}</strong>
                <span>{{ tab.count }}건</span>
              </button>
            </div>

            <div class="admin-support-workspace">
              <section class="admin-support-list-panel">
                <div class="panel__header admin-support-list-panel__header">
                  <div>
                    <h3>{{ supportTabOptions.find((tab) => tab.key === state.supportTab)?.label || '문의' }}</h3>
                  </div>
                  <button class="button button--ghost" type="button" :disabled="state.loading" @click="loadDashboard">
                    {{ state.loading ? '새로고침 중...' : '새로고침' }}
                  </button>
                </div>
                <div class="admin-support-list">
                  <button
                    v-for="inquiry in pagedSupportInquiries"
                    :key="inquiry.id"
                    type="button"
                    :class="['admin-support-row', { 'is-selected': inquiry.id === state.selectedSupportInquiryId }]"
                    @click="selectSupportInquiry(inquiry)"
                  >
                    <span class="admin-support-row__meta">
                      <b>{{ inquiry.senderDisplayName }} ({{ inquiry.senderLoginId }})</b>
                      <small>{{ formatDateTime(inquiry.createdAt) }}</small>
                    </span>
                    <strong>{{ inquiry.title }}</strong>
                    <span class="admin-support-row__footer">
                      <span :class="['entry-type-pill', inquiry.status === 'ANSWERED' ? 'entry-type-pill--income' : inquiry.status === 'IN_PROGRESS' ? 'entry-type-pill--neutral' : 'entry-type-pill--expense']">
                        {{ inquiryStatusLabel[inquiry.status] ?? inquiry.status }}
                      </span>
                      <small>{{ inquiry.attachmentFileName ? '첨부 있음' : '첨부 없음' }}</small>
                    </span>
                  </button>
                  <p v-if="!pagedSupportInquiries.length" class="panel__empty">현재 탭에 표시할 문의가 없습니다.</p>
                </div>
                <div class="panel__actions admin-support-pagination">
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

              <section class="admin-support-detail-panel">
                <template v-if="selectedSupportInquiry">
                  <div class="support-inquiry-card admin-support-detail-card">
                    <div class="support-inquiry-meta">
                      <strong>{{ selectedSupportInquiry.title }}</strong>
                      <span :class="['entry-type-pill', selectedSupportInquiry.status === 'ANSWERED' ? 'entry-type-pill--income' : selectedSupportInquiry.status === 'IN_PROGRESS' ? 'entry-type-pill--neutral' : 'entry-type-pill--expense']">
                        {{ inquiryStatusLabel[selectedSupportInquiry.status] ?? selectedSupportInquiry.status }}
                      </span>
                    </div>
                    <small>{{ selectedSupportInquiry.senderDisplayName }} ({{ selectedSupportInquiry.senderLoginId }}) · {{ formatDateTime(selectedSupportInquiry.createdAt) }}</small>
                    <p class="support-inquiry-content">{{ selectedSupportInquiry.content }}</p>
                    <div v-if="selectedSupportInquiry.attachmentUrl" class="support-inquiry-attachment admin-support-attachment">
                      <a class="button button--ghost" :href="selectedSupportInquiry.attachmentUrl" target="_blank" rel="noreferrer">
                        첨부 파일 보기
                      </a>
                      <img
                        v-if="selectedSupportInquiry.attachmentContentType?.startsWith('image/')"
                        :src="buildThumbnailUrl(selectedSupportInquiry.attachmentUrl)"
                        :alt="selectedSupportInquiry.attachmentFileName || selectedSupportInquiry.title"
                        loading="eager"
                        fetchpriority="high"
                        decoding="async"
                        class="support-inquiry-preview admin-support-preview"
                      />
                    </div>
                  </div>

                  <div class="support-inquiry-card admin-support-reply-card">
                    <div class="support-inquiry-reply__header">
                      <strong>답변 및 처리</strong>
                    </div>
                    <textarea
                      v-model="state.supportReplyContent"
                      rows="8"
                      placeholder="사용자에게 전달할 답변을 입력하세요."
                      :disabled="state.savingReply || state.mutatingInquiryId === selectedSupportInquiry.id"
                    />
                    <div class="support-inquiry-actions admin-support-actions">
                      <button
                        v-if="selectedSupportInquiry.status === 'PENDING' && !selectedSupportInquiry.archived"
                        class="button button--secondary"
                        type="button"
                        :disabled="state.mutatingInquiryId === selectedSupportInquiry.id"
                        @click="handleUpdateSupportStatus(selectedSupportInquiry, 'IN_PROGRESS')"
                      >
                        처리중으로 이동
                      </button>
                      <button
                        v-if="selectedSupportInquiry.status === 'IN_PROGRESS' && !selectedSupportInquiry.archived"
                        class="button button--ghost"
                        type="button"
                        :disabled="state.mutatingInquiryId === selectedSupportInquiry.id"
                        @click="handleUpdateSupportStatus(selectedSupportInquiry, 'PENDING')"
                      >
                        미확인으로 되돌리기
                      </button>
                      <button
                        v-if="selectedSupportInquiry.archived || selectedSupportInquiry.status === 'ANSWERED'"
                        class="button button--secondary"
                        type="button"
                        :disabled="state.mutatingInquiryId === selectedSupportInquiry.id"
                        @click="handleUpdateSupportStatus(selectedSupportInquiry, 'IN_PROGRESS')"
                      >
                        처리중으로 되돌리기
                      </button>
                      <button
                        class="button button--ghost"
                        type="button"
                        :disabled="state.mutatingInquiryId === selectedSupportInquiry.id"
                        @click="handleArchiveToggle(selectedSupportInquiry, true)"
                      >
                        완료 보관
                      </button>
                      <button
                        v-if="selectedSupportInquiry.archived"
                        class="button button--danger"
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
                </template>
                <p v-else class="panel__empty">왼쪽 목록에서 처리할 문의를 선택하세요.</p>
              </section>
            </div>
          </div>
        </div>
      </div>

      <div v-if="state.accessModalOpen" class="travel-modal admin-access-control-modal" @keydown.esc="closeAccessModal">
        <div class="travel-modal__dialog admin-access-control-modal__dialog">
          <div class="travel-modal__header admin-access-control-modal__header">
            <div>
              <span class="admin-command-center__eyebrow">Access / Users</span>
              <h2>접근 및 사용자 관리</h2>
            </div>
            <button class="button button--ghost" type="button" @click="closeAccessModal">닫기</button>
          </div>

          <div class="travel-modal__body admin-access-control-modal__body">
            <div v-if="state.errorMessage" class="feedback feedback--error">{{ state.errorMessage }}</div>
            <div v-if="state.inviteManager.feedbackMessage && state.accessModalView === 'invite'" class="feedback feedback--success">{{ state.inviteManager.feedbackMessage }}</div>
            <div v-if="state.inviteManager.errorMessage && state.accessModalView === 'invite'" class="feedback feedback--error">{{ state.inviteManager.errorMessage }}</div>

            <div class="admin-access-control-nav" aria-label="접근 및 사용자 관리 기능 선택">
              <button
                v-for="option in accessModalOptions"
                :key="option.key"
                type="button"
                :class="['admin-access-control-card', { 'is-active': state.accessModalView === option.key }]"
                @click="selectAccessModalView(option.key)"
              >
                <strong>{{ option.label }}</strong>
                <span>{{ option.count }}건</span>
              </button>
            </div>

            <section v-if="state.accessModalView === 'invite'" class="admin-access-control-panel admin-access-control-panel--invite">
              <div class="admin-access-invite-grid">
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
                <div class="support-inquiry-card admin-access-recent-card">
                  <div class="support-inquiry-reply__header">
                    <strong>최근 초대 링크 상태</strong>
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
                  <div class="panel__actions admin-access-pagination">
                    <button class="button button--ghost" type="button" :disabled="state.invitePage <= 0" @click="state.invitePage -= 1">이전</button>
                    <span>{{ state.invitePage + 1 }} / {{ invitePageCount }}</span>
                    <button class="button button--ghost" type="button" :disabled="state.invitePage + 1 >= invitePageCount" @click="state.invitePage += 1">다음</button>
                  </div>
                </div>
              </div>
            </section>

            <section v-else-if="state.accessModalView === 'blocked'" class="admin-access-control-panel">
              <div class="panel__header">
                <div>
                  <h3>차단된 IP 조회</h3>
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
              <div class="panel__actions admin-access-pagination">
                <button class="button button--ghost" type="button" :disabled="state.blockedIpPage <= 0" @click="state.blockedIpPage -= 1">이전</button>
                <span>{{ state.blockedIpPage + 1 }} / {{ blockedIpPageCount }}</span>
                <button class="button button--ghost" type="button" :disabled="state.blockedIpPage + 1 >= blockedIpPageCount" @click="state.blockedIpPage += 1">다음</button>
              </div>
            </section>

            <section v-else-if="state.accessModalView === 'logs'" class="admin-access-control-panel">
              <div class="panel__header">
                <div>
                  <h3>최근 로그인 기록</h3>
                </div>
                <button class="button button--ghost" type="button" :disabled="state.loadingLoginLogs" @click="loadLoginAuditLogs(state.loginLogPage.page || 0)">
                  {{ state.loadingLoginLogs ? '조회 중...' : '기록 새로고침' }}
                </button>
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
              <div class="panel__actions admin-access-pagination">
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

            <section v-else-if="state.accessModalView === 'users'" class="admin-access-control-panel">
              <div class="panel__header">
                <div>
                  <h3>사용자 상태</h3>
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
              <div class="panel__actions admin-access-pagination">
                <button class="button button--ghost" type="button" :disabled="state.userPage <= 0" @click="state.userPage -= 1">이전</button>
                <span>{{ state.userPage + 1 }} / {{ userPageCount }}</span>
                <button class="button button--ghost" type="button" :disabled="state.userPage + 1 >= userPageCount" @click="state.userPage += 1">다음</button>
              </div>
            </section>
          </div>
        </div>
      </div>

      <section v-if="!state.activeAdminPanel" class="panel admin-empty-state">
        <div>
          <strong>관리할 기능을 선택하세요.</strong>
        </div>
      </section>

      <template v-if="state.activeAdminPanel === 'ops'">
      <section class="panel admin-panel-section">
        <div class="panel__header">
          <div>
            <h2>AI 및 서버 제어판</h2>
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
            <small>가계부 AI: {{ aiFeatureServerStateLabel('ledger') }} · 이미지 OCR: {{ aiFeatureServerStateLabel('image') }}</small>
          </article>
          <article class="summary-card">
            <span>가계부 AI 서버</span>
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

        <form class="support-detail-grid admin-ai-control-grid" @submit.prevent="handleSaveAiControl">
          <div class="support-inquiry-card admin-ai-card admin-ai-card--select">
            <div class="support-inquiry-reply__header">
              <strong>1. AI 서버 선택</strong>
            </div>
            <label class="field field--inline admin-ai-enable-row">
              <input v-model="state.aiControlForm.enabled" type="checkbox" />
              <span class="field__label">AI 분석 사용</span>
            </label>
            <label class="field">
              <span class="field__label">저장된 AI 서버/모델</span>
              <select v-model="state.aiControlPresetKey" @change="applyAiControlPreset">
                <option value="">저장된 조합 선택</option>
                <option v-for="preset in state.aiControlPresets" :key="preset.key" :value="preset.key">
                  {{ formatAiControlPreset(preset) }}
                </option>
              </select>
            </label>
            <div class="admin-ai-current">
              <span>현재 선택</span>
              <strong>{{ state.aiControlForm.model || 'auto' }}</strong>
              <small>{{ state.aiControlForm.lmStudioBaseUrl || 'LM Studio URL 미설정' }}</small>
            </div>
          </div>

          <div class="support-inquiry-card admin-ai-card admin-ai-card--add">
            <div class="support-inquiry-reply__header">
              <strong>2. AI 서버 추가 / 새 설정 입력</strong>
            </div>
            <div class="admin-ai-field-grid">
              <label class="field">
                <span class="field__label">Provider</span>
                <select v-model="state.aiControlForm.provider" @change="handleAiProviderChange">
                  <option value="lmstudio">LM Studio</option>
                  <option value="openai">OpenAI API</option>
                  <option value="ollama">Ollama</option>
                </select>
              </label>
              <label class="field">
                <span class="field__label">모델</span>
                <input v-model="state.aiControlForm.model" placeholder="google/gemma-4-e2b 또는 auto" />
              </label>
              <label class="field admin-ai-field-grid__wide">
                <span class="field__label">LM Studio URL</span>
                <input v-model="state.aiControlForm.lmStudioBaseUrl" placeholder="http://100.x.x.x:1234" />
              </label>
              <label class="field">
                <span class="field__label">Chat path</span>
                <input v-model="state.aiControlForm.lmStudioChatPath" placeholder="/v1/chat/completions" />
              </label>
              <label class="field">
                <span class="field__label">Models path</span>
                <input v-model="state.aiControlForm.lmStudioModelsPath" placeholder="/v1/models" />
              </label>
              <label class="field admin-ai-field-grid__wide">
                <span class="field__label">LM Studio API key 변경</span>
                <input v-model="state.aiControlForm.lmStudioApiKey" type="password" autocomplete="new-password" :disabled="state.aiControlForm.clearLmStudioApiKey" :placeholder="state.aiControlForm.lmStudioApiKeyConfigured ? '설정됨 - 변경할 때만 입력' : '미설정'" />
                      <small v-if="state.aiControlForm.lmStudioApiKeyConfigured" class="admin-ai-secret-status">서버에 암호화 저장됨. 다시 입력하지 않아도 저장 시 재사용됩니다.</small>
              </label>
              <template v-if="state.aiControlForm.provider === 'openai'">
                <label class="field admin-ai-field-grid__wide">
                  <span class="field__label">OpenAI API 주소</span>
                  <input v-model="state.aiControlForm.openAiBaseUrl" placeholder="https://api.openai.com" />
                </label>
                <label class="field">
                  <span class="field__label">Chat Completions path</span>
                  <input v-model="state.aiControlForm.openAiChatPath" placeholder="/v1/chat/completions" />
                </label>
                <label class="field">
                  <span class="field__label">Models path</span>
                  <input v-model="state.aiControlForm.openAiModelsPath" placeholder="/v1/models" />
                </label>
                <label class="field admin-ai-field-grid__wide">
                  <span class="field__label">OpenAI API key 변경</span>
                  <input v-model="state.aiControlForm.openAiApiKey" type="password" autocomplete="new-password" :disabled="state.aiControlForm.clearOpenAiApiKey" :placeholder="state.aiControlForm.openAiApiKeyConfigured ? '설정됨 - 변경할 때만 입력' : '필수 입력'" />
                      <small v-if="state.aiControlForm.openAiApiKeyConfigured" class="admin-ai-secret-status">서버에 암호화 저장됨. 다시 입력하지 않아도 저장 시 재사용됩니다.</small>
                </label>
                <label class="field field--inline admin-ai-field-grid__wide">
                  <input v-model="state.aiControlForm.clearOpenAiApiKey" type="checkbox" />
                  <span class="field__label">OpenAI API key 삭제</span>
                </label>
              </template>
              <label class="field field--inline admin-ai-field-grid__wide">
                <input v-model="state.aiControlForm.clearLmStudioApiKey" type="checkbox" />
                <span class="field__label">LM Studio API key 삭제</span>
              </label>
              <label class="field admin-ai-field-grid__wide">
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
                      <small v-if="state.aiControlForm.apiKeyConfigured" class="admin-ai-secret-status">서버에 암호화 저장됨. 다시 입력하지 않아도 저장 시 재사용됩니다.</small>
              </label>
              <label class="field field--inline admin-ai-field-grid__wide">
                <input v-model="state.aiControlForm.clearApiKey" type="checkbox" />
                <span class="field__label">n8n API key 삭제</span>
              </label>
            </div>
          </div>

          <div class="support-inquiry-card admin-ai-card admin-ai-card--tuning">
            <div class="support-inquiry-reply__header">
              <strong>응답/보안 조절</strong>
            </div>
            <div class="admin-ai-field-grid admin-ai-field-grid--compact">
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
              <label class="field field--inline">
                <input v-model="state.aiControlForm.enforceProviderUrlAllowlist" type="checkbox" />
                <span class="field__label">Provider 허용 목록 강제</span>
              </label>
              <label class="field admin-ai-field-grid__wide">
                <span class="field__label">허용 호스트</span>
                <input v-model="state.aiControlForm.allowedProviderHosts" placeholder="100.x.x.x,127.0.0.1,localhost" />
              </label>
            </div>
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
                <td>가계부 AI 서버</td>
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
      </template>

    </template>
  </section>
</template>
