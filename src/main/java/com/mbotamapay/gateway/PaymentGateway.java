package com.mbotamapay.gateway;

import com.mbotamapay.gateway.dto.PaymentInitRequest;
import com.mbotamapay.gateway.dto.PaymentInitResponse;
import com.mbotamapay.gateway.dto.PaymentStatusResponse;

/**
 * Payment Gateway interface for all payment providers
 */
public interface PaymentGateway {

    /**
     * Get the gateway platform name
     */
    String getPlatformName();

    /**
     * Check if this gateway supports the given platform
     */
    boolean supports(String platform);

    /**
     * Initialize a payment
     */
    PaymentInitResponse initiatePayment(PaymentInitRequest request);

    /**
     * Check payment status
     */
    PaymentStatusResponse checkStatus(String transactionReference);

    /**
     * Verify webhook signature
     */
    boolean verifyWebhookSignature(String payload, String signature);
}
