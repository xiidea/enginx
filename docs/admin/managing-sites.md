# Managing proxy sites

Creating sites, deploying them, and knowing whether a deployment actually took effect.

## Deployment

Configuration is generated, never hand-written. The deployment unit is a **bundle**: the complete
intended configuration for one NGINX instance, content-addressed by a SHA-256 of its file
manifest.

```
POST /proxy-sites/{id}/deploy   ->  202, a deployment row and an outbox message, one transaction
                                    (the response arrives before the host has been touched)
dispatcher                      ->  render from current state under a per-instance advisory lock
                                ->  stage on the agent            (nothing served yet)
                                ->  nginx -t against the candidate (symlink already points at it)
                                ->  rename(2) the symlink          (atomic)
                                ->  nginx -s reload
```

### Why it is shaped this way

**The unit is the instance, not the site.** `nginx -t` validates a tree, not a file. A per-site
unit leaves "which change broke this?" and "roll back one site" undefined as soon as two sites
share a `server_name` or an upstream.

**The dispatcher renders, not the request.** Building the bundle when the request arrives would
capture the state its author saw. Two edits committed seconds apart would then produce two
snapshots whose application order decides the result, and applying the earlier one second would
silently revert the later edit. Rendering at dispatch, under `pg_advisory_xact_lock`, means what
reaches the host is what the database says right now.

**The outbox exists because a deployment is a database write plus a remote call.** In one
transaction, whichever half failed would leave the other applied. The deployment row and its
outbox message commit together; the dispatcher retries the side effect.

**Retries reuse one idempotency key** — the deployment id — so a response lost in flight cannot
double-apply. The agent scopes stored keys by operation, since one deployment sends the same key
to `stage` and to `activate`.

**Validation failure is never retried.** Identical bytes fail identically; retrying would burn
attempts and delay the alert. Transport failures back off 5s, 30s, 2m, 10m, 30m and then go
`DEAD` rather than retrying forever.

**Rollback is never automatic.** The one automatic revert is the agent restoring the previous
release when the *reload* fails — a refusal to leave the host broken, not a decision to change
version. `nginx -t` cannot catch a port that will not bind, because it does not bind sockets.

## Lifecycle

A site is served when its operator has enabled it **and** its activation window is open. Expiry
and activation are applied by a sweep, not by a per-site alarm.

```
active_from > now                 -> PENDING   (not served)
active_from <= now < expires_at   -> ACTIVE    (served)
expires_at <= now                 -> EXPIRED   (removed from the bundle, row retained)
```

When a site crosses either boundary the sweep marks it, writes an audit row, and queues a
redeployment of its instance — all in one transaction, so a site can never be marked expired in
the database while NGINX carries on serving it.

### Why a sweep rather than a timer per site

One Quartz trigger per `expires_at` sounds more precise, but editing an expiry would have to
reschedule, a delete would have to unschedule, and a single missed misfire would strand a site
past its expiry forever. Asking "which sites are due now?" is idempotent and self-healing: an
expiry that passed while the application was stopped is applied by the next run, because the
question is about the present rather than about an alarm that may or may not have rung.

The cost is precision bounded by the interval. "Expires at 23:59:59" means "stops serving within
a minute of 23:59:59", and the sweep is one indexed range scan over the two partial indexes on
`proxy_sites`, so running it every 60s is cheap regardless of table size.

### Why Quartz, clustered

`spring.quartz.job-store-type: jdbc` with `isClustered: true`. Triggers live in `QRTZ_TRIGGERS`,
so they survive a restart; the cluster lock means one node fires each trigger; and the claim query
uses `FOR UPDATE SKIP LOCKED` on top of that. Three independent reasons a site cannot be processed
twice, because the consequence of getting it wrong is an expiry applied twice — or not at all.

The `QRTZ_*` schema is managed by **Liquibase**, and `spring.quartz.jdbc.initialize-schema` is
`never`. Quartz's own script begins by dropping every one of its tables, so letting it run at
startup would discard triggers, misfire records and lock rows on every boot.

### The catch-all server

Every bundle emits `conf.d/00-enginx-base.conf` with `listen 80 default_server` returning 404.
Without it NGINX treats the first server block as the default, and two things follow: an expired
domain keeps being served, so expiry has no effect a client can observe, and any name pointed at
the address reaches a backend it was never meant to. The catch-all is what makes "this host serves
exactly the sites we configured" true.

An HTTPS catch-all needs a certificate of its own, so it arrives with Phase 6.

## Deployment verification and drift

A reload proves the configuration parsed and loaded. It does not prove a client reaches anything,
and it says nothing about tomorrow. Two mechanisms close that gap, and both run on the NGINX host
rather than on the management server — which is where the answer means something, and avoids
pointing the management server at user-supplied addresses.

**`VERIFY`** runs after a successful reload. The agent issues a loopback request for each deployed
name with the Host header set, for `/.well-known/enginx/site` — which each site's own server block
answers with a marker naming the site, the revision of its record and a fingerprint of its rendered
configuration — and the result is recorded as a deployment phase:

```
RENDER → UPLOAD → VALIDATE → ACTIVATE → RELOAD → VERIFY
```

The check is whether *this deployment's configuration* of the site answered: the marker of the
bundle just deployed must come back, reported as `served (v12 3f9a0c51b2de)`. The same site
answering with an earlier marker is reported as *an older configuration is still live*: NGINX
accepted the reload but kept running the configuration before. Anything else answering — a
distribution's default page, another server sharing the port — is reported as answered by
something else, however healthy its status code. NGINX serves the path itself, so a
backend that is down or returns errors does not fail the check; **Check upstreams** covers that.
The agent retries for up to five seconds, because a reload hands over to new workers gradually.
A failure is surfaced, never acted
on: it does not change the deployment's status and nothing is rolled back, because the usual causes
are a dead upstream or a DNS name pointing elsewhere, and reverting fixes neither while discarding
the operator's change. A host that cannot be probed is recorded as SKIPPED rather than FAILED.

**Upstream checks** are on demand, via `GET /api/v1/proxy-sites/{id}/upstream-check` or the
**Check upstreams** button on a site. The agent opens a TCP connection to each upstream and closes
it. Advisory only: NGINX Open Source resolves upstream names once when the configuration loads, so
this catches a typo or a stopped service and promises nothing more.

**Drift detection** runs with the agent heartbeat, every 60 seconds. It compares the bundle the
host reports serving against the one the platform activated. On a mismatch the instance becomes
`DEGRADED`, the drift is audited as `NGINX_INSTANCE_DRIFTED`, and `enginx_instances_degraded`
carries the count.

It never auto-redeploys. Unexpected drift is frequently a person mid-incident, and a platform that
silently reverts them is one they disconnect from the host before they next need it. What it does
do is make repair possible: while an instance is `DEGRADED` the dispatcher skips its "nothing
changed" shortcut, so an operator's redeploy actually redeploys instead of being a silent no-op.

The comparison is skipped while a deployment is in flight for that instance — during a deploy the
two legitimately differ, and a reconciler that reports drift on every deployment is one that gets
switched off.
