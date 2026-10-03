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
