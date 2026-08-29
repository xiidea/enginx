# Easy NGINX Admin

An enterprise control plane for NGINX reverse proxies: expirable proxy sites, domain-scoped
permissions, validated configuration deployment and automated certificate lifecycle, across many
NGINX hosts, without ever opening an SSH session.

The management server never runs a command on a host. Every change reaches a host as an
authenticated call to a small agent, over mutual TLS with a pinned certificate — and no
configuration is ever activated before `nginx -t` has accepted it.

---

## Documentation

**Running the platform**

| | |
|---|---|
| [Getting started](docs/admin/getting-started.md) | Requirements, and a development stack in two commands |
| [Configuration](docs/admin/configuration.md) | Every setting, the database schema, key management |
| [Managing proxy sites](docs/admin/managing-sites.md) | Creating and deploying sites, expiry, verification, drift |
| [Certificates](docs/admin/certificates.md) | ACME, renewal, wildcards and DNS-01, agent certificates |
| [Access control](docs/admin/access-control.md) | The permission model, and granting without over-granting |
| [Observability](docs/admin/observability.md) | Audit trail, metrics, health, notifications, rate limiting |
| [The admin console](docs/admin/console.md) | The web interface |
| [Running in production](docs/admin/production.md) | Deployment, Kubernetes, monitoring, runbooks |
| [Security posture](docs/admin/security.md) | The properties the platform holds, and why |

**Understanding and extending it**

| | |
|---|---|
| [Architecture](docs/architecture.md) | The design: components, data model, permission model, agent protocol |
| [The API](docs/api/README.md) | REST conventions, the OpenAPI contract, the generated client |
| [Contributing](docs/contributing.md) | Repository layout, building, testing |
| [Roadmap](docs/roadmap.md) | What is planned, what is still open, and what was deliberately left out |
| [Implementation notes](docs/implementation-notes.md) | What changed during construction, and why |
| [Releases](docs/releases.md) | Cutting a release, and what it publishes |

---

## Quick start

```bash
# Development PKI for management-to-agent mTLS. Prints the agent's certificate fingerprint,
# which is what you register an NGINX instance with.
./docker/pki/generate-dev-certs.sh

docker compose -f docker/docker-compose.yml up -d --build
```

The console is at `http://localhost:4200` and the API at `http://localhost:8080`. Sign in as
`admin` / `change-me-on-first-login`, which the first start creates and requires you to replace.

No identity provider is needed: the platform can authenticate accounts itself. To federate against
Keycloak instead, add `--profile oidc`. [Getting started](docs/admin/getting-started.md) covers
both, the first proxy site, and what to do when something does not come up.

---

## What it is

```
docs/architecture.md     the design this implementation follows
docker/                  development stack: Postgres, NGINX + agent, console, dev PKI, optional Keycloak
deploy/kubernetes/       manifests for the management plane and agents
proxy-management/        Spring Boot management server (Gradle multi-module)
enginx-agent/            Go node agent, co-located with NGINX
frontend/                Angular admin console
```

Java 25 · Spring Boot 4 · PostgreSQL 17 · Go 1.26 · Angular 21 · optionally any OIDC provider.

---

## Status

Phases 1–14 are complete; the roadmap is finished. Two decisions remain open and are worth making
deliberately — whether a site may target more than one NGINX host, and what `forceHttps=false`
should mean for port 80. Both are written up in
[docs/roadmap.md](docs/roadmap.md#open-decisions).

| Phase | Scope | State |
|---|---|---|
| 1 | Architecture, schema, permission model, agent protocol | **Complete** |
| 2 | Spring Boot skeleton, Go agent, PostgreSQL, Liquibase, Compose, Keycloak, proxy site CRUD | **Complete** |
| 3 | Domain groups, domain-level permissions, authorization service, Testcontainers | **Complete** |
| 4 | Configuration rendering, agent deployment workflow, deployment history | **Complete** |
| 5 | Expiration scheduler, Quartz clustering, lifecycle jobs | **Complete** |
| 6 | Certificate abstraction, ACME, renewal, expiry monitoring | **Complete** |
| 7 | Angular admin console | **Complete** |
| 8 | Audit API and console page, metrics, health, rate limiting, production Docker | **Complete** |
| 9 | Deployment verification, drift detection, upstream probes, HTTPS catch-all, CI | **Complete** |
| 10 | Notifications: expiry warnings, renewal failures, unreachable hosts, abandoned work | **Complete** |
| 11 | Grant reach preview, namespace-authority tests, audit retention | **Complete** |
| 12 | DNS-01 and wildcard certificates, Vault-backed key wrapping, agent certificate rotation | **Complete** |
| 13 | Generated API client, Kubernetes manifests, tagged releases with multi-arch agent binaries | **Complete** |
| 14 | Local accounts with optional OIDC, bootstrap administrator, console sign-in | **Complete** |
