package net.xiidea.enginx.observability;

import org.springframework.boot.health.contributor.Status;

/**
 * A status that means "someone should look at this", short of "this service is broken".
 *
 * <p>It exists because of a distinction the built-in statuses cannot express here. The indicators
 * in this package report on the <em>managed estate</em> — agents, certificates, undispatched work —
 * not on the management service itself. An unreachable agent is a real problem, but reporting it as
 * DOWN would return 503 from {@code /actuator/health}, and an orchestrator would then restart or
 * de-register the one service an operator needs in order to fix it. That is the failure mode where
 * monitoring makes an incident worse.
 *
 * <p>So WARNING maps to HTTP 200 and sorts below UP: visible on the endpoint and alertable through
 * the metrics, without ever taking the platform out of rotation for a fault it is reporting rather
 * than suffering. Configured in {@code application.yml} under {@code management.endpoint.health.status}.
 */
public final class PlatformStatus {

    public static final Status WARNING = new Status("WARNING", "Needs attention; the service itself is serving");

    private PlatformStatus() {
    }
}
