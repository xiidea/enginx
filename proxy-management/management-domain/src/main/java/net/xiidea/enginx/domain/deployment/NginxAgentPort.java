package net.xiidea.enginx.domain.deployment;

import net.xiidea.enginx.domain.nginx.NginxInstance;

/**
 * The management server's only way to touch an NGINX host.
 *
 * <p>Deliberately narrow: stage a bundle, activate one, ask for status. There is no method that
 * runs a command, copies an arbitrary file, or opens a shell, because the platform must not be
 * able to do those things even by mistake.
 */
public interface NginxAgentPort {

    /** Uploads a bundle to the host without changing what is being served. */
    void stage(NginxInstance instance, ConfigBundle bundle, String idempotencyKey);

    /**
     * Validates the staged bundle, swaps it in atomically, and reloads.
     *
     * @param reload false to validate and stage only, leaving the running configuration untouched
     * @throws AgentValidationFailedException when {@code nginx -t} fails; nothing was changed
     */
    AgentActivation activate(NginxInstance instance, ConfigBundle bundle, String idempotencyKey, boolean reload);

    AgentStatus status(NginxInstance instance);

    /**
     * Asks the host whether it now answers for each of these names.
     *
     * <p>The probe runs on the host and always dials loopback, so this cannot be used to reach
     * anything: the names travel as Host headers, never as addresses.
     *
     * @return one result per name, in the order given
     */
    java.util.List<SiteVerification> verify(NginxInstance instance, java.util.List<String> serverNames);

    /**
     * Asks the host whether it can open a TCP connection to each upstream.
     *
     * <p>Runs on the host deliberately. The management server is on the wrong network to get a
     * meaningful answer, and dialling a user-supplied host and port from it would be a
     * server-side request forgery primitive aimed at the internal network. The agent is already
     * where NGINX will connect from.
     *
     * @return one result per target, in the order given
     */
    java.util.List<UpstreamReachability> checkUpstreams(NginxInstance instance,
                                                        java.util.List<UpstreamTarget> targets);

    /** A host and port to probe. */
    record UpstreamTarget(String host, int port) {
    }

    /** Removes a superseded bundle from the host. */
    void discard(NginxInstance instance, String bundleId);

    /**
     * Publishes an HTTP-01 challenge response, outside the release tree.
     *
     * <p>Not part of a bundle on purpose: a token must appear within seconds and is discarded just
     * as fast, so routing it through render-validate-activate-reload would churn the real
     * configuration twice for every certificate.
     */
    void publishAcmeChallenge(NginxInstance instance, String token, String authorization);

    void removeAcmeChallenge(NginxInstance instance, String token);
}
