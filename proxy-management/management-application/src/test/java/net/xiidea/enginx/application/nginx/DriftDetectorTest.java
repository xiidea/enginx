package net.xiidea.enginx.application.nginx;

import net.xiidea.enginx.application.shared.AuditRecorder;
import net.xiidea.enginx.domain.audit.AuditAction;
import net.xiidea.enginx.domain.deployment.AgentStatus;
import net.xiidea.enginx.domain.deployment.ConfigBundle;
import net.xiidea.enginx.domain.deployment.ConfigBundleRepository;
import net.xiidea.enginx.domain.deployment.DeploymentRepository;
import net.xiidea.enginx.domain.nginx.NginxInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DriftDetectorTest {

    private static final UUID INSTANCE_ID = UUID.randomUUID();
    private static final UUID EXPECTED_BUNDLE = UUID.randomUUID();

    private ConfigBundleRepository bundles;
    private DeploymentRepository deployments;
    private AuditRecorder audit;
    private DriftDetector detector;
    private NginxInstance instance;

    @BeforeEach
    void setUp() {
        bundles = mock(ConfigBundleRepository.class);
        deployments = mock(DeploymentRepository.class);
        audit = mock(AuditRecorder.class);
        detector = new DriftDetector(bundles, deployments, audit);

        instance = NginxInstance.rehydrate(INSTANCE_ID, "nginx-1", "nginx-1.example.com",
                java.net.URI.create("https://nginx-1.example.com:8443"),
                "A".repeat(64), "PRODUCTION", net.xiidea.enginx.domain.nginx.InstanceStatus.ONLINE,
                "1.27.5", "0.1.0", Instant.now(), Instant.now(), Instant.now(), 0L);
    }

    private AgentStatus reporting(String activeBundleId) {
        return new AgentStatus("0.1.0", "1.27.5", true, activeBundleId, true, "ok", java.util.List.of());
    }

    // A real bundle rather than a mock: ConfigBundle is a record, and a record's accessors are
    // exactly the part a mock would have to fake.
    private ConfigBundle expectedBundle() {
        return new ConfigBundle(EXPECTED_BUNDLE, INSTANCE_ID, 1, "sha256:abc",
                java.util.List.of(), java.util.Set.of(),
                net.xiidea.enginx.domain.deployment.BundleStatus.ACTIVE, "ada", Instant.now());
    }

    @Test
    @DisplayName("a host serving the bundle we activated has not drifted")
    void matchingBundleIsNotDrift() {
        when(deployments.hasActiveDeployment(INSTANCE_ID)).thenReturn(false);
        when(bundles.findActiveForInstance(INSTANCE_ID)).thenReturn(Optional.of(expectedBundle()));

        assertThat(detector.detect(instance, reporting(EXPECTED_BUNDLE.toString()))).isEmpty();
    }

    @Test
    @DisplayName("a host serving a different bundle has drifted")
    void differentBundleIsDrift() {
        when(deployments.hasActiveDeployment(INSTANCE_ID)).thenReturn(false);
        when(bundles.findActiveForInstance(INSTANCE_ID)).thenReturn(Optional.of(expectedBundle()));

        String somethingElse = UUID.randomUUID().toString();
        Optional<DriftDetector.Drift> drift = detector.detect(instance, reporting(somethingElse));

        assertThat(drift).isPresent();
        assertThat(drift.get().expectedBundleId()).isEqualTo(EXPECTED_BUNDLE.toString());
        assertThat(drift.get().actualBundleId()).isEqualTo(somethingElse);
    }

    @Test
    @DisplayName("a host serving no managed configuration at all has drifted")
    void missingBundleIsDrift() {
        when(deployments.hasActiveDeployment(INSTANCE_ID)).thenReturn(false);
        when(bundles.findActiveForInstance(INSTANCE_ID)).thenReturn(Optional.of(expectedBundle()));

        Optional<DriftDetector.Drift> drift = detector.detect(instance, reporting(null));

        assertThat(drift).isPresent();
        assertThat(drift.get().actualBundleId()).isNull();
        // The message has to distinguish a wiped host from one serving the wrong thing: the fix is
        // the same, but the cause is not, and an operator reads this before deciding what happened.
        assertThat(drift.get().describeActual()).isEqualTo("no managed configuration");
    }

    /**
     * The trap that decides whether this feature survives contact with production. During a
     * deployment the agent has already activated the new bundle while the database still records
     * the old one as active, so every single deployment would raise drift — and a reconciler that
     * cries wolf on every deploy is switched off within a week, taking the real detections with it.
     */
    @Test
    @DisplayName("a deployment in flight is not drift, however different the two bundles look")
    void deploymentInFlightIsNotDrift() {
        when(deployments.hasActiveDeployment(INSTANCE_ID)).thenReturn(true);

        assertThat(detector.detect(instance, reporting(UUID.randomUUID().toString()))).isEmpty();
        // Not even asked: the answer could not change the outcome.
        verify(bundles, never()).findActiveForInstance(any());
    }

    @Test
    @DisplayName("a host never deployed to has nothing to have drifted from")
    void neverDeployedIsNotDrift() {
        when(deployments.hasActiveDeployment(INSTANCE_ID)).thenReturn(false);
        when(bundles.findActiveForInstance(INSTANCE_ID)).thenReturn(Optional.empty());

        assertThat(detector.detect(instance, reporting("something-from-elsewhere"))).isEmpty();
    }

    @Test
    @DisplayName("drift is audited, because who changed a host is what the trail exists to answer")
    void driftIsAudited() {
        DriftDetector.Drift drift = new DriftDetector.Drift(instance, EXPECTED_BUNDLE.toString(), "other");

        detector.record(drift);

        verify(audit).failure(eq(AuditAction.NGINX_INSTANCE_DRIFTED), eq("NGINX_INSTANCE"),
                eq(INSTANCE_ID), org.mockito.ArgumentMatchers.contains("other"));
    }

    @Test
    @DisplayName("detection never remediates")
    void detectionNeverRemediates() {
        when(deployments.hasActiveDeployment(INSTANCE_ID)).thenReturn(false);
        when(bundles.findActiveForInstance(INSTANCE_ID)).thenReturn(Optional.of(expectedBundle()));

        detector.detect(instance, reporting(UUID.randomUUID().toString()));

        // R4 is explicit: unexpected drift is frequently a person mid-incident, and a platform
        // that silently reverts them is one they disconnect from the host before they next need it.
        verify(bundles, never()).markActive(any(), any());
        verify(deployments, never()).save(any());
        verify(audit, never()).success(any(), any(), any(), isNull(), isNull());
    }
}
