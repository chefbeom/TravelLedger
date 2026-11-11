<script setup>
import { computed, ref, watch } from 'vue'
import { reconcileSplitAmounts } from '../lib/splitPayment'

const props = defineProps({
  form: { type: Object, required: true },
  methods: { type: Array, default: () => [] },
  editing: { type: Boolean, default: false },
})
const canSplit = computed(() => !props.editing && props.form.entryType === 'EXPENSE'
  && props.form.currencyMode !== 'FOREIGN' && !props.form.travelRecordId)
const selectedCard = computed(() => props.methods.some(method => String(method.id) === String(props.form.paymentMethodId) && method.kind === 'CARD'))
const splitSum = computed(() => props.form.splitPayments.reduce((sum, row) => sum + Math.round((Number(row.amount) || 0) * 100), 0) / 100)
const remaining = computed(() => Math.max(0, Math.round(((Number(props.form.amount) || 0) - splitSum.value) * 100) / 100))
const currency = value => new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 2 }).format(value)
const isCard = row => props.methods.some(method => String(method.id) === String(row.paymentMethodId) && method.kind === 'CARD')
const newPart = paymentMethodId => ({ paymentMethodId, amount: '', installmentEnabled: false, installmentMonths: '', installmentInterestType: '' })
const amountNotice = ref('')

function reconcileAmounts(changedIndex = -1, autoLast = true) {
  const rows = props.form.splitPayments
  const before = rows.map(row => row.amount)
  const result = reconcileSplitAmounts(props.form.amount, before, { changedIndex, autoLast })
  result.amounts.forEach((value, index) => { rows[index].amount = value })
  if (changedIndex >= 0 && before[changedIndex] !== result.amounts[changedIndex]) {
    amountNotice.value = '총 금액을 넘지 않도록 입력 금액을 조정했습니다.'
  } else if (changedIndex < 0 && result.adjusted && before.slice(0, -1).some((value, index) => value !== result.amounts[index])) {
    amountNotice.value = '변경된 총 금액에 맞춰 결제 금액을 조정했습니다.'
  } else amountNotice.value = ''
}

function changeAmount(event, index) {
  props.form.splitPayments[index].amount = event.target.value
  reconcileAmounts(index, index !== props.form.splitPayments.length - 1)
  event.target.value = props.form.splitPayments[index].amount
}

watch(() => props.form.amount, () => { if (props.form.splitMode) reconcileAmounts() })

function setSplit(event) {
  props.form.splitMode = event.target.checked
  if (props.form.splitMode && props.form.splitPayments.length < 2) {
    props.form.splitPayments = [newPart(String(props.form.paymentMethodId || '')), newPart('')]
  }
  if (props.form.splitMode) reconcileAmounts()
}
function addPart() {
  if (props.form.splitPayments.length < 10) { props.form.splitPayments.push(newPart('')); reconcileAmounts() }
}
function removePart(index) {
  if (props.form.splitPayments.length > 2) { props.form.splitPayments.splice(index, 1); reconcileAmounts() }
}
function changeMethod(row) {
  if (!isCard(row) && row.installmentEnabled) {
    row.installmentEnabled = false
    if (!props.form.splitPayments.some(part => part.installmentEnabled)) props.form.installmentEnabled = false
  }
}
function setInstallment(event) {
  props.form.installmentEnabled = event.target.checked
  if (!event.target.checked && props.form.splitMode) props.form.splitPayments.forEach(row => { row.installmentEnabled = false })
}
function changePartInstallment(row, event) {
  row.installmentEnabled = event.target.checked
  if (event.target.checked) props.form.installmentEnabled = true
  else if (!props.form.splitPayments.some(part => part.installmentEnabled)) props.form.installmentEnabled = false
}
</script>

<template>
  <details class="payment-options" :open="editing && !!form.installmentMonths">
    <summary>결제 옵션 <small v-if="form.splitMode || form.installmentEnabled">· {{ [form.splitMode && '분할 결제', form.installmentEnabled && '할부'].filter(Boolean).join(' · ') }}</small></summary>
    <div class="payment-options__body">
      <div class="payment-options__choices">
        <label class="payment-options__toggle payment-options__choice"><input type="checkbox" :checked="form.installmentEnabled" @change="setInstallment" /><span>할부 결제</span></label>
        <label class="payment-options__toggle payment-options__choice"><input type="checkbox" :checked="form.splitMode" :disabled="!canSplit && !form.splitMode" @change="setSplit" /><span>분할 결제</span></label>
      </div>
      <p v-if="!canSplit && !editing" class="payment-options__hint">분할 결제는 원화 지출에서 사용할 수 있으며 여행 기록과 직접 연결할 수 없습니다.</p>
      <p v-if="editing && form.splitPaymentGroupId" class="payment-options__hint">분할 결제의 각 수단은 개별 거래로 수정·환불·취소합니다.</p>
      <template v-if="form.splitMode">
        <p class="payment-options__hint">위 총 금액 {{ currency(Number(form.amount) || 0) }}원을 결제수단별로 나누어 입력하세요. 각 수단은 별도 거래로 저장됩니다.</p>
        <div v-for="(row, index) in form.splitPayments" :key="index" class="payment-options__part">
          <div class="payment-options__part-head"><strong>결제 {{ index + 1 }}</strong><button type="button" class="button button--ghost" :disabled="form.splitPayments.length <= 2" :aria-label="`결제수단 ${index + 1} 삭제`" @click="removePart(index)">삭제</button></div>
          <div class="payment-options__fields">
            <label><span>결제수단</span><select v-model="row.paymentMethodId" :aria-label="`결제수단 ${index + 1}`" @change="changeMethod(row)"><option value="">선택</option><option v-for="method in methods" :key="method.id" :value="String(method.id)">{{ method.name }}</option></select></label>
            <label><span>결제 금액{{ index === form.splitPayments.length - 1 ? ' · 잔액 자동입력' : '' }}</span><input :value="row.amount" type="number" inputmode="decimal" min="0.01" step="0.01" :aria-label="`수단 ${index + 1} 금액`" placeholder="0" @input="changeAmount($event, index)" /></label>
          </div>
          <template v-if="isCard(row)">
            <label class="payment-options__toggle"><input type="checkbox" :checked="row.installmentEnabled" @change="changePartInstallment(row, $event)" /> 이 카드 할부 적용</label>
            <div v-if="row.installmentEnabled" class="payment-options__fields">
              <label><span>할부 개월</span><input v-model="row.installmentMonths" type="number" inputmode="numeric" min="2" max="60" :aria-label="`수단 ${index + 1} 할부 개월`" placeholder="2~60개월" /></label>
              <label><span>이자 구분</span><select v-model="row.installmentInterestType" :aria-label="`수단 ${index + 1} 할부 이자`"><option value="">선택</option><option value="FREE">무이자</option><option value="INTEREST">유이자</option></select></label>
            </div>
          </template>
        </div>
        <div class="payment-options__footer"><button type="button" class="button button--secondary" :disabled="form.splitPayments.length >= 10" @click="addPart">수단 추가</button><span>입력 {{ currency(splitSum) }}원 · 남은 금액 {{ currency(remaining) }}원</span></div>
        <p class="payment-options__hint">마지막 수단에 남은 금액이 자동 입력되며 직접 수정할 수 있습니다. 다른 금액이나 총 금액을 변경하면 잔액을 다시 계산합니다.</p>
        <p v-if="amountNotice" class="payment-options__hint" role="status">{{ amountNotice }}</p>
        <p class="payment-options__hint">할부를 적용할 카드에서 각각 선택하세요. 할부는 표시 정보이며 금액을 월별로 나누지 않습니다.</p>
      </template>
      <template v-else-if="form.installmentEnabled">
        <div v-if="selectedCard" class="payment-options__fields">
          <label><span>할부 개월</span><input v-model="form.installmentMonths" type="number" inputmode="numeric" min="2" max="60" placeholder="2~60개월" /></label>
          <label><span>이자 구분</span><select v-model="form.installmentInterestType"><option value="">선택</option><option value="FREE">무이자</option><option value="INTEREST">유이자</option></select></label>
        </div>
        <p class="payment-options__hint">{{ selectedCard ? '할부는 표시 정보이며 거래 금액을 월별로 나누지 않습니다.' : '할부를 사용하려면 카드 결제수단을 선택하세요.' }}</p>
        <p v-if="form.installmentInterestUnknown && !form.installmentInterestType" class="payment-options__hint">이전 기록의 이자 구분은 저장되어 있지 않습니다. 필요하면 선택해 주세요.</p>
      </template>
    </div>
  </details>
</template>

<style scoped>
.payment-options { border: 1px solid var(--border, #aab89d); padding: .6rem; min-width: 0; text-align: left; container-type: inline-size; }
.payment-options summary { cursor: pointer; font-weight: 700; }
.payment-options__body { display: grid; gap: .65rem; padding-top: .7rem; }
.payment-options__choices { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: .5rem; }
.payment-options__choice { box-sizing: border-box; min-width: 0; min-height: 2.75rem; padding: .45rem .6rem; border: 1px solid var(--border, #aab89d); cursor: pointer; }
.payment-options__choice:has(input:checked) { border-color: var(--accent, #328368); background: var(--accent-soft, #32836822); }
.payment-options__choice:has(input:disabled) { opacity: .55; cursor: not-allowed; }
.payment-options__choice span { overflow-wrap: anywhere; }
.payment-options__toggle { display: flex; justify-content: flex-start; align-items: center; gap: .5rem; text-align: left; }
.payment-options__toggle input[type='checkbox'] { flex: 0 0 1.1rem; width: 1.1rem; height: 1.1rem; min-height: 0; margin: 0; }
.payment-options__hint { margin: 0; font-size: .78rem; line-height: 1.5; }
.payment-options__part { display: grid; gap: .5rem; padding: .6rem; border: 1px solid var(--border, #aab89d); min-width: 0; }
.payment-options__part-head { display: flex; justify-content: space-between; align-items: center; gap: .5rem; }
.payment-options__part-head strong { white-space: nowrap; }
.payment-options__fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: .5rem; }
.payment-options__fields label { display: grid; gap: .25rem; min-width: 0; font-size: .8rem; text-align: left; }
.payment-options__fields input, .payment-options__fields select { box-sizing: border-box; width: 100%; min-width: 0; min-height: 2.5rem; }
.payment-options__part button, .payment-options__footer button { flex: 0 0 auto; width: auto; min-width: 2.75rem; min-height: 2.5rem; }
.payment-options .payment-options__part-head .button { flex: 0 0 auto; width: auto; max-width: 40%; white-space: nowrap; }
.payment-options__footer { display: flex; flex-wrap: wrap; gap: .5rem; align-items: center; justify-content: space-between; font-size: .8rem; }
@container (max-width: 18rem) { .payment-options__fields { grid-template-columns: minmax(0, 1fr); } }
@container (max-width: 10rem) {
  .payment-options__part-head { flex-wrap: wrap; }
  .payment-options .payment-options__part-head .button { max-width: 100%; }
}
:global(:root[data-layout-mode='mobile']) :global(.household-entry-panel) .payment-options__part .button,
:global(:root[data-layout-mode='mobile']) :global(.household-entry-panel) .payment-options__footer .button { width: auto; min-width: 2.75rem; }
:global(:root[data-layout-mode='mobile']) :global(.household-entry-panel) .payment-options .payment-options__part-head .button { flex: 0 0 auto; width: auto; max-width: 40%; }
</style>
