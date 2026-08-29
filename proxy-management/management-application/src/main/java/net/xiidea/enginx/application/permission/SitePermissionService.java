package net.xiidea.enginx.application.permission;

import net.xiidea.enginx.application.shared.AuditRecorder;
import net.xiidea.enginx.application.shared.SubjectProvider;
import net.xiidea.enginx.domain.audit.AuditAction;
import net.xiidea.enginx.domain.group.DomainGroup;
import net.xiidea.enginx.domain.group.DomainGroupRepository;
import net.xiidea.enginx.domain.permission.AccessScope;
import net.xiidea.enginx.domain.permission.AuthenticatedSubject;
import net.xiidea.enginx.domain.permission.DomainPattern;
import net.xiidea.enginx.domain.permission.PermissionEvaluationService;
import net.xiidea.enginx.domain.permission.PermissionGrant;
import net.xiidea.enginx.domain.permission.PermissionGrantRepository;
import net.xiidea.enginx.domain.permission.ScopeType;
import net.xiidea.enginx.domain.permission.SubjectType;
import net.xiidea.enginx.domain.permission.PermissionLevel;
import net.xiidea.enginx.domain.permission.SiteAuthorizationContext;
import net.xiidea.enginx.domain.proxy.ProxySite;
import net.xiidea.enginx.domain.shared.DomainName;
import net.xiidea.enginx.domain.shared.NotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The single place authorization decisions are made and enforced.
 *
 * <p>Every method that can refuse does so by throwing, never by returning a boolean a caller
 * might forget to check. The read-only variants exist for the API to shape responses, and are
 * named so that a `can…` result is obviously advisory while a `require…` call is the guard.
 *
 * <p>Denials are audited here rather than at the web layer, so a refusal is recorded wherever it
 * originates, including from a scheduled job that never touches HTTP.
 */
@Service
public class SitePermissionService {

    private final PermissionEvaluationService evaluator;
    private final PermissionGrantRepository grants;
    private final DomainGroupRepository groups;
    private final SubjectProvider subjects;
    private final AuditRecorder audit;
    private final Clock clock;

    public SitePermissionService(PermissionEvaluationService evaluator,
                                 PermissionGrantRepository grants,
                                 DomainGroupRepository groups,
                                 SubjectProvider subjects,
                                 AuditRecorder audit,
                                 Clock clock) {
        this.evaluator = evaluator;
        this.grants = grants;
        this.groups = groups;
        this.subjects = subjects;
        this.audit = audit;
        this.clock = clock;
    }

    // ---- sites -------------------------------------------------------------

    @Transactional(readOnly = true)
    public PermissionLevel effectiveLevel(ProxySite site) {
        AuthenticatedSubject subject = subjects.currentSubject();
        return evaluator.effectiveLevel(subject, contextFor(site), grantsFor(subject), clock.instant());
    }

    /**
     * Enforces access to a site, distinguishing "you may not see this" from "you may not do this".
     *
     * <p>A caller who cannot even read the site is told it does not exist. Returning 403 there
     * would turn the error code into an existence oracle: anyone could enumerate which domains
     * the platform manages by watching 403 and 404 diverge. A caller who can read it but lacks
     * the level for this operation gets a plain refusal, because they already know it exists.
     *
     * <p>Both outcomes are audited, so the distinction hides information from the caller without
     * hiding anything from the operator.
     */
    @Transactional(readOnly = true)
    public void requireSiteAccess(ProxySite site, PermissionLevel required) {
        PermissionLevel level = effectiveLevel(site);

        if (level == null || !level.satisfies(PermissionLevel.READ)) {
            audit.denied(AuditAction.ACCESS_DENIED, "PROXY_SITE", site.id(),
                    "No read access to " + site.domain().value());
            throw new NotFoundException("PROXY_SITE", site.id());
        }
        if (!level.satisfies(required)) {
            deny("PROXY_SITE", site.id(),
                    "Requires " + required + " on " + site.domain().value() + ", holds " + level);
        }
    }

    /**
     * Creating a domain needs authority over the namespace, which only a global or pattern grant
     * confers. See {@code PermissionEvaluationService#canCreate} for why a group grant must not
     * be enough.
     */
    @Transactional(readOnly = true)
    public void requireCreatePermission(DomainName domain) {
        AuthenticatedSubject subject = subjects.currentSubject();
        if (!evaluator.canCreate(subject, domain.reversed(), grantsFor(subject), clock.instant())) {
            deny("PROXY_SITE", null,
                    "Requires MANAGE over the namespace of " + domain.value()
                            + ", granted globally or by domain pattern");
        }
    }

    /** The predicate a listing must apply. Never post-filter a page with this. */
    @Transactional(readOnly = true)
    /**
     * What some <em>other</em> subject can already reach, for the grant preview.
     *
     * <p>Deliberately not an authorization decision about the caller — it answers "what would this
     * grant actually add for them", so it is only ever used to annotate a preview the caller has
     * already been authorised to see. It reads the subject's stored grants only: realm roles live
     * in that person's token, which nobody else can inspect, so a grant may appear to add access
     * the subject's role floor already gave them. That errs toward showing more reach rather than
     * less, which is the safe direction for a warning.
     */
    public AccessScope scopeOfSubject(SubjectType subjectType, String subjectRef, PermissionLevel atLeast) {
        List<PermissionGrant> theirs = grants.findBySubject(subjectType, subjectRef).stream()
                .filter(grant -> grant.isActiveAt(clock.instant()))
                .filter(grant -> grant.level().satisfies(atLeast))
                .toList();

        if (theirs.stream().anyMatch(grant -> grant.scopeType() == ScopeType.GLOBAL)) {
            return AccessScope.all();
        }
        return AccessScope.restricted(
                theirs.stream().filter(g -> g.scopeType() == ScopeType.SITE)
                        .map(PermissionGrant::scopeSiteId).collect(java.util.stream.Collectors.toSet()),
                theirs.stream().filter(g -> g.scopeType() == ScopeType.DOMAIN_GROUP)
                        .map(PermissionGrant::scopeGroupId).collect(java.util.stream.Collectors.toSet()),
                theirs.stream().filter(g -> g.scopeType() == ScopeType.DOMAIN_PATTERN)
                        .map(PermissionGrant::domainPattern).toList());
    }

    public AccessScope accessibleScope(PermissionLevel atLeast) {
        AuthenticatedSubject subject = subjects.currentSubject();
        return evaluator.accessibleScope(subject, grantsFor(subject), atLeast, clock.instant(), groups);
    }

    // ---- domain groups -----------------------------------------------------

    @Transactional(readOnly = true)
    public PermissionLevel effectiveGroupLevel(DomainGroup group) {
        AuthenticatedSubject subject = subjects.currentSubject();
        return evaluator.effectiveGroupLevel(subject, groupAncestry(group.id()), grantsFor(subject), clock.instant());
    }

    @Transactional(readOnly = true)
    public void requireGroupPermission(DomainGroup group, PermissionLevel required) {
        PermissionLevel level = effectiveGroupLevel(group);
        if (level == null || !level.satisfies(required)) {
            deny("DOMAIN_GROUP", group.id(), "Requires " + required + " on group " + group.path());
        }
    }

    /**
     * Creating a root group is a platform-wide act, so it needs a global grant rather than
     * authority over some existing branch.
     */
    @Transactional(readOnly = true)
    public void requireRootGroupCreationPermission() {
        AuthenticatedSubject subject = subjects.currentSubject();
        PermissionLevel level = evaluator.effectiveGroupLevel(subject, Set.of(), grantsFor(subject), clock.instant());
        if (level == null || !level.satisfies(PermissionLevel.MANAGE)) {
            deny("DOMAIN_GROUP", null, "Creating a top-level group requires global MANAGE");
        }
    }

    /**
     * Filing a site into a group requires authority over <em>both</em>.
     *
     * <p>Requiring only the group would be an escalation: someone holding MANAGE on a group could
     * add a site they have no rights to, and immediately inherit MANAGE over it through the very
     * grant they used to add it.
     */
    @Transactional(readOnly = true)
    public void requireMembershipPermission(DomainGroup group, ProxySite site) {
        requireGroupPermission(group, PermissionLevel.MANAGE);
        requireSiteAccess(site, PermissionLevel.MANAGE);
    }

    // ---- namespaces and global scope ---------------------------------------

    @Transactional(readOnly = true)
    public PermissionLevel globalLevel() {
        AuthenticatedSubject subject = subjects.currentSubject();
        return evaluator.globalLevel(subject, grantsFor(subject), clock.instant());
    }

    @Transactional(readOnly = true)
    public PermissionLevel levelOverNamespace(DomainPattern namespace) {
        AuthenticatedSubject subject = subjects.currentSubject();
        return evaluator.namespaceLevel(subject, namespace, grantsFor(subject), clock.instant());
    }

    /**
     * Whether the caller administers <em>any</em> scope.
     *
     * <p>Used to gate the identity listing that grant authoring needs. Requiring global ADMIN
     * there would stop a group administrator from delegating within their own group, which is
     * the whole point of scoped administration.
     */
    @Transactional(readOnly = true)
    public boolean administersAnyScope() {
        AuthenticatedSubject subject = subjects.currentSubject();
        if (subject.isSuperAdmin()) {
            return true;
        }
        Instant now = clock.instant();
        PermissionLevel global = evaluator.globalLevel(subject, grantsFor(subject), now);
        if (global != null && global.satisfies(PermissionLevel.ADMIN)) {
            return true;
        }
        PermissionLevel ceiling = subject.roleCeiling();
        if (ceiling != null && !ceiling.satisfies(PermissionLevel.ADMIN)) {
            return false;
        }
        return grantsFor(subject).stream()
                .anyMatch(grant -> grant.isActiveAt(now) && grant.level().satisfies(PermissionLevel.ADMIN));
    }

    @Transactional(readOnly = true)
    public void requireAnyAdminScope() {
        if (!administersAnyScope()) {
            deny("IDENTITY", null, "Requires ADMIN on at least one scope");
        }
    }

    @Transactional(readOnly = true)
    public void requireGlobalAdmin() {
        PermissionLevel level = globalLevel();
        if (level == null || !level.satisfies(PermissionLevel.ADMIN)) {
            deny("PERMISSION_GRANT", null, "Requires ADMIN over all scopes");
        }
    }

    /**
     * Whether the caller may see deployment history for an instance.
     *
     * <p>A deployment covers every site on the host, so its log can reveal domains the caller has
     * no grant for. Requiring READ somewhere on that instance is the minimum that does not turn
     * the history endpoint into a way to enumerate the estate.
     *
     * @param instanceId null when listing across all instances, which needs global reach
     */
    @Transactional(readOnly = true)
    public void requireDeploymentVisibility(UUID instanceId) {
        AuthenticatedSubject subject = subjects.currentSubject();
        Instant now = clock.instant();

        PermissionLevel global = evaluator.globalLevel(subject, grantsFor(subject), now);
        if (global != null && global.satisfies(PermissionLevel.READ)) {
            return;
        }
        if (instanceId == null) {
            deny("DEPLOYMENT", null, "Listing deployments across all instances requires global READ");
            return;
        }

        // Any site on this instance the caller can read is enough: they already know the host
        // exists and that their own sites are deployed through it.
        AccessScope scope = accessibleScope(PermissionLevel.READ);
        if (scope.deniesEverything()) {
            deny("DEPLOYMENT", instanceId, "No readable sites on this NGINX instance");
        }
    }

    /**
     * Reading the certificate inventory.
     *
     * <p>Metadata only — subjects, issuers and expiry dates. Private key material is never
     * returned by any endpoint at any level, so the question here is only whether the caller may
     * see that these certificates exist.
     */
    @Transactional(readOnly = true)
    public void requireCertificateVisibility() {
        AuthenticatedSubject subject = subjects.currentSubject();
        PermissionLevel global = evaluator.globalLevel(subject, grantsFor(subject), clock.instant());
        if (global != null && global.satisfies(PermissionLevel.READ)) {
            return;
        }
        if (accessibleScope(PermissionLevel.READ).deniesEverything()) {
            deny("CERTIFICATE", null, "Requires read access to at least one site");
        }
    }

    /**
     * Obtaining or changing a certificate for a domain.
     *
     * <p>Measured against the namespace, exactly as creating a site is. A certificate is authority
     * over a name: whoever holds one can terminate TLS for it. Allowing someone to obtain a
     * certificate for a domain they do not otherwise control would hand them that authority
     * through the side door.
     */
    @Transactional(readOnly = true)
    public void requireCertificateAuthorityOver(String domain) {
        AuthenticatedSubject subject = subjects.currentSubject();
        PermissionLevel level = evaluator.namespaceLevel(subject,
                DomainPattern.of(domain), grantsFor(subject), clock.instant());

        if (level == null || !level.satisfies(PermissionLevel.MANAGE)) {
            deny("CERTIFICATE", null,
                    "Requires MANAGE over the namespace of " + domain + " to hold a certificate for it");
        }
    }

    // ---- granting ----------------------------------------------------------

    /**
     * Administering permissions requires ADMIN on the scope being granted over, and a grantor may
     * never confer more than they themselves hold.
     */
    @Transactional(readOnly = true)
    public void requireGrantPermission(PermissionLevel levelBeingGranted, PermissionLevel holderLevelOnScope) {
        if (holderLevelOnScope == null || !holderLevelOnScope.satisfies(PermissionLevel.ADMIN)) {
            deny("PERMISSION_GRANT", null, "Granting permissions requires ADMIN on the target scope");
        }
        if (!holderLevelOnScope.satisfies(levelBeingGranted)) {
            deny("PERMISSION_GRANT", null,
                    "You cannot grant " + levelBeingGranted + " because you hold " + holderLevelOnScope);
        }
    }

    // ---- helpers -----------------------------------------------------------

    private List<PermissionGrant> grantsFor(AuthenticatedSubject subject) {
        return grants.findActiveForSubjects(subject.subjectRefs(), clock.instant());
    }

    /**
     * A site's own groups plus every ancestor, so a grant made on a parent group reaches it.
     */
    private SiteAuthorizationContext contextFor(ProxySite site) {
        Set<UUID> direct = groups.groupIdsForSite(site.id());
        Set<UUID> withAncestors = direct.isEmpty() ? Set.of() : groups.ancestorIdsOf(direct);
        return SiteAuthorizationContext.of(site, withAncestors);
    }

    private Set<UUID> groupAncestry(UUID groupId) {
        return groups.ancestorIdsOf(Set.of(groupId));
    }

    private void deny(String resourceType, UUID resourceId, String reason) {
        audit.denied(AuditAction.ACCESS_DENIED, resourceType, resourceId, reason);
        // The message names the missing permission, not the resource's existence: a caller who
        // cannot read a site should not learn from the error whether it exists.
        throw new AccessDeniedException("You do not have permission to perform this action.");
    }

    Instant now() {
        return clock.instant();
    }
}
