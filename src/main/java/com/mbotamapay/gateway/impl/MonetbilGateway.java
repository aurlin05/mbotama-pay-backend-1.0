package com.mbotamapay.gateway.impl;

import com.mbotamapay.config.GatewayHttpConfig;
import com.mbotamapay.entity.enums.Country;
import com.mbotamapay.entity.enums.GatewayType;
import com.mbotamapay.entity.enums.MobileOperator;
import com.mbotamapay.gateway.GatewayCapabilities;
import com.mbotamapay.gateway.PaymentGateway;
import com.mbotamapay.gateway.PayoutGateway;
import com.mbotamapay.gateway.dto.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Intégration Monetbil — agrégateur camerounais (zone CEMAC, XAF).
 *
 * <p>
 * Apport au réseau : le Cameroun ne disposait que d'un seul agrégateur. C'est
 * aussi le premier partenaire XAF autre que FeexPay, ce qui ouvre la
 * perspective d'un second chemin vers le Congo-Brazzaville — aujourd'hui
 * desservi par une seule passerelle et injoignable depuis le Sénégal autrement
 * que par pont.
 *
 * <p>
 * <strong>À valider avant activation.</strong> Comme pour PayDunya, chemins et
 * noms de champs sont externalisés en configuration et la passerelle reste
 * inerte tant que les identifiants ne sont pas fournis.
 */
@Component
@Slf4j
public class MonetbilGateway implements PaymentGateway, PayoutGateway {

    private static final String PLATFORM_NAME = "monetbil";

    /**
     * Couverture relevée dans la table opérateurs de « Monetbil Payment API v1 ».
     *
     * <p>
     * Le partenaire y documente neuf pays :
     * <table>
     * <caption>Couverture Monetbil</caption>
     * <tr><td>Cameroun (XAF)</td><td>MTN, Orange, Express Union</td></tr>
     * <tr><td>Sénégal (XOF)</td><td>Orange</td></tr>
     * <tr><td>Congo-Kinshasa (CDF)</td><td>Orange, Airtel, Africell</td></tr>
     * <tr><td>Congo-Brazzaville (XAF)</td><td>MTN, Airtel</td></tr>
     * <tr><td>Bénin (XOF)</td><td>MTN, Moov</td></tr>
     * <tr><td>Guinée-Conakry (GNF)</td><td>MTN, Orange</td></tr>
     * <tr><td>Gabon (XAF)</td><td>Moov, Airtel</td></tr>
     * <tr><td>Liberia (LRD)</td><td>MTN</td></tr>
     * <tr><td>Ouganda (UGX)</td><td>Airtel, MTN</td></tr>
     * </table>
     *
     * <p>
     * Six sont déclarés ici. Le Gabon, le Liberia et l'Ouganda ne figurent pas
     * dans l'énumération {@code Country} : les ajouter est une décision produit,
     * pas une correction technique. Le Gabon est le candidat le plus naturel —
     * zone CEMAC, même devise que le Congo-Brazzaville et le Cameroun.
     *
     * <p>
     * Apport principal : Monetbil est le <strong>second</strong> partenaire à
     * couvrir le Congo-Brazzaville, jusqu'ici desservi par une seule passerelle,
     * et le premier à couvrir simultanément le Sénégal et le Congo — ce qui rend
     * le corridor SN↔CG franchissable en direct au lieu de passer par un pont.
     *
     * <p>
     * Express Union Mobile Money ({@code CM_EUMM}) n'est pas modélisé : le
     * catalogue d'opérateurs identifie les réseaux par préfixe téléphonique, ce
     * qui ne s'applique pas à un établissement financier.
     */
    private static final Set<Country> COVERAGE = EnumSet.of(
            Country.CAMEROON, Country.SENEGAL, Country.DRC,
            Country.CONGO_BRAZZAVILLE, Country.BENIN, Country.GUINEA);

    private static final GatewayCapabilities DEFAULT_CAPABILITIES = new GatewayCapabilities(
            GatewayType.MONETBIL,
            COVERAGE,
            COVERAGE,
            Set.of("XAF", "XOF", "CDF", "GNF"),
            EnumSet.of(
                    MobileOperator.MTN_CM, MobileOperator.ORANGE_CM,
                    MobileOperator.ORANGE_SN,
                    MobileOperator.ORANGE_CD, MobileOperator.AIRTEL_CD,
                    MobileOperator.AFRICELL_CD,
                    MobileOperator.MTN_CG, MobileOperator.AIRTEL_CG,
                    MobileOperator.MTN_BJ, MobileOperator.MOOV_BJ,
                    MobileOperator.MTN_GN, MobileOperator.ORANGE_GN),
            true);

    @Value("${gateway.monetbil.api-url:https://api.monetbil.com}")
    private String apiUrl;

    @Value("${gateway.monetbil.payment-path:/payment/v1/placePayment}")
    private String paymentPath;

    @Value("${gateway.monetbil.payment-status-path:/payment/v1/checkPayment}")
    private String paymentStatusPath;

    /** Chemin documenté sur monetbil.company (2026). L'ancien {@code /v1/payouts} était faux. */
    @Value("${gateway.monetbil.payout-path:/payment/v1/payouts/withdrawal}")
    private String payoutPath;

    @Value("${gateway.monetbil.service-key:}")
    private String serviceKey;

    @Value("${gateway.monetbil.service-secret:}")
    private String serviceSecret;

    @Value("${gateway.monetbil.enabled:false}")
    private boolean enabled;

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    /** Codes opérateurs Monetbil, identiques en encaissement et en versement. */
    private static final Map<MobileOperator, String> OPERATOR_CODES = new EnumMap<>(Map.ofEntries(
            Map.entry(MobileOperator.MTN_CM, "CM_MTNMOBILEMONEY"),
            Map.entry(MobileOperator.ORANGE_CM, "CM_ORANGEMONEY"),
            Map.entry(MobileOperator.ORANGE_SN, "SN_ORANGEMONEY"),
            Map.entry(MobileOperator.ORANGE_CD, "CD_ORANGEMONEY"),
            Map.entry(MobileOperator.AIRTEL_CD, "CD_AIRTELMONEY"),
            Map.entry(MobileOperator.AFRICELL_CD, "CD_AFRICELL"),
            Map.entry(MobileOperator.MTN_CG, "CG_MTNMOBILEMONEY"),
            Map.entry(MobileOperator.AIRTEL_CG, "CG_AIRTELMONEY"),
            Map.entry(MobileOperator.MTN_BJ, "BJ_MTNMOBILEMONEY"),
            Map.entry(MobileOperator.MOOV_BJ, "BJ_MOOVMONEY"),
            Map.entry(MobileOperator.MTN_GN, "GN_MTNMOBILEMONEY"),
            Map.entry(MobileOperator.ORANGE_GN, "GN_ORANGEMONEY")));

    private final RestTemplate restTemplate;
    private final com.mbotamapay.gateway.GatewayCapabilityRegistry registry;

    public MonetbilGateway(@Qualifier(GatewayHttpConfig.GATEWAY_REST_TEMPLATE) RestTemplate restTemplate,
            com.mbotamapay.gateway.GatewayCapabilityRegistry registry) {
        this.restTemplate = restTemplate;
        this.registry = registry;
    }

    @jakarta.annotation.PostConstruct
    void registerCapabilities() {
        registry.registerDefault(GatewayType.MONETBIL, DEFAULT_CAPABILITIES);
    }

    /**
     * Couverture effective. Le Gabon, le Liberia et l'Ouganda restent hors du
     * catalogue {@code Country} et ne peuvent donc pas être ouverts ici ; les six
     * autres marchés documentés se pilotent depuis l'administration.
     */

    @Override
    public String getPlatformName() {
        return PLATFORM_NAME;
    }

    @Override
    public boolean supports(String platform) {
        return PLATFORM_NAME.equalsIgnoreCase(platform);
    }

    @Override
    public GatewayType getGatewayType() {
        return GatewayType.MONETBIL;
    }

    @Override
    public GatewayCapabilities capabilities() {
        return registry.capabilities(GatewayType.MONETBIL, DEFAULT_CAPABILITIES);
    }

    @Override
    public boolean isOperational() {
        return enabled && !serviceKey.isBlank() && !serviceSecret.isBlank();
    }

    @Override
    public PaymentInitResponse initiatePayment(PaymentInitRequest request) {
        log.info("Initiating Monetbil payment: ref={}, amount={}",
                request.getTransactionReference(), request.getAmount());

        try {
            Map<String, Object> body = new HashMap<>();
            body.put("service", serviceKey);
            body.put("phonenumber", Msisdn.of(request.getSenderPhone(),
                    Country.fromPhoneNumber(request.getSenderPhone()).orElse(Country.CAMEROON)));
            body.put("amount", request.getAmount());
            body.put("currency", request.getCurrency());
            body.put("item_ref", request.getTransactionReference());
            body.put("payment_ref", request.getTransactionReference());
            body.put("notify_url", request.getCallbackUrl());
            body.put("return_url", request.getReturnUrl());

            Map<String, Object> response = post(apiUrl + paymentPath, body);

            if (isAccepted(response)) {
                return PaymentInitResponse.builder()
                        .success(true)
                        .paymentUrl(asString(response.get("payment_url")))
                        .externalReference(asString(response.get("paymentId")))
                        .build();
            }
            return PaymentInitResponse.builder()
                    .success(false)
                    .message(asString(response.get("message")))
                    .build();

        } catch (Exception e) {
            log.error("Monetbil payment error: {}", e.getMessage());
            return PaymentInitResponse.builder().success(false).message(e.getMessage()).build();
        }
    }

    /**
     * Versement ({@code payouts/withdrawal}). Monetbil met la demande en file et
     * notifie le résultat à {@code payout_notification_url} : il n'existe pas
     * d'API de lecture du statut d'un versement.
     *
     * <p>
     * Pas de try/catch générique : une expiration de délai doit remonter au
     * PayoutExecutor, qui la traite comme une issue indéterminée.
     */
    @Override
    public PayoutResponse initiatePayout(PayoutRequest request) {
        log.info("Initiating Monetbil payout: ref={}, amount={}, country={}",
                request.getReference(), request.getAmount(), request.getCountry());

        Map<String, Object> body = new HashMap<>();
        body.put("service_key", serviceKey);
        body.put("service_secret", serviceSecret);
        body.put("phonenumber", Msisdn.of(request.getRecipientPhone(), request.getCountry()));
        body.put("amount", request.getAmount());
        body.put("processing_number", request.getReference());
        body.put("payout_notification_url", baseUrl + "/api/v1/payments/callback/" + PLATFORM_NAME);
        String operator = request.getOperator() == null ? null : OPERATOR_CODES.get(request.getOperator());
        if (operator != null) {
            body.put("operator", operator);
        }

        Map<String, Object> response = post(apiUrl + payoutPath, body);

        if (Boolean.TRUE.equals(response.get("success"))) {
            return PayoutResponse.builder()
                    .success(true)
                    .message(nullSafe(asString(response.get("message")), "Payout initiated successfully"))
                    .externalReference(asString(response.get("transaction")))
                    .transactionReference(request.getReference())
                    .status("PENDING")
                    .build();
        }
        return PayoutResponse.builder()
                .success(false)
                .message(nullSafe(asString(response.get("message")), "Payout refusé par Monetbil"))
                .transactionReference(request.getReference())
                .status("FAILED")
                .build();
    }

    /** Monetbil n'expose pas de lecture du statut d'un versement. */
    @Override
    public PayoutStatusResponse checkPayoutStatus(String reference) {
        return PayoutStatusResponse.builder()
                .success(false)
                .status("UNKNOWN")
                .message("Monetbil ne fournit pas de statut de versement : résultat par notification")
                .externalReference(reference)
                .build();
    }

    @Override
    public PaymentStatusResponse checkStatus(String transactionReference) {
        try {
            Map<String, Object> response = post(apiUrl + paymentStatusPath, Map.of(
                    "service", serviceKey,
                    "paymentId", transactionReference));
            return PaymentStatusResponse.builder()
                    .success(isAccepted(response))
                    .status(mapStatus(asString(response.get("transaction_status"))))
                    .message(asString(response.get("message")))
                    .build();
        } catch (Exception e) {
            log.error("Monetbil status error: {}", e.getMessage());
            return PaymentStatusResponse.builder().success(false).status("ERROR")
                    .message(e.getMessage()).build();
        }
    }

    /**
     * Vérification HMAC-SHA256 du corps brut de la notification, avec comparaison
     * en temps constant.
     *
     * <p>
     * L'appelant doit fournir le corps <em>brut</em> de la requête. Passer la
     * représentation Java d'une {@code Map} — ce que fait le contrôleur de
     * callback aujourd'hui — ne peut structurellement pas produire une signature
     * valide.
     */
    @Override
    public boolean verifyWebhookSignature(String payload, String signature) {
        if (payload == null || signature == null || serviceSecret.isBlank()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(serviceSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] computed = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(computed, hexToBytes(signature));
        } catch (Exception e) {
            log.warn("Monetbil signature verification failed: {}", e.getMessage());
            return false;
        }
    }

    // === Internes ===

    private Map<String, Object> post(String url, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> received = response.getBody();
        return received == null ? Map.of() : received;
    }

    private boolean isAccepted(Map<String, Object> response) {
        Object success = response.get("success");
        if (success instanceof Boolean b) {
            return b;
        }
        String status = asString(response.get("status"));
        return "REQUEST_ACCEPTED".equalsIgnoreCase(status) || "success".equalsIgnoreCase(status);
    }

    private String mapStatus(String status) {
        if (status == null) {
            return "UNKNOWN";
        }
        return switch (status.toLowerCase()) {
            case "1", "success", "successful", "completed" -> "COMPLETED";
            case "0", "pending", "processing" -> "PENDING";
            case "-1", "failed", "error" -> "FAILED";
            case "cancelled" -> "CANCELLED";
            default -> "UNKNOWN";
        };
    }

    private static byte[] hexToBytes(String hex) {
        String clean = hex.trim().toLowerCase();
        int length = clean.length();
        if (length % 2 != 0) {
            return new byte[0];
        }
        byte[] out = new byte[length / 2];
        for (int i = 0; i < length; i += 2) {
            out[i / 2] = (byte) ((Character.digit(clean.charAt(i), 16) << 4)
                    + Character.digit(clean.charAt(i + 1), 16));
        }
        return out;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String nullSafe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
