<script setup>
import { computed } from 'vue'
import { buildThumbnailUrl } from '../lib/mediaPreview'
import { formatDateTime } from '../lib/uiFormat'
import TravelMapPanel from './TravelMapPanel.vue'

const props = defineProps({
  travelPlan: {
    type: Object,
    default: null,
  },
  communityFeed: {
    type: Array,
    default: () => [],
  },
  communityPage: {
    type: Number,
    default: 0,
  },
  communityPageCount: {
    type: Number,
    default: 1,
  },
  communityTotal: {
    type: Number,
    default: 0,
  },
})

const emit = defineEmits(['change-community-page'])

const localSharedCount = computed(() => (props.travelPlan?.memoryRecords ?? []).filter((item) => item.sharedWithCommunity).length)
const uniqueUsers = computed(() => new Set(props.communityFeed.map((item) => item.ownerDisplayName).filter(Boolean)).size)
const uniquePlaces = computed(() =>
  new Set(props.communityFeed.map((item) => [item.country, item.region, item.placeName].filter(Boolean).join(' / ')).filter(Boolean)).size,
)

const communityMarkers = computed(() =>
  props.communityFeed
    .filter((item) => item.latitude !== null && item.latitude !== undefined && item.longitude !== null && item.longitude !== undefined)
    .map((item) => ({
      id: item.memoryId,
      planId: item.planId,
      planName: item.planName,
      colorHex: item.planColorHex || '#3182F6',
      latitude: Number(item.latitude),
      longitude: Number(item.longitude),
      country: item.country,
      region: item.region,
      placeName: item.placeName,
      title: item.title,
      visitedDate: item.memoryDate,
      visitedTime: item.memoryTime,
      uploadedBy: item.ownerDisplayName,
      photoCount: Number(item.photoCount ?? 0),
      receiptCount: 0,
      mediaItems: item.heroPhotoUrl
        ? [
            {
              contentType: 'image/jpeg',
              contentUrl: item.heroPhotoUrl,
              originalFileName: item.title || 'community-photo',
              caption: item.heroPhotoCaption || item.title || '',
            },
          ]
        : [],
    })),
)

function goToCommunityPage(page) {
  emit('change-community-page', page)
}
</script>

<template>
  <div class="workspace-stack">
    <section class="panel">
      <div class="panel__header">
        <div>
          <h2>커뮤니티 사진 피드</h2>
          <p>여러 사용자가 공유한 여행 기록을 하나의 지도와 카드 피드로 모아 확인할 수 있습니다.</p>
        </div>
        <span class="panel__badge">{{ communityFeed.length }}개 게시물</span>
      </div>

      <div class="travel-media-summary-grid">
        <article class="travel-media-summary-card">
          <span>이 여행에서 공유한 기록</span>
          <strong>{{ localSharedCount }}</strong>
          <small>{{ travelPlan ? travelPlan.name : '여행을 선택해주세요' }}</small>
        </article>
        <article class="travel-media-summary-card">
          <span>참여 사용자</span>
          <strong>{{ uniqueUsers }}</strong>
          <small>피드에 보이는 고유 사용자 수</small>
        </article>
        <article class="travel-media-summary-card">
          <span>공유 장소</span>
          <strong>{{ uniquePlaces }}</strong>
          <small>위치가 찍힌 고유 장소 수</small>
        </article>
      </div>
    </section>

    <section class="panel panel--map-fill">
      <div class="panel__header">
        <div>
          <h2>커뮤니티 지도</h2>
          <p>핀을 눌러 다른 사용자가 어디를 다녀왔는지, 어떤 사진을 올렸는지 빠르게 살펴볼 수 있습니다.</p>
        </div>
      </div>

      <TravelMapPanel
        :markers="communityMarkers"
        :selected-point="null"
        :enable-pick-location="false"
        :enable-draw-route="false"
        hint-title="공유 핀 보기"
        hint-text="축소 상태에서는 작은 핀으로 보이고, 눌렀을 때 사진과 위치 미리보기가 열립니다."
      />
    </section>

    <section class="panel">
      <div class="panel__header">
        <div>
          <h2>피드 카드</h2>
          <p>사진, 작성자, 장소, 시간, 짧은 메모를 카드 형태로 내려보면서 둘러볼 수 있습니다.</p>
        </div>
      </div>

      <div v-if="communityFeed.length" class="travel-media-grid travel-media-grid--gallery">
        <article v-for="item in communityFeed" :key="item.memoryId" class="travel-media-card">
          <img v-if="item.heroPhotoUrl" :src="buildThumbnailUrl(item.heroPhotoUrl)" :alt="item.title" class="travel-media-thumb" />
          <div v-else class="travel-media-thumb travel-media-thumb--receipt">사진 없음</div>
          <div class="travel-media-copy">
            <div class="travel-media-tags">
              <span class="chip chip--neutral">{{ item.ownerDisplayName || '알 수 없는 사용자' }}</span>
              <span class="chip chip--neutral">{{ item.planName || '여행' }}</span>
            </div>
            <strong>{{ item.title }}</strong>
            <small>{{ formatDateTime(item.memoryDate, item.memoryTime) }}</small>
            <small>{{ [item.country, item.region, item.placeName].filter(Boolean).join(' / ') || '위치 미설정' }}</small>
            <small>{{ item.memo || '아직 짧은 메모가 등록되지 않았습니다.' }}</small>
          </div>
        </article>
      </div>
      <div v-if="communityPageCount > 1" class="panel__actions">
        <button
          class="button button--ghost"
          type="button"
          :disabled="communityPage <= 0"
          @click="goToCommunityPage(communityPage - 1)"
        >
          이전
        </button>
        <span>{{ communityPage + 1 }} / {{ communityPageCount }}</span>
        <button
          class="button button--ghost"
          type="button"
          :disabled="communityPage + 1 >= communityPageCount"
          @click="goToCommunityPage(communityPage + 1)"
        >
          다음
        </button>
      </div>
      <p v-else class="panel__empty">공유된 사진 게시물이 아직 없습니다.</p>
    </section>
  </div>
</template>
