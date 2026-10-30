package net.xiidea.enginx.application.gate;

import net.xiidea.enginx.domain.audit.AuditAction;
import net.xiidea.enginx.domain.audit.AuditEvent;
import net.xiidea.enginx.domain.audit.AuditResult;
import net.xiidea.enginx.domain.audit.AuditSink;
import net.xiidea.enginx.domain.proxy.ProxySite;
import net.xiidea.enginx.domain.proxy.ProxySiteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Records in the audit trail what a host's gate saw at a protected site's door: sign-ins, wrong
 * passwords and codes, visitors turned away, sign-outs.
 *
 * <p>The gate sends them in batches, each site's signed with that site's relay key, as code
 * requests are, so this needs no credential of its own and works for push and pull hosts alike.
 * What a visitor typed arrives here, so every text is bounded and stripped of control characters,
 * and a gate can add at most {@link #PER_SITE_LIMIT} rows an hour for a site; past that, events are
 * counted and the count recorded instead.
 */
@Service
public class GateEventService {

    public record Event(long at, String kind, String method, String user, String email, String ip, String reason) {
    }

    /** One site's section, as the gate signed it. */
    public record Batch(UUID siteId, String batchId, long sentAt, int dropped, List<Event> events) {
    }

    public enum Outcome {
        RECORDED,
        /** Unknown or unprotected site, bad signature, stale or replayed: indistinguishable to the caller. */
        REFUSED
    }

    static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);
    /** How far back an event may claim to be: a gate keeps events while the platform is away. */
    static final Duration MAX_EVENT_AGE = Duration.ofDays(7);
    static final int MAX_EVENTS_PER_BATCH = 200;
    static final int PER_SITE_LIMIT = 6000;
    static final Duration PER_SITE_WINDOW = Duration.ofHours(1);
    private static final int MAX_TEXT = 128;
    private static final int MAX_TRACKED = 10_000;

    private static final Pattern BATCH_ID = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");
    private static final Pattern REASON = Pattern.compile("^[a-z-]{1,32}$");
    private static final Set<String> METHODS = Set.of("password", "header", "code", "oidc");
    private static final String RESOURCE_TYPE = "PROXY_SITE";
    /** The actor subject of every gate row: the visitor is not a platform account. */
    static final String ACTOR = "gate";

    private static final Logger log = LoggerFactory.getLogger(GateEventService.class);

    private final ProxySiteRepository sites;
    private final GateKeys keys;
    private final AuditSink audit;
    private final Clock clock;
    // Per replica, as the relay's limits are: a batch replayed to another replica within the clock
    // skew is recorded twice, which costs rows, never a wrong answer to who got in.
    private final Map<String, Instant> seenBatches = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Instant>> recorded = new ConcurrentHashMap<>();

    public GateEventService(ProxySiteRepository sites, GateKeys keys, AuditSink audit, Clock clock) {
        this.sites = sites;
        this.keys = keys;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public Outcome record(UUID siteId, String payload, String signature, Batch batch) {
        if (siteId == null || payload == null || batch == null || !siteId.equals(batch.siteId())
                || batch.batchId() == null || !BATCH_ID.matcher(batch.batchId()).matches()) {
            return Outcome.REFUSED;
        }
        ProxySite site = sites.findById(siteId).orElse(null);
        if (site == null || !site.spec().protection().usesGate()) {
            return Outcome.REFUSED;
        }
        byte[] key = keys.reveal(site.id()).orElse(null);
        if (key == null || !GateKeyDerivation.signatureMatches(GateKeyDerivation.eventsSignature(key, payload), signature)) {
            log.warn("Refused gate events for {} with a bad signature", site.domain().value());
            return Outcome.REFUSED;
        }
        Instant now = clock.instant();
        if (Duration.between(Instant.ofEpochSecond(batch.sentAt()), now).abs().compareTo(MAX_CLOCK_SKEW) > 0) {
            log.warn("Refused gate events for {} stamped {}s from now", site.domain().value(),
                    batch.sentAt() - now.getEpochSecond());
            return Outcome.REFUSED;
        }
        if (!firstSighting(batch.batchId(), now)) {
            return Outcome.REFUSED;
        }

        List<Event> events = batch.events() == null ? List.of() : batch.events();
        int dropped = Math.max(0, batch.dropped()) + Math.max(0, events.size() - MAX_EVENTS_PER_BATCH);
        for (Event event : events.subList(0, Math.min(events.size(), MAX_EVENTS_PER_BATCH))) {
            Kind kind = Kind.of(event.kind());
            if (kind == null) {
                continue;
            }
            if (!admit(site.id(), now)) {
                dropped++;
                continue;
            }
            audit.record(row(site, event, kind, now));
        }
        if (dropped > 0) {
            audit.record(new AuditEvent(UUID.randomUUID(), now, ACTOR, null, AuditAction.GATE_EVENTS_DROPPED,
                    RESOURCE_TYPE, site.id(), null, Map.of("domain", site.domain().value(), "count", dropped),
                    null, null, AuditResult.FAILURE, dropped + " gate events were not recorded", null));
        }
        return Outcome.RECORDED;
    }

    private enum Kind {
        SIGNED_IN("signed-in", AuditAction.GATE_SIGNED_IN, AuditResult.SUCCESS),
        SIGN_IN_FAILED("sign-in-failed", AuditAction.GATE_SIGN_IN_FAILED, AuditResult.FAILURE),
        ACCESS_DENIED("access-denied", AuditAction.GATE_ACCESS_DENIED, AuditResult.DENIED),
        SIGNED_OUT("signed-out", AuditAction.GATE_SIGNED_OUT, AuditResult.SUCCESS);

        final String wire;
        final AuditAction action;
        final AuditResult result;

        Kind(String wire, AuditAction action, AuditResult result) {
            this.wire = wire;
            this.action = action;
            this.result = result;
        }

        static Kind of(String wire) {
            for (Kind kind : values()) {
                if (kind.wire.equals(wire)) {
                    return kind;
                }
            }
            return null;
        }
    }

    private static AuditEvent row(ProxySite site, Event event, Kind kind, Instant now) {
        String email = text(event.email());
        String user = text(event.user());
        String reason = event.reason() != null && REASON.matcher(event.reason()).matches() ? event.reason() : null;
        String method = METHODS.contains(event.method()) ? event.method() : "unknown";

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("domain", site.domain().value());
        details.put("mode", site.spec().protection().mode().name());
        details.put("method", method);
        if (email != null) {
            details.put("email", email);
        }
        if (user != null) {
            details.put("user", user);
        }
        if (reason != null) {
            details.put("reason", reason);
        }
        return new AuditEvent(UUID.randomUUID(), occurredAt(event.at(), now), ACTOR,
                email != null ? email : user, kind.action, RESOURCE_TYPE, site.id(), null, details,
                ip(event.ip()), null, kind.result, kind.result == AuditResult.SUCCESS ? null : reason, null);
    }

    /** When the gate saw it, within reason: never in the future, never older than a gate keeps. */
    private static Instant occurredAt(long millis, Instant now) {
        Instant at = Instant.ofEpochMilli(millis);
        if (at.isAfter(now)) {
            return now;
        }
        Instant oldest = now.minus(MAX_EVENT_AGE);
        return at.isBefore(oldest) ? oldest : at;
    }

    private static String text(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.codePoints()
                .filter(c -> !Character.isISOControl(c))
                .limit(MAX_TEXT)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString().strip();
        return cleaned.isEmpty() ? null : cleaned;
    }

    /** An address as NGINX passes it: digits, hex, dots and colons only. */
    private static String ip(String value) {
        return value != null && value.length() <= 64 && value.matches("^[0-9A-Fa-f.:]+$") ? value : null;
    }

    private boolean firstSighting(String batchId, Instant now) {
        if (seenBatches.size() > MAX_TRACKED) {
            seenBatches.values().removeIf(at -> at.isBefore(now.minus(MAX_CLOCK_SKEW.multipliedBy(2))));
            if (seenBatches.size() > MAX_TRACKED) {
                seenBatches.clear();
            }
        }
        return seenBatches.putIfAbsent(batchId, now) == null;
    }

    private boolean admit(UUID siteId, Instant now) {
        if (recorded.size() > MAX_TRACKED) {
            recorded.clear();
        }
        Deque<Instant> times = recorded.computeIfAbsent(siteId, ignored -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && times.peekFirst().isBefore(now.minus(PER_SITE_WINDOW))) {
                times.pollFirst();
            }
            if (times.size() >= PER_SITE_LIMIT) {
                return false;
            }
            times.addLast(now);
            return true;
        }
    }
}
