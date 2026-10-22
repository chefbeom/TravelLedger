import { expect, test } from '@playwright/test'
import { mockPersonalization } from './fixtures/calendarPersonalization.js'

const quickEntry = page => page.locator('.household-entry-panel')

async function expectUnifiedDateTimeLayout(page) {
  const panel = quickEntry(page)
  for (const section of ['.entry-editor__amount', '.entry-editor__fields']) {
    await expect(panel.locator(section)).toHaveCSS('border-top-width', '0px')
    await expect(panel.locator(section)).toHaveCSS('padding-left', '0px')
    await expect(panel.locator(section)).toHaveCSS('background-color', 'rgba(0, 0, 0, 0)')
  }
  await expect.poll(() => panel.evaluate(element => {
    const date = element.querySelector('.entry-date-control input').getBoundingClientRect()
    const time = element.querySelector('.household-time-selectors input').getBoundingClientRect()
    return Math.max(Math.abs(date.top - time.top), Math.abs(date.height - time.height))
  }), { message: 'Date and time inputs must share one baseline and height' }).toBeLessThan(1)
  const geometry = await panel.evaluate(element => {
    const bounds = element.getBoundingClientRect()
    const rect = selector => {
      const { left, right, top, bottom, width } = element.querySelector(selector).getBoundingClientRect()
      return { left, right, top, bottom, width }
    }
    return {
      left: bounds.left, right: bounds.right, overflow: element.scrollWidth - element.clientWidth,
      date: rect('.entry-date-control input'), time: rect('.household-time-selectors input'),
      hint: rect('.entry-date-time-hint'),
      steps: [...element.querySelectorAll('.entry-date-control__step')].map(button => ({ height: button.getBoundingClientRect().height })),
    }
  })
  expect(geometry.overflow).toBeLessThanOrEqual(1)
  expect(geometry.date.right).toBeLessThanOrEqual(geometry.time.left)
  expect(geometry.date.width).toBeGreaterThanOrEqual(120)
  expect(geometry.time.width).toBeGreaterThanOrEqual(80)
  expect(geometry.date.left).toBeGreaterThanOrEqual(geometry.left)
  expect(geometry.time.right).toBeLessThanOrEqual(geometry.right)
  expect(geometry.hint.top).toBeGreaterThanOrEqual(geometry.date.bottom)
  expect(geometry.hint.top).toBeGreaterThanOrEqual(geometry.time.bottom)
  for (const step of geometry.steps) expect(step.height).toBeGreaterThanOrEqual(44)
}

test('quick entry stays one unboxed form with horizontal date and time across screen sizes and themes', async ({ page }, testInfo) => {
  test.setTimeout(90_000)
  const state = await mockPersonalization(page)
  for (const width of [320, 360, 390, 412, 1024, 1440, 1920]) {
    await page.setViewportSize({ width, height: 900 })
    for (const theme of ['default', 'toss']) {
      if (((await page.locator('html').getAttribute('data-theme')) === 'toss') !== (theme === 'toss')) {
        await page.getByRole('button', { name: theme === 'toss' ? '다크 테마' : '기본 테마', exact: true }).click()
      }
      await expectUnifiedDateTimeLayout(page)
      if (width === 390 || width === 1440) {
        await quickEntry(page).screenshot({ path: testInfo.outputPath(`${theme}-${width}-quick-entry.png`) })
      }
    }
  }
  await quickEntry(page).getByRole('button', { name: '외화', exact: true }).click()
  await expectUnifiedDateTimeLayout(page)
  await quickEntry(page).getByRole('button', { name: '수입', exact: true }).click()
  await expectUnifiedDateTimeLayout(page)
  expect(state.writes).toEqual([])
  expect(state.errors).toEqual([])
})

test('horizontal date and time preserve date navigation, shortcuts and timed or untimed submissions', async ({ page }) => {
  const entries = []
  page.on('request', request => {
    if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/entries') entries.push(request.postDataJSON())
  })
  const state = await mockPersonalization(page)
  const panel = quickEntry(page)
  const date = panel.getByLabel('거래 날짜', { exact: true })
  const time = panel.getByLabel('시간', { exact: true })
  const toggle = panel.getByRole('checkbox', { name: '시간 입력 사용', exact: true })
  await date.fill('2026-10-20')
  await panel.getByRole('button', { name: '전날', exact: true }).click()
  await expect(date).toHaveValue('2026-10-19')
  await panel.getByRole('button', { name: '다음날', exact: true }).click()
  await expect(date).toHaveValue('2026-10-20')
  await toggle.check()
  await time.fill('13:45')
  await panel.getByRole('textbox', { name: '거래 제목', exact: true }).fill('수평 입력 검증')
  await panel.getByRole('button', { name: '1만', exact: true }).click()
  await panel.getByRole('button', { name: '+5천', exact: true }).click()
  await expect(panel.locator('.amount-input input')).toHaveValue('15,000')
  await panel.getByRole('button', { name: '거래 등록', exact: true }).click()
  await expect.poll(() => entries.length).toBe(1)
  expect(entries[0]).toMatchObject({ entryDate: '2026-10-20', entryTime: '13:45', title: '수평 입력 검증', amount: 15000 })
  await expect(page.getByText('가계부 내역을 등록했습니다.', { exact: true })).toBeVisible()
  await toggle.uncheck()
  await expect(time).toBeDisabled()
  await panel.locator('.amount-input input').fill('10000')
  await panel.getByRole('textbox', { name: '거래 제목', exact: true }).fill('시간 미사용 검증')
  await panel.getByRole('button', { name: '거래 등록', exact: true }).click()
  await expect.poll(() => entries.length).toBe(2)
  expect(entries[1]).toMatchObject({ entryTime: '00:00', title: '시간 미사용 검증', amount: 10000 })
  expect(state.errors).toEqual([])
})
