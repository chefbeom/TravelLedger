<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  fetchTravelPublicMapShare,
  fetchTravelPublicMapSharePhotoCluster,
} from '../lib/api'
import { formatDate, formatTime, safeNumber } from '../lib/uiFormat'
import {
  buildTravelJourneyDays,
  buildTravelRoutePlaybackPath,
  getTravelRoutePosition,
  matchesTravelJourneyDay,
  sortTravelJourneyPhotos,
} from '../lib/travelJourney'
import TravelMyMapClusterPanel from './TravelMyMapClusterPanel.vue'
import TravelPublicMapPhotoDetailModal from './TravelPublicMapPhotoDetailModal.vue'
import TravelJourneyPlaybackControls from './TravelJourneyPlaybackControls.vue'

const CLUSTER_PHOTO_PAGE_SIZE = 36

const props = defineProps({
  token: {
    type: String,
    default: '',
  },
})

const isLoading = ref(false)
const isDetailLoading = ref(false)
const errorMessage = ref('')
const detailErrorMessage = ref('')
const share = ref(null)
const selectedClusterSummary = ref(null)
const selectedClusterDetail = ref(null)
const selectedPhotoId = ref(null)
const selectedMarkerId = ref(null)
const selectedClusterPage = ref(0)
const photoModalOpen = ref(false)
const displayMode = ref('cluster')
const mapFitRequestKey = ref(0)
const isMapFullscreen = ref(false)
const selectedJourneyDayKey = ref('')
const journeyPhotoIndex = ref(-1)
const journeySpeedSeconds = ref(4)
const isJourneyPlaying = ref(false)
const selectedJourneyRouteId = ref('')
const journeyRouteSpeedKmh = ref(300)
const journeyRouteDistanceMeters = ref(0)
const isJourneyRoutePlaying = ref(false)
const mapFocusTarget = ref(null)
let clusterDetailRequestSequence = 0
let journeyPlaybackTimer = null
let journeyPlaybackSequence = 0
let journeyRoutePlaybackTimer = null
let journeyRoutePlaybackSequence = 0
let journeyRoutePlaybackStartedAt = 0
let journeyRoutePlaybackStartDistance = 0
let mapFocusSequence = 0

const overview = computed(() => share.value?.overview ?? null)
const markers = computed(() => overview.value?.markers ?? [])
const photoClusters = computed(() => overview.value?.photoClusters ?? [])
const photoPins = computed(() => overview.value?.photoPins ?? [])
const routes = computed(() => overview.value?.routes ?? [])
const summary = computed(() => ({
  planCount: overview.value?.includedPlanCount ?? 0,
  markerCount: markers.value.length,
  photoCount: photoPins.value.length,
  clusterCount: photoClusters.value.length,
  routeCount: routes.value.length,
  totalDistanceKm: routes.value.reduce((total, route) => total + safeNumber(route?.distanceKm), 0),
}))
const selectedPhotos = computed(() => selectedClusterDetail.value?.photos ?? [])
const selectedRepresentativePhoto = computed(() => selectedClusterDetail.value?.representativePhoto ?? selectedPhotos.value[0] ?? null)
const selectedPhoto = computed(() => (
  selectedPhotos.value.find((photo) => String(photo.id) === String(selectedPhotoId.value))
  ?? selectedRepresentativePhoto.value
))
const allDisplayedPhotoSequence = computed(() => {
  const seenMediaIds = new Set()
  return sortTravelJourneyPhotos(photoPins.value.filter((pin) => {
    const mediaId = String(pin?.mediaId ?? '').trim()
    const clusterId = String(pin?.clusterId ?? '').trim()
    if (!mediaId || !clusterId || !pin?.photoUrl || seenMediaIds.has(mediaId)) {
      return false
    }
    seenMediaIds.add(mediaId)
    return true
  }))
})
const journeyDays = computed(() => buildTravelJourneyDays(
  allDisplayedPhotoSequence.value,
  [...markers.value, ...routes.value],
))
const selectedJourneyDay = computed(() => journeyDays.value.find((day) => day.key === selectedJourneyDayKey.value) ?? null)
const journeyPhotoPins = computed(() => selectedJourneyDay.value
  ? sortTravelJourneyPhotos(allDisplayedPhotoSequence.value.filter((pin) => matchesTravelJourneyDay(pin, selectedJourneyDay.value)))
  : [])
const journeyRouteOptions = computed(() => selectedJourneyDay.value
  ? mapRoutes.value.map((route, index) => {
      const path = buildTravelRoutePlaybackPath(route)
      return {
        id: String(route?.id ?? `${selectedJourneyDay.value.key}-route-${index}`),
        label: `${route?.title || '여행 경로'} · ${path.totalDistanceKm.toFixed(1)} km`,
        path,
      }
    }).filter((route) => route.path.isPlayable)
  : [])
const selectedJourneyRoute = computed(() => journeyRouteOptions.value.find((route) => route.id === selectedJourneyRouteId.value)
  ?? journeyRouteOptions.value[0]
  ?? null)
const activeJourneyPhotoId = computed(() => journeyPhotoPins.value[journeyPhotoIndex.value]?.mediaId ?? null)
const journeyRouteDistanceKm = computed(() => selectedJourneyRoute.value?.path.totalDistanceKm ?? 0)
const journeyRouteHasCompleted = computed(() => Boolean(
  selectedJourneyRoute.value
  && journeyRouteDistanceMeters.value >= selectedJourneyRoute.value.path.totalDistanceMeters,
))
const displayedPhotoSequence = computed(() => selectedJourneyDay.value ? journeyPhotoPins.value : allDisplayedPhotoSequence.value)
const mapPhotoClusters = computed(() => selectedJourneyDay.value ? [] : photoClusters.value)
const mapPhotoPins = computed(() => selectedJourneyDay.value ? journeyPhotoPins.value : photoPins.value)
const mapMarkers = computed(() => selectedJourneyDay.value
  ? markers.value.filter((marker) => matchesTravelJourneyDay(marker, selectedJourneyDay.value))
  : markers.value)
const mapRoutes = computed(() => selectedJourneyDay.value
  ? routes.value.filter((route) => matchesTravelJourneyDay(route, selectedJourneyDay.value))
  : routes.value)
const mapDisplayMode = computed(() => selectedJourneyDay.value ? 'pin' : displayMode.value)
const modalPhotos = computed(() => selectedJourneyDay.value
  ? selectedPhotos.value.filter((photo) => matchesTravelJourneyDay(photo, selectedJourneyDay.value))
  : selectedPhotos.value)
const journeyPlaybackState = computed(() => (
  selectedJourneyDay.value && isMapFullscreen.value
    ? {
        isPlaying: isJourneyPlaying.value,
        photoIndex: journeyPhotoIndex.value,
        photoCount: journeyPhotoPins.value.length,
        speedSeconds: journeySpeedSeconds.value,
        isBusy: isDetailLoading.value,
        hasCompleted: !isJourneyPlaying.value && journeyPhotoIndex.value === journeyPhotoPins.value.length - 1,
      }
    : null
))
const canNavigatePhotos = computed(() => displayedPhotoSequence.value.length > 1)
const photoModalTitle = computed(() => selectedPhoto.value?.placeName || selectedPhoto.value?.title || selectedPhoto.value?.originalFileName || selectedClusterSummary.value?.title || '여행 사진')
const photoModalMeta = computed(() => {
  const photo = selectedPhoto.value
  const cluster = selectedClusterSummary.value
  const date = formatDate(photo?.expenseDate || photo?.memoryDate || cluster?.memoryDate)
  const time = formatTime(photo?.expenseTime || photo?.memoryTime || cluster?.memoryTime)
  return [
    photo?.planName || cluster?.planName,
    [date, time].filter(Boolean).join(' '),
    photo?.placeName || photo?.region || photo?.country,
  ].filter(Boolean).join(' · ')
})
const shareTitle = computed(() => share.value?.title || '공유 여행 지도')

function setError(message = '') {
  errorMessage.value = message
}

function setDetailError(message = '') {
  detailErrorMessage.value = message
}

async function loadShare({ autoSelect = false } = {}) {
  pauseJourneyPlayback()
  pauseJourneyRoutePlayback({ preserveDistance: false })
  selectedJourneyDayKey.value = ''
  selectedJourneyRouteId.value = ''
  journeyRouteDistanceMeters.value = 0
  journeyPhotoIndex.value = -1
  const token = String(props.token || '').trim()
  if (!token) {
    share.value = null
    setError('공유 링크가 올바르지 않습니다.')
    return
  }

  isLoading.value = true
  setError('')
  setDetailError('')

  try {
    const response = await fetchTravelPublicMapShare(token)
    share.value = response
    mapFitRequestKey.value += 1
    const clusters = response?.overview?.photoClusters ?? []
    if (autoSelect && clusters.length) {
      await handleSelectCluster(clusters[0])
    } else {
      clearSelection()
    }
  } catch (error) {
    share.value = null
    clearSelection()
    setError(error.message || '공유 지도를 불러오지 못했습니다.')
  } finally {
    isLoading.value = false
  }
}

async function loadClusterDetail(clusterId, preferredPhotoId = null, page = 0, preferredIndex = null) {
  const requestSequence = ++clusterDetailRequestSequence
  if (!clusterId) {
    selectedClusterDetail.value = null
    selectedPhotoId.value = null
    selectedClusterPage.value = 0
    return
  }

  isDetailLoading.value = true
  setDetailError('')
  try {
    const detail = await fetchTravelPublicMapSharePhotoCluster(props.token, clusterId, {
      page,
      size: CLUSTER_PHOTO_PAGE_SIZE,
      focusMediaId: page === 0 ? preferredPhotoId : null,
    })
    if (requestSequence !== clusterDetailRequestSequence) {
      return
    }
    const pagePhotos = detail?.photos ?? []
    selectedClusterDetail.value = detail
    selectedClusterPage.value = detail?.page ?? page
    selectedPhotoId.value = preferredIndex != null && pagePhotos[preferredIndex]
      ? pagePhotos[preferredIndex].id
      : preferredPhotoId ?? detail?.representativeMediaId ?? pagePhotos[0]?.id ?? null
  } catch (error) {
    if (requestSequence !== clusterDetailRequestSequence) {
      return
    }
    selectedClusterDetail.value = null
    selectedPhotoId.value = null
    selectedClusterPage.value = 0
    setDetailError(error.message || '사진 상세 정보를 불러오지 못했습니다.')
  } finally {
    if (requestSequence === clusterDetailRequestSequence) {
      isDetailLoading.value = false
    }
  }
}
async function handleSelectCluster(cluster) {
  if (!cluster?.id) {
    clearSelection()
    return
  }
  pauseJourneyPlayback()
  pauseJourneyRoutePlayback()
  selectedClusterSummary.value = cluster
  selectedMarkerId.value = null
  photoModalOpen.value = true
  await loadClusterDetail(cluster.id, cluster.representativeMediaId)
}

async function handleSelectPhotoPin(pin, options = {}) {
  if (!pin?.clusterId) {
    return null
  }
  if (!options.fromJourneyPlayback) {
    pauseJourneyPlayback()
    pauseJourneyRoutePlayback()
  }
  const journeyIndex = journeyPhotoPins.value.findIndex((item) => String(item.mediaId) === String(pin.mediaId))
  if (journeyIndex >= 0) {
    journeyPhotoIndex.value = journeyIndex
  }
  focusMapAtJourneyPhoto(pin)
  const cluster = photoClusters.value.find((candidate) => String(candidate.id) === String(pin.clusterId))
  selectedClusterSummary.value = cluster ?? selectedClusterSummary.value
  selectedMarkerId.value = null
  photoModalOpen.value = true
  await loadClusterDetail(pin.clusterId, pin.mediaId)
  return selectedPhoto.value
}

function handleSelectMarker(marker) {
  pauseJourneyPlayback()
  pauseJourneyRoutePlayback()
  selectedMarkerId.value = marker?.id ?? null
  selectedClusterSummary.value = null
  selectedClusterDetail.value = null
  selectedPhotoId.value = null
  selectedClusterPage.value = 0
  photoModalOpen.value = false
}

function clearSelection() {
  pauseJourneyPlayback()
  pauseJourneyRoutePlayback()
  clusterDetailRequestSequence += 1
  isDetailLoading.value = false
  selectedClusterSummary.value = null
  selectedClusterDetail.value = null
  selectedPhotoId.value = null
  selectedMarkerId.value = null
  selectedClusterPage.value = 0
  setDetailError('')
  photoModalOpen.value = false
}

function closePhotoModal() {
  pauseJourneyPlayback()
  pauseJourneyRoutePlayback()
  photoModalOpen.value = false
}

function handleMapFullscreenChange(nextValue) {
  isMapFullscreen.value = Boolean(nextValue)
  if (!isMapFullscreen.value) {
    pauseJourneyPlayback()
    pauseJourneyRoutePlayback()
  }
}

function pauseJourneyPlayback() {
  if (journeyPlaybackTimer !== null) {
    clearTimeout(journeyPlaybackTimer)
    journeyPlaybackTimer = null
  }
  journeyPlaybackSequence += 1
  isJourneyPlaying.value = false
}

function focusMapAtCoordinate(latitudeValue, longitudeValue, { keepZoom = false, duration = 0.8 } = {}) {
  const latitude = Number(latitudeValue)
  const longitude = Number(longitudeValue)
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) {
    return
  }

  mapFocusSequence += 1
  mapFocusTarget.value = { requestId: mapFocusSequence, latitude, longitude, keepZoom, duration }
}

function focusMapAtJourneyPhoto(photo, options = {}) {
  const rawLatitude = photo?.latitude ?? photo?.gpsLatitude
  const rawLongitude = photo?.longitude ?? photo?.gpsLongitude
  if (rawLatitude == null || rawLongitude == null) {
    return
  }

  focusMapAtCoordinate(rawLatitude, rawLongitude, options)
}

function scheduleNextJourneyPhoto(sequence) {
  if (!isJourneyPlaying.value || sequence !== journeyPlaybackSequence) {
    return
  }
  if (journeyPlaybackTimer !== null) {
    clearTimeout(journeyPlaybackTimer)
  }
  journeyPlaybackTimer = setTimeout(async () => {
    journeyPlaybackTimer = null
    if (!isJourneyPlaying.value || sequence !== journeyPlaybackSequence) {
      return
    }
    const nextIndex = journeyPhotoIndex.value + 1
    if (nextIndex >= journeyPhotoPins.value.length) {
      isJourneyPlaying.value = false
      journeyPlaybackSequence += 1
      return
    }

    const pin = journeyPhotoPins.value[nextIndex]
    journeyPhotoIndex.value = nextIndex
    focusMapAtJourneyPhoto(pin, { keepZoom: true, duration: 0.65 })
    scheduleNextJourneyPhoto(sequence)
  }, journeySpeedSeconds.value * 1000)
}

async function toggleJourneyPlayback() {
  if (isJourneyPlaying.value) {
    pauseJourneyPlayback()
    return
  }
  if (!selectedJourneyDay.value || !journeyPhotoPins.value.length) {
    return
  }

  const canResume = journeyPhotoIndex.value >= 0
    && journeyPhotoIndex.value < journeyPhotoPins.value.length - 1
  pauseJourneyRoutePlayback()
  clearJourneyPlaybackDetails()
  if (!canResume) {
    journeyPhotoIndex.value = 0
  }
  isJourneyPlaying.value = true
  journeyPlaybackSequence += 1
  const sequence = journeyPlaybackSequence
  if (canResume) {
    focusMapAtJourneyPhoto(journeyPhotoPins.value[journeyPhotoIndex.value], { keepZoom: true, duration: 0.65 })
    scheduleNextJourneyPhoto(sequence)
    return
  }

  const firstPin = journeyPhotoPins.value[0]
  focusMapAtJourneyPhoto(firstPin, { keepZoom: true, duration: 0.65 })
  scheduleNextJourneyPhoto(sequence)
}

function setJourneyPlaybackSpeed(value) {
  const nextSpeed = Number(value)
  if (![2, 4, 6, 8, 10].includes(nextSpeed)) {
    return
  }
  journeySpeedSeconds.value = nextSpeed
  if (isJourneyPlaying.value && !isDetailLoading.value) {
    scheduleNextJourneyPhoto(journeyPlaybackSequence)
  }
}

function handleJourneyDayChange(dayKey) {
  const nextKey = String(dayKey ?? '')
  if (nextKey === selectedJourneyDayKey.value) {
    return
  }
  pauseJourneyPlayback()
  pauseJourneyRoutePlayback({ preserveDistance: false })
  selectedJourneyDayKey.value = nextKey
  journeyPhotoIndex.value = -1
  selectedJourneyRouteId.value = ''
  journeyRouteDistanceMeters.value = 0
  clearSelection()
  if (nextKey) {
    displayMode.value = 'pin'
  }
  mapFitRequestKey.value += 1
}

function pauseJourneyRoutePlayback({ preserveDistance = true } = {}) {
  if (isJourneyRoutePlaying.value && preserveDistance && selectedJourneyRoute.value) {
    const elapsedSeconds = Math.max(0, (performance.now() - journeyRoutePlaybackStartedAt) / 1000)
    const distance = journeyRoutePlaybackStartDistance + (journeyRouteSpeedKmh.value * (1000 / 3600) * elapsedSeconds)
    journeyRouteDistanceMeters.value = Math.min(distance, selectedJourneyRoute.value.path.totalDistanceMeters)
  }
  if (journeyRoutePlaybackTimer !== null) {
    clearTimeout(journeyRoutePlaybackTimer)
    journeyRoutePlaybackTimer = null
  }
  journeyRoutePlaybackSequence += 1
  isJourneyRoutePlaying.value = false
}

function clearJourneyPlaybackDetails() {
  clusterDetailRequestSequence += 1
  isDetailLoading.value = false
  selectedClusterSummary.value = null
  selectedClusterDetail.value = null
  selectedPhotoId.value = null
  selectedMarkerId.value = null
  selectedClusterPage.value = 0
  photoModalOpen.value = false
}

function scheduleJourneyRouteFrame(sequence) {
  const route = selectedJourneyRoute.value
  if (!isJourneyRoutePlaying.value || sequence !== journeyRoutePlaybackSequence || !route?.path.isPlayable) {
    return
  }

  const elapsedSeconds = Math.max(0, (performance.now() - journeyRoutePlaybackStartedAt) / 1000)
  const distance = journeyRoutePlaybackStartDistance + (journeyRouteSpeedKmh.value * (1000 / 3600) * elapsedSeconds)
  const nextDistance = Math.min(distance, route.path.totalDistanceMeters)
  journeyRouteDistanceMeters.value = nextDistance
  const position = getTravelRoutePosition(route.path, nextDistance)
  if (position) {
    focusMapAtCoordinate(position.latitude, position.longitude, { keepZoom: true, duration: 0.18 })
  }

  if (nextDistance >= route.path.totalDistanceMeters) {
    journeyRoutePlaybackTimer = null
    isJourneyRoutePlaying.value = false
    journeyRoutePlaybackSequence += 1
    return
  }

  journeyRoutePlaybackTimer = setTimeout(() => {
    journeyRoutePlaybackTimer = null
    scheduleJourneyRouteFrame(sequence)
  }, 120)
}

function startJourneyRoutePlayback() {
  const route = selectedJourneyRoute.value
  if (!route?.path.isPlayable || journeyRouteSpeedKmh.value <= 0) {
    return
  }
  pauseJourneyPlayback()
  clearJourneyPlaybackDetails()
  if (journeyRouteDistanceMeters.value >= route.path.totalDistanceMeters) {
    journeyRouteDistanceMeters.value = 0
  }

  journeyRoutePlaybackStartDistance = journeyRouteDistanceMeters.value
  journeyRoutePlaybackStartedAt = performance.now()
  journeyRoutePlaybackSequence += 1
  isJourneyRoutePlaying.value = true
  scheduleJourneyRouteFrame(journeyRoutePlaybackSequence)
}

function toggleJourneyRoutePlayback() {
  if (isJourneyRoutePlaying.value) {
    pauseJourneyRoutePlayback()
    return
  }
  startJourneyRoutePlayback()
}

function handleJourneyRouteChange(routeId) {
  pauseJourneyRoutePlayback({ preserveDistance: false })
  selectedJourneyRouteId.value = String(routeId ?? '')
  journeyRouteDistanceMeters.value = 0
}

function setJourneyRouteSpeed(value) {
  const nextSpeed = Number(value)
  if (![0, 60, 300, 1500, 9000].includes(nextSpeed)) {
    return
  }
  const shouldResume = isJourneyRoutePlaying.value && nextSpeed > 0
  pauseJourneyRoutePlayback()
  journeyRouteSpeedKmh.value = nextSpeed
  if (shouldResume) {
    startJourneyRoutePlayback()
  }
}

function setMapDisplayMode(mode) {
  if (mode === 'cluster' && selectedJourneyDayKey.value) {
    handleJourneyDayChange('')
  }
  displayMode.value = mode === 'pin' ? 'pin' : 'cluster'
}

function handleSelectDetailPhoto(photo) {
  if (!photo?.id) {
    return
  }
  pauseJourneyPlayback()
  pauseJourneyRoutePlayback()
  selectedPhotoId.value = photo.id
  const pin = allDisplayedPhotoSequence.value.find((item) => String(item.mediaId) === String(photo.id))
  const journeyIndex = journeyPhotoPins.value.findIndex((item) => String(item.mediaId) === String(photo.id))
  if (journeyIndex >= 0) {
    journeyPhotoIndex.value = journeyIndex
  }
  focusMapAtJourneyPhoto(pin ?? photo)
}

async function selectAdjacentPhoto(offset) {
  if (isDetailLoading.value) {
    return
  }

  const sequence = displayedPhotoSequence.value
  if (sequence.length <= 1) {
    return
  }

  const currentMediaId = String(selectedPhotoId.value ?? selectedClusterSummary.value?.representativeMediaId ?? '')
  const currentIndex = sequence.findIndex((pin) => String(pin.mediaId) === currentMediaId)
  const normalizedCurrentIndex = currentIndex >= 0 ? currentIndex : 0
  const targetIndex = (normalizedCurrentIndex + offset + sequence.length) % sequence.length
  const targetPin = sequence[targetIndex]
  if (!targetPin) {
    return
  }
  pauseJourneyPlayback()
  pauseJourneyRoutePlayback()
  const journeyIndex = journeyPhotoPins.value.findIndex((pin) => String(pin.mediaId) === String(targetPin.mediaId))
  if (journeyIndex >= 0) {
    journeyPhotoIndex.value = journeyIndex
  }
  focusMapAtJourneyPhoto(targetPin)

  const targetPhoto = selectedPhotos.value.find((photo) => String(photo.id) === String(targetPin.mediaId))
  const currentClusterId = String(selectedClusterDetail.value?.id ?? selectedClusterSummary.value?.id ?? '')
  if (String(targetPin.clusterId) === currentClusterId && targetPhoto) {
    selectedPhotoId.value = targetPhoto.id
    return
  }

  const targetCluster = photoClusters.value.find((cluster) => String(cluster.id) === String(targetPin.clusterId))
  if (!targetCluster) {
    return
  }

  selectedClusterSummary.value = targetCluster
  selectedMarkerId.value = null
  photoModalOpen.value = true
  await loadClusterDetail(targetPin.clusterId, targetPin.mediaId)
}
function formatDateRange(start, end) {
  const startText = formatDate(start)
  const endText = formatDate(end)
  if (!startText && !endText) {
    return '-'
  }
  return startText === endText ? startText : `${startText} ~ ${endText}`
}

watch(() => props.token, () => {
  loadShare()
})

watch(journeyDays, (days) => {
  if (selectedJourneyDayKey.value && !days.some((day) => day.key === selectedJourneyDayKey.value)) {
    handleJourneyDayChange('')
  }
})

watch(journeyRouteOptions, (options) => {
  if (!options.length) {
    pauseJourneyRoutePlayback({ preserveDistance: false })
    selectedJourneyRouteId.value = ''
    journeyRouteDistanceMeters.value = 0
    return
  }
  if (!options.some((route) => route.id === selectedJourneyRouteId.value)) {
    pauseJourneyRoutePlayback({ preserveDistance: false })
    selectedJourneyRouteId.value = options[0].id
    journeyRouteDistanceMeters.value = 0
  }
}, { immediate: true })

onMounted(() => {
  loadShare()
})

onBeforeUnmount(() => {
  pauseJourneyPlayback()
  pauseJourneyRoutePlayback()
  clusterDetailRequestSequence += 1
})
</script>

<template>
  <main class="public-map-share-page">
    <section class="panel public-map-share-hero">
      <div>
        <span class="panel__eyebrow">TRAVEL SHARE</span>
        <h1>{{ shareTitle }}</h1>
        <p v-if="share">공유자 {{ share.ownerDisplayName || share.ownerLoginId || '-' }} · {{ formatDate(share.createdAt) }}</p>
        <p v-else>공유된 여행 지도를 불러옵니다.</p>
      </div>
      <div class="public-map-share-hero__stats">
        <span>{{ summary.planCount }}개 여행</span>
        <span>{{ summary.photoCount }}장 사진</span>
        <span>{{ summary.markerCount }}개 장소</span>
        <span>{{ summary.routeCount }}개 경로</span>
      </div>
    </section>

    <section class="panel panel--map-fill public-map-share-map">
      <div class="panel__header">
        <div>
          <h2>공유 지도</h2>
          <p>읽기 전용 공개 지도입니다.</p>
        </div>
        <div class="travel-map-mode-switch">
          <button
            class="travel-map__toolbar-button"
            :class="{ 'is-active': mapDisplayMode === 'cluster' }"
            type="button"
            @click="setMapDisplayMode('cluster')"
          >
            클러스터
          </button>
          <button
            class="travel-map__toolbar-button"
            :class="{ 'is-active': mapDisplayMode === 'pin' }"
            type="button"
            @click="setMapDisplayMode('pin')"
          >
            핀
          </button>
        </div>
      </div>

      <p v-if="errorMessage" class="panel__empty">{{ errorMessage }}</p>
      <p v-else-if="isLoading" class="panel__empty">공유 지도를 불러오는 중입니다...</p>
      <TravelMyMapClusterPanel
        v-else
        :photo-clusters="mapPhotoClusters"
        :photo-pins="mapPhotoPins"
        :markers="mapMarkers"
        :routes="mapRoutes"
        :active="true"
        :display-mode="mapDisplayMode"
        :tile-provider="'publicLight'"
        :selected-cluster-id="selectedClusterSummary?.id ?? null"
        :selected-photo-id="selectedPhotoId ?? null"
        :journey-photo-id="activeJourneyPhotoId"
        :selected-marker-id="selectedMarkerId ?? null"
        :fit-request-key="mapFitRequestKey"
        :focus-target="mapFocusTarget"
        @select-cluster="handleSelectCluster"
        @select-marker="handleSelectMarker"
        @select-photo-pin="handleSelectPhotoPin"
        @preview-cluster="handleSelectCluster"
        @fullscreen-change="handleMapFullscreenChange"
        @clear-selection="clearSelection"
      >
        <template #fullscreen-controls="{ isFullscreen }">
          <TravelJourneyPlaybackControls
            v-if="isFullscreen"
            :days="journeyDays"
            :selected-day-key="selectedJourneyDayKey"
            :is-playing="isJourneyPlaying"
            :photo-index="journeyPhotoIndex"
            :photo-count="journeyPhotoPins.length"
            :speed-seconds="journeySpeedSeconds"
            :is-busy="isDetailLoading"
            :has-completed="journeyPhotoIndex === journeyPhotoPins.length - 1 && journeyPhotoPins.length > 0"
            :routes="journeyRouteOptions.map(({ id, label }) => ({ id, label }))"
            :selected-route-id="selectedJourneyRoute?.id ?? ''"
            :route-distance-km="journeyRouteDistanceKm"
            :route-speed-kmh="journeyRouteSpeedKmh"
            :is-route-playing="isJourneyRoutePlaying"
            :route-has-completed="journeyRouteHasCompleted"
            @select-day="handleJourneyDayChange"
            @toggle="toggleJourneyPlayback"
            @speed-change="setJourneyPlaybackSpeed"
            @select-route="handleJourneyRouteChange"
            @toggle-route="toggleJourneyRoutePlayback"
            @route-speed-change="setJourneyRouteSpeed"
          />
        </template>
        <template #fullscreen-dialog="{ isFullscreen }">
          <TravelPublicMapPhotoDetailModal
            v-if="isFullscreen && photoModalOpen"
            :title="photoModalTitle"
            :meta="photoModalMeta"
            :photo="selectedPhoto"
            :photos="modalPhotos"
            :current-photo-id="selectedPhotoId"
            :is-loading="isDetailLoading"
            :error-message="detailErrorMessage"
            :can-navigate="canNavigatePhotos"
            :playback-state="journeyPlaybackState"
            @close="closePhotoModal"
            @previous-photo="selectAdjacentPhoto(-1)"
            @next-photo="selectAdjacentPhoto(1)"
            @select-photo="handleSelectDetailPhoto"
            @toggle-journey-playback="toggleJourneyPlayback"
            @journey-speed-change="setJourneyPlaybackSpeed"
          />
        </template>
      </TravelMyMapClusterPanel>
    </section>


    <TravelPublicMapPhotoDetailModal
      v-if="photoModalOpen && !isMapFullscreen"
      :title="photoModalTitle"
      :meta="photoModalMeta"
      :photo="selectedPhoto"
      :photos="modalPhotos"
      :current-photo-id="selectedPhotoId"
      :is-loading="isDetailLoading"
      :error-message="detailErrorMessage"
      :can-navigate="canNavigatePhotos"
      :playback-state="journeyPlaybackState"
      @close="closePhotoModal"
      @previous-photo="selectAdjacentPhoto(-1)"
      @next-photo="selectAdjacentPhoto(1)"
      @select-photo="handleSelectDetailPhoto"
      @toggle-journey-playback="toggleJourneyPlayback"
      @journey-speed-change="setJourneyPlaybackSpeed"
    />
  </main>
</template>
