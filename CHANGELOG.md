# Changelog

What changed in each release, written for the people who run it. The release workflow publishes
the section for a version as that release's notes, ahead of the list generated from pull requests,
so a change pushed without one is still announced.

Versions follow the policy in [`docs/releases.md`](docs/releases.md#versioning-and-compatibility):
before 1.0, a minor release may break compatibility and says so here; a patch release never does.

## 0.1.1

- **VERIFY proves the new configuration is live, not just the site.** Each site's marker now carries
  the revision of its record and a fingerprint of its rendered configuration and certificate
  (`<id> v<revision> <fingerprint>`), and the agent reports the marker it saw. A reload that
  `nginx -t` accepted but NGINX did not apply — a port held by another process is the usual cause —
  used to verify as served, because the old configuration answered with the same id. It is now
  reported as *an older configuration is still live*.
- **Redeploying repairs it.** After a deployment whose VERIFY failed, the next deployment re-sends
  the configuration even when nothing changed, and the agent reloads when asked to re-activate the
  bundle it already has. Before, the redeploy was skipped as already served.

Nothing changes in the API, the configuration or the schema. Upgrade agents first, as always: a
0.1.0 agent still verifies against a 0.1.1 server, but cannot report what it saw, so a stale
configuration is reported as answered by something else, and its re-activation does not reload.

## 0.1.0

The first release meant to be kept. Earlier `0.0.x` tags were development snapshots: their
databases cannot be upgraded and must be recreated (see
[Upgrading to 0.1.0](https://github.com/xiidea/enginx/blob/main/CHANGELOG.md#upgrading-to-010)).
From this release on, the schema only moves forward through migrations.

### Highlights

- **Runs beside an existing NGINX.** On a host that already serves sites, the agent either takes
  over running NGINX or works with the one the host's own service runs (`AGENT_NGINX_MANAGED=false`).
  It refuses to start a second master instead of crash-looping, checks that `nginx.conf` includes
  the platform's tree and refuses a deployment that NGINX would never load, and a host can keep its
  own default server (`PUT /api/v1/nginx-instances/{id}/default-server`). HTTP/2 is rendered in the
  form the host's version understands, so stock NGINX on current Debian and Ubuntu LTS loads it.
  1.22 (Debian 12) and 1.27 are tested; 1.18 and later is expected to work.
- **Custom directives without an editor.** Every rendered server block includes host-owned files
  under `/etc/nginx/enginx/custom/` — `http/`, `server/`, `sites/<domain>/`, `redirect/` and
  `default/` — NGINX Proxy Manager's custom-files mechanism. Writing one takes root on the host, not
  a grant in the console, and every deployment's validation loads them.
- **VERIFY proves the site is served.** Each site answers `/.well-known/enginx/site` with its own id,
  and only that answer verifies it. A default page answering 200 used to pass for a site that was not
  being served at all.
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
- VERIFY now fails when a name is answered by anything other than the site's own server block. The
  agent's verify request carries `sites` with markers; agents from before 0.1.0 do not understand it,
  which is one more reason to upgrade agents first.
- An agent left on the default `AGENT_NGINX_MANAGED=true` refuses to start while another NGINX master
  is running, and a deployment to a host whose `nginx.conf` does not include
  `/etc/nginx/enginx/current/conf.d/*.conf` fails validation instead of reporting success.

### Upgrading to 0.1.0

There is no in-place upgrade from `0.0.x`. Recreate the database and re-register hosts:

```bash
cd docker && docker compose down -v && docker compose up -d
```

Upgrade agents before the management server, as for every release
([production guide §2.3](docs/admin/production.md)).
