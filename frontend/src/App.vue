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
    title: '媛怨꾨?',
    description: '媛怨꾨?, ?듦퀎, 寃?? 遺꾨쪟 愿由ш퉴吏 ???붾㈃?먯꽌 諛붾줈 ?ъ슜?????덉뒿?덈떎.',
  },
  {
    key: 'travel-money',
    number: '2',
    title: '?ы뻾 ???λ?',
    description: '?꾩슂???뚮쭔 ?ы뻾 ?덉궛?덇낵 吏異?湲곕줉??蹂댁“ ?붾㈃?먯꽌 愿由ы빀?덈떎.',
  },
  {
    key: 'travel-log',
    number: '3',
    title: '?ы뻾 濡쒓렇',
    description: '?ы뻾 湲곕줉, 寃쎈줈, ?ъ쭊, GPX ?뚯씪?????뚰겕?ㅽ럹?댁뒪?먯꽌 ?뺣━?⑸땲??',
  },
  {
    key: 'photo-album',
    number: '4',
    title: '?ы뻾 ?ъ쭊',
    description: '湲곕줉???곌껐???ы뻾 ?ъ쭊怨?異붿뼲???꾩슜 媛ㅻ윭由??붾㈃?먯꽌 蹂????덉뒿?덈떎.',
  },
  {
    key: 'family-album',
    number: '5',
    title: '媛議??⑤쾾',
    description: '珥덈???媛議?援ъ꽦?먭낵 移댄뀒怨좊━蹂??ъ쭊怨??곸긽??怨듭쑀?⑸땲??',
  },
  {
    key: 'my-map',
    number: '6',
    title: '??吏??,
    description: '吏湲덇퉴吏 ??ν븳 ?ы뻾 ?μ냼 ?怨??대룞 寃쎈줈瑜????μ쓽 吏?꾩뿉???뺤씤?⑸땲??',
  },
  {
    key: 'drive',
    number: '4',
    title: 'CalenDrive',
    description: '???뵬 ??낆쨮??? ?⑤벊?, ????? ?온?귐? ?袁⑥쨮????쇱젟繹먮슣? ?뚎됱뵬?怨? ??뺤뵬????疫꿸퀡????袁⑹뒠 ??곌쾿??쎈읂??곷뮞嚥??????몃빍??',
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
    title: '媛怨꾨?',
    description: '媛怨꾨?, ?듦퀎, 寃?됯낵 遺꾨쪟 愿由ш퉴吏 ???붾㈃?먯꽌 諛붾줈 ?ъ슜?????덉뒿?덈떎.',
  },
  {
    key: 'travel',
    number: '2',
    title: '?ы뻾',
    description: '吏??湲곕줉, ?μ냼 諛⑸Ц, GPX 寃쎈줈? ?ы뻾 ?ъ쭊?????뚰겕?ㅽ럹?댁뒪?먯꽌 ?댁뼱??愿由ы빀?덈떎.',
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
  title: '愿由ъ옄',
  description: '濡쒓렇??濡쒓렇, 李⑤떒 IP, ?ъ슜???곹깭, 珥덈? ?꾪솴??愿由ы빀?덈떎.',
}

const THEME_STORAGE_KEY = 'calen-theme-mode'
const THEME_DEGREE_STORAGE_KEY = 'calen-theme-degree'
const LAYOUT_MODE_STORAGE_KEY = 'calen-layout-mode'
const MOBILE_LAYOUT_QUERY = '(max-width: 760px)'
const DEFAULT_TOSS_DEGREE = 100
const ROUTE_LEAVE_GUARD_EVENT = 'calen-route-leave-guard'
const DEFAULT_ROUTE_LEAVE_GUARD_MESSAGE = '?섏씠吏瑜?踰쀬뼱?섎㈃ ?ㅼ떆 泥섏쓬遺???낅줈???댁빞?⑸땲??'

const routeMeta = {
  launcher: {
    title: '湲곕뒫 ?좏깮',
    description: '?ㅼ쓬?쇰줈 ?닿퀬 ?띠? 湲곕뒫 ?곸뿭???좏깮?섏꽭??',
  },
  household: {
    title: '媛怨꾨?',
    description: '?щ젰 媛怨꾨?, ?듦퀎, 寃?? 遺꾨쪟 愿由?湲곕뒫???④퍡 ?뺤씤?⑸땲??',
  },
  travel: {
    title: '?ы뻾',
    description: '吏??湲곕줉, ?μ냼 諛⑸Ц, GPX 寃쎈줈? ?ы뻾 ?ъ쭊???섎굹???ы뻾 ?뚰겕?ㅽ럹?댁뒪?먯꽌 ?ъ슜?⑸땲??',
  },
  drive: {
    title: 'CalenDrive',
    description: '???뵬 ??뺤뵬???? ?⑤벊?, ????? ?온??, ?袁⑥쨮????쇱젟, ?온?귐딆쁽 疫꿸퀡??袁? 4甕???륁뵠筌왖?癒?퐣 ??????뤿연 ?????몃빍??',
  },
  'travel-money': {
    title: '?ы뻾 ?덉궛',
    description: '?ы뻾 ?덉궛?덇낵 ?ㅼ젣 吏異쒖쓣 ??怨녹뿉??愿由ы빀?덈떎.',
  },
  'travel-log': {
    title: '?ы뻾 濡쒓렇',
    description: '?ы뻾 硫붾え, ?대룞 寃쎈줈, ?μ냼, ?낅줈???뚯씪???뺤씤?⑸땲??',
  },
  'photo-album': {
    title: '?ы뻾 ?ъ쭊',
    description: '湲곕줉??湲곕컲?쇰줈 援ъ꽦???ъ쭊 以묒떖 ?ы뻾 ?붾㈃???섎윭遊낅땲??',
  },
  'family-album': {
    title: '媛議??⑤쾾',
    description: '珥덈???援ъ꽦?먭낵 媛議?移댄뀒怨좊━, ?⑤쾾, 誘몃뵒?대? 怨듭쑀?⑸땲??',
  },
  'my-map': {
    title: '??吏??,
    description: '?꾩껜 ?ы뻾???怨?寃쎈줈瑜?吏?꾨줈 紐⑥븘 蹂닿퀬, ?먰븯???留??뚮윭 ?먯꽭???뺤씤?⑸땲??',
  },
  admin: {
    title: '愿由ъ옄',
    description: '濡쒓렇??濡쒓렇? ?ъ슜???곹깭, 珥덈? ?꾪솴???먭??⑸땲??',
  },
  profile: {
    title: '???꾨줈??,
    description: '??怨꾩젙 ?뺣낫? 臾몄쓽 ?댁뿭, 愿由ъ옄 ?듬?????怨녹뿉???뺤씤?⑸땲??',
  },
  invite: {
    title: '珥덈? 留곹겕 媛??,
    description: '??怨꾩젙? 1?뚯슜 珥덈? 留곹겕濡쒕쭔 留뚮뱾 ???덉뒿?덈떎.',
  },
}

const correctedFeatureItems = [
  {
    key: 'household',
    number: '1',
    title: '媛怨꾨?',
    description: '媛怨꾨?, ?듦퀎, 寃?됯낵 遺꾨쪟 愿由ш퉴吏 ???붾㈃?먯꽌 諛붾줈 ?ъ슜?????덉뒿?덈떎.',
  },
  {
    key: 'travel',
    number: '2',
    title: '?ы뻾',
    description: '吏??湲곕줉, ?μ냼 諛⑸Ц, GPX 寃쎈줈? ?ы뻾 ?ъ쭊?????뚰겕?ㅽ럹?댁뒪?먯꽌 愿由ы빀?덈떎.',
  },
  {
    key: 'drive',
    number: '4',
    title: 'CalenDrive',
    description: '?대씪?곕뱶 ?쒕씪?대툕, 怨듭쑀, 理쒓렐 ?뚯씪, ?댁??? 愿由ъ옄 湲곕뒫???섎굹???붾㈃?먯꽌 ?ъ슜?⑸땲??',
  },
]

const correctedAdminFeatureItem = {
  key: 'admin',
  number: '7',
  title: '愿由ъ옄',
  description: '濡쒓렇??濡쒓렇, 李⑤떒 IP, ?ъ슜???곹깭, 珥덈? ?꾪솴??愿由ы빀?덈떎.',
}

const correctedRouteMeta = {
  ...routeMeta,
  launcher: {
    title: '湲곕뒫 ?좏깮',
    description: '?ㅼ쓬?쇰줈 ?닿퀬 ?띠? 湲곕뒫 ?곸뿭???좏깮?섏꽭??',
  },
  household: {
    title: '媛怨꾨?',
    description: '媛怨꾨?, ?듦퀎, 寃?됯낵 遺꾨쪟 愿由ш퉴吏 ???붾㈃?먯꽌 諛붾줈 ?ъ슜?????덉뒿?덈떎.',
  },
  travel: {
    title: '?ы뻾',
    description: '吏??湲곕줉, ?μ냼 諛⑸Ц, GPX 寃쎈줈? ?ы뻾 ?ъ쭊?????뚰겕?ㅽ럹?댁뒪?먯꽌 愿由ы빀?덈떎.',
  },
  drive: {
    title: 'CalenDrive',
    description: '援ш? ?쒕씪?대툕???뚯씪 愿由?援ъ“瑜?Calen ?덉쑝濡???꺼, ?낅줈?쑣룰났?졖룹턀洹??뚯씪쨌?댁??돠룰?由?湲곕뒫????怨듦컙?먯꽌 ?ъ슜?⑸땲??',
  },
  admin: {
    title: '愿由ъ옄',
    description: '濡쒓렇??濡쒓렇, 李⑤떒 IP, ?ъ슜???곹깭, 珥덈? ?꾪솴??愿由ы빀?덈떎.',
  },
  profile: {
    title: '???꾨줈??,
    description: '怨꾩젙 ?뺣낫? 臾몄쓽 ?댁뿭, 愿由ъ옄 ?듬?????怨녹뿉???뺤씤?⑸땲??',
  },
  invite: {
    title: '珥덈? 留곹겕 媛??,
    description: '??怨꾩젙? 1?뚯슜 珥덈? 留곹겕濡쒕쭔 留뚮뱾 ???덉뒿?덈떎.',
  },
}

const normalizedFeatureItems = [
  {
    key: 'household',
    number: '1',
    title: '媛怨꾨?',
    description: '媛怨꾨?, ?듦퀎, 寃?됯낵 遺꾨쪟 愿由ш퉴吏 ???붾㈃?먯꽌 諛붾줈 ?ъ슜?????덉뒿?덈떎.',
  },
  {
    key: 'travel',
    number: '2',
    title: '?ы뻾',
    description: '吏??湲곕줉, ?μ냼 諛⑸Ц, GPX 寃쎈줈? ?ы뻾 ?ъ쭊?????뚰겕?ㅽ럹?댁뒪?먯꽌 愿由ы빀?덈떎.',
  },
  {
    key: 'drive',
    number: '4',
    title: 'CalenDrive',
    description: '?뚯씪 ?낅줈?? 怨듭쑀, ?댁??? 愿由ъ옄 ?꾧뎄瑜??섎굹???쒕씪?대툕 ?붾㈃?먯꽌 ?ъ슜?⑸땲??',
  },
]

const normalizedAdminFeatureItem = {
  key: 'admin',
  number: '7',
  title: '愿由ъ옄',
  description: '濡쒓렇??濡쒓렇, 李⑤떒 IP, ?ъ슜???곹깭, 珥덈? ?꾪솴??愿由ы빀?덈떎.',
}

const normalizedRouteMeta = {
  notifications: {
    title: 'Notifications',
    description: 'Review AI, OCR, backup, sharing, and operational notifications in one place.',
  },
  launcher: {
    title: '湲곕뒫 ?좏깮',
    description: '?ㅼ쓬?쇰줈 ?닿퀬 ?띠? 湲곕뒫 ?곸뿭???좏깮?섏꽭??',
  },
  household: {
    title: '媛怨꾨?',
    description: '',
  },
  travel: {
    title: '?ы뻾',
    description: '吏??湲곕줉, ?μ냼 諛⑸Ц, GPX 寃쎈줈? ?ы뻾 ?ъ쭊?????뚰겕?ㅽ럹?댁뒪?먯꽌 ?ъ슜?⑸땲??',
  },
  drive: {
    title: 'CalenDrive',
    description: '?뚯씪 ?낅줈?? ?대뜑 愿由? 怨듭쑀, ?댁??? 愿由ъ옄 湲곕뒫???쒕씪?대툕 ?붾㈃?먯꽌 ?ъ슜?⑸땲??',
  },
  'travel-money': {
    title: '?ы뻾 媛怨꾨?',
    description: '?ы뻾 ?섏엯쨌吏異쒖? 媛怨꾨????ы뻾 ?꾩슜 ?붾㈃?먯꽌 愿由ы븯怨? 湲곗〈 ?덉궛 ?붾㈃? ?꾩슂???뚮쭔 ?쎈땲??',
  },
  'travel-log': {
    title: '?ы뻾 濡쒓렇',
    description: '?ы뻾 湲곕줉, ?대룞 寃쎈줈, ?μ냼, ?낅줈???뚯씪???뺤씤?⑸땲??',
  },
  'photo-album': {
    title: '?ы뻾 ?ъ쭊',
    description: '湲곕줉 湲곕컲?쇰줈 援ъ꽦???ы뻾 ?ъ쭊 紐⑥븘蹂닿린瑜??뺤씤?⑸땲??',
  },
  'family-album': {
    title: '媛議??⑤쾾',
    description: '媛議?援ъ꽦?먭낵 ?④퍡 ?곕뒗 ?ъ쭊 諛??곸긽 ?⑤쾾???뺤씤?⑸땲??',
  },
  'my-map': {
    title: '??吏??,
    description: '?꾩껜 ?ы뻾???怨?寃쎈줈瑜?吏?꾨줈 紐⑥븘 蹂닿퀬, ?먰븯???留??뚮윭 ?먯꽭???뺤씤?⑸땲??',
  },
  admin: {
    title: '愿由ъ옄',
    description: '濡쒓렇??濡쒓렇, 李⑤떒 IP, ?ъ슜???곹깭, 珥덈? ?꾪솴??愿由ы빀?덈떎.',
  },
  profile: {
    title: '???꾨줈??,
    description: '怨꾩젙 ?뺣낫? 臾몄쓽 ?댁뿭, 愿由ъ옄 ?щ?瑜??쒓납?먯꽌 ?뺤씤?⑸땲??',
  },
  invite: {
    title: '珥덈? 留곹겕 留뚮뱾湲?,
    description: '??怨꾩젙? 1?뚯슜 珥덈? 留곹겕濡쒕쭔 留뚮뱾 ???덉뒿?덈떎.',
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
    { key: 'launcher', label: '硫붿씤' },
    { key: 'household', label: '媛怨꾨?' },
    { key: 'travel', label: '?ы뻾' },
    { key: 'drive', label: '?쒕씪?대툕' },
  ]
  if (currentUser.value?.admin) {
    items.push({ key: 'admin', label: '愿由ъ옄' })
  }
  return items
})
const themeDegreeDisplay = computed(() => `${themeDegree.value}%`)
const layoutModeOptions = [
  { value: 'mobile', label: '紐⑤컮?? },
  { value: 'desktop', label: '?곗뒪?ы깙' },
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
    setFeedback('', '珥덈? 留곹겕媛 ?щ컮瑜댁? ?딆뒿?덈떎.')
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
    setFeedback('濡쒓렇?몃릺?덉뒿?덈떎.')
  } catch (error) {
    setFeedback('', error.message)
  } finally {
    isSubmitting.value = false
    activeSubmit.value = ''
  }
}

async function handleAcceptInvite() {
  if (!inviteInfo.value || !inviteToken.value) {
    setFeedback('', '?좏슚??珥덈? 留곹겕瑜?癒쇱? ?댁뼱二쇱꽭??')
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
    setFeedback('珥덈? 留곹겕濡?怨꾩젙??留뚮뱾怨?諛붾줈 濡쒓렇?명뻽?듬땲??')
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
  setFeedback('濡쒓렇?꾩썐?덉뒿?덈떎.')
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
        <div class="layout-mode-toggle" role="group" aria-label="蹂닿린 ?섍꼍 ?꾪솚">
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
          {{ isTossTheme ? '湲곕낯 ?뚮쭏' : '?좎뒪 ?뚮쭏' }}
        </button>
        <button
          v-if="isTossTheme"
          class="theme-toggle theme-toggle--degree"
          type="button"
          @click.stop="toggleThemeDegreePanel"
        >
          ?ㅽ겕 degree {{ themeDegreeDisplay }}
        </button>
      </div>

      <div v-if="isTossTheme && themeDegreePanelOpen" class="theme-degree-panel">
        <div class="theme-degree-panel__header">
          <strong>?λ떎??媛뺣룄</strong>
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
          <span>?쇰컲 ?ㅽ겕</span>
          <span>?λ떎??/span>
        </div>
      </div>
    </div>

    <button v-if="false" class="theme-toggle" type="button" @click="toggleTheme">
      {{ isTossTheme ? '湲곕낯 ?뚮쭏' : '?좎뒪 ?뚮쭏' }}
    </button>

    <div v-if="!authChecked" class="loading-overlay">?몄뀡???뺤씤?섎뒗 以묒엯?덈떎...</div>

    <template v-else-if="activeRoute === 'invite'">
      <section class="auth-shell">
        <div class="auth-copy">
          <span class="auth-copy__badge">珥덈? 留곹겕 媛??/span>
          <h1>??怨꾩젙? 1?뚯슜 珥덈? 留곹겕濡쒕쭔 留뚮뱾 ???덉뒿?덈떎.</h1>
          <p>留곹겕媛 ?좏슚?섎㈃ 濡쒓렇??ID, ?쒖떆 ?대쫫, 鍮꾨?踰덊샇瑜??낅젰??怨꾩젙??留뚮뱾怨?諛붾줈 濡쒓렇?명븷 ???덉뒿?덈떎.</p>
          <p v-if="currentUser" class="auth-copy__hint">
            ?꾩옱 {{ currentUser.displayName }} ({{ currentUser.loginId }}) 怨꾩젙?쇰줈 濡쒓렇??以묒엯?덈떎. 媛?낆씠 ?앸굹硫???釉뚮씪?곗?????怨꾩젙?쇰줈 ?꾪솚?⑸땲??
          </p>
        </div>

        <div class="auth-grid">
          <article class="auth-card">
            <h2>珥덈? ?곹깭</h2>
            <div class="stack-form stack-form--readonly">
              <p v-if="isInviteLoading">珥덈? 留곹겕瑜??뺤씤?섎뒗 以묒엯?덈떎...</p>
              <template v-else-if="inviteInfo">
                <p><strong>{{ inviteInfo.inviterDisplayName }}</strong> ?섏씠 留뚮뱺 珥덈? 留곹겕?낅땲??</p>
                <p>留뚮즺 ?쒓컙: {{ formatDateTime(inviteInfo.expiresAt) }}</p>
              </template>
              <p v-else>??留곹겕濡쒕뒗 怨꾩젙??留뚮뱾 ???놁뒿?덈떎.</p>
            </div>
          </article>

          <article class="auth-card">
            <h2>珥덈? 怨꾩젙 留뚮뱾湲?/h2>
            <form class="stack-form" @submit.prevent="handleAcceptInvite">
              <input
                v-model="inviteForm.loginId"
                type="text"
                placeholder="濡쒓렇??ID"
                autocomplete="username"
                :disabled="isSubmitting || isInviteLoading || !inviteInfo"
              />
              <input
                v-model="inviteForm.displayName"
                type="text"
                placeholder="?쒖떆 ?대쫫"
                autocomplete="name"
                :disabled="isSubmitting || isInviteLoading || !inviteInfo"
              />
              <input
                v-model="inviteForm.password"
                type="password"
                placeholder="鍮꾨?踰덊샇 (8???댁긽)"
                autocomplete="new-password"
                :disabled="isSubmitting || isInviteLoading || !inviteInfo"
              />
              <PinPadInput
                v-model="inviteForm.secondaryPin"
                label="2李?鍮꾨?踰덊샇"
                hint="媛????濡쒓렇?명븷 ?뚮룄 媛숈? ?レ옄 8?먮━瑜?留덉슦?ㅻ줈 ?뚮윭 ?낅젰?⑸땲??"
                :disabled="isSubmitting || isInviteLoading || !inviteInfo"
              />
              <label class="checkbox-row">
                <input
                  v-model="inviteForm.rememberDevice"
                  type="checkbox"
                  :disabled="isSubmitting || isInviteLoading || !inviteInfo"
                />
                <span>??釉뚮씪?곗??먯꽌 濡쒓렇???곹깭 ?좎?</span>
              </label>
              <button class="button button--primary" type="submit" :disabled="isSubmitting || isInviteLoading || !inviteInfo">
                {{ isSubmitting && activeSubmit === 'invite' ? '怨꾩젙 ?앹꽦 以?..' : '怨꾩젙 留뚮뱾怨?濡쒓렇?? }}
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
            <h2>濡쒓렇??/h2>
            <form class="stack-form" @submit.prevent="handleLogin">
              <input v-model="loginForm.loginId" type="text" placeholder="濡쒓렇??ID" autocomplete="username" />
              <input v-model="loginForm.password" type="password" placeholder="鍮꾨?踰덊샇" autocomplete="current-password" />
              <PinPadInput
                v-model="loginForm.secondaryPin"
                label="2李?鍮꾨?踰덊샇"
                hint="?ㅻ낫??????レ옄 踰꾪듉???뚮윭 8?먮━瑜??낅젰?댁＜?몄슂."
                :disabled="isSubmitting"
              />
              <label class="checkbox-row">
                <input v-model="loginForm.rememberDevice" type="checkbox" />
                <span>??釉뚮씪?곗??먯꽌 濡쒓렇???곹깭 ?좎?</span>
              </label>
              <button class="button button--primary" type="submit" :disabled="isSubmitting">
                {{ isSubmitting && activeSubmit === 'login' ? '濡쒓렇??以?..' : '濡쒓렇?? }}
              </button>
            </form>
          </article>

          <article class="auth-card">
            <h2>怨꾩젙 ?덈궡</h2>
            <div class="stack-form stack-form--readonly">
              <p>怨듦컻 ?뚯썝媛?낆? 爰쇱졇 ?덉뒿?덈떎.</p>
              <p>??怨꾩젙???꾩슂?섎㈃ 湲곗〈 ?ъ슜?먮굹 愿由ъ옄?먭쾶 1?뚯슜 珥덈? 留곹겕 ?앹꽦???붿껌?댁＜?몄슂.</p>
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
          <nav class="topbar__nav" aria-label="二쇱슂 湲곕뒫">
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
            :class="['topbar__nav-button', { 'topbar__nav-button--active': activeRoute === 'notifications' }]"
            @click="navigate('notifications')"
          >
            Notifications
          </button></nav>
          <div class="topbar__actions">
            <button v-if="activeRoute !== 'profile'" class="button button--ghost" @click="navigate('profile')">???꾨줈??/button>
            <button class="button button--ghost" @click="handleLogout">濡쒓렇?꾩썐</button>
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
