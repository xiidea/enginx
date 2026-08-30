# Administrator guide

For whoever runs the platform. Start with [getting-started.md](getting-started.md) for a
development stack, or [production.md](production.md) for a real one.

| Page | What it covers |
|---|---|
| [getting-started.md](getting-started.md) | Requirements, bringing up a development stack, running outside Docker |
| [configuration.md](configuration.md) | Every setting, the database schema, and key management |
| [managing-sites.md](managing-sites.md) | Creating and deploying proxy sites, expiry, verification and drift |
| [agent.md](agent.md) | The node agent: its two connectivity modes, running the binary, every setting |
| [certificates.md](certificates.md) | ACME issuance, renewal, wildcards and DNS-01, agent certificates |
| [access-control.md](access-control.md) | The permission model, and granting access without over-granting |
| [observability.md](observability.md) | Audit trail, metrics, health, notifications, rate limiting |
| [console.md](console.md) | The web interface |
| [production.md](production.md) | Production deployment, Kubernetes, monitoring, runbooks |
| [security.md](security.md) | The security properties the platform holds, and why |

Two things outside this guide are often wanted alongside it: the
[REST API](../api/README.md), and the [architecture](../architecture.md) — which explains why the
platform behaves the way these pages describe.
