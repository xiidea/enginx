# Running in production

Deploying the platform, watching it, and recovering it. A development stack is covered in
[getting-started.md](getting-started.md); this page is about a real one.

## Deploying


```bash
cd docker
./generate-secrets.sh
docker compose -f docker-compose.prod.yml up -d       # management plane
docker compose -f docker-compose.agent.yml up -d      # on each NGINX host
```

`docker-compose.prod.yml` publishes exactly one service, the edge proxy. PostgreSQL, the management
API and any identity provider's management port are reachable only on the internal network. Secrets are
mounted as files and read as a Spring config tree, never passed as environment variables — those
are visible in `docker inspect` and in `/proc`.

Full instructions, monitoring setup, alert rules and recovery runbooks are in
the runbooks below.

## Kubernetes


Manifests are in [`deploy/kubernetes/`](../../deploy/kubernetes), validated against the Kubernetes 1.31
schemas in CI:

```bash
kubectl apply -f deploy/kubernetes/
```

The management plane is a two-replica Deployment — safe because Quartz is clustered, the outbox
claims with `SKIP LOCKED`, and deployments take a per-instance advisory lock. Secrets are mounted
as files and read through Spring's config tree, exactly as in Compose, so nothing changes between
the two. NetworkPolicies are included because which component may talk to which is most of this
platform's security story, and a cluster that permits everything discards it.

Agents are a DaemonSet, but only for clusters that also serve the proxied traffic, and only on
nodes labelled `enginx.net/proxy=true`. Most estates will not use it: the agent's usual home is a
virtual machine outside any cluster, and a host does not have to be a pod.

---

## 1. Topology

Two things are deployed, and they are deployed differently.

**The management plane** — API, console, PostgreSQL, and an identity provider if you use one —
runs once, wherever you like. It
holds the desired state and is the only thing operators talk to. It is not in the request path of
any proxied site: if it is down, every site keeps serving, and only changes stop.

**The agent** runs on every host that serves proxied traffic, in the same container as the NGINX it
manages. It reaches the platform one of two ways, chosen per host:

- **Push** — the management plane dials the agent on 8443 and pins its certificate. Stronger
  authentication of the host, and it needs a route to it.
- **Pull** — the agent enrols with a registration token and calls the platform. No inbound
  connectivity at all, so a host behind NAT or in another network can be managed. See
  [Enrolling a host that calls in](#enrolling-a-host-that-calls-in). It has to be the same container because the agent signals the NGINX process to reload, and
a process in another container cannot be signalled.

```
        operators                          proxied traffic
            │                                     │
            ▼                                     ▼
    ┌───────────────┐   mTLS, pinned   ┌────────────────────────┐
    │ management    │─────────────────▶│ nginx host 1  (agent)  │
    │ plane         │─────────────────▶│ nginx host 2  (agent)  │
    │ (this repo)   │                  │ …                      │
    └───────┬───────┘                  └────────────────────────┘
            │
      ┌─────┴─────┐
      │ postgres  │   desired state, audit trail, encrypted secrets
      └───────────┘
```

The management server never opens an SSH session to a host. Every change reaches a host as an
authenticated call to its agent, over mutual TLS, with the agent's certificate fingerprint pinned
at registration time.

---

## 2. First deployment

### 2.1 Secrets

```bash
cd docker
./generate-secrets.sh
```

This writes five files into `docker/secrets/`, each `0600`, and refuses to overwrite anything that
already exists. They are mounted into containers at `/run/secrets/` and read by Spring as a config
tree — never passed as environment variables, which are visible in `docker inspect`, in `/proc`,
and in the output of any tool that dumps its environment on error.

**Back up `secrets/crypto_key_k1` somewhere your database backup is not.** It wraps every
certificate private key and the ACME account key. A database restored without it is a database of
ciphertext, and you find that out at the next renewal rather than at the restore.

### 2.2 PKI for agent communication

```bash
./pki/generate-dev-certs.sh          # development only
```

For production, issue from your own CA. Three things are needed:

| File | Held by | Purpose |
|---|---|---|
| `ca.crt` | both | Signs both ends. The agent trusts only clients it signed |
| `management.p12` | management plane | Client identity, CN `enginx-management` |
| `agent.crt` / `agent.key` | each NGINX host | That host's server identity |

Each agent gets its **own** certificate. Registering the host records the SHA-256 fingerprint of
that certificate, and the management server pins it: a replacement certificate is not trusted until
someone re-registers it. This is deliberate — it means an attacker who obtains a certificate signed
by the same CA still cannot impersonate a host.

### 2.3 Upgrade order

**Upgrade agents before the management server.** Bundles rendered from Phase 9 onward reference
`/etc/nginx/enginx/default-tls/`, which only a Phase 9 agent creates. Deploying such a bundle to an
older agent fails validation citing a certificate nobody configured. On a first install the order
does not matter.

### 2.4 Bring it up

```bash
export KEYCLOAK_PUBLIC_URL=https://nginx.example.com
export CONSOLE_PUBLIC_URL=https://nginx.example.com
export API_PUBLIC_URL=https://nginx.example.com/api/v1
export ACME_CONTACT_EMAIL=platform@example.com
export ACME_ACCEPT_TOS=true

docker compose -f docker-compose.prod.yml up -d
```

Then on each NGINX host:

```bash
docker compose -f docker-compose.agent.yml up -d
```

`docker-compose.prod.yml` publishes exactly one service — the edge proxy on 80 and 443. PostgreSQL,
the management API and any identity provider's management port are reachable only on the internal
network. A database port bound to `0.0.0.0` is the most common way a stack like this is lost.

### 2.5 Before going live

- [ ] `ACME_DIRECTORY_URL` still points at staging while you prove the configuration. Switch to
      production only once issuance works — Let's Encrypt counts certificates per registered domain
      per week, and exceeding it is a lockout measured in days, not a slowdown.
- [ ] `springdoc.api-docs.enabled=false` and `springdoc.swagger-ui.enabled=false` if you do not
      want the schema public.
- [ ] The realm's users, roles and client secrets are yours, not the development ones — or, with
      local accounts, `AUTH_BOOTSTRAP_PASSWORD` has been used once and removed from the
      environment, and `AUTH_JWT_SECRET` is a value nothing else has ever held.
- [ ] `secrets/crypto_key_k1` is backed up separately from the database.
- [ ] A restore has been tested. An untested backup is a hypothesis.

---

## 3. Watching it

### 3.1 Health

`/actuator/health` is public. Everything else under `/actuator` requires the `SUPER_ADMIN` realm
role.

| Endpoint | Answers |
|---|---|
| `/actuator/health/liveness` | Is the process alive? Restart if not |
| `/actuator/health/readiness` | Can this instance serve? Database plus application state |
| `/actuator/health/platform` | Is the *estate* well? Agents, outbox, certificates |

The split matters. Readiness deliberately excludes the estate indicators, because those report on
things the management plane observes rather than things it suffers. An unreachable agent must not
cause an orchestrator to restart or de-register the one service capable of fixing it.

For the same reason the estate indicators report a custom `WARNING` status, which maps to HTTP 200.
They are visible on the endpoint and alertable through metrics, and they never take the platform out
of rotation.

| Indicator | Warns when |
|---|---|
| `agents` | An instance is silent for `enginx.observability.agent-silence` (5m), or is DEGRADED |
| `outbox` | More than `outbox-warn-depth` (25) messages undispatched, or any dead |
| `certificates` | Nearest expiry within `certificate-warn-days` (7), or any expired or failed |

### 3.2 Metrics

`/actuator/prometheus`, behind the same `SUPER_ADMIN` requirement. Scrape it with a client
credentials token from a Keycloak service account holding that role — do not open the endpoint.

```yaml
scrape_configs:
  - job_name: enginx
    metrics_path: /actuator/prometheus
    oauth2:
      client_id: prometheus-scraper
      client_secret_file: /etc/prometheus/enginx-secret
      token_url: https://nginx.example.com/realms/enginx/protocol/openid-connect/token
    static_configs:
      - targets: ['proxy-management:8080']
```

The gauges are chosen for what you would page on, not for what is easy to count:

| Metric | Meaning |
|---|---|
| `enginx_sites{status}` | Sites by lifecycle status |
| `enginx_certificates{status}` | Certificates by status |
| `enginx_certificates_nearest_expiry_days` | Days to the soonest expiry; `-1` when none installed |
| `enginx_deployments_pending` | Queued or in progress |
| `enginx_deployments_failed` | Ended in failure |
| `enginx_outbox_depth` | Committed changes not yet delivered |
| `enginx_outbox_dead` | Gave up retrying; needs a person |
| `enginx_instances_unreachable` | Agents not heard from recently |
| `enginx_instances_degraded` | Answering, but not serving — or serving a configuration nobody deployed |
| `enginx_notifications_failed` | Claimed but reached no channel. Nothing retries these |

Label sets are closed — one series per enum constant, fixed at startup. Nothing is tagged by site or
certificate id, because that grows without bound and is how a cardinality explosion starts.

**Alerts worth having:**

```yaml
- alert: EnginxCertificateExpiringSoon
  expr: enginx_certificates_nearest_expiry_days >= 0 and enginx_certificates_nearest_expiry_days < 7
  for: 1h
  annotations: { summary: "A certificate expires within a week and renewal has not completed" }

- alert: EnginxOutboxStuck
  expr: enginx_outbox_depth > 25
  for: 15m
  annotations: { summary: "Committed changes are not reaching hosts" }

- alert: EnginxOutboxDead
  expr: enginx_outbox_dead > 0
  annotations: { summary: "A change gave up retrying and will not be delivered without help" }

- alert: EnginxAgentUnreachable
  expr: enginx_instances_unreachable > 0
  for: 10m
  annotations: { summary: "An NGINX host is not answering; it keeps serving, but cannot be changed" }

- alert: EnginxNotificationsNotReaching
  expr: enginx_notifications_failed > 0
  annotations: { summary: "A notification was claimed but delivered nowhere; nothing will retry it" }

- alert: EnginxHostDrifted
  expr: enginx_instances_degraded > 0
  for: 5m
  annotations: { summary: "A host is serving something the platform did not deploy, or is not serving at all" }
```

`enginx_outbox_depth` is the one to watch most closely. Every API call that changes anything
returns 202 the moment the intent is committed; the outbox is where that intent waits to become a
real change on a host. A depth that keeps climbing is invisible from the API — every one of those
requests succeeded.

### 3.3 The audit trail

`GET /api/v1/audit-logs`, `SUPER_ADMIN` only, since the trail spans every domain in the estate.
Also in the console under **Audit log**.

There is no write endpoint and no delete, and this is not a convention: the table has a trigger that
raises on UPDATE and DELETE, so the API could not offer one. The application's database role can
insert and select, nothing more.

The table is range-partitioned by month. A job creates partitions two months ahead daily. **Always
pass a time range** where you can — a bounded `from`/`to` lets PostgreSQL skip whole partitions
instead of scanning the history.

```bash
# Everything one person did last week
curl -H "Authorization: Bearer $TOKEN" \
  "$API/audit-logs?actor=alice&from=2026-08-20T00:00:00Z&to=2026-08-27T00:00:00Z"

# Everything that was refused
curl -H "Authorization: Bearer $TOKEN" "$API/audit-logs?result=DENIED"

# One site's whole history
curl -H "Authorization: Bearer $TOKEN" \
  "$API/audit-logs?resourceType=PROXY_SITE&resourceId=$SITE_ID"
```

Retention is off by default, because silently destroying an audit trail for want of a configured
property is a failure discovered during an investigation. Set a window and the daily maintenance
job applies it:

```yaml
enginx:
  audit:
    retention-months: 24     # 0, the default, keeps everything
```

Whole partitions are dropped, which is instant and never touches a row — a `DELETE` here is both
slow and refused outright by the immutability trigger. The default partition is never dropped, and
the deletion is itself written to the trail, so a gap is never ambiguous between policy and someone
with database access.

**Archive first if you need to.** There is no built-in archive step; dump the partitions you want
to keep before the window passes.

```sql
-- what exists, and what the next run would remove
select c.relname, pg_get_expr(c.relpartbound, c.oid)
  from pg_class c join pg_inherits i on i.inhrelid = c.oid
  join pg_class p on p.oid = i.inhparent
 where p.relname = 'audit_logs' order by 1;
```

---

## 4. Rate limiting

Two layers, doing different jobs.

**In the application**, per authenticated subject, keyed on the token's `sub` so that refreshing a
token does not hand out a fresh allowance. Three tiers:

| Tier | Default | Covers |
|---|---|---|
| `READ` | 300/min | Every safe method |
| `WRITE` | 60/min | Ordinary changes |
| `SENSITIVE` | 10/min | Certificates, deployments, permissions, instance registration |

The sensitive tier exists because those requests do damage that outlives the request: they burn a
certificate authority's weekly quota, reload NGINX on live hosts, or change who can do either.
Exceeding a tier returns 429 with an RFC 9457 body, `Retry-After`, and `RateLimit-*` headers.

This limiter is **per instance**. Behind two replicas a caller gets up to twice the configured
allowance. That is a deliberate trade — the alternative is a round trip to a shared store on every
request, adding a hop and a dependency in front of the whole API to make an approximate limit exact.
The limit exists to stop abuse and runaway clients, and it does that at 2x as well as at 1x.

**At the edge**, per address, in `docker/edge/nginx.conf`. This catches what the application limiter
structurally cannot: a flood arriving with no valid token at all, which would otherwise reach the JWT
decoder. It is also where an exact estate-wide limit belongs, if you need one.

---

## 5. When something goes wrong

### A deployment failed

Nothing is broken. Validation happens before activation, and if `nginx -t` fails the agent never
reloads; if a reload fails, the agent restores the previous release itself. The host is still
serving whatever it was serving.

```bash
curl -H "Authorization: Bearer $TOKEN" "$API/deployments/$ID"
```

The `events` array shows how far it got — RENDER, UPLOAD, VALIDATE, ACTIVATE, RELOAD — and
`nginxTestOutput` carries `nginx -t` verbatim. Fix the configuration and deploy again. Deployments
are idempotent: re-deploying an unchanged bundle is a no-op on the host.

### The outbox is backing up

Almost always one unreachable agent.

```bash
curl -H "Authorization: Bearer $TOKEN" "$API/nginx-instances"       # which is not ONLINE?
docker compose -f docker-compose.agent.yml logs nginx --tail 100    # on that host
```

Messages retry with backoff. After exhausting attempts they become `DEAD` and stop, which is why
`enginx_outbox_dead > 0` is an alert with no `for:` clause — it will not clear itself.

### Nobody is receiving notifications

Notifications are claimed in the ledger before they are sent, so one that failed to deliver has
already been marked handled and will not be tried again. The ledger is where to look:

```sql
select kind, threshold, status, recipients, detail, created_at
  from notification_ledger order by created_at desc limit 20;
```

`detail` names each channel and what it did. Two common findings: `No recipients are configured`
means `enginx.notifications.operator-addresses` is empty, and `mail=failed` means SMTP is rejecting
the message.

To re-send something after fixing the cause, delete its ledger row — the next scan will find the
condition again and claim it afresh.

```sql
delete from notification_ledger where kind = 'SITE_EXPIRING' and resource_id = '<site id>';
```

### A host has drifted

The instance is `DEGRADED` and `enginx_instances_degraded` is above zero. The host is answering, but
what it serves is not what the platform put there — someone edited it by hand, it was rebuilt, or it
was restored from a snapshot.

```bash
curl -H "Authorization: Bearer $TOKEN" \
  "$API/audit-logs?action=NGINX_INSTANCE_DRIFTED"     # names both bundles
```

Nothing has been reverted, deliberately: the cause is often a person working on an incident. Find
out what changed before overwriting it.

When you do want the platform's configuration back, redeploy the instance. While it is `DEGRADED`
the dispatcher skips its "nothing changed" shortcut, so this genuinely re-sends and re-activates
rather than reporting a no-op:

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" "$API/nginx-instances/$ID/deploy"
```

The next heartbeat returns the instance to `ONLINE`.

### A deployment succeeded but VERIFY failed

The configuration is loaded and being served — VERIFY does not change that, and nothing was rolled
back. What failed is the loopback probe: the host did not answer for that name.

Usual causes, in the order worth checking: the upstream is down (use **Check upstreams** on the
site, or `GET $API/proxy-sites/{id}/upstream-check`); a `server_name` collision with another site;
or the site is HTTPS-only, in which case port 80 is answered by the catch-all with a 404 — see the
open decision in the roadmap.

A VERIFY phase marked SKIPPED means the agent could not be probed at all. That is not a site
outage; check the agent.

### An agent is unreachable but the site is up

Expected, and worth understanding: the agent is the control path, not the data path. NGINX keeps
serving the configuration it has. You have lost the ability to *change* that host, not the traffic.

Check in order: the container is running; port 8443 is reachable from the management plane; the
agent's certificate has not been replaced without re-registering it (fingerprint pinning will refuse
a new one).

### Certificate renewal is failing

```bash
curl -H "Authorization: Bearer $TOKEN" "$API/certificates/$ID"   # lastError
curl -H "Authorization: Bearer $TOKEN" "$API/audit-logs?resourceType=CERTIFICATE&resourceId=$ID"
```

HTTP-01 needs the authority to reach `http://<domain>/.well-known/acme-challenge/` on the host
serving that domain. The generated configuration always includes that location, so the usual causes
are outside this platform: DNS pointing elsewhere, or something in front blocking port 80.

If you have hit a rate limit, stop. Retrying is what turns an hour's wait into a week's.

### Restoring from backup

1. Restore the database.
2. Restore `secrets/crypto_key_k1` — the same key, not a new one. Without it every stored
   private key and the ACME account key are unreadable.
3. Start the management plane. Liquibase brings the schema forward; Quartz recovers its triggers.
4. Deploy each instance once. The database is the desired state, and this makes the hosts match it.

Agents need nothing restored. They keep serving through all of the above.

---

## 6. Configuration reference

Everything below is an environment variable on the management container. Defaults are in
`application.yml`; the ones with no default must be set.

| Variable | Default | Notes |
|---|---|---|
| `DB_URL`, `DB_USERNAME` | localhost | Password comes from the secret, not from here |
| `DB_POOL_SIZE` | 10 | 20 is reasonable in production |
| `AUTH_OIDC_ENABLED` | `true` | Trust an identity provider |
| `AUTH_LOCAL_ENABLED` | `false` | Authenticate accounts held by the platform. At least one of the two must be on |
| `AUTH_JWT_SECRET` | — | Signs local tokens. At least 32 bytes, identical across replicas, from a file rather than here |
| `AUTH_TOKEN_TTL` | `8h` | Local token lifetime. Self-contained, so this is also the revocation delay |
| `AUTH_BOOTSTRAP_USERNAME`, `AUTH_BOOTSTRAP_PASSWORD` | — | The first administrator. Remove both once it exists |
| `OIDC_ISSUER_URI` | — | The **public** provider URL. Must match what browsers used |
| `OIDC_JWK_SET_URI` | — | How this container reaches the provider internally. Differs on purpose |
| `CORS_ALLOWED_ORIGINS` | localhost:4200 | The console's public origin |
| `CRYPTO_ACTIVE_KEY_ID` | `dev` | Which key wraps *new* secrets. Old ones record their own |
| `ACME_DIRECTORY_URL` | LE staging | Switch to production deliberately |
| `ACME_ACCEPT_TOS` | `false` | Must be set; agreeing on your behalf is not ours to do |
| `LIFECYCLE_INTERVAL_SECONDS` | 60 | Also the precision the API promises for an expiry |
| `OUTBOX_INTERVAL_SECONDS` | 5 | |
| `CERTIFICATE_INTERVAL_SECONDS` | 3600 | Renewal windows are days; eagerness only costs quota |
| `HEARTBEAT_INTERVAL_SECONDS` | 60 | Keep well under `AGENT_SILENCE_THRESHOLD`. Also the drift-detection interval |
| `NOTIFICATION_INTERVAL_SECONDS` | 3600 | Thresholds are in days; the ledger makes repeats harmless |
| `NOTIFICATION_OPERATOR_ADDRESSES` | — | Comma-separated. Without it, estate-wide conditions reach nobody |
| `NOTIFICATION_EXPIRY_THRESHOLDS` | `7,3,1` | Days before expiry at which to warn |
| `NOTIFICATION_WEBHOOK_URL` | — | Generic JSON payload per notification |
| `NOTIFICATION_MIN_SEVERITY` | `INFO` | Floor below which nothing is delivered |
| `AUDIT_RETENTION_MONTHS` | 0 | 0 keeps everything. When set, whole months are dropped daily |
| `CRYPTO_PROVIDER` | `environment` | `vault` wraps data keys in Vault's transit engine |
| `VAULT_ADDR` / `VAULT_TOKEN` | — | Required when the provider is `vault` |
| `VAULT_TRANSIT_KEY` | `enginx` | The transit key name, recorded as each secret's key id |
| `ACME_DNS_CHALLTESTSRV_URL` | — | Development only. Enables DNS-01 against pebble-challtestsrv |
| `ACME_PREFER_DNS01` | false | Use DNS-01 even for non-wildcards |
| `AGENT_SILENCE_THRESHOLD` | 5m | When silence becomes "unreachable" |
| `OUTBOX_WARN_DEPTH` | 25 | |
| `CERTIFICATE_WARN_DAYS` | 7 | |
| `RATE_LIMIT_ENABLED` | true | |
| `RATE_LIMIT_READ` / `_WRITE` / `_SENSITIVE` | 300 / 60 / 10 | Per minute, per subject, per instance |

### Rotating the encryption key, or moving to Vault

Every encrypted row records which key wrapped it, and reads are routed to whichever configured
provider recognises that id — so a rotation or a provider change is gradual rather than a
stop-the-world rewrite.

1. Add the new key or provider alongside the old. Both must stay configured.
2. Point `CRYPTO_ACTIVE_KEY_ID` (or `CRYPTO_PROVIDER`) at the new one and restart. The startup log
   names what writes and what can read: *"Data keys are wrapped by X and can be unwrapped by [X, Y]"*.
3. Re-wrap everything:

   ```bash
   curl -X POST -H "Authorization: Bearer $TOKEN" "$API/certificates/rewrap-secrets"
   # {"examined": 12, "rewrapped": 12, "failed": 0}
   ```

   Resumable and audited. A secret it cannot read is counted in `failed` and skipped, so fix the
   cause and run it again.
4. Remove the old key or provider **only** once a run reports `rewrapped: 0, failed: 0`.

Skipping step 3 leaves the old key required forever by whatever still names it — and it is rarely
what you would guess. The ACME account key is wrapped too, so a certificate-only migration leaves
one row holding the old key hostage until issuance breaks.

```sql
-- what still names which key
select kek_id, count(*) from certificate_secrets group by kek_id
union all select kek_id, count(*) from acme_accounts group by kek_id;
```

### Rotating an agent certificate

The fingerprint is pinned, so a new certificate is not trusted until you say so.

1. Install the new certificate and key on the host, and restart its agent.
2. Trust the new fingerprint:

   ```bash
   openssl x509 -in agent.crt -noout -fingerprint -sha256 | sed 's/.*=//' | tr -d ':'
   curl -X PUT -H "Authorization: Bearer $TOKEN" \
     -d '{"agentCertFingerprint":"<that value>"}' \
     "$API/nginx-instances/$ID/agent-certificate"
   ```

   Confirm which binary is running while you are there — the agent reports its own build:

   ```bash
   docker exec <container> /usr/local/bin/enginx-agent -version
   # enginx-agent 1.2.0 (a1b2c3d) linux/amd64
   ```

The instance goes to UNKNOWN and returns to ONLINE at the next heartbeat, within a minute. Between
the two steps the host is unreachable *for changes* — it keeps serving its traffic throughout.

---

## 7. Scaling out

The management plane runs several replicas without changes. Three things make that safe, and they
were designed in rather than added:

- **Quartz** uses a clustered JDBC job store, so a scheduled job fires on exactly one node.
- **The outbox** claims work with `FOR UPDATE SKIP LOCKED`, so dispatchers take disjoint sets
  instead of duplicating or blocking.
- **Deployments** take a `pg_advisory_xact_lock` per instance, so two nodes cannot render and
  activate configuration for the same host at once.

What does not scale by replication is the rate limiter, which is per instance — see §4.

The Kubernetes shape follows from the same properties: the management plane is a stateless
Deployment with the liveness and readiness probes above; agents are a DaemonSet on the nodes that
serve traffic; secrets become Secrets mounted as files, which is already how the config tree reads
them.

---

## Enrolling a host that calls in

A push host needs an address the management plane can reach and a port open to it. A host behind
NAT, in another cloud, or on a network nobody routes to can offer neither. Pull mode inverts the
connection so that no inbound path is required.

Mint a registration token on the **NGINX instances** page, which shows it once alongside the exact
`docker run` line for the new host. Or over the API:

```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"description":"edge hosts","maxUses":1}' \
  "$API/agent-registration-tokens"
```

The response carries the token in clear. That is the only time it exists anywhere but as a digest;
a lost token is replaced, not recovered.

Then run the agent with it. There is no certificate to generate and no port to open:

```bash
docker run -d --name enginx-agent \
  -e ENGINX_SERVER_URL=https://enginx.example.com/api/v1 \
  -e ENGINX_REGISTRATION_TOKEN=enginx-reg-… \
  -e ENGINX_INSTANCE_NAME=nginx-edge-01 \
  -v /var/lib/enginx:/var/lib/enginx \
  -p 80:80 -p 443:443 \
  xiidea/enginx-agent:latest
```

Only 80 and 443 are published, and both are for the traffic the host serves — nothing is published
for the control plane.

**Bound the token.** `maxUses: 1` is right for one known host. It travels into a manifest, a
provisioning script, a chat message; the useful question is not whether it leaks but how long a
leaked one is worth anything. Revoking it stops further enrolment and leaves hosts it already
enrolled working, because those hold credentials of their own.

**The agent token is written to `/var/lib/enginx/agent-token`, mode 0600.** Keep that path on a
volume: without it every restart enrols again, and a single-use token would be spent by a reboot.
Anything on the host that can read the file can collect every site's private key, since a
configuration bundle contains them — the same exposure a push host's private key already has.

**Revoking a host's own token** stops it collecting work without deleting the instance, so its
deployment history and the sites pointing at it survive. The host re-enrols with a fresh
registration token.

### How work reaches a pull host

The agent long-polls `GET /agents/jobs/request`, and the server answers the moment a job appears —
so a deployment reaches the host in about as long as a request takes rather than waiting out an
interval. A deployment becomes two jobs, staged then activated, with the second queued only after
the first succeeds: a host is never told to activate a bundle it has not stored.

Three properties are worth knowing when reading `agent_jobs`:

- **One job outstanding per host.** Two in flight against one NGINX would be two processes racing
  to swap the same symlink. Enforced by a unique index, not only by the claim query.
- **Jobs are leased, not assigned.** A host holds one for `AGENT_JOB_LEASE` (default 5m); if no
  result arrives the job returns to the queue. An agent that dies mid-deployment strands nothing,
  and replaying is safe because bundles are content-addressed and activation carries the
  deployment's idempotency key.
- **A failure cancels what was queued behind it.** Otherwise the host would collect an activation
  for a deployment that already failed.

**Verification is not available on a pull host yet.** A push deployment ends by asking the host
whether it actually answers for the names just deployed; that is a question rather than queued
work, and it is recorded as skipped rather than quietly omitted. The same applies to upstream
checks and ACME HTTP-01, so **a pull host cannot issue HTTP-01 certificates**.
