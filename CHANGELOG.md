# Changelog

What changed in each release, written for the people who run it. The release workflow publishes
the section for a version as that release's notes, ahead of the list generated from pull requests,
so a change pushed without one is still announced.

Versions follow the policy in [`docs/releases.md`](docs/releases.md#versioning-and-compatibility):
before 1.0, a minor release may break compatibility and says so here; a patch release never does.

## Unreleased

- **The Locations card no longer suggests that locations route.** A location is a path and a match
  type, and every location block uses the site's same upstreams, headers, timeouts and protection,
  so adding one changes nothing about where requests go. The card now says so; per-location routing
  is planned ([roadmap, Phase 15](docs/roadmap.md)).
- **The root location can no longer be removed in the console.** It is kept automatically whenever a
  site has other paths. Before, removing `/` left every other path to NGINX's own document root
  rather than the upstream. "Add location" adds one row, not the root and an empty row.

## 0.2.0

Protected sites: a site can now require a sign-in before anything reaches its upstream, by basic
authentication, by a code mailed to an allowed address, or at the platform's identity provider.
The last two are served by `enginx-gate`, a new unprivileged process beside NGINX on each host, so a
protected site never waits on the platform to check a visitor.

### Highlights

- **Protected sites: basic authentication.** A site can require a username and password from a user
  list kept on the site. Passwords are write-only, stored as SHA-512-crypt, and never returned.
  Visitors sign in with the browser's prompt, or on a **login page** served by the gate, with
  sessions, limits on failed attempts and *Sign everyone out*, while scripts can still send basic
  credentials. See [protected sites](docs/admin/protected-sites.md).
- **Protected sites: email sign-in.** A site can admit only visitors who sign in with a code mailed
  to an allowed address or `*@domain`. Codes are mailed by the platform, so hosts hold no SMTP
  credentials; sessions are checked on the host alone. **Sign everyone out** replaces a site's key.
  Needs `GATE_RELAY_URL`, SMTP, and outbound HTTPS from the host to the platform.
- **Protected sites: identity provider sign-in.** A site can admit visitors who sign in at the
  platform's identity provider: anyone who can, or only listed users (by email or username), realm
  roles and client roles. The gate runs the authorization code flow with PKCE directly with the
  provider and verifies its tokens itself. Configured once, with `GATE_OIDC_ISSUER_URI`,
  `GATE_OIDC_CLIENT_ID` and, when hosts reach the provider differently from browsers,
  `GATE_OIDC_BACKCHANNEL_URI`.
- **The upstream knows who signed in.** Gated sites pass `X-Enginx-Email` and `X-Enginx-User`, always
  set by NGINX, so a client cannot send its own.
- **Access rule changes apply to everyone at once.** A gated site's sessions carry the rules they
  were issued under, so deploying a narrower allow-list or role rule ends the sessions of everyone
  signed in under the old one, rather than letting them in until their session expires.
- **VERIFY checks that protection is enforced.** A protected site is asked for `/` and a random path
  as a visitor who has not signed in, and VERIFY fails if either is answered: a `return` in a host's
  custom file, a shadowing server block, unreadable files or a missing gate, none of which
  `nginx -t` rejects.
- **A warning for directives that bypass sign-in.** Every validation reads the custom files a
  protected site includes and reports each `return` or `rewrite` with its file and line, which also
  covers paths VERIFY does not ask for.
- **Unavailable protection is refused when a site is saved.** A gate mode whose platform settings are
  missing is refused with the setting named, and the console disables it; previously it failed only
  at the next deployment. `GET /proxy-sites/protection-modes` reports what is available.
- **The deployment files carry the gate.** The Kubernetes ConfigMap and the production compose file
  have the gate's settings and commented mail settings, and a NetworkPolicy lets agent pods reach
  the API to have codes mailed.
- **Bundle files carry their mode.** Secrets the NGINX workers read are written `0640`, group-owned
  by `AGENT_NGINX_GROUP` (default `nginx`; `www-data` on Debian and Ubuntu packages).

### Fixes

- **Saving a site from the console no longer drops its custom locations.** The form never sent
  them, and the API read the omission as "just the root location", so every save reduced a site to
  `location /`. The form now has a Locations card, and locations are recorded in the audit trail.
- **Secrets in stored bundles are sealed.** A rendered bundle is kept so redeploys and rollbacks
  resend exactly what was validated, and its sensitive files (certificate private keys, htpasswd
  files, gate files) were kept in the clear. They are now envelope-encrypted like every other
  secret, and re-wrapped when the key rotates.
- **A node with the scheduler turned off runs no scheduled jobs.** `SCHEDULER_ENABLED` is on by
  default and nothing shipped turns it off. Set to `false`, it removed the node's job definitions
  but still started a scheduler against the shared job store, which went on firing every job the
  other nodes had registered. It now runs none of them.
- **A host keeps its agent version after a deployment.** Every activation recorded the instance with
  no agent version until its next heartbeat, so the console showed none, and a protected site's
  render had to ask the agent again each time.

### Breaking changes since 0.1.2

- **An update that leaves `locations` out keeps the site's current ones.** It used to reset the site
  to the root location. Send an empty list for the root location alone. `protection` behaves the
  same way.
- **`config_bundle_files.content` is null for sensitive files**, which are held in the new sealed
  columns. Only a tool reading that table directly is affected; the API is unchanged.

### Upgrading to 0.2.0

1. **Upgrade agents first**, as for every release ([production guide §2.3](docs/admin/production.md)).
   The platform refuses to render a protected site for an agent older than 0.2.0, because an older
   agent writes its files where NGINX's workers cannot read them; existing sites are unaffected.
2. **Start the management server.** Migration `003-site-protection` runs, and the first start then
   seals the private keys of every stored bundle and logs how many.
3. **Optionally, set up the gate:** `GATE_RELAY_URL` and SMTP for email sign-in, `GATE_OIDC_*` for
   identity provider sign-in ([configuration](docs/admin/configuration.md)). Basic authentication
   needs neither. On a host whose service manager runs NGINX, run `enginx-gate` as a service too.

Schema: migration `003-site-protection` adds `proxy_sites.protection_mode`, `gate_session_ttl_s`,
`oidc_any_user` and `basic_login_page`; the `proxy_site_basic_auth_users`, `proxy_site_allowed_emails`,
`proxy_site_oidc_users`, `proxy_site_oidc_roles` and `proxy_site_gate_secrets` tables; and
`config_bundle_files.file_mode` plus sealed-content columns, with `content` now null for sensitive
files. API: `protection` on the site request and response, `GET /proxy-sites/protection-modes`,
`POST /proxy-sites/{id}/sign-out-everyone`, and `POST /agents/gate/otp-mail` for gates.

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
