# Roadmap

**Phases 9 through 13 are complete.** The roadmap is finished; what remains is the two open
decisions below.

Phases 1–8 are complete. What follows is everything named as outstanding in
[`../docs/architecture.md`](architecture.md) §11, plus the Phase 1 open questions that were
never answered and so were never built.

Phases are ordered by what it costs to defer them, not by what is most interesting. Two things
drive that ordering: schema decisions get more expensive with every phase that assumes the current
shape, and a gap in *verification* is worth more than a new capability, because it decides whether
you can trust what the platform already reports.

---

## Open decisions

Two questions were deliberately left to the project owner rather than answered by whoever happened
to be writing code. Neither blocked any phase — but the first grows more expensive with every
client written against the current API.

### Can a site target more than one NGINX host?

Today `proxy_sites.nginx_instance_id` is a single non-null column, so an HA pair — the same domain
served by two hosts behind a load balancer — cannot be expressed.

The engine barely notices the change; the cost is almost entirely at the edges:

| Area | Impact |
|---|---|
| Renderer | None. It already renders per instance from `findDeployableForInstance` |
| Deployment | Small. The outbox and dispatcher are already per-instance; one site change fans out to N |
| Permission model | None. Grants are scoped by domain, never by instance |
| Schema | `proxy_sites.nginx_instance_id` becomes a `proxy_site_instances` join table |
| REST API | **Breaking.** `nginxInstanceId` appears in request and response DTOs across 24 backend files |
| Console | 17 references; an instance picker becomes a multi-select |

That API break is the whole cost, and it only grows: every client written against the current shape
is another thing to migrate.

**Recommendation.** If an HA pair is ever wanted, take the API break now, while the only client is
the console in this repository. If one-host-per-site is genuinely the model, record that in
[`architecture.md`](architecture.md) and close the question — leaving it open invites the same
discussion every few months.

### What should `forceHttps=false` mean for port 80?

Found by the `VERIFY` phase on its first live run. A site with `sslEnabled` and `forceHttps=false`
renders **only** a 443 server block, so port 80 for that domain is answered by the catch-all with a
404 — neither served nor redirected. The toggle currently chooses between *redirect HTTP to HTTPS*
and *HTTP is a 404*; it never means *serve both*, which is the reading most operators would give it.

| `forceHttps=false` could mean | Consequence |
|---|---|
| Serve both 80 and 443 | The natural reading. Changes what every such site serves today |
| Serve 443 only | Current behaviour. The toggle's name is then misleading |
| Redirect 80 → 443 | Already what `forceHttps=true` does |

**Recommendation.** The first, with the toggle renamed to say what it does. Not applied
unilaterally, because it changes live traffic for every SSL-enabled site.

---

## Phase 9 — Deployment truth — **complete**

Closed R3 and R4. The theme was one question: *does reality match what the platform believes?*
Before this, a deployment reported SUCCESS when NGINX reloaded — which proves the configuration is
syntactically valid and loaded, not that the site answers, and not that it still matches the
database tomorrow.

**9.0 — CI.** `.github/workflows/ci.yml`, three independent jobs (management, agent, console) so a
failure names its own area. The agent job runs `gofmt -l`, `go vet` and `go test -race`; the
management job runs `./gradlew build`, which covers the ArchUnit module rules, the golden-file
renderer tests and the Testcontainers integration tests.

**9.1 — `VERIFY` phase.** The agent gained `POST /agent/v1/verify`. After a successful reload the
dispatcher asks the host whether it answers for each deployed name, and records the result as a
VERIFY event. The check is *did the server respond*, not *did it return 200*. It never changes the
deployment's status — see the Phase 9 implementation notes for why, and for what happens when the
host cannot be probed at all.

**9.2 — Upstream reachability.** `POST /agent/v1/upstream-checks` on the agent, exposed as
`GET /api/v1/proxy-sites/{id}/upstream-check` and offered as a button on the site page. Built as
its own endpoint rather than a step inside create and update: a probe on the write path adds a
round trip to every save, and a check that can block a save will eventually block a legitimate one
at the moment an operator is trying to fix an outage.

**9.3 — Reconciler (R4).** Folded into the `instance-heartbeat` job, which already fetched
`activeBundleId` every 60 seconds and discarded it. On mismatch the instance becomes DEGRADED, the
drift is audited as `NGINX_INSTANCE_DRIFTED`, and `enginx_instances_degraded` exposes the count.
Never auto-redeploys.

**9.4 — HTTPS catch-all.** The agent generates a self-signed certificate with no subject
alternative names at startup and before every activation; the renderer emits
`listen 443 ssl default_server` returning 404. An unserved name over HTTPS now gets a certificate
error instead of another site's certificate and content.

**Also fixed, found by 9.3.** The dispatcher's idempotency shortcut compared the rendered bundle
against the *database's* belief, so redeploying a drifted host was silently a no-op — drift was
detectable but not repairable. The shortcut is now skipped while an instance is DEGRADED.

### Found by Phase 9, still undecided

`VERIFY` discovered on its first live run that a site with `sslEnabled` and `forceHttps=false`
renders only a 443 block, so port 80 is answered by the catch-all with a 404. Written up under
[Open decisions](#what-should-forcehttpsfalse-mean-for-port-80) above. Not changed as part of
Phase 9: it alters live traffic for every SSL site, which is a decision rather than a fix.

## Phase 10 — Notifications — **complete**

Improvement #6, plus the subsystem it needs. Silent expiry at 23:59:59 generates incidents, and
that is only the most obvious case — certificate renewal failing quietly looks exactly like
renewal working, right up to the browser error.

**10.1 — Notification subsystem.** A `NotificationPort` in the application layer with channel
implementations in infrastructure: SMTP via `spring-boot-starter-mail`, and an outbound webhook.
Both behind the port, so adding Slack later touches one module.

**10.2 — Events worth sending.** Site expiring at 7/3/1 days; site expired; certificate expiring;
certificate renewal failed; instance offline; instance drifted (from 9.3); outbox message dead.
The last four already have health indicators — this gives them a push channel rather than
requiring someone to be looking.

**10.3 — The trap that decides whether this is useful.** Every one of these fires from a job that
runs on a timer. Without a record of what has already been sent, the 7-day warning is re-sent every
hour for four days and every recipient filters the whole channel into a folder they stop reading.
Needs a `notifications_sent` table keyed by `(event_type, resource_id, threshold)`, with the key
reset when the underlying fact changes — extending a site's expiry must re-arm its warnings.

**10.4 — Recipients.** Genuinely an open question, and worth answering deliberately rather than
defaulting: a site's `created_by`, the holders of a grant over its domain, or a configured
operations address. My recommendation is a configured address for estate-wide events (instance,
outbox) and the site's owner plus that address for per-site events, because estate events have no
natural owner.

Every notification should be audited. It is an outbound action taken on a user's behalf.

*Rough size: a week. Most of it is 10.3 and 10.4, not the sending.*

---

## Phase 11 — Governance — **complete**

Two items that share a theme: making authority explicit and bounded.

**11.1 — Wildcard grant scoping (R5).** Grant expiry was implemented in Phase 3 and is surfaced in
the console. Two mitigations remain:

- **Show what a pattern currently matches, before the grant is created.** `*.example.com` at
  `MANAGE` silently covers every future subdomain, and the person granting it usually has not
  enumerated what that means today, let alone next year.
- ~~**Require `ADMIN` at an equal-or-broader scope to create a pattern grant.**~~ Already
  implemented in Phase 3 via `levelOverNamespace` — but it had no test, which for an authorization
  rule is close to not having it. Phase 11 added `NamespaceAuthorityTest` rather than the rule.

**11.2 — Audit retention.** Partitioning makes this cheap — [`../docs/admin/production.md`](admin/production.md) documents the
`DETACH`/`DROP` procedure and it is instant — but nothing enforces a policy, so the trail grows
forever until a person intervenes. Add a configurable retention window and a job that detaches and
drops partitions past it, with an archive hook that runs first. Default to *no* retention limit:
silently deleting an audit trail because a default said so is worse than an unbounded table.

*Rough size: two to three days.*

---

## Phase 12 — Certificate and key capability — **complete**

Three items that all answer "what can this platform be trusted with in a real organisation?"

**12.1 — DNS-01 and wildcards.** `AcmeCertificateProvider` explicitly refuses a wildcard request
today with a message saying DNS-01 is not configured. A `DnsChallengeProvider` port behind the
existing ACME flow, with a Route 53 or Cloudflare implementation. This unblocks `*.example.com`
certificates, which in turn makes the wildcard grants of 11.1 useful in practice.

**12.2 — Production `KekProvider`.** The interface exists exactly as R1 recommended, and
`EnvironmentKekProvider` is still the only implementation. Add Vault or a cloud KMS. The seam was
the expensive part and it is already paid for; this is the phase that makes the envelope
encryption story true for an organisation that will not accept a key in an environment file.

**12.3 — Agent PKI rotation.** Today agent certificates come from `generate-dev-certs.sh` or your
own CA, and the platform neither issues nor rotates them — rotation means re-registering an
instance and copying a fingerprint by hand. At minimum, a re-registration flow that does not
involve copying a fingerprint by hand. Ideally the platform issues and rotates them, which was
Phase 1 open question 4 and is still unanswered.

*The external dependencies turned out to be avoidable for verification: Pebble's challtestsrv
provides real DNS-01 against a real authority, and Vault runs locally in dev mode. Both were
exercised end to end rather than written blind. What is **not** included is a cloud DNS provider —
Route 53, Cloudflare — because there is no way to verify one here, and untested code that
manipulates DNS records is worse than an honest gap. `DnsChallengePublisher` is the seam; an
implementation is a single class.*

---

## Phase 13 — Delivery — **complete**

**13.1 — OpenAPI-generated TypeScript client (improvement #7).**
`frontend/src/app/core/api/models.ts` is hand-written and can drift from the server's DTOs with
nothing failing to warn you. Generate it from the OpenAPI document in CI — which exists as of 9.0.

**13.2 — Kubernetes manifests or a Helm chart.** The brief required the architecture to *allow* a
later Kubernetes deployment, not to ship manifests, and it does: the management plane is stateless
with real liveness and readiness probes, agents are a DaemonSet on the nodes that serve traffic,
and secrets are already read as files through a config tree, which is exactly how a mounted Secret
presents itself. [`../docs/admin/production.md`](admin/production.md) §7 describes the shape. This phase writes it down as manifests.

*Rough size: a few days.*

---

## Sequencing at a glance

| Phase | Theme | Closes | Blocked by |
|---|---|---|---|
| — | Multi-instance decision | Open question 1 | — |
| 9 | Deployment truth | R3, R4, CI, HTTPS catch-all | **Complete** |
| 10 | Notifications | Improvement 6 | **Complete** |
| 11 | Governance | R5, retention | **Complete** |
| 12 | Certificate and key capability | R1, open questions 2 and 4 | **Complete** |
| 13 | Delivery | Improvement 7 | **Complete** |

Phase 11 depends on nothing and can be pulled forward if the wildcard grants are already in use.
Phase 13 is the only one left, and depends on nothing but time.

## Deliberately not planned

**A UI config editor for raw NGINX directives.** Requested by users of every platform like this,
and refusing it is the reason this one can claim configuration injection is prevented. The
`ProxySiteSpec` value object is the only input to the renderer specifically so that there is one
boundary to defend rather than many.

**Auto-remediation of drift.** Named here so it is a decision rather than an omission — see 9.3.
