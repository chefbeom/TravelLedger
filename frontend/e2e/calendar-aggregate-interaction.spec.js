import { expect, test } from '@playwright/test'
import { calendarSettings, mockPersonalization } from './fixtures/calendarPersonalization.js'

const widget = (page, index) => page.locator(`.calendar-widget[data-aggregate-index="${index}"]`)
const inspector = page => page.getByRole('region', { name: '위젯 직접 편집', exact: true })
const board = page => page.locator('.calendar-dashboard')

function aggregateSettings() {
  const saved = calendarSettings()
  saved.widgets = saved.widgets.map((item, index) => ({
    ...item, kind: index < 5 ? (index % 2 ? 'PAYMENT_METHOD' : 'TOTAL') : 'NONE',
    title: `개별 집계 ${index + 1}`, paymentMethodId: index % 2 ? 103 : null,
    textSize: ['SMALL', 'MEDIUM', 'LARGE'][index % 3],
    textColor: ['SKY', 'MINT', 'PINK', 'GOLD', 'DEFAULT'][index],
    cardWidth: index === 0 ? 260 : 0, cardHeight: 0,
    showPeriod: index !== 2, showDetails: index !== 3,
  }))
  return saved
}

async function edit(page) {
  await page.getByRole('button', { name: '대시보드 편집', exact: true }).click()
  await expect(board(page)).toHaveClass(/calendar-dashboard--editing/)
  await expect(page.locator('.calendar-draft-toolbar').getByRole('button', { name: '취소', exact: true })).toBeEnabled()
}

async function appearance(card) {
  return card.evaluate(element => {
    const rect = element.getBoundingClientRect()
    const properties = target => {
      if (!target) return null
      const style = getComputedStyle(target)
      return { font: style.fontSize, color: style.color, fill: style.webkitTextFillColor }
    }
    return {
      width: Math.round(rect.width), height: Math.round(rect.height),
      title: properties(element.querySelector('.household-aggregate-card__eyebrow')),
      amount: properties(element.querySelector('.household-aggregate-card__copy strong')),
      period: properties(element.querySelector('.household-aggregate-card__copy small')),
      detail: properties(element.querySelector('.household-aggregate-card__meta span')),
    }
  })
}

test('stored per-widget typography and colors are identical in view and edit, in both themes', async ({ page }, testInfo) => {
  const state = await mockPersonalization(page, { saved: aggregateSettings() })
  for (const theme of ['default', 'toss']) {
    if (theme === 'toss') await page.getByRole('button', { name: '다크 테마', exact: true }).click()
    const color = theme === 'toss' ? 'rgb(125, 211, 252)' : 'rgb(3, 105, 161)'
    const before = await appearance(widget(page, 0))
    await testInfo.attach(`${theme}-widget-appearance`, { body: JSON.stringify(before), contentType: 'application/json' })
    expect(before.title.font).toBe('12px')
    expect(before.amount.font).toBe('20px')
    expect(before.detail.font).toBe('11px')
    for (const part of [before.title, before.amount, before.period, before.detail]) {
      expect(part.color).toBe(color)
      expect(part.fill).toBe(color)
    }
    const colors = theme === 'toss' ? ['rgb(125, 211, 252)', 'rgb(110, 231, 183)', 'rgb(249, 168, 212)', 'rgb(252, 211, 77)'] : ['rgb(3, 105, 161)', 'rgb(4, 120, 87)', 'rgb(190, 24, 93)', 'rgb(161, 98, 7)']
    for (let index = 1; index < 4; index++) {
      const card = await appearance(widget(page, index))
      for (const part of [card.title, card.amount, card.period, card.detail].filter(Boolean)) {
        expect(part.color).toBe(colors[index])
        expect(part.fill).toBe(colors[index])
      }
    }
    await expect(widget(page, 0).locator('.household-aggregate-card__copy small')).toHaveText('이번 달 기준 1건')
    await expect(widget(page, 1).locator('.household-aggregate-card__eyebrow')).toHaveCSS('font-size', '14px')
    await expect(widget(page, 2).locator('.household-aggregate-card__eyebrow')).toHaveCSS('font-size', '16px')
    await expect(widget(page, 2).locator('.household-aggregate-card__copy small')).toHaveText('1건')
    await expect(widget(page, 3).locator('.household-aggregate-card__meta')).toHaveCount(0)
    await edit(page)
    expect(await appearance(widget(page, 0))).toEqual(before)
    await page.locator('.calendar-draft-toolbar').getByRole('button', { name: '취소', exact: true }).click()
    expect(await appearance(widget(page, 0))).toEqual(before)
  }
  expect(state.errors).toEqual([])
  expect(state.writes).toEqual([])
})

test('changing a card frame never rescales its text or its siblings, and persists after saving', async ({ page }) => {
  const state = await mockPersonalization(page, { saved: aggregateSettings() })
  await edit(page)
  const before = await Promise.all([0, 1, 2, 3, 4].map(index => appearance(widget(page, index))))
  await widget(page, 0).getByRole('button', { name: /위젯 설정/ }).click()
  await inspector(page).getByRole('combobox', { name: '카드 너비 (칸)', exact: true }).selectOption('3')
  await inspector(page).getByRole('combobox', { name: '카드 높이 (칸)', exact: true }).selectOption('2')
  const resized = await appearance(widget(page, 0))
  const rowHeight = await page.locator('.household-aggregate-grid').evaluate(element => parseFloat(getComputedStyle(element).getPropertyValue('--calendar-widget-cell-height')))
  expect(resized.height).toBe(rowHeight * 2 - 8)
  for (const part of ['title', 'amount', 'period', 'detail']) expect(resized[part]).toEqual(before[0][part])
  for (let index = 1; index < 5; index++) expect(await appearance(widget(page, index))).toEqual(before[index])
  await inspector(page).getByRole('combobox', { name: '글자 크기', exact: true }).selectOption('LARGE')
  await expect(widget(page, 0).locator('.household-aggregate-card__eyebrow')).toHaveCSS('font-size', '16px')
  for (let index = 1; index < 5; index++) expect(await appearance(widget(page, index))).toEqual(before[index])
  await inspector(page).getByRole('button', { name: '편집 계속하기' }).click()
  const edited = await appearance(widget(page, 0))
  await page.locator('.calendar-draft-toolbar').getByRole('button', { name: '변경사항 저장', exact: true }).click()
  await expect(board(page)).not.toHaveClass(/calendar-dashboard--editing/)
  expect(await appearance(widget(page, 0))).toEqual(edited)
  await page.reload()
  await page.getByRole('button', { name: '달력 가계부', exact: true }).click()
  expect(await appearance(widget(page, 0))).toEqual(edited)
  expect(state.saved.widgets[0]).toMatchObject({ cardWidth: 260, cardHeight: 0, cardColumns: 3, cardRows: 2, textSize: 'LARGE', textColor: 'SKY' })
  for (let index = 1; index < 5; index++) expect(await appearance(widget(page, index))).toEqual(before[index])
  expect(state.errors).toEqual([])
})

test('aggregate details wrap without clipping, cards are flat, and header tools do not cover the badge', async ({ page }, testInfo) => {
  const saved = aggregateSettings()
  saved.widgets[0].cardWidth = 200
  const state = await mockPersonalization(page, { saved })
  await edit(page)
  const layout = await page.locator('[data-calendar-panel-id="aggregate"]').evaluate(palette => {
    const card = palette.querySelector('.calendar-widget')
    const meta = card.querySelector('.household-aggregate-card__meta')
    const copy = card.querySelector('.household-aggregate-card__copy')
    const header = palette.querySelector('.household-aggregate-header')
    const tools = palette.querySelector('.calendar-palette-tools')
    const badge = palette.querySelector('.panel__badge')
    const gap = (a, b) => b.getBoundingClientRect().top - a.getBoundingClientRect().bottom
    const overlaps = (a, b) => {
      const x = a.getBoundingClientRect(), y = b.getBoundingClientRect()
      return Math.min(x.right, y.right) > Math.max(x.left, y.left) && Math.min(x.bottom, y.bottom) > Math.max(x.top, y.top)
    }
    return {
      detailGap: gap(copy, meta), shadow: getComputedStyle(card).boxShadow,
      headerOverlap: overlaps(tools, badge), headerHeight: header.getBoundingClientRect().height,
      clipped: [...meta.querySelectorAll('span')].some(span => span.scrollWidth > span.clientWidth + 1),
      cardOverflow: card.scrollWidth > card.clientWidth + 1,
    }
  })
  await testInfo.attach('aggregate-layout', { body: JSON.stringify(layout), contentType: 'application/json' })
  expect(layout.detailGap).toBeLessThanOrEqual(16)
  expect(layout.shadow).toBe('none')
  expect(layout.headerOverlap).toBe(false)
  expect(layout.clipped).toBe(false)
  expect(layout.cardOverflow).toBe(false)
  await page.screenshot({ path: testInfo.outputPath('aggregate-edit-layout.png'), fullPage: false })
  expect(state.errors).toEqual([])
})

test('pointer and touch resizing preview only the selected widget and cancel restores its settings', async ({ page }) => {
  const state = await mockPersonalization(page, { saved: aggregateSettings() })
  await edit(page)
  const before = await Promise.all([0, 1, 2, 3, 4].map(index => appearance(widget(page, index))))
  const handle = widget(page, 0).getByRole('button', { name: /위젯 크기 조절/ })
  await handle.evaluate(element => element.scrollIntoView({ block: 'center', behavior: 'instant' }))
  const rect = await handle.boundingBox(), x = rect.x + rect.width / 2, y = rect.y + rect.height / 2
  const rowHeight = await page.locator('.household-aggregate-grid').evaluate(element => parseFloat(getComputedStyle(element).getPropertyValue('--calendar-widget-cell-height')))
  if (page.viewportSize().width < 760) {
    const client = await page.context().newCDPSession(page)
    await client.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y, id: 1 }] })
    for (let step = 1; step <= 8; step++) await client.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: x + step * 5, y: y + step * rowHeight * 0.7 / 8, id: 1 }] })
    await client.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] })
    await client.detach()
  } else {
    await page.mouse.move(x, y)
    await page.mouse.down()
    await page.mouse.move(x + 40, y + rowHeight * 0.7, { steps: 12 })
    for (let index = 1; index < 5; index++) expect(await appearance(widget(page, index))).toEqual(before[index])
    await page.mouse.up()
  }
  const expectedRows = Math.round((before[0].height + 8) / rowHeight) + 1
  await expect.poll(async () => (await appearance(widget(page, 0))).height).toBe(rowHeight * expectedRows - 8)
  for (let index = 1; index < 5; index++) expect(await appearance(widget(page, index))).toEqual(before[index])
  await expect(handle).toHaveCSS('background-color', 'rgba(0, 0, 0, 0)')
  await expect(handle).toHaveCSS('box-shadow', 'none')
  page.once('dialog', prompt => prompt.accept())
  await page.locator('.calendar-draft-toolbar').getByRole('button', { name: '취소', exact: true }).click()
  for (let index = 0; index < 5; index++) expect(await appearance(widget(page, index))).toEqual(before[index])
  expect(state.writes).toEqual([])
  expect(state.errors).toEqual([])
})

test('resizing the aggregate palette does not resize the cards or their personalized typography', async ({ page }) => {
  const state = await mockPersonalization(page, { saved: aggregateSettings() })
  await edit(page)
  const before = await Promise.all([0, 1, 2, 3, 4].map(index => appearance(widget(page, index))))
  await page.locator('[data-calendar-panel-id="aggregate"]').getByRole('button', { name: '집계 영역 설정', exact: true }).click()
  const editor = page.getByRole('region', { name: /직접 편집/ })
  await editor.getByLabel('높이 (칸)', { exact: true }).fill('4')
  await editor.getByLabel('높이 (칸)', { exact: true }).press('Tab')
  for (let index = 0; index < 5; index++) expect(await appearance(widget(page, index))).toEqual(before[index])
  await editor.getByRole('button', { name: '편집 계속하기' }).click()
  await page.locator('.calendar-draft-toolbar').getByRole('button', { name: '변경사항 저장', exact: true }).click()
  await expect(board(page)).not.toHaveClass(/calendar-dashboard--editing/)
  for (let index = 0; index < 5; index++) expect(await appearance(widget(page, index))).toEqual(before[index])
  expect(state.saved.layout.find(item => item.id === 'aggregate').h).toBe(4)
  expect(state.saved.widgets.map(item => item.cardHeight)).toEqual([0, 0, 0, 0, 0, 0])
  expect(state.errors).toEqual([])
})

test('chart and goal details honor their switches and the chart grows without stretching other cards', async ({ page }, testInfo) => {
  const saved = calendarSettings()
  saved.layout[0].h = 4
  saved.layout[1].y = saved.layout[2].y = 4
  saved.widgets[3] = { ...saved.widgets[3], textSize: 'LARGE', textColor: 'SKY', cardHeight: 260 }
  const state = await mockPersonalization(page, { saved })
  await edit(page)
  const other = await appearance(widget(page, 0))
  const chart = widget(page, 3), graph = chart.locator('.household-aggregate-chart--preview svg')
  const oldHeight = (await graph.boundingBox()).height
  await chart.getByRole('button', { name: /위젯 설정/ }).click()
  await inspector(page).getByLabel('기간 표시', { exact: true }).uncheck()
  await expect(chart.locator('.household-aggregate-card__chart-caption > span')).toHaveCount(1)
  await expect(chart.locator('.household-aggregate-card__chart-caption > span')).toBeVisible()
  await inspector(page).getByLabel('수입 누적선', { exact: true }).uncheck()
  await expect(chart.locator('polyline.household-aggregate-chart__line--income')).toHaveCount(0)
  await inspector(page).getByRole('button', { name: '편집 계속하기' }).click()
  await chart.getByRole('button', { name: /위젯 크기 조절/ }).focus()
  await page.keyboard.press('ArrowDown')
  expect((await graph.boundingBox()).height).toBeGreaterThan(oldHeight + 5)
  expect(await appearance(widget(page, 0))).toEqual(other)
  await widget(page, 4).getByRole('button', { name: /위젯 설정/ }).click()
  await inspector(page).getByLabel('수입·지출 및 상세 정보 표시', { exact: true }).uncheck()
  await expect(widget(page, 4).locator('.household-aggregate-card__copy small')).toContainText('이번 달 기준')
  await inspector(page).getByLabel('기간 표시', { exact: true }).uncheck()
  await expect(widget(page, 4).locator('.household-aggregate-card__copy small')).toHaveCount(0)
  await expect(widget(page, 4).locator('.household-aggregate-card__meta')).toHaveCount(0)
  await inspector(page).getByRole('button', { name: '편집 계속하기' }).click()
  await page.locator('.household-aggregate-panel').evaluate(element => { element.scrollTop = 0; element.scrollIntoView({ block: 'start', behavior: 'instant' }) })
  await page.screenshot({ path: testInfo.outputPath('aggregate-graph-edit.png') })
  await page.locator('.calendar-draft-toolbar').getByRole('button', { name: '변경사항 저장', exact: true }).click()
  await expect(board(page)).not.toHaveClass(/calendar-dashboard--editing/)
  expect(state.saved.widgets[3]).toMatchObject({ textSize: 'LARGE', textColor: 'SKY', cardHeight: 260, cardRows: 3, showPeriod: false, showIncomeCumulative: false })
  expect(state.saved.widgets[4]).toMatchObject({ showPeriod: false, showDetails: false })
  expect(state.errors).toEqual([])
})

test('long titles and large amounts remain readable at 320, 390, 1024 and 1440 pixels', async ({ page }, testInfo) => {
  const saved = aggregateSettings()
  saved.widgets[0] = { ...saved.widgets[0], title: '올해 내가 고른 결제수단의 긴 이름을 표시하는 집계', textSize: 'LARGE', cardWidth: 200 }
  const state = await mockPersonalization(page, { saved, entryOverrides: { amount: 999999999 } })
  await edit(page)
  for (const width of [320, 390, 1024, 1440]) {
    await page.setViewportSize({ width, height: 900 })
    await expect(widget(page, 0).locator('.household-aggregate-card__copy strong')).toHaveText('₩999,999,999')
    const dimensions = await widget(page, 0).evaluate(card => {
      const title = card.querySelector('.household-aggregate-card__eyebrow'), meta = card.querySelector('.household-aggregate-card__meta')
      const header = card.closest('.household-aggregate-panel').querySelector('.household-aggregate-header')
      const badge = header.querySelector('.panel__badge').getBoundingClientRect()
      return {
        cardOverflow: card.scrollWidth > card.clientWidth + 1,
        titleOverflow: title.scrollHeight > title.clientHeight + 1,
        detailsOverflow: [...meta.querySelectorAll('span')].some(span => span.scrollWidth > span.clientWidth + 1),
        headerOverflow: header.scrollWidth > header.clientWidth + 1,
        buttons: [...header.querySelectorAll('button')].map(button => {
          const r = button.getBoundingClientRect(), parent = header.getBoundingClientRect()
          return { height: r.height, inside: r.left >= parent.left && r.right <= parent.right + 1, coversBadge: Math.min(r.right, badge.right) > Math.max(r.left, badge.left) && Math.min(r.bottom, badge.bottom) > Math.max(r.top, badge.top) }
        }),
      }
    })
    await testInfo.attach(`responsive-aggregate-${width}`, { body: JSON.stringify(dimensions), contentType: 'application/json' })
    expect(dimensions.cardOverflow).toBe(false)
    expect(dimensions.titleOverflow).toBe(false)
    expect(dimensions.detailsOverflow).toBe(false)
    expect(dimensions.headerOverflow).toBe(false)
    for (const button of dimensions.buttons) {
      expect(button.inside).toBe(true)
      expect(button.coversBadge).toBe(false)
      expect(button.height).toBeGreaterThanOrEqual(width < 760 ? 44 : 32)
    }
    await expect(widget(page, 0).locator('.household-aggregate-card__eyebrow')).toHaveCSS('font-size', '16px')
    await expect(widget(page, 1).locator('.household-aggregate-card__eyebrow')).toHaveCSS('font-size', '14px')
  }
  expect(state.writes).toEqual([])
  expect(state.errors).toEqual([])
})

test('direct editor buttons keep the same readable colors and flat shadows on hover in both themes', async ({ page }) => {
  const state = await mockPersonalization(page, { saved: aggregateSettings() })
  for (const theme of ['default', 'toss']) {
    if (theme === 'toss') await page.getByRole('button', { name: '다크 테마', exact: true }).click()
    await edit(page)
    await widget(page, 0).getByRole('button', { name: /위젯 설정/ }).click()
    const button = inspector(page).getByRole('button', { name: '편집 계속하기', exact: true })
    const background = await button.evaluate(element => getComputedStyle(element).backgroundColor)
    await expect(button).toHaveCSS('color', 'rgb(255, 255, 255)')
    await button.hover()
    await expect(button).toHaveCSS('background-color', background)
    await expect(button).toHaveCSS('-webkit-text-fill-color', 'rgb(255, 255, 255)')
    await expect(button).toHaveCSS('box-shadow', 'none')
    await button.click()
    await page.locator('.calendar-draft-toolbar').getByRole('button', { name: '취소', exact: true }).click()
  }
  expect(state.writes).toEqual([])
  expect(state.errors).toEqual([])
})
