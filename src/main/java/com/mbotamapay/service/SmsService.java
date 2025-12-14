package com.mbotamapay.service;

import com.mbotamapay.template.SmsTemplates;
import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * SMS Service for sending OTP codes and notifications via Twilio
 */
@Service
@Slf4j
public class SmsService {

    @Value("${otp.expiration:300}")
    private int otpExpirationSeconds;

    @Value("${twilio.account-sid:}")
    private String accountSid;

    @Value("${twilio.auth-token:}")
    private String authToken;

    @Value("${twilio.phone-number:}")
    private String twilioPhoneNumber;

    @Value("${twilio.enabled:false}")
    private boolean twilioEnabled;

    @PostConstruct
    public void init() {
        if (twilioEnabled && !accountSid.isEmpty() && !authToken.isEmpty()) {
            Twilio.init(accountSid, authToken);
            log.info("✅ Twilio SMS service initialized successfully");
        } else {
            log.warn("⚠️ Twilio SMS service is DISABLED - SMS will be logged only");
        }
    }

    /**
     * Send OTP via SMS
     */
    @Async
    public void sendOtp(String phoneNumber, String code) {
        String message = SmsTemplates.formatOtpVerification(code, otpExpirationSeconds / 60);
        doSendSms(phoneNumber, message);
    }

    /**
     * Send OTP for login
     */
    @Async
    public void sendLoginOtp(String phoneNumber, String code) {
        String message = SmsTemplates.formatOtpLogin(code, otpExpirationSeconds / 60);
        doSendSms(phoneNumber, message);
    }

    /**
     * Send welcome SMS
     */
    @Async
    public void sendWelcome(String phoneNumber, String firstName) {
        String message = SmsTemplates.formatWelcome(firstName);
        doSendSms(phoneNumber, message);
    }

    /**
     * Send transaction sent notification
     */
    @Async
    public void sendTransactionSent(String phoneNumber, long amount, String currency, String recipientName) {
        String message = SmsTemplates.formatTransactionSent(amount, currency, recipientName);
        doSendSms(phoneNumber, message);
    }

    /**
     * Send transaction received notification
     */
    @Async
    public void sendTransactionReceived(String phoneNumber, long amount, String currency, String senderName) {
        String message = SmsTemplates.formatTransactionReceived(amount, currency, senderName);
        doSendSms(phoneNumber, message);
    }

    /**
     * Send KYC approved notification
     */
    @Async
    public void sendKycApproved(String phoneNumber, String levelName) {
        String message = SmsTemplates.formatKycApproved(levelName);
        doSendSms(phoneNumber, message);
    }

    /**
     * Send KYC rejected notification
     */
    @Async
    public void sendKycRejected(String phoneNumber, String reason) {
        String message = SmsTemplates.formatKycRejected(reason);
        doSendSms(phoneNumber, message);
    }

    /**
     * Send KYC submitted confirmation
     */
    @Async
    public void sendKycSubmitted(String phoneNumber) {
        doSendSms(phoneNumber, SmsTemplates.KYC_SUBMITTED);
    }

    /**
     * Send custom SMS message
     */
    @Async
    public void sendSms(String phoneNumber, String message) {
        doSendSms(phoneNumber, message);
    }

    /**
     * Internal method to send SMS via Twilio
     */
    private void doSendSms(String phoneNumber, String messageText) {
        // Always log the SMS for debugging
        log.info("========================================");
        log.info("📱 SMS to {}", phoneNumber);
        log.info("📝 Message: {}", messageText);
        log.info("========================================");

        if (twilioEnabled && !accountSid.isEmpty() && !authToken.isEmpty() && !twilioPhoneNumber.isEmpty()) {
            try {
                Message message = Message.creator(
                        new PhoneNumber(phoneNumber),
                        new PhoneNumber(twilioPhoneNumber),
                        messageText).create();

                log.info("✅ SMS sent successfully via Twilio. SID: {}", message.getSid());
            } catch (Exception e) {
                log.error("❌ Failed to send SMS via Twilio to {}: {}", phoneNumber, e.getMessage());
            }
        } else {
            log.debug("Twilio is disabled, SMS logged only (not sent)");
        }
    }
}
