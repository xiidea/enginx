package net.xiidea.enginx.domain.proxy;

import net.xiidea.enginx.domain.permission.AccessScope;
import net.xiidea.enginx.domain.shared.DomainName;
import net.xiidea.enginx.domain.shared.PageResult;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for the proxy site aggregate. Implemented in the infrastructure module. */
public interface ProxySiteRepository {

    ProxySite save(ProxySite site);

    Optional<ProxySite> findById(UUID id);

    /**
     * Several sites at once, for callers that already hold a set of ids.
     *
     * <p>Unscoped, unlike {@link #search}: the ids come from a relation the caller has already
     * been authorised to read, so filtering again here would be a second, different answer to a
     * question already settled. Callers holding ids from anywhere else must scope them first.
     */
    List<ProxySite> findAllById(Collection<UUID> ids);

    /**
     * @param excludeId a site to ignore, so an update can keep its own domain. May be null.
     */
    boolean existsByInstanceAndDomain(UUID nginxInstanceId, DomainName domain, UUID excludeId);

    /**
     * @param scope the sites the caller may see. Required rather than optional so that a listing
     *              cannot be written without deciding the question: an unscoped overload would
     *              eventually be called by accident, and the bug would be invisible until someone
     *              saw a domain they should not have.
     */
    PageResult<ProxySite> search(ProxySiteQuery query, AccessScope scope);

    /**
     * The sites this instance should be serving at {@code now}.
     *
     * <p>A site is included when the operator has enabled it and its activation window is open.
     * Sites in ERROR are included deliberately: the error describes a failed deployment attempt,
     * and dropping them would take a working site off the air because of an unrelated bad edit.
     *
     * <p>The predicate is evaluated against the window columns rather than the stored status, so
     * a bundle is correct even if the lifecycle sweep has not run yet. Trusting the status column
     * would make every deployment depend on a background job having caught up first.
     */
    List<ProxySite> findDeployableForInstance(UUID nginxInstanceId, Instant now);

    /**
     * Takes sites whose lifecycle has moved on, locking them for this worker.
     *
     * <p>Two transitions only: an active site whose expiry has passed, and a pending site whose
     * window has opened. Both are matched by the partial indexes created with the table, so the
     * sweep costs one index range scan per run however many sites exist.
     *
     * <p>Implemented with {@code FOR UPDATE SKIP LOCKED}, so a second worker takes a disjoint set
     * rather than waiting or duplicating. The locks are held until the caller's transaction
     * commits, which is what makes the status change and the deployment it triggers atomic.
     *
     * <p>Must be called inside a transaction.
     */
    List<ProxySite> claimSitesDueForLifecycle(Instant now, int limit);

    /**
     * Sites whose expiry falls before {@code cutoff}, including ones already expired.
     *
     * <p>Unscoped, unlike {@link #search}: the caller is the notification scan, which acts for the
     * platform rather than for a user. Every other listing takes an {@link AccessScope} precisely
     * so this distinction has to be made deliberately, which is what this comment is for.
     */
    List<ProxySite> findWithExpiryBefore(Instant cutoff, int limit);

    void deleteById(UUID id);
}
