package com.mbotamapay.controller;

import com.mbotamapay.dto.ApiResponse;
import com.mbotamapay.dto.transaction.TransactionRequest;
import com.mbotamapay.dto.transaction.TransactionResponse;
import com.mbotamapay.entity.User;
import com.mbotamapay.service.TransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Transaction Controller for money transfers
 */
@RestController
@RequestMapping("/transactions")
@RequiredArgsConstructor
@Tag(name = "Transactions", description = "APIs for money transfers")
@SecurityRequirement(name = "bearerAuth")
public class TransactionController {

    private final TransactionService transactionService;

    @PostMapping
    @Operation(summary = "Initiate transaction", description = "Creates a new money transfer")
    @PreAuthorize("hasAnyRole('KYC_LEVEL_1', 'KYC_LEVEL_2')")
    public ResponseEntity<ApiResponse<TransactionResponse>> initiateTransaction(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody TransactionRequest request) {
        TransactionResponse response = transactionService.initiateTransaction(user.getId(), request);
        return ResponseEntity.ok(ApiResponse.success("Transaction initiée", response));
    }

    @GetMapping
    @Operation(summary = "Get user transactions", description = "Returns paginated list of user transactions")
    public ResponseEntity<ApiResponse<Page<TransactionResponse>>> getTransactions(
            @AuthenticationPrincipal User user,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<TransactionResponse> transactions = transactionService.getUserTransactions(user.getId(), pageable);
        return ResponseEntity.ok(ApiResponse.success(transactions));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get transaction details", description = "Returns details of a specific transaction")
    public ResponseEntity<ApiResponse<TransactionResponse>> getTransaction(
            @AuthenticationPrincipal User user,
            @PathVariable Long id) {
        TransactionResponse response = transactionService.getTransaction(user.getId(), id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
