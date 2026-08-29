package net.xiidea.enginx.domain.proxy;

import net.xiidea.enginx.domain.shared.ConflictException;
import net.xiidea.enginx.domain.shared.DomainName;
import net.xiidea.enginx.domain.shared.TimeWindow;
import net.xiidea.enginx.domain.shared.ValidationException;

import java.time.Instant;
import java.util.UUID;

/**
 * A reverse-proxy site: the aggregate root of this system.
 *
 * <p>{@code adminState} records what the operator asked for and {@code status} records what the
 * platform observes. Keeping them apart is what makes the lifecycle a total function rather than
 * a set of special cases (AD-6): a user may disable a site that has not started yet, and the
 * activation job will still do the right thing when its window opens.
 */
public final class ProxySite {

    private final UUID id;
    private ProxySiteSpec spec;
    private AdminState adminState;
    private SiteStatus status;

    private final String createdBy;
    private final Instant createdAt;
    private String updatedBy;
    private Instant updatedAt;
    private final long version;

    private ProxySite(UUID id, ProxySiteSpec spec, AdminState adminState, SiteStatus status,
                      String createdBy, Instant createdAt, String updatedBy, Instant updatedAt, long version) {
        this.id = id;
        this.spec = spec;
        this.adminState = adminState;
        this.status = status;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    /** Creates a new site. The initial status is derived, never supplied. */
    public static ProxySite create(UUID id, ProxySiteSpec spec, AdminState adminState, String actor, Instant now) {
        if (id == null) {
            throw new ValidationException("id", "Site id is required");
        }
        ProxySite site = new ProxySite(id, spec,
                adminState == null ? AdminState.ENABLED : adminState,
                SiteStatus.PENDING, actor, now, actor, now, 0L);
        site.refreshStatus(now, false);
        return site;
    }

    /** Rebuilds an aggregate from storage. Used only by the persistence adapter. */
    public static ProxySite rehydrate(UUID id, ProxySiteSpec spec, AdminState adminState, SiteStatus status,
                                      String createdBy, Instant createdAt, String updatedBy, Instant updatedAt,
                                      long version) {
        return new ProxySite(id, spec, adminState, status, createdBy, createdAt, updatedBy, updatedAt, version);
    }

    public void update(ProxySiteSpec newSpec, String actor, Instant now) {
        if (!newSpec.nginxInstanceId().equals(spec.nginxInstanceId())) {
            throw new ConflictException(
                    "A site cannot be moved between NGINX instances. Clone it onto the target instance instead.");
        }
        this.spec = newSpec;
        touch(actor, now);
        refreshStatus(now, status == SiteStatus.ERROR);
    }

    public void enable(String actor, Instant now) {
        this.adminState = AdminState.ENABLED;
        touch(actor, now);
        refreshStatus(now, false);
    }

    public void disable(String actor, Instant now) {
        this.adminState = AdminState.DISABLED;
        touch(actor, now);
        refreshStatus(now, false);
    }

    /** Extends or sets the expiry. Renewing to a moment already past is a user error, not a no-op. */
    public void renewUntil(Instant newExpiry, String actor, Instant now) {
        if (newExpiry == null) {
            throw new ValidationException("expiresAt", "A new expiry is required. Use 'remove expiry' to clear it.");
        }
        if (!newExpiry.isAfter(now)) {
            throw new ValidationException("expiresAt", "The new expiry must be in the future");
        }
        this.spec = spec.withWindow(spec.window().withExpiresAt(newExpiry));
        touch(actor, now);
        refreshStatus(now, false);
    }

    public void clearExpiry(String actor, Instant now) {
        this.spec = spec.withWindow(spec.window().withoutExpiry());
        touch(actor, now);
        refreshStatus(now, false);
    }

    /**
     * Produces an independent copy on the same NGINX instance. The clone starts disabled so that
     * a half-edited duplicate can never take traffic by accident.
     */
    public ProxySite cloneAs(UUID newId, String newName, DomainName newDomain, String actor, Instant now) {
        return create(newId, spec.withIdentity(newName, newDomain), AdminState.DISABLED, actor, now);
    }

    /** The lifecycle function of AD-6. Pure: no side effects, no clock of its own. */
    public SiteStatus deriveStatus(Instant now, boolean lastDeploymentFailed) {
        if (adminState == AdminState.DISABLED) {
            return SiteStatus.DISABLED;
        }
        TimeWindow window = spec.window();
        if (window.hasExpiredAt(now)) {
            return SiteStatus.EXPIRED;
        }
        if (window.notYetStartedAt(now)) {
            return SiteStatus.PENDING;
        }
        if (lastDeploymentFailed) {
            return SiteStatus.ERROR;
        }
        return SiteStatus.ACTIVE;
    }

    public boolean refreshStatus(Instant now, boolean lastDeploymentFailed) {
        SiteStatus next = deriveStatus(now, lastDeploymentFailed);
        if (next == status) {
            return false;
        }
        status = next;
        return true;
    }

    private void touch(String actor, Instant now) {
        this.updatedBy = actor;
        this.updatedAt = now;
    }

    public UUID id() {
        return id;
    }

    public ProxySiteSpec spec() {
        return spec;
    }

    public String name() {
        return spec.name();
    }

    public DomainName domain() {
        return spec.domain();
    }

    public UUID nginxInstanceId() {
        return spec.nginxInstanceId();
    }

    public TimeWindow window() {
        return spec.window();
    }

    public AdminState adminState() {
        return adminState;
    }

    public SiteStatus status() {
        return status;
    }

    public String createdBy() {
        return createdBy;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public String updatedBy() {
        return updatedBy;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }
}
