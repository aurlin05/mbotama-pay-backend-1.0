package com.mbotamapay.gateway.impl;

import com.mbotamapay.entity.enums.Country;
import com.mbotamapay.entity.enums.GatewayType;
import com.mbotamapay.entity.enums.MobileOperator;
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
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@DisplayName("Passerelle CinetPay (API v1)")
class CinetPayGatewayTest {

    private static final String API = "https://api.cinetpay.net";
    private static final String REF = "TRF-0b7c2f5e-3d1a-4c8e-9f60-2a1b3c4d5e6f";
    private static final String LOGIN_OK =
            "{\"code\":200,\"status\":\"OK\",\"access_token\":\"jwt-ci\",\"expires_in\":86400}";

    private MockRestServiceServer server;
    private CinetPayGateway gateway;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();

        GatewayCapabilityRegistry registry = mock(GatewayCapabilityRegistry.class);
        when(registry.capabilities(eq(GatewayType.CINETPAY), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));

        gateway = new CinetPayGateway(restTemplate, registry);
        ReflectionTestUtils.setField(gateway, "apiUrl", API);
        ReflectionTestUtils.setField(gateway, "credentialsRaw", "CI:sk_test_ci:pwd-ci");
        ReflectionTestUtils.setField(gateway, "enabled", true);
        ReflectionTestUtils.setField(gateway, "baseUrl", "https://backend.example");
        ReflectionTestUtils.setField(gateway, "defaultClientEmail", "client@example.com");
        gateway.init();
    }

    private PayoutRequest payout(MobileOperator operator, Country country) {
        return PayoutRequest.builder()
                .reference(REF)
                .amount(5000L)
                .currency(country.getCurrency())
                .recipientPhone("+2250707000001")
                .recipientName("Awa")
                .country(country)
                .operator(operator)
                .build();
    }

    private void expectLogin() {
        server.expect(requestTo(API + "/v1/oauth/login"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.api_key").value("sk_test_ci"))
                .andExpect(jsonPath("$.api_password").value("pwd-ci"))
                .andRespond(withSuccess(LOGIN_OK, MediaType.APPLICATION_JSON));
    }

    @Nested
    @DisplayName("Formats")
    class Formats {

        @Test
        @DisplayName("référence courte conservée, longue condensée à 30 caractères de façon stable")
        void merchantId() {
            assertThat(CinetPayGateway.merchantId("MBP-1A2B3C4D")).isEqualTo("MBP-1A2B3C4D");
            String condensed = CinetPayGateway.merchantId(REF);
            assertThat(condensed).hasSize(30).startsWith("H");
            assertThat(CinetPayGateway.merchantId(REF)).isEqualTo(condensed);
        }

        @Test
        @DisplayName("identifiants par pays ; entrée mal formée ou pays inconnu ignorés")
        void credentials() {
            var parsed = CinetPayGateway.parseCredentials("CI:k1:p1, SN:k2:p:2,XX:k:p,ML:onlykey");
            assertThat(parsed).containsOnlyKeys(Country.COTE_DIVOIRE, Country.SENEGAL);
            assertThat(parsed.get(Country.SENEGAL).apiPassword()).isEqualTo("p:2");
        }

        @Test
        @DisplayName("inactive sans identifiants")
        void notOperationalWithoutCredentials() {
            ReflectionTestUtils.setField(gateway, "credentialsRaw", "");
            gateway.init();
            assertThat(gateway.isOperational()).isFalse();
        }
    }

    @Nested
    @DisplayName("Transfert")
    class Transfer {

        @Test
        @DisplayName("connexion puis transfert : jeton, numéro international, code opérateur")
        void transfer() {
            expectLogin();
            server.expect(requestTo(API + "/v1/transfer"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(header("Authorization", "Bearer jwt-ci"))
                    .andExpect(jsonPath("$.merchant_transaction_id").value(CinetPayGateway.merchantId(REF)))
                    .andExpect(jsonPath("$.phone_number").value("+2250707000001"))
                    .andExpect(jsonPath("$.payment_method").value("OM_CI"))
                    .andExpect(jsonPath("$.amount").value(5000))
                    .andExpect(jsonPath("$.notify_url").value(
                            "https://backend.example/api/v1/payments/callback/cinetpay"))
                    .andRespond(withSuccess("{\"code\":2002,\"status\":\"PENDING\",\"transaction_id\":\"tx-1\"}",
                            MediaType.APPLICATION_JSON));

            PayoutResponse response = gateway.initiatePayout(payout(MobileOperator.ORANGE_CI, Country.COTE_DIVOIRE));

            server.verify();
            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getStatus()).isEqualTo("PENDING");
            assertThat(response.getExternalReference()).isEqualTo("tx-1");
        }

        @Test
        @DisplayName("le jeton est réutilisé d'un appel à l'autre")
        void tokenCached() {
            expectLogin();
            server.expect(ExpectedCount.twice(), requestTo(API + "/v1/transfer"))
                    .andRespond(withSuccess("{\"code\":2002,\"status\":\"PENDING\"}", MediaType.APPLICATION_JSON));

            gateway.initiatePayout(payout(MobileOperator.ORANGE_CI, Country.COTE_DIVOIRE));
            gateway.initiatePayout(payout(MobileOperator.ORANGE_CI, Country.COTE_DIVOIRE));

            server.verify();
        }

        @Test
        @DisplayName("jeton expiré : nouvelle connexion et un seul nouvel essai")
        void expiredTokenRetried() {
            expectLogin();
            server.expect(requestTo(API + "/v1/transfer"))
                    .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                            .body("{\"code\":1003,\"status\":\"EXPIRED_TOKEN\"}"));
            expectLogin();
            server.expect(requestTo(API + "/v1/transfer"))
                    .andRespond(withSuccess("{\"code\":2002,\"status\":\"PENDING\"}", MediaType.APPLICATION_JSON));

            assertThat(gateway.initiatePayout(payout(MobileOperator.ORANGE_CI, Country.COTE_DIVOIRE))
                    .isSuccess()).isTrue();
            server.verify();
        }

        @Test
        @DisplayName("TRANSACTION_EXIST : succès, pour ne pas payer une seconde fois ailleurs")
        void duplicateIsSuccess() {
            expectLogin();
            server.expect(requestTo(API + "/v1/transfer"))
                    .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                            .body("{\"code\":1200,\"status\":\"TRANSACTION_EXIST\",\"description\":\"dup\"}"));

            assertThat(gateway.initiatePayout(payout(MobileOperator.ORANGE_CI, Country.COTE_DIVOIRE))
                    .isSuccess()).isTrue();
        }

        @Test
        @DisplayName("solde insuffisant : échec avec le motif CinetPay")
        void insufficientBalance() {
            expectLogin();
            server.expect(requestTo(API + "/v1/transfer"))
                    .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                            .body("{\"code\":2005,\"status\":\"INSUFFICIENT_BALANCE\",\"description\":\"Solde\"}"));

            PayoutResponse response = gateway.initiatePayout(payout(MobileOperator.ORANGE_CI, Country.COTE_DIVOIRE));

            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getMessage()).isEqualTo("INSUFFICIENT_BALANCE : Solde");
        }

        @Test
        @DisplayName("pays sans identifiants : refus sans appel réseau")
        void countryWithoutCredentials() {
            PayoutResponse response = gateway.initiatePayout(payout(MobileOperator.ORANGE_SN, Country.SENEGAL));

            server.verify();
            assertThat(response.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("délai dépassé : remonté au moteur, qui ne bascule pas")
        void timeoutPropagates() {
            expectLogin();
            server.expect(requestTo(API + "/v1/transfer"))
                    .andRespond(request -> {
                        throw new java.net.SocketTimeoutException("read timed out");
                    });

            assertThatThrownBy(() -> gateway.initiatePayout(payout(MobileOperator.ORANGE_CI, Country.COTE_DIVOIRE)))
                    .isInstanceOf(ResourceAccessException.class);
        }

        @Test
        @DisplayName("statut du transfert relu par la référence marchande")
        void status() {
            expectLogin();
            server.expect(requestTo(API + "/v1/transfer/" + CinetPayGateway.merchantId(REF)))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess("{\"code\":100,\"status\":\"SUCCESS\",\"amount\":\"5000\"}",
                            MediaType.APPLICATION_JSON));

            PayoutStatusResponse status = gateway.checkPayoutStatus(REF);

            assertThat(status.getStatus()).isEqualTo("COMPLETED");
            assertThat(status.getAmount()).isEqualTo(5000L);
        }
    }

    @Nested
    @DisplayName("Encaissement")
    class Payment {

        @Test
        @DisplayName("initiation : champs exigés par l'API v1, lien de paiement renvoyé")
        void initiate() {
            expectLogin();
            server.expect(requestTo(API + "/v1/payment"))
                    .andExpect(jsonPath("$.merchant_transaction_id").value("MBP-1A2B3C4D"))
                    .andExpect(jsonPath("$.client_email").value("client@example.com"))
                    .andExpect(jsonPath("$.client_first_name").value("Awa"))
                    .andExpect(jsonPath("$.client_last_name").value("Diallo"))
                    .andExpect(jsonPath("$.client_phone_number").value("+2250707000000"))
                    .andExpect(jsonPath("$.channel").value("PUSH"))
                    .andRespond(withSuccess("{\"code\":200,\"status\":\"OK\",\"transaction_id\":\"tx-9\","
                            + "\"payment_url\":\"https://secure.sandbox.cinetpay.net/payment/abc\"}",
                            MediaType.APPLICATION_JSON));

            PaymentInitResponse response = gateway.initiatePayment(PaymentInitRequest.builder()
                    .transactionReference("MBP-1A2B3C4D")
                    .amount(10000L)
                    .currency("XOF")
                    .senderPhone("+2250707000000")
                    .senderName("Awa Diallo")
                    .returnUrl("https://app/success")
                    .cancelUrl("https://app/failed")
                    .callbackUrl("https://backend.example/api/v1/payments/callback/cinetpay")
                    .build());

            server.verify();
            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getPaymentUrl()).isEqualTo("https://secure.sandbox.cinetpay.net/payment/abc");
        }

        @Test
        @DisplayName("statut : EXPIRED est un échec, INITIATED est en attente")
        void statusMapping() {
            expectLogin();
            server.expect(requestTo(API + "/v1/payment/MBP-1A2B3C4D"))
                    .andRespond(withSuccess("{\"code\":2003,\"status\":\"EXPIRED\"}", MediaType.APPLICATION_JSON));
            server.expect(requestTo(API + "/v1/payment/MBP-1A2B3C4D"))
                    .andRespond(withSuccess("{\"code\":2001,\"status\":\"INITIATED\"}", MediaType.APPLICATION_JSON));

            assertThat(gateway.checkStatus("MBP-1A2B3C4D").getStatus()).isEqualTo("FAILED");
            assertThat(gateway.checkStatus("MBP-1A2B3C4D").getStatus()).isEqualTo("PENDING");
        }
    }
}
