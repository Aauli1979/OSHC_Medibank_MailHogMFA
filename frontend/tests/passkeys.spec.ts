import { test, expect, type Page } from '@playwright/test';
import { createHmac } from 'node:crypto';

const password = 'Passkey!TestPassword123';

function totp(secret: string): string {
  let bits = '';
  for (const char of secret.toUpperCase().replace(/=+$/, '')) {
    bits += 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'.indexOf(char).toString(2).padStart(5, '0');
  }
  const bytes = Buffer.from((bits.match(/.{8}/g) || []).map(byte => parseInt(byte, 2)));
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 30_000)));
  const digest = createHmac('sha1', bytes).update(counter).digest();
  const offset = digest[digest.length - 1] & 15;
  return ((digest.readUInt32BE(offset) & 0x7fffffff) % 1_000_000).toString().padStart(6, '0');
}

async function mutate(page: Page, path: string, body: unknown = {}) {
  const csrf = await (await page.request.get('/api/auth/csrf')).json();
  return page.request.post(path, { data: body, headers: { [csrf.headerName]: csrf.token } });
}

test('browser enrolls, signs in without email, renames, revokes, and uses password/TOTP fallback', async ({ page, context }) => {
  const cdp = await context.newCDPSession(page);
  await cdp.send('WebAuthn.enable');
  const { authenticatorId } = await cdp.send('WebAuthn.addVirtualAuthenticator', {
    options: { protocol: 'ctap2', transport: 'internal', hasResidentKey: true,
      hasUserVerification: true, isUserVerified: true, automaticPresenceSimulation: true }
  });
  const email = `e2e-${Date.now()}@example.test`;
  const registered = await mutate(page, '/api/auth/register', { email, password });
  expect(registered.ok()).toBeTruthy();
  const secret = new URL((await registered.json()).otpauthUri).searchParams.get('secret')!;
  expect((await mutate(page, '/api/auth/register/mfa/verify', { code: totp(secret) })).ok()).toBeTruthy();

  await page.goto('/settings');
  await page.getByLabel('Current password for passkeys', { exact: true }).fill(password);
  await page.getByLabel('Authenticator code for passkeys').fill(totp(secret));
  await page.getByLabel('Passkey name', { exact: true }).fill('Browser test key');
  await page.getByRole('button', { name: 'Add passkey', exact: true }).click();
  await expect(page.getByText('Passkey added. You can now use it on the sign-in page.')).toBeVisible();
  await expect(page.getByText('Browser test key', { exact: true })).toBeVisible();
  const { credentials } = await cdp.send('WebAuthn.getCredentials', { authenticatorId });
  expect(credentials).toHaveLength(1);
  expect(credentials[0].isResidentCredential).toBeTruthy();

  expect((await mutate(page, '/api/auth/logout')).ok()).toBeTruthy();
  await page.goto('/login');
  await page.getByRole('button', { name: 'Sign in with a passkey', exact: true }).click();
  await expect(page).toHaveURL('http://localhost:5173/');
  await expect(page.getByRole('heading', { name: 'Understand what to do when healthcare becomes real.' })).toBeVisible();
  const who = await (await page.request.get('/api/auth/me')).json();
  expect(who.email).toBe(email);

  await page.goto('/settings');
  await expect(page.getByText(/Last used/)).toBeVisible();
  await page.getByRole('button', { name: 'Rename', exact: true }).click();
  await page.getByLabel('New passkey name', { exact: true }).fill('Renamed browser key');
  await page.getByRole('button', { name: 'Save name' }).click();
  await expect(page.getByText('Renamed browser key', { exact: true })).toBeVisible();
  await page.getByLabel('Current password for passkeys', { exact: true }).fill(password);
  await page.getByLabel('Authenticator code for passkeys').fill(totp(secret));
  page.once('dialog', dialog => dialog.accept());
  await page.getByRole('button', { name: 'Remove', exact: true }).click();
  await expect(page.getByText(/No passkeys yet/)).toBeVisible();

  expect((await mutate(page, '/api/auth/logout')).ok()).toBeTruthy();
  await page.goto('/login');
  await page.getByRole('button', { name: 'Sign in with a passkey', exact: true }).click();
  await expect(page.getByRole('alert')).toHaveText('Passkey verification failed. Please start again.');
  await page.getByLabel('Email', { exact: true }).fill(email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Continue with password' }).click();
  await page.getByLabel('Authenticator code', { exact: true }).fill(totp(secret));
  await page.getByRole('button', { name: 'Verify and enter' }).click();
  await expect(page).toHaveURL('http://localhost:5173/');
});

test('unsupported browsers show a usable password fallback', async ({ page }) => {
  await page.addInitScript(() => Object.defineProperty(window, 'PublicKeyCredential', { value: undefined }));
  await page.goto('/login');
  await expect(page.getByRole('button', { name: 'Sign in with a passkey' })).toBeDisabled();
  await expect(page.getByText(/This browser does not support passkeys/)).toBeVisible();
  await expect(page.getByRole('button', { name: 'Continue with password' })).toBeEnabled();
});

test('cancellation is explained and does not leave sign-in disabled', async ({ page }) => {
  await page.addInitScript(() => {
    Object.defineProperty(navigator.credentials, 'get', {
      value: () => Promise.reject(new DOMException('Cancelled', 'NotAllowedError'))
    });
  });
  await page.goto('/login');
  await page.getByRole('button', { name: 'Sign in with a passkey' }).click();
  await expect(page.getByRole('alert')).toHaveText('The passkey request was cancelled or timed out. You can try again.');
  await expect(page.getByRole('button', { name: 'Continue with password' })).toBeEnabled();
});

test('registration UI requires TOTP before granting access', async ({ page }) => {
  const email = `registration-${Date.now()}@example.test`;
  await page.goto('/register');
  await page.getByLabel('Email', { exact: true }).fill(email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByLabel('Confirm password', { exact: true }).fill(password);
  const response = page.waitForResponse(r => r.url().endsWith('/api/auth/register') && r.request().method() === 'POST');
  await page.getByRole('button', { name: 'Create account & continue to MFA' }).click();
  const registered = await (await response).json();
  expect(registered.registrationMfaRequired).toBeTruthy();
  await expect(page.getByRole('heading', { name: 'Set up MFA to finish registration' })).toBeVisible();
  expect((await page.request.get('/api/profile')).status()).toBe(401);
  const secret = new URL(registered.otpauthUri).searchParams.get('secret')!;
  await page.getByLabel('Authenticator code', { exact: true }).fill(totp(secret));
  await page.getByRole('button', { name: 'Verify MFA & finish registration' }).click();
  await expect(page).toHaveURL('http://localhost:5173/');
  const cookies = await page.context().cookies();
  expect(cookies.find(c => c.name === 'SG_ACCESS')?.httpOnly).toBe(true);
  expect(cookies.find(c => c.name === 'SG_REFRESH')?.httpOnly).toBe(true);
});

test('password-reset email reaches MailHog and its link works in the UI', async ({ page, request }) => {
  const inbox = process.env.MAILHOG_API_URL;
  test.skip(!inbox, 'Set MAILHOG_API_URL and the backend MAIL_HOST/MAIL_PORT to run the live SMTP test.');
  const email = `mailhog-${Date.now()}@example.test`;
  const registration = await mutate(page, '/api/auth/register', { email, password });
  expect(registration.ok()).toBeTruthy();
  const secret = new URL((await registration.json()).otpauthUri).searchParams.get('secret')!;
  expect((await mutate(page, '/api/auth/register/mfa/verify', { code: totp(secret) })).ok()).toBeTruthy();
  await mutate(page, '/api/auth/logout');
  await page.goto('/forgot-password');
  await page.getByLabel('Email', { exact: true }).fill(email);
  await page.getByRole('button', { name: 'Send reset instructions' }).click();
  await expect(page.getByText('If an account exists for that email, a password reset link has been sent.')).toBeVisible();
  let message: any;
  await expect.poll(async () => {
    const messages = await (await request.get(inbox + '/api/v2/messages')).json();
    message = messages.items.find((item: any) => item.Content.Headers.To?.includes(email));
    return !!message;
  }).toBe(true);
  const link = message.Content.Body.match(/http:\/\/localhost:5173\/reset-password\?token=[A-Za-z0-9_-]+/)?.[0];
  expect(link).toBeTruthy();
  await page.goto(link);
  const replacement = 'Changed!Password123';
  await page.getByLabel('New password', { exact: true }).fill(replacement);
  await page.getByLabel('Confirm password', { exact: true }).fill(replacement);
  await page.getByRole('button', { name: 'Change password' }).click();
  await expect(page).toHaveURL('http://localhost:5173/login');
  await page.getByLabel('Email', { exact: true }).fill(email);
  await page.getByLabel('Password', { exact: true }).fill(replacement);
  await page.getByRole('button', { name: 'Continue with password' }).click();
  await page.getByLabel('Authenticator code', { exact: true }).fill(totp(secret));
  await page.getByRole('button', { name: 'Verify and enter' }).click();
  await expect(page).toHaveURL('http://localhost:5173/');
});
