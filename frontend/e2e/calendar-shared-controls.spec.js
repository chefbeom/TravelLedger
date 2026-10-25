import { expect, test } from '@playwright/test'
import { calendarSettings, mockPersonalization } from './fixtures/calendarPersonalization.js'

async function metrics(page) {
  return page.locator('.calendar-dashboard-grid').evaluate(grid => {
    const rect = element => {
      const r = element.getBoundingClientRect(), s = getComputedStyle(element)
      return { x: r.x, y: r.y, w: r.width, h: r.height, scroll: element.scrollHeight, minH: s.minHeight, height: s.height, align: s.alignSelf, order: s.order, position: s.position }
    }
    return {
      mode: document.documentElement.dataset.layoutMode,
      grid: rect(grid), query: rect(grid.querySelector('.calendar-query-item')),
      queryNode: grid.querySelector('.calendar-query-item').gridstackNode && ['x', 'y', 'w', 'h'].map(key => [key, grid.querySelector('.calendar-query-item').gridstackNode[key]]),
      toolbar: rect(grid.querySelector('.calendar-query-toolbar')), controls: rect(grid.querySelector('.calendar-query-toolbar__controls')),
      guideCells: grid.querySelectorAll('.household-calendar-layout-guide > span').length,
      panels: [...grid.querySelectorAll('[data-calendar-panel-id]')].map(item => ({ id: item.dataset.calendarPanelId, rect: rect(item), node: item.gridstackNode && ['x', 'y', 'w', 'h'].map(key => [key, item.gridstackNode[key]]) })),
    }
  })
}

test('legacy common controls start above both palettes and do not write an upgraded layout before explicit save', async ({ page }, testInfo) => {
  const saved = calendarSettings(), original = structuredClone(saved.layout)
  saved.preferences.transactionSheetViewMode = 'inline'
  const state = await mockPersonalization(page, { saved })
  const before = await metrics(page)
  await page.getByRole('button', { name: '대시보드 편집', exact: true }).click()
  if (page.viewportSize().width >= 760) for (const width of [1920, 1024, 1440]) {
    await page.setViewportSize({ width, height: 900 })
    await page.locator('.calendar-query-toolbar').evaluate(element => element.getBoundingClientRect())
  }
  const result = await metrics(page)
  expect(result.query.h).toBeLessThan(page.viewportSize().width < 760 ? 400 : 150)
  if (page.viewportSize().width >= 760) expect(result.queryNode.find(([key]) => key === 'h')[1]).toBe(1)
  await page.locator('.calendar-draft-toolbar').getByRole('button', { name: '취소', exact: true }).click()
  expect(state.saved.layout).toEqual(original)
  expect(state.errors).toEqual([])
  await testInfo.attach('common-query-before-edit', { body: JSON.stringify(before), contentType: 'application/json' })
  await testInfo.attach('common-query-metrics', { body: JSON.stringify(result), contentType: 'application/json' })
})

test('custom palette arrangements and hidden input keep the shared controls above every primary palette', async ({ context }) => {
  for (const variant of ['input-first', 'crossing-aggregate', 'hidden-input']) {
    const saved = calendarSettings(), page = await context.newPage()
    if (variant === 'input-first') saved.layout = [
      { id: 'aggregate', x: 0, y: 8, w: 9, h: 2 },
      { id: 'calendar', x: 0, y: 2, w: 6, h: 6 },
      { id: 'quick-entry', x: 6, y: 0, w: 3, h: 6 },
      { id: 'sheet', x: 0, y: 10, w: 9, h: 3 },
    ]
    if (variant === 'crossing-aggregate') saved.layout = [
      { id: 'aggregate', x: 6, y: 0, w: 3, h: 4 },
      { id: 'calendar', x: 0, y: 2, w: 6, h: 6 },
      { id: 'quick-entry', x: 6, y: 4, w: 3, h: 6 },
      { id: 'sheet', x: 0, y: 10, w: 9, h: 3 },
    ]
    if (variant === 'hidden-input') saved.preferences.quickEntryPanelEnabled = false
    const original = structuredClone(saved.layout)
    try {
      const state = await mockPersonalization(page, { saved })
      const above = () => page.locator('.calendar-query-item').evaluate(query => {
        const primary = [...document.querySelectorAll('[data-calendar-panel-id="calendar"], [data-calendar-panel-id="quick-entry"]')]
        return query.getBoundingClientRect().bottom - Math.min(...primary.map(element => element.getBoundingClientRect().top))
      })
      await expect.poll(above, { message: `${variant}: shared query must precede both primary palettes` }).toBeLessThanOrEqual(1)
      await page.getByRole('button', { name: '대시보드 편집', exact: true }).click()
      await expect.poll(above).toBeLessThanOrEqual(1)
      await page.locator('.calendar-draft-toolbar').getByRole('button', { name: '취소', exact: true }).click()
      await expect.poll(above).toBeLessThanOrEqual(1)
      expect(state.saved.layout).toEqual(original)
      expect(state.writes).toEqual([])
      expect(state.errors).toEqual([])
    } finally { await page.close() }
  }
})
