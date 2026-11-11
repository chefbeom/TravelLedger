import exifr from 'exifr'

// Compare locally read GPS only; do not send every photo's coordinates to a geocoder.
export function hasDistantPhotoLocations(points, thresholdMeters = 250) {
  const valid = points.filter(point => Number.isFinite(point?.latitude) && Number.isFinite(point?.longitude)
    && Math.abs(point.latitude) <= 90 && Math.abs(point.longitude) <= 180)
  if (valid.length < 2) return false
  const radians = degrees => degrees * Math.PI / 180
  return valid.some((first, index) => valid.slice(index + 1).some(point => {
    const deltaLat = radians(point.latitude - first.latitude)
    const deltaLon = radians(point.longitude - first.longitude)
    const a = Math.sin(deltaLat / 2) ** 2
      + Math.cos(radians(first.latitude)) * Math.cos(radians(point.latitude)) * Math.sin(deltaLon / 2) ** 2
    return 12742000 * Math.asin(Math.sqrt(Math.min(1, a))) > thresholdMeters
  }))
}

export async function photosHaveDistantLocations(files) {
  if (files.length < 2) return false
  const positions = await Promise.all(files.map(async file => {
    try { return await exifr.gps(file) } catch { return null }
  }))
  return hasDistantPhotoLocations(positions)
}
