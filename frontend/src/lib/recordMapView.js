// Pure helpers for the record-map workspace. Each function reproduces the
// expression that used to live inline in RecordMapWorkspace / RecordMapHubWorkspace.

// Later (list) entries overwrite earlier (map) entries with the same id; order is first-seen.
export function mergePlacesById(mapPlaces = [], listPlaces = []) {
  return [...new Map([...mapPlaces, ...listPlaces].map(place => [place.id, place])).values()]
}

// placeId -> photoUrl of the first photo of the first visit that has photos.
// Search order is every list visit, then every map visit (same as [...list, ...map].find).
export function buildFirstPhotoIndex(listVisits = [], mapVisits = []) {
  const index = new Map()
  for (const visit of [...listVisits, ...mapVisits]) {
    if (visit.photos?.length && !index.has(visit.placeId)) index.set(visit.placeId, visit.photos[0].photoUrl)
  }
  return index
}

// Location candidates -> map markers. Candidate ids are always `location:{n}`, so a null
// focusedId never matches (equivalent to the original `foundLocation?.id === location.id`).
export function buildLocationMarkers(locationResults = [], focusedId = null) {
  return locationResults.map((location, index) => ({ ...location,
    title: location.name, placeName: location.name, recordMap: true, recordMapLocation: true, searchResultNumber: index + 1,
    searchResultFocused: focusedId != null && focusedId === location.id,
    planName: '검색 후보 · 저장 전', planColorHex: '#357CCB' }))
}

export function sortDiaryVisits(visits = []) {
  return visits.filter(visit => visit.date).sort((a, b) => (b.date + (b.time || '')).localeCompare(a.date + (a.time || '')))
}

export function placeIdFromMapItem(item) {
  return Number(item?.recordMapPlaceId ?? item?.placeId ?? Number(String(item?.id || item?.clusterId || '').split(':').at(-1)))
}

export function formatPlaceMeta(place = {}) {
  return [place.country, place.region, place.visited ? '방문한 곳' : '가고 싶은 곳'].filter(Boolean).join(' · ')
}

export function countActiveFilters(filters = {}) {
  return ['collectionId', 'from', 'to'].filter(key => Boolean(filters[key])).length
}

export function hubModeForRoute(route) { return route === 'travel' ? 'records' : 'travel' }
export function hubHashForMode(mode) { return mode === 'records' ? 'travel' : mode === 'travel' ? 'my-map' : '' }
