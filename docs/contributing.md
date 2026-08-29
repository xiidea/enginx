# Contributing

How the repository is laid out, and how to build and test it.

## Layout


```
docs/                  architecture, the API contract, the administrator guide, this file
docker/                development stack: Postgres, Keycloak, NGINX + agent, console, dev PKI
deploy/kubernetes/     manifests for the management plane and agents
proxy-management/      Spring Boot management server (Gradle multi-module)
enginx-agent/          Go node agent, co-located with NGINX
frontend/              Angular admin console
```

### Module boundaries

`proxy-management` is a modular monolith. Dependencies point inward and the direction is
asserted by an ArchUnit test, not merely documented:

```
management-domain          plain Java. No Spring, no JPA, no Jakarta.
                           Includes the whole permission model, so every authorization
                           rule is unit-testable without a container.
management-application     use cases, transaction boundaries, @PreAuthorize
management-infrastructure  JPA adapters, Liquibase, audit sink
management-security        OIDC resource server, role mapping, audience validation
management-scheduler       Quartz jobs: lifecycle sweep, outbox dispatch, certificate
                           monitor, audit partition maintenance, agent heartbeat
                           (which also detects configuration drift)
management-api             REST controllers, DTOs, RFC 9457 problem details, OpenAPI
management-boot            wiring and configuration
```

## Tests


```bash
cd proxy-management && ./gradlew test   # domain invariants, lifecycle matrix, ArchUnit boundaries
cd enginx-agent && go test ./...
```

## Documentation

| Where | What |
|---|---|
| [`docs/architecture.md`](architecture.md) | The design. Changes when the design changes |
| [`docs/admin/`](admin) | For whoever runs the platform |
| [`docs/api/`](api) | The REST contract and the generated client |
| [`docs/roadmap.md`](roadmap.md) | What is planned, and what was deliberately not done |
| [`docs/implementation-notes.md`](implementation-notes.md) | What changed during construction, and why |
| [`docs/releases.md`](releases.md) | Cutting a release, and what it publishes |

Implementation notes are kept apart from the design deliberately. A design document edited to match
every discovery reads as though nothing was ever learned, and the reasoning behind a correction is
the part most worth keeping.
