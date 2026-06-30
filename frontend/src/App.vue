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
    title: 'åª›Â€?¨ê¾¨?',
    description: 'åª›Â€?¨ê¾¨?, ???€? å¯ƒÂ€?? ?ºê¾¨ìª??¿Â€?±Ñˆí‰´ï§Â€ ???ë¶¾ãˆƒ?ë¨?½Œ è«›ë¶¾ì¤??????????‰ë’¿??ˆë–.',
  },
  {
    key: 'travel-money',
    number: '2',
    title: '??ë»????Î»?',
    description: '?ê¾©ìŠ‚?????­” ??ë»???‰ê¶›??‡ë‚µ ï§Â€??æ¹²ê³•ì¤??è¹‚ëŒ???ë¶¾ãˆƒ?ë¨?½Œ ?¿Â€?±Ñ‹ë???ˆë–.',
  },
  {
    key: 'travel-log',
    number: '3',
    title: '??ë»?æ¿¡ì’“??,
    description: '??ë»?æ¹²ê³•ì¤? å¯ƒìˆì¤? ??ì­? GPX ???”ª??????°ê²•??½ëŸ¹??ë’ª?ë¨?½Œ ?ëº£â”??¸ë•²??',
  },
  {
    key: 'photo-album',
    number: '4',
    title: '??ë»???ì­?,
    description: 'æ¹²ê³•ì¤???ê³Œê»????ë»???ì­Šæ€??°ë¶¿ë¼???ê¾©ìŠœ åª›ã…»??”±??ë¶¾ãˆƒ?ë¨?½Œ è¹?????‰ë’¿??ˆë–.',
  },
  {
    key: 'family-album',
    number: '5',
    title: 'åª›Â€è­???¤ì¾¾',
    description: '?¥ëˆ???åª›Â€è­??´ÑŠê½¦?ë¨?‚µ ç§»ëŒ„?’æ€¨ì¢Š?è¹‚???ì­Šæ€??ê³¸ê¸½???¨ë“­?€??¸ë•²??',
  },
  {
    key: 'my-map',
    number: '6',
    title: '??ï§Â€??,
    description: 'ï§Â€æ¹²ë‡?´ï§? ???Î½ë¸???ë»??Î¼????????€ë£?å¯ƒìˆì¤ˆç‘œ????Î¼??ï§Â€?ê¾©ë¿‰???ëº¤ì”¤??¸ë•²??',
  },
  {
    key: 'drive',
    number: '4',
    title: 'CalenDrive',
    description: '???ëµ????†ì¨®??? ??¤ë²Š??, ????? ??¨Â€?ê·? ?è¢â‘¥ì¨?????±ì Ÿç¹¹ë¨®?? ??ë±ëµ??? ??ëº¤ëµ¬??????«ê¿¸?????è¢â‘¹????ê³Œì¾¿???ˆì‚??ê³·ë®???????ëªƒë¹??',
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
    title: 'åª›Â€?¨ê¾¨?',
    description: 'åª›Â€?¨ê¾¨?, ???€? å¯ƒÂ€???‚µ ?ºê¾¨ìª??¿Â€?±Ñˆí‰´ï§Â€ ???ë¶¾ãˆƒ?ë¨?½Œ è«›ë¶¾ì¤??????????‰ë’¿??ˆë–.',
  },
  {
    key: 'travel',
    number: '2',
    title: '??ë»?,
    description: 'ï§Â€??æ¹²ê³•ì¤? ?Î¼??è«›â‘¸Ğ¦, GPX å¯ƒìˆì¤?? ??ë»???ì­??????°ê²•??½ëŸ¹??ë’ª?ë¨?½Œ ??ë¼±???¿Â€?±Ñ‹ë???ˆë–.',
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
  title: '?¿Â€?±ÑŠì˜„',
  description: 'æ¿¡ì’“???æ¿¡ì’“?? ï§¡â‘¤??IP, ??????ê³¹ê¹­, ?¥ëˆ? ?ê¾ªì†´???¿Â€?±Ñ‹ë???ˆë–.',
}

const THEME_STORAGE_KEY = 'calen-theme-mode'
const THEME_DEGREE_STORAGE_KEY = 'calen-theme-degree'
const LAYOUT_MODE_STORAGE_KEY = 'calen-layout-mode'
const MOBILE_LAYOUT_QUERY = '(max-width: 760px)'
const DEFAULT_TOSS_DEGREE = 100
const ROUTE_LEAVE_GUARD_EVENT = 'calen-route-leave-guard'
const DEFAULT_ROUTE_LEAVE_GUARD_MESSAGE = '??ì” ï§Â€??è¸°ì€¬ë¼±??ãˆƒ ??¼ë–† ï§£ì„?¬éº?????…ì¤ˆ????ë¹??¸ë•²??'

const routeMeta = {
  launcher: {
    title: 'æ¹²ê³•???ì¢ê¹®',
    description: '??¼ì“¬??°ì¤ˆ ??¿í€???? æ¹²ê³•???ê³¸ë¿­???ì¢ê¹®??ê½­??',
  },
  household: {
    title: 'åª›Â€?¨ê¾¨?',
    description: '????åª›Â€?¨ê¾¨?, ???€? å¯ƒÂ€?? ?ºê¾¨ìª??¿Â€??æ¹²ê³•?????£í¡ ?ëº¤ì”¤??¸ë•²??',
  },
  travel: {
    title: '??ë»?,
    description: 'ï§Â€??æ¹²ê³•ì¤? ?Î¼??è«›â‘¸Ğ¦, GPX å¯ƒìˆì¤?? ??ë»???ì­????êµ¹????ë»???°ê²•??½ëŸ¹??ë’ª?ë¨?½Œ ?????¸ë•²??',
  },
  drive: {
    title: 'CalenDrive',
    description: '???ëµ???ëº¤ëµ¬????? ??¤ë²Š??, ????? ??¨Â€??, ?è¢â‘¥ì¨?????±ì Ÿ, ??¨Â€?ê·ë”†???«ê¿¸???è¢? 4????ë¥ëµ ç­Œì™–????????????ë¤¿ì—° ?????ëªƒë¹??',
  },
  'travel-money': {
    title: '??ë»???‰ê¶›',
    description: '??ë»???‰ê¶›??‡ë‚µ ??¼ì £ ï§Â€?°ì’–?????¨ë…¹ë¿???¿Â€?±Ñ‹ë???ˆë–.',
  },
  'travel-log': {
    title: '??ë»?æ¿¡ì’“??,
    description: '??ë»?ï§ë¶¾?? ??€ë£?å¯ƒìˆì¤? ?Î¼?? ??…ì¤ˆ?????”ª???ëº¤ì”¤??¸ë•²??',
  },
  'photo-album': {
    title: '??ë»???ì­?,
    description: 'æ¹²ê³•ì¤??æ¹²ê³•ì»??°ì¤ˆ ?´ÑŠê½¦????ì­?ä»¥ë¬’????ë»??ë¶¾ãˆƒ????ìœ­?Šë‚…???',
  },
  'family-album': {
    title: 'åª›Â€è­???¤ì¾¾',
    description: '?¥ëˆ????´ÑŠê½¦?ë¨?‚µ åª›Â€è­?ç§»ëŒ„?’æ€¨ì¢Š?? ??¤ì¾¾, èª˜ëªƒëµ??€? ?¨ë“­?€??¸ë•²??',
  },
  'my-map': {
    title: '??ï§Â€??,
    description: '?ê¾©ê»œ ??ë»??????å¯ƒìˆì¤ˆç‘œ?ï§Â€?ê¾¨ì¤ˆ ï§â‘¥ë¸?è¹‚ë‹¿?? ?ë¨°ë¸¯????ï§????œ­ ?ë¨?½­???ëº¤ì”¤??¸ë•²??',
  },
  admin: {
    title: '?¿Â€?±ÑŠì˜„',
    description: 'æ¿¡ì’“???æ¿¡ì’“??? ??????ê³¹ê¹­, ?¥ëˆ? ?ê¾ªì†´???ë¨???¸ë•²??',
  },
  profile: {
    title: '???ê¾¨ì¤ˆ??,
    description: '???¨ê¾©???ëº£ë‚«?? ?¾ëª„????ë¿­, ?¿Â€?±ÑŠì˜„ ????????¨ë…¹ë¿???ëº¤ì”¤??¸ë•²??',
  },
  invite: {
    title: '?¥ëˆ? ï§ê³¹ê²?åª›Â€??,
    description: '???¨ê¾©??? 1???Šœ ?¥ëˆ? ï§ê³¹ê²•æ¿¡?•ì­” ï§ëš®ë±?????‰ë’¿??ˆë–.',
  },
}

const correctedFeatureItems = [
  {
    key: 'household',
    number: '1',
    title: 'åª›Â€?¨ê¾¨?',
    description: 'åª›Â€?¨ê¾¨?, ???€? å¯ƒÂ€???‚µ ?ºê¾¨ìª??¿Â€?±Ñˆí‰´ï§Â€ ???ë¶¾ãˆƒ?ë¨?½Œ è«›ë¶¾ì¤??????????‰ë’¿??ˆë–.',
  },
  {
    key: 'travel',
    number: '2',
    title: '??ë»?,
    description: 'ï§Â€??æ¹²ê³•ì¤? ?Î¼??è«›â‘¸Ğ¦, GPX å¯ƒìˆì¤?? ??ë»???ì­??????°ê²•??½ëŸ¹??ë’ª?ë¨?½Œ ?¿Â€?±Ñ‹ë???ˆë–.',
  },
  {
    key: 'drive',
    number: '4',
    title: 'CalenDrive',
    description: '??€??ê³•ë±¶ ??•ì”ª??€?? ?¨ë“­?€, ï§¤ì’“?????”ª, ????? ?¿Â€?±ÑŠì˜„ æ¹²ê³•?????êµ¹???ë¶¾ãˆƒ?ë¨?½Œ ?????¸ë•²??',
  },
]

const correctedAdminFeatureItem = {
  key: 'admin',
  number: '7',
  title: '?¿Â€?±ÑŠì˜„',
  description: 'æ¿¡ì’“???æ¿¡ì’“?? ï§¡â‘¤??IP, ??????ê³¹ê¹­, ?¥ëˆ? ?ê¾ªì†´???¿Â€?±Ñ‹ë???ˆë–.',
}

const correctedRouteMeta = {
  ...routeMeta,
  launcher: {
    title: 'æ¹²ê³•???ì¢ê¹®',
    description: '??¼ì“¬??°ì¤ˆ ??¿í€???? æ¹²ê³•???ê³¸ë¿­???ì¢ê¹®??ê½­??',
  },
  household: {
    title: 'åª›Â€?¨ê¾¨?',
    description: 'åª›Â€?¨ê¾¨?, ???€? å¯ƒÂ€???‚µ ?ºê¾¨ìª??¿Â€?±Ñˆí‰´ï§Â€ ???ë¶¾ãˆƒ?ë¨?½Œ è«›ë¶¾ì¤??????????‰ë’¿??ˆë–.',
  },
  travel: {
    title: '??ë»?,
    description: 'ï§Â€??æ¹²ê³•ì¤? ?Î¼??è«›â‘¸Ğ¦, GPX å¯ƒìˆì¤?? ??ë»???ì­??????°ê²•??½ëŸ¹??ë’ª?ë¨?½Œ ?¿Â€?±Ñ‹ë???ˆë–.',
  },
  drive: {
    title: 'CalenDrive',
    description: '?´Ñ? ??•ì”ª??€??????”ª ?¿Â€???´ÑŠâ€œç‘œ?Calen ??‰ì‘æ¿???êº? ??…ì¤ˆ??£ë£°??ì¡–ë£¹?€æ´????”ªì¨????? ë£°???æ¹²ê³•??????¨ë“¦ì»?ë¨?½Œ ?????¸ë•²??',
  },
  admin: {
    title: '?¿Â€?±ÑŠì˜„',
    description: 'æ¿¡ì’“???æ¿¡ì’“?? ï§¡â‘¤??IP, ??????ê³¹ê¹­, ?¥ëˆ? ?ê¾ªì†´???¿Â€?±Ñ‹ë???ˆë–.',
  },
  profile: {
    title: '???ê¾¨ì¤ˆ??,
    description: '?¨ê¾©???ëº£ë‚«?? ?¾ëª„????ë¿­, ?¿Â€?±ÑŠì˜„ ????????¨ë…¹ë¿???ëº¤ì”¤??¸ë•²??',
  },
  invite: {
    title: '?¥ëˆ? ï§ê³¹ê²?åª›Â€??,
    description: '???¨ê¾©??? 1???Šœ ?¥ëˆ? ï§ê³¹ê²•æ¿¡?•ì­” ï§ëš®ë±?????‰ë’¿??ˆë–.',
  },
}

const normalizedFeatureItems = [
  {
    key: 'household',
    number: '1',
    title: 'åª›Â€?¨ê¾¨?',
    description: 'åª›Â€?¨ê¾¨?, ???€? å¯ƒÂ€???‚µ ?ºê¾¨ìª??¿Â€?±Ñˆí‰´ï§Â€ ???ë¶¾ãˆƒ?ë¨?½Œ è«›ë¶¾ì¤??????????‰ë’¿??ˆë–.',
  },
  {
    key: 'travel',
    number: '2',
    title: '??ë»?,
    description: 'ï§Â€??æ¹²ê³•ì¤? ?Î¼??è«›â‘¸Ğ¦, GPX å¯ƒìˆì¤?? ??ë»???ì­??????°ê²•??½ëŸ¹??ë’ª?ë¨?½Œ ?¿Â€?±Ñ‹ë???ˆë–.',
  },
  {
    key: 'drive',
    number: '4',
    title: 'CalenDrive',
    description: '???”ª ??…ì¤ˆ?? ?¨ë“­?€, ????? ?¿Â€?±ÑŠì˜„ ?ê¾§ë„????êµ¹????•ì”ª??€???ë¶¾ãˆƒ?ë¨?½Œ ?????¸ë•²??',
  },
]

const normalizedAdminFeatureItem = {
  key: 'admin',
  number: '7',
  title: '?¿Â€?±ÑŠì˜„',
  description: 'æ¿¡ì’“???æ¿¡ì’“?? ï§¡â‘¤??IP, ??????ê³¹ê¹­, ?¥ëˆ? ?ê¾ªì†´???¿Â€?±Ñ‹ë???ˆë–.',
}

const normalizedRouteMeta = {
  notifications: {
    title: 'Notifications',
    description: 'Review AI, OCR, backup, sharing, and operational notifications in one place.',
  },
  launcher: {
    title: 'æ¹²ê³•???ì¢ê¹®',
    description: '??¼ì“¬??°ì¤ˆ ??¿í€???? æ¹²ê³•???ê³¸ë¿­???ì¢ê¹®??ê½­??',
  },
  household: {
    title: 'åª›Â€?¨ê¾¨?',
    description: '',
  },
  travel: {
    title: '??ë»?,
    description: 'ï§Â€??æ¹²ê³•ì¤? ?Î¼??è«›â‘¸Ğ¦, GPX å¯ƒìˆì¤?? ??ë»???ì­??????°ê²•??½ëŸ¹??ë’ª?ë¨?½Œ ?????¸ë•²??',
  },
  drive: {
    title: 'CalenDrive',
    description: '???”ª ??…ì¤ˆ?? ??€???¿Â€?? ?¨ë“­?€, ????? ?¿Â€?±ÑŠì˜„ æ¹²ê³•?????•ì”ª??€???ë¶¾ãˆƒ?ë¨?½Œ ?????¸ë•²??',
  },
  'travel-money': {
    title: '??ë»?åª›Â€?¨ê¾¨?',
    description: '??ë»???ì—¯ì¨Œï§??°ì’–? åª›Â€?¨ê¾¨?????ë»??ê¾©ìŠœ ?ë¶¾ãˆƒ?ë¨?½Œ ?¿Â€?±Ñ‹ë¸¯?? æ¹²ê³—????‰ê¶› ?ë¶¾ãˆƒ?? ?ê¾©ìŠ‚?????­” ??ˆë•²??',
  },
  'travel-log': {
    title: '??ë»?æ¿¡ì’“??,
    description: '??ë»?æ¹²ê³•ì¤? ??€ë£?å¯ƒìˆì¤? ?Î¼?? ??…ì¤ˆ?????”ª???ëº¤ì”¤??¸ë•²??',
  },
  'photo-album': {
    title: '??ë»???ì­?,
    description: 'æ¹²ê³•ì¤?æ¹²ê³•ì»??°ì¤ˆ ?´ÑŠê½¦????ë»???ì­?ï§â‘¥ë¸˜è¹‚?¿ë¦°???ëº¤ì”¤??¸ë•²??',
  },
  'family-album': {
    title: 'åª›Â€è­???¤ì¾¾',
    description: 'åª›Â€è­??´ÑŠê½¦?ë¨?‚µ ??£í¡ ?ê³•ë’— ??ì­?è«??ê³¸ê¸½ ??¤ì¾¾???ëº¤ì”¤??¸ë•²??',
  },
  'my-map': {
    title: '??ï§Â€??,
    description: '?ê¾©ê»œ ??ë»??????å¯ƒìˆì¤ˆç‘œ?ï§Â€?ê¾¨ì¤ˆ ï§â‘¥ë¸?è¹‚ë‹¿?? ?ë¨°ë¸¯????ï§????œ­ ?ë¨?½­???ëº¤ì”¤??¸ë•²??',
  },
  admin: {
    title: '?¿Â€?±ÑŠì˜„',
    description: 'æ¿¡ì’“???æ¿¡ì’“?? ï§¡â‘¤??IP, ??????ê³¹ê¹­, ?¥ëˆ? ?ê¾ªì†´???¿Â€?±Ñ‹ë???ˆë–.',
  },
  profile: {
    title: '???ê¾¨ì¤ˆ??,
    description: '?¨ê¾©???ëº£ë‚«?? ?¾ëª„????ë¿­, ?¿Â€?±ÑŠì˜„ ???????“ë‚©?ë¨?½Œ ?ëº¤ì”¤??¸ë•²??',
  },
  invite: {
    title: '?¥ëˆ? ï§ê³¹ê²?ï§ëš®ë±¾æ¹²?,
    description: '???¨ê¾©??? 1???Šœ ?¥ëˆ? ï§ê³¹ê²•æ¿¡?•ì­” ï§ëš®ë±?????‰ë’¿??ˆë–.',
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
    { key: 'launcher', label: 'ï§ë¶¿?? },
    { key: 'household', label: 'åª›Â€?¨ê¾¨?' },
    { key: 'travel', label: '??ë»? },
    { key: 'drive', label: '??•ì”ª??€?? },
  ]
  if (currentUser.value?.admin) {
    items.push({ key: 'admin', label: '?¿Â€?±ÑŠì˜„' })
  }
  return items
})
const themeDegreeDisplay = computed(() => `${themeDegree.value}%`)
const layoutModeOptions = [
  { value: 'mobile', label: 'ï§â‘¤ì»?? },
  { value: 'desktop', label: '?ê³—ë’ª??ê¹? },
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
    setFeedback('', '?¥ëˆ? ï§ê³¹ê²•åª›? ??ì»?‘œ?? ??†ë’¿??ˆë–.')
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
    setFeedback('æ¿¡ì’“??ëªƒë¦º??‰ë’¿??ˆë–.')
  } catch (error) {
    setFeedback('', error.message)
  } finally {
    isSubmitting.value = false
    activeSubmit.value = ''
  }
}

async function handleAcceptInvite() {
  if (!inviteInfo.value || !inviteToken.value) {
    setFeedback('', '?ì¢ìŠš???¥ëˆ? ï§ê³¹ê²•ç‘œ??’ì‡±? ??ë¼±äºŒì‡±ê½??')
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
    setFeedback('?¥ëˆ? ï§ê³¹ê²•æ¿¡??¨ê¾©???ï§ëš®ë±¾æ€?è«›ë¶¾ì¤?æ¿¡ì’“??ëª…ë»½??¬ë•²??')
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
  setFeedback('æ¿¡ì’“??ê¾©ì??‰ë’¿??ˆë–.')
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
        <div class="layout-mode-toggle" role="group" aria-label="è¹‚ë‹¿ë¦???ê¼ ?ê¾ªì†š">
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
          {{ isTossTheme ? 'æ¹²ê³•?????­' : '?ì¢ë’ª ???­' }}
        </button>
        <button
          v-if="isTossTheme"
          class="theme-toggle theme-toggle--degree"
          type="button"
          @click.stop="toggleThemeDegreePanel"
        >
          ??½ê²• degree {{ themeDegreeDisplay }}
        </button>
      </div>

      <div v-if="isTossTheme && themeDegreePanelOpen" class="theme-degree-panel">
        <div class="theme-degree-panel__header">
          <strong>?Î»???åª›ëº£ë£?/strong>
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
          <span>??°ì»² ??½ê²•</span>
          <span>?Î»???/span>
        </div>
      </div>
    </div>

    <button v-if="false" class="theme-toggle" type="button" @click="toggleTheme">
      {{ isTossTheme ? 'æ¹²ê³•?????­' : '?ì¢ë’ª ???­' }}
    </button>

    <div v-if="!authChecked" class="loading-overlay">?ëª„ë€???ëº¤ì”¤??ë’— ä»¥ë¬’???ˆë–...</div>

    <template v-else-if="activeRoute === 'invite'">
      <section class="auth-shell">
        <div class="auth-copy">
          <span class="auth-copy__badge">?¥ëˆ? ï§ê³¹ê²?åª›Â€??/span>
          <h1>???¨ê¾©??? 1???Šœ ?¥ëˆ? ï§ê³¹ê²•æ¿¡?•ì­” ï§ëš®ë±?????‰ë’¿??ˆë–.</h1>
          <p>ï§ê³¹ê²•åª›? ?ì¢ìŠš??ãˆƒ æ¿¡ì’“???ID, ??–ë–† ??€ì«? ??¾¨?è¸°ëŠ?‡ç‘œ???…ì °???¨ê¾©???ï§ëš®ë±¾æ€?è«›ë¶¾ì¤?æ¿¡ì’“??ëª…ë¸· ????‰ë’¿??ˆë–.</p>
          <p v-if="currentUser" class="auth-copy__hint">
            ?ê¾©ì˜± {{ currentUser.displayName }} ({{ currentUser.loginId }}) ?¨ê¾©???°ì¤ˆ æ¿¡ì’“???ä»¥ë¬’???ˆë–. åª›Â€??†ì”  ??¸êµ¹ï§????‰ëš®??ê³??????¨ê¾©???°ì¤ˆ ?ê¾ªì†š??¸ë•²??
          </p>
        </div>

        <div class="auth-grid">
          <article class="auth-card">
            <h2>?¥ëˆ? ?ê³¹ê¹­</h2>
            <div class="stack-form stack-form--readonly">
              <p v-if="isInviteLoading">?¥ëˆ? ï§ê³¹ê²•ç‘œ??ëº¤ì”¤??ë’— ä»¥ë¬’???ˆë–...</p>
              <template v-else-if="inviteInfo">
                <p><strong>{{ inviteInfo.inviterDisplayName }}</strong> ??ì”  ï§ëš®ë±??¥ëˆ? ï§ê³¹ê²??…ë•²??</p>
                <p>ï§ëš®ì¦???“ì»™: {{ formatDateTime(inviteInfo.expiresAt) }}</p>
              </template>
              <p v-else>??ï§ê³¹ê²•æ¿¡?•ë’— ?¨ê¾©???ï§ëš®ë±?????ë’¿??ˆë–.</p>
            </div>
          </article>

          <article class="auth-card">
            <h2>?¥ëˆ? ?¨ê¾©??ï§ëš®ë±¾æ¹²?/h2>
            <form class="stack-form" @submit.prevent="handleAcceptInvite">
              <input
                v-model="inviteForm.loginId"
                type="text"
                placeholder="æ¿¡ì’“???ID"
                autocomplete="username"
                :disabled="isSubmitting || isInviteLoading || !inviteInfo"
              />
              <input
                v-model="inviteForm.displayName"
                type="text"
                placeholder="??–ë–† ??€ì«?
                autocomplete="name"
                :disabled="isSubmitting || isInviteLoading || !inviteInfo"
              />
              <input
                v-model="inviteForm.password"
                type="password"
                placeholder="??¾¨?è¸°ëŠ??(8????ê¸½)"
                autocomplete="new-password"
                :disabled="isSubmitting || isInviteLoading || !inviteInfo"
              />
              <PinPadInput
                v-model="inviteForm.secondaryPin"
                label="2ï§???¾¨?è¸°ëŠ??
                hint="åª›Â€????æ¿¡ì’“??ëª…ë¸· ???£„ åª›ìˆˆ? ??¬ì˜„ 8?ë¨?”??ï§ë‰???»ì¤ˆ ???œ­ ??…ì °??¸ë•²??"
                :disabled="isSubmitting || isInviteLoading || !inviteInfo"
              />
              <label class="checkbox-row">
                <input
                  v-model="inviteForm.rememberDevice"
                  type="checkbox"
                  :disabled="isSubmitting || isInviteLoading || !inviteInfo"
                />
                <span>???‰ëš®??ê³??ë¨?½Œ æ¿¡ì’“????ê³¹ê¹­ ?ì¢?</span>
              </label>
              <button class="button button--primary" type="submit" :disabled="isSubmitting || isInviteLoading || !inviteInfo">
                {{ isSubmitting && activeSubmit === 'invite' ? '?¨ê¾©????¹ê½¦ ä»?..' : '?¨ê¾©??ï§ëš®ë±¾æ€?æ¿¡ì’“??? }}
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
            <h2>æ¿¡ì’“???/h2>
            <form class="stack-form" @submit.prevent="handleLogin">
              <input v-model="loginForm.loginId" type="text" placeholder="æ¿¡ì’“???ID" autocomplete="username" />
              <input v-model="loginForm.password" type="password" placeholder="??¾¨?è¸°ëŠ?? autocomplete="current-password" />
              <PinPadInput
                v-model="loginForm.secondaryPin"
                label="2ï§???¾¨?è¸°ëŠ??
                hint="??»ë‚«????????¬ì˜„ è¸°ê¾ª??????œ­ 8?ë¨?”????…ì °??ï¼œ?ëª„ìŠ‚."
                :disabled="isSubmitting"
              />
              <label class="checkbox-row">
                <input v-model="loginForm.rememberDevice" type="checkbox" />
                <span>???‰ëš®??ê³??ë¨?½Œ æ¿¡ì’“????ê³¹ê¹­ ?ì¢?</span>
              </label>
              <button class="button button--primary" type="submit" :disabled="isSubmitting">
                {{ isSubmitting && activeSubmit === 'login' ? 'æ¿¡ì’“???ä»?..' : 'æ¿¡ì’“??? }}
              </button>
            </form>
          </article>

          <article class="auth-card">
            <h2>?¨ê¾©????ˆê¶¡</h2>
            <div class="stack-form stack-form--readonly">
              <p>?¨ë“¦ì»????åª›Â€??? ?°ì‡±ì¡???‰ë’¿??ˆë–.</p>
              <p>???¨ê¾©????ê¾©ìŠ‚??ãˆƒ æ¹²ê³—??????ë¨?µ¹ ?¿Â€?±ÑŠì˜„?ë¨?¾¶ 1???Šœ ?¥ëˆ? ï§ê³¹ê²???¹ê½¦???ë¶¿ê»Œ??ï¼œ?ëª„ìŠ‚.</p>
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
          <nav class="topbar__nav" aria-label="äºŒì‡±??æ¹²ê³•??>
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
            <button v-if="activeRoute !== 'profile'" class="button button--ghost" @click="navigate('profile')">???ê¾¨ì¤ˆ??/button>
            <button class="button button--ghost" @click="handleLogout">æ¿¡ì’“??ê¾©ì</button>
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
