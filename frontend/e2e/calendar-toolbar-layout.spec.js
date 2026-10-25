import { expect, test } from '@playwright/test'
import { mockPersonalization } from './fixtures/calendarPersonalization.js'

const calendar = page => page.locator('[data-calendar-panel-id="calendar"]')
const query = page => page.getByRole('region', { name: '달력 조회', exact: true })

async function geometry(page) {
  return calendar(page).evaluate(palette => {
    const origin = palette.getBoundingClientRect()
    return ['.calendar-query-toolbar__heading', '.calendar-shell', '.calendar__weekdays', '.calendar__weeks'].map(selector => {
      const rect = palette.querySelector(selector).getBoundingClientRect()
      return { selector, x: rect.x - origin.x, y: rect.y - origin.y, width: rect.width, height: rect.height }
    })
  })
}

async function expectGeometry(page, before) {
  await expect.poll(async () => Math.max(...(await geometry(page)).flatMap((rect, index) => ['x', 'y', 'width', 'height'].map(key => Math.abs(rect[key] - before[index][key]))))).toBeLessThan(1)
}

test('one full-width query strip sits above calendar and quick entry, with identical edit and saved geometry', async ({ page }, testInfo) => {
  test.setTimeout(90_000)
  const state = await mockPersonalization(page)
  for (const width of [1440, 1024, 390, 320]) {
    await page.setViewportSize({ width, height: 900 })
    for (const theme of ['default', 'toss']) {
      if (((await page.locator('html').getAttribute('data-theme')) === 'toss') !== (theme === 'toss')) await page.getByRole('button', { name: theme === 'toss' ? '다크 테마' : '기본 테마', exact: true }).click()
      const settings = page.getByRole('region', { name: '대시보드 설정', exact: true })
      await expect(settings.getByRole('button', { name: '개인화 설정', exact: true })).toBeVisible()
      await expect(settings.getByRole('button', { name: '대시보드 편집', exact: true })).toBeVisible()
      await expect(settings.locator('select')).toHaveCount(0)
      await expect(settings.getByRole('button', { name: '거래 시트 열기', exact: true })).toHaveCount(0)
      await expect(query(page).getByRole('combobox', { name: '연도', exact: true })).toHaveCount(1)
      await expect(query(page).getByRole('combobox', { name: '월', exact: true })).toHaveCount(1)
      await expect(query(page).getByRole('combobox', { name: '달력 보기', exact: true })).toHaveCount(1)
      await expect(query(page).getByRole('combobox', { name: '표시 기준', exact: true })).toHaveCount(1)
      await expect(page.locator('.household-calendar-panel-dragbar')).toHaveCount(0)
      await expect.poll(() => query(page).evaluate(toolbar => toolbar.getBoundingClientRect().bottom - Math.min(...[...document.querySelectorAll('[data-calendar-panel-id="calendar"], [data-calendar-panel-id="quick-entry"]')].map(element => element.getBoundingClientRect().top))), { message: 'After a responsive transition, the shared strip must remain above both primary palettes' }).toBeLessThanOrEqual(1)
      const bounds = await query(page).evaluate(toolbar => {
        const rect = toolbar.getBoundingClientRect(), palette = toolbar.closest('.calendar-query-item').getBoundingClientRect()
        const controls = [...toolbar.querySelectorAll('button, select')].map(control => { const r = control.getBoundingClientRect(); return { left: r.left, right: r.right, top: r.top, bottom: r.bottom, height: r.height } })
        const primary = [...document.querySelectorAll('[data-calendar-panel-id="calendar"], [data-calendar-panel-id="quick-entry"]')].map(element => element.getBoundingClientRect().top)
        return { rect: { left: rect.left, right: rect.right, bottom: rect.bottom }, palette: { left: palette.left, right: palette.right, bottom: palette.bottom }, controls, gridTop: Math.min(...primary) }
      })
      expect(bounds.rect.bottom).toBeLessThanOrEqual(bounds.gridTop + 1)
      await expect(calendar(page).locator('.calendar-query-toolbar')).toHaveCount(0)
      await expect(page.locator('[data-calendar-panel-id="quick-entry"] .calendar-query-toolbar')).toHaveCount(0)
      if (width >= 1024) expect(Math.max(...bounds.controls.map(item => item.top)) - Math.min(...bounds.controls.map(item => item.top))).toBeLessThan(1)
      for (const control of bounds.controls) {
        expect(control.left).toBeGreaterThanOrEqual(bounds.palette.left)
        expect(control.right).toBeLessThanOrEqual(bounds.palette.right + 1)
        expect(control.bottom).toBeLessThanOrEqual(bounds.palette.bottom + 1)
        expect(control.height).toBeGreaterThanOrEqual(width < 760 ? 44 : 40)
      }
      expect(await calendar(page).locator('.calendar__metrics').evaluateAll(items => items.filter(item => item.getBoundingClientRect().height > 0 && item.scrollHeight > item.clientHeight + 1).length), 'Moving the toolbar must not squash the income/expense details').toBe(0)
      const settingsBox = await settings.boundingBox(), boardBox = await page.locator('.calendar-dashboard').boundingBox()
      expect(settingsBox.y + settingsBox.height).toBeLessThanOrEqual(boardBox.y)
      const before = await geometry(page)
      await settings.getByRole('button', { name: '대시보드 편집', exact: true }).click()
      await expect(page.locator('.calendar-dashboard')).toHaveClass(/calendar-dashboard--editing/)
      await expect(page.locator('.household-calendar-panel-dragbar')).toBeVisible()
      await expect(page.locator('.household-calendar-panel-dragbar')).toHaveCSS('cursor', 'grab')
      await expectGeometry(page, before)
      await page.locator('.calendar-draft-toolbar').getByRole('button', { name: '취소', exact: true }).click()
      await expect(page.locator('.household-calendar-panel-dragbar')).toHaveCount(0)
      await expectGeometry(page, before)
      if (width === 1440 || width === 390) {
        await query(page).evaluate(element => element.scrollIntoView({ block: 'start', behavior: 'instant' }))
        await page.screenshot({ path: testInfo.outputPath(`${theme}-${width}-calendar-toolbar.png`) })
      }
    }
  }
  expect(state.writes).toEqual([])
  expect(state.errors).toEqual([])
})

test('moved date and view controls retain navigation, temporary filters and opening the transaction sheet', async ({ page }) => {
  const state = await mockPersonalization(page)
  await query(page).getByRole('combobox', { name: '월', exact: true }).selectOption('8')
  await expect(query(page).getByRole('combobox', { name: '월', exact: true })).toHaveValue('8')
  await query(page).getByRole('button', { name: '이전 월', exact: true }).click()
  await expect(query(page).getByRole('combobox', { name: '월', exact: true })).toHaveValue('7')
  await query(page).getByRole('button', { name: '다음 월', exact: true }).click()
  await expect(query(page).getByRole('combobox', { name: '월', exact: true })).toHaveValue('8')
  await query(page).getByRole('combobox', { name: '연도', exact: true }).selectOption('2025')
  await expect(query(page).getByRole('combobox', { name: '연도', exact: true })).toHaveValue('2025')
  await query(page).getByRole('button', { name: '오늘', exact: true }).click()
  const today = await page.evaluate(() => new Date().getMonth() + 1)
  await expect(query(page).getByRole('combobox', { name: '월', exact: true })).toHaveValue(String(today))
  await query(page).getByRole('combobox', { name: '달력 보기', exact: true }).selectOption('previous')
  await query(page).getByRole('combobox', { name: '이전 주 범위', exact: true }).selectOption('2')
  await query(page).getByRole('combobox', { name: '표시 기준', exact: true }).selectOption('expense')
  await expect(calendar(page).locator('.calendar__week')).toHaveCount(3)
  await query(page).getByRole('combobox', { name: '달력 보기', exact: true }).selectOption('current')
  await expect(calendar(page).locator('.calendar__week')).toHaveCount(1)
  await query(page).getByRole('button', { name: '거래 시트 열기', exact: true }).click()
  await expect(page.getByRole('dialog', { name: /거래 시트/ })).toBeVisible()
  await page.getByRole('dialog', { name: /거래 시트/ }).getByRole('button', { name: '닫기', exact: true }).click()
  expect(state.writes).toEqual([])
  expect(state.errors).toEqual([])
})
