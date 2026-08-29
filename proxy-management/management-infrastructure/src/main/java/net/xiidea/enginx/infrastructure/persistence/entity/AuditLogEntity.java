package net.xiidea.enginx.infrastructure.persistence.entity;

import net.xiidea.enginx.domain.audit.AuditAction;
import net.xiidea.enginx.domain.audit.AuditResult;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only. There is no setter for anything after construction and no update path in any
 * repository; the database enforces the same rule with a trigger, so neither a bug nor a
 * direct SQL session can rewrite history.
 */
@Entity
@Table(name = "audit_logs")
public class AuditLogEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "actor_subject", length = 128, updatable = false)
    private String actorSubject;

    @Column(name = "actor_username", length = 128, updatable = false)
    private String actorUsername;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 64, updatable = false)
    private AuditAction action;

    @Column(name = "resource_type", nullable = false, length = 64, updatable = false)
    private String resourceType;

    @Column(name = "resource_id", updatable = false)
    private UUID resourceId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_state", columnDefinition = "jsonb", updatable = false)
    private String beforeState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_state", columnDefinition = "jsonb", updatable = false)
    private String afterState;

    @Column(name = "ip_address", length = 64, updatable = false)
    private String ipAddress;

    @Column(name = "user_agent", length = 512, updatable = false)
    private String userAgent;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, length = 16, updatable = false)
    private AuditResult result;

    @Column(name = "error_message", length = 2048, updatable = false)
    private String errorMessage;

    @Column(name = "trace_id", length = 64, updatable = false)
    private String traceId;

    protected AuditLogEntity() {
    }

    public AuditLogEntity(UUID id, Instant occurredAt, String actorSubject, String actorUsername,
                          AuditAction action, String resourceType, UUID resourceId,
                          String beforeState, String afterState, String ipAddress, String userAgent,
                          AuditResult result, String errorMessage, String traceId) {
        this.id = id;
        this.occurredAt = occurredAt;
        this.actorSubject = actorSubject;
        this.actorUsername = actorUsername;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.beforeState = beforeState;
        this.afterState = afterState;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.result = result;
        this.errorMessage = errorMessage;
        this.traceId = traceId;
    }

    public UUID getId() {
        return id;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public AuditAction getAction() {
        return action;
    }

    public AuditResult getResult() {
        return result;
    }

    // Read accessors. There are deliberately no setters: an audit row is written once, by the
    // constructor, and the database refuses UPDATE and DELETE outright.

    public String getActorSubject() {
        return actorSubject;
    }

    public String getActorUsername() {
        return actorUsername;
    }

    public String getResourceType() {
        return resourceType;
    }

    public java.util.UUID getResourceId() {
        return resourceId;
    }

    public String getBeforeState() {
        return beforeState;
    }

    public String getAfterState() {
        return afterState;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getTraceId() {
        return traceId;
    }
}
