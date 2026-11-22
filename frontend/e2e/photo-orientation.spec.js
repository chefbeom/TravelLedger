import { expect, test } from '@playwright/test'
import {
  createState, mock, openMap, markerJpeg, orientationOnlyExif, portraitSceneJpeg, requestsTo, seedPhotoRecord,
  withOrientationExif, withSegment,
} from './fixtures/travelMock.js'

// Orientation: the original bytes stay untouched; the uploaded thumbnails are display-oriented exactly once.

async function captureUploads(page) {
  const uploads = []
  // registered after mock(): the later handler runs first
  await page.route('**/__mock-upload/**', async (route) => {
    uploads.push({ path: new URL(route.request().url()).pathname, body: route.request().postDataBuffer() })
    await route.fulfill({ status: 200, body: '' })
  })
  return uploads
}

function describeBitmap(page, bytes) {
  return page.evaluate(async (base64) => {
    const raw = Uint8Array.from(atob(base64), (c) => c.charCodeAt(0))
    const bitmap = await createImageBitmap(new Blob([raw]))
    const canvas = document.createElement('canvas')
    canvas.width = bitmap.width
    canvas.height = bitmap.height
    const context = canvas.getContext('2d')
    context.drawImage(bitmap, 0, 0)
    const at = (x, y) => Array.from(context.getImageData(x, y, 1, 1).data).slice(0, 3)
    const w = bitmap.width
    const h = bitmap.height
    const result = {
      width: w,
      height: h,
      top: at(Math.floor(w / 2), 5),
      bottom: at(Math.floor(w / 2), h - 6),
      corners: { TL: at(3, 3), TR: at(w - 4, 3), BR: at(w - 4, h - 4), BL: at(3, h - 4) },
    }
    bitmap.close()
    return result
  }, Buffer.from(bytes).toString('base64'))
}

const redCorner = (info) => Object.entries(info.corners).find(([, [r, , b]]) => r > 180 && b < 120)?.[0] ?? 'none'

test('O1 a portrait photo (Orientation 6) keeps its original bytes and gets upright thumbnails', async ({ page }) => {
  const state = createState()
  await mock(page, state)
  const uploads = await captureUploads(page)
  await openMap(page, { plan: 90 })
  const original = withOrientationExif(await portraitSceneJpeg(page), { orientation: 6, lat: 35.0116, lon: 135.7681, dateTime: '2026:10:03 15:40:00' })
  const [chooser] = await Promise.all([
    page.waitForEvent('filechooser'),
    page.getByRole('button', { name: '사진 추가', exact: true }).click(),
  ])
  await chooser.setFiles({ name: 'IMG_PORTRAIT.jpg', mimeType: 'image/jpeg', buffer: original })
  const dialog = page.getByRole('dialog', { name: '사진 추가' })
  await expect(dialog).toBeVisible()
  const preview = dialog.locator('li.travel-import-item img').first()
  await expect.poll(() => preview.evaluate((img) => (img.complete ? img.naturalHeight - img.naturalWidth : -1))).toBeGreaterThan(0)
  await dialog.getByRole('button', { name: '저장', exact: true }).click()
  await expect(dialog).toBeHidden()

  const originalPut = uploads.find((item) => /\/501-0$/.test(item.path))
  expect(originalPut).toBeTruthy()
  expect(Buffer.compare(originalPut.body, original)).toBe(0)

  const expected = { pin: 96, mini: 240, preview: 240, detail: 240 }
  for (const [variant, width] of Object.entries(expected)) {
    const put = uploads.find((item) => item.path.endsWith(`/501-0-${variant}`))
    expect(put, variant).toBeTruthy()
    const info = await describeBitmap(page, put.body)
    expect(info.width, variant).toBe(width)
    expect(info.height, variant).toBe(width * 2)
    expect(info.top[2], `${variant} sky blue`).toBeGreaterThan(200)
    expect(info.top[1], `${variant} sky green`).toBeGreaterThan(150)
    expect(info.bottom[1], `${variant} grass green`).toBeGreaterThan(120)
    expect(info.bottom[0], `${variant} grass red`).toBeLessThan(100)
  }
  const complete = requestsTo(state, 'POST', /^\/api\/travel\/memories\/501\/media\/complete$/)
  expect(complete).toHaveLength(1)
  expect(complete[0].body.files[0].orientedThumbnails).toBe(true)
  expect(state.errors).toEqual([])
})

const EXPECTED = {
  1: { corner: 'TL', portrait: false },
  2: { corner: 'TR', portrait: false },
  3: { corner: 'BR', portrait: false },
  4: { corner: 'BL', portrait: false },
  5: { corner: 'TL', portrait: true },
  6: { corner: 'TR', portrait: true },
  7: { corner: 'BR', portrait: true },
  8: { corner: 'BL', portrait: true },
}

test('O2 every EXIF orientation produces a correctly oriented client thumbnail (1 stays unchanged)', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'chromium-desktop', 'desktop only')
  const state = createState()
  await mock(page, state)
  await openMap(page, { plan: 90 })
  const raw = await markerJpeg(page)
  for (let orientation = 1; orientation <= 8; orientation += 1) {
    const bytes = withSegment(raw, orientationOnlyExif(orientation, orientation % 2 === 1))
    const mini = await page.evaluate(async ({ base64, orientation: o }) => {
      const { prepareClientImageUpload } = await import('/src/lib/mediaPreview.js')
      const file = new File([Uint8Array.from(atob(base64), (c) => c.charCodeAt(0))], `o${o}.jpg`, { type: 'image/jpeg' })
      const prepared = await prepareClientImageUpload(file)
      const thumb = prepared.thumbnails.find((item) => item.variant === 'mini')
      const buffer = new Uint8Array(await thumb.blob.arrayBuffer())
      let binary = ''
      buffer.forEach((b) => { binary += String.fromCharCode(b) })
      return { base64: btoa(binary), oriented: prepared.orientedThumbnails }
    }, { base64: bytes.toString('base64'), orientation })
    expect(mini.oriented).toBe(true)
    const info = await describeBitmap(page, Buffer.from(mini.base64, 'base64'))
    const want = EXPECTED[orientation]
    expect([info.width, info.height], `orientation ${orientation} size`).toEqual(want.portrait ? [60, 120] : [120, 60])
    expect(redCorner(info), `orientation ${orientation} marker`).toBe(want.corner)
  }
  expect(state.errors).toEqual([])
})

test('O4 thumbnail URLs carry the cache version', async ({ page }) => {
  const state = createState()
  seedPhotoRecord(state)
  await mock(page, state)
  await openMap(page, { plan: 90 })
  const thumbs = page.locator('img[src*="thumbnail=true"]')
  await expect.poll(() => thumbs.count()).toBeGreaterThan(0)
  for (const src of await thumbs.evaluateAll((images) => images.map((image) => image.getAttribute('src')))) {
    expect(src).toContain('tv=2')
  }
  expect(state.errors).toEqual([])
})
