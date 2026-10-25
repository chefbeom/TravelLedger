import { expect, test } from '@playwright/test'
import { calendarSettings, mockPersonalization } from './fixtures/calendarPersonalization.js'

const gridSelector = '.calendar-dashboard-grid.grid-stack'

async function openLayoutEditor(page, expectedPanels = 4) {
  await page.getByRole('button', { name: '대시보드 편집', exact: true }).click()
  await expect.poll(() => page.locator(gridSelector).evaluate(grid => grid.gridstack?.engine?.nodes.filter(node => node.id !== 'calendar-query').length)).toBe(expectedPanels)
}

async function layoutGeometry(page) {
  return page.locator(gridSelector).evaluate(grid => {
    const cells = [...grid.closest('.calendar-dashboard').querySelectorAll('.household-calendar-layout-guide > span')]
    const edges = element => {
      const { left, top, right, bottom } = element.getBoundingClientRect()
      return { left, top, right, bottom }
    }
    const panels = [...grid.querySelectorAll(':scope > [data-calendar-panel-id]')].map(item => {
      const { x, y, w, h } = item.gridstackNode
      const first = cells[y * 9 + x], last = cells[(y + h - 1) * 9 + x + w - 1]
      if (!first || !last) return { id: item.dataset.calendarPanelId, error: 10000 }
      const start = edges(first), end = edges(last)
      const expected = { left: start.left, top: start.top, right: end.right, bottom: end.bottom }
      const content = edges(item.querySelector(':scope > .grid-stack-item-content'))
      const panel = edges(item.querySelector('.calendar-dashboard-panel > .panel'))
      const error = Math.max(...[content, panel].flatMap(rect => Object.keys(expected).map(edge => Math.abs(expected[edge] - rect[edge]))))
      return { id: item.dataset.calendarPanelId, x, y, w, h, expected, content, panel, error }
    })
    return { panels, maxError: Math.max(...panels.map(panel => panel.error)) }
  })
}

async function expectAligned(page) {
  await expect.poll(async () => (await layoutGeometry(page)).maxError, {
    message: 'Every card and its border must fit the guide cells at its saved grid coordinates',
  }).toBeLessThan(1)
}

async function gridMetrics(page, id) {
  return page.locator(gridSelector).evaluate((grid, panelId) => {
    const { x, y, w, h } = grid.gridstack.engine.nodes.find(node => node.id === panelId)
    return { x, y, w, h, cellWidth: grid.gridstack.cellWidth(), cellHeight: grid.gridstack.getCellHeight(true) }
  }, id)
}

async function dragPanel(page, id, dx, dy) {
  const before = await gridMetrics(page, id)
  const handle = page.locator(`${gridSelector} [data-calendar-panel-id="${id}"] .panel__header h2`)
  await handle.evaluate(element => element.scrollIntoView({ block: 'center', behavior: 'instant' }))
  const box = await handle.boundingBox()
  const x = box.x + 25, y = box.y + box.height / 2
  await page.mouse.move(x, y)
  await page.mouse.down()
  await page.mouse.move(x + dx * before.cellWidth, y + dy * before.cellHeight, { steps: 20 })
  await expect.poll(() => gridMetrics(page, id)).toMatchObject({ x: before.x + dx, y: before.y + dy })
  await page.mouse.up()
  await expectAligned(page)
}

async function resizePanel(page, id, w, h) {
  const before = await gridMetrics(page, id)
  const item = page.locator(`${gridSelector} [data-calendar-panel-id="${id}"]`)
  await item.evaluate(element => element.scrollIntoView({ block: 'end', behavior: 'instant' }))
  const itemBox = await item.boundingBox()
  await page.mouse.move(itemBox.x + itemBox.width - 14, itemBox.y + itemBox.height - 14)
  const handle = item.locator(':scope > .ui-resizable-se')
  await expect(handle).toBeVisible()
  const box = await handle.boundingBox()
  const x = box.x + box.width / 2, y = box.y + box.height / 2
  await page.mouse.move(x, y)
  await page.mouse.down()
  await page.mouse.move(x + (w - before.w) * before.cellWidth, y + (h - before.h) * before.cellHeight, { steps: 20 })
  await expect.poll(() => gridMetrics(page, id)).toMatchObject({ w, h })
  await page.mouse.up()
  await expectAligned(page)
}

test('editing guides align with card borders at every viewport size and in both themes', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'chromium-desktop', 'GridStack editing is desktop-only; mobile uses the settings editor.')
  test.setTimeout(90_000)
  const saved = calendarSettings()
  saved.preferences.transactionSheetViewMode = 'inline'
  const state = await mockPersonalization(page, { saved })
  await openLayoutEditor(page)
  await testInfo.attach('initial-layout-geometry', { body: JSON.stringify(await layoutGeometry(page), null, 2), contentType: 'application/json' })
  await page.locator('.calendar-dashboard').screenshot({ path: testInfo.outputPath('layout-editing.png') })
  await expectAligned(page)
  for (const width of [1920, 1024, 1440]) {
    await page.setViewportSize({ width, height: 900 })
    await expectAligned(page)
  }
  await page.getByRole('button', { name: '다크 테마', exact: true }).click()
  await expectAligned(page)
  await page.locator('.calendar-dashboard').screenshot({ path: testInfo.outputPath('layout-editing-dark.png') })
  await page.locator('.calendar-draft-toolbar').getByRole('button', { name: '취소', exact: true }).click()
  expect(state.writes).toEqual([])
  expect(state.errors).toEqual([])
})

test('dragged and resized cards snap to the same cells and retain their coordinates after save and reload', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'chromium-desktop', 'GridStack editing is desktop-only.')
  test.setTimeout(90_000)
  const saved = calendarSettings()
  saved.preferences.transactionSheetViewMode = 'inline'
  const state = await mockPersonalization(page, { saved })
  await openLayoutEditor(page)
  await dragPanel(page, 'quick-entry', 0, 2)
  await resizePanel(page, 'quick-entry', 2, 4)
  await dragPanel(page, 'quick-entry', 1, 0)
  await resizePanel(page, 'calendar', 5, 3)
  const edited = (await layoutGeometry(page)).panels.map(({ id, x, y, w, h }) => ({ id, x, y, w, h }))
  expect(state.writes).toEqual([])
  await page.locator('.calendar-draft-toolbar').getByRole('button', { name: '변경사항 저장', exact: true }).click()
  await expect(page.locator(gridSelector)).toHaveCount(0)
  await expect(page.locator('.household-calendar-layout-guide')).toHaveCount(0)
  expect(state.writes).toHaveLength(1)
  expect([...state.saved.layout].sort((a, b) => a.id.localeCompare(b.id))).toEqual([...edited].sort((a, b) => a.id.localeCompare(b.id)))
  await page.reload()
  await page.getByRole('button', { name: '달력 가계부', exact: true }).click()
  await openLayoutEditor(page)
  await expectAligned(page)
  expect((await layoutGeometry(page)).panels.map(({ id, x, y, w, h }) => ({ id, x, y, w, h }))).toEqual(edited)
  await page.locator('.calendar-dashboard').screenshot({ path: testInfo.outputPath('layout-after-move-resize-reload.png') })
  expect(state.errors).toEqual([])
})

test('switching to mobile hides the grid guides and returning to desktop restores alignment without saving', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'chromium-desktop', 'The desktop context also verifies the mobile viewport transition.')
  const state = await mockPersonalization(page)
  await openLayoutEditor(page, 3)
  const before = (await layoutGeometry(page)).panels.map(({ id, x, y, w, h }) => ({ id, x, y, w, h }))
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(page.locator(gridSelector)).toHaveCount(0)
  await expect(page.locator('.household-calendar-layout-guide')).toHaveCount(0)
  const panels = await page.locator('[data-calendar-panel-id]').evaluateAll(items => items.map(item => {
    const { left, right } = item.getBoundingClientRect()
    return { left, right }
  }))
  for (const panel of panels) { expect(panel.left).toBeGreaterThanOrEqual(0); expect(panel.right).toBeLessThanOrEqual(391) }
  await page.setViewportSize({ width: 1440, height: 900 })
  await expect.poll(() => page.locator(gridSelector).evaluate(grid => grid.gridstack?.engine?.nodes.filter(node => node.id !== 'calendar-query').length)).toBe(3)
  await expectAligned(page)
  expect((await layoutGeometry(page)).panels.map(({ id, x, y, w, h }) => ({ id, x, y, w, h }))).toEqual(before)
  expect(state.writes).toEqual([])
  expect(state.errors).toEqual([])
})
