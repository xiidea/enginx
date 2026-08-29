package net.xiidea.enginx.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * The platform's identity at an ACME authority, with its key encrypted the same way certificate
 * keys are.
 *
 * <p>Persisted because registering a fresh account on every start would exhaust the authority's
 * account-creation rate limit within a day, and would orphan the authorisations the previous
 * account had already earned.
 */
@Entity
@Table(name = "acme_accounts")
public class AcmeAccountEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "directory_url", nullable = false, length = 512)
    private String directoryUrl;

    @Column(name = "account_url", length = 512)
    private String accountUrl;

    @Column(name = "contact_email", length = 256)
    private String contactEmail;

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

    protected AcmeAccountEntity() {
    }

    public AcmeAccountEntity(UUID id, String directoryUrl, String contactEmail, byte[] ciphertext,
                             byte[] wrappedDek, String kekId, String cipher, byte[] iv, Instant createdAt) {
        this.id = id;
        this.directoryUrl = directoryUrl;
        this.contactEmail = contactEmail;
        this.ciphertext = ciphertext;
        this.wrappedDek = wrappedDek;
        this.kekId = kekId;
        this.cipher = cipher;
        this.iv = iv;
        this.createdAt = createdAt;
    }

    public String getDirectoryUrl() {
        return directoryUrl;
    }

    public String getAccountUrl() {
        return accountUrl;
    }

    public void setAccountUrl(String accountUrl) {
        this.accountUrl = accountUrl;
    }

    /**
     * Replaces the sealed material after a re-wrap.
     *
     * <p>The only mutator for these columns, and it moves all four together. Changing the
     * ciphertext without the key id, or the key id without the wrapped data key, produces a row
     * that decrypts to nothing — and there is no second copy of an ACME account key.
     */
    public void reseal(byte[] newCiphertext, byte[] newWrappedDek, String newKekId,
                       String newCipher, byte[] newIv) {
        this.ciphertext = newCiphertext;
        this.wrappedDek = newWrappedDek;
        this.kekId = newKekId;
        this.cipher = newCipher;
        this.iv = newIv;
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
