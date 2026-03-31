import exifr from 'exifr'

const REVERSE_GEOCODE_MIN_INTERVAL_MS = 1200
const REVERSE_GEOCODE_CACHE_LIMIT = 300
const RETRYABLE_REVERSE_GEOCODE_STATUS_CODES = new Set([403, 429, 500, 502, 503, 504])

const reverseGeocodeCache = new Map()
const reverseGeocodeInFlight = new Map()
let reverseGeocodeQueue = Promise.resolve()
let lastReverseGeocodeAt = 0

function toDateParts(value) {
  if (!(value instanceof Date) || Number.isNaN(value.getTime())) {
    return {}
  }

  const year = value.getFullYear()
  const month = `${value.getMonth() + 1}`.padStart(2, '0')
  const day = `${value.getDate()}`.padStart(2, '0')
  const hours = `${value.getHours()}`.padStart(2, '0')
  const minutes = `${value.getMinutes()}`.padStart(2, '0')

  return {
    date: `${year}-${month}-${day}`,
    time: `${hours}:${minutes}`,
  }
}

function normalizeCoordinate(value) {
  if (!Number.isFinite(value)) {
    return null
  }
  return Number(value.toFixed(7))
}

function sleep(ms) {
  if (!ms || ms <= 0) {
    return Promise.resolve()
  }

  return new Promise((resolve) => {
    window.setTimeout(resolve, ms)
  })
}

function buildReverseGeocodeCacheKey(latitude, longitude) {
  return `${Number(latitude).toFixed(5)},${Number(longitude).toFixed(5)}`
}

function rememberReverseGeocode(cacheKey, value) {
  reverseGeocodeCache.delete(cacheKey)
  reverseGeocodeCache.set(cacheKey, value)

  if (reverseGeocodeCache.size <= REVERSE_GEOCODE_CACHE_LIMIT) {
    return
  }

  const oldestKey = reverseGeocodeCache.keys().next().value
  if (oldestKey) {
    reverseGeocodeCache.delete(oldestKey)
  }
}

function getRetryDelayMs(response, attempt) {
  const retryAfter = Number(response.headers.get('retry-after') ?? '')
  if (Number.isFinite(retryAfter) && retryAfter > 0) {
    return retryAfter * 1000
  }

  return REVERSE_GEOCODE_MIN_INTERVAL_MS * (attempt + 1)
}

async function executeReverseGeocodeTask(task) {
  const previousTask = reverseGeocodeQueue.catch(() => undefined)
  const nextTask = previousTask.then(async () => {
    const waitMs = Math.max(0, REVERSE_GEOCODE_MIN_INTERVAL_MS - (Date.now() - lastReverseGeocodeAt))
    if (waitMs > 0) {
      await sleep(waitMs)
    }

    const result = await task()
    lastReverseGeocodeAt = Date.now()
    return result
  })

  reverseGeocodeQueue = nextTask.then(
    () => undefined,
    () => undefined,
  )

  return nextTask
}

async function fetchReverseGeocode(latitude, longitude) {
  const controller = typeof AbortController !== 'undefined' ? new AbortController() : null
  const timeoutId = controller ? window.setTimeout(() => controller.abort(), 6000) : null

  try {
    for (let attempt = 0; attempt < 3; attempt += 1) {
      const response = await fetch(
        `https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=${latitude}&lon=${longitude}&zoom=18&addressdetails=1`,
        {
          headers: {
            'Accept-Language': 'ko,en',
          },
          signal: controller?.signal,
        },
      )

      if (response.ok) {
        const payload = await response.json()
        const address = payload.address ?? {}

        return {
          country: address.country || '',
          region:
            address.city ||
            address.town ||
            address.village ||
            address.municipality ||
            address.county ||
            address.state_district ||
            address.state ||
            '',
          placeName:
            payload.name ||
            address.attraction ||
            address.amenity ||
            address.shop ||
            address.tourism ||
            address.leisure ||
            address.building ||
            address.road ||
            address.neighbourhood ||
            address.suburb ||
            '',
        }
      }

      if (
        attempt < 2 &&
        RETRYABLE_REVERSE_GEOCODE_STATUS_CODES.has(response.status)
      ) {
        await sleep(getRetryDelayMs(response, attempt))
        continue
      }

      return {}
    }

    return {}
  } catch {
    return {}
  } finally {
    if (timeoutId) {
      window.clearTimeout(timeoutId)
    }
  }
}

export async function reverseGeocode(latitude, longitude) {
  const cacheKey = buildReverseGeocodeCacheKey(latitude, longitude)
  const cachedValue = reverseGeocodeCache.get(cacheKey)
  if (cachedValue) {
    return cachedValue
  }

  const inFlightRequest = reverseGeocodeInFlight.get(cacheKey)
  if (inFlightRequest) {
    return inFlightRequest
  }

  const request = executeReverseGeocodeTask(async () => {
    const result = await fetchReverseGeocode(latitude, longitude)
    rememberReverseGeocode(cacheKey, result)
    return result
  })

  reverseGeocodeInFlight.set(cacheKey, request)

  try {
    return await request
  } finally {
    reverseGeocodeInFlight.delete(cacheKey)
  }
}

export async function extractPhotoMetadata(file) {
  if (!file) {
    return null
  }

  const metadata = await exifr.parse(file, {
    gps: true,
    tiff: true,
    exif: true,
    ifd0: true,
  })

  if (!metadata) {
    return null
  }

  const takenAt =
    metadata.DateTimeOriginal ||
    metadata.CreateDate ||
    metadata.ModifyDate ||
    metadata.DateTimeDigitized ||
    null

  const latitude = normalizeCoordinate(metadata.latitude)
  const longitude = normalizeCoordinate(metadata.longitude)
  const locationDetails =
    latitude !== null && longitude !== null ? await reverseGeocode(latitude, longitude) : {}

  return {
    ...toDateParts(takenAt),
    latitude,
    longitude,
    country: locationDetails.country || '',
    region: locationDetails.region || '',
    placeName: locationDetails.placeName || '',
  }
}
