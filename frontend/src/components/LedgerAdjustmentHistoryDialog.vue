<script setup>
defineProps({
  entry: { type: Object, required: true },
  items: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  busy: { type: Boolean, default: false },
  error: { type: String, default: '' },
})
const emit = defineEmits(['close', 'reload', 'revert'])
const money = value => new Intl.NumberFormat('ko-KR', { style: 'currency', currency: 'KRW', maximumFractionDigits: 2 }).format(Number(value || 0))
const label = item => item.adjustmentType === 'REFUND' ? '환불' : '결제 취소'
</script>

<template>
  <Teleport to="body">
    <div class="history-overlay" @click.self="!busy && emit('close')" @keydown.esc.stop="!busy && emit('close')">
      <section class="panel history-dialog" role="dialog" aria-modal="true" aria-labelledby="adjustment-history-title">
        <div class="panel__header history-header"><div><h2 id="adjustment-history-title">취소·환불 처리 내역</h2><p>{{ entry.title }} · 원거래 {{ money(entry.amount) }}</p></div><button class="button button--ghost" type="button" :disabled="busy" @click="emit('close')">닫기</button></div>
        <p>원거래와 처리 기록은 보존되어 일반 수정·삭제할 수 없습니다. 잘못 기록한 처리는 되돌릴 수 있으며, 되돌린 내역도 남습니다.</p>
        <p v-if="error" class="feedback feedback--error" role="alert">{{ error }}</p>
        <p v-if="loading" role="status">처리 내역을 불러오는 중입니다…</p>
        <p v-else-if="!items.length">표시할 처리 내역이 없습니다.</p>
        <ol v-else class="history-list">
          <li v-for="item in items" :key="item.id" class="history-item">
            <div><strong>{{ label(item) }}<template v-if="item.adjustmentVoidedAt"> · 되돌림</template></strong> <span>{{ item.entryDate }} {{ item.entryTime || '' }}</span></div>
            <p v-if="item.adjustmentType === 'REFUND'">기준 {{ money(item.adjustmentPrincipal) }} − 차감 {{ money(item.adjustmentDeduction) }} + 추가 {{ money(item.adjustmentExtra) }} = 수령 {{ money(item.amount) }} · {{ item.paymentMethodName }}</p>
            <p v-else>결제 취소 금액 {{ money(item.adjustmentPrincipal) }}</p>
            <p v-if="item.memo">{{ item.memo }}</p>
            <button v-if="!item.adjustmentVoidedAt" type="button" class="button button--ghost" :disabled="busy" @click="emit('revert', item)">이 처리 되돌리기</button>
          </li>
        </ol>
        <button type="button" class="button button--ghost" :disabled="loading || busy" @click="emit('reload')">새로고침</button>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.history-overlay { position: fixed; inset: 0; z-index: 2600; display: grid; place-items: center; padding: 1rem; background: rgb(0 0 0 / 65%); }
.history-dialog { width: min(100%, 43rem); max-height: calc(100dvh - 2rem); overflow-y: auto; }
.history-header { display: flex; justify-content: space-between; align-items: start; gap: 1rem; }
.history-list { display: grid; gap: .6rem; list-style: none; padding: 0; }
.history-item { border: 1px solid currentColor; padding: .75rem; overflow-wrap: anywhere; }
.history-item p { margin: .45rem 0; }
.history-item button { min-height: 2.75rem; }
</style>
