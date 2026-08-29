package net.xiidea.enginx.domain.permission;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface PermissionGrantRepository {

    PermissionGrant save(PermissionGrant grant);

    Optional<PermissionGrant> findById(UUID id);

    /**
     * Every unexpired grant addressed to any of these subject references. One query per request:
     * a caller has few grants, and loading them together keeps evaluation a pure in-memory step.
     */
    List<PermissionGrant> findActiveForSubjects(Collection<String> subjectRefs, Instant now);

    List<PermissionGrant> findBySubject(SubjectType subjectType, String subjectRef);

    /**
     * Which of these subject references a grant still names.
     *
     * <p>One query for a whole page. Asked before removing anything from the identity mirror:
     * a subject a grant names is one whose row is still doing work, because without it the grant
     * shows as a bare reference nobody can identify.
     */
    Set<String> subjectRefsWithGrants(Collection<String> subjectRefs);

    /**
     * An existing grant for the same subject over the same scope, if there is one.
     *
     * <p>A subject holds at most one grant per scope, so re-granting is a change of level rather
     * than a second, competing rule. Allowing two would leave an audit trail that says nothing
     * useful: the effective answer is the maximum, so the lower grant would be invisible.
     */
    Optional<PermissionGrant> findBySubjectAndScope(SubjectType subjectType, String subjectRef,
                                                    ScopeType scopeType, UUID scopeGroupId,
                                                    UUID scopeSiteId, String patternReversed);

    List<PermissionGrant> findByScopeGroup(UUID groupId);

    List<PermissionGrant> findByScopeSite(UUID siteId);

    List<PermissionGrant> findAll();

    void deleteById(UUID id);
}
