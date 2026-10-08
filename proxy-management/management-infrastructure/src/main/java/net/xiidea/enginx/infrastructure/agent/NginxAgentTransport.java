package net.xiidea.enginx.infrastructure.agent;

import net.xiidea.enginx.domain.deployment.AgentActivation;
import net.xiidea.enginx.domain.deployment.AgentStatus;
import net.xiidea.enginx.domain.deployment.ConfigBundle;
import net.xiidea.enginx.domain.deployment.NginxAgentPort;
import net.xiidea.enginx.domain.deployment.SiteVerification;
import net.xiidea.enginx.domain.deployment.UpstreamReachability;
import net.xiidea.enginx.domain.nginx.NginxInstance;

import java.util.List;

/**
 * Common transport interface for push agent implementations (mTLS, Token HTTP, gRPC).
 */
public interface NginxAgentTransport {

    void stage(NginxInstance instance, ConfigBundle bundle, String idempotencyKey);

    AgentActivation activate(NginxInstance instance, ConfigBundle bundle, String idempotencyKey, boolean reload);

    AgentStatus status(NginxInstance instance);

    List<SiteVerification> verify(NginxInstance instance, List<String> serverNames);

    List<UpstreamReachability> checkUpstreams(NginxInstance instance, List<NginxAgentPort.UpstreamTarget> targets);

    void discard(NginxInstance instance, String bundleId);

    void publishAcmeChallenge(NginxInstance instance, String token, String authorization);

    void removeAcmeChallenge(NginxInstance instance, String token);
}
