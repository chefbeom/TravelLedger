<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import {
  approveRecurringLedgerOccurrence,
  createRecurringLedgerRule,
  deleteRecurringLedgerRule,
  fetchRecurringLedgerOccurrences,
  fetchRecurringLedgerRules,
  skipRecurringLedgerOccurrence,
  updateRecurringLedgerRule,
} from '../lib/api'

const props = defineProps({
  categoryGroups: {
    type: Array,
    default: () => [],
  },
  paymentMethods: {
    type: Array,
    default: () => [],
  },
  prefill: {
    type: Object,
    default: null,
  },
  formatCurrency: {
    type: Function,
    default: (value) => new Intl.NumberFormat('ko-KR', {
      style: 'currency',
      currency: 'KRW',
      maximumFractionDigits: 0,
    }).format(Number(value || 0)),
  },
})

const emit = defineEmits(['entries-changed', 'prefill-applied'])

function getToday() {
  const date = new Date()
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return year + '-' + month + '-' + day
}

const rules = ref([])
const pendingOccurrences = ref([])
const isLoading = ref(false)
const isSubmitting = ref(false)
const action = ref('')
const errorMessage = ref('')
const feedback = ref('')
const editingRuleId = ref(null)

const form = reactive({
  title: '',
  memo: '',
  amount: '',
  entryType: 'EXPENSE',
  scheduleType: 'MONTHLY_DATE',
  monthInterval: 1,
  dayOfMonth: 1,
  intervalDays: 1,
  startDate: getToday(),
  endDate: '',
  mode: 'CONFIRM',
  categoryGroupId: '',
  categoryDetailId: '',
  paymentMethodId: '',
  active: true,
})

const selectedGroups = computed(() => props.categoryGroups.filter((group) => group.entryType === form.entryType))
const selectedGroup = computed(() => selectedGroups.value.find((group) => String(group.id) === String(form.categoryGroupId)))
const availableDetails = computed(() => selectedGroup.value?.details ?? [])

function syncSelections() {
  if (!selectedGroups.value.some((group) => String(group.id) === String(form.categoryGroupId))) {
    form.categoryGroupId = selectedGroups.value[0] ? String(selectedGroups.value[0].id) : ''
  }

  if (!availableDetails.value.some((detail) => String(detail.id) === String(form.categoryDetailId))) {
    form.categoryDetailId = availableDetails.value[0] ? String(availableDetails.value[0].id) : ''
  }

  if (form.entryType === 'INCOME') {
    form.paymentMethodId = ''
  } else if (!props.paymentMethods.some((method) => String(method.id) === String(form.paymentMethodId))) {
    form.paymentMethodId = props.paymentMethods[0] ? String(props.paymentMethods[0].id) : ''
  }
}

watch(() => form.entryType, syncSelections)
watch(() => form.categoryGroupId, syncSelections)
watch(() => [props.categoryGroups, props.paymentMethods], syncSelections, { deep: true })

function resetForm() {
  Object.assign(form, {
    title: '',
    memo: '',
    amount: '',
    entryType: 'EXPENSE',
    scheduleType: 'MONTHLY_DATE',
    monthInterval: 1,
    dayOfMonth: 1,
    intervalDays: 1,
    startDate: getToday(),
    endDate: '',
    mode: 'CONFIRM',
    categoryGroupId: '',
    categoryDetailId: '',
    paymentMethodId: '',
    active: true,
  })
  editingRuleId.value = null
  syncSelections()
}

function setMessage(message = '', error = '') {
  feedback.value = message
  errorMessage.value = error
}

async function loadRules() {
  rules.value = await fetchRecurringLedgerRules()
}

async function loadPendingOccurrences() {
  pendingOccurrences.value = await fetchRecurringLedgerOccurrences()
}

async function loadAll() {
  isLoading.value = true
  setMessage()
  try {
    await Promise.all([loadRules(), loadPendingOccurrences()])
    syncSelections()
  } catch (error) {
    setMessage('', error.message || '정기 입출금 정보를 불러오지 못했습니다.')
  } finally {
    isLoading.value = false
  }
}

function applyPrefill(prefill = {}) {
  const startDate = String(prefill.entryDate || getToday()).slice(0, 10)
  const parsedDay = Number(startDate.slice(8, 10))
  const entryType = prefill.entryType === 'INCOME' ? 'INCOME' : 'EXPENSE'
  const amount = Number(prefill.amount)

  Object.assign(form, {
    title: String(prefill.title || ''),
    memo: String(prefill.memo || ''),
    amount: Number.isFinite(amount) && amount > 0 ? String(amount) : '',
    entryType,
    scheduleType: 'MONTHLY_DATE',
    monthInterval: 1,
    dayOfMonth: Number.isInteger(parsedDay) && parsedDay >= 1 && parsedDay <= 31 ? parsedDay : 1,
    intervalDays: 1,
    startDate,
    endDate: '',
    mode: 'CONFIRM',
    categoryGroupId: prefill.categoryGroupId != null ? String(prefill.categoryGroupId) : '',
    categoryDetailId: prefill.categoryDetailId != null ? String(prefill.categoryDetailId) : '',
    paymentMethodId: entryType === 'EXPENSE' && prefill.paymentMethodId != null
      ? String(prefill.paymentMethodId)
      : '',
    active: true,
  })
  editingRuleId.value = null
  syncSelections()
  setMessage('현재 거래 입력값을 정기 결제 초안으로 불러왔습니다. 반복 주기와 세부값을 확인한 뒤 등록해 주세요.')
  emit('prefill-applied')
}

onMounted(async () => {
  await loadAll()
  if (props.prefill) {
    applyPrefill(props.prefill)
  }
})

watch(
  () => props.prefill,
  (prefill) => {
    if (prefill) {
      applyPrefill(prefill)
    }
  },
  { deep: true },
)

function buildFormPayload() {
  return {
    title: String(form.title || '').trim(),
    memo: String(form.memo || '').trim() || null,
    amount: Number(form.amount),
    entryType: form.entryType,
    scheduleType: form.scheduleType,
    monthInterval: form.scheduleType === 'MONTHLY_DATE' ? Number(form.monthInterval) : null,
    dayOfMonth: form.scheduleType === 'MONTHLY_DATE' ? Number(form.dayOfMonth) : null,
    intervalDays: form.scheduleType === 'EVERY_N_DAYS' ? Number(form.intervalDays) : null,
    startDate: form.startDate,
    endDate: form.endDate || null,
    mode: form.mode,
    categoryGroupId: Number(form.categoryGroupId),
    categoryDetailId: form.categoryDetailId ? Number(form.categoryDetailId) : null,
    paymentMethodId: form.entryType === 'EXPENSE' && form.paymentMethodId
      ? Number(form.paymentMethodId)
      : null,
    active: Boolean(form.active),
  }
}

function buildRulePayload(rule, active = rule.active) {
  const scheduleType = resolveScheduleType(rule)
  return {
    title: rule.title,
    memo: rule.memo || null,
    amount: Number(rule.amount),
    entryType: rule.entryType,
    scheduleType,
    monthInterval: scheduleType === 'MONTHLY_DATE' ? Number(rule.monthInterval || 1) : null,
    dayOfMonth: scheduleType === 'MONTHLY_DATE' ? Number(rule.dayOfMonth) : null,
    intervalDays: scheduleType === 'EVERY_N_DAYS' ? Number(rule.intervalDays) : null,
    startDate: rule.startDate,
    endDate: rule.endDate || null,
    mode: rule.mode,
    categoryGroupId: Number(rule.categoryGroupId),
    categoryDetailId: rule.categoryDetailId != null ? Number(rule.categoryDetailId) : null,
    paymentMethodId: rule.entryType === 'EXPENSE' && rule.paymentMethodId != null
      ? Number(rule.paymentMethodId)
      : null,
    active: Boolean(active),
  }
}

async function saveRule() {
  setMessage()
  const title = String(form.title || '').trim()
  const amount = Number(form.amount)
  const dayOfMonth = Number(form.dayOfMonth)
  const intervalDays = Number(form.intervalDays)
  const monthInterval = Number(form.monthInterval)
  const scheduleType = form.scheduleType || 'MONTHLY_DATE'

  if (!title) {
    setMessage('', '정기 입출금 제목을 입력해 주세요.')
    return
  }
  if (!Number.isFinite(amount) || amount <= 0) {
    setMessage('', '금액은 0보다 커야 합니다.')
    return
  }
  if (scheduleType === 'MONTHLY_DATE') {
    if (!Number.isInteger(dayOfMonth) || dayOfMonth < 1 || dayOfMonth > 31) {
      setMessage('', '매월 반복 날짜는 1일부터 31일 사이로 선택해 주세요.')
      return
    }
    if (!Number.isInteger(monthInterval) || monthInterval < 1 || monthInterval > 120) {
      setMessage('', '달 반복 간격은 1개월부터 120개월 사이로 입력해 주세요.')
      return
    }
  } else if (!Number.isInteger(intervalDays) || intervalDays < 1 || intervalDays > 3650) {
    setMessage('', '반복 간격은 1일부터 3650일 사이로 입력해 주세요.')
    return
  }
  if (!form.startDate) {
    setMessage('', '시작일을 선택해 주세요.')
    return
  }
  if (form.endDate && form.endDate < form.startDate) {
    setMessage('', '종료일은 시작일보다 빠를 수 없습니다.')
    return
  }
  if (!form.categoryGroupId) {
    setMessage('', '대분류를 선택해 주세요.')
    return
  }
  if (form.entryType === 'EXPENSE' && !form.paymentMethodId) {
    setMessage('', '지출 정기 입출금은 결제수단을 선택해 주세요.')
    return
  }

  isSubmitting.value = true
  action.value = editingRuleId.value ? 'update' : 'create'
  try {
    const payload = buildFormPayload()
    if (editingRuleId.value) {
      await updateRecurringLedgerRule(editingRuleId.value, payload)
      setMessage('정기 입출금 규칙을 수정했습니다.')
    } else {
      await createRecurringLedgerRule(payload)
      setMessage('정기 입출금 규칙을 등록했습니다.')
    }
    await loadRules()
    resetForm()
  } catch (error) {
    setMessage('', error.message || '정기 입출금 규칙을 저장하지 못했습니다.')
  } finally {
    isSubmitting.value = false
    action.value = ''
  }
}

function editRule(rule) {
  Object.assign(form, {
    title: rule.title || '',
    memo: rule.memo || '',
    amount: rule.amount ?? '',
    entryType: rule.entryType || 'EXPENSE',
    scheduleType: resolveScheduleType(rule),
    monthInterval: rule.monthInterval || 1,
    dayOfMonth: rule.dayOfMonth || 1,
    intervalDays: rule.intervalDays || 1,
    startDate: rule.startDate || getToday(),
    endDate: rule.endDate || '',
    mode: rule.mode || 'CONFIRM',
    categoryGroupId: rule.categoryGroupId != null ? String(rule.categoryGroupId) : '',
    categoryDetailId: rule.categoryDetailId != null ? String(rule.categoryDetailId) : '',
    paymentMethodId: rule.paymentMethodId != null ? String(rule.paymentMethodId) : '',
    active: rule.active !== false,
  })
  editingRuleId.value = rule.id
  syncSelections()
}

function copyRule(rule) {
  editRule(rule)
  editingRuleId.value = null
  form.active = true
  setMessage('기존 정기 입출금 내용을 새 등록 초안으로 복사했습니다. 반복 주기와 세부값을 확인해 주세요.')
}

function cancelEdit() {
  resetForm()
  setMessage()
}

async function toggleRule(rule) {
  isSubmitting.value = true
  action.value = 'toggle-' + rule.id
  setMessage()
  try {
    await updateRecurringLedgerRule(rule.id, buildRulePayload(rule, !rule.active))
    await loadRules()
    setMessage(rule.active ? '정기 입출금을 일시정지했습니다.' : '정기 입출금을 다시 사용합니다.')
  } catch (error) {
    setMessage('', error.message || '정기 입출금 상태를 변경하지 못했습니다.')
  } finally {
    isSubmitting.value = false
    action.value = ''
  }
}

async function deleteRule(rule) {
  if (rule.active) {
    setMessage('', '먼저 일시정지한 정기 입출금만 삭제할 수 있습니다.')
    return
  }
  if (!window.confirm('"' + rule.title + '" 정기 입출금을 삭제할까요? 반복 처리 기록도 함께 삭제됩니다. 이미 가계부에 등록된 거래는 삭제되지 않습니다.')) {
    return
  }

  isSubmitting.value = true
  action.value = 'delete-' + rule.id
  setMessage()
  try {
    await deleteRecurringLedgerRule(rule.id)
    if (editingRuleId.value === rule.id) {
      resetForm()
    }
    await loadAll()
    setMessage('정기 입출금과 반복 처리 기록을 삭제했습니다. 이미 등록된 가계부 거래는 유지됩니다.')
  } catch (error) {
    setMessage('', error.message || '정기 입출금을 삭제하지 못했습니다.')
  } finally {
    isSubmitting.value = false
    action.value = ''
  }
}

async function approveOccurrence(occurrence) {
  isSubmitting.value = true
  action.value = 'approve-' + occurrence.id
  setMessage()
  try {
    await approveRecurringLedgerOccurrence(occurrence.id)
    await loadPendingOccurrences()
    emit('entries-changed')
    setMessage('정기 입출금 내역을 가계부에 등록했습니다.')
  } catch (error) {
    setMessage('', error.message || '정기 입출금 내역을 등록하지 못했습니다.')
  } finally {
    isSubmitting.value = false
    action.value = ''
  }
}

async function skipOccurrence(occurrence) {
  if (!window.confirm(occurrence.scheduledDate + ' 예정 내역을 건너뛸까요?')) {
    return
  }

  isSubmitting.value = true
  action.value = 'skip-' + occurrence.id
  setMessage()
  try {
    await skipRecurringLedgerOccurrence(occurrence.id)
    await loadPendingOccurrences()
    setMessage('이번 정기 입출금 내역을 건너뛰었습니다.')
  } catch (error) {
    setMessage('', error.message || '정기 입출금 내역을 건너뛰지 못했습니다.')
  } finally {
    isSubmitting.value = false
    action.value = ''
  }
}

function entryTypeLabel(entryType) {
  return entryType === 'INCOME' ? '수입' : '지출'
}

function modeLabel(mode) {
  return mode === 'AUTO' ? '자동 등록' : '확인 후 등록'
}

function formatAmount(value) {
  return props.formatCurrency(value)
}

function formatDate(value) {
  return value ? String(value).replaceAll('-', '.') : '-'
}

function formatCategory(rule) {
  return [rule.categoryGroupName, rule.categoryDetailName].filter(Boolean).join(' / ') || '분류 없음'
}

function resolveScheduleType(rule) {
  return rule.scheduleType || (rule.intervalDays != null ? 'EVERY_N_DAYS' : 'MONTHLY_DATE')
}

function formatMonthInterval(monthInterval) {
  const interval = Number(monthInterval) || 1
  if (interval === 1) {
    return '매월'
  }
  if (interval === 2) {
    return '격월'
  }
  if (interval === 3) {
    return '분기마다'
  }
  if (interval === 12) {
    return '매년'
  }
  if (interval > 12) {
    const years = Math.floor(interval / 12)
    const months = interval % 12
    return years + '년' + (months ? ' ' + months + '개월' : '') + '마다'
  }
  return interval + '개월마다'
}

function formatRuleSchedule(rule) {
  const scheduleType = resolveScheduleType(rule)
  const schedule = scheduleType === 'EVERY_N_DAYS'
    ? (rule.intervalDays || 1) + '일마다 (시작일 기준)'
    : formatMonthInterval(rule.monthInterval) + ' ' + rule.dayOfMonth + '일'
  return schedule + ' · ' + modeLabel(rule.mode)
}
</script>

<template>
  <section class="panel recurring-ledger-workspace" data-testid="recurring-ledger-workspace">
    <div class="panel__header">
      <div>
        <p class="panel__eyebrow">RECURRING LEDGER</p>
        <h2>정기 입출금</h2>
        <p>반복되는 수입과 지출을 등록하고 설정한 주기에 맞춰 가계부에 반영합니다.</p>
      </div>
      <button class="button button--secondary" type="button" @click="resetForm">새 정기 입출금</button>
    </div>

    <div v-if="feedback" class="feedback feedback--success">{{ feedback }}</div>
    <div v-if="errorMessage" class="feedback feedback--error">{{ errorMessage }}</div>

    <div class="recurring-ledger-layout">
      <form class="panel panel--compact recurring-ledger-form" data-testid="recurring-ledger-form" @submit.prevent="saveRule">
        <div class="panel__header">
          <div>
            <h3>{{ editingRuleId ? '정기 입출금 수정' : '정기 입출금 등록' }}</h3>
            <p>금액과 분류는 실제 거래 생성 시에도 다시 검증됩니다.</p>
          </div>
          <span v-if="editingRuleId" class="panel__badge">수정 중</span>
        </div>

        <div class="stack-form">
          <label class="field">
            <span class="field__label">제목</span>
            <input v-model="form.title" type="text" maxlength="120" placeholder="예: 넷플릭스 구독료, 월급" />
          </label>

          <div class="recurring-ledger-field-grid">
            <label class="field">
              <span class="field__label">구분</span>
              <select v-model="form.entryType">
                <option value="EXPENSE">지출</option>
                <option value="INCOME">수입</option>
              </select>
            </label>
            <label class="field">
              <span class="field__label">금액</span>
              <input v-model="form.amount" type="number" min="0.01" step="0.01" inputmode="decimal" placeholder="0" />
            </label>
            <label class="field">
              <span class="field__label">반복 기준</span>
              <select v-model="form.scheduleType">
                <option value="MONTHLY_DATE">달력 기준</option>
                <option value="EVERY_N_DAYS">N일마다</option>
              </select>
            </label>
          </div>

          <div class="recurring-ledger-field-grid recurring-ledger-field-grid--schedule">
            <label v-if="form.scheduleType === 'MONTHLY_DATE'" class="field">
              <span class="field__label">달 반복 주기(개월)</span>
              <input v-model.number="form.monthInterval" type="number" min="1" max="120" />
              <small class="field__hint">1=매월 · 2=격월 · 3=분기 · 18=1년 6개월마다</small>
            </label>
            <label v-else class="field">
              <span class="field__label">반복 간격(일)</span>
              <input v-model.number="form.intervalDays" type="number" min="1" max="3650" />
              <small class="field__hint">시작일을 기준으로 4일·23일·45일마다 실행됩니다.</small>
            </label>
            <label v-if="form.scheduleType === 'MONTHLY_DATE'" class="field">
              <span class="field__label">매월 날짜</span>
              <input v-model.number="form.dayOfMonth" type="number" min="1" max="31" />
              <small class="field__hint">28~31일은 날짜가 없는 달에 말일로 실행됩니다.</small>
            </label>
            <label class="field">
              <span class="field__label">시작일</span>
              <input v-model="form.startDate" type="date" />
            </label>
            <label class="field">
              <span class="field__label">종료일 <small>(선택)</small></span>
              <input v-model="form.endDate" type="date" />
            </label>
          </div>

          <fieldset class="recurring-ledger-mode">
            <legend class="field__label">등록 방식</legend>
            <label class="recurring-ledger-mode__option">
              <input v-model="form.mode" type="radio" value="AUTO" />
              <span><strong>자동 등록</strong><small>예정일에 거래를 바로 만듭니다.</small></span>
            </label>
            <label class="recurring-ledger-mode__option">
              <input v-model="form.mode" type="radio" value="CONFIRM" />
              <span><strong>확인 후 등록</strong><small>예정일에 대기 내역을 만들고 승인 후 등록합니다.</small></span>
            </label>
          </fieldset>

          <div class="recurring-ledger-field-grid">
            <label class="field">
              <span class="field__label">대분류</span>
              <select v-model="form.categoryGroupId">
                <option value="" disabled>대분류 선택</option>
                <option v-for="group in selectedGroups" :key="group.id" :value="String(group.id)">
                  {{ group.name }}
                </option>
              </select>
            </label>
            <label class="field">
              <span class="field__label">소분류</span>
              <select v-model="form.categoryDetailId">
                <option value="">소분류 없음</option>
                <option v-for="detail in availableDetails" :key="detail.id" :value="String(detail.id)">
                  {{ detail.name }}
                </option>
              </select>
            </label>
          </div>

          <label v-if="form.entryType === 'EXPENSE'" class="field">
            <span class="field__label">결제수단</span>
            <select v-model="form.paymentMethodId">
              <option value="" disabled>결제수단 선택</option>
              <option v-for="method in paymentMethods" :key="method.id" :value="String(method.id)">
                {{ method.name }}
              </option>
            </select>
          </label>

          <label class="field">
            <span class="field__label">메모 <small>(선택)</small></span>
            <textarea v-model="form.memo" rows="3" maxlength="500" placeholder="반복 거래에 남길 메모"></textarea>
          </label>

          <div class="recurring-ledger-form__actions">
            <button class="button button--primary" type="submit" :disabled="isSubmitting || isLoading" data-testid="recurring-rule-save">
              {{ isSubmitting && action === 'create' ? '등록 중...' : isSubmitting && action === 'update' ? '수정 중...' : editingRuleId ? '정기 입출금 수정' : '정기 입출금 등록' }}
            </button>
            <button v-if="editingRuleId" class="button button--secondary" type="button" :disabled="isSubmitting" @click="cancelEdit">취소</button>
          </div>
        </div>
      </form>

      <div class="recurring-ledger-results">
        <section v-if="pendingOccurrences.length" class="panel panel--compact recurring-ledger-pending" data-testid="recurring-pending">
          <div class="panel__header">
            <div>
              <h3>등록 대기</h3>
              <p>확인 후 등록 방식의 예정 내역입니다.</p>
            </div>
            <span class="panel__badge">{{ pendingOccurrences.length }}건</span>
          </div>
          <div class="recurring-ledger-list">
            <article v-for="occurrence in pendingOccurrences" :key="occurrence.id" class="recurring-ledger-item recurring-ledger-item--pending">
              <div>
                <div class="recurring-ledger-item__title">
                  <strong>{{ occurrence.ruleTitle }}</strong>
                  <span :class="['chip', occurrence.entryType === 'INCOME' ? 'chip--income' : 'chip--expense']">{{ entryTypeLabel(occurrence.entryType) }}</span>
                </div>
                <p>{{ formatDate(occurrence.scheduledDate) }} 예정 · {{ formatAmount(occurrence.amount) }}</p>
              </div>
              <div class="recurring-ledger-item__actions">
                <button class="button button--primary" type="button" :disabled="isSubmitting" @click="approveOccurrence(occurrence)">등록</button>
                <button class="button button--secondary" type="button" :disabled="isSubmitting" @click="skipOccurrence(occurrence)">건너뛰기</button>
              </div>
            </article>
          </div>
        </section>

        <section class="panel panel--compact">
          <div class="panel__header">
            <div>
              <h3>등록된 정기 입출금</h3>
              <p>비활성화하면 이후 예정일에 새 내역을 만들지 않습니다.</p>
            </div>
            <span class="panel__badge">{{ rules.length }}건</span>
          </div>

          <div v-if="isLoading" class="panel__empty">불러오는 중...</div>
          <div v-else-if="!rules.length" class="panel__empty">
            아직 등록된 정기 입출금이 없습니다. 왼쪽에서 첫 규칙을 등록해 보세요.
          </div>
          <div v-else class="recurring-ledger-list">
            <article
              v-for="rule in rules"
              :key="rule.id"
              class="recurring-ledger-item"
              :class="{ 'recurring-ledger-item--inactive': !rule.active }"
            >
              <div>
                <div class="recurring-ledger-item__title">
                  <strong>{{ rule.title }}</strong>
                  <span :class="['chip', rule.entryType === 'INCOME' ? 'chip--income' : 'chip--expense']">{{ entryTypeLabel(rule.entryType) }}</span>
                  <span v-if="!rule.active" class="chip chip--neutral">일시정지</span>
                </div>
                <p>{{ formatAmount(rule.amount) }} · {{ formatRuleSchedule(rule) }} · {{ formatCategory(rule) }}</p>
                <small>
                  {{ formatDate(rule.startDate) }}<template v-if="rule.endDate"> ~ {{ formatDate(rule.endDate) }}</template>
                  <template v-if="rule.active && rule.nextDueDate"> · 다음 예정 {{ formatDate(rule.nextDueDate) }}</template>
                </small>
              </div>
              <div class="recurring-ledger-item__actions">
                <button class="button button--secondary" type="button" :disabled="isSubmitting" @click="editRule(rule)">수정</button>
                <button class="button button--secondary" type="button" :disabled="isSubmitting" @click="copyRule(rule)">복사해서 새 등록</button>
                <button class="button" type="button" :disabled="isSubmitting" @click="toggleRule(rule)">
                  {{ isSubmitting && action === 'toggle-' + rule.id ? '변경 중...' : rule.active ? '일시정지' : '다시 사용' }}
                </button>
                <button
                  v-if="!rule.active"
                  class="button button--danger"
                  type="button"
                  :disabled="isSubmitting"
                  @click="deleteRule(rule)"
                >
                  {{ isSubmitting && action === 'delete-' + rule.id ? '삭제 중...' : '삭제' }}
                </button>
              </div>
            </article>
          </div>
        </section>
      </div>
    </div>
  </section>
</template>

<style scoped>
.recurring-ledger-layout {
  display: grid;
  grid-template-columns: minmax(280px, 0.85fr) minmax(0, 1.15fr);
  gap: 1rem;
  align-items: start;
}

.recurring-ledger-form {
  position: sticky;
  top: 1rem;
}

.recurring-ledger-field-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0.75rem;
}

.recurring-ledger-field-grid + .recurring-ledger-field-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.recurring-ledger-field-grid--schedule {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.recurring-ledger-mode {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0.5rem;
  margin: 0;
  padding: 0.55rem 0.65rem 0.65rem;
  border: 1px solid var(--line);
  background: var(--surface-soft);
}

.recurring-ledger-mode legend {
  grid-column: 1 / -1;
  padding: 0 0.25rem;
}

.recurring-ledger-mode__option {
  display: flex;
  gap: 0.5rem;
  align-items: center;
  min-height: 0;
  padding: 0.45rem 0.55rem;
  border: 1px solid transparent;
  cursor: pointer;
}

.recurring-ledger-mode__option:has(input:checked) {
  border-color: var(--brand);
  background: var(--brand-soft);
}

.recurring-ledger-mode__option span {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 0.1rem 0.45rem;
}

.recurring-ledger-mode__option small {
  flex-basis: 100%;
  color: var(--text-soft);
}

.recurring-ledger-form__actions,
.recurring-ledger-item__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.55rem;
}

.recurring-ledger-form__actions .button {
  flex: 1 1 12rem;
}

.recurring-ledger-results {
  display: grid;
  gap: 1rem;
}

.recurring-ledger-list {
  display: grid;
  gap: 0.65rem;
}

.recurring-ledger-item {
  display: flex;
  justify-content: space-between;
  gap: 1rem;
  align-items: center;
  padding: 0.9rem;
  border: 1px solid var(--line);
  background: var(--surface-soft);
}

.recurring-ledger-item--pending {
  border-color: color-mix(in srgb, var(--brand) 55%, var(--line));
  background: color-mix(in srgb, var(--brand-soft) 50%, var(--surface-soft));
}

.recurring-ledger-item--inactive {
  opacity: 0.72;
}

.recurring-ledger-item__title {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.45rem;
}

.recurring-ledger-item p,
.recurring-ledger-item small {
  display: block;
  margin: 0.25rem 0 0;
  color: var(--text-soft);
}

.recurring-ledger-item small {
  color: var(--text-muted);
}

@media (max-width: 900px) {
  .recurring-ledger-layout {
    grid-template-columns: 1fr;
  }

  .recurring-ledger-form {
    position: static;
  }
}

@media (max-width: 620px) {
  .recurring-ledger-field-grid,
  .recurring-ledger-field-grid + .recurring-ledger-field-grid,
  .recurring-ledger-field-grid--schedule {
    grid-template-columns: 1fr;
  }

  .recurring-ledger-mode {
    grid-template-columns: 1fr;
  }

  .recurring-ledger-mode legend {
    grid-column: auto;
  }

  .recurring-ledger-item {
    align-items: stretch;
    flex-direction: column;
  }
}
</style>
