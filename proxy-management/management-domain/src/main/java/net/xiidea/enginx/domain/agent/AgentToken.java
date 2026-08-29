package net.xiidea.enginx.domain.agent;

import java.time.Instant;
import java.util.UUID;

/**
 * The long-lived credential a host presents on every call, issued once at enrolment.
 *
 * <p>This is what stands between an attacker and every site's private key, because a configuration
 * bundle contains them (risk R1). In the push model that job is done by a pinned certificate,
 * which is stronger; a bearer token is the interim, and issuing agent certificates is what
 * replaces it.
 *
 * <p>{@code lastUsedAt} exists so a credential nobody is using is visible. A token that has not
 * been presented for a month belongs to a host that is gone, and revoking it costs nothing.
 */
public final class AgentToken {

    private final UUID id;
    private final UUID nginxInstanceId;
    private final String tokenHash;
    private final Instant issuedAt;
    private Instant lastUsedAt;
    private Instant revokedAt;

    private AgentToken(UUID id, UUID nginxInstanceId, String tokenHash, Instant issuedAt,
                       Instant lastUsedAt, Instant revokedAt) {
        this.id = id;
        this.nginxInstanceId = nginxInstanceId;
        this.tokenHash = tokenHash;
        this.issuedAt = issuedAt;
        this.lastUsedAt = lastUsedAt;
        this.revokedAt = revokedAt;
    }

    public static AgentToken issue(UUID id, UUID nginxInstanceId, String tokenHash, Instant now) {
        return new AgentToken(id, nginxInstanceId, tokenHash, now, null, null);
    }

    public static AgentToken rehydrate(UUID id, UUID nginxInstanceId, String tokenHash, Instant issuedAt,
                                       Instant lastUsedAt, Instant revokedAt) {
        return new AgentToken(id, nginxInstanceId, tokenHash, issuedAt, lastUsedAt, revokedAt);
    }

    public boolean isUsable() {
        return revokedAt == null;
    }

    public void used(Instant now) {
        this.lastUsedAt = now;
    }

    public void revoke(Instant now) {
        if (revokedAt == null) {
            this.revokedAt = now;
        }
    }

    public UUID id() {
        return id;
    }

    public UUID nginxInstanceId() {
        return nginxInstanceId;
    }

    public String tokenHash() {
        return tokenHash;
    }

    public Instant issuedAt() {
        return issuedAt;
    }

    public Instant lastUsedAt() {
        return lastUsedAt;
    }

    public Instant revokedAt() {
        return revokedAt;
    }

    @Override
    public String toString() {
        return "AgentToken[id=" + id + ", instance=" + nginxInstanceId + ", revoked=" + (revokedAt != null) + "]";
    }
}
