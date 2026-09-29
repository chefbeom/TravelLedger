function normalizeText(value) {
  return String(value ?? '').trim()
}

function normalizeDate(value) {
  return normalizeText(value).slice(0, 10)
}

function resolvePlanId(item) {
  return item?.planId ?? item?.travelPlanId ?? item?.plan?.id ?? null
}

function resolvePlanName(item) {
  return normalizeText(item?.planName ?? item?.plan?.name) || '여행 미지정'
}

function planIdentity(planId, planName) {
  return planId == null ? `name:${planName}` : `id:${String(planId)}`
}

export function buildTravelJourneyDays(photoPins = [], journeyItems = []) {
  const grouped = new Map()
  const seenMediaIds = new Set()

  function registerJourneyDay(item, date) {
    const planId = resolvePlanId(item)
    const planName = resolvePlanName(item)
    const identity = planIdentity(planId, planName)
    if (!grouped.has(identity)) {
      grouped.set(identity, { identity, planId, planName, dates: new Set(), photoCounts: new Map() })
    }

    const group = grouped.get(identity)
    if (group.planName === '여행 미지정' && planName !== '여행 미지정') {
      group.planName = planName
    }
    group.dates.add(date)
    return group
  }

  photoPins.forEach((pin) => {
    const mediaId = pin?.mediaId ?? pin?.id
    const date = normalizeDate(pin?.memoryDate ?? pin?.expenseDate)
    const mediaKey = String(mediaId ?? '')
    if (mediaId == null || !date || !pin?.photoUrl || seenMediaIds.has(mediaKey)) {
      return
    }
    seenMediaIds.add(mediaKey)

    const group = registerJourneyDay(pin, date)
    group.photoCounts.set(date, (group.photoCounts.get(date) ?? 0) + 1)
  })

  journeyItems.forEach((item) => {
    const date = normalizeDate(item?.memoryDate ?? item?.routeDate ?? item?.expenseDate)
    if (date) {
      registerJourneyDay(item, date)
    }
  })

  return Array.from(grouped.values())
    .flatMap((group) => Array.from(group.dates)
      .sort((left, right) => left.localeCompare(right))
      .map((date, index) => ({
        key: JSON.stringify([group.identity, date]),
        planId: group.planId,
        planName: group.planName,
        date,
        dayNumber: index + 1,
        photoCount: group.photoCounts.get(date) ?? 0,
        label: `${group.planName} · ${index + 1}일차 · ${date}`,
      })))
    .sort((left, right) => left.planName.localeCompare(right.planName, 'ko')
      || left.date.localeCompare(right.date)
      || left.key.localeCompare(right.key))
}

export function matchesTravelJourneyDay(item, day) {
  if (!item || !day || normalizeDate(item.memoryDate ?? item.routeDate ?? item.expenseDate) !== day.date) {
    return false
  }

  const itemPlanId = resolvePlanId(item)
  if (day.planId != null) {
    return itemPlanId != null && String(itemPlanId) === String(day.planId)
  }

  return itemPlanId == null && resolvePlanName(item) === day.planName
}

export function sortTravelJourneyPhotos(photoPins = []) {
  return [...photoPins].sort((left, right) => {
    const leftDateTime = `${normalizeDate(left?.memoryDate ?? left?.expenseDate)} ${normalizeText(left?.memoryTime ?? left?.expenseTime)}`
    const rightDateTime = `${normalizeDate(right?.memoryDate ?? right?.expenseDate)} ${normalizeText(right?.memoryTime ?? right?.expenseTime)}`
    return leftDateTime.localeCompare(rightDateTime)
      || String(left?.mediaId ?? left?.id ?? '').localeCompare(String(right?.mediaId ?? right?.id ?? ''), undefined, { numeric: true })
  })
}
