import { onBeforeUnmount, ref } from 'vue'

// Reactive matchMedia result. Always false when there is no window.
export function useMediaQuery(query) {
  const matches = ref(false)
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return matches
  const list = window.matchMedia(query)
  matches.value = list.matches
  const update = event => { matches.value = event.matches }
  if (list.addEventListener) list.addEventListener('change', update)
  else list.addListener(update)
  onBeforeUnmount(() => {
    if (list.removeEventListener) list.removeEventListener('change', update)
    else list.removeListener(update)
  })
  return matches
}
