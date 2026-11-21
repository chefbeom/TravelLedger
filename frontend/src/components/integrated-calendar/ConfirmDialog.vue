<script setup>
import BaseDialog from './BaseDialog.vue'

defineProps({ title: { type: String, required: true }, message: { type: String, default: '' }, confirmLabel: { type: String, default: '삭제' }, busy: { type: Boolean, default: false } })
const emit = defineEmits(['confirm', 'close'])
</script>

<template>
  <BaseDialog role="alertdialog" variant="center" labelledby="integrated-calendar-confirm-title" initial-focus="[data-initial-focus]" :busy="busy" @close="emit('close')">
    <div class="integrated-calendar__dialog-body">
      <h2 id="integrated-calendar-confirm-title">{{ title }}</h2>
      <p>{{ message }}</p>
      <div class="integrated-calendar__dialog-actions">
        <button type="button" class="button button--ghost" data-initial-focus :disabled="busy" @click="emit('close')">취소</button>
        <button type="button" class="button button--danger" :disabled="busy" @click="emit('confirm')">{{ confirmLabel }}</button>
      </div>
    </div>
  </BaseDialog>
</template>

<style scoped>
.integrated-calendar__dialog-body { display: grid; gap: 1rem; padding: 1.25rem; }
.integrated-calendar__dialog-body h2, .integrated-calendar__dialog-body p { margin: 0; }
.integrated-calendar__dialog-body h2 { font-size: 1.125rem; }
.integrated-calendar__dialog-actions { display: flex; justify-content: flex-end; gap: .5rem; flex-wrap: wrap; }
.integrated-calendar__dialog-actions .button { min-height: 2.75rem; }
</style>
