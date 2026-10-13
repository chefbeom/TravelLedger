import { requestRecordMap } from './api.js'

function query(params) {
  const search = new URLSearchParams()
  Object.entries(params || {}).forEach(([key, value]) => {
    if (value !== '' && value != null) search.set(key, String(value))
  })
  return search.size ? '?' + search.toString() : ''
}
const json = (method, body) => ({ method, body: JSON.stringify(body) })
export const recordMapApi = {
  overview: (params, token = '') => requestRecordMap((token ? '/public/' + encodeURIComponent(token) : '/overview') + query(params)),
  place: (id, body) => requestRecordMap('/places' + (id ? '/' + id : ''), json(id ? 'PUT' : 'POST', body)),
  deletePlace: (id) => requestRecordMap('/places/' + id, { method: 'DELETE' }),
  collection: (id, body) => requestRecordMap('/collections' + (id ? '/' + id : ''), json(id ? 'PUT' : 'POST', body)),
  deleteCollection: (id) => requestRecordMap('/collections/' + id, { method: 'DELETE' }),
  visits: (placeId, page = 0) => requestRecordMap('/places/' + placeId + '/visits' + query({ page })),
  visit: (placeId, id, body) => requestRecordMap('/places/' + placeId + '/visits' + (id ? '/' + id : ''), json(id ? 'PUT' : 'POST', body)),
  deleteVisit: (id) => requestRecordMap('/visits/' + id, { method: 'DELETE' }),
  photo: (visitId, file) => { const body = new FormData(); body.append('file', file); return requestRecordMap('/visits/' + visitId + '/photos', { method: 'POST', body }) },
  deletePhoto: (id) => requestRecordMap('/photos/' + id, { method: 'DELETE' }),
  candidates: (page = 0) => requestRecordMap('/travel-candidates' + query({ page })),
  importTravel: (recordId, collectionIds) => requestRecordMap('/travel-imports', json('POST', { recordId, collectionIds })),
  shares: (id) => requestRecordMap('/collections/' + id + '/shares'),
  createShare: (id, body) => requestRecordMap('/collections/' + id + '/shares', json('POST', body)),
  revokeShare: (id) => requestRecordMap('/shares/' + id, { method: 'DELETE' }),
}
