package net.xiidea.enginx.security;

import net.xiidea.enginx.application.shared.IdentityMirror;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Notes the caller in the identity mirror after authentication has succeeded.
 *
 * <p>The filter runs after the request is served, not before, so the bookkeeping can never delay
 * or fail the response. The mirror itself throttles how often it actually writes.
 */
@Component
public class IdentityMirrorFilter extends OncePerRequestFilter {

    private final IdentityMirror mirror;

    public IdentityMirrorFilter(IdentityMirror mirror) {
        this.mirror = mirror;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(request, response);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token) {
            UserPrincipal principal = KeycloakJwtAuthenticationConverter.toPrincipal(token.getToken());
            mirror.recordSeen(principal.subject(), principal.username(), principal.email(),
                    token.getToken().getClaimAsString("name"), principal.groups());
        }
    }
}
