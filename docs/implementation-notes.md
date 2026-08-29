# Implementation notes

What changed during construction, and why. Each entry records a decision that departed from the
design in [`../docs/architecture.md`](architecture.md), or a defect worth remembering —
usually one where the obvious implementation was quietly wrong rather than visibly broken.

These are kept apart from the design on purpose. A design document that is edited to match every
discovery reads as though nothing was ever learned, and the reasoning that produced a correction is
the part most worth keeping.

---

## Implementation notes — Phase 3

Two decisions in section 4 changed when the permission model was built. Both are recorded here so
the design and the code do not drift apart.

**Grants are keyed by Keycloak identifiers, not by mirrored row ids.** The ER diagram gives
`permission_grants.subject_id` as a `uuid` referencing `app_users` or `app_groups`. That conflicts
with risk R6, which requires group membership to be read from the access token on every request:
if a grant is addressed to `app_groups.id`, evaluating it forces a lookup in the mirror, and the
staleness R6 exists to prevent comes straight back. The column is therefore `subject_ref`, holding
the `sub` claim for a user grant and the group path for a group grant. Keycloak's group-membership
mapper emits paths rather than stable ids, so renaming a group orphans its grants; that is the
accepted cost, and the permissions screen should warn on a grant whose path no longer resolves.

**`READ_ONLY` is a ceiling only.** Section 4.1 describes it as capping explicit grants at READ. An
early implementation also gave it an implicit READ on every scope, which quietly made every
read-only account a reader of every domain — the exact opposite of what per-domain access is for.
The role now confers nothing on its own and only caps.

**A subject holds at most one grant per scope.** Re-granting changes the level, recorded with the
previous level as the audit `before` state, rather than inserting a second rule. Two grants over
one scope are indistinguishable in effect from the higher of the two, so the lower one would be an
invisible entry in the audit trail.

---

## Implementation notes — Phase 4

**The rendered bundle is built by the dispatcher, not by the request.** Section 6's sequence
diagram shows rendering inside the request transaction, with the dispatcher merely uploading what
was already built. That contradicts risk R2 in section 11, which is the correct reading: two edits
committed moments apart would each snapshot the state their author saw, and applying the earlier
snapshot second would silently revert the later edit. Rendering now happens in the dispatcher,
inside a `pg_advisory_xact_lock` on the instance, so `deployments.config_bundle_id` is null until
that point.

**Validation runs against the candidate through the live symlink.** The include in `nginx.conf`
resolves through `current`, so validating any other tree would validate something other than what
would load. The agent therefore points the symlink at the candidate first, runs `nginx -t`, and
restores the previous target if it fails. The gap is not a risk: NGINX re-reads its configuration
only on reload, and no reload happens in between.

**A pending site is not deployable.** Section 5 said only `EXPIRED` and `DISABLED` sites are
excluded from a bundle. A `PENDING` site has not reached its `active_from` and must not serve
either, or the activation window would mean nothing. The renderer's input is "enabled, and the
window is open", evaluated against the window columns rather than the stored status so that a
bundle is correct even before the Phase 5 sweep has run.

**Idempotency keys are scoped per operation on the agent.** One deployment sends the same key to
`stage` and to `activate`. Keying the agent's replay cache on the header alone let the activate
request replay the stage response, which the management client then read as a failed activation.
The stored key is method plus path plus the header value.

---

## Implementation notes — Phase 5

**Risk R7 is closed.** Spring Boot 4.1.1 manages Quartz 2.5.2 and ships `spring-boot-starter-quartz`;
the clustered JDBC job store works against PostgreSQL under `jakarta.*` with no adjustment. The
ShedLock fallback is not needed. This is asserted by an integration test rather than assumed: the
`QRTZ_*` schema is managed by Liquibase, and a mismatch between it and what Quartz expects would
fail at runtime rather than at compile time.

**The Quartz schema is owned by Liquibase, not by Quartz.** `spring.quartz.jdbc.initialize-schema`
is `never`, because the upstream `tables_postgres.sql` begins by dropping every table it then
creates. Running it at startup would discard the scheduler's state on every boot, which in a
cluster means losing triggers, misfire records and the lock rows that stop two nodes firing the
same job.

**Every bundle now declares a catch-all server.** Section 3 described rendering per site and said
nothing about the default. NGINX treats the first server block it loads as the default server, so
without an explicit one a request whose Host matches nothing is proxied to whichever site sorts
first. That has two consequences, both serious: an expired or deleted domain keeps working, so
expiry has no effect a client can observe; and anyone who points a DNS name at the address reaches
another site's backend. Bundles emit `conf.d/00-enginx-base.conf` with a `default_server` that
returns 404. The HTTPS equivalent needs a certificate of its own and belongs with Phase 6.

**Lifecycle transitions queue a deployment without the one-at-a-time guard.** The user-facing
deploy endpoints refuse while a deployment is already running for that instance, which is a
courtesy against pile-ups. The sweep skips that check deliberately: it has just changed a site's
status in the same transaction, so a refusal would leave the database saying "expired" while NGINX
carried on serving. A second queued deployment is harmless because the dispatcher serialises per
instance and renders current state, so the later one finds nothing to do and reports a no-op.

---

## Implementation notes — Phase 6

**Open question 2 is answered: HTTP-01 only.** It needs nothing but the NGINX hosts the platform
already controls, where DNS-01 would require credentials for a DNS provider. The cost is that
wildcards cannot be issued, and that is enforced before an order is placed rather than discovered
after an authorisation has already spent rate-limit budget. A DNS-capable provider slots in behind
`CertificateProvider` when wildcards are actually needed.

**Challenge responses bypass the deployment path.** Section 3 implied everything NGINX serves comes
from a bundle. An HTTP-01 token has to appear within seconds and is discarded just as fast, so
routing it through render-validate-activate-reload would churn every site's configuration twice per
certificate. The agent writes tokens to a fixed directory outside the release tree, and every
rendered server block — including the redirect server of an HTTPS-only site, and the catch-all —
serves that directory. Without the redirect-server case, forcing HTTPS would leave a site unable to
renew the certificate that made it HTTPS.

**Never-issued certificates are excluded from automatic renewal.** Section 5 described renewal as
driven by the expiry window, which left a certificate with no material permanently "due". In
practice that made the monitoring sweep race an issuance still in flight — the two writers
collided on an optimistic lock — and, once failed, retry on a timer a request that could not
succeed until a human pointed the domain at the platform, spending rate limit each time. First
issuance is explicit; only renewal is automatic.

**The agent no longer refuses to start on an invalid stored configuration.** It previously exited,
which was defensible in isolation and wrong in context: an unresolvable upstream at boot would
leave the host with no agent, so the only way to repair it was to log in by hand — the thing this
platform exists to avoid. It now logs the failure loudly, leaves NGINX stopped, and serves its API
so a corrected bundle can be deployed. Activation starts NGINX when it is not already running.

**A stored ACME account is verified before use.** An authority can retire an account, and a test
server loses them on restart; without the check every subsequent issuance failed with "account not
found" and the only remedy was editing the database.

**The domain purity rule no longer bans `javax..`.** It was a reasonable proxy for Java EE when it
was written, but Jakarta EE moved to `jakarta..` and what remains under `javax` is JDK standard
library. Keeping the ban would have forbidden the domain from reading an X.509 certificate, which
is precisely the framework-free logic the rule exists to protect. The rule now names the frameworks
it actually means, including the ACME and BouncyCastle libraries.

---

## Implementation notes — Phase 7

**Authentication resolves before the router navigates.** The first attempt guarded routes with the
OIDC library's `isAuthenticated$`, which emits `false` before `checkAuth()` has completed. The
guard therefore blocked the very first navigation and the shell rendered signed in with an empty
outlet — a page that looked broken rather than unauthorised. Authentication is now resolved in an
application initializer, so the guard's first answer is the true one.

**The console's permission awareness is presentation, not enforcement.** Section 13 says the
frontend should hide unauthorised actions while the backend remains the source of truth, and that
split is worth being precise about: the console calls `GET /permissions/effective` for a site and
greys out what would be refused, but every action is re-authorised on the request. This was
verified from both directions — the read-only account is offered no mutating control, and a
request made directly with that account's own token is still refused.

**Fonts load at runtime rather than being inlined at build time.** Angular's default inlines
Google Fonts during the build, which makes the build depend on reaching a third party and fails
outright in a restricted CI network. Every rule declares a real fallback stack, so the console is
fully usable if the font never arrives.

**The console's own nginx repeats its security headers in every location block.** `add_header` in
a child block discards every inherited one, and the single-page fallback makes that easy to miss:
`try_files` re-runs location matching, so a request that appears to match `location /` is actually
answered by `location = /index.html`. The headers were silently absent until each block set them.

## Implementation notes — Phase 8

**The audit trail is readable through a port that cannot write.** `AuditSink` (append) and
`AuditLogReader` (search) are separate interfaces with separate adapters, and the JPA repository
behind the reader extends the bare `Repository` marker plus `JpaSpecificationExecutor` — so it has
no `save` and no `delete` to call. Section 7 requires the trail to be immutable from the normal
API; the database trigger enforces that, but the split means a future writer cannot quietly gain
read-and-delete by reusing the nearest available interface.

**`audit_logs` is range-partitioned by month.** The conversion is the awkward part and is done
once. A daily job then creates partitions two months ahead. (The changeset that converted an
existing DEFAULT-only table was dropped when the changelogs were squashed into a baseline — the
baseline creates the partitioned table directly, so the conversion had nothing left to convert.) This is
also what makes retention practical — dropping a partition is instant, whereas `DELETE` on an audit
table is both slow and blocked by the immutability trigger.

**Fleet health is reported at a status that does not take the platform out of rotation.** The
agent, outbox and certificate indicators describe the *managed estate*, not this service. Reporting
an unreachable agent as DOWN would return 503 from `/actuator/health` and cause an orchestrator to
restart or de-register the one component capable of fixing it — monitoring making the incident
worse. They report a custom `WARNING` status mapped to HTTP 200, and readiness is composed
explicitly of `readinessState` and `db` so it can never include them.

**The agent health indicator exposed a gap it did not create.** `last_seen_at` was only ever
written by a deployment, so a working estate with nothing to deploy was indistinguishable from one
where every agent had died, and the indicator would have sat at WARNING permanently until it was
ignored. Phase 8 adds an `instance-heartbeat` job that calls each agent's status endpoint. It
records DEGRADED rather than ONLINE when the agent answers but NGINX is not running or its config
no longer passes `nginx -t`, and on failure it deliberately leaves `last_seen_at` alone — moving it
would erase the very thing the staleness check reads.

**Metrics are cached and their label sets are closed.** One snapshot query serves every gauge and
every health indicator for 15 seconds, because both endpoints are polled by machines and the naive
version scales its cost with how carefully the platform is watched. Nothing is tagged by site or
certificate id: `enginx_certificates_nearest_expiry_days` answers the question a per-certificate
gauge would, with one series instead of an unbounded churning set.

**Rate limiting is tiered by blast radius, not by method.** A flood of reads costs CPU and can be
absorbed; a flood of certificate requests burns an authority's weekly quota, and a flood of deploys
reloads NGINX on live hosts — damage that outlives the request. Sensitive paths are matched as
prefixes rather than annotated per endpoint, so an endpoint added under `/certificates` next year is
limited on the day it is written. The limiter is per instance and keyed on the token's `sub`;
§4 of [`../docs/admin/production.md`](admin/production.md) records why that approximation
was chosen over a shared store, and where an
exact global limit belongs instead.

**The actuator security chain never mapped realm roles.** It required `hasRole(SUPER_ADMIN)` but
configured `oauth2ResourceServer` with the default JWT converter, which produces `SCOPE_*`
authorities and ignores realm roles entirely — so the check could not be satisfied by anyone, and
looked like a working authorization rule. Found by trying to read `/actuator/prometheus` with a
SUPER_ADMIN token. Both chains now use the same converter.

**Two custom filters were being registered twice.** Boot auto-registers every `Filter` bean with
the servlet container, so `IdentityMirrorFilter` — and the new `RateLimitFilter` — ran once ahead of
Spring Security and again inside the chain. Harmless for the mirror, which reads a context that is
empty by then; a real defect for the limiter, which would have keyed on the peer address instead of
the subject. Both are now registered through a disabled `FilterRegistrationBean`, leaving
`addFilterAfter` as the single place either is installed.

## Implementation notes — Phase 9

**The probes run on the host, not on the management server.** R3 implies the management server
checks upstream reachability at save time. It should not, and the reason is not style: the upstream
host and port come from a user, so a management server that dials them on request is a server-side
request forgery primitive aimed at the internal network. It is also on the wrong network to get a
meaningful answer. Both probes therefore live on the agent — which is the process that will open
that connection anyway when NGINX loads the configuration. The site probe additionally always dials
loopback and treats the name purely as a Host header, so it cannot be pointed anywhere at all.

**`VERIFY` asks whether the server responded, not whether it returned 200.** A site with forced
HTTPS answers port 80 with a 301, and a backend may legitimately return 404 or 502 for the site
root. Treating any of those as failure would report every correctly configured site as broken. What
the phase catches is the case a successful reload cannot rule out: the configuration loaded, and
the name is served by nothing.

**A verification failure never fails the deployment.** R3 says surface it rather than act on it,
and the rule lives in `Deployment.verified`, which cannot change `status`. Reverting would not fix
the usual causes — a dead upstream, a DNS name pointing elsewhere — while it would discard the
operator's change. A host that cannot be probed at all is recorded as SKIPPED rather than FAILED:
not knowing whether a site answers is different from knowing that it does not, and conflating the
two makes an agent restart look like an outage.

**Drift detection had to know about deployments in flight.** During a deployment the agent has
already activated the new bundle while the database still records the previous one as active, so a
naive comparison reports drift on every single deploy. A reconciler that cries wolf that often is
switched off within a week, taking the real detections with it. `DriftDetector` returns early when
`hasActiveDeployment` is true, and a test asserts it does not even ask the bundle repository.

**Detecting drift exposed that a drifted host could not be repaired.** The dispatcher's
idempotency shortcut compares the rendered bundle against what the *database* believes is active,
and returns "no change" — which is silently wrong exactly when the host has diverged. A redeploy of
a drifted host did nothing at all. The shortcut is now skipped while an instance is DEGRADED, so an
operator's redeploy actually redeploys. Still never automatic: nothing redeploys on its own, and
this only changes what happens once a person decides to, which is the line R4 draws.

**The HTTPS catch-all needed a certificate that is valid for nothing.** Phase 5 closed this hole on
port 80; it survived on 443 because a TLS server block cannot exist without a certificate. The
agent now generates a self-signed one with no subject alternative names, so a client reaching an
unserved name over HTTPS gets a certificate error — the correct answer, and much better than the
alternative it replaces, which was presenting a real site's certificate and content to a domain
that site does not own. `ssl_reject_handshake` would be tidier but is 1.19.4+, and this must render
for whatever the host runs.

**Ordering constraint when upgrading.** Bundles rendered by a Phase 9 management server reference
`/etc/nginx/enginx/default-tls/`, which only a Phase 9 agent creates. Upgrade agents before the
management server, or the first deployment to an old agent fails validation citing a certificate
nobody configured. The activate handler also calls `Ensure` before validating, which covers a host
whose releases directory was wiped.

**One thing VERIFY found immediately, and did not fix.** A site with `sslEnabled` and
`forceHttps=false` renders only a 443 server block, so the domain is answered on port 80 by the
catch-all with a 404 — neither serving nor redirecting. This is pre-existing renderer behaviour,
not a Phase 9 regression, and changing it would alter what every such site serves. Recorded in the
roadmap as a decision to make rather than silently applied.

## Implementation note — package rename

The Java namespace moved from `dev.enginx` to `net.xiidea.enginx`, and the Gradle group with it.
Mechanical across 265 source files, with three parts that were not.

**Quartz stores fully-qualified class names in the database.** `QRTZ_JOB_DETAILS.job_class_name`
held `dev.enginx.scheduler.*` for every job, so on an existing deployment the scheduler would have
failed to load its jobs on the next boot — and the expiry sweep, the outbox dispatcher and
certificate renewal would all have stopped without the API showing anything wrong. Changeset
a changeset rewrote those rows. It ran during DataSource initialisation, which
is before the scheduler bean starts; `overwrite-existing-jobs` would eventually rewrite them too,
but not before Quartz had already tried to instantiate the old names. The `job_data` blobs were
checked and contain empty JobDataMaps with no package references, so the class name column was the
only thing needing migration. That changeset was dropped in the baseline squash below, along with
every other database it would have been the only reason to keep.

**ArchUnit rules pass vacuously when nothing matches.** The domain purity rules select classes by
package name as a string, so a rename that missed them would leave the suite green while enforcing
nothing — the worst failure mode available to an architecture test, since it keeps reporting success
exactly when it has stopped looking. `DomainPurityTest` now asserts the importer actually found the
domain before the other rules run.

**The problem-type URIs were deliberately left alone.** `https://enginx.dev/problems/...` appears in
`ApiExceptionHandler`, the agent's `problem.go` and the API documentation. Those are a published API
contract — §11 of [`../docs/architecture.md`](architecture.md) says clients should branch
on the `type` URI rather than on prose,
which only holds if the URI is stable. They are a domain name, not a Java namespace, and changing
them is an API break to decide on its own merits rather than a side effect of a package move.

## Implementation note — changelog baseline

The incremental changelogs were collapsed into a single baseline before the first release.

Nothing had been published, so no deployed database's history needed preserving, and the
step-by-step record was describing a past that no running system had ever lived through. Two files
remain: `001-baseline.sql` for the platform's own schema and `002-quartz.sql` for Quartz's vendor
DDL, which is kept separate because it is copied verbatim from the Quartz distribution rather than
written here.

Three things were folded away rather than carried forward. The `ALTER TABLE` statements that added
certificate columns and dropped `chain_pem` became the column list. The DEFAULT-to-monthly audit
partition conversion — detach, create, re-insert, truncate, re-attach — vanished entirely, because
the baseline creates the partitioned table with monthly partitions from the start. And the Quartz
job-class rename went with it, since a fresh database has no rows to rename.

**Verified by schema diff, not by inspection.** The old changelogs were applied to a database, a
semantic snapshot taken — every column by name, every constraint definition, index definition,
trigger and function — then the volume destroyed, the baseline applied to an empty database, and the
snapshot repeated. The two are byte-for-byte identical across 452 lines. Hibernate's
`ddl-auto: validate` passing on boot is a second, independent check that the schema still matches
every entity mapping.

**The cost, and who pays it.** Any existing database is now unusable: its `databasechangelog` names
changesets that no longer exist, and the baseline would try to create tables that are already there.
Developers wipe their volume (`docker compose down -v`), which is the whole point of doing this
before anyone outside the project has a database worth keeping. Testcontainers builds a fresh schema
per run and needed no change.

## Implementation notes — Phase 10

**Deduplication is a unique constraint, not a check-then-write.** Claiming a notification is a
single `INSERT ... ON CONFLICT DO NOTHING`. The first attempt used `saveAndFlush` and caught the
constraint violation, which passed five of six tests and failed the sixth: a failed statement marks
its transaction rollback-only, so catching the exception and returning normally makes the *commit*
fail instead, with an `UnexpectedRollbackException` surfacing at the caller well away from anything
that explains it. Letting PostgreSQL arbitrate silently keeps it to one statement with no exception
control flow, and makes two schedulers racing impossible rather than merely unlikely.

**A fingerprint re-arms notifications; nothing clears a flag.** Each ledger row records a value
identifying the underlying fact — a site's expiry instant, a certificate's expiry, the last time an
agent was heard from. Extending a site's expiry changes the fingerprint, so its warnings are due
again for the new date. The alternative, deleting ledger rows when a site is updated, works until
someone adds a second way to change an expiry and does not know to clear the flag.

**The drift fingerprint is the expected bundle, not `lastSeenAt`.** A degraded host is still
answering, so `lastSeenAt` advances on every heartbeat and would re-send the notification every
minute. The bundle the platform expects it to serve is stable for as long as the drift lasts, and
changes exactly when a successful deployment ends the episode.

**Claim before sending, never after.** A crash between the two loses one notification. Claiming
afterwards would instead re-send on every scan until a send finally succeeded, which for a condition
lasting four days is thousands of messages. A lost notification is recoverable — the condition is
still in the metrics, the health endpoint and the dashboard. Burying the recipients is not: they
stop reading the channel, and then the next real one is missed too.

**The logging channel is always enabled, and reports success.** It exists so that a notification is
never lost silently. In development there is no SMTP server, so without it a scan would find a real
problem, mark it sent, and deliver it nowhere — and because the ledger deduplicates, never send it
again.

**Two rounding rules, deliberately opposite.** The threshold is chosen by rounding *up*: "within 3
days" must not be claimed for something 3 days and 1 hour away. The number in the message rounds
*down*, because telling someone a site expires "in 3 days" when it expires in 2 days and 1 hour is
untrue. A count of zero is phrased as "today" rather than "in 0 days", which reads as a bug at
exactly the moment the message is most urgent.

**An empty property is not an absent one.** `@ConditionalOnProperty` matches on a property
existing, and an unset environment variable behind a placeholder default leaves an empty string.
The webhook channel was therefore created for a deployment with no webhook, and recorded a failure
against itself on every notification. Found live, in the ledger's own record of what happened —
which is a fair advertisement for storing the delivery outcome rather than only the fact of sending.

## Implementation notes — Phase 11

**Half of R5 was already built, and untested.** The rule that a pattern grant requires authority
over a namespace containing it — `levelOverNamespace`, backed by `DomainPattern.isCoveredBy` — was
implemented in Phase 3 and had no test of its own. That is the worst state for an authorization
rule: nothing would report it if a refactor quietly widened it. `NamespaceAuthorityTest` now pins
the escalation it prevents, including the case that matters most, ADMIN over `*.test.example.com`
conferring nothing over `*.example.com`.

**The preview is authorised exactly like the grant.** Seeing the reach of a grant you are entitled
to make discloses nothing you could not disclose to yourself by making it, so `preview` runs the
same `requireGrantPermission` check rather than a second, weaker one that could drift from it. A
caller who cannot make the grant gets 403 from the preview too.

**The scope is expressed as an `AccessScope`.** A grant's reach and a listing's predicate are the
same question asked from two directions, so the preview builds the scope the grant would confer and
hands it to the ordinary site search. Nothing new had to learn how patterns match.

**The preview reports what is new, not only what matches.** A grant that duplicates access the
subject already holds is a different decision from one opening a namespace to them for the first
time, and only the second is worth pausing over. `coversFutureDomains` is the caveat that keeps the
list honest: a wildcard is a rule, and the list is a snapshot of it.

**Retention defaults to keeping everything.** Silently destroying an audit trail because nobody set
a property is a worse failure than an unbounded table, and it is the kind discovered during an
investigation. Dropping is also itself audited, so a gap in the trail is never ambiguous between
"policy" and "someone with database access".

**A regex that matched nothing made retention a silent no-op.** The drop function parsed each
partition's upper bound from its own definition, with a date-shaped pattern. The partition key is
`timestamptz`, so PostgreSQL renders bounds as `'2024-02-01 00:00:00+00'` and the pattern matched
nothing at all — the function ran, reported success, and dropped nothing. Only one of the five
tests caught it; the other four asserted that nothing unwanted was dropped, which a no-op satisfies
perfectly. They now assert that something *was* dropped as well, so none of them can pass against
an implementation that does nothing.

## Implementation notes — Phase 12

**The challenge type is chosen by what the authority offers, not by inspecting the domain.** The
first version checked the identifier for a wildcard and routed accordingly. It failed on the first
real order, because RFC 8555 reports a wildcard authorization under its *base* domain with a
separate flag — `*.wild.example.com` arrives calling itself `wild.example.com`, so the check never
fired and the request was sent down HTTP-01, which no authority offers for a wildcard. Asking which
challenges are on the table is both simpler and impossible to get subtly wrong. It also handles the
mixed order correctly: `example.com` validates over HTTP and `*.example.com` over DNS, in one order.

**The KEK seam had to change shape before a KMS could implement it.** `KekProvider` returned the
key-encryption key itself, which can only ever be satisfied by something willing to hand its keys
out — and a hardware module, AWS KMS, GCP KMS and Vault's transit engine all specifically refuse
to. R1 anticipated "a Vault/KMS implementation" behind that interface, but the interface as written
could not have one. It now exposes wrap and unwrap, so the key can stay where this process never
sees it. Verified against a real Vault whose transit key reports `exportable: false`.

**Switching provider had to be survivable, which needed two things.** `RoutingKekProvider` writes
with the configured provider and reads with whichever one recognises the key id a secret records,
so old secrets stay readable during a migration. And `SecretRewrapService` moves them, because
without it the schema's promise that "a rotation can proceed gradually" was never true: nothing
re-wrapped anything, so a rotation could begin and never finish and the old key could never be
retired. The whole path was exercised on the live stack — env-wrapped secrets, restart writing to
Vault, re-wrap, then restart with no environment key configured at all and issue a certificate,
which proves the ACME account key is readable from Vault alone.

**Re-wrapping only certificates would have left one row holding the old key hostage.** The ACME
account key is wrapped too, and a migration that missed it would keep the previous key required
forever — invisibly, until someone deleted it and issuance stopped. `SecretRewrapTarget` collects
every store, so a future one is migrated by being registered rather than by someone remembering.

**A read-only transaction silently discarded the ACME re-wrap.** The scan runs
`@Transactional(readOnly = true)` and the store's own `@Transactional` joined it, so the write was
counted and then thrown away at commit — reporting a successful migration that had not happened,
which is precisely how an old key gets deleted while something still needs it. REQUIRES_NEW, as
everywhere else that writes from inside a scan.

**An empty property is not an absent one, again.** `@ConditionalOnProperty` matched
`enginx.crypto.vault.address` because the placeholder leaves an empty string, so the Vault provider
was created for every deployment without Vault and failed startup demanding a token. The same trap
caught the notification webhook in Phase 10. Both now use `@ConditionalOnExpression` testing for
content, and this is worth treating as a rule rather than as two incidents.

**`@ConditionalOnMissingBean` does not work on a scanned component.** The null-object DNS publisher
carried it and won or lost depending on scan order, which showed up as a context that sometimes
failed to start. Conditions of that kind belong on a `@Bean`, which is processed after scanning.

**Agent certificate rotation was previously delete-and-re-register.** That discards an instance's
deployment history and its link from every site on it, to change one field. `PUT
/agent-certificate` changes the field, resets the instance to UNKNOWN — what the platform knew was
learned through a certificate it no longer trusts — and audits both fingerprints. There is a window
either way round, and it is a loss of control rather than of traffic: the host keeps serving
throughout, it simply cannot be changed until the two agree.

## Implementation notes — Phase 13

**The published OpenAPI document was wrong for seventeen endpoints.** Four controllers each declare
a nested record called `Response`, and springdoc names schemas by simple class name — so
certificates, deployments, permissions and domain groups all collided on one `Response` schema,
which ended up carrying the domain-group fields. Anyone generating a client from that document got
entirely incorrect types for those endpoints, silently. Found by generating a client, which is a
fair argument for improvement #7 being worth more than the drift it was proposed to catch. Fixed
with an explicit `@Schema(name = …)` on each, which is local and needs no code renamed.

**Generated types are only useful if the spec carries nullability.** springdoc marks every field
optional, because a Java record says nothing about nullness, so the first generated client typed
all 27 fields of a site as possibly-undefined and produced 85 compile errors in a console that was
correct. The fix is one `requiredProperties` list per response DTO — required meaning always
present *and* never null — which is a single annotation per type rather than one per field.
Genuinely nullable fields stay optional on purpose: that is the part a generated client must
handle.

**Requests and responses need different requiredness, even from one record.** `UpstreamDto` is used
in both directions. Marking `weight` and `maxFails` required was right for a response and wrong for
a request, where omitting them is how a client asks for the server's default — the generated client
began demanding values the API does not. Only the three fields that must always be supplied are
required, and the console's request types deliberately keep the generated optionality.

**One convention difference, bridged once.** The schema says an absent field is `T | undefined`; the
console has always said `T | null`. Both mean the same thing, and for this server the console's is
more accurate, since Jackson serialises every record component — the key is present, the value is
null. A single `Wire<T>` mapped type converts at the boundary rather than scattering `?? null`
through nineteen files.

**The drift detection was tested by causing drift.** Renaming `domain` to `domainName` in the spec
and regenerating breaks the console build with a precise error; restoring it fixes it. A guard that
has never been seen to fire is a guard nobody should trust — this project has already shipped two
that did not (a vacuous ArchUnit rule, and a release check that grepped a binary for a version
string that was present either way).

**Release verification runs the binaries.** The obvious check — grepping each binary for the version
— passes on an *unstamped* build of this agent, measurably: the bytes turn up elsewhere in a Go
binary. `go version -m` does not record `-ldflags` either. QEMU is registered so every
cross-compiled binary is executed and asked what it is, which is the only check that distinguishes
a stamped build from one where the `-X` path silently matched no package.

**Gradle discarded `-Pversion` on subprojects.** The conventions plugin assigned `version` after
Gradle had already applied the command-line property, so a release would have produced a jar named
for the tag whose manifest said SNAPSHOT. It now only sets a default.

## Implementation note — naming

The Go module is `github.com/xiidea/enginx/enginx-agent` and the directory matches it. The rename
touched more than the module path, and each of the rest was a separate decision rather than a
mechanical consequence:

**Images moved together.** `enginx/nginx-agent` became `xiidea/enginx-agent`, and the management
server and console moved with it, to `xiidea/enginx-management` and `xiidea/enginx-console`.
Renaming only the agent would have replaced one inconsistency with a worse one — two images left
under a namespace the project no longer uses.

**The binary inside the image is `enginx-agent`.** It was `nginx-agent`, while the release
artefacts were already `enginx-agent-<os>-<arch>`, so the same program had two names depending on
how it was delivered.

**The development agent certificate is `CN=enginx-agent`**, with `enginx-agent` in its SAN list.
Neither is load-bearing — the management server pins the fingerprint, and it is the *client* CN
(`enginx-management`) the agent checks — but a certificate whose subject names a component that no
longer exists is a small lie in the one place a reader is trying to establish identity.

Changing it regenerates the PKI and therefore the agent's fingerprint, which the platform pins. The
sequence is the one Phase 12.3 added the rotation endpoint for, and it behaved as designed: the
heartbeat reported OFFLINE within one interval, `PUT /nginx-instances/{id}/agent-certificate`
trusted the new digest, and the instance returned to ONLINE at the next heartbeat. An existing
deployment could not have done this without deleting and re-registering the instance.

## Implementation note — local users, and optional OIDC

Keycloak was a hard dependency: the application would not start without a reachable issuer, which
made the smallest useful deployment a two-service one. Local accounts remove that, and the OIDC
path became a switch rather than an assumption.

**Local login issues a JWT rather than creating a session.** Every authorization path already
begins with a validated JWT, so a session would have meant a second path — and a second path is one
that only some of the tests exercise. The token's claims mirror the provider's exactly
(`sub`, `preferred_username`, `email`, `realm_access.roles`, `groups`), so the converter, the
permission evaluator and every `@PreAuthorize` are unchanged and unaware of which issuer signed.

**HS256, not a keypair.** This service is both the only issuer and the only verifier of these
tokens. A keypair would buy third-party verification nothing needs, and cost key distribution
across replicas. The consequence is that `AUTH_JWT_SECRET` must match across replicas, and rotating
it revokes every local session — which is the only way to revoke them all, since the tokens are
self-contained.

**A local subject is `local:<uuid>`.** This is the one invariant that keeps the two providers safe
to run together. `permission_grants.subject_ref` holds an OIDC `sub`, which is a bare UUID; an
unnamespaced local id could match a grant written for somebody else, and nothing would report it.

**Choosing the verifier by the unverified `iss` claim.** With both providers on there are two
decoders, and the token has to be routed to one of them before anything about it is trusted.
Reading `iss` to make that choice is safe because the chosen decoder still validates the
signature: a token claiming to be local is verified against the local secret, and fails.

**The must-change-password flag is enforced by a filter, not by the console.** It began as a claim
the console read to show a password form first. That is a suggestion — the token is a bearer token
and curl works just as well. It matters most for the bootstrap administrator, whose password comes
from configuration and has therefore been readable by everything that can read configuration. The
filter confines such a token to exactly one request: a `PUT` to *its own* password, matched against
the subject in the token. Allowing "any password change" instead would have handed whoever read the
bootstrap password out of a manifest the ability to take over an administrator who had already
rotated.

**Changing your own password requires the current one, even though you are already holding a valid
token.** A stolen token should not be enough to take permanent ownership of the account it was
stolen from. An administrator's reset does not require it, and must not: a reset exists precisely
for the case where the old password is unavailable.

**One rejection message, and a dummy hash.** Wrong password, no such account and disabled account
return the same message, and the missing-account path still runs bcrypt against a fixed hash so the
three take the same time. Either difference on its own turns the login form into an account
enumerator — which is how a credential-stuffing list gets filtered down to the accounts worth
attacking.

**The last enabled `SUPER_ADMIN` cannot be deleted or disabled.** Cheap to check, and only ever
wrong in the direction of making someone create a second administrator first. Without it the
remedy for the obvious mistake is an `INSERT` with a bcrypt hash produced by hand.

**Bootstrap runs on an empty user table, not on a missing bootstrap user.** The narrower condition
would recreate an account an administrator deliberately deleted, and resurrect one they disabled.

**The console asks the server which methods exist** (`GET /api/v1/auth/methods`) before rendering
anything. Offering a form for a method that is switched off produces a login that always fails with
nothing on screen to explain why. Two smaller consequences followed: the route guard had been
reading the OIDC library's session, which is invisible to a local one and turned local users away
from every route; and the local token needs its own interceptor, which attaches it only to the API
base — a bearer token sent to any other host is a credential handed to a third party on every
request.

## Deviations from the original schema


Recorded here rather than silently applied:

1. **No `citext`.** `DomainName` normalises to lowercase in its constructor, so a
   case-insensitive column type would be redundant. Plain `varchar` plus a check constraint
   asserting `domain = lower(domain)`.
2. **`audit_logs` primary key is `(id, occurred_at)`.** PostgreSQL requires the partition key in
   the primary key of a partitioned table.
3. **`ip_address` is `varchar(64)`, not `inet`.** It stores a value taken from
   `X-Forwarded-For`, which is a claim by a proxy rather than an address the database should
   validate.
4. **`proxy_sites.last_deployment_id` is not created yet.** It references `deployments`, which
   Phase 4 introduces.
5. **Java 25, not 21.** Requested during Phase 2.
6. **Grants are keyed by Keycloak identifiers, not mirrored row ids.** Phase 1's ER used a
   `uuid subject_id` into `app_users` / `app_groups`. But authorization must read group
   membership from the token on every request (risk R6), and keying a grant to a mirrored row
   would make every decision depend on that mirror being fresh — reintroducing exactly the
   staleness R6 exists to prevent. `subject_ref` therefore holds the `sub` claim for a user
   grant and the group path for a group grant. The mirror survives for grant authoring and
   display only. Cost: renaming a group in Keycloak orphans its grants, because the
   group-membership mapper emits paths rather than stable ids.
7. **`deployments.config_bundle_id` is nullable.** Phase 1 had it mandatory, which assumed the
   bundle existed when the deployment was created. Rendering moved to dispatch time to close
   risk R2, so a queued deployment has no bundle yet.
8. **No IPv6 `listen` directives.** `nginx -t` does not bind sockets, so `listen [::]:80` passes
   validation on a host without IPv6 and then fails at reload — precisely the failure this
   platform exists to prevent. IPv6 belongs to a per-instance capability flag, not to every
   rendered site.
9. **A PENDING site is not deployable.** [`../docs/architecture.md`](architecture.md) §5
   said only EXPIRED and DISABLED sites are
   excluded from a bundle. A site before its `active_from` must not serve either, or the
   activation window would mean nothing.
10. **`certificates.chain_pem` was dropped.** One column holds the full chain, leaf first, as
    NGINX requires. Two columns invited the halves to drift apart and to be assembled in the wrong
    order at deployment time — a failure that only appears when a client validates.
11. **No HTTPS catch-all server yet.** The HTTP one returns 404 for unmatched hosts. Its TLS
    equivalent needs a certificate of its own; until then an unmatched SNI is served the first
    site's certificate and the client rejects the name mismatch, so the connection fails closed
    rather than being proxied.
12. **No `ltree`.** Group hierarchy is a materialised path in `varchar` with a
   `text_pattern_ops` index. Descendants are a prefix scan; ancestors are computed by splitting
   the path and need no query at all. Same performance for this shape of query, no extension to
   install with privileges a managed database may not grant, and no custom Hibernate type.
