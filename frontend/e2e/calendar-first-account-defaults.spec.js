import { expect, test } from '@playwright/test'
import { ensureCalendarQueryPalette } from '../src/lib/calendarQueryLayout.js'
import { calendarSettings, mockPersonalization } from './fixtures/calendarPersonalization.js'

function newAccountSettings() {
  const settings = calendarSettings()
  settings.layout = null
  settings.preferences = null
  settings.widgets = ['PAYMENT_METHOD', 'MONTHLY_GOAL', 'TOTAL', 'MONTHLY_CUMULATIVE_CHART', 'NONE', 'NONE'].map((kind, index) => ({
    kind, period: 'MONTH', paymentMethodId: kind === 'PAYMENT_METHOD' ? 103 : null,
    amountType: kind === 'MONTHLY_CUMULATIVE_CHART' ? 'NET' : 'EXPENSE',
    monthlyExpenseTarget: 0, singleExpenseLimit: 0, chartMaxAmount: 0,
    showIncomeCumulative: true, showExpenseCumulative: true, comparePreviousPeriod: false,
    textSize: 'MEDIUM', textColor: 'DEFAULT', title: '', hidden: false, displaySize: 'MEDIUM',
    layoutX: [1, 2, 3, 4, 6, 7][index], layoutY: 1, layoutW: kind === 'MONTHLY_CUMULATIVE_CHART' ? 2 : 1,
    layoutH: 1, layoutOrder: index + 1,
    cardColumns: index < 3 ? 2 : index === 3 ? 3 : null, cardRows: index < 4 ? 1 : null,
  }))
  return settings
}

async function overflowMetrics(page) {
  return page.evaluate(() => {
    const inspect = element => ({ client: element.clientHeight, scroll: element.scrollHeight, width: element.clientWidth, scrollWidth: element.scrollWidth })
    return {
      calendar: inspect(document.querySelector('.household-calendar-panel')),
      entry: inspect(document.querySelector('.household-entry-panel')),
      aggregate: inspect(document.querySelector('.household-aggregate-panel')),
      widgets: [...document.querySelectorAll('.calendar-widget')].map(inspect),
    }
  })
}

test('new account uses four readable cards and spacious calendar and input panels', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'chromium-desktop', 'The desktop matrix covers multiple widths.')
  await page.setViewportSize({ width: 1600, height: 1200 })
  const state = await mockPersonalization(page, { saved: newAccountSettings() })
  const widgets = page.locator('.calendar-widget')
  await expect(widgets).toHaveCount(4)
  await expect(widgets.nth(0)).toContainText('검증용 카드')
  await expect(widgets.nth(1)).toContainText('지출 목표')
  await expect(widgets.nth(2)).toContainText('총 지출')
  await expect(widgets.nth(3)).toContainText('누적 그래프')
  await expect.poll(() => widgets.evaluateAll(cards => cards.map(card => card.dataset.widgetGridColumns))).toEqual(['2', '2', '2', '3'])
  for (const width of [1600, 1366, 1024]) {
    await page.setViewportSize({ width, height: 900 })
    if (width >= 1366) {
      await expect.poll(async () => {
        const sizes = await overflowMetrics(page)
        return Object.fromEntries(Object.entries(sizes).map(([name, value]) => [name, Array.isArray(value) ? value.map(item => item.scroll - item.client) : value.scroll - value.client]))
      }, { message: `Nested overflow at viewport ${width}` }).toEqual({ calendar: 0, entry: 0, aggregate: 0, widgets: [0, 0, 0, 0] })
    } else {
      // A compact desktop still keeps constrained content reachable by scrolling.
      for (const selector of ['.household-calendar-panel', '.household-entry-panel', '.calendar-widget']) {
        const states = await page.locator(selector).evaluateAll(elements => elements.map(el => ({ clipped: el.scrollHeight > el.clientHeight + 1, overflow: getComputedStyle(el).overflowY })))
        expect(states.filter(item => item.clipped).every(item => ['auto', 'scroll'].includes(item.overflow))).toBe(true)
      }
    }
    if (width >= 1366) {
      const geometry = await widgets.evaluateAll(cards => cards.map(card => Math.round(card.getBoundingClientRect().y)))
      expect(new Set(geometry).size).toBe(1)
    }
    if (width === 1600) await page.locator('.calendar-dashboard').screenshot({ path: testInfo.outputPath('new-account-default.png') })
  }
  await page.reload()
  await page.getByRole('button', { name: '달력 가계부', exact: true }).click()
  await expect.poll(() => widgets.evaluateAll(cards => cards.map(card => card.dataset.widgetGridColumns))).toEqual(['2', '2', '2', '3'])
  expect(state.writes).toEqual([])
  expect(state.errors).toEqual([])
})

test('new-account controls and all four cards remain reachable on mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const state = await mockPersonalization(page, { saved: newAccountSettings() })
  const widgets = page.locator('.calendar-widget')
  await expect(widgets).toHaveCount(4)
  for (const widget of await widgets.all()) {
    await widget.scrollIntoViewIfNeeded()
    await expect(widget).toBeInViewport()
  }
  const register = page.getByRole('button', { name: '거래 등록', exact: true })
  await register.scrollIntoViewIfNeeded()
  await expect(register).toBeInViewport()
  const viewport = await page.evaluate(() => ({ width: document.documentElement.clientWidth, scroll: document.documentElement.scrollWidth }))
  expect(viewport.scroll).toBeLessThanOrEqual(viewport.width + 1)
  expect(state.writes).toEqual([])
  expect(state.errors).toEqual([])
})

test('editing and saving a fresh account keeps the default geometry and persists the chosen settings', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'chromium-desktop', 'The desktop test covers the editable palette geometry.')
  await page.setViewportSize({ width: 1600, height: 1000 })
  const state = await mockPersonalization(page, { saved: newAccountSettings() })
  const cards = page.locator('.calendar-widget')
  const sizes = () => cards.evaluateAll(elements => elements.map(element => ({ width: Math.round(element.getBoundingClientRect().width), height: Math.round(element.getBoundingClientRect().height) })))
  await expect.poll(() => cards.first().evaluate(card => {
    const grid = card.closest('.household-aggregate-grid')
    const pitch = parseFloat(getComputedStyle(grid).gridAutoRows) + parseFloat(getComputedStyle(grid).rowGap)
    return Math.abs(card.getBoundingClientRect().height - (pitch - 8))
  })).toBeLessThan(1)
  const before = await sizes()
  await page.getByRole('button', { name: '대시보드 편집', exact: true }).click()
  await expect(page.locator('.calendar-draft-toolbar')).toBeVisible()
  await expect.poll(sizes).toEqual(before)
  await cards.first().getByRole('button', { name: /위젯 설정/ }).click()
  const editor = page.getByRole('region', { name: '위젯 직접 편집', exact: true })
  await editor.getByLabel('위젯 이름', { exact: true }).fill('내 카드 지출')
  await editor.getByRole('button', { name: '편집 계속하기' }).click()
  await page.locator('.calendar-draft-toolbar').getByRole('button', { name: '변경사항 저장', exact: true }).click()
  await expect(page.locator('.calendar-draft-toolbar')).toHaveCount(0)
  await expect.poll(sizes).toEqual(before)
  expect(state.saved.widgets[0]).toMatchObject({ title: '내 카드 지출', cardColumns: 2, cardRows: 1 })
  expect(state.saved.layout.find(panel => panel.id === 'calendar').h).toBe(9)
  await page.reload()
  await page.getByRole('button', { name: '달력 가계부', exact: true }).click()
  await expect(cards.first()).toContainText('내 카드 지출')
  await expect.poll(sizes).toEqual(before)
  expect(state.errors).toEqual([])
})

for (const source of ['local-default', 'local-custom', 'server-default']) {
  test(`${source} restores safely without overwriting account settings`, async ({ page }) => {
    const saved = newAccountSettings()
    const oldLayout = ensureCalendarQueryPalette(calendarSettings().layout)
    if (source === 'local-custom') oldLayout.find(panel => panel.id === 'calendar').h = 7
    if (source === 'server-default') saved.layout = oldLayout
    else await page.addInitScript(layout => localStorage.setItem('calen-household-calendar-panel-layout:v3:2', JSON.stringify({ savedAt: 1, layout })), oldLayout)
    const state = await mockPersonalization(page, { saved })
    const expectedHeight = source === 'local-default' ? 9 : source === 'local-custom' ? 7 : 6
    await expect.poll(async () => page.locator('[data-calendar-panel-id="calendar"]').evaluate(element => Number(element.style.getPropertyValue('--calendar-panel-rows')))).toBe(expectedHeight)
    expect(state.writes).toEqual([])
    expect(state.errors).toEqual([])
  })
}

test('an old local default is not migrated when the account settings server cannot be reached', async ({ page }) => {
  const oldLayout = ensureCalendarQueryPalette(calendarSettings().layout)
  await page.addInitScript(layout => localStorage.setItem('calen-household-calendar-panel-layout:v3:2', JSON.stringify({ savedAt: 1, layout })), oldLayout)
  const state = await mockPersonalization(page, { saved: newAccountSettings() })
  await page.route('**/api/account/preferences/layout-settings/household-calendar', route => route.fulfill({ status: 503, json: { message: 'offline' } }))
  await page.reload()
  await page.getByRole('button', { name: '달력 가계부', exact: true }).click()
  await expect.poll(async () => page.locator('[data-calendar-panel-id="calendar"]').evaluate(element => Number(element.style.getPropertyValue('--calendar-panel-rows')))).toBe(6)
  expect(state.writes).toEqual([])
})
