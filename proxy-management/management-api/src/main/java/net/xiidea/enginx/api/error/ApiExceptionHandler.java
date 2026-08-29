package net.xiidea.enginx.api.error;

import net.xiidea.enginx.application.shared.AuditRecorder;
import net.xiidea.enginx.domain.audit.AuditAction;
import net.xiidea.enginx.domain.shared.ConflictException;
import net.xiidea.enginx.domain.shared.NotFoundException;
import net.xiidea.enginx.domain.shared.ValidationException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import net.xiidea.enginx.application.identity.LocalAuthenticationService;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Translates every failure into an RFC 9457 problem document.
 *
 * <p>Two rules hold throughout: the {@code type} URI is stable so clients can branch on it rather
 * than on prose, and no handler echoes an internal message. A stack trace or a constraint name
 * tells an attacker about the schema; the log keeps the detail, the response keeps a correlation id.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String TYPE_BASE = "https://enginx.dev/problems/";

    private final AuditRecorder audit;

    public ApiExceptionHandler(AuditRecorder audit) {
        this.audit = audit;
    }

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail handleNotFound(NotFoundException e, HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.NOT_FOUND, "not-found", "Resource not found", e.getMessage(), request);
        problem.setProperty("resourceType", e.resourceType());
        problem.setProperty("resourceId", String.valueOf(e.resourceId()));
        return problem;
    }

    @ExceptionHandler(ValidationException.class)
    public ProblemDetail handleDomainValidation(ValidationException e, HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_CONTENT, "validation-failed",
                "The request could not be processed", e.getMessage(), request);
        if (e.field() != null) {
            problem.setProperty("errors", List.of(Map.of("field", e.field(), "message", e.getMessage())));
        }
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleBeanValidation(MethodArgumentNotValidException e, HttpServletRequest request) {
        List<Map<String, String>> errors = e.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of(
                        "field", error.getField(),
                        "message", error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage()))
                .toList();

        ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_CONTENT, "validation-failed",
                "The request could not be processed",
                "One or more fields are invalid. See 'errors' for details.", request);
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(ConflictException.class)
    public ProblemDetail handleConflict(ConflictException e, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "conflict", "The request conflicts with existing state",
                e.getMessage(), request);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLock(OptimisticLockingFailureException e, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "concurrent-modification", "The resource was modified concurrently",
                "Someone else changed this resource while you were editing it. Reload and try again.", request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrity(DataIntegrityViolationException e, HttpServletRequest request) {
        String reference = correlate();
        log.warn("Data integrity violation [{}] on {}", reference, request.getRequestURI(), e);
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "constraint-violation",
                "The request conflicts with existing state",
                "The change was rejected by a database constraint. A referenced record may not exist, "
                        + "or a unique value is already taken.", request);
        problem.setProperty("reference", reference);
        return problem;
    }

    /**
     * A rejected login is 401, not 403: the caller has not proven who they are, rather than proven
     * it and been refused. The detail is whatever the service chose, which is deliberately the same
     * sentence for every cause.
     */
    @ExceptionHandler(LocalAuthenticationService.AuthenticationFailedException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ProblemDetail handleAuthenticationFailed(
            LocalAuthenticationService.AuthenticationFailedException e, HttpServletRequest request) {
        return problem(HttpStatus.UNAUTHORIZED, "authentication-failed", "Authentication failed",
                e.getMessage(), request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException e, HttpServletRequest request) {
        audit.denied(AuditAction.ACCESS_DENIED, "HTTP_REQUEST", null,
                request.getMethod() + " " + request.getRequestURI());
        return problem(HttpStatus.FORBIDDEN, "access-denied", "Access denied",
                "Your account does not have permission to perform this action.", request);
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class,
            MissingRequestHeaderException.class, HttpMessageNotReadableException.class})
    public ProblemDetail handleMalformedRequest(Exception e, HttpServletRequest request) {
        String detail = switch (e) {
            case MethodArgumentTypeMismatchException mismatch ->
                    "'" + mismatch.getName() + "' is not in the expected format";
            case MissingRequestHeaderException missing ->
                    "The '" + missing.getHeaderName() + "' header is required";
            default -> "The request body or parameters could not be read";
        };
        return problem(HttpStatus.BAD_REQUEST, "malformed-request", "Malformed request", detail, request);
    }

    /**
     * The request never matched a handler: an unknown path, the wrong method for a known one, a
     * body this endpoint cannot read, or a response type it cannot produce.
     *
     * <p>Spring raises these before any controller runs, and each already carries the right status.
     * Without this they fall through to {@link #handleUnexpected}, which reports a client's typo as
     * a server fault -- a 500 that pages somebody, and that tells the caller to report a bug that
     * is theirs. They are not logged as errors for the same reason.
     *
     * <p>Answering 404 here discloses nothing: every path under {@code /api} requires
     * authentication, so an anonymous caller is still turned away with 401 before reaching this.
     */
    @ExceptionHandler({NoResourceFoundException.class, HttpRequestMethodNotSupportedException.class,
            HttpMediaTypeNotSupportedException.class, HttpMediaTypeNotAcceptableException.class})
    public ResponseEntity<ProblemDetail> handleUnmatchedRequest(ErrorResponse e, HttpServletRequest request) {
        HttpStatus status = HttpStatus.valueOf(e.getStatusCode().value());
        ProblemDetail problem = problem(status, slugFor(status), titleFor(status), detailFor(e, request), request);

        // RFC 9110 requires Allow on a 405, and it is the only thing that tells a caller which
        // method to use instead.
        HttpHeaders headers = new HttpHeaders();
        if (e instanceof HttpRequestMethodNotSupportedException methodNotSupported
                && methodNotSupported.getSupportedHttpMethods() != null) {
            headers.setAllow(methodNotSupported.getSupportedHttpMethods());
        }
        return new ResponseEntity<>(problem, headers, status);
    }

    private static String slugFor(HttpStatus status) {
        return switch (status) {
            case METHOD_NOT_ALLOWED -> "method-not-allowed";
            case UNSUPPORTED_MEDIA_TYPE -> "unsupported-media-type";
            case NOT_ACCEPTABLE -> "not-acceptable";
            default -> "no-such-endpoint";
        };
    }

    private static String titleFor(HttpStatus status) {
        return switch (status) {
            case METHOD_NOT_ALLOWED -> "Method not allowed";
            case UNSUPPORTED_MEDIA_TYPE -> "Unsupported media type";
            case NOT_ACCEPTABLE -> "Not acceptable";
            default -> "No such endpoint";
        };
    }

    /**
     * Says what to change, without echoing anything the caller did not already send. The method and
     * path came from the request line; the supported types came from this application's own
     * mappings.
     */
    private static String detailFor(ErrorResponse e, HttpServletRequest request) {
        return switch (e) {
            case HttpRequestMethodNotSupportedException methodNotSupported ->
                    request.getMethod() + " is not supported here. Supported: "
                            + String.join(", ", methodNotSupported.getSupportedMethods() == null
                                    ? new String[0] : methodNotSupported.getSupportedMethods()) + ".";
            case HttpMediaTypeNotSupportedException mediaType ->
                    "The request body's Content-Type is not supported. Supported: "
                            + mediaType.getSupportedMediaTypes() + ".";
            case HttpMediaTypeNotAcceptableException acceptable ->
                    "No representation matches the Accept header. Available: "
                            + acceptable.getSupportedMediaTypes() + ".";
            default -> "No endpoint exists at " + request.getRequestURI() + ".";
        };
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e, HttpServletRequest request) {
        String reference = correlate();
        log.error("Unhandled exception [{}] on {} {}", reference, request.getMethod(), request.getRequestURI(), e);
        ProblemDetail problem = problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal server error",
                "The request could not be completed. Quote the reference when reporting this.", request);
        problem.setProperty("reference", reference);
        return problem;
    }

    private static ProblemDetail problem(HttpStatus status, String slug, String title, String detail,
                                         HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_BASE + slug));
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }

    /** A short id that appears in both the log line and the response, so the two can be joined. */
    private static String correlate() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
