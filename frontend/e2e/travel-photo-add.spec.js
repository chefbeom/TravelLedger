import { expect, test } from '@playwright/test'
import {
  canvasImage, createState, gpxFile, mock, openLegacyList, openMap, photoSet, requestsTo, seedPhotoRecord, withExif,
} from './fixtures/travelMock.js'

// "사진 추가" flows: every photo is chosen through the real file chooser that opens from the button click.

async function pick(page, buttonName, files, { scope = page } = {}) {
  const [chooser] = await Promise.all([
    page.waitForEvent('filechooser'),
    scope.getByRole('button', { name: buttonName, exact: true }).click(),
  ])
  await chooser.setFiles(files)
}

async function nearPhoto(page) {
  return {
    name: 'IMG_0104.jpg',
    mimeType: 'image/jpeg',
    buffer: withExif(await canvasImage(page, 'image/jpeg', 5), { lat: 34.99495, lon: 135.78505, dateTime: '2026:10:01 10:20:00' }),
  }
}

const photoDialog = (page) => page.getByRole('dialog', { name: '사진 추가' })
const importPresigns = (state, recordId) => requestsTo(state, 'POST', new RegExp(`^/api/travel/memories/${recordId}/media/presign$`))

async function openPhotoReview(page, state, files) {
  await mock(page, state)
  await openMap(page, { plan: 90 })
  await pick(page, '사진 추가', files)
  const dialog = photoDialog(page)
  await expect(dialog).toBeVisible()
  return dialog
}

test('P1 one GPS photo is added to the selected trip in three steps', async ({ page }) => {
  const state = createState()
  await mock(page, state)
  await openMap(page, { plan: 90 })
  const photos = await photoSet(page)
  await pick(page, '사진 추가', photos.c)
  const dialog = photoDialog(page)
  await expect(dialog).toBeVisible()
  await expect(dialog.getByLabel('저장할 여행')).toHaveValue('90')
  const items = dialog.locator('li.travel-import-item[data-kind="photos"]')
  await expect(items).toHaveCount(1)
  await expect(dialog.getByRole('button', { name: '지도에서 위치 찍기' })).toHaveCount(0)
  await expect(dialog.getByRole('searchbox', { name: '장소 검색' })).toHaveCount(0)
  await expect(dialog.getByRole('button', { name: '경로 직접 그리기' })).toHaveCount(0)
  await expect(items.getByLabel('기록 제목')).toHaveValue('기요미즈데라')
  await dialog.getByRole('button', { name: '저장', exact: true }).click()
  await expect(dialog).toBeHidden()
  const memories = requestsTo(state, 'POST', /^\/api\/travel\/plans\/90\/memories$/)
  expect(memories).toHaveLength(1)
  expect(memories[0].body.memoryDate).toBe('2026-10-03')
  expect(memories[0].body.latitude).not.toBeNull()
  expect(memories[0].body.longitude).not.toBeNull()
  const presigns = importPresigns(state, 501)
  expect(presigns).toHaveLength(1)
  expect(presigns[0].body.files).toHaveLength(1)
  await expect(page.locator('.travel-map-import-notice')).toContainText('기록 1건 · 사진 1장')
  expect(state.errors).toEqual([])
})

async function reviewAttach(page, state) {
  seedPhotoRecord(state)
  const photos = await photoSet(page)
  const near = await nearPhoto(page)
  const dialog = await openPhotoReview(page, state, [photos.a, photos.b, near])
  const items = dialog.locator('li.travel-import-item')
  await expect(items).toHaveCount(2)
  await expect(items.nth(1).getByLabel('기록 제목')).toHaveValue('후시미이나리')
  return { dialog, items }
}

test('P2 several photos are grouped and a nearby photo is attached to the existing record', async ({ page }) => {
  const state = createState()
  const { dialog, items } = await reviewAttach(page, state)
  expect(requestsTo(state, 'GET', /^\/api\/travel\/plans\/90$/).length).toBeGreaterThanOrEqual(1)
  const attached = items.nth(0)
  await expect(attached.getByLabel('저장 위치')).toHaveValue('501')
  await expect(attached.getByLabel('기록 제목')).toHaveCount(0)
  await expect(attached.locator('.travel-import-item__attach')).toContainText('기요미즈데라')
  await expect(dialog.locator('.travel-import-summary')).toContainText('기존 기록에 추가 1')
  await dialog.getByRole('button', { name: '저장', exact: true }).click()
  await expect(dialog).toBeHidden()
  const memories = requestsTo(state, 'POST', /^\/api\/travel\/plans\/90\/memories$/)
  expect(memories).toHaveLength(1)
  expect(memories[0].body.title).toBe('후시미이나리')
  const toExisting = importPresigns(state, 501)
  expect(toExisting).toHaveLength(1)
  expect(toExisting[0].body.files).toHaveLength(1)
  const toNew = importPresigns(state, 502)
  expect(toNew).toHaveLength(1)
  expect(toNew[0].body.files).toHaveLength(2)
  await expect(page.locator('.travel-map-import-notice')).toContainText('그중 사진 1장은 기존 기록 1건에 더했습니다')
  expect(state.errors).toEqual([])
})

test('P2b choosing "새 기록으로 저장" creates a record instead of attaching', async ({ page }) => {
  const state = createState()
  const { dialog, items } = await reviewAttach(page, state)
  await items.nth(0).getByLabel('저장 위치').selectOption({ label: '새 기록으로 저장' })
  await expect(items.nth(0).getByLabel('기록 제목')).toBeVisible()
  await dialog.getByRole('button', { name: '저장', exact: true }).click()
  await expect(dialog).toBeHidden()
  expect(requestsTo(state, 'POST', /^\/api\/travel\/plans\/90\/memories$/)).toHaveLength(2)
  expect(importPresigns(state, 501)).toHaveLength(0)
  expect(state.errors).toEqual([])
})

test('P3 a photo without GPS or date is saved with an estimated date and can get a location', async ({ page }) => {
  const state = createState()
  const photos = await photoSet(page)
  const dialog = await openPhotoReview(page, state, photos.plain)
  const item = dialog.locator('li.travel-import-item')
  await expect(item).toHaveCount(1)
  await expect(item.locator('.travel-import-flag')).toHaveText(['날짜 추정', '위치 없음'])
  await expect(item.getByLabel('날짜')).toHaveValue('2026-10-01')
  await expect(dialog.locator('h3.travel-import-day')).toHaveText('날짜 확인 필요')
  await dialog.getByRole('button', { name: '저장', exact: true }).click()
  await expect(dialog).toBeHidden()
  const memories = requestsTo(state, 'POST', /^\/api\/travel\/plans\/90\/memories$/)
  expect(memories).toHaveLength(1)
  expect(memories[0].body.latitude).toBeNull()
  expect(state.errors).toEqual([])
})

test('P3b the location of a photo without GPS is set on the map', async ({ page }) => {
  const state = createState()
  const photos = await photoSet(page)
  const dialog = await openPhotoReview(page, state, photos.plain)
  const item = dialog.locator('li.travel-import-item')
  await item.getByRole('button', { name: '지도에서 위치 지정', exact: true }).click()
  const canvas = dialog.locator('.travel-map__canvas')
  const box = await canvas.boundingBox()
  await canvas.click({ position: { x: box.width * .4, y: box.height * .6 } })
  await expect(item.locator('.travel-import-flag', { hasText: '위치 없음' })).toHaveCount(0)
  await dialog.getByRole('button', { name: '저장', exact: true }).click()
  await expect(dialog).toBeHidden()
  const memories = requestsTo(state, 'POST', /^\/api\/travel\/plans\/90\/memories$/)
  expect(memories[0].body.latitude).not.toBeNull()
  expect(memories[0].body.longitude).not.toBeNull()
  expect(state.errors).toEqual([])
})

test('P4 unsupported files are listed with a reason and "장소·GPX도 추가" switches the dialog mode', async ({ page }) => {
  const state = createState()
  const photos = await photoSet(page)
  const dialog = await openPhotoReview(page, state, [
    { name: 'a.heic', mimeType: 'image/heic', buffer: Buffer.from('not-an-image') },
    gpxFile('route.gpx', { start: '2026-10-02T03:00:00Z' }),
    { name: 'big.jpg', mimeType: 'image/jpeg', buffer: Buffer.alloc(16 * 1024 * 1024) },
    photos.a,
  ])
  const rejected = dialog.locator('.travel-import-rejected li')
  await expect(rejected).toHaveCount(3)
  await expect(rejected.filter({ hasText: 'HEIC' })).toHaveCount(1)
  await expect(rejected.filter({ hasText: '사진(JPG·PNG·WEBP·GIF·BMP)만' })).toHaveCount(1)
  await expect(rejected.filter({ hasText: '15MB' })).toHaveCount(1)
  await expect(dialog.locator('li.travel-import-item')).toHaveCount(1)
  await expect(dialog.getByRole('button', { name: '저장', exact: true })).toBeEnabled()
  await dialog.getByRole('button', { name: '장소·GPX도 추가', exact: true }).click()
  const all = page.getByRole('dialog', { name: '여행에 추가' })
  await expect(all).toBeVisible()
  await expect(all.getByRole('button', { name: '지도에서 위치 찍기' })).toBeVisible()
  await expect(all.locator('li.travel-import-item')).toHaveCount(1)
  expect(state.errors).toEqual([])
})

test('P5 a failed upload is retried without creating records twice', async ({ page }) => {
  const state = createState({ failMemoryPresigns: 1 })
  const { dialog } = await reviewAttach(page, state)
  await dialog.getByRole('button', { name: '저장', exact: true }).click()
  const failed = dialog.locator('li.travel-import-item[data-status="failed"]')
  await expect(failed).toHaveCount(1)
  const status = failed.locator('.travel-import-item__status')
  await expect(status).toContainText('실패')
  await expect(status).not.toContainText(/Presigned|decode/)
  expect(requestsTo(state, 'POST', /^\/api\/travel\/plans\/90\/memories$/)).toHaveLength(1)
  await dialog.getByRole('button', { name: '남은 항목 다시 저장', exact: true }).click()
  await expect(dialog).toBeHidden()
  expect(requestsTo(state, 'POST', /^\/api\/travel\/plans\/90\/memories$/)).toHaveLength(1)
  expect(importPresigns(state, 501)).toHaveLength(2)
  expect(importPresigns(state, 502)).toHaveLength(1)
  expect(state.errors).toEqual([])
})

async function openLightboxAddPhotos(page, files) {
  await page.locator('.travel-map__canvas .leaflet-marker-icon').first().click()
  await page.getByRole('button', { name: /사진 크게 보기/ }).first().click()
  const lightbox = page.locator('.travel-modal--lightbox')
  await expect(lightbox).toBeVisible()
  await pick(page, '이 기록에 사진 추가', files, { scope: lightbox })
  const dialog = page.getByRole('dialog', { name: '기록 수정' })
  await expect(dialog).toBeVisible()
  return dialog
}

test('P6 two photos are added to an existing record from the photo viewer without a record update', async ({ page }, testInfo) => {
  const state = createState()
  seedPhotoRecord(state)
  await mock(page, state)
  await openMap(page, { plan: 90 })
  const photos = await photoSet(page)
  const dialog = await openLightboxAddPhotos(page, [photos.a, photos.b])
  await expect(page.locator('.travel-setup-workflow')).toHaveCount(0)
  await expect(dialog.locator('.travel-edit-photo.is-new')).toHaveCount(2)
  await expect(dialog.getByText('사진 위치 있음').first()).toBeVisible()
  const add = await dialog.getByRole('button', { name: '사진 추가', exact: true }).boundingBox()
  expect(add.y + add.height).toBeLessThanOrEqual(page.viewportSize().height)
  await dialog.getByRole('button', { name: '저장', exact: true }).click()
  await expect(dialog).toBeHidden()
  expect(requestsTo(state, 'PUT', /^\/api\/travel\/memories\/501$/)).toHaveLength(0)
  const presigns = importPresigns(state, 501)
  expect(presigns).toHaveLength(1)
  expect(presigns[0].body.files).toHaveLength(2)
  expect(requestsTo(state, 'POST', /^\/api\/travel\/memories\/501\/media\/complete$/)).toHaveLength(1)
  await expect(page.locator('.travel-map-import-notice')).toContainText('사진 2장을 ‘기요미즈데라’ 기록에 추가했습니다.')
  expect(state.errors).toEqual([])
})

test('P7 a record without a location takes the first photo location and the setup screen shows the result', async ({ page }) => {
  const state = createState()
  state.memories.push({ id: 510, planId: 90, memoryDate: '2026-10-02', memoryTime: null, category: '장소', title: '위치 없는 기록', latitude: null, longitude: null, country: null, region: null, placeName: null, sharedWithCommunity: false, memo: null })
  state.nextMemoryId = 511
  await mock(page, state)
  await openMap(page, { plan: 90 })
  await openLegacyList(page)
  const photos = await photoSet(page)
  await pick(page, '위치 없는 기록에 사진 추가', photos.a)
  const dialog = page.getByRole('dialog', { name: '기록 수정' })
  await expect(dialog).toBeVisible()
  const useLocation = dialog.getByRole('checkbox', { name: '첫 사진 위치를 기록 위치로 사용' })
  await expect(useLocation).toBeChecked()
  await dialog.getByRole('button', { name: '저장', exact: true }).click()
  await expect(dialog).toBeHidden()
  const updates = requestsTo(state, 'PUT', /^\/api\/travel\/memories\/510$/)
  expect(updates).toHaveLength(1)
  expect(updates[0].body.latitude).toBeCloseTo(34.9671, 3)
  expect(updates[0].body.longitude).toBeCloseTo(135.7727, 3)
  expect(importPresigns(state, 510)).toHaveLength(1)
  await expect(page.getByRole('heading', { name: '장소·사진 목록' })).toBeVisible()
  await expect(page.getByRole('status').filter({ hasText: '기록을 수정했습니다. 사진 1장을 추가했습니다.' })).toBeVisible()
  expect(state.errors).toEqual([])
})

test('P8 a failed upload in the record editor keeps the photos and retries only the upload', async ({ page }) => {
  const state = createState({ failMemoryPresigns: 1 })
  seedPhotoRecord(state)
  await mock(page, state)
  await openMap(page, { plan: 90 })
  const photos = await photoSet(page)
  const dialog = await openLightboxAddPhotos(page, [photos.a, photos.b])
  await dialog.getByRole('button', { name: '저장', exact: true }).click()
  await expect(dialog.getByRole('alert')).toContainText('다시 저장하면 사진만 올립니다')
  await expect(dialog.getByRole('alert')).not.toContainText('Presigned')
  await expect(dialog.locator('.travel-edit-photo.is-new')).toHaveCount(2)
  await dialog.getByRole('button', { name: '저장', exact: true }).click()
  await expect(dialog).toBeHidden()
  expect(importPresigns(state, 501)).toHaveLength(2)
  expect(requestsTo(state, 'POST', /^\/api\/travel\/memories\/501\/media\/complete$/)).toHaveLength(1)
  expect(requestsTo(state, 'PUT', /^\/api\/travel\/memories\/501$/)).toHaveLength(0)
  expect(state.errors).toEqual([])
})

test('P9 the setup screen has a visible "새 기록" and a "사진 추가" button that opens the photo dialog', async ({ page }) => {
  const state = createState()
  await mock(page, state)
  await openMap(page, { plan: 90 })
  await openLegacyList(page)
  const actions = page.locator('.travel-memory-map-panel__actions')
  const newRecord = actions.getByRole('button', { name: '새 기록', exact: true })
  await expect(newRecord).toBeVisible()
  const colors = await newRecord.evaluate((element) => {
    const style = getComputedStyle(element)
    return { fill: style.webkitTextFillColor, background: style.backgroundColor }
  })
  expect(colors.fill).not.toBe(colors.background)
  if (colors.background === 'rgba(0, 0, 0, 0)') expect(colors.fill).not.toBe('rgb(255, 255, 255)')
  const photos = await photoSet(page)
  await pick(page, '사진 추가', photos.a, { scope: actions })
  const dialog = photoDialog(page)
  await expect(dialog).toBeVisible()
  await expect(dialog.getByLabel('저장할 여행')).toHaveValue('90')
  await expect(dialog.getByLabel('기록 제목')).toHaveValue('후시미이나리')
  await dialog.getByRole('button', { name: '저장', exact: true }).click()
  await expect(dialog).toBeHidden()
  await expect(page.getByRole('heading', { name: '장소·사진 목록' })).toBeVisible()
  await expect(page.getByRole('row').filter({ hasText: '후시미이나리' })).toHaveCount(1)
  await expect(page.getByRole('status').filter({ hasText: '기록 1건 · 사진 1장' })).toBeVisible()
  expect(state.errors).toEqual([])
})

test('P10 dropping a photo on the map opens the photo dialog', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'chromium-desktop', 'desktop only')
  const state = createState()
  await mock(page, state)
  await openMap(page, { plan: 90 })
  const transfer = await page.evaluateHandle(async () => {
    const canvas = document.createElement('canvas')
    canvas.width = 64
    canvas.height = 48
    canvas.getContext('2d').fillRect(0, 0, 64, 48)
    const blob = await new Promise((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.8))
    const data = new DataTransfer()
    data.items.add(new File([blob], 'dropped.jpg', { type: 'image/jpeg' }))
    return data
  })
  const panel = page.locator('.travel-overview-map-panel')
  await panel.dispatchEvent('dragover', { dataTransfer: transfer })
  await expect(page.locator('.travel-map-drop-hint')).toBeVisible()
  await expect(page.locator('.travel-map-drop-hint')).toContainText('가을 교토 여행')
  await panel.dispatchEvent('drop', { dataTransfer: transfer })
  const dialog = photoDialog(page)
  await expect(dialog).toBeVisible()
  await expect(dialog.locator('li.travel-import-item')).toHaveCount(1)
  expect(state.errors).toEqual([])
})

test('P11 Escape closes the photo dialog, releases previews and returns focus to the button', async ({ page }) => {
  await page.addInitScript(() => {
    window.__revoked = 0
    const original = URL.revokeObjectURL
    URL.revokeObjectURL = (url) => { window.__revoked += 1; return original.call(URL, url) }
  })
  const state = createState()
  await mock(page, state)
  await openMap(page, { plan: 90 })
  const photos = await photoSet(page)
  await pick(page, '사진 추가', [photos.a, photos.c])
  const dialog = photoDialog(page)
  await expect(dialog.locator('li.travel-import-item')).toHaveCount(2)
  page.once('dialog', (confirmation) => confirmation.accept())
  await page.keyboard.press('Escape')
  await expect(dialog).toBeHidden()
  await expect.poll(() => page.evaluate(() => document.activeElement?.textContent?.trim())).toBe('사진 추가')
  expect(await page.evaluate(() => window.__revoked)).toBeGreaterThanOrEqual(2)
  expect(requestsTo(state, 'POST', /\/api\/travel\/plans\/\d+\/memories$/)).toHaveLength(0)
  expect(state.errors).toEqual([])
})

test('P12 the phone layout keeps the button, the list and the save action on screen', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'chromium-mobile', 'mobile only')
  const state = createState()
  await mock(page, state)
  await openMap(page, { plan: 90 })
  const viewport = page.viewportSize()
  const button = await page.getByRole('button', { name: '사진 추가', exact: true }).boundingBox()
  expect(button.y + button.height).toBeLessThanOrEqual(viewport.height)
  const photos = await photoSet(page)
  await pick(page, '사진 추가', [photos.a, photos.b, photos.c])
  const dialog = photoDialog(page)
  await expect(dialog.locator('li.travel-import-item')).toHaveCount(2)
  const box = await dialog.boundingBox()
  expect(Math.round(box.width)).toBe(viewport.width)
  expect(Math.round(box.height)).toBe(viewport.height)
  const itemBox = await dialog.locator('li.travel-import-item').first().boundingBox()
  const mapBox = await dialog.locator('.travel-import-mapbox').boundingBox()
  expect(itemBox.y).toBeLessThan(mapBox.y)
  const save = await dialog.getByRole('button', { name: '저장', exact: true }).boundingBox()
  expect(save.y + save.height).toBeLessThanOrEqual(viewport.height)
  const metrics = await page.evaluate(() => {
    const element = document.querySelector('.travel-import-dialog')
    return { page: document.documentElement.scrollWidth, dialogScroll: element.scrollWidth, dialogClient: element.clientWidth }
  })
  expect(metrics.page).toBeLessThanOrEqual(viewport.width)
  expect(metrics.dialogScroll).toBeLessThanOrEqual(metrics.dialogClient)
  expect(state.errors).toEqual([])
})

test('P13 cancelling the file chooser does nothing', async ({ page }) => {
  const state = createState()
  await mock(page, state)
  await openMap(page, { plan: 90 })
  await pick(page, '사진 추가', [])
  await page.waitForTimeout(300)
  await expect(page.getByRole('dialog')).toHaveCount(0)
  expect(requestsTo(state, 'POST', /\/api\/travel\//)).toHaveLength(0)
  expect(state.errors).toEqual([])
})

for (const theme of ['light', 'dark']) {
  test(`P14 the setup map toolbar has its own "사진 추가" that opens the photo dialog (${theme})`, async ({ page }) => {
    if (theme === 'dark') await page.addInitScript(() => localStorage.setItem('calen-theme-mode', 'toss'))
    const state = createState()
    await mock(page, state)
    await openMap(page, { plan: 90 })
    await openLegacyList(page)
    const toolButton = page.getByRole('button', { name: '사진 추가 (지도 도구)' })
    await toolButton.scrollIntoViewIfNeeded()
    await expect(toolButton).toBeVisible()
    await expect(page.getByRole('button', { name: '사진 추가', exact: true })).toHaveCount(1)
    const photos = await photoSet(page)
    const [chooser] = await Promise.all([page.waitForEvent('filechooser'), toolButton.click()])
    await chooser.setFiles(photos.a)
    const dialog = photoDialog(page)
    await expect(dialog).toBeVisible()
    await expect(dialog.getByLabel('저장할 여행')).toHaveValue('90')
    expect(state.errors).toEqual([])
  })
}

test('P15 in the dark theme the primary "사진 추가" differs from "새 기록" and the toolbar button is emphasised', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('calen-theme-mode', 'toss'))
  const state = createState()
  await mock(page, state)
  await openMap(page, { plan: 90 })
  await openLegacyList(page)
  const actions = page.locator('.travel-memory-map-panel__actions')
  const style = (locator) => locator.evaluate((element) => {
    const computed = getComputedStyle(element)
    return { background: computed.backgroundColor, fill: computed.webkitTextFillColor, border: computed.borderTopColor, shadow: computed.boxShadow, weight: computed.fontWeight }
  })
  const add = await style(actions.getByRole('button', { name: '사진 추가', exact: true }))
  const record = await style(actions.getByRole('button', { name: '새 기록', exact: true }))
  expect(add.background).not.toBe(record.background)
  expect(add.fill).not.toBe(add.background)
  const toolButton = page.getByRole('button', { name: '사진 추가 (지도 도구)' })
  await toolButton.scrollIntoViewIfNeeded()
  const tool = await style(toolButton)
  expect(tool.shadow).not.toBe('none')
  expect(Number(tool.weight)).toBeGreaterThanOrEqual(700)
  expect(state.errors).toEqual([])
})
