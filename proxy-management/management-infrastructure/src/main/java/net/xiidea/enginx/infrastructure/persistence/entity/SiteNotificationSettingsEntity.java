package net.xiidea.enginx.infrastructure.persistence.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "proxy_site_notifications")
public class SiteNotificationSettingsEntity {

    @Id
    @Column(name = "proxy_site_id", nullable = false)
    private UUID proxySiteId;

    @Column(name = "expiry_enabled", nullable = false)
    private boolean expiryEnabled = true;

    /**
     * Eager, and deliberately: the only reason to load these settings is to decide who to tell,
     * so a lazy list would be a second query every single time with no case that avoids it.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "proxy_site_notification_subscribers",
            joinColumns = @JoinColumn(name = "proxy_site_id"))
    @Column(name = "email", nullable = false, length = 256)
    private Set<String> subscribers = new LinkedHashSet<>();

    @Column(name = "updated_by", length = 128)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SiteNotificationSettingsEntity() {
    }

    public SiteNotificationSettingsEntity(UUID proxySiteId) {
        this.proxySiteId = proxySiteId;
    }

    public UUID getProxySiteId() {
        return proxySiteId;
    }

    public boolean isExpiryEnabled() {
        return expiryEnabled;
    }

    public void setExpiryEnabled(boolean expiryEnabled) {
        this.expiryEnabled = expiryEnabled;
    }

    public Set<String> getSubscribers() {
        return subscribers;
    }

    public void setSubscribers(Set<String> subscribers) {
        this.subscribers = new LinkedHashSet<>(subscribers);
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
