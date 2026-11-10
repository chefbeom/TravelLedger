import test from 'node:test'
import assert from 'node:assert/strict'
import { normalizeInstallmentMonths, prepareSplitPayment } from '../../src/lib/splitPayment.js'

const methods = [{ id: 1, kind: 'CARD' }, { id: 2, kind: 'CASH' }, { id: 3, kind: 'CARD' }]
const entry = { entryType: 'EXPENSE', amount: 10000, foreignCurrencyCode: null, travelRecordId: null }

test('split amounts add up once and installment is metadata on the selected card part', () => {
  const parts = prepareSplitPayment(entry, [
    { paymentMethodId: '1', amount: '6000', installmentMonths: '3' },
    { paymentMethodId: '2', amount: '4000', installmentMonths: '' },
  ], methods)
  assert.deepEqual(parts, [{ paymentMethodId: 1, amount: 6000, installmentMonths: 3 }, { paymentMethodId: 2, amount: 4000, installmentMonths: null }])
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
