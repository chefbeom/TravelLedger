import base from './playwright.config.js'

// Dedicated loopback port/output: never reuse a user's server or erase older QA artifacts.
export default {
  ...base,
  workers: 1,
  outputDir: './artifacts/record-map-validation/results',
  reporter: [['list'], ['html', { outputFolder: './artifacts/record-map-validation/report', open: 'never' }]],
  use: { ...base.use, baseURL: 'http://127.0.0.1:5187' },
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 5187 --strictPort',
    url: 'http://127.0.0.1:5187',
    reuseExistingServer: false,
    timeout: 60_000,
  },
}
