const API_BASE = '/api'

function buildUrl(path, params = {}) {
  const search = new URLSearchParams()

  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      search.set(key, value)
    }
  })

  const queryString = search.toString()
  return `${API_BASE}${path}${queryString ? `?${queryString}` : ''}`
}

async function request(path, options = {}) {
  const isFormData = typeof FormData !== 'undefined' && options.body instanceof FormData
  const headers = {
    ...(!isFormData && options.body ? { 'Content-Type': 'application/json' } : {}),
    ...(options.headers ?? {}),
  }

  const response = await fetch(`${API_BASE}${path}`, {
    credentials: 'include',
    ...options,
    headers,
  })

  if (!response.ok) {
    let message = '?붿껌 泥섎━ 以??ㅻ쪟媛 諛쒖깮?덉뒿?덈떎.'
    let details = null

    try {
      const body = await response.json()
      message = body.message ?? message
      details = body
    } catch {
      // Keep the generic message when the body is not JSON.
    }

    const error = new Error(message)
    error.status = response.status
    error.details = details
    throw error
  }

  if (response.status === 204) {
    return null
  }

  return response.json()
}

export function fetchCurrentUser() {
  return request('/auth/me')
}

export function login(payload) {
  return request('/auth/login', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function register(payload) {
  return request('/auth/register', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function logout() {
  return request('/auth/logout', {
    method: 'POST',
  })
}

export function fetchDashboard(anchorDate) {
  return request(buildUrl('/dashboard', { anchorDate }).replace(API_BASE, ''))
}

export function fetchOverview(from, to) {
  return request(buildUrl('/statistics/overview', { from, to }).replace(API_BASE, ''))
}

export function fetchCategoryBreakdown(from, to, entryType) {
  return request(buildUrl('/statistics/category-breakdown', { from, to, entryType }).replace(API_BASE, ''))
}

export function fetchPaymentBreakdown(from, to) {
  return request(buildUrl('/statistics/payment-breakdown', { from, to }).replace(API_BASE, ''))
}

export function fetchCompare(anchorDate, unit, periods) {
  return request(buildUrl('/statistics/compare', { anchorDate, unit, periods }).replace(API_BASE, ''))
}

export function fetchEntries(from, to) {
  return request(buildUrl('/entries', { from, to }).replace(API_BASE, ''))
}

export function createEntry(payload) {
  return request('/entries', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function updateEntry(id, payload) {
  return request(`/entries/${id}`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  })
}

export function deleteEntry(id) {
  return request(`/entries/${id}`, {
    method: 'DELETE',
  })
}

export function fetchCategories(entryType) {
  return request(buildUrl('/categories', { entryType }).replace(API_BASE, ''))
}

export function createCategoryGroup(payload) {
  return request('/categories/groups', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function createCategoryDetail(payload) {
  return request('/categories/details', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function deactivateCategoryGroup(id) {
  return request(`/categories/groups/${id}`, { method: 'DELETE' })
}

export function deactivateCategoryDetail(id) {
  return request(`/categories/details/${id}`, { method: 'DELETE' })
}

export function fetchPaymentMethods() {
  return request('/payment-methods')
}

export function createPaymentMethod(payload) {
  return request('/payment-methods', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function deactivatePaymentMethod(id) {
  return request(`/payment-methods/${id}`, { method: 'DELETE' })
}

export function fetchTravelPlans() {
  return request('/travel/plans')
}

export function fetchTravelPlan(planId) {
  return request(`/travel/plans/${planId}`)
}

export function fetchTravelPortfolio() {
  return request('/travel/portfolio')
}

export function createTravelPlan(payload) {
  return request('/travel/plans', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function updateTravelPlan(planId, payload) {
  return request(`/travel/plans/${planId}`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  })
}

export function deleteTravelPlan(planId) {
  return request(`/travel/plans/${planId}`, {
    method: 'DELETE',
  })
}

export function createTravelBudgetItem(planId, payload) {
  return request(`/travel/plans/${planId}/budget-items`, {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function updateTravelBudgetItem(itemId, payload) {
  return request(`/travel/budget-items/${itemId}`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  })
}

export function deleteTravelBudgetItem(itemId) {
  return request(`/travel/budget-items/${itemId}`, {
    method: 'DELETE',
  })
}

export function createTravelRecord(planId, payload) {
  return request(`/travel/plans/${planId}/records`, {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function createTravelMemory(planId, payload) {
  return request(`/travel/plans/${planId}/memories`, {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function updateTravelRecord(recordId, payload) {
  return request(`/travel/records/${recordId}`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  })
}

export function updateTravelMemory(memoryId, payload) {
  return request(`/travel/memories/${memoryId}`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  })
}

export function deleteTravelRecord(recordId) {
  return request(`/travel/records/${recordId}`, {
    method: 'DELETE',
  })
}

export function deleteTravelMemory(memoryId) {
  return request(`/travel/memories/${memoryId}`, {
    method: 'DELETE',
  })
}

function uploadTravelMediaDirect(path, mediaType, files, caption = '', includeMediaType = true) {
  const formData = new FormData()
  if (includeMediaType) {
    formData.append('mediaType', mediaType)
  }
  if (caption) {
    formData.append('caption', caption)
  }
  ;(files ?? []).forEach((file) => {
    formData.append('files', file)
  })

  return request(path, {
    method: 'POST',
    body: formData,
  })
}

function buildUploadFilePayload(file) {
  return {
    originalFileName: file.name,
    contentType: file.type || 'application/octet-stream',
    fileSize: file.size,
  }
}

async function uploadFileToPresignedUrl(upload, file) {
  const response = await fetch(upload.uploadUrl, {
    method: upload.method || 'PUT',
    headers: upload.contentType ? { 'Content-Type': upload.contentType } : undefined,
    body: file,
  })

  if (!response.ok) {
    throw new Error('MinIO direct upload failed. Check MinIO endpoint and CORS settings.')
  }
}

async function uploadTravelMediaInternal({
  directPath,
  presignPath,
  completePath,
  mediaType,
  files,
  caption = '',
  includeMediaTypeInDirect = true,
}) {
  const selectedFiles = [...(files ?? [])].filter(Boolean)
  if (!selectedFiles.length) {
    return []
  }

  let prepare
  try {
    prepare = await request(presignPath, {
      method: 'POST',
      body: JSON.stringify({
        mediaType,
        caption,
        files: selectedFiles.map(buildUploadFilePayload),
      }),
    })
  } catch (error) {
    if (error.status && error.status >= 400) {
      return uploadTravelMediaDirect(directPath, mediaType, selectedFiles, caption, includeMediaTypeInDirect)
    }
    throw error
  }

  if (prepare.uploadMode !== 'PRESIGNED') {
    return uploadTravelMediaDirect(directPath, mediaType, selectedFiles, caption, includeMediaTypeInDirect)
  }

  if (!Array.isArray(prepare.uploads) || prepare.uploads.length !== selectedFiles.length) {
    throw new Error('Upload ticket response is invalid.')
  }

  try {
    await Promise.all(
      prepare.uploads.map((upload, index) => uploadFileToPresignedUrl(upload, selectedFiles[index])),
    )

    return await request(completePath, {
      method: 'POST',
      body: JSON.stringify({
        mediaType,
        caption,
        files: prepare.uploads.map((upload) => ({
          objectKey: upload.objectKey,
          originalFileName: upload.originalFileName,
          contentType: upload.contentType,
          fileSize: upload.fileSize,
        })),
      }),
    })
  } catch (error) {
    return uploadTravelMediaDirect(directPath, mediaType, selectedFiles, caption, includeMediaTypeInDirect)
  }
}

export async function uploadTravelRecordMedia(recordId, mediaType, files, caption = '') {
  return uploadTravelMediaInternal({
    directPath: `/travel/records/${recordId}/media`,
    presignPath: `/travel/records/${recordId}/media/presign`,
    completePath: `/travel/records/${recordId}/media/complete`,
    mediaType,
    files,
    caption,
    includeMediaTypeInDirect: true,
  })
}

export async function uploadTravelMemoryMedia(memoryId, files, caption = '') {
  return uploadTravelMediaInternal({
    directPath: `/travel/memories/${memoryId}/media`,
    presignPath: `/travel/memories/${memoryId}/media/presign`,
    completePath: `/travel/memories/${memoryId}/media/complete`,
    mediaType: 'PHOTO',
    files,
    caption,
    includeMediaTypeInDirect: false,
  })
}

export function deleteTravelMedia(mediaId) {
  return request(`/travel/media/${mediaId}`, {
    method: 'DELETE',
  })
}

export function fetchTravelExchangeRates(currencies = []) {
  return request(buildUrl('/travel/exchange-rates', {
    currencies: currencies.filter(Boolean).join(','),
  }).replace(API_BASE, ''))
}

export function fetchTravelCategories() {
  return request('/travel/categories')
}

export function fetchTravelCommunityFeed() {
  return request('/travel/community-feed')
}

export function createTravelRoute(planId, payload) {
  return request(`/travel/plans/${planId}/routes`, {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function deleteTravelRoute(routeId) {
  return request(`/travel/routes/${routeId}`, {
    method: 'DELETE',
  })
}