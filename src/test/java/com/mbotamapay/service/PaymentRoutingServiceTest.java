package com.mbotamapay.service;

import com.mbotamapay.dto.routing.RoutingDecision;
import com.mbotamapay.entity.GatewayRoute;
import com.mbotamapay.entity.enums.Country;
import com.mbotamapay.entity.enums.GatewayType;
import com.mbotamapay.entity.enums.MobileOperator;
import com.mbotamapay.repository.GatewayRouteRepository;
import com.mbotamapay.repository.GatewayStockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires pour PaymentRoutingService
 */
@ExtendWith(MockitoExtension.class)
class PaymentRoutingServiceTest {

    @Mock
    private GatewayRouteRepository routeRepository;

    @Mock
    private GatewayStockRepository stockRepository;

    @Mock
    private FeeCalculator feeCalculator;

    @InjectMocks
    private PaymentRoutingService routingService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(routingService, "preferDirectRoute", true);
    }

    @Nested
    @DisplayName("Country Detection Tests")
    class CountryDetectionTests {

        @Test
        @DisplayName("Détecte le Sénégal à partir du numéro +221...")
        void shouldDetectSenegal() {
            Optional<Country> result = routingService.detectCountry("+221771234567");
            assertThat(result).isPresent().contains(Country.SENEGAL);
        }

        @Test
        @DisplayName("Détecte le Bénin à partir du numéro 229...")
        void shouldDetectBenin() {
            Optional<Country> result = routingService.detectCountry("22997123456");
            assertThat(result).isPresent().contains(Country.BENIN);
        }

        @Test
        @DisplayName("Détecte la Côte d'Ivoire à partir du numéro 00225...")
        void shouldDetectCoteIvoire() {
            Optional<Country> result = routingService.detectCountry("00225071234567");
            assertThat(result).isPresent().contains(Country.COTE_DIVOIRE);
        }

        @Test
        @DisplayName("Retourne empty pour numéro invalide")
        void shouldReturnEmptyForInvalidNumber() {
            Optional<Country> result = routingService.detectCountry("123");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Retourne empty pour null")
        void shouldReturnEmptyForNull() {
            Optional<Country> result = routingService.detectCountry(null);
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("Operator Detection Tests")
    class OperatorDetectionTests {

        @Test
        @DisplayName("Détecte MTN Bénin à partir du préfixe 97")
        void shouldDetectMtnBenin() {
            Optional<MobileOperator> result = routingService.detectOperator(
                    "22997123456", Country.BENIN);
            assertThat(result).isPresent().contains(MobileOperator.MTN_BJ);
        }

        @Test
        @DisplayName("Détecte Orange Sénégal à partir du préfixe 77")
        void shouldDetectOrangeSenegal() {
            Optional<MobileOperator> result = routingService.detectOperator(
                    "+221771234567", Country.SENEGAL);
            assertThat(result).isPresent().contains(MobileOperator.ORANGE_SN);
        }

        @Test
        @DisplayName("Détecte Wave Sénégal à partir du préfixe 78")
        void shouldDetectWaveSenegal() {
            Optional<MobileOperator> result = routingService.detectOperator(
                    "221781234567", Country.SENEGAL);
            assertThat(result).isPresent().contains(MobileOperator.WAVE_SN);
        }
    }

    @Nested
    @DisplayName("Route Determination Tests")
    class RouteDeterminationTests {

        @Test
        @DisplayName("Détermine la route SN→SN avec FeeXPay")
        void shouldDetermineLocalRoute() {
            // Given
            GatewayRoute route = GatewayRoute.builder()
                    .sourceCountry(Country.SENEGAL)
                    .destCountry(Country.SENEGAL)
                    .gateway(GatewayType.FEEXPAY)
                    .priority(1)
                    .gatewayFeePercent(new BigDecimal("2.70"))
                    .enabled(true)
                    .build();

            when(routeRepository.findActiveRoutes(Country.SENEGAL, Country.SENEGAL))
                    .thenReturn(new java.util.ArrayList<>(List.of(route)));
            when(feeCalculator.calculateFees(any(), any()))
                    .thenReturn(com.mbotamapay.dto.FeeBreakdown.builder()
                            .gatewayFee(2700L)
                            .appFee(2300L)
                            .totalFee(5000L)
                            .displayPercent(5)
                            .capped(false)
                            .build());

            // When
            RoutingDecision result = routingService.determineRoute(
                    "+221771234567", "+221781234567", 100_000L);

            // Then
            assertThat(result.isRouteFound()).isTrue();
            assertThat(result.getSourceCountry()).isEqualTo(Country.SENEGAL);
            assertThat(result.getDestCountry()).isEqualTo(Country.SENEGAL);
            assertThat(result.getCollectionGateway()).isEqualTo(GatewayType.FEEXPAY);
            assertThat(result.getFees().getDisplayPercent()).isEqualTo(5);
        }

        @Test
        @DisplayName("Retourne routeFound=false quand aucune route")
        void shouldReturnNotFoundWhenNoRoute() {
            // Given
            when(routeRepository.findActiveRoutes(Country.SENEGAL, Country.SENEGAL))
                    .thenReturn(List.of());

            // When
            RoutingDecision result = routingService.determineRoute(
                    "+221771234567", "+221781234567", 100_000L);

            // Then
            assertThat(result.isRouteFound()).isFalse();
            assertThat(result.getRoutingReason()).contains("Aucune route");
        }

        @Test
        @DisplayName("Retourne routeFound=false pour pays non détecté")
        void shouldReturnNotFoundForUnknownCountry() {
            // When
            RoutingDecision result = routingService.determineRoute(
                    "123", "+221781234567", 100_000L);

            // Then
            assertThat(result.isRouteFound()).isFalse();
            assertThat(result.getRoutingReason()).contains("non détecté");
        }

        @Test
        @DisplayName("Retourne routeFound=false pour SN→CG (aucune route configurée)")
        void shouldReturnNotFoundForSnToCg() {
            when(routeRepository.findActiveRoutes(Country.SENEGAL, Country.CONGO_BRAZZAVILLE))
                    .thenReturn(List.of());

            RoutingDecision result = routingService.determineRoute(
                    "+221775688191", "+242066031323", 100L);

            assertThat(result.isRouteFound()).isFalse();
            assertThat(result.getRoutingReason()).contains("Aucune route configurée");
        }
    }
}
