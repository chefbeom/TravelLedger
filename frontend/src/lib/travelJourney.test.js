import test from 'node:test'
import assert from 'node:assert/strict'
import {
  buildTravelJourneyDays,
  buildTravelJourneyRoutePhotoDistances,
  buildTravelRoutePlaybackPath,
  findNextTravelJourneyDay,
  getTravelJourneyRouteDistanceAtElapsed,
  getTravelJourneyViewportOverviewZoom,
  getTravelRoutePosition,
  matchesTravelJourneyDay,
  sortTravelJourneyPhotos,
} from './travelJourney.js'

test('journey days are numbered chronologically per travel plan', () => {
  const days = buildTravelJourneyDays([
    { mediaId: 3, planId: 10, planName: '도쿄', memoryDate: '2026-09-03', photoUrl: '/3.jpg' },
    { mediaId: 2, planId: 10, planName: '도쿄', memoryDate: '2026-09-01', photoUrl: '/2.jpg' },
    { mediaId: 1, planId: 20, planName: '부산', memoryDate: '2026-09-02', photoUrl: '/1.jpg' },
    { mediaId: 4, planId: 10, planName: '도쿄', memoryDate: '2026-09-03', photoUrl: '/4.jpg' },
    { mediaId: 4, planId: 10, planName: '도쿄', memoryDate: '2026-09-03', photoUrl: '/4-duplicate.jpg' },
    { mediaId: 5, planId: 10, planName: '도쿄', memoryDate: null, photoUrl: '/5.jpg' },
  ], [
    { planId: 10, planName: '도쿄', routeDate: '2026-09-02' },
  ])

  assert.deepEqual(days.map(({ planName, date, dayNumber, photoCount }) => ({ planName, date, dayNumber, photoCount })), [
    { planName: '도쿄', date: '2026-09-01', dayNumber: 1, photoCount: 1 },
    { planName: '도쿄', date: '2026-09-02', dayNumber: 2, photoCount: 0 },
    { planName: '도쿄', date: '2026-09-03', dayNumber: 3, photoCount: 2 },
    { planName: '부산', date: '2026-09-02', dayNumber: 1, photoCount: 1 },
  ])
})

test('day matching scopes photos, markers, and routes to both plan and date', () => {
  const [day] = buildTravelJourneyDays([
    { mediaId: 1, planId: 7, planName: '여행', memoryDate: '2026-09-01', photoUrl: '/1.jpg' },
  ])

  assert.equal(matchesTravelJourneyDay({ planId: 7, memoryDate: '2026-09-01' }, day), true)
  assert.equal(matchesTravelJourneyDay({ planId: 7, routeDate: '2026-09-01' }, day), true)
  assert.equal(matchesTravelJourneyDay({ planId: 8, memoryDate: '2026-09-01' }, day), false)
  assert.equal(matchesTravelJourneyDay({ planId: 7, memoryDate: '2026-09-02' }, day), false)
})

test('next journey day stays in the same trip and skips days without photos', () => {
  const days = buildTravelJourneyDays([
    { mediaId: 1, planId: 7, planName: '여행', memoryDate: '2026-09-01', photoUrl: '/1.jpg' },
    { mediaId: 2, planId: 8, planName: '다른 여행', memoryDate: '2026-09-02', photoUrl: '/2.jpg' },
    { mediaId: 3, planId: 7, planName: '여행', memoryDate: '2026-09-03', photoUrl: '/3.jpg' },
  ], [
    { planId: 7, planName: '여행', routeDate: '2026-09-02' },
  ])
  const firstDay = days.find((day) => day.planId === 7 && day.date === '2026-09-01')

  assert.equal(findNextTravelJourneyDay(days, firstDay.key)?.date, '2026-09-03')
  assert.equal(findNextTravelJourneyDay(days, days.find((day) => day.planId === 8).key), null)
})

test('photo playback order uses recorded date and time with stable media id tie-break', () => {
  const sorted = sortTravelJourneyPhotos([
    { mediaId: 9, memoryDate: '2026-09-01', memoryTime: '12:00' },
    { mediaId: 3, memoryDate: '2026-09-01', memoryTime: '08:00' },
    { mediaId: 2, memoryDate: '2026-09-01', memoryTime: '08:00' },
  ])

  assert.deepEqual(sorted.map((photo) => photo.mediaId), [2, 3, 9])
})

test('route playback path filters invalid and duplicate GPS points and calculates distance', () => {
  const path = buildTravelRoutePlaybackPath({
    points: [
      { latitude: 0, longitude: 0 },
      { latitude: 0, longitude: 0 },
      { latitude: 0, longitude: 1 },
      { latitude: 100, longitude: 2 },
    ],
  })

  assert.equal(path.isPlayable, true)
  assert.equal(path.points.length, 2)
  assert.ok(path.totalDistanceKm > 111 && path.totalDistanceKm < 112)
})

test('route position follows distance and clamps to route endpoints', () => {
  const path = buildTravelRoutePlaybackPath({
    points: [
      { latitude: 0, longitude: 0 },
      { latitude: 0, longitude: 1 },
      { latitude: 1, longitude: 1 },
    ],
  })

  assert.deepEqual(getTravelRoutePosition(path, -1), { latitude: 0, longitude: 0 })
  assert.deepEqual(getTravelRoutePosition(path, path.totalDistanceMeters), { latitude: 1, longitude: 1 })
  const midpoint = getTravelRoutePosition(path, path.totalDistanceMeters / 2)
  assert.ok(Math.abs(midpoint.latitude) < 0.001)
  assert.ok(Math.abs(midpoint.longitude - 1) < 0.001)
  assert.equal(getTravelRoutePosition(buildTravelRoutePlaybackPath({ points: [] }), 0), null)
})

test('combined route itinerary deduplicates identical GPX and hand-drawn tracks', () => {
  const route = { points: [{ latitude: 0, longitude: 0 }, { latitude: 0, longitude: 1 }] }
  const combined = buildTravelRoutePlaybackPath([route, { points: [...route.points].reverse() }])
  const single = buildTravelRoutePlaybackPath(route)

  assert.equal(combined.isPlayable, true)
  assert.equal(combined.points.length, 2)
  assert.equal(combined.totalDistanceMeters, single.totalDistanceMeters)
})

test('combined route itinerary starts nearest to the first journey photo and connects route legs', () => {
  const combined = buildTravelRoutePlaybackPath([
    { points: [{ latitude: 0, longitude: 0 }, { latitude: 0, longitude: 1 }] },
    { points: [{ latitude: 0, longitude: 2 }, { latitude: 0, longitude: 1.1 }] },
  ], { latitude: 0, longitude: 2 })

  assert.equal(combined.isPlayable, true)
  assert.deepEqual(getTravelRoutePosition(combined, 0), { latitude: 0, longitude: 2 })
  assert.equal(combined.points.length, 4)
  assert.ok(combined.totalDistanceMeters > 220_000)
})

test('route playback starts at the first photo and reaches each photo location at its display change', () => {
  const photos = [
    { latitude: 0, longitude: 0.005 },
    { latitude: 0, longitude: 0.01 },
    { latitude: 0, longitude: 0.015 },
  ]
  const path = buildTravelRoutePlaybackPath({
    points: [{ latitude: 0, longitude: 0 }, { latitude: 0, longitude: 0.02 }],
  }, photos[0])
  const photoDistances = buildTravelJourneyRoutePhotoDistances(path, photos)

  assert.ok(Math.abs(getTravelRoutePosition(path, 0).longitude - photos[0].longitude) < 0.00001)
  assert.ok(Math.abs(photoDistances[1] - path.totalDistanceMeters / 3) < 2)
  assert.ok(Math.abs(getTravelJourneyRouteDistanceAtElapsed(path, photoDistances, 1000, 1000) - photoDistances[1]) < 0.01)
  assert.ok(Math.abs(getTravelJourneyRouteDistanceAtElapsed(path, photoDistances, 2000, 1000) - photoDistances[2]) < 0.01)
  assert.equal(getTravelJourneyRouteDistanceAtElapsed(path, photoDistances, 3000, 1000), path.totalDistanceMeters)
})

test('route photo anchors stay ordered and interpolate photos without usable route coordinates', () => {
  const path = buildTravelRoutePlaybackPath({
    points: [{ latitude: 0, longitude: 0 }, { latitude: 0, longitude: 0.02 }],
  })
  const distances = buildTravelJourneyRoutePhotoDistances(path, [
    { latitude: 0, longitude: 0.004 },
    { mediaId: 2 },
    { gpsLatitude: 0, gpsLongitude: 0.012 },
    { latitude: 1, longitude: 1 },
  ])

  assert.equal(distances.length, 4)
  assert.ok(distances.every((distance, index) => distance >= 0 && distance <= path.totalDistanceMeters
    && (index === 0 || distance >= distances[index - 1])))
  assert.ok(distances[1] > distances[0] && distances[1] < distances[2])
  assert.ok(distances[3] > distances[2])
})

test('journey overview zoom fits both pins in the visible viewport', () => {
  const project = (point, zoom) => ({ x: point.x * 2 ** zoom, y: point.y * 2 ** zoom })
  const options = {
    current: { x: 0, y: 0 }, next: { x: 1, y: 0.5 },
    currentZoom: 10, minZoom: 2, width: 800, height: 600, project,
  }
  assert.equal(getTravelJourneyViewportOverviewZoom(options), 8)
  assert.equal(getTravelJourneyViewportOverviewZoom({ ...options, next: { x: 0.1, y: 0.1 } }), 10)
  assert.equal(getTravelJourneyViewportOverviewZoom({ ...options, next: { x: 1000, y: 1000 } }), 2)
  assert.equal(getTravelJourneyViewportOverviewZoom({ ...options, width: 500 }), 7)
})
