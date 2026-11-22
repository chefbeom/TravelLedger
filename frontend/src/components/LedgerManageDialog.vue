<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { createLedgerAdjustment, fetchLedgerAdjustmentGroup, revertLedgerAdjustment, updateEntry } from '../lib/api'
import {
  buildLimitedEditPayload, ledgerEntryCapabilities, splitRowCapabilities, validateLimitedEditDraft,
} from '../lib/ledgerEntryCapabilities'
import {
  compareLedgerChronologically, ledgerEntryStatusLabel, splitDisplayedAmount, splitExpectedCount, summarizeLedgerAdjustments,
} from '../lib/ledgerSheetGroups'
import LedgerAdjustmentForm from './LedgerAdjustmentForm.vue'

const props = defineProps({
  row: { type: Object, required: true },
  split: { type: Boolean, default: false },
  initialStep: { type: String, default: 'menu' },
  categoryGroups: { type: Array, default: () => [] },
  paymentMethods: { type: Array, default: () => [] },
  formatTime: { type: Function, required: true },
  formatPaymentMethod: { type: Function, required: true },
  formatAmountMain: { type: Function, required: true },
  formatAmountSub: { type: Function, required: true },
})
const emit = defineEmits(['close', 'edit-entry', 'delete-entry', 'choose-split-action', 'ledger-changed'])

const STEP_TITLES = {
  cancel: '결제 취소 기록',
  refund: '환불 기록',
  uncancel: '결제 취소 취소',
  unrefund: '환불 취소',
  'edit-limited': '거래 내용 수정',
  delete: '거래 삭제',
}
const ACTION_STEP = { cancel: 'cancel', refund: 'refund', uncancel: 'uncancel', unrefund: 'unrefund', delete: 'delete' }

const step = ref('menu')
const busy = ref(false)
const stepError = ref('')
const resultMessage = ref('')
const selectedRefundId = ref('')
const draft = reactive({ entryDate: '', entryTime: '', title: '', memo: '', categoryGroupId: '', categoryDetailId: '' })
const manageGroup = reactive({ loading: false, error: '', origin: null, adjustments: null, seq: 0 })
const closeButton = ref(null)
const dialogEl = ref(null)
const stepTitle = ref(null)
const resultEl = ref(null)
const pendingStep = ref(props.initialStep && props.initialStep !== 'menu' ? props.initialStep : '')
let stepTrigger = null

function money(value) {
  return new Intl.NumberFormat('ko-KR', { style: 'currency', currency: 'KRW', maximumFractionDigits: 2 }).format(Number(value || 0))
}
function clock(value) { return value ? String(value).slice(0, 5) : '' }
function stamp(value) { return String(value || '').replace('T', ' ').slice(0, 16) }
function whenText(item) { return [item.entryDate, clock(item.entryTime)].filter(Boolean).join(' ') }
function splitCount(row) {
  const expected = splitExpectedCount(row.parts)
  return expected > row.parts.length ? `${row.parts.length}/${expected}개 표시` : `${row.parts.length}개 수단`
}

const managedOrigin = computed(() => {
  if (props.split) return props.row.parts[0]
  if (manageGroup.origin) return manageGroup.origin
  return props.row.kind === 'adjustment' ? null : props.row.parts[0]
})
const managedHistory = computed(() => {
  if (props.split) return []
  return manageGroup.adjustments ?? (props.row.kind === 'adjustment' ? props.row.parts : props.row.adjustments)
})
const managedSummary = computed(() => summarizeLedgerAdjustments(managedOrigin.value || {}, managedHistory.value))
const managedPending = computed(() => props.row.kind === 'adjustment' && !managedOrigin.value && !props.split)
const historyStatus = computed(() => (manageGroup.loading ? 'loading' : manageGroup.error ? 'error' : 'ready'))
const caps = computed(() => (!props.split && managedOrigin.value
  ? ledgerEntryCapabilities(managedOrigin.value, managedHistory.value, { historyStatus: historyStatus.value })
  : null))
const showHistory = computed(() => !props.split && (managedHistory.value.length > 0
  || Boolean(managedOrigin.value?.hasAdjustmentHistory) || props.row.kind === 'adjustment'))
const badge = computed(() => {
  if (props.split) return ''
  return managedPending.value ? ledgerEntryStatusLabel(props.row.parts[0]) : managedSummary.value.label
})

const SPLIT_ACTIONS = [
  { id: 'refund', label: '환불', description: '처리할 결제수단 선택' },
  { id: 'cancel', label: '결제 취소', description: '처리할 결제수단 선택' },
  { id: 'uncancel', label: '결제 취소 취소', description: '되돌릴 결제수단 선택' },
  { id: 'unrefund', label: '환불 취소', description: '되돌릴 결제수단 선택' },
  { id: 'delete', label: '삭제', description: '삭제할 결제수단 선택' },
]
const splitCaps = computed(() => (props.split ? splitRowCapabilities(props.row) : null))
const actionList = computed(() => {
  if (props.split) {
    const info = splitCaps.value
    const list = [{
      id: 'edit', label: '거래 내용 수정', enabled: info.canEditGroup,
      description: info.canEditGroup ? '분할 결제 전체 수정' : info.editGroupReason,
    }]
    for (const action of SPLIT_ACTIONS) {
      if (info.intents.includes(action.id)) list.push({ ...action, enabled: true })
    }
    return list
  }
  return caps.value?.actions || []
})

const activeRefunds = computed(() => caps.value?.activeRefunds || [])
const refundTarget = computed(() => {
  if (activeRefunds.value.length === 1) return activeRefunds.value[0]
  return activeRefunds.value.find(item => String(item.id) === selectedRefundId.value) || null
})
const historyVoidedCount = computed(() => managedHistory.value.length)

const draftGroups = computed(() => props.categoryGroups.filter(group => group.entryType === (managedOrigin.value?.entryType || 'EXPENSE')
  && (group.active !== false || String(group.id) === String(managedOrigin.value?.categoryGroupId))))
const draftDetails = computed(() => {
  const group = props.categoryGroups.find(item => String(item.id) === String(draft.categoryGroupId))
  return (group?.details || []).filter(detail => detail.active !== false || String(detail.id) === String(managedOrigin.value?.categoryDetailId))
})
const draftError = computed(() => (caps.value ? validateLimitedEditDraft(draft, caps.value) : ''))
const editIntro = computed(() => `${caps.value?.state === 'CANCELLED' ? '결제 취소가' : '환불이'} 적용 중이라 금액·구분·결제수단·외화·할부·여행 연결은 바꿀 수 없습니다.`)

function resetGroup() {
  manageGroup.seq += 1
  manageGroup.loading = false
  manageGroup.error = ''
  manageGroup.origin = null
  manageGroup.adjustments = null
}

async function loadGroup(entryId) {
  const seq = ++manageGroup.seq
  manageGroup.loading = true
  manageGroup.error = ''
  try {
    const json = await fetchLedgerAdjustmentGroup(entryId)
    if (seq !== manageGroup.seq) return
    if (!(json && typeof json === 'object' && !Array.isArray(json) && json.origin && Array.isArray(json.adjustments))) {
      throw new Error('unexpected adjustment group response')
    }
    manageGroup.origin = json.origin
    manageGroup.adjustments = [...json.adjustments].sort(compareLedgerChronologically)
  } catch {
    if (seq === manageGroup.seq) manageGroup.error = 'failed'
  } finally {
    if (seq === manageGroup.seq) manageGroup.loading = false
  }
}
function reloadGroup() {
  return loadGroup(managedOrigin.value?.id ?? props.row.parts[0].id)
}
function retryGroup() { return reloadGroup() }

function focusSoon(getter) {
  nextTick(() => {
    if (dialogEl.value) dialogEl.value.scrollTop = 0
    getter()?.focus?.({ preventScroll: false })
  })
}

function enterStep(id, trigger) {
  // The menu is re-rendered on return, so remember the action id instead of the (replaced) button element.
  stepTrigger = trigger?.dataset?.manageAction || null
  stepError.value = ''
  resultMessage.value = ''
  selectedRefundId.value = ''
  if (id === 'edit-limited') {
    const origin = managedOrigin.value
    draft.entryDate = origin.entryDate || ''
    draft.entryTime = clock(origin.entryTime)
    draft.title = origin.title || ''
    draft.memo = origin.memo ?? ''
    draft.categoryGroupId = String(origin.categoryGroupId ?? '')
    draft.categoryDetailId = String(origin.categoryDetailId ?? '')
  }
  step.value = id
  if (id !== 'cancel' && id !== 'refund') focusSoon(() => stepTitle.value)
  else nextTick(() => { if (dialogEl.value) dialogEl.value.scrollTop = 0 })
}

function backToMenu() {
  if (busy.value) return
  step.value = 'menu'
  stepError.value = ''
  focusSoon(() => (stepTrigger && dialogEl.value?.querySelector(`[data-manage-action="${stepTrigger}"]`)) || closeButton.value)
}

function onAction(action, event) {
  if (!action.enabled) return
  if (props.split) {
    if (action.id === 'edit') emit('edit-entry', { splitGroupId: managedOrigin.value.splitPaymentGroupId })
    else emit('choose-split-action', action.id)
    return
  }
  if (action.id === 'edit') {
    if (caps.value.editMode === 'FULL') emit('edit-entry', managedOrigin.value)
    else enterStep('edit-limited', event.currentTarget)
    return
  }
  enterStep(ACTION_STEP[action.id], event.currentTarget)
}

function tryEnterPending() {
  if (!pendingStep.value || !caps.value || step.value !== 'menu') return
  const action = caps.value.actions.find(item => item.id === pendingStep.value)
  if (action?.enabled) {
    const id = pendingStep.value
    pendingStep.value = ''
    enterStep(ACTION_STEP[id] || id, null)
  } else if (!manageGroup.loading) {
    pendingStep.value = ''
  }
}

const RESULT_MESSAGES = {
  cancel: '결제 취소를 저장했습니다. 이 거래는 지출 합계에서 제외됩니다.',
  refund: '환불 기록을 저장했습니다.',
  uncancel: '결제 취소를 취소했습니다. 이 거래가 다시 지출 합계에 포함됩니다.',
  unrefund: '환불을 취소했습니다. 처리 이력은 보존됩니다.',
  edit: '거래 내용을 수정했습니다.',
}

async function afterChange(kind) {
  await reloadGroup()
  step.value = 'menu'
  stepError.value = ''
  resultMessage.value = RESULT_MESSAGES[kind]
  emit('ledger-changed', { kind, message: RESULT_MESSAGES[kind] })
  focusSoon(() => resultEl.value)
}

async function runWrite(kind, task) {
  if (busy.value) return
  busy.value = true
  stepError.value = ''
  try {
    await task()
  } catch (error) {
    stepError.value = error?.message || '처리하지 못했습니다.'
    busy.value = false
    await reloadGroup()
    return
  }
  try {
    await afterChange(kind)
  } finally {
    busy.value = false
  }
}

function submitAdjustment(payload) {
  const kind = step.value
  return runWrite(kind, () => createLedgerAdjustment(managedOrigin.value.id, payload))
}
function runRevert(kind) {
  const record = kind === 'uncancel' ? caps.value?.activeCancellation : refundTarget.value
  if (!record) return undefined
  return runWrite(kind, () => revertLedgerAdjustment(record.id))
}
function saveLimited() {
  if (busy.value || draftError.value) return undefined
  return runWrite('edit', () => updateEntry(managedOrigin.value.id, buildLimitedEditPayload(managedOrigin.value, draft)))
}
function confirmDelete() {
  if (busy.value) return
  emit('delete-entry', { entry: managedOrigin.value, confirmed: true })
}

function onEscape() {
  if (busy.value) return
  if (step.value === 'menu') emit('close', true)
  else backToMenu()
}
function onOverlayClick() {
  if (!busy.value) emit('close', true)
}

watch(() => [caps.value?.actions, manageGroup.loading], tryEnterPending, { flush: 'post' })
watch(() => draft.categoryGroupId, (value, previous) => {
  if (previous !== undefined && value !== previous && !draftDetails.value.some(detail => String(detail.id) === draft.categoryDetailId)) {
    draft.categoryDetailId = ''
  }
})

onMounted(() => {
  const first = props.row.parts[0]
  if (!props.split && (props.row.kind === 'adjustment' || props.row.adjustments.length > 0 || first?.hasAdjustmentHistory
    || pendingStep.value === 'uncancel' || pendingStep.value === 'unrefund')) {
    loadGroup(first.id)
  }
  tryEnterPending()
  if (step.value === 'menu') closeButton.value?.focus()
})
onBeforeUnmount(resetGroup)
</script>

<template>
  <div class="ledger-manage-overlay" role="dialog" aria-modal="true" aria-labelledby="ledger-manage-title" @click.self="onOverlayClick" @keydown.esc.stop="onEscape">
    <section ref="dialogEl" class="ledger-manage-dialog panel">
      <header class="ledger-manage-header">
        <div>
          <h2 id="ledger-manage-title">거래 관리</h2>
          <p v-if="step === 'menu'">거래 상태를 확인하고 필요한 처리를 선택합니다.</p>
        </div>
        <button ref="closeButton" type="button" class="button button--ghost" :data-modal-close="step === 'menu' ? '' : null" :disabled="busy" @click="emit('close', true)">닫기</button>
      </header>

      <template v-if="step === 'menu'">
        <p v-if="caps && (badge || caps.stateLine)" class="ledger-manage-state">
          <span v-if="badge" class="adjustment-status">{{ badge }}</span>
          <span v-if="caps.stateLine">{{ caps.stateLine }}</span>
        </p>
        <div v-if="managedOrigin || managedPending" class="ledger-manage-summary">
          <template v-if="managedOrigin">
            <div class="ledger-manage-summary__title"><strong>{{ managedOrigin.title }}</strong><span v-if="!split && badge && !caps" class="adjustment-status">{{ badge }}</span></div>
            <span>{{ managedOrigin.entryDate }} · {{ managedOrigin.entryTime ? formatTime(managedOrigin.entryTime) : '시간 미지정' }} · {{ managedOrigin.entryType === 'INCOME' ? '수입' : '지출' }}</span>
            <span>{{ managedOrigin.categoryGroupName }}<template v-if="managedOrigin.categoryDetailName"> / {{ managedOrigin.categoryDetailName }}</template> · {{ split ? splitCount(row) : formatPaymentMethod(managedOrigin) }}</span>
            <strong v-if="split">{{ money(splitDisplayedAmount(row.parts)) }}</strong>
            <strong v-else><span :class="{ 'sheet-table__amount-main is-struck': managedOrigin.financiallyExcluded }">{{ formatAmountMain(managedOrigin) }}</span> <small v-if="formatAmountSub(managedOrigin)" :class="{ 'is-struck': managedOrigin.financiallyExcluded }">{{ formatAmountSub(managedOrigin) }}</small> <small v-if="managedOrigin.financiallyExcluded">{{ managedOrigin.cancelledAt ? '결제 취소 · 합계 제외' : '합계 제외' }}</small></strong>
            <span v-if="split">분할 결제 · {{ splitCount(row) }} · 표시 금액 {{ money(splitDisplayedAmount(row.parts)) }}</span>
            <span v-else-if="managedOrigin.splitPaymentGroupId">분할 {{ managedOrigin.splitPaymentIndex }}/{{ managedOrigin.splitPaymentCount }} · 이 결제수단만 처리됩니다.</span>
            <span v-if="!split && managedOrigin.installmentMonths">{{ managedOrigin.installmentMonths }}개월 {{ managedOrigin.installmentInterestType === 'FREE' ? '무이자 ' : managedOrigin.installmentInterestType === 'INTEREST' ? '유이자 ' : '' }}할부</span>
            <span v-if="managedOrigin.visibleMemo || managedOrigin.memo">메모: {{ managedOrigin.visibleMemo || managedOrigin.memo }}</span>
          </template>
          <template v-else>
            <div class="ledger-manage-summary__title"><strong>{{ row.parts[0].title }}</strong><span class="adjustment-status">{{ badge }}</span></div>
            <span>{{ row.parts[0].entryDate }} · {{ row.parts[0].entryTime ? formatTime(row.parts[0].entryTime) : '시간 미지정' }} · {{ row.parts[0].entryType === 'INCOME' ? '수입' : '지출' }}</span>
            <span>원거래 #{{ row.parts[0].adjustmentOriginId }}</span>
            <span v-if="manageGroup.loading" role="status">원거래 정보를 불러오는 중입니다…</span>
            <span v-else-if="manageGroup.error">원거래 정보를 불러오지 못했습니다.</span>
          </template>
        </div>

        <section v-if="managedOrigin && actionList.length" class="ledger-manage-actions-section" aria-labelledby="ledger-manage-actions-title">
          <h3 id="ledger-manage-actions-title">할 수 있는 작업</h3>
          <div class="ledger-manage-actions">
            <button
              v-for="action in actionList" :key="action.id" type="button"
              :class="['button', action.id === 'delete' ? 'button--danger' : 'button--secondary']"
              :aria-label="action.label" :aria-describedby="`ledger-manage-desc-${action.id}`"
              :aria-disabled="!action.enabled || null" :data-manage-action="action.id"
              @click="onAction(action, $event)"
            ><strong>{{ action.label }}</strong><small :id="`ledger-manage-desc-${action.id}`">{{ action.description }}</small></button>
          </div>
        </section>

        <section v-if="showHistory" class="ledger-manage-history" aria-labelledby="ledger-manage-history-title">
          <h3 id="ledger-manage-history-title">처리 이력</h3>
          <p v-if="manageGroup.loading" role="status">처리 이력을 불러오는 중입니다…</p>
          <div v-else-if="manageGroup.error" role="alert" class="ledger-manage-history__error">
            <p>처리 이력을 모두 불러오지 못했습니다. 이 날짜의 기록만 표시합니다.</p>
            <button type="button" class="button button--ghost" @click="retryGroup()">다시 시도</button>
          </div>
          <p v-if="!managedSummary.items.length">{{ managedSummary.hiddenHistory && (manageGroup.loading || manageGroup.error) ? '다른 날짜에 처리 기록이 있습니다.' : '표시할 처리 기록이 없습니다.' }}</p>
          <ol v-if="managedSummary.items.length" class="ledger-manage-history__list">
            <li v-for="item in managedSummary.items" :key="item.id" class="ledger-manage-history__item">
              <div><strong>{{ item.label }}</strong> · {{ whenText(item) }}</div>
              <span v-if="item.type === 'REFUND'">기준 {{ money(item.principal) }} − 차감 {{ money(item.deduction) }} + 추가 {{ money(item.extra) }} = 수령 {{ money(item.amount) }}<template v-if="item.paymentMethodName"> · {{ item.paymentMethodName }}</template><template v-if="item.voided"> (처리 당시 금액)</template></span>
              <span v-else>결제 취소 금액 {{ money(item.principal ?? item.amount) }}<template v-if="item.voided"> (처리 당시 금액)</template></span>
              <span v-if="item.memo">{{ item.memo }}</span>
              <span v-if="item.voided">되돌림 {{ stamp(item.voidedAt) }}</span>
              <span v-else class="ledger-manage-history__active">적용 중</span>
            </li>
          </ol>
        </section>

        <p v-if="caps?.note" class="ledger-manage-note">{{ caps.note }}</p>
        <p v-if="resultMessage" ref="resultEl" class="ledger-manage-result" role="status" tabindex="-1">{{ resultMessage }}</p>
      </template>

      <section v-else-if="step === 'cancel' || step === 'refund'" class="ledger-manage-step" aria-labelledby="ledger-manage-step-title">
        <h3 id="ledger-manage-step-title" ref="stepTitle" tabindex="-1">{{ STEP_TITLES[step] }}</h3>
        <LedgerAdjustmentForm
          :key="step"
          :entry="managedOrigin" :mode="step === 'refund' ? 'REFUND' : 'PAYMENT_CANCELLATION'"
          :categories="categoryGroups" :payment-methods="paymentMethods"
          :busy="busy" :error="stepError" cancel-label="돌아가기" esc-target
          @submit="submitAdjustment" @cancel="backToMenu"
        />
      </section>

      <section v-else-if="step === 'uncancel'" class="ledger-manage-step" aria-labelledby="ledger-manage-step-title">
        <h3 id="ledger-manage-step-title" ref="stepTitle" tabindex="-1">{{ STEP_TITLES.uncancel }}</h3>
        <template v-if="caps?.activeCancellation">
          <p>{{ managedOrigin.title }} · {{ caps.activeCancellation.entryDate }} 결제 취소({{ money(caps.activeCancellation.adjustmentPrincipal ?? caps.activeCancellation.amount) }})를 되돌립니다.</p>
          <ul class="ledger-manage-step__list">
            <li>원거래가 다시 지출 합계에 포함됩니다.</li>
            <li>취소 기록은 '결제 취소 되돌림'으로 처리 이력에 남습니다.</li>
          </ul>
        </template>
        <p v-else>적용 중인 취소 기록을 찾을 수 없습니다.</p>
        <p v-if="stepError" class="feedback feedback--error" role="alert">{{ stepError }}</p>
        <div class="ledger-manage-step__actions">
          <button type="button" class="button button--primary" :disabled="busy || !caps?.activeCancellation" @click="runRevert('uncancel')">{{ busy ? '처리 중…' : '결제 취소 취소하기' }}</button>
          <button type="button" class="button button--ghost" data-modal-close :disabled="busy" @click="backToMenu">돌아가기</button>
        </div>
      </section>

      <section v-else-if="step === 'unrefund'" class="ledger-manage-step" aria-labelledby="ledger-manage-step-title">
        <h3 id="ledger-manage-step-title" ref="stepTitle" tabindex="-1">{{ STEP_TITLES.unrefund }}</h3>
        <template v-if="activeRefunds.length === 1">
          <p>환불 입금 {{ money(activeRefunds[0].amount) }}({{ activeRefunds[0].entryDate }})을 되돌립니다.</p>
          <ul class="ledger-manage-step__list">
            <li>이 환불 입금이 수입 합계에서 빠집니다.</li>
            <li>환불 기준 {{ money(activeRefunds[0].adjustmentPrincipal ?? activeRefunds[0].amount) }}만큼 다시 환불할 수 있게 됩니다.</li>
            <li>기록은 '환불 되돌림'으로 남습니다.</li>
          </ul>
        </template>
        <fieldset v-else-if="activeRefunds.length > 1" class="ledger-manage-step__choices">
          <legend>되돌릴 환불 선택</legend>
          <label v-for="item in activeRefunds" :key="item.id" class="ledger-manage-step__choice">
            <input v-model="selectedRefundId" type="radio" name="ledger-manage-refund" :value="String(item.id)" :disabled="busy" />
            <span>{{ whenText(item) }} · 수령 {{ money(item.amount) }} · 기준 {{ money(item.adjustmentPrincipal ?? item.amount) }}</span>
          </label>
        </fieldset>
        <p v-else>적용 중인 환불 기록을 찾을 수 없습니다.</p>
        <p v-if="stepError" class="feedback feedback--error" role="alert">{{ stepError }}</p>
        <div class="ledger-manage-step__actions">
          <button type="button" class="button button--primary" :disabled="busy || !refundTarget" @click="runRevert('unrefund')">{{ busy ? '처리 중…' : '환불 취소하기' }}</button>
          <button type="button" class="button button--ghost" data-modal-close :disabled="busy" @click="backToMenu">돌아가기</button>
        </div>
      </section>

      <section v-else-if="step === 'edit-limited'" class="ledger-manage-step" aria-labelledby="ledger-manage-step-title">
        <h3 id="ledger-manage-step-title" ref="stepTitle" tabindex="-1">{{ STEP_TITLES['edit-limited'] }}</h3>
        <form class="ledger-manage-edit" @submit.prevent="saveLimited">
          <p>{{ editIntro }}</p>
          <p class="ledger-manage-edit__fixed">고정 항목: {{ money(managedOrigin.amount) }} · {{ managedOrigin.entryType === 'INCOME' ? '수입' : '지출' }} · {{ formatPaymentMethod(managedOrigin) }}</p>
          <div class="ledger-manage-grid">
            <label class="field"><span class="field__label">날짜</span><input v-model="draft.entryDate" type="date" :max="caps?.maxEntryDate || null" required /></label>
            <label class="field"><span class="field__label">시간 (선택)</span><input v-model="draft.entryTime" type="time" /></label>
          </div>
          <label class="field"><span class="field__label">제목</span><input v-model="draft.title" type="text" maxlength="120" required /></label>
          <label class="field"><span class="field__label">메모 (선택)</span><textarea v-model="draft.memo" maxlength="500" rows="2" /></label>
          <div class="ledger-manage-grid">
            <label class="field"><span class="field__label">대분류</span><select v-model="draft.categoryGroupId" required><option v-for="group in draftGroups" :key="group.id" :value="String(group.id)">{{ group.name }}</option></select></label>
            <label class="field"><span class="field__label">소분류</span><select v-model="draft.categoryDetailId"><option value="">소분류 없음</option><option v-for="detail in draftDetails" :key="detail.id" :value="String(detail.id)">{{ detail.name }}</option></select></label>
          </div>
          <p v-if="stepError" class="feedback feedback--error" role="alert">{{ stepError }}</p>
          <p v-else-if="draftError" class="ledger-manage-note" role="status">{{ draftError }}</p>
          <div class="ledger-manage-step__actions">
            <button type="submit" class="button button--primary" :disabled="busy || !!draftError">{{ busy ? '저장 중…' : '저장' }}</button>
            <button type="button" class="button button--ghost" data-modal-close :disabled="busy" @click="backToMenu">돌아가기</button>
          </div>
        </form>
      </section>

      <section v-else-if="step === 'delete'" class="ledger-manage-step" aria-labelledby="ledger-manage-step-title">
        <h3 id="ledger-manage-step-title" ref="stepTitle" tabindex="-1">{{ STEP_TITLES.delete }}</h3>
        <p>'{{ managedOrigin.title }}' 거래를 휴지통으로 이동합니다.</p>
        <p v-if="caps?.state === 'REVERTED' && historyVoidedCount">되돌린 처리 이력 {{ historyVoidedCount }}건도 함께 이동하며, 휴지통에서 복구하면 함께 복구됩니다.</p>
        <div class="ledger-manage-step__actions">
          <button type="button" class="button button--danger ledger-manage-step__danger" :disabled="busy" @click="confirmDelete">삭제하기</button>
          <button type="button" class="button button--ghost" data-modal-close :disabled="busy" @click="backToMenu">돌아가기</button>
        </div>
      </section>
    </section>
  </div>
</template>

<style scoped>
.ledger-manage-overlay { position: fixed; inset: 0; z-index: 2700; display: grid; place-items: center; padding: 1rem; background: rgb(0 0 0 / 65%); }
.ledger-manage-dialog { box-sizing: border-box; width: min(100%, 44rem); max-height: calc(100dvh - 2rem); overflow-y: auto; background: var(--panel-bg); color: var(--text); text-align: left; }
.ledger-manage-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 1rem; }
.ledger-manage-header > div { min-width: 0; }
.ledger-manage-header .button { flex: 0 0 auto; width: auto; min-width: 4rem; white-space: nowrap; }
.ledger-manage-header h2 { margin: 0; }
.ledger-manage-header p, .ledger-manage-note { margin: .4rem 0; line-height: 1.45; }
.ledger-manage-state { display: flex; flex-wrap: wrap; align-items: center; gap: .4rem; margin: .4rem 0 0; line-height: 1.45; }
.ledger-manage-summary { display: grid; gap: .35rem; margin: 1rem 0; padding: .8rem; border: 1px solid var(--border); overflow-wrap: anywhere; }
.ledger-manage-summary__title { display: flex; flex-wrap: wrap; align-items: center; gap: .35rem; }
.ledger-manage-actions-section { display: grid; gap: .5rem; margin: 0 0 1rem; }
.ledger-manage-actions-section h3 { margin: 0; }
.ledger-manage-actions { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 13rem), 1fr)); gap: .5rem; }
.ledger-manage-dialog .ledger-manage-actions .button {
  height: auto; min-height: 3.75rem; padding: .7rem .9rem; gap: .25rem;
  display: flex; flex-direction: column; align-items: flex-start; justify-content: flex-start;
  line-height: 1.35; text-align: left; white-space: normal;
}
.ledger-manage-dialog .ledger-manage-actions .button strong { line-height: 1.35; }
.ledger-manage-dialog .ledger-manage-actions .button small { display: block; line-height: 1.35; font-weight: 400; }
.ledger-manage-dialog .ledger-manage-actions .button[aria-disabled='true'] { opacity: .62; cursor: not-allowed; border-style: dashed !important; }
.ledger-manage-dialog .ledger-manage-actions .button--danger:not([aria-disabled='true']) { background: #8b2735 !important; border-color: #8b2735 !important; color: #fff !important; }
.ledger-manage-dialog .ledger-manage-actions .button--danger:not([aria-disabled='true']) * { color: #fff !important; -webkit-text-fill-color: #fff !important; text-shadow: none !important; }
.ledger-manage-note { font-size: .85rem; }
.ledger-manage-result { margin: .6rem 0 0; padding: .6rem .8rem; border: 1px solid var(--border); line-height: 1.45; }
.adjustment-status { display: inline-block; padding: .1rem .35rem; border: 1px solid currentColor; border-radius: .25rem; font-size: .75rem; white-space: nowrap; }
.ledger-manage-history { display: grid; gap: .5rem; margin: 0 0 1rem; }
.ledger-manage-history h3 { margin: 0; }
.ledger-manage-history p { margin: 0; line-height: 1.45; }
.ledger-manage-history__list { display: grid; gap: .5rem; margin: 0; padding: 0; list-style: none; }
.ledger-manage-history__item { display: grid; gap: .25rem; min-width: 0; padding: .65rem .8rem; border: 1px solid var(--border); border-left: 3px solid var(--border); overflow-wrap: anywhere; }
.ledger-manage-history__error { display: grid; gap: .4rem; }
.ledger-manage-history__error .button { width: auto; justify-self: start; min-height: 2.75rem; }
.ledger-manage-summary .is-struck { text-decoration: line-through; }
.ledger-manage-step { display: grid; gap: .8rem; margin-top: 1rem; }
.ledger-manage-step h3 { margin: 0; }
.ledger-manage-step p { margin: 0; line-height: 1.5; }
.ledger-manage-step__list { margin: 0; padding-left: 1.2rem; line-height: 1.6; }
.ledger-manage-step__choices { display: grid; gap: .5rem; margin: 0; padding: .6rem .8rem; border: 1px solid var(--border); }
.ledger-manage-step__choice { display: flex; align-items: flex-start; gap: .5rem; min-height: 2.75rem; line-height: 1.45; }
.ledger-manage-step__actions { display: flex; flex-wrap: wrap; gap: .5rem; justify-content: flex-end; }
.ledger-manage-step__actions .button { width: auto; min-height: 2.75rem; }
.ledger-manage-step .ledger-manage-step__actions .ledger-manage-step__danger { background: #8b2735 !important; border-color: #8b2735 !important; color: #fff !important; }
.ledger-manage-edit { display: grid; gap: .8rem; }
.ledger-manage-edit__fixed { padding: .5rem .7rem; border: 1px dashed var(--border); }
.ledger-manage-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 11rem), 1fr)); gap: .7rem; }
.ledger-manage-grid .field { min-width: 0; }
.ledger-manage-edit input, .ledger-manage-edit select, .ledger-manage-edit textarea { width: 100%; box-sizing: border-box; }
</style>
