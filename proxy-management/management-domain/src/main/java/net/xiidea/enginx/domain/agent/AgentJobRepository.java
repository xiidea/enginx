package net.xiidea.enginx.domain.agent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentJobRepository {

    AgentJob save(AgentJob job);

    Optional<AgentJob> findById(UUID id);

    /**
     * The oldest job this host may run next, if there is one.
     *
     * <p>Returns nothing while the host already holds a leased job: one at a time per instance is
     * how deployments stay serialised (risk R2), and two agents swapping the same symlink at once
     * is precisely what that exists to prevent.
     */
    Optional<AgentJob> findNextClaimable(UUID nginxInstanceId, Instant now);

    /** Leased jobs whose holder has run out of time to report. */
    List<AgentJob> findExpiredLeases(Instant now, int limit);

    /** Queued or leased work for a deployment, so a failure can cancel what has not run yet. */
    List<AgentJob> findPendingForDeployment(UUID deploymentId);
}
