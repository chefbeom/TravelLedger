import { expect, test } from '@playwright/test'
import { calendarSettings, mockPersonalization } from './fixtures/calendarPersonalization.js'

const query = page => page.locator('.calendar-query-item')
const toolbar = page => page.locator('.calendar-draft-toolbar')
const widget = (page, index) => page.locator(`.calendar-widget[data-aggregate-index="${index}"]`)

async function edit(page) {
  await page.getByRole('button', { name: '대시보드 편집', exact: true }).click()
  await expect(toolbar(page)).toBeVisible()
}

async function save(page) {
  await toolbar(page).getByRole('button', { name: '변경사항 저장', exact: true }).click()
  await expect(toolbar(page)).toHaveCount(0)
}

async function cardGeometry(page) {
  return page.locator('.household-aggregate-grid').evaluate(grid => {
    const origin = grid.getBoundingClientRect(), pitch = (grid.clientWidth + 8) / 9
    const rows = parseFloat(getComputedStyle(grid.closest('.calendar-dashboard')).getPropertyValue('--calendar-layout-cell-height'))
    const cells = [...grid.querySelectorAll('.calendar-widget-grid-guide > span')]
    const cards = [...grid.querySelectorAll('.calendar-widget')].map(card => {
      const r = card.getBoundingClientRect(), x = Number(card.dataset.widgetGridX), y = Number(card.dataset.widgetGridY)
      const w = Number(card.dataset.widgetGridColumns), h = Number(card.dataset.widgetGridRows)
      const first = cells[y * 9 + x]?.getBoundingClientRect(), last = cells[(y + h - 1) * 9 + x + w - 1]?.getBoundingClientRect()
      const error = first && last ? Math.max(Math.abs(r.left - first.left), Math.abs(r.top - first.top), Math.abs(r.right - last.right), Math.abs(r.bottom - last.bottom)) : 0
      return { x, y, w, h, left: r.left - origin.left, top: r.top - origin.top, width: r.width, height: r.height, error }
    })
    return { pitch, rows, cards, guides: cells.length, mobile: document.documentElement.dataset.layoutMode === 'mobile' }
  })
}

test('the query strip can occupy the top empty row, saves its own position and restores on another computer', async ({ page, browser }) => {
  const saved = calendarSettings()
  saved.layout = saved.layout.map(panel => ({ ...panel, y: panel.y + 2 }))
  const state = await mockPersonalization(page, { saved })
  const legacy = structuredClone(state.saved.layout)
  await edit(page)
  const handle = query(page).getByRole('button', { name: '날짜·조회 팔레트 이동', exact: true })
  await expect(handle).toBeVisible()
  for (let i = 0; i < 4; i++) await handle.press('ArrowUp')
  await expect.poll(async () => {
    const q = await query(page).boundingBox(), board = await page.locator('.calendar-dashboard-grid').boundingBox()
    return Math.abs(q.y - board.y)
  }).toBeLessThan(5)
  expect(state.saved.layout).toEqual(legacy)
  expect(state.writes).toEqual([])
  await save(page)
  expect(state.saved.layout).toHaveLength(5)
  expect(state.saved.layout.find(panel => panel.id === 'calendar-query')).toMatchObject({ x: 0, y: 0, w: 9, h: 1 })
  const committed = structuredClone(state.saved.layout)
  const other = await browser.newContext({ viewport: { width: 1024, height: 900 } })
  try {
    const second = await other.newPage()
    await mockPersonalization(second, { sharedStore: state })
    await expect.poll(async () => {
      const q = await query(second).boundingBox(), board = await second.locator('.calendar-dashboard-grid').boundingBox()
      return Math.abs(q.y - board.y)
    }).toBeLessThan(5)
  } finally { await other.close() }
  await page.reload()
  await page.getByRole('button', { name: '달력 가계부', exact: true }).click()
  await edit(page)
  await expect(toolbar(page).getByRole('button', { name: '변경사항 저장', exact: true })).toBeDisabled()
  await toolbar(page).getByRole('button', { name: '취소', exact: true }).click()
  expect(state.saved.layout).toEqual(committed)
  expect(state.errors).toEqual([])
})

test('query drag uses a real mouse/touch handle, while date controls remain clickable', async ({ page }, testInfo) => {
  const saved = calendarSettings()
  // Reproduce the user's free rows above the query strip, rather than asking
  // GridStack to swap a one-row item halfway into an occupied two-row item.
  saved.layout[1].y = saved.layout[2].y = 4
  const state = await mockPersonalization(page, { saved })
  await edit(page)
  const handle = query(page).getByRole('button', { name: '날짜·조회 팔레트 이동', exact: true })
  await handle.evaluate(element => element.scrollIntoView({ block: 'center', behavior: 'instant' }))
  const r = await handle.boundingBox(), x = r.x + r.width / 2, y = r.y + r.height / 2
  const mobile = (await page.locator('html').getAttribute('data-layout-mode')) === 'mobile'
  const rowHeight = await page.locator('.calendar-dashboard').evaluate(element => parseFloat(getComputedStyle(element).getPropertyValue('--calendar-layout-cell-height')))
  if (mobile) {
    const client = await page.context().newCDPSession(page)
    await client.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y, id: 1 }] })
    for (let i = 1; i <= 12; i++) await client.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x, y: y - rowHeight * i / 12, id: 1 }] })
    await client.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] })
    await client.detach()
  } else {
    const initial = await query(page).evaluate(element => element.gridstackNode.y)
    await page.mouse.move(x, y); await page.mouse.down()
    await page.mouse.move(x, y - rowHeight, { steps: 20 })
    await testInfo.attach('query-drag-node', { body: JSON.stringify(await query(page).evaluate(element => {
      const n = element.gridstackNode
      return { x: n.x, y: n.y, w: n.w, h: n.h, noMove: n.noMove, noResize: n.noResize, locked: n.locked, classes: element.className, parent: element.parentElement.className, hit: document.elementFromPoint(element.getBoundingClientRect().left + 12, element.getBoundingClientRect().top + 20)?.outerHTML.slice(0, 500) }
    })), contentType: 'application/json' })
    await expect.poll(() => query(page).evaluate(element => element.gridstackNode.y)).toBe(initial - 1)
    await page.mouse.up()
  }
  await expect(toolbar(page).getByRole('button', { name: '변경사항 저장', exact: true })).toBeEnabled()
  await query(page).getByRole('combobox', { name: '월', exact: true }).selectOption('8')
  await expect(query(page).getByRole('combobox', { name: '월', exact: true })).toHaveValue('8')
  await save(page)
  expect(state.saved.layout.find(panel => panel.id === 'calendar-query').y).toBeLessThan(4)
  await query(page).screenshot({ path: testInfo.outputPath('moved-query-toolbar.png') })
  expect(state.errors).toEqual([])
})

test('a 2x2 card uses the exact guide cells and later small cards fill the free row beneath neighbours', async ({ page }, testInfo) => {
  const saved = calendarSettings()
  saved.layout[0].h = 4
  saved.layout[1].y = saved.layout[2].y = 4
  saved.widgets = saved.widgets.map((config, index) => ({ ...config, kind: index < 5 ? 'TOTAL' : 'NONE', cardColumns: 2, cardRows: index === 3 ? 2 : 1 }))
  const state = await mockPersonalization(page, { saved })
  await edit(page)
  const f = await cardGeometry(page)
  for (const card of f.cards) {
    expect(card.error).toBeLessThan(1)
    expect(Math.abs(card.width - (card.w * f.pitch - 8))).toBeLessThan(1)
    expect(Math.abs(card.height - (card.h * f.rows - 8))).toBeLessThan(1)
  }
  if (!f.mobile) {
    expect(f.cards[3]).toMatchObject({ x: 6, y: 0, w: 2, h: 2 })
    expect(f.cards[4]).toMatchObject({ x: 0, y: 1, w: 2, h: 1 })
    expect(f.guides).toBe(18)
  }
  await page.locator('.household-aggregate-panel').screenshot({ path: testInfo.outputPath('two-by-two-card-grid.png') })
  await widget(page, 3).getByRole('button', { name: /위젯 크기 조절/ }).press('ArrowDown')
  const larger = await cardGeometry(page)
  expect(larger.cards[3].h).toBe(3)
  if (!f.mobile) expect(larger.cards[4]).toMatchObject({ x: 0, y: 1 })
  for (const card of larger.cards) expect(card.error).toBeLessThan(1)
  await page.locator('.household-aggregate-panel').screenshot({ path: testInfo.outputPath('two-dimensional-card-grid.png') })
  await save(page)
  await page.reload()
  await page.getByRole('button', { name: '달력 가계부', exact: true }).click()
  const restored = await cardGeometry(page)
  expect(restored.cards.map(({ error, ...card }) => card)).toEqual(larger.cards.map(({ error, ...card }) => card))
  expect(state.errors).toEqual([])
})
