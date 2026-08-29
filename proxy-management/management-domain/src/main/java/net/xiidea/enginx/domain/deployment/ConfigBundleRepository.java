package net.xiidea.enginx.domain.deployment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConfigBundleRepository {

    ConfigBundle save(ConfigBundle bundle);

    Optional<ConfigBundle> findById(UUID id);

    Optional<ConfigBundle> findByInstanceAndHash(UUID nginxInstanceId, String contentHash);

    /** The bundle the instance is believed to be serving. */
    Optional<ConfigBundle> findActiveForInstance(UUID nginxInstanceId);

    long nextSequence(UUID nginxInstanceId);

    List<ConfigBundle> findRecentForInstance(UUID nginxInstanceId, int limit);

    /** Marks this bundle ACTIVE and demotes whatever was active before it to SUPERSEDED. */
    void markActive(UUID nginxInstanceId, UUID bundleId);

    void updateStatus(UUID bundleId, BundleStatus status);
}
