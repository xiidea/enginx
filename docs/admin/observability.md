# Observability

The audit trail, metrics, health and notifications — what the platform tells you, and when.

## Audit, metrics and health

### The audit trail

`GET /api/v1/audit-logs`, and the **Audit log** page in the console. Global `SUPER_ADMIN` only —
the trail spans every domain in the estate, so it is not scoped by domain grant.

```
?actor=alice&from=2026-08-20T00:00:00Z&to=2026-08-27T00:00:00Z
?result=DENIED
?action=CERTIFICATE_ISSUED&action=CERTIFICATE_FAILED
?resourceType=PROXY_SITE&resourceId=<uuid>
```

There is no write endpoint and no delete, and that is structural rather than conventional: reading
and appending are separate ports with separate adapters, the read repository has no `save` or
`delete` method to call, and the table has a trigger that raises on `UPDATE` and `DELETE`.

The table is range-partitioned by month, with partitions created two months ahead by a daily job.
Bounded `from`/`to` values let PostgreSQL skip whole partitions, so a time range is worth passing
whenever you have one.

### Metrics

`/actuator/prometheus`, behind `SUPER_ADMIN`. Gauges cover sites and certificates by status,
pending and failed deployments, outbox depth and dead messages, unreachable instances, and days
until the nearest certificate expiry. Nothing is tagged by site or certificate id — one snapshot
query serves every gauge for 15 seconds, and label sets are fixed at startup.

### Health

| Endpoint | Question |
|---|---|
| `/actuator/health/liveness` | Is the process alive? |
| `/actuator/health/readiness` | Can this instance serve? Database and application state only |
| `/actuator/health/platform` | Is the estate well? Agents, outbox, certificates |

The estate indicators report a custom `WARNING` status that maps to HTTP 200, and readiness is
composed explicitly so it can never include them. An unreachable agent is a real problem, but it
must not cause an orchestrator to restart the one service capable of fixing it.

An `instance-heartbeat` job polls each agent every 60 seconds. Without it `last_seen_at` would only
advance on deployment, and a quiet estate would be indistinguishable from a dead one.

## Notifications

Everything below is already visible in health and metrics. This is the push channel for the subset
with a deadline: by the time an expired certificate shows up on a dashboard, the browser error has
already happened.

| Condition | Sent when |
|---|---|
| Site expiring | 7, 3 and 1 days out, configurable |
| Site expired | It has been taken off the air |
| Certificate expiring | Same thresholds |
| Certificate renewal failed | Issuance or renewal did not produce a certificate |
| Host unreachable | An agent has stopped answering |
| Host drifted | A host is serving something the platform did not deploy |
| Outbox message dead | A change was accepted by the API and will never arrive |

**Channels.** Email when `spring.mail.host` is set, an outbound webhook when
`enginx.notifications.webhook-url` is, and always the application log. The log channel is not a
placeholder: it guarantees a notification is never lost silently, which matters because the ledger
deduplicates and a message delivered nowhere would never be sent again.

**Recipients.** The configured operator addresses, plus the site's or certificate's owner when
`notify-owner` is on. Owner addresses come from the identity mirror — display only, never
authorization, so a stale mirror can misdirect an email and can never widen access. Estate-wide
conditions have no owner, which is why the operator list is not optional.

**Sent once.** A ledger row is claimed with `INSERT ... ON CONFLICT DO NOTHING` before anything is
sent, so a condition lasting four days produces one message rather than a hundred, and two
schedulers cannot both send. Each row records a *fingerprint* of the underlying fact — the expiry
instant, the last contact time — so changing that fact re-arms the notification. Extending a site's
expiry makes its warnings due again for the new date, without any update path knowing notifications
exist.

```yaml
enginx:
  notifications:
    operator-addresses: ops@example.com,platform@example.com
    expiry-thresholds: 7,3,1
    webhook-url: https://hooks.example.com/enginx
```

## Audit retention

Off by default. Set `enginx.audit.retention-months` and the daily maintenance job drops whole
monthly partitions that have fallen outside the window:

```yaml
enginx:
  audit:
    retention-months: 24
```

Dropping a partition is instant and never touches a row, which is the reason the table is
partitioned at all — the immutability trigger forbids `DELETE`, so there would otherwise be no way
to enforce retention without giving up the guarantee that makes the trail worth keeping.

Two things it will not do: touch `audit_logs_default`, where rows land only when their timestamp
falls outside every monthly range and something unexpected has happened; and delete quietly — the
deletion is recorded in the trail, so a gap is never ambiguous between policy and someone with
database access.

## Rate limiting

Per authenticated subject, keyed on the token's `sub` so refreshing a token does not grant a fresh
allowance. Three tiers, chosen by what a request can damage rather than by its method:

| Tier | Default | Covers |
|---|---|---|
| `READ` | 300/min | Every safe method, whatever it reads |
| `WRITE` | 60/min | Ordinary changes |
| `SENSITIVE` | 10/min | Certificates, deployments, permissions, instance registration |

Exceeding a tier returns `429` with an RFC 9457 body, `Retry-After` and `RateLimit-*` headers. The
sensitive tier exists because those requests burn a certificate authority's weekly quota or reload
NGINX on live hosts — damage that outlives the request being shed.

The limiter is per instance, so N replicas allow up to N times the configured rate. That trade, and
where to put an exact global limit instead, is explained in
[production.md](production.md#4-rate-limiting).

## Choosing which channel carries what

Every channel carries every kind by default. `enginx.notifications.routing` narrows that per
channel, so the conditions a person should read about by email are separable from the ones a chat
or incident tool should pick up:

```yaml
enginx:
  notifications:
    routing:
      mail:    SITE_EXPIRING,SITE_EXPIRED,CERTIFICATE_EXPIRING,CERTIFICATE_RENEWAL_FAILED
      webhook: INSTANCE_OFFLINE,INSTANCE_DRIFTED,OUTBOX_MESSAGE_DEAD
      log:     SITE_EXPIRED,OUTBOX_MESSAGE_DEAD
```

or by environment: `NOTIFICATION_ROUTE_MAIL`, `NOTIFICATION_ROUTE_WEBHOOK`, `NOTIFICATION_ROUTE_LOG`.

The channels are `mail`, `webhook` and `log`. The kinds are `SITE_EXPIRING`, `SITE_EXPIRED`,
`CERTIFICATE_EXPIRING`, `CERTIFICATE_RENEWAL_FAILED`, `INSTANCE_OFFLINE`, `INSTANCE_DRIFTED` and
`OUTBOX_MESSAGE_DEAD`.

Four rules, and the first is the one that matters when upgrading:

- **Empty means everything, not nothing.** A channel nobody has routed carries every kind, exactly
  as it did before this setting existed. It has to work this way: every setting here is written
  `${VAR:}`, so an unset environment variable arrives as an empty string — and the other reading
  would silently stop a deployment sending anything the moment it took this version, which is the
  worst possible failure for the subsystem whose job is to tell you when something is wrong.
- **Naming kinds makes the list exhaustive.** A channel that names two kinds carries those two.
- **`NONE` mutes a channel** without disabling it, and cannot be combined with anything else.
  "Carry nothing, and also carry these" has no reading, so it is refused rather than guessed at.
- **An unrecognised kind stops the application at startup**, naming the valid ones. A channel
  configured with a typo carries nothing, and carrying nothing looks exactly like having nothing
  to say — which is only discovered during the incident it was meant to warn about.

A kind may go to several channels; some conditions are worth two places. A kind routed to none is
recorded in the ledger as `SUPPRESSED` — *"No channel is configured to carry …"* — not `FAILED`.
The distinction is load-bearing: the failed-notification metric counts `FAILED` rows, and a channel
you narrowed on purpose must not read as a channel that broke.

Routing decides **where** a notification goes, never whether it happens. `minimum-severity` still
applies first, and a site opted out of expiry warnings is still opted out.

## Per-site notification settings

Expiry warnings are on for every site, and the platform's operator addresses receive them. A site
can narrow or widen that from the console's **Notifications** section, or over
`PUT /api/v1/proxy-sites/{id}/notifications`:

- **Opt out** of expiry warnings for one site — for a site whose expiry is deliberate and
  uninteresting, which is otherwise a recurring message nobody acts on. Warnings that get ignored
  train people to ignore the next one.
- **Subscribe addresses** told *in addition* to the operator list, never instead of it. A list
  here that replaced the operator addresses would quietly cut off whoever configured the estate.

Three things are deliberate:

- **It is not part of the site's configuration.** Changing it takes no optimistic lock on the site,
  does not appear in the audit trail as a configuration change, and never queues a deployment.
  It is audited on its own, as `PROXY_SITE_NOTIFICATIONS_UPDATED`.
- **OPERATE, not MANAGE.** Choosing who is told is operational, like enabling or renewing. It is
  not READ either: an address added here receives mail naming a domain and its expiry.
- **Opting out is checked before the ledger.** Claiming a notification and then discarding it
  would record it as handled, so opting back in later would send nothing.

A site nobody has configured stores no row at all — absence and the defaults are the same state,
and returning to the defaults removes the row rather than keeping one that says nothing.
