import { expect, test } from '@playwright/test'
import { calendarSettings, mockPersonalization } from './fixtures/calendarPersonalization.js'

const entries = [
  { id: 1, entryDate: '2026-10-01', title: '10월 첫날', entryType: 'EXPENSE', amount: 12000 },
  { id: 2, entryDate: '2026-10-30', title: '30일 지출', entryType: 'EXPENSE', amount: 15000 },
  { id: 3, entryDate: '2026-10-30', title: '30일 수입', entryType: 'INCOME', amount: 30000 },
  { id: 4, entryDate: '2026-10-31', title: '31일 지출', entryType: 'EXPENSE', amount: 16000 },
  { id: 5, entryDate: '2026-11-01', title: '11월 첫날', entryType: 'EXPENSE', amount: 17000 },
  { id: 6, entryDate: '2026-11-02', title: '11월 둘째 날', entryType: 'EXPENSE', amount: 18000 },
  { id: 7, entryDate: '2026-12-31', title: '올해 마지막 날', entryType: 'EXPENSE', amount: 19000 },
  { id: 8, entryDate: '2027-01-01', title: '새해 첫날', entryType: 'EXPENSE', amount: 20000 },
].map(item => ({ entryTime: '12:30', categoryGroupName: '식비', categoryDetailName: '외식', paymentMethodName: '카드', ...item }))

const quickDate = page => page.locator('.household-entry-panel').getByLabel('거래 날짜', { exact: true })
const query = page => page.getByRole('region', { name: '달력 조회', exact: true })
const modal = page => page.getByRole('dialog', { name: '거래 시트', exact: true })

async function setup(page, options = {}) {
  await page.clock.setFixedTime(new Date('2026-10-30T12:00:00'))
  const state = await mockPersonalization(page, { entries, ...options })
  await expect(quickDate(page)).toHaveValue('2026-10-30')
  return state
}

async function openSheet(page) {
  await query(page).getByRole('button', { name: '거래 시트 열기', exact: true }).click()
  await expect(modal(page)).toBeVisible()
  return modal(page)
}

test('sheet navigation retains the modal and filters while syncing the selected date across months', async ({ page }) => {
  const state = await setup(page)
  const sheet = await openSheet(page)
  await expect(sheet.getByRole('heading', { name: '10월 30일 거래 시트' })).toBeVisible()
  await expect(sheet.getByText('30일 수입')).toBeVisible()
  await sheet.getByLabel('거래 구분').selectOption('EXPENSE')
  await expect(sheet.getByText('30일 수입')).toHaveCount(0)
  const checkbox = sheet.locator('tbody input[type="checkbox"]').first()
  const canSelect = await checkbox.isVisible()
  if (canSelect) await checkbox.check()
  await sheet.getByRole('button', { name: '다음 날', exact: true }).click()
  await expect(sheet.getByRole('heading', { name: '10월 31일 거래 시트' })).toBeVisible()
  await expect(sheet.getByText('31일 지출')).toBeVisible()
  if (canSelect) await expect(sheet.locator('tbody input[type="checkbox"]').first()).not.toBeChecked()
  await sheet.getByRole('button', { name: '다음 날', exact: true }).click()
  await expect(sheet.getByRole('heading', { name: '11월 1일 거래 시트' })).toBeVisible()
  await expect(sheet.getByText('11월 첫날')).toBeVisible()
  await expect(query(page).getByRole('combobox', { name: '월', exact: true })).toHaveValue('11')
  await expect(quickDate(page)).toHaveValue('2026-11-01')
  await expect(sheet.getByLabel('거래 구분')).toHaveValue('EXPENSE')
  await sheet.getByRole('button', { name: '이전 날', exact: true }).click()
  await expect(sheet.getByRole('heading', { name: '10월 31일 거래 시트' })).toBeVisible()
  await expect(sheet.getByText('31일 지출')).toBeVisible()
  await expect(quickDate(page)).toHaveValue('2026-10-31')
  await sheet.getByRole('button', { name: '닫기', exact: true }).click()
  await expect(modal(page)).toHaveCount(0)
  expect(state.writes).toEqual([])
  expect(state.errors).toEqual([])
})

test('inline sheet supports year boundaries and empty days', async ({ page }) => {
  const saved = calendarSettings()
  saved.preferences.transactionSheetViewMode = 'inline'
  const state = await setup(page, { saved })
  await query(page).getByRole('combobox', { name: '월', exact: true }).selectOption('12')
  await quickDate(page).fill('2026-12-31')
  const sheet = page.locator('[data-calendar-panel-id="sheet"] .household-sheet-panel')
  await expect(sheet.getByRole('heading', { name: '12월 31일 거래 시트' })).toBeVisible()
  await expect(sheet.getByText('올해 마지막 날')).toBeVisible()
  await sheet.getByRole('button', { name: '다음 날', exact: true }).click()
  await expect(sheet.getByRole('heading', { name: '1월 1일 거래 시트' })).toBeVisible()
  await expect(sheet.getByText('새해 첫날')).toBeVisible()
  await expect(query(page).getByRole('combobox', { name: '연도', exact: true })).toHaveValue('2027')
  await sheet.getByRole('button', { name: '다음 날', exact: true }).click()
  await expect(sheet.getByText('선택한 날짜에는 거래가 없습니다.')).toBeVisible()
  await sheet.getByRole('button', { name: '이전 날', exact: true }).click()
  await expect(sheet.getByText('새해 첫날')).toBeVisible()
  expect(state.writes).toEqual([])
  expect(state.errors).toEqual([])
})

test('late month responses cannot restore an old day or overwrite newer month entries', async ({ page }) => {
  const state = await setup(page)
  let releaseNovember
  let completedNovember = 0
  const novemberGate = new Promise(resolve => { releaseNovember = resolve })
  await page.route('**/api/entries?*', async route => {
    const url = new URL(route.request().url())
    const from = url.searchParams.get('from') || ''
    const to = url.searchParams.get('to') || ''
    if (from === '2026-10-25') await novemberGate
    await route.fulfill({ json: entries.filter(item => item.entryDate >= from && item.entryDate <= to) })
    if (from === '2026-10-25') completedNovember += 1
  })
  await quickDate(page).fill('2026-10-31')
  const sheet = await openSheet(page)
  await sheet.getByRole('button', { name: '다음 날', exact: true }).click()
  await expect(sheet.getByRole('heading', { name: '11월 1일 거래 시트' })).toBeVisible()
  await sheet.getByRole('button', { name: '다음 날', exact: true }).click()
  await expect(sheet.getByRole('heading', { name: '11월 2일 거래 시트' })).toBeVisible()
  await expect(quickDate(page)).toHaveValue('2026-11-02')
  await sheet.getByRole('button', { name: '이전 날', exact: true }).click()
  await sheet.getByRole('button', { name: '이전 날', exact: true }).click()
  await expect(sheet.getByRole('heading', { name: '10월 31일 거래 시트' })).toBeVisible()
  await expect(quickDate(page)).toHaveValue('2026-10-31')
  await page.getByRole('dialog', { name: '거래 시트' }).getByRole('button', { name: '닫기', exact: true }).click()
  await quickDate(page).fill('2026-10-01')
  await expect(quickDate(page)).toHaveValue('2026-10-01')
  const reopened = await openSheet(page)
  await expect(reopened.getByText('10월 첫날')).toBeVisible()
  releaseNovember()
  await expect.poll(() => completedNovember).toBeGreaterThan(0)
  await expect(reopened.getByRole('heading', { name: '10월 1일 거래 시트' })).toBeVisible()
  await expect(reopened.getByText('10월 첫날')).toBeVisible()
  expect(state.errors).toEqual([])
})

test('moving again while the new month loads does not reset the selected day after loading', async ({ page }) => {
  const state = await setup(page)
  let releaseNovember
  let completedNovember = 0
  const novemberGate = new Promise(resolve => { releaseNovember = resolve })
  await page.route('**/api/entries?*', async route => {
    const url = new URL(route.request().url())
    const from = url.searchParams.get('from') || ''
    const to = url.searchParams.get('to') || ''
    if (from === '2026-10-25') await novemberGate
    await route.fulfill({ json: entries.filter(item => item.entryDate >= from && item.entryDate <= to) })
    if (from === '2026-10-25') completedNovember += 1
  })
  await quickDate(page).fill('2026-10-31')
  const sheet = await openSheet(page)
  await sheet.getByRole('button', { name: '다음 날', exact: true }).click()
  await expect(sheet.getByRole('heading', { name: '11월 1일 거래 시트' })).toBeVisible()
  await sheet.getByRole('button', { name: '다음 날', exact: true }).click()
  await expect(sheet.getByRole('heading', { name: '11월 2일 거래 시트' })).toBeVisible()
  releaseNovember()
  await expect.poll(() => completedNovember).toBeGreaterThan(0)
  await expect(sheet.getByRole('heading', { name: '11월 2일 거래 시트' })).toBeVisible()
  await expect(sheet.getByText('11월 둘째 날')).toBeVisible()
  await expect(quickDate(page)).toHaveValue('2026-11-02')
  expect(state.errors).toEqual([])
})

test('sheet navigation does not overwrite an unsaved edit', async ({ page }) => {
  const state = await setup(page)
  const sheet = await openSheet(page)
  await sheet.getByRole('row').filter({ hasText: '30일 지출' }).getByRole('button', { name: '수정', exact: true }).click()
  const title = page.locator('.household-entry-panel').getByRole('textbox', { name: '거래 제목', exact: true })
  await expect(title).toHaveValue('30일 지출')
  await title.fill('저장하지 않은 변경')
  const reopened = await openSheet(page)
  await reopened.getByRole('button', { name: '다음 날', exact: true }).click()
  await reopened.getByRole('button', { name: '다음 날', exact: true }).click()
  await expect(reopened.getByRole('heading', { name: '11월 1일 거래 시트' })).toBeVisible()
  await expect(reopened.getByText('11월 첫날')).toBeVisible()
  await expect(quickDate(page)).toHaveValue('2026-10-30')
  await expect(title).toHaveValue('저장하지 않은 변경')
  expect(state.errors).toEqual([])
})

test('week view navigation across a month boundary stays on the requested day', async ({ page }) => {
  const state = await setup(page)
  await query(page).getByRole('combobox', { name: '월', exact: true }).selectOption('9')
  await query(page).getByRole('combobox', { name: '달력 보기', exact: true }).selectOption('current')
  await quickDate(page).fill('2026-09-30')
  const sheet = await openSheet(page)
  await sheet.getByRole('button', { name: '다음 날', exact: true }).click()
  await expect(sheet.getByRole('heading', { name: '10월 1일 거래 시트' })).toBeVisible()
  await expect(sheet.getByText('10월 첫날')).toBeVisible()
  await expect(quickDate(page)).toHaveValue('2026-10-01')
  await expect(page.locator('.calendar__day--selected')).toHaveCount(1)
  await sheet.getByRole('button', { name: '닫기', exact: true }).click()
  await query(page).getByRole('combobox', { name: '월', exact: true }).selectOption('11')
  await query(page).getByRole('combobox', { name: '월', exact: true }).selectOption('10')
  await expect(quickDate(page)).toHaveValue('2026-10-01')
  expect(state.errors).toEqual([])
})

test('date controls remain accessible and inside the sheet at narrow and wide widths', async ({ page }, testInfo) => {
  const state = await setup(page)
  const sheet = await openSheet(page)
  for (const width of [320, 390, 768, 1440]) {
    await page.setViewportSize({ width, height: 900 })
    const bounds = await sheet.locator('.household-sheet-date-nav').evaluate(nav => {
      const sheetRect = nav.closest('.household-sheet-panel').getBoundingClientRect()
      return [...nav.querySelectorAll('button, h2')].map(element => {
        const rect = element.getBoundingClientRect()
        return { left: rect.left - sheetRect.left, right: rect.right - sheetRect.right, height: rect.height, tag: element.tagName }
      })
    })
    for (const element of bounds) {
      expect(element.left).toBeGreaterThanOrEqual(0)
      expect(element.right).toBeLessThanOrEqual(1)
      if (element.tag === 'BUTTON') expect(element.height).toBeGreaterThanOrEqual(44)
    }
    if (width === 320 || width === 1440) await sheet.screenshot({ path: testInfo.outputPath(`sheet-${width}.png`) })
  }
  await sheet.getByRole('button', { name: '다음 날', exact: true }).focus()
  await page.keyboard.press('Enter')
  await expect(sheet.getByRole('heading', { name: '10월 31일 거래 시트' })).toBeVisible()
  await sheet.getByRole('button', { name: '닫기', exact: true }).click()
  await page.getByRole('button', { name: '다크 테마', exact: true }).click()
  const darkSheet = await openSheet(page)
  await darkSheet.getByRole('button', { name: '이전 날', exact: true }).click()
  await expect(darkSheet.getByRole('heading', { name: '10월 30일 거래 시트' })).toBeVisible()
  await darkSheet.screenshot({ path: testInfo.outputPath('sheet-dark.png') })
  expect(state.errors).toEqual([])
})
