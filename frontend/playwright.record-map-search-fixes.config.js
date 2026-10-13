import base from './playwright.record-map-redesign.config.js'
export default {
  ...base,
  outputDir: './artifacts/record-map-search-fixes/results',
  reporter: [['list'], ['html', { outputFolder: './artifacts/record-map-search-fixes/report', open: 'never' }]],
  use: { ...base.use, baseURL: 'http://127.0.0.1:5193' },
  webServer: { ...base.webServer, command: 'npm run dev -- --host 127.0.0.1 --port 5193 --strictPort', url: 'http://127.0.0.1:5193' },
}
