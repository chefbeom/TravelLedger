import { test } from 'node:test'
import assert from 'node:assert/strict'
import { locationSuggestions, normalizeLocationResults, relatedLocationQueries } from '../../src/lib/recordMapLocationSearch.js'

const station = { name: '우장산역', displayName: '서울 강서구 우장산역', latitude: 37.548, longitude: 126.836 }
const exit = { name: '우장산역 2번 출구', latitude: 37.5481, longitude: 126.8361 }

test('search pins use validated unique coordinates, stable numbered ids, and a ten-result cap', () => {
  const rows = normalizeLocationResults([station, exit, { ...exit }, { ...station, name: 'bad', latitude: null }, { ...station, name: 'bad', longitude: 181 }, { ...station, name: '' }])
  assert.deepEqual(rows.map(row => row.id), ['location:0', 'location:1'])
  assert.equal(rows[1].latitude, exit.latitude)
  assert.notEqual(rows[1].latitude, rows[0].latitude)
  assert.equal(normalizeLocationResults(Array.from({ length: 15 }, (_, i) => ({ ...station, name: 'Place ' + i }))).length, 10)
  assert.deepEqual(normalizeLocationResults({}), [])
})

test('input suggestions come from fetched candidates and tolerate spaces without inventing exits', () => {
  assert.deepEqual(locationSuggestions('우장산역2', [station, exit, { ...exit }]).map(row => row.name), [exit.name])
  assert.deepEqual(locationSuggestions('우장산역3', [station, exit]), [])
  assert.deepEqual(locationSuggestions('우', [station, exit]), [])
  assert.deepEqual(relatedLocationQueries('우장산역'), ['우장산역 출구'])
  assert.deepEqual(relatedLocationQueries('우장산역 2번출구'), [])
})
