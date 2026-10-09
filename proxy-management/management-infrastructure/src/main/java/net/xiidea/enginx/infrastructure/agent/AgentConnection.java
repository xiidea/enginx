package net.xiidea.enginx.infrastructure.agent;

import net.xiidea.enginx.domain.nginx.NginxInstance;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;

/**
 * How one push transport reaches an agent and proves who is calling.
 *
 * <p>The only part that differs between transports. What is said to the agent — the paths, the
 * payloads, how its answers are read — is the same REST API either way, and lives once in
 * {@link HttpNginxAgentClient}.
 */
interface AgentConnection {

    HttpClient clientFor(NginxInstance instance);

    /** Adds whatever this transport presents per request. Nothing, when TLS already proved it. */
    void authenticate(HttpRequest.Builder request, NginxInstance instance);
}
