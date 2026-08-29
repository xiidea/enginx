package net.xiidea.enginx.security;

import net.xiidea.enginx.security.ratelimit.RateLimitFilter;
import jakarta.servlet.Filter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Resource-server configuration.
 *
 * <p>The application never sees a password: Keycloak is the only identity provider, and this
 * service does nothing but validate the tokens Keycloak issues.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

    private final SecurityProperties properties;
    private final IdentityMirrorFilter identityMirrorFilter;
    private final RateLimitFilter rateLimitFilter;

    public SecurityConfig(SecurityProperties properties, IdentityMirrorFilter identityMirrorFilter,
                          RateLimitFilter rateLimitFilter) {
        this.properties = properties;
        this.identityMirrorFilter = identityMirrorFilter;
        this.rateLimitFilter = rateLimitFilter;
    }

    @Bean
    SecurityFilterChain apiFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/**")
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/api/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(
                                new KeycloakJwtAuthenticationConverter(properties.clientId()))))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Both run immediately after the bearer token is validated, so they see the
                // authenticated subject. The limiter goes first: a request that is being shed
                // should not also pay for the work behind it.
                .addFilterAfter(rateLimitFilter, BearerTokenAuthenticationFilter.class)
                .addFilterAfter(identityMirrorFilter, RateLimitFilter.class)
                // Bearer tokens, no cookies and no session mean this chain has no CSRF surface.
                // A cookie-backed endpoint added later must re-enable it rather than inherit this.
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentSecurityPolicy(csp ->
                                csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .referrerPolicy(referrer ->
                                referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31_536_000)));
        return http.build();
    }

    /** Health and API documentation stay open; everything else under /actuator does not. */
    @Bean
    SecurityFilterChain supportFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/actuator/**", "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**")
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        .anyRequest().hasRole(Roles.SUPER_ADMIN))
                // The same converter as the API chain. The default one maps scopes to
                // SCOPE_* authorities and ignores realm roles entirely, which would leave the
                // hasRole check above impossible to satisfy -- locking every operator out of
                // metrics while looking like a working authorization rule.
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(
                                new KeycloakJwtAuthenticationConverter(properties.clientId()))))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable());
        return http.build();
    }

    /**
     * Keeps the two custom filters out of the servlet container's own filter chain.
     *
     * <p>Boot registers every {@code Filter} bean with the container automatically, which would run
     * each of these a second time and, worse, in the wrong place: ahead of Spring Security, before
     * any token has been validated. The rate limiter would then key every request on its peer
     * address instead of its subject, and the identity mirror would read a security context that
     * has not been populated yet. Both would fail quietly and look like they were working.
     *
     * <p>{@code setEnabled(false)} suppresses only the container registration. The explicit
     * {@code addFilterAfter} calls above remain the single place either filter is installed.
     */
    @Bean
    FilterRegistrationBean<Filter> rateLimitFilterRegistration() {
        return unregistered(rateLimitFilter);
    }

    @Bean
    FilterRegistrationBean<Filter> identityMirrorFilterRegistration() {
        return unregistered(identityMirrorFilter);
    }

    private static FilterRegistrationBean<Filter> unregistered(Filter filter) {
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * Adds audience validation on top of the standard issuer and expiry checks. Without it, any
     * token from the same realm would be accepted here, including one minted for another app.
     *
     * <p>When {@code enginx.security.jwk-set-uri} is set, keys are fetched from there instead of
     * through discovery. The issuer inside the token is still validated against {@code issuerUri}
     * either way, so splitting the two loosens nothing: it only lets this service reach the
     * identity provider by a different name than the browser does.
     */
    @Bean
    JwtDecoder jwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri) {
        NimbusJwtDecoder decoder = properties.jwkSetUri() == null
                ? (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(issuerUri)
                : NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();

        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuerUri),
                new AudienceValidator(properties.clientId()));
        decoder.setJwtValidator(validator);
        return decoder;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(properties.corsAllowedOrigins()));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "If-Match", "Idempotency-Key"));
        configuration.setExposedHeaders(List.of("ETag", "Location"));
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
