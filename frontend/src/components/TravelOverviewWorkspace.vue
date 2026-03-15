<script setup>
import { computed, ref, watch } from 'vue'
import { formatDate, formatDateTime, formatTime, safeNumber, todayIso, toIsoDate } from '../lib/uiFormat'
import TravelMapPanel from './TravelMapPanel.vue'

const props = defineProps({
  travelPlan: {
    type: Object,
    default: null,
  },
})

const activeScope = ref('ALL')

function resolvePinPreset(category) {
  const text = String(category || '').trim().toLowerCase()

  if (!text) return { key: 'general', iconText: '📍' }
  if (text.includes('숙소') || text.includes('호텔') || text.includes('hotel') || text.includes('hostel')) return { key: 'lodging', iconText: '🏠' }
  if (text.includes('음식') || text.includes('식당') || text.includes('맛집') || text.includes('food') || text.includes('restaurant')) return { key: 'food', iconText: '🍽' }
  if (text.includes('카페') || text.includes('coffee') || text.includes('cafe')) return { key: 'cafe', iconText: '☕' }
  if (text.includes('박물관') || text.includes('전시') || text.includes('museum')) return { key: 'museum', iconText: '🏛' }
  if (text.includes('관광') || text.includes('명소') || text.includes('landmark') || text.includes('sight')) return { key: 'sightseeing', iconText: '📸' }
  if (text.includes('쇼핑') || text.includes('shopping') || text.includes('mall')) return { key: 'shopping', iconText: '🛍' }
  if (text.includes('이동') || text.includes('교통') || text.includes('transit') || text.includes('버스') || text.includes('지하철') || text.includes('택시')) return { key: 'transit', iconText: '🚌' }
  return { key: 'general', iconText: '📍' }
}

const tripDays = computed(() => {
  const startText = props.travelPlan?.startDate || todayIso()
  const endText = props.travelPlan?.endDate || startText
  const start = new Date(`${startText}T00:00:00`)
  const end = new Date(`${endText}T00:00:00`)

  if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime()) || end < start) {
    return [{ index: 1, date: startText, label: '1일차' }]
  }

  const days = []
  const cursor = new Date(start)
  let index = 1

  while (cursor <= end) {
    days.push({
      index,
      date: toIsoDate(cursor),
      label: `${index}일차`,
    })
    cursor.setDate(cursor.getDate() + 1)
    index += 1
  }

  return days.length ? days : [{ index: 1, date: startText, label: '1일차' }]
})

const memoryRecords = computed(() => props.travelPlan?.memoryRecords ?? [])
const routeSegments = computed(() => props.travelPlan?.routeSegments ?? [])
const memoryMediaItems = computed(() =>
  (props.travelPlan?.mediaItems ?? []).filter((item) => item.recordType === 'MEMORY' && item.mediaType === 'PHOTO'),
)

const memoryMediaMap = computed(() => {
  const bucket = new Map()
  memoryMediaItems.value.forEach((media) => {
    const key = String(media.recordId)
    const current = bucket.get(key) ?? []
    current.push(media)
    bucket.set(key, current)
  })
  return bucket
})

const knownDates = computed(() => {
  const values = new Set(tripDays.value.map((day) => day.date))
  memoryRecords.value.forEach((memory) => {
    if (memory.memoryDate) {
      values.add(memory.memoryDate)
    }
  })
  routeSegments.value.forEach((route) => {
    if (route.routeDate) {
      values.add(route.routeDate)
    }
  })
  return [...values].sort((left, right) => left.localeCompare(right))
})

const scopeOptions = computed(() => [
  { key: 'ALL', label: '전체 보기', subtitle: `${tripDays.value.length}일 전체` },
  ...tripDays.value.map((day) => ({
    key: day.date,
    label: day.label,
    subtitle: formatDate(day.date),
  })),
])

const scopeLabel = computed(() => {
  if (activeScope.value === 'ALL') {
    return '전체 일정'
  }
  return tripDays.value.find((day) => day.date === activeScope.value)?.label || formatDate(activeScope.value)
})

const filteredMemories = computed(() =>
  memoryRecords.value
    .filter((memory) => activeScope.value === 'ALL' || memory.memoryDate === activeScope.value)
    .slice()
    .sort((left, right) => {
      const leftKey = `${left.memoryDate || ''} ${left.memoryTime || '99:99'} ${String(left.id).padStart(12, '0')}`
      const rightKey = `${right.memoryDate || ''} ${right.memoryTime || '99:99'} ${String(right.id).padStart(12, '0')}`
      return leftKey.localeCompare(rightKey)
    }),
)

const filteredRoutes = computed(() =>
  routeSegments.value
    .filter((route) => activeScope.value === 'ALL' || route.routeDate === activeScope.value)
    .slice()
    .sort((left, right) => {
      const leftKey = `${left.routeDate || ''} ${String(left.id).padStart(12, '0')}`
      const rightKey = `${right.routeDate || ''} ${String(right.id).padStart(12, '0')}`
      return leftKey.localeCompare(rightKey)
    }),
)

const overviewStats = computed(() => {
  const placeCount = filteredMemories.value.filter((item) => item.placeName || item.region || item.country).length
  const photoCount = filteredMemories.value.reduce((sum, item) => sum + (memoryMediaMap.value.get(String(item.id))?.length || 0), 0)
  const totalDistanceKm = filteredRoutes.value.reduce((sum, route) => sum + safeNumber(route.distanceKm), 0)
  const totalSteps = filteredRoutes.value.reduce((sum, route) => sum + safeNumber(route.stepCount), 0)

  return {
    placeCount,
    photoCount,
    routeCount: filteredRoutes.value.length,
    memoryCount: filteredMemories.value.length,
    totalDistanceKm,
    totalSteps,
  }
})

const mapMarkers = computed(() =>
  filteredMemories.value
    .filter((item) => item.latitude !== null && item.latitude !== undefined && item.longitude !== null && item.longitude !== undefined)
    .map((item) => {
      const preset = resolvePinPreset(item.category)
      return {
        id: item.id,
        planId: item.planId,
        planName: item.planName,
        colorHex: item.planColorHex || props.travelPlan?.colorHex || '#3182F6',
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

const mapRoutes = computed(() =>
  filteredRoutes.value.map((route) => ({
    ...route,
    lineColorHex: route.lineColorHex || route.planColorHex || props.travelPlan?.colorHex || '#3182F6',
    lineStyle: route.lineStyle || 'SOLID',
  })),
)

const dailySummaryCards = computed(() =>
  tripDays.value.map((day) => {
    const dayMemories = memoryRecords.value.filter((item) => item.memoryDate === day.date)
    const dayRoutes = routeSegments.value.filter((item) => item.routeDate === day.date)
    return {
      ...day,
      memoryCount: dayMemories.length,
      routeCount: dayRoutes.length,
      photoCount: dayMemories.reduce((sum, item) => sum + (memoryMediaMap.value.get(String(item.id))?.length || 0), 0),
      totalDistanceKm: dayRoutes.reduce((sum, route) => sum + safeNumber(route.distanceKm), 0),
    }
  }),
)

const placeCards = computed(() =>
  filteredMemories.value.map((item) => ({
    ...item,
    photoCount: memoryMediaMap.value.get(String(item.id))?.length || 0,
    locationLabel: [item.country, item.region, item.placeName].filter(Boolean).join(' / ') || '위치 미설정',
  })),
)

const timelineGroups = computed(() => {
  const targetDates = activeScope.value === 'ALL' ? knownDates.value : [activeScope.value]

  return targetDates.map((date) => {
    const memories = filteredMemories.value
      .filter((item) => item.memoryDate === date)
      .map((item) => ({
        id: `memory-${item.id}`,
        type: 'MEMORY',
        title: item.title || item.placeName || '여행 기록',
        time: item.memoryTime || '',
        summary: [item.country, item.region, item.placeName].filter(Boolean).join(' / ') || '-',
        memo: item.memo || '-',
        chip: item.category || '장소',
      }))

    const routes = filteredRoutes.value
      .filter((item) => item.routeDate === date)
      .map((item) => ({
        id: `route-${item.id}`,
        type: 'ROUTE',
        title: item.title || '이동 경로',
        time: '',
        summary: routeSummary(item),
        memo: [item.startPlaceName, item.endPlaceName].filter(Boolean).join(' -> ') || '-',
        chip: '경로',
      }))

    const items = [...memories, ...routes].sort((left, right) => {
      const leftKey = `${left.time || '99:99'}-${left.type}-${left.id}`
      const rightKey = `${right.time || '99:99'}-${right.type}-${right.id}`
      return leftKey.localeCompare(rightKey)
    })

    return {
      date,
      label: tripDays.value.find((day) => day.date === date)?.label || formatDate(date),
      items,
    }
  }).filter((group) => group.date && (activeScope.value !== 'ALL' || group.items.length))
})

watch(
  () => props.travelPlan?.id,
  () => {
    activeScope.value = 'ALL'
  },
  { immediate: true },
)

watch(
  knownDates,
  (dates) => {
    if (activeScope.value !== 'ALL' && !dates.includes(activeScope.value)) {
      activeScope.value = 'ALL'
    }
  },
  { deep: true },
)

function selectScope(scopeKey) {
  activeScope.value = scopeKey
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
      return '도보'
  }
}

function sourceLabel(sourceType) {
  return String(sourceType || '').toUpperCase() === 'GPX' ? 'GPX' : '직접 작성'
}

function routeSummary(route) {
  return [
    transportLabel(route.transportMode),
    route.distanceKm ? `${Number(route.distanceKm).toFixed(2)}km` : '',
    route.durationMinutes ? `${route.durationMinutes}분` : '',
    route.stepCount ? `${Number(route.stepCount).toLocaleString('ko-KR')}걸음` : '',
    lineStyleLabel(route.lineStyle),
    sourceLabel(route.sourceType),
  ]
    .filter(Boolean)
    .join(' / ')
}

function itemTypeLabel(type) {
  return type === 'ROUTE' ? '이동 경로' : '장소 기록'
}
</script>

<template>
  <div v-if="travelPlan" class="workspace-stack">
    <section class="panel">
      <div class="panel__header">
        <div>
          <h2>여행 보기</h2>
          <p>작성한 장소 기록과 이동 경로를 전체 또는 일차별로 한 번에 확인할 수 있습니다.</p>
        </div>
        <span class="panel__badge">{{ scopeLabel }}</span>
      </div>

      <div class="travel-summary-grid">
        <article class="travel-stat-card">
          <span>보이는 장소 기록</span>
          <strong>{{ overviewStats.memoryCount }}</strong>
          <small>핀과 메모가 연결된 기록 수</small>
        </article>
        <article class="travel-stat-card">
          <span>보이는 이동 경로</span>
          <strong>{{ overviewStats.routeCount }}</strong>
          <small>직접 작성과 GPX 경로 포함</small>
        </article>
        <article class="travel-stat-card">
          <span>이동 거리</span>
          <strong>{{ overviewStats.totalDistanceKm.toFixed(2) }}km</strong>
          <small>선택 범위 기준 누적 거리</small>
        </article>
        <article class="travel-stat-card">
          <span>장소 사진</span>
          <strong>{{ overviewStats.photoCount }}장</strong>
          <small>여행 기록에 연결된 사진 수</small>
        </article>
      </div>

      <div class="travel-day-tabs">
        <button
          v-for="option in scopeOptions"
          :key="option.key"
          class="travel-day-tabs__button"
          :class="{ 'is-active': activeScope === option.key }"
          type="button"
          @click="selectScope(option.key)"
        >
          <strong>{{ option.label }}</strong>
          <small>{{ option.subtitle }}</small>
        </button>
      </div>
    </section>

    <section class="panel panel--map-fill travel-overview-map-panel">
      <div class="panel__header">
        <div>
          <h2>{{ scopeLabel }} 지도</h2>
          <p>장소 핀과 이동 경로를 같은 지도에 겹쳐 보면서 하루 흐름이나 전체 동선을 확인할 수 있습니다.</p>
        </div>
        <span class="panel__badge">장소 {{ mapMarkers.length }}개 / 경로 {{ mapRoutes.length }}개</span>
      </div>

      <TravelMapPanel
        :markers="mapMarkers"
        :routes="mapRoutes"
        :selected-point="null"
        :enable-pick-location="false"
        :enable-draw-route="false"
        :view-key="`${travelPlan.id || 'overview'}-${activeScope}`"
        initial-map-size="expanded"
        hint-title="여행 전체 보기"
        hint-text="전체를 선택하면 모든 날짜의 장소와 경로를 함께 보고, 일차 버튼을 누르면 해당 날짜만 따로 확인할 수 있습니다."
      />
    </section>

    <section v-if="activeScope === 'ALL'" class="panel">
      <div class="panel__header">
        <div>
          <h2>일차별 요약</h2>
          <p>어느 날에 장소 기록과 이동 경로가 많았는지 빠르게 비교할 수 있습니다.</p>
        </div>
      </div>

      <div class="travel-overview-day-grid">
        <article v-for="day in dailySummaryCards" :key="day.date" class="travel-pending-card">
          <strong>{{ day.label }}</strong>
          <small>{{ formatDate(day.date) }}</small>
          <small>장소 {{ day.memoryCount }}개 / 경로 {{ day.routeCount }}개</small>
          <small>이동 {{ day.totalDistanceKm.toFixed(2) }}km / 사진 {{ day.photoCount }}장</small>
        </article>
      </div>
    </section>

    <section class="panel">
      <div class="panel__header">
        <div>
          <h2>{{ scopeLabel }} 타임라인</h2>
          <p>장소 기록과 이동 경로를 날짜 흐름대로 묶어 보며 여행의 전체 서사를 확인합니다.</p>
        </div>
      </div>

      <div v-if="timelineGroups.length" class="travel-overview-timeline">
        <article v-for="group in timelineGroups" :key="group.date" class="travel-overview-day-group">
          <div class="travel-overview-day-group__head">
            <div>
              <h3>{{ group.label }}</h3>
              <p>{{ formatDate(group.date) }}</p>
            </div>
            <span class="panel__badge">{{ group.items.length }}개 기록</span>
          </div>

          <div v-if="group.items.length" class="travel-pending-grid">
            <article v-for="item in group.items" :key="item.id" class="travel-pending-card">
              <strong>{{ itemTypeLabel(item.type) }}: {{ item.title }}</strong>
              <small>{{ item.time ? formatTime(item.time) : '-' }}</small>
              <small>{{ item.summary }}</small>
              <small>{{ item.memo }}</small>
              <div class="travel-media-tags">
                <span class="chip chip--neutral">{{ item.chip }}</span>
              </div>
            </article>
          </div>
          <p v-else class="panel__empty">이 날짜에는 아직 작성된 장소나 경로가 없습니다.</p>
        </article>
      </div>
      <p v-else class="panel__empty">표시할 여행 기록이 아직 없습니다.</p>
    </section>

    <div class="content-grid content-grid--travel">
      <section class="panel">
        <div class="panel__header">
          <div>
            <h2>{{ scopeLabel }} 장소 목록</h2>
            <p>기록된 장소와 메모, 사진 수를 지도와 함께 교차 확인할 수 있습니다.</p>
          </div>
          <span class="panel__badge">{{ placeCards.length }}곳</span>
        </div>

        <div v-if="placeCards.length" class="travel-overview-place-list">
          <article v-for="memory in placeCards" :key="memory.id" class="travel-overview-place-card">
            <div class="travel-media-tags">
              <span class="chip chip--neutral">{{ memory.category || '장소' }}</span>
              <span class="chip chip--neutral">사진 {{ memory.photoCount }}장</span>
            </div>
            <strong>{{ memory.title || memory.placeName || '제목 없는 기록' }}</strong>
            <small>{{ formatDateTime(memory.memoryDate, memory.memoryTime) }}</small>
            <small>{{ memory.locationLabel }}</small>
            <small>{{ memory.memo || '메모 없음' }}</small>
          </article>
        </div>
        <p v-else class="panel__empty">선택한 범위에 표시할 장소 기록이 없습니다.</p>
      </section>

      <section class="panel">
        <div class="panel__header">
          <div>
            <h2>{{ scopeLabel }} 경로 목록</h2>
            <p>저장된 이동 경로를 날짜와 스타일, 출발/도착 기준으로 다시 볼 수 있습니다.</p>
          </div>
          <span class="panel__badge">{{ filteredRoutes.length }}개 경로</span>
        </div>

        <div class="sheet-table-wrap">
          <table class="sheet-table">
            <thead>
              <tr>
                <th>날짜</th>
                <th>제목</th>
                <th>요약</th>
                <th>출발 / 도착</th>
                <th>포인트</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="route in filteredRoutes" :key="route.id">
                <td>{{ formatDate(route.routeDate) }}</td>
                <td>{{ route.title }}</td>
                <td>{{ routeSummary(route) }}</td>
                <td>{{ [route.startPlaceName, route.endPlaceName].filter(Boolean).join(' -> ') || '-' }}</td>
                <td>{{ route.points?.length || 0 }}</td>
              </tr>
              <tr v-if="!filteredRoutes.length">
                <td colspan="5" class="sheet-table__empty">선택한 범위에 표시할 이동 경로가 없습니다.</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </div>
  </div>

  <section v-else class="panel">
    <p class="panel__empty">먼저 여행을 만들거나 선택해 주세요.</p>
  </section>
</template>
