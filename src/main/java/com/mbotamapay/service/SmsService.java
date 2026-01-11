package com.mbotamapay.service;

import com.mbotamapay.template.SmsTemplates;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SMS Service using Infobip API
 * Documentation: https://www.infobip.com/docs/api/channels/sms
 */
@Service
@Slf4j
public class SmsService {

    @Value("${otp.expiration:300}")
    private int otpExpirationSeconds;

    @Value("${sms.enabled:false}")
    private boolean smsEnabled;

    @Value("${sms.infobip.api-key:}")
    private String infobipApiKey;

    @Value("${sms.infobip.base-url:https://x1r9qq.api.infobip.com}")
    private String infobipBaseUrl;

    @Value("${sms.infobip.sender:MbotamaPay}")
    private String senderName;

    private final RestTemplate restTemplate = new RestTemplate();

    @PostConstruct
    public void init() {
        if (smsEnabled && !infobipApiKey.isEmpty()) {
            log.info("✅ SMS service initialized with Infobip");
        } else {
            log.info("📱 SMS service in DEV mode - messages will be logged only");
        }
    }

    @Async
    public void sendOtp(String phoneNumber, String code) {
        String message = SmsTemplates.formatOtpVerification(code, otpExpirationSeconds / 60);
        doSendSms(phoneNumber, message);
    }

    @Async
    public void sendLoginOtp(String phoneNumber, String code) {
        String message = SmsTemplates.formatOtpLogin(code, otpExpirationSeconds / 60);
        doSendSms(phoneNumber, message);
    }

    @Async
    public void sendWelcome(String phoneNumber, String firstName) {
        String message = SmsTemplates.formatWelcome(firstName);
        doSendSms(phoneNumber, message);
    }

    @Async
    public void sendTransactionSent(String phoneNumber, long amount, String currency, String recipientName) {
        String message = SmsTemplates.formatTransactionSent(amount, currency, recipientName);
        doSendSms(phoneNumber, message);
    }

    @Async
    public void sendTransactionReceived(String phoneNumber, long amount, String currency, String senderName) {
        String message = SmsTemplates.formatTransactionReceived(amount, currency, senderName);
        doSendSms(phoneNumber, message);
    }

    @Async
    public void sendKycApproved(String phoneNumber, String levelName) {
        String message = SmsTemplates.formatKycApproved(levelName);
        doSendSms(phoneNumber, message);
    }

    @Async
    public void sendKycRejected(String phoneNumber, String reason) {
        String message = SmsTemplates.formatKycRejected(reason);
        doSendSms(phoneNumber, message);
    }

    @Async
    public void sendKycSubmitted(String phoneNumber) {
        doSendSms(phoneNumber, SmsTemplates.KYC_SUBMITTED);
    }

    @Async
    public void sendSms(String phoneNumber, String message) {
        doSendSms(phoneNumber, message);
    }

    private void doSendSms(String phoneNumber, String messageText) {
        // Always log
        log.info("📱 SMS to {}: {}", phoneNumber, messageText);

        if (!smsEnabled || infobipApiKey.isEmpty()) {
            log.debug("SMS disabled - message logged only");
            return;
        }

        try {
            String url = infobipBaseUrl + "/sms/2/text/advanced";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "App " + infobipApiKey);

            Map<String, Object> destination = new HashMap<>();
            destination.put("to", formatPhoneNumber(phoneNumber));

            Map<String, Object> message = new HashMap<>();
            message.put("from", senderName);
            message.put("destinations", List.of(destination));
            message.put("text", messageText);

            Map<String, Object> body = new HashMap<>();
            body.put("messages", List.of(message));

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("✅ SMS sent via Infobip to {}", phoneNumber);
            } else {
                log.error("❌ Infobip error: {}", response.getBody());
            }
        } catch (Exception e) {
            log.error("❌ Failed to send SMS to {}: {}", phoneNumber, e.getMessage());
        }
    }

    private String formatPhoneNumber(String phone) {
        String cleaned = phone.replaceAll("[\\s\\-+]", "");
        if (!cleaned.startsWith("00") && !cleaned.startsWith("+")) {
            return cleaned;
        }
        return cleaned.startsWith("00") ? cleaned.substring(2) : cleaned;
    }
}
