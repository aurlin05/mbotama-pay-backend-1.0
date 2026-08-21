package com.mbotamapay.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Audit JPA, isolé de la classe d'application.
 *
 * <p>
 * {@code @EnableJpaAuditing} était porté par {@code MbotamapayApplication}. Or
 * un test {@code @WebMvcTest} charge cette classe pour découvrir la
 * configuration, sans charger les entités : l'audit exigeait alors un métamodèle
 * JPA qui n'existait pas, et le contexte échouait avec
 * « JPA metamodel must not be empty ». C'est ce qui empêchait
 * {@code PaymentControllerTest} de démarrer.
 *
 * <p>
 * Dans une classe de configuration distincte, l'annotation n'est chargée que par
 * les tests qui montent réellement la couche de persistance.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
