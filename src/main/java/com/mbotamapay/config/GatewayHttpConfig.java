package com.mbotamapay.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Client HTTP partagé par toutes les passerelles de paiement.
 *
 * <p>
 * Les passerelles construisaient chacune leur propre {@code new RestTemplate()},
 * donc sans aucun délai d'expiration : un partenaire qui accepte la connexion
 * sans répondre immobilisait le fil d'exécution indéfiniment. Un seul bean
 * configuré ici garantit que ce n'est plus possible nulle part.
 */
@Configuration
public class GatewayHttpConfig {

    public static final String GATEWAY_REST_TEMPLATE = "gatewayRestTemplate";

    @Value("${gateway.http.connect-timeout-ms:5000}")
    private long connectTimeoutMs;

    @Value("${gateway.http.read-timeout-ms:15000}")
    private long readTimeoutMs;

    @Bean(GATEWAY_REST_TEMPLATE)
    public RestTemplate gatewayRestTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                .setReadTimeout(Duration.ofMillis(readTimeoutMs))
                .build();
    }
}
