import test from 'node:test'
import assert from 'node:assert/strict'
import { normalizeInstallmentMonths, normalizeInstallmentInterest, prepareSplitPayment, reconcileSplitAmounts } from '../../src/lib/splitPayment.js'

const methods = [{ id: 1, kind: 'CARD' }, { id: 2, kind: 'CASH' }, { id: 3, kind: 'CARD' }]
const entry = { entryType: 'EXPENSE', amount: 10000, foreignCurrencyCode: null, travelRecordId: null }

test('split amounts add up once and installment is metadata on the selected card part', () => {
  const parts = prepareSplitPayment(entry, [
    { paymentMethodId: '1', amount: '6000', installmentMonths: '3' },
    { paymentMethodId: '2', amount: '4000', installmentMonths: '' },
  ], methods)
  assert.deepEqual(parts, [{ paymentMethodId: 1, amount: 6000, installmentMonths: 3, installmentInterestType: null }, { paymentMethodId: 2, amount: 4000, installmentMonths: null, installmentInterestType: null }])
  assert.equal(normalizeInstallmentMonths('12', methods[0]), 12)
  assert.equal(normalizeInstallmentMonths('', methods[0]), null)
})

test('split rejects mismatched totals, invalid methods and unsupported foreign transactions', () => {
  const rows = [{ paymentMethodId: 1, amount: 60 }, { paymentMethodId: 2, amount: 40 }]
  assert.throws(() => prepareSplitPayment(entry, rows, methods), /합계/)
  assert.throws(() => prepareSplitPayment({ ...entry, amount: 100, foreignCurrencyCode: 'USD' }, rows, methods), /원화/)
  assert.throws(() => prepareSplitPayment({ ...entry, amount: 100 }, [rows[0], { ...rows[1], installmentMonths: 3 }], methods), /카드/)
  assert.throws(() => normalizeInstallmentMonths('1', methods[0]), /2~60/)
  assert.throws(() => normalizeInstallmentMonths('3.5', methods[0]), /2~60/)
  assert.throws(() => prepareSplitPayment({ ...entry, amount: 100 }, [rows[0], { ...rows[1], paymentMethodId: 999 }], methods), /선택/)
})

test('fractional values are compared in cents without binary rounding errors', () => {
  const rows = [{ paymentMethodId: 1, amount: '0.1' }, { paymentMethodId: 2, amount: '0.2' }]
  assert.equal(prepareSplitPayment({ ...entry, amount: 0.3 }, rows, methods).length, 2)
  assert.throws(() => prepareSplitPayment({ ...entry, amount: 0.3 }, [rows[0], { ...rows[1], amount: '0.201' }], methods), /소수 둘째/)
})

test('interest classification is validated and retained on each installment', () => {
  const parts = prepareSplitPayment(entry, [
    { paymentMethodId: 1, amount: 6000, installmentMonths: 3, installmentInterestType: 'FREE' },
    { paymentMethodId: 3, amount: 4000, installmentMonths: 6, installmentInterestType: 'INTEREST' },
  ], methods)
  assert.deepEqual(parts.map(part => part.installmentInterestType), ['FREE', 'INTEREST'])
  assert.throws(() => normalizeInstallmentInterest(''), /무이자/)
  assert.throws(() => normalizeInstallmentInterest('OTHER'), /무이자/)
  assert.equal(normalizeInstallmentInterest('', { allowUnknown: true }), null)
})

test('split remainder follows previous amounts and the last amount can be edited within the total', () => {
  assert.deepEqual(reconcileSplitAmounts('50000', ['30000', '50000'], { changedIndex: 0 }).amounts, ['30000', '20000'])
  assert.deepEqual(reconcileSplitAmounts('50000', ['30000', '50000'], { changedIndex: 1, autoLast: false }).amounts, ['30000', '20000'])
  assert.deepEqual(reconcileSplitAmounts('50000', ['30000', '10000'], { changedIndex: 1, autoLast: false }).amounts, ['30000', '10000'])
  assert.deepEqual(reconcileSplitAmounts('50000', ['45000', '10000', '0'], { changedIndex: 0 }).amounts, ['40000', '10000', '0'])
  assert.deepEqual(reconcileSplitAmounts('30000', ['25000', '10000', '15000']).amounts, ['25000', '5000', '0'])
  assert.deepEqual(reconcileSplitAmounts('0.30', ['0.10', '0.20'], { changedIndex: 0 }).amounts, ['0.10', '0.20'])
  assert.deepEqual(reconcileSplitAmounts('', ['30000', '20000']).amounts, ['30000', '20000'])
  assert.deepEqual(reconcileSplitAmounts('1', ['0.123', '0']).amounts, ['0.123', '0'])
})
