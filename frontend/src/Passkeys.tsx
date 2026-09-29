import React from 'react';
import { browserSupportsWebAuthn, startAuthentication, startRegistration } from '@simplewebauthn/browser';
import { req } from './http';

type CreationOptions = Parameters<typeof startRegistration>[0]['optionsJSON'];
type RequestOptions = Parameters<typeof startAuthentication>[0]['optionsJSON'];
type Passkey = {
  id: number; name: string; createdAt: string; lastUsedAt: string | null;
  backupEligible: boolean; backedUp: boolean;
};

export function passkeyUnavailable(): string {
  if (!window.isSecureContext) return 'Passkeys need HTTPS, or http://localhost for local development.';
  if (!browserSupportsWebAuthn()) return 'This browser does not support passkeys. Please use a current browser.';
  return '';
}

export function passkeyError(error: unknown): string {
  // DOMException and cross-realm browser errors are not always instanceof Error.
  const name = error && typeof error === 'object' && 'name' in error && typeof error.name === 'string' ? error.name : '';
  if (name === 'NotAllowedError') return 'The passkey request was cancelled or timed out. You can try again.';
  if (name === 'InvalidStateError') return 'This passkey is already registered. Try another device or security key.';
  if (name === 'SecurityError') return 'Passkeys are not configured for this website address. Check the site domain and HTTPS settings.';
  if (name === 'NotSupportedError' || name === 'ConstraintError') return 'This device cannot create the required passkey. Try another device or a security key with PIN support.';
  return error && typeof error === 'object' && 'message' in error && typeof error.message === 'string'
    ? error.message : 'Could not use the passkey. Please try again.';
}

export async function signInWithPasskey(): Promise<void> {
  const unavailable = passkeyUnavailable();
  if (unavailable) throw new Error(unavailable);
  const options = await req<{ publicKey: RequestOptions }>('/api/auth/passkeys/authentication/options', { method: 'POST' });
  const credential = await startAuthentication({ optionsJSON: options.publicKey });
  await req('/api/auth/passkeys/authentication/verify', { method: 'POST', body: JSON.stringify(credential) });
}

export function PasskeySettings({ mfaEnabled }: { mfaEnabled: boolean }) {
  const [keys, setKeys] = React.useState<Passkey[]>([]);
  const [loading, setLoading] = React.useState(true);
  const [busy, setBusy] = React.useState(false);
  const [name, setName] = React.useState('');
  const [password, setPassword] = React.useState('');
  const [code, setCode] = React.useState('');
  const [message, setMessage] = React.useState('');
  const [error, setError] = React.useState('');
  const [editing, setEditing] = React.useState<number | null>(null);
  const [editName, setEditName] = React.useState('');
  const unavailable = passkeyUnavailable();
  const refresh = async () => setKeys(await req<Passkey[]>('/api/auth/passkeys'));

  React.useEffect(() => {
    let active = true;
    req<Passkey[]>('/api/auth/passkeys').then(value => { if (active) setKeys(value); })
      .catch(error => { if (active) setError(passkeyError(error)); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, []);

  const run = async (action: () => Promise<void>) => {
    if (busy) return;
    setBusy(true); setError(''); setMessage('');
    try { await action(); }
    catch (error) { setError(passkeyError(error)); }
    finally { setBusy(false); setPassword(''); setCode(''); }
  };
  const canConfirm = password.length > 0 && (!mfaEnabled || code.length === 6);
  const add = () => run(async () => {
    const options = await req<{ publicKey: CreationOptions }>('/api/auth/passkeys/registration/options', {
      method: 'POST', body: JSON.stringify({ name, currentPassword: password, code })
    });
    const credential = await startRegistration({ optionsJSON: options.publicKey });
    await req('/api/auth/passkeys/registration/verify', { method: 'POST', body: JSON.stringify(credential) });
    setName(''); setMessage('Passkey added. You can now use it on the sign-in page.');
    await refresh();
  });
  const remove = (key: Passkey) => {
    if (!window.confirm(`Remove “${key.name}” from your account? Keep your password and authenticator available to sign in.`)) return;
    void run(async () => {
      await req(`/api/auth/passkeys/${key.id}`, { method: 'DELETE', body: JSON.stringify({ currentPassword: password, code }) });
      setMessage('Passkey removed from this account. You can also remove its saved copy from your device.');
      await refresh();
    });
  };
  const date = (value: string) => new Date(value).toLocaleString();

  return <div className="card security-card passkey-card" aria-busy={busy}>
    <div className="status-row"><div><h2>Passkeys</h2>
      <p>Sign in with your fingerprint, face, device PIN or security key, without entering your password or TOTP code.</p>
    </div><span className={keys.length ? 'badge good-badge' : 'badge'}>{keys.length} saved</span></div>
    <p>First sign in with your existing account, then add a passkey here. Your device keeps the private key and biometric information.</p>
    {unavailable && <div className="notice" role="status">{unavailable}</div>}
    {loading ? <p role="status">Loading passkeys…</p> : keys.length === 0
      ? <p>No passkeys yet. Add one below; your password and authenticator remain available.</p>
      : <ul className="passkey-list">{keys.map(key => <li key={key.id}>
        <div className="passkey-details">
          <strong>{key.name}</strong>
          <small>Added {date(key.createdAt)}</small>
          <small>{key.lastUsedAt ? `Last used ${date(key.lastUsedAt)}` : 'Not used to sign in yet'}</small>
          <small>{key.backedUp ? 'Authenticator reports this passkey is backed up' : 'Keep another sign-in method available'}</small>
        </div>
        {editing === key.id ? <div className="passkey-edit">
          <label>New passkey name<input autoFocus maxLength={80} value={editName} onChange={e => setEditName(e.target.value)} /></label>
          <button disabled={busy || !editName.trim()} onClick={() => void run(async () => {
            await req(`/api/auth/passkeys/${key.id}`, { method: 'PATCH', body: JSON.stringify({ name: editName }) });
            setEditing(null); setMessage('Passkey renamed.'); await refresh();
          })}>Save name</button>
          <button className="secondary" disabled={busy} onClick={() => setEditing(null)}>Cancel</button>
        </div> : <div className="mini-actions">
          <button className="secondary" disabled={busy} onClick={() => { setEditing(key.id); setEditName(key.name); }}>Rename</button>
          <button className="danger" disabled={busy || !canConfirm} onClick={() => remove(key)}>Remove</button>
        </div>}
      </li>)}</ul>}
    <fieldset className="passkey-fields" disabled={busy || loading}>
      <legend>Add or remove a passkey</legend>
      <p>Confirm your current password{mfaEnabled ? ' and authenticator code' : ''} before adding or removing a passkey.</p>
      <label>Current password for passkeys<input type="password" autoComplete="current-password" value={password} onChange={e => setPassword(e.target.value)} /></label>
      {mfaEnabled && <label>Authenticator code for passkeys<input inputMode="numeric" autoComplete="one-time-code" maxLength={6} value={code} onChange={e => setCode(e.target.value.replace(/\D/g, ''))} /></label>}
      <label>Passkey name<input maxLength={80} placeholder="For example, my laptop or security key" value={name} onChange={e => setName(e.target.value)} /></label>
      <button disabled={!canConfirm || !name.trim() || !!unavailable || keys.length >= 10} onClick={add}>
        {busy ? 'Waiting for confirmation…' : 'Add passkey'}
      </button>
      <small>Up to 10 passkeys. Adding a second passkey can help if you lose a device.</small>
    </fieldset>
    {message && <div className="success" role="status">{message}</div>}
    {error && <div className="error" role="alert">{error}</div>}
  </div>;
}
