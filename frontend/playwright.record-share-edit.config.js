import base from './playwright.config.js'
export default {
  ...base,
  workers: 1,
  outputDir: './artifacts/record-share-edit/results',
  reporter: [['list'], ['html', { outputFolder: './artifacts/record-share-edit/report', open: 'never' }]],
  use: { ...base.use, baseURL: 'http://127.0.0.1:5195' },
  webServer: { command: 'npm run dev -- --host 127.0.0.1 --port 5195 --strictPort', url: 'http://127.0.0.1:5195', reuseExistingServer: false, timeout: 60000 },
}
