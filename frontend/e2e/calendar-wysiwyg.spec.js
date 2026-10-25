import { expect, test } from '@playwright/test'
import { mockPersonalization } from './fixtures/calendarPersonalization.js'

const canvas = page => page.locator('.calendar-dashboard')
const widget = (page, index) => page.locator(`.calendar-widget[data-aggregate-index="${index}"]`)
async function edit(page) {
  await page.getByRole('button', { name: '대시보드 편집', exact: true }).click()
  await expect(canvas(page)).toHaveClass(/calendar-dashboard--editing/)
  await expect(page.getByRole('dialog', { name: '개인화 설정', exact: true })).toHaveCount(0)
  if ((await page.locator('html').getAttribute('data-layout-mode')) !== 'mobile') await expect.poll(() => page.locator('.calendar-dashboard-grid').evaluate(grid => grid.gridstack?.engine.nodes.filter(node => node.id !== 'calendar-query').length)).toBe(3)
  await expect(page.locator('[data-calendar-panel-id="sheet"]')).toHaveCount(0)
  const titleClearance = await page.locator('[data-calendar-panel-id="aggregate"]').evaluate(palette => {
    const range = document.createRange()
    range.selectNodeContents(palette.querySelector('.panel__header h2'))
    return range.getBoundingClientRect().left - palette.querySelector('[data-palette-move]').getBoundingClientRect().right
  })
  expect(titleClearance, 'The palette grip must not cover the title').toBeGreaterThanOrEqual(0)
}

async function geometry(page) {
  return canvas(page).evaluate(board => {
    const origin = board.getBoundingClientRect()
    return [...board.querySelectorAll('[data-calendar-panel-id] .calendar-dashboard-panel > .panel, .calendar-widget')].map(element => {
      const rect = element.getBoundingClientRect()
      return { key: element.matches('.calendar-widget') ? `widget-${element.dataset.aggregateIndex}` : element.closest('[data-calendar-panel-id]').dataset.calendarPanelId, x: rect.x - origin.x, y: rect.y - origin.y, width: rect.width, height: rect.height }
    })
  })
}

async function expectSameGeometry(page, before) {
  await expect.poll(async () => {
    const after = await geometry(page)
    if (after.length !== before.length) return 10000
    return Math.max(...after.flatMap(item => ['x', 'y', 'width', 'height'].map(key => Math.abs(item[key] - before.find(old => old.key === item.key)?.[key]))))
  }, { message: 'Edit, saved and reloaded palettes must use the same coordinates, card dimensions and content layout' }).toBeLessThan(1)
}

async function dragPointer(page, handle, dx, dy, touch = false) {
  await handle.evaluate(element => element.scrollIntoView({ block: 'center', behavior: 'instant' }))
  const rect = await handle.boundingBox(), x = rect.x + rect.width / 2, y = rect.y + rect.height / 2
  if (touch) {
    const client = await page.context().newCDPSession(page)
    await client.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y, id: 1 }] })
    for (let step = 1; step <= 12; step++) await client.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: x + dx * step / 12, y: y + dy * step / 12, id: 1 }] })
    await client.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] })
    await client.detach()
  } else {
    await page.mouse.move(x, y)
    await page.mouse.down()
    await page.mouse.move(x + dx, y + dy, { steps: 16 })
    await page.mouse.up()
  }
}

test('view and edit share palette geometry, and modal transaction sheets never become palettes', async ({ page }) => {
  const state = await mockPersonalization(page)
  for (const width of [1440, 1024, 390]) {
    await page.setViewportSize({ width, height: 900 })
    for (const theme of ['default', 'toss']) {
      if (((await page.locator('html').getAttribute('data-theme')) === 'toss') !== (theme === 'toss')) await page.getByRole('button', { name: theme === 'toss' ? '다크 테마' : '기본 테마', exact: true }).click()
      await expect(page.locator('[data-calendar-panel-id]')).toHaveCount(3)
      const before = await geometry(page)
      await edit(page)
      await expectSameGeometry(page, before)
      await page.locator('.calendar-draft-toolbar').getByRole('button', { name: '취소', exact: true }).click()
      await expectSameGeometry(page, before)
    }
  }
  await page.getByRole('button', { name: '거래 시트 열기', exact: true }).click()
  await expect(page.getByRole('dialog', { name: /거래 시트/ })).toBeVisible()
  expect(state.writes).toEqual([])
  expect(state.errors).toEqual([])
})

test('selected widget edits update the real card and persist size, font, color and detail visibility', async ({ page }, testInfo) => {
  const state = await mockPersonalization(page)
  await edit(page)
  await widget(page, 0).getByRole('button', { name: /위젯 설정/ }).click()
  const inspector = page.getByRole('region', { name: '위젯 직접 편집', exact: true })
  await expect(inspector).toBeVisible()
  await inspector.getByLabel('위젯 이름', { exact: true }).fill('직접 편집한 합계')
  await inspector.getByRole('combobox', { name: '글자 크기', exact: true }).selectOption('LARGE')
  await inspector.getByRole('combobox', { name: '글자 색상', exact: true }).selectOption('MINT')
  await inspector.getByLabel('기간 표시', { exact: true }).uncheck()
  await inspector.getByLabel('수입·지출 및 상세 정보 표시', { exact: true }).uncheck()
  await inspector.getByRole('combobox', { name: '카드 너비 (칸)', exact: true }).selectOption('2')
  await inspector.getByRole('combobox', { name: '카드 높이 (칸)', exact: true }).selectOption('2')
  await expect(widget(page, 0).locator('.household-aggregate-card__eyebrow')).toHaveText('직접 편집한 합계')
  expect(await widget(page, 0).evaluate(card => {
    const range = document.createRange()
    range.selectNodeContents(card.querySelector('.household-aggregate-card__eyebrow'))
    return range.getBoundingClientRect().left - card.querySelector('.calendar-widget-move-handle').getBoundingClientRect().right
  }), 'The widget grip must not cover the title').toBeGreaterThanOrEqual(0)
  await expect(widget(page, 0).locator('.household-aggregate-card__copy strong')).toHaveCSS('font-size', '30px')
  await expect(widget(page, 0).locator('.household-aggregate-card__copy strong')).toHaveCSS('color', 'rgb(4, 120, 87)')
  await expect(widget(page, 0).locator('.household-aggregate-card__meta')).toHaveCount(0)
  await expect(widget(page, 0).locator('.household-aggregate-card__copy small')).toHaveCount(0)
  await page.screenshot({ path: testInfo.outputPath('editing-direct-widget.png') })
  await inspector.getByRole('button', { name: '편집 계속하기' }).click()
  const before = await geometry(page)
  expect(state.writes).toEqual([])
  await page.locator('.calendar-draft-toolbar').getByRole('button', { name: '변경사항 저장', exact: true }).click()
  await expect(canvas(page)).not.toHaveClass(/calendar-dashboard--editing/)
  await expectSameGeometry(page, before)
  expect(state.saved.widgets[0]).toMatchObject({ title: '직접 편집한 합계', textSize: 'LARGE', textColor: 'MINT', cardColumns: 2, cardRows: 2, showPeriod: false, showDetails: false })
  await page.reload()
  await page.getByRole('button', { name: '달력 가계부', exact: true }).click()
  await expectSameGeometry(page, before)
  await canvas(page).screenshot({ path: testInfo.outputPath('saved-wysiwyg.png') })
  expect(state.errors).toEqual([])
})

test('direct content controls update payment totals, goals and graph series, while cancel restores the original', async ({ page }) => {
  const state = await mockPersonalization(page)
  await edit(page)
  await widget(page, 0).getByRole('button', { name: /위젯 설정/ }).click()
  const inspector = page.getByRole('region', { name: '위젯 직접 편집', exact: true })
  await inspector.getByRole('combobox', { name: '표시할 내용', exact: true }).selectOption('PAYMENT_METHOD')
  await inspector.getByRole('combobox', { name: '결제수단', exact: true }).selectOption('103')
  await inspector.getByRole('combobox', { name: '집계 기간', exact: true }).selectOption('YEAR')
  await expect(widget(page, 0).locator('.household-aggregate-card__eyebrow')).toContainText('검증용 카드')
  await inspector.getByRole('combobox', { name: '표시할 내용', exact: true }).selectOption('MONTHLY_GOAL')
  await inspector.getByLabel('월 지출 목표', { exact: true }).fill('800000')
  await inspector.getByLabel('1회 지출 기준', { exact: true }).fill('10000')
  await expect(widget(page, 0)).toContainText('800,000')
  await inspector.getByRole('combobox', { name: '표시할 내용', exact: true }).selectOption('MONTHLY_CUMULATIVE_CHART')
  await inspector.getByLabel('수입 누적선', { exact: true }).uncheck()
  await inspector.getByLabel('지난 기간 비교선', { exact: true }).check()
  await expect(widget(page, 0).locator('polyline.household-aggregate-chart__line--income')).toHaveCount(0)
  await expect(widget(page, 0).locator('polyline.household-aggregate-chart__line--expense')).toHaveCount(2)
  await inspector.getByRole('button', { name: '편집 계속하기' }).click()
  const title = widget(page, 0).getByRole('textbox', { name: '위젯 제목 직접 수정', exact: true })
  await title.fill('직접 타이핑한 제목')
  await title.press('Enter')
  await expect(widget(page, 0).locator('.household-aggregate-card__eyebrow')).toHaveText('직접 타이핑한 제목')
  page.once('dialog', prompt => prompt.accept())
  await page.locator('.calendar-draft-toolbar').getByRole('button', { name: '취소', exact: true }).click()
  await expect(widget(page, 0)).not.toContainText('직접 타이핑한 제목')
  await expect(widget(page, 0)).not.toHaveClass(/household-aggregate-card--chart/)
  expect(state.writes).toEqual([])
  expect(state.errors).toEqual([])
})

test('widget ordering and keyboard resizing operate on the selected card without a separate settings dialog', async ({ page }) => {
  const state = await mockPersonalization(page)
  await edit(page)
  const title = widget(page, 0).getByRole('textbox', { name: '위젯 제목 직접 수정', exact: true })
  await title.fill('순서 검증 첫 위젯')
  await title.press('Enter')
  const before = await widget(page, 0).boundingBox()
  const rowHeight = await canvas(page).evaluate(element => parseFloat(getComputedStyle(element).getPropertyValue('--calendar-layout-cell-height')))
  const rows = Math.round((before.height + 8) / rowHeight) + 1
  await widget(page, 0).getByRole('button', { name: /위젯 크기 조절/ }).focus()
  await page.keyboard.press('ArrowDown')
  await expect.poll(async () => Math.round((await widget(page, 0).boundingBox()).height)).toBe(rowHeight * rows - 8)
  await widget(page, 0).getByRole('button', { name: /위젯 이동/ }).focus()
  await page.keyboard.press('ArrowRight')
  await expect(widget(page, 1).locator('.household-aggregate-card__eyebrow')).toHaveText('순서 검증 첫 위젯')
  await widget(page, 1).getByRole('button', { name: /위젯 이동/ }).focus()
  await page.keyboard.press('ArrowDown')
  await expect(widget(page, 2).locator('.household-aggregate-card__eyebrow')).toHaveText('순서 검증 첫 위젯')
  await page.locator('.calendar-draft-toolbar').getByRole('button', { name: '변경사항 저장', exact: true }).click()
  await expect(canvas(page)).not.toHaveClass(/calendar-dashboard--editing/)
  expect(state.saved.widgets[2]).toMatchObject({ title: '순서 검증 첫 위젯', cardRows: rows })
  expect(state.errors).toEqual([])
})

test('mobile keyboard controls reorder palettes and resize height without changing desktop width', async ({ page }) => {
  const state = await mockPersonalization(page)
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(page.locator('html')).toHaveAttribute('data-layout-mode', 'mobile')
  await edit(page)
  const palette = page.locator('[data-calendar-panel-id="aggregate"]')
  const before = await palette.boundingBox()
  const resize = palette.getByRole('button', { name: '사용자 설정 집계 팔레트 크기 조절', exact: true })
  await resize.focus()
  await page.keyboard.press('ArrowRight')
  await page.keyboard.press('ArrowDown')
  await expect.poll(async () => Math.round((await palette.boundingBox()).height)).toBe(Math.round(before.height + 112))
  await palette.getByRole('button', { name: '사용자 설정 집계 팔레트 이동', exact: true }).focus()
  await page.keyboard.press('ArrowDown')
  await expect.poll(() => page.locator('[data-calendar-panel-id]').evaluateAll(items => items.sort((a, b) => a.getBoundingClientRect().top - b.getBoundingClientRect().top)[0].dataset.calendarPanelId)).toBe('calendar')
  await page.locator('.calendar-draft-toolbar').getByRole('button', { name: '변경사항 저장', exact: true }).click()
  await expect(canvas(page)).not.toHaveClass(/calendar-dashboard--editing/)
  expect(state.saved.layout.find(item => item.id === 'aggregate').w).toBe(9)
  expect(state.errors).toEqual([])
})

test('chart corner resize previews the real chart and survives saving', async ({ page }) => {
  const state = await mockPersonalization(page)
  await edit(page)
  const chart = widget(page, 3)
  const before = await chart.boundingBox()
  const touch = page.viewportSize().width < 760
  const rowHeight = await canvas(page).evaluate(element => parseFloat(getComputedStyle(element).getPropertyValue('--calendar-layout-cell-height')))
  const rows = Math.round((before.height + 8) / rowHeight) + 1
  await dragPointer(page, chart.getByRole('button', { name: /위젯 크기 조절/ }), touch ? -20 : 60, rowHeight * 0.7, touch)
  await expect.poll(async () => Math.round((await chart.boundingBox()).height)).toBe(rowHeight * rows - 8)
  const after = await chart.boundingBox()
  await page.locator('.calendar-draft-toolbar').getByRole('button', { name: '변경사항 저장', exact: true }).click()
  await expect(canvas(page)).not.toHaveClass(/calendar-dashboard--editing/)
  expect(state.saved.widgets[3].cardRows).toBe(rows)
  expect(state.saved.widgets[3].cardColumns).toBe(touch ? 0 : Math.round((after.width + 8) / (((await chart.locator('..').boundingBox()).width + 8) / 9)))
  expect(Math.abs((await chart.boundingBox()).height - after.height)).toBeLessThan(1)
  expect(state.errors).toEqual([])
})

test('palette movement has grab cursors and a drop preview, with direct touch resizing on mobile', async ({ page }) => {
  const state = await mockPersonalization(page)
  await edit(page)
  if (page.viewportSize().width < 760) {
    const palette = page.locator('[data-calendar-panel-id="aggregate"]')
    const before = await palette.boundingBox()
    await dragPointer(page, palette.getByRole('button', { name: '사용자 설정 집계 팔레트 크기 조절', exact: true }), 0, 112, true)
    await expect.poll(async () => Math.round((await palette.boundingBox()).height)).toBe(Math.round(before.height + 112))
  } else {
    const handle = page.locator('[data-calendar-panel-id="quick-entry"] .panel__header h2')
    await expect(handle).toHaveCSS('cursor', 'grab')
    await handle.evaluate(element => element.scrollIntoView({ block: 'center', behavior: 'instant' }))
    const box = await handle.boundingBox()
    await page.mouse.move(box.x + 25, box.y + 10)
    await page.mouse.down()
    await page.mouse.move(box.x + 25, box.y + 250, { steps: 20 })
    await expect(page.locator('.grid-stack-placeholder > .placeholder-content')).toBeVisible()
    await expect(page.locator('.ui-draggable-dragging .panel__header')).toHaveCSS('cursor', 'grabbing')
    await expect.poll(() => page.locator('.calendar-dashboard-grid').evaluate(grid => grid.gridstack.engine.nodes.find(node => node.id === 'quick-entry').y)).toBeGreaterThan(2)
    await page.mouse.up()
  }
  const beforeSave = await geometry(page)
  await page.locator('.calendar-draft-toolbar').getByRole('button', { name: '변경사항 저장', exact: true }).click()
  await expect(canvas(page)).not.toHaveClass(/calendar-dashboard--editing/)
  await expectSameGeometry(page, beforeSave)
  expect(state.writes).toHaveLength(1)
  expect(state.errors).toEqual([])
})

test('direct move gestures reorder widgets on desktop and palettes with touch on mobile', async ({ page }) => {
  const state = await mockPersonalization(page)
  await edit(page)
  if ((await page.locator('html').getAttribute('data-layout-mode')) === 'mobile') {
    const handle = page.locator('[data-calendar-panel-id="aggregate"] .panel__header h2')
    await handle.evaluate(element => element.scrollIntoView({ block: 'center', behavior: 'instant' }))
    const source = await handle.boundingBox()
    await dragPointer(page, handle, 0, Math.min(400, page.viewportSize().height - 70 - (source.y + source.height / 2)), true)
    await expect.poll(() => page.locator('[data-calendar-panel-id]').evaluateAll(items => items.sort((a, b) => a.getBoundingClientRect().top - b.getBoundingClientRect().top)[0].dataset.calendarPanelId)).toBe('calendar')
  } else {
    await widget(page, 0).getByRole('textbox', { name: '위젯 제목 직접 수정', exact: true }).fill('마우스로 이동한 위젯')
    await widget(page, 0).getByRole('textbox', { name: '위젯 제목 직접 수정', exact: true }).press('Enter')
    const handle = widget(page, 0).getByRole('button', { name: /위젯 이동/ })
    await handle.evaluate(element => element.scrollIntoView({ block: 'center', behavior: 'instant' }))
    const source = await handle.boundingBox(), target = await widget(page, 1).boundingBox()
    await dragPointer(page, handle, target.x + target.width / 2 - source.x - source.width / 2, target.y + target.height / 2 - source.y - source.height / 2)
    await expect(widget(page, 1).locator('.household-aggregate-card__eyebrow')).toHaveText('마우스로 이동한 위젯')
  }
  const beforeSave = await geometry(page)
  await page.locator('.calendar-draft-toolbar').getByRole('button', { name: '변경사항 저장', exact: true }).click()
  await expect(canvas(page)).not.toHaveClass(/calendar-dashboard--editing/)
  await expectSameGeometry(page, beforeSave)
  expect(state.writes).toHaveLength(1)
  expect(state.errors).toEqual([])
})

test('read-mode graph detail opens over the full viewport instead of being clipped by a palette', async ({ page }) => {
  const state = await mockPersonalization(page)
  await widget(page, 3).getByRole('button', { name: /상세 보기/ }).click()
  const modal = page.getByRole('dialog', { name: '누적 그래프 상세 보기', exact: true })
  await expect(modal).toBeVisible()
  const rect = await modal.boundingBox()
  expect(Math.abs(rect.x)).toBeLessThan(1)
  expect(Math.abs(rect.y)).toBeLessThan(1)
  expect(Math.abs(rect.width - page.viewportSize().width)).toBeLessThan(1)
  expect(Math.abs(rect.height - page.viewportSize().height)).toBeLessThan(1)
  await modal.getByRole('button', { name: '닫기', exact: true }).click()
  await expect(modal).toHaveCount(0)
  expect(state.writes).toEqual([])
  expect(state.errors).toEqual([])
})

test('fixed-height palettes keep transaction inputs reachable by native touch scrolling', async ({ page }) => {
  const state = await mockPersonalization(page)
  await page.setViewportSize({ width: 390, height: 844 })
  const panel = page.locator('.household-entry-panel')
  await panel.evaluate(element => element.scrollIntoView({ block: 'center', behavior: 'instant' }))
  const box = await panel.boundingBox(), x = box.x + box.width / 2
  const y = Math.min(694, box.y + box.height - 100)
  const client = await page.context().newCDPSession(page)
  await client.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y, id: 2 }] })
  for (let step = 1; step <= 12; step++) await client.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x, y: y - 300 * step / 12, id: 2 }] })
  await client.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] })
  await client.detach()
  await expect.poll(() => panel.evaluate(element => element.scrollTop)).toBeGreaterThan(0)
  await panel.getByLabel('거래 날짜', { exact: true }).fill('2026-10-20')
  await panel.getByRole('button', { name: '다음날', exact: true }).click()
  await expect(panel.getByLabel('거래 날짜', { exact: true })).toHaveValue('2026-10-21')
  await panel.getByRole('button', { name: '거래 등록', exact: true }).scrollIntoViewIfNeeded()
  await expect(panel.getByRole('button', { name: '거래 등록', exact: true })).toBeInViewport()
  expect(state.writes).toEqual([])
  expect(state.errors).toEqual([])
})
