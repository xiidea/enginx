# Self-managed setup: management + console against your own Keycloak and database

This guide deploys **only the management API and the console**, and connects them to a
**PostgreSQL and a Keycloak you already run** — a managed database, an existing identity provider,
or instances you stand up separately. NGINX hosts are managed by an **agent installed as a plain
binary**, no Docker on the node.

It is the deployment the bundled [`production.md`](production.md) is not: that one ships Postgres
and Keycloak inside the same Compose file. Use this guide when those live elsewhere.

Two agent transports are covered separately and in full — **push mode** (§7) and **pull mode**
(§8). You can run a mix across the estate.

Companion references, not repeated here in full:

- [`agent.md`](agent.md) — the complete agent variable reference and behaviour.
- [`configuration.md`](configuration.md) — every management environment variable.
- [`access-control.md`](access-control.md) — roles, grants, and local accounts.
- [`security.md`](security.md) — the trust boundaries and why they are drawn where they are.

---

## Contents

1. [Topology and what you must provide](#1-topology-and-what-you-must-provide)
2. [Prepare PostgreSQL](#2-prepare-postgresql)
3. [Prepare Keycloak](#3-prepare-keycloak)
4. [Generate secrets](#4-generate-secrets)
5. [Generate the mTLS PKI](#5-generate-the-mtls-pki-push-mode-only)
6. [Deploy management + console](#6-deploy-management--console) — [Docker Compose](#6a-docker-compose) · [Kubernetes](#6b-kubernetes)
7. [Agents — push mode (dial / mTLS)](#7-agents--push-mode-dial--mtls)
8. [Agents — pull mode (token)](#8-agents--pull-mode-token)
9. [Multiple NGINX nodes](#9-multiple-nginx-nodes)
10. [Verify end to end](#10-verify-end-to-end)

---

## 1. Topology and what you must provide

```
                        browser (operator)
                              │  OIDC login + Bearer JWT
                              ▼
                    ┌───────────────────┐        you run these two:
                    │   edge / ingress  │  TLS   ┌─────────────────────┐
                    │  (you terminate)  │──────► │ console  (static)   │
                    └───────────────────┘        │ proxy-management API│
                              │                   └─────────┬───────────┘
             ┌────────────────┼─────────────────┐          │
             ▼                ▼                  ▼          │ mTLS (push)  or
     ┌──────────────┐  ┌──────────────┐   push │ pull      │ agent dials in (pull)
     │  PostgreSQL  │  │   Keycloak   │        ▼           ▼
     │  (external)  │  │  (external)  │   ┌─────────┐  ┌─────────┐   NGINX hosts,
     └──────────────┘  └──────────────┘   │ agent   │  │ agent   │   agent = bare binary
                                          │ node-01 │  │ node-02 │   (no Docker)
                                          └─────────┘  └─────────┘
```

You provide:

| Dependency | Requirement |
|---|---|
| **PostgreSQL** | Version 17. An empty database the platform owns (Liquibase creates every table). §2. |
| **Keycloak** | A realm with two clients, four roles, and one audience mapper. §3. Or skip it and use local accounts — see the note in §3. |
| **TLS edge** | A reverse proxy or ingress that terminates HTTPS in front of the console and API. Not covered here; the platform expects `X-Forwarded-*` from it (§6). |
| **DNS** | A public name for the console/API, and (push mode) a name or route from the management plane to each agent's `:8443`. |
| **NGINX hosts** | One or more Linux hosts running NGINX, each with an agent binary. §7 / §8. |

The management plane keeps **no local state** — everything is in Postgres — so it can run one
replica or several behind a load balancer with no further coordination.

---

## 2. Prepare PostgreSQL

The platform owns its database outright: Hibernate runs with `ddl-auto: validate` and **Liquibase
creates and migrates every table** at startup. You create an empty database and a role; you never
run DDL by hand.

```sql
CREATE ROLE enginx LOGIN PASSWORD 'use-a-generated-secret';
CREATE DATABASE enginx OWNER enginx;
```

Notes:

- **Version 17.** The schema depends on declarative partitioning, partial indexes, and
  `FOR UPDATE SKIP LOCKED`; it is PostgreSQL-only by design.
- **Keycloak may share the server** in its own separate database (`CREATE DATABASE keycloak OWNER
  enginx;`), or use an entirely different one. They never share a database.
- The connection is set with `DB_URL`, `DB_USERNAME`, and a **password supplied as a secret file**,
  not an environment variable (§4, §6). Pool size is `DB_POOL_SIZE` (default 20 in the samples).
- A managed Postgres (RDS, Cloud SQL, etc.) works unchanged — point `DB_URL` at it and make sure
  the management plane's network can reach it.

---

## 3. Prepare Keycloak

The console signs people in against Keycloak; the API validates the resulting JWT for **issuer,
expiry, and audience**. Configure the realm to match what the API expects.

> **Prefer not to run Keycloak at all?** Set `AUTH_OIDC_ENABLED=false` and `AUTH_LOCAL_ENABLED=true`
> and the platform authenticates people itself with local accounts — no identity provider needed.
> See [`access-control.md`](access-control.md). The rest of this section then does not apply; skip
> to §4 and set `AUTH_JWT_SECRET` and the bootstrap account instead.

In the realm you run:

**1. Realm** — name it `enginx` (any name works, as long as the issuer URLs in §6 match it).

**2. Realm roles** — create all four. They set the global floor and ceiling for access:

```
SUPER_ADMIN   ADMIN   OPERATOR   READ_ONLY
```

Assign `SUPER_ADMIN` to your first operator so there is someone who can register hosts and author
grants. `SUPER_ADMIN` cannot be self-granted through the API, which is why it is seeded here.

**3. Public SPA client — `enginx-frontend`** (the console logs in through this):

- Client type **public**, **Standard flow** on, **Direct access grants** on.
- **Valid redirect URIs**: `https://nginx.example.com/*` (your console's public URL).
- **Web origins**: `https://nginx.example.com`.
- Two protocol mappers:
  - **Audience** mapper adding `enginx-api` to the token audience — *without this the API rejects
    every token*, because the audience check is what stops a token minted for another client being
    replayed here.
  - **Group membership** mapper named `groups` (full path on), so scoped grants by group work.

**4. API client — `enginx-api`** (the audience, not a login):

- Client type **confidential** or bearer-only; **no** standard flow, **no** direct access, **no**
  service accounts. It exists only to be named as the audience above.

**5. (Optional) Prometheus scraper** — a separate client with **service accounts** enabled and the
`SUPER_ADMIN` role, if you will scrape `/actuator/prometheus` with a client-credentials token. See
[`observability.md`](observability.md).

The values the management plane and console need to point back here (§6):

| Setting | Value |
|---|---|
| `OIDC_ISSUER_URI` (API) | the **public** realm URL a browser sees, e.g. `https://id.example.com/realms/enginx` |
| `OIDC_JWK_SET_URI` (API) | how the API reaches Keycloak **internally**, e.g. `http://keycloak.internal:8080/realms/enginx/protocol/openid-connect/certs` |
| `OIDC_CLIENT_ID` (API) | `enginx-api` — the expected **audience** |
| `OIDC_AUTHORITY` (console) | the public realm URL, same as the issuer |
| console client id | `enginx-frontend` |

The issuer and the JWKS URI **differ on purpose**: the issuer must be the name the browser used
(it is baked into the token), while the JWKS URI is the path the server uses to fetch signing keys
and may be an internal address.

---

## 4. Generate secrets

Create these once, on a trusted machine. They are supplied to the management plane as **files**
(mounted under `/run/secrets/`), never as plain environment variables.

```bash
mkdir -p secrets && chmod 700 secrets

# Database password (must match the role you created in §2).
printf '%s' 'the-postgres-password' > secrets/db_password

# Encryption key that wraps certificate private keys at rest. 32 bytes, base64.
openssl rand -base64 32 > secrets/crypto_key_k1

# mTLS keystore/truststore passwords (only needed for push mode, §5).
openssl rand -base64 24 > secrets/agent_keystore_password
openssl rand -base64 24 > secrets/agent_truststore_password
```

If you chose **local accounts** instead of Keycloak (§3), also:

```bash
# Signs locally issued tokens. Must be identical across every replica.
openssl rand -base64 48 > secrets/auth_jwt_secret
```

> **Back up `secrets/crypto_key_k1` somewhere your database backup is not.** It is the key that
> makes the encrypted columns readable. A database restored without it is a database of
> ciphertext — every certificate private key and the ACME account key become unrecoverable.

The files are named for the **property each one sets**, because Spring reads `/run/secrets` as a
config tree (`SPRING_CONFIG_IMPORT=optional:configtree:/run/secrets/`). §6 shows how each file name
maps to a property.

---

## 5. Generate the mTLS PKI (push mode only)

Skip this section entirely if every agent will run in **pull mode** (§8) — pull mode uses a token,
not a certificate.

Push mode is mutual TLS: the **management plane presents a client certificate** (CN
`enginx-management`) and the **agent presents a server certificate** whose SHA-256 fingerprint the
platform pins per host. Both are signed by one internal CA that you control.

The development script `docker/pki/generate-dev-certs.sh` shows the exact shape. The script below
is the production adaptation: real passwords from §4, and **one server certificate per NGINX host**,
each with that host's real name(s) in the SAN.

```bash
#!/usr/bin/env bash
set -euo pipefail
mkdir -p pki && cd pki
DAYS=825

# The NGINX hosts, and the name/IP the management plane will dial for each. Put every name or
# address the platform uses in agent_base_url into that host's SAN, or hostname verification fails.
NODES=(
  "node-01:nginx-01.internal.example.com"
  "node-02:nginx-02.internal.example.com,10.0.3.24"
)

KS_PW="$(cat ../secrets/agent_keystore_password)"
TS_PW="$(cat ../secrets/agent_truststore_password)"

# ---- 1. Certificate authority (keep ca.key offline; only ca.crt is distributed) ----
[[ -f ca.crt ]] || openssl req -x509 -newkey rsa:4096 -sha256 -days 3650 -nodes \
  -keyout ca.key -out ca.crt \
  -subj "/CN=Easy NGINX Admin CA/O=example" \
  -addext "basicConstraints=critical,CA:TRUE,pathlen:0" \
  -addext "keyUsage=critical,keyCertSign,cRLSign"

# ---- 2. Management client certificate (CN pinned by every agent) ----
openssl req -newkey rsa:2048 -sha256 -nodes -keyout management.key -out management.csr \
  -subj "/CN=enginx-management/O=example"
openssl x509 -req -in management.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
  -out management.crt -days "$DAYS" -sha256 \
  -extfile <(printf "extendedKeyUsage=clientAuth\nkeyUsage=critical,digitalSignature\n")

# The JVM cannot load a bare PEM pair: bundle the client identity as PKCS#12, and the CA as a
# truststore. These two files are what the management plane mounts.
openssl pkcs12 -export -out management.p12 -inkey management.key -in management.crt \
  -certfile ca.crt -name enginx-management -passout "pass:${KS_PW}"
keytool -importcert -noprompt -alias enginx-ca -file ca.crt \
  -keystore truststore.p12 -storetype PKCS12 -storepass "${TS_PW}"

# ---- 3. One server certificate per NGINX host ----
for entry in "${NODES[@]}"; do
  id="${entry%%:*}"; hosts="${entry#*:}"
  san=""; IFS=',' read -ra hs <<< "$hosts"
  for h in "${hs[@]}"; do
    [[ "$h" =~ ^[0-9.]+$ ]] && san+="IP:$h," || san+="DNS:$h,"
  done; san="${san%,}"

  openssl req -newkey rsa:2048 -sha256 -nodes -keyout "agent-$id.key" -out "agent-$id.csr" \
    -subj "/CN=enginx-agent-$id/O=example"
  openssl x509 -req -in "agent-$id.csr" -CA ca.crt -CAkey ca.key -CAcreateserial \
    -out "agent-$id.crt" -days "$DAYS" -sha256 \
    -extfile <(printf "subjectAltName=%s\nextendedKeyUsage=serverAuth\nkeyUsage=critical,digitalSignature,keyEncipherment\n" "$san")

  fp="$(openssl x509 -in "agent-$id.crt" -noout -fingerprint -sha256 | cut -d= -f2 | tr -d ':')"
  printf 'agent-%s  SAN=%s\n  fingerprint: %s\n' "$id" "$san" "$fp"
done

rm -f ./*.csr ca.srl
chmod 600 ./*.key ./*.p12
```

The script prints **each host's SHA-256 fingerprint** — you register the instance with it in §7.
What travels where:

| File | Goes to | Why |
|---|---|---|
| `management.p12`, `truststore.p12` | the **management plane** (mounted `/etc/enginx/pki`) | its client identity + the CA it trusts |
| `agent-<id>.crt`, `agent-<id>.key` | **that host only** | the host's own identity — never shared between hosts |
| `ca.crt` | **every host** | so the agent can verify the management client certificate |
| `ca.key` | **nowhere** — keep it offline | it can mint new identities for either side |

> Certificates expire (825 days above). Rotating one means re-registering the host with the new
> fingerprint — see "Rotating an agent certificate" in [`production.md`](production.md). If you run
> an internal CA already, issue from it instead of the self-signed root here; only the `clientAuth`
> / `serverAuth` usages and the `enginx-management` CN matter.

---

## 6. Deploy management + console

Both deployments read secrets as a **config tree**: a file at `/run/secrets/<property.path>` sets
that Spring property. This is why no password is ever an environment variable. The mapping:

| Secret file (§4) | Property it sets |
|---|---|
| `spring.datasource.password` | the database password |
| `enginx.crypto.keys.k1` | the encryption key (`CRYPTO_ACTIVE_KEY_ID=k1`) |
| `enginx.agent.key-store-password` | the mTLS keystore password (push) |
| `enginx.agent.trust-store-password` | the mTLS truststore password (push) |
| `enginx.auth.jwt-secret` | local-account signing key (only if not using Keycloak) |

Full variable reference: [`configuration.md`](configuration.md).

### 6A. Docker Compose

Only two services are the platform (`proxy-management`, `console`); the third is your TLS edge.
Postgres and Keycloak are **not** here — they are the external hosts from §2 and §3.

`docker-compose.selfmanaged.yml`:

```yaml
name: enginx

services:
  proxy-management:
    image: ghcr.io/xiidea/enginx-management:latest
    restart: unless-stopped
    environment:
      # --- external database (§2) ---
      DB_URL: jdbc:postgresql://db.internal.example.com:5432/enginx
      DB_USERNAME: enginx
      DB_POOL_SIZE: "20"

      # --- external Keycloak (§3) ---
      OIDC_ISSUER_URI: https://id.example.com/realms/enginx
      OIDC_JWK_SET_URI: https://id.example.com/realms/enginx/protocol/openid-connect/certs
      OIDC_CLIENT_ID: enginx-api            # the audience
      CORS_ALLOWED_ORIGINS: https://nginx.example.com

      # Secrets as files, not values (see the table above).
      SPRING_CONFIG_IMPORT: "optional:configtree:/run/secrets/"
      CRYPTO_ACTIVE_KEY_ID: k1

      # Which upstream may set X-Forwarded-* — your edge's address/subnet, and nothing wider.
      # Left unset it trusts loopback only, so the real client IP is lost behind a proxy.
      ENGINX_TRUSTED_PROXIES: "10\\.0\\.0\\.\\d+"

      # Push-mode client identity. Omit both if every agent is pull mode.
      AGENT_KEYSTORE: /etc/enginx/pki/management.p12
      AGENT_TRUSTSTORE: /etc/enginx/pki/truststore.p12

      # Production certificate issuance. Prove config against staging first.
      ACME_DIRECTORY_URL: acme://letsencrypt.org
      ACME_CONTACT_EMAIL: platform@example.com
      ACME_ACCEPT_TOS: "true"
    secrets:
      - spring.datasource.password
      - enginx.crypto.keys.k1
      - enginx.agent.key-store-password
      - enginx.agent.trust-store-password
    volumes:
      - ./pki:/etc/enginx/pki:ro          # management.p12 + truststore.p12 from §5
    read_only: true
    tmpfs: [ "/tmp:size=128m,mode=1777" ]
    cap_drop: [ALL]
    security_opt: ["no-new-privileges:true"]
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:8080/actuator/health/readiness"]
      interval: 15s
      timeout: 5s
      retries: 10
      start_period: 60s
    networks: [enginx]

  console:
    image: ghcr.io/xiidea/enginx-console:latest
    restart: unless-stopped
    environment:
      API_BASE: https://nginx.example.com/api/v1/
      OIDC_AUTHORITY: https://id.example.com/realms/enginx
      OIDC_CLIENT_ID: enginx-frontend       # the public SPA client, not enginx-api
    depends_on: [proxy-management]
    cap_drop: [ALL]
    security_opt: ["no-new-privileges:true"]
    networks: [enginx]

  # Your TLS edge. Terminates HTTPS, routes /api/v1 → proxy-management:8080 and everything else →
  # console:80, and sets X-Forwarded-* so the API recovers the true client address. Any reverse
  # proxy does this; the platform only requires that ENGINX_TRUSTED_PROXIES above matches it.
  edge:
    image: nginx:1.29-alpine
    restart: unless-stopped
    ports: ["443:443", "80:80"]
    volumes:
      - ./edge/nginx.conf:/etc/nginx/nginx.conf:ro
      - ./edge/tls:/etc/nginx/tls:ro
    depends_on: [console, proxy-management]
    networks: [enginx]

secrets:
  spring.datasource.password: { file: ./secrets/db_password }
  enginx.crypto.keys.k1:      { file: ./secrets/crypto_key_k1 }
  enginx.agent.key-store-password:   { file: ./secrets/agent_keystore_password }
  enginx.agent.trust-store-password: { file: ./secrets/agent_truststore_password }

networks:
  enginx: { driver: bridge }
```

Bring it up **management first, then console** — the console shows an error page against an API
that is not yet answering, and a rolling upgrade in that order never serves a console newer than
its API:

```bash
docker compose -f docker-compose.selfmanaged.yml up -d proxy-management
docker compose -f docker-compose.selfmanaged.yml logs -f proxy-management   # watch Liquibase run
docker compose -f docker-compose.selfmanaged.yml up -d console edge
```

Confirm readiness (covers the database and the app's own state, not the fleet):

```bash
docker compose -f docker-compose.selfmanaged.yml exec proxy-management \
  wget -qO- http://localhost:8080/actuator/health/readiness   # {"status":"UP"}
```

### 6B. Kubernetes

The manifests under [`deploy/kubernetes/`](../../deploy/kubernetes/) already assume **external**
Postgres and Keycloak — they define no database or identity provider, only pointing `DB_URL` and
the issuer URLs at hosts you provide. Use them as-is and change four things:

1. **`10-config.yaml`** — set `DB_URL`, `OIDC_ISSUER_URI`, `OIDC_JWK_SET_URI`,
   `CORS_ALLOWED_ORIGINS`, and the ACME fields to your external services. Set `CRYPTO_PROVIDER`
   (`environment` with a mounted key, or `vault` with `VAULT_ADDR`).
2. **`enginx-secrets` Secret** — replace every `change-me` with the §4 values. Keys are named for
   the property they set, exactly like the Compose config tree.
3. **`enginx-agent-pki` Secret** (push mode) — create it from §5's output, mounted at
   `/etc/enginx/pki` by `20-management.yaml`:
   ```bash
   kubectl -n enginx create secret generic enginx-agent-pki \
     --from-file=management.p12=pki/management.p12 \
     --from-file=truststore.p12=pki/truststore.p12
   ```
4. **Ingress** (`40-console-and-ingress.yaml`) — set your host and TLS, routing `/api/v1` to the
   `proxy-management` Service and everything else to `console`.

```bash
kubectl apply -f deploy/kubernetes/00-namespace.yaml
kubectl apply -f deploy/kubernetes/10-config.yaml       # edited ConfigMap + Secret
kubectl -n enginx create secret generic enginx-agent-pki --from-file=... # push mode only
kubectl apply -f deploy/kubernetes/20-management.yaml
kubectl apply -f deploy/kubernetes/40-console-and-ingress.yaml
kubectl -n enginx rollout status deploy/proxy-management
```

The management Deployment runs `replicas: 2` safely: Quartz uses a clustered JDBC job store, the
outbox claims work with `FOR UPDATE SKIP LOCKED`, and deployments take a per-instance advisory lock.
The one thing that does not scale by replication is the in-process rate limiter — see §4 of
[`production.md`](production.md).

> The bundled `30-agent-daemonset.yaml` runs agents **as pods**. This guide installs the agent as a
> **binary on the host instead** (§7, §8) — do not apply the DaemonSet if you are doing that.

---

## 7. Agents — push mode (dial / mTLS)

The management plane dials each host on `:8443` over mutual TLS. Do this per NGINX host.

**Install the binary** (same for both modes):

```bash
VERSION=0.0.7
curl -LO "https://github.com/xiidea/enginx/releases/download/v${VERSION}/enginx-agent-linux-amd64"
curl -LO "https://github.com/xiidea/enginx/releases/download/v${VERSION}/SHA256SUMS.txt"
sha256sum --ignore-missing -c SHA256SUMS.txt          # verify before trusting it
install -m 0755 enginx-agent-linux-amd64 /usr/local/bin/enginx-agent
enginx-agent -version
```

Verifying the checksum is not optional: this binary is handed every site's private key.

**Place this host's PKI** — its own certificate from §5, plus the CA:

```bash
install -d -m 0755 /etc/enginx/pki
install -m 0644 agent-node-01.crt /etc/enginx/pki/agent.crt
install -m 0600 agent-node-01.key /etc/enginx/pki/agent.key
install -m 0644 ca.crt            /etc/enginx/pki/ca.crt
```

**Configure it** — `/etc/enginx/agent.env`, mode `0600`:

```bash
AGENT_LISTEN_ADDR=:8443
AGENT_TLS_CERT=/etc/enginx/pki/agent.crt
AGENT_TLS_KEY=/etc/enginx/pki/agent.key
AGENT_CLIENT_CA=/etc/enginx/pki/ca.crt
AGENT_CLIENT_CN=enginx-management
AGENT_RELEASES_DIR=/etc/nginx/enginx
AGENT_NGINX_BINARY=/usr/sbin/nginx
AGENT_NGINX_CONF=/etc/nginx/nginx.conf
```

**Run it under systemd** — `/etc/systemd/system/enginx-agent.service`:

```ini
[Unit]
Description=Easy NGINX Admin agent (push mode)
After=network-online.target nginx.service
Wants=network-online.target

[Service]
# Root, only to signal the NGINX master and write the release tree. It runs no shell and accepts
# no command, so its reach ends there.
User=root
EnvironmentFile=/etc/enginx/agent.env
ExecStart=/usr/local/bin/enginx-agent
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

```bash
systemctl daemon-reload && systemctl enable --now enginx-agent
```

**Open `:8443` to the management plane only** — at the host firewall and any security group.
Certificate pinning is the authentication; the port has no reason to be reachable from anywhere
else.

**Register the host** with the platform, giving it the URL and this certificate's fingerprint
(printed by §5, or recomputed on the host):

```bash
openssl x509 -in /etc/enginx/pki/agent.crt -noout -fingerprint -sha256 | sed 's/.*=//' | tr -d ':'
```

Then either the console (**NGINX instances → Add**, a *dial-mode* host) or the API:

```bash
curl -sS -X POST https://nginx.example.com/api/v1/nginx-instances \
  -H "Authorization: Bearer $ADMIN_JWT" \
  -H 'Content-Type: application/json' \
  -d '{
        "name": "nginx-node-01",
        "hostname": "nginx-01.internal.example.com",
        "agentBaseUrl": "https://nginx-01.internal.example.com:8443",
        "agentCertFingerprint": "AB12…the 64-hex fingerprint…",
        "environment": "PRODUCTION"
      }'
```

The platform pins that fingerprint: a replacement certificate — even one signed by the same CA — is
not trusted until someone re-registers the host. Within a heartbeat the instance turns **ONLINE**.

### Alternative: push mode with a pre-shared token

If the agent is reached through an ingress or layer-7 proxy that terminates TLS, and so cannot be
offered a client certificate, set `AGENT_PUSH_PROTOCOL=http` and `AGENT_SECRET_TOKEN` (generate it
with `openssl rand -hex 32`; at least 32 characters) in `/etc/enginx/agent.env`. Keep the port
behind TLS — the proxy's, or the agent's own via `AGENT_TLS_CERT`/`AGENT_TLS_KEY` — because the
token and every bundle, private keys included, are readable on the wire without it. Then register
with `pushTransport`:

```bash
curl -sS -X POST https://nginx.example.com/api/v1/nginx-instances \
  -H "Authorization: Bearer $ADMIN_JWT" \
  -H 'Content-Type: application/json' \
  -d '{
        "name": "nginx-node-01",
        "hostname": "nginx-01.internal.example.com",
        "agentBaseUrl": "https://nginx-01.internal.example.com:8080",
        "pushTransport": "HTTP_TOKEN",
        "agentAuthToken": "<the AGENT_SECRET_TOKEN value>",
        "environment": "PRODUCTION"
      }'
```

---

## 8. Agents — pull mode (token)

The host enrols itself and long-polls for work over HTTPS. **Nothing connects to it** — no inbound
port, no certificate. This is the mode for hosts behind NAT, in another cloud, or on a network the
management plane cannot route to.

**Install the binary** exactly as in §7 (download, checksum, `install`).

**Mint a registration token** — the console's **NGINX instances** page shows it once with the exact
command, or the API:

```bash
curl -sS -X POST https://nginx.example.com/api/v1/agent-registration-tokens \
  -H "Authorization: Bearer $ADMIN_JWT" \
  -H 'Content-Type: application/json' \
  -d '{ "description": "edge fleet", "expiresAt": "2026-12-31T23:59:59Z", "maxUses": 10 }'
# → { "registrationToken": { ... }, "token": "enginx-reg-…" }   the token is shown once
```

`expiresAt` (an absolute ISO-8601 instant) and `maxUses` are both optional — omit them for a token
that never expires and can be used without limit. The secret comes back in the `token` field.

One token can enrol many hosts (`maxUses`) or exactly one — your call.

**Configure it** — `/etc/enginx/agent.env`, mode `0600`:

```bash
ENGINX_SERVER_URL=https://nginx.example.com/api/v1
ENGINX_REGISTRATION_TOKEN=enginx-reg-…
ENGINX_INSTANCE_NAME=nginx-node-01
ENGINX_ENVIRONMENT=PRODUCTION
ENGINX_TOKEN_FILE=/var/lib/enginx/agent-token
AGENT_RELEASES_DIR=/etc/nginx/enginx
AGENT_NGINX_BINARY=/usr/sbin/nginx
AGENT_NGINX_CONF=/etc/nginx/nginx.conf
```

Setting `ENGINX_SERVER_URL` is the **only** thing that selects pull mode — there is no flag to get
wrong. Provision `/var/lib/enginx` on durable storage:

```bash
install -d -m 0700 /var/lib/enginx
```

**Run it under systemd** — `/etc/systemd/system/enginx-agent.service`:

```ini
[Unit]
Description=Easy NGINX Admin agent (pull mode)
After=network-online.target nginx.service
Wants=network-online.target

[Service]
User=root
EnvironmentFile=/etc/enginx/agent.env
ExecStart=/usr/local/bin/enginx-agent
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

```bash
systemctl daemon-reload && systemctl enable --now enginx-agent
```

On first start the agent spends the registration token, receives an **agent token written `0600`
to `ENGINX_TOKEN_FILE`**, and reuses it on every restart. Two rules that save an on-call visit:

- **Keep `ENGINX_TOKEN_FILE` on durable storage.** Lose it and the agent re-enrols on each boot; a
  single-use token would be spent by a reboot.
- **Leave the registration token in `agent.env`.** It is not used again while the stored token
  works, but if the platform ever rejects the stored one the agent re-enrols itself exactly once
  and recovers. (See the recovery caveat in [`agent.md`](agent.md).)

One thing pull mode cannot do yet: **answer HTTP-01 ACME challenges.** Certificates for a pull host
must come from DNS-01 or be uploaded. Push hosts have no such limit.

---

## 9. Multiple NGINX nodes

Each NGINX host is **one agent** and appears as **one instance**. To manage a fleet, repeat §7 or §8
per host — the modes coexist, so some hosts can dial in while others pull.

- **Naming.** Give each instance a stable, greppable name (`nginx-edge-01`, `nginx-eu-02`). It is
  how the host shows in the console, in the audit trail, and in metrics tags.
- **Push mode: one certificate per host.** Never share `agent.key` between hosts — the fingerprint
  pin is per instance, and a shared key means a shared identity. §5 issues them in a loop; add
  hosts to the `NODES` array and re-run to mint more without touching existing ones.
- **Pull mode: one token can seed many hosts.** Mint a token with `maxUses` matching the batch,
  set a distinct `ENGINX_INSTANCE_NAME` per host, and each enrols to its own instance and its own
  issued agent token.
- **Placing sites.** A proxy site targets one instance. Spread load by pointing different sites at
  different hosts, or run the same site's config on several and balance in front — the platform
  deploys each instance independently and reports drift per host.
- **A host that goes quiet** is marked offline after `AGENT_SILENCE_THRESHOLD` (default 5m). Keep
  the agent's heartbeat/check-in well under it (pull default `ENGINX_HEARTBEAT_INTERVAL=60s`).

Scaling out the management plane itself (more replicas) is orthogonal and covered in §7 of
[`production.md`](production.md).

---

## 10. Verify end to end

1. **Management is healthy.**
   `GET /actuator/health/readiness` → `{"status":"UP"}`. The `platform` health group
   (`/actuator/health/platform`, `SUPER_ADMIN`) shows the fleet, outbox, and certificate view.
2. **You can sign in.** Open the console URL, complete the Keycloak login, and land on the
   dashboard. (Local-accounts mode: sign in with the bootstrap account and change its password.)
3. **A host is ONLINE.** Register/enrol at least one node (§7 or §8); within a heartbeat it turns
   ONLINE on the **NGINX instances** page.
4. **A deployment reaches it.** Create a throwaway proxy site targeting that host and deploy it.
   Push mode applies synchronously; pull mode enqueues a job the host collects on its next poll.
   Confirm the site goes **ACTIVE** and NGINX on the host is serving it, then delete the test site.

If a deployment fails or a host is unreachable, the diagnosis flow is in §5 of
[`production.md`](production.md) — "When something goes wrong".
