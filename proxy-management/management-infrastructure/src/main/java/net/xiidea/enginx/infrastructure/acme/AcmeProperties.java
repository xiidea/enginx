package net.xiidea.enginx.infrastructure.acme;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param directoryUrl   the authority's ACME directory. Defaults to Let's Encrypt <em>staging</em>:
 *                       production has hard rate limits counted per week, and a misconfiguration
 *                       discovered against the real endpoint can lock an account out for days.
 * @param acceptTermsOfService must be set deliberately. Agreeing to an authority's terms on an
 *                       operator's behalf without being told to is not the platform's decision.
 */
@ConfigurationProperties(prefix = "enginx.acme")
public record AcmeProperties(
        String directoryUrl,
        String contactEmail,
        boolean acceptTermsOfService,
        Duration challengeTimeout,
        Duration orderTimeout,
        int keySize) {

    public AcmeProperties {
        directoryUrl = directoryUrl == null || directoryUrl.isBlank()
                ? "acme://letsencrypt.org/staging"
                : directoryUrl.trim();
        challengeTimeout = challengeTimeout == null ? Duration.ofSeconds(60) : challengeTimeout;
        orderTimeout = orderTimeout == null ? Duration.ofSeconds(120) : orderTimeout;
        keySize = keySize < 2048 ? 2048 : keySize;
    }
}
