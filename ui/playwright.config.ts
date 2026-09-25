import { defineConfig } from '@playwright/test';

const baseURL = process.env['PLAYWRIGHT_BASE_URL'];

if (baseURL === undefined) {
  throw new Error('PLAYWRIGHT_BASE_URL must point to the isolated Compose web service.');
}

export default defineConfig({
  testDir: './e2e',
  forbidOnly: Boolean(process.env['CI']),
  fullyParallel: false,
  outputDir: 'test-results',
  reporter: [['line'], ['html', { open: 'never', outputFolder: 'playwright-report' }]],
  retries: 0,
  timeout: 60_000,
  use: {
    baseURL,
    trace: 'retain-on-failure',
  },
  workers: 1,
  projects: [
    {
      name: 'mobile-chromium',
      testIgnore: /desktop-smoke\.spec\.ts/,
      use: {
        browserName: 'chromium',
        viewport: { width: 390, height: 844 },
      },
    },
    {
      name: 'desktop-chromium',
      testMatch: /desktop-smoke\.spec\.ts/,
      use: {
        browserName: 'chromium',
        viewport: { width: 1280, height: 900 },
      },
    },
  ],
});
