package net.xiidea.enginx.domain.agent;

import net.xiidea.enginx.domain.shared.ValidationException;

import java.time.Instant;
import java.util.UUID;

/**
 * The credential an operator hands to a new host so it can enrol itself.
 *
 * <p>Bounded in three independent ways — an expiry, a use count, and revocation — because it is
 * the one secret that travels: into a deployment manifest, a provisioning script, a chat message
 * to whoever is building the host. Any one of those is a place it can be read later, so the
 * useful question is not whether it leaks but how long a leaked one is worth anything.
 */
public final class AgentRegistrationToken {

    private final UUID id;
    private final String tokenHash;
    private final String description;
    private final Instant expiresAt;
    private final Integer maxUses;
    private int uses;
    private Instant revokedAt;
    private final String createdBy;
    private final Instant createdAt;

    private AgentRegistrationToken(UUID id, String tokenHash, String description, Instant expiresAt,
                                   Integer maxUses, int uses, Instant revokedAt, String createdBy,
                                   Instant createdAt) {
        this.id = id;
        this.tokenHash = tokenHash;
        this.description = description;
        this.expiresAt = expiresAt;
        this.maxUses = maxUses;
        this.uses = uses;
        this.revokedAt = revokedAt;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public static AgentRegistrationToken issue(UUID id, String tokenHash, String description,
                                               Instant expiresAt, Integer maxUses, String createdBy,
                                               Instant now) {
        if (maxUses != null && maxUses < 1) {
            throw new ValidationException("maxUses", "A token good for no uses cannot enrol anything");
        }
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            throw new ValidationException("expiresAt", "The expiry must be in the future");
        }
        return new AgentRegistrationToken(id, tokenHash, trimmed(description), expiresAt, maxUses, 0,
                null, createdBy, now);
    }

    public static AgentRegistrationToken rehydrate(UUID id, String tokenHash, String description,
                                                   Instant expiresAt, Integer maxUses, int uses,
                                                   Instant revokedAt, String createdBy, Instant createdAt) {
        return new AgentRegistrationToken(id, tokenHash, description, expiresAt, maxUses, uses, revokedAt,
                createdBy, createdAt);
    }

    /**
     * Whether this token can still enrol a host.
     *
     * <p>One method rather than three checks at the call site, so a later caller cannot honour the
     * expiry and forget the use count.
     */
    public boolean isUsableAt(Instant now) {
        return revokedAt == null
                && (expiresAt == null || expiresAt.isAfter(now))
                && (maxUses == null || uses < maxUses);
    }

    /** Why it cannot be used, for an audit row. Never returned to the caller presenting it. */
    public String unusableReason(Instant now) {
        if (revokedAt != null) {
            return "revoked";
        }
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            return "expired";
        }
        if (maxUses != null && uses >= maxUses) {
            return "exhausted";
        }
        return "usable";
    }

    public void recordUse(Instant now) {
        if (!isUsableAt(now)) {
            throw new IllegalStateException("Refusing to spend a token that is " + unusableReason(now));
        }
        this.uses++;
    }

    public void revoke(Instant now) {
        if (revokedAt == null) {
            this.revokedAt = now;
        }
    }

    private static String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public UUID id() {
        return id;
    }

    public String tokenHash() {
        return tokenHash;
    }

    public String description() {
        return description;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Integer maxUses() {
        return maxUses;
    }

    public int uses() {
        return uses;
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

    /** Never prints the hash: a digest of a high-entropy secret is not sensitive, but the habit is. */
    @Override
    public String toString() {
        return "AgentRegistrationToken[id=" + id + ", uses=" + uses + ", revoked=" + (revokedAt != null) + "]";
    }
}
