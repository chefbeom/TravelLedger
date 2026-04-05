<script setup>
import { computed, onBeforeUnmount, onMounted } from 'vue'
import { formatDateTime } from '../lib/uiFormat'

const props = defineProps({
  photo: {
    type: Object,
    default: null,
  },
  photos: {
    type: Array,
    default: () => [],
  },
  currentPhotoId: {
    type: [String, Number],
    default: null,
  },
})

const emit = defineEmits(['close', 'select-photo'])

const DEFAULT_TITLE = '\uC0AC\uC9C4 \uBCF4\uAE30'
const CLOSE_LABEL = '\uB2EB\uAE30'
const DEFAULT_PHOTO_LABEL = '\uC0AC\uC9C4'
const LOCATION_EMPTY_LABEL = '\uC704\uCE58 \uC815\uBCF4 \uC5C6\uC74C'
const UPLOADED_BY_PREFIX = '\uC5C5\uB85C\uB4DC '
const PREVIOUS_PHOTO_LABEL = '\uC774\uC804 \uC0AC\uC9C4'
const NEXT_PHOTO_LABEL = '\uB2E4\uC74C \uC0AC\uC9C4'

const activePhoto = computed(() => {
  if (!props.photo) {
    return null
  }

  if (props.currentPhotoId == null) {
    return props.photo
  }

  return props.photos.find((item) => String(item?.id) === String(props.currentPhotoId)) ?? props.photo
})

const locationLabel = computed(() =>
  [activePhoto.value?.country, activePhoto.value?.region, activePhoto.value?.placeName].filter(Boolean).join(' / ') || LOCATION_EMPTY_LABEL,
)

const currentIndex = computed(() => {
  if (!activePhoto.value?.id) {
    return -1
  }

  return props.photos.findIndex((item) => String(item?.id) === String(activePhoto.value.id))
})

const previousPhoto = computed(() => {
  if (currentIndex.value <= 0) {
    return null
  }

  return props.photos[currentIndex.value - 1] ?? null
})

const nextPhoto = computed(() => {
  if (currentIndex.value < 0 || currentIndex.value >= props.photos.length - 1) {
    return null
  }

  return props.photos[currentIndex.value + 1] ?? null
})

function selectPreviousPhoto() {
  if (!previousPhoto.value) {
    return
  }

  emit('select-photo', previousPhoto.value)
}

function selectNextPhoto() {
  if (!nextPhoto.value) {
    return
  }

  emit('select-photo', nextPhoto.value)
}

function handleKeydown(event) {
  if (event.key === 'Escape') {
    emit('close')
    return
  }

  if (event.key === 'ArrowLeft') {
    selectPreviousPhoto()
    return
  }

  if (event.key === 'ArrowRight') {
    selectNextPhoto()
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
  <div v-if="activePhoto" class="travel-modal travel-modal--lightbox" @click.self="emit('close')">
    <div class="travel-modal__dialog travel-lightbox">
      <div class="travel-modal__header">
        <div>
          <h2>{{ activePhoto.title || activePhoto.originalFileName || DEFAULT_TITLE }}</h2>
          <p>{{ formatDateTime(activePhoto.expenseDate, activePhoto.expenseTime) }}</p>
        </div>
        <button class="button button--ghost" type="button" @click="emit('close')">{{ CLOSE_LABEL }}</button>
      </div>

      <div class="travel-lightbox__body">
        <button
          v-if="previousPhoto"
          class="travel-lightbox__nav travel-lightbox__nav--prev"
          type="button"
          :aria-label="PREVIOUS_PHOTO_LABEL"
          @click="selectPreviousPhoto"
        >
          <span aria-hidden="true">&lsaquo;</span>
        </button>
        <img
          class="travel-lightbox__image"
          :src="activePhoto.contentUrl"
          :alt="activePhoto.title || activePhoto.originalFileName || 'travel photo'"
        />
        <button
          v-if="nextPhoto"
          class="travel-lightbox__nav travel-lightbox__nav--next"
          type="button"
          :aria-label="NEXT_PHOTO_LABEL"
          @click="selectNextPhoto"
        >
          <span aria-hidden="true">&rsaquo;</span>
        </button>
      </div>

      <div class="travel-lightbox__meta">
        <strong>{{ activePhoto.caption || activePhoto.originalFileName || DEFAULT_PHOTO_LABEL }}</strong>
        <small>{{ locationLabel }}</small>
        <small v-if="activePhoto.uploadedBy">{{ UPLOADED_BY_PREFIX }}{{ activePhoto.uploadedBy }}</small>
      </div>
    </div>
  </div>
</template>
