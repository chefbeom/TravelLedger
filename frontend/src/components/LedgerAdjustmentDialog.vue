<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { toIsoDate } from '../lib/format'

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
const groups = computed(() => props.categories.filter(group => group.active !== false && group.entryType === 'INCOME'))
const methods = computed(() => props.paymentMethods.filter(method => method.active !== false && method.name !== '-'))
const remaining = computed(() => Math.max(0, Number(props.entry.amount || 0) - Number(props.entry.refundedPrincipal || 0)))
const dateInput = ref(null)
const form = reactive({
  date: [toIsoDate(new Date()), props.entry.entryDate].sort().at(-1), time: '',
  principal: String(remaining.value), deduction: '0', extra: '0',
  groupId: String(groups.value[0]?.id || ''), paymentId: String(methods.value.find(item => item.kind === 'CASH')?.id || methods.value[0]?.id || ''), memo: '',
})
const requestKey = ref('')
onMounted(() => {
  if (globalThis.crypto?.randomUUID) requestKey.value = globalThis.crypto.randomUUID()
  else if (globalThis.crypto?.getRandomValues) {
    const bytes = globalThis.crypto.getRandomValues(new Uint8Array(16))
    bytes[6] = (bytes[6] & 15) | 64
    bytes[8] = (bytes[8] & 63) | 128
    const hex = [...bytes].map(byte => byte.toString(16).padStart(2, '0')).join('')
    requestKey.value = `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
  }
  dateInput.value?.focus()
})
const received = computed(() => Number(form.principal || 0) - Number(form.deduction || 0) + Number(form.extra || 0))
const amountValid = value => /^\d+(?:\.\d{1,2})?$/.test(String(value)) && Number(value) <= 9999999999999.99
const validationError = computed(() => {
  if (!requestKey.value) return '안전한 요청 번호를 만들 수 없습니다. HTTPS로 접속한 뒤 다시 시도해 주세요.'
  if (!form.date || form.date < props.entry.entryDate) return '처리일은 원거래 날짜 이후여야 합니다.'
  if (!isRefund.value) return ''
  if (![form.principal, form.deduction, form.extra].every(amountValid) || Number(form.principal) <= 0) return '금액은 0 이상, 소수 둘째 자리까지 입력해 주세요.'
  if (Number(form.principal) > remaining.value) return '환불 기준금액은 남은 원거래 금액을 초과할 수 없습니다.'
  if (received.value < 0 || received.value > 9999999999999.99) return '실제 돌려받을 금액을 확인해 주세요.'
  if (!form.groupId || !form.paymentId) return '수입 대분류와 실제 수령 수단을 선택해 주세요.'
  return ''
})
const formatMoney = value => new Intl.NumberFormat('ko-KR', { style: 'currency', currency: 'KRW', maximumFractionDigits: 2 }).format(Number(value || 0))
function submit() {
  if (props.busy || validationError.value) return
  emit('submit', {
    type: props.mode, date: form.date, time: form.time || null, memo: form.memo, requestKey: requestKey.value,
    ...(isRefund.value ? { principal: form.principal, deduction: form.deduction, extra: form.extra,
      incomeCategoryGroupId: Number(form.groupId), receivingPaymentMethodId: Number(form.paymentId) } : {}),
  })
}
</script>

<template>
  <Teleport to="body">
    <div class="adjustment-overlay" @click.self="!busy && emit('close')" @keydown.esc.stop="!busy && emit('close')">
      <section class="panel adjustment-dialog" role="dialog" aria-modal="true" aria-labelledby="adjustment-title">
        <div class="panel__header">
          <div><h2 id="adjustment-title">{{ isRefund ? '환불 기록' : '결제 취소 기록' }}</h2><p>{{ entry.title }} · {{ formatMoney(entry.amount) }}</p></div>
          <button type="button" class="button button--ghost" :disabled="busy" @click="emit('close')">닫기</button>
        </div>
        <p v-if="isRefund" class="adjustment-note">원 지출은 유지하고, 실제 수령액을 처리일의 수입으로 기록합니다. 기존에 다른 사람에게 공유한 기록은 자동 변경되지 않습니다.</p>
        <p v-else class="adjustment-note">결제 전액이 청구되지 않은 경우에 사용합니다. 원거래와 취소 기록은 남고, 원거래 지출은 해당 원거래 날짜의 집계에서 제외됩니다. 기존 공유 내역은 자동 변경되지 않습니다.</p>
        <p v-if="entry.foreignCurrencyCode && isRefund" class="adjustment-note">외화 원거래도 원화 환산 금액을 기준으로 처리합니다. 실제 수령액을 원화로 입력해 주세요.</p>
        <p v-if="entry.travelRecordId && isRefund" class="adjustment-note">환불은 가계부에 기록되며 연결된 여행 지출은 자동 변경되지 않습니다.</p>
        <form class="adjustment-form" @submit.prevent="submit">
          <div class="adjustment-grid">
            <label class="field"><span class="field__label">{{ isRefund ? '환불받은 날짜' : '결제 취소 날짜' }}</span><input ref="dateInput" v-model="form.date" type="date" :min="entry.entryDate" required /></label>
            <label class="field"><span class="field__label">시간 (선택)</span><input v-model="form.time" type="time" /></label>
          </div>
          <template v-if="isRefund">
            <p class="adjustment-note">아직 환불 처리하지 않은 원거래 금액: <strong>{{ formatMoney(remaining) }}</strong></p>
            <div class="adjustment-grid">
              <label class="field"><span class="field__label">환불 기준금액 (원)</span><input v-model="form.principal" type="number" min="0" step="0.01" inputmode="decimal" required /></label>
              <label class="field"><span class="field__label">차감액 (원)</span><input v-model="form.deduction" type="number" min="0" step="0.01" inputmode="decimal" required /></label>
              <label class="field"><span class="field__label">추가 지급액 (원)</span><input v-model="form.extra" type="number" min="0" step="0.01" inputmode="decimal" required /></label>
            </div>
            <p class="adjustment-total">실제 수령액 = 기준금액 − 차감액 + 추가 지급액 <strong>{{ formatMoney(received) }}</strong></p>
            <div class="adjustment-grid">
              <label class="field"><span class="field__label">수입 대분류</span><select v-model="form.groupId" required><option value="">선택</option><option v-for="group in groups" :key="group.id" :value="String(group.id)">{{ group.name }}</option></select></label>
              <label class="field"><span class="field__label">실제 수령 수단</span><select v-model="form.paymentId" required><option value="">선택</option><option v-for="method in methods" :key="method.id" :value="String(method.id)">{{ method.name }}</option></select></label>
            </div>
            <p v-if="!groups.length || !methods.length" class="adjustment-note">수입 대분류와 수령 수단을 분류 관리에서 먼저 등록해 주세요.</p>
          </template>
          <label class="field"><span class="field__label">메모 (선택)</span><textarea v-model="form.memo" maxlength="500" rows="2" /></label>
          <p v-if="error" class="feedback feedback--error" role="alert">{{ error }}</p>
          <p v-else-if="validationError" class="adjustment-note" role="status">{{ validationError }}</p>
          <div class="adjustment-actions"><button type="button" class="button button--ghost" :disabled="busy" @click="emit('close')">닫기</button><button type="submit" class="button button--primary" :disabled="busy || !!validationError">{{ busy ? '저장 중…' : (isRefund ? '환불 기록 저장' : '결제 취소 저장') }}</button></div>
        </form>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.adjustment-overlay { position: fixed; inset: 0; z-index: 2600; display: grid; place-items: center; padding: 1rem; background: rgb(0 0 0 / 65%); }
.adjustment-dialog { width: min(100%, 43rem); max-height: calc(100dvh - 2rem); overflow-y: auto; }
.adjustment-dialog .panel__header { display: flex; gap: 1rem; justify-content: space-between; align-items: start; }
.adjustment-form { display: grid; gap: .8rem; }
.adjustment-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 11rem), 1fr)); gap: .7rem; }
.adjustment-grid .field { min-width: 0; }
.adjustment-grid input, .adjustment-grid select, textarea { width: 100%; box-sizing: border-box; }
.adjustment-note { margin: .4rem 0; line-height: 1.5; }
.adjustment-total { display: flex; gap: .6rem; flex-wrap: wrap; justify-content: space-between; }
.adjustment-actions { display: flex; justify-content: flex-end; gap: .5rem; }
.adjustment-actions button { min-height: 2.75rem; }
</style>
