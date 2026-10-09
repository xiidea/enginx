package net.xiidea.enginx.infrastructure.agent;

import net.xiidea.enginx.domain.deployment.AgentActivation;
import net.xiidea.enginx.domain.deployment.AgentException;
import net.xiidea.enginx.domain.deployment.AgentStatus;
import net.xiidea.enginx.domain.deployment.AgentValidationFailedException;
import net.xiidea.enginx.domain.deployment.BundleFile;
import net.xiidea.enginx.domain.deployment.ConfigBundle;
import net.xiidea.enginx.domain.deployment.NginxAgentPort;
import net.xiidea.enginx.domain.deployment.SiteVerification;
import net.xiidea.enginx.domain.deployment.UpstreamReachability;
import net.xiidea.enginx.domain.nginx.NginxInstance;
import net.xiidea.enginx.domain.nginx.PushTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * Talks to a dialled agent.
 *
 * <p>One REST API whichever way the host is reached. How the connection is made and how the
 * platform proves who is calling are the instance's {@link PushTransport}, and differ only in
 * {@link AgentConnection}; everything said over it is here, once.
 */
@Component
@EnableConfigurationProperties(AgentClientProperties.class)
public class HttpNginxAgentClient implements NginxAgentPort {

    private static final Logger log = LoggerFactory.getLogger(HttpNginxAgentClient.class);
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final AgentClientProperties properties;
    private final AgentConnection mtls;
    private final AgentConnection token;

    HttpNginxAgentClient(AgentClientProperties properties, MtlsAgentConnection mtls, TokenAgentConnection token) {
        this.properties = properties;
        this.mtls = mtls;
        this.token = token;
    }

    @Override
    public void stage(NginxInstance instance, ConfigBundle bundle, String idempotencyKey) {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("bundleId", bundle.id().toString());
        payload.put("sequence", bundle.sequence());
        payload.put("contentHash", bundle.contentHash());

        ArrayNode files = payload.putArray("files");
        for (BundleFile file : bundle.files()) {
            ObjectNode node = files.addObject();
            node.put("path", file.path());
            node.put("content", file.content());
            node.put("sha256", file.sha256());
            if (file.sensitive()) {
                node.put("sensitive", true);
                node.put("mode", "0600");
            }
        }

        HttpResponse<String> response = send(instance, "POST", "/agent/v1/configurations",
                JSON.writeValueAsString(payload), idempotencyKey);

        if (response.statusCode() == 201 || response.statusCode() == 200) {
            return;
        }
        throw failure(instance, response, "staging the bundle");
    }

    @Override
    public AgentActivation activate(NginxInstance instance, ConfigBundle bundle, String idempotencyKey,
                                    boolean reload) {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("reload", reload);

        HttpResponse<String> response = send(instance, "POST",
                "/agent/v1/configurations/" + bundle.id() + "/activate",
                JSON.writeValueAsString(payload), idempotencyKey);

        if (response.statusCode() == 409) {
            // Validation rejected the configuration. Nothing on the host changed, and retrying
            // identical bytes would fail identically, so this is surfaced as its own type.
            throw new AgentValidationFailedException(textOf(parse(response), "detail"));
        }
        if (response.statusCode() != 200) {
            throw failure(instance, response, "activating the bundle");
        }

        JsonNode body = parse(response);
        return new AgentActivation(
                textOf(body, "bundleId"),
                textOf(body, "previousBundleId"),
                textOf(body, "testOutput"),
                textOf(body, "nginxVersion"),
                body.path("noop").asBoolean(false),
                body.path("rolledBack").asBoolean(false));
    }

    @Override
    public AgentStatus status(NginxInstance instance) {
        HttpResponse<String> response = send(instance, "GET", "/agent/v1/status", null, null);
        if (response.statusCode() != 200) {
            throw failure(instance, response, "reading agent status");
        }

        JsonNode body = parse(response);
        List<AgentStatus.CertificateStatus> certificates = new ArrayList<>();
        for (JsonNode certificate : body.path("certificates")) {
            certificates.add(new AgentStatus.CertificateStatus(
                    textOf(certificate, "path"),
                    textOf(certificate, "subject"),
                    textOf(certificate, "notAfter"),
                    certificate.path("daysRemaining").asInt(0),
                    textOf(certificate, "status")));
        }

        return new AgentStatus(
                textOf(body, "agentVersion"),
                textOf(body, "nginxVersion"),
                body.path("nginxRunning").asBoolean(false),
                textOf(body, "activeBundleId"),
                body.path("configTestOk").asBoolean(false),
                textOf(body, "configTestOutput"),
                certificates);
    }

    @Override
    public List<SiteVerification> verify(NginxInstance instance, List<String> serverNames) {
        if (serverNames.isEmpty()) {
            return List.of();
        }
        ObjectNode payload = JSON.createObjectNode();
        ArrayNode names = payload.putArray("serverNames");
        serverNames.forEach(names::add);

        HttpResponse<String> response = send(instance, "POST", "/agent/v1/verify",
                JSON.writeValueAsString(payload), null);
        if (response.statusCode() != 200) {
            throw failure(instance, response, "verifying sites");
        }

        List<SiteVerification> results = new ArrayList<>();
        for (JsonNode result : parse(response).path("results")) {
            results.add(new SiteVerification(
                    textOf(result, "serverName"),
                    result.path("responded").asBoolean(false),
                    result.path("statusCode").asInt(0),
                    textOf(result, "error")));
        }
        return results;
    }

    @Override
    public List<UpstreamReachability> checkUpstreams(NginxInstance instance, List<UpstreamTarget> targets) {
        if (targets.isEmpty()) {
            return List.of();
        }
        ObjectNode payload = JSON.createObjectNode();
        ArrayNode array = payload.putArray("targets");
        for (UpstreamTarget target : targets) {
            ObjectNode node = array.addObject();
            node.put("host", target.host());
            node.put("port", target.port());
        }

        HttpResponse<String> response = send(instance, "POST", "/agent/v1/upstream-checks",
                JSON.writeValueAsString(payload), null);
        if (response.statusCode() != 200) {
            throw failure(instance, response, "checking upstreams");
        }

        List<UpstreamReachability> results = new ArrayList<>();
        for (JsonNode result : parse(response).path("results")) {
            results.add(new UpstreamReachability(
                    textOf(result, "host"),
                    result.path("port").asInt(0),
                    result.path("reachable").asBoolean(false),
                    textOf(result, "error")));
        }
        return results;
    }

    @Override
    public void discard(NginxInstance instance, String bundleId) {
        HttpResponse<String> response = send(instance, "DELETE",
                "/agent/v1/configurations/" + bundleId, null, null);
        if (response.statusCode() != 204 && response.statusCode() != 404) {
            throw failure(instance, response, "discarding a bundle");
        }
    }

    @Override
    public void publishAcmeChallenge(NginxInstance instance, String token, String authorization) {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("authorization", authorization);

        HttpResponse<String> response = send(instance, "PUT", "/agent/v1/acme-challenges/" + token,
                JSON.writeValueAsString(payload), null);
        if (response.statusCode() != 201 && response.statusCode() != 200) {
            throw failure(instance, response, "publishing an ACME challenge");
        }
    }

    @Override
    public void removeAcmeChallenge(NginxInstance instance, String token) {
        HttpResponse<String> response = send(instance, "DELETE", "/agent/v1/acme-challenges/" + token, null, null);
        if (response.statusCode() != 204 && response.statusCode() != 404) {
            throw failure(instance, response, "removing an ACME challenge");
        }
    }

    // ---- transport ---------------------------------------------------------

    private HttpResponse<String> send(NginxInstance instance, String method, String path,
                                      String body, String idempotencyKey) {
        AgentConnection connection = connectionFor(instance);
        URI uri = instance.agentBaseUrl().resolve(path);

        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);

        HttpRequest.Builder request;
        try {
            request = HttpRequest.newBuilder(uri);
        } catch (IllegalArgumentException e) {
            // A URL the client cannot dial. Registration validates the scheme, so this is a row
            // written some other way, and no number of retries will change it.
            throw new AgentException("Cannot dial the agent for " + instance.name() + " at "
                    + instance.agentBaseUrl() + ": " + e.getMessage(), e, false);
        }
        request.timeout(properties.requestTimeout())
                .header("Accept", "application/json")
                .method(method, publisher);
        connection.authenticate(request, instance);

        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }

        try {
            return connection.clientFor(instance).send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            // Transport problems are worth retrying: an agent restarting mid-deployment is the
            // common case, and the operation is idempotent.
            throw new AgentException("Could not reach the agent for " + instance.name()
                    + " at " + instance.agentBaseUrl() + ": " + e.getMessage(), e, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentException("Interrupted while talking to " + instance.name(), e, true);
        }
    }

    private AgentConnection connectionFor(NginxInstance instance) {
        return instance.pushTransport() == PushTransport.HTTP_TOKEN ? token : mtls;
    }

    private AgentException failure(NginxInstance instance, HttpResponse<String> response, String action) {
        JsonNode body = parse(response);
        String detail = textOf(body, "detail");
        String message = "Agent for " + instance.name() + " refused " + action
                + " with HTTP " + response.statusCode()
                + (detail == null ? "" : ": " + detail);

        // 5xx may pass; a 4xx is a considered refusal and will be refused again.
        boolean retryable = response.statusCode() >= 500;
        log.warn("{} (retryable={})", message, retryable);
        return new AgentException(message, retryable);
    }

    private static JsonNode parse(HttpResponse<String> response) {
        String body = response.body();
        if (body == null || body.isBlank()) {
            return JSON.createObjectNode();
        }
        try {
            return JSON.readTree(body);
        } catch (RuntimeException e) {
            return JSON.createObjectNode();
        }
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asString();
    }
}
