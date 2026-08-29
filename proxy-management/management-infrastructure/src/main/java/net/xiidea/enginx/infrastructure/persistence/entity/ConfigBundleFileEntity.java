package net.xiidea.enginx.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "config_bundle_files")
public class ConfigBundleFileEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "config_bundle_id", nullable = false)
    private ConfigBundleEntity bundle;

    @Column(name = "relative_path", nullable = false, length = 512)
    private String relativePath;

    @Column(name = "content", nullable = false)
    private String content;

    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    @Column(name = "sensitive", nullable = false)
    private boolean sensitive;

    protected ConfigBundleFileEntity() {
    }

    public ConfigBundleFileEntity(UUID id, String relativePath, String content, String sha256, boolean sensitive) {
        this.id = id;
        this.relativePath = relativePath;
        this.content = content;
        this.sha256 = sha256;
        this.sensitive = sensitive;
    }

    void setBundle(ConfigBundleEntity bundle) {
        this.bundle = bundle;
    }

    public String getRelativePath() {
        return relativePath;
    }

    public String getContent() {
        return content;
    }

    public String getSha256() {
        return sha256;
    }

    public boolean isSensitive() {
        return sensitive;
    }
}
