const MAX_KRW = 9999999999999.99

export function normalizeInstallmentMonths(value, method) {
  const text = String(value ?? '').trim()
  if (!text) return null
  const months = Number(text)
  if (method?.kind !== 'CARD' || !Number.isInteger(months) || months < 2 || months > 60) {
    throw new Error('할부는 카드 결제에서 2~60개월로 입력해 주세요.')
  }
  return months
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
    return { paymentMethodId: Number(method.id), amount: amount / 100, installmentMonths }
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
