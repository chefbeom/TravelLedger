import { test } from 'node:test'
import assert from 'node:assert/strict'
import { buildFirstPhotoIndex, buildLocationMarkers, countActiveFilters, formatPlaceMeta, hubHashForMode, hubModeForRoute, mergePlacesById, placeIdFromMapItem, sortDiaryVisits } from '../../src/lib/recordMapView.js'

test('mergePlacesById keeps first-seen order and lets list entries override map entries', () => {
  const merged = mergePlacesById([{ id: 1, name: 'map-1' }, { id: 2, name: 'map-2' }], [{ id: 3, name: 'list-3' }, { id: 1, name: 'list-1' }])
  assert.deepEqual(merged.map(p => p.name), ['list-1', 'map-2', 'list-3'])
  assert.deepEqual(mergePlacesById(), [])
})

test('buildFirstPhotoIndex matches the original per-place find over [...list, ...map]', () => {
  const list = [
    { id: 'v1', placeId: 1, photos: [] },
    { id: 'v2', placeId: 1, photos: [{ photoUrl: '/list-1a' }, { photoUrl: '/list-1b' }] },
    { id: 'v3', placeId: 2 },
    { id: 'v4', placeId: 4, photos: [{ photoUrl: '/list-4' }] },
  ]
  const map = [
    { id: 'm1', placeId: 1, photos: [{ photoUrl: '/map-1' }] },
    { id: 'm2', placeId: 2, photos: [{ photoUrl: '/map-2' }] },
    { id: 'm3', placeId: 3, photos: [] },
  ]
  const index = buildFirstPhotoIndex(list, map)
  for (const id of [1, 2, 3, 4, 5]) {
    const original = [...list, ...map].find(v => v.placeId === id && v.photos?.length)?.photos[0]
    assert.equal(index.get(id), original?.photoUrl, `place ${id}`)
  }
  assert.equal(index.get(1), '/list-1a')
  assert.equal(index.get(2), '/map-2')
  assert.equal(index.has(3), false)
})

test('buildFirstPhotoIndex handles empty input', () => {
  assert.equal(buildFirstPhotoIndex().size, 0)
  assert.equal(buildFirstPhotoIndex([], []).size, 0)
})

test('buildLocationMarkers numbers candidates from 1, marks the focused one and keeps source fields', () => {
  const rows = [{ id: 'location:1', name: 'A', latitude: 1, longitude: 2 }, { id: 'location:2', name: 'B', latitude: 3, longitude: 4 }]
  const markers = buildLocationMarkers(rows, 'location:2')
  assert.deepEqual(markers.map(m => m.searchResultNumber), [1, 2])
  assert.deepEqual(markers.map(m => m.searchResultFocused), [false, true])
  assert.equal(markers[0].planColorHex, '#357CCB')
  assert.equal(markers[0].planName, '검색 후보 · 저장 전')
  assert.equal(markers[0].title, 'A')
  assert.equal(markers[0].placeName, 'A')
  assert.equal(markers[0].recordMapLocation, true)
  assert.equal(markers[1].latitude, 3)
  assert.equal(rows[0].recordMap, undefined)
})

test('buildLocationMarkers never focuses anything when focusedId is null, even for id-less candidates', () => {
  const markers = buildLocationMarkers([{ name: 'No id' }], null)
  assert.equal(markers[0].searchResultFocused, false)
  assert.deepEqual(buildLocationMarkers(), [])
})

test('sortDiaryVisits drops undated visits and sorts newest first with time as tiebreaker', () => {
  const sorted = sortDiaryVisits([
    { id: 'a', date: '2026-10-01', time: '09:00:00' },
    { id: 'b', date: '2026-10-02' },
    { id: 'c', date: '2026-10-01', time: '18:00:00' },
    { id: 'd', date: null },
    { id: 'e', date: '2026-10-01' },
  ])
  assert.deepEqual(sorted.map(v => v.id), ['b', 'c', 'a', 'e'])
})

test('placeIdFromMapItem resolves ids from every supported shape', () => {
  assert.equal(placeIdFromMapItem({ recordMapPlaceId: 7, placeId: 8, id: 'place:9' }), 7)
  assert.equal(placeIdFromMapItem({ placeId: 8, id: 'place:9' }), 8)
  assert.equal(placeIdFromMapItem({ id: 'place:12' }), 12)
  assert.equal(placeIdFromMapItem({ clusterId: 'place:13' }), 13)
  assert.equal(placeIdFromMapItem({ id: '', clusterId: 'cluster:place:14' }), 14)
})

test('placeIdFromMapItem keeps the original semantics for unusable values', () => {
  // Non-numeric ids give NaN; a missing id gives 0 (Number('')), exactly like the old inline expression.
  assert.ok(Number.isNaN(placeIdFromMapItem({ id: 'place:abc' })))
  assert.equal(placeIdFromMapItem({}), 0)
  assert.equal(placeIdFromMapItem(null), 0)
})

test('formatPlaceMeta joins available parts without a leading separator', () => {
  assert.equal(formatPlaceMeta({ country: '대한민국', region: '서울', visited: true }), '대한민국 · 서울 · 방문한 곳')
  assert.equal(formatPlaceMeta({ country: '대한민국', visited: false }), '대한민국 · 가고 싶은 곳')
  assert.equal(formatPlaceMeta({ region: '제주', visited: true }), '제주 · 방문한 곳')
  assert.equal(formatPlaceMeta({ visited: true }), '방문한 곳')
  assert.equal(formatPlaceMeta({ country: '', region: '', visited: false }), '가고 싶은 곳')
  assert.equal(formatPlaceMeta(), '가고 싶은 곳')
})

test('countActiveFilters counts only collection and date filters', () => {
  assert.equal(countActiveFilters({ collectionId: '', state: 'VISITED', from: '', to: '', q: 'x' }), 0)
  assert.equal(countActiveFilters({ collectionId: '10', from: '2026-01-01', to: '' }), 2)
  assert.equal(countActiveFilters({ collectionId: '10', from: '2026-01-01', to: '2026-02-01' }), 3)
  assert.equal(countActiveFilters(), 0)
})

test('hubModeForRoute maps only the plain travel route to the records mode', () => {
  assert.equal(hubModeForRoute('travel'), 'records')
  for (const route of ['my-map', 'travel-money', 'photo-album', 'travel-log', 'public-trips']) assert.equal(hubModeForRoute(route), 'travel', route)
})

test('hubHashForMode gives the deep-link hash for each mode', () => {
  assert.equal(hubHashForMode('records'), 'travel')
  assert.equal(hubHashForMode('travel'), 'my-map')
  assert.equal(hubHashForMode('shares'), '')
})
