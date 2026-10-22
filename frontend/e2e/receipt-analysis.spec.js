import { expect, test } from '@playwright/test'
import { createReceiptBatchRequestIds, parseReceiptBatchRequestId } from '../src/lib/receiptHistoryGroups.js'

const image = { name: 'same-name.png', mimeType: 'image/png', buffer: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j1ioAAAAASUVORK5CYII=', 'base64') }

async function setup(page, { entryCount = 1, holdFourth = false, initialHistories = [], landing = false,
  holdRequests = [], failRequests = [], holdFinalRefresh = 0, failRefreshOnce = false, holdInitialSummary = false, brokenImages = [],
  asyncResponses = false, holdHistoryIds = [], distinctResults = false, maxConcurrent = 6, failSettings = false, admin = false, failReruns = [] } = {}) {
  const policy = { maxConcurrent, fail: failSettings }
  const requests = [], reruns = [], histories = [...initialHistories], errors = [], reads = [], imageReads = []
  const requestGates = new Map(holdRequests.map(number => {
    let release
    const promise = new Promise(resolve => { release = resolve })
    return [number, { promise, release }]
  }))
  const historyGates = new Map(holdHistoryIds.map(id => {
    let release
    const promise = new Promise(resolve => { release = resolve })
    return [id, { promise, release }]
  }))
  let releaseRefresh, refreshFailed = false
  const finalRefresh = new Promise(resolve => { releaseRefresh = resolve })
  let releaseInitialSummary
  const initialSummary = new Promise(resolve => { releaseInitialSummary = resolve })
  let releaseFourth
  const fourth = new Promise(resolve => { releaseFourth = resolve })
  page.on('pageerror', error => errors.push(error.message))
  await page.route(/^https?:\/\/(?!127\.0\.0\.1|localhost)/, route => route.abort())
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (/\/image-analysis\/history\/\d+\/image$/.test(path)) {
      const id = Number(path.split('/').at(-2))
      imageReads.push(id)
      if (brokenImages.includes(id)) return route.fulfill({ status: 404, body: 'Image missing' })
      return route.fulfill({ contentType: 'image/svg+xml', body: `<svg xmlns="http://www.w3.org/2000/svg" width="300" height="420" viewBox="0 0 300 420"><rect width="300" height="420" fill="#f5f3e9"/><rect x="20" y="20" width="260" height="380" fill="white" stroke="#d1d5db"/><g font-family="sans-serif" fill="#285d49"><text x="45" y="70" font-size="24">Receipt ${id}</text><text x="45" y="105" font-size="14">2026-10-20 12:34</text><path d="M45 130h210M45 180h130M45 220h170M45 260h110M45 300h200" stroke="#94a3b8" stroke-width="4"/><text x="45" y="360" font-size="22">TOTAL 15,000</text></g></svg>` })
    }
    if (path.endsWith('/image-analysis/analyze')) {
      const number = requests.push(route.request().postDataBuffer().toString())
      const clientRequestId = /name="clientRequestId"\r\n\r\n([^\r]+)/.exec(requests.at(-1))?.[1]
      const fileName = /name="file"; filename="([^"]+)"/.exec(requests.at(-1))?.[1] || image.name
      if (holdFourth && number === 4) await fourth
      if (requestGates.has(number)) await requestGates.get(number).promise
      if (holdFourth && number === 5) return route.fulfill({ status: 500, json: { message: '검증용 이미지 실패' } })
      if (failRequests.includes(number)) {
        histories.push({ id: number, clientRequestId, status: 'FAILED', createdAt: '2026-10-21T12:00:00', fileName, imageAvailable: true, errorMessage: '검증용 이미지 실패' })
        return route.fulfill({ status: 500, json: { message: '검증용 이미지 실패' } })
      }
      const result = {
        analysisId: number, clientRequestId, analysisStatus: 'COMPLETED', documentType: 'AUTO', rawText: '검증용 추출문',
        suggestedEntries: Array.from({ length: entryCount }, (_, index) => ({ title: distinctResults ? `결과 ${fileName}` : `거래 ${index + 1}`, amount: 1000 + index, entryType: 'EXPENSE', entryDate: '2026-10-21', entryTime: '12:00', categoryGroupId: 101, categoryDetailId: 102, paymentMethodId: 103 })),
      }
      histories.push({ id: number, clientRequestId, status: 'COMPLETED', createdAt: '2026-10-21T12:00:00', fileName, imageAvailable: true, result })
      return route.fulfill({ json: asyncResponses || number === 3 ? { analysisId: number, analysisStatus: 'PROCESSING' } : result })
    }
    if (/\/image-analysis\/history\/client\/[^/]+$/.test(path)) {
      const clientRequestId = decodeURIComponent(path.split('/').at(-1))
      const history = histories.find(item => item.clientRequestId === clientRequestId)
      return history ? route.fulfill({ json: history }) : route.fulfill({ status: 404, json: { message: '기록 없음' } })
    }
    if (/\/image-analysis\/history\/\d+\/rerun$/.test(path)) {
      const id = Number(path.split('/').at(-2))
      const history = histories.find(item => item.id === id)
      const number = reruns.push({ id, body: route.request().postDataBuffer().toString() })
      if (!history) return route.fulfill({ status: 404, json: { message: '기록 없음' } })
      if (failReruns.includes(number)) {
        Object.assign(history, { status: 'FAILED', result: null, errorMessage: '재요청 실패' })
        return route.fulfill({ status: 500, json: { message: '재요청 실패' } })
      }
      const result = { analysisId: id, clientRequestId: history.clientRequestId, analysisStatus: 'COMPLETED', documentType: 'AUTO', rawText: '재요청 추출문',
        suggestedEntries: [{ title: distinctResults ? `결과 ${history.fileName}` : '재요청 결과', amount: 15000, entryType: 'EXPENSE', entryDate: '2026-10-21', categoryGroupId: 101, categoryDetailId: 102, paymentMethodId: 103 }] }
      Object.assign(history, { status: 'COMPLETED', result, errorMessage: null })
      return route.fulfill({ json: { analysisId: id, clientRequestId: history.clientRequestId, analysisStatus: 'PROCESSING' } })
    }
    let json = []
    if (path === '/api/auth/me') json = { id: 2, loginId: 'fixture', displayName: '검증용 사용자', active: true, admin }
    else if (path.endsWith('/image-analysis/settings') || path === '/api/admin/ops-control/image-analysis') {
      if (policy.fail) return route.fulfill({ status: 503, json: { message: '설정 조회 실패' } })
      if (route.request().method() === 'PATCH') policy.maxConcurrent = route.request().postDataJSON().maxConcurrent
      json = { maxConcurrent: policy.maxConcurrent, minAllowed: 1, maxAllowed: 20 }
    }
    else if (path === '/api/admin/access/status') json = { verified: true }
    else if (path === '/api/admin/dashboard') json = { summary: {}, users: [], blockedIps: [], recentLoginLogs: [] }
    else if (path === '/api/admin/ops-control') json = { ai: { candidateServers: [], featureConnections: {} }, dataServer: {} }
    else if (path === '/api/auth/csrf') json = { token: 'fixture-csrf' }
    else if (path.startsWith('/api/categories')) json = [{ id: 101, name: '식비', entryType: 'EXPENSE', active: true, details: [{ id: 102, name: '외식', active: true }] }]
    else if (path.startsWith('/api/payment-methods')) json = [{ id: 103, name: '검증용 카드', active: true }]
    else if (path === '/api/entries/date-range') json = { minDate: '2026-10-01', maxDate: '2026-10-21' }
    else if (path === '/api/dashboard') json = { anchorDate: '2026-10-21', quickStats: [], calendar: [], expenseBreakdown: [], paymentBreakdown: [], monthlyComparison: [], recentEntries: [] }
    else if (path === '/api/account/preferences/household-aggregates') json = { widgets: [] }
    else if (path === '/api/record-shares/counts') json = { ledger: 0, travel: 0 }
    else if (path.endsWith('/image-analysis/history/review-summary')) {
      reads.push({ type: 'summary', afterRequests: requests.length })
      if (holdInitialSummary && !requests.length) {
        await initialSummary
        return route.fulfill({ json: { requests: [] } })
      }
      if (holdFinalRefresh && histories.length >= holdFinalRefresh) await finalRefresh
      if (failRefreshOnce && requests.length && !refreshFailed) {
        refreshFailed = true
        return route.fulfill({ status: 500, json: { message: '검증용 새로고침 실패' } })
      }
      json = { requests: histories.filter(item => item.status === 'COMPLETED').map(item => ({ analysisId: item.id, entryCount: item.result?.suggestedEntries?.length || 0, approvedEntryIndexes: item.result?.approvedEntryIndexes || [] })) }
    }
    else if (path.endsWith('/image-analysis/history')) {
      reads.push({ type: 'history', afterRequests: requests.length })
      if (holdFinalRefresh && histories.length >= holdFinalRefresh) await finalRefresh
      const number = Number(new URL(route.request().url()).searchParams.get('page') || 0)
      json = { content: histories.slice(number * 8, number * 8 + 8), number, totalPages: Math.max(1, Math.ceil(histories.length / 8)), totalElements: histories.length }
    }
    else if (/\/image-analysis\/history\/\d+\/cancel$/.test(path)) {
      const history = histories.find(item => item.id === Number(path.split('/').at(-2)))
      history.status = 'CANCELLED'
      json = history
    }
    else if (/\/image-analysis\/history\/\d+$/.test(path)) {
      const index = histories.findIndex(item => item.id === Number(path.split('/').at(-1)))
      if (route.request().method() === 'DELETE') {
        histories.splice(index, 1)
        json = { deleted: true }
      } else {
        const id = Number(path.split('/').at(-1))
        if (historyGates.has(id)) await historyGates.get(id).promise
        json = histories[index]
      }
    }
    return route.fulfill({ json })
  })
  await page.goto('/#household')
  await page.getByRole('button', { name: '달력 가계부', exact: true }).click()
  await page.getByRole('button', { name: '분석 모달 열기', exact: true }).click()
  const modal = page.getByRole('dialog', { name: '이미지 분석 및 검수', exact: true })
  if (!landing) await modal.getByRole('button', { name: '이미지 분석하기', exact: true }).click()
  return { modal, requests, reruns, histories, policy, errors, reads, imageReads, releaseFourth, releaseRefresh, releaseInitialSummary,
    releaseRequest: number => requestGates.get(number)?.release(), releaseHistory: id => historyGates.get(id)?.release() }
}

test('ten image requests show zero, incremental and full progress then refresh without reloading the page', async ({ page }, testInfo) => {
  const { modal, requests, errors, reads, releaseRequest, releaseRefresh } = await setup(page, { holdRequests: Array.from({ length: 10 }, (_, index) => index + 1), holdFinalRefresh: 10 })
  let navigations = 0
  page.on('framenavigated', frame => { if (frame === page.mainFrame()) navigations++ })
  const progress = modal.getByRole('progressbar', { name: '이미지 분석 요청 처리 진행률' })
  const panel = modal.locator('.receipt-ocr-batch-progress')
  try {
    await modal.locator('input[type=file][multiple]').setInputFiles(Array.from({ length: 10 }, () => image))
    await modal.getByRole('button', { name: '분석 요청하기', exact: true }).click()
    await expect.poll(() => requests.length).toBe(6)
    await expect(progress).toHaveAttribute('max', '10')
    await expect(progress).toHaveAttribute('value', '0')
    releaseRequest(1)
    await expect.poll(() => requests.length).toBe(7)
    await expect(progress).toHaveAttribute('value', '1')
    await expect(panel).toContainText('완료 1건 · 실패 0건 · 취소 0건 · 남은 9건')
    await modal.screenshot({ path: testInfo.outputPath('incremental-progress.png') })
    await modal.getByRole('button', { name: '분석 내용 확인 및 불러오기', exact: true }).click()
    await expect(progress).toBeVisible()
    for (let number = 2; number <= 10; number++) releaseRequest(number)
    await expect(progress).toHaveAttribute('value', '10')
    await expect(panel).toContainText('새로고침하고 있습니다')
    for (const type of ['history', 'summary']) expect(reads.some(read => read.type === type && read.afterRequests === 10)).toBe(true)
    releaseRefresh()
    await expect(panel).toContainText('새로고침했습니다')
    await expect(modal.locator('.receipt-ocr-review-badge')).toHaveCount(0)
    await expect(modal.locator('.receipt-ocr-history__item')).toHaveCount(8)
    await modal.getByRole('button', { name: '이미지 분석하기', exact: true }).click()
    await expect(modal.locator('.receipt-ocr-review-card')).toHaveCount(10)
    for (const theme of ['default', 'toss']) {
      await page.evaluate(theme => document.documentElement.dataset.theme = theme, theme)
      const bounds = await panel.boundingBox(), viewport = page.viewportSize()
      expect(bounds.x).toBeGreaterThanOrEqual(0)
      expect(bounds.x + bounds.width).toBeLessThanOrEqual(viewport.width + 1)
      await modal.screenshot({ path: testInfo.outputPath(`${theme}-completed-progress.png`) })
    }
    expect(navigations).toBe(0)
    expect(errors).toEqual([])
  } finally { for (let number = 1; number <= 10; number++) releaseRequest(number); releaseRefresh() }
})

test('failed batches still finish and automatically refresh their history', async ({ page }) => {
  const { modal, requests, reads, errors } = await setup(page, { failRequests: [1, 2] })
  await modal.locator('input[type=file][multiple]').setInputFiles([image, image])
  await modal.getByRole('button', { name: '분석 요청하기', exact: true }).click()
  const panel = modal.locator('.receipt-ocr-batch-progress')
  await expect(panel).toContainText('완료 0건 · 실패 2건 · 취소 0건 · 남은 0건')
  await expect(panel).toContainText('새로고침했습니다')
  for (const type of ['history', 'summary']) expect(reads.some(read => read.type === type && read.afterRequests === 2)).toBe(true)
  await modal.getByRole('button', { name: '분석 내용 확인 및 불러오기', exact: true }).click()
  await expect(modal.locator('.receipt-ocr-history__item')).toHaveCount(2)
  expect(requests).toHaveLength(2)
  expect(errors).toEqual([])
})

test('six jobs occupy their slots through async polling and a freed slot admits the next image', async ({ page }) => {
  const { modal, requests, errors, releaseHistory } = await setup(page, { asyncResponses: true, holdHistoryIds: [1, 2, 3, 4, 5, 6, 7, 8], distinctResults: true })
  try {
    const files = Array.from({ length: 8 }, (_, index) => ({ ...image, name: `photo-${index + 1}.png` }))
    await modal.locator('input[type=file][multiple]').setInputFiles(files)
    await expect(modal.locator('.receipt-ocr-concurrency')).toContainText('최대 동시 분석 6건')
    await modal.getByRole('button', { name: '분석 요청하기', exact: true }).click()
    await expect.poll(() => requests.length).toBe(6)
    await expect(modal.locator('.receipt-ocr-review-card__status', { hasText: '분석 중' })).toHaveCount(6)
    await expect(modal.locator('.receipt-ocr-review-card__status', { hasText: '대기 중' })).toHaveCount(2)
    await expect(modal.locator('.receipt-ocr-concurrency select')).toHaveCount(0)
    releaseHistory(6) // Finish a later job before the first five jobs.
    await expect.poll(() => requests.length).toBe(7)
    await expect(modal.locator('.receipt-ocr-batch-progress')).toContainText('요청 처리 1 / 8건')
    expect(requests).toHaveLength(7)
    for (let id = 1; id <= 8; id++) releaseHistory(id)
    await expect(modal.locator('.receipt-ocr-batch-progress')).toContainText('새로고침했습니다')
    await expect(modal.locator('.receipt-ocr-review-card')).toHaveCount(8)
    for (const file of files) {
      const card = modal.locator('.receipt-ocr-review-card').filter({ has: page.locator('.receipt-ocr-review-card__header strong', { hasText: file.name }) })
      await expect(card.getByRole('textbox', { name: '제목', exact: true })).toHaveValue(`결과 ${file.name}`)
    }
    expect(requests.every(body => (body.match(/name="file";/g) || []).length === 1)).toBe(true)
    expect(errors).toEqual([])
  } finally { for (let id = 1; id <= 8; id++) releaseHistory(id) }
})

test('retrying failed files reuses history ids and the original batch while preserving successful edits', async ({ page }, testInfo) => {
  const { modal, requests, reruns, histories, errors } = await setup(page, { failRequests: [2, 5], distinctResults: true })
  const files = Array.from({ length: 6 }, (_, index) => ({ ...image, name: `retry-${index + 1}.png` }))
  await modal.locator('input[type=file][multiple]').setInputFiles(files)
  await modal.getByRole('button', { name: '분석 요청하기', exact: true }).click()
  const progress = modal.locator('.receipt-ocr-batch-progress')
  await expect(progress).toContainText('완료 4건 · 실패 2건')
  await expect(progress).toContainText('새로고침했습니다')
  const successful = modal.locator('.receipt-ocr-review-card').filter({ has: page.locator('.receipt-ocr-review-card__status', { hasText: '완료' }) }).first()
  const successfulName = await successful.locator('.receipt-ocr-review-card__header strong').textContent()
  await successful.getByRole('textbox', { name: '제목', exact: true }).fill('성공 결과에서 수정한 내용')
  const failedNames = await modal.locator('.receipt-ocr-review-card').filter({ has: page.locator('.receipt-ocr-review-card__status', { hasText: '실패' }) }).locator('.receipt-ocr-review-card__header strong').allTextContents()
  const oldIds = histories.map(history => history.clientRequestId)
  await modal.getByRole('button', { name: '실패 건만 재시도 (2건)', exact: true }).click()
  await expect(progress).toContainText('요청 처리 2 / 2건')
  await expect(progress).toContainText('새로고침했습니다')
  expect(requests).toHaveLength(6)
  expect(reruns.map(rerun => rerun.id).sort()).toEqual([2, 5])
  expect(reruns.map(rerun => histories.find(history => history.id === rerun.id).fileName).sort()).toEqual(failedNames.sort())
  expect(histories).toHaveLength(6)
  expect(histories.map(history => history.clientRequestId)).toEqual(oldIds)
  await expect(modal.locator('.receipt-ocr-review-card')).toHaveCount(6)
  await expect(modal.locator('.receipt-ocr-review-card__status', { hasText: '실패' })).toHaveCount(0)
  const preserved = modal.locator('.receipt-ocr-review-card').filter({ has: page.locator('.receipt-ocr-review-card__header strong', { hasText: successfulName }) })
  await expect(preserved.getByRole('textbox', { name: '제목', exact: true })).toHaveValue('성공 결과에서 수정한 내용')
  await modal.screenshot({ path: testInfo.outputPath('parallel-retry-complete.png') })
  expect(errors).toEqual([])
})

test('an administrator concurrency of two is respected even when more images are selected', async ({ page }) => {
  const { modal, requests, errors, releaseRequest } = await setup(page, { holdRequests: [1, 2, 3, 4], maxConcurrent: 2 })
  try {
    await expect(modal.locator('.receipt-ocr-concurrency')).toContainText('최대 동시 분석 2건')
    await modal.locator('input[type=file][multiple]').setInputFiles([image, image, image, image])
    await modal.getByRole('button', { name: '분석 요청하기', exact: true }).click()
    await expect.poll(() => requests.length).toBe(2)
    await expect(modal.locator('.receipt-ocr-review-card__status', { hasText: '분석 중' })).toHaveCount(2)
    await expect(modal.locator('.receipt-ocr-review-card__status', { hasText: '대기 중' })).toHaveCount(2)
    for (let id = 1; id <= 4; id++) releaseRequest(id)
    await expect(modal.locator('.receipt-ocr-batch-progress')).toContainText('새로고침했습니다')
    expect(requests).toHaveLength(4)
    expect(errors).toEqual([])
  } finally { for (let id = 1; id <= 4; id++) releaseRequest(id) }
})

test('rerunning two stored failures from detail replaces their rows inside the original three-image batch', async ({ page }, testInfo) => {
  const ids = createReceiptBatchRequestIds(3, { timestamp: Date.parse('2026-10-20T11:34:12Z'), token: 'retry-detail' })
  const initialHistories = ids.map((clientRequestId, index) => ({ id: index + 1, clientRequestId,
    createdAt: '2026-10-20T11:34:12', fileName: `original-${index + 1}.png`, imageAvailable: true,
    status: index ? 'FAILED' : 'COMPLETED', errorMessage: index ? '원래 실패' : null,
    result: index ? null : { suggestedEntries: [{ title: '먼저 성공한 결과', amount: 1000 }] } }))
  const originalIdentities = initialHistories.map(({ id, clientRequestId, createdAt }) => ({ id, clientRequestId, createdAt }))
  const { modal, requests, reruns, histories, errors } = await setup(page, { initialHistories, landing: true })
  await modal.getByRole('button', { name: '분석 내용 확인 및 불러오기', exact: true }).click()
  for (const id of [2, 3]) {
    await modal.locator('.receipt-ocr-history__item', { hasText: `original-${id}.png` }).getByRole('button', { name: '내용 보기', exact: true }).click()
    await modal.getByRole('button', { name: '재요청 검수', exact: true }).click()
    await expect(modal.locator('.receipt-ocr-batch-progress')).toContainText('새로고침했습니다')
    await expect(modal.locator('.receipt-ocr-history-detail__header')).toContainText('완료')
    await modal.getByRole('button', { name: '< 뒤로가기', exact: true }).click()
    await expect(modal.locator('.receipt-ocr-history__item')).toHaveCount(3)
    await expect(modal.locator('.receipt-ocr-history__request-group')).toHaveCount(1)
    await expect(modal.locator('.receipt-ocr-history__request-group')).toHaveAttribute('data-request-count', '3')
  }
  expect(requests).toHaveLength(0)
  expect(reruns.map(rerun => rerun.id)).toEqual([2, 3])
  expect(histories.map(({ id, clientRequestId, createdAt }) => ({ id, clientRequestId, createdAt }))).toEqual(originalIdentities)
  expect(histories.every(history => history.status === 'COMPLETED')).toBe(true)
  await modal.screenshot({ path: testInfo.outputPath('same-three-image-batch-after-rerun.png') })
  expect(errors).toEqual([])
})

test('another failed stored rerun can be retried again without creating histories', async ({ page }) => {
  const { modal, requests, reruns, histories, errors } = await setup(page, { landing: true, failReruns: [1],
    initialHistories: [{ id: 41, status: 'FAILED', imageAvailable: true, fileName: 'repeat.png', clientRequestId: 'old-request' }] })
  for (let attempt = 0; attempt < 2; attempt++) {
    await modal.getByRole('button', { name: '분석 내용 확인 및 불러오기', exact: true }).click()
    await modal.locator('.receipt-ocr-history__item').getByRole('button', { name: '내용 보기', exact: true }).click()
    await modal.getByRole('button', { name: '재요청 검수', exact: true }).click()
    await expect(modal.locator('.receipt-ocr-batch-progress')).toContainText('새로고침했습니다')
  }
  expect(requests).toHaveLength(0)
  expect(reruns.map(rerun => rerun.id)).toEqual([41, 41])
  expect(histories).toHaveLength(1)
  expect(histories[0].status).toBe('COMPLETED')
  expect(errors).toEqual([])
})

test('retry finds a failed request beyond the first history page by its original client id', async ({ page }) => {
  const { modal, requests, reruns, histories, errors } = await setup(page, { failRequests: [10], maxConcurrent: 3 })
  await modal.locator('input[type=file][multiple]').setInputFiles(Array.from({ length: 10 }, (_, index) => ({ ...image, name: `paged-${index + 1}.png` })))
  await modal.getByRole('button', { name: '분석 요청하기', exact: true }).click()
  await expect(modal.locator('.receipt-ocr-batch-progress')).toContainText('새로고침했습니다')
  await modal.getByRole('button', { name: '실패 건만 재시도 (1건)', exact: true }).click()
  await expect(modal.locator('.receipt-ocr-batch-progress')).toContainText('완료 1건 · 실패 0건')
  await expect(modal.locator('.receipt-ocr-batch-progress')).toContainText('새로고침했습니다')
  expect(requests).toHaveLength(10)
  expect(reruns.map(rerun => rerun.id)).toEqual([10])
  expect(histories).toHaveLength(10)
  expect(errors).toEqual([])
})

test('default three is server controlled and a changed administrator policy applies to the next batch', async ({ page }) => {
  const { modal, requests, policy, errors, releaseRequest } = await setup(page, {
    maxConcurrent: 3, holdRequests: [1, 2, 3, 4, 5, 6, 7, 8, 9],
  })
  try {
    await expect(modal.locator('.receipt-ocr-concurrency')).toContainText('최대 동시 분석 3건')
    await expect(modal.locator('.receipt-ocr-concurrency select')).toHaveCount(0)
    await modal.locator('input[type=file][multiple]').setInputFiles(Array.from({ length: 6 }, () => image))
    await modal.getByRole('button', { name: '분석 요청하기', exact: true }).click()
    await expect.poll(() => requests.length).toBe(3)
    await expect(modal.locator('.receipt-ocr-review-card__status', { hasText: '대기 중' })).toHaveCount(3)
    releaseRequest(2)
    await expect.poll(() => requests.length).toBe(4)
    for (let id = 1; id <= 6; id++) releaseRequest(id)
    await expect(modal.locator('.receipt-ocr-batch-progress')).toContainText('새로고침했습니다')
    policy.maxConcurrent = 1
    await modal.locator('input[type=file][multiple]').setInputFiles([image, image, image])
    await modal.getByRole('button', { name: '분석 요청하기', exact: true }).click()
    await expect.poll(() => requests.length).toBe(7)
    await expect(modal.locator('.receipt-ocr-concurrency')).toContainText('최대 동시 분석 1건')
    await expect(modal.locator('.receipt-ocr-review-card__status', { hasText: '대기 중' })).toHaveCount(2)
    for (let id = 7; id <= 9; id++) releaseRequest(id)
    await expect(modal.locator('.receipt-ocr-batch-progress')).toContainText('새로고침했습니다')
    expect(errors).toEqual([])
  } finally { for (let id = 1; id <= 9; id++) releaseRequest(id) }
})

test('a failed server policy prevents inference and retry preserves the original image prompt', async ({ page }) => {
  const { modal, requests, policy, errors } = await setup(page, { maxConcurrent: 3, failSettings: true })
  await expect(modal.getByRole('alert')).toContainText('설정 조회 실패')
  await modal.locator('input[type=file][multiple]').setInputFiles([image, image])
  const first = modal.locator('.receipt-ocr-review-card').first()
  await first.getByRole('checkbox', { name: '이 이미지 요청사항', exact: true }).check()
  await first.locator('textarea').fill('첫 이미지 전용 요청사항 유지')
  await modal.getByRole('button', { name: '분석 요청하기', exact: true }).click()
  await expect(modal.locator('.receipt-ocr-review-card__status', { hasText: '실패' })).toHaveCount(2)
  expect(requests).toHaveLength(0)
  policy.fail = false
  await modal.getByRole('button', { name: '실패 건만 재시도 (2건)', exact: true }).click()
  await expect(modal.locator('.receipt-ocr-batch-progress')).toContainText('새로고침했습니다')
  expect(requests).toHaveLength(2)
  expect(requests.some(request => request.includes('첫 이미지 전용 요청사항 유지'))).toBe(true)
  expect(errors).toEqual([])
})

test('cancelling one active image aborts only its polling and leaves other results intact', async ({ page }) => {
  const { modal, requests, errors, releaseHistory } = await setup(page, { asyncResponses: true, holdHistoryIds: [1, 2, 3] })
  try {
    await modal.locator('input[type=file][multiple]').setInputFiles([image, image, image])
    await modal.getByRole('button', { name: '분석 요청하기', exact: true }).click()
    await expect.poll(() => requests.length).toBe(3)
    await expect(modal.locator('.receipt-ocr-review-card__status', { hasText: '분석 중' })).toHaveCount(3)
    await modal.locator('.receipt-ocr-review-card').first().getByRole('button', { name: '제거', exact: true }).click()
    const progress = modal.locator('.receipt-ocr-batch-progress')
    await expect(progress).toContainText('완료 0건 · 실패 0건 · 취소 1건 · 남은 2건')
    await expect(modal.locator('.receipt-ocr-review-card__status', { hasText: '분석 중' })).toHaveCount(2)
    for (let id = 1; id <= 3; id++) releaseHistory(id)
    await expect(progress).toContainText('완료 2건 · 실패 0건 · 취소 1건 · 남은 0건')
    await expect(progress).toContainText('새로고침했습니다')
    await expect(modal.locator('.receipt-ocr-review-card')).toHaveCount(2)
    expect(requests).toHaveLength(3)
    expect(errors).toEqual([])
  } finally { for (let id = 1; id <= 3; id++) releaseHistory(id) }
})

test('removing a queued image counts cancellation without changing the original batch total', async ({ page }) => {
  const { modal, requests, errors, releaseRequest } = await setup(page, { holdRequests: [1, 2, 3, 4, 5, 6, 7, 8] })
  try {
    await modal.locator('input[type=file][multiple]').setInputFiles(Array.from({ length: 8 }, () => image))
    await modal.getByRole('button', { name: '분석 요청하기', exact: true }).click()
    await expect.poll(() => requests.length).toBe(6)
    await modal.locator('.receipt-ocr-review-card').nth(6).getByRole('button', { name: '제거', exact: true }).click()
    const panel = modal.locator('.receipt-ocr-batch-progress')
    await expect(panel).toContainText('요청 처리 1 / 8건')
    await expect(panel).toContainText('완료 0건 · 실패 0건 · 취소 1건 · 남은 7건')
    for (let number = 1; number <= 8; number++) releaseRequest(number)
    await expect(panel).toContainText('완료 7건 · 실패 0건 · 취소 1건 · 남은 0건')
    await expect(panel).toContainText('새로고침했습니다')
    expect(requests).toHaveLength(7)
    expect(errors).toEqual([])
  } finally { for (let number = 1; number <= 8; number++) releaseRequest(number) }
})

test('a refresh failure preserves finished results and retries only the reads', async ({ page }) => {
  const { modal, requests, errors } = await setup(page, { failRefreshOnce: true })
  await modal.locator('input[type=file][multiple]').setInputFiles(image)
  await modal.getByRole('button', { name: '분석 요청하기', exact: true }).click()
  const panel = modal.locator('.receipt-ocr-batch-progress')
  await expect(panel).toContainText('새로고침에 실패했습니다')
  await expect(modal.locator('.receipt-ocr-review-card')).toHaveCount(1)
  await panel.getByRole('button', { name: '새로고침 재시도', exact: true }).click()
  await expect(panel).toContainText('새로고침했습니다')
  await expect(panel.getByRole('progressbar')).toHaveAttribute('value', '1')
  expect(requests).toHaveLength(1)
  expect(errors).toEqual([])
})

test('a stale pending summary does not delay or replace the final automatic refresh', async ({ page }) => {
  const { modal, reads, errors, releaseInitialSummary } = await setup(page, { holdInitialSummary: true })
  try {
    await modal.locator('input[type=file][multiple]').setInputFiles(image)
    await modal.getByRole('button', { name: '분석 요청하기', exact: true }).click()
    await expect(modal.locator('.receipt-ocr-batch-progress')).toContainText('새로고침했습니다')
    expect(reads.some(read => read.type === 'summary' && read.afterRequests === 1)).toBe(true)
    releaseInitialSummary()
    await expect(modal.locator('.receipt-ocr-review-badge')).toHaveText('1')
    expect(errors).toEqual([])
  } finally { releaseInitialSummary() }
})

test('history nests original request groups under the date and labels split pages honestly', async ({ page }, testInfo) => {
  const initialHistories = []
  for (const [count, time, token] of [[5, '11:00', 'five'], [4, '10:00', 'four'], [1, '09:00', 'one']]) {
    const ids = createReceiptBatchRequestIds(count, { timestamp: Date.parse(`2026-10-20T${time}:00+09:00`), token })
    for (const clientRequestId of ids) initialHistories.push({ id: initialHistories.length + 1000, clientRequestId, createdAt: '2026-10-20T12:00:00', status: 'COMPLETED', fileName: 'same.png', result: { suggestedEntries: [] } })
  }
  const { modal, errors } = await setup(page, { initialHistories, landing: true })
  await modal.getByRole('button', { name: '분석 내용 확인 및 불러오기', exact: true }).click()
  const groups = modal.locator('.receipt-ocr-history__request-group')
  await expect(groups).toHaveCount(2)
  await expect(groups.nth(0)).toHaveAttribute('data-request-count', '5')
  await expect(groups.nth(1)).toHaveAttribute('data-request-count', '4')
  await expect(groups.nth(1)).toContainText('이 페이지에 3/4건 표시')
  for (const theme of ['default', 'toss']) {
    await page.evaluate(theme => document.documentElement.dataset.theme = theme, theme)
    await modal.screenshot({ path: testInfo.outputPath(`${theme}-request-groups.png`) })
  }
  await modal.getByRole('button', { name: '블록으로 보기', exact: true }).click()
  await expect(modal.locator('.receipt-history-tile')).toHaveCount(8)
  await expect(groups.nth(0)).toHaveAttribute('data-request-count', '5')
  await expect(groups.nth(1)).toContainText('이 페이지에 3/4건 표시')
  await modal.locator('.receipt-ocr-history__pagination').getByRole('button', { name: '다음', exact: true }).click()
  await expect(groups).toHaveCount(2)
  await expect(groups.nth(0)).toHaveAttribute('data-request-count', '4')
  await expect(groups.nth(1)).toHaveAttribute('data-request-count', '1')
  await expect(modal.locator('.receipt-history-tile')).toHaveCount(2)
  await modal.getByRole('button', { name: '리스트로 보기', exact: true }).click()
  await expect(modal.locator('.receipt-ocr-history__item')).toHaveCount(2)
  expect(errors).toEqual([])
})

test('history switches between timestamped lists and three image block sizes while preserving groups and preferences', async ({ page }, testInfo) => {
  const ids = createReceiptBatchRequestIds(3, { timestamp: Date.parse('2026-10-20T12:30:00+09:00'), token: 'blocks' })
  const initialHistories = ids.map((clientRequestId, index) => ({
    id: 201 + index, clientRequestId, createdAt: '2026-10-20T12:34:56', status: 'COMPLETED',
    fileName: `receipt-${index + 1}-a-long-filename-to-verify-ellipsis.png`, imageAvailable: true,
    summary: '리스트에서만 보이는 자세한 분석 요약',
    result: { analysisStatus: 'COMPLETED', suggestedEntries: [{ title: '블록에서 연 검수', amount: 15000, entryType: 'EXPENSE', entryDate: '2026-10-20' }] },
  }))
  const { modal, requests, imageReads, errors } = await setup(page, { initialHistories, landing: true })
  await modal.getByRole('button', { name: '분석 내용 확인 및 불러오기', exact: true }).click()
  await expect(modal.getByRole('button', { name: '리스트로 보기', exact: true })).toHaveAttribute('aria-pressed', 'true')
  await expect(modal.locator('.receipt-history-row__file time')).toHaveText(Array(3).fill('요청 12:34:56'))
  expect(imageReads).toHaveLength(0)
  await modal.getByRole('button', { name: '블록으로 보기', exact: true }).click()
  await expect(modal.locator('.receipt-history-tile')).toHaveCount(3)
  await expect(modal.locator('.receipt-ocr-history__date-group')).toHaveCount(1)
  await expect(modal.locator('.receipt-ocr-history__request-group')).toHaveAttribute('data-request-count', '3')
  await expect(modal.getByText('리스트에서만 보이는 자세한 분석 요약', { exact: true })).toHaveCount(0)
  const heights = []
  for (const [size, label] of [['large', '크게 보기'], ['medium', '보통 보기'], ['small', '작게 보기']]) {
    await modal.getByRole('button', { name: label, exact: true }).click()
    await expect(modal.locator(`.receipt-history-blocks--${size}`)).toBeVisible()
    const thumbnail = modal.locator('.receipt-history-thumbnail').first()
    await thumbnail.scrollIntoViewIfNeeded()
    await expect.poll(() => thumbnail.locator('img').evaluate(element => element.complete && element.naturalWidth > 0)).toBe(true)
    expect(await thumbnail.evaluate(element => {
      const image = element.querySelector('img'), box = element.getBoundingClientRect(), imageBox = image.getBoundingClientRect()
      return imageBox.height <= box.height + 1 && imageBox.width <= box.width + 1 && getComputedStyle(image).objectFit === 'contain'
    })).toBe(true)
    heights.push((await thumbnail.boundingBox()).height)
    const square = await thumbnail.boundingBox()
    expect(Math.abs(square.width - square.height)).toBeLessThanOrEqual(1)
    for (const theme of ['default', 'toss']) {
      await page.evaluate(theme => document.documentElement.dataset.theme = theme, theme)
      const grid = modal.locator('.receipt-history-blocks')
      expect(await grid.evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
      await modal.screenshot({ path: testInfo.outputPath(`${theme}-${size}-history-blocks.png`) })
    }
  }
  expect(heights[0]).toBeGreaterThan(heights[1])
  expect(heights[1]).toBeGreaterThan(heights[2])
  await modal.locator('.receipt-history-tile__open').first().focus()
  await modal.locator('.receipt-history-tile__open').first().press('Enter')
  await expect(modal.locator('.receipt-ocr-review-entry')).toHaveCount(1)
  await modal.getByRole('button', { name: /뒤로가기/ }).click()
  await expect(modal.locator('.receipt-history-blocks--small')).toBeVisible()
  await page.reload()
  await page.getByRole('button', { name: '달력 가계부', exact: true }).click()
  await page.getByRole('button', { name: '분석 모달 열기', exact: true }).click()
  await modal.getByRole('button', { name: '분석 내용 확인 및 불러오기', exact: true }).click()
  await expect(modal.locator('.receipt-history-blocks--small')).toBeVisible()
  await modal.getByRole('button', { name: '리스트로 보기', exact: true }).click()
  await expect(modal.locator('.receipt-ocr-history__item')).toHaveCount(3)
  await expect(modal.locator('.receipt-history-row__file time')).toHaveText(Array(3).fill('요청 12:34:56'))
  await modal.screenshot({ path: testInfo.outputPath('timestamped-history-list.png') })
  expect(requests).toHaveLength(0)
  expect(errors).toEqual([])
})

test('image blocks handle missing or failed previews and preserve cancellation and confirmed deletion', async ({ page }) => {
  const initialHistories = [
    { id: 301, status: 'COMPLETED', imageAvailable: false, fileName: 'no-image.png', result: { suggestedEntries: [] } },
    { id: 302, status: 'FAILED', imageAvailable: true, fileName: 'broken-image.png' },
    { id: 303, status: 'PROCESSING', imageAvailable: true, fileName: 'processing.png' },
  ]
  const { modal, requests, imageReads, errors } = await setup(page, { initialHistories, landing: true, brokenImages: [302] })
  await modal.getByRole('button', { name: '분석 내용 확인 및 불러오기', exact: true }).click()
  await modal.getByRole('button', { name: '블록으로 보기', exact: true }).click()
  await expect(modal.locator('[data-history-id="301"]').getByRole('img')).toHaveAttribute('aria-label', '저장된 이미지 없음')
  const broken = modal.locator('[data-history-id="302"]')
  await broken.scrollIntoViewIfNeeded()
  await expect(broken.getByRole('img')).toHaveAttribute('aria-label', '이미지 미리보기 불가')
  const processing = modal.locator('[data-history-id="303"]')
  await expect(processing.locator('.receipt-history-tile__open')).toBeDisabled()
  await expect(processing.locator('.receipt-history-tile__caption strong')).toHaveText('분석 중')
  await processing.getByRole('button', { name: '요청 취소', exact: true }).click()
  await expect(processing.locator('.receipt-history-tile__open')).toBeEnabled()
  await expect(processing).toContainText('취소됨')
  page.once('dialog', dialog => dialog.dismiss())
  await broken.getByRole('button', { name: '삭제', exact: true }).click()
  await expect(broken).toHaveCount(1)
  page.once('dialog', dialog => dialog.accept())
  await broken.getByRole('button', { name: '삭제', exact: true }).click()
  await expect(broken).toHaveCount(0)
  expect(imageReads).not.toContain(301)
  expect(requests).toHaveLength(0)
  expect(errors).toEqual([])
})

test('landing reserves workspace and shows pending requests beyond the first history page', async ({ page }, testInfo) => {
  await page.addInitScript(() => localStorage.setItem('calen-household-receipt-ocr-applied-entry-markers:v1', JSON.stringify([{ analysisId: 888, entryIndex: 0, appliedAt: '2026-10-21T12:00:00' }])))
  const suggestion = index => ({ title: `대기 거래 ${index}`, amount: 1000, entryType: 'EXPENSE', entryDate: '2026-10-21', categoryGroupId: 101, categoryDetailId: 102, paymentMethodId: 103 })
  const initialHistories = Array.from({ length: 8 }, (_, index) => ({ id: 100 + index, status: 'COMPLETED', fileName: `used-${index}.png`, result: { suggestedEntries: [suggestion(index)], approvedEntryIndexes: [0] } }))
  initialHistories.push({ id: 999, status: 'COMPLETED', fileName: 'pending.png', result: { analysisStatus: 'COMPLETED', suggestedEntries: [suggestion(0), suggestion(1)], approvedEntryIndexes: [0] } })
  initialHistories.push({ id: 888, status: 'COMPLETED', fileName: 'applied.png', result: { suggestedEntries: [suggestion(0)], approvedEntryIndexes: [] } })
  const { modal, errors } = await setup(page, { initialHistories, landing: true })
  await expect(modal.locator('.receipt-ocr-welcome')).toBeVisible()
  await expect(modal.locator('.receipt-ocr-review-badge')).toHaveText('1')
  for (const theme of ['default', 'toss']) {
    await page.evaluate(theme => document.documentElement.dataset.theme = theme, theme)
    const box = await modal.boundingBox(), viewport = page.viewportSize()
    expect(box.height).toBeGreaterThanOrEqual(Math.min(600, viewport.height - 40))
    expect(box.y).toBeGreaterThanOrEqual(0)
    expect(box.y + box.height).toBeLessThanOrEqual(viewport.height + 1)
    await expect.poll(() => modal.getByRole('button', { name: '닫기', exact: true }).evaluate(element => {
      const box = element.getBoundingClientRect()
      return element.contains(document.elementFromPoint(box.x + box.width / 2, box.y + box.height / 2))
    })).toBe(true)
    await modal.screenshot({ path: testInfo.outputPath(`${theme}-landing.png`) })
  }
  await modal.getByRole('button', { name: '대기 결과 확인', exact: true }).click()
  await expect(modal.locator('.receipt-ocr-history__item')).toHaveCount(8)
  await expect(modal.locator('.receipt-ocr-review-badge')).toHaveCount(0)
  await modal.locator('.receipt-ocr-history__pagination').getByRole('button', { name: '다음', exact: true }).click()
  await modal.locator('.receipt-ocr-history__item', { hasText: 'pending.png' }).getByRole('button', { name: '내용 보기', exact: true }).click()
  await modal.locator('.receipt-ocr-review-entry').last().getByRole('button', { name: '입력칸에 적용', exact: true }).click()
  await page.getByRole('button', { name: '분석 모달 열기', exact: true }).click()
  await expect(modal.locator('.receipt-ocr-review-badge')).toHaveCount(0)
  expect(errors).toEqual([])
})

test('empty landing is full sized and its starting actions work without a badge', async ({ page }) => {
  const { modal, errors } = await setup(page, { landing: true })
  await expect(modal.locator('.receipt-ocr-review-badge')).toHaveCount(0)
  await expect(modal.locator('.receipt-ocr-welcome__steps article')).toHaveCount(3)
  await modal.getByRole('button', { name: '이미지 분석 시작', exact: true }).click()
  await expect(modal.locator('input[type=file][multiple]')).toHaveCount(1)
  await expect(modal.getByRole('button', { name: '파일에서 선택', exact: true })).toBeVisible()
  expect(errors).toEqual([])
})

test('visiting history clears unread badges persistently without approving results and new completions notify again', async ({ page }) => {
  const result = { analysisStatus: 'COMPLETED', suggestedEntries: [{ title: '미승인 거래', amount: 15000, entryType: 'EXPENSE', entryDate: '2026-10-21' }] }
  await page.addInitScript(() => localStorage.setItem('calen-household-receipt-seen:v1:999', JSON.stringify(['501', '502'])))
  const { modal, histories, errors } = await setup(page, { landing: true, initialHistories: [
    { id: 501, status: 'COMPLETED', fileName: 'first.png', result },
    { id: 502, status: 'COMPLETED', fileName: 'second.png', result },
    { id: 500, status: 'PROCESSING', fileName: 'slow.png' },
  ] })
  const badge = modal.locator('.receipt-ocr-review-badge')
  await expect(badge).toHaveText('2')
  await modal.getByRole('button', { name: '분석 내용 확인 및 불러오기', exact: true }).click()
  await expect(badge).toHaveCount(0)
  await modal.getByRole('button', { name: '이미지 분석하기', exact: true }).click()
  await expect(badge).toHaveCount(0)
  await modal.getByRole('button', { name: '닫기', exact: true }).click()
  await page.getByRole('button', { name: '분석 모달 열기', exact: true }).click()
  await expect(badge).toHaveCount(0)
  // An older request finishing later must notify too (not just IDs above a watermark).
  await modal.getByRole('button', { name: '닫기', exact: true }).click()
  Object.assign(histories.find(history => history.id === 500), { status: 'COMPLETED', result })
  await page.getByRole('button', { name: '분석 모달 열기', exact: true }).click()
  await expect(badge).toHaveText('1')
  await modal.getByRole('button', { name: '분석 내용 확인 및 불러오기', exact: true }).click()
  await expect(badge).toHaveCount(0)
  await page.reload()
  await page.getByRole('button', { name: '달력 가계부', exact: true }).click()
  await page.getByRole('button', { name: '분석 모달 열기', exact: true }).click()
  await expect(badge).toHaveCount(0)
  await expect(modal.locator('.receipt-ocr-welcome__pending')).toContainText('완료된 분석 3건')
  expect(histories.every(history => !history.result.approvedEntryIndexes?.length)).toBe(true)
  expect(errors).toEqual([])
})

test('album packs individual requests into square photo rows with a horizontal history toolbar', async ({ page }, testInfo) => {
  const { modal, errors } = await setup(page, { landing: true, initialHistories: Array.from({ length: 5 }, (_, index) => ({
    id: 601 + index, status: index === 4 ? 'PROCESSING' : 'COMPLETED', imageAvailable: true,
    fileName: `album-${index}.png`, createdAt: '2026-10-21T12:34:56',
    result: index === 4 ? undefined : { suggestedEntries: [{ title: `간단한 결제 ${index}`, amount: 15000 }] },
  })) })
  await modal.getByRole('button', { name: '분석 내용 확인 및 불러오기', exact: true }).click()
  const toolbar = modal.locator('.receipt-history-view-toolbar')
  expect((await toolbar.boundingBox()).height).toBeGreaterThanOrEqual(48)
  expect(await toolbar.evaluate(element => {
    const items = [element.querySelector('strong'), ...element.querySelectorAll('button')]
    const center = item => { const box = item.getBoundingClientRect(); return box.y + box.height / 2 }
    return items.every(item => Math.abs(center(item) - center(items[0])) < 2)
  })).toBe(true)
  await modal.getByRole('button', { name: '블록으로 보기', exact: true }).click()
  await modal.getByRole('button', { name: '작게 보기', exact: true }).click()
  expect((await toolbar.boundingBox()).height).toBeGreaterThanOrEqual(48)
  expect(await toolbar.getByRole('button', { name: '작게 보기', exact: true }).evaluate(element => {
    const box = element.getBoundingClientRect()
    return element.contains(document.elementFromPoint(box.x + box.width / 2, box.y + box.height / 2))
  })).toBe(true)
  const thumbnails = modal.locator('.receipt-history-thumbnail')
  await expect(thumbnails).toHaveCount(5)
  const first = await thumbnails.nth(0).boundingBox(), second = await thumbnails.nth(1).boundingBox()
  expect(Math.abs(first.y - second.y)).toBeLessThanOrEqual(1)
  for (const thumbnail of await thumbnails.all()) {
    const box = await thumbnail.boundingBox()
    expect(Math.abs(box.width - box.height)).toBeLessThanOrEqual(1)
  }
  await expect(modal.locator('[data-history-id="601"] .receipt-history-tile__caption strong')).toHaveText('간단한 결제 0')
  await expect(modal.locator('[data-history-id="605"] .receipt-history-tile__caption strong')).toHaveText('분석 중')
  await expect(modal.locator('[data-history-id="601"] time')).toHaveText('12:34:56')
  for (const theme of ['default', 'toss']) {
    await page.evaluate(theme => document.documentElement.dataset.theme = theme, theme)
    expect(await modal.evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
    await modal.screenshot({ path: testInfo.outputPath(`${theme}-square-album.png`) })
  }
  expect(errors).toEqual([])
})

test('six selected images remain visible after three complete and one fails', async ({ page }, testInfo) => {
  const { modal, requests, errors, releaseRequest } = await setup(page, { holdRequests: [4, 5, 6], failRequests: [5] })
  try {
    await modal.locator('input[type=file][multiple]').setInputFiles(Array.from({ length: 6 }, () => image))
    await expect(modal.locator('.receipt-ocr-review-card')).toHaveCount(6)
    await modal.getByRole('button', { name: '분석 요청하기', exact: true }).click()
    await expect.poll(() => requests.length).toBe(6)
    await expect(modal.locator('.receipt-ocr-review-card')).toHaveCount(6)
    await expect(modal.locator('.receipt-ocr-batch-progress')).toContainText('3 / 6')
    for (const rerun of await modal.getByRole('button', { name: '재요청', exact: true }).all()) await expect(rerun).toBeDisabled()
    for (const number of [4, 5, 6]) releaseRequest(number)
    await expect.poll(() => requests.length).toBe(6)
    await expect(modal.locator('.receipt-ocr-review-card')).toHaveCount(6)
    await expect(modal.locator('.receipt-ocr-review-card__status', { hasText: '실패' })).toHaveCount(1)
    await expect(modal.locator('.receipt-ocr-review-card__status', { hasText: '완료' })).toHaveCount(5)
    await expect(modal.locator('.receipt-ocr-batch-progress')).toContainText('새로고침했습니다')
    for (const body of requests) expect(body.match(/name="file";/g)).toHaveLength(1)
    const batchInfo = requests.map(body => parseReceiptBatchRequestId(/name="clientRequestId"\r\n\r\n([^\r]+)/.exec(body)?.[1]))
    expect(new Set(batchInfo.map(info => info.key)).size).toBe(1)
    expect(batchInfo.map(info => info.position).sort((a, b) => a - b)).toEqual([1, 2, 3, 4, 5, 6])
    await modal.screenshot({ path: testInfo.outputPath('six-image-batch.png') })
    await modal.locator('input[type=file][multiple]').setInputFiles(image)
    await expect(modal.locator('.receipt-ocr-review-card')).toHaveCount(1)
    await modal.getByRole('button', { name: '분석 요청하기', exact: true }).click()
    await expect.poll(() => requests.length).toBe(7)
    await expect(modal.locator('.receipt-ocr-review-card__status', { hasText: '완료' })).toHaveCount(1)
    expect(errors).toEqual([])
  } finally { for (const number of [4, 5, 6]) releaseRequest(number) }
})

test('large image results bound the rendered forms and preserve original entry indexes', async ({ page }, testInfo) => {
  const { modal, errors } = await setup(page, { entryCount: 83 })
  await modal.locator('input[type=file][multiple]').setInputFiles(image)
  await modal.getByRole('button', { name: '분석 요청하기', exact: true }).click()
  await modal.getByRole('button', { name: '분석 내용 확인 및 불러오기', exact: true }).click()
  await modal.getByRole('button', { name: '내용 보기', exact: true }).first().click()
  const forms = modal.locator('.receipt-ocr-review-entry')
  await expect(forms).toHaveCount(10)
  await modal.screenshot({ path: testInfo.outputPath('paged-review.png') })
  const firstTitle = forms.first().getByRole('textbox', { name: '제목', exact: true })
  await firstTitle.fill('첫 페이지 수정')
  for (const theme of ['default', 'toss']) {
    await page.evaluate(theme => document.documentElement.dataset.theme = theme, theme)
    await modal.getByRole('button', { name: '다음 거래', exact: true }).click()
    await expect(forms.first()).toContainText('검수 거래 11')
    await modal.getByRole('button', { name: '이전 거래', exact: true }).click()
    await expect(forms.first().getByRole('textbox', { name: '제목', exact: true })).toHaveValue('첫 페이지 수정')
  }
  for (let pageIndex = 0; pageIndex < 8; pageIndex++) await modal.getByRole('button', { name: '다음 거래', exact: true }).click()
  await expect(forms).toHaveCount(3)
  await expect(forms.last()).toContainText('검수 거래 83')
  await forms.last().getByRole('button', { name: '입력칸에 적용', exact: true }).click()
  await expect(page.locator('.household-entry-panel').getByRole('textbox', { name: '거래 제목', exact: true })).toHaveValue('거래 83')
  const markers = await page.evaluate(() => JSON.parse(localStorage.getItem('calen-household-receipt-ocr-applied-entry-markers:v1')))
  expect(markers.some(marker => marker.entryIndex === 82)).toBe(true)
  await testInfo.attach('render-size', { body: JSON.stringify({ totalCandidates: 83, maxRendered: 10 }), contentType: 'application/json' })
  expect(errors).toEqual([])
})

test('ordinary content updates do not trigger full-document mobile modal scans', async ({ page }) => {
  const { modal, errors } = await setup(page)
  const scans = await page.evaluate(async () => {
    const frame = () => new Promise(resolve => requestAnimationFrame(resolve))
    await frame(); await frame()
    const original = document.querySelector
    let calls = 0
    document.querySelector = function (selector) {
      if (selector.includes('.drive-preview-modal')) calls++
      return original.call(this, selector)
    }
    try {
      const nodes = document.querySelectorAll('.calendar__day, .receipt-ocr-modal__nav-button')
      for (let i = 0; i < 5; i++) {
        for (const node of nodes) node.classList.toggle('ordinary-content-update')
        await frame(); await frame()
      }
    } finally { document.querySelector = original }
    return calls
  })
  expect(scans).toBe(0)
  await modal.getByRole('button', { name: '닫기', exact: true }).click()
  await expect(modal).toHaveCount(0)
  await expect.poll(() => page.evaluate(() => document.body.style.position)).not.toBe('fixed')
  expect(errors).toEqual([])
})
