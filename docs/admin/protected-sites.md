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
| Email one-time code | Enters an allowed email address and the code sent to it | planned |
| OIDC | Signs in at an identity provider | planned |

## Requirements

- **HTTPS with the redirect on.** A protected site must have SSL enabled *and* the HTTP to HTTPS
  redirect, so a password is never sent in cleartext. The API refuses anything else, and so does the
  database. The port-80 server of such a site only redirects; it never asks for credentials.
- **Agent 0.2.0 or later on the host.** The htpasswd file has to be readable by NGINX's worker
  processes, which older agents cannot arrange (see [below](#why-the-agent-version-matters)). A
  deployment to a host whose agent is older fails at render, before anything is sent, saying so.
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
endpoint returns it.

### Why the agent version matters

Sensitive bundle files are normally written `0600`, owned by root. That is right for a private key,
which the NGINX master reads as root. An htpasswd file is read by the workers, which run
unprivileged, on every request. Written `0600`, it passes `nginx -t`, which the master runs, and
then every request fails with a 500 and `Permission denied` in the error log. The deployment would
have reported success.

So the file is written `0640`, owned by root and the worker group, and the platform only sends it to
an agent that knows how to do that. Upgrade agents before protecting a site on their hosts.

### Custom directives can bypass it

NGINX runs `return` and `rewrite` before it checks credentials. A `return` in one of the host's
[custom files](agent.md#custom-directives) for a protected site answers without asking for a
password. Directives in those files are root's responsibility on the host, as they always were.
