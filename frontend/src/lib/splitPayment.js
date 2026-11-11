const MAX_KRW = 9999999999999.99

// Use integer hundredths throughout so decimal inputs do not accumulate float errors.
function optionalCents(value) {
  if (String(value ?? '').trim() === '') return 0
  const number = Number(value)
  if (!Number.isFinite(number) || number < 0 || number > MAX_KRW
      || Math.abs(number * 100 - Math.round(number * 100)) > 0.0001) return null
  return Math.round(number * 100)
}

export function reconcileSplitAmounts(total, amounts, { changedIndex = -1, autoLast = true } = {}) {
  const values = [...amounts]
  const totalCents = optionalCents(total)
  if (!totalCents || values.length < 2) return { amounts: values, adjusted: false }
  const cents = values.map(optionalCents)
  if (cents.some(value => value === null)) return { amounts: values, adjusted: false }
  const last = values.length - 1
  let adjusted = false
  const set = (index, value) => {
    if (cents[index] !== value) { cents[index] = value; values[index] = String(value / 100); adjusted = true }
  }

  if (changedIndex >= 0 && changedIndex < last) {
    const others = cents.slice(0, last).reduce((sum, value, index) => sum + (index === changedIndex ? 0 : value), 0)
    set(changedIndex, Math.min(cents[changedIndex], Math.max(0, totalCents - others)))
  } else if (changedIndex < 0) {
    let available = totalCents
    for (let index = 0; index < last; index++) {
      set(index, Math.min(cents[index], available))
      available -= cents[index]
    }
  }
  const remainder = Math.max(0, totalCents - cents.slice(0, last).reduce((sum, value) => sum + value, 0))
  set(last, autoLast ? remainder : Math.min(cents[last], remainder))
  if (autoLast && String(values[last] ?? '').trim() === '') values[last] = String(remainder / 100)
  return { amounts: values, adjusted }
}

export function normalizeInstallmentMonths(value, method) {
  const text = String(value ?? '').trim()
  if (!text) return null
  const months = Number(text)
  if (method?.kind !== 'CARD' || !Number.isInteger(months) || months < 2 || months > 60) {
    throw new Error('할부는 카드 결제에서 2~60개월로 입력해 주세요.')
  }
  return months
}

export function normalizeInstallmentInterest(value, { allowUnknown = false } = {}) {
  if (value === 'FREE' || value === 'INTEREST') return value
  if (!value && allowUnknown) return null
  throw new Error('할부의 무이자 또는 유이자를 선택해 주세요.')
}

function money(value) {
  const number = Number(value)
  if (!Number.isFinite(number) || number <= 0 || number > MAX_KRW || Math.abs(number * 100 - Math.round(number * 100)) > 0.0001) {
    throw new Error('결제 금액은 0보다 크고 소수 둘째 자리 이하여야 합니다.')
  }
  return Math.round(number * 100)
}

export function prepareSplitPayment(entry, rows, methods) {
  if (entry.entryType !== 'EXPENSE' || entry.foreignCurrencyCode || entry.travelRecordId) {
    throw new Error('분할 결제는 원화 지출에서 사용할 수 있으며 여행 기록과 직접 연결할 수 없습니다.')
  }
  if (!Array.isArray(rows) || rows.length < 2 || rows.length > 10) throw new Error('결제수단을 2~10개 입력해 주세요.')
  const expected = money(entry.amount)
  let total = 0
  const parts = rows.map(row => {
    const method = methods.find(item => String(item.id) === String(row.paymentMethodId))
    if (!method) throw new Error('각 결제수단을 선택해 주세요.')
    const amount = money(row.amount)
    total += amount
    const installmentMonths = normalizeInstallmentMonths(row.installmentMonths, method)
    const installmentInterestType = installmentMonths && row.installmentInterestType
      ? normalizeInstallmentInterest(row.installmentInterestType) : null
    return { paymentMethodId: Number(method.id), amount: amount / 100, installmentMonths, installmentInterestType }
  })
  if (total !== expected) throw new Error('결제수단별 금액의 합계가 전체 거래 금액과 일치해야 합니다.')
  return parts
}

export function newSplitRequestId() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID()
  if (!globalThis.crypto?.getRandomValues) throw new Error('안전한 요청 번호를 만들 수 없습니다. 보안 연결을 확인해 주세요.')
  const bytes = globalThis.crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = Array.from(bytes, value => value.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
