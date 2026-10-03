import test from 'node:test'
import assert from 'node:assert/strict'
import { getTravelJourneyFocusZoom } from '../../src/lib/travelJourney.js'

const project = ([lat, lng]) => ({ x: lng * 100000, y: lat * 100000 })
const target = { latitude: 35.68, longitude: 139.76, project }

test('journey auto zoom ignores repeated photos at one location and remote locations', () => {
  const locations = Array.from({ length: 100 }, () => ({ latitude: 35.68, longitude: 139.76 }))
  locations.push({ latitude: 36, longitude: 140 })
  assert.equal(getTravelJourneyFocusZoom({ ...target, locations }), 15)
})

test('journey auto zoom is gently increased for nearby groups and capped at 16', () => {
  const locations = Array.from({ length: 40 }, (_, i) => ({ latitude: 35.68 + i * 0.00001, longitude: 139.76 }))
  assert.equal(getTravelJourneyFocusZoom({ ...target, locations: locations.slice(0, 2) }), 15.5)
  assert.equal(getTravelJourneyFocusZoom({ ...target, locations }), 16)
})

test('journey auto zoom uses the base level for missing or invalid coordinates', () => {
  const locations = [{ latitude: null, longitude: 139.76 }, { latitude: 'bad', longitude: 139.76 }, { latitude: 91, longitude: 139.76 }]
  assert.equal(getTravelJourneyFocusZoom({ ...target, locations }), 15)
  assert.equal(getTravelJourneyFocusZoom({ ...target, project: null }), 15)
  assert.equal(getTravelJourneyFocusZoom({ ...target, latitude: null }), 15)
})
