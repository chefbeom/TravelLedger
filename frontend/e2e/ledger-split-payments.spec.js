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
  await page.getByLabel('분할 결제', { exact: true }).check()
  await expect(page.getByText('총 금액', { exact: true })).toBeVisible()
  await page.getByLabel('할부 결제', { exact: true }).check()
  await page.getByLabel('결제수단 1', { exact: true }).selectOption('103')
  await page.getByLabel('수단 1 금액').fill('6000')
  await page.getByLabel('이 카드 할부 적용').check()
  await page.getByLabel('수단 1 할부 개월').fill('3')
  await page.getByLabel('수단 1 할부 이자').selectOption('FREE')
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
  expect(await page.getByLabel('분할 결제', { exact: true }).evaluate(element => element.getBoundingClientRect().width)).toBeLessThanOrEqual(24)
  const partBounds = await editor.locator('.payment-options__part').first().boundingBox()
  const deleteBounds = await page.getByRole('button', { name: '결제수단 1 삭제' }).boundingBox()
  expect(deleteBounds.x + deleteBounds.width).toBeLessThanOrEqual(partBounds.x + partBounds.width + 1)
  await editor.screenshot({ path: testInfo.outputPath('split-payment-editor.png') })
  await page.getByRole('button', { name: '거래 등록', exact: true }).click()
  await expect.poll(() => requests.length).toBe(1)
  expect(requests[0].parts).toEqual([
    { paymentMethodId: 103, amount: 6000, installmentMonths: 3, installmentInterestType: 'FREE' },
    { paymentMethodId: 104, amount: 4000, installmentMonths: null, installmentInterestType: null },
  ])
  await page.getByRole('region', { name: '달력 조회', exact: true }).getByRole('button', { name: '거래 시트 열기' }).click()
  const sheet = page.getByRole('dialog', { name: '거래 시트' })
  const groupedRow = sheet.getByRole('row').filter({ hasText: '분할 구매' })
  await expect(groupedRow).toHaveCount(1)
  await groupedRow.getByRole('button', { name: '상세보기' }).click()
  const details = page.getByRole('dialog', { name: '분할 결제 상세' })
  await expect(details.getByText('결제 1/2 · 카드')).toBeVisible()
  await expect(details.getByText('결제 2/2 · 현금')).toBeVisible()
  await expect(details.getByText('3개월 무이자 할부')).toBeVisible()
  await details.getByRole('button', { name: '닫기' }).click()
  await sheet.getByRole('button', { name: '닫기' }).click()
  await page.getByPlaceholder('예: 48,000').fill('12000')
  await page.getByRole('textbox', { name: '거래 제목' }).fill('단독 할부 구매')
  if (!(await editor.evaluate(element => element.open))) await editor.locator('summary').click()
  await page.getByLabel('할부 결제', { exact: true }).check()
  await editor.getByLabel('할부 개월', { exact: true }).fill('6')
  await editor.getByRole('combobox', { name: '이자 구분' }).selectOption('INTEREST')
  await page.getByRole('button', { name: '거래 등록', exact: true }).click()
  await expect.poll(() => singleRequests.length).toBe(1)
  expect(singleRequests[0]).toMatchObject({ amount: 12000, installmentMonths: 6, installmentInterestType: 'INTEREST' })
  await page.getByRole('region', { name: '달력 조회', exact: true }).getByRole('button', { name: '거래 시트 열기' }).click()
  await expect(sheet.getByText('6개월 유이자 할부')).toBeVisible()
  expect(state.errors).toEqual([])
})

test('split-only purchase uses the top amount as the total without installment details', async ({ page }) => {
  await page.clock.setFixedTime(new Date('2026-10-22T12:00:00'))
  await mockPersonalization(page, { entries: [] })
  let submitted
  await page.route('**/api/payment-methods*', route => route.fulfill({ json: [
    { id: 103, name: '카드', kind: 'CARD', active: true },
    { id: 104, name: '현금', kind: 'CASH', active: true },
  ] }))
  await page.route('**/api/entries/split-payments', route => {
    submitted = route.request().postDataJSON()
    return route.fulfill({ json: submitted.parts.map((part, index) => ({
      ...submitted.entry, ...part, id: 300 + index, splitPaymentGroupId: submitted.requestId,
      splitPaymentIndex: index + 1, splitPaymentCount: submitted.parts.length,
    })) })
  })
  await page.reload()
  await page.getByRole('button', { name: '달력 가계부', exact: true }).click()
  await page.getByPlaceholder('예: 48,000').fill('500000')
  await page.getByRole('textbox', { name: '거래 제목' }).fill('분할 구매')
  await page.locator('summary').filter({ hasText: '결제 옵션' }).click()
  await page.getByLabel('분할 결제', { exact: true }).check()
  await expect(page.getByLabel('할부 결제', { exact: true })).not.toBeChecked()
  await page.getByLabel('결제수단 1', { exact: true }).selectOption('103')
  await page.getByLabel('수단 1 금액').fill('300000')
  await page.getByLabel('결제수단 2', { exact: true }).selectOption('104')
  await page.getByLabel('수단 2 금액').fill('200000')
  await expect(page.getByLabel('수단 1 할부 개월')).toHaveCount(0)
  await page.getByRole('button', { name: '거래 등록', exact: true }).click()
  await expect.poll(() => submitted?.entry.amount).toBe(500000)
  expect(submitted.parts.map(part => [part.amount, part.installmentMonths])).toEqual([[300000, null], [200000, null]])
})

test('split amounts are capped and the editable last remainder follows changes; each card can use installments', async ({ page }) => {
  await page.clock.setFixedTime(new Date('2026-10-22T12:00:00'))
  const state = await mockPersonalization(page, { entries: [] })
  let submitted
  await page.route('**/api/payment-methods*', route => route.fulfill({ json: [
    { id: 103, name: '카드 A', kind: 'CARD', active: true },
    { id: 104, name: '카드 B', kind: 'CARD', active: true },
    { id: 105, name: '현금', kind: 'CASH', active: true },
  ] }))
  await page.route('**/api/entries/split-payments', route => {
    submitted = route.request().postDataJSON()
    return route.fulfill({ json: submitted.parts.map((part, index) => ({ ...submitted.entry, ...part,
      id: 400 + index, splitPaymentGroupId: submitted.requestId, splitPaymentIndex: index + 1, splitPaymentCount: submitted.parts.length })) })
  })
  await page.reload()
  await page.getByRole('button', { name: '달력 가계부', exact: true }).click()
  const total = page.getByPlaceholder('예: 48,000')
  await total.fill('50000')
  await page.getByRole('textbox', { name: '거래 제목' }).fill('금액 자동 분할')
  const editor = page.locator('.payment-options')
  await editor.locator('summary').click()
  const choices = await editor.locator('.payment-options__choice').evaluateAll(nodes => nodes.map(node => {
    const rect = node.getBoundingClientRect()
    return { x: rect.x, y: rect.y, width: rect.width }
  }))
  expect(choices[0].x).toBeLessThan(choices[1].x)
  expect(Math.abs(choices[0].y - choices[1].y)).toBeLessThan(1)
  expect(Math.abs(choices[0].width - choices[1].width)).toBeLessThan(1)
  await page.getByLabel('분할 결제', { exact: true }).check()
  await page.getByLabel('결제수단 1', { exact: true }).selectOption('103')
  await page.getByLabel('결제수단 2', { exact: true }).selectOption('104')
  const first = page.getByLabel('수단 1 금액'), second = page.getByLabel('수단 2 금액')
  await expect(second).toHaveValue('50000')
  await first.fill('30000')
  await expect(second).toHaveValue('20000')
  await second.fill('50000')
  await expect(second).toHaveValue('20000')
  await expect(editor.getByRole('status')).toContainText('총 금액을 넘지 않도록')
  await second.fill('10000')
  await expect(second).toHaveValue('10000')
  await expect(editor).toContainText('남은 금액 10,000원')
  await first.fill('25000')
  await expect(second).toHaveValue('25000')
  await editor.getByRole('button', { name: '수단 추가' }).click()
  await expect(page.getByLabel('수단 3 금액')).toHaveValue('0')
  await second.fill('10000')
  await expect(page.getByLabel('수단 3 금액')).toHaveValue('15000')
  await editor.getByRole('button', { name: '결제수단 3 삭제' }).click()
  await expect(second).toHaveValue('25000')
  await total.fill('20000')
  await expect(first).toHaveValue('20000')
  await expect(second).toHaveValue('0')
  await expect(editor.getByRole('status')).toContainText('변경된 총 금액')
  await total.fill('50000')
  await first.fill('30000')
  await expect(second).toHaveValue('20000')
  await editor.getByLabel('이 카드 할부 적용').nth(0).check()
  await expect(editor.getByLabel('할부 결제', { exact: true })).toBeChecked()
  await page.getByLabel('수단 1 할부 개월').fill('3')
  await page.getByLabel('수단 1 할부 이자').selectOption('FREE')
  await editor.getByLabel('이 카드 할부 적용').nth(1).check()
  await page.getByLabel('수단 2 할부 개월').fill('6')
  await page.getByLabel('수단 2 할부 이자').selectOption('INTEREST')
  await page.getByRole('button', { name: '거래 등록', exact: true }).click()
  await expect.poll(() => submitted?.parts.length).toBe(2)
  expect(submitted.parts).toEqual([
    { paymentMethodId: 103, amount: 30000, installmentMonths: 3, installmentInterestType: 'FREE' },
    { paymentMethodId: 104, amount: 20000, installmentMonths: 6, installmentInterestType: 'INTEREST' },
  ])
  expect(state.errors).toEqual([])
})
