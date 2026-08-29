package net.xiidea.enginx.application.shared;

import net.xiidea.enginx.domain.shared.PageResult;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Records who has signed in, so that permissions can be granted by picking a name instead of by
 * pasting a subject claim.
 *
 * <p>This is a convenience index, never an authorization input. Every access decision reads the
 * caller's roles and groups from their token; if this mirror were stale, missing or maliciously
 * edited, no permission check would change. That separation is what makes it safe to populate it
 * opportunistically rather than keeping it rigorously in step with Keycloak.
 */
public interface IdentityMirror {

    void recordSeen(String subject, String username, String email, String displayName, Set<String> groupPaths);

    /**
     * Users who have signed in, so a grant can be addressed by name.
     *
     * <p>Paged rather than listed whole: this table gains a row for every person who ever signs
     * in and never loses one on its own, so on a real realm it outgrows a dropdown quickly.
     *
     * @param search matched against username and display name, case-insensitively; null or blank
     *               matches everyone
     */
    PageResult<MirroredUser> findUsers(DirectoryQuery query);

    /**
     * Every stale entry, unpaged, for a bulk cleanup that has to act on all of them at once.
     *
     * <p>Separate from {@link #findUsers} because paging through a list while deleting from it is
     * a well-known way to skip half of it.
     */
    List<MirroredUser> findStale(Instant dormantBefore);

    /** @return how many were removed */
    int forgetAll(Collection<String> subjects);

    /**
     * @param staleOnly     restrict to entries that no longer resolve, or that have gone quiet
     * @param dormantBefore counts an entry as stale when it was last seen before this. Null means
     *                      only entries that provably no longer resolve are stale -- a person who
     *                      has not signed in for a year is not necessarily gone.
     */
    record DirectoryQuery(String search, boolean staleOnly, Instant dormantBefore, int page, int size) {
    }

    /**
     * Everyone the mirror has seen under this username, most recently seen first.
     *
     * <p>A list rather than one result because a username is not unique here: the mirror is keyed
     * by subject, and two providers can both have an {@code admin}. Ordering by recency lets a
     * caller that needs exactly one pick the likeliest.
     */
    List<MirroredUser> findByUsername(String username);

    /** Group paths seen in tokens, so a grant can be addressed to a team. */
    List<MirroredGroup> listGroups();

    /**
     * Removes a subject from the mirror.
     *
     * <p>Called when the platform deletes an account it owns. Without it the picker keeps
     * offering a subject that can no longer authenticate, and a grant made to it silently does
     * nothing.
     */
    void forget(String subject);

    /**
     * @param present whether the subject still resolves to an account. Only ever false for a
     *                local one: an external provider's users are not this platform's to track, so
     *                its subjects are reported present and left to the provider.
     */
    record MirroredUser(String subject, String username, String email, String displayName,
                        Instant lastLoginAt, boolean present) {
    }

    record MirroredGroup(String path, String name) {
    }
}
