package net.xiidea.enginx.security.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class SensitivePathsTest {

    @ParameterizedTest
    @CsvSource({
            // Reads are reads, whatever they are reading. Charging a certificate listing to the
            // strict bucket would exhaust an operator's allowance by opening a page.
            "GET,  /api/v1/certificates,                   READ",
            "GET,  /api/v1/proxy-sites,                    READ",
            "GET,  /api/v1/permissions,                    READ",
            "HEAD, /api/v1/deployments,                    READ",

            // Reaches a certificate authority whose quota is counted per week.
            "POST,   /api/v1/certificates,                 SENSITIVE",
            "POST,   /api/v1/certificates/abc/renew,       SENSITIVE",
            "DELETE, /api/v1/certificates/abc,             SENSITIVE",
            // Ships a bundle to a real host and reloads NGINX there.
            "POST,   /api/v1/deployments/abc/rollback,     SENSITIVE",
            "POST,   /api/v1/proxy-sites/abc/deploy,       SENSITIVE",
            "POST,   /api/v1/nginx-instances/abc/deploy,   SENSITIVE",
            // Changes who can do any of the above.
            "POST,   /api/v1/permissions,                  SENSITIVE",
            "DELETE, /api/v1/permissions/abc,              SENSITIVE",
            "POST,   /api/v1/nginx-instances,              SENSITIVE",

            // Ordinary writes. Creating or editing a site changes desired state; it is the deploy
            // that reaches a host, and that has its own tier above.
            "POST,   /api/v1/proxy-sites,                  WRITE",
            "PUT,    /api/v1/proxy-sites/abc,              WRITE",
            "POST,   /api/v1/proxy-sites/abc/enable,       WRITE",
            "POST,   /api/v1/domain-groups,                WRITE",
    })
    @DisplayName("classifies each request into the tier its blast radius deserves")
    void classifies(String method, String path, RateLimitTier expected) {
        assertThat(SensitivePaths.tierFor(path, method)).isEqualTo(expected);
    }

    @Test
    @DisplayName("a new sub-resource under a sensitive prefix is limited without being listed")
    void coversUnknownSubResources() {
        // The list is prefixes, not endpoints, so an endpoint written next year is protected on
        // the day it is written rather than on the day someone notices it was not.
        assertThat(SensitivePaths.tierFor("/api/v1/certificates/abc/some-future-action", "POST"))
                .isEqualTo(RateLimitTier.SENSITIVE);
    }
}
