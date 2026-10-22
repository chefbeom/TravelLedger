export function calendarSettings() {
  return {
    revision: 'revision-1',
    preferences: { scalePreset: 'fit', highlightMode: 'net', defaultWeekMode: 'month', aggregatePanelEnabled: true, quickEntryPanelEnabled: true, receiptOcrPanelEnabled: true, shareAfterSaveEnabled: false, transactionSheetViewMode: 'modal' },
    layout: [{ id: 'aggregate', x: 0, y: 0, w: 9, h: 2 }, { id: 'calendar', x: 0, y: 2, w: 6, h: 6 }, { id: 'quick-entry', x: 6, y: 2, w: 3, h: 6 }, { id: 'sheet', x: 0, y: 8, w: 9, h: 3 }],
    widgets: ['TOTAL', 'PAYMENT_METHOD', 'TOTAL', 'MONTHLY_CUMULATIVE_CHART', 'MONTHLY_GOAL', 'NONE'].map((kind, index) => ({
      kind, period: 'MONTH', paymentMethodId: kind === 'PAYMENT_METHOD' ? 103 : null, amountType: kind === 'MONTHLY_CUMULATIVE_CHART' ? 'NET' : 'EXPENSE',
      monthlyExpenseTarget: kind === 'MONTHLY_GOAL' ? 800000 : 0, singleExpenseLimit: 100000, chartMaxAmount: 0,
      showIncomeCumulative: true, showExpenseCumulative: true, comparePreviousPeriod: false,
      textSize: 'MEDIUM', textColor: 'DEFAULT', title: '', hidden: false, displaySize: 'MEDIUM',
      layoutX: index + 1, layoutY: 1, layoutW: kind === 'MONTHLY_CUMULATIVE_CHART' ? 2 : 1, layoutH: 1, layoutOrder: index + 1,
    })),
  }
}

export async function mockPersonalization(page, { saved = calendarSettings(), failSave = false, conflict = false, layoutUpdatedAt = null, sharedStore = null, entryOverrides = {} } = {}) {
  const state = sharedStore || { saved, writes: [], reads: [], failSave, conflict, errors: [] }
  const entry = { id: 7, entryDate: '2026-10-22', entryTime: '12:30', entryType: 'EXPENSE', amount: 15000, title: '검증용 식사', categoryGroupId: 101, categoryDetailId: 102, paymentMethodId: 103, ...entryOverrides }
  page.on('pageerror', error => state.errors.push(error.message))
  await page.route(/^https?:\/\/(?!127\.0\.0\.1|localhost)/, route => route.abort())
  await page.route('**/api/**', async route => {
    const request = route.request(), path = new URL(request.url()).pathname
    state.reads.push({ path, method: request.method(), url: request.url() })
    let json = []
    if (path === '/api/auth/me') json = { id: 2, loginId: 'fixture', displayName: '개인화 검증', active: true, admin: false }
    else if (path === '/api/auth/csrf') json = { token: 'fixture-csrf' }
    else if (path.startsWith('/api/categories')) json = [{ id: 101, name: '식비', entryType: 'EXPENSE', active: true, details: [{ id: 102, name: '외식', active: true }] }]
    else if (path.startsWith('/api/payment-methods')) json = [{ id: 103, name: '검증용 카드', active: true }]
    else if (path === '/api/account/preferences/household-personalization') {
      if (request.method() === 'PUT') {
        const payload = request.postDataJSON()
        state.writes.push(payload)
        if (state.conflict) return route.fulfill({ status: 409, json: { message: '다른 화면에서 설정을 수정했습니다. 최신 설정을 다시 확인해 주세요.' } })
        if (state.failSave) return route.fulfill({ status: 500, json: { message: '검증용 저장 실패' } })
        state.saved = { ...payload, revision: `revision-${state.writes.length + 1}` }
      }
      json = state.saved
    } else if (path === '/api/account/preferences/household-aggregates') json = { widgets: state.saved.widgets }
    else if (path.endsWith('/layout-settings/household-calendar-view')) json = { version: 3, payload: state.saved.preferences }
    else if (path.endsWith('/layout-settings/household-calendar')) json = { version: 3, payload: state.saved.layout, updatedAt: layoutUpdatedAt }
    else if (path === '/api/entries/date-range') json = { minDate: '2026-01-01', maxDate: '2026-10-22' }
    else if (path === '/api/entries') json = request.method() === 'POST' ? { ...entry, ...request.postDataJSON() } : [entry]
    else if (path === '/api/dashboard') json = { anchorDate: '2026-10-22', quickStats: [], calendar: [], expenseBreakdown: [], paymentBreakdown: [], monthlyComparison: [], recentEntries: [entry] }
    else if (path === '/api/record-shares/counts') json = { ledger: 0, travel: 0 }
    else if (path.endsWith('/image-analysis/history/summary')) json = { pendingReviewCount: 0, pendingReviewIds: [], processingCount: 0 }
    return route.fulfill({ json })
  })
  await page.goto('/#household')
  await page.getByRole('button', { name: '달력 가계부', exact: true }).click()
  await page.locator('.household-aggregate-card').first().waitFor()
  return state
}
