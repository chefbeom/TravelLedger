<script setup>
import { computed, defineAsyncComponent, onMounted, ref, watch } from 'vue'
import { fetchTravelPortfolio } from '../lib/api'
const TravelHubWorkspace = defineAsyncComponent(() => import('./TravelHubWorkspace.vue'))
const TravelMyMapWorkspace = defineAsyncComponent(() => import('./TravelMyMapWorkspace.vue'))

const props = defineProps({
  route: {
    type: String,
    default: 'travel',
  },
  recordFocusRequest: {
    type: Object,
    default: null,
  },
})

const emit = defineEmits(['open-household-travel-ledger', 'record-focus-consumed'])

const primaryTab = ref('map')
const workflowMode = ref('')
const workflowStartStep = ref(1)
const hubRoute = ref('travel-log')
const hubInitialLogTab = ref('overview')
const hubInitialMoneyTab = ref('records')
const financeLegacyOpen = ref(false)
const travelPortfolio = ref(null)
const travelSummaryLoading = ref(false)
const travelSummaryError = ref('')
const travelSummaryLoaded = ref(false)
const hubPlaceFocusRequest = ref(null)
const hubPhotoFocusRequest = ref(null)
const hubRouteFocusRequest = ref(null)
const hubRecordFocusRequest = ref(null)
const appliedRecordFocusToken = ref('')

const travelModes = [
  {
    key: 'map',
    label: '기록 지도',
    meta: '사진, 방문 장소, 이동 경로',
    badge: 'MAP',
    actionLabel: '지도 열기',
  },
  {
    key: 'memories',
    label: '장소 기록',
    meta: '방문지와 메모',
    badge: 'VISIT',
    actionLabel: '기록 작성',
  },
  {
    key: 'routes',
    label: 'GPX 경로',
    meta: '이동 기록 정리',
    badge: 'GPX',
    actionLabel: '경로 관리',
  },
  {
    key: 'photos',
    label: '내 사진',
    meta: '업로드 사진첩',
    badge: 'PHOTO',
    actionLabel: '사진 보기',
  },
]

const travelRecordSummary = computed(() => {
  const portfolio = travelPortfolio.value || {}
  const plans = Array.isArray(portfolio.plans) ? portfolio.plans : []
  const memoryRecords = Array.isArray(portfolio.memoryRecords) ? portfolio.memoryRecords : []
  const routeSegments = Array.isArray(portfolio.routeSegments) ? portfolio.routeSegments : []
  const mediaItems = Array.isArray(portfolio.mediaItems) ? portfolio.mediaItems : []

  return {
    plans: readCount(portfolio.includedPlanCount, plans.length),
    memories: readCount(portfolio.memoryRecordCount, memoryRecords.length),
    photos: readCount(portfolio.mediaItemCount, mediaItems.length),
    routes: readCount(portfolio.routeSegmentCount, routeSegments.length),
  }
})

const hasTravelPlans = computed(() => {
  const plans = Array.isArray(travelPortfolio.value?.plans) ? travelPortfolio.value.plans : []
  return plans.length > 0 || travelRecordSummary.value.plans > 0
})

const travelSummaryText = computed(() => {
  const summary = travelRecordSummary.value
  return `여행 ${summary.plans}개 · 장소 기록 ${summary.memories}건 · 사진 ${summary.photos}장 · GPX ${summary.routes}개`
})

function readCount(value, fallback = 0) {
  const numericValue = Number(value)
  return Number.isFinite(numericValue) && numericValue >= 0 ? numericValue : fallback
}

function getModeMetric(modeKey) {
  const summary = travelRecordSummary.value
  switch (modeKey) {
    case 'map':
      return `여행 ${summary.plans}개`
    case 'memories':
      return `기록 ${summary.memories}건`
    case 'routes':
      return `경로 ${summary.routes}개`
    case 'photos':
      return `사진 ${summary.photos}장`
    default:
      return ''
  }
}

async function loadTravelSummary() {
  travelSummaryLoading.value = true
  travelSummaryError.value = ''
  try {
    travelPortfolio.value = await fetchTravelPortfolio()
    if (props.route === 'travel-log') {
      openTravelSetup({ mode: hasTravelPlans.value ? 'edit' : 'create', step: hasTravelPlans.value ? 2 : 1 })
    } else if (['travel', 'my-map'].includes(props.route) && !hasTravelPlans.value) {
      openTravelSetup({ mode: 'create', step: 1 })
    }
  } catch (error) {
    travelSummaryError.value = error.message || '여행 요약을 불러오지 못했습니다.'
  } finally {
    travelSummaryLoading.value = false
    travelSummaryLoaded.value = true
  }
}
function applyRouteState(route) {
  financeLegacyOpen.value = false
  workflowMode.value = ''
  switch (route) {
    case 'travel-log': {
      const hasPlans = hasTravelPlans.value
      openTravelSetup({ mode: hasPlans ? 'edit' : 'create', step: hasPlans ? 2 : 1 })
      break
    }
    case 'photo-album':
      primaryTab.value = 'photos'
      hubRoute.value = 'photo-album'
      break
    case 'my-map':
    case 'travel':
      if (travelSummaryLoaded.value && !hasTravelPlans.value) {
        openTravelSetup({ mode: 'create', step: 1 })
      } else {
        openMap()
      }
      break
    case 'public-trips':
      openMap()
      break
    case 'travel-money':
      primaryTab.value = 'finance'
      hubRoute.value = 'travel-money'
      hubInitialMoneyTab.value = 'records'
      break
    default:
      openMap()
      break
  }
}
function openTravelSetup({ mode = 'edit', step = 1 } = {}) {
  const hasPlans = hasTravelPlans.value
  const normalizedStep = [1, 2, 3, 4].includes(Number(step)) ? Number(step) : 1
  primaryTab.value = 'setup'
  hubRoute.value = 'travel-log'
  hubInitialLogTab.value = 'overview'
  financeLegacyOpen.value = false
  workflowMode.value = mode === 'edit' && hasPlans ? 'edit' : 'create'
  workflowStartStep.value = hasPlans ? normalizedStep : 1
}

function openFinance() {
  workflowMode.value = ''
  primaryTab.value = 'finance'
  hubRoute.value = 'travel-money'
  hubInitialMoneyTab.value = 'records'
  financeLegacyOpen.value = false
}

function openFinanceEditor(initialMoneyTab = 'records') {
  workflowMode.value = ''
  primaryTab.value = 'finance'
  hubRoute.value = 'travel-money'
  hubInitialMoneyTab.value = initialMoneyTab
  financeLegacyOpen.value = true
}

function openTravelManager() {
  openTravelSetup({ mode: hasTravelPlans.value ? 'edit' : 'create', step: 1 })
}

function openMemories(clearPlaceFocus = true) {
  if (clearPlaceFocus) {
    hubPlaceFocusRequest.value = {
      type: 'place',
      token: Date.now(),
      planId: '',
      country: '',
      region: '',
      placeName: '',
    }
  }
  openTravelSetup({ mode: hasTravelPlans.value ? 'edit' : 'create', step: 2 })
}

function openPhotoEditor(photo) {
  if (!photo?.recordId || !photo?.planId) {
    return
  }

  hubRecordFocusRequest.value = null
  if (String(photo.recordType || '').toUpperCase() === 'MEMORY') {
    hubPlaceFocusRequest.value = {
      type: 'memory',
      id: String(photo.recordId),
      planId: String(photo.planId),
      token: Date.now(),
    }
    openMemories(false)
    return
  }

  hubPlaceFocusRequest.value = null
  hubRecordFocusRequest.value = {
    type: 'record',
    recordId: String(photo.recordId),
    planId: String(photo.planId),
    token: Date.now(),
  }
  openFinanceEditor()
}

function openRoutes(focusRequest = null) {
  if (focusRequest) {
    hubRouteFocusRequest.value = {
      type: 'route',
      token: Date.now(),
      routeId: focusRequest.routeId || focusRequest.id || '',
      planId: focusRequest.planId || '',
      routeDate: focusRequest.routeDate || '',
    }
  }
  openTravelSetup({ mode: hasTravelPlans.value ? 'edit' : 'create', step: 3 })
}

function openMap() {
  workflowMode.value = ''
  primaryTab.value = 'map'
  hubRoute.value = 'travel-log'
  hubInitialLogTab.value = 'overview'
}

function openPhotos(focusRequest = null) {
  if (focusRequest) {
    hubPhotoFocusRequest.value = {
      type: 'photo',
      token: Date.now(),
      planId: focusRequest.planId || '',
      country: focusRequest.country || '',
      region: focusRequest.region || '',
      placeName: focusRequest.placeName || '',
    }
  }
  openTravelSetup({ mode: hasTravelPlans.value ? 'edit' : 'create', step: 2 })
}

async function handleWorkflowComplete() {
  await loadTravelSummary()
  openMap()
}

async function closeTravelSetup() {
  await loadTravelSummary()
  openMap()
}
function openMode(mode) {
  switch (mode) {
    case 'finance':
      openFinance()
      break
    case 'memories':
      openMemories()
      break
    case 'routes':
      openRoutes()
      break
    case 'photos':
      openPhotos()
      break
    case 'map':
    default:
      openMap()
      break
  }
}

function handleRequestOpenLog() {
  openMemories()
}

function handleRequestOpenFinance() {
  openFinance()
}

const isHubVisible = computed(() => (
  primaryTab.value === 'setup'
  || (
    primaryTab.value !== 'map'
    && (primaryTab.value !== 'finance' || financeLegacyOpen.value)
  )
))
const isIntegratedPhotoMode = computed(() => primaryTab.value === 'photos')
const shouldMountHub = ref(false)

watch(
  isHubVisible,
  (value) => {
    if (value) {
      shouldMountHub.value = true
    }
  },
  { immediate: true },
)

watch(
  () => props.route,
  (route) => {
    applyRouteState(route)
  },
  { immediate: true },
)

watch(
  () => props.recordFocusRequest?.token,
  (token) => {
    const normalizedToken = String(token || '')
    if (!normalizedToken || normalizedToken === appliedRecordFocusToken.value) {
      return
    }
    appliedRecordFocusToken.value = normalizedToken
    openFinanceEditor()
  },
  { immediate: true },
)

onMounted(loadTravelSummary)
</script>

<template>
  <div class="workspace-stack travel-unified-shell">
    <section v-if="primaryTab !== 'map' && primaryTab !== 'setup'" class="panel travel-record-switcher">
      <div class="panel__header">
        <div>
          <h2>여행 기록</h2>
          <p>지도, 방문 장소, GPX 경로, 사진첩을 중심으로 여행을 정리합니다.</p>
        </div>
        <span class="panel__badge">기록 중심</span>
      </div>
      <div class="travel-record-switcher__grid">
        <button
          v-for="mode in travelModes"
          :key="mode.key"
          class="travel-record-switcher__card"
          :class="{ 'travel-record-switcher__card--active': primaryTab === mode.key }"
          type="button"
          :aria-label="`${mode.label}: ${mode.meta}`"
          @click="openMode(mode.key)"
        >
          <span>{{ mode.badge }}</span>
          <strong>{{ mode.label }}</strong>
          <small>{{ mode.meta }}</small>
          <em>{{ travelSummaryLoading ? '불러오는 중' : getModeMetric(mode.key) }}</em>
          <b class="travel-record-switcher__action">{{ mode.actionLabel }}</b>
        </button>
      </div>
      <div class="travel-record-switcher__summary">
        <span v-if="travelSummaryLoading">여행 기록 요약을 불러오는 중입니다.</span>
        <span v-else-if="travelSummaryError">{{ travelSummaryError }}</span>
        <span v-else>{{ travelSummaryText }}</span>
      </div>
      <div class="travel-record-switcher__finance-link">
        <div>
          <span>HOUSEHOLD LINK</span>
          <strong>여행 가계부는 가계부에서 이어서 관리합니다</strong>
          <small>여행 화면은 지도, 장소 방문, GPX, 사진 기록에 집중하고 여행 수입·지출은 가계부 검색/통계/수정 이력과 함께 사용합니다.</small>
        </div>
        <div class="travel-record-switcher__finance-actions">
          <button class="button button--primary" type="button" @click="emit('open-household-travel-ledger')">
            가계부에서 열기
          </button>
          <button class="button button--ghost" type="button" @click="openFinance">
            기존 예산 보기
          </button>
        </div>
      </div>
    </section>

    <div v-show="primaryTab === 'map'" class="workspace-stack">
      <TravelMyMapWorkspace
        :active="primaryTab === 'map'"
        @open-memories="openMemories"
        @open-routes="openRoutes"
        @open-photos="openPhotos"
        @open-travel-manager="openTravelManager"
        @open-photo-editor="openPhotoEditor"
      />
    </div>

    <section v-if="primaryTab === 'finance' && !financeLegacyOpen" class="panel travel-finance-bridge">
      <div class="panel__header">
        <div>
          <span class="panel__eyebrow">HOUSEHOLD LINK</span>
          <h2>여행 가계부는 가계부에서 관리합니다</h2>
          <p>여행 수입과 지출은 일반 가계부 데이터와 함께 저장하고, 여행 가계부 화면에서 따로 모아 봅니다.</p>
        </div>
        <span class="panel__badge">연계됨</span>
      </div>
      <div class="travel-finance-bridge__body">
        <article>
          <strong>새 여행 거래 입력</strong>
          <span>가계부의 거래 입력 흐름을 그대로 사용해서 여행 수입과 지출을 기록합니다.</span>
        </article>
        <article>
          <strong>기존 거래와 함께 집계</strong>
          <span>가계부 검색, 통계, 수정 이력과 같은 기존 기능을 그대로 사용할 수 있습니다.</span>
        </article>
        <article>
          <strong>기존 여행 예산 화면 유지</strong>
          <span>필요할 때만 기존 여행 예산/지출 보조 화면을 열 수 있습니다.</span>
        </article>
      </div>
      <div class="entry-editor__actions">
        <button class="button button--primary" type="button" @click="emit('open-household-travel-ledger')">
          가계부에서 여행 가계부 열기
        </button>
        <button class="button button--ghost" type="button" @click="financeLegacyOpen = true">
          기존 여행 예산/지출 보기
        </button>
      </div>
    </section>

    <div v-if="shouldMountHub" v-show="isHubVisible" class="workspace-stack">
      <TravelHubWorkspace
        :route="hubRoute"
        :integrated-mode="true"
        :integrated-photo-mode="isIntegratedPhotoMode"
        :initial-log-tab="hubInitialLogTab"
        :initial-money-tab="hubInitialMoneyTab"
        :workflow-mode="workflowMode"
        :workflow-start-step="workflowStartStep"
        :external-record-focus-request="hubRecordFocusRequest || recordFocusRequest"
        :external-memory-focus-request="hubPlaceFocusRequest"
        :external-photo-focus-request="hubPhotoFocusRequest"
        :external-route-focus-request="hubRouteFocusRequest"
        @request-open-finance="handleRequestOpenFinance"
        @request-open-log="handleRequestOpenLog"
        @workflow-complete="handleWorkflowComplete"
        @workflow-close="closeTravelSetup"
        @record-focus-consumed="emit('record-focus-consumed', $event)"
      />
    </div>
  </div>
</template>
