package net.xiidea.enginx.security.local;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.xiidea.enginx.domain.identity.LocalUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;

/**
 * Confines an account that still has to change its password to doing exactly that.
 *
 * <p>The flag matters most on the bootstrap administrator, whose password came from configuration
 * and has therefore been readable by everything that can read configuration — a deployment
 * manifest, a shell history, whatever logged the environment on the last crash. Until that password
 * is replaced, the credential should be good for replacing itself and nothing else.
 *
 * <p>Enforced here rather than in the console, because a console check is a suggestion: the token
 * is a bearer token and curl works just as well. The claim is read from the validated token, so it
 * cannot be edited by the caller.
 */
@Component
public class PasswordChangeRequiredFilter extends OncePerRequestFilter {

    private static final String TYPE = "https://enginx.dev/problems/password-change-required";
    static final String CLAIM = "must_change_password";

    private final ObjectMapper objectMapper;

    public PasswordChangeRequiredFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)
                || !Boolean.TRUE.equals(token.getToken().getClaimAsBoolean(CLAIM))
                || isOwnPasswordChange(request, token.getToken().getSubject())) {
            chain.doFilter(request, response);
            return;
        }

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
                "This account must change its password before it can do anything else.");
        problem.setType(URI.create(TYPE));
        problem.setTitle("Password change required");
        problem.setInstance(URI.create(request.getRequestURI()));

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }

    /**
     * The one request such a caller may make.
     *
     * <p>Matched against the subject in the caller's own token, so this cannot be used to reset
     * somebody else's password — which would otherwise be a privilege escalation handed to whoever
     * read the bootstrap password out of a manifest.
     */
    private static boolean isOwnPasswordChange(HttpServletRequest request, String subject) {
        if (!"PUT".equals(request.getMethod()) || subject == null
                || !subject.startsWith(LocalUser.SUBJECT_PREFIX)) {
            return false;
        }
        String id = subject.substring(LocalUser.SUBJECT_PREFIX.length());
        return request.getRequestURI().equals("/api/v1/local-users/" + id + "/password");
    }
}
