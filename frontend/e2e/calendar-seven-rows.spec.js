import { expect, test } from '@playwright/test'
import { calendarSettings, mockPersonalization } from './fixtures/calendarPersonalization.js'

function sevenRowSettings() {
  const settings = calendarSettings()
  settings.layout = settings.layout.map(panel => ['calendar', 'quick-entry'].includes(panel.id) ? { ...panel, h: 7 } : panel)
  return settings
}

async function dimensions(page) {
  return page.locator('.household-calendar-panel').evaluate(panel => {
    const rect = element => {
      const box = element.getBoundingClientRect()
      return { top: box.top, bottom: box.bottom, width: box.width, height: box.height, scroll: element.scrollHeight, client: element.clientHeight }
    }
    const days = [...panel.querySelectorAll('.calendar__day')]
    const overflows = selector => Math.max(0, ...days.flatMap(day => [...day.querySelectorAll(selector)].map(element => Math.max(element.scrollHeight - element.clientHeight, element.scrollWidth - element.clientWidth))))
    return {
      panel: rect(panel),
      cellHeight: parseFloat(getComputedStyle(panel.closest('.calendar-dashboard')).getPropertyValue('--calendar-layout-cell-height')),
      heading: rect(panel.querySelector('.calendar-query-toolbar__heading')),
      shell: rect(panel.querySelector('.calendar-shell')),
      weekdays: rect(panel.querySelector('.calendar__weekdays')),
      weeks: rect(panel.querySelector('.calendar__weeks')),
      day: rect(days[0]),
      dayOverflow: Math.max(...days.map(day => day.scrollHeight - day.clientHeight)),
      contentOverflow: overflows('.calendar__metrics, .calendar__metric, .calendar__metric strong, .calendar__day-head, .calendar__expense-total'),
      count: days.length,
    }
  })
}

async function loadSummaries(page, summaries) {
  await page.route('**/api/dashboard**', route => route.fulfill({ json: { anchorDate: '2026-10-29', quickStats: [], calendar: summaries, expenseBreakdown: [], paymentBreakdown: [], monthlyComparison: [], recentEntries: [] } }))
  await page.reload()
  await page.getByRole('button', { name: '달력 가계부', exact: true }).click()
}

test('a seven-cell calendar shows six complete weeks without an inner scrollbar', async ({ page }, info) => {
  test.skip(info.project.name !== 'chromium-desktop')
  await page.clock.setFixedTime(new Date('2026-10-29T03:00:00Z'))
  await page.setViewportSize({ width: 1600, height: 1100 })
  const state = await mockPersonalization(page, { saved: sevenRowSettings() })
  const summaries = Array.from({ length: 31 }, (_, index) => ({ date: `2026-10-${String(index + 1).padStart(2, '0')}`, income: index % 3 === 0 ? 325000 : 0, expense: index === 24 ? 1944444 : index % 4 === 0 ? 93100 : 15000, entryCount: index % 5 + 1 }))
  await loadSummaries(page, summaries)
  await expect(page.locator('.calendar__metric--expense').filter({ hasText: '1,944,444' })).toHaveCount(1)
  for (const width of [2560, 1920, 1600, 1366, 1024]) {
    await page.setViewportSize({ width, height: 1100 })
    for (const editing of [false, true]) {
      if (editing) await page.getByRole('button', { name: '대시보드 편집', exact: true }).click()
      await expect.poll(async () => { const layout = await dimensions(page); return layout.panel.scroll - layout.panel.client }).toBeLessThanOrEqual(1)
      const layout = await dimensions(page)
      expect(layout.count).toBe(42)
      expect(Math.abs(layout.panel.height - (7 * layout.cellHeight - 8))).toBeLessThan(1)
      expect(layout.panel.scroll - layout.panel.client, `calendar scrollbar at ${width}`).toBeLessThanOrEqual(1)
      expect(layout.dayOverflow, `visible day contents at ${width}`).toBeLessThanOrEqual(1)
      expect(layout.contentOverflow, `visible amounts at ${width}`).toBeLessThanOrEqual(1)
      expect(layout.weeks.bottom).toBeLessThanOrEqual(layout.panel.bottom + 1)
      if ([1600, 1024].includes(width) && !editing) await page.locator('.household-calendar-panel').screenshot({ path: info.outputPath(`seven-row-${width}.png`) })
      if (editing) await page.locator('.calendar-draft-toolbar').getByRole('button', { name: '취소', exact: true }).click()
    }
  }
  await page.setViewportSize({ width: 1600, height: 1100 })
  const chosenDay = page.locator('.calendar__day').filter({ has: page.locator('.calendar__metric--expense').filter({ hasText: '1,944,444' }) })
  await chosenDay.click()
  await expect(chosenDay).toHaveClass(/calendar__day--selected/)
  await page.getByRole('combobox', { name: '달력 보기', exact: true }).selectOption('current')
  await expect(page.locator('.calendar__day')).toHaveCount(7)
  await page.getByRole('combobox', { name: '달력 보기', exact: true }).selectOption('month')
  await expect(page.locator('.calendar__day')).toHaveCount(42)
  await page.getByRole('button', { name: '다크 테마', exact: true }).click()
  for (const width of [1600, 1024]) {
    await page.setViewportSize({ width, height: 1100 })
    await expect.poll(async () => { const layout = await dimensions(page); return Math.max(layout.panel.scroll - layout.panel.client, layout.dayOverflow, layout.contentOverflow) }).toBeLessThanOrEqual(1)
  }
  expect(state.writes).toEqual([])
  expect(state.errors).toEqual([])
})

test('mobile keeps date selection usable with the saved seven-row palette', async ({ page }, info) => {
  test.skip(info.project.name !== 'chromium-mobile')
  await page.clock.setFixedTime(new Date('2026-10-29T03:00:00Z'))
  const state = await mockPersonalization(page, { saved: sevenRowSettings() })
  await expect(page.locator('html')).toHaveAttribute('data-layout-mode', 'mobile')
  await expect(page.locator('.calendar__day')).toHaveCount(42)
  const day = page.locator('.calendar__day').nth(14)
  await day.click()
  await expect(day).toHaveClass(/calendar__day--selected/)
  const viewport = await page.evaluate(() => ({ width: document.documentElement.clientWidth, scroll: document.documentElement.scrollWidth }))
  expect(viewport.scroll).toBeLessThanOrEqual(viewport.width + 1)
  expect(state.writes).toEqual([])
  expect(state.errors).toEqual([])
})

test('resizing to seven rows saves the layout and restores it after reload', async ({ page }, info) => {
  test.skip(info.project.name !== 'chromium-desktop')
  await page.setViewportSize({ width: 1024, height: 2400 })
  const saved = sevenRowSettings()
  saved.layout = saved.layout.map(panel => panel.id === 'calendar' ? { ...panel, h: 5 } : panel)
  const state = await mockPersonalization(page, { saved })
  await expect.poll(async () => { const layout = await dimensions(page); return layout.panel.scroll - layout.panel.client }).toBeGreaterThan(0)
  await page.getByRole('button', { name: '대시보드 편집', exact: true }).click()
  const item = page.locator('.calendar-dashboard-grid.grid-stack [data-calendar-panel-id="calendar"]')
  await item.evaluate(element => element.scrollIntoView({ block: 'end', behavior: 'instant' }))
  const itemBox = await item.boundingBox()
  await page.mouse.move(itemBox.x + itemBox.width - 14, itemBox.y + itemBox.height - 14)
  const handle = item.locator(':scope > .ui-resizable-se')
  await expect(handle).toBeVisible()
  const box = await handle.boundingBox()
  const pitch = await item.evaluate(element => element.closest('.grid-stack').gridstack.getCellHeight(true))
  const initialRows = await item.evaluate(element => element.gridstackNode.h)
  expect(initialRows).toBeLessThan(7)
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2)
  await page.mouse.down()
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2 + (7 - initialRows) * pitch, { steps: 20 })
  await expect.poll(() => item.evaluate(element => element.gridstackNode.h)).toBe(7)
  await page.mouse.up()
  await expect.poll(async () => { const layout = await dimensions(page); return Math.abs(layout.panel.height - (7 * layout.cellHeight - 8)) }).toBeLessThan(1)
  await expect.poll(async () => { const layout = await dimensions(page); return layout.panel.scroll - layout.panel.client }).toBeLessThanOrEqual(1)
  await page.locator('.calendar-draft-toolbar').getByRole('button', { name: '변경사항 저장', exact: true }).click()
  await expect(page.locator('.calendar-draft-toolbar')).toHaveCount(0)
  expect(state.saved.layout.find(panel => panel.id === 'calendar').h).toBe(7)
  await page.reload()
  await page.getByRole('button', { name: '달력 가계부', exact: true }).click()
  await expect.poll(async () => { const layout = await dimensions(page); return Math.abs(layout.panel.height - (7 * layout.cellHeight - 8)) }).toBeLessThan(1)
  await expect.poll(async () => { const layout = await dimensions(page); return layout.panel.scroll - layout.panel.client }).toBeLessThanOrEqual(1)
  expect(state.errors).toEqual([])
})

test('very long amounts remain readable even when seven rows cannot contain them', async ({ page }, info) => {
  test.skip(info.project.name !== 'chromium-desktop')
  await page.clock.setFixedTime(new Date('2026-10-29T03:00:00Z'))
  await page.setViewportSize({ width: 1024, height: 1100 })
  const state = await mockPersonalization(page, { saved: sevenRowSettings() })
  const summaries = Array.from({ length: 31 }, (_, index) => ({ date: `2026-10-${String(index + 1).padStart(2, '0')}`, income: 999999999999, expense: 999999999999, entryCount: 99 }))
  await loadSummaries(page, summaries)
  await expect(page.locator('.calendar__metric--expense').filter({ hasText: '999,999,999,999' })).toHaveCount(31)
  const layout = await dimensions(page)
  expect(layout.dayOverflow).toBeLessThanOrEqual(1)
  expect(layout.contentOverflow).toBeLessThanOrEqual(1)
  const geometry = await page.locator('.calendar__weeks').evaluate(weeks => [...weeks.children].map(week => ({ top: week.getBoundingClientRect().top, bottom: week.getBoundingClientRect().bottom })))
  for (let index = 1; index < geometry.length; index++) expect(geometry[index].top).toBeGreaterThanOrEqual(geometry[index - 1].bottom)
  if (layout.panel.scroll > layout.panel.client + 1) {
    const panel = page.locator('.household-calendar-panel')
    await panel.evaluate(element => { element.scrollTop = element.scrollHeight })
    const lastDay = page.locator('.calendar__day').last()
    const panelBox = await panel.boundingBox(), lastBox = await lastDay.boundingBox()
    expect(lastBox.y + lastBox.height).toBeLessThanOrEqual(panelBox.y + panelBox.height + 1)
  }
  expect(state.writes).toEqual([])
  expect(state.errors).toEqual([])
})
