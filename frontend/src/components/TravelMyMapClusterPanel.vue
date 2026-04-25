<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import { buildThumbnailUrl, THUMBNAIL_VARIANTS } from '../lib/mediaPreview'
import { formatDate, formatTime } from '../lib/uiFormat'

const DEFAULT_CENTER = [37.5547, 126.9706]
const DEFAULT_ZOOM = 11
const VIEWPORT_PADDING_RATIO = 0.35
const VIEWPORT_RENDER_DEBOUNCE_MS = 80
const CLIENT_CLUSTER_MIN_SIZE = 2
const CLIENT_CLUSTER_MAX_ZOOM = 17

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

const emit = defineEmits([
  'select-cluster',
  'select-marker',
  'select-photo-pin',
  'preview-cluster',
  'fullscreen-change',
  'clear-selection',
])

const mapRootElement = ref(null)
const mapElement = ref(null)
const isFullscreen = ref(false)
const isMapMoving = ref(false)
const zoomLabel = ref(DEFAULT_ZOOM)

let mapInstance = null
let markerLayer = null
let routeLayer = null
let routeRenderer = null
let hasFittedInitialView = false
let renderedMarkers = new Map()
let pendingPopupMarkerKey = null
let mapRenderFrame = 0
let mapRenderTimer = 0
let popupOpenSequence = 0
let suppressNextMapBackgroundClick = false

function scheduleMarkerPopup(markerKey, remainingAttempts = 6, sequence = popupOpenSequence) {
  const normalizedKey = String(markerKey ?? '')
  if (!normalizedKey) {
    return
  }

  requestAnimationFrame(() => {
    if (sequence !== popupOpenSequence) {
      return
    }

    const marker = renderedMarkers.get(normalizedKey)
    if (marker && mapInstance?.hasLayer(marker)) {
      pendingPopupMarkerKey = null
      marker.openPopup()
      return
    }

    if (remainingAttempts > 0) {
      scheduleMarkerPopup(normalizedKey, remainingAttempts - 1)
    }
  })
}

function requestMarkerPopup(markerKey) {
  const normalizedKey = String(markerKey ?? '')
  if (!normalizedKey) {
    return
  }

  pendingPopupMarkerKey = normalizedKey
  popupOpenSequence += 1
}

function clearPendingPopupRequest() {
  pendingPopupMarkerKey = null
  popupOpenSequence += 1
}

function suppressMapBackgroundClickOnce() {
  suppressNextMapBackgroundClick = true
  setTimeout(() => {
    suppressNextMapBackgroundClick = false
  }, 0)
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

function isSelectedAggregate(aggregate) {
  if (aggregate?.isClientCluster) {
    return false
  }

  if (aggregate?.isRecordPin) {
    return String(aggregate.representative?.markerId) === String(props.selectedMarkerId)
  }

  if (aggregate?.isPhotoPin) {
    return String(aggregate.representative?.mediaId) === String(props.selectedPhotoId)
  }

  return String(aggregate?.representative?.id) === String(props.selectedClusterId)
}

function aggregateContainsSelection(aggregate) {
  if (!aggregate?.isClientCluster) {
    return isSelectedAggregate(aggregate)
  }

  return (aggregate.members ?? []).some((member) => aggregateContainsSelection(member))
}

function collectAggregateBounds(aggregate) {
  if (Array.isArray(aggregate?.bounds) && aggregate.bounds.length) {
    return aggregate.bounds
  }

  const latitude = Number(aggregate?.latitude)
  const longitude = Number(aggregate?.longitude)
  if (Number.isFinite(latitude) && Number.isFinite(longitude)) {
    return [[latitude, longitude]]
  }

  return []
}

function getPaddedMapBounds() {
  if (!mapInstance) {
    return null
  }

  try {
    return mapInstance.getBounds().pad(VIEWPORT_PADDING_RATIO)
  } catch {
    return null
  }
}

function isAggregateInBounds(aggregate, bounds) {
  if (!bounds) {
    return true
  }

  const latitude = Number(aggregate?.latitude)
  const longitude = Number(aggregate?.longitude)
  return Number.isFinite(latitude) && Number.isFinite(longitude) && bounds.contains([latitude, longitude])
}

function resolveClientClusterCellSize() {
  const zoom = mapInstance?.getZoom() ?? DEFAULT_ZOOM
  if (zoom >= CLIENT_CLUSTER_MAX_ZOOM) {
    return 0
  }

  if (props.displayMode === 'pin') {
    if (zoom <= 9) return 128
    if (zoom <= 11) return 112
    if (zoom <= 13) return 96
    if (zoom <= 15) return 72
    return 56
  }

  if (zoom <= 9) return 112
  if (zoom <= 11) return 96
  if (zoom <= 13) return 76
  if (zoom <= 15) return 60
  return 0
}

function pickRepresentativePhotoUrl(items) {
  const entry = findEarliestPhotoItem(items)
  return entry?.representative?.representativePhotoUrl || entry?.representative?.photoUrl || ''
}

function resolveAggregateDateTime(item) {
  const source = item?.representative ?? item
  const capturedAt = [
    source?.memoryDate ?? source?.expenseDate,
    source?.memoryTime ?? source?.expenseTime,
  ].filter((value) => value != null && value !== '').join(' ')
  const uploadedAt = String(source?.uploadedAt ?? '')
  const stableId = String(source?.id ?? source?.mediaId ?? item?.id ?? item?.markerKey ?? '')

  return {
    hasTime: Boolean(capturedAt || uploadedAt),
    value: [capturedAt, uploadedAt, stableId].filter(Boolean).join(' '),
  }
}

function compareAggregateDateTime(left, right) {
  const leftValue = resolveAggregateDateTime(left)
  const rightValue = resolveAggregateDateTime(right)
  if (leftValue.hasTime !== rightValue.hasTime) {
    return leftValue.hasTime ? -1 : 1
  }

  const compared = leftValue.value.localeCompare(rightValue.value)
  if (compared !== 0) {
    return compared
  }

  return String(left?.markerKey ?? left?.id ?? '').localeCompare(String(right?.markerKey ?? right?.id ?? ''))
}

function findEarliestPhotoItem(items) {
  const withPhoto = (items ?? []).filter((item) => item?.representative?.representativePhotoUrl || item?.representative?.photoUrl)
  return [...(withPhoto.length ? withPhoto : (items ?? []))].sort(compareAggregateDateTime)[0] ?? null
}

function selectRepresentativeAggregate(aggregate) {
  const target = aggregate?.representativeItem ?? aggregate
  if (!target?.representative) {
    return
  }

  if (target.isPhotoPin) {
    emit('select-photo-pin', target.representative)
  } else if (target.isRecordPin) {
    emit('select-marker', target.representative)
  } else {
    emit('select-cluster', target.representative)
  }
}

function buildClientCluster(items, cellKey) {
  const firstItem = findEarliestPhotoItem(items) ?? items[0]
  const latitude = items.reduce((sum, item) => sum + Number(item.latitude || 0), 0) / items.length
  const longitude = items.reduce((sum, item) => sum + Number(item.longitude || 0), 0) / items.length
  const photoCount = items.reduce((sum, item) => sum + Number(item.photoCount || 0), 0)
  const memoryCount = items.reduce(
    (sum, item) => sum + Number(item.memoryCount || item.representative?.memoryCount || (item.isPhotoPin ? 1 : 0)),
    0,
  )
  const bounds = items.flatMap((item) => collectAggregateBounds(item))
  const markerKey = `viewport-${props.displayMode}-${Math.round(mapInstance?.getZoom() ?? DEFAULT_ZOOM)}-${cellKey}`

  return {
    id: markerKey,
    markerKey,
    isAggregate: true,
    isClientCluster: true,
    isPhotoPin: false,
    isRecordPin: false,
    representativeItem: firstItem,
    representative: {
      ...(firstItem?.representative ?? {}),
      representativePhotoUrl: pickRepresentativePhotoUrl(items),
      planColorHex: firstItem?.representative?.planColorHex,
    },
    members: items,
    latitude,
    longitude,
    photoCount,
    memoryCount,
    bounds,
  }
}

function buildViewportAggregates(items) {
  if (!mapInstance || !items.length) {
    return items
  }

  const paddedBounds = getPaddedMapBounds()
  const visibleItems = items.filter((item) => isAggregateInBounds(item, paddedBounds))
  const cellSize = resolveClientClusterCellSize()
  if (!cellSize) {
    return visibleItems
  }

  const groups = new Map()

  visibleItems.forEach((item) => {
    const point = mapInstance.latLngToLayerPoint([item.latitude, item.longitude])
    const key = `${Math.floor(point.x / cellSize)}:${Math.floor(point.y / cellSize)}`
    const group = groups.get(key) ?? []
    group.push(item)
    groups.set(key, group)
  })

  const aggregates = []
  groups.forEach((group, key) => {
    if (group.length < CLIENT_CLUSTER_MIN_SIZE) {
      aggregates.push(...group)
      return
    }

    aggregates.push(buildClientCluster(group, key))
  })

  return aggregates
}

function focusClientCluster(aggregate) {
  if (!mapInstance) {
    return
  }

  clearPendingPopupRequest()
  mapInstance.closePopup()

  const bounds = collectAggregateBounds(aggregate)
  if (bounds.length > 1) {
    const nextZoom = Math.min((mapInstance.getZoom() ?? DEFAULT_ZOOM) + 3, 18)
    mapInstance.fitBounds(bounds, { padding: [48, 48], maxZoom: nextZoom })
    return
  }

  mapInstance.setView([aggregate.latitude, aggregate.longitude], Math.min((mapInstance.getZoom() ?? DEFAULT_ZOOM) + 2, 18))
}

function cancelScheduledClusterRender() {
  if (mapRenderTimer) {
    clearTimeout(mapRenderTimer)
    mapRenderTimer = 0
  }

  if (mapRenderFrame) {
    cancelAnimationFrame(mapRenderFrame)
    mapRenderFrame = 0
  }
}

function scheduleRenderClusters(delay = VIEWPORT_RENDER_DEBOUNCE_MS) {
  if (!mapInstance) {
    return
  }

  if (mapRenderTimer) {
    clearTimeout(mapRenderTimer)
  }

  mapRenderTimer = setTimeout(() => {
    mapRenderTimer = 0
    if (mapRenderFrame) {
      cancelAnimationFrame(mapRenderFrame)
    }
    mapRenderFrame = requestAnimationFrame(() => {
      mapRenderFrame = 0
      renderClusters()
    })
  }, delay)
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

function formatCompactCount(value) {
  const count = Number(value || 0)
  if (!Number.isFinite(count)) {
    return '0'
  }
  if (count >= 10000) {
    return `${Math.round(count / 1000)}k`
  }
  if (count >= 1000) {
    return `${Math.round(count / 100) / 10}k`
  }
  return String(count)
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
  const colorHex = normalizeColorHex(aggregate?.representative?.planColorHex, '#3182F6')

  return L.divIcon({
    className: 'travel-map__icon-root',
    html: `
      <div
        class="travel-cluster-pin${aggregate?.isAggregate ? ' is-aggregate' : ''}${aggregate?.isClientCluster ? ' is-client-cluster' : ''}${active ? ' is-active' : ''}"
        style="--cluster-color:${colorHex};${photoUrl ? `background-image:url('${escapeHtml(photoUrl)}')` : ''}"
      >
        <span class="travel-cluster-pin__count">${formatCompactCount(clusterCount)}</span>
      </div>
    `,
    iconSize: [markerSize, markerSize],
    iconAnchor: [Math.round(markerSize / 2), markerSize - 6],
    popupAnchor: [0, -markerSize + 16],
  })
}

function renderRoutes() {
  if (!routeLayer) {
    return
  }

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

    const polyline = L.polyline(points, {
      ...buildPolylineOptions(route.lineColorHex || route.planColorHex || '#3182F6', route.lineStyle),
      ...(routeRenderer ? { renderer: routeRenderer } : {}),
      bubblingMouseEvents: false,
    })

    polyline.on('click', (event) => {
      if (event?.originalEvent) {
        L.DomEvent.stopPropagation(event.originalEvent)
      }
    })

    if (route.title) {
      polyline.bindTooltip(route.title)
    }

    polyline.addTo(routeLayer)
  })
}

function renderClusters() {
  if (!mapInstance || !markerLayer) {
    return
  }

  cancelScheduledClusterRender()
  markerLayer.clearLayers()
  renderedMarkers = new Map()

  const aggregates = buildViewportAggregates(resolveRenderableItems())
  let selectedMarkerKey = null
  aggregates.forEach((aggregate) => {
    const containsSelected = aggregateContainsSelection(aggregate)
    if (containsSelected) {
      selectedMarkerKey = aggregate.markerKey
    }

    const marker = L.marker([aggregate.latitude, aggregate.longitude], {
      icon: buildClusterIcon(aggregate, containsSelected),
      bubblingMouseEvents: false,
    })

    marker.bindPopup(() => createPopupContent(aggregate))

    marker.on('click', (event) => {
      if (event?.originalEvent) {
        L.DomEvent.stopPropagation(event.originalEvent)
        L.DomEvent.preventDefault(event.originalEvent)
      }

      suppressMapBackgroundClickOnce()
      requestMarkerPopup(aggregate.markerKey)
      selectRepresentativeAggregate(aggregate)
      scheduleRenderClusters(0)
    })
    renderedMarkers.set(String(aggregate.markerKey), marker)

    marker.addTo(markerLayer)
  })

  if (pendingPopupMarkerKey) {
    const normalizedPendingKey = String(pendingPopupMarkerKey)
    if (renderedMarkers.has(normalizedPendingKey)) {
      scheduleMarkerPopup(normalizedPendingKey, 6, popupOpenSequence)
    } else if (selectedMarkerKey) {
      pendingPopupMarkerKey = selectedMarkerKey
      scheduleMarkerPopup(selectedMarkerKey, 6, popupOpenSequence)
    }
  }
}

function renderMap({ shouldFit = false } = {}) {
  renderRoutes()

  if (!hasFittedInitialView || shouldFit) {
    hasFittedInitialView = true
    fitToAll()
    scheduleRenderClusters(0)
    return
  }

  renderClusters()
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

function handleViewportStart() {
  isMapMoving.value = true
  cancelScheduledClusterRender()
}

function handleViewportEnd() {
  isMapMoving.value = false
  zoomLabel.value = mapInstance?.getZoom() ?? DEFAULT_ZOOM
  scheduleRenderClusters(0)
}

function handleZoomEnd() {
  handleViewportEnd()
}

function handleMapBackgroundClick() {
  if (suppressNextMapBackgroundClick) {
    suppressNextMapBackgroundClick = false
    return
  }

  clearPendingPopupRequest()
  mapInstance?.closePopup()
  emit('clear-selection')
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
    preferCanvas: true,
    markerZoomAnimation: false,
    fadeAnimation: false,
  }).setView(resolveInitialCenter(), DEFAULT_ZOOM)

  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    attribution: '&copy; OpenStreetMap contributors',
  }).addTo(mapInstance)

  markerLayer = L.layerGroup().addTo(mapInstance)
  routeLayer = L.layerGroup().addTo(mapInstance)
  routeRenderer = L.canvas({ padding: 0.5 })
  mapInstance.on('movestart zoomstart', handleViewportStart)
  mapInstance.on('moveend', handleViewportEnd)
  mapInstance.on('zoomend', handleZoomEnd)
  mapInstance.on('click', handleMapBackgroundClick)
  zoomLabel.value = mapInstance.getZoom()
  emit('fullscreen-change', isFullscreen.value)
  renderMap({ shouldFit: true })
})

onBeforeUnmount(() => {
  document.removeEventListener('fullscreenchange', handleFullscreenChange)

  if (mapInstance) {
    cancelScheduledClusterRender()
    mapInstance.off('movestart zoomstart', handleViewportStart)
    mapInstance.off('moveend', handleViewportEnd)
    mapInstance.off('zoomend', handleZoomEnd)
    mapInstance.off('click', handleMapBackgroundClick)
    mapInstance.remove()
    mapInstance = null
  }

  routeRenderer = null
})

watch(
  () => [props.photoClusters, props.photoPins, props.markers, props.routes, props.displayMode],
  () => {
    renderMap()
  },
)

watch(
  () => [
    props.displayMode,
    props.displayMode === 'pin'
      ? `photo-${String(props.selectedPhotoId ?? '')}`
      : `cluster-${String(props.selectedClusterId ?? '')}`,
  ],
  ([mode, selectedKey], [previousMode, previousSelectedKey] = []) => {
    if (selectedKey === 'photo-' || selectedKey === 'cluster-') {
      clearPendingPopupRequest()
      mapInstance?.closePopup()
    } else if (mode !== previousMode || selectedKey !== previousSelectedKey) {
      pendingPopupMarkerKey = selectedKey
    }

    scheduleRenderClusters(0)
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
    scheduleRenderClusters(0)
  },
)
</script>

<template>
  <div
    ref="mapRootElement"
    class="travel-map"
    :class="{ 'travel-map--fullscreen': isFullscreen, 'travel-map--moving': isMapMoving }"
  >
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
