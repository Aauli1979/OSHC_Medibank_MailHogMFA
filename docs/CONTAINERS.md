# Container deployment

## One Compose file

The root `compose.yaml` replaces both upstream MailHog Compose files and the old
Passkey archive's two-service Compose definition. All services share the `app`
network. Docker Compose and rootless Podman use the same file.

| Service | Purpose | Default host binding | Persistent data |
| --- | --- | --- | --- |
| `frontend` | NGINX, React SPA and `/api/` reverse proxy | `127.0.0.1:8088` | None |
| `backend` | Spring Boot, TOTP, Passkeys and token authentication | Not published | `/app/data` in `oshc_data` |
| `mailhog` | Local SMTP capture and web inbox | `127.0.0.1:1025`, `127.0.0.1:8025` | In memory; messages disappear on restart |

The frontend and backend images use multi-stage builds. The backend runs as UID/GID
10001; NGINX uses an unprivileged image. All services drop Linux capabilities and
set `no-new-privileges`. No container needs privileged mode or a container-engine
socket. Ports are above 1024 for rootless use.

## Start, inspect and stop

Copy `.env.example` to `.env`, edit values as needed, then run from the project root:

```sh
docker compose config
docker compose up -d --build
docker compose ps
docker compose logs -f backend
```

For Podman, replace `docker compose` with `podman compose`. A Compose provider must
be installed. `podman-compose` can also read the same file. Run rootless Podman as
your normal user.

Open `http://localhost:8088` for the application and `http://localhost:8025` for mail.
The backend may take several seconds to initialize on first startup.

Stop while keeping H2 data:

```sh
docker compose down
```

`docker compose down -v` also deletes the database volume, including accounts,
Passkeys, learning progress and token records. Use that command only for an
intentional full reset.

Rebuild after source changes:

```sh
docker compose up -d --build
```

## Development with only MailHog in a container

```sh
docker compose up -d mailhog
```

Run Spring Boot and Vite on the host using the README instructions. Host applications
use `MAIL_HOST=127.0.0.1` and `MAIL_PORT=1025`; the containerized backend uses
`MAIL_HOST=mailhog` and the container port 1025. Changing `MAILHOG_SMTP_PORT` changes
only the published host port. It does not change the internal SMTP port.

`mailhog/start-mailhog.ps1` resolves the root `compose.yaml` from its own location
and starts only MailHog. It supports `-Engine podman` as well as the default Docker.

## HTTPS and public origins

Example `.env` for an HTTPS reverse proxy on the host:

```dotenv
APP_BIND=127.0.0.1
APP_PORT=8088
FRONTEND_URL=https://oshc.example.com
WEBAUTHN_RP_ID=oshc.example.com
WEBAUTHN_ORIGINS=https://oshc.example.com
COOKIE_SECURE=true
COOKIE_SAME_SITE=Lax
```

Route the HTTPS site to `http://127.0.0.1:8088`. Forward cookies, `Origin` and CSRF
headers. The frontend's NGINX already provides SPA routing and `/api/` proxying.
`apache/oshc-smartguide-https.conf.example` is an alternative example for directly
hosting a built SPA and proxying a host-based backend; adjust its paths and ports
for your deployment. The older HTTP Apache example is not sufficient for Passkeys
outside localhost.

Use a hostname consistently. `WEBAUTHN_RP_ID` is a domain without a scheme, path or
port. `WEBAUTHN_ORIGINS` is the exact browser origin, including a non-default port;
it accepts comma-separated entries under that RP ID, without paths or wildcards.
A LAN IP over plain HTTP is not a supported Passkey deployment.

If the reverse proxy runs in another container, place it on a network that can
reach the frontend, or configure an appropriate host binding. Do not use the
proxy container's own `127.0.0.1` to reach another container.

## Email configuration

The default MailHog settings require no SMTP username or password. Use the MailHog
inbox to open password-reset links. For actual email delivery, set `MAIL_HOST`,
`MAIL_PORT`, `MAIL_FROM` and the authentication/TLS options for your SMTP provider.
A Gmail example remains in `backend/configure-mail.ps1`; set its sender and app
password before use. XAMPP/Mercury examples from the new base are also retained.

Keep `MAILHOG_BIND=127.0.0.1` unless you have explicitly secured access to the inbox.
Captured messages contain password-reset links. MailHog is a development email
sink and its inbox has no application-account access control.

## Data and compatibility

H2 resides at `/app/data/oshc.mv.db`. Keep the Compose project name stable across
updates to reuse its named volume. A changed folder/project name can select a new,
empty volume; it does not migrate old data automatically. Back up the stopped
backend's full data directory or volume before upgrading.

This base uses database-hashed opaque authentication tokens. It does not use the
old Tokenised project's encrypted profile token vault. `TOKENIZATION_KEY` and
`SESSION_COOKIE_SECURE` from the older archive are not configuration variables for
this edition. Use `COOKIE_SECURE`, and read [MIGRATION.md](MIGRATION.md) before moving
a database between the two projects.

The default MailHog image is inherited from the upstream project. If that image
has no native variant for your machine's architecture, use container emulation
or supply a compatible MailHog build. Container execution and image compatibility
must be checked on the target Docker/Podman host.

## Dependency-prefetch troubleshooting

If an older archive fails at `dependency:go-offline` while resolving a Jackson
SNAPSHOT, update both `backend/pom.xml` and `backend/Containerfile` from this
archive. See [DEPENDENCY_FIX.md](DEPENDENCY_FIX.md) for the fix and a cache-free
backend rebuild. Keep the database volume; deleting it does not fix Maven
resolution errors.
