package net.xiidea.enginx.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per notification that has been claimed for sending.
 *
 * <p>No setters for the identifying columns: once a notification is claimed, what it was about
 * cannot change. Only the delivery outcome is written afterwards.
 */
@Entity
@Table(name = "notification_ledger")
public class NotificationLedgerEntity {

    @Id
    private UUID id;

    @Column(name = "kind", nullable = false, updatable = false, length = 48)
    private String kind;

    @Column(name = "resource_type", nullable = false, updatable = false, length = 48)
    private String resourceType;

    @Column(name = "resource_id", nullable = false, updatable = false)
    private UUID resourceId;

    @Column(name = "threshold", nullable = false, updatable = false, length = 16)
    private String threshold;

    @Column(name = "fingerprint", nullable = false, updatable = false, length = 128)
    private String fingerprint;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "recipients", length = 1024)
    private String recipients;

    @Column(name = "detail")
    private String detail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected NotificationLedgerEntity() {
    }

    public NotificationLedgerEntity(UUID id, String kind, String resourceType, UUID resourceId,
                                    String threshold, String fingerprint, Instant createdAt) {
        this.id = id;
        this.kind = kind;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.threshold = threshold;
        this.fingerprint = fingerprint;
        this.status = "PENDING";
        this.createdAt = createdAt;
    }

    public void recordOutcome(String newStatus, String newRecipients, String newDetail) {
        this.status = newStatus;
        this.recipients = newRecipients;
        this.detail = newDetail;
    }

    public UUID getId() {
        return id;
    }

    public String getStatus() {
        return status;
    }
}
