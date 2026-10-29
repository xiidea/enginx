# Changelog

What changed in each release, written for the people who run it. The release workflow publishes
the section for a version as that release's notes, ahead of the list generated from pull requests,
so a change pushed without one is still announced.

Versions follow the policy in [`docs/releases.md`](docs/releases.md#versioning-and-compatibility):
before 1.0, a minor release may break compatibility and says so here; a patch release never does.

## Unreleased

- **Protected sites: basic authentication.** A site can require a username and password before
  anything reaches its upstream, from a user list kept on the site. Passwords are write-only, stored
  as SHA-512-crypt, and never returned; an update that leaves `protection` out keeps what the site
  has. A protected site must use HTTPS with the redirect on. See
  [protected sites](docs/admin/protected-sites.md).
- **Bundle files carry their mode.** Secrets the NGINX workers read are written `0640`, group-owned
  by `AGENT_NGINX_GROUP` (default `nginx`). Upgrade agents first: the platform refuses to render a
  protected site for an agent older than 0.2.0, because an older agent writes the file `0600` and
  NGINX then fails every request while `nginx -t` passes.

- **Protected sites: email sign-in.** A site can admit only visitors who sign in with a code mailed
  to an allowed address or `*@domain`. Codes are mailed by the platform, so hosts hold no SMTP
  credentials; sessions are checked on the host alone. The upstream receives the address as
  `X-Enginx-Email`. Served by `enginx-gate`, a new unprivileged process the agent image runs beside
  NGINX and the release ships as its own binary. Needs `GATE_RELAY_URL`, SMTP, and outbound HTTPS
  from the host to the platform. **Sign everyone out** replaces a site's key.
- **Protected sites: identity provider sign-in.** A site can admit visitors who sign in at the
  platform's identity provider: anyone who can, or only listed users (by email or username), realm
  roles and client roles. The gate runs the authorization code flow with PKCE directly with the
  provider and verifies its tokens itself; the platform is not involved in a sign-in. Configured
  once, with `GATE_OIDC_ISSUER_URI`, `GATE_OIDC_CLIENT_ID` and, when hosts reach the provider
  differently from browsers, `GATE_OIDC_BACKCHANNEL_URI`. The upstream also receives
  `X-Enginx-User`.
- **VERIFY checks that protection is enforced.** A protected site is asked for `/` and a random path
  as a visitor who has not signed in, and VERIFY fails if either is answered. This catches a `return`
  in a host's custom file, a shadowing server block, unreadable files and a missing gate, none of
  which `nginx -t` rejects.

- **Saving a site from the console no longer drops its custom locations.** The form never sent
  them, and the API read the omission as "just the root location", so every save reduced a site to
  `location /`. The form now has a Locations card. **API change:** an update that leaves
  `locations` out keeps the site's current ones, as `protection` already does; send an empty list
  for the root location alone. Locations are now recorded in the audit trail.
- **Secrets in stored bundles are sealed.** A rendered bundle is kept so redeploys and rollbacks
  resend exactly what was validated, and its sensitive files (certificate private keys, htpasswd
  files, gate files) were kept in the clear. They are now envelope-encrypted like every other
  secret, and re-wrapped when the key rotates. Existing bundles are sealed when the platform first
  starts on this release; the log says how many.

Schema: migrations `003-site-protection`, `004-gate`, `005-gate-oidc` and `006-sealed-bundle-files`
add
`proxy_sites.protection_mode`, `gate_session_ttl_s` and `oidc_any_user`; the
`proxy_site_basic_auth_users`, `proxy_site_allowed_emails`, `proxy_site_oidc_users`,
`proxy_site_oidc_roles` and `proxy_site_gate_secrets` tables; and `config_bundle_files.file_mode`
plus sealed-content columns, with `content` now null for sensitive files. API: `protection` on the site
request and response, `POST /proxy-sites/{id}/sign-out-everyone`, and
`POST /agents/gate/otp-mail` for gates.

## 0.1.2

- **Client roles show in the console.** A user whose role is a client role on the console's client
  (`OIDC_ROLE_CLIENT_ID`) rather than a realm role was allowed by the API but shown a console with
  every admin action hidden. The console now reads the same two sources the server does.
- **Identity-provider tokens no longer reach browser storage.** The access and refresh tokens of a
  Keycloak session are held in memory only. A reload or a new tab signs the user back in silently
  from the provider's own session, on the same page, without a password; when that session has
  ended, the sign-in page appears. Local sessions are unchanged and survive a reload as before.

Nothing changes in the API, the configuration or the schema; only the console image differs.

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
