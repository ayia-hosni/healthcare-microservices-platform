package com.healthplatform.common.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Shared Swagger/OpenAPI metadata for every REST service (identical to how PlatformSecurityConfig
 * is the one shared security chain): sets the API title/version from spring.application.name and
 * registers the "bearerAuth" scheme so Swagger UI's Authorize button can attach a JWT to requests
 * against the PlatformSecurityConfig-protected endpoints. Applied globally via addSecurityItem
 * rather than per-controller, so the small number of intentionally public endpoints (e.g.
 * identity-service's /api/v1/auth/**) show a padlock in the docs even though they don't require
 * one at runtime -- a cosmetic inaccuracy preferred over repeating @SecurityRequirement on every
 * other controller.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI healthPlatformOpenApi(@Value("${spring.application.name}") String applicationName) {
        return new OpenAPI()
                .info(new Info()
                        .title(applicationName)
                        .description("Healthcare platform API - " + applicationName)
                        .version("v1"))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                                .name(BEARER_SCHEME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
