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
  routes: { type: Array, default: () => [] },
  selectedRouteId: { type: String, default: '' },
  routeDistanceKm: { type: Number, default: 0 },
  routeSpeedKmh: { type: Number, default: 300 },
  isRoutePlaying: { type: Boolean, default: false },
  routeHasCompleted: { type: Boolean, default: false },
})

const emit = defineEmits(['select-day', 'toggle', 'speed-change', 'select-route', 'toggle-route', 'route-speed-change'])

function handleDayChange(event) {
  emit('select-day', event.target.value)
}

function handleSpeedChange(event) {
  emit('speed-change', Number(event.target.value))
}

function handleRouteChange(event) {
  emit('select-route', event.target.value)
}

function handleRouteSpeedChange(event) {
  emit('route-speed-change', Number(event.target.value))
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

    <section v-if="showDaySelector" class="travel-journey-route-controls" aria-label="실험적 경로 따라가기">
      <div class="travel-journey-route-controls__heading">
        <strong>경로 따라가기 <span>실험 기능</span></strong>
        <small v-if="routes.length">{{ routeDistanceKm.toFixed(1) }} km</small>
      </div>
      <p v-if="!routes.length" class="travel-journey-route-controls__empty">
        선택한 날짜에 재생 가능한 GPS 경로가 없습니다.
      </p>
      <template v-else>
        <label class="travel-journey-controls__day-field travel-journey-route-controls__route-field">
          <span>이동 경로</span>
          <select :value="selectedRouteId" :disabled="routes.length < 2 || isRoutePlaying" @change="handleRouteChange">
            <option v-for="route in routes" :key="route.id" :value="route.id">{{ route.label }}</option>
          </select>
        </label>
        <div class="travel-journey-route-controls__actions">
          <button
            class="button button--primary"
            type="button"
            :disabled="routeSpeedKmh <= 0"
            @click="emit('toggle-route')"
          >
            {{ isRoutePlaying ? '일시정지' : routeHasCompleted ? '다시 재생' : '경로 재생' }}
          </button>
          <label class="travel-journey-controls__speed">
            <span>가상 속도</span>
            <select :value="routeSpeedKmh" :disabled="isRoutePlaying" @change="handleRouteSpeedChange">
              <option :value="0">사용 안 함</option>
              <option :value="60">60 km/h</option>
              <option :value="300">300 km/h</option>
              <option :value="1500">1,500 km/h</option>
              <option :value="9000">9,000 km/h</option>
            </select>
          </label>
        </div>
        <small class="travel-journey-route-controls__hint">
          기록된 GPS 거리 기준으로 지도가 이동합니다. 실제 이동 시간과는 다르며 언제든 일시정지할 수 있습니다.
        </small>
      </template>
    </section>
  </section>
</template>
