package net.xiidea.enginx.infrastructure.persistence.entity;

import net.xiidea.enginx.domain.deployment.BundleStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "config_bundles")
public class ConfigBundleEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "nginx_instance_id", nullable = false)
    private UUID nginxInstanceId;

    @Column(name = "sequence", nullable = false)
    private long sequence;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "render_status", nullable = false, length = 16)
    private BundleStatus renderStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "site_ids_snapshot", columnDefinition = "jsonb", nullable = false)
    private String siteIdsSnapshot;

    @Column(name = "created_by", nullable = false, length = 128)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "bundle", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @BatchSize(size = 64)
    private List<ConfigBundleFileEntity> files = new ArrayList<>();

    protected ConfigBundleEntity() {
    }

    public ConfigBundleEntity(UUID id) {
        this.id = id;
    }

    public void attach(ConfigBundleFileEntity file) {
        file.setBundle(this);
        files.add(file);
    }

    public UUID getId() {
        return id;
    }

    public UUID getNginxInstanceId() {
        return nginxInstanceId;
    }

    public void setNginxInstanceId(UUID nginxInstanceId) {
        this.nginxInstanceId = nginxInstanceId;
    }

    public long getSequence() {
        return sequence;
    }

    public void setSequence(long sequence) {
        this.sequence = sequence;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public BundleStatus getRenderStatus() {
        return renderStatus;
    }

    public void setRenderStatus(BundleStatus renderStatus) {
        this.renderStatus = renderStatus;
    }

    public String getSiteIdsSnapshot() {
        return siteIdsSnapshot;
    }

    public void setSiteIdsSnapshot(String siteIdsSnapshot) {
        this.siteIdsSnapshot = siteIdsSnapshot;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public List<ConfigBundleFileEntity> getFiles() {
        return files;
    }
}
