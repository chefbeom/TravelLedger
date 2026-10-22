import base from './playwright.config.js'

export default {
  ...base,
  testMatch: ['receipt-analysis.spec.js', 'receipt-admin-concurrency.spec.js'],
  workers: 1,
  outputDir: './artifacts/receipt-analysis/results',
  reporter: [['list'], ['html', { outputFolder: './artifacts/receipt-analysis/report', open: 'never' }]],
  use: { ...base.use, baseURL: 'http://127.0.0.1:5197' },
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 5197 --strictPort',
    url: 'http://127.0.0.1:5197',
    reuseExistingServer: false,
    timeout: 60000,
  },
}
