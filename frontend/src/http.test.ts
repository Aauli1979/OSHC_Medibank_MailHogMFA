import { afterEach, describe, it, expect, vi } from 'vitest';
import { req, rawReq } from './http';
afterEach(() => vi.unstubAllGlobals());
describe('session and CSRF requests', () => {
  it('fetches a fresh token for every mutation, including after a new login session', async () => {
    let sequence = 0;
    const fetcher = vi.fn(async (url: string, init: RequestInit) => {
      expect(init.credentials).toBe('include');
      if (url === '/api/auth/csrf') return new Response(JSON.stringify({ headerName: 'X-CSRF-TOKEN', token: 'token-' + ++sequence }));
      expect(new Headers(init.headers).get('X-CSRF-TOKEN')).toBe('token-' + sequence);
      expect(new Headers(init.headers).get('Content-Type')).toBe('application/json');
      return new Response(JSON.stringify({ ok: true }));
    });
    vi.stubGlobal('fetch', fetcher);
    await req('/api/auth/login', { method: 'POST', body: '{}' });
    await req('/api/auth/passkeys/registration/options', { method: 'POST', body: '{}' });
    expect(sequence).toBe(2); expect(fetcher).toHaveBeenCalledTimes(4);
  });
  it('reads without requesting a token and handles empty success responses', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(new Response('[]'))
      .mockResolvedValueOnce(new Response(JSON.stringify({ headerName: 'X-CSRF-TOKEN', token: 'token' })))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetcher);
    expect(await req('/api/auth/passkeys')).toEqual([]);
    expect(await req('/api/auth/passkeys/1', { method: 'DELETE' })).toBeUndefined();
  });
  it('propagates backend messages and never submits a mutation when CSRF setup fails', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ message: 'Please sign in.' }), { status: 401 }))
      .mockResolvedValueOnce(new Response('', { status: 500 }));
    vi.stubGlobal('fetch', fetcher);
    await expect(rawReq('/api/auth/passkeys')).rejects.toThrow('Please sign in.');
    await expect(req('/api/auth/login', { method: 'POST', body: '{}' })).rejects.toThrow('Could not prepare');
    expect(fetcher).toHaveBeenCalledTimes(2);
  });
});

describe('opaque-token refresh', () => {
  it('refreshes a 401 with a JSON error message and retries the original request with a fresh CSRF token', async () => {
    let profileCalls = 0;
    let tokens = 0;
    const fetcher = vi.fn(async (url: string, init: RequestInit) => {
      if (url === '/api/auth/csrf') return new Response(JSON.stringify({ headerName: 'X-CSRF-TOKEN', token: 'csrf-' + ++tokens }));
      if (url === '/api/auth/me') return new Response(JSON.stringify({ authenticated: false }), { status: 401 });
      if (url === '/api/auth/refresh') return new Response(JSON.stringify({ authenticated: true }));
      if (url === '/api/profile') {
        profileCalls++;
        expect(new Headers(init.headers).get('X-CSRF-TOKEN')).toBe('csrf-' + tokens);
        return profileCalls === 1
          ? new Response(JSON.stringify({ message: 'Authentication required.' }), { status: 401 })
          : new Response(JSON.stringify({ email: 'student@example.test' }));
      }
      throw new Error('Unexpected request: ' + url);
    });
    vi.stubGlobal('fetch', fetcher);
    expect(await req('/api/profile', { method: 'PUT', body: '{}' })).toEqual({ email: 'student@example.test' });
    expect(profileCalls).toBe(2);
    expect(tokens).toBe(3);
    expect(fetcher.mock.calls.filter(([url]) => url === '/api/auth/refresh')).toHaveLength(1);
  });
  it('does not refresh a failed sign-in or wrong reauthentication password', async () => {
    const fetcher = vi.fn(async (url: string) => {
      if (url === '/api/auth/csrf') return new Response(JSON.stringify({ headerName: 'X-CSRF-TOKEN', token: 'csrf' }));
      if (url === '/api/auth/me') return new Response(JSON.stringify({ authenticated: true }));
      return new Response(JSON.stringify({ message: 'Check your password.' }), { status: 401 });
    });
    vi.stubGlobal('fetch', fetcher);
    await expect(req('/api/auth/login', { method: 'POST', body: '{}' })).rejects.toThrow('Check your password.');
    await expect(req('/api/auth/passkeys/registration/options', { method: 'POST', body: '{}' })).rejects.toThrow('Check your password.');
    expect(fetcher.mock.calls.some(([url]) => url === '/api/auth/refresh')).toBe(false);
  });
  it('shares one refresh between simultaneous protected requests', async () => {
    let refreshed = false;
    let refreshes = 0;
    const fetcher = vi.fn(async (url: string) => {
      if (url === '/api/auth/csrf') return new Response(JSON.stringify({ headerName: 'X-CSRF-TOKEN', token: 'csrf' }));
      if (url === '/api/auth/refresh') {
        refreshes++;
        await new Promise(resolve => setTimeout(resolve, 10));
        refreshed = true;
        return new Response('{}');
      }
      if (!refreshed) return new Response('{}', { status: 401 });
      return new Response(JSON.stringify({ ok: true }));
    });
    vi.stubGlobal('fetch', fetcher);
    expect(await Promise.all([req('/api/profile'), req('/api/progress')])).toEqual([{ ok: true }, { ok: true }]);
    expect(refreshes).toBe(1);
  });
});
