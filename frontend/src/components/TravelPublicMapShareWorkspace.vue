<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import {
  fetchTravelPublicMapShare,
  fetchTravelPublicMapSharePhotoCluster,
} from '../lib/api'
import { formatDate, formatTime, safeNumber } from '../lib/uiFormat'
import TravelMyMapClusterPanel from './TravelMyMapClusterPanel.vue'
import TravelPublicMapPhotoDetailModal from './TravelPublicMapPhotoDetailModal.vue'

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
let clusterDetailRequestSequence = 0

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
const displayedPhotoSequence = computed(() => {
  const seenMediaIds = new Set()
  return photoPins.value.filter((pin) => {
    const mediaId = String(pin?.mediaId ?? '').trim()
    const clusterId = String(pin?.clusterId ?? '').trim()
    if (!mediaId || !clusterId || seenMediaIds.has(mediaId)) {
      return false
    }
    seenMediaIds.add(mediaId)
    return true
  })
})
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
  selectedClusterSummary.value = cluster
  selectedMarkerId.value = null
  photoModalOpen.value = true
  await loadClusterDetail(cluster.id, cluster.representativeMediaId)
}

async function handleSelectPhotoPin(pin) {
  if (!pin?.clusterId) {
    return
  }
  const cluster = photoClusters.value.find((candidate) => String(candidate.id) === String(pin.clusterId))
  selectedClusterSummary.value = cluster ?? selectedClusterSummary.value
  selectedMarkerId.value = null
  photoModalOpen.value = true
  await loadClusterDetail(pin.clusterId, pin.mediaId)
}

function handleSelectMarker(marker) {
  selectedMarkerId.value = marker?.id ?? null
  selectedClusterSummary.value = null
  selectedClusterDetail.value = null
  selectedPhotoId.value = null
  selectedClusterPage.value = 0
  photoModalOpen.value = false
}

function clearSelection() {
  clusterDetailRequestSequence += 1
  isDetailLoading.value = false
  selectedClusterSummary.value = null
  selectedClusterDetail.value = null
  selectedPhotoId.value = null
  selectedMarkerId.value = null
  selectedClusterPage.value = 0
  photoModalOpen.value = false
}

function closePhotoModal() {
  clearSelection()
}

function handleMapFullscreenChange(nextValue) {
  isMapFullscreen.value = Boolean(nextValue)
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

onMounted(() => {
  loadShare()
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
            :class="{ 'is-active': displayMode === 'cluster' }"
            type="button"
            @click="displayMode = 'cluster'"
          >
            클러스터
          </button>
          <button
            class="travel-map__toolbar-button"
            :class="{ 'is-active': displayMode === 'pin' }"
            type="button"
            @click="displayMode = 'pin'"
          >
            핀
          </button>
        </div>
      </div>

      <p v-if="errorMessage" class="panel__empty">{{ errorMessage }}</p>
      <p v-else-if="isLoading" class="panel__empty">공유 지도를 불러오는 중입니다...</p>
      <TravelMyMapClusterPanel
        v-else
        :photo-clusters="photoClusters"
        :photo-pins="photoPins"
        :markers="markers"
        :routes="routes"
        :active="true"
        :display-mode="displayMode"
        :tile-provider="'publicLight'"
        :selected-cluster-id="selectedClusterSummary?.id ?? null"
        :selected-photo-id="selectedPhotoId ?? null"
        :selected-marker-id="selectedMarkerId ?? null"
        :fit-request-key="mapFitRequestKey"
        @select-cluster="handleSelectCluster"
        @select-marker="handleSelectMarker"
        @select-photo-pin="handleSelectPhotoPin"
        @preview-cluster="handleSelectCluster"
        @fullscreen-change="handleMapFullscreenChange"
        @clear-selection="clearSelection"
      >
        <template #fullscreen-dialog="{ isFullscreen }">
          <TravelPublicMapPhotoDetailModal
            v-if="isFullscreen && photoModalOpen"
            :title="photoModalTitle"
            :meta="photoModalMeta"
            :photo="selectedPhoto"
            :photos="selectedPhotos"
            :current-photo-id="selectedPhotoId"
            :is-loading="isDetailLoading"
            :error-message="detailErrorMessage"
            :can-navigate="canNavigatePhotos"
            @close="closePhotoModal"
            @previous-photo="selectAdjacentPhoto(-1)"
            @next-photo="selectAdjacentPhoto(1)"
            @select-photo="selectedPhotoId = $event.id"
          />
        </template>
      </TravelMyMapClusterPanel>
    </section>


    <TravelPublicMapPhotoDetailModal
      v-if="photoModalOpen && !isMapFullscreen"
      :title="photoModalTitle"
      :meta="photoModalMeta"
      :photo="selectedPhoto"
      :photos="selectedPhotos"
      :current-photo-id="selectedPhotoId"
      :is-loading="isDetailLoading"
      :error-message="detailErrorMessage"
      :can-navigate="canNavigatePhotos"
      @close="closePhotoModal"
      @previous-photo="selectAdjacentPhoto(-1)"
      @next-photo="selectAdjacentPhoto(1)"
      @select-photo="selectedPhotoId = $event.id"
    />
  </main>
</template>
