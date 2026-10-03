import { test } from 'node:test'
import assert from 'node:assert/strict'
import { buildTravelMapPhotoGroups } from '../../src/lib/travelMapPhotoGroups.js'

const cluster = { id: 10, latitude: 35, longitude: 139, photoCount: 40, memoryCount: 8, representativePhotoUrl: '/first.png' }
const pins = [
  { mediaId: 2, clusterId: 10, recordId: 5, latitude: 35.0001, longitude: 139, memoryDate: '2026-10-02', photoUrl: '/second.png' },
  { mediaId: 1, clusterId: 10, recordId: 5, latitude: 35, longitude: 139, memoryDate: '2026-10-01', photoUrl: '/first.png' },
]

test('server photo groups retain their count and members for either display mode', () => {
  const groups = buildTravelMapPhotoGroups([cluster], pins)
  assert.equal(groups.length, 1)
  assert.equal(groups[0].photoCount, 40)
  assert.equal(groups[0].key, 'cluster-10')
  assert.equal(groups[0].representative.representativePhotoUrl, '/first.png')
  assert.deepEqual(groups[0].photos.map((photo) => photo.mediaId), [1, 2])
  assert.deepEqual(pins.map((photo) => photo.mediaId), [2, 1], 'inputs are not sorted in place')
})

test('day-filtered pins stay grouped and count only that day, not the full server cluster', () => {
  const groups = buildTravelMapPhotoGroups([], pins)
  assert.equal(groups.length, 1)
  assert.equal(groups[0].photoCount, 2)
  assert.equal(groups[0].memoryCount, 1)
  assert.equal(groups[0].representative.mediaId, 1)
  assert.equal(buildTravelMapPhotoGroups([], [pins[0]])[0].photoCount, 1)
})

test('invalid coordinates and repeated media are excluded without losing unclustered photos', () => {
  const groups = buildTravelMapPhotoGroups([cluster, cluster], [
    ...pins, pins[0],
    { mediaId: 3, latitude: 36, longitude: 140 },
    { mediaId: 4, latitude: 36, longitude: 140 },
    { mediaId: 5, latitude: null, longitude: 140 },
    { mediaId: 6, latitude: 100, longitude: 140 },
    { mediaId: 7, latitude: 36, longitude: 'invalid' },
  ])
  assert.equal(groups.length, 2)
  assert.equal(groups[0].photos.length, 2)
  assert.equal(groups[1].photoCount, 2)
})
