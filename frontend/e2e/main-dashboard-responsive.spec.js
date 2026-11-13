import { expect, test } from '@playwright/test'
import { mockPersonalization } from './fixtures/calendarPersonalization.js'

const grid = page => page.locator('.main-dashboard__grid')
const palettes = [
  { id: 'first', type: 'household-metric', size: '2x1', position: { x: 0, y: 0 }, visible: true, options: { metric: 'monthExpense' } },
  { id: 'second', type: 'household-metric', size: '2x1', position: { x: 2, y: 0 }, visible: true, options: { metric: 'monthIncome' } },
  { id: 'third', type: 'household-metric', size: '2x1', position: { x: 4, y: 0 }, visible: true, options: { metric: 'weekExpense' } },
]

async function openMain(page, shared = { payload: { palettes: structuredClone(palettes) }, writes: [] }) {
  await mockPersonalization(page)
  await page.route('**/api/account/preferences/layout-settings/main-dashboard', async route => {
    if (route.request().method() === 'PUT') {
      const payload = route.request().postDataJSON().payload
      shared.writes.push(structuredClone(payload))
      const section = payload._layoutSection
      if (section === '1' || section === '3') {
        shared.payload.compactLayouts = { ...shared.payload.compactLayouts, [section]: payload.compactLayouts[section] }
      } else {
        const compactLayouts = shared.payload.compactLayouts
        shared.payload = structuredClone(payload)
        delete shared.payload._layoutSection
        if (section === 'desktop' && compactLayouts) shared.payload.compactLayouts = compactLayouts
      }
    }
    await route.fulfill({ json: { version: 7, payload: shared.payload } })
  })
  await page.getByRole('button', { name: '메뉴', exact: true }).click()
  await expect(grid(page).locator('[data-palette-id]')).toHaveCount(3)
  await expect.poll(() => grid(page).evaluate(el => el.gridstack?.engine?.nodes.length)).toBe(3)
  return shared
}

async function nodes(page) {
  return grid(page).evaluate(el => el.gridstack.engine.nodes.map(({ id, x, y, w, h }) => ({ id, x, y, w, h })).sort((a, b) => a.id.localeCompare(b.id)))
}

test('resizing the browser does not persist or distort the desktop palette layout', async ({ page }, info) => {
  test.skip(info.project.name !== 'chromium-desktop', 'This case explicitly resizes a desktop viewport.')
  const shared = await openMain(page)
  const desktop = await nodes(page)
  await page.setViewportSize({ width: 390, height: 844 })
  await expect.poll(() => grid(page).evaluate(el => el.gridstack?.getColumn())).toBe(1)
  await page.setViewportSize({ width: 1440, height: 900 })
  await expect.poll(() => grid(page).evaluate(el => el.gridstack?.getColumn())).toBe(9)
  await expect.poll(() => nodes(page)).toEqual(desktop)
  expect(shared.writes).toEqual([])
})

test('mobile palette movement is saved separately and survives a reload', async ({ page }, info) => {
  test.skip(info.project.name !== 'chromium-desktop', 'This case explicitly exercises both viewport sizes.')
  const shared = await openMain(page)
  const desktop = await nodes(page)
  await page.setViewportSize({ width: 390, height: 844 })
  await expect.poll(() => grid(page).evaluate(el => el.gridstack?.getColumn())).toBe(1)
  await page.getByRole('button', { name: '설정', exact: true }).last().click()
  await page.getByRole('button', { name: '편집 시작', exact: true }).click()
  await page.locator('.main-dashboard__tools').getByRole('button', { name: '닫기', exact: true }).click()
  const source = grid(page).locator('[data-palette-id="first"] .main-palette__head')
  const target = grid(page).locator('[data-palette-id="second"] .main-palette__head')
  await source.evaluate(element => element.scrollIntoView({ block: 'start', behavior: 'instant' }))
  const a = await source.boundingBox(), b = await target.boundingBox()
  await page.mouse.move(a.x + a.width / 2, a.y + a.height / 2)
  await page.mouse.down()
  await page.mouse.move(b.x + b.width / 2, b.y + b.height / 2, { steps: 12 })
  await page.mouse.up()
  await expect.poll(() => shared.writes.some(item => item._layoutSection === '1')).toBe(true)
  const savedMobile = structuredClone(shared.payload.compactLayouts['1'])
  expect(shared.payload.palettes).toEqual(palettes)
  await page.setViewportSize({ width: 1440, height: 900 })
  await expect.poll(() => grid(page).evaluate(el => el.gridstack?.getColumn())).toBe(9)
  await expect.poll(() => nodes(page)).toEqual(desktop)
  await page.setViewportSize({ width: 390, height: 844 })
  await expect.poll(() => grid(page).evaluate(el => el.gridstack?.getColumn())).toBe(1)
  await expect.poll(async () => (await nodes(page)).map(({ id, x, y, w, h }) => ({ id, x, y, w, h }))).toEqual(savedMobile.sort((a, b) => a.id.localeCompare(b.id)))
  await page.reload()
  await expect.poll(() => grid(page).evaluate(el => el.gridstack?.getColumn())).toBe(1)
  await expect.poll(() => nodes(page)).toEqual(savedMobile)
})

test('three-column layout is independently retained when switching to phone and desktop', async ({ page }, info) => {
  test.skip(info.project.name !== 'chromium-desktop', 'This case explicitly exercises the compact breakpoints.')
  const shared = await openMain(page)
  const desktop = await nodes(page)
  await page.setViewportSize({ width: 680, height: 900 })
  await expect.poll(() => grid(page).evaluate(el => el.gridstack?.getColumn())).toBe(3)
  await page.getByRole('button', { name: '설정', exact: true }).last().click()
  await page.getByRole('button', { name: '편집 시작', exact: true }).click()
  await page.locator('.main-dashboard__tools').getByRole('button', { name: '닫기', exact: true }).click()
  const source = grid(page).locator('[data-palette-id="first"] .main-palette__head')
  const target = grid(page).locator('[data-palette-id="third"] .main-palette__head')
  await source.evaluate(element => element.scrollIntoView({ block: 'start', behavior: 'instant' }))
  const a = await source.boundingBox(), b = await target.boundingBox()
  await page.mouse.move(a.x + a.width / 2, a.y + a.height / 2)
  await page.mouse.down()
  await page.mouse.move(b.x + b.width / 2, b.y + b.height / 2, { steps: 12 })
  await page.mouse.up()
  await expect.poll(() => shared.writes.some(item => item._layoutSection === '3')).toBe(true)
  const tablet = structuredClone(shared.payload.compactLayouts['3']).sort((a, b) => a.id.localeCompare(b.id))
  await page.setViewportSize({ width: 390, height: 844 })
  await expect.poll(() => grid(page).evaluate(el => el.gridstack?.getColumn())).toBe(1)
  await page.setViewportSize({ width: 1440, height: 900 })
  await expect.poll(() => nodes(page)).toEqual(desktop)
  await page.setViewportSize({ width: 680, height: 900 })
  await expect.poll(() => grid(page).evaluate(el => el.gridstack?.getColumn())).toBe(3)
  await expect.poll(() => nodes(page)).toEqual(tablet)
  expect(shared.payload.palettes).toEqual(palettes)
})

test('touch dragging a palette saves the phone layout', async ({ page }, info) => {
  test.skip(info.project.name !== 'chromium-mobile', 'This case requires a touch-enabled mobile context.')
  const shared = await openMain(page)
  await expect.poll(() => grid(page).evaluate(el => el.gridstack?.getColumn())).toBe(1)
  await page.getByRole('button', { name: '설정', exact: true }).last().click()
  await page.getByRole('button', { name: '편집 시작', exact: true }).click()
  await page.locator('.main-dashboard__tools').getByRole('button', { name: '닫기', exact: true }).click()
  const source = grid(page).locator('[data-palette-id="first"] .main-palette__head')
  const target = grid(page).locator('[data-palette-id="second"] .main-palette__head')
  await source.evaluate(element => element.scrollIntoView({ block: 'start', behavior: 'instant' }))
  const a = await source.boundingBox(), b = await target.boundingBox()
  const cdp = await page.context().newCDPSession(page)
  const start = { x: a.x + a.width / 2, y: a.y + a.height / 2 }
  const end = { x: b.x + b.width / 2, y: b.y + b.height / 2 }
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [start] })
  for (let step = 1; step <= 12; step += 1) {
    const t = step / 12
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: start.x + (end.x - start.x) * t, y: start.y + (end.y - start.y) * t }] })
  }
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] })
  await expect.poll(() => shared.writes.some(item => item._layoutSection === '1')).toBe(true)
  expect(shared.payload.palettes).toEqual(palettes)
  await page.reload()
  await expect.poll(() => nodes(page)).toEqual(structuredClone(shared.payload.compactLayouts['1']).sort((a, b) => a.id.localeCompare(b.id)))
})
