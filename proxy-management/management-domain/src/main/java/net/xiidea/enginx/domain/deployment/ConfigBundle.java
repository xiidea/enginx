package net.xiidea.enginx.domain.deployment;

import net.xiidea.enginx.domain.shared.ValidationException;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The complete intended configuration for one NGINX instance, as an immutable set of files.
 *
 * <p>The deployment unit is the whole instance rather than a single site (architecture decision
 * AD-3). {@code nginx -t} validates a configuration tree, not a file, so a per-site unit would
 * leave "which change broke this?" and "roll back one site" undefined whenever two sites share a
 * {@code server_name} or an upstream. Bundling the tree gives one validation result, one atomic
 * swap, one version, and a rollback target that is guaranteed to have been valid.
 *
 * <p>{@code contentHash} is derived from the files, which makes the bundle content-addressed: a
 * deployment whose hash already matches what the instance serves is a no-op, and that is what
 * makes redeployment idempotent without any extra bookkeeping.
 */
public record ConfigBundle(
        UUID id,
        UUID nginxInstanceId,
        long sequence,
        String contentHash,
        List<BundleFile> files,
        Set<UUID> siteIds,
        BundleStatus status,
        String createdBy,
        Instant createdAt) {

    public ConfigBundle {
        if (nginxInstanceId == null) {
            throw new ValidationException("nginxInstanceId", "A bundle belongs to an NGINX instance");
        }
        files = files == null ? List.of() : List.copyOf(files);
        siteIds = siteIds == null ? Set.of() : Set.copyOf(siteIds);
    }

    public ConfigBundle withStatus(BundleStatus newStatus) {
        return new ConfigBundle(id, nginxInstanceId, sequence, contentHash, files, siteIds,
                newStatus, createdBy, createdAt);
    }

    public ConfigBundle withIdentity(UUID newId, long newSequence) {
        return new ConfigBundle(newId, nginxInstanceId, newSequence, contentHash, files, siteIds,
                status, createdBy, createdAt);
    }

    /** Files safe to show a user: everything except private key material. */
    public List<BundleFile> publicFiles() {
        return files.stream().filter(file -> !file.sensitive()).toList();
    }

    public boolean hasSameContentAs(ConfigBundle other) {
        return other != null && contentHash.equals(other.contentHash());
    }
}
