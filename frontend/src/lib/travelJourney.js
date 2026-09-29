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

function normalizeRouteCoordinate(point) {
  const latitude = Number(point?.latitude ?? point?.lat)
  const longitude = Number(point?.longitude ?? point?.lng ?? point?.lon)
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude)
    || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
    return null
  }
  return { latitude, longitude }
}

function distanceBetweenCoordinates(left, right) {
  const radians = (degrees) => degrees * (Math.PI / 180)
  const latitudeDelta = radians(right.latitude - left.latitude)
  const longitudeDelta = radians(right.longitude - left.longitude)
  const leftLatitude = radians(left.latitude)
  const rightLatitude = radians(right.latitude)
  const rawHaversine = Math.sin(latitudeDelta / 2) ** 2
    + Math.cos(leftLatitude) * Math.cos(rightLatitude) * Math.sin(longitudeDelta / 2) ** 2
  const haversine = Math.min(1, Math.max(0, rawHaversine))
  return 6_371_000 * 2 * Math.atan2(Math.sqrt(haversine), Math.sqrt(1 - haversine))
}

export function buildTravelRoutePlaybackPath(route) {
  const points = []
  let distanceMeters = 0

  ;(Array.isArray(route?.points) ? route.points : []).forEach((source) => {
    const point = normalizeRouteCoordinate(source)
    if (!point) {
      return
    }
    const previous = points.at(-1)
    if (previous) {
      const segmentMeters = distanceBetweenCoordinates(previous, point)
      if (segmentMeters < 0.1) {
        return
      }
      distanceMeters += segmentMeters
    }
    points.push({ ...point, distanceMeters })
  })

  return {
    points,
    totalDistanceMeters: distanceMeters,
    totalDistanceKm: distanceMeters / 1000,
    isPlayable: points.length >= 2 && distanceMeters > 0,
  }
}

export function getTravelRoutePosition(path, distanceMeters) {
  const points = path?.points
  if (!Array.isArray(points) || points.length < 2 || !(path.totalDistanceMeters > 0)) {
    return null
  }

  const targetDistance = Math.max(0, Math.min(Number(distanceMeters) || 0, path.totalDistanceMeters))
  let low = 1
  let high = points.length - 1
  while (low < high) {
    const middle = Math.floor((low + high) / 2)
    if (points[middle].distanceMeters < targetDistance) {
      low = middle + 1
    } else {
      high = middle
    }
  }

  const end = points[low]
  const start = points[low - 1]
  const segmentDistance = end.distanceMeters - start.distanceMeters
  const ratio = segmentDistance > 0
    ? Math.max(0, Math.min(1, (targetDistance - start.distanceMeters) / segmentDistance))
    : 0

  return {
    latitude: start.latitude + ((end.latitude - start.latitude) * ratio),
    longitude: start.longitude + ((end.longitude - start.longitude) * ratio),
  }
}
