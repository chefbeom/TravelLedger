import test from 'node:test'
import assert from 'node:assert/strict'
import { buildTravelJourneyDays, matchesTravelJourneyDay, sortTravelJourneyPhotos } from './travelJourney.js'

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

test('photo playback order uses recorded date and time with stable media id tie-break', () => {
  const sorted = sortTravelJourneyPhotos([
    { mediaId: 9, memoryDate: '2026-09-01', memoryTime: '12:00' },
    { mediaId: 3, memoryDate: '2026-09-01', memoryTime: '08:00' },
    { mediaId: 2, memoryDate: '2026-09-01', memoryTime: '08:00' },
  ])

  assert.deepEqual(sorted.map((photo) => photo.mediaId), [2, 3, 9])
})
