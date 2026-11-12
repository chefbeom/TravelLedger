import { expect, test } from '@playwright/test'
import { mockPersonalization } from './fixtures/calendarPersonalization.js'

test('refund details, linked history, reversal and payment cancellation remain usable', async ({ page }, testInfo) => {
  await page.clock.setFixedTime(new Date('2026-10-22T12:00:00'))
  const original = { id: 7, entryDate: '2026-10-22', entryTime: '12:30', entryType: 'EXPENSE', amount: 100000,
    title: '검증용 결제', categoryGroupId: 101, categoryGroupName: '식비', paymentMethodId: 103, paymentMethodName: '카드' }
  const records = [original]
  const state = await mockPersonalization(page, { entries: records })
  const sent = []
  await page.route('**/api/categories*', route => route.fulfill({ json: [
    { id: 101, name: '식비', entryType: 'EXPENSE', active: true, details: [] },
    { id: 102, name: '기타 수입', entryType: 'INCOME', active: true, details: [] },
  ] }))
  await page.route('**/api/payment-methods*', route => route.fulfill({ json: [
    { id: 103, name: '카드', kind: 'CARD', active: true }, { id: 104, name: '현금', kind: 'CASH', active: true },
  ] }))
  await page.route('**/api/entries/7/adjustments', async route => {
    if (route.request().method() === 'GET') return route.fulfill({ json: records.filter(item => item.adjustmentOriginId === 7) })
    const body = route.request().postDataJSON()
    sent.push(body)
    original.hasAdjustmentHistory = true
    if (body.type === 'REFUND') {
      original.refundedPrincipal = Number(body.principal)
      records.push({ id: 8, entryDate: body.date, entryType: 'INCOME', title: '환불: 검증용 결제',
        amount: Number(body.principal) - Number(body.deduction) + Number(body.extra),
        categoryGroupName: '기타 수입', paymentMethodId: 104, paymentMethodName: '현금',
        adjustmentType: 'REFUND', adjustmentOriginId: 7, adjustmentPrincipal: body.principal,
        adjustmentDeduction: body.deduction, adjustmentExtra: body.extra })
      return route.fulfill({ json: { id: 8 } })
    }
    original.cancelledAt = `${body.date}T00:00:00`
    original.financiallyExcluded = true
    records.push({ id: 9, entryDate: body.date, entryType: 'EXPENSE', title: '결제 취소: 검증용 결제', amount: 100000,
      categoryGroupName: '식비', paymentMethodName: '카드', financiallyExcluded: true,
      adjustmentType: 'PAYMENT_CANCELLATION', adjustmentOriginId: 7, adjustmentPrincipal: 100000 })
    return route.fulfill({ json: { id: 9 } })
  })
  await page.route('**/api/entries/8/adjustments/revert', route => {
    original.refundedPrincipal = 0
    records.find(item => item.id === 8).adjustmentVoidedAt = '2026-10-22T12:35:00'
    records.find(item => item.id === 8).financiallyExcluded = true
    return route.fulfill({ status: 204, body: '' })
  })
  await page.reload()
  await page.getByRole('button', { name: '달력 가계부', exact: true }).click()
  await page.getByRole('region', { name: '달력 조회', exact: true }).getByRole('button', { name: '거래 시트 열기' }).click()
  const sheet = page.getByRole('dialog', { name: '거래 시트' })
  const originalRow = sheet.getByRole('row').filter({ hasText: '검증용 결제' }).first()
  await expect(originalRow.getByRole('button', { name: '환불', exact: true })).toHaveCount(0)
  await expect(originalRow.getByRole('button', { name: '삭제', exact: true })).toHaveCount(0)
  await originalRow.getByRole('button', { name: '수정', exact: true }).click()
  const manage = page.getByRole('dialog', { name: '거래 관리' })
  await expect(manage.getByText('검증용 결제')).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(manage).toHaveCount(0)
  await expect(sheet).toBeVisible()
  await originalRow.getByRole('button', { name: '수정', exact: true }).click()
  await manage.getByRole('button', { name: /환불/ }).click()
  const refund = page.getByRole('dialog', { name: '환불 기록' })
  await refund.getByLabel('차감액 (원)').fill('10000')
  await refund.getByLabel('추가 지급액 (원)').fill('3000')
  await expect(refund.getByText('₩93,000')).toBeVisible()
  await expect(refund.getByLabel('실제 수령 수단')).toHaveValue('104')
  const layout = await refund.evaluate(element => {
    const bounds = element.getBoundingClientRect()
    return { left: bounds.left, right: bounds.right, viewport: window.innerWidth,
      invalid: [...element.querySelectorAll('input, select, button')].some(input => {
        const rect = input.getBoundingClientRect()
        return rect.left < bounds.left - 1 || rect.right > bounds.right + 1
      }) }
  })
  expect(layout.left).toBeGreaterThanOrEqual(0)
  expect(layout.right).toBeLessThanOrEqual(layout.viewport)
  expect(layout.invalid).toBe(false)
  await refund.getByRole('button', { name: '환불 기록 저장' }).click()
  await expect(refund).toHaveCount(0)
  await expect(originalRow.getByText('환불 완료')).toBeVisible()
  await expect(sheet.getByText('환불 입금')).toBeVisible()
  await sheet.screenshot({ path: testInfo.outputPath('refund-sheet.png') })
  expect(sent[0]).toMatchObject({ type: 'REFUND', receivingPaymentMethodId: 104 })
  expect([sent[0].principal, sent[0].deduction, sent[0].extra].map(Number)).toEqual([100000, 10000, 3000])

  await originalRow.getByRole('button', { name: '관리' }).click()
  await manage.getByRole('button', { name: /처리 내역/ }).click()
  const history = page.getByRole('dialog', { name: '취소·환불 처리 내역' })
  await expect(history.getByText(/수령 ₩93,000/)).toBeVisible()
  page.once('dialog', dialog => dialog.accept())
  await history.getByRole('button', { name: '이 처리 되돌리기' }).click()
  await expect(history.getByText('환불 · 되돌림')).toBeVisible()
  await history.getByRole('button', { name: '닫기' }).click()
  await originalRow.getByRole('button', { name: '관리' }).click()
  await manage.getByRole('button', { name: /^결제 취소/ }).click()
  const cancellation = page.getByRole('dialog', { name: '결제 취소 기록' })
  await cancellation.getByRole('button', { name: '결제 취소 저장' }).click()
  await expect(originalRow.getByText('결제 취소됨')).toBeVisible()
  expect(sent[1].type).toBe('PAYMENT_CANCELLATION')
  expect(state.errors).toEqual([])
})
