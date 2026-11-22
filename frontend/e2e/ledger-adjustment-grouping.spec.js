import { expect, test } from '@playwright/test'
import { mockPersonalization } from './fixtures/calendarPersonalization.js'

const card = { categoryGroupId: 101, categoryGroupName: '취미', categoryDetailId: 102, categoryDetailName: '구독', paymentMethodId: 103, paymentMethodName: '테스트 카드' }
const day = '2026-10-22'

const openSheet = page => page.getByRole('region', { name: '달력 조회', exact: true }).getByRole('button', { name: '거래 시트 열기' }).click()
const sheetOf = page => page.getByRole('dialog', { name: '거래 시트' })
const managerOf = page => page.getByRole('dialog', { name: '거래 관리' })

function userCase() {
  return [
    { ...card, id: 4100, entryDate: day, entryTime: '21:31', entryType: 'EXPENSE', amount: 72000, title: '구독 서비스 B : 팀 요금제', foreignCurrencyCode: 'USD', foreignAmount: 50 },
    { ...card, id: 4101, entryDate: day, entryTime: null, entryType: 'EXPENSE', amount: 29000, title: '구독 서비스 A : 정기결제', memo: '구독 서비스 A 이용료', hasAdjustmentHistory: true, refundedPrincipal: 0, financiallyExcluded: false, cancelledAt: null },
    { ...card, id: 4102, entryDate: day, entryTime: null, entryType: 'EXPENSE', amount: 29000, title: '결제 취소: 구독 서비스 A : 정기결제', memo: '정기결제 취소', financiallyExcluded: true, adjustmentType: 'PAYMENT_CANCELLATION', adjustmentOriginId: 4101, adjustmentPrincipal: 29000, adjustmentVoidedAt: `${day}T10:00:00` },
  ]
}

async function mockGroup(page, ids, origin, adjustments) {
  const pattern = new RegExp(`/api/entries/(${ids.join('|')})/adjustment-group$`)
  await page.route(pattern, route => route.fulfill({ json: { origin, adjustments } }))
}

test('voided cancellation stays in one row with one management window', async ({ page }, testInfo) => {
  await page.clock.setFixedTime(new Date(`${day}T12:00:00`))
  const entries = userCase()
  const state = await mockPersonalization(page, { entries })
  await mockGroup(page, [4101, 4102], entries[1], [entries[2]])
  await page.reload()
  await page.getByRole('button', { name: '달력 가계부', exact: true }).click()

  for (const theme of ['light', 'dark']) {
    if (theme === 'dark') {
      await page.evaluate(() => localStorage.setItem('calen-theme-mode', 'toss'))
      await page.reload()
      await page.getByRole('button', { name: '달력 가계부', exact: true }).click()
    }
    await openSheet(page)
    const sheet = sheetOf(page)
    await expect(sheet).toContainText('2건 · 세부 기록 3건')
    const row = sheet.getByRole('row').filter({ hasText: '구독 서비스 A' })
    await expect(row).toHaveCount(1)
    await expect(sheet.getByRole('row').filter({ hasText: '결제 취소: 구독 서비스 A' })).toHaveCount(0)
    await expect(row).toContainText('취소 되돌림')
    await expect(row.getByRole('button', { name: '관리', exact: true })).toBeVisible()
    await expect(row.getByRole('button', { name: '공유', exact: true })).toBeVisible()
    await expect(sheet).not.toContainText('처리 이력 있음')
    await expect(sheet).not.toContainText('처리 되돌림')
    await sheet.screenshot({ path: testInfo.outputPath(`sheet-${theme}.png`) })

    await row.getByRole('button', { name: '관리', exact: true }).click()
    const manager = managerOf(page)
    await expect(manager.getByRole('heading', { name: '처리 이력' })).toBeVisible()
    await expect(manager.getByText('결제 취소 되돌림').first()).toBeVisible()
    await expect(manager).toContainText('시간 미지정')
    await expect(manager.getByRole('button', { name: '결제 취소', exact: true })).toBeVisible()
    await expect(manager.getByRole('button', { name: '환불', exact: true })).toBeVisible()
    await expect(manager.getByRole('button', { name: '거래 내용 수정', exact: true })).toBeVisible()
    await expect(manager.getByRole('button', { name: '삭제', exact: true })).toBeVisible()
    await expect(manager.getByRole('button', { name: '이 처리 되돌리기' })).toHaveCount(0)
    await expect(manager.getByRole('button', { name: /처리 내역/ })).toHaveCount(0)
    const fits = await manager.evaluate(element => {
      const box = element.getBoundingClientRect()
      return box.left >= 0 && box.right <= window.innerWidth && element.scrollWidth <= element.clientWidth + 1
    })
    expect(fits).toBe(true)
    await manager.screenshot({ path: testInfo.outputPath(`manager-${theme}.png`) })
    await page.keyboard.press('Escape')
    await expect(manager).toHaveCount(0)
    await expect(sheet).toBeVisible()
    await sheet.getByRole('button', { name: '닫기', exact: true }).first().click()
    await expect(sheet).toHaveCount(0)
  }
  expect(state.errors).toEqual([])
})

test('active cancellation strikes the amount and offers reverting inside the same window', async ({ page }, testInfo) => {
  await page.clock.setFixedTime(new Date(`${day}T12:00:00`))
  const origin = { ...card, id: 4101, entryDate: day, entryTime: '09:00', entryType: 'EXPENSE', amount: 29000, title: '구독 서비스 A : 정기결제', hasAdjustmentHistory: true, refundedPrincipal: 0, financiallyExcluded: true, cancelledAt: `${day}T09:30:00` }
  const record = { ...card, id: 4102, entryDate: day, entryTime: '09:30', entryType: 'EXPENSE', amount: 29000, title: '결제 취소: 구독 서비스 A : 정기결제', financiallyExcluded: true, adjustmentType: 'PAYMENT_CANCELLATION', adjustmentOriginId: 4101, adjustmentPrincipal: 29000 }
  const state = await mockPersonalization(page, { entries: [origin, record] })
  await mockGroup(page, [4101, 4102], origin, [record])
  const reverts = []
  await page.route('**/api/entries/4102/adjustments/revert', route => { reverts.push(route.request().method()); return route.fulfill({ status: 204, body: '' }) })
  await page.reload()
  await page.getByRole('button', { name: '달력 가계부', exact: true }).click()
  await openSheet(page)
  const sheet = sheetOf(page)
  const row = sheet.getByRole('row').filter({ hasText: '구독 서비스 A' })
  await expect(row).toHaveCount(1)
  await expect(row).toContainText('결제 취소됨')
  await expect(row).toContainText('결제 취소 · 합계 제외')
  await expect(row.getByRole('button', { name: '공유', exact: true })).toHaveCount(0)
  const decoration = await row.locator('.sheet-table__amount-main').evaluate(element => getComputedStyle(element).textDecorationLine)
  expect(decoration).toBe('line-through')
  await page.evaluate(() => { document.documentElement.dataset.theme = 'toss' })
  expect(await row.locator('.sheet-table__amount-main').evaluate(element => getComputedStyle(element).textDecorationLine)).toBe('line-through')
  await sheet.screenshot({ path: testInfo.outputPath('cancelled-sheet-dark.png') })
  await page.evaluate(() => { document.documentElement.dataset.theme = 'default' })
  await sheet.screenshot({ path: testInfo.outputPath('cancelled-sheet.png') })
  await row.getByRole('button', { name: '관리', exact: true }).click()
  const manager = managerOf(page)
  await expect(manager.getByText('적용 중', { exact: true })).toBeVisible()
  await expect(manager.getByRole('button', { name: '이 처리 되돌리기' })).toHaveCount(0)
  await expect(manager.getByRole('button', { name: '결제 취소 취소', exact: true })).toBeVisible()
  await manager.getByRole('button', { name: '결제 취소 취소', exact: true }).click()
  await expect(manager.getByRole('region', { name: '결제 취소 취소' })).toBeVisible()
  await manager.getByRole('button', { name: '결제 취소 취소하기' }).click()
  await expect.poll(() => reverts).toEqual(['POST'])
  expect(state.errors).toEqual([])
})

test('an adjustment on another date opens the original and full history', async ({ page }) => {
  await page.clock.setFixedTime(new Date(`${day}T12:00:00`))
  const origin = { ...card, id: 7, entryDate: '2026-09-10', entryTime: '10:00', entryType: 'EXPENSE', amount: 100000, title: '검증용 결제', hasAdjustmentHistory: true, refundedPrincipal: 30000 }
  const record = { id: 8, entryDate: day, entryTime: '14:00', entryType: 'INCOME', amount: 25000, title: '환불: 검증용 결제', categoryGroupName: '기타 수입', paymentMethodName: '현금', adjustmentType: 'REFUND', adjustmentOriginId: 7, adjustmentPrincipal: 30000, adjustmentDeduction: 5000, adjustmentExtra: 0 }
  const state = await mockPersonalization(page, { entries: [origin, record] })
  await mockGroup(page, [7, 8], origin, [record])
  await page.reload()
  await page.getByRole('button', { name: '달력 가계부', exact: true }).click()
  await openSheet(page)
  const sheet = sheetOf(page)
  const rows = sheet.getByRole('row').filter({ hasText: '환불: 검증용 결제' })
  await expect(rows).toHaveCount(1)
  await expect(rows).toContainText('환불 입금')
  await expect(rows).toContainText('원거래 #7')
  await expect(rows.getByRole('button')).toHaveCount(1)
  await rows.getByRole('button', { name: '관리', exact: true }).click()
  const manager = managerOf(page)
  await expect(manager).toContainText('2026-09-10')
  await expect(manager.getByText('환불 입금').first()).toBeVisible()
  await expect(manager.getByRole('button', { name: '환불 취소', exact: true })).toBeVisible()
  await expect(manager.getByRole('button', { name: '환불', exact: true })).toBeVisible()
  expect(state.errors).toEqual([])
})

test('type filter keeps the original row status', async ({ page }) => {
  await page.clock.setFixedTime(new Date(`${day}T12:00:00`))
  const origin = { ...card, id: 7, entryDate: day, entryTime: '12:30', entryType: 'EXPENSE', amount: 10000, title: '부분 환불 지출', hasAdjustmentHistory: true, refundedPrincipal: 3000 }
  const record = { id: 8, entryDate: day, entryTime: '13:00', entryType: 'INCOME', amount: 2500, title: '환불: 부분 환불 지출', categoryGroupName: '기타 수입', paymentMethodName: '현금', adjustmentType: 'REFUND', adjustmentOriginId: 7, adjustmentPrincipal: 3000, adjustmentDeduction: 500, adjustmentExtra: 0 }
  const state = await mockPersonalization(page, { entries: [origin, record] })
  await page.reload()
  await page.getByRole('button', { name: '달력 가계부', exact: true }).click()
  await openSheet(page)
  const sheet = sheetOf(page)
  const original = sheet.getByRole('row').filter({ hasText: '부분 환불 지출' }).filter({ hasNotText: '환불:' })
  await expect(original).toHaveCount(1)
  await expect(original).toContainText('부분 환불')
  await expect(original).toContainText('환불 입금 +₩2,500')
  await sheet.getByLabel('거래 구분').selectOption('EXPENSE')
  await expect(sheet.getByRole('row').filter({ hasText: '환불: 부분 환불 지출' })).toHaveCount(0)
  await expect(original).toContainText('부분 환불')
  await sheet.getByLabel('거래 구분').selectOption('INCOME')
  const linked = sheet.getByRole('row').filter({ hasText: '환불: 부분 환불 지출' })
  await expect(linked).toHaveCount(1)
  await expect(original).toHaveCount(0)
  expect(state.errors).toEqual([])
})
