package net.xiidea.enginx.infrastructure.acme;

import net.xiidea.enginx.application.agent.AgentJobQueue;
import net.xiidea.enginx.domain.agent.AgentJob;
import net.xiidea.enginx.domain.agent.AgentJobPayload;
import net.xiidea.enginx.domain.agent.AgentJobStatus;
import net.xiidea.enginx.domain.agent.AgentJobType;
import net.xiidea.enginx.domain.certificate.AcmeChallengePublisher;
import net.xiidea.enginx.domain.certificate.CertificateIssuanceException;
import net.xiidea.enginx.domain.deployment.NginxAgentPort;
import net.xiidea.enginx.domain.nginx.NginxInstance;
import net.xiidea.enginx.domain.nginx.NginxInstanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Publishes HTTP-01 challenge responses to every managed NGINX host.
 *
 * <p>Every host, not the one that serves the domain, for two reasons. The platform does not know
 * where a domain's DNS points — that is the authority's question to answer, and for a brand-new
 * domain there may be no site configured at all yet. And the token is a public value the authority
 * is about to fetch over plain HTTP, so publishing it more widely costs nothing.
 *
 * <p>A host the platform dials is called directly. A host that calls in cannot be, so it is sent a
 * job, and this waits — bounded — for the host to report it done: the authority must not be asked
 * to look before the response is in place. A host that does not confirm in time is not counted,
 * and its job still runs whenever it next polls, which is harmless.
 *
 * <p>Publishing must succeed somewhere. If no host accepted the token the validation is certain to
 * fail, and failing here means it fails before the authority is asked to look — which matters,
 * because a failed validation consumes rate-limit budget that a refused publish does not.
 */
@Component
public class AgentAcmeChallengePublisher implements AcmeChallengePublisher {

    private static final Logger log = LoggerFactory.getLogger(AgentAcmeChallengePublisher.class);
    private static final Duration RECHECK = Duration.ofMillis(250);

    private final NginxInstanceRepository instances;
    private final NginxAgentPort agent;
    private final AgentJobQueue jobs;
    private final Duration pullConfirmTimeout;

    public AgentAcmeChallengePublisher(NginxInstanceRepository instances, NginxAgentPort agent, AgentJobQueue jobs,
                                       @Value("${enginx.acme.pull-confirm-timeout:20s}") Duration pullConfirmTimeout) {
        this.instances = instances;
        this.agent = agent;
        this.jobs = jobs;
        this.pullConfirmTimeout = pullConfirmTimeout;
    }

    @Override
    public void publish(String token, String authorization) {
        List<NginxInstance> hosts = instances.findAll();
        if (hosts.isEmpty()) {
            throw new CertificateIssuanceException(
                    "No NGINX instances are registered, so there is nowhere to answer the challenge from", false);
        }

        int published = 0;
        List<AgentJob> queued = new ArrayList<>();
        for (NginxInstance instance : hosts) {
            if (instance.connectivityMode().isPull()) {
                queued.add(jobs.enqueue(instance.id(), null, AgentJobType.PUBLISH_ACME_CHALLENGE,
                        AgentJobPayload.forAcmeChallenge(token, authorization)));
                continue;
            }
            try {
                agent.publishAcmeChallenge(instance, token, authorization);
                published++;
            } catch (RuntimeException e) {
                // One unreachable host is survivable: the authority only needs to reach whichever
                // one the domain resolves to.
                log.warn("Could not publish the ACME challenge to {}: {}", instance.name(), e.getMessage());
            }
        }
        published += awaitConfirmation(queued, hosts);

        if (published == 0) {
            throw new CertificateIssuanceException(
                    "The challenge could not be published to any NGINX instance, so validation would certainly fail",
                    true);
        }
        log.info("Published ACME challenge to {} of {} instance(s)", published, hosts.size());
    }

    /**
     * Waits until every queued job has reported, or the timeout passes.
     *
     * <p>Polls the job rows rather than being told: the result arrives on another request, from
     * the host, and this runs outside any transaction, so each check sees what that request committed.
     *
     * @return how many hosts confirmed the challenge is in place
     */
    private int awaitConfirmation(List<AgentJob> queued, List<NginxInstance> hosts) {
        if (queued.isEmpty()) {
            return 0;
        }
        Map<UUID, String> names = hosts.stream()
                .collect(Collectors.toMap(NginxInstance::id, NginxInstance::name));
        Map<UUID, AgentJob> pending = queued.stream()
                .collect(Collectors.toMap(AgentJob::id, Function.identity()));
        Instant deadline = Instant.now().plus(pullConfirmTimeout);
        int confirmed = 0;

        while (!pending.isEmpty()) {
            for (Iterator<AgentJob> it = pending.values().iterator(); it.hasNext(); ) {
                AgentJob job = jobs.find(it.next().id()).orElse(null);
                if (job == null) {
                    it.remove();
                } else if (job.status() == AgentJobStatus.SUCCEEDED) {
                    confirmed++;
                    it.remove();
                } else if (job.status() == AgentJobStatus.FAILED) {
                    log.warn("Could not publish the ACME challenge to {}: {}",
                            names.get(job.nginxInstanceId()), job.error());
                    it.remove();
                }
            }
            if (pending.isEmpty() || Instant.now().isAfter(deadline)) {
                break;
            }
            try {
                Thread.sleep(RECHECK);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        for (AgentJob job : pending.values()) {
            log.warn("{} did not confirm the ACME challenge within {}; not counted",
                    names.get(job.nginxInstanceId()), pullConfirmTimeout);
        }
        return confirmed;
    }

    @Override
    public void withdraw(String token) {
        for (NginxInstance instance : instances.findAll()) {
            try {
                if (instance.connectivityMode().isPull()) {
                    // Not waited on: removal has no deadline, and a host that is away collects it later.
                    jobs.enqueue(instance.id(), null, AgentJobType.REMOVE_ACME_CHALLENGE,
                            AgentJobPayload.forAcmeWithdrawal(token));
                } else {
                    agent.removeAcmeChallenge(instance, token);
                }
            } catch (RuntimeException e) {
                // Best effort. A token left behind is inert: it proves nothing without the order
                // it belonged to, and the next issuance uses a different one.
                log.debug("Could not withdraw the ACME challenge from {}: {}", instance.name(), e.getMessage());
            }
        }
    }
}
