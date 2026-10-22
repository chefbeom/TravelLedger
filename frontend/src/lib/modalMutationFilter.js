// A table edit, map marker animation or pet frame is not a modal-stack change.
// Check only changed nodes rather than scanning the whole document every frame.
export function createModalMutationFilter(selector) {
  const modalClasses = new Set([...selector.matchAll(/\.([\w-]+)/g)].map(match => match[1]))
  const containsModal = node => node.nodeType === 1 && (node.matches(selector) || Boolean(node.querySelector(selector)))
  return records => records.some(record => {
    if (record.type === 'childList') {
      return [...record.addedNodes, ...record.removedNodes].some(containsModal)
    }
    if (record.type !== 'attributes' || record.target.nodeType !== 1) return false
    if (record.target.matches(selector)) return true
    if (record.attributeName === 'class') {
      return String(record.oldValue || '').split(/\s+/).some(name => modalClasses.has(name))
    }
    return (record.attributeName === 'role' && record.oldValue === 'dialog')
      || (record.attributeName === 'aria-modal' && record.oldValue === 'true')
  })
}
