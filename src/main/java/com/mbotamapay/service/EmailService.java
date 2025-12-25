package com.mbotamapay.service;

import com.mbotamapay.entity.Transaction;
import com.mbotamapay.entity.User;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.time.LocalDateTime;

/**
 * Email Service for sending templated emails
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;
    private final SendGridEmailClient sendGridEmailClient;

    @Value("${spring.mail.username:noreply@mbotamapay.com}")
    private String fromEmail;

    @Value("${app.name:MbotamaPay}")
    private String appName;

    @Value("${otp.expiration:300}")
    private int otpExpirationSeconds;
    
    @Value("${email.provider:smtp}")
    private String emailProvider;

    /**
     * Send OTP verification email
     */
    @Async
    public void sendOtpEmail(String toEmail, String otpCode, String firstName) {
        Context context = new Context();
        context.setVariable("otpCode", otpCode);
        context.setVariable("firstName", firstName);
        context.setVariable("validityMinutes", otpExpirationSeconds / 60);

        String htmlContent = templateEngine.process("email/otp-verification", context);
        sendEmail(toEmail, "Votre code de vérification MbotamaPay", htmlContent);
    }

    /**
     * Send welcome email after successful registration
     */
    @Async
    public void sendWelcomeEmail(User user) {
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            log.debug("No email for user {}, skipping welcome email", user.getPhoneNumber());
            return;
        }

        Context context = new Context();
        context.setVariable("firstName", user.getFirstName());

        String htmlContent = templateEngine.process("email/welcome", context);
        sendEmail(user.getEmail(), "Bienvenue sur MbotamaPay!", htmlContent);
    }

    /**
     * Send KYC approval email
     */
    @Async
    public void sendKycApprovedEmail(User user) {
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            return;
        }

        Context context = new Context();
        context.setVariable("firstName", user.getFirstName());
        context.setVariable("kycLevel", user.getKycLevel().getDisplayName());
        context.setVariable("transactionLimit", formatAmount(user.getTransactionLimit()));
        context.setVariable("dailyLimit", formatAmount(user.getTransactionLimit() * 2)); // Example daily limit

        String htmlContent = templateEngine.process("email/kyc-approved", context);
        sendEmail(user.getEmail(), "Votre vérification KYC est approuvée!", htmlContent);
    }

    /**
     * Send KYC rejection email
     */
    @Async
    public void sendKycRejectedEmail(User user, String rejectionReason) {
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            return;
        }

        Context context = new Context();
        context.setVariable("firstName", user.getFirstName());
        context.setVariable("rejectionReason", rejectionReason);

        String htmlContent = templateEngine.process("email/kyc-rejected", context);
        sendEmail(user.getEmail(), "Action requise - Vérification KYC", htmlContent);
    }

    /**
     * Send transaction notification email
     */
    @Async
    public void sendTransactionEmail(User user, Transaction transaction, boolean isSender) {
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            return;
        }

        Context context = new Context();
        context.setVariable("status", transaction.getStatus().name());
        context.setVariable("amount", transaction.getAmount());
        context.setVariable("fee", transaction.getFee());
        context.setVariable("currency", transaction.getCurrency());
        context.setVariable("isSender", isSender);
        context.setVariable("senderName", transaction.getSenderName());
        context.setVariable("senderPhone", maskPhone(transaction.getSenderPhone()));
        context.setVariable("recipientName", transaction.getRecipientName());
        context.setVariable("recipientPhone", maskPhone(transaction.getRecipientPhone()));
        context.setVariable("platform", transaction.getPlatform());
        context.setVariable("transactionDate", transaction.getCreatedAt());
        context.setVariable("description", transaction.getDescription());
        context.setVariable("reference", transaction.getExternalReference() != null
                ? transaction.getExternalReference()
                : "TXN-" + transaction.getId());

        String htmlContent = templateEngine.process("email/transaction-notification", context);
        String subject = isSender
                ? "Transfert envoyé - " + formatAmount(transaction.getAmount()) + " " + transaction.getCurrency()
                : "Vous avez reçu - " + formatAmount(transaction.getAmount()) + " " + transaction.getCurrency();

        sendEmail(user.getEmail(), subject, htmlContent);
    }

    /**
     * Send email with HTML content
     */
    private void sendEmail(String to, String subject, String htmlContent) {
        if ("sendgrid".equalsIgnoreCase(emailProvider)) {
            boolean ok = sendGridEmailClient.sendHtml(fromEmail, appName, to, subject, htmlContent);
            if (ok) {
                log.info("Email sent successfully to: {}", to);
                return;
            }
            log.warn("SendGrid send failed for {}, falling back to SMTP", to);
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail, appName);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlContent, true);
            mailSender.send(message);
            log.info("Email sent successfully to: {}", to);
        } catch (MessagingException e) {
            log.error("Failed to send email to {}", to, e);
        } catch (Exception e) {
            log.error("Unexpected error sending email to {}", to, e);
        }
    }

    /**
     * Format amount with thousands separator
     */
    private String formatAmount(long amount) {
        return String.format("%,d", amount);
    }

    /**
     * Mask phone number for privacy (show last 4 digits)
     */
    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) {
            return phone;
        }
        return "***" + phone.substring(phone.length() - 4);
    }
}
