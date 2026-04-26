<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'

const props = defineProps({
  latitude: {
    type: [Number, String],
    default: null,
  },
  longitude: {
    type: [Number, String],
    default: null,
  },
  title: {
    type: String,
    default: '사진 위치',
  },
})

const mapElement = ref(null)
let mapInstance = null
let locationLayer = null

const point = computed(() => {
  const latitude = Number(props.latitude)
  const longitude = Number(props.longitude)
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) {
    return null
  }
  return [latitude, longitude]
})

function destroyMap() {
  if (mapInstance) {
    mapInstance.remove()
    mapInstance = null
    locationLayer = null
  }
}

async function renderMap() {
  if (!point.value || !mapElement.value) {
    destroyMap()
    return
  }

  await nextTick()

  if (!mapInstance) {
    mapInstance = L.map(mapElement.value, {
      attributionControl: false,
      zoomControl: false,
      dragging: false,
      scrollWheelZoom: false,
      doubleClickZoom: false,
      boxZoom: false,
      keyboard: false,
      tap: false,
      preferCanvas: true,
    }).setView(point.value, 15)

    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      detectRetina: true,
      updateWhenIdle: true,
      keepBuffer: 2,
    }).addTo(mapInstance)
  } else {
    mapInstance.setView(point.value, 15, { animate: true, duration: 0.25 })
  }

  if (locationLayer) {
    locationLayer.remove()
  }

  locationLayer = L.circleMarker(point.value, {
    radius: 8,
    color: '#ffffff',
    weight: 3,
    fillColor: '#3182F6',
    fillOpacity: 0.95,
  }).addTo(mapInstance)

  locationLayer.bindTooltip(props.title, {
    permanent: false,
    direction: 'top',
  })

  requestAnimationFrame(() => mapInstance?.invalidateSize(false))
}

onMounted(renderMap)
onBeforeUnmount(destroyMap)

watch(
  () => [props.latitude, props.longitude, props.title],
  renderMap,
)
</script>

<template>
  <div v-if="point" ref="mapElement" class="travel-mini-location-map" />
  <div v-else class="travel-mini-location-map travel-mini-location-map--empty">
    위치 정보 없음
  </div>
</template>
