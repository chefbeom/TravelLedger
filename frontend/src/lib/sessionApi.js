const API_BASE = '/api'
const CSRF_COOKIE_NAME = 'XSRF-TOKEN'
const CSRF_HEADER_NAME = 'X-XSRF-TOKEN'

let csrfRequestPromise = null

function getCookie(name) {
  return document.cookie
    .split(';')
    .map((item) => item.trim())
    .find((item) => item.startsWith(`${name}=`))
    ?.slice(name.length + 1) ?? ''
}

async function requestCsrfToken() {
  const response = await fetch(`${API_BASE}/auth/csrf`, {
    credentials: 'include',
  })

  if (!response.ok) {
    throw new Error('CSRF token request failed.')
  }

  let responseToken = ''
  try {
    const body = await response.json()
    responseToken = body.token || ''
  } catch {
    // The cookie remains the source of truth when the response has no JSON body.
  }

  const token = decodeURIComponent(getCookie(CSRF_COOKIE_NAME)) || responseToken
  if (!token) {
    throw new Error('CSRF token is missing.')
  }
  return token
}

async function ensureCsrfToken() {
  const currentToken = decodeURIComponent(getCookie(CSRF_COOKIE_NAME))
  if (currentToken) {
    return currentToken
  }

  csrfRequestPromise ??= requestCsrfToken()
  try {
    return await csrfRequestPromise
  } finally {
    csrfRequestPromise = null
  }
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
    let message = '\uC11C\uBC84 \uC694\uCCAD\uC744 \uCC98\uB9AC\uD558\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4.'
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
  return text ? JSON.parse(text) : null
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

export function fetchInvite(token) {
  return request(`/invites/${encodeURIComponent(token)}`)
}

export function acceptInvite(payload) {
  return request('/invites/accept', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function fetchNotifications(params = {}) {
  const url = buildUrl('/notifications', params)
  return request(url.slice(API_BASE.length) || '/notifications')
}
