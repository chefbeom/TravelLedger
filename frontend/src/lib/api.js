const API_BASE = '/api'
const CSRF_COOKIE_NAME = 'XSRF-TOKEN'
const CSRF_HEADER_NAME = 'X-XSRF-TOKEN'

function getCookie(name) {
  return document.cookie
    .split(';')
    .map((item) => item.trim())
    .find((item) => item.startsWith(`${name}=`))
    ?.slice(name.length + 1) ?? ''
}

async function ensureCsrfToken() {
  let token = decodeURIComponent(getCookie(CSRF_COOKIE_NAME))
  if (token) {
    return token
  }

  const response = await fetch(`${API_BASE}/auth/csrf`, {
    credentials: 'include',
  })

  if (!response.ok) {
    throw new Error('CSRF token request failed.')
  }

  try {
    const body = await response.json()
    token = decodeURIComponent(getCookie(CSRF_COOKIE_NAME)) || body.token || ''
  } catch {
    token = decodeURIComponent(getCookie(CSRF_COOKIE_NAME))
  }

  if (!token) {
    throw new Error('CSRF token is missing.')
  }

  return token
}

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
  const method = String(options.method || 'GET').toUpperCase()
  const needsCsrf = !['GET', 'HEAD', 'OPTIONS', 'TRACE'].includes(method)
  const csrfToken = needsCsrf ? await ensureCsrfToken() : ''
  const headers = {
    ...(!isFormData && options.body ? { 'Content-Type': 'application/json' } : {}),
    ...(csrfToken ? { [CSRF_HEADER_NAME]: csrfToken } : {}),
    ...(options.headers ?? {}),
  }

  const response = await fetch(`${API_BASE}${path}`, {
    credentials: 'include',
    ...options,
    headers,
  })

  if (!response.ok) {
    let message = '요청을 처리하는 중 문제가 발생했습니다.'
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

  const text = await response.text()
  if (!text) {
    return null
  }

  return JSON.parse(text)
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

export function logout() {
  return request('/auth/logout', {
    method: 'POST',
  })
}

export function createInvite(payload) {
  return request('/invites', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function fetchAdminDashboard() {
  return request('/admin/dashboard')
}

export function fetchAdminLoginAuditLogs(page = 0) {
  return request(`/admin/login-audit-logs?page=${page}`)
}

export function updateAdminUserActive(userId, active) {
  return request(`/admin/users/${userId}/active`, {
    method: 'PATCH',
    body: JSON.stringify({ active }),
  })
}

export function unlockBlockedIp(ip) {
  return request(`/admin/blocked-ips?ip=${encodeURIComponent(ip)}`, {
    method: 'DELETE',
  })
}

export function fetchAdminSupportInquiries() {
  return request('/admin/support-inquiries')
}

export function replyAdminSupportInquiry(inquiryId, content) {
  return request(`/admin/support-inquiries/${inquiryId}/reply`, {
    method: 'PUT',
    body: JSON.stringify({ content }),
  })
}

export function archiveAdminSupportInquiry(inquiryId, archived) {
  return request(`/admin/support-inquiries/${inquiryId}/archive`, {
    method: 'PATCH',
    body: JSON.stringify({ archived }),
  })
}

export function deleteAdminSupportInquiry(inquiryId) {
  return request(`/admin/support-inquiries/${inquiryId}`, {
    method: 'DELETE',
  })
}

export function fetchInvite(token) {
  return request(`/invites/${encodeURIComponent(token)}`)
}

export function acceptInvite(payload) {
  return request('/invites/accept', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function fetchMySupportInquiries(page = 0, size = 5) {
  return request(buildUrl('/support/inquiries/me', { page, size }).replace(API_BASE, ''))
}

export function createSupportInquiry(formData) {
  return request('/support/inquiries', {
    method: 'POST',
    body: formData,
  })
}

export function verifyProfileSecondaryPin(secondaryPin) {
  return request('/auth/profile/verify-secondary-pin', {
    method: 'POST',
    body: JSON.stringify({ secondaryPin }),
  })
}

export function changeProfilePassword(payload) {
  return request('/auth/profile/password', {
    method: 'PUT',
    body: JSON.stringify(payload),
  })
}

export function changeProfileSecondaryPin(payload) {
  return request('/auth/profile/secondary-pin', {
    method: 'PUT',
    body: JSON.stringify(payload),
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

export function fetchEntrySearchPage(params) {
  return request(buildUrl('/entries/search', params).replace(API_BASE, ''))
}

export function fetchEntryDateRange() {
  return request('/entries/date-range')
}

export function fetchDeletedEntryPage(params = {}) {
  return request(buildUrl('/entries/trash', params).replace(API_BASE, ''))
}

export function emptyDeletedEntries() {
  return request('/entries/trash', {
    method: 'DELETE',
  })
}

function resolveDownloadFileName(response, fallback) {
  const disposition = response.headers.get('Content-Disposition') || ''
  const utf8Match = disposition.match(/filename\*=UTF-8''([^;]+)/i)
  if (utf8Match?.[1]) {
    return decodeURIComponent(utf8Match[1])
  }
  const basicMatch = disposition.match(/filename=\"?([^\";]+)\"?/i)
  return basicMatch?.[1] || fallback
}

async function downloadFile(path, fallbackFileName, options = {}) {
  const isFormData = typeof FormData !== 'undefined' && options.body instanceof FormData
  const method = String(options.method || 'GET').toUpperCase()
  const needsCsrf = !['GET', 'HEAD', 'OPTIONS', 'TRACE'].includes(method)
  const csrfToken = needsCsrf ? await ensureCsrfToken() : ''
  const headers = {
    ...(!isFormData && options.body ? { 'Content-Type': 'application/json' } : {}),
    ...(csrfToken ? { [CSRF_HEADER_NAME]: csrfToken } : {}),
    ...(options.headers ?? {}),
  }

  const response = await fetch(`${API_BASE}${path}`, {
    credentials: 'include',
    ...options,
    headers,
  })

  if (!response.ok) {
    let message = '요청을 처리하는 중 문제가 발생했습니다.'

    try {
      const body = await response.json()
      message = body.message ?? message
    } catch {
      // Keep the generic message when the body is not JSON.
    }

    const error = new Error(message)
    error.status = response.status
    throw error
  }

  const blob = await response.blob()
  const fileName = resolveDownloadFileName(response, fallbackFileName)
  const objectUrl = window.URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = objectUrl
  link.download = fileName
  document.body.appendChild(link)
  link.click()
  link.remove()
  window.URL.revokeObjectURL(objectUrl)
}

export function downloadLedgerCsv(from, to) {
  const fallbackFileName = from && to
    ? `ledger-${from}_to_${to}.csv.zip`
    : 'ledger-all.csv.zip'
  return downloadFile(
    '/entries/export/csv',
    fallbackFileName,
    {
      method: 'POST',
      body: JSON.stringify({
        from,
        to,
      }),
    },
  )
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

export function deleteEntry(id, { permanent = false } = {}) {
  const suffix = permanent ? '?permanent=true' : ''
  return request(`/entries/${id}${suffix}`, {
    method: 'DELETE',
  })
}

export function restoreEntry(id) {
  return request(`/entries/${id}/restore`, {
    method: 'POST',
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

export function fetchHouseholdAggregatePreferences() {
  return request('/account/preferences/household-aggregates')
}

export function saveHouseholdAggregatePreferences(widgets) {
  return request('/account/preferences/household-aggregates', {
    method: 'PUT',
    body: JSON.stringify({ widgets }),
  })
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

export async function previewLedgerExcelImport(file) {
  const formData = new FormData()
  formData.append('file', file)

  return request('/entries/imports/excel/preview', {
    method: 'POST',
    body: formData,
  })
}

export function commitLedgerExcelImport(rows) {
  return request('/entries/imports/excel/commit', {
    method: 'POST',
    body: JSON.stringify({ rows }),
  })
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

async function uploadTravelMediaInternal({
  directPath,
  mediaType,
  files,
  caption = '',
  includeMediaTypeInDirect = true,
}) {
  const selectedFiles = [...(files ?? [])].filter(Boolean)
  if (!selectedFiles.length) {
    return []
  }

  return uploadTravelMediaDirect(directPath, mediaType, selectedFiles, caption, includeMediaTypeInDirect)
}

export async function uploadTravelRecordMedia(recordId, mediaType, files, caption = '') {
  return uploadTravelMediaInternal({
    directPath: `/travel/records/${recordId}/media`,
    mediaType,
    files,
    caption,
    includeMediaTypeInDirect: true,
  })
}

export async function uploadTravelMemoryMedia(memoryId, files, caption = '') {
  return uploadTravelMediaInternal({
    directPath: `/travel/memories/${memoryId}/media`,
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

export function shareTravelPlan(planId, payload) {
  return request(`/travel/plans/${planId}/shares`, {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function fetchTravelSharedExhibits() {
  return request('/travel/shared-exhibits')
}

export function fetchTravelSharedExhibit(shareId) {
  return request(`/travel/shared-exhibits/${shareId}`)
}

export function createTravelRoute(planId, payload) {
  return request(`/travel/plans/${planId}/routes`, {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function updateTravelRoute(routeId, payload) {
  return request(`/travel/routes/${routeId}`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  })
}

export function uploadTravelRouteGpxFiles(routeId, files) {
  const formData = new FormData()
  ;(files ?? []).forEach((file) => {
    formData.append('files', file)
  })

  return request(`/travel/routes/${routeId}/gpx-files`, {
    method: 'POST',
    body: formData,
  })
}

export function deleteTravelRoute(routeId) {
  return request(`/travel/routes/${routeId}`, {
    method: 'DELETE',
  })
}

export function fetchFamilyAlbumBootstrap() {
  return request('/family-album/bootstrap')
}

export function searchFamilyUsers(query) {
  return request(buildUrl('/family-album/users/search', { q: query }).replace(API_BASE, ''))
}

export function createFamilyCategory(payload) {
  return request('/family-album/categories', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function uploadFamilyMedia(categoryId, files, caption = '') {
  const formData = new FormData()
  formData.append('categoryId', String(categoryId))
  if (caption) {
    formData.append('caption', caption)
  }
  ;(files ?? []).forEach((file) => {
    formData.append('files', file)
  })

  return request('/family-album/media', {
    method: 'POST',
    body: formData,
  })
}

export function createFamilyAlbum(payload) {
  return request('/family-album/albums', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}
