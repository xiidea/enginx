package net.xiidea.enginx.support;

import net.xiidea.enginx.domain.deployment.AgentActivation;
import net.xiidea.enginx.domain.deployment.AgentException;
import net.xiidea.enginx.domain.deployment.AgentStatus;
import net.xiidea.enginx.domain.deployment.AgentValidationFailedException;
import net.xiidea.enginx.domain.deployment.ConfigBundle;
import net.xiidea.enginx.domain.deployment.NginxAgentPort;
import net.xiidea.enginx.domain.deployment.SiteVerification;
import net.xiidea.enginx.domain.deployment.UpstreamReachability;
import net.xiidea.enginx.domain.nginx.NginxInstance;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A programmable stand-in for a real agent.
 *
 * <p>Substituted at the same port the HTTP client implements, so the dispatcher, the outbox and
 * the persistence of the phase history are all the production code paths. Only the wire is faked,
 * which is exactly the part the Go tests cover in isolation.
 */
public class TestAgent implements NginxAgentPort {

    public enum Behaviour {
        SUCCEED,
        /** nginx -t rejected the configuration. Nothing changed on the host. */
        FAIL_VALIDATION,
        /** The agent could not be reached. Worth another attempt. */
        UNREACHABLE,
        /** A considered refusal. Retrying would be pointless. */
        REFUSE
    }

    private volatile Behaviour behaviour = Behaviour.SUCCEED;
    /** What the host claims to be serving. Null means "whatever was last activated". */
    private volatile String reportedActiveBundleId;
    /** Whether verification finds every name answered. */
    private volatile boolean sitesRespond = true;
    private volatile boolean upstreamsReachable = true;
    /** Whether the probe call itself fails, as an unreachable agent would. */
    private volatile boolean verificationFails;
    private volatile boolean answeredByAnotherServer;
    private final List<String> verifiedNames = new ArrayList<>();
    private final AtomicInteger stageCalls = new AtomicInteger();
    private final AtomicInteger activateCalls = new AtomicInteger();
    private final List<String> idempotencyKeys = new ArrayList<>();
    private final List<String> publishedChallenges = new ArrayList<>();

    public void behave(Behaviour next) {
        this.behaviour = next;
    }

    /** Makes the host report a different bundle than the platform activated, as drift does. */
    public void reportActiveBundle(String bundleId) {
        this.reportedActiveBundleId = bundleId;
    }

    public void sitesRespond(boolean respond) {
        this.sitesRespond = respond;
    }

    public void failVerification(boolean fail) {
        this.verificationFails = fail;
    }

    public void upstreamsReachable(boolean reachable) {
        this.upstreamsReachable = reachable;
    }

    public List<String> verifiedNames() {
        synchronized (verifiedNames) {
            return List.copyOf(verifiedNames);
        }
    }

    public void reset() {
        behaviour = Behaviour.SUCCEED;
        reportedActiveBundleId = null;
        sitesRespond = true;
        upstreamsReachable = true;
        verificationFails = false;
        answeredByAnotherServer = false;
        synchronized (verifiedNames) {
            verifiedNames.clear();
        }
        stageCalls.set(0);
        activateCalls.set(0);
        synchronized (idempotencyKeys) {
            idempotencyKeys.clear();
        }
        synchronized (publishedChallenges) {
            publishedChallenges.clear();
        }
    }

    public int stageCalls() {
        return stageCalls.get();
    }

    public int activateCalls() {
        return activateCalls.get();
    }

    public List<String> idempotencyKeys() {
        synchronized (idempotencyKeys) {
            return List.copyOf(idempotencyKeys);
        }
    }

    @Override
    public void stage(NginxInstance instance, ConfigBundle bundle, String idempotencyKey) {
        stageCalls.incrementAndGet();
        synchronized (idempotencyKeys) {
            idempotencyKeys.add(idempotencyKey);
        }
        if (behaviour == Behaviour.UNREACHABLE) {
            throw new AgentException("connection refused", true);
        }
        if (behaviour == Behaviour.REFUSE) {
            throw new AgentException("the agent refused the bundle", false);
        }
    }

    @Override
    public AgentActivation activate(NginxInstance instance, ConfigBundle bundle, String idempotencyKey,
                                    boolean reload) {
        activateCalls.incrementAndGet();
        if (behaviour == Behaviour.FAIL_VALIDATION) {
            throw new AgentValidationFailedException(
                    "nginx: [emerg] host not found in upstream \"nowhere.invalid\"");
        }
        return new AgentActivation(bundle.id().toString(), null,
                "nginx: configuration file /etc/nginx/nginx.conf test is successful",
                "1.27.5", false, false);
    }

    @Override
    public AgentStatus status(NginxInstance instance) {
        return new AgentStatus("test", "1.27.5", true, reportedActiveBundleId, true, "ok", List.of());
    }

    @Override
    public List<SiteVerification> verify(NginxInstance instance, List<VerifyTarget> targets) {
        if (verificationFails) {
            throw new AgentException("the agent could not be reached", true);
        }
        synchronized (verifiedNames) {
            targets.forEach(target -> verifiedNames.add(target.serverName()));
        }
        return targets.stream()
                .map(target -> !sitesRespond
                        ? new SiteVerification(target.serverName(), false, 0, false, "connection refused")
                        // Something answers, but only an identified answer is this site.
                        : new SiteVerification(target.serverName(), true, 200, !answeredByAnotherServer, null))
                .toList();
    }

    /** Makes every name answered by something other than its own server block, as a default page does. */
    public void answerFromAnotherServer(boolean another) {
        this.answeredByAnotherServer = another;
    }

    @Override
    public List<UpstreamReachability> checkUpstreams(NginxInstance instance, List<UpstreamTarget> targets) {
        return targets.stream()
                .map(target -> new UpstreamReachability(target.host(), target.port(),
                        upstreamsReachable, upstreamsReachable ? null : "connection refused"))
                .toList();
    }

    @Override
    public void discard(NginxInstance instance, String bundleId) {
    }

    @Override
    public void publishAcmeChallenge(NginxInstance instance, String token, String authorization) {
        synchronized (publishedChallenges) {
            publishedChallenges.add(token);
        }
    }

    @Override
    public void removeAcmeChallenge(NginxInstance instance, String token) {
        synchronized (publishedChallenges) {
            publishedChallenges.remove(token);
        }
    }

    /** Tokens currently published. Empty after a completed issuance, successful or not. */
    public List<String> publishedChallenges() {
        synchronized (publishedChallenges) {
            return List.copyOf(publishedChallenges);
        }
    }

    @TestConfiguration
    public static class Config {

        @Bean
        @Primary
        public TestAgent testAgent() {
            return new TestAgent();
        }
    }
}
