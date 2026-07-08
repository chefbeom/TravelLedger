<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import {
  fetchTravelPublicMapShare,
  fetchTravelPublicMapSharePhotoCluster,
} from '../lib/api'
import { formatDate, formatTime, safeNumber } from '../lib/uiFormat'
import TravelMyMapClusterPanel from './TravelMyMapClusterPanel.vue'

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
const selectedPhotoIndex = computed(() => selectedPhotos.value.findIndex((photo) => String(photo.id) === String(selectedPhotoId.value)))
const selectedTotalPhotoCount = computed(() => selectedClusterDetail.value?.totalPhotoCount ?? selectedClusterSummary.value?.photoCount ?? selectedPhotos.value.length)
const canNavigatePhotos = computed(() => selectedTotalPhotoCount.value > 1)
const photoModalTitle = computed(() => selectedClusterSummary.value?.title || selectedPhoto.value?.placeName || selectedPhoto.value?.title || selectedPhoto.value?.originalFileName || '여행 사진')
const photoModalMeta = computed(() => {
  const photo = selectedPhoto.value
  const cluster = selectedClusterSummary.value
  const date = formatDate(cluster?.memoryDate || photo?.expenseDate || photo?.memoryDate)
  const time = formatTime(cluster?.memoryTime || photo?.expenseTime || photo?.memoryTime)
  return [
    cluster?.planName || photo?.planName,
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
    const pagePhotos = detail?.photos ?? []
    selectedClusterDetail.value = detail
    selectedClusterPage.value = detail?.page ?? page
    selectedPhotoId.value = preferredIndex != null && pagePhotos[preferredIndex]
      ? pagePhotos[preferredIndex].id
      : preferredPhotoId ?? detail?.representativeMediaId ?? pagePhotos[0]?.id ?? null
  } catch (error) {
    selectedClusterDetail.value = null
    selectedPhotoId.value = null
    selectedClusterPage.value = 0
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

async function selectAdjacentPhoto(offset) {
  if (isDetailLoading.value) {
    return
  }
  const total = selectedTotalPhotoCount.value
  if (total <= 1) {
    return
  }

  const currentPage = selectedClusterDetail.value?.page ?? selectedClusterPage.value ?? 0
  const pageSize = selectedClusterDetail.value?.size ?? CLUSTER_PHOTO_PAGE_SIZE
  const currentIndex = selectedPhotoIndex.value >= 0 ? selectedPhotoIndex.value : 0
  const targetGlobalIndex = (currentPage * pageSize + currentIndex + offset + total) % total
  const targetPage = Math.floor(targetGlobalIndex / pageSize)
  const targetIndex = targetGlobalIndex % pageSize

  if (targetPage !== currentPage) {
    await loadClusterDetail(selectedClusterSummary.value?.id ?? selectedClusterDetail.value?.id, null, targetPage, targetIndex)
    return
  }

  selectedPhotoId.value = selectedPhotos.value[targetIndex]?.id ?? selectedPhotoId.value
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

    <div v-if="photoModalOpen" class="public-map-share-photo-modal" role="dialog" aria-modal="true" aria-labelledby="public-map-share-photo-modal-title" @click.self="closePhotoModal">
      <article class="public-map-share-photo-modal__panel">
        <header class="public-map-share-photo-modal__header">
          <div>
            <span class="panel__eyebrow">PHOTO DETAIL</span>
            <h2 id="public-map-share-photo-modal-title">{{ photoModalTitle }}</h2>
            <p v-if="photoModalMeta">{{ photoModalMeta }}</p>
          </div>
          <div class="public-map-share-photo-modal__header-actions">
            <button v-if="canNavigatePhotos" class="button button--secondary" type="button" @click="selectAdjacentPhoto(-1)">이전 사진</button>
            <button v-if="canNavigatePhotos" class="button button--secondary" type="button" @click="selectAdjacentPhoto(1)">다음 사진</button>
            <button class="button button--ghost" type="button" @click="closePhotoModal">닫기</button>
          </div>
        </header>

        <p v-if="detailErrorMessage" class="panel__empty">{{ detailErrorMessage }}</p>
        <p v-else-if="isDetailLoading" class="panel__empty">사진 정보를 불러오는 중입니다...</p>
        <div v-else-if="selectedPhoto?.contentUrl" class="public-map-share-photo-modal__body">
          <button
            v-if="canNavigatePhotos"
            class="public-map-share-photo-modal__nav public-map-share-photo-modal__nav--prev"
            type="button"
            aria-label="이전 사진"
            @click="selectAdjacentPhoto(-1)"
          >
            ‹
          </button>
          <figure class="public-map-share-photo-modal__figure">
            <img :src="selectedPhoto.contentUrl" :alt="selectedPhoto.title || selectedPhoto.originalFileName || '여행 사진'" />
            <figcaption>
              <strong>{{ selectedPhoto.placeName || selectedPhoto.title || selectedPhoto.originalFileName || '여행 사진' }}</strong>
              <span>{{ selectedPhoto.region || selectedPhoto.country || selectedPhoto.planName || '' }}</span>
            </figcaption>
          </figure>
          <button
            v-if="canNavigatePhotos"
            class="public-map-share-photo-modal__nav public-map-share-photo-modal__nav--next"
            type="button"
            aria-label="다음 사진"
            @click="selectAdjacentPhoto(1)"
          >
            ›
          </button>
        </div>
        <p v-else class="panel__empty">표시할 사진 상세가 없습니다.</p>

        <div v-if="!isDetailLoading && !detailErrorMessage && selectedPhotos.length > 1" class="public-map-share-photo-modal__thumbs" aria-label="클러스터 사진 목록">
          <button
            v-for="photo in selectedPhotos"
            :key="photo.id"
            class="public-map-share-photo-modal__thumb"
            :class="{ 'is-active': String(photo.id) === String(selectedPhotoId) }"
            type="button"
            @click="selectedPhotoId = photo.id"
          >
            <img :src="photo.contentUrl" :alt="photo.title || photo.originalFileName || '여행 사진'" />
          </button>
        </div>
      </article>
    </div>
  </main>
</template>
