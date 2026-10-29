package net.xiidea.enginx.domain.proxy;

import net.xiidea.enginx.domain.shared.ValidationException;

import java.time.Duration;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Who, among the users of the platform's identity provider, may enter a site.
 *
 * <p>Either everyone who can sign in there, or anyone matching at least one entry: a user, by
 * email or username, or a holder of a realm or client role. Roles are read when the visitor signs
 * in, so a role taken away takes effect at their next sign-in — or at once, by signing everyone
 * out.
 */
public record OidcAccessPolicy(boolean anyUser, List<String> users, List<OidcRole> roles, Duration sessionTtl) {

    public static final int MAX_ENTRIES = 256;

    /** An email or a username: no quotes, whitespace or control characters, lower-cased. */
    private static final Pattern USER = Pattern.compile("^[a-z0-9._@+-]{1,254}$");

    public OidcAccessPolicy {
        users = users == null ? List.of() : users.stream()
                .map(OidcAccessPolicy::requireValidUser)
                .sorted()
                .toList();
        roles = roles == null ? List.of() : roles.stream()
                .sorted(Comparator.comparing(OidcRole::display))
                .toList();

        if (anyUser && (!users.isEmpty() || !roles.isEmpty())) {
            throw new ValidationException("protection.oidc",
                    "Choose either any signed-in user, or specific users and roles, not both");
        }
        if (!anyUser && users.isEmpty() && roles.isEmpty()) {
            throw new ValidationException("protection.oidc",
                    "Allow any signed-in user, or name at least one user or role");
        }
        if (users.size() > MAX_ENTRIES || roles.size() > MAX_ENTRIES) {
            throw new ValidationException("protection.oidc",
                    "A site may name at most " + MAX_ENTRIES + " users and " + MAX_ENTRIES + " roles");
        }
        if (new HashSet<>(users).size() != users.size()) {
            throw new ValidationException("protection.oidc.users", "Each user may be listed once");
        }
        Set<String> seenRoles = new HashSet<>();
        for (OidcRole role : roles) {
            if (!seenRoles.add(role.display())) {
                throw new ValidationException("protection.oidc.roles", "Duplicate role " + role.display());
            }
        }

        sessionTtl = sessionTtl == null ? EmailOtpPolicy.DEFAULT_SESSION_TTL : sessionTtl;
        if (sessionTtl.compareTo(EmailOtpPolicy.MIN_SESSION_TTL) < 0
                || sessionTtl.compareTo(EmailOtpPolicy.MAX_SESSION_TTL) > 0) {
            throw new ValidationException("protection.oidc.sessionTtlSeconds",
                    "A sign-in must last between 5 minutes and 30 days");
        }
    }

    private static String requireValidUser(String user) {
        if (user == null || user.isBlank()) {
            throw new ValidationException("protection.oidc.users", "A user must not be blank");
        }
        String value = user.trim().toLowerCase(Locale.ROOT);
        if (!USER.matcher(value).matches()) {
            throw new ValidationException("protection.oidc.users",
                    "'" + value + "' is not an email address or username");
        }
        return value;
    }

    public static OidcAccessPolicy anyone(Duration sessionTtl) {
        return new OidcAccessPolicy(true, List.of(), List.of(), sessionTtl);
    }
}
