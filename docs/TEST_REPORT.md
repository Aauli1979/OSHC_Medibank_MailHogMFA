# Validation report

Date: 2026-09-29 (Australia/Melbourne)

Base: `Aauli1979/OSHC_Medibank_MailHogMFA`

Commit: `473c75bcded6e59f9ffe2ebd04045459cf4d0cf4`

## Dependency fix verification

The container dependency-prefetch failure reported after the first archive was
reproduced with Maven Dependency Plugin 3.8.1 and the original POM. The corrected
POM retains Spring Boot 3.5.4 and Yubico 2.9.0, uses Dependency Plugin 3.9.0, and
pins Jackson 2.19.2, Guava 33.7.1-jre and CBOR 4.5.6.

| Check after the fix | Result |
| --- | --- |
| Original `dependency:go-offline` | Reproduced the same missing Jackson 2.18.9-SNAPSHOT parent failure |
| Corrected `dependency:go-offline` | Passed using an empty Maven cache and a temporary folder containing only the POM |
| Container's subsequent `mvn -o -DskipTests package` | Passed after copying source into that folder, using only the prefetched dependencies |
| Corrected `mvn verify` | Passed: 30 tests, zero failures/errors |
| Chromium Passkey regression after the fix | Passed: enrollment, email-free sign-in, rename, removal, revoked-key rejection and password/TOTP fallback |
| Resolved runtime graph | 86 dependencies checked; no SNAPSHOT versions or unresolved ranges; all Jackson modules are 2.19.2 |

The fresh prefetch cache contained no SNAPSHOT entries. The offline package check
was completed before running other Maven build goals that could warm that cache.
This verifies both Maven commands in the Containerfile, but is not a Docker/Podman
image-build or full-stack startup test. See [DEPENDENCY_FIX.md](DEPENDENCY_FIX.md)
for the cause and rebuild commands, and [RUNTIME_DEPENDENCIES.txt](RUNTIME_DEPENDENCIES.txt)
for the resolved application dependency list.

## Original port checks

These checks were recorded for the original port. Backend tests were rerun after
the dependency fix as reported above; the frontend source and npm dependencies
were not changed by the fix.

| Check | Result |
| --- | --- |
| JDK 21 / Maven 3.9.11, `mvn verify` | Passed: 30 tests, no failures or errors; executable JAR packaged |
| Clean frontend dependency installation, `npm ci` | Passed |
| React/HTTP tests, `npm test` | Passed: 12 tests |
| TypeScript and Vite production build, `npm run build` | Passed |
| Playwright with Chromium virtual authenticator | Passed: all 5 browser tests, including live SMTP capture |
| Docker Compose v2.39.4 `config --quiet` | Passed with defaults |
| Compose parsing with `.env.example` | Passed; services are `mailhog`, `backend`, `frontend` |
| Project source/documentation language scan | No Han characters found |
| Compose-file inventory | Exactly one file: root `compose.yaml` |

## Backend coverage

The 23 Passkey integration cases use real ES256 signatures with the Yubico verifier.
They cover discoverable registration/sign-in, device user verification, credential
ownership, name changes, deletion, email changes, account deletion, fresh challenge
sessions, CSRF, password/TOTP reauthentication and rate limiting. Negative cases
include altered origin, RP ID, challenge, user presence/verification flags, signature,
user handle, cross-origin flag, replayed/expired/wrong-purpose challenges and
signature-counter rollback.

One H2 migration test verifies that existing user rows are retained and the optional
Passkey SQL migration can run twice.

Six MailHog MFA integration tests verify:

- Mandatory registration TOTP, resumption from login, and blocked protected access
  before verification.
- Real Passkey sign-in issuing opaque cookies, access-token expiry, refresh-token
  rotation/replay rejection, logout and revoke-all.
- Rejection of credentials and tokens for an account whose registration is pending.
- Reset-email sender, recipient and link, password update, token revocation and
  rejection of a reused reset link.
- SMTP failure cleanup and no message for an unknown account.
- Progress retention after email changes and CORS origin restrictions.

The mail sender is mocked in these backend tests. The browser SMTP test below uses
an actual MailHog process and the real Spring mail sender.

## Frontend and browser coverage

Twelve component and HTTP-wrapper tests cover Passkey enrollment, metadata refresh,
rename/removal, cancellation, unsupported contexts, errors, discoverable sign-in,
fresh CSRF headers, error-status preservation and shared refresh/retry behavior.

All five Playwright tests passed against the built backend and Vite:

1. Add a discoverable Passkey, sign in without email, rename it, remove it, reject
   the revoked credential, then use password/TOTP fallback.
2. Unsupported browser capability displays a usable password fallback.
3. Cancellation explains the outcome and restores usable controls.
4. The registration UI blocks access until TOTP verification and issues HttpOnly
   access/refresh cookies afterward.
5. A real SMTP message reaches MailHog, its reset link opens in the UI, the password
   is changed, and the new password plus TOTP can sign in.

The test runner used an isolated H2 in-memory database, backend port 18080, frontend
port 5173, and a MailHog v1.0.1 Linux process on SMTP 11025 / HTTP 18025. The service
processes were stopped after testing. No production accounts or emails were used.

## Reproduce

```sh
cd backend
mvn verify
cd ../frontend
npm ci
npm test
npm run build
npx playwright install chromium
npm run test:e2e
```

By default, the live SMTP browser test is skipped unless `MAILHOG_API_URL` is set.
To include it using the project's MailHog service, start MailHog from the root:

```sh
docker compose up -d mailhog
cd frontend
MAIL_HOST=127.0.0.1 MAIL_PORT=1025 MAILHOG_API_URL=http://127.0.0.1:8025 npm run test:e2e
```

The Playwright backend inherits these environment variables. Use a development
MailHog instance and keep ports 18080 and 5173 free. On PowerShell, set the three
variables through `$env:` before running `npm run test:e2e`.

## Not executed

- Docker/Podman image builds and the full container stack: no Docker or Podman
  engine was available in the verification environment. Compose validation checks
  configuration only. The application and live MailHog were exercised as local
  processes, not as containers.
- A deployed HTTPS reverse proxy and real fingerprint/face/security-key devices.
- Native Android APK and Credential Manager integration.
- Data conversion from the old Tokenised project's encrypted token vault.

These results describe this port, not a general security audit of the upstream
application. See `MIGRATION.md` for source and database compatibility boundaries.
