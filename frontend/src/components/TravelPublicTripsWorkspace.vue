<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import {
  fetchTravelPublicTripPhotoCluster,
  fetchTravelPublicTrips,
} from '../lib/api'
import { buildThumbnailUrl, THUMBNAIL_VARIANTS } from '../lib/mediaPreview'
import { formatDate, safeNumber } from '../lib/uiFormat'
import TravelMyMapClusterPanel from './TravelMyMapClusterPanel.vue'
import TravelMyMapInspectorPanels from './TravelMyMapInspectorPanels.vue'
import TravelPhotoLightbox from './TravelPhotoLightbox.vue'

const CLUSTER_PHOTO_PAGE_SIZE = 12
const LIGHTBOX_SCOPE_GLOBAL = 'global'
const LIGHTBOX_SCOPE_CLUSTER = 'cluster'

const props = defineProps({
  active: {
    type: Boolean,
    default: true,
  },
})

const isLoading = ref(false)
const isDetailLoading = ref(false)
const isClusterPhotosLoadingMore = ref(false)
const isMapFullscreen = ref(false)
const overviewErrorMessage = ref('')
const detailErrorMessage = ref('')
const overview = ref(null)
const selectedClusterSummary = ref(null)
const selectedClusterDetail = ref(null)
const selectedPhotoId = ref(null)
const selectedPlanId = ref(null)
const lightboxPhoto = ref(null)
const lightboxPhotos = ref([])
const lightboxScope = ref(LIGHTBOX_SCOPE_GLOBAL)

function setOverviewError(message = '') {
  overviewErrorMessage.value = message
}

function setDetailError(message = '') {
  detailErrorMessage.value = message
}

function mergeClusterPhotos(existingPhotos = [], nextPhotos = []) {
  const merged = new Map()
  ;[...existingPhotos, ...nextPhotos].forEach((photo) => {
    if (photo?.id) {
      merged.set(String(photo.id), photo)
    }
  })
  return Array.from(merged.values())
}

function mergeLightboxPhotos(...photoGroups) {
  const merged = new Map()
  photoGroups.flat().forEach((photo) => {
    if (!photo?.id || !photo?.contentUrl) {
      return
    }
    merged.set(String(photo.id), {
      ...(merged.get(String(photo.id)) ?? {}),
      ...photo,
    })
  })
  return Array.from(merged.values())
}

function sortPhotosByTime(photos = []) {
  return [...photos].sort((left, right) => {
    const leftDateTime = `${left?.expenseDate ?? ''} ${left?.expenseTime ?? ''}`.trim()
    const rightDateTime = `${right?.expenseDate ?? ''} ${right?.expenseTime ?? ''}`.trim()
    const dateCompare = leftDateTime.localeCompare(rightDateTime)
    if (dateCompare !== 0) return dateCompare

    const uploadedCompare = String(left?.uploadedAt ?? '').localeCompare(String(right?.uploadedAt ?? ''))
    if (uploadedCompare !== 0) return uploadedCompare

    return String(left?.id ?? '').localeCompare(String(right?.id ?? ''))
  })
}

function normalizeGlobalLightboxPhoto(photoPin) {
  if (!photoPin?.mediaId || !photoPin?.photoUrl) {
    return null
  }

  return {
    id: photoPin.mediaId,
    clusterId: photoPin.clusterId,
    recordId: photoPin.recordId,
    planId: photoPin.planId,
    planName: photoPin.planName,
    planColorHex: photoPin.planColorHex,
    originalFileName: photoPin.title || null,
    caption: photoPin.title || null,
    uploadedBy: photoPin.sharedByDisplayName || null,
    uploadedAt: null,
    contentUrl: photoPin.photoUrl,
    expenseDate: photoPin.memoryDate,
    expenseTime: photoPin.memoryTime,
    title: photoPin.title,
    country: photoPin.country,
    region: photoPin.region,
    placeName: photoPin.placeName,
    latitude: photoPin.latitude,
    longitude: photoPin.longitude,
    gpsLatitude: photoPin.latitude,
    gpsLongitude: photoPin.longitude,
    representativeOverride: photoPin.representativeOverride,
  }
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
  setDetailError('')

  try {
    const detail = await fetchTravelPublicTripPhotoCluster(clusterId, {
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
    setDetailError(error.message)
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
  setOverviewError('')

  try {
    const nextOverview = await fetchTravelPublicTrips()
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
    setOverviewError(error.message)
  } finally {
    isLoading.value = false
  }
}

async function handleSelectCluster(cluster) {
  if (!cluster?.id) return
  const clusterChanged = String(selectedClusterSummary.value?.id ?? '') !== String(cluster.id)
  selectedClusterSummary.value = cluster
  selectedPlanId.value = cluster.planId ?? null
  if (clusterChanged) {
    selectedClusterDetail.value = null
    selectedPhotoId.value = null
  }
  await loadClusterDetail(cluster.id)
}

async function handleSelectPhotoPin(pin, options = {}) {
  if (!pin?.clusterId) return

  const nextCluster = photoClusters.value.find((cluster) => String(cluster.id) === String(pin.clusterId))
  if (nextCluster) {
    selectedClusterSummary.value = nextCluster
    selectedPlanId.value = nextCluster.planId ?? null
  }

  selectedPhotoId.value = pin.mediaId ?? null
  await loadClusterDetail(pin.clusterId, pin.mediaId, {
    page: 0,
    append: false,
    focusMediaId: pin.mediaId,
  })

  if (options.openPreview) {
    openPhotoLightbox(selectedPhoto.value || selectedClusterRepresentativePhoto.value, { scope: LIGHTBOX_SCOPE_GLOBAL })
  }
}

async function handlePreviewClusterFromMap(item) {
  if (!item) return

  if (item.clusterId) {
    await handleSelectPhotoPin(item, { openPreview: true })
    return
  }

  await handleSelectCluster(item)
  openPhotoLightbox(selectedClusterRepresentativePhoto.value || selectedPhoto.value, { scope: LIGHTBOX_SCOPE_GLOBAL })
}

function handleSelectPhoto(photo) {
  if (photo?.id) {
    selectedPhotoId.value = photo.id
  }
}

function openPhotoLightbox(photo = selectedPhoto.value, { scope = LIGHTBOX_SCOPE_GLOBAL } = {}) {
  if (!photo?.contentUrl) return

  lightboxScope.value = scope
  lightboxPhotos.value = scope === LIGHTBOX_SCOPE_CLUSTER
    ? sortPhotosByTime(mergeLightboxPhotos(selectedClusterPhotosInTimeOrder.value, [photo]))
    : sortPhotosByTime(mergeLightboxPhotos(allMapPhotosInTimeOrder.value, [photo]))
  lightboxPhoto.value = photo

  if (scope === LIGHTBOX_SCOPE_CLUSTER && photo?.id) {
    selectedPhotoId.value = photo.id
  }
}

function handleSelectLightboxPhoto(photo) {
  if (!photo?.contentUrl) return
  lightboxPhoto.value = photo

  if (lightboxScope.value === LIGHTBOX_SCOPE_CLUSTER && photo?.id) {
    selectedPhotoId.value = photo.id
  }
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

function clearSelection() {
  selectedClusterSummary.value = null
  selectedClusterDetail.value = null
  selectedPhotoId.value = null
  selectedPlanId.value = null
  setDetailError('')
}

function handleMapFullscreenChange(nextValue) {
  isMapFullscreen.value = Boolean(nextValue)
}

function selectPlan(plan) {
  selectedPlanId.value = plan?.planId ?? null
  const firstCluster = photoClusters.value.find((cluster) => String(cluster.planId) === String(plan?.planId))
  if (firstCluster) {
    handleSelectCluster(firstCluster)
  }
}

const summary = computed(() => ({
  includedPlanCount: overview.value?.includedPlanCount ?? 0,
  photoMarkerCount: overview.value?.photoMarkerCount ?? 0,
  photoClusterCount: overview.value?.photoClusterCount ?? 0,
  routeCount: overview.value?.routeCount ?? 0,
  totalDistanceKm: safeNumber(overview.value?.totalDistanceKm),
}))

const publicPlans = computed(() => overview.value?.plans ?? [])
const photoClusters = computed(() => overview.value?.photoClusters ?? [])
const photoPins = computed(() => overview.value?.photoPins ?? [])
const routes = computed(() => overview.value?.routes ?? [])

const selectedClusterPhotos = computed(() => {
  const photos = selectedClusterDetail.value?.photos ?? []
  if (photos.length) return photos

  const representativePhoto = selectedClusterDetail.value?.representativePhoto
  return representativePhoto ? [representativePhoto] : []
})

const selectedClusterPhotosInTimeOrder = computed(() =>
  sortPhotosByTime(selectedClusterPhotos.value),
)

const allMapPhotosInTimeOrder = computed(() =>
  sortPhotosByTime(
    mergeLightboxPhotos(
      photoPins.value.map(normalizeGlobalLightboxPhoto).filter(Boolean),
      selectedClusterPhotos.value,
    ),
  ),
)

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

const featuredPlans = computed(() => publicPlans.value.slice(0, 6))

watch(
  () => props.active,
  (active) => {
    if (active && !overview.value && !isLoading.value) {
      loadOverview()
    }
  },
)

onMounted(() => {
  if (props.active) {
    loadOverview()
  }
})
</script>

<template>
  <div class="workspace-stack travel-public-trips">
    <section class="panel">
      <div class="panel__header">
        <div>
          <h2>공개 여행 지도</h2>
          <p>가입자가 퍼블릭으로 공개한 여행 기록을 사진 클러스터와 경로 기준으로 둘러봅니다.</p>
        </div>
        <button class="button button--ghost" type="button" :disabled="isLoading" @click="loadOverview({ reloadDetail: true })">
          {{ isLoading ? '불러오는 중' : '새로고침' }}
        </button>
      </div>

      <div class="travel-media-summary-grid">
        <article class="travel-media-summary-card">
          <span>공개 여행</span>
          <strong>{{ summary.includedPlanCount }}</strong>
          <small>퍼블릭으로 공개된 여행</small>
        </article>
        <article class="travel-media-summary-card">
          <span>사진</span>
          <strong>{{ summary.photoMarkerCount }}</strong>
          <small>{{ summary.photoClusterCount }}개 클러스터</small>
        </article>
        <article class="travel-media-summary-card">
          <span>경로</span>
          <strong>{{ summary.routeCount }}</strong>
          <small>{{ summary.totalDistanceKm.toFixed(1) }}km</small>
        </article>
      </div>
    </section>

    <div v-if="overviewErrorMessage" class="feedback feedback--error">{{ overviewErrorMessage }}</div>
    <div v-if="detailErrorMessage" class="feedback feedback--error">{{ detailErrorMessage }}</div>

    <section class="panel panel--map-fill">
      <div class="panel__header">
        <div>
          <h2>공개 사진 클러스터</h2>
          <p>사진을 누르면 공유자가 공개한 원본 보기 창으로 이동합니다.</p>
        </div>
        <span class="panel__badge">{{ summary.photoClusterCount }}개 묶음</span>
      </div>

      <TravelMyMapClusterPanel
        :photo-clusters="photoClusters"
        :photo-pins="photoPins"
        :markers="[]"
        :routes="routes"
        :active="active"
        :selected-cluster-id="selectedClusterSummary?.id ?? null"
        :selected-photo-id="selectedPhotoId"
        display-mode="cluster"
        @select-cluster="handleSelectCluster"
        @select-photo-pin="handleSelectPhotoPin"
        @preview-cluster="handlePreviewClusterFromMap"
        @fullscreen-change="handleMapFullscreenChange"
        @clear-selection="clearSelection"
      >
        <template #fullscreen-overlay="{ isFullscreen }">
          <TravelMyMapInspectorPanels
            v-if="selectedClusterSummary || selectedClusterDetail || isDetailLoading"
            :summary="selectedClusterSummary"
            :detail="selectedClusterDetail"
            :selected-photo="selectedPhoto"
            :selected-photo-id="selectedPhotoId"
            :photos="selectedClusterPhotos"
            :is-detail-loading="isDetailLoading"
            :is-representative-saving="false"
            :is-loading-more="isClusterPhotosLoadingMore"
            :can-load-more="Boolean(selectedClusterDetail?.hasNext)"
            :total-photo-count="selectedClusterDetail?.totalPhotoCount ?? selectedClusterPhotos.length"
            :loaded-photo-count="selectedClusterPhotos.length"
            :cluster-location-label="selectedClusterLocationLabel"
            :selected-photo-location-label="selectedPhotoLocationLabel"
            :selected-photo-gps-label="selectedPhotoGpsLabel"
            :fullscreen="true"
            :closable="true"
            @select-photo="handleSelectPhoto"
            @open-photo="(photo) => openPhotoLightbox(photo, { scope: LIGHTBOX_SCOPE_CLUSTER })"
            @load-more="handleLoadMoreClusterPhotos"
            @clear="clearSelection"
          />
          <TravelPhotoLightbox
            v-if="isFullscreen && lightboxPhoto"
            :photo="lightboxPhoto"
            :photos="lightboxPhotos"
            :current-photo-id="lightboxPhoto?.id ?? null"
            @close="lightboxPhoto = null"
            @select-photo="handleSelectLightboxPhoto"
          />
        </template>
      </TravelMyMapClusterPanel>
    </section>

    <section v-if="selectedClusterSummary || selectedClusterDetail || isDetailLoading" class="panel">
      <TravelMyMapInspectorPanels
        :summary="selectedClusterSummary"
        :detail="selectedClusterDetail"
        :selected-photo="selectedPhoto"
        :selected-photo-id="selectedPhotoId"
        :photos="selectedClusterPhotos"
        :is-detail-loading="isDetailLoading"
        :is-representative-saving="false"
        :is-loading-more="isClusterPhotosLoadingMore"
        :can-load-more="Boolean(selectedClusterDetail?.hasNext)"
        :total-photo-count="selectedClusterDetail?.totalPhotoCount ?? selectedClusterPhotos.length"
        :loaded-photo-count="selectedClusterPhotos.length"
        :cluster-location-label="selectedClusterLocationLabel"
        :selected-photo-location-label="selectedPhotoLocationLabel"
        :selected-photo-gps-label="selectedPhotoGpsLabel"
        @select-photo="handleSelectPhoto"
        @open-photo="(photo) => openPhotoLightbox(photo, { scope: LIGHTBOX_SCOPE_CLUSTER })"
        @load-more="handleLoadMoreClusterPhotos"
      />
    </section>

    <section class="panel">
      <div class="panel__header">
        <div>
          <h2>공개 여행 목록</h2>
          <p>공유한 사람과 여행별 대표 사진을 확인하고 해당 여행의 첫 사진 클러스터로 이동합니다.</p>
        </div>
        <span class="panel__badge">{{ publicPlans.length }}개</span>
      </div>

      <div v-if="featuredPlans.length" class="travel-public-trip-grid">
        <button
          v-for="plan in featuredPlans"
          :key="plan.planId"
          class="travel-public-trip-card"
          :class="{ 'travel-public-trip-card--active': String(selectedPlanId || '') === String(plan.planId) }"
          type="button"
          @click="selectPlan(plan)"
        >
          <img
            v-if="plan.representativePhotoUrl"
            :src="buildThumbnailUrl(plan.representativePhotoUrl, THUMBNAIL_VARIANTS.preview)"
            :alt="plan.planName"
            loading="lazy"
            decoding="async"
          />
          <div v-else class="travel-public-trip-card__empty">사진 없음</div>
          <span>{{ plan.sharedByDisplayName || '공유자 미상' }}</span>
          <strong>{{ plan.planName }}</strong>
          <small>{{ plan.destination || '목적지 미정' }}</small>
          <small>{{ formatDate(plan.startDate) }} - {{ formatDate(plan.endDate) }}</small>
          <small>사진 {{ plan.mediaItemCount }}장 · 기록 {{ plan.memoryRecordCount }}건 · 경로 {{ plan.routeSegmentCount }}개</small>
        </button>
      </div>
      <p v-else-if="isLoading" class="panel__empty">공개 여행을 불러오는 중입니다.</p>
      <p v-else class="panel__empty">아직 공개된 여행이 없습니다.</p>
    </section>

    <TravelPhotoLightbox
      v-if="!isMapFullscreen && lightboxPhoto"
      :photo="lightboxPhoto"
      :photos="lightboxPhotos"
      :current-photo-id="lightboxPhoto?.id ?? null"
      @close="lightboxPhoto = null"
      @select-photo="handleSelectLightboxPhoto"
    />
  </div>
</template>
