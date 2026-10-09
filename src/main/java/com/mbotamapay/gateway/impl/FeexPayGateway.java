package com.mbotamapay.gateway.impl;

import com.mbotamapay.config.GatewayHttpConfig;
import com.mbotamapay.dto.verification.MobileMoneyVerificationResult;
import com.mbotamapay.entity.enums.Country;
import com.mbotamapay.entity.enums.GatewayType;
import com.mbotamapay.entity.enums.MobileOperator;
import com.mbotamapay.gateway.GatewayCapabilities;
import com.mbotamapay.gateway.GatewayCapabilityRegistry;
import com.mbotamapay.gateway.PaymentGateway;
import com.mbotamapay.gateway.PayoutGateway;
import com.mbotamapay.gateway.dto.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Intégration FeexPay — API v2 ({@code https://api-v2.feexpay.me}).
 *
 * <p>
 * La documentation officielle bascule par défaut sur la v2 et indique que les
 * versions antérieures ne fonctionnent plus. Changements pris en compte :
 * <ul>
 * <li>versements Bénin MTN/Moov regroupés sous {@code /transfer/global} et Togo
 * sous {@code /togo}, avec un champ {@code network} ;</li>
 * <li>Sénégal et Burkina Faso désormais couverts ;</li>
 * <li>identifiant de boutique public ({@code shop}) et non plus hexadécimal ;</li>
 * <li>pas de champ devise : elle découle de l'opérateur (XAF au Congo, XOF
 * ailleurs).</li>
 * </ul>
 *
 * <p>
 * Notre référence de transaction voyage dans {@code callback_info}, que FeexPay
 * renvoie dans ses webhooks et permet de rechercher.
 *
 * <p>
 * Non pris en charge : Orange Burkina et Wave Burkina en versement, Orange
 * Sénégal et Orange Burkina en encaissement — ils exigent un code OTP saisi par
 * le client, que notre parcours ne recueille pas.
 */
@Component
@Slf4j
public class FeexPayGateway implements PaymentGateway, PayoutGateway {

    private static final String PLATFORM_NAME = "feexpay";

    private static final String PAYOUT = "/api/payouts/public/";
    private static final String PAYIN = "/api/transactions/public/requesttopay/";

    /** Chemin de versement et valeur {@code network} éventuelle, par opérateur. */
    private static final Map<MobileOperator, Route> PAYOUT_ROUTES = new EnumMap<>(Map.ofEntries(
            Map.entry(MobileOperator.MTN_BJ, new Route("transfer/global", "MTN")),
            Map.entry(MobileOperator.MOOV_BJ, new Route("transfer/global", "MOOV")),
            Map.entry(MobileOperator.CELTIIS_BJ, new Route("celtiis_bj", "CELTIIS BJ")),
            Map.entry(MobileOperator.TOGOCOM_TG, new Route("togo", "TOGOCOM TG")),
            Map.entry(MobileOperator.MOOV_TG, new Route("togo", "MOOV TG")),
            Map.entry(MobileOperator.MTN_CI, new Route("mtn_ci", null)),
            Map.entry(MobileOperator.ORANGE_CI, new Route("orange_ci", null)),
            Map.entry(MobileOperator.MOOV_CI, new Route("moov_ci", null)),
            Map.entry(MobileOperator.WAVE_CI, new Route("wave_ci", null)),
            Map.entry(MobileOperator.ORANGE_SN, new Route("orange_sn", null)),
            Map.entry(MobileOperator.FREE_SN, new Route("free_sn", null)),
            Map.entry(MobileOperator.WAVE_SN, new Route("wave_sn", null)),
            Map.entry(MobileOperator.MTN_CG, new Route("mtn_cg", null)),
            Map.entry(MobileOperator.MOOV_BF, new Route("moov_bf", null))));

    /** Chemin d'encaissement par opérateur (hors opérateurs exigeant un OTP). */
    private static final Map<MobileOperator, String> PAYIN_PATHS = new EnumMap<>(Map.ofEntries(
            Map.entry(MobileOperator.MTN_BJ, "mtn"),
            Map.entry(MobileOperator.MOOV_BJ, "moov"),
            Map.entry(MobileOperator.CELTIIS_BJ, "celtiis_bj"),
            Map.entry(MobileOperator.TOGOCOM_TG, "togocom_tg"),
            Map.entry(MobileOperator.MOOV_TG, "moov_tg"),
            Map.entry(MobileOperator.MTN_CI, "mtn_ci"),
            Map.entry(MobileOperator.ORANGE_CI, "orange_ci"),
            Map.entry(MobileOperator.MOOV_CI, "moov_ci"),
            Map.entry(MobileOperator.WAVE_CI, "wave_ci"),
            Map.entry(MobileOperator.WAVE_SN, "wave_sn"),
            Map.entry(MobileOperator.FREE_SN, "free_sn"),
            Map.entry(MobileOperator.MTN_CG, "mtn_cg"),
            Map.entry(MobileOperator.MOOV_BF, "moov_bf")));

    /** Encaissements qui redirigent le client et attendent une URL de retour. */
    private static final Set<String> PAYIN_WITH_RETURN_URL = Set.of(
            "moov_ci", "orange_ci", "wave_ci", "wave_sn", "free_sn");

    private static final Set<Country> COVERAGE = EnumSet.of(
            Country.BENIN, Country.TOGO, Country.COTE_DIVOIRE, Country.CONGO_BRAZZAVILLE,
            Country.SENEGAL, Country.BURKINA_FASO);

    private static final GatewayCapabilities DEFAULT_CAPABILITIES = new GatewayCapabilities(
            GatewayType.FEEXPAY,
            COVERAGE,
            COVERAGE,
            Set.of("XOF", "XAF"),
            EnumSet.copyOf(PAYOUT_ROUTES.keySet()),
            true);

    @Value("${gateway.feexpay.api-url:https://api-v2.feexpay.me}")
    private String apiUrl;

    @Value("${gateway.feexpay.api-key:}")
    private String apiKey;

    @Value("${gateway.feexpay.shop-id:}")
    private String shopId;

    @Value("${gateway.feexpay.enabled:true}")
    private boolean enabled;

    private final RestTemplate restTemplate;
    private final GatewayCapabilityRegistry registry;

    public FeexPayGateway(@Qualifier(GatewayHttpConfig.GATEWAY_REST_TEMPLATE) RestTemplate restTemplate,
            GatewayCapabilityRegistry registry) {
        this.restTemplate = restTemplate;
        this.registry = registry;
    }

    @jakarta.annotation.PostConstruct
    void registerCapabilities() {
        registry.registerDefault(GatewayType.FEEXPAY, DEFAULT_CAPABILITIES);
    }

    /**
     * Couverture effective, résolue à chaque appel par le registre.
     *
     * <p>
     * La liste ci-dessus n'est qu'un défaut. La couverture réelle peut être
     * redéfinie en configuration ou depuis l'administration, et la résolution est
     * délibérément faite à l'appel : la figer au démarrage rendrait toute
     * modification inopérante jusqu'au redéploiement suivant.
     */
    @Override
    public GatewayCapabilities capabilities() {
        return registry.capabilities(GatewayType.FEEXPAY, DEFAULT_CAPABILITIES);
    }

    @Override
    public boolean isOperational() {
        return enabled && !apiKey.isBlank() && !shopId.isBlank();
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
        return GatewayType.FEEXPAY;
    }

    // === Encaissement ===

    @Override
    public PaymentInitResponse initiatePayment(PaymentInitRequest request) {
        log.info("Initiating FeexPay payin: ref={}, amount={}",
                request.getTransactionReference(), request.getAmount());

        Optional<Country> country = Country.fromPhoneNumber(request.getSenderPhone());
        String path = country
                .flatMap(c -> MobileOperator.fromPhoneNumber(request.getSenderPhone(), c))
                .map(PAYIN_PATHS::get)
                .orElse(null);
        if (path == null) {
            return PaymentInitResponse.builder().success(false)
                    .message("Opérateur de l'expéditeur non pris en charge par FeexPay").build();
        }

        String[] names = splitName(request.getSenderName());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("phoneNumber", Msisdn.of(request.getSenderPhone(), country.get()));
        body.put("amount", request.getAmount());
        body.put("shop", shopId);
        body.put("first_name", names[0]);
        body.put("last_name", names[1]);
        body.put("description", plainText(request.getDescription(), "Paiement MbotamaPay", 40));
        body.put("callback_info", request.getTransactionReference());
        if (PAYIN_WITH_RETURN_URL.contains(path)) {
            body.put("return_url", request.getReturnUrl());
        }
        if ("wave_ci".equals(path)) {
            body.put("cancel_url", request.getCancelUrl());
        }

        try {
            Map<String, Object> response = post(PAYIN + path, body);
            String reference = asString(response.get("reference"));
            if (reference != null && !"FAILED".equals(asString(response.get("status")))) {
                return PaymentInitResponse.builder()
                        .success(true)
                        .paymentUrl(asString(response.get("payment_url")))
                        .externalReference(reference)
                        .transactionId(reference)
                        .build();
            }
            return PaymentInitResponse.builder().success(false)
                    .message(describe(response, "Encaissement refusé par FeexPay")).build();
        } catch (Exception e) {
            log.error("FeexPay payin error: {}", e.getMessage());
            return PaymentInitResponse.builder().success(false).message(e.getMessage()).build();
        }
    }

    /**
     * Statut d'un encaissement à partir de <em>notre</em> référence, transmise dans
     * {@code callback_info} à l'initiation : c'est elle que le contrôleur de rappel
     * connaît.
     */
    @Override
    public PaymentStatusResponse checkStatus(String transactionReference) {
        try {
            Map<String, Object> response = get(
                    "/api/transactions/public/single/status/" + transactionReference + "?by=callback_info");
            return PaymentStatusResponse.builder()
                    .success(response.get("status") != null)
                    .status(mapStatus(asString(response.get("status"))))
                    .message(asString(response.get("reason")))
                    .externalReference(asString(response.get("reference")))
                    .amount(asLong(response.get("amount")))
                    .build();
        } catch (Exception e) {
            log.error("FeexPay status error: ref={}: {}", transactionReference, e.getMessage());
            return PaymentStatusResponse.builder().success(false).status("ERROR")
                    .message(e.getMessage()).build();
        }
    }

    /**
     * FeexPay ne signe pas ses webhooks. Le contrôleur relit le statut auprès de
     * l'API avant toute mise à jour, comme la documentation le demande : un appel
     * forgé ne peut que déclencher une relecture.
     */
    @Override
    public boolean verifyWebhookSignature(String payload, String signature) {
        return true;
    }

    // === Versement ===

    @Override
    public PayoutResponse initiatePayout(PayoutRequest request) {
        log.info("Initiating FeexPay payout: ref={}, amount={}, country={}",
                request.getReference(), request.getAmount(), request.getCountry());

        MobileOperator operator = request.getOperator() != null
                ? request.getOperator()
                : MobileOperator.fromPhoneNumber(request.getRecipientPhone(), request.getCountry()).orElse(null);
        Route route = operator == null ? null : PAYOUT_ROUTES.get(operator);
        if (route == null) {
            return failedPayout(request, "Opérateur du bénéficiaire non pris en charge par FeexPay");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", request.getAmount());
        body.put("phoneNumber", Msisdn.of(request.getRecipientPhone(), request.getCountry()));
        body.put("shop", shopId);
        if (route.network() != null) {
            body.put("network", route.network());
        }
        body.put("motif", plainText(request.getDescription(), "Transfert MbotamaPay", 30));
        body.put("callback_info", request.getReference());

        // Pas de try/catch générique : une expiration de délai doit remonter au
        // PayoutExecutor, qui la traite comme une issue indéterminée. FeexPay ne
        // dédoublonne pas les versements : un réessai ailleurs paierait deux fois.
        Map<String, Object> response;
        try {
            response = post(PAYOUT + route.path(), body);
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().is5xxServerError()) {
                // Une 5xx ne dit pas si le versement est parti : issue indéterminée.
                throw new ResourceAccessException("FeexPay : issue du versement indéterminée après erreur "
                        + e.getStatusCode().value());
            }
            log.error("FeexPay payout rejected: {} {}", e.getStatusCode(), e.getResponseBodyAsString());
            return failedPayout(request, "FeexPay a rejeté la requête (" + e.getStatusCode().value() + ")");
        }

        String reference = asString(response.get("reference"));
        String status = asString(response.get("status"));
        if (reference != null && !"FAILED".equals(status)) {
            return PayoutResponse.builder()
                    .success(true)
                    .message("Payout initiated successfully")
                    .externalReference(reference)
                    .transactionReference(request.getReference())
                    .status("SUCCESSFUL".equals(status) ? "COMPLETED" : "PENDING")
                    .build();
        }
        return failedPayout(request, describe(response, "Versement refusé par FeexPay"));
    }

    /**
     * Statut d'un versement par la référence <strong>FeexPay</strong> (celle
     * renvoyée à l'initiation). FeexPay rend cette vérification obligatoire avant
     * de considérer un versement comme final.
     */
    @Override
    public PayoutStatusResponse checkPayoutStatus(String reference) {
        try {
            Map<String, Object> response = get("/api/payouts/status/public/" + reference);
            return PayoutStatusResponse.builder()
                    .success(response.get("status") != null)
                    .status(mapStatus(asString(response.get("status"))))
                    .message(asString(response.get("reason")))
                    .externalReference(reference)
                    .amount(asLong(response.get("amount")))
                    .build();
        } catch (Exception e) {
            log.error("FeexPay payout status error: {}", e.getMessage());
            return PayoutStatusResponse.builder().success(false).message(e.getMessage()).build();
        }
    }

    @Override
    public MobileMoneyVerificationResult verifySubscriber(
            String phoneNumber, Country country, MobileOperator operator) {
        log.info("FeeXPay subscriber verification: phone={}, country={}", phoneNumber, country);

        try {
            Map<String, Object> body = new HashMap<>();
            body.put("phone", Msisdn.of(phoneNumber, country));
            body.put("shop_id", shopId);

            Map<String, Object> responseBody = post("/api/check-subscriber", body);

            if ("success".equals(responseBody.get("status"))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) responseBody.get("data");
                boolean isActive = data != null && Boolean.TRUE.equals(data.get("is_active"));
                String accountName = data != null ? (String) data.get("name") : null;

                return MobileMoneyVerificationResult.builder()
                        .valid(isActive)
                        .apiVerified(true)
                        .accountName(accountName)
                        .mobileMoneySupported(true)
                        .build();
            }

            return MobileMoneyVerificationResult.builder()
                    .valid(false)
                    .apiVerified(true)
                    .errorMessage("Compte Mobile Money non trouvé")
                    .build();

        } catch (Exception e) {
            log.warn("FeeXPay subscriber verification failed: {}", e.getMessage());
            return null; // Fallback to local validation
        }
    }

    // === Internes ===

    private Map<String, Object> post(String path, Map<String, Object> body) {
        ResponseEntity<Map> response = restTemplate.exchange(
                apiUrl + path, HttpMethod.POST, new HttpEntity<>(body, headers()), Map.class);
        return bodyOf(response.getBody());
    }

    private Map<String, Object> get(String path) {
        ResponseEntity<Map> response = restTemplate.exchange(
                apiUrl + path, HttpMethod.GET, new HttpEntity<>(headers()), Map.class);
        return bodyOf(response.getBody());
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        return headers;
    }

    private static String mapStatus(String status) {
        if (status == null) {
            return "UNKNOWN";
        }
        return switch (status.toUpperCase()) {
            case "SUCCESSFUL", "SUCCESS" -> "COMPLETED";
            case "PENDING", "IN PENDING STATE" -> "PENDING";
            case "FAILED" -> "FAILED";
            default -> "UNKNOWN";
        };
    }

    /**
     * {@code motif} (30 caractères) et {@code description} (40) refusent les
     * caractères spéciaux : on ne garde que lettres non accentuées, chiffres et
     * espaces.
     */
    static String plainText(String value, String fallback, int max) {
        String source = value == null || value.isBlank() ? fallback : value;
        String ascii = java.text.Normalizer.normalize(source, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^A-Za-z0-9 ]", " ")
                .replaceAll(" +", " ")
                .trim();
        if (ascii.isEmpty()) {
            ascii = fallback;
        }
        return ascii.length() <= max ? ascii : ascii.substring(0, max).trim();
    }

    private static String[] splitName(String fullName) {
        String name = fullName == null ? "" : fullName.trim();
        int space = name.indexOf(' ');
        return space > 0
                ? new String[] { name.substring(0, space), name.substring(space + 1).trim() }
                : new String[] { name, "" };
    }

    private static PayoutResponse failedPayout(PayoutRequest request, String message) {
        return PayoutResponse.builder()
                .success(false)
                .message(message)
                .transactionReference(request.getReference())
                .status("FAILED")
                .build();
    }

    private static String describe(Map<String, Object> response, String fallback) {
        for (String key : List.of("reason", "message", "responsemsg")) {
            String value = asString(response.get(key));
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> bodyOf(Map<?, ?> body) {
        return body == null ? Map.of() : (Map<String, Object>) body;
    }

    private static Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return new java.math.BigDecimal(String.valueOf(value)).longValue();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private record Route(String path, String network) {
    }
}
