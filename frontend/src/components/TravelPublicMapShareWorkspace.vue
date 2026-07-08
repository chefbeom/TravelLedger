<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import {
  fetchTravelPublicMapShare,
  fetchTravelPublicMapSharePhotoCluster,
} from '../lib/api'
import { formatDate, formatTime, safeNumber } from '../lib/uiFormat'
import TravelMyMapClusterPanel from './TravelMyMapClusterPanel.vue'

const CLUSTER_PHOTO_PAGE_SIZE = 12

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
const displayMode = ref('cluster')
const mapFitRequestKey = ref(0)

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
const shareTitle = computed(() => share.value?.title || '공유 여행 지도')

function setError(message = '') {
  errorMessage.value = message
}

function setDetailError(message = '') {
  detailErrorMessage.value = message
}

async function loadShare({ autoSelect = true } = {}) {
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

async function loadClusterDetail(clusterId, preferredPhotoId = null) {
  if (!clusterId) {
    selectedClusterDetail.value = null
    selectedPhotoId.value = null
    return
  }

  isDetailLoading.value = true
  setDetailError('')
  try {
    const detail = await fetchTravelPublicMapSharePhotoCluster(props.token, clusterId, {
      page: 0,
      size: CLUSTER_PHOTO_PAGE_SIZE,
      focusMediaId: preferredPhotoId,
    })
    selectedClusterDetail.value = detail
    selectedPhotoId.value = preferredPhotoId ?? detail?.representativeMediaId ?? detail?.photos?.[0]?.id ?? null
  } catch (error) {
    selectedClusterDetail.value = null
    selectedPhotoId.value = null
    setDetailError(error.message || '사진 상세 정보를 불러오지 못했습니다.')
  } finally {
    isDetailLoading.value = false
  }
}

async function handleSelectCluster(cluster) {
  if (!cluster?.id) {
    clearSelection()
    return
  }
  selectedClusterSummary.value = cluster
  selectedMarkerId.value = null
  await loadClusterDetail(cluster.id, cluster.representativeMediaId)
}

async function handleSelectPhotoPin(pin) {
  if (!pin?.clusterId) {
    return
  }
  const cluster = photoClusters.value.find((candidate) => String(candidate.id) === String(pin.clusterId))
  selectedClusterSummary.value = cluster ?? selectedClusterSummary.value
  selectedMarkerId.value = null
  await loadClusterDetail(pin.clusterId, pin.mediaId)
}

function handleSelectMarker(marker) {
  selectedMarkerId.value = marker?.id ?? null
  selectedClusterSummary.value = null
  selectedClusterDetail.value = null
  selectedPhotoId.value = null
}

function clearSelection() {
  selectedClusterSummary.value = null
  selectedClusterDetail.value = null
  selectedPhotoId.value = null
  selectedMarkerId.value = null
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
        @clear-selection="clearSelection"
      />
    </section>

    <section v-if="selectedClusterSummary || selectedClusterDetail || selectedMarkerId" class="panel public-map-share-detail">
      <div class="panel__header">
        <div>
          <span class="panel__eyebrow">DETAIL</span>
          <h2>{{ selectedClusterSummary?.title || selectedPhoto?.title || '선택한 장소' }}</h2>
          <p>
            {{ selectedClusterSummary?.planName || selectedPhoto?.planName || '' }}
            <template v-if="selectedClusterSummary?.memoryDate || selectedPhoto?.expenseDate">
              · {{ formatDateRange(selectedClusterSummary?.memoryDate || selectedPhoto?.expenseDate, selectedClusterSummary?.memoryDate || selectedPhoto?.expenseDate) }}
              <span v-if="selectedClusterSummary?.memoryTime || selectedPhoto?.expenseTime">{{ formatTime(selectedClusterSummary?.memoryTime || selectedPhoto?.expenseTime) }}</span>
            </template>
          </p>
        </div>
        <button class="button button--ghost" type="button" @click="clearSelection">선택 해제</button>
      </div>

      <p v-if="detailErrorMessage" class="panel__empty">{{ detailErrorMessage }}</p>
      <p v-else-if="isDetailLoading" class="panel__empty">사진 정보를 불러오는 중입니다...</p>
      <div v-else class="public-map-share-detail__grid">
        <figure v-if="selectedRepresentativePhoto?.contentUrl" class="public-map-share-detail__cover">
          <img :src="selectedRepresentativePhoto.contentUrl" :alt="selectedRepresentativePhoto.title || selectedRepresentativePhoto.originalFileName || '여행 사진'" />
          <figcaption>{{ selectedRepresentativePhoto.placeName || selectedRepresentativePhoto.region || selectedRepresentativePhoto.country || '위치 정보 없음' }}</figcaption>
        </figure>
        <div v-if="selectedPhotos.length" class="public-map-share-detail__photos">
          <button
            v-for="photo in selectedPhotos"
            :key="photo.id"
            class="public-map-share-photo"
            :class="{ 'is-active': String(photo.id) === String(selectedPhotoId) }"
            type="button"
            @click="selectedPhotoId = photo.id"
          >
            <img :src="photo.contentUrl" :alt="photo.title || photo.originalFileName || '여행 사진'" />
            <span>{{ photo.placeName || photo.title || photo.originalFileName }}</span>
          </button>
        </div>
        <p v-else class="panel__empty">표시할 사진 상세가 없습니다.</p>
      </div>
    </section>
  </main>
</template>
