// Sender-owned IDs are deliberately never carried into an imported transaction.
export function buildSharedLedgerDraft(snapshot, categories, payments) {
  const group = categories.find((item) => item.active !== false && item.entryType === snapshot.entryType && item.name === snapshot.categoryGroupName)
    || categories.find((item) => item.active !== false && item.entryType === snapshot.entryType)
  const detail = (group?.details || []).find((item) => item.active !== false && item.name === snapshot.categoryDetailName)
  const payment = payments.find((item) => item.active !== false && item.name === snapshot.paymentMethodName)
    || payments.find((item) => item.active !== false)
  return {
    entryDate: snapshot.entryDate,
    entryTime: snapshot.entryTime || '',
    title: snapshot.title || '',
    memo: snapshot.memo || '',
    amount: snapshot.amount,
    foreignCurrencyCode: snapshot.foreignCurrencyCode || '',
    foreignAmount: snapshot.foreignAmount ?? '',
    exchangeRateToKrw: snapshot.exchangeRateToKrw ?? '',
    entryType: snapshot.entryType,
    categoryGroupId: group?.id ?? '',
    categoryDetailId: detail?.id ?? '',
    paymentMethodId: snapshot.entryType === 'INCOME' ? '' : (payment?.id ?? ''),
  }
}

export function buildSharedLedgerPayload(draft) {
  const foreign = draft.foreignCurrencyCode && draft.foreignCurrencyCode !== 'KRW'
  return {
    entryDate: draft.entryDate,
    entryTime: draft.entryTime || null,
    title: draft.title.trim(),
    memo: draft.memo.trim(),
    amount: Number(draft.amount),
    entryType: draft.entryType,
    categoryGroupId: Number(draft.categoryGroupId),
    categoryDetailId: draft.categoryDetailId ? Number(draft.categoryDetailId) : null,
    paymentMethodId: draft.entryType === 'INCOME' ? null : Number(draft.paymentMethodId),
    foreignCurrencyCode: foreign ? draft.foreignCurrencyCode : null,
    foreignAmount: foreign ? Number(draft.foreignAmount) : null,
    exchangeRateToKrw: foreign ? Number(draft.exchangeRateToKrw) : null,
    travelPlanId: null,
    travelRecordId: null,
  }
}

// Sender-side editing keeps the sender's catalog IDs. Receiver-side importing
// must continue to use buildSharedLedgerDraft's name-to-own-ID mapping instead.
export function buildLedgerShareDraft(source) {
  return { entryDate: source.entryDate || '', entryTime: source.entryTime || '', title: source.title || '', memo: source.memo || '',
    amount: source.amount ?? '', entryType: source.entryType || 'EXPENSE',
    categoryGroupId: source.categoryGroupId ?? '', categoryDetailId: source.categoryDetailId ?? '',
    paymentMethodId: source.entryType === 'INCOME' ? '' : source.paymentMethodId ?? '',
    foreignCurrencyCode: source.foreignCurrencyCode || 'KRW', foreignAmount: source.foreignAmount ?? '', exchangeRateToKrw: source.exchangeRateToKrw ?? '' }
}

export function buildLedgerShareOverride(draft) {
  if (!draft.entryDate || !draft.title.trim() || draft.title.trim().length > 120 || draft.memo.length > 500)
    throw new Error('공유할 날짜·제목을 확인해 주세요. 제목은 120자, 거래 메모는 500자까지 입력할 수 있습니다.')
  if (!['INCOME', 'EXPENSE'].includes(draft.entryType) || !Number(draft.categoryGroupId) || draft.entryType === 'EXPENSE' && !Number(draft.paymentMethodId))
    throw new Error('공유할 구분·대분류·결제수단을 선택해 주세요.')
  const foreign = draft.foreignCurrencyCode && draft.foreignCurrencyCode !== 'KRW'
  if (foreign && (!/^[A-Z]{3}$/.test(draft.foreignCurrencyCode) || !Number.isFinite(Number(draft.foreignAmount)) || Number(draft.foreignAmount) <= 0
    || !Number.isFinite(Number(draft.exchangeRateToKrw)) || Number(draft.exchangeRateToKrw) <= 0))
    throw new Error('외화 금액과 적용 환율을 0보다 큰 값으로 입력해 주세요.')
  const amount = foreign ? Math.round(Number(draft.foreignAmount) * Number(draft.exchangeRateToKrw)) : Number(draft.amount)
  if (!Number.isFinite(amount) || amount < .01 || amount > 9999999999999.99) throw new Error('공유할 원화 금액을 올바르게 입력해 주세요.')
  return buildSharedLedgerPayload({ ...draft, amount })
}
