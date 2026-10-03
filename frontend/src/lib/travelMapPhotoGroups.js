function validPoint(item) {
  if (item?.latitude == null || item?.longitude == null || item.latitude === '' || item.longitude === '') return false
  const latitude = Number(item.latitude)
  const longitude = Number(item.longitude)
  return Number.isFinite(latitude) && Number.isFinite(longitude) && Math.abs(latitude) <= 90 && Math.abs(longitude) <= 180
}

function photoKey(photo) {
  return photo.clusterId != null
    ? `cluster-${photo.clusterId}`
    : `location-${Number(photo.latitude).toFixed(6)}-${Number(photo.longitude).toFixed(6)}`
}

function photoDate(photo) {
  return [photo.memoryDate || photo.expenseDate || photo.uploadedAt || '', photo.memoryTime || photo.expenseTime || '', String(photo.mediaId)].join(' ')
}

// Both display modes share stable server groups. A day filter supplies only pins:
// rebuild those groups without counting photos from other days.
export function buildTravelMapPhotoGroups(clusters = [], pins = []) {
  const pinsByGroup = new Map()
  const mediaIds = new Set()
  for (const pin of pins) {
    if (pin?.mediaId == null || !validPoint(pin) || mediaIds.has(String(pin.mediaId))) continue
    mediaIds.add(String(pin.mediaId))
    const key = photoKey(pin)
    const photos = pinsByGroup.get(key) || []
    photos.push(pin)
    pinsByGroup.set(key, photos)
  }
  for (const photos of pinsByGroup.values()) photos.sort((left, right) => photoDate(left).localeCompare(photoDate(right)))

  const groups = []
  const knownGroups = new Set()
  for (const cluster of clusters) {
    if (cluster?.id == null || !validPoint(cluster)) continue
    const key = `cluster-${cluster.id}`
    if (knownGroups.has(key)) continue
    knownGroups.add(key)
    const photos = pinsByGroup.get(key) || []
    groups.push({
      key, cluster, photos,
      representative: cluster,
      latitude: Number(cluster.latitude),
      longitude: Number(cluster.longitude),
      photoCount: Math.max(Number(cluster.photoCount) || 0, photos.length),
      memoryCount: Number(cluster.memoryCount) || 0,
    })
  }
  for (const [key, photos] of pinsByGroup) {
    if (knownGroups.has(key)) continue
    groups.push({
      key, cluster: null, photos,
      representative: photos[0],
      latitude: photos.reduce((sum, photo) => sum + Number(photo.latitude), 0) / photos.length,
      longitude: photos.reduce((sum, photo) => sum + Number(photo.longitude), 0) / photos.length,
      photoCount: photos.length,
      memoryCount: new Set(photos.map((photo) => photo.recordId ?? photo.memoryId ?? photo.mediaId)).size,
    })
  }
  return groups
}
