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
  <div class="workspace-stack travel-public-trips">
    <section class="travel-public-hero">
      <div class="travel-public-hero__copy">
        <span class="travel-public-eyebrow">Travel community atlas</span>
        <h2>공개 여행 커뮤니티</h2>
        <p>다른 사용자가 공개한 여행 기록을 지도, 사진, 경로, 작성자 기준으로 둘러봅니다.</p>
        <div class="travel-public-hero__actions">
          <button class="button button--primary" type="button" :disabled="isLoading" @click="loadOverview({ reloadDetail: true })">
            {{ isLoading ? '불러오는 중' : '최신 공개 여행 보기' }}
          </button>
          <button class="button button--ghost" type="button" :disabled="!hasActiveFilters" @click="clearCommunityFilters">
            필터 초기화
          </button>
        </div>
      </div>

      <div class="travel-public-hero__stats">
        <article>
          <span>공개 여행</span>
          <strong>{{ communityStats.publicPlanCount }}</strong>
          <small>등록된 퍼블릭 여행</small>
        </article>
        <article>
          <span>사진</span>
          <strong>{{ communityStats.photoCount }}</strong>
          <small>{{ summary.photoClusterCount }}개 위치 묶음</small>
        </article>
        <article>
          <span>공유자</span>
          <strong>{{ communityStats.contributorCount }}</strong>
          <small>{{ communityStats.regionCount }}개 지역</small>
        </article>
      </div>

      <aside class="travel-public-hero__updates">
        <div class="travel-public-section-title">
          <span>Recently shared</span>
          <strong>최근 공개</strong>
        </div>
        <button
          v-for="plan in recentPlans"
          :key="`recent-${plan.planId}`"
          class="travel-public-update"
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
          <span v-else class="travel-public-update__empty">사진 없음</span>
          <span>
            <strong>{{ plan.planName }}</strong>
            <small>{{ resolveContributorLabel(plan) }} · {{ resolvePlanLocation(plan) }}</small>
          </span>
        </button>
        <p v-if="!recentPlans.length" class="panel__empty">아직 공개된 여행이 없습니다.</p>
      </aside>
    </section>

    <div v-if="overviewErrorMessage" class="feedback feedback--error">{{ overviewErrorMessage }}</div>
    <div v-if="detailErrorMessage" class="feedback feedback--error">{{ detailErrorMessage }}</div>

    <section class="panel travel-public-controls">
      <div class="panel__header">
        <div>
          <h2>커뮤니티 탐색</h2>
          <p>여행명, 지역, 공유자, 사진 유무로 공개 여행을 좁혀봅니다.</p>
        </div>
        <span class="panel__badge">{{ visibleSummary.planCount }}개 표시</span>
      </div>

      <div class="travel-public-controls__grid">
        <label class="field travel-public-search">
          <span>검색</span>
          <input
            v-model="searchQuery"
            class="field__input"
            type="search"
            placeholder="여행명, 도시, 장소, 공유자"
          />
        </label>
        <label class="field">
          <span>지역</span>
          <select v-model="selectedRegionKey" class="field__input">
            <option value="all">전체 지역</option>
            <option v-for="region in regionOptions" :key="region.key" :value="region.key">
              {{ region.label }} ({{ region.count }})
            </option>
          </select>
        </label>
        <label class="field">
          <span>공유자</span>
          <select v-model="selectedContributorKey" class="field__input">
            <option value="all">전체 공유자</option>
            <option v-for="contributor in contributorOptions" :key="contributor.key" :value="contributor.key">
              {{ contributor.label }} ({{ contributor.count }})
            </option>
          </select>
        </label>
        <label class="field">
          <span>정렬</span>
          <select v-model="sortMode" class="field__input">
            <option v-for="option in sortOptions" :key="option.value" :value="option.value">
              {{ option.label }}
            </option>
          </select>
        </label>
        <label class="travel-public-toggle">
          <input v-model="photoOnly" type="checkbox" />
          <span>사진 있는 여행만</span>
        </label>
      </div>

      <div v-if="activePlanFilterId !== 'all'" class="travel-public-active-filter">
        <span>선택 여행만 표시 중</span>
        <button class="button button--ghost" type="button" @click="clearPlanFilter">전체 지도 보기</button>
      </div>
    </section>

    <div class="travel-public-community-layout">
      <section class="panel panel--map-fill travel-public-map-panel">
        <div class="panel__header">
          <div>
            <h2>공개 여행 지도</h2>
            <p>필터 결과에 맞는 사진 클러스터와 여행 경로가 지도에 표시됩니다.</p>
          </div>
          <span class="panel__badge">{{ visibleSummary.clusterCount }}개 묶음 · {{ visibleSummary.routeCount }}개 경로</span>
        </div>

        <TravelMyMapClusterPanel
          :photo-clusters="visiblePhotoClusters"
          :photo-pins="visiblePhotoPins"
          :markers="[]"
          :routes="visibleRoutes"
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

      <aside class="travel-public-sidebar">
        <section class="panel travel-public-side-panel">
          <div class="travel-public-section-title">
            <span>Most popular</span>
            <strong>인기 여행</strong>
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
          <p v-if="!popularPlans.length" class="panel__empty">인기 여행을 계산할 공개 데이터가 없습니다.</p>
        </section>

        <section class="panel travel-public-side-panel">
          <div class="travel-public-section-title">
            <span>Explore areas</span>
            <strong>지역으로 보기</strong>
          </div>
          <div class="travel-public-chip-list">
            <button
              class="chip chip--neutral"
              :class="{ 'travel-public-chip--active': selectedRegionKey === 'all' }"
              type="button"
              @click="selectedRegionKey = 'all'"
            >
              전체
            </button>
            <button
              v-for="region in regionOptions.slice(0, 8)"
              :key="`chip-${region.key}`"
              class="chip chip--neutral"
              :class="{ 'travel-public-chip--active': selectedRegionKey === region.key }"
              type="button"
              @click="selectedRegionKey = region.key"
            >
              {{ region.label }} {{ region.count }}
            </button>
          </div>
        </section>
      </aside>
    </div>

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

    <section class="panel travel-public-spotlight-panel">
      <div class="panel__header">
        <div>
          <h2>최근 포토 스팟</h2>
          <p>공개된 사진 위치를 여행 공유 커뮤니티 피드처럼 빠르게 살펴봅니다.</p>
        </div>
        <span class="panel__badge">{{ spotlightClusters.length }}개</span>
      </div>

      <div v-if="spotlightClusters.length" class="travel-public-spot-grid">
        <button
          v-for="cluster in spotlightClusters"
          :key="`spot-${cluster.id}`"
          class="travel-public-spot-card"
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
          <span v-else class="travel-public-spot-card__empty">사진 없음</span>
          <span class="travel-public-spot-card__meta">{{ resolveContributorLabel(cluster) }}</span>
          <strong>{{ cluster.title || cluster.placeName || cluster.planName }}</strong>
          <small>{{ [cluster.country, cluster.region, cluster.placeName].filter(Boolean).join(' / ') || '위치 미설정' }}</small>
          <small>{{ cluster.photoCount }}장 · {{ formatDate(cluster.memoryDate) }}</small>
        </button>
      </div>
      <p v-else class="panel__empty">조건에 맞는 포토 스팟이 없습니다.</p>
    </section>

    <section class="panel">
      <div class="panel__header">
        <div>
          <h2>공개 여행 목록</h2>
          <p>공유자, 대표 사진, 여행 기간, 기록 수를 기준으로 공개 여행을 선택합니다.</p>
        </div>
        <span class="panel__badge">{{ visiblePlans.length }} / {{ publicPlans.length }}개</span>
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
      <p v-else-if="isLoading" class="panel__empty">공개 여행을 불러오는 중입니다.</p>
      <p v-else class="panel__empty">조건에 맞는 공개 여행이 없습니다.</p>
    </section>

    <section class="panel travel-public-photo-strip-panel">
      <div class="panel__header">
        <div>
          <h2>공개 사진 타임라인</h2>
          <p>현재 조건에 맞는 공개 사진을 썸네일로 먼저 보고, 선택 시 원본 보기로 들어갑니다.</p>
        </div>
        <span class="panel__badge">{{ visiblePhotoPins.length }}장</span>
      </div>

      <div v-if="recentPhotoPins.length" class="travel-public-photo-strip">
        <button
          v-for="pin in recentPhotoPins"
          :key="`photo-${pin.mediaId}`"
          class="travel-public-photo-tile"
          type="button"
          @click="handleSelectPhotoPin(pin, { openPreview: true })"
        >
          <img
            :src="buildThumbnailUrl(pin.photoUrl, THUMBNAIL_VARIANTS.preview)"
            :alt="pin.title || pin.planName"
            loading="lazy"
            decoding="async"
          />
          <span>{{ pin.placeName || pin.region || pin.country || pin.planName }}</span>
        </button>
      </div>
      <p v-else class="panel__empty">표시할 공개 사진이 없습니다.</p>
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
