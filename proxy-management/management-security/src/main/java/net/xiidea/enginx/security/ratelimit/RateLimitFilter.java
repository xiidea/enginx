package net.xiidea.enginx.security.ratelimit;

import tools.jackson.databind.ObjectMapper;
import net.xiidea.enginx.security.KeycloakJwtAuthenticationConverter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;

/**
 * Applies the rate limit, and answers 429 when it is exhausted.
 *
 * <p>Runs after authentication so the bucket can be keyed by the authenticated subject rather than
 * by address. Keying on address alone would be wrong in both directions here: every operator behind
 * one corporate NAT would share a single allowance, while an attacker with a token and a handful of
 * addresses would get a fresh one per address.
 *
 * <p>Unauthenticated requests fall back to the peer address, since there is nothing better to key
 * on. That path is deliberately not the main defence -- authentication already rejects those
 * requests -- it only stops an unauthenticated flood from being free.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final String TYPE = "https://enginx.dev/problems/rate-limited";

    private final RateLimiter limiter;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(RateLimiter limiter, ObjectMapper objectMapper) {
        this.limiter = limiter;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Health and metrics are polled on a timer by machines whose whole job is to keep asking.
        // Rate-limiting them would mean the platform reports itself unhealthy under load, which is
        // exactly when its monitoring needs to keep working.
        String path = request.getRequestURI();
        return path.startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        RateLimitTier tier = SensitivePaths.tierFor(request.getRequestURI(), request.getMethod());
        RateLimiter.Decision decision = limiter.tryConsume(caller(request), tier);

        response.setHeader("RateLimit-Limit", Long.toString(decision.limit()));
        response.setHeader("RateLimit-Remaining", Long.toString(decision.remaining()));

        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }

        // Logged at warn with the tier and path but never with the token: a limiter's log is one
        // of the easier places to leak a credential into a file with wider read access.
        log.warn("Rate limit exceeded: tier={} method={} path={}",
                tier, request.getMethod(), request.getRequestURI());

        writeProblem(request, response, tier, decision);
    }

    private void writeProblem(HttpServletRequest request, HttpServletResponse response,
                              RateLimitTier tier, RateLimiter.Decision decision) throws IOException {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                "Too many requests. Retry after " + decision.retryAfterSeconds() + " seconds.");
        problem.setType(URI.create(TYPE));
        problem.setTitle("Rate limit exceeded");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("tier", tier.name());
        problem.setProperty("retryAfterSeconds", decision.retryAfterSeconds());

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
        objectMapper.writeValue(response.getOutputStream(), problem);
    }

    /**
     * The subject claim, which is stable across a user's tokens -- so refreshing a token, or
     * signing in again, does not hand the caller a fresh allowance.
     */
    private static String caller(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token) {
            return "sub:" + KeycloakJwtAuthenticationConverter.toPrincipal(token.getToken()).subject();
        }
        return "ip:" + request.getRemoteAddr();
    }
}
