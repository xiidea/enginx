package net.xiidea.enginx.api.proxy;

import net.xiidea.enginx.api.proxy.dto.HeaderDto;
import net.xiidea.enginx.api.proxy.dto.LocationDto;
import net.xiidea.enginx.api.proxy.dto.ProtectionRequest;
import net.xiidea.enginx.api.proxy.dto.ProtectionResponse;
import net.xiidea.enginx.api.proxy.dto.ProxySiteRequest;
import net.xiidea.enginx.api.proxy.dto.ProxySiteResponse;
import net.xiidea.enginx.api.proxy.dto.ProxySiteSummaryResponse;
import net.xiidea.enginx.api.proxy.dto.UpstreamDto;
import net.xiidea.enginx.application.proxy.ProtectionInput;
import net.xiidea.enginx.domain.proxy.AdminState;
import net.xiidea.enginx.domain.proxy.HeaderDirection;
import net.xiidea.enginx.domain.proxy.LoadBalancingMethod;
import net.xiidea.enginx.domain.proxy.LocationMatchType;
import net.xiidea.enginx.domain.proxy.LocationRule;
import net.xiidea.enginx.domain.proxy.ProtectionMode;
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

    /**
     * The requested protection, passwords still in the clear for the service to hash; null when
     * the request left it out, which on an update means "keep what the site has".
     */
    public ProtectionInput toProtectionInput(ProtectionRequest request) {
        if (request == null) {
            return null;
        }
        List<ProtectionInput.BasicUser> users = request.basicUsers() == null ? List.of() : request.basicUsers().stream()
                .map(u -> new ProtectionInput.BasicUser(u.username(), u.password()))
                .toList();
        ProtectionInput.EmailOtp emailOtp = request.emailOtp() == null ? null
                : new ProtectionInput.EmailOtp(request.emailOtp().allowedEmails(), request.emailOtp().sessionTtlSeconds());
        ProtectionInput.Oidc oidc = request.oidc() == null ? null : new ProtectionInput.Oidc(
                Boolean.TRUE.equals(request.oidc().anyUser()),
                request.oidc().users(),
                request.oidc().roles() == null ? List.of() : request.oidc().roles().stream()
                        .map(r -> new ProtectionInput.Oidc.Role(r.client(), r.role()))
                        .toList(),
                request.oidc().sessionTtlSeconds());
        return new ProtectionInput(parseEnum(ProtectionMode.class, request.mode(), "protection.mode"), users,
                emailOtp, oidc);
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
                new ProtectionResponse(spec.protection().mode().name(),
                        spec.protection().basicUsers().stream()
                                .map(u -> new ProtectionResponse.BasicAuthUserResponse(u.username()))
                                .toList(),
                        spec.protection().emailOtp() == null ? null : new ProtectionResponse.EmailOtpResponse(
                                spec.protection().emailOtp().allowedEmails().stream()
                                        .map(net.xiidea.enginx.domain.proxy.AllowedEmail::value)
                                        .toList(),
                                spec.protection().emailOtp().sessionTtl().toSeconds()),
                        spec.protection().oidc() == null ? null : new ProtectionResponse.OidcResponse(
                                spec.protection().oidc().anyUser(),
                                spec.protection().oidc().users(),
                                spec.protection().oidc().roles().stream()
                                        .map(r -> new net.xiidea.enginx.api.proxy.dto.OidcRoleDto(r.client(), r.role()))
                                        .toList(),
                                spec.protection().oidc().sessionTtl().toSeconds(),
                                "https://" + spec.domain().value() + "/.enginx/auth/callback")),
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
