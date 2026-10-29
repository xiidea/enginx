package net.xiidea.enginx.domain.proxy;

import net.xiidea.enginx.domain.shared.ValidationException;

import java.time.Duration;

/**
 * Basic authentication through the host's gate, on a login page, rather than the browser's
 * built-in prompt.
 *
 * <p>The same user list and the same hashes: only who asks for them changes. The gate serves a
 * form, signs the visitor in for {@code sessionTtl}, and limits failed attempts, which NGINX's own
 * basic authentication cannot. It also still accepts an {@code Authorization: Basic} header, so a
 * script with credentials keeps working.
 */
public record BasicLoginPage(Duration sessionTtl) {

    public BasicLoginPage {
        sessionTtl = sessionTtl == null ? EmailOtpPolicy.DEFAULT_SESSION_TTL : sessionTtl;
        if (sessionTtl.compareTo(EmailOtpPolicy.MIN_SESSION_TTL) < 0
                || sessionTtl.compareTo(EmailOtpPolicy.MAX_SESSION_TTL) > 0) {
            throw new ValidationException("protection.basicLoginPage.sessionTtlSeconds",
                    "A sign-in must last between 5 minutes and 30 days");
        }
    }
}
