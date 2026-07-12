import { readFileSync } from 'node:fs'
import { expect, test } from '@playwright/test'

const modalFixtures = [
  ['travel-modal', 'travel-modal__dialog'],
  ['receipt-ocr-modal', 'receipt-ocr-modal__dialog'],
  ['ledger-ai-modal', 'ledger-ai-modal__dialog'],
  ['ai-result-modal', 'ai-result-modal__dialog'],
  ['household-aggregate-chart-modal', 'household-aggregate-chart-modal__panel'],
  ['transaction-sheet-settings-modal', 'transaction-sheet-settings-modal__dialog'],
  ['main-photo-frame-modal', 'main-photo-frame-modal__dialog'],
  ['public-map-share-photo-modal', 'public-map-share-photo-modal__panel'],
  ['admin-ops-modal', 'admin-ops-modal__dialog'],
  ['admin-support-modal', 'admin-support-modal__dialog'],
  ['admin-access-control-modal', 'admin-access-control-modal__dialog'],
  ['global-notification-modal', 'global-notification-modal__dialog'],
  ['profile-workspace-modal', 'profile-workspace-modal__dialog'],
]

async function renderModalFixture(page, overlayClass, panelClass) {
  await page.evaluate(({ overlayClass: overlay, panelClass: panel }) => {
    document.querySelector('#modal-theme-fixture')?.remove()
    const overlayElement = document.createElement('div')
    overlayElement.id = 'modal-theme-fixture'
    overlayElement.className = overlay

    const panelElement = document.createElement('section')
    panelElement.className = panel
    panelElement.innerHTML = `
      <h2>Theme contract</h2>
      <label class="field__label" for="modal-theme-input">Field</label>
      <input id="modal-theme-input" value="Theme value" />
      <div class="public-map-share-photo-modal__media">Media</div>
    `
    overlayElement.append(panelElement)
    document.body.append(overlayElement)
  }, { overlayClass, panelClass })
}

async function modalColors(page, panelClass) {
  return page.evaluate((panelSelector) => {
    const panel = document.querySelector(`.${panelSelector}`)
    const input = panel?.querySelector('input')
    const heading = panel?.querySelector('h2')
    const media = panel?.querySelector('.public-map-share-photo-modal__media')
    const read = (element) => element ? getComputedStyle(element) : null
    return {
      panelBackground: read(panel)?.backgroundColor,
      panelColor: read(panel)?.color,
      inputBackground: read(input)?.backgroundColor,
      inputColor: read(input)?.color,
      headingColor: read(heading)?.color,
      mediaBackground: read(media)?.backgroundColor,
    }
  }, panelClass)
}

function contrastRatio(foreground, background) {
  const channels = (value) => (value.match(/[\d.]+/g) || []).slice(0, 3).map(Number)
  const luminance = (value) => {
    const [red, green, blue] = channels(value).map((channel) => {
      const normalized = channel / 255
      return normalized <= 0.03928 ? normalized / 12.92 : ((normalized + 0.055) / 1.055) ** 2.4
    })
    return 0.2126 * red + 0.7152 * green + 0.0722 * blue
  }
  const lighter = Math.max(luminance(foreground), luminance(background))
  const darker = Math.min(luminance(foreground), luminance(background))
  return (lighter + 0.05) / (darker + 0.05)
}

function expectReadable(colors) {
  expect(contrastRatio(colors.panelColor, colors.panelBackground)).toBeGreaterThanOrEqual(4.5)
  expect(contrastRatio(colors.headingColor, colors.panelBackground)).toBeGreaterThanOrEqual(4.5)
  expect(contrastRatio(colors.inputColor, colors.inputBackground)).toBeGreaterThanOrEqual(4.5)
}

test('representative modal surfaces follow light and dark theme tokens', async ({ page }) => {
  await page.goto('/')
  await expect(page.locator('#app')).toBeVisible()

  for (const [overlayClass, panelClass] of modalFixtures) {
    await renderModalFixture(page, overlayClass, panelClass)

    await page.evaluate(() => document.documentElement.removeAttribute('data-theme'))
    await expect.poll(() => modalColors(page, panelClass), { message: panelClass }).toMatchObject({
      panelBackground: 'rgb(255, 255, 255)',
      inputBackground: 'rgb(255, 255, 255)',
      mediaBackground: 'rgb(11, 16, 32)',
    })
    expectReadable(await modalColors(page, panelClass))

    await page.evaluate(() => document.documentElement.setAttribute('data-theme', 'toss'))
    await expect.poll(() => modalColors(page, panelClass), { message: panelClass }).toMatchObject({
      panelBackground: 'rgb(21, 25, 34)',
      inputBackground: 'rgb(17, 24, 33)',
      mediaBackground: 'rgb(11, 16, 32)',
    })
    expectReadable(await modalColors(page, panelClass))
  }
})
test('AI result window stays above the analysis manager modal', () => {
  const source = readFileSync(new URL('../src/components/StatisticsWorkspace.vue', import.meta.url), 'utf8')
  const managerLayer = Number(source.match(/\.ledger-ai-modal\s*\{[\s\S]*?z-index:\s*(\d+)/)?.[1])
  const resultLayer = Number(source.match(/\.ai-result-modal\s*\{[\s\S]*?z-index:\s*(\d+)/)?.[1])

  expect(managerLayer).toBeGreaterThan(0)
  expect(resultLayer).toBeGreaterThan(managerLayer)
})
test('global Escape closes the topmost accessible modal', async ({ page }) => {
  await page.goto('/')
  await expect(page.locator('#app')).toBeVisible()

  await page.evaluate(() => {
    const lower = document.createElement('section')
    lower.className = 'travel-modal'
    lower.style.zIndex = '100'
    lower.innerHTML = '<button data-modal-close type="button">닫기</button>'

    const upper = document.createElement('section')
    upper.setAttribute('role', 'dialog')
    upper.setAttribute('aria-modal', 'true')
    upper.style.zIndex = '200'
    upper.innerHTML = '<button data-modal-close type="button">닫기</button>'

    lower.querySelector('button').addEventListener('click', () => lower.remove())
    upper.querySelector('button').addEventListener('click', () => upper.remove())
    document.body.append(lower, upper)
  })

  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(page.locator('.travel-modal')).toHaveCount(1)

  await page.keyboard.press('Escape')
  await expect(page.locator('.travel-modal')).toHaveCount(0)
})