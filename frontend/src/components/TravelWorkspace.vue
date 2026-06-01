<script setup>
import { computed, ref, watch } from 'vue'
import TravelHubWorkspace from './TravelHubWorkspace.vue'
import TravelMyMapWorkspace from './TravelMyMapWorkspace.vue'
import TravelPublicTripsWorkspace from './TravelPublicTripsWorkspace.vue'

const props = defineProps({
  route: {
    type: String,
    default: 'travel',
  },
})

const primaryTab = ref('map')
const hubRoute = ref('travel-log')
const hubInitialLogTab = ref('overview')
const hubInitialMoneyTab = ref('records')

const travelModes = [
  {
    key: 'map',
    label: '기록 지도',
    meta: '사진과 이동 경로',
    badge: 'MAP',
  },
  {
    key: 'memories',
    label: '장소 기록',
    meta: '방문지와 사진 업로드',
    badge: 'VISIT',
  },
  {
    key: 'routes',
    label: 'GPX 경로',
    meta: '이동 기록 정리',
    badge: 'GPX',
  },
  {
    key: 'photos',
    label: '내 사진',
    meta: '썸네일 사진첩',
    badge: 'PHOTO',
  },
  {
    key: 'share',
    label: '여행 공유',
    meta: '공개/선택 공유',
    badge: 'SHARE',
  },
  {
    key: 'finance',
    label: '여행 가계부',
    meta: '예산과 지출 보조 관리',
    badge: 'LEDGER',
  },
]

function applyRouteState(route) {
  switch (route) {
    case 'travel-log':
      primaryTab.value = 'memories'
      hubRoute.value = 'travel-log'
      hubInitialLogTab.value = 'memories'
      break
    case 'photo-album':
      primaryTab.value = 'photos'
      hubRoute.value = 'photo-album'
      break
    case 'my-map':
    case 'travel':
      primaryTab.value = 'map'
      hubRoute.value = 'travel-log'
      hubInitialLogTab.value = 'overview'
      break
    case 'public-trips':
      primaryTab.value = 'share'
      break
    case 'travel-money':
      primaryTab.value = 'finance'
      hubRoute.value = 'travel-money'
      hubInitialMoneyTab.value = 'records'
      break
    default:
      primaryTab.value = 'map'
      hubRoute.value = 'travel-log'
      hubInitialLogTab.value = 'overview'
      break
  }
}

function openFinance() {
  primaryTab.value = 'finance'
  hubRoute.value = 'travel-money'
  hubInitialMoneyTab.value = 'records'
}

function openMemories() {
  primaryTab.value = 'memories'
  hubRoute.value = 'travel-log'
  hubInitialLogTab.value = 'memories'
}

function openRoutes() {
  primaryTab.value = 'routes'
  hubRoute.value = 'travel-log'
  hubInitialLogTab.value = 'routes'
}

function openMap() {
  primaryTab.value = 'map'
  hubRoute.value = 'travel-log'
  hubInitialLogTab.value = 'overview'
}

function openPhotos() {
  primaryTab.value = 'photos'
  hubRoute.value = 'photo-album'
}

function openShare() {
  primaryTab.value = 'share'
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
    case 'share':
      openShare()
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

function handleRequestOpenPublicTrips() {
  openShare()
}

const isHubVisible = computed(() => primaryTab.value !== 'share' && primaryTab.value !== 'map')
const isIntegratedPhotoMode = computed(() => primaryTab.value === 'photos')

watch(
  () => props.route,
  (route) => {
    applyRouteState(route)
  },
  { immediate: true },
)
</script>

<template>
  <div class="workspace-stack travel-unified-shell">
    <section class="panel travel-record-switcher">
      <div class="panel__header">
        <div>
          <h2>여행 기록</h2>
          <p>지도, 방문 기록, GPX 경로, 사진첩을 중심으로 여행을 정리합니다.</p>
        </div>
        <span class="panel__badge">Record first</span>
      </div>
      <div class="travel-record-switcher__grid">
        <button
          v-for="mode in travelModes"
          :key="mode.key"
          class="travel-record-switcher__card"
          :class="{ 'travel-record-switcher__card--active': primaryTab === mode.key }"
          type="button"
          @click="openMode(mode.key)"
        >
          <span>{{ mode.badge }}</span>
          <strong>{{ mode.label }}</strong>
          <small>{{ mode.meta }}</small>
        </button>
      </div>
    </section>

    <div v-show="primaryTab === 'map'" class="workspace-stack">
      <TravelMyMapWorkspace :active="primaryTab === 'map'" />
    </div>

    <div v-if="primaryTab === 'share'" class="workspace-stack">
      <TravelPublicTripsWorkspace :active="primaryTab === 'share'" />
    </div>

    <div v-show="isHubVisible" class="workspace-stack">
      <TravelHubWorkspace
        :route="hubRoute"
        :integrated-mode="true"
        :integrated-photo-mode="isIntegratedPhotoMode"
        :initial-log-tab="hubInitialLogTab"
        :initial-money-tab="hubInitialMoneyTab"
        @request-open-finance="handleRequestOpenFinance"
        @request-open-log="handleRequestOpenLog"
        @request-open-public-trips="handleRequestOpenPublicTrips"
      />
    </div>
  </div>
</template>
