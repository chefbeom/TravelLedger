<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import { buildThumbnailUrl } from '../lib/mediaPreview'
import { formatDate, formatTime } from '../lib/uiFormat'

const DEFAULT_CENTER = [37.5547, 126.9706]
const DEFAULT_ZOOM = 11
const MAX_INDIVIDUAL_ZOOM = 18

const props = defineProps({
  photoClusters: {
    type: Array,
    default: () => [],
  },
  photoPins: {
    type: Array,
    default: () => [],
  },
  markers: {
    type: Array,
    default: () => [],
  },
  routes: {
    type: Array,
    default: () => [],
  },
  active: {
    type: Boolean,
    default: true,
  },
  selectedClusterId: {
    type: [String, Number],
    default: null,
  },
  selectedPhotoId: {
    type: [String, Number],
    default: null,
  },
  selectedMarkerId: {
    type: [String, Number],
    default: null,
  },
  displayMode: {
    type: String,
    default: 'cluster',
  },
})

const emit = defineEmits(['select-cluster', 'select-marker'])

const mapRootElement = ref(null)
const mapElement = ref(null)
const isFullscreen = ref(false)
const zoomLabel = ref(DEFAULT_ZOOM)

let mapInstance = null
let markerLayer = null
let routeLayer = null
let hasFittedInitialView = false
let renderedMarkers = new Map()
let pendingPopupMarkerKey = null

function toRadians(degrees) {
  return degrees * (Math.PI / 180)
}

function normalizeColorHex(value, fallback = '#3182F6') {
  return /^#[0-9A-Fa-f]{6}$/.test(String(value || '').trim()) ? String(value).trim().toUpperCase() : fallback
}

function escapeHtml(value) {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

function normalizeCluster(cluster) {
  const latitude = Number(cluster?.latitude)
  const longitude = Number(cluster?.longitude)
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) {
    return null
  }

  return {
    ...cluster,
    latitude,
    longitude,
    photoCount: Number(cluster?.photoCount || 0),
    memoryCount: Number(cluster?.memoryCount || 0),
  }
}

function normalizePhotoPin(pin) {
  const latitude = Number(pin?.latitude)
  const longitude = Number(pin?.longitude)
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude) || pin?.mediaId == null) {
    return null
  }

  return {
    ...pin,
    id: pin.mediaId,
    latitude,
    longitude,
    photoCount: 1,
    memoryCount: 1,
    representativePhotoUrl: pin?.photoUrl || '',
  }
}

function normalizeRecordMarker(marker) {
  const latitude = Number(marker?.latitude)
  const longitude = Number(marker?.longitude)
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude) || marker?.id == null) {
    return null
  }

  return {
    ...marker,
    markerId: marker.id,
    latitude,
    longitude,
    photoCount: 0,
    memoryCount: 1,
  }
}

function normalizeLineStyle(value) {
  const normalized = String(value || '').trim().toUpperCase()
  if (['SOLID', 'DASHED', 'DOTTED', 'LONG_DASH'].includes(normalized)) {
    return normalized
  }
  return 'SOLID'
}

function dashArrayForLineStyle(value) {
  switch (normalizeLineStyle(value)) {
    case 'DASHED':
      return '10 8'
    case 'DOTTED':
      return '3 10'
    case 'LONG_DASH':
      return '18 10'
    default:
      return undefined
  }
}

function buildPolylineOptions(colorHex, lineStyle) {
  return {
    color: normalizeColorHex(colorHex),
    weight: 4,
    opacity: 0.85,
    dashArray: dashArrayForLineStyle(lineStyle),
    lineCap: 'round',
    lineJoin: 'round',
  }
}

function calculateDistanceMeters(leftLatitude, leftLongitude, rightLatitude, rightLongitude) {
  const latitudeDistance = toRadians(rightLatitude - leftLatitude)
  const longitudeDistance = toRadians(rightLongitude - leftLongitude)
  const startLatitude = toRadians(leftLatitude)
  const endLatitude = toRadians(rightLatitude)

  const haversine = Math.pow(Math.sin(latitudeDistance / 2), 2)
    + Math.cos(startLatitude) * Math.cos(endLatitude) * Math.pow(Math.sin(longitudeDistance / 2), 2)
  const angularDistance = 2 * Math.atan2(Math.sqrt(haversine), Math.sqrt(1 - haversine))
  return 6_371_000 * angularDistance
}

function resolveAggregationDistanceMeters(zoom) {
  if (!Number.isFinite(zoom)) {
    return 50
  }
  if (zoom >= MAX_INDIVIDUAL_ZOOM) {
    return 0
  }
  if (zoom < 8) {
    return 500
  }
  if (zoom <= 14) {
    return 50
  }
  return 5
}

function chooseRepresentative(members, latitude, longitude) {
  return [...members].sort((left, right) => {
    const leftDistance = calculateDistanceMeters(left.latitude, left.longitude, latitude, longitude)
    const rightDistance = calculateDistanceMeters(right.latitude, right.longitude, latitude, longitude)
    if (leftDistance !== rightDistance) {
      return leftDistance - rightDistance
    }

    if ((left.photoCount || 0) !== (right.photoCount || 0)) {
      return (right.photoCount || 0) - (left.photoCount || 0)
    }

    const leftKey = `${left.memoryDate || ''} ${left.memoryTime || '99:99'} ${String(left.id).padStart(12, '0')}`
    const rightKey = `${right.memoryDate || ''} ${right.memoryTime || '99:99'} ${String(right.id).padStart(12, '0')}`
    return rightKey.localeCompare(leftKey)
  })[0]
}

function buildRenderClusters(clusters, zoom) {
  const normalizedClusters = (clusters ?? []).map(normalizeCluster).filter(Boolean)
  if (normalizedClusters.length <= 1) {
    return normalizedClusters.map((cluster) => ({
      id: `single-${cluster.id}`,
      isAggregate: false,
      representative: cluster,
      members: [cluster],
      latitude: cluster.latitude,
      longitude: cluster.longitude,
      photoCount: cluster.photoCount,
      bounds: [[cluster.latitude, cluster.longitude]],
    }))
  }

  const thresholdMeters = resolveAggregationDistanceMeters(zoom)
  if (thresholdMeters <= 0) {
    return normalizedClusters.map((cluster) => ({
      id: `single-${cluster.id}`,
      isAggregate: false,
      representative: cluster,
      members: [cluster],
      latitude: cluster.latitude,
      longitude: cluster.longitude,
      photoCount: cluster.photoCount,
      bounds: [[cluster.latitude, cluster.longitude]],
    }))
  }

  const visited = new Array(normalizedClusters.length).fill(false)
  const grouped = []

  for (let index = 0; index < normalizedClusters.length; index += 1) {
    if (visited[index]) {
      continue
    }

    const queue = [index]
    visited[index] = true
    const members = []

    while (queue.length) {
      const currentIndex = queue.shift()
      const currentCluster = normalizedClusters[currentIndex]
      members.push(currentCluster)

      for (let candidateIndex = 0; candidateIndex < normalizedClusters.length; candidateIndex += 1) {
        if (visited[candidateIndex]) {
          continue
        }

        const candidateCluster = normalizedClusters[candidateIndex]
        const distanceMeters = calculateDistanceMeters(
          currentCluster.latitude,
          currentCluster.longitude,
          candidateCluster.latitude,
          candidateCluster.longitude,
        )

        if (distanceMeters <= thresholdMeters) {
          visited[candidateIndex] = true
          queue.push(candidateIndex)
        }
      }
    }

    const latitude = members.reduce((sum, member) => sum + member.latitude, 0) / members.length
    const longitude = members.reduce((sum, member) => sum + member.longitude, 0) / members.length
    const representative = chooseRepresentative(members, latitude, longitude)
    const photoCount = members.reduce((sum, member) => sum + Number(member.photoCount || 0), 0)
    const isAggregate = members.length > 1

    grouped.push({
      id: isAggregate ? `aggregate-${members.map((member) => member.id).join('-')}` : `single-${representative.id}`,
      isAggregate,
      representative,
      members,
      latitude: isAggregate ? latitude : representative.latitude,
      longitude: isAggregate ? longitude : representative.longitude,
      photoCount,
      bounds: members.map((member) => [member.latitude, member.longitude]),
    })
  }

  return grouped
}

function buildRenderPins(pins) {
  return (pins ?? []).map(normalizePhotoPin).filter(Boolean).map((pin) => ({
    id: `photo-${pin.mediaId}`,
    markerKey: `photo-${pin.mediaId}`,
    isAggregate: false,
    isPhotoPin: true,
    representative: pin,
    members: [pin],
    latitude: pin.latitude,
    longitude: pin.longitude,
    photoCount: 1,
    bounds: [[pin.latitude, pin.longitude]],
  }))
}

function buildRenderMarkers(markers) {
  return (markers ?? []).map(normalizeRecordMarker).filter(Boolean).map((marker) => ({
    id: `marker-${marker.markerId}`,
    markerKey: `marker-${marker.markerId}`,
    isAggregate: false,
    isPhotoPin: false,
    isRecordPin: true,
    representative: marker,
    members: [marker],
    latitude: marker.latitude,
    longitude: marker.longitude,
    photoCount: 0,
    bounds: [[marker.latitude, marker.longitude]],
  }))
}

function resolveRenderableItems() {
  if (props.displayMode === 'pin') {
    return buildRenderMarkers(props.markers)
  }

  return buildRenderClusters(props.photoClusters, mapInstance?.getZoom()).map((aggregate) => ({
    ...aggregate,
    markerKey: aggregate?.isAggregate
      ? `aggregate-${aggregate.id}`
      : `cluster-${aggregate.representative.id}`,
    isPhotoPin: false,
  }))
}

function queueMapResize() {
  requestAnimationFrame(() => {
    requestAnimationFrame(() => {
      mapInstance?.invalidateSize(false)
    })
  })
}

function collectBounds() {
  const points = []

  const entries = props.displayMode === 'pin' ? props.markers : props.photoClusters
  const normalizer = props.displayMode === 'pin' ? normalizeRecordMarker : normalizeCluster

  ;(entries ?? []).forEach((entry) => {
    const normalized = normalizer(entry)
    if (normalized) {
      points.push([normalized.latitude, normalized.longitude])
    }
  })

  ;(props.routes ?? []).forEach((route) => {
    ;(route.points ?? []).forEach((point) => {
      const latitude = Number(point?.latitude)
      const longitude = Number(point?.longitude)
      if (Number.isFinite(latitude) && Number.isFinite(longitude)) {
        points.push([latitude, longitude])
      }
    })
  })

  return points
}

function fitToAll() {
  if (!mapInstance) {
    return
  }

  const bounds = collectBounds()
  if (!bounds.length) {
    mapInstance.setView(DEFAULT_CENTER, DEFAULT_ZOOM)
    return
  }

  mapInstance.fitBounds(bounds, { padding: [40, 40], maxZoom: 16 })
}

function createPopupContent(aggregate) {
  if (aggregate?.isRecordPin) {
    const root = document.createElement('div')
    root.className = 'travel-cluster-popup'

    const copy = document.createElement('div')
    copy.className = 'travel-cluster-popup__copy'

    const title = document.createElement('strong')
    title.textContent = aggregate?.representative?.title || aggregate?.representative?.placeName || '기록 핀'
    copy.appendChild(title)

    const locationLabel = [aggregate?.representative?.country, aggregate?.representative?.region, aggregate?.representative?.placeName]
      .filter(Boolean)
      .join(' / ')
    if (locationLabel) {
      const location = document.createElement('span')
      location.textContent = locationLabel
      copy.appendChild(location)
    }

    const dateLabel = [formatDate(aggregate?.representative?.memoryDate), formatTime(aggregate?.representative?.memoryTime)]
      .filter((value) => value && value !== '-')
      .join(' ')
    if (dateLabel) {
      const date = document.createElement('span')
      date.textContent = dateLabel
      copy.appendChild(date)
    }

    const count = document.createElement('span')
    count.textContent = '기록 핀 1개'
    copy.appendChild(count)

    root.appendChild(copy)
    return root
  }

  const root = document.createElement('div')
  root.className = 'travel-cluster-popup'

  const photoUrl = aggregate?.representative?.representativePhotoUrl
  if (photoUrl) {
    const image = document.createElement('img')
    image.className = 'travel-cluster-popup__image'
    image.src = buildThumbnailUrl(photoUrl, 360)
    image.alt = aggregate?.representative?.title || aggregate?.representative?.placeName || '대표 사진'
    image.loading = 'eager'
    image.decoding = 'async'
    root.appendChild(image)
  }

  const copy = document.createElement('div')
  copy.className = 'travel-cluster-popup__copy'

  const title = document.createElement('strong')
  title.textContent = aggregate?.representative?.title || aggregate?.representative?.placeName || '사진 클러스터'
  copy.appendChild(title)

  const locationLabel = [aggregate?.representative?.country, aggregate?.representative?.region, aggregate?.representative?.placeName]
    .filter(Boolean)
    .join(' / ')
  if (locationLabel) {
    const location = document.createElement('span')
    location.textContent = locationLabel
    copy.appendChild(location)
  }

  const dateLabel = [formatDate(aggregate?.representative?.memoryDate), formatTime(aggregate?.representative?.memoryTime)]
    .filter((value) => value && value !== '-')
    .join(' ')
  if (dateLabel) {
    const date = document.createElement('span')
    date.textContent = dateLabel
    copy.appendChild(date)
  }

  const count = document.createElement('span')
  count.textContent = aggregate?.isPhotoPin
    ? '사진 1장'
    : `사진 ${aggregate?.photoCount || 0}장 / 기록 ${aggregate?.representative?.memoryCount || aggregate?.members?.length || 0}건`
  copy.appendChild(count)

  root.appendChild(copy)
  return root
}

function buildRecordMarkerIcon(marker, active) {
  const colorHex = normalizeColorHex(marker?.planColorHex, '#3182F6')
  const label = escapeHtml(String(marker?.category || marker?.title || marker?.placeName || '핀').slice(0, 2))

  return L.divIcon({
    className: 'travel-map__icon-root',
    html: `
      <div
        class="travel-map__marker-icon travel-map__marker-icon--memory${active ? ' is-active' : ''}"
        style="--marker-color:${colorHex}"
      >
        <span>${label}</span>
      </div>
    `,
    iconSize: [40, 52],
    iconAnchor: [20, 50],
    popupAnchor: [0, -28],
  })
}

function buildClusterIcon(aggregate, active) {
  if (aggregate?.isRecordPin) {
    return buildRecordMarkerIcon(aggregate?.representative, active)
  }

  const photoUrl = aggregate?.representative?.representativePhotoUrl
    ? buildThumbnailUrl(aggregate.representative.representativePhotoUrl, 320)
    : ''
  const markerSize = aggregate?.isAggregate ? 68 : 60
  const clusterCount = aggregate?.photoCount || 0
  const memberCount = aggregate?.members?.length || 1
  const colorHex = normalizeColorHex(aggregate?.representative?.planColorHex, '#3182F6')

  return L.divIcon({
    className: 'travel-map__icon-root',
    html: `
      <div
        class="travel-cluster-pin${aggregate?.isAggregate ? ' is-aggregate' : ''}${active ? ' is-active' : ''}"
        style="--cluster-color:${colorHex};${photoUrl ? `background-image:url('${escapeHtml(photoUrl)}')` : ''}"
      >
        <span class="travel-cluster-pin__count">${clusterCount}</span>
        ${aggregate?.isAggregate ? `<small class="travel-cluster-pin__group">${memberCount}</small>` : ''}
      </div>
    `,
    iconSize: [markerSize, markerSize],
    iconAnchor: [Math.round(markerSize / 2), markerSize - 6],
    popupAnchor: [0, -markerSize + 16],
  })
}

function renderRoutes() {
  routeLayer.clearLayers()

  ;(props.routes ?? []).forEach((route) => {
    const points = (route.points ?? [])
      .map((point) => {
        const latitude = Number(point?.latitude)
        const longitude = Number(point?.longitude)
        if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) {
          return null
        }
        return [latitude, longitude]
      })
      .filter(Boolean)

    if (points.length < 2) {
      return
    }

    const polyline = L.polyline(
      points,
      buildPolylineOptions(route.lineColorHex || route.planColorHex || '#3182F6', route.lineStyle),
    )

    if (route.title) {
      polyline.bindTooltip(route.title)
    }

    polyline.addTo(routeLayer)
  })
}

function focusAggregate(aggregate) {
  if (!mapInstance || !aggregate?.isAggregate || !aggregate?.bounds?.length) {
    return
  }

  mapInstance.closePopup()

  if (aggregate.bounds.length === 1) {
    mapInstance.setView(aggregate.bounds[0], Math.min((mapInstance.getZoom() || DEFAULT_ZOOM) + 2, MAX_INDIVIDUAL_ZOOM))
    return
  }

  mapInstance.fitBounds(aggregate.bounds, {
    padding: [60, 60],
    maxZoom: Math.min((mapInstance.getZoom() || DEFAULT_ZOOM) + 3, MAX_INDIVIDUAL_ZOOM),
  })
}

function renderClusters() {
  if (!mapInstance) {
    return
  }

  markerLayer.clearLayers()
  renderedMarkers = new Map()

  const aggregates = resolveRenderableItems()
  aggregates.forEach((aggregate) => {
    const containsSelected = aggregate.isRecordPin
      ? String(aggregate.representative.markerId) === String(props.selectedMarkerId)
      : aggregate.isPhotoPin
        ? String(aggregate.representative.mediaId) === String(props.selectedPhotoId)
        : aggregate.members.some((member) => String(member.id) === String(props.selectedClusterId))
    const marker = L.marker([aggregate.latitude, aggregate.longitude], {
      icon: buildClusterIcon(aggregate, containsSelected),
    })

    if (!aggregate.isAggregate) {
      marker.bindPopup(() => createPopupContent(aggregate))
      marker.on('click', () => {
        if (aggregate.isRecordPin) {
          emit('select-marker', aggregate.representative)
          return
        }
        emit('select-cluster', aggregate.representative)
      })
      renderedMarkers.set(String(aggregate.markerKey), marker)
    } else {
      marker.on('click', () => {
        focusAggregate(aggregate)
      })
    }

    marker.addTo(markerLayer)
  })

  const selectedMarker = renderedMarkers.get(String(pendingPopupMarkerKey ?? ''))
  if (selectedMarker) {
    const popupTarget = selectedMarker
    pendingPopupMarkerKey = null
    requestAnimationFrame(() => popupTarget.openPopup())
  }
}

function renderMap({ shouldFit = false } = {}) {
  renderRoutes()
  renderClusters()

  if (!hasFittedInitialView || shouldFit) {
    hasFittedInitialView = true
    fitToAll()
    return
  }

  requestAnimationFrame(() => mapInstance?.invalidateSize(false))
}

function resolveInitialCenter() {
  const firstPhotoEntry = (
    props.displayMode === 'pin'
      ? (props.markers ?? []).map(normalizeRecordMarker)
      : (props.photoClusters ?? []).map(normalizeCluster)
  ).find(Boolean)
  if (firstPhotoEntry) {
    return [firstPhotoEntry.latitude, firstPhotoEntry.longitude]
  }

  const firstRoutePoint = (props.routes ?? [])
    .flatMap((route) => route.points ?? [])
    .map((point) => {
      const latitude = Number(point?.latitude)
      const longitude = Number(point?.longitude)
      if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) {
        return null
      }
      return [latitude, longitude]
    })
    .find(Boolean)

  return firstRoutePoint || DEFAULT_CENTER
}

function syncFullscreenState() {
  isFullscreen.value = document.fullscreenElement === mapRootElement.value
}

async function toggleFullscreen() {
  const element = mapRootElement.value
  if (!element || typeof element.requestFullscreen !== 'function') {
    return
  }

  try {
    if (document.fullscreenElement === element) {
      await document.exitFullscreen()
    } else {
      await element.requestFullscreen()
    }
  } catch (error) {
    console.error('Failed to toggle my-map fullscreen.', error)
  } finally {
    syncFullscreenState()
    queueMapResize()
  }
}

function handleZoomEnd() {
  zoomLabel.value = mapInstance?.getZoom() ?? DEFAULT_ZOOM
  renderClusters()
}

function handleFullscreenChange() {
  syncFullscreenState()
  queueMapResize()
}

onMounted(() => {
  document.addEventListener('fullscreenchange', handleFullscreenChange)

  mapInstance = L.map(mapElement.value, {
    zoomControl: true,
    scrollWheelZoom: true,
  }).setView(resolveInitialCenter(), DEFAULT_ZOOM)

  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    attribution: '&copy; OpenStreetMap contributors',
  }).addTo(mapInstance)

  markerLayer = L.layerGroup().addTo(mapInstance)
  routeLayer = L.layerGroup().addTo(mapInstance)
  mapInstance.on('zoomend', handleZoomEnd)
  zoomLabel.value = mapInstance.getZoom()
  renderMap({ shouldFit: true })
})

onBeforeUnmount(() => {
  document.removeEventListener('fullscreenchange', handleFullscreenChange)

  if (mapInstance) {
    mapInstance.off('zoomend', handleZoomEnd)
    mapInstance.remove()
    mapInstance = null
  }
})

watch(
  () => [props.photoClusters, props.markers, props.routes, props.displayMode],
  () => {
    renderMap()
  },
  { deep: true },
)

watch(
  () => [props.displayMode, props.selectedClusterId, props.selectedPhotoId, props.selectedMarkerId],
  ([mode, clusterId, photoId, markerId], [previousMode, previousClusterId, previousPhotoId, previousMarkerId] = []) => {
    const normalizedValue = mode === 'pin'
      ? `marker-${String(markerId ?? '')}`
      : `cluster-${String(clusterId ?? '')}`
    const normalizedPreviousValue = previousMode === 'pin'
      ? `marker-${String(previousMarkerId ?? '')}`
      : `cluster-${String(previousClusterId ?? '')}`

    if (normalizedValue === 'marker-' || normalizedValue === 'cluster-') {
      pendingPopupMarkerKey = null
      mapInstance?.closePopup()
    } else if (normalizedValue !== normalizedPreviousValue) {
      pendingPopupMarkerKey = normalizedValue
    }

    renderClusters()
  },
  { immediate: true },
)

watch(
  () => props.active,
  async (value) => {
    if (!value || !mapInstance) {
      return
    }

    await nextTick()
    queueMapResize()
    requestAnimationFrame(() => mapInstance?.invalidateSize(false))
  },
)
</script>

<template>
  <div ref="mapRootElement" class="travel-map" :class="{ 'travel-map--fullscreen': isFullscreen }">
    <div class="travel-map__toolbar" @click.stop>
      <div class="travel-map__toolbar-group">
        <span class="travel-map__toolbar-label">줌 단계</span>
        <strong class="travel-cluster-map__zoom">{{ zoomLabel }}</strong>
      </div>

      <div class="travel-map__toolbar-group">
        <span class="travel-map__toolbar-label">클러스터 기준</span>
        <small class="travel-cluster-map__legend">
          {{ props.displayMode === 'pin' ? '핀 보기: 저장된 개별 사진 핀 표시' : '군집 보기: 낮은 줌 500m / 중간 50m / 높은 줌 5m / 최대 줌 개별 사진' }}
        </small>
      </div>

      <div class="travel-map__toolbar-group">
        <button class="travel-map__toolbar-button" type="button" @click="fitToAll">전체 보기</button>
        <button class="travel-map__toolbar-button" type="button" @click="toggleFullscreen">
          {{ isFullscreen ? '전체 화면 종료' : '전체 화면' }}
        </button>
      </div>
    </div>

    <div class="travel-map__stage">
      <div ref="mapElement" class="travel-map__canvas" />
    </div>

    <div class="travel-map__hint">
      <strong>클러스터 지도 사용법</strong>
      <span>묶음 핀을 누르면 그 영역으로 확대되고, 개별 핀을 누르면 오른쪽 패널에서 포함 사진과 대표 사진 설정을 확인할 수 있습니다.</span>
    </div>
  </div>
</template>
