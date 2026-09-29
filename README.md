# OSHC SmartGuide

OSHC SmartGuide is a web application that helps international students understand common healthcare situations in Australia and the basics of Overseas Student Health Cover (OSHC). It combines scenario-based learning, a knowledge quiz, personal progress tracking and student account management.

The application supports password sign-in with an authenticator app, passwordless passkeys and email-based password recovery. A single `compose.yaml` runs the frontend, backend and MailHog together with Docker Compose or Podman.

This is an educational prototype. Its content does not provide medical diagnosis, emergency assistance or a guarantee of insurance coverage. Claims and payment transactions are completed through the relevant official services.

## Features

| Area | Functionality |
| --- | --- |
| Healthcare Moments | Eight learning scenarios covering feeling unwell, GP visits, pharmacies, hospitals, emergencies, medical bills, making a claim and scenario feedback. Each scenario provides a question, an explanation and a practical reminder. |
| Knowledge quiz | Multiple-choice questions with immediate answer feedback, a final score and saved quiz attempts. |
| Learning progress | Completed scenarios, completion percentage, best quiz score, attempt count, topics to review and confidence ratings. |
| Student profile | Manage name, address, date of birth, phone number and profile photo. View the account email and generated student ID. |
| Account security | Mandatory TOTP setup during registration, password and email changes, MFA management, session revocation and account deletion. |
| Passkeys | Add, list, rename and remove passkeys. Sign in using device verification without entering an email, password or authenticator code. |
| Password recovery | Request a time-limited password-reset link by email. MailHog provides a local inbox for development and demonstrations. |
| Payment navigation | Open the official PayPal, Afterpay, Mastercard or Visa website. The application does not process payments or collect payment-card details. |

Passwords are stored as BCrypt hashes. Authentication uses opaque access and refresh tokens in HttpOnly cookies; the database stores only their SHA-256 hashes. Refresh tokens rotate, protected API requests require authentication, and state-changing requests use CSRF protection.

## Technology and architecture

| Component | Implementation | Responsibility |
| --- | --- | --- |
| Frontend | React 19, TypeScript and Vite | Browser interface, learning activities and account screens. NGINX serves the built application in containers and proxies `/api/` to the backend. |
| Backend | Java 21 and Spring Boot 3.5.4 | REST API, account management, TOTP, WebAuthn, authentication and email delivery. |
| Database | H2 with Spring Data JPA | Persistent accounts, profiles, learning progress, passkey public keys and authentication-token records. |
| Development email | MailHog | SMTP capture and a web inbox for inspecting password-reset messages. |

The browser application is the primary runtime. The repository also includes a Capacitor Android project; native Android passkey integration is outside the scope of the browser implementation.

## Run with Docker Compose or Podman

### Requirements

- Docker with Docker Compose, or Podman with a Compose provider such as `podman-compose`.
- Internet access for the first image build and dependency download.
- A browser, a TOTP authenticator app and, for passkeys, a compatible device or security key.

Java, Maven and Node.js do not need to be installed on the host for the container workflow.

### Start the application

Extract the project archive and open a terminal in the directory containing `compose.yaml`. All three services are defined in this one file.

Create the local configuration:

```sh
cp .env.example .env
```

In PowerShell, use:

```powershell
Copy-Item .env.example .env
```

Start with Docker Compose:

```sh
docker compose up -d --build
```

Or start with rootless Podman as your normal user:

```sh
podman compose up -d --build
```

If you invoke the provider directly, `podman-compose up -d --build` uses the same file.

Wait for the backend to finish starting, then open:

| Service | Default address |
| --- | --- |
| Application | http://localhost:8088 |
| MailHog inbox | http://localhost:8025 |
| SMTP for applications running on the host | `127.0.0.1:1025` |

Use `localhost` consistently in the browser. The containerized backend is available through the frontend's `/api/` proxy and does not publish its own host port.

### Inspect, stop and rebuild

Run these commands from the project root:

```sh
docker compose ps
docker compose logs -f backend
```

Stop the services while keeping the database:

```sh
docker compose down
```

Rebuild and start after changing source files or configuration:

```sh
docker compose up -d --build
```

For Podman, replace `docker compose` with `podman compose` in these commands.

## Use the application

### Create an account

1. Open the application and choose **Create an account**.
2. Enter an email address and a password with at least 12 characters, including an uppercase letter, a lowercase letter, a digit and one of `@$!%*?&`.
3. Scan the displayed QR code with a TOTP authenticator app.
4. Enter its current six-digit code to finish registration.

Registration must be completed before protected pages become available. If setup is interrupted, sign in with the same email and password to resume it. MFA codes come from the authenticator app; MailHog captures password-reset emails.

### Learn and track progress

Open **Scenarios**, select a Healthcare Moment, answer its question and read the explanation. Set your confidence rating and choose **Mark scenario complete** to save completion.

Open **Quiz**, answer every question and submit to record your score. **Progress** shows completed scenarios, your best score, attempt count and topics answered incorrectly in the latest submitted quiz. **Student Profile** lets you update your personal account details and photo.

### Set up a passkey

1. Sign in and open **Settings > Passkeys**.
2. Enter your current password, a current authenticator code if MFA is enabled, and a name for the passkey.
3. Choose **Add passkey** and complete the browser or device prompt.
4. Sign out, then choose **Sign in with a passkey** on the sign-in page.

Each account supports up to ten passkeys. Device verification may use a PIN, fingerprint, face recognition or a compatible security key. The backend stores the public credential, not the device's private key or biometric data.

You can rename or remove passkeys in Settings. Adding or removing a passkey requires your current password and, when enabled, a TOTP code. Removing a passkey prevents future sign-in with it; use **Revoke all sessions** to invalidate existing account sessions.

### Reset a password with MailHog

1. Choose **I forgot the password** on the sign-in page.
2. Submit the account email address.
3. Open http://localhost:8025 and select the captured reset email.
4. Follow the reset link and choose a new password.

Reset links expire after 15 minutes and can be used once. A successful reset revokes existing access and refresh tokens while preserving TOTP and passkeys.

MailHog captures messages locally and does not deliver them to real inboxes. No external email account is required for the default setup.

## Run locally for development

Install JDK 21, Maven 3.9 or later, and Node.js 22.12 or later with npm. The commands below use separate terminals, each starting from the project root.

If the full container stack is running, stop it with `docker compose down` before using the MailHog-only setup.

Start MailHog:

```sh
docker compose up -d mailhog
```

For Podman, use `podman compose up -d mailhog`.

Start the backend in another terminal:

```sh
cd backend
mvn spring-boot:run
```

Start the frontend in another terminal:

```sh
cd frontend
npm ci
npm run dev
```

Open http://localhost:5173. The backend listens on port `8080`; Vite forwards `/api/` requests to it. The backend's default SMTP connection is `127.0.0.1:1025`, and the MailHog inbox remains at http://localhost:8025.

The root `.env` is read by Compose. Spring Boot does not automatically load it or `backend/.env.example`. For host development, set environment variables in the shell, IDE or service manager. `backend/.env.example` documents those settings, and `backend/configure-mailhog.ps1` provides a PowerShell setup helper.

## Configuration

The defaults support local use without editing configuration values.

| Variable | Compose default | Host development default | Purpose |
| --- | --- | --- | --- |
| `APP_BIND` | `127.0.0.1` | Not used | Host interface for the containerized frontend. |
| `APP_PORT` | `8088` | Not used | Host port for the containerized frontend. |
| `FRONTEND_URL` | `http://localhost:8088` | `http://localhost:5173` | Application URL used in password-reset emails. |
| `WEBAUTHN_RP_ID` | `localhost` | `localhost` | Passkey relying-party domain, without a scheme, path or port. |
| `WEBAUTHN_RP_NAME` | `OSHC SmartGuide` | `OSHC SmartGuide` | Application name displayed during passkey setup. |
| `WEBAUTHN_ORIGINS` | `http://localhost:8088` | `http://localhost:5173` | Allowed browser origins, including scheme and port when applicable. |
| `COOKIE_SECURE` | `false` | `false` | Set to `true` when serving the application over HTTPS. |
| `COOKIE_SAME_SITE` | `Lax` | `Lax` | SameSite policy for authentication cookies. |
| `ACCESS_TOKEN_MINUTES` | `15` | `15` | Access-token lifetime. |
| `REFRESH_TOKEN_DAYS` | `14` | `14` | Refresh-token lifetime. |
| `MAIL_HOST` | `mailhog` | `127.0.0.1` | SMTP server hostname. |
| `MAIL_PORT` | `1025` | `1025` | SMTP server port. |
| `MAIL_FROM` | `oshc-smartguide@localhost` | `oshc-smartguide@localhost` | Sender address for password-reset emails. |
| `API_PROXY_TARGET` | Not used | `http://localhost:8080` | Backend target for the Vite development server. |

If you change `APP_PORT`, update `FRONTEND_URL` and `WEBAUTHN_ORIGINS` to match the address used in the browser. `MAILHOG_UI_PORT` and `MAILHOG_SMTP_PORT` control published host ports; they do not change the internal MailHog ports. A host-based backend must use the updated SMTP host port if you change it.

For real email delivery, configure `MAIL_HOST`, `MAIL_PORT`, `MAIL_FROM`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_SMTP_AUTH`, `MAIL_SMTP_STARTTLS`, `MAIL_SMTP_STARTTLS_REQUIRED` and `MAIL_SMTP_SSL_TRUST` as required by the SMTP provider. Keep credentials out of source control.

### HTTPS deployment

Passkeys require a secure browser context. Use `http://localhost` for local development and a trusted HTTPS hostname for remote access. Plain HTTP on a LAN IP is not a supported passkey setup.

For a host-based HTTPS reverse proxy forwarding to `http://127.0.0.1:8088`, use settings such as:

```dotenv
APP_BIND=127.0.0.1
APP_PORT=8088
FRONTEND_URL=https://oshc.example.com
WEBAUTHN_RP_ID=oshc.example.com
WEBAUTHN_ORIGINS=https://oshc.example.com
COOKIE_SECURE=true
```

Configure the reverse proxy and TLS certificate separately, then recreate the services. The origin must match the browser address exactly, without a path or trailing slash. Keep MailHog bound to localhost and restrict access to its inbox, which contains password-reset links.

## Data storage

Container deployments store the H2 database in the `oshc_data` named volume, mounted at `/app/data`. Host development stores it under `backend/data` when the backend is started from the `backend` directory. The database is initialized automatically on first startup.

Normal stops and rebuilds preserve the container database. `docker compose down -v` or `podman compose down -v` deletes the volume and its accounts, passkeys and learning progress. Keep the Compose project name consistent to reuse the same volume, and back up the database with the backend stopped before an upgrade.

MailHog messages are held in memory and disappear when its container restarts. The default H2 database and temporary challenge sessions are intended for one backend instance.

## Build and test

Build and test the backend from the project root:

```sh
cd backend
mvn verify
```

Build and test the frontend in a separate terminal from the project root:

```sh
cd frontend
npm ci
npm test
npm run build
```

After building the backend, run browser tests from `frontend`:

```sh
npx playwright install chromium
npm run test:e2e
```

The browser suite starts its own backend on `18080` with an in-memory database and a frontend on `5173`. Stop any development frontend using port `5173` before running it, and keep both ports free. Live MailHog test configuration and recorded validation results are documented in [docs/TEST_REPORT.md](docs/TEST_REPORT.md).

## Troubleshooting

| Problem | What to check |
| --- | --- |
| Application does not load | Inspect `docker compose ps` and `docker compose logs -f backend`. Allow the backend to finish starting and check for host port conflicts. |
| `podman compose` cannot start | Install or configure a Compose provider, or invoke `podman-compose` directly from the project root. |
| Passkey option is unavailable | Use a WebAuthn-capable browser on `localhost` or a trusted HTTPS domain. |
| Passkey domain or origin error | Match the browser address, `WEBAUTHN_RP_ID` and `WEBAUTHN_ORIGINS`. Avoid switching between `localhost`, `127.0.0.1` and a LAN IP. |
| Registration or password login asks for a code | Enter the current six-digit code from the TOTP authenticator app used during registration. |
| Password-reset email is missing | Inspect MailHog and backend logs. Use `mailhog:1025` from the containerized backend and `127.0.0.1:1025` from the host with default ports. |
| Reset link opens the wrong address | Correct `FRONTEND_URL`, restart the backend and request a new reset email. |
| Accounts seem missing after a container update | Check that the Compose project name and database volume are the same as before. |
| Maven fails during dependency prefetch | Keep the supplied `backend/pom.xml` and `backend/Containerfile` together, verify repository access, and rebuild the backend without the image cache. |

To rebuild the backend without cached image layers:

```sh
docker compose build --no-cache backend
docker compose up -d
```

Use the corresponding `podman compose` commands for Podman. The POM pins Maven Dependency Plugin `3.9.0`, Jackson `2.19.2`, Guava `33.7.1-jre` and CBOR `4.5.6` for dependency resolution. Database deletion is not required for a dependency rebuild.

## Project layout and further documentation

| Path | Contents |
| --- | --- |
| `compose.yaml` | Single Compose definition for the frontend, backend and MailHog. |
| `.env.example` | Example configuration for Compose. |
| `backend/` | Spring Boot application, Maven build, tests and backend container definition. |
| `frontend/` | React application, frontend tests, NGINX configuration and Capacitor Android sources. |
| `mailhog/` | PowerShell helpers for starting and checking MailHog. |
| `apache/` | Example Apache hosting and HTTPS configuration. |
| `docs/` | Deployment, passkey, dependency and validation documentation. |

- [Container deployment](docs/CONTAINERS.md)
- [Passkey configuration and API details](docs/PASSKEY_SETUP.md)
- [Dependency configuration and rebuild guidance](docs/DEPENDENCY_FIX.md)
- [Test report and platform limitations](docs/TEST_REPORT.md)
