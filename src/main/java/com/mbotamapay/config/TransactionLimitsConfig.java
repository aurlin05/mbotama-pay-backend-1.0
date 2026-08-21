package com.mbotamapay.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Configuration des plafonds intelligents anti-requalification
 * 
 * Ces plafonds protègent MBOTAMAPAY de toute requalification en établissement de paiement
 * en limitant les montants selon des règles strictes par transaction, jour, et corridor.
 */
@Configuration
@ConfigurationProperties(prefix = "limits")
@Data
public class TransactionLimitsConfig {

    /**
     * Plafonds par transaction
     */
    private TransactionLimits transaction = new TransactionLimits();

    /**
     * Plafonds quotidiens par niveau KYC
     */
    private DailyLimits daily = new DailyLimits();

    /**
     * Plafonds par corridor (pays source -> pays destination)
     */
    private Map<String, CorridorLimit> corridors = new HashMap<>();

    /**
     * Configuration du mode double passerelle
     */
    private DualGatewayConfig dualGateway = new DualGatewayConfig();

    /**
     * Si true, un corridor absent de la configuration est refusé au lieu de
     * retomber sur un défaut permissif.
     *
     * <p>
     * Le comportement historique — corridor inconnu, on ouvre à 100 000 FCFA —
     * signifiait qu'une erreur de configuration ouvrait un corridor au lieu de le
     * fermer. Sur un service qui déplace de l'argent, le défaut sûr est l'inverse.
     */
    private Boolean rejectUnknownCorridors = false;

    @Data
    public static class TransactionLimits {
        /**
         * Montant minimum par transaction (FCFA)
         */
        private Long minimum = 500L;

        /**
         * Montant maximum par transaction - corridor standard (FCFA)
         */
        private Long maximumStandard = 100_000L;

        /**
         * Montant maximum par transaction - corridor premium (FCFA)
         */
        private Long maximumPremium = 200_000L;

        /**
         * Montant maximum absolu (FCFA) - jamais dépassé
         */
        private Long absoluteMaximum = 200_000L;
    }

    @Data
    public static class DailyLimits {
        /**
         * Limite journalière KYC Niveau 0 (Non vérifié)
         */
        private Long level0 = 0L; // Transactions bloquées

        /**
         * Limite journalière KYC Niveau 1 (Vérifié)
         */
        private Long level1 = 300_000L;

        /**
         * Limite journalière KYC Niveau 2 (Vérifié Complet)
         */
        private Long level2 = 500_000L;
    }

    @Data
    public static class CorridorLimit {
        /**
         * Code du corridor (ex: "SN-SN", "SN-CI")
         */
        private String code;

        /**
         * Limite journalière totale pour ce corridor (FCFA)
         */
        private Long dailyLimit;

        /**
         * Montant maximum par transaction pour ce corridor (FCFA)
         */
        private Long maxPerTransaction;

        /**
         * Nombre maximum de transactions par jour
         */
        private Integer maxTransactionsPerDay;

        /**
         * Passerelle prioritaire pour ce corridor
         */
        private String preferredGateway;

        /**
         * Fiabilité de la passerelle (0.0 à 1.0)
         */
        private Double gatewayReliability = 1.0;

        /**
         * Corridor actif ou désactivé
         */
        private Boolean enabled = true;
    }

    @Data
    public static class DualGatewayConfig {
        /**
         * Mode double passerelle activé globalement
         */
        private Boolean enabled = false;

        /**
         * Montant maximum pour utiliser le mode double passerelle (FCFA)
         */
        private Long maxAmount = 50_000L;

        /**
         * Seuil de solde technique minimum requis (FCFA)
         */
        private Long minTechnicalBalance = 100_000L;

        /**
         * Latence maximum acceptable en millisecondes
         */
        private Long maxLatencyMs = 5000L;

        /**
         * Nombre maximum d'erreurs API tolérées dans les 24h
         */
        private Integer maxApiErrors = 3;

        /**
         * Logs complets requis
         */
        private Boolean requireFullLogs = true;
    }

    /**
     * Valider qu'un montant respecte les limites de transaction
     */
    public boolean isValidTransactionAmount(Long amount) {
        if (amount == null || amount < transaction.minimum) {
            return false;
        }
        return amount <= transaction.absoluteMaximum;
    }

    /**
     * Obtenir la limite journalière selon le niveau KYC
     */
    public Long getDailyLimitForKycLevel(String kycLevel) {
        return switch (kycLevel) {
            case "NONE" -> daily.level0;
            case "LEVEL_1" -> daily.level1;
            case "LEVEL_2" -> daily.level2;
            default -> 0L;
        };
    }

    /**
     * Vérifier si le mode double passerelle peut être utilisé
     */
    public boolean canUseDualGateway(Long amount, Long technicalBalance, Long latencyMs, Integer recentErrors) {
        if (!dualGateway.enabled) {
            return false;
        }

        // Vérifier le montant
        if (amount > dualGateway.maxAmount) {
            return false;
        }

        // Vérifier le solde technique
        if (technicalBalance < dualGateway.minTechnicalBalance) {
            return false;
        }

        // Vérifier la latence
        if (latencyMs > dualGateway.maxLatencyMs) {
            return false;
        }

        // Vérifier les erreurs récentes
        if (recentErrors > dualGateway.maxApiErrors) {
            return false;
        }

        return true;
    }

    /**
     * Obtenir la limite pour un corridor spécifique
     */
    /**
     * Recherche un corridor déclaré.
     *
     * <p>
     * Les clés de configuration sont des codes ISO ({@code "SN-SN"}). L'appelant
     * historique passait {@code Country.name()}, donc {@code "SENEGAL-SENEGAL"} :
     * aucune clé ne correspondait jamais et tous les corridors retombaient
     * silencieusement sur le défaut permissif, y compris ceux volontairement
     * désactivés en configuration.
     *
     * @param sourceIso code ISO du pays source, ex. {@code "SN"}
     * @param destIso   code ISO du pays de destination
     */
    public Optional<CorridorLimit> findCorridor(String sourceIso, String destIso) {
        return Optional.ofNullable(corridors.get(sourceIso + "-" + destIso));
    }

    public boolean isRejectUnknownCorridors() {
        return Boolean.TRUE.equals(rejectUnknownCorridors);
    }

    public CorridorLimit getCorridorLimit(String sourceCountry, String destCountry) {
        String corridorCode = sourceCountry + "-" + destCountry;
        return corridors.getOrDefault(corridorCode, createDefaultCorridorLimit(corridorCode));
    }

    private CorridorLimit createDefaultCorridorLimit(String code) {
        CorridorLimit defaultLimit = new CorridorLimit();
        defaultLimit.setCode(code);
        defaultLimit.setDailyLimit(1_000_000L); // 1M FCFA par jour par défaut
        defaultLimit.setMaxPerTransaction(transaction.maximumStandard);
        defaultLimit.setMaxTransactionsPerDay(50);
        defaultLimit.setGatewayReliability(0.95);
        defaultLimit.setEnabled(true);
        return defaultLimit;
    }
}
