import test from 'node:test'
import assert from 'node:assert/strict'
import { normalizeCompactLayouts } from '../../src/lib/mainPaletteResponsive.js'

test('compact palette layouts retain only bounded unique entries without modifying input', () => {
  const input = { 1: [{ id: 'a', x: 0, y: 2, w: 1, h: 3 }, { id: 'a', x: 0, y: 0, w: 1, h: 2 }],
    3: [{ id: 'b', x: 2, y: 0, w: 2, h: 2 }, { id: 'c', x: 1, y: 2, w: 2, h: 2 }], 9: [{ id: 'x' }] }
  const original = structuredClone(input)
  assert.deepEqual(normalizeCompactLayouts(input), { 1: [{ id: 'a', x: 0, y: 2, w: 1, h: 3 }], 3: [{ id: 'c', x: 1, y: 2, w: 2, h: 2 }] })
  assert.deepEqual(input, original)
})
