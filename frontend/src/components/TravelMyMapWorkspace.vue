<script setup>
import { computed, onMounted, ref } from 'vue'
import { fetchTravelMyMapMarkerDetails, fetchTravelMyMapOverview } from '../lib/api'
import { buildThumbnailUrl } from '../lib/mediaPreview'
import { formatDateTime, safeNumber } from '../lib/uiFormat'
import TravelMapPanel from './TravelMapPanel.vue'

const isLoading = ref(false)
const isDetailLoading = ref(false)
const errorMessage = ref('')
const overview = ref(null)
const displayMode = ref('ALL')
const selectedMarkerId = ref(null)
const selectedBundleIds = ref([])
const markerDetailCache = ref(new Map())
const markerBundleCache = ref(new Map())

function setError(message = '') {
  errorMessage.value = message
}

function resolvePinPreset(category) {
  const text = String(category || '').trim().toLowerCase()

  if (!text) return { key: 'general', iconText: '📍' }
  if (text.includes('숙소') || text.includes('호텔') || text.includes('hotel') || text.includes('hostel')) return { key: 'lodging', iconText: '🛏' }
  if (text.includes('음식') || text.includes('식당') || text.includes('맛집') || text.includes('food') || text.includes('restaurant')) return { key: 'food', iconText: '🍴' }
  if (text.includes('카페') || text.includes('coffee') || text.includes('cafe')) return { key: 'cafe', iconText: '☕' }
  if (text.includes('박물관') || text.includes('전시') || text.includes('museum')) return { key: 'museum', iconText: '🏛' }
  if (text.includes('관광') || text.includes('명소') || text.includes('landmark') || text.includes('sight')) return { key: 'sightseeing', iconText: '🗽' }
  if (text.includes('쇼핑') || text.includes('shopping') || text.includes('mall')) return { key: 'shopping', iconText: '🛍' }
  if (text.includes('이동') || text.includes('교통') || text.includes('transit') || text.includes('버스') || text.includes('지하철') || text.includes('택시')) return { key: 'transit', iconText: '🚆' }
  return { key: 'general', iconText: '📍' }
}

function transportLabel(mode) {
  switch (String(mode || '').toUpperCase()) {
    case 'BUS':
      return '버스'
    case 'TAXI':
      return '택시'
    case 'TRAIN':
      return '기차'
    case 'SUBWAY':
      return '지하철'
    case 'CAR':
      return '자동차'
    case 'FLIGHT':
      return '항공'
    case 'ETC':
      return '기타'
    default:
      return '미정'
  }
}

function sourceLabel(sourceType) {
  return String(sourceType || '').toUpperCase() === 'GPX' ? 'GPX' : '직접 작성'
}

function lineStyleLabel(style) {
  switch (String(style || '').toUpperCase()) {
    case 'DASHED':
      return '점선'
    case 'DOTTED':
      return '도트'
    case 'LONG_DASH':
      return '긴 점선'
    default:
      return '실선'
  }
}

function routeSummary(route) {
  return [
    transportLabel(route.transportMode),
    route.distanceKm ? `${Number(route.distanceKm).toFixed(2)}km` : '',
    route.durationMinutes ? `${route.durationMinutes}분` : '',
    route.stepCount ? `${Number(route.stepCount).toLocaleString('ko-KR')}걸음` : '',
    lineStyleLabel(route.lineStyle),
    sourceLabel(route.sourceType),
  ].filter(Boolean).join(' / ')
}

async function loadOverview() {
  isLoading.value = true
  setError('')

  try {
    overview.value = await fetchTravelMyMapOverview()
  } catch (error) {
    setError(error.message)
  } finally {
    isLoading.value = false
  }
}

async function loadMarkerBundle(markerId) {
  if (!markerId) {
    return
  }

  const cachedBundleIds = markerBundleCache.value.get(String(markerId))
  if (cachedBundleIds?.length) {
    selectedBundleIds.value = cachedBundleIds
    return
  }

  isDetailLoading.value = true
  setError('')

  try {
    const bundle = await fetchTravelMyMapMarkerDetails(markerId)
    const nextCache = new Map(markerDetailCache.value)

    ;(bundle.markers ?? []).forEach((marker) => {
      nextCache.set(String(marker.id), marker)
    })

    markerDetailCache.value = nextCache
    const bundleIds = (bundle.markers ?? []).map((marker) => marker.id)
    markerBundleCache.value = new Map(markerBundleCache.value).set(String(markerId), bundleIds)
    selectedBundleIds.value = bundleIds
  } catch (error) {
    setError(error.message)
  } finally {
    isDetailLoading.value = false
  }
}

function handleSelectMarker(marker) {
  selectedMarkerId.value = marker?.id ?? null
  if (marker?.id) {
    loadMarkerBundle(marker.id)
  }
}

const modeLabel = computed(() => {
  switch (displayMode.value) {
    case 'PINS':
      return '핀만 보기'
    case 'ROUTES':
      return '경로만 보기'
    default:
      return '핀 + 경로 보기'
  }
})

const summary = computed(() => ({
  includedPlanCount: overview.value?.includedPlanCount ?? 0,
  markerCount: overview.value?.markerCount ?? 0,
  routeCount: overview.value?.routeCount ?? 0,
  totalDistanceKm: safeNumber(overview.value?.totalDistanceKm),
}))

const allMarkers = computed(() =>
  (overview.value?.markers ?? []).map((item) => {
    const detail = markerDetailCache.value.get(String(item.id))
    const preset = resolvePinPreset(item.category)

    return {
      ...item,
      ...detail,
      colorHex: item.planColorHex || '#3182F6',
      latitude: Number(item.latitude),
      longitude: Number(item.longitude),
      visitedDate: item.memoryDate,
      visitedTime: item.memoryTime,
      photoCount: detail?.photoCount || 0,
      receiptCount: 0,
      mediaItems: detail?.mediaItems || [],
      iconKey: preset.key,
      iconText: preset.iconText,
    }
  }),
)

const allRoutes = computed(() =>
  (overview.value?.routes ?? []).map((route) => ({
    ...route,
    lineColorHex: route.lineColorHex || route.planColorHex || '#3182F6',
    lineStyle: route.lineStyle || 'SOLID',
  })),
)

const visibleMarkers = computed(() => (displayMode.value === 'ROUTES' ? [] : allMarkers.value))
const visibleRoutes = computed(() => (displayMode.value === 'PINS' ? [] : allRoutes.value))

const selectedMarker = computed(() =>
  allMarkers.value.find((marker) => String(marker.id) === String(selectedMarkerId.value)) || null,
)

const nearbyMarkers = computed(() => {
  if (!selectedMarkerId.value || !selectedBundleIds.value.length) {
    return []
  }

  return selectedBundleIds.value
    .filter((id) => String(id) !== String(selectedMarkerId.value))
    .map((id) => allMarkers.value.find((marker) => String(marker.id) === String(id)))
    .filter(Boolean)
})

onMounted(() => {
  loadOverview()
})
</script>

<template>
  <div class="workspace-stack">
    <section class="panel">
      <div class="panel__header">
        <div>
          <h2>내 지도</h2>
          <p>지금까지 여행에서 기록한 장소 핀과 이동 경로를 한 장의 지도에서 모아 봅니다.</p>
        </div>
        <span class="panel__badge">{{ modeLabel }}</span>
      </div>

      <div class="travel-summary-grid">
        <article class="travel-stat-card">
          <span>포함 여행</span>
          <strong>{{ summary.includedPlanCount }}</strong>
          <small>내 지도에 포함된 전체 여행</small>
        </article>
        <article class="travel-stat-card">
          <span>등록된 핀</span>
          <strong>{{ summary.markerCount }}</strong>
          <small>좌표가 저장된 장소 기록</small>
        </article>
        <article class="travel-stat-card">
          <span>등록된 경로</span>
          <strong>{{ summary.routeCount }}</strong>
          <small>직접 작성과 GPX 경로 포함</small>
        </article>
        <article class="travel-stat-card">
          <span>누적 이동 거리</span>
          <strong>{{ summary.totalDistanceKm.toFixed(2) }}km</strong>
          <small>전체 여행 기준 거리 합계</small>
        </article>
      </div>
    </section>

    <section class="panel">
      <div class="scope-toggle">
        <button class="button" :class="{ 'button--primary': displayMode === 'ALL' }" @click="displayMode = 'ALL'">핀 + 경로</button>
        <button class="button" :class="{ 'button--primary': displayMode === 'PINS' }" @click="displayMode = 'PINS'">핀만</button>
        <button class="button" :class="{ 'button--primary': displayMode === 'ROUTES' }" @click="displayMode = 'ROUTES'">경로만</button>
      </div>
      <small class="field__hint">핀 상세는 지도를 눌렀을 때 그 핀과 주변 10개 정도만 추가로 불러옵니다.</small>
    </section>

    <section class="panel panel--map-fill travel-overview-map-panel">
      <div class="panel__header">
        <div>
          <h2>내 위치 지도</h2>
          <p>초기에는 요약 핀과 경로만 가볍게 불러오고, 핀을 누를 때만 해당 핀의 자세한 내용을 이어서 불러옵니다.</p>
        </div>
        <span class="panel__badge">핀 {{ visibleMarkers.length }}개 / 경로 {{ visibleRoutes.length }}개</span>
      </div>

      <p v-if="errorMessage" class="panel__empty">{{ errorMessage }}</p>
      <p v-else-if="isLoading" class="panel__empty">내 지도를 불러오는 중입니다...</p>
      <TravelMapPanel
        v-else
        :markers="visibleMarkers"
        :routes="visibleRoutes"
        :selected-point="null"
        :enable-pick-location="false"
        :enable-draw-route="false"
        :view-key="`my-map-${displayMode}-${summary.markerCount}-${summary.routeCount}`"
        initial-map-size="expanded"
        hint-title="내 지도 보기"
        hint-text="핀을 누르면 선택한 핀과 주변 핀의 자세한 정보를 추가로 불러와 아래에 보여줍니다."
        @select-marker="handleSelectMarker"
      />
    </section>

    <div class="content-grid content-grid--travel">
      <section class="panel">
        <div class="panel__header">
          <div>
            <h2>선택한 핀 정보</h2>
            <p>지금 보고 싶은 핀의 정보만 따로 불러와서 빠르게 확인합니다.</p>
          </div>
          <span class="panel__badge">{{ selectedMarker ? '불러옴' : '선택 대기' }}</span>
        </div>

        <div v-if="selectedMarker" class="travel-overview-place-list">
          <article class="travel-overview-place-card">
            <img
              v-if="selectedMarker.mediaItems?.[0]?.contentUrl"
              :src="buildThumbnailUrl(selectedMarker.mediaItems[0].contentUrl, 360)"
              :alt="selectedMarker.title || selectedMarker.placeName || '대표 사진'"
              class="travel-media-thumb"
            />
            <div class="travel-media-tags">
              <span class="chip chip--neutral">{{ selectedMarker.category || '장소' }}</span>
              <span class="chip chip--neutral">사진 {{ selectedMarker.photoCount || 0 }}장</span>
            </div>
            <strong>{{ selectedMarker.title || selectedMarker.placeName || '제목 없는 핀' }}</strong>
            <small>{{ selectedMarker.planName || '여행' }}</small>
            <small>{{ formatDateTime(selectedMarker.memoryDate, selectedMarker.memoryTime) }}</small>
            <small>{{ [selectedMarker.country, selectedMarker.region, selectedMarker.placeName].filter(Boolean).join(' / ') || '위치 미설정' }}</small>
            <small>{{ selectedMarker.memo || '메모 없음' }}</small>
          </article>
        </div>
        <p v-else-if="isDetailLoading" class="panel__empty">핀 상세를 불러오는 중입니다...</p>
        <p v-else class="panel__empty">지도에서 보고 싶은 핀을 눌러 상세를 확인해주세요.</p>
      </section>

      <section class="panel">
        <div class="panel__header">
          <div>
            <h2>주변 핀 미리보기</h2>
            <p>선택한 핀 근처의 기록도 함께 불러와서 이어서 확인할 수 있습니다.</p>
          </div>
          <span class="panel__badge">{{ nearbyMarkers.length }}개</span>
        </div>

        <div v-if="nearbyMarkers.length" class="travel-overview-place-list">
          <article v-for="marker in nearbyMarkers" :key="marker.id" class="travel-overview-place-card">
            <div class="travel-media-tags">
              <span class="chip chip--neutral">{{ marker.category || '장소' }}</span>
              <span class="chip chip--neutral">사진 {{ marker.photoCount || 0 }}장</span>
            </div>
            <strong>{{ marker.title || marker.placeName || '제목 없는 핀' }}</strong>
            <small>{{ marker.planName || '여행' }}</small>
            <small>{{ formatDateTime(marker.memoryDate, marker.memoryTime) }}</small>
            <small>{{ [marker.country, marker.region, marker.placeName].filter(Boolean).join(' / ') || '위치 미설정' }}</small>
          </article>
        </div>
        <p v-else class="panel__empty">핀을 누르면 근처 핀들이 여기에 같이 표시됩니다.</p>
      </section>
    </div>

    <section class="panel">
      <div class="panel__header">
        <div>
          <h2>전체 경로 목록</h2>
          <p>경로는 처음부터 지도에 가볍게 보여주되, 목록에서는 제목과 거리 중심으로 한 번 더 정리해 둡니다.</p>
        </div>
        <span class="panel__badge">{{ allRoutes.length }}개 경로</span>
      </div>

      <div class="sheet-table-wrap">
        <table class="sheet-table">
          <thead>
            <tr>
              <th>여행</th>
              <th>날짜</th>
              <th>제목</th>
              <th>요약</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="route in allRoutes" :key="route.id">
              <td>{{ route.planName }}</td>
              <td>{{ route.routeDate || '-' }}</td>
              <td>{{ route.title || '이동 경로' }}</td>
              <td>{{ routeSummary(route) }}</td>
            </tr>
            <tr v-if="!allRoutes.length">
              <td colspan="4" class="sheet-table__empty">아직 저장된 경로가 없습니다.</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </div>
</template>
