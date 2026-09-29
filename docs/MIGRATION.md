# Rebase and migration notes

## Source provenance

New base: [Aauli1979/OSHC_Medibank_MailHogMFA](https://github.com/Aauli1979/OSHC_Medibank_MailHogMFA)

Base commit: `473c75bcded6e59f9ffe2ebd04045459cf4d0cf4`

Input: `OSHC-Medibank_Tokenised-passkey-podman-compose.zip`, whose README identifies
its previous base as `Aauli1979/OSHC-Medibank_Tokenised` commit
`f8be0fe60431f247b87aec73634e97bfab531d78`.

The new project's source is the baseline. Its frontend, learning features, profile
model, TOTP registration, MailHog password recovery and token-cookie authentication
are retained. The uploaded Passkey implementation and container support have been
adapted to this baseline.

## Integration decisions

| Area | Result |
| --- | --- |
| Authentication | Both Passkey and password/TOTP sign-in issue `SG_ACCESS` and `SG_REFRESH` cookies. No `AUTH` session fallback is used. |
| Registration | New accounts must finish `/api/auth/register/mfa/verify`; incomplete registration can resume from password sign-in. |
| Token lifecycle | Refresh tokens rotate with a database lock. Logout, password reset and revoke-all invalidate the relevant stored token hashes. |
| Challenge state | Login transitions replace the CSRF/MFA/WebAuthn session and clear stale challenges. Sessions do not grant protected API access. |
| CSRF and CORS | All mutations require a session-bound CSRF header. CORS follows the configured WebAuthn origin list and includes PATCH. |
| Frontend | New-base pages retained; capability-only Passkey placeholder replaced with registration, listing, rename and removal. |
| Refresh client | Uses HTTP status rather than matching an error string; shares concurrent refreshes and retries once. |
| MailHog | Default local mail transport; password-reset messages use the configured public frontend URL. Failed delivery removes the reset token. |
| Profile changes | Email changes retain learning progress and invalidate old email reset links. Account deletion removes Passkey credentials. |
| MFA replacement | The existing authenticator stays active until the replacement has been verified through `/api/profile/mfa/enable/verify`. |
| Containers | Three services in root `compose.yaml`. The two upstream MailHog Compose files are removed; the helper points to the root file. |
| Language | All supplied source, configuration, comments, scripts and documentation are English. |

The old `TokenizationService` was not transplanted: the MailHog MFA baseline has a
different user/profile storage model and its own opaque-token authentication.

## Updating an existing MailHog MFA deployment

1. Stop the existing backend and back up its full `backend/data` directory or volume.
2. Keep the same H2 data directory and preserve the deployment hostname/RP settings.
3. Build the new backend and frontend, or rebuild the Compose services.
4. The default `spring.jpa.hibernate.ddl-auto=update` adds the nullable
   `app_users.passkey_user_handle` column and `passkey_credentials` table.
5. For controlled H2 schema updates, the optional manual script is
   `backend/migrations/001_passkeys_h2.sql`. Back up first. The application does not
   include Flyway, so this script is not executed automatically. After applying
   it to a database that already has the MailHog MFA schema, you can use
   `SPRING_JPA_HIBERNATE_DDL_AUTO=validate`.
6. Confirm password/TOTP login, reset email capture and existing learning progress.
7. Add a Passkey, sign out, then verify Passkey sign-in and token refresh/revocation.

The schema script adds only Passkey storage. It does not create or convert the
MailHog MFA base's existing user, auth-token, password-reset or progress tables.

## Moving from the old Tokenised project

This is a source-code rebase, not an automatic conversion of a deployed Tokenised
database. The original Tokenised project can store profile and MFA values through
an encrypted token vault. The MailHog MFA model expects its own field values.
Copying that old H2 database into this edition is not a supported data migration.

For a clean installation, start with an empty database and register accounts again.
For an existing installation, keep a backup of the old database and its original
`TOKENIZATION_KEY`; a separate export/conversion must use the old application's
reader to decode values and map them into the new model. This archive does not
contain user data or that key, and does not claim to perform that conversion.
Existing Passkeys can be portable only if their credentials, stable user handles,
account mappings and RP ID are preserved by such a migration. Otherwise re-enroll
them. Changing the RP ID also requires planning for Passkey re-enrollment.

## Excluded generated or private content

The upstream repository includes dependencies, compiled classes, a sample H2
file and a Windows MailHog binary. These are omitted from this source archive,
along with build caches and repository history. Use `npm ci`, Maven and the
container image to obtain the required runtime components.
