import { expect, test } from '@playwright/test'
import { calendarSettings, mockPersonalization } from './fixtures/calendarPersonalization.js'

function twoRowSettings() {
  const settings = calendarSettings()
  settings.layout = settings.layout.map(panel => ({ ...panel, ...(panel.id === 'aggregate' ? { h: 2 } : {}) }))
  const kinds = ['TOTAL', 'PAYMENT_METHOD', 'MONTHLY_CUMULATIVE_CHART', 'TOTAL', 'PAYMENT_METHOD', 'TOTAL']
  const titles = ['이번 달 총 지출 합계', '올해 가족 카드 지출 합계', '이번 달 누적 그래프', '이번 달 현대 네이버 카드 지출 합계', '올해 현대 네이버 카드 지출 합계', '이번 달 가족 카드 지출 합계']
  return {
    ...settings,
    widgets: settings.widgets.map((item, index) => ({ ...item, kind: kinds[index], title: titles[index],
      cardColumns: index === 2 ? 3 : 2, cardRows: index === 2 ? 2 : 1,
      paymentMethodId: kinds[index] === 'PAYMENT_METHOD' ? 103 : null, hidden: false })),
  }
}

async function measure(page) {
  return page.locator('[data-calendar-panel-id="aggregate"]').evaluate(palette => {
    const panel = palette.querySelector('.household-aggregate-panel')
    const grid = panel.querySelector('.household-aggregate-grid')
    const board = palette.closest('.calendar-dashboard')
    const outerPitch = parseFloat(getComputedStyle(board).getPropertyValue('--calendar-layout-cell-height'))
    const innerPitch = parseFloat(getComputedStyle(grid).gridAutoRows) + parseFloat(getComputedStyle(grid).rowGap)
    const panelRect = panel.getBoundingClientRect()
    const cards = [...grid.querySelectorAll('.calendar-widget')].map(card => {
      const rect = card.getBoundingClientRect()
      return { x: Number(card.dataset.widgetGridX), y: Number(card.dataset.widgetGridY),
        w: Number(card.dataset.widgetGridColumns), h: Number(card.dataset.widgetGridRows),
        top: rect.top, bottom: rect.bottom, height: rect.height,
        overflowY: Math.max(0, card.scrollHeight - card.clientHeight),
        overflowX: Math.max(0, card.scrollWidth - card.clientWidth) }
    })
    return { outerPitch, innerPitch, panelHeight: panelRect.height,
      panelOverflowY: Math.max(0, panel.scrollHeight - panel.clientHeight),
      panelOverflowX: Math.max(0, panel.scrollWidth - panel.clientWidth),
      panelBottom: panelRect.bottom, cards }
  })
}

test('two rows of aggregate widgets fit inside a two-cell palette in view and edit modes', async ({ page }, info) => {
  test.skip(info.project.name !== 'chromium-desktop', 'Desktop matrix explicitly checks both viewport sizes.')
  const state = await mockPersonalization(page, { saved: twoRowSettings() })
  for (const width of [1600, 1366, 1024]) {
    await page.setViewportSize({ width, height: 1000 })
    for (const editing of [false, true]) {
      if (editing) await page.getByRole('button', { name: '대시보드 편집', exact: true }).click()
      await expect.poll(async () => {
        const current = await measure(page)
        return Math.max(current.panelOverflowY, ...current.cards.map(card => card.overflowY))
      }, { message: `content fits without nested scrolling at ${width}` }).toBeLessThanOrEqual(1)
      const layout = await measure(page)
      expect(layout.cards).toHaveLength(6)
      if (width >= 1366) {
        expect(Math.max(...layout.cards.map(card => card.y + card.h))).toBe(2)
        expect(Math.abs(layout.panelHeight - (2 * layout.outerPitch - 8))).toBeLessThan(2)
      } else {
        // If the configured text cannot physically fit, grow only the affected
        // cards and the palette instead of clipping or adding nested scrollbars.
        expect(layout.panelHeight).toBeGreaterThan(2 * layout.outerPitch - 8)
      }
      expect(layout.panelOverflowY, `aggregate panel height at ${width}`).toBeLessThanOrEqual(1)
      expect(layout.panelOverflowX).toBeLessThanOrEqual(1)
      expect(layout.cards.map(card => card.overflowY), `card content at ${width}`).toEqual([0, 0, 0, 0, 0, 0])
      expect(layout.cards.map(card => card.overflowX), `card width at ${width}`).toEqual([0, 0, 0, 0, 0, 0])
      for (const card of layout.cards) expect(card.bottom).toBeLessThanOrEqual(layout.panelBottom + 1)
      if (editing) {
        await page.locator('.calendar-draft-toolbar').getByRole('button', { name: '취소', exact: true }).click()
      }
    }
  }
  expect(state.writes).toEqual([])
  expect(state.errors).toEqual([])
})

test('mobile and long configured text remain readable without nested aggregate scroll', async ({ page }) => {
  const settings = twoRowSettings()
  settings.widgets[0].title = '아주 긴 제목도 개인 설정을 유지하면서 카드 안에서 모두 보이도록 확인하는 집계 항목'
  settings.widgets[0].textSize = 'LARGE'
  settings.widgets[0].textColor = 'MINT'
  await page.setViewportSize({ width: 390, height: 844 })
  const state = await mockPersonalization(page, { saved: settings })
  for (const width of [390, 320]) {
    await page.setViewportSize({ width, height: 844 })
    for (const editing of [false, true]) {
      if (editing) await page.getByRole('button', { name: '대시보드 편집', exact: true }).click()
      await expect.poll(async () => {
        const current = await measure(page)
        return Math.max(current.panelOverflowY, ...current.cards.map(card => card.overflowY))
      }).toBeLessThanOrEqual(1)
      const layout = await measure(page)
      expect(layout.panelOverflowX).toBeLessThanOrEqual(1)
      expect(layout.cards.map(card => card.overflowX)).toEqual([0, 0, 0, 0, 0, 0])
      for (const card of layout.cards) expect(card.bottom).toBeLessThanOrEqual(layout.panelBottom + 1)
      await expect(page.locator('.calendar-widget').first()).toHaveAttribute('data-aggregate-text-size', 'LARGE')
      await expect(page.locator('.calendar-widget').first()).toHaveAttribute('data-aggregate-text-color', 'MINT')
      const queryTop = (await page.locator('[data-calendar-panel-id="calendar-query"]').boundingBox()).y
      expect(queryTop).toBeGreaterThanOrEqual(layout.panelBottom - 1)
      if (editing) await page.locator('.calendar-draft-toolbar').getByRole('button', { name: '취소', exact: true }).click()
    }
  }
  expect(state.writes).toEqual([])
  expect(state.saved.widgets[0]).toMatchObject({ cardRows: 1, textSize: 'LARGE', textColor: 'MINT' })
  expect(state.errors).toEqual([])
})

test('moving another palette does not save the aggregate presentation height', async ({ page }, info) => {
  test.skip(info.project.name !== 'chromium-desktop')
  await page.setViewportSize({ width: 1024, height: 900 })
  const state = await mockPersonalization(page, { saved: twoRowSettings() })
  await expect.poll(async () => (await measure(page)).panelHeight).toBeGreaterThan(250)
  await page.getByRole('button', { name: '대시보드 편집', exact: true }).click()
  const handle = page.getByRole('button', { name: '날짜·조회 팔레트 이동', exact: true })
  await handle.scrollIntoViewIfNeeded()
  const rect = await handle.boundingBox()
  const pitch = await page.locator('.calendar-dashboard').evaluate(element => parseFloat(getComputedStyle(element).getPropertyValue('--calendar-layout-cell-height')))
  const x = rect.x + rect.width / 2, y = rect.y + rect.height / 2
  await page.mouse.move(x, y)
  await page.mouse.down()
  await page.mouse.move(x, y + pitch, { steps: 16 })
  await page.mouse.up()
  await page.locator('.calendar-draft-toolbar').getByRole('button', { name: '변경사항 저장', exact: true }).click()
  await expect(page.locator('.calendar-draft-toolbar')).toHaveCount(0)
  expect(state.saved.layout.find(panel => panel.id === 'aggregate')).toEqual({ id: 'aggregate', x: 0, y: 0, w: 9, h: 2 })
  expect(state.saved.widgets.map(widget => widget.cardRows)).toEqual([1, 1, 2, 1, 1, 1])
  expect(state.errors).toEqual([])
})

test('resizing only the width does not save the temporary readable height', async ({ page }, info) => {
  test.skip(info.project.name !== 'chromium-desktop')
  await page.setViewportSize({ width: 1024, height: 900 })
  const state = await mockPersonalization(page, { saved: twoRowSettings() })
  const card = page.locator('.calendar-widget[data-aggregate-index="0"]')
  await expect(card).toHaveAttribute('data-widget-rows', '1')
  await expect(card).toHaveAttribute('data-widget-grid-rows', '2')
  await page.getByRole('button', { name: '대시보드 편집', exact: true }).click()
  const handle = card.getByRole('button', { name: /위젯 크기 조절/ })
  await handle.scrollIntoViewIfNeeded()
  const rect = await handle.boundingBox()
  const pitch = await card.evaluate(element => (element.parentElement.clientWidth + 8) / 9)
  const x = rect.x + rect.width / 2, y = rect.y + rect.height / 2
  await page.mouse.move(x, y)
  await page.mouse.down()
  await page.mouse.move(x + pitch, y, { steps: 12 })
  await page.mouse.up()
  await expect(card).toHaveAttribute('data-widget-columns', '3')
  await expect(card).toHaveAttribute('data-widget-rows', '1')
  await page.locator('.calendar-draft-toolbar').getByRole('button', { name: '변경사항 저장', exact: true }).click()
  await expect(page.locator('.calendar-draft-toolbar')).toHaveCount(0)
  expect(state.saved.widgets[0]).toMatchObject({ cardColumns: 3, cardRows: 1 })
  expect(state.errors).toEqual([])
})
