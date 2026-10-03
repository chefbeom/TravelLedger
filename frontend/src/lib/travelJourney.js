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

export function findNextTravelJourneyDay(days = [], currentDayKey) {
  const currentDay = days.find((day) => day?.key === currentDayKey)
  if (!currentDay) {
    return null
  }

  return [...days]
    .filter((day) => {
      const samePlan = currentDay.planId != null
        ? day?.planId != null && String(day.planId) === String(currentDay.planId)
        : day?.planId == null && day?.planName === currentDay.planName
      return samePlan && String(day?.date ?? '').localeCompare(currentDay.date) > 0 && Number(day?.photoCount || 0) > 0
    })
    .sort((left, right) => String(left.date).localeCompare(String(right.date)))[0] ?? null
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

function projectCoordinateToTravelPath(path, coordinate, minimumDistanceMeters = 0) {
  const target = normalizeRouteCoordinate(coordinate)
  const points = path?.points
  if (!target || !Array.isArray(points) || points.length < 2) {
    return null
  }

  let nearest = null
  let nearestAfterMinimum = null
  for (let index = 1; index < points.length; index += 1) {
    const start = points[index - 1]
    const end = points[index]
    const meanLatitude = ((start.latitude + end.latitude + target.latitude) / 3) * (Math.PI / 180)
    const longitudeScale = 111_320 * Math.cos(meanLatitude)
    const latitudeScale = 110_574
    const segmentX = (end.longitude - start.longitude) * longitudeScale
    const segmentY = (end.latitude - start.latitude) * latitudeScale
    const targetX = (target.longitude - start.longitude) * longitudeScale
    const targetY = (target.latitude - start.latitude) * latitudeScale
    const segmentLengthSquared = (segmentX ** 2) + (segmentY ** 2)
    const ratio = segmentLengthSquared > 0
      ? Math.max(0, Math.min(1, ((targetX * segmentX) + (targetY * segmentY)) / segmentLengthSquared))
      : 0
    const projected = {
      latitude: start.latitude + ((end.latitude - start.latitude) * ratio),
      longitude: start.longitude + ((end.longitude - start.longitude) * ratio),
      distanceMeters: start.distanceMeters + ((end.distanceMeters - start.distanceMeters) * ratio),
    }
    const candidate = {
      ...projected,
      distanceFromRouteMeters: distanceBetweenCoordinates(target, projected),
    }
    if (!nearest || candidate.distanceFromRouteMeters < nearest.distanceFromRouteMeters) {
      nearest = candidate
    }
    if (candidate.distanceMeters + 0.01 >= minimumDistanceMeters
      && (!nearestAfterMinimum || candidate.distanceFromRouteMeters < nearestAfterMinimum.distanceFromRouteMeters)) {
      nearestAfterMinimum = candidate
    }
  }

  return nearestAfterMinimum ?? nearest
}

export function buildTravelRoutePlaybackPath(routeOrRoutes, startCoordinate = null) {
  const routes = Array.isArray(routeOrRoutes) ? routeOrRoutes : [routeOrRoutes]
  const candidates = routes.map((route) => {
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
    return { points, totalDistanceMeters: distanceMeters }
  }).filter((path) => path.points.length >= 2 && path.totalDistanceMeters > 0)

  // A manually drawn segment and a GPX segment can describe the same route.
  // Ignore exact overlaps so the combined journey doesn't replay them twice.
  const seenPaths = new Set()
  const uniqueCandidates = candidates.filter((path) => {
    const coordinates = path.points.map(({ latitude, longitude }) => `${latitude.toFixed(5)},${longitude.toFixed(5)}`)
    const forward = coordinates.join('|')
    const reverse = [...coordinates].reverse().join('|')
    const signature = forward < reverse ? forward : reverse
    if (seenPaths.has(signature)) {
      return false
    }
    seenPaths.add(signature)
    return true
  })

  const points = []
  let distanceMeters = 0
  let current = null
  const remaining = [...uniqueCandidates]
  while (remaining.length) {
    let selectedIndex = 0
    let selectedReversed = false
    let shortestDistance = Number.POSITIVE_INFINITY
    if (current) {
      remaining.forEach((candidate, index) => {
        const startDistance = distanceBetweenCoordinates(current, candidate.points[0])
        const endDistance = distanceBetweenCoordinates(current, candidate.points.at(-1))
        if (startDistance < shortestDistance) {
          selectedIndex = index
          selectedReversed = false
          shortestDistance = startDistance
        }
        if (endDistance < shortestDistance) {
          selectedIndex = index
          selectedReversed = true
          shortestDistance = endDistance
        }
      })
    } else if (startCoordinate) {
      const start = normalizeRouteCoordinate(startCoordinate)
      if (start) {
        remaining.forEach((candidate, index) => {
          const startDistance = distanceBetweenCoordinates(start, candidate.points[0])
          const endDistance = distanceBetweenCoordinates(start, candidate.points.at(-1))
          if (startDistance < shortestDistance) {
            selectedIndex = index
            selectedReversed = false
            shortestDistance = startDistance
          }
          if (endDistance < shortestDistance) {
            selectedIndex = index
            selectedReversed = true
            shortestDistance = endDistance
          }
        })
      }
    }

    const [candidate] = remaining.splice(selectedIndex, 1)
    const leg = selectedReversed ? [...candidate.points].reverse() : candidate.points
    const firstPoint = leg[0]
    if (current) {
      const connectorDistance = distanceBetweenCoordinates(current, firstPoint)
      if (connectorDistance >= 0.1) {
        distanceMeters += connectorDistance
        points.push({ ...firstPoint, distanceMeters })
      }
    } else {
      points.push({ ...firstPoint, distanceMeters })
    }
    current = firstPoint

    leg.slice(1).forEach((point) => {
      const segmentDistance = distanceBetweenCoordinates(current, point)
      if (segmentDistance < 0.1) {
        return
      }
      distanceMeters += segmentDistance
      points.push({ ...point, distanceMeters })
      current = point
    })
    current = points.at(-1) ?? current
  }

  let path = {
    points,
    totalDistanceMeters: distanceMeters,
    totalDistanceKm: distanceMeters / 1000,
    isPlayable: points.length >= 2 && distanceMeters > 0,
  }

  // Start the playback at the first photo's location on the route, not at a
  // distant route endpoint, so the opening photo and moving camera agree.
  const startProjection = projectCoordinateToTravelPath(path, startCoordinate)
  if (startProjection && startProjection.distanceMeters > 0.5
    && path.totalDistanceMeters - startProjection.distanceMeters > 0.5) {
    const trimmedPoints = [{
      latitude: startProjection.latitude,
      longitude: startProjection.longitude,
      distanceMeters: 0,
    }]
    path.points.forEach((point) => {
      if (point.distanceMeters > startProjection.distanceMeters + 0.05) {
        trimmedPoints.push({ ...point, distanceMeters: point.distanceMeters - startProjection.distanceMeters })
      }
    })
    distanceMeters = path.totalDistanceMeters - startProjection.distanceMeters
    path = {
      points: trimmedPoints,
      totalDistanceMeters: distanceMeters,
      totalDistanceKm: distanceMeters / 1000,
      isPlayable: trimmedPoints.length >= 2 && distanceMeters > 0,
    }
  }

  return path
}

export function buildTravelJourneyRoutePhotoDistances(path, photos = [], maximumMatchDistanceMeters = 2500) {
  const photoCount = Array.isArray(photos) ? photos.length : 0
  if (!photoCount || !path?.isPlayable || !(path.totalDistanceMeters > 0)) {
    return []
  }

  const matchedDistances = []
  let lastMatchedDistance = 0
  photos.forEach((photo) => {
    const coordinate = {
      latitude: photo?.latitude ?? photo?.gpsLatitude ?? photo?.lat,
      longitude: photo?.longitude ?? photo?.gpsLongitude ?? photo?.lng ?? photo?.lon,
    }
    const projection = projectCoordinateToTravelPath(path, coordinate, lastMatchedDistance)
    if (projection && projection.distanceFromRouteMeters <= maximumMatchDistanceMeters) {
      lastMatchedDistance = Math.max(lastMatchedDistance, projection.distanceMeters)
      matchedDistances.push(lastMatchedDistance)
    } else {
      matchedDistances.push(null)
    }
  })

  if (!matchedDistances.some((distance) => distance != null)) {
    if (photoCount === 1) {
      return [0]
    }
    return matchedDistances.map((_, index) => path.totalDistanceMeters * index / (photoCount - 1))
  }

  matchedDistances.forEach((distance, index) => {
    if (distance != null) {
      return
    }
    let previousIndex = index - 1
    while (previousIndex >= 0 && matchedDistances[previousIndex] == null) previousIndex -= 1
    let nextIndex = index + 1
    while (nextIndex < photoCount && matchedDistances[nextIndex] == null) nextIndex += 1
    const leftIndex = previousIndex >= 0 ? previousIndex : -1
    const rightIndex = nextIndex < photoCount ? nextIndex : photoCount
    const leftDistance = previousIndex >= 0 ? matchedDistances[previousIndex] : 0
    const rightDistance = nextIndex < photoCount ? matchedDistances[nextIndex] : path.totalDistanceMeters
    const progress = (index - leftIndex) / (rightIndex - leftIndex)
    matchedDistances[index] = leftDistance + ((rightDistance - leftDistance) * progress)
  })

  return matchedDistances.map((distance, index) => Math.max(
    index > 0 ? matchedDistances[index - 1] : 0,
    Math.min(path.totalDistanceMeters, Number(distance) || 0),
  ))
}

export function getTravelJourneyFocusZoom({ latitude, longitude, locations = [], project }) {
  const baseZoom = 15
  if (latitude == null || longitude == null || !Number.isFinite(Number(latitude))
    || !Number.isFinite(Number(longitude)) || typeof project !== 'function') return baseZoom

  const origin = project([Number(latitude), Number(longitude)], baseZoom)
  const nearbyLocations = new Set()
  locations.forEach((location) => {
    if (location?.latitude == null || location?.longitude == null) return
    const lat = Number(location.latitude)
    const lng = Number(location.longitude)
    if (!Number.isFinite(lat) || !Number.isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180) return
    const point = project([lat, lng], baseZoom)
    if (Math.hypot(point.x - origin.x, point.y - origin.y) <= 96) {
      nearbyLocations.add(`${lat.toFixed(6)}:${lng.toFixed(6)}`)
    }
  })

  // Grouped photos don't need street-level zoom to separate every image.
  // Keep the surrounding area visible; manual zoom can still go up to 20.
  if (nearbyLocations.size <= 1) return baseZoom
  return Math.min(16, baseZoom + Math.ceil(Math.log2(nearbyLocations.size)) * 0.5)
}

export function getTravelJourneyViewportOverviewZoom({ current, next, currentZoom, minZoom, width, height, project }) {
  const horizontalLimit = Math.max(1, width / 2 - 80)
  const verticalLimit = Math.max(1, height / 2 - 80)
  let zoom = currentZoom
  while (zoom > minZoom) {
    const from = project(current, zoom)
    const to = project(next, zoom)
    if (Math.abs(to.x - from.x) <= horizontalLimit && Math.abs(to.y - from.y) <= verticalLimit) break
    zoom = Math.max(minZoom, zoom - 0.5)
  }
  return zoom
}

export function getTravelJourneyRouteDistanceAtElapsed(path, photoDistances = [], elapsedMs = 0, intervalMs = 4000) {
  const totalDistance = Number(path?.totalDistanceMeters)
  const distances = Array.isArray(photoDistances) ? photoDistances : []
  if (!path?.isPlayable || !(totalDistance > 0) || !distances.length) {
    return 0
  }

  const interval = Math.max(1, Number(intervalMs) || 1)
  const elapsed = Math.max(0, Math.min(Number(elapsedMs) || 0, distances.length * interval))
  const segmentIndex = Math.min(distances.length - 1, Math.floor(elapsed / interval))
  const segmentProgress = Math.max(0, Math.min(1, (elapsed - (segmentIndex * interval)) / interval))
  const startDistance = Math.max(0, Math.min(totalDistance, Number(distances[segmentIndex]) || 0))
  const endDistance = segmentIndex + 1 < distances.length
    ? Math.max(startDistance, Math.min(totalDistance, Number(distances[segmentIndex + 1]) || 0))
    : totalDistance

  return startDistance + ((endDistance - startDistance) * segmentProgress)
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
