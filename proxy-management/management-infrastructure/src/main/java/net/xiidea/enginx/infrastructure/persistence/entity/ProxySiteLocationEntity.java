package net.xiidea.enginx.infrastructure.persistence.entity;

import net.xiidea.enginx.domain.proxy.LocationMatchType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "proxy_site_locations")
public class ProxySiteLocationEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "proxy_site_id", nullable = false)
    private ProxySiteEntity site;

    @Column(name = "path_pattern", nullable = false, length = 256)
    private String pathPattern;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_type", nullable = false, length = 16)
    private LocationMatchType matchType;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected ProxySiteLocationEntity() {
    }

    public ProxySiteLocationEntity(UUID id, String pathPattern, LocationMatchType matchType, int sortOrder) {
        this.id = id;
        this.pathPattern = pathPattern;
        this.matchType = matchType;
        this.sortOrder = sortOrder;
    }

    void setSite(ProxySiteEntity site) {
        this.site = site;
    }

    /** Matches the {@code uq_location} constraint. */
    public String naturalKey() {
        return naturalKey(matchType, pathPattern);
    }

    public static String naturalKey(LocationMatchType matchType, String pathPattern) {
        return matchType + "|" + pathPattern;
    }

    public void update(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public String getPathPattern() {
        return pathPattern;
    }

    public LocationMatchType getMatchType() {
        return matchType;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}
