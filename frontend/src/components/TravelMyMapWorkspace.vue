<script setup>
import { computed, onMounted, ref } from 'vue'
import {
  fetchTravelMyMapOverview,
  fetchTravelMyMapPhotoCluster,
  updateTravelMyMapPhotoClusterRepresentative,
} from '../lib/api'
import { buildThumbnailUrl } from '../lib/mediaPreview'
import { formatDate, formatDateTime, safeNumber } from '../lib/uiFormat'
import TravelMyMapClusterPanel from './TravelMyMapClusterPanel.vue'
import TravelPhotoLightbox from './TravelPhotoLightbox.vue'

const props = defineProps({
  active: {
    type: Boolean,
    default: true,
  },
})

const isLoading = ref(false)
const isDetailLoading = ref(false)
const isRepresentativeSaving = ref(false)
const errorMessage = ref('')
const overview = ref(null)
const selectedClusterSummary = ref(null)
const selectedClusterDetail = ref(null)
const selectedPhotoId = ref(null)
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

async function loadClusterDetail(clusterId, preferredPhotoId = null) {
  if (!clusterId) {
    selectedClusterDetail.value = null
    selectedPhotoId.value = null
    return
  }

  isDetailLoading.value = true
  setError('')

  try {
    const detail = await fetchTravelMyMapPhotoCluster(clusterId)
    selectedClusterDetail.value = detail

    const availablePhotos = detail?.photos ?? []
    const preferredPhotoExists = preferredPhotoId != null
      && availablePhotos.some((photo) => String(photo.id) === String(preferredPhotoId))
    const hasSelectedPhoto = availablePhotos.some((photo) => String(photo.id) === String(selectedPhotoId.value))
    if (preferredPhotoExists) {
      selectedPhotoId.value = preferredPhotoId
    } else if (!hasSelectedPhoto) {
      selectedPhotoId.value = detail?.representativeMediaId ?? availablePhotos[0]?.id ?? null
    }
  } catch (error) {
    selectedClusterDetail.value = null
    selectedPhotoId.value = null
    setError(error.message)
  } finally {
    isDetailLoading.value = false
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

  selectedClusterSummary.value = cluster
  await loadClusterDetail(cluster.id)
}

async function handleSelectPhotoPin(pin) {
  if (!pin?.clusterId) {
    return
  }

  selectedPhotoId.value = pin.mediaId ?? null
  const matchingCluster = photoClusters.value.find((cluster) => String(cluster.id) === String(pin.clusterId))
  if (matchingCluster) {
    selectedClusterSummary.value = matchingCluster
  }

  await loadClusterDetail(pin.clusterId, pin.mediaId ?? null)
}

function handleSelectPhoto(photo) {
  if (!photo?.id) {
    return
  }

  if (String(selectedPhotoId.value) === String(photo.id)) {
    lightboxPhoto.value = photo
    return
  }

  selectedPhotoId.value = photo.id
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
const routes = computed(() => overview.value?.routes ?? [])

const selectedClusterPhotos = computed(() => {
  const clusterDetail = selectedClusterDetail.value
  if (!clusterDetail?.photos?.length) {
    return []
  }

  return [...clusterDetail.photos].sort((left, right) => {
    const leftRepresentative = String(left.id) === String(clusterDetail.representativeMediaId) ? 0 : 1
    const rightRepresentative = String(right.id) === String(clusterDetail.representativeMediaId) ? 0 : 1
    if (leftRepresentative !== rightRepresentative) {
      return leftRepresentative - rightRepresentative
    }

    const leftKey = `${left.expenseDate || ''} ${left.expenseTime || '99:99'} ${left.uploadedAt || ''} ${String(left.id).padStart(12, '0')}`
    const rightKey = `${right.expenseDate || ''} ${right.expenseTime || '99:99'} ${right.uploadedAt || ''} ${String(right.id).padStart(12, '0')}`
    return rightKey.localeCompare(leftKey)
  })
})

const selectedPhoto = computed(() => {
  const photos = selectedClusterPhotos.value
  if (!photos.length) {
    return null
  }

  return photos.find((photo) => String(photo.id) === String(selectedPhotoId.value)) ?? photos[0]
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
          <p>업로드된 사진의 GPS를 기준으로 가까운 사진을 묶고, 줌 단계에 따라 다시 정리해 지도 성능과 가독성을 함께 유지합니다.</p>
        </div>
        <span class="panel__badge">클러스터 {{ summary.photoClusterCount }}개</span>
      </div>

      <div class="travel-summary-grid">
        <article class="travel-stat-card">
          <span>사진 원본 핀</span>
          <strong>{{ summary.photoMarkerCount }}</strong>
          <small>서버 5m 기준으로 계산한 원본 사진 수</small>
        </article>
        <article class="travel-stat-card">
          <span>사진 클러스터</span>
          <strong>{{ summary.photoClusterCount }}</strong>
          <small>대표 사진 기준으로 지도에 표시되는 묶음</small>
        </article>
        <article class="travel-stat-card">
          <span>기록 메모리</span>
          <strong>{{ summary.markerCount }}</strong>
          <small>여행 장소 기억과 연결된 지도 기록</small>
        </article>
        <article class="travel-stat-card">
          <span>경로 거리</span>
          <strong>{{ summary.totalDistanceKm.toFixed(2) }}km</strong>
          <small>현재 저장된 이동 경로 총합</small>
        </article>
      </div>
    </section>

    <section class="panel panel--map-fill travel-overview-map-panel">
      <div class="panel__header">
        <div>
          <h2>사진 지도</h2>
          <p>낮은 줌에서는 넓게 묶고, 확대하면 세분화합니다. 군집 보기와 핀 보기를 전환해 상황에 맞게 확인할 수 있습니다.</p>
        </div>
        <div class="travel-map-mode-switch">
          <button
            class="travel-map__toolbar-button"
            :class="{ 'is-active': viewMode === 'cluster' }"
            type="button"
            @click="viewMode = 'cluster'"
          >
            군집으로 보기
          </button>
          <button
            class="travel-map__toolbar-button"
            :class="{ 'is-active': viewMode === 'pin' }"
            type="button"
            @click="viewMode = 'pin'"
          >
            핀으로 보기
          </button>
        </div>
      </div>

      <p v-if="errorMessage" class="panel__empty">{{ errorMessage }}</p>
      <p v-else-if="isLoading" class="panel__empty">사진 클러스터 지도를 불러오는 중입니다...</p>
      <TravelMyMapClusterPanel
        v-else
        :photo-clusters="photoClusters"
        :photo-pins="photoPins"
        :routes="routes"
        :active="props.active"
        :display-mode="viewMode"
        :selected-cluster-id="selectedClusterSummary?.id ?? null"
        :selected-photo-id="selectedPhotoId ?? null"
        @select-cluster="handleSelectCluster"
        @select-photo-pin="handleSelectPhotoPin"
      />
    </section>

    <div class="content-grid content-grid--travel">
      <section class="panel">
        <div class="panel__header">
          <div>
            <h2>선택된 클러스터</h2>
            <p>대표 사진, 포함 사진 수, 묶음 범위를 확인하고 대표 사진을 바꿀 수 있습니다.</p>
          </div>
          <span class="panel__badge">
            {{ selectedClusterDetail ? `${selectedClusterDetail.photoCount}장` : '선택 대기' }}
          </span>
        </div>

        <div v-if="selectedClusterSummary" class="travel-overview-place-list">
          <article class="travel-overview-place-card travel-cluster-summary-card">
            <img
              v-if="selectedClusterSummary.representativePhotoUrl"
              :src="buildThumbnailUrl(selectedClusterSummary.representativePhotoUrl, 480)"
              :alt="selectedClusterSummary.title || selectedClusterSummary.placeName || '대표 사진'"
              class="travel-media-thumb"
              loading="eager"
              decoding="async"
            />
            <div class="travel-media-tags">
              <span class="chip chip--neutral">{{ selectedClusterSummary.category || '사진' }}</span>
              <span class="chip chip--neutral">사진 {{ selectedClusterSummary.photoCount }}장</span>
              <span class="chip chip--neutral">기록 {{ selectedClusterSummary.memoryCount }}건</span>
            </div>
            <strong>{{ selectedClusterSummary.title || selectedClusterSummary.placeName || '대표 사진 클러스터' }}</strong>
            <small>{{ selectedClusterLocationLabel }}</small>
            <small>{{ formatDateTime(selectedClusterSummary.memoryDate, selectedClusterSummary.memoryTime) }}</small>
            <small>최대 거리 {{ Number(selectedClusterSummary.maxDistanceMeters || 0).toFixed(1) }}m</small>
            <small v-if="selectedClusterSummary.representativeOverride">사용자 지정 대표 사진이 적용되어 있습니다.</small>
          </article>
        </div>
        <div v-if="isDetailLoading" class="panel__empty">클러스터 상세 사진을 불러오는 중입니다...</div>
        <p v-else-if="!selectedClusterDetail" class="panel__empty">지도에서 사진 핀을 눌러 클러스터를 선택해 주세요.</p>
      </section>

      <section class="panel">
        <div class="panel__header">
          <div>
            <h2>선택한 사진 정보</h2>
            <p>사진을 한 번 누르면 정보가 바뀌고, 같은 사진을 다시 누르면 크게 볼 수 있습니다.</p>
          </div>
          <span class="panel__badge">
            {{ selectedPhoto ? `${selectedPhoto.originalFileName || '사진'} 선택됨` : '선택 대기' }}
          </span>
        </div>

        <div v-if="selectedPhoto" class="travel-overview-place-list">
          <article class="travel-overview-place-card travel-selected-photo-card">
            <button class="travel-photo-preview-button" type="button" @click="lightboxPhoto = selectedPhoto">
              <img
                :src="buildThumbnailUrl(selectedPhoto.contentUrl, 960)"
                :alt="selectedPhoto.originalFileName || '선택한 사진'"
                class="travel-media-thumb"
                loading="eager"
                decoding="async"
              />
            </button>
            <div class="travel-media-tags">
              <span class="chip chip--neutral" v-if="String(selectedPhoto.id) === String(selectedClusterDetail?.representativeMediaId)">대표 사진</span>
              <span class="chip chip--neutral" v-if="selectedPhoto.representativeOverride">사용자 지정</span>
            </div>
            <strong>{{ selectedPhoto.caption || selectedPhoto.originalFileName || '사진' }}</strong>
            <small>{{ selectedPhotoLocationLabel }}</small>
            <small>{{ formatDateTime(selectedPhoto.expenseDate, selectedPhoto.expenseTime) }}</small>
            <small v-if="selectedPhotoGpsLabel">GPS {{ selectedPhotoGpsLabel }}</small>
            <div class="travel-media-actions">
              <button class="button button--ghost" type="button" @click="lightboxPhoto = selectedPhoto">크게 보기</button>
              <button
                class="button button--primary"
                type="button"
                :disabled="isRepresentativeSaving || String(selectedPhoto.id) === String(selectedClusterDetail?.representativeMediaId)"
                @click="handleUpdateRepresentative(selectedPhoto)"
              >
                {{
                  representativeUpdatingId === selectedPhoto.id
                    ? '대표 사진 변경 중...'
                    : String(selectedPhoto.id) === String(selectedClusterDetail?.representativeMediaId)
                      ? '현재 대표 사진'
                      : '대표 사진으로 지정'
                }}
              </button>
            </div>
          </article>
        </div>
        <p v-else class="panel__empty">아직 선택된 사진이 없습니다.</p>
      </section>
    </div>

    <section class="panel">
      <div class="panel__header">
        <div>
          <h2>클러스터 포함 사진</h2>
          <p>같은 위치 묶음에 포함된 사진을 확인하고 대표 사진을 바꿀 수 있습니다.</p>
        </div>
        <span class="panel__badge">
          {{ selectedClusterPhotos.length ? `${selectedClusterPhotos.length}장` : '선택 대기' }}
        </span>
      </div>

      <div v-if="selectedClusterPhotos.length" class="travel-media-grid travel-media-grid--gallery">
        <article
          v-for="(photo, index) in selectedClusterPhotos"
          :key="photo.id"
          class="travel-media-card"
          :class="{ 'travel-media-card--selected': String(photo.id) === String(selectedPhotoId) }"
        >
          <button class="travel-photo-card-button" type="button" @click="handleSelectPhoto(photo)">
            <img
              :src="buildThumbnailUrl(photo.contentUrl, 480)"
              :alt="photo.originalFileName || '여행 사진'"
              class="travel-media-thumb"
              :loading="index < 4 ? 'eager' : 'lazy'"
              :fetchpriority="index < 4 ? 'high' : 'auto'"
              decoding="async"
            />
          </button>
          <div class="travel-media-tags">
            <span class="chip chip--neutral" v-if="String(photo.id) === String(selectedClusterDetail?.representativeMediaId)">대표</span>
            <span class="chip chip--neutral" v-if="photo.representativeOverride">사용자 지정</span>
          </div>
          <div class="travel-media-copy">
            <strong>{{ photo.caption || photo.originalFileName || '사진' }}</strong>
            <small>{{ formatDateTime(photo.expenseDate, photo.expenseTime) }}</small>
            <small>{{ [photo.country, photo.region, photo.placeName].filter(Boolean).join(' / ') || '위치 정보 없음' }}</small>
          </div>
          <div class="travel-media-actions">
            <button class="button button--ghost" type="button" @click="handleSelectPhoto(photo)">
              {{ String(photo.id) === String(selectedPhotoId) ? '다시 눌러 크게 보기' : '정보 보기' }}
            </button>
            <button
              class="button button--primary"
              type="button"
              :disabled="isRepresentativeSaving || String(photo.id) === String(selectedClusterDetail?.representativeMediaId)"
              @click="handleUpdateRepresentative(photo)"
            >
              {{
                representativeUpdatingId === photo.id
                  ? '변경 중...'
                  : String(photo.id) === String(selectedClusterDetail?.representativeMediaId)
                    ? '대표 사진'
                    : '대표 지정'
              }}
            </button>
          </div>
        </article>
      </div>
      <p v-else class="panel__empty">선택한 클러스터의 사진이 여기에 표시됩니다.</p>
    </section>

    <section class="panel">
      <div class="panel__header">
        <div>
          <h2>전체 경로 목록</h2>
          <p>사진 클러스터와 함께 저장된 이동 경로도 그대로 확인할 수 있습니다.</p>
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
