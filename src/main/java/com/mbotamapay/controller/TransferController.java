package com.mbotamapay.controller;

import com.mbotamapay.dto.ApiResponse;
import com.mbotamapay.dto.transfer.TransferPreviewRequestDto;
import com.mbotamapay.dto.transfer.TransferPreviewResponseDto;
import com.mbotamapay.dto.transfer.TransferRequestDto;
import com.mbotamapay.dto.transfer.TransferResponseDto;
import com.mbotamapay.entity.User;
import com.mbotamapay.entity.enums.MobileOperator;
import com.mbotamapay.service.OperatorService;
import com.mbotamapay.service.TransferService;
import com.mbotamapay.service.TransferService.TransferPreview;
import com.mbotamapay.service.TransferService.TransferRequest;
import com.mbotamapay.service.TransferService.TransferResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Controller pour les transferts d'argent avec routage intelligent
 *
 * Endpoints:
 * - POST /transfers/preview : Prévisualise les frais et la route
 * - POST /transfers : Exécute le transfert
 */
@RestController
@RequestMapping("/transfers")
@RequiredArgsConstructor
@Tag(name = "Transfers", description = "APIs pour les transferts d'argent avec routage intelligent")
@SecurityRequirement(name = "bearerAuth")
@Slf4j
public class TransferController {

    private final TransferService transferService;
    private final OperatorService operatorService;

    /**
     * Prévisualise un transfert sans l'exécuter.
     *
     * <p>
     * Retourne aussi un identifiant de devis : le présenter à l'exécution
     * garantit que le prix affiché est bien celui qui sera débité.
     */
    @PostMapping("/preview")
    @Operation(summary = "Prévisualiser un transfert",
            description = "Calcule les frais et la route optimale, et émet un devis valable quelques minutes")
    public ResponseEntity<ApiResponse<TransferPreviewResponseDto>> previewTransfer(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody TransferPreviewRequestDto request) {

        // Si l'appelant est authentifié, le numéro de l'expéditeur vient du compte :
        // c'est lui qui détermine le pays source, donc le corridor et le barème.
        String senderPhone = user != null ? user.getPhoneNumber() : request.getSenderPhone();

        log.info("Transfer preview: {} -> {} ({}), amount={}",
                senderPhone, request.getRecipientPhone(), request.getDestOperator(), request.getAmount());

        TransferPreview preview = transferService.previewTransfer(
                senderPhone,
                request.getRecipientPhone(),
                request.getAmount(),
                user != null ? user.getId() : null);

        TransferPreviewResponseDto response = mapToPreviewResponse(preview,
                request.getSourceOperator(), request.getDestOperator());

        if (!preview.isAvailable()) {
            return ResponseEntity.ok(ApiResponse.success("Route non disponible", response));
        }
        return ResponseEntity.ok(ApiResponse.success("Preview calculé", response));
    }

    /**
     * Exécute un transfert complet.
     */
    @PostMapping
    @Operation(summary = "Exécuter un transfert",
            description = "Exécute un transfert avec routage intelligent. Fournir quoteId pour garantir le prix coté.")
    @PreAuthorize("hasAnyRole('KYC_LEVEL_1', 'KYC_LEVEL_2')")
    public ResponseEntity<ApiResponse<TransferResponseDto>> executeTransfer(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody TransferRequestDto request) {

        log.info("Transfer execution: userId={}, recipient={} ({}), amount={}, quote={}",
                user.getId(), request.getRecipientPhone(), request.getDestOperator(),
                request.getAmount(), request.getQuoteId());

        TransferRequest transferRequest = TransferRequest.builder()
                .senderPhone(request.getSenderPhone())
                .sourceOperator(request.getSourceOperator())
                .recipientPhone(request.getRecipientPhone())
                .recipientName(request.getRecipientName())
                .destOperator(request.getDestOperator())
                .amount(request.getAmount())
                .description(request.getDescription())
                .quoteId(request.getQuoteId())
                .build();

        TransferResult result = transferService.executeTransfer(user.getId(), transferRequest);

        TransferResponseDto response = mapToTransferResponse(result,
                request.getSourceOperator(), request.getDestOperator());

        if (!result.isSuccess()) {
            return ResponseEntity.ok(ApiResponse.error(result.getMessage()));
        }
        return ResponseEntity.ok(ApiResponse.success("Transfert initié avec succès", response));
    }

    // --- Mappers ---

    private TransferPreviewResponseDto mapToPreviewResponse(TransferPreview preview,
            String sourceOperator, String destOperator) {
        return TransferPreviewResponseDto.builder()
                .available(preview.isAvailable())
                .amount(preview.getAmount())
                .fee(preview.getFee())
                .totalAmount(preview.getTotalAmount())
                .feePercent(preview.getDisplayFeePercent())
                .gatewayFee(preview.getGatewayFee())
                .appFee(preview.getAppFee())
                .gateway(preview.getGateway())
                .sourceCountry(preview.getSourceCountry())
                .destCountry(preview.getDestCountry())
                .sourceOperatorName(getOperatorDisplayName(sourceOperator))
                .destOperatorName(getOperatorDisplayName(destOperator))
                .sourceCurrency(preview.getSourceCurrency())
                .payoutAmount(preview.getPayoutAmount())
                .payoutCurrency(preview.getPayoutCurrency())
                .routingStrategy(preview.getRoutingStrategy())
                .routingScore(preview.getRoutingScore())
                .fallbackGateways(preview.getFallbackGateways())
                .quoteId(preview.getQuoteId())
                .quoteExpiresAt(preview.getQuoteExpiresAt())
                .reason(preview.getReason())
                .rejectionReasons(preview.getRejectionReasons())
                .build();
    }

    private TransferResponseDto mapToTransferResponse(TransferResult result,
            String sourceOperator, String destOperator) {
        return TransferResponseDto.builder()
                .success(result.isSuccess())
                .transactionId(result.getTransactionId())
                .reference(result.getReference())
                .amount(result.getAmount())
                .fee(result.getFee())
                .totalAmount(result.getTotalAmount())
                .feePercent(result.getDisplayFeePercent())
                .status(result.getStatus())
                .message(result.getMessage())
                .gateway(result.getGateway())
                .sourceCountry(result.getSourceCountry())
                .destCountry(result.getDestCountry())
                .sourceOperatorName(getOperatorDisplayName(sourceOperator))
                .destOperatorName(getOperatorDisplayName(destOperator))
                .build();
    }

    private String getOperatorDisplayName(String operatorCode) {
        if (operatorCode == null) {
            return null;
        }
        return operatorService.getOperatorByCode(operatorCode)
                .map(MobileOperator::getDisplayName)
                .orElse(operatorCode);
    }
}
