<script setup>
const props = defineProps({
  days: { type: Array, default: () => [] },
  selectedDayKey: { type: String, default: '' },
  showDaySelector: { type: Boolean, default: true },
  showSettingsToggle: { type: Boolean, default: true },
  settingsExpanded: { type: Boolean, default: false },
  variant: { type: String, default: 'panel' },
  isPlaying: { type: Boolean, default: false },
  photoIndex: { type: Number, default: -1 },
  photoCount: { type: Number, default: 0 },
  speedSeconds: { type: Number, default: 4 },
  hasCompleted: { type: Boolean, default: false },
  isBusy: { type: Boolean, default: false },
  routeCount: { type: Number, default: 0 },
  routeDistanceKm: { type: Number, default: 0 },
  routeFollowEnabled: { type: Boolean, default: true },
  transitionCountdown: { type: Number, default: 0 },
  transitionDayLabel: { type: String, default: '' },
})

const emit = defineEmits(['select-day', 'toggle', 'speed-change', 'route-follow-change'])

function handleDayChange(event) {
  emit('select-day', event.target.value)
}

function handleSpeedChange(event) {
  const seconds = Number(event.target.value)
  if (Number.isInteger(seconds) && seconds >= 1 && seconds <= 5) emit('speed-change', seconds)
}

function handleRouteFollowChange(event) {
  emit('route-follow-change', event.target.checked)
}
</script>

<template>
  <section class="travel-journey-controls" :class="`travel-journey-controls--${variant}`" aria-label="여행 여정 재생">
    <div class="travel-journey-controls__heading">
      <div>
        <span class="panel__eyebrow">JOURNEY PLAYBACK</span>
        <strong>일자별 여정</strong>
      </div>
      <span class="travel-journey-controls__counter">
        {{ photoCount ? `${Math.max(0, Math.min(photoIndex + 1, photoCount))} / ${photoCount}장` : '사진 없음' }}
      </span>
    </div>

    <label v-if="showDaySelector" class="travel-journey-controls__day-field">
      <span>여행 날짜</span>
      <select aria-label="여행 날짜" :value="selectedDayKey" :disabled="!days.length" @change="handleDayChange">
        <option value="">날짜를 선택하세요</option>
        <option v-for="day in days" :key="day.key" :value="day.key">
          {{ day.label }} · 사진 {{ day.photoCount }}장
        </option>
      </select>
    </label>

    <div class="travel-journey-controls__actions">
      <button class="button button--primary" type="button" :disabled="!photoCount || (isBusy && !isPlaying)" @click="emit('toggle')">
        {{ isPlaying ? '일시정지' : hasCompleted ? '처음부터 재생' : '재생' }}
      </button>
      <label class="travel-journey-controls__speed">
        <span class="travel-journey-controls__speed-heading">
          <span>사진·경로 간격</span>
          <output>{{ speedSeconds }}초</output>
        </span>
        <input
          class="travel-journey-controls__slider"
          type="range"
          min="1"
          max="5"
          step="1"
          :value="speedSeconds"
          :style="{ '--journey-speed-progress': `${(speedSeconds - 1) * 25}%` }"
          aria-label="여정 재생 간격"
          :aria-valuetext="`${speedSeconds}초`"
          @input="handleSpeedChange"
        />
        <span class="travel-journey-controls__speed-ticks" aria-hidden="true">
          <span v-for="seconds in 5" :key="seconds" :class="{ 'is-selected': seconds === speedSeconds }">{{ seconds }}</span>
        </span>
      </label>
    </div>

    <p v-if="transitionCountdown > 0" class="travel-journey-transition" role="status" aria-live="polite">
      <strong>{{ transitionDayLabel }}</strong>
      <span>{{ transitionCountdown }}초 후 이어 재생합니다</span>
    </p>

    <details v-if="showDaySelector" class="travel-journey-controls__settings" :class="{ 'travel-journey-controls__settings--controlled': !showSettingsToggle }" :open="settingsExpanded">
      <summary v-if="showSettingsToggle">경로·재생 안내</summary>
      <small class="travel-journey-controls__hint">
        사진 핀을 따라 이동하며, 핀을 눌러 상세 내용을 열 수 있습니다. 날짜가 끝나면 다음 날로 이어집니다.
      </small>

      <section class="travel-journey-route-controls" aria-label="여정과 경로 동시 재생">
        <div class="travel-journey-route-controls__heading">
          <strong>경로 동시 따라가기 <span>실험 기능</span></strong>
          <small v-if="routeCount">{{ routeCount }}개 경로 · {{ routeDistanceKm.toFixed(1) }} km</small>
        </div>
        <p v-if="!routeCount" class="travel-journey-route-controls__empty">
          선택한 날짜에 GPS 경로가 없습니다.
        </p>
        <label v-else class="travel-journey-route-toggle">
          <input
            type="checkbox"
            :checked="props.routeFollowEnabled"
            @change="handleRouteFollowChange"
          />
          <span class="travel-journey-route-toggle__switch" aria-hidden="true"><span></span></span>
          <span class="travel-journey-route-toggle__label">경로 따라가기</span>
        </label>
        <small v-if="routeCount" class="travel-journey-route-controls__hint">
          사진과 같은 간격으로 이동합니다. 경로 시각 정보가 없어 이동은 근사 방식입니다.
        </small>
      </section>
    </details>
  </section>
</template>
