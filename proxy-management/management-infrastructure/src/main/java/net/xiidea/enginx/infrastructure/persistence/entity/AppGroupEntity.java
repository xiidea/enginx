package net.xiidea.enginx.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** A mirror of a Keycloak group, for grant authoring and display. Authorization never reads it. */
@Entity
@Table(name = "app_groups")
public class AppGroupEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "keycloak_group_path", nullable = false, length = 512)
    private String keycloakGroupPath;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    protected AppGroupEntity() {
    }

    public AppGroupEntity(UUID id, String keycloakGroupPath, String name, Instant lastSeenAt) {
        this.id = id;
        this.keycloakGroupPath = keycloakGroupPath;
        this.name = name;
        this.lastSeenAt = lastSeenAt;
    }

    public UUID getId() {
        return id;
    }

    public String getKeycloakGroupPath() {
        return keycloakGroupPath;
    }

    public String getName() {
        return name;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(Instant lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }
}
