package net.xiidea.enginx.security.local;

import net.xiidea.enginx.application.identity.LocalTokenValidator;
import net.xiidea.enginx.domain.identity.LocalUser;
import net.xiidea.enginx.security.AuthProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/**
 * Turns a revoked local account away, even while its token is still within its lifetime.
 *
 * <p>A local token carries roles and lasts hours, so disabling, deleting, or re-privileging an
 * account does nothing to a token already issued — the change would take effect only when the token
 * happened to expire. Enforced here, on the validated token, because a console check is a
 * suggestion a bearer token ignores: {@code curl} works just as well.
 *
 * <p>Only local tokens are subject to this. An OIDC token is the identity provider's to revoke, on
 * its own schedule; {@link LocalTokenValidator} explains why re-deciding it here would be wrong.
 *
 * <p>One consequence worth stating: any security-relevant change to an account, a self-service
 * password change included, invalidates that account's other live tokens at once. That is the point
 * — a changed password should not leave older sessions standing — and it means the console
 * re-authenticates after such a change rather than reusing the token that made it.
 */
@Component
public class LocalTokenRevocationFilter extends OncePerRequestFilter {

    private static final String TYPE = "https://enginx.dev/problems/session-revoked";

    private final LocalTokenValidator validator;
    private final ObjectMapper objectMapper;

    public LocalTokenRevocationFilter(LocalTokenValidator validator, ObjectMapper objectMapper) {
        this.validator = validator;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token) || !isLocal(token.getToken())) {
            chain.doFilter(request, response);
            return;
        }

        UUID userId = localUserId(token.getToken().getSubject());
        if (userId != null && validator.isStillValid(userId, token.getToken().getIssuedAt())) {
            chain.doFilter(request, response);
            return;
        }

        // One message for every reason — disabled, deleted, re-privileged, malformed subject. The
        // caller learns only that this token is finished, not which change ended it.
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
                "This session is no longer valid. Sign in again.");
        problem.setType(URI.create(TYPE));
        problem.setTitle("Session revoked");
        problem.setInstance(URI.create(request.getRequestURI()));

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }

    private static boolean isLocal(Jwt token) {
        return AuthProperties.LOCAL_ISSUER.equals(token.getClaimAsString("iss"));
    }

    private static UUID localUserId(String subject) {
        if (subject == null || !subject.startsWith(LocalUser.SUBJECT_PREFIX)) {
            return null;
        }
        try {
            return UUID.fromString(subject.substring(LocalUser.SUBJECT_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
