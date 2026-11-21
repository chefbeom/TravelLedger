// One Escape handler for every dialog and popover of the integrated calendar.
//
// The application registers its own capture-phase Escape handler that clicks a
// "close" button of whichever aria-modal dialog it finds last, which is wrong for
// stacked overlays (an alertdialog above a sheet, a menu inside a dialog). The
// calendar therefore keeps a stack of open layers and closes only the newest one.
const stack = []

function onKeydown(event) {
  if (event.key !== 'Escape' || !stack.length) return
  event.preventDefault()
  event.stopImmediatePropagation()
  stack[stack.length - 1].close()
}

export function pushEscape(close) {
  if (!stack.length) window.addEventListener('keydown', onKeydown, true)
  const entry = { close }
  stack.push(entry)
  return () => {
    const index = stack.indexOf(entry)
    if (index >= 0) stack.splice(index, 1)
    if (!stack.length) window.removeEventListener('keydown', onKeydown, true)
  }
}
