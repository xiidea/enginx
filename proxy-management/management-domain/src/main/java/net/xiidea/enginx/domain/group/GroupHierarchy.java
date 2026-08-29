package net.xiidea.enginx.domain.group;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Reads the domain group tree.
 *
 * <p>Separated from the repository because permission evaluation needs only these three
 * questions, and keeping them together makes the evaluator's dependency obvious and easy to
 * substitute in a test.
 */
public interface GroupHierarchy {

    /** The given groups plus everything beneath them. Used to widen a grant down the tree. */
    Set<UUID> descendantIdsOf(Collection<UUID> groupIds);

    /** The given groups plus everything above them. Used to widen a site's membership up the tree. */
    Set<UUID> ancestorIdsOf(Collection<UUID> groupIds);

    /** The groups a site is directly filed under. */
    Set<UUID> groupIdsForSite(UUID siteId);
}
