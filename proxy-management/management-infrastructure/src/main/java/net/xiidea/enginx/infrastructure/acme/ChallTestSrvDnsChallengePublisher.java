package net.xiidea.enginx.infrastructure.acme;

import net.xiidea.enginx.domain.certificate.CertificateIssuanceException;
import net.xiidea.enginx.domain.certificate.DnsChallengePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Writes TXT records into Let's Encrypt's {@code pebble-challtestsrv}.
 *
 * <p>For development and tests only, and registered only when its URL is set. It exists so that
 * DNS-01 and wildcard issuance are exercised against a real ACME authority performing real DNS
 * lookups, rather than being written blind and first run in production — which for a code path
 * governed by weekly rate limits is an expensive place to discover a mistake.
 *
 * <p>Never a production provider: challtestsrv answers every query for every name, which is
 * exactly what makes it useful here and exactly what makes it useless anywhere else.
 */
@Component
// Same reason as VaultTransitKekProvider: an empty placeholder is not an absent property.
@ConditionalOnExpression("!'${enginx.acme.dns.challtestsrv-url:}'.trim().isEmpty()")
public class ChallTestSrvDnsChallengePublisher implements DnsChallengePublisher {

    private static final Logger log = LoggerFactory.getLogger(ChallTestSrvDnsChallengePublisher.class);
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final URI managementUrl;
    private final HttpClient client;

    public ChallTestSrvDnsChallengePublisher(
            @Value("${enginx.acme.dns.challtestsrv-url:}") String url) {
        this.managementUrl = url == null || url.isBlank() ? null : URI.create(url.trim());
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        if (managementUrl != null) {
            log.warn("DNS-01 challenges will be published to challtestsrv at {}. "
                    + "This is a development component and must never be used in production.", managementUrl);
        }
    }

    @Override
    public boolean isConfigured() {
        return managementUrl != null;
    }

    @Override
    public void publish(String recordName, String value) {
        ObjectNode payload = JSON.createObjectNode();
        // challtestsrv matches the query name exactly, and a DNS query arrives with a trailing
        // dot. Without it the record is written under a name nothing will ever ask for, and the
        // authority reports a validation failure that looks like a propagation problem.
        payload.put("host", fullyQualified(recordName));
        payload.put("value", value);

        call("/set-txt", payload, "publishing");
    }

    @Override
    public void withdraw(String recordName) {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("host", fullyQualified(recordName));

        try {
            call("/clear-txt", payload, "withdrawing");
        } catch (RuntimeException e) {
            // Withdrawal runs in a finally block; failing here would replace the real issuance
            // error with this one.
            log.warn("Could not clear TXT record {}: {}", recordName, e.toString());
        }
    }

    private static String fullyQualified(String recordName) {
        return recordName.endsWith(".") ? recordName : recordName + ".";
    }

    private void call(String path, ObjectNode payload, String what) {
        if (managementUrl == null) {
            throw new CertificateIssuanceException("challtestsrv is not configured", false);
        }
        HttpRequest request = HttpRequest.newBuilder(managementUrl.resolve(path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload)))
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new CertificateIssuanceException(
                        "challtestsrv returned " + response.statusCode() + " while " + what + " a TXT record",
                        true);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CertificateIssuanceException("Interrupted while " + what + " a TXT record", e, true);
        } catch (java.io.IOException e) {
            throw new CertificateIssuanceException(
                    "Could not reach challtestsrv while " + what + " a TXT record: " + e.getMessage(), e, true);
        }
    }
}
