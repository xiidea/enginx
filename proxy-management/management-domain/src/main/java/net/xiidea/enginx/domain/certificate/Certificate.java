package net.xiidea.enginx.domain.certificate;

import net.xiidea.enginx.domain.shared.ConflictException;
import net.xiidea.enginx.domain.shared.ValidationException;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A TLS certificate the platform manages.
 *
 * <p>Only public material lives on this aggregate. The private key is held separately, encrypted,
 * and is reachable through one narrow path used at deployment time — so no accessor here can be
 * serialised into an API response by accident.
 *
 * <p>{@code status} is derived from the certificate's own dates, never set by a caller, for the
 * same reason a proxy site's status is derived: the truth is the material, not what someone
 * asserted about it.
 */
public final class Certificate {

    private final UUID id;
    private final CertificateProviderKind provider;
    private String name;
    private Set<String> domains;

    private String fullChainPem;
    private CertificateMetadata metadata;
    private CertificateStatus status;

    private boolean autoRenew;
    private int renewBeforeDays;
    private String lastError;
    private Instant revokedAt;

    private final String createdBy;
    private final Instant createdAt;
    private Instant updatedAt;
    private final long version;

    private Certificate(UUID id, CertificateProviderKind provider, String name, Set<String> domains,
                        String fullChainPem, CertificateMetadata metadata, CertificateStatus status,
                        boolean autoRenew, int renewBeforeDays, String lastError, Instant revokedAt,
                        String createdBy, Instant createdAt, Instant updatedAt, long version) {
        this.id = id;
        this.provider = provider;
        this.name = name;
        this.domains = Set.copyOf(domains);
        this.fullChainPem = fullChainPem;
        this.metadata = metadata;
        this.status = status;
        this.autoRenew = autoRenew;
        this.renewBeforeDays = renewBeforeDays;
        this.lastError = lastError;
        this.revokedAt = revokedAt;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    /**
     * Registers a certificate that has been requested but not yet issued.
     *
     * <p>It starts in ERROR rather than in a "pending" state of its own: from the platform's point
     * of view a certificate with no material cannot secure anything, and treating that as a
     * distinct healthy-looking state would let a site be configured against a certificate that
     * does not exist.
     */
    public static Certificate requested(UUID id, CertificateRequest request, CertificateProviderKind provider,
                                        boolean autoRenew, int renewBeforeDays, String actor, Instant now) {
        return new Certificate(id, provider, request.name(), request.domains(), null, null,
                CertificateStatus.ERROR, autoRenew, validRenewWindow(renewBeforeDays), null, null,
                actor, now, now, 0L);
    }

    public static Certificate rehydrate(UUID id, CertificateProviderKind provider, String name, Set<String> domains,
                                        String fullChainPem, CertificateMetadata metadata, CertificateStatus status,
                                        boolean autoRenew, int renewBeforeDays, String lastError, Instant revokedAt,
                                        String createdBy, Instant createdAt, Instant updatedAt, long version) {
        return new Certificate(id, provider, name, domains, fullChainPem, metadata, status, autoRenew,
                renewBeforeDays, lastError, revokedAt, createdBy, createdAt, updatedAt, version);
    }

    /**
     * Installs newly issued material.
     *
     * <p>The recorded domains are taken from the certificate's subject alternative names, not from
     * what was asked for. If an authority issued something narrower than requested, the platform
     * must know what it actually holds, or it will happily attach the certificate to a site it
     * does not cover.
     */
    public void install(IssuedCertificate issued, Instant now) {
        if (revokedAt != null) {
            throw new ConflictException("This certificate has been revoked; request a new one instead");
        }
        this.fullChainPem = issued.fullChainPem();
        this.metadata = issued.metadata();
        this.domains = new LinkedHashSet<>(issued.metadata().domains());
        this.lastError = null;
        this.updatedAt = now;
        refreshStatus(now);
    }

    public void failed(String error, Instant now) {
        // Material already installed is kept. A failed renewal must not take a working
        // certificate away from the sites currently serving it.
        this.lastError = error;
        this.updatedAt = now;
        if (metadata == null) {
            this.status = CertificateStatus.ERROR;
        } else {
            refreshStatus(now);
        }
    }

    public void revoked(Instant now) {
        this.revokedAt = now;
        this.status = CertificateStatus.REVOKED;
        this.updatedAt = now;
    }

    public void configureRenewal(boolean enabled, int days, Instant now) {
        this.autoRenew = enabled;
        this.renewBeforeDays = validRenewWindow(days);
        this.updatedAt = now;
        refreshStatus(now);
    }

    /** @return true when the status changed */
    public boolean refreshStatus(Instant now) {
        CertificateStatus next = deriveStatus(now);
        if (next == status) {
            return false;
        }
        status = next;
        return true;
    }

    public CertificateStatus deriveStatus(Instant now) {
        if (revokedAt != null) {
            return CertificateStatus.REVOKED;
        }
        if (metadata == null) {
            return CertificateStatus.ERROR;
        }
        if (!now.isBefore(metadata.notAfter())) {
            return CertificateStatus.EXPIRED;
        }
        if (daysRemaining(now) <= renewBeforeDays) {
            return CertificateStatus.EXPIRING_SOON;
        }
        return CertificateStatus.VALID;
    }

    /**
     * Whether an automatic renewal should be attempted now.
     *
     * <p>An expired certificate is still worth renewing: the site is already broken, and fresh
     * material is the fix. A revoked one is not, because renewing it would quietly resurrect
     * something an operator deliberately withdrew.
     *
     * <p>A certificate that has never been issued is excluded, which is less obvious. Such a
     * certificate is either mid-issuance — in which case the sweep would race the request that
     * created it and one of the two would lose an optimistic lock — or it failed, usually because
     * the domain does not resolve here yet. Retrying that on a timer would spend the authority's
     * rate limit on a request that cannot succeed until a human changes something. First issuance
     * is therefore explicit; only renewal is automatic.
     */
    public boolean needsRenewal(Instant now) {
        if (!autoRenew || provider != CertificateProviderKind.ACME || revokedAt != null) {
            return false;
        }
        if (metadata == null) {
            return false;
        }
        return daysRemaining(now) <= renewBeforeDays;
    }

    public long daysRemaining(Instant now) {
        if (metadata == null) {
            return 0;
        }
        return Duration.between(now, metadata.notAfter()).toDays();
    }

    public boolean covers(String domain) {
        return metadata != null && metadata.covers(domain);
    }

    private static int validRenewWindow(int days) {
        // Let's Encrypt issues for 90 days and recommends renewing at 30. Below 1 the window is
        // meaningless; at 90 or above every certificate would be permanently due for renewal.
        if (days < 1 || days > 89) {
            throw new ValidationException("renewBeforeDays", "The renewal window must be between 1 and 89 days");
        }
        return days;
    }

    public UUID id() {
        return id;
    }

    public CertificateProviderKind provider() {
        return provider;
    }

    public String name() {
        return name;
    }

    public Set<String> domains() {
        return Set.copyOf(domains);
    }

    public String fullChainPem() {
        return fullChainPem;
    }

    public CertificateMetadata metadata() {
        return metadata;
    }

    public CertificateStatus status() {
        return status;
    }

    public boolean autoRenew() {
        return autoRenew;
    }

    public int renewBeforeDays() {
        return renewBeforeDays;
    }

    public String lastError() {
        return lastError;
    }

    public Instant revokedAt() {
        return revokedAt;
    }

    public String createdBy() {
        return createdBy;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }
}
