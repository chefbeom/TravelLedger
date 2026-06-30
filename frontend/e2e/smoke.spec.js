import { expect, test } from '@playwright/test'

const USER_ENV = ['E2E_USER_LOGIN_ID', 'E2E_USER_PASSWORD']
const SECOND_USER_ENV = ['E2E_SECOND_USER_LOGIN_ID', 'E2E_SECOND_USER_PASSWORD']
const ADMIN_ENV = ['E2E_ADMIN_LOGIN_ID', 'E2E_ADMIN_PASSWORD']

const roleEnvPrefix = {
  user: 'E2E_USER',
  secondUser: 'E2E_SECOND_USER',
  admin: 'E2E_ADMIN',
}

const dedicatedFlowNames = new Set(['Login and session', 'Notification center'])

const flowEvidence = {
  'Login and session': {
    acceptance: 'CSRF bootstrap, API login, refresh persistence, /api/auth/me, logout, and logged-out denial are exercised.',
    nextAutomation: 'Add visible login form assertions once selectors and localized labels are stable.',
  },
  'Ledger entry create/edit/delete': {
    acceptance: 'Create, edit, calendar/statistics visibility, delete/trash path, and owner isolation must pass with disposable ledger data.',
    nextAutomation: 'Add stable selectors for entry fields, calendar day rows, statistics result rows, and delete confirmation.',
  },
  'Excel import preview and confirm': {
    acceptance: 'Valid spreadsheet preview, explicit confirm, visible imported rows, and invalid file rejection must pass.',
    nextAutomation: 'Add a tiny deterministic spreadsheet fixture and upload/confirm assertions.',
  },
  'OCR confirm-save': {
    acceptance: 'Stubbed receipt upload, visible suggestions, explicit save, and no ledger mutation before approval must pass.',
    nextAutomation: 'Add deterministic receipt image fixtures for success, timeout, and provider failure.',
  },
  'Travel photo upload': {
    acceptance: 'Travel plan selection, valid photo upload, visible media result, and invalid image recovery must pass.',
    nextAutomation: 'Add tiny JPEG and malformed-image fixtures, then assert no broken media record remains visible.',
  },
  'CalenDrive share': {
    acceptance: 'User A upload/share, User B visibility, revoked/expired public link feedback, and User C denial must pass.',
    nextAutomation: 'Use two authenticated contexts plus an unauthenticated or third-user context for share/revoke assertions.',
  },
  'Admin backup action': {
    acceptance: 'Non-admin denied, unverified admin denied, verified admin allowed, and cancel path safe must pass.',
    nextAutomation: 'Prefer a mocked backup endpoint for UI automation and keep real backup rehearsal in backend runbooks.',
  },
  'AI analysis advisory': {
    acceptance: 'Advisory copy, provider failure UI, schema-safe response rendering, and no direct ledger mutation must pass.',
    nextAutomation: 'Add stubbed AI success, timeout, schema failure, and prompt-injection fixture responses.',
  },
  'Notification center': {
    acceptance: 'Owner-scoped notification API shape, visible center UI, unread count, filters, and read-all affordance must pass.',
    nextAutomation: 'Seed cross-user notifications and assert owner isolation plus read/read-all state transitions.',
  },
}
const p0Flows = [
  {
    name: 'Login and session',
    route: '/',
    role: 'user',
    env: USER_ENV,
    risk: 'Authentication, CSRF bootstrap, and session isolation.',
  },
  {
    name: 'Ledger entry create/edit/delete',
    route: '/#/household',
    role: 'user',
    env: [...USER_ENV, 'E2E_LEDGER_SMOKE_READY'],
    mutating: true,
    risk: 'Core ledger integrity and owner scoping.',
  },
  {
    name: 'Excel import preview and confirm',
    route: '/#/household',
    role: 'user',
    env: [...USER_ENV, 'E2E_EXCEL_IMPORT_SMOKE_READY'],
    mutating: true,
    risk: 'Bulk import correctness and file handling.',
  },
  {
    name: 'OCR confirm-save',
    route: '/#/household',
    role: 'user',
    env: [...USER_ENV, 'E2E_OCR_STUB_READY'],
    mutating: true,
    providerMode: 'stubbed',
    risk: 'OCR safety and user-confirmed mutations.',
  },
  {
    name: 'Travel photo upload',
    route: '/#/travel',
    role: 'user',
    env: [...USER_ENV, 'E2E_TRAVEL_MEDIA_SMOKE_READY'],
    mutating: true,
    risk: 'Travel media upload and image failure isolation.',
  },
  {
    name: 'CalenDrive share',
    route: '/#/drive',
    role: 'user',
    env: [...USER_ENV, ...SECOND_USER_ENV, 'E2E_DRIVE_SHARE_SMOKE_READY'],
    mutating: true,
    risk: 'Drive ownership, share grants, and public link safety.',
  },
  {
    name: 'Admin backup action',
    route: '/#/admin',
    role: 'admin',
    env: [...ADMIN_ENV, 'E2E_ADMIN_BACKUP_SMOKE_READY'],
    mutating: true,
    risk: 'Admin authorization and destructive-operation guardrails.',
  },
  {
    name: 'AI analysis advisory',
    route: '/#/household',
    role: 'user',
    env: [...USER_ENV, 'E2E_AI_STUB_READY'],
    providerMode: 'stubbed',
    risk: 'AI advisory wording, failure handling, and no autonomous mutations.',
  },
  {
    name: 'Notification center',
    route: '/#/notifications',
    role: 'user',
    env: [...USER_ENV, 'E2E_NOTIFICATION_SMOKE_READY'],
    risk: 'Owner-scoped notification visibility and unread/read-all behavior.',
  },
]

function env(name) {
  return (process.env[name] || '').trim()
}

function unique(values) {
  return [...new Set(values)]
}

function requireEnv(names) {
  const missing = unique(names).filter((name) => !env(name))
  test.skip(
    missing.length > 0,
    `Missing E2E environment variables: ${missing.join(', ')}. See docs/e2e_smoke_checklist.md.`,
  )
}

function requireFlag(name, expected = '1') {
  const actual = env(name)
  test.skip(
    actual !== expected,
    `${name}=${expected} is required for this smoke path; current value is ${actual || '<unset>'}.`,
  )
}

function loginPayloadFor(role) {
  const prefix = roleEnvPrefix[role]
  const payload = {
    loginId: env(`${prefix}_LOGIN_ID`),
    password: env(`${prefix}_PASSWORD`),
    rememberDevice: true,
  }
  const secondaryPin = env(`${prefix}_SECONDARY_PIN`)
  if (secondaryPin) {
    payload.secondaryPin = secondaryPin
  }
  return payload
}

async function signIn(page, role = 'user') {
  const csrfResponse = await page.request.get('/api/auth/csrf')
  expect(csrfResponse.ok(), 'CSRF bootstrap should succeed before login.').toBeTruthy()

  const loginResponse = await page.request.post('/api/auth/login', {
    data: loginPayloadFor(role),
  })
  expect(loginResponse.ok(), `${role} login should succeed.`).toBeTruthy()

  const meResponse = await page.request.get('/api/auth/me')
  expect(meResponse.ok(), `${role} session should be visible through /api/auth/me.`).toBeTruthy()
}

function annotateFlow(testInfo, flow) {
  const evidence = flowEvidence[flow.name]
  annotateFlow(testInfo, flow)
  testInfo.annotations.push({ type: 'acceptance-criteria', description: evidence.acceptance })
  testInfo.annotations.push({ type: 'next-automation', description: evidence.nextAutomation })
}
async function expectRenderedApp(page) {
  const app = page.locator('#app')
  await expect(app).toBeVisible()
  await expect.poll(
    async () => (await app.innerText()).trim().length,
    { message: 'The Vue app shell should render visible text.' },
  ).toBeGreaterThan(10)
}
async function expectNotificationCenterWorkspace(page, expectedUnreadCount) {
  const workspace = page.locator('.notification-center')
  await expect(workspace.getByText('Notification center')).toBeVisible()
  await expect(workspace.getByRole('heading', { name: 'Operations, AI, OCR, and sharing updates' })).toBeVisible()
  await expect(workspace.getByText('Review user-scoped events from AI analysis, OCR, backups, and shared files in one place.')).toBeVisible()
  await expect(workspace.getByRole('button', { name: /Unread only|Show all/ })).toBeVisible()
  await expect(workspace.getByRole('button', { name: 'Mark all read' })).toBeVisible()
  await expect(workspace.getByText(`${expectedUnreadCount} unread`)).toBeVisible()
}

test('P0 scenario inventory matches release checklist', () => {
  expect(p0Flows.map((flow) => flow.name)).toEqual([
    'Login and session',
    'Ledger entry create/edit/delete',
    'Excel import preview and confirm',
    'OCR confirm-save',
    'Travel photo upload',
    'CalenDrive share',
    'Admin backup action',
    'AI analysis advisory',
    'Notification center',
  ])
  for (const flow of p0Flows) {
    expect(flowEvidence[flow.name]?.acceptance).toBeTruthy()
    expect(flowEvidence[flow.name]?.nextAutomation).toBeTruthy()
  }
})

test('public app shell loads without authenticated fixtures', async ({ page }) => {
  const pageErrors = []
  page.on('pageerror', (error) => pageErrors.push(error.message))

  await page.goto('/')
  await expectRenderedApp(page)

  expect(pageErrors).toEqual([])
})

test('P0 Login and session smoke', async ({ page }, testInfo) => {
  const flow = p0Flows.find((candidate) => candidate.name === 'Login and session')
  requireEnv(USER_ENV)
  annotateFlow(testInfo, flow)

  await signIn(page, 'user')
  await page.goto('/')
  await expectRenderedApp(page)
  await page.reload()
  await expectRenderedApp(page)

  const meAfterRefresh = await page.request.get('/api/auth/me')
  expect(meAfterRefresh.ok(), 'Session should survive a page refresh.').toBeTruthy()

  const logoutResponse = await page.request.post('/api/auth/logout')
  expect(logoutResponse.ok(), 'Logout should succeed.').toBeTruthy()

  const meAfterLogout = await page.request.get('/api/auth/me')
  expect(meAfterLogout.ok(), 'Logged-out context must not expose the previous user.').toBeFalsy()
})


test('P1 Notification center API and UI smoke', async ({ page }, testInfo) => {
  const flow = p0Flows.find((candidate) => candidate.name === 'Notification center')
  requireEnv(flow.env)

  annotateFlow(testInfo, flow)
  testInfo.annotations.push({
    type: 'automation-stage',
    description: 'Verifies owner-scoped notification API shape plus the visible notification center heading, filters, read-all affordance, and unread count badge.',
  })

  await signIn(page, flow.role)

  const notificationsResponse = await page.request.get('/api/notifications?size=20')
  expect(notificationsResponse.ok(), 'Notification list API should be available to the signed-in user.').toBeTruthy()
  const notificationsPayload = await notificationsResponse.json()
  expect(Array.isArray(notificationsPayload.content), 'Notification response content should be an array.').toBeTruthy()
  expect(Number.isFinite(Number(notificationsPayload.unreadCount)), 'Notification response should include a numeric unreadCount.').toBeTruthy()
  expect(String(JSON.stringify(notificationsPayload))).not.toMatch(/api[_-]?key|access[_-]?token|presigned|public[_-]?token|rawPrompt|providerResponse/i)

  await page.goto(flow.route)
  await expectRenderedApp(page)
  await expectNotificationCenterWorkspace(page, Number(notificationsPayload.unreadCount || 0))
})
for (const flow of p0Flows.filter((candidate) => !dedicatedFlowNames.has(candidate.name))) {
  test(`P0 ${flow.name} fixture gate and workspace checkpoint`, async ({ page }, testInfo) => {
    requireEnv(flow.env)
    if (flow.mutating) {
      requireFlag('E2E_ALLOW_MUTATING_SMOKE')
    }
    if (flow.providerMode) {
      requireFlag('E2E_PROVIDER_MODE', flow.providerMode)
    }

    annotateFlow(testInfo, flow)
    testInfo.annotations.push({
      type: 'automation-stage',
      description: 'Phase 1 verifies fixture gates, authenticated context, and target workspace rendering. Add feature-specific assertions before treating this as full release evidence.',
    })

    await signIn(page, flow.role)
    await page.goto(flow.route)
    await expectRenderedApp(page)
  })
}
