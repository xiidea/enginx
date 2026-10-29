package net.xiidea.enginx.infrastructure.persistence.mapper;

import net.xiidea.enginx.domain.proxy.AllowedEmail;
import net.xiidea.enginx.domain.proxy.BasicAuthUser;
import net.xiidea.enginx.domain.proxy.EmailOtpPolicy;
import net.xiidea.enginx.domain.proxy.LocationRule;
import net.xiidea.enginx.domain.proxy.OidcAccessPolicy;
import net.xiidea.enginx.domain.proxy.OidcRole;
import net.xiidea.enginx.domain.proxy.ProtectionMode;
import net.xiidea.enginx.domain.proxy.ProxySite;
import net.xiidea.enginx.domain.proxy.ProxySiteHeader;
import net.xiidea.enginx.domain.proxy.ProxySiteSpec;
import net.xiidea.enginx.domain.proxy.ProxyTimeouts;
import net.xiidea.enginx.domain.proxy.SiteProtection;
import net.xiidea.enginx.domain.proxy.UpstreamTarget;
import net.xiidea.enginx.domain.shared.DomainName;
import net.xiidea.enginx.domain.shared.TimeWindow;
import net.xiidea.enginx.infrastructure.persistence.entity.ProxySiteAllowedEmailEntity;
import net.xiidea.enginx.infrastructure.persistence.entity.ProxySiteBasicAuthUserEntity;
import net.xiidea.enginx.infrastructure.persistence.entity.ProxySiteEntity;
import net.xiidea.enginx.infrastructure.persistence.entity.ProxySiteHeaderEntity;
import net.xiidea.enginx.infrastructure.persistence.entity.ProxySiteLocationEntity;
import net.xiidea.enginx.infrastructure.persistence.entity.ProxySiteOidcRoleEntity;
import net.xiidea.enginx.infrastructure.persistence.entity.ProxySiteOidcUserEntity;
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

        java.time.Duration sessionTtl = entity.getGateSessionTtlSeconds() == null ? null
                : java.time.Duration.ofSeconds(entity.getGateSessionTtlSeconds());
        EmailOtpPolicy emailOtp = entity.getProtectionMode() != ProtectionMode.EMAIL_OTP ? null : new EmailOtpPolicy(
                entity.allowedEmails().stream().map(e -> new AllowedEmail(e.getEmail())).toList(), sessionTtl);
        OidcAccessPolicy oidc = entity.getProtectionMode() != ProtectionMode.OIDC ? null : new OidcAccessPolicy(
                Boolean.TRUE.equals(entity.getOidcAnyUser()),
                entity.oidcUsers().stream().map(ProxySiteOidcUserEntity::getUsername).toList(),
                entity.oidcRoles().stream().map(r -> new OidcRole(r.getClientId(), r.getRoleName())).toList(),
                sessionTtl);
        SiteProtection protection = new SiteProtection(entity.getProtectionMode(),
                entity.basicAuthUsers().stream()
                        .map(u -> new BasicAuthUser(u.getUsername(), u.getPasswordHash()))
                        .toList(),
                emailOtp,
                oidc);

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
                locations,
                protection);

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
        entity.setProtectionMode(spec.protection().mode());
        EmailOtpPolicy emailOtp = spec.protection().emailOtp();
        OidcAccessPolicy oidc = spec.protection().oidc();
        java.time.Duration sessionTtl = spec.protection().sessionTtl();
        entity.setGateSessionTtlSeconds(sessionTtl == null ? null : (int) sessionTtl.toSeconds());
        entity.setOidcAnyUser(oidc == null ? null : oidc.anyUser());
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
        mergeBasicAuthUsers(entity, spec.protection().basicUsers());
        mergeAllowedEmails(entity, emailOtp == null ? List.of() : emailOtp.allowedEmails());
        mergeOidcUsers(entity, oidc == null ? List.of() : oidc.users());
        mergeOidcRoles(entity, oidc == null ? List.of() : oidc.roles());
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

    private void mergeBasicAuthUsers(ProxySiteEntity entity, List<BasicAuthUser> desired) {
        Map<String, ProxySiteBasicAuthUserEntity> existing =
                index(entity.basicAuthUsers(), ProxySiteBasicAuthUserEntity::naturalKey);

        for (BasicAuthUser user : desired) {
            ProxySiteBasicAuthUserEntity row = existing.remove(user.username());
            if (row == null) {
                row = new ProxySiteBasicAuthUserEntity(UUID.randomUUID(), user.username(), user.passwordHash());
                entity.attach(row);
                entity.basicAuthUsers().add(row);
            } else {
                row.update(user.passwordHash());
            }
        }
        entity.basicAuthUsers().removeAll(existing.values());
    }

    private void mergeAllowedEmails(ProxySiteEntity entity, List<AllowedEmail> desired) {
        Map<String, ProxySiteAllowedEmailEntity> existing =
                index(entity.allowedEmails(), ProxySiteAllowedEmailEntity::naturalKey);

        for (AllowedEmail email : desired) {
            if (existing.remove(email.value()) == null) {
                ProxySiteAllowedEmailEntity row = new ProxySiteAllowedEmailEntity(UUID.randomUUID(), email.value());
                entity.attach(row);
                entity.allowedEmails().add(row);
            }
        }
        entity.allowedEmails().removeAll(existing.values());
    }

    private void mergeOidcUsers(ProxySiteEntity entity, List<String> desired) {
        Map<String, ProxySiteOidcUserEntity> existing = index(entity.oidcUsers(), ProxySiteOidcUserEntity::naturalKey);
        for (String user : desired) {
            if (existing.remove(user) == null) {
                ProxySiteOidcUserEntity row = new ProxySiteOidcUserEntity(UUID.randomUUID(), user);
                entity.attach(row);
                entity.oidcUsers().add(row);
            }
        }
        entity.oidcUsers().removeAll(existing.values());
    }

    private void mergeOidcRoles(ProxySiteEntity entity, List<OidcRole> desired) {
        Map<String, ProxySiteOidcRoleEntity> existing = index(entity.oidcRoles(), ProxySiteOidcRoleEntity::naturalKey);
        for (OidcRole role : desired) {
            if (existing.remove(ProxySiteOidcRoleEntity.naturalKey(role.client(), role.role())) == null) {
                ProxySiteOidcRoleEntity row = new ProxySiteOidcRoleEntity(UUID.randomUUID(), role.client(), role.role());
                entity.attach(row);
                entity.oidcRoles().add(row);
            }
        }
        entity.oidcRoles().removeAll(existing.values());
    }

    private static <E> Map<String, E> index(List<E> rows, Function<E, String> keyOf) {
        Map<String, E> byKey = new LinkedHashMap<>();
        for (E row : rows) {
            byKey.put(keyOf.apply(row), row);
        }
        return byKey;
    }
}