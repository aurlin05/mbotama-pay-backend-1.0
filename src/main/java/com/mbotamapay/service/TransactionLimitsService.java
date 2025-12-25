package com.mbotamapay.service;

import com.mbotamapay.config.TransactionLimitsConfig;
import com.mbotamapay.dto.user.UserLimitsResponse;
import com.mbotamapay.entity.User;
import com.mbotamapay.entity.enums.Country;
import com.mbotamapay.entity.enums.KycLevel;
import com.mbotamapay.exception.BadRequestException;
import com.mbotamapay.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service de validation des limites de transaction
 * 
 * Applique les plafonds intelligents anti-requalification :
 * - Limites par transaction
 * - Limites quotidiennes selon KYC
 * - Limites par corridor
 * - Règles de mode double passerelle
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TransactionLimitsService {

    private final TransactionLimitsConfig limitsConfig;
    private final TransactionRepository transactionRepository;

    /**
     * Valider qu'un utilisateur peut effectuer une transaction
     * 
     * @throws BadRequestException si les limites sont dépassées
     */
    public void validateTransaction(User user, Long amount, Country sourceCountry, Country destCountry) {
        log.info("Validating transaction limits: userId={}, amount={}, corridor={}-{}", 
                user.getId(), amount, sourceCountry, destCountry);

        // 1. Vérifier le montant minimum et maximum
        validateTransactionAmount(amount);

        // 2. Vérifier le niveau KYC
        validateKycLevel(user);

        // 3. Vérifier les limites quotidiennes
        validateDailyLimits(user, amount);

        // 4. Vérifier les limites mensuelles (existantes)
        validateMonthlyLimits(user, amount);

        // 5. Vérifier les limites du corridor
        validateCorridorLimits(amount, sourceCountry, destCountry);

        log.info("Transaction validated successfully");
    }

    /**
     * Valider le montant de la transaction
     */
    private void validateTransactionAmount(Long amount) {
        if (!limitsConfig.isValidTransactionAmount(amount)) {
            throw new BadRequestException(String.format(
                    "Montant invalide. Minimum: %d FCFA, Maximum: %d FCFA",
                    limitsConfig.getTransaction().getMinimum(),
                    limitsConfig.getTransaction().getAbsoluteMaximum()
            ));
        }
    }

    /**
     * Valider le niveau KYC
     */
    private void validateKycLevel(User user) {
        if (user.getKycLevel() == null || user.getKycLevel() == KycLevel.NONE) {
            throw new BadRequestException(
                    "Vérification d'identité requise. Complétez votre KYC pour envoyer de l'argent."
            );
        }
    }

    /**
     * Valider les limites quotidiennes selon le niveau KYC
     */
    private void validateDailyLimits(User user, Long amount) {
        Long dailyLimit = limitsConfig.getDailyLimitForKycLevel(user.getKycLevel().name());

        if (dailyLimit == 0) {
            throw new BadRequestException(
                    "Votre niveau KYC ne vous permet pas d'effectuer des transactions. " +
                    "Veuillez compléter votre vérification d'identité."
            );
        }

        // Calculer le montant utilisé aujourd'hui
        LocalDateTime startOfDay = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);
        Long usedTodayAmount = transactionRepository.sumAmountBySenderIdAndStatusCompletedSince(
                user.getId(), startOfDay);
        
        if (usedTodayAmount == null) {
            usedTodayAmount = 0L;
        }

        if (usedTodayAmount + amount > dailyLimit) {
            throw new BadRequestException(String.format(
                    "Limite quotidienne dépassée. Limite: %,d FCFA, Utilisé aujourd'hui: %,d FCFA, Disponible: %,d FCFA",
                    dailyLimit, usedTodayAmount, dailyLimit - usedTodayAmount
            ));
        }

        log.info("Daily limit check passed: limit={}, used={}, requested={}", 
                dailyLimit, usedTodayAmount, amount);
    }

    /**
     * Valider les limites mensuelles (existantes)
     */
    private void validateMonthlyLimits(User user, Long amount) {
        long monthlyLimit = user.getTransactionLimit();

        // Si limite illimitée (LEVEL_2), passer
        if (monthlyLimit == Long.MAX_VALUE) {
            return;
        }

        LocalDateTime thirtyDaysAgo = LocalDateTime.now().minusDays(30);
        Long usedAmount = transactionRepository.sumAmountBySenderIdAndStatusCompletedSince(
                user.getId(), thirtyDaysAgo);
        
        if (usedAmount == null) {
            usedAmount = 0L;
        }

        if (usedAmount + amount > monthlyLimit) {
            throw new BadRequestException(String.format(
                    "Limite mensuelle dépassée. Limite: %,d FCFA, Utilisé (30 derniers jours): %,d FCFA, Disponible: %,d FCFA",
                    monthlyLimit, usedAmount, monthlyLimit - usedAmount
            ));
        }

        log.info("Monthly limit check passed: limit={}, used={}, requested={}", 
                monthlyLimit, usedAmount, amount);
    }

    /**
     * Valider les limites du corridor
     */
    private void validateCorridorLimits(Long amount, Country sourceCountry, Country destCountry) {
        TransactionLimitsConfig.CorridorLimit corridorLimit = limitsConfig.getCorridorLimit(
                sourceCountry.name(), destCountry.name());

        // Vérifier si le corridor est actif
        if (!corridorLimit.getEnabled()) {
            throw new BadRequestException(
                    "Ce corridor de paiement est temporairement indisponible. " +
                    "Veuillez réessayer ultérieurement."
            );
        }

        // Vérifier le montant maximum par transaction pour ce corridor
        if (amount > corridorLimit.getMaxPerTransaction()) {
            throw new BadRequestException(String.format(
                    "Montant maximum pour ce corridor: %,d FCFA. Veuillez réduire le montant.",
                    corridorLimit.getMaxPerTransaction()
            ));
        }

        // Vérifier la limite quotidienne du corridor
        LocalDateTime startOfDay = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);
        
        // TODO: Ajouter une méthode dans le repository pour compter par corridor
        // Pour l'instant, on log juste l'information
        log.info("Corridor limit check: corridor={}-{}, dailyLimit={}, maxPerTx={}", 
                sourceCountry, destCountry, 
                corridorLimit.getDailyLimit(), 
                corridorLimit.getMaxPerTransaction());
    }

    /**
     * Vérifier si le mode double passerelle peut être utilisé
     */
    public boolean canUseDualGateway(Long amount, Long technicalBalance, Long latencyMs, Integer recentErrors) {
        boolean canUse = limitsConfig.canUseDualGateway(amount, technicalBalance, latencyMs, recentErrors);
        
        log.info("Dual gateway check: amount={}, balance={}, latency={}ms, errors={}, result={}", 
                amount, technicalBalance, latencyMs, recentErrors, canUse);
        
        return canUse;
    }

    /**
     * Obtenir les limites pour un utilisateur donné
     */
    public UserLimitsInfo getUserLimits(User user) {
        Long dailyLimit = limitsConfig.getDailyLimitForKycLevel(user.getKycLevel().name());
        Long monthlyLimit = user.getTransactionLimit();

        // Calculer l'utilisation
        LocalDateTime startOfDay = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);
        LocalDateTime thirtyDaysAgo = LocalDateTime.now().minusDays(30);

        Long usedToday = transactionRepository.sumAmountBySenderIdAndStatusCompletedSince(
                user.getId(), startOfDay);
        Long usedThisMonth = transactionRepository.sumAmountBySenderIdAndStatusCompletedSince(
                user.getId(), thirtyDaysAgo);

        if (usedToday == null) usedToday = 0L;
        if (usedThisMonth == null) usedThisMonth = 0L;

        return UserLimitsInfo.builder()
                .kycLevel(user.getKycLevel().name())
                .kycLevelDisplayName(user.getKycLevel().getDisplayName())
                .transactionMinimum(limitsConfig.getTransaction().getMinimum())
                .transactionMaximum(limitsConfig.getTransaction().getAbsoluteMaximum())
                .dailyLimit(dailyLimit)
                .dailyUsed(usedToday)
                .dailyRemaining(Math.max(0, dailyLimit - usedToday))
                .monthlyLimit(monthlyLimit == Long.MAX_VALUE ? -1L : monthlyLimit)
                .monthlyUsed(usedThisMonth)
                .monthlyRemaining(monthlyLimit == Long.MAX_VALUE ? -1L : Math.max(0, monthlyLimit - usedThisMonth))
                .build();
    }

    /**
     * Obtenir les limites détaillées pour l'affichage dans le profil utilisateur
     */
    public UserLimitsResponse getDetailedUserLimits(User user) {
        Long dailyLimit = limitsConfig.getDailyLimitForKycLevel(user.getKycLevel().name());
        Long monthlyLimit = user.getTransactionLimit();

        // Calculer l'utilisation
        LocalDateTime startOfDay = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);
        LocalDateTime thirtyDaysAgo = LocalDateTime.now().minusDays(30);

        Long usedToday = transactionRepository.sumAmountBySenderIdAndStatusCompletedSince(
                user.getId(), startOfDay);
        Long usedThisMonth = transactionRepository.sumAmountBySenderIdAndStatusCompletedSince(
                user.getId(), thirtyDaysAgo);

        if (usedToday == null) usedToday = 0L;
        if (usedThisMonth == null) usedThisMonth = 0L;

        // Construire les limites de transaction
        UserLimitsResponse.TransactionLimits transactionLimits = UserLimitsResponse.TransactionLimits.builder()
                .minimum(limitsConfig.getTransaction().getMinimum())
                .maximumStandard(limitsConfig.getTransaction().getMaximumStandard())
                .absoluteMaximum(limitsConfig.getTransaction().getAbsoluteMaximum())
                .build();

        // Construire les limites quotidiennes
        Long dailyRemaining = Math.max(0, dailyLimit - usedToday);
        Double dailyPercentage = dailyLimit > 0 ? (usedToday.doubleValue() / dailyLimit.doubleValue() * 100) : 0.0;
        
        UserLimitsResponse.DailyLimits dailyLimits = UserLimitsResponse.DailyLimits.builder()
                .limit(dailyLimit)
                .used(usedToday)
                .remaining(dailyRemaining)
                .percentageUsed(dailyPercentage)
                .build();

        // Construire les limites mensuelles
        boolean isUnlimited = monthlyLimit == Long.MAX_VALUE;
        Long monthlyRemaining = isUnlimited ? -1L : Math.max(0, monthlyLimit - usedThisMonth);
        Double monthlyPercentage = !isUnlimited && monthlyLimit > 0 
                ? (usedThisMonth.doubleValue() / monthlyLimit.doubleValue() * 100) 
                : null;
        
        UserLimitsResponse.MonthlyLimits monthlyLimits = UserLimitsResponse.MonthlyLimits.builder()
                .limit(isUnlimited ? -1L : monthlyLimit)
                .used(usedThisMonth)
                .remaining(monthlyRemaining)
                .percentageUsed(monthlyPercentage)
                .unlimited(isUnlimited)
                .build();

        // Obtenir les limites par corridor
        List<UserLimitsResponse.CorridorLimitInfo> corridorLimits = limitsConfig.getCorridors().values().stream()
                .map(corridor -> UserLimitsResponse.CorridorLimitInfo.builder()
                        .corridorCode(corridor.getCode())
                        .sourceCountry(corridor.getCode().split("-")[0])
                        .destinationCountry(corridor.getCode().split("-")[1])
                        .maxPerTransaction(corridor.getMaxPerTransaction())
                        .dailyLimit(corridor.getDailyLimit())
                        .maxTransactionsPerDay(corridor.getMaxTransactionsPerDay())
                        .preferredGateway(corridor.getPreferredGateway())
                        .gatewayReliability(corridor.getGatewayReliability())
                        .enabled(corridor.getEnabled())
                        .build())
                .collect(Collectors.toList());

        // Construire les modificateurs de limites
        UserLimitsResponse.LimitModifiers modifiers = buildLimitModifiers(user);

        return UserLimitsResponse.builder()
                .kycLevel(user.getKycLevel().name())
                .kycLevelDisplayName(user.getKycLevel().getDisplayName())
                .kycDescription(user.getKycLevel().getDescription())
                .transactionLimits(transactionLimits)
                .dailyLimits(dailyLimits)
                .monthlyLimits(monthlyLimits)
                .corridorLimits(corridorLimits)
                .modifiers(modifiers)
                .build();
    }

    /**
     * Construire les informations sur les modificateurs de limites
     */
    private UserLimitsResponse.LimitModifiers buildLimitModifiers(User user) {
        List<String> upgradeConditions = new ArrayList<>();
        List<String> currentRestrictions = new ArrayList<>();

        // Ajouter les conditions d'upgrade selon le niveau KYC actuel
        switch (user.getKycLevel()) {
            case NONE -> {
                upgradeConditions.add("Complétez votre vérification d'identité (KYC Niveau 1) pour débloquer les transactions");
                upgradeConditions.add("Limite quotidienne de 300 000 FCFA disponible au Niveau 1");
                upgradeConditions.add("Limite quotidienne de 500 000 FCFA disponible au Niveau 2");
                currentRestrictions.add("Les transactions sont actuellement bloquées - KYC requis");
            }
            case LEVEL_1 -> {
                upgradeConditions.add("Passez au KYC Niveau 2 pour augmenter votre limite quotidienne à 500 000 FCFA");
                upgradeConditions.add("Limite mensuelle illimitée disponible au Niveau 2");
                currentRestrictions.add("Limite quotidienne: 300 000 FCFA");
                currentRestrictions.add("Limite mensuelle: 500 000 FCFA");
            }
            case LEVEL_2 -> {
                upgradeConditions.add("Vous avez le niveau KYC maximum avec les meilleures limites");
                currentRestrictions.add("Limite quotidienne: 500 000 FCFA");
            }
        }

        // Ajouter les informations communes
        currentRestrictions.add("Montant maximum par transaction: 200 000 FCFA (réglementation)");

        String regulatoryInfo = "Ces limites sont appliquées pour assurer la sécurité et la conformité réglementaire. " +
                "Elles peuvent être modifiées en fonction des exigences réglementaires, de la disponibilité technique " +
                "des partenaires et des conditions du marché.";

        return UserLimitsResponse.LimitModifiers.builder()
                .upgradeConditions(upgradeConditions)
                .currentRestrictions(currentRestrictions)
                .regulatoryInfo(regulatoryInfo)
                .build();
    }

    /**
     * DTO pour les informations de limites utilisateur
     */
    @lombok.Data
    @lombok.Builder
    public static class UserLimitsInfo {
        private String kycLevel;
        private String kycLevelDisplayName;
        private Long transactionMinimum;
        private Long transactionMaximum;
        private Long dailyLimit;
        private Long dailyUsed;
        private Long dailyRemaining;
        private Long monthlyLimit;      // -1 si illimité
        private Long monthlyUsed;
        private Long monthlyRemaining;  // -1 si illimité
    }
}
