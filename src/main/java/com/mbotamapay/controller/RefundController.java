package com.mbotamapay.controller;

import com.mbotamapay.dto.ApiResponse;
import com.mbotamapay.dto.refund.RefundRequest;
import com.mbotamapay.dto.refund.RefundResponse;
import com.mbotamapay.entity.User;
import com.mbotamapay.service.RefundService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Refund Controller
 * Handles refund requests and status checks
 */
@RestController
@RequestMapping("/refunds")
@RequiredArgsConstructor
@Tag(name = "Refunds", description = "Refund management APIs")
public class RefundController {

    private final RefundService refundService;

    @PostMapping("/transaction/{transactionId}")
    @Operation(summary = "Request refund", description = "Request a refund for a completed transaction")
    public ResponseEntity<ApiResponse<RefundResponse>> requestRefund(
            @AuthenticationPrincipal User user,
            @PathVariable Long transactionId,
            @Valid @RequestBody RefundRequest request) {

        RefundResponse response = refundService.initiateRefund(user.getId(), transactionId, request);
        return ResponseEntity.ok(ApiResponse.success("Demande de remboursement créée", response));
    }

    @GetMapping("/{refundId}")
    @Operation(summary = "Get refund", description = "Get refund details by ID")
    public ResponseEntity<ApiResponse<RefundResponse>> getRefund(
            @AuthenticationPrincipal User user,
            @PathVariable Long refundId) {

        RefundResponse response = refundService.getRefund(user.getId(), refundId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/transaction/{transactionId}")
    @Operation(summary = "Get refund by transaction", description = "Get refund status for a transaction")
    public ResponseEntity<ApiResponse<RefundResponse>> getRefundByTransaction(
            @AuthenticationPrincipal User user,
            @PathVariable Long transactionId) {

        RefundResponse response = refundService.getRefundByTransaction(user.getId(), transactionId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping
    @Operation(summary = "Get my refunds", description = "Get all refunds for the current user")
    public ResponseEntity<ApiResponse<Page<RefundResponse>>> getMyRefunds(
            @AuthenticationPrincipal User user,
            Pageable pageable) {

        Page<RefundResponse> refunds = refundService.getUserRefunds(user.getId(), pageable);
        return ResponseEntity.ok(ApiResponse.success(refunds));
    }
}
