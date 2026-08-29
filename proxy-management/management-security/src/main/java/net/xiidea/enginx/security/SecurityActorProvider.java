package net.xiidea.enginx.security;

import net.xiidea.enginx.application.shared.Actor;
import net.xiidea.enginx.application.shared.ActorProvider;
import net.xiidea.enginx.application.shared.SubjectProvider;
import net.xiidea.enginx.domain.permission.AuthenticatedSubject;
import net.xiidea.enginx.domain.permission.GlobalRole;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Projects the access token into the two identities the application needs: who to name in an
 * audit row, and who to evaluate permissions for.
 *
 * <p>Both are read from the token on every request. Nothing here consults the mirrored
 * {@code app_users} or {@code app_groups} tables, which is what bounds revocation latency to a
 * single token lifetime rather than to whenever a synchronisation job last succeeded.
 */
@Component
public class SecurityActorProvider implements ActorProvider, SubjectProvider {

    @Override
    public Actor currentActor() {
        Jwt jwt = currentJwt();
        if (jwt == null) {
            // Background jobs and startup tasks run without a request.
            return withRequestContext(Actor.system());
        }
        UserPrincipal principal = KeycloakJwtAuthenticationConverter.toPrincipal(jwt);
        return withRequestContext(new Actor(principal.subject(), principal.username(), null, null));
    }

    @Override
    public AuthenticatedSubject currentSubject() {
        Jwt jwt = currentJwt();
        if (jwt == null) {
            // No token means no roles and no groups, so every grant lookup comes back empty and
            // every permission check fails closed.
            return new AuthenticatedSubject(null, Set.of(), Set.of());
        }
        UserPrincipal principal = KeycloakJwtAuthenticationConverter.toPrincipal(jwt);

        Set<GlobalRole> roles = new LinkedHashSet<>();
        for (String role : principal.roles()) {
            GlobalRole.from(role).ifPresent(roles::add);
        }

        return new AuthenticatedSubject(principal.subject(), principal.groups(), roles);
    }

    private static Jwt currentJwt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication instanceof JwtAuthenticationToken token ? token.getToken() : null;
    }

    private static Actor withRequestContext(Actor actor) {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return actor;
        }
        return new Actor(actor.subject(), actor.username(), clientIp(request), request.getHeader("User-Agent"));
    }

    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }

    /**
     * Trusts {@code X-Forwarded-For} only for its first entry, and only because this API is
     * expected to sit behind a reverse proxy that overwrites the header. If it is ever exposed
     * directly, this must become a configured trusted-proxy list.
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty()) {
                return first.length() > 64 ? first.substring(0, 64) : first;
            }
        }
        return request.getRemoteAddr();
    }
}
