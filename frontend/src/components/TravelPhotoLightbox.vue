<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
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
  showRepresentativeAction: {
    type: Boolean,
    default: false,
  },
  representativeMediaId: {
    type: [String, Number],
    default: null,
  },
  isRepresentativeSaving: {
    type: Boolean,
    default: false,
  },
  representativeUpdatingId: {
    type: [String, Number],
    default: null,
  },
})

const emit = defineEmits(['close', 'select-photo', 'set-representative'])

const DEFAULT_TITLE = '\uC0AC\uC9C4 \uBCF4\uAE30'
const CLOSE_LABEL = '\uB2EB\uAE30'
const DEFAULT_PHOTO_LABEL = '\uC0AC\uC9C4'
const LOCATION_EMPTY_LABEL = '\uC704\uCE58 \uC815\uBCF4 \uC5C6\uC74C'
const UPLOADED_BY_PREFIX = '\uC5C5\uB85C\uB4DC '
const PREVIOUS_PHOTO_LABEL = '\uC774\uC804 \uC0AC\uC9C4'
const NEXT_PHOTO_LABEL = '\uB2E4\uC74C \uC0AC\uC9C4'
const CURRENT_REPRESENTATIVE_LABEL = '\uD604\uC7AC \uB300\uD45C \uC0AC\uC9C4'
const SET_REPRESENTATIVE_LABEL = '\uB300\uD45C \uC9C0\uC815'
const UPDATING_REPRESENTATIVE_LABEL = '\uBCC0\uACBD \uC911...'

function getTimelineTimestamp(photo) {
  const date = String(photo?.expenseDate ?? '').trim()
  const time = String(photo?.expenseTime ?? '').trim()

  if (date) {
    const timestamp = Date.parse(`${date}T${time || '00:00:00'}`)
    if (!Number.isNaN(timestamp)) {
      return timestamp
    }
  }

  const uploadedTimestamp = Date.parse(String(photo?.uploadedAt ?? ''))
  return Number.isNaN(uploadedTimestamp) ? null : uploadedTimestamp
}

function comparePhotosByTimeline(left, right) {
  const leftTimestamp = getTimelineTimestamp(left)
  const rightTimestamp = getTimelineTimestamp(right)

  if (leftTimestamp != null && rightTimestamp != null && leftTimestamp !== rightTimestamp) {
    return leftTimestamp - rightTimestamp
  }
  if (leftTimestamp != null) {
    return -1
  }
  if (rightTimestamp != null) {
    return 1
  }

  return String(left?.id ?? '').localeCompare(String(right?.id ?? ''), undefined, { numeric: true })
}

const timelinePhotos = computed(() => [...props.photos].sort(comparePhotosByTimeline))

const activePhoto = computed(() => {
  if (!props.photo) {
    return null
  }

  if (props.currentPhotoId == null) {
    return props.photo
  }

  return timelinePhotos.value.find((item) => String(item?.id) === String(props.currentPhotoId)) ?? props.photo
})

const activePhotoUrl = computed(() => String(activePhoto.value?.contentUrl ?? '').trim())
const displayedImageUrl = ref('')
const isImagePreparing = ref(false)
let imageRequestId = 0

async function prepareImage(url) {
  const requestId = ++imageRequestId

  if (!url) {
    displayedImageUrl.value = ''
    isImagePreparing.value = false
    return
  }

  if (url === displayedImageUrl.value) {
    isImagePreparing.value = false
    return
  }

  isImagePreparing.value = true
  const image = new Image()
  image.decoding = 'async'
  image.fetchPriority = 'high'

  image.onload = async () => {
    try {
      await image.decode?.()
    } catch {
      // Some browsers reject decode for already-renderable cached images.
    }

    if (requestId !== imageRequestId) {
      return
    }

    // Keep the existing photo visible until the replacement has fully decoded.
    displayedImageUrl.value = url
    isImagePreparing.value = false
  }

  image.onerror = () => {
    if (requestId === imageRequestId) {
      isImagePreparing.value = false
    }
  }

  image.src = url
}

watch(activePhotoUrl, prepareImage, { immediate: true })

const locationLabel = computed(() =>
  [activePhoto.value?.country, activePhoto.value?.region, activePhoto.value?.placeName].filter(Boolean).join(' / ') || LOCATION_EMPTY_LABEL,
)

const currentIndex = computed(() => {
  if (!activePhoto.value?.id) {
    return -1
  }

  return timelinePhotos.value.findIndex((item) => String(item?.id) === String(activePhoto.value.id))
})

const previousPhoto = computed(() => {
  if (currentIndex.value <= 0) {
    return null
  }

  return timelinePhotos.value[currentIndex.value - 1] ?? null
})

const nextPhoto = computed(() => {
  if (currentIndex.value < 0 || currentIndex.value >= timelinePhotos.value.length - 1) {
    return null
  }

  return timelinePhotos.value[currentIndex.value + 1] ?? null
})

const isCurrentRepresentative = computed(() =>
  String(activePhoto.value?.id ?? '') === String(props.representativeMediaId ?? ''),
)

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

function handleSetRepresentative() {
  if (!activePhoto.value || isCurrentRepresentative.value || props.isRepresentativeSaving) {
    return
  }

  emit('set-representative', activePhoto.value)
}

function handleKeydown(event) {
  if (event.key === 'Escape') {
    event.preventDefault()
    event.stopPropagation()
    event.stopImmediatePropagation?.()
    event.returnValue = false
    emit('close')
    return
  }

  if (event.key === 'ArrowLeft') {
    event.preventDefault()
    event.stopPropagation()
    selectPreviousPhoto()
    return
  }

  if (event.key === 'ArrowRight') {
    event.preventDefault()
    event.stopPropagation()
    selectNextPhoto()
  }
}

onMounted(() => {
  window.addEventListener('keydown', handleKeydown, { capture: true })
})

onBeforeUnmount(() => {
  imageRequestId += 1
  window.removeEventListener('keydown', handleKeydown, { capture: true })
})
</script>

<template>
  <div v-if="activePhoto" class="travel-modal travel-modal--lightbox" data-map-photo-detail="true" @pointerdown.stop @pointerup.stop @touchstart.stop @touchend.stop @click.stop @keydown.esc="emit('close')">
    <div class="travel-modal__dialog travel-lightbox">
      <div class="travel-modal__header">
        <div>
          <h2>{{ activePhoto.title || activePhoto.originalFileName || DEFAULT_TITLE }}</h2>
          <p>{{ formatDateTime(activePhoto.expenseDate, activePhoto.expenseTime) }}</p>
        </div>
        <button class="button button--ghost" type="button" data-modal-close @click="emit('close')">{{ CLOSE_LABEL }}</button>
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
        <div v-if="isImagePreparing && !displayedImageUrl" class="travel-lightbox__image-state" role="status">
          사진을 준비하고 있습니다.
        </div>
        <img
          v-if="displayedImageUrl"
          class="travel-lightbox__image"
          :src="displayedImageUrl"
          :alt="activePhoto.title || activePhoto.originalFileName || 'travel photo'"
          decoding="async"
          fetchpriority="high"
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
        <div v-if="showRepresentativeAction" class="travel-lightbox__actions">
          <button
            class="button button--primary"
            type="button"
            :disabled="isRepresentativeSaving || isCurrentRepresentative"
            @click="handleSetRepresentative"
          >
            {{
              representativeUpdatingId === activePhoto.id
                ? UPDATING_REPRESENTATIVE_LABEL
                : isCurrentRepresentative
                  ? CURRENT_REPRESENTATIVE_LABEL
                  : SET_REPRESENTATIVE_LABEL
            }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
