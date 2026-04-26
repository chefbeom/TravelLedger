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
const searchQuery = ref('')
const selectedRegionKey = ref('all')
const selectedContributorKey = ref('all')
const sortMode = ref('latest')
const photoOnly = ref(false)
const activePlanFilterId = ref('all')
const publicMapMode = ref('cluster')

const sortOptions = [
  { value: 'latest', label: '최근 공개순' },
  { value: 'popular', label: '인기순' },
  { value: 'photos', label: '사진 많은순' },
  { value: 'distance', label: '동선 긴순' },
]

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
  activePlanFilterId.value = plan?.planId == null ? 'all' : String(plan.planId)
  const firstCluster = photoClusters.value.find((cluster) => String(cluster.planId) === String(plan?.planId))
  if (firstCluster) {
    handleSelectCluster(firstCluster)
  }
}

function clearPlanFilter() {
  activePlanFilterId.value = 'all'
  selectedPlanId.value = null
}

function clearCommunityFilters() {
  searchQuery.value = ''
  selectedRegionKey.value = 'all'
  selectedContributorKey.value = 'all'
  sortMode.value = 'latest'
  photoOnly.value = false
  publicMapMode.value = 'cluster'
  clearPlanFilter()
  clearSelection()
}

function normalizeText(value) {
  return String(value ?? '').trim().toLowerCase()
}

function normalizeKey(value) {
  return normalizeText(value).replace(/\s+/g, '-')
}

function getPlanKey(planId) {
  return String(planId ?? '')
}

function resolveRegionLabel(source) {
  return [source?.country, source?.region].filter(Boolean).join(' / ')
    || source?.placeName
    || source?.destination
    || '지역 미설정'
}

function resolveRegionKey(source) {
  return normalizeKey(resolveRegionLabel(source))
}

function resolveContributorLabel(source) {
  return source?.sharedByDisplayName || source?.sharedByLoginId || '공유자 미상'
}

function resolveContributorKey(source) {
  return normalizeKey(resolveContributorLabel(source))
}

function resolvePlanLocation(plan) {
  const clusters = clustersByPlanId.value.get(getPlanKey(plan?.planId)) ?? []
  const primaryCluster = clusters.find((cluster) => cluster.country || cluster.region || cluster.placeName)
  return [primaryCluster?.country, primaryCluster?.region, primaryCluster?.placeName].filter(Boolean).join(' / ')
    || plan?.destination
    || '목적지 미정'
}

function resolvePlanCover(plan) {
  if (plan?.representativePhotoUrl) {
    return plan.representativePhotoUrl
  }
  const firstCluster = clustersByPlanId.value.get(getPlanKey(plan?.planId))?.find((cluster) => cluster.representativePhotoUrl)
  return firstCluster?.representativePhotoUrl ?? ''
}

function buildPlanSearchText(plan) {
  const clusters = clustersByPlanId.value.get(getPlanKey(plan?.planId)) ?? []
  return [
    plan?.planName,
    plan?.destination,
    plan?.status,
    resolveContributorLabel(plan),
    ...clusters.flatMap((cluster) => [
      cluster.title,
      cluster.country,
      cluster.region,
      cluster.placeName,
      cluster.category,
    ]),
  ].map(normalizeText).join(' ')
}

function buildClusterSearchText(cluster) {
  const plan = planById.value.get(getPlanKey(cluster?.planId))
  return [
    cluster?.title,
    cluster?.country,
    cluster?.region,
    cluster?.placeName,
    cluster?.category,
    cluster?.planName,
    resolveContributorLabel(cluster),
    plan?.planName,
    plan?.destination,
  ].map(normalizeText).join(' ')
}

function getPlanScore(plan) {
  return safeNumber(plan?.mediaItemCount) * 3
    + safeNumber(plan?.memoryRecordCount) * 2
    + safeNumber(plan?.routeSegmentCount) * 2
    + safeNumber(plan?.totalDistanceKm)
}

function comparePlans(left, right) {
  if (sortMode.value === 'popular') {
    return getPlanScore(right) - getPlanScore(left)
  }
  if (sortMode.value === 'photos') {
    return safeNumber(right?.mediaItemCount) - safeNumber(left?.mediaItemCount)
  }
  if (sortMode.value === 'distance') {
    return safeNumber(right?.totalDistanceKm) - safeNumber(left?.totalDistanceKm)
  }
  return String(right?.publicSharedAt ?? '').localeCompare(String(left?.publicSharedAt ?? ''))
}

function matchesControlsForPlan(plan) {
  if (activePlanFilterId.value !== 'all' && getPlanKey(plan?.planId) !== String(activePlanFilterId.value)) {
    return false
  }

  if (photoOnly.value && safeNumber(plan?.mediaItemCount) <= 0) {
    return false
  }

  const clusters = clustersByPlanId.value.get(getPlanKey(plan?.planId)) ?? []
  if (selectedRegionKey.value !== 'all' && !clusters.some((cluster) => resolveRegionKey(cluster) === selectedRegionKey.value)) {
    return false
  }

  if (selectedContributorKey.value !== 'all') {
    const planContributorMatches = resolveContributorKey(plan) === selectedContributorKey.value
    const clusterContributorMatches = clusters.some((cluster) => resolveContributorKey(cluster) === selectedContributorKey.value)
    if (!planContributorMatches && !clusterContributorMatches) {
      return false
    }
  }

  const query = normalizeText(searchQuery.value)
  if (query && !buildPlanSearchText(plan).includes(query)) {
    return false
  }

  return true
}

function matchesControlsForCluster(cluster) {
  const plan = planById.value.get(getPlanKey(cluster?.planId))
  if (plan && !matchesControlsForPlan(plan)) {
    return false
  }

  if (activePlanFilterId.value !== 'all' && getPlanKey(cluster?.planId) !== String(activePlanFilterId.value)) {
    return false
  }

  if (photoOnly.value && safeNumber(cluster?.photoCount) <= 0) {
    return false
  }

  if (selectedRegionKey.value !== 'all' && resolveRegionKey(cluster) !== selectedRegionKey.value) {
    return false
  }

  if (selectedContributorKey.value !== 'all' && resolveContributorKey(cluster) !== selectedContributorKey.value) {
    return false
  }

  const query = normalizeText(searchQuery.value)
  if (query && !buildClusterSearchText(cluster).includes(query)) {
    return false
  }

  return true
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

const planById = computed(() => {
  const map = new Map()
  publicPlans.value.forEach((plan) => {
    map.set(getPlanKey(plan.planId), plan)
  })
  return map
})

const clustersByPlanId = computed(() => {
  const map = new Map()
  photoClusters.value.forEach((cluster) => {
    const key = getPlanKey(cluster.planId)
    const current = map.get(key) ?? []
    current.push(cluster)
    map.set(key, current)
  })
  return map
})

const regionOptions = computed(() => {
  const regions = new Map()
  photoClusters.value.forEach((cluster) => {
    const key = resolveRegionKey(cluster)
    if (!regions.has(key)) {
      regions.set(key, {
        key,
        label: resolveRegionLabel(cluster),
        count: 0,
      })
    }
    regions.get(key).count += 1
  })
  return Array.from(regions.values())
    .sort((left, right) => right.count - left.count || left.label.localeCompare(right.label))
    .slice(0, 12)
})

const contributorOptions = computed(() => {
  const contributors = new Map()
  ;[...publicPlans.value, ...photoClusters.value].forEach((item) => {
    const key = resolveContributorKey(item)
    if (!contributors.has(key)) {
      contributors.set(key, {
        key,
        label: resolveContributorLabel(item),
        count: 0,
      })
    }
    contributors.get(key).count += 1
  })
  return Array.from(contributors.values())
    .sort((left, right) => right.count - left.count || left.label.localeCompare(right.label))
})

const visiblePlans = computed(() =>
  publicPlans.value
    .filter(matchesControlsForPlan)
    .sort(comparePlans),
)

const visiblePlanIds = computed(() => new Set(visiblePlans.value.map((plan) => getPlanKey(plan.planId))))

const visiblePhotoClusters = computed(() =>
  photoClusters.value.filter((cluster) =>
    visiblePlanIds.value.has(getPlanKey(cluster.planId)) && matchesControlsForCluster(cluster),
  ),
)

const visibleClusterIds = computed(() => new Set(visiblePhotoClusters.value.map((cluster) => String(cluster.id))))

const visiblePhotoPins = computed(() =>
  photoPins.value.filter((pin) => visibleClusterIds.value.has(String(pin.clusterId))),
)

const visibleRoutes = computed(() =>
  routes.value.filter((route) => visiblePlanIds.value.has(getPlanKey(route.planId))),
)

const visibleSummary = computed(() => ({
  planCount: visiblePlans.value.length,
  photoCount: visiblePhotoPins.value.length,
  clusterCount: visiblePhotoClusters.value.length,
  routeCount: visibleRoutes.value.length,
  distanceKm: visibleRoutes.value.reduce((total, route) => total + safeNumber(route?.distanceKm), 0),
}))

const communityStats = computed(() => ({
  contributorCount: contributorOptions.value.length,
  regionCount: regionOptions.value.length,
  publicPlanCount: summary.value.includedPlanCount,
  photoCount: summary.value.photoMarkerCount,
}))

const recentPlans = computed(() =>
  [...publicPlans.value]
    .sort((left, right) => String(right?.publicSharedAt ?? '').localeCompare(String(left?.publicSharedAt ?? '')))
    .slice(0, 4),
)

const popularPlans = computed(() =>
  [...publicPlans.value]
    .sort((left, right) => getPlanScore(right) - getPlanScore(left))
    .slice(0, 4),
)

const spotlightClusters = computed(() =>
  [...visiblePhotoClusters.value]
    .sort((left, right) =>
      String(right?.publicSharedAt ?? right?.memoryDate ?? '').localeCompare(String(left?.publicSharedAt ?? left?.memoryDate ?? '')),
    )
    .slice(0, 6),
)

const recentPhotoPins = computed(() =>
  [...visiblePhotoPins.value]
    .sort((left, right) =>
      `${right?.memoryDate ?? ''} ${right?.memoryTime ?? ''}`.localeCompare(`${left?.memoryDate ?? ''} ${left?.memoryTime ?? ''}`),
    )
    .slice(0, 10),
)

const hasActiveFilters = computed(() =>
  Boolean(normalizeText(searchQuery.value))
    || selectedRegionKey.value !== 'all'
    || selectedContributorKey.value !== 'all'
    || sortMode.value !== 'latest'
    || photoOnly.value
    || activePlanFilterId.value !== 'all',
)

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
      visiblePhotoPins.value.map(normalizeGlobalLightboxPhoto).filter(Boolean),
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

const selectedClusterTitle = computed(() =>
  selectedClusterSummary.value?.title
    || selectedPhoto.value?.title
    || selectedClusterSummary.value?.placeName
    || selectedClusterSummary.value?.planName
    || '공개 여행 스팟',
)

const selectedClusterCoverUrl = computed(() =>
  selectedClusterRepresentativePhoto.value?.contentUrl
    || selectedClusterSummary.value?.representativePhotoUrl
    || '',
)

const selectedClusterContributor = computed(() =>
  resolveContributorLabel(selectedClusterSummary.value || selectedPhoto.value),
)

const selectedClusterPlan = computed(() =>
  planById.value.get(getPlanKey(selectedClusterSummary.value?.planId)) ?? null,
)

const featuredPlans = computed(() => visiblePlans.value.slice(0, 9))

watch(
  () => [
    searchQuery.value,
    selectedRegionKey.value,
    selectedContributorKey.value,
    photoOnly.value,
    activePlanFilterId.value,
  ].join('|'),
  () => {
    if (selectedClusterSummary.value?.id && !visibleClusterIds.value.has(String(selectedClusterSummary.value.id))) {
      clearSelection()
    }
  },
)

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
  <div class="travel-public-app">
    <aside class="travel-public-rail" aria-label="공개 여행 탐색">
      <button class="travel-public-rail__logo" type="button" title="공개 여행 홈" @click="clearCommunityFilters">
        <span>TL</span>
      </button>
      <button class="travel-public-rail__button is-active" type="button" title="홈" @click="clearCommunityFilters">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 11.5 12 4l8 7.5V20a1 1 0 0 1-1 1h-4.5v-6h-5v6H5a1 1 0 0 1-1-1z" /></svg>
      </button>
      <button class="travel-public-rail__button" type="button" title="전체 지도" @click="clearPlanFilter">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m4 6 5-2 6 2 5-2v14l-5 2-6-2-5 2zM9 4v14M15 6v14" /></svg>
      </button>
      <button
        class="travel-public-rail__button"
        :class="{ 'is-active': photoOnly }"
        type="button"
        title="사진 있는 여행"
        @click="photoOnly = !photoOnly"
      >
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 4h12a1 1 0 0 1 1 1v16l-7-4-7 4V5a1 1 0 0 1 1-1z" /></svg>
      </button>
    </aside>

    <main class="travel-public-canvas">
      <form class="travel-public-top-search" @submit.prevent>
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m21 21-4.2-4.2M10.8 18a7.2 7.2 0 1 1 0-14.4 7.2 7.2 0 0 1 0 14.4z" /></svg>
        <input v-model="searchQuery" type="search" placeholder="검색" aria-label="공개 여행 검색" />
        <button v-if="searchQuery" type="button" @click="searchQuery = ''">지우기</button>
      </form>

      <div v-if="overviewErrorMessage" class="feedback feedback--error">{{ overviewErrorMessage }}</div>
      <div v-if="detailErrorMessage" class="feedback feedback--error">{{ detailErrorMessage }}</div>

      <section class="travel-public-map-card">
        <div class="travel-public-map-card__stats">
          <span>{{ visibleSummary.planCount }} 여행</span>
          <span>{{ visibleSummary.clusterCount }} 스팟</span>
          <span>{{ visibleSummary.photoCount }} 사진</span>
          <span>{{ visibleSummary.routeCount }} 경로</span>
        </div>

        <div class="travel-public-map-stage">
          <TravelMyMapClusterPanel
            :photo-clusters="visiblePhotoClusters"
            :photo-pins="visiblePhotoPins"
            :markers="[]"
            :routes="visibleRoutes"
            :active="active"
            :selected-cluster-id="selectedClusterSummary?.id ?? null"
            :selected-photo-id="selectedPhotoId"
            :display-mode="publicMapMode"
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

          <aside v-if="selectedClusterSummary || selectedClusterDetail || isDetailLoading" class="travel-public-map-drawer">
            <button class="travel-public-map-drawer__close" type="button" @click="clearSelection">‹</button>
            <img
              v-if="selectedClusterCoverUrl"
              class="travel-public-map-drawer__cover"
              :src="buildThumbnailUrl(selectedClusterCoverUrl, THUMBNAIL_VARIANTS.preview)"
              :alt="selectedClusterTitle"
              loading="lazy"
              decoding="async"
            />
            <div v-else class="travel-public-map-drawer__cover travel-public-map-drawer__cover--empty">사진 없음</div>
            <div class="travel-public-map-drawer__copy">
              <strong>{{ selectedClusterTitle }}</strong>
              <span>{{ selectedClusterLocationLabel }}</span>
              <small>{{ selectedClusterContributor }} · {{ selectedClusterSummary?.planName || selectedClusterPlan?.planName || '공개 여행' }}</small>
            </div>
            <div class="travel-public-map-drawer__actions">
              <button type="button" @click="openPhotoLightbox(selectedPhoto, { scope: LIGHTBOX_SCOPE_CLUSTER })">
                <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m12 3 3 6 6 .9-4.5 4.3 1.1 6.1L12 17.3l-5.6 3 1.1-6.1L3 9.9 9 9z" /></svg>
              </button>
              <button v-if="selectedClusterPlan" type="button" @click="selectPlan(selectedClusterPlan)">
                <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M7 4h10v16H7zM10 8h4M10 12h4M10 16h4" /></svg>
              </button>
              <button type="button" @click="selectedContributorKey = resolveContributorKey(selectedClusterSummary)">
                <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zm-7 9a7 7 0 0 1 14 0" /></svg>
              </button>
              <button type="button" @click="openPhotoLightbox(selectedPhoto || selectedClusterRepresentativePhoto, { scope: LIGHTBOX_SCOPE_CLUSTER })">
                <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 12h16M12 4v16" /></svg>
              </button>
            </div>

            <TravelMyMapInspectorPanels
              class="travel-public-map-drawer__inspector"
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
          </aside>
        </div>
      </section>

      <section class="travel-public-filter-dock">
        <select v-model="selectedRegionKey" aria-label="지역 필터">
          <option value="all">전체 지역</option>
          <option v-for="region in regionOptions" :key="region.key" :value="region.key">
            {{ region.label }} ({{ region.count }})
          </option>
        </select>
        <select v-model="selectedContributorKey" aria-label="공유자 필터">
          <option value="all">전체 공유자</option>
          <option v-for="contributor in contributorOptions" :key="contributor.key" :value="contributor.key">
            {{ contributor.label }} ({{ contributor.count }})
          </option>
        </select>
        <select v-model="sortMode" aria-label="정렬">
          <option v-for="option in sortOptions" :key="option.value" :value="option.value">
            {{ option.label }}
          </option>
        </select>
        <label>
          <input v-model="photoOnly" type="checkbox" />
          <span>사진 있는 여행</span>
        </label>
        <div class="travel-public-view-switch" role="group" aria-label="지도 표시 방식">
          <button
            type="button"
            :class="{ 'is-active': publicMapMode === 'cluster' }"
            @click="publicMapMode = 'cluster'"
          >
            클러스터
          </button>
          <button
            type="button"
            :class="{ 'is-active': publicMapMode === 'pin' }"
            @click="publicMapMode = 'pin'"
          >
            핀
          </button>
        </div>
        <button v-if="hasActiveFilters" type="button" @click="clearCommunityFilters">초기화</button>
      </section>

      <section class="travel-public-shelf">
        <div class="travel-public-shelf__head">
          <h2>최근 업데이트</h2>
          <span>›</span>
        </div>
        <div v-if="spotlightClusters.length" class="travel-public-shelf__scroll">
          <button
            v-for="cluster in spotlightClusters"
            :key="`spot-${cluster.id}`"
            class="travel-public-poster"
            type="button"
            @click="handleSelectCluster(cluster)"
          >
            <img
              v-if="cluster.representativePhotoUrl"
              :src="buildThumbnailUrl(cluster.representativePhotoUrl, THUMBNAIL_VARIANTS.preview)"
              :alt="cluster.title || cluster.placeName || cluster.planName"
              loading="lazy"
              decoding="async"
            />
            <span v-else>사진 없음</span>
            <strong>{{ cluster.title || cluster.placeName || cluster.planName }}</strong>
            <small>{{ resolveContributorLabel(cluster) }}</small>
          </button>
        </div>
        <p v-else class="travel-public-empty">조건에 맞는 공개 스팟이 없습니다.</p>
      </section>

      <section class="travel-public-catalog">
        <div class="travel-public-catalog__main">
          <div class="travel-public-shelf__head">
            <h2>공개 여행 목록</h2>
            <span>{{ visiblePlans.length }} / {{ publicPlans.length }}</span>
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
                v-if="resolvePlanCover(plan)"
                :src="buildThumbnailUrl(resolvePlanCover(plan), THUMBNAIL_VARIANTS.preview)"
                :alt="plan.planName"
                loading="lazy"
                decoding="async"
              />
              <div v-else class="travel-public-trip-card__empty">사진 없음</div>
              <div class="travel-public-trip-card__body">
                <span>{{ resolveContributorLabel(plan) }}</span>
                <strong>{{ plan.planName }}</strong>
                <small>{{ resolvePlanLocation(plan) }}</small>
                <small>{{ formatDate(plan.startDate) }} - {{ formatDate(plan.endDate) }}</small>
              </div>
              <div class="travel-public-trip-card__stats">
                <small>사진 {{ plan.mediaItemCount }}장</small>
                <small>기록 {{ plan.memoryRecordCount }}건</small>
                <small>경로 {{ plan.routeSegmentCount }}개</small>
              </div>
            </button>
          </div>
          <p v-else-if="isLoading" class="travel-public-empty">공개 여행을 불러오는 중입니다.</p>
          <p v-else class="travel-public-empty">조건에 맞는 공개 여행이 없습니다.</p>
        </div>

        <aside class="travel-public-popular">
          <div class="travel-public-shelf__head">
            <h2>인기 여행</h2>
          </div>
          <button
            v-for="plan in popularPlans"
            :key="`popular-${plan.planId}`"
            class="travel-public-rank"
            :class="{ 'travel-public-rank--active': String(activePlanFilterId) === String(plan.planId) }"
            type="button"
            @click="selectPlan(plan)"
          >
            <span>{{ plan.mediaItemCount }}장</span>
            <strong>{{ plan.planName }}</strong>
            <small>{{ resolvePlanLocation(plan) }}</small>
          </button>
        </aside>
      </section>

      <TravelPhotoLightbox
        v-if="!isMapFullscreen && lightboxPhoto"
        :photo="lightboxPhoto"
        :photos="lightboxPhotos"
        :current-photo-id="lightboxPhoto?.id ?? null"
        @close="lightboxPhoto = null"
        @select-photo="handleSelectLightboxPhoto"
      />
    </main>
  </div>
</template>
