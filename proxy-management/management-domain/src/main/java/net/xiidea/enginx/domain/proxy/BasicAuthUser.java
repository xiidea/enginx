package net.xiidea.enginx.domain.proxy;

import net.xiidea.enginx.domain.shared.ValidationException;

import java.util.regex.Pattern;

/**
 * One account allowed through a site's basic authentication.
 *
 * <p>Both values end up as a line of the site's htpasswd file, {@code username:hash}, so both are
 * held to patterns that cannot contain a colon, a newline or anything else that would split or
 * add a line. The password itself never reaches the domain: only its SHA-512-crypt hash, which is
 * what NGINX verifies against through the system {@code crypt()}. SHA-512-crypt rather than
 * bcrypt because {@code auth_basic} re-verifies on every request with no cache, and bcrypt at a
 * sensible cost would add a quarter of a second to each one.
 */
public record BasicAuthUser(String username, String passwordHash) {

    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9._@-]{1,64}$");
    private static final Pattern SHA512_CRYPT =
            Pattern.compile("^\\$6\\$(rounds=\\d{4,9}\\$)?[./A-Za-z0-9]{1,16}\\$[./A-Za-z0-9]{86}$");

    public BasicAuthUser {
        username = requireValidUsername(username);
        if (passwordHash == null || !SHA512_CRYPT.matcher(passwordHash).matches()) {
            throw new ValidationException("protection.basicUsers.password",
                    "The password for '" + username + "' is not a valid SHA-512-crypt hash");
        }
    }

    /** The username rule on its own, for callers that check a name before a hash exists for it. */
    public static String requireValidUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new ValidationException("protection.basicUsers.username", "Username must not be blank");
        }
        String trimmed = username.trim();
        if (!USERNAME.matcher(trimmed).matches()) {
            throw new ValidationException("protection.basicUsers.username",
                    "'" + trimmed + "' is not a valid username. Use 1 to 64 letters, digits, '.', '_', '@' or '-'");
        }
        return trimmed;
    }

    @Override
    public String toString() {
        return "BasicAuthUser[username=" + username + ", passwordHash=<redacted>]";
    }
}
