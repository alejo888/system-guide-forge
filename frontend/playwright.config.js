const { defineConfig } = require('@playwright/test');

module.exports = defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  timeout: 60_000,
  expect: { timeout: 30_000 },
  use: {
    baseURL: process.env.SGF_FRONTEND_URL || 'http://127.0.0.1:4200',
    channel: 'msedge',
    trace: 'retain-on-failure'
  }
});
