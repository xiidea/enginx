package net.xiidea.enginx.infrastructure.persistence.mapper;

import net.xiidea.enginx.domain.proxy.LocationRule;
import net.xiidea.enginx.domain.proxy.ProxySite;
import net.xiidea.enginx.domain.proxy.ProxySiteHeader;
import net.xiidea.enginx.domain.proxy.ProxySiteSpec;
import net.xiidea.enginx.domain.proxy.ProxyTimeouts;
import net.xiidea.enginx.domain.proxy.UpstreamTarget;
import net.xiidea.enginx.domain.shared.DomainName;
import net.xiidea.enginx.domain.shared.TimeWindow;
import net.xiidea.enginx.infrastructure.persistence.entity.ProxySiteEntity;
import net.xiidea.enginx.infrastructure.persistence.entity.ProxySiteHeaderEntity;
import net.xiidea.enginx.infrastructure.persistence.entity.ProxySiteLocationEntity;
import net.xiidea.enginx.infrastructure.persistence.entity.ProxySiteUpstreamEntity;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** Translates between the persistence model and the aggregate. */
@Component
public class ProxySiteMapper {

    public ProxySite toDomain(ProxySiteEntity entity) {
        List<UpstreamTarget> upstreams = entity.upstreams().stream()
                .sorted(Comparator.comparingInt(ProxySiteUpstreamEntity::getSortOrder))
                .map(u -> new UpstreamTarget(u.getScheme(), u.getHost(), u.getPort(), u.getWeight(),
                        u.getMaxFails(), u.getFailTimeoutSeconds(), u.isBackup()))
                .toList();

        List<ProxySiteHeader> headers = entity.headers().stream()
                .map(h -> new ProxySiteHeader(h.getDirection(), h.getHeaderName(), h.getHeaderValue()))
                .toList();

        List<LocationRule> locations = entity.locations().stream()
                .sorted(Comparator.comparingInt(ProxySiteLocationEntity::getSortOrder))
                .map(l -> new LocationRule(l.getPathPattern(), l.getMatchType(), l.getSortOrder()))
                .toList();

        ProxySiteSpec spec = new ProxySiteSpec(
                entity.getName(),
                DomainName.of(entity.getDomain()),
                entity.getNginxInstanceId(),
                new TimeWindow(entity.getActiveFrom(), entity.getExpiresAt()),
                entity.isSslEnabled(),
                entity.isForceHttps(),
                entity.isHstsEnabled(),
                entity.isWebsocketEnabled(),
                entity.getSslCertificateId(),
                entity.getLbMethod(),
                new ProxyTimeouts(entity.getConnectTimeoutSeconds(), entity.getReadTimeoutSeconds(),
                        entity.getSendTimeoutSeconds(), entity.getMaxBodySizeBytes()),
                upstreams,
                headers,
                locations);

        return ProxySite.rehydrate(entity.getId(), spec, entity.getAdminState(), entity.getStatus(),
                entity.getCreatedBy(), entity.getCreatedAt(), entity.getUpdatedBy(), entity.getUpdatedAt(),
                entity.getVersion());
    }

    public void applyToEntity(ProxySite site, ProxySiteEntity entity) {
        ProxySiteSpec spec = site.spec();

        entity.setName(spec.name());
        entity.setDomain(spec.domain().value());
        entity.setDomainReversed(spec.domain().reversed());
        entity.setNginxInstanceId(spec.nginxInstanceId());
        entity.setAdminState(site.adminState());
        entity.setStatus(site.status());
        entity.setActiveFrom(spec.window().activeFrom());
        entity.setExpiresAt(spec.window().expiresAt());
        entity.setSslEnabled(spec.sslEnabled());
        entity.setForceHttps(spec.forceHttps());
        entity.setHstsEnabled(spec.hstsEnabled());
        entity.setWebsocketEnabled(spec.websocketEnabled());
        entity.setSslCertificateId(spec.sslCertificateId());
        entity.setLbMethod(spec.loadBalancingMethod());
        entity.setConnectTimeoutSeconds(spec.timeouts().connectSeconds());
        entity.setReadTimeoutSeconds(spec.timeouts().readSeconds());
        entity.setSendTimeoutSeconds(spec.timeouts().sendSeconds());
        entity.setMaxBodySizeBytes(spec.timeouts().maxBodySizeBytes());
        entity.setCreatedBy(site.createdBy());
        entity.setCreatedAt(site.createdAt());
        entity.setUpdatedBy(site.updatedBy());
        entity.setUpdatedAt(site.updatedAt());

        mergeUpstreams(entity, spec.upstreams());
        mergeHeaders(entity, spec.headers());
        mergeLocations(entity, spec.locations());
    }

    /**
     * Merges by natural key rather than replacing wholesale.
     *
     * <p>An unchanged upstream keeps its row. Only rows that genuinely disappeared are removed,
     * which orphan removal then deletes. This avoids a delete-and-reinsert of the same natural
     * key inside one flush, which the unique constraint would reject, and keeps child ids stable
     * for the deployment records that will reference them.
     */
    private void mergeUpstreams(ProxySiteEntity entity, List<UpstreamTarget> desired) {
        Map<String, ProxySiteUpstreamEntity> existing = index(entity.upstreams(), ProxySiteUpstreamEntity::naturalKey);

        for (int order = 0; order < desired.size(); order++) {
            UpstreamTarget target = desired.get(order);
            ProxySiteUpstreamEntity row = existing.remove(
                    ProxySiteUpstreamEntity.naturalKey(target.scheme(), target.host(), target.port()));
            if (row == null) {
                row = new ProxySiteUpstreamEntity(UUID.randomUUID(), target.scheme(), target.host(), target.port(),
                        target.weight(), target.maxFails(), target.failTimeoutSeconds(), target.backup(), order);
                entity.attach(row);
                entity.upstreams().add(row);
            } else {
                row.update(target.weight(), target.maxFails(), target.failTimeoutSeconds(), target.backup(), order);
            }
        }
        entity.upstreams().removeAll(existing.values());
    }

    private void mergeHeaders(ProxySiteEntity entity, List<ProxySiteHeader> desired) {
        Map<String, ProxySiteHeaderEntity> existing = index(entity.headers(), ProxySiteHeaderEntity::naturalKey);

        for (ProxySiteHeader header : desired) {
            ProxySiteHeaderEntity row = existing.remove(
                    ProxySiteHeaderEntity.naturalKey(header.direction(), header.name()));
            if (row == null) {
                row = new ProxySiteHeaderEntity(UUID.randomUUID(), header.direction(), header.name(), header.value());
                entity.attach(row);
                entity.headers().add(row);
            } else {
                row.update(header.value());
            }
        }
        entity.headers().removeAll(existing.values());
    }

    private void mergeLocations(ProxySiteEntity entity, List<LocationRule> desired) {
        Map<String, ProxySiteLocationEntity> existing = index(entity.locations(), ProxySiteLocationEntity::naturalKey);

        for (int order = 0; order < desired.size(); order++) {
            LocationRule location = desired.get(order);
            ProxySiteLocationEntity row = existing.remove(
                    ProxySiteLocationEntity.naturalKey(location.matchType(), location.pathPattern()));
            if (row == null) {
                row = new ProxySiteLocationEntity(UUID.randomUUID(), location.pathPattern(),
                        location.matchType(), order);
                entity.attach(row);
                entity.locations().add(row);
            } else {
                row.update(order);
            }
        }
        entity.locations().removeAll(existing.values());
    }

    private static <E> Map<String, E> index(List<E> rows, Function<E, String> keyOf) {
        Map<String, E> byKey = new LinkedHashMap<>();
        for (E row : rows) {
            byKey.put(keyOf.apply(row), row);
        }
        return byKey;
    }
}