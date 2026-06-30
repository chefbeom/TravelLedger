<script setup>
import {
  fetchNotifications, computed, defineAsyncComponent, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
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
    title: '媛?�꾨?',
    description: '媛?�꾨?, ???�? 寃?? ?�꾨�??�?�ш퉴吏 ???붾㈃?�?�� 諛붾�??????????�뒿??�떎.',
  },
  {
    key: 'travel-money',
    number: '2',
    title: '??�????λ?',
    description: '?꾩슂?????�� ??�???�궛??�낵 吏??湲곕�??蹂댁???붾㈃?�?�� ?�?�ы�???�떎.',
  },
  {
    key: 'travel-log',
    number: '3',
    title: '??�?濡쒓??,
    description: '??�?湲곕�? 寃쎈�? ??�? GPX ???��??????�겕??�럹??�뒪?�?�� ?뺣━??�땲??',
  },
  {
    key: 'photo-album',
    number: '4',
    title: '??�???�?,
    description: '湲곕�???곌껐????�???쭊�??�붿�???꾩슜 媛ㅻ??��??붾㈃?�?�� �?????�뒿??�떎.',
  },
  {
    key: 'family-album',
    number: '5',
    title: '媛�???�쾾',
    description: '?�덈???媛�??�ъ꽦?�?�� 移댄?�怨좊?�蹂???쭊�??곸긽???�듭?�??�땲??',
  },
  {
    key: 'my-map',
    number: '6',
    title: '??吏??,
    description: '吏湲덇?�吏? ???ν�???�??μ????????��?寃쎈줈瑜????μ??吏?꾩뿉???뺤씤??�땲??',
  },
  {
    key: 'drive',
    number: '4',
    title: 'CalenDrive',
    description: '???�????�쨮??? ??�벊??, ????? ??�?�? ?袁⑥�?????�젟繹먮?? ??�됱�??? ??뺤뵬??????�꿸?????袁⑹????곌쾿???�읂??곷뮞???????몃빍??',
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
    title: '媛?�꾨?',
    description: '媛?�꾨?, ???�? 寃???�� ?�꾨�??�?�ш퉴吏 ???붾㈃?�?�� 諛붾�??????????�뒿??�떎.',
  },
  {
    key: 'travel',
    number: '2',
    title: '??�?,
    description: '吏??湲곕�? ?μ??諛⑸Ц, GPX 寃쎈�?? ??�???�??????�겕??�럹??�뒪?�?�� ??�뼱???�?�ы�???�떎.',
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
  title: '?�?�ъ옄',
  description: '濡쒓???濡쒓?? 李⑤??IP, ??????곹깭, ?�덈? ?꾪솴???�?�ы�???�떎.',
}

const THEME_STORAGE_KEY = 'calen-theme-mode'
const THEME_DEGREE_STORAGE_KEY = 'calen-theme-degree'
const LAYOUT_MODE_STORAGE_KEY = 'calen-layout-mode'
const MOBILE_LAYOUT_QUERY = '(max-width: 760px)'
const DEFAULT_TOSS_DEGREE = 100
const ROUTE_LEAVE_GUARD_EVENT = 'calen-route-leave-guard'
const DEFAULT_ROUTE_LEAVE_GUARD_MESSAGE = '??�씠吏??踰쀬뼱??�㈃ ??�떆 泥섏?�遺?????�줈????�빞??�땲??'

const routeMeta = {
  launcher: {
    title: '湲곕???좏깮',
    description: '??�쓬??�줈 ??��???? 湲곕???곸뿭???좏깮??�꽭??',
  },
  household: {
    title: '媛?�꾨?',
    description: '????媛?�꾨?, ???�? 寃?? ?�꾨�??�??湲곕?????�퍡 ?뺤씤??�땲??',
  },
  travel: {
    title: '??�?,
    description: '吏??湲곕�? ?μ??諛⑸Ц, GPX 寃쎈�?? ??�???�????�굹????�???�겕??�럹??�뒪?�?�� ?????�땲??',
  },
  drive: {
    title: 'CalenDrive',
    description: '???�???뺤뵬????? ??�벊??, ????? ??�??, ?袁⑥�?????�젟, ??�?귐딆???�꿸???�? 4????륁뵠筌왖????????????뤿연 ?????몃빍??',
  },
  'travel-money': {
    title: '??�???�궛',
    description: '??�???�궛??�낵 ??�젣 吏?�쒖?????�녹�???�?�ы�???�떎.',
  },
  'travel-log': {
    title: '??�?濡쒓??,
    description: '??�?硫붾?? ??��?寃쎈�? ?μ?? ??�줈?????��???뺤씤??�땲??',
  },
  'photo-album': {
    title: '??�???�?,
    description: '湲곕�??湲곕�??�줈 ?�ъ꽦????�?以묒????�??붾㈃????�윭?�낅???',
  },
  'family-album': {
    title: '媛�???�쾾',
    description: '?�덈????�ъ꽦?�?�� 媛�?移댄?�怨좊?? ??�쾾, 誘몃�??�? ?�듭?�??�땲??',
  },
  'my-map': {
    title: '??吏??,
    description: '?꾩껜 ??�??????寃쎈줈瑜?吏?꾨줈 紐⑥�?蹂닿?? ?먰븯????�????�� ?�?��???뺤씤??�땲??',
  },
  admin: {
    title: '?�?�ъ옄',
    description: '濡쒓???濡쒓??? ??????곹깭, ?�덈? ?꾪솴???�???�땲??',
  },
  profile: {
    title: '???꾨줈??,
    description: '???�꾩???뺣낫?? ?�몄????�뿭, ?�?�ъ옄 ????????�녹�???뺤씤??�땲??',
  },
  invite: {
    title: '?�덈? 留곹�?媛??,
    description: '???�꾩??? 1???�� ?�덈? 留곹겕濡?�쭔 留뚮�?????�뒿??�떎.',
  },
}

const correctedFeatureItems = [
  {
    key: 'household',
    number: '1',
    title: '媛?�꾨?',
    description: '媛?�꾨?, ???�? 寃???�� ?�꾨�??�?�ш퉴吏 ???붾㈃?�?�� 諛붾�??????????�뒿??�떎.',
  },
  {
    key: 'travel',
    number: '2',
    title: '??�?,
    description: '吏??湲곕�? ?μ??諛⑸Ц, GPX 寃쎈�?? ??�???�??????�겕??�럹??�뒪?�?�� ?�?�ы�???�떎.',
  },
  {
    key: 'drive',
    number: '4',
    title: 'CalenDrive',
    description: '??�??곕뱶 ??�씪??�?? ?�듭?�, 理쒓?????��, ????? ?�?�ъ옄 湲곕?????�굹???붾㈃?�?�� ?????�땲??',
  },
]

const correctedAdminFeatureItem = {
  key: 'admin',
  number: '7',
  title: '?�?�ъ옄',
  description: '濡쒓???濡쒓?? 李⑤??IP, ??????곹깭, ?�덈? ?꾪솴???�?�ы�???�떎.',
}

const correctedRouteMeta = {
  ...routeMeta,
  launcher: {
    title: '湲곕???좏깮',
    description: '??�쓬??�줈 ??��???? 湲곕???곸뿭???좏깮??�꽭??',
  },
  household: {
    title: '媛?�꾨?',
    description: '媛?�꾨?, ???�? 寃???�� ?�꾨�??�?�ш퉴吏 ???붾㈃?�?�� 諛붾�??????????�뒿??�떎.',
  },
  travel: {
    title: '??�?,
    description: '吏??湲곕�? ?μ??諛⑸Ц, GPX 寃쎈�?? ??�???�??????�겕??�럹??�뒪?�?�� ?�?�ы�???�떎.',
  },
  drive: {
    title: 'CalenDrive',
    description: '?��? ??�씪??�??????�� ?�???�ъ“瑜?Calen ??�쑝�???�? ??�줈??�룰??졖룹?��????���?????�룰???湲곕??????�듦�?�?�� ?????�땲??',
  },
  admin: {
    title: '?�?�ъ옄',
    description: '濡쒓???濡쒓?? 李⑤??IP, ??????곹깭, ?�덈? ?꾪솴???�?�ы�???�떎.',
  },
  profile: {
    title: '???꾨줈??,
    description: '?�꾩???뺣낫?? ?�몄????�뿭, ?�?�ъ옄 ????????�녹�???뺤씤??�땲??',
  },
  invite: {
    title: '?�덈? 留곹�?媛??,
    description: '???�꾩??? 1???�� ?�덈? 留곹겕濡?�쭔 留뚮�?????�뒿??�떎.',
  },
}

const normalizedFeatureItems = [
  {
    key: 'household',
    number: '1',
    title: '媛?�꾨?',
    description: '媛?�꾨?, ???�? 寃???�� ?�꾨�??�?�ш퉴吏 ???붾㈃?�?�� 諛붾�??????????�뒿??�떎.',
  },
  {
    key: 'travel',
    number: '2',
    title: '??�?,
    description: '吏??湲곕�? ?μ??諛⑸Ц, GPX 寃쎈�?? ??�???�??????�겕??�럹??�뒪?�?�� ?�?�ы�???�떎.',
  },
  {
    key: 'drive',
    number: '4',
    title: 'CalenDrive',
    description: '???�� ??�줈?? ?�듭?�, ????? ?�?�ъ옄 ?꾧뎄????�굹????�씪??�???붾㈃?�?�� ?????�땲??',
  },
]

const normalizedAdminFeatureItem = {
  key: 'admin',
  number: '7',
  title: '?�?�ъ옄',
  description: '濡쒓???濡쒓?? 李⑤??IP, ??????곹깭, ?�덈? ?꾪솴???�?�ы�???�떎.',
}

const normalizedRouteMeta = {
  notifications: {
    title: 'Notifications',
    description: 'Review AI, OCR, backup, sharing, and operational notifications in one place.',
  },
  launcher: {
    title: '湲곕???좏깮',
    description: '??�쓬??�줈 ??��???? 湲곕???곸뿭???좏깮??�꽭??',
  },
  household: {
    title: '媛?�꾨?',
    description: '',
  },
  travel: {
    title: '??�?,
    description: '吏??湲곕�? ?μ??諛⑸Ц, GPX 寃쎈�?? ??�???�??????�겕??�럹??�뒪?�?�� ?????�땲??',
  },
  drive: {
    title: 'CalenDrive',
    description: '???�� ??�줈?? ??�???�?? ?�듭?�, ????? ?�?�ъ옄 湲곕?????�씪??�???붾㈃?�?�� ?????�땲??',
  },
  'travel-money': {
    title: '??�?媛?�꾨?',
    description: '??�???�엯쨌吏??�쒖? 媛?�꾨?????�??꾩슜 ?붾㈃?�?�� ?�?�ы븯?? 湲곗????�궛 ?붾㈃?? ?꾩슂?????�� ??�땲??',
  },
  'travel-log': {
    title: '??�?濡쒓??,
    description: '??�?湲곕�? ??��?寃쎈�? ?μ?? ??�줈?????��???뺤씤??�땲??',
  },
  'photo-album': {
    title: '??�???�?,
    description: '湲곕�?湲곕�??�줈 ?�ъ꽦????�???�?紐⑥븘蹂?�린???뺤씤??�땲??',
  },
  'family-album': {
    title: '媛�???�쾾',
    description: '媛�??�ъ꽦?�?�� ??�퍡 ?곕뒗 ??�?�??곸긽 ??�쾾???뺤씤??�땲??',
  },
  'my-map': {
    title: '??吏??,
    description: '?꾩껜 ??�??????寃쎈줈瑜?吏?꾨줈 紐⑥�?蹂닿?? ?먰븯????�????�� ?�?��???뺤씤??�땲??',
  },
  admin: {
    title: '?�?�ъ옄',
    description: '濡쒓???濡쒓?? 李⑤??IP, ??????곹깭, ?�덈? ?꾪솴???�?�ы�???�떎.',
  },
  profile: {
    title: '???꾨줈??,
    description: '?�꾩???뺣낫?? ?�몄????�뿭, ?�?�ъ옄 ???????�납?�?�� ?뺤씤??�땲??',
  },
  invite: {
    title: '?�덈? 留곹�?留뚮뱾湲?,
    description: '???�꾩??? 1???�� ?�덈? 留곹겕濡?�쭔 留뚮�?????�뒿??�떎.',
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
    { key: 'launcher', label: '硫붿?? },
    { key: 'household', label: '媛?�꾨?' },
    { key: 'travel', label: '??�? },
    { key: 'drive', label: '??�씪??�?? },
  ]
  if (currentUser.value?.admin) {
    items.push({ key: 'admin', label: '?�?�ъ옄' })
  }
  return items
})
const themeDegreeDisplay = computed(() => `${themeDegree.value}%`)
const layoutModeOptions = [
  { value: 'mobile', label: '紐⑤�?? },
  { value: 'desktop', label: '?곗뒪??�? },
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
    setFeedback('', '?�덈? 留곹겕媛? ??�?��?? ??�뒿??�떎.')
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
    setFeedback('濡쒓??몃릺??�뒿??�떎.')
  } catch (error) {
    setFeedback('', error.message)
  } finally {
    isSubmitting.value = false
    activeSubmit.value = ''
  }
}

async function handleAcceptInvite() {
  if (!inviteInfo.value || !inviteToken.value) {
    setFeedback('', '?좏슚???�덈? 留곹겕瑜??�쇱? ??�뼱二쇱�??')
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
    setFeedback('?�덈? 留곹겕濡??�꾩???留뚮뱾�?諛붾�?濡쒓??명뻽??�땲??')
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
  setFeedback('濡쒓??꾩썐??�뒿??�떎.')
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
        <div class="layout-mode-toggle" role="group" aria-label="蹂닿�???�꼍 ?꾪솚">
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
          {{ isTossTheme ? '湲곕?????��' : '?좎뒪 ???��' }}
        </button>
        <button
          v-if="isTossTheme"
          class="theme-toggle theme-toggle--degree"
          type="button"
          @click.stop="toggleThemeDegreePanel"
        >
          ??�겕 degree {{ themeDegreeDisplay }}
        </button>
      </div>

      <div v-if="isTossTheme && themeDegreePanelOpen" class="theme-degree-panel">
        <div class="theme-degree-panel__header">
          <strong>?λ???媛뺣�?</strong>
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
          <span>??�컲 ??�겕</span>
          <span>?λ???</span>
        </div>
      </div>
    </div>

    <button v-if="false" class="theme-toggle" type="button" @click="toggleTheme">
      {{ isTossTheme ? '湲곕?????��' : '?좎뒪 ???��' }}
    </button>

    <div v-if="!authChecked" class="loading-overlay">?몄�???뺤씤??�뒗 以묒???�떎...</div>

    <template v-else-if="activeRoute === 'invite'">
      <section class="auth-shell">
        <div class="auth-copy">
          <span class="auth-copy__badge">?�덈? 留곹�?媛??</span>
          <h1>???�꾩??? 1???�� ?�덈? 留곹겕濡?�쭔 留뚮�?????�뒿??�떎.</h1>
          <p>留곹겕媛? ?좏슚??�㈃ 濡쒓???ID, ??�떆 ??��? ??��?踰덊?�瑜???�젰???�꾩???留뚮뱾�?諛붾�?濡쒓??명븷 ????�뒿??�떎.</p>
          <p v-if="currentUser" class="auth-copy__hint">
            ?꾩옱 {{ currentUser.displayName }} ({{ currentUser.loginId }}) ?�꾩???�줈 濡쒓???以묒???�떎. 媛??�씠 ??�굹�????�뚮??�??????�꾩???�줈 ?꾪솚??�땲??
          </p>
        </div>

        <div class="auth-grid">
          <article class="auth-card">
            <h2>?�덈? ?곹깭</h2>
            <div class="stack-form stack-form--readonly">
              <p v-if="isInviteLoading">?�덈? 留곹겕瑜??뺤씤??�뒗 以묒???�떎...</p>
              <template v-else-if="inviteInfo">
                <p><strong>{{ inviteInfo.inviterDisplayName }}</strong> ??�씠 留뚮�??�덈? 留곹�??�땲??</p>
                <p>留뚮�???�컙: {{ formatDateTime(inviteInfo.expiresAt) }}</p>
              </template>
              <p v-else>??留곹겕濡?�뒗 ?�꾩???留뚮�?????�뒿??�떎.</p>
            </div>
          </article>

          <article class="auth-card">
            <h2>?�덈? ?�꾩??留뚮뱾湲?</h2>
            <form class="stack-form" @submit.prevent="handleAcceptInvite">
              <input
                v-model="inviteForm.loginId"
                type="text"
                placeholder="濡쒓???ID"
                autocomplete="username"
                :disabled="isSubmitting || isInviteLoading || !inviteInfo"
              />
              <input
                v-model="inviteForm.displayName"
                type="text"
                placeholder="??�떆 ??��?
                autocomplete="name"
                :disabled="isSubmitting || isInviteLoading || !inviteInfo"
              />
              <input
                v-model="inviteForm.password"
                type="password"
                placeholder="??��?踰덊??(8????�긽)"
                autocomplete="new-password"
                :disabled="isSubmitting || isInviteLoading || !inviteInfo"
              />
              <PinPadInput
                v-model="inviteForm.secondaryPin"
                label="2�???��?踰덊??
                hint="媛????濡쒓??명븷 ???�� 媛숈? ??�옄 8?�?��??留덉???�줈 ???�� ??�젰??�땲??"
                :disabled="isSubmitting || isInviteLoading || !inviteInfo"
              />
              <label class="checkbox-row">
                <input
                  v-model="inviteForm.rememberDevice"
                  type="checkbox"
                  :disabled="isSubmitting || isInviteLoading || !inviteInfo"
                />
                <span>???�뚮??�??�?�� 濡쒓????곹깭 ?�?</span>
              </label>
              <button class="button button--primary" type="submit" :disabled="isSubmitting || isInviteLoading || !inviteInfo">
                {{ isSubmitting && activeSubmit === 'invite' ? '?�꾩????�꽦 �?..' : '?�꾩??留뚮뱾�?濡쒓??? }}
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
            <h2>濡쒓???</h2>
            <form class="stack-form" @submit.prevent="handleLogin">
              <input v-model="loginForm.loginId" type="text" placeholder="濡쒓???ID" autocomplete="username" />
              <input v-model="loginForm.password" type="password" placeholder="??��?踰덊?? autocomplete="current-password" />
              <PinPadInput
                v-model="loginForm.secondaryPin"
                label="2�???��?踰덊??
                hint="??�낫????????�옄 踰꾪??????�� 8?�?��????�젰??�＜?몄슂."
                :disabled="isSubmitting"
              />
              <label class="checkbox-row">
                <input v-model="loginForm.rememberDevice" type="checkbox" />
                <span>???�뚮??�??�?�� 濡쒓????곹깭 ?�?</span>
              </label>
              <button class="button button--primary" type="submit" :disabled="isSubmitting">
                {{ isSubmitting && activeSubmit === 'login' ? '濡쒓???�?..' : '濡쒓??? }}
              </button>
            </form>
          </article>

          <article class="auth-card">
            <h2>?�꾩????�궡</h2>
            <div class="stack-form stack-form--readonly">
              <p>?�듦�????��媛??? ?�쇱�???�뒿??�떎.</p>
              <p>???�꾩????꾩슂??�㈃ 湲곗??????�?�� ?�?�ъ옄?�?�� 1???�� ?�덈? 留곹�???�꽦???붿껌??�＜?몄슂.</p>
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
            <h1>{{ pageMeta.description }}</h1>
          </div>
          <nav class="topbar__nav" aria-label="二쇱??湲곕??>
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
            <span>Notifications</span>
            <span v-if="notificationUnreadCount" class="topbar__notification-badge" aria-label="Unread notifications" aria-live="polite">{{ notificationUnreadBadgeLabel }}</span>
          </button></nav>
          <div class="topbar__actions">
            <button v-if="activeRoute !== 'profile'" class="button button--ghost" @click="navigate('profile')">???꾨줈??</button>
            <button class="button button--ghost" @click="handleLogout">濡쒓??꾩썐</button>
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
