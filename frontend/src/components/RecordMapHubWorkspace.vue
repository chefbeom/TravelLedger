<script setup>
import { computed, defineAsyncComponent, onBeforeUnmount, ref, watch } from 'vue'
import './record-map/recordMapTokens.css'
import { hubHashForMode, hubModeForRoute } from '../lib/recordMapView'
import { useRecordShareNotifications } from '../lib/useRecordShareNotifications'
const RecordMapWorkspace = defineAsyncComponent(() => import('./RecordMapWorkspace.vue'))
const TravelWorkspace = defineAsyncComponent(() => import('./TravelWorkspace.vue'))
const RecordSharingWorkspace = defineAsyncComponent(() => import('./RecordSharingWorkspace.vue'))
const props = defineProps({ route: String, recordFocusRequest: Object, placeFocusRequest: Object })
const emit = defineEmits(['open-household-travel-ledger', 'record-focus-consumed', 'place-focus-consumed'])
const { shareCounts } = useRecordShareNotifications()
// 'records' | 'travel' | 'shares'. 'shares' is not part of the URL.
const mode = ref(hubModeForRoute(props.route))
let leaveGuard = { active: false, message: '' }
function onLeaveGuard(event) { leaveGuard = event.detail || {} }
window.addEventListener('calen-route-leave-guard', onLeaveGuard)
onBeforeUnmount(() => window.removeEventListener('calen-route-leave-guard', onLeaveGuard))
function changeMode(value) {
  if (value === mode.value) return
  const targetHash = hubHashForMode(value)
  const currentHash = window.location.hash.replace(/^#/, '')
  if (targetHash && targetHash !== currentHash && hubModeForRoute(targetHash) !== hubModeForRoute(props.route)) {
    // The route changes: App's hashchange handler asks for leave confirmation (and restores the hash on cancel);
    // the route watcher below then updates the mode. Do not set the mode here.
    window.location.hash = targetHash
    return
  }
  // The route stays the same (for example records <-> shares): confirm here and switch directly.
  if (leaveGuard.active && !window.confirm(leaveGuard.message || '작성 중인 내용을 저장하지 않고 화면을 바꿀까요?')) return
  mode.value = value
}
watch(() => props.route, route => { mode.value = hubModeForRoute(route) })
watch(() => props.recordFocusRequest?.token, token => { if (token) mode.value = 'travel' })
watch(() => props.placeFocusRequest?.token, token => { if (token) mode.value = 'records' }, { immediate: true })
const legacyRoute = computed(() => props.route === 'travel' ? 'my-map' : props.route)
</script>
<template>
  <div class="workspace-stack record-map-hub">
    <nav class="panel record-map-mode-switch" aria-label="기록 지도 사용 방식">
      <button class="button" :class="{ 'button--primary': mode === 'records' }" :aria-pressed="mode === 'records'" type="button" @click="changeMode('records')">내 장소</button>
      <button class="button" :class="{ 'button--primary': mode === 'travel' }" :aria-pressed="mode === 'travel'" type="button" @click="changeMode('travel')">여행 · 일정 · 경로</button>
      <button class="button" :class="{ 'button--primary': mode === 'shares' }" :aria-pressed="mode === 'shares'" type="button" @click="changeMode('shares')">공유받은 여행<span v-if="shareCounts.travel" class="record-share-badge" :aria-label="`승인 대기 ${shareCounts.travel}건`">{{ shareCounts.travel }}</span></button>
    </nav>
    <RecordMapWorkspace v-if="mode === 'records'" :place-focus-request="placeFocusRequest" @place-focus-consumed="emit('place-focus-consumed')" @open-travel="changeMode('travel')" />
    <RecordSharingWorkspace v-else-if="mode === 'shares'" kind="TRAVEL" />
    <TravelWorkspace v-else :route="legacyRoute" :record-focus-request="recordFocusRequest" @open-household-travel-ledger="emit('open-household-travel-ledger')" @record-focus-consumed="emit('record-focus-consumed', $event)" />
  </div>
</template>
<style scoped>
.record-map-mode-switch { display: flex; align-items: center; gap: 4px; flex-wrap: wrap; padding: 6px 8px; min-height: 0; }
.record-map-mode-switch .button { min-height: 40px !important; }
.record-map-mode-switch .record-share-badge { margin-left: 6px; }
:global(html body .app-shell .record-map-hub .record-map-mode-switch .button[aria-pressed="true"]) { box-shadow: inset 0 -3px 0 var(--rm-accent) !important; font-weight: 700; }
@media (max-width: 760px) {
  .record-map-mode-switch { flex-wrap: nowrap; padding: 2px 8px !important; }
  .record-map-mode-switch .button { flex: 1 1 0; min-width: 0 !important; min-height: 44px !important; padding: 4px 6px !important; font-size: 12px !important; white-space: nowrap; width: auto !important; }
  /* Compact app header while the record map is open (moved here from RecordMapWorkspace so it only follows the hub). */
  :global(body:has(.record-map-hub) .app-shell) { padding-top: 0 !important; }
  :global(body:has(.record-map-hub) .theme-switcher) { position: static !important; padding: 4px 8px !important; margin: 0 !important; min-height: 0 !important; justify-content: flex-end; }
  :global(body:has(.record-map-hub) .theme-switcher button) { min-height: 30px !important; padding: 4px 7px !important; font-size: 11px !important; }
  :global(body:has(.record-map-hub) .topbar) { display: flex !important; flex-wrap: wrap; gap: 4px !important; padding: 6px 8px !important; min-height: 0 !important; }
  :global(body:has(.record-map-hub) .topbar__copy) { flex: 1; min-width: 0; }
  :global(body:has(.record-map-hub) .topbar__copy :is(.topbar__eyebrow,.topbar__title,.topbar__description,.topbar__context)) { display: none !important; }
  :global(body:has(.record-map-hub) .topbar__brand) { font-size: 14px !important; }
  :global(body:has(.record-map-hub) .topbar > .topbar__actions) { width: auto !important; gap: 4px !important; }
  :global(html body:has(.record-map-hub) .app-shell .topbar > .topbar__actions) { display: flex !important; flex-direction: row !important; flex-wrap: nowrap !important; }
  :global(body:has(.record-map-hub) .topbar > .topbar__actions .button) { min-height: 30px !important; min-width: 0 !important; padding: 4px 7px !important; font-size: 11px !important; }
  :global(html body:has(.record-map-hub) .app-shell .topbar > .topbar__actions .button) { width: auto !important; flex: 0 0 auto !important; }
  :global(body:has(.record-map-hub) .topbar__nav) { width: 100% !important; gap: 3px !important; padding: 0 !important; }
  :global(body:has(.record-map-hub) .topbar__nav-button) { min-height: 34px !important; padding: 5px 8px !important; font-size: 12px !important; }
}
</style>
