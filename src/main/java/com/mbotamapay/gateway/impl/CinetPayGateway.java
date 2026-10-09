package com.mbotamapay.gateway.impl;

import com.mbotamapay.config.GatewayHttpConfig;
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
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Intégration CinetPay — API v1 (2026).
 *
 * <p>
 * L'ancienne API ({@code api-checkout.cinetpay.com/v2}, {@code api.cinetpay.com/v1/transfer})
 * a été retirée : le premier hôte ne résout plus, le second répond 404. La
 * nouvelle API, décrite par les SDK officiels ({@code github.com/cinetpay}),
 * diffère sur trois points qui structurent cette classe :
 * <ul>
 * <li><strong>Identifiants par pays.</strong> Chaque pays a sa paire
 * {@code api_key}/{@code api_password} et son propre jeton. La configuration les
 * déclare sous la forme {@code CI:sk_test_xxx:motdepasse,SN:...}.</li>
 * <li><strong>Jeton JWT</strong> obtenu par {@code POST /v1/oauth/login}, valable
 * 24 h, mis en cache 23 h comme le fait le SDK, renouvelé sur 1002/1003.</li>
 * <li><strong>Référence marchande de 30 caractères au plus.</strong> Nos
 * références de transfert ({@code TRF-} + UUID) en font 40 : elles sont
 * condensées de façon déterministe (voir {@link #merchantId}).</li>
 * </ul>
 *
 * <p>
 * Production : {@code https://api.cinetpay.co}. Bac à sable :
 * {@code https://api.cinetpay.net} (clés {@code sk_test_}).
 */
@Component
@Slf4j
public class CinetPayGateway implements PaymentGateway, PayoutGateway {

    private static final String PLATFORM_NAME = "cinetpay";

    /** Le SDK officiel garde le jeton 23 h pour une validité annoncée de 24 h. */
    private static final long TOKEN_TTL_SECONDS = 82_800;

    private static final int MERCHANT_ID_MAX_LENGTH = 30;

    /**
     * Codes {@code payment_method} des SDK officiels. Les SDK ne distinguent pas
     * encaissement et transfert : la couverture en versement par opérateur reste
     * à confirmer auprès de CinetPay.
     */
    private static final Map<MobileOperator, String> PAYMENT_METHODS = new EnumMap<>(Map.ofEntries(
            Map.entry(MobileOperator.ORANGE_CI, "OM_CI"),
            Map.entry(MobileOperator.MTN_CI, "MTN_CI"),
            Map.entry(MobileOperator.MOOV_CI, "MOOV_CI"),
            Map.entry(MobileOperator.WAVE_CI, "WAVE_CI"),
            Map.entry(MobileOperator.ORANGE_SN, "OM_SN"),
            Map.entry(MobileOperator.FREE_SN, "FREE_SN"),
            Map.entry(MobileOperator.WAVE_SN, "WAVE_SN"),
            Map.entry(MobileOperator.ORANGE_ML, "OM_ML"),
            Map.entry(MobileOperator.MOOV_ML, "MOOV_ML"),
            Map.entry(MobileOperator.ORANGE_GN, "OM_GN"),
            Map.entry(MobileOperator.MTN_GN, "MTN_GN"),
            Map.entry(MobileOperator.ORANGE_CM, "OM_CM"),
            Map.entry(MobileOperator.MTN_CM, "MTN_CM"),
            Map.entry(MobileOperator.ORANGE_BF, "OM_BF"),
            Map.entry(MobileOperator.MOOV_BF, "MOOV_BF"),
            Map.entry(MobileOperator.MTN_BJ, "MTN_BJ"),
            Map.entry(MobileOperator.MOOV_BJ, "MOOV_BJ"),
            Map.entry(MobileOperator.TOGOCOM_TG, "TMONEY_TG"),
            Map.entry(MobileOperator.MOOV_TG, "MOOV_TG"),
            Map.entry(MobileOperator.AIRTEL_NE, "AIRTEL_NE"),
            Map.entry(MobileOperator.MOOV_NE, "MOOV_NE"),
            Map.entry(MobileOperator.ORANGE_CD, "OM_CD"),
            Map.entry(MobileOperator.VODACOM_CD, "MPESA_CD"),
            Map.entry(MobileOperator.AIRTEL_CD, "AIRTEL_CD"),
            Map.entry(MobileOperator.AFRICELL_CD, "AFRICELL_CD")));

    private static final Set<Country> COVERAGE = EnumSet.of(
            Country.COTE_DIVOIRE, Country.SENEGAL, Country.MALI, Country.GUINEA,
            Country.CAMEROON, Country.BURKINA_FASO, Country.BENIN, Country.TOGO,
            Country.NIGER, Country.DRC);

    private static final GatewayCapabilities DEFAULT_CAPABILITIES = new GatewayCapabilities(
            GatewayType.CINETPAY,
            COVERAGE,
            COVERAGE,
            Set.of("XOF", "GNF", "XAF", "CDF"),
            EnumSet.copyOf(PAYMENT_METHODS.keySet()),
            true);

    /** Statuts finaux d'échec, d'après la table de codes des SDK. */
    private static final Set<String> FAILED_STATUSES = Set.of(
            "FAILED", "EXPIRED", "OTP_ERROR", "OTP_EXPIRED", "INSUFFICIENT_BALANCE",
            "USER_NOT_FOUND", "USER_IS_BLOCKED", "NOT_ALLOWED", "OPERATION_ERROR");

    @Value("${gateway.cinetpay.api-url:https://api.cinetpay.net}")
    private String apiUrl;

    /** {@code CI:api_key:api_password,SN:api_key:api_password} */
    @Value("${gateway.cinetpay.credentials:}")
    private String credentialsRaw;

    /** Adresse exigée par CinetPay quand l'expéditeur n'en a pas renseigné. */
    @Value("${gateway.cinetpay.default-client-email:}")
    private String defaultClientEmail;

    @Value("${gateway.cinetpay.enabled:true}")
    private boolean enabled;

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    private final RestTemplate restTemplate;
    private final GatewayCapabilityRegistry registry;

    private Map<Country, Credentials> credentials = Map.of();
    private final Map<Country, Token> tokens = new ConcurrentHashMap<>();

    public CinetPayGateway(@Qualifier(GatewayHttpConfig.GATEWAY_REST_TEMPLATE) RestTemplate restTemplate,
            GatewayCapabilityRegistry registry) {
        this.restTemplate = restTemplate;
        this.registry = registry;
    }

    @jakarta.annotation.PostConstruct
    void init() {
        registry.registerDefault(GatewayType.CINETPAY, DEFAULT_CAPABILITIES);
        credentials = parseCredentials(credentialsRaw);
        log.info("CinetPay: {} credential set(s) configured for {}", credentials.size(), credentials.keySet());
    }

    @Override
    public GatewayCapabilities capabilities() {
        return registry.capabilities(GatewayType.CINETPAY, DEFAULT_CAPABILITIES);
    }

    /**
     * Opérationnelle dès qu'un pays a ses identifiants. Un pays sans identifiants
     * est refusé à l'appel, et le moteur bascule sur la passerelle suivante.
     */
    @Override
    public boolean isOperational() {
        return enabled && !credentials.isEmpty();
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
        return GatewayType.CINETPAY;
    }

    // === Encaissement ===

    @Override
    public PaymentInitResponse initiatePayment(PaymentInitRequest request) {
        log.info("Initiating CinetPay payment: ref={}, amount={}",
                request.getTransactionReference(), request.getAmount());

        Optional<Country> country = Country.fromPhoneNumber(request.getSenderPhone());
        if (country.isEmpty() || !credentials.containsKey(country.get())) {
            return PaymentInitResponse.builder().success(false)
                    .message("CinetPay non configuré pour le pays de l'expéditeur").build();
        }
        String email = firstNonBlank(request.getSenderEmail(), defaultClientEmail);
        if (email == null) {
            return PaymentInitResponse.builder().success(false)
                    .message("CinetPay exige une adresse e-mail client").build();
        }

        String[] names = splitName(request.getSenderName());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("currency", request.getCurrency());
        body.put("merchant_transaction_id", merchantId(request.getTransactionReference()));
        body.put("amount", request.getAmount());
        body.put("lang", "fr");
        body.put("designation", firstNonBlank(request.getDescription(), "Paiement MbotamaPay"));
        body.put("client_email", email);
        body.put("client_first_name", names[0]);
        body.put("client_last_name", names[1]);
        body.put("success_url", request.getReturnUrl());
        body.put("failed_url", request.getCancelUrl());
        body.put("notify_url", request.getCallbackUrl());
        body.put("channel", "PUSH");
        body.put("client_phone_number", "+" + Msisdn.of(request.getSenderPhone(), country.get()));

        try {
            Map<String, Object> response = call(country.get(), HttpMethod.POST, "/v1/payment", body);
            if (code(response) == 200) {
                return PaymentInitResponse.builder()
                        .success(true)
                        .paymentUrl(asString(response.get("payment_url")))
                        .externalReference(asString(response.get("transaction_id")))
                        .transactionId(asString(response.get("transaction_id")))
                        .build();
            }
            return PaymentInitResponse.builder().success(false).message(describe(response)).build();
        } catch (Exception e) {
            log.error("CinetPay payment error: {}", e.getMessage());
            return PaymentInitResponse.builder().success(false).message(e.getMessage()).build();
        }
    }

    @Override
    public PaymentStatusResponse checkStatus(String transactionReference) {
        try {
            Optional<Map<String, Object>> found = lookup("/v1/payment/", merchantId(transactionReference));
            if (found.isEmpty()) {
                return PaymentStatusResponse.builder().success(false).status("UNKNOWN")
                        .message("Transaction introuvable chez CinetPay").build();
            }
            Map<String, Object> response = found.get();
            return PaymentStatusResponse.builder()
                    .success(true)
                    .status(mapStatus(asString(response.get("status"))))
                    .message(describe(response))
                    .externalReference(asString(response.get("transaction_id")))
                    .build();
        } catch (Exception e) {
            log.error("CinetPay status error: ref={}: {}", transactionReference, e.getMessage());
            return PaymentStatusResponse.builder().success(false).status("ERROR")
                    .message(e.getMessage()).build();
        }
    }

    /**
     * La notification CinetPay ne porte ni statut ni signature : seulement un
     * {@code notify_token} à comparer avec celui reçu à l'initiation, que nous ne
     * conservons pas. Le contrôleur relit donc le statut auprès de l'API
     * ({@link #checkStatus}) — ce que la documentation du SDK recommande de toute
     * façon. Un rappel forgé ne peut que déclencher une relecture.
     */
    @Override
    public boolean verifyWebhookSignature(String payload, String signature) {
        return true;
    }

    // === Versement ===

    @Override
    public PayoutResponse initiatePayout(PayoutRequest request) {
        log.info("Initiating CinetPay transfer: ref={}, amount={}, country={}",
                request.getReference(), request.getAmount(), request.getCountry());

        Country country = request.getCountry();
        if (country == null || !credentials.containsKey(country)) {
            return failedPayout(request, "CinetPay non configuré pour "
                    + (country == null ? "ce pays" : country.getDisplayName()));
        }
        MobileOperator operator = request.getOperator() != null
                ? request.getOperator()
                : MobileOperator.fromPhoneNumber(request.getRecipientPhone(), country).orElse(null);
        String method = operator == null ? null : PAYMENT_METHODS.get(operator);
        if (method == null) {
            return failedPayout(request, "Opérateur du bénéficiaire non pris en charge par CinetPay");
        }

        String merchantId = merchantId(request.getReference());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("currency", request.getCurrency());
        body.put("merchant_transaction_id", merchantId);
        body.put("phone_number", "+" + Msisdn.of(request.getRecipientPhone(), country));
        body.put("amount", request.getAmount());
        body.put("payment_method", method);
        body.put("reason", truncate(firstNonBlank(request.getDescription(), "Transfert MbotamaPay"), 255));
        body.put("notify_url", baseUrl + "/api/v1/payments/callback/" + PLATFORM_NAME);

        // Pas de try/catch générique : une expiration de délai doit remonter au
        // PayoutExecutor, qui la traite comme une issue indéterminée.
        Map<String, Object> response = call(country, HttpMethod.POST, "/v1/transfer", body);
        String status = asString(response.get("status"));

        // TRANSACTION_EXIST : ce transfert a déjà été soumis lors d'une tentative
        // précédente. Le signaler en échec ferait payer une seconde fois ailleurs.
        if ("PENDING".equals(status) || "SUCCESS".equals(status) || "TRANSACTION_EXIST".equals(status)) {
            return PayoutResponse.builder()
                    .success(true)
                    .message("TRANSACTION_EXIST".equals(status)
                            ? "Payout already initiated"
                            : "Payout initiated successfully")
                    .externalReference(firstNonBlank(asString(response.get("transaction_id")), merchantId))
                    .transactionReference(request.getReference())
                    .status("SUCCESS".equals(status) ? "COMPLETED" : "PENDING")
                    .build();
        }
        return failedPayout(request, describe(response));
    }

    @Override
    public PayoutStatusResponse checkPayoutStatus(String reference) {
        try {
            Optional<Map<String, Object>> found = lookup("/v1/transfer/", merchantId(reference));
            if (found.isEmpty()) {
                return PayoutStatusResponse.builder().success(false).status("UNKNOWN")
                        .message("Transfert introuvable chez CinetPay").build();
            }
            Map<String, Object> response = found.get();
            return PayoutStatusResponse.builder()
                    .success(true)
                    .status(mapStatus(asString(response.get("status"))))
                    .message(describe(response))
                    .externalReference(asString(response.get("transaction_id")))
                    .amount(asLong(response.get("amount")))
                    .currency(asString(response.get("currency")))
                    .build();
        } catch (Exception e) {
            log.error("CinetPay transfer status error: {}", e.getMessage());
            return PayoutStatusResponse.builder().success(false).message(e.getMessage()).build();
        }
    }

    // === Internes ===

    /**
     * Référence marchande d'au plus 30 caractères. Une référence courte est
     * transmise telle quelle, ce qui garde la correspondance lisible avec le
     * tableau de bord. Une référence longue est condensée (SHA-256), de façon
     * déterministe : un réessai reproduit le même identifiant et CinetPay le
     * refuse en doublon ({@code TRANSACTION_EXIST}) au lieu de payer deux fois.
     */
    static String merchantId(String reference) {
        if (reference != null && reference.length() <= MERCHANT_ID_MAX_LENGTH) {
            return reference;
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(reference).getBytes(StandardCharsets.UTF_8));
            return "H" + HexFormat.of().formatHex(hash).substring(0, MERCHANT_ID_MAX_LENGTH - 1);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }

    /**
     * Une lecture de statut ne dit pas de quel pays relève la transaction : on
     * interroge chaque compte configuré jusqu'à la trouver. Il y en a au plus
     * une dizaine, et un statut n'est lu qu'à la réception d'un rappel.
     */
    private Optional<Map<String, Object>> lookup(String path, String id) {
        for (Country country : credentials.keySet()) {
            Map<String, Object> response = call(country, HttpMethod.GET, path + id, null);
            int code = code(response);
            if (code != 404 && !"NOT_FOUND".equals(asString(response.get("status")))) {
                return Optional.of(response);
            }
        }
        return Optional.empty();
    }

    /** Appel authentifié, avec une nouvelle connexion si le jeton est refusé. */
    private Map<String, Object> call(Country country, HttpMethod method, String path, Map<String, Object> body) {
        Map<String, Object> response = exchange(country, method, path, body);
        int code = code(response);
        if (code == 1002 || code == 1003) {
            tokens.remove(country);
            response = exchange(country, method, path, body);
        }
        return response;
    }

    private Map<String, Object> exchange(Country country, HttpMethod method, String path, Map<String, Object> body) {
        HttpHeaders headers = jsonHeaders();
        headers.setBearerAuth(token(country));
        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    apiUrl + path, method, new HttpEntity<>(body, headers), Map.class);
            return bodyOf(response.getBody());
        } catch (HttpStatusCodeException e) {
            // CinetPay répond en JSON sur ses erreurs 4xx : on lit le code métier.
            Map<?, ?> error = e.getResponseBodyAs(Map.class);
            if (error == null) {
                throw e;
            }
            return bodyOf(error);
        }
    }

    private String token(Country country) {
        Token cached = tokens.get(country);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return cached.value();
        }
        Credentials creds = credentials.get(country);
        if (creds == null) {
            throw new IllegalStateException("Aucun identifiant CinetPay pour " + country.getIsoCode());
        }
        ResponseEntity<Map> response = restTemplate.exchange(
                apiUrl + "/v1/oauth/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("api_key", creds.apiKey(), "api_password", creds.apiPassword()),
                        jsonHeaders()),
                Map.class);
        Map<String, Object> received = bodyOf(response.getBody());
        String value = asString(received.get("access_token"));
        if (value == null) {
            throw new IllegalStateException("Connexion CinetPay refusée pour " + country.getIsoCode()
                    + " : " + describe(received));
        }
        tokens.put(country, new Token(value, Instant.now().plusSeconds(TOKEN_TTL_SECONDS)));
        return value;
    }

    static Map<Country, Credentials> parseCredentials(String raw) {
        Map<Country, Credentials> parsed = new EnumMap<>(Country.class);
        if (raw == null || raw.isBlank()) {
            return parsed;
        }
        for (String entry : raw.split(",")) {
            String[] parts = entry.trim().split(":", 3);
            if (parts.length != 3 || parts[1].isBlank() || parts[2].isBlank()) {
                log.warn("CinetPay credential entry ignored (expected ISO:api_key:api_password)");
                continue;
            }
            Country.fromIsoCode(parts[0].trim())
                    .ifPresentOrElse(
                            c -> parsed.put(c, new Credentials(parts[1].trim(), parts[2].trim())),
                            () -> log.warn("CinetPay credential entry ignored: unknown country {}", parts[0]));
        }
        return parsed;
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return headers;
    }

    private static String mapStatus(String status) {
        if (status == null) {
            return "UNKNOWN";
        }
        if ("SUCCESS".equals(status)) {
            return "COMPLETED";
        }
        if ("INITIATED".equals(status) || "PENDING".equals(status)) {
            return "PENDING";
        }
        return FAILED_STATUSES.contains(status) ? "FAILED" : "UNKNOWN";
    }

    private static PayoutResponse failedPayout(PayoutRequest request, String message) {
        return PayoutResponse.builder()
                .success(false)
                .message(message)
                .transactionReference(request.getReference())
                .status("FAILED")
                .build();
    }

    private static String describe(Map<String, Object> response) {
        String status = asString(response.get("status"));
        String detail = firstNonBlank(asString(response.get("description")), asString(response.get("message")));
        if (status == null) {
            return detail;
        }
        return detail == null ? status : status + " : " + detail;
    }

    private static int code(Map<String, Object> response) {
        Object code = response.get("code");
        if (code == null) {
            return -1;
        }
        try {
            return Integer.parseInt(String.valueOf(code));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String[] splitName(String fullName) {
        String name = fullName == null ? "" : fullName.trim();
        int space = name.indexOf(' ');
        String first = space > 0 ? name.substring(0, space) : name;
        String last = space > 0 ? name.substring(space + 1).trim() : "";
        // CinetPay exige 2 caractères au moins pour chacun.
        return new String[] {
                first.length() >= 2 ? first : "Client",
                last.length() >= 2 ? last : "MbotamaPay" };
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

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? (fallback == null || fallback.isBlank() ? null : fallback) : value;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    record Credentials(String apiKey, String apiPassword) {
        @Override
        public String toString() {
            return "Credentials[apiKey=" + apiKey.substring(0, Math.min(8, apiKey.length())) + "…]";
        }
    }

    private record Token(String value, Instant expiresAt) {
    }
}
