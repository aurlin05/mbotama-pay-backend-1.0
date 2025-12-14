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
 * FeexPay Payment Gateway Integration
 * Documentation: https://docs.feexpay.me
 * 
 * Supports: Benin, Togo, Côte d'Ivoire, Sénégal, Congo-Brazzaville, Burkina
 * Faso
 */
@Component
@Slf4j
public class FeexPayGateway implements PaymentGateway, PayoutGateway {

    private static final String PLATFORM_NAME = "feexpay";

    private static final Set<Country> PAYOUT_COUNTRIES = EnumSet.of(
            Country.BENIN, Country.TOGO, Country.COTE_DIVOIRE,
            Country.SENEGAL, Country.CONGO_BRAZZAVILLE, Country.BURKINA_FASO);

    @Value("${gateway.feexpay.api-url:https://api.feexpay.me}")
    private String apiUrl;

    @Value("${gateway.feexpay.api-key:}")
    private String apiKey;

    @Value("${gateway.feexpay.shop-id:}")
    private String shopId;

    private final RestTemplate restTemplate;

    public FeexPayGateway() {
        this.restTemplate = new RestTemplate();
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

    @Override
    public Set<Country> getSupportedPayoutCountries() {
        return PAYOUT_COUNTRIES;
    }

    @Override
    public boolean supportsPayoutTo(Country country) {
        return PAYOUT_COUNTRIES.contains(country);
    }

    @Override
    public PaymentInitResponse initiatePayment(PaymentInitRequest request) {
        log.info("Initiating FeeXPay payment: ref={}, amount={}",
                request.getTransactionReference(), request.getAmount());

        try {
            HttpHeaders headers = createHeaders();

            Map<String, Object> body = new HashMap<>();
            body.put("shop_id", shopId);
            body.put("amount", request.getAmount());
            body.put("currency", request.getCurrency());
            body.put("custom_id", request.getTransactionReference());
            body.put("callback_url", request.getCallbackUrl());
            body.put("return_url", request.getReturnUrl());
            body.put("cancel_url", request.getCancelUrl());
            body.put("customer_phone", request.getSenderPhone());
            body.put("description", request.getDescription());

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    apiUrl + "/api/transactions/public/invoice",
                    HttpMethod.POST,
                    entity,
                    Map.class);

            Map<String, Object> responseBody = response.getBody();

            if (responseBody != null && "success".equals(responseBody.get("status"))) {
                return PaymentInitResponse.builder()
                        .success(true)
                        .paymentUrl((String) responseBody.get("payment_url"))
                        .externalReference((String) responseBody.get("reference"))
                        .build();
            } else {
                return PaymentInitResponse.builder()
                        .success(false)
                        .message("FeeXPay payment initiation failed")
                        .build();
            }
        } catch (Exception e) {
            log.error("FeeXPay payment initiation error", e);
            return PaymentInitResponse.builder()
                    .success(false)
                    .message(e.getMessage())
                    .build();
        }
    }

    @Override
    public PayoutResponse initiatePayout(PayoutRequest request) {
        log.info("Initiating FeeXPay payout: ref={}, amount={}, country={}",
                request.getReference(), request.getAmount(), request.getCountry());

        try {
            HttpHeaders headers = createHeaders();
            String endpoint = getPayoutEndpoint(request.getCountry(), request.getOperator());

            Map<String, Object> body = new HashMap<>();
            body.put("phone", normalizePhone(request.getRecipientPhone(), request.getCountry()));
            body.put("amount", request.getAmount());
            body.put("full_name", request.getRecipientName());
            body.put("shop_id", shopId);
            body.put("custom_id", request.getReference());
            body.put("description", request.getDescription());

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    apiUrl + endpoint,
                    HttpMethod.POST,
                    entity,
                    Map.class);

            Map<String, Object> responseBody = response.getBody();

            if (responseBody != null && "success".equals(responseBody.get("status"))) {
                return PayoutResponse.builder()
                        .success(true)
                        .message("Payout initiated successfully")
                        .externalReference((String) responseBody.get("reference"))
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
            log.error("FeeXPay payout error", e);
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
        log.info("Checking FeeXPay payout status: ref={}", reference);

        try {
            HttpHeaders headers = createHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    apiUrl + "/api/transactions/public/" + reference,
                    HttpMethod.GET,
                    entity,
                    Map.class);

            Map<String, Object> responseBody = response.getBody();

            if (responseBody != null) {
                return PayoutStatusResponse.builder()
                        .success(true)
                        .status(mapStatus((String) responseBody.get("status")))
                        .externalReference(reference)
                        .amount(((Number) responseBody.get("amount")).longValue())
                        .build();
            }

            return PayoutStatusResponse.builder()
                    .success(false)
                    .message("Payout not found")
                    .build();
        } catch (Exception e) {
            log.error("FeeXPay payout status check error", e);
            return PayoutStatusResponse.builder()
                    .success(false)
                    .message(e.getMessage())
                    .build();
        }
    }

    @Override
    public PaymentStatusResponse checkStatus(String transactionReference) {
        log.info("Checking FeeXPay transaction status: ref={}", transactionReference);

        try {
            HttpHeaders headers = createHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    apiUrl + "/api/transactions/public/" + transactionReference,
                    HttpMethod.GET,
                    entity,
                    Map.class);

            Map<String, Object> responseBody = response.getBody();

            if (responseBody != null) {
                String status = mapStatus((String) responseBody.get("status"));
                return PaymentStatusResponse.builder()
                        .success(true)
                        .status(status)
                        .message((String) responseBody.get("message"))
                        .build();
            }

            return PaymentStatusResponse.builder()
                    .success(false)
                    .status("UNKNOWN")
                    .message("Transaction not found")
                    .build();
        } catch (Exception e) {
            log.error("FeeXPay status check error: ref={}", transactionReference, e);
            return PaymentStatusResponse.builder()
                    .success(false)
                    .status("ERROR")
                    .message(e.getMessage())
                    .build();
        }
    }

    @Override
    public boolean verifyWebhookSignature(String payload, String signature) {
        // TODO: Implement FeeXPay webhook signature verification
        return true;
    }

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);
        return headers;
    }

    private String getPayoutEndpoint(Country country, MobileOperator operator) {
        return switch (country) {
            case BENIN ->
                operator == MobileOperator.MTN_BJ ? "/api/payouts/public/mtn_bj" : "/api/payouts/public/moov_bj";
            case SENEGAL ->
                operator == MobileOperator.ORANGE_SN ? "/api/payouts/public/orange_sn" : "/api/payouts/public/free_sn";
            case COTE_DIVOIRE -> {
                if (operator == MobileOperator.ORANGE_CI)
                    yield "/api/payouts/public/orange_ci";
                if (operator == MobileOperator.MTN_CI)
                    yield "/api/payouts/public/mtn_ci";
                if (operator == MobileOperator.WAVE_CI)
                    yield "/api/payouts/public/wave_ci";
                yield "/api/payouts/public/moov_ci";
            }
            case TOGO -> "/api/payouts/public/togocom";
            case BURKINA_FASO -> "/api/payouts/public/orange_bf";
            case CONGO_BRAZZAVILLE -> "/api/payouts/public/mtn_cg";
            default -> "/api/payouts/public/default";
        };
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

    @Override
    public com.mbotamapay.dto.verification.MobileMoneyVerificationResult verifySubscriber(
            String phoneNumber, Country country, MobileOperator operator) {
        log.info("FeeXPay subscriber verification: phone={}, country={}", phoneNumber, country);

        try {
            HttpHeaders headers = createHeaders();

            Map<String, Object> body = new HashMap<>();
            body.put("phone", normalizePhone(phoneNumber, country));
            body.put("shop_id", shopId);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    apiUrl + "/api/check-subscriber",
                    HttpMethod.POST,
                    entity,
                    Map.class);

            Map<String, Object> responseBody = response.getBody();

            if (responseBody != null && "success".equals(responseBody.get("status"))) {
                Map<String, Object> data = (Map<String, Object>) responseBody.get("data");
                boolean isActive = data != null && Boolean.TRUE.equals(data.get("is_active"));
                String accountName = data != null ? (String) data.get("name") : null;

                return com.mbotamapay.dto.verification.MobileMoneyVerificationResult.builder()
                        .valid(isActive)
                        .apiVerified(true)
                        .accountName(accountName)
                        .mobileMoneySupported(true)
                        .build();
            }

            return com.mbotamapay.dto.verification.MobileMoneyVerificationResult.builder()
                    .valid(false)
                    .apiVerified(true)
                    .errorMessage("Compte Mobile Money non trouvé")
                    .build();

        } catch (Exception e) {
            log.warn("FeeXPay subscriber verification failed: {}", e.getMessage());
            return null; // Fallback to local validation
        }
    }

    private String mapStatus(String feexpayStatus) {
        if (feexpayStatus == null)
            return "UNKNOWN";
        return switch (feexpayStatus.toLowerCase()) {
            case "paid", "success", "completed" -> "COMPLETED";
            case "pending", "processing" -> "PENDING";
            case "failed", "error" -> "FAILED";
            case "cancelled", "expired" -> "CANCELLED";
            default -> "UNKNOWN";
        };
    }
}
