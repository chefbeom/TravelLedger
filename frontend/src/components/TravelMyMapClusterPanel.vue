<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import { buildThumbnailUrl, THUMBNAIL_VARIANTS } from '../lib/mediaPreview'
import { getTravelJourneyFocusZoom, getTravelJourneyViewportOverviewZoom } from '../lib/travelJourney'
import { buildTravelMapPhotoGroups } from '../lib/travelMapPhotoGroups'
import { clampTravelMapPreviewSize } from '../lib/travelMapPreviewSize'
import TravelMapPhotoPreview from './TravelMapPhotoPreview.vue'

const DEFAULT_CENTER = [37.5547, 126.9706]
const DEFAULT_ZOOM = 11
const VIEWPORT_PADDING_RATIO = 0.35
const VIEWPORT_RENDER_DEBOUNCE_MS = 80
const CLIENT_CLUSTER_MIN_SIZE = 2
const CLIENT_CLUSTER_MAX_ZOOM = 17
const SMOOTH_ZOOM_DURATION = 0.45
const TILE_PROVIDERS = {
  osm: {
    url: 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',
    attribution: '&copy; OpenStreetMap contributors',
    subdomains: ['a', 'b', 'c'],
    detectRetina: true,
  },
  publicLight: {
    url: 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',
    attribution: '&copy; OpenStreetMap contributors',
    subdomains: ['a', 'b', 'c'],
    detectRetina: true,
  },
}

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
  journeyPhotoId: {
    type: [String, Number],
    default: null,
  },
  journeyPlaybackActive: {
    type: Boolean,
    default: false,
  },
  selectedMarkerId: {
    type: [String, Number],
    default: null,
  },
  displayMode: {
    type: String,
    default: 'cluster',
  },
  tileProvider: {
    type: String,
    default: 'osm',
  },
  fitRequestKey: {
    type: [Number, String],
    default: 0,
  },
  focusTarget: {
    type: Object,
    default: null,
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
const fullscreenToggleElement = ref(null)
const isMapMoving = ref(false)
const zoomLabel = ref(DEFAULT_ZOOM)
const previewAggregate = shallowRef(null)
const previewElement = ref(null)
const preferredPreviewSize = shallowRef(null)
const previewBounds = shallowRef({ width: 0, height: 0, compact: false })
const isPreviewResizing = ref(false)
const previewSize = computed(() => preferredPreviewSize.value && previewBounds.value.width > 0
  ? clampTravelMapPreviewSize(preferredPreviewSize.value, previewBounds.value)
  : null)
const previewStyle = computed(() => previewSize.value ? {
  '--map-preview-width': `${previewSize.value.width}px`,
  '--map-preview-height': `${previewSize.value.height}px`,
} : {})
const pinPopupContent = shallowRef(null)
const previewPhoto = computed(() => {
  const aggregate = previewAggregate.value
  if (!aggregate) return null
  const photos = aggregate.isClientCluster
    ? aggregate.members.flatMap((member) => member.photoMembers ?? [])
    : (aggregate.photoMembers ?? [])
  const activeId = props.journeyPlaybackActive ? props.journeyPhotoId : (props.selectedPhotoId ?? props.journeyPhotoId)
  return photos.find((photo) => activeId != null && String(photo.mediaId) === String(activeId))
    ?? aggregate.representative
})

let mapInstance = null
let markerLayer = null
let routeLayer = null
let routeRenderer = null
let tileLayer = null
let hasFittedInitialView = false
let hasFittedDataView = false
let renderedMarkers = new Map()
let renderedAggregates = new Map()
let pendingPreviewMarkerKey = null
let mapRenderFrame = 0
let mapRenderTimer = 0
let mapResizeFrame = 0
let mapResizeTimer = 0
let suppressViewportClusterRenderUntil = 0
let previewOpenSequence = 0
let journeyLegSequence = 0
let isPreparingJourneyLeg = false
let mapResizeObserver = null
let dismissedJourneyPreviewPhotoId = null
let pinPopup = null
let fullscreenScrollPosition = null
let previewResizeSession = null

function isTouchMapDevice() {
  if (typeof window === 'undefined') {
    return false
  }

  return Boolean(window.matchMedia?.('(pointer: coarse)').matches || navigator.maxTouchPoints > 0)
}

function createMapOptions(extra = {}) {
  const touchDevice = isTouchMapDevice()

  return {
    zoomControl: true,
    scrollWheelZoom: !touchDevice,
    dragging: true,
    touchZoom: 'center',
    tap: true,
    keyboard: !touchDevice,
    inertia: true,
    bounceAtZoomLimits: false,
    ...extra,
  }
}

function scheduleMarkerPreview(markerKey, remainingAttempts = 6, sequence = previewOpenSequence) {
  const normalizedKey = String(markerKey ?? '')
  if (!normalizedKey) {
    return
  }

  requestAnimationFrame(() => {
    if (sequence !== previewOpenSequence) {
      return
    }

    if (props.journeyPlaybackActive && syncJourneyPreview()) {
      pendingPreviewMarkerKey = null
      return
    }

    const marker = renderedMarkers.get(normalizedKey)
    if (marker && mapInstance?.hasLayer(marker)) {
      pendingPreviewMarkerKey = null
      previewAggregate.value = renderedAggregates.get(normalizedKey) ?? previewAggregate.value
      return
    }

    if (remainingAttempts > 0) {
      scheduleMarkerPreview(normalizedKey, remainingAttempts - 1)
    }
  })
}

function requestMarkerPreview(markerKey) {
  const normalizedKey = String(markerKey ?? '')
  if (!normalizedKey) {
    return
  }

  pendingPreviewMarkerKey = normalizedKey
  previewOpenSequence += 1
}

function clearPendingPreviewRequest() {
  pendingPreviewMarkerKey = null
  previewOpenSequence += 1
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

const renderablePhotoItems = computed(() => buildTravelMapPhotoGroups(props.photoClusters, props.photoPins).map((group) => ({
    id: group.key,
    markerKey: group.key,
    isAggregate: group.photoCount > 1,
    isPhotoPin: !group.cluster,
    isRecordPin: false,
    representative: group.cluster ? normalizeCluster(group.cluster) : normalizePhotoPin(group.representative),
    photoMembers: group.photos.map(normalizePhotoPin).filter(Boolean),
    latitude: group.latitude,
    longitude: group.longitude,
    photoCount: group.photoCount,
    memoryCount: group.memoryCount,
    bounds: group.photos.length
      ? group.photos.map((photo) => [Number(photo.latitude), Number(photo.longitude)])
      : [[group.latitude, group.longitude]],
  })))

function resolveRenderableItems() {
  if (!props.journeyPlaybackActive) return renderablePhotoItems.value
  return renderablePhotoItems.value.map((group) => {
    const photo = group.photoMembers.find((item) => String(item.mediaId) === String(props.journeyPhotoId))
    // A location group's centroid may be outside the view of its active photo.
    return photo ? { ...group, latitude: photo.latitude, longitude: photo.longitude } : group
  })
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
      || (props.journeyPhotoId != null && String(aggregate.representative?.mediaId) === String(props.journeyPhotoId))
  }

  return String(aggregate?.representative?.id) === String(props.selectedClusterId)
}

function aggregateContainsSelection(aggregate) {
  if (!aggregate?.isClientCluster) {
    return isSelectedAggregate(aggregate) || (aggregate.photoMembers ?? []).some((photo) => (
      String(photo.mediaId) === String(props.selectedPhotoId)
      || (props.journeyPhotoId != null && String(photo.mediaId) === String(props.journeyPhotoId))
    ))
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

  clearPendingPreviewRequest()
  mapInstance.closePopup()

  const bounds = collectAggregateBounds(aggregate)
  if (bounds.length > 1) {
    const nextZoom = Math.min((mapInstance.getZoom() ?? DEFAULT_ZOOM) + 3, 18)
    mapInstance.fitBounds(bounds, {
      padding: [48, 48],
      maxZoom: nextZoom,
      animate: true,
      duration: SMOOTH_ZOOM_DURATION,
      easeLinearity: 0.2,
    })
    return
  }

  mapInstance.setView(
    [aggregate.latitude, aggregate.longitude],
    Math.min((mapInstance.getZoom() ?? DEFAULT_ZOOM) + 2, 18),
    {
      animate: true,
      duration: SMOOTH_ZOOM_DURATION,
      easeLinearity: 0.2,
    },
  )
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
  if (mapResizeFrame) {
    cancelAnimationFrame(mapResizeFrame)
    mapResizeFrame = 0
  }
  if (mapResizeTimer) {
    clearTimeout(mapResizeTimer)
    mapResizeTimer = 0
  }

  const resize = () => {
    mapResizeFrame = 0
    const map = mapInstance
    if (!map) return
    const center = previewAggregate.value && !props.journeyPlaybackActive
      ? L.latLng(previewAggregate.value.latitude, previewAggregate.value.longitude)
      : map.getCenter()
    const zoom = map.getZoom()

    // Mobile browser chrome can resize the visual viewport repeatedly while
    // scrolling. Refresh Leaflet without rebuilding every photo marker.
    suppressViewportClusterRenderUntil = Date.now() + (isTouchMapDevice() ? 420 : 180)
    map.invalidateSize({
      animate: false,
      pan: true,
      debounceMoveend: true,
    })
    if (previewAggregate.value && !props.journeyPlaybackActive) {
      // Fullscreen/window resize may run Leaflet's own resize first. Reapply
      // the selected position so its anchor is centered in the actual canvas.
      map.setView(center, zoom, { animate: false })
    }
  }

  const delay = isTouchMapDevice() ? 140 : 0
  if (delay) {
    mapResizeTimer = setTimeout(() => {
      mapResizeTimer = 0
      mapResizeFrame = requestAnimationFrame(resize)
    }, delay)
    return
  }

  mapResizeFrame = requestAnimationFrame(resize)
}

function cancelQueuedMapResize() {
  if (mapResizeFrame) {
    cancelAnimationFrame(mapResizeFrame)
    mapResizeFrame = 0
  }
  if (mapResizeTimer) {
    clearTimeout(mapResizeTimer)
    mapResizeTimer = 0
  }
}

function resolveTileProvider() {
  return TILE_PROVIDERS[props.tileProvider] ?? TILE_PROVIDERS.osm
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

function fitToAll({ animate = true } = {}) {
  if (!mapInstance) {
    return
  }

  const bounds = collectBounds()
  if (!bounds.length) {
    mapInstance.setView(DEFAULT_CENTER, DEFAULT_ZOOM, {
      animate,
      duration: SMOOTH_ZOOM_DURATION,
      easeLinearity: 0.2,
    })
    return
  }

  mapInstance.fitBounds(bounds, {
    padding: [40, 40],
    maxZoom: 16,
    animate,
    duration: SMOOTH_ZOOM_DURATION,
    easeLinearity: 0.2,
  })
}

function buildRecordMarkerIcon(marker, active) {
  const colorHex = normalizeColorHex(marker?.planColorHex, '#3182F6')
  const label = escapeHtml(String(marker?.category || marker?.title || marker?.placeName || '핀').slice(0, 2))

  return L.divIcon({
    className: 'travel-map__icon-root',
    html: `<svg class="travel-map-pin-glyph travel-map-pin-glyph--record${active ? ' is-active' : ''}" viewBox="0 0 44 52" style="--map-pin-color:${colorHex}" aria-hidden="true" focusable="false">
      <path class="travel-map-pin-glyph__shape" d="M22 1.5C10.7 1.5 1.5 10.3 1.5 21.1c0 12.1 16.4 27.6 19.2 30.1a1.9 1.9 0 0 0 2.6 0c2.8-2.5 19.2-18 19.2-30.1C42.5 10.3 33.3 1.5 22 1.5Z" />
      <circle class="travel-map-pin-glyph__center" cx="22" cy="20.5" r="9.5" />
      <text class="travel-map-pin-glyph__label" x="22" y="24" text-anchor="middle">${label}</text>
    </svg>`,
    iconSize: [44, 52],
    iconAnchor: [22, 50],
    popupAnchor: [0, -46],
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

  const clusterCount = aggregate?.photoCount || 0
  const colorHex = normalizeColorHex(aggregate?.representative?.planColorHex, '#3182F6')
  if (props.displayMode === 'cluster') {
    const photoUrl = aggregate?.representative?.representativePhotoUrl || aggregate?.representative?.photoUrl
    const image = photoUrl
      ? `<img class="travel-map-photo-cluster__image" src="${escapeHtml(buildThumbnailUrl(photoUrl, THUMBNAIL_VARIANTS.pin))}" alt="" decoding="async" draggable="false" />`
      : '<span class="travel-map-photo-cluster__placeholder">사진</span>'
    return L.divIcon({
      className: 'travel-map__icon-root travel-map__photo-icon-root',
      html: `<div class="travel-cluster-pin travel-map-photo-cluster${active ? ' is-active' : ''}" style="--map-cluster-color:${colorHex}">${image}<span class="travel-map-photo-cluster__count">${escapeHtml(formatCompactCount(clusterCount))}</span></div>`,
      iconSize: [60, 60],
      iconAnchor: [30, 30],
      popupAnchor: [0, -28],
    })
  }
  const markerBody = aggregate?.isPhotoPin && clusterCount === 1
    ? '<circle class="travel-map-pin-glyph__center" cx="22" cy="20.5" r="7.2" />'
    : `<circle class="travel-map-pin-glyph__center travel-map-pin-glyph__center--count" cx="22" cy="20.5" r="10" />
       <text class="travel-map-pin-glyph__count" x="22" y="24" text-anchor="middle">${escapeHtml(formatCompactCount(clusterCount))}</text>`

  return L.divIcon({
    className: 'travel-map__icon-root',
    html: `<svg class="travel-map-pin-glyph${aggregate?.isPhotoPin ? ' travel-map-pin-glyph--photo' : ' travel-map-pin-glyph--cluster'}${active ? ' is-active' : ''}" viewBox="0 0 44 52" style="--map-pin-color:${colorHex}" aria-hidden="true" focusable="false">
      <path class="travel-map-pin-glyph__shape" d="M22 1.5C10.7 1.5 1.5 10.3 1.5 21.1c0 12.1 16.4 27.6 19.2 30.1a1.9 1.9 0 0 0 2.6 0c2.8-2.5 19.2-18 19.2-30.1C42.5 10.3 33.3 1.5 22 1.5Z" />
      ${markerBody}
    </svg>`,
    iconSize: [44, 52],
    iconAnchor: [22, 50],
    popupAnchor: [0, -46],
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

function closeMapPreview({ resetDismissal = false } = {}) {
  endPreviewResize()
  previewAggregate.value = null
  pinPopup?.remove()
  if (resetDismissal) dismissedJourneyPreviewPhotoId = null
}

function updatePreviewBounds() {
  const stage = mapElement.value?.parentElement
  if (!stage) return
  previewBounds.value = {
    width: stage.clientWidth,
    height: stage.clientHeight,
    compact: window.matchMedia('(max-width: 760px)').matches,
  }
}

function startPreviewResize(event) {
  if (event.button !== 0 || previewResizeSession || !previewElement.value) return
  event.preventDefault()
  event.stopPropagation()
  updatePreviewBounds()
  const rect = previewElement.value.getBoundingClientRect()
  const handle = event.currentTarget
  previewResizeSession = {
    pointerId: event.pointerId, handle, x: event.clientX, y: event.clientY,
    width: rect.width, height: rect.height, compact: previewBounds.value.compact,
  }
  preferredPreviewSize.value = clampTravelMapPreviewSize(rect, previewBounds.value)
  isPreviewResizing.value = true
  handle.setPointerCapture(event.pointerId)
}

function movePreviewResize(event) {
  const session = previewResizeSession
  if (!session || session.pointerId !== event.pointerId) return
  event.preventDefault()
  event.stopPropagation()
  updatePreviewBounds()
  // Desktop is centered vertically; its lower corner travels by half the height
  // change. The compact panel is bottom-anchored, so resize from its top corner.
  preferredPreviewSize.value = clampTravelMapPreviewSize({
    width: session.width + event.clientX - session.x,
    height: session.height + (event.clientY - session.y) * (session.compact ? -1 : 2),
  }, previewBounds.value)
}

function endPreviewResize(event) {
  const session = previewResizeSession
  if (!session || (event && event.pointerId !== session.pointerId)) return
  previewResizeSession = null
  isPreviewResizing.value = false
  if (session.handle.hasPointerCapture(session.pointerId)) session.handle.releasePointerCapture(session.pointerId)
}

function resetPreviewSize() {
  endPreviewResize()
  preferredPreviewSize.value = null
}

function handlePreviewResizeKey(event) {
  if (event.key === 'Home') {
    event.preventDefault()
    event.stopPropagation()
    resetPreviewSize()
    return
  }
  const delta = { ArrowLeft: [-16, 0], ArrowRight: [16, 0], ArrowUp: [0, -16], ArrowDown: [0, 16] }[event.key]
  if (!delta || !previewElement.value) return
  event.preventDefault()
  event.stopPropagation()
  updatePreviewBounds()
  const rect = previewElement.value.getBoundingClientRect()
  preferredPreviewSize.value = clampTravelMapPreviewSize({ width: rect.width + delta[0], height: rect.height + delta[1] }, previewBounds.value)
}

async function syncPinPopup() {
  const photo = previewPhoto.value
  const aggregate = previewAggregate.value
  if (!photo || !aggregate || !mapInstance) {
    pinPopup?.remove()
    return
  }
  if (!pinPopup) {
    const content = document.createElement('div')
    L.DomEvent.disableClickPropagation(content)
    L.DomEvent.disableScrollPropagation(content)
    pinPopupContent.value = content
    // A standalone popup survives marker-layer rebuilding during playback.
    // Vue renders the content with Teleport; record text is never injected as HTML.
    pinPopup = L.popup({
      className: 'travel-map-pin-popup',
      autoPan: false,
      closeOnClick: false,
      maxWidth: 260,
      minWidth: 220,
      offset: [0, -46],
    }).setContent(content)
    pinPopup.on('remove', () => {
      if (previewPhoto.value) dismissMapPreview()
    })
  }
  const position = props.journeyPlaybackActive ? photo : aggregate
  pinPopup.setLatLng([position.latitude, position.longitude])
  if (!mapInstance.hasLayer(pinPopup)) pinPopup.openOn(mapInstance)
  await nextTick()
  if (previewPhoto.value && mapInstance?.hasLayer(pinPopup)) pinPopup.update()
}

function syncJourneyPreview() {
  if (!mapInstance || !props.journeyPlaybackActive || props.journeyPhotoId == null) return false
  const mediaId = String(props.journeyPhotoId)
  if (dismissedJourneyPreviewPhotoId === mediaId) return true
  const group = renderablePhotoItems.value.find((item) => item.photoMembers.some((photo) => String(photo.mediaId) === mediaId))
  const photo = group?.photoMembers.find((item) => String(item.mediaId) === mediaId)
  if (!photo) return false
  // The preview lives outside Leaflet's layers, so route pans and marker
  // rebuilding cannot remove it during the current photo's interval.
  previewAggregate.value = group
  return true
}

function dismissMapPreview() {
  clearPendingPreviewRequest()
  if (props.journeyPlaybackActive && props.journeyPhotoId != null) {
    dismissedJourneyPreviewPhotoId = String(props.journeyPhotoId)
  } else {
    emit('clear-selection')
  }
  closeMapPreview()
}

function openPreviewPhoto() {
  if (previewPhoto.value) emit('preview-cluster', previewPhoto.value)
}

async function centerPreviewAggregate(aggregate) {
  await nextTick()
  if (!mapInstance || previewAggregate.value !== aggregate) return
  mapInstance.invalidateSize({ animate: false, pan: true })
  mapInstance.panTo([aggregate.latitude, aggregate.longitude], {
    animate: true, duration: SMOOTH_ZOOM_DURATION, easeLinearity: 0.2,
  })
}

function renderClusters() {
  if (!mapInstance || !markerLayer) {
    return
  }

  cancelScheduledClusterRender()
  markerLayer.clearLayers()
  renderedMarkers = new Map()
  renderedAggregates = new Map()

  const aggregates = buildViewportAggregates(resolveRenderableItems())
  let selectedMarkerKey = null
  aggregates.forEach((aggregate) => {
    const containsSelected = aggregateContainsSelection(aggregate)
    if (containsSelected) {
      selectedMarkerKey = aggregate.markerKey
    }

    const marker = L.marker([aggregate.latitude, aggregate.longitude], {
      icon: buildClusterIcon(aggregate, containsSelected),
      title: `${aggregate.representative?.title || aggregate.representative?.placeName || '여행 사진'} · 사진 ${aggregate.photoCount}장`,
      bubblingMouseEvents: false,
    })

    marker.on('click', (event) => {
      if (event?.originalEvent) {
        L.DomEvent.stopPropagation(event.originalEvent)
        L.DomEvent.preventDefault(event.originalEvent)
      }

      previewAggregate.value = aggregate
      requestMarkerPreview(aggregate.markerKey)
      selectRepresentativeAggregate(aggregate)
      centerPreviewAggregate(aggregate)
      scheduleRenderClusters(0)
    })
    renderedMarkers.set(String(aggregate.markerKey), marker)
    renderedAggregates.set(String(aggregate.markerKey), aggregate)

    marker.addTo(markerLayer)
  })

  if (props.journeyPlaybackActive && syncJourneyPreview()) {
    pendingPreviewMarkerKey = null
  } else if (pendingPreviewMarkerKey) {
    const normalizedPendingKey = String(pendingPreviewMarkerKey)
    if (renderedMarkers.has(normalizedPendingKey)) {
      scheduleMarkerPreview(normalizedPendingKey, 6, previewOpenSequence)
    } else if (selectedMarkerKey) {
      pendingPreviewMarkerKey = selectedMarkerKey
      scheduleMarkerPreview(selectedMarkerKey, 6, previewOpenSequence)
    }
  }
}

function renderMap({ shouldFit = false } = {}) {
  renderRoutes()

  const hasDataBounds = collectBounds().length > 0
  if (!hasFittedInitialView || shouldFit || (hasDataBounds && !hasFittedDataView)) {
    hasFittedInitialView = true
    hasFittedDataView = hasDataBounds || hasFittedDataView
    fitToAll({ animate: !shouldFit })
    queueMapResize()
    scheduleRenderClusters(0)
    return
  }

  renderClusters()
  queueMapResize()
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

async function setFullscreen(value, { restoreFocus = true } = {}) {
  if (isFullscreen.value === value) return
  // Native fullscreen reserves Escape for the browser, independently of
  // preventDefault. Use a viewport-filling app layer to close one layer at a time
  // without requiring Keyboard Lock permission or re-entering native fullscreen.
  if (value) fullscreenScrollPosition = { left: window.scrollX, top: window.scrollY }
  isFullscreen.value = value
  emit('fullscreen-change', value)
  await nextTick()
  if (isFullscreen.value !== value) return
  updatePreviewBounds()
  queueMapResize()
  if (value) mapRootElement.value?.focus({ preventScroll: true })
  else {
    if (restoreFocus) {
      if (fullscreenScrollPosition) window.scrollTo({ ...fullscreenScrollPosition, behavior: 'instant' })
      fullscreenToggleElement.value?.focus({ preventScroll: true })
    }
    fullscreenScrollPosition = null
  }
}

function toggleFullscreen() {
  setFullscreen(!isFullscreen.value)
}

function handleViewportStart() {
  isMapMoving.value = true
  cancelScheduledClusterRender()
}

function handleViewportEnd() {
  isMapMoving.value = false
  zoomLabel.value = mapInstance?.getZoom() ?? DEFAULT_ZOOM

  if (Date.now() < suppressViewportClusterRenderUntil) {
    scheduleRenderClusters(suppressViewportClusterRenderUntil - Date.now())
    return
  }

  scheduleRenderClusters(0)
}

function handleZoomEnd() {
  handleViewportEnd()
}

function zoomMap(direction) {
  if (direction > 0) {
    mapInstance?.zoomIn()
  } else {
    mapInstance?.zoomOut()
  }
}

function resolveJourneyFocusZoom(latitude, longitude) {
  return getTravelJourneyFocusZoom({
    latitude,
    longitude,
    locations: [...renderablePhotoItems.value, ...(props.markers ?? [])],
    project: mapInstance ? (point, zoom) => mapInstance.project(point, zoom) : null,
  })
}

function journeyLatLng(point) {
  if (point?.latitude == null || point?.longitude == null) return null
  const latitude = Number(point?.latitude)
  const longitude = Number(point?.longitude)
  return Number.isFinite(latitude) && Number.isFinite(longitude)
    ? L.latLng(latitude, longitude)
    : null
}

function isJourneyPointVisible(point) {
  if (!mapInstance) return false
  const size = mapInstance.getSize()
  const pixel = mapInstance.latLngToContainerPoint(point)
  const padding = 64
  return pixel.x >= padding && pixel.x <= size.x - padding
    && pixel.y >= padding && pixel.y <= size.y - padding
}

function resolveJourneyOverviewZoom(current, next) {
  const size = mapInstance.getSize()
  return getTravelJourneyViewportOverviewZoom({
    current,
    next,
    currentZoom: mapInstance.getZoom(),
    minZoom: mapInstance.getMinZoom(),
    width: size.x,
    height: size.y,
    project: (point, zoom) => mapInstance.project(point, zoom),
  })
}

function waitForJourneyMapMove(target, zoom, duration, sequence) {
  return new Promise((resolve) => {
    const map = mapInstance
    if (!map || sequence !== journeyLegSequence) {
      resolve(false)
      return
    }
    if (map.getCenter().distanceTo(target) < 2 && Math.abs(map.getZoom() - zoom) < 0.05) {
      resolve(true)
      return
    }

    let timeoutId = null
    const finish = () => {
      map.off('moveend', finish)
      clearTimeout(timeoutId)
      resolve(sequence === journeyLegSequence && map === mapInstance)
    }
    map.once('moveend', finish)
    timeoutId = setTimeout(finish, duration * 1000 + 500)
    map.flyTo(target, zoom, { animate: true, duration })
  })
}

function waitForJourneyTiles(sequence) {
  return new Promise((resolve) => {
    const layer = tileLayer
    if (!layer?.isLoading?.() || sequence !== journeyLegSequence) {
      resolve()
      return
    }

    let timeoutId = null
    const finish = () => {
      layer.off('load', finish)
      clearTimeout(timeoutId)
      resolve()
    }
    layer.once('load', finish)
    timeoutId = setTimeout(finish, 1500)
  })
}

async function prepareJourneyLeg(currentPoint, nextPoint, { focusCurrent = true } = {}) {
  const current = journeyLatLng(currentPoint)
  if (!mapInstance || !current) return { overview: false }

  const sequence = ++journeyLegSequence
  isPreparingJourneyLeg = true
  mapInstance.stop()
  try {
    await nextTick()
    if (sequence !== journeyLegSequence || !mapInstance) return { cancelled: true }
    mapInstance.invalidateSize({ animate: false, pan: true })
    if (focusCurrent) {
      const zoom = resolveJourneyFocusZoom(current.lat, current.lng)
      if (!await waitForJourneyMapMove(current, zoom, 0.65, sequence)) return { cancelled: true }
      await waitForJourneyTiles(sequence)
    }
    if (sequence !== journeyLegSequence || !mapInstance) return { cancelled: true }

    const next = journeyLatLng(nextPoint)
    if (!next || isJourneyPointVisible(next)) return { overview: false }

    const zoom = resolveJourneyOverviewZoom(current, next)
    if (!await waitForJourneyMapMove(current, zoom, 0.85, sequence)) return { cancelled: true }
    await nextTick()
    await waitForJourneyTiles(sequence)
    await new Promise((resolve) => setTimeout(resolve, 300))
    if (sequence !== journeyLegSequence || !mapInstance) return { cancelled: true }
    return { overview: true, zoom }
  } finally {
    if (sequence === journeyLegSequence) isPreparingJourneyLeg = false
  }
}

function cancelJourneyLeg() {
  journeyLegSequence += 1
  isPreparingJourneyLeg = false
  mapInstance?.stop()
}

defineExpose({ prepareJourneyLeg, cancelJourneyLeg })

function handleMapBackgroundClick() {
  clearPendingPreviewRequest()
  if (props.journeyPlaybackActive && props.journeyPhotoId != null) {
    dismissedJourneyPreviewPhotoId = String(props.journeyPhotoId)
  }
  closeMapPreview()
  mapInstance?.closePopup()
  emit('clear-selection')
}

function handleCanvasBackgroundClick(event) {
  if (event.target?.closest?.('.leaflet-marker-icon, .leaflet-popup, .leaflet-control, .leaflet-interactive')) return
  if (mapInstance?.dragging?.moved()) return
  handleMapBackgroundClick()
}

function handleOutsidePreviewPointer(event) {
  // Photo details may be teleported outside the map in non-fullscreen mode.
  // Closing that newer layer must not also dismiss the underlying preview.
  if (event.target?.closest?.('[data-map-photo-detail="true"]')) return
  if (props.active && previewPhoto.value && !mapRootElement.value?.contains(event.target)) dismissMapPreview()
}

function handleFullscreenEscape(event) {
  if (!props.active) return
  if (event.key === 'Tab' && isFullscreen.value) {
    const scope = mapRootElement.value?.querySelector('[data-map-photo-detail="true"]') || mapRootElement.value
    const actions = [...(scope?.querySelectorAll('button, a[href], input, select, textarea, [tabindex]') ?? [])]
      .filter((element) => !element.matches(':disabled') && element.tabIndex >= 0 && element.getClientRects().length > 0)
    const first = actions[0]
    const last = actions.at(-1)
    const focused = document.activeElement
    if (!first || !actions.includes(focused) || (event.shiftKey && focused === first) || (!event.shiftKey && focused === last)) {
      event.preventDefault()
      event.stopImmediatePropagation?.()
      const target = (event.shiftKey ? last : first) || mapRootElement.value
      target?.focus({ preventScroll: true })
    }
    return
  }
  if (event.key !== 'Escape') {
    return
  }

  const photoDetail = document.querySelector('[data-map-photo-detail="true"]')
  const closeAction = photoDetail?.querySelector('[data-modal-close]')
  const consumeEscape = () => {
    event.preventDefault()
    event.stopImmediatePropagation?.()
    event.returnValue = false
  }
  if (event.repeat && (isFullscreen.value || previewPhoto.value)) {
    consumeEscape()
    return
  }
  if (photoDetail) {
    if (!isFullscreen.value || !mapRootElement.value?.contains(photoDetail)) return
    // Close the nested detail before the map preview or fullscreen itself.
    consumeEscape()
    if (closeAction && !closeAction.disabled) closeAction.click()
  } else if (previewPhoto.value) {
    consumeEscape()
    dismissMapPreview()
  } else if (isFullscreen.value && (props.selectedClusterId != null || props.selectedPhotoId != null || props.selectedMarkerId != null)) {
    consumeEscape()
    emit('clear-selection')
  } else if (isFullscreen.value) {
    consumeEscape()
    setFullscreen(false)
  }
}

onMounted(() => {
  document.addEventListener('pointerdown', handleOutsidePreviewPointer)
  window.addEventListener('keydown', handleFullscreenEscape, { capture: true })

  mapInstance = L.map(mapElement.value, {
    ...createMapOptions({
      zoomControl: false,
      maxZoom: 20,
      preferCanvas: true,
      zoomAnimation: true,
      markerZoomAnimation: true,
      fadeAnimation: true,
      zoomSnap: 0.5,
      zoomDelta: 0.5,
      wheelPxPerZoomLevel: 96,
      wheelDebounceTime: 28,
      easeLinearity: 0.2,
    }),
  }).setView(resolveInitialCenter(), DEFAULT_ZOOM)

  const provider = resolveTileProvider()
  tileLayer = L.tileLayer(provider.url, {
    attribution: provider.attribution,
    subdomains: provider.subdomains,
    updateWhenZooming: false,
    updateWhenIdle: true,
    keepBuffer: 3,
    detectRetina: provider.detectRetina,
    maxNativeZoom: 19,
    maxZoom: 20,
  }).addTo(mapInstance)

  markerLayer = L.layerGroup().addTo(mapInstance)
  routeLayer = L.layerGroup().addTo(mapInstance)
  routeRenderer = L.canvas({ padding: 0.5 })
  mapInstance.on('movestart zoomstart', handleViewportStart)
  mapInstance.on('moveend', handleViewportEnd)
  mapInstance.on('zoomend', handleZoomEnd)
  zoomLabel.value = mapInstance.getZoom()
  emit('fullscreen-change', isFullscreen.value)
  renderMap({ shouldFit: true })
  mapInstance.whenReady(() => queueMapResize())
  updatePreviewBounds()
  if (typeof ResizeObserver !== 'undefined') {
    mapResizeObserver = new ResizeObserver(() => {
      updatePreviewBounds()
      queueMapResize()
    })
    mapResizeObserver.observe(mapElement.value)
  }
})

onBeforeUnmount(() => {
  cancelJourneyLeg()
  mapResizeObserver?.disconnect()
  document.removeEventListener('pointerdown', handleOutsidePreviewPointer)
  window.removeEventListener('keydown', handleFullscreenEscape, { capture: true })
  cancelQueuedMapResize()

  closeMapPreview({ resetDismissal: true })
  if (mapInstance) {
    cancelScheduledClusterRender()
    mapInstance.off('movestart zoomstart', handleViewportStart)
    mapInstance.off('moveend', handleViewportEnd)
    mapInstance.off('zoomend', handleZoomEnd)
    mapInstance.remove()
    mapInstance = null
  }

  tileLayer = null
  routeRenderer = null
  pinPopup = null
  pinPopupContent.value = null
})

watch([previewPhoto, previewAggregate, () => props.journeyPlaybackActive], syncPinPopup, { flush: 'post' })

watch(
  () => [props.photoClusters, props.photoPins, props.markers, props.routes, props.displayMode],
  () => {
    renderMap()
  },
)

watch(
  () => [
    props.displayMode,
    props.selectedPhotoId != null
      ? `photo-${String(props.selectedPhotoId)}`
      : `cluster-${String(props.selectedClusterId ?? '')}`,
  ],
  ([mode, selectedKey], [previousMode, previousSelectedKey] = []) => {
    if (selectedKey === 'photo-' || selectedKey === 'cluster-') {
      clearPendingPreviewRequest()
      if (!props.journeyPlaybackActive) {
        closeMapPreview()
        mapInstance?.closePopup()
      }
    } else if (mode !== previousMode || selectedKey !== previousSelectedKey) {
      pendingPreviewMarkerKey = selectedKey
    }

    scheduleRenderClusters(0)
  },
  { immediate: true },
)

watch(
  () => [props.journeyPhotoId, props.journeyPlaybackActive],
  ([mediaId, isPlaying], [previousMediaId, wasPlaying] = []) => {
    if (mediaId == null || mediaId === '') {
      closeMapPreview({ resetDismissal: true })
      return
    }
    if (String(mediaId) === String(previousMediaId) && (!isPlaying || wasPlaying)) {
      return
    }
    dismissedJourneyPreviewPhotoId = null
    if (isPlaying) syncJourneyPreview()
    pendingPreviewMarkerKey = `photo-${String(mediaId)}`
    previewOpenSequence += 1
    scheduleRenderClusters(0)
  },
)

watch(
  () => props.active,
  async (value) => {
    if (!value) {
      endPreviewResize()
      await setFullscreen(false, { restoreFocus: false })
      return
    }
    if (!mapInstance) {
      return
    }

    await nextTick()
    queueMapResize()
    scheduleRenderClusters(0)
  },
)

watch(
  () => props.fitRequestKey,
  () => {
    if (!mapInstance) {
      return
    }

    fitToAll({ animate: true })
    queueMapResize()
  },
)

watch(
  () => [
    props.focusTarget?.requestId,
    props.focusTarget?.latitude,
    props.focusTarget?.longitude,
    props.focusTarget?.keepZoom,
    props.focusTarget?.autoZoom,
    props.focusTarget?.zoom,
    props.focusTarget?.duration,
  ],
  async ([requestId, rawLatitude, rawLongitude, keepZoom, autoZoom, rawZoom, rawDuration]) => {
    if (requestId == null || rawLatitude == null || rawLongitude == null || !mapInstance) {
      return
    }

    const latitude = Number(rawLatitude)
    const longitude = Number(rawLongitude)
    if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) {
      return
    }

    await nextTick()
    if (!mapInstance || isPreparingJourneyLeg) {
      return
    }
    mapInstance.invalidateSize({ animate: false, pan: true })

    const targetZoom = Number.isFinite(Number(rawZoom))
      ? Math.max(2, Math.min(20, Number(rawZoom)))
      : keepZoom
        ? mapInstance.getZoom()
        : autoZoom
          ? resolveJourneyFocusZoom(latitude, longitude)
          : Math.min(20, Math.max(mapInstance.getZoom(), 15))
    const duration = Number.isFinite(Number(rawDuration)) ? Math.max(0.05, Number(rawDuration)) : 0.8
    if (keepZoom) {
      mapInstance.panTo([latitude, longitude], { animate: true, duration, easeLinearity: 0.2 })
      return
    }
    mapInstance.flyTo([latitude, longitude], targetZoom, { animate: true, duration })
  },
)
</script>

<template>
  <div
    ref="mapRootElement"
    class="travel-map"
    :role="isFullscreen ? 'dialog' : undefined"
    :aria-modal="isFullscreen ? 'true' : undefined"
    :aria-label="isFullscreen ? '여행 지도 전체 화면' : undefined"
    tabindex="-1"
    :class="{
      'travel-map--fullscreen': isFullscreen,
      'travel-map--moving': isMapMoving,
      'travel-map--public': props.tileProvider === 'publicLight',
      'travel-map--has-preview': Boolean(previewPhoto),
    }"
  >
    <div class="travel-map__toolbar" @click.stop>
      <div class="travel-map__toolbar-group">
        <span class="travel-map__toolbar-label">지도 확대</span>
        <button class="travel-map__toolbar-button travel-map__zoom-button" type="button" aria-label="지도 축소" @click="zoomMap(-1)">−</button>
        <strong class="travel-cluster-map__zoom">{{ zoomLabel }}</strong>
        <button class="travel-map__toolbar-button travel-map__zoom-button" type="button" aria-label="지도 확대" @click="zoomMap(1)">+</button>
      </div>

      <div class="travel-map__toolbar-group">
        <span class="travel-map__toolbar-label">클러스터 기준</span>
        <small class="travel-cluster-map__legend">
          {{ props.displayMode === 'pin' ? '핀 보기: 가까운 사진을 개수 표시 핀으로 묶음' : '클러스터 보기: 위치별 대표 사진 썸네일 표시' }}
        </small>
      </div>

      <div class="travel-map__toolbar-group">
        <button class="travel-map__toolbar-button" type="button" @click="fitToAll">전체 보기</button>
        <button ref="fullscreenToggleElement" class="travel-map__toolbar-button" type="button" :aria-expanded="isFullscreen" @click="toggleFullscreen">
          {{ isFullscreen ? '전체 화면 종료' : '전체 화면' }}
        </button>
      </div>

      <div v-if="isFullscreen" class="travel-map__toolbar-group travel-map__toolbar-group--journey">
        <slot name="fullscreen-controls" :is-fullscreen="isFullscreen" />
      </div>
      <small v-if="isFullscreen" class="travel-map__escape-hint">Esc: 사진 상세 → 미리보기 → 전체 화면 순서로 닫기</small>
    </div>

    <div class="travel-map__stage">
      <aside v-if="previewPhoto" ref="previewElement" class="travel-map__preview" :class="{ 'travel-map__preview--sized': Boolean(previewSize), 'travel-map__preview--resizing': isPreviewResizing }" :style="previewStyle" @click.stop @pointerdown.stop @touchstart.stop>
        <TravelMapPhotoPreview
          resizable
          :photo="previewPhoto"
          :preview-width="previewSize?.width || 0"
          :photo-count="previewAggregate.photoCount"
          :memory-count="previewAggregate.memoryCount || previewPhoto.memoryCount || 0"
          @open="openPreviewPhoto"
          @close="dismissMapPreview"
          @reset-size="resetPreviewSize"
        />
        <button
          class="travel-map__preview-resize"
          type="button"
          aria-label="미리보기 크기 조절"
          title="드래그 또는 방향키로 크기 조절 · Home으로 초기화"
          @pointerdown="startPreviewResize"
          @pointermove="movePreviewResize"
          @pointerup="endPreviewResize"
          @pointercancel="endPreviewResize"
          @lostpointercapture="endPreviewResize"
          @keydown="handlePreviewResizeKey"
          @click.stop.prevent
        >
          <span aria-hidden="true">⤡</span>
        </button>
      </aside>
      <div ref="mapElement" class="travel-map__canvas" @click="handleCanvasBackgroundClick" />
      <Teleport v-if="previewPhoto && pinPopupContent" :to="pinPopupContent">
        <TravelMapPhotoPreview
          compact
          :photo="previewPhoto"
          :photo-count="previewAggregate.photoCount"
          :memory-count="previewAggregate.memoryCount || previewPhoto.memoryCount || 0"
          @open="openPreviewPhoto"
        />
      </Teleport>
      <div v-if="isFullscreen" class="travel-map__overlay" @click.stop>
        <slot name="fullscreen-overlay" :is-fullscreen="isFullscreen" :has-preview="Boolean(previewPhoto)" />
      </div>
    </div>
    <div v-if="isFullscreen" class="travel-map__fullscreen-dialog" @pointerdown.stop @pointerup.stop @touchstart.stop @touchend.stop @click.stop>
      <slot name="fullscreen-dialog" :is-fullscreen="isFullscreen" />
    </div>
  </div>
</template>
