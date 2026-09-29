<script setup>
defineProps({
  days: { type: Array, default: () => [] },
  selectedDayKey: { type: String, default: '' },
  showDaySelector: { type: Boolean, default: true },
  variant: { type: String, default: 'panel' },
  isPlaying: { type: Boolean, default: false },
  photoIndex: { type: Number, default: -1 },
  photoCount: { type: Number, default: 0 },
  speedSeconds: { type: Number, default: 4 },
  hasCompleted: { type: Boolean, default: false },
  isBusy: { type: Boolean, default: false },
})

const emit = defineEmits(['select-day', 'toggle', 'speed-change'])

function handleDayChange(event) {
  emit('select-day', event.target.value)
}

function handleSpeedChange(event) {
  emit('speed-change', Number(event.target.value))
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
      <select :value="selectedDayKey" :disabled="!days.length" @change="handleDayChange">
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
        <span>사진 간격</span>
        <select :value="speedSeconds" @change="handleSpeedChange">
          <option :value="2">2초</option>
          <option :value="4">4초</option>
          <option :value="6">6초</option>
          <option :value="8">8초</option>
          <option :value="10">10초</option>
        </select>
      </label>
    </div>

    <small v-if="showDaySelector" class="travel-journey-controls__hint">
      날짜를 고르면 해당 일자의 사진·장소·경로만 표시됩니다. 재생 중에도 사진을 멈춰 자세히 볼 수 있습니다.
    </small>
  </section>
</template>
