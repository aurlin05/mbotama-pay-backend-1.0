package com.mbotamapay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * MbotamaPay Backend Application
 * Mobile Money Transfer Platform for West Africa
 */
@SpringBootApplication
@EnableJpaAuditing
public class MbotamapayApplication {

    public static void main(String[] args) {
        SpringApplication.run(MbotamapayApplication.class, args);
    }
}
