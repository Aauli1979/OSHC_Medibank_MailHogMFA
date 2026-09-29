export class HttpError extends Error {
  constructor(public readonly status: number, message: string) {
    super(message);
    this.name = 'HttpError';
  }
}

/** Obtain a fresh session-bound CSRF token before every mutation. */
export async function rawReq<T>(url: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers);
  if (options.body !== undefined) headers.set('Content-Type', 'application/json');
  const method = (options.method || 'GET').toUpperCase();
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
    const response = await fetch('/api/auth/csrf', { credentials: 'include', cache: 'no-store' });
    if (!response.ok) throw new Error('Could not prepare a secure request. Please reload the page.');
    const csrf: { headerName: string; token: string } = await response.json();
    headers.set(csrf.headerName, csrf.token);
  }
  const response = await fetch(url, { ...options, headers, credentials: 'include', cache: 'no-store' });
  if (!response.ok) {
    let message = `Request failed (${response.status})`;
    try { const body = await response.json(); message = body.message || message; } catch { /* Use the status message. */ }
    throw new HttpError(response.status, message);
  }
  return response.status === 204 ? undefined as T : response.json();
}

let refreshing: Promise<boolean> | null = null;
async function refreshToken(): Promise<boolean> {
  if (!refreshing) refreshing = rawReq('/api/auth/refresh', { method: 'POST' })
    .then(() => true).catch(() => false).finally(() => { refreshing = null; });
  return refreshing;
}
function canRefresh(url: string): boolean {
  return ['/api/profile', '/api/progress', '/api/auth/me', '/api/auth/revoke-all'].some(path => url === path || url.startsWith(path + '/'))
    || (url.startsWith('/api/auth/passkeys') && !url.includes('/authentication/'));
}
/** Retry once after an expired access token; retain server messages and HTTP status. */
export async function req<T>(url: string, options: RequestInit = {}, retry = true): Promise<T> {
  try { return await rawReq<T>(url, options); }
  catch (error) {
    if (retry && canRefresh(url) && error instanceof HttpError && error.status === 401) {
      // Avoid rotating tokens for an incorrect password/TOTP while already signed in.
      let authenticated = false;
      try { authenticated = (await rawReq<{ authenticated: boolean }>('/api/auth/me')).authenticated; }
      catch { /* An absent or expired access token can be refreshed. */ }
      if (!authenticated && await refreshToken()) return rawReq<T>(url, options);
    }
    throw error;
  }
}
