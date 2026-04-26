<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { buildThumbnailUrl, THUMBNAIL_VARIANTS } from '../lib/mediaPreview'
import { formatDate, formatDateTime } from '../lib/uiFormat'
import TravelMiniLocationMap from './TravelMiniLocationMap.vue'

const props = defineProps({
  portfolio: {
    type: Object,
    default: null,
  },
  plans: {
    type: Array,
    default: () => [],
  },
  isLoading: {
    type: Boolean,
    default: false,
  },
})

const emit = defineEmits(['open-memory-editor'])

const filters = reactive({
  planId: '',
  country: '',
  region: '',
  placeName: '',
  search: '',
  sort: 'newest',
})

const selectedPhoto = ref(null)
const failedDetailImageIds = ref(new Set())

const planNameById = computed(() => {
  const bucket = new Map()
  ;(props.plans ?? []).forEach((plan) => {
    bucket.set(String(plan.id), plan.name || '이름 없는 여행')
  })
  return bucket
})

const planOptions = computed(() =>
  (props.plans ?? [])
    .map((plan) => ({
      id: String(plan.id),
      name: plan.name || '이름 없는 여행',
    }))
    .sort((left, right) => left.name.localeCompare(right.name, 'ko')),
)

function isPhotoMedia(item) {
  const mediaType = String(item?.mediaType || '').toUpperCase()
  const contentType = String(item?.contentType || '').toLowerCase()
  if (mediaType) {
    return mediaType === 'PHOTO'
  }
  return contentType.startsWith('image/')
}

function normalizeDate(value) {
  return String(value || '').slice(0, 10)
}

function normalizeTime(value) {
  return String(value || '').slice(0, 8)
}

function uploadedDate(value) {
  return normalizeDate(value)
}

function uploadedTime(value) {
  const normalized = String(value || '')
  if (normalized.includes('T')) {
    return normalizeTime(normalized.split('T')[1])
  }
  return normalizeTime(normalized)
}

function resolvePhotoDate(item) {
  return normalizeDate(item.expenseDate) || uploadedDate(item.uploadedAt)
}

function resolvePhotoTime(item) {
  return normalizeTime(item.expenseTime) || uploadedTime(item.uploadedAt)
}

function resolvePhotoTitle(item) {
  return item.title || item.caption || item.originalFileName || '여행 사진'
}

function resolvePlanName(item) {
  return item.planName || planNameById.value.get(String(item.planId)) || '여행 미지정'
}

function resolveLocationLabel(item) {
  return [item.country, item.region, item.placeName].filter(Boolean).join(' / ') || '위치 미지정'
}

function resolveCoordinate(item, primaryKey, fallbackKey) {
  const primary = Number(item?.[primaryKey])
  if (Number.isFinite(primary)) {
    return primary
  }
  const fallback = Number(item?.[fallbackKey])
  return Number.isFinite(fallback) ? fallback : null
}

function uniqueSortedOptions(items, accessor) {
  return [...new Set(items.map(accessor).map((value) => String(value || '').trim()).filter(Boolean))]
    .sort((left, right) => left.localeCompare(right, 'ko'))
}

const allPhotos = computed(() =>
  (props.portfolio?.mediaItems ?? [])
    .filter((item) => isPhotoMedia(item) && item.contentUrl)
    .map((item) => ({
      ...item,
      planId: item.planId == null ? '' : String(item.planId),
      title: resolvePhotoTitle(item),
      expenseDate: resolvePhotoDate(item),
      expenseTime: resolvePhotoTime(item),
      displayDate: resolvePhotoDate(item),
      displayTime: resolvePhotoTime(item),
      displayTitle: resolvePhotoTitle(item),
      displayPlanName: resolvePlanName(item),
      locationLabel: resolveLocationLabel(item),
      mapLatitude: resolveCoordinate(item, 'latitude', 'gpsLatitude'),
      mapLongitude: resolveCoordinate(item, 'longitude', 'gpsLongitude'),
      sortKey: [
        resolvePhotoDate(item) || '0000-00-00',
        resolvePhotoTime(item) || '00:00:00',
        item.uploadedAt || '',
        String(item.id || '').padStart(12, '0'),
      ].join(' '),
    })),
)

const photosAfterPlanFilter = computed(() =>
  allPhotos.value.filter((item) => !filters.planId || String(item.planId) === String(filters.planId)),
)

const photosAfterCountryFilter = computed(() =>
  photosAfterPlanFilter.value.filter((item) => !filters.country || item.country === filters.country),
)

const photosAfterRegionFilter = computed(() =>
  photosAfterCountryFilter.value.filter((item) => !filters.region || item.region === filters.region),
)

const countryOptions = computed(() => uniqueSortedOptions(photosAfterPlanFilter.value, (item) => item.country))
const regionOptions = computed(() => uniqueSortedOptions(photosAfterCountryFilter.value, (item) => item.region))
const placeOptions = computed(() => uniqueSortedOptions(photosAfterRegionFilter.value, (item) => item.placeName))

const filteredPhotos = computed(() => {
  const searchText = filters.search.trim().toLowerCase()
  const items = allPhotos.value.filter((item) => {
    if (filters.planId && String(item.planId) !== String(filters.planId)) return false
    if (filters.country && item.country !== filters.country) return false
    if (filters.region && item.region !== filters.region) return false
    if (filters.placeName && item.placeName !== filters.placeName) return false

    if (!searchText) return true
    return [
      item.displayTitle,
      item.caption,
      item.originalFileName,
      item.displayPlanName,
      item.country,
      item.region,
      item.placeName,
    ]
      .filter(Boolean)
      .some((value) => String(value).toLowerCase().includes(searchText))
  })

  return items.sort((left, right) => (
    filters.sort === 'oldest'
      ? left.sortKey.localeCompare(right.sortKey)
      : right.sortKey.localeCompare(left.sortKey)
  ))
})

const groupedPhotos = computed(() => {
  const bucket = new Map()
  filteredPhotos.value.forEach((item) => {
    const key = item.displayDate || 'unknown'
    const label = item.displayDate ? formatDate(item.displayDate) : '날짜 미지정'
    const current = bucket.get(key) ?? { key, label, items: [] }
    current.items.push(item)
    bucket.set(key, current)
  })
  return [...bucket.values()]
})

const activeFilterCount = computed(() =>
  ['planId', 'country', 'region', 'placeName', 'search'].reduce((total, key) => (
    filters[key] ? total + 1 : total
  ), 0),
)

const photoCountLabel = computed(() => `${filteredPhotos.value.length} / ${allPhotos.value.length}장`)

const selectedPhotoIndex = computed(() => {
  if (!selectedPhoto.value?.id) {
    return -1
  }
  return filteredPhotos.value.findIndex((item) => String(item.id) === String(selectedPhoto.value.id))
})

const previousPhoto = computed(() => {
  if (selectedPhotoIndex.value <= 0) {
    return null
  }
  return filteredPhotos.value[selectedPhotoIndex.value - 1] ?? null
})

const nextPhoto = computed(() => {
  if (selectedPhotoIndex.value < 0 || selectedPhotoIndex.value >= filteredPhotos.value.length - 1) {
    return null
  }
  return filteredPhotos.value[selectedPhotoIndex.value + 1] ?? null
})

function thumbnailUrl(photo) {
  return buildThumbnailUrl(photo.contentUrl, THUMBNAIL_VARIANTS.preview)
}

function detailImageUrl(photo) {
  const imageKey = String(photo?.id ?? photo?.contentUrl ?? '')
  if (failedDetailImageIds.value.has(imageKey)) {
    return photo.contentUrl
  }
  return buildThumbnailUrl(photo.contentUrl, THUMBNAIL_VARIANTS.detail)
}

function handleDetailImageError(photo) {
  if (!photo?.contentUrl) {
    return
  }

  const imageKey = String(photo.id ?? photo.contentUrl)
  if (failedDetailImageIds.value.has(imageKey)) {
    return
  }

  const nextFailedIds = new Set(failedDetailImageIds.value)
  nextFailedIds.add(imageKey)
  failedDetailImageIds.value = nextFailedIds
}

function openPhoto(photo) {
  if (!photo?.contentUrl) return
  selectedPhoto.value = photo
}

function closePhoto() {
  selectedPhoto.value = null
}

function selectPhoto(photo) {
  if (!photo?.contentUrl) return
  selectedPhoto.value = photo
}

function selectPreviousPhoto() {
  if (previousPhoto.value) {
    selectPhoto(previousPhoto.value)
  }
}

function selectNextPhoto() {
  if (nextPhoto.value) {
    selectPhoto(nextPhoto.value)
  }
}

function clearFilters() {
  filters.planId = ''
  filters.country = ''
  filters.region = ''
  filters.placeName = ''
  filters.search = ''
}

function requestOpenMemoryEditor(photo) {
  if (!photo?.recordId || photo.recordType !== 'MEMORY') return
  emit('open-memory-editor', {
    memoryId: photo.recordId,
    planId: photo.planId,
  })
  closePhoto()
}

function handleModalKeydown(event) {
  if (!selectedPhoto.value) {
    return
  }

  if (event.key === 'Escape') {
    event.preventDefault()
    event.stopPropagation()
    closePhoto()
    return
  }

  if (event.key === 'ArrowLeft') {
    event.preventDefault()
    selectPreviousPhoto()
    return
  }

  if (event.key === 'ArrowRight') {
    event.preventDefault()
    selectNextPhoto()
  }
}

onMounted(() => {
  window.addEventListener('keydown', handleModalKeydown, { capture: true })
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', handleModalKeydown, { capture: true })
})

watch(
  () => filters.planId,
  () => {
    filters.country = ''
    filters.region = ''
    filters.placeName = ''
  },
)

watch(
  () => filters.country,
  () => {
    filters.region = ''
    filters.placeName = ''
  },
)

watch(
  () => filters.region,
  () => {
    filters.placeName = ''
  },
)

watch(
  () => filteredPhotos.value.map((item) => String(item.id)).join('|'),
  () => {
    if (selectedPhoto.value && !filteredPhotos.value.some((item) => String(item.id) === String(selectedPhoto.value.id))) {
      selectedPhoto.value = null
    }
  },
)
</script>

<template>
  <div class="workspace-stack travel-my-photos">
    <section class="panel travel-my-photos__hero">
      <div class="panel__header">
        <div>
          <h2>내 사진</h2>
          <p>지금까지 업로드한 여행 사진을 날짜별로 모아 보고, 여행과 위치 기준으로 빠르게 좁혀봅니다.</p>
        </div>
        <span class="panel__badge">{{ photoCountLabel }}</span>
      </div>

      <div class="travel-my-photos__filters">
        <label class="field">
          <span class="field__label">여행</span>
          <select v-model="filters.planId">
            <option value="">전체 여행</option>
            <option v-for="plan in planOptions" :key="plan.id" :value="plan.id">{{ plan.name }}</option>
          </select>
        </label>
        <label class="field">
          <span class="field__label">국가</span>
          <select v-model="filters.country">
            <option value="">전체 국가</option>
            <option v-for="country in countryOptions" :key="country" :value="country">{{ country }}</option>
          </select>
        </label>
        <label class="field">
          <span class="field__label">지역</span>
          <select v-model="filters.region">
            <option value="">전체 지역</option>
            <option v-for="region in regionOptions" :key="region" :value="region">{{ region }}</option>
          </select>
        </label>
        <label class="field">
          <span class="field__label">장소</span>
          <select v-model="filters.placeName">
            <option value="">전체 장소</option>
            <option v-for="place in placeOptions" :key="place" :value="place">{{ place }}</option>
          </select>
        </label>
        <label class="field travel-my-photos__search">
          <span class="field__label">검색</span>
          <input v-model="filters.search" type="search" placeholder="제목, 파일명, 위치" />
        </label>
        <label class="field">
          <span class="field__label">정렬</span>
          <select v-model="filters.sort">
            <option value="newest">최신순</option>
            <option value="oldest">오래된순</option>
          </select>
        </label>
      </div>

      <div class="travel-my-photos__filter-footer">
        <small>{{ activeFilterCount ? `${activeFilterCount}개 조건 적용 중` : '필터 없음' }}</small>
        <button class="button button--ghost" type="button" :disabled="!activeFilterCount" @click="clearFilters">필터 초기화</button>
      </div>
    </section>

    <section class="panel travel-my-photos__gallery-panel">
      <div class="panel__header">
        <div>
          <h2>사진 타임라인</h2>
          <p>목록에는 사진만 표시하고, 선택한 사진의 세부 정보는 아래 상세 보기에서 확인합니다.</p>
        </div>
        <span class="panel__badge">{{ groupedPhotos.length }}개 날짜</span>
      </div>

      <div v-if="groupedPhotos.length" class="travel-my-photos__timeline">
        <section v-for="group in groupedPhotos" :key="group.key" class="travel-my-photos__date-group">
          <div class="travel-my-photos__date-header">
            <strong>{{ group.label }}</strong>
            <small>{{ group.items.length }}장</small>
          </div>

          <div class="travel-my-photos__grid">
            <article
              v-for="(photo, index) in group.items"
              :key="photo.id"
              class="travel-my-photos__card"
              :class="{ 'travel-my-photos__card--selected': String(selectedPhoto?.id ?? '') === String(photo.id) }"
            >
              <button class="travel-my-photos__thumb-button" type="button" @click="openPhoto(photo)">
                <img
                  :src="thumbnailUrl(photo)"
                  :alt="photo.displayTitle"
                  :loading="index < 4 ? 'eager' : 'lazy'"
                  :fetchpriority="index < 2 ? 'high' : 'auto'"
                  decoding="async"
                />
              </button>
            </article>
          </div>
        </section>
      </div>

      <p v-else-if="isLoading" class="panel__empty">사진을 불러오는 중입니다.</p>
      <p v-else class="panel__empty">조건에 맞는 여행 사진이 없습니다.</p>
    </section>

    <div v-if="selectedPhoto" class="travel-modal travel-my-photos__modal-backdrop" @click.self="closePhoto">
      <section class="travel-modal__dialog travel-my-photos__modal" role="dialog" aria-modal="true">
        <div class="travel-modal__header">
          <div>
            <h2>사진 상세</h2>
            <p>{{ formatDateTime(selectedPhoto.displayDate, selectedPhoto.displayTime) || '날짜 미지정' }}</p>
          </div>
          <button class="button button--ghost" type="button" @click="closePhoto">닫기</button>
        </div>

        <div class="travel-my-photos__modal-body">
          <div class="travel-my-photos__modal-photo-frame">
            <button
              v-if="previousPhoto"
              class="travel-lightbox__nav travel-lightbox__nav--prev"
              type="button"
              aria-label="이전 사진"
              @click="selectPreviousPhoto"
            >
              <span aria-hidden="true">&lsaquo;</span>
            </button>
            <img
              :src="detailImageUrl(selectedPhoto)"
              :alt="selectedPhoto.displayTitle"
              loading="eager"
              decoding="async"
              @error="handleDetailImageError(selectedPhoto)"
            />
            <button
              v-if="nextPhoto"
              class="travel-lightbox__nav travel-lightbox__nav--next"
              type="button"
              aria-label="다음 사진"
              @click="selectNextPhoto"
            >
              <span aria-hidden="true">&rsaquo;</span>
            </button>
          </div>

          <aside class="travel-my-photos__modal-info">
            <div class="travel-my-photos__detail-copy">
              <span>{{ selectedPhoto.displayPlanName }}</span>
              <strong>{{ selectedPhoto.displayTitle }}</strong>
              <small>{{ formatDateTime(selectedPhoto.displayDate, selectedPhoto.displayTime) || '날짜 미지정' }}</small>
              <small>{{ selectedPhoto.locationLabel }}</small>
            </div>

            <TravelMiniLocationMap
              :latitude="selectedPhoto.mapLatitude"
              :longitude="selectedPhoto.mapLongitude"
              :title="selectedPhoto.locationLabel"
            />

            <div class="travel-my-photos__detail-actions">
              <button
                v-if="selectedPhoto.recordType === 'MEMORY' && selectedPhoto.recordId"
                class="button button--primary"
                type="button"
                @click="requestOpenMemoryEditor(selectedPhoto)"
              >
                기록으로 이동
              </button>
            </div>
          </aside>
        </div>
      </section>
    </div>
  </div>
</template>
