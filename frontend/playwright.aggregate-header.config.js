import base from './playwright.config.js'

export default {
  ...base,
  testMatch: 'mobile-usability.spec.js',
  workers: 1,
  outputDir: './artifacts/aggregate-header/results',
  reporter: [['list'], ['html', { outputFolder: './artifacts/aggregate-header/report', open: 'never' }]],
  use: { ...base.use, baseURL: 'http://127.0.0.1:5196' },
  projects: [{ name: 'chromium', use: { browserName: 'chromium' } }],
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 5196 --strictPort',
    url: 'http://127.0.0.1:5196',
    reuseExistingServer: false,
    timeout: 60000,
  },
}
