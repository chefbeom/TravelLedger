import { expect, test } from '@playwright/test'
import { calendarSettings, mockPersonalization } from './fixtures/calendarPersonalization.js'
import { ensureCalendarQueryPalette } from '../src/lib/calendarQueryLayout.js'

const baseURL = process.env.E2E_BASE_URL || process.env.PLAYWRIGHT_BASE_URL || 'http://127.0.0.1:5173'
const panels = page => page.locator('.calendar-dashboard-grid > [data-calendar-panel-id]')
const coreLayout = layout => layout.map(({ id, x, y, w, h }) => ({ id, x, y, w, h }))

async function geometry(page) {
  return panels(page).evaluateAll(elements => elements.map(element => ({
    id: element.dataset.calendarPanelId,
    column: element.style.gridColumn,
    row: element.style.gridRow,
    width: Math.round(element.getBoundingClientRect().width),
    height: Math.round(element.getBoundingClientRect().height),
  })))
}

test('mobile palettes use the viewport width without root style leaks', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const state = await mockPersonalization(page)
  await expect(page.locator('html')).toHaveAttribute('data-layout-mode', 'mobile')
  await expect.poll(() => page.evaluate(() => Math.round(document.documentElement.getBoundingClientRect().width))).toBe(390)
  await expect.poll(() => page.locator('.calendar-dashboard-grid').evaluate(element => Math.round(element.getBoundingClientRect().width))).toBeGreaterThan(350)
  for (const width of [320, 430, 760]) {
    await page.setViewportSize({ width, height: 844 })
    await expect.poll(() => page.evaluate(() => Math.round(document.documentElement.getBoundingClientRect().width))).toBe(width)
  }
  await page.setViewportSize({ width: 390, height: 844 })
  await page.getByRole('button', { name: '가계부 분석', exact: true }).click()
  await expect(page.getByRole('heading', { name: '가계부 분석', exact: true })).toBeVisible()
  await expect.poll(() => page.evaluate(() => Math.round(document.documentElement.getBoundingClientRect().width))).toBe(390)
  await page.getByRole('button', { name: '달력 가계부', exact: true }).click()
  await expect.poll(() => page.evaluate(() => Math.round(document.documentElement.getBoundingClientRect().width))).toBe(390)
  expect(state.writes).toEqual([])
  expect(state.errors).toEqual([])
})

test('mobile palette and widget edits preserve desktop layout and survive another mobile session', async ({ browser }, info) => {
  test.skip(info.project.name !== 'chromium-desktop', 'This case creates both desktop and mobile browser contexts itself.')
  const desktop = await browser.newContext({ baseURL, viewport: { width: 1440, height: 900 } })
  const mobile = await browser.newContext({ baseURL, viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, deviceScaleFactor: 2 })
  try {
    const desktopPage = await desktop.newPage()
    const saved = calendarSettings()
    saved.layout = ensureCalendarQueryPalette(saved.layout)
    saved.widgets[0].cardColumns = 2
    saved.widgets[0].cardRows = 1
    const state = await mockPersonalization(desktopPage, { saved })
    await expect.poll(() => desktopPage.locator('.calendar-dashboard-grid').evaluate(grid => grid.querySelector('[data-calendar-panel-id="calendar"]').style.gridRow)).not.toBe('')
    const originalLayout = coreLayout(structuredClone(state.saved.layout))
    const originalWidgets = structuredClone(state.saved.widgets)
    const originalDesktop = await geometry(desktopPage)

    const phone = await mobile.newPage()
    await mockPersonalization(phone, { sharedStore: state })
    await phone.getByRole('button', { name: '대시보드 편집', exact: true }).click()
    await phone.locator('[data-calendar-panel-id="aggregate"]').getByRole('button', { name: '사용자 설정 집계 팔레트 이동', exact: true }).press('ArrowDown')
    await phone.locator('[data-calendar-panel-id="calendar"]').getByRole('button', { name: '달력 팔레트 크기 조절', exact: true }).press('ArrowDown')
    const first = phone.locator('.calendar-widget[data-aggregate-index="0"]')
    await first.getByRole('button', { name: /위젯 이동/ }).press('ArrowDown')
    await first.getByRole('button', { name: /위젯 설정/ }).click()
    const inspector = phone.getByRole('region', { name: '위젯 직접 편집', exact: true })
    await expect(inspector.getByRole('combobox', { name: '카드 높이 (칸)', exact: true })).toHaveValue('')
    await expect(inspector.getByRole('button', { name: '앞으로', exact: true })).toBeEnabled()
    await inspector.getByRole('button', { name: '앞으로', exact: true }).click()
    await expect(phone.locator('.calendar-widget').first()).toHaveAttribute('data-aggregate-index', '0')
    await first.getByRole('button', { name: /위젯 설정/ }).click()
    await expect(inspector.getByRole('button', { name: '뒤로', exact: true })).toBeEnabled()
    await inspector.getByRole('button', { name: '뒤로', exact: true }).click()
    await first.getByRole('button', { name: /위젯 크기 조절/ }).press('ArrowDown')
    await phone.locator('.calendar-draft-toolbar').getByRole('button', { name: '변경사항 저장', exact: true }).click()
    await expect(phone.locator('.calendar-draft-toolbar')).toHaveCount(0)

    expect(coreLayout(state.saved.layout)).toEqual(originalLayout)
    expect(state.saved.widgets[0].cardColumns).toBe(originalWidgets[0].cardColumns)
    expect(state.saved.widgets[0].cardRows).toBe(originalWidgets[0].cardRows)
    const aggregate = state.saved.layout.find(item => item.id === 'aggregate')
    expect(aggregate.mobileOrder).toBeGreaterThan(0)
    expect(aggregate.mobileWidgetOrder.slice(0, 2)).toEqual([1, 0])
    expect(aggregate.mobileWidgetRows[0]).toBeGreaterThan(0)
    expect(state.saved.layout.find(item => item.id === 'calendar').mobileHeight).toBeGreaterThan(originalLayout.find(item => item.id === 'calendar').h)

    await desktopPage.reload()
    await desktopPage.getByRole('button', { name: '달력 가계부', exact: true }).click()
    await expect.poll(() => geometry(desktopPage)).toEqual(originalDesktop)
    await phone.reload()
    await phone.getByRole('button', { name: '달력 가계부', exact: true }).click()
    await expect.poll(() => phone.locator('.calendar-widget').first().getAttribute('data-aggregate-index')).toBe('1')
    await expect.poll(() => panels(phone).evaluateAll(elements => elements.sort((a, b) => a.getBoundingClientRect().top - b.getBoundingClientRect().top)[0].dataset.calendarPanelId)).toBe('calendar-query')
    expect(state.writes).toHaveLength(1)
    expect(state.errors).toEqual([])
  } finally {
    await mobile.close()
    await desktop.close()
  }
})
