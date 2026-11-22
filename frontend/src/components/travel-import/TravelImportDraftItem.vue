<script setup>
import { computed } from 'vue'
import { IMPORT_CATEGORIES, IMPORT_LIMITS } from '../../lib/travelImport'

const props = defineProps({
  draft: { type: Object, required: true },
  problems: { type: Array, default: () => [] },
  disabled: { type: Boolean, default: false },
  planStartDate: { type: String, default: '' },
  planPeriod: { type: Object, default: null },
  focused: { type: Boolean, default: false },
  index: { type: Number, default: 0 },
})
const emit = defineEmits(['update', 'locate', 'show-on-map', 'remove-photo', 'focus'])

const TRANSPORTS = [
  ['WALK', '도보'], ['BUS', '버스'], ['TAXI', '택시'], ['TRAIN', '기차'],
  ['SUBWAY', '지하철'], ['CAR', '자동차'], ['FLIGHT', '항공'], ['ETC', '기타'],
]
const SOURCE_LABELS = { exif: '사진 위치', search: '검색한 위치', map: '지도에서 지정', gps: '현재 위치' }

const isGpx = computed(() => props.draft.kind === 'gpx')
const saved = computed(() => props.draft.status === 'saved')
const locked = computed(() => props.disabled || saved.value)
const hasLocation = computed(() => props.draft.latitude !== null && props.draft.latitude !== ''
  && props.draft.longitude !== null && props.draft.longitude !== ''
  && Number.isFinite(Number(props.draft.latitude)) && Number.isFinite(Number(props.draft.longitude)))
const titleLabel = computed(() => (isGpx.value ? '경로 제목' : '기록 제목'))
const photoThumbs = computed(() => (props.draft.photos || []).slice(0, 4))
const extraPhotos = computed(() => Math.max(0, (props.draft.photos || []).length - 4))
const dateEstimated = computed(() => props.draft.dateSource === 'fallback' && props.draft.flagDate !== false)
const outsidePeriod = computed(() => {
  const period = props.planPeriod
  if (!period?.startDate || !period?.endDate || !props.draft.date) return false
  return props.draft.date < period.startDate || props.draft.date > period.endDate
})
const locationText = computed(() => {
  const draft = props.draft
  const names = [draft.country, draft.region, draft.placeName].filter(Boolean)
  const place = names.length ? names.join(' · ') : `${Number(draft.latitude).toFixed(5)}, ${Number(draft.longitude).toFixed(5)}`
  const source = SOURCE_LABELS[draft.locationSource]
  return source ? `${place} (${source})` : place
})
const trackSummary = computed(() => {
  const track = props.draft.track
  if (!track) return ''
  const minutes = Number(track.durationMinutes || 0)
  const duration = minutes >= 60 ? `${Math.floor(minutes / 60)}시간${minutes % 60 ? ` ${minutes % 60}분` : ''}` : minutes ? `${minutes}분` : ''
  const total = track.points.length
  const kept = Math.min(total, IMPORT_LIMITS.maxSavedRoutePoints)
  return [
    `${Number(track.distanceKm).toFixed(1)}km`,
    duration,
    total > kept ? `점 ${total.toLocaleString('ko-KR')}→${kept.toLocaleString('ko-KR')}` : `점 ${total.toLocaleString('ko-KR')}개`,
  ].filter(Boolean).join(' · ')
})
const statusText = computed(() => {
  switch (props.draft.status) {
    case 'saving': return '저장 중'
    case 'saved': return '저장됨'
    case 'failed': return `실패: ${props.draft.error}`
    case 'warning': return props.draft.error
    default: return ''
  }
})
const categoryListId = computed(() => `travel-import-categories-${props.index}`)
const memoLength = computed(() => String(props.draft.memo || '').length)
</script>

<template>
  <li
    class="travel-import-item"
    :class="{ 'is-excluded': !draft.include, 'is-focused': focused, 'is-saved': saved }"
    :data-draft-id="draft.id"
    :data-kind="draft.kind"
    :data-status="draft.status"
    :aria-invalid="problems.length ? 'true' : undefined"
    @focusin="emit('focus')"
  >
    <label class="travel-import-item__include">
      <input type="checkbox" aria-label="포함" :checked="draft.include" :disabled="locked" @change="emit('update', { include: $event.target.checked })" />
    </label>

    <div class="travel-import-item__thumbs" aria-hidden="false">
      <template v-if="draft.kind === 'photos'">
        <span v-for="photo in photoThumbs" :key="photo.id" class="travel-import-item__thumb">
          <img :src="photo.previewUrl" :alt="photo.fileName" width="56" height="56" decoding="async" loading="lazy" />
          <button
            v-if="!locked"
            type="button"
            class="travel-import-item__remove"
            :aria-label="`${photo.fileName} 빼기`"
            @click="emit('remove-photo', photo.id)"
          >×</button>
        </span>
        <span v-if="extraPhotos" class="travel-import-item__more">+{{ extraPhotos }}</span>
      </template>
      <span v-else class="travel-import-item__badge" :class="`is-${draft.kind}`">{{ isGpx ? 'GPX' : '📍' }}</span>
    </div>

    <div class="travel-import-item__body">
      <label class="travel-import-item__field travel-import-item__field--title">
        <span>제목</span>
        <input
          type="text"
          :aria-label="titleLabel"
          :value="draft.title"
          maxlength="120"
          :disabled="locked"
          :aria-invalid="problems.some((item) => item.startsWith('제목')) ? 'true' : undefined"
          @input="emit('update', { title: $event.target.value })"
        />
      </label>

      <div class="travel-import-item__row">
        <label class="travel-import-item__field">
          <span>날짜</span>
          <input type="date" aria-label="날짜" :value="draft.date" :disabled="locked" @change="emit('update', { date: $event.target.value })" />
        </label>
        <label class="travel-import-item__field">
          <span>시간</span>
          <input type="time" aria-label="시간" :value="draft.time" :disabled="locked" @change="emit('update', { time: $event.target.value })" />
        </label>
        <span v-if="draft.kind === 'photos'" class="travel-import-item__count">사진 {{ draft.photos.length }}장</span>
        <span v-else-if="isGpx" class="travel-import-item__count">{{ trackSummary }}</span>
      </div>

      <p v-if="dateEstimated || (!isGpx && !hasLocation) || outsidePeriod" class="travel-import-item__flags">
        <span v-if="dateEstimated" class="travel-import-flag">날짜 추정</span>
        <span v-if="!isGpx && !hasLocation" class="travel-import-flag">위치 없음</span>
        <span v-if="outsidePeriod" class="travel-import-flag">여행 기간 밖</span>
      </p>

      <p v-if="!isGpx" class="travel-import-item__location">
        <template v-if="hasLocation">
          <span class="travel-import-item__place">📍 {{ locationText }}</span>
          <span v-if="draft.geocoding" class="travel-import-item__hint">위치 이름 확인 중…</span>
          <button type="button" class="button button--ghost" :disabled="locked" @click="emit('show-on-map')">지도에서 보기</button>
        </template>
        <template v-else>
          <button type="button" class="button button--secondary" :disabled="locked" @click="emit('locate')">지도에서 위치 지정</button>
          <span class="travel-import-item__hint">위치가 없으면 지도에는 표시되지 않습니다.</span>
        </template>
      </p>

      <ul v-if="problems.length" class="travel-import-item__problems">
        <li v-for="problem in problems" :key="problem">{{ problem }}</li>
      </ul>

      <details class="travel-import-item__advanced">
        <summary>고급</summary>
        <div class="travel-import-item__advanced-body">
          <label v-if="!isGpx" class="travel-import-item__field">
            <span>분류</span>
            <input type="text" aria-label="분류" :list="categoryListId" :value="draft.category" maxlength="80" :disabled="locked" @input="emit('update', { category: $event.target.value })" />
            <datalist :id="categoryListId"><option v-for="category in IMPORT_CATEGORIES" :key="category" :value="category" /></datalist>
          </label>
          <label v-else class="travel-import-item__field">
            <span>이동 수단</span>
            <select aria-label="이동 수단" :value="draft.track?.transportMode || 'WALK'" :disabled="locked" @change="emit('update', { transportMode: $event.target.value })">
              <option v-for="[value, label] in TRANSPORTS" :key="value" :value="value">{{ label }}</option>
            </select>
          </label>
          <label class="travel-import-item__field">
            <span>메모 <small>{{ memoLength }}/500</small></span>
            <textarea aria-label="메모" rows="2" maxlength="500" :value="draft.memo" :disabled="locked" @input="emit('update', { memo: $event.target.value })" />
          </label>
        </div>
      </details>

      <p v-if="statusText" class="travel-import-item__status" :class="`is-${draft.status}`">{{ statusText }}</p>
    </div>
  </li>
</template>

<style scoped>
.travel-import-item { display: grid; grid-template-columns: auto auto minmax(0, 1fr); gap: 10px; align-items: start; min-width: 0; padding: 10px; border: 1px solid var(--rm-line); background: var(--rm-surface); list-style: none; }
.travel-import-item.is-focused { box-shadow: inset 3px 0 0 var(--rm-accent) !important; border-color: var(--rm-accent) !important; }
.travel-import-item.is-excluded { opacity: .6; }
.travel-import-item[aria-invalid="true"] { border-color: var(--rm-wanted); }
.travel-import-item__include { display: inline-flex; align-items: center; justify-content: center; min-width: 32px; min-height: 44px; cursor: pointer; }
.travel-import-item__include input { width: 20px; height: 20px; margin: 0; }
.travel-import-item__thumbs { display: flex; flex-wrap: wrap; gap: 4px; max-width: 128px; align-content: flex-start; }
.travel-import-item__thumb { position: relative; display: inline-block; width: 56px; height: 56px; }
.travel-import-item__thumb img { display: block; width: 56px; height: 56px; object-fit: cover; border: 1px solid var(--rm-line); background: var(--rm-panel); }
.travel-import-item__remove { position: absolute; top: -6px; right: -6px; width: 22px; height: 22px; min-height: 22px !important; min-width: 22px !important; padding: 0 !important; line-height: 1; font-size: 14px; border-radius: 50%; border: 1px solid var(--rm-line) !important; background: var(--rm-panel) !important; color: var(--rm-text) !important; -webkit-text-fill-color: var(--rm-text) !important; cursor: pointer; }
.travel-import-item__remove::after { content: ''; position: absolute; inset: -11px; }
.travel-import-item__more, .travel-import-item__badge { display: inline-flex; align-items: center; justify-content: center; width: 56px; height: 56px; border: 1px solid var(--rm-line); font-weight: 700; font-size: .85rem; color: var(--rm-text); background: var(--rm-panel); }
.travel-import-item__badge.is-gpx { color: var(--rm-accent); border-color: var(--rm-accent); }
.travel-import-item__body { display: grid; gap: 8px; min-width: 0; }
.travel-import-item__field { display: grid; gap: 3px; min-width: 0; font-size: .8rem; color: var(--rm-text-soft, inherit); }
.travel-import-item__field > span { font-size: .75rem; }
.travel-import-item__field input, .travel-import-item__field select, .travel-import-item__field textarea { width: 100%; min-width: 0; min-height: 40px; box-sizing: border-box; font-size: .9rem; }
.travel-import-item__field--title input { font-weight: 600; }
.travel-import-item__row { display: flex; flex-wrap: wrap; gap: 8px; align-items: end; }
.travel-import-item__row .travel-import-item__field { flex: 0 1 150px; min-width: 110px; }
.travel-import-item__count { font-size: .8rem; color: var(--rm-text-soft, inherit); padding-bottom: 10px; overflow-wrap: anywhere; }
.travel-import-item__flags { display: flex; flex-wrap: wrap; gap: 6px; margin: 0; }
.travel-import-flag { display: inline-flex; align-items: center; gap: 4px; padding: 2px 8px; border: 1px solid var(--rm-wanted); color: var(--rm-wanted); font-size: .75rem; font-weight: 600; }
.travel-import-flag::before { content: '⚠'; }
.travel-import-item__location { display: flex; flex-wrap: wrap; gap: 6px 10px; align-items: center; margin: 0; font-size: .85rem; min-width: 0; }
.travel-import-item__place { overflow-wrap: anywhere; min-width: 0; }
.travel-import-item__hint { font-size: .78rem; color: var(--rm-text-soft, inherit); }
.travel-import-item__location .button { min-height: 40px; }
.travel-import-item__problems { margin: 0; padding: 0; list-style: none; display: grid; gap: 2px; color: var(--rm-danger-text); font-size: .8rem; }
.travel-import-item__problems li { list-style: none; }
.travel-import-item__advanced summary { cursor: pointer; min-height: 32px; font-size: .8rem; display: flex; align-items: center; }
.travel-import-item__advanced-body { display: grid; gap: 8px; padding-top: 6px; }
.travel-import-item__status { margin: 0; font-size: .82rem; font-weight: 600; overflow-wrap: anywhere; }
.travel-import-item__status.is-saved { color: var(--rm-visited); }
.travel-import-item__status.is-failed, .travel-import-item__status.is-warning { color: var(--rm-danger-text); }
@media (max-width: 600px) {
  .travel-import-item { grid-template-columns: auto minmax(0, 1fr); padding: 8px; }
  .travel-import-item__thumbs { grid-column: 2; max-width: none; }
  .travel-import-item__body { grid-column: 1 / -1; }
  .travel-import-item__row .travel-import-item__field { flex: 1 1 120px; }
}
</style>
