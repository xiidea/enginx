package net.xiidea.enginx.api.identity;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import net.xiidea.enginx.application.identity.AccessTokenIssuer;
import net.xiidea.enginx.application.identity.AuthenticationMethods;
import net.xiidea.enginx.application.identity.LocalAuthenticationService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Signing in, and finding out how.
 *
 * <p>Both endpoints are unauthenticated by necessity — they are what a caller uses before it has a
 * token. Neither reveals whether a given account exists.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Signing in, and discovering which methods are available")
public class AuthController {

    private final AuthenticationMethods methods;
    /**
     * Absent when local authentication is switched off, in which case {@link #login} refuses
     * rather than authenticating against a store the deployment has disabled.
     */
    private final ObjectProvider<LocalAuthenticationService> localAuth;

    /** Null when the jar carries no build-info, as in tests run from the IDE. */
    private final String serverVersion;

    public AuthController(AuthenticationMethods methods,
                          ObjectProvider<LocalAuthenticationService> localAuth,
                          ObjectProvider<BuildProperties> build) {
        this.methods = methods;
        this.localAuth = localAuth;
        BuildProperties properties = build.getIfAvailable();
        this.serverVersion = properties == null ? null : properties.getVersion();
    }

    @GetMapping("/methods")
    @Operation(summary = "Which authentication methods this deployment offers",
            description = "Unauthenticated: a login screen has to render before anyone has a token. "
                    + "Returns only what a login page needs — never whether an account exists.")
    public MethodsResponse methods() {
        return new MethodsResponse(methods.localEnabled(), methods.oidcEnabled(),
                methods.oidcIssuer(), methods.oidcClientId(), serverVersion);
    }

    @PostMapping("/login")
    @Operation(summary = "Exchange a username and password for an access token",
            description = "Available only when local authentication is enabled. Rate limited as a "
                    + "sensitive operation, and every rejection returns the same message whether the "
                    + "account is missing, disabled or the password is wrong.")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        LocalAuthenticationService service = localAuth.getIfAvailable();
        if (service == null || !methods.localEnabled()) {
            throw new LocalAuthenticationService.AuthenticationFailedException(
                    "Local authentication is not enabled on this deployment");
        }
        AccessTokenIssuer.IssuedToken issued = service.authenticate(request.username(), request.password());
        return new TokenResponse(issued.token(), "Bearer", issued.expiresInSeconds(), issued.expiresAt());
    }

    @Schema(name = "LoginRequest", requiredProperties = {"username", "password"})
    public record LoginRequest(
            @NotBlank(message = "A username is required") String username,
            @NotBlank(message = "A password is required") String password) {
    }

    /**
     * @param expiresInSeconds lifetime, so a client can refresh before expiry rather than after a
     *                         401 has already interrupted somebody
     */
    @Schema(name = "TokenResponse", requiredProperties = {"accessToken", "tokenType", "expiresInSeconds"})
    public record TokenResponse(String accessToken, String tokenType, long expiresInSeconds,
                                java.time.Instant expiresAt) {
    }

    /**
     * @param serverVersion the management server's release, shown beside the console's own so a
     *                      mismatched pair after a partial upgrade is visible. Public, as it is on
     *                      /actuator/info; read here because this is the call the console already makes
     */
    @Schema(name = "AuthMethodsResponse", requiredProperties = {"localEnabled", "oidcEnabled"})
    public record MethodsResponse(boolean localEnabled, boolean oidcEnabled,
                                  String oidcIssuer, String oidcClientId, String serverVersion) {
    }
}
