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

    /** Null for a sensitive file, which is held sealed below instead. */
    @Column(name = "content")
    private String content;

    @Column(name = "content_ciphertext")
    private byte[] contentCiphertext;

    @Column(name = "content_wrapped_dek")
    private byte[] contentWrappedDek;

    @Column(name = "content_kek_id", length = 64)
    private String contentKekId;

    @Column(name = "content_cipher", length = 32)
    private String contentCipher;

    @Column(name = "content_iv")
    private byte[] contentIv;

    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    @Column(name = "sensitive", nullable = false)
    private boolean sensitive;

    @Column(name = "file_mode", nullable = false, length = 4)
    private String fileMode;

    protected ConfigBundleFileEntity() {
    }

    public ConfigBundleFileEntity(UUID id, String relativePath, String content, String sha256,
                                  boolean sensitive, String fileMode) {
        this.id = id;
        this.relativePath = relativePath;
        this.content = content;
        this.sha256 = sha256;
        this.sensitive = sensitive;
        this.fileMode = fileMode;
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

    /** Holds the content sealed rather than in the clear. */
    public void seal(net.xiidea.enginx.domain.certificate.EncryptedSecret sealed) {
        this.content = null;
        this.contentCiphertext = sealed.ciphertext();
        this.contentWrappedDek = sealed.wrappedDataKey();
        this.contentKekId = sealed.kekId();
        this.contentCipher = sealed.cipher();
        this.contentIv = sealed.iv();
    }

    /** The sealed content, or null when it is held in the clear. */
    public net.xiidea.enginx.domain.certificate.EncryptedSecret sealedContent() {
        return contentCiphertext == null ? null : new net.xiidea.enginx.domain.certificate.EncryptedSecret(
                contentCiphertext, contentWrappedDek, contentKekId, contentCipher, contentIv, null);
    }

    public UUID getId() {
        return id;
    }

    public boolean isSensitive() {
        return sensitive;
    }

    public String getFileMode() {
        return fileMode;
    }
}
