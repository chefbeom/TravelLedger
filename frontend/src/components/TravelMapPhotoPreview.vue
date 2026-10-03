<script setup>
import { computed, ref, watch } from 'vue'
import { buildThumbnailUrl, THUMBNAIL_VARIANTS } from '../lib/mediaPreview'
import { formatDate, formatTime } from '../lib/uiFormat'

const props = defineProps({
  photo: { type: Object, required: true },
  photoCount: { type: Number, default: 0 },
  memoryCount: { type: Number, default: 0 },
  compact: { type: Boolean, default: false },
})
const emit = defineEmits(['open', 'close'])
const imageFallback = ref(0)
const sourceUrl = computed(() => props.photo.representativePhotoUrl || props.photo.photoUrl || props.photo.contentUrl || '')
const imageUrl = computed(() => imageFallback.value === 1 ? sourceUrl.value : buildThumbnailUrl(sourceUrl.value, props.compact ? THUMBNAIL_VARIANTS.mini : THUMBNAIL_VARIANTS.preview))
const title = computed(() => props.photo.title || props.photo.placeName || props.photo.originalFileName || '여행 사진')
const location = computed(() => [props.photo.country, props.photo.region, props.photo.placeName].filter(Boolean).join(' / '))
const capturedAt = computed(() => [
  formatDate(props.photo.memoryDate || props.photo.expenseDate),
  formatTime(props.photo.memoryTime || props.photo.expenseTime),
].filter((value) => value && value !== '-').join(' '))
const owner = computed(() => props.photo.sharedByDisplayName || props.photo.ownerDisplayName || props.photo.uploadedBy || '')

watch(sourceUrl, () => { imageFallback.value = 0 })
</script>

<template>
  <section :class="['travel-map-preview', { 'travel-map-preview--compact': compact }]" :aria-label="compact ? '선택한 핀 정보' : '선택한 여행 사진 미리보기'">
    <header v-if="!compact" class="travel-map-preview__header">
      <span>사진 미리보기</span>
      <button class="button button--secondary" type="button" aria-label="사진 미리보기 닫기" @click="emit('close')">×</button>
    </header>
    <button class="travel-map-preview__open" type="button" :aria-label="compact ? '핀 사진 크게 보기' : '사진 크게 보기'" @click="emit('open')">
      <img v-if="sourceUrl && imageFallback < 2" class="travel-map-preview__image" :src="imageUrl" :alt="title" loading="eager" decoding="async" @error="imageFallback++" />
      <span v-else class="travel-map-preview__empty">사진 미리보기를 불러올 수 없습니다.</span>
      <span class="travel-map-preview__copy">
        <strong>{{ title }}</strong>
        <span v-if="location">{{ location }}</span>
        <span v-if="capturedAt">{{ capturedAt }}</span>
        <span v-if="owner && !compact">공유자 {{ owner }}</span>
        <span>사진 {{ photoCount }}장 / 기록 {{ memoryCount }}건</span>
        <span v-if="!compact" class="travel-map-preview__hint">사진을 누르면 크게 볼 수 있습니다.</span>
      </span>
    </button>
  </section>
</template>
