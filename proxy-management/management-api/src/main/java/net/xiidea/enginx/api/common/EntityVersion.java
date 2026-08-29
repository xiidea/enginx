package net.xiidea.enginx.api.common;

import net.xiidea.enginx.domain.shared.ValidationException;

/**
 * Optimistic locking over HTTP.
 *
 * <p>The aggregate's version is exposed as an {@code ETag} and accepted back as {@code If-Match},
 * which is what makes concurrent edits detectable across two separate requests. Hibernate's
 * {@code @Version} alone only protects writes inside one transaction.
 */
public final class EntityVersion {

    private EntityVersion() {
    }

    public static String toETag(long version) {
        return "\"" + version + "\"";
    }

    /** @return the parsed version, or null when the client did not supply one */
    public static Long parseIfMatch(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            return null;
        }
        String value = ifMatch.trim();
        if (value.equals("*")) {
            return null;
        }
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        value = value.replace("\"", "").trim();
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new ValidationException("If-Match", "If-Match must carry the entity version, for example \"3\"");
        }
    }
}
