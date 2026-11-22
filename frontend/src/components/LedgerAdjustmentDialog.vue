<script setup>
import { computed } from 'vue'
import LedgerAdjustmentForm from './LedgerAdjustmentForm.vue'

const props = defineProps({
  entry: { type: Object, required: true },
  mode: { type: String, required: true },
  categories: { type: Array, default: () => [] },
  paymentMethods: { type: Array, default: () => [] },
  busy: { type: Boolean, default: false },
  error: { type: String, default: '' },
})
const emit = defineEmits(['close', 'submit'])
const isRefund = computed(() => props.mode === 'REFUND')
const formatMoney = value => new Intl.NumberFormat('ko-KR', { style: 'currency', currency: 'KRW', maximumFractionDigits: 2 }).format(Number(value || 0))
</script>

<template>
  <Teleport to="body">
    <div class="adjustment-overlay" @click.self="!busy && emit('close')" @keydown.esc.stop="!busy && emit('close')">
      <section class="panel adjustment-dialog" role="dialog" aria-modal="true" aria-labelledby="adjustment-title">
        <div class="panel__header">
          <div><h2 id="adjustment-title">{{ isRefund ? '환불 기록' : '결제 취소 기록' }}</h2><p>{{ entry.title }} · {{ formatMoney(entry.amount) }}</p></div>
          <button type="button" class="button button--ghost" :disabled="busy" @click="emit('close')">닫기</button>
        </div>
        <LedgerAdjustmentForm
          :entry="entry" :mode="mode" :categories="categories" :payment-methods="paymentMethods"
          :busy="busy" :error="error" @submit="emit('submit', $event)" @cancel="emit('close')"
        />
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.adjustment-overlay { position: fixed; inset: 0; z-index: 2600; display: grid; place-items: center; padding: 1rem; background: rgb(0 0 0 / 65%); }
.adjustment-dialog { width: min(100%, 43rem); max-height: calc(100dvh - 2rem); overflow-y: auto; }
.adjustment-dialog .panel__header { display: flex; gap: 1rem; justify-content: space-between; align-items: start; }
</style>
