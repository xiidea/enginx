package net.xiidea.enginx.domain.shared;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A validated, normalised DNS name.
 *
 * <p>This type is one of the two boundaries that keep untrusted input out of generated
 * NGINX directives (the other is {@link net.xiidea.enginx.domain.proxy.ProxySiteHeader}). A value
 * that reaches the renderer as a {@code DomainName} has already been proven to consist only
 * of DNS label characters, so it can never terminate a directive or open a block.
 */
public record DomainName(String value) {

    private static final int MAX_LENGTH = 253;
    private static final Pattern LABEL = Pattern.compile("^(?!-)[a-z0-9-]{1,63}(?<!-)$");

    public DomainName {
        if (value == null || value.isBlank()) {
            throw new ValidationException("domain", "Domain must not be blank");
        }
        value = value.trim().toLowerCase(Locale.ROOT);
        if (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.length() > MAX_LENGTH) {
            throw new ValidationException("domain", "Domain must not exceed " + MAX_LENGTH + " characters");
        }
        String[] labels = value.split("\\.", -1);
        if (labels.length < 2) {
            throw new ValidationException("domain", "Domain must be fully qualified, for example app.example.com");
        }
        for (String label : labels) {
            if (!LABEL.matcher(label).matches()) {
                throw new ValidationException("domain", "'" + value + "' is not a valid domain name");
            }
        }
        // RFC 1123 section 2.1: an all-numeric top-level label would be indistinguishable from an
        // IP address, so a mistyped address cannot masquerade as a domain.
        String topLevel = labels[labels.length - 1];
        if (topLevel.chars().allMatch(Character::isDigit)) {
            throw new ValidationException("domain",
                    "'" + value + "' looks like an IP address. A proxy site needs a domain name.");
        }
    }

    public static DomainName of(String value) {
        return new DomainName(value);
    }

    /**
     * Dot-reversed form used for indexed wildcard matching: {@code app.example.com}
     * becomes {@code com.example.app}. See the permission model, section 4.5.
     */
    public String reversed() {
        String[] labels = value.split("\\.");
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = labels.length - 1; i >= 0; i--) {
            sb.append(labels[i]);
            if (i > 0) {
                sb.append('.');
            }
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return value;
    }
}
