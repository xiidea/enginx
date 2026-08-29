package net.xiidea.enginx.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A mirror of a Keycloak user, kept so that permissions can be granted by picking a name rather
 * than by pasting a subject claim. Authorization never reads this table.
 */
@Entity
@Table(name = "app_users")
public class AppUserEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "keycloak_subject", nullable = false, length = 128)
    private String keycloakSubject;

    @Column(name = "username", nullable = false, length = 128)
    private String username;

    @Column(name = "email", length = 256)
    private String email;

    @Column(name = "display_name", length = 256)
    private String displayName;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AppUserEntity() {
    }

    public AppUserEntity(UUID id, String keycloakSubject) {
        this.id = id;
        this.keycloakSubject = keycloakSubject;
    }

    public UUID getId() {
        return id;
    }

    public String getKeycloakSubject() {
        return keycloakSubject;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(Instant lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
