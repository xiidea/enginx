package net.xiidea.enginx.domain.proxy;

import net.xiidea.enginx.domain.shared.ValidationException;

import java.time.Duration;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Who may sign in to a site with a one-time code sent by email, and for how long a sign-in lasts.
 *
 * <p>The entries are kept sorted so the gate file rendered from them is the same bytes for the
 * same list, however it was entered.
 */
public record EmailOtpPolicy(List<AllowedEmail> allowedEmails, Duration sessionTtl) {

    public static final int MAX_ALLOWED_EMAILS = 256;
    public static final Duration MIN_SESSION_TTL = Duration.ofMinutes(5);
    public static final Duration MAX_SESSION_TTL = Duration.ofDays(30);
    public static final Duration DEFAULT_SESSION_TTL = Duration.ofHours(24);

    public EmailOtpPolicy {
        allowedEmails = allowedEmails == null ? List.of() : allowedEmails.stream()
                .sorted(Comparator.comparing(AllowedEmail::value))
                .toList();
        if (allowedEmails.isEmpty()) {
            throw new ValidationException("protection.emailOtp.allowedEmails",
                    "Email sign-in needs at least one allowed address or *@domain");
        }
        if (allowedEmails.size() > MAX_ALLOWED_EMAILS) {
            throw new ValidationException("protection.emailOtp.allowedEmails",
                    "A site may not allow more than " + MAX_ALLOWED_EMAILS + " entries");
        }
        Set<String> seen = new HashSet<>();
        for (AllowedEmail email : allowedEmails) {
            if (!seen.add(email.value())) {
                throw new ValidationException("protection.emailOtp.allowedEmails", "Duplicate entry " + email.value());
            }
        }
        sessionTtl = sessionTtl == null ? DEFAULT_SESSION_TTL : sessionTtl;
        if (sessionTtl.compareTo(MIN_SESSION_TTL) < 0 || sessionTtl.compareTo(MAX_SESSION_TTL) > 0) {
            throw new ValidationException("protection.emailOtp.sessionTtlSeconds",
                    "A sign-in must last between 5 minutes and 30 days");
        }
    }

    public boolean admits(String email) {
        return allowedEmails.stream().anyMatch(entry -> entry.admits(email));
    }
}
