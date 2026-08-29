package net.xiidea.enginx.infrastructure.persistence.entity;

import net.xiidea.enginx.domain.permission.PermissionLevel;
import net.xiidea.enginx.domain.permission.ScopeType;
import net.xiidea.enginx.domain.permission.SubjectType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "permission_grants")
public class PermissionGrantEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 16)
    private SubjectType subjectType;

    /** A Keycloak identifier: the {@code sub} claim, or a group path. Never a mirrored row id. */
    @Column(name = "subject_ref", nullable = false, length = 512)
    private String subjectRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 24)
    private ScopeType scopeType;

    @Column(name = "scope_group_id")
    private UUID scopeGroupId;

    @Column(name = "scope_site_id")
    private UUID scopeSiteId;

    @Column(name = "domain_pattern", length = 255)
    private String domainPattern;

    @Column(name = "pattern_reversed", length = 255)
    private String patternReversed;

    @Enumerated(EnumType.STRING)
    @Column(name = "permission_level", nullable = false, length = 16)
    private PermissionLevel permissionLevel;

    @Column(name = "granted_by", nullable = false, length = 128)
    private String grantedBy;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    protected PermissionGrantEntity() {
    }

    public PermissionGrantEntity(UUID id, SubjectType subjectType, String subjectRef, ScopeType scopeType,
                                 UUID scopeGroupId, UUID scopeSiteId, String domainPattern, String patternReversed,
                                 PermissionLevel permissionLevel, String grantedBy, Instant grantedAt,
                                 Instant expiresAt) {
        this.id = id;
        this.subjectType = subjectType;
        this.subjectRef = subjectRef;
        this.scopeType = scopeType;
        this.scopeGroupId = scopeGroupId;
        this.scopeSiteId = scopeSiteId;
        this.domainPattern = domainPattern;
        this.patternReversed = patternReversed;
        this.permissionLevel = permissionLevel;
        this.grantedBy = grantedBy;
        this.grantedAt = grantedAt;
        this.expiresAt = expiresAt;
    }

    public UUID getId() {
        return id;
    }

    public SubjectType getSubjectType() {
        return subjectType;
    }

    public String getSubjectRef() {
        return subjectRef;
    }

    public ScopeType getScopeType() {
        return scopeType;
    }

    public UUID getScopeGroupId() {
        return scopeGroupId;
    }

    public UUID getScopeSiteId() {
        return scopeSiteId;
    }

    public String getDomainPattern() {
        return domainPattern;
    }

    public String getPatternReversed() {
        return patternReversed;
    }

    public PermissionLevel getPermissionLevel() {
        return permissionLevel;
    }

    public String getGrantedBy() {
        return grantedBy;
    }

    public Instant getGrantedAt() {
        return grantedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
