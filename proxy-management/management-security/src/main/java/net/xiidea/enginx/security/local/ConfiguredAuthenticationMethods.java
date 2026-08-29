package net.xiidea.enginx.security.local;

import net.xiidea.enginx.application.identity.AuthenticationMethods;
import net.xiidea.enginx.security.AuthProperties;
import net.xiidea.enginx.security.SecurityProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Reports what the configuration turned on, for the console's login screen. */
@Component
public class ConfiguredAuthenticationMethods implements AuthenticationMethods {

    private final AuthProperties auth;
    private final SecurityProperties security;
    private final String issuer;

    public ConfiguredAuthenticationMethods(
            AuthProperties auth, SecurityProperties security,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuer) {
        this.auth = auth;
        this.security = security;
        this.issuer = issuer == null || issuer.isBlank() ? null : issuer;
    }

    @Override
    public boolean localEnabled() {
        return auth.localEnabled();
    }

    @Override
    public boolean oidcEnabled() {
        return auth.oidcEnabled();
    }

    @Override
    public String oidcIssuer() {
        return auth.oidcEnabled() ? issuer : null;
    }

    @Override
    public String oidcClientId() {
        return auth.oidcEnabled() ? security.clientId() : null;
    }
}
