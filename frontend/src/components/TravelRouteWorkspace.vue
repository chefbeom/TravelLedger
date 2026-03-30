<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { formatDate, formatTime, safeNumber, todayIso, toIsoDate } from '../lib/uiFormat'
import TravelMapPanel from './TravelMapPanel.vue'

const props = defineProps({
  travelPlan: {
    type: Object,
    default: null,
  },
  isSubmitting: {
    type: Boolean,
    default: false,
  },
  activeSubmit: {
    type: String,
    default: '',
  },
  refreshKey: {
    type: Number,
    default: 0,
  },
})

const emit = defineEmits(['save-route', 'delete-route'])

const transportOptions = [
  { value: 'WALK', label: '도보' },
  { value: 'BUS', label: '버스' },
  { value: 'TAXI', label: '택시' },
  { value: 'TRAIN', label: '기차' },
  { value: 'SUBWAY', label: '지하철' },
  { value: 'CAR', label: '자동차' },
  { value: 'FLIGHT', label: '항공' },
  { value: 'ETC', label: '기타' },
]

const lineStyleOptions = [
  { value: 'SOLID', label: '실선' },
  { value: 'DASHED', label: '점선' },
  { value: 'DOTTED', label: '도트' },
  { value: 'LONG_DASH', label: '긴 점선' },
]

const draft = reactive({
  routeDate: todayIso(),
  title: '',
  transportMode: 'WALK',
  durationMinutes: '',
  stepCount: '',
  sourceType: 'MANUAL',
  startPlaceName: '',
  endPlaceName: '',
  lineColorHex: '#3182F6',
  lineStyle: 'SOLID',
  memo: '',
})

const draftPoints = ref([])
const gpxFileNames = ref([])
const gpxSelectedFiles = ref([])
const activeDayDate = ref('')
const highlightedDraftIndex = ref(-1)
const editingRouteId = ref(null)

const routeSegments = computed(() => props.travelPlan?.routeSegments ?? [])
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

const activeDay = computed(() => tripDays.value.find((day) => day.date === activeDayDate.value) || tripDays.value[0] || null)
const activeDayLabel = computed(() => {
  if (!activeDayDate.value) {
    return '전체 일정'
  }
  return activeDay.value?.label || formatDate(activeDayDate.value)
})
const selectedDayMemories = computed(() =>
  (props.travelPlan?.memoryRecords ?? []).filter((item) => {
    if (!activeDayDate.value) {
      return true
    }
    return item.memoryDate === activeDayDate.value
  }),
)

const orderedDayMemories = computed(() =>
  [...selectedDayMemories.value].sort((left, right) => {
    const leftKey = `${left.memoryDate || ''}-${left.memoryTime || '00:00'}-${String(left.id || '').padStart(12, '0')}`
    const rightKey = `${right.memoryDate || ''}-${right.memoryTime || '00:00'}-${String(right.id || '').padStart(12, '0')}`
    return leftKey.localeCompare(rightKey)
  }),
)

const memoryRouteSeedPoints = computed(() =>
  orderedDayMemories.value
    .filter((item) => item.latitude !== null && item.latitude !== undefined && item.longitude !== null && item.longitude !== undefined)
    .map((item, index) =>
      createDraftPoint(
        {
          latitude: Number(item.latitude),
          longitude: Number(item.longitude),
        },
        {
          pointType: 'MEMORY',
          linkedMemoryId: item.id ?? null,
          label: item.placeName || item.title || `기록 핀 ${index + 1}`,
        },
      ),
    ),
)

const routeMapMarkers = computed(() =>
  orderedDayMemories.value
    .filter((item) => item.latitude !== null && item.latitude !== undefined && item.longitude !== null && item.longitude !== undefined)
    .map((item) => ({
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
      mediaItems: [],
      photoCount: 0,
      receiptCount: 0,
      iconKey: item.category,
    })),
)

const routesForActiveDay = computed(() =>
  routeSegments.value.filter((route) => {
    if (!activeDayDate.value) {
      return true
    }
    return route.routeDate === activeDayDate.value
  }),
)

const mapRoutes = computed(() =>
  routesForActiveDay.value.map((route) => ({
    ...route,
    lineColorHex: route.lineColorHex || route.planColorHex || props.travelPlan?.colorHex || '#3182F6',
    lineStyle: route.lineStyle || 'SOLID',
  })),
)

const draftDistanceKm = computed(() => {
  if (draftPoints.value.length < 2) {
    return 0
  }

  let total = 0
  for (let index = 1; index < draftPoints.value.length; index += 1) {
    total += haversineDistance(draftPoints.value[index - 1], draftPoints.value[index])
  }
  return total
})

const estimatedSteps = computed(() => {
  if (draft.stepCount) {
    return Number(draft.stepCount)
  }
  return draft.transportMode === 'WALK' ? Math.round(draftDistanceKm.value * 1400) : 0
})

const hasGpxGeometry = computed(() => draft.sourceType === 'GPX' && draftPoints.value.length >= 2)
const isGpxMode = computed(() => draft.sourceType === 'GPX' || hasGpxGeometry.value)
const canPlaceRoutePoints = computed(() => draft.sourceType === 'MANUAL' && !hasGpxGeometry.value)
const showDraftPointMarkers = computed(() => canPlaceRoutePoints.value)
const gpxStartPoint = computed(() => draftPoints.value[0] ?? null)
const gpxEndPoint = computed(() => draftPoints.value[draftPoints.value.length - 1] ?? null)
const storedRoutePointCount = computed(() => compressRoutePointsForSave(draftPoints.value, hasGpxGeometry.value).length)
const gpxFileLabel = computed(() => {
  if (!gpxFileNames.value.length) {
    return ''
  }
  if (gpxFileNames.value.length === 1) {
    return gpxFileNames.value[0]
  }
  return `${gpxFileNames.value[0]} 외 ${gpxFileNames.value.length - 1}개`
})
const routePointBadge = computed(() => (hasGpxGeometry.value ? `${draftPoints.value.length}개 트랙 포인트` : `${draftPoints.value.length}개 제어점`))
const routeMapHintTitle = computed(() =>
  isGpxMode.value ? 'GPX는 방문 장소 핀이 아니라 이동 경로 선으로만 표시됩니다' : '지도를 눌러 경로 제어점을 순서대로 추가합니다',
)
const routeMapHintText = computed(() =>
  isGpxMode.value
    ? '방문한 장소는 여행 기록 핀으로 따로 보이고, GPX는 이동한 길만 선으로 그립니다. 거리와 시간은 GPX 데이터로 계산합니다.'
    : highlightedDraftIndex.value >= 0
      ? `${selectedDraftPointLabel.value} 뒤에 새 경로 핀을 넣으려면 지도를 클릭하세요.`
      : '여행 기록에서 저장한 장소 핀을 참고하면서, 지도 클릭으로 출발-경유-도착 제어점을 추가하고 드래그로 미세 조정할 수 있습니다.',
)

const selectedDraftPoint = computed(() => {
  if (!canPlaceRoutePoints.value || highlightedDraftIndex.value < 0) {
    return null
  }
  return draftPoints.value[highlightedDraftIndex.value] ?? null
})

const selectedDraftPointLabel = computed(() => {
  if (!canPlaceRoutePoints.value || highlightedDraftIndex.value < 0) {
    return '선택한 제어점 없음'
  }
  return describeDraftPoint(highlightedDraftIndex.value, draftPoints.value.length)
})

const draftPointRows = computed(() => {
  if (!canPlaceRoutePoints.value) {
    return []
  }

  return draftPoints.value.map((point, index) => ({
    index,
    label: describeDraftPoint(index, draftPoints.value.length),
    latitude: Number(point.latitude),
    longitude: Number(point.longitude),
  }))
})

const selectedDraftPointDisplayLabel = computed(() => {
  if (!canPlaceRoutePoints.value || highlightedDraftIndex.value < 0) {
    return '?좏깮???쒖뼱???놁쓬'
  }
  return buildDraftPointDisplayLabel(draftPoints.value[highlightedDraftIndex.value], highlightedDraftIndex.value, draftPoints.value.length)
})

const draftPointRowsDetailed = computed(() =>
  draftPointRows.value.map((row) => ({
    ...row,
    label: buildDraftPointDisplayLabel(draftPoints.value[row.index], row.index, draftPoints.value.length),
    kindLabel: draftPoints.value[row.index]?.pointType === 'MEMORY' ? '여행 기록 핀' : '경로 핀',
  })),
)

const activeDayTimeline = computed(() => {
  const rows = []

  selectedDayMemories.value.forEach((memory) => {
    rows.push({
      id: `memory-${memory.id}`,
      type: 'MEMORY',
      date: memory.memoryDate || '',
      title: memory.title || memory.placeName || '여행 기록',
      time: memory.memoryTime || '',
      summary: [memory.country, memory.region, memory.placeName].filter(Boolean).join(' / ') || '-',
      memo: memory.memo || '-',
    })
  })

  routesForActiveDay.value.forEach((route) => {
    rows.push({
      id: `route-${route.id}`,
      type: 'ROUTE',
      date: route.routeDate || '',
      title: route.title,
      time: '',
      summary: routeSummary(route) || '-',
      memo: [route.startPlaceName, route.endPlaceName].filter(Boolean).join(' -> ') || '-',
    })
  })

  return rows.sort((left, right) => {
    const leftKey = `${left.date || ''} ${left.time || '99:99'}-${left.id}`
    const rightKey = `${right.date || ''} ${right.time || '99:99'}-${right.id}`
    return leftKey.localeCompare(rightKey)
  })
})

const activeDayRouteStats = computed(() => {
  const totalDistanceKm = routesForActiveDay.value.reduce((sum, route) => sum + safeNumber(route.distanceKm), 0)
  const totalDurationMinutes = routesForActiveDay.value.reduce((sum, route) => sum + safeNumber(route.durationMinutes), 0)
  const totalSteps = routesForActiveDay.value.reduce((sum, route) => sum + safeNumber(route.stepCount), 0)

  return {
    totalDistanceKm,
    totalDurationMinutes,
    totalSteps,
  }
})

watch(
  () => props.travelPlan?.id,
  () => {
    initializeDaySelection()
    resetDraft()
  },
  { immediate: true },
)

watch(
  () => props.refreshKey,
  () => {
    initializeDaySelection()
    resetDraft()
  },
)

watch(
  tripDays,
  (days) => {
    if (activeDayDate.value && !days.some((day) => day.date === activeDayDate.value)) {
      activeDayDate.value = days[0]?.date || props.travelPlan?.startDate || todayIso()
    }
    if (!editingRouteId.value || activeDayDate.value) {
      draft.routeDate = activeDayDate.value || props.travelPlan?.startDate || todayIso()
    }
  },
  { deep: true },
)

watch(
  () => activeDayDate.value,
  (value) => {
    if (editingRouteId.value && !value && draft.routeDate) {
      return
    }
    draft.routeDate = value || props.travelPlan?.startDate || todayIso()
  },
)

function initializeDaySelection() {
  activeDayDate.value = tripDays.value[0]?.date || props.travelPlan?.startDate || todayIso()
  draft.routeDate = activeDayDate.value
  highlightedDraftIndex.value = -1
}

function selectTripDay(day) {
  activeDayDate.value = day.date
  draft.routeDate = day.date
  highlightedDraftIndex.value = -1
}

function selectAllDays() {
  activeDayDate.value = ''
  highlightedDraftIndex.value = -1
}

function transportLabel(mode) {
  return transportOptions.find((option) => option.value === mode)?.label || mode || '기타'
}

function sourceLabel(sourceType) {
  return sourceType === 'GPX' ? 'GPX 불러오기' : '직접 그리기'
}

function lineStyleLabel(style) {
  return lineStyleOptions.find((option) => option.value === style)?.label || '실선'
}

function timelineTypeLabel(type) {
  return type === 'ROUTE' ? '이동 경로' : '여행 기록'
}

function haversineDistance(from, to) {
  const toRadians = (value) => (value * Math.PI) / 180
  const earthRadiusKm = 6371
  const dLat = toRadians(Number(to.latitude) - Number(from.latitude))
  const dLon = toRadians(Number(to.longitude) - Number(from.longitude))
  const lat1 = toRadians(Number(from.latitude))
  const lat2 = toRadians(Number(to.latitude))

  const a =
    Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.sin(dLon / 2) * Math.sin(dLon / 2) * Math.cos(lat1) * Math.cos(lat2)

  return earthRadiusKm * (2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a)))
}

function describeDraftPoint(index, total) {
  if (index === 0) return '출발 제어점'
  if (index === total - 1) return '도착 제어점'
  return `${index + 1}번 경유 제어점`
}

function nextRoutePinLabel(points = draftPoints.value) {
  const routePinCount = (points ?? []).filter((point) => point?.pointType === 'ROUTE').length
  return `경로 핀 ${routePinCount + 1}`
}

function syncDraftPointMetadata(points) {
  let routePinCount = 0

  return (points ?? []).map((point) => {
    const normalized = createDraftPoint(point, {
      pointType: point?.pointType === 'MEMORY' ? 'MEMORY' : 'ROUTE',
      linkedMemoryId: point?.linkedMemoryId ?? null,
      label: point?.label || '',
    })

    if (normalized.pointType === 'ROUTE') {
      routePinCount += 1
      return {
        ...normalized,
        label: `경로 핀 ${routePinCount}`,
      }
    }

    return {
      ...normalized,
      label: normalized.label || '기록 핀',
    }
  })
}

function setDraftPoints(nextPoints, nextHighlightedIndex = highlightedDraftIndex.value) {
  draftPoints.value = syncDraftPointMetadata(nextPoints)
  if (!draftPoints.value.length) {
    highlightedDraftIndex.value = -1
    return
  }
  highlightedDraftIndex.value = Math.max(-1, Math.min(nextHighlightedIndex, draftPoints.value.length - 1))
}

function createDraftPoint(point, overrides = {}) {
  return {
    latitude: Number(Number(point.latitude).toFixed(7)),
    longitude: Number(Number(point.longitude).toFixed(7)),
    pointType: overrides.pointType || point.pointType || 'ROUTE',
    linkedMemoryId: overrides.linkedMemoryId ?? point.linkedMemoryId ?? null,
    label: overrides.label || point.label || '',
  }
}

function buildDraftPointDisplayLabel(point, index, total) {
  if (point?.label) {
    return point.label
  }
  return describeDraftPoint(index, total)
}

function moveDraftPointByOffset(index, offset) {
  const targetIndex = index + offset
  if (index < 0 || targetIndex < 0 || targetIndex >= draftPoints.value.length) {
    return
  }

  const reordered = [...draftPoints.value]
  const [movedPoint] = reordered.splice(index, 1)
  reordered.splice(targetIndex, 0, movedPoint)
  setDraftPoints(reordered, targetIndex)
}

function applyMemoryPinsToDraft() {
  if (memoryRouteSeedPoints.value.length < 2) {
    return
  }

  draft.sourceType = 'MANUAL'
  gpxFileNames.value = []
  gpxSelectedFiles.value = []
  setDraftPoints(memoryRouteSeedPoints.value.map((point) => ({ ...point })), -1)

  if (!draft.title.trim()) {
    draft.title = `${activeDayLabel.value} 경로`
  }

  draft.startPlaceName = memoryRouteSeedPoints.value[0]?.label || draft.startPlaceName
  draft.endPlaceName = memoryRouteSeedPoints.value[memoryRouteSeedPoints.value.length - 1]?.label || draft.endPlaceName
  if (!draft.lineColorHex) {
    draft.lineColorHex = props.travelPlan?.colorHex || '#3182F6'
  }
}

function formatCoordinate(point) {
  if (!point) {
    return '-'
  }
  return `${Number(point.latitude).toFixed(5)}, ${Number(point.longitude).toFixed(5)}`
}

function thinRoutePoints(points, maxPoints = 900) {
  if (!Array.isArray(points) || points.length <= maxPoints) {
    return points
  }

  const reduced = []
  const step = Math.max(1, Math.ceil((points.length - 1) / Math.max(1, maxPoints - 1)))

  for (let index = 0; index < points.length; index += step) {
    reduced.push(points[index])
  }

  const lastPoint = points[points.length - 1]
  const reducedLastPoint = reduced[reduced.length - 1]
  if (reducedLastPoint !== lastPoint) {
    reduced.push(lastPoint)
  }

  return reduced
}

function compressRoutePointsForSave(points, isGpxRoute) {
  const normalizedPoints = [...(points ?? [])].filter((point) => Number.isFinite(Number(point.latitude)) && Number.isFinite(Number(point.longitude)))
  if (!isGpxRoute) {
    return normalizedPoints
  }
  return thinRoutePoints(normalizedPoints, 900)
}

function stripGpxExtension(fileName) {
  return String(fileName || '').replace(/\.gpx$/i, '')
}

function mergeTrackPoints(trackBatches) {
  const merged = []

  trackBatches.forEach((track) => {
    track.points.forEach((point) => {
      const previous = merged[merged.length - 1]
      if (
        previous &&
        Number(previous.latitude) === Number(point.latitude) &&
        Number(previous.longitude) === Number(point.longitude)
      ) {
        return
      }

      merged.push({
        latitude: Number(point.latitude.toFixed(7)),
        longitude: Number(point.longitude.toFixed(7)),
      })
    })
  })

  return merged
}

async function parseGpxFile(file) {
  const xmlText = await file.text()
  const parser = new DOMParser()
  const xml = parser.parseFromString(xmlText, 'application/xml')
  const points = [...xml.querySelectorAll('trkpt, rtept')]
    .map((node) => ({
      latitude: Number(node.getAttribute('lat')),
      longitude: Number(node.getAttribute('lon')),
      time: node.querySelector('time')?.textContent || '',
    }))
    .filter((point) => Number.isFinite(point.latitude) && Number.isFinite(point.longitude))

  if (points.length < 2) {
    return null
  }

  const timestamps = points
    .map((point) => point.time)
    .filter(Boolean)
    .map((value) => new Date(value))
    .filter((value) => !Number.isNaN(value.getTime()))

  return {
    file,
    fileName: file.name,
    points,
    startTimestamp: timestamps[0] ?? null,
    endTimestamp: timestamps[timestamps.length - 1] ?? null,
    durationMinutes: timestamps.length >= 2 ? Math.max(0, Math.round((timestamps[timestamps.length - 1] - timestamps[0]) / 60000)) : 0,
  }
}

function addDraftPoint(point) {
  const insertionIndex = highlightedDraftIndex.value >= 0
    ? Math.min(highlightedDraftIndex.value + 1, draftPoints.value.length)
    : draftPoints.value.length
  const nextPoints = [...draftPoints.value]
  nextPoints.splice(
    insertionIndex,
    0,
    createDraftPoint(point, {
      pointType: 'ROUTE',
      label: nextRoutePinLabel(nextPoints),
    }),
  )
  setDraftPoints(nextPoints, insertionIndex)

  if (draft.sourceType === 'GPX') {
    draft.sourceType = 'MANUAL'
    gpxFileNames.value = []
    gpxSelectedFiles.value = []
  }
}

function removeLastPoint() {
  setDraftPoints(draftPoints.value.slice(0, -1))
}

function removeDraftPoint(index) {
  setDraftPoints(
    draftPoints.value.filter((_, pointIndex) => pointIndex !== index),
    highlightedDraftIndex.value > index ? highlightedDraftIndex.value - 1 : highlightedDraftIndex.value,
  )
}

function updateDraftPoint(index, key, rawValue) {
  const numeric = Number(rawValue)
  if (!Number.isFinite(numeric)) {
    return
  }

  draftPoints.value = draftPoints.value.map((point, pointIndex) => {
    if (pointIndex !== index) {
      return point
    }

    return {
      ...point,
      [key]: Number(numeric.toFixed(7)),
    }
  })
}

function updateSelectedDraftPoint(key, rawValue) {
  if (highlightedDraftIndex.value < 0) {
    return
  }
  updateDraftPoint(highlightedDraftIndex.value, key, rawValue)
}

function focusDraftPoint(index) {
  highlightedDraftIndex.value = index
}

function focusRoutePointFromMarker(marker) {
  if (!marker || !draftPoints.value.length) {
    return
  }

  const linkedIndex = draftPoints.value.findIndex((point) =>
    point.pointType === 'MEMORY'
      && point.linkedMemoryId != null
      && String(point.linkedMemoryId) === String(marker.id),
  )

  if (linkedIndex >= 0) {
    highlightedDraftIndex.value = linkedIndex
    return
  }

  const coordinateIndex = draftPoints.value.findIndex((point) =>
    Number(point.latitude) === Number(marker.latitude)
      && Number(point.longitude) === Number(marker.longitude),
  )

  if (coordinateIndex >= 0) {
    highlightedDraftIndex.value = coordinateIndex
  }
}

function focusPreviousPoint() {
  if (highlightedDraftIndex.value > 0) {
    highlightedDraftIndex.value -= 1
  }
}

function focusNextPoint() {
  if (highlightedDraftIndex.value < draftPoints.value.length - 1) {
    highlightedDraftIndex.value += 1
  }
}

function handleMoveDraftPoint(payload) {
  if (!payload || !Number.isInteger(payload.index) || !payload.point) {
    return
  }

  setDraftPoints(draftPoints.value.map((point, index) => {
    if (index !== payload.index) {
      return point
    }
    return {
      ...point,
      latitude: payload.point.latitude,
      longitude: payload.point.longitude,
    }
  }), payload.index)
}

function resetDraft() {
  editingRouteId.value = null
  draft.title = ''
  draft.transportMode = 'WALK'
  draft.durationMinutes = ''
  draft.stepCount = ''
  draft.sourceType = 'MANUAL'
  draft.startPlaceName = ''
  draft.endPlaceName = ''
  draft.lineColorHex = props.travelPlan?.colorHex || '#3182F6'
  draft.lineStyle = 'SOLID'
  draft.memo = ''
  setDraftPoints([], -1)
  gpxFileNames.value = []
  gpxSelectedFiles.value = []
  draft.routeDate = activeDayDate.value || props.travelPlan?.startDate || todayIso()
}

function startEditRoute(route) {
  if (!route) {
    return
  }

  editingRouteId.value = route.id
  activeDayDate.value = tripDays.value.some((day) => day.date === route.routeDate) ? route.routeDate : ''
  draft.routeDate = route.routeDate || props.travelPlan?.startDate || todayIso()
  draft.title = route.title || ''
  draft.transportMode = route.transportMode || 'WALK'
  draft.durationMinutes = route.durationMinutes != null ? String(route.durationMinutes) : ''
  draft.stepCount = route.stepCount != null ? String(route.stepCount) : ''
  draft.sourceType = route.sourceType || 'MANUAL'
  draft.startPlaceName = route.startPlaceName || ''
  draft.endPlaceName = route.endPlaceName || ''
  draft.lineColorHex = route.lineColorHex || props.travelPlan?.colorHex || '#3182F6'
  draft.lineStyle = route.lineStyle || 'SOLID'
  draft.memo = route.memo || ''
  setDraftPoints((route.points ?? []).map((point, index) =>
    createDraftPoint(
      {
        latitude: Number(point.latitude),
        longitude: Number(point.longitude),
        pointType: point.pointType,
        linkedMemoryId: point.linkedMemoryId,
        label: point.label,
      },
      {
        pointType: point.pointType || 'ROUTE',
        linkedMemoryId: point.linkedMemoryId,
        label: point.label || describeDraftPoint(index, (route.points ?? []).length),
      },
    ),
  ), -1)
  gpxFileNames.value = Array.isArray(route.gpxFileNames) ? [...route.gpxFileNames] : []
  gpxSelectedFiles.value = []
}

function buildPayload() {
  const pointsForSave = compressRoutePointsForSave(draftPoints.value, hasGpxGeometry.value)

  return {
    id: editingRouteId.value,
    routeDate: draft.routeDate,
    title: draft.title.trim(),
    transportMode: draft.transportMode,
    distanceKm: Number(draftDistanceKm.value.toFixed(3)),
    durationMinutes: safeNumber(draft.durationMinutes, 0),
    stepCount: draft.stepCount ? safeNumber(draft.stepCount, 0) : estimatedSteps.value,
    sourceType: hasGpxGeometry.value ? 'GPX' : draft.sourceType,
    startPlaceName: draft.startPlaceName.trim() || null,
    endPlaceName: draft.endPlaceName.trim() || null,
    lineColorHex: draft.lineColorHex,
    lineStyle: draft.lineStyle,
    memo: draft.memo.trim() || null,
    gpxFiles: hasGpxGeometry.value ? [...gpxSelectedFiles.value] : [],
    points: pointsForSave.map((point) => ({
      latitude: Number(point.latitude),
      longitude: Number(point.longitude),
      pointType: point.pointType || 'ROUTE',
      linkedMemoryId: point.linkedMemoryId ?? null,
      label: point.label || null,
    })),
  }
}

function submitRoute() {
  if (!props.travelPlan || draftPoints.value.length < 2 || !draft.title.trim()) {
    return
  }

  emit('save-route', buildPayload())
}

async function handleGpxSelection(event) {
  const files = [...(event.target.files ?? [])]
  if (!files.length) {
    return
  }

  const parsedTracks = (await Promise.all(files.map((file) => parseGpxFile(file)))).filter(Boolean)
  if (!parsedTracks.length) {
    return
  }

  const orderedTracks = parsedTracks
    .map((track, index) => ({ ...track, orderIndex: index }))
    .sort((left, right) => {
      const leftTime = left.startTimestamp?.getTime() ?? Number.POSITIVE_INFINITY
      const rightTime = right.startTimestamp?.getTime() ?? Number.POSITIVE_INFINITY
      if (leftTime === rightTime) {
        return left.orderIndex - right.orderIndex
      }
      return leftTime - rightTime
    })

  draftPoints.value = mergeTrackPoints(orderedTracks)
  highlightedDraftIndex.value = -1
  draft.title = orderedTracks.length === 1
    ? stripGpxExtension(orderedTracks[0].fileName)
    : `${stripGpxExtension(orderedTracks[0].fileName)} 외 ${orderedTracks.length - 1}개`
  draft.transportMode = 'WALK'
  draft.sourceType = 'GPX'
  gpxFileNames.value = orderedTracks.map((track) => track.fileName)
  gpxSelectedFiles.value = orderedTracks.map((track) => track.file)

  const totalDurationMinutes = orderedTracks.reduce((sum, track) => sum + track.durationMinutes, 0)
  if (totalDurationMinutes > 0) {
    draft.durationMinutes = String(totalDurationMinutes)
  }

  const selectedRouteDate = activeDayDate.value || draft.routeDate || props.travelPlan?.startDate || todayIso()
  const firstTrackDate = orderedTracks.find((track) => track.startTimestamp)?.startTimestamp
  if (selectedRouteDate) {
    draft.routeDate = selectedRouteDate
  } else if (firstTrackDate) {
    const detectedRouteDate = toIsoDate(firstTrackDate)
    draft.routeDate = detectedRouteDate
    if (tripDays.value.some((day) => day.date === detectedRouteDate)) {
      activeDayDate.value = detectedRouteDate
    }
  }

  draft.stepCount = String(Math.round(draftDistanceKm.value * 1400))
  event.target.value = ''
}

function routeSummary(route) {
  return [
    transportLabel(route.transportMode),
    route.distanceKm ? `${Number(route.distanceKm).toFixed(2)}km` : '',
    route.durationMinutes ? `${route.durationMinutes}분` : '',
    route.stepCount ? `${Number(route.stepCount).toLocaleString('ko-KR')}걸음` : '',
    route.lineStyle ? lineStyleLabel(route.lineStyle) : '',
    route.sourceType ? sourceLabel(route.sourceType) : '',
  ]
    .filter(Boolean)
    .join(' / ')
}
</script>

<template>
  <div v-if="travelPlan" class="workspace-stack">
    <section class="panel">
      <div class="panel__header">
        <div>
          <h2>이동 경로 작성</h2>
          <p>여행 기간에 맞게 일차 버튼이 자동으로 생기고, 선택한 날짜 기준으로 경로를 만들 수 있습니다.</p>
        </div>
        <span class="panel__badge">{{ routeSegments.length }}개 경로</span>
      </div>

      <div class="travel-day-tabs">
        <button
          class="travel-day-tabs__button"
          :class="{ 'is-active': !activeDayDate }"
          type="button"
          @click="selectAllDays"
        >
          <strong>전체 일정</strong>
          <small>모든 날짜</small>
        </button>
        <button
          v-for="day in tripDays"
          :key="day.date"
          class="travel-day-tabs__button"
          :class="{ 'is-active': activeDayDate === day.date }"
          type="button"
          @click="selectTripDay(day)"
        >
          <strong>{{ day.label }}</strong>
          <small>{{ formatDate(day.date) }}</small>
        </button>
      </div>

      <div class="travel-file-chip-row">
        <span class="chip chip--neutral">현재 선택 {{ activeDayLabel }}</span>
        <span class="chip chip--neutral">선택 범위 경로 {{ routesForActiveDay.length }}개</span>
        <span class="chip chip--neutral">선택 범위 기록 핀 {{ routeMapMarkers.length }}개</span>
        <span class="chip chip--neutral">경로 생성용 핀 {{ memoryRouteSeedPoints.length }}개</span>
        <span class="chip chip--neutral">선택 범위 이동 {{ activeDayRouteStats.totalDistanceKm.toFixed(2) }}km</span>
      </div>
    </section>

    <section class="panel panel--map-fill travel-route-map-panel">
      <div class="panel__header">
        <div>
          <h2>경로 지도</h2>
          <p>수동 작성은 제어점을 추가해 경로를 만들고, GPX는 방문 핀 없이 이동 선만 표시합니다.</p>
        </div>
      </div>

      <TravelMapPanel
        :markers="routeMapMarkers"
        :routes="mapRoutes"
        :draft-path="draftPoints"
        :draft-path-color-hex="draft.lineColorHex"
        :draft-path-line-style="draft.lineStyle"
        :show-draft-point-markers="showDraftPointMarkers"
        :selected-point="null"
        :enable-pick-location="false"
        :enable-draw-route="canPlaceRoutePoints"
        :draggable-draft-path="canPlaceRoutePoints"
        :highlighted-draft-index="highlightedDraftIndex"
        :view-key="`${travelPlan.id || 'route'}-${activeDayDate}`"
        initial-map-size="expanded"
        :hint-title="routeMapHintTitle"
        :hint-text="routeMapHintText"
        @pick-route-point="addDraftPoint"
        @move-draft-point="handleMoveDraftPoint"
        @select-draft-point="focusDraftPoint"
        @select-marker="focusRoutePointFromMarker"
      />

      <div class="travel-route-map-footer">
        <article class="travel-route-focus-card">
          <div class="travel-route-focus-card__header">
            <div>
              <h3>{{ hasGpxGeometry ? 'GPX 추출 정보' : '선택 제어점 편집' }}</h3>
              <p>
                {{
                  hasGpxGeometry
                    ? 'GPX는 방문 장소 핀이 아니라 이동 경로 선으로만 표시합니다. 아래 값은 GPX에서 읽은 시작점과 종료점입니다.'
                    : '지도를 누른 직후 여기서 위도와 경도를 바로 조정할 수 있습니다.'
                }}
              </p>
            </div>
            <span class="panel__badge">
              {{ hasGpxGeometry ? '선 경로 모드' : highlightedDraftIndex >= 0 ? `${highlightedDraftIndex + 1}번 제어점` : '대기 중' }}
            </span>
          </div>

          <template v-if="hasGpxGeometry">
            <div class="travel-route-focus-grid">
              <label class="field">
                <span class="field__label">GPX 파일</span>
                <input :value="gpxFileLabel || '-'" type="text" readonly />
              </label>
              <label class="field">
                <span class="field__label">시작 좌표</span>
                <input :value="formatCoordinate(gpxStartPoint)" type="text" readonly />
              </label>
              <label class="field">
                <span class="field__label">종료 좌표</span>
                <input :value="formatCoordinate(gpxEndPoint)" type="text" readonly />
              </label>
            </div>
            <p class="travel-map-note">방문한 장소는 여행 기록 핀으로 따로 관리하고, GPX는 지나간 길만 선으로 저장합니다.</p>
          </template>
          <template v-else-if="selectedDraftPoint">
            <div class="travel-route-focus-grid">
              <label class="field">
                <span class="field__label">제어점 역할</span>
                <input :value="selectedDraftPointDisplayLabel" type="text" readonly />
              </label>
              <label class="field">
                <span class="field__label">위도</span>
                <input :value="selectedDraftPoint.latitude" type="number" step="0.0000001" @change="updateSelectedDraftPoint('latitude', $event.target.value)" />
              </label>
              <label class="field">
                <span class="field__label">경도</span>
                <input :value="selectedDraftPoint.longitude" type="number" step="0.0000001" @change="updateSelectedDraftPoint('longitude', $event.target.value)" />
              </label>
            </div>
            <div class="entry-editor__actions">
              <button class="button button--ghost" type="button" :disabled="highlightedDraftIndex <= 0" @click="focusPreviousPoint">이전 제어점</button>
              <button class="button button--ghost" type="button" :disabled="highlightedDraftIndex >= draftPoints.length - 1" @click="focusNextPoint">다음 제어점</button>
              <button class="button button--danger" type="button" @click="removeDraftPoint(highlightedDraftIndex)">현재 제어점 삭제</button>
            </div>
          </template>
          <p v-else class="panel__empty">
            {{ isGpxMode ? 'GPX 파일들을 올리면 이동 경로 선과 요약 정보가 여기에 나타납니다.' : '지도를 눌러 첫 번째 제어점을 추가해 보세요.' }}
          </p>
        </article>

        <article class="travel-route-focus-card">
          <div class="travel-route-focus-card__header">
            <div>
              <h3>경로 요약</h3>
              <p>현재 초안의 거리와 걸음 수, GPX 상태를 빠르게 확인합니다.</p>
            </div>
          </div>
          <div class="travel-file-chip-row">
            <span class="chip chip--neutral">{{ hasGpxGeometry ? `트랙 포인트 ${draftPoints.length}개` : `제어점 ${draftPoints.length}개` }}</span>
            <span class="chip chip--neutral">저장 포인트 {{ storedRoutePointCount }}개</span>
            <span class="chip chip--neutral">총 거리 {{ draftDistanceKm.toFixed(2) }}km</span>
            <span class="chip chip--neutral">예상 걸음 {{ estimatedSteps.toLocaleString('ko-KR') }}걸음</span>
            <span class="chip chip--neutral">선 스타일 {{ lineStyleLabel(draft.lineStyle) }}</span>
            <span v-if="isGpxMode" class="chip chip--neutral">표시 방식 선 그리기</span>
            <span v-if="gpxFileNames.length" class="chip chip--neutral">GPX {{ gpxFileNames.length }}개</span>
            <span v-if="gpxFileLabel" class="chip chip--neutral">{{ gpxFileLabel }}</span>
          </div>
          <div class="entry-editor__actions">
            <button class="button button--ghost" type="button" :disabled="memoryRouteSeedPoints.length < 2" @click="applyMemoryPinsToDraft">기록 핀으로 경로 만들기</button>
            <button v-if="!hasGpxGeometry" class="button button--ghost" type="button" @click="removeLastPoint">마지막 제어점 삭제</button>
            <button class="button button--ghost" type="button" @click="resetDraft">{{ hasGpxGeometry ? 'GPX 경로 비우기' : '임시 경로 비우기' }}</button>
            <button class="button button--primary" :disabled="isSubmitting || draftPoints.length < 2 || !draft.title.trim()" @click="submitRoute">
              {{ isSubmitting && activeSubmit === 'route' ? '저장 중...' : editingRouteId ? '경로 수정' : '경로 저장' }}
            </button>
          </div>
        </article>
      </div>
    </section>

    <section class="panel">
      <div class="panel__header">
        <div>
          <h2>경로 정보 입력</h2>
          <p>지도는 크게 유지하고, 필요한 값은 아래 압축 폼에서 한 번에 입력합니다.</p>
        </div>
      </div>

      <div class="travel-route-form">
        <label class="field">
          <span class="field__label">날짜</span>
          <input v-model="draft.routeDate" type="date" />
        </label>
        <label class="field field--wide">
          <span class="field__label">경로 제목</span>
          <input v-model="draft.title" type="text" placeholder="예: 2일차 오사카 성에서 난바까지" />
        </label>
        <label class="field">
          <span class="field__label">이동 수단</span>
          <select v-model="draft.transportMode">
            <option v-for="option in transportOptions" :key="option.value" :value="option.value">{{ option.label }}</option>
          </select>
        </label>
        <label class="field">
          <span class="field__label">입력 방식</span>
          <select v-model="draft.sourceType">
            <option value="MANUAL">직접 그리기</option>
            <option value="GPX">GPX 불러오기</option>
          </select>
        </label>
        <label class="field">
          <span class="field__label">총 소요 시간(분)</span>
          <input v-model="draft.durationMinutes" type="number" min="0" step="1" placeholder="85" />
        </label>
        <label class="field">
          <span class="field__label">걸음 수</span>
          <input v-model="draft.stepCount" type="number" min="0" step="1" :placeholder="String(estimatedSteps || 0)" />
        </label>
        <label class="field">
          <span class="field__label">경로 색상</span>
          <input v-model="draft.lineColorHex" type="color" />
        </label>
        <label class="field">
          <span class="field__label">선 스타일</span>
          <select v-model="draft.lineStyle">
            <option v-for="option in lineStyleOptions" :key="option.value" :value="option.value">{{ option.label }}</option>
          </select>
        </label>
        <label class="field">
          <span class="field__label">출발 장소</span>
          <input v-model="draft.startPlaceName" type="text" placeholder="오사카 성" />
        </label>
        <label class="field">
          <span class="field__label">도착 장소</span>
          <input v-model="draft.endPlaceName" type="text" placeholder="난바" />
        </label>
        <label class="field field--wide">
          <span class="field__label">GPX 파일들</span>
          <input accept=".gpx" multiple type="file" @change="handleGpxSelection" />
        </label>
        <p v-if="hasGpxGeometry" class="travel-map-note field field--full">
          GPX 여러 개를 한 번에 읽어 하루 경로로 합칩니다. 저장할 때는 렌더링이 무거워지지 않도록 포인트를 자동으로 줄여 보관합니다.
        </p>
        <p v-else-if="draft.sourceType === 'GPX'" class="travel-map-note field field--full">
          GPX 파일을 여러 개까지 올릴 수 있고, 시간과 거리, 걸음 수를 합산해 하나의 경로 선으로 그립니다.
        </p>
        <label class="field field--full">
          <span class="field__label">메모</span>
          <textarea v-model="draft.memo" rows="3" placeholder="걷기 구간, 버스 환승, 택시 이동 이유 등을 적어두세요." />
        </label>
      </div>
    </section>

    <section class="panel">
      <div class="panel__header">
        <div>
          <h2>{{ hasGpxGeometry ? 'GPX 경로 요약' : '경로 제어점 순서 리스트' }}</h2>
          <p>
            {{
              hasGpxGeometry
                ? 'GPX는 제어점 목록 대신 선 경로와 핵심 좌표만 보여 줍니다. 방문 장소는 여행 기록 핀으로 따로 관리합니다.'
                : '출발부터 도착까지 순서대로 확인하고, 중간 제어점도 바로 수정할 수 있습니다.'
            }}
          </p>
        </div>
        <span class="panel__badge">{{ routePointBadge }}</span>
      </div>

      <div v-if="draftPointRowsDetailed.length" class="travel-point-list">
        <article
          v-for="row in draftPointRowsDetailed"
          :key="`draft-point-${row.index}`"
          class="travel-point-list__item"
          :class="{ 'is-active': highlightedDraftIndex === row.index }"
        >
          <div class="travel-point-list__head">
            <strong>{{ row.index + 1 }}번</strong>
            <small>{{ row.kindLabel }} · {{ row.label }}</small>
          </div>
          <label class="field">
            <span class="field__label">위도</span>
            <input :value="row.latitude" type="number" step="0.0000001" @change="updateDraftPoint(row.index, 'latitude', $event.target.value)" />
          </label>
          <label class="field">
            <span class="field__label">경도</span>
            <input :value="row.longitude" type="number" step="0.0000001" @change="updateDraftPoint(row.index, 'longitude', $event.target.value)" />
          </label>
          <div class="travel-point-list__actions">
            <button class="button button--ghost" type="button" :disabled="row.index <= 0" @click="moveDraftPointByOffset(row.index, -1)">위로</button>
            <button class="button button--ghost" type="button" :disabled="row.index >= draftPoints.length - 1" @click="moveDraftPointByOffset(row.index, 1)">아래로</button>
            <button class="button button--ghost" type="button" @click="focusDraftPoint(row.index)">지도에서 강조</button>
            <button class="button button--danger" type="button" @click="removeDraftPoint(row.index)">삭제</button>
          </div>
        </article>
      </div>
      <article v-else-if="hasGpxGeometry" class="travel-route-focus-card">
        <div class="travel-route-focus-grid">
          <label class="field">
            <span class="field__label">트랙 포인트 수</span>
            <input :value="draftPoints.length" type="text" readonly />
          </label>
          <label class="field">
            <span class="field__label">시작 좌표</span>
            <input :value="formatCoordinate(gpxStartPoint)" type="text" readonly />
          </label>
          <label class="field">
            <span class="field__label">종료 좌표</span>
            <input :value="formatCoordinate(gpxEndPoint)" type="text" readonly />
          </label>
        </div>
        <p class="travel-map-note">GPX를 넣으면 수천 개의 포인트를 핀으로 찍지 않고, 전체 이동선을 하나의 경로로 표시합니다.</p>
      </article>
      <p v-else class="panel__empty">
        {{ draft.sourceType === 'GPX' ? 'GPX 파일들을 올리면 이동 경로 선이 생성됩니다.' : '지도를 눌러 출발 제어점부터 추가해 보세요.' }}
      </p>
    </section>

    <section class="panel">
      <div class="panel__header">
        <div>
          <h2>{{ activeDayLabel }} 일정 타임라인</h2>
          <p>선택한 일차에 작성된 여행 기록과 이동 경로를 함께 보며 하루 흐름을 확인할 수 있습니다.</p>
        </div>
      </div>

      <div class="travel-file-chip-row">
        <span class="chip chip--neutral">당일 총 이동 {{ activeDayRouteStats.totalDistanceKm.toFixed(2) }}km</span>
        <span class="chip chip--neutral">당일 총 소요 {{ activeDayRouteStats.totalDurationMinutes }}분</span>
        <span class="chip chip--neutral">당일 총 걸음 {{ activeDayRouteStats.totalSteps.toLocaleString('ko-KR') }}걸음</span>
      </div>

      <div v-if="activeDayTimeline.length" class="travel-pending-grid">
        <article v-for="item in activeDayTimeline" :key="item.id" class="travel-pending-card">
          <strong>{{ timelineTypeLabel(item.type) }}: {{ item.title }}</strong>
          <small>{{ item.time ? formatTime(item.time) : '-' }}</small>
          <small>{{ item.summary }}</small>
          <small>{{ item.memo }}</small>
        </article>
      </div>
      <p v-else class="panel__empty">선택한 날짜에는 아직 기록된 일정이 없습니다.</p>
    </section>

    <section class="panel">
      <div class="panel__header">
        <div>
          <h2>{{ activeDayLabel }} 저장 경로</h2>
          <p>선택한 일차에 저장된 경로만 모아 보고, 이동 수단과 거리, 걸음 수를 빠르게 확인할 수 있습니다.</p>
        </div>
      </div>

      <div class="sheet-table-wrap">
        <table class="sheet-table">
          <thead>
            <tr>
              <th>날짜</th>
              <th>제목</th>
              <th>요약</th>
              <th>출발 / 도착</th>
              <th>경로 포인트</th>
              <th>작업</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="route in routesForActiveDay" :key="route.id">
              <td>{{ formatDate(route.routeDate) }}</td>
              <td>{{ route.title }}</td>
              <td>{{ routeSummary(route) }}</td>
              <td>{{ [route.startPlaceName, route.endPlaceName].filter(Boolean).join(' -> ') || '-' }}</td>
              <td>{{ route.points?.length || 0 }}</td>
              <td class="sheet-table__actions">
                <button class="button button--ghost" @click="startEditRoute(route)">수정</button>
                <button class="button button--danger" @click="emit('delete-route', route)">삭제</button>
              </td>
            </tr>
            <tr v-if="!routesForActiveDay.length">
              <td colspan="6" class="sheet-table__empty">선택한 날짜에 저장된 이동 경로가 아직 없습니다.</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </div>

  <section v-else class="panel">
    <p class="panel__empty">먼저 여행을 만들거나 선택해 주세요.</p>
  </section>
</template>
