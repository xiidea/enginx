package net.xiidea.enginx.domain.proxy;

import net.xiidea.enginx.domain.shared.ValidationException;

import java.util.regex.Pattern;

/**
 * A custom header a site sets on the request to the upstream, or on the response to the client.
 *
 * <p>Values are strict by design. A newline would let a user append arbitrary directives to the
 * generated configuration, and a {@code $} would let them interpolate an NGINX variable that the
 * platform never intended to expose. Both are rejected here rather than escaped later, because
 * a single escaping bypass in the renderer is remote code execution on the NGINX host.
 * Phase 4 may introduce an explicit allowlist of supported variables; until then there are none.
 */
public record ProxySiteHeader(HeaderDirection direction, String name, String value) {

    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9!#$%&'*+._|~-]{1,128}$");
    private static final Pattern FORBIDDEN_IN_VALUE = Pattern.compile("[\\r\\n;{}\"\\\\$]");
    private static final int MAX_VALUE_LENGTH = 1024;

    public ProxySiteHeader {
        if (direction == null) {
            throw new ValidationException("headers.direction", "Header direction is required");
        }
        if (name == null || name.isBlank()) {
            throw new ValidationException("headers.name", "Header name must not be blank");
        }
        name = name.trim();
        if (!NAME.matcher(name).matches()) {
            throw new ValidationException("headers.name", "'" + name + "' is not a valid HTTP header name");
        }
        if (value == null) {
            throw new ValidationException("headers.value", "Header value must not be null");
        }
        value = value.trim();
        if (value.length() > MAX_VALUE_LENGTH) {
            throw new ValidationException("headers.value", "Header value must not exceed " + MAX_VALUE_LENGTH + " characters");
        }
        if (FORBIDDEN_IN_VALUE.matcher(value).find()) {
            throw new ValidationException("headers.value",
                    "Header value may not contain newlines, quotes, backslashes, semicolons, braces or '$'");
        }
    }
}
