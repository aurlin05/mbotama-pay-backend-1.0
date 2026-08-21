package com.mbotamapay.gateway.impl;

import com.mbotamapay.entity.enums.Country;
import com.mbotamapay.entity.enums.GatewayType;
import com.mbotamapay.entity.enums.MobileOperator;
import com.mbotamapay.gateway.PaymentGateway;
import com.mbotamapay.gateway.PayoutGateway;
import com.mbotamapay.gateway.dto.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * CinetPay Payment Gateway Integration
 * Documentation: https://docs.cinetpay.com
 * 
 * Supports: Côte d'Ivoire, Sénégal, Mali, Guinée, Cameroun, Burkina Faso,
 * Bénin, Togo, Niger
 */
@Component
@Slf4j
public class CinetPayGateway implements PaymentGateway, PayoutGateway {

    private static final String PLATFORM_NAME = "cinetpay";

    private static final Set<Country> PAYOUT_COUNTRIES = EnumSet.of(
            Country.COTE_DIVOIRE, Country.SENEGAL, Country.MALI, Country.GUINEA,
            Country.CAMEROON, Country.BURKINA_FASO, Country.BENIN, Country.TOGO,
            Country.NIGER, Country.DRC);

    private static final com.mbotamapay.gateway.GatewayCapabilities DEFAULT_CAPABILITIES =
            new com.mbotamapay.gateway.GatewayCapabilities(
                    GatewayType.CINETPAY,
                    PAYOUT_COUNTRIES,
                    PAYOUT_COUNTRIES,
                    // XOF (CI, SN, ML, BF, BJ, TG, NE), GNF (GN), XAF (CM), CDF (CD)
                    Set.of("XOF", "GNF", "XAF", "CDF"),
                    EnumSet.of(
                            MobileOperator.ORANGE_CI, MobileOperator.MTN_CI,
                            MobileOperator.MOOV_CI, MobileOperator.WAVE_CI,
                            MobileOperator.ORANGE_SN, MobileOperator.FREE_SN, MobileOperator.WAVE_SN,
                            MobileOperator.ORANGE_ML, MobileOperator.MOOV_ML,
                            MobileOperator.ORANGE_GN, MobileOperator.MTN_GN,
                            MobileOperator.ORANGE_CM, MobileOperator.MTN_CM,
                            MobileOperator.ORANGE_BF, MobileOperator.MOOV_BF,
                            MobileOperator.MTN_BJ, MobileOperator.MOOV_BJ,
                            MobileOperator.TOGOCOM_TG, MobileOperator.MOOV_TG,
                            MobileOperator.AIRTEL_NE, MobileOperator.MOOV_NE,
                            MobileOperator.ORANGE_CD, MobileOperator.VODACOM_CD,
                            MobileOperator.AIRTEL_CD),
                    true);

    @Value("${gateway.cinetpay.api-url:https://api-checkout.cinetpay.com/v2}")
    private String apiUrl;

    @Value("${gateway.cinetpay.transfer-url:https://api.cinetpay.com/v1/transfer}")
    private String transferUrl;

    @Value("${gateway.cinetpay.api-key:}")
    private String apiKey;

    @Value("${gateway.cinetpay.site-id:}")
    private String siteId;

    @Value("${gateway.cinetpay.enabled:true}")
    private boolean enabled;

    private final RestTemplate restTemplate;
    private final com.mbotamapay.gateway.GatewayCapabilityOverrides overrides;

    /** Couverture effective, redéfinissable par {@code gateway.capabilities.cinetpay.*}. */
    private volatile com.mbotamapay.gateway.GatewayCapabilities capabilities = DEFAULT_CAPABILITIES;

    public CinetPayGateway(
            @org.springframework.beans.factory.annotation.Qualifier(
                    com.mbotamapay.config.GatewayHttpConfig.GATEWAY_REST_TEMPLATE) RestTemplate restTemplate,
            com.mbotamapay.gateway.GatewayCapabilityOverrides overrides) {
        this.restTemplate = restTemplate;
        this.overrides = overrides;
    }

    @jakarta.annotation.PostConstruct
    void resolveCapabilities() {
        this.capabilities = overrides.resolve(GatewayType.CINETPAY, DEFAULT_CAPABILITIES);
    }

    @Override
    public com.mbotamapay.gateway.GatewayCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public boolean isOperational() {
        return enabled && !apiKey.isBlank() && !siteId.isBlank();
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

    @Override
    public PaymentInitResponse initiatePayment(PaymentInitRequest request) {
        log.info("Initiating CinetPay payment: ref={}, amount={}",
                request.getTransactionReference(), request.getAmount());

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> body = new HashMap<>();
            body.put("apikey", apiKey);
            body.put("site_id", siteId);
            body.put("transaction_id", request.getTransactionReference());
            body.put("amount", request.getAmount());
            body.put("currency", request.getCurrency());
            body.put("description", request.getDescription());
            body.put("notify_url", request.getCallbackUrl());
            body.put("return_url", request.getReturnUrl());
            body.put("cancel_url", request.getCancelUrl());
            body.put("channels", "ALL");

            // Customer info
            body.put("customer_phone_number", request.getSenderPhone());
            body.put("customer_name", "MbotamaPay User");
            body.put("customer_surname", "");

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    apiUrl + "/payment",
                    HttpMethod.POST,
                    entity,
                    Map.class);

            Map<String, Object> responseBody = response.getBody();

            if (responseBody != null && "00".equals(responseBody.get("code"))) {
                Map<String, Object> data = (Map<String, Object>) responseBody.get("data");
                return PaymentInitResponse.builder()
                        .success(true)
                        .paymentUrl((String) data.get("payment_url"))
                        .externalReference((String) data.get("payment_token"))
                        .build();
            } else {
                String message = responseBody != null ? (String) responseBody.get("message") : "Unknown error";
                return PaymentInitResponse.builder()
                        .success(false)
                        .message(message)
                        .build();
            }

        } catch (Exception e) {
            log.error("CinetPay payment initiation error", e);
            return PaymentInitResponse.builder()
                    .success(false)
                    .message(e.getMessage())
                    .build();
        }
    }

    @Override
    public PayoutResponse initiatePayout(PayoutRequest request) {
        log.info("Initiating CinetPay payout: ref={}, amount={}, country={}",
                request.getReference(), request.getAmount(), request.getCountry());

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            // CinetPay Transfer API
            Map<String, Object> body = new HashMap<>();
            body.put("apikey", apiKey);
            body.put("site_id", siteId);
            body.put("transaction_id", request.getReference());
            body.put("amount", request.getAmount());
            body.put("receiver", normalizePhone(request.getRecipientPhone(), request.getCountry()));
            body.put("receiver_name", request.getRecipientName());
            body.put("prefix", getOperatorPrefix(request.getOperator(), request.getCountry()));
            body.put("sending_currency", request.getCurrency());
            body.put("payment_method", "MOBILE_MONEY");

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    transferUrl + "/contact/money/send/contact",
                    HttpMethod.POST,
                    entity,
                    Map.class);

            Map<String, Object> responseBody = response.getBody();

            if (responseBody != null && "00".equals(responseBody.get("code"))) {
                Map<String, Object> data = (Map<String, Object>) responseBody.get("data");
                return PayoutResponse.builder()
                        .success(true)
                        .message("Payout initiated successfully")
                        .externalReference((String) data.get("transfer_id"))
                        .transactionReference(request.getReference())
                        .status("PENDING")
                        .build();
            } else {
                String errorMsg = responseBody != null ? (String) responseBody.get("message") : "Payout failed";
                return PayoutResponse.builder()
                        .success(false)
                        .message(errorMsg)
                        .transactionReference(request.getReference())
                        .status("FAILED")
                        .build();
            }
        } catch (Exception e) {
            log.error("CinetPay payout error", e);
            return PayoutResponse.builder()
                    .success(false)
                    .message(e.getMessage())
                    .transactionReference(request.getReference())
                    .status("FAILED")
                    .build();
        }
    }

    @Override
    public PayoutStatusResponse checkPayoutStatus(String reference) {
        log.info("Checking CinetPay payout status: ref={}", reference);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> body = new HashMap<>();
            body.put("apikey", apiKey);
            body.put("site_id", siteId);
            body.put("transaction_id", reference);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    transferUrl + "/check",
                    HttpMethod.POST,
                    entity,
                    Map.class);

            Map<String, Object> responseBody = response.getBody();

            if (responseBody != null && "00".equals(responseBody.get("code"))) {
                Map<String, Object> data = (Map<String, Object>) responseBody.get("data");
                return PayoutStatusResponse.builder()
                        .success(true)
                        .status(mapPayoutStatus((String) data.get("status")))
                        .externalReference((String) data.get("transfer_id"))
                        .amount(((Number) data.get("amount")).longValue())
                        .build();
            }

            return PayoutStatusResponse.builder()
                    .success(false)
                    .message("Payout not found")
                    .build();
        } catch (Exception e) {
            log.error("CinetPay payout status check error", e);
            return PayoutStatusResponse.builder()
                    .success(false)
                    .message(e.getMessage())
                    .build();
        }
    }

    @Override
    public PaymentStatusResponse checkStatus(String transactionReference) {
        log.info("Checking CinetPay transaction status: ref={}", transactionReference);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> body = new HashMap<>();
            body.put("apikey", apiKey);
            body.put("site_id", siteId);
            body.put("transaction_id", transactionReference);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    apiUrl + "/payment/check",
                    HttpMethod.POST,
                    entity,
                    Map.class);

            Map<String, Object> responseBody = response.getBody();

            if (responseBody != null && "00".equals(responseBody.get("code"))) {
                Map<String, Object> data = (Map<String, Object>) responseBody.get("data");
                String status = mapStatus((String) data.get("status"));
                return PaymentStatusResponse.builder()
                        .success(true)
                        .status(status)
                        .message((String) data.get("message"))
                        .build();
            }

            return PaymentStatusResponse.builder()
                    .success(false)
                    .status("UNKNOWN")
                    .message("Transaction not found")
                    .build();

        } catch (Exception e) {
            log.error("CinetPay status check error: ref={}", transactionReference, e);
            return PaymentStatusResponse.builder()
                    .success(false)
                    .status("ERROR")
                    .message(e.getMessage())
                    .build();
        }
    }

    @Override
    public boolean verifyWebhookSignature(String payload, String signature) {
        // CinetPay does not require webhook signature verification in basic
        // implementation
        return true;
    }

    private String normalizePhone(String phone, Country country) {
        String cleaned = phone.replaceAll("[\\s\\-+]", "");
        if (cleaned.startsWith("00")) {
            cleaned = cleaned.substring(2);
        }
        if (cleaned.startsWith(country.getPhonePrefix())) {
            cleaned = cleaned.substring(country.getPhonePrefix().length());
        }
        return cleaned;
    }

    private String getOperatorPrefix(MobileOperator operator, Country country) {
        if (operator == null) {
            // Default prefixes by country
            return switch (country) {
                case COTE_DIVOIRE -> "225";
                case SENEGAL -> "221";
                case MALI -> "223";
                case CAMEROON -> "237";
                case BURKINA_FASO -> "226";
                case BENIN -> "229";
                case TOGO -> "228";
                case GUINEA -> "224";
                case NIGER -> "227";
                case DRC -> "243";
                default -> country.getPhonePrefix();
            };
        }
        return country.getPhonePrefix();
    }

    private String mapStatus(String cinetpayStatus) {
        if (cinetpayStatus == null)
            return "UNKNOWN";
        return switch (cinetpayStatus.toUpperCase()) {
            case "ACCEPTED", "SUCCESS" -> "COMPLETED";
            case "PENDING", "PROCESSING" -> "PENDING";
            case "REFUSED", "FAILED" -> "FAILED";
            case "CANCELLED" -> "CANCELLED";
            default -> "UNKNOWN";
        };
    }

    private String mapPayoutStatus(String status) {
        if (status == null)
            return "UNKNOWN";
        return switch (status.toUpperCase()) {
            case "COMPLETED", "SUCCESS", "VAL" -> "COMPLETED";
            case "PENDING", "PROCESSING", "NEW" -> "PENDING";
            case "FAILED", "REFUSED" -> "FAILED";
            case "CANCELLED" -> "CANCELLED";
            default -> "UNKNOWN";
        };
    }
}
