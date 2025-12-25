package com.mbotamapay.dto.user;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response DTO contenant toutes les limites applicables à un utilisateur
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserLimitsResponse {

    /**
     * Niveau KYC de l'utilisateur
     */
    private String kycLevel;
    
    /**
     * Nom d'affichage du niveau KYC
     */
    private String kycLevelDisplayName;
    
    /**
     * Description du niveau KYC
     */
    private String kycDescription;

    /**
     * Limites par transaction
     */
    private TransactionLimits transactionLimits;

    /**
     * Limites quotidiennes
     */
    private DailyLimits dailyLimits;

    /**
     * Limites mensuelles
     */
    private MonthlyLimits monthlyLimits;

    /**
     * Limites par corridor disponibles pour l'utilisateur
     */
    private List<CorridorLimitInfo> corridorLimits;

    /**
     * Informations sur les conditions qui peuvent modifier les limites
     */
    private LimitModifiers modifiers;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransactionLimits {
        /**
         * Montant minimum par transaction (en FCFA)
         */
        private Long minimum;

        /**
         * Montant maximum par transaction selon le niveau KYC (en FCFA)
         */
        private Long maximumStandard;

        /**
         * Montant maximum absolu par transaction (en FCFA)
         */
        private Long absoluteMaximum;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DailyLimits {
        /**
         * Limite quotidienne totale (en FCFA)
         */
        private Long limit;

        /**
         * Montant déjà utilisé aujourd'hui (en FCFA)
         */
        private Long used;

        /**
         * Montant restant aujourd'hui (en FCFA)
         */
        private Long remaining;

        /**
         * Pourcentage utilisé (0-100)
         */
        private Double percentageUsed;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MonthlyLimits {
        /**
         * Limite mensuelle totale (en FCFA), -1 si illimitée
         */
        private Long limit;

        /**
         * Montant déjà utilisé ce mois (en FCFA)
         */
        private Long used;

        /**
         * Montant restant ce mois (en FCFA), -1 si illimité
         */
        private Long remaining;

        /**
         * Pourcentage utilisé (0-100), null si illimité
         */
        private Double percentageUsed;

        /**
         * Indique si les transactions sont illimitées
         */
        private Boolean unlimited;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CorridorLimitInfo {
        /**
         * Code du corridor (ex: SN-SN, SN-CI)
         */
        private String corridorCode;

        /**
         * Pays source
         */
        private String sourceCountry;

        /**
         * Pays destination
         */
        private String destinationCountry;

        /**
         * Montant maximum par transaction pour ce corridor (en FCFA)
         */
        private Long maxPerTransaction;

        /**
         * Limite quotidienne pour ce corridor (en FCFA)
         */
        private Long dailyLimit;

        /**
         * Nombre maximum de transactions par jour pour ce corridor
         */
        private Integer maxTransactionsPerDay;

        /**
         * Passerelle préférée pour ce corridor
         */
        private String preferredGateway;

        /**
         * Fiabilité de la passerelle (0.0 - 1.0)
         */
        private Double gatewayReliability;

        /**
         * Indique si le corridor est actuellement actif
         */
        private Boolean enabled;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LimitModifiers {
        /**
         * Messages sur les conditions qui peuvent augmenter les limites
         */
        private List<String> upgradeConditions;

        /**
         * Messages sur les restrictions actuelles
         */
        private List<String> currentRestrictions;

        /**
         * Informations réglementaires
         */
        private String regulatoryInfo;
    }
}
