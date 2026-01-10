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
 * PayTech Payment Gateway Integration (Senegal)
 * Documentation: https://paytech.sn/documentation
 * 
 * Supports: Sénégal, Côte d'Ivoire, Mali, Bénin
 */
@Component
@Slf4j
public class PayTechGateway implements PaymentGateway, PayoutGateway {

    private static final String PLATFORM_NAME = "paytech";

    private static final Set<Country> PAYOUT_COUNTRIES = EnumSet.of(
            Country.SENEGAL, Country.COTE_DIVOIRE, Country.MALI);

    @Value("${gateway.paytech.api-url:https://paytech.sn/api}")
    private String apiUrl;

    @Value("${gateway.paytech.api-key:}")
    private String apiKey;

    @Value("${gateway.paytech.api-secret:}")
    private String apiSecret;

    private final RestTemplate restTemplate;

    public PayTechGateway() {
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
        return GatewayType.PAYTECH;
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
        log.info("Initiating PayTech payment: ref={}, amount={}",
                request.getTransactionReference(), request.getAmount());

        try {
            HttpHeaders headers = createHeaders();

            Map<String, Object> body = new HashMap<>();
            body.put("item_name", "MbotamaPay Transfer");
            body.put("item_price", request.getAmount());
            body.put("currency", request.getCurrency());
            body.put("ref_command", request.getTransactionReference());
            body.put("command_name", request.getDescription());
            body.put("ipn_url", request.getCallbackUrl());
            body.put("success_url", request.getReturnUrl());
            body.put("cancel_url", request.getCancelUrl());
            body.put("env", "prod");
            body.put("custom_field", request.getSenderPhone());

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    apiUrl + "/payment/request-payment",
                    HttpMethod.POST,
                    entity,
                    Map.class);

            Map<String, Object> responseBody = response.getBody();

            if (responseBody != null && Integer.valueOf(1).equals(responseBody.get("success"))) {
                return PaymentInitResponse.builder()
                        .success(true)
                        .paymentUrl((String) responseBody.get("redirect_url"))
                        .externalReference((String) responseBody.get("token"))
                        .build();
            } else {
                String errors = responseBody != null ? String.valueOf(responseBody.get("errors")) : "Unknown error";
                return PaymentInitResponse.builder()
                        .success(false)
                        .message(errors)
                        .build();
            }

        } catch (Exception e) {
            log.error("PayTech payment initiation error", e);
            return PaymentInitResponse.builder()
                    .success(false)
                    .message(e.getMessage())
                    .build();
        }
    }

    @Override
    public PayoutResponse initiatePayout(PayoutRequest request) {
        log.info("Initiating PayTech payout: ref={}, amount={}, country={}",
                request.getReference(), request.getAmount(), request.getCountry());

        try {
            HttpHeaders headers = createHeaders();

            Map<String, Object> body = new HashMap<>();
            body.put("amount", request.getAmount());
            body.put("currency", request.getCurrency());
            body.put("ref_command", request.getReference());
            body.put("phone", normalizePhone(request.getRecipientPhone(), request.getCountry()));
            body.put("country_code", request.getCountry().getIsoCode());
            body.put("operator", getOperatorCode(request.getOperator()));
            body.put("full_name", request.getRecipientName());
            body.put("description", request.getDescription());

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    apiUrl + "/payout/mobile-money",
                    HttpMethod.POST,
                    entity,
                    Map.class);

            Map<String, Object> responseBody = response.getBody();

            if (responseBody != null && Integer.valueOf(1).equals(responseBody.get("success"))) {
                return PayoutResponse.builder()
                        .success(true)
                        .message("Payout initiated successfully")
                        .externalReference((String) responseBody.get("reference"))
                        .transactionReference(request.getReference())
                        .status("PENDING")
                        .build();
            } else {
                String errorMsg = responseBody != null ? String.valueOf(responseBody.get("message")) : "Payout failed";
                return PayoutResponse.builder()
                        .success(false)
                        .message(errorMsg)
                        .transactionReference(request.getReference())
                        .status("FAILED")
                        .build();
            }
        } catch (Exception e) {
            log.error("PayTech payout error", e);
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
        log.info("Checking PayTech payout status: ref={}", reference);

        try {
            HttpHeaders headers = createHeaders();

            Map<String, Object> body = new HashMap<>();
            body.put("ref_command", reference);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    apiUrl + "/payout/status",
                    HttpMethod.POST,
                    entity,
                    Map.class);

            Map<String, Object> responseBody = response.getBody();

            if (responseBody != null && Integer.valueOf(1).equals(responseBody.get("success"))) {
                Map<String, Object> data = (Map<String, Object>) responseBody.get("data");
                return PayoutStatusResponse.builder()
                        .success(true)
                        .status(mapPayoutStatus((String) data.get("status")))
                        .externalReference((String) data.get("reference"))
                        .amount(((Number) data.get("amount")).longValue())
                        .build();
            }

            return PayoutStatusResponse.builder()
                    .success(false)
                    .message("Payout not found")
                    .build();
        } catch (Exception e) {
            log.error("PayTech payout status check error", e);
            return PayoutStatusResponse.builder()
                    .success(false)
                    .message(e.getMessage())
                    .build();
        }
    }

    @Override
    public PaymentStatusResponse checkStatus(String transactionReference) {
        log.info("Checking PayTech transaction status: ref={}", transactionReference);

        try {
            HttpHeaders headers = createHeaders();

            Map<String, Object> body = new HashMap<>();
            body.put("ref_command", transactionReference);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    apiUrl + "/payment/check-status",
                    HttpMethod.POST,
                    entity,
                    Map.class);

            Map<String, Object> responseBody = response.getBody();

            if (responseBody != null && Integer.valueOf(1).equals(responseBody.get("success"))) {
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
            log.error("PayTech status check error: ref={}", transactionReference, e);
            return PaymentStatusResponse.builder()
                    .success(false)
                    .status("ERROR")
                    .message(e.getMessage())
                    .build();
        }
    }

    @Override
    public boolean verifyWebhookSignature(String payload, String signature) {
        // TODO: Implement PayTech webhook signature verification
        return true;
    }

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("API_KEY", apiKey);
        headers.set("API_SECRET", apiSecret);
        return headers;
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

    private String getOperatorCode(MobileOperator operator) {
        if (operator == null)
            return "ORANGE_MONEY";
        return switch (operator) {
            case ORANGE_SN, ORANGE_CI, ORANGE_ML -> "ORANGE_MONEY";
            case WAVE_SN, WAVE_CI -> "WAVE";
            case FREE_SN -> "FREE_MONEY";
            default -> "ORANGE_MONEY";
        };
    }

    private String mapStatus(String paytechStatus) {
        if (paytechStatus == null)
            return "UNKNOWN";
        return switch (paytechStatus.toLowerCase()) {
            case "success", "completed", "paid" -> "COMPLETED";
            case "pending", "processing" -> "PENDING";
            case "failed", "error" -> "FAILED";
            case "cancelled" -> "CANCELLED";
            default -> "UNKNOWN";
        };
    }

    private String mapPayoutStatus(String status) {
        if (status == null)
            return "UNKNOWN";
        return switch (status.toLowerCase()) {
            case "completed", "success" -> "COMPLETED";
            case "pending", "processing" -> "PENDING";
            case "failed", "error" -> "FAILED";
            case "cancelled" -> "CANCELLED";
            default -> "UNKNOWN";
        };
    }
}
