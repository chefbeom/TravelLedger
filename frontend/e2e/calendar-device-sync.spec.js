import { expect, test } from '@playwright/test'
import { ensureCalendarQueryPalette } from '../src/lib/calendarQueryLayout.js'
import { calendarSettings, mockPersonalization } from './fixtures/calendarPersonalization.js'

const widget = page => page.locator('.calendar-widget[data-aggregate-index="0"]')
const editor = page => page.getByRole('dialog', { name: '개인화 설정', exact: true })

async function openSettings(page) {
  await page.getByRole('button', { name: '개인화 설정', exact: true }).click()
  await expect(editor(page)).toHaveAttribute('aria-busy', 'false')
}

async function tab(page, name) {
  await editor(page).getByRole('tab', { name, exact: false }).click()
}

async function saveSettings(page) {
  await editor(page).getByRole('button', { name: '변경사항 저장', exact: true }).click()
  await expect(editor(page)).toHaveCount(0)
}

test('separate computers restore the complete account settings and old device caches cannot replace later saves', async ({ page, browser }) => {
  const state = await mockPersonalization(page, { layoutUpdatedAt: '2026-10-20T12:00:00Z' })
  await openSettings(page)
  await tab(page, '집계 위젯')
  await editor(page).getByLabel('위젯 이름', { exact: true }).fill('다른 컴퓨터에서도 유지')
  await editor(page).getByRole('combobox', { name: '글자 크기', exact: true }).selectOption('LARGE')
  await editor(page).getByRole('combobox', { name: '글자 색상', exact: true }).selectOption('SKY')
  await tab(page, '대시보드 구성')
  await editor(page).getByRole('combobox', { name: '영역 너비', exact: true }).selectOption('5')
  await editor(page).getByText('고급 배치 좌표', { exact: true }).click()
  await editor(page).getByLabel('영역 높이', { exact: true }).fill('7')
  await editor(page).getByLabel('영역 높이', { exact: true }).press('Tab')
  await tab(page, '달력 표시')
  await editor(page).getByRole('combobox', { name: '기본 표시 기준', exact: true }).selectOption('income')
  await tab(page, '거래 입력')
  await editor(page).getByRole('switch', { name: '거래 이미지 자동입력 표시', exact: false }).uncheck()
  await saveSettings(page)

  // Resize/detail controls live beside the real card, not in the full settings dialog.
  await page.getByRole('button', { name: '대시보드 편집', exact: true }).click()
  await widget(page).getByRole('button', { name: /위젯 설정/ }).click()
  const inline = page.getByRole('region', { name: '위젯 직접 편집', exact: true })
  for (const [name, value] of [['카드 너비 (칸)', '3'], ['카드 높이 (칸)', '2']]) {
    await inline.getByRole('combobox', { name, exact: true }).selectOption(value)
    await expect(inline.getByRole('combobox', { name, exact: true })).toHaveValue(value)
    await expect(widget(page)).toHaveAttribute(name.includes('너비') ? 'data-widget-columns' : 'data-widget-rows', value)
  }
  await inline.getByLabel('기간 표시', { exact: true }).uncheck()
  await inline.getByLabel('수입·지출 및 상세 정보 표시', { exact: true }).uncheck()
  await inline.getByRole('button', { name: '편집 계속하기', exact: true }).click()
  await page.locator('.calendar-draft-toolbar').getByRole('button', { name: '변경사항 저장', exact: true }).click()
  await expect(page.locator('.calendar-draft-toolbar')).toHaveCount(0)
  const firstSave = structuredClone(state.saved)
  expect(firstSave.widgets[0]).toMatchObject({ title: '다른 컴퓨터에서도 유지', cardColumns: 3, cardRows: 2, textSize: 'LARGE', textColor: 'SKY', showDetails: false, showPeriod: false })
  expect(firstSave.layout.find(item => item.id === 'calendar')).toMatchObject({ w: 5, h: 7 })

  const otherComputer = await browser.newContext({ viewport: { width: 1280, height: 900 } })
  try {
    const otherPage = await otherComputer.newPage()
    // The second context has no cookies, localStorage or storageState from the first computer.
    expect((await otherComputer.storageState()).origins).toEqual([])
    expect((await otherComputer.storageState()).cookies).toEqual([])
    await mockPersonalization(otherPage, { sharedStore: state, layoutUpdatedAt: '2026-10-20T12:00:00Z' })
    await expect(widget(otherPage).locator('.household-aggregate-card__eyebrow')).toHaveText('다른 컴퓨터에서도 유지')
    await expect(widget(otherPage)).toHaveAttribute('data-aggregate-text-size', 'LARGE')
    await expect(widget(otherPage)).toHaveAttribute('data-aggregate-text-color', 'SKY')
    await expect(widget(otherPage)).toHaveAttribute('data-widget-rows', '2')
    await expect(widget(otherPage)).toHaveAttribute('data-widget-columns', '3')
    const rowHeight = await otherPage.locator('.household-aggregate-grid').evaluate(element => parseFloat(getComputedStyle(element).getPropertyValue('--calendar-widget-cell-height')))
    await expect(widget(otherPage)).toHaveCSS('height', `${2 * rowHeight - 8}px`)
    await expect(widget(otherPage).locator('.household-aggregate-card__meta')).toHaveCount(0)
    await expect(widget(otherPage).locator('.household-aggregate-card__copy small')).toHaveCount(0)
    await expect(otherPage.getByRole('button', { name: '분석 모달 열기', exact: true })).toHaveCount(0)
    await expect(otherPage.getByLabel('표시 기준', { exact: true })).toHaveValue('income')
    await expect(otherPage.locator('[data-calendar-panel-id="calendar"]')).toHaveCSS('grid-column', '1 / span 5')
    expect(firstSave.layout.find(panel => panel.id === 'calendar').y).toBe(3)
    await expect.poll(() => otherPage.locator('.calendar-dashboard-grid').evaluate(grid => {
      const aggregate = grid.querySelector('[data-calendar-panel-id="aggregate"]').getBoundingClientRect()
      const query = grid.querySelector('[data-calendar-panel-id="calendar-query"]').getBoundingClientRect()
      const calendar = grid.querySelector('[data-calendar-panel-id="calendar"]').getBoundingClientRect()
      return calendar.top - Math.max(aggregate.bottom, query.bottom)
    })).toBeGreaterThanOrEqual(0)

    await openSettings(otherPage)
    await expect(editor(otherPage).getByRole('button', { name: '변경사항 저장', exact: true })).toBeDisabled()
    await tab(otherPage, '집계 위젯')
    await editor(otherPage).getByLabel('위젯 이름', { exact: true }).fill('두 번째 컴퓨터에서 저장')
    await tab(otherPage, '대시보드 구성')
    await editor(otherPage).getByRole('combobox', { name: '영역 너비', exact: true }).selectOption('4')
    await saveSettings(otherPage)

    // The returning device has an older layout but a clock far ahead of the server.
    await page.addInitScript(layout => localStorage.setItem('calen-household-calendar-panel-layout:v3:2', JSON.stringify({ savedAt: Date.parse('2099-01-01T12:00:00Z'), layout })), firstSave.layout)
    await page.reload()
    await page.getByRole('button', { name: '달력 가계부', exact: true }).click()
    await expect(widget(page).locator('.household-aggregate-card__eyebrow')).toHaveText('두 번째 컴퓨터에서 저장')
    await expect.poll(() => page.evaluate(() => JSON.parse(localStorage.getItem('calen-household-calendar-panel-layout:v3:2')).layout)).toEqual(state.saved.layout)
    await openSettings(page)
    await expect(editor(page).getByRole('button', { name: '변경사항 저장', exact: true })).toBeDisabled()
    await expect(editor(page).getByRole('combobox', { name: '영역 너비', exact: true })).toHaveValue('4')
    const cached = await page.evaluate(() => JSON.parse(localStorage.getItem('calen-household-calendar-panel-layout:v3:2')))
    expect(cached.layout).toEqual(state.saved.layout)
    expect(state.writes).toHaveLength(3)
    expect(state.errors).toEqual([])
  } finally {
    await otherComputer.close()
  }
})

test('legacy browser-only palettes require an explicit account save before another computer can restore them', async ({ page, browser }) => {
  const saved = calendarSettings()
  saved.layout = null
  saved.preferences = null
  const legacyLayout = calendarSettings().layout.map(panel => ({ ...panel, y: panel.y + 5 }))
  await page.addInitScript(layout => localStorage.setItem('calen-household-calendar-panel-layout:v3:2', JSON.stringify({ savedAt: 1, layout })), legacyLayout)
  const state = await mockPersonalization(page, { saved })
  await openSettings(page)
  await expect(editor(page)).toContainText('이 브라우저에만 보관된 설정이 있습니다')
  expect(state.writes).toEqual([])
  await expect(editor(page).getByRole('button', { name: '변경사항 저장', exact: true })).toBeEnabled()
  await saveSettings(page)
  expect(state.saved.layout).toEqual(ensureCalendarQueryPalette(legacyLayout))
  const otherComputer = await browser.newContext({ viewport: { width: 1280, height: 900 } })
  try {
    const otherPage = await otherComputer.newPage()
    await mockPersonalization(otherPage, { sharedStore: state })
    await openSettings(otherPage)
    await editor(otherPage).getByText('고급 배치 좌표', { exact: true }).click()
    await expect(editor(otherPage).getByLabel('세로 위치 (0부터)', { exact: true })).toHaveValue(String(state.saved.layout.find(panel => panel.id === 'calendar').y))
    await expect(editor(otherPage).getByRole('button', { name: '변경사항 저장', exact: true })).toBeDisabled()
    expect(state.writes).toHaveLength(1)
    expect(state.errors).toEqual([])
  } finally {
    await otherComputer.close()
  }
})

test('failed server reads leave the local fallback usable but cannot save it over account settings', async ({ page }) => {
  const state = await mockPersonalization(page)
  const serverLayout = structuredClone(state.saved.layout)
  const localLayout = serverLayout.map(panel => ({ ...panel, y: panel.y + 3 }))
  await page.addInitScript(layout => localStorage.setItem('calen-household-calendar-panel-layout:v3:2', JSON.stringify({ savedAt: 1, layout })), localLayout)
  await page.route('**/api/account/preferences/**', route => {
    const path = new URL(route.request().url()).pathname
    if (route.request().method() === 'GET' && (path.endsWith('/household-personalization') || path.includes('/layout-settings/'))) {
      return route.fulfill({ status: 503, json: { message: '검증용 설정 조회 실패' } })
    }
    return route.fallback()
  })
  await page.reload()
  await page.getByRole('button', { name: '달력 가계부', exact: true }).click()
  const calendar = ensureCalendarQueryPalette(localLayout).find(panel => panel.id === 'calendar')
  const aggregate = ensureCalendarQueryPalette(localLayout).find(panel => panel.id === 'aggregate')
  await expect.poll(() => page.locator('.calendar-dashboard-grid').evaluate((grid, savedAggregateRows) => {
    const queryRows = Number(grid.querySelector('.calendar-query-item').style.getPropertyValue('--calendar-panel-rows'))
    const aggregateRows = Number(grid.querySelector('[data-calendar-panel-id="aggregate"]').style.getPropertyValue('--calendar-panel-rows'))
    const calendarOrder = Number(grid.querySelector('[data-calendar-panel-id="calendar"]').style.getPropertyValue('--mobile-calendar-panel-order'))
    // Wrapped controls and cards may need additional presentation rows; the cached layout remains unchanged.
    return calendarOrder - (Math.max(0, queryRows - 1) + Math.max(0, aggregateRows - savedAggregateRows)) * 9
  }, aggregate.h)).toBe(calendar.y * 9 + calendar.x)
  await openSettings(page)
  await expect(editor(page).getByRole('alert')).toContainText('검증용 설정 조회 실패')
  await expect(editor(page).getByRole('button', { name: '변경사항 저장', exact: true })).toBeDisabled()
  expect(state.writes).toEqual([])
  expect(state.saved.layout).toEqual(serverLayout)
  expect(state.errors).toEqual([])
})
