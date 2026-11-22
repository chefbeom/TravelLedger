import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  buildOrientationExifSegment,
  drawOrientedImage,
  insertExifSegment,
  resolveOrientationToApply,
  resolveOrientedSize,
} from '../../src/lib/imageOrientation.js'

test('buildOrientationExifSegment lays out an APP1 Orientation segment', () => {
  const segment = buildOrientationExifSegment(6)
  assert.equal(segment.length, 36)
  assert.equal(segment[0], 0xff)
  assert.equal(segment[1], 0xe1)
  assert.equal((segment[2] << 8) | segment[3], 34)
  assert.deepEqual(Array.from(segment.subarray(4, 10)), [0x45, 0x78, 0x69, 0x66, 0, 0])
  assert.deepEqual(Array.from(segment.subarray(10, 12)), [0x49, 0x49])
  const view = new DataView(segment.buffer)
  assert.equal(view.getUint16(20, true), 0x0112)
  assert.equal(view.getUint16(28, true), 6)
})

test('insertExifSegment rejects non-JPEG and splices after SOI', () => {
  const segment = buildOrientationExifSegment(6)
  assert.equal(insertExifSegment(new Uint8Array([1, 2, 3]), segment), null)
  const jpeg = new Uint8Array([0xff, 0xd8, 0xff, 0xdb, 0x00])
  const result = insertExifSegment(jpeg, segment)
  assert.equal(result.length, jpeg.length + segment.length)
  assert.deepEqual(Array.from(result.subarray(0, 2)), [0xff, 0xd8])
  assert.deepEqual(Array.from(result.subarray(2, 2 + segment.length)), Array.from(segment))
  assert.deepEqual(Array.from(result.subarray(2 + segment.length)), [0xff, 0xdb, 0x00])
})

test('resolveOrientationToApply never applies orientation twice', () => {
  assert.equal(resolveOrientationToApply(6, true), 1)
  assert.equal(resolveOrientationToApply(6, false), 6)
  assert.equal(resolveOrientationToApply(0, false), 1)
  assert.equal(resolveOrientationToApply(9, false), 1)
  assert.equal(resolveOrientationToApply('x', false), 1)
})

test('resolveOrientedSize swaps for orientations 5-8 only', () => {
  for (let o = 1; o <= 8; o += 1) {
    const size = resolveOrientedSize(200, 100, o)
    if (o >= 5) assert.deepEqual(size, { width: 100, height: 200 })
    else assert.deepEqual(size, { width: 200, height: 100 })
  }
})

function createFakeContext() {
  let matrix = [1, 0, 0, 1, 0, 0]
  const stack = []
  const multiply = (m) => {
    const [a, b, c, d, e, f] = matrix
    const [a2, b2, c2, d2, e2, f2] = m
    matrix = [
      a * a2 + c * b2,
      b * a2 + d * b2,
      a * c2 + c * d2,
      b * c2 + d * d2,
      a * e2 + c * f2 + e,
      b * e2 + d * f2 + f,
    ]
  }
  const apply = (x, y) => [matrix[0] * x + matrix[2] * y + matrix[4], matrix[1] * x + matrix[3] * y + matrix[5]]
  const context = {
    drawn: null,
    save: () => stack.push(matrix.slice()),
    restore: () => { matrix = stack.pop() },
    translate: (x, y) => multiply([1, 0, 0, 1, x, y]),
    scale: (x, y) => multiply([x, 0, 0, y, 0, 0]),
    rotate: (t) => multiply([Math.cos(t), Math.sin(t), -Math.sin(t), Math.cos(t), 0, 0]),
    transform: (...m) => multiply(m),
    drawImage: (image, x, y, w, h) => {
      context.drawn = { topLeft: apply(x, y), topRight: apply(x + w, y) }
    },
  }
  return context
}

test('drawOrientedImage maps raw corners to the expected display corners', () => {
  const W = 200
  const H = 100
  const expected = {
    1: [[0, 0], [W, 0]],
    2: [[W, 0], [0, 0]],
    3: [[W, H], [0, H]],
    4: [[0, H], [W, H]],
    5: [[0, 0], [0, H]],
    6: [[W, 0], [W, H]],
    7: [[W, H], [W, 0]],
    8: [[0, H], [0, 0]],
  }
  for (let o = 1; o <= 8; o += 1) {
    const context = createFakeContext()
    drawOrientedImage(context, {}, o, W, H)
    const [tl, tr] = expected[o]
    for (const [actual, want] of [[context.drawn.topLeft, tl], [context.drawn.topRight, tr]]) {
      assert.ok(Math.abs(actual[0] - want[0]) < 1e-9 && Math.abs(actual[1] - want[1]) < 1e-9, `orientation ${o}: ${actual} vs ${want}`)
    }
  }
})
