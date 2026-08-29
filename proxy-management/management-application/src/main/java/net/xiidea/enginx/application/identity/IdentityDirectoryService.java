package net.xiidea.enginx.application.identity;

import net.xiidea.enginx.application.permission.SitePermissionService;
import net.xiidea.enginx.application.shared.AuditRecorder;
import net.xiidea.enginx.application.shared.IdentityMirror;
import net.xiidea.enginx.domain.audit.AuditAction;
import net.xiidea.enginx.domain.permission.PermissionGrantRepository;
import net.xiidea.enginx.domain.shared.PageResult;
import net.xiidea.enginx.domain.shared.ValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The directory of everyone who has signed in, and the removal of entries that no longer earn
 * their place.
 *
 * <p>Nothing here is an authorization input. Removing an entry takes away a name the console can
 * show when authoring a grant; it does not revoke access, and it does not delete an account. A
 * subject removed by mistake reappears the next time its owner signs in.
 */
@Service
public class IdentityDirectoryService {

    private static final String RESOURCE_TYPE = "IDENTITY_SUBJECT";

    /** Below this, "dormant" would sweep up people who were merely on leave. */
    private static final Duration MINIMUM_DORMANCY = Duration.ofDays(30);

    private final IdentityMirror mirror;
    private final PermissionGrantRepository grants;
    private final SitePermissionService permissions;
    private final AuditRecorder audit;
    private final Clock clock;

    public IdentityDirectoryService(IdentityMirror mirror, PermissionGrantRepository grants,
                                    SitePermissionService permissions, AuditRecorder audit, Clock clock) {
        this.mirror = mirror;
        this.grants = grants;
        this.permissions = permissions;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * A page of the directory, optionally only the stale part of it.
     *
     * <p>Readable by anyone who administers any scope, because that is who authors grants and so
     * who needs to pick a name. Only removal is restricted further.
     */
    @Transactional(readOnly = true)
    public PageResult<Entry> find(Query query) {
        permissions.requireAnyAdminScope();

        PageResult<IdentityMirror.MirroredUser> page = mirror.findUsers(
                new IdentityMirror.DirectoryQuery(query.search(), query.staleOnly(),
                        dormantBefore(query.dormantForDays()), query.page(), query.size()));

        Set<String> granted = grants.subjectRefsWithGrants(
                page.content().stream().map(IdentityMirror.MirroredUser::subject).toList());

        return page.map(user -> new Entry(user, granted.contains(user.subject())));
    }

    /**
     * Removes one subject.
     *
     * <p>Global admin, unlike reading: the directory is shared, and an entry someone else is about
     * to grant to is not one scope's business to remove.
     */
    @Transactional
    public void forget(String subject) {
        permissions.requireGlobalAdmin();
        if (subject == null || subject.isBlank()) {
            throw new ValidationException("subject", "A subject reference is required");
        }

        mirror.forgetAll(List.of(subject));
        audit.success(AuditAction.IDENTITY_SUBJECT_FORGOTTEN, RESOURCE_TYPE, null, null,
                Map.of("subjectRef", subject, "reason", "removed individually"));
    }

    /**
     * Removes every stale entry in one go.
     *
     * <p>Entries a grant still names are skipped unless asked for explicitly. Removing one leaves
     * the grant showing a bare subject reference nobody can identify — which is worse than a stale
     * row, because it makes an access rule unreadable rather than merely a picker untidy. Revoking
     * the grant is a separate and deliberate act; this never does it.
     *
     * @return what was removed, and what was left behind
     */
    @Transactional
    public Result cleanup(Command command) {
        permissions.requireGlobalAdmin();

        List<IdentityMirror.MirroredUser> stale = mirror.findStale(dormantBefore(command.dormantForDays()));
        Set<String> refs = stale.stream().map(IdentityMirror.MirroredUser::subject)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> granted = grants.subjectRefsWithGrants(refs);

        List<String> removable = stale.stream()
                .map(IdentityMirror.MirroredUser::subject)
                .filter(subject -> command.includeGranted() || !granted.contains(subject))
                .toList();

        int removed = mirror.forgetAll(removable);
        int skipped = stale.size() - removable.size();

        if (removed > 0) {
            audit.success(AuditAction.IDENTITY_SUBJECT_FORGOTTEN, RESOURCE_TYPE, null, null,
                    Map.of("removed", String.valueOf(removed),
                            "skippedBecauseGranted", String.valueOf(skipped),
                            "dormantForDays", String.valueOf(command.dormantForDays()),
                            "subjectRefs", removable.toString()));
        }
        return new Result(removed, skipped, removable);
    }

    /**
     * The moment before which an entry counts as dormant, or null when dormancy is not in play.
     *
     * <p>A floor rather than a free choice: "not seen for a day" describes a weekend, and a
     * cleanup that quietly removed most of the directory would be indistinguishable from a bug.
     */
    private Instant dormantBefore(Integer dormantForDays) {
        if (dormantForDays == null) {
            return null;
        }
        Duration age = Duration.ofDays(dormantForDays);
        if (age.compareTo(MINIMUM_DORMANCY) < 0) {
            throw new ValidationException("dormantForDays",
                    "Dormancy must be at least " + MINIMUM_DORMANCY.toDays() + " days. Anything "
                            + "shorter describes a holiday rather than a departure.");
        }
        return clock.instant().minus(age);
    }

    /**
     * @param staleOnly      only entries that no longer resolve, or that have gone quiet
     * @param dormantForDays treat an entry unseen for this long as stale too. Null considers only
     *                       entries that provably no longer resolve.
     */
    public record Query(String search, boolean staleOnly, Integer dormantForDays, int page, int size) {
    }

    /** @param includeGranted also remove entries a permission grant still names */
    public record Command(Integer dormantForDays, boolean includeGranted) {
    }

    /** @param hasGrants whether a permission grant still names this subject */
    public record Entry(IdentityMirror.MirroredUser user, boolean hasGrants) {
    }

    public record Result(int removed, int skippedBecauseGranted, List<String> removedSubjects) {
    }
}
