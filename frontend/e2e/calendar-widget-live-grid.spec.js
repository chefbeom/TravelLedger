import { expect, test } from '@playwright/test'
import { calendarSettings, mockPersonalization } from './fixtures/calendarPersonalization.js'

const widgets = page => page.locator('.calendar-widget')
const widget = (page, index = 0) => page.locator(`.calendar-widget[data-aggregate-index="${index}"]`)
const toolbar = page => page.locator('.calendar-draft-toolbar')

function mixedSettings() {
  const saved = calendarSettings()
  saved.layout[0].h = 5
  saved.layout[1].y = saved.layout[2].y = 5
  const widths = [247, 247, 280, 247, 328]
  saved.widgets = saved.widgets.map((config, index) => ({ ...config, kind: index < 5 ? 'TOTAL' : 'NONE', title: `격자 검증 ${index + 1}`, cardWidth: widths[index] || 0, cardHeight: index < 4 ? 190 : 160, cardColumns: index === 3 ? 1 : 0, cardRows: index === 3 ? 1 : 0 }))
  return saved
}

async function frames(page) {
  return page.locator('.household-aggregate-grid').evaluate(grid => {
    const origin = grid.getBoundingClientRect(), columns = (grid.clientWidth + 8) / 9
    const rows = parseFloat(getComputedStyle(grid.closest('.calendar-dashboard')).getPropertyValue('--calendar-layout-cell-height'))
    return { columns, rows, mobile: document.documentElement.dataset.layoutMode === 'mobile', cards: [...grid.querySelectorAll('.calendar-widget')].map(element => {
      const rect = element.getBoundingClientRect()
      return { x: rect.left - origin.left, y: rect.top - origin.top, w: rect.width, h: rect.height, font: getComputedStyle(element.querySelector('strong')).fontSize }
    }) }
  })
}

async function expectAligned(page) {
  await expect.poll(async () => {
    const f = await frames(page)
    const distance = (value, pitch) => Math.abs(value - Math.round(value / pitch) * pitch)
    return Math.max(...f.cards.flatMap(card => [distance(card.x, f.columns), distance(card.y, f.rows), distance(card.w + 8, f.columns), distance(card.h + 8, f.rows)]))
  }, { message: 'Every legacy/new card edge and wrapped row must use the same whole cells as the guide' }).toBeLessThan(1)
}

async function edit(page) {
  await page.getByRole('button', { name: '대시보드 편집', exact: true }).click()
  await expect(page.locator('.calendar-dashboard')).toHaveClass(/calendar-dashboard--editing/)
}

async function startDrag(page) {
  const handle = widget(page).getByRole('button', { name: /위젯 크기 조절/ })
  await handle.evaluate(element => element.scrollIntoView({ block: 'center', behavior: 'instant' }))
  const box = await handle.boundingBox(), x = box.x + box.width / 2, y = box.y + box.height / 2
  const mobile = (await frames(page)).mobile
  const client = mobile ? await page.context().newCDPSession(page) : null
  let cancelled = false
  if (client) await client.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y, id: 1 }] })
  else { await page.mouse.move(x, y); await page.mouse.down() }
  return {
    async move(dx, dy) {
      if (client) await client.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: x + dx, y: y + dy, id: 1 }] })
      else await page.mouse.move(x + dx, y + dy, { steps: 4 })
      await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))))
    },
    async end() {
      if (client) { if (!cancelled) await client.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] }); await client.detach() }
      else await page.mouse.up()
    },
    async cancel() {
      if (client) await client.send('Input.dispatchTouchEvent', { type: 'touchCancel', touchPoints: [] })
      else await page.evaluate(() => window.dispatchEvent(new PointerEvent('pointercancel', { pointerId: 1 })))
      cancelled = true
    },
  }
}

test('mixed legacy and grid cards, wrapping and guides stay aligned without modifying account settings', async ({ page }, testInfo) => {
  const saved = mixedSettings(), original = structuredClone(saved.widgets)
  const state = await mockPersonalization(page, { saved })
  for (const width of [1440, 1024, 390, 320]) {
    await page.setViewportSize({ width, height: 900 })
    await expectAligned(page)
    const before = await frames(page)
    await edit(page)
    await expectAligned(page)
    const guide = page.locator('.calendar-widget-grid-guide')
    await expect(guide).toBeVisible()
    const cell = await guide.locator('span').first().boundingBox()
    const f = await frames(page)
    expect(Math.abs(cell.width - (f.columns - 8))).toBeLessThan(1)
    expect(Math.abs(cell.height - (f.rows - 8))).toBeLessThan(1)
    expect(f.cards).toEqual(before.cards)
    if (width === 1440 || width === 390) await page.locator('.household-aggregate-panel').screenshot({ path: testInfo.outputPath(`aligned-cards-${width}.png`) })
    await toolbar(page).getByRole('button', { name: '취소', exact: true }).click()
    await expect(guide).toHaveCount(0)
    await expectAligned(page)
  }
  expect(state.writes).toEqual([])
  expect(state.saved.widgets).toEqual(original)
  expect(state.errors).toEqual([])
})

test('held mouse/touch gestures paint every snapped size before release and Escape restores all geometry', async ({ page }, testInfo) => {
  const saved = mixedSettings()
  saved.widgets[0] = { ...saved.widgets[0], cardColumns: 3, cardRows: 2 }
  const state = await mockPersonalization(page, { saved })
  await edit(page)
  const before = await frames(page), gesture = await startDrag(page)
  const dx = before.mobile ? 0 : before.columns
  await gesture.move(dx, before.rows * 0.7)
  await expect.poll(async () => Math.abs((await frames(page)).cards[0].h - (before.rows * 3 - 8)), { message: 'A held drag must paint the preview, not wait until pointerup' }).toBeLessThan(1)
  await expect(widget(page)).toHaveAttribute('data-widget-resizing', 'true')
  await expect(widget(page)).toHaveAttribute('data-widget-grid-columns', String(before.mobile ? 9 : 4))
  expect(Math.abs((await frames(page)).cards[0].w - (before.mobile ? before.cards[0].w : before.columns * 4 - 8))).toBeLessThan(1)
  const hint = page.locator('.calendar-widget-resize-hint')
  await expect(hint).toContainText('3행')
  const hintPosition = await hint.boundingBox()
  await gesture.move(dx, before.rows * 1.7)
  await expect.poll(async () => Math.abs((await frames(page)).cards[0].h - (before.rows * 4 - 8))).toBeLessThan(1)
  await expect(hint).toContainText('4행')
  expect((await hint.boundingBox()).y).not.toBe(hintPosition.y)
  await expectAligned(page)
  const preview = await frames(page)
  expect(preview.cards.slice(1).map(({ w, h, font }) => ({ w, h, font }))).toEqual(before.cards.slice(1).map(({ w, h, font }) => ({ w, h, font })))
  expect(state.writes).toEqual([])
  await page.screenshot({ path: testInfo.outputPath('held-resize-preview.png') })
  await page.keyboard.press('Escape')
  await gesture.end()
  await expect(hint).toHaveCount(0)
  await expect(widget(page)).not.toHaveAttribute('data-widget-resizing', 'true')
  expect((await frames(page)).cards).toEqual(before.cards)
  await expect(toolbar(page).getByRole('button', { name: '변경사항 저장', exact: true })).toBeDisabled()
  const commit = await startDrag(page)
  await commit.move(dx, before.rows * 0.7)
  await commit.end()
  const committed = (await frames(page)).cards
  await toolbar(page).getByRole('button', { name: '변경사항 저장', exact: true }).click()
  await expect(toolbar(page)).toHaveCount(0)
  expect((await frames(page)).cards).toEqual(committed)
  expect(state.saved.widgets[0]).toMatchObject({ cardColumns: before.mobile ? 3 : 4, cardRows: 3, cardWidth: 247, cardHeight: 190 })
  await page.reload()
  await page.getByRole('button', { name: '달력 가계부', exact: true }).click()
  await expectAligned(page)
  expect((await frames(page)).cards).toEqual(committed)
  expect(state.errors).toEqual([])
})

test('sub-cell motion and pointer cancellation do not commit inferred legacy dimensions', async ({ page }) => {
  const saved = mixedSettings(), original = structuredClone(saved.widgets)
  const state = await mockPersonalization(page, { saved })
  await edit(page)
  const before = await frames(page), noChange = await startDrag(page)
  await noChange.move(before.mobile ? 0 : before.columns * 0.1, before.rows * 0.1)
  await expect(page.locator('.calendar-widget-resize-hint')).toBeVisible()
  await noChange.end()
  expect((await frames(page)).cards).toEqual(before.cards)
  await expect(toolbar(page).getByRole('button', { name: '변경사항 저장', exact: true })).toBeDisabled()
  const cancelled = await startDrag(page)
  await cancelled.move(before.mobile ? 0 : before.columns, before.rows)
  await expect(widget(page)).toHaveAttribute('data-widget-resizing', 'true')
  await cancelled.cancel()
  await cancelled.end()
  await expect(page.locator('.calendar-widget-resize-hint')).toHaveCount(0)
  expect((await frames(page)).cards).toEqual(before.cards)
  await expect(toolbar(page).getByRole('button', { name: '변경사항 저장', exact: true })).toBeDisabled()
  expect(state.saved.widgets).toEqual(original)
  expect(state.writes).toEqual([])
  expect(state.errors).toEqual([])
})
