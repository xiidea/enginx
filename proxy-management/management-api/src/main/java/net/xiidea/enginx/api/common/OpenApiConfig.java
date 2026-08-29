package net.xiidea.enginx.api.common;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearer-jwt";

    @Bean
    OpenAPI enginxOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Easy NGINX Admin API")
                        .version("v1")
                        .description("""
                                Management API for reverse-proxy sites across one or more NGINX hosts.

                                Authentication is OIDC: obtain an access token from Keycloak and send it as
                                a bearer token. Errors are RFC 9457 problem documents; branch on the stable
                                `type` URI rather than on the human-readable `detail`.
                                """)
                        .license(new License().name("Proprietary")))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
