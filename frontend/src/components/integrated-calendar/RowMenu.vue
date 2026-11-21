<script setup>
import { nextTick, onBeforeUnmount, ref } from 'vue'
import { pushEscape } from '../../lib/integratedCalendarEscape.js'
import CalendarIcon from './CalendarIcon.vue'

const props = defineProps({ label: { type: String, required: true }, items: { type: Array, required: true } })
const emit = defineEmits(['select'])
const open = ref(false)
const root = ref(null), trigger = ref(null), menu = ref(null)
let popEscape = null

const buttons = () => [...(menu.value?.querySelectorAll('[role="menuitem"]') || [])]
function onOutside(event) {
  if (root.value && !root.value.contains(event.target)) close(false)
}
async function show() {
  open.value = true
  popEscape = pushEscape(() => close())
  document.addEventListener('pointerdown', onOutside)
  await nextTick()
  buttons()[0]?.focus()
}
function close(restoreFocus = true) {
  open.value = false
  popEscape?.(); popEscape = null
  document.removeEventListener('pointerdown', onOutside)
  if (restoreFocus) trigger.value?.focus()
}
function toggle() { open.value ? close() : show() }
function choose(item) {
  close()
  emit('select', item.action)
}
function onKeydown(event) {
  const list = buttons()
  const index = list.indexOf(document.activeElement)
  if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); close() }
  else if (event.key === 'Tab') close(false)
  else if (event.key === 'ArrowDown') { event.preventDefault(); list[(index + 1) % list.length]?.focus() }
  else if (event.key === 'ArrowUp') { event.preventDefault(); list[(index - 1 + list.length) % list.length]?.focus() }
  else if (event.key === 'Home') { event.preventDefault(); list[0]?.focus() }
  else if (event.key === 'End') { event.preventDefault(); list.at(-1)?.focus() }
}
onBeforeUnmount(() => { popEscape?.(); document.removeEventListener('pointerdown', onOutside) })
</script>

<template>
  <span ref="root" class="integrated-calendar__rowmenu">
    <button ref="trigger" type="button" class="integrated-calendar__rowmenu-trigger" aria-haspopup="menu" :aria-expanded="open" :aria-label="`추가 작업: ${props.label}`" @click="toggle">
      <CalendarIcon name="more" :size="18" />
    </button>
    <span v-if="open" ref="menu" role="menu" class="integrated-calendar__rowmenu-panel" :aria-label="`추가 작업: ${props.label}`" @keydown="onKeydown">
      <button v-for="item in items" :key="item.label" type="button" role="menuitem" class="integrated-calendar__rowmenu-item" @click="choose(item)">{{ item.label }}</button>
    </span>
  </span>
</template>

<style scoped>
.integrated-calendar__rowmenu { position: relative; display: inline-flex; }
.integrated-calendar .integrated-calendar__rowmenu-trigger { display: inline-flex; align-items: center; justify-content: center; box-sizing: border-box; min-width: 2.75rem; min-height: 2.75rem; padding: 0; border: 0 !important; background: transparent !important; color: var(--ic-text) !important; -webkit-text-fill-color: currentColor !important; cursor: pointer; }
.integrated-calendar .integrated-calendar__rowmenu-panel { position: absolute; z-index: 6; right: 0; top: 100%; display: grid; min-width: 11rem; padding: .25rem; border: 1px solid var(--ic-line); background: var(--ic-surface) !important; box-shadow: 0 .25rem 1rem rgba(0, 0, 0, .2); }
.integrated-calendar .integrated-calendar__rowmenu-item { display: block; box-sizing: border-box; min-height: 2.75rem; padding: .5rem .75rem; border: 0 !important; background: transparent !important; color: var(--ic-text) !important; -webkit-text-fill-color: var(--ic-text) !important; font: inherit; text-align: left; cursor: pointer; white-space: nowrap; }
.integrated-calendar .integrated-calendar__rowmenu-item:hover, .integrated-calendar .integrated-calendar__rowmenu-item:focus-visible { background: var(--ic-accent-soft) !important; }
</style>
