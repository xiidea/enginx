# The agent

One agent runs on every host that serves proxied traffic, beside the NGINX it manages. It is a
deliberately dumb executor: store a configuration bundle, run `nginx -t`, swap it in atomically,
reload, report what happened. There is no way to make it run a command, copy an arbitrary file, or
open a shell, because the platform must not be able to do those things even by mistake.

It must share a host with NGINX — it signals the NGINX process, and a process in another container
cannot be signalled. In the container image the two ship together and the agent is PID 1.

## Two modes

The agent and the platform reach each other one of two ways. The choice is a property of the
network the host sits on, not a preference, and an estate can hold both.

| | **Dial mode** | **Pull mode** |
|---|---|---|
| Who connects | the platform connects to the host | the host connects to the platform |
| Inbound port | 8443, reachable from the management plane | none |
| Host identity | a certificate, fingerprint pinned at registration | a token, issued at enrolment |
| Registration | an operator enters a URL and a fingerprint | the host enrols itself |
| Works behind NAT | no | yes |
| HTTP-01 certificates | yes | not yet |

**Dial mode proves more.** The platform pins the agent's certificate fingerprint, so a certificate
signed by the same CA still cannot impersonate that host. A bearer token is a weaker claim, and
issuing agent certificates is what will replace it.

**Pull mode asks for less.** A host in another cloud, behind NAT, or on a network nobody routes to
can satisfy no listener contract, but it can always make an outbound call.

**The mode is chosen by one variable.** Setting `ENGINX_SERVER_URL` puts the agent in pull mode;
leaving it unset leaves it dialled. Nothing else switches it, and there is no flag to get wrong.

## Installing the binary

Releases publish static binaries for Linux (amd64, arm64, armv7) and macOS (arm64, amd64). macOS
is for running an agent on a developer's machine; a production host is Linux.

```bash
VERSION=0.0.4
curl -LO "https://github.com/xiidea/enginx/releases/download/v${VERSION}/enginx-agent-linux-amd64"
curl -LO "https://github.com/xiidea/enginx/releases/download/v${VERSION}/SHA256SUMS.txt"
sha256sum --ignore-missing -c SHA256SUMS.txt

install -m 0755 enginx-agent-linux-amd64 /usr/local/bin/enginx-agent
enginx-agent -version        # enginx-agent 0.0.4 (a1b2c3d) linux/amd64
```

Verify the checksum before installing. It is the only thing standing between a download and a
binary that will be given every site's private key.

The container image is the other way to run it, and carries NGINX with it:

```
ghcr.io/xiidea/enginx-agent:0.0.4
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
| `AGENT_VERSION` | the compiled-in version | Overridden only for testing |

**Dial mode only**

| Variable | Default | |
|---|---|---|
| `AGENT_LISTEN_ADDR` | `:8443` | Bind to the management network, not `0.0.0.0`, where you can |
| `AGENT_HEALTH_ADDR` | `127.0.0.1:9099` | Unauthenticated liveness. Loopback only — it must never become a way to read host detail without a client certificate |
| `AGENT_TLS_CERT` / `AGENT_TLS_KEY` | `/etc/enginx/pki/agent.{crt,key}` | This host's identity |
| `AGENT_CLIENT_CA` | `/etc/enginx/pki/ca.crt` | The CA that signs the management client certificate |
| `AGENT_CLIENT_CN` | `enginx-management` | Pinned in addition to CA verification. Trusting the CA alone would let any certificate it ever signed drive this host |

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
was serving traffic and stopped should be replaced rather than left half-alive.

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
| `GET /agent/v1/ready` | `200` when NGINX is running, `503` when it is not | **readiness** |

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

## One thing it does not do yet

**A pull-mode host cannot answer HTTP-01 challenges.** Publishing a challenge is a question asked
of the host within seconds, and only queued work is delivered today. Certificates for a pull host
have to come from DNS-01 or be uploaded.
