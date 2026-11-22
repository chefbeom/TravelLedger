import test from 'node:test'
import assert from 'node:assert/strict'
import {
  buildLimitedEditPayload, ledgerEntryCapabilities, ledgerEntryState, splitRowCapabilities, validateLimitedEditDraft,
} from '../../src/lib/ledgerEntryCapabilities.js'

const base = { id: 1, entryType: 'EXPENSE', amount: 29000, entryDate: '2026-10-22', title: '구독 서비스 A', paymentMethodId: 103 }
const cancelled = { ...base, hasAdjustmentHistory: true, financiallyExcluded: true, cancelledAt: '2026-10-22T09:30:00' }
const record = { id: 2, adjustmentType: 'PAYMENT_CANCELLATION', adjustmentOriginId: 1, entryDate: '2026-10-23', amount: 29000 }
const ids = caps => caps.actions.map(action => action.id)
const pairs = caps => caps.actions.map(action => [action.id, action.enabled])

test('normal expense and income action lists', () => {
  assert.deepEqual(ids(ledgerEntryCapabilities(base)), ['edit', 'refund', 'cancel', 'delete'])
  assert.deepEqual(ids(ledgerEntryCapabilities({ ...base, entryType: 'INCOME' })), ['edit', 'delete'])
  const travel = ledgerEntryCapabilities({ ...base, travelRecordId: 5 }).actions.find(action => action.id === 'cancel')
  assert.equal(travel.enabled, false)
  assert.equal(travel.reason, '여행 기록과 연결된 거래는 결제 취소할 수 없습니다.')
  assert.equal(travel.description, travel.reason)
  const normal = ledgerEntryCapabilities(base)
  assert.equal(normal.state, 'NORMAL')
  assert.equal(normal.editMode, 'FULL')
  assert.equal(normal.stateLine, '')
  assert.equal(normal.note, '')
})

test('cancelled state offers uncancel first, limited edit and a disabled delete', () => {
  const caps = ledgerEntryCapabilities(cancelled, [record])
  assert.equal(caps.state, 'CANCELLED')
  assert.equal(caps.editMode, 'LIMITED')
  assert.equal(caps.maxEntryDate, '2026-10-23')
  assert.equal(caps.activeCancellation.id, 2)
  assert.deepEqual(pairs(caps), [['uncancel', true], ['edit', true], ['delete', false]])
  assert.equal(caps.actions[2].reason, '결제 취소를 먼저 취소해야 삭제할 수 있습니다.')
  assert.equal(caps.actions[0].description, '취소를 되돌려 이 거래를 지출 합계에 다시 포함합니다')
  assert.equal(caps.stateLine, '결제 취소 적용 중 · 이 거래는 지출 합계에서 제외됩니다.')
  assert.match(caps.note, /^처리가 적용 중이라 금액/)
  const loading = ledgerEntryCapabilities(cancelled, null, { historyStatus: 'loading' }).actions[0]
  assert.equal(loading.id, 'uncancel')
  assert.equal(loading.enabled, false)
  assert.equal(loading.reason, '처리 이력을 불러오는 중입니다.')
})

test('reverted entries are fully editable and deletable again', () => {
  const reverted = { ...base, hasAdjustmentHistory: true }
  const caps = ledgerEntryCapabilities(reverted, [{ ...record, adjustmentVoidedAt: '2026-10-23T10:00:00' }])
  assert.equal(caps.state, 'REVERTED')
  assert.equal(caps.editMode, 'FULL')
  assert.equal(caps.canDelete, true)
  assert.equal(caps.maxEntryDate, null)
  assert.deepEqual(ids(caps), ['edit', 'refund', 'cancel', 'delete'])
  assert.equal(caps.actions[0].description, '금액, 날짜, 분류 등 모든 항목을 변경합니다(지난 처리 이력은 보존)')
  assert.equal(caps.actions[3].description, '지난 처리 이력과 함께 휴지통으로 이동합니다')
  assert.equal(caps.note, '지난 처리 이력의 금액은 처리 당시 금액으로 보존됩니다.')
})

test('partial refund keeps refund, offers unrefund and locks cancel/delete', () => {
  const partial = { ...base, amount: 10000, hasAdjustmentHistory: true, refundedPrincipal: 3000 }
  const refunds = [{ id: 8, adjustmentType: 'REFUND', adjustmentOriginId: 1, entryDate: '2026-10-22', entryTime: '13:00', amount: 2500, adjustmentPrincipal: 3000 }]
  const caps = ledgerEntryCapabilities(partial, refunds)
  assert.equal(caps.state, 'PARTIALLY_REFUNDED')
  assert.equal(caps.refundableAmount, 7000)
  assert.equal(caps.editMode, 'LIMITED')
  assert.deepEqual(pairs(caps), [['refund', true], ['unrefund', true], ['edit', true], ['cancel', false], ['delete', false]])
  assert.equal(caps.actions[0].description, '남은 ₩7,000 범위에서 추가 환불을 기록합니다')
  assert.equal(caps.actions[1].description, '환불 입금 ₩2,500(2026-10-22)을 되돌립니다')
  assert.equal(caps.actions[3].reason, '환불 기록이 있어 결제 취소할 수 없습니다. 환불을 모두 취소한 뒤 가능합니다.')
  assert.equal(caps.actions[4].reason, '적용 중인 환불을 모두 취소해야 삭제할 수 있습니다.')
  assert.equal(caps.stateLine, '부분 환불 · 환불 기준 ₩3,000 · 남은 환불 가능 ₩7,000')
})

test('fields that disagree with known records resolve to the stricter state', () => {
  const reverted = { ...base, hasAdjustmentHistory: true }
  assert.equal(ledgerEntryCapabilities(reverted, [record]).editMode, 'LIMITED')
  assert.equal(ledgerEntryCapabilities(reverted, [record]).state, 'CANCELLED')
  const refundRecord = { id: 3, adjustmentType: 'REFUND', adjustmentOriginId: 1, entryDate: '2026-10-22', amount: 100 }
  assert.equal(ledgerEntryCapabilities(reverted, [refundRecord]).state, 'PARTIALLY_REFUNDED')
  const recordCaps = ledgerEntryCapabilities({ id: 2, adjustmentType: 'REFUND' })
  assert.equal(recordCaps.editMode, 'NONE')
  assert.deepEqual(recordCaps.actions, [])
})

test('state priority prefers a cancellation over refund fields', () => {
  assert.equal(ledgerEntryState({ ...base, cancelledAt: '2026-10-22T09:30:00', refundedPrincipal: 100 }), 'CANCELLED')
})

test('fully refunded entries cannot be refunded again', () => {
  const full = { ...base, hasAdjustmentHistory: true, refundedPrincipal: 29000 }
  const caps = ledgerEntryCapabilities(full, [])
  assert.equal(caps.state, 'REFUNDED')
  assert.deepEqual(pairs(caps).map(([id]) => id), ['unrefund', 'edit', 'cancel', 'delete'])
  assert.equal(caps.actions[2].enabled, false)
  assert.equal(caps.actions[3].enabled, false)
  assert.equal(caps.refundableAmount, 0)
})

test('refundable amount is computed in cents', () => {
  const caps = ledgerEntryCapabilities({ ...base, amount: '10000.10', refundedPrincipal: '0.20', hasAdjustmentHistory: true }, [])
  assert.equal(caps.refundableAmount, 9999.9)
})

test('several active refunds ask which one to cancel and are time ordered', () => {
  const partial = { ...base, amount: 100000, hasAdjustmentHistory: true, refundedPrincipal: 50000 }
  const late = { id: 11, adjustmentType: 'REFUND', entryDate: '2026-10-22', entryTime: '15:00', amount: 20000 }
  const early = { id: 10, adjustmentType: 'REFUND', entryDate: '2026-10-22', entryTime: '13:00', amount: 30000 }
  const caps = ledgerEntryCapabilities(partial, [late, early])
  assert.deepEqual(caps.activeRefunds.map(item => item.id), [10, 11])
  assert.equal(caps.actions.find(action => action.id === 'unrefund').description, '적용 중인 환불 2건 중 하나를 골라 되돌립니다')
})

test('history load failure and data mismatch reasons', () => {
  const failed = ledgerEntryCapabilities(cancelled, null, { historyStatus: 'error' }).actions[0]
  assert.equal(failed.reason, "처리 이력을 불러오지 못했습니다. 아래 '다시 시도'를 눌러 주세요.")
  const voided = [{ ...record, adjustmentVoidedAt: '2026-10-23T10:00:00' }]
  const mismatch = ledgerEntryCapabilities(cancelled, voided, { historyStatus: 'ready' }).actions[0]
  assert.equal(mismatch.reason, '적용 중인 취소 기록을 찾을 수 없습니다. 관리자에게 문의해 주세요.')
})

test('maxEntryDate uses the earliest active record only', () => {
  const records = [
    { id: 2, adjustmentType: 'PAYMENT_CANCELLATION', entryDate: '2026-10-25' },
    { id: 3, adjustmentType: 'PAYMENT_CANCELLATION', entryDate: '2026-10-23' },
    { id: 4, adjustmentType: 'PAYMENT_CANCELLATION', entryDate: '2026-10-21', adjustmentVoidedAt: '2026-10-22T00:00:00' },
  ]
  assert.equal(ledgerEntryCapabilities(cancelled, records).maxEntryDate, '2026-10-23')
})

test('bulk selection and sharing keep the previous conditions', () => {
  const reverted = ledgerEntryCapabilities({ ...base, hasAdjustmentHistory: true }, [])
  assert.equal(reverted.bulkSelectable, false)
  assert.equal(reverted.canShare, true)
  const cancelledCaps = ledgerEntryCapabilities(cancelled, [record])
  assert.equal(cancelledCaps.canShare, false)
  assert.equal(ledgerEntryCapabilities(base).bulkSelectable, true)
})

test('split row capabilities summarise per-method states', () => {
  const partA = { ...cancelled, id: 1 }
  const partB = { ...base, id: 5 }
  const row = { kind: 'split', parts: [partA, partB], adjustments: [{ ...record, adjustmentOriginId: 1 }] }
  const caps = splitRowCapabilities(row)
  assert.equal(caps.canEditGroup, false)
  assert.match(caps.editGroupReason, /^처리 이력이 있는 수단이 있어/)
  for (const intent of ['uncancel', 'refund', 'cancel', 'delete']) assert.ok(caps.intents.includes(intent), intent)
  assert.equal(caps.intents.includes('unrefund'), false)
  const plain = splitRowCapabilities({ kind: 'split', parts: [{ ...base, id: 1 }, { ...base, id: 2 }], adjustments: [] })
  assert.equal(plain.canEditGroup, true)
  assert.equal(plain.editGroupReason, '')
})

test('limited edit payload keeps financial fields and normalizes empty values', () => {
  const payload = buildLimitedEditPayload(
    { ...cancelled, foreignCurrencyCode: 'USD', foreignAmount: 20, exchangeRateToKrw: 1450, categoryGroupId: 101, installmentMonths: null },
    { entryDate: '2026-10-21', entryTime: '', title: ' 구독 서비스 A2 ', memo: '', categoryGroupId: '101', categoryDetailId: '' },
  )
  assert.deepEqual(payload, {
    entryDate: '2026-10-21', entryTime: null, title: '구독 서비스 A2', memo: null, amount: 29000, foreignCurrencyCode: 'USD',
    foreignAmount: 20, exchangeRateToKrw: 1450, entryType: 'EXPENSE', categoryGroupId: 101, categoryDetailId: null,
    paymentMethodId: 103, travelPlanId: null, travelRecordId: null, installmentMonths: null, installmentInterestType: null,
  })
})

test('limited edit draft validation messages', () => {
  const caps = { maxEntryDate: '2026-10-23' }
  const ok = { entryDate: '2026-10-22', title: '제목', memo: '', categoryGroupId: '101' }
  assert.equal(validateLimitedEditDraft(ok, caps), '')
  assert.equal(validateLimitedEditDraft({ ...ok, title: '  ' }, caps), '제목을 입력해 주세요.')
  assert.equal(validateLimitedEditDraft({ ...ok, title: 'a'.repeat(121) }, caps), '제목은 120자 이내로 입력해 주세요.')
  assert.equal(validateLimitedEditDraft({ ...ok, memo: 'a'.repeat(501) }, caps), '메모는 500자 이내로 입력해 주세요.')
  assert.equal(validateLimitedEditDraft({ ...ok, entryDate: '' }, caps), '날짜를 입력해 주세요.')
  assert.equal(validateLimitedEditDraft({ ...ok, entryDate: '2026-10-24' }, caps), '처리일(2026-10-23)보다 늦은 날짜로 바꿀 수 없습니다.')
  assert.equal(validateLimitedEditDraft({ ...ok, categoryGroupId: '' }, caps), '대분류를 선택해 주세요.')
})

test('inputs are not mutated', () => {
  const entry = structuredClone(cancelled)
  const list = structuredClone([record, { ...record, id: 9, entryDate: '2026-10-21' }])
  const entryBefore = structuredClone(entry)
  const listBefore = structuredClone(list)
  ledgerEntryCapabilities(entry, list)
  splitRowCapabilities({ parts: [entry], adjustments: list })
  assert.deepEqual(entry, entryBefore)
  assert.deepEqual(list, listBefore)
})
