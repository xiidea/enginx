package net.xiidea.enginx.domain.proxy;

import net.xiidea.enginx.domain.shared.ValidationException;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * One entry of a site's email allow-list: an exact address, or {@code *@domain} for everyone at a
 * domain.
 *
 * <p>Held to a pattern with no quotes, backslashes, whitespace or control characters, because the
 * list is rendered into the gate's configuration file and compared by the gate as text. Stricter
 * than RFC 5322 on purpose: quoted local parts and IP-literal domains are legal and nobody's
 * work address.
 */
public record AllowedEmail(String value) {

    private static final Pattern PATTERN = Pattern.compile(
            "^(\\*|[a-z0-9.!#%&'+=^_`{|}~-]{1,64})@([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$");

    public AllowedEmail {
        if (value == null || value.isBlank()) {
            throw new ValidationException("protection.emailOtp.allowedEmails", "An allowed email must not be blank");
        }
        value = value.trim().toLowerCase(Locale.ROOT);
        if (value.length() > 254 || !PATTERN.matcher(value).matches()) {
            throw new ValidationException("protection.emailOtp.allowedEmails",
                    "'" + value + "' is not an email address or a *@domain pattern");
        }
    }

    public boolean isDomainWildcard() {
        return value.startsWith("*@");
    }

    /** Whether an address, as a visitor typed it, is let in by this entry. */
    public boolean admits(String email) {
        if (email == null) {
            return false;
        }
        String candidate = email.trim().toLowerCase(Locale.ROOT);
        if (isDomainWildcard()) {
            int at = candidate.lastIndexOf('@');
            return at > 0 && candidate.substring(at).equals(value.substring(1));
        }
        return candidate.equals(value);
    }
}
