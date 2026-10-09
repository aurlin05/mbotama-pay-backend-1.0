package com.mbotamapay.gateway.impl;

import com.mbotamapay.entity.enums.Country;
import com.mbotamapay.entity.enums.GatewayType;
import com.mbotamapay.entity.enums.MobileOperator;
import com.mbotamapay.gateway.GatewayCapabilities;
import com.mbotamapay.gateway.GatewayCapabilityRegistry;
import com.mbotamapay.gateway.dto.PayoutRequest;
import com.mbotamapay.gateway.dto.PayoutResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
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

@DisplayName("Passerelle FeexPay (API v2)")
class FeexPayGatewayTest {

    private static final String API = "https://api-v2.feexpay.me";
    private static final String ACCEPTED =
            "{\"reference\":\"fx-1\",\"status\":\"PENDING\",\"message\":\"Payout request accepted\"}";

    private MockRestServiceServer server;
    private FeexPayGateway gateway;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        GatewayCapabilityRegistry registry = mock(GatewayCapabilityRegistry.class);
        when(registry.capabilities(eq(GatewayType.FEEXPAY), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));

        gateway = new FeexPayGateway(restTemplate, registry);
        ReflectionTestUtils.setField(gateway, "apiUrl", API);
        ReflectionTestUtils.setField(gateway, "apiKey", "fp_key");
        ReflectionTestUtils.setField(gateway, "shopId", "Ayg9lkjkhurIvNp");
        ReflectionTestUtils.setField(gateway, "enabled", true);
    }

    private PayoutRequest payout(MobileOperator operator, String phone, Country country) {
        return PayoutRequest.builder()
                .reference("TRF-123").amount(5000L).currency(country.getCurrency())
                .recipientPhone(phone).country(country).operator(operator)
                .description("Loyer d'octobre — maman")
                .build();
    }

    @Test
    @DisplayName("Bénin MTN : /transfer/global avec network, numéro à 10 chiffres conservé")
    void beninGlobal() {
        server.expect(requestTo(API + "/api/payouts/public/transfer/global"))
                .andExpect(header("Authorization", "Bearer fp_key"))
                .andExpect(jsonPath("$.network").value("MTN"))
                .andExpect(jsonPath("$.phoneNumber").value("2290197000000"))
                .andExpect(jsonPath("$.shop").value("Ayg9lkjkhurIvNp"))
                .andExpect(jsonPath("$.motif").value("Loyer d octobre maman"))
                .andExpect(jsonPath("$.callback_info").value("TRF-123"))
                .andRespond(withSuccess(ACCEPTED, MediaType.APPLICATION_JSON));

        PayoutResponse response = gateway.initiatePayout(
                payout(MobileOperator.MTN_BJ, "+229 01 97 00 00 00", Country.BENIN));

        server.verify();
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getExternalReference()).isEqualTo("fx-1");
    }

    @Test
    @DisplayName("Togo : /togo avec network MOOV TG")
    void togo() {
        server.expect(requestTo(API + "/api/payouts/public/togo"))
                .andExpect(jsonPath("$.network").value("MOOV TG"))
                .andRespond(withSuccess(ACCEPTED, MediaType.APPLICATION_JSON));

        assertThat(gateway.initiatePayout(payout(MobileOperator.MOOV_TG, "22899000000", Country.TOGO))
                .isSuccess()).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("Sénégal Wave : chemin dédié, sans champ network")
    void senegal() {
        server.expect(requestTo(API + "/api/payouts/public/wave_sn"))
                .andExpect(jsonPath("$.network").doesNotExist())
                .andRespond(withSuccess(ACCEPTED, MediaType.APPLICATION_JSON));

        assertThat(gateway.initiatePayout(payout(MobileOperator.WAVE_SN, "221781234567", Country.SENEGAL))
                .isSuccess()).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("Orange Burkina : refusé sans appel (OTP client requis)")
    void orangeBurkinaRefused() {
        assertThat(gateway.initiatePayout(payout(MobileOperator.ORANGE_BF, "22607123456", Country.BURKINA_FASO))
                .isSuccess()).isFalse();
        server.verify();
    }

    @Test
    @DisplayName("erreur 5xx : issue indéterminée, pas de bascule")
    void serverErrorIsUndetermined() {
        server.expect(requestTo(API + "/api/payouts/public/mtn_ci"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> gateway.initiatePayout(
                payout(MobileOperator.MTN_CI, "2250507700000", Country.COTE_DIVOIRE)))
                .isInstanceOf(ResourceAccessException.class);
    }

    @Test
    @DisplayName("statut de versement : SUCCESSFUL → COMPLETED")
    void payoutStatus() {
        server.expect(requestTo(API + "/api/payouts/status/public/fx-1"))
                .andRespond(withSuccess("{\"reference\":\"fx-1\",\"status\":\"SUCCESSFUL\",\"amount\":5000}",
                        MediaType.APPLICATION_JSON));

        assertThat(gateway.checkPayoutStatus("fx-1").getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("la déclaration des opérateurs concorde avec les capacités")
    void operatorDeclarationsAgree() {
        GatewayCapabilities caps = gateway.capabilities();
        for (MobileOperator operator : MobileOperator.values()) {
            assertThat(caps.canReach(operator)).as(operator.name())
                    .isEqualTo(operator.supportsGateway(GatewayType.FEEXPAY));
        }
    }

    @Test
    @DisplayName("texte libre : accents et caractères spéciaux retirés, longueur bornée")
    void plainText() {
        assertThat(FeexPayGateway.plainText("Café & thé — été 2026 !!", "x", 30)).isEqualTo("Cafe the ete 2026");
        assertThat(FeexPayGateway.plainText(null, "Transfert MbotamaPay", 30)).isEqualTo("Transfert MbotamaPay");
        assertThat(FeexPayGateway.plainText("a".repeat(50), "x", 30)).hasSize(30);
    }
}
