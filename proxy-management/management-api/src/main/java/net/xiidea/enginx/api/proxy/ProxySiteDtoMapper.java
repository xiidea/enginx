package net.xiidea.enginx.api.proxy;

import net.xiidea.enginx.api.proxy.dto.HeaderDto;
import net.xiidea.enginx.api.proxy.dto.LocationDto;
import net.xiidea.enginx.api.proxy.dto.ProxySiteRequest;
import net.xiidea.enginx.api.proxy.dto.ProxySiteResponse;
import net.xiidea.enginx.api.proxy.dto.ProxySiteSummaryResponse;
import net.xiidea.enginx.api.proxy.dto.UpstreamDto;
import net.xiidea.enginx.domain.proxy.AdminState;
import net.xiidea.enginx.domain.proxy.HeaderDirection;
import net.xiidea.enginx.domain.proxy.LoadBalancingMethod;
import net.xiidea.enginx.domain.proxy.LocationMatchType;
import net.xiidea.enginx.domain.proxy.LocationRule;
import net.xiidea.enginx.domain.proxy.ProxySite;
import net.xiidea.enginx.domain.proxy.ProxySiteHeader;
import net.xiidea.enginx.domain.proxy.ProxySiteSpec;
import net.xiidea.enginx.domain.proxy.ProxyTimeouts;
import net.xiidea.enginx.domain.proxy.UpstreamTarget;
import net.xiidea.enginx.domain.shared.DomainName;
import net.xiidea.enginx.domain.shared.TimeWindow;
import net.xiidea.enginx.domain.shared.ValidationException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Translates between the wire format and the domain.
 *
 * <p>Every enum arrives as a string and is resolved here against the real enum constants, so an
 * unrecognised value becomes a 422 naming the valid options rather than a 500 from deep inside
 * Jackson.
 */
@Component
public class ProxySiteDtoMapper {

    private final Clock clock;

    public ProxySiteDtoMapper(Clock clock) {
        this.clock = clock;
    }

    public ProxySiteSpec toSpec(ProxySiteRequest request) {
        AtomicInteger order = new AtomicInteger();
        List<UpstreamTarget> upstreams = request.upstreams().stream()
                .map(u -> new UpstreamTarget(
                        u.scheme(),
                        u.host(),
                        u.port(),
                        u.weight() == null ? 1 : u.weight(),
                        u.maxFails() == null ? 3 : u.maxFails(),
                        u.failTimeoutSeconds() == null ? 10 : u.failTimeoutSeconds(),
                        Boolean.TRUE.equals(u.backup())))
                .toList();

        List<ProxySiteHeader> headers = request.headers() == null ? List.of() : request.headers().stream()
                .map(h -> new ProxySiteHeader(
                        parseEnum(HeaderDirection.class, h.direction(), "headers.direction"),
                        h.name(),
                        h.value()))
                .toList();

        List<LocationRule> locations = request.locations() == null ? List.of() : request.locations().stream()
                .map(l -> new LocationRule(
                        l.pathPattern(),
                        l.matchType() == null
                                ? LocationMatchType.PREFIX
                                : parseEnum(LocationMatchType.class, l.matchType(), "locations.matchType"),
                        order.getAndIncrement()))
                .toList();

        return new ProxySiteSpec(
                request.name(),
                DomainName.of(request.domain()),
                request.nginxInstanceId(),
                new TimeWindow(request.activeFrom(), request.expiresAt()),
                Boolean.TRUE.equals(request.sslEnabled()),
                Boolean.TRUE.equals(request.forceHttps()),
                Boolean.TRUE.equals(request.hstsEnabled()),
                Boolean.TRUE.equals(request.websocketEnabled()),
                request.sslCertificateId(),
                request.loadBalancingMethod() == null
                        ? LoadBalancingMethod.ROUND_ROBIN
                        : parseEnum(LoadBalancingMethod.class, request.loadBalancingMethod(), "loadBalancingMethod"),
                new ProxyTimeouts(
                        request.connectTimeoutSeconds() == null ? 60 : request.connectTimeoutSeconds(),
                        request.readTimeoutSeconds() == null ? 60 : request.readTimeoutSeconds(),
                        request.sendTimeoutSeconds() == null ? 60 : request.sendTimeoutSeconds(),
                        request.maxBodySizeBytes() == null ? 1_048_576L : request.maxBodySizeBytes()),
                upstreams,
                headers,
                locations);
    }

    public AdminState toAdminState(Boolean enabled) {
        return Boolean.FALSE.equals(enabled) ? AdminState.DISABLED : AdminState.ENABLED;
    }

    public ProxySiteResponse toResponse(ProxySite site) {
        ProxySiteSpec spec = site.spec();
        return new ProxySiteResponse(
                site.id(),
                spec.name(),
                spec.domain().value(),
                spec.nginxInstanceId(),
                site.status().name(),
                site.adminState() == AdminState.ENABLED,
                spec.window().activeFrom(),
                spec.window().expiresAt(),
                secondsUntilExpiry(spec.window().expiresAt()),
                spec.sslEnabled(),
                spec.forceHttps(),
                spec.hstsEnabled(),
                spec.websocketEnabled(),
                spec.sslCertificateId(),
                spec.loadBalancingMethod().name(),
                spec.timeouts().connectSeconds(),
                spec.timeouts().readSeconds(),
                spec.timeouts().sendSeconds(),
                spec.timeouts().maxBodySizeBytes(),
                spec.upstreams().stream()
                        .map(u -> new UpstreamDto(u.scheme(), u.host(), u.port(), u.weight(),
                                u.maxFails(), u.failTimeoutSeconds(), u.backup()))
                        .toList(),
                spec.headers().stream()
                        .map(h -> new HeaderDto(h.direction().name(), h.name(), h.value()))
                        .toList(),
                spec.locations().stream()
                        .map(l -> new LocationDto(l.pathPattern(), l.matchType().name()))
                        .toList(),
                site.createdBy(),
                site.createdAt(),
                site.updatedBy(),
                site.updatedAt(),
                site.version());
    }

    public ProxySiteSummaryResponse toSummary(ProxySite site) {
        ProxySiteSpec spec = site.spec();
        UpstreamTarget primary = spec.upstreams().stream()
                .filter(u -> !u.backup())
                .findFirst()
                .orElse(spec.upstreams().getFirst());

        return new ProxySiteSummaryResponse(
                site.id(),
                spec.name(),
                spec.domain().value(),
                spec.nginxInstanceId(),
                site.status().name(),
                site.adminState() == AdminState.ENABLED,
                spec.sslEnabled(),
                spec.window().activeFrom(),
                spec.window().expiresAt(),
                secondsUntilExpiry(spec.window().expiresAt()),
                spec.upstreams().size(),
                primary.scheme() + "://" + primary.authority(),
                site.updatedAt(),
                site.version());
    }

    /** Negative once the site has expired, so the UI can render "expired 3 days ago" from one field. */
    private Long secondsUntilExpiry(Instant expiresAt) {
        return expiresAt == null ? null : expiresAt.getEpochSecond() - clock.instant().getEpochSecond();
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String field) {
        if (value == null) {
            throw new ValidationException(field, field + " is required");
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException(field,
                    "'" + value + "' is not valid. Expected one of: "
                            + String.join(", ", java.util.Arrays.stream(type.getEnumConstants()).map(Enum::name).toList()));
        }
    }
}
