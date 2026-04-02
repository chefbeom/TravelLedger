import exifr from 'exifr'

const REVERSE_GEOCODE_CACHE_LIMIT = 300

const reverseGeocodeCache = new Map()
const reverseGeocodeInFlight = new Map()

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

async function fetchReverseGeocode(latitude, longitude) {
  try {
    const response = await fetch(
      `/api/travel/geocode/reverse?lat=${encodeURIComponent(latitude)}&lon=${encodeURIComponent(longitude)}`,
      {
        credentials: 'include',
      },
    )

    if (!response.ok) {
      return {}
    }

    const payload = await response.json()
    return {
      country: payload?.country || '',
      region: payload?.region || '',
      placeName: payload?.placeName || '',
    }
  } catch {
    return {}
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

  const request = fetchReverseGeocode(latitude, longitude).then((result) => {
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
