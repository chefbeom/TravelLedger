import { test } from 'node:test'
import assert from 'node:assert/strict'
import { resolveLayoutMode, MOBILE_LAYOUT_QUERY } from '../../src/lib/responsiveLayout.js'
import { getTravelMapUsableRect } from '../../src/lib/travelMapViewport.js'

test('automatic mode follows the viewport while explicit preferences remain respected', () => {
  assert.equal(resolveLayoutMode(null, true), 'mobile')
  assert.equal(resolveLayoutMode(null, false), 'desktop')
  assert.equal(resolveLayoutMode('desktop', true), 'desktop')
  assert.equal(resolveLayoutMode('mobile', false), 'mobile')
  assert.match(MOBILE_LAYOUT_QUERY, /pointer: coarse/)
})
test('map fit ignores overlays outside the canvas and reserves a bottom preview', () => {
  assert.deepEqual(getTravelMapUsableRect(390, 640, [
    { left: 0, top: -150, right: 390, bottom: 0 },
    { left: 0, top: 450, right: 390, bottom: 640 },
  ]), { left: 0, top: 0, right: 390, bottom: 450 })
})
test('map fit reserves both desktop preview and floating controls', () => {
  assert.deepEqual(getTravelMapUsableRect(1440, 900, [
    { left: 0, top: 150, right: 330, bottom: 750 },
    { left: 1100, top: 200, right: 1440, bottom: 700 },
  ]), { left: 330, top: 0, right: 1100, bottom: 900 })
})
