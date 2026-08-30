package net.xiidea.enginx.security;

import net.xiidea.enginx.security.local.LocalTokenRevocationFilter;
import net.xiidea.enginx.security.local.PasswordChangeRequiredFilter;
import net.xiidea.enginx.security.ratelimit.RateLimitFilter;
import jakarta.servlet.Filter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Resource-server configuration.
 *
 * <p>The application is a resource server for two possible issuers: an OIDC provider, and — when
 * local accounts are enabled — itself. Both mint the same claims, so everything from
 * {@link KeycloakJwtAuthenticationConverter} onward is unaware which one authenticated the caller.
 * Which issuers are trusted is decided in {@link JwtDecoderConfiguration}.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties({SecurityProperties.class, AuthProperties.class})
public class SecurityConfig {

    private final SecurityProperties properties;
    private final IdentityMirrorFilter identityMirrorFilter;
    private final RateLimitFilter rateLimitFilter;
    private final PasswordChangeRequiredFilter passwordChangeRequiredFilter;
    private final LocalTokenRevocationFilter localTokenRevocationFilter;

    public SecurityConfig(SecurityProperties properties, IdentityMirrorFilter identityMirrorFilter,
                          RateLimitFilter rateLimitFilter,
                          PasswordChangeRequiredFilter passwordChangeRequiredFilter,
                          LocalTokenRevocationFilter localTokenRevocationFilter) {
        this.properties = properties;
        this.identityMirrorFilter = identityMirrorFilter;
        this.rateLimitFilter = rateLimitFilter;
        this.passwordChangeRequiredFilter = passwordChangeRequiredFilter;
        this.localTokenRevocationFilter = localTokenRevocationFilter;
    }

    @Bean
    @Order(2)
    SecurityFilterChain apiFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/**")
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/api/**").permitAll()
                        // Necessarily open: these are what a caller uses before it has a token.
                        // Neither reveals whether an account exists, and login is rate limited as
                        // a sensitive operation so the openness is not a brute-force surface.
                        .requestMatchers("/api/v1/auth/methods", "/api/v1/auth/login").permitAll()
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
                // Last of the three, so a confined caller is still counted against its rate limit
                // and still appears in the identity mirror. Placing it earlier would let an
                // account that cannot use the API spend nothing to keep asking.
                .addFilterAfter(passwordChangeRequiredFilter, IdentityMirrorFilter.class)
                // Turns away a local token whose account has since been disabled, deleted or
                // re-privileged, before it reaches a controller.
                .addFilterAfter(localTokenRevocationFilter, PasswordChangeRequiredFilter.class)
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

    /**
     * The chain for hosts that call in, which the API chain would reject outright.
     *
     * <p>Agents authenticate with a token, not a JWT, so this chain permits the requests and lets
     * the controller resolve the credential. That reads as a hole and is not: {@code /register}
     * is unauthenticated by necessity — the caller is a machine nobody has met, and the
     * registration token is the whole of its claim — and every other path here resolves its bearer
     * token to an instance before doing anything, refusing with 403 if it cannot.
     *
     * <p>Declared before the API chain by {@code @Order}, because {@code /api/v1/agents/**} would
     * otherwise be swallowed by that chain's {@code /api/**} matcher and demand a JWT.
     *
     * <p>Both paths are rate limited as sensitive operations: {@code /register} is open, so
     * guessing a registration token must be expensive.
     */
    @Bean
    @Order(1)
    SecurityFilterChain agentFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/v1/agents/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Ahead of the chain proper: an unauthenticated endpoint that mints a credential
                // must not be free to hammer.
                .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                // Bearer tokens and no cookies, so there is no CSRF surface here either.
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentSecurityPolicy(csp ->
                                csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .referrerPolicy(referrer ->
                                referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)));
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
                // The same confinement as the API chain. Metrics and thread dumps are exactly the
                // sort of thing a not-yet-rotated bootstrap credential should not reach.
                .addFilterAfter(passwordChangeRequiredFilter, BearerTokenAuthenticationFilter.class)
                // And the same revocation: a disabled account must not keep reaching metrics either.
                .addFilterAfter(localTokenRevocationFilter, PasswordChangeRequiredFilter.class)
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

    @Bean
    FilterRegistrationBean<Filter> passwordChangeRequiredFilterRegistration() {
        return unregistered(passwordChangeRequiredFilter);
    }

    @Bean
    FilterRegistrationBean<Filter> localTokenRevocationFilterRegistration() {
        return unregistered(localTokenRevocationFilter);
    }

    private static FilterRegistrationBean<Filter> unregistered(Filter filter) {
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
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
