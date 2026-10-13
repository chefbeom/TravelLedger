<script setup>
defineProps({ days: Array, day: String, playing: Boolean, speed: Number, index: Number, total: Number, busy: Boolean, countdown: Number, nextDate: String })
const emit = defineEmits(['day', 'toggle', 'speed'])
</script>
<template>
  <section class="record-map-playback" aria-label="기록 재생">
    <strong>기록 재생 <small>{{ total ? Math.max(0, index + 1) + ' / ' + total : '날짜 있는 기록 없음' }}</small></strong>
    <label>날짜 <select :value="day" :disabled="busy || playing" aria-label="재생할 기록 날짜" @change="emit('day', $event.target.value)"><option value="">전체 날짜</option><option v-for="date in days" :key="date" :value="date">{{ date }}</option></select></label>
    <div class="record-map-playback__row"><button class="button" :disabled="!total || busy" type="button" @click="emit('toggle')">{{ playing ? '일시정지' : index >= 0 && index < total - 1 ? '다시 재생' : '재생' }}</button><label>사진 간격 {{ speed }}초<input type="range" min="1" max="5" step="1" :value="speed" aria-label="기록 재생 간격" :aria-valuetext="speed + '초'" @change="emit('speed', Number($event.target.value))" /></label></div>
    <span v-if="countdown" role="status" aria-live="polite">{{ nextDate }} 이어보기 · {{ countdown }}</span>
  </section>
</template>
<style scoped>
.record-map-playback { display: grid; gap: 8px; padding-top: 10px; margin-top: 10px; border-top: 1px solid #53646866; }
strong { display: flex; justify-content: space-between; flex-wrap: wrap; gap: 4px; }
small { font-weight: normal; }
label { display: grid; gap: 5px; font-size: .85rem; min-width: 0; }
select { width: 100%; min-width: 0; }
.record-map-playback__row { display: grid; grid-template-columns: auto minmax(0, 1fr); gap: 12px; align-items: center; }
input[type="range"] { width: 100%; padding: 0; min-height: 24px; }
</style>
