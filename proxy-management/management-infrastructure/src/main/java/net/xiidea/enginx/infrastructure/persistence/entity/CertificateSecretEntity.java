package net.xiidea.enginx.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * An encrypted private key.
 *
 * <p>Deliberately has no {@code toString} and no accessor that yields anything readable: the only
 * thing that can turn these bytes back into a key is the encryption service, holding the KEK.
 */
@Entity
@Table(name = "certificate_secrets")
public class CertificateSecretEntity {

    @Id
    @Column(name = "certificate_id", nullable = false)
    private UUID certificateId;

    @Column(name = "ciphertext", nullable = false)
    private byte[] ciphertext;

    @Column(name = "wrapped_dek", nullable = false)
    private byte[] wrappedDek;

    @Column(name = "kek_id", nullable = false, length = 64)
    private String kekId;

    @Column(name = "cipher", nullable = false, length = 32)
    private String cipher;

    @Column(name = "iv", nullable = false)
    private byte[] iv;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CertificateSecretEntity() {
    }

    public CertificateSecretEntity(UUID certificateId, byte[] ciphertext, byte[] wrappedDek,
                                   String kekId, String cipher, byte[] iv, Instant now) {
        this.certificateId = certificateId;
        this.ciphertext = ciphertext;
        this.wrappedDek = wrappedDek;
        this.kekId = kekId;
        this.cipher = cipher;
        this.iv = iv;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void replace(byte[] ciphertext, byte[] wrappedDek, String kekId, String cipher, byte[] iv, Instant now) {
        this.ciphertext = ciphertext;
        this.wrappedDek = wrappedDek;
        this.kekId = kekId;
        this.cipher = cipher;
        this.iv = iv;
        this.updatedAt = now;
    }

    public UUID getCertificateId() {
        return certificateId;
    }

    public byte[] getCiphertext() {
        return ciphertext;
    }

    public byte[] getWrappedDek() {
        return wrappedDek;
    }

    public String getKekId() {
        return kekId;
    }

    public String getCipher() {
        return cipher;
    }

    public byte[] getIv() {
        return iv;
    }
}
