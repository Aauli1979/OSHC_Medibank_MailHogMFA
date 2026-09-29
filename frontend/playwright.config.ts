import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './tests',
  timeout: 60_000,
  workers: 1,
  fullyParallel: false,
  use: {
    baseURL: 'http://localhost:5173',
    headless: true,
    launchOptions: {
      executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || undefined,
      args: ['--no-sandbox'],
    },
  },
  webServer: [
    {
      command: 'java -jar ../backend/target/smartguide-1.0.0.jar --server.port=18080 --spring.datasource.url=jdbc:h2:mem:passkey-e2e --spring.jpa.hibernate.ddl-auto=create-drop',
      url: 'http://localhost:18080/api/auth/csrf',
      reuseExistingServer: false,
      timeout: 120_000,
    },
    {
      command: 'npm run dev -- --host 127.0.0.1 --strictPort',
      url: 'http://localhost:5173',
      env: { API_PROXY_TARGET: 'http://localhost:18080' },
      reuseExistingServer: false,
      timeout: 30_000,
    },
  ],
});
