package net.xiidea.enginx.application.gate;

import net.xiidea.enginx.application.shared.AuditRecorder;
import net.xiidea.enginx.domain.audit.AuditAction;
import net.xiidea.enginx.domain.proxy.EmailOtpPolicy;
import net.xiidea.enginx.domain.proxy.ProtectionMode;
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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Mails a sign-in code on behalf of a host's gate.
 *
 * <p>The gate proves which site it speaks for by signing the request with that site's relay key,
 * so this needs no credential of its own and works the same for push and pull hosts. The platform
 * stays the authority on who may sign in: the address is checked against the site's allow-list
 * here, whatever the gate decided, and a gate that has lost its mind can mail only allowed
 * addresses, at the rate below.
 */
@Service
public class GateRelayService {

    /** The code is the gate's; the platform only checks its shape. */
    private static final Pattern CODE = Pattern.compile("^[0-9]{6}$");
    static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);
    static final Duration CODE_VALID_FOR = Duration.ofMinutes(10);

    /** Per address per site. Enough for a typo and a resend; not enough to flood an inbox. */
    static final int PER_ADDRESS_LIMIT = 5;
    static final Duration PER_ADDRESS_WINDOW = Duration.ofMinutes(15);
    static final int PER_SITE_LIMIT = 200;
    static final Duration PER_SITE_WINDOW = Duration.ofHours(1);

    private static final Logger log = LoggerFactory.getLogger(GateRelayService.class);
    private static final String RESOURCE_TYPE = "PROXY_SITE";

    private final ProxySiteRepository sites;
    private final GateKeys keys;
    private final OtpMailer mailer;
    private final AuditRecorder audit;
    private final Clock clock;
    // Per replica, as the API rate limiter is. A cluster of N allows N times these; the gate's own
    // limits are the first line, and these only bound what a misbehaving gate can make us send.
    private final Map<String, Deque<Instant>> sent = new ConcurrentHashMap<>();

    public GateRelayService(ProxySiteRepository sites, GateKeys keys, OtpMailer mailer,
                            AuditRecorder audit, Clock clock) {
        this.sites = sites;
        this.keys = keys;
        this.mailer = mailer;
        this.audit = audit;
        this.clock = clock;
    }

    public record CodeRequest(UUID siteId, String email, String code, long timestamp, String signature) {

        @Override
        public String toString() {
            return "CodeRequest[siteId=" + siteId + ", email=" + email + ", code=<redacted>]";
        }
    }

    public enum Outcome {
        SENT,
        /** Unknown site, bad signature, stale timestamp: indistinguishable to the caller. */
        REFUSED,
        NOT_ALLOWED,
        RATE_LIMITED,
        MAIL_UNAVAILABLE
    }

    @Transactional
    public Outcome sendCode(CodeRequest request) {
        if (request.siteId() == null || request.email() == null || request.code() == null
                || !CODE.matcher(request.code()).matches()) {
            return Outcome.REFUSED;
        }
        ProxySite site = sites.findById(request.siteId()).orElse(null);
        if (site == null || site.spec().protection().mode() != ProtectionMode.EMAIL_OTP) {
            return Outcome.REFUSED;
        }
        byte[] key = keys.reveal(site.id()).orElse(null);
        if (key == null) {
            return Outcome.REFUSED;
        }
        String expected = GateKeyDerivation.relaySignature(key, site.id().toString(), request.email(),
                request.code(), request.timestamp());
        if (!GateKeyDerivation.signatureMatches(expected, request.signature())) {
            log.warn("Refused a code request for {} with a bad signature", site.domain().value());
            return Outcome.REFUSED;
        }
        Instant now = clock.instant();
        if (Duration.between(Instant.ofEpochSecond(request.timestamp()), now).abs().compareTo(MAX_CLOCK_SKEW) > 0) {
            // A replayed request, or a host whose clock is off far enough to break TLS anyway.
            log.warn("Refused a code request for {} stamped {}s from now", site.domain().value(),
                    request.timestamp() - now.getEpochSecond());
            return Outcome.REFUSED;
        }

        String email = request.email().trim().toLowerCase(Locale.ROOT);
        EmailOtpPolicy policy = site.spec().protection().emailOtp();
        if (!policy.admits(email)) {
            return Outcome.NOT_ALLOWED;
        }
        if (!mailer.available()) {
            log.error("A code for {} could not be sent: mail is not configured (spring.mail.host)",
                    site.domain().value());
            return Outcome.MAIL_UNAVAILABLE;
        }
        if (!admit(site.id() + "|" + email, PER_ADDRESS_LIMIT, PER_ADDRESS_WINDOW, now)
                || !admit(site.id().toString(), PER_SITE_LIMIT, PER_SITE_WINDOW, now)) {
            return Outcome.RATE_LIMITED;
        }

        mailer.sendCode(email, site.domain().value(), request.code(), CODE_VALID_FOR);
        audit.success(AuditAction.GATE_CODE_SENT, RESOURCE_TYPE, site.id(), null,
                Map.of("domain", site.domain().value(), "email", email));
        return Outcome.SENT;
    }

    private static final int MAX_TRACKED_BUCKETS = 10_000;

    private boolean admit(String bucket, int limit, Duration window, Instant now) {
        if (sent.size() > MAX_TRACKED_BUCKETS) {
            // Bounded the way the API rate limiter is: wholesale, which forgets every count at
            // once rather than letting a stream of distinct addresses grow this without limit.
            sent.clear();
        }
        Deque<Instant> times = sent.computeIfAbsent(bucket, ignored -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && times.peekFirst().isBefore(now.minus(window))) {
                times.pollFirst();
            }
            if (times.size() >= limit) {
                return false;
            }
            times.addLast(now);
            return true;
        }
    }
}
