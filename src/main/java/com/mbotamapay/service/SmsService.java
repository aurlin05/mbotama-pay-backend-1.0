package com.mbotamapay.service;

import com.mbotamapay.template.SmsTemplates;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * SMS Service for sending OTP codes and notifications
 * Currently in development mode - logs SMS only
 * 
 * TODO: Integrate with Orange SMS API or other African SMS provider
 */
@Service
@Slf4j
public class SmsService {

    @Value("${otp.expiration:300}")
    private int otpExpirationSeconds;

    @Value("${sms.enabled:false}")
    private boolean smsEnabled;

    @PostConstruct
    public void init() {
        if (smsEnabled) {
            log.info("✅ SMS service initialized (provider to be configured)");
        } else {
            log.info("📱 SMS service in DEV mode - messages will be logged only");
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
     * Internal method to send SMS
     * Currently logs only - integrate with SMS provider here
     */
    private void doSendSms(String phoneNumber, String messageText) {
        log.info("========================================");
        log.info("📱 SMS to {}", phoneNumber);
        log.info("📝 Message: {}", messageText);
        log.info("========================================");

        if (smsEnabled) {
            // TODO: Integrate with SMS provider (Orange, Infobip, etc.)
            log.debug("SMS provider not configured - message logged only");
        }
    }
}
