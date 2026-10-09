# Changelog

What changed in each release, written for the people who run it. The release workflow publishes
the section for a version as that release's notes, ahead of the list generated from pull requests,
so a change pushed without one is still announced.

Versions follow the policy in [`docs/releases.md`](docs/releases.md#versioning-and-compatibility):
before 1.0, a minor release may break compatibility and says so here; a patch release never does.

## 0.1.0

The first release meant to be kept. Earlier `0.0.x` tags were development snapshots: their
databases cannot be upgraded and must be recreated (see [Upgrading](#upgrading-to-010)). From this
release on, the schema only moves forward through migrations.

### Highlights

- **Push agents behind a TLS-terminating proxy.** A dialled agent can authenticate the platform with
  a pre-shared token (`AGENT_PUSH_PROTOCOL=http`) instead of a client certificate. The token is
  compared in constant time, must be at least 32 characters, and is stored sealed under the same
  envelope encryption as private keys. The agent can serve HTTPS itself (`AGENT_TLS_CERT` /
  `AGENT_TLS_KEY`); without it, it and the console warn that traffic is unencrypted. A token is
  replaced in place with `PUT /api/v1/nginx-instances/{id}/agent-token`.
- **HTTP-01 certificates on pull-mode hosts.** A challenge reaches a host that calls in as a job,
  and issuance waits for the host to confirm it (`enginx.acme.pull-confirm-timeout`, default 20s).
- **Keycloak client roles.** The four roles can be realm roles, client roles on the console's client
  (`OIDC_ROLE_CLIENT_ID`, default `enginx-frontend`), or both. Endpoint checks and scoped
  permissions now read the same set, so a role cannot count for one and not the other.
- **`forceHttps=false` serves both ports.** An SSL site without the redirect answers on 80 and 443;
  before, port 80 fell through to a 404.
- **Versions are visible.** The console shows its own release and the server's, and both when they
  differ. New hosts enrolled from the console are given the matching agent image.

### Breaking changes since 0.0.9

- The database schema is a new baseline. A `0.0.x` database will not start; recreate it.
- `pushTransport: GRPC_TOKEN` and `AGENT_PUSH_PROTOCOL=grpc` are gone. Neither was ever gRPC; use
  `HTTP_TOKEN` and `http`.
- An unknown `AGENT_PUSH_PROTOCOL`, or a token shorter than 32 characters, now stops the agent at
  startup instead of being accepted.
- Registering an instance with both a fingerprint and a token, or with a transport inferred from
  whichever credential was sent, is refused; `pushTransport` must match the credential.
- `pushTransport` is an enum in the API (`MTLS`, `HTTP_TOKEN`) and is `null` for a pull host.
- Client roles on `enginx-api` no longer grant anything. Only the console's client counts.
- `enginx.agent.verify-hostname` is removed. It was never read; hostname verification is always on.
- OpenAPI operation ids are now `<controller>_<method>`. Generated clients keyed on the old ids
  must be regenerated.
- Compose files and Kubernetes manifests reference `0.1.0` images rather than `latest`.

### Upgrading to 0.1.0

There is no in-place upgrade from `0.0.x`. Recreate the database and re-register hosts:

```bash
cd docker && docker compose down -v && docker compose up -d
```

Upgrade agents before the management server, as for every release
([production guide §2.3](docs/admin/production.md)).
