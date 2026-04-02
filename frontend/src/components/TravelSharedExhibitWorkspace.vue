<script setup>
import { computed, ref, watch } from 'vue'
import { buildThumbnailUrl } from '../lib/mediaPreview'
import { formatDate, formatDateTime } from '../lib/uiFormat'
import TravelOverviewWorkspace from './TravelOverviewWorkspace.vue'

const props = defineProps({
  exhibits: {
    type: Array,
    default: () => [],
  },
  exhibitPage: {
    type: Number,
    default: 0,
  },
  exhibitPageCount: {
    type: Number,
    default: 1,
  },
  exhibitTotal: {
    type: Number,
    default: 0,
  },
  selectedExhibitId: {
    type: [String, Number],
    default: '',
  },
  selectedExhibit: {
    type: Object,
    default: null,
  },
  isLoading: {
    type: Boolean,
    default: false,
  },
})

const emit = defineEmits(['select-exhibit', 'change-exhibit-page'])

const statusLabels = {
  PLANNED: '예정 여행',
  COMPLETED: '완성 여행',
  SAMPLE: '샘플 여행',
}

const selectedPlan = computed(() => props.selectedExhibit?.travelPlan ?? null)
const memoryById = computed(() => new Map((selectedPlan.value?.memoryRecords ?? []).map((item) => [String(item.id), item])))
const photoCards = computed(() =>
  (selectedPlan.value?.mediaItems ?? [])
    .filter((item) => item.recordType === 'MEMORY' && item.mediaType === 'PHOTO')
    .map((item) => {
      const memory = memoryById.value.get(String(item.recordId))
      return {
        id: item.id,
        title: memory?.title || item.title || memory?.placeName || '사진 기록',
        memoryDate: memory?.memoryDate || item.expenseDate,
        memoryTime: memory?.memoryTime || item.expenseTime,
        locationLabel: [memory?.country || item.country, memory?.region || item.region, memory?.placeName || item.placeName]
          .filter(Boolean)
          .join(' / ') || '위치 미설정',
        memo: memory?.memo || '',
        contentUrl: item.contentUrl,
        caption: item.caption || item.originalFileName || '',
        uploadedBy: item.uploadedBy || props.selectedExhibit?.sharedByDisplayName || '',
        uploadedAt: item.uploadedAt,
      }
    })
    .sort((left, right) => String(right.uploadedAt || '').localeCompare(String(left.uploadedAt || ''))),
)

const PHOTO_PAGE_SIZE = 10
const photoPage = ref(0)
const photoPageCount = computed(() => Math.max(Math.ceil(photoCards.value.length / PHOTO_PAGE_SIZE), 1))
const pagedPhotoCards = computed(() => {
  const start = photoPage.value * PHOTO_PAGE_SIZE
  return photoCards.value.slice(start, start + PHOTO_PAGE_SIZE)
})

function statusLabel(status) {
  return statusLabels[status] || status || '미정'
}

function isSelectedExhibit(exhibitId) {
  return String(props.selectedExhibitId || '') === String(exhibitId || '')
}

watch(
  () => props.selectedExhibitId,
  () => {
    photoPage.value = 0
  },
  { immediate: true },
)

watch(
  () => photoCards.value.length,
  () => {
    if (photoPage.value >= photoPageCount.value) {
      photoPage.value = Math.max(photoPageCount.value - 1, 0)
    }
  },
)

function goToExhibitPage(page) {
  emit('change-exhibit-page', page)
}
</script>

<template>
  <div class="workspace-stack">
    <section class="panel">
      <div class="panel__header">
        <div>
          <h2>공유 전시</h2>
          <p>다른 사용자가 완성해서 공유한 여행 로그를 읽기 전용 전시 페이지로 감상합니다.</p>
        </div>
        <span class="panel__badge">{{ exhibits.length }}개 전시</span>
      </div>

      <div v-if="exhibits.length" class="travel-plan-picker-grid">
        <button
          v-for="item in exhibits"
          :key="item.id"
          class="travel-plan-picker-card"
          :class="{ 'travel-plan-picker-card--active': isSelectedExhibit(item.id) }"
          type="button"
          @click="emit('select-exhibit', item.id)"
        >
          <strong>{{ item.planName }}</strong>
          <span>{{ item.destination || '목적지 미정' }}</span>
          <small>{{ formatDate(item.startDate) }} - {{ formatDate(item.endDate) }}</small>
          <small>{{ item.sharedByDisplayName }} ({{ item.sharedByLoginId }}) / {{ statusLabel(item.status) }}</small>
        </button>
      </div>
      <div v-if="exhibitPageCount > 1" class="panel__actions">
        <button
          class="button button--ghost"
          type="button"
          :disabled="exhibitPage <= 0"
          @click="goToExhibitPage(exhibitPage - 1)"
        >
          이전
        </button>
        <span>{{ exhibitPage + 1 }} / {{ exhibitPageCount }}</span>
        <button
          class="button button--ghost"
          type="button"
          :disabled="exhibitPage + 1 >= exhibitPageCount"
          @click="goToExhibitPage(exhibitPage + 1)"
        >
          다음
        </button>
      </div>
      <p v-else class="panel__empty">공유받은 여행 전시가 아직 없습니다.</p>
    </section>

    <section v-if="selectedExhibit" class="panel">
      <div class="panel__header">
        <div>
          <h2>{{ selectedExhibit.travelPlan.name }}</h2>
          <p>{{ selectedExhibit.sharedByDisplayName }} ({{ selectedExhibit.sharedByLoginId }}) 님이 공유한 완성 여행입니다. 이 화면은 읽기 전용입니다.</p>
        </div>
        <span class="panel__badge">{{ formatDateTime(selectedExhibit.sharedAt) }}</span>
      </div>

      <div class="travel-media-tags">
        <span class="chip chip--neutral">{{ statusLabel(selectedExhibit.travelPlan.status) }}</span>
        <span class="chip chip--neutral">기록 {{ selectedExhibit.travelPlan.memoryRecordCount }}개</span>
        <span class="chip chip--neutral">경로 {{ selectedExhibit.travelPlan.routeSegmentCount }}개</span>
        <span class="chip chip--neutral">사진 {{ selectedExhibit.travelPlan.mediaItemCount }}개</span>
      </div>
    </section>

    <TravelOverviewWorkspace v-if="selectedPlan" :travel-plan="selectedPlan" />

    <section v-if="selectedPlan" class="panel">
      <div class="panel__header">
        <div>
          <h2>전시 사진</h2>
          <p>공유된 여행 로그에 연결된 사진을 수정 없이 그대로 감상할 수 있습니다.</p>
        </div>
        <span class="panel__badge">{{ photoCards.length }}장</span>
      </div>

      <div v-if="photoCards.length" class="travel-media-grid travel-media-grid--gallery">
        <article v-for="(item, index) in pagedPhotoCards" :key="item.id" class="travel-media-card">
          <img
            v-if="item.contentUrl"
            :src="buildThumbnailUrl(item.contentUrl)"
            :alt="item.title"
            :loading="index < 2 ? 'eager' : 'lazy'"
            :fetchpriority="index < 2 ? 'high' : 'auto'"
            decoding="async"
            class="travel-media-thumb"
          />
          <div v-else class="travel-media-thumb travel-media-thumb--receipt">사진 없음</div>
          <div class="travel-media-copy">
            <div class="travel-media-tags">
              <span class="chip chip--neutral">{{ item.uploadedBy || '공유 전시' }}</span>
              <span class="chip chip--neutral">{{ item.caption || '사진' }}</span>
            </div>
            <strong>{{ item.title }}</strong>
            <small>{{ formatDateTime(item.memoryDate, item.memoryTime) }}</small>
            <small>{{ item.locationLabel }}</small>
            <small>{{ item.memo || '메모 없음' }}</small>
          </div>
          <div class="travel-media-actions">
            <a v-if="item.contentUrl" class="button button--ghost" :href="item.contentUrl" target="_blank" rel="noreferrer">사진 보기</a>
          </div>
        </article>
      </div>
      <div v-if="photoCards.length > PHOTO_PAGE_SIZE" class="panel__actions">
        <button
          class="button button--ghost"
          type="button"
          :disabled="photoPage <= 0"
          @click="photoPage -= 1"
        >
          이전
        </button>
        <span>{{ photoPage + 1 }} / {{ photoPageCount }}</span>
        <button
          class="button button--ghost"
          type="button"
          :disabled="photoPage + 1 >= photoPageCount"
          @click="photoPage += 1"
        >
          다음
        </button>
      </div>
      <p v-else class="panel__empty">공유된 사진이 아직 없습니다.</p>
    </section>

    <section v-else-if="exhibits.length && !isLoading" class="panel">
      <p class="panel__empty">위 목록에서 전시 하나를 선택해주세요.</p>
    </section>
  </div>
</template>
