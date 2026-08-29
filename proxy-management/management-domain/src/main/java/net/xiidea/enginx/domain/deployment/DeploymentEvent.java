package net.xiidea.enginx.domain.deployment;

import java.time.Instant;
import java.util.UUID;

public record DeploymentEvent(UUID id, UUID deploymentId, DeploymentPhase phase,
                              EventResult result, String detail, Instant at) {

    public enum EventResult {
        STARTED,
        SUCCESS,
        FAILURE,
        SKIPPED
    }

    public static DeploymentEvent of(UUID deploymentId, DeploymentPhase phase, EventResult result,
                                     String detail, Instant at) {
        return new DeploymentEvent(UUID.randomUUID(), deploymentId, phase, result, truncate(detail), at);
    }

    /** Agent output can be long; the detail column is bounded and the full text lives in the log. */
    private static String truncate(String detail) {
        if (detail == null || detail.length() <= 4000) {
            return detail;
        }
        return detail.substring(0, 4000) + "… (truncated)";
    }
}
