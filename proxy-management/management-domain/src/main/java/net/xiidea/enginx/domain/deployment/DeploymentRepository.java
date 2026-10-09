package net.xiidea.enginx.domain.deployment;

import net.xiidea.enginx.domain.shared.PageResult;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeploymentRepository {

    Deployment save(Deployment deployment);

    Optional<Deployment> findById(UUID id);

    PageResult<Deployment> search(UUID nginxInstanceId, List<DeploymentStatus> statuses, int page, int size);

    /** The most recent deployment for an instance, whatever its outcome. */
    Optional<Deployment> findLatestForInstance(UUID nginxInstanceId);

    /** The most recent deployment for an instance other than {@code excluding}, whatever its outcome. */
    Optional<Deployment> findLatestForInstanceExcept(UUID nginxInstanceId, UUID excluding);

    /** Whether a deployment is already queued or running for this instance. */
    boolean hasActiveDeployment(UUID nginxInstanceId);
}
