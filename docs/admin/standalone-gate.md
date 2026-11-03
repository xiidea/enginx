# Using the gate without the platform

The gate (`enginx-gate`) is the small process that puts a sign-in page in front of a protected
site. The platform normally writes its configuration and NGINX's, but the gate itself does not
need the platform to run: it reads one JSON file per site from a directory, listens on a unix
socket, and NGINX asks it about every request with `auth_request`. A server administrator can
therefore run it beside an NGINX they configure by hand.

> **Experimental.** This mode may change in any release, including the site file format below,
> which is an internal contract between the platform and the gate, without notice or a migration
> path. Feedback is welcome. Read the [caveats](#caveats) before relying on it.

## When it is useful

- **A login page instead of the browser's basic-auth prompt**, for a handful of users on an NGINX
  that is not managed by the platform: a proper sign-in form, sessions that last as long as you
  choose, and a sign-out that works.
- **Single sign-on in front of an application that has none**, through an OpenID Connect provider
  such as Keycloak, admitting anyone who signs in or only listed users, realm roles or client
  roles.
- **Trying the gate out** before deciding to manage the host with the platform.

| Mode | Without the platform |
|---|---|
| Basic authentication on a login page (`BASIC`) | Works: users and their password hashes are in the site file |
| Identity provider sign-in (`OIDC`) | Works: the gate talks to the provider directly |
| Email one-time codes (`EMAIL_OTP`) | Does not work: the gate asks the platform to mail each code |
| Basic authentication with the browser's prompt | Needs no gate: that is NGINX's own `auth_basic` |

Sign-in events are reported to the platform's audit trail only when a site file has a `relayUrl`;
without one they are written to the gate's own log, and nothing else changes.

## 1. Write a site file

One JSON file per site, in a directory only root can write, for example
`/etc/nginx/gate-sites/app.json`:

```json
{
  "version": 1,
  "siteId": "app",
  "domain": "app.example.com",
  "mode": "BASIC",
  "key": "REPLACE: openssl rand -base64 32",
  "sessionTtlSeconds": 86400,
  "basicUsers": [
    { "username": "alice", "hash": "REPLACE: openssl passwd -6 'her-password'" }
  ]
}
```

- `siteId` is any name; NGINX sends it with every request so the gate knows which site is asking.
- `key` signs the session cookies. Keep it secret; replacing it signs everyone out.
- `hash` must be SHA-512-crypt (`$6$…`), which `openssl passwd -6` produces. bcrypt is refused.
- The file holds the key and the hashes: make it readable by the gate's user only
  (`chown root:nginx`, `chmod 0640`).

For an identity provider, use `"mode": "OIDC"` and, in place of `basicUsers`:

```json
"oidc": {
  "issuer": "https://id.example.com/realms/staff",
  "clientId": "app",
  "anyUser": false,
  "users": ["alice@example.com"],
  "realmRoles": ["staff"],
  "clientRoles": [{ "client": "app", "role": "viewer" }]
}
```

`clientSecret` (for a confidential client), `scopes` (default `openid email profile`) and
`backchannel` (the provider's address as the gate reaches it, when that differs from the issuer)
are optional. A public client with PKCE is recommended. Register
`https://app.example.com/.enginx/auth/callback` as the redirect URI.

The gate notices a changed, added or removed file within about two seconds; it needs no restart.

## 2. Run the gate

`enginx-gate` is attached to every [release](https://github.com/xiidea/enginx/releases) for Linux
and macOS, and is in the agent image at `/usr/local/bin/enginx-gate`. Run it as NGINX's worker user
(`nginx`, or `www-data` on Debian and Ubuntu), so the socket it creates (`0660`) is reachable by
the workers:

```ini
# /etc/systemd/system/enginx-gate.service
[Unit]
Description=enginx gate
Before=nginx.service

[Service]
User=nginx
Group=nginx
RuntimeDirectory=enginx
Environment=GATE_SITES_DIR=/etc/nginx/gate-sites
Environment=GATE_SOCKET=/run/enginx/gate.sock
ExecStart=/usr/local/bin/enginx-gate
Restart=always

[Install]
WantedBy=multi-user.target
```

`GATE_RELAY_CA` adds a CA the gate trusts when reaching an identity provider behind a private one.

## 3. Configure NGINX

The same blocks the platform renders for a protected site. HTTPS is required: the session cookie
is `__Host-` prefixed, which browsers accept only over HTTPS.

```nginx
server {
    listen 443 ssl;
    server_name app.example.com;
    ssl_certificate     /etc/ssl/app/fullchain.pem;
    ssl_certificate_key /etc/ssl/app/privkey.pem;

    # Every request is checked by the gate; a visitor without a session is sent to sign in.
    auth_request /.enginx/auth/check;
    auth_request_set $enginx_email $upstream_http_x_enginx_email;
    auth_request_set $enginx_user  $upstream_http_x_enginx_user;
    error_page 401 = @enginx_sign_in;

    location = /.enginx/auth/check {
        internal;
        proxy_pass http://unix:/run/enginx/gate.sock:/check;
        proxy_pass_request_body off;
        proxy_set_header Content-Length "";
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Enginx-Site app;          # the site file's siteId
    }

    # The gate's own pages: the login form, the provider's callback, sign-out.
    location ^~ /.enginx/auth/ {
        auth_request off;
        proxy_pass http://unix:/run/enginx/gate.sock:;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-Host $http_host;
        proxy_set_header X-Enginx-Site app;
    }

    location @enginx_sign_in {
        absolute_redirect off;
        return 302 /.enginx/auth/login?rd=$request_uri;
    }

    location / {
        proxy_pass http://127.0.0.1:8080;
        # Always set, so a client cannot send its own: the application can trust them.
        proxy_set_header X-Enginx-Email $enginx_email;
        proxy_set_header X-Enginx-User  $enginx_user;
    }
}
```

Redirect port 80 to HTTPS as you normally would. `/.enginx/auth/logout` signs a visitor out. On a
basic-authentication site, a script may still send `Authorization: Basic` instead of signing in;
a browser is always shown the login page.

## 4. Check that it refuses visitors

Nothing does this for you without the platform, so do it after every change:

```bash
curl -sk -o /dev/null -w '%{http_code}\n' https://app.example.com/          # 302 to the login page
curl -sk -o /dev/null -w '%{http_code}\n' https://app.example.com/anything  # 302 as well
curl -sk -u alice:her-password https://app.example.com/                     # the application (BASIC)
```

A `200` from the first two means the site is open.

## Caveats

- **Experimental, and the format may change.** The site file is an internal contract
  (`"version": 1`) between the platform and the gate, not a public interface. A release may change
  it without notice or a migration path; read the changelog before upgrading the binary, and pin
  the version you tested.
- **Nothing validates your configuration.** With the platform, values are checked before anything
  is rendered, and every deployment is verified from the host: the site must answer as a visitor
  who has not signed in expects. Here a mistake fails silently, and usually open. A `siteId` in
  NGINX that matches no site file refuses everyone; a site file the gate cannot read is logged and
  that site refuses everyone.
- **`return` and `rewrite` run before the check.** NGINX evaluates them before `auth_request`, so a
  `location` that answers with `return` is reached without signing in, as are paths in another
  `server` block for the same name. Proxy to the application instead, and test each path you add.
- **No email codes.** `EMAIL_OTP` needs the platform to mail them.
- **Revoking access is yours to arrange.** Removing a user from the file ends their sessions at the
  next request; for an identity provider, roles are read at sign-in, so a role taken away takes
  effect at the next sign-in, or at once if you replace the `key`.
- **Keep the gate's socket and files private.** The socket accepts anything NGINX's workers can
  send, and the site files hold the signing key and the password hashes.
- **Feedback shapes what comes next.** Issues and use cases are welcome; if there is interest, this
  may become a fully supported mode. Until then, a host managed by the platform is the path that is
  validated and verified on every deployment.
