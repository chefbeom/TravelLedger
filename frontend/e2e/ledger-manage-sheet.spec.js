import { expect, test } from '@playwright/test'
import { mockPersonalization } from './fixtures/calendarPersonalization.js'

test('transaction manager opens editing and keeps deletion confirmation', async ({ page }, testInfo) => {
  await page.clock.setFixedTime(new Date('2026-10-22T12:00:00'))
  const entries = [{ id: 7, entryDate: '2026-10-22', entryTime: '12:30', entryType: 'EXPENSE', amount: 15000,
    title: '관리 검증 거래', categoryGroupId: 101, categoryGroupName: '식비', categoryDetailId: 102,
    paymentMethodId: 103, paymentMethodName: '카드' }]
  const state = await mockPersonalization(page, { entries })
  let deletes = 0
  await page.route('**/api/entries/7', route => {
    if (route.request().method() === 'DELETE') { deletes++; entries.splice(0, entries.length); return route.fulfill({ status: 204, body: '' }) }
    return route.fallback()
  })
  const openSheet = () => page.getByRole('region', { name: '달력 조회', exact: true }).getByRole('button', { name: '거래 시트 열기' }).click()
  await openSheet()
  let sheet = page.getByRole('dialog', { name: '거래 시트' })
  let row = sheet.getByRole('row').filter({ hasText: '관리 검증 거래' })
  await row.getByRole('button', { name: '관리', exact: true }).click()
  let manager = page.getByRole('dialog', { name: '거래 관리' })
  await expect(manager.getByText('관리 검증 거래')).toBeVisible()
  const geometry = await manager.evaluate(element => {
    const box = element.getBoundingClientRect()
    const close = element.querySelector('[data-modal-close]').getBoundingClientRect()
    return { left: box.left, right: box.right, viewport: innerWidth, closeWidth: close.width }
  })
  expect(geometry.left).toBeGreaterThanOrEqual(0)
  expect(geometry.right).toBeLessThanOrEqual(geometry.viewport)
  expect(geometry.closeWidth).toBeGreaterThanOrEqual(44)
  await manager.screenshot({ path: testInfo.outputPath('transaction-manager.png') })
  await manager.getByRole('button', { name: '거래 내용 수정', exact: true }).click()
  await expect(sheet).toHaveCount(0)
  await expect(page.getByText('거래 수정 중')).toBeVisible()
  await expect(page.getByRole('textbox', { name: '거래 제목' })).toHaveValue('관리 검증 거래')
  await openSheet()
  sheet = page.getByRole('dialog', { name: '거래 시트' })
  row = sheet.getByRole('row').filter({ hasText: '관리 검증 거래' })
  await row.getByRole('button', { name: '관리', exact: true }).click()
  manager = page.getByRole('dialog', { name: '거래 관리' })
  let nativeDialogs = 0
  page.on('dialog', dialog => { nativeDialogs += 1; dialog.dismiss() })
  await manager.getByRole('button', { name: '삭제', exact: true }).click()
  await expect(manager.getByRole('region', { name: '거래 삭제' })).toBeVisible()
  await manager.getByRole('button', { name: '돌아가기' }).click()
  expect(deletes).toBe(0)
  await expect(manager.getByRole('region', { name: '거래 삭제' })).toHaveCount(0)
  await expect(row).toBeVisible()
  await manager.getByRole('button', { name: '삭제', exact: true }).click()
  await manager.getByRole('button', { name: '삭제하기' }).click()
  await expect.poll(() => deletes).toBe(1)
  await expect(row).toHaveCount(0)
  expect(nativeDialogs).toBe(0)
  expect(state.errors).toEqual([])
})

test('management options reflect transaction type and prior processing', async ({ page }) => {
  await page.clock.setFixedTime(new Date('2026-10-22T12:00:00'))
  const common = { entryDate: '2026-10-22', entryTime: '12:30', amount: 10000, categoryGroupName: '식비', paymentMethodName: '카드' }
  const entries = [
    { ...common, id: 41, entryType: 'EXPENSE', title: '일반 지출' },
    { ...common, id: 42, entryType: 'INCOME', title: '일반 수입' },
    { ...common, id: 43, entryType: 'EXPENSE', title: '여행 연결 지출', travelRecordId: 50 },
    { ...common, id: 44, entryType: 'EXPENSE', title: '부분 환불 지출', hasAdjustmentHistory: true, refundedPrincipal: 3000 },
  ]
  const partialRefund = { ...common, id: 45, entryType: 'INCOME', title: '환불: 부분 환불 지출', amount: 3000, adjustmentType: 'REFUND',
    adjustmentOriginId: 44, adjustmentPrincipal: 3000, adjustmentDeduction: 0, adjustmentExtra: 0 }
  const state = await mockPersonalization(page, { entries })
  await page.route('**/api/entries/44/adjustment-group', route => route.fulfill({ json: { origin: entries[3], adjustments: [partialRefund] } }))
  await page.waitForLoadState('networkidle')
  await page.getByRole('region', { name: '달력 조회', exact: true }).getByRole('button', { name: '거래 시트 열기' }).click()
  const sheet = page.getByRole('dialog', { name: '거래 시트' })
  const manager = page.getByRole('dialog', { name: '거래 관리' })
  const normalRow = sheet.getByRole('row').filter({ hasText: '일반 지출' })
  const trigger = normalRow.getByRole('button', { name: '관리', exact: true })
  await expect(normalRow.getByRole('button', { name: /환불|결제 취소|삭제/ })).toHaveCount(0)
  await expect(normalRow.getByRole('button', { name: '공유', exact: true })).toBeVisible()
  await expect(trigger).toHaveAttribute('aria-haspopup', 'dialog')
  await trigger.click()
  for (const name of ['거래 내용 수정', '환불', '결제 취소', '삭제']) {
    await expect(manager.getByRole('button', { name, exact: true })).toBeVisible()
    await expect(manager.getByRole('button', { name, exact: true })).not.toHaveAttribute('aria-disabled', 'true')
  }
  await expect(manager.getByRole('button', { name: '환불 취소', exact: true })).toHaveCount(0)
  await page.keyboard.press('Escape')
  await expect(manager).toHaveCount(0)
  await expect(trigger).toBeFocused()
  await expect(sheet).toBeVisible()

  await sheet.getByRole('row').filter({ hasText: '일반 수입' }).getByRole('button', { name: '관리', exact: true }).click()
  await expect(manager.getByRole('button', { name: '거래 내용 수정', exact: true })).toBeVisible()
  await expect(manager.getByRole('button', { name: '삭제', exact: true })).toBeVisible()
  await expect(manager.getByRole('button', { name: /^환불|^결제 취소/ })).toHaveCount(0)
  await manager.getByRole('button', { name: '닫기', exact: true }).click()

  await sheet.getByRole('row').filter({ hasText: '여행 연결 지출' }).getByRole('button', { name: '관리', exact: true }).click()
  await expect(manager.getByRole('button', { name: '환불', exact: true })).toBeVisible()
  await expect(manager.getByRole('button', { name: '결제 취소', exact: true })).toHaveAttribute('aria-disabled', 'true')
  await expect(manager.getByRole('button', { name: '결제 취소', exact: true })).toHaveAccessibleDescription('여행 기록과 연결된 거래는 결제 취소할 수 없습니다.')
  await manager.getByRole('button', { name: '닫기', exact: true }).click()

  await sheet.getByRole('row').filter({ hasText: '부분 환불 지출' }).getByRole('button', { name: '관리', exact: true }).click()
  for (const name of ['환불', '환불 취소', '거래 내용 수정']) {
    await expect(manager.getByRole('button', { name, exact: true })).toBeVisible()
    await expect(manager.getByRole('button', { name, exact: true })).not.toHaveAttribute('aria-disabled', 'true')
  }
  await expect(manager.getByRole('button', { name: '거래 내용 수정', exact: true })).toHaveAccessibleDescription(/제목·메모·분류·날짜·시간만/)
  for (const name of ['결제 취소', '삭제']) {
    await expect(manager.getByRole('button', { name, exact: true })).toHaveAttribute('aria-disabled', 'true')
  }
  await expect(manager.getByRole('heading', { name: '처리 이력' })).toBeVisible()
  await expect(manager.getByRole('button', { name: /^처리 내역/ })).toHaveCount(0)
  await expect(manager).toContainText('처리가 적용 중이라 금액')
  await expect(manager).not.toContainText('기록 보존을 위해')
  expect(state.errors).toEqual([])
})
