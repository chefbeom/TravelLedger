<script setup>
import { computed, onMounted, ref } from 'vue'
import {
  fetchTravelMyMapOverview,
  fetchTravelMyMapPhotoCluster,
  updateTravelMyMapPhotoClusterRepresentative,
} from '../lib/api'
import { formatDate, safeNumber } from '../lib/uiFormat'
import TravelMyMapClusterPanel from './TravelMyMapClusterPanel.vue'
import TravelMyMapInspectorPanels from './TravelMyMapInspectorPanels.vue'
import TravelPhotoLightbox from './TravelPhotoLightbox.vue'

const CLUSTER_PHOTO_PAGE_SIZE = 12

const props = defineProps({
  active: {
    type: Boolean,
    default: true,
  },
})

const isLoading = ref(false)
const isDetailLoading = ref(false)
const isRepresentativeSaving = ref(false)
const isClusterPhotosLoadingMore = ref(false)
const isMapFullscreen = ref(false)
const errorMessage = ref('')
const overview = ref(null)
const selectedClusterSummary = ref(null)
const selectedClusterDetail = ref(null)
const selectedPhotoId = ref(null)
const selectedMarkerId = ref(null)
const lightboxPhoto = ref(null)
const representativeUpdatingId = ref(null)
const viewMode = ref('cluster')

function setError(message = '') {
  errorMessage.value = message
}

function routeSummary(route) {
  const distanceKm = safeNumber(route?.distanceKm)
  const durationMinutes = safeNumber(route?.durationMinutes)
  const stepCount = safeNumber(route?.stepCount)

  return [
    route?.transportMode || '',
    distanceKm ? `${distanceKm.toFixed(2)}km` : '',
    durationMinutes ? `${durationMinutes}분` : '',
    stepCount ? `${stepCount.toLocaleString('ko-KR')}걸음` : '',
  ].filter(Boolean).join(' / ')
}

function mergeClusterPhotos(existingPhotos = [], nextPhotos = []) {
  const merged = new Map()
  ;[...existingPhotos, ...nextPhotos].forEach((photo) => {
    if (!photo?.id) {
      return
    }
    merged.set(String(photo.id), photo)
  })
  return Array.from(merged.values())
}

function applySelectedPhoto(detail, preferredPhotoId = null) {
  const availablePhotos = detail?.photos ?? []
  const preferredPhotoExists = preferredPhotoId != null
    && availablePhotos.some((photo) => String(photo.id) === String(preferredPhotoId))
  const hasSelectedPhoto = availablePhotos.some((photo) => String(photo.id) === String(selectedPhotoId.value))

  if (preferredPhotoExists) {
    selectedPhotoId.value = preferredPhotoId
    return
  }

  if (!hasSelectedPhoto) {
    selectedPhotoId.value = detail?.representativeMediaId ?? availablePhotos[0]?.id ?? null
  }
}

async function loadClusterDetail(
  clusterId,
  preferredPhotoId = null,
  {
    page = 0,
    append = false,
    focusMediaId = preferredPhotoId,
  } = {},
) {
  if (!clusterId) {
    selectedClusterDetail.value = null
    selectedPhotoId.value = null
    return
  }

  const isLoadMore = append && page > 0
  if (isLoadMore) {
    isClusterPhotosLoadingMore.value = true
  } else {
    isDetailLoading.value = true
  }
  setError('')

  try {
    const detail = await fetchTravelMyMapPhotoCluster(clusterId, {
      page,
      size: CLUSTER_PHOTO_PAGE_SIZE,
      focusMediaId,
    })

    const nextDetail = append && selectedClusterDetail.value?.id === detail?.id
      ? {
          ...detail,
          photos: mergeClusterPhotos(selectedClusterDetail.value?.photos, detail?.photos),
        }
      : detail

    selectedClusterDetail.value = nextDetail
    applySelectedPhoto(nextDetail, preferredPhotoId)
  } catch (error) {
    if (!isLoadMore) {
      selectedClusterDetail.value = null
      selectedPhotoId.value = null
    }
    setError(error.message)
  } finally {
    if (isLoadMore) {
      isClusterPhotosLoadingMore.value = false
    } else {
      isDetailLoading.value = false
    }
  }
}

async function loadOverview({ autoSelect = false, preferredClusterId = null, reloadDetail = false } = {}) {
  isLoading.value = true
  setError('')

  try {
    const nextOverview = await fetchTravelMyMapOverview()
    overview.value = nextOverview

    const clusters = nextOverview?.photoClusters ?? []
    if (!clusters.length) {
      selectedClusterSummary.value = null
      selectedClusterDetail.value = null
      selectedPhotoId.value = null
      return
    }

    const targetClusterId = preferredClusterId ?? selectedClusterSummary.value?.id ?? null
    let nextSelectedCluster = targetClusterId
      ? clusters.find((cluster) => String(cluster.id) === String(targetClusterId))
      : null

    if (!nextSelectedCluster && autoSelect) {
      nextSelectedCluster = clusters[0]
    }

    if (!nextSelectedCluster && selectedClusterSummary.value) {
      selectedClusterSummary.value = null
      selectedClusterDetail.value = null
      selectedPhotoId.value = null
      return
    }

    if (nextSelectedCluster) {
      const clusterChanged = String(selectedClusterSummary.value?.id ?? '') !== String(nextSelectedCluster.id)
      selectedClusterSummary.value = nextSelectedCluster
      if (clusterChanged || reloadDetail) {
        await loadClusterDetail(nextSelectedCluster.id)
      }
    }
  } catch (error) {
    setError(error.message)
  } finally {
    isLoading.value = false
  }
}

async function handleSelectCluster(cluster) {
  if (!cluster?.id) {
    return
  }

  selectedMarkerId.value = null
  selectedClusterSummary.value = cluster
  await loadClusterDetail(cluster.id)
}

async function handleSelectPhotoPin(pin, options = {}) {
  if (!pin?.clusterId) {
    return
  }

  const nextCluster = photoClusters.value.find((cluster) => String(cluster.id) === String(pin.clusterId))
  if (nextCluster) {
    selectedClusterSummary.value = nextCluster
  }

  selectedMarkerId.value = null
  await loadClusterDetail(pin.clusterId, pin.mediaId, {
    page: 0,
    append: false,
    focusMediaId: pin.mediaId,
  })

  if (options.openPreview) {
    openPhotoLightbox(selectedPhoto.value || selectedClusterRepresentativePhoto.value)
  }
}

function handleSelectMarker(marker) {
  if (!marker?.id) {
    return
  }

  selectedMarkerId.value = marker.id
}

function handleSelectPhoto(photo) {
  if (!photo?.id) {
    return
  }

  selectedPhotoId.value = photo.id
}

function openPhotoLightbox(photo = selectedPhoto.value) {
  if (!photo?.contentUrl) {
    return
  }

  lightboxPhoto.value = photo
}

async function handlePreviewClusterFromMap(item) {
  if (!item) {
    return
  }

  if (item.clusterId) {
    await handleSelectPhotoPin(item, { openPreview: true })
    return
  }

  await handleSelectCluster(item)
  openPhotoLightbox(selectedClusterRepresentativePhoto.value || selectedPhoto.value)
}

async function handleLoadMoreClusterPhotos() {
  if (!selectedClusterSummary.value?.id || !selectedClusterDetail.value?.hasNext || isClusterPhotosLoadingMore.value) {
    return
  }

  await loadClusterDetail(selectedClusterSummary.value.id, selectedPhotoId.value, {
    page: Number(selectedClusterDetail.value.page || 0) + 1,
    append: true,
    focusMediaId: null,
  })
}

function handleMapFullscreenChange(nextValue) {
  isMapFullscreen.value = Boolean(nextValue)
}

async function handleUpdateRepresentative(photo) {
  if (!selectedClusterSummary.value?.id || !photo?.id) {
    return
  }

  isRepresentativeSaving.value = true
  representativeUpdatingId.value = photo.id
  setError('')

  try {
    const detail = await updateTravelMyMapPhotoClusterRepresentative(selectedClusterSummary.value.id, photo.id)
    selectedClusterDetail.value = detail
    selectedPhotoId.value = photo.id
    await loadOverview({ preferredClusterId: detail.id, reloadDetail: false })
  } catch (error) {
    setError(error.message)
  } finally {
    isRepresentativeSaving.value = false
    representativeUpdatingId.value = null
  }
}

const summary = computed(() => ({
  includedPlanCount: overview.value?.includedPlanCount ?? 0,
  markerCount: overview.value?.markerCount ?? 0,
  photoMarkerCount: overview.value?.photoMarkerCount ?? 0,
  photoClusterCount: overview.value?.photoClusterCount ?? 0,
  routeCount: overview.value?.routeCount ?? 0,
  totalDistanceKm: safeNumber(overview.value?.totalDistanceKm),
}))

const photoClusters = computed(() => overview.value?.photoClusters ?? [])
const photoPins = computed(() => overview.value?.photoPins ?? [])
const markers = computed(() => overview.value?.markers ?? [])
const routes = computed(() => overview.value?.routes ?? [])

const selectedClusterPhotos = computed(() => selectedClusterDetail.value?.photos ?? [])
const selectedClusterRepresentativePhoto = computed(() =>
  selectedClusterPhotos.value.find((photo) => String(photo.id) === String(selectedClusterDetail.value?.representativeMediaId))
  ?? selectedClusterDetail.value?.representativePhoto
  ?? null,
)
const selectedPhoto = computed(() => {
  const photos = selectedClusterPhotos.value
  if (!photos.length) {
    return selectedClusterDetail.value?.representativePhoto ?? null
  }

  return photos.find((photo) => String(photo.id) === String(selectedPhotoId.value))
    ?? selectedClusterRepresentativePhoto.value
    ?? photos[0]
})

const selectedClusterLocationLabel = computed(() => {
  const source = selectedClusterSummary.value
  return [source?.country, source?.region, source?.placeName].filter(Boolean).join(' / ') || '위치 정보 없음'
})

const selectedPhotoLocationLabel = computed(() => {
  const source = selectedPhoto.value
  return [source?.country, source?.region, source?.placeName].filter(Boolean).join(' / ') || '위치 정보 없음'
})

const selectedPhotoGpsLabel = computed(() => {
  const latitude = selectedPhoto.value?.gpsLatitude
  const longitude = selectedPhoto.value?.gpsLongitude
  if (latitude == null || longitude == null) {
    return ''
  }
  return `${Number(latitude).toFixed(6)}, ${Number(longitude).toFixed(6)}`
})

const selectedClusterTotalPhotoCount = computed(() =>
  Number(selectedClusterDetail.value?.totalPhotoCount ?? selectedClusterDetail.value?.photoCount ?? selectedClusterPhotos.value.length),
)

onMounted(async () => {
  await loadOverview({ autoSelect: true, reloadDetail: true })
})
</script>

<template>
  <div class="workspace-stack">
    <section class="panel">
      <div class="panel__header">
        <div>
          <h2>내 지도 사진 클러스터</h2>
          <p>업로드한 사진의 GPS를 기준으로 고정된 클러스터와 핀을 확인하고, 큰 사진 보기까지 바로 이어서 조작할 수 있습니다.</p>
        </div>
        <span class="panel__badge">클러스터 {{ summary.photoClusterCount }}개</span>
      </div>

      <div class="travel-summary-grid">
        <article class="travel-stat-card">
          <span>사진 마커 수</span>
          <strong>{{ summary.photoMarkerCount }}</strong>
          <small>서버에서 계산한 전체 사진 위치 개수</small>
        </article>
        <article class="travel-stat-card">
          <span>사진 클러스터</span>
          <strong>{{ summary.photoClusterCount }}</strong>
          <small>같은 위치 묶음을 지도에 고정 표시합니다.</small>
        </article>
        <article class="travel-stat-card">
          <span>기록 메모리</span>
          <strong>{{ summary.markerCount }}</strong>
          <small>여행 기억과 연결된 메모리 기록입니다.</small>
        </article>
        <article class="travel-stat-card">
          <span>경로 거리</span>
          <strong>{{ summary.totalDistanceKm.toFixed(2) }}km</strong>
          <small>현재 저장된 전체 이동 경로의 합계입니다.</small>
        </article>
      </div>
    </section>

    <section class="panel panel--map-fill travel-overview-map-panel">
      <div class="panel__header">
        <div>
          <h2>사진 지도</h2>
          <p>클러스터와 사진 핀은 한 번만 눌러도 정보가 갱신되고, 팝업을 누르면 큰 사진을 바로 열 수 있습니다.</p>
        </div>
        <div class="travel-map-mode-switch">
          <button
            class="travel-map__toolbar-button"
            :class="{ 'is-active': viewMode === 'cluster' }"
            type="button"
            @click="viewMode = 'cluster'"
          >
            클러스터 보기
          </button>
          <button
            class="travel-map__toolbar-button"
            :class="{ 'is-active': viewMode === 'pin' }"
            type="button"
            @click="viewMode = 'pin'"
          >
            핀 보기
          </button>
        </div>
      </div>

      <p v-if="errorMessage" class="panel__empty">{{ errorMessage }}</p>
      <p v-else-if="isLoading" class="panel__empty">사진 지도 데이터를 불러오는 중입니다...</p>
      <TravelMyMapClusterPanel
        v-else
        :photo-clusters="photoClusters"
        :photo-pins="photoPins"
        :markers="markers"
        :routes="routes"
        :active="props.active"
        :display-mode="viewMode"
        :selected-cluster-id="selectedClusterSummary?.id ?? null"
        :selected-photo-id="selectedPhotoId ?? null"
        :selected-marker-id="selectedMarkerId ?? null"
        @select-cluster="handleSelectCluster"
        @select-marker="handleSelectMarker"
        @select-photo-pin="handleSelectPhotoPin"
        @preview-cluster="handlePreviewClusterFromMap"
        @fullscreen-change="handleMapFullscreenChange"
      >
        <template #fullscreen-overlay="{ isFullscreen }">
          <TravelMyMapInspectorPanels
            v-if="isFullscreen"
            :summary="selectedClusterSummary"
            :detail="selectedClusterDetail"
            :selected-photo="selectedPhoto"
            :selected-photo-id="selectedPhotoId"
            :photos="selectedClusterPhotos"
            :is-detail-loading="isDetailLoading"
            :is-representative-saving="isRepresentativeSaving"
            :representative-updating-id="representativeUpdatingId"
            :is-loading-more="isClusterPhotosLoadingMore"
            :can-load-more="selectedClusterDetail?.hasNext"
            :loaded-photo-count="selectedClusterPhotos.length"
            :total-photo-count="selectedClusterTotalPhotoCount"
            :cluster-location-label="selectedClusterLocationLabel"
            :selected-photo-location-label="selectedPhotoLocationLabel"
            :selected-photo-gps-label="selectedPhotoGpsLabel"
            :fullscreen="true"
            @select-photo="handleSelectPhoto"
            @open-photo="openPhotoLightbox"
            @set-representative="handleUpdateRepresentative"
            @load-more="handleLoadMoreClusterPhotos"
          />
        </template>
      </TravelMyMapClusterPanel>
    </section>

    <TravelMyMapInspectorPanels
      v-if="!isMapFullscreen"
      :summary="selectedClusterSummary"
      :detail="selectedClusterDetail"
      :selected-photo="selectedPhoto"
      :selected-photo-id="selectedPhotoId"
      :photos="selectedClusterPhotos"
      :is-detail-loading="isDetailLoading"
      :is-representative-saving="isRepresentativeSaving"
      :representative-updating-id="representativeUpdatingId"
      :is-loading-more="isClusterPhotosLoadingMore"
      :can-load-more="selectedClusterDetail?.hasNext"
      :loaded-photo-count="selectedClusterPhotos.length"
      :total-photo-count="selectedClusterTotalPhotoCount"
      :cluster-location-label="selectedClusterLocationLabel"
      :selected-photo-location-label="selectedPhotoLocationLabel"
      :selected-photo-gps-label="selectedPhotoGpsLabel"
      @select-photo="handleSelectPhoto"
      @open-photo="openPhotoLightbox"
      @set-representative="handleUpdateRepresentative"
      @load-more="handleLoadMoreClusterPhotos"
    />

    <section class="panel">
      <div class="panel__header">
        <div>
          <h2>전체 경로 목록</h2>
          <p>사진 클러스터와 함께 저장된 이동 경로를 그대로 확인할 수 있습니다.</p>
        </div>
        <span class="panel__badge">{{ routes.length }}개 경로</span>
      </div>

      <div class="sheet-table-wrap">
        <table class="sheet-table">
          <thead>
            <tr>
              <th>여행</th>
              <th>날짜</th>
              <th>제목</th>
              <th>요약</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="route in routes" :key="route.id">
              <td>{{ route.planName }}</td>
              <td>{{ formatDate(route.routeDate) }}</td>
              <td>{{ route.title || '이동 경로' }}</td>
              <td>{{ routeSummary(route) || '-' }}</td>
            </tr>
            <tr v-if="!routes.length">
              <td colspan="4" class="sheet-table__empty">아직 저장된 경로가 없습니다.</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <TravelPhotoLightbox :photo="lightboxPhoto" @close="lightboxPhoto = null" />
  </div>
</template>
