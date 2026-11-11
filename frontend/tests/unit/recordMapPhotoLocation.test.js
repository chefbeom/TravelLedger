import test from 'node:test'
import assert from 'node:assert/strict'
import { hasDistantPhotoLocations } from '../../src/lib/recordMapPhotoLocation.js'

test('distant photo locations require confirmation without inventing missing coordinates', () => {
  assert.equal(hasDistantPhotoLocations([{ latitude: 37.55, longitude: 126.97 }, null]), false)
  assert.equal(hasDistantPhotoLocations([{ latitude: 37.55, longitude: 126.97 }, { latitude: 37.5501, longitude: 126.9701 }]), false)
  assert.equal(hasDistantPhotoLocations([{ latitude: 37.55, longitude: 126.97 }, { latitude: 35.6812, longitude: 139.7671 }]), true)
  assert.equal(hasDistantPhotoLocations([{ latitude: 0, longitude: 0 }, { latitude: 0.002, longitude: 0 }, { latitude: -0.002, longitude: 0 }]), true)
  assert.equal(hasDistantPhotoLocations([{ latitude: 100, longitude: 0 }, { latitude: 37, longitude: 127 }]), false)
})
