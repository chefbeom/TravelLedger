import exifr from 'exifr'

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

export async function reverseGeocode(latitude, longitude) {
  const controller = typeof AbortController !== 'undefined' ? new AbortController() : null
  const timeoutId = controller ? window.setTimeout(() => controller.abort(), 6000) : null

  try {
    const response = await fetch(
      `https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=${latitude}&lon=${longitude}&zoom=18&addressdetails=1`,
      {
        headers: {
          'Accept-Language': 'ko,en',
        },
        signal: controller?.signal,
      },
    )

    if (!response.ok) {
      return {}
    }

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
  } catch {
    return {}
  } finally {
    if (timeoutId) {
      window.clearTimeout(timeoutId)
    }
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
