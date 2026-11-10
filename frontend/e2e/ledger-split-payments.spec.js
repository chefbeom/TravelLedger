import { expect, test } from '@playwright/test'
import { mockPersonalization } from './fixtures/calendarPersonalization.js'

test('split payment and installment information are entered and shown per method', async ({ page }, testInfo) => {
  await page.clock.setFixedTime(new Date('2026-10-22T12:00:00'))
  const entries = []
  const state = await mockPersonalization(page, { entries })
  const requests = []
  const singleRequests = []
  await page.route('**/api/payment-methods*', route => route.fulfill({ json: [
    { id: 103, name: '카드', kind: 'CARD', active: true },
    { id: 104, name: '현금', kind: 'CASH', active: true },
  ] }))
  await page.route('**/api/entries/split-payments', route => {
    const body = route.request().postDataJSON()
    requests.push(body)
    const saved = body.parts.map((part, index) => ({ ...body.entry, ...part,
      id: index + 100, categoryGroupName: '식비', paymentMethodName: part.paymentMethodId === 103 ? '카드' : '현금',
      splitPaymentGroupId: body.requestId, splitPaymentIndex: index + 1, splitPaymentCount: body.parts.length }))
    entries.push(...saved)
    return route.fulfill({ json: saved })
  })
  await page.route('**/api/entries', route => {
    if (route.request().method() !== 'POST') return route.fallback()
    const body = route.request().postDataJSON()
    singleRequests.push(body)
    const saved = { ...body, id: 200, categoryGroupName: '식비', paymentMethodName: '카드' }
    entries.push(saved)
    return route.fulfill({ json: saved })
  })
  await page.reload()
  await page.getByRole('button', { name: '달력 가계부', exact: true }).click()
  await page.getByPlaceholder('예: 48,000').fill('10000')
  await page.getByRole('textbox', { name: '거래 제목' }).fill('분할 구매')
  await page.locator('summary').filter({ hasText: '결제 옵션' }).click()
  await page.getByLabel('여러 수단으로 분할 결제').check()
  await page.getByLabel('결제수단 1', { exact: true }).selectOption('103')
  await page.getByLabel('수단 1 금액').fill('6000')
  await page.getByLabel('수단 1 할부 개월').fill('3')
  await page.getByLabel('결제수단 2', { exact: true }).selectOption('104')
  await page.getByLabel('수단 2 금액').fill('3000')
  await page.getByRole('button', { name: '거래 등록', exact: true }).click()
  await expect(page.getByText('결제수단별 금액의 합계가 전체 거래 금액과 일치해야 합니다.')).toBeVisible()
  expect(requests).toHaveLength(0)
  await page.getByLabel('수단 2 금액').fill('4000')
  const editor = page.locator('.payment-options')
  const bounds = await editor.evaluate(element => {
    const rect = element.getBoundingClientRect()
    return { left: rect.left, right: rect.right, width: window.innerWidth }
  })
  expect(bounds.left).toBeGreaterThanOrEqual(0)
  expect(bounds.right).toBeLessThanOrEqual(bounds.width + 1)
  expect(await page.getByLabel('여러 수단으로 분할 결제').evaluate(element => element.getBoundingClientRect().width)).toBeLessThanOrEqual(24)
  await editor.screenshot({ path: testInfo.outputPath('split-payment-editor.png') })
  await page.getByRole('button', { name: '거래 등록', exact: true }).click()
  await expect.poll(() => requests.length).toBe(1)
  expect(requests[0].parts).toEqual([
    { paymentMethodId: 103, amount: 6000, installmentMonths: 3 },
    { paymentMethodId: 104, amount: 4000, installmentMonths: null },
  ])
  await page.getByRole('region', { name: '달력 조회', exact: true }).getByRole('button', { name: '거래 시트 열기' }).click()
  const sheet = page.getByRole('dialog', { name: '거래 시트' })
  await expect(sheet.getByText('분할 1/2')).toBeVisible()
  await expect(sheet.getByText('분할 2/2')).toBeVisible()
  await expect(sheet.getByText('3개월 할부')).toBeVisible()
  await sheet.getByRole('button', { name: '닫기' }).click()
  await page.getByPlaceholder('예: 48,000').fill('12000')
  await page.getByRole('textbox', { name: '거래 제목' }).fill('단독 할부 구매')
  if (!(await editor.evaluate(element => element.open))) await editor.locator('summary').click()
  await page.getByLabel('카드 할부 개월').fill('6')
  await page.getByRole('button', { name: '거래 등록', exact: true }).click()
  await expect.poll(() => singleRequests.length).toBe(1)
  expect(singleRequests[0]).toMatchObject({ amount: 12000, installmentMonths: 6 })
  await page.getByRole('region', { name: '달력 조회', exact: true }).getByRole('button', { name: '거래 시트 열기' }).click()
  await expect(sheet.getByText('6개월 할부')).toBeVisible()
  expect(state.errors).toEqual([])
})
