package net.xiidea.enginx.infrastructure.persistence.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "local_users")
public class LocalUserEntity {

    @Id
    private UUID id;

    @Column(name = "username", nullable = false, updatable = false, length = 128)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "email", length = 256)
    private String email;

    @Column(name = "display_name", length = 256)
    private String displayName;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    // Eager, because the login path needs roles and groups in the same breath as the account and
    // there is exactly one user per request. Lazy here would be an N+1 on the hottest path.
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "local_user_roles", joinColumns = @JoinColumn(name = "local_user_id"))
    @Column(name = "role", nullable = false, length = 32)
    private Set<String> roles = new LinkedHashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "local_user_groups", joinColumns = @JoinColumn(name = "local_user_id"))
    @Column(name = "group_path", nullable = false, length = 512)
    private Set<String> groupPaths = new LinkedHashSet<>();

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "created_by", nullable = false, updatable = false, length = 128)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected LocalUserEntity() {
    }

    public LocalUserEntity(UUID id) {
        this.id = id;
    }

    public void apply(String newUsername, String newPasswordHash, String newEmail, String newDisplayName,
                      boolean newEnabled, boolean newMustChange, Set<String> newRoles,
                      Set<String> newGroupPaths, Instant newLastLoginAt, String newCreatedBy,
                      Instant newCreatedAt, Instant newUpdatedAt) {
        this.username = newUsername;
        this.passwordHash = newPasswordHash;
        this.email = newEmail;
        this.displayName = newDisplayName;
        this.enabled = newEnabled;
        this.mustChangePassword = newMustChange;
        // Merged in place rather than replaced: Hibernate orders inserts before deletes, so
        // clearing and re-adding an element collection can violate the primary key mid-flush.
        this.roles.retainAll(newRoles);
        this.roles.addAll(newRoles);
        this.groupPaths.retainAll(newGroupPaths);
        this.groupPaths.addAll(newGroupPaths);
        this.lastLoginAt = newLastLoginAt;
        if (this.createdBy == null) {
            this.createdBy = newCreatedBy;
            this.createdAt = newCreatedAt;
        }
        this.updatedAt = newUpdatedAt;
    }

    public UUID getId() { return id; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public String getEmail() { return email; }
    public String getDisplayName() { return displayName; }
    public boolean isEnabled() { return enabled; }
    public boolean isMustChangePassword() { return mustChangePassword; }
    public Set<String> getRoles() { return roles; }
    public Set<String> getGroupPaths() { return groupPaths; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
