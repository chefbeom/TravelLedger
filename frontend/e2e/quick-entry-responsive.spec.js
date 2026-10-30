import { expect, test } from '@playwright/test'
import { calendarSettings, mockPersonalization } from './fixtures/calendarPersonalization.js'

const panel = page => page.locator('.household-entry-panel')
const palette = page => page.locator('[data-calendar-panel-id="quick-entry"]')
const launcher = page => panel(page).getByRole('button', { name: '영수증·거래내역 인식', exact: true })

function settings(height = 7) {
  const saved = calendarSettings()
  saved.layout = saved.layout.map(item => ['calendar', 'quick-entry'].includes(item.id) ? { ...item, h: height } : item)
  return saved
}

async function geometry(page) {
  return panel(page).evaluate(element => {
    const rect = target => {
      const box = target.getBoundingClientRect()
      return { left: box.left, right: box.right, top: box.top, bottom: box.bottom, width: box.width, height: box.height }
    }
    const find = selector => element.querySelector(selector)
    const editor = element.closest('[data-calendar-panel-id="quick-entry"]')?.querySelector('.calendar-palette-tools')
    return {
      panel: rect(element),
      content: rect(find('.household-entry-panel__content')),
      dateBadge: rect(find('.household-entry-panel__date-badge')),
      launcher: rect(find('.household-entry-panel__receipt-button')),
      editTool: editor ? rect(editor) : null,
      date: rect(find('.entry-date-control input')),
      time: rect(find('.household-time-selectors input')),
      memo: rect(find('input[placeholder="상세 메모를 남기고 싶다면 입력해 주세요."]')),
      submit: rect(find('.entry-editor__actions .button')),
      scroll: element.scrollHeight - element.clientHeight,
      horizontal: element.scrollWidth - element.clientWidth,
    }
  })
}

async function expectVisibleContent(page) {
  await expect.poll(async () => (await geometry(page)).scroll).toBeLessThanOrEqual(1)
  const layout = await geometry(page)
  expect(layout.horizontal).toBeLessThanOrEqual(1)
  expect(layout.content.bottom).toBeLessThanOrEqual(layout.panel.bottom + 1)
  expect(layout.submit.bottom).toBeLessThanOrEqual(layout.panel.bottom + 1)
  expect(layout.submit.top - layout.memo.bottom).toBeGreaterThanOrEqual(8)
  expect(layout.date.width).toBeLessThanOrEqual(224)
  expect(layout.time.width).toBeLessThanOrEqual(112)
  expect(layout.date.width).toBeGreaterThanOrEqual(120)
  expect(layout.time.width).toBeGreaterThanOrEqual(80)
  expect(layout.date.right).toBeLessThanOrEqual(layout.time.left + 1)
  expect(Math.abs(layout.date.top - layout.time.top)).toBeLessThan(1)
  for (const control of [layout.dateBadge, layout.launcher]) {
    expect(control.left).toBeGreaterThanOrEqual(layout.panel.left)
    expect(control.right).toBeLessThanOrEqual(layout.panel.right + 1)
  }
  expect(Math.max(layout.dateBadge.top, layout.launcher.top)).toBeLessThan(Math.min(layout.dateBadge.bottom, layout.launcher.bottom))
  if (layout.editTool) {
    const overlap = Math.max(0, Math.min(layout.launcher.right, layout.editTool.right) - Math.max(layout.launcher.left, layout.editTool.left))
      * Math.max(0, Math.min(layout.launcher.bottom, layout.editTool.bottom) - Math.max(layout.launcher.top, layout.editTool.top))
    expect(overlap, 'Edit tools must not cover recognition').toBeLessThanOrEqual(1)
  }
}

test('quick entry stays fully visible and fits its responsive palette in view and edit modes', async ({ page }, info) => {
  test.setTimeout(120_000)
  const state = await mockPersonalization(page, { saved: settings() })
  await expect(panel(page).getByRole('heading', { name: '빠른 거래 입력', exact: true })).toHaveCount(0)
  for (const width of [2560, 1920, 1600, 1366, 1024, 390, 320]) {
    await page.setViewportSize({ width, height: 1100 })
    for (const theme of ['default', 'toss']) {
      if (((await page.locator('html').getAttribute('data-theme')) === 'toss') !== (theme === 'toss')) {
        await page.getByRole('button', { name: theme === 'toss' ? '다크 테마' : '기본 테마', exact: true }).click()
      }
      await expectVisibleContent(page)
      if (width === 1600 && info.project.name === 'chromium-desktop') {
        await expect.poll(() => palette(page).evaluate(element => Number(element.style.getPropertyValue('--calendar-panel-rows')))).toBe(7)
      }
      if ([1600, 1024, 390].includes(width)) await panel(page).screenshot({ path: info.outputPath(`${theme}-${width}.png`) })
    }
    const beforeEdit = await geometry(page)
    await page.getByRole('button', { name: '대시보드 편집', exact: true }).click()
    await expectVisibleContent(page)
    const duringEdit = await geometry(page)
    for (const part of ['dateBadge', 'launcher', 'date', 'time', 'memo', 'submit']) {
      expect(Math.abs((beforeEdit[part].left - beforeEdit.panel.left) - (duringEdit[part].left - duringEdit.panel.left))).toBeLessThan(1)
      expect(Math.abs((beforeEdit[part].top - beforeEdit.panel.top) - (duringEdit[part].top - duringEdit.panel.top))).toBeLessThan(1)
      expect(Math.abs(beforeEdit[part].width - duringEdit[part].width)).toBeLessThan(1)
    }
    await expect(palette(page).getByRole('button', { name: '입력 설정', exact: true })).toBeVisible()
    await page.locator('.calendar-draft-toolbar').getByRole('button', { name: '취소', exact: true }).click()
  }
  expect(state.writes).toEqual([])
  expect(state.errors).toEqual([])
})

test('recognition button and type/currency selections retain their behavior in both themes', async ({ page }) => {
  const state = await mockPersonalization(page, { saved: settings() })
  for (const theme of ['default', 'toss']) {
    if (theme === 'toss') await page.getByRole('button', { name: '다크 테마', exact: true }).click()
    for (const [kind, currency] of [['지출', '원화'], ['수입', '원화'], ['수입', '외화'], ['지출', '외화']]) {
      const typeGroup = panel(page).getByRole('group', { name: '거래 구분', exact: true })
      const currencyGroup = panel(page).getByRole('group', { name: '통화 입력 방식', exact: true })
      await typeGroup.getByRole('button', { name: kind, exact: true }).click()
      await currencyGroup.getByRole('button', { name: currency, exact: true }).click()
      for (const [group, selected, other] of [[typeGroup, kind, kind === '수입' ? '지출' : '수입'], [currencyGroup, currency, currency === '원화' ? '외화' : '원화']]) {
        const active = group.getByRole('button', { name: selected, exact: true })
        const inactive = group.getByRole('button', { name: other, exact: true })
        await expect(active).toHaveAttribute('aria-pressed', 'true')
        await expect(inactive).toHaveAttribute('aria-pressed', 'false')
        expect(await active.evaluate(el => getComputedStyle(el).backgroundColor)).not.toBe(await inactive.evaluate(el => getComputedStyle(el).backgroundColor))
      }
      await expectVisibleContent(page)
    }
    await launcher(page).click()
    const dialog = page.getByRole('dialog', { name: '이미지 분석 및 검수', exact: true })
    await expect(dialog).toBeVisible()
    await dialog.getByRole('button', { name: '닫기', exact: true }).click()
  }
  expect(state.errors).toEqual([])
})

test('short saved palettes grow only for display and do not silently rewrite saved height', async ({ page }, info) => {
  const saved = settings(3)
  const state = await mockPersonalization(page, { saved })
  await expectVisibleContent(page)
  expect(await palette(page).evaluate(element => Number(element.style.getPropertyValue('--calendar-panel-rows')))).toBeGreaterThan(3)
  await page.getByRole('button', { name: '대시보드 편집', exact: true }).click()
  await expectVisibleContent(page)
  const move = page.locator('[data-palette-move="quick-entry"]')
  await move.focus()
  await page.keyboard.press(info.project.name === 'chromium-mobile' ? 'ArrowDown' : 'ArrowLeft')
  await expect(page.locator('.calendar-draft-toolbar').getByRole('button', { name: '변경사항 저장', exact: true })).toBeEnabled()
  await page.locator('.calendar-draft-toolbar').getByRole('button', { name: '변경사항 저장', exact: true }).click()
  expect(state.saved.layout.find(item => item.id === 'quick-entry').h).toBe(3)
  await expectVisibleContent(page)
  expect(state.errors).toEqual([])
})

test('additional actions remain visible and projected height shrinks again on a wider screen', async ({ page }, info) => {
  const state = await mockPersonalization(page, { saved: settings(3) })
  await page.setViewportSize({ width: 390, height: 844 })
  await panel(page).getByRole('textbox', { name: '거래 제목', exact: true }).fill('공간 조절 검증')
  await panel(page).getByRole('button', { name: '1만', exact: true }).click()
  await panel(page).getByRole('button', { name: '거래 등록', exact: true }).click()
  await expect(panel(page).getByRole('button', { name: '등록 취소', exact: true })).toBeVisible()
  await expectVisibleContent(page)
  const narrowHeight = (await geometry(page)).panel.height
  await page.setViewportSize({ width: 1600, height: 1100 })
  if (info.project.name === 'chromium-desktop') {
    await expect.poll(async () => (await geometry(page)).panel.height).toBeLessThan(narrowHeight)
  }
  await expectVisibleContent(page)
  expect(state.writes).toEqual([])
  expect(state.errors).toEqual([])
})

test('editing an existing entry keeps its status and controls visible at a narrow width', async ({ page }) => {
  const state = await mockPersonalization(page, { saved: settings(3) })
  await page.setViewportSize({ width: 320, height: 700 })
  await panel(page).getByLabel('거래 날짜', { exact: true }).fill('2026-10-22')
  await page.getByRole('button', { name: '거래 시트 열기', exact: true }).click()
  const sheet = page.getByRole('dialog', { name: '거래 시트', exact: true })
  await sheet.getByRole('button', { name: '수정', exact: true }).click()
  await expect(panel(page).getByText('거래 수정 중', { exact: true })).toBeVisible()
  await expect(panel(page).getByRole('textbox', { name: '거래 제목', exact: true })).toHaveValue('검증용 식사')
  await expectVisibleContent(page)
  expect(state.errors).toEqual([])
})
