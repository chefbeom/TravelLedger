export function buildRecordMapData(overview = {}) {
  const places = overview.places || []
  const visits = overview.visits || []
  const photoPins = []
  const photoClusters = []
  const markers = []
  for (const place of places) {
    const rows = visits.filter(v => v.placeId === place.id)
    const color = overview.collections?.find(c => place.collectionIds?.includes(c.id))?.colorHex || (place.visited ? '#009E8C' : '#C88623')
    const common = { recordMap: true, recordMapPlaceId: place.id, planName: '일상 기록', planColorHex: color,
      title: place.name, country: place.country, region: place.region, placeName: place.name,
      latitude: place.latitude, longitude: place.longitude }
    for (const visit of rows) for (const photo of visit.photos || []) {
      photoPins.push({ ...common, mediaId: photo.id, clusterId: 'place:' + place.id, recordId: visit.id,
        memoryDate: visit.date, memoryTime: visit.time, title: visit.title, photoUrl: photo.photoUrl,
        planId: visit.travelPlanId, planName: visit.travelPlanName || '일상 기록', originalFileName: photo.name })
    }
    const pictures = photoPins.filter(p => p.recordMapPlaceId === place.id)
    if (pictures.length) {
      photoClusters.push({ ...common, id: 'place:' + place.id, photoCount: pictures.length, memoryCount: rows.length,
        representativeMediaId: pictures[0].mediaId, representativePhotoUrl: pictures[0].photoUrl,
        memoryDate: pictures[0].memoryDate, memoryTime: pictures[0].memoryTime })
    } else {
      markers.push({ ...common, id: 'place:' + place.id, memoryDate: rows[0]?.date, memoryTime: rows[0]?.time,
        memoryCount: rows.length, title: place.name + (place.visited ? ' · 방문한 곳' : ' · 가고 싶은 곳') })
    }
  }
  return { markers, photoPins, photoClusters }
}

export function buildRecordMapJourney(overview = {}, day = '') {
  const byPlace = new Map((overview.places || []).map(p => [p.id, p]))
  return (overview.visits || []).filter(v => v.date && (!day || v.date === day) && byPlace.has(v.placeId))
    .flatMap(v => {
      const place = byPlace.get(v.placeId)
      const pictures = v.photos?.length ? v.photos : [null]
      return pictures.map(photo => ({ visitId: v.id, placeId: place.id, date: v.date, time: v.time || '',
        latitude: place.latitude, longitude: place.longitude, mediaId: photo?.id || null,
        photoUrl: photo?.photoUrl || '', title: v.title, markerId: 'place:' + place.id }))
    })
    .sort((a, b) => (a.date + ' ' + a.time).localeCompare(b.date + ' ' + b.time)
      || String(a.visitId).localeCompare(String(b.visitId), undefined, { numeric: true })
      || String(a.mediaId).localeCompare(String(b.mediaId), undefined, { numeric: true }))
}
