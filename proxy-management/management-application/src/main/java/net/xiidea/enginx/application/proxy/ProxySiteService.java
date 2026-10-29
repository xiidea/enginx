package net.xiidea.enginx.application.proxy;

import net.xiidea.enginx.application.permission.SitePermissionService;
import net.xiidea.enginx.application.shared.ActorProvider;
import net.xiidea.enginx.application.shared.AuditRecorder;
import net.xiidea.enginx.domain.audit.AuditAction;
import net.xiidea.enginx.domain.nginx.NginxInstanceRepository;
import net.xiidea.enginx.domain.permission.AccessScope;
import net.xiidea.enginx.domain.permission.PermissionLevel;
import net.xiidea.enginx.domain.proxy.BasicAuthUser;
import net.xiidea.enginx.domain.proxy.ProtectionMode;
import net.xiidea.enginx.domain.proxy.ProxySite;
import net.xiidea.enginx.domain.proxy.ProxySiteQuery;
import net.xiidea.enginx.domain.proxy.ProxySiteRepository;
import net.xiidea.enginx.domain.proxy.ProxySiteSpec;
import net.xiidea.enginx.domain.proxy.SiteProtection;
import net.xiidea.enginx.domain.shared.ConflictException;
import net.xiidea.enginx.domain.shared.NotFoundException;
import net.xiidea.enginx.domain.shared.PageResult;
import net.xiidea.enginx.domain.shared.ValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.UUID;

/**
 * Proxy site use cases.
 *
 * <p>Every mutating method is a single transaction that ends with an audit record, so a change
 * and its evidence commit or roll back together. Status is never taken from the caller: it is
 * re-derived from the aggregate on every write.
 *
 * <p>Authorization is enforced here rather than in the controller. A scheduler or a message
 * consumer can reach these methods without passing through the web layer, so a guard that lived
 * on the controller would be a guard those callers silently skip. Each method loads its target
 * and then asks {@link SitePermissionService} for the level that particular caller holds over
 * that particular site, which is what makes "manage app.example.com but nothing else" expressible
 * at all.
 */
@Service
public class ProxySiteService {

    static final String RESOURCE_TYPE = "PROXY_SITE";

    static final int MIN_PASSWORD_LENGTH = 8;
    // Bounded because hashing is: SHA-512-crypt's cost grows with the password, and this runs on
    // the request thread.
    static final int MAX_PASSWORD_LENGTH = 128;

    private final ProxySiteRepository sites;
    private final NginxInstanceRepository instances;
    private final SitePermissionService permissions;
    private final AuditRecorder audit;
    private final ActorProvider actorProvider;
    private final HtpasswdHasher htpasswdHasher;
    private final Clock clock;

    public ProxySiteService(ProxySiteRepository sites,
                            NginxInstanceRepository instances,
                            SitePermissionService permissions,
                            AuditRecorder audit,
                            ActorProvider actorProvider,
                            HtpasswdHasher htpasswdHasher,
                            Clock clock) {
        this.sites = sites;
        this.instances = instances;
        this.permissions = permissions;
        this.audit = audit;
        this.actorProvider = actorProvider;
        this.htpasswdHasher = htpasswdHasher;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ProxySite get(UUID id) {
        ProxySite site = load(id);
        permissions.requireSiteAccess(site, PermissionLevel.READ);
        return site;
    }

    /**
     * The listing is filtered in the query by the caller's accessible scope. Fetching a page and
     * then discarding rows would give wrong page sizes and wrong totals, and the totals alone
     * would reveal how many sites exist that this caller may not see.
     */
    @Transactional(readOnly = true)
    public PageResult<ProxySite> search(ProxySiteQuery query) {
        AccessScope scope = permissions.accessibleScope(PermissionLevel.READ);
        return sites.search(query, scope);
    }

    @Transactional
    public ProxySite create(ProxySiteCommands.Create command) {
        ProxySiteSpec spec = command.protection() == null
                ? command.spec()
                : command.spec().withProtection(resolveProtection(command.protection(), SiteProtection.none()));
        permissions.requireCreatePermission(spec.domain());
        requireInstanceExists(spec.nginxInstanceId());
        requireDomainAvailable(spec, null);

        Instant now = clock.instant();
        ProxySite site = ProxySite.create(UUID.randomUUID(), spec, command.adminState(), actor(), now);
        ProxySite saved = sites.save(site);

        audit.success(AuditAction.PROXY_SITE_CREATED, RESOURCE_TYPE, saved.id(), null, ProxySiteSnapshot.of(saved));
        return saved;
    }

    @Transactional
    public ProxySite update(ProxySiteCommands.Update command) {
        ProxySite site = load(command.id());
        permissions.requireSiteAccess(site, PermissionLevel.MANAGE);

        Map<String, Object> before = ProxySiteSnapshot.of(site);
        requireVersion(site, command.expectedVersion());
        SiteProtection current = site.spec().protection();
        ProxySiteSpec spec = command.spec().withProtection(command.protection() == null
                ? current
                : resolveProtection(command.protection(), current));
        requireDomainAvailable(spec, site.id());

        // Renaming a site onto a different domain is a move across namespaces, so it needs the
        // same authority that creating it there would have needed.
        if (!spec.domain().equals(site.domain())) {
            permissions.requireCreatePermission(spec.domain());
        }

        site.update(spec, actor(), clock.instant());
        ProxySite saved = sites.save(site);

        audit.success(AuditAction.PROXY_SITE_UPDATED, RESOURCE_TYPE, saved.id(), before, ProxySiteSnapshot.of(saved));
        return saved;
    }

    @Transactional
    public ProxySite enable(UUID id) {
        return transition(id, PermissionLevel.OPERATE, AuditAction.PROXY_SITE_ENABLED,
                (site, now) -> site.enable(actor(), now));
    }

    @Transactional
    public ProxySite disable(UUID id) {
        return transition(id, PermissionLevel.OPERATE, AuditAction.PROXY_SITE_DISABLED,
                (site, now) -> site.disable(actor(), now));
    }

    @Transactional
    public ProxySite renew(ProxySiteCommands.Renew command) {
        ProxySite site = load(command.id());
        permissions.requireSiteAccess(site, PermissionLevel.OPERATE);
        requireVersion(site, command.expectedVersion());

        return apply(site, AuditAction.PROXY_SITE_RENEWED,
                (s, now) -> s.renewUntil(command.expiresAt(), actor(), now));
    }

    @Transactional
    public ProxySite removeExpiry(UUID id) {
        return transition(id, PermissionLevel.OPERATE, AuditAction.PROXY_SITE_EXPIRY_REMOVED,
                (site, now) -> site.clearExpiry(actor(), now));
    }

    /**
     * Cloning needs MANAGE on the source <em>and</em> the authority to create the new domain.
     * Only the first would let someone copy a site they manage onto a namespace they do not.
     */
    @Transactional
    public ProxySite clone(ProxySiteCommands.Clone command) {
        ProxySite source = load(command.sourceId());
        permissions.requireSiteAccess(source, PermissionLevel.MANAGE);
        permissions.requireCreatePermission(command.domain());

        Instant now = clock.instant();
        ProxySite copy = source.cloneAs(UUID.randomUUID(), command.name(), command.domain(), actor(), now);
        requireDomainAvailable(copy.spec(), null);

        ProxySite saved = sites.save(copy);
        audit.success(AuditAction.PROXY_SITE_CLONED, RESOURCE_TYPE, saved.id(),
                Map.of("sourceId", source.id().toString(), "sourceDomain", source.domain().value()),
                ProxySiteSnapshot.of(saved));
        return saved;
    }

    @Transactional
    public void delete(UUID id, Long expectedVersion) {
        ProxySite site = load(id);
        permissions.requireSiteAccess(site, PermissionLevel.MANAGE);
        requireVersion(site, expectedVersion);

        Map<String, Object> before = ProxySiteSnapshot.of(site);
        sites.deleteById(id);
        audit.success(AuditAction.PROXY_SITE_DELETED, RESOURCE_TYPE, id, before, null);
    }

    /**
     * Turns requested protection into stored protection: new passwords hashed, and a user sent
     * without one keeping the hash it already has. A user who is new and has no password is an
     * error rather than an account nobody can sign in to.
     */
    private SiteProtection resolveProtection(ProtectionInput input, SiteProtection current) {
        if (input.mode() == null) {
            throw new ValidationException("protection.mode", "Protection mode is required");
        }
        if (input.mode() == ProtectionMode.NONE) {
            return SiteProtection.none();
        }
        Map<String, BasicAuthUser> existing = current.basicUsers().stream()
                .collect(Collectors.toMap(BasicAuthUser::username, Function.identity()));

        List<BasicAuthUser> users = new ArrayList<>();
        for (ProtectionInput.BasicUser requested : input.basicUsers()) {
            String username = BasicAuthUser.requireValidUsername(requested.username());
            String password = requested.password();
            if (password == null || password.isEmpty()) {
                BasicAuthUser kept = existing.get(username);
                if (kept == null) {
                    throw new ValidationException("protection.basicUsers.password",
                            "A password is required for the new user '" + username + "'");
                }
                users.add(kept);
                continue;
            }
            if (password.length() < MIN_PASSWORD_LENGTH || password.length() > MAX_PASSWORD_LENGTH) {
                throw new ValidationException("protection.basicUsers.password",
                        "The password for '" + username + "' must be " + MIN_PASSWORD_LENGTH + " to "
                                + MAX_PASSWORD_LENGTH + " characters");
            }
            users.add(new BasicAuthUser(username, htpasswdHasher.hash(password)));
        }
        return new SiteProtection(input.mode(), users);
    }

    /** Loads without an access check. Private, so every public path must decide for itself. */
    private ProxySite load(UUID id) {
        return sites.findById(id).orElseThrow(() -> new NotFoundException(RESOURCE_TYPE, id));
    }

    private ProxySite transition(UUID id, PermissionLevel required, AuditAction action, SiteMutation mutation) {
        ProxySite site = load(id);
        permissions.requireSiteAccess(site, required);
        return apply(site, action, mutation);
    }

    private ProxySite apply(ProxySite site, AuditAction action, SiteMutation mutation) {
        Map<String, Object> before = ProxySiteSnapshot.of(site);
        mutation.apply(site, clock.instant());
        ProxySite saved = sites.save(site);
        audit.success(action, RESOURCE_TYPE, saved.id(), before, ProxySiteSnapshot.of(saved));
        return saved;
    }

    private void requireInstanceExists(UUID instanceId) {
        if (instances.findById(instanceId).isEmpty()) {
            throw new NotFoundException("NGINX_INSTANCE", instanceId);
        }
    }

    private void requireDomainAvailable(ProxySiteSpec spec, UUID excludeId) {
        if (sites.existsByInstanceAndDomain(spec.nginxInstanceId(), spec.domain(), excludeId)) {
            throw new ConflictException(
                    "Domain " + spec.domain().value() + " is already served by another site on this NGINX instance");
        }
    }

    private void requireVersion(ProxySite site, Long expectedVersion) {
        if (expectedVersion != null && expectedVersion != site.version()) {
            throw new ConflictException("This site was modified by someone else (expected version "
                    + expectedVersion + ", current version " + site.version() + "). Reload and try again.");
        }
    }

    private String actor() {
        return actorProvider.currentActor().username();
    }

    @FunctionalInterface
    private interface SiteMutation {
        void apply(ProxySite site, Instant now);
    }
}
