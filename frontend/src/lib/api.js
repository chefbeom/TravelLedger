import { prepareClientImageUpload } from './mediaPreview'

const API_BASE = '/api'
const CSRF_COOKIE_NAME = 'XSRF-TOKEN'
const CSRF_HEADER_NAME = 'X-XSRF-TOKEN'
const MAX_TRAVEL_MEDIA_FILE_SIZE = 15 * 1024 * 1024
const TRAVEL_MEDIA_PREPARATION_CONCURRENCY = 2
const TRAVEL_MEDIA_UPLOAD_CONCURRENCY = 3

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

function inferContentTypeFromFileName(fileName) {
  const normalizedName = String(fileName || '').trim().toLowerCase()
  if (normalizedName.endsWith('.jpg') || normalizedName.endsWith('.jpeg')) {
    return 'image/jpeg'
  }
  if (normalizedName.endsWith('.png')) {
    return 'image/png'
  }
  if (normalizedName.endsWith('.webp')) {
    return 'image/webp'
  }
  if (normalizedName.endsWith('.gif')) {
    return 'image/gif'
  }
  if (normalizedName.endsWith('.bmp')) {
    return 'image/bmp'
  }
  if (normalizedName.endsWith('.pdf')) {
    return 'application/pdf'
  }
  return 'application/octet-stream'
}

function resolveUploadContentType(file) {
  const declaredType = String(file?.type || '').trim().toLowerCase()
  if (declaredType) {
    return declaredType
  }
  return inferContentTypeFromFileName(file?.name)
}

function normalizeConcurrencyLimit(value, fallback = 1) {
  const numericValue = Number(value)
  if (!Number.isFinite(numericValue) || numericValue < 1) {
    return fallback
  }
  return Math.max(1, Math.floor(numericValue))
}

async function mapWithConcurrency(items, concurrency, worker) {
  const normalizedItems = Array.isArray(items) ? items : []
  if (!normalizedItems.length) {
    return []
  }

  const results = new Array(normalizedItems.length)
  const limit = Math.min(
    normalizedItems.length,
    normalizeConcurrencyLimit(concurrency, 1),
  )
  let nextIndex = 0
  let abortedError = null

  async function runWorker() {
    while (!abortedError) {
      const currentIndex = nextIndex
      nextIndex += 1

      if (currentIndex >= normalizedItems.length) {
        return
      }

      try {
        results[currentIndex] = await worker(normalizedItems[currentIndex], currentIndex)
      } catch (error) {
        abortedError = error
        throw error
      }
    }
  }

  await Promise.all(Array.from({ length: limit }, () => runWorker()))
  return results
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

export function fetchAdminDataManagement() {
  return request('/admin/data-management')
}

export function createAdminDataBackup() {
  return request('/admin/data-management/backup', {
    method: 'POST',
  })
}

export function createAdminMinioBackup() {
  return request('/admin/data-management/minio-backup', {
    method: 'POST',
  })
}

export async function downloadAdminDataBackup() {
  const csrfToken = await ensureCsrfToken()
  const response = await fetch(`${API_BASE}/admin/data-management/backup/download`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      [CSRF_HEADER_NAME]: csrfToken,
    },
  })

  if (!response.ok) {
    let message = '?붿껌??泥섎━?섎뒗 以?臾몄젣媛 諛쒖깮?덉뒿?덈떎.'

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

  const disposition = response.headers.get('Content-Disposition') || ''
  const fileNameMatch = disposition.match(/filename=\"?([^\";]+)\"?/)
  const fileName = fileNameMatch?.[1] || 'calen-backup.sql.gz'
  const blob = await response.blob()
  return { blob, fileName }
}

export function restoreAdminDataBackup(fileName) {
  return request('/admin/data-management/restore', {
    method: 'POST',
    body: JSON.stringify({ fileName }),
  })
}

export function restoreAdminUploadedBackup(file) {
  const formData = new FormData()
  formData.append('file', file)
  return request('/admin/data-management/restore/upload', {
    method: 'POST',
    body: formData,
  })
}

export function fetchAdminAccessStatus() {
  return request('/admin/access/status')
}

export function verifyAdminAccess(code) {
  return request('/admin/access/verify', {
    method: 'POST',
    body: JSON.stringify({ code }),
  })
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

export function fetchTravelMyMapOverview() {
  return request('/travel/my-map')
}

export function fetchTravelMyMapMarkerDetails(markerId) {
  return request(`/travel/my-map/markers/${markerId}`)
}

export function fetchTravelMyMapPhotoCluster(clusterId, params = {}) {
  return request(buildUrl(`/travel/my-map/photo-clusters/${clusterId}`, params).replace(API_BASE, ''))
}

export function updateTravelMyMapPhotoClusterRepresentative(clusterId, mediaId) {
  return request(`/travel/my-map/photo-clusters/${clusterId}/representative`, {
    method: 'PUT',
    body: JSON.stringify({ mediaId }),
  })
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

async function uploadPresignedTravelMediaFile(target, file) {
  const response = await fetch(target.uploadUrl, {
    method: target.method || 'PUT',
    headers: (target?.contentType || file?.type)
      ? {
          'Content-Type': target?.contentType || file?.type,
        }
      : {},
    body: file,
  })

  if (!response.ok) {
    throw new Error('파일 업로드 중 문제가 발생했습니다.')
  }
}

function uploadPresignedTravelMediaFileWithProgress(target, file) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open(target.method || 'PUT', target.uploadUrl, true)

    if (target?.contentType || file?.type) {
      xhr.setRequestHeader('Content-Type', target?.contentType || file?.type)
    }

    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve()
        return
      }

      reject(new Error('파일 업로드 중 문제가 발생했습니다.'))
    }
    xhr.onerror = () => reject(new Error('파일 업로드 중 문제가 발생했습니다.'))
    xhr.onabort = () => reject(new Error('파일 업로드가 취소되었습니다.'))
    xhr.send(file)
  })
}

async function prepareTravelMediaUploadEntries(files, onProgress) {
  const selectedFiles = [...(files ?? [])].filter(Boolean)
  const preparedEntries = new Array(selectedFiles.length)
  let preparedCount = 0

  onProgress?.({
    phase: 'preparing',
    current: 0,
    total: selectedFiles.length,
  })

  await mapWithConcurrency(selectedFiles, TRAVEL_MEDIA_PREPARATION_CONCURRENCY, async (file, index) => {
    const contentType = resolveUploadContentType(file)
    const normalizedFileType = String(file?.type || '').trim().toLowerCase()
    const sourceFile =
      normalizedFileType === contentType
        ? file
        : new File([file], file.name, { type: contentType, lastModified: file.lastModified })

    const preparedImage = contentType.startsWith('image/')
      ? await prepareClientImageUpload(sourceFile)
      : {
          gpsLatitude: null,
          gpsLongitude: null,
          thumbnails: [],
        }

    preparedEntries[index] = {
      originalFile: file,
      originalFileName: file.name,
      contentType,
      fileSize: file.size,
      gpsLatitude: preparedImage.gpsLatitude,
      gpsLongitude: preparedImage.gpsLongitude,
      thumbnails: preparedImage.thumbnails,
    }

    preparedCount += 1
    onProgress?.({
      phase: 'preparing',
      current: preparedCount,
      total: selectedFiles.length,
      fileName: file?.name || '',
    })
  })

  return preparedEntries
}

async function uploadPreparedTravelMediaEntry(uploadTarget, preparedEntry) {
  if (!preparedEntry) {
    throw new Error('Prepared travel media upload is incomplete.')
  }

  await uploadPresignedTravelMediaFileWithProgress(uploadTarget, preparedEntry.originalFile)

  const thumbnailTargets = Array.isArray(uploadTarget?.thumbnails) ? uploadTarget.thumbnails : []
  if (thumbnailTargets.length !== preparedEntry.thumbnails.length) {
    throw new Error('Prepared thumbnail upload is incomplete.')
  }

  for (const thumbnailTarget of thumbnailTargets) {
    const localThumbnail = preparedEntry.thumbnails.find((thumbnail) => thumbnail.variant === thumbnailTarget.variant)
    if (!localThumbnail) {
      throw new Error('Prepared thumbnail upload is incomplete.')
    }
    await uploadPresignedTravelMediaFile(thumbnailTarget, localThumbnail.blob)
  }
}

async function uploadTravelMediaWithPresignedUrls({
  preparePath,
  completePath,
  mediaType,
  files,
  caption = '',
  onProgress,
}) {
  const selectedFiles = [...(files ?? [])].filter(Boolean)
  if (!selectedFiles.length) {
    return []
  }

  const preparedEntries = await prepareTravelMediaUploadEntries(selectedFiles, onProgress)

  const prepared = await request(preparePath, {
    method: 'POST',
    body: JSON.stringify({
      mediaType,
      caption,
      files: preparedEntries.map((entry) => ({
        originalFileName: entry.originalFileName,
        contentType: entry.contentType,
        fileSize: entry.fileSize,
        thumbnails: entry.thumbnails.map((thumbnail) => ({
          variant: thumbnail.variant,
          contentType: thumbnail.contentType,
          fileSize: thumbnail.fileSize,
        })),
      })),
    }),
  })

  if (
    prepared?.uploadMode !== 'PRESIGNED' ||
    !Array.isArray(prepared.uploads) ||
    prepared.uploads.length !== selectedFiles.length
  ) {
    throw new Error('Presigned travel media upload is unavailable.')
  }

  onProgress?.({
    phase: 'uploading',
    current: 0,
    total: prepared.uploads.length,
  })

  let uploadedCount = 0
  await mapWithConcurrency(prepared.uploads, TRAVEL_MEDIA_UPLOAD_CONCURRENCY, async (uploadTarget, index) => {
    const preparedEntry = preparedEntries[index]
    await uploadPreparedTravelMediaEntry(uploadTarget, preparedEntry)

    uploadedCount += 1
    onProgress?.({
      phase: 'uploading',
      current: uploadedCount,
      total: prepared.uploads.length,
      fileName: preparedEntry.originalFileName || uploadTarget?.originalFileName || '',
    })
  })

  onProgress?.({
    phase: 'finalizing',
    current: prepared.uploads.length,
    total: prepared.uploads.length,
  })

  return request(completePath, {
    method: 'POST',
    body: JSON.stringify({
      mediaType,
      caption,
      files: prepared.uploads.map((upload, index) => ({
        objectKey: upload.objectKey,
        originalFileName: upload.originalFileName,
        contentType: upload.contentType,
        fileSize: upload.fileSize,
        thumbnails: (Array.isArray(upload.thumbnails) ? upload.thumbnails : []).map((thumbnail) => ({
          variant: thumbnail.variant,
          objectKey: thumbnail.objectKey,
          contentType: thumbnail.contentType,
          fileSize: thumbnail.fileSize,
        })),
        gpsLatitude: preparedEntries[index]?.gpsLatitude,
        gpsLongitude: preparedEntries[index]?.gpsLongitude,
      })),
    }),
  })
}

async function uploadTravelMediaInternal({
  preparePath,
  completePath,
  mediaType,
  files,
  caption = '',
  onProgress,
}) {
  const selectedFiles = [...(files ?? [])].filter(Boolean)
  if (!selectedFiles.length) {
    return []
  }

  const oversizedFile = selectedFiles.find((file) => Number(file?.size || 0) > MAX_TRAVEL_MEDIA_FILE_SIZE)
  if (oversizedFile) {
    const error = new Error('여행 사진은 15MB 이하 파일만 업로드할 수 있습니다.')
    error.status = 400
    error.code = 'travel-media-too-large'
    throw error
  }

  if (preparePath && completePath) {
    return uploadTravelMediaWithPresignedUrls({
      preparePath,
      completePath,
      mediaType,
      files: selectedFiles,
      caption,
      onProgress,
    })
  }

  throw new Error('Travel media uploads require presigned object storage.')
}

export async function uploadTravelRecordMedia(recordId, mediaType, files, caption = '', options = {}) {
  return uploadTravelMediaInternal({
    preparePath: `/travel/records/${recordId}/media/presign`,
    completePath: `/travel/records/${recordId}/media/complete`,
    mediaType,
    files,
    caption,
    onProgress: options.onProgress,
  })
}

export async function uploadTravelMemoryMedia(memoryId, files, caption = '', options = {}) {
  return uploadTravelMediaInternal({
    preparePath: `/travel/memories/${memoryId}/media/presign`,
    completePath: `/travel/memories/${memoryId}/media/complete`,
    mediaType: 'PHOTO',
    files,
    caption,
    onProgress: options.onProgress,
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

export function fetchTravelCommunityFeed(page = 0, size = 10) {
  return request(buildUrl('/travel/community-feed', { page, size }).replace(API_BASE, ''))
}

export function shareTravelPlan(planId, payload) {
  return request(`/travel/plans/${planId}/shares`, {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function fetchTravelSharedExhibits(page = 0, size = 5) {
  return request(buildUrl('/travel/shared-exhibits', { page, size }).replace(API_BASE, ''))
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

export function fetchFamilyCategoryMediaPage(categoryId, page = 0, size = 10) {
  return request(buildUrl(`/family-album/categories/${categoryId}/media`, { page, size }).replace(API_BASE, ''))
}

export function fetchFamilyAlbumMediaPage(albumId, page = 0, size = 10) {
  return request(buildUrl(`/family-album/albums/${albumId}/media`, { page, size }).replace(API_BASE, ''))
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

export function fetchDriveHomeSummary() {
  return request('/file/home-summary')
}

export function fetchDrivePage(params = {}) {
  return request(buildUrl('/file/list/page', params).replace(API_BASE, ''))
}

export function fetchDriveRecentFiles() {
  return request('/file/recent')
}

export function fetchDriveTrashFiles() {
  return request('/file/trash')
}

export function initializeDriveUpload(files, parentId = null) {
  return request('/file/upload', {
    method: 'POST',
    body: JSON.stringify((files ?? []).map((file) => ({
      fileOriginName: file.name,
      fileFormat: file.name.includes('.') ? file.name.split('.').pop()?.toLowerCase() || '' : '',
      fileSize: file.size ?? 0,
      contentType: file.type || 'application/octet-stream',
      parentId,
      relativePath: file.webkitRelativePath || '',
      lastModified: file.lastModified ?? null,
    }))),
  })
}

export function completeDriveUpload(payload) {
  return request('/file/upload/complete', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function abortDriveUpload(payload) {
  return request('/file/upload/abort', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function uploadDriveFileWithProgress(target, file, onProgress) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('PUT', target.presignedUploadUrl, true)
    if (file?.type) {
      xhr.setRequestHeader('Content-Type', file.type)
    }
    xhr.upload.onprogress = (event) => {
      if (!event.lengthComputable) {
        return
      }
      onProgress?.({
        loaded: event.loaded,
        total: event.total,
        percent: Math.round((event.loaded / event.total) * 100),
      })
    }
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve()
        return
      }
      reject(new Error('파일 업로드 중 문제가 발생했습니다.'))
    }
    xhr.onerror = () => reject(new Error('파일 업로드 중 문제가 발생했습니다.'))
    xhr.onabort = () => reject(new Error('파일 업로드가 취소되었습니다.'))
    xhr.send(file)
  })
}

export function createDriveFolder(payload) {
  return request('/file/folder', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function renameDriveItem(fileId, fileName) {
  return request(`/file/${fileId}/rename`, {
    method: 'PATCH',
    body: JSON.stringify({ fileName }),
  })
}

export function moveDriveItemToTrash(fileId) {
  return request(`/file/${fileId}/trash`, {
    method: 'PATCH',
  })
}

export function restoreDriveItem(fileId) {
  return request(`/file/${fileId}/restore`, {
    method: 'PATCH',
  })
}

export function deleteDriveItem(fileId) {
  return request(`/file/${fileId}`, {
    method: 'DELETE',
  })
}

export function clearDriveTrash() {
  return request('/file/trash', {
    method: 'DELETE',
  })
}

export function moveDriveItem(fileId, targetParentId) {
  return request(`/file/${fileId}/move`, {
    method: 'PATCH',
    body: JSON.stringify({ targetParentId }),
  })
}

export function moveDriveItems(fileIds, targetParentId) {
  return request('/file/move', {
    method: 'PATCH',
    body: JSON.stringify({ fileIds, targetParentId }),
  })
}

export function restoreDriveItems(fileIds) {
  return request('/file/restore', {
    method: 'PATCH',
    body: JSON.stringify({ fileIds }),
  })
}

export function fetchDriveSharedReceived() {
  return request('/file/share/shared/list')
}

export function fetchDriveSharedSent() {
  return request('/file/share/sent/list')
}

export function searchDriveShareRecipients(query) {
  return request(buildUrl('/file/share/search-users', { q: query }).replace(API_BASE, ''))
}

export function fetchDriveShareInfo(fileId) {
  return request(`/file/share/${fileId}`)
}

export function shareDriveFiles(fileIds, recipientLoginId) {
  return request('/file/share', {
    method: 'POST',
    body: JSON.stringify({ fileIds, recipientLoginId }),
  })
}

export function cancelDriveShare(fileIds, recipientLoginId) {
  return request('/file/share/cancel', {
    method: 'POST',
    body: JSON.stringify({ fileIds, recipientLoginId }),
  })
}

export function cancelAllDriveShares(fileIds) {
  return request('/file/share/cancel-all', {
    method: 'POST',
    body: JSON.stringify({ fileIds }),
  })
}

export function saveSharedDriveFile(fileId, parentId = null) {
  return request(`/file/share/shared/${fileId}/save`, {
    method: 'POST',
    body: JSON.stringify({ targetParentId: parentId }),
  })
}

export function fetchDriveAdminDashboard() {
  return request('/administrator/dashboard')
}

export function fetchDriveStorageAnalytics() {
  return request('/administrator/storage-analytics')
}

export function updateDriveUserStatus(userId, active) {
  return request(`/administrator/users/${userId}/status`, {
    method: 'PATCH',
    body: JSON.stringify({ active }),
  })
}

export function updateDriveStorageCapacity(providerCapacityBytes) {
  return request('/administrator/storage-capacity', {
    method: 'PATCH',
    body: JSON.stringify({ providerCapacityBytes }),
  })
}

export function fetchDriveProfileSettings() {
  return request('/feater/settings/me')
}

export function updateDriveProfileSettings(payload) {
  return request('/feater/settings/me', {
    method: 'PUT',
    body: JSON.stringify(payload),
  })
}

export function uploadDriveProfileImage(file) {
  const formData = new FormData()
  formData.append('image', file)
  return request('/feater/settings/me/profile-image', {
    method: 'POST',
    body: formData,
  })
}
