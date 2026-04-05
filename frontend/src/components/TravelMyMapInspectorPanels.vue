<script setup>
import { buildThumbnailUrl, THUMBNAIL_VARIANTS } from '../lib/mediaPreview'
import { formatDateTime } from '../lib/uiFormat'

const props = defineProps({
  summary: {
    type: Object,
    default: null,
  },
  detail: {
    type: Object,
    default: null,
  },
  selectedPhoto: {
    type: Object,
    default: null,
  },
  selectedPhotoId: {
    type: [String, Number],
    default: null,
  },
  photos: {
    type: Array,
    default: () => [],
  },
  isDetailLoading: {
    type: Boolean,
    default: false,
  },
  isRepresentativeSaving: {
    type: Boolean,
    default: false,
  },
  representativeUpdatingId: {
    type: [String, Number],
    default: null,
  },
  isLoadingMore: {
    type: Boolean,
    default: false,
  },
  canLoadMore: {
    type: Boolean,
    default: false,
  },
  totalPhotoCount: {
    type: Number,
    default: 0,
  },
  loadedPhotoCount: {
    type: Number,
    default: 0,
  },
  clusterLocationLabel: {
    type: String,
    default: '',
  },
  selectedPhotoLocationLabel: {
    type: String,
    default: '',
  },
  selectedPhotoGpsLabel: {
    type: String,
    default: '',
  },
  fullscreen: {
    type: Boolean,
    default: false,
  },
  closable: {
    type: Boolean,
    default: false,
  },
})

const emit = defineEmits(['select-photo', 'open-photo', 'set-representative', 'load-more', 'clear'])

function isSelectedPhoto(photo) {
  return String(photo?.id ?? '') === String(props.selectedPhotoId ?? '')
}

function handleSelectPhoto(photo) {
  emit('select-photo', photo)
}

function handleOpenPhoto(photo) {
  emit('open-photo', photo)
}

function handleSetRepresentative(photo) {
  emit('set-representative', photo)
}

function handleLoadMore() {
  emit('load-more')
}

function handleClear() {
  emit('clear')
}
</script>

<template>
  <div class="travel-map-inspector" :class="{ 'travel-map-inspector--fullscreen': fullscreen }">
    <div v-if="closable" class="travel-map-inspector__dismiss">
      <button class="button button--ghost" type="button" @click="handleClear">선택 닫기</button>
    </div>

    <section class="panel travel-map-inspector__cluster">
      <div class="panel__header">
        <div>
          <h2>선택된 클러스터</h2>
          <p>대표 사진을 확인하고, 같은 클러스터 사진을 보면서 대표 이미지를 변경할 수 있습니다.</p>
        </div>
        <span class="panel__badge">
          {{ detail ? `${detail.photoCount}장` : '선택 대기' }}
        </span>
      </div>

      <div v-if="summary" class="travel-overview-place-list">
        <article class="travel-overview-place-card travel-cluster-summary-card">
          <button
            v-if="summary.representativePhotoUrl"
            class="travel-photo-preview-button"
            type="button"
            @click="handleOpenPhoto(detail?.representativePhoto || selectedPhoto || photos[0])"
          >
            <img
              :src="buildThumbnailUrl(summary.representativePhotoUrl, THUMBNAIL_VARIANTS.preview)"
              :alt="summary.title || summary.placeName || '대표 사진'"
              class="travel-media-thumb"
              loading="eager"
              decoding="async"
            />
          </button>
          <div class="travel-media-tags">
            <span class="chip chip--neutral">{{ summary.category || '사진' }}</span>
            <span class="chip chip--neutral">사진 {{ summary.photoCount }}장</span>
            <span class="chip chip--neutral">기록 {{ summary.memoryCount }}건</span>
          </div>
          <strong>{{ summary.title || summary.placeName || '대표 사진 클러스터' }}</strong>
          <small>{{ clusterLocationLabel || '위치 정보 없음' }}</small>
          <small>{{ formatDateTime(summary.memoryDate, summary.memoryTime) }}</small>
          <small>최대 거리 {{ Number(summary.maxDistanceMeters || 0).toFixed(1) }}m</small>
          <small v-if="summary.representativeOverride">사용자 지정 대표 사진이 적용되어 있습니다.</small>
        </article>
      </div>

      <div v-if="isDetailLoading" class="panel__empty">클러스터 상세 사진을 불러오는 중입니다...</div>

      <template v-else-if="detail">
        <div v-if="photos.length" class="travel-map-inspector__gallery-body">
          <div class="travel-map-inspector__cluster-copy">
            <strong>같은 클러스터 사진</strong>
            <small>
              {{ photos.length ? `${loadedPhotoCount}/${totalPhotoCount || photos.length}장 로드됨` : '사진 없음' }}
            </small>
          </div>

          <div class="travel-map-inspector__cluster-grid-wrap">
            <div class="travel-map-inspector__cluster-grid">
              <article
                v-for="(photo, index) in photos"
                :key="photo.id"
                class="travel-media-card travel-media-card--cluster-tile"
                :class="{ 'travel-media-card--selected': isSelectedPhoto(photo) }"
              >
                <button class="travel-photo-card-button" type="button" @click="handleSelectPhoto(photo)">
                  <img
                    :src="buildThumbnailUrl(photo.contentUrl, THUMBNAIL_VARIANTS.preview)"
                    :alt="photo.originalFileName || '여행 사진'"
                    class="travel-media-thumb travel-media-thumb--cluster-tile"
                    :loading="index < 4 ? 'eager' : 'lazy'"
                    :fetchpriority="index < 4 ? 'high' : 'auto'"
                    decoding="async"
                  />
                </button>
                <div class="travel-media-tags travel-media-tags--cluster-tile">
                  <span class="chip chip--neutral" v-if="String(photo.id) === String(detail.representativeMediaId)">대표</span>
                  <span class="chip chip--neutral" v-if="isSelectedPhoto(photo)">선택됨</span>
                  <span class="chip chip--neutral" v-if="photo.representativeOverride">사용자 지정</span>
                </div>
                <div class="travel-media-copy travel-media-copy--cluster-tile">
                  <strong>{{ photo.caption || photo.originalFileName || '사진' }}</strong>
                  <small>{{ formatDateTime(photo.expenseDate, photo.expenseTime) }}</small>
                </div>
                <div v-if="isSelectedPhoto(photo)" class="travel-media-actions travel-media-actions--cluster-tile">
                  <button class="button button--ghost" type="button" @click="handleOpenPhoto(photo)">크게 보기</button>
                  <button
                    class="button button--primary"
                    type="button"
                    :disabled="isRepresentativeSaving || String(photo.id) === String(detail.representativeMediaId)"
                    @click="handleSetRepresentative(photo)"
                  >
                    {{
                      representativeUpdatingId === photo.id
                        ? '변경 중...'
                        : String(photo.id) === String(detail.representativeMediaId)
                          ? '현재 대표 사진'
                          : '대표 지정'
                    }}
                  </button>
                </div>
              </article>
            </div>
          </div>

          <div v-if="canLoadMore" class="travel-map-inspector__load-more">
            <button class="button button--ghost" type="button" :disabled="isLoadingMore" @click="handleLoadMore">
              {{ isLoadingMore ? '사진 더 불러오는 중...' : '사진 더 불러오기' }}
            </button>
          </div>
        </div>

        <p v-else class="panel__empty">선택한 클러스터의 사진이 여기에 표시됩니다.</p>
      </template>

      <p v-else class="panel__empty">지도에서 사진 클러스터를 눌러 선택해 주세요.</p>
    </section>
  </div>
</template>
