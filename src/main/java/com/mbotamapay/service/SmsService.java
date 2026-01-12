package com.mbotamapay.service;

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
 * Notification Service using Infobip API
 * Sends OTP and notifications via Email (SMS not available on trial)
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

    @Value("${sms.infobip.sender-email:aurlinmika5@selfserve.worlds-connected.co}")
    private String senderEmail;

    private final RestTemplate restTemplate = new RestTemplate();

    @PostConstruct
    public void init() {
        if (smsEnabled && !infobipApiKey.isEmpty()) {
            log.info("✅ Notification service initialized with Infobip Email");
        } else {
            log.info("📱 Notification service in DEV mode - messages logged only");
        }
    }

    @Async
    public void sendOtp(String phoneNumber, String code) {
        log.info("========================================");
        log.info("📱 OTP VERIFICATION");
        log.info("📞 Phone: {}", phoneNumber);
        log.info("🔑 Code: {}", code);
        log.info("⏱️ Valid: {} minutes", otpExpirationSeconds / 60);
        log.info("========================================");
    }

    @Async
    public void sendLoginOtp(String phoneNumber, String code) {
        log.info("========================================");
        log.info("📱 OTP LOGIN");
        log.info("📞 Phone: {}", phoneNumber);
        log.info("🔑 Code: {}", code);
        log.info("⏱️ Valid: {} minutes", otpExpirationSeconds / 60);
        log.info("========================================");
    }

    @Async
    public void sendOtpToEmail(String email, String code) {
        String subject = "MbotamaPay - Code de vérification";
        String text = "Votre code de vérification MbotamaPay est: " + code + "\n\nValide pendant " + (otpExpirationSeconds / 60) + " minutes.\nNe partagez jamais ce code.";
        sendEmail(email, subject, text);
    }

    @Async
    public void sendWelcome(String phoneNumber, String firstName) {
        log.info("📱 Welcome SMS for {} ({})", firstName, phoneNumber);
    }

    @Async
    public void sendWelcomeEmail(String email, String firstName) {
        String subject = "Bienvenue sur MbotamaPay!";
        String text = "Bonjour " + firstName + ",\n\nBienvenue sur MbotamaPay! Votre compte a été créé avec succès.\n\nL'équipe MbotamaPay";
        sendEmail(email, subject, text);
    }

    @Async
    public void sendTransactionSent(String phoneNumber, long amount, String currency, String recipientName) {
        log.info("📱 Transaction sent notification for {}: {} {} to {}", phoneNumber, amount, currency, recipientName);
    }

    @Async
    public void sendTransactionReceived(String phoneNumber, long amount, String currency, String senderName) {
        log.info("📱 Transaction received notification for {}: {} {} from {}", phoneNumber, amount, currency, senderName);
    }

    @Async
    public void sendKycApproved(String phoneNumber, String levelName) {
        log.info("📱 KYC approved for {}: {}", phoneNumber, levelName);
    }

    @Async
    public void sendKycRejected(String phoneNumber, String reason) {
        log.info("📱 KYC rejected for {}: {}", phoneNumber, reason);
    }

    @Async
    public void sendKycSubmitted(String phoneNumber) {
        log.info("📱 KYC submitted for {}", phoneNumber);
    }

    @Async
    public void sendSms(String phoneNumber, String message) {
        log.info("📱 SMS to {}: {}", phoneNumber, message);
    }

    /**
     * Send email via Infobip Email API
     */
    public void sendEmail(String toEmail, String subject, String text) {
        log.info("📧 Email to {}: {}", toEmail, subject);

        if (!smsEnabled || infobipApiKey.isEmpty()) {
            log.debug("Infobip disabled - email logged only");
            return;
        }

        try {
            String url = infobipBaseUrl + "/email/4/messages";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            headers.set("Authorization", "App " + infobipApiKey);

            Map<String, Object> destination = new HashMap<>();
            destination.put("to", List.of(Map.of("destination", toEmail)));

            Map<String, Object> content = new HashMap<>();
            content.put("subject", subject);
            content.put("text", text);

            Map<String, Object> message = new HashMap<>();
            message.put("destinations", List.of(destination));
            message.put("sender", senderEmail);
            message.put("content", content);

            Map<String, Object> body = new HashMap<>();
            body.put("messages", List.of(message));

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

            log.info("📤 Sending email via Infobip to {}", toEmail);

            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("✅ Email sent via Infobip to {}. Response: {}", toEmail, response.getBody());
            } else {
                log.error("❌ Infobip error {}: {}", response.getStatusCode(), response.getBody());
            }
        } catch (Exception e) {
            log.error("❌ Failed to send email to {}: {}", toEmail, e.getMessage());
        }
    }
}
