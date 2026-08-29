package net.xiidea.enginx.application.observability;

import java.time.Duration;

/**
 * Reads the platform's operational counters.
 *
 * <p>A port because the efficient form of these questions is aggregate SQL, and loading every site
 * and certificate as an aggregate just to count them would make the metrics endpoint the most
 * expensive thing in the system.
 */
public interface PlatformStatisticsProvider {

    /**
     * @param agentSilenceThreshold how long an instance may go unheard-from before it counts as
     *                              unreachable
     */
    PlatformStatistics current(Duration agentSilenceThreshold);
}
