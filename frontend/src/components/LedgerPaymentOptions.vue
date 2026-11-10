<script setup>
import { computed } from 'vue'

const props = defineProps({
  form: { type: Object, required: true },
  methods: { type: Array, default: () => [] },
  editing: { type: Boolean, default: false },
})
const canSplit = computed(() => !props.editing && props.form.entryType === 'EXPENSE'
  && props.form.currencyMode !== 'FOREIGN' && !props.form.travelRecordId)
const selectedCard = computed(() => props.methods.some(method => String(method.id) === String(props.form.paymentMethodId) && method.kind === 'CARD'))
const splitSum = computed(() => props.form.splitPayments.reduce((sum, row) => sum + (Number(row.amount) || 0), 0))
const remaining = computed(() => (Number(props.form.amount) || 0) - splitSum.value)
const currency = value => new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 2 }).format(value)
const isCard = row => props.methods.some(method => String(method.id) === String(row.paymentMethodId) && method.kind === 'CARD')

function setSplit(event) {
  props.form.splitMode = event.target.checked
  if (props.form.splitMode && props.form.splitPayments.length < 2) {
    props.form.splitPayments = [
      { paymentMethodId: String(props.form.paymentMethodId || ''), amount: '', installmentMonths: '' },
      { paymentMethodId: '', amount: '', installmentMonths: '' },
    ]
  }
}
function addPart() {
  if (props.form.splitPayments.length < 10) props.form.splitPayments.push({ paymentMethodId: '', amount: '', installmentMonths: '' })
}
function changeMethod(row) {
  if (!isCard(row)) row.installmentMonths = ''
}
</script>

<template>
  <details class="payment-options" :open="editing && !!form.installmentMonths">
    <summary>결제 옵션 <small v-if="form.splitMode">· 분할 결제</small><small v-else-if="form.installmentMonths">· {{ form.installmentMonths }}개월 할부</small></summary>
    <div class="payment-options__body">
      <label class="payment-options__toggle">
        <input type="checkbox" :checked="form.splitMode" :disabled="!canSplit && !form.splitMode" @change="setSplit" />
        여러 수단으로 분할 결제
      </label>
      <p v-if="!canSplit && !editing" class="payment-options__hint">분할 결제는 원화 지출에서 사용할 수 있으며 여행 기록과 직접 연결할 수 없습니다.</p>
      <p v-if="editing && form.splitPaymentGroupId" class="payment-options__hint">분할 결제의 각 수단은 개별 거래로 수정·환불·취소합니다.</p>
      <template v-if="form.splitMode">
        <p class="payment-options__hint">총금액을 수단별로 나눠 입력하세요. 각 내역은 같은 묶음의 개별 거래로 저장됩니다.</p>
        <div v-for="(row, index) in form.splitPayments" :key="index" class="payment-options__part">
          <label><span>수단 {{ index + 1 }}</span><select v-model="row.paymentMethodId" :aria-label="`결제수단 ${index + 1}`" @change="changeMethod(row)"><option value="">선택</option><option v-for="method in methods" :key="method.id" :value="String(method.id)">{{ method.name }}</option></select></label>
          <label><span>금액</span><input v-model="row.amount" type="number" inputmode="decimal" min="0.01" step="0.01" :aria-label="`수단 ${index + 1} 금액`" placeholder="0" /></label>
          <label v-if="isCard(row)"><span>할부 개월</span><input v-model="row.installmentMonths" type="number" inputmode="numeric" min="2" max="60" :aria-label="`수단 ${index + 1} 할부 개월`" placeholder="일시불" /></label>
          <button type="button" class="button button--ghost" :disabled="form.splitPayments.length <= 2" :aria-label="`결제수단 ${index + 1} 삭제`" @click="form.splitPayments.splice(index, 1)">삭제</button>
        </div>
        <div class="payment-options__footer"><button type="button" class="button button--secondary" :disabled="form.splitPayments.length >= 10" @click="addPart">수단 추가</button><span>입력 {{ currency(splitSum) }}원 · 남은 금액 {{ currency(remaining) }}원</span></div>
      </template>
      <label v-else-if="selectedCard" class="payment-options__installment"><span>카드 할부 개월</span><input v-model="form.installmentMonths" type="number" inputmode="numeric" min="2" max="60" placeholder="일시불" /><small>표시 정보만 저장하며 거래 금액은 나누지 않습니다.</small></label>
      <p v-else class="payment-options__hint">{{ form.installmentMonths ? '카드가 아닌 수단으로 저장하면 기존 할부 표시가 해제됩니다.' : '할부 개월은 카드 결제수단을 선택하면 입력할 수 있습니다.' }}</p>
    </div>
  </details>
</template>

<style scoped>
.payment-options { border: 1px solid var(--border, #aab89d); padding: .45rem .6rem; }
.payment-options summary { cursor: pointer; font-weight: 700; }
.payment-options__body { display: grid; gap: .5rem; padding-top: .6rem; }
.payment-options__toggle { display: flex; justify-content: flex-start; align-items: center; gap: .5rem; text-align: left; }
.payment-options__toggle input[type='checkbox'] { flex: 0 0 1.1rem; width: 1.1rem; height: 1.1rem; min-height: 0; margin: 0; }
.payment-options__hint { margin: 0; font-size: .78rem; line-height: 1.4; }
.payment-options__part { display: flex; flex-wrap: wrap; gap: .4rem; align-items: end; }
.payment-options__part label, .payment-options__installment { display: grid; gap: .2rem; flex: 1 1 5rem; min-width: 0; font-size: .8rem; }
.payment-options__part input, .payment-options__part select, .payment-options__installment input { box-sizing: border-box; width: 100%; min-width: 0; min-height: 2.5rem; }
.payment-options__part button, .payment-options__footer button { flex: 0 0 auto; width: auto; min-width: 2.75rem; min-height: 2.5rem; }
.payment-options__footer { display: flex; flex-wrap: wrap; gap: .5rem; align-items: center; justify-content: space-between; font-size: .8rem; }
.payment-options__installment { max-width: 16rem; }
:global(:root[data-layout-mode='mobile']) :global(.household-entry-panel) .payment-options__part .button,
:global(:root[data-layout-mode='mobile']) :global(.household-entry-panel) .payment-options__footer .button { width: auto; min-width: 2.75rem; }
</style>
