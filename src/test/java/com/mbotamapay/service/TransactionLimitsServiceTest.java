package com.mbotamapay.service;

import com.mbotamapay.config.TransactionLimitsConfig;
import com.mbotamapay.entity.User;
import com.mbotamapay.entity.enums.Country;
import com.mbotamapay.entity.enums.KycLevel;
import com.mbotamapay.exception.BadRequestException;
import com.mbotamapay.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Tests pour le service de validation des limites de transaction
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Tests des Limites de Transaction")
class TransactionLimitsServiceTest {

    /**
     * Configuration réelle, et non simulacre.
     *
     * <p>
     * Ces onze tests échouaient tous : {@code thenCallRealMethod()} était appliqué
     * à un mock dont les champs internes étaient nuls, ce qui produisait une
     * {@code NullPointerException} au premier contrôle de montant. Le composant
     * chargé d'appliquer les plafonds réglementaires n'avait donc aucune
     * couverture effective.
     *
     * <p>
     * Mockito signalait par ailleurs le stub {@code getCorridorLimit("SN", "SN")}
     * comme inutilisé : le service interrogeait en réalité
     * {@code "SENEGAL-SENEGAL"}, si bien qu'aucun corridor configuré n'était
     * jamais trouvé en production. L'échec du test décrivait un vrai défaut, c'est
     * le code qui a été corrigé.
     */
    private TransactionLimitsConfig limitsConfig;

    @Mock
    private TransactionRepository transactionRepository;

    private TransactionLimitsService limitsService;

    private User testUser;
    private TransactionLimitsConfig.TransactionLimits transactionLimits;
    private TransactionLimitsConfig.CorridorLimit corridorLimit;

    @BeforeEach
    void setUp() {
        limitsConfig = new TransactionLimitsConfig();

        // Configuration des limites de transaction
        transactionLimits = new TransactionLimitsConfig.TransactionLimits();
        transactionLimits.setMinimum(500L);
        transactionLimits.setMaximumStandard(100_000L);
        transactionLimits.setAbsoluteMaximum(200_000L);
        limitsConfig.setTransaction(transactionLimits);

        // Plafonds quotidiens par niveau KYC
        TransactionLimitsConfig.DailyLimits daily = new TransactionLimitsConfig.DailyLimits();
        daily.setLevel0(0L);
        daily.setLevel1(300_000L);
        daily.setLevel2(500_000L);
        limitsConfig.setDaily(daily);

        // Configuration du corridor SN-SN, indexé par code ISO
        corridorLimit = new TransactionLimitsConfig.CorridorLimit();
        corridorLimit.setCode("SN-SN");
        corridorLimit.setDailyLimit(2_000_000L);
        corridorLimit.setMaxPerTransaction(200_000L);
        corridorLimit.setMaxTransactionsPerDay(100);
        corridorLimit.setEnabled(true);
        limitsConfig.getCorridors().put("SN-SN", corridorLimit);

        limitsService = new TransactionLimitsService(limitsConfig, transactionRepository);

        // Utilisateur de test KYC Niveau 1
        testUser = User.builder()
                .id(1L)
                .phoneNumber("+221770000001")
                .firstName("Amadou")
                .lastName("Diallo")
                .kycLevel(KycLevel.LEVEL_1)
                .build();
    }

    @Test
    @DisplayName("Devrait rejeter une transaction si KYC est NONE")
    void shouldRejectTransactionWhenKycIsNone() {
        // Given
        testUser.setKycLevel(KycLevel.NONE);

        // When & Then
        assertThatThrownBy(() -> limitsService.validateTransaction(
                testUser, 10_000L, Country.SENEGAL, Country.SENEGAL))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Vérification d'identité requise");
    }

    @Test
    @DisplayName("Devrait rejeter une transaction en dessous du minimum")
    void shouldRejectTransactionBelowMinimum() {
        // Given
        Long amount = 400L; // En dessous de 500 FCFA

        // When & Then
        assertThatThrownBy(() -> limitsService.validateTransaction(
                testUser, amount, Country.SENEGAL, Country.SENEGAL))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Montant invalide");
    }

    @Test
    @DisplayName("Devrait rejeter une transaction au-dessus du maximum absolu")
    void shouldRejectTransactionAboveAbsoluteMaximum() {
        // Given
        Long amount = 250_000L; // Au-dessus de 200k FCFA

        // When & Then
        assertThatThrownBy(() -> limitsService.validateTransaction(
                testUser, amount, Country.SENEGAL, Country.SENEGAL))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Montant invalide");
    }

    @Test
    @DisplayName("Devrait rejeter une transaction dépassant la limite quotidienne")
    void shouldRejectTransactionExceedingDailyLimit() {
        // Given
        Long dailyLimit = 300_000L;
        Long alreadyUsed = 250_000L;
        Long newAmount = 100_000L; // 250k + 100k = 350k > 300k

        when(transactionRepository.sumAmountBySenderIdAndStatusCompletedSince(
                eq(1L), any(LocalDateTime.class)))
                .thenReturn(alreadyUsed);

        // When & Then
        assertThatThrownBy(() -> limitsService.validateTransaction(
                testUser, newAmount, Country.SENEGAL, Country.SENEGAL))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Limite quotidienne dépassée");
    }

    @Test
    @DisplayName("Devrait accepter une transaction valide pour KYC Niveau 1")
    void shouldAcceptValidTransactionForKycLevel1() {
        // Given
        Long amount = 50_000L;
        Long dailyLimit = 300_000L;
        Long monthlyLimit = 500_000L;

        when(transactionRepository.sumAmountBySenderIdAndStatusCompletedSince(
                eq(1L), any(LocalDateTime.class)))
                .thenReturn(0L); // Aucune transaction aujourd'hui

        // When & Then - Ne devrait pas lever d'exception
        limitsService.validateTransaction(testUser, amount, Country.SENEGAL, Country.SENEGAL);
    }

    @Test
    @DisplayName("Devrait accepter une transaction à la limite exacte")
    void shouldAcceptTransactionAtExactLimit() {
        // Given
        Long dailyLimit = 300_000L;
        Long alreadyUsed = 250_000L;
        Long newAmount = 50_000L; // Exactement à la limite

        when(transactionRepository.sumAmountBySenderIdAndStatusCompletedSince(
                eq(1L), any(LocalDateTime.class)))
                .thenReturn(alreadyUsed);

        // When & Then - Ne devrait pas lever d'exception
        limitsService.validateTransaction(testUser, newAmount, Country.SENEGAL, Country.SENEGAL);
    }

    @Test
    @DisplayName("Devrait rejeter si corridor désactivé")
    void shouldRejectWhenCorridorDisabled() {
        // Given
        corridorLimit.setEnabled(false);
        when(transactionRepository.sumAmountBySenderIdAndStatusCompletedSince(
                eq(1L), any(LocalDateTime.class)))
                .thenReturn(0L);

        // When & Then
        assertThatThrownBy(() -> limitsService.validateTransaction(
                testUser, 50_000L, Country.SENEGAL, Country.SENEGAL))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("corridor de paiement est temporairement indisponible");
    }

    @Test
    @DisplayName("Devrait rejeter si montant dépasse la limite du corridor")
    void shouldRejectWhenAmountExceedsCorridorLimit() {
        // Given — le plafond du corridor doit être STRICTEMENT sous le maximum
        // absolu, sinon le contrôle de montant rejette avant d'atteindre le
        // contrôle de corridor et le test valide la mauvaise règle.
        corridorLimit.setMaxPerTransaction(100_000L);
        Long amount = 150_000L; // sous les 200k absolus, au-dessus des 100k du corridor
        when(transactionRepository.sumAmountBySenderIdAndStatusCompletedSince(
                eq(1L), any(LocalDateTime.class)))
                .thenReturn(0L);

        // When & Then
        assertThatThrownBy(() -> limitsService.validateTransaction(
                testUser, amount, Country.SENEGAL, Country.SENEGAL))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Montant maximum pour ce corridor");
    }

    @Test
    @DisplayName("Devrait retourner les limites détaillées pour un utilisateur")
    void shouldReturnDetailedLimitsForUser() {
        // Given
        when(transactionRepository.sumAmountBySenderIdAndStatusCompletedSince(
                eq(1L), any(LocalDateTime.class)))
                .thenReturn(0L, 0L); // Pour quotidien et mensuel

        // When
        var userLimits = limitsService.getUserLimits(testUser);

        // Then
        assertThat(userLimits).isNotNull();
        assertThat(userLimits.getKycLevel()).isEqualTo("LEVEL_1");
        assertThat(userLimits.getDailyLimit()).isEqualTo(300_000L);
        assertThat(userLimits.getDailyUsed()).isEqualTo(0L);
        assertThat(userLimits.getDailyRemaining()).isEqualTo(300_000L);
        assertThat(userLimits.getTransactionMinimum()).isEqualTo(500L);
        assertThat(userLimits.getTransactionMaximum()).isEqualTo(200_000L);
    }

    @Test
    @DisplayName("Devrait calculer correctement le montant restant quotidien")
    void shouldCalculateDailyRemainingCorrectly() {
        // Given
        Long dailyLimit = 300_000L;
        Long used = 150_000L;

        when(transactionRepository.sumAmountBySenderIdAndStatusCompletedSince(
                eq(1L), any(LocalDateTime.class)))
                .thenReturn(used, 0L);

        // When
        var userLimits = limitsService.getUserLimits(testUser);

        // Then
        assertThat(userLimits.getDailyRemaining()).isEqualTo(150_000L);
        assertThat(userLimits.getDailyUsed()).isEqualTo(150_000L);
    }

    @Test
    @DisplayName("KYC Niveau 2 devrait avoir des limites illimitées pour le mensuel")
    void shouldHaveUnlimitedMonthlyLimitForKycLevel2() {
        // Given
        testUser.setKycLevel(KycLevel.LEVEL_2);
        when(transactionRepository.sumAmountBySenderIdAndStatusCompletedSince(
                eq(1L), any(LocalDateTime.class)))
                .thenReturn(0L, 0L);

        // When
        var userLimits = limitsService.getUserLimits(testUser);

        // Then
        assertThat(userLimits.getMonthlyLimit()).isEqualTo(-1L); // -1 = illimité
        assertThat(userLimits.getMonthlyRemaining()).isEqualTo(-1L);
    }
}
