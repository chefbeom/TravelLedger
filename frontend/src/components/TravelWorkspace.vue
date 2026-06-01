<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { fetchTravelPortfolio } from '../lib/api'
import TravelHubWorkspace from './TravelHubWorkspace.vue'
import TravelMyMapWorkspace from './TravelMyMapWorkspace.vue'
import TravelPublicTripsWorkspace from './TravelPublicTripsWorkspace.vue'

const props = defineProps({
  route: {
    type: String,
    default: 'travel',
  },
})

const emit = defineEmits(['open-household-travel-ledger'])

const primaryTab = ref('map')
const hubRoute = ref('travel-log')
const hubInitialLogTab = ref('overview')
const hubInitialMoneyTab = ref('records')
const financeLegacyOpen = ref(false)
const travelPortfolio = ref(null)
const travelSummaryLoading = ref(false)
const travelSummaryError = ref('')

const travelModes = [
  {
    key: 'map',
    label: '기록 지도',
    meta: '사진, 방문 장소, 이동 경로',
    badge: 'MAP',
  },
  {
    key: 'memories',
    label: '장소 기록',
    meta: '방문지와 메모',
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
    meta: '업로드 사진첩',
    badge: 'PHOTO',
  },
  {
    key: 'share',
    label: '여행 공유',
    meta: '공개/선택 공유',
    badge: 'SHARE',
  },
]

const travelRecordActions = [
  {
    key: 'memories',
    label: '장소 방문 기록',
    meta: '방문지, 메모, 좌표',
    action: '기록하기',
  },
  {
    key: 'routes',
    label: 'GPX 경로 정리',
    meta: '이동 경로, 구간',
    action: '경로 보기',
  },
  {
    key: 'photos',
    label: '사진첩 정리',
    meta: '시간순, 장소별 사진',
    action: '사진 보기',
  },
  {
    key: 'share',
    label: '여행 공유',
    meta: '공개/선택 공유 지도',
    action: '공유 보기',
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
    shared: memoryRecords.filter((record) => Boolean(record.sharedWithCommunity)).length,
  }
})

const travelRecordFocusStats = computed(() => {
  const portfolio = travelPortfolio.value || {}
  const memoryRecords = Array.isArray(portfolio.memoryRecords) ? portfolio.memoryRecords : []
  const records = Array.isArray(portfolio.records) ? portfolio.records : []
  const taggedRecords = [...memoryRecords, ...records]

  return [
    {
      label: '방문 지역',
      value: uniqueRecordValues(taggedRecords, 'region').length,
    },
    {
      label: '방문 장소',
      value: uniqueRecordValues(taggedRecords, 'placeName').length,
    },
    {
      label: '사진',
      value: travelRecordSummary.value.photos,
    },
    {
      label: 'GPX',
      value: travelRecordSummary.value.routes,
    },
  ]
})

const travelRecentActivities = computed(() => {
  const portfolio = travelPortfolio.value || {}
  const memoryRecords = Array.isArray(portfolio.memoryRecords) ? portfolio.memoryRecords : []
  const mediaItems = Array.isArray(portfolio.mediaItems) ? portfolio.mediaItems : []
  const routeSegments = Array.isArray(portfolio.routeSegments) ? portfolio.routeSegments : []

  return [
    ...memoryRecords.map((record) => ({
      id: `memory-${record.id}`,
      mode: 'memories',
      kind: 'PLACE',
      label: '장소',
      title: record.placeName || record.title || '장소 기록',
      meta: joinActivityMeta([record.planName, record.region, record.country]),
      dateLabel: formatActivityDate(record.memoryDate, record.memoryTime),
      timestamp: toActivityTimestamp(record.memoryDate, record.memoryTime),
      shared: Boolean(record.sharedWithCommunity),
      previewUrl: '',
    })),
    ...routeSegments.map((route) => ({
      id: `route-${route.id}`,
      mode: 'routes',
      kind: 'GPX',
      label: 'GPX',
      title: route.title || buildRouteTitle(route),
      meta: joinActivityMeta([route.planName, buildRouteMetric(route)]),
      dateLabel: formatActivityDate(route.routeDate),
      timestamp: toActivityTimestamp(route.routeDate),
      shared: false,
      previewUrl: '',
    })),
    ...mediaItems
      .filter((media) => String(media.mediaType || '').toUpperCase() === 'PHOTO')
      .map((media) => ({
        id: `photo-${media.id}`,
        mode: 'photos',
        kind: 'PHOTO',
        label: '사진',
        title: media.caption || media.title || media.originalFileName || '여행 사진',
        meta: joinActivityMeta([media.planName, media.placeName, media.region]),
        dateLabel: formatActivityDate(media.expenseDate || media.uploadedAt),
        timestamp: toActivityTimestamp(media.expenseDate || media.uploadedAt),
        shared: false,
        previewUrl: media.contentUrl || '',
      })),
  ]
    .sort((left, right) => right.timestamp - left.timestamp)
    .slice(0, 6)
})

const travelVisitedPlaces = computed(() => {
  const portfolio = travelPortfolio.value || {}
  const memoryRecords = Array.isArray(portfolio.memoryRecords) ? portfolio.memoryRecords : []
  const records = Array.isArray(portfolio.records) ? portfolio.records : []
  const mediaItems = Array.isArray(portfolio.mediaItems) ? portfolio.mediaItems : []
  const routeSegments = Array.isArray(portfolio.routeSegments) ? portfolio.routeSegments : []
  const places = new Map()

  function readPlace(source = {}) {
    const placeName = normalizeLocationPart(source.placeName)
    const region = normalizeLocationPart(source.region)
    const country = normalizeLocationPart(source.country)
    if (!placeName && !region && !country) {
      return null
    }
    return { placeName, region, country }
  }

  function ensurePlace(source = {}) {
    const place = readPlace(source)
    if (!place) {
      return null
    }
    const key = [place.country, place.region, place.placeName || place.region || place.country].join('__')
    if (!places.has(key)) {
      places.set(key, {
        key,
        placeName: place.placeName || place.region || place.country,
        regionLabel: joinActivityMeta([place.country, place.region]),
        planNames: new Set(),
        memoryCount: 0,
        ledgerCount: 0,
        photoCount: 0,
        routeCount: 0,
        latestTimestamp: 0,
      })
    }
    return places.get(key)
  }

  memoryRecords.forEach((record) => {
    const row = ensurePlace(record)
    if (!row) return
    row.memoryCount += 1
    row.planNames.add(record.planName)
    row.latestTimestamp = Math.max(row.latestTimestamp, toActivityTimestamp(record.memoryDate, record.memoryTime))
  })

  records.forEach((record) => {
    const row = ensurePlace(record)
    if (!row) return
    row.ledgerCount += 1
    row.planNames.add(record.planName)
    row.latestTimestamp = Math.max(row.latestTimestamp, toActivityTimestamp(record.expenseDate, record.expenseTime))
  })

  mediaItems
    .filter((media) => String(media.mediaType || '').toUpperCase() === 'PHOTO')
    .forEach((media) => {
      const row = ensurePlace(media)
      if (!row) return
      row.photoCount += 1
      row.planNames.add(media.planName)
      row.latestTimestamp = Math.max(row.latestTimestamp, toActivityTimestamp(media.expenseDate || media.uploadedAt))
  })

  routeSegments.forEach((route) => {
    const routeEndpoints = [
      { placeName: route.startPlaceName, planName: route.planName },
      { placeName: route.endPlaceName, planName: route.planName },
    ]
    routeEndpoints.forEach((endpoint) => {
      const row = ensurePlace(endpoint)
      if (!row) return
      row.routeCount += 1
      row.planNames.add(endpoint.planName)
      row.latestTimestamp = Math.max(row.latestTimestamp, toActivityTimestamp(route.routeDate))
    })
  })

  return [...places.values()]
    .map((row) => ({
      ...row,
      planLabel: [...row.planNames].filter(Boolean).slice(0, 2).join(' · ') || '여행 미지정',
      totalCount: row.memoryCount + row.ledgerCount + row.photoCount + row.routeCount,
      visitCount: row.memoryCount + row.ledgerCount,
    }))
    .sort((left, right) => {
      if (right.latestTimestamp !== left.latestTimestamp) {
        return right.latestTimestamp - left.latestTimestamp
      }
      return right.totalCount - left.totalCount
    })
    .slice(0, 8)
})

const travelSummaryText = computed(() => {
  const summary = travelRecordSummary.value
  return `여행 ${summary.plans}개 · 장소 기록 ${summary.memories}건 · 사진 ${summary.photos}장 · GPX ${summary.routes}개`
})

function readCount(value, fallback = 0) {
  const numericValue = Number(value)
  return Number.isFinite(numericValue) && numericValue >= 0 ? numericValue : fallback
}

function uniqueRecordValues(records, key) {
  return [
    ...new Set(
      (records || [])
        .map((record) => String(record?.[key] || '').trim())
        .filter(Boolean),
    ),
  ]
}

function normalizeLocationPart(value) {
  return String(value || '').trim()
}

function joinActivityMeta(parts) {
  return parts.map((part) => String(part || '').trim()).filter(Boolean).join(' · ')
}

function toActivityTimestamp(dateValue, timeValue = '') {
  const rawDate = String(dateValue || '').trim()
  if (!rawDate) {
    return 0
  }

  const datePart = rawDate.includes('T') ? rawDate : rawDate.slice(0, 10)
  const rawTime = String(timeValue || '').trim()
  const timePart = rawDate.includes('T') ? '' : `T${rawTime || '00:00:00'}`
  const parsed = new Date(`${datePart}${timePart}`).getTime()
  return Number.isFinite(parsed) ? parsed : 0
}

function formatActivityDate(dateValue, timeValue = '') {
  const timestamp = toActivityTimestamp(dateValue, timeValue)
  if (!timestamp) {
    return '날짜 없음'
  }

  return new Intl.DateTimeFormat('ko-KR', {
    month: 'short',
    day: 'numeric',
    hour: timeValue ? '2-digit' : undefined,
    minute: timeValue ? '2-digit' : undefined,
  }).format(new Date(timestamp))
}

function buildRouteMetric(route) {
  const metrics = []
  const distanceKm = Number(route?.distanceKm || 0)
  const durationMinutes = Number(route?.durationMinutes || 0)
  const stepCount = Number(route?.stepCount || 0)
  if (distanceKm > 0) {
    metrics.push(`${distanceKm.toLocaleString('ko-KR', { maximumFractionDigits: 1 })}km`)
  }
  if (durationMinutes > 0) {
    metrics.push(`${durationMinutes.toLocaleString('ko-KR')}분`)
  }
  if (stepCount > 0) {
    metrics.push(`${stepCount.toLocaleString('ko-KR')}걸음`)
  }
  return metrics.join(' · ')
}

function buildRouteTitle(route) {
  return joinActivityMeta([route?.startPlaceName, route?.endPlaceName]) || '이동 경로'
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
    case 'share':
      return `공유 ${summary.shared}건`
    default:
      return ''
  }
}

async function loadTravelSummary() {
  travelSummaryLoading.value = true
  travelSummaryError.value = ''
  try {
    travelPortfolio.value = await fetchTravelPortfolio()
  } catch (error) {
    travelSummaryError.value = error.message || '여행 요약을 불러오지 못했습니다.'
  } finally {
    travelSummaryLoading.value = false
  }
}

function applyRouteState(route) {
  financeLegacyOpen.value = false
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
  financeLegacyOpen.value = false
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

const isHubVisible = computed(() =>
  primaryTab.value !== 'share'
  && primaryTab.value !== 'map'
  && (primaryTab.value !== 'finance' || financeLegacyOpen.value)
)
const isIntegratedPhotoMode = computed(() => primaryTab.value === 'photos')

watch(
  () => props.route,
  (route) => {
    applyRouteState(route)
  },
  { immediate: true },
)

onMounted(loadTravelSummary)
</script>

<template>
  <div class="workspace-stack travel-unified-shell">
    <section class="panel travel-record-switcher">
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
          @click="openMode(mode.key)"
        >
          <span>{{ mode.badge }}</span>
          <strong>{{ mode.label }}</strong>
          <small>{{ mode.meta }}</small>
          <em>{{ travelSummaryLoading ? '불러오는 중' : getModeMetric(mode.key) }}</em>
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
          <small>여행 화면은 지도, 장소 방문, GPX, 사진 기록에 집중하고 지출 데이터는 가계부 검색/통계/수정 이력과 함께 사용합니다.</small>
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

    <section class="panel travel-record-action-panel">
      <div class="travel-record-action-panel__main">
        <div class="panel__header">
          <div>
            <span class="panel__eyebrow">RECORD HUB</span>
            <h2>기록 작업</h2>
          </div>
          <span class="panel__badge">지도 중심</span>
        </div>
        <div class="travel-record-action-grid">
          <button
            v-for="action in travelRecordActions"
            :key="action.key"
            class="travel-record-action-card"
            :class="{ 'travel-record-action-card--active': primaryTab === action.key }"
            type="button"
            @click="openMode(action.key)"
          >
            <span>{{ action.meta }}</span>
            <strong>{{ action.label }}</strong>
            <small>{{ action.action }}</small>
          </button>
        </div>
      </div>
      <div class="travel-record-action-panel__stats">
        <article v-for="stat in travelRecordFocusStats" :key="stat.label">
          <span>{{ stat.label }}</span>
          <strong>{{ travelSummaryLoading ? '-' : stat.value.toLocaleString('ko-KR') }}</strong>
        </article>
      </div>
    </section>

    <section class="panel travel-place-index">
      <div class="panel__header">
        <div>
          <span class="panel__eyebrow">PLACE INDEX</span>
          <h2>방문 장소 정리</h2>
        </div>
        <span class="panel__badge">{{ travelVisitedPlaces.length }}곳</span>
      </div>
      <div v-if="travelVisitedPlaces.length" class="travel-place-index__grid">
        <button
          v-for="place in travelVisitedPlaces"
          :key="place.key"
          class="travel-place-index__card"
          type="button"
          @click="openMemories"
        >
          <span>{{ place.regionLabel || '지역 미지정' }}</span>
          <strong>{{ place.placeName }}</strong>
          <small>{{ place.planLabel }}</small>
          <em>
            방문 {{ place.visitCount }} · 사진 {{ place.photoCount }} · 경로 {{ place.routeCount }}
          </em>
        </button>
      </div>
      <div v-else class="travel-place-index__empty">
        <strong>아직 정리할 방문 장소가 없습니다.</strong>
        <button class="button button--primary" type="button" @click="openMemories">장소 기록하기</button>
      </div>
    </section>

    <section class="panel travel-record-timeline">
      <div class="panel__header">
        <div>
          <span class="panel__eyebrow">LATEST</span>
          <h2>최근 기록</h2>
        </div>
        <span class="panel__badge">{{ travelRecentActivities.length }}개</span>
      </div>
      <div v-if="travelRecentActivities.length" class="travel-record-timeline__grid">
        <button
          v-for="activity in travelRecentActivities"
          :key="activity.id"
          class="travel-record-timeline__item"
          :class="`travel-record-timeline__item--${activity.kind.toLowerCase()}`"
          type="button"
          @click="openMode(activity.mode)"
        >
          <span class="travel-record-timeline__thumb">
            <img v-if="activity.previewUrl" :src="activity.previewUrl" :alt="activity.title" loading="lazy" />
            <span v-else>{{ activity.label }}</span>
          </span>
          <span class="travel-record-timeline__copy">
            <span class="travel-record-timeline__meta">
              <strong>{{ activity.label }}</strong>
              <small>{{ activity.dateLabel }}</small>
            </span>
            <b>{{ activity.title }}</b>
            <small>{{ activity.meta || '기록 정보 없음' }}</small>
            <em v-if="activity.shared">공유됨</em>
          </span>
        </button>
      </div>
      <div v-else class="travel-record-timeline__empty">
        <strong>아직 표시할 최근 기록이 없습니다.</strong>
        <div class="travel-record-timeline__empty-actions">
          <button class="button button--primary" type="button" @click="openMemories">장소 기록하기</button>
          <button class="button button--ghost" type="button" @click="openRoutes">GPX 경로 추가</button>
        </div>
      </div>
    </section>

    <div v-show="primaryTab === 'map'" class="workspace-stack">
      <TravelMyMapWorkspace
        :active="primaryTab === 'map'"
        @open-memories="openMemories"
        @open-routes="openRoutes"
        @open-photos="openPhotos"
      />
    </div>

    <div v-if="primaryTab === 'share'" class="workspace-stack">
      <TravelPublicTripsWorkspace :active="primaryTab === 'share'" />
    </div>

    <section v-if="primaryTab === 'finance' && !financeLegacyOpen" class="panel travel-finance-bridge">
      <div class="panel__header">
        <div>
          <span class="panel__eyebrow">HOUSEHOLD LINK</span>
          <h2>여행 가계부는 가계부에서 관리합니다</h2>
          <p>여행 지출은 일반 가계부 데이터와 함께 저장하고, 여행 대시보드의 분류를 기준으로 따로 모아 봅니다.</p>
        </div>
        <span class="panel__badge">연계됨</span>
      </div>
      <div class="travel-finance-bridge__body">
        <article>
          <strong>새 여행 지출 입력</strong>
          <span>가계부의 거래 입력 흐름을 그대로 사용해서 여행 지출을 기록합니다.</span>
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
