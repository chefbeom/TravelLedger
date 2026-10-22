import test from 'node:test'
import assert from 'node:assert/strict'
import { createModalMutationFilter } from '../../src/lib/modalMutationFilter.js'

const relevant = createModalMutationFilter('.receipt-ocr-modal, [role="dialog"][aria-modal="true"], .travel-map--fullscreen')
function element(matches = false, nested = false) {
  return { nodeType: 1, matches: () => matches, querySelector: () => nested ? {} : null }
}
function attribute(target, attributeName, oldValue = '') {
  return { type: 'attributes', target, attributeName, oldValue }
}

test('table, marker and pet class changes do not request a document-wide modal scan', () => {
  const node = element()
  node.querySelector = () => { throw new Error('Do not scan unrelated class changes') }
  assert.equal(relevant(Array.from({ length: 1000 }, () => attribute(node, 'class', 'calendar__day'))), false)
  assert.equal(relevant([{ type: 'childList', addedNodes: [{ nodeType: 3 }], removedNodes: [] }]), false)
})

test('opening, removing, wrapping and changing fullscreen/dialog states still synchronize', () => {
  assert.equal(relevant([{ type: 'childList', addedNodes: [element(false, true)], removedNodes: [] }]), true)
  assert.equal(relevant([{ type: 'childList', addedNodes: [], removedNodes: [element(true)] }]), true)
  assert.equal(relevant([attribute(element(true), 'class')]), true)
  assert.equal(relevant([attribute(element(), 'class', 'travel-map travel-map--fullscreen')]), true)
  assert.equal(relevant([attribute(element(), 'class', 'receipt-ocr-modal')]), true)
  assert.equal(relevant([attribute(element(), 'role', 'dialog')]), true)
  assert.equal(relevant([attribute(element(), 'aria-modal', 'true')]), true)
})
