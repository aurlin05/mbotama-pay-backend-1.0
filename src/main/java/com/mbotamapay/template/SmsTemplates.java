package com.mbotamapay.template;

/**
 * SMS message templates for MbotamaPay notifications
 */
public final class SmsTemplates {

    private SmsTemplates() {
        // Utility class
    }

    // ==================== OTP Templates ====================

    /**
     * OTP verification SMS
     * Params: code, minutes (validity)
     */
    public static final String OTP_VERIFICATION = "MbotamaPay - Votre code de vérification: %s. Valide pendant %d minutes. Ne partagez jamais ce code.";

    /**
     * OTP for login
     */
    public static final String OTP_LOGIN = "MbotamaPay - Code de connexion: %s. Valide %d min. Si vous n'avez pas demandé ce code, ignorez ce message.";

    // ==================== Welcome Templates ====================

    /**
     * Welcome message after registration
     * Params: firstName
     */
    public static final String WELCOME = "Bienvenue sur MbotamaPay, %s! Votre compte est créé. Complétez votre profil pour augmenter vos limites.";

    // ==================== Transaction Templates ====================

    /**
     * Transaction sent notification
     * Params: amount, currency, recipientName
     */
    public static final String TRANSACTION_SENT = "MbotamaPay - Envoi de %,d %s à %s effectué avec succès.";

    /**
     * Transaction received notification
     * Params: amount, currency, senderName
     */
    public static final String TRANSACTION_RECEIVED = "MbotamaPay - Vous avez reçu %,d %s de %s.";

    /**
     * Transaction failed
     * Params: amount, currency
     */
    public static final String TRANSACTION_FAILED = "MbotamaPay - Échec du transfert de %,d %s. Veuillez réessayer ou contacter le support.";

    // ==================== KYC Templates ====================

    /**
     * KYC verification approved
     * Params: levelName
     */
    public static final String KYC_APPROVED = "MbotamaPay - Félicitations! Votre vérification %s est approuvée. Vos nouvelles limites sont actives.";

    /**
     * KYC verification rejected
     * Params: reason
     */
    public static final String KYC_REJECTED = "MbotamaPay - Votre vérification KYC a été rejetée: %s. Veuillez soumettre de nouveaux documents.";

    /**
     * KYC document submitted
     */
    public static final String KYC_SUBMITTED = "MbotamaPay - Documents reçus. Vérification en cours (24-48h). Nous vous notifierons du résultat.";

    // ==================== Security Templates ====================

    /**
     * New device login alert
     * Params: deviceInfo, time
     */
    public static final String NEW_DEVICE_LOGIN = "MbotamaPay - Nouvelle connexion détectée sur %s à %s. Si ce n'est pas vous, contactez le support immédiatement.";

    /**
     * Password changed
     */
    public static final String PASSWORD_CHANGED = "MbotamaPay - Votre mot de passe a été modifié. Si vous n'avez pas fait cette modification, contactez le support.";

    // ==================== Helper Methods ====================

    /**
     * Format OTP verification message
     */
    public static String formatOtpVerification(String code, int validityMinutes) {
        return String.format(OTP_VERIFICATION, code, validityMinutes);
    }

    /**
     * Format OTP login message
     */
    public static String formatOtpLogin(String code, int validityMinutes) {
        return String.format(OTP_LOGIN, code, validityMinutes);
    }

    /**
     * Format welcome message
     */
    public static String formatWelcome(String firstName) {
        return String.format(WELCOME, firstName != null ? firstName : "");
    }

    /**
     * Format transaction sent message
     */
    public static String formatTransactionSent(long amount, String currency, String recipientName) {
        return String.format(TRANSACTION_SENT, amount, currency, recipientName);
    }

    /**
     * Format transaction received message
     */
    public static String formatTransactionReceived(long amount, String currency, String senderName) {
        return String.format(TRANSACTION_RECEIVED, amount, currency, senderName);
    }

    /**
     * Format KYC approved message
     */
    public static String formatKycApproved(String levelName) {
        return String.format(KYC_APPROVED, levelName);
    }

    /**
     * Format KYC rejected message
     */
    public static String formatKycRejected(String reason) {
        return String.format(KYC_REJECTED, reason);
    }
}
