import assert from 'node:assert/strict'
import test from 'node:test'
import { clampTravelMapPreviewSize } from '../../src/lib/travelMapPreviewSize.js'

test('floating preview grows within the map, leaving room for the map and controls', () => {
  assert.deepEqual(clampTravelMapPreviewSize({ width: 560, height: 640 }, { width: 1440, height: 900 }), { width: 560, height: 640 })
  assert.deepEqual(clampTravelMapPreviewSize({ width: 5000, height: 5000 }, { width: 1440, height: 900 }), { width: 844, height: 868 })
  assert.equal(clampTravelMapPreviewSize({ width: 5000, height: 300 }, { width: 3840, height: 2160 }).width, 960)
})

test('compact preview and very small maps never exceed their available bounds', () => {
  assert.deepEqual(clampTravelMapPreviewSize({ width: 600, height: 1000 }, { width: 390, height: 844, compact: true }), { width: 374, height: 828 })
  assert.deepEqual(clampTravelMapPreviewSize({ width: -100, height: -100 }, { width: 390, height: 844, compact: true }), { width: 240, height: 180 })
  const tiny = clampTravelMapPreviewSize({ width: 500, height: 500 }, { width: 120, height: 100 })
  assert.deepEqual(tiny, { width: 52, height: 68 })
})

test('invalid sizes are safe and clamping does not overwrite a preferred size', () => {
  const preferred = { width: 700, height: 800 }
  assert.deepEqual(clampTravelMapPreviewSize(preferred, { width: 390, height: 400, compact: true }), { width: 374, height: 384 })
  assert.deepEqual(preferred, { width: 700, height: 800 })
  assert.deepEqual(clampTravelMapPreviewSize(preferred, { width: 1440, height: 900 }), preferred)
  assert.deepEqual(clampTravelMapPreviewSize({ width: NaN, height: Infinity }, { width: 1440, height: 900 }), { width: 240, height: 220 })
})
