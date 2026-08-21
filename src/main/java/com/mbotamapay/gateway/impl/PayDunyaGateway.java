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

import java.util.*;

/**
 * Intégration PayDunya — agrégateur sénégalais couvrant la zone UEMOA.
 *
 * <p>
 * Apport principal au réseau : PayDunya est le premier partenaire à couvrir
 * simultanément le Sénégal, le Burkina Faso et le Niger. Ces trois marchés ne
 * disposaient jusqu'ici que d'un seul agrégateur chacun — une panne partenaire y
 * fermait le corridor sans repli possible.
 *
 * <p>
 * <strong>À valider avant activation.</strong> Les chemins d'API et les noms de
 * champs ci-dessous sont externalisés en configuration précisément parce qu'ils
 * n'ont pas été confrontés à l'environnement de recette du partenaire. Tant que
 * les identifiants sont absents ou que {@code gateway.paydunya.enabled} est
 * faux, {@link #isOperational()} retourne false et la porte d'éligibilité écarte
 * la passerelle avec un motif explicite — elle n'échoue jamais en cours de
 * versement.
 */
@Component
@Slf4j
public class PayDunyaGateway implements PaymentGateway, PayoutGateway {

    private static final String PLATFORM_NAME = "paydunya";

    private static final Set<Country> COVERAGE = EnumSet.of(
            Country.SENEGAL, Country.COTE_DIVOIRE, Country.BENIN, Country.TOGO,
            Country.BURKINA_FASO, Country.MALI, Country.NIGER);

    private static final GatewayCapabilities DEFAULT_CAPABILITIES = new GatewayCapabilities(
            GatewayType.PAYDUNYA,
            COVERAGE,
            COVERAGE,
            Set.of("XOF"),
            EnumSet.of(
                    MobileOperator.ORANGE_SN, MobileOperator.FREE_SN, MobileOperator.WAVE_SN,
                    MobileOperator.ORANGE_CI, MobileOperator.MTN_CI,
                    MobileOperator.MOOV_CI, MobileOperator.WAVE_CI,
                    MobileOperator.MTN_BJ, MobileOperator.MOOV_BJ,
                    MobileOperator.TOGOCOM_TG, MobileOperator.MOOV_TG,
                    MobileOperator.ORANGE_BF, MobileOperator.MOOV_BF,
                    MobileOperator.ORANGE_ML, MobileOperator.MOOV_ML,
                    MobileOperator.AIRTEL_NE, MobileOperator.MOOV_NE),
            true);

    @Value("${gateway.paydunya.api-url:https://app.paydunya.com/api/v1}")
    private String apiUrl;

    @Value("${gateway.paydunya.checkout-path:/checkout-invoice/create}")
    private String checkoutPath;

    @Value("${gateway.paydunya.checkout-status-path:/checkout-invoice/confirm}")
    private String checkoutStatusPath;

    @Value("${gateway.paydunya.disburse-invoice-path:/disburse/get-invoice}")
    private String disburseInvoicePath;

    @Value("${gateway.paydunya.disburse-submit-path:/disburse/submit-invoice}")
    private String disburseSubmitPath;

    @Value("${gateway.paydunya.master-key:}")
    private String masterKey;

    @Value("${gateway.paydunya.private-key:}")
    private String privateKey;

    @Value("${gateway.paydunya.token:}")
    private String token;

    @Value("${gateway.paydunya.enabled:false}")
    private boolean enabled;

    private final RestTemplate restTemplate;
    private final com.mbotamapay.gateway.GatewayCapabilityOverrides overrides;

    private volatile GatewayCapabilities capabilities = DEFAULT_CAPABILITIES;

    public PayDunyaGateway(@Qualifier(GatewayHttpConfig.GATEWAY_REST_TEMPLATE) RestTemplate restTemplate,
            com.mbotamapay.gateway.GatewayCapabilityOverrides overrides) {
        this.restTemplate = restTemplate;
        this.overrides = overrides;
    }

    @jakarta.annotation.PostConstruct
    void resolveCapabilities() {
        this.capabilities = overrides.resolve(GatewayType.PAYDUNYA, DEFAULT_CAPABILITIES);
    }

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
        return GatewayType.PAYDUNYA;
    }

    @Override
    public GatewayCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public boolean isOperational() {
        return enabled && !masterKey.isBlank() && !privateKey.isBlank() && !token.isBlank();
    }

    @Override
    public PaymentInitResponse initiatePayment(PaymentInitRequest request) {
        log.info("Initiating PayDunya checkout: ref={}, amount={}",
                request.getTransactionReference(), request.getAmount());

        try {
            Map<String, Object> body = new HashMap<>();
            body.put("invoice", Map.of(
                    "total_amount", request.getAmount(),
                    "description", nullSafe(request.getDescription(), "Paiement MbotamaPay")));
            body.put("store", Map.of("name", "MbotamaPay"));
            body.put("actions", Map.of(
                    "callback_url", request.getCallbackUrl(),
                    "return_url", request.getReturnUrl(),
                    "cancel_url", request.getCancelUrl()));
            body.put("custom_data", Map.of("reference", request.getTransactionReference()));

            Map<String, Object> response = post(apiUrl + checkoutPath, body);

            if (isSuccess(response)) {
                return PaymentInitResponse.builder()
                        .success(true)
                        .paymentUrl(asString(response.get("response_text")))
                        .externalReference(asString(response.get("token")))
                        .build();
            }
            return PaymentInitResponse.builder()
                    .success(false)
                    .message(asString(response.get("response_text")))
                    .build();

        } catch (Exception e) {
            log.error("PayDunya checkout error: {}", e.getMessage());
            return PaymentInitResponse.builder().success(false).message(e.getMessage()).build();
        }
    }

    /**
     * Versement en deux temps, conformément au modèle « disburse » du partenaire :
     * on obtient d'abord un jeton de décaissement, puis on le soumet.
     *
     * <p>
     * Si la première étape réussit et la seconde échoue, aucun fonds n'a bougé —
     * le jeton seul n'engage rien. C'est la raison pour laquelle le découpage est
     * conservé tel quel plutôt que fusionné.
     */
    @Override
    public PayoutResponse initiatePayout(PayoutRequest request) {
        log.info("Initiating PayDunya disburse: ref={}, amount={}, country={}",
                request.getReference(), request.getAmount(), request.getCountry());

        try {
            Map<String, Object> invoiceBody = Map.of(
                    "account_alias", normalisePhone(request.getRecipientPhone(), request.getCountry()),
                    "amount", request.getAmount(),
                    "withdraw_mode", withdrawMode(request.getOperator()));

            Map<String, Object> invoice = post(apiUrl + disburseInvoicePath, invoiceBody);
            if (!isSuccess(invoice)) {
                return failure(request, asString(invoice.get("response_text")));
            }

            String disburseToken = asString(invoice.get("disburse_token"));
            if (disburseToken == null) {
                return failure(request, "Jeton de décaissement absent de la réponse partenaire");
            }

            Map<String, Object> submitted = post(apiUrl + disburseSubmitPath,
                    Map.of("disburse_invoice", disburseToken,
                            "disburse_id", nullSafe(request.getReference(), "")));

            if (isSuccess(submitted)) {
                return PayoutResponse.builder()
                        .success(true)
                        .message("Payout initiated successfully")
                        .externalReference(asString(submitted.get("transaction_id")))
                        .transactionReference(request.getReference())
                        .status("PENDING")
                        .build();
            }
            return failure(request, asString(submitted.get("response_text")));

        } catch (Exception e) {
            log.error("PayDunya disburse error: {}", e.getMessage());
            return failure(request, e.getMessage());
        }
    }

    @Override
    public PayoutStatusResponse checkPayoutStatus(String reference) {
        try {
            Map<String, Object> response = get(apiUrl + checkoutStatusPath + "/" + reference);
            return PayoutStatusResponse.builder()
                    .success(isSuccess(response))
                    .status(mapStatus(asString(response.get("status"))))
                    .externalReference(reference)
                    .build();
        } catch (Exception e) {
            log.error("PayDunya payout status error: {}", e.getMessage());
            return PayoutStatusResponse.builder().success(false).message(e.getMessage()).build();
        }
    }

    @Override
    public PaymentStatusResponse checkStatus(String transactionReference) {
        try {
            Map<String, Object> response = get(apiUrl + checkoutStatusPath + "/" + transactionReference);
            return PaymentStatusResponse.builder()
                    .success(isSuccess(response))
                    .status(mapStatus(asString(response.get("status"))))
                    .message(asString(response.get("response_text")))
                    .build();
        } catch (Exception e) {
            log.error("PayDunya status error: {}", e.getMessage());
            return PaymentStatusResponse.builder().success(false).status("ERROR")
                    .message(e.getMessage()).build();
        }
    }

    /**
     * PayDunya n'expose pas de signature HMAC sur ses notifications : le modèle
     * documenté est la re-vérification du statut auprès de l'API. On refuse donc
     * de valider une notification sur sa seule foi, et l'appelant doit confirmer
     * via {@link #checkStatus(String)}.
     */
    @Override
    public boolean verifyWebhookSignature(String payload, String signature) {
        return false;
    }

    // === Internes ===

    private Map<String, Object> post(String url, Map<String, Object> body) {
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers());
        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);
        return safeBody(response);
    }

    private Map<String, Object> get(String url) {
        HttpEntity<Void> entity = new HttpEntity<>(headers());
        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
        return safeBody(response);
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("PAYDUNYA-MASTER-KEY", masterKey);
        headers.set("PAYDUNYA-PRIVATE-KEY", privateKey);
        headers.set("PAYDUNYA-TOKEN", token);
        return headers;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> safeBody(ResponseEntity<Map> response) {
        Map<String, Object> body = response.getBody();
        return body == null ? Map.of() : body;
    }

    /** Le partenaire répond {@code response_code: "00"} en cas de succès. */
    private boolean isSuccess(Map<String, Object> response) {
        return "00".equals(asString(response.get("response_code")));
    }

    private PayoutResponse failure(PayoutRequest request, String message) {
        return PayoutResponse.builder()
                .success(false)
                .message(nullSafe(message, "Payout refusé par PayDunya"))
                .transactionReference(request.getReference())
                .status("FAILED")
                .build();
    }

    /** Mode de retrait attendu par le partenaire, dérivé de l'opérateur. */
    private String withdrawMode(MobileOperator operator) {
        if (operator == null) {
            return "unknown";
        }
        return switch (operator) {
            case ORANGE_SN -> "orange-money-senegal";
            case FREE_SN -> "free-money-senegal";
            case WAVE_SN -> "wave-senegal";
            case ORANGE_CI -> "orange-money-ci";
            case MTN_CI -> "mtn-ci";
            case MOOV_CI -> "moov-ci";
            case WAVE_CI -> "wave-ci";
            case MTN_BJ -> "mtn-benin";
            case MOOV_BJ -> "moov-benin";
            case TOGOCOM_TG -> "t-money-togo";
            case MOOV_TG -> "moov-togo";
            case ORANGE_BF -> "orange-money-burkina";
            case MOOV_BF -> "moov-burkina";
            case ORANGE_ML -> "orange-money-mali";
            case MOOV_ML -> "moov-mali";
            case AIRTEL_NE -> "airtel-niger";
            case MOOV_NE -> "moov-niger";
            default -> "unknown";
        };
    }

    private String normalisePhone(String phone, Country country) {
        String cleaned = phone.replaceAll("[\\s\\-+]", "");
        if (cleaned.startsWith("00")) {
            cleaned = cleaned.substring(2);
        }
        if (country != null && cleaned.startsWith(country.getPhonePrefix())) {
            cleaned = cleaned.substring(country.getPhonePrefix().length());
        }
        return cleaned;
    }

    private String mapStatus(String status) {
        if (status == null) {
            return "UNKNOWN";
        }
        return switch (status.toLowerCase()) {
            case "completed", "success", "successful" -> "COMPLETED";
            case "pending", "processing" -> "PENDING";
            case "failed", "cancelled" -> "FAILED";
            default -> "UNKNOWN";
        };
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String nullSafe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
