<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import { fetchTravelLoginMapPreview } from '../lib/api'

const DEFAULT_CENTER = [36.2, 127.8]
const DEFAULT_ZOOM = 6

const mapElement = ref(null)
const preview = ref(null)
const loading = ref(true)
const mapError = ref('')
let map = null
let featureLayer = null
let resizeObserver = null

function renderPreview() {
  if (!map || !preview.value) {
    return
  }

  featureLayer?.remove()
  featureLayer = L.featureGroup()

  for (const route of preview.value.routes ?? []) {
    const points = (route.points ?? [])
      .map((point) => [Number(point.latitude), Number(point.longitude)])
      .filter(([latitude, longitude]) => Number.isFinite(latitude) && Number.isFinite(longitude))
    if (points.length < 2) {
      continue
    }
    L.polyline(points, {
      color: '#57d7a0',
      weight: 4,
      opacity: 0.9,
      lineCap: 'round',
      lineJoin: 'round',
    }).addTo(featureLayer)
  }

  for (const marker of preview.value.markers ?? []) {
    const latitude = Number(marker.latitude)
    const longitude = Number(marker.longitude)
    if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) {
      continue
    }
    L.circleMarker([latitude, longitude], {
      radius: 7,
      color: '#f4fff9',
      weight: 2,
      fillColor: '#168b68',
      fillOpacity: 1,
    })
      .bindPopup(`여행 지점 ${marker.number}`)
      .addTo(featureLayer)
  }

  featureLayer.addTo(map)
  const bounds = featureLayer.getBounds()
  if (bounds.isValid()) {
    map.fitBounds(bounds.pad(0.18), { maxZoom: 11, animate: false })
  } else {
    map.setView(DEFAULT_CENTER, DEFAULT_ZOOM, { animate: false })
  }
}

async function loadPreview() {
  loading.value = true
  mapError.value = ''
  try {
    preview.value = await fetchTravelLoginMapPreview()
  } catch {
    preview.value = null
    mapError.value = '지도를 불러오지 못했습니다.'
  } finally {
    loading.value = false
    renderPreview()
  }
}

onMounted(() => {
  map = L.map(mapElement.value, {
    attributionControl: false,
    zoomControl: false,
    scrollWheelZoom: false,
    keyboard: true,
    dragging: true,
    tap: true,
  }).setView(DEFAULT_CENTER, DEFAULT_ZOOM)

  L.tileLayer('https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}{r}.png', {
    attribution: '&copy; OpenStreetMap contributors &copy; CARTO',
    subdomains: 'abcd',
    maxZoom: 19,
  }).addTo(map)
  L.control.zoom({ position: 'bottomright' }).addTo(map)

  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(() => map?.invalidateSize({ pan: false }))
    resizeObserver.observe(mapElement.value)
  }
  void loadPreview()
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  featureLayer?.remove()
  map?.remove()
  resizeObserver = null
  featureLayer = null
  map = null
})
</script>

<template>
  <section class="login-map-preview" aria-label="여행 지도 미리보기">
    <div ref="mapElement" class="login-map-preview__map" />
    <div class="login-map-preview__shade" aria-hidden="true" />

    <div class="login-map-preview__heading">
      <span class="login-map-preview__eyebrow">TRAVELLEDGER · MAP PREVIEW</span>
      <h2>기록이 여행의 지도가 됩니다</h2>
      <p>장소와 경로를 지도에서 직접 살펴보세요.</p>
    </div>

    <div v-if="preview?.enabled && (preview.markerCount || preview.routeCount)" class="login-map-preview__summary">
      <span>장소 {{ preview.markerCount }}곳</span>
      <span>경로 {{ preview.routeCount }}개</span>
    </div>
    <div v-else-if="!loading" class="login-map-preview__empty">
      <span class="login-map-preview__empty-mark" aria-hidden="true">＋</span>
      <strong>{{ mapError || '여행 기록을 지도 위에서 다시 만나보세요.' }}</strong>
      <span>로그인 화면에는 관리자가 공개 대상으로 선택한 지도만 표시됩니다.</span>
    </div>

    <div class="login-map-preview__footer">
      <span class="login-map-preview__status-dot" />
      <span>{{ loading ? '공개 지도를 확인하는 중' : '드래그하고 지점을 눌러 둘러보기' }}</span>
      <span class="login-map-preview__privacy">공개 선택된 경로만 표시</span>
      <span class="login-map-preview__attribution">
        © <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noreferrer">OpenStreetMap</a>
        · <a href="https://carto.com/attributions" target="_blank" rel="noreferrer">CARTO</a>
      </span>
    </div>
  </section>
</template>

<style scoped>
.login-map-preview {
  position: relative;
  min-height: 590px;
  overflow: hidden;
  border: 1px solid var(--line);
  background: var(--surface-soft);
  isolation: isolate;
}

.login-map-preview__map,
.login-map-preview__shade {
  position: absolute;
  inset: 0;
}

.login-map-preview__map {
  z-index: 0;
}

.login-map-preview__shade {
  z-index: 1;
  pointer-events: none;
  background: linear-gradient(180deg, rgba(12, 22, 25, 0.77), transparent 36%, transparent 62%, rgba(12, 22, 25, 0.72));
}

.login-map-preview__heading,
.login-map-preview__summary,
.login-map-preview__footer,
.login-map-preview__empty {
  position: absolute;
  z-index: 2;
}

.login-map-preview__heading {
  top: 30px;
  left: 32px;
  right: 32px;
  color: #f6faf8;
  pointer-events: none;
}

.login-map-preview__eyebrow {
  color: #9ce7c5;
  font-size: 0.72rem;
  font-weight: 800;
  letter-spacing: 0.14em;
}

.login-map-preview__heading h2 {
  margin: 16px 0 8px;
  font-size: clamp(1.5rem, 2.5vw, 2.25rem);
  letter-spacing: -0.04em;
}

.login-map-preview__heading p {
  margin: 0;
  color: rgba(246, 250, 248, 0.8);
}

.login-map-preview__summary {
  top: 30px;
  right: 28px;
  display: flex;
  gap: 8px;
  padding-top: 1.5rem;
  color: #eef7f2;
  font-size: 0.82rem;
  pointer-events: none;
}

.login-map-preview__summary span {
  padding: 7px 10px;
  border: 1px solid rgba(255, 255, 255, 0.32);
  background: rgba(15, 28, 30, 0.72);
}

.login-map-preview__empty {
  top: 50%;
  left: 50%;
  display: grid;
  justify-items: center;
  gap: 10px;
  width: min(350px, calc(100% - 40px));
  color: #253a35;
  text-align: center;
  transform: translate(-50%, -28%);
  pointer-events: none;
}

.login-map-preview__empty strong {
  font-size: 1.05rem;
}

.login-map-preview__empty > span:last-child {
  font-size: 0.82rem;
}

.login-map-preview__empty-mark {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border: 1px solid rgba(23, 80, 61, 0.45);
  border-radius: 50%;
  color: #17503d;
  font-size: 1.35rem;
}

.login-map-preview__footer {
  right: 24px;
  bottom: 22px;
  left: 24px;
  display: flex;
  align-items: center;
  gap: 9px;
  color: #f6faf8;
  font-size: 0.8rem;
  pointer-events: none;
}

.login-map-preview__status-dot {
  width: 8px;
  height: 8px;
  flex: 0 0 auto;
  border-radius: 50%;
  background: #80e1ae;
  box-shadow: 0 0 0 4px rgba(128, 225, 174, 0.16);
}

.login-map-preview__privacy {
  margin-left: auto;
  color: rgba(246, 250, 248, 0.78);
}

.login-map-preview__attribution {
  color: rgba(246, 250, 248, 0.78);
  font-size: 0.66rem;
  pointer-events: auto;
  white-space: nowrap;
}

.login-map-preview__attribution a {
  color: inherit;
  text-decoration: underline;
  text-underline-offset: 2px;
}

:deep(.leaflet-container) {
  font: inherit;
}

:deep(.leaflet-control-zoom a) {
  color: #20312d;
}

:deep(.leaflet-popup-content-wrapper),
:deep(.leaflet-popup-tip) {
  border-radius: 0;
}

@media (max-width: 760px) {
  .login-map-preview {
    min-height: 300px;
  }

  .login-map-preview__heading {
    top: 20px;
    left: 20px;
    right: 20px;
  }

  .login-map-preview__heading h2 {
    margin-top: 10px;
  }

  .login-map-preview__summary {
    top: auto;
    right: 18px;
    bottom: 54px;
    padding-top: 0;
  }

  .login-map-preview__footer {
    right: 18px;
    bottom: 16px;
    left: 18px;
    font-size: 0.7rem;
  }

  .login-map-preview__privacy {
    display: none;
  }

  .login-map-preview__attribution {
    margin-left: auto;
    font-size: 0.58rem;
  }
}
</style>
