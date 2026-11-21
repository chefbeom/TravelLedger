<script setup>
import { nextTick, onBeforeUnmount, ref } from 'vue'
import { pushEscape } from '../../lib/integratedCalendarEscape.js'
import CalendarIcon from './CalendarIcon.vue'

const props = defineProps({ year: { type: Number, required: true }, month: { type: Number, required: true } })
const emit = defineEmits(['pick'])
const MIN_YEAR = 1900, MAX_YEAR = 9998
const open = ref(false)
const draftYear = ref(props.year)
const root = ref(null), trigger = ref(null), panel = ref(null)
let popEscape = null

function onOutside(event) {
  if (root.value && !root.value.contains(event.target)) close(false)
}
async function show() {
  draftYear.value = props.year
  open.value = true
  popEscape = pushEscape(() => close())
  document.addEventListener('pointerdown', onOutside)
  await nextTick()
  panel.value?.querySelector('[aria-pressed="true"]')?.focus() || panel.value?.querySelector('[data-month]')?.focus()
}
function close(restoreFocus = true) {
  open.value = false
  popEscape?.(); popEscape = null
  document.removeEventListener('pointerdown', onOutside)
  if (restoreFocus) trigger.value?.focus()
}
function toggle() { open.value ? close() : show() }
function shiftYear(step) {
  const next = draftYear.value + step
  if (next >= MIN_YEAR && next <= MAX_YEAR) draftYear.value = next
}
function pick(monthNumber) {
  close()
  emit('pick', { year: draftYear.value, month: monthNumber })
}
function onKeydown(event) {
  if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); close() }
}
onBeforeUnmount(() => { popEscape?.(); document.removeEventListener('pointerdown', onOutside) })
</script>

<template>
  <div ref="root" class="integrated-calendar__monthpicker">
    <button ref="trigger" type="button" class="integrated-calendar__month-trigger" aria-haspopup="dialog" :aria-expanded="open" @click="toggle">
      <span>{{ year }}년 {{ month }}월</span><CalendarIcon name="chevron-down" :size="14" />
    </button>
    <div v-if="open" ref="panel" role="dialog" aria-label="연월 선택" class="integrated-calendar__monthpicker-panel" @keydown="onKeydown">
      <div class="integrated-calendar__monthpicker-year">
        <button type="button" aria-label="이전 해" :disabled="draftYear <= MIN_YEAR" @click="shiftYear(-1)"><CalendarIcon name="chevron-left" /></button>
        <strong>{{ draftYear }}년</strong>
        <button type="button" aria-label="다음 해" :disabled="draftYear >= MAX_YEAR" @click="shiftYear(1)"><CalendarIcon name="chevron-right" /></button>
      </div>
      <div class="integrated-calendar__monthpicker-months">
        <button v-for="m in 12" :key="m" type="button" data-month :aria-pressed="draftYear === year && m === month" @click="pick(m)">{{ m }}월</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.integrated-calendar__monthpicker { position: relative; }
.integrated-calendar .integrated-calendar__month-trigger { display: inline-flex; align-items: center; gap: .375rem; min-height: 2.75rem; padding: .25rem .75rem; font-size: 1.125rem; font-weight: 700; white-space: nowrap; }
.integrated-calendar .integrated-calendar__monthpicker-panel { position: absolute; z-index: 8; top: calc(100% + .25rem); left: 50%; transform: translateX(-50%); display: grid; gap: .5rem; width: min(17rem, calc(100vw - 2rem)); padding: .75rem; border: 1px solid var(--ic-line); background: var(--ic-surface); box-shadow: 0 .5rem 1.5rem rgba(0, 0, 0, .22); }
.integrated-calendar__monthpicker-year { display: flex; align-items: center; justify-content: space-between; gap: .5rem; }
.integrated-calendar__monthpicker-year button { display: inline-flex; align-items: center; justify-content: center; min-width: 2.75rem; min-height: 2.75rem; padding: 0; }
.integrated-calendar__monthpicker-months { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: .375rem; }
.integrated-calendar .integrated-calendar__monthpicker-months button { min-height: 2.75rem; padding: 0; }
.integrated-calendar .integrated-calendar__monthpicker-months button[aria-pressed='true'] { background: var(--ic-accent) !important; color: var(--ic-accent-contrast) !important; -webkit-text-fill-color: var(--ic-accent-contrast) !important; border-color: var(--ic-accent) !important; }
</style>
