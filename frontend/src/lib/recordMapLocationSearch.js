// These are real provider results, not fabricated locations or exit coordinates.
export function normalizeLocationResults(rows) {
  const results = [], seen = new Set()
  for (const row of Array.isArray(rows) ? rows : []) {
    if (row?.latitude == null || row?.longitude == null || row.latitude === '' || row.longitude === '') continue
    const latitude = Number(row.latitude), longitude = Number(row.longitude)
    const name = String(row.name || '').trim().slice(0, 160)
    if (!name || !Number.isFinite(latitude) || !Number.isFinite(longitude) || Math.abs(latitude) > 90 || Math.abs(longitude) > 180) continue
    const key = `${name.toLowerCase()}:${latitude.toFixed(6)}:${longitude.toFixed(6)}`
    if (seen.has(key)) continue
    seen.add(key)
    results.push({ id: `location:${results.length}`, name, latitude, longitude,
      displayName: String(row.displayName || name).slice(0, 500), country: String(row.country || '').slice(0, 80), region: String(row.region || '').slice(0, 120) })
    if (results.length === 10) break
  }
  return results
}

const searchable = value => String(value || '').replace(/\s+/g, '').toLowerCase()

export function locationSuggestions(query, previousResults = []) {
  const term = searchable(query)
  if (term.length < 2) return []
  const seen = new Set()
  return previousResults.filter(row => {
    const name = searchable(row.name)
    if (!name.includes(term) || seen.has(name)) return false
    seen.add(name)
    return true
  }).sort((a, b) => Number(searchable(b.name).startsWith(term)) - Number(searchable(a.name).startsWith(term))).slice(0, 5)
}

export function relatedLocationQueries(query) {
  const term = String(query || '').trim()
  // Refinement keywords only: never invent numbered exits that the provider did not return.
  return term.length >= 2 && term.length <= 90 && /역$/.test(term) ? [`${term} 출구`] : []
}
