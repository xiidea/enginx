package net.xiidea.enginx.infrastructure.agent;

import net.xiidea.enginx.domain.deployment.AgentActivation;
import net.xiidea.enginx.domain.deployment.AgentStatus;
import net.xiidea.enginx.domain.deployment.ConfigBundle;
import net.xiidea.enginx.domain.deployment.NginxAgentPort;
import net.xiidea.enginx.domain.deployment.SiteVerification;
import net.xiidea.enginx.domain.deployment.UpstreamReachability;
import net.xiidea.enginx.domain.nginx.NginxInstance;
import net.xiidea.enginx.domain.nginx.PushTransport;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Delegates agent operations to the appropriate transport client based on the instance's configured {@link PushTransport}.
 */
@Component
public class RoutingNginxAgentClient implements NginxAgentPort {

    private final HttpNginxAgentClient mtlsClient;
    private final TokenHttpNginxAgentClient tokenHttpClient;

    public RoutingNginxAgentClient(HttpNginxAgentClient mtlsClient, TokenHttpNginxAgentClient tokenHttpClient) {
        this.mtlsClient = mtlsClient;
        this.tokenHttpClient = tokenHttpClient;
    }

    private NginxAgentTransport selectClient(NginxInstance instance) {
        PushTransport transport = instance.pushTransport();
        if (transport == null || transport == PushTransport.MTLS) {
            return mtlsClient;
        }
        return tokenHttpClient;
    }

    @Override
    public void stage(NginxInstance instance, ConfigBundle bundle, String idempotencyKey) {
        selectClient(instance).stage(instance, bundle, idempotencyKey);
    }

    @Override
    public AgentActivation activate(NginxInstance instance, ConfigBundle bundle, String idempotencyKey, boolean reload) {
        return selectClient(instance).activate(instance, bundle, idempotencyKey, reload);
    }

    @Override
    public AgentStatus status(NginxInstance instance) {
        return selectClient(instance).status(instance);
    }

    @Override
    public List<SiteVerification> verify(NginxInstance instance, List<String> serverNames) {
        return selectClient(instance).verify(instance, serverNames);
    }

    @Override
    public List<UpstreamReachability> checkUpstreams(NginxInstance instance, List<UpstreamTarget> targets) {
        return selectClient(instance).checkUpstreams(instance, targets);
    }

    @Override
    public void discard(NginxInstance instance, String bundleId) {
        selectClient(instance).discard(instance, bundleId);
    }

    @Override
    public void publishAcmeChallenge(NginxInstance instance, String token, String authorization) {
        selectClient(instance).publishAcmeChallenge(instance, token, authorization);
    }

    @Override
    public void removeAcmeChallenge(NginxInstance instance, String token) {
        selectClient(instance).removeAcmeChallenge(instance, token);
    }
}
