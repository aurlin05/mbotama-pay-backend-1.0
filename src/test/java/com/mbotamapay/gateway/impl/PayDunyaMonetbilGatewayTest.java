package com.mbotamapay.gateway.impl;

import com.mbotamapay.entity.enums.Country;
import com.mbotamapay.entity.enums.GatewayType;
import com.mbotamapay.entity.enums.MobileOperator;
import com.mbotamapay.gateway.GatewayCapabilityRegistry;
import com.mbotamapay.gateway.dto.PayoutRequest;
import com.mbotamapay.gateway.dto.PayoutResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@DisplayName("Passerelles PayDunya et Monetbil")
class PayDunyaMonetbilGatewayTest {

    private static GatewayCapabilityRegistry passThroughRegistry() {
        GatewayCapabilityRegistry registry = mock(GatewayCapabilityRegistry.class);
        when(registry.capabilities(any(GatewayType.class), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        return registry;
    }

    private static PayoutRequest payout(MobileOperator operator, String phone, Country country) {
        return PayoutRequest.builder()
                .reference("TRF-123").amount(4500L).currency(country.getCurrency())
                .recipientPhone(phone).country(country).operator(operator)
                .build();
    }

    @Nested
    @DisplayName("PayDunya Disburse v2")
    class PayDunya {

        private static final String API = "https://app.paydunya.com/api/v2/disburse";
        private final RestTemplate restTemplate = new RestTemplate();
        private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        private final PayDunyaGateway gateway = new PayDunyaGateway(restTemplate, passThroughRegistry());

        {
            ReflectionTestUtils.setField(gateway, "disburseUrl", API);
            ReflectionTestUtils.setField(gateway, "baseUrl", "https://backend.example");
            ReflectionTestUtils.setField(gateway, "masterKey", "mk");
            ReflectionTestUtils.setField(gateway, "privateKey", "pk");
            ReflectionTestUtils.setField(gateway, "token", "tk");
        }

        @Test
        @DisplayName("jeton puis soumission : numéro sans indicatif, callback, disburse_id")
        void disburse() {
            server.expect(requestTo(API + "/get-invoice"))
                    .andExpect(header("PAYDUNYA-MASTER-KEY", "mk"))
                    .andExpect(jsonPath("$.account_alias").value("771111111"))
                    .andExpect(jsonPath("$.withdraw_mode").value("orange-money-senegal"))
                    .andExpect(jsonPath("$.callback_url").value(
                            "https://backend.example/api/v1/payments/callback/paydunya"))
                    .andRespond(withSuccess("{\"response_code\":\"00\",\"disburse_token\":\"tok-1\"}",
                            MediaType.APPLICATION_JSON));
            server.expect(requestTo(API + "/submit-invoice"))
                    .andExpect(jsonPath("$.disburse_invoice").value("tok-1"))
                    .andExpect(jsonPath("$.disburse_id").value("TRF-123"))
                    .andRespond(withSuccess("{\"response_code\":\"00\",\"status\":\"pending\"}",
                            MediaType.APPLICATION_JSON));

            PayoutResponse response = gateway.initiatePayout(
                    payout(MobileOperator.ORANGE_SN, "+221771111111", Country.SENEGAL));

            server.verify();
            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getStatus()).isEqualTo("PENDING");
            assertThat(response.getExternalReference()).isEqualTo("tok-1");
        }

        @Test
        @DisplayName("soumission sans issue et jeton « created » : même jeton soumis à nouveau")
        void resubmitWhenCreated() {
            server.expect(requestTo(API + "/get-invoice"))
                    .andRespond(withSuccess("{\"response_code\":\"00\",\"disburse_token\":\"tok-2\"}",
                            MediaType.APPLICATION_JSON));
            server.expect(requestTo(API + "/submit-invoice"))
                    .andRespond(withSuccess("{\"response_code\":\"5000\",\"response_text\":\"Erreur\"}",
                            MediaType.APPLICATION_JSON));
            server.expect(requestTo(API + "/check-status"))
                    .andExpect(jsonPath("$.disburse_invoice").value("tok-2"))
                    .andRespond(withSuccess("{\"response_code\":\"00\",\"status\":\"created\"}",
                            MediaType.APPLICATION_JSON));
            server.expect(requestTo(API + "/submit-invoice"))
                    .andExpect(jsonPath("$.disburse_invoice").value("tok-2"))
                    .andRespond(withSuccess("{\"response_code\":\"00\"}", MediaType.APPLICATION_JSON));

            PayoutResponse response = gateway.initiatePayout(
                    payout(MobileOperator.MTN_CM, "237650000000", Country.CAMEROON));

            server.verify();
            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getStatus()).isEqualTo("COMPLETED");
        }

        @Test
        @DisplayName("Niger : plus desservi")
        void nigerDropped() {
            assertThat(gateway.capabilities().canPayoutTo(Country.NIGER)).isFalse();
            assertThat(gateway.initiatePayout(payout(MobileOperator.AIRTEL_NE, "22797000000", Country.NIGER))
                    .isSuccess()).isFalse();
            server.verify();
        }
    }

    @Nested
    @DisplayName("Monetbil")
    class Monetbil {

        private final RestTemplate restTemplate = new RestTemplate();
        private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        private final MonetbilGateway gateway = new MonetbilGateway(restTemplate, passThroughRegistry());

        {
            ReflectionTestUtils.setField(gateway, "apiUrl", "https://api.monetbil.com");
            ReflectionTestUtils.setField(gateway, "payoutPath", "/payment/v1/payouts/withdrawal");
            ReflectionTestUtils.setField(gateway, "serviceKey", "sk");
            ReflectionTestUtils.setField(gateway, "serviceSecret", "ss");
            ReflectionTestUtils.setField(gateway, "baseUrl", "https://backend.example");
        }

        @Test
        @DisplayName("versement : chemin withdrawal, numéro avec indicatif, code opérateur")
        void withdrawal() {
            server.expect(requestTo("https://api.monetbil.com/payment/v1/payouts/withdrawal"))
                    .andExpect(jsonPath("$.service_secret").value("ss"))
                    .andExpect(jsonPath("$.phonenumber").value("237654088375"))
                    .andExpect(jsonPath("$.operator").value("CM_MTNMOBILEMONEY"))
                    .andExpect(jsonPath("$.processing_number").value("TRF-123"))
                    .andRespond(withSuccess("{\"success\":true,\"transaction\":479181124592,"
                            + "\"message\":\"Payout successfully queued. You will receive notification\"}",
                            MediaType.APPLICATION_JSON));

            PayoutResponse response = gateway.initiatePayout(
                    payout(MobileOperator.MTN_CM, "654088375", Country.CAMEROON));

            server.verify();
            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getExternalReference()).isEqualTo("479181124592");
        }

        @Test
        @DisplayName("refus : message Monetbil remonté")
        void refused() {
            server.expect(requestTo("https://api.monetbil.com/payment/v1/payouts/withdrawal"))
                    .andRespond(withSuccess("{\"success\":false,\"message\":\"amount invalid\"}",
                            MediaType.APPLICATION_JSON));

            PayoutResponse response = gateway.initiatePayout(
                    payout(MobileOperator.MTN_CM, "237654088375", Country.CAMEROON));

            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getMessage()).isEqualTo("amount invalid");
        }
    }

    @Test
    @DisplayName("Bénin : un numéro à 10 chiffres (01 + ancien numéro) est reconnu")
    void beninTenDigitNumbering() {
        assertThat(MobileOperator.fromPhoneNumber("+2290197000000", Country.BENIN)).contains(MobileOperator.MTN_BJ);
        assertThat(MobileOperator.fromPhoneNumber("+22997000000", Country.BENIN)).contains(MobileOperator.MTN_BJ);
        assertThat(MobileOperator.fromPhoneNumber("+2290190000000", Country.BENIN)).contains(MobileOperator.CELTIIS_BJ);
    }
}
