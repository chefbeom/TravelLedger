<script setup>
import CalendarIcon from './CalendarIcon.vue'

defineProps({ issues: { type: Array, required: true }, retrying: { type: Boolean, default: false } })
const emit = defineEmits(['retry'])
const VISIBLE = 3
</script>

<template>
  <div v-if="issues.length" class="integrated-calendar__issues" role="alert">
    <CalendarIcon name="warning" :size="20" class="integrated-calendar__issues-icon" />
    <div class="integrated-calendar__issues-body">
      <strong>일부 자료를 불러오지 못했습니다.</strong>
      <ul>
        <li v-for="issue in issues.slice(0, VISIBLE)" :key="issue.key">{{ issue.message }}</li>
      </ul>
      <details v-if="issues.length > VISIBLE">
        <summary>외 {{ issues.length - VISIBLE }}건</summary>
        <ul><li v-for="issue in issues.slice(VISIBLE)" :key="issue.key">{{ issue.message }}</li></ul>
      </details>
    </div>
    <button type="button" class="integrated-calendar__issues-retry" :disabled="retrying" @click="emit('retry')">재시도</button>
  </div>
</template>

<style scoped>
.integrated-calendar__issues { display: flex; align-items: flex-start; gap: .75rem; padding: .625rem .75rem; border: 1px solid var(--ic-line); border-left: 3px solid var(--ic-warning); background: var(--ic-warning-soft); }
.integrated-calendar__issues-icon { margin-top: .125rem; color: var(--ic-warning); }
.integrated-calendar__issues-body { flex: 1 1 auto; min-width: 0; display: grid; gap: .25rem; }
.integrated-calendar__issues-body ul { margin: 0; padding-left: 1.125rem; display: grid; gap: .125rem; font-size: .875rem; }
.integrated-calendar__issues-body summary { cursor: pointer; min-height: 2.75rem; display: flex; align-items: center; font-size: .875rem; }
.integrated-calendar .integrated-calendar__issues-retry { flex: none; min-height: 2.75rem; padding: .25rem 1rem; }
</style>
