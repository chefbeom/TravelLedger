import { test } from 'node:test'
import assert from 'node:assert/strict'
import { buildRecordMapData, buildRecordMapJourney } from '../../src/lib/recordMap.js'
import { buildTravelMapPhotoGroups } from '../../src/lib/travelMapPhotoGroups.js'

const overview = {
  collections: [{ id: 1, colorHex: '#009E8C' }],
  places: [
    { id: 10, name: 'Cafe', latitude: 37.5, longitude: 127, visited: true, collectionIds: [1] },
    { id: 20, name: 'Wanted place', latitude: 37.6, longitude: 127.1, visited: false, collectionIds: [] },
    { id: 30, name: 'Walk', latitude: 37.7, longitude: 127.2, visited: true, collectionIds: [] },
  ],
  visits: [
    { id: 'visit:2', placeId: 10, title: 'Second visit', date: '2026-10-13', time: '13:00:00', photos: [{ id: 'photo:2', name: 'two.jpg', photoUrl: '/two' }] },
    { id: 'travel:5', placeId: 10, title: 'Travel cafe', date: '2026-10-12', time: '12:00:00', travelPlanId: 9, travelPlanName: 'Original', photos: [{ id: 'travel-photo:1', photoUrl: '/travel-one' }] },
    { id: 'visit:1', placeId: 30, title: 'No-photo walk', date: '2026-10-13', time: '09:00:00', photos: [] },
  ],
}

test('places form stable thumbnail groups in both modes without losing bookmarks', () => {
  const data = buildRecordMapData(overview)
  assert.equal(data.photoClusters.length, 1)
  assert.equal(data.photoClusters[0].photoCount, 2)
  assert.equal(data.photoClusters[0].memoryCount, 2)
  assert.deepEqual(data.markers.map(p => p.id), ['place:20', 'place:30'])
  assert.deepEqual(data.markers.map(p => p.memoryCount), [0, 1], 'date-free bookmarks are not counted as visits')
  const groups = buildTravelMapPhotoGroups(data.photoClusters, data.photoPins)
  assert.equal(groups.length, 1)
  assert.equal(groups[0].photoCount, 2)
  assert.deepEqual(groups[0].photos.map(p => p.mediaId), ['travel-photo:1', 'photo:2'])
  assert.equal(groups[0].cluster.recordMapPlaceId, 10)
  assert.equal(data.photoPins[0].planColorHex, '#009E8C')
})

test('playback includes only dated visits in chronological order, including no-photo visits', () => {
  const steps = buildRecordMapJourney(overview)
  assert.deepEqual(steps.map(p => p.visitId), ['travel:5', 'visit:1', 'visit:2'])
  assert.equal(steps.some(p => p.placeId === 20), false, 'a date-free bookmark must never replay')
  assert.equal(steps[1].mediaId, null)
  assert.equal(steps[1].markerId, 'place:30')
  assert.deepEqual(buildRecordMapJourney(overview, '2026-10-13').map(p => p.visitId), ['visit:1', 'visit:2'])
  assert.equal(buildRecordMapJourney(overview, '2026-10-14').length, 0)
})

test('photos use distinct namespaces and shared links remain inside the supplied snapshot', () => {
  const shared = { ...overview, places: [overview.places[0]], visits: [
    { ...overview.visits[0], photos: [{ id: 'photo:1', photoUrl: '/api/record-map/public/token/photos/1/content' }, { id: 'travel-photo:1', photoUrl: '/api/record-map/public/token/travel-photos/1/content' }] },
    { id: 'visit:99', placeId: 999, date: '2026-10-13', photos: [] },
  ] }
  assert.deepEqual(buildRecordMapData(shared).photoPins.map(p => p.mediaId), ['photo:1', 'travel-photo:1'])
  assert.equal(buildRecordMapData(shared).photoPins.every(p => p.photoUrl.includes('/public/token/')), true)
  assert.equal(buildRecordMapJourney(shared).length, 2)
})

test('building map and playback data does not mutate caller-owned records', () => {
  const before = structuredClone(overview)
  buildRecordMapData(overview)
  buildRecordMapJourney(overview)
  assert.deepEqual(overview, before)
  assert.deepEqual(buildRecordMapData(), { markers: [], photoPins: [], photoClusters: [] })
  assert.deepEqual(buildRecordMapJourney(), [])
})
