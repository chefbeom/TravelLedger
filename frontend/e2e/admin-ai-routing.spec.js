import { expect, test } from '@playwright/test'

async function mockAdmin(page) {
  const state = { hosts: 'localhost,127.0.0.1', enforce: true, enabled: true, connect: 3, read: 300, secrets: new Map(), saved: [],
    candidates: [{ presetKey: 'existing', title: '기존 서버', provider: 'openai', model: 'model-a', baseUrl: 'https://old.example.test', chatPath: '/v1/chat/completions', modelsPath: '/v1/models', temperature: 0.2, maxTokens: 4096 }],
    connections: { ledger: '', image: '', excel: '' }, errors: [] }
  page.on('pageerror', error => state.errors.push(error.message))
  await page.route(/^https?:\/\/(?!127\.0\.0\.1|localhost)/, route => route.abort())
  await page.route('**/api/**', route => {
    const req = route.request(), path = new URL(req.url()).pathname
    const ai = () => ({ enabled: state.enabled, provider: 'openai', model: 'model-a', allowedProviderHosts: state.hosts, enforceProviderUrlAllowlist: state.enforce, connectTimeoutSeconds: state.connect, readTimeoutSeconds: state.read, routingConfigured: true, candidateServers: state.candidates.map(candidate => ({ ...candidate, apiKeyConfigured: state.secrets.has(candidate.presetKey) })), featureConnections: state.connections, ledgerAnalysis: {}, imageAnalysis: {}, excelImport: {} })
    let json = []
    if (path === '/api/auth/me') json = { id: 1, loginId: 'admin', displayName: '관리자', admin: true, active: true }
    else if (path === '/api/auth/csrf') json = { token: 'fixture-csrf' }
    else if (path === '/api/admin/access/status') json = { verified: true }
    else if (path === '/api/admin/dashboard') json = { summary: {}, users: [], blockedIps: [], recentInvites: [] }
    else if (path === '/api/admin/registration-policy') json = { publicRegistrationEnabled: false }
    else if (path === '/api/admin/login-map-preview') json = { enabled: false }
    else if (path === '/api/admin/login-audit-logs') json = { content: [], page: 0, totalPages: 0 }
    else if (path === '/api/admin/ops-control/ai/preset') {
      const body = req.postDataJSON(); state.saved.push(body)
      const secret = body.openAiApiKey || body.lmStudioApiKey || body.ollamaApiKey
      if (secret) state.secrets.set(body.presetKey, secret)
      if (body.clearOpenAiApiKey || body.clearLmStudioApiKey || body.clearOllamaApiKey) state.secrets.delete(body.presetKey)
      state.hosts = body.allowedProviderHosts; state.enforce = body.enforceProviderUrlAllowlist; state.connect = body.connectTimeoutSeconds; state.read = body.readTimeoutSeconds; state.enabled = body.enabled
      json = { credentialConfigured: state.secrets.has(body.presetKey), allowedProviderHosts: state.hosts, enforceProviderUrlAllowlist: state.enforce, connectTimeoutSeconds: state.connect, readTimeoutSeconds: state.read, enabled: state.enabled }
    } else if (path === '/api/admin/ops-control/ai/routing') {
      const body = req.postDataJSON(); state.candidates = body.candidates; state.connections = body.featureConnections
      json = { ai: ai(), dataServer: {} }
    } else if (path === '/api/admin/ops-control') json = { ai: ai(), dataServer: {} }
    else if (path === '/api/record-shares/counts') json = { ledger: 0, travel: 0 }
    else if (path === '/api/dashboard') json = { quickStats: [], calendar: [], recentEntries: [] }
    return route.fulfill({ json })
  })
  await page.goto('/#admin')
  await page.getByRole('button', { name: '관리자', exact: true }).click()
  await page.locator('.admin-command-card', { hasText: 'AI 및 서버 제어판' }).getByRole('button', { name: '열기', exact: true }).click()
  await page.getByRole('button', { name: /서버 리스트 조회 및 서버 변경/ }).click()
  return state
}

test('new profile uses one key, saves shared hosts and routes functions visibly', async ({ page }, info) => {
  const state = await mockAdmin(page)
  const modal = page.locator('.admin-ops-modal__dialog')
  await expect(page.locator('.theme-switcher')).toBeHidden()
  await modal.getByRole('button', { name: '새 서버 등록' }).click()
  await modal.getByLabel('서버 이름').fill('신규 서버')
  await modal.getByLabel('제공자').selectOption('openai')
  await modal.getByLabel('서버 모델 명').fill('model-new')
  await modal.getByLabel('OpenAI API 주소').fill('https://new.example.test')
  await modal.getByRole('button', { name: '다음' }).click()
  await modal.getByLabel('OpenAI API key', { exact: true }).fill('fixture-secret')
  await modal.getByRole('button', { name: '다음' }).click()
  await modal.getByLabel('허용 호스트').fill('localhost,127.0.0.1,new.example.test')
  await modal.getByRole('button', { name: '서버 저장', exact: true }).click()
  await expect(modal.getByRole('status')).toContainText('공통 보안 설정을 저장')
  expect(state.saved[0].allowedProviderHosts).toContain('new.example.test')
  const presets = await page.evaluate(() => JSON.parse(localStorage.getItem('travelledger:admin-ai-control-presets:v1')))
  const preset = presets.find(item => item.title === '신규 서버')
  expect(preset.key).toBe(state.saved[0].presetKey)
  expect(preset.credentialConfigured).toBe(true)
  expect(JSON.stringify(presets)).not.toContain('fixture-secret')
  await modal.getByRole('button', { name: /서버 리스트 조회 및 서버 변경/ }).click()
  await modal.getByLabel('후보로 등록할 서버').selectOption(preset.key)
  await modal.getByRole('button', { name: '후보 추가' }).click()
  await expect(modal.locator('.admin-ai-routing__candidate-card').filter({ hasText: '신규 서버' })).toContainText('API 키 암호화 저장됨')
  await modal.getByRole('button', { name: '가계부 AI 분석: 신규 서버' }).click()
  await modal.getByRole('button', { name: '이미지 분석 / OCR: 신규 서버' }).click()
  await modal.getByRole('button', { name: 'AI 엑셀 추출: 기존 서버' }).click()
  await expect(modal.getByRole('button', { name: '가계부 AI 분석: 신규 서버' })).toHaveAttribute('aria-pressed', 'true')
  const ledgerStatus = modal.locator('.admin-ai-routing__connection:visible').filter({ hasText: '신규 서버' }).first()
  await expect(ledgerStatus).toContainText('변경 대기')
  await modal.getByRole('button', { name: '기능별 연결 저장' }).click()
  await expect(modal.locator('.admin-ai-routing__connection:visible').filter({ hasText: '신규 서버' }).first()).toContainText('현재 적용')
  expect(state.connections).toEqual({ ledger: preset.key, image: preset.key, excel: 'existing' })
  await modal.screenshot({ path: info.outputPath('admin-ai-routing-matrix.png') })
  await page.reload()
  await page.getByRole('button', { name: '관리자', exact: true }).click()
  await page.locator('.admin-command-card', { hasText: 'AI 및 서버 제어판' }).getByRole('button', { name: '열기', exact: true }).click()
  await page.getByRole('button', { name: /서버 리스트 조회 및 서버 변경/ }).click()
  await expect(modal.getByRole('button', { name: '가계부 AI 분석: 신규 서버' })).toHaveAttribute('aria-pressed', 'true')
  await expect(modal.locator('.admin-ai-routing__candidate-card').filter({ hasText: '신규 서버' })).toContainText('API 키 암호화 저장됨')
  await modal.getByRole('button', { name: '편집' }).last().click()
  await modal.getByRole('button', { name: '다음' }).click()
  await modal.getByRole('button', { name: '다음' }).click()
  await expect(modal.getByLabel('허용 호스트')).toHaveValue('localhost,127.0.0.1,new.example.test')
  await page.screenshot({ path: info.outputPath('admin-ai-routing.png'), fullPage: true })
  expect(state.errors).toEqual([])
})
