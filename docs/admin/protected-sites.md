# Protected sites

A proxy site is public by default: anyone who can reach the host can reach the site. Protection puts
a sign-in in front of it, enforced by NGINX before a request reaches the upstream, so the
application behind it needs no changes.

Protection applies to the whole site. Two paths always stay open, because the platform itself
needs them: ACME HTTP-01 challenges, so the site's certificate can still renew, and the
`/.well-known/enginx/site` marker the post-deployment probe reads.

| Mode | What a visitor does | Status |
|---|---|---|
| `NONE` | Nothing; the site is public | available |
| `BASIC` | Enters a username and password from the site's own user list | available |
| `EMAIL_OTP` | Enters an allowed email address, then the code mailed to it | available |
| `OIDC` | Signs in at the platform's identity provider, and is let in if the site allows them | available |

## Requirements

- **HTTPS with the redirect on.** A protected site must have SSL enabled *and* the HTTP to HTTPS
  redirect, so a password is never sent in cleartext. The API refuses anything else, and so does the
  database. The port-80 server of such a site only redirects; it never asks for credentials.
- **Agent 0.2.0 or later on the host.** Both modes ship files NGINX's worker processes (or the gate,
  running as their user) read, which older agents cannot arrange (see
  [below](#why-the-agent-version-matters)). A deployment to a host whose agent is older fails at
  render, before anything is sent, saying so.
- **The worker group.** The agent gives the file to the group NGINX's workers run as, named by
  `AGENT_NGINX_GROUP` (default `nginx`, which is right for the official image and most
  distributions; Debian and Ubuntu packages use `www-data`). If the group cannot be resolved, the
  agent logs a warning at startup and refuses to stage a protected site rather than write a file the
  workers cannot open.

## Basic authentication

In the console, open the site's form and set **Protection** to *Basic authentication*, then add
users. Over the API it is the `protection` object of the site request:

```json
"protection": {
  "mode": "BASIC",
  "basicUsers": [
    { "username": "alice", "password": "correct horse battery" },
    { "username": "bob" }
  ]
}
```

- **Passwords are write-only.** They are hashed on arrival and never returned; a site's response
  carries usernames only. To keep a user's current password, send the user without one, as `bob`
  above. A new user without a password is refused.
- **Leaving `protection` out of an update keeps what the site has.** A client that predates
  protection cannot remove it by omission. To remove it, send `"mode": "NONE"`.
- Usernames are 1 to 64 letters, digits, `.`, `_`, `@` or `-`. Passwords are 8 to 128 characters.
  A site may have up to 64 users.
- Changing a password changes the site's fingerprint, so VERIFY can tell whether the new user list
  is live.
- The audit trail records the mode and the usernames, never a hash.

### A login page instead of the browser's prompt

By default a visitor gets the browser's own username and password prompt, which no site can style
or replace. Choose **Visitors sign in with: a login page** (in the API,
`"basicLoginPage": {"sessionTtlSeconds": 28800}`) and the host's gate serves a login page on the
site's domain instead, for the same users and passwords:

- **Sessions.** A visitor signs in once and stays signed in for the session length; **Sign out**
  (`/.enginx/auth/logout`) and **Sign everyone out** work as for the other gate modes. Changing a
  user's password or removing the user ends their sessions at once, as it would with the prompt.
- **Limits on guessing.** Failed attempts are limited per client and per username, which NGINX's own
  basic authentication cannot do. An unknown username takes as long to refuse as a wrong password.
- **Scripts keep working.** A request carrying `Authorization: Basic` with valid credentials is let
  through without a session, so `curl -u user:pass` is unaffected. Its failures count against the
  same limits as the form's. Scripts only: a browser that once used the site's prompt keeps sending
  the credentials it cached there, and accepting them would put it past the login page for good,
  out of reach of signing out. Browsers mark every request with `Sec-Fetch-Mode`, which a page
  cannot remove and scripts do not send, so a request carrying it is sent to the login page.
  Credentials are not sessions: *Sign everyone out* does not stop a script that has them; changing
  the password does.
- **It needs the gate**, like email and identity provider sign-in: the agent image runs it, and a host
  whose service manager runs NGINX runs `enginx-gate` too. The browser's prompt needs NGINX alone.

### How it is stored and served

Each password is hashed with SHA-512-crypt (`$6$`, 5000 rounds), the strongest scheme that both
musl and glibc `crypt()` implement, which is what NGINX verifies against. bcrypt is deliberately
not used: `auth_basic` re-verifies on every request, with no cache, and bcrypt at a sensible cost
would add a quarter of a second to each one.

The users are rendered into `auth/<domain>/htpasswd` in the bundle, and the site's TLS server gets:

```nginx
auth_basic "Restricted";
auth_basic_user_file /etc/nginx/enginx/current/auth/<domain>/htpasswd;
```

The realm is a fixed string, never taken from the site. The file is marked sensitive, so no read
endpoint returns it. Like every sensitive file of a stored bundle — a certificate's private key
included — it is held envelope-encrypted, so redeploys and rollbacks can resend exactly what was
validated without the database holding it in the clear.

### Why the agent version matters

Sensitive bundle files are normally written `0600`, owned by root. That is right for a private key,
which the NGINX master reads as root. An htpasswd file is read by the workers, which run
unprivileged, on every request. Written `0600`, it passes `nginx -t`, which the master runs, and
then every request fails with a 500 and `Permission denied` in the error log. The deployment would
have reported success.

So the file is written `0640`, owned by root and the worker group, and the platform only sends it to
an agent that knows how to do that. Upgrade agents before protecting a site on their hosts.

## Email sign-in

A visitor without a session is sent to a sign-in page on the site's own domain. They enter their
address, and if it is on the site's allow-list a six-digit code is mailed to them. The code signs
them in for the session length set on the site, after which they are asked again.

```json
"protection": {
  "mode": "EMAIL_OTP",
  "emailOtp": {
    "allowedEmails": ["alice@partner.org", "*@example.com"],
    "sessionTtlSeconds": 86400
  }
}
```

- `*@example.com` admits every address at exactly that domain, not its subdomains.
- Changing the list ends every session once deployed, so an address taken off it is out at once;
  the others sign in again.
- A sign-in lasts from 5 minutes to 30 days; 24 hours if unset.
- The upstream receives the signed-in address as `X-Enginx-Email`, and as `X-Enginx-User`. NGINX
  always sets both, so a client cannot send its own, and the application can trust them.
- **Sign everyone out** (on the site page, or `POST /proxy-sites/{id}/sign-out-everyone`) replaces
  the site's key and deploys it, and every existing session stops working once that deployment is
  live. Through the API, add `?deploy=auto` to have it deploy; without it the key waits for the next
  deployment.

### What it needs

- **`GATE_RELAY_URL`** on the platform: its own API as the NGINX hosts reach it, ending in
  `/api/v1/agents/gate/otp-mail`. Without it a site with email sign-in does not render, and says
  why.
- **SMTP** on the platform (`SPRING_MAIL_HOST` and friends). Codes are mailed by the platform, so
  no host ever holds mail credentials.
- **Outbound HTTPS from the host to the platform**, to ask for a code to be mailed. Only then:
  checking a visitor's session never leaves the host, so a protected site keeps working with the
  platform down for everyone already signed in. A push host that the platform can reach but that
  cannot reach the platform back cannot offer email sign-in.
- **The gate.** In the agent image the agent starts it, as the `nginx` user. On a host whose service
  manager runs NGINX, run `enginx-gate` as a service too, as NGINX's worker user, with
  `GATE_SITES_DIR=<AGENT_RELEASES_DIR>/current/gate/sites`; it listens on
  `/run/enginx/gate.sock`. `GATE_RELAY_CA` adds a CA to trust for the relay, for a platform behind a
  private one.

### How it works

```
visitor ──▶ NGINX ──auth_request──▶ gate: valid session cookie?
              │                        yes ▶ 204 + X-Enginx-Email ─▶ proxied to the upstream
              │                        no  ▶ 401 ─▶ 302 to /.enginx/auth/login
              └─ /.enginx/auth/* ─────▶ gate: sign-in pages
                                         └─ code ──signed──▶ platform: allowed? rate? ──▶ SMTP
```

- **The gate** is a separate, unprivileged process, not part of the agent. It listens only on a
  unix socket the NGINX workers can reach, and reads one file per site from the active bundle,
  reloading when a deployment changes them.
- **Sessions** are a cookie (`__Host-enginx_session`: HTTPS-only, host-only, HttpOnly) signed with a
  key derived from the site's key. The gate keeps no session state, so restarting it signs nobody
  out.
- **Codes** are random, kept hashed in the gate's memory, valid for ten minutes, tied to the browser
  that asked for them, and spent after one use or five wrong tries.
- **The site's key** is 32 random bytes generated by the platform, held envelope-encrypted
  (`proxy_site_gate_secrets`) and re-wrapped with every key rotation. It reaches the host only in
  the bundle, in a file the workers' group can read and no read endpoint returns; the stored copy of
  that bundle file is sealed too.
- **The platform stays the authority.** The gate signs each request to mail a code with a second key
  derived from the site's key. The platform checks the signature and that the timestamp is within
  five minutes. It then checks the address against the site's allow-list itself, whatever the gate
  decided, and rate-limits per address and per site before anything is sent. Each code sent is
  audited, without the code.

### What a visitor cannot learn or do

- Whether an address is allowed: one that is not gets the same page and the same wait, and no mail.
- Anything from someone else's code: a code works only in the browser that asked for it.
- Send someone to another site: the post-sign-in redirect must be a path on this site.
- Use the forms from another site: a sign-in form posted from another origin is refused.
- Flood an inbox: per 15 minutes, the gate allows 10 requests from one client and 5 for one
  address; the platform allows 5 per address per 15 minutes and 200 per site per hour.

Rate limits are per gate and per platform replica, held in memory. When the gate's store of
sign-ins in progress is full, the oldest gives way, so flooding it cannot stop everyone else from
signing in.

## Identity provider sign-in

The platform has one identity provider for its sites, and each site decides who among its users may
enter. A visitor without a session is sent to sign in there, then comes back to the page they asked
for, or to a page saying their account may not enter.

```json
"protection": {
  "mode": "OIDC",
  "oidc": {
    "anyUser": false,
    "users": ["alice", "bob@example.com"],
    "roles": [{"role": "auditor"}, {"client": "reports", "role": "viewer"}],
    "sessionTtlSeconds": 28800
  }
}
```

- **`anyUser: true`** admits everyone who can sign in at the provider. Otherwise a visitor is let
  in if they match **any** entry:
  - **a user**, matched against the token's `email` and its `preferred_username`. An email the
    provider marks `email_verified: false` is ignored, for matching and for `X-Enginx-Email`, so an
    unproven address cannot claim someone's place on the list. Prefer usernames where your provider
    lets users change their own address.
  - **a realm role** (`{"role": "auditor"}`), read from `realm_access.roles`
  - **a client role** (`{"client": "reports", "role": "viewer"}`), read from
    `resource_access.reports.roles`. A role of that name on another client, or in the realm, does
    not count.
- In the console, roles are one per line: `auditor` for a realm role, `reports:viewer` for a client
  role. Role and client names may not contain a colon.
- The upstream receives `X-Enginx-Email` and `X-Enginx-User` (`preferred_username`), both always
  set by NGINX.
- **Roles are read when the visitor signs in.** A role taken away at the provider takes effect at
  their next sign-in; to take effect at once, **sign everyone out**. A change to the site's own
  rules takes effect at once when the site is deployed: every session was issued under the rules
  of its time, and a session issued under other rules sends its visitor to sign in again.
- **A changed access rule reaches the host when it is deployed.** The console deploys on save,
  unless *Save without deploying* is used; the API deploys only with `?deploy=auto` (see
  [deploying on save](managing-sites.md#deploying-on-save)).

### What it needs

- **The provider, configured on the platform:** `GATE_OIDC_ISSUER_URI` and `GATE_OIDC_CLIENT_ID`,
  plus `GATE_OIDC_BACKCHANNEL_URI` when hosts reach the provider at a different address than
  browsers do (see [configuration](configuration.md)). Without them a site with OIDC sign-in does
  not render, and says why.
- **A client for the gates.** It should be public with PKCE (S256), so no secret is copied to every
  host. Every OIDC site's redirect URI must be allowed on it: `https://<domain>/.enginx/auth/callback`,
  shown on the site page.
- **Outbound HTTPS from the host to the provider**, to exchange the code and fetch signing keys. The
  platform is not involved in a sign-in at all.
- **Roles in the tokens.** Keycloak puts realm and client roles in the access token by default; the
  gate reads them there, falling back to the ID token. Another provider must put Keycloak-shaped
  `realm_access` / `resource_access` claims in one of them for role rules to match. User rules and
  `anyUser` need nothing beyond `email` or `preferred_username`.

### How it works

- **The authorization code flow with PKCE, state and nonce.** The state is bound to the browser by
  the flow cookie, so a callback link opened anywhere else is refused. The code is exchanged by the
  gate, directly with the provider.
- **The ID token is verified**: an RS256 or ES256 signature against the provider's published keys,
  the issuer, an audience of this client, the authorized party, expiry with a minute of skew, and
  the nonce of this sign-in. `none` and HMAC algorithms never verify. The access token is used for
  roles only when it verifies the same way and was issued to this client for the same subject.
- **Sign-ins begun are limited** to 120 per client per 15 minutes, generous because an office
  behind one address signs in through it.
- **Keys and discovery are cached** for an hour. An unknown key id fetches the keys again, at most
  every 30 seconds, which is how a provider's key rotation is picked up.
- **Then the same session as email sign-in:** the same cookie, signed with the site's key, so
  checking a visitor never leaves the host, and *sign everyone out* works the same way.
- **The redirect back keeps the port the browser used**, from `X-Forwarded-Host`, trusted only when
  it names the site itself.

## Public paths

A location of a protected site can be marked **public**: it is reached without signing in, for a
webhook, a health check, or anything else a third party calls and cannot sign in to. Everything
else on the site still asks. It applies to that `location` block alone, rendered as
`auth_basic off;` or, for the two gate modes, `auth_request off;`. On a gated site a public path
still clears `X-Enginx-Email` and `X-Enginx-User`, so the upstream can trust them wherever they
arrive: nobody signed in there, so they are empty.

- **Prefer an exact match, or end a prefix with `/`.** NGINX matches a prefix by characters, so a
  public `/hooks` also opens `/hooks-admin`; `/hooks/` does not.
- **The root cannot be public.** Making `/` public is turning protection off, which is the
  protection setting's job. The platform's own paths (`/.enginx…`, `/.well-known/enginx…`) cannot
  be public either.
- **Verification still asks for protection.** VERIFY's probe requests `/` and a random path under
  `/.enginx-verify-`, neither of which a public location can match, so a site whose public paths
  are as intended still verifies, and one whose protection is lost elsewhere still fails.
- The site page lists the public paths in the Protection card, beside who may sign in, and the
  audit trail marks a location `[public]` when it changes.

## Verification

After each deployment, VERIFY asks the host, as a visitor who has not signed in, for `/` and for a
random path on every protected site, over HTTPS on loopback. Basic authentication must answer 401;
email sign-in must redirect to its sign-in page. Anything else fails VERIFY with what was answered
instead, for example:

```
private.example.com → served (v4 3fa9c1d2e8b0), but its protection is not enforced: / answered 200 without signing in
```

This is what catches the ways protection can be lost that `nginx -t` accepts. NGINX runs `return`
and `rewrite` before it checks credentials, so a `return` in one of the host's
[custom files](agent.md#custom-directives) answers without asking. Other causes are another server
block claiming the same name, files the workers cannot open, and a gate that is not running.

For both gate modes, the expected refusal is a redirect to `/.enginx/auth/login` on the site.

It cannot see a bypass confined to a path it does not ask for, such as a `location = /health`
with a `return` in a custom file. For that, every deployment's validation also reads the custom
files each protected site's server block includes (`custom/server/` and `custom/sites/<domain>/`,
following their own includes), and records each `return` or `rewrite` it finds as a warning with
the validation output:

```
warning: private.example.com is protected, but /etc/nginx/enginx/custom/sites/private.example.com/health.conf:2 has `return`, which answers before the visitor is asked to sign in
```

A warning does not fail the deployment: the file is root's on the host, and its author may mean
it. Directives in custom files remain root's responsibility, as they always were. VERIFY runs on
push hosts, and since 0.2.2 on pull hosts too, as a job the host collects after the activation
([managing sites](managing-sites.md#deployment-verification-and-drift)).

## The audit trail

The gate reports what happens at a protected site's door, and each event becomes a row in the
platform's audit trail, against the site, with the visitor as the actor (subject `gate`), their
address, and the time the gate saw it:

| Event | Audit action | Result |
|---|---|---|
| Signed in, with a password, a code or the identity provider | `GATE_SIGNED_IN` | SUCCESS |
| A wrong password or code, or a sign-in the provider refused | `GATE_SIGN_IN_FAILED` | FAILURE |
| Signed in at the provider but not allowed, or a code asked for an address not on the list | `GATE_ACCESS_DENIED` | DENIED |
| Signed out | `GATE_SIGNED_OUT` | SUCCESS |

A script's basic credentials are recorded only when they are wrong: one that works would add a row
every minute it runs.

- **Batched.** Every 30 seconds (`GATE_EVENTS_INTERVAL`), one request per host, to the relay
  address's sibling `…/api/v1/agents/gate/events`. Each site's events are signed with that site's
  relay key, as code requests are, so it needs no new credential and works for push and pull hosts.
- **Needs `enginx.gate.relay-url`** (`GATE_RELAY_URL`), and outbound HTTPS from the host to the
  platform, as email sign-in does. Without a relay address, events stay in the gate's own log.
  Basic and identity-provider sites carry the address from 0.2.2: deploy them once after upgrading,
  and the site page will say they have undeployed changes until you do.
- **Kept while the platform is away.** Up to 1,000 events a site wait on the host; past that, and
  past 6,000 rows a site an hour on the platform, events are counted instead, and the count is
  recorded as `GATE_EVENTS_DROPPED`.
- **What a visitor typed is bounded**: names and addresses are cut to 128 characters and stripped of
  control characters, and an address that is not one is dropped.
- A batch is accepted once; a section not signed with its site's key, or stamped more than five
  minutes from the platform's clock, is skipped. Events signed with a key that *Sign everyone out*
  has just replaced are skipped too, until the new key is deployed.
