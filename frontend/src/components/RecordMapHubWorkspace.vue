<script setup>
import { computed, defineAsyncComponent, onBeforeUnmount, ref, watch } from 'vue'
const RecordMapWorkspace = defineAsyncComponent(() => import('./RecordMapWorkspace.vue'))
const TravelWorkspace = defineAsyncComponent(() => import('./TravelWorkspace.vue'))
const props = defineProps({ route: String, recordFocusRequest: Object })
const emit = defineEmits(['open-household-travel-ledger', 'record-focus-consumed'])
const mode = ref(props.route === 'travel' ? 'records' : 'travel')
let leaveGuard = { active: false, message: '' }
function onLeaveGuard(event) { leaveGuard = event.detail || {} }
window.addEventListener('calen-route-leave-guard', onLeaveGuard)
onBeforeUnmount(() => window.removeEventListener('calen-route-leave-guard', onLeaveGuard))
function changeMode(value) {
  if (value === mode.value) return
  if (leaveGuard.active && !window.confirm(leaveGuard.message || '작성 중인 내용을 저장하지 않고 화면을 바꿀까요?')) return
  mode.value = value
}
watch(() => props.route, route => { mode.value = route === 'travel' ? 'records' : 'travel' })
watch(() => props.recordFocusRequest?.token, token => { if (token) mode.value = 'travel' })
const legacyRoute = computed(() => props.route === 'travel' ? 'my-map' : props.route)
</script>
<template>
  <div class="workspace-stack">
    <nav class="panel record-map-mode-switch" aria-label="기록 지도 사용 방식">
      <button class="button" :class="{ 'button--primary': mode === 'records' }" :aria-pressed="mode === 'records'" type="button" @click="changeMode('records')">일상 기록 · 장소 모음</button>
      <button class="button" :class="{ 'button--primary': mode === 'travel' }" :aria-pressed="mode === 'travel'" type="button" @click="changeMode('travel')">여행 · 일정 · 경로</button>
    </nav>
    <RecordMapWorkspace v-if="mode === 'records'" @open-travel="mode = 'travel'" />
    <TravelWorkspace v-else :route="legacyRoute" :record-focus-request="recordFocusRequest" @open-household-travel-ledger="emit('open-household-travel-ledger')" @record-focus-consumed="emit('record-focus-consumed', $event)" />
  </div>
</template>
<style scoped>
.record-map-mode-switch { display: flex; gap: 8px; flex-wrap: wrap; padding: 12px; }
</style>
