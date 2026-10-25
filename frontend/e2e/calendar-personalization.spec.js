import { expect, test } from '@playwright/test'
import { ensureCalendarQueryPalette } from '../src/lib/calendarQueryLayout.js'
import { calendarSettings, mockPersonalization } from './fixtures/calendarPersonalization.js'

const dialog = page => page.getByRole('dialog', { name: '개인화 설정', exact: true })
const tab = (page, name) => dialog(page).getByRole('tab', { name, exact: false })
const open = async page => { await page.getByRole('button', { name: '개인화 설정', exact: true }).click(); await expect(dialog(page).getByRole('button', { name: '변경사항 저장', exact: true })).toBeDisabled(); await expect(dialog(page)).toHaveAttribute('aria-busy', 'false') }

test('view mode has no editing forms and temporary filters never persist defaults', async ({ page }) => {
  const state = await mockPersonalization(page)
  await expect(page.locator('.household-aggregate-card input, .household-aggregate-card select, .calendar-widget-edit')).toHaveCount(0)
  await expect(page.getByRole('button', { name: '입력 설정', exact: true })).toHaveCount(0)
  await page.getByLabel('표시 기준', { exact: true }).selectOption('expense')
  await open(page)
  await tab(page, '달력 표시').click()
  await expect(dialog(page).getByRole('combobox', { name: '기본 표시 기준', exact: true })).toHaveValue('net')
  await dialog(page).getByRole('button', { name: '취소', exact: true }).click()
  await expect(page.getByLabel('표시 기준', { exact: true })).toHaveValue('expense')
  expect(state.reads.filter(item => item.method === 'PUT')).toEqual([])
  expect(state.errors).toEqual([])
})

test('one selected widget uses a common editor; cancel restores values without storage writes', async ({ page }) => {
  const state = await mockPersonalization(page)
  const cache = await page.evaluate(() => ({ ...localStorage }))
  await open(page)
  await tab(page, '집계 위젯').click()
  const editor = dialog(page)
  await expect(editor.getByLabel('위젯 이름', { exact: true })).toHaveCount(1)
  await editor.getByLabel('위젯 이름', { exact: true }).fill('취소할 이름')
  await expect(editor.getByLabel('선택 위젯 실시간 미리보기')).toContainText('취소할 이름')
  await expect(editor.getByRole('button', { name: '변경사항 저장', exact: true })).toBeEnabled()
  page.once('dialog', prompt => prompt.accept())
  await editor.getByRole('button', { name: '취소', exact: true }).click()
  await expect(editor).toHaveCount(0)
  await expect(page.locator('.household-aggregate-card__eyebrow').first()).not.toContainText('취소할 이름')
  expect(state.writes).toEqual([])
  expect(await page.evaluate(() => ({ ...localStorage }))).toEqual(cache)
  expect(state.errors).toEqual([])
})

test('save commits widget, input visibility, defaults and layout in one request and reload restores them', async ({ page }) => {
  const state = await mockPersonalization(page)
  await open(page)
  await tab(page, '집계 위젯').click()
  await dialog(page).getByLabel('위젯 이름', { exact: true }).fill('나의 합계')
  await dialog(page).getByRole('combobox', { name: '카드 너비 (칸)', exact: true }).selectOption('3')
  await dialog(page).getByRole('combobox', { name: '카드 높이 (칸)', exact: true }).selectOption('2')
  await tab(page, '거래 입력').click()
  await dialog(page).getByRole('switch', { name: '거래 이미지 자동입력 표시', exact: false }).uncheck()
  await tab(page, '달력 표시').click()
  await dialog(page).getByRole('combobox', { name: '기본 표시 기준', exact: true }).selectOption('income')
  await tab(page, '대시보드 구성').click()
  await dialog(page).getByRole('combobox', { name: '영역 너비', exact: true }).selectOption('5')
  await dialog(page).getByRole('button', { name: '변경사항 저장', exact: true }).click()
  await expect(dialog(page)).toHaveCount(0)
  expect(state.writes).toHaveLength(1)
  expect(state.writes[0].widgets[0]).toMatchObject({ title: '나의 합계', cardColumns: 3, cardRows: 2 })
  expect(state.writes[0].preferences).toMatchObject({ receiptOcrPanelEnabled: false, highlightMode: 'income' })
  expect(state.writes[0].layout.find(item => item.id === 'calendar').w).toBe(5)
  await page.reload()
  await page.getByRole('button', { name: '달력 가계부', exact: true }).click()
  await expect(page.locator('.household-aggregate-card__eyebrow').first()).toHaveText('나의 합계')
  await expect(page.getByRole('button', { name: '분석 모달 열기', exact: true })).toHaveCount(0)
  await expect(page.getByLabel('표시 기준', { exact: true })).toHaveValue('income')
  expect(state.errors).toEqual([])
})

test('failed save keeps the draft and retry uses the same revision', async ({ page }) => {
  const state = await mockPersonalization(page, { failSave: true })
  await open(page)
  await tab(page, '집계 위젯').click()
  await dialog(page).getByLabel('위젯 이름', { exact: true }).fill('보존된 초안')
  await dialog(page).getByRole('button', { name: '변경사항 저장', exact: true }).click()
  await expect(dialog(page).getByRole('alert')).toContainText('검증용 저장 실패')
  await expect(dialog(page).getByLabel('위젯 이름', { exact: true })).toHaveValue('보존된 초안')
  expect(state.saved.widgets[0].title).toBe('')
  state.failSave = false
  await dialog(page).getByRole('button', { name: '변경사항 저장', exact: true }).click()
  await expect(dialog(page)).toHaveCount(0)
  expect(state.writes).toHaveLength(2)
  expect(state.writes[0].revision).toBe(state.writes[1].revision)
  expect(state.errors).toEqual([])
})

test('conflict is explicit and latest settings do not silently replace the draft', async ({ page }) => {
  const state = await mockPersonalization(page, { conflict: true })
  await open(page)
  await tab(page, '집계 위젯').click()
  await dialog(page).getByLabel('위젯 이름', { exact: true }).fill('충돌 초안')
  await dialog(page).getByRole('button', { name: '변경사항 저장', exact: true }).click()
  await expect(dialog(page).getByRole('alert')).toContainText('다른 화면')
  await expect(dialog(page).getByLabel('위젯 이름', { exact: true })).toHaveValue('충돌 초안')
  state.saved.widgets[0].title = '다른 창의 설정'
  state.saved.revision = 'new-revision'
  page.once('dialog', prompt => prompt.accept())
  await dialog(page).getByRole('button', { name: '최신 설정으로 다시 열기' }).click()
  await expect(dialog(page).getByLabel('위젯 이름', { exact: true })).toHaveValue('다른 창의 설정')
  await expect(dialog(page).getByRole('button', { name: '변경사항 저장', exact: true })).toBeDisabled()
  expect(state.errors).toEqual([])
})

test('reset is scoped and widget clone/delete preserve transaction data', async ({ page }) => {
  const state = await mockPersonalization(page)
  await open(page)
  await tab(page, '집계 위젯').click()
  await dialog(page).getByRole('button', { name: '위젯 복제', exact: true }).click()
  await expect(dialog(page).getByLabel('위젯 이름', { exact: true })).toHaveValue(/복사/)
  page.once('dialog', prompt => prompt.accept())
  await dialog(page).getByRole('button', { name: '위젯 삭제', exact: true }).click()
  await expect(dialog(page).getByRole('combobox', { name: '위젯 유형', exact: true })).toHaveValue('NONE')
  await tab(page, '기본값 관리').click()
  page.once('dialog', prompt => prompt.accept())
  await dialog(page).getByRole('button', { name: '배치만 초기화', exact: true }).click()
  expect(state.reads.some(item => item.method === 'DELETE')).toBe(false)
  expect(state.saved.widgets).toHaveLength(6)
})

test('custom saved rows are not rearranged by reading, resize or cancel', async ({ page }) => {
  const saved = calendarSettings()
  saved.layout = saved.layout.map(panel => ({ ...panel, y: panel.y + 4 }))
  const state = await mockPersonalization(page, { saved })
  await open(page)
  await expect(dialog(page).getByRole('button', { name: '변경사항 저장', exact: true })).toBeDisabled()
  await tab(page, '대시보드 구성').click()
  await dialog(page).getByText('고급 배치 좌표', { exact: true }).click()
  await expect(dialog(page).getByLabel('세로 위치 (0부터)', { exact: true })).toHaveValue(String(ensureCalendarQueryPalette(saved.layout).find(panel => panel.id === 'calendar').y))
  await dialog(page).getByRole('button', { name: '취소', exact: true }).click()
  expect(state.writes).toEqual([])
  expect(state.saved.layout).toEqual(saved.layout)
})

test('server layout wins over a stale browser cache even when its computer clock is ahead', async ({ page }) => {
  const saved = calendarSettings()
  const local = saved.layout.map(panel => ({ ...panel, y: panel.y + 7 }))
  await page.addInitScript(layout => localStorage.setItem('calen-household-calendar-panel-layout:v3:2', JSON.stringify({ savedAt: Date.parse('2026-10-22T12:00:00Z'), layout })), local)
  const state = await mockPersonalization(page, { saved, layoutUpdatedAt: '2026-10-21T12:00:00Z' })
  await page.getByRole('button', { name: '개인화 설정', exact: true }).click()
  await expect(dialog(page)).toHaveAttribute('aria-busy', 'false')
  await expect(dialog(page)).not.toContainText('이 브라우저에 보관된 배치가 서버 값과 다릅니다')
  await dialog(page).getByText('고급 배치 좌표', { exact: true }).click()
  await expect(dialog(page).getByLabel('세로 위치 (0부터)', { exact: true })).toHaveValue(String(ensureCalendarQueryPalette(saved.layout).find(panel => panel.id === 'calendar').y))
  await expect(dialog(page).getByRole('button', { name: '변경사항 저장', exact: true })).toBeDisabled()
  await dialog(page).getByRole('button', { name: '취소', exact: true }).click()
  await expect(dialog(page)).toHaveCount(0)
  expect(state.writes).toEqual([])
  expect(state.saved.layout).toEqual(saved.layout)
})

test('common preview follows text styles, visibility and the existing graph options', async ({ page }) => {
  const state = await mockPersonalization(page)
  await open(page)
  await tab(page, '집계 위젯').click()
  await dialog(page).getByRole('combobox', { name: '글자 크기', exact: true }).selectOption('LARGE')
  await dialog(page).getByRole('combobox', { name: '글자 색상', exact: true }).selectOption('MINT')
  const preview = dialog(page).getByLabel('선택 위젯 실시간 미리보기')
  await expect(preview.locator('b')).toHaveCSS('font-size', '30px')
  await expect(preview.locator('b')).toHaveCSS('color', 'rgb(4, 120, 87)')
  await dialog(page).getByRole('switch', { name: '대시보드에 표시', exact: true }).uncheck()
  await expect(page.locator('.household-aggregate-card[data-aggregate-index="0"]')).toHaveCount(0)
  await dialog(page).getByRole('combobox', { name: '위젯 유형', exact: true }).selectOption('MONTHLY_CUMULATIVE_CHART')
  await expect(preview.getByRole('img', { name: '선택한 누적 그래프 미리보기' })).toBeVisible()
  await dialog(page).getByRole('combobox', { name: '카드 너비 (칸)', exact: true }).selectOption('3')
  await dialog(page).getByRole('checkbox', { name: '지난 기간 비교', exact: true }).check()
  await expect(preview.locator('.personalization-preview-chart__previous')).toHaveCount(2)
  page.once('dialog', prompt => prompt.accept())
  await dialog(page).getByRole('button', { name: '취소', exact: true }).click()
  await expect(page.locator('.household-aggregate-card[data-aggregate-index="0"]')).toHaveCount(1)
  expect(state.writes).toEqual([])
})

test('desktop drag or mobile direct resizing is a cancellable draft', async ({ page }) => {
  const state = await mockPersonalization(page)
  await page.getByRole('button', { name: '대시보드 편집', exact: true }).click()
  if (page.viewportSize().width < 760) {
    await expect(dialog(page)).toHaveCount(0)
    await page.locator('[data-palette-resize="calendar"]').focus()
    await page.keyboard.press('ArrowDown')
    await expect(page.locator('.calendar-draft-toolbar').getByRole('button', { name: '변경사항 저장', exact: true })).toBeEnabled()
    page.once('dialog', prompt => prompt.accept())
    await page.locator('.calendar-draft-toolbar').getByRole('button', { name: '취소', exact: true }).click()
  } else {
    const grid = page.locator('.calendar-dashboard-grid.grid-stack')
    await expect(grid).toBeVisible()
    await expect.poll(() => grid.evaluate(element => element.gridstack?.engine?.nodes.filter(node => node.id !== 'calendar-query').length)).toBe(3)
    const handle = grid.locator('[data-calendar-panel-id="quick-entry"] .panel__header h2')
    await handle.evaluate(element => element.scrollIntoView({ block: 'center', behavior: 'instant' }))
    const box = await handle.boundingBox()
    const initialRow = await grid.evaluate(element => element.gridstack.engine.nodes.find(node => node.id === 'quick-entry').y)
    await page.mouse.move(box.x + 25, box.y + box.height / 2)
    await page.mouse.down()
    await page.mouse.move(box.x + 25, box.y + 240, { steps: 20 })
    // GridStack applies movement after its short drag-pause debounce; release only after snapping.
    await expect.poll(() => grid.evaluate(element => element.gridstack.engine.nodes.find(node => node.id === 'quick-entry')?.y)).toBe(initialRow + 2)
    await page.mouse.up()
    const toolbar = page.locator('.calendar-draft-toolbar')
    await expect(toolbar.getByRole('button', { name: '변경사항 저장', exact: true })).toBeEnabled()
    expect(state.writes).toEqual([])
    page.once('dialog', prompt => prompt.accept())
    await toolbar.getByRole('button', { name: '취소', exact: true }).click()
    await expect(grid).toHaveCount(0)
  }
  expect(state.saved.layout).toEqual(calendarSettings().layout)
  expect(state.writes).toEqual([])
  expect(state.errors).toEqual([])
})

test('desktop tablet and mobile keep cards and all editor sections readable in both themes', async ({ page }, testInfo) => {
  const state = await mockPersonalization(page)
  for (const width of [1440, 1024, 390]) {
    await page.setViewportSize({ width, height: 900 })
    for (const theme of ['default', 'toss']) {
      const current = await page.locator('html').getAttribute('data-theme')
      if ((current === 'toss') !== (theme === 'toss')) await page.getByRole('button', { name: theme === 'toss' ? '다크 테마' : '기본 테마', exact: true }).click()
      const overflow = await page.locator('.household-aggregate-card').evaluateAll(cards => cards.filter(card => card.scrollWidth > card.clientWidth + 2).length)
      expect(overflow).toBe(0)
      // A whole cell can be narrower than the former 220px minimum. Verify the
      // explicit cell contract, while retaining the content overflow check above.
      const sizes = await page.locator('.household-aggregate-card').evaluateAll(cards => cards.map(card => {
        const r = card.getBoundingClientRect(), rowPitch = parseFloat(getComputedStyle(card.closest('.calendar-dashboard')).getPropertyValue('--calendar-layout-cell-height'))
        return { width: r.width, height: r.height, columns: Number(card.dataset.widgetGridColumns), expectedWidth: (card.parentElement.clientWidth + 8) / 9 * Number(card.dataset.widgetGridColumns) - 8, expectedHeight: rowPitch * Number(card.dataset.widgetGridRows) - 8 }
      }))
      for (const size of sizes) {
        expect(size.columns).toBeGreaterThanOrEqual(1)
        expect(size.columns).toBeLessThanOrEqual(9)
        expect(Math.abs(size.width - size.expectedWidth)).toBeLessThan(1)
        expect(Math.abs(size.height - size.expectedHeight)).toBeLessThan(1)
        expect(size.height).toBeLessThan(500)
      }
      if (width === 390) {
        const panels = await page.locator('[data-calendar-panel-id]').evaluateAll(items => items.map(item => ({ id: item.dataset.calendarPanelId, y: item.getBoundingClientRect().y })))
        expect(panels.find(item => item.id === 'aggregate').y).toBeLessThan(panels.find(item => item.id === 'calendar').y)
      }
      await open(page)
      for (const name of ['대시보드 구성', '달력 표시', '집계 위젯', '거래 입력', '기본값 관리']) {
        await tab(page, name).click()
        expect(await dialog(page).evaluate(element => element.scrollWidth > element.clientWidth + 1)).toBe(false)
        const bounds = await dialog(page).boundingBox()
        expect(bounds.x).toBeGreaterThanOrEqual(0)
        expect(bounds.x + bounds.width).toBeLessThanOrEqual(width)
      }
      await tab(page, '집계 위젯').click()
      await dialog(page).screenshot({ path: testInfo.outputPath(`${theme}-${width}-editor.png`) })
      await dialog(page).getByRole('button', { name: '취소', exact: true }).click()
      await page.locator('.calendar-dashboard').screenshot({ path: testInfo.outputPath(`${theme}-${width}-dashboard.png`) })
    }
  }
  expect(state.errors).toEqual([])
})

test('inline sheet stays below full-sized calendar and input after ending layout editing', async ({ page }) => {
  const saved = calendarSettings()
  saved.preferences.transactionSheetViewMode = 'inline'
  const state = await mockPersonalization(page, { saved })
  const bounds = () => page.locator('[data-calendar-panel-id]').evaluateAll(items => items.map(item => {
    const rect = item.getBoundingClientRect()
    return { id: item.dataset.calendarPanelId, top: rect.top, bottom: rect.bottom, width: rect.width, height: rect.height, scrollHeight: item.scrollHeight }
  }))
  const before = await bounds()
  expect(before.find(item => item.id === 'calendar').height).toBeGreaterThan(400)
  await page.getByRole('button', { name: '대시보드 편집', exact: true }).click()
  if (page.viewportSize().width < 760) {
    await expect(dialog(page)).toHaveCount(0)
    await page.locator('.calendar-draft-toolbar').getByRole('button', { name: '취소', exact: true }).click()
  } else {
    await expect.poll(() => page.locator('.grid-stack').evaluate(element => element.gridstack?.engine?.nodes.filter(node => node.id !== 'calendar-query').length)).toBe(4)
    await page.locator('.calendar-draft-toolbar').getByRole('button', { name: '취소', exact: true }).click()
  }
  const after = await bounds()
  for (const panel of after) {
    expect(Math.abs(panel.width - before.find(item => item.id === panel.id).width)).toBeLessThan(2)
    expect(panel.scrollHeight).toBeLessThanOrEqual(panel.height + 2)
  }
  expect(after.find(item => item.id === 'sheet').top).toBeGreaterThanOrEqual(Math.max(...after.filter(item => item.id !== 'sheet').map(item => item.bottom)))
  expect(state.writes).toEqual([])
  expect(state.errors).toEqual([])
})

test('keyboard focus stays in the editor and Escape confirms before discarding a draft', async ({ page }) => {
  const state = await mockPersonalization(page)
  await open(page)
  await tab(page, '집계 위젯').click()
  await dialog(page).getByLabel('위젯 이름', { exact: true }).fill('키보드 초안')
  await dialog(page).getByRole('button', { name: '변경사항 저장', exact: true }).focus()
  await page.keyboard.press('Tab')
  await expect(dialog(page).getByRole('button', { name: '개인화 설정 닫기' })).toBeFocused()
  page.once('dialog', prompt => prompt.dismiss())
  await page.keyboard.press('Escape')
  await expect(dialog(page)).toBeVisible()
  await expect(dialog(page).getByLabel('위젯 이름', { exact: true })).toHaveValue('키보드 초안')
  page.once('dialog', prompt => prompt.accept())
  await page.keyboard.press('Escape')
  await expect(dialog(page)).toHaveCount(0)
  expect(state.writes).toEqual([])
})

test('leaving dashboard editing asks about unsaved settings and never autosaves them', async ({ page }) => {
  const state = await mockPersonalization(page)
  await open(page)
  await tab(page, '집계 위젯').click()
  await dialog(page).getByLabel('위젯 이름', { exact: true }).fill('이탈 전 초안')
  await tab(page, '대시보드 구성').click()
  if (page.viewportSize().width < 760) {
    // The route guard also protects programmatic navigation on mobile, where the modal covers the tabs.
    page.once('dialog', prompt => prompt.dismiss())
    expect(await page.evaluate(() => window.dispatchEvent(new CustomEvent('calen:before-preference-navigation', { cancelable: true })))).toBe(false)
    page.once('dialog', prompt => prompt.accept())
    expect(await page.evaluate(() => window.dispatchEvent(new CustomEvent('calen:before-preference-navigation', { cancelable: true })))).toBe(true)
  } else {
    await dialog(page).getByRole('button', { name: '화면에서 배치 직접 편집' }).click()
    await expect(dialog(page)).toHaveCount(0)
    page.once('dialog', prompt => prompt.dismiss())
    await page.getByRole('button', { name: '대시보드', exact: true }).click()
    await expect(page.locator('.calendar-draft-toolbar')).toBeVisible()
    page.once('dialog', prompt => prompt.accept())
    await page.getByRole('button', { name: '대시보드', exact: true }).click()
    await expect(page.locator('.calendar-draft-toolbar')).toHaveCount(0)
  }
  expect(state.writes).toEqual([])
  expect(state.errors).toEqual([])
})
