# Passkey setup and operation

## Account and sign-in flows

1. Open the application at its configured browser origin.
2. Create an account with a strong password. Scan the QR code with a TOTP
   authenticator and enter the six-digit code. The account remains unauthenticated
   until registration MFA succeeds. If interrupted, sign in with the password to
   resume the MFA setup screen.
3. Open **Settings > Passkeys** after signing in. Enter the current password,
   current TOTP code if enabled, and a descriptive Passkey name.
4. Choose **Add passkey** and complete the browser/device confirmation. A device
   PIN, fingerprint, face or compatible security key can provide user verification.
5. Sign out. On the sign-in page, choose **Sign in with a passkey** without entering
   an email. Select the saved credential and complete device verification.

Successful Passkey sign-in issues the same HttpOnly access/refresh cookies as the
password/TOTP flow. A verified Passkey is an alternative to password plus TOTP at
sign-in; it does not bypass the initial account-registration MFA requirement.
The device's private key and biometric data are never uploaded to this backend.

Settings supports listing, renaming and removing credentials. Adding or removing
one requires the current password and, when enabled, a valid TOTP code. Renaming
requires the signed-in owner. A maximum of ten Passkeys can be saved per account.
Removing a credential revokes future use of that Passkey; it does not remove its
local copy from the device or revoke authentication cookies already issued. Use
**Revoke all sessions** to invalidate those tokens. Password/TOTP remains available
when the last Passkey is removed.

## Server verification and storage

The server uses Yubico `webauthn-server-core` 2.9.0; the browser client uses
`@simplewebauthn/browser` 13.2.2. Registration requires discoverable credentials
(`residentKey=required`), and registration and authentication require
`userVerification=required`.

Challenges expire after five minutes, are single-use, and are bound to their
session and operation; enrollment is also bound to the authenticated account.
The server checks origin, RP ID, challenge, user presence/verification, signature,
user handle and signature counter. Credential IDs are unique and account handles
remain stable when an email address changes. Passkeys are stored in H2 as public
keys and metadata, including counter, creation time, last use and backup state.
No private keys are stored by the server.

Authentication is verified from opaque `SG_ACCESS` cookies on every protected
request. Only SHA-256 token hashes are persisted. `SG_REFRESH` cookies rotate on
refresh, with concurrent use of the same refresh token rejected. The browser
shares one refresh operation between simultaneous protected requests and retries
once after a successful refresh. A failed password or TOTP check is not silently
retried as a new sign-in.

Sessions retain only CSRF tokens and temporary MFA/WebAuthn state. Completing a
login replaces that session. Revoking authentication cookies cannot be bypassed
using an old `AUTH` session attribute.

## Configuration

| Variable | Host-development default | Compose default | Meaning |
| --- | --- | --- | --- |
| `WEBAUTHN_RP_ID` | `localhost` | `localhost` | Domain only; no scheme, path or port |
| `WEBAUTHN_RP_NAME` | `OSHC SmartGuide` | Same | Browser-visible relying-party name |
| `WEBAUTHN_ORIGINS` | `http://localhost:5173` | `http://localhost:8088` | Exact allowed origins, comma-separated |
| `FRONTEND_URL` | `http://localhost:5173` | `http://localhost:8088` | Public URL used in reset emails |
| `COOKIE_SECURE` | `false` | `false` | Set `true` for HTTPS; affects auth and session cookies |
| `COOKIE_SAME_SITE` | `Lax` | `Lax` | Authentication-cookie SameSite policy |
| `ACCESS_TOKEN_MINUTES` | `15` | `15` | Access-token lifetime |
| `REFRESH_TOKEN_DAYS` | `14` | `14` | Refresh-token lifetime |
| `API_PROXY_TARGET` | `http://localhost:8080` | Not used | Vite development API target |
| `MAIL_HOST` | `127.0.0.1` | `mailhog` | SMTP host |
| `MAIL_PORT` | `1025` | `1025` | SMTP port |
| `MAIL_FROM` | `oshc-smartguide@localhost` | Same | Sender for reset emails |

Use HTTPS outside local development. Allowed origins must be HTTPS origins under
the RP ID; the explicit development exception is `http://localhost`. Do not add
paths, trailing slashes or wildcards. Do not switch between `localhost`,
`127.0.0.1` and a LAN IP while testing. WebAuthn and CORS use the same allowlist.

For example, a production site can set:

```sh
export FRONTEND_URL=https://oshc.example.com
export WEBAUTHN_RP_ID=oshc.example.com
export WEBAUTHN_ORIGINS=https://oshc.example.com
export COOKIE_SECURE=true
```

For Compose, put those values in the root `.env` and recreate the services. For
host-based Spring Boot, export them through the shell, IDE or service manager;
Spring Boot does not automatically read either `.env.example` file.

The default H2 database and in-memory challenge sessions are intended for one
backend instance. Multiple instances require shared/sticky sessions and a database
strategy appropriate to that deployment. The Passkey endpoint limiter allows 90
requests per minute per direct remote address. It does not trust arbitrary
`X-Forwarded-For`; users behind one proxy share a bucket. Configure suitable
edge rate limiting and trusted-proxy handling when deploying beyond local use.

## Password reset with MailHog

Choose **I forgot the password**, submit the account email, and open the resulting
message in `http://localhost:8025`. Follow its `/reset-password?token=...` link to
set a new password. Links expire after 15 minutes and are single-use. A successful
reset revokes all access and refresh tokens for that account. It preserves TOTP
and Passkeys. A failed SMTP delivery removes the newly generated reset token.

The MailHog inbox is a development facility, not a user mailbox. Keep it private.
For real delivery, configure an external SMTP service and the correct sender.

## API

Before each mutation, call `GET /api/auth/csrf`. Send the returned token using the
returned `headerName` and keep the same session cookie. Fetch a new token after
login or another operation that replaces the session. The frontend HTTP wrapper
handles this, including refresh requests. API scripts must do the same.

| Method and path | Requirements | Body or response |
| --- | --- | --- |
| `GET /api/auth/csrf` | Public | `{headerName,token}` |
| `POST /api/auth/passkeys/registration/options` | Access cookie; password and enabled TOTP | `{name,currentPassword,code}` returns `{publicKey:...}` |
| `POST /api/auth/passkeys/registration/verify` | Same account and challenge session | Browser registration JSON; returns public credential metadata |
| `POST /api/auth/passkeys/authentication/options` | Public | Returns discoverable-credential options; no email is requested |
| `POST /api/auth/passkeys/authentication/verify` | Same challenge session | Browser assertion JSON; issues access/refresh cookies |
| `GET /api/auth/passkeys` | Access cookie | Current account's credential metadata |
| `PATCH /api/auth/passkeys/{id}` | Credential owner | `{name}` |
| `DELETE /api/auth/passkeys/{id}` | Credential owner; password and enabled TOTP | `{currentPassword,code}`; returns 204 |
| `POST /api/auth/register/mfa/verify` | Pending registration session | `{code}`; enables TOTP and issues auth cookies |
| `POST /api/auth/refresh` | Valid refresh cookie | Rotates refresh cookie and issues a new access cookie |
| `POST /api/auth/logout` | CSRF and the cookies to revoke | Revokes this cookie pair and clears temporary session state |
| `POST /api/auth/revoke-all` | Access cookie | Revokes all stored tokens for the account |

TOTP codes are six-character strings and may start with zero. Management IDs are
database row IDs; the listing does not expose public-key material, credential IDs
or account user handles.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Passkey button disabled | Use HTTPS or localhost and a WebAuthn-capable browser |
| Domain/security error | Match browser origin, RP ID and `WEBAUTHN_ORIGINS` exactly |
| Expired or reused challenge | Start again; avoid overlapping credential dialogs in one session |
| 403 on a mutation | Fetch a new CSRF token and preserve the session cookie |
| 401 on enrollment/removal | Complete sign-in and check the current password and TOTP code |
| 429 | Wait for the limiter window; review shared reverse-proxy traffic |
| No credential on another device | Check credential-manager sync or use a backup sign-in method |
| No reset email | Check MailHog and backend logs, internal hostname and SMTP port |
| Reset link points to the wrong site | Correct `FRONTEND_URL` and request a new email |
| Account missing after a container update | Verify the Compose project name and H2 volume |

## Verification and platform scope

See [TEST_REPORT.md](TEST_REPORT.md) for executed checks. Backend tests use real
ES256 signatures, not a mocked WebAuthn verifier. Browser tests use Chromium's
virtual authenticator. Validate the final HTTPS hostname with actual target devices
before relying on Passkeys for normal use.

The included Capacitor Android project is retained from upstream. Native Android
Credential Manager, Digital Asset Links, signing configuration and physical
biometric hardware have not been implemented or certified by this browser port.
WebViews that cannot provide WebAuthn show the password fallback.
