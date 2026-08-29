package net.xiidea.enginx.infrastructure.acme;

import net.xiidea.enginx.domain.certificate.AcmeChallengePublisher;
import net.xiidea.enginx.domain.certificate.CertificateIssuanceException;
import net.xiidea.enginx.domain.deployment.NginxAgentPort;
import net.xiidea.enginx.domain.nginx.NginxInstance;
import net.xiidea.enginx.domain.nginx.NginxInstanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Publishes HTTP-01 challenge responses to every managed NGINX host.
 *
 * <p>Every host, not the one that serves the domain, for two reasons. The platform does not know
 * where a domain's DNS points — that is the authority's question to answer, and for a brand-new
 * domain there may be no site configured at all yet. And the token is a public value the authority
 * is about to fetch over plain HTTP, so publishing it more widely costs nothing.
 *
 * <p>Publishing must succeed somewhere. If no host accepted the token the validation is certain to
 * fail, and failing here means it fails before the authority is asked to look — which matters,
 * because a failed validation consumes rate-limit budget that a refused publish does not.
 */
@Component
public class AgentAcmeChallengePublisher implements AcmeChallengePublisher {

    private static final Logger log = LoggerFactory.getLogger(AgentAcmeChallengePublisher.class);

    private final NginxInstanceRepository instances;
    private final NginxAgentPort agent;

    public AgentAcmeChallengePublisher(NginxInstanceRepository instances, NginxAgentPort agent) {
        this.instances = instances;
        this.agent = agent;
    }

    @Override
    public void publish(String token, String authorization) {
        List<NginxInstance> hosts = instances.findAll();
        if (hosts.isEmpty()) {
            throw new CertificateIssuanceException(
                    "No NGINX instances are registered, so there is nowhere to answer the challenge from", false);
        }

        int published = 0;
        for (NginxInstance instance : hosts) {
            try {
                agent.publishAcmeChallenge(instance, token, authorization);
                published++;
            } catch (RuntimeException e) {
                // One unreachable host is survivable: the authority only needs to reach whichever
                // one the domain resolves to.
                log.warn("Could not publish the ACME challenge to {}: {}", instance.name(), e.getMessage());
            }
        }

        if (published == 0) {
            throw new CertificateIssuanceException(
                    "The challenge could not be published to any NGINX instance, so validation would certainly fail",
                    true);
        }
        log.info("Published ACME challenge to {} of {} instance(s)", published, hosts.size());
    }

    @Override
    public void withdraw(String token) {
        for (NginxInstance instance : instances.findAll()) {
            try {
                agent.removeAcmeChallenge(instance, token);
            } catch (RuntimeException e) {
                // Best effort. A token left behind is inert: it proves nothing without the order
                // it belonged to, and the next issuance uses a different one.
                log.debug("Could not withdraw the ACME challenge from {}: {}", instance.name(), e.getMessage());
            }
        }
    }
}
