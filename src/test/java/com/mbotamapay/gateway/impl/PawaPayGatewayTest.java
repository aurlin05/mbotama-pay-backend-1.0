package com.mbotamapay.gateway.impl;

import com.mbotamapay.entity.enums.Country;
import com.mbotamapay.entity.enums.GatewayType;
import com.mbotamapay.entity.enums.MobileOperator;
import com.mbotamapay.gateway.GatewayCapabilities;
import com.mbotamapay.gateway.GatewayCapabilityRegistry;
import com.mbotamapay.gateway.dto.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@DisplayName("Passerelle pawaPay")
class PawaPayGatewayTest {

    private static final String API = "https://api.sandbox.pawapay.io";
    private static final String REF = "TRF-0b7c2f5e-3d1a-4c8e-9f60-2a1b3c4d5e6f";

    private MockRestServiceServer server;
    private PawaPayGateway gateway;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();

        GatewayCapabilityRegistry registry = mock(GatewayCapabilityRegistry.class);
        when(registry.capabilities(eq(GatewayType.PAWAPAY), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));

        gateway = new PawaPayGateway(restTemplate, registry);
        ReflectionTestUtils.setField(gateway, "apiUrl", API);
        ReflectionTestUtils.setField(gateway, "apiToken", "test-token");
        ReflectionTestUtils.setField(gateway, "enabled", true);
        ReflectionTestUtils.setField(gateway, "authUrlPollAttempts", 3);
        ReflectionTestUtils.setField(gateway, "authUrlPollIntervalMs", 0L);
    }

    @Nested
    @DisplayName("Identifiants et formats")
    class Formats {

        @Test
        @DisplayName("l'identifiant de paiement est un UUID v4 stable, distinct entre dépôt et versement")
        void paymentIdIsDeterministicV4() {
            String payout = PawaPayGateway.paymentId("payout", REF);
            String deposit = PawaPayGateway.paymentId("deposit", REF);

            assertThat(UUID.fromString(payout).version()).isEqualTo(4);
            assertThat(UUID.fromString(payout).variant()).isEqualTo(2);
            assertThat(PawaPayGateway.paymentId("payout", REF)).isEqualTo(payout);
            assertThat(deposit).isNotEqualTo(payout);
        }

        @Test
        @DisplayName("le MSISDN porte l'indicatif, sans + ni 00, zéro local conservé")
        void msisdn() {
            assertThat(PawaPayGateway.msisdn("+225 07 12 34 56 78", Country.COTE_DIVOIRE))
                    .isEqualTo("2250712345678");
            assertThat(PawaPayGateway.msisdn("00237650000000", Country.CAMEROON))
                    .isEqualTo("237650000000");
            assertThat(PawaPayGateway.msisdn("0612345678", Country.CONGO_BRAZZAVILLE))
                    .isEqualTo("2420612345678");
        }

        @Test
        @DisplayName("la déclaration des opérateurs concorde avec les capacités de la passerelle")
        void operatorDeclarationsAgree() {
            GatewayCapabilities caps = gateway.capabilities();
            for (MobileOperator operator : MobileOperator.values()) {
                assertThat(caps.canReach(operator))
                        .as(operator.name())
                        .isEqualTo(operator.supportsGateway(GatewayType.PAWAPAY));
            }
        }
    }

    @Nested
    @DisplayName("Versement")
    class Payout {

        private PayoutRequest request(MobileOperator operator, String phone, Country country) {
            return PayoutRequest.builder()
                    .reference(REF)
                    .amount(5000L)
                    .currency(country.getCurrency())
                    .recipientPhone(phone)
                    .recipientName("Awa")
                    .country(country)
                    .operator(operator)
                    .build();
        }

        @Test
        @DisplayName("ACCEPTED : versement en attente, corps conforme à l'API v2")
        void accepted() {
            String payoutId = PawaPayGateway.paymentId("payout", REF);
            server.expect(requestTo(API + "/v2/payouts"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(header("Authorization", "Bearer test-token"))
                    .andExpect(jsonPath("$.payoutId").value(payoutId))
                    .andExpect(jsonPath("$.amount").value("5000"))
                    .andExpect(jsonPath("$.currency").value("XAF"))
                    .andExpect(jsonPath("$.recipient.type").value("MMO"))
                    .andExpect(jsonPath("$.recipient.accountDetails.provider").value("MTN_MOMO_CMR"))
                    .andExpect(jsonPath("$.recipient.accountDetails.phoneNumber").value("237650000000"))
                    .andRespond(withSuccess("{\"payoutId\":\"" + payoutId + "\",\"status\":\"ACCEPTED\"}",
                            MediaType.APPLICATION_JSON));

            PayoutResponse response = gateway.initiatePayout(
                    request(MobileOperator.MTN_CM, "+237650000000", Country.CAMEROON));

            server.verify();
            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getStatus()).isEqualTo("PENDING");
            assertThat(response.getExternalReference()).isEqualTo(payoutId);
        }

        @Test
        @DisplayName("DUPLICATE_IGNORED : succès, pour ne pas basculer sur une autre passerelle")
        void duplicateIsSuccess() {
            server.expect(requestTo(API + "/v2/payouts"))
                    .andRespond(withSuccess("{\"status\":\"DUPLICATE_IGNORED\"}", MediaType.APPLICATION_JSON));

            PayoutResponse response = gateway.initiatePayout(
                    request(MobileOperator.ORANGE_SN, "221771234567", Country.SENEGAL));

            assertThat(response.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("REJECTED : échec portant le code pawaPay")
        void rejected() {
            server.expect(requestTo(API + "/v2/payouts"))
                    .andRespond(withSuccess("{\"status\":\"REJECTED\",\"failureReason\":{"
                            + "\"failureCode\":\"PAWAPAY_WALLET_OUT_OF_FUNDS\","
                            + "\"failureMessage\":\"Wallet empty\"}}", MediaType.APPLICATION_JSON));

            PayoutResponse response = gateway.initiatePayout(
                    request(MobileOperator.MTN_BJ, "22997000000", Country.BENIN));

            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getMessage()).startsWith("PAWAPAY_WALLET_OUT_OF_FUNDS");
        }

        @Test
        @DisplayName("opérateur non couvert : refus sans appel réseau")
        void unsupportedOperator() {
            PayoutResponse response = gateway.initiatePayout(
                    request(MobileOperator.MOOV_CI, "2250112345678", Country.COTE_DIVOIRE));

            server.verify();
            assertThat(response.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("erreur 5xx puis versement retrouvé : succès")
        void serverErrorButRecorded() {
            String payoutId = PawaPayGateway.paymentId("payout", REF);
            server.expect(requestTo(API + "/v2/payouts"))
                    .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
            server.expect(requestTo(API + "/v2/payouts/" + payoutId))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess("{\"status\":\"FOUND\",\"data\":{\"status\":\"PROCESSING\"}}",
                            MediaType.APPLICATION_JSON));

            PayoutResponse response = gateway.initiatePayout(
                    request(MobileOperator.AIRTEL_CD, "243970000000", Country.DRC));

            assertThat(response.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("erreur 5xx et statut illisible : issue indéterminée, remontée comme expiration")
        void serverErrorAndLookupFails() {
            String payoutId = PawaPayGateway.paymentId("payout", REF);
            server.expect(requestTo(API + "/v2/payouts"))
                    .andRespond(withStatus(HttpStatus.BAD_GATEWAY));
            server.expect(requestTo(API + "/v2/payouts/" + payoutId))
                    .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

            assertThatThrownBy(() -> gateway.initiatePayout(
                    request(MobileOperator.AIRTEL_CD, "243970000000", Country.DRC)))
                    .isInstanceOf(ResourceAccessException.class);
        }

        @Test
        @DisplayName("statut : COMPLETED, en cours, introuvable")
        void status() {
            String payoutId = PawaPayGateway.paymentId("payout", REF);
            server.expect(requestTo(API + "/v2/payouts/" + payoutId))
                    .andRespond(withSuccess("{\"status\":\"FOUND\",\"data\":{\"status\":\"COMPLETED\","
                            + "\"amount\":\"5000\",\"currency\":\"XOF\"}}", MediaType.APPLICATION_JSON));
            server.expect(requestTo(API + "/v2/payouts/" + payoutId))
                    .andRespond(withSuccess("{\"status\":\"FOUND\",\"data\":{\"status\":\"ENQUEUED\"}}",
                            MediaType.APPLICATION_JSON));
            server.expect(requestTo(API + "/v2/payouts/" + payoutId))
                    .andRespond(withSuccess("{\"status\":\"NOT_FOUND\"}", MediaType.APPLICATION_JSON));

            PayoutStatusResponse completed = gateway.checkPayoutStatus(REF);
            assertThat(completed.getStatus()).isEqualTo("COMPLETED");
            assertThat(completed.getAmount()).isEqualTo(5000L);

            assertThat(gateway.checkPayoutStatus(payoutId).getStatus()).isEqualTo("PENDING");
            assertThat(gateway.checkPayoutStatus(REF).isSuccess()).isFalse();
        }
    }

    @Nested
    @DisplayName("Encaissement")
    class Deposit {

        private PaymentInitRequest request(String senderPhone, String currency) {
            return PaymentInitRequest.builder()
                    .transactionReference(REF)
                    .amount(10000L)
                    .currency(currency)
                    .senderPhone(senderPhone)
                    .returnUrl("https://app/success")
                    .cancelUrl("https://app/failed")
                    .build();
        }

        @Test
        @DisplayName("opérateur détecté depuis le numéro, sans URL de redirection")
        void providerAuth() {
            server.expect(requestTo(API + "/v2/deposits"))
                    .andExpect(jsonPath("$.payer.accountDetails.provider").value("ORANGE_CMR"))
                    .andExpect(jsonPath("$.payer.accountDetails.phoneNumber").value("237690000000"))
                    .andExpect(jsonPath("$.clientReferenceId").value(REF))
                    .andExpect(jsonPath("$.successfulUrl").doesNotExist())
                    .andRespond(withSuccess("{\"status\":\"ACCEPTED\",\"nextStep\":\"FINAL_STATUS\"}",
                            MediaType.APPLICATION_JSON));

            PaymentInitResponse response = gateway.initiatePayment(request("+237690000000", "XAF"));

            server.verify();
            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getPaymentUrl()).isNull();
        }

        @Test
        @DisplayName("Wave : l'URL d'autorisation est récupérée par relecture du statut")
        void waveRedirect() {
            String depositId = PawaPayGateway.paymentId("deposit", REF);
            server.expect(requestTo(API + "/v2/deposits"))
                    .andExpect(jsonPath("$.payer.accountDetails.provider").value("WAVE_SEN"))
                    .andExpect(jsonPath("$.successfulUrl").value("https://app/success"))
                    .andRespond(withSuccess("{\"status\":\"ACCEPTED\",\"nextStep\":\"GET_AUTH_URL\"}",
                            MediaType.APPLICATION_JSON));
            server.expect(requestTo(API + "/v2/deposits/" + depositId))
                    .andRespond(withSuccess("{\"status\":\"FOUND\",\"data\":{\"status\":\"ACCEPTED\"}}",
                            MediaType.APPLICATION_JSON));
            server.expect(requestTo(API + "/v2/deposits/" + depositId))
                    .andRespond(withSuccess("{\"status\":\"FOUND\",\"data\":{\"status\":\"ACCEPTED\","
                            + "\"authorizationUrl\":\"https://pay.wave.com/x\"}}", MediaType.APPLICATION_JSON));

            PaymentInitResponse response = gateway.initiatePayment(request("221781234567", "XOF"));

            server.verify();
            assertThat(response.getPaymentUrl()).isEqualTo("https://pay.wave.com/x");
        }

        @Test
        @DisplayName("Orange Burkina : refusé d'emblée, la préautorisation n'est pas recueillie")
        void preauthRefused() {
            PaymentInitResponse response = gateway.initiatePayment(request("+22607123456", "XOF"));

            server.verify();
            assertThat(response.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("statut du dépôt relu depuis notre référence")
        void status() {
            String depositId = PawaPayGateway.paymentId("deposit", REF);
            server.expect(requestTo(API + "/v2/deposits/" + depositId))
                    .andRespond(withSuccess("{\"status\":\"FOUND\",\"data\":{\"status\":\"FAILED\","
                            + "\"failureReason\":{\"failureCode\":\"PAYER_NOT_FOUND\"}}}",
                            MediaType.APPLICATION_JSON));

            PaymentStatusResponse status = gateway.checkStatus(REF);

            assertThat(status.getStatus()).isEqualTo("FAILED");
            assertThat(status.getMessage()).isEqualTo("PAYER_NOT_FOUND");
        }
    }

    @Test
    @DisplayName("inerte sans jeton d'API")
    void notOperationalWithoutToken() {
        ReflectionTestUtils.setField(gateway, "apiToken", "");
        assertThat(gateway.isOperational()).isFalse();
    }
}
