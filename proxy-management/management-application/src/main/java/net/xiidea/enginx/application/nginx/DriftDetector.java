package net.xiidea.enginx.application.nginx;

import net.xiidea.enginx.application.shared.AuditRecorder;
import net.xiidea.enginx.domain.audit.AuditAction;
import net.xiidea.enginx.domain.deployment.AgentStatus;
import net.xiidea.enginx.domain.deployment.ConfigBundle;
import net.xiidea.enginx.domain.deployment.ConfigBundleRepository;
import net.xiidea.enginx.domain.deployment.DeploymentRepository;
import net.xiidea.enginx.domain.nginx.NginxInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Compares what a host is serving with what the platform believes it put there.
 *
 * <p>Closes R4. Nothing else in the system would notice a host that was rebuilt, restored from a
 * snapshot, or edited by hand: deployments would keep succeeding, health would stay green, and the
 * database's belief about the estate would be quietly wrong. The agent has always reported its
 * active bundle; until now nobody read it.
 *
 * <p>Detection only. R4 is explicit that drift must not be auto-remediated, and the reason is
 * worth restating where the code is: unexpected drift is frequently a person working on an
 * incident, and a platform that silently reverts them is one they will disconnect from the host
 * before they next need it.
 */
@Component
public class DriftDetector {

    private static final Logger log = LoggerFactory.getLogger(DriftDetector.class);
    private static final String RESOURCE_TYPE = "NGINX_INSTANCE";

    private final ConfigBundleRepository bundles;
    private final DeploymentRepository deployments;
    private final AuditRecorder audit;

    public DriftDetector(ConfigBundleRepository bundles, DeploymentRepository deployments, AuditRecorder audit) {
        this.bundles = bundles;
        this.deployments = deployments;
        this.audit = audit;
    }

    /**
     * @return the drift found, or empty when the host matches, is mid-deployment, or has never
     *         been deployed to
     */
    public Optional<Drift> detect(NginxInstance instance, AgentStatus status) {
        // Mid-deployment the two legitimately differ: the bundle has been staged and activated on
        // the host before the database records it as active. A reconciler that does not know this
        // reports drift on every single deployment, and is switched off within a week — taking
        // the real detections with it.
        if (deployments.hasActiveDeployment(instance.id())) {
            return Optional.empty();
        }

        Optional<ConfigBundle> active = bundles.findActiveForInstance(instance.id());
        if (active.isEmpty()) {
            // Never deployed to. Whatever the host serves came from somewhere else, but the
            // platform has no expectation to compare against, so it has nothing to report.
            return Optional.empty();
        }

        String expected = active.get().id().toString();
        String actual = status.activeBundleId();

        if (actual == null || actual.isBlank()) {
            // The agent answered but serves no managed bundle at all: the releases directory was
            // wiped, or the host was rebuilt. Distinct from serving the wrong one, and worth
            // saying so, because the fix is the same but the cause is not.
            return Optional.of(new Drift(instance, expected, null));
        }
        if (expected.equals(actual)) {
            return Optional.empty();
        }
        return Optional.of(new Drift(instance, expected, actual));
    }

    /**
     * Records the drift. Audited rather than merely logged, because "who changed this host, and
     * when did we notice" is exactly the question the audit trail exists to answer.
     */
    public void record(Drift drift) {
        log.warn("Configuration drift on {} ({}): expected bundle {}, host is serving {}",
                drift.instance().name(), drift.instance().hostname(),
                drift.expectedBundleId(), drift.describeActual());

        audit.failure(AuditAction.NGINX_INSTANCE_DRIFTED, RESOURCE_TYPE, drift.instance().id(),
                "Expected bundle " + drift.expectedBundleId() + ", host is serving " + drift.describeActual());
    }

    /**
     * @param actualBundleId what the host reports serving, or null when it serves no managed
     *                       bundle at all
     */
    public record Drift(NginxInstance instance, String expectedBundleId, String actualBundleId) {

        public String describeActual() {
            return actualBundleId == null ? "no managed configuration" : actualBundleId;
        }

        public Map<String, Object> details() {
            return Map.of(
                    "expectedBundleId", expectedBundleId,
                    "actualBundleId", describeActual());
        }
    }
}
