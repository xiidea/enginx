# Getting started

Requirements for a development stack, and how to bring one up.

## Requirements

- JDK 25 (the Gradle toolchain requests it; the wrapper needs nothing else installed)
- Docker with Compose v2
- Go 1.24+ only if you want to build the agent outside Docker

## Running the stack

```bash
# 1. Development PKI for management-to-agent mTLS. Prints the agent's certificate fingerprint,
#    which is what you register an NGINX instance with.
./docker/pki/generate-dev-certs.sh

# 2. Everything
docker compose -f docker/docker-compose.yml up -d --build
```

| Service | URL | Notes |
|---|---|---|
| Admin console | http://localhost:4200 | sign in with the bootstrap account below |
| Management API | http://localhost:8080 | |
| OpenAPI UI | http://localhost:8080/swagger-ui.html | |
| NGINX (proxied traffic) | http://localhost:8090 | |
| Agent (mTLS) | https://localhost:8443 | client certificate required |
| PostgreSQL | localhost:5432 | `enginx` / `enginx` |

The development stack uses **local accounts** and starts no identity provider. Adding one is an
opt-in profile — see [Signing in with an identity provider](#signing-in-with-an-identity-provider).

### Signing in

The first start creates one administrator from configuration and flags it as needing a new
password. The stack's default is `admin` / `change-me-on-first-login`.

That flag is enforced, not suggested: until the password is replaced, the account's token is
refused on every endpoint except its own password change. The console sends you straight to the
form.

The bootstrap account is created **only when the user table is empty**, so it will not reappear
after you delete it and it will not resurrect one you disabled. Once you have a real
administrator, drop `AUTH_BOOTSTRAP_USERNAME` and `AUTH_BOOTSTRAP_PASSWORD` from the environment —
a password in configuration has been readable by everything that can read configuration.

Create the rest of your accounts under **Local users** in the console, or over the API.

### Getting a token

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"change-me-on-first-login"}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["accessToken"])')

curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/proxy-sites
```

`POST /api/v1/auth/login` and `GET /api/v1/auth/methods` are the only unauthenticated endpoints.
Login is rate limited as a sensitive operation, so its openness is not a brute-force surface.

### Signing in with an identity provider

Keycloak runs under the `oidc` Compose profile, and is not started without it:

```bash
AUTH_OIDC_ENABLED=true docker compose -f docker/docker-compose.yml --profile oidc up -d --build
```

Keycloak is then at http://localhost:8081 (`admin` / `admin`), and four users exist in the imported
`enginx` realm. Passwords are development-only.

| Username | Password | Realm role | Can |
|---|---|---|---|
| `admin` | `admin123` | `SUPER_ADMIN` | everything, including registering NGINX instances |
| `manager` | `manager123` | `ADMIN` | create, update, delete and clone sites |
| `operator` | `operator123` | `OPERATOR` | enable, disable, renew |
| `viewer` | `viewer123` | `READ_ONLY` | read only |

Both providers can be on at once — the console offers whichever the server reports. Their subjects
never collide, so a permission grant belongs to exactly one of them.

For a realm token, use the password grant:

```bash
TOKEN=$(curl -s -X POST \
  http://localhost:8081/realms/enginx/protocol/openid-connect/token \
  -d grant_type=password -d client_id=enginx-frontend \
  -d username=admin -d password=admin123 | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')
```

Password grant is enabled on `enginx-frontend` for local development and integration tests only.
Turn it off in any deployed realm; the browser flow is authorization code with PKCE.

## Running the app outside Docker

Postgres still comes from Compose:

```bash
docker compose -f docker/docker-compose.yml up -d postgres
cd proxy-management && ./gradlew :management-boot:bootRun
```

Add `keycloak` to that first command, and `--profile oidc`, if you want the identity provider too.
