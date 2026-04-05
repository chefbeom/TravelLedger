<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import { buildThumbnailUrl, THUMBNAIL_VARIANTS } from '../lib/mediaPreview'
import { formatDate, formatTime } from '../lib/uiFormat'

const DEFAULT_CENTER = [37.5547, 126.9706]
const DEFAULT_ZOOM = 11

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

const emit = defineEmits(['select-cluster', 'select-marker', 'select-photo-pin', 'preview-cluster', 'fullscreen-change'])

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
    photoUrl: marker?.photoUrl || '',
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

function buildRenderClusters(clusters) {
  return (clusters ?? []).map(normalizeCluster).filter(Boolean).map((cluster) => ({
    id: `cluster-${cluster.id}`,
    markerKey: `cluster-${cluster.id}`,
    isAggregate: Number(cluster.photoCount || 0) > 1,
    isPhotoPin: false,
    isRecordPin: false,
    representative: cluster,
    latitude: cluster.latitude,
    longitude: cluster.longitude,
    photoCount: Number(cluster.photoCount || 0),
    memoryCount: Number(cluster.memoryCount || 0),
  }))
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
    return buildRenderPins(props.photoPins)
  }

  return buildRenderClusters(props.photoClusters)
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

  const entries = props.displayMode === 'pin' ? props.photoPins : props.photoClusters
  const normalizer = props.displayMode === 'pin' ? normalizePhotoPin : normalizeCluster

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
    return createPopupContentLegacy(aggregate)
  }

  const root = document.createElement('button')
  root.type = 'button'
  root.className = 'travel-cluster-popup travel-cluster-popup--actionable'
  root.addEventListener('click', (event) => {
    event.preventDefault()
    event.stopPropagation()
    emit('preview-cluster', aggregate?.representative)
  })
  L.DomEvent.disableClickPropagation(root)

  const photoUrl = aggregate?.representative?.representativePhotoUrl
  if (photoUrl) {
    const image = document.createElement('img')
    image.className = 'travel-cluster-popup__image'
    image.src = buildThumbnailUrl(photoUrl, THUMBNAIL_VARIANTS.mini)
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
    : `사진 ${aggregate?.photoCount || 0}장 / 기록 ${aggregate?.memoryCount || aggregate?.representative?.memoryCount || 0}건`
  copy.appendChild(count)

  const action = document.createElement('span')
  action.className = 'travel-cluster-popup__action'
  action.textContent = '팝업을 누르면 큰 사진을 바로 볼 수 있습니다.'
  copy.appendChild(action)

  root.appendChild(copy)
  return root
}

function createPopupContentLegacy(aggregate) {
  if (aggregate?.isRecordPin) {
    const root = document.createElement('div')
    root.className = 'travel-cluster-popup'

    if (aggregate?.representative?.photoUrl) {
      const image = document.createElement('img')
      image.className = 'travel-cluster-popup__image'
      image.src = buildThumbnailUrl(aggregate.representative.photoUrl, THUMBNAIL_VARIANTS.mini)
      image.alt = aggregate?.representative?.title || aggregate?.representative?.placeName || '기록 사진'
      image.loading = 'eager'
      image.decoding = 'async'
      root.appendChild(image)
    }

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
    count.textContent = '기록 1건'
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
    image.src = buildThumbnailUrl(photoUrl, THUMBNAIL_VARIANTS.mini)
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
    : `사진 ${aggregate?.photoCount || 0}장 / 기록 ${aggregate?.memoryCount || aggregate?.representative?.memoryCount || 0}건`
  copy.appendChild(count)

  root.appendChild(copy)
  return root
}

function buildRecordMarkerIcon(marker, active) {
  const colorHex = normalizeColorHex(marker?.planColorHex, '#3182F6')
  const label = escapeHtml(String(marker?.category || marker?.title || marker?.placeName || '핀').slice(0, 2))
  const photoUrl = marker?.photoUrl ? buildThumbnailUrl(marker.photoUrl, THUMBNAIL_VARIANTS.pin) : ''

  return L.divIcon({
    className: 'travel-map__icon-root',
    html: `
      <div
        class="travel-map__thumb-pin travel-map__thumb-pin--memory${active ? ' is-active' : ''}"
        style="--marker-color:${colorHex};${photoUrl ? `background-image:url('${escapeHtml(photoUrl)}')` : ''}"
      >
        ${photoUrl ? '' : `<span>${label}</span>`}
      </div>
    `,
    iconSize: [50, 62],
    iconAnchor: [25, 58],
    popupAnchor: [0, -28],
  })
}

function buildClusterIcon(aggregate, active) {
  if (aggregate?.isRecordPin) {
    return buildRecordMarkerIcon(aggregate?.representative, active)
  }

  const photoUrl = aggregate?.representative?.representativePhotoUrl
    ? buildThumbnailUrl(aggregate.representative.representativePhotoUrl, THUMBNAIL_VARIANTS.pin)
    : ''
  const markerSize = aggregate?.isAggregate ? 68 : 60
  const clusterCount = aggregate?.photoCount || 0
  const memberCount = aggregate?.memoryCount || aggregate?.representative?.memoryCount || 1
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
        : String(aggregate.representative.id) === String(props.selectedClusterId)
    const marker = L.marker([aggregate.latitude, aggregate.longitude], {
      icon: buildClusterIcon(aggregate, containsSelected),
    })

    marker.bindPopup(() => createPopupContent(aggregate))
    marker.on('click', () => {
      pendingPopupMarkerKey = aggregate.markerKey

      if (aggregate.isPhotoPin) {
        emit('select-photo-pin', aggregate.representative)
      } else if (aggregate.isRecordPin) {
        emit('select-marker', aggregate.representative)
      } else {
        emit('select-cluster', aggregate.representative)
      }

      requestAnimationFrame(() => {
        const popupTarget = renderedMarkers.get(String(aggregate.markerKey)) || marker
        popupTarget?.openPopup()
      })
    })
    renderedMarkers.set(String(aggregate.markerKey), marker)

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
      ? (props.photoPins ?? []).map(normalizePhotoPin)
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
}

function handleFullscreenChange() {
  syncFullscreenState()
  emit('fullscreen-change', isFullscreen.value)
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
  emit('fullscreen-change', isFullscreen.value)
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
  () => [props.photoClusters, props.photoPins, props.markers, props.routes, props.displayMode],
  () => {
    renderMap()
  },
)

watch(
  () => [props.displayMode, props.selectedClusterId, props.selectedPhotoId, props.selectedMarkerId],
  ([mode, clusterId, photoId, markerId], [previousMode, previousClusterId, previousPhotoId, previousMarkerId] = []) => {
    const normalizedValue = mode === 'pin'
      ? `photo-${String(photoId ?? '')}`
      : `cluster-${String(clusterId ?? '')}`
    const normalizedPreviousValue = previousMode === 'pin'
      ? `photo-${String(previousPhotoId ?? '')}`
      : `cluster-${String(previousClusterId ?? '')}`

    if (normalizedValue === 'photo-' || normalizedValue === 'cluster-') {
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
          {{ props.displayMode === 'pin' ? '핀 보기: 기록 썸네일 핀 고정 표시' : '군집 보기: 서버에서 계산한 고정 군집 유지' }}
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
      <div v-if="isFullscreen" class="travel-map__overlay" @click.stop>
        <slot name="fullscreen-overlay" :is-fullscreen="isFullscreen" />
      </div>
    </div>

    <div class="travel-map__hint">
      <strong>클러스터 지도 사용법</strong>
      <span>확대와 축소를 해도 군집은 다시 묶이거나 풀리지 않습니다. 군집 핀을 누르면 오른쪽 패널에서 포함 사진을, 핀 보기에서는 기록 썸네일 핀을 바로 확인할 수 있습니다.</span>
    </div>
  </div>
</template>
