<script setup>
defineProps({
  title: {
    type: String,
    default: '',
  },
  meta: {
    type: String,
    default: '',
  },
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
  isLoading: {
    type: Boolean,
    default: false,
  },
  errorMessage: {
    type: String,
    default: '',
  },
  canNavigate: {
    type: Boolean,
    default: false,
  },
})

const emit = defineEmits(['close', 'previous-photo', 'next-photo', 'select-photo'])

const DEFAULT_PHOTO_LABEL = '\uC5EC\uD589 \uC0AC\uC9C4'
const PREVIOUS_PHOTO_LABEL = '\uC774\uC804 \uC0AC\uC9C4'
const NEXT_PHOTO_LABEL = '\uB2E4\uC74C \uC0AC\uC9C4'
const CLOSE_LABEL = '\uB2EB\uAE30'
const LOADING_LABEL = '\uC0AC\uC9C4 \uC815\uBCF4\uB97C \uBD88\uB7EC\uC624\uB294 \uC911\uC785\uB2C8\uB2E4...'
const EMPTY_LABEL = '\uD45C\uC2DC\uD560 \uC0AC\uC9C4 \uC0C1\uC138\uAC00 \uC5C6\uC2B5\uB2C8\uB2E4.'
const THUMB_LIST_LABEL = '\uD074\uB7EC\uC2A4\uD130 \uC0AC\uC9C4 \uBAA9\uB85D'
</script>

<template>
  <div class="public-map-share-photo-modal" role="dialog" aria-modal="true" aria-labelledby="public-map-share-photo-modal-title" @keydown.esc="emit('close')">
    <article class="public-map-share-photo-modal__panel">
      <header class="public-map-share-photo-modal__header">
        <div>
          <span class="panel__eyebrow">PHOTO DETAIL</span>
          <h2 id="public-map-share-photo-modal-title">{{ title || DEFAULT_PHOTO_LABEL }}</h2>
          <p v-if="meta">{{ meta }}</p>
        </div>
        <div class="public-map-share-photo-modal__header-actions">
          <button v-if="canNavigate" class="button button--secondary" type="button" @click="emit('previous-photo')">{{ PREVIOUS_PHOTO_LABEL }}</button>
          <button v-if="canNavigate" class="button button--secondary" type="button" @click="emit('next-photo')">{{ NEXT_PHOTO_LABEL }}</button>
          <button class="button button--ghost" type="button" @click="emit('close')">{{ CLOSE_LABEL }}</button>
        </div>
      </header>

      <p v-if="errorMessage" class="panel__empty">{{ errorMessage }}</p>
      <p v-else-if="isLoading" class="panel__empty">{{ LOADING_LABEL }}</p>
      <div v-else-if="photo?.contentUrl" class="public-map-share-photo-modal__body">
        <button
          v-if="canNavigate"
          class="public-map-share-photo-modal__nav public-map-share-photo-modal__nav--prev"
          type="button"
          :aria-label="PREVIOUS_PHOTO_LABEL"
          @click="emit('previous-photo')"
        >
          <span aria-hidden="true">&lsaquo;</span>
        </button>
        <figure class="public-map-share-photo-modal__figure">
          <img :src="photo.contentUrl" :alt="photo.title || photo.originalFileName || DEFAULT_PHOTO_LABEL" />
          <figcaption>
            <strong>{{ photo.placeName || photo.title || photo.originalFileName || DEFAULT_PHOTO_LABEL }}</strong>
            <span>{{ photo.region || photo.country || photo.planName || '' }}</span>
          </figcaption>
        </figure>
        <button
          v-if="canNavigate"
          class="public-map-share-photo-modal__nav public-map-share-photo-modal__nav--next"
          type="button"
          :aria-label="NEXT_PHOTO_LABEL"
          @click="emit('next-photo')"
        >
          <span aria-hidden="true">&rsaquo;</span>
        </button>
      </div>
      <p v-else class="panel__empty">{{ EMPTY_LABEL }}</p>

      <div v-if="!isLoading && !errorMessage && photos.length > 1" class="public-map-share-photo-modal__thumbs" :aria-label="THUMB_LIST_LABEL">
        <button
          v-for="item in photos"
          :key="item.id"
          class="public-map-share-photo-modal__thumb"
          :class="{ 'is-active': String(item.id) === String(currentPhotoId) }"
          type="button"
          @click="emit('select-photo', item)"
        >
          <img :src="item.contentUrl" :alt="item.title || item.originalFileName || DEFAULT_PHOTO_LABEL" />
        </button>
      </div>
    </article>
  </div>
</template>