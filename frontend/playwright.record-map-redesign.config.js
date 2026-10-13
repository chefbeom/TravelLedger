import base from './playwright.record-map.config.js'
export default {
  ...base,
  outputDir: './artifacts/record-map-redesign/results',
  reporter: [['list'], ['html', { outputFolder: './artifacts/record-map-redesign/report', open: 'never' }]],
  use: { ...base.use, baseURL: 'http://127.0.0.1:5191' },
  webServer: { ...base.webServer, command: 'npm run dev -- --host 127.0.0.1 --port 5191 --strictPort', url: 'http://127.0.0.1:5191' },
}
