<script setup>
import { computed, defineAsyncComponent, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import PinPadInput from './components/PinPadInput.vue'
import {
  acceptInvite,
  fetchCurrentUser,
  fetchInvite,
  login,
  logout as logoutRequest,
} from './lib/api'

const AdminWorkspace = defineAsyncComponent(() => import('./components/AdminWorkspace.vue'))
const HouseholdWorkspace = defineAsyncComponent(() => import('./components/HouseholdWorkspace.vue'))
const MainDashboardWorkspace = defineAsyncComponent(() => import('./components/MainDashboardWorkspace.vue'))
const CalenDriveWorkspace = defineAsyncComponent(() => import('./components/CalenDriveWorkspace.vue'))
const NotificationCenterWorkspace = defineAsyncComponent(() => import('./components/NotificationCenterWorkspace.vue'))
const ProfileWorkspace = defineAsyncComponent(() => import('./components/ProfileWorkspace.vue'))
const TravelWorkspace = defineAsyncComponent(() => import('./components/TravelWorkspace.vue'))

const legacyFeatureItems = [
  {
    key: 'household',
    number: '1',
    title: '가계부',
    description: '가계부, 통계, 검색, 일정 기반 입력을 한 화면에서 관리합니다.',
  },
  {
    key: 'travel-money',
    number: '2',
    title: '여행 가계부',
    description: '여행 예산과 실제 지출을 연결해서 기록합니다.',
  },
  {
    key: 'travel-log',
    number: '3',
    title: '여행 기록',
    description: '여행 경로, 방문지, GPX, 사진 기록을 정리합니다.',
  },
  {
    key: 'photo-album',
    number: '4',
    title: '사진 앨범',
    description: '기록과 연결된 사진을 모아봅니다.',
  },
  {
    key: 'family-album',
    number: '5',
    title: '가족 앨범',
    description: '가족 사진과 영상을 앨범으로 관리합니다.',
  },
  {
    key: 'my-map',
    number: '6',
    title: '내 지도',
    description: '여행과 사진 위치를 지도에서 확인합니다.',
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
    description: '가계부, 통계, 검색, 일정 기반 입력을 한 화면에서 관리합니다.',
  },
  {
    key: 'travel',
    number: '2',
    title: '여행',
    description: '여행 기록, 방문지, GPX 경로, 예산, 사진을 한곳에서 관리합니다.',
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
  description: '로그인 기록, 접속 IP, 사용자 상태, 초대 현황을 관리합니다.',
}

const THEME_STORAGE_KEY = 'calen-theme-mode'
const THEME_DEGREE_STORAGE_KEY = 'calen-theme-degree'
const LAYOUT_MODE_STORAGE_KEY = 'calen-layout-mode'
const MOBILE_LAYOUT_QUERY = '(max-width: 760px)'
const DEFAULT_TOSS_DEGREE = 100
const ROUTE_LEAVE_GUARD_EVENT = 'calen-route-leave-guard'
const DEFAULT_ROUTE_LEAVE_GUARD_MESSAGE = '페이지를 벗어나면 작성 중인 내용이 사라질 수 있습니다.'

const routeMeta = {
  notifications: {
    title: '알림 센터',
    description: 'AI, OCR, 백업, 공유, 여행, 예산 알림을 한곳에서 확인합니다.',
  },
  launcher: {
    title: '기능 선택',
    description: '오늘 사용할 기능 영역을 선택하세요.',
  },
  household: {
    title: '가계부',
    description: '가계부, 통계, 검색, 입력 기능을 관리합니다.',
  },
  travel: {
    title: '여행',
    description: '여행 경로, 방문지, 예산, 사진을 관리합니다.',
  },
  drive: {
    title: 'CalenDrive',
    description: '파일 업로드, 공유, 휴지통, 관리자 도구를 관리합니다.',
  },
  'travel-money': {
    title: '여행 가계부',
    description: '여행 예산과 실제 지출을 비교합니다.',
  },
  'travel-log': {
    title: '여행 기록',
    description: '여행 메모, 경로, 방문 기록을 확인합니다.',
  },
  'photo-album': {
    title: '사진 앨범',
    description: '기록과 연결된 사진을 모아봅니다.',
  },
  'family-album': {
    title: '가족 앨범',
    description: '가족 사진과 영상을 앨범으로 관리합니다.',
  },
  'my-map': {
    title: '내 지도',
    description: '여행 경로와 사진 위치를 지도에서 확인합니다.',
  },
  admin: {
    title: '관리자',
    description: '사용자, 초대, 백업, 운영 상태를 관리합니다.',
  },
  profile: {
    title: '프로필',
    description: '계정 정보, 보안 설정, 개인정보 작업을 관리합니다.',
  },
  invite: {
    title: '초대 링크 가입',
    description: '초대 링크로 계정을 만들 수 있습니다.',
  },
}

const correctedFeatureItems = featureItems
const correctedAdminFeatureItem = adminFeatureItem
const correctedRouteMeta = routeMeta
const normalizedFeatureItems = featureItems
const normalizedAdminFeatureItem = adminFeatureItem
const normalizedRouteMeta = routeMeta
const initialRouteState = resolveRouteState(window.location.hash)

const authChecked = ref(false)
const currentUser = ref(null)
const isSubmitting = ref(false)
const activeSubmit = ref('')
const successMessage = ref('')
const errorMessage = ref('')
const activeRoute = ref(initialRouteState.route)
const notificationUnreadCount = ref(0)
const notificationUnreadBadgeLabel = computed(() => (notificationUnreadCount.value > 99 ? '99+' : String(notificationUnreadCount.value)))
const inviteToken = ref(initialRouteState.token)
const householdInitialTab = ref('')
const travelRecordFocusRequest = ref(null)
const inviteInfo = ref(null)
const isInviteLoading = ref(false)
const themeMode = ref('default')
const themeDegree = ref(DEFAULT_TOSS_DEGREE)
const themeDegreePanelOpen = ref(false)
const themeSwitcherRef = ref(null)
const layoutMode = ref('desktop')
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

const travelRouteKeys = new Set(['travel', 'travel-money', 'travel-log', 'photo-album', 'my-map', 'public-trips'])
const pageMeta = computed(() => {
  const routeKey = travelRouteKeys.has(activeRoute.value) ? 'travel' : activeRoute.value
  return normalizedRouteMeta[routeKey] || normalizedRouteMeta.launcher
})
const isTossTheme = computed(() => themeMode.value === 'toss')
const launcherItems = computed(() => (
  currentUser.value?.admin ? [...normalizedFeatureItems, normalizedAdminFeatureItem] : normalizedFeatureItems
))
const headerNavItems = computed(() => {
  const items = [
    { key: 'launcher', label: '메뉴' },
    { key: 'household', label: '가계부' },
    { key: 'travel', label: '여행' },
    { key: 'drive', label: '드라이브' },
  ]
  if (currentUser.value?.admin) {
    items.push({ key: 'admin', label: '관리자' })
  }
  return items
})
const themeDegreeDisplay = computed(() => `${themeDegree.value}%`)
const layoutModeOptions = [
  { value: 'mobile', label: '모바일' },
  { value: 'desktop', label: '데스크톱' },
]

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

  if (route === 'public-trips') {
    return {
      route: 'public-trips',
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

function applyHashRoute(hash) {
  const routeState = resolveRouteState(hash)
  activeRoute.value = routeState.route
  inviteToken.value = routeState.token
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

function normalizeLayoutMode(mode) {
  return mode === 'mobile' ? 'mobile' : 'desktop'
}

function resolveInitialLayoutMode() {
  if (typeof window === 'undefined') {
    return 'desktop'
  }

  const storedMode = window.localStorage.getItem(LAYOUT_MODE_STORAGE_KEY)
  if (storedMode === 'mobile' || storedMode === 'desktop') {
    return storedMode
  }

  return window.matchMedia?.(MOBILE_LAYOUT_QUERY).matches ? 'mobile' : 'desktop'
}

function applyLayoutMode(mode, persist = true) {
  const normalized = normalizeLayoutMode(mode)
  layoutMode.value = normalized

  if (typeof document !== 'undefined') {
    document.documentElement.setAttribute('data-layout-mode', normalized)

    const viewportMeta = document.querySelector('meta[name="viewport"]')
    if (viewportMeta) {
      viewportMeta.setAttribute(
        'content',
        normalized === 'desktop'
          ? 'width=1280, initial-scale=1.0'
          : 'width=device-width, initial-scale=1.0',
      )
    }
  }

  if (persist && typeof window !== 'undefined') {
    window.localStorage.setItem(LAYOUT_MODE_STORAGE_KEY, normalized)
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

function setNotificationUnreadCount(value) {
  notificationUnreadCount.value = Math.max(0, Number(value || 0))
}

async function refreshNotificationUnreadCount() {
  if (!currentUser.value) {
    setNotificationUnreadCount(0)
    return
  }
  try {
    const response = await fetchNotifications({ page: 0, size: 1, unreadOnly: 'true' })
    setNotificationUnreadCount(response?.unreadCount)
  } catch (error) {
    setNotificationUnreadCount(0)
  }
}

function handleNotificationUnreadCountChange(value) {
  setNotificationUnreadCount(value)
}
function navigate(route, options = {}) {
  const nextRoute = normalizedRouteMeta[route] ? route : 'launcher'
  if (nextRoute !== activeRoute.value && !confirmRouteLeaveIfNeeded()) {
    return
  }
  householdInitialTab.value = nextRoute === 'household' ? (options.householdTab || '') : ''
  activeRoute.value = nextRoute
  inviteToken.value = ''
  window.location.hash = nextRoute
}

function navigateHouseholdTravelLedger() {
  navigate('household', { householdTab: 'travel-ledger' })
}

function navigateTravelRecordLocation(payload = {}) {
  const planId = String(payload?.travelPlanId || payload?.planId || '').trim()
  const recordId = String(payload?.travelRecordId || payload?.recordId || '').trim()
  if (planId && recordId) {
    travelRecordFocusRequest.value = {
      planId,
      recordId,
      token: Date.now(),
    }
  }
  navigate('travel-money')
}

function clearTravelRecordFocusRequest(payload = {}) {
  const token = String(payload?.token || '')
  if (!token || token === String(travelRecordFocusRequest.value?.token || '')) {
    travelRecordFocusRequest.value = null
  }
}

function isHeaderNavActive(route) {
  if (route === 'travel') {
    return travelRouteKeys.has(activeRoute.value)
  }
  return activeRoute.value === route
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
    setFeedback('', '유효한 초대 링크를 다시 열어주세요.')
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
  setFeedback('로그아웃되었습니다.')
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

watch(currentUser, (user) => {
  if (user) {
    refreshNotificationUnreadCount()
  } else {
    setNotificationUnreadCount(0)
  }
})

watch(activeRoute, (route) => {
  if (route === 'notifications') {
    refreshNotificationUnreadCount()
  }
})
onMounted(() => {
  if (typeof window !== 'undefined') {
    applyLayoutMode(resolveInitialLayoutMode(), false)
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
  <div class="app-shell" :data-layout-mode="layoutMode">
    <div ref="themeSwitcherRef" class="theme-switcher">
      <div class="theme-switcher__actions">
        <div class="layout-mode-toggle" role="group" aria-label="보기 환경 전환">
          <button
            v-for="option in layoutModeOptions"
            :key="option.value"
            class="layout-mode-toggle__button"
            :class="{ 'layout-mode-toggle__button--active': layoutMode === option.value }"
            type="button"
            :aria-pressed="layoutMode === option.value"
            @click="applyLayoutMode(option.value)"
          >
            {{ option.label }}
          </button>
        </div>
        <button class="theme-toggle" type="button" @click="toggleTheme">
          {{ isTossTheme ? '기본 테마' : '다크 테마' }}
        </button>
        <button
          v-if="isTossTheme"
          class="theme-toggle theme-toggle--degree"
          type="button"
          @click.stop="toggleThemeDegreePanel"
        >
          다크 강도 {{ themeDegreeDisplay }}
        </button>
      </div>

      <div v-if="isTossTheme && themeDegreePanelOpen" class="theme-degree-panel">
        <div class="theme-degree-panel__header">
          <strong>다크 강도</strong>
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
          <span>강하게</span>
        </div>
      </div>
    </div>

    <button v-if="false" class="theme-toggle" type="button" @click="toggleTheme">
      {{ isTossTheme ? '기본 테마' : '다크 테마' }}
    </button>

    <div v-if="!authChecked" class="loading-overlay">인증 확인 중입니다...</div>

    <template v-else-if="activeRoute === 'invite'">
      <section class="auth-shell">
        <div class="auth-copy">
          <span class="auth-copy__badge">초대 링크 가입</span>
          <h1>초대 링크로 계정을 만들고 바로 로그인할 수 있습니다.</h1>
          <p>링크가 유효하면 로그인 ID, 표시 이름, 비밀번호, 2차 비밀번호를 입력해 계정을 만들고 바로 로그인할 수 있습니다.</p>
          <p v-if="currentUser" class="auth-copy__hint">
            현재 {{ currentUser.displayName }} ({{ currentUser.loginId }}) 계정으로 로그인 중입니다. 가입이 끝나면 새 계정으로 전환됩니다.
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
                placeholder="비밀번호(8자 이상)"
                autocomplete="new-password"
                :disabled="isSubmitting || isInviteLoading || !inviteInfo"
              />
              <PinPadInput
                v-model="inviteForm.secondaryPin"
                label="2차 비밀번호"
                hint="가입 후 로그인할 때 사용할 숫자 8자리 이상을 입력합니다."
                :disabled="isSubmitting || isInviteLoading || !inviteInfo"
              />
              <label class="checkbox-row">
                <input
                  v-model="inviteForm.rememberDevice"
                  type="checkbox"
                  :disabled="isSubmitting || isInviteLoading || !inviteInfo"
                />
                <span>이 기기에서 로그인 상태 유지</span>
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

        <div class="auth-grid">
          <article class="auth-card">
            <h2>로그인</h2>
            <form class="stack-form" @submit.prevent="handleLogin">
              <input v-model="loginForm.loginId" type="text" placeholder="로그인 ID" autocomplete="username" />
              <input v-model="loginForm.password" type="password" placeholder="비밀번호" autocomplete="current-password" />
              <PinPadInput
                v-model="loginForm.secondaryPin"
                label="2차 비밀번호"
                hint="보안을 위해 숫자 8자리 이상을 입력해 주세요."
                :disabled="isSubmitting"
              />
              <label class="checkbox-row">
                <input v-model="loginForm.rememberDevice" type="checkbox" />
                <span>이 기기에서 로그인 상태 유지</span>
              </label>
              <button class="button button--primary" type="submit" :disabled="isSubmitting">
                {{ isSubmitting && activeSubmit === 'login' ? '로그인 중...' : '로그인' }}
              </button>
            </form>
          </article>

          <article class="auth-card">
            <h2>초대 상태</h2>
            <div class="stack-form stack-form--readonly">
              <p>초대 정보가 아직 없습니다.</p>
              <p>가입하려면 기존 사용자가 관리자 화면에서 1회용 초대 링크 생성을 요청해 주세요.</p>
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
          <div class="topbar__copy">
            <p class="topbar__eyebrow">{{ pageMeta.title }}</p>
            <h1>초대 링크로 계정을 만들고 바로 로그인할 수 있습니다.</h1>
          </div>
          <nav class="topbar__nav" aria-label="주요 기능">
            <button
              v-for="item in headerNavItems"
              :key="item.key"
              class="topbar__nav-button"
              :class="{ 'topbar__nav-button--active': isHeaderNavActive(item.key) }"
              type="button"
              @click="navigate(item.key)"
            >
              {{ item.label }}
            </button>
                    <button
            type="button"
            :class="['topbar__nav-button', 'topbar__nav-button--notifications', { 'topbar__nav-button--active': activeRoute === 'notifications' }]"
            @click="navigate('notifications')"
          >
            <span>알림</span>
            <span v-if="notificationUnreadCount" class="topbar__notification-badge" aria-label="읽지 않은 알림" aria-live="polite">{{ notificationUnreadBadgeLabel }}</span>
          </button></nav>
          <div class="topbar__actions">
            <button v-if="activeRoute !== 'profile'" class="button button--ghost" @click="navigate('profile')">프로필</button>
            <button class="button button--ghost" @click="handleLogout">로그아웃</button>
          </div>
        </header>

        <div v-if="successMessage" class="feedback feedback--success">{{ successMessage }}</div>
        <div v-if="errorMessage" class="feedback feedback--error">{{ errorMessage }}</div>

        <div v-if="activeRoute === 'launcher'" class="workspace-stack">
          <MainDashboardWorkspace
            :current-user="currentUser"
            :items="launcherItems"
            @navigate="navigate"
          />
        </div>
        <AdminWorkspace v-else-if="activeRoute === 'admin'" :current-user="currentUser" />
        <ProfileWorkspace v-else-if="activeRoute === 'profile'" :current-user="currentUser" />
        <HouseholdWorkspace
          v-else-if="activeRoute === 'household'"
          :current-user="currentUser"
          :initial-tab="householdInitialTab"
          @open-travel-record-location="navigateTravelRecordLocation"
        />
        <CalenDriveWorkspace v-else-if="activeRoute === 'drive'" :current-user="currentUser" />
        <NotificationCenterWorkspace
          v-else-if="activeRoute === 'notifications'"
          @unread-count-change="handleNotificationUnreadCountChange"
        />
        <TravelWorkspace
          v-else-if="travelRouteKeys.has(activeRoute)"
          :route="activeRoute"
          :record-focus-request="travelRecordFocusRequest"
          @open-household-travel-ledger="navigateHouseholdTravelLedger"
          @record-focus-consumed="clearTravelRecordFocusRequest"
        />
      </div>
    </template>
  </div>
</template>
