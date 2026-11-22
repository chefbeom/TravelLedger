// Single place that decides what a user can do with a ledger entry that may carry
// cancellation/refund history. Mirrors the server rules in LedgerEntryEditPolicy (the server is authoritative).
// Pure functions: inputs are never mutated.
import { adjustmentsForEntry, compareLedgerChronologically } from './ledgerSheetGroups.js'

export const LEDGER_ENTRY_STATES = ['NORMAL', 'CANCELLED', 'PARTIALLY_REFUNDED', 'REFUNDED', 'REVERTED', 'RECORD']

const REASON_LOADING = '처리 이력을 불러오는 중입니다.'
const REASON_LOAD_FAILED = "처리 이력을 불러오지 못했습니다. 아래 '다시 시도'를 눌러 주세요."
const REASON_NO_CANCELLATION = '적용 중인 취소 기록을 찾을 수 없습니다. 관리자에게 문의해 주세요.'
const REASON_NO_REFUND = '적용 중인 환불 기록을 찾을 수 없습니다. 관리자에게 문의해 주세요.'
const REASON_TRAVEL = '여행 기록과 연결된 거래는 결제 취소할 수 없습니다.'
const REASON_CANCEL_BLOCKED_BY_REFUND = '환불 기록이 있어 결제 취소할 수 없습니다. 환불을 모두 취소한 뒤 가능합니다.'
const REASON_DELETE_CANCELLED = '결제 취소를 먼저 취소해야 삭제할 수 있습니다.'
const REASON_DELETE_REFUNDED = '적용 중인 환불을 모두 취소해야 삭제할 수 있습니다.'
const NOTE_APPLIED = '처리가 적용 중이라 금액·수입/지출 구분·결제수단·외화·할부·여행 연결은 바꿀 수 없습니다. 처리를 취소하면 모든 항목을 수정할 수 있습니다.'
const NOTE_REVERTED = '지난 처리 이력의 금액은 처리 당시 금액으로 보존됩니다.'

function cents(value) {
  return Math.round((Number(value) || 0) * 100)
}

function won(value) {
  return `₩${(Number(value) || 0).toLocaleString('en-US')}`
}

function isActiveRecord(record) {
  return Boolean(record) && !record.adjustmentVoidedAt
}

/** State from the original's own fields only (2.1). */
export function ledgerEntryState(entry) {
  if (!entry) return 'NORMAL'
  if (entry.adjustmentType) return 'RECORD'
  if (entry.cancelledAt) return 'CANCELLED'
  const refunded = Number(entry.refundedPrincipal) || 0
  if (refunded > 0 && refunded >= (Number(entry.amount) || 0)) return 'REFUNDED'
  if (refunded > 0) return 'PARTIALLY_REFUNDED'
  if (entry.hasAdjustmentHistory) return 'REVERTED'
  return 'NORMAL'
}

function effectiveState(entry, activeCancellation, activeRefunds) {
  const state = ledgerEntryState(entry)
  if (state !== 'NORMAL' && state !== 'REVERTED') return state
  // Stricter reading when the fields disagree with the known records (matches the server).
  if (activeCancellation || (state === 'NORMAL' && entry?.financiallyExcluded)) return 'CANCELLED'
  if (activeRefunds.length) return 'PARTIALLY_REFUNDED'
  return state
}

function stateLineFor(state, entry, refundable) {
  if (state === 'CANCELLED') return '결제 취소 적용 중 · 이 거래는 지출 합계에서 제외됩니다.'
  if (state === 'REFUNDED') return '환불 완료 · 원거래 금액 전액이 환불 처리되었습니다.'
  if (state === 'PARTIALLY_REFUNDED') {
    return `부분 환불 · 환불 기준 ${won(entry.refundedPrincipal)} · 남은 환불 가능 ${won(refundable)}`
  }
  if (state === 'REVERTED') return '지난 처리를 모두 취소했습니다 · 처리 이력만 남아 있으며 일반 거래처럼 수정·삭제할 수 있습니다.'
  return ''
}

function noteFor(state) {
  if (state === 'CANCELLED' || state === 'PARTIALLY_REFUNDED' || state === 'REFUNDED') return NOTE_APPLIED
  if (state === 'REVERTED') return NOTE_REVERTED
  return ''
}

function pendingReason(status, hasLocalRecords) {
  if (status === 'error' && !hasLocalRecords) return REASON_LOAD_FAILED
  return REASON_LOADING
}

export function ledgerEntryCapabilities(entry, adjustments = null, { historyStatus = 'ready' } = {}) {
  const origin = entry || {}
  const known = Array.isArray(adjustments)
  const records = known ? adjustments : []
  const bulkSelectable = !origin.hasAdjustmentHistory && !origin.adjustmentType
  const canShare = !origin.financiallyExcluded && !origin.adjustmentType

  if (origin.adjustmentType) {
    return {
      state: 'RECORD', hasActive: false, editMode: 'NONE', maxEntryDate: null, bulkSelectable, canShare, canDelete: false,
      refundableAmount: 0, activeCancellation: null, activeRefunds: [], actions: [], stateLine: '', note: '',
    }
  }

  const activeRecords = records.filter(isActiveRecord)
  const activeCancellationList = activeRecords
    .filter(record => record.adjustmentType === 'PAYMENT_CANCELLATION')
    .sort(compareLedgerChronologically)
  const activeCancellation = activeCancellationList.length ? activeCancellationList[activeCancellationList.length - 1] : null
  const activeRefunds = activeRecords
    .filter(record => record.adjustmentType === 'REFUND')
    .sort(compareLedgerChronologically)

  const state = effectiveState(origin, activeCancellation, activeRefunds)
  const refundedCents = cents(origin.refundedPrincipal)
  const refundableAmount = Math.max(0, cents(origin.amount) - refundedCents) / 100
  const hasActive = Boolean(origin.cancelledAt) || refundedCents > 0 || Boolean(origin.financiallyExcluded) || activeRecords.length > 0
  const editMode = hasActive ? 'LIMITED' : 'FULL'
  const canDelete = !hasActive && (state === 'NORMAL' || state === 'REVERTED')
  const isExpense = origin.entryType === 'EXPENSE'

  let maxEntryDate = null
  if (editMode === 'LIMITED' && activeRecords.length) {
    maxEntryDate = activeRecords.map(record => record.entryDate).filter(Boolean).sort()[0] || null
  }

  const actions = []
  const push = (id, label, enabled, description, reason = '') => {
    actions.push({ id, label, description: enabled ? description : (reason || description), enabled, reason: enabled ? '' : reason })
  }
  const pending = !known || historyStatus !== 'ready'

  const pushUncancel = () => {
    if (activeCancellation) {
      push('uncancel', '결제 취소 취소', true, '취소를 되돌려 이 거래를 지출 합계에 다시 포함합니다')
    } else if (pending) {
      push('uncancel', '결제 취소 취소', false, '', pendingReason(historyStatus, known && records.length > 0))
    } else {
      push('uncancel', '결제 취소 취소', false, '', REASON_NO_CANCELLATION)
    }
  }
  const pushUnrefund = () => {
    if (activeRefunds.length === 1) {
      const only = activeRefunds[0]
      push('unrefund', '환불 취소', true, `환불 입금 ${won(only.amount)}(${only.entryDate})을 되돌립니다`)
    } else if (activeRefunds.length > 1) {
      push('unrefund', '환불 취소', true, `적용 중인 환불 ${activeRefunds.length}건 중 하나를 골라 되돌립니다`)
    } else if (pending) {
      push('unrefund', '환불 취소', false, '', pendingReason(historyStatus, known && records.length > 0))
    } else {
      push('unrefund', '환불 취소', false, '', REASON_NO_REFUND)
    }
  }
  const pushEdit = () => {
    if (editMode === 'LIMITED') {
      push('edit', '거래 내용 수정', true, '제목·메모·분류·날짜·시간만 변경합니다(금액 등은 처리 적용 중)')
    } else {
      push('edit', '거래 내용 수정', true, state === 'REVERTED'
        ? '금액, 날짜, 분류 등 모든 항목을 변경합니다(지난 처리 이력은 보존)'
        : '금액, 날짜, 분류 등 모든 항목을 변경합니다')
    }
  }
  const pushDelete = () => {
    if (canDelete) {
      push('delete', '삭제', true, state === 'REVERTED' ? '지난 처리 이력과 함께 휴지통으로 이동합니다' : '휴지통으로 이동합니다')
    } else {
      push('delete', '삭제', false, '', state === 'CANCELLED' ? REASON_DELETE_CANCELLED : REASON_DELETE_REFUNDED)
    }
  }
  const refundText = '받은 금액과 차감·추가 금액을 기록합니다'

  if (state === 'CANCELLED') {
    pushUncancel()
    pushEdit()
    pushDelete()
  } else if (state === 'REFUNDED') {
    pushUnrefund()
    pushEdit()
    push('cancel', '결제 취소', false, '', REASON_CANCEL_BLOCKED_BY_REFUND)
    pushDelete()
  } else if (state === 'PARTIALLY_REFUNDED') {
    push('refund', '환불', true, `남은 ${won(refundableAmount)} 범위에서 추가 환불을 기록합니다`)
    pushUnrefund()
    pushEdit()
    push('cancel', '결제 취소', false, '', REASON_CANCEL_BLOCKED_BY_REFUND)
    pushDelete()
  } else {
    pushEdit()
    if (isExpense) {
      push('refund', '환불', true, refundText)
      if (origin.travelRecordId != null) push('cancel', '결제 취소', false, '', REASON_TRAVEL)
      else push('cancel', '결제 취소', true, '청구되지 않은 결제를 취소로 기록하고 합계에서 제외합니다')
    }
    pushDelete()
  }

  return {
    state,
    hasActive,
    editMode,
    maxEntryDate,
    bulkSelectable,
    canShare,
    canDelete,
    refundableAmount,
    activeCancellation,
    activeRefunds,
    actions,
    stateLine: stateLineFor(state, origin, refundableAmount),
    note: noteFor(state),
  }
}

const SPLIT_INTENTS = ['refund', 'cancel', 'uncancel', 'unrefund', 'delete']

/** Which row-level intents can be offered for one split payment (state based, no history fetch needed). */
export function splitPartIntents(part, adjustments = []) {
  const caps = ledgerEntryCapabilities(part, adjustments, { historyStatus: 'ready' })
  const enabled = id => caps.actions.some(action => action.id === id && action.enabled)
  const intents = []
  if (enabled('refund')) intents.push('refund')
  if (enabled('cancel')) intents.push('cancel')
  if (caps.state === 'CANCELLED') intents.push('uncancel')
  if (caps.state === 'REFUNDED' || caps.state === 'PARTIALLY_REFUNDED') intents.push('unrefund')
  if (enabled('delete')) intents.push('delete')
  return intents
}

export function splitRowCapabilities(row) {
  const parts = row?.parts || []
  const perPart = parts.map(part => splitPartIntents(part, adjustmentsForEntry(row, part.id)))
  const canEditGroup = parts.length > 0 && parts.every(part => ledgerEntryCapabilities(part).bulkSelectable)
  return {
    canEditGroup,
    editGroupReason: canEditGroup ? '' : "처리 이력이 있는 수단이 있어 묶음 전체 수정은 할 수 없습니다. '분할 결제 보기'에서 수단별 '관리'를 이용하세요.",
    intents: SPLIT_INTENTS.filter(intent => perPart.some(list => list.includes(intent))),
  }
}

export function buildLimitedEditPayload(origin, draft) {
  return {
    entryDate: draft.entryDate,
    entryTime: draft.entryTime || null,
    title: String(draft.title ?? '').trim(),
    memo: String(draft.memo ?? '').trim() || null,
    amount: Number(origin.amount),
    foreignCurrencyCode: origin.foreignCurrencyCode || null,
    foreignAmount: origin.foreignAmount != null ? Number(origin.foreignAmount) : null,
    exchangeRateToKrw: origin.exchangeRateToKrw != null ? Number(origin.exchangeRateToKrw) : null,
    entryType: origin.entryType,
    categoryGroupId: Number(draft.categoryGroupId),
    categoryDetailId: draft.categoryDetailId ? Number(draft.categoryDetailId) : null,
    paymentMethodId: origin.paymentMethodId ?? null,
    travelPlanId: origin.travelPlanId ?? null,
    travelRecordId: origin.travelRecordId ?? null,
    installmentMonths: origin.installmentMonths ?? null,
    installmentInterestType: origin.installmentInterestType ?? null,
  }
}

export function validateLimitedEditDraft(draft, capabilities) {
  const title = String(draft?.title ?? '').trim()
  if (!title) return '제목을 입력해 주세요.'
  if (title.length > 120) return '제목은 120자 이내로 입력해 주세요.'
  if (String(draft?.memo ?? '').trim().length > 500) return '메모는 500자 이내로 입력해 주세요.'
  if (!draft?.entryDate) return '날짜를 입력해 주세요.'
  const max = capabilities?.maxEntryDate
  if (max && String(draft.entryDate) > max) return `처리일(${max})보다 늦은 날짜로 바꿀 수 없습니다.`
  if (!draft?.categoryGroupId) return '대분류를 선택해 주세요.'
  return ''
}
