import test from 'node:test'
import assert from 'node:assert/strict'
import { selectPhotoFrameDisplayItems } from '../../src/lib/photoFrameSelection.js'

const photos = [
  { id: 'a', title: '나', updatedAt: '2026-01-02' },
  { id: 'b', title: '가', updatedAt: '2026-03-01' },
  { id: 'c', title: '다', updatedAt: '2026-02-01' },
]

test('fixed mode returns the chosen id, or the first sorted item when missing', () => {
  assert.equal(selectPhotoFrameDisplayItems(photos, { mode: 'fixed', fixedPhotoId: 'c' }, '')[0].id, 'c')
  assert.equal(selectPhotoFrameDisplayItems(photos, { mode: 'fixed', fixedPhotoId: 'zzz' }, '')[0].id, 'b')
})

test('latest mode returns the newest updatedAt first', () => {
  assert.equal(selectPhotoFrameDisplayItems(photos, { mode: 'latest', sort: 'recent' }, '')[0].id, 'b')
})

test('name mode returns the first title in ko-KR order', () => {
  assert.equal(selectPhotoFrameDisplayItems(photos, { mode: 'name' }, '')[0].id, 'b')
  assert.equal(selectPhotoFrameDisplayItems(photos, { mode: 'latest', sort: 'name' }, '')[0].title, '가')
})

test('random mode is stable for the same seed and does not mutate the input', () => {
  const snapshot = photos.map((photo) => photo.id)
  const first = selectPhotoFrameDisplayItems(photos, { mode: 'random' }, 'p1:1000')
  const second = selectPhotoFrameDisplayItems(photos, { mode: 'random' }, 'p1:1000')
  assert.equal(first[0].id, second[0].id)
  assert.deepEqual(photos.map((photo) => photo.id), snapshot)
})

test('empty input selects nothing', () => {
  assert.deepEqual(selectPhotoFrameDisplayItems([], { mode: 'random' }, 's'), [])
})
