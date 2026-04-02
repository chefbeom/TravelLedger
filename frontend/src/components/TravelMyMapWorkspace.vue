<script setup>
import { computed, ref } from 'vue'
import { safeNumber } from '../lib/uiFormat'
import TravelMapPanel from './TravelMapPanel.vue'

const props = defineProps({
  portfolio: {
    type: Object,
    default: null,
  },
})

const displayMode = ref('ALL')

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

const memoryRecords = computed(() => props.portfolio?.memoryRecords ?? [])
const routeSegments = computed(() => props.portfolio?.routeSegments ?? [])
const mediaItems = computed(() => props.portfolio?.mediaItems ?? [])

const memoryMediaMap = computed(() => {
  const bucket = new Map()

  mediaItems.value.forEach((item) => {
    if (item.recordType !== 'MEMORY' || item.mediaType !== 'PHOTO') {
      return
    }

    const key = String(item.recordId)
    const current = bucket.get(key) ?? []
    current.push(item)
    bucket.set(key, current)
  })

  return bucket
})

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

const allMarkers = computed(() =>
  memoryRecords.value
    .filter((item) => item.latitude !== null && item.latitude !== undefined && item.longitude !== null && item.longitude !== undefined)
    .map((item) => {
      const preset = resolvePinPreset(item.category)
      return {
        id: item.id,
        planId: item.planId,
        planName: item.planName,
        colorHex: item.planColorHex || '#3182F6',
        latitude: Number(item.latitude),
        longitude: Number(item.longitude),
        country: item.country,
        region: item.region,
        placeName: item.placeName,
        title: item.title,
        visitedDate: item.memoryDate,
        visitedTime: item.memoryTime,
        photoCount: memoryMediaMap.value.get(String(item.id))?.length || 0,
        receiptCount: 0,
        mediaItems: memoryMediaMap.value.get(String(item.id)) || [],
        iconKey: preset.key,
        iconText: preset.iconText,
      }
    }),
)

const allRoutes = computed(() =>
  routeSegments.value.map((route) => ({
    ...route,
    lineColorHex: route.lineColorHex || route.planColorHex || '#3182F6',
    lineStyle: route.lineStyle || 'SOLID',
  })),
)

const visibleMarkers = computed(() => (displayMode.value === 'ROUTES' ? [] : allMarkers.value))
const visibleRoutes = computed(() => (displayMode.value === 'PINS' ? [] : allRoutes.value))

const summary = computed(() => ({
  includedPlanCount: props.portfolio?.includedPlanCount ?? 0,
  markerCount: allMarkers.value.length,
  routeCount: allRoutes.value.length,
  photoCount: mediaItems.value.filter((item) => item.recordType === 'MEMORY' && item.mediaType === 'PHOTO').length,
  totalDistanceKm: safeNumber(props.portfolio?.totalDistanceKm),
}))
</script>

<template>
  <div v-if="portfolio" class="workspace-stack">
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
          <small>지금까지 저장한 여행 전체</small>
        </article>
        <article class="travel-stat-card">
          <span>등록된 핀</span>
          <strong>{{ summary.markerCount }}</strong>
          <small>장소와 방문 기록 좌표</small>
        </article>
        <article class="travel-stat-card">
          <span>등록된 경로</span>
          <strong>{{ summary.routeCount }}</strong>
          <small>직접 그린 선과 GPX 경로</small>
        </article>
        <article class="travel-stat-card">
          <span>누적 이동 거리</span>
          <strong>{{ summary.totalDistanceKm.toFixed(2) }}km</strong>
          <small>사진 {{ summary.photoCount }}장 포함</small>
        </article>
      </div>
    </section>

    <section class="panel">
      <div class="scope-toggle">
        <button class="button" :class="{ 'button--primary': displayMode === 'ALL' }" @click="displayMode = 'ALL'">핀 + 경로</button>
        <button class="button" :class="{ 'button--primary': displayMode === 'PINS' }" @click="displayMode = 'PINS'">핀만</button>
        <button class="button" :class="{ 'button--primary': displayMode === 'ROUTES' }" @click="displayMode = 'ROUTES'">경로만</button>
      </div>
    </section>

    <section class="panel panel--map-fill travel-overview-map-panel">
      <div class="panel__header">
        <div>
          <h2>내 위치 지도</h2>
          <p>선택한 보기 기준에 따라 핀만, 경로만, 또는 둘 다 겹쳐서 확인할 수 있습니다.</p>
        </div>
        <span class="panel__badge">핀 {{ visibleMarkers.length }}개 / 경로 {{ visibleRoutes.length }}개</span>
      </div>

      <TravelMapPanel
        :markers="visibleMarkers"
        :routes="visibleRoutes"
        :selected-point="null"
        :enable-pick-location="false"
        :enable-draw-route="false"
        :view-key="`my-map-${displayMode}-${summary.markerCount}-${summary.routeCount}`"
        initial-map-size="expanded"
        hint-title="내 지도 보기"
        hint-text="지금까지 저장한 위치와 경로를 한 화면에서 확인하고, 보기 기준에 따라 원하는 정보만 남길 수 있습니다."
      />
    </section>
  </div>

  <section v-else class="panel">
    <p class="panel__empty">내 지도를 준비할 여행 데이터가 아직 없습니다.</p>
  </section>
</template>
