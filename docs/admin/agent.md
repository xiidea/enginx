# The agent

One agent runs on every host that serves proxied traffic, beside the NGINX it manages. It is a
deliberately dumb executor: store a configuration bundle, run `nginx -t`, swap it in atomically,
reload, report what happened. There is no way to make it run a command, copy an arbitrary file, or
open a shell, because the platform must not be able to do those things even by mistake.

It must share a host with NGINX — it signals the NGINX process, and a process in another container
cannot be signalled. In the container image the two ship together and the agent is PID 1.

On a host that already runs NGINX, the agent either takes over running it or works with the
NGINX the host's own service runs (`AGENT_NGINX_MANAGED=false`). Either way, the host's
`nginx.conf` needs one line to include the platform's tree, and a decision about who answers names
no site matches. See [Beside an existing NGINX](#beside-an-existing-nginx).

## Two modes

The agent and the platform reach each other one of two ways. The choice is a property of the
network the host sits on, not a preference, and an estate can hold both.

| | **Dial mode** | **Pull mode** |
|---|---|---|
| Who connects | the platform connects to the host | the host connects to the platform |
| Inbound port | 8443 (or configured port), reachable from the management plane | none |
| Host identity | a pinned certificate (mTLS), or a pre-shared token | a token, issued at enrolment |
| Registration | an operator enters a URL and a fingerprint or token | the host enrols itself |
| Works behind NAT | no | yes |
| HTTP-01 certificates | yes | yes |

**Dial mode proves more.** With mutual TLS (`mtls`, the default), the platform pins the agent's
certificate fingerprint, so a certificate signed by the same CA still cannot impersonate that host.
A host behind a proxy or tunnel that terminates TLS cannot be offered a client certificate, so dial
mode can instead authenticate the platform with a pre-shared token (`AGENT_PUSH_PROTOCOL=http`).
A token is a weaker claim than a pinned certificate; prefer mTLS wherever it can reach.

**Pull mode asks for less.** A host in another cloud, behind NAT, or on a network nobody routes to
can satisfy no listener contract, but it can always make an outbound call.

**The mode is chosen by one variable.** Setting `ENGINX_SERVER_URL` puts the agent in pull mode;
leaving it unset leaves it dialled. Nothing else switches it, and there is no flag to get wrong.

## Installing the binary

Releases publish static binaries for Linux (amd64, arm64, armv7) and macOS (arm64, amd64). macOS
is for running an agent on a developer's machine; a production host is Linux.

```bash
VERSION=0.1.0
curl -LO "https://github.com/xiidea/enginx/releases/download/v${VERSION}/enginx-agent-linux-amd64"
curl -LO "https://github.com/xiidea/enginx/releases/download/v${VERSION}/SHA256SUMS.txt"
sha256sum --ignore-missing -c SHA256SUMS.txt

install -m 0755 enginx-agent-linux-amd64 /usr/local/bin/enginx-agent
enginx-agent -version        # enginx-agent 0.1.0 (a1b2c3d) linux/amd64
```

Verify the checksum before installing. It is the only thing standing between a download and a
binary that will be given every site's private key.

The container image is the other way to run it, and carries NGINX with it:

```
ghcr.io/xiidea/enginx-agent:0.1.1
```

## Dial mode

The original model. The host needs a certificate the platform trusts, and 8443 open to the
management plane.

```bash
enginx-agent
```

with:

```bash
AGENT_TLS_CERT=/etc/enginx/pki/agent.crt
AGENT_TLS_KEY=/etc/enginx/pki/agent.key
AGENT_CLIENT_CA=/etc/enginx/pki/ca.crt
AGENT_CLIENT_CN=enginx-management
AGENT_LISTEN_ADDR=:8443
```

Then register the host, giving the platform its URL and the SHA-256 fingerprint of that
certificate:

```bash
openssl x509 -in /etc/enginx/pki/agent.crt -noout -fingerprint -sha256 | sed 's/.*=//' | tr -d ':'
```

The agent refuses to start if any of the three files is unreadable, naming the one that is missing.
A control plane that came up without its identity and failed on the first real call would be worse:
the failure would arrive at deployment time, on somebody else's schedule.

**Restrict 8443 at the firewall to the management plane's address.** Certificate pinning is the
authentication; there is no reason for the port to be reachable from anywhere else at all.

### Dial mode with a pre-shared token

For a host behind a load balancer, ingress, or tunnel that terminates TLS and so cannot pass a
client certificate through:

```bash
AGENT_PUSH_PROTOCOL=http
AGENT_SECRET_TOKEN=$(openssl rand -hex 32)   # at least 32 characters, or the agent refuses to start
AGENT_LISTEN_ADDR=:8080
# Optional: serve HTTPS from the agent itself. No client certificate is asked for.
AGENT_TLS_CERT=/etc/enginx/pki/agent.crt
AGENT_TLS_KEY=/etc/enginx/pki/agent.key
enginx-agent
```

Register the host with transport **Token** in the console, or `"pushTransport": "HTTP_TOKEN"` and
the same value as `agentAuthToken` through the API. The platform sends it as
`Authorization: Bearer …`; the agent compares it in constant time.

**Without TLS, nothing is secret.** The token and every bundle the platform pushes — site private
keys included — cross the network readable. Plain HTTP is accepted only so a proxy on the same
machine can terminate TLS in front of the agent. Anywhere else, set `AGENT_TLS_CERT`/`AGENT_TLS_KEY`
or front the port with something that does. Over HTTPS the platform checks the agent's certificate
against the JVM trust store.

The platform stores the token sealed under the same envelope encryption as private keys, and KEK
re-wrap covers it. To rotate one, set the new `AGENT_SECRET_TOKEN` on the host, restart the agent,
then `PUT /api/v1/nginx-instances/{id}/agent-token` with `{"agentAuthToken": "…"}`. Calls fail
between the two steps; traffic does not.

## Pull mode

The host enrols itself and then asks for work. Nothing connects to it.

Mint a registration token first — the console's **NGINX instances** page, which shows it once
alongside the command to run, or the API. Then:

```bash
ENGINX_SERVER_URL=https://enginx.example.com/api/v1 \
ENGINX_REGISTRATION_TOKEN=enginx-reg-… \
ENGINX_INSTANCE_NAME=nginx-edge-01 \
  enginx-agent
```

No certificate, no listener, no open port.

**Keep `ENGINX_TOKEN_FILE` on durable storage.** The token issued at enrolment is written there,
mode 0600, and reused on every restart. Without it the agent enrols again each time it starts, and
a token good for a single use would be spent by a reboot.

**Leave the registration token in place after enrolment.** It is not used again while the stored
token works — but if the platform ever rejects the stored one, the agent will enrol again exactly
once and recover on its own. Remove it and recovery becomes a manual visit to the host.

If the instance still exists and only its credential was revoked, that recovery fails on the name
already being taken, and the log says the host needs an operator. Delete the instance or reissue
its token, then restart.

## Configuration

Everything is an environment variable. There is deliberately no way to configure a command: NGINX
is invoked with a fixed argument vector, never through a shell, so the agent has no
command-injection surface to protect.

**Both modes**

| Variable | Default | |
|---|---|---|
| `AGENT_RELEASES_DIR` | `/etc/nginx/enginx` | Where bundles are stored and `current` points |
| `AGENT_NGINX_BINARY` | `/usr/sbin/nginx` | Must exist, or the agent refuses to start |
| `AGENT_NGINX_CONF` | `/etc/nginx/nginx.conf` | The configuration NGINX is started with |
| `AGENT_NGINX_MANAGED` | `true` | `true`: the agent starts and supervises NGINX. `false`: the host's service manager runs it, and the agent only validates and reloads |
| `AGENT_NGINX_PID_FILE` | `/run/nginx.pid` | Where the running master's pid is. Read when NGINX is external, and checked at startup so a managed agent refuses to start a second master |
| `AGENT_VERSION` | the compiled-in version | Overridden only for testing |

**Dial mode only**

| Variable | Default | |
|---|---|---|
| `AGENT_PUSH_PROTOCOL` | `mtls` | `mtls`, or `http` for a pre-shared token. Anything else is refused at startup |
| `AGENT_SECRET_TOKEN` | — | Required under `http`, at least 32 characters |
| `AGENT_LISTEN_ADDR` | `:8443` | Bind to the management network, not `0.0.0.0`, where you can |
| `AGENT_HEALTH_ADDR` | `127.0.0.1:9099` | Unauthenticated liveness. Loopback only — it must never become a way to read host detail without authentication |
| `AGENT_TLS_CERT` / `AGENT_TLS_KEY` | `/etc/enginx/pki/agent.{crt,key}` | This host's identity. Under `http`, used only when both are set, to serve HTTPS |
| `AGENT_CLIENT_CA` | `/etc/enginx/pki/ca.crt` | The CA that signs the management client certificate (`mtls` protocol only) |
| `AGENT_CLIENT_CN` | `enginx-management` | Pinned in addition to CA verification (`mtls` protocol only) |

**Pull mode only**

| Variable | Default | |
|---|---|---|
| `ENGINX_SERVER_URL` | — | The management API base. **Setting this selects pull mode** |
| `ENGINX_REGISTRATION_TOKEN` | — | Spent once at enrolment, then kept for recovery |
| `ENGINX_TOKEN_FILE` | `/var/lib/enginx/agent-token` | The issued credential, written `0600` |
| `ENGINX_INSTANCE_NAME` | the hostname | How this host appears in the console |
| `ENGINX_ENVIRONMENT` | `PRODUCTION` | A label, shown beside the instance |
| `ENGINX_HEARTBEAT_INTERVAL` | `60s` | Keep well under `AGENT_SILENCE_THRESHOLD` on the server |
| `ENGINX_POLL_WAIT` | `30s` | How long a request for work is held open when there is none |

## Running it under systemd

```ini
[Unit]
Description=Easy NGINX Admin agent
After=network-online.target
Wants=network-online.target
# The agent runs NGINX, so the distribution's service must not start another. With
# AGENT_NGINX_MANAGED=false use After=nginx.service and Requires=nginx.service instead.
Conflicts=nginx.service

[Service]
# Root, and only because it signals the NGINX master and writes into the release tree. Its
# privileges end there: it runs no shell and accepts no command.
User=root
Environment=ENGINX_SERVER_URL=https://enginx.example.com/api/v1
Environment=ENGINX_INSTANCE_NAME=nginx-edge-01
EnvironmentFile=-/etc/enginx/agent.env
ExecStart=/usr/local/bin/enginx-agent
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

Put `ENGINX_REGISTRATION_TOKEN` in `/etc/enginx/agent.env`, mode `0600`, rather than in the unit
file — a unit file is world-readable and ends up in configuration management.

The agent supervises NGINX as its child and exits if NGINX dies, so `Restart=always` restarts both
together. That is deliberate: NGINX dying is different from never having started, and a host that
was serving traffic and stopped should be replaced rather than left half-alive. With
`AGENT_NGINX_MANAGED=false` none of that applies: NGINX's lifecycle is its own service's, and the
agent keeps running while it restarts.

## Beside an existing NGINX

What a host that already ran NGINX needs. The self-managed guide walks through it step by step
([§7](self-managed-setup.md#prepare-the-hosts-nginx-both-modes)).

**The include line.** A distribution's `nginx.conf` loads `conf.d/` and `sites-enabled/`, not the
platform's tree. Add, inside `http { … }`:

```nginx
include /etc/nginx/enginx/current/conf.d/*.conf;
```

Without it a deployment would validate and reload and serve nothing, so the agent checks for it
(`nginx -T`): it warns at startup, and a deployment fails validation with the line to add. Existing
sites keep working beside the platform's, as long as no `server_name` is used by both.

**Who runs NGINX.** Either disable the distribution's service and let the agent run NGINX (the
default), or keep the service and set `AGENT_NGINX_MANAGED=false`. Starting the agent on its default
while the distribution's NGINX runs is refused with that choice spelled out: a second master cannot
bind ports 80 and 443, and would take the agent down on every restart.

**Who answers unmatched names.** The platform's catch-all and a distribution's
`sites-enabled/default` both claim `default_server`, and two fail validation. Remove the
distribution's, or register the host as keeping its own default server (the console's checkbox, or
`"defaultServerManaged": false`) so bundles leave the catch-all out.

**Versions.** NGINX 1.25.1 replaced the `listen … http2` parameter with an `http2 on;` directive,
and each version rejects the other's form. The agent reports the host's version, and the platform
renders whichever that version accepts — the parameter form until it knows.

**How a deployment is verified.** Every site answers `/.well-known/enginx/site` on port 80 with a
marker, served by NGINX itself: the site's id, the revision of its record and a fingerprint of the
configuration rendered for it (`<id> v<revision> <fingerprint>`). The fingerprint covers the site's
file and its certificate, so it changes whenever what NGINX serves for the site does. After a reload
the agent asks for the marker under each site's name, for up to five seconds while old workers hand
over, and reports what it saw. A site counts as served only when the marker of the bundle just
deployed comes back. The same site answering with an earlier marker means the reload did not take
effect and NGINX kept the configuration before; a distribution's default page answering 200 is
reported as answered by something else.

## Custom directives

For the directive the platform does not model — a rate limit, a header on every site, an
`allow`/`deny` list, a log format — the host's administrator writes it in a file the rendered
configuration includes. It is NGINX Proxy Manager's custom-files mechanism, and deliberately not an
editor in the console: a directive here can do anything NGINX can, so writing one takes root on the
host rather than a grant in this platform.

| File | Included |
|---|---|
| `/etc/nginx/enginx/custom/http/*.conf` | in the `http` block, before every server: zones, maps, log formats |
| `/etc/nginx/enginx/custom/server/*.conf` | at the end of every site's server block, ports 80 and 443 |
| `/etc/nginx/enginx/custom/sites/<domain>/*.conf` | at the end of that one site's server blocks |
| `/etc/nginx/enginx/custom/redirect/*.conf` | at the end of every HTTP → HTTPS redirect block |
| `/etc/nginx/enginx/custom/default/*.conf` | at the end of the platform's catch-all servers, when it renders them |

For example, a header on every site and a rate limit on one:

```bash
mkdir -p /etc/nginx/enginx/custom/http /etc/nginx/enginx/custom/server \
         /etc/nginx/enginx/custom/sites/app.example.com
echo 'limit_req_zone $binary_remote_addr zone=app_rl:10m rate=20r/s;' \
  > /etc/nginx/enginx/custom/http/ratelimit.conf
echo 'add_header X-Robots-Tag "noindex" always;' > /etc/nginx/enginx/custom/server/headers.conf
echo 'limit_req zone=app_rl burst=40;' > /etc/nginx/enginx/custom/sites/app.example.com/limits.conf
nginx -t && nginx -s reload
```

What to know:

- **Nothing to configure.** The includes are wildcards, which NGINX accepts when nothing matches,
  even when the directory does not exist. A host without custom files is unaffected.
- **Apply edits with `nginx -t && nginx -s reload`** on the host (`docker exec <agent container> sh
  -c 'nginx -t && nginx -s reload'` for the image; `systemctl reload nginx` when the host's service
  runs NGINX). The platform does not see these files, so it neither deploys them nor notices them
  change — keep them in configuration management.
- **Every deployment validates them.** Its `nginx -t` loads the custom files too, so a broken one
  fails the deployment at VALIDATE naming the file and line, and nothing changes on the host. Fix the
  file, then deploy again.
- **They come last in each server block**, after the platform's locations. A `location` repeating a
  path the platform already renders fails validation rather than silently winning.
- **The agent never touches the directory.** It lives beside `acme-challenge/` and `default-tls/`,
  outside the release tree. For the container image, bind-mount it into the releases volume — the
  agent compose file has the line commented out.

## Switching a host between modes

There is no in-place switch. A host is registered one way or the other, and the two carry different
identities — a pinned certificate against an issued token.

To move a host, delete its instance and register it again the other way. Its deployment history and
the sites pointing at it belong to the instance, so both are lost; the sites have to be pointed at
the new one. Cloning a site onto the new instance before deleting the old one is the least
disruptive order.

## Health and readiness

Two endpoints on the loopback listener, in both modes. Neither requires a client certificate and
neither reveals anything about the host beyond whether it is serving.

| | | |
|---|---|---|
| `GET /agent/v1/health` | always `200` while the agent runs | **liveness** |
| `GET /agent/v1/ready` | `200` when NGINX is running — the agent's child, or the master in `AGENT_NGINX_PID_FILE` when external — `503` when it is not | **readiness** |

**Liveness deliberately ignores NGINX, and that separation is load-bearing.** The agent stays up
when NGINX will not start — an unresolvable upstream on a cold boot is enough to do it — precisely
so a corrected bundle can be deployed. If liveness reported that failure, an orchestrator would
restart the container, the agent would find the same broken configuration, and the only route to
repairing the host would be destroyed by the thing meant to protect it.

**Readiness is what tells you the host is not serving.** Probe it, and a container whose NGINX
never started shows `unhealthy` instead of `healthy`. Docker does not restart on an unhealthy
check, and the Kubernetes DaemonSet uses it as a `readinessProbe`, so failing takes the host out of
rotation and reports it without restarting anything.

Carry a slow start in `start_period` rather than in `retries`. Retries are how long a *genuine*
failure takes to surface, and a host that stopped serving should say so quickly.

## HTTP-01 on a pull-mode host

A challenge reaches a pull host as a job, delivered on its next long-poll — normally within a
second. Issuance waits up to `enginx.acme.pull-confirm-timeout` (default `20s`) for the host to
report the response in place before the authority is asked to look; a host that does not confirm in
time is not counted, and issuance fails only if no host at all confirmed.

A host runs one job at a time, so a challenge queued behind a deployment in progress waits for it.
Keep the timeout comfortably above how long an activation takes on your slowest host.
