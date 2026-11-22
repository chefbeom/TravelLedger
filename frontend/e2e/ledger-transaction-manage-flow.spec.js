import { expect, test } from '@playwright/test'
import { mockPersonalization } from './fixtures/calendarPersonalization.js'

const day = '2026-10-22'
const NEXT_DAY = '2026-10-23'
const baseOrigin = {
  id: 7, entryDate: day, entryTime: '12:30', entryType: 'EXPENSE', amount: 100000, title: '검증용 결제', memo: '테스트 메모',
  categoryGroupId: 101, categoryGroupName: '식비', categoryDetailId: null, paymentMethodId: 103, paymentMethodName: '카드',
}

function cancelRecord(extra = {}) {
  return {
    id: 9, entryDate: day, entryTime: '12:35', entryType: 'EXPENSE', title: '결제 취소: 검증용 결제', amount: 100000,
    categoryGroupName: '식비', paymentMethodName: '카드', financiallyExcluded: true,
    adjustmentType: 'PAYMENT_CANCELLATION', adjustmentOriginId: 7, adjustmentPrincipal: 100000, ...extra,
  }
}

function refundRecord(id, principal, extra = {}) {
  return {
    id, entryDate: day, entryTime: null, entryType: 'INCOME', title: '환불: 검증용 결제', amount: principal,
    categoryGroupName: '기타 수입', paymentMethodName: '현금', adjustmentType: 'REFUND', adjustmentOriginId: 7,
    adjustmentPrincipal: principal, adjustmentDeduction: 0, adjustmentExtra: 0, ...extra,
  }
}

const SEEDS = {
  NORMAL: () => ({ origin: {}, records: [] }),
  CANCELLED: () => ({
    origin: { hasAdjustmentHistory: true, financiallyExcluded: true, cancelledAt: `${day}T12:35:00` },
    records: [cancelRecord()],
  }),
  PARTIALLY_REFUNDED: () => ({
    origin: { hasAdjustmentHistory: true, refundedPrincipal: 30000 },
    records: [refundRecord(8, 30000)],
  }),
  REVERTED: () => ({
    origin: { hasAdjustmentHistory: true },
    records: [cancelRecord({ adjustmentVoidedAt: `${day}T12:40:00` })],
  }),
}

async function setup(page, kind = 'NORMAL') {
  await page.clock.setFixedTime(new Date(`${day}T12:00:00`))
  const origin = {}
  const records = []
  const entries = []
  const calls = { posts: [], reverts: [], puts: [], deletes: 0, dialogs: 0 }
  const seed = name => {
    const next = SEEDS[name]()
    Object.keys(origin).forEach(key => delete origin[key])
    Object.assign(origin, baseOrigin, next.origin)
    records.splice(0, records.length, ...next.records)
    entries.splice(0, entries.length, origin, ...records)
  }
  seed(kind)
  const state = await mockPersonalization(page, { entries })
  page.on('dialog', dialog => { calls.dialogs += 1; dialog.dismiss() })
  await page.route('**/api/categories*', route => route.fulfill({ json: [
    { id: 101, name: '식비', entryType: 'EXPENSE', active: true, details: [{ id: 201, name: '외식', active: true }] },
    { id: 102, name: '기타 수입', entryType: 'INCOME', active: true, details: [] },
  ] }))
  await page.route('**/api/payment-methods*', route => route.fulfill({ json: [
    { id: 103, name: '카드', kind: 'CARD', active: true }, { id: 104, name: '현금', kind: 'CASH', active: true },
  ] }))
  let nextRefundId = 8
  await page.route('**/api/entries/7/adjustments', route => {
    if (route.request().method() === 'GET') return route.fulfill({ json: records })
    const body = route.request().postDataJSON()
    calls.posts.push(body)
    origin.hasAdjustmentHistory = true
    let record
    if (body.type === 'REFUND') {
      const id = nextRefundId
      nextRefundId = id === 8 ? 10 : id + 1
      origin.refundedPrincipal = (Number(origin.refundedPrincipal) || 0) + Number(body.principal)
      record = refundRecord(id, Number(body.principal) - Number(body.deduction) + Number(body.extra), {
        entryDate: body.date, entryTime: body.time || null, adjustmentPrincipal: Number(body.principal),
        adjustmentDeduction: Number(body.deduction), adjustmentExtra: Number(body.extra),
      })
    } else {
      origin.cancelledAt = `${body.date}T12:35:00`
      origin.financiallyExcluded = true
      record = cancelRecord({ entryDate: body.date })
    }
    records.push(record)
    entries.push(record)
    return route.fulfill({ json: { id: record.id } })
  })
  await page.route(/\/api\/entries\/\d+\/adjustments\/revert$/, route => {
    const id = Number(/entries\/(\d+)\//.exec(route.request().url())[1])
    calls.reverts.push(route.request().url())
    const record = records.find(item => item.id === id)
    record.adjustmentVoidedAt = `${day}T13:00:00`
    record.financiallyExcluded = true
    if (record.adjustmentType === 'PAYMENT_CANCELLATION') {
      origin.cancelledAt = null
      origin.financiallyExcluded = false
    } else {
      origin.refundedPrincipal = (Number(origin.refundedPrincipal) || 0) - Number(record.adjustmentPrincipal)
    }
    return route.fulfill({ status: 204, body: '' })
  })
  await page.route(/\/api\/entries\/(7|8|9|10|11)\/adjustment-group$/, route => route.fulfill({ json: { origin, adjustments: records } }))
  await page.route('**/api/entries/7', route => {
    const method = route.request().method()
    if (method === 'PUT') {
      const body = route.request().postDataJSON()
      calls.puts.push(body)
      const active = Boolean(origin.cancelledAt) || Number(origin.refundedPrincipal) > 0
      if (active && (Number(body.amount) !== Number(origin.amount) || body.entryType !== origin.entryType || body.paymentMethodId !== origin.paymentMethodId)) {
        return route.fulfill({ status: 400, json: { message: '결제 취소·환불이 적용 중인 거래는 금액을(를) 바꿀 수 없습니다. 결제 취소 취소 또는 환불 취소 후 수정해 주세요.' } })
      }
      Object.assign(origin, { entryDate: body.entryDate, entryTime: body.entryTime, title: body.title, memo: body.memo, amount: body.amount })
      return route.fulfill({ json: origin })
    }
    if (method === 'DELETE') {
      calls.deletes += 1
      entries.splice(0, entries.length, ...entries.filter(item => item !== origin && item.adjustmentOriginId !== 7))
      return route.fulfill({ status: 204, body: '' })
    }
    return route.fallback()
  })
  await page.reload()
  await page.getByRole('button', { name: '달력 가계부', exact: true }).click()
  return { state, calls, seed, origin, records }
}

async function reseed(page, mock, kind) {
  mock.seed(kind)
  await page.reload()
  await page.getByRole('button', { name: '달력 가계부', exact: true }).click()
}

async function openManage(page) {
  await page.getByRole('region', { name: '달력 조회', exact: true }).getByRole('button', { name: '거래 시트 열기' }).click()
  const sheet = page.getByRole('dialog', { name: '거래 시트' })
  const row = sheet.getByRole('row').filter({ hasText: '검증용 결제' })
  const trigger = row.getByRole('button', { name: '관리', exact: true })
  await trigger.click()
  return { sheet, row, trigger, manage: page.getByRole('dialog', { name: '거래 관리' }) }
}

const action = (manage, name) => manage.getByRole('button', { name, exact: true })
const status = (manage, text) => manage.getByRole('status').filter({ hasText: text })

test('input, payment cancellation, cancelling the cancellation and a full edit run in one window', async ({ page }) => {
  const mock = await setup(page)
  const { sheet, row, manage } = await openManage(page)
  for (const name of ['거래 내용 수정', '환불', '결제 취소', '삭제']) await expect(action(manage, name)).toBeVisible()

  await action(manage, '결제 취소').click()
  const region = manage.getByRole('region', { name: '결제 취소 기록' })
  await expect(region).toBeVisible()
  await region.getByRole('button', { name: '결제 취소 저장' }).click()
  await expect(region).toHaveCount(0)
  await expect(manage).toBeVisible()
  await expect(manage.locator('.ledger-manage-state').getByText('결제 취소됨')).toBeVisible()
  await expect(status(manage, '결제 취소를 저장했습니다')).toBeVisible()
  await expect(manage.locator('[data-manage-action]').first()).toHaveAttribute('data-manage-action', 'uncancel')
  await expect(action(manage, '결제 취소 취소')).toBeVisible()
  await expect(action(manage, '삭제')).toHaveAttribute('aria-disabled', 'true')
  await expect(action(manage, '삭제')).toHaveAccessibleDescription('결제 취소를 먼저 취소해야 삭제할 수 있습니다.')
  expect(await row.locator('.sheet-table__amount-main').first().evaluate(element => getComputedStyle(element).textDecorationLine)).toBe('line-through')

  await action(manage, '결제 취소 취소').click()
  const confirmRegion = manage.getByRole('region', { name: '결제 취소 취소' })
  await expect(confirmRegion).toBeVisible()
  await confirmRegion.getByRole('button', { name: '결제 취소 취소하기' }).click()
  await expect(confirmRegion).toHaveCount(0)
  expect(mock.calls.reverts).toHaveLength(1)
  expect(mock.calls.reverts[0]).toMatch(/\/api\/entries\/9\/adjustments\/revert$/)
  await expect(manage.locator('.ledger-manage-state').getByText('취소 되돌림')).toBeVisible()
  await expect(action(manage, '거래 내용 수정')).toHaveAccessibleDescription(/모든 항목/)
  await expect(action(manage, '삭제')).not.toHaveAttribute('aria-disabled', 'true')

  await action(manage, '거래 내용 수정').click()
  await expect(sheet).toHaveCount(0)
  await expect(page.getByText('거래 수정 중')).toBeVisible()
  await expect(page.getByRole('textbox', { name: '거래 제목' })).toHaveValue('검증용 결제')
  expect(mock.calls.dialogs).toBe(0)
  expect(mock.state.errors).toEqual([])
})

test('a cancelled entry allows a limited edit inside the window', async ({ page }) => {
  const mock = await setup(page, 'CANCELLED')
  const { manage } = await openManage(page)
  await expect(action(manage, '거래 내용 수정')).toHaveAccessibleDescription(/제목·메모·분류·날짜·시간만/)
  await action(manage, '거래 내용 수정').click()
  const region = manage.getByRole('region', { name: '거래 내용 수정' })
  await expect(region).toBeVisible()
  await expect(region).toContainText('결제 취소가 적용 중이라')
  await expect(region).toContainText('고정 항목: ₩100,000 · 지출 · 카드')
  const date = region.getByLabel('날짜', { exact: true })
  await expect(date).toHaveAttribute('max', day)
  await region.getByLabel('제목', { exact: true }).fill('검증용 결제 수정')
  await region.getByLabel('메모 (선택)').fill('테스트 메모 수정')
  await region.getByRole('button', { name: '저장', exact: true }).click()
  await expect(status(manage, '거래 내용을 수정했습니다')).toBeVisible()
  expect(mock.calls.puts).toHaveLength(1)
  expect(mock.calls.puts[0]).toMatchObject({
    amount: 100000, entryType: 'EXPENSE', paymentMethodId: 103, title: '검증용 결제 수정', entryTime: '12:30', memo: '테스트 메모 수정',
  })
  await expect(manage).toContainText('검증용 결제 수정')

  await action(manage, '거래 내용 수정').click()
  await region.getByLabel('날짜', { exact: true }).fill(NEXT_DAY)
  await expect(region.getByRole('button', { name: '저장', exact: true })).toBeDisabled()
  await expect(region.getByRole('status')).toContainText(`처리일(${day})보다 늦은 날짜로 바꿀 수 없습니다.`)
  await region.getByRole('button', { name: '돌아가기' }).click()
  await expect(region).toHaveCount(0)
  expect(mock.state.errors).toEqual([])
})

test('refunds can be added and one of several can be cancelled', async ({ page }) => {
  const mock = await setup(page)
  const { manage } = await openManage(page)
  await action(manage, '환불').click()
  const refund = manage.getByRole('region', { name: '환불 기록' })
  await expect(refund.getByLabel('차감액 (원)')).toBeVisible()
  await refund.getByLabel('환불 기준금액 (원)').fill('30000')
  await refund.getByRole('button', { name: '환불 기록 저장' }).click()
  await expect(refund).toHaveCount(0)
  await expect(manage.locator('.ledger-manage-state').locator('.adjustment-status', { hasText: '부분 환불' })).toBeVisible()
  await expect(action(manage, '환불')).toHaveCount(1)
  await expect(action(manage, '환불 취소')).toHaveCount(1)
  await expect(action(manage, '결제 취소')).toHaveAttribute('aria-disabled', 'true')
  await expect(action(manage, '결제 취소')).toHaveAccessibleDescription(/환불 기록이 있어 결제 취소할 수 없습니다/)

  await action(manage, '환불').click()
  await expect(refund).toBeVisible()
  await refund.getByLabel('환불 기준금액 (원)').fill('20000')
  await refund.getByRole('button', { name: '환불 기록 저장' }).click()
  await expect(refund).toHaveCount(0)
  await expect(action(manage, '환불 취소')).toHaveAccessibleDescription('적용 중인 환불 2건 중 하나를 골라 되돌립니다')

  await action(manage, '환불 취소').click()
  const choose = manage.getByRole('region', { name: '환불 취소' })
  await expect(choose.getByRole('group', { name: '되돌릴 환불 선택' })).toBeVisible()
  await expect(choose.getByRole('radio')).toHaveCount(2)
  await expect(choose.getByRole('button', { name: '환불 취소하기' })).toBeDisabled()
  await choose.getByRole('radio').nth(1).check()
  await choose.getByRole('button', { name: '환불 취소하기' }).click()
  await expect(choose).toHaveCount(0)
  expect(mock.calls.reverts).toHaveLength(1)
  expect(mock.calls.reverts[0]).toMatch(/\/api\/entries\/10\/adjustments\/revert$/)
  await expect(status(manage, '환불을 취소했습니다')).toBeVisible()
  expect(mock.state.errors).toEqual([])
})

test('a reverted entry can be deleted with its history from the window', async ({ page }) => {
  const mock = await setup(page, 'REVERTED')
  const { row, manage } = await openManage(page)
  await action(manage, '삭제').click()
  const region = manage.getByRole('region', { name: '거래 삭제' })
  await expect(region).toContainText('되돌린 처리 이력 1건도 함께 이동')
  await region.getByRole('button', { name: '삭제하기' }).click()
  await expect.poll(() => mock.calls.deletes).toBe(1)
  await expect(manage).toHaveCount(0)
  await expect(row).toHaveCount(0)
  expect(mock.calls.dialogs).toBe(0)
  expect(mock.state.errors).toEqual([])
})

test('escape steps back before closing and restores focus', async ({ page }) => {
  const mock = await setup(page)
  const { trigger, manage } = await openManage(page)
  await action(manage, '결제 취소').click()
  const region = manage.getByRole('region', { name: '결제 취소 기록' })
  await expect(region).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(region).toHaveCount(0)
  await expect(manage).toBeVisible()
  await expect(action(manage, '결제 취소')).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(manage).toHaveCount(0)
  await expect(trigger).toBeFocused()
  expect(mock.state.errors).toEqual([])
})

test('action descriptions are not clipped in any state, theme or layout', async ({ page }, testInfo) => {
  test.setTimeout(120000)
  const mock = await setup(page)
  const kinds = ['NORMAL', 'CANCELLED', 'PARTIALLY_REFUNDED', 'REVERTED']
  for (const theme of ['light', 'dark']) {
    await page.evaluate(value => {
      if (value === 'dark') localStorage.setItem('calen-theme-mode', 'toss')
      else localStorage.removeItem('calen-theme-mode')
    }, theme)
    for (const kind of kinds) {
      await reseed(page, mock, kind)
      const { manage } = await openManage(page)
      await expect(manage.locator('[data-manage-action]').first()).toBeVisible()
      const metrics = await manage.evaluate(element => ({
        overflow: element.scrollWidth - element.clientWidth,
        buttons: [...element.querySelectorAll('[data-manage-action]')].map(button => {
          const small = button.querySelector('small')
          return {
            id: button.dataset.manageAction,
            paddingTop: parseFloat(getComputedStyle(button).paddingTop),
            gap: button.getBoundingClientRect().bottom - small.getBoundingClientRect().bottom,
            clipped: small.scrollHeight > small.clientHeight + 1,
          }
        }),
      }))
      expect(metrics.overflow, `${kind} ${theme} horizontal overflow`).toBeLessThanOrEqual(1)
      expect(metrics.buttons.length).toBeGreaterThan(0)
      for (const button of metrics.buttons) {
        expect(button.paddingTop, `${kind} ${theme} ${button.id} padding`).toBeGreaterThan(0)
        expect(button.gap, `${kind} ${theme} ${button.id} bottom gap`).toBeGreaterThanOrEqual(8)
        expect(button.clipped).toBe(false)
      }
      await manage.screenshot({ path: testInfo.outputPath(`manage-${kind.toLowerCase()}-${theme}.png`) })
      if (kind === 'CANCELLED') {
        await action(manage, '결제 취소 취소').click()
        await expect(manage.getByRole('region', { name: '결제 취소 취소' })).toBeVisible()
        await manage.screenshot({ path: testInfo.outputPath(`manage-uncancel-step-${theme}.png`) })
        await manage.getByRole('button', { name: '돌아가기' }).click()
        await action(manage, '거래 내용 수정').click()
        await expect(manage.getByRole('region', { name: '거래 내용 수정' })).toBeVisible()
        await manage.screenshot({ path: testInfo.outputPath(`manage-edit-limited-${theme}.png`) })
      }
    }
  }
  expect(mock.state.errors).toEqual([])
})
