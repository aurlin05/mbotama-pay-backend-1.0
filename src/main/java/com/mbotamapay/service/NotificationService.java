package com.mbotamapay.service;

import com.mbotamapay.entity.DeviceToken;
import com.mbotamapay.entity.Transaction;
import com.mbotamapay.entity.User;
import com.mbotamapay.repository.DeviceTokenRepository;
import com.mbotamapay.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Notification Service
 * Handles push notifications to mobile devices
 * Ready for Firebase Cloud Messaging integration
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final DeviceTokenRepository deviceTokenRepository;
    private final UserRepository userRepository;

    /**
     * Register a device token for push notifications
     */
    @Transactional
    public void registerDeviceToken(Long userId, String token, String deviceType, String deviceName) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Check if token already exists
        if (deviceTokenRepository.existsByToken(token)) {
            log.debug("Device token already registered: {}", maskToken(token));
            return;
        }

        DeviceToken deviceToken = DeviceToken.builder()
                .user(user)
                .token(token)
                .deviceType(deviceType)
                .deviceName(deviceName)
                .build();

        deviceTokenRepository.save(deviceToken);
        log.info("Device token registered for user {}: {}", userId, maskToken(token));
    }

    /**
     * Remove a device token
     */
    @Transactional
    public void removeDeviceToken(String token) {
        deviceTokenRepository.deactivateToken(token);
        log.info("Device token deactivated: {}", maskToken(token));
    }

    /**
     * Send notification to a user
     */
    @Async
    public void sendNotification(Long userId, String title, String body) {
        List<DeviceToken> tokens = deviceTokenRepository.findByUserIdAndIsActiveTrue(userId);

        if (tokens.isEmpty()) {
            log.debug("No active device tokens for user {}", userId);
            return;
        }

        for (DeviceToken deviceToken : tokens) {
            try {
                sendPushNotification(deviceToken.getToken(), title, body);

                // Update last used timestamp
                deviceToken.setLastUsedAt(LocalDateTime.now());
                deviceTokenRepository.save(deviceToken);
            } catch (Exception e) {
                log.error("Failed to send notification to token {}: {}",
                        maskToken(deviceToken.getToken()), e.getMessage());

                // Deactivate invalid tokens
                if (isInvalidTokenError(e)) {
                    deviceToken.setIsActive(false);
                    deviceTokenRepository.save(deviceToken);
                }
            }
        }
    }

    /**
     * Send transaction notification
     */
    @Async
    public void sendTransactionNotification(Transaction transaction, String status) {
        String title;
        String body;

        switch (status) {
            case "COMPLETED" -> {
                title = "Transaction réussie 🎉";
                body = String.format("Votre envoi de %d %s à %s a été effectué avec succès.",
                        transaction.getAmount(), transaction.getCurrency(), transaction.getRecipientName());
            }
            case "FAILED" -> {
                title = "Transaction échouée ❌";
                body = String.format("Votre envoi de %d %s à %s a échoué. Veuillez réessayer.",
                        transaction.getAmount(), transaction.getCurrency(), transaction.getRecipientName());
            }
            case "REFUNDED" -> {
                title = "Remboursement effectué 💰";
                body = String.format("Votre remboursement de %d %s a été crédité sur votre compte.",
                        transaction.getAmount(), transaction.getCurrency());
            }
            default -> {
                title = "Mise à jour de transaction";
                body = String.format("Le statut de votre transaction est maintenant: %s", status);
            }
        }

        sendNotification(transaction.getSender().getId(), title, body);
    }

    /**
     * Send KYC status notification
     */
    @Async
    public void sendKycNotification(Long userId, String status) {
        String title;
        String body;

        switch (status) {
            case "APPROVED" -> {
                title = "Vérification approuvée ✅";
                body = "Félicitations ! Votre vérification d'identité a été approuvée. Vous pouvez maintenant effectuer des transactions.";
            }
            case "REJECTED" -> {
                title = "Vérification refusée ⚠️";
                body = "Votre vérification d'identité n'a pas pu être validée. Veuillez soumettre de nouveaux documents.";
            }
            default -> {
                title = "Mise à jour KYC";
                body = "Le statut de votre vérification a été mis à jour.";
            }
        }

        sendNotification(userId, title, body);
    }

    /**
     * Send OTP notification (backup for SMS)
     */
    @Async
    public void sendOtpNotification(Long userId, String otp) {
        String title = "Code de vérification";
        String body = String.format("Votre code de vérification MbotamaPay est: %s. Il expire dans 5 minutes.", otp);

        sendNotification(userId, title, body);
    }

    /**
     * Send push notification via Firebase (placeholder)
     * TODO: Implement actual Firebase integration when credentials are available
     */
    private void sendPushNotification(String token, String title, String body) {
        // Firebase implementation would go here:
        // FirebaseMessaging.getInstance().send(Message.builder()
        // .setToken(token)
        // .setNotification(Notification.builder()
        // .setTitle(title)
        // .setBody(body)
        // .build())
        // .build());

        // For now, just log the notification
        log.info("Push notification sent to {}: {} - {}", maskToken(token), title, body);
    }

    /**
     * Check if error is due to invalid token
     */
    private boolean isInvalidTokenError(Exception e) {
        String message = e.getMessage();
        return message != null &&
                (message.contains("InvalidRegistration") ||
                        message.contains("NotRegistered") ||
                        message.contains("invalid-token"));
    }

    /**
     * Mask token for logging
     */
    private String maskToken(String token) {
        if (token == null || token.length() < 10) {
            return "***";
        }
        return token.substring(0, 5) + "..." + token.substring(token.length() - 5);
    }
}
