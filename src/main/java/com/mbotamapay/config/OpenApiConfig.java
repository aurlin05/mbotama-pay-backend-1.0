package com.mbotamapay.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI / Swagger Configuration
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI mbotamapayOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("MbotamaPay API")
                        .description("API Backend pour MbotamaPay - Plateforme de transfert d'argent mobile")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("MbotamaPay Team")
                                .email("support@mbotamapay.com"))
                        .license(new License()
                                .name("Proprietary")
                                .url("https://mbotamapay.com")))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("JWT token authentication")));
    }
}
