<script setup>
import { computed, defineAsyncComponent, nextTick, onMounted, ref } from 'vue'
import BaseDialog from './BaseDialog.vue'
import CalendarIcon from './CalendarIcon.vue'

const TravelSharedExhibitWorkspace = defineAsyncComponent(() => import('../TravelSharedExhibitWorkspace.vue'))
const RecordMapWorkspace = defineAsyncComponent(() => import('../RecordMapWorkspace.vue'))

const props = defineProps({ source: { type: Object, required: true } })
const emit = defineEmits(['close'])
const isMap = computed(() => props.source.kind === 'map')
const label = computed(() => isMap.value ? '공유 지도 원본' : '공유 여행 원본')
// The content is rendered after the dialog is open so a map measures its real size.
const ready = ref(false)
onMounted(async () => { await nextTick(); ready.value = true })

// An inner dialog (for example a place detail of the map) is closed before the overlay itself.
function requestClose() {
  const inner = [...document.querySelectorAll('dialog[open]')].filter(dialog => !dialog.classList.contains('integrated-calendar__dialog')).at(-1)
  const closer = inner?.querySelector('[data-modal-close]')
  if (closer) { closer.click(); return }
  emit('close')
}
</script>

<template>
  <BaseDialog variant="fullscreen" class="integrated-calendar__overlay" :label="label" @close="requestClose">
    <div class="integrated-calendar__overlay-frame">
      <div class="integrated-calendar__overlay-bar">
        <strong>{{ label }}</strong>
        <button type="button" class="integrated-calendar__overlay-close" :aria-label="`${label} 닫기`" @click="emit('close')"><CalendarIcon name="close" :size="18" /><span>닫기</span></button>
      </div>
      <div class="integrated-calendar__overlay-body">
        <template v-if="ready">
          <RecordMapWorkspace v-if="isMap" public-view :calendar-grant-id="source.grantId" :place-focus-request="source.focus" />
          <TravelSharedExhibitWorkspace v-else embedded :selected-exhibit="source.detail" />
        </template>
      </div>
    </div>
  </BaseDialog>
</template>

<style scoped>
.integrated-calendar__overlay-frame { display: flex; flex-direction: column; height: 100%; min-height: 0; }
.integrated-calendar__overlay-bar { display: flex; align-items: center; justify-content: space-between; gap: .5rem; padding: .25rem .75rem; border-bottom: 1px solid var(--ic-line); background: var(--ic-panel); }
.integrated-calendar .integrated-calendar__overlay-close { display: inline-flex; align-items: center; gap: .375rem; min-height: 2.75rem; padding: .25rem .875rem; }
.integrated-calendar__overlay-body { flex: 1 1 auto; min-height: 0; height: calc(100dvh - 3.5rem); overflow: auto; }
</style>
