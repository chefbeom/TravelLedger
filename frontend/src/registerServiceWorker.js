export function registerServiceWorker() {
  if (!('serviceWorker' in navigator) || !import.meta.env.PROD) {
    return
  }

  navigator.serviceWorker.register('/sw.js').catch((error) => {
    console.warn('TravelLedger service worker registration failed.', error)
  })
}
