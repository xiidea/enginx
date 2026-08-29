package net.xiidea.enginx.application.shared;

import java.time.Instant;
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

    /** Users who have signed in, so a grant can be addressed by name. */
    List<MirroredUser> listUsers();

    /** Group paths seen in tokens, so a grant can be addressed to a team. */
    List<MirroredGroup> listGroups();

    record MirroredUser(String subject, String username, String email, String displayName, Instant lastLoginAt) {
    }

    record MirroredGroup(String path, String name) {
    }
}
