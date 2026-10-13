<script setup>
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
const props = defineProps({ open: Boolean, title: String, busy: Boolean })
const emit = defineEmits(['close'])
const dialog = ref(null)
let trigger = null
watch(() => props.open, async value => {
  await nextTick()
  if (value && dialog.value && !dialog.value.open) {
    trigger = document.activeElement
    dialog.value.showModal()
    dialog.value.querySelector('input:not([type="hidden"]), textarea, select, button')?.focus()
  } else if (!value && dialog.value?.open) {
    dialog.value.close()
    trigger?.isConnected && trigger.focus()
  }
}, { immediate: true })
function close() { if (!props.busy) emit('close') }
function outside(event) {
  if (event.target !== dialog.value) return
  const r = dialog.value.getBoundingClientRect()
  if (event.clientX < r.left || event.clientX > r.right || event.clientY < r.top || event.clientY > r.bottom) close()
}
onBeforeUnmount(() => { dialog.value?.close(); trigger?.isConnected && trigger.focus() })
</script>
<template>
  <dialog ref="dialog" class="record-map-dialog" data-map-photo-detail="true" role="dialog" aria-modal="true" aria-labelledby="record-map-dialog-title" @cancel.prevent="close" @keydown.esc.stop @click="outside">
    <header><h2 id="record-map-dialog-title">{{ title }}</h2><button class="button button--ghost" data-modal-close type="button" :disabled="busy" aria-label="창 닫기" @click="close">닫기</button></header>
    <fieldset class="record-map-dialog__body" :disabled="busy"><slot /></fieldset>
  </dialog>
</template>
<style scoped>
.record-map-dialog { padding: 0; width: min(820px, calc(100vw - 24px)); max-height: min(90dvh, 960px); border: 1px solid var(--line, #536468); color: var(--text, inherit); background: var(--panel-bg, #20292c); overflow: auto; }
.record-map-dialog::backdrop { background: #0009; }
header { position: sticky; top: 0; z-index: 2; display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 16px; background: inherit; border-bottom: 1px solid var(--line, #536468); }
h2 { margin: 0; font-size: 1.15rem; overflow-wrap: anywhere; min-width: 0; }
header button { flex-shrink: 0; }
.record-map-dialog__body { display: block; min-width: 0; margin: 0; border: 0; padding: 18px; }
@media(max-width: 600px) { .record-map-dialog__body { padding: 12px; } }
</style>
