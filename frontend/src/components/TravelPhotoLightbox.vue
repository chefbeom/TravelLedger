<script setup>
import { computed, onBeforeUnmount, onMounted } from 'vue'
import { formatDateTime } from '../lib/uiFormat'

const props = defineProps({
  photo: {
    type: Object,
    default: null,
  },
})

const emit = defineEmits(['close'])

const locationLabel = computed(() =>
  [props.photo?.country, props.photo?.region, props.photo?.placeName].filter(Boolean).join(' / ') || '위치 정보 없음',
)

function handleKeydown(event) {
  if (event.key === 'Escape') {
    emit('close')
  }
}

onMounted(() => {
  window.addEventListener('keydown', handleKeydown)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', handleKeydown)
})
</script>

<template>
  <div v-if="photo" class="travel-modal travel-modal--lightbox" @click.self="emit('close')">
    <div class="travel-modal__dialog travel-lightbox">
      <div class="travel-modal__header">
        <div>
          <h2>{{ photo.title || photo.originalFileName || '사진 보기' }}</h2>
          <p>{{ formatDateTime(photo.expenseDate, photo.expenseTime) }}</p>
        </div>
        <button class="button button--ghost" type="button" @click="emit('close')">닫기</button>
      </div>

      <div class="travel-lightbox__body">
        <img
          class="travel-lightbox__image"
          :src="photo.contentUrl"
          :alt="photo.title || photo.originalFileName || 'travel photo'"
        />
      </div>

      <div class="travel-lightbox__meta">
        <strong>{{ photo.caption || photo.originalFileName || '사진' }}</strong>
        <small>{{ locationLabel }}</small>
        <small v-if="photo.uploadedBy">업로드 {{ photo.uploadedBy }}</small>
      </div>
    </div>
  </div>
</template>
