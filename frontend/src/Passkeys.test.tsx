import React from 'react';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { req } from './http';
import { browserSupportsWebAuthn, startRegistration, startAuthentication } from '@simplewebauthn/browser';
import { PasskeySettings, passkeyError, passkeyUnavailable, signInWithPasskey } from './Passkeys';

vi.mock('./http', () => ({ req: vi.fn() }));
vi.mock('@simplewebauthn/browser', () => ({
  browserSupportsWebAuthn: vi.fn(), startRegistration: vi.fn(), startAuthentication: vi.fn()
}));
const request = vi.mocked(req);
const key = { id: 7, name: 'Saved key', createdAt: '2026-09-28T00:00:00Z', lastUsedAt: null, backedUp: false, backupEligible: false };

beforeEach(() => {
  vi.resetAllMocks();
  Object.defineProperty(window, 'isSecureContext', { value: true, configurable: true });
  vi.mocked(browserSupportsWebAuthn).mockReturnValue(true);
});
afterEach(cleanup);

describe('passkey settings', () => {
  it('registers with current password and TOTP, sends browser response, and clears secrets', async () => {
    let saved = false;
    request.mockImplementation(async (url, options) => {
      if (url === '/api/auth/passkeys') return saved ? [key] : [];
      if (url.endsWith('/registration/options')) {
        expect(JSON.parse(String(options?.body))).toEqual({ name: 'Laptop', currentPassword: 'password', code: '123456' });
        return { publicKey: { challenge: 'challenge' } };
      }
      if (url.endsWith('/registration/verify')) { saved = true; return key; }
      throw new Error('Unexpected URL');
    });
    vi.mocked(startRegistration).mockResolvedValue({ id: 'credential' } as never);
    const user = userEvent.setup();
    render(<PasskeySettings mfaEnabled />);
    await screen.findByText(/No passkeys yet/);
    await user.type(screen.getByLabelText('Current password for passkeys'), 'password');
    await user.type(screen.getByLabelText('Authenticator code for passkeys'), '123456');
    await user.type(screen.getByLabelText('Passkey name'), 'Laptop');
    await user.click(screen.getByRole('button', { name: 'Add passkey' }));
    await screen.findByText('Saved key');
    expect(startRegistration).toHaveBeenCalledWith({ optionsJSON: { challenge: 'challenge' } });
    expect(request).toHaveBeenCalledWith('/api/auth/passkeys/registration/verify', {
      method: 'POST', body: JSON.stringify({ id: 'credential' })
    });
    expect((screen.getByLabelText('Current password for passkeys') as HTMLInputElement).value).toBe('');
    expect((screen.getByLabelText('Authenticator code for passkeys') as HTMLInputElement).value).toBe('');
  });

  it('shows a cancelled registration and restores controls', async () => {
    request.mockImplementation(async url => url === '/api/auth/passkeys' ? [] : { publicKey: {} });
    vi.mocked(startRegistration).mockRejectedValue(new DOMException('Cancelled', 'NotAllowedError'));
    const user = userEvent.setup(); render(<PasskeySettings mfaEnabled={false} />);
    await screen.findByText(/No passkeys yet/);
    await user.type(screen.getByLabelText('Current password for passkeys'), 'password');
    await user.type(screen.getByLabelText('Passkey name'), 'Laptop');
    await user.click(screen.getByRole('button', { name: 'Add passkey' }));
    expect((await screen.findByRole('alert')).textContent).toContain('cancelled or timed out');
    expect((screen.getByLabelText('Current password for passkeys') as HTMLInputElement).disabled).toBe(false);
    expect(request.mock.calls.some(([url]) => url.endsWith('/registration/verify'))).toBe(false);
  });

  it('renames and removes only the selected key with explicit confirmation', async () => {
    let keys = [key];
    request.mockImplementation(async (url, options) => {
      if (url === '/api/auth/passkeys') return keys;
      expect(url).toBe('/api/auth/passkeys/7');
      if (options?.method === 'PATCH') { keys = [{ ...key, name: 'New name' }]; return keys[0]; }
      expect(options?.method).toBe('DELETE');
      expect(JSON.parse(String(options?.body))).toEqual({ currentPassword: 'password', code: '' });
      keys = []; return undefined;
    });
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const user = userEvent.setup(); render(<PasskeySettings mfaEnabled={false} />);
    await screen.findByText('Saved key');
    await user.click(screen.getByRole('button', { name: 'Rename' }));
    await user.clear(screen.getByLabelText('New passkey name'));
    await user.type(screen.getByLabelText('New passkey name'), 'New name');
    await user.click(screen.getByRole('button', { name: 'Save name' }));
    await screen.findByText('New name');
    await user.type(screen.getByLabelText('Current password for passkeys'), 'password');
    await user.click(screen.getByRole('button', { name: /^Remove$/ }));
    await screen.findByText(/No passkeys yet/);
    expect(window.confirm).toHaveBeenCalledOnce();
  });

  it('renders loading errors and disables unsupported enrollment', async () => {
    request.mockRejectedValue(new Error('Please sign in again.'));
    vi.mocked(browserSupportsWebAuthn).mockReturnValue(false);
    render(<PasskeySettings mfaEnabled={false} />);
    await screen.findByText('Please sign in again.');
    expect(screen.getByText(/This browser does not support passkeys/)).toBeTruthy();
    await waitFor(() => expect((screen.getByRole('button', { name: 'Add passkey' }) as HTMLButtonElement).disabled).toBe(true));
  });
});

describe('passkey sign-in', () => {
  it('uses discoverable options without an email and forwards the browser assertion', async () => {
    request.mockResolvedValueOnce({ publicKey: { challenge: 'challenge' } }).mockResolvedValueOnce({ authenticated: true });
    vi.mocked(startAuthentication).mockResolvedValue({ id: 'credential' } as never);
    await signInWithPasskey();
    expect(request).toHaveBeenNthCalledWith(1, '/api/auth/passkeys/authentication/options', { method: 'POST' });
    expect(startAuthentication).toHaveBeenCalledWith({ optionsJSON: { challenge: 'challenge' } });
    expect(request).toHaveBeenNthCalledWith(2, '/api/auth/passkeys/authentication/verify', {
      method: 'POST', body: JSON.stringify({ id: 'credential' })
    });
  });
  it('explains unavailable contexts and authenticator errors', async () => {
    Object.defineProperty(window, 'isSecureContext', { value: false, configurable: true });
    expect(passkeyUnavailable()).toContain('HTTPS');
    await expect(signInWithPasskey()).rejects.toThrow('HTTPS');
    expect(request).not.toHaveBeenCalled();
    expect(passkeyError(new DOMException('', 'InvalidStateError'))).toContain('already registered');
    expect(passkeyError(new DOMException('', 'SecurityError'))).toContain('website address');
    expect(passkeyError(new DOMException('', 'NotSupportedError'))).toContain('Try another device');
  });
});
