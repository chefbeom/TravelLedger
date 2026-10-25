import { expect, test } from '@playwright/test'
import { calendarSettings, mockPersonalization } from './fixtures/calendarPersonalization.js'

const card = page => page.locator('.calendar-widget[data-aggregate-index="0"]')
const inline = page => page.getByRole('region', { name: '위젯 직접 편집', exact: true })
const toolbar = page => page.locator('.calendar-draft-toolbar')

function gridSettings() {
  const saved = calendarSettings()
  saved.layout[0].h = 4
  saved.layout[1].y = saved.layout[2].y = 4
  saved.widgets[0] = { ...saved.widgets[0], cardColumns: 3, cardRows: 2, cardWidth: 310, cardHeight: 230 }
  return saved
}

async function frame(page) {
  return card(page).evaluate(element => {
    const r = element.getBoundingClientRect(), grid = element.parentElement
    const rowPitch = parseFloat(getComputedStyle(element.closest('.calendar-dashboard')).getPropertyValue('--calendar-layout-cell-height'))
    return { width: r.width, height: r.height, columns: Number(element.dataset.widgetColumns), rows: Number(element.dataset.widgetRows), columnPitch: (grid.clientWidth + 8) / 9, rowPitch, font: getComputedStyle(element.querySelector('.household-aggregate-card__copy strong')).fontSize, mobile: document.documentElement.dataset.layoutMode === 'mobile' }
  })
}

async function expectGridFrame(page, columns, rows) {
  await expect(card(page)).toHaveAttribute('data-widget-columns', String(columns))
  await expect(card(page)).toHaveAttribute('data-widget-rows', String(rows))
  await expect.poll(async () => {
    const actual = await frame(page)
    const width = actual.mobile ? actual.columnPitch * 9 - 8 : actual.columnPitch * columns - 8
    return Math.max(Math.abs(actual.width - width), Math.abs(actual.height - (actual.rowPitch * rows - 8)))
  }, { message: 'A saved column/row must use the same grid pitch in view and edit, not an old pixel width' }).toBeLessThan(1)
}

async function edit(page) {
  await page.getByRole('button', { name: '대시보드 편집', exact: true }).click()
  await expect(page.locator('.calendar-dashboard')).toHaveClass(/calendar-dashboard--editing/)
}

async function save(page) {
  await toolbar(page).getByRole('button', { name: '변경사항 저장', exact: true }).click()
  await expect(toolbar(page)).toHaveCount(0)
}

async function drag(page, dx, dy, cancel = false) {
  const handle = card(page).getByRole('button', { name: /위젯 크기 조절/ })
  await handle.evaluate(element => element.scrollIntoView({ block: 'center', behavior: 'instant' }))
  const box = await handle.boundingBox(), x = box.x + box.width / 2, y = box.y + box.height / 2
  if ((await frame(page)).mobile) {
    const client = await page.context().newCDPSession(page)
    await client.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y, id: 1 }] })
    for (let step = 1; step <= 10; step++) await client.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: x + dx * step / 10, y: y + dy * step / 10, id: 1 }] })
    if (cancel) await page.keyboard.press('Escape')
    await client.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] })
    await client.detach()
  } else {
    await page.mouse.move(x, y)
    await page.mouse.down()
    await page.mouse.move(x + dx, y + dy, { steps: 12 })
    if (cancel) await page.keyboard.press('Escape')
    await page.mouse.up()
  }
}

test('grid sizes persist as whole cells, render responsively on another device, and leave legacy pixels intact', async ({ page, browser }) => {
  const state = await mockPersonalization(page, { saved: gridSettings() })
  await expectGridFrame(page, 3, 2)
  await edit(page)
  await card(page).getByRole('button', { name: /위젯 설정/ }).click()
  await expect(inline(page).getByLabel('카드 너비 (px)', { exact: true })).toHaveCount(0)
  await inline(page).getByRole('combobox', { name: '카드 너비 (칸)', exact: true }).selectOption('4')
  await inline(page).getByRole('combobox', { name: '카드 높이 (칸)', exact: true }).selectOption('3')
  await expectGridFrame(page, 4, 3)
  await inline(page).getByRole('button', { name: '편집 계속하기' }).click()
  await save(page)
  expect(state.saved.widgets[0]).toMatchObject({ cardColumns: 4, cardRows: 3, cardWidth: 310, cardHeight: 230 })
  expect(state.saved.widgets.slice(1).every(item => !item.cardColumns && !item.cardRows)).toBe(true)
  const other = await browser.newContext({ viewport: { width: 1024, height: 900 } })
  try {
    const second = await other.newPage()
    await mockPersonalization(second, { sharedStore: state })
    await expectGridFrame(second, 4, 3)
    expect(state.writes).toHaveLength(1)
  } finally { await other.close() }
  await page.reload()
  await page.getByRole('button', { name: '달력 가계부', exact: true }).click()
  await expectGridFrame(page, 4, 3)
  expect(state.errors).toEqual([])
})

test('mouse and touch snap to cells, Escape restores the preview, and keyboard resizing changes one row', async ({ page }, testInfo) => {
  const state = await mockPersonalization(page, { saved: gridSettings() })
  await edit(page)
  const initial = await frame(page)
  const siblings = await page.locator('.calendar-widget:not([data-aggregate-index="0"])').evaluateAll(elements => elements.map(element => ({ w: element.getBoundingClientRect().width, h: element.getBoundingClientRect().height })))
  await drag(page, initial.columnPitch, initial.rowPitch * 0.7, true)
  await expectGridFrame(page, 3, 2)
  expect(state.writes).toEqual([])
  await drag(page, initial.columnPitch, initial.rowPitch * 0.7)
  await expectGridFrame(page, initial.mobile ? 3 : 4, 3)
  await card(page).getByRole('button', { name: /위젯 크기 조절/ }).press('ArrowUp')
  await expectGridFrame(page, initial.mobile ? 3 : 4, 2)
  expect(await page.locator('.calendar-widget:not([data-aggregate-index="0"])').evaluateAll(elements => elements.map(element => ({ w: element.getBoundingClientRect().width, h: element.getBoundingClientRect().height })))).toEqual(siblings)
  expect((await frame(page)).font).toBe(initial.font)
  if (initial.mobile) {
    // Width is intentionally unchanged on mobile; ArrowUp restored the entire
    // original 3x2 configuration, so there is correctly nothing to save yet.
    await expect(toolbar(page).getByRole('button', { name: '변경사항 저장', exact: true })).toBeDisabled()
    await card(page).getByRole('button', { name: /위젯 크기 조절/ }).press('ArrowDown')
    await expectGridFrame(page, 3, 3)
  }
  await page.locator('.household-aggregate-panel').screenshot({ path: testInfo.outputPath('grid-widget-editing.png') })
  await save(page)
  expect(state.saved.widgets[0]).toMatchObject({ cardColumns: initial.mobile ? 3 : 4, cardRows: initial.mobile ? 3 : 2, cardWidth: 310, cardHeight: 230 })
  expect(state.errors).toEqual([])
})

test('clearing grid overrides keeps original pixels and snaps their display to the common grid', async ({ page }) => {
  const state = await mockPersonalization(page, { saved: gridSettings() })
  await edit(page)
  await card(page).getByRole('button', { name: /위젯 설정/ }).click()
  await inline(page).getByRole('combobox', { name: '카드 너비 (칸)', exact: true }).selectOption('')
  await inline(page).getByRole('combobox', { name: '카드 높이 (칸)', exact: true }).selectOption('')
  await inline(page).getByRole('button', { name: '편집 계속하기' }).click()
  await save(page)
  const original = await frame(page), rows = Math.round((230 + 8) / original.rowPitch)
  await expect(card(page)).toHaveCSS('height', `${rows * original.rowPitch - 8}px`)
  await expect(card(page)).toHaveAttribute('data-widget-grid-rows', String(rows))
  expect(state.saved.widgets[0]).toMatchObject({ cardColumns: 0, cardRows: 0, cardWidth: 310, cardHeight: 230 })
  await page.reload()
  await page.getByRole('button', { name: '달력 가계부', exact: true }).click()
  await expect(card(page)).toHaveCSS('height', `${rows * original.rowPitch - 8}px`)
  expect(state.errors).toEqual([])
})

test('view and edit preserve cell settings when switching between desktop and mobile widths', async ({ page }) => {
  const state = await mockPersonalization(page, { saved: gridSettings() })
  for (const width of [1440, 1024, 390, 320, 1440]) {
    await page.setViewportSize({ width, height: 900 })
    await expectGridFrame(page, 3, 2)
    await edit(page)
    await expectGridFrame(page, 3, 2)
    await toolbar(page).getByRole('button', { name: '취소', exact: true }).click()
    await expectGridFrame(page, 3, 2)
  }
  expect(state.writes).toEqual([])
  expect(state.errors).toEqual([])
})
