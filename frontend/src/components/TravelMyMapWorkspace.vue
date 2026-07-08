<script setup>
import { computed, defineAsyncComponent, onMounted, reactive, ref, watch } from 'vue'
import {
  createTravelMapShare,
  fetchTravelMyMapOverview,
  fetchTravelMyMapPhotoCluster,
  updateTravelMyMapPhotoClusterRepresentative,
} from '../lib/api'
import { formatDate, safeNumber } from '../lib/uiFormat'
import TravelMyMapClusterPanel from './TravelMyMapClusterPanel.vue'

const TravelMyMapInspectorPanels = defineAsyncComponent(() => import('./TravelMyMapInspectorPanels.vue'))
const TravelPhotoLightbox = defineAsyncComponent(() => import('./TravelPhotoLightbox.vue'))

const CLUSTER_PHOTO_PAGE_SIZE = 12
const LIGHTBOX_SCOPE_GLOBAL = 'global'
const LIGHTBOX_SCOPE_CLUSTER = 'cluster'

const props = defineProps({
  active: {
    type: Boolean,
    default: true,
  },
})

const emit = defineEmits(['open-memories', 'open-routes', 'open-photos'])

const isLoading = ref(false)
const isDetailLoading = ref(false)
const isRepresentativeSaving = ref(false)
const isClusterPhotosLoadingMore = ref(false)
const isMapFullscreen = ref(false)
const overviewErrorMessage = ref('')
const detailErrorMessage = ref('')
const overview = ref(null)
const selectedClusterSummary = ref(null)
const selectedClusterDetail = ref(null)
const selectedPhotoId = ref(null)
const selectedMarkerId = ref(null)
const lightboxPhoto = ref(null)
const lightboxPhotos = ref([])
const lightboxScope = ref(LIGHTBOX_SCOPE_GLOBAL)
const representativeUpdatingId = ref(null)
const viewMode = ref('cluster')
const detailRecoveryClusterId = ref(null)
const mapFilterMode = ref('all')
const selectedPlanFilterKeys = ref([])
const selectedCountryKey = ref('all')
const selectedRegionKey = ref('all')
const mapFitRequestKey = ref(0)
const shareDialog = reactive({
  open: false,
  title: '',
  selectedPlanIds: [],
  excludedRecordIds: [],
  excludedMediaIds: [],
  excludedRouteIds: [],
  saving: false,
  error: '',
  generatedUrl: '',
})

function setOverviewError(message = '') {
  overviewErrorMessage.value = message
}

function setDetailError(message = '') {
  detailErrorMessage.value = message
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

function mergeLightboxPhotos(...photoGroups) {
  const merged = new Map()

  photoGroups.flat().forEach((photo) => {
    if (!photo?.id || !photo?.contentUrl) {
      return
    }

    const key = String(photo.id)
    merged.set(key, {
      ...(merged.get(key) ?? {}),
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

    if (dateCompare !== 0) {
      return dateCompare
    }

    const leftUploadedAt = String(left?.uploadedAt ?? '')
    const rightUploadedAt = String(right?.uploadedAt ?? '')
    const uploadedAtCompare = leftUploadedAt.localeCompare(rightUploadedAt)

    if (uploadedAtCompare !== 0) {
      return uploadedAtCompare
    }

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
    uploadedBy: null,
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
    const nextOverview = await fetchTravelMyMapOverview()
    overview.value = nextOverview

    const clusters = nextOverview?.photoClusters ?? []
    if (!clusters.length) {
      selectedClusterSummary.value = null
      selectedClusterDetail.value = null
      selectedPhotoId.value = null
      detailRecoveryClusterId.value = null
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
      detailRecoveryClusterId.value = null
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
  if (!cluster?.id) {
    return
  }

  const clusterChanged = String(selectedClusterSummary.value?.id ?? '') !== String(cluster.id)
  selectedMarkerId.value = null
  selectedClusterSummary.value = cluster
  if (clusterChanged) {
    selectedClusterDetail.value = null
    selectedPhotoId.value = null
    detailRecoveryClusterId.value = null
  }
  await loadClusterDetail(cluster.id)
}

async function handleSelectPhotoPin(pin, options = {}) {
  if (!pin?.clusterId) {
    return
  }

  const clusterChanged = String(selectedClusterSummary.value?.id ?? '') !== String(pin.clusterId)
  const nextCluster = photoClusters.value.find((cluster) => String(cluster.id) === String(pin.clusterId))
  if (nextCluster) {
    selectedClusterSummary.value = nextCluster
  }

  selectedMarkerId.value = null
  if (clusterChanged) {
    selectedClusterDetail.value = null
    detailRecoveryClusterId.value = null
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

function openPhotoLightbox(photo = selectedPhoto.value, { scope = LIGHTBOX_SCOPE_GLOBAL } = {}) {
  if (!photo?.contentUrl) {
    return
  }

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
  if (!photo?.contentUrl) {
    return
  }

  lightboxPhoto.value = photo

  if (lightboxScope.value === LIGHTBOX_SCOPE_CLUSTER && photo?.id) {
    selectedPhotoId.value = photo.id
  }
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
  openPhotoLightbox(selectedClusterRepresentativePhoto.value || selectedPhoto.value, { scope: LIGHTBOX_SCOPE_GLOBAL })
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

function clearSelection() {
  selectedClusterSummary.value = null
  selectedClusterDetail.value = null
  selectedPhotoId.value = null
  selectedMarkerId.value = null
  detailRecoveryClusterId.value = null
  setDetailError('')
}

async function handleUpdateRepresentative(photo) {
  if (!selectedClusterSummary.value?.id || !photo?.id) {
    return
  }

  isRepresentativeSaving.value = true
  representativeUpdatingId.value = photo.id
  setDetailError('')

  try {
    const detail = await updateTravelMyMapPhotoClusterRepresentative(selectedClusterSummary.value.id, photo.id)
    selectedClusterDetail.value = detail
    selectedPhotoId.value = photo.id
    await loadOverview({ preferredClusterId: detail.id, reloadDetail: false })
  } catch (error) {
    setDetailError(error.message)
  } finally {
    isRepresentativeSaving.value = false
    representativeUpdatingId.value = null
  }
}

const summary = computed(() => ({
  includedPlanCount: visiblePlanCount.value,
  markerCount: visibleMarkers.value.length,
  photoMarkerCount: visiblePhotoPins.value.length,
  photoClusterCount: visiblePhotoClusters.value.length,
  routeCount: visibleRoutes.value.length,
  totalDistanceKm: visibleRoutes.value.reduce((total, route) => total + safeNumber(route?.distanceKm), 0),
}))

const photoClusters = computed(() => overview.value?.photoClusters ?? [])
const photoPins = computed(() => overview.value?.photoPins ?? [])
const markers = computed(() => overview.value?.markers ?? [])
const routes = computed(() => overview.value?.routes ?? [])

function normalizeFilterText(value) {
  return String(value ?? '').trim()
}

function getPlanFilterKey(source) {
  const planId = source?.planId ?? source?.travelPlanId ?? source?.plan?.id
  if (planId !== undefined && planId !== null && String(planId).trim()) {
    return `plan:${planId}`
  }
  const planName = normalizeFilterText(source?.planName || source?.plan?.name)
  return planName ? `name:${planName}` : 'name:unassigned'
}

function getPlanFilterLabel(source) {
  return normalizeFilterText(source?.planName || source?.plan?.name) || '여행 미지정'
}

function getCountryFilterKey(source) {
  return normalizeFilterText(source?.country) || 'unknown-country'
}

function getCountryFilterLabel(key) {
  return key === 'unknown-country' ? '국가 미입력' : key
}

function getRegionFilterKey(source) {
  const country = getCountryFilterKey(source)
  const region = normalizeFilterText(source?.region) || '지역 미입력'
  return `${country}::${region}`
}

function getRegionFilterLabel(key) {
  if (!key || key === 'unknown-country::지역 미입력') {
    return '지역 미입력'
  }
  const [country, region] = String(key).split('::')
  return `${getCountryFilterLabel(country)} / ${region || '지역 미입력'}`
}

function addCountOption(map, key, label, amount = 1) {
  const current = map.get(key) ?? { key, label, count: 0 }
  current.count += Math.max(1, Number(amount) || 1)
  if (!current.label || current.label.includes('미지정') || current.label.includes('미입력')) {
    current.label = label || current.label
  }
  map.set(key, current)
}

const planFilterOptions = computed(() => {
  const options = new Map()
  ;[...photoPins.value, ...markers.value, ...routes.value].forEach((item) => {
    addCountOption(options, getPlanFilterKey(item), getPlanFilterLabel(item))
  })
  photoClusters.value.forEach((cluster) => {
    addCountOption(options, getPlanFilterKey(cluster), getPlanFilterLabel(cluster), cluster?.photoCount || 1)
  })
  return Array.from(options.values())
    .sort((left, right) => left.label.localeCompare(right.label, 'ko') || left.key.localeCompare(right.key))
})

const selectedPlanKeySet = computed(() => new Set(selectedPlanFilterKeys.value.map(String)))

const planLocationIndex = computed(() => {
  const index = new Map()
  ;[...photoPins.value, ...markers.value, ...photoClusters.value].forEach((item) => {
    const planKey = getPlanFilterKey(item)
    const current = index.get(planKey) ?? { countryKeys: new Set(), regionKeys: new Set() }
    current.countryKeys.add(getCountryFilterKey(item))
    current.regionKeys.add(getRegionFilterKey(item))
    index.set(planKey, current)
  })
  return index
})

const countryFilterOptions = computed(() => {
  const options = new Map()
  ;[...photoPins.value, ...markers.value, ...photoClusters.value].forEach((item) => {
    const key = getCountryFilterKey(item)
    addCountOption(options, key, getCountryFilterLabel(key), item?.photoCount || 1)
  })
  return Array.from(options.values())
    .sort((left, right) => {
      if (left.key === 'unknown-country') return 1
      if (right.key === 'unknown-country') return -1
      return left.label.localeCompare(right.label, 'ko')
    })
})

const regionFilterOptions = computed(() => {
  const options = new Map()
  ;[...photoPins.value, ...markers.value, ...photoClusters.value].forEach((item) => {
    const key = getRegionFilterKey(item)
    addCountOption(options, key, getRegionFilterLabel(key), item?.photoCount || 1)
  })
  return Array.from(options.values())
    .sort((left, right) => {
      if (left.key.startsWith('unknown-country::')) return 1
      if (right.key.startsWith('unknown-country::')) return -1
      return left.label.localeCompare(right.label, 'ko')
    })
})

function planMatchesMapFilter(source) {
  if (mapFilterMode.value !== 'plans' || !selectedPlanFilterKeys.value.length) {
    return true
  }
  return selectedPlanKeySet.value.has(getPlanFilterKey(source))
}

function countryMatchesMapFilter(source) {
  if (mapFilterMode.value !== 'country' || selectedCountryKey.value === 'all') {
    return true
  }
  if (getCountryFilterKey(source) === selectedCountryKey.value) {
    return true
  }
  const planEntry = planLocationIndex.value.get(getPlanFilterKey(source))
  return Boolean(planEntry?.countryKeys?.has(selectedCountryKey.value))
}

function regionMatchesMapFilter(source) {
  if (mapFilterMode.value !== 'region' || selectedRegionKey.value === 'all') {
    return true
  }
  if (getRegionFilterKey(source) === selectedRegionKey.value) {
    return true
  }
  const planEntry = planLocationIndex.value.get(getPlanFilterKey(source))
  return Boolean(planEntry?.regionKeys?.has(selectedRegionKey.value))
}

function itemMatchesMapFilters(source) {
  return planMatchesMapFilter(source)
    && countryMatchesMapFilter(source)
    && regionMatchesMapFilter(source)
}

const visiblePhotoClusters = computed(() => photoClusters.value.filter(itemMatchesMapFilters))
const visiblePhotoPins = computed(() => photoPins.value.filter(itemMatchesMapFilters))
const visibleMarkers = computed(() => markers.value.filter(itemMatchesMapFilters))
const visibleRoutes = computed(() => routes.value.filter(itemMatchesMapFilters))
const visiblePlanCount = computed(() => {
  const keys = new Set()
  ;[...visiblePhotoPins.value, ...visibleMarkers.value, ...visibleRoutes.value, ...visiblePhotoClusters.value].forEach((item) => {
    keys.add(getPlanFilterKey(item))
  })
  return keys.size
})
const hasActiveMapFilter = computed(() => (
  mapFilterMode.value !== 'all'
  && (
    (mapFilterMode.value === 'plans' && selectedPlanFilterKeys.value.length > 0)
    || (mapFilterMode.value === 'country' && selectedCountryKey.value !== 'all')
    || (mapFilterMode.value === 'region' && selectedRegionKey.value !== 'all')
  )
))
const mapFilterResultLabel = computed(() => [
  `여행 ${summary.value.includedPlanCount}개`,
  `사진 ${summary.value.photoMarkerCount}장`,
  `기록 ${summary.value.markerCount}개`,
  `경로 ${summary.value.routeCount}개`,
].join(' · '))

function setMapFilterMode(mode) {
  mapFilterMode.value = ['all', 'plans', 'country', 'region'].includes(mode) ? mode : 'all'
}

function togglePlanFilter(key) {
  const normalizedKey = String(key)
  selectedPlanFilterKeys.value = selectedPlanKeySet.value.has(normalizedKey)
    ? selectedPlanFilterKeys.value.filter((item) => String(item) !== normalizedKey)
    : [...selectedPlanFilterKeys.value, normalizedKey]
}

function clearMapFilters() {
  mapFilterMode.value = 'all'
  selectedPlanFilterKeys.value = []
  selectedCountryKey.value = 'all'
  selectedRegionKey.value = 'all'
}

const sharePlanOptions = computed(() => planFilterOptions.value
  .map((plan) => ({
    ...plan,
    planId: parsePlanIdFromFilterKey(plan.key),
  }))
  .filter((plan) => plan.planId != null))
const shareSelectedPlanIdSet = computed(() => new Set(shareDialog.selectedPlanIds.map((id) => String(id))))
const shareExcludedRecordIdSet = computed(() => new Set(shareDialog.excludedRecordIds.map((id) => String(id))))
const shareExcludedMediaIdSet = computed(() => new Set(shareDialog.excludedMediaIds.map((id) => String(id))))
const shareExcludedRouteIdSet = computed(() => new Set(shareDialog.excludedRouteIds.map((id) => String(id))))
const shareCandidateMarkers = computed(() => markers.value.filter((item) => shareSelectedPlanIdSet.value.has(String(item?.planId))))
const shareCandidatePhotoPins = computed(() => photoPins.value.filter((item) => shareSelectedPlanIdSet.value.has(String(item?.planId))))
const shareCandidateRoutes = computed(() => routes.value.filter((item) => shareSelectedPlanIdSet.value.has(String(item?.planId))))

function parsePlanIdFromFilterKey(key) {
  const text = String(key || '')
  if (!text.startsWith('plan:')) {
    return null
  }
  const numeric = Number(text.slice('plan:'.length))
  return Number.isFinite(numeric) && numeric > 0 ? numeric : null
}

function buildTravelShareUrl(token) {
  const base = `${window.location.origin}${window.location.pathname}`
  return `${base}#travel-share/${encodeURIComponent(token)}`
}

function openShareDialog() {
  const selectedFromFilter = mapFilterMode.value === 'plans'
    ? selectedPlanFilterKeys.value.map(parsePlanIdFromFilterKey).filter(Boolean)
    : []
  shareDialog.open = true
  shareDialog.title = ''
  shareDialog.selectedPlanIds = selectedFromFilter.length
    ? selectedFromFilter
    : sharePlanOptions.value.map((plan) => plan.planId)
  shareDialog.excludedRecordIds = []
  shareDialog.excludedMediaIds = []
  shareDialog.excludedRouteIds = []
  shareDialog.error = ''
  shareDialog.generatedUrl = ''
}

function closeShareDialog() {
  shareDialog.open = false
}

function toggleSharePlan(planId) {
  const normalizedId = Number(planId)
  if (!Number.isFinite(normalizedId)) {
    return
  }
  shareDialog.generatedUrl = ''
  const exists = shareSelectedPlanIdSet.value.has(String(normalizedId))
  shareDialog.selectedPlanIds = exists
    ? shareDialog.selectedPlanIds.filter((id) => String(id) !== String(normalizedId))
    : [...shareDialog.selectedPlanIds, normalizedId]
  const nextPlanSet = new Set(shareDialog.selectedPlanIds.map((id) => String(id)))
  shareDialog.excludedRecordIds = shareDialog.excludedRecordIds.filter((id) => {
    const marker = markers.value.find((item) => String(item.id) === String(id))
    return marker && nextPlanSet.has(String(marker.planId))
  })
  shareDialog.excludedMediaIds = shareDialog.excludedMediaIds.filter((id) => {
    const pin = photoPins.value.find((item) => String(item.mediaId) === String(id))
    return pin && nextPlanSet.has(String(pin.planId))
  })
  shareDialog.excludedRouteIds = shareDialog.excludedRouteIds.filter((id) => {
    const route = routes.value.find((item) => String(item.id) === String(id))
    return route && nextPlanSet.has(String(route.planId))
  })
}

function toggleId(listName, id) {
  const normalizedId = Number(id)
  if (!Number.isFinite(normalizedId)) {
    return
  }
  shareDialog.generatedUrl = ''
  const list = shareDialog[listName]
  shareDialog[listName] = list.some((item) => String(item) === String(normalizedId))
    ? list.filter((item) => String(item) !== String(normalizedId))
    : [...list, normalizedId]
}

async function createShareLink() {
  if (!shareDialog.selectedPlanIds.length) {
    shareDialog.error = '공유할 여행을 하나 이상 선택해 주세요.'
    return
  }
  shareDialog.saving = true
  shareDialog.error = ''
  shareDialog.generatedUrl = ''
  try {
    const response = await createTravelMapShare({
      title: shareDialog.title,
      planIds: shareDialog.selectedPlanIds,
      excludedRecordIds: shareDialog.excludedRecordIds,
      excludedMediaIds: shareDialog.excludedMediaIds,
      excludedRouteIds: shareDialog.excludedRouteIds,
    })
    shareDialog.generatedUrl = buildTravelShareUrl(response.token)
  } catch (error) {
    shareDialog.error = error.message || '공유 링크를 만들지 못했습니다.'
  } finally {
    shareDialog.saving = false
  }
}

async function copyShareUrl() {
  if (!shareDialog.generatedUrl) {
    return
  }
  try {
    await navigator.clipboard?.writeText(shareDialog.generatedUrl)
  } catch {
    shareDialog.error = '클립보드 복사에 실패했습니다. 링크를 직접 복사해 주세요.'
  }
}
const expandedRoutePlanKey = ref('')

function getRoutePlanKey(route) {
  const planId = route?.planId ?? route?.travelPlanId ?? route?.plan?.id
  if (planId !== undefined && planId !== null && String(planId).trim()) {
    return `plan-${planId}`
  }
  return `name-${route?.planName || 'unassigned'}`
}

function getRoutePlanName(route) {
  return route?.planName || '여행 미지정'
}

function formatRouteGroupDuration(minutes) {
  const value = Math.round(safeNumber(minutes))
  if (!value) {
    return ''
  }
  const hours = Math.floor(value / 60)
  const remainingMinutes = value % 60
  return hours ? `${hours}시간 ${remainingMinutes}분` : `${remainingMinutes}분`
}

function formatRouteGroupDateRange(group) {
  if (!group.firstDate && !group.lastDate) {
    return '날짜 정보 없음'
  }
  if (group.firstDate === group.lastDate) {
    return formatDate(group.firstDate)
  }
  return `${formatDate(group.firstDate)} ~ ${formatDate(group.lastDate)}`
}

function buildRouteGroupSummary(group) {
  return [
    `${group.routes.length}개 경로`,
    group.totalDistanceKm ? `${group.totalDistanceKm.toFixed(2)}km` : '',
    formatRouteGroupDuration(group.totalDurationMinutes),
    group.totalSteps ? `${Math.round(group.totalSteps).toLocaleString('ko-KR')}걸음` : '',
  ].filter(Boolean).join(' / ')
}

const routePlanGroups = computed(() => {
  const groups = new Map()
  visibleRoutes.value.forEach((route) => {
    const key = getRoutePlanKey(route)
    if (!groups.has(key)) {
      groups.set(key, {
        key,
        planName: getRoutePlanName(route),
        routes: [],
        totalDistanceKm: 0,
        totalDurationMinutes: 0,
        totalSteps: 0,
        firstDate: '',
        lastDate: '',
      })
    }

    const group = groups.get(key)
    group.routes.push(route)
    group.totalDistanceKm += safeNumber(route?.distanceKm)
    group.totalDurationMinutes += safeNumber(route?.durationMinutes)
    group.totalSteps += safeNumber(route?.stepCount)

    const routeDate = route?.routeDate || ''
    if (routeDate) {
      group.firstDate = !group.firstDate || routeDate < group.firstDate ? routeDate : group.firstDate
      group.lastDate = !group.lastDate || routeDate > group.lastDate ? routeDate : group.lastDate
    }
  })

  return Array.from(groups.values())
    .map((group) => {
      const sortedRoutes = group.routes.slice().sort((left, right) => String(right.routeDate || '').localeCompare(String(left.routeDate || '')))
      const normalizedGroup = { ...group, routes: sortedRoutes }
      return {
        ...normalizedGroup,
        dateRange: formatRouteGroupDateRange(normalizedGroup),
        summary: buildRouteGroupSummary(normalizedGroup),
      }
    })
    .sort((left, right) => String(right.lastDate || '').localeCompare(String(left.lastDate || '')) || left.planName.localeCompare(right.planName))
})

function isRoutePlanGroupExpanded(group) {
  return expandedRoutePlanKey.value === group.key
}

function toggleRoutePlanGroup(group) {
  expandedRoutePlanKey.value = isRoutePlanGroupExpanded(group) ? '' : group.key
}

const selectedClusterPhotos = computed(() => {
  const photos = selectedClusterDetail.value?.photos ?? []
  if (photos.length) {
    return photos
  }

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

const selectedClusterTotalPhotoCount = computed(() =>
  Number(selectedClusterDetail.value?.totalPhotoCount ?? selectedClusterDetail.value?.photoCount ?? selectedClusterPhotos.value.length),
)

const shouldShowFullscreenInspector = computed(() =>
  Boolean(
    isMapFullscreen.value
    && (
      selectedClusterSummary.value
      || selectedClusterDetail.value
      || isDetailLoading.value
      || detailErrorMessage.value
    ),
  ),
)

onMounted(async () => {
  await loadOverview({ autoSelect: false, reloadDetail: false })
})

watch(
  () => [selectedClusterSummary.value?.id, selectedClusterDetail.value?.id, isDetailLoading.value],
  async ([summaryId, detailId, loading]) => {
    if (!summaryId || loading) {
      return
    }

    if (String(detailId ?? '') === String(summaryId)) {
      return
    }

    if (String(detailRecoveryClusterId.value ?? '') === String(summaryId)) {
      return
    }

    detailRecoveryClusterId.value = summaryId
    await loadClusterDetail(summaryId, selectedPhotoId.value)
  },
)

watch(
  () => [
    mapFilterMode.value,
    selectedPlanFilterKeys.value.join('|'),
    selectedCountryKey.value,
    selectedRegionKey.value,
  ],
  () => {
    mapFitRequestKey.value += 1
    if (selectedClusterSummary.value?.id && !visiblePhotoClusters.value.some((cluster) => String(cluster.id) === String(selectedClusterSummary.value?.id))) {
      clearSelection()
    }
    if (expandedRoutePlanKey.value && !routePlanGroups.value.some((group) => group.key === expandedRoutePlanKey.value)) {
      expandedRoutePlanKey.value = ''
    }
  },
)

watch(planFilterOptions, (options) => {
  const availableKeys = new Set(options.map((option) => String(option.key)))
  const nextKeys = selectedPlanFilterKeys.value.filter((key) => availableKeys.has(String(key)))
  if (nextKeys.length !== selectedPlanFilterKeys.value.length) {
    selectedPlanFilterKeys.value = nextKeys
  }
})
</script>

<template>
  <div class="workspace-stack">
    <section class="panel">
      <div class="panel__header travel-my-map-header">
        <div>
          <span class="panel__eyebrow">TRAVEL ATLAS</span>
          <h2>내 여행 지도</h2>
        </div>
        <div class="travel-my-map-header__actions">
          <button class="button button--primary" type="button" @click="emit('open-memories')">장소 기록 추가</button>
          <button class="button button--secondary" type="button" @click="emit('open-routes')">GPX 경로 추가</button>
          <button class="button button--ghost" type="button" @click="emit('open-photos')">사진첩 열기</button>
          <button class="button button--primary" type="button" @click="openShareDialog">지도 공유</button>
        </div>
      </div>
      <div class="travel-summary-grid">
        <article class="travel-stat-card">
          <span>사진 핀</span>
          <strong>{{ summary.photoMarkerCount }}</strong>
        </article>
        <article class="travel-stat-card">
          <span>사진 클러스터</span>
          <strong>{{ summary.photoClusterCount }}</strong>
        </article>
        <article class="travel-stat-card">
          <span>장소 기록</span>
          <strong>{{ summary.markerCount }}</strong>
        </article>
        <article class="travel-stat-card">
          <span>경로 거리</span>
          <strong>{{ summary.totalDistanceKm.toFixed(2) }}km</strong>
        </article>
      </div>

      <div class="travel-map-filter-panel">
        <div class="travel-map-filter-panel__top">
          <div class="scope-toggle scope-toggle--wrap" role="group" aria-label="여행 지도 보기 기준">
            <button class="button" :class="{ 'button--primary': mapFilterMode === 'all' }" type="button" @click="setMapFilterMode('all')">전체</button>
            <button class="button" :class="{ 'button--primary': mapFilterMode === 'plans' }" type="button" @click="setMapFilterMode('plans')">여행별</button>
            <button class="button" :class="{ 'button--primary': mapFilterMode === 'country' }" type="button" @click="setMapFilterMode('country')">국가별</button>
            <button class="button" :class="{ 'button--primary': mapFilterMode === 'region' }" type="button" @click="setMapFilterMode('region')">지역별</button>
          </div>
          <div class="travel-map-filter-panel__meta">
            <span class="chip chip--neutral">{{ mapFilterResultLabel }}</span>
            <button v-if="hasActiveMapFilter" class="button button--ghost" type="button" @click="clearMapFilters">초기화</button>
          </div>
        </div>

        <div v-if="mapFilterMode === 'plans'" class="travel-map-filter-options" aria-label="여행 선택">
          <button
            v-for="plan in planFilterOptions"
            :key="plan.key"
            class="travel-map-filter-chip"
            :class="{ 'is-active': selectedPlanKeySet.has(plan.key) }"
            type="button"
            @click="togglePlanFilter(plan.key)"
          >
            <strong>{{ plan.label }}</strong>
            <span>{{ plan.count }}</span>
          </button>
        </div>

        <label v-else-if="mapFilterMode === 'country'" class="field travel-map-filter-select">
          <span class="field__label">국가</span>
          <select v-model="selectedCountryKey">
            <option value="all">전체 국가</option>
            <option v-for="country in countryFilterOptions" :key="country.key" :value="country.key">
              {{ country.label }} ({{ country.count }})
            </option>
          </select>
        </label>

        <label v-else-if="mapFilterMode === 'region'" class="field travel-map-filter-select">
          <span class="field__label">지역</span>
          <select v-model="selectedRegionKey">
            <option value="all">전체 지역</option>
            <option v-for="region in regionFilterOptions" :key="region.key" :value="region.key">
              {{ region.label }} ({{ region.count }})
            </option>
          </select>
        </label>
      </div>
    </section>

    <section class="panel panel--map-fill travel-overview-map-panel">
      <div class="panel__header">
        <div>
          <h2>사진 지도</h2>
          <p>클러스터는 한 번만 눌러도 세부 사진이 열리고, 사진을 누르면 원본 보기로 이어집니다.</p>
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

      <p v-if="overviewErrorMessage" class="panel__empty">{{ overviewErrorMessage }}</p>
      <p v-else-if="isLoading" class="panel__empty">사진 지도 데이터를 불러오는 중입니다...</p>
      <TravelMyMapClusterPanel
        v-else
        :photo-clusters="visiblePhotoClusters"
        :photo-pins="visiblePhotoPins"
        :markers="visibleMarkers"
        :routes="visibleRoutes"
        :active="props.active"
        :display-mode="viewMode"
        :selected-cluster-id="selectedClusterSummary?.id ?? null"
        :selected-photo-id="selectedPhotoId ?? null"
        :selected-marker-id="selectedMarkerId ?? null"
        :fit-request-key="mapFitRequestKey"
        @select-cluster="handleSelectCluster"
        @select-marker="handleSelectMarker"
        @select-photo-pin="handleSelectPhotoPin"
        @preview-cluster="handlePreviewClusterFromMap"
        @fullscreen-change="handleMapFullscreenChange"
        @clear-selection="clearSelection"
      >
        <template #fullscreen-overlay="{ isFullscreen }">
          <TravelMyMapInspectorPanels
            v-if="isFullscreen && shouldShowFullscreenInspector"
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
            :closable="true"
            @select-photo="handleSelectPhoto"
            @open-photo="(photo) => openPhotoLightbox(photo, { scope: LIGHTBOX_SCOPE_CLUSTER })"
            @set-representative="handleUpdateRepresentative"
            @load-more="handleLoadMoreClusterPhotos"
            @clear="clearSelection"
          />
          <TravelPhotoLightbox
            v-if="isFullscreen && lightboxPhoto"
            :photo="lightboxPhoto"
            :photos="lightboxPhotos"
            :current-photo-id="lightboxPhoto?.id ?? null"
            :show-representative-action="lightboxScope === LIGHTBOX_SCOPE_CLUSTER"
            :representative-media-id="selectedClusterDetail?.representativeMediaId ?? null"
            :is-representative-saving="isRepresentativeSaving"
            :representative-updating-id="representativeUpdatingId"
            @close="lightboxPhoto = null"
            @select-photo="handleSelectLightboxPhoto"
            @set-representative="handleUpdateRepresentative"
          />
        </template>
      </TravelMyMapClusterPanel>
    </section>

    <section v-if="detailErrorMessage && !selectedClusterDetail" class="panel">
      <p class="panel__empty">{{ detailErrorMessage }}</p>
    </section>

    <section class="panel travel-route-plan-panel">
      <div class="panel__header travel-my-map-header">
        <div>
          <h2>여행별 GPX 경로</h2>
        </div>
        <div class="travel-my-map-header__actions">
          <span class="panel__badge">{{ routePlanGroups.length }}개 여행 · {{ visibleRoutes.length }}개 경로</span>
          <button class="button button--secondary" type="button" @click="emit('open-routes')">경로 관리</button>
        </div>
      </div>

      <div v-if="routePlanGroups.length" class="travel-route-plan-list">
        <article
          v-for="group in routePlanGroups"
          :key="group.key"
          class="travel-route-plan-card"
          :class="{ 'is-expanded': isRoutePlanGroupExpanded(group) }"
        >
          <div class="travel-route-plan-card__summary">
            <div class="travel-route-plan-card__title">
              <span class="chip chip--neutral">여행</span>
              <h3>{{ group.planName }}</h3>
              <p>{{ group.dateRange }}</p>
            </div>
            <div class="travel-route-plan-card__stats">
              <span>{{ group.routes.length }}개 경로</span>
              <span>{{ group.totalDistanceKm.toFixed(2) }}km</span>
              <span v-if="group.totalSteps">{{ Math.round(group.totalSteps).toLocaleString('ko-KR') }}걸음</span>
            </div>
            <button class="button button--secondary" type="button" @click="toggleRoutePlanGroup(group)">
              {{ isRoutePlanGroupExpanded(group) ? '접기' : `${group.planName} 자세히 보기` }}
            </button>
          </div>

          <div v-if="isRoutePlanGroupExpanded(group)" class="sheet-table-wrap travel-route-plan-card__details">
            <table class="sheet-table">
              <thead>
                <tr>
                  <th>날짜</th>
                  <th>제목</th>
                  <th>요약</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="route in group.routes" :key="route.id">
                  <td>{{ formatDate(route.routeDate) }}</td>
                  <td>{{ route.title || '이동 경로' }}</td>
                  <td>{{ routeSummary(route) || '-' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </article>
      </div>

      <p v-else class="panel__empty">현재 보기 기준에 맞는 경로가 없습니다.</p>
    </section>
    <div v-if="shareDialog.open" class="travel-modal travel-map-share-modal" @click.self="closeShareDialog">
      <div class="travel-modal__dialog travel-map-share-modal__dialog" role="dialog" aria-modal="true" aria-labelledby="travel-map-share-title">
        <div class="travel-modal__header">
          <div>
            <span class="panel__eyebrow">PUBLIC MAP SHARE</span>
            <h2 id="travel-map-share-title">여행 지도 공유</h2>
            <p>공개 URL을 가진 사람은 로그인 없이 선택한 여행 지도를 읽기 전용으로 볼 수 있습니다.</p>
          </div>
          <button class="button button--ghost" type="button" @click="closeShareDialog">닫기</button>
        </div>

        <div class="travel-modal__body travel-map-share-modal__body">
          <label class="field">
            <span class="field__label">공유 제목</span>
            <input v-model="shareDialog.title" type="text" placeholder="예: 2026 외가 가족 여행 공유 지도" />
          </label>

          <section class="travel-map-share-section">
            <div class="travel-map-share-section__header">
              <h3>공유할 여행</h3>
              <span>{{ shareDialog.selectedPlanIds.length }}개 선택</span>
            </div>
            <div class="travel-map-share-chip-grid">
              <button
                v-for="plan in sharePlanOptions"
                :key="plan.planId"
                class="travel-map-share-chip"
                :class="{ 'is-active': shareSelectedPlanIdSet.has(String(plan.planId)) }"
                type="button"
                @click="toggleSharePlan(plan.planId)"
              >
                <strong>{{ plan.label }}</strong>
                <span>{{ plan.count }}개 항목</span>
              </button>
            </div>
          </section>

          <section class="travel-map-share-section">
            <div class="travel-map-share-section__header">
              <h3>공유에서 제외할 항목</h3>
              <span>장소 {{ shareDialog.excludedRecordIds.length }} · 사진 {{ shareDialog.excludedMediaIds.length }} · 경로 {{ shareDialog.excludedRouteIds.length }}</span>
            </div>
            <div class="travel-map-share-exclusion-grid">
              <div class="travel-map-share-exclusion-list">
                <strong>장소/핀</strong>
                <button
                  v-for="marker in shareCandidateMarkers"
                  :key="marker.id"
                  class="travel-map-share-exclusion-item"
                  :class="{ 'is-excluded': shareExcludedRecordIdSet.has(String(marker.id)) }"
                  type="button"
                  @click="toggleId('excludedRecordIds', marker.id)"
                >
                  <span>{{ marker.title || marker.placeName || '장소' }}</span>
                  <small>{{ marker.planName }} · {{ formatDate(marker.memoryDate) }}</small>
                </button>
                <p v-if="!shareCandidateMarkers.length" class="panel__empty">선택한 여행에 장소 핀이 없습니다.</p>
              </div>

              <div class="travel-map-share-exclusion-list">
                <strong>사진</strong>
                <button
                  v-for="pin in shareCandidatePhotoPins"
                  :key="pin.mediaId"
                  class="travel-map-share-exclusion-item"
                  :class="{ 'is-excluded': shareExcludedMediaIdSet.has(String(pin.mediaId)) }"
                  type="button"
                  @click="toggleId('excludedMediaIds', pin.mediaId)"
                >
                  <span>{{ pin.title || pin.placeName || '사진' }}</span>
                  <small>{{ pin.planName }} · {{ formatDate(pin.memoryDate) }}</small>
                </button>
                <p v-if="!shareCandidatePhotoPins.length" class="panel__empty">선택한 여행에 위치 사진이 없습니다.</p>
              </div>

              <div class="travel-map-share-exclusion-list">
                <strong>경로</strong>
                <button
                  v-for="route in shareCandidateRoutes"
                  :key="route.id"
                  class="travel-map-share-exclusion-item"
                  :class="{ 'is-excluded': shareExcludedRouteIdSet.has(String(route.id)) }"
                  type="button"
                  @click="toggleId('excludedRouteIds', route.id)"
                >
                  <span>{{ route.title || '이동 경로' }}</span>
                  <small>{{ route.planName }} · {{ formatDate(route.routeDate) }}</small>
                </button>
                <p v-if="!shareCandidateRoutes.length" class="panel__empty">선택한 여행에 경로가 없습니다.</p>
              </div>
            </div>
          </section>

          <div v-if="shareDialog.generatedUrl" class="travel-map-share-result">
            <strong>공유 URL</strong>
            <input :value="shareDialog.generatedUrl" readonly />
            <div class="travel-map-share-result__actions">
              <button class="button button--secondary" type="button" @click="copyShareUrl">복사</button>
              <a class="button button--primary" :href="shareDialog.generatedUrl" target="_blank" rel="noopener noreferrer">열기</a>
            </div>
          </div>
          <p v-if="shareDialog.error" class="form-error">{{ shareDialog.error }}</p>
        </div>

        <div class="travel-modal__footer">
          <button class="button button--ghost" type="button" @click="closeShareDialog">취소</button>
          <button class="button button--primary" type="button" :disabled="shareDialog.saving || !shareDialog.selectedPlanIds.length" @click="createShareLink">
            {{ shareDialog.saving ? '생성 중...' : '공유 URL 생성' }}
          </button>
        </div>
      </div>
    </div>
    <TravelPhotoLightbox
      v-if="!isMapFullscreen && lightboxPhoto"
      :photo="lightboxPhoto"
      :photos="lightboxPhotos"
      :current-photo-id="lightboxPhoto?.id ?? null"
      :show-representative-action="lightboxScope === LIGHTBOX_SCOPE_CLUSTER"
      :representative-media-id="selectedClusterDetail?.representativeMediaId ?? null"
      :is-representative-saving="isRepresentativeSaving"
      :representative-updating-id="representativeUpdatingId"
      @close="lightboxPhoto = null"
      @select-photo="handleSelectLightboxPhoto"
      @set-representative="handleUpdateRepresentative"
    />
  </div>
</template>
