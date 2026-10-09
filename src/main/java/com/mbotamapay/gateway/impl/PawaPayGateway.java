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
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Intégration pawaPay — agrégateur mobile money panafricain (Merchant API v2).
 *
 * <p>
 * Apport au réseau : une seule API couvre sept de nos marchés, dont trois qui ne
 * reposaient que sur une passerelle (Cameroun, RD Congo, Congo-Brazzaville).
 * C'est aussi le premier partenaire à offrir une <strong>idempotence de bout en
 * bout</strong> : un versement soumis deux fois avec le même identifiant est
 * ignoré ({@code DUPLICATE_IGNORED}) au lieu d'être payé deux fois.
 *
 * <p>
 * Particularités de l'API, à garder en tête :
 * <ul>
 * <li>Les identifiants de paiement ({@code depositId}, {@code payoutId}) sont des
 * UUID fournis <em>par nous</em>. Ils sont dérivés de façon déterministe de notre
 * référence de transaction (voir {@link #paymentId}), ce qui évite d'avoir à les
 * stocker et rend tout réessai idempotent.</li>
 * <li>Le fournisseur (opérateur) est obligatoire : il n'est pas déduit du numéro
 * côté pawaPay.</li>
 * <li>Les versements sont <strong>préfinancés</strong> : le portefeuille pawaPay
 * de chaque pays doit être approvisionné, sinon le versement est refusé avec
 * {@code PAWAPAY_WALLET_OUT_OF_FUNDS}.</li>
 * </ul>
 *
 * <p>
 * Inerte tant que {@code gateway.pawapay.enabled} est faux ou que le jeton d'API
 * est absent.
 */
@Component
@Slf4j
public class PawaPayGateway implements PaymentGateway, PayoutGateway {

    private static final String PLATFORM_NAME = "pawapay";

    /**
     * Correspondance opérateur → code fournisseur pawaPay, relevée dans la page
     * « Providers » de la documentation v2.
     *
     * <p>
     * Absents chez pawaPay, donc non joignables par cette passerelle : Moov
     * Côte d'Ivoire, Celtiis Bénin, Africell RDC. Le Togo, le Mali, le Niger et
     * la Guinée ne sont pas couverts.
     */
    private static final Map<MobileOperator, String> PROVIDERS = new EnumMap<>(Map.ofEntries(
            Map.entry(MobileOperator.MTN_BJ, "MTN_MOMO_BEN"),
            Map.entry(MobileOperator.MOOV_BJ, "MOOV_BEN"),
            Map.entry(MobileOperator.MOOV_BF, "MOOV_BFA"),
            Map.entry(MobileOperator.ORANGE_BF, "ORANGE_BFA"),
            Map.entry(MobileOperator.MTN_CM, "MTN_MOMO_CMR"),
            Map.entry(MobileOperator.ORANGE_CM, "ORANGE_CMR"),
            Map.entry(MobileOperator.MTN_CI, "MTN_MOMO_CIV"),
            Map.entry(MobileOperator.ORANGE_CI, "ORANGE_CIV"),
            Map.entry(MobileOperator.WAVE_CI, "WAVE_CIV"),
            Map.entry(MobileOperator.VODACOM_CD, "VODACOM_MPESA_COD"),
            Map.entry(MobileOperator.AIRTEL_CD, "AIRTEL_COD"),
            Map.entry(MobileOperator.ORANGE_CD, "ORANGE_COD"),
            Map.entry(MobileOperator.MTN_CG, "MTN_MOMO_COG"),
            Map.entry(MobileOperator.AIRTEL_CG, "AIRTEL_COG"),
            Map.entry(MobileOperator.ORANGE_SN, "ORANGE_SEN"),
            Map.entry(MobileOperator.FREE_SN, "FREE_SEN"),
            Map.entry(MobileOperator.WAVE_SN, "WAVE_SEN")));

    /**
     * Fournisseurs dont l'encaissement exige une redirection du client vers une
     * page d'autorisation (Wave). L'URL n'est pas renvoyée à l'initiation : elle
     * devient disponible quelques instants plus tard via le statut du dépôt.
     */
    private static final Set<String> REDIRECT_AUTH_PROVIDERS = Set.of("WAVE_CIV", "WAVE_SEN");

    /**
     * Fournisseurs dont l'encaissement exige un code OTP saisi par le client
     * <em>avant</em> l'initiation (Orange Burkina). Notre parcours de collecte ne
     * recueille pas ce code : l'encaissement y est refusé d'emblée, le versement
     * reste possible.
     */
    private static final Set<String> PREAUTH_PROVIDERS = Set.of("ORANGE_BFA");

    private static final Set<Country> COVERAGE = EnumSet.of(
            Country.BENIN, Country.BURKINA_FASO, Country.CAMEROON, Country.COTE_DIVOIRE,
            Country.DRC, Country.CONGO_BRAZZAVILLE, Country.SENEGAL);

    private static final GatewayCapabilities DEFAULT_CAPABILITIES = new GatewayCapabilities(
            GatewayType.PAWAPAY,
            COVERAGE,
            COVERAGE,
            Set.of("XOF", "XAF", "CDF"),
            EnumSet.copyOf(PROVIDERS.keySet()),
            true);

    @Value("${gateway.pawapay.api-url:https://api.sandbox.pawapay.io}")
    private String apiUrl;

    @Value("${gateway.pawapay.api-token:}")
    private String apiToken;

    @Value("${gateway.pawapay.enabled:false}")
    private boolean enabled;

    /** Nombre de lectures du statut pour obtenir l'URL d'autorisation Wave. */
    @Value("${gateway.pawapay.auth-url-poll-attempts:5}")
    private int authUrlPollAttempts;

    @Value("${gateway.pawapay.auth-url-poll-interval-ms:1000}")
    private long authUrlPollIntervalMs;

    private final RestTemplate restTemplate;
    private final GatewayCapabilityRegistry registry;

    public PawaPayGateway(@Qualifier(GatewayHttpConfig.GATEWAY_REST_TEMPLATE) RestTemplate restTemplate,
            GatewayCapabilityRegistry registry) {
        this.restTemplate = restTemplate;
        this.registry = registry;
    }

    @jakarta.annotation.PostConstruct
    void registerCapabilities() {
        registry.registerDefault(GatewayType.PAWAPAY, DEFAULT_CAPABILITIES);
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
        return GatewayType.PAWAPAY;
    }

    @Override
    public GatewayCapabilities capabilities() {
        return registry.capabilities(GatewayType.PAWAPAY, DEFAULT_CAPABILITIES);
    }

    @Override
    public boolean isOperational() {
        return enabled && !apiToken.isBlank();
    }

    // === Encaissement (deposit) ===

    @Override
    public PaymentInitResponse initiatePayment(PaymentInitRequest request) {
        log.info("Initiating pawaPay deposit: ref={}, amount={}",
                request.getTransactionReference(), request.getAmount());

        Optional<Country> country = Country.fromPhoneNumber(request.getSenderPhone());
        Optional<String> provider = country.flatMap(c -> resolveProvider(null, request.getSenderPhone(), c));
        if (provider.isEmpty()) {
            return PaymentInitResponse.builder()
                    .success(false)
                    .message("Opérateur de l'expéditeur non pris en charge par pawaPay")
                    .build();
        }
        if (PREAUTH_PROVIDERS.contains(provider.get())) {
            return PaymentInitResponse.builder()
                    .success(false)
                    .message("Encaissement " + provider.get()
                            + " non pris en charge : code de préautorisation requis")
                    .build();
        }

        String depositId = paymentId("deposit", request.getTransactionReference());
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("depositId", depositId);
            body.put("amount", String.valueOf(request.getAmount()));
            body.put("currency", request.getCurrency());
            body.put("payer", account(request.getSenderPhone(), country.get(), provider.get()));
            body.put("clientReferenceId", request.getTransactionReference());
            if (REDIRECT_AUTH_PROVIDERS.contains(provider.get())) {
                body.put("successfulUrl", request.getReturnUrl());
                body.put("failedUrl", request.getCancelUrl());
            }

            Map<String, Object> response = post("/v2/deposits", body);
            String status = asString(response.get("status"));

            if ("ACCEPTED".equals(status) || "DUPLICATE_IGNORED".equals(status)) {
                String paymentUrl = "GET_AUTH_URL".equals(asString(response.get("nextStep")))
                        ? awaitAuthorizationUrl(depositId)
                        : null;
                return PaymentInitResponse.builder()
                        .success(true)
                        .message("ACCEPTED".equals(status)
                                ? "Validez le paiement sur votre téléphone"
                                : "Paiement déjà initié")
                        .paymentUrl(paymentUrl)
                        .externalReference(depositId)
                        .transactionId(depositId)
                        .build();
            }
            return PaymentInitResponse.builder()
                    .success(false)
                    .message(failureMessage(response, "Dépôt refusé par pawaPay"))
                    .externalReference(depositId)
                    .build();

        } catch (HttpStatusCodeException e) {
            log.error("pawaPay deposit rejected: {} {}", e.getStatusCode(), e.getResponseBodyAsString());
            return PaymentInitResponse.builder().success(false)
                    .message("pawaPay a rejeté la requête (" + e.getStatusCode().value() + ")").build();
        } catch (Exception e) {
            log.error("pawaPay deposit error: {}", e.getMessage());
            return PaymentInitResponse.builder().success(false).message(e.getMessage()).build();
        }
    }

    @Override
    public PaymentStatusResponse checkStatus(String transactionReference) {
        String depositId = asPaymentId("deposit", transactionReference);
        try {
            Map<String, Object> data = fetch("/v2/deposits/" + depositId);
            if (data == null) {
                return PaymentStatusResponse.builder().success(false).status("UNKNOWN")
                        .message("Dépôt introuvable chez pawaPay").externalReference(depositId).build();
            }
            return PaymentStatusResponse.builder()
                    .success(true)
                    .status(mapStatus(asString(data.get("status"))))
                    .message(failureMessage(data, null))
                    .externalReference(depositId)
                    .amount(asLong(data.get("amount")))
                    .currency(asString(data.get("currency")))
                    .build();
        } catch (Exception e) {
            log.error("pawaPay deposit status error: {}", e.getMessage());
            return PaymentStatusResponse.builder().success(false).status("ERROR")
                    .message(e.getMessage()).build();
        }
    }

    /**
     * pawaPay signe ses callbacks selon la RFC 9421 (ECDSA/RSA sur un ensemble
     * d'en-têtes {@code Signature}, {@code Signature-Input}, {@code Content-Digest}),
     * ce que le contrat {@code (payload, signature)} ne permet pas de vérifier : le
     * corps brut et les en-têtes n'arrivent pas jusqu'ici.
     *
     * <p>
     * Le callback est accepté, mais son contenu n'est <strong>jamais</strong> pris
     * pour argent comptant : le contrôleur relit le statut auprès de l'API pawaPay
     * ({@link #checkStatus}), authentifiée par notre jeton. C'est la vérification
     * de repli que pawaPay recommande. Un callback forgé ne peut donc que
     * déclencher une relecture, pas modifier un statut.
     */
    @Override
    public boolean verifyWebhookSignature(String payload, String signature) {
        return true;
    }

    // === Versement (payout) ===

    @Override
    public PayoutResponse initiatePayout(PayoutRequest request) {
        log.info("Initiating pawaPay payout: ref={}, amount={}, country={}",
                request.getReference(), request.getAmount(), request.getCountry());

        Optional<String> provider = resolveProvider(
                request.getOperator(), request.getRecipientPhone(), request.getCountry());
        if (provider.isEmpty()) {
            return failedPayout(request, "Opérateur du bénéficiaire non pris en charge par pawaPay");
        }

        String payoutId = paymentId("payout", request.getReference());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("payoutId", payoutId);
        body.put("amount", String.valueOf(request.getAmount()));
        body.put("currency", request.getCurrency());
        body.put("recipient", account(request.getRecipientPhone(), request.getCountry(), provider.get()));
        body.put("clientReferenceId", request.getReference());

        // Pas de try/catch générique : une expiration de délai doit remonter
        // jusqu'au PayoutExecutor, qui la traite comme une issue indéterminée.
        Map<String, Object> response;
        try {
            response = post("/v2/payouts", body);
        } catch (HttpServerErrorException e) {
            // Une erreur 5xx ne dit pas si le versement a été enregistré. Le statut
            // tranche ; s'il est lui-même illisible, l'issue est indéterminée.
            log.error("pawaPay payout server error: {} {}", e.getStatusCode(), e.getResponseBodyAsString());
            return reconcileAfterServerError(request, payoutId, e);
        } catch (HttpStatusCodeException e) {
            log.error("pawaPay payout rejected: {} {}", e.getStatusCode(), e.getResponseBodyAsString());
            return failedPayout(request, "pawaPay a rejeté la requête (" + e.getStatusCode().value() + ")");
        }

        String status = asString(response.get("status"));
        if ("ACCEPTED".equals(status) || "DUPLICATE_IGNORED".equals(status)) {
            // DUPLICATE_IGNORED : ce versement a déjà été accepté lors d'une tentative
            // précédente. Le signaler en échec ferait basculer sur une autre
            // passerelle, et payer deux fois.
            return PayoutResponse.builder()
                    .success(true)
                    .message("ACCEPTED".equals(status)
                            ? "Payout initiated successfully"
                            : "Payout already initiated")
                    .externalReference(payoutId)
                    .transactionReference(request.getReference())
                    .status("PENDING")
                    .build();
        }
        return failedPayout(request, failureMessage(response, "Payout refusé par pawaPay"));
    }

    @Override
    public PayoutStatusResponse checkPayoutStatus(String reference) {
        String payoutId = asPaymentId("payout", reference);
        try {
            Map<String, Object> data = fetch("/v2/payouts/" + payoutId);
            if (data == null) {
                return PayoutStatusResponse.builder().success(false).status("UNKNOWN")
                        .message("Versement introuvable chez pawaPay").externalReference(payoutId).build();
            }
            return PayoutStatusResponse.builder()
                    .success(true)
                    .status(mapStatus(asString(data.get("status"))))
                    .message(failureMessage(data, null))
                    .externalReference(payoutId)
                    .amount(asLong(data.get("amount")))
                    .currency(asString(data.get("currency")))
                    .build();
        } catch (Exception e) {
            log.error("pawaPay payout status error: {}", e.getMessage());
            return PayoutStatusResponse.builder().success(false).message(e.getMessage()).build();
        }
    }

    // === Internes ===

    /**
     * UUID déterministe dérivé de notre référence, au format v4 exigé par pawaPay.
     *
     * <p>
     * Une même référence donne toujours le même identifiant : un réessai après
     * expiration de délai est dédoublonné par pawaPay au lieu d'être payé une
     * seconde fois. Le préfixe sépare les espaces dépôt et versement, qui
     * partagent la même référence de transaction.
     */
    static String paymentId(String kind, String reference) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((kind + ":" + reference).getBytes(StandardCharsets.UTF_8));
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x40); // version 4
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80); // variante RFC 4122
            ByteBuffer buffer = ByteBuffer.wrap(hash, 0, 16);
            return new UUID(buffer.getLong(), buffer.getLong()).toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }

    /** Accepte indifféremment notre référence ou l'identifiant pawaPay déjà dérivé. */
    private static String asPaymentId(String kind, String reference) {
        try {
            return UUID.fromString(reference).toString();
        } catch (IllegalArgumentException notAUuid) {
            return paymentId(kind, reference);
        }
    }

    private Optional<String> resolveProvider(MobileOperator operator, String phone, Country country) {
        MobileOperator resolved = operator != null
                ? operator
                : MobileOperator.fromPhoneNumber(phone, country).orElse(null);
        return Optional.ofNullable(resolved).map(PROVIDERS::get);
    }

    private static Map<String, Object> account(String phone, Country country, String provider) {
        return Map.of(
                "type", "MMO",
                "accountDetails", Map.of(
                        "phoneNumber", msisdn(phone, country),
                        "provider", provider));
    }

    /**
     * Format MSISDN attendu par pawaPay : chiffres seuls, indicatif pays inclus,
     * sans « + » ni « 00 ». Le zéro initial du numéro local est conservé : il fait
     * partie du numéro en Côte d'Ivoire, au Bénin et au Congo.
     */
    static String msisdn(String phone, Country country) {
        String cleaned = phone == null ? "" : phone.replaceAll("[^0-9]", "");
        if (cleaned.startsWith("00")) {
            cleaned = cleaned.substring(2);
        }
        if (country != null && !cleaned.startsWith(country.getPhonePrefix())) {
            cleaned = country.getPhonePrefix() + cleaned;
        }
        return cleaned;
    }

    /**
     * Wave n'expose l'URL d'autorisation qu'après l'initiation. Lecture bornée du
     * statut ; si l'URL n'arrive pas à temps, l'encaissement reste initié et le
     * client ne pourra pas le valider — l'échec final remontera par callback.
     */
    private String awaitAuthorizationUrl(String depositId) {
        for (int attempt = 0; attempt < authUrlPollAttempts; attempt++) {
            try {
                Thread.sleep(authUrlPollIntervalMs);
                Map<String, Object> data = fetch("/v2/deposits/" + depositId);
                String url = data == null ? null : asString(data.get("authorizationUrl"));
                if (url != null) {
                    return url;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("pawaPay authorization URL lookup failed: {}", e.getMessage());
            }
        }
        log.warn("pawaPay authorization URL not available for deposit {}", depositId);
        return null;
    }

    private Map<String, Object> post(String path, Map<String, Object> body) {
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers());
        ResponseEntity<Map> response = restTemplate.exchange(apiUrl + path, HttpMethod.POST, entity, Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> received = response.getBody();
        return received == null ? Map.of() : received;
    }

    /** Renvoie le bloc {@code data} d'une lecture de statut, ou null si NOT_FOUND. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> fetch(String path) {
        ResponseEntity<Map> response = restTemplate.exchange(
                apiUrl + path, HttpMethod.GET, new HttpEntity<>(headers()), Map.class);
        Map<String, Object> body = response.getBody();
        if (body == null || !"FOUND".equals(body.get("status"))) {
            return null;
        }
        return (Map<String, Object>) body.get("data");
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiToken);
        return headers;
    }

    private PayoutResponse reconcileAfterServerError(PayoutRequest request, String payoutId,
            HttpServerErrorException cause) {
        Map<String, Object> data;
        try {
            data = fetch("/v2/payouts/" + payoutId);
        } catch (Exception lookupFailure) {
            // Remonté comme expiration de délai : le PayoutExecutor ne bascule pas
            // sur une autre passerelle tant que l'issue n'est pas connue.
            throw new ResourceAccessException(
                    "pawaPay : issue du versement indéterminée après erreur "
                            + cause.getStatusCode().value());
        }
        if (data == null) {
            return failedPayout(request, "pawaPay indisponible (" + cause.getStatusCode().value() + ")");
        }
        return PayoutResponse.builder()
                .success(true)
                .message("Payout initiated successfully")
                .externalReference(payoutId)
                .transactionReference(request.getReference())
                .status("PENDING")
                .build();
    }

    private static PayoutResponse failedPayout(PayoutRequest request, String message) {
        return PayoutResponse.builder()
                .success(false)
                .message(message)
                .transactionReference(request.getReference())
                .status("FAILED")
                .build();
    }

    private static String mapStatus(String status) {
        if (status == null) {
            return "UNKNOWN";
        }
        return switch (status) {
            case "COMPLETED" -> "COMPLETED";
            case "FAILED" -> "FAILED";
            case "ACCEPTED", "ENQUEUED", "PROCESSING", "IN_RECONCILIATION" -> "PENDING";
            default -> "UNKNOWN";
        };
    }

    @SuppressWarnings("unchecked")
    private static String failureMessage(Map<String, Object> response, String fallback) {
        if (response.get("failureReason") instanceof Map<?, ?> reason) {
            Map<String, Object> failure = (Map<String, Object>) reason;
            String code = asString(failure.get("failureCode"));
            String message = asString(failure.get("failureMessage"));
            if (code != null) {
                return message == null ? code : code + " : " + message;
            }
        }
        return fallback;
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
}
