# Configuration

Every setting the platform reads, and the schema behind it.

## Configuration

| Variable | Default | Notes |
|---|---|---|
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | local Postgres | |
| `AUTH_OIDC_ENABLED` | `true` | Trust an identity provider |
| `AUTH_LOCAL_ENABLED` | `false` | Let the platform authenticate accounts itself |
| `AUTH_JWT_SECRET` | unset | Signs local tokens. Required when local is on; at least 32 bytes |
| `AUTH_TOKEN_TTL` | `8h` | How long a local token lasts |
| `AUTH_BOOTSTRAP_USERNAME` / `AUTH_BOOTSTRAP_PASSWORD` | unset | The first administrator, created only into an empty user table |
| `OIDC_ISSUER_URI` | `http://localhost:8081/realms/enginx` | Must match the issuer **inside** the token |
| `OIDC_JWK_SET_URI` | unset | Where this service fetches signing keys, when that differs from the issuer |
| `OIDC_CLIENT_ID` | `enginx-api` | Required audience, for local tokens as well |
| `OIDC_ROLE_CLIENT_ID` | `enginx-frontend` | The console's client. Its client roles count alongside realm roles; other clients' roles are ignored |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:4200` | |
| `GATE_RELAY_URL` | unset | Where hosts' gates ask for a sign-in code to be mailed: this API as the NGINX hosts reach it, ending in `/api/v1/agents/gate/otp-mail`. Required for [email sign-in](protected-sites.md#email-sign-in) |
| `GATE_MAIL_FROM` | `NOTIFICATION_FROM` | Sender of sign-in codes |
| `GATE_OIDC_ISSUER_URI` | unset | The identity provider sites sign visitors in at, for [identity provider sign-in](protected-sites.md#identity-provider-sign-in). Must match the `iss` of its tokens |
| `GATE_OIDC_BACKCHANNEL_URI` | unset | The provider as the NGINX hosts reach it, when that differs from the issuer (the same split as `OIDC_JWK_SET_URI`) |
| `GATE_OIDC_CLIENT_ID` | unset | The client the gates sign in with. A public client with PKCE is recommended |
| `GATE_OIDC_CLIENT_SECRET` | unset | Only for a confidential client. Copied to every host with an OIDC site, so prefer a public one |
| `GATE_OIDC_SCOPES` | `openid email profile` | Must include `openid` |
| `SPRING_MAIL_HOST` / `SPRING_MAIL_PORT` / `SPRING_MAIL_USERNAME` / `SPRING_MAIL_PASSWORD` | unset | SMTP, for notifications and sign-in codes alike |

### Choosing an authentication method

The two are independent, and at least one must be on — the application refuses to start with
neither, rather than coming up healthy with nobody able to sign in.

| | `AUTH_OIDC_ENABLED` | `AUTH_LOCAL_ENABLED` |
|---|---|---|
| An organisation that already runs an identity provider | `true` | `false` |
| A small deployment that would rather not run one | `false` | `true` |
| Migrating between the two, or keeping a break-glass account | `true` | `true` |

With both on, the token's issuer decides which verifier runs. Everything after that — roles,
groups, per-domain grants — is identical, because a local token carries the same claims an OIDC
token does.

Local subjects are `local:<uuid>` and OIDC subjects are bare UUIDs, so the two sets cannot
intersect and a permission grant always belongs to exactly one of them.

`AUTH_JWT_SECRET` must be the same across replicas: it both signs and verifies, so a replica with
a different value rejects tokens its neighbour issued. Rotating it invalidates every local session
at once, which is also how you revoke them all.

### Why the issuer and the JWKS URI are separate

They are the same URL only when every party reaches Keycloak by one name. In Docker they diverge:
a browser gets its token from `http://localhost:8081`, so that is the issuer stamped into the
token, but the management container must reach Keycloak as `http://keycloak:8081` on the compose
network. OIDC discovery refuses to start when the issuer it reads differs from the location it
asked, which is correct — the two really are different URLs.

Setting `OIDC_JWK_SET_URI` splits the concerns: keys are fetched over the container network,
while the issuer inside every token is still validated against `OIDC_ISSUER_URI`. Nothing is
loosened; the alternative fixes are editing `/etc/hosts` on every developer machine, or disabling
issuer validation, which would be a real weakening.

Leave `OIDC_JWK_SET_URI` unset when running outside Docker: ordinary discovery works there.

## Database schema

Liquibase owns the schema. Two baseline changelogs, and one per release that changed it since:

```
db/changelog/changes/001-baseline.sql        the platform's own schema
db/changelog/changes/002-quartz.sql          Quartz's vendor DDL, copied verbatim
db/changelog/changes/003-site-protection.sql 0.2.0: site protection, bundle file modes, sealed bundle files
```

`003` cannot seal the bundle files already there, since the key is not the database's. The application
seals them when it starts, logs how many, and then has PostgreSQL validate the constraint that holds
every row to it. A database user that does not own the table cannot validate it; the constraint
still binds every new and changed row, and the warning says so.

The incremental changelogs that built this up were squashed before the first production
deployment — no deployed database's history was worth preserving. Each squash was verified by
applying both the old chain and the new baseline to empty databases and diffing a semantic snapshot
of each (columns, constraints, indexes, triggers, functions); they match, apart from the transition
columns the squash deliberately dropped.

Hibernate runs with `ddl-auto: validate`, so any drift between a changelog and an entity mapping is
a startup failure rather than a runtime surprise.

**If you have a database from before the squash — including one created by v0.0.8 or v0.0.9 — delete it:**

```bash
cd docker && docker compose down -v && docker compose up -d
```

Its `databasechangelog` names changesets that no longer exist. Testcontainers builds a fresh schema
per run, so tests are unaffected.

This is the last time. From v0.1.0 the schema only moves forward through new changelogs, and an
upgrade between releases keeps its data.

## Key management

Certificate private keys and the ACME account key are envelope-encrypted: a per-secret data key
seals the material, and a key-encryption key wraps the data key. Two providers wrap:

| `enginx.crypto.provider` | Key lives | Notes |
|---|---|---|
| `environment` (default) | This process's memory | Fine for development and file-based secrets |
| `vault` | Inside Vault, never exported | A copy of the database plus this config reads nothing |

```yaml
enginx:
  crypto:
    provider: vault
    vault: { address: https://vault.example.com, token: ..., transit-key: enginx }
```

**Changing provider, or rotating a key, is survivable.** Every secret records the key that wrapped
it, and reads are routed to whichever configured provider recognises that id. So:

1. Configure the new provider, keep the old one, restart. Old secrets stay readable.
2. `POST /api/v1/certificates/rewrap-secrets` — moves every secret to the current key. Resumable,
   audited, and reports what it could not read rather than failing the whole run.
3. Remove the old provider once it reports nothing left to do.

Without step 2 the old key can never be retired, which is the state the platform was previously in
despite the schema being designed for exactly this.
