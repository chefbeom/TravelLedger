export const travelCurrencyOptions = [
  { code: 'KRW', label: '원화 (KRW)' },
  { code: 'USD', label: '달러 (USD)' },
  { code: 'JPY', label: '엔화 (JPY)' },
  { code: 'CNY', label: '위안 (CNY)' },
  { code: 'EUR', label: '유로 (EUR)' },
  { code: 'GBP', label: '파운드 (GBP)' },
  { code: 'THB', label: '바트 (THB)' },
  { code: 'TWD', label: '대만 달러 (TWD)' },
  { code: 'HKD', label: '홍콩 달러 (HKD)' },
  { code: 'VND', label: '동 (VND)' },
]

export const travelBudgetCategoryOptions = [
  '항공',
  '숙소',
  '보험',
  '식비',
  '교통',
  '쇼핑',
  '투어',
  '기타',
]

export function normalizeCurrencyCode(value) {
  return String(value || 'KRW').trim().toUpperCase()
}

export function normalizeTravelColor(value, fallback = '#3182F6') {
  return /^#[0-9A-Fa-f]{6}$/.test(String(value || '').trim()) ? String(value).trim().toUpperCase() : fallback
}

export function resolveTravelRate(rates, currencyCode) {
  const normalized = normalizeCurrencyCode(currencyCode)
  if (normalized === 'KRW') {
    return 1
  }

  const rate = rates.find((item) => item.currencyCode === normalized)
  return rate?.rateToKrw ? Number(rate.rateToKrw) : 0
}

export function convertToKrw(amount, currencyCode, rates) {
  const numericAmount = Number(amount ?? 0)
  const code = normalizeCurrencyCode(currencyCode)
  if (code === 'KRW') {
    return numericAmount
  }

  const rate = resolveTravelRate(rates, code)
  return rate ? numericAmount * rate : 0
}

export function convertFromKrw(krwAmount, currencyCode, rates) {
  const code = normalizeCurrencyCode(currencyCode)
  const numericKrw = Number(krwAmount ?? 0)
  if (code === 'KRW') {
    return numericKrw
  }

  const rate = resolveTravelRate(rates, code)
  return rate ? numericKrw / rate : 0
}

export function buildKrwGuideAmount(rateToKrw, krwAmount = 1000) {
  const rate = Number(rateToKrw ?? 0)
  if (!rate) {
    return 0
  }
  return krwAmount / rate
}

export function collectTravelCurrencies(travelData) {
  const codes = new Set(['KRW', 'USD', 'JPY', 'CNY', 'EUR'])
  if (!travelData) {
    return [...codes]
  }

  codes.add(normalizeCurrencyCode(travelData.homeCurrency))
  ;(travelData.plans ?? []).forEach((plan) => codes.add(normalizeCurrencyCode(plan.homeCurrency)))
  ;(travelData.budgetItems ?? []).forEach((item) => codes.add(normalizeCurrencyCode(item.currencyCode)))
  ;(travelData.records ?? []).forEach((item) => codes.add(normalizeCurrencyCode(item.currencyCode)))
  return [...codes]
}

function sumBy(items, keyResolver, valueResolver) {
  const bucket = new Map()

  items.forEach((item) => {
    const key = keyResolver(item)
    const current = bucket.get(key) ?? { key, total: 0, count: 0 }
    current.total += Number(valueResolver(item) ?? 0)
    current.count += 1
    bucket.set(key, current)
  })

  return [...bucket.values()].sort((a, b) => b.total - a.total)
}

function uniqueLabels(values) {
  return [...new Set(values.filter(Boolean).map((value) => String(value).trim()).filter(Boolean))]
}

export function buildTravelGalleryItems(travelData) {
  return [...(travelData?.mediaItems ?? [])].sort((a, b) => {
    const left = new Date(a.uploadedAt ?? 0).getTime()
    const right = new Date(b.uploadedAt ?? 0).getTime()
    return right - left
  })
}

export function buildTravelAnalytics(travelData, exchangeRates = []) {
  if (!travelData) {
    return {
      summary: {
        scopeLabel: '여행 없음',
        plannedTotalKrw: 0,
        actualTotalKrw: 0,
        remainingBudgetKrw: 0,
        budgetItemCount: 0,
        recordCount: 0,
        mediaItemCount: 0,
        headCount: 1,
        includedPlanCount: 0,
      },
      plannedByCategory: [],
      actualByCategory: [],
      categoryComparison: [],
      currencyBreakdown: [],
      dailyTotals: [],
      visitedCountries: [],
      visitedRegions: [],
      visitedPlaces: [],
      mapMarkers: [],
      galleryItems: [],
    }
  }

  const budgetItems = travelData.budgetItems ?? []
  const records = travelData.records ?? []
  const memoryRecords = travelData.memoryRecords ?? []
  const taggedRecords = [...records, ...memoryRecords]
  const mediaItems = buildTravelGalleryItems(travelData)
  const plannedTotalKrw = Number(travelData.plannedTotalKrw ?? 0)
  const actualTotalKrw = Number(travelData.actualTotalKrw ?? 0)

  const plannedByCategory = sumBy(
    budgetItems,
    (item) => item.category || '기타',
    (item) => item.amountKrw,
  ).map((item) => ({
    label: item.key,
    value: item.total,
    count: item.count,
  }))

  const actualByCategory = sumBy(
    records,
    (item) => item.category || '기타',
    (item) => item.amountKrw,
  ).map((item) => ({
    label: item.key,
    value: item.total,
    count: item.count,
  }))

  const categoryMap = new Map()
  plannedByCategory.forEach((item) => {
    categoryMap.set(item.label, {
      label: item.label,
      planned: item.value,
      actual: 0,
      gap: item.value,
    })
  })
  actualByCategory.forEach((item) => {
    const current = categoryMap.get(item.label) ?? {
      label: item.label,
      planned: 0,
      actual: 0,
      gap: 0,
    }
    current.actual = item.value
    current.gap = current.planned - current.actual
    categoryMap.set(item.label, current)
  })

  const rateMap = new Map(exchangeRates.map((item) => [item.currencyCode, item]))
  const currencyBreakdown = sumBy(
    records,
    (item) => normalizeCurrencyCode(item.currencyCode),
    (item) => item.amountKrw,
  ).map((item) => {
    const sourceItems = records.filter((record) => normalizeCurrencyCode(record.currencyCode) === item.key)
    const originalTotal = sourceItems.reduce((total, record) => total + Number(record.amount ?? 0), 0)
    const currentRate = rateMap.get(item.key)
    return {
      label: item.key,
      value: item.total,
      originalTotal,
      count: item.count,
      currentRateToKrw: currentRate?.rateToKrw ? Number(currentRate.rateToKrw) : 0,
      rateAvailable: Boolean(currentRate?.available),
    }
  })

  const dailyTotals = sumBy(
    records,
    (item) => item.expenseDate,
    (item) => item.amountKrw,
  )
    .map((item) => ({
      label: item.key,
      value: item.total,
      count: item.count,
    }))
    .sort((a, b) => a.label.localeCompare(b.label))

  const mediaByRecord = new Map()
  mediaItems.forEach((item) => {
    const bucket = mediaByRecord.get(item.recordId) ?? []
    bucket.push(item)
    mediaByRecord.set(item.recordId, bucket)
  })

  const visitedCountries = uniqueLabels(taggedRecords.map((item) => item.country))
  const visitedRegions = uniqueLabels(taggedRecords.map((item) => item.region))
  const visitedPlaces = uniqueLabels(taggedRecords.map((item) => item.placeName))

  const mapMarkers = taggedRecords
    .filter((item) => item.latitude !== null && item.latitude !== undefined && item.longitude !== null && item.longitude !== undefined)
    .map((item) => {
      const relatedMedia = mediaByRecord.get(item.id) ?? []
      const photoItems = relatedMedia.filter((media) => media.mediaType === 'PHOTO')
      const receiptItems = relatedMedia.filter((media) => media.mediaType === 'RECEIPT')
      const heroPhoto = photoItems[0] ?? null

      return {
        id: item.id,
        planId: item.planId ?? travelData.id ?? null,
        planName: item.planName ?? travelData.name ?? '',
        latitude: Number(item.latitude),
        longitude: Number(item.longitude),
        country: item.country || '',
        region: item.region || '',
        placeName: item.placeName || item.title,
        title: item.title,
        amount: Number(item.amount ?? 0),
        currencyCode: item.currencyCode,
        amountKrw: Number(item.amountKrw ?? 0),
        visitedDate: item.expenseDate || item.memoryDate || '',
        visitedTime: item.expenseTime || item.memoryTime || '',
        label: item.placeName || item.title,
        colorHex: normalizeTravelColor(item.planColorHex ?? travelData.colorHex),
        recordType: item.recordType || 'LEDGER',
        photoUrl: heroPhoto?.contentUrl || '',
        photoCount: photoItems.length,
        receiptCount: receiptItems.length,
        uploadedBy: heroPhoto?.uploadedBy || relatedMedia[0]?.uploadedBy || '',
        mediaItems: relatedMedia,
      }
    })

  return {
    summary: {
      scopeLabel: travelData.scopeLabel || travelData.name || '여행 통계',
      plannedTotalKrw,
        actualTotalKrw,
        remainingBudgetKrw: plannedTotalKrw - actualTotalKrw,
        budgetItemCount: budgetItems.length,
        recordCount: records.length,
        memoryRecordCount: memoryRecords.length,
        mediaItemCount: mediaItems.length,
        headCount: Number(travelData.headCount ?? 1),
      includedPlanCount: Number(travelData.includedPlanCount ?? (travelData.plans?.length ?? (travelData.id ? 1 : 0))),
    },
    plannedByCategory,
    actualByCategory,
    categoryComparison: [...categoryMap.values()].sort((a, b) => b.actual - a.actual || b.planned - a.planned),
    currencyBreakdown,
    dailyTotals,
    visitedCountries,
    visitedRegions,
    visitedPlaces,
    mapMarkers,
    galleryItems: mediaItems,
  }
}
