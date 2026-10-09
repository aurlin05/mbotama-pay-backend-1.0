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

    /**
     * Pays d'encaissement (checkout). Le Niger ne figure plus dans la
     * documentation PayDunya, ni en encaissement ni en versement (vérifié en
     * octobre 2026).
     */
    private static final Set<Country> COLLECTION = EnumSet.of(
            Country.SENEGAL, Country.COTE_DIVOIRE, Country.BENIN, Country.TOGO,
            Country.BURKINA_FASO, Country.MALI);

    /** Versement : mêmes pays, plus le Cameroun (MTN, en XAF). */
    private static final Set<Country> PAYOUT = EnumSet.of(
            Country.SENEGAL, Country.COTE_DIVOIRE, Country.BENIN, Country.TOGO,
            Country.BURKINA_FASO, Country.MALI, Country.CAMEROON);

    /** Valeurs {@code withdraw_mode} de l'API Disburse v2, par opérateur. */
    private static final Map<MobileOperator, String> WITHDRAW_MODES = new EnumMap<>(Map.ofEntries(
            Map.entry(MobileOperator.ORANGE_SN, "orange-money-senegal"),
            Map.entry(MobileOperator.FREE_SN, "free-money-senegal"),
            Map.entry(MobileOperator.WAVE_SN, "wave-senegal"),
            Map.entry(MobileOperator.ORANGE_CI, "orange-money-ci"),
            Map.entry(MobileOperator.MTN_CI, "mtn-ci"),
            Map.entry(MobileOperator.MOOV_CI, "moov-ci"),
            Map.entry(MobileOperator.WAVE_CI, "wave-ci"),
            Map.entry(MobileOperator.MTN_BJ, "mtn-benin"),
            Map.entry(MobileOperator.MOOV_BJ, "moov-benin"),
            Map.entry(MobileOperator.CELTIIS_BJ, "celtiis-cash"),
            Map.entry(MobileOperator.TOGOCOM_TG, "t-money-togo"),
            Map.entry(MobileOperator.MOOV_TG, "moov-togo"),
            Map.entry(MobileOperator.ORANGE_BF, "orange-money-burkina"),
            Map.entry(MobileOperator.MOOV_BF, "moov-burkina-faso"),
            Map.entry(MobileOperator.ORANGE_ML, "orange-money-mali"),
            Map.entry(MobileOperator.MTN_CM, "mtn-cameroun")));

    private static final GatewayCapabilities DEFAULT_CAPABILITIES = new GatewayCapabilities(
            GatewayType.PAYDUNYA,
            COLLECTION,
            PAYOUT,
            Set.of("XOF", "XAF"),
            EnumSet.copyOf(WITHDRAW_MODES.keySet()),
            true);

    @Value("${gateway.paydunya.api-url:https://app.paydunya.com/api/v1}")
    private String apiUrl;

    @Value("${gateway.paydunya.checkout-path:/checkout-invoice/create}")
    private String checkoutPath;

    @Value("${gateway.paydunya.checkout-status-path:/checkout-invoice/confirm}")
    private String checkoutStatusPath;

    /** API Disburse v2 : la v1 répond 404 depuis 2026. */
    @Value("${gateway.paydunya.disburse-url:https://app.paydunya.com/api/v2/disburse}")
    private String disburseUrl;

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    @Value("${gateway.paydunya.master-key:}")
    private String masterKey;

    @Value("${gateway.paydunya.private-key:}")
    private String privateKey;

    @Value("${gateway.paydunya.token:}")
    private String token;

    @Value("${gateway.paydunya.enabled:false}")
    private boolean enabled;

    private final RestTemplate restTemplate;
    private final com.mbotamapay.gateway.GatewayCapabilityRegistry registry;

    public PayDunyaGateway(@Qualifier(GatewayHttpConfig.GATEWAY_REST_TEMPLATE) RestTemplate restTemplate,
            com.mbotamapay.gateway.GatewayCapabilityRegistry registry) {
        this.restTemplate = restTemplate;
        this.registry = registry;
    }

    @jakarta.annotation.PostConstruct
    void registerCapabilities() {
        registry.registerDefault(GatewayType.PAYDUNYA, DEFAULT_CAPABILITIES);
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
        return registry.capabilities(GatewayType.PAYDUNYA, DEFAULT_CAPABILITIES);
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
     * Versement en deux temps, conformément à l'API Disburse v2 : on obtient un
     * jeton de décaissement, puis on le soumet.
     *
     * <p>
     * Si la soumission n'aboutit pas clairement, la documentation impose de
     * relire le statut du jeton : {@code created} veut dire que rien n'est parti
     * et qu'il faut soumettre à nouveau <em>le même jeton</em> ; {@code pending}
     * ou {@code success} veulent dire que les fonds sont engagés.
     *
     * <p>
     * Pas de try/catch générique : une expiration de délai doit remonter au
     * PayoutExecutor, qui la traite comme une issue indéterminée.
     */
    @Override
    public PayoutResponse initiatePayout(PayoutRequest request) {
        log.info("Initiating PayDunya disburse: ref={}, amount={}, country={}",
                request.getReference(), request.getAmount(), request.getCountry());

        String mode = request.getOperator() == null ? null : WITHDRAW_MODES.get(request.getOperator());
        if (mode == null) {
            return failure(request, "Opérateur du bénéficiaire non pris en charge par PayDunya");
        }

        Map<String, Object> invoice = post(disburseUrl + "/get-invoice", Map.of(
                "account_alias", normalisePhone(request.getRecipientPhone(), request.getCountry()),
                "amount", request.getAmount(),
                "withdraw_mode", mode,
                "callback_url", baseUrl + "/api/v1/payments/callback/" + PLATFORM_NAME));
        if (!isSuccess(invoice)) {
            return failure(request, asString(invoice.get("response_text")));
        }
        String disburseToken = asString(invoice.get("disburse_token"));
        if (disburseToken == null) {
            return failure(request, "Jeton de décaissement absent de la réponse partenaire");
        }

        Map<String, Object> submitted = submit(disburseToken, request.getReference());
        if (isSuccess(submitted) && !"failed".equalsIgnoreCase(asString(submitted.get("status")))) {
            return accepted(request, disburseToken, asString(submitted.get("status")));
        }

        // Soumission sans issue nette : le statut du jeton tranche.
        String status = asString(checkStatusOf(disburseToken).get("status"));
        if ("created".equalsIgnoreCase(status)) {
            submitted = submit(disburseToken, request.getReference());
            if (isSuccess(submitted)) {
                return accepted(request, disburseToken, asString(submitted.get("status")));
            }
            return failure(request, asString(submitted.get("response_text")));
        }
        if ("pending".equalsIgnoreCase(status) || "success".equalsIgnoreCase(status)) {
            return accepted(request, disburseToken, status);
        }
        return failure(request, asString(submitted.get("response_text")));
    }

    /** Statut d'un versement par son jeton de décaissement (référence externe). */
    @Override
    public PayoutStatusResponse checkPayoutStatus(String reference) {
        try {
            Map<String, Object> response = checkStatusOf(reference);
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

    private Map<String, Object> submit(String disburseToken, String reference) {
        Map<String, Object> body = new HashMap<>();
        body.put("disburse_invoice", disburseToken);
        if (reference != null) {
            body.put("disburse_id", reference);
        }
        return post(disburseUrl + "/submit-invoice", body);
    }

    private Map<String, Object> checkStatusOf(String disburseToken) {
        return post(disburseUrl + "/check-status", Map.of("disburse_invoice", disburseToken));
    }

    /** Un succès sans statut explicite est final, d'après la documentation. */
    private PayoutResponse accepted(PayoutRequest request, String disburseToken, String status) {
        return PayoutResponse.builder()
                .success(true)
                .message("Payout initiated successfully")
                .externalReference(disburseToken)
                .transactionReference(request.getReference())
                .status(status == null || "success".equalsIgnoreCase(status) ? "COMPLETED" : "PENDING")
                .build();
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
            case "pending", "processing", "created" -> "PENDING";
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
