<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import { formatCurrency, formatCurrencyByCode, formatDate, formatTime } from '../lib/uiFormat'

const DEFAULT_CENTER = [37.5547, 126.9706]
const DEFAULT_ZOOM = 11

const iconPresetMap = {
  general: { label: '📍' },
  lodging: { label: '🏠' },
  food: { label: '🍽' },
  cafe: { label: '☕' },
  museum: { label: '🏛' },
  sightseeing: { label: '📸' },
  shopping: { label: '🛍' },
  transit: { label: '🚌' },
  route: { label: '➜' },
}

const props = defineProps({
  markers: {
    type: Array,
    default: () => [],
  },
  routes: {
    type: Array,
    default: () => [],
  },
  draftPath: {
    type: Array,
    default: () => [],
  },
  showDraftPointMarkers: {
    type: Boolean,
    default: true,
  },
  selectedPoint: {
    type: Object,
    default: null,
  },
  enablePickLocation: {
    type: Boolean,
    default: true,
  },
  enableDrawRoute: {
    type: Boolean,
    default: false,
  },
  markerRadius: {
    type: Number,
    default: 8,
  },
  hintTitle: {
    type: String,
    default: '지도 상호작용',
  },
  hintText: {
    type: String,
    default: '지도를 눌러 위치를 선택하거나 경로 점을 추가할 수 있습니다.',
  },
  viewKey: {
    type: [String, Number],
    default: '',
  },
  draggableMarkers: {
    type: Boolean,
    default: false,
  },
  draggableDraftPath: {
    type: Boolean,
    default: false,
  },
  draggableSelectedPoint: {
    type: Boolean,
    default: false,
  },
  highlightedDraftIndex: {
    type: Number,
    default: -1,
  },
  initialMapSize: {
    type: String,
    default: 'default',
  },
  autoFit: {
    type: Boolean,
    default: true,
  },
})

const emit = defineEmits([
  'pick-location',
  'pick-route-point',
  'move-marker',
  'move-draft-point',
  'move-selected-point',
  'select-marker',
  'select-draft-point',
  'select-selected-point',
  'viewport-mode-change',
  'map-size-change',
])

const mapElement = ref(null)
const mapSize = ref(['compact', 'default', 'expanded'].includes(props.initialMapSize) ? props.initialMapSize : 'default')
const viewportMode = ref('keep')
const canFit = ref(false)
const searchQuery = ref('')
const searchResults = ref([])
const searchMessage = ref('')
const searchStatus = ref('idle')

let mapInstance = null
let markersLayer = null
let routesLayer = null
let draftLayer = null
let selectedLayer = null
let searchLayer = null
let hasFittedInitialView = false
let searchAbortController = null

function escapeHtml(value) {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

function normalizeColorHex(value, fallback = '#3182F6') {
  return /^#[0-9A-Fa-f]{6}$/.test(String(value || '').trim()) ? String(value).trim().toUpperCase() : fallback
}

function normalizeIconKey(value) {
  const text = String(value || '').trim().toLowerCase()

  if (!text) return 'general'
  if (text.includes('숙소') || text.includes('호텔') || text.includes('hostel') || text.includes('hotel')) return 'lodging'
  if (text.includes('음식') || text.includes('식당') || text.includes('맛집') || text.includes('restaurant') || text.includes('food')) return 'food'
  if (text.includes('카페') || text.includes('coffee') || text.includes('cafe')) return 'cafe'
  if (text.includes('박물관') || text.includes('전시') || text.includes('museum')) return 'museum'
  if (text.includes('관광') || text.includes('명소') || text.includes('랜드마크') || text.includes('spot') || text.includes('sight')) return 'sightseeing'
  if (text.includes('쇼핑') || text.includes('shopping') || text.includes('mall')) return 'shopping'
  if (text.includes('교통') || text.includes('이동') || text.includes('버스') || text.includes('택시') || text.includes('지하철') || text.includes('transit')) return 'transit'
  if (text.includes('route')) return 'route'
  return 'general'
}

function iconLabelForKey(iconKey) {
  return iconPresetMap[iconKey]?.label || iconPresetMap.general.label
}

function normalizePoint(pointLike) {
  const latitude = Number(pointLike?.latitude ?? pointLike?.lat)
  const longitude = Number(pointLike?.longitude ?? pointLike?.lng)

  if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) {
    return null
  }

  return {
    latitude: Number(latitude.toFixed(7)),
    longitude: Number(longitude.toFixed(7)),
  }
}

function thinLinePoints(points, maxPoints = 1200) {
  if (!Array.isArray(points) || points.length <= maxPoints) {
    return points
  }

  const reduced = []
  const step = Math.max(1, Math.ceil((points.length - 1) / (maxPoints - 1)))

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

function createPopupContent(marker) {
  const root = document.createElement('div')
  root.className = 'travel-map__popup'

  const mediaItems = Array.isArray(marker.mediaItems) ? marker.mediaItems : []
  const heroPhoto = marker.photoUrl || mediaItems.find((item) => item.contentType?.startsWith('image/'))?.contentUrl || ''

  if (heroPhoto) {
    const image = document.createElement('img')
    image.className = 'travel-map__popup-image'
    image.src = heroPhoto
    image.alt = marker.title || marker.placeName || '사진'
    root.appendChild(image)
  }

  const copy = document.createElement('div')
  copy.className = 'travel-map__popup-copy'

  const title = document.createElement('strong')
  title.textContent = marker.title || marker.placeName || '등록된 위치'
  copy.appendChild(title)

  const location = [marker.country, marker.region, marker.placeName].filter(Boolean).join(' / ')
  if (location) {
    const locationLine = document.createElement('span')
    locationLine.textContent = location
    copy.appendChild(locationLine)
  }

  const visitedAt = [formatDate(marker.visitedDate), marker.visitedTime ? formatTime(marker.visitedTime) : '']
    .filter((item) => item && item !== '-')
    .join(' ')
  if (visitedAt) {
    const dateLine = document.createElement('span')
    dateLine.textContent = visitedAt
    copy.appendChild(dateLine)
  }

  if (marker.amountKrw) {
    const amountLine = document.createElement('span')
    const originalAmount = marker.amount ? ` / ${formatCurrencyByCode(marker.amount, marker.currencyCode)}` : ''
    amountLine.textContent = `${formatCurrency(marker.amountKrw)}${originalAmount}`
    copy.appendChild(amountLine)
  }

  if (marker.uploadedBy) {
    const authorLine = document.createElement('span')
    authorLine.textContent = `작성자 ${marker.uploadedBy}`
    copy.appendChild(authorLine)
  }

  if (marker.photoCount || marker.receiptCount) {
    const metaLine = document.createElement('span')
    metaLine.textContent = `사진 ${marker.photoCount || 0}장 / 영수증 ${marker.receiptCount || 0}장`
    copy.appendChild(metaLine)
  }

  root.appendChild(copy)
  return root
}

function createSearchPopup(result) {
  const root = document.createElement('div')
  root.className = 'travel-map__popup'

  const copy = document.createElement('div')
  copy.className = 'travel-map__popup-copy'

  const title = document.createElement('strong')
  title.textContent = result.title || '검색 결과'
  copy.appendChild(title)

  const locationLine = document.createElement('span')
  locationLine.textContent = result.displayName
  copy.appendChild(locationLine)

  const coordinateLine = document.createElement('span')
  coordinateLine.textContent = `${result.latitude.toFixed(5)}, ${result.longitude.toFixed(5)}`
  copy.appendChild(coordinateLine)

  root.appendChild(copy)
  return root
}

function buildMarkerIcon({
  type = 'memory',
  colorHex = '#3182F6',
  iconKey = 'general',
  iconText = '',
  active = false,
}) {
  const safeIconKey = normalizeIconKey(iconKey)
  const safeText = escapeHtml(String(iconText || iconLabelForKey(safeIconKey)).slice(0, 2))
  const size = type === 'route' ? [34, 34] : type === 'selected' ? [42, 52] : [40, 52]
  const iconAnchor = type === 'route' ? [17, 17] : [20, 50]

  return L.divIcon({
    className: 'travel-map__icon-root',
    html: `
      <div
        class="travel-map__marker-icon travel-map__marker-icon--${type} travel-map__marker-icon--${safeIconKey}${active ? ' is-active' : ''}"
        style="--marker-color:${normalizeColorHex(colorHex)}"
      >
        <span>${safeText}</span>
      </div>
    `,
    iconSize: size,
    iconAnchor,
    popupAnchor: [0, -28],
    tooltipAnchor: [0, -18],
  })
}

function createMarkerLayer(marker) {
  const point = normalizePoint(marker)
  if (!point) {
    return null
  }

  const layer = L.marker([point.latitude, point.longitude], {
    draggable: props.draggableMarkers,
    icon: buildMarkerIcon({
      type: 'memory',
      colorHex: marker.colorHex || '#3182F6',
      iconKey: marker.iconKey || marker.category,
      iconText: marker.iconText,
    }),
  })

  layer.bindPopup(createPopupContent(marker))
  layer.on('click', () => emit('select-marker', marker))

  if (props.draggableMarkers) {
    layer.on('dragend', (event) => {
      emit('move-marker', {
        marker,
        point: normalizePoint(event.target.getLatLng()),
      })
    })
  }

  return layer
}

function collectBounds() {
  const points = []

  props.markers.forEach((marker) => {
    const point = normalizePoint(marker)
    if (point) {
      points.push([point.latitude, point.longitude])
    }
  })

  props.routes.forEach((route) => {
    ;(route.points ?? []).forEach((point) => {
      const normalized = normalizePoint(point)
      if (normalized) {
        points.push([normalized.latitude, normalized.longitude])
      }
    })
  })

  props.draftPath.forEach((point) => {
    const normalized = normalizePoint(point)
    if (normalized) {
      points.push([normalized.latitude, normalized.longitude])
    }
  })

  const selected = normalizePoint(props.selectedPoint)
  if (selected) {
    points.push([selected.latitude, selected.longitude])
  }

  return points
}

function describeDraftPoint(index, total) {
  if (index === 0) return '출발'
  if (index === total - 1) return '도착'
  return `${index + 1}번 경유지`
}

function clearSearchMarker() {
  searchLayer?.clearLayers()
}

function clearSearchResults({ keepQuery = true } = {}) {
  searchResults.value = []
  searchStatus.value = 'idle'
  searchMessage.value = ''
  if (!keepQuery) {
    searchQuery.value = ''
  }
}

function focusSearchResult(result) {
  if (!mapInstance) {
    return
  }

  clearSearchMarker()

  const marker = L.marker([result.latitude, result.longitude], {
    icon: buildMarkerIcon({
      type: 'selected',
      colorHex: '#0F172A',
      iconKey: 'general',
      iconText: '🔎',
      active: true,
    }),
  })

  marker.bindPopup(createSearchPopup(result))
  marker.addTo(searchLayer)
  marker.openPopup()
  mapInstance.setView([result.latitude, result.longitude], Math.max(mapInstance.getZoom(), 15))
  requestAnimationFrame(() => mapInstance?.invalidateSize(false))
}

async function submitSearch() {
  const query = searchQuery.value.trim()

  if (!query) {
    clearSearchMarker()
    clearSearchResults({ keepQuery: true })
    searchMessage.value = '검색어를 입력해 주세요.'
    return
  }

  if (searchAbortController) {
    searchAbortController.abort()
  }

  searchAbortController = typeof AbortController !== 'undefined' ? new AbortController() : null
  searchStatus.value = 'loading'
  searchMessage.value = ''
  searchResults.value = []

  try {
    const response = await fetch(
      `https://nominatim.openstreetmap.org/search?format=jsonv2&addressdetails=1&limit=6&q=${encodeURIComponent(query)}`,
      {
        headers: {
          'Accept-Language': 'ko,en',
        },
        signal: searchAbortController?.signal,
      },
    )

    if (!response.ok) {
      throw new Error('검색 요청에 실패했습니다.')
    }

    const payload = await response.json()
    searchResults.value = (Array.isArray(payload) ? payload : []).map((item) => ({
      id: item.place_id,
      title: item.name || String(item.display_name || '').split(',')[0] || query,
      displayName: item.display_name || query,
      latitude: Number(item.lat),
      longitude: Number(item.lon),
    })).filter((item) => Number.isFinite(item.latitude) && Number.isFinite(item.longitude))

    searchStatus.value = 'done'
    if (!searchResults.value.length) {
      searchMessage.value = '검색 결과가 없습니다. 한글 또는 영어로 다시 검색해 보세요.'
    } else {
      searchMessage.value = '검색 결과를 누르면 해당 위치로 지도가 이동합니다.'
    }
  } catch (error) {
    if (error?.name === 'AbortError') {
      return
    }
    searchStatus.value = 'error'
    searchMessage.value = '검색 중 문제가 발생했습니다. 잠시 후 다시 시도해 주세요.'
  } finally {
    searchAbortController = null
  }
}

function selectSearchResult(result) {
  searchQuery.value = result.displayName
  searchResults.value = []
  searchStatus.value = 'idle'
  searchMessage.value = '검색한 위치로 이동했습니다.'
  focusSearchResult(result)
}

function fitToAll() {
  if (!mapInstance) {
    return
  }

  const bounds = collectBounds()
  canFit.value = bounds.length > 0

  if (bounds.length) {
    mapInstance.fitBounds(bounds, { padding: [40, 40], maxZoom: 14 })
  } else {
    mapInstance.setView(DEFAULT_CENTER, DEFAULT_ZOOM)
  }

  hasFittedInitialView = true
  requestAnimationFrame(() => mapInstance?.invalidateSize(false))
}

function renderMapLayers({ shouldFit = false } = {}) {
  if (!mapInstance) {
    return
  }

  markersLayer.clearLayers()
  routesLayer.clearLayers()
  draftLayer.clearLayers()
  selectedLayer.clearLayers()

  props.markers.forEach((marker) => {
    const layer = createMarkerLayer(marker)
    if (layer) {
      layer.addTo(markersLayer)
    }
  })

  props.routes.forEach((route) => {
    const linePoints = thinLinePoints(
      (route.points ?? [])
        .map((point) => normalizePoint(point))
        .filter(Boolean),
    ).map((point) => [point.latitude, point.longitude])

    if (linePoints.length < 2) {
      return
    }

    const polyline = L.polyline(linePoints, {
      color: normalizeColorHex(route.colorHex || route.planColorHex, '#3182F6'),
      weight: 4,
      opacity: 0.85,
    })

    if (route.title) {
      polyline.bindTooltip(route.title)
    }

    polyline.addTo(routesLayer)
  })

  const draftPoints = props.draftPath
    .map((point) => normalizePoint(point))
    .filter(Boolean)

  if (draftPoints.length >= 2) {
    L.polyline(
      thinLinePoints(draftPoints).map((point) => [point.latitude, point.longitude]),
      {
        color: '#FF6B6B',
        weight: 4,
        opacity: 0.9,
        dashArray: '10 8',
      },
    ).addTo(draftLayer)
  }

  if (props.showDraftPointMarkers) {
    draftPoints.forEach((point, index) => {
      const isActive = props.highlightedDraftIndex === index
      const marker = L.marker([point.latitude, point.longitude], {
        draggable: props.draggableDraftPath,
        icon: buildMarkerIcon({
          type: 'route',
          colorHex: isActive ? '#111827' : '#FF6B6B',
          iconKey: 'route',
          iconText: String(index + 1),
          active: isActive,
        }),
      })

      marker.bindTooltip(describeDraftPoint(index, draftPoints.length))
      marker.on('click', () => emit('select-draft-point', index))

      if (props.draggableDraftPath) {
        marker.on('dragend', (event) => {
          emit('move-draft-point', {
            index,
            point: normalizePoint(event.target.getLatLng()),
          })
        })
      }

      marker.addTo(draftLayer)
    })
  }

  const selectedPoint = normalizePoint(props.selectedPoint)
  if (selectedPoint) {
    const marker = L.marker([selectedPoint.latitude, selectedPoint.longitude], {
      draggable: props.draggableSelectedPoint,
      icon: buildMarkerIcon({
        type: 'selected',
        colorHex: props.selectedPoint?.colorHex || '#111827',
        iconKey: props.selectedPoint?.iconKey || 'general',
        iconText: props.selectedPoint?.iconText || '📍',
        active: true,
      }),
    })

    marker.bindTooltip('현재 선택한 위치')
    marker.on('click', () => emit('select-selected-point', { ...props.selectedPoint, ...selectedPoint }))

    if (props.draggableSelectedPoint) {
      marker.on('dragend', (event) => {
        emit('move-selected-point', normalizePoint(event.target.getLatLng()))
      })
    }

    marker.addTo(selectedLayer)
  }

  canFit.value = collectBounds().length > 0

  if (!hasFittedInitialView) {
    hasFittedInitialView = true

    if (shouldFit) {
      fitToAll()
    } else {
      mapInstance.setView(DEFAULT_CENTER, DEFAULT_ZOOM)
      requestAnimationFrame(() => mapInstance?.invalidateSize(false))
    }
    return
  }

  if (shouldFit) {
    fitToAll()
    return
  }

  requestAnimationFrame(() => mapInstance?.invalidateSize(false))
}

function handleMapClick(event) {
  const point = normalizePoint(event.latlng)
  if (!point) {
    return
  }

  clearSearchMarker()
  clearSearchResults({ keepQuery: true })

  let handled = false

  if (props.enableDrawRoute) {
    emit('pick-route-point', point)
    handled = true
  }

  if (props.enablePickLocation) {
    emit('pick-location', point)
    handled = true
  }

  if (handled && viewportMode.value === 'follow') {
    mapInstance?.panTo([point.latitude, point.longitude], { animate: true, duration: 0.35 })
  }
}

function setViewportMode(mode) {
  viewportMode.value = mode === 'follow' ? 'follow' : 'keep'
}

function setMapSize(size) {
  if (['compact', 'default', 'expanded'].includes(size)) {
    mapSize.value = size
  }
}

onMounted(() => {
  mapInstance = L.map(mapElement.value, {
    zoomControl: true,
    scrollWheelZoom: true,
  }).setView(DEFAULT_CENTER, DEFAULT_ZOOM)

  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    attribution: '&copy; OpenStreetMap contributors',
  }).addTo(mapInstance)

  markersLayer = L.layerGroup().addTo(mapInstance)
  routesLayer = L.layerGroup().addTo(mapInstance)
  draftLayer = L.layerGroup().addTo(mapInstance)
  selectedLayer = L.layerGroup().addTo(mapInstance)
  searchLayer = L.layerGroup().addTo(mapInstance)

  mapInstance.on('click', handleMapClick)
  renderMapLayers({ shouldFit: props.autoFit })
})

onBeforeUnmount(() => {
  if (searchAbortController) {
    searchAbortController.abort()
    searchAbortController = null
  }

  if (mapInstance) {
    mapInstance.off('click', handleMapClick)
    mapInstance.remove()
    mapInstance = null
  }
})

watch(
  () => [
    props.markers,
    props.routes,
    props.draftPath,
    props.showDraftPointMarkers,
    props.selectedPoint,
    props.highlightedDraftIndex,
    props.markerRadius,
  ],
  () => {
    renderMapLayers()
  },
  { deep: true },
)

watch(
  () => props.viewKey,
  async () => {
    hasFittedInitialView = false
    clearSearchMarker()
    clearSearchResults({ keepQuery: false })
    await nextTick()
    renderMapLayers({ shouldFit: props.autoFit })
  },
)

watch(
  () => props.autoFit,
  async (value) => {
    if (!mapInstance) {
      return
    }
    hasFittedInitialView = false
    await nextTick()
    renderMapLayers({ shouldFit: value })
  },
)

watch(
  () => props.initialMapSize,
  (value) => {
    if (['compact', 'default', 'expanded'].includes(value)) {
      mapSize.value = value
    }
  },
)

watch(mapSize, async () => {
  await nextTick()
  emit('map-size-change', mapSize.value)
  requestAnimationFrame(() => mapInstance?.invalidateSize(false))
})

watch(viewportMode, (mode) => {
  emit('viewport-mode-change', mode)
})
</script>

<template>
  <div class="travel-map" :class="[`travel-map--${mapSize}`]">
    <div class="travel-map__stage">
      <div ref="mapElement" class="travel-map__canvas" />

      <div class="travel-map__toolbar" @click.stop>
        <div class="travel-map__search">
          <form class="travel-map__search-form" @submit.prevent="submitSearch">
            <input
              v-model="searchQuery"
              class="travel-map__search-input"
              type="search"
              placeholder="장소 검색: 서울역, 오사카성, Tokyo Station"
            />
            <button class="travel-map__toolbar-button travel-map__search-button" type="submit" :disabled="searchStatus === 'loading'">
              {{ searchStatus === 'loading' ? '검색 중...' : '검색' }}
            </button>
          </form>

          <div v-if="searchMessage || searchResults.length" class="travel-map__search-results">
            <p v-if="searchMessage" class="travel-map__search-message">{{ searchMessage }}</p>
            <button
              v-for="result in searchResults"
              :key="result.id"
              class="travel-map__search-result"
              type="button"
              @click="selectSearchResult(result)"
            >
              <strong>{{ result.title }}</strong>
              <span>{{ result.displayName }}</span>
            </button>
          </div>
        </div>

        <div class="travel-map__toolbar-group">
          <span class="travel-map__toolbar-label">시야</span>
          <button
            class="travel-map__toolbar-button"
            :class="{ 'is-active': viewportMode === 'keep' }"
            type="button"
            @click="setViewportMode('keep')"
          >
            고정
          </button>
          <button
            class="travel-map__toolbar-button"
            :class="{ 'is-active': viewportMode === 'follow' }"
            type="button"
            @click="setViewportMode('follow')"
          >
            따라가기
          </button>
        </div>

        <div class="travel-map__toolbar-group">
          <span class="travel-map__toolbar-label">크기</span>
          <button
            class="travel-map__toolbar-button"
            :class="{ 'is-active': mapSize === 'compact' }"
            type="button"
            @click="setMapSize('compact')"
          >
            작게
          </button>
          <button
            class="travel-map__toolbar-button"
            :class="{ 'is-active': mapSize === 'default' }"
            type="button"
            @click="setMapSize('default')"
          >
            보통
          </button>
          <button
            class="travel-map__toolbar-button"
            :class="{ 'is-active': mapSize === 'expanded' }"
            type="button"
            @click="setMapSize('expanded')"
          >
            크게
          </button>
        </div>

        <div class="travel-map__toolbar-group">
          <button class="travel-map__toolbar-button" type="button" :disabled="!canFit" @click="fitToAll">전체 보기</button>
        </div>

        <slot
          name="toolbar"
          :map-size="mapSize"
          :viewport-mode="viewportMode"
          :set-map-size="setMapSize"
          :set-viewport-mode="setViewportMode"
          :fit-to-all="fitToAll"
        />
      </div>
    </div>

    <div class="travel-map__hint">
      <strong>{{ hintTitle }}</strong>
      <span>{{ hintText }}</span>
    </div>
  </div>
</template>
