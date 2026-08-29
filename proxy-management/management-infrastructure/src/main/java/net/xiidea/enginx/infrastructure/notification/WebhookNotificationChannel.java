package net.xiidea.enginx.infrastructure.notification;

import net.xiidea.enginx.domain.notification.NotificationChannel;
import net.xiidea.enginx.domain.notification.NotificationEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collection;

/**
 * An outbound webhook, for chat integrations and incident tooling.
 *
 * <p>A generic JSON payload rather than any one vendor's message format. Every such tool accepts a
 * transformation step, and building Slack's schema in here would mean building Teams' next, and
 * PagerDuty's after that — each a separate thing to keep current with someone else's API.
 */
@Component
@Order(200)
@ConditionalOnProperty(name = "enginx.notifications.webhook-url")
public class WebhookNotificationChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(WebhookNotificationChannel.class);
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final URI endpoint;
    private final HttpClient client;

    /**
     * The endpoint is null when the property is present but empty.
     *
     * <p>{@code @ConditionalOnProperty} matches on a property *existing*, and an unset environment
     * variable behind a placeholder default leaves an empty string rather than nothing at all — so
     * the bean is created for a deployment that has no webhook. Without the blank check below,
     * every notification would then try to POST to an unparseable URI and record a failure against
     * a channel nobody asked for.
     */
    public WebhookNotificationChannel(@Value("${enginx.notifications.webhook-url:}") String url) {
        this.endpoint = url == null || url.isBlank() ? null : URI.create(url.trim());
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public String name() {
        return "webhook";
    }

    @Override
    public boolean isEnabled() {
        return endpoint != null;
    }

    @Override
    public boolean deliver(NotificationEvent event, Collection<String> recipients) {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("kind", event.kind().name());
        payload.put("severity", event.severity().name());
        payload.put("subject", event.subject());
        payload.put("body", event.body());
        payload.put("resourceType", event.resourceType());
        payload.put("resourceId", event.resourceId().toString());
        // Recipients are included so a chat integration can mention the right people. Addresses,
        // not the ledger's internal ids: whoever receives this cannot resolve ours.
        recipients.forEach(payload.withArray("recipients")::add);

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload)))
                .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 == 2) {
                return true;
            }
            log.warn("Notification webhook returned {}", response.statusCode());
            return false;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.warn("Could not call the notification webhook: {}", e.toString());
            return false;
        }
    }
}
