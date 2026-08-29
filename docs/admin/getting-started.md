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
| Admin console | http://localhost:4200 | sign in with the users below |
| Management API | http://localhost:8080 | |
| OpenAPI UI | http://localhost:8080/swagger-ui.html | |
| Keycloak | http://localhost:8081 | admin / admin |
| NGINX (proxied traffic) | http://localhost:8090 | |
| Agent (mTLS) | https://localhost:8443 | client certificate required |
| PostgreSQL | localhost:5432 | `enginx` / `enginx` |

### Development users

All four exist in the imported `enginx` realm. Passwords are development-only.

| Username | Password | Realm role | Can |
|---|---|---|---|
| `admin` | `admin123` | `SUPER_ADMIN` | everything, including registering NGINX instances |
| `manager` | `manager123` | `ADMIN` | create, update, delete and clone sites |
| `operator` | `operator123` | `OPERATOR` | enable, disable, renew |
| `viewer` | `viewer123` | `READ_ONLY` | read only |

### Getting a token

```bash
TOKEN=$(curl -s -X POST \
  http://localhost:8081/realms/enginx/protocol/openid-connect/token \
  -d grant_type=password -d client_id=enginx-frontend \
  -d username=admin -d password=admin123 | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')

curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/proxy-sites
```

Password grant is enabled on `enginx-frontend` for local development and integration tests only.
Turn it off in any deployed realm; the browser flow is authorization code with PKCE.

## Running the app outside Docker

Postgres and Keycloak still come from Compose:

```bash
docker compose -f docker/docker-compose.yml up -d postgres keycloak
cd proxy-management && ./gradlew :management-boot:bootRun
```
