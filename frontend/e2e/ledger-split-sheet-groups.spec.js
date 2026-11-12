import { expect, test } from '@playwright/test'
import { mockPersonalization } from './fixtures/calendarPersonalization.js'

test('split purchases use one compact row with per-method detail and actions', async ({ page }, testInfo) => {
  await page.clock.setFixedTime(new Date('2026-10-22T12:00:00'))
  const base = { entryDate: '2026-10-22', entryTime: '12:30', entryType: 'EXPENSE', title: '분할 구매',
    categoryGroupId: 101, categoryGroupName: '식비', categoryDetailId: 102, categoryDetailName: '외식', memo: '긴 구매 메모', splitPaymentGroupId: 'group-a', splitPaymentCount: 3 }
  const entries = [
    { ...base, id: 1, splitPaymentIndex: 1, amount: 25000, paymentMethodId: 103, paymentMethodName: '카드 A', installmentMonths: 5, installmentInterestType: 'FREE' },
    { ...base, id: 2, splitPaymentIndex: 2, amount: 15000, paymentMethodId: 104, paymentMethodName: '포인트' },
    { ...base, id: 3, splitPaymentIndex: 3, amount: 10000, paymentMethodId: 105, paymentMethodName: '카드 B', financiallyExcluded: true, cancelledAt: '2026-10-22T13:00:00', hasAdjustmentHistory: true },
    { ...base, id: 4, title: '이동된 수단', splitPaymentGroupId: 'group-b', splitPaymentIndex: 1, splitPaymentCount: 2, amount: 3000, paymentMethodName: '현금' },
    { id: 5, entryDate: '2026-10-22', entryTime: '11:00', entryType: 'INCOME', amount: 2000, title: '일반 수입', categoryGroupName: '기타' },
  ]
  const state = await mockPersonalization(page, { entries })
  await page.waitForLoadState('networkidle')
  await page.getByRole('region', { name: '달력 조회', exact: true }).getByRole('button', { name: '거래 시트 열기' }).click()
  const sheet = page.getByRole('dialog', { name: '거래 시트' })
  const row = sheet.getByRole('row').filter({ hasText: '분할 구매' })
  await expect(row).toHaveCount(1)
  await expect(sheet).toContainText('3건 · 세부 거래 5건')
  await expect(row).toContainText('분할 결제 · 3개 수단')
  await expect(row).toContainText('₩50,000')
  await expect(row).toContainText('처리 이력 있음')
  await expect(sheet.getByRole('row').filter({ hasText: '이동된 수단' })).toContainText('1/2개 표시')
  await sheet.screenshot({ path: testInfo.outputPath('split-group-sheet.png') })
  const groupCheckbox = row.getByRole('checkbox', { name: '분할 구매 분할 거래 선택', includeHidden: true })
  if (await groupCheckbox.isVisible()) {
    await groupCheckbox.check()
    await expect(groupCheckbox).toBeChecked()
  }
  await row.getByRole('button', { name: '상세보기' }).click()
  const detail = page.getByRole('dialog', { name: '분할 결제 상세' })
  await expect(detail.getByText('결제 1/3 · 카드 A')).toBeVisible()
  await expect(detail.getByText('결제 2/3 · 포인트')).toBeVisible()
  await expect(detail.getByText('결제 3/3 · 카드 B')).toBeVisible()
  await expect(detail.getByText('5개월 무이자 할부')).toBeVisible()
  await expect(detail.getByText('결제 취소됨')).toBeVisible()
  await expect(detail.getByText('긴 구매 메모', { exact: true })).toHaveCount(1)
  await expect(detail.getByText('식비 / 외식', { exact: true })).toHaveCount(1)
  await expect(detail).toContainText('환불·취소를 차감한 금액이 아닙니다')
  const bounds = await detail.evaluate(node => { const r = node.getBoundingClientRect(); return { left: r.left, right: r.right, width: innerWidth } })
  expect(bounds.left).toBeGreaterThanOrEqual(0)
  expect(bounds.right).toBeLessThanOrEqual(bounds.width + 1)
  await detail.screenshot({ path: testInfo.outputPath('split-detail.png') })
  const first = detail.locator('.split-detail-part').filter({ hasText: '카드 A' })
  await first.getByRole('button', { name: '수정·처리' }).click()
  const manager = page.getByRole('dialog', { name: '거래 관리' })
  await expect(manager.getByText('카드 A', { exact: false })).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(manager).toHaveCount(0)
  await expect(detail).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(detail).toHaveCount(0)
  await expect(sheet).toBeVisible()
  await sheet.getByRole('row').filter({ hasText: '이동된 수단' }).getByRole('button', { name: '상세보기' }).click()
  await expect(detail).toContainText('현재 날짜와 필터에 해당하는 수단만 표시합니다')
  await detail.getByRole('button', { name: '닫기' }).click()
  await row.getByRole('button', { name: '상세보기' }).click()
  await detail.locator('.split-detail-part').filter({ hasText: '카드 A' }).getByRole('button', { name: '수정·처리' }).click()
  await manager.getByRole('button', { name: /^환불/ }).click()
  const refund = page.getByRole('dialog', { name: '환불 기록' })
  await expect(refund).toContainText('₩25,000')
  await expect(refund).toContainText('분할 결제 1/3 중 이 결제수단의 금액만 처리합니다')
  await expect(detail).toHaveCount(0)
  await refund.getByRole('button', { name: '닫기' }).first().click()
  expect(state.errors).toEqual([])
})

test('edited parts with different details are not hidden and type filtering shows a partial group', async ({ page }) => {
  await page.clock.setFixedTime(new Date('2026-10-22T12:00:00'))
  const common = { entryDate: '2026-10-22', splitPaymentGroupId: 'group-mixed', splitPaymentCount: 2 }
  const entries = [
    { ...common, id: 21, splitPaymentIndex: 1, entryType: 'EXPENSE', entryTime: '10:00', title: '첫 항목', memo: '첫 메모', amount: 12000, paymentMethodName: '카드', categoryGroupName: '식비' },
    { ...common, id: 22, splitPaymentIndex: 2, entryType: 'INCOME', entryTime: '11:00', title: '두 번째 항목', memo: '다른 메모', amount: 8000, paymentMethodName: '현금', categoryGroupName: '수입' },
  ]
  const state = await mockPersonalization(page, { entries })
  await page.waitForLoadState('networkidle')
  await page.getByRole('region', { name: '달력 조회', exact: true }).getByRole('button', { name: '거래 시트 열기' }).click()
  const sheet = page.getByRole('dialog', { name: '거래 시트' })
  const row = sheet.getByRole('row').filter({ hasText: '첫 항목' })
  await expect(row).toContainText('수단별로 다름')
  await expect(row).toContainText('혼합')
  await row.getByRole('button', { name: '상세보기' }).click()
  const detail = page.getByRole('dialog', { name: '분할 결제 상세' })
  await expect(detail.getByText('첫 메모', { exact: true })).toBeVisible()
  await expect(detail.getByText('다른 메모', { exact: true })).toBeVisible()
  await expect(detail.getByText('두 번째 항목', { exact: true })).toBeVisible()
  await detail.getByRole('button', { name: '닫기' }).click()
  await sheet.getByLabel('거래 구분').selectOption('EXPENSE')
  await expect(row).toContainText('1/2개 표시')
  await expect(row).toContainText('₩12,000')
  await row.getByRole('button', { name: '상세보기' }).click()
  await expect(detail).toContainText('현재 날짜와 필터에 해당하는 수단만 표시합니다')
  await expect(detail.getByText('다른 메모', { exact: true })).toHaveCount(0)
  expect(state.errors).toEqual([])
})
