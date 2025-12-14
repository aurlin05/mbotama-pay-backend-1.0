package com.mbotamapay.controller;

import com.mbotamapay.dto.ApiResponse;
import com.mbotamapay.entity.Transaction;
import com.mbotamapay.entity.enums.TransactionStatus;
import com.mbotamapay.gateway.GatewayService;
import com.mbotamapay.gateway.PaymentGateway;
import com.mbotamapay.gateway.dto.PaymentInitRequest;
import com.mbotamapay.gateway.dto.PaymentInitResponse;
import com.mbotamapay.gateway.dto.PaymentStatusResponse;
import com.mbotamapay.repository.TransactionRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Payment Gateway Controller
 * Handles payment initiation, callbacks, and status checks
 */
@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
@Tag(name = "Payments", description = "Payment gateway APIs")
@Slf4j
public class PaymentController {

    private final GatewayService gatewayService;
    private final TransactionRepository transactionRepository;

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    @Value("${app.frontend-url:http://localhost:3000}")
    private String frontendUrl;

    @GetMapping("/platforms")
    @Operation(summary = "Get available platforms", description = "Returns list of supported payment platforms")
    public ResponseEntity<ApiResponse<List<String>>> getAvailablePlatforms() {
        List<String> platforms = gatewayService.getAvailablePlatforms();
        return ResponseEntity.ok(ApiResponse.success(platforms));
    }

    @PostMapping("/initiate/{transactionId}")
    @Operation(summary = "Initiate payment", description = "Initiates payment with the selected platform")
    public ResponseEntity<ApiResponse<PaymentInitResponse>> initiatePayment(
            @PathVariable Long transactionId) {

        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new RuntimeException("Transaction non trouvée"));

        if (transaction.getStatus() != TransactionStatus.PENDING) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Cette transaction ne peut plus être payée"));
        }

        PaymentGateway gateway = gatewayService.getGateway(transaction.getPlatform());

        PaymentInitRequest request = PaymentInitRequest.builder()
                .transactionReference(transaction.getExternalReference())
                .amount(transaction.getTotalAmount())
                .currency(transaction.getCurrency())
                .senderPhone(transaction.getSenderPhone())
                .senderName(transaction.getSenderName())
                .recipientPhone(transaction.getRecipientPhone())
                .recipientName(transaction.getRecipientName())
                .description(
                        transaction.getDescription() != null ? transaction.getDescription() : "Paiement MbotamaPay")
                .callbackUrl(baseUrl + "/api/v1/payments/callback/" + transaction.getPlatform())
                .returnUrl(frontendUrl + "/pay/success?ref=" + transaction.getExternalReference())
                .cancelUrl(frontendUrl + "/pay/failed?ref=" + transaction.getExternalReference())
                .build();

        PaymentInitResponse response = gateway.initiatePayment(request);

        if (response.isSuccess()) {
            transaction.setStatus(TransactionStatus.PROCESSING);
            transactionRepository.save(transaction);
        }

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/callback/{platform}")
    @Operation(summary = "Payment callback", description = "Receives payment status from gateway")
    public ResponseEntity<String> handleCallback(
            @PathVariable String platform,
            @RequestBody(required = false) Map<String, Object> payload,
            @RequestHeader(value = "X-Signature", required = false) String signature) {

        log.info("Payment callback received from {}: {}", platform, payload);

        PaymentGateway gateway = gatewayService.getGateway(platform);

        // Verify webhook signature
        if (!gateway.verifyWebhookSignature(payload.toString(), signature)) {
            log.warn("Invalid webhook signature from {}", platform);
            return ResponseEntity.badRequest().body("Invalid signature");
        }

        // Extract reference from payload (platform-specific)
        String reference = extractReference(platform, payload);
        if (reference == null) {
            log.warn("Could not extract reference from callback payload");
            return ResponseEntity.badRequest().body("Missing reference");
        }

        // Check payment status
        PaymentStatusResponse status = gateway.checkStatus(reference);

        // Update transaction
        transactionRepository.findByExternalReference(reference).ifPresent(transaction -> {
            switch (status.getStatus()) {
                case "COMPLETED" -> {
                    transaction.setStatus(TransactionStatus.COMPLETED);
                    transaction.setCompletedAt(LocalDateTime.now());
                }
                case "FAILED" -> transaction.setStatus(TransactionStatus.FAILED);
                case "CANCELLED" -> transaction.setStatus(TransactionStatus.CANCELLED);
                // PENDING - no change
            }
            transactionRepository.save(transaction);
            log.info("Transaction {} updated to {}", reference, transaction.getStatus());
        });

        return ResponseEntity.ok("OK");
    }

    @GetMapping("/status/{reference}")
    @Operation(summary = "Check payment status", description = "Checks the status of a payment")
    public ResponseEntity<ApiResponse<PaymentStatusResponse>> checkStatus(
            @PathVariable String reference) {

        Transaction transaction = transactionRepository.findByExternalReference(reference)
                .orElseThrow(() -> new RuntimeException("Transaction non trouvée"));

        PaymentGateway gateway = gatewayService.getGateway(transaction.getPlatform());
        PaymentStatusResponse status = gateway.checkStatus(reference);

        return ResponseEntity.ok(ApiResponse.success(status));
    }

    private String extractReference(String platform, Map<String, Object> payload) {
        return switch (platform.toLowerCase()) {
            case "feexpay" -> (String) payload.get("custom_id");
            case "cinetpay" -> (String) payload.get("cpm_trans_id");
            case "paytech" -> (String) payload.get("ref_command");
            default -> (String) payload.get("reference");
        };
    }
}
