package net.xiidea.enginx.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "domain_group_members")
public class DomainGroupMemberEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "domain_group_id", nullable = false)
    private UUID domainGroupId;

    @Column(name = "proxy_site_id", nullable = false)
    private UUID proxySiteId;

    @Column(name = "added_by", nullable = false, length = 128)
    private String addedBy;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;

    protected DomainGroupMemberEntity() {
    }

    public DomainGroupMemberEntity(UUID id, UUID domainGroupId, UUID proxySiteId, String addedBy, Instant addedAt) {
        this.id = id;
        this.domainGroupId = domainGroupId;
        this.proxySiteId = proxySiteId;
        this.addedBy = addedBy;
        this.addedAt = addedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDomainGroupId() {
        return domainGroupId;
    }

    public UUID getProxySiteId() {
        return proxySiteId;
    }
}
