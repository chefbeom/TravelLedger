import { expect, test } from '@playwright/test'

const sizes = [[320, 640], [360, 740], [390, 844], [412, 915], [844, 390], [390, 440]]
const memo = '긴 메모와 거래 상세 설명 '.repeat(12)
const entries = Array.from({ length: 8 }, (_, i) => ({ id: 100 + i, entryDate: '2026-10-03', entryTime: '13:07', title: '모바일 검증용 거래', memo, amount: 12000, entryType: 'EXPENSE', categoryGroupId: 101, categoryGroupName: '식비', categoryDetailId: 102, categoryDetailName: '외식', paymentMethodId: 103, paymentMethodName: '검증용 카드' }))
const photos = [1, 2, 3, 4].map(id => ({ id, mediaId: id, clusterId: id === 4 ? 20 : 10, recordId: id, planId: 1, planName: '모바일 검증 여행', planColorHex: '#16875c', latitude: id === 4 ? 35.7052 : 35.6812 + id * .000001, longitude: id === 4 ? 139.7911 : 139.7671 + id * .000001, title: `사진 ${id}`, placeName: '검증용 장소', country: '일본', region: '도쿄', memoryDate: '2026-10-03', memoryTime: `10:0${id}:00`, photoUrl: `/mobile-fixture-${id}.svg`, contentUrl: `/mobile-fixture-${id}.svg`, mediaType: 'PHOTO', recordType: 'MEMORY' }))
const clusters = [10, 20].map(id => { const members = photos.filter(p => p.clusterId === id); return { ...members[0], id, representativeMediaId: members[0].id, representativePhotoUrl: members[0].photoUrl, photoCount: members.length, memoryCount: members.length } })
const overview = { includedPlanCount: 1, markers: [], routes: [], photoPins: photos, photoClusters: clusters }

async function mock(page, authenticated = true) {
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  await page.route(/^https?:\/\/(?!127\.0\.0\.1|localhost)/, route => route.abort())
  await page.route('**/mobile-fixture-*.svg*', route => route.fulfill({ contentType: 'image/svg+xml', body: '<svg xmlns="http://www.w3.org/2000/svg" width="1200" height="800"><rect width="1200" height="800" fill="#9abccc"/></svg>' }))
  await page.route('**/api/**', route => {
    const path = new URL(route.request().url()).pathname
    let json = {}
    if (path === '/api/auth/me') return route.fulfill(authenticated ? { json: { id: 2, loginId: 'fixture', displayName: '모바일 검증', active: true, admin: false } } : { status: 401, json: {} })
    if (path === '/api/auth/csrf') json = { token: 'fixture-csrf' }
    else if (path.startsWith('/api/categories')) json = [{ id: 101, name: '식비', entryType: 'EXPENSE', active: true, details: [{ id: 102, name: '외식', active: true }] }]
    else if (path.startsWith('/api/payment-methods')) json = [{ id: 103, name: '검증용 카드', active: true }]
    else if (path === '/api/entries/date-range') json = { minDate: '2026-10-01', maxDate: '2026-10-03' }
    else if (path === '/api/entries') json = entries
    else if (path === '/api/dashboard') json = { anchorDate: '2026-10-03', quickStats: [], calendar: [], expenseBreakdown: [], paymentBreakdown: [], monthlyComparison: [], recentEntries: entries }
    else if (path === '/api/account/preferences/household-aggregates') json = { widgets: [] }
    else if (path === '/api/record-shares/counts') json = { ledger: 0, travel: 0 }
    else if (['/api/statistics/category-breakdown', '/api/statistics/payment-breakdown', '/api/statistics/compare', '/api/record-shares/groups', '/api/travel/plans', '/api/recurring-ledger/rules', '/api/recurring-ledger/occurrences/pending', '/api/file/photos', '/api/file/recent', '/api/travel/photo-frame-media'].includes(path)) json = []
    else if (path.includes('/photo-clusters/')) { const id = Number(path.split('/').at(-1)); const members = photos.filter(p => p.clusterId === id); json = { id, representativeMediaId: members[0]?.id, representativePhoto: members[0], photos: members, page: 0, size: 12, totalPhotoCount: members.length, hasNext: false } }
    else if (path === '/api/travel/my-map') json = overview
    else if (path === '/api/travel/public/map-shares/mobile-fixture') json = { token: 'mobile-fixture', title: '공유 지도 검증', ownerDisplayName: '테스터', overview }
    return route.fulfill({ json })
  })
  return errors
}

async function mountMap(page, kind) {
  await page.goto('/')
  await page.evaluate(async kind => {
    const { createApp } = await import('/node_modules/.vite/deps/vue.js')
    const { default: Component } = await import(`/src/components/${kind === 'private' ? 'TravelMyMapWorkspace' : 'TravelPublicMapShareWorkspace'}.vue`)
    document.querySelector('#app').style.display = 'none'
    const host = document.createElement('div'); host.className = 'app-shell'; document.body.append(host)
    createApp(Component, kind === 'private' ? { active: true } : { token: 'mobile-fixture' }).mount(host)
  }, kind)
  await page.locator('.leaflet-marker-icon').first().waitFor()
}

async function canTouch(locator) {
  return locator.evaluate(element => {
    const r = element.getBoundingClientRect()
    const hit = document.elementFromPoint(r.x + r.width / 2, r.y + r.height / 2)
    return r.left >= 0 && r.top >= 0 && r.right <= innerWidth && r.bottom <= innerHeight && Boolean(hit && element.contains(hit))
  })
}

async function paintColors(locator) {
  return locator.evaluate(element => {
    const colors = []
    for (let node = element; node; node = node.parentElement) {
      colors.push(getComputedStyle(node).backgroundColor.match(/[\d.]+/g).map(Number))
    }
    // Ghost buttons are transparent: measure their visible backing surface,
    // compositing translucent backgrounds instead of treating them as black.
    const background = colors.reverse().reduce((under, over) => {
      const alpha = over[3] ?? 1
      return under.map((value, i) => Math.round(over[i] * alpha + value * (1 - alpha)))
    }, [255, 255, 255])
    const style = getComputedStyle(element)
    return { color: style.webkitTextFillColor || style.color, background: `rgb(${background.join(', ')})` }
  })
}

function contrast(foreground, background) {
  const luminance = color => {
    const c = color.match(/[\d.]+/g).slice(0, 3).map(Number).map(v => v / 255).map(v => v <= .04045 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4)
    return c[0] * .2126 + c[1] * .7152 + c[2] * .0722
  }
  const a = luminance(foreground), b = luminance(background)
  return (Math.max(a, b) + .05) / (Math.min(a, b) + .05)
}

for (const [width, height] of sizes) {
  test.describe(`mobile usability ${width}x${height}`, () => {
    test.use({ viewport: { width, height }, hasTouch: true, isMobile: true })

    test('ledger controls and full amounts are reachable without covered buttons', async ({ page }, testInfo) => {
      const errors = await mock(page)
      await page.goto('/#household')
      await expect(page.locator('html')).toHaveAttribute('data-layout-mode', 'mobile')
      const tab = page.getByRole('button', { name: '달력 가계부', exact: true })
      await tab.tap()
      for (const theme of ['default', 'toss']) {
        await page.evaluate(theme => document.documentElement.dataset.theme = theme, theme)
        await tab.tap()
        const colors = await tab.evaluate(el => ({ color: getComputedStyle(el).color, background: getComputedStyle(el).backgroundColor }))
        expect(contrast(colors.color, colors.background)).toBeGreaterThanOrEqual(4.5)
      }
      await page.getByLabel('조회 기준일', { exact: true }).fill('2026-10-03')
      await page.locator('.household-entry-panel').waitFor()
      const nextDay = page.getByRole('button', { name: '다음날', exact: true })
      await nextDay.evaluate(el => { const r = el.getBoundingClientRect(); window.scrollBy(0, r.y - (innerHeight - 100)) })
      await expect.poll(() => canTouch(nextDay)).toBe(true)
      await nextDay.tap()
      await page.getByRole('button', { name: '전날', exact: true }).tap()
      await page.locator('.calendar__day--selected').first().press('Enter')
      const sheet = page.getByRole('dialog', { name: '거래 시트', exact: true })
      await sheet.waitFor()
      for (const name of ['거래 구분', '시간 정렬']) await expect.poll(() => canTouch(sheet.getByLabel(name, { exact: true }))).toBe(true)
      await sheet.getByLabel('거래 구분', { exact: true }).selectOption('EXPENSE')
      const amount = sheet.locator('.sheet-table__amount-main').first()
      await expect(amount).toHaveText('₩12,000')
      await amount.scrollIntoViewIfNeeded()
      await expect.poll(() => canTouch(amount)).toBe(true)
      const first = sheet.locator('tbody tr').first()
      const summary = first.locator('.household-sheet-memo summary')
      await summary.tap()
      await expect(first.locator('.household-sheet-memo p')).toHaveText(memo)
      await summary.tap()
      // Check all action bounds, not only document overflow.
      for (const name of ['수정', '공유', '삭제']) {
        const button = first.getByRole('button', { name, exact: true })
        await button.scrollIntoViewIfNeeded()
        await expect.poll(() => canTouch(button)).toBe(true)
        for (const theme of ['default', 'toss']) {
          await page.evaluate(theme => document.documentElement.dataset.theme = theme, theme)
          // Theme colors transition; assert the settled palette, not a mixed frame.
          await page.waitForTimeout(350)
          const colors = await paintColors(button)
          expect(contrast(colors.color, colors.background), `${name} ${theme} contrast: ${JSON.stringify(colors)}`).toBeGreaterThanOrEqual(4.5)
        }
      }
      await page.evaluate(() => document.documentElement.dataset.theme = 'default')
      await page.waitForTimeout(350)
      await page.screenshot({ path: testInfo.outputPath('ledger-sheet.png') })
      expect(errors).toEqual([])
    })

    for (const kind of ['private', 'public']) {
      test(`${kind} map pin and popup stay clear of essential controls`, async ({ page }, testInfo) => {
        const errors = await mock(page, kind === 'private')
        await mountMap(page, kind)
        await page.getByRole('button', { name: '전체 화면', exact: true }).tap()
        const days = page.locator('.travel-journey-controls__day-field select')
        await days.selectOption(await days.locator('option').nth(1).getAttribute('value'))
        // Day filtering and the resize observer both schedule map motion.
        await page.waitForTimeout(1800)
        await expect(page.locator('.travel-map')).not.toHaveClass(/travel-map--moving/)
        const marker = page.locator('.leaflet-marker-icon').first()
        await expect.poll(() => canTouch(marker)).toBe(true)
        await marker.tap() // Never dispatch a synthetic click to bypass a blocked target.
        await page.getByRole('region', { name: '선택한 여행 사진 미리보기', exact: true }).waitFor()
        await page.waitForTimeout(1800)
        const popup = page.locator('.travel-map-pin-popup')
        await expect.poll(() => popup.evaluate(el => {
          const r = el.getBoundingClientRect(), canvas = document.querySelector('.travel-map__canvas').getBoundingClientRect()
          return r.top >= canvas.top - 1 && r.bottom <= canvas.bottom + 1
        })).toBe(true)
        await expect.poll(() => canTouch(popup.locator('strong'))).toBe(true)
        const settings = page.getByRole('button', { name: '지도 설정', exact: true })
        await settings.tap()
        await expect(page.getByRole('region', { name: '여정과 경로 동시 재생', exact: true })).toBeVisible()
        await settings.tap()
        await expect(settings).toHaveAttribute('aria-expanded', 'false')
        await expect.poll(() => popup.evaluate(el => {
          const r = el.getBoundingClientRect(), canvas = document.querySelector('.travel-map__canvas').getBoundingClientRect()
          return r.top >= canvas.top - 1 && r.bottom <= canvas.bottom + 1
        })).toBe(true)
        for (const control of [days, page.getByRole('button', { name: '재생', exact: true }), page.getByRole('slider', { name: '여정 재생 간격' }), page.getByRole('button', { name: '전체 보기', exact: true })]) {
          await expect.poll(() => canTouch(control)).toBe(true)
        }
        await expect.poll(() => page.locator('.travel-map__toolbar').evaluate(el => el.scrollHeight - el.clientHeight)).toBeLessThanOrEqual(2)
        for (const theme of ['default', 'toss']) {
          await page.evaluate(theme => document.documentElement.dataset.theme = theme, theme)
          const colors = await page.locator('.travel-map__toolbar output').evaluate(el => ({ color: getComputedStyle(el).color, background: getComputedStyle(el.closest('.travel-map__toolbar')).backgroundColor }))
          expect(contrast(colors.color, colors.background)).toBeGreaterThanOrEqual(4.5)
        }
        await page.screenshot({ path: testInfo.outputPath(`${kind}-map.png`) })
        expect(errors).toEqual([])
      })
    }
  })
}

test.describe('touch layout preference', () => {
  test.use({ hasTouch: true, isMobile: true })
  test('automatic mode follows rotation and manual mode can return to automatic', async ({ page }) => {
    await mock(page)
    await page.setViewportSize({ width: 390, height: 844 })
    await page.goto('/#household')
    await expect(page.locator('html')).toHaveAttribute('data-layout-mode', 'mobile')
    await expect(page.getByRole('button', { name: '자동', exact: true })).toHaveAttribute('aria-pressed', 'true')
    await page.setViewportSize({ width: 1440, height: 900 })
    await expect(page.locator('html')).toHaveAttribute('data-layout-mode', 'desktop')
    await page.setViewportSize({ width: 844, height: 390 })
    await expect(page.locator('html')).toHaveAttribute('data-layout-mode', 'mobile')
    await page.reload()
    await expect(page.locator('html')).toHaveAttribute('data-layout-mode', 'mobile')
    await page.getByRole('button', { name: '데스크톱', exact: true }).tap()
    await page.reload()
    await expect(page.locator('html')).toHaveAttribute('data-layout-mode', 'desktop')
    await page.getByRole('button', { name: '자동', exact: true }).tap()
    await expect(page.locator('html')).toHaveAttribute('data-layout-mode', 'mobile')
    await expect(page.getByRole('button', { name: '자동', exact: true })).toHaveAttribute('aria-pressed', 'true')
  })
})
