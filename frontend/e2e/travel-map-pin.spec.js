import { expect, test } from '@playwright/test'

test('journey pin has no square background in dark or light theme', async ({ page }) => {
  await page.goto('/')
  await page.evaluate(() => {
    const map = document.createElement('div')
    map.className = 'travel-map'
    map.innerHTML = `<div class="leaflet-marker-icon leaflet-interactive travel-map__icon-root" role="button" tabindex="0">
      <svg class="travel-map-pin-glyph" viewBox="0 0 44 52" style="--map-pin-color:#16875c">
        <path class="travel-map-pin-glyph__shape" d="M22 1.5C10.7 1.5 1.5 10.3 1.5 21.1c0 12.1 16.4 27.6 19.2 30.1a1.9 1.9 0 0 0 2.6 0c2.8-2.5 19.2-18 19.2-30.1C42.5 10.3 33.3 1.5 22 1.5Z" />
        <circle class="travel-map-pin-glyph__center" cx="22" cy="20.5" r="7.2" />
      </svg>
    </div>`
    const shell = document.createElement('div')
    shell.className = 'app-shell'
    shell.appendChild(map)
    document.body.appendChild(shell)
  })

  for (const theme of ['default', 'toss']) {
    await page.evaluate((value) => { document.documentElement.dataset.theme = value }, theme)
    const styles = await page.locator('.travel-map__icon-root').evaluate((element) => {
      const wrapper = getComputedStyle(element)
      const glyph = getComputedStyle(element.querySelector('svg'))
      const shape = getComputedStyle(element.querySelector('path'))
      return {
        wrapperBackground: wrapper.backgroundColor,
        wrapperImage: wrapper.backgroundImage,
        wrapperShadow: wrapper.boxShadow,
        glyphBackground: glyph.backgroundColor,
        shapeFill: shape.fill,
      }
    })
    expect(styles, theme).toEqual({
      wrapperBackground: 'rgba(0, 0, 0, 0)',
      wrapperImage: 'none',
      wrapperShadow: 'none',
      glyphBackground: 'rgba(0, 0, 0, 0)',
      shapeFill: 'rgb(22, 135, 92)',
    })
  }
})
