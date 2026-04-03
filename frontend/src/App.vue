<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import AdminWorkspace from './components/AdminWorkspace.vue'
import FeatureLauncher from './components/FeatureLauncher.vue'
import HouseholdWorkspace from './components/HouseholdWorkspace.vue'
import InviteAccessPanel from './components/InviteAccessPanel.vue'
import PinPadInput from './components/PinPadInput.vue'
import CalenDriveWorkspace from './components/CalenDriveWorkspace.vue'
import ProfileWorkspace from './components/ProfileWorkspace.vue'
import TravelWorkspace from './components/TravelWorkspace.vue'
import {
  acceptInvite,
  createInvite,
  fetchCurrentUser,
  fetchInvite,
  login,
  logout as logoutRequest,
} from './lib/api'

const legacyFeatureItems = [
  {
    key: 'household',
    number: '1',
    title: '가계부',
    description: '가계부, 통계, 검색, 분류 관리까지 한 화면에서 바로 사용할 수 있습니다.',
  },
  {
    key: 'travel-money',
    number: '2',
    title: '여행 예산',
    description: '여행 계획, 예산안, 실제 지출과 요약 통계를 함께 관리합니다.',
  },
  {
    key: 'travel-log',
    number: '3',
    title: '여행 로그',
    description: '여행 기록, 경로, 사진, GPX 파일을 한 워크스페이스에서 정리합니다.',
  },
  {
    key: 'photo-album',
    number: '4',
    title: '여행 사진',
    description: '기록에 연결된 여행 사진과 추억을 전용 갤러리 화면에서 볼 수 있습니다.',
  },
  {
    key: 'family-album',
    number: '5',
    title: '가족 앨범',
    description: '초대된 가족 구성원과 카테고리별 사진과 영상을 공유합니다.',
  },
  {
    key: 'my-map',
    number: '6',
    title: '내 지도',
    description: '지금까지 저장한 여행 장소 핀과 이동 경로를 한 장의 지도에서 확인합니다.',
  },
  {
    key: 'drive',
    number: '4',
    title: 'CalenDrive',
    description: '?뚯씪 ?낅줈?쒖? 怨듭쑀, ?댁??? 愿由ъ? ?꾨줈???ㅼ젙源뚯? 而щ씪?곗? ?쒕씪?대툕 湲곕뒫???꾩슜 ?뚰겕?ㅽ럹?댁뒪濡??ъ슜?⑸땲??',
  },
  {
    key: 'drive',
    number: '4',
    title: 'CalenDrive',
    description: 'Cloud drive, sharing, trash, and admin tools in one workspace.',
  },
]

const featureItems = [
  {
    key: 'household',
    number: '1',
    title: '가계부',
    description: '가계부, 통계, 검색과 분류 관리까지 한 화면에서 바로 사용할 수 있습니다.',
  },
  {
    key: 'travel',
    number: '2',
    title: '여행',
    description: '여행 설정, 여행 가계부, 여행 로그, 내 지도와 사진 기능을 한 워크스페이스에서 이어서 관리합니다.',
  },
  {
    key: 'drive',
    number: '4',
    title: 'CalenDrive',
    description: 'Cloud drive, sharing, trash, and admin tools in one workspace.',
  },
]

const adminFeatureItem = {
  key: 'admin',
  number: '7',
  title: '관리자',
  description: '로그인 로그, 차단 IP, 사용자 상태, 초대 현황을 관리합니다.',
}

const THEME_STORAGE_KEY = 'calen-theme-mode'
const THEME_DEGREE_STORAGE_KEY = 'calen-theme-degree'
const DEFAULT_TOSS_DEGREE = 100
const ROUTE_LEAVE_GUARD_EVENT = 'calen-route-leave-guard'
const DEFAULT_ROUTE_LEAVE_GUARD_MESSAGE = '페이지를 벗어나면 다시 처음부터 업로드 해야합니다.'

const routeMeta = {
  launcher: {
    title: '기능 선택',
    description: '다음으로 열고 싶은 기능 영역을 선택하세요.',
  },
  household: {
    title: '가계부',
    description: '달력 가계부, 통계, 검색, 분류 관리 기능을 함께 확인합니다.',
  },
  travel: {
    title: '여행',
    description: '여행 설정, 여행 가계부, 여행 로그, 내 지도와 사진 기능을 하나의 여행 워크스페이스에서 사용합니다.',
  },
  drive: {
    title: 'CalenDrive',
    description: '?뚯씪 ?쒕씪?대툕, 怨듭쑀, ?댁??? 愿由?, ?꾨줈???ㅼ젙, 愿由ъ옄 湲곕뒫?꾩? 4踰??섏씠吏?먯꽌 ?듯빀?섏뿬 ?ъ슜?⑸땲??',
  },
  'travel-money': {
    title: '여행 예산',
    description: '여행 예산안과 실제 지출을 한 곳에서 관리합니다.',
  },
  'travel-log': {
    title: '여행 로그',
    description: '여행 메모, 이동 경로, 장소, 업로드 파일을 확인합니다.',
  },
  'photo-album': {
    title: '여행 사진',
    description: '기록을 기반으로 구성된 사진 중심 여행 화면을 둘러봅니다.',
  },
  'family-album': {
    title: '가족 앨범',
    description: '초대된 구성원과 가족 카테고리, 앨범, 미디어를 공유합니다.',
  },
  'my-map': {
    title: '내 지도',
    description: '전체 여행의 핀과 경로를 지도로 모아 보고, 원하는 핀만 눌러 자세히 확인합니다.',
  },
  admin: {
    title: '관리자',
    description: '로그인 로그와 사용자 상태, 초대 현황을 점검합니다.',
  },
  profile: {
    title: '내 프로필',
    description: '내 계정 정보와 문의 내역, 관리자 답변을 한 곳에서 확인합니다.',
  },
  invite: {
    title: '초대 링크 가입',
    description: '새 계정은 1회용 초대 링크로만 만들 수 있습니다.',
  },
}

const correctedFeatureItems = [
  {
    key: 'household',
    number: '1',
    title: '가계부',
    description: '가계부, 통계, 검색과 분류 관리까지 한 화면에서 바로 사용할 수 있습니다.',
  },
  {
    key: 'travel',
    number: '2',
    title: '여행',
    description: '여행 설정, 여행 가계부, 여행 로그, 내 지도와 사진 기능을 한 워크스페이스에서 관리합니다.',
  },
  {
    key: 'drive',
    number: '4',
    title: 'CalenDrive',
    description: '클라우드 드라이브, 공유, 최근 파일, 휴지통, 관리자 기능을 하나의 화면에서 사용합니다.',
  },
]

const correctedAdminFeatureItem = {
  key: 'admin',
  number: '7',
  title: '관리자',
  description: '로그인 로그, 차단 IP, 사용자 상태, 초대 현황을 관리합니다.',
}

const correctedRouteMeta = {
  ...routeMeta,
  launcher: {
    title: '기능 선택',
    description: '다음으로 열고 싶은 기능 영역을 선택하세요.',
  },
  household: {
    title: '가계부',
    description: '가계부, 통계, 검색과 분류 관리까지 한 화면에서 바로 사용할 수 있습니다.',
  },
  travel: {
    title: '여행',
    description: '여행 설정, 여행 가계부, 여행 로그, 내 지도와 사진 기능을 한 워크스페이스에서 관리합니다.',
  },
  drive: {
    title: 'CalenDrive',
    description: '구글 드라이브형 파일 관리 구조를 Calen 안으로 옮겨, 업로드·공유·최근 파일·휴지통·관리 기능을 한 공간에서 사용합니다.',
  },
  admin: {
    title: '관리자',
    description: '로그인 로그, 차단 IP, 사용자 상태, 초대 현황을 관리합니다.',
  },
  profile: {
    title: '내 프로필',
    description: '계정 정보와 문의 내역, 관리자 답변을 한 곳에서 확인합니다.',
  },
  invite: {
    title: '초대 링크 가입',
    description: '새 계정은 1회용 초대 링크로만 만들 수 있습니다.',
  },
}

const normalizedFeatureItems = [
  {
    key: 'household',
    number: '1',
    title: '가계부',
    description: '가계부, 통계, 검색과 분류 관리까지 한 화면에서 바로 사용할 수 있습니다.',
  },
  {
    key: 'travel',
    number: '2',
    title: '여행',
    description: '여행 설정, 여행 가계부, 여행 로그, 내 지도와 사진 기능을 한 워크스페이스에서 관리합니다.',
  },
  {
    key: 'drive',
    number: '4',
    title: 'CalenDrive',
    description: '파일 업로드, 공유, 휴지통, 관리자 도구를 하나의 드라이브 화면에서 사용합니다.',
  },
]

const normalizedAdminFeatureItem = {
  key: 'admin',
  number: '7',
  title: '관리자',
  description: '로그인 로그, 차단 IP, 사용자 상태, 초대 현황을 관리합니다.',
}

const normalizedRouteMeta = {
  launcher: {
    title: '기능 선택',
    description: '다음으로 열고 싶은 기능 영역을 선택하세요.',
  },
  household: {
    title: '가계부',
    description: '월별 가계부, 통계, 검색과 분류 관리 기능을 한곳에서 확인합니다.',
  },
  travel: {
    title: '여행',
    description: '여행 설정, 여행 가계부, 여행 로그, 내 지도와 사진 기능을 한 워크스페이스에서 사용합니다.',
  },
  drive: {
    title: 'CalenDrive',
    description: '파일 업로드, 폴더 관리, 공유, 휴지통, 관리자 기능을 드라이브 화면에서 사용합니다.',
  },
  'travel-money': {
    title: '여행 예산',
    description: '여행 예산안과 실제 지출을 한곳에서 관리합니다.',
  },
  'travel-log': {
    title: '여행 로그',
    description: '여행 기록, 이동 경로, 장소, 업로드 파일을 확인합니다.',
  },
  'photo-album': {
    title: '여행 사진',
    description: '기록 기반으로 구성된 여행 사진 모아보기를 확인합니다.',
  },
  'family-album': {
    title: '가족 앨범',
    description: '가족 구성원과 함께 쓰는 사진 및 영상 앨범을 확인합니다.',
  },
  'my-map': {
    title: '내 지도',
    description: '전체 여행의 핀과 경로를 지도로 모아 보고, 원하는 핀만 눌러 자세히 확인합니다.',
  },
  admin: {
    title: '관리자',
    description: '로그인 로그, 차단 IP, 사용자 상태, 초대 현황을 관리합니다.',
  },
  profile: {
    title: '내 프로필',
    description: '계정 정보와 문의 내역, 관리자 여부를 한곳에서 확인합니다.',
  },
  invite: {
    title: '초대 링크 만들기',
    description: '새 계정은 1회용 초대 링크로만 만들 수 있습니다.',
  },
}

const initialRouteState = resolveRouteState(window.location.hash)

const authChecked = ref(false)
const currentUser = ref(null)
const isSubmitting = ref(false)
const activeSubmit = ref('')
const successMessage = ref('')
const errorMessage = ref('')
const activeRoute = ref(initialRouteState.route)
const inviteToken = ref(initialRouteState.token)
const inviteInfo = ref(null)
const isInviteLoading = ref(false)
const isCreatingInvite = ref(false)
const themeMode = ref('default')
const themeDegree = ref(DEFAULT_TOSS_DEGREE)
const themeDegreePanelOpen = ref(false)
const themeSwitcherRef = ref(null)
const routeLeaveGuard = reactive({
  active: false,
  message: DEFAULT_ROUTE_LEAVE_GUARD_MESSAGE,
})

const loginForm = reactive({
  loginId: '',
  password: '',
  secondaryPin: '',
  rememberDevice: true,
})

const inviteForm = reactive({
  loginId: '',
  displayName: '',
  password: '',
  secondaryPin: '',
  rememberDevice: true,
})

const inviteManager = reactive({
  expiresInHours: 72,
  generatedLink: '',
  generatedExpiresAt: '',
  feedbackMessage: '',
  errorMessage: '',
})

const travelRouteKeys = new Set(['travel', 'travel-money', 'travel-log', 'photo-album', 'my-map'])
const pageMeta = computed(() => {
  const routeKey = travelRouteKeys.has(activeRoute.value) ? 'travel' : activeRoute.value
  return normalizedRouteMeta[routeKey] || normalizedRouteMeta.launcher
})
const isTossTheme = computed(() => themeMode.value === 'toss')
const launcherItems = computed(() => (
  currentUser.value?.admin ? [...normalizedFeatureItems, normalizedAdminFeatureItem] : normalizedFeatureItems
))
const themeDegreeDisplay = computed(() => `${themeDegree.value}%`)

let inviteRequestSequence = 0

function resolveRouteState(hash) {
  const route = String(hash || '').replace(/^#/, '').trim()
  if (route.toLowerCase().startsWith('invite/')) {
    return {
      route: 'invite',
      token: decodeURIComponent(route.slice('invite/'.length)).trim(),
    }
  }

  if (route === 'family-album') {
    return {
      route: 'travel',
      token: '',
    }
  }

  return {
    route: normalizedRouteMeta[route] ? route : 'launcher',
    token: '',
  }
}

function formatDateTime(value) {
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

function setFeedback(message = '', error = '') {
  successMessage.value = message
  errorMessage.value = error
}

function clearInviteManagerFeedback() {
  inviteManager.feedbackMessage = ''
  inviteManager.errorMessage = ''
}

function applyHashRoute(hash) {
  const routeState = resolveRouteState(hash)
  activeRoute.value = routeState.route
  inviteToken.value = routeState.token
}

function buildInviteUrl(token) {
  const path = window.location.pathname || '/'
  return `${window.location.origin}${path}#invite/${encodeURIComponent(token)}`
}

function clampThemeDegree(value) {
  const numeric = Number(value)
  if (!Number.isFinite(numeric)) {
    return DEFAULT_TOSS_DEGREE
  }

  return Math.min(100, Math.max(0, Math.round(numeric)))
}

function mixChannel(start, end, ratio) {
  return Math.round(start + (end - start) * ratio)
}

function mixHexColor(start, end, ratio) {
  const normalizedStart = start.replace('#', '')
  const normalizedEnd = end.replace('#', '')
  const startRgb = [
    parseInt(normalizedStart.slice(0, 2), 16),
    parseInt(normalizedStart.slice(2, 4), 16),
    parseInt(normalizedStart.slice(4, 6), 16),
  ]
  const endRgb = [
    parseInt(normalizedEnd.slice(0, 2), 16),
    parseInt(normalizedEnd.slice(2, 4), 16),
    parseInt(normalizedEnd.slice(4, 6), 16),
  ]
  const mixed = startRgb.map((channel, index) => mixChannel(channel, endRgb[index], ratio))
  return `rgb(${mixed[0]}, ${mixed[1]}, ${mixed[2]})`
}

function mixRgbaColor(start, end, ratio) {
  const mixed = start.map((channel, index) => (
    index === 3
      ? channel + (end[index] - channel) * ratio
      : mixChannel(channel, end[index], ratio)
  ))

  return `rgba(${mixed[0]}, ${mixed[1]}, ${mixed[2]}, ${mixed[3].toFixed(3)})`
}

function buildTossThemePalette(degree) {
  const ratio = clampThemeDegree(degree) / 100

  return {
    '--toss-bg': mixHexColor('#202632', '#0e1117', ratio),
    '--toss-surface': mixHexColor('#2a313e', '#151922', ratio),
    '--toss-surface-panel-start': mixRgbaColor([43, 50, 63, 0.98], [24, 29, 38, 0.98], ratio),
    '--toss-surface-panel-end': mixRgbaColor([33, 39, 50, 0.98], [18, 22, 30, 0.98], ratio),
    '--toss-surface-elevated-start': mixHexColor('#2d3542', '#181d26', ratio),
    '--toss-surface-elevated-end': mixHexColor('#232a36', '#121720', ratio),
    '--toss-surface-soft': mixHexColor('#313948', '#1a202b', ratio),
    '--toss-surface-soft-strong': mixHexColor('#3b4556', '#232b38', ratio),
    '--toss-line': mixHexColor('#465164', '#2a3240', ratio),
    '--toss-text-soft': mixHexColor('#ccd4e1', '#b0bacb', ratio),
    '--toss-text-muted': mixHexColor('#a0abbe', '#7e8aa0', ratio),
    '--toss-bg-glow': mixRgbaColor([79, 140, 255, 0.1], [79, 140, 255, 0.16], ratio),
    '--toss-bg-gradient-mid': mixHexColor('#1f2632', '#10141c', ratio),
    '--toss-bg-gradient-end': mixHexColor('#19202a', '#0c1016', ratio),
    '--toss-theme-toggle-bg': mixRgbaColor([35, 42, 54, 0.9], [23, 28, 37, 0.92], ratio),
    '--toss-theme-toggle-border': mixRgbaColor([112, 135, 179, 0.22], [79, 140, 255, 0.24], ratio),
    '--toss-theme-toggle-text': mixHexColor('#eef4ff', '#dce7ff', ratio),
    '--toss-calendar-size-toggle-bg': mixRgbaColor([39, 46, 59, 0.94], [24, 29, 38, 0.94], ratio),
    '--toss-resize-panel-bg': mixRgbaColor([30, 36, 47, 0.96], [14, 20, 29, 0.96], ratio),
  }
}

function applyThemeDegree(degree) {
  const normalized = clampThemeDegree(degree)
  themeDegree.value = normalized

  if (typeof document !== 'undefined') {
    const rootStyle = document.documentElement.style
    const palette = buildTossThemePalette(normalized)
    Object.entries(palette).forEach(([key, value]) => {
      rootStyle.setProperty(key, value)
    })
  }

  if (typeof window !== 'undefined') {
    window.localStorage.setItem(THEME_DEGREE_STORAGE_KEY, String(normalized))
  }
}

function applyTheme(mode) {
  const normalized = mode === 'toss' || mode === 'metal-dark' ? 'toss' : 'default'
  themeMode.value = normalized

  if (typeof document !== 'undefined') {
    if (normalized === 'toss') {
      document.documentElement.setAttribute('data-theme', 'toss')
      applyThemeDegree(themeDegree.value)
    } else {
      document.documentElement.removeAttribute('data-theme')
      themeDegreePanelOpen.value = false
    }
  }

  if (typeof window !== 'undefined') {
    window.localStorage.setItem(THEME_STORAGE_KEY, normalized)
  }
}

function toggleTheme() {
  applyTheme(isTossTheme.value ? 'default' : 'toss')
}

function toggleThemeDegreePanel() {
  if (!isTossTheme.value) {
    return
  }

  themeDegreePanelOpen.value = !themeDegreePanelOpen.value
}

function handleThemeDegreeInput(event) {
  applyThemeDegree(event.target.value)
}

function handleDocumentPointerDown(event) {
  if (!themeDegreePanelOpen.value) {
    return
  }

  if (themeSwitcherRef.value?.contains(event.target)) {
    return
  }

  themeDegreePanelOpen.value = false
}

function buildCurrentHashRoute() {
  if (activeRoute.value === 'invite' && inviteToken.value) {
    return `invite/${encodeURIComponent(inviteToken.value)}`
  }
  return activeRoute.value || 'launcher'
}

function confirmRouteLeaveIfNeeded() {
  if (!routeLeaveGuard.active || typeof window === 'undefined') {
    return true
  }

  return window.confirm(routeLeaveGuard.message || DEFAULT_ROUTE_LEAVE_GUARD_MESSAGE)
}

function handleRouteLeaveGuardChange(event) {
  routeLeaveGuard.active = Boolean(event?.detail?.active)
  routeLeaveGuard.message = String(event?.detail?.message || DEFAULT_ROUTE_LEAVE_GUARD_MESSAGE)
}

function handleBeforeUnload(event) {
  if (!routeLeaveGuard.active) {
    return
  }

  event.preventDefault()
  event.returnValue = ''
}

function navigate(route) {
  const nextRoute = normalizedRouteMeta[route] ? route : 'launcher'
  if (nextRoute !== activeRoute.value && !confirmRouteLeaveIfNeeded()) {
    return
  }
  activeRoute.value = nextRoute
  inviteToken.value = ''
  window.location.hash = nextRoute
}

function handleHashChange() {
  const nextState = resolveRouteState(window.location.hash)
  if (
    (nextState.route !== activeRoute.value || nextState.token !== inviteToken.value)
    && !confirmRouteLeaveIfNeeded()
  ) {
    window.location.hash = buildCurrentHashRoute()
    return
  }

  applyHashRoute(window.location.hash)
}

async function restoreSession() {
  try {
    currentUser.value = await fetchCurrentUser()
  } catch (error) {
    currentUser.value = null
    if (error.status !== 401) {
      setFeedback('', error.message)
    }
  } finally {
    authChecked.value = true
  }
}

async function loadInviteDetails(token) {
  const requestId = ++inviteRequestSequence

  if (!token) {
    inviteInfo.value = null
    isInviteLoading.value = false
    setFeedback('', '초대 링크가 올바르지 않습니다.')
    return
  }

  isInviteLoading.value = true
  inviteInfo.value = null
  setFeedback()

  try {
    const response = await fetchInvite(token)
    if (requestId !== inviteRequestSequence) {
      return
    }
    inviteInfo.value = response
  } catch (error) {
    if (requestId !== inviteRequestSequence) {
      return
    }
    inviteInfo.value = null
    setFeedback('', error.message)
  } finally {
    if (requestId === inviteRequestSequence) {
      isInviteLoading.value = false
    }
  }
}

async function handleLogin() {
  isSubmitting.value = true
  activeSubmit.value = 'login'
  setFeedback()

  try {
    currentUser.value = await login({
      loginId: loginForm.loginId.trim(),
      password: loginForm.password,
      secondaryPin: loginForm.secondaryPin,
      rememberDevice: loginForm.rememberDevice,
    })
    loginForm.password = ''
    loginForm.secondaryPin = ''
    navigate('launcher')
    setFeedback('로그인되었습니다.')
  } catch (error) {
    setFeedback('', error.message)
  } finally {
    isSubmitting.value = false
    activeSubmit.value = ''
  }
}

async function handleAcceptInvite() {
  if (!inviteInfo.value || !inviteToken.value) {
    setFeedback('', '유효한 초대 링크를 먼저 열어주세요.')
    return
  }

  isSubmitting.value = true
  activeSubmit.value = 'invite'
  setFeedback()

  const normalizedLoginId = inviteForm.loginId.trim()
  const normalizedDisplayName = inviteForm.displayName.trim()

  try {
    await acceptInvite({
      token: inviteToken.value,
      loginId: normalizedLoginId,
      displayName: normalizedDisplayName,
      password: inviteForm.password,
      secondaryPin: inviteForm.secondaryPin,
    })

    currentUser.value = await login({
      loginId: normalizedLoginId,
      password: inviteForm.password,
      secondaryPin: inviteForm.secondaryPin,
      rememberDevice: inviteForm.rememberDevice,
    })

    inviteForm.loginId = ''
    inviteForm.displayName = ''
    inviteForm.password = ''
    inviteForm.secondaryPin = ''
    navigate('launcher')
    setFeedback('초대 링크로 계정을 만들고 바로 로그인했습니다.')
  } catch (error) {
    setFeedback('', error.message)
  } finally {
    isSubmitting.value = false
    activeSubmit.value = ''
  }
}

async function handleCreateInvite() {
  isCreatingInvite.value = true
  clearInviteManagerFeedback()

  try {
    const response = await createInvite({
      expiresInHours: inviteManager.expiresInHours,
    })

    inviteManager.generatedLink = buildInviteUrl(response.token)
    inviteManager.generatedExpiresAt = response.expiresAt
    inviteManager.feedbackMessage = '1회용 초대 링크를 만들었습니다. 복사해서 전달해주세요.'
  } catch (error) {
    inviteManager.errorMessage = error.message
  } finally {
    isCreatingInvite.value = false
  }
}

async function copyInviteLink() {
  if (!inviteManager.generatedLink) {
    return
  }

  clearInviteManagerFeedback()

  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(inviteManager.generatedLink)
    } else {
      const element = document.createElement('textarea')
      element.value = inviteManager.generatedLink
      element.setAttribute('readonly', 'readonly')
      element.style.position = 'absolute'
      element.style.left = '-9999px'
      document.body.appendChild(element)
      element.select()
      document.execCommand('copy')
      element.remove()
    }

    inviteManager.feedbackMessage = '초대 링크를 클립보드에 복사했습니다.'
  } catch {
    inviteManager.errorMessage = '브라우저에서 자동 복사를 지원하지 않아 직접 복사해야 합니다.'
  }
}

async function handleLogout() {
  try {
    await logoutRequest()
  } catch {
    // Keep the UI consistent even when the logout request fails.
  }

  currentUser.value = null
  loginForm.password = ''
  loginForm.secondaryPin = ''
  inviteForm.password = ''
  inviteForm.secondaryPin = ''
  navigate('launcher')
  setFeedback('로그아웃했습니다.')
}

watch([activeRoute, inviteToken], ([route, token]) => {
  if (route === 'invite') {
    loadInviteDetails(token)
    return
  }

  inviteRequestSequence += 1
  inviteInfo.value = null
  isInviteLoading.value = false
}, { immediate: true })

watch([currentUser, activeRoute], ([user, route]) => {
  if (route === 'admin' && !(user && user.admin)) {
    navigate('launcher')
  }
}, { immediate: true })

onMounted(() => {
  if (typeof window !== 'undefined') {
    themeDegree.value = clampThemeDegree(window.localStorage.getItem(THEME_DEGREE_STORAGE_KEY) ?? DEFAULT_TOSS_DEGREE)
    applyTheme(window.localStorage.getItem(THEME_STORAGE_KEY) || 'default')
  }
  window.addEventListener('hashchange', handleHashChange)
  window.addEventListener('beforeunload', handleBeforeUnload)
  window.addEventListener(ROUTE_LEAVE_GUARD_EVENT, handleRouteLeaveGuardChange)
  document.addEventListener('pointerdown', handleDocumentPointerDown)
  restoreSession()
})

onBeforeUnmount(() => {
  window.removeEventListener('hashchange', handleHashChange)
  window.removeEventListener('beforeunload', handleBeforeUnload)
  window.removeEventListener(ROUTE_LEAVE_GUARD_EVENT, handleRouteLeaveGuardChange)
  document.removeEventListener('pointerdown', handleDocumentPointerDown)
})
</script>

<template>
  <div class="app-shell">
    <div ref="themeSwitcherRef" class="theme-switcher">
      <div class="theme-switcher__actions">
        <button class="theme-toggle" type="button" @click="toggleTheme">
          {{ isTossTheme ? '기본 테마' : '토스 테마' }}
        </button>
        <button
          v-if="isTossTheme"
          class="theme-toggle theme-toggle--degree"
          type="button"
          @click.stop="toggleThemeDegreePanel"
        >
          다크 degree {{ themeDegreeDisplay }}
        </button>
      </div>

      <div v-if="isTossTheme && themeDegreePanelOpen" class="theme-degree-panel">
        <div class="theme-degree-panel__header">
          <strong>딥다크 강도</strong>
          <span>{{ themeDegreeDisplay }}</span>
        </div>
        <input
          class="theme-degree-panel__slider"
          type="range"
          min="0"
          max="100"
          step="1"
          :value="themeDegree"
          @input="handleThemeDegreeInput"
        />
        <div class="theme-degree-panel__labels">
          <span>일반 다크</span>
          <span>딥다크</span>
        </div>
      </div>
    </div>

    <button v-if="false" class="theme-toggle" type="button" @click="toggleTheme">
      {{ isTossTheme ? '기본 테마' : '토스 테마' }}
    </button>

    <div v-if="!authChecked" class="loading-overlay">세션을 확인하는 중입니다...</div>

    <template v-else-if="activeRoute === 'invite'">
      <section class="auth-shell">
        <div class="auth-copy">
          <span class="auth-copy__badge">초대 링크 가입</span>
          <h1>새 계정은 1회용 초대 링크로만 만들 수 있습니다.</h1>
          <p>링크가 유효하면 로그인 ID, 표시 이름, 비밀번호를 입력해 계정을 만들고 바로 로그인할 수 있습니다.</p>
          <p v-if="currentUser" class="auth-copy__hint">
            현재 {{ currentUser.displayName }} ({{ currentUser.loginId }}) 계정으로 로그인 중입니다. 가입이 끝나면 이 브라우저는 새 계정으로 전환됩니다.
          </p>
        </div>

        <div class="auth-grid">
          <article class="auth-card">
            <h2>초대 상태</h2>
            <div class="stack-form stack-form--readonly">
              <p v-if="isInviteLoading">초대 링크를 확인하는 중입니다...</p>
              <template v-else-if="inviteInfo">
                <p><strong>{{ inviteInfo.inviterDisplayName }}</strong> 님이 만든 초대 링크입니다.</p>
                <p>만료 시간: {{ formatDateTime(inviteInfo.expiresAt) }}</p>
              </template>
              <p v-else>이 링크로는 계정을 만들 수 없습니다.</p>
            </div>
          </article>

          <article class="auth-card">
            <h2>초대 계정 만들기</h2>
            <form class="stack-form" @submit.prevent="handleAcceptInvite">
              <input
                v-model="inviteForm.loginId"
                type="text"
                placeholder="로그인 ID"
                autocomplete="username"
                :disabled="isSubmitting || isInviteLoading || !inviteInfo"
              />
              <input
                v-model="inviteForm.displayName"
                type="text"
                placeholder="표시 이름"
                autocomplete="name"
                :disabled="isSubmitting || isInviteLoading || !inviteInfo"
              />
              <input
                v-model="inviteForm.password"
                type="password"
                placeholder="비밀번호 (8자 이상)"
                autocomplete="new-password"
                :disabled="isSubmitting || isInviteLoading || !inviteInfo"
              />
              <PinPadInput
                v-model="inviteForm.secondaryPin"
                label="2차 비밀번호"
                hint="가입 후 로그인할 때도 같은 숫자 8자리를 마우스로 눌러 입력합니다."
                :disabled="isSubmitting || isInviteLoading || !inviteInfo"
              />
              <label class="checkbox-row">
                <input
                  v-model="inviteForm.rememberDevice"
                  type="checkbox"
                  :disabled="isSubmitting || isInviteLoading || !inviteInfo"
                />
                <span>이 브라우저에서 로그인 상태 유지</span>
              </label>
              <button class="button button--primary" type="submit" :disabled="isSubmitting || isInviteLoading || !inviteInfo">
                {{ isSubmitting && activeSubmit === 'invite' ? '계정 생성 중...' : '계정 만들고 로그인' }}
              </button>
            </form>
          </article>
        </div>
      </section>

      <div v-if="successMessage" class="feedback feedback--success auth-feedback">{{ successMessage }}</div>
      <div v-if="errorMessage" class="feedback feedback--error auth-feedback">{{ errorMessage }}</div>
    </template>

    <template v-else-if="!currentUser">
      <section class="auth-shell">
        <div class="auth-copy">
          <span class="auth-copy__badge">로그인 후 사용</span>
          <h1>로그인하면 가계부, 여행, 가족 앨범 기능을 바로 사용할 수 있습니다.</h1>
          <p>공개 회원가입은 비활성화되어 있고, 새 계정은 기존 사용자가 만든 1회용 초대 링크로만 생성됩니다.</p>
        </div>

        <div class="auth-grid">
          <article class="auth-card">
            <h2>로그인</h2>
            <form class="stack-form" @submit.prevent="handleLogin">
              <input v-model="loginForm.loginId" type="text" placeholder="로그인 ID" autocomplete="username" />
              <input v-model="loginForm.password" type="password" placeholder="비밀번호" autocomplete="current-password" />
              <PinPadInput
                v-model="loginForm.secondaryPin"
                label="2차 비밀번호"
                hint="키보드 대신 숫자 버튼을 눌러 8자리를 입력해주세요."
                :disabled="isSubmitting"
              />
              <label class="checkbox-row">
                <input v-model="loginForm.rememberDevice" type="checkbox" />
                <span>이 브라우저에서 로그인 상태 유지</span>
              </label>
              <button class="button button--primary" type="submit" :disabled="isSubmitting">
                {{ isSubmitting && activeSubmit === 'login' ? '로그인 중...' : '로그인' }}
              </button>
            </form>
          </article>

          <article class="auth-card">
            <h2>계정 안내</h2>
            <div class="stack-form stack-form--readonly">
              <p>공개 회원가입은 꺼져 있습니다.</p>
              <p>새 계정이 필요하면 기존 사용자나 관리자에게 1회용 초대 링크 생성을 요청해주세요.</p>
            </div>
          </article>
        </div>
      </section>

      <div v-if="successMessage" class="feedback feedback--success auth-feedback">{{ successMessage }}</div>
      <div v-if="errorMessage" class="feedback feedback--error auth-feedback">{{ errorMessage }}</div>
    </template>

    <template v-else>
      <div class="main-shell main-shell--standalone">
        <header class="topbar">
          <div>
            <p class="topbar__eyebrow">{{ pageMeta.title }}</p>
            <h1>{{ pageMeta.description }}</h1>
          </div>
          <div class="topbar__actions">
            <button v-if="activeRoute !== 'profile'" class="button button--ghost" @click="navigate('profile')">내 프로필</button>
            <button v-if="activeRoute !== 'launcher'" class="button button--ghost" @click="navigate('launcher')">기능 선택으로</button>
            <button class="button button--ghost" @click="handleLogout">로그아웃</button>
          </div>
        </header>

        <div v-if="successMessage" class="feedback feedback--success">{{ successMessage }}</div>
        <div v-if="errorMessage" class="feedback feedback--error">{{ errorMessage }}</div>

        <div v-if="activeRoute === 'launcher'" class="workspace-stack">
          <FeatureLauncher
            :current-user="currentUser"
            :items="launcherItems"
            @navigate="navigate"
          />
          <InviteAccessPanel
            v-if="currentUser?.admin"
            :expires-in-hours="inviteManager.expiresInHours"
            :generated-link="inviteManager.generatedLink"
            :generated-expires-at="inviteManager.generatedExpiresAt"
            :is-creating="isCreatingInvite"
            :feedback-message="inviteManager.feedbackMessage"
            :error-message="inviteManager.errorMessage"
            @change-expiry="inviteManager.expiresInHours = $event"
            @create-invite="handleCreateInvite"
            @copy-invite="copyInviteLink"
          />
        </div>
        <AdminWorkspace v-else-if="activeRoute === 'admin'" :current-user="currentUser" />
        <ProfileWorkspace v-else-if="activeRoute === 'profile'" :current-user="currentUser" />
        <HouseholdWorkspace v-else-if="activeRoute === 'household'" />
        <CalenDriveWorkspace v-else-if="activeRoute === 'drive'" :current-user="currentUser" />
        <TravelWorkspace v-else-if="travelRouteKeys.has(activeRoute)" :route="activeRoute" />
      </div>
    </template>
  </div>
</template>
