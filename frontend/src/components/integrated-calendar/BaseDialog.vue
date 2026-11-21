<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { pushEscape } from '../../lib/integratedCalendarEscape.js'

const props = defineProps({
  labelledby: { type: String, default: null },
  label: { type: String, default: null },
  variant: { type: String, default: 'center' }, // center | drawer | sheet | fullscreen
  role: { type: String, default: 'dialog' }, // dialog | alertdialog
  busy: { type: Boolean, default: false },
  closeOnBackdrop: { type: Boolean, default: true },
  initialFocus: { type: String, default: null }, // CSS selector
})
const emit = defineEmits(['close'])
const el = ref(null)
let trigger = null, popEscape = null
const FOCUSABLE = 'button:not(:disabled), [href], input:not(:disabled), select:not(:disabled), textarea:not(:disabled), summary, [tabindex]:not([tabindex="-1"])'

onMounted(async () => {
  trigger = document.activeElement
  popEscape = pushEscape(() => { if (!props.busy) emit('close') })
  el.value.showModal()
  await nextTick()
  const target = (props.initialFocus && el.value.querySelector(props.initialFocus)) || el.value.querySelector('[autofocus]') || el.value.querySelector(FOCUSABLE)
  target?.focus()
})
onBeforeUnmount(() => {
  popEscape?.()
  if (el.value?.open) el.value.close()
  if (trigger?.isConnected) trigger.focus({ preventScroll: true })
})
// With stacked modal dialogs the browser may route Esc to a dialog that is not on top, so the
// topmost dialog (the one holding focus) handles Esc itself and the native cancel is suppressed.
function onKeydown(event) {
  if (event.key !== 'Escape') return
  event.preventDefault()
  if (!props.busy) emit('close')
}
function onCancel(event) {
  event.preventDefault()
  if (!props.busy) emit('close')
}
function onClick(event) {
  if (!props.closeOnBackdrop || props.busy || event.target !== el.value) return
  const rect = el.value.getBoundingClientRect()
  if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) emit('close')
}
</script>

<template>
  <dialog ref="el" :class="['integrated-calendar__dialog', `integrated-calendar__dialog--${variant}`]" :role="role" aria-modal="true"
    :aria-labelledby="labelledby" :aria-label="label" @cancel="onCancel" @keydown="onKeydown" @click="onClick">
    <slot />
  </dialog>
</template>

<style scoped>
.integrated-calendar__dialog { box-sizing: border-box; padding: 0; border: 1px solid var(--ic-line, #a3b18a); background: var(--ic-panel, #f4f1e8); color: var(--ic-text, inherit); overflow: auto; max-width: 100vw; }
.integrated-calendar__dialog::backdrop { background: var(--ic-backdrop, rgba(52, 78, 65, 0.52)); }
.integrated-calendar__dialog--center { width: min(32rem, calc(100vw - 2rem)); max-height: min(90dvh, 48rem); }
.integrated-calendar__dialog--drawer { margin: 0 0 0 auto; height: 100dvh; max-height: 100dvh; width: min(26rem, 100vw); max-width: 100vw; border-width: 0 0 0 1px; }
.integrated-calendar__dialog--sheet { margin: auto 0 0 0; width: 100vw; max-width: 100vw; max-height: 85dvh; border-width: 1px 0 0; }
.integrated-calendar__dialog--fullscreen { margin: 0; width: 100vw; height: 100dvh; max-width: 100vw; max-height: 100dvh; border-width: 0; overflow: hidden; }
@media (prefers-reduced-motion: no-preference) {
  .integrated-calendar__dialog--drawer[open] { animation: ic-slide-left .18s ease-out; }
  .integrated-calendar__dialog--sheet[open] { animation: ic-slide-up .18s ease-out; }
  .integrated-calendar__dialog--center[open] { animation: ic-fade .15s ease-out; }
}
@keyframes ic-slide-left { from { transform: translateX(2rem); opacity: 0; } to { transform: none; opacity: 1; } }
@keyframes ic-slide-up { from { transform: translateY(2rem); opacity: 0; } to { transform: none; opacity: 1; } }
@keyframes ic-fade { from { opacity: 0; } to { opacity: 1; } }
</style>
