<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { pushEscape } from '../../lib/integratedCalendarEscape.js'
import CalendarIcon from './CalendarIcon.vue'

const props = defineProps({ text: { type: String, required: true } })
const hovered = ref(false), focused = ref(false), pinned = ref(false)
const open = computed(() => hovered.value || focused.value || pinned.value)
const id = `integrated-calendar-tip-${Math.random().toString(36).slice(2, 9)}`
const root = ref(null)
let popEscape = null
watch(open, value => {
  if (value && !popEscape) popEscape = pushEscape(() => { hovered.value = focused.value = pinned.value = false })
  else if (!value && popEscape) { popEscape(); popEscape = null }
})

function onFocus(event) { focused.value = event.target.matches(':focus-visible') }
function onKeydown(event) {
  if (event.key !== 'Escape' || !open.value) return
  event.preventDefault(); event.stopPropagation()
  hovered.value = focused.value = pinned.value = false
}
function onOutside(event) {
  if (root.value && !root.value.contains(event.target)) pinned.value = false
}
function toggle() {
  pinned.value = !pinned.value
  if (pinned.value) document.addEventListener('pointerdown', onOutside)
  else document.removeEventListener('pointerdown', onOutside)
}
onBeforeUnmount(() => { popEscape?.(); document.removeEventListener('pointerdown', onOutside) })
</script>

<template>
  <span ref="root" class="integrated-calendar__infotip" @mouseenter="hovered = true" @mouseleave="hovered = false">
    <button type="button" class="integrated-calendar__infotip-button" :aria-label="`도움말: ${props.text}`" :aria-describedby="open ? id : undefined" :aria-expanded="open" @click="toggle" @focus="onFocus" @blur="focused = false" @keydown="onKeydown">
      <CalendarIcon name="info" :size="16" />
    </button>
    <span v-if="open" :id="id" role="tooltip" class="integrated-calendar__infotip-bubble">{{ props.text }}</span>
  </span>
</template>

<style scoped>
.integrated-calendar__infotip { position: relative; display: inline-flex; align-items: center; }
/* The visual icon is 1rem; the hit area is 2.75rem without affecting layout. */
.integrated-calendar .integrated-calendar__infotip-button { display: inline-flex; align-items: center; justify-content: center; box-sizing: border-box; width: 2.75rem; height: 2.75rem; margin: -.875rem; padding: 0; border: 0 !important; background: transparent !important; color: var(--ic-text-soft) !important; -webkit-text-fill-color: currentColor !important; cursor: help; }
.integrated-calendar .integrated-calendar__infotip-bubble { position: absolute; z-index: 5; top: calc(100% + .5rem); left: 0; width: max-content; max-width: min(18rem, 70vw); padding: .5rem .625rem; border: 1px solid var(--ic-line); background: var(--ic-surface) !important; color: var(--ic-text) !important; -webkit-text-fill-color: var(--ic-text) !important; font-size: .8125rem; line-height: 1.4; font-weight: 400; text-align: left; white-space: normal; box-shadow: 0 .25rem 1rem rgba(0, 0, 0, .18); }
</style>
